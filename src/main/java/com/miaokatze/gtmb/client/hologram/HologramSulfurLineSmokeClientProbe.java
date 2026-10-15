package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramV011Evidence;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in two-world sulfur line author/recipient using real input, Netty ACKs and END framebuffers. */
public final class HologramSulfurLineSmokeClientProbe {

    private interface Action {

        boolean run(Minecraft mc, HologramScreen screen, NBTTagCompound ui) throws Exception;
    }

    private interface GroupAction {

        void run(HologramQuantumGroupsScreen screen) throws Exception;
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
    private final NBTTagList frames = new NBTTagList();
    private final NBTTagList saveProofs = new NBTTagList();
    private final NBTTagList areaSelections = new NBTTagList();
    private final NBTTagList groupInteractions = new NBTTagList();
    private final NBTTagList groupNodeClicks = new NBTTagList();
    private final NBTTagList privateTerminalDrops = new NBTTagList();
    private NBTTagCompound privateNoTerminal;
    private final NBTTagList quantumPlacements = new NBTTagList();
    private NBTTagCompound quantumPlacement;
    private final HologramActualUsePress press = new HologramActualUsePress();
    private final String role = System.getProperty("gtmb.hologram.sulfurLineRole", "");
    private final String intent = System.getProperty("gtmb.hologram.sulfurLineIntent", "self");
    private final long installed = System.nanoTime();
    private int step;
    private long stepStarted = installed, lastGeneration = -1, nativeMouseEvents, nativeKeyEvents, nativeWheelEvents;
    private HologramScreen lastScreen, renderedScreen;
    private GuiScreen captureScreen;
    private long lastGroupGeneration = -1;
    private HologramState renderedState;
    private long renderedGeneration;
    private NBTTagCompound ready, runtime, capture, verifiedSaveProof;
    private NBTTagCompound saveReviewEvidence = new NBTTagCompound();
    private NBTTagCompound fullReviewEvidence;
    private int[] bounds, targetOrigin;
    private String localId = "", expectedName, session = "", presetId = "";
    private String importId;
    private Throwable importFailure;
    private boolean finished, newSave, overwriteSave, processingPresetSave, imported;
    private boolean saveReviewConfirmed;
    private boolean enteredQuantumGroupingScreen;
    private String originalFileHash, originalPreviewHash;
    private long closeSequence = -1;

    public static void install() {
        HologramGuidanceNativeClientProbe.install();
        if (!Boolean.getBoolean("gtmb.hologram.sulfurLineClient")) return;
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramSulfurLineSmokeClientProbe());
    }

    private HologramSulfurLineSmokeClientProbe() {
        add("connect-loopback", (mc, screen, ui) -> {
            boolean mainMenu = mc.currentScreen instanceof GuiMainMenu
                || mc.currentScreen != null && mc.currentScreen.getClass()
                    .getName()
                    .equals("lumien.custommainmenu.gui.GuiCustom");
            if (!mainMenu) return false;
            require(role.equals("author") || role.equals("placer"), "explicit sulfur client role author or placer");
            int dimension = Integer.getInteger("gtmb.hologram.sulfurLineDimension", "placer".equals(role) ? 777 : 0);
            if (dimension != 0) {
                require(
                    !DimensionManager.isDimensionRegistered(dimension),
                    "fresh opt-in client target dimension cannot replace another mod's dimension");
                DimensionManager.registerDimension(dimension, 0);
            }
            String address = System.getProperty("gtmb.hologram.sulfurLineAddress", "");
            require(address.matches("127\\.0\\.0\\.1:[0-9]{2,5}"), "explicit paired sulfur loopback address");
            require(
                !Boolean.getBoolean("gtmb.hologram.clientSmoke")
                    && !Boolean.getBoolean("gtmb.hologram.realInteractionSmoke")
                    && !Boolean.getBoolean("gtmb.hologram.followupWorkbenchClient"),
                "exclusive real sulfur client driver");
            for (String suffix : new String[] { "final.nbt", "pass.txt", "fail.txt" }) require(
                !Files.exists(
                    HologramV011Evidence.directory()
                        .resolve("sulfur-" + role + "-" + suffix)),
                "fresh role receipts cannot reuse prior results");
            mc.gameSettings.pauseOnLostFocus = false;
            cpw.mods.fml.client.FMLClientHandler.instance()
                .setupServerList();
            cpw.mods.fml.client.FMLClientHandler.instance()
                .connectToServer(mc.currentScreen, new ServerData("GTMB isolated sulfur " + role, address));
            return true;
        });
        quantumBootstrap();
        if (role.equals("author")) author();
        else if (role.equals("placer")) placer();
    }

    private void quantumBootstrap() {
        add("native-quantum-terminal-controller-click", (mc, screen, ui) -> {
            NBTTagCompound boot = HologramV011Evidence.read("sulfur-quantum-bootstrap-ready.nbt");
            if (!connected(mc) || boot == null) return false;
            validateActor(mc, boot);
            require("self".equals(intent) || "share".equals(intent), "explicit author tendency");
            int[] controller = boot.getIntArray("controller");
            mc.thePlayer.inventory.currentItem = 1;
            require(
                mc.thePlayer.getHeldItem() != null && mc.thePlayer.getHeldItem()
                    .getItem()
                    .getClass()
                    .getName()
                    .equals("com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal"),
                "real unbound native terminal");
            aim(mc, controller[0] + .5, controller[1] + .5, controller[2] + .5);
            press.begin(mc, false);
            useBlock(mc, controller[0], controller[1], controller[2], 3);
            nativeMouseEvents++;
            return true;
        });
        add("native-terminal-binding-server-ack", (mc, screen, ui) -> {
            NBTTagCompound bound = HologramV011Evidence.read("sulfur-quantum-native-bound.nbt");
            if (bound == null) {
                if (mc.theWorld.getTotalWorldTime() % 40 == 0) {
                    int[] controller = HologramV011Evidence.read("sulfur-quantum-bootstrap-ready.nbt")
                        .getIntArray("controller");
                    useBlock(mc, controller[0], controller[1], controller[2], 3);
                    nativeMouseEvents++;
                }
                return false;
            }
            validateActor(mc, bound);
            mc.thePlayer.inventory.currentItem = 0;
            requestFrame(mc, null, "sulfur-quantum-native-bind-frame", "sulfur-quantum-native-bound.nbt");
            return true;
        });
        add(
            "native-bound-terminal-places-storage-gateway",
            (mc, screen, ui) -> { return nativeQuantumPlacement(mc, "sulfur-quantum-native-storage-place"); });
        if ("author".equals(role)) for (int i = 0; i < 7; i++) {
            final int index = i;
            add(
                "native-bound-terminal-places-gateway-" + index,
                (mc, screen, ui) -> { return nativeQuantumPlacement(mc, "sulfur-quantum-native-place-" + index); });
        }
        add("native-quantum-bootstrap-complete", (mc, screen, ui) -> {
            if (HologramV011Evidence.read("sulfur-quantum-bootstrap-complete.nbt") == null) return false;
            mc.thePlayer.inventory.currentItem = 0;
            return true;
        });
    }

    private boolean nativeQuantumPlacement(Minecraft mc, String prefix) throws Exception {
        NBTTagCompound place = HologramV011Evidence.read(prefix + "-ready.nbt");
        if (mc.thePlayer == null || mc.theWorld == null || place == null) return false;
        validateActor(mc, place);
        require(place.getInteger("dimension") == mc.thePlayer.dimension, "native placement ready dimension");
        long tick = mc.theWorld.getTotalWorldTime();
        if (quantumPlacement == null) {
            quantumPlacement = new NBTTagCompound();
            quantumPlacement.setString("prefix", prefix);
            quantumPlacement.setTag("ready", place.copy());
            quantumPlacement.setLong("firstObservedClientTick", tick);
            quantumPlacement.setTag("attempts", new NBTTagList());
        }
        require(prefix.equals(quantumPlacement.getString("prefix")), "one native placement action awaits its own ACK");
        NBTTagCompound ack = HologramV011Evidence.read(prefix + "-ack.nbt");
        if (ack != null) {
            validateActor(mc, ack);
            require(
                ack.getString("world")
                    .equals(place.getString("world"))
                    && ack.getString("session")
                        .equals(place.getString("session"))
                    && ack.getInteger("dimension") == place.getInteger("dimension")
                    && ack.getLong("readyWorldTick") == place.getLong("worldTick")
                    && ack.getLong("worldTick") >= place.getLong("worldTick")
                    && Arrays.equals(ack.getIntArray("gateway"), place.getIntArray("gateway"))
                    && Arrays.equals(ack.getIntArray("gatewaySupport"), place.getIntArray("gatewaySupport"))
                    && Arrays.equals(ack.getIntArray("expectedAnchor"), place.getIntArray("expectedAnchor")),
                "real server ACK matches this placement ready, world, actor, support and anchor");
            NBTTagCompound node = ack.getCompoundTag("nativeNode");
            int[] anchor = place.getIntArray("expectedAnchor");
            require(
                "gtswn.network_quantum_node".equals(node.getString("id"))
                    && Arrays.equals(
                        anchor,
                        new int[] { node.getInteger("anchorDim"), node.getInteger("anchorX"),
                            node.getInteger("anchorY"), node.getInteger("anchorZ") })
                    && mc.thePlayer.getUniqueID()
                        .toString()
                        .equals(node.getString("placerUUID"))
                    && mc.thePlayer.getCommandSenderName()
                        .equals(node.getString("placerName"))
                    && node.getCompoundTag("proxy")
                        .getInteger("p") == ack.getInteger("nativeOwnerPlayerId"),
                "real server node has the requested native anchor and current placer/AE owner");
            require(
                quantumPlacement.getTagList("attempts", 10)
                    .tagCount() > 0,
                "ACK follows actual native input");
            quantumPlacement.setTag("ack", ack.copy());
            quantumPlacement.setLong("ackObservedClientTick", tick);
            quantumPlacements.appendTag(quantumPlacement);
            quantumPlacement = null;
            writePlacementProgress(mc);
            return true;
        }
        NBTTagList attempts = quantumPlacement.getTagList("attempts", 10);
        long last = attempts.tagCount() == 0 ? quantumPlacement.getLong("firstObservedClientTick")
            : attempts.getCompoundTagAt(attempts.tagCount() - 1)
                .getLong("clientWorldTick");
        if (tick - last < 20) return false;
        int[] point = place.getIntArray("gateway");
        net.minecraft.tileentity.TileEntity observed = mc.theWorld.getTileEntity(point[0], point[1], point[2]);
        if (observed != null) {
            require(
                "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode".equals(
                    observed.getClass()
                        .getName()),
                "native placement target contains the expected node, never retry into another tile");
            if (!quantumPlacement.hasKey("clientObservedNativeNode")) {
                NBTTagCompound nativeNode = new NBTTagCompound();
                observed.writeToNBT(nativeNode);
                quantumPlacement.setTag("clientObservedNativeNode", nativeNode);
                quantumPlacement.setLong("nativeNodeObservedClientTick", tick);
                writePlacementProgress(mc);
            }
            return false;
        }
        require(attempts.tagCount() < 8, "native quantum placement exhausted eight bounded attempts: " + prefix);
        double dx = mc.thePlayer.posX - place.getDouble("serverPlayerX");
        double dy = mc.thePlayer.boundingBox.minY - place.getDouble("serverPlayerY");
        double dz = mc.thePlayer.posZ - place.getDouble("serverPlayerZ");
        if (mc.currentScreen != null || dx * dx + dy * dy + dz * dz > .01) return false;
        int[] support = place.getIntArray("gatewaySupport");
        require(
            mc.thePlayer.getDistanceSq(support[0] + .5, support[1] + .5, support[2] + .5) <= 36,
            "native placement support remains in actual reach");
        mc.thePlayer.inventory.currentItem = 1;
        require(
            mc.thePlayer.getHeldItem() != null && mc.thePlayer.getHeldItem()
                .getItem()
                .getClass()
                .getName()
                .equals("com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal"),
            "actual placement terminal in native slot");
        NBTTagCompound attempt = placementClientState(mc);
        attempt.setLong("nativeMouseEventsBefore", nativeMouseEvents);
        attempt.setIntArray("gatewaySupport", support);
        aim(mc, support[0] + .5, support[1] + .5, support[2] + .5);
        useBlock(mc, support[0], support[1], support[2], support[3]);
        nativeMouseEvents++;
        attempt.setLong("nativeMouseEventsAfter", nativeMouseEvents);
        attempts.appendTag(attempt);
        writePlacementProgress(mc);
        return false;
    }

    private NBTTagCompound placementClientState(Minecraft mc) {
        NBTTagCompound state = new NBTTagCompound();
        if (mc.thePlayer != null) {
            state.setDouble("clientPlayerX", mc.thePlayer.posX);
            state.setDouble("clientPlayerY", mc.thePlayer.boundingBox.minY);
            state.setDouble("clientNativePosY", mc.thePlayer.posY);
            state.setDouble("clientPlayerZ", mc.thePlayer.posZ);
            state.setInteger("clientSelectedSlot", mc.thePlayer.inventory.currentItem);
            state.setInteger("dimension", mc.thePlayer.dimension);
            if (mc.thePlayer.getHeldItem() != null) state.setTag(
                "clientHeldItem",
                mc.thePlayer.getHeldItem()
                    .writeToNBT(new NBTTagCompound()));
        }
        if (mc.theWorld != null) state.setLong("clientWorldTick", mc.theWorld.getTotalWorldTime());
        return state;
    }

    private void writePlacementProgress(Minecraft mc) throws Exception {
        NBTTagCompound progress = placementClientState(mc);
        progress.setBoolean(
            "passed",
            quantumPlacement == null && quantumPlacements.tagCount() == ("author".equals(role) ? 8 : 1));
        progress.setString(
            "actor",
            mc.thePlayer.getUniqueID()
                .toString());
        progress.setTag("actualQuantumPlacements", quantumPlacements.copy());
        if (quantumPlacement != null) progress.setTag("pendingQuantumPlacement", quantumPlacement.copy());
        HologramV011Evidence.write("sulfur-quantum-native-placement-client-progress.nbt", progress);
    }

    private void quantumCycleFrames(String label) {
        for (String phase : new String[] { "stopped", "restored" })
            add("quantum-" + label + "-" + phase + "-native-frame", (mc, screen, ui) -> {
                String name = "sulfur-" + label + "-quantum-" + phase;
                NBTTagCompound witness = HologramV011Evidence.read(name + ".nbt");
                if (!connected(mc) || mc.currentScreen != null || witness == null) return false;
                validateActor(mc, witness);
                int[] gateway = HologramV011Evidence.read("sulfur-quantum-bootstrap-ready.nbt")
                    .getIntArray("gateway");
                aim(mc, gateway[0] + .5, gateway[1] + .5, gateway[2] + .5);
                requestFrame(mc, null, name + "-frame", name + ".nbt");
                return true;
            });
    }

    private void author() {
        add("source-running-native-frame", (mc, screen, ui) -> {
            runtime = HologramV011Evidence.read("sulfur-source-running.nbt");
            if (!connected(mc) || runtime == null) return false;
            validateActor(mc, runtime);
            require("SOURCE_RUNNING".equals(runtime.getString("phase")), "native source runtime phase");
            bounds = runtime.getIntArray("bounds");
            require(bounds.length == 6, "source runtime has actual six-coordinate bounds");
            mc.displayGuiScreen(null);
            aim(
                mc,
                (bounds[0] + bounds[3] + 1) / 2D,
                (bounds[1] + bounds[4] + 1) / 2D,
                (bounds[2] + bounds[5] + 1) / 2D);
            requestFrame(mc, null, "sulfur-source-running-frame", "sulfur-source-running.nbt");
            return true;
        });
        quantumCycleFrames("source");
        add("source-quiescent-native-begin-area", (mc, screen, ui) -> {
            ready = HologramV011Evidence.read("sulfur-server-ready.nbt");
            if (!connected(mc) || ready == null) return false;
            validateActor(mc, ready);
            require("SOURCE_QUIESCENT".equals(ready.getString("phase")), "source is quiescent before actual capture");
            require(
                ready.getString("world")
                    .equals(runtime.getString("world")),
                "runtime/capture share real source world");
            require(Arrays.equals(bounds, ready.getIntArray("bounds")), "runtime/capture source bounds agree");
            require(
                mc.thePlayer.dimension
                    == ready.getInteger(ready.hasKey("sourceDimension") ? "sourceDimension" : "dimension"),
                "actual source dimension matches server");
            mc.displayGuiScreen(null);
            key(Keyboard.KEY_LSHIFT, true);
            press.begin(mc, false);
            useBlock(mc, bounds[0], bounds[1], bounds[2], 1);
            key(Keyboard.KEY_LSHIFT, false);
            return true;
        });
        add("begin-area-netty-ack", (mc, screen, ui) -> {
            if (HologramClient.state == null || !data().getBoolean("areaSelecting")) return false;
            session = HologramClient.state.session;
            return true;
        });
        add("native-left-source-corner", (mc, screen, ui) -> {
            corner(mc, 0, bounds[0], bounds[1], bounds[2], false);
            return true;
        });
        add(
            "left-corner-netty-ack",
            (mc, screen, ui) -> areaPointAck(mc, new int[] { bounds[0], bounds[1], bounds[2] }));
        add("native-right-source-corner", (mc, screen, ui) -> {
            corner(mc, 1, bounds[3], bounds[4], bounds[5], false);
            return true;
        });
        add("two-corners-netty-ack", (mc, screen, ui) -> areaPointAck(mc, bounds));
        add("native-shift-right-finish", (mc, screen, ui) -> {
            corner(mc, 1, bounds[3], bounds[4], bounds[5], true);
            return true;
        });
        screen("actual-area-import-complete", (mc, screen, ui) -> {
            if (!data().getBoolean("areaImported") || data().getBoolean("capturePending")
                || data().getBoolean("areaImporting")
                || !idle(screen, ui)) return false;
            require(HologramClient.state.cells.size() > 6, "actual multi-machine native line was captured");
            require(HologramLibraryClient.dedicated() && ui.getBoolean("dedicated"), "actual dedicated client library");
            require(data().getBoolean("blueprintProcessingRequired"), "raw line requires real author processing");
            return true;
        });
        shot("sulfur-source-capture-frame", "sulfur-server-ready.nbt");
        click("open-blueprint-tab", 149);
        type("native-blueprint-name", "presetNameField", "Sulfur acid " + intent + " native line");
        screen("raw-save-gate", (mc, screen, ui) -> {
            require(!enabled(ui, 140) && !enabled(ui, 132) && !enabled(ui, 221), "raw author save and preset gates");
            nativeClick(screen, 140);
            require(
                !screen.smokePending() && !HologramLibraryClient.INSTANCE.busy(),
                "disabled save dispatches no operation");
            return true;
        });
        click("native-open-six-stage-processing", 220);
        for (int i = 0; i < 6; i++) {
            final int stage = i;
            screen("stage-" + stage + "-manual-confirmation-required", (mc, screen, ui) -> {
                if (stage == 0 && !ui.getBoolean("flowFullReview")) {
                    require(idle(screen, ui) && enabled(ui, 296), "visible native complete six-stage review control");
                    fullReviewEvidence = new NBTTagCompound();
                    fullReviewEvidence.setTag("actualUiBefore", ui.copy());
                    fullReviewEvidence.setTag("actualPolicyBefore", flow(screen).policy());
                    fullReviewEvidence.setLong("nativeMouseEventsBefore", nativeMouseEvents);
                    nativeClick(screen, 296);
                    NBTTagCompound after = screen.smokeLibraryUiState();
                    fullReviewEvidence.setTag("actualUiAfter", after.copy());
                    fullReviewEvidence.setTag("actualPolicyAfter", flow(screen).policy());
                    fullReviewEvidence.setLong("nativeMouseEventsAfter", nativeMouseEvents);
                    require(
                        after.getBoolean("flowFullReview") && after.getInteger("flowStage") == 0
                            && !after.getBoolean("flowChosen")
                            && confirmed(flow(screen).policy()) == 0,
                        "actual complete-review mouse click starts six explicit unconfirmed choices");
                    return false;
                }
                require(
                    ui.getBoolean("processingFlow") && ui.getBoolean("flowFullReview")
                        && ui.getInteger("flowStage") == stage
                        && confirmed(flow(screen).policy()) == stage,
                    "native stage needs its own Next " + stage);
                return true;
            });
            shot("sulfur-processing-stage-" + stage + "-unconfirmed", "sulfur-server-ready.nbt");
            screen("stage-" + stage + "-native-choice", (mc, screen, ui) -> {
                if (stage == 4) {
                    NBTTagList keys = data().hasKey("processingNbtKeys", 9) ? data().getTagList("processingNbtKeys", 8)
                        : data().getCompoundTag("processing")
                            .getTagList("nbtKeys", 8);
                    int index = -1;
                    for (int row = 0; row < keys.tagCount(); row++)
                        if ("mStoredEnergy".equals(keys.getStringTagAt(row))) index = row;
                    require(index >= 0, "native author NBT list exposes source GT stored EU");
                    int control = 72000 + index;
                    if (!enabled(ui, control)) {
                        int before = flow(screen).offset;
                        nativeWheel(mc, screen, -120, 100, 175);
                        require(flow(screen).offset > before, "native wheel scrolls toward the stored EU root");
                        return false;
                    }
                    nativeClick(screen, control);
                    NBTTagList ignored = flow(screen).policy()
                        .getTagList("ignoredNbtKeys", 8);
                    require(
                        flow(screen).chosen() && confirmed(flow(screen).policy()) == stage
                            && ignored.tagCount() == 1
                            && "mStoredEnergy".equals(ignored.getStringTagAt(0)),
                        "actual NBT root click omits stored EU alone and leaves Next unconfirmed");
                    return true;
                }
                if (stage == 0) nativeClick(screen, "share".equals(intent) ? 233 : 232);
                int[] choices = { 235, 240, "share".equals(intent) ? 260 : 262, 270, 280,
                    "share".equals(intent) ? 291 : 290 };
                int choice = stage == 1 ? 240 + flow(screen).values()
                    .getInteger("deidentificationLevel") : choices[stage];
                if (stage == 2) require(
                    ("share".equals(intent) ? "rebind" : "preserve").equals(
                        flow(screen).values()
                            .getString("quantum")),
                    "native tendency selected its actual quantum default");
                nativeClick(screen, choice);
                NBTTagCompound after = screen.smokeLibraryUiState();
                require(confirmed(flow(screen).policy()) == stage, "explicit choice leaves its own Next unconfirmed");
                if (stage == 2) {
                    require(
                        ("share".equals(intent) ? "rebind" : "preserve").equals(
                            flow(screen).values()
                                .getString("quantum"))
                            && after.getBoolean("flowChosen")
                            && enabled(after, 231)
                            && enabled(after, 263),
                        "native quantum default permits Next without entering optional grouping");
                } else require(
                    after.getBoolean("flowChosen"),
                    "explicit non-quantum choice is ready for its unconfirmed Next");
                return true;
            });
            shot("sulfur-processing-stage-" + stage + "-choice", "sulfur-server-ready.nbt");
            if (stage == 2 && "share".equals(intent)) quantumGrouping();
            click("native-next-" + stage, 231);
        }
        screen("processing-real-netty-ack", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !data().getBoolean("blueprintProcessingComplete")) return false;
            NBTTagCompound policy = data().getCompoundTag("processingPolicy");
            require(
                confirmed(policy) == 6 && intent.equals(policy.getString("tendency"))
                    && ("share".equals(intent) ? "rebind" : "preserve").equals(policy.getString("quantum"))
                    && "auto".equals(policy.getString("coordinateDefault"))
                    && policy.getTagList("ignoredNbtKeys", 8)
                        .tagCount() == 1
                    && "mStoredEnergy".equals(
                        policy.getTagList("ignoredNbtKeys", 8)
                            .getStringTagAt(0)),
                "six explicit stages retain the actual tendency defaults and omit stored EU");
            NBTTagList relations = data().getTagList("processingRelations", 10);
            boolean internalSensor = false;
            for (int i = 0; i < relations.tagCount(); i++) {
                NBTTagCompound relation = relations.getCompoundTagAt(i);
                if ("nc.sensor".equals(relation.getString("adapter")) && relation.getBoolean("internal")) {
                    require("relative".equals(relation.getString("mode")), "actual internal NC target is relative");
                    internalSensor = true;
                }
            }
            require(internalSensor, "real captured NC card has an internal line target");
            return true;
        });
        shot("sulfur-processing-ack-frame", "sulfur-server-ready.nbt");
        click("native-save-processing-preset-review", 221);
        type("native-processing-preset-name", "processingPresetField", "Sulfur acid " + intent + " reusable flow");
        shot("sulfur-processing-preset-review-frame", "sulfur-server-ready.nbt");
        click("native-confirm-processing-preset", 224);
        screen("processing-preset-real-netty-ack", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !"saveProcessingPreset".equals(data().getString("processingPresetOperation")))
                return false;
            require(data().getBoolean("processingPresetOperationSuccess"), "actual processing preset save succeeded");
            NBTTagList presets = data().getTagList("processingPresets", 10);
            for (int i = 0; i < presets.tagCount(); i++) {
                NBTTagCompound row = presets.getCompoundTagAt(i);
                if (("Sulfur acid " + intent + " reusable flow").equals(row.getString("name")))
                    presetId = row.getString("id");
            }
            require(!presetId.isEmpty(), "server returned persisted processing preset ID");
            processingPresetSave = true;
            return true;
        });
        screen("native-new-save", (mc, screen, ui) -> {
            expectedName = "Sulfur acid " + intent + " native line";
            resetSaveReview();
            nativeClick(screen, 140);
            return true;
        });
        screen("actual-new-file-owner-ack", (mc, screen, ui) -> {
            if (!awaitSave(mc, screen, ui)) return false;
            localId = data().getString("libraryLocalId");
            HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(localPath(mc, localId));
            originalFileHash = artifact.jsonSha256;
            originalPreviewHash = artifact.pngSha256;
            NBTTagCompound proof = (NBTTagCompound) verifiedSaveProof.copy();
            proof.setString("saveKind", "new");
            saveProofs.appendTag(proof);
            newSave = true;
            return true;
        });
        shot("sulfur-source-save-new-frame", "sulfur-server-ready.nbt");
        click("native-overwrite-mode", 222);
        screen("native-overwrite-select-saved-file", (mc, screen, ui) -> {
            require(ui.getBoolean("overwriteMode"), "actual overwrite mode");
            int index = localIndex(ui, localId);
            require(index >= 0, "real new file appears in client library");
            nativeClick(screen, 14000 + index);
            require(
                localId.equals(
                    screen.smokeLibraryUiState()
                        .getString("overwriteTarget")),
                "native overwrite row targets actual local file");
            return true;
        });
        type("native-overwrite-name", "presetNameField", "Sulfur acid " + intent + " reviewed native line");
        click("native-open-overwrite-review", 140);
        screen("named-overwrite-review", (mc, screen, ui) -> {
            require(
                ui.getBoolean("overwriteReview") && localId.equals(ui.getString("overwriteTarget")),
                "mandatory named overwrite review");
            expectedName = "Sulfur acid " + intent + " reviewed native line";
            resetSaveReview();
            return true;
        });
        shot("sulfur-source-overwrite-review-frame", "sulfur-server-ready.nbt");
        click("native-confirm-overwrite", 224);
        screen("actual-overwrite-file-owner-ack", (mc, screen, ui) -> {
            if (!awaitSave(mc, screen, ui)) return false;
            require(localId.equals(data().getString("libraryLocalId")), "overwrite preserves actual local ID");
            HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(localPath(mc, localId));
            require(
                !originalFileHash.equals(artifact.jsonSha256) && !originalPreviewHash.equals(artifact.pngSha256),
                "named overwrite changed both actual JSON and its bound preview PNG");
            NBTTagCompound proof = (NBTTagCompound) verifiedSaveProof.copy();
            require(
                saveProofs.tagCount() == 1 && proof.getCompoundTag("nativePreviewWitness")
                    .getLong("captureGeneration")
                    > saveProofs.getCompoundTagAt(0)
                        .getCompoundTag("nativePreviewWitness")
                        .getLong("captureGeneration"),
                "overwrite has its own later native preview capture generation");
            proof.setString("saveKind", "overwrite");
            saveProofs.appendTag(proof);
            overwriteSave = true;
            return true;
        });
        shot("sulfur-source-save-frame", "sulfur-server-ready.nbt");
        add("write-author-export-final", (mc, screen, ui) -> {
            require(newSave && overwriteSave && processingPresetSave, "all actual save flows acknowledged");
            HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(localPath(mc, localId));
            byte[] bytes = artifact.json;
            NBTTagCompound result = finalReceipt(mc, ui);
            result.setBoolean("processingComplete", data().getBoolean("blueprintProcessingComplete"));
            result.setString("intent", intent);
            result.setBoolean("newSave", newSave);
            result.setBoolean("overwriteSave", overwriteSave);
            result.setBoolean("processingPresetSave", processingPresetSave);
            result.setString("processingPresetId", presetId);
            result.setString("localId", localId);
            result.setByteArray("exportedJson", bytes);
            result.setString("exportedJsonSha256", artifact.jsonSha256);
            result.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            result.setByteArray("exportedPng", artifact.png);
            result.setString("exportedPngSha256", artifact.pngSha256);
            result.setInteger("previewWidth", artifact.width);
            result.setInteger("previewHeight", artifact.height);
            result.setString("newSaveJsonSha256", originalFileHash);
            result.setString("newSavePngSha256", originalPreviewHash);
            require(saveProofs.tagCount() == 2, "both actual source artifact saves retain native packet/render proof");
            result.setTag("actualSaveProofs", saveProofs.copy());
            result.setTag("quantumGroupingInteractions", groupInteractions.copy());
            result.setTag("quantumGroupingNodeClicks", groupNodeClicks.copy());
            result.setString("world", ready.getString("world"));
            result.setIntArray("bounds", bounds);
            HologramV011Evidence.write("sulfur-author-final.nbt", result);
            return true;
        });
        add("wait-independent-author-acceptance", (mc, screen, ui) -> {
            NBTTagCompound accepted = HologramV011Evidence.read("sulfur-author-accepted.nbt");
            if (accepted == null) return false;
            require(accepted.getBoolean("passed"), "independent server accepted actual author saves");
            HologramV011Evidence.marker(
                "sulfur-author-pass.txt",
                "PASS actual author GUI, six explicit stages, new save/overwrite/preset ACK and END frames");
            finished = true;
            mc.shutdown();
            return true;
        });
    }

    private void quantumGrouping() {
        click("native-open-quantum-grouping-preview", 263);
        groupAction("default-one-group-real-preview", group -> {
            NBTTagCompound ui = group.smokeUiState();
            require(
                ui.getInteger("nodeCount") == 7 && ui.getInteger("groupCount") == 1
                    && ui.getInteger("unassigned") == 0
                    && ui.getBoolean("saveEnabled"),
                "default actual preview assigns all seven directly attached physical gateways to one named network");
        });
        groupShot("sulfur-quantum-groups-default-frame");
        if ("share".equals(intent)) {
            groupAction("native-T-selects-all-seven", group -> {
                group.smokeKey(Keyboard.KEY_T, false);
                nativeKeyEvents++;
                require(
                    group.smokeUiState()
                        .getIntArray("selection").length == 7,
                    "native T selects all actual nodes");
            });
            groupAction("native-CtrlT-complement-clears-all", group -> {
                group.smokeKey(Keyboard.KEY_T, true);
                nativeKeyEvents++;
                require(
                    group.smokeUiState()
                        .getIntArray("selection").length == 0,
                    "native Ctrl T chooses the complement");
            });
            groupAction("native-preview-Ctrl-node-multiselect", group -> {
                NBTTagList nodes = group.smokeUiState()
                    .getTagList("nodes", 10);
                int selected = 0;
                for (int index = 0; index < nodes.tagCount(); index++) if (nodes.getCompoundTagAt(index)
                    .getInteger("x") < 5) {
                        nativeGroupNodeClick(group, index, selected > 0);
                        selected++;
                        nativeMouseEvents++;
                    }
                require(selected == 3, "actual preview exposes all three first-reactor ME nodes");
                require(
                    group.smokeUiState()
                        .getIntArray("selection").length == 3,
                    "actual preview hit regions and Ctrl select the three front-stage nodes");
            });
            groupShot("sulfur-quantum-groups-multiselect-frame");
            groupAction("native-unassigned-gates-save", group -> {
                group.smokeClickControl(7);
                nativeMouseEvents++;
                require(
                    group.smokeUiState()
                        .getInteger("unassigned") == 3
                        && !group.smokeUiState()
                            .getBoolean("saveEnabled"),
                    "three unclassified nodes visibly block the real Save control");
            });
            groupShot("sulfur-quantum-groups-unassigned-frame");
            groupAction("native-CtrlZ-restores-complete-grouping", group -> {
                group.smokeKey(Keyboard.KEY_Z, true);
                nativeKeyEvents++;
                require(
                    group.smokeUiState()
                        .getInteger("unassigned") == 0 && group.smokeUiState()
                            .getBoolean("saveEnabled"),
                    "native undo restores complete actual assignment");
            });
            groupAction("native-CtrlY-restores-incomplete-gate", group -> {
                group.smokeKey(Keyboard.KEY_Y, true);
                nativeKeyEvents++;
                require(
                    group.smokeUiState()
                        .getInteger("unassigned") == 3
                        && !group.smokeUiState()
                            .getBoolean("saveEnabled"),
                    "native redo restores the actual incomplete classification gate");
            });
            groupAction("native-final-complement-and-Chinese-group-names", group -> {
                group.smokeKey(Keyboard.KEY_Z, true);
                group.smokeKey(Keyboard.KEY_T, true);
                nativeKeyEvents += 2;
                require(
                    group.smokeUiState()
                        .getIntArray("selection").length == 4,
                    "final complement retains the four later-stage ME nodes");
                group.smokeName("前段供料");
                group.smokeClickControl(10);
                nativeMouseEvents += 2;
            });
            groupAction("native-new-back-stage-group", group -> {
                group.smokeName("后段供料");
                group.smokeClickControl(5);
                nativeMouseEvents += 2;
                require(
                    group.smokeUiState()
                        .getInteger("groupCount") == 2
                        && group.smokeUiState()
                            .getInteger("unassigned") == 0,
                    "Chinese native text input creates a second complete actual group");
            });
            groupShot("sulfur-quantum-groups-two-named-frame");
        }
        groupAction("native-save-complete-quantum-classification", group -> {
            require(
                group.smokeUiState()
                    .getBoolean("saveEnabled"),
                "all actual nodes assigned before classification submit");
            group.smokeClickControl(1);
            nativeMouseEvents++;
        });
        screen("quantum-classification-real-netty-ack", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !data().getCompoundTag("processing")
                .getBoolean("quantumGroupsConfirmed")) return false;
            require(
                data().getBoolean("quantumGroupsOperationSuccess")
                    && "configureBlueprintQuantumGroups".equals(data().getString("processingPresetOperation"))
                    && ui.getInteger("flowStage") == 2
                    && confirmed(flow(screen).policy()) == 2
                    && ui.getBoolean("flowQuantumGroupsConfirmed")
                    && ui.getBoolean("flowChosen")
                    && enabled(ui, 231),
                "only the actual complete classification ACK enables stage-2 Next while leaving it unconfirmed");
            NBTTagList groups = data().getCompoundTag("processing")
                .getTagList("quantumGroups", 10);
            require(
                groups.tagCount() == ("share".equals(intent) ? 2 : 1),
                "server acknowledged this tendency's actual complete classification");
            return true;
        });
        shot("sulfur-quantum-groups-save-ack-frame", "sulfur-server-ready.nbt");
    }

    private void groupAction(String label, GroupAction action) {
        add(label, (mc, screen, ui) -> {
            if (!(mc.currentScreen instanceof HologramQuantumGroupsScreen)) return false;
            HologramQuantumGroupsScreen group = (HologramQuantumGroupsScreen) mc.currentScreen;
            NBTTagCompound before = group.smokeUiState();
            if (before.getLong("renderGeneration") <= lastGroupGeneration) return false;
            NBTTagCompound proof = new NBTTagCompound();
            proof.setString("action", label);
            proof.setTag("before", before);
            groupInteractions.appendTag(proof);
            try {
                action.run(group);
            } finally {
                proof.setTag("after", group.smokeUiState());
                proof.setLong("nativeMouseEvents", nativeMouseEvents);
                proof.setLong("nativeKeyEvents", nativeKeyEvents);
            }
            lastGroupGeneration = before.getLong("renderGeneration");
            return true;
        });
    }

    private void nativeGroupNodeClick(HologramQuantumGroupsScreen group, int index, boolean control) throws Exception {
        NBTTagCompound before = group.smokeUiState();
        int cell = before.getTagList("nodes", 10)
            .getCompoundTagAt(index)
            .getInteger("cell");
        float scale = (Float) field(group, "scale");
        int ox = (Integer) field(group, "originX"), oy = (Integer) field(group, "originY");
        int vx = (Integer) field(group, "VX"), vy = (Integer) field(group, "VY");
        int vw = (Integer) field(group, "VW"), vh = (Integer) field(group, "VH");
        int[] legacy = group.smokeNodePoint(index);
        int legacyX = (int) ((legacy[0] - ox) / scale), legacyY = (int) ((legacy[1] - oy) / scale);
        int sx = -1, sy = -1;
        for (int y = Math.max(0, oy + Math.round(vy * scale)); y < group.height && sy < 0; y++) {
            int localY = (int) ((y - oy) / scale);
            if (localY < vy || localY >= vy + vh) continue;
            for (int x = Math.max(0, ox + Math.round(vx * scale)); x < group.width; x++) {
                int localX = (int) ((x - ox) / scale);
                if (localX < vx || localX >= vx + vw) continue;
                if (HologramRenderer.pick(localX, localY) == cell) {
                    sx = x;
                    sy = y;
                    break;
                }
            }
        }
        require(sx >= 0 && sy >= 0, "actual integer GUI pixels expose the requested 3D node");
        int lx = (int) ((sx - ox) / scale), ly = (int) ((sy - oy) / scale);
        NBTTagCompound proof = new NBTTagCompound();
        proof.setInteger("nodeIndex", index);
        proof.setInteger("requestedCell", cell);
        proof.setInteger("actualPickedCell", HologramRenderer.pick(lx, ly));
        proof.setIntArray("screenPoint", new int[] { sx, sy });
        proof.setIntArray("localPoint", new int[] { lx, ly });
        proof.setIntArray("legacyScreenPoint", legacy);
        proof.setInteger("legacyRoundTripPick", HologramRenderer.pick(legacyX, legacyY));
        proof.setFloat("guiScale", scale);
        proof.setIntArray("guiOrigin", new int[] { ox, oy });
        proof.setIntArray("guiSize", new int[] { group.width, group.height });
        proof.setLong("renderGeneration", before.getLong("renderGeneration"));
        proof.setBoolean("control", control);
        proof.setIntArray("selectionBefore", before.getIntArray("selection"));
        groupNodeClicks.appendTag(proof);
        key(Keyboard.KEY_LCONTROL, control);
        try {
            group.mouseClicked(sx, sy, 0);
        } finally {
            key(Keyboard.KEY_LCONTROL, false);
            proof.setIntArray(
                "selectionAfter",
                group.smokeUiState()
                    .getIntArray("selection"));
        }
        int[] after = proof.getIntArray("selectionAfter");
        require(
            proof.getInteger("actualPickedCell") == cell && Arrays.stream(after)
                .anyMatch(value -> value == index)
                && after.length == (control ? before.getIntArray("selection").length + 1 : 1),
            "actual scaled preview click selects its requested node and preserves native Ctrl selection");
        if (control) for (int previous : before.getIntArray("selection")) require(
            Arrays.stream(after)
                .anyMatch(value -> value == previous),
            "native Ctrl click retains each earlier selected node");
    }

    private void groupShot(String label) {
        add(label, (mc, screen, ui) -> {
            if (!(mc.currentScreen instanceof HologramQuantumGroupsScreen)) return false;
            HologramQuantumGroupsScreen group = (HologramQuantumGroupsScreen) mc.currentScreen;
            requestFrame(mc, null, label, "sulfur-server-ready.nbt");
            captureScreen = group;
            capture.setBoolean("quantumGroupingScreen", true);
            NBTTagCompound state = group.smokeUiState();
            capture.setTag("requestedUi", state.copy());
            capture.setLong("requestedAfterGeneration", state.getLong("renderGeneration"));
            capture.setLong("planRevision", data().getLong("planRevision"));
            return true;
        });
    }

    private void placer() {
        if ("self".equals(intent)) {
            add("private-native-inventory-drops-bootstrap-terminals", (mc, screen, ui) -> {
                NBTTagCompound target = HologramV011Evidence.read("sulfur-target-ready.nbt");
                if (!connected(mc) || target == null) return false;
                validateActor(mc, target);
                mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
                require(
                    mc.thePlayer.openContainer == mc.thePlayer.inventoryContainer,
                    "native private inventory container");
                for (int slot : new int[] { 1, 35 }) {
                    net.minecraft.item.ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
                    require(isQuantumTerminal(stack), "real bootstrap terminal precedes native inventory drop");
                    NBTTagCompound drop = new NBTTagCompound();
                    drop.setInteger("inventorySlot", slot);
                    drop.setInteger("containerSlot", slot < 9 ? slot + 36 : slot);
                    drop.setInteger("windowId", mc.thePlayer.inventoryContainer.windowId);
                    drop.setInteger("mouseButton", 1);
                    drop.setInteger("clickMode", 4);
                    drop.setTag("before", stack.writeToNBT(new NBTTagCompound()));
                    drop.setTag("clientState", placementClientState(mc));
                    mc.playerController
                        .windowClick(drop.getInteger("windowId"), drop.getInteger("containerSlot"), 1, 4, mc.thePlayer);
                    nativeMouseEvents++;
                    privateTerminalDrops.appendTag(drop);
                }
                NBTTagCompound request = (NBTTagCompound) target.copy();
                request.setString("phase", "PRIVATE_NATIVE_TERMINALS_DROPPED");
                request.setTag("nativeDrops", privateTerminalDrops.copy());
                HologramV011Evidence.write("sulfur-private-no-terminal-request.nbt", request);
                return true;
            });
            add("private-native-inventory-drop-server-ack", (mc, screen, ui) -> {
                privateNoTerminal = HologramV011Evidence.read("sulfur-private-no-terminal-ack.nbt");
                if (privateNoTerminal == null) return false;
                validateActor(mc, privateNoTerminal);
                require(
                    privateNoTerminal.getBoolean("noQuantumTerminal") && privateTerminalDrops.tagCount() == 2,
                    "server confirms both real C0E drops before private placement without terminal");
                for (net.minecraft.item.ItemStack stack : mc.thePlayer.inventory.mainInventory)
                    require(!isQuantumTerminal(stack), "actual client private inventory contains no quantum terminal");
                mc.thePlayer.closeScreen();
                return true;
            });
        }
        add("recipient-ready-import-actual-export", (mc, screen, ui) -> {
            ready = HologramV011Evidence.read("sulfur-target-ready.nbt");
            if (!connected(mc) || ready == null) return false;
            validateActor(mc, ready);
            require(mc.thePlayer.dimension == ready.getInteger("dimension"), "actual target dimension");
            targetOrigin = ready.getIntArray("targetOrigin");
            require(targetOrigin.length == 3, "actual target origin");
            bounds = ready.getIntArray("bounds");
            require(bounds.length == 6, "actual target bounds");
            byte[] bytes = ready.getByteArray("exportedJson");
            byte[] png = ready.getByteArray("exportedPng");
            require(
                bytes.length > 0 && png.length > 0
                    && ready.getInteger("artifactVersion") == HologramBlueprintArtifact.VERSION,
                "exact author JSON/PNG pair is supplied by target server transfer contract");
            NBTTagCompound transfer = HologramV011Evidence.read("sulfur-author-transfer.nbt");
            require(
                transfer != null && transfer.getBoolean("passed")
                    && Arrays.equals(bytes, transfer.getByteArray("exportedJson"))
                    && Arrays.equals(png, transfer.getByteArray("exportedPng"))
                    && hash(bytes).equals(transfer.getString("exportedSha256"))
                    && hash(png).equals(transfer.getString("exportedPngSha256"))
                    && hash(png).equals(ready.getString("exportedPngSha256"))
                    && transfer.getInteger("artifactVersion") == HologramBlueprintArtifact.VERSION
                    && ready.getInteger("previewWidth") == HologramBlueprintArtifact.WIDTH
                    && ready.getInteger("previewHeight") == HologramBlueprintArtifact.HEIGHT,
                "recipient imports exact source-generated JSON/PNG bytes and independent digests");
            require(
                !ready.getString("world")
                    .equals(transfer.getString("sourceWorld")),
                "recipient uses a different actual server save");
            JsonObject blueprint = blueprint(bytes);
            require(
                blueprint.has("authorUuid") && !mc.thePlayer.getUniqueID()
                    .toString()
                    .equals(
                        blueprint.get("authorUuid")
                            .getAsString()),
                "recipient profile differs from actual author");
            Thread worker = new Thread(() -> {
                try {
                    HologramClientLibrary store = new HologramClientLibrary(mc.mcDataDir.toPath());
                    String id = store.save(bytes, png, "");
                    HologramBlueprintArtifact.Artifact written = HologramBlueprintArtifact.decode(store.artifact(id));
                    require(
                        Arrays.equals(bytes, written.json) && Arrays.equals(png, written.png),
                        "native pair import preserves exact JSON, preview pixels, author and version metadata");
                    mc.func_152344_a(() -> { importId = id; });
                } catch (Throwable failure) {
                    mc.func_152344_a(() -> { importFailure = failure; });
                }
            }, "GTMB sulfur native client import");
            worker.setDaemon(true);
            worker.start();
            return true;
        });
        add("native-import-complete-open-projector", (mc, screen, ui) -> {
            if (importFailure != null) throw new IllegalStateException("native recipient import failed", importFailure);
            if (importId == null) return false;
            localId = importId;
            imported = true;
            mc.displayGuiScreen(null);
            press.begin(mc, false);
            mc.objectMouseOver = null;
            mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
            return true;
        });
        screen("normal-projector-open-netty-ack", (mc, screen, ui) -> {
            require(
                HologramLibraryClient.dedicated() && ui.getBoolean("dedicated"),
                "normal dedicated recipient screen");
            session = HologramClient.state.session;
            return true;
        });
        click("native-open-recipient-blueprint-tab", 149);
        screen("native-select-imported-library-file", (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            int index = localIndex(ui, localId);
            if (index < 0) return false;
            nativeClick(screen, 14000 + index);
            return true;
        });
        screen("actual-import-stage-preview-netty-ack", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !localId.equals(data().getString("libraryLocalId"))
                || !data().getBoolean("blueprintPreview")) return false;
            // Native previewPreset opens a new editing Session; retain its actual navigation ACK identity.
            session = HologramClient.state.session;
            return true;
        });
        shot("sulfur-recipient-load-frame", "sulfur-target-ready.nbt");
        click("native-place-imported-line", 141);
        add(
            "actual-moving-preview-netty-ack",
            (mc, screen, ui) -> HologramClient.state != null && mc.currentScreen == null
                && data().getBoolean("virtualPlacement")
                && !data().getBoolean("virtualLocked"));
        add("native-world-first-right-lock", (mc, screen, ui) -> {
            mc.objectMouseOver = new MovingObjectPosition(
                targetOrigin[0],
                targetOrigin[1] - 1,
                targetOrigin[2],
                1,
                Vec3.createVectorHelper(targetOrigin[0] + .5, targetOrigin[1], targetOrigin[2] + .5));
            press.begin(mc, true);
            useBlock(mc, targetOrigin[0], targetOrigin[1] - 1, targetOrigin[2], 1);
            return true;
        });
        add("actual-world-lock-ack", (mc, screen, ui) -> {
            if (!data().getBoolean("virtualLocked")) return false;
            require(
                mc.currentScreen == null && Arrays.equals(
                    targetOrigin,
                    new int[] { data().getInteger("x"), data().getInteger("y"), data().getInteger("z") }),
                "real first click locks exact target in world");
            return true;
        });
        add("finite-materials-supplied-before-review-refresh", (mc, screen, ui) -> {
            NBTTagCompound materials = HologramV011Evidence.read("sulfur-materials-ready.nbt");
            if (materials == null) return false;
            validateActor(mc, materials);
            require(
                "MATERIALS_SUPPLIED".equals(materials.getString("phase"))
                    && HologramClient.state.session.equals(materials.getString("session")),
                "finite real fixture inventory belongs to actual locked placement session");
            return true;
        });
        add("native-second-right-review", (mc, screen, ui) -> {
            press.begin(mc, false);
            mc.objectMouseOver = null;
            mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
            return true;
        });
        screen("actual-review-netty-ack", (mc, screen, ui) -> {
            if (!idle(screen, ui) || !data().getBoolean("blueprintAwaitingConfirmation")
                || !ui.getBoolean("confirmation")) return false;
            require(
                data().getBoolean("virtualLocked") && data().getLong("placementReviewNonce") > 0,
                "normal placement review has authoritative lock and nonce");
            return true;
        });
        if ("self".equals(intent)) {
            screen("native-private-preserved-anchor-rejection", (mc, screen, ui) -> {
                if (!idle(screen, ui)) return false;
                NBTTagList groups = data().getCompoundTag("networkBindings")
                    .getTagList("groups", 10);
                require(groups.tagCount() >= 1, "private blueprint retained its original quantum network descriptor");
                for (int i = 0; i < groups.tagCount(); i++) {
                    NBTTagCompound group = groups.getCompoundTagAt(i);
                    require(
                        !group.getBoolean("terminalBinding") && !group.getBoolean("bound")
                            && group.getString("reason")
                                .contains("其他存档"),
                        "foreign-world private anchor is explicitly unavailable and cannot be rebound from new terminals");
                }
                require(!enabled(ui, 55), "private foreign-world construction is visibly disabled");
                return true;
            });
            shot("sulfur-recipient-preserved-anchor-blocked-frame", "sulfur-materials-ready.nbt");
            add("write-private-rejection-final", (mc, screen, ui) -> {
                NBTTagCompound result = finalReceipt(mc, ui);
                result.setBoolean("preservedAnchorRejected", true);
                result.setTag("privateNoTerminal", privateNoTerminal.copy());
                result.setTag("privateNativeTerminalDrops", privateTerminalDrops.copy());
                result.setBoolean("nativeImport", imported);
                result.setString("intent", intent);
                result.setString("localId", localId);
                result.setTag("rejectionSnapshot", data().copy());
                HologramV011Evidence.write("sulfur-placer-final.nbt", result);
                HologramV011Evidence.marker(
                    "sulfur-placer-pass.txt",
                    "PASS actual private original-world anchor rejection; no rebind or construction");
                finished = true;
                mc.shutdown();
                return true;
            });
            return;
        }
        screen("native-open-quantum-binding-picker", (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            if (!ui.getBoolean("networkDrawer")) {
                require(enabled(ui, 196), "actual review exposes quantum binding picker");
                nativeClick(screen, 196);
            }
            return true;
        });
        for (int bindingIndex = 0; bindingIndex < 2; bindingIndex++) {
            final int groupIndex = bindingIndex;
            screen("actual-native-terminal-picker-group-" + groupIndex, (mc, screen, ui) -> {
                if (!idle(screen, ui) || !ui.getBoolean("networkDrawer")) return false;
                NBTTagList groups = data().getCompoundTag("networkBindings")
                    .getTagList("groups", 10);
                require(groups.tagCount() == 2, "actual shared sulfur line has two author-named quantum groups");
                require(
                    groups.getCompoundTagAt(groupIndex)
                        .getBoolean("terminalBinding"),
                    "shared group requires own terminal");
                require(enabled(ui, 61035), "server verified real recipient quantum terminal in slot 35");
                nativeClick(screen, 60000 + groupIndex);
                return true;
            });
            shot("sulfur-recipient-quantum-picker-group-" + groupIndex + "-frame", "sulfur-materials-ready.nbt");
            click("native-bind-quantum-group-" + groupIndex + "-from-real-terminal", 61035);
            screen("quantum-binding-group-" + groupIndex + "-real-netty-ack", (mc, screen, ui) -> {
                if (!idle(screen, ui)) return false;
                NBTTagList groups = data().getCompoundTag("networkBindings")
                    .getTagList("groups", 10);
                if (groups.tagCount() != 2 || !groups.getCompoundTagAt(groupIndex)
                    .getBoolean("bound")) return false;
                if (groupIndex == 0) require(
                    !groups.getCompoundTagAt(1)
                        .getBoolean("bound") && !enabled(ui, 197),
                    "one group binding cannot authorize construction or silently bind the other group");
                require(
                    screen.retainsPlacementReview(HologramClient.state),
                    "native binding ACK retains actual placement review capability");
                return true;
            });
            shot("sulfur-recipient-quantum-binding-group-" + groupIndex + "-ack-frame", "sulfur-materials-ready.nbt");
        }
        click("native-close-quantum-picker", 197);
        screen("fresh-material-review-after-binding-ack", (mc, screen, ui) -> {
            if (!idle(screen, ui) || ui.getBoolean("networkDrawer") || !ui.getBoolean("confirmation")) return false;
            require(data().getInteger("missing") == 0, "fresh production quote sees finite actual payment inventory");
            NBTTagList groups = data().getCompoundTag("networkBindings")
                .getTagList("groups", 10);
            require(
                groups.tagCount() == 2 && groups.getCompoundTagAt(0)
                    .getBoolean("bound")
                    && groups.getCompoundTagAt(1)
                        .getBoolean("bound"),
                "fresh actual quote retains both independently selected terminal bindings");
            require(enabled(ui, 55), "normal reviewed construction control is enabled");
            return true;
        });
        shot("sulfur-recipient-construct-review-frame", "sulfur-target-ready.nbt");
        click("native-confirm-paid-construction", 55);
        add("actual-target-construction-complete", (mc, screen, ui) -> {
            if (HologramClient.state == null || data().getInteger("job") == 1 || data().getInteger("job") == 2)
                return false;
            runtime = HologramV011Evidence.read("sulfur-target-running.nbt");
            if (runtime == null) return false;
            validateActor(mc, runtime);
            require("TARGET_RUNNING".equals(runtime.getString("phase")), "independent native target runtime receipt");
            require(
                session.equals(runtime.getString("session")) && session.equals(HologramClient.state.session),
                "actual paid target runtime retains the loaded blueprint's acknowledged editing session");
            return true;
        });
        screen("native-close-completed-projector", (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            require(data().getInteger("job") == 3, "actual paid construction completed before native Escape");
            screen.keyTyped((char) 27, Keyboard.KEY_ESCAPE);
            require(mc.currentScreen == null, "native Escape closes the completed projector to the world");
            closeSequence = (Long) field(screen, "sequence");
            require(closeSequence > data().getLong("uiSequence"), "native Escape dispatched the real close operation");
            nativeKeyEvents++;
            return true;
        });
        add("target-running-native-frame", (mc, screen, ui) -> {
            if (data().getLong("uiSequence") < closeSequence) return false;
            require(
                closeSequence >= 0 && mc.currentScreen == null
                    && session.equals(HologramClient.state.session)
                    && data().getBoolean("virtualLocked")
                    && !data().getBoolean("moving")
                    && data().getInteger("job") == 3,
                "real close ACK retains completed locked placement under world focus");
            aim(
                mc,
                (bounds[0] + bounds[3] + 1) / 2D,
                (bounds[1] + bounds[4] + 1) / 2D,
                (bounds[2] + bounds[5] + 1) / 2D);
            requestFrame(mc, null, "sulfur-target-running-frame", "sulfur-target-running.nbt");
            capture.setLong("nativeCloseSequence", closeSequence);
            return true;
        });
        quantumCycleFrames("target");
        add("target-nc-decor-native-frame", (mc, screen, ui) -> {
            NBTTagCompound monitor = HologramV011Evidence.read("sulfur-target-monitor-ready.nbt");
            if (!connected(mc) || mc.currentScreen != null || monitor == null) return false;
            validateActor(mc, monitor);
            require("TARGET_MONITOR_VIEW".equals(monitor.getString("phase")), "native monitor/decor view phase");
            require(
                HologramClient.state != null && HologramClient.state.session.equals(monitor.getString("session")),
                "native monitor view belongs to actual placement session");
            require(
                mc.thePlayer.dimension == monitor.getInteger("dimension"),
                "native monitor view uses actual target dimension");
            require(
                ready.getString("world")
                    .equals(monitor.getString("world")),
                "native monitor view uses actual target save");
            int[] camera = monitor.getIntArray("cameraPosition");
            require(camera.length == 3, "server supplies actual monitor camera position");
            if (mc.thePlayer.getDistanceSq(camera[0] + .5, camera[1], camera[2] + .5) > 16) return false;
            int[] panel = findNativePanel(mc);
            aim(mc, panel[0] + .5, panel[1] + .5, panel[2] + .01);
            requestFrame(mc, null, "sulfur-target-nc-decor-frame", "sulfur-target-monitor-ready.nbt");
            capture.setIntArray("expectedCameraPosition", camera);
            capture.setIntArray("nativePanel", panel);
            capture.setString("nativePanelFace", "NORTH");
            return true;
        });
        add("target-decor-native-frame", (mc, screen, ui) -> {
            NBTTagCompound monitor = HologramV011Evidence.read("sulfur-target-monitor-ready.nbt");
            if (!connected(mc) || mc.currentScreen != null || monitor == null) return false;
            validateActor(mc, monitor);
            require(
                "TARGET_MONITOR_VIEW".equals(monitor.getString("phase")) && HologramClient.state != null
                    && HologramClient.state.session.equals(monitor.getString("session")),
                "native decoration gallery view belongs to actual target placement session");
            int[] camera = monitor.getIntArray("cameraPosition");
            require(camera.length == 3, "server supplies actual decoration gallery camera position");
            if (mc.thePlayer.getDistanceSq(camera[0] + .5, camera[1], camera[2] + .5) > 16) return false;
            aim(mc, targetOrigin[0] + 4.5, targetOrigin[1] + .5, targetOrigin[2] + 8.5);
            requestFrame(mc, null, "sulfur-target-decor-frame", "sulfur-target-monitor-ready.nbt");
            capture.setIntArray("expectedCameraPosition", camera);
            capture.setString("view", "native decoration gallery from north");
            return true;
        });
        add("write-recipient-final", (mc, screen, ui) -> {
            HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(localPath(mc, localId));
            require(
                Arrays.equals(ready.getByteArray("exportedJson"), artifact.json)
                    && Arrays.equals(ready.getByteArray("exportedPng"), artifact.png),
                "actual recipient pair still equals this source transfer after paid placement");
            NBTTagCompound result = finalReceipt(mc, ui);
            result.setBoolean("nativeImport", imported);
            result.setString("localId", localId);
            result.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            result.setString("importedJsonSha256", artifact.jsonSha256);
            result.setString("importedPngSha256", artifact.pngSha256);
            result.setInteger("previewWidth", artifact.width);
            result.setInteger("previewHeight", artifact.height);
            result.setString("world", ready.getString("world"));
            result.setInteger("dimension", mc.thePlayer.dimension);
            result.setIntArray("targetOrigin", targetOrigin);
            result.setTag("nativeRuntime", runtime.copy());
            HologramV011Evidence.write("sulfur-placer-final.nbt", result);
            HologramV011Evidence.marker(
                "sulfur-placer-pass.txt",
                "PASS actual recipient import, normal Netty stage, native lock/review/construction and target END frames");
            finished = true;
            mc.shutdown();
            return true;
        });
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.currentScreen instanceof HologramQuantumGroupsScreen) enteredQuantumGroupingScreen = true;
            if (mc.theWorld != null && press.tick(event, mc)) return;
            if (event.phase != TickEvent.Phase.END) return;
            require(System.nanoTime() - installed < 900000000000L, "actual sulfur role exceeded fifteen minutes");
            require(System.nanoTime() - stepStarted < 180000000000L, "native sulfur stage/ACK timeout " + stepLabel());
            require(
                !Files.isRegularFile(
                    HologramV011Evidence.directory()
                        .resolve("sulfur-server-fail.txt")),
                "paired server has no failure");
            if (capture != null) return;
            HologramScreen screen = mc.currentScreen instanceof HologramScreen ? (HologramScreen) mc.currentScreen
                : null;
            NBTTagCompound ui = screen == null ? null : screen.smokeLibraryUiState();
            if (screen != null) {
                require(!(Boolean) field(screen, "captureRenderSmokeAction"), "actual normal Netty dispatch enabled");
                if (renderedScreen != screen || renderedState != HologramClient.state
                    || renderedGeneration != ui.getLong("renderGeneration")) return;
                if (screen == lastScreen && ui.getLong("renderGeneration") <= lastGeneration) return;
            }
            boolean advanced = steps.get(step).action.run(mc, screen, ui);
            lastScreen = screen;
            lastGeneration = screen == null ? -1
                : screen.smokeLibraryUiState()
                    .getLong("renderGeneration");
            if (advanced) {
                System.out.println("[GTMB-SULFUR-CLIENT] " + role + " native step=" + step + " " + stepLabel());
                step++;
                stepStarted = System.nanoTime();
            }
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void rendered(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            renderedScreen = mc.currentScreen instanceof HologramScreen ? (HologramScreen) mc.currentScreen : null;
            renderedState = HologramClient.state;
            renderedGeneration = renderedScreen == null ? -1
                : renderedScreen.smokeLibraryUiState()
                    .getLong("renderGeneration");
            if (capture == null || !connected(mc) || mc.currentScreen != captureScreen) return;
            require(
                mc.thePlayer.getUniqueID()
                    .toString()
                    .equals(capture.getString("actor")),
                "END frame actor remains actual player");
            require(
                mc.thePlayer.dimension == capture.getInteger("dimension"),
                "END frame dimension remains actual world");
            String actualSession = HologramClient.state == null ? "" : HologramClient.state.session;
            require(actualSession.equals(capture.getString("session")), "END frame retains requested real session");
            NBTTagCompound ui = captureScreen instanceof HologramScreen
                ? ((HologramScreen) captureScreen).smokeLibraryUiState()
                : captureScreen instanceof HologramQuantumGroupsScreen
                    ? ((HologramQuantumGroupsScreen) captureScreen).smokeUiState()
                    : new NBTTagCompound();
            if (capture.getBoolean("quantumGroupingScreen")) {
                if (ui.getLong("renderGeneration") <= capture.getLong("requestedAfterGeneration")) return;
                NBTTagCompound requested = capture.getCompoundTag("requestedUi");
                require(
                    ui.getTagList("groups", 10)
                        .equals(requested.getTagList("groups", 10))
                        && Arrays.equals(ui.getIntArray("selection"), requested.getIntArray("selection"))
                        && ui.getBoolean("saveEnabled") == requested.getBoolean("saveEnabled")
                        && ui.getInteger("unassigned") == requested.getInteger("unassigned"),
                    "actual independent grouping END frame retains real assignments, selection and Save gate");
            } else if (captureScreen != null) {
                if (ui.getLong("renderGeneration") <= capture.getLong("requestedAfterGeneration")) return;
                NBTTagCompound requested = capture.getCompoundTag("requestedUi");
                for (String mode : new String[] { "processingFlow", "flowChosen", "blueprintTab", "confirmation",
                    "overwriteReview", "processingPresetSaveReview", "networkDrawer", "flowFullReview" })
                    require(
                        ui.getBoolean(mode) == requested.getBoolean(mode),
                        "END frame retains native UI mode " + mode);
                require(
                    ui.getInteger("flowStage") == requested.getInteger("flowStage"),
                    "END frame retains exact processing stage");
                require(
                    data().getLong("planRevision") == capture.getLong("planRevision"),
                    "END frame binds real server revision");
                if (capture.hasKey("actualLocalPolicy", 10)) require(
                    flow((HologramScreen) captureScreen) != null && capture.getCompoundTag("actualLocalPolicy")
                        .equals(flow((HologramScreen) captureScreen).policy()),
                    "END frame retains exact explicit native choices");
            } else {
                ui.setBoolean("worldFocused", mc.currentScreen == null);
                HologramWorldGuide.RenderWitness guide = HologramWorldGuide.lastRenderWitness();
                if (guide != null) {
                    ui.setInteger("guideCenterX", guide.centerX);
                    ui.setInteger("guideTop", guide.top);
                    ui.setInteger("guideWidth", guide.width);
                    ui.setInteger("guideHeight", guide.height);
                    NBTTagList rows = new NBTTagList();
                    for (String row : guide.rows) rows.appendTag(new NBTTagString(row));
                    ui.setTag("actualGuideRows", rows);
                }
            }
            NBTTagCompound server = HologramV011Evidence.read(capture.getString("serverReceipt"));
            require(
                server != null && server.getBoolean("passed"),
                "paired server receipt still exists and passed at END");
            require(
                server.equals(capture.getCompoundTag("serverWitness")),
                "END frame pairs exact immutable runtime/capture receipt");
            String pngName = capture.getString("label") + ".png";
            ScreenShotHelper
                .saveScreenshot(mc.mcDataDir, pngName, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            Path png = mc.mcDataDir.toPath()
                .resolve("screenshots")
                .resolve(pngName);
            require(Files.isRegularFile(png) && Files.size(png) > 0, "actual END framebuffer PNG exists");
            Files.copy(
                png,
                HologramV011Evidence.directory()
                    .resolve(pngName),
                StandardCopyOption.REPLACE_EXISTING);
            NBTTagCompound frame = (NBTTagCompound) capture.copy();
            frame.setBoolean("passed", true);
            frame.setBoolean("actualFrame", true);
            frame.setBoolean("actualNettyClient", true);
            frame.setBoolean("syntheticSnapshotsUsed", false);
            frame.setString("capturePhase", "RenderTick.END");
            frame.setLong("clientWorldTick", mc.theWorld.getTotalWorldTime());
            frame.setLong("actualRenderGeneration", ui.getLong("renderGeneration"));
            frame.setInteger("framebufferObject", mc.getFramebuffer().framebufferObject);
            frame.setString("png", pngName);
            frame.setString("pngSha256", hash(Files.readAllBytes(png)));
            frame.setString("actualUiSha256", hash(CompressedStreamTools.compress(ui)));
            frame.setTag("actualUi", ui.copy());
            if (HologramClient.state != null) {
                frame.setString("snapshotSha256", hash(CompressedStreamTools.compress(data())));
                frame.setTag("actualSnapshot", data().copy());
                frame.setInteger("stateObjectIdentity", System.identityHashCode(HologramClient.state));
            }
            frame.setFloat("cameraYaw", mc.thePlayer.rotationYaw);
            frame.setFloat("cameraPitch", mc.thePlayer.rotationPitch);
            frame.setDouble("cameraX", mc.thePlayer.posX);
            frame.setDouble("cameraY", mc.thePlayer.posY);
            frame.setDouble("cameraZ", mc.thePlayer.posZ);
            HologramV011Evidence.write(capture.getString("label") + ".nbt", frame);
            frames.appendTag(new NBTTagString(capture.getString("label") + ".nbt"));
            capture = null;
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    private void requestFrame(Minecraft mc, HologramScreen screen, String label, String serverReceipt)
        throws Exception {
        require(capture == null, "one actual END frame capture at a time");
        NBTTagCompound server = HologramV011Evidence.read(serverReceipt);
        require(server != null && server.getBoolean("passed"), "actual frame has independent server evidence");
        captureScreen = screen;
        capture = new NBTTagCompound();
        capture.setString("label", label);
        capture.setString(
            "actor",
            mc.thePlayer.getUniqueID()
                .toString());
        capture.setInteger("dimension", mc.thePlayer.dimension);
        capture.setString("role", role);
        capture.setString("session", HologramClient.state == null ? "" : HologramClient.state.session);
        capture.setString("serverReceipt", serverReceipt);
        capture.setTag("serverWitness", server.copy());
        capture.setString("serverReceiptSha256", hash(CompressedStreamTools.compress(server)));
        capture.setString("runtimePhase", server.getString("phase"));
        capture.setString("world", server.getString("world"));
        capture.setLong("nativeMouseEvents", nativeMouseEvents);
        capture.setLong("nativeKeyEvents", nativeKeyEvents);
        capture.setLong("nativeWheelEvents", nativeWheelEvents);
        if (screen != null) {
            NBTTagCompound ui = screen.smokeLibraryUiState();
            capture.setTag("requestedUi", ui.copy());
            capture.setLong("requestedAfterGeneration", ui.getLong("renderGeneration"));
            capture.setLong("planRevision", data().getLong("planRevision"));
            if (flow(screen) != null) capture.setTag("actualLocalPolicy", flow(screen).policy());
        }
    }

    private boolean awaitSave(Minecraft mc, HologramScreen screen, NBTTagCompound ui) throws Exception {
        if (ui.getBoolean("aeReview")) {
            NBTTagCompound report = data().getCompoundTag("aeSaveReview");
            saveReviewEvidence.setTag("actualReport", report.copy());
            saveReviewEvidence.setTag("actualUi", ui.copy());
            HologramAeReviewModel review = (HologramAeReviewModel) field(screen, "aeReview");
            require(
                review != null && review.review
                    && review.valid
                    && review.matches(HologramClient.state.session, data().getLong("planRevision"))
                    && review.transactionId.equals(report.getString("transactionId"))
                    && expectedName.equals(review.name),
                "actual AE save report belongs to the current named draft and revision");
            require(
                review.blockerCount == 0 && report.getInteger("blockerCount") == 0,
                "actual AE save report blocks saving: " + saveReviewBlockers(report));
            require(review.canConfirm && report.getBoolean("canConfirm"), "actual AE save report permits confirmation");
            if (screen.smokePending()) return false;
            require(!saveReviewConfirmed && enabled(ui, 181), "one actual visible enabled AE confirmation control");
            if (saveProofs.tagCount() > 0) require(
                !report.getString("transactionId")
                    .equals(
                        saveProofs.getCompoundTagAt(0)
                            .getCompoundTag("actualAeSaveReview")
                            .getCompoundTag("actualReport")
                            .getString("transactionId")),
                "overwrite has its own actual AE review transaction");
            long beforeSequence = (Long) field(screen, "sequence");
            saveReviewEvidence.setLong("sequenceBefore", beforeSequence);
            saveReviewEvidence.setLong("nativeMouseEventsBefore", nativeMouseEvents);
            nativeClick(screen, 181);
            long confirmedSequence = (Long) field(screen, "sequence");
            boolean closed = field(screen, "aeReview") == null;
            saveReviewEvidence.setLong("confirmationSequence", confirmedSequence);
            saveReviewEvidence.setLong("nativeMouseEventsAfter", nativeMouseEvents);
            saveReviewEvidence.setBoolean("reviewClosedByNativeClick", closed);
            require(
                closed && confirmedSequence == beforeSequence + 1
                    && screen.smokePending()
                    && (Long) field(screen, "pendingSequence") == confirmedSequence,
                "native AE confirmation dismissed the actual review and dispatched its pending sequence");
            saveReviewConfirmed = true;
            return false;
        }
        if (!idle(screen, ui) || data().getBoolean("blueprintSavePending")
            || data().getBoolean("blueprintDirty")
            || !data().getBoolean("blueprintSaved")
            || !data().getBoolean("blueprintOperationSuccess")) return false;
        String saved = data().getString("libraryLocalId");
        if (!saved.equals(HologramLibraryClient.INSTANCE.selected(false))) return false;
        int row = localIndex(ui, saved);
        if (row < 0 || !expectedName.equals(
            ui.getTagList("entries", 10)
                .getCompoundTagAt(row)
                .getString("name")))
            return false;
        HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(localPath(mc, saved));
        JsonObject json = blueprint(artifact.json);
        require(
            saved.equals(
                json.get("id")
                    .getAsString())
                && expectedName.equals(
                    json.get("name")
                        .getAsString()),
            "actual disk JSON and owner save ACK agree");
        require(
            mc.thePlayer.getUniqueID()
                .toString()
                .equals(
                    json.get("authorUuid")
                        .getAsString()),
            "actual file retains original author UUID");
        NBTTagCompound proof = nativeSaveProof(artifact, saved);
        if (proof == null) return false;
        NBTTagCompound report = saveReviewEvidence.getCompoundTag("actualReport");
        long confirmationSequence = saveReviewEvidence.getLong("confirmationSequence");
        require(
            saveReviewConfirmed && !report.getString("transactionId")
                .isEmpty()
                && proof.getString("session")
                    .equals(report.getString("session"))
                && proof.getLong("planRevision") == report.getLong("planRevision")
                && data().getLong("uiSequence") >= confirmationSequence,
            "actual committed save follows this same revision's native AE review confirmation ACK");
        saveReviewEvidence.setLong("confirmationAckSequence", data().getLong("uiSequence"));
        proof.setTag("actualAeSaveReview", saveReviewEvidence.copy());
        verifiedSaveProof = proof;
        return true;
    }

    private void resetSaveReview() {
        saveReviewEvidence = new NBTTagCompound();
        saveReviewConfirmed = false;
    }

    private static String saveReviewBlockers(NBTTagCompound report) {
        StringBuilder blockers = new StringBuilder();
        NBTTagList issues = report.getTagList("issues", 10);
        for (int index = 0; index < issues.tagCount(); index++) {
            NBTTagCompound issue = issues.getCompoundTagAt(index);
            if (!"blocker".equals(issue.getString("severity"))) continue;
            if (blockers.length() > 0) blockers.append("; ");
            blockers.append(issue.getString("code"))
                .append(" at ")
                .append(issue.getString("path"))
                .append(": ")
                .append(issue.getString("message"));
        }
        return blockers.toString();
    }

    private NBTTagCompound nativeSaveProof(HologramBlueprintArtifact.Artifact artifact, String local) throws Exception {
        NBTTagCompound proof = HologramLibraryClient.actualSaveWitness();
        for (String key : new String[] { "nativePreviewWitness", "previewVerifiedReply", "ownerDiskAck",
            "saveCommittedReply" }) if (!proof.hasKey(key, 10)) return null;
        proof.setTag("actualLibrarySaveState", HologramLibraryClient.actualSaveState());
        NBTTagCompound nativePreview = proof.getCompoundTag("nativePreviewWitness");
        if (!artifact.jsonSha256.equals(nativePreview.getString("sourceJsonSha256"))) return null;
        require(
            nativePreview.equals(HologramBlueprintPreview.lastCaptureWitness())
                && nativePreview.getInteger("width") == HologramBlueprintArtifact.WIDTH
                && nativePreview.getInteger("height") == HologramBlueprintArtifact.HEIGHT
                && nativePreview.getBoolean("renderTickEnd")
                && nativePreview.getBoolean("nativeRendering")
                && nativePreview.getBoolean("readbackSucceeded")
                && nativePreview.getLong("captureGeneration") > 0
                && "RGB8_TOP_LEFT_ROW_MAJOR".equals(nativePreview.getString("rgbPacking"))
                && previewRgbHash(artifact.png).equals(nativePreview.getString("rgbSha256")),
            "actual saved PNG pixels match the source-fenced native END framebuffer readback");
        NBTTagCompound acknowledgement = proof.getCompoundTag("ownerDiskAck");
        String receipt = acknowledgement.getString("saveReceipt");
        require(!receipt.isEmpty(), "actual owner disk ACK has a save receipt");
        String[] keys = { "previewVerifiedReply", "ownerDiskAck", "saveCommittedReply" };
        String[] operations = { "previewVerified", "ackBlueprintSaved", "saveCommitted" };
        for (int index = 0; index < keys.length; index++) {
            NBTTagCompound packet = proof.getCompoundTag(keys[index]);
            require(
                operations[index].equals(packet.getString("op")) && receipt.equals(packet.getString("saveReceipt"))
                    && local.equals(packet.getString("localId"))
                    && HologramClient.state.session.equals(packet.getString("session"))
                    && data().getLong("planRevision") == packet.getLong("planRevision")
                    && packet.getInteger("artifactVersion") == HologramBlueprintArtifact.VERSION
                    && artifact.jsonSha256.equals(packet.getString("jsonSha256"))
                    && artifact.pngSha256.equals(packet.getString("previewSha256")),
                "actual native save packet belongs to this exact committed source pair: " + keys[index]);
        }
        proof.setByteArray("savedJson", artifact.json);
        proof.setByteArray("savedPng", artifact.png);
        proof.setString("savedJsonSha256", artifact.jsonSha256);
        proof.setString("savedPngSha256", artifact.pngSha256);
        proof.setString("localId", acknowledgement.getString("localId"));
        proof.setString("session", acknowledgement.getString("session"));
        proof.setLong("planRevision", acknowledgement.getLong("planRevision"));
        return proof;
    }

    private static String previewRgbHash(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        require(
            image != null && image.getWidth() == HologramBlueprintArtifact.WIDTH
                && image.getHeight() == HologramBlueprintArtifact.HEIGHT,
            "actual saved preview PNG decodes at native dimensions");
        try {
            byte[] rgb = new byte[image.getWidth() * image.getHeight() * 3];
            int offset = 0;
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getRGB(x, y);
                rgb[offset++] = (byte) (color >>> 16);
                rgb[offset++] = (byte) (color >>> 8);
                rgb[offset++] = (byte) color;
            }
            return hash(rgb);
        } finally {
            image.flush();
        }
    }

    private NBTTagCompound finalReceipt(Minecraft mc, NBTTagCompound ui) {
        require(capture == null && frames.tagCount() > 0, "all requested END frames completed");
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("passed", true);
        result.setBoolean("actualNettyClient", true);
        result.setBoolean("syntheticSnapshotsUsed", false);
        result.setBoolean("controllerMemoryWritesUsed", false);
        result.setString(
            "actor",
            mc.thePlayer.getUniqueID()
                .toString());
        result.setString("role", role);
        result.setString("session", HologramClient.state == null ? session : HologramClient.state.session);
        result.setInteger("completedNativeSteps", step);
        result.setLong("nativeMouseEvents", nativeMouseEvents);
        result.setLong("nativeKeyEvents", nativeKeyEvents);
        result.setLong("nativeWheelEvents", nativeWheelEvents);
        result.setBoolean("enteredQuantumGroupingScreen", enteredQuantumGroupingScreen);
        result.setTag("actualQuantumPlacements", quantumPlacements.copy());
        result.setTag("actualFrameReceipts", frames.copy());
        if (areaSelections.tagCount() > 0) result.setTag("actualAreaSelections", areaSelections.copy());
        if (fullReviewEvidence != null) result.setTag("actualFullReviewSelection", fullReviewEvidence.copy());
        if (HologramClient.state != null) result.setTag("actualSnapshot", data().copy());
        if (ui != null) result.setTag("actualUi", ui.copy());
        return result;
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
            require(enabled(ui, id), "native visible enabled control " + id);
            nativeClick(screen, id);
            return true;
        });
    }

    private void shot(String label, String serverReceipt) {
        screen(label, (mc, screen, ui) -> {
            requestFrame(mc, screen, label, serverReceipt);
            return true;
        });
    }

    private void type(String label, String fieldName, String value) {
        screen(label, (mc, screen, ui) -> {
            if (!idle(screen, ui)) return false;
            GuiTextField target = (GuiTextField) field(screen, fieldName);
            require(target != null, "real native text field " + fieldName);
            float scale = (Float) field(screen, "scale");
            int x = (Integer) field(screen, "originX") + Math.round((target.xPosition + 4) * scale);
            int y = (Integer) field(screen, "originY") + Math.round((target.yPosition + 7) * scale);
            screen.mouseClicked(x, y, 0);
            screen.mouseMovedOrUp(x, y, 0);
            nativeMouseEvents++;
            require(target.isFocused(), "native mouse focuses text field");
            screen.keyTyped((char) 1, Keyboard.KEY_A);
            Method injectText = null;
            Constructor<?> textEvent = null;
            try {
                Class<?> input = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents");
                Class<?> event = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents$TextEvent");
                injectText = input.getMethod("injectTextEvent", event);
                textEvent = event.getConstructor(String.class);
            } catch (ClassNotFoundException vanillaInput) {
                // Vanilla consumes the character event directly; LWJGL3 uses its native queued text event.
            }
            for (char character : value.toCharArray()) {
                if (injectText != null) injectText.invoke(null, textEvent.newInstance(String.valueOf(character)));
                screen.keyTyped(character, 0);
            }
            nativeKeyEvents += value.length() + 1;
            require(value.equals(target.getText()), "native keyboard replaced exact field value");
            return true;
        });
    }

    private void nativeClick(HologramScreen screen, int id) {
        int[] point = screen.smokeControlPoint(id);
        screen.mouseClicked(point[0], point[1], 0);
        screen.mouseMovedOrUp(point[0], point[1], 0);
        nativeMouseEvents++;
    }

    private void nativeWheel(Minecraft mc, HologramScreen screen, int delta, int x, int y) throws Exception {
        float scale = (Float) field(screen, "scale");
        int screenX = (Integer) field(screen, "originX") + Math.round(x * scale);
        int screenY = (Integer) field(screen, "originY") + Math.round(y * scale);
        int rawX = screenX * mc.displayWidth / screen.width;
        int rawY = (screen.height - screenY - 1) * mc.displayHeight / screen.height;
        String[] names = { "event_dwheel", "event_x", "event_y", "eventButton", "eventState" };
        Object[] values = { delta, rawX, rawY, -1, false };
        Object[] original = new Object[names.length];
        Field[] fields = new Field[names.length];
        try {
            for (int i = 0; i < names.length; i++) {
                fields[i] = Mouse.class.getDeclaredField(names[i]);
                fields[i].setAccessible(true);
                original[i] = fields[i].get(null);
                fields[i].set(null, values[i]);
            }
            screen.handleMouseInput();
            nativeMouseEvents++;
            nativeWheelEvents++;
        } finally {
            for (int i = 0; i < fields.length; i++)
                if (fields[i] != null && original[i] != null) fields[i].set(null, original[i]);
        }
    }

    private void corner(Minecraft mc, int button, int x, int y, int z, boolean finish) throws Exception {
        require(mc.currentScreen == null && mc.theWorld != null, "native area input has actual world focus");
        require(mc.renderViewEntity == null || mc.renderViewEntity == mc.thePlayer, "native player is the area camera");
        aim(mc, x + .5, y + .5, z + .5);
        double dx = x + .5 - mc.thePlayer.posX;
        double dy = y + .5 - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
        double dz = z + .5 - mc.thePlayer.posZ;
        int targetDistance = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        require(
            targetDistance > 0 && targetDistance <= HologramWorldFlow.MAX_DISTANCE,
            "actual source corner fits the native floating cursor distance");
        key(Keyboard.KEY_LMENU, true);
        HologramClient.areaSelectionAnchor();
        Field distanceField = HologramClient.class.getDeclaredField("previewDistance");
        distanceField.setAccessible(true);
        HologramWorldFlow.PreviewDistance distance = (HologramWorldFlow.PreviewDistance) distanceField.get(null);
        int beforeDistance = distance.distance();
        if (beforeDistance != targetDistance) {
            MouseEvent wheel = new MouseEvent();
            mouseField(wheel, "dwheel", (targetDistance - beforeDistance) * 120);
            mouseField(wheel, "button", -1);
            mouseField(wheel, "buttonstate", false);
            key(Keyboard.KEY_LMENU, true);
            try {
                MinecraftForge.EVENT_BUS.post(wheel);
            } finally {
                key(Keyboard.KEY_LMENU, true);
            }
            require(
                wheel.isCanceled() && distance.distance() == targetDistance,
                "native Alt wheel adjusts cursor distance");
            nativeMouseEvents++;
            nativeWheelEvents++;
        }
        require(
            Keyboard.isKeyDown(Keyboard.KEY_LMENU)
                && Arrays.equals(HologramClient.areaSelectionAnchor(), new int[] { x, y, z }),
            "actual Alt forced-air sight-distance cursor targets this source corner");
        NBTTagCompound selection = new NBTTagCompound();
        selection.setInteger("point", button == 0 ? 1 : 2);
        selection.setIntArray("actualCursor", HologramClient.areaSelectionAnchor());
        selection.setInteger("distanceBefore", beforeDistance);
        selection.setInteger("distance", distance.distance());
        selection.setDouble("cameraX", mc.thePlayer.posX);
        selection.setDouble("cameraY", mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        selection.setDouble("cameraZ", mc.thePlayer.posZ);
        selection.setFloat("cameraYaw", mc.thePlayer.rotationYaw);
        selection.setFloat("cameraPitch", mc.thePlayer.rotationPitch);
        Vec3 look = mc.thePlayer.getLookVec();
        selection.setDouble("lookX", look.xCoord);
        selection.setDouble("lookY", look.yCoord);
        selection.setDouble("lookZ", look.zCoord);
        selection
            .setBoolean("altHeld", Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU));
        selection.setBoolean(
            "blockHitUsed",
            mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK);
        selection.setString("session", HologramClient.state.session);
        selection.setLong("planRevision", data().getLong("planRevision"));
        selection.setLong("clientWorldTick", mc.theWorld.getTotalWorldTime());
        selection.setLong("nativeMouseEventsBefore", nativeMouseEvents);
        if (button == 1) press.begin(mc, false);
        key(Keyboard.KEY_LSHIFT, finish);
        MouseEvent event = new MouseEvent();
        mouseField(event, "button", button);
        mouseField(event, "buttonstate", true);
        MinecraftForge.EVENT_BUS.post(event);
        key(Keyboard.KEY_LSHIFT, false);
        key(Keyboard.KEY_LMENU, false);
        require(event.isCanceled(), "actual registered Forge mouse handler consumed area gesture");
        nativeMouseEvents++;
        selection.setLong("nativeMouseEventsAfter", nativeMouseEvents);
        if (!finish) areaSelections.appendTag(selection);
    }

    private boolean areaPointAck(Minecraft mc, int[] points) {
        if (!Arrays.equals(data().getIntArray("areaPoints"), points)) return false;
        require(areaSelections.tagCount() > 0, "actual native cursor selection preceded its Netty ACK");
        NBTTagCompound selection = areaSelections.getCompoundTagAt(areaSelections.tagCount() - 1);
        selection.setTag("actualAck", data().copy());
        selection.setLong("ackClientWorldTick", mc.theWorld.getTotalWorldTime());
        return true;
    }

    private static void mouseField(MouseEvent event, String name, Object value) throws Exception {
        Field field = MouseEvent.class.getField(name);
        field.setAccessible(true);
        field.set(event, value);
    }

    private static void useBlock(Minecraft mc, int x, int y, int z, int side) {
        ForgeDirection face = ForgeDirection.getOrientation(side);
        mc.objectMouseOver = new MovingObjectPosition(
            x,
            y,
            z,
            side,
            Vec3.createVectorHelper(
                x + .5 + face.offsetX * .5,
                y + .5 + face.offsetY * .5,
                z + .5 + face.offsetZ * .5));
        mc.playerController.onPlayerRightClick(
            mc.thePlayer,
            mc.theWorld,
            mc.thePlayer.getHeldItem(),
            x,
            y,
            z,
            side,
            mc.objectMouseOver.hitVec);
    }

    private static void key(int code, boolean down) throws Exception {
        Field buffer = Keyboard.class.getDeclaredField("keyDownBuffer");
        buffer.setAccessible(true);
        ((ByteBuffer) buffer.get(null)).put(code, (byte) (down ? 1 : 0));
    }

    private static boolean connected(Minecraft mc) {
        return mc.theWorld != null && mc.thePlayer != null
            && ItemNekoHologramProjector.isProjector(mc.thePlayer.getHeldItem());
    }

    private static boolean isQuantumTerminal(net.minecraft.item.ItemStack stack) {
        return stack != null && stack.getItem()
            .getClass()
            .getName()
            .equals("com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal");
    }

    private static void validateActor(Minecraft mc, NBTTagCompound receipt) {
        require(
            receipt.getBoolean("passed") && receipt.getString("actor")
                .equals(
                    mc.thePlayer.getUniqueID()
                        .toString()),
            "server receipt belongs to actual connected profile");
        require(
            !receipt.getString("world")
                .isEmpty(),
            "independent receipt names isolated real world");
    }

    private static void aim(Minecraft mc, double x, double y, double z) {
        double dx = x - mc.thePlayer.posX, dy = y - mc.thePlayer.posY - mc.thePlayer.getEyeHeight(),
            dz = z - mc.thePlayer.posZ;
        mc.thePlayer.rotationYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        mc.thePlayer.prevRotationYaw = mc.thePlayer.rotationYaw;
        mc.thePlayer.rotationPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.thePlayer.prevRotationPitch = mc.thePlayer.rotationPitch;
    }

    private int[] findNativePanel(Minecraft mc) {
        for (int y = bounds[1]; y <= bounds[4]; y++)
            for (int z = bounds[2]; z <= bounds[5]; z++) for (int x = bounds[0]; x <= bounds[3]; x++) {
                Object block = net.minecraft.block.Block.blockRegistry.getNameForObject(mc.theWorld.getBlock(x, y, z));
                if ("IC2NuclearControl:blockNuclearControlMain".equals(block)) return new int[] { x, y, z };
            }
        throw new AssertionError("actual placed target NC panel is absent from native world");
    }

    private static boolean idle(HologramScreen screen, NBTTagCompound ui) {
        return !screen.smokePending() && !ui.getBoolean("busy");
    }

    private static boolean enabled(NBTTagCompound ui, int id) {
        NBTTagList controls = ui.getTagList("drawnControls", 10);
        for (int i = 0; i < controls.tagCount(); i++) {
            NBTTagCompound control = controls.getCompoundTagAt(i);
            if (control.getInteger("id") == id) return control.getBoolean("enabled");
        }
        return false;
    }

    private static int localIndex(NBTTagCompound ui, String id) {
        NBTTagList entries = ui.getTagList("entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) if (id.equals(
            entries.getCompoundTagAt(i)
                .getString("id")))
            return i;
        return -1;
    }

    private static NBTTagCompound data() {
        require(HologramClient.state != null, "actual Netty snapshot exists");
        return HologramClient.state.data;
    }

    private static HologramProcessingFlow flow(HologramScreen screen) throws Exception {
        return (HologramProcessingFlow) field(screen, "processingFlow");
    }

    private static int confirmed(NBTTagCompound policy) {
        return policy.getTagList("confirmedStages", 8)
            .tagCount();
    }

    private static Object field(Object target, String name) throws Exception {
        Field value = target.getClass()
            .getDeclaredField(name);
        value.setAccessible(true);
        return value.get(target);
    }

    private static Path localPath(Minecraft mc, String id) {
        require(id.matches("local_[a-fA-F0-9-]{36}"), "bounded actual private file ID");
        return mc.mcDataDir.toPath()
            .resolve("MiaoBlueprints")
            .resolve(id + ".json");
    }

    private static JsonObject blueprint(byte[] bytes) {
        JsonObject root = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
            .getAsJsonObject();
        return root.has("blueprint") ? root.getAsJsonObject("blueprint") : root;
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }

    private String stepLabel() {
        return step < steps.size() ? steps.get(step).label : "after-final";
    }

    private void fail(Minecraft mc, Throwable failure) {
        failure.printStackTrace();
        finished = true;
        try {
            for (int key : new int[] { Keyboard.KEY_LSHIFT, Keyboard.KEY_LCONTROL, Keyboard.KEY_LMENU })
                key(key, false);
            NBTTagCompound result = new NBTTagCompound();
            result.setBoolean("passed", false);
            result.setString("role", role);
            result.setInteger("step", step);
            result.setString("stepLabel", stepLabel());
            result.setString("failure", failure.toString());
            result.setString("expectedSession", session);
            result.setTag("actualQuantumPlacements", quantumPlacements.copy());
            result.setTag("quantumGroupingInteractions", groupInteractions.copy());
            result.setTag("quantumGroupingNodeClicks", groupNodeClicks.copy());
            result.setTag("privateNativeTerminalDrops", privateTerminalDrops.copy());
            if (mc.currentScreen instanceof HologramQuantumGroupsScreen) result
                .setTag("actualQuantumGroupingUi", ((HologramQuantumGroupsScreen) mc.currentScreen).smokeUiState());
            result.setTag("nativePlacementClientState", placementClientState(mc));
            if (quantumPlacement != null) {
                result.setTag("pendingQuantumPlacement", quantumPlacement.copy());
                int[] point = quantumPlacement.getCompoundTag("ready")
                    .getIntArray("gateway");
                if (mc.theWorld != null && point.length == 3) {
                    net.minecraft.tileentity.TileEntity node = mc.theWorld.getTileEntity(point[0], point[1], point[2]);
                    result.setBoolean("nativePlacementClientTilePresent", node != null);
                    if (node != null) {
                        NBTTagCompound tag = new NBTTagCompound();
                        node.writeToNBT(tag);
                        result.setTag("nativePlacementClientTile", tag);
                    }
                }
            }
            result.setLong("nativeCloseSequence", closeSequence);
            result.setString(
                "actualScreenClass",
                mc.currentScreen == null ? ""
                    : mc.currentScreen.getClass()
                        .getName());
            if (!saveReviewEvidence.hasNoTags()) result.setTag("actualAeSaveReview", saveReviewEvidence.copy());
            if (areaSelections.tagCount() > 0) result.setTag("actualAreaSelections", areaSelections.copy());
            if (fullReviewEvidence != null) result.setTag("actualFullReviewSelection", fullReviewEvidence.copy());
            result.setTag("actualLibrarySaveState", HologramLibraryClient.actualSaveState());
            result.setTag("actualLibrarySaveWitness", HologramLibraryClient.actualSaveWitness());
            if (HologramClient.state != null) result.setTag("actualSnapshot", data().copy());
            if (mc.currentScreen instanceof HologramScreen)
                result.setTag("actualUi", ((HologramScreen) mc.currentScreen).smokeLibraryUiState());
            HologramV011Evidence.write("sulfur-" + role + "-failure.nbt", result);
            HologramV011Evidence.marker(
                "sulfur-" + role + "-fail.txt",
                "FAIL actual native sulfur step=" + step + " " + stepLabel() + " " + failure);
        } catch (Throwable evidenceFailure) {
            failure.addSuppressed(evidenceFailure);
        }
        mc.shutdown();
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }
}
