// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;

import gregtech.api.interfaces.IHeatingCoil;

/** Native supported named-channel form, independent of the optional material suggestions. */
final class HologramRuntimeCoilControls {

    private final HologramScreen screen;
    private final Path output;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final long started = System.nanoTime();
    private int phase, ticks;
    private boolean capture;

    HologramRuntimeCoilControls(HologramScreen screen, Path output, Map<String, Object> parent) {
        if (!HologramHatchPresetRuntimeWitness.enabled())
            throw new IllegalStateException("Explicit hatch profile required");
        this.screen = screen;
        this.output = output;
        parent.put("runtimeCoilControls", report);
        report.put(
            "initialCapability",
            screen.ui.config.capability("coil")
                .toString());
        report.put("input", "Production named-channel form; field input followed by native MUI control 67");
    }

    boolean step() throws Exception {
        if (System.nanoTime() - started > 15_000_000_000L)
            throw new IllegalStateException("Native coil form timeout: " + phase);
        if (++ticks < 3 || capture) return false;
        switch (phase) {
            case 0 -> {
                var state = screen.ui.session.state();
                for (var cell : state.cells)
                    if (!cell.anchor && Block.getBlockFromName(cell.wantId) instanceof IHeatingCoil) {
                        screen.ui.preview.selection()
                            .click(cell.index, false);
                        screen.ui.preview.selected(cell.index);
                        screen.ui.config.requestSelectedDetails();
                        report.put("selectedCoilIndex", cell.index);
                        phase++;
                        ticks = 0;
                        return false;
                    }
                throw new IllegalStateException("Actual EBF coil row unavailable");
            }
            case 1 -> {
                report.put(
                    "selectedCapability",
                    screen.ui.config.capability("coil")
                        .toString());
                screen.ui.router.activate(60);
                phase++;
                ticks = 0;
                return false;
            }
            case 2 -> {
                if (screen.ui.shell.targetBounds("control.67") == null) return false;
                screen.ui.preview.clearSelection();
                screen.ui.config.channelNameField()
                    .setText("coil");
                screen.ui.config.channelValueField()
                    .setText("2");
                capture = true;
                phase++;
                ticks = 0;
                return false;
            }
            case 3 -> {
                var input = new LinkedHashMap<String, Object>();
                report.put("nativeControl", input);
                HologramMuiProbeInput.nativeTarget(screen, "control.67", input);
                report.put("submittedChannel", "coil");
                report.put("submittedValue", 2);
                phase++;
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    void guiPost(GuiScreen gui, long frame) throws Exception {
        if (gui != screen || !capture) return;
        var mc = screen.minecraft();
        Files.createDirectories(output.resolve("screenshots"));
        String name = "gtmb-runtime-coil-channel-form.png";
        ScreenShotHelper.saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        if (!Files.isRegularFile(
            output.resolve("screenshots")
                .resolve(name)))
            throw new IllegalStateException("Native coil form frame missing");
        report.put("formFrame", frame);
        report.put("formScreenshot", "screenshots/" + name);
        capture = false;
    }
}
