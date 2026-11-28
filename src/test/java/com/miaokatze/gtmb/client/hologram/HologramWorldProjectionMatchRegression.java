// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramNativePlacement;
import com.miaokatze.gtmb.hologram.HologramPreviewPose;
import com.miaokatze.gtmb.hologram.HologramProjectionIdentity;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.covers.Cover;

/** Exercises the renderer's real coordinate mask, native event queues and region refresh without a GL context. */
public final class HologramWorldProjectionMatchRegression {

    private HologramWorldProjectionMatchRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramWorldProjectionMatchRegression.class, args)) return;
        run();
        System.out.println("GTMB_WORLD_PROJECTION_MATCH_PASS events, sections, pose, GT facing, retained NBT boundary");
    }

    static void run() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Field singleton = null;
        for (Field field : Minecraft.class.getDeclaredFields())
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Minecraft.class) singleton = field;
        if (singleton == null) throw new AssertionError("Minecraft singleton missing");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        Minecraft client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        FixtureWorld world = (FixtureWorld) unsafe.allocateInstance(FixtureWorld.class);
        world.blocks = new HashMap<>();
        world.metas = new HashMap<>();
        client.theWorld = world;
        singleton.set(null, client);
        try {
            ordinaryEvents(world);
            duplicateEvents(world);
            movingPose(world);
            gtIdentity(world, unsafe);
            nativePayload(world);
        } finally {
            MiaoVisonLiteRenderer.releaseWorld();
            singleton.set(null, previous);
        }
    }

    private static void ordinaryEvents(FixtureWorld world) throws Exception {
        HologramState state = state(false, 15, 16, 64);
        install(state, new MiaoVisonLiteRenderer.WorldTransform(0, 0, 0, null, new int[3]));
        Object first = group(15, 64, 0), adjacent = group(16, 64, 0), far = group(64, 64, 0);
        check(ghosts().size() == 3, "unbuilt ordinary cells remain visible");
        field(first.getClass(), "built").setInt(first, 7);
        field(adjacent.getClass(), "built").setInt(adjacent, 8);
        field(far.getClass(), "built").setInt(far, 9);
        int reads = world.reads;
        HologramClient.nativeTileChanged((net.minecraft.world.World) null, 15, 64, 0);
        check(
            ((Set<?>) field(first.getClass(), "dirtyMatches").get(first)).isEmpty(),
            "a tile packet without the active world cannot dirty its projection");
        world.put(15, 64, 0, Blocks.stone, 0);
        HologramClient.nativeTileChanged(world, 15, 64, 0);
        MiaoVisonLiteRenderer.worldBlockChanged(15, 64, 0);
        check(world.reads == reads, "events only enqueue changes");
        check(
            field(first.getClass(), "built").getInt(first) == 0
                && field(adjacent.getClass(), "built").getInt(adjacent) == 0
                && field(far.getClass(), "built").getInt(far) == 9,
            "only changed region and shared boundary retire batches");
        check(
            ((Set<?>) field(first.getClass(), "dirtyMatches").get(first)).size() == 1,
            "duplicate block events merge");
        ready(state, first);
        check(
            ghosts().size() == 2 && MiaoVisonLiteRenderer.lastWorldMatched == 1,
            "external placement hides exactly its matching ghost");
        check(world.reads == reads + 1, "one changed coordinate is reread; neighboring geometry stays cached");
        world.put(15, 64, 0, Blocks.air, 0);
        MiaoVisonLiteRenderer.worldBlockChanged(15, 64, 0);
        ready(state, first);
        check(
            ghosts().size() == 3 && MiaoVisonLiteRenderer.lastWorldMatched == 0,
            "external removal restores the ghost despite the old snapshot status");
        world.put(15, 64, 0, Blocks.dirt, 0);
        MiaoVisonLiteRenderer.worldBlockChanged(15, 64, 0);
        ready(state, first);
        check(ghosts().size() == 3, "a wrong replacement stays visible");
        OutlineTessellator outlines = new OutlineTessellator();
        check(
            MiaoVisonLiteRenderer.emitWorldProblemOutline(outlines, state, state.cells.get(0), false) == 24
                && outlines.vertices == 24,
            "a new wrong replacement submits actual world edges despite stale satisfied status");
        world.put(15, 64, 0, Blocks.stone, 0);
        MiaoVisonLiteRenderer.worldChunkChanged(0, 0, 1 << 4);
        MiaoVisonLiteRenderer.worldChunkChanged(0, 0, 1 << 4);
        check(
            field(first.getClass(), "allMatchesDirty").getBoolean(first),
            "received section merges its matching work");
        check(!field(far.getClass(), "allMatchesDirty").getBoolean(far), "unrelated chunk remains untouched");
        ready(state, first);
        check(ghosts().size() == 2, "S21/S26 section data updates the same world mask without a screen");
        world.unloaded = true;
        MiaoVisonLiteRenderer.worldChunkChanged(0, 0, 0xffff);
        ready(state, first);
        check(ghosts().size() == 3, "chunk unload stops claiming the target is matched");
        world.unloaded = false;
        check(
            field(MiaoVisonLiteRenderer.class, "worldState").get(null) == state
                && field(MiaoVisonLiteRenderer.class, "worldIndex").getInt(null) == state.cells.size()
                && groups().size() == 3,
            "events preserve the snapshot, full target index and unaffected groups");
        MiaoVisonLiteRenderer.releaseWorld();
        MiaoVisonLiteRenderer.worldChunkChanged(0, 0, 0xffff);
        MiaoVisonLiteRenderer.worldBlockChanged(15, 64, 0);
        check(groups().isEmpty(), "world teardown drops pending changes");
        world.blocks.clear();
    }

    private static void movingPose(FixtureWorld world) throws Exception {
        HologramState state = state(true, 15);
        HologramPreviewPose pose = new HologramPreviewPose().turn(false)
            .turn(true);
        var transform = new MiaoVisonLiteRenderer.WorldTransform(100, 70, -30, pose, new int[] { 2, 64, -1 });
        int[] at = transform.apply(15, 64, 0);
        check(
            java.util.Arrays.equals(new int[] { 15, 64, 0 }, transform.inverse(at[0], at[1], at[2])),
            "translated/rotated authored pivot is reversible");
        world.put(15, 64, 0, Blocks.stone, 0);
        install(state, transform);
        check(ghosts().size() == 1, "a source-position match never hides a moved virtual target");
        world.put(at[0], at[1], at[2], Blocks.stone, 0);
        MiaoVisonLiteRenderer.worldBlockChanged(at[0], at[1], at[2]);
        ready(state, group(15, 64, 0));
        check(ghosts().isEmpty(), "world events use the final rotated destination");
        var next = new MiaoVisonLiteRenderer.WorldTransform(101, 70, -30, pose, new int[] { 2, 64, -1 });
        method("updateWorldTransform", MiaoVisonLiteRenderer.WorldTransform.class).invoke(null, next);
        ready(state, group(15, 64, 0));
        check(ghosts().size() == 1, "moving the anchor invalidates old matching evidence");
        check(
            state.cells.size() == 1 && state.regions.at(15, 64, 0) != null,
            "world masking retains the full UI preview and picking index");
        world.blocks.clear();
    }

    private static void duplicateEvents(FixtureWorld world) throws Exception {
        HologramState original = state(false, 15, 15);
        NBTTagCompound source = (NBTTagCompound) original.data.copy();
        NBTTagList rows = new NBTTagList();
        rows.appendTag(
            original.cells.get(0)
                .tag());
        NBTTagCompound second = original.cells.get(1)
            .tag();
        second.setString("wantId", "minecraft:glass");
        rows.appendTag(second);
        source.setTag("cells", rows);
        HologramState state = new HologramState(source);
        install(state, new MiaoVisonLiteRenderer.WorldTransform(0, 0, 0, null, new int[3]));
        Object region = group(15, 64, 0);
        Set<?> occluders = (Set<?>) field(MiaoVisonLiteRenderer.class, "worldOccluders").get(null);
        check(
            ghosts().size() == 1 && occluders.size() == 1,
            "duplicate stone/glass rows share one ghost coordinate and retain the opaque stone contribution");
        world.put(15, 64, 0, Blocks.stone, 0);
        MiaoVisonLiteRenderer.worldBlockChanged(15, 64, 0);
        check(
            ((Set<?>) field(region.getClass(), "dirtyMatches").get(region)).size() == 2,
            "a native event invalidates every row at the changed coordinate");
        ready(state, region);
        check(
            ghosts().size() == 1 && occluders.isEmpty() && MiaoVisonLiteRenderer.lastWorldMatched == 1,
            "matching stone withdraws only its opaque contribution while unmatched glass remains");
        check(
            !(Boolean) method("worldGhostCell", HologramState.Cell.class).invoke(null, state.cells.get(0))
                && (Boolean) method("worldGhostCell", HologramState.Cell.class).invoke(null, state.cells.get(1)),
            "the real geometry gate draws only the unmatched row of a shared coordinate");
        world.put(15, 64, 0, Blocks.air, 0);
        MiaoVisonLiteRenderer.worldBlockChanged(15, 64, 0);
        ready(state, region);
        check(
            ghosts().size() == 1 && occluders.size() == 1 && MiaoVisonLiteRenderer.lastWorldMatched == 0,
            "removal restores both row contributions without duplicating their coordinate");
        world.blocks.clear();
    }

    private static void gtIdentity(FixtureWorld world, sun.misc.Unsafe unsafe) throws Exception {
        FixtureGt tile = (FixtureGt) unsafe.allocateInstance(FixtureGt.class);
        field(gregtech.api.metatileentity.CoverableTileEntity.class, "covers").set(tile, new Cover[6]);
        tile.id = 42;
        tile.front = ForgeDirection.NORTH;
        world.tile = tile;
        world.put(3, 64, 0, Blocks.stone, 0);
        ItemStack stack = new ItemStack(new ItemMachines(Blocks.stone), 1, 42);
        var target = HologramProjectionIdentity.target(Blocks.stone, 0, 2, stack, "", null, null);
        check(target.matches(world, 3, 64, 0), "same GT identity and required facing match");
        tile.front = ForgeDirection.SOUTH;
        check(!target.matches(world, 3, 64, 0), "same GT ID with a different facing stays visible");
        tile.front = ForgeDirection.NORTH;
        tile.id = 43;
        check(!target.matches(world, 3, 64, 0), "different GT identity stays visible");
        tile.id = 42;
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("unsynchronizedInventory", "server-only contents");
        payload.setString("unsynchronizedConfiguration", "server-only configuration");
        var retained = HologramProjectionIdentity.target(Blocks.stone, 0, 2, stack, "", payload, null);
        check(
            retained.matches(world, 3, 64, 0),
            "same GT model is visually hidden even when inventory and business configuration are unsynchronized");
        HologramState model = state(false, 3);
        HologramState.Cell cell = model.cells.get(0);
        field(HologramState.Cell.class, "targetStack").set(cell, stack);
        field(HologramState.Cell.class, "targetFacing").setInt(cell, 2);
        cell.display(false)
            .setTag("nativeTile", payload);
        install(model, new MiaoVisonLiteRenderer.WorldTransform(0, 0, 0, null, new int[3]));
        check(ghosts().isEmpty(), "the real renderer mask hides an existing GT model despite server-only business NBT");
        MiaoVisonLiteRenderer.releaseWorld();
        NBTTagCompound visual = new NBTTagCompound();
        visual.setInteger("color", 4);
        var colored = HologramProjectionIdentity.target(Blocks.stone, 0, 2, stack, "", payload, null, visual);
        check(!colored.matches(world, 3, 64, 0), "a known different GT visual color keeps its projection");
        HologramPreviewPose pose = new HologramPreviewPose().turn(false);
        var rotated = HologramProjectionIdentity.target(Blocks.stone, 0, 2, stack, "gregtech.fixture", null, pose);
        int[] north = pose.apply(0, 0, -1);
        tile.front = ForgeDirection.getOrientation(north[0] < 0 ? 4 : north[0] > 0 ? 5 : north[2] < 0 ? 2 : 3);
        check(
            rotated != null && rotated.matches(world, 3, 64, 0),
            "GT orientation uses the same cube pose as its destination");
        world.tile = null;
    }

    private static void nativePayload(FixtureWorld world) {
        world.put(6, 64, 0, Blocks.chest, 2);
        NBTTagCompound inventory = new NBTTagCompound();
        inventory.setString("Items", "inventory unavailable on client");
        var chest = HologramProjectionIdentity.target(Blocks.chest, 2, -1, null, "", inventory, null);
        check(chest.matches(world, 6, 64, 0), "a fixed chest model hides independently of its inventory NBT");
        Block block = new Block(Material.rock) {

            @Override
            public boolean hasTileEntity(int metadata) {
                return true;
            }
        };
        HologramNativePlacement.Adapter adapter = new HologramNativePlacement.Adapter() {

            public boolean supports(Block candidate) {
                return candidate == block;
            }

            public TileEntity create(Block candidate, int metadata, NBTTagCompound payload,
                net.minecraft.world.World detached) {
                ConfigTile tile = new ConfigTile();
                tile.config = payload.getInteger("config");
                return tile;
            }

            public List<ItemStack> materials(Block candidate, int metadata, TileEntity tile) {
                return java.util.Collections.emptyList();
            }
        };
        HologramNativePlacement.register("gtmb:projection_fixture", adapter);
        try {
            ConfigTile actual = new ConfigTile();
            actual.config = 7;
            world.tile = actual;
            world.put(6, 64, 0, block, 0);
            NBTTagCompound payload = new NBTTagCompound();
            actual.writeToNBT(payload);
            var target = HologramProjectionIdentity.target(block, 0, -1, null, "", payload, null);
            check(target.matches(world, 6, 64, 0), "native adapter compares a retained special-block payload");
            actual.config = 8;
            check(!target.matches(world, 6, 64, 0), "same special block with different retained NBT stays visible");
            var absent = HologramProjectionIdentity.target(block, 0, -1, null, "", null, null);
            check(!absent.matches(world, 6, 64, 0), "missing native payload never proves a special tile configured");
        } finally {
            HologramNativePlacement.unregister("gtmb:projection_fixture", adapter);
            world.tile = null;
        }
    }

    private static HologramState state(boolean virtual, int... xs) {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "projection-match-fixture");
        source.setBoolean("virtual", virtual);
        NBTTagList cells = new NBTTagList();
        for (int x : xs) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", x);
            cell.setInteger("y", 64);
            cell.setString("id", "minecraft:air");
            cell.setString("wantId", "minecraft:stone");
            cell.setString("status", "satisfied"); // stale snapshots must not override current world evidence
            cells.appendTag(cell);
        }
        source.setTag("cells", cells);
        return new HologramState(source);
    }

    private static void install(HologramState state, MiaoVisonLiteRenderer.WorldTransform transform) throws Exception {
        MiaoVisonLiteRenderer.releaseWorld();
        field(MiaoVisonLiteRenderer.class, "worldState").set(null, state);
        field(MiaoVisonLiteRenderer.class, "worldTransform").set(null, transform);
        method("indexWorld", HologramState.class, long.class).invoke(null, state, Long.MAX_VALUE);
    }

    private static void ready(HologramState state, Object group) throws Exception {
        check(
            (Boolean) method("readyForWorldGeometry", HologramState.class, group.getClass(), long.class)
                .invoke(null, state, group, Long.MAX_VALUE),
            "changed-mask refresh finishes");
    }

    private static Map<?, ?> groups() throws Exception {
        return (Map<?, ?>) field(MiaoVisonLiteRenderer.class, "worldGroups").get(null);
    }

    private static Set<?> ghosts() throws Exception {
        return (Set<?>) field(MiaoVisonLiteRenderer.class, "worldGhosts").get(null);
    }

    private static Object group(int x, int y, int z) throws Exception {
        for (Object group : groups().values()) {
            List<?> cells = (List<?>) field(group.getClass(), "cells").get(group);
            if (!cells.isEmpty()) {
                HologramState.Cell cell = (HologramState.Cell) cells.get(0);
                if (cell.x >> 4 == x >> 4 && cell.y >> 4 == y >> 4 && cell.z >> 4 == z >> 4) return group;
            }
        }
        throw new AssertionError("missing world region");
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(String name, Class<?>... parameters) throws Exception {
        Method method = MiaoVisonLiteRenderer.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class FixtureWorld extends WorldClient {

        Map<String, Block> blocks;
        Map<String, Integer> metas;
        TileEntity tile;
        boolean unloaded;
        int reads;

        FixtureWorld() {
            super(null, null, 0, null, null);
        }

        void put(int x, int y, int z, Block block, int metadata) {
            blocks.put(x + ":" + y + ":" + z, block);
            metas.put(x + ":" + y + ":" + z, metadata);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return !unloaded;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            reads++;
            return blocks.getOrDefault(x + ":" + y + ":" + z, Blocks.air);
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return metas.getOrDefault(x + ":" + y + ":" + z, 0);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tile;
        }
    }

    private static final class FixtureGt extends BaseMetaTileEntity {

        int id;
        ForgeDirection front;

        @Override
        public int getMetaTileID() {
            return id;
        }

        @Override
        public ForgeDirection getFrontFacing() {
            return front;
        }

        @Override
        public byte getColorization() {
            return -1;
        }
    }

    private static final class ConfigTile extends TileEntity {

        int config;

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            tag.setString("id", "gtmb.projection_fixture");
            tag.setInteger("config", config);
        }
    }

    private static final class OutlineTessellator extends Tessellator {

        int vertices;

        @Override
        public void addVertex(double x, double y, double z) {
            vertices++;
        }
    }
}
