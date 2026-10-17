package gtmb.v0111.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in dedicated-server API contracts. Never a real-Netty or 64k business acceptance witness. */
@Mod(modid = "gtmb_v0111_native_contracts_probe", name = "GTMB native placement contracts", version = "1",
    dependencies = "required-after:gtmb")
public final class NativeContractsProbe {
    private static final String SUITE = "placement-compatibility";
    private static final String HELPERS = "detachedReportingParts,idleAeHosts,basicMachineFacingRestore";
    private final Properties proof = new Properties();
    private File output;
    private String expectedWorld;
    private boolean enabled, done;
    private int ticks;
    private long started;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        enabled = Boolean.getBoolean("gtmb.v0111.nativeContractsOnly");
        if (!enabled) return; // Normal business addon: no subscription, file access or world mutation.
        check(FMLCommonHandler.instance().getSide().isServer(), "dedicated server side required");
        File fixture = new File(System.getProperty("gtmb.hologram.followupRoot", "")).getCanonicalFile();
        check(fixture.getPath().replace('\\', '/').endsWith("/build/hologram-packaged-v018-mui"), "owned packaged fixture");
        check(new File(".").getCanonicalFile().equals(new File(fixture, "server")), "exact owned server working directory");
        output = new File(System.getProperty("gtmb.v0111.output", "")).getCanonicalFile();
        File outputs = new File(fixture.getParentFile(), "verification/render-v0111").getCanonicalFile();
        check(output.isDirectory() && output.getParentFile().equals(outputs)
            && output.getName().matches("[A-Za-z0-9_-]{1,20}"), "fresh owned case output directory");
        check(!new File(output, "native-contracts.properties").exists(), "fresh native contracts receipt");
        expectedWorld = System.getProperty("gtmb.v0111.nativeContractsWorld", "");
        check(expectedWorld.matches("hologram-smoke-v0111-[A-Za-z0-9_-]{1,20}"), "fresh smoke world name required");
        check(!Boolean.getBoolean("gtmb.hologram.smoketest")
            && !Boolean.getBoolean("gtmb.v0111.compositeServer")
            && !Boolean.getBoolean("gtmb.v0111.compositeClient"), "only the selected contracts entry point");
        check(SUITE.equals(System.getProperty("gtmb.v0111.nativeContractsSuite", "")), "explicit placement compatibility suite required");
        started = System.nanoTime();
        proof.setProperty("scope", "Three existing placement compatibility helpers with actual Forge FakePlayer and installed FML/native APIs; full suite is not invoked");
        proof.setProperty("suite", SUITE);
        proof.setProperty("selectedHelpers", HELPERS);
        proof.setProperty("fullSuiteInvoked", "false");
        proof.setProperty("fullSuiteCoverage", "NOT_RUN; legacy all-feature suite is outside this selected compatibility run");
        proof.setProperty("realNetty64kBusinessClaim", "false");
        proof.setProperty("clientStarted", "false");
        proof.setProperty("worldName", expectedWorld);
        proof.setProperty("worldDimension", "0");
        proof.setProperty("productSha256", installedHash(Class.forName("com.miaokatze.gtmb.hologram.HologramNativePlacementSmoke"),
            "gtmb", new File(fixture, "server/mods"), "gtmb.v0111.nativeContractsProductSha256"));
        proof.setProperty("addonSha256", installedHash(NativeContractsProbe.class,
            "gtmb_v0111_native_contracts_probe", new File(fixture, "server/mods"), "gtmb.v0111.nativeContractsAddonSha256"));
        proof.setProperty("status", "WAITING");
        persist();
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return;
        Object server = null;
        Throwable failure = null;
        String failureKind = "FIXTURE_SETUP";
        try {
            server = call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C");
            noPlayers(server);
            if (++ticks < 40) return;
            done = true;
            Object world = call(server, "func_71218_a", new Class<?>[] { int.class }, 0);
            String worldName = String.valueOf(call(call(world, "func_72912_H"), "func_76065_j"));
            check(expectedWorld.equals(worldName), "actual world0 has the admitted smoke name");
            check(worldName.startsWith("hologram-smoke-"), "isolated_world_guard from the existing native suite");
            proof.setProperty("endTicksBeforeRun", String.valueOf(ticks));
            proof.setProperty("noRealPlayersBefore", "true");
            proof.setProperty("status", "RUNNING");
            failureKind = "TEST_IO";
            persist();
            failureKind = "PRODUCT_OR_ASSERTION";
            proof.setProperty("invocationStarted", "true");
            Object actor = nativeActor(world);
            Class<?> suite = Class.forName("com.miaokatze.gtmb.hologram.HologramNativePlacementSmoke");
            invokeHelper(suite, "detachedReportingParts", new Class<?>[0]);
            invokeHelper(suite, "idleAeHosts", new Class<?>[0]);
            invokeHelper(suite, "basicMachineFacingRestore", new Class<?>[] {
                Class.forName("net.minecraft.world.WorldServer"), Class.forName("net.minecraft.entity.player.EntityPlayerMP") }, world, actor);
            proof.setProperty("invocationComplete", "true");
            noPlayers(server);
            proof.setProperty("noRealPlayersAfter", "true");
            proof.setProperty("status", "PASS");
        } catch (Throwable invalid) {
            done = true;
            failure = invalid;
            while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
            proof.setProperty("status", "FAIL");
            proof.setProperty("failureKind", failureKind);
            proof.setProperty("failure", failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
            failure.printStackTrace(System.err); // Retain the complete original cause in the runner's raw stderr.
        } finally {
            if (done) {
                proof.setProperty("elapsedNanos", String.valueOf(System.nanoTime() - started));
                proof.setProperty("shutdownRequested", "true");
                try {
                    persist();
                } catch (Throwable io) {
                    proof.setProperty("status", "FAIL");
                    proof.setProperty("failureKind", "TEST_IO");
                    proof.setProperty("receiptFailure", io.toString());
                    if (failure != null) io.addSuppressed(failure);
                    io.printStackTrace(System.err);
                    try { persist(); } catch (Throwable secondary) { secondary.printStackTrace(System.err); }
                } finally {
                    try {
                        if (server == null) server = call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C");
                        call(server, "func_71263_m"); // Native initiateShutdown; normal save/stop runs afterward.
                    } catch (Throwable shutdown) { shutdown.printStackTrace(System.err); }
                }
            }
        }
    }

    private static void noPlayers(Object server) throws Exception {
        Object manager = call(server, "func_71203_ab");
        Field field = manager.getClass().getField("field_72404_b");
        check(((List<?>) field.get(manager)).isEmpty(), "no logged-in real players may enter this contracts run");
    }

    /** Reproduce the existing suite's real Forge FakePlayer setup, without invoking unrelated legacy contracts. */
    private Object nativeActor(Object world) throws Exception {
        Class<?> profileType = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(
            UUID.fromString("776dddb0-84e0-46b8-8521-9962604f8c11"), "GTMBNativePlace");
        Object actor = Class.forName("net.minecraftforge.common.util.FakePlayerFactory")
            .getMethod("get", Class.forName("net.minecraft.world.WorldServer"), profileType).invoke(null, world, profile);
        check(Class.forName("net.minecraftforge.common.util.FakePlayer").isInstance(actor), "actual Forge FakePlayer required");
        Object inventory = field(actor, "field_71071_by").get(actor);
        Class<?> stackType = Class.forName("net.minecraft.item.ItemStack");
        Object slots = Array.newInstance(stackType, 36);
        field(inventory, "field_70462_a").set(inventory, slots);
        field(inventory, "field_70461_c").setInt(inventory, 0);
        Object capabilities = field(actor, "field_71075_bZ").get(actor);
        field(capabilities, "field_75098_d").setBoolean(capabilities, false);
        field(capabilities, "field_75099_e").setBoolean(capabilities, true);
        Class<?> gameType = Class.forName("net.minecraft.world.WorldSettings$GameType");
        Object survival = call(gameType, "func_77146_a", new Class<?>[] { int.class }, 0);
        Object manager = field(actor, "field_71134_c").get(actor);
        call(manager, "func_73076_a", new Class<?>[] { gameType }, survival);
        Object projector = Class.forName("cpw.mods.fml.common.registry.GameRegistry")
            .getMethod("findItem", String.class, String.class).invoke(null, "gtmb", "neko_hologram_projector");
        check(projector != null, "actual registered projector required");
        Array.set(slots, 0, stackType.getConstructor(Class.forName("net.minecraft.item.Item")).newInstance(projector));
        check(!field(capabilities, "field_75098_d").getBoolean(capabilities)
            && Integer.valueOf(0).equals(call(call(manager, "func_73081_b"), "func_77148_a")), "native survival FakePlayer setup");
        proof.setProperty("actorSetup", "Forge FakePlayerFactory; existing suite UUID/name; 36 slots; current0; native Survival; registered projector");
        proof.setProperty("nativeActorGameType", "0");
        proof.setProperty("nativeActorCreative", "false");
        return actor;
    }

    private void invokeHelper(Class<?> suite, String name, Class<?>[] parameters, Object... values) throws Exception {
        proof.setProperty("currentHelper", name);
        proof.setProperty("helper." + name, "RUNNING");
        persist();
        Method helper = suite.getDeclaredMethod(name, parameters);
        helper.setAccessible(true);
        helper.invoke(null, values);
        proof.setProperty("helper." + name, "PASS");
        persist();
    }

    private static Field field(Object target, String name) throws Exception {
        return target.getClass().getField(name);
    }

    private static String installedHash(Class<?> type, String modId, File mods, String expectedKey) throws Exception {
        ModContainer container = Loader.instance().getIndexedModList().get(modId);
        check(container != null && container.getSource() != null, "actual FML mod source required: " + modId);
        File jar = container.getSource().getCanonicalFile();
        check(jar.isFile() && jar.getName().endsWith(".jar") && jar.getParentFile().equals(mods.getCanonicalFile()),
            "suite and addon load from owned installed JARs");
        check(type.getProtectionDomain() != null && type.getProtectionDomain().getCodeSource() != null,
            "loaded class origin required: " + type.getName());
        URL origin = type.getProtectionDomain().getCodeSource().getLocation();
        // Launch/FML may expose jar:file:...!/Some.class, whose opaque URI is not a File URI.
        // Match the actual containing archive to FML's admitted source, as in V017ProductionCutProbe.origin.
        URL archive = "jar".equals(origin.getProtocol())
            ? ((JarURLConnection) origin.openConnection()).getJarFileURL() : origin;
        check("file".equals(archive.getProtocol()), "local containing JAR URL required: " + origin);
        check(jar.equals(new File(archive.toURI()).getCanonicalFile()), "loaded class and FML mod source must agree: " + modId);
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(jar.toPath())) {
            byte[] bytes = new byte[65536];
            for (int n; (n = stream.read(bytes)) >= 0;) if (n != 0) hash.update(bytes, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : hash.digest()) hex.append(String.format("%02x", value & 255));
        check(hex.toString().equals(System.getProperty(expectedKey, "")), "installed artifact matches expected SHA-256: " + expectedKey);
        return hex.toString();
    }

    private void persist() throws IOException {
        Path target = new File(output, "native-contracts.properties").toPath();
        Path temporary = new File(output, ".native-contracts.properties.tmp").toPath();
        check(!Files.isSymbolicLink(target) && !Files.isSymbolicLink(temporary), "receipt leaves cannot redirect");
        try (FileOutputStream stream = new FileOutputStream(temporary.toFile())) {
            proof.store(stream, "native API contracts; not 64k business acceptance");
            stream.getFD().sync();
        }
        for (int attempt = 0;; attempt++) {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException transientWindows) {
                if (!windowsMoveRace(transientWindows) || attempt >= 7) throw transientWindows;
                try { Thread.sleep(25); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    transientWindows.addSuppressed(interrupted);
                    throw transientWindows;
                }
            }
        }
    }

    private static boolean windowsMoveRace(IOException failure) {
        if (!System.getProperty("os.name", "").startsWith("Windows") || !(failure instanceof FileSystemException)) return false;
        if (failure instanceof AccessDeniedException) return true;
        String reason = ((FileSystemException) failure).getReason();
        return "The process cannot access the file because it is being used by another process.".equals(reason)
            || "The process cannot access the file because it is being used by another process".equals(reason)
            || "另一个程序正在使用此文件，进程无法访问。".equals(reason);
    }

    private static Object call(Object target, String name) throws Exception {
        return call(target, name, new Class<?>[0]);
    }

    private static Object call(Object target, String name, Class<?>[] parameters, Object... values) throws Exception {
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        Method method = type.getMethod(name, parameters);
        return method.invoke(target instanceof Class<?> ? null : target, values);
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }
}
