// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real NBT persistence for explicitly named presets, without automatic recording. */
public final class HologramConfigurationPresetsRegression {

    private HologramConfigurationPresetsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramConfigurationPresetsRegression.class, args)) return;
        run();
        HologramControllerDetailsRegression.hatchFacings();
        System.out.println("PASS preset UI selection, named combinations and manual-preserving undo");
    }

    public static void run() {
        try {
            explicitPresetSelection();
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

    private static void explicitPresetSelection() throws Exception {
        HologramUiModules ui = new HologramUiModules(null);
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setBoolean("supported", true);
        metadata.setBoolean("localStandardController", true);
        metadata.setInteger("hatchTierMax", 12);
        var stateField = HologramUiSession.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(ui.session, new HologramState(metadata));
        ui.config.activateControl(94);
        SimpleAssert.that(
            !ui.config.nativePresetRow(0)
                .startsWith("✓"),
            "fresh projection never preselects ULV");
        SimpleAssert.that(!ui.config.basicPresetActionAvailable(), "fresh projection requires an explicit tier");
        ui.config.activateControl(1700);
        SimpleAssert
            .that(!ui.config.basicPresetActionAvailable(), "an unowned graph cannot apply even an explicit tier");
        SimpleAssert.that(
            ui.config.nativePresetRow(0)
                .startsWith("✓"),
            "explicit ULV selection is visible");
        NBTTagCompound draft = ui.config.backgroundUiState();
        ui.config.restoreBackgroundUiState(new NBTTagCompound());
        SimpleAssert.that(!ui.config.basicPresetActionAvailable(), "missing background draft has no selected tier");
        ui.config.restoreBackgroundUiState(draft);
        SimpleAssert.that(
            ui.config.nativePresetRow(0)
                .startsWith("✓"),
            "background restoration preserves explicit ULV");
        metadata.setInteger("mode", 1);
        stateField.set(ui.session, new HologramState(metadata));
        SimpleAssert.that(ui.config.targetEditingMode(), "replacement never restricts native target configuration");
        metadata.setInteger("mode", 2);
        metadata.setBoolean("presetAvailable", true);
        stateField.set(ui.session, new HologramState(metadata));
        SimpleAssert.that(ui.config.targetEditingMode(), "demolition never freezes native preset or channel editing");
        SimpleAssert
            .that(!ui.config.basicPresetActionAvailable(), "available metadata cannot bypass missing owner identity");
        metadata.setInteger("mode", 0);
        metadata.setBoolean("capturePending", true);
        stateField.set(ui.session, new HologramState(metadata));
        SimpleAssert.that(
            ui.config.nativePresetRow(0)
                .startsWith("✓"),
            "a target recomputation does not erase the explicit tier draft");
        metadata.setBoolean("capturePending", false);
        metadata.setInteger("hatchTier", 0);
        metadata.setBoolean("presetActive", true);
        stateField.set(ui.session, new HologramState(metadata));
        ui.config.activateControl(94);
        SimpleAssert.that(
            ui.config.nativePresetRow(0)
                .startsWith("✓"),
            "applied ULV survives reopening");
        metadata.setBoolean("presetActive", false);
        stateField.set(ui.session, new HologramState(metadata));
        ui.config.activateControl(94);
        SimpleAssert.that(!ui.config.basicPresetActionAvailable(), "cleared preset cannot revive a stale tier");
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
