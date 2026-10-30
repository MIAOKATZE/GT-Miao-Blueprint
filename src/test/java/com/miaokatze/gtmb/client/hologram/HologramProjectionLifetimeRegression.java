package com.miaokatze.gtmb.client.hologram;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramProjectionLifetimeRegression {

    private HologramProjectionLifetimeRegression() {}

    public static void run() {
        SimpleAssert.that(
            HologramVoucherRibbon.contains(10, 20, 220, 28, 225, 23),
            "painted cat/banner pixels own the voucher tooltip");
        SimpleAssert.that(
            !HologramVoucherRibbon.contains(10, 20, 220, 28, 12, 23)
                && !HologramVoucherRibbon.contains(10, 20, 220, 28, 230, 23)
                && !HologramVoucherRibbon.contains(10, 20, 220, 28, 225, 48),
            "row text, the next row and the outside edge never acquire the voucher tooltip");
        SimpleAssert.that(
            HologramVoucherRibbon.contains(10, 20, 220, 14, 227.5f, 21.5f),
            "smaller controls keep the same painted-pixel hit region after scaling");
        SimpleAssert.that(
            !HologramVoucherRibbon.contains(10, 20, 220, 28, 229, 20),
            "the open top-right corner retains the blueprint tooltip");
        HologramProjectionLifetime clock = new HologramProjectionLifetime();
        SimpleAssert.that(clock.visible("one", 1, 1), "active work remains visible");
        SimpleAssert.that(clock.visible("one", 3, 2), "completion starts its retention interval");
        SimpleAssert.that(clock.visible("one", 3, 14_000_000_002L), "completion remains visible before 15 seconds");
        SimpleAssert
            .that(!clock.visible("one", 3, 15_000_000_002L), "repeated completion snapshots cannot extend retention");
        SimpleAssert
            .that(clock.visible("two", 5, 16_000_000_002L), "new session has an independent partial-job deadline");
        SimpleAssert.that(!clock.visible("two", 4, 17_000_000_002L), "cancellation hides immediately");
        SimpleAssert.that(
            clock.visible("two", 4, 17_000_000_003L, true),
            "new local selection remains visible over a stopped projection");
        SimpleAssert.that(clock.visible("one", 5, 20_000_000_000L), "new partial job begins its own retention");
        SimpleAssert.that(!clock.visible("one", 5, 36_000_000_000L), "old partial projection expires");
        SimpleAssert.that(
            clock.visible("one", 5, 36_000_000_001L, true),
            "new selection remains visible after the prior completion deadline");
        SimpleAssert.that(
            !clock.visible("one", 5, 36_000_000_002L),
            "selection display never extends the old placement deadline");
        activeWorkspaceSnapshot();
    }

    @SuppressWarnings("unchecked")
    private static void activeWorkspaceSnapshot() {
        HologramState previous = HologramClient.state;
        try {
            java.lang.reflect.Field epoch = HologramClient.class.getDeclaredField("editorWorkspaceEpoch");
            java.lang.reflect.Field retired = HologramClient.class.getDeclaredField("retiredSessions");
            java.lang.reflect.Method accepts = HologramClient.class
                .getDeclaredMethod("validSnapshotIdentity", net.minecraft.nbt.NBTTagCompound.class);
            epoch.setAccessible(true);
            retired.setAccessible(true);
            accepts.setAccessible(true);
            long previousEpoch = epoch.getLong(null);
            java.lang.reflect.Field[] clearedFields = { HologramClient.class.getDeclaredField("editorSequence"),
                HologramClient.class.getDeclaredField("snapshotWorld"),
                HologramClient.class.getDeclaredField("acceptedBatch") };
            Object[] previousValues = new Object[clearedFields.length];
            for (int i = 0; i < clearedFields.length; i++) {
                clearedFields[i].setAccessible(true);
                previousValues[i] = clearedFields[i].get(null);
            }
            java.util.Set<String> sessions = (java.util.Set<String>) retired.get(null);
            java.util.Set<String> previousSessions = new java.util.LinkedHashSet<>(sessions);
            try {
                net.minecraft.nbt.NBTTagCompound source = new net.minecraft.nbt.NBTTagCompound();
                source.setString("session", "active-workstation-ttl");
                source.setString("generation", "active-workstation-ttl");
                source.setLong("editorWorkspaceEpoch", 3);
                source.setLong("planRevision", 2);
                source.setLong("uiSequence", 5);
                source.setString("phase", "IDLE");
                HologramClient.state = new HologramState(source);
                epoch.setLong(null, 3);
                sessions.remove(source.getString("session"));
                net.minecraft.nbt.NBTTagCompound ack = (net.minecraft.nbt.NBTTagCompound) source.copy();
                ack.setLong("planRevision", 3);
                ack.setLong("uiSequence", 6);
                long idle = HologramProjectionLifetime.SNAPSHOT_TTL_NANOS + 1;
                if (HologramProjectionLifetime
                    .retireSnapshot(source.getString("session"), HologramClient.state.session, true, idle, false))
                    HologramClient.clearProjection();
                SimpleAssert.that(
                    HologramClient.state != null && (Boolean) accepts.invoke(null, ack),
                    "an editor held beyond five minutes keeps the real same-session processing ACK admissible");
                SimpleAssert.that(
                    HologramProjectionLifetime
                        .retireSnapshot(source.getString("session"), "another-workstation", true, idle, false),
                    "a different screen owner cannot retain an idle preview");
                SimpleAssert.that(
                    !HologramProjectionLifetime.retireSnapshot(
                        source.getString("session"),
                        null,
                        true,
                        HologramProjectionLifetime.SNAPSHOT_TTL_NANOS,
                        false),
                    "the original TTL boundary remains exact");
                SimpleAssert.that(
                    !HologramProjectionLifetime.retireSnapshot(source.getString("session"), null, true, idle, true),
                    "existing background work still retains its pending preview");
                SimpleAssert.that(
                    HologramProjectionLifetime
                        .retireSnapshot(source.getString("session"), source.getString("session"), false, 0, true),
                    "world, player or dimension invalidation overrides both editor and background ownership");
                if (HologramProjectionLifetime.retireSnapshot(source.getString("session"), null, true, idle, false))
                    HologramClient.clearProjection();
                SimpleAssert.that(
                    HologramClient.state == null && sessions.contains(source.getString("session"))
                        && !(Boolean) accepts.invoke(null, ack),
                    "closing the editor restores idle TTL retirement and the real late-ACK rejection fence");
                HologramClient.state = new HologramState(source);
                sessions.remove(source.getString("session"));
                HologramClient.clearProjection();
                SimpleAssert.that(
                    sessions.contains(source.getString("session")) && !(Boolean) accepts.invoke(null, ack),
                    "explicit projection cancellation still retires the editor session immediately");
            } finally {
                sessions.clear();
                sessions.addAll(previousSessions);
                epoch.setLong(null, previousEpoch);
                for (int i = 0; i < clearedFields.length; i++) clearedFields[i].set(null, previousValues[i]);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        } finally {
            HologramClient.state = previous;
        }
    }
}
