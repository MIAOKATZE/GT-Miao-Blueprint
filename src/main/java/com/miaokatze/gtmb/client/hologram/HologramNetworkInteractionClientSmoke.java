package com.miaokatze.gtmb.client.hologram;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.hologram.HologramNetworkInteractionSmoke;

/** Actual rendered mouse/text events with normal Netty dispatch and server ACKs; no intercepted packets. */
public final class HologramNetworkInteractionClientSmoke {

    private static final String PREFIX = "[GTMB-NETWORK-INTERACTION-CLIENT] ";
    private static int stage, settling;
    private static long stageStarted, lastActionTick = Long.MIN_VALUE;
    private static String observationKey = "";
    private static boolean done;
    private static Throwable failure;
    private static Path output;
    private static final NBTTagCompound receipt = new NBTTagCompound();

    private HologramNetworkInteractionClientSmoke() {}

    public static boolean done() {
        return !HologramNetworkInteractionSmoke.enabled() || done;
    }

    public static String status() {
        return !HologramNetworkInteractionSmoke.enabled() ? "DISABLED"
            : failure != null ? "FAIL" : done ? "PASS" : "PENDING";
    }

    public static Throwable failure() {
        return failure;
    }

    public static void clientTick(Minecraft mc, long frame) throws Exception {
        if (!HologramNetworkInteractionSmoke.enabled() || done) return;
        if (failure != null) throw new IllegalStateException("Network client probe failed", failure);
        if (HologramNetworkInteractionSmoke.failure() != null)
            throw new IllegalStateException("Network server probe failed", HologramNetworkInteractionSmoke.failure());
        if (!HologramNetworkInteractionSmoke.ready()) return;
        try {
            if (output == null) {
                String configured = System.getProperty("gtmb.hologram.productionPreviewOutput", "");
                output = Paths
                    .get(
                        configured.isEmpty()
                            ? System.getProperty("gtmb.hologram.packagedProbeDir", mc.mcDataDir.getPath()) + "/receipts"
                            : configured)
                    .toAbsolutePath()
                    .normalize();
                Files.createDirectories(output);
            }
            observe(mc, frame);
            if (stageStarted == 0) stageStarted = System.nanoTime();
            if (System.nanoTime() - stageStarted > 30000000000L)
                throw new IllegalStateException("Actual network GUI/ACK timed out stage=" + stage);
            if (!(mc.currentScreen instanceof HologramScreen) || HologramClient.state == null
                || !HologramNetworkInteractionSmoke.session()
                    .equals(HologramClient.state.session))
                return;
            HologramScreen screen = (HologramScreen) mc.currentScreen;
            require(!(Boolean) field(screen, "captureRenderSmokeAction"), "normal_netty_dispatch_enabled");
            long tick = mc.theWorld.getTotalWorldTime();
            if (tick == lastActionTick || ++settling < 2) return;
            HologramState state = HologramClient.state;
            NBTTagList groups = state.data.getCompoundTag("networkBindings")
                .getTagList("groups", 10);
            switch (stage) {
                case 0:
                    require(
                        state.cells.size() == 3 && groups.tagCount() == 2,
                        "actual_snapshot_two_network_groups_three_devices");
                    receipt.setString("session", state.session);
                    require(
                        state.data.getLong("placementReviewNonce") > 0 && screen.retainsPlacementReview(state),
                        "actual_opening_snapshot_installs_production_material_review_context");
                    if (!(Boolean) field(screen, "networkDrawer")) click(screen, 196);
                    else receipt.setBoolean("rawLiveAreaAutomaticallyOpenedBindingChooser", true);
                    next(1, tick);
                    break;
                case 1:
                    if (screen.smokePending() || !(Boolean) field(screen, "networkDrawer")) return;
                    screenshot(mc, "network-native-inventory-chooser.png");
                    assertCarrier(state, 20);
                    assertCarrier(state, 21);
                    click(screen, 60000);
                    next(2, tick);
                    break;
                case 2:
                    click(screen, 61020);
                    next(3, tick);
                    break;
                case 3:
                    if (screen.smokePending() || !groups.getCompoundTagAt(0)
                        .getBoolean("bound")) return;
                    receipt.setBoolean("groupABoundAck", true);
                    typeHint(screen, "Production quantum group A");
                    click(screen, 200);
                    next(4, tick);
                    break;
                case 4:
                    if (screen.smokePending() || !"Production quantum group A".equals(
                        groups.getCompoundTagAt(0)
                            .getString("prompt")))
                        return;
                    receipt.setBoolean("groupAHintAck", true);
                    click(screen, 60001);
                    next(5, tick);
                    break;
                case 5:
                    click(screen, 61021);
                    next(6, tick);
                    break;
                case 6:
                    if (screen.smokePending() || !groups.getCompoundTagAt(1)
                        .getBoolean("bound")) return;
                    receipt.setBoolean("groupBBoundAck", true);
                    typeHint(screen, "Production quantum group B");
                    click(screen, 200);
                    next(7, tick);
                    break;
                case 7:
                    if (screen.smokePending() || !"Production quantum group B".equals(
                        groups.getCompoundTagAt(1)
                            .getString("prompt")))
                        return;
                    receipt.setBoolean("groupBHintAck", true);
                    screenshot(mc, "network-native-two-group-hints.png");
                    click(screen, 197);
                    next(8, tick);
                    break;
                case 8:
                    if (screen.smokePending() || (Boolean) field(screen, "networkDrawer")) return;
                    // Display-only view choices; packet dispatch, source state and placement capability stay intact.
                    set(screen, "confirmation", false);
                    set(screen, "libraryDrawer", false);
                    set(screen, "view", 0);
                    receipt.setString("annotations", HologramQuantumAnnotationClientProbe.verifyActiveScreen());
                    screenshot(mc, "network-native-group-markers.png");
                    click(screen, 130);
                    next(9, tick);
                    break;
                case 9:
                    set(screen, "confirmation", false);
                    click(screen, 140);
                    next(10, tick);
                    break;
                case 10:
                    if (screen.smokePending() || field(screen, "aeReview") == null) return;
                    screenshot(mc, "network-native-hint-save-review.png");
                    click(screen, 181);
                    next(11, tick);
                    break;
                case 11:
                    if (screen.smokePending() || !state.data.getBoolean("blueprintOperationSuccess")) return;
                    require(
                        groups.getCompoundTagAt(0)
                            .getBoolean("bound")
                            && groups.getCompoundTagAt(1)
                                .getBoolean("bound"),
                        "saving_local_hints_preserves_current_verified_binding_scope");
                    receipt.setBoolean("actualClientConfirmedHintSave", true);
                    set(screen, "confirmation", false);
                    click(screen, 148);
                    next(12, tick);
                    break;
                case 12:
                    if (screen.smokePending()) return;
                    if (!(Boolean) field(screen, "confirmation")) click(screen, 26);
                    next(13, tick);
                    break;
                case 13:
                    if (screen.smokePending() || !(Boolean) field(screen, "confirmation")) return;
                    screenshot(mc, "network-native-actual-material-confirmation.png");
                    require(
                        state.data.getInteger("missing") == 0,
                        "real_survival_materials_complete_before_client_confirm");
                    require(
                        groups.getCompoundTagAt(0)
                            .getBoolean("bound")
                            && groups.getCompoundTagAt(1)
                                .getBoolean("bound")
                            && screen.retainsPlacementReview(state),
                        "real_material_refresh_preserves_verified_bindings_and_review_context");
                    click(screen, 55);
                    next(14, tick);
                    break;
                case 14:
                    if (screen.smokePending() || state.data.getInteger("job") == 1 || state.data.getInteger("job") == 2)
                        return;
                    require(
                        state.data.getInteger("job") == 3,
                        "actual_client_confirm_started_and_completed_service_construction");
                    write("network-client-built-state.nbt", state.data);
                    Files.write(
                        output.resolve("network-client-built.txt"),
                        "actual GUI Netty confirm build completed\n".getBytes(StandardCharsets.UTF_8));
                    receipt.setBoolean("actualClientConstructionAck", true);
                    next(15, tick);
                    break;
                case 15:
                    if (!HologramNetworkInteractionSmoke.done()) return;
                    require(
                        "PASS".equals(HologramNetworkInteractionSmoke.status()),
                        "server_owner_material_and_rollback_receipt_passed");
                    receipt.setBoolean("actualNettyClient", true);
                    receipt.setBoolean("passed", true);
                    write("network-client-final.nbt", receipt);
                    Files.write(
                        output.resolve("NETWORK_CLIENT_PASS.txt"),
                        (PREFIX
                            + "FINAL PASS actual two-group inventory selection, hint typing/save, hover/pin and construction\n")
                                .getBytes(StandardCharsets.UTF_8));
                    done = true;
                    System.out.println(
                        PREFIX
                            + "FINAL PASS actual two-group inventory selection, hint typing/save, hover/pin and construction");
                    break;
                default:
                    throw new IllegalStateException("Unknown network client stage " + stage);
            }
        } catch (Throwable problem) {
            failure = problem;
            receipt.setInteger("stage", stage);
            receipt.setString("failure", problem.toString());
            if (output != null) {
                write("network-client-failure.nbt", receipt);
                if (HologramClient.state != null) write("network-client-failure-state.nbt", HologramClient.state.data);
                screenshot(mc, "network-native-failure-stage-" + stage + ".png");
            }
            throw new IllegalStateException(PREFIX + "FAIL stage=" + stage, problem);
        }
    }

    private static void assertCarrier(HologramState state, int slot) {
        NBTTagList inventory = state.data.getCompoundTag("networkBindings")
            .getTagList("inventory", 10);
        for (int i = 0; i < inventory.tagCount(); i++) if (inventory.getCompoundTagAt(i)
            .getInteger("slot") == slot) {
                NBTTagCompound row = inventory.getCompoundTagAt(i);
                require(
                    row.getBoolean("available") && row.getBoolean("carrier"),
                    "server_verified_real_coordinate_carrier_slot_" + slot);
                require(
                    !row.getCompoundTag("item")
                        .hasKey("tag"),
                    "chooser_display_does_not_receive_anchor_identity_slot_" + slot);
                return;
            }
        throw new IllegalStateException("Real carrier inventory row is missing at slot " + slot);
    }

    private static void typeHint(HologramScreen screen, String text) throws Exception {

        int[] point = (int[]) HologramUiDiagnostics
            .invokeDiagnostic(screen, "smokePreviewPoint", new Class<?>[] { int.class, int.class }, 120, 229);
        HologramMuiProbeInput.click(screen, point[0], point[1], 0);
        HologramMuiTextField editor = (HologramMuiTextField) field(screen, "networkHintField");
        require(editor.isFocused(), "actual_mouse_focused_group_hint_field");
        String before = editor.getText();
        // Stage the ordinary Ctrl+A input through MUI2, including its native modifier check.
        HologramMuiProbeInput.key(screen, (char) 1, Keyboard.KEY_A);
        require(before.equals(editor.getSelectedText()), "actual_ctrl_a_selects_existing_group_hint");
        for (char c : text.toCharArray()) {
            HologramMuiProbeInput.key(screen, c, 0);
        }
        String actual = editor.getText();
        NBTTagCompound typed = new NBTTagCompound();
        typed.setString("expected", text);
        typed.setString("actual", actual);
        typed.setString("before", before);
        typed.setString("driver", "MUI2 screen key and text events");
        typed.setBoolean("focused", editor.isFocused());
        typed.setString("selectedText", editor.getSelectedText());
        receipt.setTag("hintTypingStage" + stage, typed);
        write("network-client-hint-typing-stage-" + stage + ".nbt", typed);
        System.out.println(
            PREFIX + "HINT expected=[" + text + "] actual=[" + actual + "] driver=" + typed.getString("driver"));
        require(
            text.equals(actual),
            "actual_gui_keyboard_path_retains_group_hint_text expected=[" + text + "] actual=[" + actual + "]");
    }

    private static void click(HologramScreen screen, int id) throws Exception {
        HologramMuiProbeInput.renderFixture(screen, 0, 0, 0);
        int[] point = screen.smokeControlPoint(id);
        HologramMuiProbeInput.click(screen, point[0], point[1], 0);
    }

    private static void next(int value, long tick) {
        stage = value;
        stageStarted = System.nanoTime();
        settling = 0;
        lastActionTick = tick;
        System.out.println(PREFIX + "stage=" + stage);
    }

    private static Object field(HologramScreen screen, String name) throws Exception {

        return ((Object) HologramUiDiagnostics.readDiagnosticField(screen, name));
    }

    private static void observe(Minecraft mc, long frame) throws Exception {
        NBTTagCompound observed = new NBTTagCompound();
        observed.setInteger("stage", stage);
        observed.setLong("clientFrame", frame);
        observed.setLong("lastActionWorldTick", lastActionTick);
        observed.setLong("worldTotalTime", mc.theWorld == null ? -1 : mc.theWorld.getTotalWorldTime());
        observed.setBoolean("gamePaused", mc.isGamePaused());
        observed.setString("expectedSession", HologramNetworkInteractionSmoke.session());
        observed.setString(
            "guiClass",
            mc.currentScreen == null ? ""
                : mc.currentScreen.getClass()
                    .getName());
        HologramState state = HologramClient.state;
        if (state != null) {
            observed.setString("receivedSession", state.session);
            observed.setLong("receivedUiSequence", state.data.getLong("uiSequence"));
            observed.setLong("receivedPlanRevision", state.data.getLong("planRevision"));
            observed.setBoolean("receivedOpen", state.data.getBoolean("open"));
            observed.setLong("placementReviewNonce", state.data.getLong("placementReviewNonce"));
            observed.setString("openPurpose", state.data.getString("openPurpose"));
            observed.setString("receivedStatus", state.data.getString("status"));
            observed.setTag(
                "networkBindings",
                state.data.getCompoundTag("networkBindings")
                    .copy());
        }
        if (mc.currentScreen instanceof HologramScreen) {
            HologramScreen screen = (HologramScreen) mc.currentScreen;
            HologramState shown = (HologramState) field(screen, "state");
            observed.setString("shownSession", shown.session);
            observed.setLong("shownUiSequence", shown.data.getLong("uiSequence"));
            observed.setLong("sentSequence", (Long) field(screen, "sequence"));
            observed.setLong("pendingSequence", (Long) field(screen, "pendingSequence"));
            observed.setBoolean("networkDrawer", (Boolean) field(screen, "networkDrawer"));
            observed.setBoolean("confirmation", (Boolean) field(screen, "confirmation"));
        }
        receipt.setTag("lastObservation", observed);
        String key = stage + "/"
            + observed.getString("guiClass")
            + "/"
            + observed.getString("receivedSession")
            + "/"
            + observed.getLong("receivedUiSequence")
            + "/"
            + observed.getLong("pendingSequence");
        if (!key.equals(observationKey)) {
            observationKey = key;
            write("network-client-observation-stage-" + stage + ".nbt", observed);
            System.out.println(
                PREFIX + "OBS stage="
                    + stage
                    + " gui="
                    + observed.getString("guiClass")
                    + " serverAck="
                    + observed.getLong("receivedUiSequence")
                    + " pending="
                    + observed.getLong("pendingSequence")
                    + " frame="
                    + frame
                    + " worldTime="
                    + observed.getLong("worldTotalTime"));
        }
    }

    private static void set(HologramScreen screen, String name, Object value) throws Exception {

        HologramUiDiagnostics.writeDiagnosticField(screen, name, value);
    }

    private static void screenshot(Minecraft mc, String name) throws Exception {
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        Files.copy(
            mc.mcDataDir.toPath()
                .resolve("screenshots")
                .resolve(name),
            output.resolve(name),
            StandardCopyOption.REPLACE_EXISTING);
    }

    private static void write(String name, NBTTagCompound data) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(
            output.resolve(name)
                .toFile())) {
            CompressedStreamTools.writeCompressed(data, stream);
        }
    }

    private static void require(boolean value, String name) {
        if (!value) throw new IllegalStateException(PREFIX + "FAIL " + name);
        if (!name.equals("normal_netty_dispatch_enabled")) System.out.println(PREFIX + "PASS " + name);
    }
}
