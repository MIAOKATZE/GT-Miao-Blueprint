package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;

/** One IO worker, bounded mailboxes and aggregate fair transfer budgets; never sleeps on a server tick. */
final class HologramServerLibrary {

    private static final Gson GSON = new Gson();
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_PEERS = 64;
    private static final long MAX_STORED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_RESERVED_BYTES = 16L * 1024 * 1024;
    private static final Field LIST_FIELD = listField();
    private final Path directory;
    private final ScheduledThreadPoolExecutor worker;
    private final Map<UUID, Peer> peers = new LinkedHashMap<>();
    private final ArrayBlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(16);
    private volatile Map<String, Entry> published = Collections.emptyMap();
    private volatile HologramLibraryConfig config;
    private volatile boolean closed;
    private volatile boolean ready;
    private volatile String loadFailure;
    private long storedBytes, reservedBytes, downloadBytes, catalogRevision = 1;
    private int activeDownloads;
    private int activeUploads, cursor;
    private final HologramLibraryProtocol.FairBudget uploadBudget = new HologramLibraryProtocol.FairBudget();
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
                        && candidate.requests.isEmpty()) {
                        candidate.online = false;
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
        // One in-flight chunk plus at most three controls. Neither Netty nor the main scheduler can grow a queue.
        if (peer.requests.size() >= 4) {
            error(peer, "Server library request queue is busy; retry later", request.getLong("clientTaskToken"));
            return;
        }
        peer.requests.addLast((NBTTagCompound) request.copy());
    }

    synchronized void heartbeat() {
        for (Peer peer : peers.values()) {
            peer.online = peer.player.playerNetServerHandler != null
                && peer.player.playerNetServerHandler.netManager != null
                && peer.player.playerNetServerHandler.netManager.isChannelOpen();
            peer.admin = HologramLibraryFacade.isAdmin(peer.player);
            Upload upload = peer.upload;
            if (upload != null && upload.privateUpload
                && !HologramLibraryService.sessionCurrent(peer.player, upload.request)) upload.valid = false;
            if (upload != null && upload.previewUpload
                && !HologramLibraryService.saveCurrent(peer.player, upload.request)) upload.valid = false;
            Download download = peer.download;
            if (download != null && download.boundRequest != null
                && !HologramLibraryService.draftCurrent(peer.player, download.boundRequest)) download.valid = false;
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
            HologramLibraryConfig.DEFAULT_KIB_PER_SECOND,
            HologramLibraryConfig.DEFAULT_KIB_PER_SECOND,
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
            NBTTagCompound request;
            synchronized (this) {
                request = peer.requests.peekFirst();
            }
            if (request != null) {
                String op = request.getString("op");
                int length = request.getByteArray("data").length;
                boolean chunk = "uploadChunk".equals(op) || "privateChunk".equals(op) || "previewChunk".equals(op);
                if (!chunk || length == 0
                    || length > HologramLibraryProtocol.CHUNK_BYTES
                    || uploadBudget.take(peer.owner, length, config.view().uploadKiB * 1024, now)) {
                    synchronized (this) {
                        peer.requests.pollFirst();
                    }
                    try {
                        handle(peer, request);
                    } catch (IOException | RuntimeException invalid) {
                        if (chunk || "uploadBegin".equals(op) || "privateBegin".equals(op) || "previewBegin".equals(op))
                            abort(peer);
                        error(peer, invalid.getMessage(), request.getLong("clientTaskToken"));
                    }
                }
            }
            try {
                sendDownload(peer, now);
            } catch (RuntimeException failure) {
                long clientTaskToken = taskToken(peer);
                clearDownload(peer);
                error(peer, failure.getMessage(), clientTaskToken);
            }
        }
    }

    private void handle(Peer peer, NBTTagCompound request) throws IOException {
        String op = request.getString("op");
        if ("list".equals(op)) list(peer, request.getInteger("page"));
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
            HologramBlueprintArtifact.deletePair(directory.resolve(id + ".json"));
            Map<String, Entry> next = new LinkedHashMap<>(published);
            next.remove(id);
            published = Collections.unmodifiableMap(next);
            storedBytes -= entry.bytes;
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
            HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact
                .readPair(directory.resolve(entry.blueprint.id + ".json"));
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

    private void begin(Peer peer, NBTTagCompound request) throws IOException {
        if (request.getInteger("artifactVersion") != HologramBlueprintArtifact.VERSION)
            throw new IOException("Unsupported blueprint artifact version");
        HologramLibraryProtocol
            .header(request.getString("transfer"), request.getInteger("total"), request.getString("hash"));
        if (peer.upload != null) throw new IOException("An upload is already active");
        boolean privateUpload = "privateBegin".equals(request.getString("op"));
        boolean previewUpload = "previewBegin".equals(request.getString("op"));
        String replace = request.getString("replaceId");
        if (!privateUpload && !previewUpload) {
            if (!replace.isEmpty()) owned(peer.owner, replace);
            else checkQuota(peer.owner, countOwned(peer.owner), config.view().maxUploads);
        }
        if (activeUploads >= 4 || request.getInteger("total") > MAX_RESERVED_BYTES - reservedBytes)
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
        peer.upload = upload;
        reservedBytes += upload.total;
        activeUploads++;
        NBTTagCompound ready = HologramLibraryProtocol.reply(previewUpload ? "previewReady" : "uploadReady");
        ready.setLong("clientTaskToken", request.getLong("clientTaskToken"));
        ready.setString("transfer", upload.transfer);
        ready.setInteger("nextIndex", 0);
        send(peer, ready);
    }

    private void append(Peer peer, NBTTagCompound request) throws IOException {
        Upload upload = peer.upload;
        if (upload == null || !upload.transfer.equals(request.getString("transfer")))
            throw new IOException("Unknown upload");
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
                    HologramBlueprintSharing.decode(new String(artifact.json, StandardCharsets.UTF_8)));
                if (upload.privateUpload) HologramLibraryFacade.importPrivate(peer.player, upload.request, blueprint);
                else publish(peer, upload, blueprint, artifact.png);
            }
            abort(peer);
        } else {
            NBTTagCompound ack = HologramLibraryProtocol.reply("chunkAck");
            ack.setLong("clientTaskToken", upload.request.getLong("clientTaskToken"));
            ack.setString("transfer", upload.transfer);
            ack.setInteger("index", upload.index - 1);
            ack.setInteger("nextIndex", upload.index);
            send(peer, ack);
        }
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
        byte[] bytes = HologramBlueprintSharing.encodeBounded(GSON, envelope, HologramBlueprintSharing.MAX_BYTES);
        byte[] boundPreview = HologramBlueprintArtifact.bindPreview(bytes, preview);
        long nextBytes = storedBytes - (previous == null ? 0 : previous.bytes) + bytes.length + boundPreview.length;
        if (nextBytes > MAX_STORED_BYTES) throw new IOException("Server library byte budget is full");
        HologramBlueprintArtifact.writePair(directory.resolve(blueprint.id + ".json"), bytes, boundPreview);
        Map<String, Entry> next = new LinkedHashMap<>(published);
        next.put(blueprint.id, new Entry(envelope, bytes.length + boundPreview.length));
        published = Collections.unmodifiableMap(next);
        storedBytes = nextBytes;
        catalogRevision++;
        NBTTagCompound result = HologramLibraryProtocol.reply("uploaded");
        result.setLong("clientTaskToken", upload.request.getLong("clientTaskToken"));
        result.setString("id", blueprint.id);
        result.setString("transfer", upload.transfer);
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
        Map<String, Entry> loaded = new LinkedHashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path path : files) {
                String name = path.getFileName()
                    .toString();
                if (name.startsWith("incoming-") && name.endsWith(".tmp")) {
                    Files.deleteIfExists(path);
                    continue;
                }
                if (!name.matches("public_[a-f0-9-]{36}\\.json")) continue;
                if (loaded.size() >= MAX_ENTRIES) throw new IOException("Server blueprint index entry budget");
                HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(path);
                byte[] bytes = artifact.json;
                if (bytes.length + artifact.png.length > MAX_STORED_BYTES - storedBytes)
                    throw new IOException("Server blueprint index byte budget");
                String json = new String(bytes, StandardCharsets.UTF_8);
                HologramBlueprintSharing.boundedJson(json);
                HologramBlueprintVersions.validateJson(
                    new JsonParser().parse(json)
                        .getAsJsonObject()
                        .get("blueprint"));
                Envelope envelope = GSON.fromJson(json, Envelope.class);
                if (envelope == null || envelope.schema != 1
                    || envelope.blueprint == null
                    || !name.equals(envelope.blueprint.id + ".json")
                    || envelope.uploadedBy == null
                    || envelope.uploadedBy.length() > 64) throw new IOException("Invalid server blueprint envelope");
                UUID.fromString(envelope.owner);
                envelope.blueprint = canonical(envelope.blueprint);
                loaded.put(envelope.blueprint.id, new Entry(envelope, bytes.length + artifact.png.length));
                storedBytes += bytes.length + artifact.png.length;
            }
        }
        published = Collections.unmodifiableMap(loaded);
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
        HologramAePolicy.Draft draft = HologramAePolicy.sanitize(copy);
        if (draft.blocking) throw new IOException("Blueprint contains unresolved AE payloads; repair before upload");
        HologramPresets.validate(draft.preset);
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
        JsonObject share = new JsonObject();
        share.addProperty("schema", 2);
        share.add("blueprint", GSON.toJsonTree(blueprint));
        HologramBlueprintVersions.validate(blueprint);
        return HologramBlueprintSharing.encodeBounded(GSON, share, HologramBlueprintSharing.MAX_BYTES);
    }

    synchronized void draft(EntityPlayerMP player, NBTTagCompound request, byte[] bytes, String name)
        throws IOException {
        checkAvailable();
        Peer peer = peers.get(HologramLibraryFacade.actorId(player));
        if (peer == null || peer.player != player || !peer.online)
            throw new IOException("Server library connection changed; retry");
        if (bytes == null || bytes.length == 0 || bytes.length > HologramLibraryProtocol.MAX_BYTES)
            throw new IOException("Blueprint download byte budget exceeded");
        if (peer.pendingDraft != null || peer.download != null) throw new IOException("A download is already active");
        peer.pendingDraft = new Draft(bytes, name, (NBTTagCompound) request.copy());
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
        if (!enqueue(() -> {
            try {
                HologramLibraryFacade.draftPrepared(player, request, encodeShare(detached), detached.name);
            } catch (IOException | RuntimeException failure) {
                HologramLibraryFacade.draftPreparationFailed(player, request, failure.getMessage());
            }
        })) throw new IOException("Server library worker is busy; retry");
    }

    boolean enqueue(Runnable task) {
        return !closed && tasks.offer(task);
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
        peer.download = new Download(HologramLibraryDownload.encode(bytes), request.getLong("clientTaskToken"));
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
        Draft draft;
        synchronized (this) {
            draft = peer.pendingDraft;
            peer.pendingDraft = null;
        }
        if (draft != null) {
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
        Upload upload = peer.upload;
        if (upload == null) return;
        try {
            upload.output.close();
            Files.deleteIfExists(upload.temp);
        } catch (IOException failure) {
            GTMiaoBlueprint.LOG.warn("Library temporary upload cleanup failed", failure);
        }
        reservedBytes -= upload.total;
        activeUploads--;
        peer.upload = null;
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

        Entry(Envelope envelope, int bytes) {
            owner = UUID.fromString(envelope.owner);
            uploadedBy = envelope.uploadedBy;
            blueprint = envelope.blueprint;
            this.bytes = bytes;
        }
    }

    private static final class Peer {

        final EntityPlayerMP player;
        final UUID owner;
        final String name;
        final ArrayDeque<NBTTagCompound> requests = new ArrayDeque<>();
        volatile boolean online = true, admin;
        volatile Upload upload;
        volatile Download download;
        Draft pendingDraft;

        Peer(EntityPlayerMP player) {
            this.player = player;
            owner = HologramLibraryFacade.actorId(player);
            name = player.getCommandSenderName();
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
