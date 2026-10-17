package com.miaokatze.gtmb.api;

import java.io.IOException;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramBlockTransformApi;

/** Rotation of disposable metadata/NBT values, used by editing, preview and native placement. */
public final class BlockTransforms {

    private BlockTransforms() {}

    public interface Adapter {

        boolean accepts(State state);

        void rotate(State state, boolean vertical) throws IOException;
    }

    public static final class State {

        public final Block block;
        public int metadata;
        public NBTTagCompound tile, item;
        public final int x, y, z;
        public final Map<String, int[]> references;

        private State(HologramBlockTransformApi.State state) {
            block = state.block;
            metadata = state.metadata;
            tile = state.tile;
            item = state.item;
            x = state.x;
            y = state.y;
            z = state.z;
            references = state.references;
        }
    }

    /** Newest adapter wins. accepts receives a disposable copy and cannot mutate the actual rotation input. */
    public static GTMBExtensions.Registration register(String id, Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        return GTMBExtensions.install(id, "block-transform", () -> {
            AutoCloseable handle = HologramBlockTransformApi.register(new HologramBlockTransformApi.Adapter() {

                public boolean accepts(HologramBlockTransformApi.State state) {
                    return adapter.accepts(new State(state));
                }

                public void rotate(HologramBlockTransformApi.State state, boolean vertical) throws IOException {
                    State copy = new State(state);
                    adapter.rotate(copy, vertical);
                    state.metadata = copy.metadata;
                    state.tile = copy.tile;
                    state.item = copy.item;
                }
            });
            return () -> Declarations.close(handle);
        });
    }
}
