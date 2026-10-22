package com.miaokatze.gtmb.client.hologram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises saved user bindings without initializing a window or reading native keyboard state. */
public final class HologramKeyBindingsRegression {

    private HologramKeyBindingsRegression() {}

    public static void run() {
        HologramEditorCommands commands = new HologramEditorCommands(null);
        SimpleAssert.eq(
            "clearWorkspace",
            commands.match(Keyboard.KEY_DELETE, 3),
            "Ctrl Shift Delete clears the current workspace");
        SimpleAssert.that(
            !"clearWorkspace".equals(commands.match(Keyboard.KEY_DELETE, 0)),
            "plain Delete retains its cell edit meaning");
        SimpleAssert
            .that(!commands.bind(commands.index("clearWorkspace"), Keyboard.KEY_E, 0), "E remains a return key");
        Path directory = null;
        try {
            directory = Files.createTempDirectory("gtmb-hologram-bindings-");
            Path config = directory.resolve("keys.properties");
            HologramKeyBindings bindings = new HologramKeyBindings();
            bindings.load(config.toFile());
            SimpleAssert.eq(1, bindings.match(Keyboard.KEY_C, 1), "Ctrl C copies by default");
            SimpleAssert.eq(-1, bindings.match(Keyboard.KEY_C, 0), "plain C cannot copy accidentally");
            SimpleAssert.eq(3, bindings.match(Keyboard.KEY_DELETE, 0), "Delete restores casing");
            SimpleAssert.eq(4, bindings.match(Keyboard.KEY_R, 0), "R cycles legal block faces");
            SimpleAssert.eq(-1, bindings.match(Keyboard.KEY_R, 1), "Ctrl R has no default planner action");
            SimpleAssert.that(!bindings.bind(0, Keyboard.KEY_C, 1), "duplicate shortcut rejected");
            SimpleAssert.that(!bindings.bind(0, Keyboard.KEY_ESCAPE, 0), "Esc stays available to close dialogs");
            SimpleAssert.that(!bindings.bind(0, Keyboard.KEY_1, 0), "mode key stays reserved");
            SimpleAssert.that(!bindings.bind(0, Keyboard.KEY_T, 0), "held T exact-type key stays reserved");
            SimpleAssert.that(!bindings.bind(0, Keyboard.KEY_T, 1), "Ctrl T cannot overlap additive type selection");
            SimpleAssert.that(!bindings.bind(0, Keyboard.KEY_G, 0), "legal movement handle toggle stays reserved");
            SimpleAssert.that(!bindings.bind(0, Keyboard.KEY_A, 1), "Ctrl A remains the whole-selection shortcut");
            SimpleAssert.that(!bindings.bind(0, Keyboard.KEY_R, 8), "unknown modifier bit rejected");
            SimpleAssert.that(bindings.bind(0, Keyboard.KEY_K, 3), "Ctrl Shift K can replace cut shortcut");
            SimpleAssert.that(bindings.save(), "user bindings persisted");
            HologramKeyBindings restored = new HologramKeyBindings();
            restored.load(config.toFile());
            SimpleAssert.eq(0, restored.match(Keyboard.KEY_K, 3), "custom modifiers restored");
            SimpleAssert.eq(-1, restored.match(Keyboard.KEY_X, 1), "old shortcut no longer active");
            Files.write(config, "editCut=46:1\neditCopy=46:1\n".getBytes(StandardCharsets.UTF_8));
            restored.load(config.toFile());
            SimpleAssert.eq(0, restored.match(Keyboard.KEY_X, 1), "invalid conflicting file falls back atomically");
            SimpleAssert.eq(1, restored.match(Keyboard.KEY_C, 1), "all defaults restored after invalid file");
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            if (directory != null) try {
                Files.deleteIfExists(directory.resolve("keys.properties"));
                Files.deleteIfExists(directory);
            } catch (java.io.IOException cleanupFailure) {
                throw new AssertionError(cleanupFailure);
            }
        }
    }
}
