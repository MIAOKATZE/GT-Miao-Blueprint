package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

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
                "providerReady", "handlerRegistered", "worldLoaded", "active" })
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
