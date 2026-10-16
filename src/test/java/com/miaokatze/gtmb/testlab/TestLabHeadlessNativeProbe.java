package com.miaokatze.gtmb.testlab;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;

import com.miaokatze.gtmb.Tags;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/** Test-only FML mod. ROOT packages only these compiled classes into each new isolated server fixture. */
@Mod(
    modid = "gtmb_testlab_native_probe",
    name = "GTMB isolated native testlab observer",
    version = "1",
    dependencies = "required-after:gtmb",
    acceptableRemoteVersions = "*")
public final class TestLabHeadlessNativeProbe {

    private static final String PREFIX = "[GTMB-TESTLAB-NATIVE] ";
    private static final String[] OPERATIONS = { "reset", "run minimal", "run stress" };
    private final NBTTagList observations = new NBTTagList();
    private final NBTTagList commands = new NBTTagList();
    private final NBTTagList reports = new NBTTagList();
    private final Set<String> completedIds = new HashSet<>();
    private Path root;
    private MinecraftServer server;
    private String phase;
    private boolean active, awaiting;
    private int ticks, operation;
    private int watchdogSeconds;
    private long started;

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (!Boolean.getBoolean("gtmb.testlab.nativeProbe")) return;
        try {
            phase = System.getProperty("gtmb.testlab.nativePhase", "");
            require("disabled".equals(phase) || "enabled".equals(phase), "explicit native phase");
            root = Paths.get(System.getProperty("gtmb.testlab.nativeRoot", ""))
                .toAbsolutePath()
                .normalize();
            require(
                root.getFileName()
                    .toString()
                    .equals(phase),
                "phase-local output root");
            require(
                root.getParent()
                    .getParent()
                    .getFileName()
                    .toString()
                    .equals("gtmb-testlab-native"),
                "new isolated fixture namespace");
            require(
                Paths.get("")
                    .toAbsolutePath()
                    .normalize()
                    .equals(root.resolve("server")),
                "actual isolated server cwd");
            require(
                Files.isDirectory(root.resolve("receipts")) && !Files.isSymbolicLink(root),
                "prepared local receipts");
            require(
                FMLCommonHandler.instance()
                    .getSide() == Side.SERVER,
                "actual dedicated FML side");
            server = MinecraftServer.getServer();
            require(server != null && server.isDedicatedServer(), "actual dedicated Minecraft server");
            // Infrastructure bound derives from the three unchanged production operation timeouts plus handoff time.
            watchdogSeconds = 3 * TestLabConfig.load(
                root.resolve("server/config")
                    .toFile()).runSeconds
                + 30;
            started = System.nanoTime();
            active = true;
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            observe("server-started");
            require(
                DimensionManager.getWorld(Integer.getInteger("gtmb.testlab.nativeDimension", 1048578)) == null,
                "actual fresh fixture has no lab world before its first explicit operator operation");
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (!active || event.phase != TickEvent.Phase.END) return;
        try {
            require(
                ++ticks <= watchdogSeconds * 20 + 200 && System.nanoTime() - started < watchdogSeconds * 1000000000L,
                "derived native observer watchdog; production run budgets are unchanged");
            NBTTagCompound status = TestLab.status();
            require(
                status.getInteger("ownedChunks") >= 0
                    && status.getInteger("ownedChunks") <= status.getInteger("maxOwnedChunks"),
                "actual owned chunk bound");
            if (ticks <= 20 || ticks % 5 == 0) observe("server-end-tick");
            if (ticks < 20) return;
            if ("disabled".equals(phase)) {
                assertDisabled(status);
                finish();
                return;
            }
            if (!awaiting && operation == 0) {
                assertInitialEnabled(status);
                dispatch("status");
                require(
                    !TestLab.status()
                        .getBoolean("worldLoaded"),
                    "actual status command has no world side effect");
            }
            if (!awaiting) {
                if (operation == OPERATIONS.length) {
                    metricsOffRoundtrip();
                    dispatch("report");
                    finish();
                    return;
                }
                dispatch(OPERATIONS[operation]);
                awaiting = true;
                return;
            }
            status = TestLab.status();
            if (status.getBoolean("active")) return;
            NBTTagCompound report = TestLab.report();
            String expected = OPERATIONS[operation].replace("run ", "");
            require(
                "PASS".equals(report.getString("state")) && expected.equals(report.getString("kind")),
                "actual command run completed successfully: " + expected + " actual=" + report);
            String id = report.getString("runId");
            require(
                UUID.fromString(id)
                    .toString()
                    .equals(id) && completedIds.add(id),
                "fresh actual run UUID");
            require(
                report.getBoolean("serverBackendOnly") && report.getBoolean("notNetty"),
                "truthful backend-only scope");
            NBTTagList checks = report.getTagList("checks", 10);
            require(checks.tagCount() > 0, "actual run retains quantitative checks");
            for (int index = 0; index < checks.tagCount(); index++) {
                NBTTagCompound check = checks.getCompoundTagAt(index);
                require(
                    "PASS".equals(check.getString("status"))
                        || "idle_network_observation".equals(check.getString("name"))
                            && "INCONCLUSIVE".equals(check.getString("status")),
                    "transaction checks pass; only unattributed global idle-network observation may be inconclusive");
            }
            dispatch("report");
            reports.appendTag(report.copy());
            write("operation-" + operation + "-" + expected + ".nbt", report);
            awaiting = false;
            operation++;
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void assertDisabled(NBTTagCompound status) {
        require(
            !status.getBoolean("configEnabled") && !status.getBoolean("enabled"),
            "local disabled configuration remains disabled");
        for (String name : new String[] { "dimensionRegistered", "providerRegistered", "handlerRegistered",
            "worldLoaded", "active" }) require(!status.getBoolean(name), "disabled lab has no owned " + name);
        int dimension = Integer.getInteger("gtmb.testlab.nativeDimension", 1048578);
        require(
            !DimensionManager.isDimensionRegistered(dimension) && DimensionManager.getWorld(dimension) == null,
            "actual disabled dimension registration/world absent");
        require(
            !server.getCommandManager()
                .getCommands()
                .containsKey("gtmbtestlab"),
            "actual disabled command absent");
        require(
            registeredLabHandlers(
                FMLCommonHandler.instance()
                    .bus())
                == 0 && registeredLabHandlers(MinecraftForge.EVENT_BUS) == 0,
            "actual disabled FML and Forge listener registration maps have no production lab-owned object");
        require(
            !HologramRuntimeMetrics.isEnabled() && metricsCountersZero(HologramRuntimeMetrics.snapshot()),
            "disabled common metrics remain off/zero");
    }

    private void assertInitialEnabled(NBTTagCompound status) {
        require(
            status.getBoolean("configEnabled") && status.getBoolean("enabled"),
            "only actual local config enables lab");
        for (String name : new String[] { "dimensionRegistered", "providerRegistered", "handlerRegistered" })
            require(status.getBoolean(name), "enabled lab owns " + name);
        require(
            !status.getBoolean("worldLoaded") && !status.getBoolean("active"),
            "enabled initial status did not create a world/run");
        require(
            server.getCommandManager()
                .getCommands()
                .containsKey("gtmbtestlab"),
            "actual enabled console command present");
        require(
            registeredLabHandlers(
                FMLCommonHandler.instance()
                    .bus())
                == 1 && registeredLabHandlers(MinecraftForge.EVENT_BUS) == 0,
            "actual enabled FML listener map contains exactly its owned runtime handler");
    }

    private void dispatch(String arguments) {
        NBTTagCompound command = new NBTTagCompound();
        command.setInteger("sequence", commands.tagCount() + 1);
        command.setString("command", "gtmbtestlab " + arguments);
        command.setLong("serverTick", ticks);
        command.setTag("before", TestLab.status());
        int result = server.getCommandManager()
            .executeCommand(server, command.getString("command"));
        command.setInteger("actualCommandResult", result);
        command.setTag("after", TestLab.status());
        commands.appendTag(command);
        System.out.println(
            PREFIX + "COMMAND "
                + command.getInteger("sequence")
                + " "
                + command.getString("command")
                + " result="
                + result);
        require(result > 0, "actual console command manager accepted operation");
    }

    private void metricsOffRoundtrip() throws Exception {
        HologramRuntimeMetrics.setEnabled(false);
        HologramRuntimeMetrics.reset();
        TestLabConfig config = TestLabConfig.load(
            root.resolve("server/config")
                .toFile());
        TestLabReport report = new TestLabReport(
            UUID.randomUUID()
                .toString(),
            "observer-metrics-off",
            config);
        TestLabCodecWork codec = new TestLabCodecWork(1);
        boolean complete = false;
        for (int step = 0; step < 8 && !complete; step++) complete = codec.step(report, 1);
        require(
            complete && report.snapshot()
                .getCompoundTag("counters")
                .getLong("codecIterations") == 1,
            "metrics-off pressure actually roundtripped the production codec/frame");
        require(
            !HologramRuntimeMetrics.isEnabled() && metricsCountersZero(HologramRuntimeMetrics.snapshot()),
            "explicit metrics-off/reset actual codec work leaves all counters zero");
        NBTTagCompound observed = report.snapshot();
        observed.setTag("actualMetrics", HologramRuntimeMetrics.snapshot());
        write("metrics-off-codec.nbt", observed);
    }

    private static boolean metricsCountersZero(NBTTagCompound snapshot) {
        for (String group : new String[] { "network", "activity", "queues" })
            if (!allMetricsZero(snapshot.getCompoundTag(group))) return false;
        return true;
    }

    private static boolean allMetricsZero(net.minecraft.nbt.NBTBase tag) {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            for (Object key : compound.func_150296_c())
                if (!allMetricsZero(compound.getTag(String.valueOf(key)))) return false;
        } else if (tag instanceof net.minecraft.nbt.NBTBase.NBTPrimitive) {
            return ((net.minecraft.nbt.NBTBase.NBTPrimitive) tag).func_150291_c() == 0;
        }
        return true;
    }

    private void observe(String label) throws Exception {
        NBTTagCompound observation = new NBTTagCompound();
        observation.setString("label", label);
        observation.setLong("tick", ticks);
        observation.setLong("elapsedNanos", started == 0 ? 0 : System.nanoTime() - started);
        observation.setTag("status", TestLab.status());
        observation.setTag("metrics", HologramRuntimeMetrics.snapshot());
        int online = server.getConfigurationManager().playerEntityList.size();
        observation.setInteger("actualOnlinePlayers", online);
        require(online == 0, "headless backend-only phase has no actual connected players");
        observation.setInteger(
            "actualFmlLabHandlerObjects",
            registeredLabHandlers(
                FMLCommonHandler.instance()
                    .bus()));
        observation.setInteger("actualForgeLabHandlerObjects", registeredLabHandlers(MinecraftForge.EVENT_BUS));
        int dimension = Integer.getInteger("gtmb.testlab.nativeDimension", 1048578);
        observation.setInteger("configuredDimension", dimension);
        observation.setBoolean("actualDimensionRegistered", DimensionManager.isDimensionRegistered(dimension));
        net.minecraft.world.WorldServer world = DimensionManager.getWorld(dimension);
        observation.setBoolean("actualLabWorldLoaded", world != null);
        if (world != null) {
            observation.setInteger("actualWorldDimension", world.provider.dimensionId);
            observation.setString(
                "actualWorldName",
                world.getWorldInfo()
                    .getWorldName());
            observation.setString(
                "actualProviderClass",
                world.provider.getClass()
                    .getName());
        }
        observation.setTag("loadedClientClasses", loadedClientClasses());
        if (observations.tagCount() < 768) observations.appendTag(observation);
    }

    private static NBTTagCompound loadedClientClasses() throws Exception {
        NBTTagCompound observed = new NBTTagCompound();
        Method find = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        find.setAccessible(true);
        for (String name : new String[] { "net.minecraft.client.Minecraft",
            "com.miaokatze.gtmb.client.hologram.HologramClient",
            "com.miaokatze.gtmb.client.hologram.HologramProgressView" }) {
            boolean loaded = false;
            for (ClassLoader loader = TestLabHeadlessNativeProbe.class.getClassLoader(); loader
                != null; loader = loader.getParent()) loaded |= find.invoke(loader, name) != null;
            observed.setBoolean(name, loaded);
            require(!loaded, "actual headless observer did not load client class " + name);
        }
        return observed;
    }

    /** Inspect registration owners without posting events or trusting TestLab's self-reported handler flag. */
    private static int registeredLabHandlers(Object bus) {
        try {
            Field listeners = bus.getClass()
                .getDeclaredField("listeners");
            listeners.setAccessible(true);
            Object value = listeners.get(bus);
            require(value instanceof Map, "actual EventBus registration map is observable");
            int count = 0;
            for (Object owner : ((Map<?, ?>) value).keySet()) {
                String name = owner instanceof Class ? ((Class<?>) owner).getName()
                    : owner.getClass()
                        .getName();
                if (name.startsWith("com.miaokatze.gtmb.testlab.")
                    && !name.startsWith(TestLabHeadlessNativeProbe.class.getName())) count++;
            }
            return count;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot independently inspect actual EventBus registration owners", failure);
        }
    }

    private NBTTagCompound receipt(boolean passed) throws Exception {
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("passed", passed);
        result.setString("releaseVersion", Tags.RELEASE_VERSION);
        result.setString("phase", phase == null ? "" : phase);
        result.setBoolean("actualDedicatedServer", server != null && server.isDedicatedServer());
        result.setBoolean("serverBackendOnly", true);
        result.setBoolean("actualNettyPlayers", false);
        result.setInteger(
            "actualOnlinePlayers",
            server == null ? -1 : server.getConfigurationManager().playerEntityList.size());
        result.setBoolean("syntheticCodec", "enabled".equals(phase));
        result.setBoolean("probeTriggersClientEvents", false);
        result.setBoolean("productionClientEventActivityInstrumented", false);
        result.setString(
            "handlerObservationScope",
            "Actual FML/Forge EventBus registration owners in production testlab package; test-only observer excluded");
        result.setInteger("ticks", ticks);
        result.setInteger("watchdogSeconds", watchdogSeconds);
        result.setTag("commands", commands.copy());
        result.setTag("operations", reports.copy());
        result.setTag("observations", observations.copy());
        result.setTag("finalStatus", TestLab.status());
        result.setTag("finalReport", TestLab.report());
        result.setTag("finalLoadedClientClasses", loadedClientClasses());
        return result;
    }

    private void finish() throws Exception {
        active = false;
        write("native-final.nbt", receipt(true));
        Files.write(
            root.resolve("receipts/native-pass.txt"),
            ("PASS " + phase + " backend-only native fixture\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW);
        System.out.println(PREFIX + "FINAL PASS phase=" + phase + " actualDedicated=true actualNettyPlayers=false");
        server.initiateShutdown();
    }

    private void fail(Throwable failure) {
        active = false;
        failure.printStackTrace();
        System.err.println(PREFIX + "FINAL FAIL " + failure);
        try {
            if (root != null && Files.isDirectory(root.resolve("receipts"))) {
                Files.write(
                    root.resolve("receipts/native-fail.txt"),
                    (failure.toString() + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
                write("native-failed.nbt", receipt(false));
            }
        } catch (Exception preserving) {
            preserving.printStackTrace();
        }
        MinecraftServer current = MinecraftServer.getServer();
        if (current != null) current.initiateShutdown();
    }

    private void write(String name, NBTTagCompound value) throws Exception {
        try (OutputStream output = Files.newOutputStream(
            root.resolve("receipts")
                .resolve(name),
            StandardOpenOption.CREATE_NEW)) {
            CompressedStreamTools.writeCompressed(value, output);
        }
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
