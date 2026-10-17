package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.ChunkCoordIntPair;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Pure bounds/config/durable-claim tests; actual FML worlds, actors and commands need the separate native probe. */
public final class TestLabRegression {

    private TestLabRegression() {}

    public static void run() {
        TestLabConfig defaults = config(4096);
        SimpleAssert.that(!defaults.enabled, "lab opt-in defaults off");
        SimpleAssert.eq(1048578, defaults.dimension, "high ID avoids referenced GTSR/Outpost defaults");
        SimpleAssert.eq(5, defaults.zones.size(), "five distinct facilities");
        for (int i = 0; i < defaults.zones.size(); i++) {
            TestLabZone zone = defaults.zones.get(i);
            SimpleAssert.that(zone.contains(zone.x, zone.y, zone.z, 32, 16, 32), "exact facility volume admitted");
            SimpleAssert.that(!zone.contains(zone.x - 1, zone.y, zone.z, 32, 16, 32), "outside reset origin rejected");
            SimpleAssert
                .that(!zone.contains(zone.x, zone.y, zone.z, Integer.MAX_VALUE, 1, 1), "overflow-safe extent rejected");
            SimpleAssert.that(!zone.contains(zone.x, zone.y, zone.z, 0, 1, 1), "empty facility request rejected");
            int[] last = zone.cell(zone.cells() - 1);
            SimpleAssert.eq(zone.x + 31, last[0], "reset cursor final X");
            SimpleAssert.eq(zone.y + 15, last[1], "reset cursor final Y");
            SimpleAssert.eq(zone.z + 31, last[2], "reset cursor final Z");
            for (int j = i + 1; j < defaults.zones.size(); j++)
                SimpleAssert.that(Math.abs(zone.x - defaults.zones.get(j).x) >= 4096, "distant zones cannot overlap");
        }
        rejected(
            () -> new TestLabConfig(true, true, 1, 1048578, 4096, 4096, 4096, 80, 8, 512, 5, 60, 64, 512),
            "vanilla dimension ID rejected");
        rejected(
            () -> new TestLabConfig(true, true, 1048578, 1048578, 4096, 4096, 16, 80, 8, 512, 5, 60, 64, 512),
            "overlapping layout rejected");
        rejected(
            () -> new TestLabConfig(true, true, 1048578, 1048578, 4096, 4096, 4096, 80, 8, 512, 5, 60, 10001, 512),
            "unbounded stress rejected");
        try {
            Path dir = Files.createTempDirectory("gtmb-owned-testlab-");
            TestLabConfig loaded = TestLabConfig.load(dir.toFile());
            SimpleAssert.that(!loaded.enabled, "generated local Forge file stays disabled");
            TestLab.preInit(dir.toFile());
            TestLab.init();
            TestLab.serverStarting(null);
            TestLab.serverStopping();
            TestLab.serverStopped();
            NBTTagCompound status = TestLab.status();
            for (String flag : new String[] { "configEnabled", "enabled", "dimensionRegistered", "providerRegistered",
                "providerReady", "handlerRegistered", "clientDimensionMapped", "worldLoaded", "active" })
                SimpleAssert.that(!status.getBoolean(flag), "disabled lifecycle has no " + flag);
            SimpleAssert
                .that(!TestLab.useTestItem(null, null, null, 0, 0, 0), "disabled effect entry is inert and null-safe");
            SimpleAssert.that(TestLab.testItem() == null, "disabled accessor creates no actor or equipment");
            Path foreign = dir.resolve("DIM" + loaded.dimension);
            Files.createDirectory(foreign);
            refused(() -> new TestLabOwnership(dir, loaded), "unmarked pre-existing dimension cannot be claimed");
            Files.delete(foreign);
            TestLabOwnership ownership = new TestLabOwnership(dir, loaded);
            SimpleAssert.eq(
                ownership.owner.toString(),
                new TestLabOwnership(dir, loaded).owner.toString(),
                "save ownership survives a new reader");
            Path marker = dir.resolve("gtmb-testlab/ownership-" + loaded.dimension + ".nbt");
            byte[] before = Files.readAllBytes(marker);
            refused(() -> new TestLabOwnership(dir, config(8192)), "changed owned layout cannot reset old coordinates");
            SimpleAssert.that(
                Arrays.equals(before, Files.readAllBytes(marker)),
                "failed adoption leaves durable owner bytes unchanged");
            Files.delete(marker);
            Files.delete(marker.getParent());
            Files.delete(dir.resolve("gtmb-testlab.cfg"));
            Files.delete(dir);
        } catch (IOException failure) {
            throw new AssertionError("testlab durable ownership/config", failure);
        }
        TestLabReport report = new TestLabReport("test", "minimal", defaults);
        report.check("case", "PASS", "detached");
        report.data.setString("state", "PASS");
        report.data.getCompoundTag("timing")
            .setLong("elapsedNanos", 123);
        NBTTagCompound copy = report.snapshot();
        copy.setString("runId", "changed");
        SimpleAssert.eq(
            "test",
            report.snapshot()
                .getString("runId"),
            "probe report is detached");
        SimpleAssert.that(
            report.snapshot()
                .getCompoundTag("timing")
                .getLong("elapsedNanos") == 123,
            "completed elapsed time is frozen");
        NBTTagCompound coordinate = new NBTTagCompound();
        coordinate.setDouble("actualX", 8.5);
        SimpleAssert.that(
            ((Number) ((Map<?, ?>) TestLabReport.json(coordinate)).get("actualX")).doubleValue() == 8.5,
            "JSON keeps actual actor coordinates");
        NBTTagList labels = new NBTTagList();
        labels.appendTag(new NBTTagString("first"));
        labels.appendTag(new NBTTagString("second"));
        List<?> labelJson = (List<?>) TestLabReport.json(labels);
        SimpleAssert.eq("first", labelJson.get(0), "JSON keeps non-compound list values and order");
        SimpleAssert.eq("second", labelJson.get(1), "JSON keeps final list value");
        SimpleAssert.eq(2, labels.tagCount(), "JSON does not mutate native report list");
        lifecycleCleanup(defaults);
        clientMappingLifecycle();
    }

    /** Exercise actual Forge mappings without initializing any world or linking client classes. */
    private static void clientMappingLifecycle() {
        final int dimension = 1999988, provider = 1999988;
        SimpleAssert.that(
            !net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimension),
            "dedicated client-mapping regression ID starts unclaimed");
        SimpleAssert.that(
            net.minecraftforge.common.DimensionManager
                .registerProviderType(provider, TestLabWorldProvider.class, false),
            "regression provider type registered without a world");
        Object oldConnection = new Object(), newConnection = new Object();
        TestLabClientMapping mapping = new TestLabClientMapping();
        try {
            SimpleAssert.that(
                !mapping.connected(oldConnection, false, false, true, false, dimension, provider),
                "disabled client cannot create a dimension mapping");
            SimpleAssert.that(
                !mapping.connected(oldConnection, false, true, false, false, dimension, provider),
                "missing provider cannot create a client dimension mapping");
            SimpleAssert.that(
                !mapping.connected(oldConnection, true, true, true, false, dimension, provider),
                "local integrated connection leaves server ownership alone");
            SimpleAssert.that(
                !mapping.connected(oldConnection, false, true, true, true, dimension, provider),
                "server-admitted mapping is never claimed by client lifecycle");
            SimpleAssert.that(
                !net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimension),
                "rejected client lifecycle paths are inert");
            SimpleAssert.that(
                mapping.connected(oldConnection, false, true, true, false, dimension, provider),
                "enabled remote connection registers its configured mapping");
            SimpleAssert.eq(
                provider,
                net.minecraftforge.common.DimensionManager.getProviderType(dimension),
                "client mapping points at the existing configured provider");
            SimpleAssert.that(
                net.minecraftforge.common.DimensionManager.getWorld(dimension) == null,
                "client registration does not construct a world");
            SimpleAssert.that(
                mapping.connected(newConnection, false, true, true, false, dimension, provider),
                "new remote session takes ownership of the same client-owned mapping");
            mapping.disconnected(oldConnection, false);
            SimpleAssert.that(
                net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimension),
                "late old-session disconnect cannot remove the new session mapping");
            mapping.disconnected(newConnection, false);
            mapping.disconnected(newConnection, false);
            SimpleAssert.that(
                !mapping.owned() && !net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimension),
                "matching disconnect releases its own mapping exactly once");
            net.minecraftforge.common.DimensionManager.registerDimension(dimension, provider);
            SimpleAssert.that(
                !mapping.connected(newConnection, false, true, true, false, dimension, provider),
                "client never adopts an existing unowned mapping, even with matching provider");
            mapping.disconnected(newConnection, false);
            SimpleAssert.that(
                net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimension),
                "unowned integrated or foreign mapping survives client disconnect");
            net.minecraftforge.common.DimensionManager.unregisterDimension(dimension);
            mapping.connected(newConnection, false, true, true, false, dimension, provider);
            mapping.beforeServerWorldsLoad(false);
            SimpleAssert.that(
                !net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimension),
                "integrated startup drops only its pending client-owned mapping before loadAllWorlds");
            mapping.disconnected(newConnection, false);
            mapping.connected(newConnection, false, true, true, false, dimension, provider);
            mapping.beforeServerWorldsLoad(true);
            SimpleAssert.that(
                net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimension),
                "integrated-server ownership prevents client unregister");
            net.minecraftforge.common.DimensionManager.unregisterDimension(dimension);
            mapping.connected(newConnection, false, true, true, false, dimension, provider);
            net.minecraftforge.common.DimensionManager.unregisterDimension(dimension);
            net.minecraftforge.common.DimensionManager.registerDimension(dimension, 0);
            mapping.disconnected(newConnection, false);
            SimpleAssert.eq(
                0,
                net.minecraftforge.common.DimensionManager.getProviderType(dimension),
                "changed foreign mapping is not released under stale client ownership");
            SimpleAssert.that(
                new TestLabWorldProvider().getEntrancePortalLocation() == null,
                "unadmitted disabled provider entrance is inert and does not require server configuration");
        } finally {
            if (net.minecraftforge.common.DimensionManager.isDimensionRegistered(dimension))
                net.minecraftforge.common.DimensionManager.unregisterDimension(dimension);
            net.minecraftforge.common.DimensionManager.unregisterProviderType(provider);
        }
    }

    /** Exercise failed cleanup retention and retry without constructing or loading a Minecraft world. */
    private static void lifecycleCleanup(TestLabConfig config) {
        try {
            TestLabRuntime runtime = new TestLabRuntime(config);
            NBTTagCompound terminalActor = new NBTTagCompound();
            terminalActor.setString("id", "detached-terminal-actor");
            terminalActor.setDouble("actualX", 4096.5);
            field(TestLabRuntime.class, "lastActor").set(runtime, terminalActor);
            NBTTagCompound observed = runtime.actorTag();
            observed.setString("id", "changed-by-observer");
            SimpleAssert.eq(
                "detached-terminal-actor",
                runtime.actorTag()
                    .getString("id"),
                "terminal actor observation is detached");
            TestLabLease lease = new TestLabLease(null, config, null, "cleanup-retry-unit");
            @SuppressWarnings("unchecked")
            Set<ChunkCoordIntPair> chunks = (Set<ChunkCoordIntPair>) field(TestLabLease.class, "chunks").get(lease);
            chunks.add(new ChunkCoordIntPair(1, 1));
            field(TestLabRuntime.class, "lease").set(runtime, lease);
            // Deliberately absent world makes unload fail; its owned chunk must stay reachable for retry.
            runtime.stop("unit cleanup failure");
            SimpleAssert.that(
                field(TestLabRuntime.class, "lease").get(runtime) == lease,
                "cleanup failure retains exact lease owner");
            SimpleAssert.that(
                field(TestLabRuntime.class, "cleanupPending").getBoolean(runtime),
                "cleanup failure is observable");
            SimpleAssert.eq(1, lease.size(), "cleanup failure does not invent zero owned chunks");
            SimpleAssert
                .eq("FAIL", field(TestLabRuntime.class, "state").get(runtime), "cleanup failure cannot report pass");
            // The native release condition is now satisfied. Stop must retry even though no task is active.
            chunks.clear();
            runtime.stop("unit cleanup retry");
            runtime.stop("unit cleanup idempotence");
            for (String name : new String[] { "world", "actor", "lease", "backend", "task" }) SimpleAssert
                .that(field(TestLabRuntime.class, name).get(runtime) == null, "successful cleanup releases " + name);
            SimpleAssert.that(
                !field(TestLabRuntime.class, "cleanupPending").getBoolean(runtime),
                "successful retry clears pending cleanup");
            SimpleAssert.eq(
                "detached-terminal-actor",
                runtime.actorTag()
                    .getString("id"),
                "cleanup preserves terminal actor evidence");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("testlab lifecycle cleanup", failure);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static TestLabConfig config(int x) {
        return new TestLabConfig(false, true, 1048578, 1048578, x, 4096, 4096, 80, 8, 512, 5, 60, 64, 512);
    }

    private static void rejected(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IllegalArgumentException expected) {}
    }

    private static void refused(Checked action, String label) throws IOException {
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IOException expected) {}
    }

    private interface Checked {

        void run() throws IOException;
    }
}
