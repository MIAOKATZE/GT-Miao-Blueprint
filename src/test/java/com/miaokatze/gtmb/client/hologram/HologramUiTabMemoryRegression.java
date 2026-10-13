package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** The selected workspace tab survives a new server session and client restart. */
public final class HologramUiTabMemoryRegression {

    private HologramUiTabMemoryRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        try {
            exercisePersistence();
            draftNameSurvivesChildRevision();
        } catch (Exception failure) {
            throw new IllegalStateException("Workspace tab persistence regression failed", failure);
        }
    }

    private static void draftNameSurvivesChildRevision() throws Exception {
        net.minecraft.nbt.NBTTagCompound before = new net.minecraft.nbt.NBTTagCompound();
        before.setString("session", "actual-editor-session");
        before.setBoolean("virtual", true);
        before.setString("virtualPresetId", "private-source");
        before.setString("libraryLocalId", "local-source");
        before.setString("virtualPresetName", "施工蓝图");
        before.setLong("planRevision", 4);
        HologramDraftNameMemory remembered = new HologramDraftNameMemory(before, "SocketAEV005");
        net.minecraft.nbt.NBTTagCompound child = com.miaokatze.gtmb.hologram.HologramSnapshotCodec
            .decode(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(before));
        child.setLong("planRevision", 19);
        SimpleAssert.eq(
            "SocketAEV005",
            remembered.restore(child),
            "AE child revisions and actual snapshot round trip retain the user's name for the same loaded source");
        child.setString("libraryLocalId", "different-local-source");
        SimpleAssert.eq(
            "施工蓝图",
            remembered.restore(child),
            "another imported client blueprint cannot inherit the previous draft name");
        child.setString("libraryLocalId", "local-source");
        child.setString("session", "different-editor-session");
        SimpleAssert.eq(
            "施工蓝图",
            remembered.restore(child),
            "another server session does not inherit text even when its loaded source id matches");
        child.setString("session", "actual-editor-session");
        child.setString("virtualPresetName", "SocketAEV005 saved canonical");
        SimpleAssert.eq(
            "SocketAEV005 saved canonical",
            remembered.restore(child),
            "a confirmed canonical server name wins over an older cached textbox value");
        child.removeTag("libraryLocalId");
        before.removeTag("libraryLocalId");
        remembered = new HologramDraftNameMemory(before, "Unsaved user text");
        child.setString("virtualPresetName", "施工蓝图");
        child.setString("virtualPresetId", "public-different-source");
        SimpleAssert.eq(
            "施工蓝图",
            remembered.restore(child),
            "a different public preset cannot inherit text when no client local id exists");
        SimpleAssert.that(
            !HologramDraftNameMemory.sameSource(before, child),
            "the live editor detects loaded-source replacement independently of plan revision");
    }

    private static void exercisePersistence() throws Exception {
        Path directory = Files.createTempDirectory("gtmb-ui-tab-");
        Path file = directory.resolve("config/gtmb-hologram-ui.properties");
        try {
            HologramUiTabMemory memory = new HologramUiTabMemory(file.toFile());
            SimpleAssert.that(!memory.blueprints(false), "first ordinary workspace defaults to structure");
            SimpleAssert.that(memory.blueprints(true), "an explicit initial library entry stays available");
            memory.remember(true);
            SimpleAssert.that(
                new HologramUiTabMemory(file.toFile()).blueprints(false),
                "blueprint tab survives a new session and restart");
            memory.remember(false);
            SimpleAssert.that(
                !new HologramUiTabMemory(file.toFile()).blueprints(true),
                "structure choice replaces the previous blueprint preference");
            Files.write(file, "tab=invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SimpleAssert.that(
                !new HologramUiTabMemory(file.toFile()).blueprints(false),
                "an invalid preference falls back to the valid opening tab");
            HologramUiTabMemory unavailable = new HologramUiTabMemory(directory.toFile());
            unavailable.remember(true);
            SimpleAssert.that(unavailable.blueprints(false), "unwritable preferences remain available in memory");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(directory);
        }
    }
}
