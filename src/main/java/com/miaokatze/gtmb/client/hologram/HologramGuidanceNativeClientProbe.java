package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;

import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramV011Evidence;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Fresh native client: real pointer handlers, inventory C0E, normal Netty ACKs, and END framebuffer pixels. */
public final class HologramGuidanceNativeClientProbe {

    private interface Action {

        boolean run(Minecraft mc, HologramScreen screen, NBTTagCompound ui) throws Exception;
    }

    private static final class Step {

        final String label;
        final Action action;

        Step(String label, Action action) {
            this.label = label;
            this.action = action;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private final NBTTagList actions = new NBTTagList(), frames = new NBTTagList();
    private final HologramActualUsePress press = new HologramActualUsePress();
    private final long started = System.nanoTime();
    private final long startupBudgetNanos = Math
        .max(60L, Math.min(3600L, Long.getLong("gtmb.hologram.nativeStartupSeconds", 1800L))) * 1000000000L;
    private long executionStarted;
    private long stepStarted = started, renderedGeneration = -1, rejectedAfterSequence;
    private HologramScreen renderedScreen, captureScreen;
    private HologramState renderedState;
    private int step, pointerActions;
    private boolean done, imported;
    private volatile String shareId, selfId;
    private volatile Throwable importFailure;
    private NBTTagCompound capture;

    public static void install() {
        if (Boolean.getBoolean("gtmb.hologram.guidanceNativeClient")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramGuidanceNativeClientProbe());
    }

    private HologramGuidanceNativeClientProbe() {
        add("connect-isolated-loopback", (mc, screen, ui) -> {
            if (!(mc.currentScreen instanceof GuiMainMenu) && !(mc.currentScreen != null && mc.currentScreen.getClass()
                .getName()
                .equals("lumien.custommainmenu.gui.GuiCustom"))) return false;
            require(
                !Boolean.getBoolean("gtmb.hologram.sulfurLineClient")
                    && !Boolean.getBoolean("gtmb.hologram.realInteractionSmoke"),
                "exclusive native guidance driver");
            String address = System.getProperty("gtmb.hologram.guidanceNativeAddress", "");
            require(address.matches("127\\.0\\.0\\.1:[0-9]{2,5}"), "explicit isolated loopback address");
            require(
                !Files.exists(
                    HologramV011Evidence.directory()
                        .resolve("guidance-client-final.nbt")),
                "fresh guidance receipts");
            mc.gameSettings.pauseOnLostFocus = false;
            cpw.mods.fml.client.FMLClientHandler.instance()
                .setupServerList();
            cpw.mods.fml.client.FMLClientHandler.instance()
                .connectToServer(mc.currentScreen, new ServerData("GTMB v0.1.3 guidance", address));
            return true;
        });
        add("native-controller-pointer-use", (mc, screen, ui) -> {
            NBTTagCompound ready = HologramV011Evidence.read("guidance-server-ready.nbt");
            if (mc.theWorld == null || mc.thePlayer == null || ready == null) return false;
            require(
                ready.getString("actor")
                    .equals(
                        mc.thePlayer.getUniqueID()
                            .toString()),
                "actual server player");
            if (mc.thePlayer.inventory.mainInventory[1] == null) return false;
            executionStarted = System.nanoTime();
            mc.thePlayer.inventory.currentItem = 1;
            useBlock(mc, 48, 80, 72, 3);
            return true;
        });
        add("native-terminal-server-proof", (mc, screen, ui) -> {
            if (HologramV011Evidence.read("guidance-terminal-bound.nbt") == null) {
                if (mc.theWorld.getTotalWorldTime() % 40 == 0) useBlock(mc, 48, 80, 72, 3);
                return false;
            }
            if (mc.thePlayer.inventory.mainInventory[35] == null) return false;
            mc.thePlayer.inventory.currentItem = 0;
            return true;
        });
        add("import-exact-existing-saved-pairs", (mc, screen, ui) -> {
            Thread worker = new Thread(() -> {
                try {
                    HologramClientLibrary library = new HologramClientLibrary(mc.mcDataDir.toPath());
                    String shared = importPair(library, "guidance-source-share.nbt");
                    String own = importPair(library, "guidance-source-self.nbt");
                    shareId = shared;
                    selfId = own;
                } catch (Throwable failure) {
                    importFailure = failure;
                }
            }, "GTMB native guidance saved-pair import");
            worker.setDaemon(true);
            worker.start();
            return true;
        });
        add("native-projector-open", (mc, screen, ui) -> {
            if (importFailure != null)
                throw new IllegalStateException("actual saved pair import failed", importFailure);
            if (shareId == null || selfId == null) return false;
            imported = true;
            openProjector(mc);
            return true;
        });
        screen("projector-open-normal-netty-ack", (mc, screen, ui) -> idle(screen, ui) && ui.getBoolean("dedicated"));
        browseAndPlace(false);
        screen("shared-review-guidance", (mc, screen, ui) -> {
            if (!review(screen, ui)) return false;
            require(
                groups().tagCount() == 2 && ui.getInteger("nextNetworkGroup") == 0,
                "two genuine shared groups await binding");
            require(accent(ui, 196) != 0 && !enabled(ui, 55), "binding entry highlighted before construction");
            assertModalContains(ui, 196);
            return true;
        });
        shot("guidance-shared-confirmation", 196);
        click("open-shared-network-drawer", 196);
        screen("current-group-and-native-terminal-highlight", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !ui.getBoolean("networkDrawer")) return false;
            require(
                ui.getInteger("networkGroupIndex") == 0 && accent(ui, 60000) != 0,
                "first pending group visibly selected");
            require(
                enabled(ui, 61035) && accent(ui, 61035) != 0,
                "server verified native bound terminal visibly highlighted");
            require(!enabled(ui, 197), "incomplete groups cannot confirm");
            return true;
        });
        shot("guidance-first-group-terminal", 60000, 61035);
        screen("actual-native-inventory-drop-then-stale-pointer-bind", (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            require(
                enabled(ui, 61035) && mc.thePlayer.inventory.mainInventory[35] != null,
                "real old terminal cell before inventory race");
            rejectedAfterSequence = data().getLong("uiSequence");
            mc.playerController.windowClick(mc.thePlayer.inventoryContainer.windowId, 35, 1, 4, mc.thePlayer);
            pointer(screen, 61035);
            return true;
        });
        screen("rejected-actual-netty-ack-does-not-advance", (mc, screen, ui) -> {
            if (!idle(screen, ui) || data().getLong("uiSequence") <= rejectedAfterSequence) return false;
            require(
                data().getString("status")
                    .contains("蓝图操作失败"),
                "actual rejected bind ACK");
            require(
                ui.getBoolean("networkDrawer") && ui.getInteger("networkGroupIndex") == 0,
                "failure retains same drawer group");
            require(
                !groups().getCompoundTagAt(0)
                    .getBoolean("bound")
                    && !groups().getCompoundTagAt(1)
                        .getBoolean("bound"),
                "failed selection binds neither group");
            require(!enabled(ui, 197), "failed binding cannot authorize confirmation");
            return true;
        });
        shot("guidance-rejected-bind-stays-first", 60000);
        click("return-after-rejected-binding", 201);
        screen(
            "real-review-restored-after-failure",
            (mc, screen, ui) -> review(screen, ui) && !ui.getBoolean("networkDrawer"));
        click("reopen-native-inventory-picker", 196);
        screen(
            "native-spare-terminal-still-available",
            (mc, screen, ui) -> idle(screen, ui) && ui.getBoolean("networkDrawer") && enabled(ui, 61001));
        click("bind-first-group-real-terminal-pointer", 61001);
        screen("successful-ack-advances-to-next-unbound-group", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !groups().getCompoundTagAt(0)
                .getBoolean("bound")) return false;
            require(
                !groups().getCompoundTagAt(1)
                    .getBoolean("bound") && ui.getInteger("networkGroupIndex") == 1,
                "successful ACK advances exactly to second group");
            require(
                accent(ui, 60001) != 0 && accent(ui, 61001) != 0 && !enabled(ui, 197),
                "second group and carried terminal highlighted, confirm still gated");
            return true;
        });
        shot("guidance-success-advances-second", 60001, 61001);
        click("bind-second-group-real-terminal-pointer", 61001);
        screen("all-success-ack-highlights-confirm", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !ui.getBoolean("networkGroupsReady")) return false;
            require(
                groups().getCompoundTagAt(0)
                    .getBoolean("bound")
                    && groups().getCompoundTagAt(1)
                        .getBoolean("bound"),
                "both independent group ACKs present");
            require(enabled(ui, 197) && accent(ui, 197) == 0x80efaa, "all-success confirm visibly green");
            return true;
        });
        shot("guidance-all-success-confirm-green", 197);
        click("native-confirm-all-groups-return-to-placement", 197);
        screen("normal-confirmation-preserved-after-binding", (mc, screen, ui) -> {
            if (!review(screen, ui) || ui.getBoolean("networkDrawer")) return false;
            require(
                ui.getBoolean("networkGroupsReady") && enabled(ui, 55),
                "normal placement confirmation is now authorized");
            return true;
        });
        shot("guidance-bound-placement-confirmation", 55);
        click("cancel-share-placement-through-real-control", 54);
        add("drop-final-terminal-through-native-inventory", (mc, screen, ui) -> {
            if (screen != null && screen.smokePending()) return false;
            mc.displayGuiScreen(null);
            require(
                mc.thePlayer.inventory.mainInventory[1] != null,
                "real spare terminal before private policy scenario");
            mc.playerController.windowClick(mc.thePlayer.inventoryContainer.windowId, 37, 1, 4, mc.thePlayer);
            NBTTagCompound request = new NBTTagCompound();
            request.setBoolean("requested", true);
            HologramV011Evidence.write("guidance-private-no-terminal-request.nbt", request);
            return true;
        });
        add("server-confirms-no-carried-terminal", (mc, screen, ui) -> {
            NBTTagCompound proof = HologramV011Evidence.read("guidance-private-no-terminal-ack.nbt");
            if (proof == null) return false;
            require(proof.getBoolean("noQuantumTerminal"), "actual private inventory has no terminal");
            openProjector(mc);
            return true;
        });
        screen("normal-private-projector-open", (mc, screen, ui) -> idle(screen, ui));
        browseAndPlace(true);
        screen("private-preserve-is-not-forced-to-bind", (mc, screen, ui) -> {
            if (!review(screen, ui)) return false;
            require(
                groups().tagCount() > 0 && ui.getInteger("nextNetworkGroup") == -1,
                "private original anchor has no required terminal workflow");
            require(
                accent(ui, 196) == 0 && !enabled(ui, 55),
                "foreign-world private anchor remains blocked without forced bind highlight");
            for (int i = 0; i < groups().tagCount(); i++) require(
                !groups().getCompoundTagAt(i)
                    .getBoolean("terminalBinding"),
                "private groups preserve original network");
            return true;
        });
        shot("guidance-private-preserve-no-forced-binding");
        click("explicitly-inspect-private-original-network", 196);
        screen("private-drawer-cannot-select-new-terminal", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !ui.getBoolean("networkDrawer")) return false;
            for (int slot = 0; slot < 36; slot++)
                require(!enabled(ui, 61000 + slot), "private drawer never requires terminal selection");
            require(
                !ui.getBoolean("pendingNetworkBinding") && accent(ui, 197) == 0,
                "private unavailable anchor cannot gain all-success highlight");
            return true;
        });
        shot("guidance-private-drawer-preserves-original");
        add("write-actual-guidance-client-final", (mc, screen, ui) -> {
            NBTTagCompound result = new NBTTagCompound();
            result.setBoolean("passed", true);
            result.setBoolean("actualNettyClient", true);
            result.setBoolean("syntheticSnapshotsUsed", false);
            result.setBoolean("nativePairImport", imported);
            result.setInteger("pointerActions", pointerActions);
            result.setString(
                "actor",
                mc.thePlayer.getUniqueID()
                    .toString());
            result.setString("releaseVersion", "0.1.3");
            result.setTag("actions", actions.copy());
            result.setTag("frames", frames.copy());
            result.setTag("actualSnapshot", data().copy());
            result.setTag("actualUi", ui.copy());
            HologramV011Evidence.write("guidance-client-final.nbt", result);
            HologramV011Evidence
                .marker("guidance-client-pass.txt", "PASS native guidance pointer/ACK/END pixel checks");
            done = true;
            // Keep the connection alive until the independently observing server saves and closes the world.
            return true;
        });
    }

    private void browseAndPlace(boolean own) {
        String prefix = own ? "private" : "shared";
        screen(prefix + "-open-saved-blueprints-tab", (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            pointer(screen, 149);
            return true;
        });
        screen(prefix + "-select-genuine-saved-library-row", (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            String id = own ? selfId : shareId;
            NBTTagList entries = ui.getTagList("entries", 10);
            for (int i = 0; i < entries.tagCount(); i++) if (id.equals(
                entries.getCompoundTagAt(i)
                    .getString("id"))) {
                        pointer(screen, 14000 + i);
                        return true;
                    }
            return false;
        });
        screen(prefix + "-saved-browse-has-no-author-workflow", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !(own ? selfId : shareId).equals(data().getString("libraryLocalId"))) return false;
            require(
                data().getBoolean("blueprintPreview") && ui.getBoolean("selectedSavedBlueprint"),
                "actual saved blueprint preview selected");
            require(
                !ui.getBoolean("authorWorkspace") && !ui.getBoolean("processingFlow") && control(ui, 220) == null,
                "saved selection has no author processing entry");
            require(enabled(ui, 141) && accent(ui, 141) == 0x80efaa, "saved blueprint placement is visibly green");
            return true;
        });
        shot("guidance-" + prefix + "-saved-browse-green-place", 141);
        click(prefix + "-native-place-saved-blueprint", 141);
        add(
            prefix + "-moving-preview-ack",
            (mc, screen, ui) -> mc.currentScreen == null && data().getBoolean("virtualPlacement")
                && !data().getBoolean("virtualLocked"));
        add(prefix + "-first-world-right-lock", (mc, screen, ui) -> {
            press.begin(mc, true);
            useBlock(mc, 64, 79, 64, 1);
            return true;
        });
        add(prefix + "-actual-world-lock-ack", (mc, screen, ui) -> {
            if (!data().getBoolean("virtualLocked")) return false;
            require(
                mc.currentScreen == null && data().getInteger("x") == 64
                    && data().getInteger("y") == 80
                    && data().getInteger("z") == 64,
                "native first right locks physical target");
            return true;
        });
        add(prefix + "-second-world-right-opens-review", (mc, screen, ui) -> {
            openProjector(mc);
            return true;
        });
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (done) {
            if (mc.theWorld == null) mc.shutdown();
            return;
        }
        try {
            if (mc.theWorld != null && press.tick(event, mc)) return;
            if (event.phase != TickEvent.Phase.END || capture != null) return;
            long now = System.nanoTime();
            if (step <= 1) {
                require(now - started < startupBudgetNanos, "guidance menu/world startup timeout");
            } else {
                require(now - executionStarted < 600000000000L, "guidance native client exceeded ten minutes");
                require(now - stepStarted < 90000000000L, "native step timeout: " + steps.get(step).label);
            }
            require(
                !Files.exists(
                    HologramV011Evidence.directory()
                        .resolve("guidance-server-fail.txt")),
                "paired server did not fail");
            HologramScreen screen = mc.currentScreen instanceof HologramScreen ? (HologramScreen) mc.currentScreen
                : null;
            NBTTagCompound ui = screen == null ? null : screen.smokeLibraryUiState();
            if (screen != null && (renderedScreen != screen || renderedState != HologramClient.state
                || renderedGeneration != ui.getLong("renderGeneration"))) return;
            if (!steps.get(step).action.run(mc, screen, ui)) return;
            NBTTagCompound action = new NBTTagCompound();
            action.setString("label", steps.get(step).label);
            action.setLong("clientWorldTick", mc.theWorld == null ? -1 : mc.theWorld.getTotalWorldTime());
            action.setTag("actualSnapshot", data().copy());
            if (ui != null) action.setTag("actualUi", ui.copy());
            actions.appendTag(action);
            System.out.println("[GTMB-GUIDANCE-NATIVE] step=" + step + " " + steps.get(step).label);
            step++;
            stepStarted = System.nanoTime();
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            renderedScreen = mc.currentScreen instanceof HologramScreen ? (HologramScreen) mc.currentScreen : null;
            renderedState = HologramClient.state;
            renderedGeneration = renderedScreen == null ? -1
                : renderedScreen.smokeLibraryUiState()
                    .getLong("renderGeneration");
            if (capture == null || renderedScreen != captureScreen
                || renderedGeneration <= capture.getLong("afterGeneration")) return;
            NBTTagCompound ui = captureScreen.smokeLibraryUiState();
            require(
                capture.getString("session")
                    .equals(HologramClient.state.session)
                    && capture.getLong("uiSequence") == data().getLong("uiSequence"),
                "END frame retains exact actual ACK state");
            String name = capture.getString("label") + ".png";
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            Path source = mc.mcDataDir.toPath()
                .resolve("screenshots")
                .resolve(name);
            Path target = HologramV011Evidence.directory()
                .resolve(name);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            BufferedImage png = ImageIO.read(target.toFile());
            require(
                png != null && png.getWidth() == mc.displayWidth && png.getHeight() == mc.displayHeight,
                "native END framebuffer PNG");
            NBTTagCompound frame = (NBTTagCompound) capture.copy();
            frame.setBoolean("passed", true);
            frame.setBoolean("actualFrame", true);
            frame.setBoolean("actualNettyClient", true);
            frame.setBoolean("syntheticSnapshotsUsed", false);
            frame.setString("capturePhase", "RenderTick.END");
            frame.setString("png", name);
            frame.setString("pngSha256", hash(Files.readAllBytes(target)));
            frame.setTag("actualUi", ui.copy());
            frame.setTag("actualSnapshot", data().copy());
            frame.setLong("actualRenderGeneration", renderedGeneration);
            frame.setTag("accentPixels", accentPixels(mc, captureScreen, ui, png, capture.getIntArray("accentIds")));
            HologramV011Evidence.write(capture.getString("label") + ".nbt", frame);
            frames.appendTag(new NBTTagString(capture.getString("label") + ".nbt"));
            capture = null;
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    private NBTTagList accentPixels(Minecraft mc, HologramScreen screen, NBTTagCompound ui, BufferedImage png,
        int[] ids) throws Exception {
        NBTTagList pixels = new NBTTagList();
        float scale = (Float) field(screen, "scale");
        int ox = (Integer) field(screen, "originX"), oy = (Integer) field(screen, "originY");
        for (int id : ids) {
            NBTTagCompound c = control(ui, id);
            require(
                c != null && c.getBoolean("enabled") && c.getInteger("accent") != 0,
                "real accented enabled control " + id);
            int rgb = c.getInteger("accent");
            double sx = (double) mc.displayWidth / screen.width, sy = (double) mc.displayHeight / screen.height;
            int x0 = (int) Math.floor((ox + (c.getInteger("x") + 2) * scale) * sx);
            int x1 = (int) Math.ceil((ox + (c.getInteger("x") + c.getInteger("w") - 2) * scale) * sx);
            int y0 = (int) Math.floor((oy + c.getInteger("y") * scale) * sy) - 2;
            int y1 = (int) Math.ceil((oy + (c.getInteger("y") + 1) * scale) * sy) + 2;
            int matching = 0;
            for (int y = Math.max(0, y0); y < Math.min(png.getHeight(), y1); y++)
                for (int x = Math.max(0, x0); x < Math.min(png.getWidth(), x1); x++)
                    if ((png.getRGB(x, y) & 0xffffff) == rgb) matching++;
            require(
                matching >= Math.max(2, (x1 - x0) / 4),
                "actual PNG retains visible accent border for control " + id + ": " + matching);
            NBTTagCompound proof = new NBTTagCompound();
            proof.setInteger("control", id);
            proof.setInteger("rgb", rgb);
            proof.setInteger("matchingPixels", matching);
            proof.setIntArray("pixelRegion", new int[] { x0, y0, x1, y1 });
            pixels.appendTag(proof);
        }
        return pixels;
    }

    private static String importPair(HologramClientLibrary library, String receipt) throws Exception {
        NBTTagCompound source = HologramV011Evidence.read(receipt);
        require(
            source != null && source.getBoolean("passed") && "SOURCE_COMPLETE".equals(source.getString("phase")),
            "actual completed historical author transfer");
        byte[] json = source.getByteArray("exportedJson"), png = source.getByteArray("exportedPng");
        require(
            hash(json).equals(source.getString("exportedSha256"))
                && hash(png).equals(source.getString("exportedPngSha256")),
            "exact historical JSON/PNG bytes");
        String id = library.save(json, png, "");
        HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.decode(library.artifact(id));
        require(
            Arrays.equals(json, actual.json) && Arrays.equals(png, actual.png),
            "native disk import retains exact saved pair");
        return id;
    }

    private void add(String label, Action action) {
        steps.add(new Step(label, action));
    }

    private void screen(String label, Action action) {
        add(label, (mc, screen, ui) -> screen != null && action.run(mc, screen, ui));
    }

    private void click(String label, int id) {
        screen(label, (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            require(enabled(ui, id), "actual visible enabled pointer target " + id);
            pointer(screen, id);
            return true;
        });
    }

    private void shot(String label, int... accentIds) {
        screen(label, (mc, screen, ui) -> {
            require(capture == null, "single END framebuffer request");
            captureScreen = screen;
            capture = new NBTTagCompound();
            capture.setString("label", label);
            capture.setString("session", HologramClient.state.session);
            capture.setLong("uiSequence", data().getLong("uiSequence"));
            capture.setLong("afterGeneration", ui.getLong("renderGeneration"));
            capture.setIntArray("accentIds", accentIds);
            return true;
        });
    }

    private void pointer(HologramScreen screen, int id) {
        int[] point = screen.smokeControlPoint(id);
        HologramMuiProbeInput.click(screen, point[0], point[1], 0);
        pointerActions++;
    }

    private void openProjector(Minecraft mc) {
        mc.displayGuiScreen(null);
        mc.thePlayer.inventory.currentItem = 0;
        mc.objectMouseOver = null;
        press.begin(mc, false);
        mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
        pointerActions++;
    }

    private void useBlock(Minecraft mc, int x, int y, int z, int side) {
        Vec3 hit = Vec3.createVectorHelper(x + .5, y + .5, z + .5);
        mc.objectMouseOver = new MovingObjectPosition(x, y, z, side, hit);
        mc.playerController
            .onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem(), x, y, z, side, hit);
        pointerActions++;
    }

    private static boolean idle(HologramScreen screen, NBTTagCompound ui) {
        return !screen.smokePending() && !ui.getBoolean("busy");
    }

    private static boolean review(HologramScreen screen, NBTTagCompound ui) {
        return idle(screen, ui) && ui.getBoolean("confirmation")
            && data().getBoolean("virtualLocked")
            && data().getBoolean("blueprintAwaitingConfirmation");
    }

    private static NBTTagCompound control(NBTTagCompound ui, int id) {
        NBTTagList rows = ui.getTagList("drawnControls", 10);
        for (int i = 0; i < rows.tagCount(); i++) if (rows.getCompoundTagAt(i)
            .getInteger("id") == id) return rows.getCompoundTagAt(i);
        return null;
    }

    private static int accent(NBTTagCompound ui, int id) {
        NBTTagCompound c = control(ui, id);
        return c == null ? 0 : c.getInteger("accent");
    }

    private static boolean enabled(NBTTagCompound ui, int id) {
        NBTTagCompound c = control(ui, id);
        return c != null && c.getBoolean("enabled");
    }

    private static NBTTagCompound data() {
        return HologramClient.state == null ? new NBTTagCompound() : HologramClient.state.data;
    }

    private static NBTTagList groups() {
        return data().getCompoundTag("networkBindings")
            .getTagList("groups", 10);
    }

    private static void assertModalContains(NBTTagCompound ui, int id) {
        int[] bounds = ui.getIntArray("confirmationBounds");
        NBTTagCompound c = control(ui, id);
        require(
            bounds.length == 4 && c != null
                && c.getInteger("x") >= bounds[0]
                && c.getInteger("y") >= bounds[1]
                && c.getInteger("x") + c.getInteger("w") <= bounds[2]
                && c.getInteger("y") + c.getInteger("h") <= bounds[3],
            "binding control wholly inside actual modal");
    }

    private static Object field(Object object, String name) throws Exception {

        return ((Object) HologramUiDiagnostics.readDiagnosticField(object, name));
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) value.append(String.format("%02x", b & 255));
        return value.toString();
    }

    private void fail(Minecraft mc, Throwable failure) {
        failure.printStackTrace();
        try {
            NBTTagCompound proof = new NBTTagCompound();
            proof.setBoolean("passed", false);
            proof.setString("failure", failure.toString());
            proof.setInteger("step", step);
            proof.setString("stepLabel", step < steps.size() ? steps.get(step).label : "complete");
            proof.setTag("actualSnapshot", data().copy());
            if (mc.currentScreen instanceof HologramScreen)
                proof.setTag("actualUi", ((HologramScreen) mc.currentScreen).smokeLibraryUiState());
            proof.setTag("actions", actions.copy());
            HologramV011Evidence.write("guidance-client-failure.nbt", proof);
            HologramV011Evidence.marker("guidance-client-fail.txt", "FAIL " + failure);
        } catch (Exception evidenceFailure) {
            failure.addSuppressed(evidenceFailure);
        }
        done = true;
        mc.shutdown();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError("native guidance: " + message);
    }
}
