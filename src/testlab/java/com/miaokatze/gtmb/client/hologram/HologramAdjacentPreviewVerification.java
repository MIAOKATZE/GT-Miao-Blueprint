package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Checks real editor face emission for opaque floors beneath native cable/workbench/slope models. */
public final class HologramAdjacentPreviewVerification {

    private HologramAdjacentPreviewVerification() {}

    public static NBTTagCompound verify(NBTTagCompound snapshot, boolean current) throws ReflectiveOperationException {
        HologramState rendered = (HologramState) field(HologramRenderer.class, "previewState").get(null);
        require(
            rendered != null && rendered.data.equals(snapshot),
            "adjacency witness requires this actual editor snapshot");
        int renderedView = field(HologramRenderer.class, "visibleView").getInt(null);
        int renderedMode = field(HologramRenderer.class, "visibleMode").getInt(null);
        require(
            (renderedView == 1 || renderedMode == 2) == current,
            "adjacency witness requires the actual displayed target/current view");
        List<?> faces = (List<?>) field(HologramRenderer.class, "cachedFaces").get(null);
        HologramState state = new HologramState(snapshot);
        NBTTagList rows = new NBTTagList();
        int floors = 0;
        for (HologramState.Cell floor : state.cells) {
            Block block = floor.block(current || floor.anchor);
            if (block == null || !block.isOpaqueCube()
                || !block.renderAsNormalBlock()
                || HologramNativeTiles.supported(block)
                || floor.iconOnly) continue;
            for (HologramState.Cell above : state.cells) {
                if (above.x != floor.x || above.y != floor.y + 1 || above.z != floor.z) continue;
                Block nativeBlock = above.block(current || above.anchor);
                if (!HologramNativeTiles.supported(nativeBlock)) continue;
                int emitted = 0;
                double[][] top = null;
                for (Object face : faces) {
                    HologramState.Cell owner = (HologramState.Cell) field(face.getClass(), "cell").get(face);
                    if (owner.x != floor.x || owner.y != floor.y
                        || owner.z != floor.z
                        || field(face.getClass(), "nativeModel").getBoolean(face)
                        || field(face.getClass(), "empty").getBoolean(face)) continue;
                    int side = field(face.getClass(), "side").getInt(face);
                    emitted |= 1 << side;
                    if (side == 1) {
                        double[][] points = (double[][]) field(face.getClass(), "points").get(face);
                        top = new double[][] { points[3], points[7], points[6], points[2] };
                    }
                }
                require(
                    (emitted & 2) != 0,
                    "ordinary floor top was omitted below " + (current ? above.id : above.wantId)
                        + " at "
                        + floor.x
                        + ":"
                        + floor.y
                        + ":"
                        + floor.z);
                NBTTagCompound row = new NBTTagCompound();
                row.setString("floor", current ? floor.id : floor.wantId);
                row.setString("above", current ? above.id : above.wantId);
                row.setInteger("x", floor.x);
                row.setInteger("y", floor.y);
                row.setInteger("z", floor.z);
                row.setInteger("actualEmittedFaceMask", emitted);
                row.setTag("floorTopProjectedVertices", vectors(top));
                row.setTag(
                    "floorTopFramebufferVertices",
                    vectors(HologramSourcePreviewReference.framebufferPoints(top)));
                rows.appendTag(row);
                floors++;
            }
        }
        require(floors >= 2, "adjacency fixture must include at least two opaque floors below native models");
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("current", current);
        receipt.setInteger("nativeAdjacentFloors", floors);
        receipt.setTag("floors", rows);
        return receipt;
    }

    private static NBTTagList vectors(double[][] vectors) {
        NBTTagList result = new NBTTagList();
        for (double[] vector : vectors) {
            NBTTagList point = new NBTTagList();
            for (double value : vector) point.appendTag(new NBTTagDouble(value));
            result.appendTag(point);
        }
        return result;
    }

    private static Field field(Class<?> type, String name) {
        return ReflectionHelper.findField(type, name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
