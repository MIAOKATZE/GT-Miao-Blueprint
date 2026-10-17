package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** One bounded job, owned zones and one transient lease. Disabled configurations never instantiate this handler. */
public final class TestLabRuntime {

    private final TestLabConfig config;
    private TestLabOwnership ownership;
    private WorldServer world;
    private TestLabLease lease;
    private TestLabActor actor;
    private TestLabBackend backend;
    private TestLabTasks.Task task;
    private TestLabReport report;
    private TestLabZone leasedZone;
    private String state = "IDLE", runId = "", kind = "";
    private boolean stopping;
    private boolean reportPersisted;
    private String persistedRunId = "";
    private long reportWriteNanos;
    private long ticks;
    private NBTTagCompound lastActor = new NBTTagCompound();
    private boolean cleanupPending;

    TestLabRuntime(TestLabConfig config) {
        this.config = config;
    }

    TestLabConfig config() {
        return config;
    }

    TestLabOwnership ownership() {
        return ownership;
    }

    TestLabActor actor() {
        return actor;
    }

    WorldServer world() {
        return world;
    }

    TestLabBackend backend() {
        return backend;
    }

    TestLabReport evidence() {
        return report;
    }

    long ticks() {
        return ticks;
    }

    boolean active() {
        return task != null;
    }

    void start(String requestedKind) throws Exception {
        if (!TestLab.isEnabled()) throw new IOException("Lab disabled");
        if (active()) throw new IOException("Another lab run is active; stop it first");
        if ("composite".equals(requestedKind) && !config.composite)
            throw new IOException("Composite suite requires local compositeEnabled configuration");
        if (hasWorldResources()) {
            cleanupResources();
            if (cleanupPending)
                throw new IOException("Previous lab cleanup is incomplete; retained resources were not replaced");
        }
        WorldServer overworld = MinecraftServer.getServer()
            .worldServerForDimension(0);
        ownership = new TestLabOwnership(
            overworld.getSaveHandler()
                .getWorldDirectory()
                .toPath(),
            config);
        world = DimensionManager.getWorld(config.dimension);
        if (world == null) {
            DimensionManager.initDimension(config.dimension);
            world = DimensionManager.getWorld(config.dimension);
        }
        if (world == null || !(world.provider instanceof TestLabWorldProvider)
            || world.provider.dimensionId != config.dimension)
            throw new IOException("Registered lab provider/world identity differs");
        for (Object user : world.playerEntities) if (!(user instanceof FakePlayer))
            throw new IOException("Human presence in lab dimension: automated mutation refused");
        runId = UUID.randomUUID()
            .toString();
        kind = requestedKind;
        state = "RUNNING";
        stopping = false;
        reportPersisted = false;
        persistedRunId = "";
        reportWriteNanos = 0;
        lastActor = new NBTTagCompound();
        report = new TestLabReport(runId, kind, config);
        try {
            lease = new TestLabLease(world, config, ownership, runId);
            actor = new TestLabActor(world, ownership, config, runId, "composite".equals(kind));
            report.data.setTag("actor", actor.tag());
            backend = new TestLabBackend(this);
            if (config.performance) report.data.setTag("metricsStart", HologramRuntimeMetrics.snapshot());
            task = TestLabTasks.create(this, kind);
        } catch (Exception failure) {
            finish("FAIL", failure.toString());
            persistSafely();
            throw failure;
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || task == null) return;
        long start = System.nanoTime(), deadline = start + config.tickMillis * 1000000L;
        ticks++;
        try {
            if (start - report.started > config.runSeconds * 1000000000L) throw new IOException("Bounded run timeout");
            for (int operations = 0; operations < config.operations && System.nanoTime() < deadline; operations++) {
                requireActor();
                report.count("ops", 1);
                int result = task.step();
                if (result == TestLabTasks.DONE) {
                    finish("PASS", "All listed checks completed");
                    break;
                }
                if (result == TestLabTasks.WAIT) break;
            }
        } catch (Exception | LinkageError failure) {
            GTMiaoBlueprint.LOG.error("GTMB bounded testlab run failed " + runId, failure);
            finish("FAIL", failure.toString());
        } finally {
            if (report != null) {
                long elapsed = System.nanoTime() - start;
                report.tick(elapsed);
                if (elapsed > config.tickMillis * 1000000L) report.count("atomicStepsOverTickBudget", 1);
                if (!active()) persistSafely();
            }
        }
    }

    void useZone(TestLabZone zone) throws IOException {
        requireActor();
        if (!config.zones.contains(zone)) throw new IOException("Unknown lab facility");
        if (leasedZone != zone) {
            if ("composite".equals(kind) && !"composite-bank".equals(zone.id)) {
                if ("composite-source".equals(zone.id)) lease.acquire(zone, config.zone("composite-bank"));
                else lease.acquire(zone, config.zone("composite-source"), config.zone("composite-bank"));
            } else lease.acquire(zone);
            leasedZone = zone;
        }
        actor.at(zone);
        if (report != null) report.data.setTag("actor", actor.tag());
    }

    void requireOwned(int x, int y, int z, int sx, int sy, int sz) throws IOException {
        if (world == null || world.provider.dimensionId != config.dimension
            || !(world.provider instanceof TestLabWorldProvider)
            || !config.contains(x, y, z, sx, sy, sz)) throw new IOException("Operation outside owned lab facilities");
    }

    void useContainingZone(int x, int y, int z) throws IOException {
        for (TestLabZone zone : config.zones) if (zone.contains(x, y, z, 1, 1, 1)) {
            useZone(zone);
            return;
        }
        throw new IOException("No owned facility at the requested lab location");
    }

    void requireActor() throws IOException {
        if (actor == null || actor.player.worldObj != world
            || actor.player.dimension != config.dimension
            || !TestLab.isEnabled()
            || actor.player.isDead
            || actor.player.capabilities.isCreativeMode
            || (!(actor.player instanceof FakePlayer) && actor.player.theItemInWorldManager.getGameType()
                != net.minecraft.world.WorldSettings.GameType.SURVIVAL)
            || !ItemNekoHologramProjector.isProjector(actor.player.getHeldItem()))
            throw new IOException("Lab actor/world/tool ownership invalid");
        for (Object user : world.playerEntities) if (!(user instanceof FakePlayer) && user != actor.player)
            throw new IOException("Human presence entered the lab; automatic mutation stopped");
    }

    boolean ownsFixture(World target, EntityPlayerMP user, int x, int y, int z, int sx, int sy, int sz) {
        if (!active() || target != world
            || actor == null
            || user != actor.player
            || ownership == null
            || lease == null
            || lease.size() == 0
            || leasedZone == null
            || !(leasedZone.contains(x, y, z, sx, sy, sz) || ("composite".equals(kind) && (config.zone("composite-bank")
                .contains(x, y, z, sx, sy, sz)
                || config.zone("composite-source")
                    .contains(x, y, z, sx, sy, sz)))))
            return false;
        try {
            requireActor();
        } catch (IOException invalid) {
            return false;
        }
        NBTTagCompound tag = user.getHeldItem()
            .getTagCompound();
        if (tag == null || !ownership.owner.toString()
            .equals(tag.getString("testLabOwner")) || !runId.equals(tag.getString("testLabRun"))) return false;
        for (int cx = x >> 4; cx <= (x + sx - 1) >> 4; cx++) for (int cz = z >> 4; cz <= (z + sz - 1) >> 4; cz++)
            if (!world.theChunkProviderServer.chunkExists(cx, cz)) return false;
        return true;
    }

    NBTTagCompound compositeManifest() {
        NBTTagCompound manifest = new NBTTagCompound();
        if (!config.composite || !"composite".equals(kind) || !active()) return manifest;
        for (String name : new String[] { "source", "self", "share", "raw", "bank" }) {
            TestLabZone zone = config.zone("composite-" + name);
            manifest.setIntArray(name, new int[] { zone.x, zone.y, zone.z });
        }
        manifest.setIntArray("size", new int[] { 40, 40, 40 });
        manifest.setInteger("machineCount", config.compositeMachines);
        manifest.setString("runId", runId);
        manifest.setString("fixtureVersion", "native-composite-40-v1");
        manifest.setString(
            "actor",
            actor.player.getUniqueID()
                .toString());
        manifest.setInteger("dimension", config.dimension);
        return manifest;
    }

    void useCompositeFacility(World target, EntityPlayerMP user, String facility) throws IOException {
        if (!active() || !"composite".equals(kind)
            || target != world
            || actor == null
            || user != actor.player
            || !("source".equals(facility) || "self".equals(facility)
                || "share".equals(facility)
                || "raw".equals(facility)))
            throw new IOException("Composite facility switch refused");
        useZone(config.zone("composite-" + facility));
    }

    void stop(String reason) {
        if (active()) {
            finish("CANCELLED", reason);
            persistSafely();
        } else if (hasWorldResources()) {
            cleanupResources();
            if (report != null) persistSafely();
        }
    }

    private void finish(String result, String detail) {
        if (stopping) return;
        stopping = true;
        if (task != null) {
            try {
                task.cleanup();
            } catch (Exception failure) {
                result = "FAIL";
                detail = "Task cleanup failed: " + failure;
            }
        }
        task = null;
        state = result;
        if (report != null) {
            report.data.setString("state", result);
            report.data.setString("detail", detail);
            if (config.performance) report.data.setTag("metricsEnd", HologramRuntimeMetrics.snapshot());
        }
        cleanupResources();
        if (report != null) report.data.getCompoundTag("timing")
            .setLong("elapsedNanos", System.nanoTime() - report.started);
    }

    private boolean hasWorldResources() {
        return world != null || actor != null || lease != null || backend != null;
    }

    /** Retain failed leases for stop/start retry; detach observations before releasing successful world ownership. */
    private void cleanupResources() {
        boolean retry = cleanupPending;
        RuntimeException cleanupFailure = null;
        try {
            if (actor != null) {
                lastActor = (NBTTagCompound) actor.tag()
                    .copy();
                if (report != null) report.data.setTag("actor", lastActor.copy());
            }
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        }
        try {
            if (actor != null) actor.clear();
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        }
        try {
            if (lease != null) lease.release();
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        }
        leasedZone = null;
        int remaining = lease == null ? 0 : lease.size();
        cleanupPending = cleanupFailure != null || remaining != 0;
        if (cleanupPending) {
            state = "FAIL";
            if (report != null) {
                report.data.setString("state", state);
                String detail = cleanupFailure == null ? "Owned chunks remain: " + remaining
                    : cleanupFailure.toString();
                report.data.setString("cleanupError", detail);
                report.check("owned_chunk_cleanup", "FAIL", detail);
            }
            if (cleanupFailure != null) GTMiaoBlueprint.LOG.error("GTMB lab cleanup failed " + runId, cleanupFailure);
        } else {
            // No world-bound object is needed for terminal status/report: lastActor is detached NBT.
            world = null;
            actor = null;
            lease = null;
            backend = null;
            if (retry && report != null) report.data.setBoolean("cleanupRecovered", true);
        }
        if (report != null) {
            report.data.setInteger("ownedChunksAfterCleanup", remaining);
            report.data.setBoolean("cleanupPending", cleanupPending);
        }
    }

    private void persistSafely() {
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            if (report != null) report.data.setString("reportPersistenceError", failure.toString());
            GTMiaoBlueprint.LOG.error("Testlab report remains in memory after IO failure", failure);
        }
    }

    void persist() throws IOException {
        if (ownership == null || report == null)
            throw new IOException("No lab run/report exists; no world was created");
        long start = System.nanoTime();
        reportPersisted = false;
        try {
            ownership.report(report.snapshot());
            persistedRunId = runId;
            reportPersisted = true;
        } finally {
            reportWriteNanos = System.nanoTime() - start;
        }
    }

    NBTTagCompound report() {
        NBTTagCompound n = report == null ? new NBTTagCompound() : report.snapshot();
        persistence(n);
        return n;
    }

    NBTTagCompound status() {
        NBTTagCompound n = new NBTTagCompound();
        n.setBoolean("active", active());
        n.setString("state", state);
        n.setString("runId", runId);
        n.setString("runKind", kind);
        n.setBoolean("worldLoaded", DimensionManager.getWorld(config.dimension) != null);
        n.setInteger("ownedChunks", lease == null ? 0 : lease.size());
        if (actor != null || !lastActor.hasNoTags()) n.setTag("actor", actorTag());
        n.setBoolean("cleanupPending", cleanupPending);
        persistence(n);
        return n;
    }

    NBTTagCompound actorTag() {
        return actor == null ? (NBTTagCompound) lastActor.copy() : actor.tag();
    }

    private void persistence(NBTTagCompound n) {
        n.setBoolean("reportPersisted", reportPersisted);
        n.setString("persistedRunId", persistedRunId);
        n.setLong("reportWriteNanos", reportWriteNanos);
    }
}
