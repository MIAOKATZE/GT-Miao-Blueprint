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
    private int stage, waitTicks;
    private boolean done;
    private File receipts;
    private HologramScreen realScreen;
    private long lastClickedRender = -1;
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
    public void worldRendered(net.minecraftforge.client.event.RenderWorldLastEvent event) {
        renderedWorldFrames++;
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
                if (stage == 46) keyboard(Keyboard.KEY_RETURN, true);
                return;
            }
            // Validate this tick's real file before any input; publication retries never repeat a sent action.
            tickReceipt = null;
            if (stage >= 2 && receipts != null) {
                if (receiptReader == null) receiptReader = new HologramInteractionReceiptSmoke(receipts);
                tickReceipt = receiptReader.read();
                if (tickReceipt == null) return;
            }
            if (timedStage != stage) {
                timedStage = stage;
                stageEnteredAt = System.nanoTime();
            }
            if ((stage == 11 || stage == 17 || stage == 21 || stage == 24)
                && System.nanoTime() - stageEnteredAt >= 10000000000L) {
                HologramScreen waitingScreen = mc.currentScreen instanceof HologramScreen
                    ? (HologramScreen) mc.currentScreen
                    : null;
                NBTTagCompound waitingState = waitingScreen == null ? null : waitingScreen.smokeSnapshot();
                if (waitingState == null
                    || waitingState.getInteger("job") != 3 && waitingState.getInteger("job") != 5) {
                    String summary = stageSnapshot(waitingState);
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
            if (System.nanoTime() - installed > 600000000000L)
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
            if (stage == 61 || stage == 63) {
                require(mc.currentScreen == null, "root_exit_key_returns_to_real_world_stage_" + stage);
                if (System.nanoTime() - stageEnteredAt >= 10000000000L)
                    throw new IllegalStateException("normal root close observation timed out stage=" + stage);
                if (renderedWorldFrames <= closedAtWorldFrame || receipt().getLong("tick") - closedAtServerTick < 10)
                    return;
                require(mc.currentScreen == null, "normal_root_close_stays_closed_for_ten_server_ticks_" + stage);
                reopenWithRightClick(mc);
                stage = stage == 61 ? 62 : 64;
                return;
            }
            if ((stage >= 40 && stage <= 46 || stage == 50 || stage == 51 || stage == 54 || stage == 55)
                && worldFlow(mc)) return;
            if (!(mc.currentScreen instanceof HologramScreen)) return;
            HologramScreen screen = (HologramScreen) mc.currentScreen;
            if (screen.smokeRenderGeneration() <= lastClickedRender) {
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
                    require(libraryTab(screen), "actual_blueprint_tab_is_peer_workspace_tab");
                    require(
                        java.util.Arrays.equals(preservedGuiSelection, screen.smokeSelectedIndices()),
                        "peer_tab_preserves_preview_selection");
                    closedAtWorldFrame = renderedWorldFrames;
                    closedAtServerTick = receipt().getLong("tick");
                    screen.keyTyped('\0', Keyboard.KEY_ESCAPE);
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
                    screen.keyTyped('e', Keyboard.KEY_E);
                    require(mc.currentScreen == null, "E_from_blueprints_exits_whole_GUI");
                    stage = 63;
                    break;
                case 64:
                    require(libraryTab(screen), "reopen_after_E_preserves_blueprint_tab");
                    require(
                        java.util.Arrays.equals(preservedGuiSelection, screen.smokeSelectedIndices()),
                        "reopen_after_E_preserves_actual_mouse_selection");
                    click(screen, 148);
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
                    lastClickedRender = screen.smokeRenderGeneration();
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
                    lastClickedRender = screen.smokeRenderGeneration();
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
                        "shift_mouse_enter_imports_actual_world_blocks_sufficient_" + sufficientBlueprintPass);
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
                    click(screen, 130);
                    stage = 48;
                    break;
                case 48:
                    java.lang.reflect.Field nameField = HologramScreen.class.getDeclaredField("presetNameField");
                    nameField.setAccessible(true);
                    net.minecraft.client.gui.GuiTextField name = (net.minecraft.client.gui.GuiTextField) nameField
                        .get(screen);
                    name.setFocused(true);
                    for (char character : (sufficientBlueprintPass ? "SocketSufficient" : "SocketV004").toCharArray())
                        screen.keyTyped(character, 0);
                    lastClickedRender = screen.smokeRenderGeneration();
                    stage = 68;
                    break;
                case 68:
                    click(screen, 132);
                    stage = 49;
                    break;
                case 49:
                    require(
                        !state.getString("selectedPresetId")
                            .isEmpty(),
                        "actual_library_save_creates_server_owned_blueprint");
                    click(screen, 141);
                    stage = 50;
                    break;
                case 52:
                    require(
                        state.getBoolean("virtualLocked") && state.getBoolean("blueprintAwaitingConfirmation"),
                        "normal_right_locks_ghost_and_opens_material_confirmation");
                    require(!libraryTab(screen), "position_review_initially_opens_structure_peer_tab");
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
                    click(screen, 141);
                    stage = 54;
                    break;
                case 56:
                    require(!libraryTab(screen), "second_position_review_initially_opens_structure_peer_tab");
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
                        state.getBoolean("forcePlacementReady") && receipt().getInteger("blueprintPlaced") == 1,
                        "first_missing_material_confirmation_only_arms_second_review");
                    logConstructionScope(screen, "whole-blueprint-explicit-second-confirmation");
                    screenshot(mc, "placement-missing-explicit-second-review");
                    click(screen, 55);
                    stage = 58;
                    break;
                case 58:
                    if (state.getInteger("job") != 3 && state.getInteger("job") != 5) return;
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
                    if (Boolean.getBoolean("gtmb.hologram.aeRealInteractionSmoke")) {
                        aeDriver = new HologramAeInteractionClientSmoke(receipts);
                        stage = 100;
                        break;
                    }
                    Files.write(
                        new File(receipts, "client-complete.txt").toPath(),
                        "real socket normal/air C08 + GUI mouse + peer tabs/Esc/E + direct-build/upgrade/full-dismantle + Shift area/Enter/Esc + camera/wheel Place ghost + one-confirm sufficient placement/material cancel/explicit missing confirmation PASS\n"
                            .getBytes(StandardCharsets.UTF_8));
                    done = true;
                    System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] FINAL PASS stage=" + stage);
                    mc.shutdown();
                    break;
                case 66:
                    if (state.getInteger("job") != 3 && state.getInteger("job") != 5) return;
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
                    stage = 40;
                    waitTicks = 0;
                    mc.displayGuiScreen(null);
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
        java.lang.reflect.Field tab = HologramScreen.class.getDeclaredField("libraryDrawer");
        tab.setAccessible(true);
        return tab.getBoolean(screen);
    }

    private int[] selectPlainPreviewFace(HologramScreen screen, NBTTagCompound snapshot) throws Exception {
        HologramState state = new HologramState(snapshot);
        java.lang.reflect.Field originX = HologramScreen.class.getDeclaredField("originX");
        java.lang.reflect.Field originY = HologramScreen.class.getDeclaredField("originY");
        java.lang.reflect.Field scale = HologramScreen.class.getDeclaredField("scale");
        originX.setAccessible(true);
        originY.setAccessible(true);
        scale.setAccessible(true);
        for (int y = HologramScreenLayout.VIEWPORT_Y + 40; y
            < HologramScreenLayout.VIEWPORT_Y + HologramScreenLayout.VIEWPORT_HEIGHT - 50; y += 2)
            for (int x = HologramScreenLayout.VIEWPORT_X + 10; x
                < HologramScreenLayout.VIEWPORT_X + HologramScreenLayout.VIEWPORT_WIDTH - 10; x += 2) {
                    int index = HologramRenderer.pick(x, y);
                    if (index < 0 || !HologramRenderer.pickStructure(x, y)
                        .isEmpty()) continue;
                    HologramState.Cell cell = state.cells.get(index);
                    if (!cell.family.isEmpty() || cell.targetStack == null) continue;
                    int mouseX = originX.getInt(screen) + Math.round(x * scale.getFloat(screen));
                    int mouseY = originY.getInt(screen) + Math.round(y * scale.getFloat(screen));
                    screen.mouseClicked(mouseX, mouseY, 0);
                    screen.mouseMovedOrUp(mouseX, mouseY, 0);
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
            java.lang.reflect.Field field = HologramScreen.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            require(!field.getBoolean(screen), "coil_grade_selection_does_not_open_item_palette_" + fieldName);
        }
        for (String fieldName : new String[] { "inventorySlot", "sourceChoice" }) {
            java.lang.reflect.Field field = HologramScreen.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            require(field.getInt(screen) == -1, "coil_grade_selection_does_not_choose_item_palette_slot_" + fieldName);
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
        java.lang.reflect.Field originX = HologramScreen.class.getDeclaredField("originX");
        java.lang.reflect.Field originY = HologramScreen.class.getDeclaredField("originY");
        java.lang.reflect.Field scale = HologramScreen.class.getDeclaredField("scale");
        originX.setAccessible(true);
        originY.setAccessible(true);
        scale.setAccessible(true);
        int x = originX.getInt(screen) + Math.round(logicalX * scale.getFloat(screen));
        int y = originY.getInt(screen) + Math.round(logicalY * scale.getFloat(screen));
        screen.mouseClicked(x, y, button);
        screen.mouseMovedOrUp(x, y, button);
    }

    /** Inject native LWJGL key state across real client ticks and dispatch the registered Forge mouse event. */
    private boolean worldFlow(Minecraft mc) throws Exception {
        NBTTagCompound state = HologramClient.state == null ? null : HologramClient.state.data;
        if (stage == 40 || stage == 42) {
            if (stage == 42 && (state == null || state.getBoolean("areaSelecting"))) return true;
            mc.displayGuiScreen(null);
            keyboard(Keyboard.KEY_RETURN, false);
            keyboard(Keyboard.KEY_LSHIFT, true);
            rightClickSource(mc);
            stage = stage == 40 ? 41 : 43;
            waitTicks = 0;
            return true;
        }
        if (stage == 41 || stage == 43) {
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
            keyboard(Keyboard.KEY_RETURN, true);
            stage = 46;
            return true;
        }
        if (stage == 46) {
            if (!(mc.currentScreen instanceof HologramScreen) || state == null || !awaitStoneArea(state)) return true;
            keyboard(Keyboard.KEY_RETURN, false);
            lastClickedRender = -1;
            stage = 47;
            return true;
        }
        if (stage == 50 || stage == 54) {
            if (state == null || !state.getBoolean("moving") || mc.currentScreen != null) return true;
            if (++waitTicks < 4) return true;
            require(
                state.getBoolean("virtualPlacement") && !state.getBoolean("virtualLocked"),
                "library_Place_enters_unlocked_world_ghost_stage_" + stage);
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
                java.util.Arrays.equals(expectedAirAnchor, HologramClient.movingAnchor(HologramClient.state)),
                "rendered_air_ghost_and_right_click_share_exact_camera_target_stage_" + stage);
            screenshot(mc, "air-camera-scroll-ghost-stage-" + stage);
            mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
            airPlacementPrepared = false;
            stage = stage == 50 ? 51 : 55;
            waitTicks = 0;
            return true;
        }
        if (stage == 51 || stage == 55) {
            if (!(mc.currentScreen instanceof HologramScreen) || state == null || !state.getBoolean("virtualLocked"))
                return true;
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
            System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] PASS actual air placement precheck "
                    + ((HologramScreen) mc.currentScreen).smokePlacementReviewContext());
            logConstructionScope((HologramScreen) mc.currentScreen, "actual-air-position-precheck-whole-blueprint");
            lastClickedRender = -1;
            stage = stage == 51 ? 52 : 56;
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

    private void mouseCorner(Minecraft mc, int button, int x) throws Exception {
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
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(wheel);
        require(wheel.isCanceled(), "real_registered_moving_wheel_consumed_without_hotbar_change");
    }

    private static void rightClickSource(Minecraft mc) {
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

    private void click(HologramScreen screen, int id) throws Exception {
        awaitControl(screen, id);
        screen.smokeClickControl(id);
        lastClickedRender = screen.smokeRenderGeneration();
        System.out.println("[GTMB-HOLOGRAM-INTERACTION-CLIENT] mouse control=" + id + " stage=" + stage);
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
        lastClickedRender = -1;
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
