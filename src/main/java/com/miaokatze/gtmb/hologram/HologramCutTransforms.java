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
        return apply(original, transform, ox, oy, oz, original.getInteger("dimension"));
    }

    static NBTTagCompound apply(NBTTagCompound original, CutTransforms.Transform transform, int ox, int oy, int oz,
        int targetDimension) {
        NBTTagCompound result = (NBTTagCompound) original.copy();
        transform.write(result);
        List<CutTransforms.Adapter> adapters;
        synchronized (ADAPTERS) {
            adapters = new ArrayList<>(ADAPTERS);
        }
        HologramCutRelations.Context relations = HologramCutRelations
            .context(original, transform, ox, oy, oz, targetDimension);
        if (transform.identity() && adapters.isEmpty()
            && !relations.targetDependent()
            && !relations.crossDimensionExternal()) return result;
        int sx = original.getInteger("sizeX"), sy = original.getInteger("sizeY"), sz = original.getInteger("sizeZ");
        int[] size = transform.size(sx, sy, sz);
        result.setInteger("sizeX", size[0]);
        result.setInteger("sizeY", size[1]);
        result.setInteger("sizeZ", size[2]);
        NBTTagList cells = original.getTagList("cells", 10), changed = new NBTTagList(), warnings = new NBTTagList();
        java.util.Set<String> reported = new java.util.LinkedHashSet<>();
        HologramCutLittleTiles littleTiles = HologramCutLittleTiles.rotate(original, transform);
        // accepts(State) can itself depend on the target, including when no adapter matches at the source origin.
        boolean targetDependent = !adapters.isEmpty() || relations.targetDependent();
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
                oz + position[2],
                original.getInteger("dimension"),
                targetDimension);
            try {
                boolean handled = false;
                for (CutTransforms.Adapter adapter : adapters) if (adapter.accepts(state.copy())) {
                    adapter.transform(state, transform);
                    handled = true;
                    break;
                }
                if (!handled && !transform.identity() && littleTiles.contains(i)) {
                    state.tile = littleTiles.tile(i);
                    handled = true;
                }
                if (!handled && !transform.identity()) handled = HologramCutTransformDirections.apply(state, transform);
                if (!handled && !transform.identity()) {
                    // The raw API only; never enter blueprint string codecs, materials or native schema approval.
                    if (!legacyRotations(state, transform))
                        throw new IOException("Raw spatial schema did not handle every requested quarter turn");
                }
                cell.setInteger("meta", state.metadata);
                if (state.tile != null) cell.setTag("tile", state.tile.copy());
                else cell.removeTag("tile");
            } catch (IOException | RuntimeException | LinkageError unsupportedSpatialSchema) {
                // WE/MM retain unknown spatial data. Discard partial adapter edits, keep the entire original payload.
                reported.add(source.getString("block"));
            }
            if (source.hasKey("tile", 10) && cell.hasKey("tile", 10)) try {
                NBTTagCompound tile = (NBTTagCompound) cell.getCompoundTag("tile")
                    .copy();
                relations.apply(source.getCompoundTag("tile"), tile);
                cell.setTag("tile", tile);
            } catch (IOException | RuntimeException | LinkageError unsupportedRelation) {
                reported.add(source.getString("block"));
            }
            changed.appendTag(cell);
        }
        for (String name : reported) warnings.appendTag(new NBTTagString(name));
        if (relations.crossDimensionExternal())
            warnings.appendTag(new NBTTagString("gtmb:external_dimension_relation"));
        result.setTag("cells", changed);
        if (!transform.identity() || warnings.tagCount() > 0) result.setTag("transformUnknown", warnings);
        if (targetDependent) result.setBoolean("transformTargetDependent", true);
        return result;
    }

    private static boolean legacyRotations(CutTransforms.State state, CutTransforms.Transform pose) throws IOException {
        if (pose.mirror != 0) return false;
        boolean handled = true;
        for (int i = 0; i < pose.turnX; i++) handled &= turn(state, true, 1);
        for (int i = 0; i < pose.rotation; i++) handled &= turn(state, false, 1);
        // Z = Y^-1 X Y, using the existing raw quarter-turn contract.
        for (int i = 0; i < pose.turnZ; i++) {
            handled &= turn(state, false, 3);
            handled &= turn(state, true, 1);
            handled &= turn(state, false, 1);
        }
        return handled;
    }

    private static boolean turn(CutTransforms.State state, boolean vertical, int turns) throws IOException {
        HologramBlockTransformApi.RotationResult result = HologramBlockTransformApi
            .rotate(state.block, state.metadata, state.tile, vertical, turns);
        state.metadata = result.metadata;
        state.tile = result.tile;
        return result.handled;
    }
}
