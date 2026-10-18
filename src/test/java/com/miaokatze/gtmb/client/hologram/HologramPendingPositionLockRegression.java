package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** The first click is visible immediately, while construction authority still comes from the server. */
final class HologramPendingPositionLockRegression {

    private HologramPendingPositionLockRegression() {}

    static void run() {
        HologramPendingPositionLock lock = new HologramPendingPositionLock();
        NBTTagCompound state = state("lock", 8, 12), request = new NBTTagCompound();
        int[] target = { 91, 72, -8 };
        SimpleAssert.that(lock.begin(state, target, 15, request), "the first click starts one local lock");
        SimpleAssert.eq(16L, request.getLong("uiSequence"), "lock uses the live command watermark");
        SimpleAssert.eq(91, request.getInteger("x"), "request and visual intent share the real target");
        target[0] = 400;
        SimpleAssert.eq(91, lock.anchor()[0], "camera target changes cannot move the pending preview");
        SimpleAssert.that(!state.getBoolean("virtualLocked"), "visual intent never edits server authority");
        SimpleAssert.that(!lock.begin(state, target, 20, new NBTTagCompound()), "another click cannot resubmit");
        lock.snapshot(state("lock", 8, 15));
        SimpleAssert.that(lock.pending(state), "old ACK does not release the current lock");
        NBTTagCompound rejected = state("other", 8, 16);
        SimpleAssert.that(!lock.rejected(rejected), "another session cannot reject a lock");
        rejected = state("lock", 8, 15);
        SimpleAssert.that(!lock.rejected(rejected), "old rejection cannot release a newer lock");
        rejected.setLong("uiSequence", 16);
        SimpleAssert.that(lock.rejected(rejected), "matching rejection releases the local lock");
        SimpleAssert.that(!lock.pending(state), "denial restores moving input");
        SimpleAssert.that(lock.begin(state, target, -1, request), "a denied click can retry");
        SimpleAssert.eq(17L, request.getLong("uiSequence"), "retry has a fresh identity despite an old snapshot");
        NBTTagCompound prepare = state("lock", 8, 17);
        prepare.setString("phase", "PREPARE");
        lock.snapshot(prepare);
        SimpleAssert.that(lock.pending(state), "preparation is not an authority ACK");
        prepare.setString("phase", "ACK");
        lock.snapshot(prepare);
        SimpleAssert.that(!lock.pending(state), "the matching authority ACK completes waiting");
        lock.begin(state, target, -1, request);
        lock.snapshot(state("lock", 9, 12));
        SimpleAssert.that(!lock.pending(state), "revision changes revoke the old visual intent");
        lock.begin(state, target, -1, request);
        lock.snapshot(state("replacement", 8, 12));
        SimpleAssert.that(!lock.pending(state), "a session replacement cannot inherit a local lock");
        lock.begin(state, target, -1, request);
        long sequence = request.getLong("uiSequence");
        NBTTagCompound cancel = new NBTTagCompound();
        lock.cancel(cancel);
        SimpleAssert.eq(sequence, cancel.getLong("positionLockSequence"), "Escape correlates the in-flight lock");
        SimpleAssert.eq(sequence, cancel.getLong("uiSequence"), "busy cancellation replies retain the lock identity");
        SimpleAssert.that(!lock.pending(state) && lock.cancelling(state), "Escape clears lock and fences stale ACK");
        lock.snapshot(state("lock", 9, sequence));
        SimpleAssert.that(lock.cancelling(state), "late moved lock ACK cannot resurrect a cancelled preview");
        NBTTagCompound cancelled = state("lock", 9, sequence);
        cancelled.setBoolean("virtualPlacement", false);
        lock.snapshot(cancelled);
        SimpleAssert.that(!lock.cancelling(state), "cancel authority ACK releases its visual fence");
        lock.begin(state, target, -1, request);
        lock.clear();
        SimpleAssert.that(!lock.pending(state) && !lock.cancelling(state), "world teardown drops all local intent");
        clientRenderBinding();
    }

    private static void clientRenderBinding() {
        try {
            java.lang.reflect.Field field = HologramClient.class.getDeclaredField("pendingPositionLock");
            field.setAccessible(true);
            HologramPendingPositionLock lock = (HologramPendingPositionLock) field.get(null);
            HologramState state = new HologramState(state("render-lock", 4, 6));
            int[] target = { 100, 64, -100 };
            lock.begin(state.data, target, -1, new NBTTagCompound());
            SimpleAssert.that(HologramClient.positionLockPending(state), "real client exposes pending intent");
            SimpleAssert.that(HologramClient.positionLocked(state), "render input becomes locked immediately");
            SimpleAssert.eq(100, HologramClient.movingAnchor(state)[0], "renderer consumes the frozen target");
            SimpleAssert.eq(0x66d9ef, HologramRenderer.placementOutlineColor(state), "pending outline is lock cyan");
            SimpleAssert.eq(0, state.data.getInteger("job"), "visual lock cannot start construction");
            SimpleAssert.that(!state.data.getBoolean("placementReviewIssued"), "visual lock cannot issue a review");
            lock.clear();
            SimpleAssert.eq(0x70e69a, HologramRenderer.placementOutlineColor(state), "failure restores moving green");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static NBTTagCompound state(String session, long revision, long sequence) {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", session);
        state.setLong("planRevision", revision);
        state.setLong("uiSequence", sequence);
        state.setBoolean("virtual", true);
        state.setBoolean("virtualPlacement", true);
        state.setBoolean("moving", true);
        state.setInteger("side", 1);
        return state;
    }
}
