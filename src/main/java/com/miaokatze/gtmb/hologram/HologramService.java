package com.miaokatze.gtmb.hologram;

import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Stable protocol facade. Domain workflows own state, policy, planning, persistence and transactions. */
public final class HologramService {

    private HologramService() {}

    public static void open(EntityPlayerMP player, int x, int y, int z, int side) {
        HologramProjectorWorkflow.open(player, x, y, z, side);
    }

    public static void openAir(EntityPlayerMP player) {
        HologramProjectorWorkflow.openAir(player);
    }

    public static void openWorld(EntityPlayerMP player, int x, int y, int z, int side) {
        HologramProjectorWorkflow.openWorld(player, x, y, z, side);
    }

    public static boolean isAreaSelecting(EntityPlayer player, ItemStack stack) {
        return HologramWorkspace.isAreaSelecting(player, stack);
    }

    public static void toggleAreaSelection(EntityPlayerMP player) {
        HologramAreaWorkflow.toggleAreaSelection(player);
    }

    public static void beginAreaSelection(EntityPlayerMP player) {
        HologramAreaWorkflow.beginAreaSelection(player);
    }

    public static void finishAreaSelection(EntityPlayerMP player, NBTTagCompound request) {
        HologramAreaWorkflow.finishAreaSelection(player, request);
    }

    public static void cancelAreaSelection(EntityPlayerMP player, String session) {
        HologramAreaWorkflow.cancelAreaSelection(player, session);
    }

    public static void worldAreaPoint(EntityPlayerMP player, NBTTagCompound request) {
        HologramAreaWorkflow.worldAreaPoint(player, request);
    }

    public static void undoAreaSelection(EntityPlayerMP player, NBTTagCompound request) {
        HologramAreaWorkflow.undoAreaSelection(player, request);
    }

    public static void areaPoint(EntityPlayerMP player, int point, int x, int y, int z, boolean atPlayer) {
        HologramAreaWorkflow.areaPoint(player, point, x, y, z, atPlayer);
    }

    public static void clear() {
        HologramWorkspace.clear();
    }

    public static void openSettings(EntityPlayerMP player) {
        HologramProjectorWorkflow.openSettings(player);
    }

    public static void openVirtual(EntityPlayerMP player, int x, int y, int z, int side, String presetId) {
        HologramWorldPlacementWorkflow.openVirtual(player, x, y, z, side, presetId);
    }

    public static void quickPlaceLastBlueprint(EntityPlayerMP player, NBTTagCompound request) {
        HologramWorldPlacementWorkflow.quickPlaceLastBlueprint(player, request);
    }

    public static void lockVirtual(EntityPlayerMP player, int x, int y, int z, int side) {
        HologramWorldPlacementWorkflow.lockVirtual(player, x, y, z, side);
    }

    public static void confirmWorldPosition(EntityPlayerMP player, NBTTagCompound request) {
        HologramWorldPlacementWorkflow.confirmWorldPosition(player, request);
    }

    public static void unlockWorldPosition(EntityPlayerMP player, NBTTagCompound request) {
        HologramWorldPlacementWorkflow.unlockWorldPosition(player, request);
    }

    public static void nudgeWorldPosition(EntityPlayerMP player, NBTTagCompound request) {
        HologramWorldPlacementWorkflow.nudgeWorldPosition(player, request);
    }

    public static void cancelWorldPlacement(EntityPlayerMP player, NBTTagCompound request) {
        HologramWorldPlacementWorkflow.cancelWorldPlacement(player, request);
    }

    public static void immediateVirtual(EntityPlayerMP player, int x, int y, int z, int side, String presetId) {
        HologramWorldPlacementWorkflow.immediateVirtual(player, x, y, z, side, presetId);
    }

    public static void cancel(EntityPlayerMP player) {
        HologramProjectorWorkflow.cancel(player);
    }

    public static void handle(EntityPlayerMP player, NBTTagCompound action) {
        HologramActionDispatcher.handle(player, action);
    }

    public static void tick() {
        HologramWorkspaceScheduler.tick();
    }

    static void calibrateMaterialChannels(HologramCapture capture, NBTTagList rows, NBTTagCompound channels,
        Map<Integer, ItemStack> pins, Map<String, ItemStack> coordinatePins) {
        HologramControllerCapture.calibrateMaterialChannels(capture, rows, channels, pins, coordinatePins);
    }

    static void updateDraftSettings(NBTTagCompound draft, HologramMaterials.Settings settings, boolean downgrade) {
        HologramDraftPersistence.updateDraftSettings(draft, settings, downgrade);
    }

    static boolean librarySessionCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return HologramLibraryWorkflow.librarySessionCurrent(player, request);
    }

    static NBTTagCompound libraryQuickBootstrap(EntityPlayerMP player, NBTTagCompound request)
        throws java.io.IOException {
        return HologramLibraryWorkflow.libraryQuickBootstrap(player, request);
    }

    static void libraryQuickCancel(EntityPlayerMP player, String token) {
        HologramLibraryWorkflow.libraryQuickCancel(player, token);
    }

    static HologramPresets.Preset libraryPrivatePreset(EntityPlayerMP player, String id) {
        return HologramLibraryWorkflow.libraryPrivatePreset(player, id);
    }

    static void libraryActivate(EntityPlayerMP player, NBTTagCompound request, HologramPresets.Preset uploaded,
        HologramLibraryFacade.ActivationCompletion completion) throws java.io.IOException {
        HologramLibraryWorkflow.libraryActivate(player, request, uploaded, completion);
    }

    static void libraryExportDraft(EntityPlayerMP player, NBTTagCompound request) throws java.io.IOException {
        HologramLibraryWorkflow.libraryExportDraft(player, request);
    }

    static NBTTagCompound sealedActorTile(NBTTagCompound stored, java.util.UUID actor, String actorName, int x, int y,
        int z) throws java.io.IOException {
        return HologramCellPolicy.sealedActorTile(stored, actor, actorName, x, y, z);
    }

    static boolean librarySaveCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return HologramLibraryWorkflow.librarySaveCurrent(player, request);
    }

    static boolean libraryDraftPrepared(EntityPlayerMP player, NBTTagCompound request, String hash) {
        return HologramLibraryWorkflow.libraryDraftPrepared(player, request, hash);
    }

    static boolean libraryPreviewVerified(EntityPlayerMP player, NBTTagCompound request, String jsonHash,
        String previewHash) {
        return HologramLibraryWorkflow.libraryPreviewVerified(player, request, jsonHash, previewHash);
    }

    static void librarySaveCancelled(EntityPlayerMP player, NBTTagCompound request) {
        HologramLibraryWorkflow.librarySaveCancelled(player, request);
    }
}
