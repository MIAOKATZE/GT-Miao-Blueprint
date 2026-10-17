package com.miaokatze.gtmb.hologram;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
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

    static void cancel(HologramSession s) {
        Task task = TASKS.get(s);
        if (task != null) cancel(task);
    }

    static void start(HologramSession s) {
        Task previous = TASKS.get(s);
        if (previous != null && previous.valid()) return;
        if (previous != null) cancel(previous);
        if (TASKS.size() >= MAX_TASKS) return;
        HologramNetwork.SnapshotTicket ticket = HologramNetwork.reserveState(s.player, s.id, s.cancelled);
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
                && HologramSessionPolicy.valid(s)
                && !s.cancelled) HologramSnapshots.send(s);
            return true;
        }
        if (task.encoding) {
            task.progress("snapshot.encode", "gzip", 0, 1, false);
            return true;
        }
        try {
            task.step(256, HologramWorkBudget.deadline(HologramWorkBudget.SLICE_NANOS), System::nanoTime);
        } catch (RuntimeException | LinkageError failure) {
            cancel(task);
            HologramNetwork.transportFailure(
                s.player,
                s.id,
                new IllegalStateException("Hologram snapshot preparation failed", failure));
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
        }
        task.state = null;
        task.metadata = null;
    }

    /** Production checkpoint loop also used by fake-clock regressions. One native callback is atomic. */
    static int runSlice(Runnable operation, java.util.function.BooleanSupplier complete, int maxWork, long deadline,
        LongSupplier clock) {
        int work = 0;
        while (!complete.getAsBoolean() && work < maxWork && (work == 0 || clock.getAsLong() < deadline)) {
            operation.run();
            work++;
        }
        return work;
    }

    private static final class Task {

        final HologramSession s;
        final HologramNetwork.SnapshotTicket ticket;
        final HologramCapture capture;
        final World world;
        final String session, phase, active;
        final long revision, ui, batch, planGeneration;
        final int x, y, z, size, job, scope, layer, selected;
        final boolean draftOnly;
        final String planError;
        NBTTagCompound state;
        HologramSnapshotMetadataTask metadata;
        final NBTTagList cells = new NBTTagList(), materials = new NBTTagList(), recovery = new NBTTagList();
        final NBTTagList hatchEntries = new NBTTagList();
        boolean structureValid = true, structureFound;
        final Map<String, NBTTagCompound> requirements = new LinkedHashMap<>();
        Iterator<NBTTagCompound> materialIterator;
        int stage, index, protectedCount, missing, total, workTotal, recoveryRows, recoveryItems;
        boolean encoding;
        long lastTick = Long.MIN_VALUE;
        String lastDetail = "";

        Task(HologramSession s, HologramNetwork.SnapshotTicket ticket) {
            this.s = s;
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
            batch = s.batchSeq;
            planGeneration = s.localPlanGeneration;
            draftOnly = s.localPlanPending && !s.localPlanError.isEmpty();
            planError = s.localPlanError;
            x = s.x;
            y = s.y;
            z = s.z;
            size = capture == null ? 0 : capture.cells.size();
            job = s.job;
        }

        boolean valid() {
            return ticket.current() && HologramWorkspace.find(s.player.getUniqueID()) == s
                && session.equals(s.id)
                && s.player.worldObj == world
                && capture == s.capture
                && size == (s.capture == null ? 0 : s.capture.cells.size())
                && revision == s.planRevision
                && ui == s.uiSequence
                && batch == s.batchSeq
                && planGeneration == s.localPlanGeneration
                && draftOnly == (s.localPlanPending && !s.localPlanError.isEmpty())
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
                && HologramSessionPolicy.valid(s);
        }

        void step(int maxWork, long deadline, LongSupplier clock) {
            if (stage == 0) {
                int materialized = 0;
                for (Task queued : TASKS.values()) if (queued.state != null) materialized++;
                if (materialized >= MAX_MATERIALIZED) {
                    progress("snapshot.prepare", "queued", 0, 0, false);
                    return;
                }
                state = HologramSnapshots.basicState(s);
                metadata = new HologramSnapshotMetadataTask(s, state);
                stage = 1;
                return;
            }
            if (stage == 1) {
                if (!metadata.step(maxWork, deadline)) {
                    HologramAreaPreparation.Progress current = metadata.progress();
                    progress("snapshot.prepare", current.phase, current.done, current.total, false);
                    return;
                }
                metadata = null;
                HologramLocalPlanRefresh.snapshotBegin(s);
                stage = 2;
                return;
            }
            runSlice(this::unit, () -> encoding, maxWork, deadline, clock);
            if (!encoding) progress("snapshot.prepare", stageDetail(), index, stageTotal(), false);
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
                    return "cache_capture";
                case 6:
                    return "cells";
                case 7:
                    return "resources";
                case 8:
                    return "materials";
                case 9:
                    return "recovery";
                default:
                    return "metadata";
            }
        }

        int stageTotal() {
            if (stage == 2) return s.virtualPreset == null ? 0 : s.virtualPreset.cells.size();
            if (stage == 3) return s.processingSource == null ? 0 : s.processingSource.cells.size();
            if (stage == 4) return s.processingSource == null ? 0 : s.processingSource.palette.size();
            if (stage == 5 || stage == 6) return size;
            if (draftOnly && stage >= 7) return 0;
            if (stage == 7) return s.blueprintResources == null ? 0 : s.blueprintResources.items.size();
            if (stage == 8) return requirements.size();
            return s.recovered.size();
        }

        void unit() {
            while (stage >= 2 && stage <= 9 && index >= stageTotal()) {
                stage++;
                index = 0;
                if (stage == 8) materialIterator = requirements.values()
                    .iterator();
            }
            if (stage == 2) HologramLocalPlanRefresh.snapshotSeedDefaults(s, index++);
            else if (stage == 3) HologramLocalPlanRefresh.snapshotSeedSource(s, index++);
            else if (stage == 4) HologramLocalPlanRefresh.snapshotSeedPalette(s, index++);
            else if (stage == 5) HologramLocalPlanRefresh.snapshotSeedCapture(s, index++);
            else if (stage == 6) addCell(index++);
            else if (stage == 7) {
                ItemStack content = s.blueprintResources.items.get(index++);
                if (!s.player.capabilities.isCreativeMode) addResource(content);
            } else if (stage == 8) {
                NBTTagCompound req = materialIterator.next();
                materials.appendTag(req);
                missing += Math.max(0, req.getInteger("required") - req.getInteger("available"));
                index++;
            } else if (stage == 9) {
                ItemStack drop = s.recovered.get(index++);
                recoveryRows++;
                recoveryItems += drop.stackSize;
                if (recovery.tagCount() < 96) recovery.appendTag(HologramSnapshots.recoverySummary(drop, false));
            } else if (stage == 10) finish();
        }

        void addCell(int index) {
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.FULL_CELLS_VISITED, 1);
            HologramCapture.Cell c = s.capture.cells.get(index);
            if (!draftOnly) HologramHatchProfiles.captureCell(s, index, hatchEntries);
            if (!draftOnly && !s.virtual && s.machine != null && !s.machine.mMachine && structureValid) {
                int built = HologramPlanPolicy.replacementCellBuilt(s, c);
                if (built < 0) structureValid = false;
                if (built > 0) structureFound = true;
            }
            NBTTagCompound row = HologramSnapshots.cellRow(s, index, draftOnly);
            cells.appendTag(row);
            if (draftOnly) {
                HologramLocalPlanRefresh.snapshotDraftCell(s, index, row);
                if (HologramSessionPolicy.inScope(s, c, index)) total++;
                return;
            }
            HologramLocalPlanRefresh.snapshotCell(s, index, row);
            World world = s.player.worldObj;
            boolean loaded = world.blockExists(c.x, c.y, c.z);
            Block current = loaded ? world.getBlock(c.x, c.y, c.z) : Blocks.air;
            int meta = loaded ? world.getBlockMetadata(c.x, c.y, c.z) : 0;
            ItemStack pinned = HologramCellPolicy.targetFor(s, c, index);
            if (HologramSessionPolicy.inScope(s, c, index)
                && ("protected".equals(c.status) || "unsupported".equals(c.status) || "blocked".equals(c.status)))
                protectedCount++;
            if (HologramSessionPolicy.inScope(s, c, index)) total++;
            if (HologramSessionPolicy.inScope(s, c, index) && index < s.planStatus.size()
                && "pending".equals(s.planStatus.get(index))) workTotal++;

            if (!"satisfied".equals(c.status) && !"protected".equals(c.status)
                && !"unsupported".equals(c.status)
                && !"placed".equals(c.status)
                && !"replaced".equals(c.status)
                && pinned != null
                && !HologramCellPolicy.orientationOnly(s, c, index)
                && HologramSessionPolicy.inScope(s, c, index)
                && s.mode != 2
                && !HologramCellPolicy.decorationTarget(s, c)
                && !HologramMaterialAccess.freeShell(s, c, pinned)
                && (!s.player.capabilities.isCreativeMode || HologramRecovery.hasSeal(pinned))) {
                ItemStack stack = HologramEssentia.shellMaterial(pinned);
                stack.stackSize = 1;
                String key = HologramValues.stackKey(stack);
                NBTTagCompound req = requirements.get(key);
                if (req == null) {
                    req = new NBTTagCompound();
                    stack.writeToNBT(req);
                    req.setInteger(
                        "available",
                        HologramMaterialAccess.shellCount(s, stack, Math.max(4096, s.capture.cells.size())));
                    req.setInteger(
                        "mainAvailable",
                        HologramMaterialAccess.materialSource(s)
                            .mainCount(stack, Math.max(4096, s.capture.cells.size())));
                    req.setInteger(
                        "controllerAvailable",
                        s.target instanceof net.minecraft.inventory.IInventory
                            && ((net.minecraft.inventory.IInventory) s.target).isUseableByPlayer(s.player)
                                ? HologramMaterials.controllerCount(
                                    (net.minecraft.inventory.IInventory) s.target,
                                    stack,
                                    Math.max(4096, s.capture.cells.size()))
                                : -1);
                    requirements.put(key, req);
                }
                req.setInteger("required", req.getInteger("required") + 1);
            }
            if (HologramSessionPolicy.inScope(s, c, index) && "pending".equals(c.status)
                && s.mode != 2
                && !s.player.capabilities.isCreativeMode) for (ItemStack cover : HologramCellPolicy.coverCosts(s, c)) {
                    String key = HologramValues.stackKey(cover);
                    NBTTagCompound requirement = requirements.get(key);
                    if (requirement == null) {
                        requirement = cover.writeToNBT(new NBTTagCompound());
                        requirement.setInteger(
                            "available",
                            HologramMaterialAccess.materialSource(s)
                                .count(cover, Math.max(4096, s.capture.cells.size() * 6)));
                        requirement.setInteger(
                            "mainAvailable",
                            HologramMaterialAccess.materialSource(s)
                                .mainCount(cover, Math.max(4096, s.capture.cells.size() * 6)));
                        requirement.setInteger("controllerAvailable", -1);
                        requirements.put(key, requirement);
                    }
                    requirement.setInteger("required", requirement.getInteger("required") + 1);
                }
            if (HologramSessionPolicy.inScope(s, c, index) && "pending".equals(c.status)
                && !HologramCellPolicy.orientationOnly(s, c, index)
                && loaded
                && !HologramCellPolicy.trivial(world, c.x, c.y, c.z)
                && (!s.player.capabilities.isCreativeMode || world.getTileEntity(c.x, c.y, c.z) != null)) try {
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
            String key = HologramValues.stackKey(content);
            NBTTagCompound requirement = requirements.get(key);
            if (requirement == null) {
                ItemStack identity = content.copy();
                identity.stackSize = 1;
                requirement = identity.writeToNBT(new NBTTagCompound());
                requirement.setInteger(
                    "available",
                    HologramMaterialAccess.materialSource(s)
                        .count(identity, Math.max(4096, content.stackSize)));
                requirement.setInteger(
                    "mainAvailable",
                    HologramMaterialAccess.materialSource(s)
                        .mainCount(identity, Math.max(4096, content.stackSize)));
                requirement.setInteger("controllerAvailable", -1);
                requirements.put(key, requirement);
            }
            requirement.setInteger("required", requirement.getInteger("required") + content.stackSize);
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
            if (!draftOnly) state.setTag("materials", materials);
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
            state.setInteger("missing", draftOnly ? 0 : Math.max(missing, s.planMissing));
            state.setInteger("total", total);
            state.setInteger("workTotal", workTotal);
            state.setString("description", "控制器始终保留；直接施工会重新扫描并逐格重验。手选目标覆盖对应位置，不保证结构成型；差分可供查看材料与改动。");
            HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.FULL_CELLS_BUILDS, 1);

            if (s.job == 1) HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.RUNNING_JOB_FULL_BUILDS, 1);
            encoding = true;
            progress("snapshot.prepare", "cells", cells.tagCount(), size, true);
            progress("snapshot.encode", "gzip", 0, 1, true);
            HologramNetwork.completeOwnedState(ticket, state, this::valid, () -> {
                HologramLocalPlanRefresh.snapshotComplete(s, state);
                s.jobPublication.published(s.player.worldObj.getTotalWorldTime(), s.planRevision, s.uiSequence);
                progress("snapshot.encode", "gzip", 1, 1, true);
                TASKS.remove(s);
            });
        }

        void progress(String phase, String detail, long done, long total, boolean force) {
            long tick = s.player.worldObj.getTotalWorldTime();
            if (!force && detail.equals(lastDetail)
                && lastTick != Long.MIN_VALUE
                && tick >= lastTick
                && tick - lastTick < 5) return;
            lastTick = tick;
            lastDetail = detail;
            NBTTagCompound progress = new NBTTagCompound();
            progress.setString("scope", "server");
            progress.setString("session", session);
            progress.setLong("planRevision", revision);
            progress.setLong("uiSequence", ui);
            progress.setString("phase", phase);
            progress.setString("detail", detail);
            progress.setLong("done", done);
            progress.setLong("total", total);
            progress.setString("unit", phase.equals("snapshot.encode") ? "tasks" : "rows");
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
