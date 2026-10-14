package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramNetwork;

/** One bounded disk worker and acknowledged 1 KiB chunks; no JSON or file IO runs on the render/tick thread. */
final class HologramLibraryClient {

    static final HologramLibraryClient INSTANCE = new HologramLibraryClient();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
        1,
        1,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(2),
        runnable -> {
            Thread thread = new Thread(runnable, "GTMB client blueprints");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
    private HologramClientLibrary store;
    private NBTTagList local = new NBTTagList(), server = new NBTTagList();
    private NBTTagCompound config = new NBTTagCompound();
    private String selectedLocal = "", selectedServer = "", status = "", connection = "";
    private String outgoingId = "", outgoingOp = "", outgoingLocal = "", incomingId = "", incomingHash = "";
    private String incomingPurpose = "", incomingLocal = "", actorName = "", actorUuid = "";
    private String incomingSaveReceipt = "", incomingSaveSession = "";
    private long incomingSaveRevision;
    private final SaveProgress saveProgress = new SaveProgress();
    private byte[] outgoing;
    private int nextChunk, sentBytes, incomingTotal, incomingIndex, page, pages = 1;
    private boolean ready, inFlight, loading, preparing;
    private ByteArrayOutputStream incoming;
    private volatile long generation;
    private long lastActivity;
    private Object libraryWorld, quickTool;
    private String quickToolId = "";
    private int quickSlot;
    private final HologramLibraryQuickState quick = new HologramLibraryQuickState();
    private String quickLocal = "", quickSession = "", observedPublic = "";
    private byte[] quickBytes;
    private String quickHash = "";
    private boolean outgoingQuick, downloadExpected, configWritePending, draftPending;
    private boolean incomingCapturePreview;
    private NBTTagCompound pendingSaveAck;
    private HologramBlueprintPreview.Request previewCapture;
    private Runnable pendingPreviewStart;
    private UUID libraryActor;
    private PendingDiskSave pendingDiskSave;
    private volatile NBTTagCompound saveWitness = new NBTTagCompound();
    private long lastSaveStatus;
    private NBTTagList saveTransitions = new NBTTagList();
    private NBTTagCompound lastSaveTransition = new NBTTagCompound();
    private int saveTraceEvents, downloadProgressMarker, uploadProgressMarker;

    /** Waiting for an exact source is bounded independently of byte-progressing transfers. */
    static final class SaveProgress {

        private long waitingSince, progressedAt;

        void begin(long now, boolean waiting) {
            waitingSince = waiting ? now : 0;
            progressedAt = now;
        }

        void sourceReady(long now) {
            if (waitingSince != 0 && !waitingExpired(now)) {
                waitingSince = 0;
                progressedAt = now;
            }
        }

        void progress(long now) {
            progressedAt = now;
        }

        boolean waitingExpired(long now) {
            return waitingSince != 0 && now - waitingSince > TimeUnit.SECONDS.toNanos(120);
        }

        boolean idleExpired(long now) {
            return progressedAt != 0 && now - progressedAt > TimeUnit.SECONDS.toNanos(120);
        }

        void clear() {
            waitingSince = progressedAt = 0;
        }
    }

    static NBTTagCompound actualSaveState() {
        NBTTagCompound observed = INSTANCE.saveState();
        observed.setTag("lastTransition", INSTANCE.lastSaveTransition.copy());
        observed.setTag("transitions", INSTANCE.saveTransitions.copy());
        return observed;
    }

    private NBTTagCompound saveState() {
        Minecraft mc = Minecraft.getMinecraft();
        HologramState current = HologramClient.state;
        long now = System.nanoTime();
        NBTTagCompound observed = new NBTTagCompound();
        observed.setLong("observedAtNanos", now);
        observed.setLong("generation", generation);
        observed.setString("expectedSession", incomingSaveSession);
        observed.setLong("expectedRevision", incomingSaveRevision);
        observed.setString("currentSession", current == null ? "" : current.session);
        observed.setLong("currentRevision", current == null ? -1 : current.data.getLong("planRevision"));
        observed.setString(
            "saveReceiptSha256",
            HologramBlueprintArtifact.hash(incomingSaveReceipt.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        observed.setBoolean("sameWorld", mc.theWorld != null && mc.theWorld == libraryWorld);
        observed.setBoolean(
            "sameActor",
            mc.thePlayer != null && mc.thePlayer.getUniqueID()
                .equals(libraryActor));
        observed.setString(
            "screen",
            mc.currentScreen == null ? ""
                : mc.currentScreen.getClass()
                    .getName());
        observed.setString("status", status);
        observed.setBoolean("incoming", incoming != null);
        observed.setInteger("incomingBytes", incoming == null ? 0 : incoming.size());
        observed.setInteger("incomingTotal", incomingTotal);
        observed.setInteger("incomingIndex", incomingIndex);
        observed.setString("incomingSha256", incomingHash);
        observed.setBoolean("capturePreview", incomingCapturePreview);
        observed.setBoolean("downloadExpected", downloadExpected);
        observed.setBoolean("draftPending", draftPending);
        observed.setBoolean("preparing", preparing);
        observed.setBoolean("pendingPreviewStart", pendingPreviewStart != null);
        observed.setBoolean("previewCapture", previewCapture != null);
        observed.setBoolean("pendingSaveAck", pendingSaveAck != null);
        observed.setString("pendingJsonSha256", pendingSaveAck == null ? "" : pendingSaveAck.getString("jsonSha256"));
        observed
            .setString("pendingPreviewSha256", pendingSaveAck == null ? "" : pendingSaveAck.getString("previewSha256"));
        observed.setBoolean("pendingDiskSave", pendingDiskSave != null);
        observed.setBoolean("diskFinalizing", pendingDiskSave != null && pendingDiskSave.finalizing);
        observed.setString("outgoingOp", outgoingOp);
        observed.setInteger("outgoingBytes", outgoing == null ? 0 : outgoing.length);
        observed.setInteger("sentBytes", sentBytes);
        observed.setInteger("nextChunk", nextChunk);
        observed.setBoolean("ready", ready);
        observed.setBoolean("inFlight", inFlight);
        observed.setInteger("workerActive", worker.getActiveCount());
        observed.setInteger(
            "workerQueued",
            worker.getQueue()
                .size());
        observed.setLong("waitingStateNanos", saveProgress.waitingSince == 0 ? 0 : now - saveProgress.waitingSince);
        observed.setLong("idleNanos", saveProgress.progressedAt == 0 ? 0 : now - saveProgress.progressedAt);
        return observed;
    }

    private void traceSave(String phase) {
        if (!Boolean.getBoolean("gtmb.hologram.librarySaveTrace")
            && !Boolean.getBoolean("gtmb.hologram.previewCaptureWitness")) return;
        NBTTagCompound observed = saveState();
        observed.setString("phase", phase);
        lastSaveTransition = observed;
        if (saveTraceEvents++ >= 16) return;
        saveTransitions.appendTag(observed.copy());
        com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info(
            "Blueprint save phase=" + phase
                + " receiptSha="
                + observed.getString("saveReceiptSha256")
                    .substring(0, 12)
                + " revision="
                + observed.getLong("currentRevision")
                + "/"
                + incomingSaveRevision
                + " received="
                + observed.getInteger("incomingBytes")
                + "/"
                + incomingTotal
                + " sent="
                + sentBytes
                + "/"
                + observed.getInteger("outgoingBytes")
                + " waitingState="
                + observed.getBoolean("pendingPreviewStart"));
    }

    private static final class PendingDiskSave {

        final HologramBlueprintArtifact.PairWrite write;
        final NBTTagCompound acknowledgement;
        final String serverKey, name, path;
        final boolean integrated;
        boolean finalizing;

        PendingDiskSave(HologramBlueprintArtifact.PairWrite write, NBTTagCompound acknowledgement, String serverKey,
            String name, String path) {
            this.write = write;
            this.acknowledgement = (NBTTagCompound) acknowledgement.copy();
            this.serverKey = serverKey;
            this.name = name;
            this.path = path;
            integrated = !dedicated();
        }
    }

    /** Actual packet and render evidence only; ordinary clients retain no witness payloads. */
    static NBTTagCompound actualSaveWitness() {
        return Boolean.getBoolean("gtmb.hologram.previewCaptureWitness") ? (NBTTagCompound) INSTANCE.saveWitness.copy()
            : new NBTTagCompound();
    }

    private void recordSaveWitness(String key, NBTTagCompound proof) {
        if (!Boolean.getBoolean("gtmb.hologram.previewCaptureWitness")) return;
        NBTTagCompound observed = (NBTTagCompound) saveWitness.copy();
        observed.setTag(key, proof.copy());
        if ("previewVerifiedReply".equals(key)) observed.setTag("previewVerified", proof.copy());
        if ("ownerDiskAck".equals(key)) observed.setTag("ackBlueprintSaved", proof.copy());
        if ("saveCommittedReply".equals(key) || "saveRejectedReply".equals(key))
            observed.setTag("finalOutcome", proof.copy());
        observed.setLong("observedAtNanos", System.nanoTime());
        saveWitness = observed;
    }

    private HologramLibraryClient() {}

    static boolean dedicated() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld != null && !mc.isIntegratedServerRunning();
    }

    void install() {
        HologramBlueprintPreview.install();
        HologramNetwork.setLibraryReceiver(reply -> {
            long token = generation;
            Minecraft.getMinecraft()
                .func_152344_a(() -> { if (token == generation) receive(reply); });
        });
    }

    void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        String current = mc.theWorld == null ? ""
            : mc.func_147104_D() == null ? "integrated" : mc.func_147104_D().serverIP;
        UUID currentActor = mc.thePlayer == null ? null : mc.thePlayer.getUniqueID();
        if (libraryWorld != mc.theWorld || !current.equals(connection)
            || !java.util.Objects.equals(libraryActor, currentActor)) {
            cancel();
            PendingDiskSave abandoned = pendingDiskSave;
            pendingDiskSave = null;
            if (abandoned != null) work(() -> {
                boolean committed = abandoned.integrated && store.logicalSaveCommitted(
                    abandoned.acknowledgement.getString("localId"),
                    abandoned.acknowledgement.getString("jsonSha256"));
                if (committed) abandoned.write.commit();
                else abandoned.write.rollback();
            });
            preparing = false;
            HologramNetwork.clearLibraryClientTransport();
            libraryWorld = mc.theWorld;
            libraryActor = currentActor;
            connection = current;
            generation++;
            server = new NBTTagList();
            selectedServer = "";
            config = new NBTTagCompound();
            page = 0;
            observedPublic = "";
        }
        if ((previewCapture != null || pendingPreviewStart != null
            || pendingSaveAck != null
            || incoming != null && incomingCapturePreview)
            && !saveSourceAwaiting(generation, incomingSaveSession, incomingSaveRevision)) {
            boolean timedOut = saveProgress.waitingExpired(System.nanoTime());
            cancel();
            status = timedOut ? "等待对应蓝图状态超时，未保存修改保留" : "蓝图来源已变化，结构预览保存已取消";
        }
        if (saveSourceCurrent(generation, incomingSaveSession, incomingSaveRevision))
            saveProgress.sourceReady(System.nanoTime());
        if (pendingDiskSave == null && incomingCapturePreview
            && !incomingSaveReceipt.isEmpty()
            && (incoming != null || preparing
                || pendingPreviewStart != null
                || previewCapture != null
                || pendingSaveAck != null)
            && saveProgress.idleExpired(System.nanoTime())) {
            cancel();
            status = "蓝图保存无进展超时，未保存修改保留";
        }
        if (pendingPreviewStart != null && saveSourceCurrent(generation, incomingSaveSession, incomingSaveRevision)) {
            Runnable start = pendingPreviewStart;
            pendingPreviewStart = null;
            start.run();
        }
        if (pendingDiskSave != null && !pendingDiskSave.finalizing
            && pendingDiskSave.serverKey.equals(connection)
            && mc.theWorld != null
            && System.nanoTime() - lastSaveStatus > TimeUnit.SECONDS.toNanos(2)) {
            NBTTagCompound query = (NBTTagCompound) pendingDiskSave.acknowledgement.copy();
            query.setString("op", "saveStatus");
            lastSaveStatus = System.nanoTime();
            HologramNetwork.sendLibrary(query);
        }
        if (quick.active()) {
            if (HologramClient.state != null && HologramClient.state.data.getBoolean("virtualLocked")
                && quick.commitLockedSession(HologramClient.state.session)) {
                quickBytes = null;
            } else if (!sameQuickTool(mc) || mc.currentScreen != null) cancelQuickPlacement();
        }
        if (dedicated() && HologramClient.state != null) {
            String selected = HologramClient.state.data.getString("selectedPresetId");
            if (selected.startsWith("public_") && selected.equals(selectedServer)
                && !selectedRow(true).getString("id")
                    .isEmpty()
                && !selected.equals(observedPublic)) {
                observedPublic = selected;
                rememberPublic(selected);
            }
        }
        if ((outgoing != null || incoming != null
            || downloadExpected
            || preparing
            || previewCapture != null
            || pendingPreviewStart != null
            || quickInProgress()) && pendingDiskSave == null
            && System.nanoTime() - lastActivity > TimeUnit.SECONDS.toNanos(120)) {
            cancel();
            status = "传输超时，请重试";
        }
        if (outgoing != null && ready && !inFlight && nextChunk * 1024 < outgoing.length) {
            int offset = nextChunk * 1024;
            NBTTagCompound chunk = request(outgoingOp + "Chunk");
            chunk.setString("transfer", outgoingId);
            chunk.setInteger("index", nextChunk);
            chunk.setByteArray("data", Arrays.copyOfRange(outgoing, offset, Math.min(offset + 1024, outgoing.length)));
            inFlight = true;
            HologramNetwork.sendLibrary(chunk);
        }
    }

    void open() {
        ensureStore();
        refreshLocal();
        if (dedicated()) {
            refreshServer(0);
            HologramNetwork.sendLibrary(request("configGet"));
        }
    }

    private void ensureStore() {
        if (store == null)
            store = new HologramClientLibrary(Paths.get(Minecraft.getMinecraft().mcDataDir.getAbsolutePath()));
    }

    void rememberPublic(String id) {
        ensureStore();
        String serverKey = connection;
        work(() -> store.rememberPublic(serverKey, id));
    }

    /** Alt is fenced before any IO; missing local files retain the original server shortcut fallback. */
    boolean quickPlaceLast(NBTTagCompound position) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!dedicated() || mc.thePlayer == null
            || mc.currentScreen != null
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector
                .isProjector(mc.thePlayer.getHeldItem()))
            return false;
        if (busy()) {
            status = "蓝图传输进行中，请等待或取消";
            return true;
        }
        cancelQuickPlacement();
        ensureStore();
        String token = quick.begin(), serverKey = connection;
        quickSession = "";
        Object world = mc.theWorld;
        quickTool = mc.thePlayer.getHeldItem();
        preparing = true;
        quickSlot = mc.thePlayer.inventory.currentItem;
        quickToolId = mc.thePlayer.getHeldItem()
            .hasTagCompound()
                ? mc.thePlayer.getHeldItem()
                    .getTagCompound()
                    .getString("gtitHologramToolId")
                : "";
        lastActivity = System.nanoTime();
        NBTTagCompound fallback = (NBTTagCompound) position.copy();
        status = "正在读取上一张客户端蓝图";
        work(() -> {
            HologramClientLibrary.LastSource source = store.lastSource(serverKey);
            byte[] loaded = null;
            if (!source.publicLibrary && !source.localId.isEmpty()) {
                try {
                    loaded = store.artifact(source.localId);
                } catch (java.io.IOException missing) { /* A removed local file leaves the server fallback available. */ }
            }
            byte[] bytes = loaded;
            String hash = bytes == null ? "" : hash(bytes);
            main(() -> {
                Minecraft current = Minecraft.getMinecraft();
                if (!quick.matches(token) || current.theWorld != world
                    || current.thePlayer == null
                    || !sameQuickTool(current)
                    || current.currentScreen != null) return;
                if (bytes == null) {
                    preparing = false;
                    quick.committed();
                    if (!source.publicId.isEmpty()) fallback.setString("publicPresetId", source.publicId);
                    status = "正在快捷放置上次服务器蓝图";
                    HologramNetwork.sendAction(fallback);
                    return;
                }
                quickBytes = bytes;
                quickHash = hash;
                quickLocal = source.localId;
                NBTTagCompound bootstrap = request("quickBootstrap");
                bootstrap.setString("quickToken", token);
                bootstrap.setInteger("x", fallback.getInteger("x"));
                bootstrap.setInteger("y", fallback.getInteger("y"));
                bootstrap.setInteger("z", fallback.getInteger("z"));
                bootstrap.setInteger("side", fallback.getInteger("side"));
                status = "快捷放置 · 等待服务器创建临时会话";
                lastActivity = System.nanoTime();
                HologramNetwork.sendLibrary(bootstrap);
            });
        });
        return true;
    }

    boolean quickInProgress() {
        return quick.active() && (preparing || outgoingQuick && outgoing != null);
    }

    private boolean sameQuickTool(Minecraft mc) {
        if (mc.thePlayer == null || mc.thePlayer.inventory.currentItem != quickSlot
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector
                .isProjector(mc.thePlayer.getHeldItem()))
            return false;
        net.minecraft.item.ItemStack held = mc.thePlayer.getHeldItem();
        String id = held.hasTagCompound() ? held.getTagCompound()
            .getString("gtitHologramToolId") : "";
        if (!quickToolId.isEmpty()) return quickToolId.equals(id);
        if (!id.isEmpty()) {
            quickToolId = id;
            return true;
        }
        return held == quickTool;
    }

    boolean cancelQuickPlacement() {
        if (!quick.active()) return false;
        String token = quick.cancel();
        NBTTagCompound request = request("quickCancel");
        request.setString("quickToken", token);
        if (dedicated()) HologramNetwork.sendLibrary(request);
        if (!quickSession.isEmpty()) HologramClient.dismissRootView(quickSession, 0);
        if (outgoingQuick && outgoing != null) {
            NBTTagCompound cancel = request("cancel");
            cancel.setString("transfer", outgoingId);
            if (dedicated()) HologramNetwork.sendLibrary(cancel);
            outgoing = null;
            outgoingId = "";
            ready = inFlight = false;
        }
        generation++;
        quickBytes = null;
        quickLocal = quickSession = "";
        outgoingQuick = false;
        preparing = false;
        status = "快捷放置已取消";
        return true;
    }

    void refreshLocal() {
        if (store == null || loading) return;
        loading = true;
        work(() -> {
            NBTTagList labels = new NBTTagList();
            for (HologramClientLibrary.Entry entry : store.list()) {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("id", entry.id);
                row.setString("name", entry.name);
                row.setString("authorName", entry.authorName);
                row.setString("authorUuid", entry.authorUuid);
                row.setString("sourceMod", entry.sourceMod);
                row.setLong("createdAt", entry.createdAt);
                row.setInteger("cells", entry.cells);
                row.setInteger("structures", entry.structures);
                row.setInteger("schema", 2);
                row.setLong("bytes", entry.bytes);
                row.setBoolean("mine", true);
                labels.appendTag(row);
            }
            main(() -> {
                local = labels;
                loading = false;
            });
        });
    }

    void refreshServer(int value) {
        page = Math.max(0, value);
        NBTTagCompound request = request("list");
        request.setInteger("page", page);
        HologramNetwork.sendLibrary(request);
    }

    NBTTagList rows(boolean publicLibrary) {
        return publicLibrary ? server : local;
    }

    String selected(boolean publicLibrary) {
        return publicLibrary ? selectedServer : selectedLocal;
    }

    void select(boolean publicLibrary, String id) {
        if (publicLibrary) selectedServer = id;
        else selectedLocal = id;
    }

    NBTTagCompound selectedRow(boolean publicLibrary) {
        NBTTagList rows = rows(publicLibrary);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (row.getString("id")
                .equals(selected(publicLibrary))) return row;
        }
        return new NBTTagCompound();
    }

    String status() {
        return HologramUiText.reason(status);
    }

    NBTTagCompound smokeQuickState() {
        Minecraft mc = Minecraft.getMinecraft();
        NBTTagCompound observed = new NBTTagCompound();
        observed.setBoolean("active", quick.active());
        observed.setString("token", quick.token());
        observed.setString("acknowledgedSession", quickSession);
        observed.setString("localId", quickLocal);
        observed.setString("status", status);
        observed.setLong("generation", generation);
        observed.setBoolean("preparing", preparing);
        observed.setBoolean("loading", loading);
        observed.setBoolean("outgoingQuick", outgoingQuick);
        observed.setBoolean("ready", ready);
        observed.setBoolean("inFlight", inFlight);
        observed.setBoolean("bytesAwaitingBootstrap", quickBytes != null);
        observed.setString("transfer", outgoingId);
        observed.setString("transferOp", outgoingOp);
        observed.setInteger("sentBytes", sentBytes);
        observed.setInteger("outgoingBytes", outgoing == null ? 0 : outgoing.length);
        observed.setInteger("expectedSlot", quickSlot);
        observed.setString("expectedToolUuid", quickToolId);
        observed.setBoolean("sameWorld", libraryWorld == mc.theWorld);
        observed.setString(
            "screen",
            mc.currentScreen == null ? ""
                : mc.currentScreen.getClass()
                    .getName());
        if (mc.thePlayer != null) {
            net.minecraft.item.ItemStack held = mc.thePlayer.getHeldItem();
            observed.setInteger("heldSlot", mc.thePlayer.inventory.currentItem);
            observed.setInteger("heldStackIdentity", held == null ? 0 : System.identityHashCode(held));
            observed.setBoolean(
                "heldProjector",
                com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(held));
            observed.setString(
                "heldToolUuid",
                held == null || !held.hasTagCompound() ? ""
                    : held.getTagCompound()
                        .getString("gtitHologramToolId"));
        }
        return observed;
    }

    boolean busy() {
        return outgoing != null || incoming != null || loading || preparing || pendingDiskSave != null;
    }

    int progressBytes() {
        return outgoing != null ? sentBytes : incoming == null ? 0 : incoming.size();
    }

    int totalBytes() {
        return outgoing != null ? outgoing.length : incomingTotal;
    }

    int page() {
        return page;
    }

    int pages() {
        return pages;
    }

    NBTTagCompound config() {
        return config;
    }

    void saveDraft(HologramState state, NBTTagCompound options, boolean replace) {
        if (busy()) return;
        NBTTagCompound request = (NBTTagCompound) options.copy();
        request.setString("op", "exportDraft");
        request.setString("session", state.session);
        request.setLong("planRevision", state.data.getLong("planRevision"));
        String replaceId = replace && state.data.getBoolean("virtual") ? state.data.getString("libraryLocalId") : "";
        request.setString("localId", replaceId);
        request.setString("replaceLocalId", replaceId);
        preparing = true;
        draftPending = true;
        downloadExpected = true;
        saveProgress.clear();
        lastActivity = System.nanoTime();
        captureActor();
        status = "正在准备蓝图，确认后下载至客户端";
        HologramNetwork.sendLibrary(request);
    }

    void cancelDraftReview() {
        if (!draftPending || incoming != null) return;
        draftPending = downloadExpected = preparing = false;
        status = "蓝图保存已取消";
    }

    void expectDraftDownload() {
        ensureStore();
        preparing = draftPending = downloadExpected = true;
        saveProgress.clear();
        captureActor();
        status = "等待服务器审核蓝图并发送保存内容";
        lastActivity = System.nanoTime();
    }

    void sendLocal(HologramState state, String action, boolean upload) {
        sendLocal(state, action, upload, false);
    }

    void sendLocal(HologramState state, String action, boolean upload, boolean confirmDiscard) {
        if (busy() || selectedLocal.isEmpty()) return;
        String id = selectedLocal;
        long token = generation;
        String targetServer = connection;
        preparing = true;
        status = upload ? "正在读取客户端蓝图，准备上传" : "正在读取客户端蓝图，准备" + actionLabel(action);
        work(() -> {
            byte[] bytes = store.artifact(id);
            String hash = hash(bytes);
            String replaceId = upload ? store.uploadedId(targetServer, id) : "";
            main(() -> {
                if (generation != token) return;
                preparing = false;
                outgoing = bytes;
                outgoingId = UUID.randomUUID()
                    .toString();
                outgoingOp = upload ? "upload" : "private";
                outgoingQuick = false;
                outgoingLocal = id;
                nextChunk = sentBytes = 0;
                ready = inFlight = false;
                NBTTagCompound begin = request(outgoingOp + "Begin");
                begin.setString("transfer", outgoingId);
                begin.setInteger("total", bytes.length);
                begin.setString("hash", hash);
                begin.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
                begin.setString("session", state.session);
                begin.setLong("planRevision", state.data.getLong("planRevision"));
                begin.setString("action", action);
                begin.setBoolean("confirmDiscard", confirmDiscard);
                begin.setString("localId", id);
                if (upload && !replaceId.isEmpty()) begin.setString("replaceId", replaceId);
                status = upload ? "上传中 · 等待服务器接收" : "传输中 · " + actionLabel(action);
                lastActivity = System.nanoTime();
                HologramNetwork.sendLibrary(begin);
            });
        });
    }

    void downloadSelected() {
        if (busy() || selectedServer.isEmpty()) return;
        captureActor();
        NBTTagCompound request = request("download");
        request.setString("id", selectedServer);
        preparing = true;
        downloadExpected = true;
        lastActivity = System.nanoTime();
        status = "下载中 · 保存副本到客户端";
        HologramNetwork.sendLibrary(request);
    }

    void deleteSelected(boolean publicLibrary) {
        if (busy()) return;
        String id = selected(publicLibrary);
        if (id.isEmpty()) return;
        if (publicLibrary) {
            if (!selectedRow(true).getBoolean("mine")) return;
            NBTTagCompound request = request("delete");
            request.setString("id", id);
            HologramNetwork.sendLibrary(request);
            status = "正在请求删除自己的上传";
        } else work(() -> {
            store.delete(id);
            main(() -> {
                selectedLocal = "";
                status = "客户端蓝图已删除";
                refreshLocal();
            });
        });
    }

    void saveConfig(int uploadKiB, int downloadKiB, int maxUploads) {
        if (!config.getBoolean("admin")) return;
        NBTTagCompound request = request("configSet");
        request.setLong("expectedRevision", config.getLong("configRevision"));
        request.setInteger("uploadKiB", uploadKiB);
        request.setInteger("downloadKiB", downloadKiB);
        request.setInteger("maxUploads", maxUploads);
        configWritePending = true;
        HologramNetwork.sendLibrary(request);
        status = "等待服务器确认设置";
    }

    void cancel() {
        boolean activeTransfer = outgoing != null || incoming != null
            || preparing
            || inFlight
            || downloadExpected
            || draftPending
            || quick.active()
            || pendingDiskSave != null;
        if (activeTransfer && !incomingSaveReceipt.isEmpty()) traceSave("cancel_before_clear");
        cancelQuickPlacement();
        if (previewCapture != null) previewCapture.cancel();
        previewCapture = null;
        pendingPreviewStart = null;
        pendingSaveAck = null;
        if (!outgoingId.isEmpty() || !incomingId.isEmpty()
            || activeTransfer && !incomingSaveReceipt.isEmpty()
            || pendingDiskSave != null) {
            NBTTagCompound request = pendingDiskSave == null ? request("cancel")
                : (NBTTagCompound) pendingDiskSave.acknowledgement.copy();
            request.setString("op", "cancel");
            request.setString("transfer", outgoing != null ? outgoingId : incomingId);
            if (pendingDiskSave == null && activeTransfer && !incomingSaveReceipt.isEmpty()) {
                request.setString("session", incomingSaveSession);
                request.setLong("planRevision", incomingSaveRevision);
                request.setString("saveReceipt", incomingSaveReceipt);
                request.setString("replaceLocalId", incomingLocal);
            }
            HologramNetwork.sendLibrary(request);
            lastSaveStatus = System.nanoTime();
        }
        generation++;
        saveProgress.clear();
        downloadExpected = false;
        draftPending = false;
        outgoing = null;
        incoming = null;
        outgoingId = incomingId = "";
        ready = inFlight = preparing = false;
        if (pendingDiskSave != null) preparing = true;
        status = activeTransfer ? "传输已取消" : "";
    }

    private void receive(NBTTagCompound reply) {
        lastActivity = System.nanoTime();
        String op = reply.getString("op");
        if ("saveCommitted".equals(op) || "saveRejected".equals(op)) {
            finishDiskSave(reply, "saveCommitted".equals(op));
        } else if ("quickBootstrap".equals(op)) {
            String token = reply.getString("quickToken"), session = reply.getString("session");
            if (!quick.matches(token) || quickBytes == null
                || session.isEmpty()
                || !reply.hasKey("planRevision", 4)
                || !quick.bootstrap(token, session)) return;
            quickSession = session;
            outgoing = quickBytes;
            quickBytes = null;
            outgoingQuick = true;
            outgoingLocal = quickLocal;
            outgoingId = UUID.randomUUID()
                .toString();
            outgoingOp = "private";
            nextChunk = sentBytes = 0;
            preparing = ready = inFlight = false;
            NBTTagCompound begin = request("privateBegin");
            begin.setString("transfer", outgoingId);
            begin.setInteger("total", outgoing.length);
            begin.setString("hash", quickHash);
            begin.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            begin.setString("session", session);
            begin.setLong("planRevision", reply.getLong("planRevision"));
            begin.setString("localId", quickLocal);
            begin.setString("action", "place");
            begin.setBoolean("quickPlace", true);
            begin.setString("quickToken", token);
            status = "快捷放置 · 正在传输上一张客户端蓝图";
            HologramNetwork.sendLibrary(begin);
        } else if ("queued".equals(op)) {
            status = reply.getString("message");
        } else if ("list".equals(op)) {
            server = reply.getTagList("entries", 10);
            page = reply.getInteger("page");
            pages = Math.max(1, reply.getInteger("pages"));
            if (reply.hasKey("admin")) {
                config.setBoolean("admin", reply.getBoolean("admin"));
                config.setBoolean("readOnly", !reply.getBoolean("admin"));
            }
        } else if ("config".equals(op)) {
            config = (NBTTagCompound) reply.copy();
            if (!config.getString("libraryError")
                .isEmpty()) status = "服务器蓝图库只读：" + config.getString("libraryError");
            else if (configWritePending) status = "服务器设置已确认 · 修订 " + config.getLong("configRevision");
            configWritePending = false;
        } else if ("uploadReady".equals(op) || "privateReady".equals(op) || "previewReady".equals(op)) {
            if (matchesOutgoing(reply)) ready = true;
        } else if ("previewVerified".equals(op)) {
            if (!matchesOutgoing(reply) || pendingSaveAck == null
                || !pendingSaveAck.getString("saveReceipt")
                    .equals(reply.getString("saveReceipt"))
                || !pendingSaveAck.getString("jsonSha256")
                    .equals(reply.getString("jsonSha256"))
                || !pendingSaveAck.getString("previewSha256")
                    .equals(reply.getString("previewSha256")))
                return;
            NBTTagCompound acknowledgement = pendingSaveAck;
            saveProgress.progress(System.nanoTime());
            traceSave("preview_verified");
            recordSaveWitness("previewVerifiedReply", reply);
            byte[] verifiedArtifact = outgoing;
            String saved = outgoingLocal, serverKey = connection;
            long token = generation;
            pendingSaveAck = null;
            outgoing = null;
            outgoingId = "";
            ready = inFlight = false;
            preparing = true;
            status = "结构预览已确认，正在原子保存 JSON 与 PNG";
            work(() -> {
                HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.decode(verifiedArtifact);
                String session = acknowledgement.getString("session");
                long revision = acknowledgement.getLong("planRevision");
                HologramBlueprintArtifact.PairWrite written = store
                    .savePending(artifact.json, artifact.png, saved, () -> saveBytesCurrent(token, session, revision));
                String savedName, path;
                try {
                    HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.decode(store.artifact(saved));
                    if (!actual.jsonSha256.equals(acknowledgement.getString("jsonSha256"))
                        || !actual.pngSha256.equals(acknowledgement.getString("previewSha256")))
                        throw new java.io.IOException("Actual saved artifact differs from verified bytes");
                    savedName = store.savedName(saved);
                    path = store.savedPath(saved)
                        .toString();
                } catch (Exception failure) {
                    try {
                        written.rollback();
                    } catch (Exception rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
                main(() -> {
                    if (!saveSourceCurrent(token, session, revision)) {
                        work(() -> {
                            try {
                                written.rollback();
                            } catch (Exception failure) {
                                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                                    .warn("Cancelled blueprint pair rollback failed", failure);
                            }
                        });
                        if (token == generation) cancel();
                        return;
                    }
                    pendingDiskSave = new PendingDiskSave(written, acknowledgement, serverKey, savedName, path);
                    preparing = true;
                    lastSaveStatus = System.nanoTime();
                    HologramNetwork.sendAction(acknowledgement);
                    recordSaveWitness("ownerDiskAck", acknowledgement);
                    traceSave("owner_disk_ack");
                    status = "JSON 与 PNG 已写入，正在确认蓝图库索引";
                });
            });
        } else if ("chunkAck".equals(op)) {
            if (!matchesOutgoing(reply)) return;
            int previousBytes = sentBytes;
            nextChunk = reply.getInteger("nextIndex");
            sentBytes = Math.min(outgoing.length, nextChunk * 1024);
            if (pendingSaveAck != null && sentBytes > previousBytes) {
                saveProgress.progress(System.nanoTime());
                int marker = (int) ((long) sentBytes * 4 / outgoing.length);
                if (marker > uploadProgressMarker && marker < 4) {
                    uploadProgressMarker = marker;
                    traceSave("preview_upload_progress_" + marker);
                }
            }
            inFlight = false;
        } else if ("uploaded".equals(op) || "privateImported".equals(op)) {
            if (!matchesOutgoing(reply)) return;
            boolean uploaded = "uploaded".equals(op);
            outgoing = null;
            outgoingId = "";
            ready = inFlight = false;
            status = uploaded ? "已上传至服务器蓝图库" : "蓝图已传输，服务器正在打开预览";
            if (uploaded) {
                selectedServer = reply.getString("id");
                String serverKey = connection, localId = outgoingLocal, publicId = selectedServer;
                work(() -> store.uploadedId(serverKey, localId, publicId));
                refreshServer(page);
            } else {
                String serverKey = connection, localId = outgoingLocal;
                work(() -> store.rememberLocal(serverKey, localId));
                status = outgoingQuick ? "上一张客户端蓝图已进入世界定位" : "蓝图已传输，服务器正在打开预览";
                outgoingQuick = false;
            }
        } else if ("deleted".equals(op)) {
            String serverKey = connection, publicId = reply.getString("id");
            if (store != null) work(() -> store.forgetUpload(serverKey, publicId));
            status = "自己的上传已删除";
            selectedServer = "";
            refreshServer(page);
        } else if ("downloadBegin".equals(op)) {
            boolean nativeDraft = reply.getBoolean("capturePreview") && !reply.getString("saveReceipt")
                .isEmpty() && saveSourceAwaiting(generation, reply.getString("session"), reply.getLong("planRevision"));
            if (reply.getBoolean("capturePreview") && !nativeDraft) return;
            if (!downloadExpected && !nativeDraft) return;
            ensureStore();
            if (nativeDraft) captureActor();
            downloadExpected = false;
            draftPending = false;
            int total = reply.getInteger("total");
            boolean capturePreview = reply.getBoolean("capturePreview");
            if (incoming != null || total <= 0
                || total > (capturePreview ? HologramClientLibrary.MAX_BYTES : HologramBlueprintArtifact.MAX_BYTES)
                || capturePreview != "draft".equals(reply.getString("purpose"))
                || !reply.getString("hash")
                    .matches("[a-f0-9]{64}")
                || !reply.getString("transfer")
                    .matches("[a-f0-9-]{36}")
                || reply.getInteger("artifactVersion") != HologramBlueprintArtifact.VERSION) {
                preparing = false;
                status = "下载长度无效";
                return;
            }
            incoming = new ByteArrayOutputStream(Math.min(total, 65536));
            incomingTotal = total;
            incomingIndex = 0;
            preparing = false;
            incomingId = reply.getString("transfer");
            incomingHash = reply.getString("hash");
            incomingPurpose = reply.getString("purpose");
            incomingLocal = reply.getString("localId");
            incomingSaveReceipt = reply.getString("saveReceipt");
            incomingSaveSession = reply.getString("session");
            incomingSaveRevision = reply.getLong("planRevision");
            incomingCapturePreview = reply.getBoolean("capturePreview");
            if (incomingCapturePreview) {
                saveProgress.begin(
                    System.nanoTime(),
                    HologramClient.state.data.getLong("planRevision") != incomingSaveRevision);
                saveTransitions = new NBTTagList();
                saveWitness = new NBTTagCompound();
                saveTraceEvents = downloadProgressMarker = uploadProgressMarker = 0;
                traceSave("download_begin");
            } else saveProgress.clear();
            status = "下载中 · 等待服务器分片";
        } else if ("downloadChunk".equals(op)) {
            if (incoming == null || !incomingId.equals(reply.getString("transfer"))) return;
            byte[] bytes = reply.getByteArray("data");
            if (reply.getInteger("index") != incomingIndex
                || bytes.length != Math.min(1024, incomingTotal - incoming.size())) {
                cancel();
                status = "下载分片无效，请重试";
                return;
            }
            incoming.write(bytes, 0, bytes.length);
            incomingIndex++;
            if (incomingCapturePreview) {
                saveProgress.progress(System.nanoTime());
                int marker = (int) ((long) incoming.size() * 4 / incomingTotal);
                if (incomingIndex == 1) traceSave("download_first_chunk");
                else if (marker > downloadProgressMarker && marker < 4) {
                    downloadProgressMarker = marker;
                    traceSave("download_progress_" + marker);
                }
            }
        } else if ("downloadDone".equals(op)) {
            if (incoming == null || !incomingId.equals(reply.getString("transfer"))) return;
            byte[] bytes = incoming.toByteArray();
            String expected = incomingHash;
            String replace = "draft".equals(incomingPurpose) ? incomingLocal : "";
            String saveReceipt = incomingSaveReceipt, saveSession = incomingSaveSession;
            boolean capturePreview = incomingCapturePreview;
            long saveRevision = incomingSaveRevision;
            long token = generation;
            String serverKey = connection;
            int expectedTotal = incomingTotal;
            preparing = true;
            if (capturePreview) {
                saveProgress.progress(System.nanoTime());
                traceSave("download_done");
            }
            incoming = null;
            incomingId = "";
            status = "正在校验并保存客户端蓝图";
            work(() -> {
                if (token != generation) return;
                if (bytes.length != expectedTotal || !hash(bytes).equals(expected))
                    throw new java.io.IOException("下载校验失败");
                if (capturePreview) {
                    com.miaokatze.gtmb.hologram.HologramLibraryFacade.validateNativeJson(bytes);
                    NBTTagCompound snapshot = HologramBlueprintPreview.snapshot(bytes);
                    main(() -> {
                        if (token != generation) return;
                        saveProgress.progress(System.nanoTime());
                        traceSave("preview_snapshot_ready");
                        Runnable start = () -> {
                            if (!saveSourceCurrent(token, saveSession, saveRevision)) return;
                            status = "正在生成真实结构预览 PNG";
                            previewCapture = HologramBlueprintPreview.capture(
                                snapshot,
                                () -> saveSourceCurrent(token, saveSession, saveRevision),
                                image -> persistPreview(
                                    bytes,
                                    image,
                                    replace,
                                    saveReceipt,
                                    saveSession,
                                    saveRevision,
                                    token,
                                    serverKey),
                                failure -> {
                                    if (token != generation) return;
                                    failSave("结构预览保存失败：" + HologramUiText.reason(failure.getMessage()), failure);
                                });
                            traceSave("preview_capture_queued");
                        };
                        if (saveSourceCurrent(token, saveSession, saveRevision)) start.run();
                        else if (saveSourceAwaiting(token, saveSession, saveRevision)) {
                            pendingPreviewStart = start;
                            status = "保存内容已接收，正在等待对应的蓝图状态";
                            traceSave("waiting_exact_state");
                        } else failSave("蓝图来源已变化，请重新保存", null);
                    });
                } else {
                    HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.decode(bytes);
                    persistArtifact(
                        artifact.json,
                        artifact.png,
                        replace,
                        "",
                        saveSession,
                        saveRevision,
                        token,
                        serverKey);
                }
            });
        } else if ("error".equals(op)) {
            cancel();
            configWritePending = false;
            status = "服务器：" + HologramUiText.reason(reply.getString("message"));
        }
    }

    private boolean matchesOutgoing(NBTTagCompound reply) {
        return outgoing != null && (!reply.hasKey("transfer") || outgoingId.equals(reply.getString("transfer")));
    }

    private void finishDiskSave(NBTTagCompound reply, boolean committed) {
        PendingDiskSave pending = pendingDiskSave;
        if (pending == null || pending.finalizing || !pending.serverKey.equals(connection)) return;
        NBTTagCompound acknowledgement = pending.acknowledgement;
        for (String field : new String[] { "saveReceipt", "session", "localId", "jsonSha256", "previewSha256" })
            if (!acknowledgement.getString(field)
                .equals(reply.getString(field))) return;
        if (acknowledgement.getLong("planRevision") != reply.getLong("planRevision")
            || reply.getInteger("artifactVersion") != HologramBlueprintArtifact.VERSION) return;
        recordSaveWitness(committed ? "saveCommittedReply" : "saveRejectedReply", reply);
        traceSave(committed ? "owner_committed" : "owner_rejected");
        pending.finalizing = true;
        preparing = true;
        work(() -> {
            if (committed) {
                pending.write.commit();
                store.rememberLocal(pending.serverKey, acknowledgement.getString("localId"));
            } else pending.write.rollback();
            main(() -> {
                if (pendingDiskSave != pending) return;
                pendingDiskSave = null;
                saveProgress.clear();
                preparing = false;
                NBTTagCompound consumed = (NBTTagCompound) acknowledgement.copy();
                consumed.setString("op", "saveResultConsumed");
                if (pending.serverKey.equals(connection)) HologramNetwork.sendLibrary(consumed);
                if (committed) {
                    selectedLocal = acknowledgement.getString("localId");
                    announceSaved(pending.name, pending.path);
                    status = "JSON 与结构预览 PNG 已校验保存";
                } else status = "蓝图保存未提交，原 JSON 与 PNG 已恢复：" + HologramUiText.reason(reply.getString("message"));
                refreshLocal();
            });
        });
    }

    private boolean saveSourceCurrent(long token, String session, long revision) {
        return saveSourceAwaiting(token, session, revision)
            && HologramClient.state.data.getLong("planRevision") == revision;
    }

    private boolean saveSourceAwaiting(long token, String session, long revision) {
        HologramState current = HologramClient.state;
        return token == generation && Minecraft.getMinecraft().theWorld == libraryWorld
            && !saveProgress.waitingExpired(System.nanoTime())
            && Minecraft.getMinecraft().currentScreen instanceof HologramScreen
            && Minecraft.getMinecraft().thePlayer != null
            && Minecraft.getMinecraft().thePlayer.getUniqueID()
                .equals(libraryActor)
            && current != null
            && session.equals(current.session)
            && current.data.getLong("planRevision") <= revision;
    }

    private void failSave(String message, Throwable failure) {
        if (pendingDiskSave != null) pendingDiskSave.finalizing = false;
        cancel();
        status = message;
        com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn(message, failure);
    }

    /** The network-published state is volatile; an IO commit never waits for the next client tick to see edits. */
    private boolean saveBytesCurrent(long token, String session, long revision) {
        HologramState current = HologramClient.state;
        return token == generation && current != null
            && session.equals(current.session)
            && current.data.getLong("planRevision") == revision;
    }

    private void persistPreview(byte[] json, BufferedImage image, String replace, String receipt, String session,
        long revision, long token, String serverKey) {
        previewCapture = null;
        if (!saveSourceCurrent(token, session, revision)) return;
        saveProgress.progress(System.nanoTime());
        traceSave("preview_rendered");
        if (Boolean.getBoolean("gtmb.hologram.previewCaptureWitness")) {
            NBTTagCompound capture = HologramBlueprintPreview.lastCaptureWitness();
            saveWitness = new NBTTagCompound();
            recordSaveWitness("nativePreviewWitness", capture);
        }
        work(() -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new java.io.IOException("PNG encoder unavailable");
            byte[] preview = HologramBlueprintArtifact.bindPreview(json, output.toByteArray());
            persistArtifact(json, preview, replace, receipt, session, revision, token, serverKey);
        });
    }

    /** Native saves are staged until the server validates their source-bound PNG; downloads retain supplied pixels. */
    private void persistArtifact(byte[] json, byte[] preview, String replace, String receipt, String session,
        long revision, long token, String serverKey) throws Exception {
        boolean reviewedSave = !receipt.isEmpty();
        if (token != generation || reviewedSave && !saveBytesCurrent(token, session, revision)) return;
        String saved = reviewedSave ? replace : store.save(json, preview, replace, () -> token == generation);
        byte[] artifact = reviewedSave ? HologramBlueprintArtifact.encode(json, preview) : store.artifact(saved);
        HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.decode(artifact);
        String actualJsonHash = HologramBlueprintArtifact.hash(actual.json);
        String actualPreviewHash = HologramBlueprintArtifact.hash(actual.png);
        if (!Arrays.equals(json, actual.json)) throw new java.io.IOException("Saved JSON differs from reviewed bytes");
        if (!reviewedSave) store.rememberLocal(serverKey, saved);
        String savedName = reviewedSave ? "" : store.savedName(saved);
        String savedPath = reviewedSave ? ""
            : store.savedPath(saved)
                .toString();
        main(() -> {
            if (token != generation || reviewedSave && !saveSourceCurrent(token, session, revision)) return;
            if (!reviewedSave) {
                selectedLocal = saved;
                announceSaved(savedName, savedPath);
                preparing = false;
                status = "JSON 与结构预览 PNG 已保存至客户端蓝图库";
                refreshLocal();
                return;
            }
            NBTTagCompound acknowledgement = request("ackBlueprintSaved");
            acknowledgement.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            acknowledgement.setString("session", session);
            acknowledgement.setLong("planRevision", revision);
            acknowledgement.setString("localId", saved);
            acknowledgement.setString("saveReceipt", receipt);
            acknowledgement.setString("jsonSha256", actualJsonHash);
            acknowledgement.setString("previewSha256", actualPreviewHash);
            pendingSaveAck = acknowledgement;
            outgoing = artifact;
            outgoingId = UUID.randomUUID()
                .toString();
            outgoingOp = "preview";
            outgoingLocal = saved;
            outgoingQuick = false;
            nextChunk = sentBytes = 0;
            ready = inFlight = false;
            NBTTagCompound begin = request("previewBegin");
            begin.setString("transfer", outgoingId);
            begin.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            begin.setInteger("total", artifact.length);
            begin.setString("hash", HologramBlueprintArtifact.hash(artifact));
            begin.setString("session", session);
            begin.setLong("planRevision", revision);
            begin.setString("saveReceipt", receipt);
            begin.setString("replaceLocalId", saved);
            preparing = false;
            status = "结构预览已生成，正在确认对应 JSON 摘要";
            lastActivity = System.nanoTime();
            saveProgress.progress(lastActivity);
            traceSave("preview_upload_begin");
            HologramNetwork.sendLibrary(begin);
        });
    }

    private static void announceSaved(String name, String path) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(
            new net.minecraft.util.ChatComponentText(
                net.minecraft.util.StatCollector
                    .translateToLocalFormatted("gtmb.hologram.library.saved_path", name, path)));
    }

    private void captureActor() {
        Minecraft mc = Minecraft.getMinecraft();
        actorName = mc.thePlayer == null ? "" : mc.thePlayer.getCommandSenderName();
        actorUuid = mc.thePlayer == null ? ""
            : mc.thePlayer.getUniqueID()
                .toString();
    }

    private interface Job {

        void run() throws Exception;
    }

    private void work(Job job) {
        long token = generation;
        try {
            worker.execute(() -> {
                try {
                    job.run();
                } catch (Exception error) {
                    main(() -> {
                        if (token != generation) return;
                        loading = false;
                        failSave("客户端蓝图库：" + HologramUiText.reason(error.getMessage()), error);
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            loading = false;
            failSave("客户端蓝图库忙，请稍后重试", busy);
        }
    }

    private static void main(Runnable action) {
        Minecraft.getMinecraft()
            .func_152344_a(action);
    }

    private static NBTTagCompound request(String op) {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("op", op);
        return nbt;
    }

    private static String actionLabel(String action) {
        return "place".equals(action) ? "放置" : "edit".equals(action) ? "编辑" : "预览";
    }

    static String bytes(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
            : bytes >= 1024 ? String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0) : bytes + " B";
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }
}
