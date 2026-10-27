package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;

/**
 * Synchronous owner-only redo protocol. The caller supplies an already durable pending journal (newly saved or
 * loaded for recovery). No stage, entry or receipt escapes this call, yields a tick, or runs native APIs on a worker.
 * A verified world receipt exists only after native save, global drain, real readback, comparison and region sync.
 */
final class HologramCutTransaction {

    private HologramCutTransaction() {}

    static void recover(HologramCutStorage storage, NBTTagCompound state, WorldServer world,
        HologramCutDiagnostics trace) throws Exception {
        PendingEdit pending = new PendingEdit(state, world, trace);
        pending.applyAndVerify().commit(storage);
    }

    /** Borrowed state is exclusively owned by this synchronous invocation; never mutate it during native callbacks. */
    private static final class PendingEdit {

        final Thread owner = Thread.currentThread();
        final NBTTagCompound state, pending, entry;
        final WorldServer world;
        final HologramCutDiagnostics trace;
        final int x, y, z;
        final boolean air;

        PendingEdit(NBTTagCompound state, WorldServer world, HologramCutDiagnostics trace) throws IOException {
            if (!state.hasKey("pending", 10)) throw new IOException("剪切恢复记录缺失");
            this.state = state;
            this.world = world;
            this.trace = trace;
            pending = state.getCompoundTag("pending");
            entry = pending.getCompoundTag("entry");
            x = pending.getInteger("x");
            y = pending.getInteger("y");
            z = pending.getInteger("z");
            air = pending.getBoolean("air");
        }

        VerifiedEdit applyAndVerify() throws Exception {
            checkOwner();
            try (HologramCutDiagnostics.Scope ignored = trace.phase(
                HologramCutDiagnostics.Phase.NATIVE_APPLY, entry.getTagList("cells", 10).tagCount())) {
                HologramCutWorld.apply(world, entry, x, y, z, air);
            }
            HologramCutWorld.saveAndVerify(world, entry, x, y, z, air, trace);
            // Construction is deliberately after every existing durability barrier, never just after apply/save.
            return new VerifiedEdit(this);
        }

        void checkOwner() throws IOException {
            if (Thread.currentThread() != owner || state.getTag("pending") != pending
                || pending.getTag("entry") != entry) throw new IOException("剪切事务上下文已改变，恢复记录已保留");
        }
    }

    /** Operation-local world durability receipt. It cannot be constructed by a caller or reused for another edit. */
    private static final class VerifiedEdit {

        private final PendingEdit edit;
        private boolean attempted;

        private VerifiedEdit(PendingEdit edit) {
            this.edit = edit;
        }

        void commit(HologramCutStorage storage) throws IOException {
            edit.checkOwner();
            if (attempted) throw new IOException("剪切事务已尝试提交，请重新读取权威记录");
            attempted = true;
            NBTTagCompound completed = (NBTTagCompound) edit.state.copy();
            String consumeId = edit.pending.getString("consumeId");
            if (!consumeId.isEmpty()) remove(completed, consumeId);
            completed.removeTag("pending");
            HologramCutStorage.PreparedWrite write = storage.prepare(completed, edit.trace);
            HologramCutStorage.WriteReceipt receipt = write.write();
            if (!receipt.confirms(write)) throw new IOException("剪切提交存储回执不匹配");
            // No in-memory consumption or native notification before the completed journal replacement succeeds.
            if (!consumeId.isEmpty()) remove(edit.state, consumeId);
            edit.state.removeTag("pending");
            try (HologramCutDiagnostics.Scope ignored = edit.trace.phase(HologramCutDiagnostics.Phase.COMMIT_NOTIFY, 1)) {
                HologramCutWorld.notifyCommitted(edit.world, edit.entry, edit.x, edit.y, edit.z, edit.air);
            }
        }
    }

    private static void remove(NBTTagCompound state, String id) {
        NBTTagList entries = state.getTagList("entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) if (id.equals(entries.getCompoundTagAt(i).getString("id"))) {
            entries.removeTag(i);
            return;
        }
    }
}
