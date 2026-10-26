package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.api.CutTransforms;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import appeng.tile.AEBaseTile;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Native fixed schemas and independently specified front/up expectations, without live world hooks. */
final class HologramModOrientationRegression {

    private HologramModOrientationRegression() {}

    static void run() {
        try {
            drawers();
            aeMetadata();
            aeTilesAndParts();
            nativeAeReader();
        } catch (Exception failure) {
            throw new AssertionError("Mod orientation regression", failure);
        }
    }

    private static void drawers() throws IOException {
        for (String id : new String[] { "StorageDrawers:tileDrawersStandard", "StorageDrawers:tileDrawersComp" }) {
            NBTTagCompound tile = drawer(id, 2, 0), entry = entry(tile), before = (NBTTagCompound) entry.copy();
            NBTTagCompound plain = HologramCutTransforms.apply(entry, pose(0, 0, 0, 0), 25, 70, 42);
            SimpleAssert.eq(tile, tile(plain), "unrotated translation retains complete drawer Dir/Rot and inventory");
            SimpleAssert.eq(
                5,
                (int) tile(HologramCutTransforms.apply(entry, pose(1, 0, 0, 0), 0, 0, 0)).getByte("Dir"),
                "native NORTH drawer front rotates EAST");
            SimpleAssert.eq(
                3,
                (int) tile(HologramCutTransforms.apply(entry, pose(0, 0, 0, 4), 0, 0, 0)).getByte("Dir"),
                "Z reflection changes native NORTH drawer front to SOUTH");
            NBTTagCompound tilted = tile(HologramCutTransforms.apply(entry, pose(0, 1, 0, 0), 0, 0, 0));
            SimpleAssert.eq(1, (int) tilted.getByte("Dir"), "X quarter turn moves NORTH drawer front UP");
            SimpleAssert.eq(2, (int) tilted.getByte("Rot"), "tilted drawer's top points SOUTH, native UP roll two");
            for (int side = 0; side < 6; side++) for (int roll = 0; roll < 4; roll++) for (int x = 0; x < 4; x++)
                for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int mirror = 0; mirror < 8; mirror++) {
                    NBTTagCompound source = drawer(id, side, roll), changed = (NBTTagCompound) source.copy();
                    CutTransforms.Transform transform = pose(y, x, z, mirror);
                    HologramBlockTransformStorageDrawers.apply(changed, transform);
                    int next = transform.direction(side);
                    SimpleAssert.eq(next, (int) changed.getByte("Dir"), "every pose moves exact native drawer front");
                    int[] downTop = { 3, 5, 2, 4 }, upTop = { 2, 4, 3, 5 };
                    int originalTop = side == 0 ? downTop[roll] : side == 1 ? upTop[roll] : 1;
                    if (next < 2) {
                        int nativeTop = (next == 0 ? downTop : upTop)[changed.getByte("Rot")];
                        SimpleAssert.eq(
                            transform.direction(originalTop),
                            nativeTop,
                            "native vertical drawer roll tracks transformed top");
                    }
                    source.removeTag("Dir");
                    source.removeTag("Rot");
                    changed.removeTag("Dir");
                    changed.removeTag("Rot");
                    SimpleAssert
                        .eq(source, changed, "drawer transforms never reorder slots or rewrite private payload");
                }
            HologramBlockTransforms.Data blueprint = new HologramBlockTransforms.Data(null, "", 7, tile, null);
            HologramBlockTransforms.Data turned = HologramBlockTransforms.turn(blueprint, false);
            SimpleAssert.eq(5, (int) turned.tile.getByte("Dir"), "blueprint shares native drawer facing adapter");
            SimpleAssert.eq(7, turned.metadata, "drawer wood metadata is never treated as facing");
            SimpleAssert.eq(before, entry, "raw durable drawer clipboard stays immutable");
        }
        NBTTagCompound unrelated = drawer("thirdparty:unrelated", 2, 0);
        SimpleAssert.that(
            !HologramBlockTransformStorageDrawers.supports(Blocks.stone, unrelated),
            "Dir alone never identifies drawers");
        NBTTagCompound unusual = drawer("StorageDrawers:tileDrawersStandard", 1, 127);
        HologramBlockTransformStorageDrawers.apply(unusual, pose(0, 0, 0, 4));
        SimpleAssert.eq(1, (int) unusual.getByte("Dir"), "Z reflection retains upward drawer front");
        SimpleAssert.eq(127, (int) unusual.getByte("Rot"), "native-equivalent noncanonical roll byte is preserved");
        unusual.setString("Rot", "unrecognized");
        HologramBlockTransformStorageDrawers.apply(unusual, pose(1, 0, 0, 0));
        SimpleAssert.eq("unrecognized", unusual.getString("Rot"), "unrecognized native roll type is not replaced");
    }

    private static void aeMetadata() throws Exception {
        for (String name : new String[] { "appeng.block.solids.BlockQuartzPillar", "appeng.block.misc.BlockQuartzTorch",
            "appeng.block.misc.BlockLightDetector" }) {
            Block block = allocate(name);
            for (int face = 0; face < 6; face++) {
                CutTransforms.State state = state(block, face, null);
                SimpleAssert.that(
                    HologramCutTransformDirections.apply(state, pose(1, 0, 0, 0)),
                    "AE metadata is handled without a tile");
                int[] yTurn = { 0, 1, 5, 4, 2, 3 };
                SimpleAssert.eq(yTurn[face], state.metadata, "AE MetaRotation up follows native Y quarter turn");
                state = state(block, face, null);
                HologramCutTransformDirections.apply(state, pose(0, 1, 0, 0));
                int[] xTurn = { 2, 3, 1, 0, 4, 5 };
                SimpleAssert.eq(xTurn[face], state.metadata, "AE MetaRotation up follows native X quarter turn");
                state = state(block, face, null);
                HologramCutTransformDirections.apply(state, pose(0, 0, 0, 2));
                SimpleAssert.eq(face < 2 ? face ^ 1 : face, state.metadata, "AE MetaRotation up follows Y reflection");
            }
        }
        SimpleAssert.eq(
            4,
            HologramCutTransformDirections.aeMetadata("appeng.block.storage.BlockDrive", 4, pose(1, 0, 0, 0)),
            "ordinary AE machine metadata is not a direction");
    }

    private static void aeTilesAndParts() throws Exception {
        NBTTagCompound tag = drawer("BlockMolecularAssembler", 2, 0);
        tag.setString("orientation_forward", "NORTH");
        tag.setString("orientation_up", "UP");
        tag.setInteger("pushDirection", 5);
        CutTransforms.State state = state(allocate("appeng.block.crafting.BlockMolecularAssembler"), 9, tag);
        HologramCutTransformDirections.apply(state, pose(1, 0, 0, 4));
        SimpleAssert
            .eq("WEST", state.tile.getString("orientation_forward"), "AE machine forward follows rotation plus mirror");
        SimpleAssert.eq("UP", state.tile.getString("orientation_up"), "AE machine up remains upright");
        SimpleAssert.eq(3, state.tile.getInteger("pushDirection"), "assembler EAST output rotates SOUTH");
        SimpleAssert.eq(9, state.metadata, "AE machine state bits survive");
        NBTTagCompound blueprint = (NBTTagCompound) tag.copy();
        HologramBlockTransformAdapters.ae(blueprint, false);
        SimpleAssert.eq(3, blueprint.getInteger("pushDirection"), "blueprint assembler also rotates saved output face");
        for (int invalid : new int[] { 6, 47 }) {
            tag.setInteger("pushDirection", invalid);
            HologramCutTransformDirections.ae(tag, pose(1, 0, 0, 0));
            SimpleAssert
                .eq(invalid, tag.getInteger("pushDirection"), "UNKNOWN and unrecognized assembler values survive");
        }
        NBTTagCompound cable = new NBTTagCompound();
        for (int side = 0; side < 7; side++) {
            NBTTagCompound part = new NBTTagCompound(), extra = new NBTTagCompound();
            part.setInteger("nativeSlot", side);
            extra.setByte("spin", (byte) (side == 0 ? 127 : 0));
            cable.setTag("def:" + side, part);
            cable.setTag("extra:" + side, extra);
            if (side < 6) cable.setTag("facade:" + side, part.copy());
        }
        NBTTagCompound central = (NBTTagCompound) cable.getCompoundTag("extra:6")
            .copy();
        HologramCutTransformDirections.ae(cable, pose(1, 0, 0, 0));
        int[] yTurn = { 0, 1, 5, 4, 2, 3 };
        for (int side = 0; side < 6; side++) {
            SimpleAssert.eq(
                side,
                cable.getCompoundTag("def:" + yTurn[side])
                    .getInteger("nativeSlot"),
                "AE cable part side moves");
            SimpleAssert.eq(
                side,
                cable.getCompoundTag("facade:" + yTurn[side])
                    .getInteger("nativeSlot"),
                "AE facade side moves");
        }
        SimpleAssert.eq(
            2,
            (int) cable.getCompoundTag("extra:1")
                .getByte("spin"),
            "UP reporting panel top SOUTH rotates WEST, native spin two");
        SimpleAssert.eq(
            127,
            (int) cable.getCompoundTag("extra:0")
                .getByte("spin"),
            "unknown native spin preserved");
        SimpleAssert.eq(central, cable.getCompoundTag("extra:6"), "central cable identity is unchanged");
    }

    private static Block allocate(String name) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Block) ((sun.misc.Unsafe) field.get(null)).allocateInstance(Class.forName(name));
    }

    @SuppressWarnings("unchecked")
    private static void nativeAeReader() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        WorldServer world = (WorldServer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(WorldServer.class);
        Map<String, Class<?>> types = (Map<String, Class<?>>) ReflectionHelper
            .findField(TileEntity.class, "nameToClassMap", "field_145855_i")
            .get(null);
        String id = "gtmb_test_native_ae_orientation";
        Class<?> previous = types.put(id, AEBaseTile.class);
        try {
            CutTransforms.Transform[] poses = { pose(0, 0, 0, 0), pose(1, 0, 0, 0), pose(0, 1, 0, 0),
                pose(0, 0, 0, 4) };
            ForgeDirection[] forwards = { ForgeDirection.NORTH, ForgeDirection.EAST, ForgeDirection.UP,
                ForgeDirection.SOUTH };
            ForgeDirection[] ups = { ForgeDirection.UP, ForgeDirection.UP, ForgeDirection.SOUTH, ForgeDirection.UP };
            for (int i = 0; i < poses.length; i++) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("id", id);
                tag.setString("orientation_forward", "NORTH");
                tag.setString("orientation_up", "UP");
                tag.setInteger("x", 25);
                tag.setInteger("y", 70);
                tag.setInteger("z", 42);
                HologramCutTransformDirections.ae(tag, poses[i]);
                NBTTagCompound before = (NBTTagCompound) tag.copy();
                AEBaseTile restored = (AEBaseTile) HologramCutTiles.create(world, Blocks.stone, 0, tag);
                HologramCutTiles.ready(restored);
                SimpleAssert.eq(
                    forwards[i],
                    restored.getForward(),
                    "actual AE native reader restores translated/rotated/reflected forward");
                SimpleAssert.eq(ups[i], restored.getUp(), "actual AE native reader restores transformed up");
                SimpleAssert.that(
                    restored.getWorldObj() == world && restored.xCoord == 25
                        && restored.yCoord == 70
                        && restored.zCoord == 42,
                    "native restoration establishes destination world and coordinates");
                SimpleAssert.eq(before, tag, "actual AE native reader does not mutate clipboard payload");
            }
        } finally {
            if (previous == null) types.remove(id);
            else types.put(id, previous);
        }
    }

    private static CutTransforms.State state(Block block, int metadata, NBTTagCompound tile) {
        return new CutTransforms.State(block, metadata, tile, 0, 64, 0, 12, 70, 12);
    }

    private static CutTransforms.Transform pose(int y, int x, int z, int mirror) {
        return new CutTransforms.Transform(y, x, z, mirror);
    }

    private static NBTTagCompound drawer(String id, int side, int rotation) {
        NBTTagCompound tag = new NBTTagCompound(), payload = new NBTTagCompound();
        tag.setString("id", id);
        tag.setByte("Dir", (byte) side);
        tag.setByte("Rot", (byte) rotation);
        payload.setInteger("Count", 12345);
        payload.setString("owner", "preserved");
        tag.setTag("Slots", payload);
        return tag;
    }

    private static NBTTagCompound entry(NBTTagCompound tile) {
        NBTTagCompound entry = new NBTTagCompound(), cell = new NBTTagCompound();
        entry.setInteger("sizeX", 1);
        entry.setInteger("sizeY", 1);
        entry.setInteger("sizeZ", 1);
        cell.setString("block", "minecraft:stone");
        cell.setInteger("meta", 7);
        cell.setTag("tile", tile.copy());
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        entry.setTag("cells", cells);
        return entry;
    }

    private static NBTTagCompound tile(NBTTagCompound entry) {
        return entry.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("tile");
    }
}
