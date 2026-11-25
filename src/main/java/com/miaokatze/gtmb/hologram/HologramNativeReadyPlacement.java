// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.RandomAccess;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.api.StructureSources;

/**
 * Explicit native PLACE against an already prepared, owner-bound server capture. No capture or quote is opened here.
 */
final class HologramNativeReadyPlacement {

    static final String FLAG = "nativeReadyPlace";
    private static final Set<String> FIELDS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "action",
                FLAG,
                "placementRequestId",
                "session",
                "x",
                "y",
                "z",
                "side",
                "uiSequence",
                "editorWorkspaceEpoch",
                "planRevision",
                "sourceRevision")));
    private static final long LIFETIME_NANOS = 30_000_000_000L;
    private static final int SLICE_ROWS = 256;
    private static final String RECOVERY_PENDING = "存在待核定施工凭据，未开始放置。";

    private final String id;
    private final HologramSession session;
    private final HologramCapture capture;
    private final HologramNativeItemBill bill;
    private final HologramPlacementAdmissionSweep sweep;
    private final long sourceRevision, started;
    private final boolean ordinary;
    private final int x, y, z, side;
    private final List<Integer> work = new NativeWork();
    private final HologramMaterials.Context inventory;
    private int stockCursor;
    private boolean anchor;

    private HologramNativeReadyPlacement(HologramSession session, String id, long now, boolean ordinary) {
        this.session = session;
        this.id = id;
        this.ordinary = ordinary;
        capture = session.capture;
        bill = session.nativeReadyBill;
        sweep = new HologramPlacementAdmissionSweep(capture.cells.size());
        sourceRevision = StructureSources.revision();
        started = now;
        x = session.x;
        y = session.y;
        z = session.z;
        side = session.side;
        inventory = new HologramMaterials.Context(session.player, session.materialSettings, session.slot);
    }

    static boolean enabled() {
        return Boolean.getBoolean("gtmb.experimental.nativeReadyPlace");
    }

    static boolean admitPrepared(HologramSession s, String id) {
        boolean ordinary = HologramNativeCompactStart.ordinary(s);
        if (s.nativeCompactStart == null || s.nativeReadyPlacement != null || !ready(s, ordinary)) return false;
        s.nativeReadyPlacement = new HologramNativeReadyPlacement(s, id, System.nanoTime(), ordinary);
        return true;
    }

    static void receive(EntityPlayerMP player, NBTTagCompound request) {
        receive(player, request, HologramConstructionReceipts::reply);
    }

    @FunctionalInterface
    interface Receipt {

        void send(EntityPlayerMP player, String id, boolean accepted, String reason);
    }

    static void receive(EntityPlayerMP player, NBTTagCompound request, Receipt receipt) {
        String id = request.getString("placementRequestId");
        if (!canonicalId(id) || HologramConstructionReceipts.consumed(player, id)) return;
        // Retire on first intake, including refusal and disconnect. Replays never produce a second admission.
        HologramConstructionReceipts.retire(player, id);
        String scopedRefusal = HologramWorkspace.legacyConstructionRefusal(player);
        if (scopedRefusal != null) {
            receipt.send(player, id, false, scopedRefusal);
            return;
        }
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        String refusal;
        try {
            if (!enabled() || !matches(s, player, request) || !ready(s, false)) refusal = "当前没有同版本、已准备且受支持的原生放置计划。";
            else if (s.nativeReadyPlacement != null) refusal = "正在核对另一条原生放置请求。";
            else if (HologramConstructionJournal.pending(player)) refusal = RECOVERY_PENDING;
            else {
                s.nativeReadyPlacement = new HologramNativeReadyPlacement(s, id, System.nanoTime(), false);
                return;
            }
        } catch (RuntimeException | LinkageError invalid) {
            refusal = "无法核对当前原生放置计划。";
        }
        receipt.send(player, id, false, refusal);
    }

    private static boolean canonicalId(String id) {
        if (id.length() != 36) return false;
        try {
            return UUID.fromString(id)
                .toString()
                .equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean matches(HologramSession s, EntityPlayerMP player, NBTTagCompound r) {
        if (s == null || s.player != player
            || !HologramCompactNativeRequest.hasFields(r, FIELDS)
            || !r.hasKey(FLAG, 1)
            || !r.getBoolean(FLAG)
            || !"useBlock".equals(r.getString("action"))
            || !s.id.equals(r.getString("session"))) return false;
        for (String key : new String[] { "x", "y", "z", "side" }) if (!r.hasKey(key, 3)) return false;
        for (String key : new String[] { "uiSequence", "editorWorkspaceEpoch", "planRevision", "sourceRevision" })
            if (!r.hasKey(key, 4)) return false;
        return r.getInteger("x") == s.x && r.getInteger("y") == s.y
            && r.getInteger("z") == s.z
            && r.getInteger("side") == s.side
            && s.side >= 0
            && s.side <= 5
            && r.getLong("uiSequence") == s.uiSequence
            && r.getLong("editorWorkspaceEpoch") == s.editorWorkspaceEpoch
            && r.getLong("planRevision") == s.planRevision
            && r.getLong("sourceRevision") == StructureSources.revision();
    }

    private static boolean ready(HologramSession s, boolean ordinary) {
        if (s.nativeReadyBill == null || s.virtual
            || s.mode != 0
            || s.scope != 0
            || s.target == null
            || s.machine == null
            || !s.areaStructureId.isEmpty()
            || !s.manualPositions.isEmpty()
            || s.geometryEdits.validationPending
            || !s.projectionEdits.isEmpty()
            || !s.pins.isEmpty()
            || !s.coordinatePins.isEmpty()
            || !s.facingPins.isEmpty()
            || s.allocationsFrozen
            || !s.suppressedAuto.isEmpty()
            || (!ordinary && (s.materialSettings.containers || s.materialSettings.me || !s.materialSettings.main))
            || HologramWorkspace.find(s.player.getUniqueID()) != s
            || !HologramSessionPolicy.valid(s)
            || s.player.getDistanceSq(s.x + .5, s.y + .5, s.z + .5) > 64
            || s.machine.getExtendedFacing()
                .ordinal() != s.facing
            || s.localPlanPending
            || !s.localPlanError.isEmpty()
            || s.executionStart != null
            || s.currentPlacementTask != null
            || s.voucherCoverageTask != null
            || s.attachmentTask != null
            || s.presentationTransition != null
            || s.structureSavePreparation != null
            || !HologramEditorWorkspaceRegistry.idle(s)
            || !HologramStructureProjection.operationPrepared(s)
            || !s.nativeReadyBill.observedStockSufficient(s)
            || !s.capture.readyPlacementCurrent(s)) return false;
        int size = s.capture.cells.size();
        return s.expectedBlocks.size() == size && s.expectedMeta.size() == size
            && s.expectedTiles.size() == size
            && s.expectedFacings.size() == size
            && s.planStatus.size() == size
            && s.planTargets.size() == size;
    }

    static boolean tick(HologramSession s) {
        return tick(
            s,
            SLICE_ROWS,
            HologramWorkBudget.deadline(HologramWorkBudget.SLICE_NANOS),
            System::nanoTime,
            HologramConstructionReceipts::reply);
    }

    static boolean tick(HologramSession s, int rows, long deadline, LongSupplier clock, Receipt receipt) {
        HologramNativeReadyPlacement task = s.nativeReadyPlacement;
        if (task == null) return false;
        String refusal = null;
        try {
            if (!task.step(rows, deadline, clock)) return true;
        } catch (IOException | RuntimeException | LinkageError invalid) {
            refusal = HologramAePolicy.fallback(invalid.getMessage());
        }
        s.nativeReadyPlacement = null;
        receipt.send(s.player, task.id, refusal == null, refusal);
        return true;
    }

    static void cancel(HologramSession s) {
        HologramNativeReadyPlacement task = s.nativeReadyPlacement;
        if (task == null) return;
        s.nativeReadyPlacement = null;
        HologramConstructionReceipts.reply(s.player, task.id, false, "原生放置请求已取消。");
    }

    static boolean cancel(EntityPlayerMP player, String id) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s == null || s.player != player || s.nativeReadyPlacement == null || !s.nativeReadyPlacement.id.equals(id))
            return false;
        cancel(s);
        return true;
    }

    long completed() {
        return (long) sweep.visited() + stockCursor;
    }

    private boolean step(int rows, long deadline, LongSupplier clock) throws IOException {
        HologramSession s = session;
        String scopedRefusal = HologramWorkspace.legacyConstructionRefusal(s.player);
        if (scopedRefusal != null) throw new IOException(scopedRefusal);
        if (!ordinary && (!enabled() || clock.getAsLong() - started >= LIFETIME_NANOS)
            || s.capture != capture
            || s.nativeReadyBill != bill
            || x != s.x
            || y != s.y
            || z != s.z
            || side != s.side
            || sourceRevision != StructureSources.revision()
            || !ready(s, ordinary)) throw new IOException("原生放置计划或当前目标已变更。");
        var slice = new HologramPlacementAdmissionSweep.Slice(rows, deadline, clock);
        var progress = sweep.inspect(slice, index -> {
            HologramCapture.Cell cell = capture.cells.get(index);
            if (cell.anchor) {
                if (anchor || !HologramExecutionReview.retainedPhysicalAnchor(s, cell, true))
                    throw new IOException("原生控制器已变更。");
                anchor = true;
                return true;
            }
            if (cell.excluded || cell.userPosition
                || !cell.memberships()
                    .isEmpty()
                || !HologramPlanPolicy.expectedCurrent(s, index)) throw new IOException("现场或原生施工范围已变更。");
            String status = HologramPlanPolicy.classify(
                s,
                cell,
                index,
                true,
                s.expectedBlocks.get(index),
                s.expectedMeta.get(index),
                s.expectedTiles.get(index));
            if (!status.equals(s.planStatus.get(index)) || !("pending".equals(status) || "satisfied".equals(status)))
                throw new IOException("原生放置含受保护、未支持或已变更的位置。");
            if ("pending".equals(status)) {
                if (s.expectedBlocks.get(index) != Blocks.air || s.expectedTiles.get(index) != null)
                    throw new IOException("普通放置不能移除原方块。");
                // Preserve native capture order as in HologramPlacementJobs.start, not virtual Y/Z/X ordering.
                work.add(index);
            }
            return true;
        });
        if (progress != HologramPlacementAdmissionSweep.Progress.COMPLETE) return false;
        if (!anchor || work.isEmpty()) throw new IOException("没有可安全放置的原生目标。");
        while (stockCursor < bill.rows()
            .size() && slice.take()) {
            HologramNativeItemBill.Row row = bill.rows()
                .get(stockCursor++);
            var item = row.item();
            if (HologramGtswnPlacementMaterials.supplyKind(item) >= 0
                || inventory.count(item, row.required) < row.required) throw new IOException("原生施工材料已不足。");
        }
        if (stockCursor < bill.rows()
            .size()) return false;
        if (!bill.declarationCurrent() || !capture.readyPlacementCurrent(s)) throw new IOException("原生放置计划或当前目标已变更。");
        if (HologramConstructionJournal.pending(s.player)) throw new IOException(RECOVERY_PENDING);
        s.work = work;
        HologramPlacementJobs.begin(s, null);
        if (s.job != 1) throw new IOException(s.status);
        HologramStructureProjection.dismissOperationReview(s);
        return true;
    }

    /** Bound take/append growth too: no large backing copy between slices or at job handoff. */
    private static final class NativeWork extends AbstractList<Integer> implements RandomAccess {

        private static final int PAGE_ROWS = 1024;
        private final List<int[]> pages = new ArrayList<>();
        private int size;

        @Override
        public int size() {
            return size;
        }

        @Override
        public Integer get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException("index " + index + ", size " + size);
            return pages.get(index / PAGE_ROWS)[index % PAGE_ROWS];
        }

        @Override
        public boolean add(Integer index) {
            if (size % PAGE_ROWS == 0) pages.add(new int[PAGE_ROWS]);
            pages.get(size / PAGE_ROWS)[size % PAGE_ROWS] = index;
            size++;
            modCount++;
            return true;
        }

        @Override
        public Integer set(int index, Integer value) {
            int previous = get(index);
            pages.get(index / PAGE_ROWS)[index % PAGE_ROWS] = value;
            return previous;
        }

        @Override
        public void clear() {
            pages.clear();
            size = 0;
            modCount++;
        }
    }
}
