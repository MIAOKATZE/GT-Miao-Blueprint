// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.input.Keyboard;

import gregtech.common.blocks.ItemMachines;

/** Explicit, bounded GUI witness inside the existing lease-owned EBF native preview. */
final class HologramHatchPresetRuntimeWitness {

    static final String FLAG = "gtmb.testlab.hatchPresetRuntime";
    private final HologramScreen screen;
    private final Path output, file;
    private final byte[] original;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> controls = new ArrayList<>();
    private final Map<Integer, String> baseline = new LinkedHashMap<>();
    private final Set<Integer> inspected = new HashSet<>();
    private final String name = "runtime-" + System.getProperty("miaotest.host.runId")
        .substring(0, 8);
    private final int controllerId;
    private int phase, ticks, manualIndex = -1;
    private ItemStack manualStack;
    private int manualCandidate = -1;
    private NBTTagCompound saved;
    private boolean restored, basicSubmitted, tutorialExit;
    private String capture;
    private long since = System.nanoTime();

    static boolean enabled() {
        return Boolean.getBoolean(FLAG);
    }

    HologramHatchPresetRuntimeWitness(HologramScreen screen, Path output, Map<String, Object> parent,
        ItemStack controller) throws Exception {
        require(enabled() && Boolean.getBoolean("miaotest.host.environment"), "Exact host opt-in required");
        this.screen = screen;
        this.output = output;
        controllerId = controller.getItemDamage();
        file = screen.minecraft().mcDataDir.toPath()
            .resolve("config/gtmb-hologram-presets.nbt");
        original = Files.exists(file) ? Files.readAllBytes(file) : null;
        if (original != null) Files.write(output.resolve("hatch-presets-original.nbt"), original);
        parent.put("hatchPresetRuntime", report);
        report.put("schema", "gtmb-hatch-preset-runtime-v1");
        report.put(
            "input",
            "MUI nativeTarget press/release; selection and candidate scrolling use production view state");
        report.put("physicalConstruction", "NOT_RUN; production declaredSelection checked without submitting");
        report.put("controls", controls);
        report.put("presetFileOriginallyExisted", original != null);
        report.put("passed", false);
    }

    boolean step() throws Exception {
        require(System.nanoTime() - since < 20_000_000_000L, "HATCH_PRESET_PHASE_TIMEOUT: " + phase);
        require(screen.minecraft().currentScreen == screen, "Native EBF screen changed");
        report.put("tutorialActive", screen.ui.tutorial.active());
        report.put(
            "lastView",
            screen.ui.config.backgroundUiState()
                .toString());
        report.put("lastEditable", screen.ui.session.editable());
        report.put("lastEditingReason", screen.ui.session.editingReason());
        if (screen.ui.tutorial.active()) {
            require(
                "projection".equals(
                    screen.ui.tutorial.diagnosticState()
                        .getString("topic")),
                "Unexpected tutorial on native EBF");
            if (!tutorialExit) {
                tutorialExit = true;
                HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                report.put("projectionTutorialExit", "native Escape; no preference writes");
            }
            return false;
        }
        if (++ticks < 3 || capture != null) return false;
        var state = screen.ui.session.state();
        if (state.data.getBoolean("capturePending")) return false;
        switch (phase) {
            case 0 -> {
                require(!state.data.getBoolean("presetActive"), "Fresh EBF unexpectedly applied a preset");
                require(
                    !state.data.hasKey("hatchTier", 3) || state.data.getInteger("hatchTier") == -1,
                    "Fresh EBF falsely reported ULV");
                for (var cell : state.cells) baseline.put(cell.index, stack(cell.targetStack));
                report.put("freshPresetActive", false);
                report.put("freshHatchTierPresent", state.data.hasKey("hatchTier", 3));
                report
                    .put("freshHatchTier", state.data.hasKey("hatchTier", 3) ? state.data.getInteger("hatchTier") : -1);
                screen.ui.router.activate(94);
                next();
            }
            case 1 -> {
                if (!present("control.1700")) return false;
                require(
                    screen.ui.config.backgroundUiState()
                        .getInteger("presetTier") == -1,
                    "Fresh preset drawer falsely selected ULV");
                require(!screen.ui.config.basicPresetActionAvailable(), "Unset tier can generate a basic preset");
                report.put(
                    "unsetDrawer",
                    screen.ui.config.backgroundUiState()
                        .toString());
                capture = "unset";
                click("control.1710");
                next();
            }
            case 2 -> {
                if (!click("control.1700")) return false;
                next();
            }
            case 3 -> {
                require(
                    screen.ui.config.backgroundUiState()
                        .getInteger("presetTier") == 0,
                    "Explicit ULV row did not remain a valid tier");
                require(screen.ui.config.basicPresetActionAvailable(), "Explicit ULV cannot generate");
                report.put("explicitUlvSelectable", true);
                if (!click("control.1701")) return false;
                next();
            }
            case 4 -> {
                if (!basicSubmitted) {
                    require(
                        screen.ui.config.backgroundUiState()
                            .getInteger("presetTier") == 1,
                        "Native LV selection was not retained");
                    if (!click("control.106")) return false;
                    basicSubmitted = true;
                    ticks = 0;
                    return false;
                }
                if (!state.data.getBoolean("presetActive")) return false;
                require(
                    state.data.getBoolean("nativePresetExact") && state.data.getInteger("hatchTier") == 1,
                    "LV preset did not produce exact native targets");
                report.put("generatedConstruction", construction());
                require(
                    ((Number) ((Map<?, ?>) report.get("generatedConstruction")).get("hatches")).intValue() > 0,
                    "atLeast generated no construction hatch choices");
                capture = "generated";
                next();
            }
            case 5 -> {
                if (manualIndex < 0) {
                    for (var cell : state.cells) if ("preset".equals(cell.pinScope) && inspected.add(cell.index)) {
                        manualIndex = cell.index;
                        screen.ui.preview.selection()
                            .click(cell.index, false);
                        screen.ui.preview.selected(cell.index);
                        screen.ui.config.requestSelectedDetails();
                        screen.ui.router.activate(95);
                        ticks = 0;
                        return false;
                    }
                    throw new IllegalStateException("No exact generated hatch row offers a second native tier");
                }
                var cell = screen.ui.session.selectedCell();
                report.put("candidateExpectedIndex", manualIndex);
                report.put("candidateSelectedIndex", screen.ui.preview.selected());
                report.put(
                    "candidateSelection",
                    screen.ui.preview.selection()
                        .indices());
                require(cell != null && cell.index == manualIndex, "Selected native candidate row changed");
                if (manualCandidate < 0) {
                    var currentMachine = ItemMachines.getMetaTileEntity(cell.targetStack);
                    for (int i = 0; i < cell.candidates.size(); i++) {
                        var candidate = cell.candidates.get(i);
                        int[] tiers = cell.tag()
                            .getIntArray("candidateTiers");
                        if (i >= tiers.length || tiers[i] != 2
                            || ItemStack.areItemStacksEqual(candidate, cell.targetStack)) continue;
                        var alternative = ItemMachines.getMetaTileEntity(candidate);
                        if (alternative == null || currentMachine == null
                            || alternative.getClass() != currentMachine.getClass()) continue;
                        manualCandidate = i;
                        manualStack = candidate.copy();
                        break;
                    }
                    if (manualCandidate < 0) {
                        HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                        manualIndex = -1;
                        ticks = 0;
                        return false;
                    }
                    int visible = screen.ui.config.filteredHatches()
                        .indexOf(manualCandidate);
                    require(visible >= 0, "Exact second native tier was filtered from the real candidate pane");
                    screen.ui.config.hatchOffset(Math.max(0, visible - 2));
                    ticks = 0;
                    return false;
                }
                int row = screen.ui.config.filteredHatches()
                    .indexOf(manualCandidate) - screen.ui.config.hatchOffset();
                require(row >= 0 && row < 5, "Selected native candidate is outside the visible page");
                if (!click("config.candidate.row." + row)) return false;
                next();
            }
            case 6 -> {
                require(
                    "manual".equals(state.cells.get(manualIndex).pinScope)
                        && ItemStack.areItemStacksEqual(manualStack, state.cells.get(manualIndex).targetStack),
                    "Mixed manual production pin was not accepted");
                report.put("mixedManualIndex", manualIndex);
                report.put("mixedManualStack", stack(manualStack));
                if (screen.ui.shell.hasModalPanel()) {
                    HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                    ticks = 0;
                    return false;
                }
                screen.ui.preview.clearSelection();
                screen.ui.router.activate(94);
                next();
            }
            case 7 -> {
                if (!click("control.1711")) return false;
                next();
            }
            case 8 -> {
                if (!present("control.1712")) return false;
                screen.ui.config.nativePresetNameField()
                    .setText(name);
                if (!click("control.1712")) return false;
                next();
            }
            case 9 -> {
                saved = new HologramConfigurationPresets(file.toFile()).get(name);
                require(
                    saved != null && saved.getTagList("entries", 10)
                        .tagCount() > 1,
                    "Named mixed preset was not persisted with exact native entries");
                require(
                    saved.equals(screen.currentNativeHatchProfile(1, false)),
                    "Saved custom profile differs from current native plan");
                report.put("savedProfile", saved.toString());
                capture = "saved-custom";
                next();
            }
            case 10 -> {
                if (!click("control.107")) return false;
                next();
            }
            case 11 -> {
                require(!state.data.getBoolean("presetActive"), "Undo kept generated preset active");
                require(
                    "manual".equals(state.cells.get(manualIndex).pinScope)
                        && ItemStack.areItemStacksEqual(manualStack, state.cells.get(manualIndex).targetStack),
                    "Undo discarded the independent manual hatch");
                for (var cell : state.cells) if (cell.index != manualIndex) require(
                    baseline.get(cell.index)
                        .equals(stack(cell.targetStack)),
                    "Undo left a generated target");
                report.put("undoPreservedManual", true);
                report.put("undoConstruction", construction());
                screen.ui.router.activate(94);
                next();
            }
            case 12 -> {
                if (!click("control.1711")) return false;
                next();
            }
            case 13 -> {
                require(screen.ui.config.nativeSelectedPreset(), "Saved local preset no longer selectable");
                if (!click("control.106")) return false;
                next();
            }
            case 14 -> {
                if (!state.data.getBoolean("presetActive")) return false;
                require(
                    state.data.getBoolean("nativePresetExact"),
                    "Custom preset did not produce exact native choices");
                NBTTagCompound reapplied = screen.currentNativeHatchProfile(1, false);
                report.put("reappliedProfile", reapplied.toString());
                report.put("reappliedEntriesInOriginalOrder", saved.equals(reapplied));
                report.put("savedExactCounts", profileCounts(saved).toString());
                report.put("reappliedExactCounts", profileCounts(reapplied).toString());
                require(
                    "manual".equals(state.cells.get(manualIndex).pinScope)
                        && ItemStack.areItemStacksEqual(manualStack, state.cells.get(manualIndex).targetStack),
                    "Custom reapply moved or replaced the independent manual hatch");
                require(sameProfile(saved, reapplied), "Custom reapply lost mixed native counts or identity");
                report.put("reappliedExactCountsMatch", true);
                report.put("customConstruction", construction());
                capture = "custom-reapplied";
                screen.ui.router.activate(94);
                next();
            }
            case 15 -> {
                if (!click("control.107")) return false;
                next();
            }
            case 16 -> {
                screen.ui.preview.selection()
                    .click(manualIndex, false);
                screen.ui.preview.selected(manualIndex);
                var restore = new NBTTagCompound();
                restore.setInteger("selected", manualIndex);
                screen.ui.session.send("restoreCell", restore, true);
                next();
            }
            case 17 -> {
                screen.ui.preview.clearSelection();
                require(!state.data.getBoolean("presetActive"), "Preset remained after second undo");
                for (var cell : state.cells) require(
                    baseline.get(cell.index)
                        .equals(stack(cell.targetStack)),
                    "Preset witness failed to restore exact original targets");
                require(
                    ((Number) construction().get("hatches")).intValue() == 0,
                    "Construction retained cleared choices");
                screen.ui.router.activate(94);
                next();
            }
            case 18 -> {
                if (!click("control.1710")) return false;
                next();
            }
            case 19 -> {
                require(
                    screen.ui.config.backgroundUiState()
                        .getInteger("presetTier") == -1 && !screen.ui.config.basicPresetActionAvailable(),
                    "Reopened cleared drawer silently selected ULV");
                report.put(
                    "clearedDrawer",
                    screen.ui.config.backgroundUiState()
                        .toString());
                capture = "cleared-unset";
                next();
            }
            case 20 -> {
                HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                restore();
                report.put("targetsRestored", true);
                report.put("passed", true);
                next();
            }
            default -> {
                return true;
            }
        }
        return false;
    }

    void guiPost(GuiScreen gui, long frame) throws Exception {
        if (gui != screen || capture == null) return;
        var mc = Minecraft.getMinecraft();
        String filename = "gtmb-hatch-preset-" + capture + ".png";
        Files.createDirectories(output.resolve("screenshots"));
        ScreenShotHelper
            .saveScreenshot(output.toFile(), filename, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        require(
            Files.isRegularFile(
                output.resolve("screenshots")
                    .resolve(filename)),
            "Natural GUI screenshot missing");
        report.put(capture + "Frame", frame);
        report.put(capture + "Screenshot", "screenshots/" + filename);
        capture = null;
    }

    void restore() throws Exception {
        if (restored) return;
        if (original == null) Files.deleteIfExists(file);
        else Files.write(file, original);
        restored = true;
        report.put(
            "presetFileRestored",
            original == null ? !Files.exists(file) : Arrays.equals(original, Files.readAllBytes(file)));
    }

    private Map<String, Object> construction() {
        throw new UnsupportedOperationException(
            "RETIRED: legacy declaredSelection witness cannot validate the native target action engine; use fourMachineRuntime");
    }

    private boolean present(String key) {
        return screen.ui.shell.targetBounds(key) != null;
    }

    private boolean click(String key) throws Exception {
        if (!present(key)) return false;
        var hit = new LinkedHashMap<String, Object>();
        hit.put("phase", phase);
        hit.put("target", key);
        controls.add(hit);
        HologramMuiProbeInput.nativeTarget(screen, key, hit);
        return true;
    }

    private void next() {
        phase++;
        ticks = 0;
        since = System.nanoTime();
        report.put("phase", phase);
    }

    private static String stack(ItemStack stack) {
        return stack == null ? "null"
            : stack.writeToNBT(new NBTTagCompound())
                .toString();
    }

    private static boolean sameProfile(NBTTagCompound a, NBTTagCompound b) {
        NBTTagCompound aMeta = (NBTTagCompound) a.copy(), bMeta = (NBTTagCompound) b.copy();
        aMeta.removeTag("entries");
        bMeta.removeTag("entries");
        return aMeta.equals(bMeta) && profileCounts(a).equals(profileCounts(b));
    }

    private static Map<NBTTagCompound, Integer> profileCounts(NBTTagCompound profile) {
        var result = new LinkedHashMap<NBTTagCompound, Integer>();
        var entries = profile.getTagList("entries", 10);
        require(entries.tagCount() <= 128, "Named profile exceeded its entry bound");
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound exact = (NBTTagCompound) entries.getCompoundTagAt(i)
                .copy();
            int count = exact.getInteger("count");
            require(count > 0 && count <= 512, "Named profile has invalid exact count");
            exact.removeTag("count");
            result.merge(exact, count, Math::addExact);
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
