package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Uses a real socket, PlayerController C08, GUI mouse path and server-owned receipts. */
public final class HologramInteractionClientSmoke {

    private final long installed = System.nanoTime();
    private final long startupBudgetNanos = Math
        .max(60L, Math.min(3600L, Long.getLong("gtmb.hologram.nativeStartupSeconds", 1800L))) * 1000000000L;
    private long executionStarted;
    private int stage, waitTicks;
    private boolean done;
    private File receipts;
    private HologramScreen realScreen;
    private long lastClickedRender = -1;
    private HologramScreen lastClickedScreen;
    private long renderedWorldFrames, closedAtWorldFrame;
    private long closedAtServerTick;
    private HologramInteractionReceiptSmoke receiptReader;
    private NBTTagCompound tickReceipt;
    private boolean connectionDiagnosticSaved;
    private int timedStage = -1;
    private long stageEnteredAt;
    private String lastStageSnapshot = "";
    private final int[] initialClicks = { 71, 72, 70, 82, 80, 60 };
    private int gradeClicks;
    private int initialGrade;
    private boolean airPlacementPrepared;
    private long airPreparedFrame;
    private int[] expectedAirAnchor;
    private int[] preservedGuiSelection;
    private boolean sufficientBlueprintPass = true;
    private long operationStartedAt;
    private final java.util.Set<String> preservedGradeCoordinates = new java.util.LinkedHashSet<>();
    private HologramAeInteractionClientSmoke aeDriver;
    private long aeRenderedFrames;
    private long disconnectedFrame = -1, disconnectedAt;
    private String disconnectedReason;
    private String lastStoneAreaObservation = "";
    private String quickPresetId;
    private String quickLocalId, completedLocalId;
    private HologramLibraryUiSocketSmoke libraryUiDriver;
    private String librarySaveWait = "";
    private long lockedNonce;
    private HologramLockedNudgeClientSmoke nudgeSmoke;
    private boolean nudgeChecked;
    private int reviewStage;
    private final HologramActualUsePress usePress = new HologramActualUsePress();
    private String completedSession, completedPreset, completedActor, completedClientActor;
    private int[] completedStoneAnchor;
    private int completedPlaced, completedStock, afterRepeatStage;
    private long repeatStartedAt, repeatFrame, repeatCancelledTick;
    private String repeatSession;
    private long repeatNonce;
    private boolean lockedEscapeChecked;
    private String lockedEscapeSession;
    private long lockedEscapeNonce, lockedEscapeStartedAt;
    private HologramRenderer.WorldRenderWitness lastConsumedWorldWitness;
    private HologramState lastRenderedProjectionState;
    private int[] lastRenderedMovingAnchor, aimedMovingAnchor;
    private int observedWorldGeometry, observedWorldGhosts, observedWorldOutline;
    private String aimedProjectionGeneration = "";
    private long aimedWorldFrame;
    private long aimedConsumedGeneration;
    private NBTTagCompound aimedCameraObservation, renderedCameraObservation;
    private int observedToolCopyStack;
    private long repeatCancelAckTick = -1, lockedEscapeCancelSentTick;
    private long lockedEscapeCancelAckTick = -1;
    private boolean lockedEscapeReopenRequested;
    private String cancelledReceiptWait = "";
    private String areaSourceSwitchNonce = "";
    private HologramProcessingClientSmoke areaProcessing;
    private HologramPlacementReviewClientSmoke placementReviewInput;
    private NBTTagCompound right2Opening;
    private HologramAreaSelectionClientSmoke areaEntryInput;

    public static void install() {
        if (Boolean.getBoolean("gtmb.hologram.realInteractionSmoke")) {
            HologramInteractionClientSmoke driver = new HologramInteractionClientSmoke();
            FMLCommonHandler.instance()
                .bus()
                .register(driver);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(driver);
        }
    }

    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.LOWEST)
    public void worldRendered(net.minecraftforge.client.event.RenderWorldLastEvent event)
        throws ReflectiveOperationException {
        renderedWorldFrames++;
        lastConsumedWorldWitness = HologramRenderer.lastWorldRenderWitness();
        lastRenderedProjectionState = lastConsumedWorldWitness == null ? null : lastConsumedWorldWitness.state;
        observedWorldGeometry = lastConsumedWorldWitness == null ? 0 : lastConsumedWorldWitness.geometry;
        observedWorldGhosts = lastConsumedWorldWitness == null ? 0 : lastConsumedWorldWitness.ghosts;
        observedWorldOutline = lastConsumedWorldWitness == null ? 0 : lastConsumedWorldWitness.outlineColor;
        lastRenderedMovingAnchor = lastConsumedWorldWitness == null ? null : lastConsumedWorldWitness.anchor();
        renderedCameraObservation = cameraObservation(Minecraft.getMinecraft(), lastRenderedProjectionState);
    }

    @SubscribeEvent
    public void aeRendered(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) aeRenderedFrames++;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (done) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.currentScreen instanceof net.minecraft.client.gui.GuiDisconnected) {
                if (event.phase == TickEvent.Phase.START) return;
                if (disconnectedReason == null) {
                    disconnectedReason = disconnectReason((net.minecraft.client.gui.GuiDisconnected) mc.currentScreen);
                    disconnectedFrame = aeRenderedFrames;
                    disconnectedAt = System.nanoTime();
                    System.err.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] DISCONNECTED " + disconnectedReason);
                    if (receipts != null) Files.write(
                        new File(receipts, "client-disconnect.txt").toPath(),
                        disconnectedReason.getBytes(StandardCharsets.UTF_8));
                }
                // Capture its next genuine rendered frame, then fail; never reconnect or wait for the overall timeout.
                if (aeRenderedFrames <= disconnectedFrame && System.nanoTime() - disconnectedAt < 2000000000L) return;
                screenshot(mc, "network-disconnected-exact-reason");
                throw new IllegalStateException("actual socket disconnected: " + disconnectedReason);
            }
            if (aeDriver != null) {
                if (aeDriver.tick(event, aeRenderedFrames)) {
                    if (Boolean.getBoolean("gtmb.hologram.librarySocketSmoke") && !awaitLibrarySocketProof()) return;
                    Files.write(
                        new File(receipts, "client-complete.txt").toPath(),
                        "v004 whole real flow and v005 actual AE container/NEI/review/paid placement PASS\n"
                            .getBytes(StandardCharsets.UTF_8));
                    done = true;
                    System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] FINAL PASS stage=100 AE full real flow");
                    mc.shutdown();
                }
                return;
            }
            if (event.phase == TickEvent.Phase.START) {
                usePress.tick(event, mc);
                return;
            }
            // Validate this tick's real file before any input; publication retries never repeat a sent action.
            tickReceipt = null;
            if (stage >= 2 && receipts != null) {
                if (receiptReader == null) receiptReader = new HologramInteractionReceiptSmoke(receipts);
                tickReceipt = receiptReader.read();
                if (tickReceipt == null) return;
            }
            if (usePress.tick(event, mc)) return;
            if (timedStage != stage) {
                timedStage = stage;
                stageEnteredAt = System.nanoTime();
            }
            if (stage == 48 && System.nanoTime() - stageEnteredAt > 10000000000L) {
                NBTTagCompound waiting = HologramClient.state == null ? null : HologramClient.state.data;
                saveObservation("stone-processing-timeout-state.nbt", waiting);
                throw new IllegalStateException(
                    "10 second actual stone processing input/ACK wait: " + stageSnapshot(waiting));
            }
            if ((stage == 11 || stage == 17 || stage == 21 || stage == 24 || stage == 66)
                && System.nanoTime() - stageEnteredAt >= 10000000000L) {
                HologramScreen waitingScreen = mc.currentScreen instanceof HologramScreen
                    ? (HologramScreen) mc.currentScreen
                    : null;
                NBTTagCompound waitingState = waitingScreen == null ? null : waitingScreen.smokeSnapshot();
                if (waitingState == null
                    || waitingState.getInteger("job") != 3 && waitingState.getInteger("job") != 5) {
                    String summary = stageSnapshot(waitingState);
                    if (stage == 66) {
                        if (waitingScreen != null)
                            logConfirmClickState(waitingScreen, "single-stone-ten-second-timeout");
                        saveObservation("stage-66-timeout-state.nbt", waitingState);
                        saveObservation("stage-66-timeout-receipt.nbt", tickReceipt);
                    }
                    System.err.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] WAIT TIMEOUT " + summary);
                    IllegalStateException failure = new IllegalStateException("10 second execution wait " + summary);
                    try {
                        screenshot(mc, "stage-" + stage + "-timeout");
                    } catch (Throwable screenshotFailure) {
                        failure.addSuppressed(screenshotFailure);
                    }
                    throw failure;
                }
            }
            if (executionStarted == 0) {
                if (System.nanoTime() - installed > startupBudgetNanos)
                    throw new IllegalStateException("real interaction menu/world startup timeout stage=" + stage);
            } else if (System.nanoTime() - executionStarted > 600000000000L)
                throw new IllegalStateException("real interaction timeout stage=" + stage);
            if (operationStartedAt != 0 && System.nanoTime() - operationStartedAt > 180000000000L)
                throw new IllegalStateException("180 second real-player operation budget exceeded stage=" + stage);
            if (stage == 0) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) return;
                mc.gameSettings.pauseOnLostFocus = false;
                receipts = new File(System.getProperty("gtmb.hologram.interactionReceiptDir", ""));
                if (!receipts.isDirectory()) throw new IllegalStateException("prepared receipt directory required");
                String address = System.getProperty("gtmb.hologram.interactionAddress", "127.0.0.1:25581");
                if (!address.equals("127.0.0.1:25581"))
                    throw new IllegalStateException("isolated localhost address guard");
                cpw.mods.fml.client.FMLClientHandler.instance()
                    .setupServerList();
                cpw.mods.fml.client.FMLClientHandler.instance()
                    .connectToServer(mc.currentScreen, new ServerData("GTMB isolated interaction", address));
                stage = 1;
                return;
            }
            if (stage == 1) {
                if (++waitTicks % 100 == 0) System.out.println(
                    "[GTMB-HOLOGRAM-INTERACTION-CLIENT] CONNECT screen="
                        + (mc.currentScreen == null ? "none"
                            : mc.currentScreen.getClass()
                                .getName())
                        + " player="
                        + (mc.thePlayer != null)
                        + " world="
                        + (mc.theWorld != null)
                        + " receipt="
                        + new File(receipts, "world-receipt.nbt").isFile());
                if (!connectionDiagnosticSaved && waitTicks >= 200) {
                    connectionDiagnosticSaved = true;
                    ScreenShotHelper.saveScreenshot(
                        mc.mcDataDir,
                        "hologram-network-connect-diagnostic.png",
                        mc.displayWidth,
                        mc.displayHeight,
                        mc.getFramebuffer());
                }
                if (mc.thePlayer == null || mc.theWorld == null || !new File(receipts, "world-receipt.nbt").isFile())
                    return;
                if (mc.thePlayer.getHeldItem() == null || mc.theWorld.getTileEntity(48, 80, 48) == null) return;
                require(
                    isOfficial(mc.thePlayer.getHeldItem()) && mc.thePlayer.getHeldItem().stackSize == 1,
                    "fresh_server_supplies_one_official_projector_instead_of_GTMB_output");
                executionStarted = System.nanoTime();
                operationStartedAt = System.nanoTime();
                mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiInventory(mc.thePlayer));
                stage = 70;
                waitTicks = 0;
                return;
            }
            if (stage >= 70 && stage <= 75) {
                crafting(mc);
                return;
            }
            if (stage == 30) {
                if (renderedWorldFrames <= closedAtWorldFrame) return;
                waitTicks = 0;
                require(HologramRenderer.lastWorldGeometry > 0, "cached_world_material_geometry_" + stage);
                require(HologramRenderer.lastWorldGTGeometry > 0, "world_renders_GT_material_models_" + stage);
                require(HologramRenderer.lastWorldGhosts > 0, "real_world_has_ghost_cells_" + stage);
                screenshot(mc, "world-materials");
                reopenWithRightClick(mc);
                stage = 3;
                return;
            }
            if (stage == 61 || stage == 63 || stage == 95) {
                require(mc.currentScreen == null, "root_exit_key_returns_to_real_world_stage_" + stage);
                if (System.nanoTime() - stageEnteredAt >= 10000000000L)
                    throw new IllegalStateException("normal root close observation timed out stage=" + stage);
                if (renderedWorldFrames <= closedAtWorldFrame || receipt().getLong("tick") - closedAtServerTick < 10)
                    return;
                require(mc.currentScreen == null, "normal_root_close_stays_closed_for_ten_server_ticks_" + stage);
                if (stage == 95) {
                    reopenWithRightClick(mc);
                    stage = 96;
                } else {
                    mc.objectMouseOver = null;
                    airRightClick(mc, false);
                    clearClickedFrame();
                    stage = stage == 61 ? 62 : 64;
                }
                return;
            }
            if ((stage >= 40 && stage <= 46 || stage == 50
                || stage == 51
                || stage == 54
                || stage == 55
                || stage >= 77 && stage <= 79
                || stage == 94
                || stage >= 91 && stage <= 93) && worldFlow(mc)) return;
            if ((stage >= 83 && stage <= 86 || stage >= 88 && stage <= 90) && repeatCompletedPlacement(mc)) return;
            if (libraryUiDriver != null && !libraryUiDriver.complete()) {
                libraryUiDriver.tick(mc, tickReceipt);
                return;
            }
            if (!(mc.currentScreen instanceof HologramScreen)) return;
            HologramScreen screen = (HologramScreen) mc.currentScreen;
            if (!hasFreshDraw(screen)) {
                if ((stage == 65 || stage == 67 || stage == 28 || stage == 29 || stage == 76)
                    && System.nanoTime() - stageEnteredAt >= 10000000000L)
                    throw new IllegalStateException("10 second wait for actual grade GUI render stage=" + stage);
                return;
            }
            NBTTagCompound state = screen.smokeSnapshot();
            if (screen.smokePending()) {
                if ((stage == 67 || stage == 28 || stage == 29) && System.nanoTime() - stageEnteredAt >= 10000000000L)
                    throw new IllegalStateException(
                        "10 second wait for actual coil-grade acknowledgement " + stageSnapshot(state));
                return;
            }
            if (stage == 11 || stage == 17 || stage == 33 || stage == 18 || stage == 19 || stage == 21 || stage == 24) {
                String summary = stageSnapshot(state);
                if (!summary.equals(lastStageSnapshot)) {
                    lastStageSnapshot = summary;
                    System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] SNAPSHOT " + summary);
                }
            }
            if ((stage == 11 || stage == 17 || stage == 21 || stage == 24) && screen.smokeHasControl(55)) {
                require(state.getInteger("missing") == 0, "real_execution_materials_sufficient_stage_" + stage);
                // Protected positions can deliberately stay unchanged. Independent receipts below require
                // the complete shell, all sixteen upgraded coils and exact material conservation.
                logConstructionScope(screen, "whole-structure-materials-stage-" + stage);
                screenshot(mc, "materials-confirm-stage-" + stage);
                click(screen, 55);
                return;
            }
            if (++waitTicks < 4) return;
            waitTicks = 0;
            if (stage == 2) {
                if (state.getBoolean("capturePending")) return;
                require(
                    !libraryTab(screen) && !(Boolean) observedField(screen, "libraryDrawer"),
                    "real_controller_entry_overrides_persisted_blueprint_tab");
                require(
                    state.getBoolean("target") && state.getBoolean("supported")
                        && state.getTagList("cells", 10)
                            .tagCount() == 36,
                    "real_C08_state_opens_full_EBF_GUI");
                awaitControl(screen, 81);
                require(HologramRenderer.lastPreviewAnchorMarkers > 0, "controller_marker_drawn_in_real_preview");
                verifyAnimatedItemAtlas(mc);
                String guidance = HologramGuidance.next(new HologramState(state), -1, libraryTab(screen), false);
                require(
                    !guidance.trim()
                        .isEmpty(),
                    "first_crafted_projector_entry_has_contextual_next_step_text");
                System.out.println(
                    "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS first entry " + screen.smokeLayout()
                        + " guidance="
                        + guidance);
                screenshot(mc, "initial-GUI");
                preservedGuiSelection = selectPlainPreviewFace(screen, state);
                click(screen, 149);
                stage = 60;
                return;
            }
            if (stage >= 3 && stage < 3 + initialClicks.length) {
                click(screen, initialClicks[stage - 3]);
                stage++;
                return;
            }
            switch (stage) {
                case 60:
                    require(
                        libraryTab(screen) && (Boolean) observedField(screen, "libraryDrawer"),
                        "actual_blueprint_tab_is_peer_workspace_tab");
                    require(
                        java.util.Arrays.equals(preservedGuiSelection, screen.smokeSelectedIndices()),
                        "peer_tab_preserves_preview_selection");
                    closedAtWorldFrame = renderedWorldFrames;
                    closedAtServerTick = receipt().getLong("tick");
                    HologramMuiProbeInput.key(screen, '\0', Keyboard.KEY_ESCAPE);
                    require(mc.currentScreen == null, "Escape_from_blueprints_exits_whole_GUI");
                    stage = 61;
                    break;
                case 62:
                    require(libraryTab(screen), "reopen_after_Escape_preserves_blueprint_tab");
                    require(
                        java.util.Arrays.equals(preservedGuiSelection, screen.smokeSelectedIndices()),
                        "reopen_after_Escape_preserves_actual_mouse_selection");
                    closedAtWorldFrame = renderedWorldFrames;
                    closedAtServerTick = receipt().getLong("tick");
                    HologramMuiProbeInput.key(screen, 'e', Keyboard.KEY_E);
                    require(mc.currentScreen == null, "E_from_blueprints_exits_whole_GUI");
                    stage = 63;
                    break;
                case 64:
                    require(libraryTab(screen), "reopen_after_E_preserves_blueprint_tab");
                    require(
                        java.util.Arrays.equals(preservedGuiSelection, screen.smokeSelectedIndices()),
                        "reopen_after_E_preserves_actual_mouse_selection");
                    closedAtWorldFrame = renderedWorldFrames;
                    closedAtServerTick = receipt().getLong("tick");
                    HologramMuiProbeInput.key(screen, 'e', Keyboard.KEY_E);
                    require(mc.currentScreen == null, "blueprint_memory_E_closes_before_actual_controller_reopen");
                    stage = 95;
                    break;
                case 96:
                    require(
                        !libraryTab(screen) && !(Boolean) observedField(screen, "libraryDrawer"),
                        "actual_controller_right_click_overrides_recent_blueprint_tab");
                    require(
                        state.getBoolean("target") && !state.getBoolean("virtual"),
                        "actual_controller_reopen_keeps_real_structure_context");
                    screenshot(mc, "v009-controller-structure-overrides-blueprint-memory");
                    stage = 65;
                    break;
                case 65:
                    require(!libraryTab(screen), "actual_structure_tab_returns_to_peer_workspace");
                    preservedGuiSelection = selectCoilPreviewFamily(screen, state);
                    preservedGradeCoordinates.clear();
                    preservedGradeCoordinates.addAll(coilSelectionCoordinates(state, preservedGuiSelection));
                    require(
                        preservedGradeCoordinates.size() == 16,
                        "real_EBF_coil_family_has_sixteen_distinct_world_cells");
                    assertNoItemPalette(screen);
                    logConstructionScope(screen, "actual-coil-family-sixteen-selection");
                    recordClickedFrame(screen);
                    stage = 67;
                    break;
                case 67:
                    require(
                        java.util.Arrays.equals(preservedGuiSelection, screen.smokeSelectedIndices()),
                        "actual_coil_family_selection_survives_next_draw_before_grade_menu");
                    assertNoItemPalette(screen);
                    click(screen, 81);
                    stage = 28;
                    break;
                case 28:
                    awaitControl(screen, 63);
                    screenshot(mc, "grade-options");
                    click(screen, 63);
                    stage = 29;
                    break;
                case 29:
                    require(
                        java.util.Arrays.equals(preservedGuiSelection, screen.smokeSelectedIndices()),
                        "consecutive_grade_updates_keep_actual_mouse_selection_" + gradeClicks);
                    require(
                        preservedGradeCoordinates
                            .equals(coilSelectionCoordinates(state, screen.smokeSelectedIndices())),
                        "consecutive_grade_updates_keep_coil_family_and_world_coordinates_" + gradeClicks);
                    if (gradeClicks > 0) require(
                        preservedGradeCoordinates
                            .equals(coilSelectionCoordinates(state, state.getIntArray("selectedIndices"))),
                        "server_grade_ack_retains_clicked_coil_family_coordinates_" + gradeClicks);
                    assertNoItemPalette(screen);
                    logConstructionScope(screen, "coil-grade-ack-" + gradeClicks);
                    if (gradeClicks == 0) initialGrade = coilGrade(state);
                    else require(
                        coilGrade(state) == initialGrade + (gradeClicks <= 3 ? gradeClicks : 6 - gradeClicks),
                        "consecutive_grade_mouse_click_acknowledged_" + gradeClicks);
                    if (gradeClicks < 6) {
                        click(screen, gradeClicks < 3 ? 82 : 80);
                        gradeClicks++;
                        break;
                    }
                    require(
                        coilGrade(state) == initialGrade,
                        "three_consecutive_grade_increments_and_decrements_restore_native_grade");
                    screenshot(mc, "coil-family-six-real-grade-clicks");
                    previewMouseClick(
                        screen,
                        HologramScreenLayout.VIEWPORT_X + HologramScreenLayout.VIEWPORT_WIDTH / 2,
                        HologramScreenLayout.VIEWPORT_Y + HologramScreenLayout.VIEWPORT_HEIGHT / 2,
                        1);
                    recordClickedFrame(screen);
                    stage = 76;
                    break;
                case 76:
                    require(
                        screen.smokeSelectedIndices().length == 0,
                        "actual_clear_selection_restores_whole_structure_build_scope");
                    assertNoItemPalette(screen);
                    logConstructionScope(screen, "actual-right-click-clear-whole-structure");
                    realScreen = screen;
                    mc.thePlayer.rotationYaw = 0;
                    mc.thePlayer.rotationPitch = 20;
                    HologramClient.worldPreview = true;
                    closedAtWorldFrame = renderedWorldFrames;
                    mc.displayGuiScreen(null);
                    stage = 30;
                    break;
                case 9:
                    click(screen, 33);
                    stage = 32;
                    break;
                case 32:
                    click(screen, 62);
                    stage = 31;
                    break;
                case 31:
                    require(state.getInteger("mode") == 0, "three_modes_grade_buttons_acknowledged");
                    click(screen, 50);
                    stage = 11;
                    break;
                case 11:
                    if (state.getInteger("job") != 3 && state.getInteger("job") != 5) return;
                    require(receipt().getInteger("placed") > 0, "direct_build_server_world_and_material_receipt");
                    require(mc.currentScreen == screen, "direct_build_retains_GUI_for_real_progress");
                    require(
                        state.getInteger("layerDuration") == 0 && state.getLong("due") == 0,
                        "direct_build_has_no_layer_animation_schedule");
                    click(screen, 60);
                    stage = 25;
                    break;
                case 25:
                    click(screen, 23);
                    stage = 26;
                    break;
                case 26:
                    click(screen, 62);
                    stage = 27;
                    break;
                case 27:
                    click(screen, 50);
                    stage = 17;
                    break;
                case 17:
                    boolean scannedNoChanges = state.getInteger("job") == 0 && state.hasKey("workTotal", 3)
                        && state.getInteger("workTotal") == 0;
                    if (state.getInteger("job") != 3 && state.getInteger("job") != 5 && !scannedNoChanges) return;
                    NBTTagCompound builtReceipt = receipt();
                    require(builtReceipt.getInteger("coils") == 16, "build_installs_sixteen_real_coils");
                    if (scannedNoChanges) {
                        require(
                            !screen.smokeHasControl(55),
                            "satisfied_rescan_has_zero_operations_and_no_execute_control");
                        require(
                            builtReceipt.getInteger("placed") + builtReceipt.getInteger("stock") == 192,
                            "satisfied_rescan_preserves_real_world_and_inventory_material_conservation");
                        System.out.println(
                            "[GTMB-HOLOGRAM-INTERACTION-CLIENT] NOOP receipt workTotal=0 coils="
                                + builtReceipt.getInteger("coils")
                                + " placed="
                                + builtReceipt.getInteger("placed")
                                + " stock="
                                + builtReceipt.getInteger("stock")
                                + "; closing review before real upgrade");
                        click(screen, 54);
                        stage = 33;
                        break;
                    }
                    click(screen, 71);
                    stage = 18;
                    break;
                case 33:
                    click(screen, 71);
                    stage = 18;
                    break;
                case 18:
                    click(screen, 82);
                    stage++;
                    break;
                case 19:
                    click(screen, 50);
                    stage = 21;
                    break;
                case 21:
                    if (state.getInteger("job") != 3 && state.getInteger("job") != 5) return;
                    require(
                        receipt().getInteger("coils") == 16 && receipt().getInteger("highCoils") == 16,
                        "upgrade_changes_all_sixteen_real_coils_and_preserves_material_conservation");
                    click(screen, 72);
                    stage++;
                    break;
                case 22:
                    click(screen, 50);
                    stage = 24;
                    break;
                case 24:
                    if (state.getInteger("job") != 3 && state.getInteger("job") != 5) return;
                    NBTTagCompound receipt = receipt();
                    require(
                        receipt.getInteger("placed") == 0 && receipt.getInteger("stock") == 192,
                        "dismantle_returns_all_shell_materials_exactly");
                    require(
                        receipt.getInteger("mufflerWorld") == 0 && receipt.getInteger("mufflerStock") == 1,
                        "dismantle_returns_single_real_mandatory_muffler_without_duplication");
                    require(
                        !state.getBoolean("targetClosed") && mc.theWorld.getTileEntity(48, 80, 48) != null,
                        "dismantle_preserves_controller_target");
                    stage = 40;
                    mc.displayGuiScreen(null);
                    break;
                case 47:
                    if (!awaitStoneArea(state)) return;
                    require(
                        state.getInteger("mode") == 0 && state.getInteger("scope") == 0,
                        "fresh_area_resets_previous_dismantling_mode_and_scope");
                    require(
                        state.getBoolean("virtual") && state.getTagList("cells", 10)
                            .tagCount() == (sufficientBlueprintPass ? 1 : 2),
                        "second_shift_right_imports_actual_world_blocks_without_Enter_sufficient_"
                            + sufficientBlueprintPass);
                    net.minecraft.nbt.NBTTagList importedStoneCells = state.getTagList("cells", 10);
                    java.util.Set<Integer> importedStoneX = new java.util.HashSet<>();
                    for (int i = 0; i < importedStoneCells.tagCount(); i++) {
                        NBTTagCompound cell = importedStoneCells.getCompoundTagAt(i);
                        int stoneX = cell.getInteger("x");
                        net.minecraft.item.ItemStack capturedTarget = net.minecraft.item.ItemStack
                            .loadItemStackFromNBT(cell.getCompoundTag("targetStack"));
                        require(
                            (stoneX == 50 || !sufficientBlueprintPass && stoneX == 51) && importedStoneX.add(stoneX)
                                && cell.getInteger("y") == 80
                                && cell.getInteger("z") == 44
                                && "minecraft:stone".equals(cell.getString("id"))
                                && cell.getInteger("meta") == 0
                                && "minecraft:stone".equals(cell.getString("wantId"))
                                && cell.getInteger("wantMeta") == 0
                                && "minecraft:stone".equals(cell.getString("wantId"))
                                && capturedTarget != null
                                && capturedTarget.stackSize == 1
                                && capturedTarget.getItem()
                                    == net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.stone)
                                && capturedTarget.getItemDamage() == 0,
                            "real_completed_area_contains_exact_source_stone_coordinate_" + i);
                    }
                    if (!(Boolean) observedField(screen, "libraryDrawer")) click(screen, 149);
                    areaProcessing = new HologramProcessingClientSmoke(
                        receipts,
                        "stone-" + sufficientBlueprintPass,
                        (processingScreen, id) -> {
                            click(processingScreen, id);
                            return true;
                        });
                    stage = 48;
                    break;
                case 48:
                    if (!areaProcessing.tick(screen)) return;
                    HologramMuiTextField name = (HologramMuiTextField) ((Object) HologramUiDiagnostics
                        .readDiagnosticField(screen, "presetNameField"));
                    int[] namePoint = (int[]) HologramUiDiagnostics.invokeDiagnostic(
                        screen,
                        "smokePreviewPoint",
                        new Class<?>[] { int.class, int.class },
                        name.xPosition + 4,
                        name.yPosition + 7);
                    HologramMuiProbeInput.click(screen, namePoint[0], namePoint[1], 0);
                    require(name.isFocused(), "actual_mouse_focuses_blueprint_name");
                    keyboard(Keyboard.KEY_LCONTROL, true);
                    HologramMuiProbeInput.key(screen, (char) 1, Keyboard.KEY_A);
                    keyboard(Keyboard.KEY_LCONTROL, false);
                    HologramMuiProbeInput.key(screen, '\b', Keyboard.KEY_BACK);
                    for (char character : (sufficientBlueprintPass ? "SocketSufficient" : "SocketV004").toCharArray())
                        HologramMuiProbeInput.key(screen, character, 0);
                    recordClickedFrame(screen);
                    stage = 68;
                    break;
                case 68:
                    click(screen, 140);
                    stage = 49;
                    break;
                case 49:
                    if (HologramLibraryClient.dedicated()) {
                        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
                        String waiting = "busy=" + library.busy()
                            + " rows="
                            + library.rows(false)
                                .tagCount()
                            + " local="
                            + library.selected(false)
                            + " serverLocal="
                            + state.getString("libraryLocalId")
                            + " serverPreset="
                            + state.getString("selectedPresetId")
                            + " status="
                            + library.status();
                        if (!waiting.equals(librarySaveWait)) {
                            librarySaveWait = waiting;
                            System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] LIBRARY-SAVE-WAIT " + waiting);
                            saveObservation("library-save-wait-client.nbt", screen.smokeLibraryUiState());
                        }
                        if (library.status()
                            .startsWith("服务器：")
                            || library.status()
                                .startsWith("客户端蓝图库："))
                            throw new IllegalStateException("Actual private save rejected: " + waiting);
                        if (library.busy() || library.selectedRow(false)
                            .getString("id")
                            .isEmpty()) return;
                        if (!state.getBoolean("blueprintSaved") || state.getBoolean("blueprintDirty")
                            || state.getBoolean("blueprintSavePending")) return;
                        require(
                            new File(mc.mcDataDir, "MiaoBlueprints/" + library.selected(false) + ".json").isFile(),
                            "actual_library_save_publishes_private_file_before_place_or_Alt");
                        require(
                            new File(mc.mcDataDir, "MiaoBlueprints/" + library.selected(false) + ".png").isFile(),
                            "actual_library_save_publishes_native_preview_before_place_or_Alt");
                        saveObservation("library-save-wait-client.nbt", screen.smokeLibraryUiState());
                        saveObservation(
                            "library-save-committed-" + sufficientBlueprintPass + "-client.nbt",
                            screen.smokeLibraryUiState());
                        saveObservation("library-save-committed-" + sufficientBlueprintPass + "-state.nbt", state);
                        if (Boolean.getBoolean("gtmb.hologram.librarySocketSmoke") && libraryUiDriver == null) {
                            libraryUiDriver = new HologramLibraryUiSocketSmoke(
                                receipts,
                                () -> airRightClick(mc, false),
                                () -> lastRenderedProjectionState == HologramClient.state && observedWorldGeometry > 0
                                    && observedWorldGhosts > 0);
                            return;
                        }
                    }
                    require(
                        !state.getString("selectedPresetId")
                            .isEmpty(),
                        "actual_library_save_creates_server_owned_blueprint");
                    if (sufficientBlueprintPass) {
                        quickPresetId = state.getString("selectedPresetId");
                        quickLocalId = HologramLibraryClient.dedicated()
                            ? HologramLibraryClient.INSTANCE.selected(false)
                            : state.getString("libraryLocalId");
                        require(libraryTab(screen), "saved_blueprint_shortcut_preserves_last_library_tab");
                        HologramMuiProbeInput.key(screen, 'e', mc.gameSettings.keyBindInventory.getKeyCode());
                        require(mc.currentScreen == null, "quick_place_starts_from_actual_closed_workspace");
                        keyboard(Keyboard.KEY_LMENU, true);
                        rightClickSource(mc);
                        keyboard(Keyboard.KEY_LMENU, false);
                    } else click(screen, 141);
                    stage = 50;
                    break;
                case 52:
                    require(
                        state.getBoolean("virtualLocked") && state.getBoolean("blueprintAwaitingConfirmation"),
                        "second_normal_right_opens_material_confirmation_for_locked_ghost");
                    require(libraryTab(screen), "position_review_preserves_last_library_peer_tab");
                    require(state.getLong("placementReviewNonce") > 0, "position_review_has_explicit_world_open_nonce");
                    require(
                        receipt().getInteger("blueprintPlaced") == (sufficientBlueprintPass ? 0 : 1),
                        "position_confirmation_never_constructs");
                    logConstructionScope(screen, "whole-blueprint-sufficient-or-cancel-review");
                    screenshot(
                        mc,
                        sufficientBlueprintPass ? "placement-precheck-sufficient-primary"
                            : "placement-precheck-cancel-keeps-draft");
                    if (sufficientBlueprintPass) {
                        require(
                            state.getInteger("missing") == 0,
                            "single_block_blueprint_has_sufficient_real_material");
                        click(screen, 55);
                        stage = 66;
                    } else {
                        click(screen, 54);
                        stage = 53;
                    }
                    break;
                case 53:
                    require(
                        state.getBoolean("virtual") && state.getTagList("cells", 10)
                            .tagCount() == 2,
                        "cancel_material_confirmation_preserves_two_cell_draft");
                    if (!(Boolean) observedField(screen, "libraryDrawer")) {
                        click(screen, 149);
                        break;
                    }
                    click(screen, 141);
                    stage = 54;
                    break;
                case 56:
                    require(libraryTab(screen), "second_position_review_preserves_last_library_peer_tab");
                    require(
                        state.getInteger("missing") == 1,
                        "real_blueprint_missing_material_requires_explicit_review");
                    logConstructionScope(screen, "whole-blueprint-first-missing-review");
                    screenshot(mc, "placement-missing-first-review");
                    click(screen, 55);
                    stage = 57;
                    break;
                case 57:
                    require(
                        state.getBoolean("jobPartialPlacement") && !state.getBoolean("blueprintAwaitingConfirmation"),
                        "one_missing_material_confirmation_admits_reviewed_partial_job");
                    require(
                        !(Boolean) observedField(screen, "confirmation"),
                        "accepted_partial_review_does_not_reopen");
                    stage = 58;
                    break;
                case 58:
                    if (state.getInteger("job") != 3 && state.getInteger("job") != 5) return;
                    require(libraryTab(screen), "partial_placement_completion_preserves_library_peer_tab");
                    require(
                        state.getBoolean("jobPartialPlacement") && state.getInteger("completed") == 1
                            && state.getInteger("jobMissingCells") == 1
                            && state.getInteger("jobSkippedCells") == 1
                            && state.getInteger("jobProtectedCells") == 0
                            && state.getInteger("jobUnsupportedCells") == 0,
                        "actual_partial_primary_button_reports_one_placed_one_missing_without_hidden_protection_or_unsupported_failure");
                    NBTTagCompound blueprintReceipt = receipt();
                    require(
                        blueprintReceipt.getInteger("blueprintPlaced") == 2
                            && blueprintReceipt.getInteger("blueprintStock") == 0,
                        "second_explicit_missing_review_builds_one_real_block_and_consumes_exactly_one_item");
                    logConstructionScope(screen, "whole-blueprint-real-partial-placement-complete");
                    screenshot(mc, "v004-area-ghost-material-review");
                    beginRepeatCompletedPlacement(mc, screen, state, 87);
                    break;
                case 87:
                    if (Boolean.getBoolean("gtmb.hologram.aeRealInteractionSmoke")) {
                        aeDriver = new HologramAeInteractionClientSmoke(receipts);
                        stage = 100;
                        break;
                    }
                    Files.write(
                        new File(receipts, "client-complete.txt").toPath(),
                        "real socket C08 + GUI mouse + tabs/Esc/E + build/upgrade/dismantle + Shift-right area finish + Alt-right saved preset ghost + green/right cyan/Shift-right green/right cyan/RIGHT2 review + one-confirm sufficient/material cancel/explicit missing confirmation PASS\n"
                            .getBytes(StandardCharsets.UTF_8));
                    done = true;
                    System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] FINAL PASS stage=" + stage);
                    mc.shutdown();
                    break;
                case 66:
                    if (state.getInteger("job") != 3 && state.getInteger("job") != 5) return;
                    require(libraryTab(screen), "sufficient_placement_completion_preserves_library_peer_tab");
                    NBTTagCompound sufficientReceipt = receipt();
                    require(
                        sufficientReceipt.getBoolean("sufficientBlueprintJobStarted"),
                        "single_primary_confirmation_starts_real_server_blueprint_job_one");
                    require(
                        sufficientReceipt.getInteger("blueprintPlaced") == 1
                            && sufficientReceipt.getInteger("blueprintStock") == 1,
                        "one_primary_confirmation_builds_one_real_block_and_consumes_one_material");
                    screenshot(mc, "v004-single-confirm-sufficient-placement");
                    sufficientBlueprintPass = false;
                    beginRepeatCompletedPlacement(mc, screen, state, 40);
                    break;
                default:
                    throw new IllegalStateException("unknown stage " + stage);
            }
        } catch (AwaitReceipt pendingReceipt) {
            // A Windows receipt publication can briefly replace the target; preserve this driver stage.
        } catch (AwaitDraw pendingDraw) {
            // A hidden client can tick many times before drawing its next frame.
        } catch (Throwable failure) {
            done = true;
            if (receipts != null) try {
                Files.write(
                    new File(receipts, "client-failed.txt").toPath(),
                    ("stage=" + stage + " " + failure).getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException receiptFailure) {
                receiptFailure.printStackTrace();
            }
            System.err.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] FINAL FAIL stage=" + stage + " " + failure);
            failure.printStackTrace();
            mc.shutdown();
        }
    }

    private static String disconnectReason(net.minecraft.client.gui.GuiDisconnected screen) throws Exception {
        String title = "";
        net.minecraft.util.IChatComponent reason = null;
        for (java.lang.reflect.Field field : net.minecraft.client.gui.GuiDisconnected.class.getDeclaredFields()) {
            if (net.minecraft.util.IChatComponent.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                reason = (net.minecraft.util.IChatComponent) field.get(screen);
            } else if ("field_146306_a".equals(field.getName())) {
                field.setAccessible(true);
                title = String.valueOf(field.get(screen));
            }
        }
        if (reason == null) throw new IllegalStateException("GuiDisconnected reason component missing");
        return "title=" + title + " reason=" + reason.getUnformattedText() + " component=" + reason;
    }

    private void crafting(Minecraft mc) throws Exception {
        if (++waitTicks < 4) return;
        waitTicks = 0;
        if (stage == 75) {
            if (renderedWorldFrames <= closedAtWorldFrame) return;
            require(
                mc.currentScreen == null && isCraftedProjector(mc.thePlayer.getHeldItem()),
                "actual_crafted_projector_is_held_for_first_world_entry");
            mc.thePlayer.setSneaking(false);
            mc.thePlayer.sendQueue
                .addToSendQueue(new net.minecraft.network.play.client.C0BPacketEntityAction(mc.thePlayer, 2));
            screenshot(mc, "crafted-projector-first-world-entry");
            reopenWithRightClick(mc);
            stage = 2;
            return;
        }
        require(
            mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiInventory,
            "actual_2x2_inventory_screen_remains_open_stage_" + stage);
        require(
            mc.thePlayer.openContainer == mc.thePlayer.inventoryContainer && mc.thePlayer.openContainer.windowId == 0,
            "actual_crafting_uses_vanilla_player_container_window_zero_stage_" + stage);
        net.minecraft.inventory.Container container = mc.thePlayer.openContainer;
        net.minecraft.item.ItemStack cursor = mc.thePlayer.inventory.getItemStack();
        switch (stage) {
            case 70:
                require(
                    isOfficial(
                        container.getSlot(36)
                            .getStack())
                        && container.getSlot(36)
                            .getStack().stackSize == 1,
                    "actual_hotbar_contains_only_one_official_crafting_source");
                require(
                    cursor == null && container.getSlot(0)
                        .getStack() == null,
                    "empty_crafting_grid_has_no_spawned_GTMB_output");
                screenshot(mc, "crafting-official-source-in-inventory");
                inventoryMouseClick(mc, 36);
                stage = 71;
                break;
            case 71:
                if (!isOfficial(cursor)) return;
                require(
                    cursor.stackSize == 1 && container.getSlot(36)
                        .getStack() == null,
                    "actual_C0E_pickup_moves_source_to_mouse_cursor");
                inventoryMouseClick(mc, 1);
                stage = 72;
                break;
            case 72:
                if (cursor != null || !isOfficial(
                    container.getSlot(1)
                        .getStack())
                    || !isCraftedProjector(
                        container.getSlot(0)
                            .getStack()))
                    return;
                NBTTagCompound preview = receipt();
                if (!preview.getBoolean("oneToOneCraftPreview") || !preview.getBoolean("craftOutputReady")) return;
                require(
                    preview.getInteger("officialCraftInput") == 1 && preview.getInteger("craftedProjectors") == 0,
                    "server_observes_one_official_2x2_input_and_one_derived_output_before_pickup");
                screenshot(mc, "crafting-one-source-one-result");
                inventoryMouseClick(mc, 0);
                stage = 73;
                break;
            case 73:
                if (!isCraftedProjector(cursor)) return;
                require(
                    cursor.stackSize == 1 && container.getSlot(1)
                        .getStack() == null
                        && container.getSlot(0)
                            .getStack() == null,
                    "actual_output_pickup_consumes_source_and_returns_exactly_one_projector");
                inventoryMouseClick(mc, 36);
                stage = 74;
                break;
            case 74:
                if (cursor != null || !isCraftedProjector(mc.thePlayer.getHeldItem())) return;
                NBTTagCompound crafted = receipt();
                if (crafted.getInteger("craftingEvents") != 1) return;
                require(
                    crafted.getInteger("officialRemaining") == 0 && crafted.getInteger("craftedProjectors") == 1
                        && crafted.getInteger("officialCraftInput") == 0,
                    "real_server_ItemCraftedEvent_and_inventory_prove_one_to_one_source_consumption");
                String displayName = mc.thePlayer.getHeldItem()
                    .getDisplayName();
                require(
                    !displayName.trim()
                        .isEmpty()
                        && !displayName.equals(
                            mc.thePlayer.getHeldItem()
                                .getUnlocalizedName() + ".name"),
                    "actual_crafted_projector_has_resolved_localized_name");
                System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS crafted item name=" + displayName);
                screenshot(mc, "crafting-crafted-projector-on-hotbar");
                closedAtWorldFrame = renderedWorldFrames;
                mc.displayGuiScreen(null);
                stage = 75;
                break;
            default:
                throw new IllegalStateException("unknown actual crafting stage " + stage);
        }
    }

    private void inventoryMouseClick(Minecraft mc, int slotIndex) throws Exception {
        net.minecraft.client.gui.inventory.GuiInventory inventory = (net.minecraft.client.gui.inventory.GuiInventory) mc.currentScreen;
        java.lang.reflect.Field left = net.minecraft.client.gui.inventory.GuiContainer.class
            .getDeclaredField("guiLeft");
        java.lang.reflect.Field top = net.minecraft.client.gui.inventory.GuiContainer.class.getDeclaredField("guiTop");
        left.setAccessible(true);
        top.setAccessible(true);
        net.minecraft.inventory.Slot slot = mc.thePlayer.openContainer.getSlot(slotIndex);
        int x = left.getInt(inventory) + slot.xDisplayPosition + 8;
        int y = top.getInt(inventory) + slot.yDisplayPosition + 8;
        java.lang.reflect.Method press = net.minecraft.client.gui.inventory.GuiContainer.class
            .getDeclaredMethod("mouseClicked", int.class, int.class, int.class);
        java.lang.reflect.Method release = net.minecraft.client.gui.inventory.GuiContainer.class
            .getDeclaredMethod("mouseMovedOrUp", int.class, int.class, int.class);
        press.setAccessible(true);
        release.setAccessible(true);
        press.invoke(inventory, x, y, 0);
        release.invoke(inventory, x, y, 0);
        System.out.println(
            "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS actual inventory mouse slot=" + slotIndex
                + " window=0 position="
                + x
                + ","
                + y
                + " stage="
                + stage);
    }

    private static boolean isOfficial(net.minecraft.item.ItemStack stack) {
        return stack != null
            && stack.getItem() == com.gtnewhorizon.structurelib.StructureLibAPI.getDefaultHologramItem();
    }

    private static boolean isCraftedProjector(net.minecraft.item.ItemStack stack) {
        return stack != null
            && stack.getItem() == net.minecraft.item.Item.itemRegistry.getObject("gtmb:neko_hologram_projector");
    }

    private static int coilGrade(NBTTagCompound state) {
        net.minecraft.nbt.NBTTagList capabilities = state.getTagList("capabilities", 10);
        for (int i = 0; i < capabilities.tagCount(); i++) {
            NBTTagCompound row = capabilities.getCompoundTagAt(i);
            if ("coil".equals(row.getString("id"))) return row.getInteger("current");
        }
        throw new IllegalStateException("Real EBF fixture has no native coil capability");
    }

    private static boolean libraryTab(HologramScreen screen) throws ReflectiveOperationException {

        return ((Boolean) HologramUiDiagnostics.readDiagnosticField(screen, "blueprintTab"));
    }

    private int[] selectPlainPreviewFace(HologramScreen screen, NBTTagCompound snapshot) throws Exception {
        HologramState state = new HologramState(snapshot);

        for (int y = HologramScreenLayout.VIEWPORT_Y + 40; y
            < HologramScreenLayout.VIEWPORT_Y + HologramScreenLayout.VIEWPORT_HEIGHT - 50; y += 2)
            for (int x = HologramScreenLayout.VIEWPORT_X + 10; x
                < HologramScreenLayout.VIEWPORT_X + HologramScreenLayout.VIEWPORT_WIDTH - 10; x += 2) {
                    int index = HologramRenderer.pick(x, y);
                    if (index < 0 || !HologramRenderer.pickStructure(x, y)
                        .isEmpty()) continue;
                    HologramState.Cell cell = state.cells.get(index);
                    if (!cell.family.isEmpty() || cell.targetStack == null) continue;
                    int mouseX = ((Integer) HologramUiDiagnostics.readDiagnosticField(screen, "originX"))
                        + Math.round(x * ((Float) HologramUiDiagnostics.readDiagnosticField(screen, "scale")));
                    int mouseY = ((Integer) HologramUiDiagnostics.readDiagnosticField(screen, "originY"))
                        + Math.round(y * ((Float) HologramUiDiagnostics.readDiagnosticField(screen, "scale")));
                    HologramMuiProbeInput.click(screen, mouseX, mouseY, 0);
                    int[] selected = screen.smokeSelectedIndices();
                    require(
                        selected.length == 1 && selected[0] == index,
                        "actual_plain_preview_mouse_selects_one_cell");
                    return selected;
                }
        throw new IllegalStateException("Real EBF preview has no plain face for root-exit selection persistence");
    }

    private int[] selectCoilPreviewFamily(HologramScreen screen, NBTTagCompound snapshot) throws Exception {
        HologramState state = new HologramState(snapshot);
        for (int y = HologramScreenLayout.VIEWPORT_Y + 40; y
            < HologramScreenLayout.VIEWPORT_Y + HologramScreenLayout.VIEWPORT_HEIGHT - 50; y += 2)
            for (int x = HologramScreenLayout.VIEWPORT_X + 10; x
                < HologramScreenLayout.VIEWPORT_X + HologramScreenLayout.VIEWPORT_WIDTH - 10; x += 2) {
                    int index = HologramRenderer.pick(x, y);
                    if (index < 0 || !HologramRenderer.pickStructure(x, y)
                        .isEmpty() || !"coil".equals(state.cells.get(index).family)) continue;
                    previewMouseClick(screen, x, y, 0);
                    int[] selected = screen.smokeSelectedIndices();
                    require(
                        selected.length == 16 && coilSelectionCoordinates(snapshot, selected).size() == 16,
                        "actual_coil_face_mouse_click_automatically_selects_sixteen_same_family_cells");
                    return selected;
                }
        throw new IllegalStateException("Real EBF preview has no visible coil face for repeated-grade interaction");
    }

    private java.util.Set<String> coilSelectionCoordinates(NBTTagCompound snapshot, int[] selected) {
        HologramState state = new HologramState(snapshot);
        java.util.Set<String> coordinates = new java.util.LinkedHashSet<>();
        for (int index : selected) {
            if (index < 0 || index >= state.cells.size())
                throw new IllegalStateException("Coil selection index outside current native snapshot: " + index);
            HologramState.Cell cell = state.cells.get(index);
            if (!"coil".equals(cell.family)) throw new IllegalStateException(
                "Clicked grade cohort contains another family: index=" + index + " family=" + cell.family);
            if (!coordinates.add(cell.x + "," + cell.y + "," + cell.z)) throw new IllegalStateException(
                "Clicked grade cohort contains duplicate world coordinates at index=" + index);
        }
        require(coordinates.size() == 16, "clicked_grade_cohort_keeps_all_sixteen_world_positions");
        return coordinates;
    }

    private void assertNoItemPalette(HologramScreen screen) throws ReflectiveOperationException {
        for (String fieldName : new String[] { "inventory", "hatchDrawer", "sourceDrawer" }) {

            require(
                !((Boolean) HologramUiDiagnostics.readDiagnosticField(screen, fieldName)),
                "coil_grade_selection_does_not_open_item_palette_" + fieldName);
        }
        for (String fieldName : new String[] { "inventorySlot", "sourceChoice" }) {

            require(
                ((Integer) HologramUiDiagnostics.readDiagnosticField(screen, fieldName)) == -1,
                "coil_grade_selection_does_not_choose_item_palette_slot_" + fieldName);
        }
    }

    private static void logConstructionScope(HologramScreen screen, String step) {
        System.out.println(
            "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS actual construction scope " + step
                + " "
                + screen.smokeConstructionScopeContext());
    }

    private static void previewMouseClick(HologramScreen screen, int logicalX, int logicalY, int button)
        throws Exception {

        int x = ((Integer) HologramUiDiagnostics.readDiagnosticField(screen, "originX"))
            + Math.round(logicalX * ((Float) HologramUiDiagnostics.readDiagnosticField(screen, "scale")));
        int y = ((Integer) HologramUiDiagnostics.readDiagnosticField(screen, "originY"))
            + Math.round(logicalY * ((Float) HologramUiDiagnostics.readDiagnosticField(screen, "scale")));
        HologramMuiProbeInput.click(screen, x, y, button);
    }

    private void beginRepeatCompletedPlacement(Minecraft mc, HologramScreen screen, NBTTagCompound state, int nextStage)
        throws Exception {
        completedSession = state.getString("session");
        completedPreset = state.getString("selectedPresetId");
        completedLocalId = state.getString("libraryLocalId");
        completedClientActor = mc.thePlayer.getUniqueID()
            .toString();
        NBTTagCompound completedServer = receipt().getCompoundTag("blueprintSession");
        completedActor = completedServer.getString("actor");
        require(
            completedSession.equals(completedServer.getString("id"))
                && completedPreset.equals(completedServer.getString("presetId"))
                && java.util.UUID.fromString(completedActor)
                    .toString()
                    .equals(completedActor),
            "completed_session_and_preset_bind_actual_canonical_server_actor");
        System.out.println(
            "[GTMB-HOLOGRAM-INTERACTION-CLIENT] POSTCOMPLETE-ACTOR-BINDING clientLocalUUID=" + completedClientActor
                + " canonicalServerActor="
                + completedActor
                + " session="
                + completedSession);
        completedStoneAnchor = expectedAirAnchor.clone();
        completedPlaced = receipt().getInteger("blueprintPlaced");
        completedStock = receipt().getInteger("blueprintStock");
        afterRepeatStage = nextStage;
        repeatStartedAt = System.nanoTime();
        repeatCancelledTick = receipt().getLong("tick");
        aimedProjectionGeneration = "";
        saveObservation("post-complete-Alt-before-receipt-" + nextStage + ".nbt", receipt());
        HologramMuiProbeInput.key(screen, 'e', mc.gameSettings.keyBindInventory.getKeyCode());
        require(mc.currentScreen == null, "completed_placement_E_returns_to_world_before_Alt_repeat");
        stage = 83;
        waitTicks = 0;
    }

    private boolean repeatCompletedPlacement(Minecraft mc) throws Exception {
        if (System.nanoTime() - repeatStartedAt > 15000000000L)
            throw movingWorldTimeout(mc, "post-completion physical Alt/Escape regression exceeded fifteen seconds");
        NBTTagCompound state = HologramClient.state == null ? null : HologramClient.state.data;
        if (stage == 83 || stage == 84 || stage == 85 || stage == 86 || stage == 88 || stage == 90)
            require(mc.currentScreen == null, "post_complete_Alt_or_actual_E_close_keeps_world_closed_stage_" + stage);
        require(
            completedClientActor.equals(
                mc.thePlayer.getUniqueID()
                    .toString()),
            "post_complete_Alt_repeat_preserves_same_actual_player_UUID");
        require(
            completedActor.equals(
                receipt().getCompoundTag("blueprintSession")
                    .getString("actor")),
            "post_complete_all_independent_receipts_preserve_same_canonical_server_actor");
        require(
            receipt().getInteger("blueprintPlaced") == completedPlaced
                && receipt().getInteger("blueprintStock") == completedStock,
            "post_complete_Alt_Escape_never_places_or_consumes_another_material_stage_" + stage);
        require(
            mc.theWorld.getBlock(completedStoneAnchor[0], completedStoneAnchor[1], completedStoneAnchor[2])
                == net.minecraft.init.Blocks.stone,
            "post_complete_Alt_Escape_preserves_actual_previously_paid_stone_stage_" + stage);
        if (stage == 83) {
            if (receipt().getLong("tick") - repeatCancelledTick < 10) return true;
            keyboard(Keyboard.KEY_LMENU, true);
            rightClickSource(mc);
            keyboard(Keyboard.KEY_LMENU, false);
            saveObservation(
                "post-complete-Alt-dispatch-" + afterRepeatStage + ".nbt",
                HologramLibraryClient.INSTANCE.smokeQuickState());
            repeatFrame = renderedWorldFrames;
            stage = 84;
            return true;
        }
        if (stage == 84) {
            if (state == null || !state.getBoolean("moving") || !state.getBoolean("virtualPlacement")) return true;
            require(
                !state.getBoolean("virtualLocked") && !state.getBoolean("open")
                    && !state.getBoolean("blueprintAwaitingConfirmation")
                    && state.getInteger("job") == 0,
                "post_complete_Alt_starts_unpaid_unlocked_world_ghost_without_job");
            require(
                !completedSession.equals(state.getString("session"))
                    && (HologramLibraryClient.dedicated()
                        ? !completedLocalId.isEmpty() && completedLocalId.equals(state.getString("libraryLocalId"))
                            && state.getString("selectedPresetId")
                                .startsWith("private_")
                        : completedPreset.equals(state.getString("selectedPresetId"))),
                "post_complete_Alt_uses_exact_saved_preset_in_new_server_session");
            if (HologramLibraryClient.dedicated()) completedPreset = state.getString("selectedPresetId");
            repeatSession = state.getString("session");
            repeatNonce = state.getLong("placementReviewNonce");
            require(repeatNonce == 0, "fresh_unlocked_Alt_session_has_no_review_nonce");
            if (!repeatSession.equals(
                receipt().getCompoundTag("blueprintSession")
                    .getString("id")))
                return true;
            if (!awaitAimedMovingWorldFrame(mc)) return true;
            require(
                HologramRenderer.lastWorldGeometry > 0 && HologramRenderer.lastWorldGhosts > 0
                    && HologramRenderer.lastWorldPlacementOutlineColor == 0x70e69a,
                "post_complete_Alt_new_world_ghost_is_actually_rendered_green");
            screenshot(mc, "v007-post-complete-Alt-green-" + afterRepeatStage);
            saveObservation("post-complete-Alt-green-receipt-" + afterRepeatStage + ".nbt", receipt());
            stage = 85;
            return true;
        }
        if (stage == 85) {
            repeatCancelledTick = receipt().getLong("tick");
            repeatCancelAckTick = -1;
            mc.displayGuiScreen(new net.minecraft.client.gui.GuiIngameMenu());
            require(mc.currentScreen == null, "post_complete_repeat_Escape_cancels_without_pause_menu");
            stage = 86;
            return true;
        }
        if (stage == 86 || stage == 89) {
            if (state == null || state.getBoolean("moving") || state.getBoolean("virtualPlacement")) return true;
            if (stage == 89 && !(mc.currentScreen instanceof HologramScreen)) return true;
            HologramScreen draft = stage == 89 ? (HologramScreen) mc.currentScreen : null;
            require(
                repeatSession.equals(state.getString("session"))
                    && completedPreset.equals(state.getString("selectedPresetId"))
                    && !state.getBoolean("virtualLocked")
                    && !state.getBoolean("blueprintAwaitingConfirmation")
                    && state.getInteger("job") == 0
                    && state.getLong("placementReviewNonce") == repeatNonce
                    && (stage == 86 ? mc.currentScreen == null && !state.getBoolean("open")
                        : !(Boolean) observedField(draft, "confirmation") && libraryTab(draft)),
                "world_Escape_keeps_world_closed_then_air_right_reopens_same_draft_without_review_authority");
            NBTTagCompound independent = receipt().getCompoundTag("blueprintSession");
            if (!awaitCancelledReceipt(repeatSession, state, repeatCancelledTick)) return true;
            System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] CANCEL-RECEIPT-PREDICATES stage=" + stage
                    + " idExpected="
                    + repeatSession
                    + " idActual="
                    + independent.getString("id")
                    + " presetExpected="
                    + completedPreset
                    + " presetActual="
                    + independent.getString("presetId")
                    + " serverActorExpected="
                    + completedActor
                    + " serverActorActual="
                    + independent.getString("actor")
                    + " clientLocalUUID="
                    + completedClientActor
                    + " placementExpected=false actual="
                    + independent.getBoolean("virtualPlacement")
                    + " lockedExpected=false actual="
                    + independent.getBoolean("virtualLocked")
                    + " awaitingExpected=false actual="
                    + independent.getBoolean("blueprintAwaitingConfirmation")
                    + " issuedExpected=false actual="
                    + independent.getBoolean("placementReviewIssued")
                    + " jobExpected=0 actual="
                    + independent.getInteger("job")
                    + " nonceExpected=0 actual="
                    + independent.getLong("placementReviewNonce"));
            require(repeatSession.equals(independent.getString("id")), "cancelled_receipt_matches_exact_quick_session");
            require(
                completedPreset.equals(independent.getString("presetId")),
                "cancelled_receipt_matches_exact_saved_preset");
            require(
                completedActor.equals(independent.getString("actor")),
                "cancelled_receipt_matches_same_canonical_server_actor");
            require(!independent.getBoolean("virtualPlacement"), "cancelled_receipt_has_no_placement_mode");
            require(!independent.getBoolean("virtualLocked"), "cancelled_receipt_has_no_locked_position");
            require(
                !independent.getBoolean("blueprintAwaitingConfirmation"),
                "cancelled_receipt_has_no_awaiting_confirmation");
            require(!independent.getBoolean("placementReviewIssued"), "cancelled_receipt_has_no_review_capability");
            require(independent.getInteger("job") == 0, "cancelled_receipt_has_no_job");
            require(independent.getLong("placementReviewNonce") == 0, "cancelled_fresh_quick_session_has_zero_nonce");
            require(
                repeatSession.equals(independent.getString("id")) && !independent.getBoolean("virtualPlacement")
                    && completedPreset.equals(independent.getString("presetId"))
                    && completedActor.equals(independent.getString("actor"))
                    && !independent.getBoolean("virtualLocked")
                    && !independent.getBoolean("blueprintAwaitingConfirmation")
                    && !independent.getBoolean("placementReviewIssued")
                    && independent.getInteger("job") == 0
                    && independent.getLong("placementReviewNonce") == 0,
                "cancelled_Alt_draft_has_no_independent_server_placement_authority");
            if (stage == 86) {
                if (repeatCancelAckTick < 0) repeatCancelAckTick = receipt().getLong("tick");
                if (receipt().getLong("tick") - repeatCancelAckTick < 10) return true;
                screenshot(mc, "v007-post-complete-Alt-Escape-preserves-paid-world-" + afterRepeatStage);
                saveObservation("post-complete-Alt-Escape-receipt-" + afterRepeatStage + ".nbt", receipt());
                require(mc.currentScreen == null, "cancelled_Alt_draft_Escape_stays_world_only_for_ten_ticks");
                repeatCancelledTick = receipt().getLong("tick");
                stage = 88;
                return true;
            }
            screenshot(mc, "v007-post-complete-draft-E-close-right-reopen-" + afterRepeatStage);
            System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS post-complete Alt new-green-session same-player/preset Escape world-only10ticks air-right-reopen paid-world/resources intact placed="
                    + completedPlaced
                    + " stock="
                    + completedStock
                    + " next="
                    + afterRepeatStage);
            clearClickedFrame();
            waitTicks = 0;
            if (afterRepeatStage == 40) {
                HologramMuiProbeInput.key(draft, 'e', mc.gameSettings.keyBindInventory.getKeyCode());
                require(mc.currentScreen == null, "sufficient_repeat_reopened_draft_actual_E_closes_before_next_area");
                repeatCancelledTick = receipt().getLong("tick");
                stage = 90;
            } else stage = 87;
            return true;
        }
        if (stage == 88 || stage == 90) {
            if (receipt().getLong("tick") - repeatCancelledTick < 10) return true;
            if (stage == 88) {
                mc.objectMouseOver = null;
                airRightClick(mc, false);
                stage = 89;
            } else stage = 40;
            return true;
        }
        return true;
    }

    /** Socket ACK and the independent disk publication are separate observations of the actual server state. */
    private boolean awaitCancelledReceipt(String session, NBTTagCompound acknowledged, long beforeActionTick)
        throws Exception {
        NBTTagCompound actual = receipt();
        NBTTagCompound server = actual.getCompoundTag("blueprintSession");
        boolean current = session.equals(server.getString("id")) && actual.getLong("tick") > beforeActionTick
            && server.getLong("uiSequence") >= acknowledged.getLong("uiSequence")
            && server.getLong("planRevision") >= acknowledged.getLong("planRevision")
            && !server.getBoolean("virtualPlacement");
        if (!current) {
            String waiting = "stage=" + stage
                + " receiptTick="
                + actual.getLong("tick")
                + " afterActionTick="
                + beforeActionTick
                + " receiptSession="
                + server.getString("id")
                + " expectedSession="
                + session
                + " uiSequence="
                + server.getLong("uiSequence")
                + "/"
                + acknowledged.getLong("uiSequence")
                + " revision="
                + server.getLong("planRevision")
                + "/"
                + acknowledged.getLong("planRevision")
                + " placement="
                + server.getBoolean("virtualPlacement");
            if (!waiting.equals(cancelledReceiptWait)) {
                cancelledReceiptWait = waiting;
                System.out
                    .println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] WAIT independent cancellation receipt " + waiting);
            }
            return false;
        }
        cancelledReceiptWait = "";
        return true;
    }

    private static String projectionGeneration(HologramState snapshot) {
        if (snapshot == null) return "none";
        NBTTagCompound data = snapshot.data;
        return snapshot.session + ":"
            + data.getLong("batchSeq")
            + ":"
            + data.getLong("planRevision")
            + ":"
            + data.getInteger("x")
            + ","
            + data.getInteger("y")
            + ","
            + data.getInteger("z")
            + ":moving="
            + data.getBoolean("moving")
            + ":locked="
            + data.getBoolean("virtualLocked");
    }

    /** Aim through the normal camera/scroll path, then wait for that generation and anchor's actual world mesh. */
    private boolean awaitAimedMovingWorldFrame(Minecraft mc) throws Exception {
        HologramState latest = HologramClient.state;
        if (Boolean.getBoolean("gtmb.hologram.v010MovingWheelSmoke") && !v010WheelChecked) {
            HologramV010GuiSmoke.assertMovingWheel(mc);
            v010WheelChecked = true;
        }
        String generation = projectionGeneration(latest);
        NBTTagCompound currentCamera = cameraObservation(mc, latest);
        mc.objectMouseOver = null;
        if (!generation.equals(aimedProjectionGeneration)) {
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = -60;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -30;
            int[] beforeWheel = HologramClient.movingAnchor(latest);
            mouseWheel(120);
            mouseWheel(120);
            aimedMovingAnchor = HologramClient.movingAnchor(latest);
            require(
                !java.util.Arrays.equals(beforeWheel, aimedMovingAnchor),
                "fresh_moving_ghost_actual_scroll_changes_air_target");
            require(
                aimedMovingAnchor[1] > net.minecraft.util.MathHelper.floor_double(mc.thePlayer.posY)
                    && mc.theWorld.blockExists(aimedMovingAnchor[0], aimedMovingAnchor[1], aimedMovingAnchor[2])
                    && mc.theWorld.isAirBlock(aimedMovingAnchor[0], aimedMovingAnchor[1], aimedMovingAnchor[2]),
                "fresh_moving_ghost_camera_aims_loaded_empty_air_above_player");
            aimedProjectionGeneration = generation;
            aimedWorldFrame = renderedWorldFrames;
            aimedConsumedGeneration = lastConsumedWorldWitness == null ? -1 : lastConsumedWorldWitness.generation;
            aimedCameraObservation = cameraObservation(mc, latest);
            observedToolCopyStack = aimedCameraObservation.getInteger("stackIdentity");
            return false;
        }
        int[] latestAnchor = HologramClient.movingAnchor(latest);
        NBTTagCompound latestCamera = cameraObservation(mc, latest);
        if (aimedCameraObservation != null && !aimedCameraObservation.getString("toolUuid")
            .isEmpty()
            && aimedCameraObservation.getString("toolUuid")
                .equals(latestCamera.getString("toolUuid"))
            && aimedCameraObservation.getInteger("heldItemIdentity") == latestCamera.getInteger("heldItemIdentity")
            && aimedCameraObservation.getInteger("heldDamage") == latestCamera.getInteger("heldDamage")
            && aimedCameraObservation.getInteger("heldSlot") == latestCamera.getInteger("heldSlot")
            && latestCamera.getInteger("stackIdentity") != observedToolCopyStack) {
            require(
                aimedCameraObservation.getInteger("previewDistance") == latestCamera.getInteger("previewDistance"),
                "same_logical_projector_native_stack_synchronization_preserves_actual_scroll_distance");
            NBTTagCompound copy = new NBTTagCompound();
            copy.setTag("before", aimedCameraObservation.copy());
            copy.setTag("after", latestCamera.copy());
            copy.setBoolean("sameLogicalTool", true);
            copy.setBoolean("distancePreserved", true);
            saveObservation("stage-" + stage + "-logical-tool-copy-preview.nbt", copy);
            observedToolCopyStack = latestCamera.getInteger("stackIdentity");
        }
        if (!java.util.Arrays.equals(aimedMovingAnchor, latestAnchor)) {
            NBTTagCompound changed = new NBTTagCompound();
            changed.setInteger("stage", stage);
            changed.setString("generation", generation);
            changed.setIntArray("preparedAnchor", aimedMovingAnchor);
            changed.setIntArray("latestAnchor", latestAnchor);
            if (lastRenderedMovingAnchor != null) changed.setIntArray("consumedAnchor", lastRenderedMovingAnchor);
            if (aimedCameraObservation != null) changed.setTag("preparedCamera", aimedCameraObservation.copy());
            changed.setTag("beforeAirGesture", currentCamera);
            changed.setTag("currentCamera", latestCamera.copy());
            if (renderedCameraObservation != null) changed.setTag("renderedCamera", renderedCameraObservation.copy());
            saveObservation("stage-" + stage + "-moving-target-change.nbt", changed);
            System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] WAIT latest camera target changed stage=" + stage
                    + " prepared="
                    + java.util.Arrays.toString(aimedMovingAnchor)
                    + " latest="
                    + java.util.Arrays.toString(latestAnchor));
            aimedMovingAnchor = latestAnchor.clone();
            aimedWorldFrame = renderedWorldFrames;
            aimedConsumedGeneration = lastConsumedWorldWitness == null ? -1 : lastConsumedWorldWitness.generation;
            aimedCameraObservation = cameraObservation(mc, latest);
            return false;
        }
        if (renderedWorldFrames <= aimedWorldFrame || lastConsumedWorldWitness == null
            || lastConsumedWorldWitness.generation <= aimedConsumedGeneration
            || lastConsumedWorldWitness.state != latest
            || !latest.session.equals(lastConsumedWorldWitness.session)
            || latest.data.getLong("planRevision") != lastConsumedWorldWitness.revision
            || !generation.equals(projectionGeneration(lastRenderedProjectionState))
            || !java.util.Arrays.equals(aimedMovingAnchor, lastRenderedMovingAnchor)
            || observedWorldGeometry <= 0
            || observedWorldGhosts <= 0
            || observedWorldOutline != 0x70e69a) return false;
        require(
            java.util.Arrays.equals(aimedMovingAnchor, HologramClient.movingAnchor(latest)),
            "latest_moving_snapshot_camera_target_equals_actual_rendered_world_anchor");
        require(
            HologramRenderer.lastWorldGeometry > 0 && HologramRenderer.lastWorldGhosts > 0
                && HologramRenderer.lastWorldPlacementOutlineColor == 0x70e69a,
            "latest_aimed_world_frame_has_actual_ghost_geometry_and_green_outline");
        NBTTagCompound consumed = new NBTTagCompound();
        consumed.setBoolean("actualGlListConsumed", true);
        consumed.setLong("consumedGeneration", lastConsumedWorldWitness.generation);
        consumed.setString("session", lastConsumedWorldWitness.session);
        consumed.setLong("planRevision", lastConsumedWorldWitness.revision);
        consumed.setInteger("stateIdentity", System.identityHashCode(latest));
        consumed.setInteger("consumedStateIdentity", System.identityHashCode(lastConsumedWorldWitness.state));
        consumed.setIntArray("cameraAnchor", latestAnchor);
        consumed.setIntArray("consumedAnchor", lastRenderedMovingAnchor);
        consumed.setInteger("lists", lastConsumedWorldWitness.lists);
        consumed.setInteger("geometry", observedWorldGeometry);
        consumed.setInteger("ghosts", observedWorldGhosts);
        consumed.setInteger("outlineColor", observedWorldOutline);
        consumed.setTag("camera", latestCamera.copy());
        saveObservation("stage-" + stage + "-moving-world-consumed-frame.nbt", consumed);
        System.out.println(
            "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS latest moving world frame stage=" + stage
                + " generation="
                + generation
                + " frame="
                + renderedWorldFrames
                + " stateIdentity="
                + System.identityHashCode(latest)
                + " renderedIdentity="
                + System.identityHashCode(lastRenderedProjectionState)
                + " anchor="
                + java.util.Arrays.toString(aimedMovingAnchor)
                + " geometry="
                + observedWorldGeometry
                + " ghosts="
                + observedWorldGhosts
                + " outline=70e69a");
        return true;
    }

    private static NBTTagCompound cameraObservation(Minecraft mc, HologramState snapshot)
        throws ReflectiveOperationException {
        NBTTagCompound observation = new NBTTagCompound();
        observation.setString("generation", projectionGeneration(snapshot));
        if (snapshot != null) observation.setInteger("stateIdentity", System.identityHashCode(snapshot));
        net.minecraft.entity.EntityLivingBase camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        if (camera != null) {
            observation.setDouble("x", camera.posX);
            observation.setDouble("y", camera.posY);
            observation.setDouble("z", camera.posZ);
            observation.setFloat("yaw", camera.rotationYaw);
            observation.setFloat("pitch", camera.rotationPitch);
            observation.setDouble("eyeHeight", camera.getEyeHeight());
        }
        if (mc.thePlayer != null) {
            observation.setInteger("heldSlot", mc.thePlayer.inventory.currentItem);
            net.minecraft.item.ItemStack held = mc.thePlayer.getHeldItem();
            observation.setInteger("stackIdentity", held == null ? 0 : System.identityHashCode(held));
            observation.setInteger("heldItemIdentity", held == null ? 0 : System.identityHashCode(held.getItem()));
            observation.setInteger("heldDamage", held == null ? 0 : held.getItemDamage());
            observation.setString(
                "toolUuid",
                held == null || !held.hasTagCompound() ? ""
                    : held.getTagCompound()
                        .getString("gtitHologramToolId"));
        }
        java.lang.reflect.Field distance = HologramClient.class.getDeclaredField("previewDistance");
        distance.setAccessible(true);
        observation.setInteger(
            "previewDistance",
            ((com.miaokatze.gtmb.hologram.HologramWorldFlow.PreviewDistance) distance.get(null)).distance());
        net.minecraft.util.MovingObjectPosition hit = mc.objectMouseOver;
        observation.setString("rawHitType", hit == null ? "NONE" : hit.typeOfHit.name());
        if (hit != null && hit.typeOfHit == net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK) {
            observation.setIntArray("rawHitBlock", new int[] { hit.blockX, hit.blockY, hit.blockZ });
            observation.setInteger("rawHitSide", hit.sideHit);
        }
        return observation;
    }

    private IllegalStateException movingWorldTimeout(Minecraft mc, String reason) {
        IllegalStateException failure = new IllegalStateException(
            reason + " stage="
                + stage
                + " latest="
                + projectionGeneration(HologramClient.state)
                + " rendered="
                + projectionGeneration(lastRenderedProjectionState)
                + " frame="
                + renderedWorldFrames
                + " preparedFrame="
                + aimedWorldFrame
                + " aimedAnchor="
                + java.util.Arrays.toString(aimedMovingAnchor)
                + " renderedAnchor="
                + java.util.Arrays.toString(lastRenderedMovingAnchor)
                + " geometry="
                + observedWorldGeometry
                + " ghosts="
                + observedWorldGhosts
                + " outline="
                + Integer.toHexString(observedWorldOutline));
        try {
            screenshot(mc, "stage-" + stage + "-latest-world-frame-timeout");
            saveObservation(
                "stage-" + stage + "-latest-world-frame-timeout.nbt",
                HologramClient.state == null ? null : HologramClient.state.data);
            saveObservation("stage-" + stage + "-quick-timeout.nbt", HologramLibraryClient.INSTANCE.smokeQuickState());
            saveObservation("stage-" + stage + "-camera-timeout.nbt", cameraObservation(mc, HologramClient.state));
        } catch (Exception diagnosticFailure) {
            failure.addSuppressed(diagnosticFailure);
        }
        return failure;
    }

    /** Inject native LWJGL key state across real client ticks and dispatch the registered Forge mouse event. */
    private boolean worldFlow(Minecraft mc) throws Exception {
        NBTTagCompound state = HologramClient.state == null ? null : HologramClient.state.data;
        if (stage == 94) {
            if (nudgeSmoke.worldTick(mc)) {
                stage = 78;
                waitTicks = 0;
            }
            return true;
        }
        if (stage == 40 || stage == 42) {
            if (stage == 42 && (state == null || state.getBoolean("areaSelecting"))) return true;
            mc.displayGuiScreen(null);
            keyboard(Keyboard.KEY_RETURN, false);
            areaEntryInput = new HologramAreaSelectionClientSmoke(
                receipts,
                "stone-" + sufficientBlueprintPass + "-" + stage,
                HologramInteractionClientSmoke::keyboard,
                this::rightClickSource);
            areaEntryInput.begin(
                mc,
                new net.minecraft.util.MovingObjectPosition(50, 80, 44, 2, Vec3.createVectorHelper(50.5, 80.5, 44)));
            stage = stage == 40 ? 41 : 43;
            waitTicks = 0;
            return true;
        }
        if (stage == 41 || stage == 43) {
            if (!areaEntryInput.tick(mc)) return true;
            if (state == null || !state.getBoolean("areaSelecting")) return true;
            keyboard(Keyboard.KEY_LSHIFT, false);
            if (stage == 41) {
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiIngameMenu());
                require(mc.currentScreen == null, "Escape_cancels_area_without_opening_pause_menu");
                stage = 42;
            } else {
                mouseCorner(mc, 0, 50);
                stage = 44;
            }
            waitTicks = 0;
            return true;
        }
        if (stage == 44) {
            int[] first = state == null ? new int[0] : state.getIntArray("areaPoints");
            if (first.length < 3 || first[0] != 50 || first[1] != 80 || first[2] != 44 || ++waitTicks < 4) return true;
            mouseCorner(mc, 1, sufficientBlueprintPass ? 50 : 51);
            stage = 45;
            waitTicks = 0;
            return true;
        }
        if (stage == 45) {
            int[] expected = { 50, 80, 44, sufficientBlueprintPass ? 50 : 51, 80, 44 };
            if (state == null || !java.util.Arrays.equals(state.getIntArray("areaPoints"), expected) || ++waitTicks < 4)
                return true;
            keyboard(Keyboard.KEY_LSHIFT, true);
            // Finish deliberately points elsewhere: the gesture must not replace corner two.
            mouseCorner(mc, 1, 48);
            keyboard(Keyboard.KEY_LSHIFT, false);
            areaSourceSwitchNonce = "";
            stage = 46;
            return true;
        }
        if (stage == 46) {
            if (!(mc.currentScreen instanceof HologramScreen) || state == null) return true;
            HologramScreen importing = (HologramScreen) mc.currentScreen;
            if (state.getBoolean("discardConfirmationRequired")) {
                if (importing.smokePending() || !hasFreshDraw(importing)) return true;
                NBTTagCompound proposal = state.getCompoundTag("discardTargetAction");
                String nonce = proposal.getString("sourceSwitchNonce");
                require(
                    "importArea".equals(proposal.getString("op")) && proposal.getString("session")
                        .equals(state.getString("session"))
                        && proposal.getLong("planRevision") == state.getLong("planRevision")
                        && !nonce.isEmpty(),
                    "actual_area_source_switch_retains_server_owned_proposal");
                require(
                    java.util.Arrays.equals(
                        state.getIntArray("areaPoints"),
                        new int[] { 50, 80, 44, sufficientBlueprintPass ? 50 : 51, 80, 44 }),
                    "actual_area_source_review_retains_both_selected_corners");
                if (!areaSourceSwitchNonce.isEmpty()) {
                    require(areaSourceSwitchNonce.equals(nonce), "area_source_proposal_changed_after_confirmation");
                    if (System.nanoTime() - stageEnteredAt > 10000000000L) throw new IllegalStateException(
                        "10 second actual area source confirmation ACK wait: " + state.getString("status"));
                    return true;
                }
                saveObservation("stone-source-switch-review-" + sufficientBlueprintPass + ".nbt", state);
                saveObservation(
                    "stone-source-switch-review-ui-" + sufficientBlueprintPass + ".nbt",
                    importing.smokeLibraryUiState());
                require(
                    importing.smokeLibraryUiState()
                        .getBoolean("discardReview"),
                    "server_area_proposal_opens_actual_discard_modal");
                click(importing, 224);
                areaSourceSwitchNonce = nonce;
                return true;
            }
            if (!awaitStoneArea(state)) return true;
            keyboard(Keyboard.KEY_RETURN, false);
            clearClickedFrame();
            stage = 47;
            return true;
        }
        if (stage == 50 || stage == 54) {
            if (state == null || !state.getBoolean("moving") || mc.currentScreen != null) return true;
            if (++waitTicks < 4) return true;
            require(
                state.getBoolean("virtualPlacement") && !state.getBoolean("virtualLocked"),
                "library_Place_enters_unlocked_world_ghost_stage_" + stage);
            if (sufficientBlueprintPass && stage == 50) {
                require(
                    HologramLibraryClient.dedicated()
                        ? !quickLocalId.isEmpty() && quickLocalId.equals(state.getString("libraryLocalId"))
                            && state.getString("selectedPresetId")
                                .startsWith("private_")
                        : quickPresetId.equals(state.getString("selectedPresetId")),
                    "Alt_right_uses_exact_last_saved_blueprint");
                if (HologramLibraryClient.dedicated()) quickPresetId = state.getString("selectedPresetId");
            }
            require(
                receipt().getInteger("blueprintPlaced") == (sufficientBlueprintPass ? 0 : 1),
                "moving_ghost_never_builds_stage_" + stage);
            require(
                HologramRenderer.lastWorldGeometry > 0 && HologramRenderer.lastWorldGhosts > 0,
                "actual_world_render_draws_blueprint_ghost_stage_" + stage);
            mc.objectMouseOver = null;
            if (!airPlacementPrepared) {
                int[] beforeCamera = HologramClient.movingAnchor(HologramClient.state);
                mc.thePlayer.rotationYaw = stage == 50 ? sufficientBlueprintPass ? 135 : 60 : -90;
                mc.thePlayer.rotationPitch = stage == 50 ? sufficientBlueprintPass ? -25 : -30 : -35;
                mc.thePlayer.prevRotationYaw = mc.thePlayer.rotationYaw;
                mc.thePlayer.prevRotationPitch = mc.thePlayer.rotationPitch;
                int[] beforeWheel = HologramClient.movingAnchor(HologramClient.state);
                require(
                    !java.util.Arrays.equals(beforeCamera, beforeWheel),
                    "camera_look_moves_air_ghost_stage_" + stage);
                mouseWheel(120);
                mouseWheel(120);
                expectedAirAnchor = HologramClient.movingAnchor(HologramClient.state);
                require(
                    !java.util.Arrays.equals(beforeWheel, expectedAirAnchor),
                    "registered_scroll_changes_air_ghost_distance_stage_" + stage);
                require(
                    expectedAirAnchor[1] > net.minecraft.util.MathHelper.floor_double(mc.thePlayer.posY),
                    "upward_camera_places_air_ghost_above_player_stage_" + stage);
                require(
                    !java.util.Arrays.equals(
                        expectedAirAnchor,
                        new int[] { state.getInteger("x"), state.getInteger("y"), state.getInteger("z") }),
                    "air_ghost_uses_camera_instead_of_stale_server_anchor_stage_" + stage);
                airPreparedFrame = renderedWorldFrames;
                airPlacementPrepared = true;
                return true;
            }
            if (renderedWorldFrames <= airPreparedFrame) return true;
            require(
                HologramRenderer.lastWorldPlacementOutlineColor == 0x70e69a,
                "actual_moving_world_ghost_outline_is_green_stage_" + stage);
            require(
                java.util.Arrays.equals(expectedAirAnchor, HologramClient.movingAnchor(HologramClient.state)),
                "rendered_air_ghost_and_right_click_share_exact_camera_target_stage_" + stage);
            screenshot(mc, "air-camera-scroll-ghost-stage-" + stage);
            airRightClick(mc, true);
            airPlacementPrepared = false;
            stage = stage == 50 ? 51 : 55;
            waitTicks = 0;
            return true;
        }
        if (stage == 51 || stage == 55) {
            if (state == null || !state.getBoolean("virtualLocked")) return true;
            require(mc.currentScreen == null, "first_normal_right_locks_without_opening_GUI_stage_" + stage);
            require(
                !state.getBoolean("moving") && !state.getBoolean("open"),
                "first_right_is_world_only_lock_" + stage);
            require(
                java.util.Arrays.equals(
                    expectedAirAnchor,
                    new int[] { state.getInteger("x"), state.getInteger("y"), state.getInteger("z") }),
                "real_air_C08_locks_exact_camera_scroll_anchor_stage_" + stage);
            NBTTagCompound authoritative = receipt();
            if (!java.util.Arrays.equals(expectedAirAnchor, authoritative.getIntArray("blueprintAnchor"))) return true;
            require(
                authoritative.getInteger("blueprintObservedTargetCount") >= (sufficientBlueprintPass ? 1 : 2),
                "server_owned_receipt_observes_air_target_cells_stage_" + stage);
            if (++waitTicks < 4 || renderedWorldFrames <= airPreparedFrame) return true;
            require(
                HologramRenderer.lastWorldGeometry > 0 && HologramRenderer.lastWorldPlacementOutlineColor == 0x66d9ef,
                "actual_first_locked_world_ghost_outline_is_cyan_stage_" + stage);
            require(
                authoritative.getInteger("blueprintPlaced") == (sufficientBlueprintPass ? 0 : 1),
                "first_world_lock_never_constructs_" + stage);
            screenshot(mc, "v007-first-right-cyan-world-lock-stage-" + stage);
            if (stage == 51 && sufficientBlueprintPass && !lockedEscapeChecked) {
                lockedEscapeChecked = true;
                lockedEscapeSession = state.getString("session");
                lockedEscapeNonce = state.getLong("placementReviewNonce");
                lockedEscapeStartedAt = System.nanoTime();
                lockedEscapeCancelSentTick = receipt().getLong("tick");
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiIngameMenu());
                require(mc.currentScreen == null, "locked_cyan_Escape_cancels_without_pause_menu");
                stage = 91;
                return true;
            }
            lockedNonce = state.getLong("placementReviewNonce");
            reviewStage = stage == 51 ? 52 : 56;
            keyboard(Keyboard.KEY_LSHIFT, true);
            rightClickSource(mc);
            keyboard(Keyboard.KEY_LSHIFT, false);
            airPreparedFrame = renderedWorldFrames;
            stage = 77;
            waitTicks = 0;
            return true;
        }
        if (stage == 77) {
            if (state == null || !state.getBoolean("moving") || state.getBoolean("virtualLocked")) return true;
            require(mc.currentScreen == null, "Shift_right_unlock_returns_to_world_without_GUI");
            require(
                state.getLong("placementReviewNonce") > lockedNonce
                    && !state.getBoolean("blueprintAwaitingConfirmation"),
                "Shift_right_unlock_invalidates_old_review_nonce");
            if (++waitTicks < 4 || renderedWorldFrames <= airPreparedFrame) return true;
            require(
                HologramRenderer.lastWorldGeometry > 0 && HologramRenderer.lastWorldPlacementOutlineColor == 0x70e69a,
                "actual_Shift_right_unlocked_world_ghost_returns_to_green");
            screenshot(mc, "v007-shift-right-green-unlocked-review-" + reviewStage);
            mc.objectMouseOver = null;
            expectedAirAnchor = HologramClient.movingAnchor(HologramClient.state);
            lockedNonce = state.getLong("placementReviewNonce");
            airRightClick(mc, true);
            airPreparedFrame = renderedWorldFrames;
            stage = 78;
            waitTicks = 0;
            return true;
        }
        if (stage == 78) {
            if (state == null || !state.getBoolean("virtualLocked")) return true;
            require(mc.currentScreen == null && !state.getBoolean("open"), "relock_still_keeps_real_GUI_closed");
            require(state.getLong("placementReviewNonce") > lockedNonce, "relock_invalidates_prior_world_review_nonce");
            require(
                java.util.Arrays.equals(
                    expectedAirAnchor,
                    new int[] { state.getInteger("x"), state.getInteger("y"), state.getInteger("z") }),
                "relock_uses_exact_rendered_air_anchor");
            if (++waitTicks < 4 || renderedWorldFrames <= airPreparedFrame) return true;
            require(
                HologramRenderer.lastWorldGeometry > 0 && HologramRenderer.lastWorldPlacementOutlineColor == 0x66d9ef,
                "actual_relocked_world_ghost_returns_to_cyan");
            screenshot(mc, "v007-relocked-cyan-before-RIGHT2-review-" + reviewStage);
            if (sufficientBlueprintPass && !nudgeChecked) {
                nudgeChecked = true;
                nudgeSmoke = new HologramLockedNudgeClientSmoke(
                    mc,
                    HologramInteractionClientSmoke::keyboard,
                    this::receipt,
                    name -> screenshot(mc, name));
                stage = 94;
                return true;
            }
            lockedNonce = state.getLong("placementReviewNonce");
            mc.objectMouseOver = null;
            airRightClick(mc, false);
            right2Opening = null;
            stage = 79;
            return true;
        }
        if (stage == 79) {
            if (!(mc.currentScreen instanceof HologramScreen) || state == null || !state.getBoolean("virtualLocked"))
                return true;
            if (right2Opening == null) {
                require(
                    state.getBoolean("open") && state.getLong("placementReviewNonce") > lockedNonce,
                    "RIGHT2_is_the_only_gesture_issuing_material_review");
                right2Opening = (NBTTagCompound) state.copy();
                saveObservation("stone-right2-opening-" + reviewStage + ".nbt", right2Opening);
            }
            if (nudgeSmoke != null && !nudgeSmoke.uiTick(mc)) return true;
            if (!placementReviewReady((HologramScreen) mc.currentScreen)) return true;
            state = ((HologramScreen) mc.currentScreen).smokeSnapshot();
            require(
                right2Opening.getString("session")
                    .equals(state.getString("session")) && state.getBoolean("virtualPlacement")
                    && state.getBoolean("virtualLocked")
                    && state.getBoolean("blueprintAwaitingConfirmation")
                    && state.getLong("placementReviewNonce") >= right2Opening.getLong("placementReviewNonce")
                    && state.getLong("planRevision") >= right2Opening.getLong("planRevision")
                    && java.util.Arrays.equals(
                        new int[] { right2Opening.getInteger("x"), right2Opening.getInteger("y"),
                            right2Opening.getInteger("z") },
                        new int[] { state.getInteger("x"), state.getInteger("y"), state.getInteger("z") }),
                "RIGHT2_review_keeps_source_locked_anchor_and_current_plan_after_optional_identity_input");
            System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS v007 green RIGHT cyan SHIFT_RIGHT green RIGHT cyan RIGHT2 review "
                    + ((HologramScreen) mc.currentScreen).smokePlacementReviewContext());
            logConstructionScope((HologramScreen) mc.currentScreen, "actual-air-position-precheck-whole-blueprint");
            clearClickedFrame();
            stage = reviewStage;
            return true;
        }
        if (stage >= 91 && stage <= 93) {
            if (System.nanoTime() - lockedEscapeStartedAt > 15000000000L)
                throw movingWorldTimeout(mc, "locked Escape draft/Place navigation exceeded fifteen seconds");
            require(
                receipt().getInteger("blueprintPlaced") == 0 && receipt().getInteger("blueprintStock") == 2,
                "locked_Escape_and_restarting_Place_never_builds_or_consumes_material");
            if (stage == 91) {
                if (state == null || state.getBoolean("virtualPlacement")) return true;
                require(
                    lockedEscapeSession.equals(state.getString("session"))
                        && quickPresetId.equals(state.getString("selectedPresetId"))
                        && !state.getBoolean("virtualLocked")
                        && !state.getBoolean("blueprintAwaitingConfirmation")
                        && state.getLong("placementReviewNonce") > lockedEscapeNonce,
                    "locked_Escape_retains_same_draft_and_revokes_old_review_nonce");
                NBTTagCompound server = receipt().getCompoundTag("blueprintSession");
                if (!awaitCancelledReceipt(lockedEscapeSession, state, lockedEscapeCancelSentTick)) return true;
                require(
                    lockedEscapeSession.equals(server.getString("id"))
                        && quickPresetId.equals(server.getString("presetId"))
                        && !server.getBoolean("virtualPlacement")
                        && !server.getBoolean("virtualLocked")
                        && !server.getBoolean("placementReviewIssued")
                        && !server.getBoolean("blueprintAwaitingConfirmation")
                        && server.getInteger("job") == 0
                        && server.getLong("placementReviewNonce") > lockedEscapeNonce,
                    "locked_Escape_independent_server_receipt_revokes_placement_capability");
                if (!lockedEscapeReopenRequested) {
                    require(
                        mc.currentScreen == null && !state.getBoolean("open"),
                        "locked_Escape_ACK_keeps_world_closed_without_draft_UI");
                    if (lockedEscapeCancelAckTick < 0) lockedEscapeCancelAckTick = receipt().getLong("tick");
                    if (receipt().getLong("tick") - lockedEscapeCancelAckTick < 10) return true;
                    screenshot(mc, "v009-locked-Escape-world-only-capability-revoked");
                    saveObservation("locked-Escape-world-receipt.nbt", receipt());
                    mc.objectMouseOver = null;
                    airRightClick(mc, false);
                    lockedEscapeReopenRequested = true;
                    clearClickedFrame();
                    return true;
                }
                if (!(mc.currentScreen instanceof HologramScreen)) return true;
                HologramScreen draft = (HologramScreen) mc.currentScreen;
                require(
                    !(Boolean) observedField(draft, "confirmation") && libraryTab(draft),
                    "normal_air_right_reopens_cancelled_cyan_draft_without_review_authority");
                if (draft.smokePending()) return true;
                if (!(Boolean) observedField(draft, "libraryDrawer")) {
                    saveObservation("locked-Escape-draft-editor-ui-state.nbt", draft.smokeLibraryUiState());
                    awaitControl(draft, 149);
                    click(draft, 149);
                    return true;
                }
                if (HologramLibraryClient.dedicated()) {
                    HologramLibraryClient library = HologramLibraryClient.INSTANCE;
                    if (library.busy()) return true;
                    require(
                        quickLocalId.equals(library.selected(false))
                            && new File(mc.mcDataDir, "MiaoBlueprints/" + quickLocalId + ".json").isFile(),
                        "locked_Escape_reopened_library_selects_the_same_portable_private_file");
                }
                awaitControl(draft, 141);
                screenshot(mc, "v007-locked-Escape-draft-UI-capability-revoked");
                saveObservation("locked-Escape-draft-receipt.nbt", receipt());
                click(draft, 141);
                repeatFrame = renderedWorldFrames;
                aimedProjectionGeneration = "";
                stage = 92;
                return true;
            }
            if (stage == 92) {
                if (state == null || !state.getBoolean("moving") || state.getBoolean("virtualLocked")) return true;
                require(mc.currentScreen == null, "locked_Escape_draft_Place_returns_to_world_ghost");
                if (!awaitAimedMovingWorldFrame(mc)) return true;
                require(
                    HologramRenderer.lastWorldGeometry > 0
                        && HologramRenderer.lastWorldPlacementOutlineColor == 0x70e69a,
                    "locked_Escape_draft_Place_renders_actual_green_ghost");
                mc.objectMouseOver = null;
                expectedAirAnchor = HologramClient.movingAnchor(HologramClient.state);
                airRightClick(mc, true);
                stage = 93;
                waitTicks = 0;
                return true;
            }
            if (state == null || !state.getBoolean("virtualLocked")) return true;
            require(
                mc.currentScreen == null && !state.getBoolean("open"),
                "locked_Escape_following_Place_first_right_stays_world_only");
            System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS locked-cyan Escape world-only10ticks nonce-revoked zero-world/cost air-right-reopen Place green resumes original three reviews");
            stage = 51;
            return true;
        }
        return false;
    }

    private boolean awaitStoneArea(NBTTagCompound state) throws Exception {
        String observation = "stage=" + stage
            + " session="
            + state.getString("session")
            + " revision="
            + state.getLong("planRevision")
            + " areaSelecting="
            + state.getBoolean("areaSelecting")
            + " areaImporting="
            + state.getBoolean("areaImporting")
            + " areaImported="
            + state.getBoolean("areaImported")
            + " capturePending="
            + state.getBoolean("capturePending")
            + " virtual="
            + state.getBoolean("virtual")
            + " cells="
            + state.getTagList("cells", 10)
                .tagCount()
            + " points="
            + java.util.Arrays.toString(state.getIntArray("areaPoints"))
            + " status="
            + state.getString("status");
        if (!observation.equals(lastStoneAreaObservation)) {
            lastStoneAreaObservation = observation;
            System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] AREA " + observation);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                new File(receipts, "stone-area-observed-state.nbt"))) {
                CompressedStreamTools.writeCompressed(state, out);
            }
        }
        if (state.getString("status")
            .contains("区域导入失败")) throw new IllegalStateException("actual stone area import failed: " + observation);
        int[] expected = { 50, 80, 44, sufficientBlueprintPass ? 50 : 51, 80, 44 };
        boolean finished = !state.getBoolean("areaSelecting") && !state.getBoolean("areaImporting")
            && state.getBoolean("areaImported")
            && !state.getBoolean("capturePending")
            && java.util.Arrays.equals(expected, state.getIntArray("areaPoints"))
            && state.getInteger("x") == 50
            && state.getInteger("y") == 80
            && state.getInteger("z") == 44;
        if (!finished && System.nanoTime() - stageEnteredAt > 10000000000L)
            throw new IllegalStateException("10 second actual stone area completion wait: " + observation);
        return finished;
    }

    private static void keyboard(int key, boolean down) throws Exception {
        java.lang.reflect.Field buffer = Keyboard.class.getDeclaredField("keyDownBuffer");
        buffer.setAccessible(true);
        ((java.nio.ByteBuffer) buffer.get(null)).put(key, (byte) (down ? 1 : 0));
    }

    private boolean awaitLibrarySocketProof() throws Exception {
        File failure = new File(receipts, "library-socket-client-failed.txt");
        if (failure.isFile()) throw new IllegalStateException(
            "Independent library socket probe failed: "
                + new String(Files.readAllBytes(failure.toPath()), StandardCharsets.UTF_8));
        if (libraryUiDriver == null || !libraryUiDriver.complete()) return false;
        for (String name : new String[] { "library-socket-client-pass.txt", "library-socket-server-pass.txt",
            "library-ui-client-pass.txt", "library-ui-delete-server-pass.txt" }) {
            File proof = new File(receipts, name);
            if (!proof.isFile()) return false;
            require(
                new String(Files.readAllBytes(proof.toPath()), StandardCharsets.UTF_8).startsWith("PASS"),
                "independent_library_socket_and_UI_proof_" + name);
        }
        return true;
    }

    private void mouseCorner(Minecraft mc, int button, int x) throws Exception {
        if (button == 1) usePress.begin(mc, false);
        mc.objectMouseOver = new net.minecraft.util.MovingObjectPosition(
            x,
            80,
            44,
            2,
            Vec3.createVectorHelper(x + .5, 80.5, 44));
        net.minecraftforge.client.event.MouseEvent mouse = new net.minecraftforge.client.event.MouseEvent();
        java.lang.reflect.Field nativeButton = mouse.getClass()
            .getField("button");
        java.lang.reflect.Field nativeState = mouse.getClass()
            .getField("buttonstate");
        nativeButton.setAccessible(true);
        nativeState.setAccessible(true);
        nativeButton.setInt(mouse, button);
        nativeState.setBoolean(mouse, true);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(mouse);
        require(mouse.isCanceled(), "real_registered_area_mouse_consumes_corner_button_" + button);
    }

    private boolean v010WheelChecked;

    private void mouseWheel(int distance) throws Exception {
        net.minecraftforge.client.event.MouseEvent wheel = new net.minecraftforge.client.event.MouseEvent();
        java.lang.reflect.Field delta = wheel.getClass()
            .getField("dwheel");
        java.lang.reflect.Field button = wheel.getClass()
            .getField("button");
        java.lang.reflect.Field buttonState = wheel.getClass()
            .getField("buttonstate");
        delta.setAccessible(true);
        button.setAccessible(true);
        buttonState.setAccessible(true);
        delta.setInt(wheel, distance);
        button.setInt(wheel, -1);
        buttonState.setBoolean(wheel, false);
        java.nio.ByteBuffer keys = (java.nio.ByteBuffer) observedKeyboardBuffer();
        byte previousAlt = keys.get(Keyboard.KEY_LMENU);
        try {
            keyboard(Keyboard.KEY_LMENU, true);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(wheel);
            require(wheel.isCanceled(), "real_registered_Alt_moving_wheel_consumed_without_hotbar_change");
        } finally {
            keys.put(Keyboard.KEY_LMENU, previousAlt);
        }
    }

    private static Object observedKeyboardBuffer() throws Exception {
        java.lang.reflect.Field field = Keyboard.class.getDeclaredField("keyDownBuffer");
        field.setAccessible(true);
        return field.get(null);
    }

    private void airRightClick(Minecraft mc, boolean checkWorldLock) {
        usePress.begin(mc, checkWorldLock);
        mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
    }

    private void rightClickSource(Minecraft mc) {
        usePress.begin(mc, false);
        mc.objectMouseOver = new net.minecraft.util.MovingObjectPosition(
            50,
            80,
            44,
            2,
            Vec3.createVectorHelper(50.5, 80.5, 44));
        mc.playerController.onPlayerRightClick(
            mc.thePlayer,
            mc.theWorld,
            mc.thePlayer.getHeldItem(),
            50,
            80,
            44,
            2,
            Vec3.createVectorHelper(50.5, 80.5, 44));
    }

    private boolean hasFreshDraw(HologramScreen screen) {
        long generation = screen.smokeRenderGeneration();
        return generation > 0 && (screen != lastClickedScreen || generation > lastClickedRender);
    }

    private void recordClickedFrame(HologramScreen screen) {
        lastClickedScreen = screen;
        lastClickedRender = screen.smokeRenderGeneration();
    }

    private void clearClickedFrame() {
        lastClickedScreen = null;
        lastClickedRender = -1;
    }

    private void click(HologramScreen screen, int id) throws Exception {
        if (id == 55 && !placementReviewReady(screen)) throw new AwaitDraw();
        awaitControl(screen, id);
        if (id == 55) logConfirmClickState(screen, "before-native-mouse");
        HologramMuiProbeInput.control(screen, id);
        if (id == 55) logConfirmClickState(screen, "after-native-mouse");
        recordClickedFrame(screen);
        System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] mouse control=" + id + " stage=" + stage);
    }

    private boolean placementReviewReady(HologramScreen screen) throws Exception {
        if (placementReviewInput == null)
            placementReviewInput = new HologramPlacementReviewClientSmoke(receipts, "stone", (placementScreen, id) -> {
                click(placementScreen, id);
                return true;
            }, this::receipt);
        return placementReviewInput.tick(screen);
    }

    private void saveObservation(String filename, NBTTagCompound observed) throws Exception {
        if (observed == null) return;
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(receipts, filename))) {
            CompressedStreamTools.writeCompressed(observed, output);
        }
    }

    /** Read the exact GUI hit and dispatch gates without redrawing, activating controls, or sending packets. */
    private void logConfirmClickState(HologramScreen screen, String phase) throws Exception {
        NBTTagCompound state = screen.smokeSnapshot();
        StringBuilder details = new StringBuilder("stage=" + stage + " phase=" + phase);
        for (String field : new String[] { "confirmation", "libraryDrawer", "roleDropdown", "resetReview", "nbtDrawer",
            "promptDrawer", "versionsDrawer", "shortcutsDrawer", "hatchDrawer", "presetDrawer", "deletePresetId",
            "sequence", "pendingSequence", "queuedDispatchSequence", "confirmedRevision", "captureRenderSmokeAction",
            "notice" })
            details.append(' ')
                .append(field)
                .append('=')
                .append(observedField(screen, field));
        Object queue = observedField(screen, "editQueue");
        details.append(" editQueueSize=")
            .append(((HologramEditQueue) queue).size());

        details.append(" editable=")
            .append(HologramUiDiagnostics.invokeDiagnostic(screen, "editable", new Class<?>[] {}))
            .append(" actionQueued=")
            .append((Long) observedField(screen, "pendingSequence") >= 0)
            .append(" expectedOp=")
            .append(state.getBoolean("blueprintAwaitingConfirmation") ? "confirmBlueprintPlacement" : "build");
        details.append(" planRevision=")
            .append(state.getLong("planRevision"))
            .append(" uiSequence=")
            .append(state.getLong("uiSequence"))
            .append(" awaiting=")
            .append(state.getBoolean("blueprintAwaitingConfirmation"))
            .append(" missing=")
            .append(state.getInteger("missing"))
            .append(" job=")
            .append(state.getInteger("job"));
        if (screen.smokeHasControl(55)) {
            int[] point = screen.smokeControlPoint(55);
            float scale = (Float) observedField(screen, "scale");
            int localX = (int) ((point[0] - (Integer) observedField(screen, "originX")) / scale);
            int localY = (int) ((point[1] - (Integer) observedField(screen, "originY")) / scale);
            details.append(" nativePoint=")
                .append(java.util.Arrays.toString(point))
                .append(" logicalPoint=")
                .append(localX)
                .append(',')
                .append(localY)
                .append(" enabled55=true hitsInDrawOrder=[");
            for (Object control : (java.util.List<?>) observedField(screen, "controls")) {
                int x = (Integer) observedField(control, "x"), y = (Integer) observedField(control, "y");
                int w = (Integer) observedField(control, "w"), h = (Integer) observedField(control, "h");
                if (localX >= x && localX < x + w && localY >= y && localY < y + h)
                    details.append(observedField(control, "id"))
                        .append(':')
                        .append(observedField(control, "enabled"))
                        .append(';');
            }
            details.append(']');
        } else details.append(" enabled55=false");
        Object captured = observedField(screen, "capturedRenderSmokeAction");
        if (captured instanceof NBTTagCompound) details.append(" mockCapturedOp=")
            .append(((NBTTagCompound) captured).getString("op"));
        System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] CONFIRM-DIAGNOSTIC " + details);
    }

    private static Object observedField(Object instance, String name) throws ReflectiveOperationException {

        return ((Object) HologramUiDiagnostics.readDiagnosticField(instance, name));
    }

    private String stageSnapshot(NBTTagCompound state) {
        if (state == null) return "stage=" + stage + " no hologram snapshot";
        NBTTagCompound coil = new NBTTagCompound();
        net.minecraft.nbt.NBTTagList capabilities = state.getTagList("capabilities", 10);
        for (int i = 0; i < capabilities.tagCount(); i++) {
            NBTTagCompound row = capabilities.getCompoundTagAt(i);
            if ("coil".equals(row.getString("id"))) coil = row;
        }
        String coilBlock = String
            .valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(gregtech.api.GregTechAPI.sBlockCasings5));
        Map<String, Integer> coilRows = new LinkedHashMap<>();
        Map<String, StringBuilder> protectedRows = new LinkedHashMap<>();
        net.minecraft.nbt.NBTTagList cells = state.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound row = cells.getCompoundTagAt(i);
            String reason = row.getString("reason");
            if (reason.length() > 160) reason = reason.substring(0, 160);
            if (!row.getBoolean("anchor") && "PROTECT".equals(row.getString("operation"))) {
                String protectedGroup = "id=" + row.getString("id")
                    + " meta="
                    + row.getInteger("meta")
                    + " wantId="
                    + row.getString("wantId")
                    + " wantMeta="
                    + row.getInteger("wantMeta")
                    + " role="
                    + row.getString("role")
                    + " inScope="
                    + row.getBoolean("inScope")
                    + " reason="
                    + reason;
                StringBuilder coordinates = protectedRows
                    .computeIfAbsent(protectedGroup, ignored -> new StringBuilder());
                if (coordinates.length() > 0) coordinates.append(';');
                coordinates.append(row.getInteger("x"))
                    .append(',')
                    .append(row.getInteger("y"))
                    .append(',')
                    .append(row.getInteger("z"));
            }
            if (!coilBlock.equals(row.getString("id")) && !coilBlock.equals(row.getString("wantId"))) continue;
            String cohort = "actual=" + row.getInteger("meta")
                + " want="
                + row.getInteger("wantMeta")
                + " status="
                + row.getString("status")
                + " operation="
                + row.getString("operation")
                + " reason="
                + reason;
            coilRows.merge(cohort, 1, Integer::sum);
        }
        return "stage=" + stage
            + " job="
            + state.getInteger("job")
            + " mode="
            + state.getInteger("mode")
            + " main="
            + state.getInteger("main")
            + " channels.coil="
            + state.getCompoundTag("channels")
                .getInteger("coil")
            + " selected="
            + state.getInteger("selected")
            + " coil.current="
            + coil.getInteger("current")
            + " coil.explicit="
            + coil.getBoolean("explicit")
            + " workTotal="
            + state.getInteger("workTotal")
            + " missing="
            + state.getInteger("missing")
            + " protected="
            + state.getInteger("protected")
            + " protectedWork="
            + nonControllerProtection(state)
            + " protectedRows="
            + protectedRows
            + " coils="
            + coilRows
            + " status="
            + state.getString("status");
    }

    private static int nonControllerProtection(NBTTagCompound state) {
        int count = 0;
        net.minecraft.nbt.NBTTagList cells = state.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound row = cells.getCompoundTagAt(i);
            if (row.getBoolean("inScope") && !row.getBoolean("anchor") && "PROTECT".equals(row.getString("operation")))
                count++;
        }
        return count;
    }

    private void reopenWithRightClick(Minecraft mc) {
        usePress.begin(mc, false);
        mc.displayGuiScreen(null);
        mc.playerController.onPlayerRightClick(
            mc.thePlayer,
            mc.theWorld,
            mc.thePlayer.getHeldItem(),
            48,
            80,
            48,
            2,
            Vec3.createVectorHelper(48.5, 80.5, 48));
        clearClickedFrame();
        waitTicks = 0;
    }

    private void awaitControl(HologramScreen screen, int id) {
        if (!screen.smokeHasControl(id)) {
            if (System.nanoTime() - stageEnteredAt >= 10000000000L) throw new IllegalStateException(
                "10 second wait for enabled native control=" + id + " " + stageSnapshot(screen.smokeSnapshot()));
            throw new AwaitDraw();
        }
    }

    private static final class AwaitDraw extends RuntimeException {
    }

    private static final class AwaitReceipt extends RuntimeException {
    }

    private void screenshot(Minecraft mc, String name) throws Exception {
        int error = GL11.glGetError();
        require(error == GL11.GL_NO_ERROR, "OpenGL_no_error_" + name + "_error=" + error);
        String filename = "interaction-" + name + ".png";
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, filename, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        File image = new File(new File(mc.mcDataDir, "screenshots"), filename);
        require(image.isFile() && image.length() > 0, "saved_real_network_screenshot_" + name);
        System.out.println(
            "[GTMB-HOLOGRAM-INTERACTION-CLIENT] RENDER geometry=" + HologramRenderer.lastWorldGeometry
                + " GTgeometry="
                + HologramRenderer.lastWorldGTGeometry
                + " fallback="
                + HologramRenderer.lastWorldFallback
                + " ghost="
                + HologramRenderer.lastWorldGhosts
                + " matched="
                + HologramRenderer.lastWorldMatched
                + " screenshot="
                + image.getAbsolutePath());
    }

    private void verifyAnimatedItemAtlas(Minecraft mc) {
        Object texture = mc.getTextureManager()
            .getTexture(TextureMap.locationItemsTexture);
        require(texture instanceof TextureMap, "real_item_atlas_loaded");
        TextureAtlasSprite sprite = ((TextureMap) texture).getAtlasSprite("gtmb:neko_hologram_projector");
        require(
            sprite != null && !sprite.getIconName()
                .equals("missingno"),
            "projector_sprite_registered_in_item_atlas");
        require(
            sprite.hasAnimationMetadata() && sprite.getFrameCount() > 1,
            "projector_atlas_animation_metadata_and_multiple_frames_registered");
        boolean distinct = false;
        int[] first = sprite.getFrameTextureData(0)[0];
        for (int i = 1; i < sprite.getFrameCount(); i++)
            if (!java.util.Arrays.equals(first, sprite.getFrameTextureData(i)[0])) distinct = true;
        require(distinct, "projector_atlas_contains_distinct_animation_frames");
    }

    private NBTTagCompound receipt() throws Exception {
        if (tickReceipt == null) throw new AwaitReceipt();
        return tickReceipt;
    }

    private void require(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] ASSERT PASS " + label);
    }
}
