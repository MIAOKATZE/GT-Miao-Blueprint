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
        } catch (Exception failure) {
            throw new IllegalStateException("Workspace tab persistence regression failed", failure);
        }
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
