package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.api.CutTransforms;

/** Pure transforms of disposable raw entries; the persistent player queue is never edited by this module. */
public final class HologramCutTransforms {

    private static final List<CutTransforms.Adapter> ADAPTERS = new ArrayList<>();

    private HologramCutTransforms() {}

    /** Bridge for the versioned public extension catalog; the returned action removes this exact slot. */
    public static Runnable install(CutTransforms.Adapter adapter) {
        CutTransforms.Adapter slot = new CutTransforms.Adapter() {

            public boolean accepts(CutTransforms.State state) {
                return adapter.accepts(state);
            }

            public void transform(CutTransforms.State state, CutTransforms.Transform transform) throws IOException {
                adapter.transform(state, transform);
            }
        };
        synchronized (ADAPTERS) {
            ADAPTERS.add(0, slot);
        }
        return () -> {
            synchronized (ADAPTERS) {
                ADAPTERS.remove(slot);
            }
        };
    }

    static NBTTagCompound apply(NBTTagCompound original, CutTransforms.Transform transform, int ox, int oy, int oz) {
        NBTTagCompound result = (NBTTagCompound) original.copy();
        transform.write(result);
        if (transform.identity()) return result;
        int sx = original.getInteger("sizeX"), sy = original.getInteger("sizeY"), sz = original.getInteger("sizeZ");
        int[] size = transform.size(sx, sy, sz);
        result.setInteger("sizeX", size[0]);
        result.setInteger("sizeY", size[1]);
        result.setInteger("sizeZ", size[2]);
        NBTTagList cells = original.getTagList("cells", 10), changed = new NBTTagList(), warnings = new NBTTagList();
        java.util.Set<String> reported = new java.util.LinkedHashSet<>();
        List<CutTransforms.Adapter> adapters;
        synchronized (ADAPTERS) {
            adapters = new ArrayList<>(ADAPTERS);
        }
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound source = cells.getCompoundTagAt(i), cell = (NBTTagCompound) source.copy();
            int x = source.getInteger("x"), y = source.getInteger("y"), z = source.getInteger("z");
            int[] position = transform.point(x, y, z, sx, sy, sz);
            cell.setInteger("x", position[0]);
            cell.setInteger("y", position[1]);
            cell.setInteger("z", position[2]);
            Block block = Block.getBlockFromName(source.getString("block"));
            CutTransforms.State state = new CutTransforms.State(
                block,
                source.getInteger("meta"),
                source.hasKey("tile", 10) ? source.getCompoundTag("tile") : null,
                original.getInteger("x") + x,
                original.getInteger("y") + y,
                original.getInteger("z") + z,
                ox + position[0],
                oy + position[1],
                oz + position[2]);
            try {
                boolean handled = false;
                for (CutTransforms.Adapter adapter : adapters) if (adapter.accepts(state.copy())) {
                    adapter.transform(state, transform);
                    handled = true;
                    break;
                }
                if (!handled) handled = HologramCutTransformDirections.apply(state, transform);
                if (!handled) {
                    // The raw API only; never enter blueprint string codecs, materials or native schema approval.
                    state = legacyRotations(state, transform);
                    if (state.tile != null || transform.mirror != 0) reported.add(source.getString("block"));
                }
                cell.setInteger("meta", state.metadata);
                if (state.tile != null) cell.setTag("tile", state.tile.copy());
            } catch (IOException | RuntimeException | LinkageError unsupportedSpatialSchema) {
                // WE/MM retain unknown spatial data. Discard partial adapter edits, keep the entire original payload.
                reported.add(source.getString("block"));
            }
            changed.appendTag(cell);
        }
        for (String name : reported) warnings.appendTag(new NBTTagString(name));
        result.setTag("cells", changed);
        result.setTag("transformUnknown", warnings);
        return result;
    }

    private static CutTransforms.State legacyRotations(CutTransforms.State state, CutTransforms.Transform pose)
        throws IOException {
        if (pose.mirror != 0) return state;
        for (int i = 0; i < pose.turnX; i++) state = turn(state, true, 1);
        for (int i = 0; i < pose.rotation; i++) state = turn(state, false, 1);
        // Z = Y^-1 X Y, using the existing raw quarter-turn contract.
        for (int i = 0; i < pose.turnZ; i++) {
            state = turn(state, false, 3);
            state = turn(state, true, 1);
            state = turn(state, false, 1);
        }
        return state;
    }

    private static CutTransforms.State turn(CutTransforms.State state, boolean vertical, int turns) throws IOException {
        HologramBlockTransformApi.RotationResult result = HologramBlockTransformApi
            .rotate(state.block, state.metadata, state.tile, vertical, turns);
        state.metadata = result.metadata;
        state.tile = result.tile;
        return state;
    }
}
