package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/** Projector workspace opening, closing and source admission. */
final class HologramProjectorWorkflow {

    private HologramProjectorWorkflow() {}

    static void open(EntityPlayerMP player, int x, int y, int z, int side) {
        HologramSession previous = HologramWorkspace.find(player.getUniqueID());
        if (previous != null && previous.player == player && HologramSessionPolicy.blockedByAreaImport(previous)) {
            HologramSnapshots.sendUi(previous);
            return;
        }
        if (previous != null && previous.player == player
            && !previous.cancelled
            && (previous.job == 1 || previous.job == 2 || HologramAeCraftingOrders.active(player))
            && HologramSessionPolicy.jobValid(previous)) {
            openAir(player);
            return;
        }
        if (previous != null && previous.player == player
            && !previous.virtual
            && !previous.cancelled
            && previous.x == x
            && previous.y == y
            && previous.z == z
            && HologramSessionPolicy.valid(previous)) {
            previous.open = true;
            previous.settingsOnly = false;
            dismissPresetBrowse(previous);
            previous.openPurpose = "controllerStructure";
            HologramSnapshots.sendUi(previous);
            return;
        }
        String draftFailure = HologramDraftPersistence.admissionFailure(player, x, y, z);
        if (!draftFailure.isEmpty()) {
            player.addChatMessage(new net.minecraft.util.ChatComponentText(draftFailure));
            return;
        }
        if (previous != null && previous.player == player
            && HologramWorkspace.areaSessionOwned(previous)
            && HologramAuthoringWorkflow.blueprintHasUnsavedChanges(previous)) {
            if (!HologramWorldFlow.worldPosition(x, y, z, player.worldObj.getHeight())
                || !HologramSessionPolicy.previewReach(player, x, y, z)
                || !player.worldObj.blockExists(x, y, z)
                || side < 0
                || side > 5) return;
            TileEntity target = player.worldObj.getTileEntity(x, y, z);
            if (target == null) return;
            Object context = HologramStructureDiscovery.context(target);
            NBTTagCompound next = new NBTTagCompound();
            next.setString("op", "openController");
            next.setInteger("x", x);
            next.setInteger("y", y);
            next.setInteger("z", z);
            next.setInteger("side", side);
            next.setString("presetId", "controller:" + x + "," + y + "," + z);
            HologramAuthoringWorkflow.requestSourceSwitch(previous, next, target, context, null);
            HologramSnapshots.sendUi(previous);
            return;
        }
        if (previous != null && (previous.job == 1 || previous.job == 2)) {
            previous.job = 4;
            previous.phase = "STOPPED";
            previous.status = "施工已停止：" + HologramSessionPolicy.jobInvalidReason(previous) + "；已施工方块保留";
            HologramSnapshots.sendStoppedJob(previous);
        }
        HologramSession session = HologramWorkspace.create(player, x, y, z, true, side);
        session.libraryUiActive = true;
        if (!HologramDraftPersistence.readDraft(session)) {
            player.addChatMessage(new net.minecraft.util.ChatComponentText(session.status));
            return;
        }
        HologramWorkspace.activate(player.getUniqueID(), session);
        session.openPurpose = "controllerStructure";
        HologramBuildPreparation.scan(session);
        HologramDraftPersistence.persist(session);
        HologramSnapshots.send(session);
    }

    static void openAir(EntityPlayerMP player) {
        HologramSession previous = HologramWorkspace.find(player.getUniqueID());
        if (previous != null && previous.player == player
            && !previous.cancelled
            && HologramAeCraftingOrders.active(player)
            && HologramSessionPolicy.jobValid(previous)) {
            previous.open = true;
            previous.settingsOnly = false;
            previous.openPurpose = "placementReview";
            HologramSnapshots.sendUi(previous);
            return;
        }
        if (openPlacementReview(previous, player)) return;
        if (previous != null && previous.player == player
            && !previous.cancelled
            && previous.job != 4
            && (previous.target != null || previous.virtual)
            && HologramSessionPolicy.tickValid(previous)) {
            previous.open = true;
            previous.settingsOnly = false;
            dismissPresetBrowse(previous);
            previous.openPurpose = "";
            HologramSnapshots.sendUi(previous);
            return;
        }
        HologramSession session = uiSession(player);
        session.open = true;
        session.settingsOnly = false;
        session.blueprintPreview = true;
        session.status = "右击真实控制器导入结构，或在蓝图库选择蓝图后点击放置";
        HologramWorkspace.activate(player.getUniqueID(), session);
        HologramSnapshots.sendUi(session);
    }

    /** Ordinary world use imports a constructable controller or opens the existing workspace. */
    static void openWorld(EntityPlayerMP player, int x, int y, int z, int side) {
        HologramSession previous = HologramWorkspace.find(player.getUniqueID());
        if (openPlacementReview(previous, player)) return;
        if (previous != null && previous.player == player
            && !previous.cancelled
            && HologramSessionPolicy.tickValid(previous)
            && (previous.job == 1 || previous.job == 2)) {
            openAir(player);
            return;
        }
        TileEntity tile = player.worldObj.getTileEntity(x, y, z);
        if (HologramStructureDiscovery.supports(tile)) open(player, x, y, z, side);
        else openAir(player);
    }

    /** The second ordinary world click opens review without importing the block beneath the ghost. */
    static boolean openPlacementReview(HologramSession s, EntityPlayerMP player) {
        if (s == null || s.player != player
            || s.cancelled
            || !HologramSessionPolicy.valid(s)
            || !s.virtual
            || !s.virtualPlacement
            || !s.virtualLocked
            || s.virtualPreset == null
            || s.job == 1
            || s.job == 2
            || s.job == 3
            || s.job == 5
            || s.job == 4) return false;
        if (HologramSessionPolicy.blockedByAreaImport(s)) {
            HologramSnapshots.sendUi(s);
            return true;
        }
        // Opening a review retains its complete plan and observed stock. The final submit checks the world.
        boolean cachedMaterials = !s.localPlanPending && HologramMaterialQuote.cached(s);
        s.blueprintAwaitingConfirmation = true;
        s.placementReviewIssued = cachedMaterials && !s.localPlanPending;
        s.forcePlacementReady = false;
        s.open = true;
        s.settingsOnly = false;
        s.openPurpose = "placementReview";
        s.placementReviewNonce++;
        s.status = "位置已锁定，请核对材料与 NBT 条目后确认施工";
        if (cachedMaterials) {
            try {
                HologramMaterialQuote.project(s);
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            HologramSnapshots.sendUi(s);
        } else {
            if (s.virtualPreset.schema < 2) {
                s.placementReviewIssued = true;
                HologramSnapshots.send(s);
                return true;
            }
            s.placementValidating = true;
            if (!s.localPlanPending) HologramDeferredPlanRefresh.request(s, java.util.Collections.emptySet());
            s.status = "正在核对放置位置与材料，完成后打开材料核算";
            HologramSnapshots.sendUi(s);
        }
        return true;
    }

    static HologramSession uiSession(EntityPlayerMP player) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s != null && s.player == player
            && HologramWorkspace.areaSessionOwned(s)
            && HologramAuthoringWorkflow.blueprintHasUnsavedChanges(s)) {
            s.cancelled = false;
            return s;
        }
        if (s == null || s.player != player
            || s.cancelled
            || !(HologramAeCraftingOrders.active(player) ? HologramSessionPolicy.jobValid(s)
                : HologramSessionPolicy.tickValid(s))) {
            if (s != null) HologramWorkspace.clearAreaSelection(s);
            s = HologramWorkspace.create(
                player,
                net.minecraft.util.MathHelper.floor_double(player.posX),
                net.minecraft.util.MathHelper.floor_double(player.posY),
                net.minecraft.util.MathHelper.floor_double(player.posZ),
                false,
                1);
            HologramWorkspace.activate(player.getUniqueID(), s);
        }
        return s;
    }

    static void openSettings(EntityPlayerMP player) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s != null && s.player == player
            && !s.cancelled
            && (s.job == 1 || s.job == 2 || HologramAeCraftingOrders.active(player))
            && HologramSessionPolicy.jobValid(s)) {
            openAir(player);
            return;
        }
        if (s == null || s.player != player
            || (s.cancelled || !HologramSessionPolicy.valid(s)) && !(HologramWorkspace.areaSessionOwned(s)
                && HologramAuthoringWorkflow.blueprintHasUnsavedChanges(s))) {
            s = HologramWorkspace.create(player, 0, 0, 0, false, 2);
            HologramWorkspace.activate(player.getUniqueID(), s);
        }
        s.cancelled = false;
        s.settingsOnly = false;
        dismissPresetBrowse(s);
        s.open = true;
        HologramSnapshots.sendUi(s);
    }

    static void cancel(EntityPlayerMP player) {
        HologramSession s = HologramWorkspace.find(player.getUniqueID());
        if (s == null || s.player != player || !HologramSessionPolicy.tickValid(s)) return;
        if (HologramSessionPolicy.blockedByAreaImport(s)) {
            HologramAreaWorkflow.cancelAreaImport(s);
            HologramSnapshots.send(s);
            return;
        }
        HologramArea area = HologramWorkspace.AREAS.get(player.getUniqueID());
        if (area != null) area.importing = false;
        s.job = 4;
        s.cancelled = true;
        libraryCloseUi(s);
        if (s.virtual) s.virtualLocked = true;
        HologramSession.cancelCaptureRetry(s);
        HologramSession.clearPending(s);
        s.status = "已取消未执行的施工；已施工世界保留";
        if (s.virtual) HologramPresetWorkflow.presetGuidance(s, "投影已取消，已施工方块保留；右击打开界面，可在蓝图库再次放置。");
        HologramSnapshots.send(s);
    }

    static void dismissPresetBrowse(HologramSession s) {
        if (s.presetBrowseForPlacement && s.virtualPreset == null) s.virtualPlacement = false;
        s.presetBrowseForPlacement = false;
        if (!s.virtualPlacement || !s.virtualLocked) {
            s.blueprintAwaitingConfirmation = false;
            s.placementReviewIssued = false;
        }
    }

    /** Closing the UI is a capability transition; consuming a snapshot's open intent is not. */
    static void libraryCloseUi(HologramSession s) {
        s.blueprintSaveReceipt = "";
        s.blueprintSaveJsonSha256 = s.blueprintSavePreviewSha256 = "";
        s.blueprintSavePrepared = null;
        s.blueprintSaveSelectedPresetId = "";
        s.libraryUiActive = false;
        s.libraryQuickPending = false;
        s.open = false;
    }
}
