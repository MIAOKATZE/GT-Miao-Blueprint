package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Optional real mouse/socket/disk witness, embedded before the existing placement and Escape scenarios. */
final class HologramLibraryUiSocketSmoke {

    private final File receipts;
    private final Runnable reopen;
    private final java.util.function.BooleanSupplier worldRendered;
    private final long started = System.nanoTime();
    private int stage;
    private long clickedRender = -1, configRevision, cancelTick;
    private HologramScreen clickedScreen;
    private long op3AckRender = -1, op4AckRender = -1;
    private int originalUpload, originalDownload, originalQuota, stock, placed;
    private String localId, publicId, downloadedId;
    private boolean done;

    HologramLibraryUiSocketSmoke(File receipts, Runnable reopen, java.util.function.BooleanSupplier worldRendered) {
        this.receipts = receipts;
        this.reopen = reopen;
        this.worldRendered = worldRendered;
    }

    boolean complete() {
        return done;
    }

    boolean tick(Minecraft mc, NBTTagCompound worldReceipt) throws Exception {
        if (done) return true;
        if (System.nanoTime() - started > 180000000000L)
            throw new IllegalStateException("Library UI socket timeout stage=" + stage);
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        HologramScreen screen = mc.currentScreen instanceof HologramScreen ? (HologramScreen) mc.currentScreen : null;
        NBTTagCompound state = HologramClient.state == null ? new NBTTagCompound() : HologramClient.state.data;
        // A new preview ACK can install a screen before its first actual frame creates hit controls.
        if (screen != null && screen.smokeRenderGeneration() == 0) return false;
        if (screen != null && screen == clickedScreen && screen.smokeRenderGeneration() <= clickedRender) return false;
        try {
            switch (stage) {
                case 0:
                    if (screen == null || library.busy()
                        || library.selectedRow(false)
                            .getString("id")
                            .isEmpty())
                        return false;
                    require(
                        HologramLibraryClient.dedicated() && !mc.getNetHandler()
                            .getNetworkManager()
                            .isLocalChannel(),
                        "real dedicated socket");
                    localId = library.selected(false);
                    JsonObject blueprint = fileBlueprint(mc, localId);
                    require(
                        blueprint.get("authorName")
                            .getAsString()
                            .equals(mc.thePlayer.getCommandSenderName()),
                        "saved file current player author");
                    require(
                        blueprint.get("authorUuid")
                            .getAsString()
                            .equals(
                                worldReceipt.getCompoundTag("blueprintSession")
                                    .getString("actor")),
                        "saved file canonical server creator UUID");
                    require(
                        blueprint.get("createdAt")
                            .getAsLong() > 0
                            && blueprint.getAsJsonArray("cells")
                                .size() > 0,
                        "saved file timestamp and cells");
                    stock = worldReceipt.getInteger("blueprintStock");
                    placed = worldReceipt.getInteger("blueprintPlaced");
                    screenshot(mc, "v009-client-library");
                    requestLevel("3");
                    stage = 1;
                    return false;
                case 1:
                    if (!nativeLevel("op3", 3, mc)) return false;
                    library.open();
                    stage = 2;
                    return false;
                case 2:
                    if (screen == null || library.busy()
                        || library.config()
                            .hasNoTags()
                        || library.config()
                            .getBoolean("admin"))
                        return false;
                    if (op3AckRender < 0) {
                        op3AckRender = screen.smokeRenderGeneration();
                        return false;
                    }
                    if (screen.smokeRenderGeneration() <= op3AckRender) return false;
                    require(
                        !screen.smokeLibraryUiState()
                            .getBoolean("adminVisible"),
                        "OP3 has no admin widget");
                    observe("library-ui-op3-client.nbt", screen.smokeLibraryUiState());
                    screenshot(mc, "v009-library-op3-hidden");
                    requestLevel("4");
                    stage = 3;
                    return false;
                case 3:
                    if (!nativeLevel("op4", 4, mc)) return false;
                    library.open();
                    stage = 4;
                    return false;
                case 4:
                    if (screen == null || library.busy()
                        || !library.config()
                            .getBoolean("admin"))
                        return false;
                    if (op4AckRender < 0) {
                        op4AckRender = screen.smokeRenderGeneration();
                        return false;
                    }
                    if (screen.smokeRenderGeneration() <= op4AckRender) return false;
                    require(
                        screen.smokeLibraryUiState()
                            .getBoolean("adminVisible"),
                        "OP4 has admin widget");
                    originalUpload = library.config()
                        .getInteger("uploadKiB");
                    originalDownload = library.config()
                        .getInteger("downloadKiB");
                    originalQuota = library.config()
                        .getInteger("maxUploads");
                    configRevision = library.config()
                        .getLong("configRevision");
                    click(screen, 207);
                    stage = 5;
                    return false;
                case 5:
                    if (screen == null || !screen.smokeLibraryUiState()
                        .getBoolean("adminDrawer")) return false;
                    screenshot(mc, "v009-library-op4-settings");
                    typeAt(screen, 350, 148, "17");
                    typeAt(screen, 350, 183, "19");
                    typeAt(screen, 350, 218, "11");
                    click(screen, 210);
                    stage = 6;
                    return false;
                case 6:
                    if (screen == null || library.config()
                        .getLong("configRevision") <= configRevision) return false;
                    require(
                        library.config()
                            .getInteger("uploadKiB") == 17
                            && library.config()
                                .getInteger("downloadKiB") == 19
                            && library.config()
                                .getInteger("maxUploads") == 11,
                        "real OP4 config revision ACK");
                    if (!nativeConfig(library.config())) return false;
                    observe("library-ui-op4-client.nbt", screen.smokeLibraryUiState());
                    screenshot(mc, "v009-library-op4-config-ack");
                    configRevision = library.config()
                        .getLong("configRevision");
                    typeAt(screen, 350, 148, String.valueOf(originalUpload));
                    typeAt(screen, 350, 183, String.valueOf(originalDownload));
                    typeAt(screen, 350, 218, String.valueOf(originalQuota));
                    click(screen, 210);
                    stage = 7;
                    return false;
                case 7:
                    if (screen == null || library.config()
                        .getLong("configRevision") <= configRevision) return false;
                    require(
                        library.config()
                            .getInteger("uploadKiB") == originalUpload
                            && library.config()
                                .getInteger("downloadKiB") == originalDownload
                            && library.config()
                                .getInteger("maxUploads") == originalQuota,
                        "fixture config restored through real UI");
                    if (!nativeConfig(library.config())) return false;
                    click(screen, 211);
                    requestLevel("restore");
                    stage = 8;
                    return false;
                case 8:
                    if (!nativeLevel("restore", -1, mc) || screen == null || library.busy()) return false;
                    click(screen, 203);
                    stage = 9;
                    return false;
                case 9:
                    if (screen == null || library.busy()) return false;
                    publicId = library.selected(true);
                    if (publicId.isEmpty()) return false;
                    NBTTagCompound publication = read("library-ui-upload.nbt");
                    if (publication == null || !publicId.equals(publication.getString("id"))) return false;
                    require(
                        publication.getBoolean("nativeOwnerVerified") && publication.getBoolean("realDedicatedSocket"),
                        "native public owner witness");
                    click(screen, 201);
                    stage = 10;
                    return false;
                case 10:
                    if (screen == null || !screen.smokeLibraryUiState()
                        .getBoolean("public")) return false;
                    int index = index(library.rows(true), publicId);
                    if (index < 0) return false;
                    require(
                        library.rows(true)
                            .getCompoundTagAt(index)
                            .getBoolean("mine"),
                        "public delete authority mirrors verified uploader");
                    click(screen, 14000 + index);
                    stage = 11;
                    return false;
                case 11:
                    if (screen == null || library.busy() || !publicId.equals(state.getString("selectedPresetId")))
                        return false;
                    NBTTagCompound publicBrowse = screen.smokeLibraryUiState();
                    require(
                        publicBrowse.getBoolean("library") && publicBrowse.getBoolean("public")
                            && "blueprintBrowse".equals(publicBrowse.getString("openPurpose")),
                        "public preview acknowledgement keeps the server browse list ready for actual placement");
                    observe("library-ui-public-choice-client.nbt", screen.smokeLibraryUiState());
                    screenshot(mc, "v009-server-library");
                    click(screen, 141);
                    stage = 12;
                    return false;
                case 12:
                    if (screen != null || !state.getBoolean("moving")
                        || !publicId.equals(state.getString("selectedPresetId"))) return false;
                    if (!worldRendered.getAsBoolean()) return false;
                    require(
                        !state.getBoolean("virtualLocked") && HologramRenderer.lastWorldGeometry > 0,
                        "direct public choice becomes actual world ghost");
                    require(
                        worldReceipt.getInteger("blueprintStock") == stock
                            && worldReceipt.getInteger("blueprintPlaced") == placed,
                        "public placement preview consumes no inventory");
                    screenshot(mc, "v009-public-blueprint-world-preview");
                    cancelTick = worldReceipt.getLong("tick");
                    mc.displayGuiScreen(new GuiIngameMenu());
                    require(mc.currentScreen == null, "public moving Escape retains existing world cancel flow");
                    stage = 13;
                    return false;
                case 13:
                    if (state.getBoolean("virtualPlacement") || worldReceipt.getLong("tick") <= cancelTick
                        || worldReceipt.getCompoundTag("blueprintSession")
                            .getBoolean("virtualPlacement"))
                        return false;
                    mc.objectMouseOver = null;
                    reopen.run();
                    stage = 14;
                    return false;
                case 14:
                    if (screen == null || !screen.smokeLibraryUiState()
                        .getBoolean("library")) return false;
                    if (!screen.smokeLibraryUiState()
                        .getBoolean("public")) {
                        click(screen, 201);
                        return false;
                    }
                    click(screen, 203);
                    stage = 15;
                    return false;
                case 15:
                    if (screen == null || library.busy()
                        || library.selected(false)
                            .equals(localId))
                        return false;
                    downloadedId = library.selected(false);
                    JsonObject downloaded = fileBlueprint(mc, downloadedId), original = fileBlueprint(mc, localId);
                    require(
                        downloaded.getAsJsonArray("cells")
                            .size()
                            == original.getAsJsonArray("cells")
                                .size()
                            && downloaded.get("authorName")
                                .getAsString()
                                .equals(
                                    original.get("authorName")
                                        .getAsString()),
                        "public download saves an independent client file preserving creator and cells");
                    observe("library-ui-download-client.nbt", screen.smokeLibraryUiState());
                    click(screen, 205);
                    stage = 16;
                    return false;
                case 16:
                    if (screen == null) return false;
                    NBTTagCompound deleteReview = screen.smokeLibraryUiState();
                    require(
                        deleteReview.getBoolean("deleteReview")
                            && publicId.equals(deleteReview.getString("deletePresetId")),
                        "actual owner deletion confirmation names the selected public blueprint");
                    observe("library-ui-delete-review-client.nbt", deleteReview);
                    click(screen, 176);
                    stage = 17;
                    return false;
                case 17:
                    if (screen == null || index(library.rows(true), publicId) >= 0
                        || !new File(receipts, "library-ui-delete-server-pass.txt").isFile()) return false;
                    click(screen, 201);
                    stage = 18;
                    return false;
                case 18:
                    if (screen == null || screen.smokeLibraryUiState()
                        .getBoolean("public")) return false;
                    int originalIndex = index(library.rows(false), localId);
                    screenshot(mc, "v009-download-client-copy");
                    if (originalIndex < 0) throw new IllegalStateException(
                        "Original private blueprint disappeared during public operations");
                    click(screen, 14000 + originalIndex);
                    stage = 19;
                    return false;
                case 19:
                    if (screen == null || library.busy() || !localId.equals(state.getString("libraryLocalId")))
                        return false;
                    require(
                        fileBlueprint(mc, localId).getAsJsonArray("cells")
                            .size() > 0 && !localId.equals(downloadedId),
                        "public delete leaves original portable private file and downloaded copy");
                    observe("library-ui-final-client.nbt", screen.smokeLibraryUiState());
                    Files.write(
                        new File(receipts, "library-ui-client-pass.txt").toPath(),
                        ("PASS real dedicated UI private save/native OP3 hidden/native OP4 config revision/public upload/choice/place/Escape/download/own deletion "
                            + localId
                            + " "
                            + publicId
                            + " "
                            + downloadedId
                            + "\n").getBytes(StandardCharsets.UTF_8));
                    System.out
                        .println("[GTMB-LIBRARY-UI-SOCKET-CLIENT] PASS real mouse/socket/native disk library flow");
                    done = true;
                    return true;
                default:
                    throw new IllegalStateException("Unknown library UI stage " + stage);
            }
        } catch (AwaitEnabledDraw pendingDraw) {
            return false;
        }
    }

    private boolean nativeLevel(String phase, int level, Minecraft mc) throws Exception {
        NBTTagCompound receipt = read("library-ui-op-ready.nbt");
        if (receipt == null || !phase.equals(receipt.getString("phase"))) return false;
        require(
            receipt.getBoolean("nativeVerified") && mc.thePlayer.getCommandSenderName()
                .equals(receipt.getString("actorName")),
            "actual socket native OP profile");
        if (level >= 0) require(
            receipt.getInteger("actualLevel") == level && receipt.getBoolean("admin") == (level == 4),
            "native OP level and permission");
        else require(receipt.getBoolean("baselineRestored"), "native OP baseline restored");
        return true;
    }

    private boolean nativeConfig(NBTTagCompound config) throws Exception {
        NBTTagCompound witness = read("library-ui-config.nbt");
        if (witness == null || witness.getLong("configRevision") != config.getLong("configRevision")) return false;
        require(
            witness.getBoolean("nativeConfigVerified")
                && witness.getInteger("uploadKiB") == config.getInteger("uploadKiB")
                && witness.getInteger("downloadKiB") == config.getInteger("downloadKiB")
                && witness.getInteger("maxUploads") == config.getInteger("maxUploads"),
            "server config physical file matches acknowledged revision");
        return true;
    }

    private void requestLevel(String phase) throws Exception {
        Path target = new File(receipts, "library-ui-op-request.txt").toPath();
        Path temporary = Files.createTempFile(target.getParent(), "op-request-", ".tmp");
        Files.write(temporary, phase.getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private NBTTagCompound read(String name) throws Exception {
        File file = new File(receipts, name);
        if (!file.isFile()) return null;
        try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    private void observe(String name, NBTTagCompound observation) throws Exception {
        Files.write(new File(receipts, name).toPath(), CompressedStreamTools.compress(observation));
    }

    private static void screenshot(Minecraft mc, String name) {
        String filename = "hologram-network-" + name + ".png";
        net.minecraft.util.ScreenShotHelper
            .saveScreenshot(mc.mcDataDir, filename, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        File file = new File(mc.mcDataDir, "screenshots/" + filename);
        require(file.isFile() && file.length() > 0, "actual rendered library screenshot " + name);
    }

    private static JsonObject fileBlueprint(Minecraft mc, String id) throws Exception {
        require(id.matches("local_[a-f0-9-]{36}"), "canonical private file ID");
        Path path = mc.mcDataDir.toPath()
            .resolve("MiaoBlueprints/" + id + ".json");
        JsonObject root = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
            .getAsJsonObject();
        return root.getAsJsonObject("blueprint");
    }

    private void click(HologramScreen screen, int id) throws Exception {
        NBTTagList drawn = screen.smokeLibraryUiState()
            .getTagList("drawnControls", 10);
        for (int i = 0; i < drawn.tagCount(); i++) {
            NBTTagCompound control = drawn.getCompoundTagAt(i);
            if (control.getInteger("id") == id && !control.getBoolean("enabled")) {
                clickedScreen = screen;
                clickedRender = screen.smokeRenderGeneration();
                throw new AwaitEnabledDraw();
            }
        }
        clickedScreen = screen;
        clickedRender = screen.smokeRenderGeneration();
        try {
            screen.smokeClickControl(id);
        } catch (RuntimeException failure) {
            NBTTagCompound observation = screen.smokeLibraryUiState();
            observation.setInteger("probeStage", stage);
            observation.setInteger("requestedControl", id);
            observation.setString("failure", failure.toString());
            observe("library-ui-failure-client.nbt", observation);
            screenshot(Minecraft.getMinecraft(), "v009-library-failure-stage-" + stage + "-control-" + id);
            throw failure;
        }
    }

    private static final class AwaitEnabledDraw extends RuntimeException {
    }

    private static int index(NBTTagList rows, String id) {
        for (int i = 0; i < rows.tagCount(); i++) if (id.equals(
            rows.getCompoundTagAt(i)
                .getString("id")))
            return i;
        return -1;
    }

    private static void typeAt(HologramScreen screen, int x, int y, String text) throws Exception {

        screen.smokeClickAt(
            ((Integer) HologramUiDiagnostics.readDiagnosticField(screen, "originX"))
                + Math.round(x * ((Float) HologramUiDiagnostics.readDiagnosticField(screen, "scale"))),
            ((Integer) HologramUiDiagnostics.readDiagnosticField(screen, "originY"))
                + Math.round(y * ((Float) HologramUiDiagnostics.readDiagnosticField(screen, "scale"))),
            0);
        java.lang.reflect.Field keys = Keyboard.class.getDeclaredField("keyDownBuffer");
        keys.setAccessible(true);
        java.nio.ByteBuffer buffer = (java.nio.ByteBuffer) keys.get(null);
        buffer.put(Keyboard.KEY_LCONTROL, (byte) 1);
        try {
            screen.keyTyped((char) 1, Keyboard.KEY_A);
        } finally {
            buffer.put(Keyboard.KEY_LCONTROL, (byte) 0);
        }
        for (char character : text.toCharArray()) screen.keyTyped(character, 0);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Library UI: " + label);
    }
}
