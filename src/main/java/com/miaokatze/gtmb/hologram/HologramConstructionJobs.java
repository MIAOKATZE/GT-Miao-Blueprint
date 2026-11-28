// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;

import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation;

/** GTNL-style preparation, site work, one support retry and controller-last execution for every source. */
final class HologramConstructionJobs {

    interface Source {

        HologramSession session();

        Operation operation();

        boolean current();

        boolean prepare(long deadline) throws IOException;

        Target next(long deadline) throws IOException;

        boolean exhausted();

        long total();

        default long alreadySatisfied() {
            return 0;
        }

        default long startedAt() {
            return System.nanoTime();
        }

        default int destination() {
            return -1;
        }

        default HologramMaterials.Context recoveryContext() {
            return null;
        }

        default boolean canReplace() {
            return operation() == Operation.REPAIR || operation() == Operation.REPLACE;
        }

        default boolean removalCurrent(Target cell) {
            return current();
        }

        default void before(Target cell) throws IOException {}

        default void committed(Target cell, boolean changed) throws IOException {}

        default void progress(Job job) {}

        void finished(Job job);
    }

    static final class Target {

        final int x, y, z;
        final HologramNativeConstruction.Prepared prepared;
        final boolean air, controller;

        Target(int x, int y, int z, HologramNativeConstruction.Prepared prepared, boolean air, boolean controller) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.prepared = prepared;
            this.air = air;
            this.controller = controller;
        }
    }

    enum End {
        COMPLETE,
        PARTIAL,
        CANCELLED,
        FAILED,
        RECOVERY_PENDING
    }

    private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();
    private static final Map<Object, Long> SEQUENCES = new WeakHashMap<>();
    private static int round;
    private static boolean ticking;

    private HologramConstructionJobs() {}

    static Job start(UUID request, long sequence, Source source) throws IOException {
        HologramSession seat = source.session();
        EntityPlayerMP player = seat.player;
        Object connection = player.playerNetServerHandler;
        if (sequence > 0 && !retire(connection, sequence)) throw new IOException("施工请求已处理，未重复执行。");
        if (JOBS.size() >= 64) throw new IOException("施工任务已满。");
        if (JOBS.containsKey(player.getUniqueID())) throw new IOException("已有施工任务，请先取消或等待结束。");
        if (HologramConstructionAuthority.hasKnownRecovery(player)) throw new IOException("存在待核定或待交付施工记录。");
        if (!source.current()) throw new IOException("施工上下文已变化。");
        Job job = new Job(request, source);
        if (JOBS.putIfAbsent(player.getUniqueID(), job) != null) throw new IOException("已有施工任务，请先取消或等待结束。");
        // Register before the native close callback, but do not let a reentrant tick run world work yet.
        job.executing = true;
        End stopped = null;
        String detail = "";
        boolean closing = false;
        try {
            if (player.playerNetServerHandler != null && player.openContainer != null
                && player.inventoryContainer != null) {
                closing = true;
                player.closeScreen();
                closing = false;
            }
            if (!job.current()) {
                stopped = End.CANCELLED;
                detail = job.cancelled ? "施工已取消，已提交结果保留。" : "施工上下文已变化，未执行部分已停止。";
            }
        } catch (RuntimeException | LinkageError failure) {
            stopped = job.cancelled ? End.CANCELLED : End.FAILED;
            detail = job.cancelled ? "施工已取消，已提交结果保留。" : closing ? "关闭施工界面失败，未开始施工。" : "施工异常，已提交结果保留。";
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .debug("Construction GUI close failed before world work", failure);
        } finally {
            job.executing = false;
        }
        // This request was accepted into the table. Preserve its one terminal result for its existing caller.
        if (stopped != null) job.finish(stopped, detail);
        return job;
    }

    static boolean retire(Object connection, long sequence) {
        long seen = SEQUENCES.getOrDefault(connection, 0L);
        if (sequence <= seen) return false;
        SEQUENCES.put(connection, sequence);
        return true;
    }

    static int size() {
        return JOBS.size();
    }

    static boolean active(EntityPlayerMP player) {
        return player != null && JOBS.containsKey(player.getUniqueID());
    }

    static Job find(EntityPlayerMP player) {
        return player == null ? null : JOBS.get(player.getUniqueID());
    }

    static boolean cancel(EntityPlayerMP player, UUID request) {
        Job job = find(player);
        if (job == null || job.player != player || !job.request.equals(request)) return false;
        job.cancelled = true;
        if (!job.executing) job.finish(End.CANCELLED, "施工已取消，已提交结果保留。");
        return true;
    }

    static void tick(long deadline) {
        if (ticking || JOBS.isEmpty()) return;
        ticking = true;
        try {
            List<Job> jobs = new ArrayList<>(JOBS.values());
            int start = Math.floorMod(round, jobs.size());
            for (int visited = 0; visited < jobs.size() && (visited == 0 || System.nanoTime() < deadline); visited++) {
                Job job = jobs.get((start + visited) % jobs.size());
                if (JOBS.get(job.player.getUniqueID()) != job) continue;
                long now = System.nanoTime();
                job.step(now + Math.max(1L, (deadline - now) / Math.max(1, jobs.size() - visited)));
            }
            round = (start + 1) % jobs.size();
        } finally {
            ticking = false;
        }
    }

    static void stopAll(String reason) {
        for (Job job : new ArrayList<>(JOBS.values())) {
            job.cancelled = true;
            if (!job.executing) job.finish(End.CANCELLED, reason);
        }
    }

    static final class Job {

        final UUID request;
        final Source source;
        final HologramSession session;
        final EntityPlayerMP player;
        final HologramMachineUpdateBatch.Updates updates = new HologramMachineUpdateBatch.Updates();
        final HologramOperationResult result;
        final List<Target> controllers = new ArrayList<>(), retry = new ArrayList<>();
        long completed, satisfied, blocked;
        int controllerCursor, retryCursor;
        boolean ready, cancelled, executing, terminal;
        HologramMaterials.Context recovery;
        End end;
        String reason = "";

        Job(UUID request, Source source) {
            this.request = request;
            this.source = source;
            session = source.session();
            player = session.player;
            result = HologramOperationResult.start(
                source.operation()
                    .name()
                    .toLowerCase(java.util.Locale.ROOT),
                request,
                source.startedAt());
        }

        boolean current() {
            return !cancelled && !terminal && JOBS.get(player.getUniqueID()) == this && source.current()
            // Source.current may call native permission/transport code that reenters cancellation.
                && !cancelled
                && !terminal
                && JOBS.get(player.getUniqueID()) == this;
        }

        void step(long deadline) {
            if (terminal || executing) return;
            executing = true;
            try {
                if (!current()) {
                    finish(End.CANCELLED, "施工上下文已变化，未执行部分已停止。");
                    return;
                }
                if (!ready) {
                    if (!source.prepare(deadline)) return;
                    ready = true;
                }
                try (var scope = updates.open()) {
                    while (System.nanoTime() < deadline) {
                        if (!current()) {
                            finish(End.CANCELLED, "施工已取消，已提交结果保留。");
                            return;
                        }
                        Target cell;
                        boolean retrying = false;
                        if (!source.exhausted()) {
                            cell = source.next(deadline);
                            if (cell == null) {
                                if (!source.exhausted()) return;
                                continue;
                            }
                            if (cell.controller) {
                                controllers.add(cell);
                                continue;
                            }
                        } else if (retryCursor < retry.size()) {
                            cell = retry.get(retryCursor++);
                            retrying = true;
                        } else if (controllerCursor < controllers.size()) cell = controllers.get(controllerCursor++);
                        else {
                            finish(blocked == 0 ? End.COMPLETE : End.PARTIAL, blocked == 0 ? "施工完成。" : "施工受阻，已提交结果保留。");
                            return;
                        }
                        var outcome = operate(cell);
                        if (outcome == HologramNativeConstruction.Outcome.OBSTRUCTED && !retrying && !cell.controller) {
                            retry.add(cell);
                            continue;
                        }
                        if (outcome == HologramNativeConstruction.Outcome.SATISFIED) satisfied++;
                        else if (outcome == HologramNativeConstruction.Outcome.PLACED) completed++;
                        else {
                            blocked++;
                            if (HologramConstructionJournal.pending(player)) {
                                finish(End.RECOVERY_PENDING, session.status);
                                return;
                            }
                            if (!current()) {
                                finish(End.CANCELLED, "施工上下文已变化。");
                                return;
                            }
                            continue;
                        }
                        source.committed(cell, outcome == HologramNativeConstruction.Outcome.PLACED);
                    }
                }
            } catch (IOException | RuntimeException | LinkageError failure) {
                finish(
                    HologramConstructionJournal.pending(player) ? End.RECOVERY_PENDING : End.FAILED,
                    failure.getMessage() == null ? "施工异常，已提交结果保留。" : failure.getMessage());
            } finally {
                executing = false;
                if (terminal) publishTerminal();
                else source.progress(this);
            }
        }

        private HologramNativeConstruction.Outcome operate(Target cell) throws IOException {
            var world = player.worldObj;
            if (!world.blockExists(cell.x, cell.y, cell.z)) throw new IOException("施工区块已卸载。");
            source.before(cell);
            boolean empty = world.getBlock(cell.x, cell.y, cell.z) == Blocks.air
                && world.getTileEntity(cell.x, cell.y, cell.z) == null;
            if (cell.air || source.operation() == Operation.DEMOLISH) {
                if (empty) return HologramNativeConstruction.Outcome.SATISFIED;
                return remove(cell) ? HologramNativeConstruction.Outcome.PLACED
                    : HologramNativeConstruction.Outcome.FAILED;
            }
            if (HologramNativeConstruction.matches(session, cell.prepared, cell.x, cell.y, cell.z))
                return HologramNativeConstruction.Outcome.SATISFIED;
            if (!empty && HologramNativeConstruction.synchronizable(session, cell.prepared, cell.x, cell.y, cell.z)) {
                boolean changed = HologramNativeConstruction
                    .synchronize(session, cell.prepared, cell.x, cell.y, cell.z, this::current);
                if (changed) updates.changed(world, cell.x, cell.y, cell.z);
                return changed ? HologramNativeConstruction.Outcome.PLACED : HologramNativeConstruction.Outcome.FAILED;
            }
            if (!empty) {
                if (!source.canReplace()) {
                    session.status = "目标被占用，未替换原方块。";
                    return HologramNativeConstruction.Outcome.FAILED;
                }
                if (!remove(cell)) return HologramNativeConstruction.Outcome.FAILED;
            }
            var outcome = HologramNativeConstruction
                .place(session, cell.prepared, cell.x, cell.y, cell.z, this::current);
            if (outcome == HologramNativeConstruction.Outcome.PLACED) updates.changed(world, cell.x, cell.y, cell.z);
            return outcome;
        }

        private boolean remove(Target cell) throws IOException {
            var world = player.worldObj;
            boolean wasMachine = HologramMachineUpdateBatch.machineBlock(world, cell.x, cell.y, cell.z);
            if (recovery == null) recovery = source.recoveryContext();
            if (recovery == null)
                recovery = HologramDemolition.recoveryContext(player, session.slot, source.destination());
            if (!player.capabilities.isCreativeMode) recovery.requireDemolitionDestination(source.destination());
            if (recovery.recoveryBacklogged()) throw new IOException("回收队列尚未交付。");
            HologramDemolition.Removal removal;
            try (var selected = new BoundTool(player, session.slot)) {
                removal = HologramDemolition.removeBlock(
                    player,
                    world,
                    cell.x,
                    cell.y,
                    cell.z,
                    this::current,
                    () -> !cancelled && !terminal && source.removalCurrent(cell));
            }
            if (removal.removed) updates.removed(world, cell.x, cell.y, cell.z, wasMachine);
            recovery.recoverWorldAtPlayer(removal.drops, source.destination());
            recovery.flushRecovery();
            if (recovery.pendingWorldRecovery()) {
                HologramDemolition.resumeRecovery(player);
                throw new IOException("回收队列尚未交付。");
            }
            if (removal.failure != null) throw new IOException("原生拆除回调失败，实际掉落已保留。", removal.failure);
            return removal.removed;
        }

        void finish(End outcome, String detail) {
            if (terminal) return;
            terminal = true;
            end = outcome;
            reason = detail == null ? "" : detail;
            if (!executing) publishTerminal();
        }

        private void publishTerminal() {
            if (!JOBS.remove(player.getUniqueID(), this)) return;
            try {
                updates.finish();
            } finally {
                if (recovery != null && recovery.pendingWorldRecovery()) HologramDemolition.resumeRecovery(player);
                try {
                    source.finished(this);
                } finally {
                    result.finish(
                        player,
                        end == End.COMPLETE ? "success"
                            : end == End.PARTIAL ? "partial" : end == End.CANCELLED ? "cancelled" : "failed",
                        completed,
                        satisfied + source.alreadySatisfied(),
                        blocked,
                        source.total(),
                        end == End.COMPLETE && "施工完成。".equals(reason) ? "" : reason);
                }
            }
        }
    }

    /** Native harvest APIs inspect the player's selected stack; retain the admitted tool without moving it. */
    static final class BoundTool implements AutoCloseable {

        private final EntityPlayerMP player;
        private final int previous, selected;

        BoundTool(EntityPlayerMP player, int selected) {
            this.player = player;
            this.previous = player.inventory.currentItem;
            this.selected = selected;
            player.inventory.currentItem = selected;
        }

        public void close() {
            if (player.inventory.currentItem == selected) player.inventory.currentItem = previous;
        }
    }
}
