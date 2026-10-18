package gtmb.v019.probe;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.imageio.ImageIO;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

/** Test addon, never distributed. All Minecraft/GTMB method links use production SRG reflection. */
@Mod(modid = "gtmb_v019_ctm_probe", name = "GTMB CTM native regression witness", version = "1")
public final class V019CtmRenderProbe {
    private final Properties proof = new Properties();
    private File output;
    private Object mc, ctmBlock, originalHandler, registry, raw, session, gui, firstWorld, firstConnection;
    private int originalId, phase, phaseTicks, ticks, renderFrames, nativeFrames, clientConnectionSerial;
    private boolean client, server, done, serverReady, drift, inventoryRouteMissing;
    private String ctmName;
    private BufferedImage baseline, previous, beforeSentinel, sentinelImage, restoredImage;
    private Object serverActor;
    private long started, phaseStarted, heartbeatAt;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        client = Boolean.getBoolean("gtmb.v019.ctmClient");
        server = Boolean.getBoolean("gtmb.v019.ctmServer");
        if (!client && !server) return;
        output = new File(System.getProperty("gtmb.v019.output", "")).getCanonicalFile();
        File fixture = new File(System.getProperty("gtmb.hologram.followupRoot", "")).getCanonicalFile();
        check(fixture.getPath().replace('\\', '/').endsWith("/build/hologram-packaged-v018-mui"),
            "existing owned packaged fixture required");
        check(new File(".").getCanonicalFile().equals(new File(fixture, client ? "client" : "server").getCanonicalFile()),
            "process working directory must be the exact owned fixture side");
        check(output.toPath().startsWith(new File(fixture.getParentFile(), "verification/render-v019").getCanonicalFile().toPath()),
            "evidence must stay inside owned build/verification/render-v019");
        check(output.isDirectory(), "precreated evidence directory required");
        check(!new File(output, client ? "ctm-client.properties" : "ctm-server.properties").exists(),
            "fresh receipts required");
        proof.setProperty("scope", "actual production raw renderer; diagnostic constructed raw entry, not a user world CUT; no OS input");
        proof.setProperty("pid", ManagementFactory.getRuntimeMXBean().getName());
        proof.setProperty("javaVersion", System.getProperty("java.version"));
        started = System.nanoTime();
        phaseStarted = started;
        FMLCommonHandler.instance().bus().register(this);
        if (client) MinecraftForge.EVENT_BUS.register(new WorldListener(this));
        persist("RUNNING");
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (!client || done || event.phase != TickEvent.Phase.END) return;
        try {
            check(System.nanoTime() - started < 900000000000L, "client timeout");
            Properties peer = read("ctm-server.properties");
            check(!peer.containsKey("failure"), "paired server failed: " + peer.getProperty("failure", ""));
            long phaseBudget = phase <= 1 ? 600000000000L : 180000000000L;
            check(System.nanoTime() - phaseStarted < phaseBudget, "client phase timeout " + phase);
            if (mc == null) {
                mc = call(Class.forName("net.minecraft.client.Minecraft"), "func_71410_x");
                Object settings = get(mc, "field_71474_y");
                set(settings, "field_82881_y", false);
                set(settings, "field_74320_O", 0);
                set(settings, "field_74334_X", 70f);
            }
            ticks++;
            phaseTicks++;
            Object world = get(mc, "field_71441_e");
            Object player = get(mc, "field_71439_g");
            heartbeat(world, player);
            if (world != null && player != null && get(mc, "field_71462_r") == null) {
                set(player, "field_70177_z", 180f);
                set(player, "field_70126_B", 180f);
                set(player, "field_70125_A", 0f);
                set(player, "field_70127_C", 0f);
            }
            if (phase == 0) {
                if (get(mc, "field_71462_r") == null || ticks < 30) return;
                identifyCtm();
                Properties selected = new Properties();
                selected.setProperty("block", ctmName);
                store("ctm-selected.properties", selected);
                connect();
                advance(1);
            } else if (phase == 1) {
                if (world == null || player == null || !file("ctm-fixture-ready.properties").isFile()) return;
                if (!boardLoaded(world) || phaseTicks < 100) return;
                firstWorld = world;
                firstConnection = call(mc, "func_147114_u");
                proof.setProperty("firstWorldIdentity", identity(firstWorld));
                proof.setProperty("firstConnectionIdentity", identity(firstConnection));
                snapshot("baseline");
                forceRebuild();
                advance(2);
            } else if (phase == 2 && phaseTicks > 70 && baseline != null) {
                // World renderer entry receives the exact production view transform in RenderWorldLast.
                session = construct("com.miaokatze.gtmb.client.hologram.HologramCutRawPreviewSession");
                call(session, "update", raw);
                nativeFrames = 0;
                snapshot("after-session-construction");
                advance(3);
            } else if (phase == 3 && nativeFrames >= 8 && proof.containsKey("world.nativePreviewPng")) {
                inspectSession("world");
                snapshot("after-world-preview");
                call(session, "release");
                session = null;
                snapshot("after-world-release");
                forceRebuild();
                advance(4);
            } else if (phase == 4 && phaseTicks > 70 && proof.containsKey("after-world.terrainChangedFraction")) {
                gui = construct("com.miaokatze.gtmb.client.hologram.HologramCutStorageScreen");
                call(mc, "func_147108_a", gui);
                Object owner = get(gui, "preview");
                session = construct("com.miaokatze.gtmb.client.hologram.HologramCutRawPreviewSession");
                call(session, "update", raw);
                set(owner, "raw", raw);
                set(owner, "renderer", session);
                advance(5);
            } else if (phase == 5 && phaseTicks > 60 && proof.containsKey("gui.nativePreviewPng")) {
                check(get(mc, "field_71462_r") == gui, "actual storage GUI remains visible");
                check(get(get(gui, "preview"), "renderer") == session, "actual storage widget retains the diagnostic renderer");
                inspectSession("gui");
                snapshot("after-gui-preview");
                call(mc, "func_147108_a", new Object[] { null });
                gui = null;
                // Actual screen close releases its independent browser renderer.
                call(session, "release");
                session = null;
                snapshot("after-gui-close-release");
                forceRebuild();
                advance(6);
            } else if (phase == 6 && phaseTicks > 70 && proof.containsKey("after-gui.terrainChangedFraction")) {
                Properties p = new Properties(); p.setProperty("mode", "stone");
                store("ctm-sentinel-request.properties", p);
                advance(7);
            } else if (phase == 7 && "stone".equals(read("ctm-sentinel-ack.properties").getProperty("mode"))
                && phaseTicks > 70 && sentinelImage != null) {
                Properties p = new Properties(); p.setProperty("mode", "ctm");
                store("ctm-sentinel-request.properties", p);
                advance(8);
            } else if (phase == 8 && "ctm".equals(read("ctm-sentinel-ack.properties").getProperty("mode"))
                && phaseTicks > 70 && proof.containsKey("after-sentinel-restore.terrainChangedFraction")) {
                snapshot("after-actual-section-rebuild");
                call(world, "func_72882_A");
                call(mc, "func_71403_a", new Object[] { null });
                call(mc, "func_147108_a", construct("net.minecraft.client.gui.GuiMainMenu"));
                advance(9);
            } else if (phase == 9 && world == null && phaseTicks > 30) {
                snapshot("disconnected-same-jvm");
                connect();
                advance(10);
            } else if (phase == 10 && world != null && player != null && phaseTicks > 100 && boardLoaded(world)) {
                check(world != firstWorld, "reconnect creates a new WorldClient");
                check(call(mc, "func_147114_u") != firstConnection, "reconnect creates a new NetHandler");
                proof.setProperty("reconnectedWorldIdentity", identity(world));
                proof.setProperty("reconnectedConnectionIdentity", identity(call(mc, "func_147114_u")));
                proof.setProperty("sameJvmReconnect", "true");
                snapshot("same-jvm-reconnected");
                forceRebuild();
                advance(11);
            } else if (phase == 11 && phaseTicks > 80 && proof.containsKey("after-reconnect.terrainChangedFraction")) {
                snapshot("same-jvm-reconnected-rebuilt");
                finish();
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void worldRender(float partialTicks) {
        if (!client || done || phase != 3 || session == null) return;
        try {
            call(session, "render", 7d, 80d, 7d, partialTicks);
            nativeFrames++;
        } catch (Throwable failure) { fail(failure); }
    }

    /** Kept outside the server listener's declared methods to avoid loading client event types there. */
    public static final class WorldListener {
        private final V019CtmRenderProbe owner;
        WorldListener(V019CtmRenderProbe owner) { this.owner = owner; }
        @SubscribeEvent
        public void render(RenderWorldLastEvent event) { owner.worldRender(event.partialTicks); }
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (!client || done || mc == null || event.phase != TickEvent.Phase.END) return;
        try {
            renderFrames++;
            if (phaseTicks < 50 || renderFrames % 15 != 0) return;
            if (phase == 3 && nativeFrames >= 8 && !proof.containsKey("world.nativePreviewPng")) {
                capture("world-native-preview");
                proof.setProperty("world.nativePreviewPng", screenshotName("world-native-preview"));
                persist("RUNNING");
            }
            if (phase == 5 && !proof.containsKey("gui.nativePreviewPng")) {
                capture("gui-native-preview");
                proof.setProperty("gui.nativePreviewPng", screenshotName("gui-native-preview"));
                persist("RUNNING");
            }
            if (phase == 2 && baseline == null) {
                BufferedImage image = capture("baseline-" + renderFrames);
                if (previous == null) { previous = image; return; }
                double change = difference(previous, image);
                proof.setProperty("baselineStabilityChangedFraction", String.valueOf(change));
                check(change < .01, "baseline CTM terrain ROI is stable");
                baseline = image;
                proof.setProperty("baselinePixelsObserved", "true");
            }
            // One capture after settling per checkpoint. Tick progression waits on this evidence.
            String key = phase == 4 ? "after-world" : phase == 6 ? "after-gui"
                : phase == 8 ? "after-sentinel-restore" : phase == 11 ? "after-reconnect" : "";
            if (!key.isEmpty() && !proof.containsKey(key + ".terrainChangedFraction")) {
                if (phase == 8 && !"ctm".equals(read("ctm-sentinel-ack.properties").getProperty("mode"))) return;
                BufferedImage image = capture(key);
                if (phase == 6) beforeSentinel = image;
                if (phase == 8) restoredImage = image;
                proof.setProperty(key + ".terrainChangedFraction", String.valueOf(difference(baseline, image)));
                persist("RUNNING");
            }
            if (phase == 7 && sentinelImage == null && "stone".equals(read("ctm-sentinel-ack.properties").getProperty("mode"))) {
                sentinelImage = capture("sentinel-stone");
                proof.setProperty("sentinel.terrainChangedFraction", String.valueOf(difference(baseline, sentinelImage)));
                proof.setProperty("sentinel.actualChangeFraction", String.valueOf(difference(beforeSentinel, sentinelImage)));
                persist("RUNNING");
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void identifyCtm() throws Exception {
        Object blockRegistry = staticField("net.minecraft.block.Block", "field_149771_c");
        originalId = ((Number) staticField("com.cricketcraft.chisel.api.rendering.ClientUtils", "renderCTMId")).intValue();
        Object rr = call(Class.forName("cpw.mods.fml.client.registry.RenderingRegistry"), "instance");
        registry = get(rr, "blockRenderers");
        originalHandler = ((Map<?, ?>) registry).get(originalId);
        check(originalHandler != null && originalHandler.getClass().getName().equals("team.chisel.client.render.RendererCTM"),
            "actual Chisel RendererCTM registered at baseline ID");
        for (String preferred : new String[] { "chisel:marble", "Chisel:marble", "chisel:limestone", "Chisel:limestone" }) {
            Object candidate = call(blockRegistry, "func_82594_a", preferred);
            if (candidate != null && ((Number) call(candidate, "func_149645_b")).intValue() == originalId) {
                ctmBlock = candidate; ctmName = preferred; break;
            }
        }
        if (ctmBlock == null) for (Object key : (Iterable<?>) call(blockRegistry, "func_148742_b")) {
            Object candidate = call(blockRegistry, "func_82594_a", key.toString());
            if (candidate != null && candidate.getClass().getName().equals("team.chisel.block.BlockCarvable")
                && ((Number) call(candidate, "func_149645_b")).intValue() == originalId) {
                ctmBlock = candidate; ctmName = key.toString(); break;
            }
        }
        check(ctmBlock != null, "actual registered Chisel CTM block found");
        proof.setProperty("ctmBlock", ctmName);
        proof.setProperty("originalRenderId", String.valueOf(originalId));
        proof.setProperty("originalRendererIdentity", identity(originalHandler));
        proof.setProperty("productionRawClassOrigin", String.valueOf(Class.forName(
            "com.miaokatze.gtmb.client.hologram.HologramCutRawPreviewSession").getProtectionDomain().getCodeSource().getLocation()));
        raw = construct("net.minecraft.nbt.NBTTagCompound");
        nbt(raw, "sizeX", 4); nbt(raw, "sizeY", 1); nbt(raw, "sizeZ", 1);
        nbt(raw, "id", "v019-constructed-diagnostic-raw");
        Object cells = construct("net.minecraft.nbt.NBTTagList");
        // Four common decorative forms; all are real registry blocks. Chisel is the focused regression.
        String[] blocks = { ctmName, "minecraft:stone_slab", "minecraft:glass", "minecraft:brick_stairs" };
        for (int i = 0; i < blocks.length; i++) {
            check(call(blockRegistry, "func_82594_a", blocks[i]) != null, "decorative fixture block loaded " + blocks[i]);
            Object cell = construct("net.minecraft.nbt.NBTTagCompound");
            nbt(cell, "x", i); nbt(cell, "y", 0); nbt(cell, "z", 0);
            nbt(cell, "block", blocks[i]); nbt(cell, "meta", 0);
            call(cells, "func_74742_a", cell);
        }
        call(raw, "func_74782_a", "cells", cells);
        snapshot("before-preview-construction");
    }

    private void snapshot(String label) throws Exception {
        int id = ((Number) staticField("com.cricketcraft.chisel.api.rendering.ClientUtils", "renderCTMId")).intValue();
        int blockId = ((Number) call(ctmBlock, "func_149645_b")).intValue();
        Object original = ((Map<?, ?>) registry).get(originalId), current = ((Map<?, ?>) registry).get(blockId);
        boolean unchanged = id == originalId && blockId == originalId && original == originalHandler && current == originalHandler;
        drift |= !unchanged;
        proof.setProperty(label + ".renderCTMId", String.valueOf(id));
        proof.setProperty(label + ".blockRenderType", String.valueOf(blockId));
        proof.setProperty(label + ".originalRegistryRendererIdentity", identity(original));
        proof.setProperty(label + ".currentRegistryRendererIdentity", identity(current));
        proof.setProperty(label + ".identityInvariant", String.valueOf(unchanged));
        proof.setProperty(label + ".originalHandler.shouldRender3DInInventory",
            String.valueOf(call(originalHandler, "shouldRender3DInInventory", originalId)));
        Object renderingRegistry = call(Class.forName("cpw.mods.fml.client.registry.RenderingRegistry"), "instance");
        boolean actualInventory3D = (Boolean) call(renderingRegistry, "renderItemAsFull3DBlock", blockId);
        inventoryRouteMissing |= !actualInventory3D;
        proof.setProperty(label + ".actualRegistry.renderItemAsFull3DBlock", String.valueOf(actualInventory3D));
        if (mc != null && get(mc, "field_71441_e") != null) {
            Object rg = get(mc, "field_71438_f");
            proof.setProperty(label + ".chunksDebug", String.valueOf(call(rg, "func_72735_c")));
            try {
                Object backend = call(Class.forName("com.gtnewhorizons.angelica.rendering.celeritas.CeleritasWorldRenderer"), "getInstanceOrNull");
                proof.setProperty(label + ".celeritasWorldMatches", String.valueOf(call(backend, "getWorld") == get(mc, "field_71441_e")));
                proof.setProperty(label + ".sectionManagerIdentity", identity(call(backend, "getRenderSectionManager")));
            } catch (ReflectiveOperationException missing) { proof.setProperty(label + ".celeritasDiagnostic", missing.toString()); }
        }
        persist("RUNNING");
    }

    private void inspectSession(String label) throws Exception {
        Object inspection = call(session, "inspection");
        proof.setProperty(label + ".inspection", inspection.toString());
        check(((Number) call(inspection, "func_74763_f", "frames")).longValue() >= 3,
            label + " actual raw renderer executed multiple frames");
        check(((Number) call(inspection, "func_74763_f", "failureCount")).intValue() == 0,
            label + " actual raw preview has no native failures");
        check(((Number) call(inspection, "func_74762_e", "pendingVisibleCells")).intValue() == 0,
            label + " actual raw preview completes visible geometry");
        check(((Number) call(inspection, "func_74763_f", "staticAttempts")).longValue() > 0,
            label + " actual native static renderer executed");
        check(((Number) call(inspection, "func_74763_f", "staticRendered")).longValue() > 0,
            label + " actual native static geometry compiled");
    }

    private boolean boardLoaded(Object world) throws Exception {
        Object real = call(world, "func_147439_a", 12, 81, 4);
        return real == ctmBlock;
    }

    private void forceRebuild() throws Exception {
        Object rg = get(mc, "field_71438_f");
        call(rg, "func_147585_a", 0, 79, 3, 16, 84, 14);
        proof.setProperty("forcedProductionRebuildRequests", String.valueOf(
            Integer.parseInt(proof.getProperty("forcedProductionRebuildRequests", "0")) + 1));
    }

    private BufferedImage capture(String label) throws Exception {
        int w = ((Number) get(mc, "field_71443_c")).intValue(), h = ((Number) get(mc, "field_71440_d")).intValue();
        String name = screenshotName(label);
        File data = (File) get(mc, "field_71412_D");
        File actualScreenshot = new File(new File(data, "screenshots"), name);
        check(!actualScreenshot.exists(), "fresh actual screenshot path required");
        // srg_patched_minecraft-sources.jar: five arguments (including file name) is func_148259_a;
        // func_148260_a is the distinct four-argument timestamp-named overload.
        call(Class.forName("net.minecraft.util.ScreenShotHelper"), "func_148259_a", data, name, w, h, call(mc, "func_147110_a"));
        BufferedImage image = ImageIO.read(actualScreenshot);
        check(image != null && image.getWidth() == w && image.getHeight() == h, "actual framebuffer screenshot");
        ImageIO.write(image, "png", file(name));
        proof.setProperty("terrainRoi", ".55w,.40h to .78w,.60h; static CTM wall outside overlay/HUD");
        return image;
    }

    private String screenshotName(String label) {
        return "ctm-" + output.getParentFile().getName().replaceAll("[^A-Za-z0-9_-]", "_") + "-" + label + ".png";
    }

    private static double difference(BufferedImage a, BufferedImage b) {
        if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return 1;
        int changes = 0, count = 0;
        for (int y = a.getHeight() * 40 / 100; y < a.getHeight() * 60 / 100; y++)
            for (int x = a.getWidth() * 55 / 100; x < a.getWidth() * 78 / 100; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y), error = 0;
                for (int shift = 0; shift < 24; shift += 8) error = Math.max(error, Math.abs((p >> shift & 255) - (q >> shift & 255)));
                if (error > 8) changes++;
                count++;
            }
        return changes / (double) Math.max(1, count);
    }

    private void connect() throws Exception {
        String address = System.getProperty("gtmb.v019.address", "127.0.0.1:25678");
        check(address.matches("127\\.0\\.0\\.1:[0-9]{2,5}"), "isolated loopback address required");
        Object fml = call(Class.forName("cpw.mods.fml.client.FMLClientHandler"), "instance");
        call(fml, "setupServerList");
        Object data = construct("net.minecraft.client.multiplayer.ServerData", "GTMB CTM owned fixture", address);
        call(fml, "connectToServer", get(mc, "field_71462_r"), data);
        proof.setProperty("connectionAttempts", String.valueOf(++clientConnectionSerial));
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (!server || done || event.phase != TickEvent.Phase.END) return;
        try {
            check(System.nanoTime() - started < 900000000000L, "server timeout");
            Properties peer = read("ctm-client.properties");
            check(!peer.containsKey("failure"), "paired client failed: " + peer.getProperty("failure", ""));
            Object host = call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C");
            Object world = call(host, "func_71218_a", 0);
            List<?> players = (List<?>) get(call(host, "func_71203_ab"), "field_72404_b");
            proof.setProperty("heartbeat.actualPlayerCount", String.valueOf(players.size()));
            heartbeat(world, players.isEmpty() ? null : players.get(0));
            String name = String.valueOf(call(call(world, "func_72912_H"), "func_76065_j"));
            check(name.startsWith("hologram-cut-v020-"), "owned named fixture world required");
            call(world, "func_72877_b", 6000L);
            if (!file("ctm-selected.properties").isFile()) return;
            Object br = staticField("net.minecraft.block.Block", "field_149771_c");
            Object selected = call(br, "func_82594_a", read("ctm-selected.properties").getProperty("block"));
            Object stone = call(br, "func_82594_a", "minecraft:stone");
            check(selected != null && selected.getClass().getName().startsWith("team.chisel.block."), "real Chisel fixture block");
            if (!serverReady) {
                // Production loader verifies non-EmptyChunk, requested coordinates, and distinct identities.
                // provideChunk/getChunkFromChunkCoords is deliberately not accepted as explicit loading.
                call(Class.forName("com.miaokatze.gtmb.hologram.HologramCutChunks"), "load", world, 0, 3, 17, 12);
                proof.setProperty("fixtureChunkLoader", "production HologramCutChunks.load; non-EmptyChunk/coordinates/identity verified");
                for (int x = 0; x <= 16; x++) for (int z = 3; z <= 14; z++) put(world, x, 79, z, stone);
                for (int x = 0; x <= 16; x++) for (int y = 80; y <= 83; y++) put(world, x, y, 4, x < 8 ? stone : selected);
                call(call(world, "func_72912_H"), "func_76081_a", 8, 80, 13);
                proof.setProperty("fixtureStandardSpawn", "8,80,13; WorldInfo.setSpawnPosition");
                Properties ready = new Properties(); ready.setProperty("block", read("ctm-selected.properties").getProperty("block"));
                ready.setProperty("scope", "real server blocks, actual Netty chunks; constructed raw preview is independently labeled");
                store("ctm-fixture-ready.properties", ready);
                serverReady = true;
            }
            if (!players.isEmpty() && players.get(0) != serverActor) {
                check(players.size() == 1, "single owned real player");
                serverActor = players.get(0);
                call(get(serverActor, "field_71135_a"), "func_147364_a", 8.5d, 80d, 13d, 180f, 0f);
                // This is the same chunk-watch update invoked by normal NetHandlerPlayServer player movement.
                // setPlayerLocation alone sends S08 but does not update managed X/Z or target send queues.
                call(call(host, "func_71203_ab"), "func_72358_d", serverActor);
                proof.setProperty("fixturePostTeleportChunkWatchUpdate", "standard ServerConfigurationManager.func_72358_d");
                Object inventory = get(serverActor, "field_71071_by");
                Object items = get(inventory, "field_70462_a");
                Object item = call(staticField("net.minecraft.item.Item", "field_150901_e"), "func_82594_a", "gtmb:neko_hologram_projector");
                java.lang.reflect.Array.set(items, 0, construct("net.minecraft.item.ItemStack", item));
                set(inventory, "field_70461_c", 0);
                call(get(serverActor, "field_71069_bz"), "func_75142_b");
                proof.setProperty("actualPlayerConnections", String.valueOf(Integer.parseInt(proof.getProperty("actualPlayerConnections", "0")) + 1));
            }
            Properties request = read("ctm-sentinel-request.properties"), ack = read("ctm-sentinel-ack.properties");
            String mode = request.getProperty("mode", "");
            if (!mode.isEmpty() && !mode.equals(ack.getProperty("mode"))) {
                for (int x = 10; x <= 14; x++) for (int y = 80; y <= 83; y++) put(world, x, y, 4, "stone".equals(mode) ? stone : selected);
                Properties result = new Properties(); result.setProperty("mode", mode);
                store("ctm-sentinel-ack.properties", result);
            }
            if (file("ctm-client.properties").isFile()) {
                Properties result = read("ctm-client.properties");
                if ("PASS".equals(result.getProperty("status")) || "FAIL".equals(result.getProperty("status"))) {
                    proof.setProperty("clientVerdict", result.getProperty("status"));
                    persist("COMPLETE"); done = true;
                    call(host, "func_71263_m");
                }
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private static void put(Object world, int x, int y, int z, Object block) throws Exception {
        call(world, "func_147465_d", x, y, z, block, 0, 3);
        check(call(world, "func_147439_a", x, y, z) == block, "fixture block applied");
    }

    /** Observation only: distinguish chunk delivery, GUI/death, relocation, and missing actor waits. */
    private void heartbeat(Object world, Object actor) throws Exception {
        long now = System.nanoTime();
        if (heartbeatAt != 0 && now - heartbeatAt < 2000000000L) return;
        heartbeatAt = now;
        proof.setProperty("heartbeat.phase", String.valueOf(phase));
        proof.setProperty("heartbeat.phaseMillis", String.valueOf((now - phaseStarted) / 1000000L));
        proof.setProperty("heartbeat.elapsedMillis", String.valueOf((now - started) / 1000000L));
        proof.setProperty("heartbeat.world", identity(world));
        proof.setProperty("heartbeat.actor", identity(actor));
        proof.setProperty("heartbeat.serverReady", String.valueOf(serverReady));
        if (client && mc != null) {
            Object current = get(mc, "field_71462_r");
            proof.setProperty("heartbeat.screen", current == null ? "world" : current.getClass().getName());
            proof.setProperty("heartbeat.renderFrames", String.valueOf(renderFrames));
        }
        if (actor != null) {
            for (String[] field : new String[][] {
                { "x", "field_70165_t" }, { "y", "field_70163_u" }, { "z", "field_70161_v" },
                { "bbox", "field_70121_D" }, { "dead", "field_70128_L" }, { "dimension", "field_71093_bK" },
                { "onGround", "field_70122_E" } }) {
                try { proof.setProperty("heartbeat.actor." + field[0], String.valueOf(get(actor, field[1]))); }
                catch (ReflectiveOperationException unavailable) { proof.setProperty("heartbeat.actor." + field[0], unavailable.toString()); }
            }
            try { proof.setProperty("heartbeat.actor.health", String.valueOf(call(actor, "func_110143_aJ"))); }
            catch (ReflectiveOperationException unavailable) { proof.setProperty("heartbeat.actor.health", unavailable.toString()); }
            if (server) {
                try {
                    proof.setProperty("heartbeat.actor.managedX", String.valueOf(get(actor, "field_71131_d")));
                    proof.setProperty("heartbeat.actor.managedZ", String.valueOf(get(actor, "field_71132_e")));
                    List<?> queue = (List<?>) get(actor, "field_71129_f");
                    proof.setProperty("heartbeat.actor.chunkSendQueueSize", String.valueOf(queue.size()));
                    boolean queued00 = false, queued10 = false;
                    for (Object pair : queue) if (pair != null) {
                        int x = ((Number) get(pair, "field_77276_a")).intValue();
                        int z = ((Number) get(pair, "field_77275_b")).intValue();
                        queued00 |= x == 0 && z == 0;
                        queued10 |= x == 1 && z == 0;
                    }
                    proof.setProperty("heartbeat.actor.chunk00Queued", String.valueOf(queued00));
                    proof.setProperty("heartbeat.actor.chunk10Queued", String.valueOf(queued10));
                    Object playerManager = call(world, "func_73040_p");
                    proof.setProperty("heartbeat.actor.playerManagerContainsActor",
                        String.valueOf(((List<?>) get(playerManager, "field_72699_b")).contains(actor)));
                    // Vanilla predicate means watched and no longer queued, not an invented client receipt ACK.
                    proof.setProperty("heartbeat.actor.chunk00WatchedAndNotQueued",
                        String.valueOf(call(playerManager, "func_72694_a", actor, 0, 0)));
                    proof.setProperty("heartbeat.actor.chunk10WatchedAndNotQueued",
                        String.valueOf(call(playerManager, "func_72694_a", actor, 1, 0)));
                } catch (ReflectiveOperationException unavailable) {
                    proof.setProperty("heartbeat.actor.chunkWatchDiagnostic", unavailable.toString());
                }
            }
        }
        if (world != null) {
            try {
                Object block = call(world, "func_147439_a", 12, 81, 4);
                proof.setProperty("heartbeat.board.block", identity(block));
                proof.setProperty("heartbeat.board.expectedBlock", identity(ctmBlock));
                proof.setProperty("heartbeat.board.matchesClientRegistry", String.valueOf(block == ctmBlock));
                Object chunk = call(world, "func_72964_e", 0, 0);
                proof.setProperty("heartbeat.board.chunk", identity(chunk));
                proof.setProperty("heartbeat.board.chunkX", String.valueOf(get(chunk, "field_76635_g")));
                proof.setProperty("heartbeat.board.chunkZ", String.valueOf(get(chunk, "field_76647_h")));
                proof.setProperty("heartbeat.board.chunkLoaded", String.valueOf(get(chunk, "field_76636_d")));
                proof.setProperty("heartbeat.board.chunkReadyForNormalBulkSend", String.valueOf(call(chunk, "func_150802_k")));
            } catch (ReflectiveOperationException unavailable) { proof.setProperty("heartbeat.board.diagnostic", unavailable.toString()); }
        }
        System.out.println("[GTMB-CTM-NATIVE] HEARTBEAT role=" + (client ? "client" : "server")
            + " phase=" + phase + " screen=" + proof.getProperty("heartbeat.screen", "server")
            + " world=" + proof.getProperty("heartbeat.world")
            + " actor=" + proof.getProperty("heartbeat.actor")
            + " xyz=" + proof.getProperty("heartbeat.actor.x", "?") + ","
            + proof.getProperty("heartbeat.actor.y", "?") + "," + proof.getProperty("heartbeat.actor.z", "?")
            + " health=" + proof.getProperty("heartbeat.actor.health", "?")
            + " dead=" + proof.getProperty("heartbeat.actor.dead", "?")
            + " bbox=" + proof.getProperty("heartbeat.actor.bbox", "?")
            + " dimension=" + proof.getProperty("heartbeat.actor.dimension", "?")
            + " board=" + proof.getProperty("heartbeat.board.block", "?")
            + " chunk=" + proof.getProperty("heartbeat.board.chunk", "?")
            + " chunkLoaded=" + proof.getProperty("heartbeat.board.chunkLoaded", "?")
            + " bulkReady=" + proof.getProperty("heartbeat.board.chunkReadyForNormalBulkSend", "?")
            + " managedXZ=" + proof.getProperty("heartbeat.actor.managedX", "client") + ","
            + proof.getProperty("heartbeat.actor.managedZ", "client")
            + " sendQueue=" + proof.getProperty("heartbeat.actor.chunkSendQueueSize", "client")
            + " queued00=" + proof.getProperty("heartbeat.actor.chunk00Queued", "client")
            + " watchedNotQueued00=" + proof.getProperty("heartbeat.actor.chunk00WatchedAndNotQueued", "client")
            + " connections=" + proof.getProperty("actualPlayerConnections", "client"));
        // Frequent heartbeat observations stay in the log. Latest fields join the next phase/final receipt.
        // External Windows readers must still open receipt files with FILE_SHARE_DELETE for atomic replacement.
    }

    private void finish() throws Exception {
        check(baseline != null && beforeSentinel != null && sentinelImage != null && restoredImage != null,
            "actual terrain captures completed");
        check(Double.parseDouble(proof.getProperty("sentinel.actualChangeFraction", "0")) > .05,
            "actual server sentinel changes visible terrain, proving real section rebuild");
        proof.setProperty("sentinel.actualRestoreChangeFraction", String.valueOf(difference(sentinelImage, restoredImage)));
        check(difference(sentinelImage, restoredImage) > .05, "actual server sentinel restoration visibly rebuilt");
        boolean pixels = true;
        for (String key : new String[] { "after-world", "after-gui", "after-sentinel-restore", "after-reconnect" }) {
            check(proof.containsKey(key + ".terrainChangedFraction"), "required terrain capture " + key);
            pixels &= Double.parseDouble(proof.getProperty(key + ".terrainChangedFraction")) < .02;
        }
        proof.setProperty("actualSectionRebuildWitness", "true");
        proof.setProperty("terrainPixelsMatchBaseline", String.valueOf(pixels));
        proof.setProperty("renderIdOrRegistryIdentityDrift", String.valueOf(drift));
        boolean positive = !drift && pixels && !inventoryRouteMissing;
        String expected = System.getProperty("gtmb.v019.expect", "positive");
        check(expected.equals("positive") || expected.equals("negative"), "expect must be positive or negative");
        proof.setProperty("productVerdict", positive ? "PASS" : "FAIL");
        proof.setProperty("actualRegistryInventory3DAtEveryCheckpoint", String.valueOf(!inventoryRouteMissing));
        proof.setProperty("inventoryEvidenceScope", "actual Forge registry routing predicate; no inventory screenshot pixel assertion");
        proof.setProperty("negativeReproduction", String.valueOf(drift));
        proof.setProperty("expectation", expected);
        proof.setProperty("scenarioVerdict", expected.equals("negative") && drift ? "NEGATIVE_REPRODUCED"
            : expected.equals("positive") && positive ? "POSITIVE_VERIFIED" : "EXPECTATION_FAILED");
        persist(positive ? "PASS" : "FAIL");
        done = true;
        call(mc, "func_71400_g");
    }

    private void advance(int next) throws Exception {
        phase = next; phaseTicks = 0; renderFrames = 0;
        phaseStarted = System.nanoTime();
        proof.setProperty("phase", String.valueOf(phase));
        System.out.println("[GTMB-CTM-NATIVE] phase=" + phase);
        persist("RUNNING");
    }

    private void fail(Throwable failure) {
        failure.printStackTrace();
        try {
            proof.setProperty("failure", failure.toString());
            proof.setProperty("phase", String.valueOf(phase));
            proof.setProperty("productVerdict", "INCONCLUSIVE");
            proof.setProperty("renderIdOrRegistryIdentityDrift", String.valueOf(drift));
            persist("FAIL");
        } catch (Exception evidence) { evidence.printStackTrace(); }
        done = true;
        try {
            if (client && mc != null) call(mc, "func_71400_g");
            if (server) call(call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C"), "func_71263_m");
        } catch (Exception shutdown) { shutdown.printStackTrace(); }
    }

    private File file(String name) { return new File(output, name); }
    private Properties read(String name) throws Exception {
        Properties result = new Properties();
        if (file(name).isFile()) try (FileInputStream in = new FileInputStream(file(name))) { result.load(in); }
        return result;
    }
    private void persist(String status) throws Exception {
        proof.setProperty("status", status);
        store(client ? "ctm-client.properties" : "ctm-server.properties", proof);
    }
    private void store(String name, Properties p) throws Exception {
        File temporary = file(name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) { p.store(out, "GTMB native CTM witness"); }
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                java.nio.file.Files.move(temporary.toPath(), file(name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (java.nio.file.FileSystemException temporarilyUnavailable) {
                // Windows observation tools can briefly deny delete sharing. Never publish partial receipts or
                // swallow the final failure: retry only filesystem move failures for at most ninety milliseconds.
                if (attempt == 9) throw temporarilyUnavailable;
                Thread.sleep(10L);
            }
        }
    }
    private static String identity(Object o) { return o == null ? "null" : o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o)); }
    private static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private static Object staticField(String type, String name) throws Exception { return get(Class.forName(type), name); }
    private static Field member(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Object get(Object o, String name) throws Exception { return member(o instanceof Class ? (Class<?>) o : o.getClass(), name).get(o instanceof Class ? null : o); }
    private static void set(Object o, String name, Object value) throws Exception { member(o.getClass(), name).set(o, value); }
    private static Object construct(String type, Object... args) throws Exception {
        for (Constructor<?> c : Class.forName(type).getDeclaredConstructors()) if (compatible(c.getParameterTypes(), args)) {
            c.setAccessible(true); return c.newInstance(args);
        }
        throw new NoSuchMethodException(type + " constructor " + args.length);
    }
    private static Object call(Object object, String name, Object... args) throws Exception {
        Class<?> type = object instanceof Class ? (Class<?>) object : object.getClass();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method m : c.getDeclaredMethods())
            if (m.getName().equals(name) && compatible(m.getParameterTypes(), args)) {
                m.setAccessible(true); return m.invoke(object instanceof Class ? null : object, args);
            }
        throw new NoSuchMethodException(type.getName() + "." + name + " args=" + args.length);
    }
    private static boolean compatible(Class<?>[] types, Object[] values) {
        if (types.length != values.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (values[i] == null) { if (types[i].isPrimitive()) return false; continue; }
            Class<?> t = types[i];
            if (t.isPrimitive()) t = t == int.class ? Integer.class : t == long.class ? Long.class : t == float.class ? Float.class
                : t == double.class ? Double.class : t == boolean.class ? Boolean.class : t;
            if (!t.isInstance(values[i])) return false;
        }
        return true;
    }
    private static void nbt(Object compound, String key, Object value) throws Exception {
        call(compound, value instanceof Integer ? "func_74768_a" : "func_74778_a", key, value);
    }
}
