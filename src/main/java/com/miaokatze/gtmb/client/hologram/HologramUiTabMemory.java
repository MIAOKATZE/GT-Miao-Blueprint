package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;

import net.minecraft.nbt.NBTTagCompound;

/** Client presentation preferences; placement authority and blueprint identity remain server state. */
final class HologramUiTabMemory {

    private final File file;
    private Boolean blueprints;
    private final Properties values = new Properties();
    private boolean saveGuidanceSuppressed;

    HologramUiTabMemory(File file) {
        this.file = file;
        if (!file.isFile()) return;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            values.load(input);
            saveGuidanceSuppressed = Boolean.parseBoolean(values.getProperty("saveGuidanceSuppressed", "false"));
            String tab = values.getProperty("tab");
            if ("blueprints".equals(tab)) blueprints = true;
            else if ("structure".equals(tab)) blueprints = false;
        } catch (IOException ignored) {
            // A missing or unreadable preference never blocks the projector workspace.
        }
    }

    boolean blueprints(boolean fallback) {
        return blueprints == null ? fallback : blueprints;
    }

    /** A server opening intent wins; its sticky workspace default cannot replace a remembered user tab. */
    static Boolean openingTab(NBTTagCompound state, boolean sessionRemembered) {
        String purpose = state.getString("openPurpose");
        if ("controllerStructure".equals(purpose)) return false;
        if ("blueprintBrowse".equals(purpose) || "blueprintEdit".equals(purpose)
            || "blueprintPlacement".equals(purpose)) return true;
        if (sessionRemembered) return null;
        String workspace = state.getString("workspaceTab");
        if ("blueprint".equals(workspace) && !state.getBoolean("virtualPlacement")) return true;
        if ("structure".equals(workspace) || workspace.isEmpty() && state.getBoolean("virtual")
            && purpose.isEmpty()
            && !state.getBoolean("blueprintSaved")
            && !state.getBoolean("virtualPlacement")
            && !state.getBoolean("presetBrowseForPlacement")) return false;
        return null;
    }

    void remember(boolean selected) {
        if (blueprints != null && blueprints == selected) return;
        blueprints = selected;
        values.setProperty("tab", selected ? "blueprints" : "structure");
        persist();
    }

    boolean saveGuidanceSuppressed() {
        return saveGuidanceSuppressed;
    }

    void suppressSaveGuidance() {
        saveGuidanceSuppressed = true;
        values.setProperty("saveGuidanceSuppressed", "true");
        persist();
    }

    private void persist() {
        try {
            Files.createDirectories(
                file.toPath()
                    .getParent());
            try (OutputStream output = Files.newOutputStream(file.toPath())) {
                values.store(output, "GT Miao Blueprint client UI preferences");
            }
        } catch (IOException ignored) {
            // Retain the preference for this client run even if persistence is unavailable.
        }
    }
}
