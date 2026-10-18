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
import com.miaokatze.gtmb.hologram.HologramLibraryDownload;
import com.miaokatze.gtmb.hologram.HologramLibraryProtocol;
import com.miaokatze.gtmb.hologram.HologramNetwork;

/** One bounded disk worker and acknowledged chunks; no JSON or file IO runs on the render/tick thread. */
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
    private String incomingEncoding = "";
    private int incomingDecodedTotal;
    private String incomingPurpose = "", incomingLocal = "", actorName = "", actorUuid = "";
    private String incomingSaveReceipt = "", incomingSaveSession = "";
    private long incomingSaveRevision;
    private final SaveProgress saveProgress = new SaveProgress();
    private final HologramSavePreparationProgress preparationProgress = new HologramSavePreparationProgress();
    private final HologramProgress.Tracker transferProgress = new HologramProgress.Tracker();
    private final HologramProgress.Tracker localCatalogProgress = new HologramProgress.Tracker();
    private final HologramProgress.Tracker serverCatalogProgress = new HologramProgress.Tracker();
    private final HologramProgress.Tracker settingsProgress = new HologramProgress.Tracker();
    private String localProgressPhase = "library.prepare";
    private boolean refreshingServer, deletingServer;
    private int serverCatalogReplies;
    private long pendingConfigRevision = -1;
    private byte[] outgoing;
    private HologramLibraryProtocol.UploadWindow uploadWindow;
    private int nextChunk, sentBytes, incomingTotal, incomingIndex, page, pages = 1;
    private boolean ready, inFlight, loading, preparing;
    private HologramLibraryDownload.Assembly incoming;
    private volatile long generation;
    private long lastActivity;
    private Object libraryWorld, quickTool;
    private String quickToolId = "";
    private int quickSlot;
    private final HologramLibraryQuickState quick = new HologramLibraryQuickState();
    private String quickLocal = "", quickSession = "", observedPublic = "";
    private byte[] quickBytes;
    private NBTTagCompound quickFallback;
    private String quickHash = "";
    private boolean outgoingQuick, downloadExpected, configWritePending, draftPending;
    private boolean incomingCapturePreview;
    private boolean incomingLocalSaveReceipt;
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
    private long backgroundTaskToken;
    private SaveCommand draftSave, cancelledSourceSave;
    private long diagnosticTask;
    private int diagnosticPhases;
    private String diagnosticPhase = "", diagnosticTransfer = "";
    private long downloadHeaderRevision = -1;
    private boolean diagnosticTerminal, downloadBeginObserved, downloadBeginAccepted, downloadDoneObserved,
        downloadDoneAccepted;

    /** The save request remains owned while the server is asking for its AE review. */
    static final class SaveCommand {

        final long task;
        final String session, operation;
        long sequence;

        SaveCommand(long task, String session, long sequence, String operation) {
            this.task = task;
            this.session = session;
            this.sequence = sequence;
            this.operation = operation;
        }

        boolean matches(NBTTagCompound snapshot, long currentTask) {
            return task == currentTask && session.equals(snapshot.getString("session"))
                && task == snapshot.getLong("blueprintClientTaskToken")
                && snapshot.hasKey("uiSequence", 4)
                && sequence == snapshot.getLong("uiSequence")
                && operation.equals(snapshot.getString("blueprintOperation"));
        }

        boolean failed(NBTTagCompound snapshot, long currentTask) {
            return matches(snapshot, currentTask) && snapshot.hasKey("blueprintOperationSuccess", 1)
                && !snapshot.getBoolean("blueprintOperationSuccess")
                && !snapshot.getBoolean("blueprintSavePending")
                && !snapshot.getBoolean("snapshotPending")
                && !snapshot.getBoolean("workspaceUiOnly")
                && !reviewPending(snapshot, task);
        }

        static boolean reviewPending(NBTTagCompound snapshot, long task) {
            NBTTagCompound review = snapshot.getCompoundTag("aeSaveReview");
            return task > 0 && review.getLong("clientTaskToken") == task
                && snapshot.getString("session")
                    .equals(review.getString("session"))
                && snapshot.getLong("planRevision") == review.getLong("planRevision")
                && !review.getString("transactionId")
                    .isEmpty();
        }
    }

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

        void preparation(long now) {
            if (waitingSince != 0 && !waitingExpired(now)) waitingSince = now;
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

    /** A busy admission is inconclusive; resolve the exact staged receipt only once. */
    static final class SaveReceiptResolution {

        private boolean requested;

        boolean requested() {
            return requested;
        }

        boolean begin(NBTTagCompound issued, NBTTagCompound rejected) {
            if (requested || !"ackBlueprintSaved".equals(rejected.getString("op"))) return false;
            for (String field : new String[] { "saveReceipt", "session", "localId", "jsonSha256", "previewSha256" })
                if (issued.getString(field)
                    .isEmpty()
                    || !issued.getString(field)
                        .equals(rejected.getString(field)))
                    return false;
            if (issued.getLong("planRevision") != rejected.getLong("planRevision")
                || rejected.getInteger("artifactVersion") != HologramBlueprintArtifact.VERSION) return false;
            requested = true;
            return true;
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
        observed.setString("incomingEncoding", incomingEncoding);
        observed.setInteger("incomingDecodedTotal", incomingDecodedTotal);
        observed.setBoolean("capturePreview", incomingCapturePreview);
        observed.setBoolean("localSaveReceipt", incomingLocalSaveReceipt);
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
        final long backgroundToken;
        boolean finalizing;
        final SaveReceiptResolution resolution = new SaveReceiptResolution();

        PendingDiskSave(HologramBlueprintArtifact.PairWrite write, NBTTagCompound acknowledgement, String serverKey,
            String name, String path, long backgroundToken) {
            this.write = write;
            this.acknowledgement = (NBTTagCompound) acknowledgement.copy();
            this.serverKey = serverKey;
            this.name = name;
            this.path = path;
            this.backgroundToken = backgroundToken;
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
            refreshingServer = false;
            serverCatalogReplies = 0;
            localCatalogProgress.clear();
            serverCatalogProgress.clear();
            settingsProgress.clear();
            configWritePending = false;
            pendingConfigRevision = -1;
            cancel("蓝图连接已变化", false);
            PendingDiskSave abandoned = pendingDiskSave;
            pendingDiskSave = null;
            if (abandoned != null) finishBackgroundTask(abandoned.backgroundToken, false, "蓝图连接已变化");
            if (abandoned != null) durableWork(() -> {
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
            advanceGeneration();
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
            failTask(
                timedOut ? "source.wait.timeout" : "source.changed",
                timedOut ? "等待对应蓝图状态超时，未保存修改保留" : "蓝图来源已变化，未保存修改保留");
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
            failTask("save.idle.timeout", "蓝图保存无进展超时，未保存修改保留");
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
                && !(outgoingQuick && outgoing != null)
                && quick.snapshotMatches(
                    HologramClient.state.data.getString("quickToken"),
                    backgroundTaskToken,
                    HologramClient.state.data.getLong("quickClientTaskToken"))
                && quick.commitLockedSession(HologramClient.state.session)) {
                quickBytes = null;
                quickFallback = null;
                preparing = false;
                progressCompleted();
                finishBackgroundTask(backgroundTaskToken, true, "蓝图已在原目标位置准备就绪");
            } else if (!sameQuickTool(mc) || !quickScreenAllowed(mc)) cancelQuickPlacement();
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
            boolean stalledPreparation = draftPending && preparationProgress.stalled(System.nanoTime());
            failTask(
                stalledPreparation ? "prepare.stalled" : "transfer.idle.timeout",
                stalledPreparation ? "蓝图准备长时间没有进展，请重试；未保存修改保留" : "传输超时，请重试；未保存修改保留");
        }
        if (backgroundTaskToken > 0) lifecycle("phase", false);
        while (outgoing != null && ready && uploadWindow != null && uploadWindow.canSend()) {
            int index = uploadWindow.nextSent();
            int offset = index * HologramLibraryProtocol.CHUNK_BYTES;
            NBTTagCompound chunk = request(outgoingOp + "Chunk");
            chunk.setString("transfer", outgoingId);
            chunk.setLong("clientTaskToken", uploadWindow.task());
            chunk.setInteger("index", index);
            chunk.setByteArray(
                "data",
                Arrays.copyOfRange(
                    outgoing,
                    offset,
                    Math.min(offset + HologramLibraryProtocol.CHUNK_BYTES, outgoing.length)));
            uploadWindow.sent(index);
            inFlight = uploadWindow.inFlight();
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
        if (!beginBackgroundTask("快速放置蓝图")) return true;
        ensureStore();
        String token = quick.begin(), serverKey = connection;
        quickSession = "";
        Object world = mc.theWorld;
        quickTool = mc.thePlayer.getHeldItem();
        preparing = true;
        localProgressPhase = "library.read";
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
                    || !quickScreenAllowed(current)) {
                    if (quick.matches(token)) cancelQuickPlacement();
                    return;
                }
                if (bytes == null) {
                    if (!source.publicId.isEmpty()) fallback.setString("publicPresetId", source.publicId);
                    quickFallback = fallback;
                } else {
                    quickFallback = null;
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
                localProgressPhase = "library.session";
                lastActivity = System.nanoTime();
                HologramNetwork.sendLibrary(bootstrap);
            });
        });
        return true;
    }

    boolean quickInProgress() {
        return quick.active();
    }

    private boolean quickScreenAllowed(Minecraft mc) {
        return mc.currentScreen == null
            || mc.currentScreen instanceof HologramScreen && HologramBackgroundTasks.libraryTask();
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
        long cancelledTask = backgroundTaskToken;
        String token = quick.cancel();
        NBTTagCompound request = request("quickCancel");
        request.setLong("clientTaskToken", cancelledTask);
        request.setString("quickToken", token);
        if (dedicated()) HologramNetwork.sendLibrary(request);
        if (!quickSession.isEmpty()) HologramClient.dismissRootView(quickSession, 0);
        if (outgoingQuick && outgoing != null) {
            NBTTagCompound cancel = request("cancel");
            cancel.setString("transfer", outgoingId);
            if (dedicated()) HologramNetwork.sendLibrary(cancel);
            outgoing = null;
            uploadWindow = null;
            outgoingId = "";
            ready = inFlight = false;
        }
        advanceGeneration();
        quickBytes = null;
        quickFallback = null;
        quickLocal = quickSession = "";
        outgoingQuick = false;
        preparing = false;
        finishBackgroundTask(cancelledTask, false, "快速放置已取消");
        status = "快捷放置已取消";
        return true;
    }

    void refreshLocal() {
        if (store == null || loading) return;
        loading = true;
        long token = generation;
        String operation = "local:" + token;
        localCatalogProgress.update(operation, "library.index", 0, 0, "", false, System.nanoTime());
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
            main(() -> finishLocalRefresh(token, operation, labels));
        });
    }

    private void finishLocalRefresh(long token, String operation, NBTTagList labels) {
        if (token != generation) return;
        local = labels;
        if (selectedRow(false).getString("id")
            .isEmpty()) selectedLocal = "";
        loading = false;
        localCatalogProgress.finish(operation, System.nanoTime());
    }

    private void advanceGeneration() {
        generation++;
        worker.getQueue()
            .removeIf(task -> task instanceof QueuedWork && !((QueuedWork) task).durable);
        loading = false;
        localCatalogProgress.clear();
    }

    void refreshServer(int value) {
        page = Math.max(0, value);
        refreshingServer = true;
        serverCatalogReplies++;
        serverCatalogProgress.update("server:" + generation, "library.refresh", 0, 0, "", false, System.nanoTime());
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

    void clearSelection() {
        selectedLocal = selectedServer = "";
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
        return outgoing != null || incoming != null
            || loading
            || preparing
            || pendingDiskSave != null
            || quick.active();
    }

    int progressBytes() {
        return outgoing != null ? sentBytes : incoming == null ? 0 : incoming.size();
    }

    int totalBytes() {
        return outgoing != null ? outgoing.length : incomingTotal;
    }

    HologramProgress progress() {
        long now = System.nanoTime();
        String identity = "library:" + generation;
        if (pendingDiskSave != null) transferProgress.update(
            identity,
            pendingDiskSave.finalizing ? localProgressPhase
                : pendingDiskSave.resolution.requested() ? "library.resolve" : "library.confirm",
            0,
            0,
            "",
            false,
            now);
        else if (previewCapture != null) transferProgress.update(identity, "library.preview", 0, 0, "", false, now);
        else if (pendingPreviewStart != null) {
            HologramProgress observed = preparationProgress.view(now);
            if (observed != null) return observed;
            transferProgress.update(identity, "library.source", 0, 0, "", false, now);
        } else if (incoming != null)
            transferProgress.update(identity, "library.download", incoming.size(), incomingTotal, "bytes", false, now);
        else if (outgoing != null) transferProgress.update(
            identity,
            sentBytes >= outgoing.length ? "library.verify" : "library.upload",
            sentBytes >= outgoing.length ? 0 : sentBytes,
            sentBytes >= outgoing.length ? 0 : outgoing.length,
            "bytes",
            false,
            now);
        else if (downloadExpected || draftPending) {
            HologramProgress observed = preparationProgress.view(now);
            if (observed != null) return observed;
            transferProgress.update(identity, "library.prepare", 0, 0, "", false, now);
        } else if (preparing) transferProgress.update(identity, localProgressPhase, 0, 0, "", false, now);
        else if (quick.active()) transferProgress.update(identity, "library.session", 0, 0, "", false, now);
        else if (deletingServer) transferProgress.update(identity, "library.delete", 0, 0, "", false, now);
        HologramProgress transfer = transferProgress.view(now);
        if (transfer != null && !transfer.complete
            && !busy()
            && !downloadExpected
            && !draftPending
            && !quick.active()
            && !deletingServer) {
            transferProgress.clear();
            transfer = null;
        }
        if (transfer != null && !transfer.complete) return transfer;
        if (configWritePending) return settingsProgress.view(now);
        if (loading) return localCatalogProgress.view(now);
        if (refreshingServer) return serverCatalogProgress.view(now);
        HologramProgress catalog = localCatalogProgress.view(now), serverCatalog = serverCatalogProgress.view(now),
            settings = settingsProgress.view(now);
        if (catalog != null && !catalog.complete) catalog = null;
        if (serverCatalog != null && !serverCatalog.complete) serverCatalog = null;
        if (settings != null && !settings.complete) settings = null;
        if (serverCatalog != null && (catalog == null || serverCatalog.observedAt > catalog.observedAt))
            catalog = serverCatalog;
        if (catalog != null && (transfer == null || catalog.observedAt > transfer.observedAt)) transfer = catalog;
        if (settings != null && (transfer == null || settings.observedAt > transfer.observedAt)) transfer = settings;
        return transfer;
    }

    private void progressCompleted() {
        transferProgress.update("library:" + generation, "library.complete", 1, 1, "steps", true, System.nanoTime());
    }

    private boolean beginBackgroundTask(String label) {
        if (backgroundTaskToken != 0) return true;
        backgroundTaskToken = HologramBackgroundTasks.beginLibrary(label);
        if (backgroundTaskToken != 0) {
            cancelledSourceSave = null;
            resetLifecycle();
        }
        return backgroundTaskToken != 0;
    }

    long backgroundTaskToken() {
        return backgroundTaskToken;
    }

    private void resetLifecycle() {
        diagnosticTask = backgroundTaskToken;
        diagnosticPhases = 0;
        diagnosticPhase = diagnosticTransfer = "";
        downloadHeaderRevision = -1;
        diagnosticTerminal = false;
        downloadBeginObserved = downloadBeginAccepted = downloadDoneObserved = downloadDoneAccepted = false;
    }

    /** One task emits at most 24 phase transitions and one terminal observation, without blueprint payloads. */
    private void lifecycle(String outcome, boolean terminal) {
        if (backgroundTaskToken == 0) return;
        if (diagnosticTask != backgroundTaskToken) resetLifecycle();
        HologramProgress observed = progress();
        String phase = observed == null ? localProgressPhase : observed.phase;
        String detail = observed == null ? "" : observed.detail;
        String key = phase + ":" + detail;
        if (terminal) {
            if (diagnosticTerminal) return;
            diagnosticTerminal = true;
        } else {
            if (diagnosticTerminal || key.equals(diagnosticPhase) || diagnosticPhases >= 24) return;
            diagnosticPhase = key;
            diagnosticPhases++;
        }
        long now = System.nanoTime();
        HologramState source = HologramClient.state;
        com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info(
            "Blueprint lifecycle task=" + backgroundTaskToken
                + " outcome="
                + outcome
                + " phase="
                + phase
                + " detail="
                + detail
                + " done="
                + (observed == null ? 0 : observed.done)
                + "/"
                + (observed == null ? 0 : observed.total)
                + " received="
                + (incoming == null ? downloadDoneAccepted ? incomingTotal : 0 : incoming.size())
                + "/"
                + (downloadBeginAccepted ? incomingTotal : 0)
                + " sent="
                + sentBytes
                + "/"
                + (outgoing == null ? 0 : outgoing.length)
                + " idleMs="
                + (lastActivity == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(now - lastActivity))
                + " saveIdleMs="
                + (saveProgress.progressedAt == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(now - saveProgress.progressedAt))
                + " transfer="
                + (!outgoingId.isEmpty() ? outgoingId : incomingId.isEmpty() ? diagnosticTransfer : incomingId)
                + " begin="
                + downloadBeginObserved
                + "/"
                + downloadBeginAccepted
                + " donePacket="
                + downloadDoneObserved
                + "/"
                + downloadDoneAccepted
                + " sourceRevision="
                + (source == null ? -1 : source.data.getLong("planRevision"))
                + "/"
                + (downloadBeginObserved ? downloadHeaderRevision : -1)
                + " sourceUi="
                + (source == null ? -1 : source.data.getLong("uiSequence"))
                + " saveUi="
                + (draftSave == null ? -1 : draftSave.sequence)
                + " waitingSource="
                + (pendingPreviewStart != null)
                + " commitPending="
                + (pendingDiskSave != null)
                + " finalizing="
                + (pendingDiskSave != null && pendingDiskSave.finalizing));
    }

    /** Preserve the reason and observed phase before retiring the transport and owner. */
    void failTask(String outcome, String reason) {
        HologramProgress observed = progress();
        String phase = observed == null ? localProgressPhase : observed.phase;
        lifecycle(outcome, true);
        cancel(HologramUiText.reason(reason) + " [" + HologramProgressView.phase(phase) + "]", false);
    }

    private void finishBackgroundTask(long token, boolean success, String detail) {
        finishBackgroundTask(token, success, detail, false);
    }

    private void finishBackgroundTask(long token, boolean success, String detail, boolean cancelled) {
        if (token == 0 || token != backgroundTaskToken) return;
        SaveCommand completed = draftSave;
        lifecycle(success ? "success" : cancelled ? "cancelled" : "failure", true);
        preparationProgress.clear();
        backgroundTaskToken = 0;
        draftSave = null;
        HologramBackgroundTasks.finishLibrary(token, success, detail, cancelled);
        Minecraft mc = Minecraft.getMinecraft();
        if (completed != null && mc != null && mc.currentScreen instanceof HologramScreen screen)
            screen.ui.session.libraryTaskFinished(completed.session, completed.sequence, token, detail);
    }

    void saveCommand(String session, long sequence, String operation) {
        if (!draftPending || backgroundTaskToken == 0) return;
        if (diagnosticTask != backgroundTaskToken) resetLifecycle();
        preparationProgress.clear();
        lastActivity = System.nanoTime();
        if ("confirmAeSave".equals(operation)) {
            if (draftSave != null && draftSave.task == backgroundTaskToken
                && draftSave.session.equals(session)
                && sequence > draftSave.sequence) draftSave.sequence = sequence;
        } else if (saveOperation(operation))
            draftSave = new SaveCommand(backgroundTaskToken, session, sequence, operation);
    }

    static boolean saveOperation(String operation) {
        return "saveBlueprint".equals(operation) || "saveBlueprintAs".equals(operation)
            || "saveBlueprintReplace".equals(operation)
            || "savePreset".equals(operation)
            || "updatePreset".equals(operation)
            || "exportDraft".equals(operation);
    }

    /** Called on the client thread; progress cannot acknowledge or complete a save. */
    void preparationProgress(NBTTagCompound event) {
        HologramState state = HologramClient.state;
        boolean awaitingSource = incomingCapturePreview && !incomingSaveReceipt.isEmpty()
            && pendingDiskSave == null
            && (incoming != null || pendingPreviewStart != null);
        if (!(draftPending && downloadExpected || awaitingSource) || draftSave == null
            || state == null
            || !draftSave.session.equals(state.session)) return;
        long now = System.nanoTime();
        if (preparationProgress.receive(
            event,
            backgroundTaskToken,
            draftSave.session,
            draftSave.sequence,
            awaitingSource ? incomingSaveRevision : state.data.getLong("planRevision"),
            now)) {
            lastActivity = now;
            saveProgress.preparation(now);
        }
    }

    /** Snapshot failures are terminal only for their exact save capability, including detached screens. */
    boolean transportFailed(NBTTagCompound event) {
        if (pendingDiskSave != null || draftSave == null
            || backgroundTaskToken == 0
            || event.getLong("clientTaskToken") != backgroundTaskToken
            || !draftSave.session.equals(event.getString("session"))
            || !event.hasKey("uiSequence", 4)
            || draftSave.sequence != event.getLong("uiSequence")) return false;
        String message = HologramBackgroundTasks.transportFailureMessage(event);
        cancel(message, false);
        return true;
    }

    /** Installed snapshots reach the task owner even when its business screen is closed. */
    boolean snapshot(HologramState next) {
        if (!draftPending || draftSave == null) return false;
        if (draftSave.failed(next.data, backgroundTaskToken)) {
            String message = next.data.getString("status");
            failDraftReview(message.isEmpty() ? "蓝图保存失败，请保持当前蓝图并重试" : message);
            return false;
        }
        return draftSave.matches(next.data, backgroundTaskToken)
            && SaveCommand.reviewPending(next.data, backgroundTaskToken);
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
        if (!beginBackgroundTask("保存蓝图")) return;
        NBTTagCompound request = draftRequest(state, options, replace);
        preparing = true;
        draftPending = true;
        downloadExpected = true;
        saveCommand(state.session, state.data.getLong("uiSequence"), "exportDraft");
        saveProgress.clear();
        lastActivity = System.nanoTime();
        captureActor();
        status = "正在准备蓝图，确认后下载至客户端";
        HologramNetwork.sendLibrary(request);
    }

    /** The actual Save request envelope overrides caller options after task ownership has been acquired. */
    NBTTagCompound draftRequest(HologramState state, NBTTagCompound options, boolean replace) {
        if (backgroundTaskToken <= 0) throw new IllegalStateException("Blueprint save requires an active task token");
        NBTTagCompound request = (NBTTagCompound) options.copy();
        request.setString("op", "exportDraft");
        request.setLong("clientTaskToken", backgroundTaskToken);
        request.setString("session", state.session);
        request.setLong("planRevision", state.data.getLong("planRevision"));
        String replaceId = replace && state.data.getBoolean("virtual") ? state.data.getString("libraryLocalId") : "";
        request.setString("localId", replaceId);
        request.setString("replaceLocalId", replaceId);
        return request;
    }

    void cancelDraftReview() {
        if (!draftPending || incoming != null) return;
        cancel();
    }

    void failDraftReview(String message) {
        if (!draftPending || incoming != null) return;
        cancel(message, false);
    }

    void expectDraftDownload() {
        if (!beginBackgroundTask("保存蓝图")) return;
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
        if (!beginBackgroundTask(upload ? "上传蓝图" : "读取蓝图")) return;
        String id = selectedLocal;
        long token = generation;
        String targetServer = connection;
        preparing = true;
        localProgressPhase = "library.read";
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
                uploadWindow = new HologramLibraryProtocol.UploadWindow(begin);
                HologramNetwork.sendLibrary(begin);
            });
        });
    }

    void downloadSelected() {
        if (busy() || selectedServer.isEmpty()) return;
        if (!beginBackgroundTask("下载蓝图")) return;
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
            deletingServer = true;
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
        pendingConfigRevision = config.getLong("configRevision");
        settingsProgress
            .update("config:" + config.getLong("configRevision"), "settings", 0, 0, "", false, System.nanoTime());
        HologramNetwork.sendLibrary(request);
        status = "等待服务器确认设置";
    }

    /** A staged pair stays owned until the matching server receipt resolves commit versus rollback. */
    boolean cancelBackgroundTask() {
        cancel();
        return pendingDiskSave == null;
    }

    void cancel() {
        cancel("蓝图任务已取消", true);
    }

    private void cancel(String reason, boolean cancelled) {
        long cancelledTask = backgroundTaskToken;
        SaveCommand cancelledCommand = draftSave;
        if (cancelledCommand != null) cancelledSourceSave = new SaveCommand(
            cancelledCommand.task,
            cancelledCommand.session,
            cancelledCommand.sequence,
            cancelledCommand.operation);
        if (pendingDiskSave == null) finishBackgroundTask(backgroundTaskToken, false, reason, cancelled);
        transferProgress.clear();
        localProgressPhase = "library.prepare";
        deletingServer = false;
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
        if (activeTransfer && cancelledTask > 0 || !outgoingId.isEmpty()
            || !incomingId.isEmpty()
            || activeTransfer && !incomingSaveReceipt.isEmpty()
            || pendingDiskSave != null) {
            NBTTagCompound request = pendingDiskSave == null ? request("cancel")
                : (NBTTagCompound) pendingDiskSave.acknowledgement.copy();
            request.setString("op", "cancel");
            request.setLong("clientTaskToken", cancelledTask);
            request.setString("transfer", outgoing != null ? outgoingId : incomingId);
            if (cancelledCommand != null) {
                request.setString("session", cancelledCommand.session);
                request.setLong("uiSequence", cancelledCommand.sequence);
            }
            if (pendingDiskSave == null && activeTransfer && !incomingSaveReceipt.isEmpty()) {
                request.setString("session", incomingSaveSession);
                request.setLong("planRevision", incomingSaveRevision);
                request.setString("saveReceipt", incomingSaveReceipt);
                request.setString("replaceLocalId", incomingLocal);
            }
            HologramNetwork.sendLibrary(request);
            lastSaveStatus = System.nanoTime();
        }
        advanceGeneration();
        saveProgress.clear();
        downloadExpected = false;
        draftPending = false;
        outgoing = null;
        uploadWindow = null;
        if (incoming != null) incoming.cancel();
        incoming = null;
        outgoingId = incomingId = "";
        ready = inFlight = preparing = false;
        if (pendingDiskSave != null) preparing = true;
        status = activeTransfer ? reason : "";
    }

    private void receive(NBTTagCompound reply) {
        String op = reply.getString("op");
        if ("sourceCancelled".equals(op)) {
            SaveCommand cancelled = cancelledSourceSave;
            if (cancelled == null || backgroundTaskToken != 0
                || cancelled.task != reply.getLong("clientTaskToken")
                || !cancelled.session.equals(reply.getString("session"))
                || !reply.hasKey("uiSequence", 4)
                || cancelled.sequence != reply.getLong("uiSequence")) return;
            cancelledSourceSave = null;
            HologramClient.saveSourceCancelled(reply);
            return;
        }
        if ("preparationProgress".equals(op)) {
            preparationProgress(reply);
            return;
        }
        if (("error".equals(op) || "downloadBegin".equals(op)
            || "downloadChunk".equals(op)
            || "downloadDone".equals(op)) && !matchesTransferTask(reply)) return;
        if (!("chunkAck".equals(op) || "uploadReady".equals(op)
            || "privateReady".equals(op)
            || "previewReady".equals(op)
            || "uploaded".equals(op)
            || "privateImported".equals(op)
            || "previewVerified".equals(op)
            || "downloadBegin".equals(op)
            || "downloadChunk".equals(op)
            || "downloadDone".equals(op)) && !(draftPending && ("list".equals(op) || "config".equals(op))))
            lastActivity = System.nanoTime();
        if ("saveCommitted".equals(op) || "saveRejected".equals(op)) {
            finishDiskSave(reply, "saveCommitted".equals(op));
        } else if ("quickBootstrap".equals(op)) {
            String token = reply.getString("quickToken"), session = reply.getString("session");
            if (!matchesTransferTask(reply) || !quick.matches(token)
                || quickBytes == null && quickFallback == null
                || session.isEmpty()
                || !reply.hasKey("planRevision", 4)
                || !quick.bootstrap(token, session)) return;
            quickSession = session;
            if (quickFallback != null) {
                NBTTagCompound fallback = (NBTTagCompound) quickFallback.copy();
                fallback.setString("op", "quickFallback");
                fallback.setString("session", session);
                fallback.setLong("planRevision", reply.getLong("planRevision"));
                fallback.setString("quickToken", token);
                fallback.setLong("clientTaskToken", backgroundTaskToken);
                fallback.setBoolean("quickPlace", true);
                fallback.setString("action", "place");
                preparing = true;
                localProgressPhase = "library.session";
                HologramNetwork.sendLibrary(fallback);
                return;
            }
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
            uploadWindow = new HologramLibraryProtocol.UploadWindow(begin);
            HologramNetwork.sendLibrary(begin);
        } else if ("quickPlacement".equals(op)) {
            if (!matchesTransferTask(reply) || !quick.placement(
                reply.getString("quickToken"),
                reply.getString("previousSession"),
                reply.getString("session"))) return;
            quickSession = reply.getString("session");
            quickFallback = null;
        } else if ("queued".equals(op)) {
            status = reply.getString("message");
        } else if ("list".equals(op)) {
            server = reply.getTagList("entries", 10);
            page = reply.getInteger("page");
            pages = Math.max(1, reply.getInteger("pages"));
            serverCatalogReplies = Math.max(0, serverCatalogReplies - 1);
            refreshingServer = serverCatalogReplies > 0;
            if (!refreshingServer) serverCatalogProgress.finish(System.nanoTime());
            if (reply.hasKey("admin")) {
                config.setBoolean("admin", reply.getBoolean("admin"));
                config.setBoolean("readOnly", !reply.getBoolean("admin"));
            }
        } else if ("config".equals(op)) {
            boolean confirmsWrite = configWritePending && (!reply.getString("libraryError")
                .isEmpty() || reply.getLong("configRevision") > pendingConfigRevision);
            if (confirmsWrite && reply.getString("libraryError")
                .isEmpty()) settingsProgress.finish(System.nanoTime());
            else if (confirmsWrite) settingsProgress.clear();
            config = (NBTTagCompound) reply.copy();
            if (!config.getString("libraryError")
                .isEmpty()) status = "服务器蓝图库只读：" + config.getString("libraryError");
            else if (confirmsWrite) status = "服务器设置已确认 · 修订 " + config.getLong("configRevision");
            if (confirmsWrite) configWritePending = false;
        } else if ("uploadReady".equals(op) || "privateReady".equals(op) || "previewReady".equals(op)) {
            if (!matchesOutgoing(reply) || uploadWindow == null) return;
            try {
                if (uploadWindow.ready(reply)) {
                    ready = true;
                    lastActivity = System.nanoTime();
                }
            } catch (java.io.IOException invalid) {
                failSave(invalid.getMessage(), invalid);
            }
        } else if ("previewVerified".equals(op)) {
            if (!matchesOutgoing(reply) || !completedUpload(reply)
                || pendingSaveAck == null
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
            uploadWindow = null;
            outgoingId = "";
            ready = inFlight = false;
            preparing = true;
            status = "结构预览已确认，正在原子保存 JSON 与 PNG";
            work(() -> {
                HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.decode(verifiedArtifact);
                String session = acknowledgement.getString("session");
                long revision = acknowledgement.getLong("planRevision");
                HologramClientLibrary.LocalSave local = store.prepareLocalSave(
                    artifact.json,
                    artifact.png,
                    saved,
                    () -> saveBytesCurrent(token, session, revision));
                try {
                    if (!local.jsonHash.equals(acknowledgement.getString("jsonSha256"))
                        || !local.pngHash.equals(acknowledgement.getString("previewSha256")))
                        throw new java.io.IOException("Actual saved artifact differs from verified bytes");
                } catch (Exception failure) {
                    try {
                        local.write.rollback();
                    } catch (Exception rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
                publishDiskSave(local, acknowledgement, serverKey, token);
            });
        } else if ("chunkAck".equals(op)) {
            if (!matchesOutgoing(reply)) return;
            int previousBytes = sentBytes;
            if (uploadWindow == null) return;
            try {
                if (!uploadWindow.acknowledge(reply)) return;
            } catch (java.io.IOException invalid) {
                failSave(invalid.getMessage(), invalid);
                return;
            }
            lastActivity = System.nanoTime();
            nextChunk = uploadWindow.acknowledged();
            sentBytes = Math.min(outgoing.length, nextChunk * HologramLibraryProtocol.CHUNK_BYTES);
            if (pendingSaveAck != null && sentBytes > previousBytes) {
                saveProgress.progress(System.nanoTime());
                int marker = (int) ((long) sentBytes * 4 / outgoing.length);
                if (marker > uploadProgressMarker && marker < 4) {
                    uploadProgressMarker = marker;
                    traceSave("preview_upload_progress_" + marker);
                }
            }
            inFlight = uploadWindow.inFlight();
        } else if ("uploaded".equals(op) || "privateImported".equals(op)) {
            if (!matchesOutgoing(reply) || !completedUpload(reply)) return;
            long completedTask = backgroundTaskToken;
            boolean quickUpload = outgoingQuick;
            boolean uploaded = "uploaded".equals(op);
            outgoing = null;
            uploadWindow = null;
            outgoingId = "";
            ready = inFlight = false;
            status = uploaded ? "已上传至服务器蓝图库" : "蓝图已传输，服务器正在打开预览";
            progressCompleted();
            if (uploaded) {
                selectedServer = reply.getString("id");
                String serverKey = connection, localId = outgoingLocal, publicId = selectedServer;
                work(() -> {
                    store.uploadedId(serverKey, localId, publicId);
                    main(() -> finishBackgroundTask(completedTask, true, "蓝图已上传"));
                });
                refreshServer(page);
            } else {
                String serverKey = connection, localId = outgoingLocal;
                work(() -> {
                    store.rememberLocal(serverKey, localId);
                    if (!quickUpload) main(() -> finishBackgroundTask(completedTask, true, "蓝图已载入"));
                });
                status = outgoingQuick ? "上一张客户端蓝图已进入世界定位" : "蓝图已传输，服务器正在打开预览";
                outgoingQuick = false;
                if (quickUpload && quick.active()) preparing = true;
            }
        } else if ("deleted".equals(op)) {
            deletingServer = false;
            progressCompleted();
            String serverKey = connection, publicId = reply.getString("id");
            if (store != null) work(() -> store.forgetUpload(serverKey, publicId));
            status = "自己的上传已删除";
            selectedServer = "";
            refreshServer(page);
        } else if ("downloadBegin".equals(op)) {
            downloadBeginObserved = true;
            downloadHeaderRevision = reply.getLong("planRevision");
            boolean nativeDraft = HologramLibraryDownload.taskMatches(backgroundTaskToken, reply)
                && reply.getBoolean("capturePreview")
                && !reply.getString("saveReceipt")
                    .isEmpty()
                && saveSourceAwaiting(generation, reply.getString("session"), reply.getLong("planRevision"));
            if (reply.getBoolean("capturePreview") && !nativeDraft) {
                failTask("download.source.rejected", "保存内容来源已变化，未保存修改保留，请重试");
                return;
            }
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
                failTask("download.header.invalid", "下载长度无效");
                return;
            }
            String encoding = reply.getString("encoding");
            int decodedTotal = reply.getInteger("decodedTotal");
            try {
                HologramLibraryDownload
                    .header(reply.getString("transfer"), total, encoding, decodedTotal, reply.getString("hash"));
                if (capturePreview && decodedTotal > HologramClientLibrary.MAX_BYTES) throw new java.io.IOException(
                    "Decoded blueprint exceeds " + HologramClientLibrary.MAX_BYTES / (1024 * 1024) + " MiB");
                incoming = new HologramLibraryDownload.Assembly(total);
            } catch (java.io.IOException invalid) {
                failTask("download.invalid", HologramUiText.reason(invalid.getMessage()));
                return;
            }
            downloadBeginAccepted = true;
            lastActivity = System.nanoTime();
            downloadDoneObserved = downloadDoneAccepted = false;
            incomingEncoding = encoding;
            incomingDecodedTotal = decodedTotal;
            incomingTotal = total;
            incomingIndex = 0;
            preparing = false;
            incomingId = reply.getString("transfer");
            diagnosticTransfer = incomingId;
            incomingHash = reply.getString("hash");
            incomingPurpose = reply.getString("purpose");
            incomingLocal = reply.getString("localId");
            incomingSaveReceipt = reply.getString("saveReceipt");
            incomingSaveSession = reply.getString("session");
            incomingSaveRevision = reply.getLong("planRevision");
            incomingCapturePreview = reply.getBoolean("capturePreview");
            incomingLocalSaveReceipt = incomingCapturePreview && reply.getBoolean("localSaveReceipt");
            if (incomingCapturePreview) {
                saveProgress.begin(
                    System.nanoTime(),
                    HologramClient.state.data.getLong("planRevision") != incomingSaveRevision);
                saveTransitions = new NBTTagList();
                saveWitness = new NBTTagCompound();
                recordSaveWitness("downloadBeginReply", reply);
                saveTraceEvents = downloadProgressMarker = uploadProgressMarker = 0;
                traceSave("download_begin");
            } else saveProgress.clear();
            status = "下载中 · 等待服务器分片";
        } else if ("downloadChunk".equals(op)) {
            if (incoming == null || !incomingId.equals(reply.getString("transfer"))) return;
            byte[] bytes = reply.getByteArray("data");
            try {
                incoming.append(reply.getInteger("index"), bytes);
            } catch (java.io.IOException invalid) {
                failTask("download.chunk.invalid", "下载分片无效，请重试");
                return;
            }
            incomingIndex++;
            lastActivity = System.nanoTime();
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
            downloadDoneObserved = true;
            if (incoming == null || !incomingId.equals(reply.getString("transfer"))) return;
            byte[] encoded;
            try {
                encoded = incoming.finish();
            } catch (java.io.IOException invalid) {
                failTask("download.invalid", HologramUiText.reason(invalid.getMessage()));
                return;
            }
            downloadDoneAccepted = true;
            lastActivity = System.nanoTime();
            String encoding = incomingEncoding;
            int decodedTotal = incomingDecodedTotal;
            String expected = incomingHash;
            String replace = "draft".equals(incomingPurpose) ? incomingLocal : "";
            String saveReceipt = incomingSaveReceipt, saveSession = incomingSaveSession;
            boolean capturePreview = incomingCapturePreview;
            boolean localSaveReceipt = incomingLocalSaveReceipt;
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
            localProgressPhase = "library.parse";
            status = "正在校验并保存客户端蓝图";
            work(() -> {
                if (token != generation) return;
                if (encoded.length != expectedTotal) throw new java.io.IOException("下载校验失败");
                byte[] bytes = HologramLibraryDownload.decode(encoded, encoding, decodedTotal, expected);
                if (token != generation) return;
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
                                    serverKey,
                                    localSaveReceipt),
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
            cancel("服务器：" + HologramUiText.reason(reply.getString("message")), false);
            refreshingServer = false;
            serverCatalogReplies = 0;
            localCatalogProgress.clear();
            serverCatalogProgress.clear();
            settingsProgress.clear();
            configWritePending = false;
            status = "服务器：" + HologramUiText.reason(reply.getString("message"));
        }
    }

    private boolean completedUpload(NBTTagCompound reply) {
        try {
            if (uploadWindow == null || !uploadWindow.complete(reply)) return false;
            lastActivity = System.nanoTime();
            return true;
        } catch (java.io.IOException invalid) {
            failSave(invalid.getMessage(), invalid);
            return false;
        }
    }

    private boolean matchesOutgoing(NBTTagCompound reply) {
        return matchesTransferTask(reply) && outgoing != null
            && (!reply.hasKey("transfer") || outgoingId.equals(reply.getString("transfer")));
    }

    private boolean matchesTransferTask(NBTTagCompound reply) {
        return backgroundTaskToken == 0 ? reply.getLong("clientTaskToken") == 0
            : HologramLibraryDownload.taskMatches(backgroundTaskToken, reply);
    }

    void transportRejectedSave(NBTTagCompound event) {
        if (!"ackBlueprintSaved".equals(event.getString("op"))) return;
        PendingDiskSave pending = pendingDiskSave;
        if (pending == null || pending.finalizing || !pending.serverKey.equals(connection)) return;
        if (!pending.resolution.begin(pending.acknowledgement, event)) return;
        // A denied duplicate ACK does not decide whether an earlier ACK already committed.
        // Existing cancel keeps the pair staged until the server resolves committed versus rejected.
        cancel(HologramProgressView.phase("library.resolve"), false);
        status = HologramProgressView.phase("library.resolve");
    }

    private void finishDiskSave(NBTTagCompound reply, boolean committed) {
        PendingDiskSave pending = pendingDiskSave;
        if (pending == null || pending.finalizing || !pending.serverKey.equals(connection)) return;
        NBTTagCompound acknowledgement = pending.acknowledgement;
        if (pending.backgroundToken > 0 && !HologramLibraryDownload.taskMatches(pending.backgroundToken, reply)) return;
        if (acknowledgement.getLong("clientTaskToken") != reply.getLong("clientTaskToken")) return;
        for (String field : new String[] { "saveReceipt", "session", "localId", "jsonSha256", "previewSha256" })
            if (!acknowledgement.getString(field)
                .equals(reply.getString(field))) return;
        if (acknowledgement.getLong("planRevision") != reply.getLong("planRevision")
            || reply.getInteger("artifactVersion") != HologramBlueprintArtifact.VERSION) return;
        recordSaveWitness(committed ? "saveCommittedReply" : "saveRejectedReply", reply);
        traceSave(committed ? "owner_committed" : "owner_rejected");
        pending.finalizing = true;
        preparing = true;
        localProgressPhase = committed ? "library.commit" : "library.rollback";
        durableWork(() -> {
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
                    progressCompleted();
                    selectedLocal = acknowledgement.getString("localId");
                    announceSaved(pending.name, pending.path);
                    HologramUiLibrary.committedSave(acknowledgement.getString("session"));
                    status = "JSON 与结构预览 PNG 已校验保存";
                } else status = "蓝图保存未提交，原 JSON 与 PNG 已恢复：" + HologramUiText.reason(reply.getString("message"));
                finishBackgroundTask(
                    pending.backgroundToken,
                    committed,
                    committed ? "蓝图已保存" : HologramUiText.reason(reply.getString("message")));
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
            && Minecraft.getMinecraft().thePlayer != null
            && Minecraft.getMinecraft().thePlayer.getUniqueID()
                .equals(libraryActor)
            && current != null
            && session.equals(current.session)
            && current.data.getLong("planRevision") <= revision;
    }

    private void failSave(String message, Throwable failure) {
        if (pendingDiskSave != null) pendingDiskSave.finalizing = false;
        failTask(
            failure == null ? "save.failure"
                : "save." + failure.getClass()
                    .getSimpleName(),
            message);
    }

    /** The network-published state is volatile; an IO commit never waits for the next client tick to see edits. */
    private boolean saveBytesCurrent(long token, String session, long revision) {
        HologramState current = HologramClient.state;
        return token == generation && current != null
            && session.equals(current.session)
            && current.data.getLong("planRevision") == revision;
    }

    private void persistPreview(byte[] json, BufferedImage image, String replace, String receipt, String session,
        long revision, long token, String serverKey, boolean localSaveReceipt) {
        long originalBackgroundToken = backgroundTaskToken;
        previewCapture = null;
        localProgressPhase = "library.disk";
        if (!saveSourceCurrent(token, session, revision)) return;
        saveProgress.progress(System.nanoTime());
        traceSave("preview_rendered");
        if (Boolean.getBoolean("gtmb.hologram.previewCaptureWitness")) {
            NBTTagCompound capture = HologramBlueprintPreview.lastCaptureWitness();
            recordSaveWitness("nativePreviewWitness", capture);
        }
        work(() -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new java.io.IOException("PNG encoder unavailable");
            byte[] preview = output.toByteArray();
            if (localSaveReceipt && !receipt.isEmpty()) {
                if (!saveBytesCurrent(token, session, revision)) return;
                HologramClientLibrary.LocalSave local = store
                    .prepareLocalSave(json, preview, replace, () -> saveBytesCurrent(token, session, revision));
                NBTTagCompound acknowledgement = request("ackBlueprintSaved");
                acknowledgement.setLong("clientTaskToken", originalBackgroundToken);
                acknowledgement.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
                acknowledgement.setString("session", session);
                acknowledgement.setLong("planRevision", revision);
                acknowledgement.setString("localId", replace);
                acknowledgement.setString("saveReceipt", receipt);
                acknowledgement.setString("jsonSha256", local.jsonHash);
                acknowledgement.setString("previewSha256", local.pngHash);
                acknowledgement.setBoolean("localSaveReceipt", true);
                publishDiskSave(local, acknowledgement, serverKey, token);
            } else persistArtifact(json, preview, replace, receipt, session, revision, token, serverKey);
        });
    }

    private void publishDiskSave(HologramClientLibrary.LocalSave local, NBTTagCompound acknowledgement,
        String serverKey, long token) {
        long originalBackgroundToken = backgroundTaskToken;
        String session = acknowledgement.getString("session");
        long revision = acknowledgement.getLong("planRevision");
        main(() -> {
            if (!saveSourceCurrent(token, session, revision)) {
                durableWork(() -> {
                    try {
                        local.write.rollback();
                    } catch (Exception failure) {
                        com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                            .warn("Cancelled blueprint pair rollback failed", failure);
                    }
                });
                if (token == generation) failTask("source.changed", "蓝图来源已变化，未保存修改保留");
                return;
            }
            pendingDiskSave = new PendingDiskSave(
                local.write,
                acknowledgement,
                serverKey,
                local.name,
                local.path,
                originalBackgroundToken);
            preparing = true;
            lastSaveStatus = System.nanoTime();
            if (acknowledgement.getBoolean("localSaveReceipt")) {
                NBTTagCompound readback = (NBTTagCompound) acknowledgement.copy();
                readback.setString("path", local.path);
                readback.setInteger("jsonBytes", local.jsonBytes);
                readback.setInteger("pngBytes", local.pngBytes);
                recordSaveWitness("localPairReadback", readback);
                traceSave("local_pair_readback");
            }
            HologramNetwork.sendAction(acknowledgement);
            recordSaveWitness("ownerDiskAck", acknowledgement);
            traceSave("owner_disk_ack");
            status = "JSON 与 PNG 已写入，正在确认蓝图库索引";
        });
    }

    /** Native saves are staged until the server validates their source-bound PNG; downloads retain supplied pixels. */
    private void persistArtifact(byte[] json, byte[] preview, String replace, String receipt, String session,
        long revision, long token, String serverKey) throws Exception {
        long originalBackgroundToken = backgroundTaskToken;
        boolean reviewedSave = !receipt.isEmpty();
        if (token != generation || reviewedSave && !saveBytesCurrent(token, session, revision)) return;
        String saved = reviewedSave ? replace : store.save(json, preview, replace, () -> token == generation);
        byte[] artifact = reviewedSave ? HologramBlueprintArtifact.encode(json, preview) : store.artifact(saved);
        HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.decode(artifact);
        String actualJsonHash = HologramBlueprintArtifact.hash(actual.json);
        String actualPreviewHash = HologramBlueprintArtifact.hash(actual.png);
        if (!Arrays.equals(json, actual.json)) throw new java.io.IOException("Saved JSON differs from reviewed bytes");
        if (token != generation || reviewedSave && !saveBytesCurrent(token, session, revision)) return;
        if (!reviewedSave) store.rememberLocal(serverKey, saved);
        String savedName = reviewedSave ? "" : store.savedName(saved);
        String savedPath = reviewedSave ? ""
            : store.savedPath(saved)
                .toString();
        main(() -> {
            if (token != generation || originalBackgroundToken != backgroundTaskToken
                || reviewedSave && !saveSourceCurrent(token, session, revision)) return;
            if (!reviewedSave) {
                progressCompleted();
                selectedLocal = saved;
                announceSaved(savedName, savedPath);
                finishBackgroundTask(originalBackgroundToken, true, "蓝图已下载并保存");
                preparing = false;
                status = "JSON 与结构预览 PNG 已保存至客户端蓝图库";
                refreshLocal();
                return;
            }
            NBTTagCompound acknowledgement = request("ackBlueprintSaved");
            acknowledgement.setLong("clientTaskToken", originalBackgroundToken);
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
            begin.setLong("clientTaskToken", originalBackgroundToken);
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
            uploadWindow = new HologramLibraryProtocol.UploadWindow(begin);
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

    private final class QueuedWork implements Runnable {

        final Job job;
        final long token = generation;
        final boolean durable;

        QueuedWork(Job job, boolean durable) {
            this.job = job;
            this.durable = durable;
        }

        public void run() {
            if (!durable && token != generation) return;
            try {
                job.run();
            } catch (Exception error) {
                main(() -> {
                    if (token != generation) return;
                    loading = false;
                    failSave("保存蓝图失败：" + HologramUiText.reason(error.getMessage()), error);
                });
            }
        }
    }

    private void work(Job job) {
        enqueueWork(job, false);
    }

    private void durableWork(Job job) {
        enqueueWork(job, true);
    }

    private void enqueueWork(Job job, boolean durable) {
        try {
            worker.execute(new QueuedWork(job, durable));
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            loading = false;
            failSave("蓝图保存队列繁忙，请稍后重试", busy);
        }
    }

    private static void main(Runnable action) {
        Minecraft.getMinecraft()
            .func_152344_a(action);
    }

    private NBTTagCompound request(String op) {
        NBTTagCompound request = HologramLibraryProtocol.request(op);
        if (op.endsWith("Begin") || op.endsWith("Chunk")
            || op.startsWith("quick")
            || "download".equals(op)
            || "exportDraft".equals(op)
            || "cancel".equals(op)
            || "ackBlueprintSaved".equals(op)) request.setLong("clientTaskToken", backgroundTaskToken);
        return request;
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
