// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
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
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;

import com.google.gson.Gson;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/** One IO worker, bounded mailboxes and aggregate fair transfer budgets; never sleeps on a server tick. */
final class HologramServerLibrary {

    private static final Gson GSON = new Gson();
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_PEERS = 64;
    private static final AttributeKey<PublicCopyHistory> PUBLIC_COPY_HISTORY =
        new AttributeKey<>("gtmb.hologram.publicCopyHistory");
    private static final long MAX_STORED_BYTES = 64L * 1024 * 1024;
    static final long MAX_PUBLIC_DTO_BYTES = 512L * 1024 * 1024;
    private static final long MAX_RESERVED_BYTES = 2L * HologramBlueprintArtifact.MAX_BYTES;
    private static final Field LIST_FIELD = listField();
    private final Path directory;
    private final ScheduledThreadPoolExecutor worker;
    private final Map<UUID, Peer> peers = new LinkedHashMap<>();
    private final HologramTrustedBlueprintCache trusted = new HologramTrustedBlueprintCache();
    private final ArrayBlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(16);
    private volatile Map<String, Entry> published = Collections.emptyMap();
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
        String op = request.getString("op");
        boolean cancelling = "cancel".equals(op) || "cancelTransfer".equals(op);
        long clientTask = request.getLong("clientTaskToken");
        if (cancelling && request.hasKey("clientTaskToken", 4) && clientTask > 0) {
            // Cancellation must outlive an evicted mailbox, including when its control queue is already full.
            PublicCopyConnection copy = PublicCopyConnection.capture(player);
            if (copy != null) copy.history.retire(clientTask);
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
        if (cancelling) {
            // This runs on the server thread even while the sole IO pump is inside serialization.
            peer.retiredThrough = Math.max(peer.retiredThrough, clientTask);
            if (peer.preparation != null && (clientTask == 0 || peer.preparation.token == clientTask)) {
                peer.preparation.valid = false;
                tasks.remove(peer.preparation);
                peer.preparation = null;
            }
            if (peer.download != null && (clientTask == 0 || peer.download.clientTaskToken == clientTask))
                peer.download.valid = false;
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
        return true;
    }

    synchronized void heartbeat() {
        for (Peer peer : peers.values()) {
            peer.online = peer.player.playerNetServerHandler != null
                && peer.player.playerNetServerHandler.netManager != null
                && peer.player.playerNetServerHandler.netManager.isChannelOpen();
            if (!peer.online) trusted.retire(peer);
            peer.admin = HologramLibraryFacade.isAdmin(peer.player);
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
    }

    HologramPresets.Preset get(String id) throws IOException {
        checkAvailable();
        Entry entry = published.get(id);
        if (entry == null) return null;
        if (!entry.compactSource) throw new HologramCompactRequiredException();
        return HologramPresets.copy(entry.blueprint);
    }

    HologramLibraryConfig.View config() {
        return config == null ? null : config.view();
    }

    HologramLibraryPermissions.Group policy(UUID owner) {
        HologramLibraryConfig.View view = config();
        return view == null ? HologramLibraryPermissions.defaults(10)
            .effective(owner) : view.permissions.effective(owner);
    }

    boolean canDownload(UUID owner) {
        return !HologramLibraryFacade.enabled() || config() != null && policy(owner).download;
    }

    void requireDownload(UUID owner) throws IOException {
        if (!canDownload(owner)) throw new IOException("你所在的权限组不允许下载或读取服务器公共蓝图");
    }

    private void requirePublish(UUID owner) throws IOException {
        if (HologramLibraryFacade.enabled() && !policy(owner).publish) throw new IOException("你所在的权限组不允许发布服务器公共蓝图");
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
        }
        // A single 16 KiB slice per 50ms caps throughput at 320 KiB/s regardless
        // of configuration. Drain a bounded number of fair rounds instead.
        for (int pass = 0; pass < 4; pass++) {
            for (int n = 0; n < round.size(); n++) {
                Peer peer = round.get((start + n) % round.size());
                try {
                    sendDownload(peer, now);
                } catch (RuntimeException failure) {
                    long clientTaskToken = taskToken(peer);
                    clearDownload(peer);
                    error(peer, failure.getMessage(), clientTaskToken);
                }
            }
        }
    }

    private void process(Peer peer, NBTTagCompound request) {
        try {
            handle(peer, request);
        } catch (IOException | RuntimeException invalid) {
            String op = request.getString("op");
            if (HologramLibraryProtocol.uploadChunk(request) || "uploadBegin".equals(op))
                abort(peer);
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
        if ("uploadBegin".equals(op) || "uploadChunk".equals(op)) requirePublish(peer.owner);
        if ("list".equals(op)) list(peer, request.getInteger("page"));
        else if ("upgradeLibrary".equals(op)) upgradeLibrary(peer, request);
        else if ("configGet".equals(op)) send(peer, statusTag(peer.admin));
        else if ("configSet".equals(op)) {
            if (!peer.admin) throw new IOException("Operator level 4 required");
            if (request.hasKey("permissionGroupsVersion")) {
                if (request.getInteger("permissionGroupsVersion") != HologramLibraryPermissions.VERSION
                    || !request.hasKey("permissionGroups", 9)) throw new IOException("权限组协议版本不支持");
                config.update(
                    peer.admin,
                    request.getLong("expectedRevision"),
                    request.getInteger("uploadKiB"),
                    request.getInteger("downloadKiB"),
                    HologramLibraryPermissions.read(request.getTagList("permissionGroups", 10)));
            } else config.update(
                peer.admin,
                request.getLong("expectedRevision"),
                request.getInteger("uploadKiB"),
                request.getInteger("downloadKiB"),
                request.getInteger("maxUploads"));
            broadcastConfig();
            NBTTagCompound confirmed = statusTag(peer.admin);
            confirmed.setString("configRequestId", request.getString("configRequestId"));
            send(peer, confirmed);
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
        } else if ("uploadBegin".equals(op)) begin(peer, request);
        else if ("uploadChunk".equals(op)) append(peer, request);
        else if ("download".equals(op)) {
            requirePublicCopy(peer, request);
            long task = request.getLong("clientTaskToken");
            if (!peer.copyConnection.history.claim(task))
                throw new IOException("旧复制请求不会重新传送蓝图，请明确重新复制");
            Entry entry = publicEntry(request);
            if (request.hasKey("sourceRevision") || request.hasKey("sourceHash")) requirePublicRevision(entry, request);
            // This operation transfers a complete usable file; a browse label never authorizes releasing it.
            if (!entry.compactSource) throw new HologramCompactRequiredException();
            HologramBlueprintArtifact.Artifact artifact = storedArtifact(entry);
            HologramBlueprintArtifact.Artifact download = HologramBlueprintArtifact
                .transfer(HologramBlueprintCompactCodec.publicShare(artifact.json), artifact);
            NBTTagCompound identity = (NBTTagCompound) request.copy();
            identity.setString("sourceHash", entry.hash);
            identity.setLong("sourceRevision", entry.revision);
            queueDownload(
                peer,
                HologramBlueprintArtifact.encode(download.json, download.png),
                entry.blueprint.name,
                identity,
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

    private void requirePublicCopy(Peer peer, NBTTagCompound request) throws IOException {
        requireDownload(peer.owner);
        if (HologramBusinessWirePolicy.libraryRequest(
            request, HologramBusinessWirePolicy.Owner.NONE, HologramBusinessWirePolicy.Origin.NETWORK_INGRESS)
            != HologramBusinessWirePolicy.Intent.LIBRARY_DOWNLOAD)
            throw new IOException("完整蓝图仅在明确复制到本地时下载");
        if (!publicCopyCurrent(peer, request.getLong("clientTaskToken")))
            throw new IOException("服务器蓝图复制已取消或连接已变更，请重新确认复制");
        if (peer.download != null) throw new IOException("A download is already active");
    }

    private boolean publicCopyCurrent(Peer peer, long token) {
        PublicCopyConnection copy = peer.copyConnection;
        return !closed && peer.online && token > peer.retiredThrough && copy != null
            && token > copy.history.retiredThrough && copy.current(peer.player);
    }

    private static void requirePublicRevision(Entry entry, NBTTagCompound request) throws IOException {
        if (!entry.hash.equals(request.getString("sourceHash")) || !String.valueOf(entry.revision)
            .equals(
                request.hasKey("sourceRevision", 8) ? request.getString("sourceRevision")
                    : String.valueOf(request.getLong("sourceRevision"))))
            throw new IOException("Server blueprint changed; reopen its preview");
    }

    private Entry publicEntry(NBTTagCompound request) throws IOException {
        Entry entry = published.get(HologramLibraryService.publicId(request.getString("id")));
        if (entry == null) throw new IOException("Blueprint no longer exists");
        return entry;
    }

    private void begin(Peer peer, NBTTagCompound request) throws IOException {
        if (request.getInteger("artifactVersion") != HologramBlueprintArtifact.VERSION)
            throw new IOException("Unsupported blueprint artifact version");
        if (peer.upload != null) throw new IOException("An upload is already active");
        HologramLibraryProtocol
            .header(request.getString("transfer"), request.getInteger("total"), request.getString("hash"));
        String replace = request.getString("replaceId");
        requirePublish(peer.owner);
        if (!replace.isEmpty()) owned(peer.owner, replace);
        else checkQuota(peer.owner, countOwned(peer.owner), policy(peer.owner).quota);
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
        NBTTagCompound ready = HologramLibraryProtocol.reply("uploadReady");
        ready.setLong("clientTaskToken", request.getLong("clientTaskToken"));
        ready.setString("transfer", upload.transfer);
        ready.setInteger("nextIndex", 0);
        ready.setInteger("uploadWindow", HologramLibraryProtocol.MAX_UPLOAD_WINDOW);
        send(peer, ready);
    }

    private void append(Peer peer, NBTTagCompound request) throws IOException {
        Upload upload = peer.upload;
        if (upload == null || !admitted(peer, request)) throw new IOException("Unknown upload");
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
            HologramPresets.Preset blueprint = canonical(HologramBlueprintSharing.decodeStored(artifact.json));
            publish(peer, upload, blueprint, artifact);
            abort(peer);
        } else upload.ackPending = true;
    }

    private void publish(Peer peer, Upload upload, HologramPresets.Preset blueprint,
        HologramBlueprintArtifact.Artifact original) throws IOException {
        requirePublish(peer.owner);
        Entry previous = upload.replace.isEmpty() ? null : owned(peer.owner, upload.replace);
        if (previous == null) checkQuota(peer.owner, countOwned(peer.owner), policy(peer.owner).quota);
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
        HologramBlueprintArtifact.Artifact publication = HologramBlueprintArtifact.transfer(bytes, original);
        bytes = publication.json;
        byte[] boundPreview = publication.png;
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
            label.setBoolean("fullyVoucherCovered", Boolean.TRUE.equals(entry.blueprint.fullyVoucherCovered));
            label.setString(
                "gtmbVersion",
                entry.blueprint.nativeBlueprint == null ? "" : entry.blueprint.nativeBlueprint.gtmbVersion);
            label.setString("sourceHash", entry.hash);
            label.setLong("sourceRevision", entry.revision);
            label.setBoolean("needsCompaction", !entry.compactSource);
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
            var original = HologramBlueprintCompactCodec.parseStored(bytes, HologramBlueprintArtifact.MAX_JSON_BYTES);
            com.google.gson.JsonObject root = original.root();
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
                HologramBlueprintContentHash.of(bytes),
                1,
                bytes,
                original.compact());
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
                byte[] source = HologramBlueprintPreviewBinding.sourceJson(original.json);
                byte[] compact = HologramBlueprintUpgrade.migrate(
                    source,
                    HologramBlueprintArtifact.MAX_JSON_BYTES,
                    () -> peer.online && peer.admin && token > peer.retiredThrough && !closed && epoch == runtimeEpoch);
                if (source == compact) {
                    row.setString("state", "current");
                    current++;
                } else {
                    HologramBlueprintArtifact.Artifact migratedPair = HologramBlueprintArtifact
                        .transfer(compact, original);
                    compact = migratedPair.json;
                    byte[] bound = migratedPair.png;
                    Envelope envelope = new Envelope();
                    envelope.owner = previous.owner.toString();
                    envelope.uploadedBy = previous.uploadedBy;
                    // Reuse already admitted immutable cells; the IO worker must not re-resolve registries.
                    HologramPresets.Preset migrated = previous.blueprint;
                    if (HologramBlueprintUpgrade
                        .status(migrated.nativeBlueprint == null ? "" : migrated.nativeBlueprint.gtmbVersion) > 0) {
                        migrated = HologramSnapshotMetadataSource.copyMetadata(previous.blueprint);
                        migrated.cells.addAll(previous.blueprint.cells);
                        migrated.palette.addAll(previous.blueprint.palette);
                        com.google.gson.JsonObject header = HologramBlueprintCompactCodec
                            .displayRows(compact).blueprint;
                        migrated.nativeBlueprint = new com.google.gson.Gson()
                            .fromJson(header.get("nativeBlueprint"), HologramBlueprintVersions.Metadata.class);
                        migrated.modVersions.put(
                            "gtmb",
                            header.getAsJsonObject("modVersions")
                                .get("gtmb")
                                .getAsString());
                        migrated.fullyVoucherCovered = header.get("fullyVoucherCovered")
                            .getAsBoolean();
                    }
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
        published = Collections.unmodifiableMap(entries);
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
        String hash = trusted.commitSaved(
            peer,
            proof.getString("saveReceipt"),
            proof.hasKey("reviewedJsonSha256", 8) ? proof.getString("reviewedJsonSha256")
                : proof.getString("jsonSha256"));
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
        String operation = request.getString("op");
        boolean publicDownload = "download".equals(operation);
        if (publicDownload) requirePublicCopy(peer, request);
        else if (!"exportDraft".equals(operation)) throw new IOException("Unknown library transfer purpose");
        if (peer.download != null) throw new IOException("A download is already active");
        if (activeDownloads >= 4 || bytes.length > MAX_RESERVED_BYTES - downloadBytes)
            throw new IOException("Server download queue is busy; retry later");
        long task = request.getLong("clientTaskToken");
        HologramLibraryDownload.Encoded encoded = HologramLibraryDownload.encode(
            bytes, () -> publicDownload ? publicCopyCurrent(peer, task)
                : peer.online && !closed && (task == 0 || task > peer.retiredThrough));
        if (publicDownload) requirePublicCopy(peer, request);
        peer.download = new Download(encoded, task);
        peer.download.publicDownload = publicDownload;
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
        if (publicDownload) {
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
        if (!canDownload(peer.owner) && peer.download != null && peer.download.publicDownload) {
            long token = peer.download.clientTaskToken;
            clearDownload(peer);
            error(peer, "权限组已变更，公共蓝图读取已取消", token);
            return;
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
        if (!download.valid || download.publicDownload && !publicCopyCurrent(peer, download.clientTaskToken)) {
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
        if ("config".equals(tag.getString("op"))) {
            HologramLibraryPermissions.Group group = policy(peer.owner);
            tag.setString("permissionGroup", group.name);
            tag.setBoolean("canPublish", group.publish);
            tag.setBoolean("canDownload", group.download);
            tag.setInteger("memberQuota", group.quota);
        }
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
        final boolean compactSource;

        /** Publication and explicit migration use the compact writers; loading binds the observed original format. */
        Entry(Envelope envelope, int bytes, String hash, long revision, byte[] json) throws IOException {
            this(envelope, bytes, hash, revision, json, true);
        }

        Entry(Envelope envelope, int bytes, String hash, long revision, byte[] json, boolean compactSource)
            throws IOException {
            this.compactSource = compactSource;
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

    /** Mailboxes can be evicted; exactly one small watermark lives on the authenticated transport itself. */
    private static final class PublicCopyHistory {

        final UUID owner;
        volatile long retiredThrough;
        private long claimedThrough;

        PublicCopyHistory(UUID owner) {
            this.owner = owner;
        }

        synchronized void retire(long token) {
            retiredThrough = Math.max(retiredThrough, token);
        }

        synchronized boolean claim(long token) {
            if (token <= retiredThrough || token <= claimedThrough) return false;
            claimedThrough = token;
            return true;
        }
    }

    private static final class PublicCopyConnection {

        final NetHandlerPlayServer handler;
        final NetworkManager network;
        final Channel channel;
        final PublicCopyHistory history;

        private PublicCopyConnection(NetHandlerPlayServer handler, NetworkManager network, Channel channel,
            PublicCopyHistory history) {
            this.handler = handler;
            this.network = network;
            this.channel = channel;
            this.history = history;
        }

        boolean current(EntityPlayerMP player) {
            return current(player, handler, network, channel);
        }

        private static boolean current(EntityPlayerMP player, NetHandlerPlayServer handler, NetworkManager network,
            Channel channel) {
            return channel != null && channel.isOpen() && player.playerNetServerHandler == handler
                && handler.playerEntity == player && handler.netManager == network && network.getNetHandler() == handler
                && network.channel() == channel;
        }

        static PublicCopyConnection capture(EntityPlayerMP player) {
            NetHandlerPlayServer handler = player.playerNetServerHandler;
            NetworkManager network = handler == null ? null : handler.netManager;
            Channel channel = network == null ? null : network.channel();
            if (!current(player, handler, network, channel)) return null;
            UUID owner = HologramLibraryFacade.actorId(player);
            Attribute<PublicCopyHistory> attribute = channel.attr(PUBLIC_COPY_HISTORY);
            PublicCopyHistory history = attribute.get();
            if (history == null) {
                PublicCopyHistory fresh = new PublicCopyHistory(owner);
                history = attribute.setIfAbsent(fresh);
                if (history == null) history = fresh;
            }
            // No player, world, or library is retained by the attribute after the channel closes.
            return owner.equals(history.owner) && current(player, handler, network, channel)
                ? new PublicCopyConnection(handler, network, channel, history) : null;
        }
    }

    private static final class Peer {

        final EntityPlayerMP player;
        final Object world;
        final UUID owner;
        final String name;
        final PublicCopyConnection copyConnection;
        final HologramLibraryProtocol.RequestQueue requests = new HologramLibraryProtocol.RequestQueue();
        volatile boolean online = true, admin;
        volatile Upload upload;
        volatile Download download;
        Draft pendingDraft;
        UpgradeReport upgradeReport;
        volatile long retiredThrough;
        Preparation preparation;
        NBTTagCompound preparationRequest;

        Peer(EntityPlayerMP player) {
            this.player = player;
            world = net.minecraft.server.MinecraftServer.getServer();
            owner = HologramLibraryFacade.actorId(player);
            name = player.getCommandSenderName();
            copyConnection = PublicCopyConnection.capture(player);
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
        boolean publicDownload;
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
