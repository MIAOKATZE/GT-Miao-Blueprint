package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** A terminal receive failure cannot release a newer or unrelated UI command. */
final class HologramSnapshotFailureCorrelationRegression {

    static void run() {
        HologramClient.clearSnapshotFailures();
        try {
            NBTTagCompound first = queued(17, "first", 31);
            SimpleAssert
                .that(HologramClient.snapshotFailureHeader(first) == null, "queued metadata alone is not failure");
            NBTTagCompound failure = HologramClient.failedSnapshotHeader(17, null, new Exception("too large"));
            SimpleAssert.eq("first", failure.getString("session"), "known failure keeps exact session");
            SimpleAssert.eq(31L, failure.getLong("uiSequence"), "known failure keeps exact command");
            SimpleAssert.eq(9L, failure.getLong("planRevision"), "failure keeps exact plan revision");
            SimpleAssert.eq(52L, failure.getLong("clientTaskToken"), "failure keeps exact task owner token");
            SimpleAssert.that(
                HologramClient.failedSnapshotHeader(18, null, new Exception("too large")) == null,
                "a header not yet scheduled on the owner thread is deferred");
            SimpleAssert.that(
                HologramClient.snapshotFailureHeader(queued(19, "unrelated", 33)) == null,
                "an unrelated queued receipt cannot complete the pending failure");
            failure = HologramClient.snapshotFailureHeader(queued(18, "late", 32));
            SimpleAssert.eq("late", failure.getString("session"), "late exact header completes its own failure");
            SimpleAssert.eq(32L, failure.getLong("uiSequence"), "late header cannot borrow a newer command");
            HologramClient.snapshotFailureHeader(queued(20, "successful", 34));
            HologramClient.snapshotTransportSucceeded(20);
            SimpleAssert.that(
                HologramClient.failedSnapshotHeader(20, null, new Exception("stale")) == null,
                "success retires its correlation metadata");
            HologramClient.clearSnapshotFailures();
            SimpleAssert.that(
                HologramClient.snapshotFailureHeader(queued(20, "new world", 0)) == null,
                "world teardown clears a deferred failure before a new-world header");
            for (int i = 0; i < 100; i++) {
                HologramClient.snapshotFailureHeader(queued(100 + i, "bounded", i));
                HologramClient.failedSnapshotHeader(1000 + i, null, new Exception("bounded"));
            }
            for (String name : new String[] { "snapshotFailureHeaders", "pendingSnapshotFailures" }) {
                java.lang.reflect.Field field = HologramClient.class.getDeclaredField(name);
                field.setAccessible(true);
                SimpleAssert
                    .that(((java.util.Map<?, ?>) field.get(null)).size() <= 64, "correlation metadata is bounded");
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        } finally {
            HologramClient.clearSnapshotFailures();
        }
    }

    private static NBTTagCompound queued(long sequence, String session, long command) {
        NBTTagCompound progress = new NBTTagCompound();
        progress.setString("scope", "server");
        progress.setString("phase", "snapshot.queued");
        progress.setLong("transportSequence", sequence);
        progress.setString("session", session);
        progress.setLong("uiSequence", command);
        progress.setLong("planRevision", 9);
        progress.setLong("clientTaskToken", 52);
        return progress;
    }
}
