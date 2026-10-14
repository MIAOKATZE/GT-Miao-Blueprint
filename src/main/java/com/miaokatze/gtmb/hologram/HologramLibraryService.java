package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

/** Server-thread adapter between bounded library transfers and the existing reviewed editor workflow. */
public final class HologramLibraryService {

    private HologramLibraryService() {}

    public static void init() {
        HologramLibraryFacade.setPrivateReceiver(HologramService::libraryActivate);
        HologramLibraryFacade.setDraftReceiver((player, request) -> {
            try {
                HologramService.libraryExportDraft(player, request);
            } catch (IOException | RuntimeException invalid) {
                HologramLibraryFacade.error(player, invalid.getMessage());
            }
        });
    }

    /** No callback from a completed transfer can recreate or reopen a closed/replaced session. */
    public static boolean sessionCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return HologramLibraryFacade.enabled() && connectedProjector(player)
            && HologramService.librarySessionCurrent(player, request);
    }

    static boolean saveCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return connectedProjector(player) && HologramService.librarySaveCurrent(player, request);
    }

    static boolean draftCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return !request.getString("saveReceipt")
            .isEmpty() ? saveCurrent(player, request) : sessionCurrent(player, request);
    }

    static boolean connectedProjector(EntityPlayerMP player) {
        return player != null && !player.isDead
            && player.worldObj != null
            && !player.worldObj.isRemote
            && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager != null
            && player.playerNetServerHandler.netManager.isChannelOpen()
            && com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem());
    }

    public static void quickBootstrap(EntityPlayerMP player, NBTTagCompound request) {
        if (!HologramLibraryFacade.enabled() || !connectedProjector(player)) return;
        try {
            HologramNetwork.sendLibraryReply(player, HologramService.libraryQuickBootstrap(player, request));
        } catch (IOException | RuntimeException invalid) {
            HologramLibraryFacade.error(player, invalid.getMessage());
        }
    }

    public static void quickCancel(EntityPlayerMP player, NBTTagCompound request) {
        if (HologramLibraryFacade.enabled() && request != null)
            HologramService.libraryQuickCancel(player, request.getString("quickToken"));
    }

    static String quickToken(String token) throws IOException {
        try {
            if (!UUID.fromString(token)
                .toString()
                .equals(token)) throw new IllegalArgumentException();
            return token;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid quick blueprint capability");
        }
    }

    static String publicId(String id) throws IOException {
        if (id == null || !id.startsWith("public_")) throw new IOException("Invalid server blueprint ID");
        try {
            if (!UUID.fromString(id.substring(7))
                .toString()
                .equals(id.substring(7))) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid server blueprint ID");
        }
    }

    static String localId(String id, boolean allowEmpty) throws IOException {
        if (id == null || id.isEmpty()) return allowEmpty ? ""
            : "local_" + UUID.randomUUID()
                .toString();
        if (!id.startsWith("local_")) throw new IOException("Invalid private blueprint file ID");
        try {
            if (!UUID.fromString(id.substring(6))
                .toString()
                .equals(id.substring(6))) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid private blueprint file ID");
        }
        return id;
    }

    /** Author is descriptive provenance; public deletion authority always belongs to the verified publisher. */
    static void stampAuthor(HologramPresets.Preset preset, EntityPlayerMP player, boolean newCreation) {
        if (newCreation || preset.authorUuid == null || preset.authorUuid.isEmpty()) {
            preset.authorUuid = player.getUniqueID()
                .toString();
            preset.authorName = player.getCommandSenderName();
            preset.createdAt = System.currentTimeMillis();
        }
    }
}
