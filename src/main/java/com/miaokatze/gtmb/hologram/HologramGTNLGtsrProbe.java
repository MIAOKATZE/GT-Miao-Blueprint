package com.miaokatze.gtmb.hologram;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testlab.TestLab;
import com.miaokatze.gtmb.testlab.TestLabWorldProvider;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.Materials;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Actual World/TE integration probes. Synthetic work counters deliberately bypass recipe selection only. */
public final class HologramGTNLGtsrProbe {

    private static final String MIXER = "com.science.gtnl.common.machine.multiblock.steam.LargeSteamMixer";
    private static final String AIR = "com.miaokatze.gtsr.common.machine.MTEAirCompressor";
    private static final String BIG_INPUT = "com.science.gtnl.common.machine.hatch.CustomFluidHatch";
    private static final String WIRELESS = "com.science.gtnl.common.machine.hatch.WirelessSteamEnergyHatch";
    private static final String COOLING = "com.miaokatze.gtsr.common.machine.base.MTESteamCoolingHatch";
    private static final String PRESSURE = "com.miaokatze.gtsr.common.machine.base.MTEPressureSteamCoolingHatch";
    private static final String OUTPUT = "gregtech.api.metatileentity.implementations.MTEHatchOutput";
    private static final String GTSR_SHA = "f53f1fe4d2118622f8a86a79d8b4db26dea4d8f3f60934fe332b7ac8509f5d9a";

    private HologramGTNLGtsrProbe() {}

    public static Run begin(WorldServer world, FakePlayer actor, int x, int y, int z) throws Exception {
        requireOwned(world, actor, x, y, z);
        for (int dx = 0; dx < 7; dx++) for (int dy = 0; dy < 7; dy++) for (int dz = 0; dz < 7; dz++) require(
            world.blockExists(x + dx, y + dy, z + dz) && world.isAirBlock(x + dx, y + dy, z + dz)
                && world.getTileEntity(x + dx, y + dy, z + dz) == null,
            "GTSR integration requires one original empty, loaded 7x7x7 owned volume");
        return new Run(world, actor, x, y, z);
    }

    public static final class Run implements AutoCloseable {

        private final WorldServer world;
        private final FakePlayer actor;
        private final int x, y, z;
        private final int cx, cy, cz;
        private final String runId;
        private final NBTTagList inventory;
        private final int originalSlot;
        private final NBTTagCompound report = new NBTTagCompound();
        private final NBTTagList rows = new NBTTagList();
        private final boolean enhancement, pipeless;
        private int index, failures;
        private boolean finished, closed;
        private NBTTagCompound row;

        private Run(WorldServer world, FakePlayer actor, int x, int y, int z) throws Exception {
            this.world = world;
            this.actor = actor;
            this.x = x;
            this.y = y;
            this.z = z;
            cx = x + 3;
            cy = y + 3;
            cz = z;
            inventory = actor.inventory.writeToNBT(new NBTTagList());
            originalSlot = actor.inventory.currentItem;
            runId = TestLab.status()
                .getString("runId");
            Map<String, ModContainer> mods = Loader.instance()
                .getIndexedModList();
            require(mods.containsKey("gtsr") && mods.containsKey("sciencenotleisure"), "GTNL/GTSR not loaded");
            require(
                "v2.1.1".equals(
                    mods.get("gtsr")
                        .getVersion()),
                "GTSR integration requires fixed v2.1.1");
            require(
                "0.2.7-rc2.1".equals(
                    mods.get("sciencenotleisure")
                        .getVersion()),
                "GTNL integration requires rc2.1");
            String hash = sha256(
                mods.get("gtsr")
                    .getSource());
            require(GTSR_SHA.equals(hash), "GTSR runtime differs from the fixed v2.1.1 release");
            enhancement = config("gtnlEnhancement");
            pipeless = config("gtnlPipelessSteam");
            report.setBoolean("applicable", true);
            report.setBoolean("actualWorld", true);
            report.setBoolean("actualRegisteredControllersAndHatches", true);
            report.setBoolean("syntheticWorkCounters", true);
            report.setBoolean("fullRecipeSelectionVerified", false);
            report.setBoolean("guiVerified", false);
            report.setBoolean("wirelessNetworkRefillVerified", false);
            report.setString(
                "scope",
                "Actual placed controller checkMachine and onRunningTick; synthetic lEUt/progress only");
            report.setString(
                "networkIsolation",
                "No automatic tile tick between setup and cleanup; wireless owner UUID is null");
            report.setString(
                "gtsrVersion",
                mods.get("gtsr")
                    .getVersion());
            report.setString(
                "gtsrJar",
                mods.get("gtsr")
                    .getSource()
                    .getName());
            report.setString("gtsrSha256", hash);
            report.setBoolean("enhancement", enhancement);
            report.setBoolean("pipeless", pipeless);
            report.setInteger("dimension", world.provider.dimensionId);
            report.setIntArray("ownedVolumeMin", new int[] { x, y, z });
            report.setIntArray("ownedVolumeSize", new int[] { 7, 7, 7 });
            report.setTag("cases", rows);
        }

        /** Each case completes setup, native calls and restoration in one server task step. */
        public boolean step() throws Exception {
            require(!closed, "GTSR probe is closed");
            requireCurrent();
            List<Spec> cases = specs();
            if (index >= cases.size()) {
                finished = true;
                return true;
            }
            Spec spec = cases.get(index);
            row = new NBTTagCompound();
            row.setString("name", spec.name);
            row.setString("status", "RUNNING");
            row.setString("controllerClass", spec.air ? AIR : MIXER);
            rows.appendTag(row);
            try {
                execute(spec);
                row.setString("status", "PASS");
            } catch (Exception | LinkageError failed) {
                fail(failed.toString());
            } finally {
                try {
                    cleanupVolume();
                    restoreInventory();
                    row.setBoolean("ownedVolumeRestored", true);
                    row.setBoolean("actorInventoryRestored", true);
                } catch (Exception | LinkageError cleanupFailed) {
                    fail("Cleanup failed: " + cleanupFailed);
                    throw new IOException("GTSR probe could not restore its owned volume", cleanupFailed);
                }
            }
            row = null;
            index++;
            finished = index == cases.size();
            report.setInteger("failedCases", failures);
            report.setBoolean("checksComplete", finished);
            return finished;
        }

        private void execute(Spec spec) throws Exception {
            int controllerId = registered(spec.air ? AIR : MIXER, -1);
            BaseMetaTileEntity controller = place(controllerId, cx, cy, cz);
            MTEEnhancedMultiBlockBase<?> machine = (MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity();
            row.setInteger("controllerId", controllerId);
            row.setIntArray("controllerPosition", new int[] { cx, cy, cz });
            row.setBoolean(
                "selfManagedMarker",
                interfaceNamed(machine.getClass(), "com.miaokatze.gtsr.api.compat.ISelfManagedSteamMachine"));
            row.setBoolean(
                "coolingHolderMarker",
                interfaceNamed(machine.getClass(), "com.miaokatze.gtsr.api.compat.ICoolingHatchHolder"));
            row.setBoolean(
                "additionalSteamMarker",
                interfaceNamed(machine.getClass(), "com.miaokatze.gtsr.api.compat.IAdditionalSteamSourceHolder"));
            require(row.getBoolean("coolingHolderMarker"), "Actual transformed cooling-holder interface is absent");
            require(spec.air || row.getBoolean("selfManagedMarker"), "GTNL native identity bridge is absent");
            require(
                !spec.air || row.getBoolean("additionalSteamMarker") == pipeless,
                "P selector does not match actual transformed consumer");

            ItemStack trigger = new ItemStack(Blocks.stone, 1);
            List<int[]> positions = positions(machine, spec.air ? 1 : 3, spec.air ? 2 : 3);
            ((IConstructable) machine).construct(trigger.copy(), false);
            List<int[]> shells = new ArrayList<>();
            for (int[] p : positions) if (world.getBlock(p[0], p[1], p[2]) == GregTechAPI.sBlockCasings1
                && world.getBlockMetadata(p[0], p[1], p[2]) == 10) shells.add(p);
            require(
                shells.size() >= (spec.air ? 2 : 3),
                "Native construct did not produce replaceable tier-one shells");
            int inputId = spec.wirelessTier < 0 ? 22518 : registered(WIRELESS, spec.wirelessTier);
            require(
                inputId < GregTechAPI.METATILEENTITIES.length && GregTechAPI.METATILEENTITIES[inputId] != null
                    && GregTechAPI.METATILEENTITIES[inputId].getClass()
                        .getName()
                        .equals(spec.wirelessTier < 0 ? BIG_INPUT : WIRELESS),
                "Fixed GTNL steam input registration differs");
            BaseMetaTileEntity inputTile = replace(inputId, shells.get(0));
            MTEHatch input = (MTEHatch) inputTile.getMetaTileEntity();
            row.setInteger("inputId", inputId);
            row.setInteger("inputTier", input.mTier);
            row.setIntArray("inputPosition", shells.get(0));
            MTEHatch cooling = null, pressure = null;
            if (spec.air) {
                BaseMetaTileEntity outputTile = replace(registered(OUTPUT, -1), shells.get(1));
                row.setIntArray("outputPosition", shells.get(1));
                row.setInteger("outputId", outputTile.getMetaTileID());
            } else {
                BaseMetaTileEntity coolingTile = replace(registered(COOLING, -1), shells.get(1));
                BaseMetaTileEntity pressureTile = replace(registered(PRESSURE, -1), shells.get(2));
                cooling = (MTEHatch) coolingTile.getMetaTileEntity();
                pressure = (MTEHatch) pressureTile.getMetaTileEntity();
                row.setIntArray("coolingPosition", shells.get(1));
                row.setIntArray("pressurePosition", shells.get(2));
                row.setInteger("coolingId", coolingTile.getMetaTileID());
                row.setInteger("pressureId", pressureTile.getMetaTileID());
            }
            FluidStack fluid = spec.superheated ? FluidRegistry.getFluidStack("ic2superheatedsteam", spec.amount)
                : Materials.Steam.getGas(spec.amount);
            require(fluid != null, "Required native steam fluid is absent");
            int filled = ((Number) call(
                input,
                "fill",
                new Class<?>[] { FluidStack.class, boolean.class },
                fluid.copy(),
                true)).intValue();
            require(filled == spec.amount, "Native input tank did not accept its exact test fluid");
            row.setString(
                "inputFluid",
                fluid.getFluid()
                    .getName());
            row.setInteger("loadedAmount", filled);

            call(machine, "clearHatches", new Class<?>[0]);
            List<Object> errors = new ArrayList<>();
            call(
                machine,
                "checkMachine",
                new Class<?>[] { IGregTechTileEntity.class, ItemStack.class, List.class },
                controller,
                trigger.copy(),
                errors);
            NBTTagList errorRows = new NBTTagList();
            for (Object error : errors) errorRows.appendTag(
                new NBTTagString(
                    error.getClass()
                        .getName() + ": "
                        + error));
            row.setTag("nativeStructureErrors", errorRows);
            boolean formed = errors.isEmpty();
            boolean expectedFormed = !spec.air || pipeless && spec.wirelessTier == 0;
            row.setBoolean("nativeStructureAccepted", formed);
            require(formed == expectedFormed, "Native structure acceptance differs from the E/P boundary");
            int coolingCount = listSize(machine, "gtsr$getCoolingHatches");
            int pressureCount = listSize(machine, "gtsr$getPressureHatches");
            row.setInteger("coolingRegistered", coolingCount);
            row.setInteger("pressureRegistered", pressureCount);
            row.setInteger(
                "nativeOutputRegistered",
                ((List<?>) field(machine.getClass(), "mOutputHatches").get(machine)).size());
            if (!spec.air) {
                require(
                    coolingCount == (enhancement ? 1 : 0) && pressureCount == (enhancement ? 1 : 0),
                    "GTNL cooling registration is not controlled solely by E");
                int nativeOutputs = row.getInteger("nativeOutputRegistered");
                require(
                    nativeOutputs == (enhancement ? 0 : 2),
                    "Cooling slots did not follow the actual native chain order");
                if (spec.wirelessTier >= 0) require(
                    ((List<?>) field(machine.getClass(), "mSteamWirelessInputFluids").get(machine)).size() == 1,
                    "GTNL must accept its native tier-one wireless source independently of P");
            } else if (pipeless) {
                int additional = listSize(machine, "gtsr$getAdditionalSteamSources");
                row.setInteger("additionalSteamSources", additional);
                require(
                    additional == (spec.wirelessTier == 0 ? 1 : 0),
                    "GTSR consumer did not enforce tier-zero source");
            }
            if (!spec.air) compareMixer(machine, trigger, cooling, pressure);

            field(machine.getClass(), "lEUt").setLong(machine, spec.air ? -20L : -400L);
            field(machine.getClass(), "mEfficiency").setInt(machine, 10000);
            field(machine.getClass(), "mProgresstime").setInt(machine, 0);
            field(machine.getClass(), "mMaxProgresstime").setInt(machine, 80);
            row.setLong("syntheticEUt", spec.air ? -20L : -400L);
            row.setInteger("syntheticEfficiency", 10000);
            row.setInteger("syntheticDuration", 80);
            int inputBefore = amount(input);
            long accumulatedBefore = ledger(machine, "gtsr$getAccumulatedSteam");
            long pendingBefore = ledger(machine, "gtsr$getPendingSuperheatedSteam");
            int waterBefore = amount(cooling), pressureBefore = amount(pressure);
            boolean ran = (Boolean) call(machine, "onRunningTick", new Class<?>[] { ItemStack.class }, trigger.copy());
            int inputAfter = amount(input), waterAfter = amount(cooling), pressureAfter = amount(pressure);
            long accumulatedAfter = ledger(machine, "gtsr$getAccumulatedSteam");
            long pendingAfter = ledger(machine, "gtsr$getPendingSuperheatedSteam");
            int expectedDebit;
            if (spec.air) expectedDebit = pipeless && spec.wirelessTier == 0 ? 20 : 0;
            else if (spec.amount < 40) expectedDebit = 0;
            else if (!spec.superheated) expectedDebit = 400;
            else expectedDebit = enhancement && spec.amount >= 160 ? 160 : 40;
            row.setInteger("inputBefore", inputBefore);
            row.setInteger("inputAfter", inputAfter);
            row.setInteger("inputDebited", inputBefore - inputAfter);
            row.setInteger("expectedDebit", expectedDebit);
            row.setBoolean("nativeRunningReturned", ran);
            row.setInteger("durationAfter", field(machine.getClass(), "mMaxProgresstime").getInt(machine));
            row.setInteger("waterBefore", waterBefore);
            row.setInteger("waterAfter", waterAfter);
            row.setString("waterFluidAfter", fluidName(cooling));
            row.setInteger("pressureBefore", pressureBefore);
            row.setInteger("pressureAfter", pressureAfter);
            row.setString("pressureFluidAfter", fluidName(pressure));
            row.setString("inputFluidAfter", fluidName(input));
            row.setLong("accumulatedBefore", accumulatedBefore);
            row.setLong("accumulatedAfter", accumulatedAfter);
            row.setLong("pendingBefore", pendingBefore);
            row.setLong("pendingAfter", pendingAfter);
            require(
                inputBefore - inputAfter == expectedDebit && ran == (expectedDebit > 0),
                "Native payment amount or success differs");
            require(
                inputAfter == 0 || fluid.getFluid()
                    .getName()
                    .equals(fluidName(input)),
                "Input changed fluid identity");
            require(
                waterAfter == 0 || "ic2distilledwater".equals(fluidName(cooling)),
                "Cooling output is not distilled water");
            require(
                pressureAfter == 0 || Materials.Steam.mGas.getName()
                    .equals(fluidName(pressure)),
                "Pressure cooling output is not ordinary steam");
            if (expectedDebit > 0) require(
                row.getInteger("durationAfter")
                    == (!spec.air && spec.superheated && enhancement && spec.amount >= 160 ? 20 : 80),
                "Actual native duration does not match the selected boost mode");
            if (!spec.air && enhancement) {
                long recovered = spec.superheated ? pressureAfter - pressureBefore + pendingAfter - pendingBefore
                    : (waterAfter - waterBefore) * 160L + accumulatedAfter - accumulatedBefore;
                row.setLong("coolingConservedLiters", recovered);
                require(
                    recovered == expectedDebit,
                    "Paid steam is not conserved by the actual cooling tanks and ledger");
            } else if (!spec.air) {
                require(
                    waterAfter == waterBefore && pressureAfter == pressureBefore
                        && accumulatedAfter == accumulatedBefore
                        && pendingAfter == pendingBefore,
                    "E disabled must leave GTNL cooling tanks and ledger unchanged");
            }
            if (spec.wirelessTier >= 0) {
                require(
                    field(input.getClass(), "ownerUUID").get(input) == null && inputTile.getOwnerUuid() == null,
                    "Local-only wireless probe acquired a network owner");
            }
            requireCurrent();
            require(
                inventory.equals(actor.inventory.writeToNBT(new NBTTagList()))
                    && actor.inventory.currentItem == originalSlot,
                "Native integration changed the actor inventory");
        }

        private void compareMixer(MTEEnhancedMultiBlockBase<?> machine, ItemStack trigger, MTEHatch cooling,
            MTEHatch pressure) throws Exception {
            NBTTagCompound comparison = new NBTTagCompound();
            row.setTag("gtmbComparison", comparison);
            comparison.setString("purpose", "NEW_TEMPLATE");
            comparison.setInteger("maximumCells", 343);
            comparison.setInteger("maximumSlices", 3);
            comparison.setLong("budgetNanos", 25_000_000L);
            long started = System.nanoTime();
            HologramCapture capture = null;
            int slices = 0;
            try {
                do {
                    require(
                        slices < 3 && System.nanoTime() - started < 25_000_000L,
                        "GTMB Mixer comparison exceeded its bounded same-step capture budget");
                    capture = HologramCapture.collect(
                        machine,
                        trigger,
                        machine.getExtendedFacing(),
                        StructureSources.CaptureIntent.NEW_TEMPLATE,
                        capture);
                    slices++;
                } while (capture.pending);
                require(
                    !capture.incomplete && capture.sourceCurrent(),
                    "GTMB Mixer comparison source was incomplete or expired: " + capture.failure);
                require(
                    capture.cells.size() <= 343 && !capture.cells.isEmpty(),
                    "GTMB Mixer comparison exceeded its known geometry");
                require(
                    capture.nativeConstructCalls == 0 && capture.nativeDefinitionCalls == 0
                        && capture.nativeHintCalls == 0,
                    "GTMB comparison executed a native discovery callback");
                int mismatches = 0, coolingCompared = 0, pressureCompared = 0;
                NBTTagList positions = new NBTTagList();
                for (HologramCapture.Cell cell : capture.cells) {
                    require(inside(cell.x, cell.y, cell.z), "GTMB Mixer declaration leaves the owned volume");
                    HologramGTNLElement element = HologramGTNLSemantics.reviewedElement(cell.element);
                    boolean matches = cell.anchor
                        ? world.getTileEntity(cell.x, cell.y, cell.z) == machine.getBaseMetaTileEntity()
                        : cell.block == Blocks.air ? world.isAirBlock(cell.x, cell.y, cell.z)
                            : !cell.unknown && element != null && element.matchesWorld(world, cell.x, cell.y, cell.z);
                    if (!matches) {
                        mismatches++;
                        if (positions.tagCount() < 16)
                            positions.appendTag(new NBTTagString(cell.x + "," + cell.y + "," + cell.z));
                    }
                    TileEntity actual = world.getTileEntity(cell.x, cell.y, cell.z);
                    if (actual == cooling.getBaseMetaTileEntity() || actual == pressure.getBaseMetaTileEntity()) {
                        require(
                            actual instanceof IGregTechTileEntity && element != null,
                            "GTMB did not retain a declaration for an installed cooling hatch");
                        String role = HologramGTNLSemantics.hatchRole(
                            cell.element,
                            new ItemStack(
                                GregTechAPI.sBlockMachines,
                                1,
                                ((IGregTechTileEntity) actual).getMetaTileID()));
                        boolean ordinary = actual == cooling.getBaseMetaTileEntity();
                        String expected = enhancement ? ordinary ? "gtsr:cooling" : "gtsr:pressure_cooling"
                            : "gtnl:output";
                        comparison.setString(ordinary ? "coolingRole" : "pressureRole", role);
                        require(
                            expected.equals(role),
                            "GTMB cooling role differs from the actual E registration boundary");
                        if (ordinary) coolingCompared++;
                        else pressureCompared++;
                    }
                }
                comparison.setInteger("mismatches", mismatches);
                comparison.setInteger("coolingPositionsCompared", coolingCompared);
                comparison.setInteger("pressurePositionsCompared", pressureCompared);
                comparison.setTag("mismatchPositions", positions);
                comparison.setTag("sourceMetadata", capture.sourceMetadata.copy());
                require(
                    mismatches == 0 && coolingCompared == 1 && pressureCompared == 1,
                    "GTMB Mixer geometry/material declaration does not match the actual formed machine");
                require(
                    System.nanoTime() - started <= 25_000_000L,
                    "GTMB Mixer comparison did not complete within its same-step time budget");
                comparison.setBoolean("complete", true);
            } finally {
                comparison.setInteger("slices", slices);
                comparison.setLong("elapsedNanos", System.nanoTime() - started);
                if (capture != null) {
                    comparison.setInteger("cells", capture.cells.size());
                    comparison.setBoolean("pending", capture.pending);
                    comparison.setString("captureFailure", capture.failure);
                    comparison.setInteger("nativeConstructCalls", capture.nativeConstructCalls);
                    comparison.setInteger("nativeDefinitionCalls", capture.nativeDefinitionCalls);
                    comparison.setInteger("nativeHintCalls", capture.nativeHintCalls);
                    capture.cancelPending();
                }
            }
        }

        @SuppressWarnings({ "rawtypes", "unchecked" })
        private List<int[]> positions(MTEEnhancedMultiBlockBase<?> machine, int horizontal, int vertical)
            throws IOException {
            List<int[]> result = new ArrayList<>();
            IStructureDefinition definition = machine.getStructureDefinition();
            definition.iterate(
                "main",
                world,
                machine.getExtendedFacing(),
                cx,
                cy,
                cz,
                horizontal,
                vertical,
                0,
                new IStructureWalker() {

                    public boolean visit(IStructureElement element, World w, int xx, int yy, int zz, int a, int b,
                        int c) {
                        if (!inside(xx, yy, zz))
                            throw new IllegalStateException("Native structure leaves owned 7x7x7 volume");
                        result.add(new int[] { xx, yy, zz });
                        return true;
                    }

                    public boolean blockNotLoaded(IStructureElement element, World w, int xx, int yy, int zz, int a,
                        int b, int c) {
                        throw new IllegalStateException("Native structure reaches an unloaded cell");
                    }
                });
            require(!result.isEmpty() && result.size() <= 343, "Native structure does not have bounded geometry");
            return result;
        }

        private BaseMetaTileEntity replace(int id, int[] position) throws Exception {
            clear(position[0], position[1], position[2]);
            return place(id, position[0], position[1], position[2]);
        }

        private BaseMetaTileEntity place(int id, int px, int py, int pz) throws Exception {
            require(inside(px, py, pz), "Placement leaves owned volume");
            ItemStack stack = new ItemStack(GregTechAPI.sBlockMachines, 1, id);
            require(stack.getItem() instanceof ItemMachines, "Machine item is not the native ItemMachines");
            require(
                ((ItemMachines) stack.getItem())
                    .placeBlockAt(stack, actor, world, px, py, pz, ForgeDirection.NORTH.ordinal(), .5F, .5F, .5F, 0),
                "Native machine placement failed");
            require(world.getTileEntity(px, py, pz) instanceof BaseMetaTileEntity, "Native machine TE was not created");
            BaseMetaTileEntity tile = (BaseMetaTileEntity) world.getTileEntity(px, py, pz);
            tile.setFrontFacing(ForgeDirection.NORTH);
            tile.disableWorking();
            tile.setOwnerName("");
            tile.setOwnerUuid(null);
            if (tile.getMetaTileEntity()
                .getClass()
                .getName()
                .equals(WIRELESS))
                field(
                    tile.getMetaTileEntity()
                        .getClass(),
                    "ownerUUID").set(tile.getMetaTileEntity(), null);
            require(tile.getMetaTileID() == id && tile.getWorld() == world, "Placed machine identity differs");
            return tile;
        }

        private boolean inside(int px, int py, int pz) {
            return px >= x && px < x + 7 && py >= y && py < y + 7 && pz >= z && pz < z + 7;
        }

        private void requireCurrent() throws Exception {
            requireOwned(world, actor, x, y, z);
            require(
                runId.equals(
                    TestLab.status()
                        .getString("runId")),
                "GTSR probe lost its run ownership");
            require(
                enhancement == config("gtnlEnhancement") && pipeless == config("gtnlPipelessSteam"),
                "GTSR configuration changed during a run; restart in one fixed configuration");
        }

        private void fail(String detail) {
            if (row == null) return;
            if (!"FAIL".equals(row.getString("status"))) failures++;
            row.setString("status", "FAIL");
            row.setString("error", detail);
            report.setInteger("failedCases", failures);
        }

        private void clear(int px, int py, int pz) throws IOException {
            require(inside(px, py, pz), "Cleanup leaves owned volume");
            Chunk chunk = world.getChunkFromChunkCoords(px >> 4, pz >> 4);
            TileEntity old = world.getTileEntity(px, py, pz);
            // These are owned synthetic fixtures. Do not invoke wireless break/refund callbacks on cleanup.
            chunk.removeTileEntity(px & 15, py, pz & 15);
            if (old != null) world.loadedTileEntityList.remove(old);
            ExtendedBlockStorage section = chunk.getBlockStorageArray()[py >> 4];
            if (section != null) {
                section.func_150818_a(px & 15, py & 15, pz & 15, Blocks.air);
                section.setExtBlockMetadata(px & 15, py & 15, pz & 15, 0);
            }
            chunk.setChunkModified();
            world.markBlockForUpdate(px, py, pz);
        }

        private void cleanupVolume() throws IOException {
            requireOwned(world, actor, x, y, z);
            require(
                runId.equals(
                    TestLab.status()
                        .getString("runId")),
                "Cleanup lost its original run");
            for (int dx = 0; dx < 7; dx++)
                for (int dy = 0; dy < 7; dy++) for (int dz = 0; dz < 7; dz++) clear(x + dx, y + dy, z + dz);
            for (int dx = 0; dx < 7; dx++) for (int dy = 0; dy < 7; dy++) for (int dz = 0; dz < 7; dz++) require(
                world.isAirBlock(x + dx, y + dy, z + dz) && world.getTileEntity(x + dx, y + dy, z + dz) == null,
                "Owned volume was not restored to its original empty state");
        }

        private void restoreInventory() throws IOException {
            requireOwned(world, actor, x, y, z);
            if (!inventory.equals(actor.inventory.writeToNBT(new NBTTagList()))
                || actor.inventory.currentItem != originalSlot) {
                actor.inventory.readFromNBT((NBTTagList) inventory.copy());
                actor.inventory.currentItem = originalSlot;
                actor.inventory.markDirty();
            }
            require(
                inventory.equals(actor.inventory.writeToNBT(new NBTTagList())),
                "Actor inventory could not be restored");
        }

        public NBTTagCompound result() {
            return (NBTTagCompound) report.copy();
        }

        @Override
        public void close() throws Exception {
            if (closed) return;
            if (row != null && "RUNNING".equals(row.getString("status")))
                fail("Stopped before the native case completed");
            cleanupVolume();
            restoreInventory();
            report.setBoolean("ownedVolumeRestored", true);
            report.setBoolean("actorInventoryRestored", true);
            report.setInteger("failedCases", failures);
            report.setBoolean("success", finished && failures == 0);
            closed = true;
        }
    }

    private static int registered(String type, int tier) throws IOException {
        for (int id = 0; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity prototype = GregTechAPI.METATILEENTITIES[id];
            if (prototype == null || !prototype.getClass()
                .getName()
                .equals(type)) continue;
            if (tier >= 0 && (!(prototype instanceof MTEHatch) || ((MTEHatch) prototype).mTier != tier)) continue;
            return id;
        }
        throw new IOException("Expected actual registered machine is absent: " + type + "/tier=" + tier);
    }

    private static int amount(MTEHatch hatch) throws Exception {
        if (hatch == null) return 0;
        FluidStack contents = (FluidStack) call(hatch, "getFluid", new Class<?>[0]);
        return contents == null ? 0 : contents.amount;
    }

    private static String fluidName(MTEHatch hatch) throws Exception {
        if (hatch == null) return "";
        FluidStack contents = (FluidStack) call(hatch, "getFluid", new Class<?>[0]);
        return contents == null ? ""
            : contents.getFluid()
                .getName();
    }

    private static long ledger(Object machine, String method) throws Exception {
        return ((Number) call(machine, method, new Class<?>[0])).longValue();
    }

    private static int listSize(Object machine, String method) throws Exception {
        return ((List<?>) call(machine, method, new Class<?>[0])).size();
    }

    private static boolean config(String name) throws Exception {
        return Class.forName("com.miaokatze.gtsr.config.Config", false, HologramGTNLGtsrProbe.class.getClassLoader())
            .getField(name)
            .getBoolean(null);
    }

    private static Object call(Object target, String method, Class<?>[] signature, Object... args) throws Exception {
        try {
            Method nativeMethod = target.getClass()
                .getMethod(method, signature);
            return nativeMethod.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException absent) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean interfaceNamed(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            for (Class<?> implemented : current.getInterfaces()) if (implemented.getName()
                .equals(name) || interfaceNamed(implemented, name)) return true;
        return false;
    }

    private static void requireOwned(WorldServer world, FakePlayer actor, int x, int y, int z) throws IOException {
        require(
            TestLab.isEnabled() && world != null
                && actor != null
                && !world.isRemote
                && world.provider instanceof TestLabWorldProvider
                && actor.worldObj == world
                && !actor.capabilities.isCreativeMode
                && !actor.isDead
                && actor.capabilities.allowEdit
                && ItemNekoHologramProjector.isProjector(actor.getHeldItem())
                && TestLab.isOwnedFixture(world, actor, x, y, z, 7, 7, 7),
            "GTSR probe requires one active leased TestLab volume and its own actor");
        for (Object user : world.playerEntities)
            require(user instanceof FakePlayer, "Human presence stops GTSR fixture mutation");
    }

    private static void require(boolean condition, String detail) throws IOException {
        if (!condition) throw new IOException(detail);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static List<Spec> specs() {
        return Arrays.asList(
            new Spec("mixer_normal_cooling", false, false, 1000, -1),
            new Spec("mixer_superheated_boost", false, true, 1000, -1),
            new Spec("mixer_superheated_unboosted_when_short", false, true, 100, -1),
            new Spec("mixer_insufficient_steam_no_debit", false, true, 39, -1),
            new Spec("mixer_native_wireless_tier1_independent_of_P", false, false, 1000, 1),
            new Spec("gtsr_consumer_wireless_tier0", true, false, 1000, 0),
            new Spec("gtsr_consumer_rejects_wireless_tier1", true, false, 1000, 1));
    }

    private static final class Spec {

        final String name;
        final boolean air, superheated;
        final int amount, wirelessTier;

        Spec(String name, boolean air, boolean superheated, int amount, int wirelessTier) {
            this.name = name;
            this.air = air;
            this.superheated = superheated;
            this.amount = amount;
            this.wirelessTier = wirelessTier;
        }
    }
}
