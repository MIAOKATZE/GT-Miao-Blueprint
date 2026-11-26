// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real NBT persistence for explicitly named presets, without automatic recording. */
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

        try (var input = Files.newInputStream(file)) {
            NBTTagCompound stored = net.minecraft.nbt.CompressedStreamTools.readCompressed(input);
            SimpleAssert.that(
                !stored.hasKey("lastHatch") && !stored.hasKey("lastSources"),
                "the named preset store does not automatically create a last-used profile");
        }
        reopened.delete("Mixed voltage");
        SimpleAssert.that(
            new HologramConfigurationPresets(file.toFile()).names()
                .isEmpty(),
            "deletion persists");
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
