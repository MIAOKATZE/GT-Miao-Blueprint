// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.BlockSnapshot;

/** In-memory block writes for the real headless placement transaction. It stays read-only until armed. */
final class HologramPlacementFixtureWorld extends HologramServiceEditRegression.ReadOnlyWorld {

    private static final class State {

        final Block block;
        final int metadata;

        State(Block block, int metadata) {
            this.block = block;
            this.metadata = metadata;
        }
    }

    private Map<String, State> placed;
    private List<String> writes;
    private boolean armed;

    static HologramPlacementFixtureWorld create(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        HologramPlacementFixtureWorld world = (HologramPlacementFixtureWorld) unsafe
            .allocateInstance(HologramPlacementFixtureWorld.class);
        world.placed = new HashMap<>();
        world.writes = new ArrayList<>();
        setWorldField(world, "provider", new WorldProviderSurface());
        setWorldField(world, "worldAccesses", new ArrayList<>());
        setWorldField(world, "perWorldStorage", new MapStorage(null));
        world.capturedBlockSnapshots = new ArrayList<>();
        return world;
    }

    private static void setWorldField(World world, String name, Object value) throws ReflectiveOperationException {
        Field field = World.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(world, value);
    }

    void seed(int x, int y, int z, Block block, int metadata) {
        if (armed) throw new AssertionError("Fixture pre-existing blocks must be set before admission");
        placed.put(position(x, y, z), new State(block, metadata));
    }

    void arm() {
        armed = true;
    }

    /** A soft-expired slice still admits the first work item, and cannot accidentally batch the second. */
    static void firstJobTick(HologramSession session) throws ReflectiveOperationException {
        var scheduler = HologramWorkspaceScheduler.class.getDeclaredMethod("tickSession", HologramSession.class);
        scheduler.setAccessible(true);
        long expired = System.nanoTime() - 1_000_000_000L;
        HologramWorkspaceScheduler.runRound(java.util.Collections.singletonList(session), current -> {
            try {
                scheduler.invoke(null, current);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("actual scheduler placement step", failure);
            }
        }, () -> expired, 1L);
    }

    List<String> writes() {
        return java.util.Collections.unmodifiableList(writes);
    }

    static String position(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        State saved = placed.get(position(x, y, z));
        return saved == null ? super.getBlock(x, y, z) : saved.block;
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        State saved = placed.get(position(x, y, z));
        return saved == null ? super.getBlockMetadata(x, y, z) : saved.metadata;
    }

    @Override
    public boolean isAirBlock(int x, int y, int z) {
        return getBlock(x, y, z) == Blocks.air;
    }

    @Override
    public boolean canPlaceEntityOnSide(Block block, int x, int y, int z, boolean replace, int side, Entity entity,
        ItemStack stack) {
        return block != null && block != Blocks.air
            && blockExists(x, y, z)
            && isAirBlock(x, y, z)
            && getTileEntity(x, y, z) == null;
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block, int metadata, int flags) {
        if (!armed) return super.setBlock(x, y, z, block, metadata, flags);
        if (captureBlockSnapshots) capturedBlockSnapshots
            .add(new BlockSnapshot(this, x, y, z, getBlock(x, y, z), getBlockMetadata(x, y, z), flags));
        String position = position(x, y, z);
        placed.put(position, new State(block, metadata));
        writes.add(position);
        return true;
    }

    @Override
    public boolean setBlockMetadataWithNotify(int x, int y, int z, int metadata, int flags) {
        if (!armed) throw new AssertionError("Admission wrote fixture metadata");
        String position = position(x, y, z);
        State saved = placed.get(position);
        if (saved == null) return false;
        if (captureBlockSnapshots)
            capturedBlockSnapshots.add(new BlockSnapshot(this, x, y, z, saved.block, saved.metadata, flags));
        placed.put(position, new State(saved.block, metadata));
        return true;
    }

    @Override
    public void playSoundEffect(double x, double y, double z, String sound, float volume, float pitch) {}

    @Override
    public void markAndNotifyBlock(int x, int y, int z, Chunk chunk, Block old, Block placed, int flags) {
        // This fixture has no chunks/listeners; native notification behavior is covered by the real lab.
    }

    @Override
    public void removeTileEntity(int x, int y, int z) {
        if (getTileEntity(x, y, z) != null) throw new AssertionError("Unexpected fixture tile removal");
    }
}
