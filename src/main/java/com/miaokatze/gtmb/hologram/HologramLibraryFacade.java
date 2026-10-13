package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.UserListOpsEntry;

import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Common-side bridge. Client-visible flags never replace the actual dedicated-server check. */
public final class HologramLibraryFacade {

    private static volatile HologramServerLibrary library;
    private static boolean initialized;
    private static final ArrayBlockingQueue<Inbound> inbound = new ArrayBlockingQueue<>(64);
    private static final ConcurrentHashMap<UUID, AtomicInteger> pending = new ConcurrentHashMap<>();
    private static volatile PrivateReceiver privateReceiver;
    private static volatile BiConsumer<EntityPlayerMP, NBTTagCompound> draftReceiver;

    private HologramLibraryFacade() {}

    @FunctionalInterface
    public interface PrivateReceiver {

        String accept(EntityPlayerMP player, NBTTagCompound request, HologramPresets.Preset preset) throws IOException;
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        HologramLibraryService.init();
        FMLCommonHandler.instance()
            .bus()
            .register(new Events());
    }

    public static boolean enabled() {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.isDedicatedServer();
    }

    public static boolean isDedicated() {
        return enabled();
    }

    public static UUID actorId(EntityPlayerMP player) {
        if (player == null || player.getGameProfile() == null
            || player.getGameProfile()
                .getId() == null)
            throw new IllegalArgumentException("Verified server player profile required");
        return player.getGameProfile()
            .getId();
    }

    public static boolean isAdmin(EntityPlayerMP player) {
        if (!enabled() || player == null
            || MinecraftServer.getServer()
                .getConfigurationManager() == null)
            return false;
        UserListOpsEntry entry = (UserListOpsEntry) MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152603_m()
            .func_152683_b(player.getGameProfile());
        return entry != null && entry.func_152644_a() >= 4;
    }

    public static HologramPresets.Preset get(EntityPlayerMP player, String id) throws IOException {
        if (!enabled()) return HologramPresets.get(id);
        if (id == null) return null;
        if (id.startsWith("private_")) return HologramService.libraryPrivatePreset(player, id);
        if (!id.startsWith("public_") || library == null) return null;
        return library.get(id);
    }

    public static void setPrivateReceiver(PrivateReceiver receiver) {
        privateReceiver = receiver;
    }

    public static void setDraftReceiver(BiConsumer<EntityPlayerMP, NBTTagCompound> receiver) {
        draftReceiver = receiver;
    }

    /** Netty ingress is capped before anything is posted to the main-thread queue. */
    static void receive(EntityPlayerMP player, NBTTagCompound request) {
        if (!enabled()) return;
        synchronized (pending) {
            UUID owner = actorId(player);
            AtomicInteger count = pending.get(owner);
            if (count == null) {
                if (pending.size() >= 64) {
                    error(player, "Server library request queue is busy; retry later");
                    return;
                }
                count = new AtomicInteger();
                pending.put(owner, count);
            }
            if (count.incrementAndGet() > 4) {
                count.decrementAndGet();
                return;
            }
            if (!inbound.offer(new Inbound(player, (NBTTagCompound) request.copy(), count))) {
                if (count.decrementAndGet() == 0) pending.remove(owner, count);
                error(player, "Server library request queue is busy; retry later");
            }
        }
    }

    static void importPrivate(EntityPlayerMP player, NBTTagCompound request, HologramPresets.Preset preset) {
        ServerTaskScheduler.scheduleServerTask(() -> {
            PrivateReceiver receiver = privateReceiver;
            if (enabled() && receiver != null && HologramLibraryService.sessionCurrent(player, request)) {
                try {
                    String id = receiver.accept(player, request, preset);
                    NBTTagCompound result = HologramLibraryProtocol.reply("privateImported");
                    result.setString("id", id == null ? "" : id);
                    result.setString("session", request.getString("session"));
                    result.setString("transfer", request.getString("transfer"));
                    HologramNetwork.sendLibraryReply(player, result);
                } catch (IOException | RuntimeException failure) {
                    error(player, failure.getMessage());
                }
            } else error(player, "Blueprint session changed; reopen and retry");
        });
    }

    static void exportDraft(EntityPlayerMP player, NBTTagCompound request) {
        ServerTaskScheduler.scheduleServerTask(() -> {
            BiConsumer<EntityPlayerMP, NBTTagCompound> receiver = draftReceiver;
            if (enabled() && receiver != null && HologramLibraryService.sessionCurrent(player, request))
                receiver.accept(player, request);
            else error(player, "Blueprint session changed; reopen and retry");
        });
    }

    public static void downloadDraft(EntityPlayerMP player, HologramPresets.Preset preset, String session,
        long revision, String replaceLocalId) throws IOException {
        NBTTagCompound request = HologramLibraryProtocol.request("exportDraft");
        request.setString("session", session);
        request.setLong("planRevision", revision);
        request.setString("replaceLocalId", replaceLocalId == null ? "" : replaceLocalId);
        HologramServerLibrary current = library;
        if (!enabled() || current == null) throw new IOException("Server library is still loading");
        current.prepareDraft(player, request, preset);
    }

    public static boolean consumeDownloadAllowance(UUID player, int bytes) {
        HologramServerLibrary current = library;
        return !enabled() || current != null && current.consumeDownload(player, bytes);
    }

    public static boolean downloadWaiting(UUID player) {
        HologramServerLibrary current = library;
        return current != null && current.downloadWaiting(player);
    }

    public static void cancelDownloadAllowance(UUID player) {
        HologramServerLibrary current = library;
        if (current != null) current.cancelDownload(player);
    }

    static HologramServerLibrary backend() {
        return library;
    }

    public static void error(EntityPlayerMP player, String message) {
        NBTTagCompound tag = HologramLibraryProtocol.reply("error");
        tag.setString("message", message);
        HologramNetwork.sendLibraryReply(player, tag);
    }

    public static synchronized void stop() {
        HologramServerLibrary previous = library;
        library = null;
        inbound.clear();
        pending.clear();
        if (previous != null) previous.stop();
    }

    public static final class Events {

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || !enabled()) return;
            HologramServerLibrary current = library;
            if (current == null) {
                current = new HologramServerLibrary(
                    Paths.get("config", "gtmb", "server-blueprints"),
                    Paths.get("config", "gtmb", "server-blueprint-library.cfg"));
                library = current;
            }
            current.heartbeat();
            for (int i = 0; i < 64; i++) {
                Inbound next = inbound.poll();
                if (next == null) break;
                synchronized (pending) {
                    if (next.count.decrementAndGet() == 0) pending.remove(actorId(next.player), next.count);
                }
                String op = next.request.getString("op");
                if ("quickBootstrap".equals(op) || "quickCancel".equals(op)) {
                    if ("quickBootstrap".equals(op)) HologramLibraryService.quickBootstrap(next.player, next.request);
                    else HologramLibraryService.quickCancel(next.player, next.request);
                    continue;
                }
                if (("privateBegin".equals(op) || "exportDraft".equals(op))
                    && !HologramLibraryService.sessionCurrent(next.player, next.request)) {
                    error(next.player, "Blueprint session changed; reopen and retry");
                    continue;
                }
                current.submit(next.player, next.request, isAdmin(next.player));
            }
        }
    }

    private static final class Inbound {

        final EntityPlayerMP player;
        final NBTTagCompound request;
        final AtomicInteger count;

        Inbound(EntityPlayerMP player, NBTTagCompound request, AtomicInteger count) {
            this.player = player;
            this.request = request;
            this.count = count;
        }
    }
}
