// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.miaokatze.gtmb.testlab.TestLab;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.interfaces.modularui.IControllerWithOptionalFeatures;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Installed community material/state samples through real native placement, one variant per lab tick. */
public final class HologramCommunityVariantPlacementProbe {

    private final WorldServer world;
    private final FakePlayer actor;
    private final List<HologramInstalledNativeTargetMatrix.Variant> variants;
    private final List<HologramInstalledNativeTargetMatrix.ControllerSample> controllers;
    private final HologramSession session;
    private final ItemStack tool, toolBefore;
    private final NBTTagList inventoryBefore, results = new NBTTagList();
    private final int x, y, z, toolSlot, supplySlot;
    private final double originalX, originalY, originalZ;
    private HologramNativeTargetPlan.Cell activeTarget;
    private int cursor, events;
    private boolean observing, restored;
    private final NBTTagList controllerResults = new NBTTagList();
    private int controllerCursor, controllerStage;
    private HologramPresets.Cell capturedController;
    private NBTTagCompound capturedControllerState, controllerDefaults, activeConfiguration;
    private ExtendedFacing activeAlignment;

    public HologramCommunityVariantPlacementProbe(WorldServer world, FakePlayer actor, int x, int y, int z,
        List<HologramInstalledNativeTargetMatrix.Variant> variants,
        List<HologramInstalledNativeTargetMatrix.ControllerSample> controllers) throws Exception {
        require(
            Boolean.getBoolean("gtmb.testlab.installedNativeTargetMatrix"),
            "Explicit installed matrix opt-in required");
        require(variants != null && !variants.isEmpty(), "No actual installed community variants were supplied");
        this.world = world;
        this.actor = actor;
        this.x = x;
        this.y = y;
        this.z = z;
        this.variants = variants;
        require(
            controllers != null && controllers.size() == 2,
            "The exact GTNL and LCR controller samples are required");
        this.controllers = controllers;
        toolSlot = actor.inventory.currentItem;
        tool = actor.getHeldItem();
        require(tool != null && tool.getItem() == ProjectorRegistry.projector, "Owned projector required");
        toolBefore = tool.copy();
        require(authority(), "Exact owned world/volume and non-OP survival actor required");
        emptyVolume(false);
        require(!HologramConstructionJournal.pending(actor), "Existing business journal forbids community fixture");
        int vacant = -1;
        for (int n = 0; n < actor.inventory.mainInventory.length; n++)
            if (n != toolSlot && actor.inventory.mainInventory[n] == null) {
                vacant = n;
                break;
            }
        require(vacant >= 0, "An empty single-material fixture slot is required");
        supplySlot = vacant;
        inventoryBefore = inventory();
        originalX = actor.posX;
        originalY = actor.posY;
        originalZ = actor.posZ;
        session = new HologramSession(actor, x + 1, y + 1, z + 1, false, 1, false);
        session.stockNativePrivate = true;
        session.materialSettings.main = true;
        session.materialSettings.containers = session.materialSettings.me = false;
        session.materialContext = new HologramMaterials.Context(actor, session.materialSettings, toolSlot);
        actor.setPosition(x + 1.5, y + 4, z + 1.5);
        MinecraftForge.EVENT_BUS.register(this);
        observing = true;
    }

    public boolean step() throws Exception {
        require(!restored && authority(), "Community fixture authority changed");
        if (cursor == variants.size()) return stepController();
        require(cursor < variants.size(), "Community variant cursor is invalid");
        require(inventoryBefore.equals(inventory()), "Previous community variant did not restore borrowed inventory");
        emptyVolume(false);
        HologramInstalledNativeTargetMatrix.Variant variant = variants.get(cursor);
        activeTarget = variant.target();
        ItemStack material = activeTarget.material();
        require(
            material != null && material.stackSize == 1
                && !HologramRecovery.hasSeal(material)
                && (!material.hasTagCompound() || material.getTagCompound()
                    .hasNoTags()),
            "UNSUPPORTED unknown/retained/sealed target; no community placement PASS is permitted");
        for (int n = 0; n < actor.inventory.mainInventory.length; n++) {
            ItemStack existing = actor.inventory.mainInventory[n];
            require(
                n == toolSlot || existing == null || !existing.isItemEqual(material),
                "Community material already exists outside the one-item fixture supply");
        }
        HologramNativeConstruction.Prepared prepared = HologramNativeConstruction.projection(activeTarget);
        actor.inventory.mainInventory[supplySlot] = material.copy();
        actor.inventory.markDirty();
        events = 0;
        long started = System.nanoTime();
        HologramNativeConstruction.Outcome outcome = HologramNativeConstruction
            .place(session, prepared, x + 1, y + 1, z + 1, this::authority);
        require(
            outcome == HologramNativeConstruction.Outcome.PLACED,
            "Actual installed community placement failed: " + session.status + " variant=" + variant.description());
        require(
            events == 1 && actor.inventory.mainInventory[supplySlot] == null,
            "Community variant must consume the single actual material and emit one final protection event");
        require(
            !HologramConstructionJournal.pending(actor) && session.job != 4,
            "Community placement left business recovery pending");
        require(
            inventoryBefore.equals(inventory()),
            "Community placement changed unrelated inventory or failed exact debit");
        requireTarget();
        emptyVolume(true);
        NBTTagCompound result = variant.description();
        result.setInteger("finalPlaceEvents", events);
        result.setInteger("actualMaterialDebit", 1);
        result.setInteger("actualMetadata", world.getBlockMetadata(x + 1, y + 1, z + 1));
        result.setString(
            "actualBlockClass",
            world.getBlock(x + 1, y + 1, z + 1)
                .getClass()
                .getName());
        result.setString(
            "nativeMaterialItemClass",
            material.getItem()
                .getClass()
                .getName());
        result.setLong("placementNanos", System.nanoTime() - started);
        TileEntity tile = world.getTileEntity(x + 1, y + 1, z + 1);
        result.setString(
            "actualTileClass",
            tile == null ? ""
                : tile.getClass()
                    .getName());
        if (tile instanceof IGregTechTileEntity gt) {
            result.setInteger("actualGtId", gt.getMetaTileID());
            result.setInteger(
                "actualFacing",
                gt.getFrontFacing()
                    .ordinal());
            result.setString(
                "actualMteClass",
                gt.getMetaTileEntity()
                    .getClass()
                    .getName());
            result.setString("actualOwner", String.valueOf(gt.getOwnerUuid()));
            result.setBoolean("nativeGtInitializationAndOwnershipVerified", true);
            if (gt.getMetaTileEntity() instanceof MTEHatch hatch) {
                NBTTagCompound state = new NBTTagCompound();
                hatch.saveNBTData(state);
                result.setInteger("actualTexturePage", state.getInteger("texturePage"));
                result.setInteger("actualTextureIndex", state.getInteger("textureIndex"));
            }
        }
        require(world.setBlockToAir(x + 1, y + 1, z + 1), "Could not clear the owned community sample");
        emptyVolume(false);
        require(inventoryBefore.equals(inventory()), "Community sample cleanup changed actor inventory");
        result.setBoolean("ownedCellAndBorrowedInventoryRestored", true);
        results.appendTag(result);
        activeTarget = null;
        cursor++;
        return false;
    }

    /** Source placement and each saved replay are separate real lab ticks; all use this exact owned cell. */
    private boolean stepController() throws Exception {
        require(controllerCursor < controllers.size(), "Saved-controller cursor is invalid");
        require(inventoryBefore.equals(inventory()), "Controller fixture inventory was not restored between samples");
        emptyVolume(false);
        var sample = controllers.get(controllerCursor);
        activeTarget = sample.target();
        activeAlignment = sample.alignment();
        activeConfiguration = controllerStage == 0 ? null
            : controllerStage == 1 ? capturedControllerState : controllerDefaults;
        NBTTagCompound result = sample.description();
        HologramNativeConstruction.Prepared prepared;
        if (controllerStage == 0) {
            result.setString("phase", "native-source-placement-and-production-capture");
            prepared = HologramNativeConstruction.projection(activeTarget);
        } else {
            boolean retain = controllerStage == 1;
            result.setString("phase", retain ? "saved-retain-true" : "saved-retain-false");
            result.setBoolean("retainNbt", retain);
            session.virtual = session.virtualPlacement = session.virtualLocked = session.open = true;
            session.virtualPreset = HologramTestLabBlueprintProbe
                .preset(actor.getUniqueID(), actor.getCommandSenderName());
            session.virtualPreset.retainNbt = retain;
            session.virtualPreset.retainCovers = false;
            session.placementRetainCovers = false;
            prepared = HologramNativeConstruction.saved(
                session,
                activeTarget.block(),
                activeTarget.metadata(),
                activeTarget.material(),
                capturedController.facing,
                capturedController.tileNbt,
                capturedController.itemNbt,
                activeAlignment);
            NBTTagCompound projected = prepared.buffers.tileEmpty;
            for (String key : new String[] { "mEUt", "checkRecipeResultID", "canBeMuffled", "mProgresstime",
                "mMaxProgresstime" })
                require(
                    projected == null || !projected.hasKey(key),
                    "Runtime observation leaked into saved target: " + key);
            result.setBoolean("runtimeObservationsProjectedOut", true);
            result.setTag(
                "expectedNativeConfiguration",
                activeConfiguration.getCompoundTag(HologramNativeConfiguration.TAG)
                    .copy());
        }
        ItemStack material = activeTarget.material();
        require(
            !HologramRecovery.hasSeal(material) && (!material.hasTagCompound() || material.getTagCompound()
                .hasNoTags()),
            "UNSUPPORTED retained/sealed controller payment material");
        for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++) {
            ItemStack existing = actor.inventory.mainInventory[slot];
            require(
                slot == toolSlot || existing == null || !existing.isItemEqual(material),
                "Controller material exists outside the one-item borrowed supply");
        }
        actor.inventory.mainInventory[supplySlot] = material.copy();
        actor.inventory.markDirty();
        events = 0;
        var outcome = HologramNativeConstruction.place(session, prepared, x + 1, y + 1, z + 1, this::authority);
        require(
            outcome == HologramNativeConstruction.Outcome.PLACED,
            "Controller saved/native placement rejected: " + result + " status=" + session.status);
        require(
            events == 1 && actor.inventory.mainInventory[supplySlot] == null && inventoryBefore.equals(inventory()),
            "Controller placement must consume exactly one real item and publish exactly one final event");
        require(
            !HologramConstructionJournal.pending(actor) && session.job != 4,
            "Controller placement left a pending world/payment recovery journal");
        requireTarget();
        emptyVolume(true);
        TileEntity tile = world.getTileEntity(x + 1, y + 1, z + 1);
        if (controllerStage == 0) captureController(tile, result);
        result.setInteger("finalPlaceEvents", events);
        result.setInteger("actualMaterialDebit", 1);
        result.setInteger("actualGtId", ((IGregTechTileEntity) tile).getMetaTileID());
        result.setString(
            "actualMteClass",
            ((IGregTechTileEntity) tile).getMetaTileEntity()
                .getClass()
                .getName());
        result.setString(
            "actualAlignment",
            ((MTEEnhancedMultiBlockBase<?>) ((IGregTechTileEntity) tile).getMetaTileEntity()).getExtendedFacing()
                .toString());
        NBTTagCompound actualConfiguration = new NBTTagCompound();
        HologramNativeConfiguration.capture(tile, actor, actualConfiguration);
        result.setTag(
            "actualNativeConfiguration",
            actualConfiguration.getCompoundTag(HologramNativeConfiguration.TAG)
                .copy());
        require(world.setBlockToAir(x + 1, y + 1, z + 1), "Could not clear the owned controller fixture");
        emptyVolume(false);
        require(inventoryBefore.equals(inventory()), "Controller fixture cleanup changed the original inventory");
        result.setBoolean("journalClear", true);
        result.setBoolean("ownedCellAndBorrowedInventoryRestored", true);
        controllerResults.appendTag(result);
        activeTarget = null;
        activeAlignment = null;
        activeConfiguration = null;
        if (++controllerStage == 3) {
            controllerStage = 0;
            controllerCursor++;
            capturedController = null;
            capturedControllerState = controllerDefaults = null;
        }
        return controllerCursor == controllers.size();
    }

    private void captureController(TileEntity tile, NBTTagCompound result) throws Exception {
        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
        require(
            gt.getMetaTileEntity() instanceof IControllerWithOptionalFeatures,
            "Installed controller has no native optional-feature configuration capability");
        IControllerWithOptionalFeatures features = (IControllerWithOptionalFeatures) gt.getMetaTileEntity();
        require(
            features.supportsBatchMode(),
            "Installed controller has no native batch setting for retained/default contrast");
        controllerDefaults = new NBTTagCompound();
        HologramNativeConfiguration.capture(tile, actor, controllerDefaults);
        boolean previousBatch = features.isBatchModeEnabled();
        features.setBatchMode(!previousBatch);
        require(
            features.isBatchModeEnabled() != previousBatch,
            "Native controller batch setter did not accept the fixture setting");
        tile.markDirty();
        NBTTagCompound before = HologramNativeCapture.capture(tile);
        HologramPresets.Preset literal = HologramTestLabBlueprintProbe
            .preset(actor.getUniqueID(), actor.getCommandSenderName());
        literal.cells.clear();
        literal.palette.clear();
        new HologramLiteralAreaWriter()
            .append(literal, activeTarget.block(), activeTarget.metadata(), tile, 0, 0, 0, actor);
        require(
            world.getTileEntity(x + 1, y + 1, z + 1) == tile && before.equals(HologramNativeCapture.capture(tile)),
            "Production literal controller capture changed source tile identity or persistent state");
        require(literal.cells.size() == 1, "Production literal capture did not produce exactly one controller cell");
        capturedController = literal.cells.get(0);
        require(
            capturedController.damage == activeTarget.material()
                .getItemDamage() && capturedController.facing == activeTarget.facing(),
            "Captured controller identity/facing differs from the real source");
        capturedControllerState = HologramBlueprintNbt.decode(capturedController.tileNbt);
        require(
            capturedControllerState != null && capturedControllerState.hasKey(HologramNativeConfiguration.TAG, 10),
            "Production capture did not retain the actual native controller configuration envelope");
        NBTTagCompound observations = new NBTTagCompound();
        for (String key : new String[] { "mEUt", "checkRecipeResultID", "canBeMuffled", "mProgresstime",
            "mMaxProgresstime", "mRuntime", "mTotalRunTime" })
            if (capturedControllerState.hasKey(key)) observations.setTag(
                key,
                capturedControllerState.getTag(key)
                    .copy());
        require(
            !observations.hasNoTags(),
            "Actual native controller capture did not exercise any reported runtime-observation field");
        result.setTag("actualSourceRuntimeObservations", observations);
        result.setBoolean("runtimeFieldsWrittenByProbe", false);
        result.setBoolean("productionCaptureSourceUnchanged", true);
        result.setBoolean("nativeBatchSettingToggled", true);
        result.setTag(
            "factoryDefaultConfiguration",
            controllerDefaults.getCompoundTag(HologramNativeConfiguration.TAG)
                .copy());
        result.setString("capturedTileNbt", capturedController.tileNbt);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void placed(BlockEvent.PlaceEvent event) {
        if (!observing || event.world != world || event.player != actor) return;
        require(
            activeTarget != null && event.x == x + 1 && event.y == y + 1 && event.z == z + 1,
            "Community native callback wrote outside the active sample cell");
        require(authority() && !event.isCanceled(), "Community sample lost authority or protection permission");
        requireTarget();
        events++;
    }

    private void requireTarget() {
        require(
            activeTarget != null && HologramBlockIdentity.sameForPlacement(
                world.getBlock(x + 1, y + 1, z + 1),
                world.getBlockMetadata(x + 1, y + 1, z + 1),
                activeTarget.block(),
                activeTarget.metadata()),
            "Actual community block/metadata differs from its native target");
        TileEntity tile = world.getTileEntity(x + 1, y + 1, z + 1);
        require(
            !activeTarget.block()
                .hasTileEntity(activeTarget.metadata()) || tile != null,
            "Native block callbacks did not create their required tile entity");
        if (activeTarget.material()
            .getItem() instanceof ItemMachines) {
            require(
                tile instanceof IGregTechTileEntity,
                "Native GT ItemMachines callback did not create its real tile");
            IGregTechTileEntity gt = (IGregTechTileEntity) tile;
            var prototype = ItemMachines.getMetaTileEntity(activeTarget.material());
            require(
                gt.getMetaTileEntity() != null && gt.getMetaTileEntity()
                    .getBaseMetaTileEntity() == gt
                    && gt.getMetaTileID() == activeTarget.material()
                        .getItemDamage()
                    && prototype != null
                    && prototype.getClass() == gt.getMetaTileEntity()
                        .getClass()
                    && actor.getUniqueID()
                        .equals(gt.getOwnerUuid()),
                "Native GT initialization/id/base/ownership was not retained");
            require(
                activeTarget.facing() < 0 || gt.getFrontFacing()
                    .ordinal() == activeTarget.facing(),
                "Real native GT callback/facing synchronization differs from the target");
            NBTTagCompound appearance = activeTarget.tileData();
            if (appearance != null && appearance.hasKey("texturePage", 3)) {
                require(gt.getMetaTileEntity() instanceof MTEHatch, "Texture-authored native target is not a hatch");
                NBTTagCompound state = new NBTTagCompound();
                gt.getMetaTileEntity()
                    .saveNBTData(state);
                require(
                    state.getInteger("texturePage") == appearance.getInteger("texturePage")
                        && state.getInteger("textureIndex") == appearance.getInteger("textureIndex"),
                    "Real GT hatch texture callback did not retain the native appearance");
            }
        }
        if (activeAlignment != null) {
            require(
                tile instanceof IGregTechTileEntity
                    && ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>,
                "Installed controller has no native enhanced alignment");
            require(
                ((MTEEnhancedMultiBlockBase<?>) ((IGregTechTileEntity) tile).getMetaTileEntity()).getExtendedFacing()
                    == activeAlignment,
                "Actual saved/native controller alignment differs");
        }
        if (activeConfiguration != null) try {
            require(
                HologramNativeConfiguration.matches(tile, actor, activeConfiguration),
                "Actual retained/default controller configuration differs from native capture");
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Cannot compare actual saved controller configuration", invalid);
        }
    }

    private void emptyVolume(boolean targetAllowed) {
        for (int dy = 0; dy < 3; dy++) for (int dz = 0; dz < 3; dz++) for (int dx = 0; dx < 3; dx++) {
            if (targetAllowed && dx == 1 && dy == 1 && dz == 1) continue;
            require(
                world.isAirBlock(x + dx, y + dy, z + dz) && world.getTileEntity(x + dx, y + dy, z + dz) == null,
                "Community variant wrote an unsupported extra cell; preserve the owned fixture");
        }
    }

    public void cleanup() {
        if (restored) return;
        if (observing) {
            observing = false;
            MinecraftForge.EVENT_BUS.unregister(this);
        }
        require(
            authority() && !HologramConstructionJournal.pending(actor),
            "Community fixture has unresolved recovery; preserve resources and world");
        if (!world.isAirBlock(x + 1, y + 1, z + 1)) {
            requireTarget();
            emptyVolume(true);
            require(world.setBlockToAir(x + 1, y + 1, z + 1), "Owned community sample cleanup failed");
        }
        ItemStack borrowed = actor.inventory.mainInventory[supplySlot];
        if (borrowed != null) {
            require(
                activeTarget != null && borrowed.stackSize == 1
                    && ItemStack.areItemStacksEqual(borrowed, activeTarget.material()),
                "Borrowed community material changed; preserve it");
            actor.inventory.mainInventory[supplySlot] = null;
            actor.inventory.markDirty();
        }
        emptyVolume(false);
        require(inventoryBefore.equals(inventory()), "Community actor inventory was not restored exactly");
        actor.setPosition(originalX, originalY, originalZ);
        restored = true;
    }

    public NBTTagCompound evidence() {
        require(
            cursor == variants.size() && controllerCursor == controllers.size() && restored,
            "Community variant/controller placement/cleanup is incomplete");
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("variants", results.copy());
        result.setInteger("completedVariants", cursor);
        result.setBoolean("fixtureAndBorrowedInventoryRestored", true);
        result.setBoolean("journalClear", true);
        result.setString(
            "entry",
            "installed plan material/state variants -> NativeConstruction.projection/place -> real WorldServer/Forge/payment");
        result.setString(
            "scope",
            "GTNL/GTSR material/state variants only; not full-machine spatial placement, formation, four operations, saved-blueprint end-to-end, GUI or TCP acceptance");
        return result;
    }

    public NBTTagCompound controllerEvidence() {
        require(
            controllerCursor == controllers.size() && restored && controllerResults.tagCount() == 6,
            "Installed controller source capture and both retained/default saved replays are incomplete");
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("placements", controllerResults.copy());
        result.setInteger("sourceControllers", controllers.size());
        result.setInteger("savedReplays", 4);
        result.setInteger("actualMaterialDebit", 6);
        result.setBoolean("fixtureAndOriginalInventoryRestored", true);
        result.setBoolean("journalClear", true);
        result.setString(
            "scope",
            "Installed GTNL/LCR controller shells: real native source placement, production literal capture, common saved retain=true/false replay; no full structure formation, blueprint GUI, external TCP, or full blueprint TaskGate claim");
        return result;
    }

    public NBTTagCompound controllerProgress() {
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("placements", controllerResults.copy());
        result.setInteger("completedControllers", controllerCursor);
        result.setInteger("nextControllerStage", controllerStage);
        result.setBoolean("complete", controllerCursor == controllers.size() && restored);
        result.setString(
            "scope",
            "Partial common-side controller capture/replay evidence; complete PASS requires all source/retain groups and fixture cleanup");
        return result;
    }

    private NBTTagList inventory() {
        return actor.inventory.writeToNBT(new NBTTagList());
    }

    private boolean authority() {
        return actor.worldObj == world && actor.dimension == world.provider.dimensionId
            && !actor.isDead
            && !actor.capabilities.isCreativeMode
            && actor.capabilities.allowEdit
            && actor.theItemInWorldManager.getGameType() == GameType.SURVIVAL
            && !actor.canCommandSenderUseCommand(2, "gtmbtestlab")
            && actor.inventory.currentItem == toolSlot
            && actor.getHeldItem() == tool
            && ItemStack.areItemStacksEqual(tool, toolBefore)
            && TestLab.isOwnedFixture(world, actor, x, y, z, 3, 3, 3);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException("community native variants: " + message);
    }
}
