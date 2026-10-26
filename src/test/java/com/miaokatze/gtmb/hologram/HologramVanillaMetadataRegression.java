package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockButtonStone;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockCocoa;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockEndPortalFrame;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.BlockHugeMushroom;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockPortal;
import net.minecraft.block.BlockPumpkin;
import net.minecraft.block.BlockRail;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRailPowered;
import net.minecraft.block.BlockRedstoneComparator;
import net.minecraft.block.BlockRedstoneRepeater;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.block.BlockSkull;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockStoneSlab;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockTripWireHook;
import net.minecraft.block.BlockVine;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;

import com.miaokatze.gtmb.api.CutTransforms;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Independent native bounds and literal state expectations; no game/world hooks or optional class initialization. */
public final class HologramVanillaMetadataRegression {

    private static final CutTransforms.Transform Y = pose(1, 0, 0, 0);
    private static final CutTransforms.Transform X2 = pose(0, 2, 0, 0);
    private static final CutTransforms.Transform Z = pose(0, 0, 1, 0);
    private static final CutTransforms.Transform MIRROR_X = pose(0, 0, 0, 1);

    private HologramVanillaMetadataRegression() {}

    public static void run() {
        try {
            literalSchemas();
            pairedBounds();
            stairsNativeGeometry();
            inheritanceAndPayload();
            neutralSchemas();
            invalidPoses();
        } catch (Exception failure) {
            throw new AssertionError("Native metadata schemas", failure);
        }
    }

    private static void literalSchemas() throws Exception {
        BlockRailPowered powered = allocate(BlockRailPowered.class);
        Field power = BlockRailBase.class.getDeclaredField("field_150053_a");
        power.setAccessible(true);
        power.setBoolean(powered, true);
        eq(powered, 8, Y, 9, "powered rail retains bit 8");
        eq(powered, 10, Y, 13, "powered east slope becomes powered south slope");
        eq(allocate(BlockRail.class), 6, Y, 7, "south-east curve becomes south-west curve");
        eq(allocate(BlockRail.class), 9, MIRROR_X, 8, "north-east curve mirrors north-west");
        eq(allocate(BlockLever.class), 9, Y, 11, "powered east-facing wall lever becomes south-facing");
        eq(allocate(BlockLever.class), 13, Y, 14, "powered floor lever changes base axis");
        BlockSlab slab = allocate(BlockStoneSlab.class);
        eq(slab, 3, X2, 11, "half flip preserves slab material");
        Field doubleSlab = BlockSlab.class.getDeclaredField("field_150004_a");
        doubleSlab.setAccessible(true);
        doubleSlab.setBoolean(slab, true);
        eq(slab, 11, Z, 11, "double slab does not toggle material/seam bit");
        eq(allocate(BlockTrapDoor.class), 12, Y, 15, "open upper south trapdoor turns west");
        eq(allocate(BlockTrapDoor.class), 12, X2, 5, "upper open trapdoor flips mounting half and side");
        eq(allocate(BlockBed.class), 12, Y, 13, "head and occupied bits retained");
        eq(allocate(BlockDoor.class), 4, Y, 5, "open east door becomes open south door");
        eq(allocate(BlockDoor.class), 9, MIRROR_X, 8, "door mirror flips upper hinge only");
        eq(allocate(BlockDoublePlant.class), 5, Y, 5, "lower plant type is not a direction");
        eq(allocate(BlockDoublePlant.class), 8, Y, 9, "upper plant yaw is spatial");
        eq(allocate(BlockRedstoneRepeater.class), 12, Y, 13, "repeater delay retained");
        eq(allocate(BlockRedstoneComparator.class), 12, Y, 13, "comparator mode and powered bits retained");
        eq(allocate(BlockCocoa.class), 8, Y, 9, "cocoa age retained");
        eq(allocate(BlockTripWireHook.class), 12, Y, 13, "hook connected and powered retained");
        eq(allocate(BlockFenceGate.class), 4, Y, 5, "gate open retained");
        eq(allocate(BlockAnvil.class), 8, Y, 9, "anvil damage retained");
        eq(allocate(BlockEndPortalFrame.class), 4, Y, 5, "portal frame eye retained");
        eq(allocate(BlockPumpkin.class), 3, Y, 0, "pumpkin east becomes south");
        eq(allocate(BlockVine.class), 5, Y, 10, "opposite vine faces rotate as a mask");
        eq(allocate(BlockPortal.class), 1, Y, 2, "nether portal plane swaps X/Z");
        eq(allocate(BlockHugeMushroom.class), 1, Y, 3, "north-west cap corner becomes north-east");
        eq(allocate(BlockHugeMushroom.class), 14, Z, 14, "all skin mushroom is orientation invariant");
        eq(allocate(BlockDispenser.class), 10, Y, 13, "dispenser triggered bit retained");
        eq(allocate(BlockButtonStone.class), 9, Y, 11, "button powered bit retained");
        if (Blocks.standing_sign != null) eq(Blocks.standing_sign, 0, Y, 4, "standing sign uses native 16-way yaw");
    }

    private static void inheritanceAndPayload() throws Exception {
        Block pillar = allocate(InheritedPillar.class);
        eq(pillar, 5, Y, 9, "inherited rotated pillar preserves low species bits");
        eq(pillar, 14, Z, 14, "all bark pillar retains its sentinel axis");
        Block stairs = allocate(InheritedStairs.class);
        eq(stairs, 0, Y, 2, "inherited stairs share vanilla schema");
        eq(allocate(InheritedTorch.class), 11, Y, 10, "inherited torch preserves nonattachment state");
        NBTTagCompound payload = new NBTTagCompound();
        payload.setByte("Rot", (byte) 0);
        payload.setString("Owner", "untouched");
        payload.setByte("SkullType", (byte) 3);
        NBTTagCompound nested = new NBTTagCompound();
        nested.setIntArray("slots", new int[] { 1, 19, 47 });
        payload.setTag("privatePayload", nested);
        CutTransforms.State skull = state(allocate(BlockSkull.class), 9, payload);
        HologramCutTransformMetadata.apply(skull, Y);
        SimpleAssert.eq(4, (int) skull.tile.getByte("Rot"), "floor skull turns native yaw 0 to 4");
        SimpleAssert.eq(9, skull.metadata, "skull no-drop bit retained");
        NBTTagCompound expected = (NBTTagCompound) payload.copy();
        expected.setByte("Rot", (byte) 4);
        SimpleAssert.eq(expected, skull.tile, "skull preserves every unrelated typed payload tag");
        SimpleAssert.eq(0, (int) payload.getByte("Rot"), "input tile is detached");
        CutTransforms.State repeater = state(allocate(BlockRedstoneRepeater.class), 12, payload);
        HologramCutTransformMetadata.apply(repeater, Y);
        SimpleAssert.eq(payload, repeater.tile, "metadata-only schemas do not edit payload");
    }

    private static void invalidPoses() throws Exception {
        rejected(allocate(BlockHopper.class), 0, X2, "upside-down hopper");
        rejected(allocate(BlockHopper.class), 1, Y, "illegal upward hopper input");
        rejected(allocate(BlockRail.class), 6, X2, "upside-down rail");
        rejected(allocate(BlockRail.class), 10, Y, "illegal rail code");
        rejected(allocate(BlockTorch.class), 5, X2, "ceiling torch");
        rejected(allocate(BlockStoneSlab.class), 3, Z, "sideways half slab");
        rejected(allocate(BlockStairs.class), 2, Z, "sideways stair profile");
        rejected(allocate(BlockChest.class), 2, X2, "upside-down chest roll");
        rejected(allocate(BlockDoor.class), 0, X2, "door roles require paired context");
        rejected(allocate(BlockBed.class), 0, X2, "bed has no ceiling orientation");
        rejected(allocate(BlockDoublePlant.class), 8, X2, "plant roles require paired context");
        rejected(allocate(BlockPortal.class), 2, Z, "horizontal portal");
        rejected(allocate(BlockPortal.class), 0, Y, "unresolved portal axis");
    }

    private static void neutralSchemas() throws Exception {
        eq(
            allocate(
                Class.forName("net.minecraft.block.BlockStone")
                    .asSubclass(Block.class)),
            3,
            Z,
            3,
            "native stone metadata is scalar");
        eq(
            allocate(
                Class.forName("net.minecraft.block.BlockDirt")
                    .asSubclass(Block.class)),
            2,
            Y,
            2,
            "native dirt variant is retained");
        rejected(
            allocate(
                Class.forName("net.minecraft.block.BlockSnow")
                    .asSubclass(Block.class)),
            3,
            Z,
            "snow layers have no sideways plane");
        rejected(
            allocate(
                Class.forName("net.minecraft.block.BlockCake")
                    .asSubclass(Block.class)),
            2,
            Y,
            "bitten cake has no rotated missing slice");
        SimpleAssert.that(
            !HologramCutTransformMetadata.supports(allocate(UnknownBlock.class)),
            "a mod subclass is not inferred to have neutral metadata");
        for (String name : new String[] { "BlockCommandBlock", "BlockPistonMoving", "BlockMobSpawner",
            "BlockBrewingStand" })
            SimpleAssert.that(
                !HologramCutTransformMetadata.supports(
                    allocate(
                        Class.forName("net.minecraft.block." + name)
                            .asSubclass(Block.class))),
                "opaque native payload remains unsupported: " + name);
        Set<Block> nativeBlocks = Collections.newSetFromMap(new IdentityHashMap<Block, Boolean>());
        // Fixture registration can acquire the default minecraft namespace; native identity comes from Blocks.
        for (Field field : Blocks.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Block.class.isAssignableFrom(field.getType())) continue;
            Block block = (Block) field.get(null);
            if (block == null || !nativeBlocks.add(block)) continue;
            String id = Block.blockRegistry.getNameForObject(block);
            boolean opaque = id.equals("minecraft:command_block") || id.equals("minecraft:piston_extension")
                || id.equals("minecraft:mob_spawner")
                || id.equals("minecraft:brewing_stand");
            SimpleAssert.eq(
                !opaque,
                HologramCutTransformMetadata.supports(block),
                "every registered native block has an explicit schema or unsupported classification: " + id);
        }
        if (!nativeBlocks.isEmpty()) SimpleAssert.eq(171, nativeBlocks.size(), "native 1.7.10 registry closure");
    }

    private static void pairedBounds() throws Exception {
        BlockDoor door = allocate(BlockDoor.class);
        for (int bottom = 0; bottom < 8; bottom++) for (int hinge = 0; hinge < 2; hinge++)
            for (CutTransforms.Transform pose : new CutTransforms.Transform[] { Y, MIRROR_X, pose(1, 0, 0, 4) }) {
                int upper = 8 | hinge;
                door.setBlockBoundsBasedOnState(access(door, bottom, upper), 0, 0, 0);
                double[] wanted = transformedBounds(bounds(door), pose);
                int nextBottom = changed(door, bottom, pose), nextUpper = changed(door, upper, pose);
                door.setBlockBoundsBasedOnState(access(door, nextBottom, nextUpper), 0, 0, 0);
                SimpleAssert
                    .that(Arrays.equals(wanted, bounds(door)), "native door bounds preserve open hinge geometry");
            }
    }

    private static void stairsNativeGeometry() throws Exception {
        BlockStairs stairs = allocate(BlockStairs.class);
        boolean[][] nativeShapes = new boolean[8][];
        for (int i = 0; i < 8; i++) nativeShapes[i] = nativeStair(stairs, i);
        eq(stairs, 0, Z, 4, "Z quarter turn is an expressible upper stair, not an unchanged lower stair");
        int expressible = 0, rejected = 0;
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++) for (int mirror = 0; mirror < 8; mirror++) for (int old = 0; old < 8; old++) {
                CutTransforms.Transform pose = pose(y, x, z, mirror);
                boolean[] wanted = new boolean[8];
                for (int i = 0; i < 8; i++) {
                    int[] at = pose.point(i & 1, i >> 1 & 1, i >> 2 & 1, 2, 2, 2);
                    wanted[at[0] | at[1] << 1 | at[2] << 2] = nativeShapes[old][i];
                }
                int expected = -1;
                for (int i = 0; i < 8; i++) if (Arrays.equals(wanted, nativeShapes[i])) expected = i;
                if (expected < 0) {
                    rejected(stairs, old, pose, "unrepresentable native stair shape");
                    rejected++;
                } else {
                    eq(stairs, old, pose, expected, "production stair matches independently transformed native bounds");
                    expressible++;
                }
            }
        SimpleAssert.that(expressible > 0 && rejected > 0, "native geometry exercises both capability outcomes");
    }

    private static boolean[] nativeStair(BlockStairs stairs, int metadata) {
        IBlockAccess access = access(null, metadata, metadata);
        boolean[] result = new boolean[8];
        stairs.func_150147_e(access, 0, 0, 0);
        fill(bounds(stairs), result);
        stairs.func_150145_f(access, 0, 0, 0);
        fill(bounds(stairs), result);
        return result;
    }

    private static IBlockAccess access(Block block, int bottom, int upper) {
        return (IBlockAccess) Proxy.newProxyInstance(
            HologramVanillaMetadataRegression.class.getClassLoader(),
            new Class<?>[] { IBlockAccess.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getBlockMetadata")) return (int) args[1] == 1 ? upper : bottom;
                if (method.getName()
                    .equals("getBlock")) return block;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });
    }

    private static double[] bounds(Block b) {
        return new double[] { b.getBlockBoundsMinX(), b.getBlockBoundsMinY(), b.getBlockBoundsMinZ(),
            b.getBlockBoundsMaxX(), b.getBlockBoundsMaxY(), b.getBlockBoundsMaxZ() };
    }

    private static double[] transformedBounds(double[] box, CutTransforms.Transform pose) {
        double[] result = { 1, 1, 1, 0, 0, 0 };
        int[][] axes = { pose.vector(1, 0, 0), pose.vector(0, 1, 0), pose.vector(0, 0, 1) };
        for (int i = 0; i < 8; i++) for (int row = 0; row < 3; row++) {
            double value = 0.5;
            for (int axis = 0; axis < 3; axis++) value += axes[axis][row] * (box[axis + ((i >> axis & 1) * 3)] - .5);
            result[row] = Math.min(result[row], value);
            result[row + 3] = Math.max(result[row + 3], value);
        }
        return result;
    }

    private static void fill(double[] box, boolean[] occupied) {
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) * .5 + .25, y = (i >> 1 & 1) * .5 + .25, z = (i >> 2 & 1) * .5 + .25;
            if (x > box[0] && y > box[1] && z > box[2] && x < box[3] && y < box[4] && z < box[5]) occupied[i] = true;
        }
    }

    private static void eq(Block block, int metadata, CutTransforms.Transform pose, int expected, String label)
        throws IOException {
        SimpleAssert.eq(expected, changed(block, metadata, pose), label);
    }

    private static int changed(Block block, int metadata, CutTransforms.Transform pose) throws IOException {
        CutTransforms.State state = state(block, metadata, null);
        SimpleAssert.that(HologramCutTransformMetadata.apply(state, pose), "native schema is recognized");
        return state.metadata;
    }

    private static void rejected(Block block, int metadata, CutTransforms.Transform pose, String label) {
        try {
            changed(block, metadata, pose);
            throw new AssertionError(label + " must fail explicitly");
        } catch (IOException expected) {
            SimpleAssert.that(
                !expected.getMessage()
                    .isEmpty(),
                label + " has a reason");
        }
    }

    private static CutTransforms.State state(Block block, int metadata, NBTTagCompound tile) {
        return new CutTransforms.State(block, metadata, tile, 0, 0, 0, 0, 0, 0);
    }

    private static CutTransforms.Transform pose(int y, int x, int z, int mirror) {
        return new CutTransforms.Transform(y, x, z, mirror);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(
            unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), type));
    }

    private static final class InheritedPillar extends BlockRotatedPillar {

        private InheritedPillar() {
            super(Material.wood);
        }

        protected IIcon getSideIcon(int metadata) {
            return null;
        }
    }

    private static final class InheritedStairs extends BlockStairs {

        private InheritedStairs() {
            super(Blocks.stone, 0);
        }
    }

    private static final class InheritedTorch extends BlockTorch {
    }

    private static final class UnknownBlock extends Block {

        private UnknownBlock() {
            super(Material.rock);
        }
    }
}
