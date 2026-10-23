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

    private static final String LESSON_PREFIX = "tutorial.v2.";
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

    boolean selectionDiscardSuppressed() {
        return Boolean.parseBoolean(values.getProperty("selection.discard.suppressed", "false"));
    }

    void suppressSelectionDiscard() {
        put("selection.discard.suppressed", "true");
    }

    boolean seen(String topic) {
        return values.containsKey(LESSON_PREFIX + topic);
    }

    String outcome(String topic) {
        return values.getProperty(LESSON_PREFIX + topic, "");
    }

    void finish(String topic, boolean completed) {
        put(LESSON_PREFIX + topic, completed ? "completed" : "skipped");
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
