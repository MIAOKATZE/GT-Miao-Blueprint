package com.miaokatze.gtmb.hologram;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.PlayerSource;
import appeng.api.parts.IPartHost;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.IGridProxyable;
import appeng.parts.PartPlacement;
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
import gregtech.api.metatileentity.implementations.MTEBasicBatteryBuffer;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTUtility;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;
import gregtech.common.tileentities.machines.MTEHatchInputME;
import gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor;
import gregtech.common.tileentities.machines.outputme.MTEHatchOutputME;

/** Native, finite sulfur line fixture. Only ordinary world ticks produce its SO2, SO3 and acid. */
public final class HologramSulfurLineSmokeNative {

    private static final long SULFUR = 64, OXYGEN = 192_000, WATER = 64_000;
    private static final int BATTERY_EU = 100_000;

    private HologramSulfurLineSmokeNative() {}

    public static Line create(WorldServer world, EntityPlayerMP actor, int x, int y, int z) throws Exception {
        isolated(world, actor);
        int[] bounds = { x, y, z, x + 13, y + 2, z + 4 };
        for (int px = bounds[0]; px <= bounds[3]; px++) {
            for (int pz = bounds[2]; pz <= bounds[5]; pz++) {
                world.getChunkFromBlockCoords(px, pz);
                for (int py = bounds[1]; py <= bounds[4]; py++) require(
                    world.isAirBlock(px, py, pz) || px == x + 13 && py == y + 1
                        && pz == z + 3
                        && HologramNetworkBindings.NODE_TILE.equals(
                            world.getTileEntity(px, py, pz)
                                .getClass()
                                .getName()),
                    "fresh native line footprint or the actual sole quantum gateway required");
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
            placeGT(world, actor, ItemList.Hatch_Energy_LV.get(1), sx, y, z, ForgeDirection.NORTH);
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
        ItemStack cable = AEApi.instance()
            .definitions()
            .parts()
            .cableGlass()
            .stack(AEColor.Transparent, 1);
        for (int dx = 0; dx <= 12; dx++) placeCable(world, actor, cable, x + dx, y + 1, z + 3);
        placeCable(world, actor, cable, x + 1, y + 2, z + 3);
        // The drive, controller and AE cell are outside this captured footprint. Only the GTSWN gateway reaches them.
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
        private final BaseMetaTileEntity[] batteryBases = new BaseMetaTileEntity[3];
        private final MTEBasicBatteryBuffer[] batteries = new MTEBasicBatteryBuffer[3];
        private final long[] batteryInitial = new long[3];
        private final int[] maxProgress = new int[3];
        private final long[] completionBaseline = new long[3];
        private final NBTTagCompound[] previousSamples = new NBTTagCompound[3];
        private final NBTTagList[] completionBoundaries = new NBTTagList[3];
        private final NBTTagCompound initial = new NBTTagCompound();
        private long lastTick = Long.MIN_VALUE;
        private long suppliedTick = -1, stoppedTick = -1;
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
                require(energy[stage].getMetaTileEntity() instanceof MTEHatchEnergy, "native energy hatch required");
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
            initial.setLong("batteryEuPerReactor", BATTERY_EU);
            initial.setLong("worldTick", suppliedTick);
            initial.setString(
                "supplyMethod",
                "once-only remote ME stock; independent once-charged native LV batteries naturally power energy hatches");
            NBTTagList maintenanceSupply = new NBTTagList();
            ItemStack[] bundle = MTEHatchMaintenance.getAutoMaintenanceInputs();
            for (int stage = 0; stage < 3; stage++) {
                int sx = x + stage * 5;
                require(world.isAirBlock(sx, y, z - 1), "external battery fixture footprint is fresh air");
                batteryBases[stage] = placeGT(
                    world,
                    actor,
                    ItemList.Battery_Buffer_1by1_LV.get(1),
                    sx,
                    y,
                    z - 1,
                    ForgeDirection.SOUTH);
                batteries[stage] = cast(batteryBases[stage].getMetaTileEntity(), MTEBasicBatteryBuffer.class);
                ItemStack battery = ItemList.Battery_RE_LV_Lithium.get(1);
                GTModHandler.chargeElectricItem(battery, BATTERY_EU, Integer.MAX_VALUE, true, false);
                batteries[stage].setInventorySlotContents(0, battery);
                batteryInitial[stage] = batteries[stage].getStoredEnergy()[0];
                require(
                    batteryInitial[stage] > 0 && batteryInitial[stage] <= BATTERY_EU,
                    "once-only finite native battery charge");
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
                    .enableWorking();
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
                row.setInteger("progress", reactor.mProgresstime);
                row.setInteger("maxProgress", reactor.mMaxProgresstime);
                row.setInteger("observedProgress", maxProgress[stage]);
                row.setLong("recipesDone", reactor.recipesDone - completionBaseline[stage]);
                row.setLong("nativeRecipesDone", reactor.recipesDone);
                row.setLong("completionBaseline", completionBaseline[stage]);
                row.setTag("nativeCompletionBoundaries", completionBoundaries[stage].copy());
                long batteryRemaining = batteries[stage] == null ? 0 : batteries[stage].getStoredEnergy()[0];
                row.setLong("batteryInitialEU", batteryInitial[stage]);
                row.setLong("batteryRemainingEU", batteryRemaining);
                row.setLong("batteryDebitedEU", batteryInitial[stage] - batteryRemaining);
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

        /** Reads live native graph edges. A route which omits the gateway is always a fixture failure. */
        public NBTTagCompound topology() {
            NBTTagCompound result = new NBTTagCompound();
            result.setIntArray("gateway", new int[] { x + 13, y + 1, z + 3 });
            result.setIntArray("remoteController", new int[] { x - 16, y, z + 8 });
            result.setIntArray("remoteDrive", new int[] { x - 12, y, z + 11 });
            result.setIntArray("storageGateway", new int[] { x - 13, y, z + 11 });
            result.setIntArray("remoteAePower", new int[] { x - 17, y, z + 8 });
            result.setInteger("remoteControllerDistance", 35);
            result.setBoolean("localStorageOrAePowerPlaced", false);
            result.setBoolean("directFixtureHatchEuWrites", false);
            try {
                IGridNode start = sulfurBus.getProxy()
                    .getNode(),
                    destination = drive.getProxy()
                        .getNode();
                require(start != null && destination != null, "live native graph nodes required");
                Set<IGridNode> omitted = Collections.newSetFromMap(new IdentityHashMap<IGridNode, Boolean>());
                NBTTagList nativeGateways = new NBTTagList(), bridge = new NBTTagList();
                for (int[] point : new int[][] { { x + 13, y + 1, z + 3 }, { x + 3, y + 1, z + 4 },
                    { x + 8, y + 1, z + 4 } }) {
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

    private static void placeCable(WorldServer world, EntityPlayerMP actor, ItemStack definition, int x, int y, int z) {
        int slot = actor.inventory.currentItem;
        ItemStack original = actor.inventory.mainInventory[slot];
        try {
            ItemStack cable = definition.copy();
            actor.inventory.mainInventory[slot] = cable;
            require(
                PartPlacement.placeItemPart(cable, actor, world, x, y, z, ForgeDirection.UP),
                "official AE cable placement");
            IPartHost host = PartPlacement.getExistingHost(world.getTileEntity(x, y, z));
            require(host != null && host.getPart(ForgeDirection.UNKNOWN) != null, "native placed AE cable host");
        } finally {
            actor.inventory.mainInventory[slot] = original;
        }
    }

    private static <T extends TileEntity> T placeAE(WorldServer world, EntityPlayerMP actor, Block block, int x, int y,
        int z, Class<T> type) {
        require(world.setBlock(x, y, z, block, 0, 3), "registered native AE block placement");
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null) {
            tile = block.createTileEntity(world, 0);
            if (tile == null && block instanceof appeng.block.AEBaseTileBlock)
                tile = ((appeng.block.AEBaseTileBlock) block).createNewTileEntity(world, 0);
            require(type.isInstance(tile), "registered native AE factory");
            world.setTileEntity(x, y, z, tile);
        }
        T result = cast(tile, type);
        require(result.getWorldObj() == world && !result.isInvalid(), "actual native AE world tile");
        ((IGridProxyable) result).getProxy()
            .setOwner(actor);
        result.markDirty();
        return result;
    }

    private static ItemStack fluidCell() throws Exception {
        // AE2FC is runtime optional to the compiler. Its finite multi-fluid 64k cell holds all five native fluids.
        Object registered = Class.forName("com.glodblock.github.loader.ItemAndBlockHolder")
            .getField("CELL64KM")
            .get(null);
        require(registered instanceof Item, "registered finite AE2FC multi-fluid 64k storage cell");
        ItemStack result = new ItemStack((Item) registered, 1, 0);
        require(
            AEApi.instance()
                .registries()
                .cell()
                .getCellInventory(result, null, appeng.api.storage.StorageChannel.FLUIDS) != null,
            "registered native finite fluid cell handler");
        return result;
    }

    private static Block registeredBlock(String className) {
        for (Object value : Block.blockRegistry) if (value != null && value.getClass()
            .getName()
            .equals(className)) return (Block) value;
        throw new IllegalStateException("Native sulfur line block unavailable: " + className);
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
