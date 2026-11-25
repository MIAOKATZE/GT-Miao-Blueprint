// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtmb.common.util.BlueprintWebsite;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBusinessWirePolicy;
import com.miaokatze.gtmb.hologram.HologramCompactRequiredException;
import com.miaokatze.gtmb.hologram.HologramLibraryDownload;
import com.miaokatze.gtmb.hologram.HologramLibraryProtocol;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.FrozenDraft;
import com.miaokatze.gtmb.hologram.HologramMaterials;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Evidence;
import com.miaokatze.gtmb.hologram.HologramProjectedMainPolicy;

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
    private String auxiliaryLibraryError = "";
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
    private PublicUpload publicUpload;
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
    private volatile UUID placementRequest;
    private PublicRead publicRead;
    private volatile PublicCopySave publicCopySave;
    private PublicIdentity incomingPublic;
    private final java.util.LinkedHashMap<PublicIdentity, CachedPublicCopy> publicArtifacts = new java.util.LinkedHashMap<>(
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

    /** One in-memory publication receipt owner; retains no JSON/PNG or chunk buffers after cancel. */
    static final class PublicUpload {

        static final long RECEIPT_NANOS = TimeUnit.SECONDS.toNanos(120);
        final HologramClientLibrary store;
        final HologramLibraryProtocol.UploadWindow window;
        final Object handler, world;
        final UUID actor;
        final String server, localId, replaceId, artifactHash;
        boolean cancelled, linking, expiryReported, checkRequested, checked;
        long cancelledAt;
        String committedId;

        PublicUpload(HologramClientLibrary store, HologramLibraryProtocol.UploadWindow window, Object handler,
            Object world, UUID actor, String server, String localId, String replaceId, String artifactHash) {
            this.store = store;
            this.window = window;
            this.handler = handler;
            this.world = world;
            this.actor = actor;
            this.server = server;
            this.localId = localId;
            this.replaceId = replaceId;
            this.artifactHash = artifactHash;
        }

        boolean matchesOwner(Object sender, Object world, UUID actor, String server) {
            return handler != null && sender == handler
                && this.world != null
                && this.world == world
                && this.actor != null
                && this.actor.equals(actor)
                && this.server.equals(server);
        }

        boolean expired(long now) {
            return cancelled && committedId == null && now - cancelledAt >= RECEIPT_NANOS;
        }

        boolean accept(NBTTagCompound reply, long now) {
            if (committedId != null || expired(now)
                || !"uploaded".equals(reply.getString("op"))
                || !reply.hasKey("id", 8)) return false;
            String id = reply.getString("id");
            if (!id.startsWith("public_") || id.length() != 43 || !replaceId.isEmpty() && !replaceId.equals(id))
                return false;
            try {
                String uuid = id.substring(7);
                if (!UUID.fromString(uuid)
                    .toString()
                    .equals(uuid) || !window.complete(reply)) return false;
            } catch (IllegalArgumentException | IOException invalid) {
                return false;
            }
            committedId = id;
            return true;
        }

        void persistLink() throws IOException {
            if (committedId == null) throw new IOException("Public upload has no matching commit receipt");
            store.reconcileUploadedId(server, localId, replaceId, committedId);
        }
    }

    private enum PublicUse {
        BROWSE,
        PLACE,
        EDIT,
        COPY
    }

    @com.github.bsideup.jabel.Desugar
    private record PublicIdentity(String id, String hash, long revision) {

        static PublicIdentity from(NBTTagCompound row) {
            return new PublicIdentity(row.getString("id"), row.getString("sourceHash"), row.getLong("sourceRevision"));
        }

        boolean complete() {
            return publicId(id) && hash.matches("[a-f0-9]{64}") && revision > 0;
        }
    }

    /** Exists only after the explicitly downloaded artifact pair has committed to the private library. */
    @com.github.bsideup.jabel.Desugar
    private record CachedPublicCopy(PublicIdentity identity, HologramBlueprintArtifact.Artifact artifact, String localId) {

        long retainedBytes() {
            return (long) artifact.json.length + artifact.png.length;
        }
    }

    @com.github.bsideup.jabel.Desugar
    private record PublicRead(PublicIdentity identity, PublicUse use, long browseEpoch,
        java.util.function.Consumer<byte[]> open, java.util.function.Consumer<String> failure) {}

    /** The worker claims an already verified pair; cancellation and that claim cannot both win. */
    private static final class PublicCopySave {

        private enum Phase {
            OPEN,
            RETIRED,
            COMMITTING
        }

        final long generation, task;
        final String id = "local_" + UUID.randomUUID();
        final HologramClientLibrary store;
        private final java.util.concurrent.atomic.AtomicReference<Phase> phase = new java.util.concurrent.atomic.AtomicReference<>(
            Phase.OPEN);

        PublicCopySave(long generation, long task, HologramClientLibrary store) {
            this.generation = generation;
            this.task = task;
            this.store = store;
        }

        boolean open() {
            return phase.get() == Phase.OPEN;
        }

        boolean retire() {
            return phase.compareAndSet(Phase.OPEN, Phase.RETIRED) || phase.get() == Phase.RETIRED;
        }

        boolean claim() {
            return phase.compareAndSet(Phase.OPEN, Phase.COMMITTING);
        }
    }

    /** A frozen display source. File stamps are reuse hints, never server admission receipts. */
    static final class DisplaySource {

        final String key, stamp, localId, publicId, revision;
        final byte[] json;
        final FrozenDraft draft;
        private final boolean indexedDisplayPath;
        NBTTagCompound snapshot;
        HologramBlueprintPreview.CompactCells compactCells;
        // Client-only facts about this exact captured local JSON; not managed membership or a verified PNG pair.
        HologramSavedMaterialSource savedMaterials;
        // Stays attached to these exact bytes when the transient geometry is released after browsing.
        boolean compactSource;
        private boolean checkedStoredUse; // set only by the single IO worker for these immutable bytes
        private Evidence plainPlacement; // established on that worker before publishing this display source
        volatile HologramState state;
        String hash;
        byte[] artifact;
        String artifactHash;
        volatile BufferedImage thumbnail;

        /** IO worker only. Preserve existing malformed-source errors rather than treating them as legacy. */
        void requireStoredUse() throws java.io.IOException {
            if (draft != null || checkedStoredUse) return;
            if (compactSource) {
                if (plainPlacementEvidence() == null) {
                    if (publicId.isEmpty() && !localId.isEmpty())
                        com.miaokatze.gtmb.hologram.HologramBlueprintPreviewBinding.validateLocalStoredSyntax(json);
                    else com.miaokatze.gtmb.hologram.HologramBlueprintPreviewBinding.validateStoredSyntax(json);
                }
            } else HologramCompactRequiredException.require(json);
            checkedStoredUse = true;
        }

        DisplaySource(String key, String stamp, byte[] json, NBTTagCompound snapshot, String localId, String publicId,
            String hash, String revision) {
            this(key, stamp, json, snapshot, localId, publicId, hash, revision, null, false);
        }

        private DisplaySource(String key, String stamp, byte[] json, NBTTagCompound snapshot, String localId,
            String publicId, String hash, String revision, FrozenDraft draft, boolean indexedDisplayPath) {
            this.indexedDisplayPath = indexedDisplayPath;
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

        static DisplaySource previewDraft(FrozenDraft draft, HologramBlueprintPreview.DisplayInput input) {
            DisplaySource source = new DisplaySource("draft", "", null, input.snapshot, "", "", "", "", draft, false);
            source.compactCells = input.cells;
            source.savedMaterials = input.savedMaterials;
            source.plainPlacement = input.plainPlacement;
            return source;
        }

        Evidence plainPlacementEvidence() {
            if (plainPlacement == null) return null;
            if (draft != null) return "draft".equals(key) && json == null && localId.isEmpty() && publicId.isEmpty()
                && plainPlacement.sameCapture(draft) ? plainPlacement : null;
            return indexedDisplayPath && plainPlacement.sameCapture(json) ? plainPlacement : null;
        }

        /** Historical observation on this capture; not a live index check, a pair receipt, or placement authority. */
        boolean capturedIndexedMaterialSource() {
            return indexedDisplayPath && savedMaterials != null;
        }
    }

    private boolean downloadExpected, configWritePending, draftPending;
    private String pendingConfigRequestId = "";
    private long confirmedConfigRevision = -1;
    private boolean incomingCapturePreview;
    private boolean incomingLocalSaveReceipt;
    private HologramBlueprintPreview.Request previewCapture;
    private HologramSavePreviewPose savePreviewPose;
    private Runnable pendingPreviewStart;
    private UUID libraryActor;
    private PendingDiskSave pendingDiskSave;
    private volatile NBTTagCompound saveWitness = new NBTTagCompound();
    private long lastSaveStatus;
    private NBTTagList saveTransitions = new NBTTagList();
    private NBTTagCompound lastSaveTransition = new NBTTagCompound();
    private int saveTraceEvents, downloadProgressMarker;
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
        HologramNetwork.setLibraryReceiver((reply, sender) -> {
            long token = generation;
            Minecraft mc = Minecraft.getMinecraft();
            mc.func_152344_a(() -> {
                String op = reply.getString("op");
                if ("uploaded".equals(op)) {
                    if (libraryConnectionCurrent(mc)) receivePublicUpload(reply, sender, System.nanoTime());
                } else if (token == generation) {
                    PublicRead read = publicRead;
                    boolean ownedCopy = read != null && read.use() == PublicUse.COPY
                        && (op.startsWith("download") || "error".equals(op));
                    if (!ownedCopy || sender == libraryConnection && libraryConnectionCurrent(mc)) receive(reply);
                }
            });
        });
    }

    void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        HologramClientProjectedPlacement.tick(mc);
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
            auxiliaryLibraryError = "";
            localCatalogProgress.clear();
            serverCatalogProgress.clear();
            settingsProgress.clear();
            configWritePending = false;
            pendingConfigRevision = -1;
            publicUpload = null;
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
        PublicUpload attempt = publicUpload;
        if (attempt != null && !attempt.expiryReported
            && attempt.expired(System.nanoTime())
            && backgroundTaskToken == 0
            && !busy()) {
            attempt.expiryReported = true;
            status = "上次上传结果未确认；重新上传前请先检查服务器蓝图库";
        }
        if (lastDisplayFrame == 0 || System.nanoTime() - lastDisplayFrame >= 60_000_000L) tickBrowsePreview(5_000_000L);
        if (quickPreview && (!sameQuickTool(mc) || !quickScreenAllowed(mc))) cancelQuickPlacement();
        if ((previewCapture != null || pendingPreviewStart != null
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
                || previewCapture != null)
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
        quickToolId = held.hasTagCompound() ? held.getTagCompound()
            .getString("gtitHologramToolId") : "";
        String fallback = !lastIntegratedPreview.isEmpty() ? lastIntegratedPreview
            : held.hasTagCompound() ? held.getTagCompound()
                .getString("gtitHologramPreset") : "";
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
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                    .warn("Unable to read historical blueprint catalog", unavailable);
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
                source = 0;
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
        upgradeLocal(null, refreshCatalog);
    }

    void upgradeLocal(String namedAlias, Runnable refreshCatalog) {
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
            java.util.function.BooleanSupplier current = () -> token == generation
                && upgrading.task == backgroundTaskToken
                && upgradeTask == upgrading;
            java.util.function.BiConsumer<Integer, Integer> progress = (done, total) -> {
                if (!current.getAsBoolean()) return;
                upgrading.done = done;
                upgrading.total = total;
                upgrading.progressedAt = System.nanoTime();
            };
            String result;
            if (namedAlias == null) result = store.upgradeLegacy(current, progress);
            else {
                progress.accept(0, 1);
                result = store.upgradeNamedHistoric(namedAlias, current);
                progress.accept(1, 1);
            }
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
        }, error -> {
            if (token != generation || upgrading.task != backgroundTaskToken || upgradeTask != upgrading) return;
            upgradeTask = null;
            preparing = false;
            status = "升级蓝图失败：" + HologramUiText.reason(error.getMessage());
            finishBackgroundTask(upgrading.task, false, status);
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
        PublicCopySave copy = publicCopySave;
        if (copy != null) {
            copy.retire();
            publicCopySave = null;
        }
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
        return auxiliaryLibraryError.isEmpty() ? HologramUiText.reason(status) : auxiliaryLibraryError;
    }

    /** This single-line error is already scoped to the library; do not relabel it as preview validation. */
    private static String auxiliaryLibraryError(String message) {
        String raw = message.substring(0, Math.min(message.length(), 240));
        String detail = EnumChatFormatting.getTextWithoutFormattingCodes(raw).replaceAll("\\p{Cntrl}", " ").trim();
        if (detail.isEmpty()) return "服务器蓝图库请求失败";
        return detail.startsWith("服务器蓝图库") ? detail : "服务器蓝图库：" + detail;
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
            || placementRequest != null
            || publicUpload != null && publicUpload.committedId != null
                && backgroundTaskToken == publicUpload.window.task()
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
        else if (displayIndex != null) transferProgress.update(identity, "snapshot.regions", 0, 0, "", false, now);
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
            auxiliaryLibraryError = "";
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
        if (busy() || selectedLocal.isEmpty()
            || !allowPublicUploadRetry(System.nanoTime())
            || !beginBackgroundTask("上传蓝图")) return;
        ensureStore();
        HologramClientLibrary targetStore = store;
        Object targetHandler = libraryConnection, targetWorld = libraryWorld;
        UUID targetActor = libraryActor;
        String id = selectedLocal;
        long token = generation;
        String targetServer = connection;
        preparing = true;
        localProgressPhase = "library.read";
        status = "正在读取客户端蓝图，准备上传";
        work(() -> {
            byte[] bytes = targetStore.publicationArtifact(id);
            String hash = HologramBlueprintArtifact.hash(bytes);
            String replaceId = targetStore.uploadedId(targetServer, id);
            main(() -> {
                if (generation != token || !libraryConnectionCurrent(Minecraft.getMinecraft())) return;
                preparing = false;
                outgoing = bytes;
                outgoingId = UUID.randomUUID()
                    .toString();
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
                publicUpload = new PublicUpload(
                    targetStore,
                    uploadWindow,
                    targetHandler,
                    targetWorld,
                    targetActor,
                    targetServer,
                    id,
                    replaceId,
                    hash);
                HologramNetwork.sendLibrary(begin);
            });
        });
    }

    private boolean allowPublicUploadRetry(long now) {
        PublicUpload attempt = publicUpload;
        if (attempt == null) return true;
        if (attempt.committedId != null) {
            if (!attempt.linking) linkPublicUpload(attempt);
            return false;
        }
        if (!attempt.expired(now)) {
            status = "正在等待服务器确认上次上传结果，请不要重复发布";
            return false;
        }
        if (attempt.checked) {
            publicUpload = null;
            return true;
        }
        status = "上次上传结果未确认；请检查服务器蓝图库，再决定是否重新上传";
        if (!attempt.checkRequested && !refreshingServer) {
            attempt.checkRequested = true;
            refreshServer(page);
        }
        return false;
    }

    private void receivePublicUpload(NBTTagCompound reply, Object sender, long now) {
        PublicUpload attempt = publicUpload;
        if (attempt == null || libraryConnection != sender
            || !attempt.matchesOwner(sender, libraryWorld, libraryActor, connection)
            || !attempt.accept(reply, now)) return;
        if (!attempt.cancelled) {
            lastActivity = now;
            outgoing = null;
            uploadWindow = null;
            outgoingId = "";
            ready = inFlight = false;
            status = "已上传至服务器蓝图库";
            progressCompleted();
            selectedServer = attempt.committedId;
            refreshServer(page);
        }
        linkPublicUpload(attempt);
    }

    private void linkPublicUpload(PublicUpload attempt) {
        attempt.linking = true;
        try {
            // Once a receipt is validated, only this identity may update the durable alias map.
            // Cancellation/generation cleanup must not discard that write.
            worker.execute(() -> {
                try {
                    attempt.persistLink();
                    main(() -> publicUploadLinked(attempt, true));
                } catch (IOException | RuntimeException failure) {
                    main(() -> publicUploadLinked(attempt, false));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            publicUploadLinked(attempt, false);
        }
    }

    private void publicUploadLinked(PublicUpload attempt, boolean saved) {
        if (publicUpload != attempt) return;
        attempt.linking = false;
        if (saved) publicUpload = null;
        String detail = saved ? attempt.cancelled ? "取消前服务器已发布蓝图，本地关联已保留" : "蓝图已上传" : "服务器已发布蓝图，但本地关联未保存；再次点击只重试保存关联";
        if (backgroundTaskToken == attempt.window.task()) {
            finishBackgroundTask(attempt.window.task(), true, detail);
            status = detail;
        } else if (backgroundTaskToken == 0 && !busy()) status = detail;
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
            DisplaySource source = readSavedDisplaySource(id, store.savedDisplayPath(id), !placement, token, epoch);
            if (placement) source.requireStoredUse();
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
                draft,
                () -> token == generation,
                (done, total) -> observeDisplay(token, "library.parse", done, total, "cells"));
            DisplaySource source = DisplaySource.previewDraft(draft, input);
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
        return readDisplaySource(key, path, localId, publicId, false, generation, browseEpoch, false);
    }

    DisplaySource readSavedDisplaySource(String id, HologramClientLibrary.SavedDisplayPath selected, boolean thumbnail,
        long token, long epoch) throws java.io.IOException {
        return readDisplaySource("local:" + id, selected.path(), id, "", thumbnail, token, epoch, selected.indexed());
    }

    private DisplaySource readDisplaySource(String key, java.nio.file.Path path, String localId, String publicId,
        boolean thumbnail, long token, long epoch, boolean indexedDisplayPath) throws java.io.IOException {
        String stamp = fileStamp(path);
        DisplaySource cached;
        synchronized (localPreviews) {
            cached = localPreviews.get(key);
        }
        if (cached != null && stamp.equals(cached.stamp)
            && localId.equals(cached.localId)
            && publicId.equals(cached.publicId)
            && indexedDisplayPath == cached.indexedDisplayPath) {
            // The existing stamp is a reuse hint, not proof that on-disk content still equals the captured bytes.
            if (thumbnail && cached.thumbnail == null) queueBrowseImage(cached, path, token, epoch);
            return cached;
        }
        byte[] json = readDisplayBytes(
            path,
            publicId.isEmpty() ? com.miaokatze.gtmb.hologram.HologramBlueprintLimits.LOCAL_FILE_BYTES
                : HologramBlueprintArtifact.MAX_JSON_BYTES);
        if (!stamp.equals(fileStamp(path))) throw new java.io.IOException("蓝图文件已变化，请重试");
        DisplaySource source = new DisplaySource(
            key,
            stamp,
            json,
            null,
            localId,
            publicId,
            "",
            "",
            null,
            indexedDisplayPath);
        if (thumbnail) queueBrowseImage(source, path, token, epoch);
        observeDisplay(token, "library.parse", 0, 0, "");
        // savedPath also serves untracked portable files. A local selection is not durable index membership.
        boolean savedPrivate = publicId.isEmpty() && localId.matches("local_[a-fA-F0-9-]{36}")
            && key.equals("local:" + localId);
        HologramBlueprintPreview.DisplayInput input = HologramBlueprintPreview.displayInput(
            json,
            (done, total) -> observeDisplay(token, "library.parse", done, total, "cells"),
            savedPrivate,
            indexedDisplayPath);
        source.snapshot = input.snapshot;
        source.compactCells = input.cells;
        source.savedMaterials = input.savedMaterials;
        source.compactSource = input.cells != null;
        source.plainPlacement = input.plainPlacement;
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
            DisplaySource display = !source.publicLibrary && !source.localId.isEmpty() ? readSavedDisplaySource(
                source.localId,
                store.savedDisplayPath(source.localId),
                false,
                token,
                browseEpoch) : null;
            if (display != null) display.requireStoredUse();
            main(() -> {
                if (token != generation) return;
                if (display != null) startDisplay(display, true, browseEpoch);
                else {
                    preparing = false;
                    if (source.publicLibrary) {
                        CachedPublicCopy saved = cachedPublicCopy(listedPublicIdentity(source.publicId), PublicUse.PLACE);
                        if (saved != null) {
                            finishBackgroundTask(backgroundTaskToken, true, "已读取最近蓝图的本地记录");
                            previewPublic(source.publicId);
                            quickPreview = preparing;
                        } else {
                            String detail = missingPublicCopy(PublicUse.PLACE);
                            finishBackgroundTask(backgroundTaskToken, false, detail);
                            HologramClient.localPlacementNotice(detail);
                        }
                    } else if (!legacyFallback.isEmpty()) {
                        finishBackgroundTask(backgroundTaskToken, true, "已读取最近蓝图的本地记录");
                        previewIntegrated(legacyFallback, true);
                        quickPreview = preparing;
                    } else {
                        String detail = "没有可预览的最近蓝图，请先从蓝图库选择";
                        finishBackgroundTask(backgroundTaskToken, false, detail);
                        HologramClient.localPlacementNotice(detail);
                    }
                }
            });
        });
        return true;
    }

    void previewPublic(String id) {
        previewPublic(id, true);
    }

    void previewPublic(String id, boolean placement) {
        if (!dedicated() && (id == null || !id.startsWith("public_"))) {
            previewIntegrated(id, placement);
            return;
        }
        readPublic(id, placement ? PublicUse.PLACE : PublicUse.BROWSE, null, null);
    }

    void openPublic(String id, java.util.function.Consumer<byte[]> open, java.util.function.Consumer<String> failure) {
        readPublic(id, PublicUse.EDIT, open, failure);
    }

    private static boolean publicId(String id) {
        return id != null && id.startsWith("public_") && canonicalProjectorId(id.substring(7));
    }

    private PublicIdentity listedPublicIdentity(String id) {
        for (int i = 0; i < server.tagCount(); i++) {
            NBTTagCompound row = server.getCompoundTagAt(i);
            if (!id.equals(row.getString("id"))) continue;
            PublicIdentity listed = PublicIdentity.from(row);
            if (listed.complete()) return listed;
            break;
        }
        return new PublicIdentity(id, "", 0);
    }

    private CachedPublicCopy cachedPublicCopy(PublicIdentity identity, PublicUse use) {
        if (identity.complete()) return publicArtifacts.get(identity);
        if (use == PublicUse.COPY) return null; // A deliberate re-download without a revision asks for the current file.
        CachedPublicCopy exact = null;
        for (CachedPublicCopy candidate : publicArtifacts.values()) {
            if (!candidate.identity().id().equals(identity.id())) continue;
            if (exact != null) return null; // Do not present an arbitrary saved revision as the selected public copy.
            exact = candidate;
        }
        return exact;
    }

    private static String missingPublicCopy(PublicUse use) {
        return switch (use) {
            case PLACE -> "此公库蓝图没有可用的已下载副本。请手动下载，或从客户端蓝图库选择已保存的副本来放置。";
            case EDIT -> "此公库蓝图没有可用的已下载副本。请手动下载，或在客户端蓝图库打开已保存的副本。";
            default -> "此公库蓝图尚无可在本机预览的副本。请使用下载按钮。";
        };
    }

    private void readPublic(String id, PublicUse use, java.util.function.Consumer<byte[]> open,
        java.util.function.Consumer<String> failure) {
        if (!canReadPublic()) {
            status = "你所在的权限组不允许读取公共蓝图；已保存的副本仍可在客户端蓝图库打开";
            if (failure != null) failure.accept(status);
            return;
        }
        if (busy() || !publicId(id)) {
            if (failure != null) failure.accept("当前无法读取服务器蓝图，请重试");
            return;
        }
        PublicIdentity identity = listedPublicIdentity(id);
        CachedPublicCopy cached = cachedPublicCopy(identity, use);
        if (cached == null && use != PublicUse.COPY) {
            auxiliaryLibraryError = "";
            status = missingPublicCopy(use);
            if (use == PublicUse.BROWSE) {
                beginBrowsePreview(false);
                browseStatus = status;
            }
            if (failure != null) failure.accept(status);
            else if (use == PublicUse.PLACE) HologramClient.localPlacementNotice(status);
            return; // A cold local use never creates a task, requests files, or silently switches sources.
        }
        if (!beginBackgroundTask(use == PublicUse.COPY ? "下载服务器蓝图" : "读取已下载的蓝图")) {
            if (failure != null) failure.accept("当前已有蓝图库任务");
            return;
        }
        ensureStore();
        long epoch = use == PublicUse.BROWSE || use == PublicUse.PLACE ? beginBrowsePreview(use == PublicUse.PLACE)
            : browseEpoch;
        PublicRead read = new PublicRead(cached == null ? identity : cached.identity(), use, epoch, open, failure);
        publicRead = read;
        if (use == PublicUse.COPY) publicCopySave = new PublicCopySave(generation, backgroundTaskToken, store);
        preparing = true;
        if (cached != null) {
            localDisplayTask = true;
            status = "正在读取已下载的蓝图副本";
            long token = generation;
            String serverKey = connection;
            work(() -> useCachedPublicCopy(read, cached, token, serverKey), error -> {
                if (use == PublicUse.COPY) {
                    if (token == generation && publicRead == read)
                        failTask("download.copy", "已下载副本未能保存：" + HologramUiText.reason(error.getMessage()));
                } else failPublicRead(read, token, missingPublicCopy(use));
            });
            return;
        }
        NBTTagCompound request = request("download");
        request.setString("id", id);
        request.setString(HologramBusinessWirePolicy.DOWNLOAD_INTENT_KEY, HologramBusinessWirePolicy.PUBLIC_COPY);
        if (identity.complete()) {
            request.setString("sourceHash", identity.hash());
            request.setLong("sourceRevision", identity.revision());
        }
        if (HologramBusinessWirePolicy.libraryRequest(request, HologramBusinessWirePolicy.Owner.NONE,
            HologramBusinessWirePolicy.Origin.EXPLICIT_LIBRARY_DOWNLOAD) != HologramBusinessWirePolicy.Intent.LIBRARY_DOWNLOAD) {
            publicCopySave = null;
            failPublicRead(read, generation, "蓝图下载任务已变化，未发送下载请求");
            return;
        }
        downloadExpected = true;
        status = "正在下载服务器蓝图文件";
        HologramNetwork.sendLibrary(request);
    }

    private void failPublicRead(PublicRead read, long token, String detail) {
        if (token != generation || publicRead != read) return;
        if (read.use() == PublicUse.BROWSE && discardedBrowse(token, read.browseEpoch(), false)) {
            publicRead = null;
            return;
        }
        publicRead = null;
        preparing = false;
        status = detail;
        if (read.use() == PublicUse.BROWSE) browseStatus = detail;
        finishBackgroundTask(backgroundTaskToken, false, detail);
        if (read.failure() != null) read.failure().accept(detail);
        else if (read.use() == PublicUse.PLACE) HologramClient.localPlacementNotice(detail);
    }

    private void useCachedPublicCopy(PublicRead read, CachedPublicCopy cached, long token, String serverKey)
        throws IOException {
        if (token != generation) return;
        PublicIdentity identity = cached.identity();
        HologramBlueprintArtifact.Artifact artifact = cached.artifact();
        if (read.use() == PublicUse.COPY) {
            persistDownloadedPublicCopy(identity, artifact, token, serverKey);
            return;
        }
        if (read.use() == PublicUse.EDIT) {
            main(() -> {
                if (token != generation || publicRead != read) return;
                publicRead = null;
                preparing = false;
                finishBackgroundTask(backgroundTaskToken, true, "已读取下载到本机的蓝图副本");
                read.open().accept(artifact.json);
            });
            return;
        }
        DisplaySource display;
        if (read.use() == PublicUse.PLACE) {
            var local = store.savedDisplayPath(cached.localId());
            if (!local.indexed()) throw new IOException("Previously downloaded private copy no longer indexed");
            display = readSavedDisplaySource(cached.localId(), local, false, token, read.browseEpoch());
            if (!artifact.jsonSha256.equals(HologramBlueprintArtifact.hash(display.json)))
                throw new IOException("Previously downloaded private copy was edited");
            display.requireStoredUse();
        } else {
            String key = "public:" + identity.id();
            String stamp = identity.hash() + ":" + identity.revision();
            DisplaySource prepared;
            synchronized (localPreviews) {
                prepared = localPreviews.get(key);
            }
            if (prepared == null || !stamp.equals(prepared.stamp)) {
                HologramBlueprintPreview.DisplayInput input = HologramBlueprintPreview.displayInput(
                    artifact.json,
                    (done, total) -> observeDisplay(token, "library.parse", done, total, "cells"));
                prepared = new DisplaySource(key, stamp, artifact.json, input.snapshot, "", identity.id(),
                    identity.hash(), String.valueOf(identity.revision()));
                prepared.compactCells = input.cells;
                prepared.compactSource = input.cells != null;
                prepared.thumbnail = decodeDisplayImage(artifact.png);
            }
            display = prepared;
        }
        main(() -> {
            if (token != generation || publicRead != read) return;
            publicRead = null;
            boolean placement = read.use() == PublicUse.PLACE;
            if (discardedBrowse(token, read.browseEpoch(), placement)) return;
            startDisplay(display, placement, read.browseEpoch());
        });
    }

    private void persistDownloadedPublicCopy(PublicIdentity identity, HologramBlueprintArtifact.Artifact artifact,
        long token, String serverKey) throws IOException {
        if (token != generation) return;
        HologramCompactRequiredException.require(artifact.json);
        PublicCopySave copy = publicCopySave;
        if (copy != null && copy.generation == token) persistPublicCopy(copy, identity, artifact, serverKey);
    }

    private void retainPublicCopy(CachedPublicCopy copy) {
        CachedPublicCopy previous = publicArtifacts.put(copy.identity(), copy);
        if (previous != null) publicArtifactBytes -= previous.retainedBytes();
        publicArtifactBytes += copy.retainedBytes();
        java.util.Iterator<CachedPublicCopy> entries = publicArtifacts.values().iterator();
        while (publicArtifacts.size() > 16 || publicArtifactBytes > 64L * 1024 * 1024) {
            publicArtifactBytes -= entries.next().retainedBytes();
            entries.remove();
        }
    }

    private void forgetCachedPublicCopy(String localId) {
        java.util.Iterator<CachedPublicCopy> entries = publicArtifacts.values().iterator();
        while (entries.hasNext()) {
            CachedPublicCopy copy = entries.next();
            if (!copy.localId().equals(localId)) continue;
            publicArtifactBytes -= copy.retainedBytes();
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
                    if (browseState == null || !HologramRenderer.browse()
                        .previewMeshVisible(browseState)) previewImage.replace(image);
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
            HologramClientLibrary.SavedDisplayPath saved = localId.matches("local_[a-fA-F0-9-]{36}")
                ? store.savedDisplayPath(localId)
                : null;
            if (saved != null && java.nio.file.Files.isRegularFile(saved.path()))
                source = readSavedDisplaySource(localId, saved, !placement, token, epoch);
            else {
                String key = id.startsWith("legacy:") ? id : "legacy:" + id;
                String stamp = fileStamp(store.legacyCatalogPath());
                synchronized (localPreviews) {
                    source = localPreviews.get(key);
                }
                if (source == null || !stamp.equals(source.stamp)) {
                    byte[] json = store.readLegacyDocument(id);
                    if (!stamp.equals(fileStamp(store.legacyCatalogPath())))
                        throw new java.io.IOException("蓝图库已变化，请重试");
                    HologramBlueprintPreview.DisplayInput input = HologramBlueprintPreview.displayInput(
                        json,
                        (done, total) -> observeDisplay(token, "library.parse", done, total, "cells"));
                    source = new DisplaySource(key, stamp, json, input.snapshot, "", "", "", "");
                    source.compactCells = input.cells;
                    source.compactSource = input.cells != null;
                }
            }
            if (placement) source.requireStoredUse();
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
            + (source.savedMaterials == null ? 0L : source.savedMaterials.estimatedBytes())
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
        return libraryConnectionCurrent(mc) && (!quickPreview || sameQuickTool(mc) && quickScreenAllowed(mc));
    }

    private boolean libraryConnectionCurrent(Minecraft mc) {
        return mc.theWorld != null && mc.theWorld == libraryWorld
            && mc.getNetHandler() == libraryConnection
            && mc.thePlayer != null
            && mc.thePlayer.getUniqueID()
                .equals(libraryActor);
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
            displayPreparing,
            generation,
            backgroundTaskToken,
            displayEpoch,
            displayPlacement);
        displayIndex = ticket;
        browsePreparation = null;
        HologramState prefix = index.firstDisplay();
        if (!ticket.placement() && prefix != null && ticket.epoch() == browseEpoch) {
            browseState = prefix;
            displayFirstPublished = true;
        }
        work(() -> {
            HologramState complete = index.finish(() -> displayIndex == ticket && ticket.generation() == generation);
            if (complete != null) main(() -> {
                if (!displayIndexCurrent(ticket)) return;
                ticket.source().state = complete;
                displayReady();
            });
        },
            failure -> {
                if (displayIndexCurrent(ticket))
                    failTask("preview.decode", "Preview unavailable: " + failure.getMessage());
            });
    }

    private boolean displayIndexCurrent(DisplayIndex ticket) {
        return displayIndex == ticket && ticket.generation() == generation
            && ticket.task() == backgroundTaskToken
            && ticket.source() == displayPreparing
            && ticket.placement() == displayPlacement
            && (ticket.placement() || ticket.epoch() == browseEpoch)
            && displayContextCurrent();
    }

    /** The existing confirmed-click owner, not a construction capability or a server material quote. */
    static final class PlainPlacementCandidate {

        final UUID request;
        final long generation;
        final HologramLocalPlacement.Submission submission;
        final int slot;
        final String toolId;
        private final ItemStack tool;
        private final HologramPrivatePlainPlacementSource source;

        private PlainPlacementCandidate(UUID request, long generation, HologramLocalPlacement.Submission submission,
            int slot, String toolId, ItemStack tool, HologramPrivatePlainPlacementSource source) {
            this.request = request;
            this.generation = generation;
            this.submission = submission;
            this.slot = slot;
            this.toolId = toolId;
            this.tool = tool;
            this.source = source;
        }

        private boolean currentSource(HologramLibraryClient owner, Minecraft client) {
            return client != null && workerCurrent(owner) && submission.current(HologramLocalPlacement.INSTANCE, client)
                && owner.libraryConnectionCurrent(client) && !client.thePlayer.isDead
                && client.thePlayer.inventory.currentItem == slot;
        }

        /** Only the exact clicked held stack may negotiate and offer a manifest. */
        boolean currentBeforeAdmission(HologramLibraryClient owner, Minecraft client) {
            return currentSource(owner, client) && client.thePlayer.getHeldItem() == tool
                && toolId.equals(projectorId(tool));
        }

        /** Resolve logical identity only after the current job receives positive authenticated admission. */
        boolean currentAfterAdmission(HologramLibraryClient owner, Minecraft client, String admittedId) {
            if (!currentSource(owner, client)) return false;
            ItemStack held = client.thePlayer.getHeldItem();
            return com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(held)
                && admittedId.equals(projectorId(held)) && (held == tool || !admittedId.isEmpty());
        }

        String admittedToolId(Minecraft client) {
            return client.thePlayer != null && client.thePlayer.getHeldItem() == tool ? projectorId(tool) : toolId;
        }

        boolean workerCurrent(HologramLibraryClient owner) {
            return generation == owner.generation && request.equals(owner.placementRequest);
        }

        int cells() {
            return source.cells();
        }

        HologramProjectedLayerEncoder.Prepared prepare(HologramLibraryClient owner,
            java.util.function.LongConsumer observed) throws IOException {
            return HologramProjectedLayerEncoder.prepare(
                request, source.anchor(0), source.anchor(1), source.anchor(2), source,
                () -> workerCurrent(owner), observed);
        }
    }

    private static String projectorId(ItemStack tool) {
        return tool == null || !tool.hasTagCompound() ? ""
            : tool.getTagCompound().getString("gtitHologramToolId");
    }

    static boolean canonicalProjectorId(String id) {
        if (id == null || id.length() != 36) return false;
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    enum PlainAvailability {
        READY(""),
        SOURCE("此蓝图暂不支持分片施工，仍可查看材料与预览"),
        MODE("目前仅支持有编辑权限的生存施工"),
        MATERIALS("分片施工只从主背包扣料；需启用主背包，且容器不能优先于主背包"),
        TOOL("该投影仪尚未取得可验证的施工身份"),
        TRANSPORT("此客户端未启用分片施工"),
        BUSY("已有任务，暂不能提交施工");

        final String reason;

        PlainAvailability(String reason) {
            this.reason = reason;
        }
    }

    PlainAvailability plainPlacementAvailability(HologramLocalPlacement.Submission submission, Minecraft client) {
        DisplaySource source = submission == null ? null : submission.source();
        var player = client == null ? null : client.thePlayer;
        ItemStack held = player == null ? null : player.getHeldItem();
        PlainAvailability support = plainPlacementAvailability(
            source != null && source.plainPlacementEvidence() != null,
            player != null && player.capabilities.allowEdit && !player.capabilities.isCreativeMode,
            HologramMaterials.read(held),
            canonicalProjectorId(projectorId(held)),
            HologramClientProjectedPlacement.transportRegistered());
        return support == PlainAvailability.READY && (busy() || HologramBackgroundTasks.busy()) ? PlainAvailability.BUSY
            : support;
    }

    static PlainAvailability plainPlacementAvailability(boolean exactSource, boolean allowedSurvival,
        HologramMaterials.Settings sources, boolean identifiedTool, boolean registered) {
        if (!exactSource) return PlainAvailability.SOURCE;
        if (!allowedSurvival) return PlainAvailability.MODE;
        if (!HologramProjectedMainPolicy.eligibleSelected(sources)) return PlainAvailability.MATERIALS;
        if (!identifiedTool) return PlainAvailability.TOOL;
        return registered ? PlainAvailability.READY : PlainAvailability.TRANSPORT;
    }

    /**
     * Only the existing confirmed construction click can enter. Unsupported public/draft/advanced paths
     * do not inspect or transmit a full file and never fall back to the legacy geometry upload.
     */
    boolean prepareLocalPlacement(HologramLocalPlacement.Submission submission, boolean direct) {
        if (!direct || submission == null || !submission.current()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        PlainAvailability availability = plainPlacementAvailability(submission, mc);
        if (availability != PlainAvailability.READY) {
            if (availability == PlainAvailability.TRANSPORT) HologramClientProjectedPlacement.logMissingTransport();
            return false;
        }
        if (!libraryConnectionCurrent(mc) || mc.thePlayer.isDead) return false;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector.isProjector(held)) return false;
        UUID request = UUID.randomUUID();
        long token = generation;
        placementRequest = request;
        try {
            var source = HologramPrivatePlainPlacementSource.bind(
                submission, mc, () -> token == generation && request.equals(placementRequest));
            var candidate = new PlainPlacementCandidate(
                request, token, submission, mc.thePlayer.inventory.currentItem, projectorId(held), held, source);
            if (HologramClientProjectedPlacement.start(this, candidate, mc)) return true;
        } catch (IOException notPlainOrContextChanged) {
            // Preserve local preview and material inspection; an unsupported construction has no alternate wire.
        }
        placementRequest = null;
        return false;
    }

    void projectedPlacementEnded(UUID request, boolean completed) {
        if (!request.equals(placementRequest)) return;
        placementRequest = null;
        if (completed) HologramLocalPlacement.INSTANCE.clear();
        else HologramLocalPlacement.INSTANCE.retry();
    }

    void projectedWork(Job job, java.util.function.Consumer<Exception> failure) {
        work(job, failure);
    }

    void projectedMain(Runnable action) {
        main(action);
    }

    /** Never let a surviving legacy reply become authority for the sliced placement route. */
    void projectedPlacementProgress(NBTTagCompound progress) {}

    boolean acceptProjectedPlacement(NBTTagCompound receipt) {
        return receipt.hasKey("placementRequestId", 8);
    }

    boolean cancelProjectedPlacement() {
        return HologramClientProjectedPlacement.stopCurrent();
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
                forgetCachedPublicCopy(id);
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

    /** A staged pair stays owned until its matching receipt resolves commit versus rollback. */
    boolean cancelBackgroundTask() {
        cancel();
        return pendingDiskSave == null && publicCopySave == null;
    }

    void cancel() {
        cancel("蓝图任务已取消", true);
    }

    private void cancel(String reason, boolean cancelled) {
        PublicCopySave copy = publicCopySave;
        if (cancelled && copy != null && !copy.retire()) {
            status = "本地蓝图已进入保存提交，正在确认文件";
            return;
        }
        savePreviewPose = null;
        boolean localDisplay = localDisplayTask || placementRequest != null;
        long cancelledTask = backgroundTaskToken;
        PublicUpload attempt = publicUpload;
        if (attempt != null && !attempt.cancelled
            && attempt.committedId == null
            && attempt.window.task() == cancelledTask) {
            if (attempt.window.fullySent()) {
                attempt.cancelled = true;
                attempt.cancelledAt = System.nanoTime();
                if (cancelled) reason = "已请求取消；服务器可能已发布，正在等待确认";
            } else publicUpload = null;
        }
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
        if (abandoned != null && abandoned.failure() != null) abandoned.failure()
            .accept(reason);
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
        if (placementRequest == null) HologramLocalPlacement.INSTANCE.retry();
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
        if ("error".equals(op) || "deleted".equals(op)
            || "saveCommitted".equals(op)
            || "saveRejected".equals(op)
            || !draftPending && ("list".equals(op) || "config".equals(op))) lastActivity = System.nanoTime();
        if ("saveCommitted".equals(op) || "saveRejected".equals(op)) {
            finishDiskSave(reply, "saveCommitted".equals(op));
        } else if ("list".equals(op)) {
            boolean resolvesCatalog = refreshingServer;
            server = reply.getTagList("entries", 10);
            page = reply.getInteger("page");
            pages = Math.max(1, reply.getInteger("pages"));
            serverCatalogReplies = Math.max(0, serverCatalogReplies - 1);
            refreshingServer = serverCatalogReplies > 0;
            if (!refreshingServer) {
                if (resolvesCatalog) auxiliaryLibraryError = "";
                serverCatalogProgress.finish(System.nanoTime());
                if (publicUpload != null && publicUpload.checkRequested) publicUpload.checked = true;
            }
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
                    localPreviews.keySet()
                        .removeIf(key -> key.startsWith("public:"));
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
                auxiliaryLibraryError = "";
                confirmedConfigRevision = config.getLong("configRevision");
                configWritePending = false;
            }
        } else if ("uploadReady".equals(op)) {
            if (!matchesOutgoing(reply) || uploadWindow == null) return;
            try {
                if (uploadWindow.ready(reply)) {
                    ready = true;
                    lastActivity = System.nanoTime();
                }
            } catch (java.io.IOException invalid) {
                failSave(invalid.getMessage(), invalid);
            }
        } else if ("chunkAck".equals(op)) {
            if (!matchesOutgoing(reply)) return;
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
            inFlight = uploadWindow.inFlight();
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
                && reply.hasKey("localSaveReceipt", 1)
                && reply.getBoolean("localSaveReceipt")
                && !reply.getString("saveReceipt")
                    .isEmpty()
                && saveSourceAwaiting(generation, reply.getString("session"), reply.getLong("planRevision"));
            if (reply.getBoolean("capturePreview") && !nativeDraft) {
                failTask("download.source.rejected", "保存内容来源已变化，未保存修改保留，请重试");
                return;
            }
            if (!downloadExpected && !nativeDraft) return;
            if (!nativeDraft) {
                PublicRead requested = publicRead;
                PublicCopySave copy = publicCopySave;
                if (requested == null || requested.use() != PublicUse.COPY || copy == null || !copy.open()
                    || copy.generation != generation || !HologramLibraryDownload.taskMatches(copy.task, reply)) return;
                PublicIdentity offered = PublicIdentity.from(reply);
                if (!"public".equals(reply.getString("purpose"))
                    || !offered.complete()
                    || !offered.id()
                        .equals(
                            requested.identity().id())
                    || requested.identity().complete() && !requested.identity().equals(offered)) {
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
                saveTraceEvents = downloadProgressMarker = 0;
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
            // Done retires the public transfer; verification, import, and display from here are local.
            if (publicRequest != null && publicIdentity != null && !capturePreview) localDisplayTask = true;
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
                if (publicRequest != null && publicRequest.use() == PublicUse.COPY && publicIdentity != null) {
                    HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.decode(bytes);
                    if (!com.miaokatze.gtmb.hologram.HologramBlueprintContentHash.of(artifact.json, publicIdentity.id())
                        .equals(publicIdentity.hash())) throw new java.io.IOException("服务器蓝图内容摘要不匹配");
                    persistDownloadedPublicCopy(publicIdentity, artifact, token, serverKey);
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
                                    serverKey),
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
            String message = reply.getString("message");
            // A token-0 catalogue/settings response is not a permitted owner or terminal for the typed job.
            if (placementRequest != null && reply.getLong("clientTaskToken") == 0) {
                auxiliaryLibraryError = auxiliaryLibraryError(message);
            } else {
                auxiliaryLibraryError = "";
                String reason = "服务器：" + HologramUiText.reason(message);
                HologramLocalPlacement.INSTANCE.retry();
                cancel(reason, false);
                status = reason;
            }
            refreshingServer = false;
            serverCatalogReplies = 0;
            if (publicUpload != null) publicUpload.checkRequested = false;
            localCatalogProgress.clear();
            serverCatalogProgress.clear();
            settingsProgress.clear();
            configWritePending = false;
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
        long revision, long token, String serverKey) {
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
    private void persistPublicCopy(PublicCopySave copy, PublicIdentity identity,
        HologramBlueprintArtifact.Artifact artifact, String serverKey) throws java.io.IOException {
        if (copy.generation != generation || !copy.open()) return;
        HologramClientLibrary.LocalSave local = copy.store.preparePrivateDocumentSave(
            artifact.json,
            artifact.png,
            copy.id,
            () -> copy.generation == generation && copy.open());
        if (!copy.claim()) {
            local.write.rollback();
            return;
        }
        local.write.commit();
        // The staged receipt already verified the exact JSON, PNG, and logical index. A preference write is not
        // part of the copy transaction and cannot turn an accepted pair into a failed download.
        boolean remembered = false;
        if (copy.generation == generation) try {
            copy.store.rememberLocal(serverKey, copy.id);
            remembered = true;
        } catch (java.io.IOException | RuntimeException ignored) {
            // The saved artifact remains available from the indexed local library.
        }
        boolean quickSourceAvailable = remembered;
        CachedPublicCopy cached = new CachedPublicCopy(identity, artifact, copy.id);
        main(() -> finishPublicCopy(copy, local, cached, quickSourceAvailable));
    }

    private void finishPublicCopy(PublicCopySave copy, HologramClientLibrary.LocalSave local, CachedPublicCopy cached,
        boolean remembered) {
        if (copy != publicCopySave || copy.generation != generation || copy.task != backgroundTaskToken) return;
        publicCopySave = null;
        publicRead = null;
        retainPublicCopy(cached);
        progressCompleted();
        selectedLocal = copy.id;
        if (remembered) knownQuickSource = 1;
        announceSaved(local.name, local.path);
        String detail = "蓝图已下载并保存 · " + copy.id;
        finishBackgroundTask(copy.task, true, detail);
        preparing = false;
        status = detail;
        refreshLocal();
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

    interface Job {

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
            if (error instanceof HologramCompactRequiredException) {
                cancel(error.getMessage(), false);
                return;
            }
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
