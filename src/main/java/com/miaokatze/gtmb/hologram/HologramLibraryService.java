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
                HologramLibraryFacade.error(player, invalid.getMessage(), request.getLong("clientTaskToken"));
            }
        });
    }

    /** No callback from a completed transfer can recreate or reopen a closed/replaced session. */
    public static boolean sessionCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return (HologramLibraryFacade.enabled() || request != null && ("privateBegin".equals(request.getString("op"))
            || "publicPrepare".equals(request.getString("op")) && !request.getString("integratedPresetId")
                .isEmpty()))
            && connectedProjector(player)
            && HologramService.librarySessionCurrent(player, request);
    }

    /** Content preparation is owner/connection bound and deliberately has no workspace or placement admission. */
    static boolean contentCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return request != null && request.getLong("clientTaskToken") > 0 && connectedProjector(player);
    }

    static boolean saveCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return connected(player) && HologramService.librarySaveCurrent(player, request);
    }

    static boolean draftCurrent(EntityPlayerMP player, NBTTagCompound request) {
        return !request.getString("saveReceipt")
            .isEmpty() ? saveCurrent(player, request) : sessionCurrent(player, request);
    }

    static boolean connectedProjector(EntityPlayerMP player) {
        return connected(player)
            && com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(player.getHeldItem());
    }

    private static boolean connected(EntityPlayerMP player) {
        return player != null && !player.isDead
            && player.worldObj != null
            && !player.worldObj.isRemote
            && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager != null
            && player.playerNetServerHandler.netManager.isChannelOpen();
    }

    public static void quickBootstrap(EntityPlayerMP player, NBTTagCompound request) {
        if (!connectedProjector(player)) return;
        try {
            HologramNetwork.sendLibraryReply(player, HologramService.libraryQuickBootstrap(player, request));
        } catch (IOException | RuntimeException invalid) {
            HologramLibraryFacade.error(player, invalid.getMessage(), request.getLong("clientTaskToken"));
        }
    }

    public static void quickCancel(EntityPlayerMP player, NBTTagCompound request) {
        if (request != null) HologramService.libraryQuickCancel(player, request.getString("quickToken"));
    }

    static void quickFallback(EntityPlayerMP player, NBTTagCompound request) {
        try {
            if (!sessionCurrent(player, request)) throw new IOException("Quick blueprint capability changed");
            HologramSession previous = HologramWorkspace.find(player.getUniqueID());
            if (previous.libraryQuickClientTaskToken <= 0
                || previous.libraryQuickClientTaskToken != request.getLong("clientTaskToken"))
                throw new IOException("Quick blueprint task changed");
            String sourceSession = previous.id;
            HologramWorldPlacementWorkflow.quickPlaceLastBlueprint(player, request);
            HologramSession placed = HologramWorkspace.find(player.getUniqueID());
            if (placed == previous || placed == null || !placed.virtualPlacement || placed.virtualPreset == null)
                throw new IOException(previous.status);
            placed.libraryQuickToken = request.getString("quickToken");
            placed.libraryQuickClientTaskToken = request.getLong("clientTaskToken");
            HologramWorldPlacementWorkflow.lockVirtual(player, placed.x, placed.y, placed.z, placed.side);
            if (!placed.virtualLocked) throw new IOException(placed.status);
            NBTTagCompound reply = HologramLibraryProtocol.reply("quickPlacement");
            reply.setString("quickToken", placed.libraryQuickToken);
            reply.setLong("clientTaskToken", placed.libraryQuickClientTaskToken);
            reply.setString("previousSession", sourceSession);
            reply.setString("session", placed.id);
            HologramNetwork.sendLibraryReply(player, reply);
        } catch (IOException | RuntimeException invalid) {
            HologramLibraryFacade.error(player, invalid.getMessage(), request.getLong("clientTaskToken"));
        }
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
