package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;

import codechicken.nei.LayoutManager;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Full live AE continuation of the existing vanilla-craft and real v004 socket flow. */
final class HologramAeInteractionClientSmoke {

    private final File receipts;
    private final long started = System.nanoTime();
    private final HologramAeActualInputProbe input = new HologramAeActualInputProbe();
    private final HologramInteractionReceiptSmoke receiptReader;
    private NBTTagCompound tickReceipt;
    private HologramAeInteractionGuard guard;
    private HologramScreen parent;
    private HologramAeActualInputProbe.EditWitness edit;
    private HologramAeActualInputProbe.SaveReviewWitness blockedReview;
    private NBTTagCompound fixture, issue;
    private final Set<Integer> inspectedIssues = new HashSet<>();
    private int stage, ticks, timedStage = -1, issuePage, sourceIndex, navigationGroup;
    private long stageStarted, frame, lastActionFrame = -1, trafficBefore;
    private String slotId, search;
    private ItemStack expected;
    private int[] expectedAnchor;
    private int originalScale;
    private String originalSavedId;
    private java.util.Map<String, String> beforeLocalFiles;
    private String confirmedLocalId;
    private String lastAreaDiagnostic = "";
    private boolean busyPass = true;
    private boolean drivePreviewRecorded;
    private int[] oldSavePoint, oldPlacePoint;
    private NBTTagList oldBusyDraft;
    private long busyBlockedTraffic, busyCancelledWorldTick;
    private long worldLockFrame, worldLockNonce;
    private boolean placementLibraryTab;
    private final HologramActualUsePress usePress = new HologramActualUsePress();
    private long nativePreviewTabRender = -1;
    private long returnParentRender = -1;
    private int selectionDiagnosticStage = -1;
    private String areaSourceSwitchNonce = "";
    private HologramProcessingClientSmoke initialProcessing, clearedProcessing, finalProcessing;
    private HologramPlacementReviewClientSmoke placementReviewInput;
    private NBTTagCompound placementOpeningState;
    private HologramAreaSelectionClientSmoke areaSelectionInput;
    private HologramScreen saveControlScreen;
    private String saveControlSession = "";
    private long saveControlRevision = -1, saveControlSequence = -1, saveControlFrame = -1;
    private final NBTTagList wheelObservations = new NBTTagList();

    HologramAeInteractionClientSmoke(File receipts) {
        this.receipts = receipts;
        receiptReader = new HologramInteractionReceiptSmoke(receipts);
        require(Boolean.getBoolean("gtmb.hologram.aeRealInteractionSmoke"), "AE real interaction flag required");
    }

    boolean tick(TickEvent.ClientTickEvent event, long renderedFrame) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        frame = renderedFrame;
        if (event.phase == TickEvent.Phase.START) {
            usePress.tick(event, mc);
            return false;
        }
        if (timedStage != stage) {
            timedStage = stage;
            stageStarted = System.nanoTime();
            ticks = 0;
            System.out.println("[GTMB-HOLOGRAM-AE-INTERACTION-CLIENT] stage=" + stage);
        }
        if (System.nanoTime() - started > 240000000000L)
            throw new IllegalStateException("AE real flow 240s timeout stage=" + stage);
        if (System.nanoTime() - stageStarted > 10000000000L) throw new IllegalStateException(
            "AE actual input/ACK 10s timeout stage=" + stage
                + " screen="
                + (mc.currentScreen == null ? "world"
                    : mc.currentScreen.getClass()
                        .getName())
                + (stage == 39 && HologramAeContainerScreen.from(mc.currentScreen) != null
                    ? " navigation=" + childNavigationDiagnostic()
                    : ""));
        // A transient publication is retried before the stage performs any non-idempotent mouse/key input.
        tickReceipt = receiptReader.read();
        if (tickReceipt == null) return false;
        if (usePress.tick(event, mc)) return false;
        if (guard != null) guard.assertStable("actual AE stage " + stage);
        if (++ticks < 3 || frame <= lastActionFrame) return false;
        switch (stage) {
            case 0:
                require(mc.currentScreen instanceof HologramScreen, "busy test must continue the real previous draft");
                parent = (HologramScreen) mc.currentScreen;
                if (libraryDrawerVisible(parent) || rootClick(149)) next(59);
                break;
            case 59:
                oldSavePoint = parent.smokeControlPoint(140);
                oldPlacePoint = parent.smokeControlPoint(141);
                mc.displayGuiScreen(null);
                signal("ae-start.txt");
                next(1);
                break;
            case 1:
                fixture = receipt();
                if (!fixture.getBoolean("aeReady") || Math.abs(mc.thePlayer.posX - 650.5) > 1
                    || !fixtureInventoryArrived(mc, fixture)) return false;
                if (areaSelectionInput == null) beginAreaSelection(mc, "ae-busy");
                if (!areaSelectionInput.tick(mc)) return false;
                next(2);
                break;
            case 2:
                if (HologramClient.state == null || !HologramClient.state.data.getBoolean("areaSelecting"))
                    return false;
                key(Keyboard.KEY_LSHIFT, false);
                mc.thePlayer.setSneaking(false);
                if (busyPass) {
                    if (!mc.theWorld.blockExists(618, 56, 157) || !mc.theWorld.blockExists(684, 120, 223)) return false;
                    corner(mc, 0, 618, 56, 157);
                } else corner(mc, 0, 648);
                next(3);
                break;
            case 3:
                int[] firstPoint = HologramClient.state.data.getIntArray("areaPoints");
                int[] wantedFirst = busyPass ? new int[] { 618, 56, 157 } : new int[] { 648, 88, 192 };
                if (firstPoint.length < 3 || firstPoint[0] != wantedFirst[0]
                    || firstPoint[1] != wantedFirst[1]
                    || firstPoint[2] != wantedFirst[2]) return false;
                if (busyPass) corner(mc, 1, 684, 120, 223);
                else corner(mc, 1, fixture.getIntArray("aeSourceX")[fixture.getInteger("aeSourceCount") - 1]);
                next(4);
                break;
            case 4:
                int[] bothPoints = HologramClient.state.data.getIntArray("areaPoints");
                int lastSourceX = fixture.getIntArray("aeSourceX")[fixture.getInteger("aeSourceCount") - 1];
                int[] wantedCorners = busyPass ? new int[] { 618, 56, 157, 684, 120, 223 }
                    : new int[] { 648, 88, 192, lastSourceX, 88, 192 };
                if (!java.util.Arrays.equals(bothPoints, wantedCorners)) return false;
                if (busyPass) oldBusyDraft = (NBTTagList) HologramClient.state.data.getTagList("cells", 10)
                    .copy();
                key(Keyboard.KEY_LSHIFT, true);
                // The finishing gesture targets a different block and must preserve both selected corners.
                corner(mc, 1, 648, 88, 191);
                key(Keyboard.KEY_LSHIFT, false);
                areaSourceSwitchNonce = "";
                next(5);
                break;
            case 5:
                if (!(mc.currentScreen instanceof HologramScreen)) return false;
                key(Keyboard.KEY_RETURN, false);
                parent = (HologramScreen) mc.currentScreen;
                NBTTagCompound imported = parent.smokeSnapshot();
                if (imported.getBoolean("discardConfirmationRequired")) {
                    NBTTagCompound proposal = imported.getCompoundTag("discardTargetAction");
                    String nonce = proposal.getString("sourceSwitchNonce");
                    require(
                        "importArea".equals(proposal.getString("op")) && proposal.getString("session")
                            .equals(imported.getString("session"))
                            && proposal.getLong("planRevision") == imported.getLong("planRevision")
                            && !nonce.isEmpty(),
                        "actual AE area import retains the server source proposal");
                    require(
                        parent.smokeLibraryUiState()
                            .getBoolean("discardReview"),
                        "actual AE source proposal opens its discard modal");
                    if (!areaSourceSwitchNonce.isEmpty()) {
                        require(
                            areaSourceSwitchNonce.equals(nonce),
                            "AE source proposal changed after the actual confirmation");
                        return false;
                    }
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        new File(receipts, "ae-source-switch-review-" + busyPass + ".nbt"))) {
                        CompressedStreamTools.writeCompressed(imported, out);
                    }
                    if (rootClick(224)) areaSourceSwitchNonce = nonce;
                    return false;
                }
                if (busyPass) {
                    if (!imported.getBoolean("areaImporting")) return false;
                    if (mc.thePlayer.getHeldItem()
                        .hasTagCompound()
                        && mc.thePlayer.getHeldItem()
                            .getTagCompound()
                            .hasKey("gtitHologramAreaSelecting"))
                        return false;
                    NBTTagCompound busy = parent.smokeAreaImportUiState();
                    require(
                        !busy.getBoolean("editable")
                            && java.util.Arrays.equals(busy.getIntArray("visibleControlIds"), new int[] { 193 }),
                        "actual import busy overlay exposes old draft actions");
                    require(
                        busy.getInteger("areaImportProgress") >= 0 && busy.getInteger("areaImportProgress") < 100,
                        "actual busy scan has no incomplete progress");
                    guard = new HologramAeInteractionGuard(mc);
                    if (HologramLibraryClient.dedicated()) beforeLocalFiles = localFiles();
                    busyBlockedTraffic = guard.customPackets();
                    signal("ae-busy-visible.txt");
                    HologramMuiProbeInput.click(parent, oldSavePoint[0], oldSavePoint[1], 0);
                    HologramMuiProbeInput.click(parent, oldPlacePoint[0], oldPlacePoint[1], 0);
                    int[] blockedEdit = previewScreenPoint(60, 110);
                    HologramMuiProbeInput.click(parent, blockedEdit[0], blockedEdit[1], 0);
                    HologramMuiProbeInput.key(parent, (char) 127, Keyboard.KEY_DELETE);
                    screenshot("ae-real-import-busy-old-actions-hidden");
                    next(60);
                    break;
                }
                String areaDiagnostic = areaDiagnostic(imported);
                if (!areaDiagnostic.equals(lastAreaDiagnostic)) {
                    lastAreaDiagnostic = areaDiagnostic;
                    System.out.println("[GTMB-HOLOGRAM-AE-INTERACTION-CLIENT] AREA " + areaDiagnostic);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        new File(receipts, "ae-area-observed-state.nbt"))) {
                        CompressedStreamTools.writeCompressed(imported, out);
                    }
                }
                if (imported.getString("status")
                    .contains("区域导入失败")) {
                    screenshot("ae-native-area-import-failed");
                    throw new IllegalStateException("real AE area import failed: " + areaDiagnostic);
                }
                // capturePending belongs to machine capture. Area import has its own authoritative lifecycle.
                if (imported.getBoolean("capturePending") || !imported.getBoolean("virtual")
                    || imported.getBoolean("areaSelecting")
                    || imported.getBoolean("areaImporting")
                    || !imported.getBoolean("areaImported")) return false;
                require(
                    imported.getInteger("mode") == 0 && imported.getInteger("scope") == 0,
                    "completed native AE area defaults to build and whole scope");
                require(
                    imported.getTagList("cells", 10)
                        .tagCount() >= fixture.getInteger("aeSourceCount"),
                    "real area import omitted native source cells: " + areaDiagnostic);
                for (int sourceX : fixture.getIntArray("aeSourceX")) {
                    boolean found = false;
                    NBTTagList importedCells = imported.getTagList("cells", 10);
                    for (int i = 0; i < importedCells.tagCount(); i++) {
                        NBTTagCompound cell = importedCells.getCompoundTagAt(i);
                        found |= cell.getInteger("x") == sourceX && cell.getInteger("y") == 88
                            && cell.getInteger("z") == 192
                            && !"minecraft:air".equals(cell.getString("wantId"));
                    }
                    require(
                        found,
                        "real completed AE import omitted native coordinate " + sourceX + ": " + areaDiagnostic);
                }
                originalSavedId = imported.getString("selectedPresetId");
                if (HologramLibraryClient.dedicated()) beforeLocalFiles = localFiles();
                signal("ae-virtual-begin.txt");
                guard = new HologramAeInteractionGuard(mc);
                next(58);
                break;
            case 60:
                require(
                    guard.customPackets() == busyBlockedTraffic,
                    "actual busy save/place/edit mouse or Delete emitted a GTMB packet");
                if (!receipt().getBoolean("aeBusyStarted")) return false;
                require(
                    parent.smokeSnapshot()
                        .getBoolean("areaImporting"),
                    "natural import window ended before user cancellation");
                HologramMuiProbeInput.key(parent, 'e', mc.gameSettings.keyBindInventory.getKeyCode());
                busyCancelledWorldTick = receipt().getLong("tick");
                next(61);
                break;
            case 61:
                require(
                    mc.currentScreen == null,
                    "E during actual import failed to return to the world or reopened GUI");
                if (HologramClient.state == null || HologramClient.state.data.getBoolean("areaImporting")) return false;
                require(
                    oldBusyDraft.equals(HologramClient.state.data.getTagList("cells", 10)),
                    "cancelled natural import changed the previous complete draft");
                if (receipt().getLong("tick") - busyCancelledWorldTick < 80) return false;
                require(
                    guard.customPackets() > busyBlockedTraffic,
                    "actual E close emitted no custom cancellation packet");
                signal("ae-busy-check-cancel.txt");
                next(62);
                break;
            case 62:
                require(mc.currentScreen == null, "late completion reopened a cancelled real import");
                if (!receipt().getBoolean("aeBusyCancelledVerified")) return false;
                guard.assertStable("natural busy E cancellation and delayed no-reopen");
                if (HologramLibraryClient.dedicated()) require(
                    beforeLocalFiles.equals(localFiles()),
                    "natural busy E cancellation wrote no private client file");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new File(receipts, "ae-client-busy.nbt"))) {
                    CompressedStreamTools.writeCompressed(guard.receipt(), out);
                }
                guard.close();
                guard = null;
                busyPass = false;
                screenshot("ae-real-import-cancel-world-no-reopen");
                System.out.println(
                    "[GTMB-HOLOGRAM-AE-INTERACTION-CLIENT] PASS natural-busy E-cancel old-draft/disk/source/inventory intact no-reopen80ticks");
                key(Keyboard.KEY_RETURN, false);
                beginAreaSelection(mc, "ae-import");
                next(64);
                break;
            case 64:
                if (!areaSelectionInput.tick(mc)) return false;
                next(2);
                break;
            case 58:
                if (!receipt().getBoolean("aeVirtualEpoch")) return false;
                if (libraryDrawerVisible(parent)) {
                    if (rootClick(148)) nativePreviewTabRender = parent.smokeRenderGeneration();
                    return false;
                }
                if (parent.smokeRenderGeneration() <= nativePreviewTabRender) return false;
                NBTTagCompound importedPreview = parent.smokeSnapshot();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new File(receipts, "ae-source-preview-state.nbt"))) {
                    CompressedStreamTools.writeCompressed(importedPreview, out);
                }
                verifyActualPreview(false, screenshot("ae-real-native-area-import"));
                if (rootClick(10)) next(56);
                break;
            case 56:
                verifyActualPreview(true, screenshot("ae-native-current-real-preview"));
                if (rootClick(10)) next(57);
                break;
            case 57:
                screenshot("ae-native-difference-real-preview");
                if (rootClick(10)) next(6);
                break;
            case 6:
                if (rootClick(149)) next(7);
                break;
            case 7:
                if (initialProcessing == null) initialProcessing = processingDriver("ae-initial");
                if (!initialProcessing.tick(parent)) return false;
                enterDraftName();
                next(8);
                break;
            case 8:
                if (rootClick(140)) next(9);
                break;
            case 9:
                if (!parent.smokeAeUiState()
                    .getBoolean("modal")) return false;
                blockedReview = new HologramAeActualInputProbe.SaveReviewWitness(parent);
                require(
                    blockedReview.blockers >= 1 && !blockedReview.canConfirm,
                    "native hidden pattern did not block save");
                require(
                    parent.smokeAeUiState()
                        .getInteger("warningCount") >= 1,
                    "native full cell warning missing");
                screenshot("ae-save-aggregate-native-blocker-warning");
                if (rootClick(181)) next(10);
                break;
            case 10:
                blockedReview.assertSameReview(parent);
                require(
                    parent.smokeAeUiState()
                        .getBoolean("modal"),
                    "disabled confirm dismissed the blocked review");
                if (rootClick(186)) next(11);
                break;
            case 11:
                blockedReview.assertSameReview(parent);
                NBTTagCompound ui = parent.smokeAeUiState();
                NBTTagList rows = ui.getTagList("visibleRows", 10);
                for (int i = 0; i < rows.tagCount(); i++) {
                    NBTTagCompound row = rows.getCompoundTagAt(i);
                    inspectedIssues.add(row.getInteger("index"));
                    if ("hidden_pattern".equals(row.getString("code"))) {
                        issue = (NBTTagCompound) row.copy();
                        issuePage = ui.getInteger("offset");
                    }
                }
                if (ui.getInteger("offset") + rows.tagCount() < ui.getInteger("filteredRowCount")) {
                    rootClick(189);
                    return false;
                }
                require(
                    inspectedIssues.size() == ui.getInteger("filteredRowCount") && issue != null,
                    "real all-issue navigation missed a row or native hidden pattern");
                require(
                    "clearSlot".equals(issue.getString("resolution")),
                    "hidden pattern lacks actionable clear navigation");
                screenshot("ae-real-complete-issue-tail");
                next(12);
                break;
            case 12:
                int offset = parent.smokeAeUiState()
                    .getInteger("offset");
                if (offset > issuePage) {
                    rootClick(188);
                    return false;
                }
                if (offset < issuePage) {
                    rootClick(189);
                    return false;
                }
                if (rootClick(issue.getInteger("controlId"))) next(13);
                break;
            case 13:
                if (rootClick(192)) next(14);
                break;
            case 14:
                if (!(HologramAeContainerScreen.from(mc.currentScreen) != null)) return false;
                HologramAeContainerScreen blockedChild = child();
                require(
                    blockedChild.smokeModel().x == issue.getInteger("x")
                        && blockedChild.smokeModel().y == issue.getInteger("y")
                        && blockedChild.smokeModel().z == issue.getInteger("z"),
                    "issue navigation opened the wrong native cell");
                slotId = issue.getString("slotId");
                navigationGroup = 0;
                next(15);
                break;
            case 15:
                if (!navigate(slotId)) return false;
                trafficBefore = guard.customPackets();
                edit = input.clear(child(), slotId, frame);
                if (edit != null) {
                    lastActionFrame = frame;
                    next(16);
                }
                break;
            case 16:
                if (!edit.assertAcknowledged(parent, child(), true, null, "")) return false;
                guard.requireCustomTrafficSince(trafficBefore, "hidden native pattern blueprint CLEAR");
                screenshot("ae-native-hidden-slot-cleared-source-preserved");
                if (childClick(1)) next(17);
                break;
            case 17:
                if (mc.currentScreen != parent || parent.smokePending()) return false;
                if (clearedProcessing == null) clearedProcessing = processingDriver("ae-after-hidden-clear");
                if (!clearedProcessing.tick(parent)) return false;
                if (rootClick(140)) next(18);
                break;
            case 18:
                if (!parent.smokeAeUiState()
                    .getBoolean("modal")) return false;
                blockedReview.assertReplacementReview(parent);
                require(
                    parent.smokeAeUiState()
                        .getInteger("blockerCount") == 0 && parent.smokeAeUiState()
                            .getBoolean("canConfirm"),
                    "actual CLEAR failed to resolve save blocker");
                screenshot("ae-save-resolved-warning-review");
                if (rootClick(182)) next(19);
                break;
            case 19:
                if (parent.smokePending() || parent.smokeAeUiState()
                    .getBoolean("modal")) return false;
                signal("ae-check-cancel.txt");
                next(20);
                break;
            case 20:
                if (!receipt().getBoolean("aeCancelledVerified")) return false;
                if (HologramLibraryClient.dedicated())
                    require(beforeLocalFiles.equals(localFiles()), "cancelled AE save wrote no private client file");
                if (rootClick(148)) next(21);
                break;
            case 21:
                if (!selectSource(1)) return false;
                next(22);
                break;
            case 22:
                if (rootClick(179)) next(23);
                break;
            case 23:
                if (!(HologramAeContainerScreen.from(mc.currentScreen) != null)) return false;
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new File(receipts, "ae-preview-stage23-state.nbt"))) {
                    CompressedStreamTools.writeCompressed(parent.smokeSnapshot(), out);
                }
                require(child().smokeModel().x == 652, "real header preview opened the wrong Interface");
                require(
                    equal(mark(child(), "patterns/#0"), sample("aePatternSample")),
                    "exact native encoded recipe missing");
                require(equal(mark(child(), "upgrades/#0"), sample("aeCardSample")), "native crafting card missing");
                slotId = "config/#0";
                navigationGroup = 0;
                next(24);
                break;
            case 24:
                if (!navigate(slotId)) return false;
                if (!typeSearch(new ItemStack(net.minecraft.init.Items.diamond).getDisplayName())) return false;
                next(25);
                break;
            case 25:
                trafficBefore = guard.customPackets();
                edit = input
                    .neiDrop(child(), slotId, s -> s.getItem() == net.minecraft.init.Items.diamond, false, frame);
                if (edit != null) {
                    expected = edit.requestedSample.copy();
                    lastActionFrame = frame;
                    next(26);
                }
                break;
            case 26:
                if (!edit.assertAcknowledged(parent, child(), true, expected, "")) return false;
                guard.requireCustomTrafficSince(trafficBefore, "actual public NEI ghost SET");
                screenshot("ae-nei-public-panel-real-ghost-set-ack");
                edit = input.clear(child(), slotId, frame);
                if (edit != null) {
                    lastActionFrame = frame;
                    next(27);
                }
                break;
            case 27:
                if (!edit.assertAcknowledged(parent, child(), true, null, "")) return false;
                slotId = "patterns/#0";
                navigationGroup = 0;
                next(28);
                break;
            case 28:
                if (!navigate(slotId) || !typeSearch(sample("aeBlankPatternSample").getDisplayName())) return false;
                next(29);
                break;
            case 29:
                ItemStack blank = sample("aeBlankPatternSample");
                edit = input.neiDrop(child(), slotId, s -> s.isItemEqual(blank), false, frame);
                if (edit != null) {
                    lastActionFrame = frame;
                    next(30);
                }
                break;
            case 30:
                if (!edit.assertAcknowledged(parent, child(), false, null, "ae.edit.rejected")) return false;
                require(
                    equal(mark(child(), slotId), sample("aePatternSample")),
                    "blank pattern changed the real recipe marker");
                screenshot("ae-nei-blank-pattern-rejected-exact-mark-preserved");
                next(31);
                break;
            case 31:
                if (!backpackSet(sample("aePatternSample"))) return false;
                expected = sample("aePatternSample");
                next(32);
                break;
            case 32:
                if (!edit.assertAcknowledged(parent, child(), true, expected, "")) return false;
                slotId = "upgrades/#0";
                navigationGroup = 0;
                next(33);
                break;
            case 33:
                if (!navigate(slotId)) return false;
                edit = input.clear(child(), slotId, frame);
                if (edit != null) {
                    lastActionFrame = frame;
                    next(34);
                }
                break;
            case 34:
                if (!edit.assertAcknowledged(parent, child(), true, null, "")) return false;
                if (!backpackSet(sample("aeCardSample"))) return false;
                expected = sample("aeCardSample");
                next(35);
                break;
            case 35:
                if (!edit.assertAcknowledged(parent, child(), true, expected, "")) return false;
                screenshot("ae-physical-pattern-card-backpack-copy-ack");
                unfocusSearchByMouse();
                int[] clonePoint = child().smokeSlotPoint(slotId);
                require(clonePoint != null, "card marker disappeared before native clone input check");
                HologramMuiProbeInput.click(child(), clonePoint[0], clonePoint[1], 2);
                HologramMuiProbeInput.key(child(), '1', Keyboard.KEY_1);
                HologramMuiProbeInput.key(child(), 'q', Keyboard.KEY_Q);
                HologramMuiProbeInput.key(child(), ' ', Keyboard.KEY_SPACE);
                originalScale = mc.gameSettings.guiScale;
                mc.gameSettings.guiScale = originalScale == 1 ? 2 : 1;
                net.minecraft.client.gui.ScaledResolution scaled = new net.minecraft.client.gui.ScaledResolution(
                    mc,
                    mc.displayWidth,
                    mc.displayHeight);
                child().getScreenWrapper()
                    .getGuiScreen()
                    .setWorldAndResolution(mc, scaled.getScaledWidth(), scaled.getScaledHeight());
                next(36);
                break;
            case 36:
                guard.assertStable("native NEI transformed resize/update and hotbar/drop inputs");
                mc.gameSettings.guiScale = originalScale;
                net.minecraft.client.gui.ScaledResolution restored = new net.minecraft.client.gui.ScaledResolution(
                    mc,
                    mc.displayWidth,
                    mc.displayHeight);
                child().getScreenWrapper()
                    .getGuiScreen()
                    .setWorldAndResolution(mc, restored.getScaledWidth(), restored.getScaledHeight());
                unfocusSearchByMouse();
                HologramMuiProbeInput.key(child(), 'e', mc.gameSettings.keyBindInventory.getKeyCode());
                returnParentRender = parent.smokeRenderGeneration();
                next(37);
                break;
            case 37:
                if (mc.currentScreen != parent || parent.smokePending()) return false;
                if (parent.smokeRenderGeneration() <= returnParentRender) return false;
                require(parent.smokeSelectedIndices().length == 1, "child E lost the parent local cell selection");
                if (!selectSource(0)) return false;
                next(38);
                break;
            case 38:
                if (rootClick(179)) {
                    slotId = "inv/item0";
                    navigationGroup = 0;
                    drivePreviewRecorded = false;
                    next(39);
                }
                break;
            case 39:
                if (!(HologramAeContainerScreen.from(mc.currentScreen) != null)) return false;
                require(child().smokeModel().x == 648, "Drive native preview binding mismatch");
                if (!drivePreviewRecorded) {
                    System.out.println(
                        "[GTMB-HOLOGRAM-AE-INTERACTION-CLIENT] Drive navigation first " + childNavigationDiagnostic());
                    screenshot("ae-drive-empty-cell-warning-native-child");
                    drivePreviewRecorded = true;
                }
                require(
                    equal(mark(child(), slotId), sample("aeEmptyCellMark")),
                    "Drive canonical empty cell/card projection mismatch");
                if (!backpackSet(sample("aeFullCellSample"))) return false;
                expected = sample("aeEmptyCellMark");
                next(40);
                break;
            case 40:
                if (!edit.assertAcknowledged(parent, child(), true, expected, "")) return false;
                require(
                    mark(child(), slotId).getTagCompound()
                        .getLong("ic") == 0L,
                    "full storage contents leaked into the mark");
                HologramMuiProbeInput.key(child(), (char) 0, Keyboard.KEY_ESCAPE);
                sourceIndex = 2;
                next(41);
                break;
            case 41:
                if (mc.currentScreen != parent || parent.smokePending()) return false;
                if (!selectSource(sourceIndex)) return false;
                next(42);
                break;
            case 42:
                if (rootClick(179)) next(43);
                break;
            case 43:
                if (!(HologramAeContainerScreen.from(mc.currentScreen) != null)) return false;
                require(
                    child().smokeModel().x == fixture.getIntArray("aeSourceX")[sourceIndex],
                    "part host preview coordinate mismatch");
                boolean pattern = false, card = false, ghost = false;
                for (HologramAePreviewModel.MarkSlot s : child().smokeModel().slots) {
                    pattern |= s.id.contains("patterns/") && s.stack() != null;
                    card |= s.id.contains("upgrades/") && s.stack() != null;
                    ghost |= "ghost".equals(s.kind);
                }
                require(pattern && card && ghost, "native part host omitted pattern/card/ghost inventory descriptors");
                screenshot(
                    sourceIndex == 2 ? "ae-cablebus-native-part-container" : "ae-fmp-native-interface-part-container");
                if (childClick(1)) next(44);
                break;
            case 44:
                if (mc.currentScreen != parent || parent.smokePending()) return false;
                sourceIndex++;
                if (sourceIndex < fixture.getInteger("aeSourceCount")) next(41);
                else next(45);
                break;
            case 45:
                // Warning signs are actually drawn; hover and pin route through the real root mouse handlers.
                NBTTagList markers = parent.smokeAeWarningMarkers();
                require(markers.tagCount() >= 1, "real AE mini warning markers missing");
                int[] point = markers.getCompoundTagAt(0)
                    .getIntArray("screenPoint");
                require(point.length == 2, "AE warning marker has no drawn hit coordinate");
                HologramMuiProbeInput.click(parent, point[0], point[1], 0);
                next(46);
                break;
            case 46:
                require(
                    !parent.smokeAeUiState()
                        .getString("pinnedWarning")
                        .isEmpty(),
                    "actual warning icon failed to pin");
                screenshot("ae-native-warning-marker-pinned");
                if (rootClick(149)) next(47);
                break;
            case 47:
                if (finalProcessing == null) finalProcessing = processingDriver("ae-after-edits");
                if (!finalProcessing.tick(parent)) return false;
                assertPreservedDraftName();
                if (rootClick(140)) next(48);
                break;
            case 48:
                if (!parent.smokeAeUiState()
                    .getBoolean("modal")) return false;
                HologramAeActualInputProbe.SaveReviewWitness finalReview = new HologramAeActualInputProbe.SaveReviewWitness(
                    parent);
                require(
                    finalReview.canConfirm && finalReview.blockers == 0,
                    "final real edited blueprint is not saveable");
                if (rootClick(187)) next(49);
                break;
            case 49:
                require(
                    parent.smokeAeUiState()
                        .getInteger("materialCount") >= fixture.getInteger("aeSourceCount"),
                    "material review omitted native shells/cards/patterns");
                screenshot("ae-save-exact-material-counts-before-confirm");
                if (rootClick(181)) next(50);
                break;
            case 50:
                if (parent.smokePending() || parent.smokeAeUiState()
                    .getBoolean("modal")) return false;
                String saved = parent.smokeSnapshot()
                    .getString("selectedPresetId");
                if (saved.isEmpty() || saved.equals(originalSavedId)) return false;
                if (HologramLibraryClient.dedicated()) {
                    HologramLibraryClient library = HologramLibraryClient.INSTANCE;
                    if (library.busy()) return false;
                    NBTTagCompound committed = parent.smokeSnapshot();
                    if (!committed.getBoolean("blueprintSaved") || committed.getBoolean("blueprintDirty")
                        || committed.getBoolean("blueprintSavePending")) return false;
                    confirmedLocalId = library.selected(false);
                    if (confirmedLocalId.isEmpty()) return false;
                    int rowIndex = localRow(library.rows(false), confirmedLocalId);
                    if (rowIndex < 0) return false;
                    require(
                        !beforeLocalFiles.equals(localFiles()) && localFiles().containsKey(confirmedLocalId + ".json"),
                        "confirmed AE save completed the actual private file before placement");
                    require(
                        new File(mc.mcDataDir, "MiaoBlueprints/" + confirmedLocalId + ".png").isFile(),
                        "confirmed AE save completed the actual native preview before placement");
                    observeLocalSave();
                }
                signal("ae-check-confirm.txt");
                next(51);
                break;
            case 51:
                if (!receipt().getBoolean("aeConfirmedVerified")) return false;
                if (!libraryDrawerVisible(parent)) {
                    rootClick(149);
                    return false;
                }
                if (HologramLibraryClient.dedicated()) require(
                    confirmedLocalId.equals(HologramLibraryClient.INSTANCE.selected(false)),
                    "paid AE place selects the confirmed private file rather than a previous blueprint");
                guard.assertStable("all AE marks/save actions actual inventory conservation");
                placementLibraryTab = libraryTab(parent);
                if (!rootClick(141)) return false;
                NBTTagCompound guarded = guard.receipt();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new File(receipts, "ae-client-virtual.nbt"))) {
                    CompressedStreamTools.writeCompressed(guarded, out);
                }
                guard.close();
                guard = null;
                signal("ae-paid-begin.txt");
                next(52);
                break;
            case 52:
                if (mc.currentScreen != null || HologramClient.state == null
                    || !HologramClient.state.data.getBoolean("moving")
                    || !receipt().getBoolean("aePaidStarted")) return false;
                mc.objectMouseOver = null;
                mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 135;
                mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -35;
                wheel(120);
                wheel(120);
                expectedAnchor = HologramClient.movingAnchor(HologramClient.state);
                screenshot("ae-real-world-camera-scroll-ghost");
                next(53);
                break;
            case 53:
                mc.objectMouseOver = null;
                require(
                    HologramRenderer.lastWorldGeometry > 0
                        && HologramRenderer.lastWorldPlacementOutlineColor == 0x70e69a,
                    "AE actual moving world ghost is not rendered green before first RIGHT");
                require(
                    java.util.Arrays.equals(expectedAnchor, HologramClient.movingAnchor(HologramClient.state)),
                    "AE rendered ghost and actual air use disagree");
                airRightClick(mc, true);
                worldLockFrame = frame;
                next(54);
                break;
            case 54:
                if (HologramClient.state == null || !HologramClient.state.data.getBoolean("virtualLocked"))
                    return false;
                NBTTagCompound lockedWorld = HologramClient.state.data;
                require(
                    mc.currentScreen == null && !lockedWorld.getBoolean("open"),
                    "AE first RIGHT opened GUI before RIGHT2");
                require(
                    java.util.Arrays.equals(
                        expectedAnchor,
                        new int[] { lockedWorld.getInteger("x"), lockedWorld.getInteger("y"),
                            lockedWorld.getInteger("z") }),
                    "AE first C08 locked a different rendered coordinate");
                if (frame <= worldLockFrame || HologramRenderer.lastWorldPlacementOutlineColor != 0x66d9ef)
                    return false;
                require(HologramRenderer.lastWorldGeometry > 0, "AE locked cyan world ghost has no actual geometry");
                require(receipt().getInteger("aePlaceEvents") == 0, "AE first RIGHT placed before material review");
                screenshot("ae-v007-first-right-cyan-world-lock");
                worldLockNonce = lockedWorld.getLong("placementReviewNonce");
                mc.objectMouseOver = null;
                placementOpeningState = null;
                airRightClick(mc, false);
                next(63);
                break;
            case 63:
                if (!(mc.currentScreen instanceof HologramScreen)) return false;
                parent = (HologramScreen) mc.currentScreen;
                NBTTagCompound lock = parent.smokeSnapshot();
                if (!lock.getBoolean("virtualLocked") || parent.smokePending()) return false;
                if (placementOpeningState == null) {
                    placementOpeningState = (NBTTagCompound) lock.copy();
                    require(
                        lock.getLong("placementReviewNonce") > worldLockNonce && lock.getBoolean("open"),
                        "AE second RIGHT did not issue a fresh real material review");
                    require(libraryTab(parent) == placementLibraryTab, "AE opening review switched the last peer tab");
                    require(
                        java.util.Arrays.equals(
                            expectedAnchor,
                            new int[] { lock.getInteger("x"), lock.getInteger("y"), lock.getInteger("z") }),
                        "AE opening review changed the actual locked coordinate");
                    try (java.io.OutputStream output = Files
                        .newOutputStream(new File(receipts, "ae-placement-right2-open-state.nbt").toPath())) {
                        CompressedStreamTools.writeCompressed(placementOpeningState, output);
                    }
                }
                if (!placementReviewReady()) return false;
                lock = parent.smokeSnapshot();
                require(
                    placementOpeningState.getString("session")
                        .equals(lock.getString("session")) && lock.getBoolean("virtualPlacement")
                        && lock.getBoolean("virtualLocked")
                        && lock.getBoolean("blueprintAwaitingConfirmation")
                        && lock.getLong("planRevision") >= placementOpeningState.getLong("planRevision")
                        && lock.getLong("placementReviewNonce")
                            >= placementOpeningState.getLong("placementReviewNonce"),
                    "AE placement identity acknowledgement lost the current locked material review");
                require(libraryTab(parent) == placementLibraryTab, "AE placement review switched the last peer tab");
                require(
                    java.util.Arrays.equals(
                        expectedAnchor,
                        new int[] { lock.getInteger("x"), lock.getInteger("y"), lock.getInteger("z") }),
                    "AE real C08 locked a different coordinate");
                require(lock.getInteger("missing") == 0, "native AE planned components are not fully funded");
                require(
                    receipt().getInteger("aePlaceEvents") == 0,
                    "AE identity review placed before material confirmation");
                System.out
                    .println("[GTMB-HOLOGRAM-AE-INTERACTION-CLIENT] precheck " + parent.smokePlacementReviewContext());
                screenshot("ae-real-paid-placement-material-precheck");
                if (rootClick(55)) next(55);
                break;
            case 55:
                NBTTagCompound completed = parent.smokeSnapshot();
                if (completed.getInteger("job") != 3 && completed.getInteger("job") != 5) return false;
                NBTTagCompound independent = receipt();
                if (!independent.getBoolean("aeCompleted")) return false;
                require(
                    libraryTab(parent) == placementLibraryTab,
                    "AE paid placement completion switched the peer tab");
                require(
                    completed.getInteger("completed") == fixture.getInteger("aeSourceCount")
                        && independent.getInteger("aePlaceEvents") == fixture.getInteger("aeSourceCount"),
                    "real AE primary confirmation did not install every funded source device exactly once");
                screenshot("ae-real-paid-native-placement-source-intact");
                signal("ae-client-complete.txt");
                System.out.println(
                    "[GTMB-HOLOGRAM-AE-INTERACTION-CLIENT] FINAL PASS actual NEI/marks/nonce/source/cost/container protocol");
                return true;
            default:
                throw new IllegalStateException("unknown real AE stage=" + stage);
        }
        return false;
    }

    private void enterDraftName() throws Exception {

        HologramMuiTextField name = (HologramMuiTextField) ((Object) HologramUiDiagnostics
            .readDiagnosticField(parent, "presetNameField"));
        int[] point = previewScreenPoint(name.xPosition + 4, name.yPosition + 7);
        HologramMuiProbeInput.click(parent, point[0], point[1], 0);
        require(name.isFocused(), "actual mouse click did not focus the blueprint name");
        HologramMuiProbeInput.key(parent, (char) 1, Keyboard.KEY_A);
        for (char c : "SocketAEV005".toCharArray()) HologramMuiProbeInput.key(parent, c, 0);
        require("SocketAEV005".equals(name.getText()), "actual Ctrl+A name editing did not replace the previous name");
        HologramMuiProbeInput.key(parent, '\r', Keyboard.KEY_RETURN);
        require(!name.isFocused(), "actual Enter did not commit and release the blueprint name");
    }

    private void assertPreservedDraftName() throws Exception {

        String name = ((HologramMuiTextField) ((Object) HologramUiDiagnostics
            .readDiagnosticField(parent, "presetNameField"))).getText();
        NBTTagCompound observed = parent.smokeLibraryUiState();
        observed.setString("draftName", name);
        observed.setString("expectedDraftName", "SocketAEV005");
        observed.setBoolean("typedOnlyAtInitialSave", true);
        try (java.io.OutputStream output = Files
            .newOutputStream(new File(receipts, "ae-draft-name-preserved.nbt").toPath())) {
            CompressedStreamTools.writeCompressed(observed, output);
        }
        require("SocketAEV005".equals(name), "AE child edits or returns lost the initially typed draft name");
    }

    private boolean selectSource(int index) throws Exception {
        if (parent.smokePending()) return false;

        java.lang.reflect.Field previewState = HologramRenderer.class.getDeclaredField("previewState");

        previewState.setAccessible(true);
        if (((Object) HologramUiDiagnostics.readDiagnosticField(parent, "state")) != previewState.get(null))
            return false;
        HologramState state = new HologramState(parent.smokeSnapshot());
        int[] bounds = parent.smokeViewportBounds();
        NBTTagList skipped = new NBTTagList();
        for (int y = bounds[1] + 10; y < bounds[1] + bounds[3] - 10; y += 2)
            for (int x = bounds[0] + 10; x < bounds[0] + bounds[2] - 10; x += 2) {
                int picked = HologramRenderer.pick(x, y);
                if (picked < 0 || picked >= state.cells.size()) continue;
                HologramState.Cell cell = state.cells.get(picked);
                if (cell.x != fixture.getIntArray("aeSourceX")[index] || cell.y != 88 || cell.z != 192) continue;
                int[] actual = previewScreenPoint(x, y);
                NBTTagCompound route = previewClickRoute(actual[0], actual[1]);
                route.setIntArray("scanPoint", new int[] { x, y });
                route.setInteger("scanPicked", picked);
                if (!"selection".equals(route.getString("route")) || route.getInteger("picked") != picked) {
                    if (skipped.tagCount() < 32) skipped.appendTag(route);
                    continue;
                }
                route.setTag("skippedCandidates", skipped);
                route.setInteger("sourceIndex", index);
                route.setTag("beforeUi", parent.smokeLibraryUiState());
                HologramMuiProbeInput.click(parent, actual[0], actual[1], 0);
                route.setIntArray("actualSelectedIndices", parent.smokeSelectedIndices());
                route.setTag("afterUi", parent.smokeLibraryUiState());
                writeSelectionObservation(route);
                if (parent.smokeSelectedIndices().length != 1 || parent.smokeSelectedIndices()[0] != picked)
                    screenshot("ae-selection-failure-stage-" + stage);
                require(
                    parent.smokeSelectedIndices().length == 1 && parent.smokeSelectedIndices()[0] == picked,
                    "actual preview face click failed to select its native AE cell");
                lastActionFrame = frame;
                return true;
            }
        if (skipped.tagCount() > 0 && selectionDiagnosticStage != stage) {
            NBTTagCompound observed = parent.smokeLibraryUiState();
            observed.setInteger("sourceIndex", index);
            observed.setTag("skippedCandidates", skipped);
            observed.setBoolean("noEligibleFace", true);
            writeSelectionObservation(observed);
            screenshot("ae-selection-obstructed-stage-" + stage);
            selectionDiagnosticStage = stage;
        }
        return false;
    }

    private void writeSelectionObservation(NBTTagCompound observation) throws Exception {
        try (java.io.OutputStream output = Files
            .newOutputStream(new File(receipts, "ae-selection-stage-" + stage + ".nbt").toPath())) {
            CompressedStreamTools.writeCompressed(observation, output);
        }
    }

    /** Observe the existing mouse routing at the transformed point, without changing editor selection or overlays. */
    private NBTTagCompound previewClickRoute(int screenX, int screenY) throws Exception {
        int x = (Integer) screenPointCall("localX", screenX), y = (Integer) screenPointCall("localY", screenY);
        NBTTagCompound observed = new NBTTagCompound();
        observed.setIntArray("screenPoint", new int[] { screenX, screenY });
        observed.setIntArray("logicalPoint", new int[] { x, y });
        observed.setInteger("picked", HologramRenderer.pick(x, y));
        observed.setString("structure", HologramRenderer.pickStructure(x, y));
        observed.setLong("renderGeneration", parent.smokeRenderGeneration());
        observed.setIntArray("selectedIndices", parent.smokeSelectedIndices());
        boolean sameType = screenFlag("sameTypeSelectionHeld"), air = screenFlag("airSelectionHeld");
        observed.setBoolean("sameTypeHeld", sameType);
        observed.setBoolean("additiveHeld", screenFlag("controlSelectionHeld"));
        String route = "selection";
        if (!screenFlag("previewInteractive")) route = "modal";
        else if (!screenFlag("editable")) route = "readonly";
        else if (!(Boolean) screenPointCall("inViewport", x, y) || (Boolean) screenPointCall("previewBlockedAt", x, y))
            route = "surface";
        else {
            if (screenFlag("annotationInteractive")) {

                for (Object entry : (java.util.List<?>) ((Object) HologramUiDiagnostics
                    .readDiagnosticField(parent, "visibleMarkers")))
                    if (((HologramAnnotationMarkers.Marker) entry).contains(x, y)) route = "annotation";
            }
            if ("selection".equals(route)) {
                NBTTagList controls = parent.smokeLibraryUiState()
                    .getTagList("drawnControls", 10);
                for (int i = 0; i < controls.tagCount(); i++) {
                    NBTTagCompound control = controls.getCompoundTagAt(i);
                    if (x >= control.getInteger("x") && x < control.getInteger("x") + control.getInteger("w")
                        && y >= control.getInteger("y")
                        && y < control.getInteger("y") + control.getInteger("h")) {
                        route = "control";
                        observed.setInteger("control", control.getInteger("id"));
                        break;
                    }
                }
            }

            if ("selection".equals(route) && !sameType
                && !air
                && ((Boolean) HologramUiDiagnostics.readDiagnosticField(parent, "moveHandles"))
                && screenFlag("canMoveSelection")) {

                int axis = ((HologramGizmo) ((Object) HologramUiDiagnostics.readDiagnosticField(parent, "gizmo")))
                    .pick(x, y);
                if (axis >= 0) {
                    route = "gizmo";
                    observed.setInteger("axis", axis);
                }
            }
            if ("selection".equals(route) && air) route = "air-selection";
            if ("selection".equals(route) && net.minecraft.client.gui.GuiScreen.isShiftKeyDown() && !sameType)
                route = "box-selection";
        }
        observed.setString("route", route);
        return observed;
    }

    private boolean screenFlag(String name) throws Exception {

        return (Boolean) HologramUiDiagnostics.invokeDiagnostic(parent, name, new Class<?>[] {});
    }

    private Object screenPointCall(String name, int... point) throws Exception {
        Class<?>[] parameters = new Class<?>[point.length];
        Object[] arguments = new Object[point.length];
        for (int i = 0; i < point.length; i++) {
            parameters[i] = int.class;
            arguments[i] = point[i];
        }

        return HologramUiDiagnostics.invokeDiagnostic(parent, name, parameters, arguments);
    }

    private java.util.Map<String, String> localFiles() throws Exception {
        java.util.Map<String, String> files = new java.util.TreeMap<>();
        java.nio.file.Path directory = Minecraft.getMinecraft().mcDataDir.toPath()
            .resolve("MiaoBlueprints");
        if (!Files.exists(directory)) return files;
        try (
            java.nio.file.DirectoryStream<java.nio.file.Path> entries = Files.newDirectoryStream(directory, "*.json")) {
            for (java.nio.file.Path entry : entries) {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(entry));
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                files.put(
                    entry.getFileName()
                        .toString(),
                    hex.toString());
            }
        }
        return files;
    }

    private void observeLocalSave() throws Exception {
        NBTTagCompound witness = new NBTTagCompound();
        witness.setString("localId", confirmedLocalId);
        witness.setBoolean("realClientFileExists", true);
        witness.setString("sha256", localFiles().get(confirmedLocalId + ".json"));
        witness.setBoolean("downloadComplete", !HologramLibraryClient.INSTANCE.busy());
        witness.setBoolean(
            "realClientPreviewExists",
            new File(Minecraft.getMinecraft().mcDataDir, "MiaoBlueprints/" + confirmedLocalId + ".png").isFile());
        witness.setTag("currentState", parent.smokeSnapshot());
        witness.setTag("currentUi", parent.smokeLibraryUiState());
        witness.setTag("actualSaveWitness", HologramLibraryClient.actualSaveWitness());
        NBTTagList baseline = new NBTTagList();
        for (java.util.Map.Entry<String, String> entry : beforeLocalFiles.entrySet()) {
            NBTTagCompound file = new NBTTagCompound();
            file.setString("file", entry.getKey());
            file.setString("sha256", entry.getValue());
            baseline.appendTag(file);
        }
        witness.setTag("beforeClientLocalFiles", baseline);
        try (java.io.OutputStream output = Files
            .newOutputStream(new File(receipts, "ae-client-local-save.nbt").toPath())) {
            CompressedStreamTools.writeCompressed(witness, output);
        }
    }

    private static int localRow(NBTTagList rows, String id) {
        for (int i = 0; i < rows.tagCount(); i++) if (id.equals(
            rows.getCompoundTagAt(i)
                .getString("id")))
            return i;
        return -1;
    }

    private String areaDiagnostic(NBTTagCompound snapshot) {
        StringBuilder cells = new StringBuilder();
        NBTTagList rows = snapshot.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (i > 0) cells.append(';');
            cells.append(row.getInteger("x"))
                .append(',')
                .append(row.getInteger("y"))
                .append(',')
                .append(row.getInteger("z"))
                .append('=')
                .append(row.getString("wantId"))
                .append('/')
                .append(row.getInteger("wantMeta"));
        }
        return "session=" + snapshot.getString("session")
            + " revision="
            + snapshot.getLong("planRevision")
            + " anchor="
            + snapshot.getInteger("x")
            + ","
            + snapshot.getInteger("y")
            + ","
            + snapshot.getInteger("z")
            + " capturePending="
            + snapshot.getBoolean("capturePending")
            + " areaSelecting="
            + snapshot.getBoolean("areaSelecting")
            + " areaImporting="
            + snapshot.getBoolean("areaImporting")
            + " areaImported="
            + snapshot.getBoolean("areaImported")
            + " virtual="
            + snapshot.getBoolean("virtual")
            + " points="
            + java.util.Arrays.toString(snapshot.getIntArray("areaPoints"))
            + " expectedSources="
            + java.util.Arrays.toString(fixture.getIntArray("aeSourceX"))
            + " expectedCount="
            + fixture.getInteger("aeSourceCount")
            + " actualCount="
            + rows.tagCount()
            + " cells="
            + cells
            + " status="
            + snapshot.getString("status");
    }

    private void verifyActualPreview(boolean current, java.awt.image.BufferedImage raster) throws Exception {

        HologramState state = (HologramState) ((Object) HologramUiDiagnostics.readDiagnosticField(parent, "state"));
        java.lang.reflect.Field renderedState = HologramRenderer.class.getDeclaredField("previewState");
        renderedState.setAccessible(true);
        require(renderedState.get(null) == state, "AE preview verification must observe the actual rendered state");

        require(
            ((Integer) HologramUiDiagnostics.readDiagnosticField(parent, "view")) == (current ? 1 : 0),
            "AE actual view mouse control did not select current/target");
        int sourceCells = 0, atlasCells = 0, nativeCells = 0;
        int[] bounds = parent.smokeViewportBounds();
        for (HologramState.Cell cell : state.cells) {
            if (cell.x != 648 && cell.x != 652 && cell.x != 656 && cell.x != 660) continue;
            boolean renderedCurrent = current || cell.anchor
                || cell.status.equals("protected") && cell.actualStack != null;
            String diagnostic = "index=" + cell.index
                + " pos="
                + cell.x
                + ","
                + cell.y
                + ","
                + cell.z
                + " requestedCurrent="
                + current
                + " renderedCurrent="
                + renderedCurrent
                + " actual="
                + cell.id
                + "/"
                + cell.meta
                + " target="
                + cell.wantId
                + "/"
                + cell.wantMeta
                + " status="
                + cell.status
                + " blockClass="
                + cell.block(renderedCurrent)
                    .getClass()
                    .getName();
            boolean nativeRoute = HologramNativeTiles.supported(cell.block(renderedCurrent));
            System.out.println(
                "[GTMB-HOLOGRAM-AE-INTERACTION-CLIENT] RENDER " + diagnostic
                    + " route="
                    + (nativeRoute ? "native-model" : "atlas"));
            if (nativeRoute) {
                require(
                    HologramBlockRender.ready(cell, current),
                    "actual AE native GL model missing: " + diagnostic
                        + " failure="
                        + HologramBlockRender.failure(cell, renderedCurrent));
                require(
                    HologramBlockRender.vertexCount(cell, renderedCurrent) > 0
                        && !HologramBlockRender.missingTexture(cell, renderedCurrent),
                    "actual AE native geometry empty or missingno: " + diagnostic);
                nativeCells++;
            } else {
                require(
                    cell.x == 648 || cell.x == 652,
                    "CBC/FMP must keep its registered native model route: " + diagnostic);
                HologramTextures.Material material = HologramTextures.material(cell, current);
                require(
                    !material.fallback && !material.geometry.isEmpty(),
                    "AE ordinary atlas fallback: " + diagnostic);
                for (int side = 0; side < 6; side++) {
                    require(
                        !material.sides[side].isEmpty(),
                        "AE ordinary atlas side missing: " + diagnostic + " side=" + side);
                    for (HologramTextures.Sample sample : material.sides[side]) {
                        net.minecraft.util.IIcon icon = sample.icon;
                        require(
                            icon != null && !"missingno".equals(icon.getIconName())
                                && icon.getIconWidth() > 0
                                && icon.getIconHeight() > 0
                                && icon.getMaxU() > icon.getMinU()
                                && icon.getMaxV() > icon.getMinV(),
                            "AE ordinary atlas icon invalid/missing: " + diagnostic + " side=" + side);
                    }
                }
                atlasCells++;
            }
            boolean visibleRaster = false;
            for (int y = bounds[1] + 10; y < bounds[1] + bounds[3] - 10 && !visibleRaster; y += 2)
                for (int x = bounds[0] + 10; x < bounds[0] + bounds[2] - 10; x += 2) {
                    if (HologramRenderer.pick(x, y) != cell.index) continue;
                    int[] screen = previewScreenPoint(x, y);
                    int px = (int) Math.round(screen[0] * (double) raster.getWidth() / parent.width);
                    int py = (int) Math.round(screen[1] * (double) raster.getHeight() / parent.height);
                    Set<Integer> colors = new HashSet<>();
                    for (int ry = Math.max(0, py - 3); ry <= Math.min(raster.getHeight() - 1, py + 3); ry++)
                        for (int rx = Math.max(0, px - 3); rx <= Math.min(raster.getWidth() - 1, px + 3); rx++)
                            colors.add(raster.getRGB(rx, ry) & 0xffffff);
                    if (colors.size() > 2) {
                        visibleRaster = true;
                        break;
                    }
                }
            require(visibleRaster, "AE source has no textured actual preview raster/hit: " + diagnostic);
            sourceCells++;
        }
        // Drive/Interface may use their explicitly registered audited native adapters. CBC/FMP remain native-only.
        require(
            sourceCells == fixture.getInteger("aeSourceCount") && atlasCells + nativeCells == sourceCells
                && nativeCells >= fixture.getInteger("aeSourceCount") - 2,
            "actual AE registered render route/source count mismatch");
        require(
            org.lwjgl.opengl.GL11.glGetError() == org.lwjgl.opengl.GL11.GL_NO_ERROR,
            "actual AE native current/target GL error");
        System.out.println(
            "[GTMB-HOLOGRAM-AE-INTERACTION-CLIENT] PASS actual-current=" + current
                + " atlas="
                + atlasCells
                + " native="
                + nativeCells
                + " raster="
                + sourceCells);
    }

    private int[] previewScreenPoint(int logicalX, int logicalY) throws Exception {

        return new int[] {
            ((Integer) HologramUiDiagnostics.readDiagnosticField(parent, "originX"))
                + Math.round(logicalX * ((Float) HologramUiDiagnostics.readDiagnosticField(parent, "scale"))),
            ((Integer) HologramUiDiagnostics.readDiagnosticField(parent, "originY"))
                + Math.round(logicalY * ((Float) HologramUiDiagnostics.readDiagnosticField(parent, "scale"))) };
    }

    private boolean navigate(String logicalSlot) {
        if (child().smokeSlotPoint(logicalSlot) != null) return true;
        if (child().smokeControlEnabled(5)) {
            childClick(5);
            return false;
        }
        if (navigationGroup >= child().smokeGroups()
            .size())
            throw new IllegalStateException("native slot inaccessible through actual tabs/pages: " + logicalSlot);
        if (childClick(100 + navigationGroup)) navigationGroup++;
        return false;
    }

    private String childNavigationDiagnostic() throws ReflectiveOperationException {
        HologramAeContainerScreen screen = child();
        java.lang.reflect.Field group = HologramAeContainerScreen.class.getDeclaredField("group");
        group.setAccessible(true);
        return "groups=" + screen.smokeGroups()
            + " activeGroup="
            + group.getInt(screen)
            + " slotPage="
            + screen.smokeSlotPage()
            + " backpackPage="
            + screen.smokeBackpackPage()
            + " slotId="
            + slotId
            + " visible="
            + (screen.smokeSlotPoint(slotId) != null)
            + " nextGroup="
            + navigationGroup;
    }

    private boolean backpackSet(ItemStack sample) {
        if (!navigate(slotId)) return false;
        Minecraft mc = Minecraft.getMinecraft();
        int index = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack candidate = mc.thePlayer.inventory.mainInventory[i];
            if (candidate != null && candidate.isItemEqual(sample)
                && ItemStack.areItemStackTagsEqual(candidate, sample)) {
                index = i;
                break;
            }
        }
        require(index >= 0, "real prepared backpack sample absent");
        if (child().smokeBackpackPage() != index / 9) {
            childClick(3);
            return false;
        }
        edit = input.backpackDrop(child(), slotId, index % 9, frame);
        if (edit == null) return false;
        // Physical cards and patterns have limit1; source backpack stacks may contain several funded copies.
        lastActionFrame = frame;
        return true;
    }

    private boolean typeSearch(String text) throws Exception {
        if (LayoutManager.searchField == null || LayoutManager.itemPanel == null) return false;
        if (text.equals(search) && text.equals(LayoutManager.searchField.text())) return true;
        int x = LayoutManager.searchField.x + LayoutManager.searchField.w / 2;
        int y = LayoutManager.searchField.y + LayoutManager.searchField.h / 2;
        HologramMuiProbeInput.click(child(), x, y, 0);
        require(LayoutManager.searchField.focused(), "actual NEI search click failed to focus");
        HologramMuiProbeInput.key(child(), (char) 1, Keyboard.KEY_A);
        for (char c : text.toCharArray()) HologramMuiProbeInput.key(child(), c, 0);
        require(
            text.equals(LayoutManager.searchField.text()),
            "actual NEI search typing did not populate the public search field");
        search = text;
        lastActionFrame = frame;
        return false;
    }

    private void unfocusSearchByMouse() {
        // Title-band click follows the production outside-slot route and releases the public NEI search focus.
        int x = child().getMainPanel()
            .getArea().x + 10,
            y = child().getMainPanel()
                .getArea().y + 10;
        HologramMuiProbeInput.click(child(), x, y, 0);
        require(
            LayoutManager.searchField == null || !LayoutManager.searchField.focused(),
            "actual title-band click failed to release NEI search focus");
    }

    private static boolean libraryTab(HologramScreen screen) throws ReflectiveOperationException {

        return ((Boolean) HologramUiDiagnostics.readDiagnosticField(screen, "blueprintTab"));
    }

    private static boolean libraryDrawerVisible(HologramScreen screen) throws ReflectiveOperationException {

        return ((Boolean) HologramUiDiagnostics.readDiagnosticField(screen, "libraryDrawer"));
    }

    private boolean rootClick(int id) throws Exception {
        if (parent.smokePending()) return false;
        if (id == 55 && !placementReviewReady()) return false;
        if (id == 140 && !saveControlReady()) return false;
        NBTTagCompound saveSource = id == 140 ? parent.smokeSnapshot() : null;
        NBTTagCompound saveUi = id == 140 ? parent.smokeLibraryUiState() : null;
        boolean clicked = input.rootControl(parent, id, frame);
        if (clicked) {
            lastActionFrame = frame;
            if (id == 140) {
                require(
                    HologramLibraryClient.dedicated() ? HologramLibraryClient.INSTANCE.busy() : parent.smokePending(),
                    "actual enabled AE save click did not dispatch a save request");
                NBTTagCompound observed = new NBTTagCompound();
                observed.setTag("sourceState", saveSource);
                observed.setTag("beforeUi", saveUi);
                observed.setTag("afterUi", parent.smokeLibraryUiState());
                try (java.io.OutputStream output = Files
                    .newOutputStream(new File(receipts, "ae-save-click-stage-" + stage + ".nbt").toPath())) {
                    CompressedStreamTools.writeCompressed(observed, output);
                }
            }
        }
        return clicked;
    }

    private boolean saveControlReady() {
        NBTTagCompound state = parent.smokeSnapshot();
        if (saveControlScreen != parent || !saveControlSession.equals(state.getString("session"))
            || saveControlRevision != state.getLong("planRevision")
            || saveControlSequence != state.getLong("uiSequence")) {
            saveControlScreen = parent;
            saveControlSession = state.getString("session");
            saveControlRevision = state.getLong("planRevision");
            saveControlSequence = state.getLong("uiSequence");
            saveControlFrame = parent.smokeRenderGeneration();
            return false;
        }
        if (parent.smokeRenderGeneration() <= saveControlFrame) return false;
        NBTTagList controls = parent.smokeLibraryUiState()
            .getTagList("drawnControls", 10);
        for (int i = 0; i < controls.tagCount(); i++) {
            NBTTagCompound control = controls.getCompoundTagAt(i);
            if (control.getInteger("id") == 140) return control.getBoolean("enabled");
        }
        return false;
    }

    private boolean placementReviewReady() throws Exception {
        if (placementReviewInput == null)
            placementReviewInput = new HologramPlacementReviewClientSmoke(receipts, "ae", (screen, id) -> {
                require(
                    screen == parent && Minecraft.getMinecraft().currentScreen == parent,
                    "placement identity input belongs to the actual current AE parent");
                return rootClick(id);
            }, this::receipt);
        return placementReviewInput.tick(parent);
    }

    private HologramProcessingClientSmoke processingDriver(String label) {
        return new HologramProcessingClientSmoke(receipts, label, (screen, id) -> {
            require(
                screen == parent && Minecraft.getMinecraft().currentScreen == parent,
                "processing input belongs to the actual current AE parent");
            return rootClick(id);
        });
    }

    private boolean childClick(int id) {
        boolean clicked = input.childControl(child(), id, frame);
        if (clicked) lastActionFrame = frame;
        return clicked;
    }

    private HologramAeContainerScreen child() {
        require(
            HologramAeContainerScreen.from(Minecraft.getMinecraft().currentScreen) != null,
            "actual AE child screen required");
        return HologramAeContainerScreen.from(Minecraft.getMinecraft().currentScreen);
    }

    private ItemStack sample(String key) {
        return ItemStack.loadItemStackFromNBT(fixture.getCompoundTag(key));
    }

    private NBTTagCompound receipt() throws Exception {
        require(tickReceipt != null, "actual tick receipt must be validated before actions");
        return tickReceipt;
    }

    private void next(int target) {
        stage = target;
        ticks = 0;
        lastActionFrame = frame;
    }

    private void signal(String name) throws Exception {
        Files.write(
            new File(receipts, name).toPath(),
            "actual isolated AE verification checkpoint\n".getBytes(StandardCharsets.UTF_8));
    }

    private java.awt.image.BufferedImage screenshot(String name) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        String filename = "hologram-interaction-" + name + ".png";
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, filename, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        File png = new File(new File(mc.mcDataDir, "screenshots"), filename);
        require(png.isFile() && png.length() > 0, "actual AE screenshot missing");
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(png);
        require(
            image != null && image.getWidth() == mc.displayWidth && image.getHeight() == mc.displayHeight,
            "actual AE screenshot resolution differs from the live framebuffer");
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 8)
            for (int x = 0; x < image.getWidth(); x += 8) colors.add(image.getRGB(x, y) & 0xffffff);
        require(colors.size() > 8, "actual AE framebuffer is empty/flat");
        require(
            org.lwjgl.opengl.GL11.glGetError() == org.lwjgl.opengl.GL11.GL_NO_ERROR,
            "actual AE screenshot GL error");
        System.out.println("[GTMB-HOLOGRAM-AE-INTERACTION-CLIENT] screenshot=" + png.getAbsolutePath());
        return image;
    }

    private static ItemStack mark(HologramAeContainerScreen child, String id) {
        for (HologramAePreviewModel.MarkSlot slot : child.smokeModel().slots)
            if (id.equals(slot.id)) return slot.stack();
        throw new IllegalStateException("native server slot missing " + id);
    }

    private static boolean equal(ItemStack a, ItemStack b) {
        return a == null ? b == null
            : b != null && a.writeToNBT(new NBTTagCompound())
                .equals(b.writeToNBT(new NBTTagCompound()));
    }

    private static boolean fixtureInventoryArrived(Minecraft mc, NBTTagCompound fixture) {
        NBTTagList entries = fixture.getTagList("aeFixtureInventory", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound n = entries.getCompoundTagAt(i);
            int slot = n.getByte("Slot") & 255;
            if (slot > 0 && slot < 36
                && !equal(mc.thePlayer.inventory.mainInventory[slot], ItemStack.loadItemStackFromNBT(n))) return false;
        }
        return true;
    }

    private void airRightClick(Minecraft mc, boolean checkWorldLock) {
        usePress.begin(mc, checkWorldLock);
        mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
    }

    private void beginAreaSelection(Minecraft mc, String label) throws Exception {
        mc.thePlayer.setSneaking(true);
        areaSelectionInput = new HologramAreaSelectionClientSmoke(
            receipts,
            label,
            HologramAeInteractionClientSmoke::key,
            this::rightClickSource);
        areaSelectionInput
            .begin(mc, new MovingObjectPosition(648, 88, 192, 2, Vec3.createVectorHelper(648.5, 88.5, 192)));
    }

    private void rightClickSource(Minecraft mc) {
        usePress.begin(mc, false);
        mc.playerController.onPlayerRightClick(
            mc.thePlayer,
            mc.theWorld,
            mc.thePlayer.getHeldItem(),
            648,
            88,
            192,
            2,
            Vec3.createVectorHelper(648.5, 88.5, 192));
    }

    private void corner(Minecraft mc, int button, int x) throws Exception {
        corner(mc, button, x, 88, 192);
    }

    private void corner(Minecraft mc, int button, int x, int y, int z) throws Exception {
        if (button == 1) usePress.begin(mc, false);
        mc.objectMouseOver = new MovingObjectPosition(x, y, z, 2, Vec3.createVectorHelper(x + .5, y + .5, z));
        net.minecraftforge.client.event.MouseEvent mouse = new net.minecraftforge.client.event.MouseEvent();
        java.lang.reflect.Field b = mouse.getClass()
            .getField("button"),
            pressed = mouse.getClass()
                .getField("buttonstate");
        b.setAccessible(true);
        pressed.setAccessible(true);
        b.setInt(mouse, button);
        pressed.setBoolean(mouse, true);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(mouse);
        require(mouse.isCanceled(), "actual area mouse corner was not consumed");
    }

    private void wheel(int amount) throws Exception {
        net.minecraftforge.client.event.MouseEvent wheel = new net.minecraftforge.client.event.MouseEvent();
        java.lang.reflect.Field delta = wheel.getClass()
            .getField("dwheel"),
            button = wheel.getClass()
                .getField("button"),
            buttonState = wheel.getClass()
                .getField("buttonstate");
        delta.setAccessible(true);
        button.setAccessible(true);
        buttonState.setAccessible(true);
        delta.setInt(wheel, amount);
        button.setInt(wheel, -1);
        buttonState.setBoolean(wheel, false);
        java.lang.reflect.Field keyboardBuffer = Keyboard.class.getDeclaredField("keyDownBuffer");
        keyboardBuffer.setAccessible(true);
        ByteBuffer keys = (ByteBuffer) keyboardBuffer.get(null);
        byte previousAlt = keys.get(Keyboard.KEY_LMENU);
        NBTTagCompound observed = new NBTTagCompound();
        observed.setByte("leftAltBefore", previousAlt);
        observed.setIntArray("anchorBefore", HologramClient.movingAnchor(HologramClient.state));
        int before = previewDistance();
        observed.setInteger("distanceBefore", before);
        observed.setInteger("wheel", amount);
        try {
            key(Keyboard.KEY_LMENU, true);
            observed.setBoolean("altHeld", Keyboard.isKeyDown(Keyboard.KEY_LMENU));
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(wheel);
            require(wheel.isCanceled(), "actual AE moving wheel was not consumed");
            int after = previewDistance();
            long steps = Math.max(1L, Math.abs((long) amount) / 120L);
            int expected = (int) Math.max(
                HologramWorldFlow.MIN_DISTANCE,
                Math.min(HologramWorldFlow.MAX_DISTANCE, before + (amount > 0 ? steps : -steps)));
            require(after == expected && after != before, "actual AE Alt-wheel did not move the preview distance");
            observed.setInteger("distanceAfter", after);
            observed.setIntArray("anchorAfter", HologramClient.movingAnchor(HologramClient.state));
            observed.setBoolean("eventCancelled", wheel.isCanceled());
        } finally {
            keys.put(Keyboard.KEY_LMENU, previousAlt);
        }
        observed.setByte("leftAltAfter", keys.get(Keyboard.KEY_LMENU));
        wheelObservations.appendTag(observed);
        NBTTagCompound evidence = new NBTTagCompound();
        evidence.setTag("wheels", wheelObservations.copy());
        evidence.setTag("state", HologramClient.state.data.copy());
        try (java.io.OutputStream output = Files
            .newOutputStream(new File(receipts, "ae-world-alt-wheel.nbt").toPath())) {
            CompressedStreamTools.writeCompressed(evidence, output);
        }
    }

    private static int previewDistance() throws Exception {
        java.lang.reflect.Field field = HologramClient.class.getDeclaredField("previewDistance");
        field.setAccessible(true);
        return ((HologramWorldFlow.PreviewDistance) field.get(null)).distance();
    }

    private static void key(int code, boolean down) throws Exception {
        java.lang.reflect.Field field = Keyboard.class.getDeclaredField("keyDownBuffer");
        field.setAccessible(true);
        ((ByteBuffer) field.get(null)).put(code, (byte) (down ? 1 : 0));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
