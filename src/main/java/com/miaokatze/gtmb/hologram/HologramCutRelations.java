package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.CutTransforms;

/** Complete-selection relationship relocation, independent of direction adapters and identity transforms. */
final class HologramCutRelations {

    private HologramCutRelations() {}

    static Context context(NBTTagCompound original, CutTransforms.Transform pose, int ox, int oy, int oz) {
        return context(original, pose, ox, oy, oz, original.getInteger("dimension"));
    }

    static Context context(NBTTagCompound original, CutTransforms.Transform pose, int ox, int oy, int oz,
        int targetDimension) {
        return new Context(original, pose, ox, oy, oz, targetDimension);
    }

    static final class Context {

        private final Map<String, int[]> targets = new HashMap<>();
        private boolean dependent;
        private boolean crossDimensionExternal;

        Context(NBTTagCompound original, CutTransforms.Transform pose, int ox, int oy, int oz, int dimension) {
            // Native lookups use the current world. Complete internal targets move into the destination world;
            // external tuples cannot encode their original dimension and require a visible warning.
            int sx = original.getInteger("sizeX"), sy = original.getInteger("sizeY"), sz = original.getInteger("sizeZ");
            NBTTagList cells = original.getTagList("cells", 10);
            java.util.Set<String> referenced = new java.util.HashSet<>();
            for (int i = 0; i < cells.tagCount(); i++)
                for (HologramGtCoordinateData.Binding link : HologramGtCoordinateData.bindings(
                    cells.getCompoundTagAt(i)
                        .getCompoundTag("tile")))
                    referenced.add(position(link.x, link.y, link.z));
            if (referenced.isEmpty()) return;
            for (int i = 0; i < cells.tagCount(); i++) {
                NBTTagCompound cell = cells.getCompoundTagAt(i);
                int x = cell.getInteger("x"), y = cell.getInteger("y"), z = cell.getInteger("z");
                String source = position(
                    (long) original.getInteger("x") + x,
                    (long) original.getInteger("y") + y,
                    (long) original.getInteger("z") + z);
                if (!referenced.contains(source)) continue;
                int[] target = pose.point(x, y, z, sx, sy, sz);
                targets.put(
                    source,
                    new int[] { Math.addExact(ox, target[0]), Math.addExact(oy, target[1]),
                        Math.addExact(oz, target[2]) });
            }
            dependent = !targets.isEmpty();
            crossDimensionExternal = original.getInteger("dimension") != dimension
                && targets.size() < referenced.size();
        }

        boolean targetDependent() {
            return dependent;
        }

        boolean crossDimensionExternal() {
            return crossDimensionExternal;
        }

        void apply(NBTTagCompound sourceTile, NBTTagCompound transformedTile) throws IOException {
            if (sourceTile == null || transformedTile == null) return;
            for (HologramGtCoordinateData.Binding link : HologramGtCoordinateData.bindings(sourceTile)) {
                int[] target = targets.get(position(link.x, link.y, link.z));
                if (target != null) HologramGtCoordinateData
                    .write(transformedTile, link.adapter, link.slot, target[0], target[1], target[2]);
            }
        }
    }

    private static String position(long x, long y, long z) {
        return x + "," + y + "," + z;
    }
}
