// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramBlueprintFiles;
import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.hologram.HologramNativeControllerBrowserReference;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
import com.miaokatze.gtmb.hologram.HologramSnapshotPalette;
import com.miaokatze.gtmb.register.ProjectorRegistry;

import codechicken.nei.recipe.GuiRecipe;
import cpw.mods.fml.relauncher.Side;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.GregTechTileClientEvents;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/** Opt-in inside the existing lease-owned Issue100 integrated world. */
final class HologramLocalPreviewStagesProbe {

    private static final String FLAG = "gtmb.testlab.localPreviewStages";
    private static final String SOURCE = FLAG + "Source";
    private static final String HANDLER = "gtmb-testlab-local-preview-stages";
    private static final int SOLAR_BODY_CELLS = 2847;
    private static final Set<String> PLACEMENT_FIELDS = new HashSet<>(
        Arrays.asList(
            "action",
            "placementRequestId",
            "placementWireVersion",
            "placementAnchor",
            "placementVariants",
            "placementOperations",
            "placementMaterials",
            "clientSnapshotProtocol",
            "clientSnapshotPalette",
            "clientSnapshotFlow"));
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final Map<String, Integer> outbound = new LinkedHashMap<>();
    private final long started = System.nanoTime();
    private final Path output;
    private final boolean construction = Boolean.getBoolean(FLAG + "Construction");
    private final boolean materialsOnly = materialsOnlyEnabled();
    private final boolean pressureReview = pressureReviewEnabled();
    private volatile HologramPressureReviewCase pressure;
    private boolean pressureFinished;
    private ItemStack pressureTool;
    private NBTTagList pressureInventory;
    private HologramLocalMaterialQuietCase materials;
    private final boolean postFocus = construction || Boolean.getBoolean(FLAG + "PostFocus");
    private final NativeParity parity = NativeParity.configured();
    private final boolean fixedNativeEbf = fixedNativeEbfEnabled();
    private HologramEbfFixedControls fixedEbf;
    private volatile NBTTagList ebfServerInventory, ebfInventoryBaseline;
    private volatile int ebfServerSlot = -1;
    private volatile UUID fixedEbfActor;
    private volatile boolean ebfServerFootprintVerified;
    private boolean fixedEbfEnabledMetrics;
    private final boolean workbenchStages = Boolean.getBoolean(FLAG + "WorkbenchStages");
    private final String timingMode = System.getProperty(FLAG + "Timing", "");
    private final boolean strictTiming = HologramPreviewThresholdWitness.enabled() && "gtmb".equals(timingMode);
    private final List<Map<String, Object>> timingRuns = new ArrayList<>();
    private final List<HologramState> timingSolarStates = new ArrayList<>();
    private GuiScreen timingScreen;
    private long reopenAtTick;
    private volatile Map<String, Object> timingNeiFrame;
    private Map<String, Object> timingGtmbFrame;
    private Map<String, Object> timingFrameToCapture;
    private long timingDrawFrame;
    private long timingWorkbenchInitNanos;
    private Map<String, Map<String, Long>> timingWorkbenchActivity;
    private HologramPreviewThresholdWitness timingMaterial;
    private volatile Exception timingFailure;
    private final int x, y, z, toolSlot, stoneSlot;
    private final String actorName;
    private final String localId = "local_" + UUID.fromString(System.getProperty("miaotest.host.runId"));
    private final ItemStack originalTool, originalStone, fixtureTool;
    private final MovingObjectPosition originalHit;
    private final float yaw, pitch;
    private volatile boolean fixtureReady;
    private volatile Throwable fixtureFailure;
    private volatile ItemStack controllerItem;
    private int parityPass;
    private volatile boolean solarTelemetryRequested;
    private boolean solarTelemetrySent;
    private long parityDataReady;
    private NBTTagCompound activityBeforeParityEdit = new NBTTagCompound();
    private Map<String, String> firstParityCells;
    private volatile boolean submitInput;
    private volatile NBTTagCompound projectedPlacement;
    private int stage;
    private long controllerClicked, controllerClickTick, privateRequested, lockedTick, lockedGeneration,
        movedGeneration;
    private int[] firstAnchor, lockedAnchor;
    private HologramScreen controller;
    private HologramState controllerDrawn;
    private long controllerDrawSerial, ownDrawTail = -1, controllerDrawsAtStart, controllerPreviousDraws;
    private boolean controllerFrameCaptured;
    private HologramWorkbenchStagesWitness workbench;
    private HologramState ghost;
    private HologramClientLibrary originalStore, fixtureStore;
    private Channel channel;
    private boolean cancelling;
    private volatile boolean finished;
    private EntityPlayerMP serverActor;
    private ItemStack serverToolBefore, serverStoneBefore;
    private volatile boolean restoreServerInventory, serverInventoryRestored;
    private volatile int serverObservationRequest;
    private volatile Map<String, Object> beforeConfirm, afterConstruction;
    private HologramScreen constructionReview;
    private String constructionSession;

    static boolean enabled() {
        return Boolean.getBoolean(FLAG);
    }

    static boolean materialsOnlyEnabled() {
        return Boolean.getBoolean(HologramLocalMaterialQuietCase.FLAG);
    }

    static boolean pressureReviewEnabled() {
        return Boolean.getBoolean(HologramPressureReviewCase.FLAG);
    }

    static boolean fixedNativeEbfEnabled() {
        return Boolean.getBoolean(HologramEbfFixedControls.FLAG);
    }

    HologramLocalPreviewStagesProbe(Minecraft mc, Path output, Map<String, Object> receipt) {
        this.output = output;
        receipt.put("localPreviewStages", evidence);
        x = MathHelper.floor_double(mc.thePlayer.posX);
        y = MathHelper.floor_double(mc.thePlayer.boundingBox.minY);
        z = MathHelper.floor_double(mc.thePlayer.posZ) + 3;
        yaw = mc.thePlayer.rotationYaw;
        pitch = mc.thePlayer.rotationPitch;
        actorName = mc.thePlayer.getCommandSenderName();
        if (materialsOnly) require(!construction && !fixedNativeEbf && parity == null && timingMode.isEmpty()
            && !workbenchStages && System.getProperty(SOURCE, "").isEmpty(),
            "Material browse has one ordinary stone source and no construction or alternate workload");
        if (pressureReview) require(postFocus && !construction && !materialsOnly && !fixedNativeEbf
            && parity == null && timingMode.isEmpty() && !workbenchStages
            && !System.getProperty(SOURCE, "").isEmpty() && Boolean.getBoolean("gtmb.projectedPlacementGuardTrace"),
            "Pressure review is a separate owned PostFocus run with the frozen pair and guard trace");
        if (fixedNativeEbf) {
            fixedEbfActor = mc.thePlayer.getUniqueID();
            require(parity == NativeParity.EBF && !postFocus && timingMode.isEmpty() && !workbenchStages
                && !Boolean.getBoolean(FLAG + "ExerciseCandidates"),
                "Fixed native EBF is a separate owned preview profile with no construction, alternate driver or timing");
            evidence.put("fixedNativeEbf", true);
            evidence.put("inventoryFixtureWrites", 0);
        }
        toolSlot = mc.thePlayer.inventory.currentItem;
        stoneSlot = (toolSlot + 1) % 9;
        originalTool = mc.thePlayer.getHeldItem();
        originalStone = mc.thePlayer.inventory.getStackInSlot(stoneSlot);
        originalHit = mc.objectMouseOver;
        fixtureTool = new ItemStack(ProjectorRegistry.projector);
        evidence.put("schema", "gtmb-local-preview-stages-v1");
        evidence.put("passed", false);
        evidence.put("controllerPosition", new int[] { x, y, z });
        evidence.put(
            "input",
            postFocus ? "production business callbacks after the world-focus gate"
                : "native PlayerControllerMP right click, synthetic game input, no OS input");
        evidence.put("nativeWorldInputExercised", !postFocus);
        evidence.put("constructionRequested", construction);
        evidence.put("observer", "actual FML client outbound IMessage pipeline, GTMB mod channel only");
        if (!timingMode.isEmpty()) {
            require(("nei".equals(timingMode) || "gtmb".equals(timingMode)) && !construction,
                "Timing mode must be 'nei' or 'gtmb' and runs separately from construction");
            evidence.put("timingMode", timingMode);
            evidence.put("timingRuns", timingRuns);
            evidence.put("timingScope", "same default trigger, south facing, first open then natural close/reopen");
            if (strictTiming) evidence.put("strictPreviewThresholds", true);
        }
        if (parity != null) {
            require(!construction, "Native controller parity and the construction positive control are separate fixtures");
            evidence.put("nativeParityFixture", parity.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Called only after Issue100 has verified the exact marker-owned environment, on its server thread. */
    void setup(MinecraftServer server) {
        if (finished) return;
        try {
            WorldServer world = server.worldServerForDimension(0);
            if (restoreServerInventory) {
                if (!serverInventoryRestored && serverActor != null) {
                    serverActor.inventory.setInventorySlotContents(toolSlot, serverToolBefore);
                    serverActor.inventory.setInventorySlotContents(stoneSlot, serverStoneBefore);
                    serverActor.inventoryContainer.detectAndSendChanges();
                }
                serverInventoryRestored = true;
                return;
            }
            if (fixtureReady || fixtureFailure != null) {
                if (fixedNativeEbf && fixtureReady) sampleFixedEbfInventory(world);
                if (construction && serverActor != null) observeWorld(world);
                HologramPressureReviewCase observed = pressure;
                if (pressureReview && observed != null)
                    observed.observeServer(world, serverActor, pressureTool, pressureInventory);
                if (solarTelemetryRequested && !solarTelemetrySent) {
                    BaseMetaTileEntity base = (BaseMetaTileEntity) world.getTileEntity(x, y, z);
                    base.enableWorking();
                    // Exercise the normal incoming GT event without having to build all tiers in the fixture world.
                    world.addBlockEvent(x, y, z, world.getBlock(x, y, z), GregTechTileClientEvents.CHANGE_CUSTOM_DATA, 6);
                    solarTelemetrySent = true;
                }
                return;
            }
            EntityPlayer actor = world.getPlayerEntityByName(actorName);
            require(
                actor != null && world.blockExists(x, y, z) && world.isAirBlock(x, y, z),
                "Owned-world controller fixture site unavailable");
            if (fixedNativeEbf) {
                require(actor instanceof EntityPlayerMP && !(actor instanceof FakePlayer)
                    && fixedEbfActor.equals(actor.getUniqueID()) && !actor.capabilities.isCreativeMode
                    && actor.dimension == 0 && actor.inventory.currentItem == toolSlot && nativeProjector(actor.getHeldItem()),
                    "PRECONDITION: the owned survival actor has no actual already-held projector; fixture not staged");
            }
            ItemStack item = parity == null ? ItemList.Machine_Multi_BlastFurnace.get(1) : parity.item();
            ForgeDirection facing = timingMode.isEmpty() ? ForgeDirection.NORTH : ForgeDirection.SOUTH;
            require(
                item != null && item.getItem() instanceof ItemMachines machines
                    && machines.placeBlockAt(item, actor, world, x, y, z, facing.ordinal(), .5F, .5F, .5F, 0),
                "Native controller placement failed");
            require(
                world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity base
                    && fixtureController(base),
                "Native controller fixture identity mismatch");
            ((BaseMetaTileEntity) world.getTileEntity(x, y, z)).setFrontFacing(facing);
            if (parity == NativeParity.SOLAR || parity == NativeParity.ENTANGLER)
                ((BaseMetaTileEntity) world.getTileEntity(x, y, z)).disableWorking();
            world.markBlockForUpdate(x, y, z);
            if (pressureReview) {
                require(actor instanceof EntityPlayerMP && !(actor instanceof FakePlayer)
                    && !actor.capabilities.isCreativeMode && actor.capabilities.allowEdit
                    && !actor.canCommandSenderUseCommand(2, "give"),
                    "Pressure review requires the actual editable non-OP survival actor");
                serverActor = (EntityPlayerMP) actor;
                serverToolBefore = actor.inventory.getStackInSlot(toolSlot);
                serverStoneBefore = actor.inventory.getStackInSlot(stoneSlot);
                pressureTool = new ItemStack(ProjectorRegistry.projector);
                NBTTagCompound ownedId = new NBTTagCompound();
                ownedId.setString("gtitHologramToolId", UUID.randomUUID().toString());
                pressureTool.setTagCompound(ownedId);
                actor.inventory.setInventorySlotContents(toolSlot, pressureTool);
                pressureInventory = actor.inventory.writeToNBT(new NBTTagList());
                actor.inventoryContainer.detectAndSendChanges();
            }
            if (construction || materialsOnly) {
                require(
                    actor instanceof EntityPlayerMP && !(actor instanceof FakePlayer)
                        && !actor.capabilities.isCreativeMode
                        && stones(actor) == 0,
                    "Stone fixture requires the real survival actor with no existing stones");
                serverActor = (EntityPlayerMP) actor;
                serverToolBefore = actor.inventory.getStackInSlot(toolSlot);
                serverStoneBefore = actor.inventory.getStackInSlot(stoneSlot);
                actor.inventory.setInventorySlotContents(toolSlot, new ItemStack(ProjectorRegistry.projector));
                actor.inventory.setInventorySlotContents(stoneSlot, new ItemStack(Blocks.stone));
                actor.inventoryContainer.detectAndSendChanges();
            }
            controllerItem = item;
            fixtureReady = true;
        } catch (RuntimeException | LinkageError problem) {
            fixtureFailure = problem;
        }
    }

    private static boolean nativeProjector(ItemStack held) {
        return ItemNekoHologramProjector.isProjector(held) && held.hasTagCompound()
            && !held.getTagCompound().getString("gtitHologramToolId").isEmpty();
    }

    private void sampleFixedEbfInventory(WorldServer world) {
        EntityPlayer player = world.getPlayerEntityByName(actorName);
        require(player instanceof EntityPlayerMP && !(player instanceof FakePlayer) && !player.capabilities.isCreativeMode
            && player.dimension == 0 && fixedEbfActor.equals(player.getUniqueID()), "Fixed EBF server actor changed");
        var tile = world.getTileEntity(x, y, z);
        require(tile instanceof BaseMetaTileEntity base && fixtureController(base)
            && ((MTEElectricBlastFurnace) base.getMetaTileEntity()).getExtendedFacing()
                == com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.NORTH_NORMAL_NONE,
            "Fixed EBF exact server controller/alignment changed");
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 3; dy++) for (int dz = 0; dz <= 2; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            require(world.blockExists(x + dx, y + dy, z + dz) && world.isAirBlock(x + dx, y + dy, z + dz)
                && world.getTileEntity(x + dx, y + dy, z + dz) == null, "Fixed EBF server footprint changed");
        }
        ebfServerFootprintVerified = true;
        int selected = player.inventory.currentItem;
        var current = player.inventory.writeToNBT(new NBTTagList());
        var baseline = ebfInventoryBaseline;
        require(baseline == null || selected == toolSlot && baseline.equals(current), "Fixed EBF server inventory changed");
        ebfServerSlot = selected;
        ebfServerInventory = current;
    }

    boolean step(Minecraft mc) throws Exception {
        evidence.put("stage", stage);
        evidence.put("worldInputFocused", HologramClient.worldInputFocused());
        if (fixtureFailure != null) throw new IllegalStateException("Owned fixture failed", fixtureFailure);
        boolean expired = System.nanoTime() - started >= (pressureReview ? 300_000_000_000L : 90_000_000_000L);
        if (expired && timingMaterial != null) {
            evidence.put("pendingWorkbenchInitializationNanos", timingWorkbenchInitNanos);
            evidence.put("pendingWorkbenchInitializationActivity", timingWorkbenchActivity);
            evidence.put("pendingMaterialFrameThresholds", timingMaterial.receipt(
                0, parity == NativeParity.SOLAR ? HologramPreviewThresholdWitness.SOLAR_COMPLETE_NANOS : 0));
        }
        require(
            !expired,
            "Local preview proof timed out in stage " + stage
                + ", focus="
                + HologramClient.worldInputFocused()
                + ", outbound="
                + packets());
        if (!timingMode.isEmpty()) return timingStep(mc);
        if (stage == 0) {
            if (!fixtureReady || !(mc.theWorld.getTileEntity(x, y, z) instanceof BaseMetaTileEntity base)
                || !fixtureController(base)
                || !postFocus && !HologramClient.worldInputFocused()) return false;
            if (pressureReview && !ItemStack.areItemStacksEqual(pressureTool, mc.thePlayer.getHeldItem())) return false;
            if ((construction || materialsOnly)
                && (!ItemNekoHologramProjector.isProjector(mc.thePlayer.getHeldItem()) || stones(mc.thePlayer) != 1))
                return false;
            if (fixedNativeEbf) {
                var stock = mc.thePlayer.inventory.writeToNBT(new NBTTagList());
                if (ebfServerInventory == null || ebfServerSlot != toolSlot || !stock.equals(ebfServerInventory)) return false;
                ItemStack held = mc.thePlayer.getHeldItem();
                require(nativeProjector(held),
                    "PRECONDITION: use a normal synchronized, already-held projector; no inventory fixture injection");
                ebfInventoryBaseline = stock;
                evidence.put("stockActorAndHeldProjectorVerified", true);
                evidence.put("ownedControllerFixtureSetupCount", 1);
            }
            observeOutbound();
            if (!construction && !materialsOnly && !pressureReview && !fixedNativeEbf) mc.thePlayer.inventory.setInventorySlotContents(toolSlot, fixtureTool);
            look(mc, x + .5, y + .5, z + .5);
            require(hitAt(mc, x, y, z), "EntityRenderer crosshair missed the real controller");
            controllerClicked = System.nanoTime();
            if (parity != null) {
                fixedEbfEnabledMetrics = fixedNativeEbf && !HologramRuntimeMetrics.isEnabled();
                HologramRuntimeMetrics.setEnabled(true);
            }
            if (workbenchStages) workbench = new HologramWorkbenchStagesWitness(null, controllerClicked);
            controllerClickTick = mc.thePlayer.ticksExisted;
            controllerDrawsAtStart = MiaoVisonLiteRenderer.previewDirectMeshDraws;
            if (postFocus) HologramScreen.localController(base, mc.thePlayer.dimension, mc.objectMouseOver.sideHit)
                .openFrom(mc.currentScreen);
            else rightClick(mc);
            evidence.put("controllerScreenOpenedDuringClick", mc.currentScreen instanceof HologramScreen);
            zero("controllerOpening");
            stage++;
        } else if (stage == 1) {
            zero("controllerWaiting");
            if (controller == null) {
                int ticksFromClick = mc.thePlayer.ticksExisted - (int) controllerClickTick;
                if (mc.currentScreen == null && ticksFromClick < 5) return false;
                require(
                    mc.currentScreen instanceof HologramScreen opened && opened.localControllerStandard(),
                    "Ordinary use did not promptly open the original local standard workbench");
                controller = (HologramScreen) mc.currentScreen;
                evidence.put("controllerOpenTicks", ticksFromClick);
                evidence.put("controllerOpenNanosUpperBound", System.nanoTime() - controllerClicked);
            }
            require(
                mc.currentScreen == controller && controller.localControllerStandard(),
                "Controller screen retired");
            HologramState state = controller.ui.session.state();
            evidence.put("controllerProjectionDataComplete", state.data.getBoolean("projectionDataComplete"));
            evidence.put("controllerCapturePending", state.data.getBoolean("capturePending"));
            evidence.put("controllerCurrentCells", state.cells.size());
            evidence.put("controllerCurrentMain", state.data.getInteger("main"));
            evidence.put("controllerStatus", state.data.getString("status"));
            require(!state.data.getBoolean("localStructureUnavailable"), "Local standard declaration unavailable");
            if (fixedNativeEbf) {
                if (fixedEbf == null) {
                    var report = new LinkedHashMap<String, Object>();
                    evidence.put(HologramEbfFixedControls.receiptKey(), report);
                    fixedEbf = new HologramEbfFixedControls(mc, controller, x, y, z, controllerItem,
                        report, this::requireNativeFootprint, ebfInventoryBaseline, toolSlot);
                }
                if (!fixedEbf.tick(mc)) return false;
                require(fixtureFailure == null && ebfServerFootprintVerified && ebfInventoryBaseline.equals(ebfServerInventory)
                    && ebfServerSlot == toolSlot, "Fixed EBF server stock proof missing");
                evidence.put("serverStockAndEmptyFootprintUnchangedAfterSetup", true);
                zero("fixedEbfPreviewVerified");
                mc.displayGuiScreen(null);
                zero("fixedEbfPreviewClosed");
                require(finish(mc), "Fixed EBF owned cleanup did not complete");
                evidence.put("passed", true);
                return true;
            }
            if (parity != null) return parityStep(mc, state);
            if (controllerDrawn != state || !controllerFrameCaptured) return false;
            Map<String, Object> footprint = new LinkedHashMap<>();
            evidence.put("controllerNativeFootprint", footprint);
            requireNativeFootprint(state, footprint);
            requireLiveController(mc);
            evidence.put("controllerCells", state.cells.size());
            evidence.put("controllerPresentedNanos", System.nanoTime() - controllerClicked);
            zero("controllerPresented");
            mc.displayGuiScreen(null);
            stage++;
        } else if (stage == 2) {
            zero("controllerClosed");
            if (HologramLibraryClient.INSTANCE.busy() || HologramBackgroundTasks.busy()
                || !postFocus && !HologramClient.worldInputFocused()) return false;
            stagePrivatePair(mc);
            look(mc, x + .5, y + .5, z + .5);
            privateRequested = System.nanoTime();
            HologramLibraryClient.INSTANCE.previewLocal(localId, true);
            zero("privateSourceRequested");
            stage++;
        } else if (stage == 3) {
            zero("privateMovingWaiting");
            HologramLocalPlacement placement = HologramLocalPlacement.INSTANCE;
            if (!placement.active()) return false;
            ghost = placement.display();
            HologramLibraryClient.DisplaySource source = (HologramLibraryClient.DisplaySource) field(
                HologramLocalPlacement.class,
                "source").get(placement);
            require(
                !placement.locked() && !placement.submitting()
                    && source != null
                    && localId.equals(source.localId)
                    && !ghost.cells.isEmpty(),
                "Expected prepared moving ghost from the private file");
            MiaoVisonLiteRenderer.WorldRenderWitness witness = witness();
            if (witness == null) return false;
            firstAnchor = witness.anchor();
            if (firstAnchor == null) return false;
            movedGeneration = witness.generation;
            evidence.put("movingCells", ghost.cells.size());
            evidence.put("privateMovingPresentedNanos", System.nanoTime() - privateRequested);
            evidence.put("movingFirstLists", witness.lists);
            evidence.put("movingFirstAnchor", firstAnchor);
            // The ordinary move witness serves one stone. This preflight only requires the actual first visible anchor.
            if (!pressureReview) look(mc, x - 1.5, y - .5, z - 1.5);
            stage++;
        } else if (stage == 4) {
            zero("privateMoving");
            MiaoVisonLiteRenderer.WorldRenderWitness witness = witness();
            int[] moved = HologramLocalPlacement.INSTANCE.anchor();
            if (pressureReview) {
                require(mc.currentScreen == null && moved != null && Arrays.equals(firstAnchor, moved)
                    && mc.theWorld.blockExists(moved[0], moved[1], moved[2])
                    && com.miaokatze.gtmb.hologram.HologramWorldFlow.withinPreviewReach(mc.thePlayer.posX,
                        mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ, moved[0], moved[1], moved[2]),
                    "Frozen archive's first actually drawn aim is no longer the current loaded and reachable anchor");
                if (witness == null || !Arrays.equals(witness.anchor(), moved)) return false;
                evidence.put("lockedAtFirstVisibleAnchor", true);
            } else if (witness == null || witness.generation <= movedGeneration
                || moved == null
                || Arrays.equals(firstAnchor, moved)
                || !Arrays.equals(witness.anchor(), moved)) return false;
            if (construction) require(
                mc.theWorld.blockExists(moved[0], moved[1], moved[2])
                    && mc.theWorld.isAirBlock(moved[0], moved[1], moved[2]),
                "Construction target must be loaded air");
            require(postFocus || HologramClient.worldInputFocused(), "Native lock requires world focus");
            evidence.put("movingSecondAnchor", moved);
            evidence.put("movingSecondLists", witness.lists);
            placementUse(mc);
            HologramLocalPlacement placement = HologramLocalPlacement.INSTANCE;
            require(
                placement.locked() && !placement.submitting() && Arrays.equals(moved, placement.anchor()),
                "Native first right-click did not locally lock the moved ghost");
            lockedAnchor = placement.anchor();
            lockedTick = mc.theWorld.getTotalWorldTime();
            lockedGeneration = witness.generation;
            zero("privateLockedInput");
            stage++;
        } else if (stage == 5) {
            zero("privateLockedWaiting");
            MiaoVisonLiteRenderer.WorldRenderWitness witness = witness();
            if (witness == null || witness.generation <= lockedGeneration
                || mc.theWorld.getTotalWorldTime() - lockedTick < 12) return false;
            require(
                HologramLocalPlacement.INSTANCE.locked() && mc.currentScreen == null
                    && Arrays.equals(lockedAnchor, HologramLocalPlacement.INSTANCE.anchor())
                    && Arrays.equals(lockedAnchor, witness.anchor()),
                "Local lock changed");
            evidence.put("lockedAnchor", lockedAnchor);
            evidence.put("lockedDrawnLists", witness.lists);
            evidence.put("lockedWorldTicks", mc.theWorld.getTotalWorldTime() - lockedTick);
            zero("privateLockedPresented");
            if (pressureReview) {
                pressure = new HologramPressureReviewCase(mc, lockedAnchor);
                evidence.put("pressureReview", pressure.evidence);
                placementUse(mc);
                require(mc.currentScreen instanceof HologramLibraryMaterialReviewScreen,
                    "Second ordinary PostFocus use did not open the local review");
                stage = 10;
                return false;
            }
            if (materialsOnly) {
                materials = new HologramLocalMaterialQuietCase(mc, lockedAnchor, packets());
                evidence.put("ordinaryMaterialBrowse", materials.evidence);
                stage = 8;
                return false;
            }
            submitInput = true;
            placementUse(mc);
            require(HologramLocalPlacement.INSTANCE.submitting(), "Explicit second right-click did not submit");
            stage++;
        } else if (stage == 6) {
            if (projectedPlacement == null || !libraryWorkerIdle()) return false;
            Map<String, Integer> sent = packets();
            require(
                sent.getOrDefault("action:projectedPlacement", 0) == 1
                    && (construction || Collections.singletonMap("action:projectedPlacement", 1)
                        .equals(sent) && HologramLocalPlacement.INSTANCE.submitting()),
                "Unexpected traffic after explicit submission");
            verifyProjectedPlacement();
            evidence.put("explicitSubmitOutbound", Collections.singletonMap("action:projectedPlacement", 1));
            evidence.put("allOutboundAtPositiveControl", sent);
            evidence.put("serverValidationDelivered", construction);
            stage++;
        } else if (stage == 7) {
            if (construction) return constructionStep(mc);
            if (!finish(mc)) return false;
            evidence.put("passed", true);
            return true;
        } else if (stage == 8) {
            if (!materials.step(mc, packets(), () -> placementUse(mc))) return false;
            stage = 9;
        } else if (stage == 9) {
            if (!finish(mc)) return false;
            require(packets().isEmpty(), "Unexpected GTMB writes during material fixture teardown");
            evidence.put("passed", true);
            return true;
        } else if (stage == 10) {
            if (!pressure.step(mc)) return false;
            stage = 11;
        } else if (stage == 11) {
            if (!finish(mc)) return false;
            evidence.put("passed", true);
            return true;
        }
        return false;
    }

    void guiPre(GuiScreen gui, long frame) {
        if (fixedEbf != null && !finished) fixedEbf.guiPre(gui, frame);
    }

    void guiPost(GuiScreen gui, long frame) {
        if (fixedEbf != null && !finished) fixedEbf.guiPost(gui, frame);
        if (!timingMode.isEmpty()) {
            if (finished || stage != 1 || gui != timingScreen || timingFailure != null
                || timingFrameToCapture != null) return;
            if (timingMaterial != null && gui instanceof HologramScreen shown) {
                timingMaterial.observe(shown.ui.session.state(), HologramRenderer.editorMaterialPresentation());
                return;
            }
            if (gui instanceof HologramScreen shown && workbench.guiPost(shown)) {
                timingFrameToCapture = new LinkedHashMap<>();
                timingDrawFrame = frame;
            }
            else if (gui instanceof GuiRecipe<?> recipe && timingNeiFrame == null) {
                long drawPost = System.nanoTime();
                try {
                    Map<String, Object> measured = HologramNeiFirstFrame.frame(Minecraft.getMinecraft(), recipe, controllerItem);
                    if (measured.isEmpty()) return;
                    timingFrameToCapture = new LinkedHashMap<>(measured);
                    timingFrameToCapture.put("firstMaterialDrawPostNanos", drawPost - controllerClicked);
                    timingDrawFrame = frame;
                } catch (Exception failed) {
                    timingFailure = failed;
                }
            }
            return;
        }
        if (!finished && stage == 1 && workbench != null && gui instanceof HologramScreen shown
            && shown.localControllerStandard() && (controller == null || gui == controller))
            workbench.guiPost(shown);
        if (finished || parity != null || stage != 1 || controller == null || gui != controller) return;
        HologramState state = controller.ui.session.state();
        NBTTagCompound witness = HologramRenderer.lessonInspection(state);
        boolean complete = state.data.getBoolean("projectionDataComplete");
        boolean ready = HologramRenderer.previewMeshReady(state);
        boolean displayed = HologramRenderer.previewDisplayState() == state;
        long materialDraws = MiaoVisonLiteRenderer.previewDirectMeshDraws - controllerDrawsAtStart;
        long newMaterialDraws = materialDraws - controllerPreviousDraws;
        controllerPreviousDraws = materialDraws;
        Map<String, Integer> roles = new LinkedHashMap<>();
        for (HologramState.Cell cell : state.cells) roles.merge(cell.role, 1, Integer::sum);
        evidence.put("controllerRoles", roles);
        evidence.put("controllerGuiPostFrames", ++controllerDrawSerial);
        evidence.put("controllerProjectionDataComplete", complete);
        evidence.put("controllerMeshReady", ready);
        evidence.put("controllerExactDisplayState", displayed);
        evidence.put("controllerScopeMaterialMeshDraws", materialDraws);
        evidence.put("controllerOwnGuiFrameMaterialDraws", newMaterialDraws);
        evidence.put("controllerLessonSnapshotPresented", witness.getBoolean("snapshotPresented"));
        evidence.put("controllerLessonVisibleCells", witness.getInteger("presentedVisibleCellCount"));
        evidence.put("controllerLessonRenderedCells", witness.getInteger("renderedCellCount"));
        evidence.put("controllerLastGuiPostNanos", System.nanoTime() - controllerClicked);
        if (complete && !state.data.getBoolean("capturePending")
            && roles.getOrDefault("controller", 0) == 1
            && newMaterialDraws > 0) {
            controllerDrawn = state;
            ownDrawTail = frame;
        }
    }

    void renderTail(long frame) throws Exception {
        if (fixedEbf != null && !finished) fixedEbf.renderEnd(Minecraft.getMinecraft(), frame);
        if (!finished && stage == 1 && timingFrameToCapture != null && timingDrawFrame == frame - 1
            && Minecraft.getMinecraft().currentScreen == timingScreen) {
            timingScreenshot(Minecraft.getMinecraft(), timingFrameToCapture);
            if (timingScreen instanceof HologramScreen) timingGtmbFrame = timingFrameToCapture;
            else timingNeiFrame = timingFrameToCapture;
            timingFrameToCapture = null;
        }
        if (finished || stage != 1
            || controllerDrawn == null
            || controllerFrameCaptured
            || ownDrawTail != frame - 1
            || Minecraft.getMinecraft().currentScreen != controller) return;
        String name = "gtmb-local-standard-controller.png";
        Minecraft mc = Minecraft.getMinecraft();
        Files.createDirectories(output.resolve("screenshots"));
        ScreenShotHelper.saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        require(
            Files.isRegularFile(
                output.resolve("screenshots")
                    .resolve(name)),
            "Controller proof screenshot missing");
        evidence.put("controllerFramebuffer", "screenshots/" + name);
        evidence.put("controllerRenderTailFrame", frame);
        controllerFrameCaptured = true;
    }

    Map<String, Object> progress() {
        return new LinkedHashMap<>(evidence);
    }

    private boolean timingStep(Minecraft mc) throws Exception {
        if (timingFailure != null) throw timingFailure;
        zero("timing." + stage);
        if (stage == 0) {
            if (!fixtureReady || mc.currentScreen != null || mc.thePlayer.ticksExisted < reopenAtTick
                || !postFocus && "gtmb".equals(timingMode) && !HologramClient.worldInputFocused()
                || !(mc.theWorld.getTileEntity(x, y, z) instanceof BaseMetaTileEntity tile)
                || !fixtureController(tile) || tile.getFrontFacing() != ForgeDirection.SOUTH) return false;
            if (channel == null) observeOutbound();
            look(mc, x + .5, y + .5, z + .5);
            require(hitAt(mc, x, y, z), "The timing fixture is not at the player's crosshair");
            if ("nei".equals(timingMode)) {
                controllerClicked = System.nanoTime();
                timingScreen = HologramNeiFirstFrame.open(mc, controllerItem);
            } else {
                if (mc.thePlayer.inventory.getStackInSlot(toolSlot) != fixtureTool)
                    mc.thePlayer.inventory.setInventorySlotContents(toolSlot, fixtureTool);
                HologramRuntimeMetrics.setEnabled(true);
                if (strictTiming) timingMaterial = new HologramPreviewThresholdWitness(
                    parity == NativeParity.SOLAR ? SOLAR_BODY_CELLS + 1 : 0);
                NBTTagCompound beforeOpen = HologramRuntimeMetrics.snapshot().getCompoundTag("activity");
                controllerClicked = System.nanoTime();
                if (postFocus) HologramScreen.localController(tile, mc.thePlayer.dimension, mc.objectMouseOver.sideHit)
                    .openFrom(mc.currentScreen);
                else rightClick(mc);
                timingWorkbenchInitNanos = System.nanoTime() - controllerClicked;
                timingWorkbenchActivity = workbenchActivity(
                    beforeOpen, HologramRuntimeMetrics.snapshot().getCompoundTag("activity"));
                require(mc.currentScreen instanceof HologramScreen, "The timing fixture did not open the workbench");
                timingScreen = mc.currentScreen;
                if (timingMaterial != null) timingMaterial.start(controllerClicked);
                else workbench = new HologramWorkbenchStagesWitness(null, controllerClicked);
            }
            stage = 1;
            return false;
        }
        require(mc.currentScreen == timingScreen, "The timed screen was retired before its receipt");
        Map<String, Object> sample;
        if (timingScreen instanceof HologramScreen shown) {
            HologramState state = shown.ui.session.state();
            if (!state.data.getBoolean("projectionDataComplete") || state.data.getBoolean("capturePending")) {
                if (workbench != null) workbench.observe(state);
                return false;
            }
            require(state.data.getInteger("main") == 1 && state.data.getCompoundTag("channels").hasNoTags(),
                "GTMB timing fixture does not use the default trigger");
            if (timingMaterial != null) {
                if (!timingMaterial.sawComplete()) return false;
                long completeLimit = parity == NativeParity.SOLAR ? HologramPreviewThresholdWitness.SOLAR_COMPLETE_NANOS : 0;
                sample = new LinkedHashMap<>();
                sample.put("materialFrameThresholds", timingMaterial.receipt(0, completeLimit));
                sample.put("firstMaterialNanos", timingMaterial.firstNanos());
                sample.put("completedMaterialNanos", timingMaterial.completeNanos());
                boolean workbenchPassed = timingWorkbenchInitNanos < HologramPreviewThresholdWitness.WORKBENCH_NANOS;
                sample.put("workbenchInitializationPassed", workbenchPassed);
                sample.put("workbenchInitializationLimitNanosExclusive", HologramPreviewThresholdWitness.WORKBENCH_NANOS);
                sample.put("strictThresholdsPassed", workbenchPassed
                    && (completeLimit == 0 || timingMaterial.completeNanos() < completeLimit));
                if (parity == NativeParity.SOLAR) timingSolarStates.add(state);
                timingMaterial = null;
            } else {
                workbench.observe(state);
                if (!workbench.sawMaterial() || timingGtmbFrame == null) return false;
                sample = workbench.receipt(state, HologramRuntimeMetrics.snapshot().getTagList("loadStages", 10),
                    Boolean.getBoolean(FLAG + "RequireEarly"), Boolean.getBoolean(FLAG + "RequireEarlyFrame"));
                sample.putAll(timingGtmbFrame);
                timingGtmbFrame = null;
            }
            sample.put("workbenchInitializationNanos", timingWorkbenchInitNanos);
            sample.put("workbenchInitializationActivity", timingWorkbenchActivity);
            sample.put("screen", shown.getClass().getName());
            sample.put("controllerMeta", controllerItem.getItemDamage());
            sample.put("facing", ForgeDirection.SOUTH.name());
            sample.put("main", 1);
            sample.put("channels", Collections.singletonMap("gt_hatch", 1));
            sample.put("targetPositions", state.cells.size());
        } else {
            if (timingNeiFrame == null) return false;
            sample = timingNeiFrame;
            timingNeiFrame = null;
        }
        sample.put("temperature", timingRuns.isEmpty() ? "first-open-of-target-this-process" : "reopen-same-process");
        sample.put("width", mc.displayWidth);
        sample.put("height", mc.displayHeight);
        sample.put("guiScale", mc.gameSettings.guiScale);
        timingRuns.add(sample);
        mc.displayGuiScreen(null);
        timingScreen = null;
        if (timingRuns.size() == 1) {
            reopenAtTick = mc.thePlayer.ticksExisted + 5L;
            stage = 0;
            return false;
        }
        zero("timing.closed");
        require(finish(mc), "Timing probe did not restore its world input");
        if (strictTiming) {
            for (int index = 0; index < timingSolarStates.size(); index++) {
                Map<String, Object> nativeParity = new LinkedHashMap<>();
                timingRuns.get(index).put("nativeFootprint", nativeParity);
                requireNativeFootprint(timingSolarStates.get(index), nativeParity, ForgeDirection.SOUTH);
                require(Integer.valueOf(SOLAR_BODY_CELLS).equals(nativeParity.get("nativeCells")),
                    "The timed Solar footprint lost native positions");
            }
            timingSolarStates.clear();
            boolean passed = timingRuns.stream().allMatch(sampleResult ->
                Boolean.TRUE.equals(sampleResult.get("strictThresholdsPassed")));
            evidence.put("strictThresholdsPassed", passed);
            require(passed, "Workbench initialization or full Solar material rendering missed its deadline");
        }
        evidence.put("passed", true);
        return true;
    }

    private static Map<String, Map<String, Long>> workbenchActivity(NBTTagCompound before, NBTTagCompound after) {
        Map<String, Map<String, Long>> deltas = new TreeMap<>();
        for (Object entry : after.func_150296_c()) {
            String name = (String) entry;
            if (!name.startsWith("workbench")) continue;
            NBTTagCompound previous = before.getCompoundTag(name), current = after.getCompoundTag(name);
            Map<String, Long> delta = new LinkedHashMap<>();
            delta.put("calls", current.getLong("calls") - previous.getLong("calls"));
            delta.put("totalNanos", current.getLong("totalNanos") - previous.getLong("totalNanos"));
            deltas.put(name, delta);
        }
        return deltas;
    }

    private void timingScreenshot(Minecraft mc, Map<String, Object> sample) throws java.io.IOException {
        String name = "gtmb-timing-" + timingMode + "-" + (timingRuns.isEmpty() ? "first" : "reopen") + ".png";
        Files.createDirectories(output.resolve("screenshots"));
        ScreenShotHelper.saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        require(Files.isRegularFile(output.resolve("screenshots").resolve(name)), "Timing screenshot missing");
        sample.put("framebuffer", "screenshots/" + name);
        sample.put("framebufferStage", "RenderTick.END after first observed material Draw.Post");
    }

    private boolean fixtureController(BaseMetaTileEntity base) {
        return parity == null ? base.getMetaTileEntity() instanceof MTEElectricBlastFurnace
            : parity.matches(base.getMetaTileEntity());
    }

    private boolean parityStep(Minecraft mc, HologramState state) throws Exception {
        if (workbench != null) workbench.observe(state);
        if (!state.data.getBoolean("projectionDataComplete") || state.data.getBoolean("capturePending")) return false;
        NBTTagCompound channels = state.data.getCompoundTag("channels");
        int main = state.data.getInteger("main");
        int signal = "main".equals(parity.channel) ? main : channels.getInteger(parity.channel);
        if (parityPass == 1 && signal != parity.nextSignal) return false;
        if (parityDataReady == 0) parityDataReady = System.nanoTime();
        boolean fixed = parity == NativeParity.SOLAR || parity == NativeParity.ENTANGLER;
        HologramLocalMachineState telemetry = controller.localMachineState();
        NBTTagCompound metrics = HologramRuntimeMetrics.snapshot();
        NBTTagList stages = metrics.getTagList("loadStages", 10);
        long target = 0, installed = 0, firstDraw = 0;
        for (int i = 0; i < stages.tagCount(); i++) {
            NBTTagCompound mark = stages.getCompoundTagAt(i);
            long at = mark.getLong("atNanos");
            if (at < controllerClicked || !state.session.equals(mark.getString("session"))) continue;
            String markedStage = mark.getString("stage");
            if (target == 0 && markedStage.equals("CLIENT_CONTROLLER_TARGET")) target = at;
            if (installed == 0 && markedStage.equals("CLIENT_INSTALL")) installed = at;
            if (firstDraw == 0 && markedStage.equals("CLIENT_FIRST_MATERIAL") && (target > 0 || !workbenchStages))
                firstDraw = at;
        }
        if (workbench != null && !workbench.sawMaterial() && firstDraw == 0) return false;
        if (fixed) {
            evidence.put("pendingTelemetry", telemetry.caption());
            evidence.put("pendingNativeDataReadyNanos", parityDataReady - controllerClicked);
            evidence.put("pendingNativeMeshDraws", MiaoVisonLiteRenderer.previewDirectMeshDraws - controllerDrawsAtStart);
            boolean synced = parityPass == 0 ? telemetry.operation() == HologramLocalMachineState.Operation.DISABLED
                : telemetry.operation() == HologramLocalMachineState.Operation.IDLE && telemetry.tier() == 3;
            if (!synced) {
                require(System.nanoTime() - parityDataReady < 4_000_000_000L,
                    "Native data published but the expected machine sync is missing: " + evidence);
                return false;
            }
            if (firstDraw == 0 && System.nanoTime() - parityDataReady < 500_000_000L) return false;
        }

        Map<String, Object> proof = new LinkedHashMap<>();
        NBTTagCompound capability = controller.ui.config.capability(parity.channel);
        proof.put("channel", parity.channel);
        proof.put("signal", signal);
        proof.put("effectiveSignal", capability == null ? null : capability.getInteger("current"));
        proof.put("dataReadyNanos", (installed > 0 ? installed : parityDataReady) - controllerClicked);
        proof.put("firstMaterialDrawNanos", firstDraw > 0 ? firstDraw - controllerClicked : null);
        if (workbench != null) {
            proof.put("workbenchStages", workbench.receipt(state, stages,
                Boolean.getBoolean(FLAG + "RequireEarly"), Boolean.getBoolean(FLAG + "RequireEarlyFrame")));
            proof.put("orderedCandidateSamples", candidateSamples(state));
            List<int[]> unobserved = unobservedActualSamples(mc, state);
            proof.put("unobservedActualSamples", unobserved);
            if (Boolean.getBoolean(FLAG + "RequireUnobservedActual"))
                require(!unobserved.isEmpty(), "Fixture did not exercise any unobserved live positions");
        }
        if (fixed) {
            proof.put("observedOperation", telemetry.operation().name());
            proof.put("observedTier", telemetry.tier());
            proof.put("observedCaption", telemetry.caption());
        }
        NBTTagCompound measured = metrics.getCompoundTag("activity");
        Map<String, Map<String, Long>> timings = new LinkedHashMap<>();
        for (Object key : measured.func_150296_c()) {
            String name = (String) key;
            if (!name.startsWith("localController") && !name.startsWith("workbench")) continue;
            NBTTagCompound timer = measured.getCompoundTag(name);
            NBTTagCompound before = activityBeforeParityEdit.getCompoundTag(name);
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("calls", timer.getLong("calls") - before.getLong("calls"));
            values.put("totalNanos", timer.getLong("totalNanos") - before.getLong("totalNanos"));
            if (parityPass == 0) values.put("maxNanos", timer.getLong("maxNanos"));
            timings.put(name, values);
        }
        proof.put("localReadTimings", timings);
        evidence.put(parityPass == 0 ? "nativeBeforeChannelEdit" : "nativeAfterChannelEdit", proof);
        Map<String, String> actual = requireNativeFootprint(state, proof);
        if (parity == NativeParity.CENTER) {
            require(((Integer) proof.get("machineCells")) >= 7, "Mineral Integration Center preview ports are missing");
            require(capability != null && capability.getInteger("current") == (parityPass == 0 ? 1 : parity.nextSignal),
                "Mineral Integration Center must report its actual sector signal");
        }
        if (parity == NativeParity.CRITICAL)
            require(((Integer) proof.get("maximumRelativeY")) > 127, "Critical Solar preview lost its upper structure");
        requireLiveController(mc);
        if (Boolean.getBoolean(FLAG + "ExerciseCandidates")) {
            require(parity == NativeParity.EBF, "Use the electric blast furnace for the candidate edit fixture");
            proof.put("candidateEdit", exerciseCandidates(state));
        }

        if (fixed) {
            int checked = 0;
            for (HologramState.Cell cell : state.cells) {
                if (cell.anchor || !mc.theWorld.blockExists(cell.x, cell.y, cell.z)) continue;
                String actualId = net.minecraft.block.Block.blockRegistry
                    .getNameForObject(mc.theWorld.getBlock(cell.x, cell.y, cell.z));
                require(actualId.equals(cell.id) && mc.theWorld.getBlockMetadata(cell.x, cell.y, cell.z) == cell.meta,
                    "Local preview did not preserve the loaded position's actual block and metadata");
                checked++;
            }
            require(checked > 0, "The fixture provided no loaded positions for the live comparison");
            proof.put("loadedPositionsMatched", checked);
            if (parity == NativeParity.SOLAR && parityPass == 0) {
                solarTelemetryRequested = true;
                firstParityCells = actual;
                beginParityEdit();
                controller.ui.config.configure("main", parity.nextSignal);
                require(controller.ui.session.state().data.getBoolean("capturePending"),
                    "The production configuration did not queue a new solar capture");
                zero("nativeChannelChanged");
                return false;
            }
            if (parity == NativeParity.SOLAR) {
                boolean changed = !firstParityCells.equals(actual);
                proof.put("geometryChanged", changed);
                require(changed, "Solar tier selection did not change its preview targets");
            }
            zero("nativeFixedStructurePresented");
            mc.displayGuiScreen(null);
            zero("nativeFixedStructureClosed");
            require(finish(mc), "Fixed structure fixture cleanup must complete without server inventory");
            evidence.put("passed", true);
            return true;
        }

        if (parityPass == 0) {
            firstParityCells = actual;
            require(capability != null && capability.getBoolean("editable"), "Native channel is unavailable: " + parity.channel);
            NBTTagList options = capability.getTagList("options", 10);
            if (parity == NativeParity.CHEMICAL)
                require(options.tagCount() == 9, "Chemical Plant requires eight casing choices and reset");
            int index = -1;
            for (int candidate = 0; candidate < options.tagCount(); candidate++) {
                if (options.getCompoundTagAt(candidate).getInteger("value") == parity.nextSignal) {
                    index = candidate;
                    break;
                }
            }
            require(index >= 0, "Native channel choice missing: " + parity.nextSignal);
            beginParityEdit();
            controller.ui.config.chooseOption(capability, index);
            zero("nativeChannelChanged");
        } else {
            boolean changed = !firstParityCells.equals(actual);
            proof.put("geometryChanged", changed);
            // GTSR V2 registers the same Solar geometry for every tier piece. Both passes still compare with NEI.
            if (parity != NativeParity.CRITICAL) require(changed, "Native channel edit left the preview unchanged");
            if (parity == NativeParity.CENTER)
                require(actual.size() > firstParityCells.size(), "Selecting six sectors must expand the footprint");
            if (parity == NativeParity.GTNL || parity == NativeParity.TST)
                require(firstParityCells.keySet().equals(actual.keySet()),
                    "A material tier must preserve the original native coordinates");
            zero("nativeChannelPresented");
            mc.displayGuiScreen(null);
            zero("nativeChannelEditorClosed");
            require(finish(mc), "Native parity fixture cleanup must complete without awaiting server inventory");
            evidence.put("passed", true);
            return true;
        }
        return false;
    }

    private void beginParityEdit() {
        parityPass++;
        parityDataReady = 0;
        controllerDrawsAtStart = MiaoVisonLiteRenderer.previewDirectMeshDraws;
        activityBeforeParityEdit = HologramRuntimeMetrics.snapshot().getCompoundTag("activity");
        controllerClicked = System.nanoTime();
        if (workbenchStages)
            workbench = new HologramWorkbenchStagesWitness(controller.ui.session.state(), controllerClicked);
    }

    private static List<Map<String, Object>> candidateSamples(HologramState state) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Integer> samples = new LinkedHashMap<>();
        for (HologramState.Cell cell : state.cells) {
            if (cell.anchor) continue;
            String category = cell.candidates.isEmpty() ? cell.candidateComplete ? "empty" : "unprovenEmpty"
                : cell.candidateComplete ? "complete" : "partial";
            if (samples.getOrDefault(category, 0) >= 3) continue;
            samples.merge(category, 1, Integer::sum);
            NBTTagCompound row = cell.tag();
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("category", category);
            sample.put("index", cell.index);
            sample.put("relative", new int[] { cell.dx, cell.dy, cell.dz });
            sample.put("boundary", cell.candidateBoundary);
            sample.put("role", cell.role);
            sample.put("candidateRoles", row.getTagList("candidateRoles", 8).toString());
            sample.put("candidateTiers", row.getIntArray("candidateTiers"));
            sample.put("hatchFacings", row.getIntArray("hatchFacings"));
            sample.put("chosenChoice", cell.chosenChoice);
            sample.put("candidates", cell.candidates.stream()
                .map(stack -> stack.writeToNBT(new NBTTagCompound()).toString()).collect(Collectors.toList()));
            result.add(sample);
            if (result.size() == 12) break;
        }
        return result;
    }

    private static List<int[]> unobservedActualSamples(Minecraft mc, HologramState state) {
        List<int[]> result = new ArrayList<>();
        var world = mc.theWorld;
        for (HologramState.Cell cell : state.cells) {
            if (cell.anchor) continue;
            int cx = cell.x >> 4, cz = cell.z >> 4;
            boolean loaded = cell.y >= 0 && cell.y < world.getHeight() && world.getChunkProvider().chunkExists(cx, cz);
            if (loaded && !(world.getChunkFromChunkCoords(cx, cz) instanceof EmptyChunk)) continue;
            NBTTagCompound row = cell.tag();
            // Chunk availability can change after sampling; assert only observations the capture declared missing.
            if (!row.getBoolean("localObservationMissing")) continue;
            require(!cell.wantId.isEmpty() && cell.actualStack == null && !row.getString("reason").isEmpty(),
                "An unreadable live position replaced or supplied a target observation");
            result.add(new int[] { cell.dx, cell.dy, cell.dz });
            if (result.size() == 3) break;
        }
        return result;
    }

    private Map<String, Object> exerciseCandidates(HologramState baseline) {
        HologramState.Cell manual = null, batch = null;
        int manualChoice = -1, batchChoice = -1, tier = -1;
        for (HologramState.Cell cell : baseline.cells) {
            if (cell.anchor || cell.candidates.isEmpty()) continue;
            if (manual == null) {
                byte[] facingMasks = cell.tag().getByteArray("candidateFacingMasks");
                for (int choice = 0; choice < Math.min(cell.candidates.size(), facingMasks.length); choice++) {
                    if (facingMasks[choice] == 0
                        || ItemStack.areItemStacksEqual(cell.targetStack, cell.candidates.get(choice))) continue;
                    manual = cell;
                    manualChoice = choice;
                    break;
                }
                if (manual == cell) continue;
            }
            int[] tiers = cell.tag().getIntArray("candidateTiers");
            for (int choice = 0; choice < Math.min(cell.candidates.size(), tiers.length); choice++) {
                if (tiers[choice] < 0) continue;
                batch = cell;
                batchChoice = choice;
                tier = tiers[choice];
                break;
            }
            if (manual != null && batch != null) break;
        }
        require(manual != null && batch != null, "EBF candidate fixture needs two editable candidate rows");
        NBTTagCompound pin = new NBTTagCompound();
        pin.setInteger("selected", manual.index);
        pin.setInteger("choiceindex", manualChoice);
        controller.ui.session.send("pin", pin, true);
        HologramState.Cell pinned = controller.ui.session.state().cells.get(manual.index);
        require("manual".equals(pinned.pinScope)
            && ItemStack.areItemStacksEqual(pinned.targetStack, manual.candidates.get(manualChoice)),
            "Single-cell pin used a stale or reordered candidate");
        int[] legalFacings = pinned.tag().getIntArray("hatchFacings");
        require(legalFacings.length > 0, "Native hatch pin did not publish the selected candidate's legal facings");
        controller.ui.preview.selected(manual.index);
        controller.ui.router.activate(99);
        HologramState.Cell turned = controller.ui.session.state().cells.get(manual.index);
        require(turned.tag().getBoolean("hasFacingPin")
            && Arrays.stream(legalFacings).anyMatch(direction -> direction == turned.targetFacing),
            "The production facing control did not pin a declared legal direction");
        controller.ui.router.activate(102);
        HologramState.Cell auto = controller.ui.session.state().cells.get(manual.index);
        require(!auto.tag().getBoolean("hasFacingPin") && !auto.tag().hasKey("targetDisplay")
            && Arrays.equals(legalFacings, auto.tag().getIntArray("hatchFacings"))
            && ItemStack.areItemStacksEqual(auto.targetStack, pinned.targetStack),
            "The production Auto control did not restore native auto without dropping the selected hatch");
        NBTTagCompound preset = new NBTTagCompound();
        preset.setInteger("tier", tier);
        controller.ui.session.send("hatchPreset", preset, true);
        HologramState planned = controller.ui.session.state();
        HologramState.Cell presetCell = planned.cells.get(batch.index);
        require("preset".equals(presetCell.pinScope)
            && ItemStack.areItemStacksEqual(presetCell.targetStack, batch.candidates.get(batchChoice)),
            "Tier preset missed its exact candidate row or picked a different item");
        HologramState.Cell kept = planned.cells.get(manual.index);
        require("manual".equals(kept.pinScope) && ItemStack.areItemStacksEqual(kept.targetStack, pinned.targetStack),
            "Tier preset overwrote the manual pin");
        controller.ui.session.send("clearHatchPreset", new NBTTagCompound(), true);
        controller.ui.session.send("restoreCell", pin, true);
        HologramState restored = controller.ui.session.state();
        require(ItemStack.areItemStacksEqual(restored.cells.get(manual.index).targetStack, manual.targetStack)
            && ItemStack.areItemStacksEqual(restored.cells.get(batch.index).targetStack, batch.targetStack),
            "Clear and restore failed to recover the original targets");
        require(baseline.data.getString("generation").equals(restored.data.getString("generation")),
            "Same-geometry candidate edits retired the geometry generation");
        zero("nativeCandidateEditsReturnedToBaseline");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("manualIndex", manual.index);
        result.put("manualChoice", manualChoice);
        result.put("presetIndex", batch.index);
        result.put("tier", tier);
        result.put("presetChoice", batchChoice);
        result.put("legalFacings", legalFacings);
        result.put("pinnedFacing", turned.targetFacing);
        result.put("autoFacingRestored", !auto.tag().getBoolean("hasFacingPin"));
        result.put("targetsRestored", true);
        return result;
    }

    private Map<String, String> requireNativeFootprint(HologramState state, Map<String, Object> proof) throws Exception {
        return requireNativeFootprint(state, proof, ForgeDirection.NORTH);
    }

    private Map<String, String> requireNativeFootprint(HologramState state, Map<String, Object> proof,
        ForgeDirection facing) throws Exception {
        Map<String, String> actual = new TreeMap<>();
        int anchors = 0, ports = 0, maximumRelativeY = 0;
        for (HologramState.Cell cell : state.cells) {
            require(cell.prompt.isEmpty(), "Native structure text reached the player annotation layer");
            if (cell.anchor) {
                anchors++;
                require(cell.x == x && cell.y == y && cell.z == z && cell.dx == 0 && cell.dy == 0 && cell.dz == 0
                    && cell.targetStack != null && controllerItem.isItemEqual(cell.targetStack),
                    "Controller anchor does not match the placed native fixture");
                continue;
            }
            if ("minecraft:air".equals(cell.wantId)) continue;
            maximumRelativeY = Math.max(maximumRelativeY, cell.dy);
            ItemStack stack = cell.targetStack;
            int machine = stack != null && stack.getItem() instanceof ItemMachines ? stack.getItemDamage() : -1;
            if (machine >= 0) ports++;
            String coordinate = HologramNativeControllerBrowserReference.position(cell.dx, cell.dy, cell.dz);
            String row = HologramNativeControllerBrowserReference.block(
                cell.wantId, cell.wantMeta, machine, cell.targetFacing);
            require(actual.put(coordinate, row) == null, "Duplicate native footprint at " + coordinate);
        }
        proof.put("controllerAnchors", anchors);
        require(anchors == 1, "Native structure screen must retain the exact controller anchor");
        long referenceStarted = System.nanoTime();
        Map<String, String> reference = HologramNativeControllerBrowserReference.cells(
            controllerItem, facing, state.data.getInteger("main"), state.data.getCompoundTag("channels"));
        proof.put("blockRendererReferenceNanos", System.nanoTime() - referenceStarted);
        proof.put("nativeCells", actual.size());
        proof.put("blockRendererCells", reference.size());
        proof.put("machineCells", ports);
        proof.put("maximumRelativeY", maximumRelativeY);
        proof.put("descriptions", state.data.getTagList("structureDescription", 8).tagCount());
        ArrayList<String> differences = new ArrayList<>();
        for (Map.Entry<String, String> entry : reference.entrySet()) {
            String observed = actual.get(entry.getKey());
            if (!entry.getValue().equals(observed) && differences.size() < 8)
                differences.add(entry.getKey() + " expected " + entry.getValue() + " observed " + observed);
        }
        for (String coordinate : actual.keySet())
            if (!reference.containsKey(coordinate) && differences.size() < 8)
                differences.add(coordinate + " unexpected " + actual.get(coordinate));
        proof.put("sampleDifferences", differences);
        proof.put("matchesBlockRenderer", actual.equals(reference));
        require(!reference.isEmpty() && actual.equals(reference), "Native footprint differs from BlockRenderer: " + proof);
        return actual;
    }

    private void requireLiveController(Minecraft mc) {
        require(mc.theWorld.getTileEntity(x, y, z) instanceof BaseMetaTileEntity base
            && fixtureController(base) && base.getFrontFacing() == ForgeDirection.NORTH, "Live controller changed during preview");
    }

    private enum NativeParity {
        CENTER("com.miaokatze.gtsr.common.machine.orecenter.MTEMineralIntegrationCenter",
            "gtsr.ore_integration_center", "ore_center_sectors", 6),
        CRITICAL("com.miaokatze.gtsr.common.machine.MTECriticalMachine", "gtsr.critical.solar", "main", 2),
        SOLAR("com.miaokatze.gtsr.common.machine.MTELargeSolarOverpressureArray",
            "gtsr.large.solar.overpressure.array", "main", 2),
        TURBINE("com.miaokatze.gtsr.common.machine.MTEMegaSteamTurbineArray",
            "gtsr.mega.steam.turbine.array", "lenght", 3),
        ENTANGLER("com.miaokatze.gtsr.common.machine.MTESteamSingularityEntangler",
            "gtsr.steam.singularity.entangler", "main", 1),
        CHEMICAL("gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.production.chemplant.MTEChemicalPlant",
            "", "casing", 8),
        GTNL("com.science.gtnl.common.machine.multiblock.structuralReconstructionPlan.ElectricBlastFurnace",
            "ElectricBlastFurnace", "coil", 2),
        TST("com.Nxer.TwistSpaceTechnology.system.Disassembler.TST_Disassembler", "NameTSTDisassembler", "component", 2),
        EBF(MTEElectricBlastFurnace.class.getName(), "", "coil", 2);

        final String type, name, channel;
        final int nextSignal;

        NativeParity(String type, String name, String channel, int nextSignal) {
            this.type = type;
            this.name = name;
            this.channel = channel;
            this.nextSignal = nextSignal;
        }

        static NativeParity configured() {
            String configured = System.getProperty(FLAG + "NativeParity", "");
            return configured.isEmpty() ? null : valueOf(configured.toUpperCase(java.util.Locale.ROOT));
        }

        boolean matches(IMetaTileEntity machine) {
            return machine != null && type.equals(machine.getClass().getName())
                && (name.isEmpty() || name.equals(machine.getMetaName()));
        }

        ItemStack item() {
            for (IMetaTileEntity prototype : GregTechAPI.METATILEENTITIES)
                if (matches(prototype)) return prototype.getStackForm(1);
            throw new IllegalStateException("Registered native controller unavailable: " + type + " " + name);
        }
    }

    private boolean constructionStep(Minecraft mc) throws Exception {
        HologramState state = HologramClient.state;
        if (constructionReview == null) {
            if (!(mc.currentScreen instanceof HologramScreen review) || state == null
                || !state.data.getBoolean("blueprintAwaitingConfirmation")
                || !review.ui.review.materialControlEnabled(55)) return false;
            NBTTagCompound quote = state.data.getCompoundTag("materialQuote");
            if (!quote.getBoolean("complete")) return false;
            require(
                !review.localControllerStandard() && state.data.getBoolean("virtualLocked")
                    && state.data.getLong("placementReviewNonce") > 0
                    && state.data.getInteger("missing") == 0
                    && projectedPlacement.getString("placementRequestId").equals(state.data.getString("executionRequestId")),
                "Typed material review did not accept this locked single-stone placement");
            require(state.session.equals(review.ui.session.state().session) && state.session.equals(quote.getString("session")),
                "Material review is for another session");
            NBTTagList rows = quote.getTagList("rows", 10);
            NBTTagCompound row = rows.getCompoundTagAt(0);
            ItemStack stone = HologramMaterialQuote.readItem(row);
            require(
                rows.tagCount() == 1 && "item".equals(row.getString("kind")) && stone != null
                    && stone.getItem() == Item.getItemFromBlock(Blocks.stone) && stone.getItemDamage() == 0
                    && row.getLong("required") == 1 && row.getLong("available") == 1 && row.getLong("missing") == 0,
                "Authoritative material review must request exactly the one available stone");
            constructionReview = review;
            constructionSession = state.session;
            evidence.put("materialReviewRequiredStone", 1);
            evidence.put("materialReviewAvailableStone", 1);
            evidence.put("materialReviewMissing", 0);
            serverObservationRequest = 1;
        }
        if (serverObservationRequest == 1) {
            Map<String, Object> before = beforeConfirm;
            if (before == null) return false;
            require(
                Boolean.TRUE.equals(before.get("air")) && Integer.valueOf(1)
                    .equals(before.get("playerStone"))
                    && mc.currentScreen == constructionReview
                    && constructionReview.ui.review.materialControlEnabled(55),
                "World or inventory changed before confirmation");
            evidence.put("authoritativeBeforeConfirm", before);
            long sequence = constructionReview.ui.session.sequence();
            require(
                constructionReview.ui.review.activateControl(55) && constructionReview.ui.session.sequence() > sequence,
                "Actual material confirm did not issue a command");
            serverObservationRequest = 2;
        }
        if (serverObservationRequest == 2) {
            if (state == null || !constructionSession.equals(state.session) || state.data.getInteger("job") != 3)
                return false;
            evidence.put("constructionCompletedJob", true);
            serverObservationRequest = 3;
        }
        Map<String, Object> after = afterConstruction;
        if (after == null) return false;
        require(
            packets().getOrDefault("action:confirmBlueprintPlacement", 0) == 1,
            "Construction did not use exactly one ordinary material confirmation");
        require(
            Boolean.FALSE.equals(after.get("air")) && "minecraft:stone".equals(after.get("block"))
                && Integer.valueOf(0)
                    .equals(after.get("metadata"))
                && Integer.valueOf(0)
                    .equals(after.get("playerStone")),
            "Construction did not place and pay for one stone in the real server world");
        evidence.put("authoritativeAfterConstruction", after);
        if (!finish(mc)) return false;
        evidence.put("constructionPassed", true);
        evidence.put("passed", true);
        return true;
    }

    private void observeWorld(WorldServer world) {
        int request = serverObservationRequest;
        if (request == 1 && beforeConfirm != null || request == 3 && afterConstruction != null
            || request != 1 && request != 3) return;
        require(serverActor.worldObj == world && lockedAnchor != null, "Construction witness lost its owned world");
        int[] at = lockedAnchor;
        require(world.blockExists(at[0], at[1], at[2]), "Construction target chunk unloaded");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("air", world.isAirBlock(at[0], at[1], at[2]));
        snapshot
            .put("block", String.valueOf(Block.blockRegistry.getNameForObject(world.getBlock(at[0], at[1], at[2]))));
        snapshot.put("metadata", world.getBlockMetadata(at[0], at[1], at[2]));
        snapshot.put("playerStone", stones(serverActor));
        snapshot.put("worldTick", world.getTotalWorldTime());
        if (request == 1) beforeConfirm = snapshot;
        else afterConstruction = snapshot;
    }

    private static int stones(EntityPlayer actor) {
        int total = 0;
        for (ItemStack stack : actor.inventory.mainInventory)
            if (stack != null && stack.getItem() == Item.getItemFromBlock(Blocks.stone) && stack.getItemDamage() == 0)
                total += stack.stackSize;
        return total;
    }

    private void verifyProjectedPlacement() {
        NBTTagCompound payload = projectedPlacement;
        Set<?> keys = payload.func_150296_c();
        require(PLACEMENT_FIELDS.equals(keys), "Typed placement sent an unexpected field or a blueprint payload: " + keys);
        require(payload.hasKey("action", 8) && "projectedPlacement".equals(payload.getString("action"))
            && payload.hasKey("placementRequestId", 8) && payload.hasKey("placementWireVersion", 3)
            && payload.getInteger("placementWireVersion") == 2, "Typed placement wire identity missing");
        String id = payload.getString("placementRequestId");
        require(id.length() == 36 && UUID.fromString(id).toString().equals(id), "Placement request id is not canonical");
        require(payload.hasKey("clientSnapshotProtocol", 3) && payload.getInteger("clientSnapshotProtocol") == 1
            && payload.hasKey("clientSnapshotPalette", 3)
            && payload.getInteger("clientSnapshotPalette") == HologramSnapshotPalette.VERSION
            && payload.hasKey("clientSnapshotFlow", 3) && payload.getInteger("clientSnapshotFlow") == 1,
            "Playback did not observe the current Action snapshot transport envelope");
        require(Arrays.equals(lockedAnchor, payload.getIntArray("placementAnchor")), "Request anchor lost local lock");
        int[] operations = payload.getIntArray("placementOperations");
        NBTTagList variants = payload.getTagList("placementVariants", 10);
        NBTTagList materials = payload.getTagList("placementMaterials", 10);
        require(operations.length > 0 && operations.length % 4 == 0 && variants.tagCount() > 0 && materials.tagCount() > 0,
            "Missing projected operations, variants or material tally");
        if (construction) {
            NBTTagCompound variant = variants.getCompoundTagAt(0), material = materials.getCompoundTagAt(0);
            require(Arrays.equals(operations, new int[] { 0, 0, 0, 0 }) && variants.tagCount() == 1
                && materials.tagCount() == 1 && "minecraft:stone".equals(variant.getString("block"))
                && "minecraft:stone".equals(variant.getString("item")) && variant.getInteger("metadata") == 0
                && variant.getInteger("damage") == 0 && variant.getInteger("facing") == -1
                && !variant.getBoolean("controller") && !variant.hasKey("device") && !variant.hasKey("itemState")
                && "minecraft:stone".equals(material.getString("item")) && material.getInteger("damage") == 0
                && material.getInteger("count") == 1, "Paid fixture expected one ordinary stone operation and one shell");
        }
        evidence.put("submittedPlacementRequestId", id);
        evidence.put("submittedPlacementWireVersion", payload.getInteger("placementWireVersion"));
        evidence.put("submittedOperations", operations.length / 4);
        evidence.put("submittedMaterialRows", materials.tagCount());
        evidence.put(
            "submittedPayloadFields",
            keys.stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.toList()));
    }

    private MiaoVisonLiteRenderer.WorldRenderWitness witness() {
        MiaoVisonLiteRenderer.WorldRenderWitness witness = MiaoVisonLiteRenderer.lastWorldRenderWitness();
        return witness != null && witness.state == ghost && witness.lists > 0 ? witness : null;
    }

    private static void look(Minecraft mc, double x, double y, double z) {
        double dx = x - mc.thePlayer.posX, dy = y - mc.thePlayer.posY - mc.thePlayer.getEyeHeight(),
            dz = z - mc.thePlayer.posZ;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        mc.thePlayer.setAngles((yaw - mc.thePlayer.rotationYaw) / .15F, (mc.thePlayer.rotationPitch - pitch) / .15F);
        mc.entityRenderer.getMouseOver(1.0F);
    }

    private static boolean hitAt(Minecraft mc, int x, int y, int z) {
        MovingObjectPosition hit = mc.objectMouseOver;
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && hit.blockX == x
            && hit.blockY == y
            && hit.blockZ == z;
    }

    private static void rightClick(Minecraft mc) throws Exception {
        MouseEvent event = new MouseEvent();
        int button = mc.gameSettings.keyBindUseItem.getKeyCode() + 100;
        require(button >= 0 && button < 16, "Configured use binding is not a mouse button");
        field(MouseEvent.class, "button").setInt(event, button);
        field(MouseEvent.class, "buttonstate").setBoolean(event, true);
        MinecraftForge.EVENT_BUS.post(event);
        require(!event.isCanceled(), "Native world mouse press was intercepted");
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK)
            mc.playerController.onPlayerRightClick(
                mc.thePlayer,
                mc.theWorld,
                mc.thePlayer.getHeldItem(),
                hit.blockX,
                hit.blockY,
                hit.blockZ,
                hit.sideHit,
                hit.hitVec);
        else mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
    }

    private void placementUse(Minecraft mc) throws Exception {
        if (postFocus) HologramLocalPlacement.INSTANCE.use(false);
        else rightClick(mc);
    }

    private void stagePrivatePair(Minecraft mc) throws Exception {
        Path root = Files.createDirectory(output.resolve("local-stage-private-library"));
        require(
            !root.toRealPath()
                .startsWith(
                    mc.mcDataDir.toPath()
                        .toRealPath()),
            "Private fixture outside client required");
        Path library = Files.createDirectory(root.resolve("MiaoBlueprints"));
        Path path = library.resolve(localId + ".json");
        byte[] json, png;
        String supplied = System.getProperty(SOURCE, "");
        require(!construction || supplied.isEmpty(), "Paid one-stone proof requires its own construction document");
        if (!supplied.isEmpty()) {
            HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact.readPair(Paths.get(supplied));
            if (pressureReview) require(HologramPressureReviewCase.matchesFrozen(pair),
                "Pressure review requires the exact frozen original-123369 pair");
            json = pair.json;
            png = pair.png;
        } else {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            BufferedImage thumbnail = new BufferedImage(
                HologramBlueprintArtifact.WIDTH,
                HologramBlueprintArtifact.HEIGHT,
                BufferedImage.TYPE_INT_RGB);
            require(ImageIO.write(thumbnail, "png", buffer), "PNG required");
            byte[] source = stoneBlueprint();
            if (materialsOnly) source = HologramBlueprintCompactCodec.recompress(
                source, HologramBlueprintArtifact.MAX_JSON_BYTES);
            HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact.prepareSave(source, buffer.toByteArray());
            json = pair.json;
            png = pair.png;
        }
        if (materialsOnly || pressureReview) {
            var saved = HologramBlueprintArtifact.writePairPending(path, json, png, () -> true);
            try {
                HologramBlueprintFiles.track(library, localId, saved);
            } catch (java.io.IOException | RuntimeException failure) {
                saved.rollback();
                throw failure;
            }
        } else HologramBlueprintArtifact.writePair(path, json, png);
        evidence.put("privateSourceKind", pressureReview ? "exact frozen 123369 pair" : materialsOnly ? "owned indexed compact one-stone pair"
            : supplied.isEmpty() ? "owned schema2 saved pair" : "supplied saved pair");
        HologramBlueprintArtifact.Artifact staged = HologramBlueprintArtifact.readPair(path);
        evidence.put("privateSourceJsonSha256", staged.jsonSha256);
        evidence.put("privateSourcePngSha256", staged.pngSha256);
        Field store = field(HologramLibraryClient.class, "store");
        originalStore = (HologramClientLibrary) store.get(HologramLibraryClient.INSTANCE);
        fixtureStore = new HologramClientLibrary(root);
        store.set(HologramLibraryClient.INSTANCE, fixtureStore);
    }

    private byte[] stoneBlueprint() {
        JsonObject blueprint = new JsonObject();
        blueprint.addProperty("schema", 2);
        blueprint.addProperty("id", localId);
        blueprint.addProperty("name", "Local Preview Stages");
        blueprint.addProperty("controllerType", "area");
        blueprint.addProperty("createdAt", 1234);
        JsonObject version = new JsonObject();
        version.addProperty("formatVersion", 1);
        version.addProperty("gtmbVersion", "0.3.39");
        version.addProperty("gtnhVersion", "unknown");
        version.addProperty("gtnhVersionSource", "unavailable");
        blueprint.add("nativeBlueprint", version);
        JsonArray cells = new JsonArray();
        int size = construction || materialsOnly ? 1 : 3;
        for (int x = 0; x < size; x++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("item", "minecraft:stone");
            cell.addProperty("block", "minecraft:stone");
            cell.addProperty("count", 1);
            cell.addProperty("facing", -1);
            cell.addProperty("x", x);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("metadata", 0);
            cell.addProperty("damage", 0);
            cells.add(cell);
        }
        blueprint.add("cells", cells);
        JsonObject share = new JsonObject();
        share.addProperty("schema", 2);
        share.add("blueprint", blueprint);
        return share.toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    private void observeOutbound() throws Exception {
        Object wrapper = field(HologramNetwork.class, "channel").get(null);
        channel = (Channel) ((Map<?, ?>) field(wrapper.getClass(), "channels").get(wrapper)).get(Side.CLIENT);
        require(
            channel != null && channel.pipeline()
                .get(HANDLER) == null,
            "Expected actual GTMB outbound channel");
        Field action = field(HologramNetwork.Packet.class, "tag");
        Field library = field(HologramNetwork.LibraryRequest.class.getSuperclass(), "tag");
        channel.pipeline()
            .addLast(HANDLER, new ChannelOutboundHandlerAdapter() {

                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
                    throws Exception {
                    String name;
                    NBTTagCompound placement = null;
                    if (message instanceof HologramNetwork.Action) {
                        NBTTagCompound payload = (NBTTagCompound) action.get(message);
                        String op = payload.getString("action");
                        name = "action:" + (op.isEmpty() ? payload.getString("op") : op);
                        if ("projectedPlacement".equals(op)) placement = (NBTTagCompound) payload.copy();
                    } else if (message instanceof HologramNetwork.LibraryRequest) {
                        name = "library:" + ((NBTTagCompound) library.get(message)).getString("op");
                    } else name = message.getClass()
                        .getSimpleName();
                    synchronized (outbound) {
                        outbound.merge(name, 1, Integer::sum);
                        if (placement != null) projectedPlacement = placement;
                    }
                    HologramPressureReviewCase owned = pressure;
                    if (materialsOnly || construction && submitInput
                        || pressureReview && owned != null && owned.forward(message,
                            message instanceof HologramNetwork.Action ? (NBTTagCompound) action.get(message) : null))
                        context.write(message, promise);
                    else {
                        io.netty.util.ReferenceCountUtil.release(message);
                        promise.setSuccess();
                    }
                }
            });
    }

    private Map<String, Integer> packets() {
        synchronized (outbound) {
            return new LinkedHashMap<>(outbound);
        }
    }

    private void zero(String phase) {
        Map<String, Integer> sent = packets();
        evidence.put("lastZeroCheckpoint", phase);
        evidence.put("preSubmitOutbound", sent);
        require(!submitInput && sent.isEmpty(), phase + " emitted a GTMB C2S packet: " + sent);
    }

    private static boolean libraryWorkerIdle() throws Exception {
        ThreadPoolExecutor worker = (ThreadPoolExecutor) field(HologramLibraryClient.class, "worker")
            .get(HologramLibraryClient.INSTANCE);
        return worker.getActiveCount() == 0 && worker.getQueue()
            .isEmpty();
    }

    boolean finish(Minecraft mc) throws Exception {
        if (finished) return true;
        if (construction || materialsOnly || pressureReview) {
            restoreServerInventory = true;
            if (mc.thePlayer != null && (!serverInventoryRestored
                || !ItemStack.areItemStacksEqual(originalTool, mc.thePlayer.inventory.getStackInSlot(toolSlot))
                || (materialsOnly || pressureReview) && !ItemStack.areItemStacksEqual(originalStone, mc.thePlayer.inventory.getStackInSlot(stoneSlot))))
                return false;
        }
        if (fixtureStore != null) {
            if (!cancelling && HologramLibraryClient.INSTANCE.busy()) {
                HologramLibraryClient.INSTANCE.cancel();
                cancelling = true;
            }
            if (!libraryWorkerIdle()) return false;
            Map<?, ?> sources = (Map<?, ?>) field(HologramLibraryClient.class, "localPreviews")
                .get(HologramLibraryClient.INSTANCE);
            synchronized (sources) {
                sources.remove("local:" + localId);
            }
            Field store = field(HologramLibraryClient.class, "store");
            require(store.get(HologramLibraryClient.INSTANCE) == fixtureStore, "Private fixture library was replaced");
            store.set(HologramLibraryClient.INSTANCE, originalStore);
            evidence.put("privateStoreRestored", true);
        }
        if (fixtureStore != null && HologramLocalPlacement.INSTANCE.active()) HologramLocalPlacement.INSTANCE.clear();
        if (mc.thePlayer != null) {
            if (mc.thePlayer.inventory.getStackInSlot(toolSlot) == fixtureTool)
                mc.thePlayer.inventory.setInventorySlotContents(toolSlot, originalTool);
            mc.thePlayer.rotationYaw = yaw;
            mc.thePlayer.rotationPitch = pitch;
            mc.objectMouseOver = originalHit;
        }
        if (controller != null && mc.currentScreen == controller
            || constructionReview != null && mc.currentScreen == constructionReview
            || timingScreen != null && mc.currentScreen == timingScreen
            || mc.currentScreen instanceof HologramPlacementLoadingScreen
            || (materialsOnly || pressureReview) && mc.currentScreen instanceof HologramLibraryMaterialReviewScreen) mc.displayGuiScreen(null);
        if (construction) HologramClient.clearProjection();
        if (!pressureReview && channel != null && channel.pipeline()
            .get(HANDLER) != null) channel.pipeline()
                .remove(HANDLER);
        evidence.put("outboundIncludingTeardown", packets());
        if (fixedEbfEnabledMetrics) HologramRuntimeMetrics.setEnabled(false);
        if (materials != null) materials.close();
        finished = true;
        return true;
    }

    void retirePressureObserver() throws Exception {
        if (!pressureReview || pressureFinished) return;
        if (pressure != null) pressure.close();
        if (channel != null && channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
        pressureFinished = true;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field value = owner.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
