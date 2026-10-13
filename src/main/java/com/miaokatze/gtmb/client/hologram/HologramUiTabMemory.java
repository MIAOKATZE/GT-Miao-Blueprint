package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;

/** Only the workspace tab persists; placement authority and blueprint identity remain server state. */
final class HologramUiTabMemory {

    private final File file;
    private Boolean blueprints;

    HologramUiTabMemory(File file) {
        this.file = file;
        if (!file.isFile()) return;
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file.toPath())) {
            values.load(input);
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

    void remember(boolean selected) {
        if (blueprints != null && blueprints == selected) return;
        blueprints = selected;
        Properties values = new Properties();
        values.setProperty("tab", selected ? "blueprints" : "structure");
        try {
            Files.createDirectories(
                file.toPath()
                    .getParent());
            try (OutputStream output = Files.newOutputStream(file.toPath())) {
                values.store(output, "GT Miao Blueprint workspace tab");
            }
        } catch (IOException ignored) {
            // Retain the preference for this client run even if persistence is unavailable.
        }
    }
}
