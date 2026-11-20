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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.register.ProjectorRegistry;

import cpw.mods.fml.relauncher.Side;
import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
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
    private final boolean postFocus = construction || Boolean.getBoolean(FLAG + "PostFocus");
    private final int x, y, z, toolSlot, stoneSlot;
    private final String actorName;
    private final String localId = "local_" + UUID.fromString(System.getProperty("miaotest.host.runId"));
    private final ItemStack originalTool, fixtureTool;
    private final MovingObjectPosition originalHit;
    private final float yaw, pitch;
    private volatile boolean fixtureReady;
    private volatile Throwable fixtureFailure;
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

    HologramLocalPreviewStagesProbe(Minecraft mc, Path output, Map<String, Object> receipt) {
        this.output = output;
        receipt.put("localPreviewStages", evidence);
        x = MathHelper.floor_double(mc.thePlayer.posX);
        y = MathHelper.floor_double(mc.thePlayer.boundingBox.minY);
        z = MathHelper.floor_double(mc.thePlayer.posZ) + 3;
        yaw = mc.thePlayer.rotationYaw;
        pitch = mc.thePlayer.rotationPitch;
        actorName = mc.thePlayer.getCommandSenderName();
        toolSlot = mc.thePlayer.inventory.currentItem;
        stoneSlot = (toolSlot + 1) % 9;
        originalTool = mc.thePlayer.getHeldItem();
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
                if (construction && serverActor != null) observeWorld(world);
                return;
            }
            EntityPlayer actor = world.getPlayerEntityByName(actorName);
            require(
                actor != null && world.blockExists(x, y, z) && world.isAirBlock(x, y, z),
                "Owned-world controller fixture site unavailable");
            ItemStack item = ItemList.Machine_Multi_BlastFurnace.get(1);
            ForgeDirection north = ForgeDirection.NORTH;
            require(
                item != null && item.getItem() instanceof ItemMachines machines
                    && machines.placeBlockAt(item, actor, world, x, y, z, north.ordinal(), .5F, .5F, .5F, 0),
                "Native EBF placement failed");
            require(
                world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity base
                    && base.getMetaTileEntity() instanceof MTEElectricBlastFurnace,
                "Native EBF fixture identity mismatch");
            ((BaseMetaTileEntity) world.getTileEntity(x, y, z)).setFrontFacing(north);
            world.markBlockForUpdate(x, y, z);
            if (construction) {
                require(
                    actor instanceof EntityPlayerMP && !(actor instanceof FakePlayer)
                        && !actor.capabilities.isCreativeMode
                        && stones(actor) == 0,
                    "Construction proof requires the real survival actor with no existing stones");
                serverActor = (EntityPlayerMP) actor;
                serverToolBefore = actor.inventory.getStackInSlot(toolSlot);
                serverStoneBefore = actor.inventory.getStackInSlot(stoneSlot);
                actor.inventory.setInventorySlotContents(toolSlot, new ItemStack(ProjectorRegistry.projector));
                actor.inventory.setInventorySlotContents(stoneSlot, new ItemStack(Blocks.stone));
                actor.inventoryContainer.detectAndSendChanges();
            }
            fixtureReady = true;
        } catch (RuntimeException | LinkageError problem) {
            fixtureFailure = problem;
        }
    }

    boolean step(Minecraft mc) throws Exception {
        evidence.put("stage", stage);
        evidence.put("worldInputFocused", HologramClient.worldInputFocused());
        if (fixtureFailure != null) throw new IllegalStateException("Owned fixture failed", fixtureFailure);
        require(
            System.nanoTime() - started < 90_000_000_000L,
            "Local preview proof timed out in stage " + stage
                + ", focus="
                + HologramClient.worldInputFocused()
                + ", outbound="
                + packets());
        if (stage == 0) {
            if (!fixtureReady || !(mc.theWorld.getTileEntity(x, y, z) instanceof BaseMetaTileEntity base)
                || !(base.getMetaTileEntity() instanceof MTEElectricBlastFurnace)
                || !postFocus && !HologramClient.worldInputFocused()) return false;
            if (construction
                && (!ItemNekoHologramProjector.isProjector(mc.thePlayer.getHeldItem()) || stones(mc.thePlayer) != 1))
                return false;
            observeOutbound();
            if (!construction) mc.thePlayer.inventory.setInventorySlotContents(toolSlot, fixtureTool);
            look(mc, x + .5, y + .5, z + .5);
            require(hitAt(mc, x, y, z), "EntityRenderer crosshair missed the real controller");
            controllerClicked = System.nanoTime();
            controllerClickTick = mc.thePlayer.ticksExisted;
            controllerDrawsAtStart = HologramRenderer.previewDirectMeshDraws;
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
            evidence.put("controllerStatus", state.data.getString("status"));
            require(!state.data.getBoolean("localStructureUnavailable"), "Local standard declaration unavailable");
            if (controllerDrawn != state || !controllerFrameCaptured) return false;
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
            HologramRenderer.WorldRenderWitness witness = witness();
            if (witness == null) return false;
            firstAnchor = witness.anchor();
            if (firstAnchor == null) return false;
            movedGeneration = witness.generation;
            evidence.put("movingCells", ghost.cells.size());
            evidence.put("privateMovingPresentedNanos", System.nanoTime() - privateRequested);
            evidence.put("movingFirstLists", witness.lists);
            evidence.put("movingFirstAnchor", firstAnchor);
            // Move the real camera ray from the control block to the ground beside the player.
            look(mc, x - 1.5, y - .5, z - 1.5);
            stage++;
        } else if (stage == 4) {
            zero("privateMoving");
            HologramRenderer.WorldRenderWitness witness = witness();
            int[] moved = HologramLocalPlacement.INSTANCE.anchor();
            if (witness == null || witness.generation <= movedGeneration
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
            HologramRenderer.WorldRenderWitness witness = witness();
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
        }
        return false;
    }

    void guiPost(GuiScreen gui, long frame) {
        if (finished || stage != 1 || controller == null || gui != controller) return;
        HologramState state = controller.ui.session.state();
        NBTTagCompound witness = HologramRenderer.lessonInspection(state);
        boolean complete = state.data.getBoolean("projectionDataComplete");
        boolean ready = HologramRenderer.previewMeshReady(state);
        boolean displayed = HologramRenderer.previewDisplayState() == state;
        long materialDraws = HologramRenderer.previewDirectMeshDraws - controllerDrawsAtStart;
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
            && state.cells.size() == 36
            && roles.getOrDefault("controller", 0) == 1
            && newMaterialDraws > 0) {
            controllerDrawn = state;
            ownDrawTail = frame;
        }
    }

    void renderTail(long frame) throws Exception {
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

    private boolean constructionStep(Minecraft mc) throws Exception {
        HologramState state = HologramClient.state;
        if (constructionReview == null) {
            if (!(mc.currentScreen instanceof HologramScreen review) || state == null
                || !state.data.getBoolean("blueprintAwaitingConfirmation")
                || !review.ui.review.materialControlEnabled(55)) return false;
            require(
                !review.localControllerStandard() && state.data.getBoolean("virtualLocked")
                    && state.data.getLong("placementReviewNonce") > 0
                    && state.data.getInteger("missing") == 0,
                "Original material review did not accept the locked single-stone placement");
            require(state.session.equals(review.ui.session.state().session), "Material review is for another session");
            require(
                state.materials.size() == 1 && state.materials.get(0).stack != null
                    && state.materials.get(0).stack.getItem() == Item.getItemFromBlock(Blocks.stone)
                    && state.materials.get(0).stack.getItemDamage() == 0
                    && state.materials.get(0).required == 1
                    && state.materials.get(0).available == 1,
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
        require(PLACEMENT_FIELDS.equals(keys), "Unexpected projected placement payload fields: " + keys);
        require(Arrays.equals(lockedAnchor, payload.getIntArray("placementAnchor")), "Request anchor lost local lock");
        int operations = payload.getIntArray("placementOperations").length;
        int needs = payload.getTagList("placementMaterials", 10)
            .tagCount();
        require(operations > 0 && operations % 4 == 0 && needs > 0, "Missing projected operations or material tally");
        evidence.put("submittedOperations", operations / 4);
        evidence.put("submittedMaterialRows", needs);
        evidence.put(
            "submittedPayloadFields",
            keys.stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.toList()));
    }

    private HologramRenderer.WorldRenderWitness witness() {
        HologramRenderer.WorldRenderWitness witness = HologramRenderer.lastWorldRenderWitness();
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
            json = pair.json;
            png = pair.png;
        } else {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            BufferedImage thumbnail = new BufferedImage(
                HologramBlueprintArtifact.WIDTH,
                HologramBlueprintArtifact.HEIGHT,
                BufferedImage.TYPE_INT_RGB);
            require(ImageIO.write(thumbnail, "png", buffer), "PNG required");
            HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact
                .prepareSave(stoneBlueprint(), buffer.toByteArray());
            json = pair.json;
            png = pair.png;
        }
        HologramBlueprintArtifact.writePair(path, json, png);
        evidence.put("privateSourceKind", supplied.isEmpty() ? "owned schema2 saved pair" : "supplied saved pair");
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
        int size = construction ? 1 : 3;
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
                    if (construction && submitInput) context.write(message, promise);
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
        if (construction) {
            restoreServerInventory = true;
            if (mc.thePlayer != null && (!serverInventoryRestored
                || !ItemStack.areItemStacksEqual(originalTool, mc.thePlayer.inventory.getStackInSlot(toolSlot))))
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
            || mc.currentScreen instanceof HologramPlacementLoadingScreen) mc.displayGuiScreen(null);
        if (construction) HologramClient.clearProjection();
        if (channel != null && channel.pipeline()
            .get(HANDLER) != null) channel.pipeline()
                .remove(HANDLER);
        evidence.put("outboundIncludingTeardown", packets());
        finished = true;
        return true;
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
