package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtmb.Tags;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in real FML/GL routing probe. Synthetic incoming snapshots are explicitly marked as fixtures. */
public final class HologramV010GuiSmoke {

    private final long started = System.nanoTime();
    private HologramScreen screen;
    private NBTTagCompound source;
    private File output;
    private int stage, frames;
    private int previousGuiScale;
    private int[] modalSelection;
    private CaptureRequest pendingCapture;
    private final Map<String, String> screenshotHashes = new LinkedHashMap<>();
    private boolean finished;

    /** Return true when this dedicated GUI driver owns the client; no normal client is affected. */
    public static boolean install() {
        if (!Boolean.getBoolean("gtmb.hologram.v010GuiSmoke")) return false;
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke"))
            throw new IllegalStateException("v010 GUI probe requires the isolated clientSmoke profile");
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramV010GuiSmoke());
        return true;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (System.nanoTime() - started > 300000000000L)
                throw new AssertionError("v010 real GUI initialization/stage timed out: " + stage);
            if (stage == 0) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) return;
                File snapshot = new File(System.getProperty("gtmb.hologram.clientState", ""));
                output = new File(System.getProperty("gtmb.hologram.v010ReceiptDir", ""));
                require(
                    !output.getPath()
                        .isEmpty() && output.isDirectory(),
                    "explicit receipt directory");
                try (FileInputStream input = new FileInputStream(snapshot)) {
                    source = CompressedStreamTools.readCompressed(input);
                }
                require(
                    source != null && source.getTagList("cells", 10)
                        .tagCount() > 0,
                    "real source snapshot");
                NBTTagCompound provenance = new NBTTagCompound();
                provenance.setString(
                    "version",
                    (String) Tags.class.getField("VERSION")
                        .get(null));
                provenance.setString(
                    "sourceSnapshotSha256",
                    hex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(snapshot.toPath()))));
                provenance.setString("fixtureKind", "synthetic incoming snapshots derived from real server snapshot");
                write("provenance.nbt", provenance);
                open(mc, fixture(154, "large"));
                stage = 1;
                return;
            }
            if (pendingCapture != null) return;
            if (++frames < 4) return;
            frames = 0;
            if (stage == 1) {
                HologramSelection selection = (HologramSelection) field(screen, "selection");
                selection.click(153, false);
                set(screen, "selected", 153);
                NBTTagCompound next = fixture(53, "small");
                next.setString("openPurpose", "blueprintEdit");
                screen.updateState(new HologramState(next));
                require(
                    selection.size() == 0 && (Integer) field(screen, "selected") == -1,
                    "154 to 53 retires stale primary and memory indices before opening purpose");
                captureAfterRender("snapshot-154-to-53");
                write("snapshot-53.nbt", next);
                stage = 2;
            } else if (stage == 2) {
                NBTTagCompound empty = fixture(0, "empty");
                empty.setString("openPurpose", "blueprintEdit");
                screen.updateState(new HologramState(empty));
                captureAfterRender("snapshot-53-to-empty");
                stage = 3;
            } else if (stage == 3) {
                previousGuiScale = mc.gameSettings.guiScale;
                mc.gameSettings.guiScale = 3;
                ScaledResolution resized = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
                screen.setWorldAndResolution(mc, resized.getScaledWidth(), resized.getScaledHeight());
                require(screen.smokeSelectedIndices().length == 0, "empty snapshot survives actual GUI scale change");
                captureAfterRender("snapshot-empty-scale-3");
                stage = 4;
            } else if (stage == 4) {
                mc.gameSettings.guiScale = previousGuiScale;
                screen.onGuiClosed();
                NBTTagCompound empty = fixture(0, "empty");
                empty.setString("openPurpose", "blueprintEdit");
                open(mc, empty);
                require(screen.smokeSelectedIndices().length == 0, "empty snapshot survives close/reopen memory");
                captureAfterRender("snapshot-empty-reopened");
                stage = 5;
            } else if (stage == 5) {
                open(mc, fixture(53, "mouse"));
                set(screen, "libraryDrawer", false);
                set(screen, "blueprintTab", false);
                set(screen, "moveHandles", false);
                stage = 6;
            } else if (stage == 6) {
                probeMouseSelection();
                captureAfterRender("actual-Ctrl-mouse-selection");
                stage = 7;
            } else if (stage == 7) {
                modalSelection = screen.smokeSelectedIndices();
                set(screen, "libraryDrawer", true);
                set(screen, "blueprintTab", true);
                HologramMuiProbeInput.renderFixture(screen, 0, 0, 0); // Build actual mouse hit controls; capture waits
                                                                      // for normal rendered
                // frames.
                click(screen.smokeControlPoint(211));
                require(
                    (Boolean) field(screen, "deidentificationDrawer"),
                    "actual deidentification button opens modal");
                captureAfterRender("deidentification-modal-recommended");
                stage = 8;
            } else if (stage == 8) {
                click(screen.smokeControlPoint(22010));
                require((Integer) field(screen, "deidentificationLevel") == 10, "actual level10 control");
                captureAfterRender("deidentification-modal-level10");
                stage = 9;
            } else if (stage == 9) {
                click(screen.smokeControlPoint(22000));
                require((Integer) field(screen, "deidentificationLevel") == 0, "actual level0 control");
                captureAfterRender("deidentification-modal-level0");
                stage = 10;
            } else if (stage == 10) {
                click(screen.smokeControlPoint(213));
                require(!(Boolean) field(screen, "deidentificationDrawer"), "actual remember button dismisses modal");
                HologramMuiProbeInput.renderFixture(screen, 0, 0, 0);
                click(screen.smokeControlPoint(211));
                require(
                    (Integer) field(screen, "deidentificationLevel") == 0,
                    "chosen zero remembered when modal reopens");
                captureAfterRender("deidentification-modal-zero-reopened");
                stage = 11;
            } else if (stage == 11) {
                finishDeidentificationModal(mc);
                captureAfterRender("deidentification-modal-Esc-editor");
                stage = 12;
            } else if (stage == 12) {
                require(
                    !screenshotHashes.get("snapshot-154-to-53")
                        .equals(screenshotHashes.get("snapshot-53-to-empty")),
                    "actual rendered 53 and empty screenshots differ");
                require(
                    !screenshotHashes.get("deidentification-modal-level10")
                        .equals(screenshotHashes.get("deidentification-modal-level0")),
                    "actual rendered level10 and level0 modal screenshots differ");
                require(
                    !screenshotHashes.get("actual-Ctrl-mouse-selection")
                        .equals(screenshotHashes.get("deidentification-modal-level0")),
                    "actual rendered editor and modal screenshots differ");
                finish(mc, true, "snapshot154to53to0 scale3 memory-reopen actual-Ctrl-mouse deid-level0-memory GL");
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(mc, false, failure.toString());
        }
    }

    private void open(Minecraft mc, NBTTagCompound snapshot) throws Exception {
        screen = new HologramScreen(new HologramState(snapshot));
        set(screen, "captureRenderSmokeAction", true);
        mc.displayGuiScreen(screen);
    }

    private NBTTagCompound fixture(int count, String label) {
        NBTTagCompound fixture = (NBTTagCompound) source.copy();
        fixture.setString("session", "v010-gui-fixture");
        fixture.setString("status", "v0.0.10 incoming snapshot fixture: " + label);
        fixture.setBoolean("virtual", true);
        fixture.setBoolean("virtualPlacement", false);
        fixture.setBoolean("moving", false);
        fixture.setBoolean("supported", true);
        fixture.setBoolean("target", true);
        fixture.setBoolean("settingsOnly", false);
        fixture.setBoolean("presetBrowseForPlacement", false);
        fixture.setBoolean("blueprintPreview", false);
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("capturePending", false);
        fixture.setInteger("job", 0);
        fixture.setInteger("selected", -1);
        fixture.setIntArray("selectedIndices", new int[0]);
        fixture.setString("openPurpose", "");
        fixture.setString("activeStructure", "");
        fixture.setLong("planRevision", count == 154 ? 1 : count == 53 ? 2 : 3);
        fixture.setTag("structures", new NBTTagList());
        fixture.setTag("hintAreas", new NBTTagList());
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < count; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            int x = i % 11, y = i / 11;
            cell.setInteger("dx", x);
            cell.setInteger("dy", y);
            cell.setInteger("dz", 0);
            cell.setInteger("x", fixture.getInteger("x") + x);
            cell.setInteger("y", fixture.getInteger("y") + y);
            cell.setInteger("z", fixture.getInteger("z"));
            cell.setString("id", "minecraft:stone");
            cell.setString("wantId", "minecraft:stone");
            cell.setString("status", "missing");
            cells.appendTag(cell);
        }
        fixture.setTag("cells", cells);
        return fixture;
    }

    private void probeMouseSelection() throws Exception {
        HologramMuiProbeInput.renderFixture(screen, 0, 0, 0);
        Map<Integer, int[]> points = new LinkedHashMap<>();
        for (int y = HologramScreenLayout.VIEWPORT_Y + 8; y
            < HologramScreenLayout.VIEWPORT_Y + HologramScreenLayout.VIEWPORT_HEIGHT - 8; y += 3) {
            for (int x = HologramScreenLayout.VIEWPORT_X + 8; x
                < HologramScreenLayout.VIEWPORT_X + HologramScreenLayout.VIEWPORT_WIDTH - 8; x += 3) {
                int index = HologramRenderer.pick(x, y);
                if (index >= 0 && !points.containsKey(index)) points.put(index, point(x, y));
            }
        }
        require(points.size() >= 2, "two distinct actual GL projected cell hit regions");
        Integer[] indices = points.keySet()
            .toArray(new Integer[0]);
        int[] first = points.get(indices[0]), second = points.get(indices[1]);
        int[] modifiers = { Keyboard.KEY_LCONTROL, Keyboard.KEY_RCONTROL, Keyboard.KEY_LMENU, Keyboard.KEY_RMENU,
            Keyboard.KEY_LSHIFT, Keyboard.KEY_RSHIFT, Keyboard.KEY_T };
        byte[] previous = new byte[modifiers.length];
        ByteBuffer buffer = keys();
        for (int i = 0; i < modifiers.length; i++) {
            previous[i] = buffer.get(modifiers[i]);
            buffer.put(modifiers[i], (byte) 0);
        }
        try {
            click(first);
            require(Arrays.equals(screen.smokeSelectedIndices(), new int[] { indices[0] }), "actual first cell click");
            buffer.put(Keyboard.KEY_LCONTROL, (byte) 1);
            click(second);
            int[] selected = screen.smokeSelectedIndices();
            require(
                selected.length == 2 && contains(selected, indices[0]) && contains(selected, indices[1]),
                "actual Ctrl click adds second cell");
            click(first);
            require(
                Arrays.equals(screen.smokeSelectedIndices(), new int[] { indices[1] }),
                "actual Ctrl click toggles only clicked cell");
            require(field(screen, "capturedRenderSmokeAction") == null, "local mouse selection emits no packet");
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setIntArray("pickedIndices", new int[] { indices[0], indices[1] });
            receipt.setIntArray("firstScreenPoint", first);
            receipt.setIntArray("secondScreenPoint", second);
            receipt.setIntArray("finalSelection", screen.smokeSelectedIndices());
            receipt.setBoolean("realKeyboardBuffer", true);
            receipt.setBoolean("realProtectedMouseHandlers", true);
            write("actual-mouse.nbt", receipt);
        } finally {
            for (int i = 0; i < modifiers.length; i++) buffer.put(modifiers[i], previous[i]);
        }
    }

    private int[] point(int x, int y) throws Exception {
        float scale = (Float) field(screen, "scale");
        return new int[] { (Integer) field(screen, "originX") + Math.round(x * scale),
            (Integer) field(screen, "originY") + Math.round(y * scale) };
    }

    private void finishDeidentificationModal(Minecraft mc) throws Exception {
        HologramMuiProbeInput.key(screen, '\0', Keyboard.KEY_ESCAPE);
        require(
            !(Boolean) field(screen, "deidentificationDrawer") && mc.currentScreen == screen,
            "actual Esc closes deidentification modal and retains editor");
        require(
            Arrays.equals(modalSelection, screen.smokeSelectedIndices()),
            "modal controls preserve local selection");
        require(
            field(screen, "capturedRenderSmokeAction") == null,
            "local save-choice/modal cancellation emits no packet");
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("actualLevel10Control", true);
        receipt.setBoolean("actualLevel0Control", true);
        receipt.setBoolean("chosenZeroRemembered", true);
        receipt.setBoolean("escapeRetainsEditor", true);
        receipt.setIntArray("preservedSelection", screen.smokeSelectedIndices());
        write("deidentification-modal.nbt", receipt);
        System.out.println("[GTMB-V010-GUI] PASS actual-deidentification-modal-level10-level0-memory-Esc");
    }

    private void click(int[] point) {
        HologramMuiProbeInput.click(screen, point[0], point[1], 0);
    }

    private void captureAfterRender(String name) throws Exception {
        require(pendingCapture == null, "one capture per distinct rendered state");
        HologramState state = (HologramState) field(screen, "state");
        NBTTagCompound expected = new NBTTagCompound();
        expected.setString("name", name);
        expected.setInteger("screenIdentity", System.identityHashCode(screen));
        expected.setInteger("stateIdentity", System.identityHashCode(state));
        expected.setInteger("cells", state.cells.size());
        expected.setBoolean("modal", (Boolean) field(screen, "deidentificationDrawer"));
        expected.setInteger("modalLevel", (Integer) field(screen, "deidentificationLevel"));
        expected.setBoolean("library", (Boolean) field(screen, "libraryDrawer"));
        expected.setIntArray("selected", screen.smokeSelectedIndices());
        pendingCapture = new CaptureRequest(screen, state, (Long) field(screen, "renderGeneration"), expected);
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (finished || pendingCapture == null || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            CaptureRequest request = pendingCapture;
            require(
                mc.currentScreen == request.screen && screen == request.screen,
                "render capture currentScreen is the exact requested editor");
            HologramState actual = (HologramState) field(screen, "state");
            require(actual == request.state, "render capture consumes exact requested snapshot object");
            long generation = (Long) field(screen, "renderGeneration");
            if (generation < request.minimumGeneration + 2) return;
            require(
                actual.cells.size() == request.expected.getInteger("cells")
                    && (Boolean) field(screen, "deidentificationDrawer") == request.expected.getBoolean("modal")
                    && (Integer) field(screen, "deidentificationLevel") == request.expected.getInteger("modalLevel")
                    && Arrays.equals(screen.smokeSelectedIndices(), request.expected.getIntArray("selected")),
                "actual normal rendered frame matches cells, modal, level and selected witness");
            GL11.glFinish();
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "actual normal GL rendered frame");
            String name = request.expected.getString("name");
            ScreenShotHelper
                .saveScreenshot(output, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            File png = new File(output, "screenshots/" + name + ".png");
            require(png.isFile(), "actual post-render screenshot for " + name);
            String checksum = hex(
                MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(png.toPath())));
            screenshotHashes.put(name, checksum);
            NBTTagCompound witness = (NBTTagCompound) request.expected.copy();
            witness.setString("captureEvent", "RenderTick.END");
            witness.setLong("requestedAfterGeneration", request.minimumGeneration);
            witness.setLong("actualRenderedGeneration", generation);
            witness.setInteger("framebufferObject", mc.getFramebuffer().framebufferObject);
            witness.setString("pngSha256", checksum);
            witness.setString(
                "snapshotSha256",
                hex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(CompressedStreamTools.compress(actual.data))));
            write(name + "-render-witness.nbt", witness);
            System.out
                .println("[GTMB-V010-GUI] PASS " + name + " actualRenderGeneration=" + generation + " PNG=" + checksum);
            pendingCapture = null;
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(mc, false, failure.toString());
        }
    }

    private static final class CaptureRequest {

        final HologramScreen screen;
        final HologramState state;
        final long minimumGeneration;
        final NBTTagCompound expected;

        CaptureRequest(HologramScreen screen, HologramState state, long generation, NBTTagCompound expected) {
            this.screen = screen;
            this.state = state;
            minimumGeneration = generation;
            this.expected = expected;
        }
    }

    /** Call once in the existing isolated Socket driver's actual unlocked moving world stage. */
    public static void assertMovingWheel(Minecraft mc) throws Exception {
        if (!Boolean.getBoolean("gtmb.hologram.v010MovingWheelSmoke")) return;
        require(
            mc.currentScreen == null && mc.thePlayer != null
                && HologramClient.state != null
                && HologramClient.state.data.getBoolean("moving")
                && !HologramClient.state.data.getBoolean("virtualLocked"),
            "actual moving world context");
        HologramClient.movingAnchor(HologramClient.state);
        HologramWorldFlow.PreviewDistance distance = (HologramWorldFlow.PreviewDistance) staticField(
            HologramClient.class,
            "previewDistance");
        int before = distance.distance();
        ByteBuffer buffer = keys();
        byte left = buffer.get(Keyboard.KEY_LMENU), right = buffer.get(Keyboard.KEY_RMENU);
        ItemStack held = mc.thePlayer.getHeldItem();
        try {
            buffer.put(Keyboard.KEY_LMENU, (byte) 0);
            buffer.put(Keyboard.KEY_RMENU, (byte) 0);
            MouseEvent ordinary = wheel(-120);
            MinecraftForge.EVENT_BUS.post(ordinary);
            require(!ordinary.isCanceled() && distance.distance() == before, "ordinary wheel retains hotbar routing");
            buffer.put(Keyboard.KEY_LMENU, (byte) 1);
            MouseEvent zero = wheel(-120 * 100);
            MinecraftForge.EVENT_BUS.post(zero);
            require(zero.isCanceled() && distance.distance() == 0, "Alt wheel reaches zero and owns input");
            int[] feet = { MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.boundingBox.minY), MathHelper.floor_double(mc.thePlayer.posZ) };
            require(
                Arrays.equals(feet, HologramClient.movingAnchor(HologramClient.state)),
                "zero anchor uses player feet");
            require(
                held != null && held.hasTagCompound()
                    && !held.getTagCompound()
                        .getString("gtitHologramToolId")
                        .isEmpty(),
                "real logical projector UUID");
            mc.thePlayer.inventory.mainInventory[mc.thePlayer.inventory.currentItem] = held.copy();
            HologramClient.movingAnchor(HologramClient.state);
            require(distance.distance() == 0, "same UUID slot synchronization keeps zero memory");
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setString("session", HologramClient.state.session);
            receipt.setLong("planRevision", HologramClient.state.data.getLong("planRevision"));
            receipt.setIntArray("zeroAnchor", feet);
            receipt.setInteger("originalDistance", before);
            receipt.setBoolean("ordinaryWheelUncancelled", true);
            receipt.setBoolean("altWheelCancelled", true);
            receipt.setBoolean("sameUuidStackCopyPreservedZero", true);
            File directory = new File(System.getProperty("gtmb.hologram.interactionReceiptDir", ""));
            try (FileOutputStream out = new FileOutputStream(new File(directory, "v010-moving-wheel.nbt"))) {
                CompressedStreamTools.writeCompressed(receipt, out);
            }
            System.out.println("[GTMB-V010-GUI] PASS actual-Forge-Alt-wheel-zero-and-UUID-memory");
        } finally {
            mc.thePlayer.inventory.mainInventory[mc.thePlayer.inventory.currentItem] = held;
            buffer.put(Keyboard.KEY_LMENU, (byte) 1);
            if (distance.distance() != before)
                MinecraftForge.EVENT_BUS.post(wheel(120 * (before - distance.distance())));
            buffer.put(Keyboard.KEY_LMENU, left);
            buffer.put(Keyboard.KEY_RMENU, right);
        }
    }

    private static MouseEvent wheel(int amount) throws Exception {
        MouseEvent event = new MouseEvent();
        Field wheel = MouseEvent.class.getField("dwheel");
        wheel.setAccessible(true);
        wheel.setInt(event, amount);
        Field button = MouseEvent.class.getField("button");
        button.setAccessible(true);
        button.setInt(event, -1);
        Field state = MouseEvent.class.getField("buttonstate");
        state.setAccessible(true);
        state.setBoolean(event, false);
        return event;
    }

    private void write(String name, NBTTagCompound receipt) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(output, name))) {
            CompressedStreamTools.writeCompressed(receipt, out);
        }
    }

    private void finish(Minecraft mc, boolean pass, String reason) {
        finished = true;
        String line = "[GTMB-V010-GUI] FINAL " + (pass ? "PASS " : "FAIL ") + reason;
        System.out.println(line);
        try {
            if (output != null) Files.write(
                new File(output, pass ? "PASS.txt" : "FAIL.txt").toPath(),
                (line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception publicationFailure) {
            publicationFailure.printStackTrace();
        }
        mc.shutdown();
    }

    private static ByteBuffer keys() throws Exception {
        return (ByteBuffer) staticField(Keyboard.class, "keyDownBuffer");
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object field(Object owner, String name) throws Exception {

        return ((Object) HologramUiDiagnostics.readDiagnosticField(owner, name));
    }

    private static void set(Object owner, String name, Object value) throws Exception {

        HologramUiDiagnostics.writeDiagnosticField(owner, name, value);
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError("v010 GUI: " + label);
    }
}
