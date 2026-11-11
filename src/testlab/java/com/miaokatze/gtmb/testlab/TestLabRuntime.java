// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

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
import com.miaokatze.miaokatzecore.testkit.Deadline;

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
        boolean nativeRun = "native-build".equals(requestedKind) || "native-test".equals(requestedKind);
        if (nativeRun && !config.nativePlatforms)
            throw new IOException("Native platforms require local nativePlatformsEnabled configuration");
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
        TestLabRecoveryGuard.verify(ownership.root, config, ownership.owner, requestedKind);
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
        report.data.setString(
            "reportPath",
            ownership.directory.resolve("latest-report.json")
                .toString());
        report.event("run.started", "", null, "");
        try {
            lease = new TestLabLease(
                world,
                config,
                ownership,
                runId,
                nativeRun ? config.nativeMaxChunks : config.maxChunks);
            TestLabZone nativeEntrance = null;
            if ("native-test".equals(kind)) {
                NBTTagCompound index = ownership.nativeIndex();
                if (index == null || !"READY".equals(index.getString("state"))
                    || index.getTagList("pages", 10)
                        .tagCount() == 0)
                    throw new IOException("Build persistent native platforms first");
                NBTTagCompound first = index.getTagList("pages", 10)
                    .getCompoundTagAt(0)
                    .getCompoundTag("source");
                nativeEntrance = new TestLabZone(
                    first.getString("id"),
                    first.getInteger("x"),
                    first.getInteger("y"),
                    first.getInteger("z"),
                    first.getInteger("sizeX"),
                    first.getInteger("sizeY"),
                    first.getInteger("sizeZ"));
            }
            actor = new TestLabActor(
                world,
                ownership,
                config,
                runId,
                "composite".equals(kind) || "native-test".equals(kind),
                nativeEntrance);
            report.data.setTag("actor", actor.tag());
            report.data.getCompoundTag("coverage")
                .setString(
                    "actor",
                    actor.tag()
                        .getString("kind"));
            report.data.getCompoundTag("coverage")
                .setString(
                    "availableActorTransport",
                    actor.tag()
                        .getString("transport"));
            backend = new TestLabBackend(this);
            if (config.performance) report.data.setTag("metricsStart", HologramRuntimeMetrics.snapshot());
            task = TestLabTasks.create(this, kind);
            report.event("task.started", "", null, "");
        } catch (Exception failure) {
            finish("FAIL", failure.toString());
            persistSafely();
            throw failure;
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || task == null) return;
        long start = System.nanoTime();
        Deadline deadline = Deadline.startingAt(start, config.tickMillis * 1000000L);
        ticks++;
        try {
            int timeout = kind.startsWith("native-") ? config.nativeSeconds : config.runSeconds;
            if (start - report.started > timeout * 1000000000L)
                throw new java.util.concurrent.TimeoutException("Bounded run timeout");
            for (int operations = 0; operations < config.operations && deadline.hasTimeRemaining(); operations++) {
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
            finish(failure instanceof java.util.concurrent.TimeoutException ? "TIMEOUT" : "FAIL", failure.toString());
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
            || !(config.contains(x, y, z, sx, sy, sz)
                || (task instanceof TestLabNativeTask && ((TestLabNativeTask) task).contains(x, y, z, sx, sy, sz))))
            throw new IOException("Operation outside owned lab facilities");
        if (task instanceof TestLabNativeTask && (lease == null || !lease.covers(x, z, sx, sz)))
            throw new IOException("Native operation outside the currently leased facilities");
    }

    void useNativeZones(TestLabZone actorSource, TestLabZone... zones) throws IOException {
        requireActor();
        if (!(task instanceof TestLabNativeTask)) throw new IOException("Native session is not active");
        TestLabNativeTask nativeTask = (TestLabNativeTask) task;
        if (!nativeTask.owns(actorSource)) throw new IOException("Unknown native actor facility");
        for (TestLabZone zone : zones) if (!nativeTask.owns(zone)) throw new IOException("Unknown native facility");
        lease.acquire(zones);
        leasedZone = actorSource;
        actor.atNative(actorSource);
        if (report != null) report.data.setTag("actor", actor.tag());
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
                    .contains(x, y, z, sx, sy, sz)))
                || (task instanceof TestLabNativeTask && ((TestLabNativeTask) task).contains(x, y, z, sx, sy, sz))))
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
        if (!lease.covers(x, z, sx, sz)) return false;
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

    NBTTagCompound nativeManifest() {
        return task instanceof TestLabNativeTask ? ((TestLabNativeTask) task).manifest() : new NBTTagCompound();
    }

    NBTTagCompound nativePageManifest(String id) throws IOException {
        if (!(task instanceof TestLabNativeTask)) throw new IOException("Native session is not active");
        return ((TestLabNativeTask) task).pageManifest(id);
    }

    void useNativeFacility(World target, EntityPlayerMP user, String id) throws IOException {
        requireActor();
        if (!(task instanceof TestLabNativeTask) || !"native-test".equals(kind)
            || target != world
            || user != actor.player) throw new IOException("Native facility switch refused");
        ((TestLabNativeTask) task).useFacility(id);
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
        if ("PASS".equals(result) && "STAGED_RESTART_REQUIRED".equals(
            report.data.getCompoundTag("constructionRecovery")
                .getString("state"))) {
            result = "RECOVERY_PENDING";
            detail = "Durable recovery fixture staged; explicit recovery-resume after process restart remains required";
        }
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
            report.event("task.finished", "", TestLabReport.result(result), "");
            report.data.setString("state", result);
            report.data.setString("detail", detail);
            if (config.performance) report.data.setTag("metricsEnd", HologramRuntimeMetrics.snapshot());
        }
        cleanupResources();
        if (report != null) report.event(
            "CANCELLED".equals(result) ? "run.cancelled" : "run.finished",
            "",
            TestLabReport.result(state),
            cleanupPending ? "pending" : "released");
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
            if (actor != null) {
                actor.clear();
                lastActor = (NBTTagCompound) actor.tag()
                    .copy();
                if (report != null) report.data.setTag("actor", lastActor.copy());
            }
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
            report.event(
                "cleanup.finished",
                "owned-resources",
                cleanupPending ? com.miaokatze.miaokatzecore.api.testlab.LabResult.FAIL
                    : com.miaokatze.miaokatzecore.api.testlab.LabResult.PASS,
                cleanupPending ? "pending" : "actor-restored; lease-released; persistent-owned-fixtures-retained");
        }
    }

    private void persistSafely() {
        try {
            persist();
        } catch (IOException | RuntimeException failure) {
            if (report != null) {
                report.data.setString("reportPersistenceError", failure.toString());
                report.event(
                    "report.failed",
                    "",
                    com.miaokatze.miaokatzecore.api.testlab.LabResult.FAIL,
                    failure.toString());
            }
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
            report.event("report.written", "", null, "");
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
