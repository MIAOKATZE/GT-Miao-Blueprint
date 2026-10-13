package com.miaokatze.gtmb.hologram;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Dedicated visual reference fixture. Native construction and formation are independent of the smoke suites. */
public final class HologramMachineReference {

    public static final String[] MACHINES = { "electric-blast-furnace", "industrial-electrolyzer",
        "large-chemical-reactor", "industrial-centrifuge" };
    private static final String[] TYPES = { "MTEElectricBlastFurnace", "MTEIndustrialElectrolyzer",
        "MTELargeChemicalReactor", "MTEIndustrialCentrifuge" };
    private static final String[] SERVICES = { "MTEHatchInputBus", "MTEHatchOutputBus", "MTEHatchInput",
        "MTEHatchOutput", "MTEHatchEnergy", "MTEHatchMaintenance" };
    private int ticks;
    private boolean finished;

    public static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.referenceServer") || Boolean.getBoolean("gtmb.hologram.smoketest")
            || Boolean.getBoolean("gtmb.hologram.compatibilitySmoke"))
            throw new IllegalStateException("Machine references require a separate server profile");
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramMachineReference());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END || ++ticks < 40) return;
        finished = true;
        MinecraftServer server = MinecraftServer.getServer();
        try {
            WorldServer world = server.worldServerForDimension(0);
            require(
                world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-reference-"),
                "dedicated empty world");
            for (int i = 0; i < MACHINES.length; i++) capture(world, i);
            System.out
                .println("[GTMB-MACHINE-REFERENCE] FINAL PASS machines=4 native construction + real checkStructure");
        } catch (Throwable failure) {
            System.err.println("[GTMB-MACHINE-REFERENCE] FINAL FAIL " + failure);
            failure.printStackTrace();
        } finally {
            server.initiateShutdown();
        }
    }

    private static void capture(WorldServer world, int index) throws Exception {
        int x = 48 + index * 32, y = 80, z = 48;
        for (int xx = x - 8; xx <= x + 8; xx++) for (int zz = z - 8; zz <= z + 8; zz++) {
            world.getChunkFromBlockCoords(xx, zz);
            for (int yy = y - 8; yy <= y + 8; yy++) require(world.isAirBlock(xx, yy, zz), "fresh fixture air");
        }
        int id = registered(TYPES[index]);
        BaseMetaTileEntity base = machine(world, x, y, z, id, ForgeDirection.NORTH);
        MTEEnhancedMultiBlockBase<?> controller = (MTEEnhancedMultiBlockBase<?>) base.getMetaTileEntity();
        ItemStack trigger = new ItemStack(net.minecraft.init.Items.feather, 1);
        ChannelDataAccessor.setChannelData(trigger, "coil", 3);
        HologramCapture capture = null;
        for (int step = 0; step < 128; step++) {
            capture = HologramCapture.collect(controller, trigger, controller.getExtendedFacing(), capture);
            if (!capture.pending) break;
        }
        require(capture != null && !capture.pending && !capture.incomplete, "complete native declaration");

        // The machine's actual creative constructor handles frames and the LCR's exactly-one-coil state.
        controller.construct(trigger, false);
        Set<String> occupied = new HashSet<>();
        List<HologramCapture.Cell> slots = new ArrayList<>();
        ItemStack hatchSignal = trigger.copy();
        ChannelDataAccessor.setChannelData(hatchSignal, "gt_hatch", 1);
        for (HologramCapture.Cell cell : capture.cells) {
            if (cell.anchor) continue;
            HologramDeclarationCandidates candidates = candidates(controller, world, cell, hatchSignal);
            if (has(candidates, "MTEHatchMuffler") && (cell.unknown || !has(candidates, "MTEHatchEnergy"))) {
                placeHatch(world, controller, cell, candidates, "MTEHatchMuffler", ForgeDirection.UP);
                occupied.add(key(cell));
            }
            if (has(candidates, "MTEHatchEnergy")
                && !(world.getBlock(cell.x, cell.y, cell.z) instanceof gregtech.api.interfaces.IHeatingCoil))
                slots.add(cell);
        }
        // Place visible front/base services first; all substitutions come from this exact structure element.
        slots.sort(
            Comparator.comparingInt((HologramCapture.Cell c) -> c.y)
                .thenComparingInt(c -> c.z)
                .thenComparingInt(c -> c.x));
        for (String service : SERVICES) {
            HologramCapture.Cell chosen = null;
            for (HologramCapture.Cell cell : slots) {
                if (!occupied.contains(key(cell)) && has(candidates(controller, world, cell, hatchSignal), service)) {
                    chosen = cell;
                    break;
                }
            }
            require(chosen != null, "legal declared position for " + service);
            ForgeDirection facing = outward(capture, chosen);
            placeHatch(world, controller, chosen, candidates(controller, world, chosen, hatchSignal), service, facing);
            occupied.add(key(chosen));
        }
        if (index == 1 || index == 3) {
            HologramCapture.Cell chosen = null;
            for (HologramCapture.Cell cell : slots) {
                if (!occupied.contains(key(cell))
                    && has(candidates(controller, world, cell, hatchSignal), "MTEHatchMuffler")
                    && (chosen == null || cell.y > chosen.y)) chosen = cell;
            }
            require(chosen != null, "legal upper muffler position");
            placeHatch(
                world,
                controller,
                chosen,
                candidates(controller, world, chosen, hatchSignal),
                "MTEHatchMuffler",
                ForgeDirection.UP);
        }
        require(controller.checkStructure(true, base), "real formation check for " + MACHINES[index]);
        NBTTagCompound state = new NBTTagCompound();
        state.setString("name", MACHINES[index]);
        state.setString(
            "class",
            controller.getClass()
                .getName());
        state.setInteger("controllerId", id);
        state.setBoolean("formed", true);
        state.setString("verification", "native construct + checkStructure(true, real server tile)");
        NBTTagList blocks = new NBTTagList();
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
        int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        for (HologramCapture.Cell cell : capture.cells) {
            Block block = world.getBlock(cell.x, cell.y, cell.z);
            if (block == net.minecraft.init.Blocks.air) continue;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", cell.x - x);
            row.setInteger("y", cell.y - y);
            row.setInteger("z", cell.z - z);
            row.setString("block", String.valueOf(Block.blockRegistry.getNameForObject(block)));
            row.setInteger("meta", world.getBlockMetadata(cell.x, cell.y, cell.z));
            TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            if (tile != null) {
                NBTTagCompound saved = new NBTTagCompound();
                tile.writeToNBT(saved);
                row.setTag("tile", saved);
            }
            if (tile instanceof IGregTechTileEntity) {
                IGregTechTileEntity gt = (IGregTechTileEntity) tile;
                row.setInteger("machineId", gt.getMetaTileID());
                row.setString(
                    "machineClass",
                    gt.getMetaTileEntity()
                        .getClass()
                        .getName());
                row.setInteger(
                    "facing",
                    gt.getFrontFacing()
                        .ordinal());
            }
            blocks.appendTag(row);
            minX = Math.min(minX, cell.x - x);
            maxX = Math.max(maxX, cell.x - x);
            minY = Math.min(minY, cell.y - y);
            maxY = Math.max(maxY, cell.y - y);
            minZ = Math.min(minZ, cell.z - z);
            maxZ = Math.max(maxZ, cell.z - z);
        }
        state.setIntArray("bounds", new int[] { minX, minY, minZ, maxX, maxY, maxZ });
        state.setTag("blocks", blocks);
        File output = new File(System.getProperty("gtmb.hologram.referenceStateDir", "."), MACHINES[index] + ".nbt");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            CompressedStreamTools.writeCompressed(state, stream);
        }
        System.out.println(
            "[GTMB-MACHINE-REFERENCE] PASS " + MACHINES[index]
                + " id="
                + id
                + " blocks="
                + blocks.tagCount()
                + " formed=true dimensions="
                + (maxX - minX + 1)
                + "x"
                + (maxY - minY + 1)
                + "x"
                + (maxZ - minZ + 1)
                + " snapshot="
                + output.getAbsolutePath());
    }

    private static ForgeDirection outward(HologramCapture capture, HologramCapture.Cell cell) {
        if (!capture.positions.containsKey((cell.x) + "," + cell.y + "," + (cell.z - 1))) return ForgeDirection.NORTH;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = minX, maxZ = maxX;
        for (HologramCapture.Cell c : capture.cells) {
            minX = Math.min(minX, c.x);
            maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z);
            maxZ = Math.max(maxZ, c.z);
        }
        if (cell.z == minZ) return ForgeDirection.NORTH;
        if (cell.x == minX) return ForgeDirection.WEST;
        if (cell.x == maxX) return ForgeDirection.EAST;
        if (cell.z == maxZ) return ForgeDirection.SOUTH;
        return ForgeDirection.DOWN;
    }

    private static void placeHatch(WorldServer world, MTEEnhancedMultiBlockBase<?> controller,
        HologramCapture.Cell cell, HologramDeclarationCandidates candidates, String service, ForgeDirection facing) {
        ItemStack selected = null;
        int distance = Integer.MAX_VALUE;
        for (ItemStack stack : candidates.stacks) {
            IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(stack);
            if (prototype == null || !prototype.getClass()
                .getSimpleName()
                .equals(service)) continue;
            int d = Math.abs(((MTEHatch) prototype).mTier - 3);
            if (d < distance) {
                distance = d;
                selected = stack;
            }
        }
        require(selected != null, "registered declared hatch " + service);
        BaseMetaTileEntity base = machine(world, cell.x, cell.y, cell.z, selected.getItemDamage(), facing);
        NBTTagCompound display = HologramDisplaySnapshot.target(selected, facing.ordinal(), cell.element, "", false);
        if (display.hasKey("texturePage") && display.hasKey("textureIndex")) ((MTEHatch) base.getMetaTileEntity())
            .updateTexture(display.getInteger("texturePage") << 7 | display.getInteger("textureIndex"));
        System.out.println(
            "[GTMB-MACHINE-REFERENCE] HATCH " + controller.getClass()
                .getSimpleName()
                + " "
                + service
                + " id="
                + selected.getItemDamage()
                + " position="
                + key(cell)
                + " facing="
                + facing);
    }

    private static HologramDeclarationCandidates candidates(MTEEnhancedMultiBlockBase<?> controller, WorldServer world,
        HologramCapture.Cell cell, ItemStack trigger) {
        HologramDeclarationCandidates result = new HologramDeclarationCandidates();
        result.collect(cell.element, controller, world, cell.x, cell.y, cell.z, trigger, null, 0);
        return result;
    }

    private static boolean has(HologramDeclarationCandidates candidates, String simpleName) {
        for (ItemStack stack : candidates.stacks) {
            IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(stack);
            if (prototype != null && prototype.getClass()
                .getSimpleName()
                .equals(simpleName)) return true;
        }
        return false;
    }

    private static BaseMetaTileEntity machine(WorldServer world, int x, int y, int z, int id, ForgeDirection facing) {
        world.setBlockToAir(x, y, z);
        require(
            world.setBlock(
                x,
                y,
                z,
                GregTechAPI.sBlockMachines,
                GregTechAPI.METATILEENTITIES[id].getTileEntityBaseType(),
                3),
            "native machine block");
        BaseMetaTileEntity base = (BaseMetaTileEntity) world.getTileEntity(x, y, z);
        base.setInitialValuesAsNBT(null, (short) id);
        base.setFrontFacing(facing);
        return base;
    }

    private static int registered(String simpleName) {
        for (int id = 1; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity prototype = GregTechAPI.METATILEENTITIES[id];
            if (prototype != null && prototype.getClass()
                .getSimpleName()
                .equals(simpleName)) return id;
        }
        throw new AssertionError("Missing registered machine " + simpleName);
    }

    private static String key(HologramCapture.Cell cell) {
        return cell.x + "," + cell.y + "," + cell.z;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
