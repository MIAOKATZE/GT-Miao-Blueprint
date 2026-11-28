// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

/** Starts reviewed bounded jobs; transactions remain the sole per-cell world operation boundary. */
final class HologramPlacementJobs {

    private HologramPlacementJobs() {}

    static void updateJobCounts(HologramSession s) {
        s.jobMissingCells = s.jobProtectedCells = s.jobUnsupportedCells = 0;
        for (int i = 0; i < s.cursor && i < s.work.size(); i++) {
            String status = s.capture.cells.get(s.work.get(i)).status;
            if ("missing".equals(status)) s.jobMissingCells++;
            else if ("protected".equals(status) || "blocked".equals(status)) s.jobProtectedCells++;
            else if ("unsupported".equals(status)) s.jobUnsupportedCells++;
        }
        s.jobSkippedCells = Math.max(0, s.cursor - s.completed);
    }

    /** A reviewed request must keep the same world differential; resource availability is checked again. */

    static long spatialWorkOrder(HologramSession s) {
        if (!s.virtual || s.work.size() < 2) return 0;
        // Old saved blueprints also inherited Population's controller-last editor order. Order physical
        // work by integer coordinates, treating controllers exactly like their surrounding blocks.
        long started = System.nanoTime();
        s.work.sort((left, right) -> {
            HologramCapture.Cell a = s.capture.cells.get(left), b = s.capture.cells.get(right);
            int order = Integer.compare(a.y, b.y);
            if (order == 0) order = Integer.compare(a.z, b.z);
            return order == 0 ? Integer.compare(a.x, b.x) : order;
        });
        return System.nanoTime() - started;
    }

    /** Editor/review sources use the same scheduler and native writer as stock and saved projection sources. */
    static void start(HologramSession s) {
        start(s, null);
    }

    static void start(HologramSession s, net.minecraft.nbt.NBTTagCompound action) {
        if (s.capture == null || s.capture.incomplete || s.capture.pending || s.capture.projectionOnly) {
            s.status = "请等待完整结构采集，再核对材料确认施工";
            return;
        }
        if (!HologramPlanPolicy.constructionAllowed(s) || !approvedMaterials(s, action)) return;
        s.work.clear();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            var cell = s.capture.cells.get(i);
            if (!HologramSessionPolicy.ignoredPlacementAir(s, cell, i) && HologramSessionPolicy.inScope(s, cell, i)
                && (s.mode == 2 || "pending".equals(s.planStatus.get(i)) || "satisfied".equals(s.planStatus.get(i))))
                s.work.add(i);
        }
        spatialWorkOrder(s);
        begin(s, action);
    }

    static boolean approvedMaterials(HologramSession s, net.minecraft.nbt.NBTTagCompound action) {
        if (!HologramMaterialWorkflow.recheckBuild(s, action)) return false;
        if (s.planMissing > 0 && (!s.virtual || !s.forcePlacementReady)) {
            s.forcePlacementReady = false;
            s.status = s.virtual
                ? (s.blueprintResourceMissing.isEmpty() ? "蓝图材料不足" : s.blueprintResourceMissing)
                    + "；请核对仅放已有材料的方案后明确确认，权限与占位保护仍有效"
                : "施工材料不足，请补齐后重试";
            return false;
        }
        if (!s.planRecoveryFits && !s.blueprintResourceMissing.isEmpty()) {
            s.status = "蓝图资源校验失败，未施工：" + s.blueprintResourceMissing;
            return false;
        }
        return true;
    }

    static void begin(HologramSession s, net.minecraft.nbt.NBTTagCompound action) {
        String operationRefusal = HologramPlanPolicy.legacyOperationRefusal(s);
        if (!operationRefusal.isEmpty()) {
            s.forcePlacementReady = false;
            s.status = operationRefusal;
            return;
        }
        try {
            if (!HologramSessionPolicy.valid(s)) throw new java.io.IOException("施工上下文已变化。");
            boolean voucher = !s.virtual && action != null
                && action.getBoolean("useDecorationVoucher")
                && HologramMaterialAccess.voucherSelected(s);
            var source = new CapturedSource(s);
            var job = HologramConstructionJobs.start(java.util.UUID.randomUUID(), 0, source);
            if (job.terminal) return;
            s.forcePlacementReady = false;
            s.replacementAuthorized = s.mode == 1;
            s.cursor = s.completed = 0;
            s.job = 1;
            s.phase = "RUNNING";
            HologramSession.resetJobCounts(s, s.virtual && s.planMissing > 0);
            s.controllerVoucherApproved = voucher;
            if (s.virtual) s.placementReviewNonce++;
            s.status = "正在原生施工";
        } catch (java.io.IOException failure) {
            s.status = failure.getMessage();
        }
    }

    /** Finite target adapter only: no independent placement, payment, retry, cancellation or finish loop. */
    private static final class CapturedSource implements HologramConstructionJobs.Source {

        @com.github.bsideup.jabel.Desugar
        private record Variant(net.minecraft.block.Block block, int metadata,
            HologramBlueprintResources.ExactKey sample, int facing, String tile, String item) {}

        final HologramSession s;
        final HologramCapture capture;
        final java.util.List<Integer> indices;
        final java.util.Map<Variant, HologramNativeConstruction.Prepared> variants = new java.util.HashMap<>();
        final java.util.Map<Integer, HologramNativeConstruction.Prepared> targets = new java.util.HashMap<>();
        final net.minecraft.world.World world;
        final net.minecraft.entity.player.InventoryPlayer inventory;
        final net.minecraft.item.ItemStack[] slots;
        final HologramWorkspace.ActionEndpoint endpoint;
        final net.minecraft.tileentity.TileEntity controller;
        final gregtech.api.interfaces.metatileentity.IMetaTileEntity controllerMeta;
        final gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?> sessionMachine;
        final com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing controllerFacing;
        final net.minecraft.block.Block controllerBlock;
        final int controllerX, controllerY, controllerZ, controllerId, controllerFront, controllerMetadata;
        HologramConstructionJobs.Target finalAnchor;
        boolean anchorOffered, anchorRemoved, startedWriting;
        final boolean creative;
        final int mode;
        final long revision, started = System.nanoTime();
        int prepared, cursor;
        long skipped;
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet budgeted = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        CapturedSource(HologramSession s) {
            this.s = s;
            capture = s.capture;
            indices = new java.util.ArrayList<>(s.work);
            mode = s.mode;
            revision = s.planRevision;
            world = s.player.worldObj;
            inventory = s.player.inventory;
            slots = inventory.mainInventory;
            creative = s.player.capabilities.isCreativeMode;
            endpoint = HologramWorkspace.captureActionEndpoint(s.player.playerNetServerHandler);
            controller = !s.virtual ? s.target : null;
            sessionMachine = controller == null ? null : s.machine;
            var gt = controller instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity nativeTile
                ? nativeTile
                : null;
            controllerMeta = gt == null ? null : gt.getMetaTileEntity();
            controllerFacing = controllerMeta instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>aligned
                ? aligned.getExtendedFacing()
                : null;
            controllerX = controller == null ? 0 : controller.xCoord;
            controllerY = controller == null ? 0 : controller.yCoord;
            controllerZ = controller == null ? 0 : controller.zCoord;
            controllerId = gt == null ? 0 : gt.getMetaTileID();
            controllerFront = gt == null ? -1
                : gt.getFrontFacing()
                    .ordinal();
            controllerBlock = controller == null ? null : world.getBlock(controllerX, controllerY, controllerZ);
            controllerMetadata = controller == null ? 0 : world.getBlockMetadata(controllerX, controllerY, controllerZ);
            s.pendingAssetBudget.clear();
            if (s.materialContext == null)
                s.materialContext = new HologramMaterials.Context(s.player, s.materialSettings, s.slot);
        }

        public HologramSession session() {
            return s;
        }

        public HologramStockNativeWire.Operation operation() {
            if (s.virtual) return HologramStockNativeWire.Operation.BUILD;
            return mode == 2 ? HologramStockNativeWire.Operation.DEMOLISH
                : mode == 1 ? HologramStockNativeWire.Operation.REPLACE : HologramStockNativeWire.Operation.BUILD;
        }

        public long startedAt() {
            return started;
        }

        public long total() {
            return indices.size();
        }

        public long alreadySatisfied() {
            return skipped;
        }

        public int destination() {
            return s.materialSettings.recoveryDestination;
        }

        public boolean canReplace() {
            return !s.virtual && mode == 1;
        }

        public boolean current() {
            if (!contextCurrent()) return false;
            if (controller == null) return true;
            if (anchorRemoved) return operation() == HologramStockNativeWire.Operation.DEMOLISH && anchorAir();
            if (!controllerCurrent()) return false;
            boolean allowed = controller instanceof gregtech.api.metatileentity.BaseMetaTileEntity base
                && controllerMeta instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>machine
                    ? HologramNativeProjectionJob.controllerAccessAllowed(s.player, base, machine)
                    : controllerMeta == null || controllerMeta.isAccessAllowed(s.player);
            // Native access is an external callback; it cannot replace/move/turn the bound controller unnoticed.
            return allowed && contextCurrent() && controllerCurrent();
        }

        private boolean contextCurrent() {
            return contextStillBound()
                && (endpoint == null || HologramWorkspace.currentActionEndpoint(endpoint) && contextStillBound());
        }

        private boolean contextStillBound() {
            return !s.cancelled && s.capture == capture
                && s.mode == mode
                && s.planRevision == revision
                && s.player.worldObj == world
                && s.player.dimension == s.dimension
                && s.player.inventory == inventory
                && inventory.mainInventory == slots
                && slots[s.slot] == s.boundTool
                && HologramSessionPolicy.jobTool(s) != null
                && !s.player.isDead
                && s.player.capabilities.allowEdit
                && s.player.capabilities.isCreativeMode == creative;
        }

        private boolean controllerCurrent() {
            if (s.virtual || s.target != controller
                || s.machine != sessionMachine
                || controller.isInvalid()
                || controller.getWorldObj() != world
                || controller.xCoord != controllerX
                || controller.yCoord != controllerY
                || controller.zCoord != controllerZ
                || !world.blockExists(controllerX, controllerY, controllerZ)
                || world.getTileEntity(controllerX, controllerY, controllerZ) != controller
                || world.getBlock(controllerX, controllerY, controllerZ) != controllerBlock
                || world.getBlockMetadata(controllerX, controllerY, controllerZ) != controllerMetadata) return false;
            if (controller instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity gt
                && (gt.getMetaTileEntity() != controllerMeta || gt.getMetaTileID() != controllerId
                    || gt.getFrontFacing()
                        .ordinal() != controllerFront))
                return false;
            return controllerMeta == null || controllerMeta.getBaseMetaTileEntity() == controller
                && (!(controllerMeta instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>aligned)
                    || aligned.getExtendedFacing() == controllerFacing);
        }

        private boolean anchorAir() {
            return world.blockExists(controllerX, controllerY, controllerZ)
                && world.getBlock(controllerX, controllerY, controllerZ) == net.minecraft.init.Blocks.air
                && world.getTileEntity(controllerX, controllerY, controllerZ) == null;
        }

        public boolean removalCurrent(HologramConstructionJobs.Target cell) {
            return current() || controller != null && operation() == HologramStockNativeWire.Operation.DEMOLISH
                && anchorOffered
                && cursor == indices.size()
                && cell.x == controllerX
                && cell.y == controllerY
                && cell.z == controllerZ
                && contextCurrent()
                && anchorAir();
        }

        public boolean prepare(long deadline) throws java.io.IOException {
            while (prepared < indices.size() && System.nanoTime() < deadline) {
                if (!current()) throw new java.io.IOException("施工上下文已变化。");
                int index = indices.get(prepared++);
                var cell = capture.cells.get(index);
                capture.positions.putIfAbsent(HologramValues.position(cell), cell);
                if (mode == 2 || cell.anchor && !s.virtual) continue;
                var sample = HologramCellPolicy.targetFor(s, cell, index);
                if (sample == null) continue;
                var block = HologramCellPolicy.targetPreviewBlock(s, cell, sample);
                int metadata = HologramCellPolicy.targetPreviewMeta(s, cell, sample);
                int facing = cell.savedPlacementTarget != null ? cell.savedPlacementFacing
                    : s.facingPins.getOrDefault(
                        HologramValues.position(cell),
                        index < s.planFacings.size() && s.planFacings.get(index) != null ? s.planFacings.get(index)
                            : -1);
                var key = new Variant(
                    block,
                    metadata,
                    new HologramBlueprintResources.ExactKey(sample, null),
                    facing,
                    cell.tileNbt,
                    cell.itemNbt);
                var target = variants.get(key);
                if (target == null) {
                    target = s.virtual
                        ? HologramNativeConstruction
                            .saved(s, block, metadata, sample, facing, cell.tileNbt, cell.itemNbt)
                        : HologramNativeConstruction.projection(
                            new HologramNativeTargetPlan.Cell(
                                0,
                                0,
                                0,
                                block,
                                metadata,
                                sample,
                                facing,
                                null,
                                null,
                                cell.element,
                                null,
                                false));
                    variants.put(key, target);
                }
                targets.put(index, target);
                if (!creative && !target.assets.isEmpty()
                    && !HologramNativeConstruction.matches(s, target, cell.x, cell.y, cell.z))
                    addBudget(cell.x, cell.y, cell.z, target);
            }
            return prepared == indices.size();
        }

        public boolean exhausted() {
            return cursor == indices.size() && (finalAnchor == null || anchorOffered);
        }

        public HologramConstructionJobs.Target next(long deadline) throws java.io.IOException {
            while (cursor < indices.size() && System.nanoTime() < deadline) {
                int index = indices.get(cursor++);
                var cell = capture.cells.get(index);
                if (cell.anchor && !s.virtual && mode != 2
                    || HologramSessionPolicy.ignoredPlacementAir(s, cell, index)) {
                    skipped++;
                    continue;
                }
                var target = targets.get(index);
                boolean requiredAir = !cell.unknown && cell.block == net.minecraft.init.Blocks.air
                    && HologramElementCatalog.nativeAir(cell.element);
                if (mode != 2 && !requiredAir && target == null) {
                    skipped++;
                    continue;
                }
                boolean controller = cell.anchor || "templateController".equals(cell.role)
                    || this.controller != null && cell.x == controllerX
                        && cell.y == controllerY
                        && cell.z == controllerZ;
                var next = new HologramConstructionJobs.Target(
                    cell.x,
                    cell.y,
                    cell.z,
                    target,
                    mode == 2 || requiredAir,
                    controller);
                if (this.controller != null && mode == 2
                    && cell.x == controllerX
                    && cell.y == controllerY
                    && cell.z == controllerZ) {
                    finalAnchor = next;
                    continue;
                }
                return next;
            }
            if (cursor == indices.size() && finalAnchor != null && !anchorOffered) {
                anchorOffered = true;
                return finalAnchor;
            }
            return null;
        }

        private void addBudget(int x, int y, int z, HologramNativeConstruction.Prepared target) {
            if (!budgeted.add(com.gtnewhorizon.gtnhlib.util.CoordinatePacker.pack(x, y, z))) return;
            for (var cost : target.assets.entrySet())
                s.pendingAssetBudget.merge(cost.getKey(), cost.getValue(), Math::addExact);
        }

        public void before(HologramConstructionJobs.Target target) throws java.io.IOException {
            if (!startedWriting) {
                String refusal = HologramPlanPolicy.legacyOperationRefusal(s);
                if (!refusal.isEmpty()) throw new java.io.IOException(refusal);
            }
            if (creative || target.prepared == null || target.prepared.assets.isEmpty()) return;
            if (!budgeted.contains(com.gtnewhorizon.gtnhlib.util.CoordinatePacker.pack(target.x, target.y, target.z))
                && !HologramNativeConstruction.matches(s, target.prepared, target.x, target.y, target.z)) {
                addBudget(target.x, target.y, target.z, target.prepared);
                s.materialContext.resetAssetReservations();
            }
        }

        public void committed(HologramConstructionJobs.Target target, boolean changed) {
            // Matched rows do not authorize later writes; the first committed change freezes formation admission.
            if (changed) startedWriting = true;
            if (changed && target == finalAnchor) anchorRemoved = true;
            var cell = capture.positions.get(target.x + "," + target.y + "," + target.z);
            if (cell != null) {
                cell.status = changed ? operation() == HologramStockNativeWire.Operation.DEMOLISH ? "removed"
                    : mode == 1 ? "replaced" : "placed" : "satisfied";
                int index = HologramSessionPolicy.cellIndex(s, cell);
                s.ackIndices.add(index);
                s.ackSuccesses.add(changed ? 1 : 0);
                s.jobPublication.indices.add(index);
                s.jobPublication.successes.add(changed ? 1 : 0);
                s.jobPublication.statuses.add(cell.status);
            }
            if (target.prepared != null && !creative
                && !target.prepared.assets.isEmpty()
                && budgeted.remove(com.gtnewhorizon.gtnhlib.util.CoordinatePacker.pack(target.x, target.y, target.z))) {
                for (var cost : target.prepared.assets.entrySet()) {
                    long remaining = s.pendingAssetBudget.getOrDefault(cost.getKey(), 0L) - cost.getValue();
                    if (remaining <= 0) s.pendingAssetBudget.remove(cost.getKey());
                    else s.pendingAssetBudget.put(cost.getKey(), remaining);
                }
                s.materialContext.assetsCommitted(target.prepared.assets);
            }
        }

        public void progress(HologramConstructionJobs.Job job) {
            s.cursor = cursor;
            s.completed = (int) job.completed;
            s.batchSeq++;
            if (!job.terminal) HologramSnapshots.sendJob(s);
        }

        public void finished(HologramConstructionJobs.Job job) {
            progress(job);
            s.job = job.end == HologramConstructionJobs.End.COMPLETE ? 3
                : job.end == HologramConstructionJobs.End.PARTIAL ? 5 : 4;
            s.phase = s.job == 3 ? "COMPLETE" : "STOPPED";
            s.status = job.reason;
            s.blueprintAwaitingConfirmation = false;
            HologramSnapshots.sendStoppedJob(s);
        }
    }
}
