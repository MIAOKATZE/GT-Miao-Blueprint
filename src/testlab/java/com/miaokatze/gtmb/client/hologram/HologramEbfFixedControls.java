// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramEbfNativeCandidateContextWitness;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.interfaces.IHeatingCoil;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchMuffler;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Bounded preview-only stock EBF test. All active edits go through real MUI controls. */
final class HologramEbfFixedControls {

    static final String FLAG = "gtmb.testlab.localPreviewStagesEbfFixedNative";
    static final int BASE_OUTPUT = 60, OTHER_OUTPUT = 61;
    private static final long LIMIT = 9_000_000_000L;
    private static final int[] TARGET = { 1, 3, 0 };

    interface Oracle {

        Map<String, String> compare(HologramState state, Map<String, Object> receipt) throws Exception;
    }

    private enum Phase {
        BASE, SELECT_PIN, OPEN_CANDIDATES, PIN, WAIT_PIN, SELECT_FACING, FACING, WAIT_FACING,
        SELECT_AUTO, AUTO, WAIT_AUTO, SELECT_RESTORE, RESTORE, WAIT_RESTORE,
        CHANNELS, CHOOSE_COIL, CHOOSE_VALUE, WAIT_COIL, DONE
    }

    private final HologramScreen screen;
    private final ItemStack controller;
    private final BaseMetaTileEntity tile;
    private final EntityPlayer actor;
    private final Object world, connection;
    private final int ax, ay, az;
    private final String session;
    private final Oracle oracle;
    private final Map<String, Object> report;
    private final NBTTagList stock;
    private final int slot;
    private final Map<String, Object> controls = new LinkedHashMap<>();
    private final List<Map<String, Object>> acknowledgements = new ArrayList<>();
    private Phase phase;
    private long since, minSerial, pressed, preSerial, naturalSerial;
    private Object preState;
    private HologramState naturalState, initial, editBefore;
    private long preFrame = -1, postFrame = -1;
    private MiaoVisonLiteRenderer.MaterialPresentation postMaterial;
    private NBTTagCompound baseChannels;
    private Map<String, String> firstFootprint;
    private Map<String, HeatingCoilLevel> firstCoils;
    private List<List<Object>> worldBaseline;
    private boolean tutorialExit, closeSent, capturePending, dockOpened;
    private int wantedFacing = -1;
    private ItemStack offeredAlternate;

    HologramEbfFixedControls(Minecraft mc, HologramScreen screen, int x, int y, int z, ItemStack controller,
        Map<String, Object> report, Oracle oracle, NBTTagList stock, int slot) {
        this.screen = screen;
        this.controller = controller;
        this.report = report;
        this.oracle = oracle;
        this.stock = stock;
        this.slot = slot;
        ax = x;
        ay = y;
        az = z;
        actor = mc.thePlayer;
        world = mc.theWorld;
        connection = mc.getNetHandler();
        tile = (BaseMetaTileEntity) mc.theWorld.getTileEntity(x, y, z);
        session = state().session;
        report.put("scope", testScope());
        if (!coilOnly) report.put("targetRelative", TARGET.clone());
        report.put("phaseLimitNanos", LIMIT);
        report.put("controls", controls);
        if (!coilOnly) report.put("acknowledgements", acknowledgements);
        step(Phase.BASE);
    }

    void guiPre(GuiScreen gui, long frame) {
        if (gui != screen || screen.ui.tutorial.active() || screen.ui.shell.hasModalPanel()) return;
        preFrame = frame;
        preState = state();
        preSerial = materialSerial();
    }

    void guiPost(GuiScreen gui, long frame) {
        if (gui != screen || preFrame != frame || preState != state() || screen.ui.tutorial.active()
            || screen.ui.shell.hasModalPanel()) return;
        var material = HologramRenderer.editorMaterialPresentation();
        if (material == null || material.state() != state() || material.serial() <= preSerial
            || !material.complete() || !material.bodyMaterial() || material.submittedCells() != 34
            || !HologramRenderer.previewMeshReady(state()) || HologramRenderer.previewDisplayState() != state()
            || !(screen.ui.shell.scene() instanceof HologramEditorSceneWidget edit) || !edit.editFrameReady()) return;
        notePickDraw(edit, material, frame);
        // This records a real Draw.Post; RenderTick.END below independently closes the fence.
    }

    void renderEnd(Minecraft mc, long frame) {
        if (mc.currentScreen == screen && postFrame == frame - 1 && postMaterial != null
            && postMaterial.state() == state() && !screen.ui.shell.hasModalPanel() && !screen.ui.tutorial.active()) {
            acceptPickRenderEnd();
            // Keep native body/ACK witnesses separate from viewport layout eligibility.
        }
    }

    boolean tick(Minecraft mc) throws Exception {
        own(mc);
        require(System.nanoTime() - since < LIMIT, "EBF_PHASE_TIMEOUT: " + phase);
        var now = state();
        if (worldBaseline != null) require(worldBaseline.equals(worldSample(mc, initial)), "EBF footprint world changed");
        switch (phase) {
            case BASE -> {
                if (screen.ui.tutorial.active()) {
                    require("projection".equals(screen.ui.tutorial.diagnosticState().getString("topic")),
                        "Unexpected tutorial on native projection");
                    if (!tutorialExit) {
                        tutorialExit = true;
                        HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                        minSerial = materialSerial();
                        report.put("tutorial", "native Escape, no persistent completion");
                    }
                    return false;
                }
                if (!fresh() || !ready()) return false;
                initial = now;
                require(now.data.getLong("planRevision") == 0 && coil() == 1, "Expected fresh effective coil=1");
                baseChannels = (NBTTagCompound) now.data.getCompoundTag("channels").copy();
                worldBaseline = worldSample(mc, now);
                var first = new LinkedHashMap<String, Object>();
                report.put("coil1", first);
                firstFootprint = oracle.compare(now, first);
                firstCoils = coils(now);
                require(firstFootprint.size() == 33 && validCoils(firstCoils, HeatingCoilLevel.LV),
                    "Stock EBF must have 33 native rows and exactly 16 Cupronickel coordinates");
                first.put("nativeBodySerial", naturalSerial);
                if (coilOnly) startCoilOnly(first);
                else startSelection();
            }
            case SELECT_PIN -> { if (select("selectPin")) step(Phase.OPEN_CANDIDATES); }
            case OPEN_CANDIDATES -> {
                if (clickWhenPresent("control.95", "candidateControl")) step(Phase.PIN);
            }
            case PIN -> {
                require(singleton(), "Candidate pane lost the selected top coordinate");
                int row = candidateRow(OTHER_OUTPUT);
                if (row < 0 || screen.ui.shell.targetBounds("config.candidate.row." + row) == null) return false;
                require(canClick("config.candidate.row." + candidateRow(BASE_OUTPUT))
                    && canClick("config.candidate.row." + candidateRow(-1)),
                    "Actual visible candidate page lacks the distinct native defaults");
                offeredAlternate = target(now).candidates.get(2).copy();
                press("config.candidate.row." + row, "pin61");
                step(Phase.WAIT_PIN);
            }
            case WAIT_PIN -> {
                if (revisionDelta()) {
                    if (screen.ui.shell.hasModalPanel()) {
                        if (!closeSent) {
                            closeSent = true;
                            HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                        }
                        return false;
                    }
                    if (fresh()) {
                        var cell = target(now);
                        require(ItemStack.areItemStacksEqual(cell.targetStack, offeredAlternate)
                            && device(cell.targetStack, OTHER_OUTPUT) && "manual".equals(cell.pinScope) && cell.chosenChoice == 2
                            && !cell.tag().getBoolean("hasFacingPin") && cell.targetFacing == -1
                            && cell.tag().getIntArray("hatchFacings").length > 0,
                            "PIN: no exact manually selected alternate hatch with legal auto-facing state");
                        ack("pin61", cell);
                        step(Phase.SELECT_FACING);
                    }
                }
            }
            case SELECT_FACING -> { if (select("reselectAfterPin")) step(Phase.FACING); }
            case FACING -> {
                require(singleton(), "Facing lost true singleton selection");
                wantedFacing = nextFacing(target(now).tag().getIntArray("hatchFacings"), target(now).hatchFacing);
                require(wantedFacing >= 0, "No lawful candidate facing");
                if (canClick("control.99")) {
                    press("control.99", "facing");
                    step(Phase.WAIT_FACING);
                }
            }
            case WAIT_FACING -> {
                if (revisionDelta() && fresh()) {
                    var cell = target(now);
                    require(sameManualHatch(cell, target(editBefore)) && cell.targetFacing == wantedFacing
                        && cell.hatchFacing == wantedFacing && cell.tag().getBoolean("hasFacingPin"),
                        "Facing did not publish its exact next legal orientation");
                    ack("facing", cell);
                    step(Phase.SELECT_AUTO);
                }
            }
            case SELECT_AUTO -> { if (select("reselectAfterFacing")) step(Phase.AUTO); }
            case AUTO -> {
                require(singleton(), "Auto facing lost true singleton selection");
                if (canClick("control.102")) {
                    press("control.102", "automaticFacing");
                    step(Phase.WAIT_AUTO);
                }
            }
            case WAIT_AUTO -> {
                if (revisionDelta() && fresh()) {
                    var cell = target(now);
                    require(sameManualHatch(cell, target(editBefore)) && cell.targetFacing == -1 && cell.hatchFacing == -1
                        && !cell.tag().getBoolean("hasFacingPin") && !cell.tag().hasKey("targetDisplay"),
                        "Auto facing did not just clear the manual orientation");
                    ack("automaticFacing", cell);
                    step(Phase.SELECT_RESTORE);
                }
            }
            case SELECT_RESTORE -> { if (select("reselectForRestore")) step(Phase.RESTORE); }
            case RESTORE -> {
                require(singleton(), "Restore lost true singleton selection");
                if (canClick("control.66")) {
                    press("control.66", "restoreNative");
                    step(Phase.WAIT_RESTORE);
                }
            }
            case WAIT_RESTORE -> {
                if (revisionDelta() && fresh()) {
                    require(targetTag(target(now)).equals(targetTag(target(initial))),
                        "Restore did not recover the exact native target");
                    ack("restoreNative", target(now));
                    step(Phase.CHANNELS);
                }
            }
            case CHANNELS -> {
                if (openChannels()) step(Phase.CHOOSE_COIL);
            }
            case CHOOSE_COIL -> {
                int index = capabilitySlot();
                if (index >= 0 && clickWhenPresent("config.capability.row." + index, "chooseCoil"))
                    step(Phase.CHOOSE_VALUE);
            }
            case CHOOSE_VALUE -> {
                if (!"coil".equals(screen.ui.config.optionChannel())) return false;
                int index = optionSlot(2);
                if (index >= 0 && canClick("config.option.row." + index)) {
                    pressCoilOption(index);
                    step(Phase.WAIT_COIL);
                }
            }
            case WAIT_COIL -> {
                if (now.data.getBoolean("capturePending")) {
                    capturePending = observeCoilAdmission(now);
                    return false;
                }
                if (!exposeCoilPreview(now) || !fresh() || !ready() || coil() != 2 || now == editBefore) return false;
                require(capturePending, "Did not witness the exact owned recapture admission");
                require(!now.data.getString("generation").equals(initial.data.getString("generation")),
                    "Coil 2 retained the first geometry generation");
                if (screen.ui.shell.hasModalPanel()) return false;
                NBTTagCompound expected = (NBTTagCompound) baseChannels.copy();
                expected.setInteger("coil", 2);
                require(expected.equals(now.data.getCompoundTag("channels")), "Unrelated channel changed");
                var second = new LinkedHashMap<String, Object>();
                report.put("coil2", second);
                Map<String, String> footprint = oracle.compare(now, second);
                require(exactCoilDelta(firstFootprint, footprint, firstCoils, coils(now)),
                    "Coil change did not alter exactly the same 16 native coordinates and nothing else");
                second.put("nativeBodySerial", naturalSerial);
                second.put("coilChangedCoordinates", new ArrayList<>(firstCoils.keySet()));
                second.put("completeNanos", System.nanoTime() - pressed);
                if (coilOnly) completeCoilOnly(second); else report.put("automaticFacingWasPlacement", false);
                report.put("targetInventoryAndFootprintUnchanged", true);
                step(Phase.DONE);
            }
            case DONE -> {}
        }
        return phase == Phase.DONE;
    }

    private void baselineOffers() throws Exception {
        var state = state();
        var cell = target(state);
        require(device(cell.targetStack, BASE_OUTPUT) && cell.tag().getInteger("nativeCandidateCount") == 1
            && cell.candidates.size() > 2 && casing(cell.candidates.get(0))
            && device(cell.candidates.get(1), BASE_OUTPUT) && device(cell.candidates.get(2), OTHER_OUTPUT),
            "Expected settled target 60 and separate direct native casing/60/61 offers");
        int mufflers = 0, anchors = 0;
        for (var row : state.cells) if (row.anchor || row.targetStack != null
            && row.targetStack.getItem() instanceof ItemMachines
            && ItemMachines.getMetaTileEntity(row.targetStack) instanceof MTEHatchMuffler) {
            if (row.anchor) {
                require(row.dx == 0 && row.dy == 0 && row.dz == 0
                    && ItemStack.areItemStacksEqual(controller, row.targetStack), "Controller anchor changed");
                anchors++;
            } else {
                require(row.dy == 3, "Muffler isn't in the native roof plane");
                mufflers++;
            }
            for (ItemStack offer : row.candidates)
                require(!device(offer, BASE_OUTPUT) && !device(offer, OTHER_OUTPUT), "Output offered on muffler/controller");
        }
        require(mufflers == 1 && anchors == 1, "Missing distinct muffler/controller controls");
        var proof = HologramEbfNativeCandidateContextWitness.measure(controller, tile.getBlockMetadata(),
            ExtendedFacing.NORTH_NORMAL_NONE, state.data, cell.dx, cell.dy, cell.dz, cell.targetStack, cell.candidates);
        report.put("independentHatchContext", proof);
        require(Boolean.TRUE.equals(proof.get("demonstrated"))
            && Integer.valueOf(0).equals(proof.get("displayedCasingIndex"))
            && Integer.valueOf(1).equals(proof.get("displayedFreshNativeIndex"))
            && Integer.valueOf(2).equals(proof.get("displayedFreshAlternateIndex")),
            "Read-only StructureLib predicate did not confirm exact top hatch/casing controls");
    }

    private static final class PickLayout {
        final HologramState state;
        final IWidget scene;
        final int[] bounds;
        final long serial;

        PickLayout(HologramState state, IWidget scene, int[] bounds, long serial) {
            this.state = state;
            this.scene = scene;
            this.bounds = bounds;
            this.serial = serial;
        }
    }

    private PickLayout dockInput, drawPostLayout, renderedLayout;
    private int[] drawPostInspectorClip;
    private boolean firstHatchDockFrame;
    private final Map<String, Map<String, Object>> pickReadiness = new LinkedHashMap<>();

    private void notePickDraw(HologramEditorSceneWidget scene, MiaoVisonLiteRenderer.MaterialPresentation material, long frame) {
        postFrame = frame;
        postMaterial = material;
        drawPostLayout = new PickLayout(material.state(), scene, screen.ui.shell.nativeWidgetBounds(scene), material.serial());
        if (firstHatchDockFramePending()) drawPostInspectorClip = screen.ui.shell.nativeInspectorBounds();
        if (coilOnly && phase == Phase.CHANNELS && dockInput != null && screen.ui.shell.dockExpanded()
            && screen.ui.shell.dockPage() == 3 && screen.ui.shell.propertiesGroupExpanded("source")) {
            coilStructureDrawSerial = material.serial();
            coilStructureDrawControlClip = screen.ui.shell.targetBounds("control.60");
        }
    }

    private void acceptPickRenderEnd() {
        naturalState = postMaterial.state();
        naturalSerial = postMaterial.serial();
        naturalMaterialCells = postMaterial.submittedCells();
        var scene = screen.ui.shell.scene();
        var post = drawPostLayout;
        renderedLayout = post != null && post.state == naturalState && post.serial == naturalSerial
            && post.scene == scene && Arrays.equals(post.bounds, screen.ui.shell.nativeWidgetBounds(scene))
                ? post : null;
        if (firstHatchDockFramePending() && renderedLayout != null && post.state == dockInput.state
            && post.scene == dockInput.scene && post.serial > dockInput.serial
            && !Arrays.equals(post.bounds, dockInput.bounds)) {
            var frame = new LinkedHashMap<String, Object>();
            int[] inspectorAtEnd = screen.ui.shell.nativeInspectorBounds();
            frame.put("beforeDockSerial", dockInput.serial);
            frame.put("naturalSerial", post.serial);
            frame.put("bodyCells", naturalMaterialCells);
            frame.put("sceneClip", post.bounds);
            frame.put("inspectorDrawPostClip", drawPostInspectorClip);
            frame.put("inspectorRenderEndClip", inspectorAtEnd);
            frame.put("disjoint", Arrays.equals(drawPostInspectorClip, inspectorAtEnd)
                && HologramEbfHatchClickLedger.separate(post.bounds, drawPostInspectorClip));
            report.put("firstHatchDockFrame", frame);
            firstHatchDockFrame = true;
        }
    }

    private boolean firstHatchDockFramePending() {
        return !coilOnly && Boolean.getBoolean(HologramEbfHatchClickLedger.FLAG)
            && !firstHatchDockFrame && phase == Phase.SELECT_PIN && dockInput != null
            && screen.ui.shell.dockExpanded() && screen.ui.shell.dockPage() == 0;
    }

    private void openSelectionDock(String label) throws Exception {
        String key = "selectPin".equals(label) ? "selectionDock" : label + "SelectionDock";
        var hit = new LinkedHashMap<String, Object>();
        require(controls.putIfAbsent(key, hit) == null, "Refusing repeat selection-dock click");
        HologramMuiProbeInput.nativeTarget(screen, "launcher.0", hit, () -> {
            var scene = screen.ui.shell.scene();
            require(scene instanceof HologramEditorSceneWidget, "Selection dock does not own native scene");
            int[] bounds = screen.ui.shell.nativeWidgetBounds(scene);
            require(safePickBounds(bounds), "Selection dock had no owned viewport before input");
            dockInput = new PickLayout(state(), scene, bounds, materialSerial());
            hit.put("lastMaterialSerialBeforePress", dockInput.serial);
            hit.put("beforeViewportBounds", bounds);
            hit.put("beforePlanRevision", dockInput.state.data.getLong("planRevision"));
        });
        dockOpened = true;
    }

    private boolean select(String label) throws Exception {
        var baseline = state();
        var cell = target(baseline);
        var scene = screen.ui.shell.scene();
        int[] bounds = scene == null ? null : screen.ui.shell.nativeWidgetBounds(scene);
        boolean dock = screen.ui.shell.dockExpanded() && screen.ui.shell.dockPage() == 0;
        boolean modal = screen.ui.shell.hasModalPanel();
        boolean widget = scene instanceof HologramEditorSceneWidget;
        boolean editFrame = widget && ((HologramEditorSceneWidget) scene).editFrameReady();
        boolean interactive = screen.ui.preview.previewInteractive();
        boolean navigable = screen.ui.shell.sceneNavigable();
        boolean control = screen.ui.preview.controlSelectionHeld(), air = screen.ui.preview.airSelectionHeld(),
            sameType = screen.ui.preview.sameTypeSelectionHeld();
        boolean leftShift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT), rightShift = Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        int selected = screen.ui.preview.selected();
        int[] selectedIndices = screen.ui.preview.selection().indices();
        boolean exactSelection = canSelectTarget(selected, selectedIndices, cell.index);
        var observed = renderedLayout;
        long afterSerial = dockInput == null ? minSerial : Math.max(minSerial, dockInput.serial);
        boolean dockOwner = dockInput == null || dockInput.state == baseline && dockInput.scene == scene;
        boolean layout = observed != null && pickFrameMatches(afterSerial, observed.serial,
            baseline, observed.state, scene, observed.scene, bounds, observed.bounds);
        var evidence = new LinkedHashMap<String, Object>();
        if (pickReadiness.isEmpty()) report.put("pickerReadiness", pickReadiness);
        pickReadiness.put(label, evidence);
        evidence.put("dockExpandedOnSelectionPage", dock);
        evidence.put("noModal", !modal);
        evidence.put("expectedCellIndex", cell.index);
        evidence.put("targetCoordinate", new int[] { cell.x, cell.y, cell.z });
        evidence.put("planRevision", baseline.data.getLong("planRevision"));
        evidence.put("sameDockOwner", dockOwner);
        evidence.put("freshBodyForState", fresh());
        evidence.put("currentNativeSceneWidget", widget);
        evidence.put("currentClip", bounds);
        evidence.put("renderEndClip", observed == null ? null : observed.bounds);
        evidence.put("requiredGreaterThanSerial", afterSerial);
        evidence.put("naturalSerial", naturalSerial);
        evidence.put("exactFreshRenderedLayout", layout);
        evidence.put("editFrameReady", editFrame);
        evidence.put("previewInteractive", interactive);
        evidence.put("sceneNavigable", navigable);
        evidence.put("controlHeld", control);
        evidence.put("airHeld", air);
        evidence.put("sameTypeHeld", sameType);
        evidence.put("leftShift", leftShift);
        evidence.put("rightShift", rightShift);
        evidence.put("selectedBefore", selected);
        evidence.put("selectedCountBefore", selectedIndices.length);
        evidence.put("selectedExactOrEmpty", exactSelection);
        evidence.put("pickerStarted", false);
        require(!control && !air && !sameType && !leftShift && !rightShift, "Unexpected native pick modifier");
        require(dockOwner, "Selection dock changed the owned scene/controller state");
        if (!fresh() || modal || !widget) return false;
        if (!dock) {
            if (!dockOpened && canClick("launcher.0")) openSelectionDock(label);
            return false;
        }
        if (!layout || !editFrame || !interactive || !navigable) return false;
        require(exactSelection, "Unrelated selection prevents exact native singleton reselect");
        evidence.put("pickerStarted", true);
        int bestX = -1, bestY = -1, exactTargetSamples = 0, excludedOutlineSamples = 0, hoverMisses = 0;
        double best = Double.POSITIVE_INFINITY;
        double[] projected = HologramRenderer.project(cell.dx, cell.dy, cell.dz);
        // Deterministic native picker: sample its present geometry and prefer the actual target's interior center.
        for (int y = bounds[1] + 2; y < bounds[1] + bounds[3] - 1; y += 3)
            for (int x = bounds[0] + 2; x < bounds[0] + bounds[2] - 1; x += 3) {
                int[] local = screen.ui.shell.sceneLocalPoint(x, y);
                if (local == null) continue;
                double dist = Math.abs(local[0] - projected[0]) + Math.abs(local[1] - projected[1]);
                if (dist >= best) continue;
                if (!HologramRenderer.pickStructure(local[0], local[1]).isEmpty()) {
                    excludedOutlineSamples++;
                    continue;
                }
                if (HologramRenderer.pick(local[0], local[1]) != cell.index) continue;
                exactTargetSamples++;
                if (!hover(scene, x, y)) {
                    hoverMisses++;
                    continue;
                }
                best = dist;
                bestX = x;
                bestY = y;
            }
        evidence.put("pickerExactTargetSamples", exactTargetSamples);
        evidence.put("pickerExcludedOutlineSamples", excludedOutlineSamples);
        evidence.put("pickerTargetHoverMisses", hoverMisses);
        boolean hovering = bestX >= 0 && hover(scene, bestX, bestY);
        evidence.put("nativeHoverOnScene", hovering);
        require(hovering, "Exact stock top cell was not normally pickable");
        int[] verified = screen.ui.shell.sceneLocalPoint(bestX, bestY);
        require(baseline == state() && scene == screen.ui.shell.scene()
            && Arrays.equals(bounds, screen.ui.shell.nativeWidgetBounds(scene))
            && ((HologramEditorSceneWidget) scene).editFrameReady() && screen.ui.preview.previewInteractive()
            && verified != null && HologramRenderer.pick(verified[0], verified[1]) == cell.index
            && HologramRenderer.pickStructure(verified[0], verified[1]).isEmpty(), "Native pick changed before press");
        int selectionBefore = screen.ui.preview.selection().version();
        var mui = screen.getScreen();
        var ledger = HologramEbfHatchClickLedger.enabled(
            Boolean.getBoolean(HologramEbfHatchClickLedger.FLAG), coilOnly, label)
                ? new HologramEbfHatchClickLedger(screen, baseline, scene, evidence, bounds, verified, () -> {
                    var mc = screen.minecraft();
                    return mc.thePlayer == actor && mc.theWorld == world && mc.getNetHandler() == connection
                        && actor.inventory.currentItem == slot;
                }) : null;
        boolean pre = mui.onMouseInputPre(0, true), handled = false;
        try {
            if (!pre) handled = mui.onMousePressed(0);
            if (ledger != null) {
                ledger.result("press", pre, pre ? null : Boolean.valueOf(handled));
                ledger.sample("afterPressBeforeRelease");
            }
        } finally {
            boolean releasePre = mui.onMouseInputPre(0, false);
            Boolean releaseHandled = releasePre ? null : Boolean.valueOf(mui.onMouseRelease(0));
            if (ledger != null) {
                ledger.result("release", releasePre, releaseHandled);
                ledger.sample("afterRelease");
            }
        }
        int selectionAfter = screen.ui.preview.selection().version();
        evidence.put("selectionVersionBeforePress", selectionBefore);
        evidence.put("selectionVersionAfterRelease", selectionAfter);
        controls.put(label, Arrays.asList(bestX, bestY, cell.index, naturalSerial));
        require(!pre && handled && baseline == state() && singleton() && selectionAfter > selectionBefore,
            "Native reselect did not advance exact singleton selection");
        return true;
    }

    static boolean pickFrameMatches(long minimum, long serial, Object state, Object drawnState, Object scene, Object drawnScene,
        int[] bounds, int[] drawnBounds) {
        return serial > minimum && state != null && state == drawnState && scene != null && scene == drawnScene
            && safePickBounds(bounds) && Arrays.equals(bounds, drawnBounds);
    }

    static boolean canSelectTarget(int selected, int[] selectedIndices, int target) {
        return target >= 0 && (selected == -1 && selectedIndices.length == 0
            || selected == target && selectedIndices.length == 1 && selectedIndices[0] == target);
    }

    private static boolean safePickBounds(int[] bounds) {
        return bounds != null && bounds.length == 4 && bounds[2] > 0 && bounds[3] > 0
            && (long) bounds[2] * bounds[3] <= 1_000_000L;
    }

    private boolean hover(IWidget widget, int x, int y) {
        var mui = screen.getScreen();
        mui.getContext().updateState(x, y, 0);
        mui.onFrameUpdate();
        for (var hit : widget.getPanel().getHovering()) if (hit.getElement() == widget) return true;
        return false;
    }

    private boolean singleton() {
        int index = target(state()).index;
        return screen.ui.preview.selected() == index
            && Arrays.equals(screen.ui.preview.selection().indices(), new int[] { index });
    }

    private int candidateRow(int metadata) {
        var visible = screen.ui.config.filteredHatches();
        var target = target(state());
        for (int i = 0; i < 5 && i + screen.ui.config.hatchOffset() < visible.size(); i++) {
            int index = visible.get(i + screen.ui.config.hatchOffset());
            ItemStack stack = target.candidates.get(index);
            if (metadata < 0 ? casing(stack) : device(stack, metadata)) return i;
        }
        return -1;
    }

    private int capabilitySlot() {
        var caps = screen.ui.config.capabilities();
        for (int i = 0; i < 4 && i + screen.ui.config.drawerOffset() < caps.tagCount(); i++)
            if ("coil".equals(caps.getCompoundTagAt(i + screen.ui.config.drawerOffset()).getString("id"))) return i;
        return -1;
    }

    private int optionSlot(int value) {
        var options = screen.ui.config.capability("coil").getTagList("options", 10);
        for (int i = 0; i < 7 && i + screen.ui.config.drawerOffset() < options.tagCount(); i++) {
            var option = options.getCompoundTagAt(i + screen.ui.config.drawerOffset());
            if (option.getBoolean("present") && option.getInteger("value") == value) return i;
        }
        return -1;
    }

    static int nextFacing(int[] legal, int current) {
        if (legal.length == 0) return -1;
        for (int i = 0; i < legal.length; i++) if (legal[i] == current) return legal[(i + 1) % legal.length];
        return legal[0];
    }

    static boolean exactCoilDelta(Map<String, String> before, Map<String, String> after,
        Map<String, HeatingCoilLevel> old, Map<String, HeatingCoilLevel> next) {
        if (before.size() != 33 || !before.keySet().equals(after.keySet()) || !old.keySet().equals(next.keySet())
            || !before.keySet().containsAll(old.keySet()) || !validCoils(old, HeatingCoilLevel.LV)
            || !validCoils(next, HeatingCoilLevel.MV)) return false;
        for (String coordinate : before.keySet())
            if (old.containsKey(coordinate) == Objects.equals(before.get(coordinate), after.get(coordinate))) return false;
        return true;
    }

    private static boolean validCoils(Map<String, HeatingCoilLevel> coils, HeatingCoilLevel level) {
        return coils.size() == 16 && coils.values().stream().allMatch(found -> found == level);
    }

    private static Map<String, HeatingCoilLevel> coils(HologramState state) {
        Map<String, HeatingCoilLevel> result = new TreeMap<>();
        for (var cell : state.cells) {
            Block block = Block.getBlockFromName(cell.wantId);
            if (block instanceof IHeatingCoil coil)
                result.put(cell.dx + "," + cell.dy + "," + cell.dz, coil.getCoilHeat(cell.wantMeta));
        }
        return result;
    }

    private HologramState.Cell target(HologramState state) {
        HologramState.Cell found = null;
        for (var c : state.cells) if (c.dx == TARGET[0] && c.dy == TARGET[1] && c.dz == TARGET[2]) {
            require(found == null && c.x == ax + TARGET[0] && c.y == ay + TARGET[1] && c.z == az + TARGET[2],
                "EBF target duplicated or absolute coordinate changed");
            found = c;
        }
        require(found != null && !found.anchor, "Exact top cell absent");
        return found;
    }

    private void press(String key, String label) throws Exception {
        editBefore = state();
        pressed = System.nanoTime();
        minSerial = materialSerial();
        click(key, label);
    }

    private boolean revisionDelta() {
        var state = state();
        long base = editBefore.data.getLong("planRevision"), revision = state.data.getLong("planRevision");
        require(base <= revision && revision <= base + 1, "Unexpected EBF local revision");
        if (revision == base) return false;
        require(editBefore.session.equals(state.session)
            && editBefore.data.getString("generation").equals(state.data.getString("generation"))
            && state.cells.size() == editBefore.cells.size(), "Single-coordinate control changed geometry owner");
        var subject = target(state);
        require(target(editBefore).index == subject.index && Arrays.equals(state.changedIndices, new int[] { subject.index }),
            "EBF partial patch did not address exactly the selected coordinate");
        for (int i = 0; i < state.cells.size(); i++) if (i != subject.index)
            require(state.cells.get(i).tag().equals(editBefore.cells.get(i).tag()), "Unrelated row changed");
        return true;
    }

    private void ack(String label, HologramState.Cell cell) {
        var r = new LinkedHashMap<String, Object>();
        r.put("action", label);
        r.put("revision", state().data.getLong("planRevision"));
        r.put("naturalBodySerial", naturalSerial);
        r.put("nanos", System.nanoTime() - pressed);
        r.put("facing", cell.targetFacing);
        r.put("hasFacingPin", cell.tag().getBoolean("hasFacingPin"));
        r.put("machine", cell.targetStack.getItemDamage());
        acknowledgements.add(r);
        editBefore = null;
    }

    private void own(Minecraft mc) {
        require(mc.currentScreen == screen && screen.localControllerStandard() && actor == mc.thePlayer
            && world == mc.theWorld && connection == mc.getNetHandler() && actor.dimension == 0
            && !actor.capabilities.isCreativeMode && tile == mc.theWorld.getTileEntity(ax, ay, az)
            && tile.getMetaTileEntity().getClass() == MTEElectricBlastFurnace.class
            && ((MTEElectricBlastFurnace) tile.getMetaTileEntity()).getExtendedFacing() == ExtendedFacing.NORTH_NORMAL_NONE
            && session.equals(state().session) && actor.inventory.currentItem == slot
            && stock.equals(actor.inventory.writeToNBT(new NBTTagList())),
            "EBF lost exact owner, alignment, or unmodified player inventory");
    }

    private List<List<Object>> worldSample(Minecraft mc, HologramState state) {
        var proof = new ArrayList<List<Object>>();
        for (var row : state.cells) {
            require(mc.theWorld.blockExists(row.x, row.y, row.z), "Unloaded footprint coordinate");
            var block = mc.theWorld.getBlock(row.x, row.y, row.z);
            var te = mc.theWorld.getTileEntity(row.x, row.y, row.z);
            require(row.anchor ? te == tile : block == Blocks.air && te == null, "Fixture is not an empty controller footprint");
            proof.add(Arrays.asList(row.x, row.y, row.z, block, mc.theWorld.getBlockMetadata(row.x, row.y, row.z), te));
        }
        return proof;
    }

    private boolean ready() {
        require(!state().data.getBoolean("localStructureUnavailable"), "Native declaration is unavailable");
        return state().data.getBoolean("projectionDataComplete") && !state().data.getBoolean("capturePending")
            && state().cells.size() == 34 && state().data.getInteger("main") == 1 && state().data.getInteger("scope") == 0
            && screen.ui.session.editable() && !screen.ui.library.localBusy() && !HologramBackgroundTasks.busy();
    }

    private boolean fresh() { return naturalState == state() && naturalSerial > minSerial; }
    private HologramState state() { return screen.ui.session.state(); }
    private int coil() { return screen.ui.config.capability("coil").getInteger("current"); }

    private long materialSerial() {
        var frame = HologramRenderer.editorMaterialPresentation();
        return frame == null ? 0 : frame.serial();
    }

    private String toolControl(String command) {
        int index = new HologramEditorCommands(screen.ui).index(command);
        require(index >= 0, "Workbench control missing: " + command);
        return "control." + (HologramEditorControlIds.TOOLS + index);
    }

    private boolean canClick(String key) { return screen.ui.shell.targetBounds(key) != null; }

    private boolean clickWhenPresent(String key, String label) throws Exception {
        if (!canClick(key)) return false;
        click(key, label);
        return true;
    }

    private void click(String key, String label) throws Exception {
        var hit = new LinkedHashMap<String, Object>();
        require(controls.putIfAbsent(label, hit) == null, "Refusing repeat native click: " + label);
        HologramMuiProbeInput.nativeTarget(screen, key, hit);
    }

    private void step(Phase next) {
        phase = next;
        since = System.nanoTime();
        closeSent = false;
        dockOpened = false;
        dockInput = null;
        report.put("phase", next.name());
    }

    private static boolean device(ItemStack stack, int id) {
        return stack != null && stack.getItem() instanceof ItemMachines && stack.getItemDamage() == id;
    }

    private static boolean casing(ItemStack stack) {
        return stack != null && Block.getBlockFromItem(stack.getItem()) == GregTechAPI.sBlockCasings1
            && stack.getItemDamage() == 11;
    }

    private static boolean sameManualHatch(HologramState.Cell now, HologramState.Cell before) {
        return device(now.targetStack, OTHER_OUTPUT) && "manual".equals(now.pinScope) && now.chosenChoice == before.chosenChoice
            && ItemStack.areItemStacksEqual(now.targetStack, before.targetStack)
            && facingIndependent(now).equals(facingIndependent(before));
    }

    private static NBTTagCompound facingIndependent(HologramState.Cell cell) {
        var tag = targetTag(cell);
        for (String key : new String[] { "hatchFacing", "hasFacingPin", "targetFacing", "targetDisplay" })
            tag.removeTag(key);
        return tag;
    }

    private static NBTTagCompound targetTag(HologramState.Cell cell) {
        var tag = cell.tag();
        tag.removeTag("index"); // partial edit packets carry their ordinal; baseline compact target rows do not
        return tag;
    }

    static final String COIL_FLAG = "gtmb.testlab.localPreviewStagesEbfCoilOnly";
    static final String COIL_SCHEMA = "gtmb-stock-ebf-native-coil-v1";
    static final String COIL_FILE = "gtmb-ebf-coil-native.json";

    private final boolean coilOnly = coilOnlyEnabled();
    private long coilBaselineSerial;
    private int naturalMaterialCells;
    private NBTTagCompound coilChannels;
    private long coilStructureDrawSerial;
    private int[] coilStructureDrawControlClip;

    static boolean coilOnlyEnabled() {
        return Boolean.getBoolean(COIL_FLAG);
    }

    static String receiptKey() {
        return coilOnlyEnabled() ? "coilControllerAcceptance" : "fixedControllerAcceptance";
    }

    static boolean coilProfileAllowed(boolean issue100, boolean host, boolean localPreview, boolean stock,
        boolean fixed, String parity, boolean alternate) {
        return issue100 && host && localPreview && stock && fixed && "ebf".equalsIgnoreCase(parity) && !alternate;
    }

    private String testScope() {
        return coilOnly ? "stock EBF fixed master/alignment; native coil channel only; no hatch controls or placement"
            : "stock EBF fixed master/alignment/one selected top coordinate; no construction";
    }

    private void startSelection() throws Exception {
        baselineOffers();
        step(Phase.SELECT_PIN);
    }

    private void startCoilOnly(Map<String, Object> first) {
        requireCoilBaseline();
        coilBaselineSerial = naturalSerial;
        require(coilBaselineSerial > 0 && !generation(initial).isEmpty(), "No exact untouched R0 material frame");
        first.put("effectiveCoil", 1);
        first.put("planRevision", initial.data.getLong("planRevision"));
        first.put("fullMaterialCells", naturalMaterialCells);
        coilChannels = (NBTTagCompound) baseChannels.copy();
        coilChannels.setInteger("coil", 2);
        step(Phase.CHANNELS);
    }

    private void requireCoilBaseline() {
        require(state() == initial && state().data.getLong("planRevision") == 0 && emptySelection() && coil() == 1
            && ready(), "Coil-only action must start on the original unedited and unselected R0");
    }

    private boolean emptySelection() {
        return screen.ui.preview.selected() == -1 && screen.ui.preview.selection().size() == 0;
    }

    private boolean openChannels() throws Exception {
        if (!coilOnly) return clickWhenPresent(toolControl("channels"), "channels");
        var shell = screen.ui.shell;
        var scene = shell.scene();
        var viewport = scene == null ? null : shell.nativeWidgetBounds(scene);
        boolean projection = screen.ui.workspaces.projection() && !screen.ui.workspaces.emptyProjection();
        boolean virtual = state().data.getBoolean("virtual");
        boolean expanded = shell.dockExpanded(), source = shell.propertiesGroupExpanded("source");
        int page = shell.dockPage();
        String modal = shell.tutorialModalIdentity();
        boolean blocked = shell.hasModalPanel();
        boolean hasScene = scene instanceof HologramEditorSceneWidget;
        boolean owner = dockInput != null && dockInput.state == initial && dockInput.state == state()
            && dockInput.scene == scene;
        long previousSerial = dockInput == null ? minSerial : Math.max(minSerial, dockInput.serial);
        var observed = renderedLayout;
        boolean actualFrame = observed != null && hasScene && ((HologramEditorSceneWidget) scene).editFrameReady()
            && pickFrameMatches(previousSerial, observed.serial, state(), observed.state, scene, observed.scene,
                viewport, observed.bounds);
        var launcher = shell.targetBounds("launcher.3");
        var channels = shell.targetBounds("control.60");
        boolean paneDrawn = observed != null && coilStructureDrawSerial == observed.serial
            && safePickBounds(channels) && Arrays.equals(channels, coilStructureDrawControlClip);
        var evidence = new LinkedHashMap<String, Object>();
        report.put("nativeChannelsRoute", evidence);
        evidence.put("step", dockInput == null ? "openStructureConfiguration" : "awaitStructurePage");
        evidence.put("projection", projection);
        evidence.put("virtual", virtual);
        evidence.put("dockExpanded", expanded);
        evidence.put("dockPage", page);
        evidence.put("sourceGroupExpanded", source);
        evidence.put("topModal", modal);
        evidence.put("noModal", !blocked);
        evidence.put("sameLauncherOwner", owner);
        evidence.put("strictlyNewerNativeLayout", actualFrame);
        evidence.put("structurePaneDrawnInThatLayout", paneDrawn);
        evidence.put("structurePaneDrawSerial", coilStructureDrawSerial);
        evidence.put("currentViewport", viewport);
        evidence.put("renderedViewport", observed == null ? null : observed.bounds);
        evidence.put("requiredSerialExclusive", previousSerial);
        evidence.put("nativeMaterialSerial", naturalSerial);
        evidence.put("structureLauncherClip", launcher);
        evidence.put("channelsControlClip", channels);
        requireCoilBaseline();
        if (dockInput == null) {
            require(projection && !virtual && !expanded && page == 0 && !blocked,
                "Coil-only expected the untouched physical projection with the inspector closed");
            if (hasScene && safePickBounds(viewport) && fresh() && safePickBounds(launcher))
                openCoilStructureDock(scene, viewport);
            return false;
        }
        require(owner, "Structure-config launcher changed coil R0 or the owned viewport widget");
        if (!coilStructurePaneEligible(projection, virtual, expanded, page, source, blocked, owner, actualFrame,
            paneDrawn, channels)) return false;
        evidence.put("step", "pressStructureChannels");
        var hit = new LinkedHashMap<String, Object>();
        require(controls.putIfAbsent("channels", hit) == null, "Refusing repeat structure Channels click");
        IWidget target = ownedChannelsControl(channels, hit);
        HologramMuiProbeInput.nativeTarget(screen, "control.60", hit, () -> {
            requireCoilBaseline();
            require(!shell.hasModalPanel() && shell.dockExpanded() && shell.dockPage() == 3
                && shell.propertiesGroupExpanded("source") && scene == shell.scene()
                && ((HologramEditorSceneWidget) scene).editFrameReady()
                && Arrays.equals(viewport, shell.nativeWidgetBounds(scene))
                && Arrays.equals(channels, shell.nativeWidgetBounds(target))
                && target.getPanel().getHovering().stream().anyMatch(located -> located.getElement() == target),
                "Exact labelled Channels control changed native owner before press");
            hit.put("labelAndNativeHoverMatch", true);
        });
        return true;
    }

    private void openCoilStructureDock(IWidget scene, int[] viewport) throws Exception {
        var hit = new LinkedHashMap<String, Object>();
        require(controls.putIfAbsent("structureDock", hit) == null, "Refusing repeat structure-config launcher click");
        hit.put("sourceLabel", "结构配置");
        HologramMuiProbeInput.nativeTarget(screen, "launcher.3", hit, () -> {
            requireCoilBaseline();
            var shell = screen.ui.shell;
            require(!shell.hasModalPanel() && !shell.dockExpanded() && shell.dockPage() == 0 && scene == shell.scene()
                && Arrays.equals(viewport, shell.nativeWidgetBounds(scene)), "Initial native structure launcher lost its owner");
            dockInput = new PickLayout(state(), scene, viewport, materialSerial());
            hit.put("lastMaterialSerialBeforePress", dockInput.serial);
        });
    }

    private IWidget ownedChannelsControl(int[] clip, Map<String, Object> hit) throws ReflectiveOperationException {
        var shell = screen.ui.shell;
        // Read the label from the exact production control registration; do not invoke its callback.
        var registry = (Map<?, ?>) HologramTestLabUiDiagnostics.readDiagnosticField(shell, "controls");
        Object rows = registry.get(60);
        require(rows instanceof List<?>, "No registered Channels action ID 60");
        IWidget matched = null;
        for (Object entry : (List<?>) rows) {
            IWidget widget = (IWidget) HologramTestLabUiDiagnostics.readDiagnosticField(entry, "widget");
            if (widget.getPanel() != shell.host() || !Arrays.equals(clip, shell.nativeWidgetBounds(widget))) continue;
            boolean structureList = false;
            for (IWidget parent = widget; parent != null && parent != shell.host(); parent = parent.getParent())
                if ("editor_native_structure_list".equals(parent.getName())) structureList = true;
            var supplier = (java.util.function.Supplier<?>) HologramTestLabUiDiagnostics.readDiagnosticField(entry, "label");
            Object name = supplier.get();
            hit.put("registeredLabel", name);
            hit.put("ownedStructureList", structureList);
            require(structureList && "结构等级 / 信道".equals(name) && matched == null,
                "Action ID 60 is not the unique labelled Channels control in the mounted structure pane");
            matched = widget;
        }
        require(matched != null, "Could not resolve the exact owned Channels control");
        return matched;
    }

    static boolean coilStructurePaneEligible(boolean physicalProjection, boolean virtual, boolean expanded, int page,
        boolean sourceExpanded, boolean modal, boolean exactOwner, boolean fresh, boolean paneDrawn, int[] buttonClip) {
        return physicalProjection && !virtual && expanded && page == 3 && sourceExpanded && !modal && exactOwner
            && fresh && paneDrawn && safePickBounds(buttonClip);
    }

    private void pressCoilOption(int row) throws Exception {
        if (coilOnly) {
            requireCoilBaseline();
            require(exactTopConfiguration("options"),
                "Native coil option is not the exact top options panel");
        }
        press("config.option.row." + row, "coil2");
    }

    private boolean observeCoilAdmission(HologramState pending) {
        if (!coilOnly) return true;
        boolean original = coilAdmissionMatches(initial, editBefore, initial.cells, pending.cells, session,
            pending.session, generation(initial), generation(pending), pending.data.getLong("planRevision"))
            && baseChannels.equals(pending.data.getCompoundTag("channels"));
        boolean intermediate = capturePending && !generation(pending).isEmpty()
            && !generation(initial).equals(generation(pending)) && expectedCoilState(pending);
        require(emptySelection() && (original || intermediate), "Coil-only recapture did not admit on exact untouched R0");
        if (!capturePending) {
            Map<String, Boolean> admission = new LinkedHashMap<>();
            admission.put("sameOwnerAndR0Body", true);
            admission.put("capturePending", true);
            report.put("captureAdmission", java.util.Collections.unmodifiableMap(admission));
        }
        return true;
    }

    static boolean coilAdmissionMatches(Object baseline, Object pressed, Object originalCells, Object pendingCells,
        String owner, String pendingOwner, String generation, String pendingGeneration, long pendingRevision) {
        return baseline != null && baseline == pressed && originalCells != null && originalCells == pendingCells
            && owner != null && !owner.isEmpty() && owner.equals(pendingOwner)
            && generation != null && !generation.isEmpty() && generation.equals(pendingGeneration) && pendingRevision == 0;
    }

    private boolean exposeCoilPreview(HologramState now) throws Exception {
        if (!coilOnly) return true;
        if (!capturePending || !ready() || coil() != 2 || now == initial || generation(now).equals(generation(initial)))
            return false;
        require(!generation(now).isEmpty() && emptySelection() && expectedCoilState(now),
            "Coil-only recapture changed configuration outside the requested channel");
        // The native option closes only its own window. Expose the completed body through the normal Channels close.
        if (screen.ui.config.drawer()) {
            if (!controls.containsKey("closeChannels") && screen.ui.config.optionChannel().isEmpty()
                && exactTopConfiguration("channels")
                && canClick("config.channels.close")) {
                minSerial = materialSerial();
                click("config.channels.close", "closeChannels");
            }
            return false;
        }
        return !screen.ui.shell.hasModalPanel() && controls.containsKey("closeChannels");
    }

    private boolean exactTopConfiguration(String name) {
        return screen.ui.panelName("gtmb_editor_configuration_" + name).equals(screen.ui.shell.tutorialModalIdentity());
    }

    private boolean expectedCoilState(HologramState now) {
        return session.equals(now.session) && now.data.getInteger("main") == initial.data.getInteger("main")
            && now.data.getInteger("scope") == initial.data.getInteger("scope")
            && now.data.getInteger("facing") == initial.data.getInteger("facing")
            && coilChannels.equals(now.data.getCompoundTag("channels"));
    }

    private void completeCoilOnly(Map<String, Object> second) {
        require(coilFrameMatches(initial, state(), naturalState, coilBaselineSerial, minSerial, naturalSerial)
            && emptySelection(), "Coil-only terminal lacks a newer owned material frame");
        second.put("effectiveCoil", 2);
        second.put("fullMaterialCells", naturalMaterialCells);
        second.put("newCaptureGeneration", true);
        require(coilControlsMatch(new ArrayList<>(controls.keySet())) && acknowledgements.isEmpty(),
            "Coil-only terminal includes other input or a missing native Channels control");
        report.put("hatchControlsExercised", false);
        report.put("placementExercised", false);
    }

    static boolean coilControlsMatch(List<String> controls) {
        return controls.equals(Arrays.asList("structureDock", "channels", "chooseCoil", "coil2", "closeChannels"));
    }

    static boolean coilFrameMatches(Object baseline, Object expected, Object drawn, long firstSerial, long closeSerial,
        long drawnSerial) {
        return baseline != null && expected != null && baseline != expected && expected == drawn
            && firstSerial > 0 && drawnSerial > Math.max(firstSerial, closeSerial);
    }

    private static String generation(HologramState state) {
        return state.data.getString("generation");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
