package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;

import org.lwjgl.input.Keyboard;

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
    private String lastAreaDiagnostic = "";
    private boolean busyPass = true;
    private boolean drivePreviewRecorded;
    private int[] oldSavePoint, oldPlacePoint;
    private NBTTagList oldBusyDraft;
    private long busyBlockedTraffic, busyCancelledWorldTick;

    HologramAeInteractionClientSmoke(File receipts) {
        this.receipts = receipts;
        receiptReader = new HologramInteractionReceiptSmoke(receipts);
        require(Boolean.getBoolean("gtmb.hologram.aeRealInteractionSmoke"), "AE real interaction flag required");
    }

    boolean tick(TickEvent.ClientTickEvent event, long renderedFrame) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        frame = renderedFrame;
        if (event.phase == TickEvent.Phase.START) {
            if (stage == 5) key(Keyboard.KEY_RETURN, true);
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
                + (stage == 39 && mc.currentScreen instanceof HologramAeContainerScreen
                    ? " navigation=" + childNavigationDiagnostic()
                    : ""));
        // A transient publication is retried before the stage performs any non-idempotent mouse/key input.
        tickReceipt = receiptReader.read();
        if (tickReceipt == null) return false;
        if (guard != null) guard.assertStable("actual AE stage " + stage);
        if (++ticks < 3 || frame <= lastActionFrame) return false;
        switch (stage) {
            case 0:
                require(mc.currentScreen instanceof HologramScreen, "busy test must continue the real previous draft");
                parent = (HologramScreen) mc.currentScreen;
                if (rootClick(130)) next(59);
                break;
            case 59:
                oldSavePoint = parent.smokeControlPoint(132);
                oldPlacePoint = parent.smokeControlPoint(141);
                mc.displayGuiScreen(null);
                signal("ae-start.txt");
                next(1);
                break;
            case 1:
                fixture = receipt();
                if (!fixture.getBoolean("aeReady") || Math.abs(mc.thePlayer.posX - 650.5) > 1
                    || !fixtureInventoryArrived(mc, fixture)) return false;
                mc.thePlayer.setSneaking(true);
                key(Keyboard.KEY_LSHIFT, true);
                rightClickSource(mc);
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
                key(Keyboard.KEY_RETURN, true);
                next(5);
                break;
            case 5:
                if (!(mc.currentScreen instanceof HologramScreen)) return false;
                key(Keyboard.KEY_RETURN, false);
                parent = (HologramScreen) mc.currentScreen;
                NBTTagCompound imported = parent.smokeSnapshot();
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
                    busyBlockedTraffic = guard.customPackets();
                    signal("ae-busy-visible.txt");
                    parent.smokeClickAt(oldSavePoint[0], oldSavePoint[1], 0);
                    parent.smokeClickAt(oldPlacePoint[0], oldPlacePoint[1], 0);
                    int[] blockedEdit = previewScreenPoint(60, 110);
                    parent.smokeClickAt(blockedEdit[0], blockedEdit[1], 0);
                    parent.keyTyped((char) 127, Keyboard.KEY_DELETE);
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
                parent.keyTyped('e', mc.gameSettings.keyBindInventory.getKeyCode());
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
                key(Keyboard.KEY_LSHIFT, true);
                mc.thePlayer.setSneaking(true);
                rightClickSource(mc);
                next(2);
                break;
            case 58:
                if (!receipt().getBoolean("aeVirtualEpoch")) return false;
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
                if (rootClick(130)) next(7);
                break;
            case 7:
                java.lang.reflect.Field nameField = HologramScreen.class.getDeclaredField("presetNameField");
                nameField.setAccessible(true);
                GuiTextField name = (GuiTextField) nameField.get(parent);
                name.setFocused(true);
                key(Keyboard.KEY_LCONTROL, true);
                parent.keyTyped((char) 1, Keyboard.KEY_A);
                key(Keyboard.KEY_LCONTROL, false);
                for (char c : "SocketAEV005".toCharArray()) parent.keyTyped(c, 0);
                require(
                    "SocketAEV005".equals(name.getText()),
                    "actual Ctrl+A name editing did not replace the previous name");
                name.setFocused(false);
                next(8);
                break;
            case 8:
                if (rootClick(132)) next(9);
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
                if (!(mc.currentScreen instanceof HologramAeContainerScreen)) return false;
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
                if (rootClick(132)) next(18);
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
                if (!(mc.currentScreen instanceof HologramAeContainerScreen)) return false;
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
                child().smokeMouseButton(clonePoint[0], clonePoint[1], 2, true);
                child().smokeMouseButton(clonePoint[0], clonePoint[1], 2, false);
                child().keyTyped('1', Keyboard.KEY_1);
                child().keyTyped('q', Keyboard.KEY_Q);
                child().keyTyped(' ', Keyboard.KEY_SPACE);
                originalScale = mc.gameSettings.guiScale;
                mc.gameSettings.guiScale = originalScale == 1 ? 2 : 1;
                net.minecraft.client.gui.ScaledResolution scaled = new net.minecraft.client.gui.ScaledResolution(
                    mc,
                    mc.displayWidth,
                    mc.displayHeight);
                child().setWorldAndResolution(mc, scaled.getScaledWidth(), scaled.getScaledHeight());
                next(36);
                break;
            case 36:
                guard.assertStable("native NEI transformed resize/update and hotbar/drop inputs");
                mc.gameSettings.guiScale = originalScale;
                net.minecraft.client.gui.ScaledResolution restored = new net.minecraft.client.gui.ScaledResolution(
                    mc,
                    mc.displayWidth,
                    mc.displayHeight);
                child().setWorldAndResolution(mc, restored.getScaledWidth(), restored.getScaledHeight());
                unfocusSearchByMouse();
                child().keyTyped('e', mc.gameSettings.keyBindInventory.getKeyCode());
                next(37);
                break;
            case 37:
                if (mc.currentScreen != parent || parent.smokePending()) return false;
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
                if (!(mc.currentScreen instanceof HologramAeContainerScreen)) return false;
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
                child().keyTyped((char) 0, Keyboard.KEY_ESCAPE);
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
                if (!(mc.currentScreen instanceof HologramAeContainerScreen)) return false;
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
                parent.mouseClicked(point[0], point[1], 0);
                parent.mouseMovedOrUp(point[0], point[1], 0);
                next(46);
                break;
            case 46:
                require(
                    !parent.smokeAeUiState()
                        .getString("pinnedWarning")
                        .isEmpty(),
                    "actual warning icon failed to pin");
                screenshot("ae-native-warning-marker-pinned");
                if (rootClick(130)) next(47);
                break;
            case 47:
                if (rootClick(132)) next(48);
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
                signal("ae-check-confirm.txt");
                next(51);
                break;
            case 51:
                if (!receipt().getBoolean("aeConfirmedVerified")) return false;
                guard.assertStable("all AE marks/save actions actual inventory conservation");
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
                    java.util.Arrays.equals(expectedAnchor, HologramClient.movingAnchor(HologramClient.state)),
                    "AE rendered ghost and actual air use disagree");
                mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
                next(54);
                break;
            case 54:
                if (!(mc.currentScreen instanceof HologramScreen)) return false;
                parent = (HologramScreen) mc.currentScreen;
                NBTTagCompound lock = parent.smokeSnapshot();
                if (!lock.getBoolean("virtualLocked") || parent.smokePending()) return false;
                require(
                    java.util.Arrays.equals(
                        expectedAnchor,
                        new int[] { lock.getInteger("x"), lock.getInteger("y"), lock.getInteger("z") }),
                    "AE real C08 locked a different coordinate");
                require(lock.getInteger("missing") == 0, "native AE planned components are not fully funded");
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

    private boolean selectSource(int index) throws Exception {
        if (parent.smokePending()) return false;
        HologramState state = new HologramState(parent.smokeSnapshot());
        int[] bounds = parent.smokeViewportBounds();
        for (int y = bounds[1] + 10; y < bounds[1] + bounds[3] - 10; y += 2)
            for (int x = bounds[0] + 10; x < bounds[0] + bounds[2] - 10; x += 2) {
                int picked = HologramRenderer.pick(x, y);
                if (picked < 0 || picked >= state.cells.size()) continue;
                HologramState.Cell cell = state.cells.get(picked);
                if (cell.x != fixture.getIntArray("aeSourceX")[index] || cell.y != 88 || cell.z != 192) continue;
                int[] actual = previewScreenPoint(x, y);
                parent.mouseClicked(actual[0], actual[1], 0);
                parent.mouseMovedOrUp(actual[0], actual[1], 0);
                require(
                    parent.smokeSelectedIndices().length == 1 && parent.smokeSelectedIndices()[0] == picked,
                    "actual preview face click failed to select its native AE cell");
                lastActionFrame = frame;
                return true;
            }
        return false;
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
        java.lang.reflect.Field stateField = HologramScreen.class.getDeclaredField("state");
        stateField.setAccessible(true);
        HologramState state = (HologramState) stateField.get(parent);
        java.lang.reflect.Field renderedState = HologramRenderer.class.getDeclaredField("previewState");
        renderedState.setAccessible(true);
        require(renderedState.get(null) == state, "AE preview verification must observe the actual rendered state");
        java.lang.reflect.Field view = HologramScreen.class.getDeclaredField("view");
        view.setAccessible(true);
        require(view.getInt(parent) == (current ? 1 : 0), "AE actual view mouse control did not select current/target");
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
        java.lang.reflect.Field x = HologramScreen.class.getDeclaredField("originX");
        java.lang.reflect.Field y = HologramScreen.class.getDeclaredField("originY");
        java.lang.reflect.Field scale = HologramScreen.class.getDeclaredField("scale");
        x.setAccessible(true);
        y.setAccessible(true);
        scale.setAccessible(true);
        return new int[] { x.getInt(parent) + Math.round(logicalX * scale.getFloat(parent)),
            y.getInt(parent) + Math.round(logicalY * scale.getFloat(parent)) };
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
        child().smokeMouseButton(x, y, 0, true);
        child().smokeMouseButton(x, y, 0, false);
        require(LayoutManager.searchField.focused(), "actual NEI search click failed to focus");
        key(Keyboard.KEY_LCONTROL, true);
        child().keyTyped((char) 1, Keyboard.KEY_A);
        key(Keyboard.KEY_LCONTROL, false);
        for (char c : text.toCharArray()) child().keyTyped(c, 0);
        require(
            text.equals(LayoutManager.searchField.text()),
            "actual NEI search typing did not populate the public search field");
        search = text;
        lastActionFrame = frame;
        return false;
    }

    private void unfocusSearchByMouse() {
        // Title-band click follows the production outside-slot route and releases the public NEI search focus.
        int x = (child().width - 256) / 2 + 10, y = (child().height - 238) / 2 + 10;
        child().smokeMouseButton(x, y, 0, true);
        child().smokeMouseButton(x, y, 0, false);
        require(
            LayoutManager.searchField == null || !LayoutManager.searchField.focused(),
            "actual title-band click failed to release NEI search focus");
    }

    private boolean rootClick(int id) {
        if (parent.smokePending()) return false;
        boolean clicked = input.rootControl(parent, id, frame);
        if (clicked) lastActionFrame = frame;
        return clicked;
    }

    private boolean childClick(int id) {
        boolean clicked = input.childControl(child(), id, frame);
        if (clicked) lastActionFrame = frame;
        return clicked;
    }

    private HologramAeContainerScreen child() {
        require(
            Minecraft.getMinecraft().currentScreen instanceof HologramAeContainerScreen,
            "actual AE child screen required");
        return (HologramAeContainerScreen) Minecraft.getMinecraft().currentScreen;
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

    private static void rightClickSource(Minecraft mc) {
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

    private static void corner(Minecraft mc, int button, int x) throws Exception {
        corner(mc, button, x, 88, 192);
    }

    private static void corner(Minecraft mc, int button, int x, int y, int z) throws Exception {
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

    private static void wheel(int amount) throws Exception {
        net.minecraftforge.client.event.MouseEvent wheel = new net.minecraftforge.client.event.MouseEvent();
        java.lang.reflect.Field delta = wheel.getClass()
            .getField("dwheel"),
            button = wheel.getClass()
                .getField("button");
        delta.setAccessible(true);
        button.setAccessible(true);
        delta.setInt(wheel, amount);
        button.setInt(wheel, -1);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(wheel);
        require(wheel.isCanceled(), "actual AE moving wheel was not consumed");
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
