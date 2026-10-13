package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

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

    private HologramLibraryClient() {}

    static boolean dedicated() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld != null && !mc.isIntegratedServerRunning();
    }

    void install() {
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
        if (libraryWorld != mc.theWorld || !current.equals(connection)) {
            cancel();
            HologramNetwork.clearLibraryClientTransport();
            libraryWorld = mc.theWorld;
            connection = current;
            generation++;
            server = new NBTTagList();
            selectedServer = "";
            config = new NBTTagCompound();
            page = 0;
            observedPublic = "";
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
        if ((outgoing != null || incoming != null || quickInProgress())
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
                    loaded = store.read(source.localId);
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
        return status;
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
        return outgoing != null || incoming != null || loading || preparing;
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
        captureActor();
        status = "正在准备草稿，确认后下载至客户端";
        HologramNetwork.sendLibrary(request);
    }

    void cancelDraftReview() {
        if (!draftPending || incoming != null) return;
        draftPending = downloadExpected = preparing = false;
        status = "草稿保存已取消";
    }

    void sendLocal(HologramState state, String action, boolean upload) {
        if (busy() || selectedLocal.isEmpty()) return;
        String id = selectedLocal;
        long token = generation;
        String targetServer = connection;
        preparing = true;
        status = upload ? "正在读取客户端蓝图，准备上传" : "正在读取客户端蓝图，准备" + actionLabel(action);
        work(() -> {
            byte[] bytes = store.read(id);
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
                begin.setString("session", state.session);
                begin.setLong("planRevision", state.data.getLong("planRevision"));
                begin.setString("action", action);
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
        cancelQuickPlacement();
        if (!outgoingId.isEmpty() || !incomingId.isEmpty()) {
            NBTTagCompound request = request("cancel");
            request.setString("transfer", outgoing != null ? outgoingId : incomingId);
            if (dedicated()) HologramNetwork.sendLibrary(request);
        }
        generation++;
        downloadExpected = false;
        draftPending = false;
        outgoing = null;
        incoming = null;
        outgoingId = incomingId = "";
        ready = inFlight = preparing = false;
        status = "传输已取消";
    }

    private void receive(NBTTagCompound reply) {
        lastActivity = System.nanoTime();
        String op = reply.getString("op");
        if ("quickBootstrap".equals(op)) {
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
        } else if ("uploadReady".equals(op) || "privateReady".equals(op)) {
            if (matchesOutgoing(reply)) ready = true;
        } else if ("chunkAck".equals(op)) {
            if (!matchesOutgoing(reply)) return;
            nextChunk = reply.getInteger("nextIndex");
            sentBytes = Math.min(outgoing.length, nextChunk * 1024);
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
            if (!downloadExpected) return;
            downloadExpected = false;
            draftPending = false;
            int total = reply.getInteger("total");
            if (incoming != null || total <= 0 || total > HologramClientLibrary.MAX_BYTES) {
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
            status = "下载中 · 等待服务器分片";
        } else if ("downloadChunk".equals(op)) {
            if (incoming == null || !incomingId.equals(reply.getString("transfer"))) return;
            byte[] bytes = reply.getByteArray("data");
            if (reply.getInteger("index") != incomingIndex || bytes.length > 1024
                || incoming.size() + bytes.length > incomingTotal) {
                cancel();
                status = "下载分片无效，请重试";
                return;
            }
            incoming.write(bytes, 0, bytes.length);
            incomingIndex++;
        } else if ("downloadDone".equals(op)) {
            if (incoming == null || !incomingId.equals(reply.getString("transfer"))) return;
            byte[] bytes = incoming.toByteArray();
            String expected = incomingHash;
            String replace = "draft".equals(incomingPurpose) ? incomingLocal : "";
            String name = actorName, uuid = actorUuid;
            long token = generation;
            String serverKey = connection;
            int expectedTotal = incomingTotal;
            preparing = true;
            incoming = null;
            incomingId = "";
            status = "正在校验并保存客户端蓝图";
            work(() -> {
                if (token != generation) return;
                if (bytes.length != expectedTotal || !hash(bytes).equals(expected))
                    throw new java.io.IOException("下载校验失败");
                String saved = store.save(bytes, replace, name, uuid);
                store.rememberLocal(serverKey, saved);
                main(() -> {
                    if (token != generation) return;
                    preparing = false;
                    selectedLocal = saved;
                    status = "已保存至客户端蓝图库";
                    refreshLocal();
                });
            });
        } else if ("error".equals(op)) {
            draftPending = false;
            configWritePending = downloadExpected = false;
            cancelQuickPlacement();
            outgoing = null;
            incoming = null;
            outgoingId = incomingId = "";
            ready = inFlight = preparing = false;
            status = "服务器：" + reply.getString("message");
        }
    }

    private boolean matchesOutgoing(NBTTagCompound reply) {
        return outgoing != null && (!reply.hasKey("transfer") || outgoingId.equals(reply.getString("transfer")));
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
        try {
            worker.execute(() -> {
                try {
                    job.run();
                } catch (Exception error) {
                    main(() -> {
                        loading = preparing = false;
                        status = "客户端蓝图库：" + error.getMessage();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            loading = preparing = false;
            status = "客户端蓝图库忙，请稍后重试";
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
