package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.Gson;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;

/** One IO worker, bounded mailboxes and aggregate fair transfer budgets; never sleeps on a server tick. */
final class HologramServerLibrary {

    private static final Gson GSON = new Gson();
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_PEERS = 64;
    private static final long MAX_STORED_BYTES = 64L * 1024 * 1024;
    static final long MAX_PUBLIC_DTO_BYTES = 512L * 1024 * 1024;
    static final int THUMBNAIL_BYTES = 16 * 1024;
    private static final long MAX_RESERVED_BYTES = 2L * HologramBlueprintArtifact.MAX_BYTES;
    private static final Field LIST_FIELD = listField();
    private final Path directory;
    private final ScheduledThreadPoolExecutor worker;
    private final Map<UUID, Peer> peers = new LinkedHashMap<>();
    private final HologramTrustedBlueprintCache trusted = new HologramTrustedBlueprintCache();
    private final java.util.ArrayDeque<Entry> materialPreviews = new java.util.ArrayDeque<>();
    private final LinkedHashMap<String, byte[]> previews = new LinkedHashMap<>(16, .75f, true);
    private long previewBytes;
    private final ArrayBlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(16);
    private volatile Map<String, Entry> published = Collections.emptyMap();
    private volatile Map<String, Entry> contentIndex = Collections.emptyMap();
    private volatile HologramLibraryConfig config;
    private volatile boolean closed;
    private volatile boolean ready;
    private volatile long runtimeEpoch;
    private volatile String loadFailure;
    private long storedBytes, publicDtoBytes, reservedBytes, downloadBytes, catalogRevision = 1;
    private int activeDownloads;
    private int activeUploads, cursor;
    private final HologramLibraryProtocol.FairBudget uploadBudget = new HologramLibraryProtocol.FairBudget(true);
    private final HologramLibraryProtocol.FairBudget downloadBudget = new HologramLibraryProtocol.FairBudget();

    HologramServerLibrary(Path directory, Path configFile) {
        this.directory = directory;
        worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "gtmb-blueprint-library");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.execute(() -> {
            try {
                config = new HologramLibraryConfig(configFile.toFile());
                load();
                ready = true;
            } catch (IOException | RuntimeException failure) {
                GTMiaoBlueprint.LOG.error("Server blueprint library could not load", failure);
                loadFailure = failure.getMessage() == null ? failure.getClass()
                    .getSimpleName() : failure.getMessage();
                closed = true;
            }
        });
        worker.scheduleAtFixedRate(this::pumpSafely, 50, 50, TimeUnit.MILLISECONDS);
    }

    synchronized void submit(EntityPlayerMP player, NBTTagCompound request, boolean admin) {
        if (!available()) {
            HologramLibraryFacade.error(player, unavailableMessage(), request.getLong("clientTaskToken"));
            HologramNetwork.sendLibraryReply(player, statusTag(admin));
            return;
        }
        Peer peer = peers.get(HologramLibraryFacade.actorId(player));
        if (peer == null || peer.player != player) {
            if (peer != null) {
                peer.online = false;
                trusted.retire(peer);
                if (peer.preparation != null) {
                    peer.preparation.valid = false;
                    tasks.remove(peer.preparation);
                    peer.preparation = null;
                }
                HologramLibraryFacade
                    .error(player, "Server library connection changed; retry", request.getLong("clientTaskToken"));
                return;
            }
            if (peers.size() >= MAX_PEERS) {
                java.util.Iterator<Peer> idle = peers.values()
                    .iterator();
                while (idle.hasNext()) {
                    Peer candidate = idle.next();
                    if (candidate.upload == null && candidate.download == null
                        && candidate.pendingDraft == null
                        && candidate.preparation == null
                        && candidate.materialPreview == null
                        && candidate.upgradeReport == null
                        && candidate.requests.isEmpty()) {
                        candidate.online = false;
                        trusted.retire(candidate);
                        idle.remove();
                        break;
                    }
                }
                if (peers.size() >= MAX_PEERS) {
                    HologramLibraryFacade.error(
                        player,
                        "Server library transfer queue is busy; retry later",
                        request.getLong("clientTaskToken"));
                    return;
                }
            }
            peer = new Peer(player);
            peers.put(peer.owner, peer);
        }
        peer.admin = admin;
        if ("cancel".equals(request.getString("op")) || "cancelTransfer".equals(request.getString("op"))) {
            // This runs on the server thread even while the sole IO pump is inside serialization.
            peer.retiredThrough = Math.max(peer.retiredThrough, request.getLong("clientTaskToken"));
            if (peer.preparation != null && (request.getLong("clientTaskToken") == 0
                || peer.preparation.token == request.getLong("clientTaskToken"))) {
                peer.preparation.valid = false;
                tasks.remove(peer.preparation);
                peer.preparation = null;
            }
            if (peer.download != null && (request.getLong("clientTaskToken") == 0
                || peer.download.clientTaskToken == request.getLong("clientTaskToken"))) peer.download.valid = false;
        }
        if (!peer.requests.offer(request, admitted(peer, request))) {
            error(peer, "Server library request queue is busy; retry later", request.getLong("clientTaskToken"));
        }
    }

    /** Immutable transfer identity plus volatile admission, without native world access on Netty. */
    synchronized boolean uploadAdmitted(EntityPlayerMP player, NBTTagCompound request) {
        if (!available()) return false;
        Peer peer = peers.get(HologramLibraryFacade.actorId(player));
        return peer != null && peer.player == player && admitted(peer, request);
    }

    private boolean admitted(Peer peer, NBTTagCompound request) {
        Upload upload = peer.upload;
        if (closed || !peer.online
            || upload == null
            || !upload.valid
            || !HologramLibraryProtocol.uploadChunk(request)
            || !upload.transfer.equals(request.getString("transfer"))
            || upload.request.getLong("clientTaskToken") != request.getLong("clientTaskToken")) return false;
        String op = request.getString("op");
        return upload.privateUpload == "privateChunk".equals(op) && upload.previewUpload == "previewChunk".equals(op);
    }

    synchronized void heartbeat() {
        for (Peer peer : peers.values()) {
            peer.online = peer.player.playerNetServerHandler != null
                && peer.player.playerNetServerHandler.netManager != null
                && peer.player.playerNetServerHandler.netManager.isChannelOpen();
            if (!peer.online) trusted.retire(peer);
            peer.admin = HologramLibraryFacade.isAdmin(peer.player);
            Upload upload = peer.upload;
            if (upload != null && upload.privateUpload
                && !(upload.request.getBoolean("contentOnly")
                    ? HologramLibraryService.contentCurrent(peer.player, upload.request)
                    : HologramLibraryService.sessionCurrent(peer.player, upload.request)))
                upload.valid = false;
            if (upload != null && upload.previewUpload
                && !HologramLibraryService.saveCurrent(peer.player, upload.request)) upload.valid = false;
            if (peer.preparation != null
                && (!peer.online || !HologramLibraryService.draftCurrent(peer.player, peer.preparationRequest))) {
                peer.preparation.valid = false;
                tasks.remove(peer.preparation);
                peer.preparation = null;
            }
            Download download = peer.download;
            if (download != null && download.boundRequest != null
                && !HologramLibraryService.draftCurrent(peer.player, download.boundRequest)) download.valid = false;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
        while (!materialPreviews.isEmpty() && System.nanoTime() < deadline) {
            Entry entry = materialPreviews.removeFirst();
            if (published.get(entry.blueprint.id) != entry) {
                entry.materialBuilder = null;
                continue;
            }
            try {
                if (entry.materialBuilder.step()) {
                    entry.preparedMaterials = entry.materialBuilder.result;
                    entry.materialBuilder = null;
                } else materialPreviews.addLast(entry);
            } catch (IOException | RuntimeException unavailable) {
                NBTTagCompound prepared = new NBTTagCompound();
                NBTTagCompound missing = new NBTTagCompound();
                missing.setBoolean("complete", false);
                missing.setString("detail", "Public material summary is unavailable");
                prepared.setTag("localMaterials", missing);
                entry.preparedMaterials = prepared;
                entry.materialBuilder = null;
            }
        }
    }

    HologramPresets.Preset get(String id) throws IOException {
        checkAvailable();
        Entry entry = published.get(id);
        if (entry == null) return null;
        return HologramPresets.copy(entry.blueprint);
    }

    HologramLibraryConfig.View config() {
        return config == null ? null : config.view();
    }

    boolean available() {
        return ready && !closed;
    }

    String unavailableMessage() {
        String failure = loadFailure;
        if (failure != null) return "Server blueprint library unavailable; operator must repair storage: "
            + failure.substring(0, Math.min(160, failure.length()));
        return closed ? "Server blueprint library has stopped" : "Server blueprint library is still loading; retry";
    }

    NBTTagCompound statusTag(boolean admin) {
        HologramLibraryConfig.View view = config();
        if (view == null) view = new HologramLibraryConfig.View(
            HologramLibraryConfig.DEFAULT_UPLOAD_KIB_PER_SECOND,
            HologramLibraryConfig.DEFAULT_DOWNLOAD_KIB_PER_SECOND,
            10,
            0);
        NBTTagCompound tag = view.tag(admin && available());
        tag.setBoolean("readOnly", !available() || !admin);
        tag.setString("libraryError", available() ? "" : unavailableMessage());
        return tag;
    }

    private void checkAvailable() throws IOException {
        if (!available()) throw new IOException(unavailableMessage());
    }

    boolean consumeDownload(UUID owner, int bytes) {
        HologramLibraryConfig.View view = config();
        return view != null && downloadBudget.take(owner, bytes, view.downloadKiB * 1024, System.nanoTime());
    }

    boolean downloadWaiting(UUID owner) {
        return downloadBudget.waiting(owner);
    }

    void cancelDownload(UUID owner) {
        downloadBudget.cancel(owner);
    }

    private void pumpSafely() {
        try {
            if (!closed && config != null) pump();
        } catch (RuntimeException failure) {
            GTMiaoBlueprint.LOG.warn("Server library worker failure", failure);
            loadFailure = failure.getMessage() == null ? failure.getClass()
                .getSimpleName() : failure.getMessage();
            closed = true;
            List<Peer> failed;
            synchronized (this) {
                failed = new ArrayList<>(peers.values());
            }
            for (Peer peer : failed) {
                long clientTaskToken = taskToken(peer);
                abort(peer);
                clearDownload(peer);
                if (peer.online) {
                    HologramLibraryFacade.error(peer.player, unavailableMessage(), clientTaskToken);
                    HologramNetwork.sendLibraryReply(peer.player, statusTag(peer.admin));
                }
            }
        }
    }

    private void pump() {
        for (Entry entry : published.values()) {
            NBTTagCompound prepared = entry.preparedMaterials;
            if (prepared == null) continue;
            try {
                retainPreview(entry.blueprint.id + ":materials", HologramSnapshotCodec.compress(prepared));
                entry.preparedMaterials = null;
            } catch (IOException | RuntimeException failure) {
                entry.preparedMaterials = null;
                GTMiaoBlueprint.LOG.warn("Public material summary encoding failed", failure);
            }
        }
        Runnable task = tasks.poll();
        if (task != null) task.run();
        List<Peer> round;
        synchronized (this) {
            round = new ArrayList<>(peers.values());
        }
        if (round.isEmpty()) return;
        long now = System.nanoTime();
        int start = cursor++ % round.size();
        for (int n = 0; n < round.size(); n++) {
            Peer peer = round.get((start + n) % round.size());
            if (!peer.online || peer.upload != null
                && (!peer.upload.valid || now - peer.upload.lastActivity > HologramLibraryProtocol.TIMEOUT_NANOS)) {
                long clientTaskToken = taskToken(peer);
                abort(peer);
                clearDownload(peer);
                if (peer.online) error(peer, "Upload timed out; retry", clientTaskToken);
                else synchronized (this) {
                    peers.remove(peer.owner, peer);
                }
                continue;
            }
            NBTTagCompound control;
            synchronized (this) {
                control = peer.requests.pollControl();
            }
            if (control != null) process(peer, control);
        }
        // Every peer gets one opportunity per round; no UUID can drain the shared bucket first.
        for (int pass = 0; pass < 4; pass++) {
            boolean progressed = false;
            for (int n = 0; n < round.size(); n++) {
                Peer peer = round.get((start + n) % round.size());
                NBTTagCompound request;
                synchronized (this) {
                    request = peer.requests.peekChunk();
                }
                if (request == null || !peer.online) continue;
                int length = request.getByteArray("data").length;
                if (length > 0 && length <= HologramLibraryProtocol.CHUNK_BYTES
                    && !uploadBudget.take(peer.owner, length, config.view().uploadKiB * 1024, System.nanoTime()))
                    continue;
                synchronized (this) {
                    request = peer.requests.pollChunk();
                }
                if (request != null) {
                    process(peer, request);
                    progressed = true;
                }
            }
            if (!progressed) break;
        }
        for (Peer peer : round) {
            flushUploadAck(peer);
            sendUpgradeReport(peer);
            try {
                sendDownload(peer, now); // Downloads retain their existing once-per-50ms pump semantics.
            } catch (RuntimeException failure) {
                long clientTaskToken = taskToken(peer);
                clearDownload(peer);
                error(peer, failure.getMessage(), clientTaskToken);
            }
        }
    }

    private void process(Peer peer, NBTTagCompound request) {
        try {
            handle(peer, request);
        } catch (IOException | RuntimeException invalid) {
            String op = request.getString("op");
            if (HologramLibraryProtocol.uploadChunk(request) || "uploadBegin".equals(op)
                || "privateBegin".equals(op)
                || "previewBegin".equals(op)) abort(peer);
            error(peer, invalid.getMessage(), request.getLong("clientTaskToken"));
        }
    }

    private void flushUploadAck(Peer peer) {
        Upload upload = peer.upload;
        if (upload == null || !upload.valid || !upload.ackPending) return;
        upload.ackPending = false;
        NBTTagCompound ack = HologramLibraryProtocol.reply("chunkAck");
        ack.setLong("clientTaskToken", upload.request.getLong("clientTaskToken"));
        ack.setString("transfer", upload.transfer);
        ack.setInteger("index", upload.index - 1);
        ack.setInteger("nextIndex", upload.index);
        send(peer, ack);
    }

    private void handle(Peer peer, NBTTagCompound request) throws IOException {
        String op = request.getString("op");
        if ("list".equals(op)) list(peer, request.getInteger("page"));
        else if ("contentResolve".equals(op)) {
            String hash;
            if (request.getString("id")
                .isEmpty()) {
                hash = request.getString("jsonHash");
                String sourceHash = request.getString("sourceHash");
                if (hash.isEmpty()) hash = sourceHash;
                if (!sourceHash.isEmpty() && !sourceHash.equals(hash))
                    throw new IOException("Blueprint content identities disagree");
                HologramLibraryProtocol.header(request.getString("transfer"), 1, hash);
                Entry publicSource = contentIndex.get(hash);
                boolean publicHit = publicSource != null && published.get(publicSource.blueprint.id) == publicSource;
                boolean hit = trusted.contains(peer.owner, peer, peer.world, runtimeEpoch, hash) || publicHit
                    || integratedContentHit(request, hash);
                if (draftTaskCurrent(peer.player, request))
                    send(peer, hit ? contentReply(request, hash, true, false) : contentUploadReply(request, hash));
                return;
            } else if (HologramLibraryFacade.enabled()) {
                Entry entry = publicEntry(request);
                if (!request.getString("sourceHash")
                    .isEmpty()) requirePublicRevision(entry, request);
                hash = entry.hash;
            } else {
                String id = request.getString("integratedPresetId");
                if (id.isEmpty() || !id.equals(request.getString("id")))
                    throw new IOException("Invalid integrated blueprint identity");
                hash = HologramPresets.contentHash(id);
                if (hash == null) throw new IOException("Saved blueprint no longer exists");
                String requestedHash = request.getString("sourceHash");
                if (!requestedHash.isEmpty() && !HologramPresets.matchesContent(id, requestedHash)) {
                    HologramLibraryProtocol.header(request.getString("transfer"), 1, requestedHash);
                    if (draftTaskCurrent(peer.player, request)) send(peer, contentUploadReply(request, requestedHash));
                    return;
                }
                if (!requestedHash.isEmpty()) hash = requestedHash;
            }
            HologramLibraryProtocol.header(request.getString("transfer"), 1, hash);
            if (draftTaskCurrent(peer.player, request)) send(peer, contentReply(request, hash, true, false));
        } else if ("preview".equals(op)) {
            Entry entry = publicEntry(request);
            NBTTagCompound identity = (NBTTagCompound) request.copy();
            identity.setString("sourceHash", entry.hash);
            identity.setLong("sourceRevision", entry.revision);
            sendThumbnail(peer, entry, identity);
            byte[] preview = previews.get(entry.blueprint.id);
            if (preview == null) {
                preview = HologramSnapshotCodec.compress(HologramPublicPreview.project(entry.blueprint));
                retainPreview(entry.blueprint.id, preview);
            }
            queueDownload(peer, preview, entry.blueprint.name, identity, entry.blueprint.id);
        } else if ("previewMaterials".equals(op)) {
            Entry entry = publicEntry(request);
            requirePublicRevision(entry, request);
            synchronized (this) {
                if (entry.materialBuilder == null && entry.preparedMaterials == null
                    && !previews.containsKey(entry.blueprint.id + ":materials")) {
                    if (materialPreviews.size() >= 4)
                        throw new IOException("Public material preparation queue is busy");
                    entry.materialBuilder = new HologramPublicPreview.Builder(entry.blueprint);
                    materialPreviews.addLast(entry);
                }
                peer.materialPreview = entry;
                peer.materialPreviewRequest = (NBTTagCompound) request.copy();
                peer.materialPreviewRequest.setString("sourceHash", entry.hash);
                peer.materialPreviewRequest.setLong("sourceRevision", entry.revision);
            }
        } else if ("publicPrepare".equals(op)) {
            HologramPresets.Preset source;
            if (HologramLibraryFacade.enabled()) {
                Entry entry = publicEntry(request);
                requirePublicRevision(entry, request);
                source = HologramPresets.copy(entry.blueprint);
            } else {
                String id = request.getString("integratedPresetId");
                if (id.isEmpty() || !id.equals(request.getString("id")))
                    throw new IOException("Invalid integrated blueprint identity");
                source = HologramPresets.admitted(id, request.getString("sourceHash"));
                if (source == null) throw new IOException("Saved blueprint no longer exists");
            }
            NBTTagCompound activation = (NBTTagCompound) request.copy();
            activation.setString("localId", "local_" + UUID.randomUUID());
            HologramLibraryFacade.importPrivate(peer.player, activation, source, "publicPrepared");
        } else if ("upgradeLibrary".equals(op)) upgradeLibrary(peer, request);
        else if ("configGet".equals(op)) send(peer, statusTag(peer.admin));
        else if ("configSet".equals(op)) {
            config.update(
                peer.admin,
                request.getLong("expectedRevision"),
                request.getInteger("uploadKiB"),
                request.getInteger("downloadKiB"),
                request.getInteger("maxUploads"));
            broadcastConfig();
        } else if ("delete".equals(op)) {
            String id = request.getString("id");
            Entry entry = owned(peer.owner, id);
            HologramBlueprintFiles.delete(directory, id);
            Map<String, Entry> next = new LinkedHashMap<>(published);
            next.remove(id);
            publishIndex(next);
            storedBytes -= entry.bytes;
            publicDtoBytes -= entry.dtoBytes;
            catalogRevision++;
            NBTTagCompound deleted = HologramLibraryProtocol.reply("deleted");
            deleted.setString("id", id);
            send(peer, deleted);
            list(peer, 0);
        } else if ("uploadBegin".equals(op) || "privateBegin".equals(op) || "previewBegin".equals(op))
            begin(peer, request);
        else if ("uploadChunk".equals(op) || "privateChunk".equals(op) || "previewChunk".equals(op))
            append(peer, request);
        else if ("download".equals(op)) {
            Entry entry = published.get(request.getString("id"));
            if (entry == null) throw new IOException("Blueprint no longer exists");
            HologramBlueprintArtifact.Artifact artifact = storedArtifact(entry);
            queueDownload(
                peer,
                HologramBlueprintArtifact.encode(encodeShare(entry.blueprint), artifact.png),
                entry.blueprint.name,
                request,
                entry.blueprint.id);
        } else if ("exportDraft".equals(op)) {
            HologramLibraryFacade.exportDraft(peer.player, request);
        } else if ("cancelTransfer".equals(op) || "cancel".equals(op)) {
            String transfer = request.getString("transfer");
            long task = request.getLong("clientTaskToken");
            if (peer.upload != null && (task == 0 || task == peer.upload.request.getLong("clientTaskToken"))
                && (transfer.isEmpty() || transfer.equals(peer.upload.transfer))) abort(peer);
            if (peer.download != null && (task == 0 || task == peer.download.clientTaskToken)
                && (transfer.isEmpty() || transfer.equals(peer.download.transfer))) clearDownload(peer);
            synchronized (this) {
                if (peer.pendingDraft != null
                    && (task == 0 || task == peer.pendingDraft.request.getLong("clientTaskToken")))
                    peer.pendingDraft = null;
            }
        } else throw new IOException("Unknown library operation");
    }

    private static void requirePublicRevision(Entry entry, NBTTagCompound request) throws IOException {
        if (!entry.hash.equals(request.getString("sourceHash")) || !String.valueOf(entry.revision)
            .equals(
                request.hasKey("sourceRevision", 8) ? request.getString("sourceRevision")
                    : String.valueOf(request.getLong("sourceRevision"))))
            throw new IOException("Server blueprint changed; reopen its preview");
    }

    private void retainPreview(String key, byte[] bytes) {
        if (bytes.length > MAX_STORED_BYTES) return;
        byte[] previous = previews.remove(key);
        if (previous != null) previewBytes -= previous.length;
        while (!previews.isEmpty() && (previews.size() >= 32 || previewBytes + bytes.length > MAX_STORED_BYTES)) {
            java.util.Iterator<byte[]> iterator = previews.values()
                .iterator();
            previewBytes -= iterator.next().length;
            iterator.remove();
        }
        previews.put(key, bytes);
        previewBytes += bytes.length;
    }

    /** Optional pixels travel ahead of the existing DTO; missing thumbnails never delay or fail 3D preparation. */
    private void sendThumbnail(Peer peer, Entry entry, NBTTagCompound request) {
        if (entry.blueprint.cells.size() <= 4096) return;
        long epoch = runtimeEpoch;
        java.util.function.BooleanSupplier current = () -> epoch == runtimeEpoch && !closed
            && peer.online
            && published.get(entry.blueprint.id) == entry
            && draftTaskCurrent(peer.player, request);
        try {
            if (!current.getAsBoolean() || entry.thumbnailUnavailable) return;
            String key = entry.blueprint.id + ":thumbnail";
            byte[] png = previews.get(key);
            if (png == null) {
                Path json = HologramBlueprintFiles.resolve(directory, entry.blueprint.id);
                png = thumbnail(
                    HologramBlueprintSharing.readBytes(
                        HologramBlueprintArtifact.previewPath(json),
                        HologramBlueprintArtifact.MAX_PNG_BYTES),
                    current);
                if (!current.getAsBoolean()) return;
                retainPreview(key, png);
            }
            if (!current.getAsBoolean()) return;
            boolean alreadyWaiting = downloadBudget.waiting(peer.owner);
            if (!downloadBudget.take(peer.owner, png.length, config.view().downloadKiB * 1024, System.nanoTime())) {
                if (!alreadyWaiting) downloadBudget.cancel(peer.owner);
                return;
            }
            NBTTagCompound reply = HologramLibraryProtocol.reply("previewThumbnail");
            reply.setLong("clientTaskToken", request.getLong("clientTaskToken"));
            reply.setString("publicId", entry.blueprint.id);
            reply.setString("sourceHash", entry.hash);
            reply.setLong("sourceRevision", entry.revision);
            reply.setByteArray("thumbnailPng", png);
            send(peer, reply);
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            if (current.getAsBoolean()) entry.thumbnailUnavailable = true;
        }
    }

    /** PNG pixels only: no JSON read, artifact pairing, source hashing or retained image metadata. */
    static byte[] thumbnail(byte[] png, java.util.function.BooleanSupplier current) throws IOException {
        if (png == null || png.length < 33 || png.length > HologramBlueprintArtifact.MAX_PNG_BYTES)
            throw new IOException("Public thumbnail source byte budget");
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(png);
        if (header.getLong() != 0x89504e470d0a1a0aL || header.getInt() != 13
            || header.getInt() != 0x49484452
            || header.getInt() != HologramBlueprintArtifact.WIDTH
            || header.getInt() != HologramBlueprintArtifact.HEIGHT)
            throw new IOException("Public thumbnail source dimensions");
        if (!current.getAsBoolean()) throw new java.io.InterruptedIOException("Public thumbnail retired");
        java.awt.image.BufferedImage source;
        javax.imageio.ImageReader reader = javax.imageio.ImageIO.getImageReadersByFormatName("png")
            .next();
        try (
            javax.imageio.stream.MemoryCacheImageInputStream input = new javax.imageio.stream.MemoryCacheImageInputStream(
                new java.io.ByteArrayInputStream(png))) {
            reader.setInput(input, true, true);
            source = reader.read(0);
        } finally {
            reader.dispose();
        }
        if (source == null || source.getWidth() != HologramBlueprintArtifact.WIDTH
            || source.getHeight() != HologramBlueprintArtifact.HEIGHT)
            throw new IOException("Public thumbnail source dimensions");
        if (!current.getAsBoolean()) throw new java.io.InterruptedIOException("Public thumbnail retired");
        for (int size : new int[] { 160, 64 }) {
            java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(
                size,
                size,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = small.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, size, size, null);
            } finally {
                graphics.dispose();
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(THUMBNAIL_BYTES);
            if (!javax.imageio.ImageIO.write(small, "png", bytes))
                throw new IOException("Public thumbnail PNG unavailable");
            if (!current.getAsBoolean()) throw new java.io.InterruptedIOException("Public thumbnail retired");
            if (bytes.size() <= THUMBNAIL_BYTES) return bytes.toByteArray();
        }
        throw new IOException("Public thumbnail byte budget");
    }

    private Entry publicEntry(NBTTagCompound request) throws IOException {
        Entry entry = published.get(HologramLibraryService.publicId(request.getString("id")));
        if (entry == null) throw new IOException("Blueprint no longer exists");
        return entry;
    }

    static NBTTagCompound contentReply(NBTTagCompound request, String hash, boolean cached, boolean missing) {
        NBTTagCompound result = HologramLibraryProtocol.reply(missing ? "contentMissing" : "contentReady");
        result.setString("transfer", request.getString("transfer"));
        result.setLong("clientTaskToken", request.getLong("clientTaskToken"));
        result.setString("contentHash", hash);
        result.setBoolean("cached", cached);
        return result;
    }

    /** A probe miss has no upload reservation; the client may now prepare the actual bounded artifact. */
    static NBTTagCompound contentUploadReply(NBTTagCompound request, String hash) {
        NBTTagCompound result = contentReply(request, hash, false, false);
        result.setString("op", "needUpload");
        return result;
    }

    /** A client-supplied ID only selects one saved source; the actual digest must still match that source. */
    static String integratedContentId(NBTTagCompound request) {
        if (HologramLibraryFacade.enabled()) return "";
        String id = request.getString("integratedPresetId");
        if (id.isEmpty() && request.getString("localId")
            .startsWith("local_"))
            id = request.getString("localId")
                .substring(6);
        return id.matches("[a-zA-Z0-9_-]{1,96}") ? id : "";
    }

    private static boolean integratedContentHit(NBTTagCompound request, String hash) throws IOException {
        String id = integratedContentId(request);
        return !id.isEmpty() && HologramPresets.matchesContent(id, hash);
    }

    private void begin(Peer peer, NBTTagCompound request) throws IOException {
        if (request.getInteger("artifactVersion") != HologramBlueprintArtifact.VERSION)
            throw new IOException("Unsupported blueprint artifact version");
        if (peer.upload != null) throw new IOException("An upload is already active");
        boolean contentOnly = request.getBoolean("contentOnly");
        if ("privateBegin".equals(request.getString("op"))
            && (request.getBoolean("hashProbe") || contentOnly || request.getBoolean("admittedOnly"))) {
            String jsonHash = request.getString("jsonHash");
            HologramLibraryProtocol.header(request.getString("transfer"), 1, jsonHash);
            Entry publicSource = contentIndex.get(jsonHash);
            boolean publicHit = publicSource != null && published.get(publicSource.blueprint.id) == publicSource;
            if (contentOnly) {
                if (trusted.contains(peer.owner, peer, peer.world, runtimeEpoch, jsonHash) || publicHit
                    || integratedContentHit(request, jsonHash)) {
                    if (draftTaskCurrent(peer.player, request))
                        send(peer, contentReply(request, jsonHash, true, false));
                    return;
                }
            } else {
                HologramPresets.Preset cached = trusted.get(peer.owner, peer, peer.world, runtimeEpoch, jsonHash);
                // Public source access is separate from every other player's private cache.
                if (cached == null && publicHit) cached = HologramPresets.copy(publicSource.blueprint);
                if (cached == null) {
                    String integratedId = integratedContentId(request);
                    if (!integratedId.isEmpty()) cached = HologramPresets.matching(integratedId, jsonHash);
                }
                if (cached != null) {
                    HologramLibraryFacade.importPrivate(peer.player, request, cached, "privateCached");
                    return;
                }
                if (request.getBoolean("admittedOnly")) {
                    if (draftTaskCurrent(peer.player, request))
                        send(peer, contentReply(request, jsonHash, false, true));
                    return;
                }
            }
        }
        HologramLibraryProtocol
            .header(request.getString("transfer"), request.getInteger("total"), request.getString("hash"));
        boolean privateUpload = "privateBegin".equals(request.getString("op"));
        boolean previewUpload = "previewBegin".equals(request.getString("op"));
        String replace = request.getString("replaceId");
        if (!privateUpload && !previewUpload) {
            if (!replace.isEmpty()) owned(peer.owner, replace);
            else checkQuota(peer.owner, countOwned(peer.owner), config.view().maxUploads);
        }
        if (activeUploads >= HologramLibraryProtocol.ACTIVE_UPLOADS
            || request.getInteger("total") > MAX_RESERVED_BYTES - reservedBytes)
            throw new IOException("Server upload queue is busy; retry later");
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "incoming-", ".tmp");
        Upload upload;
        try {
            upload = new Upload(request, temp, Files.newOutputStream(temp));
        } catch (IOException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
        synchronized (this) {
            peer.upload = upload;
        }
        reservedBytes += upload.total;
        activeUploads++;
        NBTTagCompound ready = HologramLibraryProtocol.reply(previewUpload ? "previewReady" : "uploadReady");
        ready.setLong("clientTaskToken", request.getLong("clientTaskToken"));
        ready.setString("transfer", upload.transfer);
        ready.setInteger("nextIndex", 0);
        ready.setInteger("uploadWindow", HologramLibraryProtocol.MAX_UPLOAD_WINDOW);
        send(peer, ready);
    }

    private void append(Peer peer, NBTTagCompound request) throws IOException {
        Upload upload = peer.upload;
        if (upload == null || !admitted(peer, request)) throw new IOException("Unknown upload");
        boolean privateChunk = "privateChunk".equals(request.getString("op"));
        boolean previewChunk = "previewChunk".equals(request.getString("op"));
        if (privateChunk != upload.privateUpload || previewChunk != upload.previewUpload)
            throw new IOException("Transfer purpose mismatch");
        byte[] bytes = request.getByteArray("data");
        HologramLibraryProtocol
            .chunk(upload.total, upload.offset, upload.index, request.getInteger("index"), bytes.length);
        upload.output.write(bytes);
        upload.digest.update(bytes);
        upload.offset += bytes.length;
        upload.index++;
        upload.lastActivity = System.nanoTime();
        if (upload.offset == upload.total) {
            upload.output.close();
            if (!upload.hash.equals(HologramLibraryProtocol.hex(upload.digest.digest())))
                throw new IOException("Upload hash mismatch");
            HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact
                .decode(HologramBlueprintSharing.readBytes(upload.temp, HologramBlueprintArtifact.MAX_BYTES));
            if (upload.previewUpload) {
                HologramLibraryFacade.verifySavedPreview(peer.player, upload.request, artifact);
            } else {
                HologramPresets.Preset blueprint = canonical(
                    HologramBlueprintSharing.decodeUnchecked(new String(artifact.json, StandardCharsets.UTF_8)));
                if (upload.privateUpload) {
                    String jsonHash = HologramBlueprintContentHash.of(artifact.json);
                    if ((upload.request.getBoolean("hashProbe") || upload.request.getBoolean("contentOnly"))
                        && !jsonHash.equals(upload.request.getString("jsonHash")))
                        throw new IOException("Blueprint JSON digest mismatch");
                    if (draftTaskCurrent(peer.player, upload.request) && upload.valid) {
                        int contentBytes = HologramBlueprintCompactCodec.encode(blueprint, (done, total) -> {}).length;
                        boolean retained = trusted
                            .put(peer.owner, peer, peer.world, runtimeEpoch, jsonHash, blueprint, contentBytes);
                        if (upload.request.getBoolean("contentOnly")) {
                            if (!retained) throw new IOException(
                                "Blueprint exceeds the temporary content cache allowance; preparation was not retained");
                            send(peer, contentReply(upload.request, jsonHash, false, false));
                        } else HologramLibraryFacade.importPrivate(peer.player, upload.request, blueprint);
                    }
                } else publish(peer, upload, blueprint, artifact.png);
            }
            abort(peer);
        } else upload.ackPending = true;
    }

    private void publish(Peer peer, Upload upload, HologramPresets.Preset blueprint, byte[] preview)
        throws IOException {
        Entry previous = upload.replace.isEmpty() ? null : owned(peer.owner, upload.replace);
        if (previous == null) checkQuota(peer.owner, countOwned(peer.owner), config.view().maxUploads);
        if (previous == null && published.size() >= MAX_ENTRIES) throw new IOException("Server library is full");
        blueprint.id = previous == null ? "public_" + UUID.randomUUID() : previous.blueprint.id;
        if (blueprint.authorName == null || blueprint.authorName.isEmpty()) {
            blueprint.authorName = peer.name;
            blueprint.authorUuid = peer.owner.toString();
        }
        if (blueprint.createdAt <= 0) blueprint.createdAt = System.currentTimeMillis();
        Envelope envelope = new Envelope();
        envelope.owner = peer.owner.toString();
        envelope.uploadedBy = peer.name;
        envelope.blueprint = blueprint;
        byte[] bytes = HologramBlueprintCompactCodec.encodeEnvelope(blueprint, envelope.owner, envelope.uploadedBy);
        byte[] boundPreview = HologramBlueprintArtifact.bindPreview(bytes, preview);
        long nextBytes = storedBytes - (previous == null ? 0 : previous.bytes) + bytes.length + boundPreview.length;
        if (nextBytes > MAX_STORED_BYTES) throw new IOException("Server library byte budget is full");
        Entry replacement = new Entry(
            envelope,
            bytes.length + boundPreview.length,
            HologramBlueprintContentHash.of(blueprint),
            catalogRevision + 1,
            bytes);
        long nextDtoBytes = publicDtoBytes - (previous == null ? 0 : previous.dtoBytes) + replacement.dtoBytes;
        if (nextDtoBytes > MAX_PUBLIC_DTO_BYTES)
            throw new IOException("Server library expanded DTO memory budget is full");
        String cleanupWarning = writePublicPair(
            blueprint.id,
            bytes,
            boundPreview,
            () -> peer.online && !closed
                && (upload.request.getLong("clientTaskToken") == 0
                    || upload.request.getLong("clientTaskToken") > peer.retiredThrough));
        Map<String, Entry> next = new LinkedHashMap<>(published);
        next.put(blueprint.id, replacement);
        publishIndex(next);
        storedBytes = nextBytes;
        publicDtoBytes = nextDtoBytes;
        catalogRevision++;
        NBTTagCompound result = HologramLibraryProtocol.reply("uploaded");
        result.setLong("clientTaskToken", upload.request.getLong("clientTaskToken"));
        result.setString("id", blueprint.id);
        result.setString("transfer", upload.transfer);
        result.setString("cleanupWarning", cleanupWarning);
        send(peer, result);
        list(peer, 0);
    }

    private void list(Peer peer, int requestedPage) {
        List<Entry> entries = new ArrayList<>(published.values());
        int pages = Math
            .max(1, (entries.size() + HologramLibraryProtocol.PAGE_SIZE - 1) / HologramLibraryProtocol.PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        NBTTagList labels = new NBTTagList();
        for (int i = page * HologramLibraryProtocol.PAGE_SIZE; i
            < Math.min(entries.size(), (page + 1) * HologramLibraryProtocol.PAGE_SIZE); i++) {
            Entry entry = entries.get(i);
            NBTTagCompound label = new NBTTagCompound();
            label.setString("id", entry.blueprint.id);
            label.setString("name", entry.blueprint.name);
            label.setString(
                "gtmbVersion",
                entry.blueprint.nativeBlueprint == null ? "" : entry.blueprint.nativeBlueprint.gtmbVersion);
            label.setString("sourceHash", entry.hash);
            label.setLong("sourceRevision", entry.revision);
            label.setString("authorName", entry.blueprint.authorName == null ? "" : entry.blueprint.authorName);
            label.setString("owner", entry.owner.toString());
            label.setString("uploadedBy", entry.uploadedBy);
            label.setBoolean("mine", peer.owner.equals(entry.owner));
            label.setInteger("cells", entry.blueprint.cells.size());
            label.setInteger("structures", entry.blueprint.structures.size());
            label.setInteger("bytes", entry.bytes);
            labels.appendTag(label);
        }
        NBTTagCompound result = HologramLibraryProtocol.reply("list");
        result.setBoolean("enabled", true);
        result.setBoolean("admin", peer.admin);
        result.setTag("entries", labels);
        result.setInteger("page", page);
        result.setInteger("pages", pages);
        result.setInteger("total", entries.size());
        result.setLong("revision", catalogRevision);
        send(peer, result);
    }

    private Entry owned(UUID owner, String id) throws IOException {
        Entry entry = published.get(id);
        if (entry == null) throw new IOException("Blueprint no longer exists");
        checkOwner(owner, entry.owner);
        return entry;
    }

    static void checkOwner(UUID actor, UUID owner) throws IOException {
        if (actor == null || !actor.equals(owner))
            throw new IOException("Only the uploader can delete or replace this blueprint");
    }

    static void checkQuota(UUID actor, int count, int max) throws IOException {
        if (actor == null || count >= max) throw new IOException("Your server blueprint upload quota is full");
    }

    private int countOwned(UUID owner) {
        int count = 0;
        for (Entry entry : published.values()) if (owner.equals(entry.owner)) count++;
        return count;
    }

    private void load() throws IOException {
        Files.createDirectories(directory);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "incoming-*.tmp")) {
            for (Path path : files) Files.deleteIfExists(path);
        }
        Map<String, Entry> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Path> member : HologramBlueprintFiles.managedPublic(directory)
            .entrySet()) {
            String id = HologramLibraryService.publicId(member.getKey());
            if (loaded.size() >= MAX_ENTRIES) throw new IOException("Server blueprint index entry budget");
            HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(member.getValue());
            byte[] bytes = artifact.json;
            if (bytes.length + artifact.png.length > MAX_STORED_BYTES - storedBytes)
                throw new IOException("Server blueprint index byte budget");
            com.google.gson.JsonObject root = HologramBlueprintCompactCodec.parse(bytes);
            HologramBlueprintVersions.validateJson(root.get("blueprint"));
            Envelope envelope = GSON.fromJson(root, Envelope.class);
            if (envelope == null || envelope.schema != 1
                || envelope.blueprint == null
                || !id.equals(envelope.blueprint.id)
                || envelope.uploadedBy == null
                || envelope.uploadedBy.length() > 64) throw new IOException("Invalid server blueprint envelope");
            UUID.fromString(envelope.owner);
            envelope.blueprint = canonical(envelope.blueprint);
            Entry entry = new Entry(
                envelope,
                bytes.length + artifact.png.length,
                HologramBlueprintContentHash.of(envelope.blueprint),
                1,
                bytes);
            if (entry.dtoBytes > MAX_PUBLIC_DTO_BYTES - publicDtoBytes)
                throw new IOException("Server library expanded DTO memory budget exceeded");
            loaded.put(id, entry);
            publicDtoBytes += entry.dtoBytes;
            storedBytes += bytes.length + artifact.png.length;
        }
        publishIndex(loaded);
    }

    private HologramBlueprintArtifact.Artifact storedArtifact(Entry entry) throws IOException {
        HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact
            .readPair(HologramBlueprintFiles.resolve(directory, entry.blueprint.id));
        if (!entry.storedJsonHash.equals(artifact.jsonSha256))
            throw new IOException("Server blueprint original changed after catalog loading; reload before using it");
        return artifact;
    }

    /** The pair and logical filename mapping share one receipt. Cleanup warnings follow an accepted commit. */
    private String writePublicPair(String id, byte[] json, byte[] png, java.util.function.BooleanSupplier current)
        throws IOException {
        Path target = HologramBlueprintFiles.target(directory, id, json);
        HologramBlueprintArtifact.PairWrite receipt = HologramBlueprintArtifact
            .writePairPending(target, json, png, current);
        try {
            HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.readPair(target);
            if (!receipt.jsonSha256.equals(actual.jsonSha256) || !receipt.pngSha256.equals(actual.pngSha256))
                throw new IOException("Published blueprint pair failed readback verification");
            if (!current.getAsBoolean()) throw new IOException("Blueprint storage operation was retired");
            HologramBlueprintFiles.track(directory, id, receipt);
        } catch (IOException | RuntimeException failure) {
            try {
                receipt.rollback();
            } catch (IOException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        // commit accepts the new pair/index even if old recovery files cannot be cleaned up.
        receipt.commit();
        return receipt.cleanupWarning();
    }

    private void upgradeLibrary(Peer peer, NBTTagCompound request) throws IOException {
        requireUpgradeAdmin(peer.admin);
        long token = request.getLong("clientTaskToken");
        if (token <= 0) throw new IOException("Invalid library upgrade task");
        if (peer.upgradeReport != null && peer.upgradeReport.token > peer.retiredThrough)
            throw new IOException("A library upgrade report is already active");
        List<NBTTagCompound> report = new ArrayList<>();
        int upgraded = 0, current = 0, skipped = 0;
        long epoch = runtimeEpoch;
        for (Entry previous : new ArrayList<>(published.values())) {
            if (!peer.online || token <= peer.retiredThrough || closed || epoch != runtimeEpoch) break;
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", previous.blueprint.id);
            try {
                requireUpgradeAdmin(peer.admin);
                if (!peer.online || closed || epoch != runtimeEpoch)
                    throw new IOException("Library connection or runtime changed");
                HologramBlueprintArtifact.Artifact original = storedArtifact(previous);
                byte[] compact = HologramBlueprintUpgrade.migrate(
                    original.json,
                    HologramBlueprintArtifact.MAX_JSON_BYTES,
                    () -> peer.online && !closed && epoch == runtimeEpoch);
                Path oldPath = HologramBlueprintFiles.resolve(directory, previous.blueprint.id);
                Path target = HologramBlueprintFiles.target(directory, previous.blueprint.id, compact);
                byte[] bound = HologramBlueprintArtifact.bindPreview(compact, original.png);
                if (oldPath.equals(target) && java.util.Arrays.equals(original.json, compact)
                    && java.util.Arrays.equals(original.png, bound)) {
                    row.setString("state", "current");
                    current++;
                } else {
                    Envelope envelope = new Envelope();
                    envelope.owner = previous.owner.toString();
                    envelope.uploadedBy = previous.uploadedBy;
                    // Reuse already admitted immutable cells; the IO worker must not re-resolve registries.
                    HologramPresets.Preset migrated = HologramSnapshotMetadataSource.copyMetadata(previous.blueprint);
                    migrated.cells.addAll(previous.blueprint.cells);
                    migrated.palette.addAll(previous.blueprint.palette);
                    migrated.nativeBlueprint = new com.google.gson.Gson().fromJson(
                        HologramBlueprintCompactCodec.parse(compact)
                            .getAsJsonObject("blueprint")
                            .get("nativeBlueprint"),
                        HologramBlueprintVersions.Metadata.class);
                    migrated.modVersions.put("gtmb", com.miaokatze.gtmb.Tags.RELEASE_VERSION);
                    envelope.blueprint = migrated;
                    Entry replacement = new Entry(
                        envelope,
                        compact.length + bound.length,
                        HologramBlueprintContentHash.of(compact),
                        previous.revision + 1,
                        compact);
                    long nextBytes = storedBytes - previous.bytes + replacement.bytes;
                    long nextDtoBytes = publicDtoBytes - previous.dtoBytes + replacement.dtoBytes;
                    if (nextBytes > MAX_STORED_BYTES) throw new IOException("Server library byte budget is full");
                    if (nextDtoBytes > MAX_PUBLIC_DTO_BYTES)
                        throw new IOException("Server library expanded DTO memory budget is full");
                    String warning = writePublicPair(
                        previous.blueprint.id,
                        compact,
                        bound,
                        () -> peer.online && peer.admin
                            && token > peer.retiredThrough
                            && !closed
                            && epoch == runtimeEpoch);
                    Map<String, Entry> next = new LinkedHashMap<>(published);
                    next.put(previous.blueprint.id, replacement);
                    publishIndex(next);
                    storedBytes = nextBytes;
                    publicDtoBytes = nextDtoBytes;
                    catalogRevision++;
                    row.setString("state", "upgraded");
                    row.setString("reason", warning.substring(0, Math.min(512, warning.length())));
                    upgraded++;
                }
            } catch (IOException | RuntimeException failure) {
                row.setString("state", "skipped");
                String reason = failure.getMessage() == null ? failure.getClass()
                    .getSimpleName() : failure.getMessage();
                row.setString("reason", reason.substring(0, Math.min(512, reason.length())));
                skipped++;
            }
            report.add(row);
        }
        peer.upgradeReport = new UpgradeReport(token, report, upgraded, current, skipped);
    }

    private void sendUpgradeReport(Peer peer) {
        UpgradeReport report = peer.upgradeReport;
        if (report == null) return;
        if (!peer.online || report.token <= peer.retiredThrough) {
            peer.upgradeReport = null;
            return;
        }
        NBTTagCompound reply = report.next();
        send(peer, reply);
        if (reply.getBoolean("complete")) {
            peer.upgradeReport = null;
            list(peer, 0);
        }
    }

    static final class UpgradeReport {

        final long token;
        final List<NBTTagCompound> entries;
        final int upgraded, current, skipped, pages;
        int page;

        UpgradeReport(long token, List<NBTTagCompound> entries, int upgraded, int current, int skipped) {
            this.token = token;
            this.entries = entries;
            this.upgraded = upgraded;
            this.current = current;
            this.skipped = skipped;
            pages = Math
                .max(1, (entries.size() + HologramLibraryProtocol.PAGE_SIZE - 1) / HologramLibraryProtocol.PAGE_SIZE);
        }

        NBTTagCompound next() {
            if (page >= pages) throw new IllegalStateException("Library upgrade report already completed");
            NBTTagCompound result = HologramLibraryProtocol.reply("upgradeLibraryResult");
            result.setLong("clientTaskToken", token);
            result.setInteger("upgraded", upgraded);
            result.setInteger("current", current);
            result.setInteger("skipped", skipped);
            result.setInteger("page", page);
            result.setInteger("pages", pages);
            result.setBoolean("complete", page == pages - 1);
            NBTTagList rows = new NBTTagList();
            for (int index = page * HologramLibraryProtocol.PAGE_SIZE; index
                < Math.min(entries.size(), (page + 1) * HologramLibraryProtocol.PAGE_SIZE); index++)
                rows.appendTag(entries.get(index));
            result.setTag("entries", rows);
            result.setString(
                "message",
                "Server blueprint upgrade: " + upgraded
                    + " upgraded, "
                    + current
                    + " current, "
                    + skipped
                    + " skipped");
            page++;
            return result;
        }
    }

    static void requireUpgradeAdmin(boolean admin) throws IOException {
        if (!admin) throw new IOException("Only a server library administrator can upgrade public blueprint storage");
    }

    private void publishIndex(Map<String, Entry> entries) {
        Map<String, Entry> byContent = new LinkedHashMap<>();
        for (Entry entry : entries.values()) byContent.put(entry.hash, entry);
        published = Collections.unmodifiableMap(entries);
        contentIndex = Collections.unmodifiableMap(byContent);
        previews.clear();
        previewBytes = 0;
    }

    static HologramPresets.Preset canonical(HologramPresets.Preset source) throws IOException {
        HologramPresets.Preset copy = HologramPresets.copy(source);
        HologramPresets.validate(copy);
        for (List<HologramPresets.Cell> cells : java.util.Arrays.asList(copy.cells, copy.palette)) {
            for (HologramPresets.Cell cell : cells) {
                rejectDropPayload(HologramBlueprintNbt.decode(cell.tileNbt));
                rejectDropPayload(HologramBlueprintNbt.decode(cell.itemNbt));
            }
        }
        // Portable library data has no live-world context, including while canonicalizing on the I/O worker.
        HologramAePolicy.Draft draft = HologramAePolicy.sanitizeOwned(
            copy,
            Collections.emptyMap(),
            Collections.emptyMap(),
            HologramAePolicy.PreparationProgress.NONE);
        if (draft.blocking) throw new IOException("Blueprint contains unresolved AE payloads; repair before upload");
        // Sanitization only removes or normalizes admitted payloads; full validation ran once above.
        return draft.preset;
    }

    private static Field listField() {
        for (Field field : NBTTagList.class.getDeclaredFields()) if (List.class.isAssignableFrom(field.getType())) {
            field.setAccessible(true);
            return field;
        }
        throw new IllegalStateException("NBT list field unavailable");
    }

    @SuppressWarnings("unchecked")
    static void rejectDropPayload(NBTBase tag) throws IOException {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            for (Object raw : compound.func_150296_c()) {
                String key = (String) raw;
                if (key.toLowerCase(Locale.ROOT)
                    .equals("dropitem")) throw new IOException("Uploaded dropItem payload is forbidden");
                rejectDropPayload(compound.getTag(key));
            }
        } else if (tag instanceof NBTTagList) {
            try {
                for (NBTBase value : (List<NBTBase>) LIST_FIELD.get(tag)) rejectDropPayload(value);
            } catch (IllegalAccessException failure) {
                throw new IOException(failure);
            }
        }
    }

    static byte[] encodeShare(HologramPresets.Preset blueprint) throws IOException {
        return HologramDraftEncoding.encode(blueprint, (done, total) -> {});
    }

    synchronized boolean draftTaskCurrent(EntityPlayerMP player, NBTTagCompound request) {
        Peer peer = peers.get(HologramLibraryFacade.actorId(player));
        long token = request.getLong("clientTaskToken");
        return !closed && peer != null
            && peer.player == player
            && peer.online
            && (token == 0 || token > peer.retiredThrough);
    }

    synchronized void draft(EntityPlayerMP player, NBTTagCompound request, byte[] bytes, String name)
        throws IOException {
        checkAvailable();
        Peer peer = peers.get(HologramLibraryFacade.actorId(player));
        if (!draftTaskCurrent(player, request))
            throw new IOException("Server library connection or preparation changed; retry");
        if (bytes == null || bytes.length == 0 || bytes.length > HologramLibraryProtocol.MAX_BYTES)
            throw new IOException("Blueprint download byte budget exceeded");
        if (peer.pendingDraft != null || peer.download != null) throw new IOException("A download is already active");
        peer.pendingDraft = new Draft(bytes, name, (NBTTagCompound) request.copy());
        peer.preparation = null;
        peer.preparationRequest = null;
    }

    synchronized String savedContentCommitted(EntityPlayerMP player, NBTTagCompound proof) {
        Peer peer = peers.get(HologramLibraryFacade.actorId(player));
        if (closed || peer == null || peer.player != player || !peer.online) return "";
        String hash = trusted.commitSaved(peer, proof.getString("saveReceipt"), proof.getString("jsonSha256"));
        if (!hash.isEmpty()) return hash;
        hash = proof.getString("contentHash");
        return trusted.contains(peer.owner, peer, peer.world, runtimeEpoch, hash) ? hash : "";
    }

    synchronized void discardSavedContent(EntityPlayerMP player, NBTTagCompound request) {
        Peer peer = peers.get(HologramLibraryFacade.actorId(player));
        if (peer != null && peer.player == player) trusted.discardPending(peer, request.getString("saveReceipt"));
    }

    void prepareDraft(EntityPlayerMP player, NBTTagCompound request, HologramPresets.Preset preset) throws IOException {
        checkAvailable();
        synchronized (this) {
            UUID owner = HologramLibraryFacade.actorId(player);
            Peer peer = peers.get(owner);
            if (peer != null && peer.player != player) throw new IOException("Blueprint connection changed");
            if (peer == null) {
                if (peers.size() >= MAX_PEERS) throw new IOException("Blueprint peer queue full");
                peers.put(owner, new Peer(player));
            }
        }
        HologramPresets.Preset detached = HologramPresets.copy(preset);
        long epoch = runtimeEpoch;
        NBTTagCompound identity = (NBTTagCompound) request.copy();
        HologramSession session = HologramWorkspace.find(player.getUniqueID());
        if (session == null || !session.id.equals(identity.getString("session"))
            || session.planRevision != identity.getLong("planRevision"))
            throw new IOException("Blueprint preparation source changed");
        identity.setLong("uiSequence", session.uiSequence);
        final Preparation preparation = new Preparation(identity.getLong("clientTaskToken"));
        synchronized (this) {
            Peer peer = peers.get(HologramLibraryFacade.actorId(player));
            if (!draftTaskCurrent(player, identity)) throw new IOException("Blueprint preparation was cancelled");
            if (peer.preparation != null) {
                peer.preparation.valid = false;
                tasks.remove(peer.preparation);
            }
            peer.preparation = preparation;
            peer.preparationRequest = identity;
        }
        preparation.action = () -> {
            long[] lastProgress = { Long.MIN_VALUE };
            long[] advancedAt = { System.nanoTime() };
            try {
                byte[] encoded = HologramDraftEncoding.encode(detached, new HologramDraftEncoding.Progress() {

                    @Override
                    public void checkpoint() throws IOException {
                        if (!preparation.valid || epoch != runtimeEpoch || closed)
                            throw new java.io.InterruptedIOException("Blueprint preparation retired");
                        long now = System.nanoTime();
                        if (now - advancedAt[0] > TimeUnit.SECONDS.toNanos(120))
                            throw new IOException("Blueprint preparation stalled");
                        advancedAt[0] = now;
                    }

                    @Override
                    public void update(long done, long total) throws IOException {
                        checkpoint();
                        long now = System.nanoTime();
                        if (done != 0 && done != total
                            && lastProgress[0] != Long.MIN_VALUE
                            && now - lastProgress[0] < TimeUnit.MILLISECONDS.toNanos(250)) return;
                        lastProgress[0] = now;
                        NBTTagCompound progress = (NBTTagCompound) identity.copy();
                        progress.setString("op", "preparationProgress");
                        progress.setString("phase", "snapshot.prepare");
                        progress.setString("detail", "export_json");
                        progress.setLong("done", done);
                        progress.setLong("total", total);
                        progress.setString("unit", "rows");
                        HologramNetwork.sendLibraryReply(player, progress);
                    }
                });
                if (preparation.valid && epoch == runtimeEpoch && !closed) {
                    if (!identity.getString("saveReceipt")
                        .isEmpty()) {
                        String hash = HologramBlueprintContentHash.of(detached);
                        Peer peer;
                        synchronized (this) {
                            peer = peers.get(HologramLibraryFacade.actorId(player));
                            if (!draftTaskCurrent(player, identity))
                                throw new IOException("Blueprint save preparation was retired");
                        }
                        identity.setString("contentHash", hash);
                        trusted.stage(
                            peer.owner,
                            peer,
                            peer.world,
                            epoch,
                            hash,
                            detached,
                            encoded.length,
                            identity.getString("saveReceipt"),
                            HologramBlueprintArtifact.hash(encoded));
                        if (closed || epoch != runtimeEpoch || !peer.online) trusted.retire(peer);
                    }
                    HologramLibraryFacade.draftPrepared(player, identity, encoded, detached.name);
                }
            } catch (IOException | RuntimeException failure) {
                if (preparation.valid && epoch == runtimeEpoch && !closed)
                    HologramLibraryFacade.draftPreparationFailed(player, identity, failure.getMessage());
            }
        };
        if (!tasks.offer(preparation)) {
            synchronized (this) {
                Peer peer = peers.get(HologramLibraryFacade.actorId(player));
                if (peer != null && peer.preparation == preparation) peer.preparation = null;
            }
            preparation.valid = false;
            throw new IOException("Server library worker is busy; retry");
        }
    }

    boolean enqueue(Runnable task) {
        long epoch = runtimeEpoch;
        return !closed && tasks.offer(() -> { if (epoch == runtimeEpoch && !closed) task.run(); });
    }

    /** Retire capabilities immediately; serialize temporary-file cleanup with the existing writer. */
    synchronized void restartRuntime() {
        runtimeEpoch++;
        HologramPresets.clearContentCache();
        trusted.clear();
        materialPreviews.clear();
        List<Peer> retired = new ArrayList<>(peers.values());
        for (Peer peer : retired) {
            peer.online = false;
            peer.pendingDraft = null;
            if (peer.preparation != null) peer.preparation.valid = false;
            if (peer.upload != null) peer.upload.valid = false;
            if (peer.download != null) peer.download.valid = false;
        }
        peers.clear();
        tasks.clear();
        worker.execute(() -> {
            for (Peer peer : retired) {
                abort(peer);
                clearDownload(peer);
            }
        });
    }

    void changeConfig(boolean admin, long revision, int up, int down, int max) throws IOException {
        checkAvailable();
        config.update(admin, revision, up, down, max);
        broadcastConfig();
    }

    private void queueDownload(Peer peer, byte[] bytes, String name, NBTTagCompound request, String id)
        throws IOException {
        if (peer.download != null) throw new IOException("A download is already active");
        if (activeDownloads >= 4 || bytes.length > MAX_RESERVED_BYTES - downloadBytes)
            throw new IOException("Server download queue is busy; retry later");
        peer.download = new Download(
            HologramLibraryDownload.encode(
                bytes,
                () -> peer.online && !closed
                    && (request.getLong("clientTaskToken") == 0
                        || request.getLong("clientTaskToken") > peer.retiredThrough)),
            request.getLong("clientTaskToken"));
        if ("exportDraft".equals(request.getString("op"))) peer.download.boundRequest = (NBTTagCompound) request.copy();
        downloadBytes += bytes.length;
        activeDownloads++;
        NBTTagCompound begin = HologramLibraryProtocol.reply("downloadBegin");
        begin.setLong("clientTaskToken", peer.download.clientTaskToken);
        begin.setString("transfer", peer.download.transfer);
        begin.setString("hash", peer.download.payload.hash);
        begin.setInteger("total", peer.download.bytes.length);
        begin.setString("encoding", peer.download.payload.encoding);
        begin.setInteger("decodedTotal", bytes.length);
        begin.setString("id", id);
        begin.setString("name", name == null ? "" : name);
        begin.setString("purpose", "exportDraft".equals(request.getString("op")) ? "draft" : "public");
        begin.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
        if ("preview".equals(request.getString("op")) || "previewMaterials".equals(request.getString("op"))) {
            begin.setBoolean("materialsOnly", "previewMaterials".equals(request.getString("op")));
            begin.setBoolean("previewOnly", true);
            begin.setString("publicId", id);
            begin.setString("sourceHash", request.getString("sourceHash"));
            begin.setLong("sourceRevision", request.getLong("sourceRevision"));
        }
        begin.setBoolean("capturePreview", "exportDraft".equals(request.getString("op")));
        begin.setBoolean(
            "localSaveReceipt",
            "exportDraft".equals(request.getString("op")) && !request.getString("saveReceipt")
                .isEmpty());
        begin.setString("localId", request.getString("replaceLocalId"));
        if ("exportDraft".equals(request.getString("op")))
            begin.setString("saveReceipt", request.getString("saveReceipt"));
        begin.setString("session", request.getString("session"));
        begin.setLong("planRevision", request.getLong("planRevision"));
        send(peer, begin);
        peer.download.startedAt = System.nanoTime();
    }

    private void sendDownload(Peer peer, long now) {
        Entry materialEntry = peer.materialPreview;
        if (materialEntry != null && peer.download == null) {
            NBTTagCompound request = peer.materialPreviewRequest;
            if (published.get(materialEntry.blueprint.id) != materialEntry
                || request.getLong("clientTaskToken") <= peer.retiredThrough) {
                peer.materialPreview = null;
                peer.materialPreviewRequest = null;
            } else if (previews.containsKey(materialEntry.blueprint.id + ":materials")) try {
                byte[] bytes = previews.get(materialEntry.blueprint.id + ":materials");
                queueDownload(peer, bytes, materialEntry.blueprint.name, request, materialEntry.blueprint.id);
                peer.materialPreview = null;
                peer.materialPreviewRequest = null;
            } catch (IOException | RuntimeException failure) {
                peer.materialPreview = null;
                peer.materialPreviewRequest = null;
                error(peer, failure.getMessage(), request.getLong("clientTaskToken"));
            }
            else synchronized (this) {
                // Another public preview can evict this summary while its subscriber is waiting.
                // Requeue a bounded preparation instead of leaving that request without a producer.
                if (materialEntry.materialBuilder == null && materialEntry.preparedMaterials == null
                    && materialPreviews.size() < 4) {
                    materialEntry.materialBuilder = new HologramPublicPreview.Builder(materialEntry.blueprint);
                    materialPreviews.addLast(materialEntry);
                }
            }
        }
        Draft draft;
        synchronized (this) {
            draft = peer.pendingDraft;
            peer.pendingDraft = null;
        }
        if (draft != null && draftTaskCurrent(peer.player, draft.request)) {
            try {
                queueDownload(peer, draft.bytes, draft.name, draft.request, "");
            } catch (IOException | RuntimeException failure) {
                error(peer, failure.getMessage(), draft.request.getLong("clientTaskToken"));
            }
        }
        Download download = peer.download;
        if (download == null || !peer.online) return;
        if (!download.valid) {
            clearDownload(peer);
            error(peer, "Blueprint session changed; transfer cancelled", download.clientTaskToken);
            return;
        }
        if (now - download.lastActivity > HologramLibraryProtocol.TIMEOUT_NANOS) {
            clearDownload(peer);
            error(peer, "Download timed out; retry", download.clientTaskToken);
            return;
        }
        int length = Math.min(HologramLibraryProtocol.CHUNK_BYTES, download.bytes.length - download.offset);
        download.budgetPolls++;
        if (!downloadBudget.take(peer.owner, length, config.view().downloadKiB * 1024, now)) return;
        NBTTagCompound chunk = HologramLibraryProtocol.reply("downloadChunk");
        chunk.setLong("clientTaskToken", download.clientTaskToken);
        chunk.setString("transfer", download.transfer);
        chunk.setInteger("index", download.index++);
        chunk.setByteArray(
            "data",
            java.util.Arrays.copyOfRange(download.bytes, download.offset, download.offset + length));
        send(peer, chunk);
        download.lastActivity = now;
        download.offset += length;
        if (download.offset == download.bytes.length) {
            NBTTagCompound done = HologramLibraryProtocol.reply("downloadDone");
            done.setLong("clientTaskToken", download.clientTaskToken);
            done.setString("transfer", download.transfer);
            if (Boolean.getBoolean("gtmb.hologram.libraryDownloadTrace")) {
                done.setLong("transferNanos", System.nanoTime() - download.startedAt);
                done.setInteger("chunks", download.index);
                done.setInteger("wireBytes", download.bytes.length);
                done.setInteger("decodedBytes", download.payload.decodedTotal);
                done.setInteger("budgetPolls", download.budgetPolls);
            }
            send(peer, done);
            clearDownload(peer);
        }
    }

    private void broadcastConfig() {
        List<Peer> copy;
        synchronized (this) {
            copy = new ArrayList<>(peers.values());
        }
        for (Peer peer : copy) if (peer.online) send(peer, statusTag(peer.admin));
    }

    private void send(Peer peer, NBTTagCompound tag) {
        if (peer.online && !closed) HologramNetwork.sendLibraryReply(peer.player, tag);
    }

    private static long taskToken(Peer peer) {
        if (peer.download != null) return peer.download.clientTaskToken;
        return peer.upload == null ? 0L : peer.upload.request.getLong("clientTaskToken");
    }

    private void error(Peer peer, String message, long clientTaskToken) {
        NBTTagCompound tag = HologramLibraryProtocol.reply("error");
        tag.setLong("clientTaskToken", clientTaskToken);
        tag.setString(
            "message",
            message == null ? "Library operation failed" : message.substring(0, Math.min(256, message.length())));
        send(peer, tag);
        if (config != null) send(peer, statusTag(peer.admin));
    }

    private void abort(Peer peer) {
        Upload upload;
        synchronized (this) {
            upload = peer.upload;
            if (upload == null) return;
            upload.valid = false;
            peer.upload = null;
            peer.requests.discard(upload.transfer, upload.request.getLong("clientTaskToken"));
        }
        try {
            upload.output.close();
            Files.deleteIfExists(upload.temp);
        } catch (IOException failure) {
            GTMiaoBlueprint.LOG.warn("Library temporary upload cleanup failed", failure);
        }
        reservedBytes -= upload.total;
        activeUploads--;
        uploadBudget.cancel(peer.owner);
    }

    private void clearDownload(Peer peer) {
        if (peer.download == null) return;
        downloadBytes -= peer.download.payload.decodedTotal;
        activeDownloads--;
        peer.download = null;
        downloadBudget.cancel(peer.owner);
    }

    void stop() {
        closed = true;
        trusted.clear();
        worker.execute(() -> {
            List<Peer> copy;
            synchronized (this) {
                copy = new ArrayList<>(peers.values());
                peers.clear();
            }
            for (Peer peer : copy) {
                peer.online = false;
                abort(peer);
                clearDownload(peer);
            }
        });
        worker.shutdown();
    }

    private static final class Envelope {

        int schema = 1;
        String owner, uploadedBy;
        HologramPresets.Preset blueprint;
    }

    private static final class Entry {

        final UUID owner;
        final String uploadedBy;
        final HologramPresets.Preset blueprint;
        final int bytes;
        final String hash;
        final long revision, dtoBytes;
        final String storedJsonHash;
        volatile HologramPublicPreview.Builder materialBuilder;
        volatile NBTTagCompound preparedMaterials;
        boolean thumbnailUnavailable;

        Entry(Envelope envelope, int bytes, String hash, long revision, byte[] json) throws IOException {
            dtoBytes = HologramTrustedBlueprintCache.dtoWeight(envelope.blueprint, json.length);
            storedJsonHash = HologramBlueprintArtifact.hash(json);
            this.hash = hash;
            this.revision = revision;
            owner = UUID.fromString(envelope.owner);
            uploadedBy = envelope.uploadedBy;
            blueprint = envelope.blueprint;
            this.bytes = bytes;
        }
    }

    private static final class Peer {

        final EntityPlayerMP player;
        final Object world;
        final UUID owner;
        final String name;
        final HologramLibraryProtocol.RequestQueue requests = new HologramLibraryProtocol.RequestQueue();
        volatile boolean online = true, admin;
        volatile Upload upload;
        volatile Download download;
        Draft pendingDraft;
        UpgradeReport upgradeReport;
        volatile Entry materialPreview;
        volatile NBTTagCompound materialPreviewRequest;
        volatile long retiredThrough;
        Preparation preparation;
        NBTTagCompound preparationRequest;

        Peer(EntityPlayerMP player) {
            this.player = player;
            world = net.minecraft.server.MinecraftServer.getServer();
            owner = HologramLibraryFacade.actorId(player);
            name = player.getCommandSenderName();
        }
    }

    /** Queue entry and running capability are the same object, so retirement removes queued work too. */
    static final class Preparation implements Runnable {

        final long token;
        volatile boolean valid = true;
        Runnable action;

        Preparation(long token) {
            this.token = token;
        }

        public void run() {
            if (valid && action != null) action.run();
            action = null;
        }
    }

    private static final class Upload {

        final String transfer, hash, replace;
        final int total;
        final boolean privateUpload, previewUpload;
        final NBTTagCompound request;
        final Path temp;
        final OutputStream output;
        final MessageDigest digest = HologramLibraryProtocol.digest();
        int offset, index;
        boolean ackPending;
        long lastActivity = System.nanoTime();
        volatile boolean valid = true;

        Upload(NBTTagCompound request, Path temp, OutputStream output) {
            this.request = request;
            transfer = request.getString("transfer");
            hash = request.getString("hash");
            total = request.getInteger("total");
            replace = request.getString("replaceId");
            privateUpload = "privateBegin".equals(request.getString("op"));
            previewUpload = "previewBegin".equals(request.getString("op"));
            this.temp = temp;
            this.output = output;
        }
    }

    private static final class Download {

        final String transfer = UUID.randomUUID()
            .toString();
        final byte[] bytes;
        final HologramLibraryDownload.Encoded payload;
        final long clientTaskToken;
        int offset, index;
        int budgetPolls;
        long startedAt;
        long lastActivity = System.nanoTime();
        volatile boolean valid = true;
        NBTTagCompound boundRequest;

        Download(HologramLibraryDownload.Encoded payload, long clientTaskToken) {
            this.payload = payload;
            this.clientTaskToken = clientTaskToken;
            this.bytes = payload.bytes;
        }
    }

    private static final class Draft {

        final byte[] bytes;
        final String name;
        final NBTTagCompound request;

        Draft(byte[] bytes, String name, NBTTagCompound request) {
            this.bytes = bytes;
            this.name = name;
            this.request = request;
        }
    }
}
