package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Small, client-only presentation preferences; no world, draft, library or inventory data. */
final class HologramEditorPreferences {

    private static HologramEditorPreferences instance;
    private final File file;
    private final Properties values = new Properties();

    static HologramEditorPreferences get(File directory) {
        if (instance == null)
            instance = new HologramEditorPreferences(new File(directory, "config/gtmb-editor-guide.properties"));
        return instance;
    }

    HologramEditorPreferences(File file) {
        this.file = file;
        if (file.isFile()) try (FileInputStream input = new FileInputStream(file)) {
            values.load(input);
        } catch (IOException ignored) { /* A damaged preference never prevents opening the editor. */ }
    }

    boolean collapsed() {
        return Boolean.parseBoolean(values.getProperty("guide.collapsed", "false"));
    }

    void collapsed(boolean value) {
        put("guide.collapsed", Boolean.toString(value));
    }

    boolean seen(String topic) {
        return values.containsKey("tutorial.v1." + topic);
    }

    String outcome(String topic) {
        return values.getProperty("tutorial.v1." + topic, "");
    }

    void finish(String topic, boolean completed) {
        put("tutorial.v1." + topic, completed ? "completed" : "skipped");
    }

    private void put(String key, String value) {
        if (value.equals(values.getProperty(key))) return;
        values.setProperty(key, value);
        try {
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) return;
            File pending = new File(parent, file.getName() + ".pending");
            try (FileOutputStream output = new FileOutputStream(pending)) {
                values.store(output, "GTMB editor presentation preferences");
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { /* Keep the current session usable on read-only installations. */ }
    }
}
