package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

/** Native preview and smoke fixtures use precisely the detached blueprint adapter registry. */
public final class HologramBlockTransformApi {

    /** Register only schemas whose actual block class or tile ID your integration understands. */
    public interface Adapter {

        boolean accepts(State state);

        /** Mutate detached metadata/tile/item. Throw IOException for an unrepresentable rotation. */
        void rotate(State state, boolean vertical) throws IOException;
    }

    public static final class State {

        public final Block block;
        public int metadata;
        public NBTTagCompound tile;
        public NBTTagCompound item;
        public final int x, y, z;
        /** Optional LittleTiles lookup: "cellX,cellY,cellZ:minX,minY,minZ" -> transformed voxel minimum. */
        public final Map<String, int[]> references;

        private State(HologramBlockTransforms.Data data) {
            block = data.block;
            metadata = data.metadata;
            tile = copy(data.tile);
            item = copy(data.item);
            x = data.x;
            y = data.y;
            z = data.z;
            Map<String, int[]> detached = new HashMap<>();
            if (data.littleReferences != null)
                for (Map.Entry<String, int[]> entry : data.littleReferences.entrySet()) detached.put(
                    entry.getKey(),
                    entry.getValue()
                        .clone());
            references = Collections.unmodifiableMap(detached);
        }
    }

    public static final class RotationResult {

        public final int metadata;
        public final NBTTagCompound tile;
        public final NBTTagCompound item;

        private RotationResult(int metadata, NBTTagCompound tile, NBTTagCompound item) {
            this.metadata = metadata;
            this.tile = tile;
            this.item = item;
        }
    }

    private HologramBlockTransformApi() {}

    /**
     * Newest registration is tried first, allowing an integration to handle a known custom FMP part before the
     * built-in microblock adapter. The returned handle removes exactly this registration. accepts() receives a
     * disposable copy; its mutations are discarded. Registered adapters are used by editing, placement and preview.
     */
    public static AutoCloseable register(final Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        final HologramBlockTransforms.Adapter bridge = new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return adapter.accepts(new State(data));
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                State state = new State(data);
                adapter.rotate(state, vertical);
                data.metadata = state.metadata;
                data.tile = copy(state.tile);
                data.item = copy(state.item);
            }
        };
        HologramBlockTransforms.registerFirst(bridge);
        return () -> HologramBlockTransforms.unregister(bridge);
    }

    private static NBTTagCompound copy(NBTTagCompound tag) {
        return tag == null ? null : (NBTTagCompound) tag.copy();
    }

    public static RotationResult rotate(Block block, int metadata, NBTTagCompound tile, boolean vertical, int turns)
        throws IOException {
        return rotate(block, metadata, tile, null, vertical, turns, null, 0, 0, 0);
    }

    public static RotationResult rotate(Block block, int metadata, NBTTagCompound tile, NBTTagCompound item,
        boolean vertical, int turns, Map<String, int[]> references, int x, int y, int z) throws IOException {
        HologramBlockTransforms.Data data = new HologramBlockTransforms.Data(block, "", metadata, tile, item);
        data.x = x;
        data.y = y;
        data.z = z;
        if (references != null) {
            data.littleReferences = new HashMap<>();
            for (Map.Entry<String, int[]> entry : references.entrySet()) data.littleReferences.put(
                entry.getKey(),
                entry.getValue()
                    .clone());
        }
        int count = Math.floorMod(turns, 4);
        if (count > 1 && references != null && !references.isEmpty())
            throw new IOException("Rebuild neighboring tile references between quarter turns");
        for (int i = 0; i < count; i++) data = HologramBlockTransforms.turn(data, vertical);
        return new RotationResult(data.metadata, data.tile, data.item);
    }
}
