package com.miaokatze.gtmb.hologram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.UserListOps;
import net.minecraft.server.management.UserListOpsEntry;
import net.minecraftforge.common.config.Configuration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.util.ServerTaskScheduler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in independent native-file witness for real socket publication and deletion. Runs only on the IO worker. */
public final class HologramLibrarySocketServerProbe {

    private static final Map<UUID, String> witnessed = new ConcurrentHashMap<>();
    private static final Map<UUID, String> uiWitnessed = new ConcurrentHashMap<>();
    private static volatile PhaseDriver phases;

    private HologramLibrarySocketServerProbe() {}

    static void observe(EntityPlayerMP player, NBTTagCompound reply) {
        String op = reply.getString("op");
        if (!Boolean.getBoolean("gtmb.hologram.librarySocketSmoke")) return;
        try {
            if (!HologramLibraryFacade.enabled() || player.playerNetServerHandler.netManager.isLocalChannel())
                throw new IllegalStateException("Library socket probe requires a real dedicated connection");
            Path root = Paths.get(".")
                .toRealPath();
            Path receipts = Paths.get(System.getProperty("gtmb.hologram.libraryReceiptDir", ""))
                .toRealPath();
            if (!root.toString()
                .toLowerCase(java.util.Locale.ROOT)
                .contains("hologram")
                || !receipts.getFileName()
                    .toString()
                    .equals("receipts")
                || !receipts.toString()
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("hologram"))
                throw new IllegalStateException("Prepared isolated hologram receipts required");
            ensurePhases(player, root, receipts);
            if ("config".equals(op)) {
                witnessConfig(player, reply, root, receipts);
                return;
            }
            if (!"uploaded".equals(op) && !"deleted".equals(op)) return;
            String id = reply.getString("id");
            if (!id.matches("public_[a-f0-9-]{36}")) throw new IllegalStateException("Probe public ID invalid");
            Path file = root.resolve("MiaoBlueprints")
                .resolve(id + ".json")
                .normalize();
            if (!file.startsWith(root)) throw new IllegalStateException("Probe path escaped fixture");
            if ("uploaded".equals(op)) {
                byte[] bytes = HologramBlueprintSharing.readBytes(file);
                JsonObject envelope = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                String name = envelope.getAsJsonObject("blueprint")
                    .get("name")
                    .getAsString();
                boolean ui = "SocketSufficient".equals(name) || "SocketV004".equals(name);
                if (!ui && !"LibrarySocketFixture".equals(name)) return;
                if (!HologramLibraryFacade.actorId(player)
                    .toString()
                    .equals(
                        envelope.get("owner")
                            .getAsString())
                    || !player.getCommandSenderName()
                        .equals(
                            envelope.get("uploadedBy")
                                .getAsString()))
                    throw new IllegalStateException("Native public envelope owner was not the socket actor");
                (ui ? uiWitnessed : witnessed).put(HologramLibraryFacade.actorId(player), id);
                NBTTagCompound receipt = new NBTTagCompound();
                receipt.setString("id", id);
                receipt.setString(
                    "owner",
                    HologramLibraryFacade.actorId(player)
                        .toString());
                receipt.setString(
                    "nativeFileSha256",
                    HologramLibraryProtocol.hex(
                        HologramLibraryProtocol.digest()
                            .digest(bytes)));
                receipt.setBoolean("realDedicatedSocket", true);
                receipt.setBoolean("nativeOwnerVerified", true);
                HologramBlueprintSharing.atomicWrite(
                    receipts.resolve(ui ? "library-ui-upload.nbt" : "library-socket-upload.nbt"),
                    CompressedStreamTools.compress(receipt));
            } else if (id.equals(witnessed.get(HologramLibraryFacade.actorId(player)))
                || id.equals(uiWitnessed.get(HologramLibraryFacade.actorId(player)))) {
                    if (Files.exists(file))
                        throw new IllegalStateException("Owner delete ACK did not remove native public file");
                    boolean ui = id.equals(uiWitnessed.get(HologramLibraryFacade.actorId(player)));
                    Files.write(
                        receipts.resolve(ui ? "library-ui-delete-server-pass.txt" : "library-socket-server-pass.txt"),
                        ("PASS real dedicated socket native UUID owner publication and own deletion " + id + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    (ui ? uiWitnessed : witnessed).remove(HologramLibraryFacade.actorId(player));
                }
        } catch (Exception failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .error("Library real-socket independent server witness failed", failure);
            throw new IllegalStateException("Library socket witness failed", failure);
        }
    }

    private static synchronized void ensurePhases(EntityPlayerMP player, Path root, Path receipts) {
        if (phases != null) return;
        phases = new PhaseDriver(player, MinecraftServer.getServer(), root, receipts);
        PhaseDriver driver = phases;
        ServerTaskScheduler.scheduleServerTask(
            () -> FMLCommonHandler.instance()
                .bus()
                .register(driver));
    }

    /** Called by the real server stopping event on the server thread; never leaves temporary fixture OPs behind. */
    public static synchronized void stop() {
        PhaseDriver previous = phases;
        phases = null;
        witnessed.clear();
        uiWitnessed.clear();
        if (previous != null) previous.close();
    }

    private static void witnessConfig(EntityPlayerMP player, NBTTagCompound reply, Path root, Path receipts)
        throws Exception {
        PhaseDriver driver = phases;
        if (driver == null || driver.player != player
            || !"op4".equals(driver.phase)
            || driver.level != 4
            || !reply.getBoolean("admin")) return;
        Path file = root.resolve("config/gtmb/server-blueprint-library.cfg");
        if (!Files.isRegularFile(file)) throw new IllegalStateException("Native Forge library config missing");
        Configuration config = new Configuration(file.toFile());
        config.load();
        int up = config
            .get("serverBlueprintLibrary", "uploadKiBPerSecond", HologramLibraryConfig.DEFAULT_UPLOAD_KIB_PER_SECOND)
            .getInt(HologramLibraryConfig.DEFAULT_UPLOAD_KIB_PER_SECOND);
        int down = config
            .get(
                "serverBlueprintLibrary",
                "downloadKiBPerSecond",
                HologramLibraryConfig.DEFAULT_DOWNLOAD_KIB_PER_SECOND)
            .getInt(HologramLibraryConfig.DEFAULT_DOWNLOAD_KIB_PER_SECOND);
        int quota = config.get("serverBlueprintLibrary", "maxUploadsPerPlayer", 10)
            .getInt(10);
        if (up != reply.getInteger("uploadKiB") || down != reply.getInteger("downloadKiB")
            || quota != reply.getInteger("maxUploads"))
            throw new IllegalStateException("Network config differs from native Forge file");
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("nativeConfigVerified", true);
        receipt.setLong("configRevision", reply.getLong("configRevision"));
        receipt.setInteger("uploadKiB", up);
        receipt.setInteger("downloadKiB", down);
        receipt.setInteger("maxUploads", quota);
        HologramBlueprintSharing
            .atomicWrite(receipts.resolve("library-ui-config.nbt"), CompressedStreamTools.compress(receipt));
        HologramBlueprintSharing.atomicWrite(
            receipts.resolve("library-ui-config-r" + reply.getLong("configRevision") + ".nbt"),
            CompressedStreamTools.compress(receipt));
    }

    /** File polling and receipts stay on this worker; only native OP-list mutation runs on the server thread. */
    public static final class PhaseDriver {

        final EntityPlayerMP player;
        final MinecraftServer server;
        final Path root, receipts;
        final ScheduledThreadPoolExecutor worker;
        final AtomicBoolean queued = new AtomicBoolean();
        volatile String phase = "";
        volatile int level;
        private UserListOpsEntry baseline;
        private boolean captured, active;
        private volatile boolean closed;
        private long lastPhase, revision;

        PhaseDriver(EntityPlayerMP player, MinecraftServer server, Path root, Path receipts) {
            this.player = player;
            this.server = server;
            this.root = root;
            this.receipts = receipts;
            worker = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "gtmb-library-op-fixture");
                thread.setDaemon(true);
                return thread;
            });
            worker.scheduleAtFixedRate(this::poll, 250, 250, TimeUnit.MILLISECONDS);
        }

        private void poll() {
            if (closed || queued.get()) return;
            try {
                Path request = receipts.resolve("library-ui-op-request.txt");
                if (!Files.isRegularFile(request)) return;
                if (Files.size(request) > 32) throw new IllegalStateException("OP fixture phase request too large");
                String requested = new String(HologramBlueprintSharing.readBytes(request, 32), StandardCharsets.UTF_8)
                    .trim();
                if (!"3".equals(requested) && !"4".equals(requested) && !"restore".equals(requested))
                    throw new IllegalStateException("Unknown OP fixture phase");
                Files.deleteIfExists(request);
                if (!queued.compareAndSet(false, true)) return;
                ServerTaskScheduler.scheduleServerTask(() -> {
                    try {
                        apply(requested, "requested");
                    } catch (Exception failure) {
                        failed(failure);
                    } finally {
                        queued.set(false);
                    }
                });
            } catch (Exception failure) {
                failed(failure);
            }
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (closed || event.phase != TickEvent.Phase.END || !active) return;
            boolean disconnected = player.playerNetServerHandler == null
                || player.playerNetServerHandler.netManager == null
                || !player.playerNetServerHandler.netManager.isChannelOpen();
            if (disconnected || MinecraftServer.getServer() != server
                || System.nanoTime() - lastPhase > 120000000000L) {
                try {
                    apply("restore", disconnected ? "disconnect" : "timeout");
                } catch (Exception failure) {
                    failed(failure);
                }
            }
        }

        private void apply(String requested, String reason) throws Exception {
            if (closed) return;
            UserListOps ops = server.getConfigurationManager()
                .func_152603_m();
            if (!captured) {
                baseline = (UserListOpsEntry) ops.func_152683_b(player.getGameProfile());
                captured = true;
            }
            boolean restore = "restore".equals(requested);
            if (!restore) {
                if (!HologramLibraryFacade.enabled() || MinecraftServer.getServer() != server
                    || player.playerNetServerHandler == null
                    || player.playerNetServerHandler.netManager == null
                    || player.playerNetServerHandler.netManager.isLocalChannel()
                    || !player.playerNetServerHandler.netManager.isChannelOpen())
                    throw new IllegalStateException(
                        "OP fixture phase requires its original real dedicated socket actor");
                ops.func_152687_a(new UserListOpsEntry(player.getGameProfile(), Integer.parseInt(requested)));
                active = true;
            } else {
                if (baseline == null) ops.func_152684_c(player.getGameProfile());
                else ops.func_152687_a(baseline);
                active = false;
            }
            UserListOpsEntry actual = (UserListOpsEntry) ops.func_152683_b(player.getGameProfile());
            level = actual == null ? 0 : actual.func_152644_a();
            int expected = restore ? (baseline == null ? 0 : baseline.func_152644_a()) : Integer.parseInt(requested);
            if (level != expected) throw new IllegalStateException("Native OP entry differs from fixture phase");
            phase = restore ? "restore" : "op" + requested;
            lastPhase = System.nanoTime();
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setString("phase", phase);
            receipt.setString(
                "actor",
                HologramLibraryFacade.actorId(player)
                    .toString());
            receipt.setString("actorName", player.getCommandSenderName());
            receipt.setString("reason", reason);
            receipt.setInteger("actualLevel", level);
            receipt.setInteger("level", level);
            receipt.setBoolean("nativeVerified", true);
            receipt.setBoolean("admin", HologramLibraryFacade.isAdmin(player));
            receipt.setBoolean("baselineRestored", restore && actual == baseline);
            receipt.setLong("phaseRevision", ++revision);
            String nativePhase = phase;
            worker.execute(() -> {
                try {
                    byte[] bytes = CompressedStreamTools.compress(receipt);
                    HologramBlueprintSharing.atomicWrite(receipts.resolve("library-ui-" + nativePhase + ".nbt"), bytes);
                    if ("restore".equals(nativePhase))
                        HologramBlueprintSharing.atomicWrite(receipts.resolve("library-ui-oprestore.nbt"), bytes);
                    HologramBlueprintSharing.atomicWrite(receipts.resolve("library-ui-op-ready.nbt"), bytes);
                } catch (Exception failure) {
                    failed(failure);
                }
            });
        }

        private void failed(Exception failure) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.error("Library native OP fixture failed", failure);
            if (!worker.isShutdown()) worker.execute(() -> {
                try {
                    Files.write(
                        receipts.resolve("library-ui-op-failed.txt"),
                        failure.toString()
                            .getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            });
        }

        void close() {
            try {
                if (captured && active) apply("restore", "serverStopping");
            } catch (Exception failure) {
                failed(failure);
            }
            closed = true;
            FMLCommonHandler.instance()
                .bus()
                .unregister(this);
            worker.shutdown();
        }
    }
}
