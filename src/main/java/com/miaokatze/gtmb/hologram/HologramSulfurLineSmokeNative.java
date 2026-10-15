package com.miaokatze.gtmb.hologram;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileDenseEnergyCell;
import appeng.tile.storage.TileDrive;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchEnergyDebug;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.util.GTUtility;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;
import gregtech.common.tileentities.machines.MTEHatchInputME;
import gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor;
import gregtech.common.tileentities.machines.outputme.MTEHatchOutputME;

/** Finite native ME chemistry with explicitly authorized native debug energy; only world ticks produce the fluids. */
public final class HologramSulfurLineSmokeNative {

    private static final long SULFUR = 64, OXYGEN = 192_000, WATER = 64_000;

    private HologramSulfurLineSmokeNative() {}

    public static int[][] gatewayPositions(int x, int y, int z) {
        return new int[][] { { x, y + 1, z + 3 }, { x + 2, y + 1, z + 3 }, { x + 1, y + 2, z + 3 },
            { x + 5, y + 1, z + 3 }, { x + 7, y + 1, z + 3 }, { x + 10, y + 1, z + 3 }, { x + 12, y + 1, z + 3 } };
    }

    public static Line create(WorldServer world, EntityPlayerMP actor, int x, int y, int z) throws Exception {
        isolated(world, actor);
        int[] bounds = { x, y, z, x + 13, y + 2, z + 4 };
        for (int px = bounds[0]; px <= bounds[3]; px++) {
            for (int pz = bounds[2]; pz <= bounds[5]; pz++) {
                world.getChunkFromBlockCoords(px, pz);
                for (int py = bounds[1]; py <= bounds[4]; py++)
                    require(world.isAirBlock(px, py, pz), "fresh native direct-node line footprint required");
            }
        }
        for (int stage = 0; stage < 3; stage++) {
            int sx = x + stage * 5;
            for (int dx = 0; dx < 3; dx++) for (int dy = 0; dy < 3; dy++) for (int dz = 0; dz < 3; dz++) require(
                world.setBlock(sx + dx, y + dy, z + dz, GregTechAPI.sBlockCasings8, 0, 3),
                "native inert casing placement");
            require(
                world.setBlock(sx + 1, y + 1, z + 1, GregTechAPI.sBlockCasings8, 1, 3),
                "native PTFE pipe casing placement");
            require(
                world.setBlock(sx + 1, y + 2, z + 1, GregTechAPI.sBlockCasings5, 0, 3),
                "native cupronickel heating coil placement");
            BaseMetaTileEntity controller = placeGT(
                world,
                actor,
                ItemList.Machine_Multi_LargeChemicalReactor.get(1),
                sx + 1,
                y + 1,
                z,
                ForgeDirection.NORTH);
            MTELargeChemicalReactor reactor = cast(controller.getMetaTileEntity(), MTELargeChemicalReactor.class);
            reactor.setExtendedFacing(ExtendedFacing.NORTH_NORMAL_NONE);
            reactor.setInventorySlotContents(
                reactor.getControllerSlotIndex(),
                GTUtility.getIntegratedCircuit(stage == 0 ? 3 : 1));
            controller.disableWorking();
            MTEHatchEnergyDebug debug = cast(
                placeGT(world, actor, ItemList.DebugEnergyHatch.get(1), sx, y, z, ForgeDirection.NORTH)
                    .getMetaTileEntity(),
                MTEHatchEnergyDebug.class);
            debug.setVoltageTier(1);
            debug.setAmperage(1);
            debug.setRefillInterval(20);
            placeGT(world, actor, ItemList.Hatch_AutoMaintenance.get(1), sx + 2, y, z, ForgeDirection.NORTH);
            MTEHatchInputME input = cast(
                placeGT(world, actor, ItemList.Hatch_Input_ME.get(1), sx, y + 1, z + 2, ForgeDirection.SOUTH)
                    .getMetaTileEntity(),
                MTEHatchInputME.class);
            if (stage == 0) input.setSlotConfig(0, Materials.Oxygen.getGas(1));
            else if (stage == 1) {
                input.setSlotConfig(0, Materials.Oxygen.getGas(1));
                input.setSlotConfig(1, Materials.SulfurDioxide.getGas(1));
            } else {
                input.setSlotConfig(0, Materials.Water.getFluid(1));
                input.setSlotConfig(1, Materials.SulfurTrioxide.getGas(1));
            }
            placeGT(world, actor, ItemList.Hatch_Output_ME.get(1), sx + 2, y + 1, z + 2, ForgeDirection.SOUTH);
            if (stage == 0) {
                MTEHatchInputBusME bus = cast(
                    placeGT(
                        world,
                        actor,
                        ItemList.Hatch_Input_Bus_ME.get(1),
                        sx + 1,
                        y + 2,
                        z + 2,
                        ForgeDirection.SOUTH).getMetaTileEntity(),
                    MTEHatchInputBusME.class);
                bus.setSlotConfig(0, Materials.Sulfur.getDust(1));
            }
        }
        // Every native ME hatch receives its own directly adjacent quantum node; no AE cable is placed.
        return discover(world, actor, x, y, z);
    }

    /** Discovers the already placed blueprint. It never places a machine, casing, part or storage cell. */
    public static Line discover(WorldServer world, EntityPlayerMP actor, int x, int y, int z) {
        isolated(world, actor);
        return new Line(world, actor, x, y, z);
    }

    public static final class Line {

        private final WorldServer world;
        private final EntityPlayerMP actor;
        private final int x, y, z;
        private final MTELargeChemicalReactor[] reactors = new MTELargeChemicalReactor[3];
        private final BaseMetaTileEntity[] energy = new BaseMetaTileEntity[3];
        private final MTEHatchInputME[] inputs = new MTEHatchInputME[3];
        private final MTEHatchOutputME[] outputs = new MTEHatchOutputME[3];
        private final MTEHatchMaintenance[] maintenance = new MTEHatchMaintenance[3];
        private final MTEHatchInputBusME sulfurBus;
        private final TileDrive drive;
        private final TileDenseEnergyCell power;
        private final int[] maxProgress = new int[3];
        private final long[] completionBaseline = new long[3];
        private final NBTTagCompound[] previousSamples = new NBTTagCompound[3];
        private final NBTTagList[] completionBoundaries = new NBTTagList[3];
        private final NBTTagCompound initial = new NBTTagCompound();
        private long lastTick = Long.MIN_VALUE;
        private long suppliedTick = -1, startedTick = -1, stoppedTick = -1;
        private int startCount;
        private boolean supplied;

        private Line(WorldServer world, EntityPlayerMP actor, int x, int y, int z) {
            this.world = world;
            this.actor = actor;
            this.x = x;
            this.y = y;
            this.z = z;
            for (int stage = 0; stage < 3; stage++) {
                int sx = x + stage * 5;
                reactors[stage] = nativeMTE(world, sx + 1, y + 1, z, MTELargeChemicalReactor.class);
                energy[stage] = nativeBase(world, sx, y, z);
                MTEHatchEnergyDebug debug = cast(energy[stage].getMetaTileEntity(), MTEHatchEnergyDebug.class);
                require(
                    debug.getVoltageTier() == 1 && debug.getAmperage() == 1 && debug.getRefillInterval() == 20,
                    "native debug energy uses the reviewed LV/1A/20-tick configuration");
                inputs[stage] = nativeMTE(world, sx, y + 1, z + 2, MTEHatchInputME.class);
                outputs[stage] = nativeMTE(world, sx + 2, y + 1, z + 2, MTEHatchOutputME.class);
                maintenance[stage] = nativeMTE(world, sx + 2, y, z, MTEHatchMaintenance.class);
                require(maintenance[stage].mAuto, "real automatic maintenance hatch required");
                completionBaseline[stage] = reactors[stage].recipesDone;
                completionBoundaries[stage] = new NBTTagList();
            }
            sulfurBus = nativeMTE(world, x + 1, y + 2, z + 2, MTEHatchInputBusME.class);
            drive = cast(world.getTileEntity(x - 12, y, z + 11), TileDrive.class);
            power = cast(world.getTileEntity(x - 17, y, z + 8), TileDenseEnergyCell.class);
            require(!power.isInfinite(), "finite native AE energy cell required");
        }

        public int[] bounds() {
            return new int[] { x, y, z, x + 13, y + 2, z + 4 };
        }

        public int[] firstController() {
            return new int[] { x + 1, y + 1, z };
        }

        public boolean ready() {
            return operational();
        }

        public boolean operational() {
            try {
                if (!drive.getProxy()
                    .isActive()) return false;
                for (int stage = 0; stage < 3; stage++) {
                    if (!reactors[stage].mMachine || !inputs[stage].getProxy()
                        .isActive()
                        || !outputs[stage].getProxy()
                            .isActive())
                        return false;
                    if (inputs[stage].getProxy()
                        .getGrid()
                        != drive.getProxy()
                            .getGrid()
                        || outputs[stage].getProxy()
                            .getGrid()
                            != drive.getProxy()
                                .getGrid())
                        return false;
                }
                return sulfurBus.getProxy()
                    .isActive()
                    && sulfurBus.getProxy()
                        .getGrid()
                        == drive.getProxy()
                            .getGrid();
            } catch (Exception unavailable) {
                return false;
            }
        }

        /** One explicit finite fixture supply, through the actual native storage monitors. */
        public void supplyInitialStock() throws Exception {
            require(!supplied, "line fixture supply is once only");
            require(
                drive.getProxy()
                    .getNode() != null,
                "natural AE node initialization required before fixture supply");
            require(operational(), "remote stock supply requires the naturally linked quantum grid");
            initial.setDouble("remoteAeStoredBefore", power.getAECurrentPower());
            // Injecting initial fixture inventory is an explicit supply. Machines subsequently use poweredExtraction.
            PlayerSource source = new PlayerSource(actor, drive);
            IAEItemStack item = AEItemStack.create(Materials.Sulfur.getDust(1));
            item.setStackSize(SULFUR);
            require(items().injectItems(item, Actionable.MODULATE, source) == null, "native ME sulfur supply accepted");
            require(
                fluids().injectItems(AEFluidStack.create(Materials.Oxygen.getGas(OXYGEN)), Actionable.MODULATE, source)
                    == null,
                "native ME oxygen supply accepted");
            require(
                fluids().injectItems(AEFluidStack.create(Materials.Water.getFluid(WATER)), Actionable.MODULATE, source)
                    == null,
                "native ME water supply accepted");
            supplied = true;
            suppliedTick = world.getTotalWorldTime();
            initial.setLong("sulfur", SULFUR);
            initial.setLong("oxygen", OXYGEN);
            initial.setLong("water", WATER);
            initial.setLong("acidSupplied", 0);
            initial.setString("machinePowerMode", "native-debug-energy-hatch");
            initial.setBoolean("debugEnergyExplicitlyAuthorized", true);
            initial.setBoolean("fixtureDirectHatchEuWrites", false);
            initial.setLong("worldTick", suppliedTick);
            initial.setString(
                "supplyMethod",
                "once-only remote ME stock; native debug energy hatch onFirstTick/onPreTick supplies authorized machine EU");
            NBTTagList maintenanceSupply = new NBTTagList();
            ItemStack[] bundle = MTEHatchMaintenance.getAutoMaintenanceInputs();
            for (int stage = 0; stage < 3; stage++) {
                NBTTagCompound suppliedBundle = new NBTTagCompound();
                suppliedBundle.setInteger("stage", stage);
                suppliedBundle.setInteger("repairStatusBefore", reactors[stage].getRepairStatus());
                NBTTagList nativeItems = new NBTTagList();
                for (int slot = 0; slot < bundle.length; slot++) {
                    require(maintenance[stage].getStackInSlot(slot) == null, "fresh automatic maintenance supply slot");
                    ItemStack stock = bundle[slot].copy();
                    maintenance[stage].setInventorySlotContents(slot, stock);
                    nativeItems.appendTag(stock.writeToNBT(new NBTTagCompound()));
                }
                suppliedBundle.setTag("items", nativeItems);
                maintenanceSupply.appendTag(suppliedBundle);
                maintenance[stage].getBaseMetaTileEntity()
                    .markDirty();
                reactors[stage].getBaseMetaTileEntity()
                    .disableWorking();
            }
            initial.setTag("automaticMaintenanceSupply", maintenanceSupply);
            // The driver's observation may have already run at this END tick before the explicit supply.
            lastTick = Long.MIN_VALUE;
            tick();
        }

        /** The external bank owns the only finite AE power supply. A discovered blueprint receives no power. */
        public void supplyBootPower() {
            require(!initial.hasKey("blueprintBootPower"), "discovery power audit is once only");
            initial.setDouble("blueprintBootPower", 0);
        }

        /** Call from the driver's natural server tick. This never invokes a TE tick or recipe method. */
        public void tick() {
            long now = world.getTotalWorldTime();
            if (lastTick == now) return;
            lastTick = now;
            if (supplied && startedTick < 0 && stoppedTick < 0) {
                require(
                    now - suppliedTick <= 200,
                    "native debug hatches must naturally obtain EU before the startup deadline");
                boolean powered = true;
                for (BaseMetaTileEntity hatch : energy) powered &= hatch.getStoredEU() > 0;
                if (powered) {
                    NBTTagList ready = new NBTTagList();
                    for (int stage = 0; stage < 3; stage++) {
                        require(
                            !reactors[stage].getBaseMetaTileEntity()
                                .isAllowedToWork(),
                            "reactors remain stopped until actual native debug power is available");
                        NBTTagCompound row = new NBTTagCompound();
                        row.setTag("nativeEnergyHatch", nativeTile(energy[stage]));
                        row.setTag("beforeEnable", nativeSample(stage, now));
                        ready.appendTag(row);
                    }
                    startedTick = now;
                    startCount++;
                    initial.setLong("nativePowerReadyTick", now);
                    initial.setInteger("startupEnableCount", startCount);
                    initial.setTag("nativePowerBeforeEnable", ready);
                    for (MTELargeChemicalReactor reactor : reactors) reactor.getBaseMetaTileEntity()
                        .enableWorking();
                }
            }
            for (int stage = 0; stage < 3; stage++) {
                maxProgress[stage] = Math.max(maxProgress[stage], reactors[stage].mProgresstime);
                if (supplied) {
                    NBTTagCompound sample = nativeSample(stage, now);
                    NBTTagCompound previous = previousSamples[stage];
                    if (previous != null && completedBetween(previous, sample)) {
                        NBTTagCompound boundary = new NBTTagCompound();
                        boundary.setTag("before", previous.copy());
                        boundary.setTag("after", sample.copy());
                        completionBoundaries[stage].appendTag(boundary);
                    }
                    previousSamples[stage] = sample;
                }
            }
        }

        private NBTTagCompound nativeSample(int stage, long now) {
            MTELargeChemicalReactor reactor = reactors[stage];
            NBTTagCompound sample = new NBTTagCompound();
            sample.setInteger("stage", stage);
            sample.setString("phase", "ServerTick.END");
            sample.setLong("worldTick", now);
            sample.setLong("totalRunTime", reactor.getTotalRunTime());
            sample.setLong("lastWorkingTick", reactor.getLastWorkingTick());
            sample.setLong("recipesDone", reactor.recipesDone);
            sample.setInteger("progress", reactor.mProgresstime);
            sample.setInteger("maxProgress", reactor.mMaxProgresstime);
            sample.setInteger("efficiency", reactor.mEfficiency);
            sample.setInteger("eut", reactor.mEUt);
            sample.setBoolean("formed", reactor.mMachine);
            sample.setBoolean(
                "allowedToWork",
                reactor.getBaseMetaTileEntity()
                    .isAllowedToWork());
            sample.setString(
                "nativeRecipeResult",
                reactor.getCheckRecipeResult()
                    .getID());
            NBTTagList pending = new NBTTagList();
            if (reactor.mOutputFluids != null) {
                for (FluidStack output : reactor.mOutputFluids) {
                    if (output != null) pending.appendTag(output.writeToNBT(new NBTTagCompound()));
                }
            }
            sample.setTag("pendingOutputs", pending);
            return sample;
        }

        private static boolean completedBetween(NBTTagCompound before, NBTTagCompound after) {
            // GT .205 increments totalRunTime before running; successful completion stamps that same clock.
            // Native NC info reads can clear ProcessingLogic's parallel counter, so retain the actual boundary.
            return before.getLong("worldTick") + 1 == after.getLong("worldTick")
                && before.getLong("totalRunTime") + 1 == after.getLong("totalRunTime")
                && before.getBoolean("formed")
                && after.getBoolean("formed")
                && before.getInteger("maxProgress") > 1
                && before.getInteger("progress") == before.getInteger("maxProgress") - 1
                && (after.getInteger("progress") == 0 || after.getInteger("progress") == 1)
                && after.getLong("lastWorkingTick") > before.getLong("lastWorkingTick")
                && after.getLong("lastWorkingTick") == after.getLong("totalRunTime")
                && after.getInteger("efficiency") > 0
                && before.getTagList("pendingOutputs", 10)
                    .tagCount() > 0;
        }

        /** Stops new work; already active native recipes finish and flush on ordinary world ticks. */
        public void stop() {
            if (stoppedTick < 0) stoppedTick = world.getTotalWorldTime();
            for (MTELargeChemicalReactor reactor : reactors) reactor.getBaseMetaTileEntity()
                .disableWorking();
        }

        public boolean quiescent() {
            if (stoppedTick < 0) return false;
            for (int stage = 0; stage < 3; stage++) {
                if (reactors[stage].mMaxProgresstime > 0 || reactors[stage].mOutputFluids != null) return false;
                NBTTagCompound nativeData = new NBTTagCompound();
                outputs[stage].saveNBTData(nativeData);
                if (nativeData.getTagList("cache", 10)
                    .tagCount() != 0) return false;
            }
            return true;
        }

        public NBTTagCompound initialStock() {
            return (NBTTagCompound) initial.copy();
        }

        public long acid() {
            return fluidCount(Materials.SulfuricAcid.getFluid(1));
        }

        public NBTTagCompound receipt() {
            NBTTagCompound result = new NBTTagCompound();
            result.setIntArray("bounds", bounds());
            result.setTag("initialStock", initialStock());
            result.setBoolean("operational", operational());
            result.setBoolean("quiescent", quiescent());
            result.setLong("worldTick", world.getTotalWorldTime());
            result.setLong("suppliedTick", suppliedTick);
            result.setLong("startedTick", startedTick);
            result.setInteger("startupEnableCount", startCount);
            result.setLong("stoppedTick", stoppedTick);
            result.setLong("sulfurRemaining", itemCount(Materials.Sulfur.getDust(1)));
            result.setLong("oxygenRemaining", fluidCount(Materials.Oxygen.getGas(1)));
            result.setLong("waterRemaining", fluidCount(Materials.Water.getFluid(1)));
            result.setLong("sulfurConsumed", supplied ? SULFUR - itemCount(Materials.Sulfur.getDust(1)) : 0);
            result.setLong("oxygenConsumed", supplied ? OXYGEN - fluidCount(Materials.Oxygen.getGas(1)) : 0);
            result.setLong("waterConsumed", supplied ? WATER - fluidCount(Materials.Water.getFluid(1)) : 0);
            result.setLong("sulfurDioxide", fluidCount(Materials.SulfurDioxide.getGas(1)));
            result.setLong("sulfurTrioxide", fluidCount(Materials.SulfurTrioxide.getGas(1)));
            result.setLong("acid", acid());
            result.setBoolean("outputsAreNative", true);
            result.setString(
                "recipeSource",
                "GT5U .205 ChemicalRecipes:2826,2957,2997 UniversalChemical cell-to-fluid conversion");
            NBTTagList machines = new NBTTagList();
            for (int stage = 0; stage < 3; stage++) {
                MTELargeChemicalReactor reactor = reactors[stage];
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger("stage", stage);
                row.setString(
                    "reaction",
                    stage == 0 ? "S + 2000 O -> 1000 SO2"
                        : stage == 1 ? "1000 SO2 + 1000 O -> 1000 SO3" : "1000 SO3 + 1000 water -> 1000 sulfuric acid");
                row.setBoolean("formed", reactor.mMachine);
                row.setBoolean(
                    "allowedToWork",
                    reactor.getBaseMetaTileEntity()
                        .isAllowedToWork());
                row.setInteger("progress", reactor.mProgresstime);
                row.setInteger("maxProgress", reactor.mMaxProgresstime);
                row.setInteger("observedProgress", maxProgress[stage]);
                row.setLong("recipesDone", reactor.recipesDone - completionBaseline[stage]);
                row.setLong("nativeRecipesDone", reactor.recipesDone);
                row.setLong("completionBaseline", completionBaseline[stage]);
                row.setTag("nativeCompletionBoundaries", completionBoundaries[stage].copy());
                MTEHatchEnergyDebug debug = cast(energy[stage].getMetaTileEntity(), MTEHatchEnergyDebug.class);
                row.setString(
                    "nativeEnergyHatchClass",
                    debug.getClass()
                        .getName());
                row.setInteger("nativeEnergyHatchId", energy[stage].getMetaTileID());
                row.setInteger("debugVoltageTier", debug.getVoltageTier());
                row.setInteger("debugAmperage", debug.getAmperage());
                row.setInteger("debugRefillInterval", debug.getRefillInterval());
                row.setBoolean("nativeDebugEnergyPresent", reactor.isDebugEnergyPresent());
                row.setTag("nativeEnergyHatch", nativeTile(energy[stage]));
                row.setLong("fixtureDirectHatchEuInjected", 0);
                row.setLong("storedEU", energy[stage].getStoredEU());
                row.setInteger("eut", reactor.mEUt);
                row.setString(
                    "nativeRecipeResult",
                    reactor.getCheckRecipeResult()
                        .getID());
                row.setBoolean("automaticMaintenance", maintenance[stage].mAuto);
                row.setInteger("repairStatus", reactor.getRepairStatus());
                NBTTagList maintenanceStock = new NBTTagList();
                int remainingMaintenanceItems = 0;
                for (int slot = 0; slot < maintenance[stage].getSizeInventory(); slot++) {
                    ItemStack stock = maintenance[stage].getStackInSlot(slot);
                    if (stock == null || stock.stackSize <= 0) continue;
                    maintenanceStock.appendTag(stock.writeToNBT(new NBTTagCompound()));
                    remainingMaintenanceItems += stock.stackSize;
                }
                row.setTag("automaticMaintenanceStock", maintenanceStock);
                row.setInteger("automaticMaintenanceItemsRemaining", remainingMaintenanceItems);
                row.setInteger("automaticMaintenanceItemsConsumed", supplied ? 12 - remainingMaintenanceItems : 0);
                NBTTagCompound output = new NBTTagCompound();
                outputs[stage].saveNBTData(output);
                row.setTag("outputCache", output.getTagList("cache", 10));
                machines.appendTag(row);
            }
            result.setTag("machines", machines);
            NBTTagCompound nativeDrive = new NBTTagCompound();
            drive.writeToNBT(nativeDrive);
            result.setTag("nativeDrive", nativeDrive);
            result.setTag("topology", topology());
            return result;
        }

        /** Same-END failure evidence reads each native object independently, even when the grid is unavailable. */
        public NBTTagCompound diagnostic() {
            NBTTagCompound result = new NBTTagCompound();
            long now = world.getTotalWorldTime();
            result.setLong("worldTick", now);
            result.setInteger("dimension", world.provider.dimensionId);
            result.setString("samplePhase", "ServerTick.END");
            result.setLong("suppliedTick", suppliedTick);
            result.setLong("startedTick", startedTick);
            result.setInteger("startupEnableCount", startCount);
            NBTTagList rows = new NBTTagList();
            for (int stage = 0; stage < 3; stage++) {
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger("stage", stage);
                row.setTag("sample", nativeSample(stage, now));
                row.setTag("nativeReactor", nativeTile((TileEntity) reactors[stage].getBaseMetaTileEntity()));
                row.setTag("nativeEnergyHatch", nativeTile(energy[stage]));
                row.setTag("nativeInput", nativeTile((TileEntity) inputs[stage].getBaseMetaTileEntity()));
                row.setTag("nativeOutput", nativeTile((TileEntity) outputs[stage].getBaseMetaTileEntity()));
                row.setTag("nativeMaintenance", nativeTile((TileEntity) maintenance[stage].getBaseMetaTileEntity()));
                rows.appendTag(row);
            }
            result.setTag("machines", rows);
            result.setTag("nativeSulfurBus", nativeTile((TileEntity) sulfurBus.getBaseMetaTileEntity()));
            result.setTag("nativeRemoteDrive", nativeTile(drive));
            result.setTag("nativeRemoteAePower", nativeTile(power));
            result.setLong("sulfurRemaining", itemCount(Materials.Sulfur.getDust(1)));
            result.setLong("oxygenRemaining", fluidCount(Materials.Oxygen.getGas(1)));
            result.setLong("waterRemaining", fluidCount(Materials.Water.getFluid(1)));
            result.setLong("acid", acid());
            try {
                result.setTag("topology", topology());
            } catch (RuntimeException unavailable) {
                result.setString("topologyObservationFailure", unavailable.toString());
            }
            return result;
        }

        private static NBTTagCompound nativeTile(TileEntity tile) {
            NBTTagCompound result = new NBTTagCompound();
            tile.writeToNBT(result);
            return result;
        }

        /** Reads live native graph edges. A route which omits the gateway is always a fixture failure. */
        public NBTTagCompound topology() {
            NBTTagCompound result = new NBTTagCompound();
            result.setIntArray("gateway", new int[] { x, y + 1, z + 3 });
            result.setIntArray("remoteController", new int[] { x - 16, y, z + 8 });
            result.setIntArray("remoteDrive", new int[] { x - 12, y, z + 11 });
            result.setIntArray("storageGateway", new int[] { x - 13, y, z + 11 });
            result.setIntArray("remoteAePower", new int[] { x - 17, y, z + 8 });
            result.setInteger("remoteControllerDistance", 22);
            result.setBoolean("localStorageOrAePowerPlaced", false);
            result.setBoolean("directFixtureHatchEuWrites", false);
            int ordinaryCableHosts = 0;
            for (int px = x; px <= x + 13; px++)
                for (int py = y; py <= y + 2; py++) for (int pz = z; pz <= z + 4; pz++) {
                    TileEntity tile = world.getTileEntity(px, py, pz);
                    if (tile != null && tile.getClass()
                        .getName()
                        .equals("appeng.tile.networking.TileCableBus")) ordinaryCableHosts++;
                }
            require(ordinaryCableHosts == 0, "no ordinary AE cable host may exist in the production footprint");
            result.setInteger("ordinaryAeCableHostCount", ordinaryCableHosts);
            try {
                IGridNode start = sulfurBus.getProxy()
                    .getNode(),
                    destination = drive.getProxy()
                        .getNode();
                require(start != null && destination != null, "live native graph nodes required");
                Set<IGridNode> omitted = Collections.newSetFromMap(new IdentityHashMap<IGridNode, Boolean>());
                NBTTagList nativeGateways = new NBTTagList(), bridge = new NBTTagList();
                for (int[] point : gatewayPositions(x, y, z)) {
                    TileEntity gateway = world.getTileEntity(point[0], point[1], point[2]);
                    if (gateway instanceof IGridProxyable) {
                        IGridNode gatewayNode = ((IGridProxyable) gateway).getProxy()
                            .getNode();
                        if (gatewayNode != null) {
                            omitted.add(gatewayNode);
                            for (IGridConnection edge : gatewayNode.getConnections()) {
                                NBTTagCompound row = new NBTTagCompound();
                                row.setTag("from", nativeNode(gatewayNode));
                                row.setTag("to", nativeNode(edge.getOtherSide(gatewayNode)));
                                row.setBoolean("directional", edge.hasDirection());
                                row.setInteger("channels", edge.getUsedChannels());
                                bridge.appendTag(row);
                            }
                        }
                        NBTTagCompound raw = new NBTTagCompound();
                        gateway.writeToNBT(raw);
                        nativeGateways.appendTag(raw);
                    }
                }
                NBTTagList edges = new NBTTagList();
                Set<IGridNode> visited = Collections.newSetFromMap(new IdentityHashMap<IGridNode, Boolean>());
                ArrayDeque<IGridNode> pending = new ArrayDeque<>();
                visited.add(start);
                pending.add(start);
                for (int stage = 0; stage < 3; stage++) {
                    IGridNode inputNode = inputs[stage].getProxy()
                        .getNode(),
                        outputNode = outputs[stage].getProxy()
                            .getNode();
                    if (visited.add(inputNode)) pending.add(inputNode);
                    if (visited.add(outputNode)) pending.add(outputNode);
                }
                boolean bypass = false;
                while (!pending.isEmpty()) {
                    IGridNode node = pending.remove();
                    require(visited.size() <= 128, "bounded isolated native grid graph");
                    if (node == destination) bypass = true;
                    for (IGridConnection edge : node.getConnections()) {
                        IGridNode other = edge.getOtherSide(node);
                        NBTTagCompound row = new NBTTagCompound();
                        row.setTag("from", nativeNode(node));
                        row.setTag("to", nativeNode(other));
                        row.setBoolean("directional", edge.hasDirection());
                        row.setInteger("channels", edge.getUsedChannels());
                        edges.appendTag(row);
                        if (!omitted.contains(other) && visited.add(other)) pending.add(other);
                    }
                }
                require(!bypass, "no native graph route from the line to remote storage when gateway is omitted");
                boolean same = start.getGrid() == destination.getGrid();
                boolean all = same;
                for (int stage = 0; stage < 3; stage++) all &= inputs[stage].getProxy()
                    .getNode()
                    .getGrid() == destination.getGrid()
                    && outputs[stage].getProxy()
                        .getNode()
                        .getGrid() == destination.getGrid();
                result.setBoolean("allInputsOnRemoteGrid", all);
                result.setBoolean("ordinaryBypassRoute", bypass);
                result.setInteger("localNodeCountWithoutGateway", visited.size());
                result.setTag("actualLocalEdgesWithoutGateway", edges);
                result.setInteger("remoteGridIdentity", System.identityHashCode(destination.getGrid()));
                result.setInteger("localGridIdentity", System.identityHashCode(start.getGrid()));
                result.setInteger("remoteOwnerPlayerId", destination.getPlayerID());
                result.setTag("nativeGateways", nativeGateways);
                result.setTag("actualGatewayEdges", bridge);
                result.setInteger("productionGatewayCount", nativeGateways.tagCount());
                result.setBoolean("nativeGraphObserved", true);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot observe actual quantum topology", failure);
            }
            return result;
        }

        private NBTTagCompound nativeNode(IGridNode node) {
            NBTTagCompound row = new NBTTagCompound();
            DimensionalCoord position = node.getGridBlock()
                .getLocation();
            row.setIntArray("position", new int[] { position.getDimension(), position.x, position.y, position.z });
            row.setString(
                "hostClass",
                node.getMachine()
                    .getClass()
                    .getName());
            row.setInteger("playerId", node.getPlayerID());
            row.setInteger("gridIdentity", System.identityHashCode(node.getGrid()));
            row.setBoolean("active", node.isActive());
            return row;
        }

        private IMEMonitor<IAEItemStack> items() throws Exception {
            return drive.getProxy()
                .getStorage()
                .getItemInventory();
        }

        private IMEMonitor<IAEFluidStack> fluids() throws Exception {
            return drive.getProxy()
                .getStorage()
                .getFluidInventory();
        }

        private long itemCount(ItemStack stack) {
            try {
                IAEItemStack wanted = AEItemStack.create(stack);
                wanted.setStackSize(Long.MAX_VALUE);
                IAEItemStack actual = items().extractItems(wanted, Actionable.SIMULATE, new PlayerSource(actor, drive));
                return actual == null ? 0 : actual.getStackSize();
            } catch (Exception unavailable) {
                return 0;
            }
        }

        private long fluidCount(FluidStack stack) {
            try {
                IAEFluidStack wanted = AEFluidStack.create(stack);
                wanted.setStackSize(Long.MAX_VALUE);
                IAEFluidStack actual = fluids()
                    .extractItems(wanted, Actionable.SIMULATE, new PlayerSource(actor, drive));
                return actual == null ? 0 : actual.getStackSize();
            } catch (Exception unavailable) {
                return 0;
            }
        }
    }

    private static BaseMetaTileEntity placeGT(WorldServer world, EntityPlayerMP actor, ItemStack item, int x, int y,
        int z, ForgeDirection facing) {
        require(item != null && item.getItem() instanceof ItemMachines, "registered native GT machine item required");
        require(
            ((ItemMachines) item.getItem())
                .placeBlockAt(item, actor, world, x, y, z, facing.ordinal(), .5F, .5F, .5F, 0),
            "official GT ItemMachines placement accepted");
        BaseMetaTileEntity base = nativeBase(world, x, y, z);
        base.setFrontFacing(facing);
        base.markDirty();
        return base;
    }

    private static BaseMetaTileEntity nativeBase(WorldServer world, int x, int y, int z) {
        BaseMetaTileEntity base = cast(world.getTileEntity(x, y, z), BaseMetaTileEntity.class);
        require(
            base.getWorldObj() == world && !base.isInvalid() && base.getMetaTileEntity() != null,
            "live native GT world tile at " + x + "," + y + "," + z);
        return base;
    }

    private static <T extends IMetaTileEntity> T nativeMTE(WorldServer world, int x, int y, int z, Class<T> type) {
        IGregTechTileEntity base = nativeBase(world, x, y, z);
        return cast(base.getMetaTileEntity(), type);
    }

    private static <T> T cast(Object value, Class<T> type) {
        require(
            type.isInstance(value),
            "expected native " + type.getName()
                + "; actual "
                + (value == null ? "null"
                    : value.getClass()
                        .getName()));
        return type.cast(value);
    }

    private static void isolated(WorldServer world, EntityPlayerMP actor) {
        require(world != null && actor != null && actor.worldObj == world, "native world actor required");
        require(
            world.getWorldInfo()
                .getWorldName()
                .contains("hologram"),
            "isolated hologram world required");
    }

    private static void require(boolean accepted, String message) {
        if (!accepted) throw new IllegalStateException("Native sulfur line: " + message);
    }
}
