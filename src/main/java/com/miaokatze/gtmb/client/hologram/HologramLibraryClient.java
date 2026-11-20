// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtmb.common.util.BlueprintWebsite;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramLibraryDownload;
import com.miaokatze.gtmb.hologram.HologramLibraryProtocol;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.FrozenDraft;
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
    private final ThreadPoolExecutor thumbnailWorker = new ThreadPoolExecutor(
        0,
        1,
        15,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1),
        runnable -> {
            Thread thread = new Thread(runnable, "GTMB blueprint thumbnails");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy());
    private HologramClientLibrary store;
    private NBTTagList local = new NBTTagList(), server = new NBTTagList();
    private NBTTagCompound config = new NBTTagCompound();
    private String selectedLocal = "", selectedServer = "", status = "", connection = "";
    // -1: unobserved, 0: no remembered client source, 1: remembered source observed by existing disk work.
    private int knownQuickSource = -1;
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
    private volatile long lastActivity;
    private long lastDisplayFrame;
    private volatile HologramProgress workerDisplayProgress;
    private Object libraryConnection;
    private Object libraryWorld, quickTool;
    private String quickToolId = "";
    private int quickSlot;
    private boolean quickPreview;
    private HologramLocalPlacement.Submission placementSubmission;
    private volatile UUID placementRequest;
    private boolean placementRequestSent;
    private PublicRead publicRead;
    private PublicIdentity incomingPublic;
    private final java.util.LinkedHashMap<PublicIdentity, HologramBlueprintArtifact.Artifact> publicArtifacts = new java.util.LinkedHashMap<>(
        16,
        .75f,
        true);
    private long publicArtifactBytes;
    private DisplaySource displayPreparing;
    private final java.util.LinkedHashMap<String, DisplaySource> localPreviews = new java.util.LinkedHashMap<>(
        4,
        .75f,
        true);
    private static final long LOCAL_PREVIEW_CACHE_BYTES = 32L * 1024 * 1024;
    private static final long ESTIMATED_CELL_BYTES = 512L;
    private boolean displayPlacement, localDisplayTask;
    private long displayEpoch;
    private boolean displayFirstPublished;
    private boolean browseRendering;
    private long browseStartedAt;
    private final HologramLibraryPreviewImage previewImage = new HologramLibraryPreviewImage();
    private HologramState.Preparation browsePreparation;
    private volatile DisplayIndex displayIndex;
    private HologramState browseState;
    private volatile long browseEpoch;
    private String browseStatus = "";
    private String lastIntegratedPreview = "";
    private volatile UpgradeTask upgradeTask;
    private final java.util.List<String> upgradeDetails = new java.util.ArrayList<>();

    @com.github.bsideup.jabel.Desugar
    private record DisplayIndex(DisplaySource source, long generation, long task, long epoch, boolean placement) {}

    private static final class UpgradeTask {

        final long task, epoch;
        final boolean server;
        final Runnable refresh;
        int page, pages;
        volatile int done, total;
        volatile long progressedAt;

        UpgradeTask(long task, long epoch, boolean server, Runnable refresh) {
            this.task = task;
            this.epoch = epoch;
            this.server = server;
            this.refresh = refresh;
        }
    }

    private enum PublicUse { BROWSE, PLACE, EDIT, COPY }

    @com.github.bsideup.jabel.Desugar
    private record PublicIdentity(String id, String hash, long revision) {

        static PublicIdentity from(NBTTagCompound row) {
            return new PublicIdentity(row.getString("id"), row.getString("sourceHash"), row.getLong("sourceRevision"));
        }

        boolean complete() {
            return id.matches("public_[a-fA-F0-9-]{36}") && hash.matches("[a-f0-9]{64}") && revision > 0;
        }
    }

    @com.github.bsideup.jabel.Desugar
    private record PublicRead(PublicIdentity identity, PublicUse use, long browseEpoch,
        java.util.function.Consumer<byte[]> open, java.util.function.Consumer<String> failure) {}

    /** A frozen display source. File stamps are reuse hints, never server admission receipts. */
    static final class DisplaySource {

        final String key, stamp, localId, publicId, revision;
        final byte[] json;
        final FrozenDraft draft;
        NBTTagCompound snapshot;
        HologramBlueprintPreview.CompactCells compactCells;
        volatile HologramState state;
        String hash;
        byte[] artifact;
        String artifactHash;
        volatile BufferedImage thumbnail;

        DisplaySource(String key, String stamp, byte[] json, NBTTagCompound snapshot, String localId, String publicId,
            String hash, String revision) {
            this(key, stamp, json, snapshot, localId, publicId, hash, revision, null);
        }

        private DisplaySource(String key, String stamp, byte[] json, NBTTagCompound snapshot, String localId,
            String publicId, String hash, String revision, FrozenDraft draft) {
            this.key = key;
            this.stamp = stamp;
            this.json = json;
            this.draft = draft;
            this.snapshot = snapshot;
            this.localId = localId;
            this.publicId = publicId;
            this.hash = hash;
            this.revision = revision;
        }
    }

    private boolean downloadExpected, configWritePending, draftPending;
    private String pendingConfigRequestId = "";
    private long confirmedConfigRevision = -1;
    private boolean incomingCapturePreview;
    private boolean incomingLocalSaveReceipt;
    private NBTTagCompound pendingSaveAck;
    private HologramBlueprintPreview.Request previewCapture;
    private HologramSavePreviewPose savePreviewPose;
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

    HologramLibraryClient() {}

    static boolean dedicated() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.theWorld != null && !mc.isIntegratedServerRunning();
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
        Object currentConnection = mc.getNetHandler();
        if (libraryWorld != mc.theWorld || libraryConnection != currentConnection
            || !current.equals(connection)
            || !java.util.Objects.equals(libraryActor, currentActor)) {
            boolean disconnected = mc.theWorld == null || libraryConnection != currentConnection
                || !current.equals(connection)
                || !java.util.Objects.equals(libraryActor, currentActor);
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
            libraryConnection = currentConnection;
            libraryActor = currentActor;
            connection = current;
            advanceGeneration();
            server = new NBTTagList();
            selectedServer = "";
            config = new NBTTagCompound();
            page = 0;
            knownQuickSource = -1;
            HologramLocalPlacement.INSTANCE.clear();
            clearBrowsePreview();
            if (disconnected) {
                lastIntegratedPreview = "";
                synchronized (localPreviews) {
                    localPreviews.clear();
                }
                publicArtifacts.clear();
                publicArtifactBytes = 0;
            }
        }
        if (lastDisplayFrame == 0 || System.nanoTime() - lastDisplayFrame >= 60_000_000L)
            tickBrowsePreview(5_000_000L);
        if (quickPreview && (!sameQuickTool(mc) || !quickScreenAllowed(mc))) cancelQuickPlacement();
        if (placementSubmission != null && (!placementSubmission.current() || !sameQuickTool(mc)))
            cancelProjectedPlacement("蓝图放置来源或投影仪已变化，请重新确认");
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
        if (pendingPreviewStart != null && saveSourceCurrent(generation, incomingSaveSession, incomingSaveRevision)
            && savePreviewReady(generation, incomingSaveSession, incomingSaveRevision)) {
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
        UpgradeTask upgrading = upgradeTask;
        if (upgrading != null && upgrading.task == backgroundTaskToken
            && upgrading.epoch == generation
            && upgrading.progressedAt != 0) {
            lastActivity = Math.max(lastActivity, upgrading.progressedAt);
            transferProgress.update(
                "library:" + generation,
                "library.disk",
                upgrading.done,
                upgrading.total,
                "steps",
                false,
                lastActivity);
        }
        if ((outgoing != null || incoming != null
            || downloadExpected
            || preparing
            || previewCapture != null
            || pendingPreviewStart != null
            || quickInProgress()) && pendingDiskSave == null
            && System.nanoTime() - lastActivity > TimeUnit.SECONDS.toNanos(120)
            && !cancelProjectedPlacement("施工核对超时，请重新确认")) {
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

    void refreshServerConfig() {
        HologramNetwork.sendLibrary(request("configGet"));
    }

    private void ensureStore() {
        if (store == null)
            store = new HologramClientLibrary(Paths.get(Minecraft.getMinecraft().mcDataDir.getAbsolutePath()));
    }

    HologramClientLibrary privateStore() {
        ensureStore();
        return store;
    }

    void rememberPublic(String id) {
        ensureStore();
        String serverKey = connection;
        long token = generation;
        work(() -> {
            store.rememberPublic(serverKey, id);
            main(() -> { if (token == generation) knownQuickSource = 1; });
        });
    }

    /** Rendering only reads this small mirror; it must never initialize or read the disk store. */
    int knownQuickSource() {
        Minecraft mc = Minecraft.getMinecraft();
        return dedicated() && libraryWorld == mc.theWorld
            && mc.thePlayer != null
            && mc.thePlayer.getUniqueID()
                .equals(libraryActor) ? knownQuickSource : -1;
    }

    /** Recent previews read local display data; server admission starts only with construction. */
    boolean quickPlaceLast(NBTTagCompound position) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null
            || !com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector
                .isProjector(mc.thePlayer.getHeldItem()))
            return false;
        if (busy()) return true;
        net.minecraft.item.ItemStack held = mc.thePlayer.getHeldItem();
        quickTool = held;
        quickSlot = mc.thePlayer.inventory.currentItem;
        quickToolId = held.hasTagCompound() ? held.getTagCompound().getString("gtitHologramToolId") : "";
        String fallback = !lastIntegratedPreview.isEmpty() ? lastIntegratedPreview
            : held.hasTagCompound() ? held.getTagCompound().getString("gtitHologramPreset") : "";
        return previewLastLocal(dedicated() ? "" : fallback);
    }

    boolean quickInProgress() {
        return quickPreview;
    }

    private boolean quickScreenAllowed(Minecraft mc) {
        return mc.currentScreen == null || mc.currentScreen instanceof HologramPlacementLoadingScreen
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
        if (!quickPreview) return false;
        cancel("快捷放置已取消", true);
        return true;
    }

    void refreshLocal() {
        if (store == null || loading) return;
        loading = true;
        long token = generation;
        String serverKey = connection;
        String operation = "local:" + token;
        localCatalogProgress.update(operation, "library.index", 0, 0, "", false, System.nanoTime());
        work(() -> {
            NBTTagList labels = new NBTTagList();
            java.util.List<HologramClientLibrary.Entry> entries = store.list();
            try {
                entries.addAll(store.listLegacy());
            } catch (java.io.IOException | RuntimeException unavailable) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Unable to read historical blueprint catalog", unavailable);
            }
            for (HologramClientLibrary.Entry entry : entries) {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("id", entry.id);
                row.setString("name", entry.name);
                row.setString("authorName", entry.authorName);
                row.setString("authorUuid", entry.authorUuid);
                row.setString("sourceMod", entry.sourceMod);
                row.setString("gtmbVersion", entry.gtmbVersion);
                row.setBoolean("fullyVoucherCovered", entry.fullyVoucherCovered);
                row.setLong("createdAt", entry.createdAt);
                row.setInteger("cells", entry.cells);
                row.setInteger("structures", entry.structures);
                row.setInteger("schema", 2);
                row.setLong("bytes", entry.bytes);
                row.setBoolean("mine", !entry.id.startsWith("legacy:"));
                labels.appendTag(row);
            }
            int source = -1;
            try {
                HologramClientLibrary.LastSource last = store.lastSource(serverKey);
                source = last.publicId.isEmpty() ? 0 : 1;
                if (!last.publicLibrary && !last.localId.isEmpty())
                    for (int i = 0; i < labels.tagCount(); i++) if (last.localId.equals(
                        labels.getCompoundTagAt(i)
                            .getString("id")))
                        source = 1;
            } catch (java.io.IOException unavailable) { /* Unknown memory does not invalidate the catalog. */ }
            int observedSource = source;
            main(() -> {
                if (token == generation) knownQuickSource = observedSource;
                finishLocalRefresh(token, operation, labels);
            });
        }, error -> {
            loading = false;
            localCatalogProgress.finish(operation, System.nanoTime());
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Blueprint local catalog refresh failed", error);
            if (!busy()) status = "读取本地蓝图库失败：" + HologramUiText.reason(error.getMessage());
        });
    }

    /** File conversion runs on the existing bounded worker and refreshes through the normal library UI route. */
    void upgradeLocal(Runnable refreshCatalog) {
        if (busy() || !beginBackgroundTask("升级本地蓝图")) return;
        ensureStore();
        long token = generation;
        UpgradeTask upgrading = new UpgradeTask(backgroundTaskToken, token, false, refreshCatalog);
        upgradeTask = upgrading;
        upgradeDetails.clear();
        preparing = true;
        localProgressPhase = "library.disk";
        transferProgress.update("library:" + token, localProgressPhase, 0, 0, "steps", false, System.nanoTime());
        status = "正在压缩并升级本地蓝图";
        work(() -> {
            String result = store.upgradeLegacy(
                () -> token == generation && upgrading.task == backgroundTaskToken && upgradeTask == upgrading,
                (done, total) -> {
                    if (token != generation || upgrading.task != backgroundTaskToken || upgradeTask != upgrading)
                        return;
                    upgrading.done = done;
                    upgrading.total = total;
                    upgrading.progressedAt = System.nanoTime();
                });
            main(() -> {
                if (token != generation || upgrading.task != backgroundTaskToken || upgradeTask != upgrading) return;
                upgradeTask = null;
                preparing = false;
                status = result;
                progressCompleted();
                finishBackgroundTask(backgroundTaskToken, true, result);
                refreshLocal();
                if (refreshCatalog != null) refreshCatalog.run();
            });
        });
    }

    void upgradeServer(Runnable refreshCatalog) {
        if (!dedicated() || !config.getBoolean("admin") || busy() || !beginBackgroundTask("升级服务器蓝图")) return;
        upgradeTask = new UpgradeTask(backgroundTaskToken, generation, true, refreshCatalog);
        upgradeDetails.clear();
        preparing = true;
        localProgressPhase = "library.disk";
        transferProgress.update("library:" + generation, localProgressPhase, 0, 0, "steps", false, System.nanoTime());
        status = "正在升级服务器蓝图库，等待管理员操作结果";
        NBTTagCompound request = request("upgradeLibrary");
        request.setLong("clientTaskToken", backgroundTaskToken);
        HologramNetwork.sendLibrary(request);
    }

    java.util.List<String> upgradeDetails() {
        return new java.util.ArrayList<>(upgradeDetails);
    }

    private void receiveUpgradeResult(NBTTagCompound reply) {
        UpgradeTask upgrading = upgradeTask;
        if (upgrading == null || !upgrading.server
            || upgrading.epoch != generation
            || upgrading.task != backgroundTaskToken
            || reply.getLong("clientTaskToken") != upgrading.task) return;
        int page = reply.getInteger("page"), pages = reply.getInteger("pages");
        if (page < upgrading.page) return;
        NBTTagList entries = reply.getTagList("entries", 10);
        if (page != upgrading.page || pages < 1
            || pages > 86
            || page >= pages
            || upgrading.pages != 0 && upgrading.pages != pages
            || entries.tagCount() > HologramLibraryProtocol.PAGE_SIZE
            || reply.getBoolean("complete") != (page == pages - 1)) {
            failTask("upgrade.report.invalid", "服务器蓝图升级报告不完整，请重新读取蓝图库");
            return;
        }
        upgrading.pages = pages;
        upgrading.page++;
        for (int index = 0; index < entries.tagCount(); index++) {
            NBTTagCompound entry = entries.getCompoundTagAt(index);
            if (!"skipped".equals(entry.getString("state"))) continue;
            String id = entry.getString("id"), reason = entry.getString("reason");
            String detail = id.substring(0, Math.min(96, id.length())) + "："
                + reason.substring(0, Math.min(512, reason.length()));
            upgradeDetails.add(detail);
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info("Blueprint server upgrade skipped " + detail);
        }
        status = "服务器蓝图升级：已升级 " + Math.max(0, reply.getInteger("upgraded"))
            + "，已最新 "
            + Math.max(0, reply.getInteger("current"))
            + "，跳过 "
            + Math.max(0, reply.getInteger("skipped"));
        lastActivity = System.nanoTime();
        transferProgress
            .update("library:" + generation, localProgressPhase, upgrading.page, pages, "steps", false, lastActivity);
        if (!reply.getBoolean("complete")) return;
        upgradeTask = null;
        preparing = false;
        progressCompleted();
        finishBackgroundTask(upgrading.task, true, status);
        if (upgrading.refresh != null) upgrading.refresh.run();
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
        observed.setBoolean("active", quickPreview);
        observed.setLong("token", backgroundTaskToken);
        observed.setString("status", status);
        observed.setLong("generation", generation);
        observed.setBoolean("preparing", preparing);
        observed.setBoolean("loading", loading);
        observed.setBoolean("ready", ready);
        observed.setBoolean("inFlight", inFlight);
        observed.setBoolean("bytesAwaitingBootstrap", false);
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
            || quickPreview;
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
        if (!busy() && browseRendering
            && browseState != null
            && !refreshingServer
            && !deletingServer
            && !configWritePending
            && !downloadExpected
            && !draftPending
            && (!HologramBackgroundTasks.busy() || backgroundTaskToken != 0)) {
            boolean ready = HologramRenderer.previewMeshReady(browseState);
            HologramProgress render = transferProgress.update(
                identity,
                "library.display",
                HologramRenderer.previewMeshCompleted(browseState),
                HologramRenderer.previewMeshTotal(browseState),
                "cells",
                ready,
                now);
            if (ready) browseRendering = false;
            return render;
        }
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
        } else if (displayPreparing != null && browsePreparation != null) transferProgress.update(
            identity,
            browsePreparation.phase(),
            browsePreparation.completed(),
            browsePreparation.total(),
            browsePreparation.unit(),
            false,
            now);
        else if (displayIndex != null)
            transferProgress.update(identity, "snapshot.regions", 0, 0, "", false, now);
        else if (displayPreparing != null && displayPreparing.state != null && !displayPlacement)
            transferProgress.update(
                identity,
                "library.display",
                HologramRenderer.previewMeshCompleted(displayPreparing.state),
                HologramRenderer.previewMeshTotal(displayPreparing.state),
                "cells",
                false,
                now);
        else if (preparing && workerDisplayProgress != null) return workerDisplayProgress;
        else if (incoming != null)
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
        } else if (preparing && upgradeTask == null)
            transferProgress.update(identity, localProgressPhase, 0, 0, "", false, now);
        else if (quickPreview) transferProgress.update(identity, "library.read", 0, 0, "", false, now);
        else if (deletingServer) transferProgress.update(identity, "library.delete", 0, 0, "", false, now);
        HologramProgress transfer = transferProgress.view(now);
        if (transfer != null && !transfer.complete
            && !busy()
            && !downloadExpected
            && !draftPending
            && !quickPreview
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
            savePreviewPose = null;
            // Every owner gets its own idle budget, including disk-only preview and the following admission.
            // A fresh client has no prior packet activity; zero is not a valid task start time.
            lastActivity = System.nanoTime();
            workerDisplayProgress = null;
            transferProgress.clear();
            localProgressPhase = "library.prepare";
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
        localDisplayTask = false;
        quickPreview = false;
        draftSave = null;
        savePreviewPose = null;
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

    boolean canReadPublic() {
        return !dedicated() || !config.hasKey("canDownload") || config.getBoolean("canDownload");
    }

    boolean supportsPermissionGroups() {
        return config.hasKey("permissionGroupsVersion", 3) && config.getInteger("permissionGroupsVersion")
            == com.miaokatze.gtmb.hologram.HologramLibraryPermissions.VERSION;
    }

    boolean canPublishPublic() {
        return !dedicated() || !config.hasKey("canPublish") || config.getBoolean("canPublish");
    }

    void saveDraft(HologramState state, NBTTagCompound options, boolean replace) {
        saveDraft(state, options, replace, HologramSavePreviewPose.DEFAULT_YAW, HologramSavePreviewPose.DEFAULT_PITCH);
    }

    void saveDraft(HologramState state, NBTTagCompound options, boolean replace, double yaw, double pitch) {
        if (busy()) return;
        if (!beginBackgroundTask("保存蓝图")) return;
        savePreviewPose = new HologramSavePreviewPose(backgroundTaskToken, generation, state, yaw, pitch);
        NBTTagCompound request = draftRequest(state, options, replace);
        preparing = true;
        draftPending = true;
        downloadExpected = true;
        saveCommand(
            state.session,
            state.data.getLong("uiSequence"),
            options.getBoolean("saveCurrent") ? "saveBlueprint" : "exportDraft");
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
        expectDraftDownload(null, HologramSavePreviewPose.DEFAULT_YAW, HologramSavePreviewPose.DEFAULT_PITCH);
    }

    void expectDraftDownload(HologramState state, double yaw, double pitch) {
        if (!beginBackgroundTask("保存蓝图")) return;
        savePreviewPose = state == null ? null
            : new HologramSavePreviewPose(backgroundTaskToken, generation, state, yaw, pitch);
        ensureStore();
        preparing = draftPending = downloadExpected = true;
        saveProgress.clear();
        captureActor();
        status = "等待服务器审核蓝图并发送保存内容";
        lastActivity = System.nanoTime();
    }

    void publishSelected() {
        if (!canPublishPublic()) {
            status = "你所在的权限组不允许发布公共蓝图";
            return;
        }
        if (busy() || selectedLocal.isEmpty() || !beginBackgroundTask("上传蓝图")) return;
        ensureStore();
        String id = selectedLocal;
        long token = generation;
        String targetServer = connection;
        preparing = true;
        localProgressPhase = "library.read";
        status = "正在读取客户端蓝图，准备上传";
        work(() -> {
            byte[] bytes = store.previewArtifact(id);
            String hash = HologramBlueprintArtifact.hash(bytes);
            String replaceId = store.uploadedId(targetServer, id);
            main(() -> {
                if (generation != token) return;
                preparing = false;
                outgoing = bytes;
                outgoingId = UUID.randomUUID().toString();
                outgoingOp = "upload";
                outgoingLocal = id;
                nextChunk = sentBytes = 0;
                ready = inFlight = false;
                NBTTagCompound begin = request("uploadBegin");
                begin.setString("transfer", outgoingId);
                begin.setInteger("total", bytes.length);
                begin.setString("hash", hash);
                begin.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
                if (!replaceId.isEmpty()) begin.setString("replaceId", replaceId);
                status = "上传中 · 等待服务器接收";
                lastActivity = System.nanoTime();
                uploadWindow = new HologramLibraryProtocol.UploadWindow(begin);
                HologramNetwork.sendLibrary(begin);
            });
        });
    }

    /** Display reads use file identity; content admission starts only for a placement action. */
    void previewLocal(String id, boolean placement) {
        if (id != null && id.startsWith("legacy:")) {
            previewIntegrated(id, placement);
            return;
        }
        if (busy() || id == null || id.isEmpty() || !beginBackgroundTask("读取蓝图预览")) return;
        localDisplayTask = true;
        ensureStore();
        long token = generation, epoch = beginBrowsePreview(placement);
        preparing = true;
        status = "正在读取蓝图预览";
        localProgressPhase = "library.read";
        work(() -> {
            DisplaySource source = readDisplaySource("local:" + id, store.savedPath(id), id, "", !placement, token, epoch);
            main(() -> {
                if (discardedBrowse(token, epoch, placement)) return;
                startDisplay(source, placement, epoch);
            });
        });
    }

    boolean previewDraftPlacement(FrozenDraft draft) {
        if (draft == null || busy() || !beginBackgroundTask("准备当前蓝图预览")) return false;
        localDisplayTask = true;
        long token = generation, epoch = beginBrowsePreview(true);
        preparing = true;
        localProgressPhase = "library.parse";
        work(() -> {
            HologramBlueprintPreview.DisplayInput input = HologramBlueprintPreview.displayInput(
                draft, () -> token == generation,
                (done, total) -> observeDisplay(token, "library.parse", done, total, "cells"));
            DisplaySource source = new DisplaySource("draft", "", null, input.snapshot, "", "", "", "", draft);
            source.compactCells = input.cells;
            main(() -> { if (!discardedBrowse(token, epoch, true)) startDisplay(source, true, epoch); });
        }, failure -> failTask("preview.decode", "当前蓝图预览失败，" + HologramUiText.reason(failure.getMessage())));
        return true;
    }

    private static String fileStamp(java.nio.file.Path path) throws java.io.IOException {
        java.nio.file.attribute.BasicFileAttributes attributes = java.nio.file.Files
            .readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
        return path.toAbsolutePath()
            .normalize() + ":"
            + attributes.fileKey()
            + ":"
            + attributes.size()
            + ":"
            + attributes.lastModifiedTime();
    }

    private byte[] readDisplayBytes(java.nio.file.Path path, int maximum) throws java.io.IOException {
        return readDisplayBytes(path, maximum, true);
    }

    private byte[] readDisplayBytes(java.nio.file.Path path, int maximum, boolean observed) throws java.io.IOException {
        long token = generation, total = java.nio.file.Files.size(path);
        if (observed) observeDisplay(token, "library.read", 0, total, "bytes");
        if (total > maximum) throw new java.io.IOException("Blueprint display byte budget");
        byte[] bytes = new byte[(int) total];
        try (java.io.InputStream input = java.nio.file.Files.newInputStream(path)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, Math.min(65536, bytes.length - offset));
                if (count < 0) throw new java.io.IOException("蓝图文件已变化，请重试");
                offset += count;
                if (observed) observeDisplay(token, "library.read", offset, total, "bytes");
            }
            if (input.read() != -1) throw new java.io.IOException("蓝图文件已变化，请重试");
            if (observed && bytes.length == 0) observeDisplay(token, "library.read", 0, total, "bytes");
            return bytes;
        }
    }

    DisplaySource readDisplaySource(String key, java.nio.file.Path path, String localId, String publicId)
        throws java.io.IOException {
        return readDisplaySource(key, path, localId, publicId, false, generation, browseEpoch);
    }

    private DisplaySource readDisplaySource(String key, java.nio.file.Path path, String localId, String publicId,
        boolean thumbnail, long token, long epoch) throws java.io.IOException {
        String stamp = fileStamp(path);
        DisplaySource cached;
        synchronized (localPreviews) {
            cached = localPreviews.get(key);
        }
        if (cached != null && stamp.equals(cached.stamp)) {
            if (thumbnail && cached.thumbnail == null) queueBrowseImage(cached, path, token, epoch);
            return cached;
        }
        byte[] json = readDisplayBytes(path, HologramBlueprintArtifact.MAX_JSON_BYTES);
        if (!stamp.equals(fileStamp(path))) throw new java.io.IOException("蓝图文件已变化，请重试");
        DisplaySource source = new DisplaySource(key, stamp, json, null, localId, publicId, "", "");
        if (thumbnail) queueBrowseImage(source, path, token, epoch);
        observeDisplay(token, "library.parse", 0, 0, "");
        HologramBlueprintPreview.DisplayInput input = HologramBlueprintPreview
            .displayInput(json, (done, total) -> observeDisplay(token, "library.parse", done, total, "cells"));
        source.snapshot = input.snapshot;
        source.compactCells = input.cells;
        return source;
    }

    private boolean previewLastLocal(String legacyFallback) {
        if (busy() || !beginBackgroundTask("读取最近蓝图预览")) return true;
        quickPreview = true;
        localDisplayTask = true;
        ensureStore();
        preparing = true;
        localProgressPhase = "library.read";
        long token = generation;
        work(() -> {
            HologramClientLibrary.LastSource source = store.lastSource(connection);
            DisplaySource display = !source.publicLibrary && !source.localId.isEmpty()
                ? readDisplaySource("local:" + source.localId, store.savedPath(source.localId), source.localId, "")
                : null;
            main(() -> {
                if (token != generation) return;
                if (display != null) startDisplay(display, true, browseEpoch);
                else {
                    preparing = false;
                    finishBackgroundTask(backgroundTaskToken, true, "最近蓝图已读取");
                    if (!source.publicId.isEmpty()) {
                        previewPublic(source.publicId);
                        quickPreview = preparing;
                    } else if (!legacyFallback.isEmpty()) {
                        previewIntegrated(legacyFallback, true);
                        quickPreview = preparing;
                    }
                    else HologramClient.localPlacementNotice("没有可预览的最近蓝图，请先从蓝图库选择");
                }
            });
        });
        return true;
    }

    void previewPublic(String id) {
        previewPublic(id, true);
    }

    void previewPublic(String id, boolean placement) {
        if (!dedicated()) {
            previewIntegrated(id, placement);
            return;
        }
        readPublic(id, placement ? PublicUse.PLACE : PublicUse.BROWSE, null, null);
    }

    void openPublic(String id, java.util.function.Consumer<byte[]> open, java.util.function.Consumer<String> failure) {
        readPublic(id, PublicUse.EDIT, open, failure);
    }

    private void readPublic(String id, PublicUse use, java.util.function.Consumer<byte[]> open,
        java.util.function.Consumer<String> failure) {
        if (!canReadPublic()) {
            status = "你所在的权限组不允许读取公共蓝图";
            if (failure != null) failure.accept(status);
            return;
        }
        if (busy() || id == null || !id.matches("public_[a-fA-F0-9-]{36}")
            || !beginBackgroundTask("读取服务器蓝图")) {
            if (failure != null) failure.accept("当前无法读取服务器蓝图，请重试");
            return;
        }
        ensureStore();
        PublicIdentity identity = new PublicIdentity(id, "", 0);
        for (int i = 0; i < server.tagCount(); i++) {
            NBTTagCompound row = server.getCompoundTagAt(i);
            if (!id.equals(row.getString("id"))) continue;
            PublicIdentity listed = PublicIdentity.from(row);
            if (listed.complete()) identity = listed;
            break;
        }
        long epoch = use == PublicUse.BROWSE || use == PublicUse.PLACE ? beginBrowsePreview(use == PublicUse.PLACE)
            : browseEpoch;
        PublicRead read = new PublicRead(identity, use, epoch, open, failure);
        publicRead = read;
        preparing = true;
        HologramBlueprintArtifact.Artifact cached = publicArtifacts.get(identity);
        if (cached != null) {
            localDisplayTask = true;
            long token = generation;
            String serverKey = connection;
            work(() -> usePublicArtifact(read, read.identity(), cached, token, serverKey));
            return;
        }
        NBTTagCompound request = request("download");
        request.setString("id", id);
        if (identity.complete()) {
            request.setString("sourceHash", identity.hash());
            request.setLong("sourceRevision", identity.revision());
        }
        downloadExpected = true;
        status = "正在获取服务器蓝图文件";
        HologramNetwork.sendLibrary(request);
    }

    private void usePublicArtifact(PublicRead read, PublicIdentity identity, HologramBlueprintArtifact.Artifact artifact,
        long token, String serverKey) throws Exception {
        if (token != generation) return;
        if (read.use() == PublicUse.COPY) {
            main(() -> retainPublicArtifact(identity, artifact, token));
            persistArtifact(artifact.json, artifact.png, "", "", "", 0, token, serverKey);
            return;
        }
        if (read.use() == PublicUse.EDIT) {
            main(() -> {
                if (token != generation || publicRead != read) return;
                retainPublicArtifact(identity, artifact, token);
                publicRead = null;
                preparing = false;
                finishBackgroundTask(backgroundTaskToken, true, "服务器蓝图已读取");
                read.open().accept(artifact.json);
            });
            return;
        }
        String key = "public:" + identity.id();
        String stamp = identity.hash() + ":" + identity.revision();
        DisplaySource cached;
        synchronized (localPreviews) {
            cached = localPreviews.get(key);
        }
        if (cached == null || !stamp.equals(cached.stamp)) {
            HologramBlueprintPreview.DisplayInput input = HologramBlueprintPreview.displayInput(
                artifact.json,
                (done, total) -> observeDisplay(token, "library.parse", done, total, "cells"));
            cached = new DisplaySource(
                key, stamp, artifact.json, input.snapshot, "", identity.id(), identity.hash(),
                String.valueOf(identity.revision()));
            cached.compactCells = input.cells;
            cached.thumbnail = decodeDisplayImage(artifact.png);
        }
        DisplaySource display = cached;
        main(() -> {
            if (token != generation || publicRead != read) return;
            publicRead = null;
            retainPublicArtifact(identity, artifact, token);
            boolean placement = read.use() == PublicUse.PLACE;
            if (discardedBrowse(token, read.browseEpoch(), placement)) return;
            startDisplay(display, placement, read.browseEpoch());
            rememberPublic(identity.id());
        });
    }

    private void retainPublicArtifact(PublicIdentity identity, HologramBlueprintArtifact.Artifact artifact, long token) {
        if (token != generation) return;
        HologramBlueprintArtifact.Artifact previous = publicArtifacts.put(identity, artifact);
        if (previous != null) publicArtifactBytes -= previous.json.length + previous.png.length;
        publicArtifactBytes += artifact.json.length + artifact.png.length;
        java.util.Iterator<HologramBlueprintArtifact.Artifact> entries = publicArtifacts.values().iterator();
        while (publicArtifacts.size() > 16 || publicArtifactBytes > 64L * 1024 * 1024) {
            HologramBlueprintArtifact.Artifact retired = entries.next();
            publicArtifactBytes -= retired.json.length + retired.png.length;
            entries.remove();
        }
    }

    HologramState browsePreviewState() {
        return browseState;
    }

    long browsePreviewEpoch() {
        return browseEpoch;
    }

    private void observeDisplay(long token, String phase, long done, long total, String unit) {
        if (token != generation) return;
        lastActivity = System.nanoTime();
        workerDisplayProgress = transferProgress
            .update("library:" + token, phase, done, total, unit, false, lastActivity);
    }

    private void queueBrowseImage(DisplaySource source, java.nio.file.Path path, long token, long epoch) {
        thumbnailWorker.execute(() -> {
            if (token != generation || epoch != browseEpoch) return;
            try {
                java.nio.file.Path png = HologramBlueprintArtifact.previewPath(path);
                if (!java.nio.file.Files.isRegularFile(png)) return;
                String stamp = fileStamp(png);
                BufferedImage image = decodeDisplayImage(
                    readDisplayBytes(png, HologramBlueprintArtifact.MAX_PNG_BYTES, false));
                if (!stamp.equals(fileStamp(png)) || !source.stamp.equals(fileStamp(path))) return;
                main(() -> {
                    if (token != generation || epoch != browseEpoch) return;
                    source.thumbnail = image;
                    synchronized (localPreviews) {
                        if (localPreviews.get(source.key) == source) retainDisplaySource(source);
                    }
                    if (browseState == null || !HologramRenderer.browse().previewMeshVisible(browseState))
                        previewImage.replace(image);
                });
            } catch (java.io.IOException | RuntimeException unavailable) {
                // The local cover is optional and independent of preparing geometry.
            }
        });
    }

    static BufferedImage decodeDisplayImage(byte[] png) throws java.io.IOException {
        if (png == null || png.length < 24
            || png.length > HologramBlueprintArtifact.MAX_PNG_BYTES
            || png[0] != (byte) 137
            || png[1] != 80
            || png[2] != 78
            || png[3] != 71) throw new java.io.IOException("Invalid display PNG header");
        java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
        if (!readers.hasNext()) throw new java.io.IOException("PNG reader unavailable");
        javax.imageio.ImageReader reader = readers.next();
        try (javax.imageio.stream.ImageInputStream input = new javax.imageio.stream.MemoryCacheImageInputStream(
            new java.io.ByteArrayInputStream(png))) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > 1024 || height > 1024 || (long) width * height > 1024 * 1024)
                throw new java.io.IOException("Display PNG dimensions exceed budget");
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    net.minecraft.util.ResourceLocation browsePreviewTexture() {
        return System.nanoTime() - browseStartedAt < 200_000_000L ? null : previewImage.texture();
    }

    BufferedImage browsePreviewImage() {
        return previewImage.image();
    }

    void releaseBrowseImage() {
        previewImage.clear();
    }

    String browsePreviewStatus() {
        return browseStatus;
    }

    private long beginBrowsePreview(boolean placement) {
        browseStartedAt = System.nanoTime();
        if (!placement) {
            clearBrowsePreview();
            browseStatus = "正在读取本地预览";
        }
        return browseEpoch;
    }

    private boolean discardedBrowse(long token, long epoch, boolean placement) {
        if (token != generation) return true;
        if (placement || epoch == browseEpoch) return false;
        preparing = false;
        finishBackgroundTask(backgroundTaskToken, false, "Preview cancelled", true);
        return true;
    }

    private void previewIntegrated(String id, boolean placement) {
        if (busy() || id == null || id.isEmpty() || !beginBackgroundTask("读取本地旧蓝图")) return;
        localDisplayTask = true;
        ensureStore();
        long token = generation, epoch = beginBrowsePreview(placement);
        preparing = true;
        localProgressPhase = "library.read";
        work(() -> {
            DisplaySource source;
            String localId = id.startsWith("local_") ? id : "local_" + id;
            if (localId.matches("local_[a-fA-F0-9-]{36}") && java.nio.file.Files.isRegularFile(store.savedPath(localId)))
                source = readDisplaySource(
                    "local:" + localId, store.savedPath(localId), localId, "", !placement, token, epoch);
            else {
                String key = id.startsWith("legacy:") ? id : "legacy:" + id;
                String stamp = fileStamp(store.legacyCatalogPath());
                synchronized (localPreviews) {
                    source = localPreviews.get(key);
                }
                if (source == null || !stamp.equals(source.stamp)) {
                    byte[] json = store.readLegacyDocument(id);
                    if (!stamp.equals(fileStamp(store.legacyCatalogPath()))) throw new java.io.IOException("蓝图库已变化，请重试");
                    HologramBlueprintPreview.DisplayInput input = HologramBlueprintPreview.displayInput(
                        json, (done, total) -> observeDisplay(token, "library.parse", done, total, "cells"));
                    source = new DisplaySource(key, stamp, json, input.snapshot, "", "", "", "");
                    source.compactCells = input.cells;
                }
            }
            DisplaySource readySource = source;
            main(() -> {
                if (discardedBrowse(token, epoch, placement)) return;
                startDisplay(readySource, placement, epoch);
                if (placement) lastIntegratedPreview = id;
            });
        });
    }

    void clearBrowsePreview() {
        HologramRenderer.cancelBrowseTopology(browseState);
        if (browseState != null && browseState.cells.size() * ESTIMATED_CELL_BYTES > LOCAL_PREVIEW_CACHE_BYTES)
            HologramRenderer.releaseInactiveBrowse(browseState);
        browseEpoch++;
        browseRendering = false;
        releaseBrowseImage();
        if (!displayPlacement) {
            if (displayPreparing != null) {
                preparing = false;
                finishBackgroundTask(backgroundTaskToken, false, "蓝图预览已取消", true);
            }
            displayPreparing = null;
            browsePreparation = null;
            displayIndex = null;
        }
        browseState = null;
        browseStatus = "";
    }

    private void startDisplay(DisplaySource source, boolean placement, long epoch) {
        workerDisplayProgress = null;
        displayFirstPublished = false;
        displayIndex = null;
        if (previewImage.image() == null) previewImage.replace(source.thumbnail);
        displayPreparing = source;
        displayPlacement = placement;
        displayEpoch = epoch;
        preparing = true;
        browseStatus = "正在准备显示";
        if (source.state != null) {
            if (!placement) browseState = source.state;
            displayReady();
        } else browsePreparation = new HologramState.Preparation(
            source.snapshot,
            source.json != null || source.draft != null,
            source.compactCells);
    }

    private void displayReady() {
        DisplaySource source = displayPreparing;
        boolean placement = displayPlacement;
        displayPreparing = null;
        browsePreparation = null;
        displayIndex = null;
        displayPlacement = false;
        preparing = false;
        source.snapshot = null;
        source.compactCells = null;
        retainDisplaySource(source);
        if (placement) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null
                || mc.theWorld != libraryWorld
                || mc.getNetHandler() != libraryConnection
                || !mc.thePlayer.getUniqueID()
                    .equals(libraryActor)) {
                finishBackgroundTask(backgroundTaskToken, false, "蓝图预览来源已变化");
                return;
            }
            if (!source.localId.isEmpty()) {
                String serverKey = connection;
                work(() -> store.rememberLocal(serverKey, source.localId));
            }
            progressCompleted();
            finishBackgroundTask(backgroundTaskToken, true, "蓝图预览已准备好");
            HologramLocalPlacement.INSTANCE.install(source);
        } else {
            if (displayEpoch == browseEpoch) {
                browseState = source.state;
                browseStatus = "";
            }
            browseRendering = !HologramRenderer.previewMeshReady(source.state);
            if (!browseRendering) progressCompleted();
            finishBackgroundTask(backgroundTaskToken, true, "蓝图预览已准备好");
        }
    }

    void retainDisplaySource(DisplaySource source) {
        if (source.json != null) synchronized (localPreviews) {
            localPreviews.remove(source.key);
            // Reject an over-budget source even when it would be the only cache entry.
            if (retainedPreviewBytes(source) > LOCAL_PREVIEW_CACHE_BYTES) return;
            localPreviews.put(source.key, source);
            long retained = 0;
            for (DisplaySource entry : localPreviews.values()) retained += retainedPreviewBytes(entry);
            java.util.Iterator<DisplaySource> entries = localPreviews.values()
                .iterator();
            while (localPreviews.size() > 4 || retained > LOCAL_PREVIEW_CACHE_BYTES) {
                retained -= retainedPreviewBytes(entries.next());
                entries.remove();
            }
        }
    }

    private static long retainedPreviewBytes(DisplaySource source) {
        return source.json.length + (source.artifact == null ? 0L : source.artifact.length)
            + (source.state == null ? 0L : source.state.cells.size() * ESTIMATED_CELL_BYTES)
            + (source.thumbnail == null ? 0L : (long) source.thumbnail.getWidth() * source.thumbnail.getHeight() * 4);
    }

    /** A miss may compress its in-memory transport representation; the source file stays byte-exact. */
    static byte[] uploadJson(byte[] source, java.util.function.BooleanSupplier current) throws java.io.IOException {
        if (!com.miaokatze.gtmb.hologram.HologramBlueprintPreviewBinding.pngSha256(source)
            .isEmpty()) return source;
        try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
            new java.io.InputStreamReader(
                new java.io.ByteArrayInputStream(source),
                java.nio.charset.StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (reader.nextName()
                    .equals("encoding")) {
                    if (reader.nextString()
                        .equals("gtmb-dictionary-1")) return source;
                } else reader.skipValue();
            }
        }
        byte[] compact = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec
            .recompress(source, HologramBlueprintArtifact.MAX_JSON_BYTES, current);
        return compact.length < source.length ? compact : source;
    }

    void advanceFrame() {
        if (displayPreparing == null) return;
        if (!displayContextCurrent()) return;
        lastDisplayFrame = System.nanoTime();
        tickBrowsePreview(5_000_000L);
    }

    private boolean displayContextCurrent() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld != null && mc.theWorld == libraryWorld && mc.getNetHandler() == libraryConnection
            && mc.thePlayer != null && mc.thePlayer.getUniqueID().equals(libraryActor)
            && (!quickPreview || sameQuickTool(mc) && quickScreenAllowed(mc));
    }

    private void tickBrowsePreview(long nanos) {
        if (displayPreparing == null || displayIndex != null) return;
        try {
            if (browsePreparation != null) {
                if (browsePreparation.canPrepareCompactIndex()) {
                    HologramState.Preparation.DetachedIndex index = browsePreparation.prepareCompactIndex(256, nanos);
                    lastActivity = System.nanoTime();
                    if (index != null) startDisplayIndex(index);
                    return;
                } else if (browsePreparation.step(Integer.MAX_VALUE, nanos)) {
                    displayPreparing.state = browsePreparation.result();
                    browsePreparation = null;
                    if (!displayPlacement) browseState = displayPreparing.state;
                } else if (!displayFirstPublished) {
                    HologramState first = browsePreparation.firstDisplay();
                    if (first != null && displayEpoch == browseEpoch) {
                        browseState = first;
                        displayFirstPublished = true;
                    }
                }
            }
            lastActivity = System.nanoTime();
            if (browsePreparation == null) displayReady();
        } catch (RuntimeException invalid) {
            displayPreparing = null;
            browsePreparation = null;
            displayIndex = null;
            failTask("preview.decode", "Preview unavailable: " + invalid.getMessage());
        }
    }

    private void startDisplayIndex(HologramState.Preparation.DetachedIndex index) {
        DisplayIndex ticket = new DisplayIndex(
            displayPreparing, generation, backgroundTaskToken, displayEpoch, displayPlacement);
        displayIndex = ticket;
        browsePreparation = null;
        HologramState prefix = index.firstDisplay();
        if (!ticket.placement() && prefix != null && ticket.epoch() == browseEpoch) {
            browseState = prefix;
            displayFirstPublished = true;
        }
        work(() -> {
            HologramState complete = index.finish(
                () -> displayIndex == ticket && ticket.generation() == generation);
            if (complete != null) main(() -> {
                if (!displayIndexCurrent(ticket)) return;
                ticket.source().state = complete;
                displayReady();
            });
        }, failure -> {
            if (displayIndexCurrent(ticket))
                failTask("preview.decode", "Preview unavailable: " + failure.getMessage());
        });
    }

    private boolean displayIndexCurrent(DisplayIndex ticket) {
        return displayIndex == ticket && ticket.generation() == generation && ticket.task() == backgroundTaskToken
            && ticket.source() == displayPreparing && ticket.placement() == displayPlacement
            && (ticket.placement() || ticket.epoch() == browseEpoch) && displayContextCurrent();
    }

    boolean prepareLocalPlacement(HologramLocalPlacement placement) {
        return prepareLocalPlacement(placement, new NBTTagCompound());
    }

    boolean prepareLocalPlacement(HologramLocalPlacement placement, NBTTagCompound options) {
        HologramLocalPlacement.Submission submission = placement.freeze(options);
        if (submission == null || busy()) return false;
        DisplaySource source = submission.source();
        if (!beginBackgroundTask("准备施工并核对材料")) return false;
        Minecraft mc = Minecraft.getMinecraft();
        UUID request = UUID.randomUUID();
        placementRequest = request;
        placementRequestSent = false;
        placementSubmission = submission;
        quickTool = mc.thePlayer.getHeldItem();
        quickSlot = mc.thePlayer.inventory.currentItem;
        quickToolId = quickTool instanceof net.minecraft.item.ItemStack
            && ((net.minecraft.item.ItemStack) quickTool).hasTagCompound()
                ? ((net.minecraft.item.ItemStack) quickTool).getTagCompound()
                    .getString("gtitHologramToolId")
                : "";
        NBTTagCompound intent = submission.intent();
        int[] anchor = { intent.getInteger("x"), intent.getInteger("y"), intent.getInteger("z") };
        int[] pose = intent.getIntArray("previewPose");
        int worldHeight = mc.theWorld.getHeight();
        long token = generation;
        preparing = true;
        localProgressPhase = "library.verify";
        status = "正在准备锁定位置的施工清单";
        work(() -> {
            byte[] json = source.draft == null ? source.json : source.draft.artifact();
            com.miaokatze.gtmb.hologram.HologramProjectedPlacement.Plan plan = com.miaokatze.gtmb.hologram.HologramProjectedPlacement
                .compile(
                    com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.portableDocument(json),
                    anchor,
                    pose,
                    worldHeight,
                    () -> token == generation && request.equals(placementRequest));
            NBTTagCompound action = com.miaokatze.gtmb.hologram.HologramPlacementRequest.encode(request, anchor, plan);
            HologramNetwork.Action prepared = HologramNetwork.prepareProjectedPlacement(action);
            main(() -> {
                if (token != generation || !request.equals(placementRequest)
                    || !submission.current()
                    || !sameQuickTool(Minecraft.getMinecraft())) return;
                localProgressPhase = "library.session";
                lastActivity = System.nanoTime();
                status = "正在核对现场和施工材料";
                placementRequestSent = true;
                HologramNetwork.sendPreparedPlacement(prepared);
            });
        }, failure -> {
            if (request.equals(placementRequest))
                finishProjectedPlacement(false, "施工清单准备失败，" + HologramUiText.reason(failure.getMessage()));
        });
        return true;
    }

    boolean acceptProjectedPlacement(NBTTagCompound receipt) {
        if (!receipt.hasKey("placementRequestId", 8)) return false;
        UUID request = placementRequest;
        if (request == null || !request.toString()
            .equals(receipt.getString("placementRequestId"))
            || placementSubmission == null
            || !placementSubmission.current()
            || !sameQuickTool(Minecraft.getMinecraft())) return true;
        boolean accepted = receipt.getBoolean("placementRequestAccepted");
        finishProjectedPlacement(
            accepted,
            accepted ? "施工位置已确认，正在打开材料核算" : HologramUiText.reason(receipt.getString("errorMessage")));
        return true;
    }

    boolean cancelProjectedPlacement() {
        return cancelProjectedPlacement("蓝图放置准备已取消");
    }

    private boolean cancelProjectedPlacement(String reason) {
        UUID request = placementRequest;
        if (request == null) return false;
        if (placementRequestSent && placementSubmission != null && placementSubmission.current()) {
            NBTTagCompound action = new NBTTagCompound();
            action.setString("action", "cancelProjectedPlacement");
            action.setString("placementRequestId", request.toString());
            HologramNetwork.sendAction(action);
        }
        finishProjectedPlacement(false, reason);
        return true;
    }

    private void finishProjectedPlacement(boolean accepted, String detail) {
        placementRequest = null;
        placementRequestSent = false;
        placementSubmission = null;
        preparing = false;
        status = detail;
        if (accepted) {
            progressCompleted();
            HologramLocalPlacement.INSTANCE.clear();
        } else HologramLocalPlacement.INSTANCE.retry();
        finishBackgroundTask(backgroundTaskToken, accepted, detail);
    }

    void downloadSelected() {
        readPublic(selectedServer, PublicUse.COPY, null, null);
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
                knownQuickSource = -1;
                status = "客户端蓝图已删除";
                refreshLocal();
            });
        });
    }

    void saveConfig(int uploadKiB, int downloadKiB, int maxUploads) {
        saveConfig(uploadKiB, downloadKiB, maxUploads, config.getLong("configRevision"), null);
    }

    boolean configSavePending() {
        return configWritePending;
    }

    long confirmedConfigRevision() {
        return confirmedConfigRevision;
    }

    void saveConfig(int uploadKiB, int downloadKiB, int maxUploads, long expectedRevision, NBTTagList groups) {
        if (!config.getBoolean("admin")) return;
        if (groups != null && !supportsPermissionGroups()) {
            status = "服务器不支持权限组设置，请升级服务器；未发送任何设置更改";
            return;
        }
        if (configWritePending) return;
        NBTTagCompound request = request("configSet");
        request.setLong("expectedRevision", expectedRevision);
        pendingConfigRequestId = java.util.UUID.randomUUID()
            .toString();
        request.setString("configRequestId", pendingConfigRequestId);
        if (groups != null) {
            request
                .setInteger("permissionGroupsVersion", com.miaokatze.gtmb.hologram.HologramLibraryPermissions.VERSION);
            request.setTag("permissionGroups", groups.copy());
        }
        request.setInteger("uploadKiB", uploadKiB);
        request.setInteger("downloadKiB", downloadKiB);
        request.setInteger("maxUploads", maxUploads);
        configWritePending = true;
        confirmedConfigRevision = -1;
        pendingConfigRevision = expectedRevision;
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
        savePreviewPose = null;
        boolean localDisplay = localDisplayTask || placementRequest != null;
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
            || quickPreview
            || pendingDiskSave != null;
        if (activeTransfer && !incomingSaveReceipt.isEmpty()) traceSave("cancel_before_clear");
        if (previewCapture != null) previewCapture.cancel();
        previewCapture = null;
        pendingPreviewStart = null;
        pendingSaveAck = null;
        if (!localDisplay && (activeTransfer && cancelledTask > 0 || !outgoingId.isEmpty()
            || !incomingId.isEmpty()
            || activeTransfer && !incomingSaveReceipt.isEmpty()
            || pendingDiskSave != null)) {
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
        upgradeTask = null;
        saveProgress.clear();
        downloadExpected = false;
        draftPending = false;
        outgoing = null;
        uploadWindow = null;
        if (incoming != null) incoming.cancel();
        incoming = null;
        outgoingId = incomingId = "";
        PublicRead abandoned = publicRead;
        publicRead = null;
        incomingPublic = null;
        if (abandoned != null && abandoned.failure() != null) abandoned.failure().accept(reason);
        displayPreparing = null;
        browsePreparation = null;
        displayIndex = null;
        workerDisplayProgress = null;
        releaseBrowseImage();
        if (displayFirstPublished && browseState != null && browseState.data.getBoolean("displayPartial")) {
            HologramRenderer.cancelBrowseTopology(browseState);
            HologramRenderer.releaseInactiveBrowse(browseState);
            browseState = null;
        }
        displayFirstPublished = false;
        displayPlacement = false;
        placementSubmission = null;
        placementRequest = null;
        placementRequestSent = false;
        HologramLocalPlacement.INSTANCE.retry();
        ready = inFlight = preparing = false;
        if (pendingDiskSave != null) preparing = true;
        status = activeTransfer ? reason : "";
        if (browseState == null && !browseStatus.isEmpty()) {
            clearBrowsePreview();
            browseStatus = reason;
        }
    }

    private void receive(NBTTagCompound reply) {
        String op = reply.getString("op");
        if ("upgradeLibraryResult".equals(op)) {
            receiveUpgradeResult(reply);
            return;
        }
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
        if ("error".equals(op) || "deleted".equals(op) || "saveCommitted".equals(op) || "saveRejected".equals(op)
            || !draftPending && ("list".equals(op) || "config".equals(op)))
            lastActivity = System.nanoTime();
        if ("saveCommitted".equals(op) || "saveRejected".equals(op)) {
            finishDiskSave(reply, "saveCommitted".equals(op));
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
            boolean confirmsWrite = configWritePending
                && pendingConfigRequestId.equals(reply.getString("configRequestId"))
                && reply.getLong("configRevision") > pendingConfigRevision;
            if (confirmsWrite && reply.getString("libraryError")
                .isEmpty()) settingsProgress.finish(System.nanoTime());
            else if (confirmsWrite) settingsProgress.clear();
            config = (NBTTagCompound) reply.copy();
            if (!canReadPublic()) {
                publicArtifacts.clear();
                publicArtifactBytes = 0;
                synchronized (localPreviews) {
                    localPreviews.keySet().removeIf(key -> key.startsWith("public:"));
                }
                if (publicRead != null) {
                    cancel("权限组已变更，公共蓝图读取已取消", false);
                    clearBrowsePreview();
                }
            }
            if (!config.getString("libraryError")
                .isEmpty()) status = "服务器蓝图库只读：" + config.getString("libraryError");
            else if (confirmsWrite) status = "服务器设置已确认 · 修订 " + config.getLong("configRevision");
            if (confirmsWrite) {
                confirmedConfigRevision = config.getLong("configRevision");
                configWritePending = false;
            }
        } else if ("uploadReady".equals(op) || "previewReady".equals(op)) {
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
        } else if ("uploaded".equals(op)) {
            if (!matchesOutgoing(reply) || !completedUpload(reply)) return;
            long completedTask = backgroundTaskToken;
            outgoing = null;
            uploadWindow = null;
            outgoingId = "";
            ready = inFlight = false;
            status = "已上传至服务器蓝图库";
            progressCompleted();
            selectedServer = reply.getString("id");
            String serverKey = connection, localId = outgoingLocal, publicId = selectedServer;
            work(() -> {
                store.uploadedId(serverKey, localId, publicId);
                main(() -> finishBackgroundTask(completedTask, true, "蓝图已上传"));
            });
            refreshServer(page);
        } else if ("deleted".equals(op)) {
            deletingServer = false;
            knownQuickSource = -1;
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
            if (!nativeDraft) {
                PublicIdentity offered = PublicIdentity.from(reply);
                if (publicRead == null || !"public".equals(reply.getString("purpose")) || !offered.complete()
                    || !offered.id().equals(publicRead.identity().id())
                    || publicRead.identity().complete() && !publicRead.identity().equals(offered)) {
                    failTask("download.source.rejected", "服务器蓝图来源已变化，请刷新公库后重试");
                    return;
                }
                incomingPublic = offered;
            }
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
            PublicRead publicRequest = publicRead;
            PublicIdentity publicIdentity = incomingPublic;
            incomingPublic = null;
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
                if (publicRequest != null && publicIdentity != null) {
                    HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.decode(bytes);
                    if (!com.miaokatze.gtmb.hologram.HologramBlueprintContentHash.of(artifact.json, publicIdentity.id())
                        .equals(publicIdentity.hash())) throw new java.io.IOException("服务器蓝图内容摘要不匹配");
                    usePublicArtifact(publicRequest, publicIdentity, artifact, token, serverKey);
                } else if (capturePreview) {
                    com.miaokatze.gtmb.hologram.HologramLibraryFacade.validateNativeJson(bytes);
                    NBTTagCompound snapshot = HologramBlueprintPreview.snapshot(bytes);
                    main(() -> {
                        if (token != generation) return;
                        saveProgress.progress(System.nanoTime());
                        traceSave("preview_snapshot_ready");
                        Runnable start = () -> {
                            if (!saveSourceCurrent(token, saveSession, saveRevision)) return;
                            if (!savePreviewReady(token, saveSession, saveRevision)) return;
                            pendingPreviewStart = null;
                            HologramSavePreviewPose pose = savePreviewPose;
                            boolean observed = pose != null && pose.accepts(
                                backgroundTaskToken,
                                token,
                                saveSession,
                                saveRevision,
                                draftSave,
                                HologramClient.state);
                            status = "正在生成真实结构预览 PNG";
                            previewCapture = HologramBlueprintPreview.capture(
                                snapshot,
                                observed ? pose.yaw : HologramSavePreviewPose.DEFAULT_YAW,
                                observed ? pose.pitch : HologramSavePreviewPose.DEFAULT_PITCH,
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
                        if (saveSourceCurrent(token, saveSession, saveRevision)
                            && savePreviewReady(token, saveSession, saveRevision)) start.run();
                        else if (saveSourceAwaiting(token, saveSession, saveRevision)) {
                            pendingPreviewStart = start;
                            status = "保存内容已接收，正在等待对应的蓝图状态";
                            traceSave("waiting_exact_state");
                        } else failSave("蓝图来源已变化，请重新保存", null);
                    });
                } else throw new java.io.IOException("下载没有对应的蓝图请求");
            });
        } else if ("error".equals(op)) {
            HologramLocalPlacement.INSTANCE.retry();
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
        return matchesTransferTask(reply) && !outgoingId.isEmpty()
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
                    knownQuickSource = 1;
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

    private boolean savePreviewReady(long token, String session, long revision) {
        HologramSavePreviewPose pose = savePreviewPose;
        return pose == null || !pose.sameSource(backgroundTaskToken, token, session)
            || pose.accepts(backgroundTaskToken, token, session, revision, draftSave, HologramClient.state);
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
            HologramBlueprintArtifact.Artifact captured = HologramBlueprintArtifact
                .prepareSave(json, output.toByteArray());
            byte[] preview = captured.png;
            if (localSaveReceipt && !receipt.isEmpty()) {
                if (!saveBytesCurrent(token, session, revision)) return;
                HologramClientLibrary.LocalSave local = store.prepareLocalSave(
                    captured.json,
                    preview,
                    replace,
                    () -> saveBytesCurrent(token, session, revision));
                NBTTagCompound acknowledgement = request("ackBlueprintSaved");
                acknowledgement.setLong("clientTaskToken", originalBackgroundToken);
                acknowledgement.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
                acknowledgement.setString("session", session);
                acknowledgement.setLong("planRevision", revision);
                acknowledgement.setString("localId", replace);
                acknowledgement.setString("saveReceipt", receipt);
                acknowledgement.setString("jsonSha256", local.jsonHash);
                acknowledgement.setString("reviewedJsonSha256", HologramBlueprintArtifact.hash(json));
                acknowledgement.setString("previewSha256", local.pngHash);
                acknowledgement.setBoolean("localSaveReceipt", true);
                publishDiskSave(local, acknowledgement, serverKey, token);
            } else persistArtifact(captured.json, preview, replace, receipt, session, revision, token, serverKey);
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
            HologramClient.stampSemanticRevision(acknowledgement);
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
        String saved = reviewedSave || !replace.isEmpty() ? replace : "local_" + UUID.randomUUID();
        if (!reviewedSave) {
            HologramClientLibrary.LocalSave local = store
                .preparePrivateDocumentSave(json, preview, saved, () -> token == generation);
            if (token != generation) {
                local.write.rollback();
                return;
            }
            local.write.commit();
        }
        byte[] artifact = reviewedSave ? HologramBlueprintArtifact.encode(json, preview) : store.previewArtifact(saved);
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
                publicRead = null;
                progressCompleted();
                selectedLocal = saved;
                knownQuickSource = 1;
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
            try {
                acknowledgement.setString(
                    "reviewedJsonSha256",
                    HologramBlueprintArtifact
                        .hash(com.miaokatze.gtmb.hologram.HologramBlueprintPreviewBinding.sourceJson(actual.json)));
            } catch (java.io.IOException invalid) {
                throw new IllegalStateException("Previously validated blueprint binding changed", invalid);
            }
            acknowledgement.setString("previewSha256", actualPreviewHash);
            pendingSaveAck = acknowledgement;
            outgoing = artifact;
            outgoingId = UUID.randomUUID()
                .toString();
            outgoingOp = "preview";
            outgoingLocal = saved;
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
        if (mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(
            new ChatComponentText(
                StatCollector.translateToLocalFormatted("gtmb.hologram.library.saved_path", name, path)));
        ChatComponentText website = new ChatComponentText(
            StatCollector.translateToLocalFormatted("gtmb.hologram.library.saved_website", BlueprintWebsite.URL));
        website.getChatStyle()
            .setColor(EnumChatFormatting.AQUA)
            .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, BlueprintWebsite.URL));
        mc.thePlayer.addChatMessage(website);
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
        final java.util.function.Consumer<Exception> failure;

        QueuedWork(Job job, boolean durable, java.util.function.Consumer<Exception> failure) {
            this.job = job;
            this.durable = durable;
            this.failure = failure;
        }

        public void run() {
            if (!durable && token != generation) return;
            try {
                job.run();
            } catch (Exception error) {
                failed(error);
            }
        }

        void failed(Exception error) {
            main(() -> { if (token == generation) failure.accept(error); });
        }
    }

    private void work(Job job) {
        enqueueSaveWork(job, false);
    }

    private void work(Job job, java.util.function.Consumer<Exception> failure) {
        enqueueWork(job, false, failure);
    }

    /** Topology preparation shares the bounded worker and yields to any pending library operation. */
    private void durableWork(Job job) {
        enqueueSaveWork(job, true);
    }

    private void enqueueSaveWork(Job job, boolean durable) {
        long task = backgroundTaskToken;
        enqueueWork(job, durable, error -> {
            if (task != backgroundTaskToken) return;
            loading = false;
            failSave(
                error instanceof java.util.concurrent.RejectedExecutionException ? "蓝图保存队列繁忙，请稍后重试"
                    : "保存蓝图失败：" + HologramUiText.reason(error.getMessage()),
                error);
        });
    }

    private void enqueueWork(Job job, boolean durable, java.util.function.Consumer<Exception> failure) {
        QueuedWork queued = new QueuedWork(job, durable, failure);
        try {
            worker.execute(queued);
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            queued.failed(busy);
        }
    }

    private static void main(Runnable action) {
        Minecraft.getMinecraft()
            .func_152344_a(action);
    }

    private NBTTagCompound request(String op) {
        NBTTagCompound request = HologramLibraryProtocol.request(op);
        if (op.endsWith("Begin") || op.endsWith("Chunk")
            || "download".equals(op)
            || "exportDraft".equals(op)
            || "cancel".equals(op)
            || "ackBlueprintSaved".equals(op)) request.setLong("clientTaskToken", backgroundTaskToken);
        return request;
    }

    static String bytes(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
            : bytes >= 1024 ? String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0) : bytes + " B";
    }
}
