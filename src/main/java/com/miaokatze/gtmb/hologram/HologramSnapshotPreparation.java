package com.miaokatze.gtmb.hologram;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

/** Main-thread, bounded full DTO preparation. A reserved FIFO slot protects the future delta baseline. */
final class HologramSnapshotPreparation {

    private static final Map<HologramSession, Task> TASKS = new IdentityHashMap<>();
    private static final int MAX_TASKS = 32;
    private static final int MAX_MATERIALIZED = 4;

    private HologramSnapshotPreparation() {}

    static boolean pending(HologramSession s) {
        return TASKS.containsKey(s);
    }

    static boolean canAdvance(HologramSession s) {
        Task task = TASKS.get(s);
        if (task == null || task.encoding || task.metadata != null && task.metadata.waitingForWorker()) return false;
        if (task.stage == 0) {
            int materialized = 0;
            for (Task queued : TASKS.values()) if (queued.state != null) materialized++;
            if (materialized >= MAX_MATERIALIZED) return false;
        }
        return true;
    }

    static void cancel(HologramSession s) {
        Task task = TASKS.get(s);
        if (task != null) cancel(task);
    }

    /** Cancelling a save never schedules another full traversal; the client decides if geometry is missing. */
    static void cancelSaveSource(net.minecraft.entity.player.EntityPlayerMP player, NBTTagCompound request) {
        HologramSession session = HologramWorkspace.find(player.getUniqueID());
        Task task = session == null ? null : TASKS.get(session);
        if (task == null || task.clientTask <= 0
            || task.clientTask != request.getLong("clientTaskToken")
            || !task.session.equals(request.getString("session"))
            || !request.hasKey("uiSequence", 4)
            || task.ui != request.getLong("uiSequence")) return;
        cancel(task);
        NBTTagCompound reply = HologramLibraryProtocol.reply("sourceCancelled");
        reply.setString("session", task.session);
        reply.setLong("planRevision", task.revision);
        reply.setLong("uiSequence", task.ui);
        reply.setLong("clientTaskToken", task.clientTask);
        reply.setInteger("cellCount", task.size);
        HologramNetwork.sendLibraryReply(player, reply);
    }

    static void start(HologramSession s) {
        Task previous = TASKS.get(s);
        if (previous != null && previous.valid()) return;
        if (previous != null) cancel(previous);
        if (TASKS.size() >= MAX_TASKS) {
            if (s.presentationTransition != null) HologramNetwork.transportFailure(
                s.player,
                s.id,
                s.uiSequence,
                "snapshot.queue",
                s.blueprintClientTaskToken,
                new IllegalStateException("snapshot preparation queue full"));
            return;
        }
        HologramNetwork.SnapshotTicket ticket = HologramNetwork.reserveOwnedState(s);
        if (ticket == null) return;
        Task task = new Task(s, ticket);
        TASKS.put(s, task);
        task.progress("snapshot.prepare", "metadata", 0, 0, true);
    }

    static boolean tick(HologramSession s) {
        Task task = TASKS.get(s);
        if (task == null) return false;
        if (!task.valid()) {
            cancel(task);
            if (!task.ticket.failed() && HologramWorkspace.find(s.player.getUniqueID()) == s
                && HologramSessionPolicy.tickValid(s)
                && !s.cancelled) HologramSnapshots.send(s);
            return true;
        }
        if (task.encoding) {
            task.progress("snapshot.encode", "gzip", task.ticket.encodingBytes(), 0, false);
            return true;
        }
        try {
            // Cheap DTO rows should consume the available time slice, not one artificial tick per 256 rows.
            task.step(
                HologramWorkBudget.deadline(HologramWorkBudget.remainingNanos(HologramWorkBudget.SLICE_NANOS)),
                System::nanoTime);
        } catch (RuntimeException | LinkageError failure) {
            String failedPhase = task.metadata == null ? task.stageDetail() : task.metadata.phase();
            long failedDone = task.metadata == null ? task.stageDone() : task.metadata.done();
            long failedTotal = task.metadata == null ? task.stageTotal() : task.metadata.total();
            cancel(task);
            HologramNetwork.transportFailure(
                s.player,
                task.session,
                task.ui,
                "snapshot.prepare",
                task.clientTask,
                new IllegalStateException(
                    "Hologram snapshot preparation failed phase=" + failedPhase
                        + " done="
                        + failedDone
                        + "/"
                        + failedTotal,
                    failure));
        }
        return true;
    }

    /** Retired sessions are no longer visited by WorkspaceScheduler; transport owns their final cleanup. */
    static void pruneRetired() {
        for (Task task : new java.util.ArrayList<>(TASKS.values())) {
            HologramSession s = task.s;
            if (HologramWorkspace.find(s.player.getUniqueID()) != s || !HologramNetwork.progressConnected(s.player)
                || s.player.worldObj != task.world
                || s.player.dimension != s.dimension
                || !task.session.equals(s.id)
                || !task.ticket.current()) cancel(task);
        }
    }

    static void clear() {
        for (Task task : TASKS.values()) {
            if (task.metadata != null) task.metadata.cancel();
            HologramNetwork.cancelState(task.ticket);
        }
        TASKS.clear();
    }

    private static void cancel(Task task) {
        TASKS.remove(task.s);
        if (task.metadata != null) task.metadata.cancel();
        HologramNetwork.cancelState(task.ticket);
        if (task.state != null && task.session.equals(task.s.id)) {
            task.s.open |= task.state.getBoolean("open");
            task.s.hints |= task.state.getBoolean("hints");
            task.s.libraryInitialSnapshot |= task.state.getBoolean("libraryInitialSnapshot");
            if (task.s.openPurpose.isEmpty()) task.s.openPurpose = task.state.getString("openPurpose");
            if (task.s.cutWorkspaceRequestId.isEmpty())
                task.s.cutWorkspaceRequestId = task.state.getString("cutWorkspaceRequestId");
        }
        task.state = null;
        task.metadata = null;
    }

    /** Production checkpoint loop also used by fake-clock regressions. One native callback is atomic. */
    static int runSlice(Runnable operation, java.util.function.BooleanSupplier complete, long deadline,
        LongSupplier clock) {
        return runSlice(operation, complete, Integer.MAX_VALUE, deadline, clock);
    }

    static int runSlice(Runnable operation, java.util.function.BooleanSupplier complete, int maxWork, long deadline,
        LongSupplier clock) {
        int work = 0;
        while (!complete.getAsBoolean() && work < maxWork && (work == 0 || clock.getAsLong() < deadline)) {
            operation.run();
            work++;
        }
        return work;
    }

    static long stockLimit(long demand, long reserved, long pending) {
        long protectedStock = Math.max(0L, reserved);
        long pendingStock = Math.max(0L, pending);
        protectedStock = pendingStock > Long.MAX_VALUE - protectedStock ? Long.MAX_VALUE
            : protectedStock + pendingStock;
        long wanted = Math.max(0L, demand);
        return wanted > Long.MAX_VALUE - protectedStock ? Long.MAX_VALUE : wanted + protectedStock;
    }

    private static final class Task {

        final HologramSession s;
        final HologramNetwork.SnapshotTicket ticket;
        final HologramCapture capture;
        final World world;
        final String session, phase, active;
        final long revision, ui, batch, planGeneration, clientTask;
        final long voucherRevision = DecorationVoucherConfig.revision();
        final long declarationGeneration = HologramDeclarationAdapters.generation();
        final int x, y, z, size, job, scope, layer, selected;
        final boolean authoring, draftOnly;
        final String planError;
        final HologramSnapshots.CellRows rowContext;
        NBTTagCompound state;
        HologramSnapshotMetadataTask metadata;
        final NBTTagList cells = new NBTTagList(), materials = new NBTTagList(), recovery = new NBTTagList();
        final NBTTagList hatchEntries = new NBTTagList();
        boolean structureValid = true, structureFound;
        final Map<HologramBlueprintResources.ExactKey, NBTTagCompound> requirements = new LinkedHashMap<>();
        final Map<HologramBlueprintResources.ExactKey, NBTTagCompound> voucherDecor = new LinkedHashMap<>(),
            ordinaryDecor = new LinkedHashMap<>();
        final Map<HologramBlueprintResources.ExactKey, Long> oldDecor = new LinkedHashMap<>(),
            ordinaryReserved = new LinkedHashMap<>(), voucherReserved = new LinkedHashMap<>();
        final HologramDecorMaterials.Ledger ordinaryLedger, voucherLedger;
        final HologramMaterialQuote.StockSnapshot inventory;
        Iterator<NBTTagCompound> materialIterator;
        HologramMaterialQuote.Preparation materialQuote;
        int stage, index, protectedCount, total, workTotal, recoveryRows, recoveryItems;
        boolean encoding;
        long lastTick = Long.MIN_VALUE;
        long lastDone = -1, lastTotal = -1;
        String lastDetail = "";

        Task(HologramSession s, HologramNetwork.SnapshotTicket ticket) {
            this.s = s;
            inventory = new HologramMaterialQuote.StockSnapshot(HologramMaterialQuote.stock(s));
            ordinaryLedger = s.decorLedger.copy();
            voucherLedger = s.decorLedger.copy();
            for (ItemStack payment : s.decorPlanItems)
                HologramValues.mergeStack(oldDecor, payment, (long) payment.stackSize, Long::sum);
            rowContext = new HologramSnapshots.CellRows(s);
            this.ticket = ticket;
            capture = s.capture;
            world = s.player.worldObj;
            session = s.id;
            phase = s.phase;
            active = s.blueprintActiveStructure;
            scope = s.scope;
            layer = s.layer;
            selected = s.selected;
            revision = s.planRevision;
            ui = s.uiSequence;
            clientTask = s.blueprintClientTaskToken;
            batch = s.batchSeq;
            planGeneration = s.localPlanGeneration;
            authoring = HologramSessionPolicy.authoring(s);
            draftOnly = authoring || s.localPlanPending && !s.localPlanError.isEmpty();
            planError = s.localPlanError;
            x = s.x;
            y = s.y;
            z = s.z;
            size = capture == null ? 0 : capture.cells.size();
            job = s.job;
        }

        boolean valid() {
            return ticket.current() && HologramWorkspace.find(s.player.getUniqueID()) == s
                && voucherRevision == DecorationVoucherConfig.revision()
                && declarationGeneration == HologramDeclarationAdapters.generation()
                && session.equals(s.id)
                && s.player.worldObj == world
                && capture == s.capture
                && size == (s.capture == null ? 0 : s.capture.cells.size())
                && revision == s.planRevision
                && ui == s.uiSequence
                && batch == s.batchSeq
                && planGeneration == s.localPlanGeneration
                && authoring == HologramSessionPolicy.authoring(s)
                && draftOnly == (authoring || s.localPlanPending && !s.localPlanError.isEmpty())
                && planError.equals(s.localPlanError)
                && x == s.x
                && y == s.y
                && z == s.z
                && job == s.job
                && phase.equals(s.phase)
                && active.equals(s.blueprintActiveStructure)
                && scope == s.scope
                && layer == s.layer
                && selected == s.selected
                && !s.cancelled
                && HologramSessionPolicy.tickValid(s);
        }

        void step(long deadline, LongSupplier clock) {
            if (stage == 0) {
                int materialized = 0;
                for (Task queued : TASKS.values()) if (queued.state != null) materialized++;
                if (materialized >= MAX_MATERIALIZED) {
                    progress("snapshot.prepare", "queued", 0, 0, false);
                    return;
                }
                state = HologramSnapshots.basicState(s);
                HologramRuntimeMetrics
                    .loadStage(HologramRuntimeMetrics.LoadStage.SERVER_METADATA_BEGIN, s, ticket.sequence(), size);
                metadata = new HologramSnapshotMetadataTask(s, state, ticket.sequence());
                stage = 1;
                if (clock.getAsLong() >= deadline) return;
            }
            if (stage == 1) {
                if (!metadata.step(Integer.MAX_VALUE, deadline)) {
                    HologramAreaPreparation.Progress current = metadata.progress();
                    progress("snapshot.prepare", current.phase, current.done, current.total, false);
                    return;
                }
                metadata = null;
                HologramRuntimeMetrics
                    .loadStage(HologramRuntimeMetrics.LoadStage.SERVER_METADATA_END, s, ticket.sequence(), size);
                HologramRuntimeMetrics
                    .loadStage(HologramRuntimeMetrics.LoadStage.SERVER_ROWS_BEGIN, s, ticket.sequence(), size);
                HologramLocalPlanRefresh.snapshotBegin(s);
                stage = 2;
                if (clock.getAsLong() >= deadline) return;
            }
            runSlice(() -> unit(deadline), () -> encoding, deadline, clock);
            if (!encoding) progress("snapshot.prepare", stageDetail(), stageDone(), stageTotal(), false);
        }

        String stageDetail() {
            switch (stage) {
                case 2:
                    return "cache_defaults";
                case 3:
                    return "cache_source";
                case 4:
                    return "cache_palette";
                case 5:
                    return "cells";
                case 6:
                    return "resources";
                case 7:
                    return "materials";
                case 8:
                    return "recovery";
                case 9:
                    return materialQuote == null ? "inventory"
                        : "inventory." + materialQuote.phase()
                            .substring(6);
                case 10:
                    return "metadata";
                default:
                    return "metadata";
            }
        }

        long stageDone() {
            return stage == 9 && materialQuote != null ? materialQuote.completed() : index;
        }

        long stageTotal() {
            if (stage == 2)
                return s.localEdits.snapshotReuseIndices || s.virtualPreset == null ? 0 : s.virtualPreset.cells.size();
            if (stage == 3) return s.localEdits.snapshotReuseIndices || s.processingSource == null ? 0
                : s.processingSource.cells.size();
            if (stage == 4) return s.localEdits.snapshotReuseIndices || s.processingSource == null ? 0
                : s.processingSource.palette.size();
            if (stage == 5) return size;
            if (draftOnly && stage >= 6) return 0;
            if (stage == 9) return materialQuote == null ? 0 : materialQuote.total();
            if (stage == 10) return 1;
            if (stage == 6) return s.blueprintResources == null ? 0 : s.blueprintResources.items.size();
            if (stage == 7) return requirements.size();
            return s.recovered.size();
        }

        void unit(long deadline) {
            int previousStage = stage;
            while (stage >= 2 && stage <= 8 && index >= stageTotal()) {
                stage++;
                index = 0;
                if (stage == 7) {
                    materialIterator = requirements.values()
                        .iterator();
                }
            }
            if (stage != previousStage) progress("snapshot.prepare", stageDetail(), stageDone(), stageTotal(), true);
            if (stage == 2) HologramLocalPlanRefresh.snapshotSeedDefaults(s, index++);
            else if (stage == 3) HologramLocalPlanRefresh.snapshotSeedSource(s, index++);
            else if (stage == 4) HologramLocalPlanRefresh.snapshotSeedPalette(s, index++);
            else if (stage == 5) addCell(index++);
            else if (stage == 6) {
                ItemStack content = s.blueprintResources.items.get(index++);
                addResource(content);
            } else if (stage == 7) {
                NBTTagCompound req = materialIterator.next();
                quoteMaterial(req);
                materials.appendTag(req);
                index++;
            } else if (stage == 8) {
                ItemStack drop = s.recovered.get(index++);
                recoveryRows++;
                recoveryItems += drop.stackSize;
                if (recovery.tagCount() < 96) recovery.appendTag(HologramSnapshots.recoverySummary(drop, false));
            } else if (stage == 9) {
                if (draftOnly) {
                    stage = 10;
                    return;
                }
                if (materialQuote == null) {
                    NBTTagList voucherMaterials = HologramMaterialQuote.voucherRows(materials);
                    HologramMaterialQuote.mergeRows(materials, ordinaryDecor.values());
                    HologramMaterialQuote.mergeRows(voucherMaterials, voucherDecor.values());
                    state.setTag("materials", materials);
                    materialQuote = new HologramMaterialQuote.Preparation(
                        s,
                        materials,
                        voucherMaterials,
                        state.getTagList("blueprintResources", 10),
                        inventory);
                    progress("snapshot.prepare", stageDetail(), stageDone(), stageTotal(), true);
                }
                progress("snapshot.prepare", stageDetail(), stageDone(), stageTotal(), false);
                boolean quoted = materialQuote.step(1, deadline);
                progress("snapshot.prepare", stageDetail(), stageDone(), stageTotal(), false);
                if (quoted) {
                    state.setTag("materialQuote", materialQuote.result());
                    HologramMaterialWorkflow.writeState(s, state);
                    stage = 10;
                    index = 0;
                }
            } else if (stage == 10) finish();
        }

        void addCell(int index) {
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.FULL_CELLS_VISITED, 1);
            HologramLocalPlanRefresh.snapshotSeedCapture(s, index);
            HologramCapture.Cell c = s.capture.cells.get(index);
            if (!draftOnly && HologramSessionPolicy.ignoredPlacementAir(s, c, index)) {
                HologramSnapshots.CellRow air = HologramSnapshots.cellRowData(s, index, false, rowContext);
                cells.appendTag(air.row);
                HologramLocalPlanRefresh.snapshotCell(s, index, air.row, null, air.inScope);
                return;
            }
            if (!draftOnly) HologramHatchProfiles.captureCell(s, index, hatchEntries);
            if (!draftOnly && !s.virtual && s.machine != null && !s.machine.mMachine && structureValid) {
                int built = HologramPlanPolicy.replacementCellBuilt(s, c);
                if (built < 0) structureValid = false;
                if (built > 0) structureFound = true;
            }
            HologramSnapshots.CellRow observed = HologramSnapshots.cellRowData(s, index, draftOnly, rowContext);
            NBTTagCompound row = observed.row;
            cells.appendTag(row);
            if (draftOnly) {
                HologramLocalPlanRefresh.snapshotDraftCell(s, index, row);
                if (observed.inScope) total++;
                return;
            }
            HologramLocalPlanRefresh.snapshotCell(s, index, row, observed.target, observed.inScope);
            World world = s.player.worldObj;
            boolean loaded = observed.loaded;
            Block current = observed.current;
            int meta = observed.metadata;
            ItemStack pinned = observed.target;
            if (observed.inScope
                && ("protected".equals(c.status) || "unsupported".equals(c.status) || "blocked".equals(c.status)))
                protectedCount++;
            if (observed.inScope) total++;
            if (observed.inScope && index < s.planStatus.size() && "pending".equals(s.planStatus.get(index)))
                workTotal++;

            if (!"satisfied".equals(c.status) && !"protected".equals(c.status)
                && !"unsupported".equals(c.status)
                && !"placed".equals(c.status)
                && !"replaced".equals(c.status)
                && pinned != null
                && !HologramCellPolicy.orientationOnly(s, c, index)
                && observed.inScope
                && s.mode != 2
                && !HologramCellPolicy.decorationTarget(s, c)
                && !(s.virtual && s.virtualPreset != null
                    && s.virtualPreset.schema >= 2
                    && HologramGtswnPlacementMaterials.freeShell(c.block, pinned))) {
                ItemStack stack = HologramEssentia.shellMaterial(pinned);
                stack.stackSize = 1;
                HologramBlueprintResources.ExactKey key = HologramValues.lookupKey(stack);
                NBTTagCompound req = requirements.get(key);
                if (req == null) {
                    req = new NBTTagCompound();
                    stack.writeToNBT(req);
                    requirements.put(HologramValues.stackKey(stack), req);
                }
                req.setLong("required", Math.addExact(req.getLong("required"), 1L));
                req.setLong("shellRequired", Math.addExact(req.getLong("shellRequired"), 1L));
                if (!HologramRecovery.hasSeal(pinned) && HologramDecorationVoucher.covered(c.block, pinned))
                    req.setLong("voucherShellRequired", Math.addExact(req.getLong("voucherShellRequired"), 1L));
            }
            if (observed.inScope && "pending".equals(c.status) && s.mode != 2)
                for (ItemStack cover : HologramCellPolicy.coverCosts(s, c)) {
                    HologramBlueprintResources.ExactKey key = HologramValues.lookupKey(cover);
                    NBTTagCompound requirement = requirements.get(key);
                    if (requirement == null) {
                        requirement = cover.writeToNBT(new NBTTagCompound());
                        requirements.put(HologramValues.stackKey(cover), requirement);
                    }
                    requirement.setLong("required", Math.addExact(requirement.getLong("required"), 1L));
                    requirement.setLong("exactRequired", Math.addExact(requirement.getLong("exactRequired"), 1L));
                }
            if (observed.inScope && "pending".equals(c.status)
                && s.mode != 2
                && !HologramCellPolicy.orientationOnly(s, c, index)) try {
                    HologramDecorMaterials.Descriptor descriptor = HologramCellPolicy.decoration(s, c);
                    if (descriptor != null) {
                        addDecoration(descriptor, ordinaryLedger, ordinaryReserved, ordinaryDecor);
                        addDecoration(
                            HologramDecorationVoucher.payable(c.block, descriptor),
                            voucherLedger,
                            voucherReserved,
                            voucherDecor);
                    }
                } catch (java.io.IOException invalid) {
                    s.planRecoveryFits = false;
                    s.blueprintResourceMissing = invalid.getMessage();
                }
            if (observed.inScope && "pending".equals(c.status)
                && !HologramCellPolicy.orientationOnly(s, c, index)
                && loaded
                && !HologramCellPolicy.trivial(world, c.x, c.y, c.z)
                && (!s.player.capabilities.isCreativeMode || observed.tile != null)) try {
                    for (ItemStack drop : HologramMaterialAccess.drops(current, world, c, meta)) {
                        recoveryRows++;
                        recoveryItems += drop.stackSize;
                        if (recovery.tagCount() < 96) recovery.appendTag(HologramSnapshots.recoverySummary(drop, true));
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    row.setString("reason", "预计掉落无法确定，施工将重验并可暂停");
                }
        }

        void addResource(ItemStack content) {
            HologramBlueprintResources.ExactKey key = HologramValues.lookupKey(content);
            long amount = Math.max(0L, (long) content.stackSize - oldDecor.getOrDefault(key, 0L));
            if (amount == 0) return;
            NBTTagCompound requirement = requirements.get(key);
            if (requirement == null) {
                ItemStack identity = content.copy();
                identity.stackSize = 1;
                requirement = identity.writeToNBT(new NBTTagCompound());
                requirements.put(HologramValues.stackKey(content), requirement);
            }
            requirement.setLong("required", Math.addExact(requirement.getLong("required"), amount));
            requirement.setLong("exactRequired", Math.addExact(requirement.getLong("exactRequired"), amount));
        }

        void addDecoration(HologramDecorMaterials.Descriptor descriptor, HologramDecorMaterials.Ledger ledger,
            Map<HologramBlueprintResources.ExactKey, Long> reserved,
            Map<HologramBlueprintResources.ExactKey, NBTTagCompound> rows) throws java.io.IOException {
            HologramDecorMaterials.Quote quote = ledger.quote(descriptor, (item, limit) -> {
                HologramBlueprintResources.ExactKey key = HologramValues.lookupKey(item);
                long pending = s.pendingAssetBudget.getOrDefault(key, 0L);
                long protectedStock = stockLimit(0, reserved.getOrDefault(key, 0L), pending);
                long perCell = Math.max(1L, limit);
                long geometryDemand = size > Long.MAX_VALUE / perCell ? Long.MAX_VALUE : (long) size * perCell;
                long request = Math.max(
                    stockLimit(limit, reserved.getOrDefault(key, 0L), pending),
                    Math.max(
                        stockLimit(geometryDemand, pending, 0),
                        stockLimit(oldDecor.getOrDefault(key, 0L), pending, 0)));
                return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, inventory.exact(item, request) - protectedStock));
            });
            for (ItemStack item : quote.items) {
                HologramValues.mergeStack(reserved, item, (long) item.stackSize, Long::sum);
                HologramBlueprintResources.ExactKey key = HologramValues.stackKey(item);
                NBTTagCompound row = rows.computeIfAbsent(key, ignored -> {
                    ItemStack identity = item.copy();
                    identity.stackSize = 1;
                    return identity.writeToNBT(new NBTTagCompound());
                });
                row.setLong("required", Math.addExact(row.getLong("required"), (long) item.stackSize));
            }
            ledger.commit(quote);
        }

        void quoteMaterial(NBTTagCompound row) {
            ItemStack identity = ItemStack.loadItemStackFromNBT(row);
            int required = (int) Math.min(Integer.MAX_VALUE, Math.max(4096L, row.getLong("required")));
            row.setInteger(
                "mainAvailable",
                HologramMaterialAccess.materialSource(s)
                    .mainCount(identity, required));
            row.setInteger(
                "controllerAvailable",
                row.getLong("shellRequired") > 0 && s.target instanceof net.minecraft.inventory.IInventory
                    && ((net.minecraft.inventory.IInventory) s.target).isUseableByPlayer(s.player)
                        ? HologramMaterials
                            .controllerCount((net.minecraft.inventory.IInventory) s.target, identity, required)
                        : -1);
        }

        void finish() {
            NBTTagCompound profile = new NBTTagCompound();
            profile.setInteger("tier", s.hatchTier);
            profile.setBoolean("downgrade", s.hatchDowngrade);
            profile.setTag("entries", hatchEntries);
            state.setTag("hatchProfile", profile);
            state.setBoolean(
                "structureBuilt",
                !draftOnly && !s.virtual
                    && s.machine != null
                    && (s.machine.mMachine || s.target != null && capture != null
                        && !capture.pending
                        && !capture.incomplete
                        && structureValid
                        && structureFound));
            state.setInteger("cellCount", cells.tagCount());
            state.setTag("cells", cells);
            state.setBoolean("planIncomplete", s.localPlanPending);
            state.setBoolean("quotesIncomplete", draftOnly);
            if (draftOnly) {
                state.setBoolean("recoveryFits", false);
                state.setBoolean("quotesIncomplete", true);
            }
            state.setInteger("recoveryMore", Math.max(0, recoveryRows - recovery.tagCount()));
            state.setInteger("recoveryTotal", recoveryItems);
            if (!draftOnly) state.setTag("recovery", recovery);
            state.setInteger("protected", protectedCount);
            state.setInteger("missing", draftOnly ? 0 : s.planMissing);
            state.setInteger("total", total);
            state.setInteger("workTotal", workTotal);
            state.setString(
                "description",
                authoring ? "作者预览；进入放置时核对现场方块与材料。" : "控制器始终保留；直接施工会重新扫描并逐格重验。手选目标覆盖对应位置，不保证结构成型；差分可供查看材料与改动。");
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.FULL_CELLS_BUILDS, 1);

            if (s.job == 1) HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.RUNNING_JOB_FULL_BUILDS, 1);
            encoding = true;
            HologramRuntimeMetrics
                .loadStage(HologramRuntimeMetrics.LoadStage.SERVER_ROWS_END, s, ticket.sequence(), cells.tagCount());
            progress("snapshot.prepare", "cells", cells.tagCount(), size, true);
            progress("snapshot.encode", "gzip", 0, 0, true);
            HologramNetwork.completeOwnedState(ticket, state, this::valid, () -> {
                HologramLocalPlanRefresh.snapshotComplete(s, state);
                s.jobPublication.published(s.player.worldObj.getTotalWorldTime(), s.planRevision, s.uiSequence);
                progress("snapshot.encode", "gzip", ticket.encodingBytes(), ticket.encodingBytes(), true);
                TASKS.remove(s);
            });
        }

        void progress(String phase, String detail, long done, long total, boolean force) {
            long tick = s.player.worldObj.getTotalWorldTime();
            if (!force && detail.equals(lastDetail)
                && lastTick != Long.MIN_VALUE
                && tick >= lastTick
                && tick - lastTick < (done == lastDone && total == lastTotal ? 20 : 5)) return;
            lastTick = tick;
            lastDetail = detail;
            lastDone = done;
            lastTotal = total;
            NBTTagCompound progress = new NBTTagCompound();
            progress.setString("scope", "server");
            progress.setString("session", session);
            progress.setLong("planRevision", revision);
            progress.setLong("uiSequence", ui);
            progress.setLong("clientTaskToken", clientTask);
            progress.setString("phase", phase);
            progress.setString("detail", detail);
            progress.setLong("done", done);
            progress.setLong("total", total);
            progress.setString("unit", phase.equals("snapshot.encode") ? "bytes" : "rows");
            progress.setString("outcome", "running");
            progress.setBoolean("complete", total > 0 && done >= total);
            NBTTagCompound event = new NBTTagCompound();
            event.setBoolean("progressOnly", true);
            event.setTag("progress", progress);
            HologramWorkspace.snapshotSink()
                .send(s.player, event);
        }
    }
}
