package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;

import net.minecraft.nbt.NBTTagCompound;

/** User preferences only: a remembered zero is distinct from an unchosen recommendation. */
final class HologramDeidentificationMemory {

    private static HologramDeidentificationMemory client;
    private final File file;
    private final Properties values = new Properties();

    HologramDeidentificationMemory(File file) {
        this.file = file;
        if (file.isFile()) try (InputStream input = Files.newInputStream(file.toPath())) {
            values.load(input);
        } catch (IOException | IllegalArgumentException ignored) {}
    }

    static HologramDeidentificationMemory client() {
        if (client == null) client = new HologramDeidentificationMemory(
            new File(
                net.minecraft.client.Minecraft.getMinecraft().mcDataDir,
                "config/gtmb-deidentification.properties"));
        return client;
    }

    static String source(NBTTagCompound snapshot) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        return source(
            snapshot,
            mc.func_147104_D() == null ? "integrated" : mc.func_147104_D().serverIP,
            mc.thePlayer == null ? ""
                : mc.thePlayer.getUniqueID()
                    .toString());
    }

    Integer choice(String source) {
        if (source.isEmpty()) return null;
        try {
            int level = Integer.parseInt(values.getProperty(source));
            return level >= 0 && level <= 10 ? level : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    void remember(String source, int level) {
        if (source.isEmpty() || level < 0 || level > 10) return;
        if (Integer.toString(level)
            .equals(values.getProperty(source))) return;
        values.setProperty(source, Integer.toString(level));
        try {
            Files.createDirectories(
                file.toPath()
                    .getParent());
            try (OutputStream output = Files.newOutputStream(file.toPath())) {
                values.store(output, "GT Miao Blueprint deidentification choices 0..10");
            }
        } catch (IOException ignored) {}
    }

    static String source(NBTTagCompound snapshot, String worldScope) {
        return source(snapshot, worldScope, "");
    }

    static String source(NBTTagCompound snapshot, String worldScope, String actorUuid) {
        String actor = actorUuid + ":";
        String local = snapshot.getString("libraryLocalId");
        if (!local.isEmpty()) return actor + "local:" + local;
        String preset = snapshot.getString("virtualPresetId");
        if (preset.isEmpty() && snapshot.getBoolean("virtual")) preset = snapshot.getString("selectedPresetId");
        if (!preset.isEmpty()) return actor + worldScope + ":preset:" + preset;
        // Unsaved drafts are isolated to their session, never conflated by an editable display name.
        return actor + worldScope + ":draft:" + snapshot.getString("session");
    }

    static int minimum(NBTTagCompound snapshot) {
        return Math.max(0, Math.min(10, snapshot.getInteger("deidentificationMinimumLevel")));
    }

    static int level(NBTTagCompound snapshot, boolean placement, Integer remembered) {
        String level = placement ? "placementDeidentificationLevel" : "blueprintDeidentificationLevel";
        // Blueprint metadata records its author's choice; it is not this placing player's preference.
        boolean chosen = placement && snapshot.getBoolean("placementDeidentificationChosen");
        int actual = snapshot.hasKey(level) ? snapshot.getInteger(level) : 0;
        int recommended = snapshot.hasKey("deidentificationRecommendedLevel")
            ? snapshot.getInteger("deidentificationRecommendedLevel")
            : 1;
        int value = remembered != null ? remembered : chosen ? actual : Math.max(actual, recommended);
        return Math.max(minimum(snapshot), Math.max(0, Math.min(10, value)));
    }
}
