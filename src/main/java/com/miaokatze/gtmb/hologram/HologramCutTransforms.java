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
    private static long adapterGeneration;

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
            adapterGeneration++;
        }
        return () -> {
            synchronized (ADAPTERS) {
                ADAPTERS.remove(slot);
                adapterGeneration++;
            }
        };
    }

    static NBTTagCompound apply(NBTTagCompound original, CutTransforms.Transform transform, int ox, int oy, int oz) {
        return apply(original, transform, ox, oy, oz, original.getInteger("dimension"));
    }

    static NBTTagCompound apply(NBTTagCompound original, CutTransforms.Transform transform, int ox, int oy, int oz,
        int targetDimension) {
        Task task = new Task(original, transform, ox, oy, oz, targetDimension);
        while (!task.step(4096, Long.MAX_VALUE)) {}
        return task.result();
    }

    /** Owns a stable raw entry and adapter snapshot; publication is allowed only after every cell finishes. */
    static final class Task {

        private final NBTTagCompound original, result;
        private final CutTransforms.Transform transform;
        private final int ox, oy, oz, targetDimension, sx, sy, sz;
        private final List<CutTransforms.Adapter> adapters;
        private final HologramCutRelations.Context relations;
        private final HologramCutLittleTiles littleTiles;
        private final NBTTagList cells, changed = new NBTTagList(), warnings = new NBTTagList();
        private final java.util.Set<String> reported = new java.util.LinkedHashSet<>();
        private final boolean targetDependent;
        private final boolean copyOnly;
        private final long generation;
        private final long blockTransformGeneration;
        private int cursor;
        private boolean done;

        Task(NBTTagCompound original, CutTransforms.Transform transform, int ox, int oy, int oz, int targetDimension) {
            this.original = original;
            this.transform = transform;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.targetDimension = targetDimension;
            sx = original.getInteger("sizeX");
            sy = original.getInteger("sizeY");
            sz = original.getInteger("sizeZ");
            // Copy the small header now, then each owned cell once as the continuation advances.
            result = HologramCutEntry.directory(original);
            // directory supplies a display fallback; transforms preserve the original header exactly.
            if (!original.hasKey("name")) result.removeTag("name");
            else result.setTag(
                "name",
                original.getTag("name")
                    .copy());
            transform.write(result);
            synchronized (ADAPTERS) {
                adapters = new ArrayList<>(ADAPTERS);
                generation = adapterGeneration;
            }
            blockTransformGeneration = HologramBlockTransforms.generation();
            relations = HologramCutRelations.context(original, transform, ox, oy, oz, targetDimension);
            // Identity translation still relocates relationships, but never consumes the voxel rotation graph.
            littleTiles = transform.identity() ? null : HologramCutLittleTiles.rotate(original, transform);
            targetDependent = !adapters.isEmpty() || relations.targetDependent();
            copyOnly = transform.identity() && adapters.isEmpty()
                && !relations.targetDependent()
                && !relations.crossDimensionExternal();
            int[] size = transform.size(sx, sy, sz);
            result.setInteger("sizeX", size[0]);
            result.setInteger("sizeY", size[1]);
            result.setInteger("sizeZ", size[2]);
            cells = original.getTagList("cells", 10);
        }

        boolean step(int maximumCells, long deadline) {
            if (maximumCells <= 0) throw new IllegalArgumentException("maximumCells");
            synchronized (ADAPTERS) {
                if (generation != adapterGeneration) throw new IllegalStateException("剪切变换扩展已改变，请重试");
            }
            if (blockTransformGeneration != HologramBlockTransforms.generation())
                throw new IllegalStateException("方块变换扩展已改变，请重试");
            int worked = 0;
            while (!done && cursor < cells.tagCount()
                && worked < maximumCells
                && (worked == 0 || System.nanoTime() < deadline)) {
                transformCell(cursor);
                cursor++;
                worked++;
            }
            if (!done && cursor == cells.tagCount()) {
                for (String name : reported) warnings.appendTag(new NBTTagString(name));
                if (relations.crossDimensionExternal())
                    warnings.appendTag(new NBTTagString("gtmb:external_dimension_relation"));
                result.setTag("cells", changed);
                if (!transform.identity() || warnings.tagCount() > 0) result.setTag("transformUnknown", warnings);
                if (targetDependent) result.setBoolean("transformTargetDependent", true);
                done = true;
            }
            return done;
        }

        NBTTagCompound result() {
            if (!done) throw new IllegalStateException("raw transform still running");
            synchronized (ADAPTERS) {
                if (generation != adapterGeneration) throw new IllegalStateException("剪切变换扩展已改变，请重试");
            }
            if (blockTransformGeneration != HologramBlockTransforms.generation())
                throw new IllegalStateException("方块变换扩展已改变，请重试");
            return result;
        }

        private void transformCell(int i) {
            NBTTagCompound source = cells.getCompoundTagAt(i), cell = (NBTTagCompound) source.copy();
            if (copyOnly) {
                changed.appendTag(cell);
                return;
            }
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
