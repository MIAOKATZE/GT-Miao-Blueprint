package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real NBT persistence and exact successful-command receipts; no UI or server-state prediction. */
public final class HologramConfigurationPresetsRegression {

    private HologramConfigurationPresetsRegression() {}

    public static void run() {
        try {
            Path parent = Paths.get("build", "tmp");
            Files.createDirectories(parent);
            Path directory = Files.createTempDirectory(parent, "hatch-presets-");
            Path file = directory.resolve("presets.nbt");
            try {
                exercise(file);
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Configuration preset regression failed", failure);
        }
    }

    private static void exercise(Path file) throws Exception {
        HologramConfigurationPresets store = new HologramConfigurationPresets(file.toFile());
        NBTTagCompound profile = profile(2, 3);
        store.save("Mixed voltage", profile, false);
        profile.getTagList("entries", 10)
            .getCompoundTagAt(0)
            .setInteger("count", 90);
        SimpleAssert.eq(
            2,
            store.get("Mixed voltage")
                .getTagList("entries", 10)
                .getCompoundTagAt(0)
                .getInteger("count"),
            "saved combinations detach caller-owned NBT");
        NBTTagCompound detached = store.get("Mixed voltage");
        detached.getTagList("entries", 10)
            .getCompoundTagAt(1)
            .getCompoundTag("tag")
            .setString("variant", "edited");
        HologramConfigurationPresets reopened = new HologramConfigurationPresets(file.toFile());
        SimpleAssert.eq(
            "declared",
            reopened.get("Mixed voltage")
                .getTagList("entries", 10)
                .getCompoundTagAt(1)
                .getCompoundTag("tag")
                .getString("variant"),
            "reload retains machine-definition NBT and getter edits stay isolated");
        SimpleAssert.eq(
            3,
            reopened.get("Mixed voltage")
                .getTagList("entries", 10)
                .getCompoundTagAt(1)
                .getInteger("count"),
            "the complete second role and quantity survive disk reload");
        boolean rejected = false;
        try {
            reopened.save("Mixed voltage", profile(1, 1), false);
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "duplicate names require an explicit overwrite");
        reopened.save("Mixed voltage", profile(4, 5), true);
        SimpleAssert.eq(
            1,
            reopened.names()
                .size(),
            "overwriting preserves one named entry");
        SimpleAssert.eq(
            5,
            new HologramConfigurationPresets(file.toFile()).get("Mixed voltage")
                .getTagList("entries", 10)
                .getCompoundTagAt(1)
                .getInteger("count"),
            "overwrite persists the complete replacement");

        HologramConfigurationPresets.Pending pending = new HologramConfigurationPresets.Pending();
        NBTTagCompound snapshot = new NBTTagCompound();
        pending.begin("one", 12, true, profile(4, 5));
        snapshot.setLong("uiSequence", 11);
        snapshot.setLong("configurationAcceptedSequence", 12);
        pending.acknowledge("one", snapshot, reopened);
        SimpleAssert.eq(null, reopened.last(true), "a stale snapshot cannot acknowledge an apply");
        snapshot.setLong("uiSequence", 12);
        pending.acknowledge("another", snapshot, reopened);
        SimpleAssert.eq(null, reopened.last(true), "a different world session cannot acknowledge an apply");
        snapshot.setLong("configurationAcceptedSequence", 11);
        pending.acknowledge("one", snapshot, reopened);
        snapshot.setLong("configurationAcceptedSequence", 12);
        pending.acknowledge("one", snapshot, reopened);
        SimpleAssert
            .eq(null, reopened.last(true), "a failed receipt retires the request; late snapshots cannot resurrect it");
        pending.begin("one", 13, true, profile(4, 5));
        snapshot.setLong("uiSequence", 13);
        snapshot.setLong("configurationAcceptedSequence", 13);
        pending.acknowledge("one", snapshot, reopened);
        SimpleAssert
            .eq(profile(4, 5), reopened.last(true), "only an exact accepted command becomes the last hatch preset");
        NBTTagCompound sources = new NBTTagCompound();
        sources.setBoolean("materialMain", false);
        sources.setBoolean("materialContainers", true);
        sources.setBoolean("materialMe", true);
        sources.setInteger("materialPriority", 2);
        pending.begin("one", 14, false, sources);
        snapshot.setLong("uiSequence", 14);
        snapshot.setLong("configurationAcceptedSequence", 14);
        pending.acknowledge("one", snapshot, reopened);
        pending.begin("one", 15, false, new NBTTagCompound());
        pending.reject(15);
        snapshot.setLong("uiSequence", 15);
        snapshot.setLong("configurationAcceptedSequence", 15);
        pending.acknowledge("one", snapshot, reopened);
        HologramConfigurationPresets again = new HologramConfigurationPresets(file.toFile());
        SimpleAssert.eq(
            sources,
            again.last(false),
            "source order and enabled flags persist independently; denied drafts never replace them");
        SimpleAssert.eq(
            profile(4, 5),
            again.last(true),
            "remembering material sources leaves the last hatch combination intact");
        again.delete("Mixed voltage");
        SimpleAssert.that(
            new HologramConfigurationPresets(file.toFile()).names()
                .isEmpty(),
            "deletion persists");
        SimpleAssert.eq(
            profile(4, 5),
            again.last(true),
            "deleting a named shortcut preserves the last successfully applied copy");
    }

    private static NBTTagCompound profile(int inputCount, int energyCount) {
        NBTTagCompound profile = new NBTTagCompound();
        profile.setInteger("tier", 4);
        profile.setBoolean("downgrade", false);
        NBTTagList entries = new NBTTagList();
        for (int i = 0; i < 2; i++) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("role", i == 0 ? "InputHatch" : "Energy");
            entry.setString("item", "gregtech:gt.blockmachines");
            entry.setInteger("damage", i == 0 ? 44 : 48);
            entry.setInteger("count", i == 0 ? inputCount : energyCount);
            NBTTagCompound definition = new NBTTagCompound();
            definition.setString("variant", "declared");
            entry.setTag("tag", definition);
            entries.appendTag(entry);
        }
        profile.setTag("entries", entries);
        return profile;
    }
}
