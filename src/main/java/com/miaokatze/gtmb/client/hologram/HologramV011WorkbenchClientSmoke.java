package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramV011Evidence;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in paired Socket author-workbench driver. All changes use native GUI/input and ordinary Netty dispatch. */
public final class HologramV011WorkbenchClientSmoke {

    private interface Probe {

        boolean run(Minecraft mc, HologramScreen screen, NBTTagCompound ui) throws Exception;
    }

    private static final class Step {

        final String label;
        final Probe probe;

        Step(String label, Probe probe) {
            this.label = label;
            this.probe = probe;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private final List<String> receipts = new ArrayList<>();
    private final Set<String> savedIds = new LinkedHashSet<>();
    private final NBTTagCompound findings = new NBTTagCompound();
    private final HologramActualUsePress press = new HologramActualUsePress();
    private final long installed = System.nanoTime();
    private long started, lastGeneration = -1, expectedSequence;
    private long processingRequestSequence = -1, saveRequestActionSequence = -1;
    private long saveRequestRevision, saveRequestGeneration, saveRequestLastActivity;
    private long lastPreviewCaptureGeneration;
    private HologramScreen lastScreen, captureScreen, lastRenderedScreen;
    private HologramState lastRenderedState;
    private long lastRenderedGeneration;
    private NBTTagCompound capture, baseline, nativeScreenData, savedReviewPolicy, savedReviewDraft;
    private NBTTagCompound fullReviewSelection;
    private HologramBlueprintArtifact.Artifact savedReviewArtifact, overwriteOriginalArtifact, deleteOriginalArtifact;
    private NBTTagList sourceCards, sourceNativeItems, sourceNativeRelations, savedReviewRelations;
    private final NBTTagList fullReviewSelections = new NBTTagList();
    private int step, ncCell = -1, stoneCell = -1, nbtEntry = -1, retainedOffset;
    private int fullReviewNativeClicks;
    private String initialSession, primaryFlowId, secondaryFlowId, targetLocalId, deletedLocalId, omittedKey;
    private String expectedSaveName, processingRequestSession, saveRequestSession;
    private String savedReviewLocalId;
    private boolean finished, saveReviewCaptured, saveReviewConfirmed;

    public static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.followupWorkbenchClient")) return;
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramV011WorkbenchClientSmoke());
    }

    private HologramV011WorkbenchClientSmoke() {
        buildSteps();
    }

    private void buildSteps() {
        add("connect-loopback", (mc, screen, ui) -> {
            if (!(mc.currentScreen instanceof GuiMainMenu)) return false;
            String address = System.getProperty("gtmb.hologram.followupAddress", "");
            require(address.matches("127\\.0\\.0\\.1:[0-9]{2,5}"), "explicit paired loopback address");
            require(
                Boolean.getBoolean("gtmb.hologram.previewCaptureWitness"),
                "actual workbench launch enables native preview capture observations");
            require(
                !Boolean.getBoolean("gtmb.hologram.realInteractionSmoke")
                    && !Boolean.getBoolean("gtmb.hologram.clientSmoke"),
                "legacy smoke drivers are disabled for this actual workbench scenario");
            for (String old : Arrays.asList(
                "workbench-client-final.nbt",
                "workbench-client-pass.txt",
                "workbench-server-final.nbt",
                "workbench-server-pass.txt",
                "workbench-client-fail.txt",
                "workbench-server-fail.txt",
                "workbench-contract.json"))
                require(
                    !Files.exists(
                        HologramV011Evidence.directory()
                            .resolve(old)),
                    "fresh receipts cannot reuse earlier workbench results: " + old);
            mc.gameSettings.pauseOnLostFocus = false;
            cpw.mods.fml.client.FMLClientHandler.instance()
                .setupServerList();
            cpw.mods.fml.client.FMLClientHandler.instance()
                .connectToServer(mc.currentScreen, new ServerData("GTMB isolated author workbench", address));
            return true;
        });
        add("native-source-ready-begin-area", (mc, screen, ui) -> {
            NBTTagCompound ready = HologramV011Evidence.read("workbench-server-ready.nbt");
            if (mc.theWorld == null || mc.thePlayer == null || ready == null || mc.thePlayer.getHeldItem() == null)
                return false;
            require(
                ready.getBoolean("passed") && ready.getString("actor")
                    .equals(
                        mc.thePlayer.getUniqueID()
                            .toString()),
                "server-ready receipt belongs to this actual connected player");
            require(
                ready.getString("world")
                    .startsWith("hologram-followup-"),
                "server corroborates fresh isolated world");
            sourceCards = (NBTTagList) ready.getTagList("sourceCards", 10)
                .copy();
            require(sourceCards.tagCount() == 3, "independent server observes three actual native source card assets");
            mc.displayGuiScreen(null);
            key(Keyboard.KEY_LSHIFT, true);
            press.begin(mc, false);
            mc.playerController.onPlayerRightClick(
                mc.thePlayer,
                mc.theWorld,
                mc.thePlayer.getHeldItem(),
                50,
                80,
                44,
                2,
                Vec3.createVectorHelper(50.5, 80.5, 44));
            key(Keyboard.KEY_LSHIFT, false);
            return true;
        });
        add("begin-area-netty-ack", (mc, screen, ui) -> {
            if (HologramClient.state == null || !data().getBoolean("areaSelecting")) return false;
            initialSession = HologramClient.state.session;
            findings.setString("initialAreaSession", initialSession);
            return true;
        });
        add("native-left-source-corner", (mc, screen, ui) -> {
            corner(mc, 0, 50, false);
            return true;
        });
        add(
            "left-corner-netty-ack",
            (mc, screen, ui) -> Arrays.equals(data().getIntArray("areaPoints"), new int[] { 50, 80, 44 }));
        add("native-right-internal-target-corner", (mc, screen, ui) -> {
            corner(mc, 1, 51, false);
            return true;
        });
        add(
            "two-corners-netty-ack",
            (mc, screen, ui) -> Arrays.equals(data().getIntArray("areaPoints"), new int[] { 50, 80, 44, 51, 80, 44 }));
        add("native-shift-right-finish", (mc, screen, ui) -> {
            corner(mc, 1, 51, true);
            return true;
        });
        screen("actual-area-import-complete", (mc, screen, ui) -> {
            if (!data().getBoolean("areaImported") || data().getBoolean("capturePending")
                || data().getBoolean("areaImporting")) return false;
            require(HologramClient.state.cells.size() == 2, "actual NC panel and internal stone captured");
            require(
                HologramLibraryClient.dedicated() && ui.getBoolean("dedicated") && !ui.getBoolean("public"),
                "paired dedicated server uses the actual client private library");
            require(
                data().getBoolean("blueprintProcessingRequired"),
                "raw author import requires six-stage processing");
            for (HologramState.Cell cell : HologramClient.state.cells) {
                if (cell.id.equals("IC2NuclearControl:blockNuclearControlMain")) ncCell = cell.index;
                if (cell.id.equals("minecraft:stone")) stoneCell = cell.index;
            }
            require(ncCell >= 0 && stoneCell >= 0, "actual imported cells retain native block identities");
            captureNativeSource();
            findings.setBoolean("actualC08AreaImportAndForgeCorners", true);
            return true;
        });
        shot("initial-import-tab");
        click("open-blueprint-tab", 149);
        type("native-blueprint-name", "presetNameField", "Workbench raw source");
        screen("mandatory-processing-save-gate", (mc, screen, ui) -> {
            require(
                !enabled(ui, 140) && !hasControl(ui, 132) && !enabled(ui, 166) && !enabled(ui, 221),
                "raw save/NBT/preset-save stay disabled until server processing ACK and save has one visible owner");
            baseline = (NBTTagCompound) data().copy();
            nativeClick(screen, 140);
            require(!screen.smokePending() && !HologramLibraryClient.INSTANCE.busy(), "disabled save sends no request");
            require(baseline.equals(data()), "disabled save leaves actual server snapshot unchanged");
            findings.setBoolean("mandatoryProcessingAndAckGate", true);
            return true;
        });
        shot("raw-blueprint-save-disabled");
        select("initial-panel-selection", true);
        click("initial-selected-prompt", 146);
        screen("selected-prompt-scope", (mc, screen, ui) -> {
            require(
                ui.getBoolean("promptDrawer") && ((Integer) read(screen, "promptScope")) == 1,
                "actual selected cell opens cell prompt scope");
            return true;
        });
        shot("initial-panel-selected-prompt");
        click("close-selected-prompt", 151);
        click("visit-structure-tab", 148);
        select("initial-stone-selection-sidebar-refresh", false);
        screen("stone-sidebar-refreshed", (mc, screen, ui) -> {
            require(
                ((Integer) read(screen, "selected")) == stoneCell && !ui.getBoolean("blueprintTab"),
                "native preview pick refreshes initial structure sidebar to stone");
            findings.setBoolean("initialTabSelectionSidebar", true);
            return true;
        });
        shot("initial-stone-structure-sidebar");
        click("return-blueprint-tab", 149);
        screen("view-rotation-baseline", (mc, screen, ui) -> {
            baseline = viewBaseline(ui);
            nativeClick(screen, 223);
            return true;
        });
        screen("view-rotation-keeps-server-plan", (mc, screen, ui) -> {
            require(
                Math.abs(ui.getDouble("yaw") - baseline.getDouble("yaw") - Math.PI / 2) < .00001,
                "native view button advances preview yaw by ninety degrees");
            assertPlanBaseline(baseline);
            findings.setBoolean("viewYawChangesServerPlanUnchanged", true);
            return true;
        });
        shot("view-rotation-server-plan-unchanged");
        click("open-actual-six-stage-flow", 220);
        sixStages("native", false);
        processingAck("native-six-stage-process-ack");
        screen("native-coordinate-policy-ack", (mc, screen, ui) -> {
            assertNativeRelations();
            findings.setBoolean("nativeNcRelativeAbsoluteClearServerAck", true);
            return true;
        });
        shot("native-process-server-ack");

        select("processed-panel-selection", true);
        click("open-selected-cell-nbt-refinement", 166);
        screen("choose-ordinary-nbt-entry", (mc, screen, ui) -> {
            require(
                ui.getBoolean("nbtDrawer") && ui.getBoolean("nbtCellScope"),
                "actual panel selection opens per-cell NBT");
            NBTTagList entries = ui.getTagList("visibleNbtEntries", 10);
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                String path = entry.getString("path"), key = entry.getString("key");
                if (!entry.getBoolean("protected") && !Arrays.asList("list", "compound", "byte_array", "int_array")
                    .contains(entry.getString("type"))
                    && path.equals("tile:/" + key)
                    && !Arrays.asList("Items", "id", "x", "y", "z", "gtmbCoordinateRelations", "screenData")
                        .contains(key)) {
                    nbtEntry = i;
                    omittedKey = key;
                    break;
                }
            }
            require(nbtEntry >= 0, "real native panel has an ordinary scalar NBT root available for explicit omission");
            findings.setString("actualOmittedNativeNbtRoot", omittedKey);
            return true;
        });
        screen("scroll-real-nbt-entry-into-view", (mc, screen, ui) -> {
            if (hasControl(ui, 17000 + nbtEntry)) {
                nativeClick(screen, 17000 + nbtEntry);
                return true;
            }
            nativeWheel(mc, screen, -120, 100, 170);
            return false;
        });
        shot("selected-cell-nbt-before-refinement");
        click("native-toggle-ordinary-root-retention", 229);
        shot("selected-cell-nbt-explicit-refinement");
        click("submit-actual-cell-nbt-refinement", 173);
        waitProcessingOperation("cell-nbt-refinement-netty-ack", "configureBlueprintCellNbt");
        screen("nbt-refinement-invalidates-final-two-stages", (mc, screen, ui) -> {
            require(
                !data().getBoolean("blueprintProcessingComplete")
                    && confirmed(data().getCompoundTag("processingPolicy")) == 4,
                "actual NBT ACK retains four prior confirmations and requires NBT/hints review");
            require(
                !enabled(ui, 140) && !hasControl(ui, 132),
                "the sole save control stays gated after actual NBT refinement");
            NBTTagList overrides = data().getTagList("cellNbtOverrides", 10);
            require(
                overrides.tagCount() == 1 && !overrides.getCompoundTagAt(0)
                    .getBoolean("all")
                    && !contains(
                        overrides.getCompoundTagAt(0)
                            .getTagList("keys", 8),
                        omittedKey),
                "actual server stores selected-cell NBT root omission");
            return true;
        });
        shot("nbt-refinement-server-ack-save-gated");
        click("resume-final-nbt-and-hints-review", 220);
        flowStage("refined", 4, 281);
        flowStage("refined", 5, 291);
        processingAck("refined-final-two-stages-server-ack");
        shot("refined-flow-complete-server-ack");
        screen("refined-policy-choice-retained", (mc, screen, ui) -> {
            assertNativeRelations();
            require(
                data().getCompoundTag("processingPolicy")
                    .getTagList("cellNbtOverrides", 10)
                    .tagCount() == 1,
                "completed policy retains actual per-cell NBT refinement");
            require(
                "full".equals(
                    data().getCompoundTag("processingPolicy")
                        .getString("autoHints")),
                "final explicit automatic full hints ACK");
            findings.setBoolean("selectedCellNbtRefinementAndFinalHintsAck", true);
            return true;
        });

        click("open-save-processing-preset-modal", 221);
        shot("processing-preset-save-modal");
        type("name-primary-flow-preset", "processingPresetField", "Workbench primary flow");
        sendClick("confirm-primary-flow-preset", 224);
        waitProcessingOperation("primary-flow-created-ack", "saveProcessingPreset");
        screen("remember-primary-actual-flow-id", (mc, screen, ui) -> {
            primaryFlowId = flowId("Workbench primary flow");
            require(!primaryFlowId.isEmpty(), "actual server processing preset creation produced an ID");
            return true;
        });
        shot("processing-preset-created-server-ack");
        click("reopen-flow-for-native-crud", 220);
        stageZero("previous-to-preset-stage-for-create");
        type("name-second-flow-preset", "processingPresetField", "Workbench secondary flow");
        sendClick("native-stage-zero-create-flow", 293);
        waitProcessingOperation("secondary-flow-created-ack", "saveProcessingPreset");
        screen("remember-secondary-actual-flow-id", (mc, screen, ui) -> {
            secondaryFlowId = flowId("Workbench secondary flow");
            require(
                !secondaryFlowId.isEmpty() && !secondaryFlowId.equals(primaryFlowId),
                "native stage-zero create produces distinct server preset");
            return true;
        });
        screen("native-load-primary-processing-preset", (mc, screen, ui) -> {
            int index = flowIndex(primaryFlowId);
            require(index >= 0, "actual primary processing preset is present");
            if (!hasControl(ui, 70000 + index)) {
                nativeWheel(mc, screen, -120, 180, 165);
                return false;
            }
            nativeClick(screen, 70000 + index);
            expectedSequence = sequence(screen);
            return true;
        });
        waitProcessingOperation("primary-flow-load-netty-ack", "loadProcessingPreset");
        screen("preset-load-still-needs-six-confirmations", (mc, screen, ui) -> {
            require(
                primaryFlowId.equals(data().getString("selectedProcessingPresetId"))
                    && !data().getBoolean("blueprintProcessingComplete")
                    && confirmed(flow(screen).policy()) == 0,
                "actual load ACK supplies choices without confirming stages");
            return true;
        });
        shot("processing-preset-loaded-unconfirmed-ack");
        sixStages("preset", true);
        processingAck("loaded-preset-six-manual-confirmations-ack");
        click("reopen-for-processing-preset-update", 220);
        stageZero("previous-to-preset-stage-for-update");
        type("rename-primary-flow-through-keyboard", "processingPresetField", "Workbench primary updated");
        sendClick("native-stage-zero-update-flow", 294);
        waitProcessingOperation("primary-flow-update-netty-ack", "updateProcessingPreset");
        screen("actual-primary-name-updated", (mc, screen, ui) -> {
            require(
                primaryFlowId.equals(flowId("Workbench primary updated")) && flowId("Workbench primary flow").isEmpty(),
                "server processing preset update retained ID and changed name");
            return true;
        });
        shot("processing-preset-updated-server-ack");
        sendClick("native-stage-zero-delete-flow", 295);
        waitProcessingOperation("primary-flow-delete-netty-ack", "deleteProcessingPreset");
        screen("actual-primary-preset-deleted", (mc, screen, ui) -> {
            require(
                flowIndex(primaryFlowId) < 0 && flowIndex(secondaryFlowId) >= 0,
                "actual delete removes selected processing preset and preserves second preset");
            findings.setBoolean("processingPresetNativeCreateLoadUpdateDeleteAck", true);
            return true;
        });
        shot("processing-preset-deleted-server-ack");
        click("close-processing-crud-modal", 234);
        select("preset-reloaded-panel-selection", true);
        click("reapply-selected-cell-nbt-after-reusable-preset", 166);
        screen("reusable-preset-keeps-cell-selections-blueprint-local", (mc, screen, ui) -> {
            require(
                ui.getBoolean("nbtDrawer") && ui.getBoolean("nbtCellScope")
                    && data().getCompoundTag("processingPolicy")
                        .getTagList("cellNbtOverrides", 10)
                        .tagCount() == 0,
                "reusable flow load has no blueprint-specific cell omissions");
            nbtEntry = -1;
            NBTTagList entries = ui.getTagList("visibleNbtEntries", 10);
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                if (("tile:/" + omittedKey).equals(entry.getString("path"))) {
                    require(
                        nbtEntry < 0 && !entry.getBoolean("protected") && entry.getBoolean("selected"),
                        "the same ordinary native root requires a fresh explicit cell omission");
                    nbtEntry = i;
                }
            }
            require(nbtEntry >= 0, "original omitted native root remains available after reusable preset load");
            return true;
        });
        screen("native-select-original-omitted-root-after-preset", (mc, screen, ui) -> {
            if (hasControl(ui, 17000 + nbtEntry)) {
                nativeClick(screen, 17000 + nbtEntry);
                return true;
            }
            nativeWheel(mc, screen, -120, 100, 170);
            return false;
        });
        click("native-reapply-original-root-omission", 229);
        click("submit-reapplied-cell-nbt-omission", 173);
        waitProcessingOperation("reapplied-cell-nbt-netty-ack", "configureBlueprintCellNbt");
        screen("reapplied-omission-needs-final-two-confirmations", (mc, screen, ui) -> {
            NBTTagList overrides = data().getTagList("cellNbtOverrides", 10);
            require(
                !data().getBoolean("blueprintProcessingComplete")
                    && confirmed(data().getCompoundTag("processingPolicy")) == 4
                    && !enabled(ui, 140)
                    && overrides.tagCount() == 1
                    && !overrides.getCompoundTagAt(0)
                        .getBoolean("all")
                    && !contains(
                        overrides.getCompoundTagAt(0)
                            .getTagList("keys", 8),
                        omittedKey),
                "fresh cell omission has an actual server ACK and requires manual NBT/hints review");
            return true;
        });
        click("review-reapplied-native-nbt-and-hints", 220);
        flowStage("reapplied", 4, 281, false);
        flowStage("reapplied", 5, 291, false);
        processingAck("reapplied-final-two-manual-confirmations-ack");
        screen("reapplied-native-policy-preserved", (mc, screen, ui) -> {
            assertNativeRelations();
            findings.setBoolean("reusablePresetCellOmissionRequiresFreshNativeReview", true);
            return true;
        });

        for (int i = 0; i < 6; i++) {
            final int index = i;
            type("name-private-save-as-" + i, "presetNameField", "Workbench actual " + i);
            screen("native-private-save-as-" + i, (mc, screen, ui) -> {
                beginSave(screen, 140, "Workbench actual " + index);
                return true;
            });
            screen("private-disk-and-owner-save-ack-" + i, (mc, screen, ui) -> awaitSave(mc, screen, ui, index));
            shot("private-save-as-" + i + "-actual-disk-ack");
        }
        screen("actual-six-private-blueprints", (mc, screen, ui) -> {
            require(
                savedIds.size() == 6 && ui.getInteger("rows") >= 6,
                "six distinct actual private files create more than four library rows");
            findings.setBoolean("sixDistinctNativeSaveAsFilesAndOwnerAck", true);
            return true;
        });
        screen("native-library-wheel-down", (mc, screen, ui) -> {
            retainedOffset = ui.getInteger("libraryOffset");
            nativeWheel(mc, screen, -120, 555, 170);
            return true;
        });
        screen("actual-library-scroll-offset", (mc, screen, ui) -> {
            require(
                ui.getInteger("libraryOffset") == Math.min(retainedOffset + 1, ui.getInteger("rows") - 4),
                "native LWJGL wheel/handleMouseInput advances actual library offset");
            retainedOffset = ui.getInteger("libraryOffset");
            NBTTagList entries = ui.getTagList("entries", 10);
            targetLocalId = entries.getCompoundTagAt(retainedOffset + 1)
                .getString("id");
            baseline = viewBaseline(ui);
            nativeClick(screen, 14000 + retainedOffset + 1);
            return true;
        });
        screen("native-scrolled-row-preview-netty-ack", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !targetLocalId.equals(ui.getString("selectedLocal"))
                || !targetLocalId.equals(data().getString("libraryLocalId"))) return false;
            require(
                ui.getInteger("libraryOffset") == retainedOffset,
                "actual library preview ACK preserves scrolled offset");
            require(
                data().getBoolean("blueprintPreview"),
                "native private row selection has actual server preview ACK");
            return true;
        });
        shot("scrolled-private-row-native-preview-ack");
        click("native-edit-saved-blueprint", 139);
        screen("saved-blueprint-edit-netty-ack", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !"blueprintEdit".equals(data().getString("openPurpose"))) return false;
            require(ui.getBoolean("blueprintTab") && ui.getBoolean("library"), "saved edit opens author blueprint tab");
            require(
                ui.getInteger("libraryOffset") == retainedOffset,
                "saved edit ACK retains actual library scroll position");
            return true;
        });
        reviewSavedBlueprint("saved-edit-review", "savedEditFreshSixStageReview");
        select("saved-panel-selection", true);
        click("saved-panel-selected-nbt-sidebar", 166);
        screen("saved-panel-nbt-refresh", (mc, screen, ui) -> {
            require(
                ui.getBoolean("nbtCellScope") && ui.getTagList("visibleNbtEntries", 10)
                    .tagCount() > 0,
                "actual saved panel selection refreshes per-cell NBT sidebar");
            return true;
        });
        shot("saved-panel-selected-cell-nbt");
        click("close-saved-panel-nbt", 172);
        click("saved-structure-tab", 148);
        select("saved-stone-sidebar-selection", false);
        shot("saved-stone-structure-sidebar");
        click("saved-blueprint-tab", 149);
        screen("saved-selection-tab-memory", (mc, screen, ui) -> {
            require(
                ((Integer) read(screen, "selected")) == stoneCell && ui.getBoolean("blueprintTab"),
                "native tabs retain saved selection and refresh the correct sidebar");
            findings.setBoolean("savedEditTabsCellSelectionAndNbtRefresh", true);
            findings.setBoolean("actualWheelAndAckRetainListOffset", true);
            return true;
        });
        click("native-global-prompt-modal", 227);
        screen("global-prompt-is-explicit", (mc, screen, ui) -> {
            require(
                ui.getBoolean("promptDrawer") && ((Integer) read(screen, "promptScope")) == 0,
                "global button opens global prompt with an actual selected cell");
            return true;
        });
        type("type-real-global-prompt", "promptField", "Workbench actual dirty global prompt");
        shot("global-prompt-native-keyboard-choice");
        sendClick("apply-real-global-prompt", 158);
        screen("global-prompt-netty-ack-dirty", (mc, screen, ui) -> {
            if (!idle(screen, ui) || data().getLong("uiSequence") < expectedSequence
                || !"Workbench actual dirty global prompt".equals(data().getString("blueprintPrompt"))) return false;
            require(data().getBoolean("blueprintDirty"), "actual global prompt ACK marks draft dirty");
            baseline = viewBaseline(ui);
            return true;
        });
        screen("native-dirty-row-switch-review", (mc, screen, ui) -> {
            int index = anotherVisibleRow(ui, targetLocalId);
            deletedLocalId = ui.getTagList("entries", 10)
                .getCompoundTagAt(index)
                .getString("id");
            nativeClick(screen, 14000 + index);
            require(
                screen.smokeLibraryUiState()
                    .getBoolean("discardReview"),
                "dirty row switch opens native discard review");
            return true;
        });
        shot("dirty-row-switch-discard-review");
        click("native-cancel-dirty-discard", 225);
        screen("dirty-discard-cancel-keeps-content", (mc, screen, ui) -> {
            assertPlanBaseline(baseline);
            require(
                data().getBoolean("blueprintDirty")
                    && "Workbench actual dirty global prompt".equals(data().getString("blueprintPrompt")),
                "native cancel retains dirty prompt and current blueprint content");
            return true;
        });
        screen("native-repeat-dirty-row-switch", (mc, screen, ui) -> {
            nativeClick(screen, 14000 + localIndex(ui, deletedLocalId));
            return true;
        });
        shot("dirty-row-switch-confirm-review");
        click("native-confirm-dirty-discard", 224);
        screen("dirty-discard-confirm-real-preview-ack", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !deletedLocalId.equals(data().getString("libraryLocalId"))) return false;
            require(
                !data().getBoolean("blueprintDirty")
                    && !"Workbench actual dirty global prompt".equals(data().getString("blueprintPrompt")),
                "native confirmed discard switches to actual saved blueprint through Netty");
            require(ui.getInteger("libraryOffset") == retainedOffset, "confirmed dirty switch retains list offset");
            findings.setBoolean("globalPromptAckDirtyDiscardCancelAndConfirm", true);
            return true;
        });
        shot("dirty-switch-confirmed-server-ack");
        click("edit-current-before-overwrite", 139);
        screen(
            "actual-edit-before-overwrite-ack",
            (mc, screen, ui) -> idle(screen, ui) && "blueprintEdit".equals(data().getString("openPurpose")));
        reviewSavedBlueprint("overwrite-edit-review", "overwriteEditFreshSixStageReview");
        click("native-enable-overwrite-mode", 222);
        screen("native-overwrite-target-only-selection", (mc, screen, ui) -> {
            require(ui.getBoolean("overwriteMode"), "actual overwrite checkbox is enabled");
            int index = anotherVisibleRow(ui, deletedLocalId);
            targetLocalId = ui.getTagList("entries", 10)
                .getCompoundTagAt(index)
                .getString("id");
            overwriteOriginalArtifact = HologramBlueprintArtifact.readPair(localPath(mc, targetLocalId));
            baseline = viewBaseline(ui);
            nativeClick(screen, 14000 + index);
            require(
                !screen.smokePending() && !HologramLibraryClient.INSTANCE.busy(),
                "overwrite target selection dispatches no preview request");
            return true;
        });
        screen("overwrite-target-keeps-current-plan", (mc, screen, ui) -> {
            assertPlanBaseline(baseline);
            require(
                targetLocalId.equals(ui.getString("overwriteTarget"))
                    && !targetLocalId.equals(ui.getString("selectedLocal")),
                "actual overwrite row chooses only target and retains current selected blueprint");
            return true;
        });
        shot("overwrite-target-only-current-plan-unchanged");
        type("native-overwrite-name", "presetNameField", "Workbench actual overwritten");
        click("native-open-named-overwrite-review", 140);
        screen("actual-named-overwrite-review", (mc, screen, ui) -> {
            require(
                ui.getBoolean("overwriteReview") && targetLocalId.equals(ui.getString("overwriteTarget")),
                "native save displays selected overwrite target in mandatory named review");
            expectedSaveName = "Workbench actual overwritten";
            saveReviewCaptured = saveReviewConfirmed = false;
            return true;
        });
        shot("overwrite-named-native-review");
        sendClick("native-confirm-named-overwrite", 224);
        screen("actual-overwrite-disk-and-owner-ack", (mc, screen, ui) -> {
            if (!awaitSave(mc, screen, ui, -1)) return false;
            require(
                targetLocalId.equals(HologramLibraryClient.INSTANCE.selected(false)),
                "actual overwrite saves the chosen local file ID");
            require(
                ui.getInteger("rows") == 6 && ui.getInteger("libraryOffset") == retainedOffset,
                "actual overwrite preserves six IDs and existing list scroll");
            findings.setBoolean("overwriteTargetOnlyNamedReviewActualReplaceAck", true);
            return true;
        });
        shot("overwrite-native-replace-disk-ack");
        click("disable-overwrite-mode-before-delete", 222);
        click("native-private-delete-review", 205);
        screen("native-private-delete-review-state", (mc, screen, ui) -> {
            require(
                ui.getBoolean("deleteReview") && targetLocalId.equals(ui.getString("deletePresetId")),
                "actual private library delete opens named review");
            deleteOriginalArtifact = HologramBlueprintArtifact.readPair(localPath(mc, targetLocalId));
            return true;
        });
        shot("private-delete-native-review");
        click("native-private-delete-cancel", 177);
        screen("private-delete-cancel-keeps-file", (mc, screen, ui) -> {
            require(localIndex(ui, targetLocalId) >= 0, "native delete cancel preserves actual library row");
            assertPairUnchanged(
                deleteOriginalArtifact,
                HologramBlueprintArtifact.readPair(localPath(mc, targetLocalId)),
                "native delete cancel preserves both actual JSON and PNG bytes");
            assertLocalPairs(mc, "");
            return true;
        });
        click("native-private-delete-review-again", 205);
        click("native-private-delete-confirm", 176);
        screen("actual-private-file-delete-complete", (mc, screen, ui) -> {
            if (!idle(screen, ui) || localIndex(ui, targetLocalId) >= 0) return false;
            require(
                !Files.exists(localPath(mc, targetLocalId))
                    && !Files.exists(HologramBlueprintArtifact.previewPath(localPath(mc, targetLocalId)))
                    && ui.getInteger("rows") == 5,
                "actual confirmed private delete removes selected JSON and PNG and refreshes rows");
            assertLocalPairs(mc, targetLocalId);
            NBTTagCompound deleted = artifactProof(localPath(mc, targetLocalId), deleteOriginalArtifact);
            deleted.setString("localId", targetLocalId);
            deleted.setBoolean("jsonAbsent", true);
            deleted.setBoolean("pngAbsent", true);
            findings.setTag("deletedNativePair", deleted);
            findings.setBoolean("privateDeleteRemovesNativePair", true);
            require(
                data().getBoolean("blueprintProcessingComplete"),
                "deleting local library file preserves current author draft");
            findings.setBoolean("privateDeleteReviewCancelConfirmActualFile", true);
            return true;
        });
        shot("private-delete-actual-file-and-draft-preserved");
        screen("write-fresh-workbench-final", (mc, screen, ui) -> {
            require(capture == null && !receipts.isEmpty(), "all actual rendered evidence completed");
            require(
                fullReviewNativeClicks > 0 && fullReviewSelections.tagCount() == fullReviewNativeClicks
                    && fullReviewSelection == null,
                "all actual full review toggle inputs have a corroborating later rendered mode");
            NBTTagCompound result = new NBTTagCompound();
            result.setBoolean("passed", true);
            result.setBoolean("actualNettyClient", true);
            result.setBoolean("syntheticSnapshotsUsed", false);
            result.setBoolean("controllerMemoryWritesUsed", false);
            result.setBoolean("paidPlacementClaimed", false);
            result.setInteger("completedNativeSteps", step);
            int renderedWitnesses = 0;
            for (String receipt : receipts)
                if (receipt.endsWith(".png") && !receipt.endsWith("-native.png")) renderedWitnesses++;
            result.setInteger("freshRenderWitnesses", renderedWitnesses);
            result.setTag("verifiedFindings", findings.copy());
            result.setTag("actualSnapshot", data().copy());
            result.setTag("actualUi", ui.copy());
            HologramV011Evidence.write("workbench-client-final.nbt", result);
            HologramV011Evidence.marker(
                "workbench-client-pass.txt",
                "PASS actual Socket author workbench, native inputs, server ACKs, private files and END framebuffer witnesses");
            writeContract();
            return true;
        });
        add("wait-independent-server-final-before-shutdown", (mc, screen, ui) -> {
            NBTTagCompound result = HologramV011Evidence.read("workbench-server-final.nbt");
            if (result == null || !Files.isRegularFile(
                HologramV011Evidence.directory()
                    .resolve("workbench-server-pass.txt")))
                return false;
            require(
                result.getBoolean("passed") && result.getBoolean("sourceUnchanged"),
                "independent server confirms native source and assets unchanged");
            finished = true;
            mc.shutdown();
            return true;
        });
    }

    private void reviewSavedBlueprint(String prefix, String finding) {
        screen(prefix + "-stored-receipt-does-not-authorize-save", (mc, screen, ui) -> {
            savedReviewLocalId = data().getString("libraryLocalId");
            savedReviewArtifact = HologramBlueprintArtifact.readPair(localPath(mc, savedReviewLocalId));
            findings.setTag(
                finding + "LoadedArtifact",
                artifactProof(localPath(mc, savedReviewLocalId), savedReviewArtifact));
            require(
                !data().getBoolean("blueprintProcessingComplete") && data().getBoolean("blueprintProcessingRequired")
                    && !enabled(ui, 140)
                    && !enabled(ui, 221)
                    && enabled(ui, 166),
                "saved source permits NBT editing while saving and flow export require a fresh server-authorized review");
            savedReviewPolicy = (NBTTagCompound) data().getCompoundTag("processingPolicy")
                .copy();
            require(
                confirmed(savedReviewPolicy) == 6 && "self".equals(savedReviewPolicy.getString("tendency"))
                    && "auto".equals(savedReviewPolicy.getString("coordinateDefault"))
                    && savedReviewPolicy.getInteger("deidentificationLevel") == 0
                    && data().getCompoundTag("processing")
                        .getInteger("minimumLevel") == 0
                    && "rebind".equals(savedReviewPolicy.getString("quantum"))
                    && "full".equals(savedReviewPolicy.getString("autoHints")),
                "loaded source retains the actual saved processing choices without granting this session authority");
            assertCoordinateOverrides(savedReviewPolicy);
            NBTTagList overrides = savedReviewPolicy.getTagList("cellNbtOverrides", 10);
            require(
                overrides.tagCount() == 1 && !overrides.getCompoundTagAt(0)
                    .getBoolean("all")
                    && !contains(
                        overrides.getCompoundTagAt(0)
                            .getTagList("keys", 8),
                        omittedKey),
                "loaded source retains its blueprint-specific ordinary root omission");
            savedReviewRelations = (NBTTagList) data().getTagList("processingRelations", 10)
                .copy();
            assertSavedReviewRelations(savedReviewRelations);
            findings.setTag(finding + "LoadedPolicy", savedReviewPolicy.copy());
            findings.setTag(finding + "LoadedRelations", savedReviewRelations.copy());
            savedReviewDraft = (NBTTagCompound) data().copy();
            return true;
        });
        click(prefix + "-open-saved-nbt-before-fresh-review", 166);
        screen(prefix + "-saved-nbt-read-only-drawer", (mc, screen, ui) -> {
            require(
                ui.getBoolean("nbtDrawer") && enabled(ui, 172)
                    && !screen.smokePending()
                    && !HologramLibraryClient.INSTANCE.busy(),
                "native saved NBT drawer opens without dispatching a modifying request");
            require(
                savedReviewDraft.equals(data()) && savedReviewPolicy.equals(data().getCompoundTag("processingPolicy")),
                "opening saved NBT retains the complete source draft and processing policy");
            assertPairUnchanged(
                savedReviewArtifact,
                HologramBlueprintArtifact.readPair(localPath(mc, savedReviewLocalId)),
                "opening saved NBT preserves the complete source JSON and native PNG bytes");
            return true;
        });
        click(prefix + "-close-saved-nbt-without-modification", 172);
        screen(prefix + "-saved-nbt-close-retains-review-gates", (mc, screen, ui) -> {
            require(
                !ui.getBoolean("nbtDrawer") && !data().getBoolean("blueprintProcessingComplete")
                    && data().getBoolean("blueprintProcessingRequired")
                    && !enabled(ui, 140)
                    && !enabled(ui, 221)
                    && enabled(ui, 166),
                "closing saved NBT retains immediate editing and the mandatory fresh review before saving or flow export");
            require(
                savedReviewDraft.equals(data()) && savedReviewPolicy.equals(data().getCompoundTag("processingPolicy")),
                "read-only saved NBT inspection preserves the complete draft and processing policy");
            assertPairUnchanged(
                savedReviewArtifact,
                HologramBlueprintArtifact.readPair(localPath(mc, savedReviewLocalId)),
                "read-only saved NBT inspection preserves exact JSON and native PNG bytes");
            findings.setBoolean(finding + "SavedNbtReadOnlyBeforeReview", true);
            return true;
        });
        shot(prefix + "-saved-import-save-disabled");
        click(prefix + "-open-six-stage-review", 220);
        ensureFullReview(prefix);
        screen(prefix + "-all-six-need-new-manual-confirmations", (mc, screen, ui) -> {
            assertStage(screen, ui, 0);
            require(!ui.getBoolean("flowChosen") && !enabled(ui, 231), "loaded receipt does not confirm a new stage");
            return true;
        });
        int[] choices = { 235, 240, 260, 270, 281, 291 };
        for (int stage = 0; stage < choices.length; stage++) {
            if (stage == 3) {
                screen(prefix + "-cleared-source-remains-unknown", (mc, screen, ui) -> {
                    assertSavedReviewRelations(data().getTagList("processingRelations", 10));
                    return assertStage(screen, ui, 3);
                });
                shot(prefix + "-stage-3-clear-target-unknown");
            }
            flowStage(prefix, stage, choices[stage], false);
        }
        processingAck(prefix + "-six-new-manual-confirmations-server-ack");
        screen(prefix + "-stored-choices-and-omission-preserved", (mc, screen, ui) -> {
            require(
                savedReviewPolicy.equals(data().getCompoundTag("processingPolicy"))
                    && savedReviewRelations.equals(data().getTagList("processingRelations", 10)),
                "fresh review preserves the entire loaded policy, native targets and ordinary root omission");
            assertSavedReviewRelations(data().getTagList("processingRelations", 10));
            assertPairUnchanged(
                savedReviewArtifact,
                HologramBlueprintArtifact.readPair(localPath(mc, savedReviewLocalId)),
                "fresh saved review preserves source JSON metadata and its exact native preview bytes");
            findings.setBoolean("savedNativeMetadataPreservedThroughEditReview", true);
            findings.setBoolean(finding, true);
            return true;
        });
        shot(prefix + "-six-stage-server-ack");
    }

    private void assertSavedReviewRelations(NBTTagList rows) {
        assertNativeRelationSet(rows);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound relation = rows.getCompoundTagAt(i);
            boolean sensor = "nc.sensor".equals(relation.getString("adapter"));
            int slot = relation.getInteger("slot");
            String mode = sensor ? new String[] { "relative", "absolute", "clear" }[slot] : "relative";
            require(
                mode.equals(relation.getString("mode")) && relation.hasKey("targetKnown", 1)
                    && relation.getBoolean("targetKnown") == !"clear".equals(mode)
                    && relation.getBoolean("internal") == (!sensor || slot == 0),
                "loaded native relationship retains its saved mode and actual known/unknown classification");
            if ("clear".equals(mode))
                for (String key : new String[] { "targetX", "targetY", "targetZ", "targetDimension", "dx", "dy", "dz" })
                    require(
                        relation.hasKey(key, 3) && relation.getInteger(key) == 0,
                        "already-cleared source contains no original target or relative offset to restore");
        }
    }

    private void sixStages(String prefix, boolean fromPreset) {
        ensureFullReview(prefix);
        for (int i = 0; i < 6; i++) {
            final int stage = i;
            if (i == 0 && !fromPreset) {
                screen(prefix + "-untouched-first-stage", (mc, screen, ui) -> {
                    require(
                        ui.getBoolean("processingFlow") && ui.getInteger("flowStage") == 0
                            && !ui.getBoolean("flowChosen")
                            && !enabled(ui, 231)
                            && confirmed(flow(screen).policy()) == 0,
                        "untouched first stage has no available choice and cannot advance");
                    nativeClick(screen, 231);
                    require(
                        confirmed(flow(screen).policy()) == 0,
                        "native disabled Next does not confirm untouched stage");
                    return true;
                });
                shot(prefix + "-untouched-stage-0-next-disabled");
                click(prefix + "-share-tendency", 233);
                screen(prefix + "-share-supplies-unconfirmed-choices", (mc, screen, ui) -> {
                    require(
                        "share".equals(
                            flow(screen).policy()
                                .getString("tendency"))
                            && confirmed(flow(screen).policy()) == 0,
                        "share tendency supplies choices without confirming any stage");
                    return true;
                });
                shot(prefix + "-share-tendency-unconfirmed");
                click(prefix + "-self-tendency", 232);
                screen(prefix + "-self-supplies-unconfirmed-choices", (mc, screen, ui) -> {
                    require(
                        "self".equals(
                            flow(screen).policy()
                                .getString("tendency"))
                            && confirmed(flow(screen).policy()) == 0,
                        "self tendency supplies choices without confirming any stage");
                    findings.setBoolean("tendencyProvidesChoicesRequiresManualNext", true);
                    return true;
                });
            }
            if (i == 3) {
                screen(prefix + "-stage-3-unconfirmed", (mc, screen, ui) -> assertStage(screen, ui, 3));
                shot(prefix + "-stage-3-unconfirmed");
                click(prefix + "-native-coordinate-default-auto", 270);
                screen(prefix + "-coordinate-relations-match-real-cards", (mc, screen, ui) -> {
                    NBTTagList relations = data().getTagList("processingRelations", 10);
                    assertNativeRelationSet(relations);
                    assertSourceRelationTargets(relations);
                    return true;
                });
                coordinateClick(prefix + "-native-card-0-relative", 0, 2);
                coordinateClick(prefix + "-native-card-1-absolute", 1, 1);
                coordinateClick(prefix + "-native-card-2-clear", 2, 0);
                screen(prefix + "-explicit-overrides-survive-tendency", (mc, screen, ui) -> {
                    NBTTagCompound before = flow(screen).policy();
                    nativeClick(screen, 233);
                    nativeClick(screen, 232);
                    require(
                        before.getInteger("deidentificationLevel") == flow(screen).policy()
                            .getInteger("deidentificationLevel") && before.getString("quantum")
                                .equals(
                                    flow(screen).policy()
                                        .getString("quantum"))
                            && before.getTagList("coordinateOverrides", 10)
                                .equals(
                                    flow(screen).policy()
                                        .getTagList("coordinateOverrides", 10))
                            && confirmed(flow(screen).policy()) == 3,
                        "tendency switches preserve explicit identity, quantum and actual per-card overrides");
                    findings.setBoolean("explicitChoicesSurviveTendencySwitch", true);
                    return true;
                });
                shot(prefix + "-stage-3-explicit-choice");
                click(prefix + "-manual-next-3", 231);
            } else {
                int[] choices = { 235, 240, 260, 270, 281, 291 };
                if (stage == 1) {
                    screen(prefix + "-stage-1-unconfirmed", (mc, screen, ui) -> assertStage(screen, ui, 1));
                    shot(prefix + "-stage-1-unconfirmed");
                    screen(prefix + "-explicit-identity-choice", (mc, screen, ui) -> {
                        int minimum = data().getCompoundTag("processing")
                            .getInteger("minimumLevel");
                        require(minimum >= 0 && minimum <= 10, "actual identity minimum is bounded");
                        nativeClick(screen, 240 + minimum);
                        return true;
                    });
                    shot(prefix + "-stage-1-explicit-choice");
                    click(prefix + "-manual-next-1", 231);
                } else flowStage(prefix, i, choices[i]);
            }
        }
    }

    private void flowStage(String prefix, int stage, int choice) {
        flowStage(prefix, stage, choice, true);
    }

    private void flowStage(String prefix, int stage, int choice, boolean captureShots) {
        screen(prefix + "-stage-" + stage + "-unconfirmed", (mc, screen, ui) -> assertStage(screen, ui, stage));
        if (captureShots) shot(prefix + "-stage-" + stage + "-unconfirmed");
        click(prefix + "-explicit-stage-" + stage + "-choice", choice);
        screen(prefix + "-stage-" + stage + "-choice-still-unconfirmed", (mc, screen, ui) -> {
            require(
                ui.getBoolean("flowChosen") && confirmed(flow(screen).policy()) == stage,
                "explicit native choice supplies only this stage and still needs manual Next");
            return true;
        });
        if (captureShots) shot(prefix + "-stage-" + stage + "-explicit-choice");
        click(prefix + "-manual-next-" + stage, 231);
    }

    private static boolean assertStage(HologramScreen screen, NBTTagCompound ui, int stage) throws Exception {
        require(
            ui.getBoolean("processingFlow") && ui.getInteger("flowStage") == stage
                && ui.getBoolean("flowFullReview")
                && confirmed(flow(screen).policy()) == stage,
            "actual modal stage " + stage + " requires its own manual confirmation");
        return true;
    }

    private void ensureFullReview(String prefix) {
        screen(prefix + "-choose-visible-full-six-stage-entry", (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            require(
                ui.getBoolean("processingFlow") && enabled(ui, 296)
                    && data().getCompoundTag("processing")
                        .hasKey("requiredStages", 3)
                    && ui.getInteger("flowRequiredStages") == data().getCompoundTag("processing")
                        .getInteger("requiredStages"),
                "actual full review toggle is visible and retains the server's content-derived stage requirements");
            if (ui.getBoolean("flowFullReview")) {
                require(
                    fullReviewNativeClicks > 0,
                    "full review preference comes from this run's actual native toggle");
                return true;
            }
            fullReviewSelection = new NBTTagCompound();
            fullReviewSelection.setString("label", prefix);
            fullReviewSelection.setInteger("controlId", 296);
            fullReviewSelection.setIntArray("controlPoint", screen.smokeControlPoint(296));
            fullReviewSelection.setTag("actualUiBefore", ui.copy());
            fullReviewSelection.setTag("actualSnapshotBefore", data().copy());
            fullReviewSelection.setTag("planBefore", viewBaseline(ui));
            nativeClick(screen, 296);
            fullReviewNativeClicks++;
            require(!screen.smokePending(), "native full review selection changes only the local review mode");
            return true;
        });
        screen(prefix + "-full-six-stage-entry-rendered", (mc, screen, ui) -> {
            require(
                ui.getBoolean("processingFlow") && ui.getBoolean("flowFullReview")
                    && enabled(ui, 296)
                    && ui.getInteger("flowRequiredStages") == data().getCompoundTag("processing")
                        .getInteger("requiredStages"),
                "actual rendered modal retains manually requested full review and the authoritative content mask");
            if (fullReviewSelection != null) {
                assertStage(screen, ui, 0);
                require(
                    !ui.getBoolean("flowChosen") && !enabled(ui, 231),
                    "native full review begins at an untouched first stage requiring its own choice");
                assertPlanBaseline(fullReviewSelection.getCompoundTag("planBefore"));
                require(
                    fullReviewSelection.getCompoundTag("actualSnapshotBefore")
                        .getCompoundTag("processingPolicy")
                        .equals(data().getCompoundTag("processingPolicy")),
                    "manual full review selection does not edit server source policy");
                fullReviewSelection.setTag("actualUiAfter", ui.copy());
                fullReviewSelection.setTag("actualSnapshotAfter", data().copy());
                fullReviewSelections.appendTag(fullReviewSelection);
                fullReviewSelection = null;
                findings.setTag("actualFullReviewSelections", fullReviewSelections.copy());
            }
            findings.setInteger("actualFullReviewNativeToggleClicks", fullReviewNativeClicks);
            findings.setBoolean("actualVisibleFullSixStageReviewSelected", true);
            return true;
        });
    }

    private void stageZero(String label) {
        screen(label, (mc, screen, ui) -> {
            require(ui.getBoolean("processingFlow"), "actual processing modal is open");
            if (ui.getInteger("flowStage") == 0) return true;
            nativeClick(screen, 230);
            return false;
        });
    }

    private void coordinateClick(String label, int slot, int mode) {
        screen(label, (mc, screen, ui) -> {
            int index = relationIndex(slot);
            require(index >= 0, "native coordinate control identifies exactly one sensor slot " + slot);
            nativeClick(screen, 71000 + index * 3 + mode);
            return true;
        });
    }

    private void processingAck(String label) {
        screen(label, (mc, screen, ui) -> {
            require(processingRequestSequence >= 0, "actual final Next submitted a processing request");
            if (!idle(screen, ui) || !processingRequestSession.equals(HologramClient.state.session)
                || data().getLong("uiSequence") < processingRequestSequence
                || !"processBlueprint".equals(data().getString("processingPresetOperation"))) return false;
            require(
                data().getBoolean("processingPresetOperationSuccess"),
                "actual current processBlueprint ACK rejected sequence " + processingRequestSequence
                    + " revision "
                    + data().getLong("planRevision")
                    + ": "
                    + data().getString("status"));
            if (!data().getBoolean("blueprintProcessingComplete")) return false;
            require(
                !ui.getBoolean("processingFlow")
                    && "processBlueprint".equals(data().getString("processingPresetOperation"))
                    && data().getBoolean("processingPresetOperationSuccess")
                    && confirmed(data().getCompoundTag("processingPolicy")) == 6,
                "six native manual confirmations completed actual server processing ACK");
            require(
                enabled(ui, 140) && !hasControl(ui, 132) && enabled(ui, 166),
                "actual server success opens the sole save control and NBT controls");
            return true;
        });
    }

    private void waitProcessingOperation(String label, String operation) {
        screen(label, (mc, screen, ui) -> {
            if (!idle(screen, ui) || data().getLong("uiSequence") < expectedSequence
                || !operation.equals(data().getString("processingPresetOperation"))) return false;
            require(
                data().getBoolean("processingPresetOperationSuccess"),
                "actual " + operation + " server ACK succeeded: " + data().getString("status"));
            return true;
        });
    }

    private void add(String label, Probe probe) {
        steps.add(new Step(label, probe));
    }

    private void screen(String label, Probe probe) {
        add(label, (mc, screen, ui) -> screen != null && probe.run(mc, screen, ui));
    }

    private void click(String label, int id) {
        screen(label, (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            boolean processing = id == 231 && ui.getBoolean("processingFlow") && ui.getInteger("flowStage") == 5;
            String session = HologramClient.state.session;
            nativeClick(screen, id);
            if (processing) {
                require(screen.smokePending(), "native final Next dispatched its actual processing request");
                processingRequestSequence = sequence(screen);
                processingRequestSession = session;
            }
            return true;
        });
    }

    private void sendClick(String label, int id) {
        screen(label, (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            boolean overwrite = id == 224 && ui.getBoolean("overwriteReview");
            nativeClick(screen, id);
            expectedSequence = sequence(screen);
            if (overwrite) observeSaveRequest(expectedSequence);
            return true;
        });
    }

    private void type(String label, String field, String value) {
        screen(label, (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            GuiTextField target = (GuiTextField) read(screen, field);
            require(target != null, "actual text field " + field + " exists");
            NBTTagCompound keyboard = new NBTTagCompound();
            keyboard.setInteger("step", step);
            keyboard.setString("label", label);
            keyboard.setString("field", field);
            keyboard.setString("expectedText", value);
            keyboard.setString("textBefore", target.getText());
            keyboard.setBoolean("leftControlBefore", Keyboard.isKeyDown(Keyboard.KEY_LCONTROL));
            keyboard.setBoolean("rightControlBefore", Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
            keyboard.setBoolean(
                "shiftBefore",
                Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT));
            keyboard.setTag("actualUiBefore", ui.copy());
            try {
                int[] point = point(screen, target.xPosition + 4, target.yPosition + 7);
                screen.mouseClicked(point[0], point[1], 0);
                screen.mouseMovedOrUp(point[0], point[1], 0);
                keyboard.setBoolean("focusedAfterNativeClick", target.isFocused());
                require(target.isFocused(), "native click focuses actual text field " + field);
                key(Keyboard.KEY_LCONTROL, true);
                // GuiTextField receives ASCII SOH from a real Ctrl+A keyboard event in Minecraft 1.7.10.
                screen.keyTyped('\u0001', Keyboard.KEY_A);
                keyboard.setString("selectedAfterControlA", target.getSelectedText());
                require(
                    target.getText()
                        .equals(target.getSelectedText()),
                    "native Ctrl+A selects the complete actual field text");
                key(Keyboard.KEY_LCONTROL, false);
                for (char character : value.toCharArray()) screen.keyTyped(character, 0);
                require(value.equals(target.getText()), "native keyboard replaced actual field text");
                keyboard.setBoolean("passed", true);
            } catch (Exception | Error failure) {
                keyboard.setString("failure", failure.toString());
                throw failure;
            } finally {
                key(Keyboard.KEY_LCONTROL, false);
                keyboard.setString("textAfter", target.getText());
                keyboard.setBoolean("focusedAfterInput", target.isFocused());
                String name = "workbench-keyboard-step-" + step + ".nbt";
                HologramV011Evidence.write(name, keyboard);
                receipts.add(name);
            }
            return true;
        });
    }

    private void select(String label, boolean panel) {
        screen(label, (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            // Loaded private blueprints may reorder cells; resolve their actual immutable snapshot each time.
            for (HologramState.Cell cell : HologramClient.state.cells) {
                if (cell.id.equals("IC2NuclearControl:blockNuclearControlMain")) ncCell = cell.index;
                if (cell.id.equals("minecraft:stone")) stoneCell = cell.index;
            }
            int wanted = panel ? ncCell : stoneCell;
            int x = (Integer) read(screen, "viewportX"), y = (Integer) read(screen, "viewportY");
            int width = (Integer) read(screen, "viewportWidth"), height = (Integer) read(screen, "viewportHeight");
            for (int py = y + 5; py < y + height - 6; py++) for (int px = x + 5; px < x + width - 6; px++) {
                if (controlAt(ui, px, py) || HologramRenderer.pick(px, py) != wanted) continue;
                int[] point = point(screen, px, py);
                screen.mouseClicked(point[0], point[1], 0);
                screen.mouseMovedOrUp(point[0], point[1], 0);
                if (((Integer) read(screen, "selected")) == wanted) return true;
            }
            throw new AssertionError("actual rendered preview has no native selectable point for " + wanted);
        });
    }

    private void shot(String label) {
        screen(label, (mc, screen, ui) -> {
            requestCapture(screen, label, ui);
            return true;
        });
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.theWorld != null && press.tick(event, mc)) return;
            if (event.phase != TickEvent.Phase.END) return;
            require(System.nanoTime() - installed < 600000000000L, "actual workbench scenario exceeded ten minutes");
            if (started == 0) started = System.nanoTime();
            require(
                System.nanoTime() - started < (step < 2 ? 180000000000L : 45000000000L),
                "actual native stage/ACK timeout " + step + " " + steps.get(step).label);
            if (Files.isRegularFile(
                HologramV011Evidence.directory()
                    .resolve("workbench-server-fail.txt")))
                throw new AssertionError("paired server reported workbench failure");
            if (capture != null) return;
            HologramScreen screen = mc.currentScreen instanceof HologramScreen ? (HologramScreen) mc.currentScreen
                : null;
            NBTTagCompound ui = screen == null ? null : screen.smokeLibraryUiState();
            if (screen != null) {
                require(!(Boolean) read(screen, "captureRenderSmokeAction"), "actual normal Netty dispatch is enabled");
                if (lastRenderedScreen != screen || lastRenderedState != HologramClient.state
                    || lastRenderedGeneration != ui.getLong("renderGeneration")) return;
                if (screen == lastScreen && ui.getLong("renderGeneration") <= lastGeneration) return;
            }
            Step current = steps.get(step);
            boolean advanced = current.probe.run(mc, screen, ui);
            lastScreen = screen;
            lastGeneration = screen == null ? -1
                : screen.smokeLibraryUiState()
                    .getLong("renderGeneration");
            if (advanced) {
                System.out.println("[GTMB-WORKBENCH-CLIENT] native step=" + step + " " + current.label);
                step++;
                started = System.nanoTime();
            }
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    private void requestCapture(HologramScreen screen, String label, NBTTagCompound ui) throws Exception {
        require(capture == null, "one actual END frame capture at a time");
        captureScreen = screen;
        capture = new NBTTagCompound();
        capture.setString("label", "workbench-" + label);
        capture.setString("session", HologramClient.state.session);
        capture.setLong("planRevision", data().getLong("planRevision"));
        capture.setLong("uiSequence", data().getLong("uiSequence"));
        capture.setLong("requestedAfterGeneration", ui.getLong("renderGeneration"));
        capture.setInteger("flowStage", ui.getInteger("flowStage"));
        capture.setBoolean("flowChosen", ui.getBoolean("flowChosen"));
        capture.setBoolean("processingFlow", ui.getBoolean("processingFlow"));
        capture.setBoolean("flowFullReview", ui.getBoolean("flowFullReview"));
        capture.setInteger("flowRequiredStages", ui.getInteger("flowRequiredStages"));
        capture.setBoolean("pendingAck", screen.smokePending());
        capture.setInteger("selectedCell", (Integer) read(screen, "selected"));
        capture.setTag("requestedUi", ui.copy());
        if (flow(screen) != null) capture.setTag("actualLocalPolicy", flow(screen).policy());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            lastRenderedScreen = mc.currentScreen instanceof HologramScreen ? (HologramScreen) mc.currentScreen : null;
            lastRenderedState = HologramClient.state;
            lastRenderedGeneration = lastRenderedScreen == null ? -1
                : lastRenderedScreen.smokeLibraryUiState()
                    .getLong("renderGeneration");
            if (capture == null) return;
            if (mc.currentScreen != captureScreen || HologramClient.state == null
                || !HologramClient.state.session.equals(capture.getString("session"))) return;
            NBTTagCompound ui = captureScreen.smokeLibraryUiState();
            if (ui.getLong("renderGeneration") <= capture.getLong("requestedAfterGeneration")) return;
            require(
                ui.getInteger("flowStage") == capture.getInteger("flowStage")
                    && ui.getBoolean("flowChosen") == capture.getBoolean("flowChosen")
                    && ui.getBoolean("processingFlow") == capture.getBoolean("processingFlow")
                    && ui.getBoolean("flowFullReview") == capture.getBoolean("flowFullReview")
                    && ui.getInteger("flowRequiredStages") == capture.getInteger("flowRequiredStages")
                    && (Integer) read(captureScreen, "selected") == capture.getInteger("selectedCell"),
                "actual END frame retains requested modal/selection state");
            require(
                data().getLong("planRevision") == capture.getLong("planRevision"),
                "actual END frame matches requested server plan");
            NBTTagCompound requestedUi = capture.getCompoundTag("requestedUi");
            for (String modal : Arrays.asList(
                "blueprintTab",
                "nbtDrawer",
                "promptDrawer",
                "deleteReview",
                "discardReview",
                "overwriteReview",
                "processingPresetSaveReview"))
                require(
                    ui.getBoolean(modal) == requestedUi.getBoolean(modal),
                    "actual END frame retains requested native UI mode " + modal);
            require(
                ui.getInteger("libraryOffset") == requestedUi.getInteger("libraryOffset"),
                "actual END frame retains requested list offset");
            if (capture.hasKey("actualLocalPolicy", 10)) require(
                flow(captureScreen) != null && capture.getCompoundTag("actualLocalPolicy")
                    .equals(flow(captureScreen).policy()),
                "actual END frame retains the exact native stage choice policy");
            String pngName = capture.getString("label") + ".png";
            ScreenShotHelper
                .saveScreenshot(mc.mcDataDir, pngName, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            Path png = mc.mcDataDir.toPath()
                .resolve("screenshots")
                .resolve(pngName);
            require(Files.isRegularFile(png) && Files.size(png) > 0, "actual END framebuffer PNG exists");
            Files.copy(
                png,
                HologramV011Evidence.directory()
                    .resolve(pngName),
                StandardCopyOption.REPLACE_EXISTING);
            NBTTagCompound witness = (NBTTagCompound) capture.copy();
            witness.setBoolean("passed", true);
            witness.setBoolean("actualNettyClient", true);
            witness.setBoolean("syntheticSnapshotsUsed", false);
            witness.setString("capturePhase", "RenderTick.END");
            witness.setLong("actualRenderGeneration", ui.getLong("renderGeneration"));
            witness.setInteger("framebufferObject", mc.getFramebuffer().framebufferObject);
            witness.setInteger("stateObjectIdentity", System.identityHashCode(HologramClient.state));
            witness.setString("png", pngName);
            witness.setString(
                "pngSha256",
                hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(png))));
            witness.setString(
                "snapshotSha256",
                hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(CompressedStreamTools.compress(data()))));
            witness.setTag("actualUi", ui.copy());
            witness.setTag("actualSnapshot", data().copy());
            if (flow(captureScreen) != null) witness.setTag("actualLocalPolicyAtEnd", flow(captureScreen).policy());
            String nbtName = capture.getString("label") + ".nbt";
            HologramV011Evidence.write(nbtName, witness);
            receipts.add(nbtName);
            receipts.add(pngName);
            capture = null;
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    private void beginSave(HologramScreen screen, int id, String name) throws Exception {
        expectedSaveName = name;
        saveReviewCaptured = saveReviewConfirmed = false;
        nativeClick(screen, id);
        observeSaveRequest(-1);
    }

    private void observeSaveRequest(long actionSequence) throws Exception {
        require(HologramLibraryClient.INSTANCE.busy(), "actual native save started library transfer preparation");
        saveRequestSession = HologramClient.state.session;
        saveRequestRevision = data().getLong("planRevision");
        saveRequestActionSequence = actionSequence;
        saveRequestGeneration = (Long) read(HologramLibraryClient.INSTANCE, "generation");
        saveRequestLastActivity = (Long) read(HologramLibraryClient.INSTANCE, "lastActivity");
    }

    private boolean awaitSave(Minecraft mc, HologramScreen screen, NBTTagCompound ui, int index) throws Exception {
        String status = ui.getString("status");
        if (!HologramLibraryClient.INSTANCE.busy() && !ui.getBoolean("busy")
            && status.startsWith("服务器：")
            && HologramClient.state.session.equals(saveRequestSession)
            && data().getLong("planRevision") == saveRequestRevision
            && (Long) read(HologramLibraryClient.INSTANCE, "generation") == saveRequestGeneration
            && (Long) read(HologramLibraryClient.INSTANCE, "lastActivity") > saveRequestLastActivity
            && (saveRequestActionSequence < 0 || data().getLong("uiSequence") >= saveRequestActionSequence))
            require(
                false,
                "actual current private save rejected revision " + saveRequestRevision
                    + " for "
                    + expectedSaveName
                    + ": "
                    + status);
        if (ui.getBoolean("aeReview")) {
            if (!saveReviewCaptured) {
                requestCapture(
                    screen,
                    index < 0 ? "overwrite-author-save-review" : "save-as-" + index + "-author-save-review",
                    ui);
                saveReviewCaptured = true;
                return false;
            }
            if (!saveReviewConfirmed && !screen.smokePending() && enabled(ui, 181)) {
                nativeClick(screen, 181);
                expectedSequence = sequence(screen);
                saveReviewConfirmed = true;
            }
            return false;
        }
        if (!idle(screen, ui) || data().getBoolean("blueprintSavePending")
            || data().getBoolean("blueprintDirty")
            || !data().getBoolean("blueprintSaved")
            || !data().getBoolean("blueprintOperationSuccess")) return false;
        String local = HologramLibraryClient.INSTANCE.selected(false);
        if (!local.equals(data().getString("libraryLocalId"))) return false;
        int row = localIndex(ui, local);
        if (row < 0 || !expectedSaveName.equals(
            ui.getTagList("entries", 10)
                .getCompoundTagAt(row)
                .getString("name")))
            return false;
        require(local.matches("local_[a-fA-F0-9-]{36}"), "actual private save produced bounded local ID");
        require(Files.isRegularFile(localPath(mc, local)), "actual private blueprint file exists after owner ACK");
        HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(localPath(mc, local));
        JsonObject blueprint = diskBlueprint(artifact);
        require(
            local.equals(
                blueprint.get("id")
                    .getAsString())
                && expectedSaveName.equals(
                    blueprint.get("name")
                        .getAsString()),
            "actual server-assigned saved JSON retains requested local ID and native typed name");
        assertSavedNativePayload(blueprint);
        if (index >= 0) require(savedIds.add(local), "each native save-as creates a distinct actual local file ID");
        assertLocalPairs(mc, "");
        NBTTagCompound proof = artifactProof(localPath(mc, local), artifact);
        NBTTagCompound nativePreview = HologramBlueprintPreview.lastCaptureWitness();
        require(
            nativePreview.getBoolean("renderTickEnd") && nativePreview.getBoolean("readbackSucceeded")
                && nativePreview.getBoolean("nativeRendering")
                && nativePreview.getInteger("width") == 640
                && nativePreview.getInteger("height") == 640
                && artifact.jsonSha256.equals(nativePreview.getString("sourceJsonSha256"))
                && "RGB8_TOP_LEFT_ROW_MAJOR".equals(nativePreview.getString("rgbPacking"))
                && nativePreview.getLong("captureGeneration") > lastPreviewCaptureGeneration,
            "actual current native preview has a fresh source-bound 640x640 render END and GL readback witness");
        require(
            pngRgbHash(artifact.png).equals(nativePreview.getString("rgbSha256")),
            "independently decoded actual PNG RGB bytes match the real native FBO readback");
        lastPreviewCaptureGeneration = nativePreview.getLong("captureGeneration");
        proof.setTag("actualNativePreviewWitness", nativePreview);
        NBTTagCompound saveWitness = HologramLibraryClient.actualSaveWitness();
        assertSaveWitness(saveWitness, artifact, local, nativePreview);
        proof.setTag("actualSaveWitness", saveWitness);
        findings.setBoolean("actualNativeFboReadbackMatchesDiskPreview", true);
        findings.setBoolean("actualNativePreviewVerificationAndOwnerCommitPackets", true);
        proof.setBoolean("passed", true);
        proof.setBoolean("actualNettyClient", true);
        proof.setString("localId", local);
        proof.setString("name", expectedSaveName);
        if (index < 0) {
            require(
                overwriteOriginalArtifact != null && !overwriteOriginalArtifact.jsonSha256.equals(artifact.jsonSha256)
                    && !overwriteOriginalArtifact.pngSha256.equals(artifact.pngSha256),
                "actual named overwrite changes JSON and its source-bound PNG hashes");
            proof.setString("previousJsonSha256", overwriteOriginalArtifact.jsonSha256);
            proof.setString("previousPngSha256", overwriteOriginalArtifact.pngSha256);
            findings.setBoolean("overwriteUpdatesNativePreviewSourceHash", true);
        }
        String stem = index < 0 ? "workbench-overwrite-native" : "workbench-save-as-" + index + "-native";
        Files.write(
            HologramV011Evidence.directory()
                .resolve(stem + ".json"),
            artifact.json);
        Files.write(
            HologramV011Evidence.directory()
                .resolve(stem + ".png"),
            artifact.png);
        proof.setString("nativeJson", stem + ".json");
        proof.setString("nativePng", stem + ".png");
        receipts.add(stem + ".json");
        receipts.add(stem + ".png");
        findings.setBoolean("actualNativeJsonPngPairAndVersionMetadata", true);
        findings.setBoolean("privateLibraryContainsOnlyCurrentNativePairs", true);
        proof.setTag("actualSnapshot", data().copy());
        HologramV011Evidence.write(
            index < 0 ? "workbench-overwrite-disk-ack.nbt" : "workbench-save-as-" + index + "-disk-ack.nbt",
            proof);
        receipts.add(index < 0 ? "workbench-overwrite-disk-ack.nbt" : "workbench-save-as-" + index + "-disk-ack.nbt");
        return true;
    }

    private void assertNativeRelations() {
        NBTTagCompound policy = data().getCompoundTag("processingPolicy");
        require(
            confirmed(policy) == 6 && "auto".equals(policy.getString("coordinateDefault"))
                && "self".equals(policy.getString("tendency")),
            "actual processed policy has six confirmations and explicit self/auto choices");
        assertCoordinateOverrides(policy);
        assertNativeRelations(data().getTagList("processingRelations", 10), false);
    }

    private void captureNativeSource() throws Exception {
        // Bind optional NC bounds and observed card caches to the actual native import ACK.
        NBTTagCompound nativeRows = null;
        NBTTagList cells = data().getTagList("cellNbtEntries", 10);
        for (int i = 0; i < cells.tagCount(); i++) if (cells.getCompoundTagAt(i)
            .getInteger("index") == ncCell) {
                require(nativeRows == null, "actual captured panel has one native NBT observation");
                nativeRows = cells.getCompoundTagAt(i);
            }
        require(nativeRows != null, "actual import ACK includes native panel NBT");
        NBTTagList entries = nativeRows.getTagList("entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            if ("tile:/Items".equals(entry.getString("path"))) {
                require(
                    sourceNativeItems == null && "list".equals(entry.getString("type"))
                        && entry.getString("value")
                            .length() < 4096,
                    "one complete native Items list");
                NBTTagCompound observed = (NBTTagCompound) JsonToNBT
                    .func_150315_a("{Items:" + entry.getString("value") + "}");
                require(observed.hasKey("Items", 9), "native Items observation retains its list type");
                sourceNativeItems = (NBTTagList) observed.getTagList("Items", 10)
                    .copy();
            } else if ("tile:/screenData".equals(entry.getString("path"))) {
                require(
                    nativeScreenData == null && "compound".equals(entry.getString("type"))
                        && entry.getString("value")
                            .length() < 4096,
                    "one complete native screenData compound");
                NBTTagCompound observed = (NBTTagCompound) JsonToNBT
                    .func_150315_a("{screenData:" + entry.getString("value") + "}");
                require(observed.hasKey("screenData", 10), "native screen bounds retain their compound type");
                nativeScreenData = (NBTTagCompound) observed.getCompoundTag("screenData")
                    .copy();
                for (String corner : new String[] { "min", "max" }) for (String axis : new String[] { "X", "Y", "Z" })
                    require(nativeScreenData.hasKey(corner + axis, 3), "native screen has complete typed bounds");
            }
        }
        require(sourceNativeItems != null && sourceNativeItems.tagCount() == 3, "native capture retains three cards");
        for (int slot = 0; slot < 3; slot++) {
            NBTTagCompound observed = (NBTTagCompound) nativeStack(sourceNativeItems, slot).copy();
            observed.removeTag("Slot");
            observed.removeTag("gtitBlueprintItemRegistry");
            NBTTagCompound original = null;
            for (int i = 0; i < sourceCards.tagCount(); i++) if (sourceCards.getCompoundTagAt(i)
                .getInteger("slot") == slot) {
                    require(original == null, "independent source card slot is unique");
                    original = (NBTTagCompound) sourceCards.getCompoundTagAt(i)
                        .copy();
                    original.removeTag("slot");
                }
            NBTTagCompound tag = observed.getCompoundTag("tag");
            if (original != null && !original.getCompoundTag("tag")
                .hasKey("state") && tag.hasKey("state")) {
                require(tag.hasKey("state", 3), "native card state is the reviewed int cache");
                tag.removeTag("state");
            }
            require(
                original != null && original.equals(observed),
                "captured card preserves exact independent source identity, configuration and unknown assets");
        }
        sourceNativeRelations = (NBTTagList) data().getTagList("processingRelations", 10)
            .copy();
        assertNativeRelationSet(sourceNativeRelations);
        assertSourceRelationTargets(sourceNativeRelations);
        findings.setTag("sourceNativeItems", sourceNativeItems.copy());
        findings.setTag("sourceNativeRelations", sourceNativeRelations.copy());
        findings.setBoolean("sourceNativeScreenDataPresent", nativeScreenData != null);
        if (nativeScreenData != null) findings.setTag("sourceNativeScreenData", nativeScreenData.copy());
    }

    private void assertNativeRelationSet(NBTTagList rows) {
        require(
            rows.tagCount() == 3 + (nativeScreenData == null ? 0 : 2),
            "native relationships exactly match three source cards and the captured screen bounds");
        Set<String> identities = new LinkedHashSet<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound relation = rows.getCompoundTagAt(i);
            String adapter = relation.getString("adapter");
            int slot = relation.getInteger("slot");
            boolean sensor = "nc.sensor".equals(adapter);
            require(
                relation.hasKey("adapter", 8) && relation.hasKey("slot", 3)
                    && (sensor ? slot >= 0 && slot < 3
                        : nativeScreenData != null && slot == -1
                            && Arrays.asList("nc.screen.min", "nc.screen.max")
                                .contains(adapter)),
                "each native relation has a registered adapter and its exact source slot");
            String identity = "0,0,0:tile:" + adapter + ":" + slot;
            require(
                identity.equals(relation.getString("id")) && identities.add(identity)
                    && "tile".equals(relation.getString("payload"))
                    && relation.hasKey("x", 3)
                    && relation.hasKey("y", 3)
                    && relation.hasKey("z", 3)
                    && relation.getInteger("x") == 0
                    && relation.getInteger("y") == 0
                    && relation.getInteger("z") == 0
                    && relation.hasKey("implicitDimension", 1)
                    && relation.getBoolean("implicitDimension")
                    && relation.hasKey("physicalAsset", 1)
                    && relation.getBoolean("physicalAsset") == sensor,
                "native relation uniquely identifies the actual panel host and physical asset kind");
        }
        for (int slot = 0; slot < 3; slot++)
            require(relationIndex(rows, "nc.sensor", slot) >= 0, "exact native sensor relation for slot " + slot);
        if (nativeScreenData != null) for (String corner : new String[] { "min", "max" })
            require(relationIndex(rows, "nc.screen." + corner, -1) >= 0, "exact native screen " + corner + " relation");
    }

    private void assertSourceRelationTargets(NBTTagList rows) {
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound relation = rows.getCompoundTagAt(i);
            boolean sensor = "nc.sensor".equals(relation.getString("adapter"));
            int slot = relation.getInteger("slot");
            NBTTagCompound source = sensor ? nativeStack(sourceNativeItems, slot).getCompoundTag("tag")
                : nativeScreenData;
            String prefix = sensor ? "" : "nc.screen.min".equals(relation.getString("adapter")) ? "min" : "max";
            int tx = source.getInteger(sensor ? "x" : prefix + "X"),
                ty = source.getInteger(sensor ? "y" : prefix + "Y"),
                tz = source.getInteger(sensor ? "z" : prefix + "Z");
            for (String key : new String[] { "targetX", "targetY", "targetZ", "targetDimension", "dx", "dy", "dz" })
                require(relation.hasKey(key, 3), "native relation retains typed coordinates and offsets");
            require(
                relation.hasKey("targetKnown", 1) && relation.getBoolean("targetKnown")
                    && relation.hasKey("internal", 1)
                    && relation.getInteger("targetX") == tx
                    && relation.getInteger("targetY") == ty
                    && relation.getInteger("targetZ") == tz
                    && relation.getInteger("targetDimension") == 0
                    && relation.getBoolean("internal") == (!sensor || slot == 0)
                    && ty == 80
                    && tz == 44
                    && tx == (sensor ? slot == 0 ? 51 : 900 + slot : 50),
                "native relation preserves its exact source target and captured internal/external classification");
        }
    }

    private void assertNativeRelations(NBTTagList rows, boolean saved) {
        assertNativeRelationSet(rows);
        for (int i = 0; i < sourceNativeRelations.tagCount(); i++) {
            NBTTagCompound original = sourceNativeRelations.getCompoundTagAt(i);
            String adapter = original.getString("adapter");
            int slot = original.getInteger("slot");
            String mode = "nc.sensor".equals(adapter) ? new String[] { "relative", "absolute", "clear" }[slot]
                : "relative";
            NBTTagCompound expected = (NBTTagCompound) original.copy();
            expected.setString("mode", mode);
            expected.setInteger("dx", "relative".equals(mode) ? original.getInteger("targetX") - 50 : 0);
            expected.setInteger("dy", "relative".equals(mode) ? original.getInteger("targetY") - 80 : 0);
            expected.setInteger("dz", "relative".equals(mode) ? original.getInteger("targetZ") - 44 : 0);
            if (saved) {
                expected.removeTag("targetKnown");
                if (!"absolute".equals(mode)) {
                    expected.setInteger("targetX", 0);
                    expected.setInteger("targetY", 0);
                    expected.setInteger("targetZ", 0);
                    expected.setInteger("targetDimension", 0);
                }
            }
            require(
                expected.equals(rows.getCompoundTagAt(relationIndex(rows, adapter, slot))),
                "exact native " + adapter + " slot " + slot + " " + mode + " relation without hidden target backups");
        }
    }

    private static void assertCoordinateOverrides(NBTTagCompound policy) {
        NBTTagList overrides = policy.getTagList("coordinateOverrides", 10);
        require(overrides.tagCount() == 3, "policy retains exactly the three explicit sensor choices");
        Set<String> identities = new LinkedHashSet<>();
        for (int i = 0; i < overrides.tagCount(); i++) {
            NBTTagCompound choice = overrides.getCompoundTagAt(i);
            String identity = choice.getString("id");
            int slot = -1;
            for (int candidate = 0; candidate < 3; candidate++)
                if (("0,0,0:tile:nc.sensor:" + candidate).equals(identity)) slot = candidate;
            require(
                slot >= 0 && identities.add(identity)
                    && new String[] { "relative", "absolute", "clear" }[slot].equals(choice.getString("mode")),
                "policy has one exact explicit mode per real sensor and leaves native bounds under self/auto");
        }
    }

    private static NBTTagCompound nativeStack(NBTTagList items, int slot) {
        NBTTagCompound result = null;
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound stack = items.getCompoundTagAt(i);
            require(stack.hasKey("Slot", 1), "native inventory retains typed slot identities");
            int actualSlot = stack.getByte("Slot") & 255;
            require(actualSlot < 3, "native inventory contains only the three actual source cards");
            if (actualSlot == slot) {
                require(result == null && stack.hasKey("tag", 10), "one native card payload per slot");
                result = stack;
            }
        }
        require(result != null, "native inventory contains source card slot " + slot);
        return result;
    }

    private void assertSavedNativePayload(JsonObject blueprint) throws Exception {
        JsonArray cells = blueprint.getAsJsonArray("cells");
        NBTTagCompound panel = null;
        for (JsonElement element : cells) {
            JsonObject cell = element.getAsJsonObject();
            if (cell.get("block")
                .getAsString()
                .equals("IC2NuclearControl:blockNuclearControlMain")) {
                require(panel == null, "actual saved blueprint contains one native panel host");
                panel = decode(
                    cell.get("tileNbt")
                        .getAsString());
            }
        }
        require(
            panel != null && panel.getString("id")
                .equals("IC2NCAdvancedInfoPanel"),
            "actual file contains native NC panel NBT");
        NBTTagList descriptors = panel.getTagList("gtmbCoordinateRelations", 10);
        assertNativeRelations(descriptors, true);
        NBTTagCompound[] cards = new NBTTagCompound[3];
        NBTTagList items = panel.getTagList("Items", 10);
        require(items.tagCount() == 3, "actual saved NC inventory retains exactly the three physical source cards");
        for (int slot = 0; slot < 3; slot++) {
            NBTTagCompound expected = (NBTTagCompound) nativeStack(sourceNativeItems, slot).copy();
            NBTTagCompound tag = expected.getCompoundTag("tag");
            if (slot != 1) {
                tag.removeTag("x");
                tag.removeTag("y");
                tag.removeTag("z");
            }
            tag.setString("gtmbCoordinatePayment", new String[] { "relative", "absolute", "clear" }[slot]);
            NBTTagCompound actual = nativeStack(items, slot);
            cards[slot] = actual.getCompoundTag("tag");
            require(
                expected.equals(actual),
                "actual saved card " + slot + " preserves exact native identity, typed caches and unknown assets");
        }
        require(
            !cards[0].hasKey("x") && !cards[0].hasKey("y")
                && !cards[0].hasKey("z")
                && "exact-non-coordinate-data-must-survive-relative".equals(cards[0].getString("followupAsset")),
            "relative card carries mapping only and preserves exact non-coordinate opaque asset data");
        require(
            cards[1].getInteger("x") == 901 && cards[1].getInteger("y") == 80 && cards[1].getInteger("z") == 44,
            "absolute external card keeps actual original coordinates");
        require(
            !cards[2].hasKey("x") && !cards[2].hasKey("y") && !cards[2].hasKey("z"),
            "clear external card removes original coordinate keys");
        if (nativeScreenData == null) require(!panel.hasKey("screenData"), "absent native screen data stays absent");
        else {
            NBTTagCompound expected = (NBTTagCompound) nativeScreenData.copy();
            for (String corner : new String[] { "min", "max" })
                for (String axis : new String[] { "X", "Y", "Z" }) expected.removeTag(corner + axis);
            require(
                panel.hasKey("screenData", 10) && expected.equals(panel.getCompoundTag("screenData")),
                "relative native bounds retain exact other screen data and remove all original coordinate keys");
        }
        require(
            omittedKey != null && !panel.hasKey(omittedKey),
            "final actual NC payload applies selected-cell ordinary NBT omission");
        require(blueprint.has("processingNbt"), "actual file carries completed processing receipt");
        NBTTagCompound processing = decode(
            blueprint.get("processingNbt")
                .getAsString());
        require(
            processing.getBoolean("complete") && confirmed(processing.getCompoundTag("policy")) == 6
                && "full".equals(
                    processing.getCompoundTag("policy")
                        .getString("autoHints")),
            "actual file contains completed manual flow and final explicit full hints policy");
        assertCoordinateOverrides(processing.getCompoundTag("policy"));
        findings.setBoolean("savedNativeCoordinatesWithoutOriginalBackup", true);
        findings.setBoolean("actualSavedNbtOmissionAndHintReceipt", true);
    }

    private void writeContract() throws Exception {
        JsonObject contract = new JsonObject();
        contract.addProperty("complete", true);
        contract.addProperty("actualNettyClient", true);
        JsonArray required = new JsonArray();
        for (String name : Arrays.asList(
            "workbench-client-final.nbt",
            "workbench-client-pass.txt",
            "workbench-server-ready.nbt",
            "workbench-server-final.nbt",
            "workbench-server-pass.txt")) required.add(new com.google.gson.JsonPrimitive(name));
        for (String name : receipts) required.add(new com.google.gson.JsonPrimitive(name));
        contract.add("required_receipts", required);
        JsonArray scope = new JsonArray();
        for (Object key : findings.func_150296_c()) {
            String name = (String) key;
            if (findings.hasKey(name, 1) && findings.getBoolean(name))
                scope.add(new com.google.gson.JsonPrimitive(name));
        }
        contract.add("verified_scope", scope);
        contract.addProperty(
            "excluded_scope",
            "Paid placement and payment/refund are covered independently; this driver claims actual author GUI, Netty ACKs and private file results only.");
        Files.write(
            HologramV011Evidence.directory()
                .resolve("workbench-contract.json"),
            contract.toString()
                .getBytes(StandardCharsets.UTF_8));
    }

    private static NBTTagCompound viewBaseline(NBTTagCompound ui) {
        NBTTagCompound baseline = new NBTTagCompound();
        baseline.setDouble("yaw", ui.getDouble("yaw"));
        baseline.setLong("planRevision", data().getLong("planRevision"));
        baseline.setInteger("facing", data().getInteger("facing"));
        baseline.setString("virtualPresetId", data().getString("virtualPresetId"));
        baseline.setString("libraryLocalId", data().getString("libraryLocalId"));
        baseline.setString("selectedPresetId", data().getString("selectedPresetId"));
        baseline.setTag(
            "cells",
            data().getTagList("cells", 10)
                .copy());
        return baseline;
    }

    private static void assertPlanBaseline(NBTTagCompound baseline) {
        require(
            data().getLong("planRevision") == baseline.getLong("planRevision")
                && data().getInteger("facing") == baseline.getInteger("facing")
                && data().getString("virtualPresetId")
                    .equals(baseline.getString("virtualPresetId"))
                && data().getString("libraryLocalId")
                    .equals(baseline.getString("libraryLocalId"))
                && data().getString("selectedPresetId")
                    .equals(baseline.getString("selectedPresetId"))
                && data().getTagList("cells", 10)
                    .equals(baseline.getTagList("cells", 10)),
            "actual server blueprint identity, selection, revision, facing and cells unchanged");
    }

    private static int anotherVisibleRow(NBTTagCompound ui, String current) {
        int offset = ui.getInteger("libraryOffset");
        NBTTagList entries = ui.getTagList("entries", 10);
        for (int i = offset; i < Math.min(entries.tagCount(), offset + 4); i++) if (!current.equals(
            entries.getCompoundTagAt(i)
                .getString("id")))
            return i;
        throw new AssertionError("actual visible library has no alternate row");
    }

    private static int localIndex(NBTTagCompound ui, String id) {
        NBTTagList entries = ui.getTagList("entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) if (id.equals(
            entries.getCompoundTagAt(i)
                .getString("id")))
            return i;
        return -1;
    }

    private static String flowId(String name) {
        NBTTagList entries = data().getTagList("processingPresets", 10);
        for (int i = 0; i < entries.tagCount(); i++) if (name.equals(
            entries.getCompoundTagAt(i)
                .getString("name")))
            return entries.getCompoundTagAt(i)
                .getString("id");
        return "";
    }

    private static int flowIndex(String id) {
        NBTTagList entries = data().getTagList("processingPresets", 10);
        for (int i = 0; i < entries.tagCount(); i++) if (id.equals(
            entries.getCompoundTagAt(i)
                .getString("id")))
            return i;
        return -1;
    }

    private static int relationIndex(int slot) {
        return relationIndex(data().getTagList("processingRelations", 10), "nc.sensor", slot);
    }

    private static int relationIndex(NBTTagList rows, String adapter, int slot) {
        int result = -1;
        for (int i = 0; i < rows.tagCount(); i++) if (rows.getCompoundTagAt(i)
            .getInteger("slot") == slot && adapter.equals(
                rows.getCompoundTagAt(i)
                    .getString("adapter"))) {
                        require(result < 0, "native adapter and slot identify one relation");
                        result = i;
                    }
        return result;
    }

    private static boolean idle(HologramScreen screen, NBTTagCompound ui) {
        return !screen.smokePending() && !ui.getBoolean("busy");
    }

    private static NBTTagCompound data() {
        require(HologramClient.state != null, "actual Netty snapshot exists");
        return HologramClient.state.data;
    }

    private static HologramProcessingFlow flow(HologramScreen screen) throws Exception {
        return (HologramProcessingFlow) read(screen, "processingFlow");
    }

    private static int confirmed(NBTTagCompound policy) {
        return policy.getTagList("confirmedStages", 8)
            .tagCount();
    }

    private static long sequence(HologramScreen screen) throws Exception {
        return (Long) read(screen, "sequence");
    }

    private static Object read(Object target, String name) throws Exception {

        return ((Object) HologramUiDiagnostics.readDiagnosticField(target, name)); // Observation only: no controller
                                                                                   // fields are written.
    }

    private static boolean contains(NBTTagList values, String wanted) {
        for (int i = 0; i < values.tagCount(); i++) if (wanted.equals(values.getStringTagAt(i))) return true;
        return false;
    }

    private static boolean enabled(NBTTagCompound ui, int id) {
        NBTTagList controls = ui.getTagList("drawnControls", 10);
        for (int i = 0; i < controls.tagCount(); i++) if (controls.getCompoundTagAt(i)
            .getInteger("id") == id)
            return controls.getCompoundTagAt(i)
                .getBoolean("enabled");
        return false;
    }

    private static boolean hasControl(NBTTagCompound ui, int id) {
        NBTTagList controls = ui.getTagList("drawnControls", 10);
        for (int i = 0; i < controls.tagCount(); i++) if (controls.getCompoundTagAt(i)
            .getInteger("id") == id) return true;
        return false;
    }

    private static boolean controlAt(NBTTagCompound ui, int x, int y) {
        NBTTagList controls = ui.getTagList("drawnControls", 10);
        for (int i = 0; i < controls.tagCount(); i++) {
            NBTTagCompound control = controls.getCompoundTagAt(i);
            if (x >= control.getInteger("x") && x < control.getInteger("x") + control.getInteger("w")
                && y >= control.getInteger("y")
                && y < control.getInteger("y") + control.getInteger("h")) return true;
        }
        return false;
    }

    private static void nativeClick(HologramScreen screen, int id) {
        int[] point = screen.smokeControlPoint(id);
        screen.mouseClicked(point[0], point[1], 0);
        screen.mouseMovedOrUp(point[0], point[1], 0);
    }

    private static int[] point(HologramScreen screen, int x, int y) throws Exception {
        float scale = (Float) read(screen, "scale");
        return new int[] { (Integer) read(screen, "originX") + Math.round(x * scale),
            (Integer) read(screen, "originY") + Math.round(y * scale) };
    }

    private static void nativeWheel(Minecraft mc, HologramScreen screen, int delta, int x, int y) throws Exception {
        int[] point = point(screen, x, y);
        int rawX = point[0] * mc.displayWidth / screen.width;
        int rawY = (screen.height - point[1] - 1) * mc.displayHeight / screen.height;
        String[] names = { "event_dwheel", "event_x", "event_y", "eventButton", "eventState" };
        Object[] values = { delta, rawX, rawY, -1, false };
        Object[] original = new Object[names.length];
        Field[] fields = new Field[names.length];
        try {
            for (int i = 0; i < names.length; i++) {
                fields[i] = Mouse.class.getDeclaredField(names[i]);
                fields[i].setAccessible(true);
                original[i] = fields[i].get(null);
                fields[i].set(null, values[i]);
            }
            screen.handleMouseInput();
        } finally {
            for (int i = 0; i < fields.length; i++)
                if (fields[i] != null && original[i] != null) fields[i].set(null, original[i]);
        }
    }

    private void corner(Minecraft mc, int button, int x, boolean finish) throws Exception {
        require(mc.theWorld != null && mc.currentScreen == null, "native area corner requires actual world focus");
        if (button == 1) press.begin(mc, false);
        if (!finish) {
            HologramV011AreaClientSmoke.aimFloatingCorner(mc, new int[] { x, 80, 44 });
            require(
                !Keyboard.isKeyDown(Keyboard.KEY_LMENU) && !Keyboard.isKeyDown(Keyboard.KEY_RMENU),
                "native source corners use the floating view ray without holding Alt");
            findings.setBoolean("actualAreaCornersUseFloatingRayWithoutAlt", true);
        }
        mc.objectMouseOver = null;
        key(Keyboard.KEY_LSHIFT, finish);
        MouseEvent event = new MouseEvent();
        Field buttonField = MouseEvent.class.getField("button");
        buttonField.setAccessible(true);
        buttonField.set(event, button);
        Field stateField = MouseEvent.class.getField("buttonstate");
        stateField.setAccessible(true);
        stateField.set(event, true);
        MinecraftForge.EVENT_BUS.post(event);
        key(Keyboard.KEY_LSHIFT, false);
        require(event.isCanceled(), "registered native Forge area mouse handler consumed actual input");
    }

    private static void key(int code, boolean down) throws Exception {
        Field field = Keyboard.class.getDeclaredField("keyDownBuffer");
        field.setAccessible(true);
        ((ByteBuffer) field.get(null)).put(code, (byte) (down ? 1 : 0));
    }

    private static Path localPath(Minecraft mc, String id) {
        require(id.matches("local_[a-fA-F0-9-]{36}"), "bounded actual private file ID");
        return mc.mcDataDir.toPath()
            .resolve("MiaoBlueprints")
            .resolve(id + ".json");
    }

    private static JsonObject diskBlueprint(HologramBlueprintArtifact.Artifact artifact) {
        JsonObject root = new JsonParser().parse(new String(artifact.json, StandardCharsets.UTF_8))
            .getAsJsonObject();
        return root.has("blueprint") ? root.getAsJsonObject("blueprint") : root;
    }

    private static NBTTagCompound artifactProof(Path jsonPath, HologramBlueprintArtifact.Artifact artifact)
        throws Exception {
        int width = ByteBuffer.wrap(artifact.png)
            .getInt(16),
            height = ByteBuffer.wrap(artifact.png)
                .getInt(20);
        String source = previewSource(artifact.png);
        require(
            width == 640 && height == 640 && source.equals(artifact.jsonSha256),
            "actual native PNG has 640x640 IHDR dimensions and binds the exact saved JSON bytes");
        JsonObject blueprint = diskBlueprint(artifact);
        require(
            blueprint.has("nativeBlueprint") && blueprint.get("nativeBlueprint")
                .isJsonObject(),
            "actual saved JSON includes native version metadata");
        JsonObject metadata = blueprint.getAsJsonObject("nativeBlueprint");
        JsonElement format = metadata.get("formatVersion");
        require(
            metadata.entrySet()
                .size() == 4 && format != null
                && format.isJsonPrimitive()
                && format.getAsJsonPrimitive()
                    .isNumber()
                && "1".equals(format.getAsString()),
            "actual native version metadata retains exact numeric formatVersion 1");
        NBTTagCompound nativeMetadata = new NBTTagCompound();
        nativeMetadata.setInteger("formatVersion", 1);
        String[] keys = { "gtmbVersion", "gtnhVersion", "gtnhVersionSource" };
        String[] values = { "0.1.0", "unknown", "unavailable" };
        for (int i = 0; i < keys.length; i++) {
            JsonElement value = metadata.get(keys[i]);
            require(
                value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive()
                        .isString()
                    && values[i].equals(value.getAsString()),
                "actual native " + keys[i] + " uses this release and partial dev environment provenance");
            nativeMetadata.setString(keys[i], value.getAsString());
        }
        NBTTagCompound proof = new NBTTagCompound();
        proof.setString("actualPath", jsonPath.toString());
        proof.setString("actualFileSha256", artifact.jsonSha256);
        proof.setString(
            "actualPngPath",
            HologramBlueprintArtifact.previewPath(jsonPath)
                .toString());
        proof.setString("actualPngSha256", artifact.pngSha256);
        proof.setInteger("actualJsonBytes", artifact.json.length);
        proof.setInteger("actualPngBytes", artifact.png.length);
        proof.setInteger("previewWidth", width);
        proof.setInteger("previewHeight", height);
        proof.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
        proof.setString("sourceJsonSha256", source);
        proof.setString("sourceJsonTextKey", HologramBlueprintArtifact.SOURCE_KEY);
        proof.setTag("actualNativeBlueprint", nativeMetadata);
        return proof;
    }

    /** readPair already validates framing and CRCs; this independently observes the PNG's actual source text. */
    private static String previewSource(byte[] png) {
        String source = null;
        for (int offset = 8; offset < png.length;) {
            int length = ByteBuffer.wrap(png, offset, 4)
                .getInt();
            String type = new String(png, offset + 4, 4, StandardCharsets.ISO_8859_1);
            if ("tEXt".equals(type)) {
                String text = new String(png, offset + 8, length, StandardCharsets.ISO_8859_1);
                String prefix = HologramBlueprintArtifact.SOURCE_KEY + '\0';
                if (text.startsWith(prefix)) {
                    require(source == null, "actual PNG has one source JSON digest text chunk");
                    source = text.substring(prefix.length());
                }
            }
            offset += length + 12;
        }
        require(source != null, "actual PNG includes the native source JSON digest text");
        return source;
    }

    private static void assertPairUnchanged(HologramBlueprintArtifact.Artifact before,
        HologramBlueprintArtifact.Artifact after, String reason) {
        require(
            before != null && Arrays.equals(before.json, after.json) && Arrays.equals(before.png, after.png),
            reason);
    }

    private static String pngRgbHash(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        require(
            image != null && image.getWidth() == 640 && image.getHeight() == 640,
            "actual PNG independently decodes");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) {
            int rgb = image.getRGB(x, y);
            digest.update((byte) (rgb >>> 16));
            digest.update((byte) (rgb >>> 8));
            digest.update((byte) rgb);
        }
        return hex(digest.digest());
    }

    private void assertSaveWitness(NBTTagCompound observed, HologramBlueprintArtifact.Artifact artifact, String localId,
        NBTTagCompound nativePreview) {
        require(
            observed.hasKey("nativePreviewWitness", 10) && observed.hasKey("previewVerifiedReply", 10)
                && observed.hasKey("ownerDiskAck", 10)
                && observed.hasKey("saveCommittedReply", 10)
                && !observed.hasKey("saveRejectedReply"),
            "actual current save records native capture, preview verification, disk owner ACK and final server commit");
        NBTTagCompound captureWitness = observed.getCompoundTag("nativePreviewWitness");
        require(
            artifact.jsonSha256.equals(captureWitness.getString("sourceJsonSha256"))
                && nativePreview.getString("rgbSha256")
                    .equals(captureWitness.getString("rgbSha256"))
                && nativePreview.getLong("captureGeneration") == captureWitness.getLong("captureGeneration"),
            "actual library save freezes this request's real source-bound native capture witness");
        String receipt = observed.getCompoundTag("ownerDiskAck")
            .getString("saveReceipt");
        require(!receipt.isEmpty(), "actual owner disk ACK retains its server-issued save receipt nonce");
        String[] keys = { "previewVerifiedReply", "ownerDiskAck", "saveCommittedReply" };
        String[] ops = { "previewVerified", "ackBlueprintSaved", "saveCommitted" };
        for (int i = 0; i < keys.length; i++) {
            NBTTagCompound packet = observed.getCompoundTag(keys[i]);
            require(
                ops[i].equals(packet.getString("op")) && packet.hasKey("planRevision", 4)
                    && saveRequestRevision == packet.getLong("planRevision")
                    && saveRequestSession.equals(packet.getString("session"))
                    && localId.equals(packet.getString("localId"))
                    && receipt.equals(packet.getString("saveReceipt"))
                    && artifact.jsonSha256.equals(packet.getString("jsonSha256"))
                    && artifact.pngSha256.equals(packet.getString("previewSha256")),
                "actual " + keys[i]
                    + " matches this owner save's exact session, revision, nonce, local ID and disk hashes");
            require(
                packet.hasKey("artifactVersion", 3) && packet.getInteger("artifactVersion") == 1,
                "actual preview verification, owner ACK and final commit identify artifact version 1");
        }
    }

    private void assertLocalPairs(Minecraft mc, String removedId) throws Exception {
        Set<String> expected = new LinkedHashSet<>(), observed = new LinkedHashSet<>();
        for (String id : savedIds) if (!id.equals(removedId)) {
            expected.add(id + ".json");
            expected.add(id + ".png");
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(
            mc.mcDataDir.toPath()
                .resolve("MiaoBlueprints"))) {
            for (Path file : files) {
                require(
                    Files.isRegularFile(file),
                    "actual private artifact directory contains only regular pair files");
                observed.add(
                    file.getFileName()
                        .toString());
            }
        }
        require(
            expected.equals(observed),
            "actual private directory has one JSON and PNG per retained ID with no stale PNG, JPEG or temporary files");
    }

    private static NBTTagCompound decode(String encoded) throws Exception {
        return CompressedStreamTools.readCompressed(
            new ByteArrayInputStream(
                Base64.getDecoder()
                    .decode(encoded)));
    }

    private void fail(Minecraft mc, Throwable failure) {
        failure.printStackTrace();
        finished = true;
        try {
            for (int key : new int[] { Keyboard.KEY_LSHIFT, Keyboard.KEY_LCONTROL, Keyboard.KEY_LMENU })
                key(key, false);
            NBTTagCompound result = new NBTTagCompound();
            result.setBoolean("passed", false);
            result.setInteger("step", step);
            result.setString("stepLabel", step < steps.size() ? steps.get(step).label : "after-final");
            result.setString("failure", failure.toString());
            if (HologramClient.state != null) result.setTag("actualSnapshot", data().copy());
            if (mc.currentScreen instanceof HologramScreen)
                result.setTag("actualUi", ((HologramScreen) mc.currentScreen).smokeLibraryUiState());
            HologramV011Evidence.write("workbench-client-failure.nbt", result);
            HologramV011Evidence.marker(
                "workbench-client-fail.txt",
                "FAIL native step=" + step + " " + result.getString("stepLabel") + " " + failure);
        } catch (Throwable evidenceFailure) {
            failure.addSuppressed(evidenceFailure);
        }
        mc.shutdown();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }
}
