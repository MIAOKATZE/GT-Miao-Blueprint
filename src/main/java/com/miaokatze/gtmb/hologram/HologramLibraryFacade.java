package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    private static volatile Path clientGameDirectory;
    private static boolean initialized;
    private static final ArrayBlockingQueue<Inbound> inbound = new ArrayBlockingQueue<>(64);
    private static final ConcurrentHashMap<UUID, AtomicInteger> pending = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, SavedResult> savedResults = new ConcurrentHashMap<>();
    private static volatile PrivateReceiver privateReceiver;
    private static volatile BiConsumer<EntityPlayerMP, NBTTagCompound> draftReceiver;

    private HologramLibraryFacade() {}

    /** Set by the client proxy before an integrated server or a library worker can load files. */
    public static void configureClientGameDirectory(Path gameDirectory) {
        if (gameDirectory == null) throw new IllegalArgumentException("Client game directory required");
        clientGameDirectory = gameDirectory.toAbsolutePath()
            .normalize();
        HologramPresets.clearCache();
    }

    static Path blueprintDirectory() {
        return blueprintDirectory(clientGameDirectory, isDedicated());
    }

    static Path blueprintDirectory(Path clientDirectory, boolean dedicated) {
        return (dedicated || clientDirectory == null ? Paths.get("") : clientDirectory).resolve("MiaoBlueprints")
            .toAbsolutePath()
            .normalize();
    }

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

    /** Client files obey the same raw version/type gate as server uploads. */
    public static void validateNativeJson(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > HologramBlueprintSharing.MAX_BYTES)
            throw new IOException("Invalid native blueprint byte budget");
        HologramBlueprintSharing.decode(new String(bytes, StandardCharsets.UTF_8));
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
        String operation = request.getString("op");
        if (!enabled() && !("previewBegin".equals(operation) || "previewChunk".equals(operation)
            || "cancel".equals(operation)
            || "saveStatus".equals(operation)
            || "saveResultConsumed".equals(operation))) return;
        synchronized (pending) {
            UUID owner = actorId(player);
            AtomicInteger count = pending.get(owner);
            if (count == null) {
                if (pending.size() >= 64) {
                    HologramBusyReplies.reject(player, request, HologramBusyReplies.Kind.LIBRARY);
                    return;
                }
                count = new AtomicInteger();
                pending.put(owner, count);
            }
            if (count.incrementAndGet() > 4) {
                count.decrementAndGet();
                HologramBusyReplies.reject(player, request, HologramBusyReplies.Kind.LIBRARY);
                return;
            }
            if (!inbound.offer(new Inbound(player, (NBTTagCompound) request.copy(), count))) {
                if (count.decrementAndGet() == 0) pending.remove(owner, count);
                HologramBusyReplies.reject(player, request, HologramBusyReplies.Kind.LIBRARY);
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

    static void draftPrepared(EntityPlayerMP player, NBTTagCompound request, byte[] json, String name) {
        ServerTaskScheduler.scheduleServerTask(() -> {
            try {
                if (!HologramLibraryService.draftCurrent(player, request))
                    throw new IOException("Blueprint save source changed; retry");
                String jsonHash = HologramBlueprintArtifact.hash(json);
                if (!request.getString("saveReceipt")
                    .isEmpty() && !HologramService.libraryDraftPrepared(player, request, jsonHash))
                    throw new IOException("Blueprint save receipt changed; retry");
                HologramServerLibrary current = library;
                if (current == null) throw new IOException("Blueprint library stopped");
                if (!request.getString("saveReceipt")
                    .isEmpty()) {
                    NBTTagCompound proof = (NBTTagCompound) request.copy();
                    proof.setString("localId", request.getString("replaceLocalId"));
                    proof.setString("jsonSha256", jsonHash);
                    proof.setString("previewSha256", "");
                    proof.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
                    proof.setBoolean("localSaveReceipt", true);
                    rememberSavedResult(player, proof);
                }
                // Publish the result capability before the worker can advertise the download.
                current.draft(player, request, json, name);
            } catch (IOException | RuntimeException failure) {
                draftPreparationFailed(player, request, failure.getMessage());
            }
        });
    }

    /** Failed encoding/download admission retires only the still-current issued owner capability. */
    static void draftPreparationFailed(EntityPlayerMP player, NBTTagCompound request, String message) {
        NBTTagCompound proof = (NBTTagCompound) request.copy();
        ServerTaskScheduler.scheduleServerTask(() -> {
            if (!HologramLibraryService.draftCurrent(player, proof)) return;
            if (!proof.getString("saveReceipt")
                .isEmpty()) HologramService.librarySaveCancelled(player, proof);
            error(player, message);
        });
    }

    static void verifySavedPreview(EntityPlayerMP player, NBTTagCompound request,
        HologramBlueprintArtifact.Artifact artifact) {
        ServerTaskScheduler.scheduleServerTask(() -> {
            try {
                if (!HologramLibraryService.saveCurrent(player, request) || !HologramService.libraryPreviewVerified(
                    player,
                    request,
                    HologramBlueprintArtifact.hash(artifact.json),
                    HologramBlueprintArtifact.hash(artifact.png)))
                    throw new IOException("Blueprint preview source or receipt changed");
                NBTTagCompound verified = HologramLibraryProtocol.reply("previewVerified");
                verified.setString("transfer", request.getString("transfer"));
                verified.setString("saveReceipt", request.getString("saveReceipt"));
                verified.setString("jsonSha256", HologramBlueprintArtifact.hash(artifact.json));
                verified.setString("previewSha256", HologramBlueprintArtifact.hash(artifact.png));
                verified.setString("session", request.getString("session"));
                verified.setLong("planRevision", request.getLong("planRevision"));
                verified.setString("localId", request.getString("replaceLocalId"));
                verified.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
                rememberSavedResult(player, verified);
                HologramNetwork.sendLibraryReply(player, verified);
            } catch (IOException | RuntimeException failure) {
                error(player, failure.getMessage());
            }
        });
    }

    /** One owner-bound result per peer resolves cancel/ACK races without retaining preview payloads. */
    private static void rememberSavedResult(EntityPlayerMP player, NBTTagCompound verified) throws IOException {
        pruneSavedResults();
        UUID owner = actorId(player);
        if (!savedResults.containsKey(owner) && savedResults.size() >= 64)
            throw new IOException("Blueprint save result queue is busy");
        SavedResult next = new SavedResult(player, verified), previous = savedResults.put(owner, next);
        if (previous != null && "pending".equals(previous.outcome) && !previous.matches(player, verified)) {
            previous.outcome = "saveRejected";
            previous.finishedAtNanos = System.nanoTime();
            previous.message = "Blueprint save was replaced before its disk ACK";
            previous.send();
        }
    }

    /** The server already owns the JSON digest; private PNG bytes remain on their owner's client. */
    static boolean localSaveWritten(EntityPlayerMP player, NBTTagCompound acknowledgement) {
        SavedResult result = savedResults.get(actorId(player));
        return result != null && "pending".equals(result.outcome)
            && result.matches(player, acknowledgement)
            && result.bindLocalPreview(acknowledgement);
    }

    static void savedResult(EntityPlayerMP player, NBTTagCompound acknowledgement, boolean committed, String message) {
        SavedResult result = savedResults.get(actorId(player));
        if (result == null || !result.matches(player, acknowledgement)) return;
        if (!"pending".equals(result.outcome)) {
            result.send();
            return;
        }
        result.outcome = committed ? "saveCommitted" : "saveRejected";
        result.finishedAtNanos = System.nanoTime();
        result.message = message == null ? "" : message;
        result.send();
    }

    static void resolveSavedResult(EntityPlayerMP player, NBTTagCompound request, boolean cancel) {
        // Preview or IO can fail before the verified-byte result exists. Still revoke the exact owner receipt.
        if (cancel) HologramService.librarySaveCancelled(player, request);
        SavedResult result = savedResults.get(actorId(player));
        if (result == null || !result.matches(player, request)) return;
        result.bindLocalPreview(request);
        if ("pending".equals(result.outcome)) {
            if (!HologramLibraryService.saveCurrent(player, request)) {
                result.outcome = "saveRejected";
                result.finishedAtNanos = System.nanoTime();
                result.message = "Blueprint save source was cancelled or replaced";
            }
        }
        if (!"pending".equals(result.outcome)) result.send();
    }

    private static final class SavedResult {

        final EntityPlayerMP actor;
        final NBTTagCompound proof;
        String outcome = "pending", message = "";
        long finishedAtNanos;

        SavedResult(EntityPlayerMP actor, NBTTagCompound proof) {
            this.actor = actor;
            this.proof = (NBTTagCompound) proof.copy();
        }

        boolean matches(EntityPlayerMP player, NBTTagCompound request) {
            String localId = request.hasKey("localId", 8) ? request.getString("localId")
                : request.getString("replaceLocalId");
            return actor == player && proof.getString("saveReceipt")
                .equals(request.getString("saveReceipt"))
                && request.hasKey("planRevision", 4)
                && proof.getString("session")
                    .equals(request.getString("session"))
                && proof.getLong("planRevision") == request.getLong("planRevision")
                && proof.getString("localId")
                    .equals(localId)
                && (!request.hasKey("jsonSha256", 8) || proof.getString("jsonSha256")
                    .equals(request.getString("jsonSha256")))
                && (!request.hasKey("previewSha256", 8) || proof.getString("previewSha256")
                    .equals(request.getString("previewSha256"))
                    || proof.getBoolean("localSaveReceipt") && proof.getString("previewSha256")
                        .isEmpty());
        }

        boolean bindLocalPreview(NBTTagCompound request) {
            if (!proof.getBoolean("localSaveReceipt")
                || request.getInteger("artifactVersion") != HologramBlueprintArtifact.VERSION
                || !request.getString("previewSha256")
                    .matches("[a-f0-9]{64}"))
                return false;
            String previous = proof.getString("previewSha256");
            if (!previous.isEmpty() && !previous.equals(request.getString("previewSha256"))) return false;
            proof.setString("previewSha256", request.getString("previewSha256"));
            return true;
        }

        void send() {
            NBTTagCompound reply = (NBTTagCompound) proof.copy();
            reply.setString("op", outcome);
            reply.setString("message", message.substring(0, Math.min(256, message.length())));
            HologramNetwork.sendLibraryReply(actor, reply);
        }
    }

    private static void pruneSavedResults() {
        long now = System.nanoTime();
        for (java.util.Map.Entry<UUID, SavedResult> entry : savedResults.entrySet()) {
            SavedResult result = entry.getValue();
            boolean connected = result.actor.playerNetServerHandler != null
                && result.actor.playerNetServerHandler.netManager != null
                && result.actor.playerNetServerHandler.netManager.isChannelOpen();
            if (!connected || !"pending".equals(result.outcome)
                && now - result.finishedAtNanos > java.util.concurrent.TimeUnit.MINUTES.toNanos(10)) {
                savedResults.remove(entry.getKey(), result);
            } else if ("pending".equals(result.outcome)
                && !HologramLibraryService.saveCurrent(result.actor, result.proof)) {
                    result.outcome = "saveRejected";
                    result.finishedAtNanos = now;
                    result.message = "Blueprint source changed before its disk ACK";
                    result.send();
                }
        }
    }

    private static void consumeSavedResult(EntityPlayerMP player, NBTTagCompound request) {
        UUID owner = actorId(player);
        SavedResult result = savedResults.get(owner);
        if (result != null && !"pending".equals(result.outcome) && result.matches(player, request))
            savedResults.remove(owner, result);
    }

    public static void downloadDraft(EntityPlayerMP player, HologramPresets.Preset preset, String session,
        long revision, String replaceLocalId) throws IOException {
        downloadDraft(player, preset, session, revision, replaceLocalId, "");
    }

    public static void downloadDraft(EntityPlayerMP player, HologramPresets.Preset preset, String session,
        long revision, String replaceLocalId, String saveReceipt) throws IOException {
        NBTTagCompound request = HologramLibraryProtocol.request("exportDraft");
        request.setString("session", session);
        request.setLong("planRevision", revision);
        request.setString("replaceLocalId", replaceLocalId == null ? "" : replaceLocalId);
        request.setString("saveReceipt", saveReceipt == null ? "" : saveReceipt);
        HologramServerLibrary current = library;
        if (current == null) throw new IOException("Server library is still loading");
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
        savedResults.clear();
        if (previous != null) previous.stop();
    }

    public static final class Events {

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            MinecraftServer server = MinecraftServer.getServer();
            if (event.phase != TickEvent.Phase.END || server == null) return;
            pruneSavedResults();
            HologramServerLibrary current = library;
            if (current == null) {
                current = new HologramServerLibrary(
                    blueprintDirectory(),
                    blueprintDirectory().getParent()
                        .resolve("config/gtmb/server-blueprint-library.cfg"));
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
                if ("saveResultConsumed".equals(op)) {
                    consumeSavedResult(next.player, next.request);
                    continue;
                }
                if (("cancel".equals(op) || "saveStatus".equals(op)) && !next.request.getString("saveReceipt")
                    .isEmpty()) {
                    resolveSavedResult(next.player, next.request, "cancel".equals(op));
                    if ("saveStatus".equals(op)) continue;
                }
                if ("quickBootstrap".equals(op) || "quickCancel".equals(op)) {
                    if ("quickBootstrap".equals(op)) HologramLibraryService.quickBootstrap(next.player, next.request);
                    else HologramLibraryService.quickCancel(next.player, next.request);
                    continue;
                }
                if (("privateBegin".equals(op) || "exportDraft".equals(op))
                    && !HologramLibraryService.sessionCurrent(next.player, next.request)) {
                    error(next.player, "蓝图会话已变更，请重新打开后重试");
                    continue;
                }
                if ("previewBegin".equals(op) && !HologramLibraryService.saveCurrent(next.player, next.request)) {
                    error(next.player, "蓝图保存来源已变更，请重新保存");
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
