// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Independent LittleTiles schema and native cutout contracts; no FML world or GUI is launched. */
public final class HologramLittleTilesCompatibilityRegression {

    private static final HologramLittleTilesCapabilities.Capabilities OLD = new HologramLittleTilesCapabilities.Capabilities(
        13,
        48,
        false,
        0,
        false);
    private static final HologramLittleTilesCapabilities.Capabilities MODERN = new HologramLittleTilesCapabilities.Capabilities(
        15,
        48,
        true,
        16,
        true);

    private HologramLittleTilesCompatibilityRegression() {}

    public static void main(String[] args) {
        int status = 0;
        try {
            if (!MinecraftTestBootstrap.enter(HologramLittleTilesCompatibilityRegression.class, args)) return;
            schemas();
            nativeTransforms();
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            status = 1;
        }
        System.exit(status);
    }

    private static void schemas() throws Exception {
        NBTTagCompound plain = host(null);
        check(plain, OLD, true);
        NBTTagCompound missing = host(null);
        missing.removeTag("tilesCount");
        check(missing, MODERN, false);
        for (int count : new int[] { -1, 65537 }) {
            NBTTagCompound invalid = host(null);
            invalid.setInteger("tilesCount", count);
            check(invalid, MODERN, false);
        }
        for (int type = 0; type < 15; type++) {
            check(host(shape(type, 0)), OLD, type > 0 && type < 13);
            check(host(shape(type, 47)), MODERN, type > 0);
        }
        for (int type : new int[] { -1, 15, 127, 256 }) {
            NBTTagCompound shape = shape(1, 0);
            shape.setInteger("cutoutType", type);
            check(host(shape), MODERN, false);
        }
        for (int orientation : new int[] { -1, 48, 256 }) {
            NBTTagCompound shape = shape(1, 0);
            shape.setInteger("cutoutOrientation", orientation);
            check(host(shape), MODERN, false);
        }
        for (String face : new String[] { "cutoutFaceStart", "cutoutFaceEnd" }) {
            NBTTagCompound shape = shape(1, 0);
            shape.setByte(face, (byte) 7);
            check(host(shape), MODERN, false);
        }
        NBTTagCompound inverted = shape(1, 0);
        inverted.setBoolean("cutoutInverted", true);
        check(host(inverted), OLD, false);
        check(host(inverted), MODERN, true);
        inverted.setBoolean("cutoutInverted", false);
        check(host(inverted), OLD, true);
        for (int count : new int[] { 0, 16, 17 }) {
            NBTTagCompound compound = compound(count);
            check(host(compound), MODERN, count <= 16);
            check(host(compound), OLD, count == 0);
        }
        NBTTagCompound nested = compound(1);
        nested.getTagList("cutoutFurtherShapes", 10)
            .getCompoundTagAt(0)
            .setTag("cutoutFurtherShapes", compound(1).getTag("cutoutFurtherShapes"));
        check(host(nested), MODERN, false);
        for (int invalid = 0; invalid < 4; invalid++) {
            NBTTagCompound compound = compound(1);
            NBTTagCompound child = compound.getTagList("cutoutFurtherShapes", 10)
                .getCompoundTagAt(0);
            if (invalid == 0) child.removeTag("cutoutType");
            if (invalid == 1) child.setByte("cutoutType", (byte) 15);
            if (invalid == 2) child.setByte("cutoutOrientation", (byte) 48);
            if (invalid == 3) child.setString("cutoutPosX", "bad");
            check(host(compound), MODERN, false);
        }
        NBTTagCompound wrongList = shape(1, 0);
        wrongList.setString("cutoutFurtherShapes", "bad");
        check(host(wrongList), MODERN, false);
        NBTTagList strings = new NBTTagList();
        strings.appendTag(new NBTTagString("bad"));
        wrongList.setTag("cutoutFurtherShapes", strings);
        check(host(wrongList), MODERN, false);
        NBTTagCompound orphan = shape(1, 0);
        orphan.removeTag("cutoutType");
        check(host(orphan), MODERN, false);
        NBTTagCompound connection = host(null);
        connection.getCompoundTag("t0")
            .setBoolean("disableConnection", true);
        check(connection, OLD, false);
        check(connection, MODERN, true);
        connection.getCompoundTag("t0")
            .setBoolean("disableConnection", false);
        check(connection, OLD, true);
        System.out.println(
            "LittleTiles schema PASS: .63/.67 capabilities, 0/16/17 conditions, invalid type/basis/list/nesting, source unchanged");
    }

    private static void check(NBTTagCompound host, HologramLittleTilesCapabilities.Capabilities capabilities,
        boolean valid) throws IOException {
        NBTTagCompound before = (NBTTagCompound) host.copy();
        boolean accepted = true;
        try {
            HologramLittleTilesCapabilities.validateHost(host, capabilities);
        } catch (IOException rejected) {
            accepted = false;
        }
        SimpleAssert.eq(valid, accepted, "target schema acceptance");
        SimpleAssert.eq(before, host, "preflight preserves source on success and failure");
    }

    private static void nativeTransforms() throws Exception {
        Class<?> cutout = Class.forName("com.creativemd.littletiles.common.utils.LittleTileCutoutInfo");
        int types = Class.forName("com.creativemd.littletiles.common.utils.LittleTileShapeMode")
            .getEnumConstants().length;
        boolean modern;
        try {
            cutout.getField("furtherShapes");
            modern = true;
        } catch (NoSuchFieldException old) {
            modern = false;
        }
        int cases = 0;
        for (boolean vertical : new boolean[] { false, true })
            for (int type = 1; type < types; type++) for (int basis = 0; basis < 48; basis++) {
                NBTTagCompound shape = modern ? compound(2) : shape(type, basis);
                shape.setByte("cutoutType", (byte) type);
                shape.setByte("cutoutOrientation", (byte) basis);
                if (modern) shape.setBoolean("cutoutInverted", true);
                NBTTagCompound original = host(shape);
                original.getCompoundTag("t0")
                    .setBoolean("disableConnection", modern);
                HologramBlockTransforms.Data data = new HologramBlockTransforms.Data(null, "", 0, original, null);
                for (int turn = 0; turn < 4; turn++) {
                    NBTTagCompound previous = (NBTTagCompound) data.tile.getCompoundTag("t0")
                        .copy();
                    HologramBlockTransformLittleTiles.turn(data, vertical);
                    NBTTagCompound next = data.tile.getCompoundTag("t0");
                    geometry(previous, next, vertical);
                    nativeRoundTrip(cutout, next, modern ? 3 : 1);
                    SimpleAssert.eq(modern, next.getBoolean("cutoutInverted"), "root inversion survives");
                    SimpleAssert.eq(modern, next.getBoolean("disableConnection"), "connection configuration survives");
                }
                SimpleAssert
                    .eq(original, data.tile, "four turns restore exact compound NBT for both axes and all bases");
                SimpleAssert.eq(original, hostWithConnection(shape, modern), "detached transform preserves source");
                cases++;
            }
        NBTTagCompound invalid = host(compound(17));
        HologramBlockTransforms.Data rejected = new HologramBlockTransforms.Data(null, "", 0, invalid, null);
        try {
            HologramBlockTransformLittleTiles.turn(rejected, false);
            throw new AssertionError("invalid cutout must not become an ordinary box");
        } catch (IOException expected) {
            SimpleAssert.eq(invalid, rejected.tile, "failed transform preserves detached source");
        }
        // An old target must reject modern data before its loader silently drops these fields.
        NBTTagCompound targetProbe = host(compound(1));
        boolean supported = true;
        try {
            HologramLittleTilesCapabilities.validateHost(targetProbe);
        } catch (IOException unsupported) {
            supported = false;
        }
        SimpleAssert.eq(modern, supported, "runtime capability resolution matches installed LittleTiles schema");
        NBTTagCompound connection = host(null);
        connection.getCompoundTag("t0")
            .setBoolean("disableConnection", true);
        runtimeAcceptance(connection, modern);
        NBTTagCompound inverted = shape(1, 0);
        inverted.setBoolean("cutoutInverted", true);
        runtimeAcceptance(host(inverted), modern);
        nativeConnection(modern);
        System.out.println(
            "LittleTiles native PASS: " + cases
                + " all-shape/all-basis cases, both axes, four turns, native load/write; compound="
                + modern);
        if (!modern) System.out
            .println("LittleTiles modern compound native transforms NOT_RUN on old target; old target rejection PASS");
    }

    private static void runtimeAcceptance(NBTTagCompound host, boolean expected) throws IOException {
        boolean supported = true;
        try {
            HologramLittleTilesCapabilities.validateHost(host);
        } catch (IOException unsupported) {
            supported = false;
        }
        SimpleAssert.eq(expected, supported, "individual runtime capability gate");
    }

    private static void nativeConnection(boolean modern) throws Exception {
        Class<?> tile = Class.forName("com.creativemd.littletiles.common.utils.LittleTile");
        Class<?> block = Class.forName("com.creativemd.littletiles.common.utils.LittleTileBlock");
        tile.getMethod("registerLittleTile", Class.class, String.class)
            .invoke(null, block, "BlockTileBlock");
        for (boolean disabled : new boolean[] { false, true }) {
            NBTTagCompound input = host(null).getCompoundTag("t0");
            input.setBoolean("disableConnection", disabled);
            Object nativeTile = block.getConstructor()
                .newInstance();
            tile.getMethod("loadTileCore", NBTTagCompound.class)
                .invoke(nativeTile, input);
            NBTTagCompound saved = new NBTTagCompound();
            tile.getMethod("saveTileCore", NBTTagCompound.class)
                .invoke(nativeTile, saved);
            SimpleAssert.eq(
                modern && disabled,
                saved.getBoolean("disableConnection"),
                "native placed configuration serialization (old loader loses unsupported true)");
        }
    }

    private static NBTTagCompound hostWithConnection(NBTTagCompound shape, boolean connection) {
        NBTTagCompound host = host(shape);
        host.getCompoundTag("t0")
            .setBoolean("disableConnection", connection);
        return host;
    }

    private static void nativeRoundTrip(Class<?> cutout, NBTTagCompound shape, int conditions) throws Exception {
        Object loaded = cutout.getMethod("loadFromNBT", NBTTagCompound.class)
            .invoke(null, shape);
        SimpleAssert.that(loaded != null, "native loader retains cutout, not an ordinary box");
        NBTTagCompound saved = new NBTTagCompound();
        cutout.getMethod("writeToNBT", NBTTagCompound.class)
            .invoke(loaded, saved);
        SimpleAssert.eq(shape.getByte("cutoutType"), saved.getByte("cutoutType"), "native shape type retained");
        SimpleAssert
            .eq(shape.getByte("cutoutOrientation"), saved.getByte("cutoutOrientation"), "native basis retained");
        SimpleAssert
            .eq(shape.getBoolean("cutoutInverted"), saved.getBoolean("cutoutInverted"), "native inversion retained");
        SimpleAssert.eq(
            conditions - 1,
            saved.getTagList("cutoutFurtherShapes", 10)
                .tagCount(),
            "native condition count retained");
        if (conditions > 1) {
            List<?> loadedConditions = (List<?>) cutout.getMethod("conditions")
                .invoke(loaded);
            SimpleAssert.eq(conditions, loadedConditions.size(), "native absolute condition expansion");
            for (int i = 1; i < conditions; i++) {
                Object child = loadedConditions.get(i);
                NBTTagCompound absolute = new NBTTagCompound();
                cutout.getMethod("writeToNBT", NBTTagCompound.class)
                    .invoke(child, absolute);
                NBTTagCompound relative = shape.getTagList("cutoutFurtherShapes", 10)
                    .getCompoundTagAt(i - 1);
                for (String axis : new String[] { "X", "Y", "Z" }) SimpleAssert.eq(
                    shape.getInteger("cutoutPos" + axis) + relative.getInteger("cutoutPos" + axis),
                    absolute.getInteger("cutoutPos" + axis),
                    "native relative condition position");
                SimpleAssert.eq(
                    relative.getBoolean("cutoutInverted"),
                    absolute.getBoolean("cutoutInverted"),
                    "child inversion survives native load");
            }
        }
    }

    private static void geometry(NBTTagCompound before, NBTTagCompound after, boolean vertical) throws Exception {
        conditionGeometry(before, before, after, after, vertical);
        NBTTagList old = before.getTagList("cutoutFurtherShapes", 10),
            next = after.getTagList("cutoutFurtherShapes", 10);
        for (int i = 0; i < old.tagCount(); i++)
            conditionGeometry(before, old.getCompoundTagAt(i), after, next.getCompoundTagAt(i), vertical);
    }

    private static void conditionGeometry(NBTTagCompound oldTile, NBTTagCompound oldShape, NBTTagCompound newTile,
        NBTTagCompound newShape, boolean vertical) throws Exception {
        int[] min = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };
        int[] max = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        String[] axes = { "X", "Y", "Z" };
        for (int corner = 0; corner < 8; corner++) {
            int[] p = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                p[axis] = oldTile.getInteger("bBox0min" + axes[axis]) + oldShape.getInteger("cutoutPos" + axes[axis]);
                if (oldTile != oldShape) p[axis] += oldTile.getInteger("cutoutPos" + axes[axis]);
                if ((corner & (1 << axis)) != 0) p[axis] += oldShape.getInteger("cutoutSize" + axes[axis]);
            }
            int[] rotated = vertical ? new int[] { p[0], 16 - p[2], p[1] } : new int[] { 16 - p[2], p[1], p[0] };
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], rotated[axis]);
                max[axis] = Math.max(max[axis], rotated[axis]);
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            int absolute = newTile.getInteger("bBox0min" + axes[axis]) + newShape.getInteger("cutoutPos" + axes[axis]);
            if (newTile != newShape) absolute += newTile.getInteger("cutoutPos" + axes[axis]);
            SimpleAssert.eq(min[axis], absolute, "absolute condition lower corner rotates with tile");
            SimpleAssert
                .eq(max[axis] - min[axis], newShape.getInteger("cutoutSize" + axes[axis]), "condition extents rotate");
        }
        Class<?> mapper = Class.forName("com.creativemd.littletiles.client.util3d.OrientationMapper");
        Object oldMatrix = mapper.getMethod("fromId", int.class)
            .invoke(null, (int) oldShape.getByte("cutoutOrientation"));
        Object newMatrix = mapper.getMethod("fromId", int.class)
            .invoke(null, (int) newShape.getByte("cutoutOrientation"));
        for (int column = 0; column < 3; column++) {
            int[] old = new int[3], next = new int[3];
            for (int row = 0; row < 3; row++) {
                old[row] = Math.round(
                    oldMatrix.getClass()
                        .getField("m" + column + row)
                        .getFloat(oldMatrix));
                next[row] = Math.round(
                    newMatrix.getClass()
                        .getField("m" + column + row)
                        .getFloat(newMatrix));
            }
            int[] expected = vertical ? new int[] { old[0], -old[2], old[1] } : new int[] { -old[2], old[1], old[0] };
            SimpleAssert.that(Arrays.equals(expected, next), "each native condition basis rotates");
        }
    }

    private static NBTTagCompound compound(int count) {
        NBTTagCompound root = shape(1, 0);
        NBTTagList further = new NBTTagList();
        for (int i = 0; i < count; i++) {
            NBTTagCompound child = shape(i % 2 == 0 ? 2 : 12, (i + 9) % 48);
            child.setInteger("cutoutPosX", -3 + i);
            child.setInteger("cutoutPosY", 5 - i);
            child.setInteger("cutoutPosZ", 7 + i);
            child.setBoolean("cutoutInverted", i % 2 == 0);
            further.appendTag(child);
        }
        root.setTag("cutoutFurtherShapes", further);
        return root;
    }

    private static NBTTagCompound shape(int type, int orientation) {
        NBTTagCompound shape = new NBTTagCompound();
        shape.setByte("cutoutType", (byte) type);
        shape.setByte("cutoutOrientation", (byte) orientation);
        shape.setInteger("cutoutSizeX", 3);
        shape.setInteger("cutoutSizeY", 12);
        shape.setInteger("cutoutSizeZ", 5);
        shape.setInteger("cutoutPosX", 2);
        shape.setInteger("cutoutPosY", -4);
        shape.setInteger("cutoutPosZ", 1);
        shape.setByte("cutoutFaceStart", (byte) 2);
        shape.setByte("cutoutFaceEnd", (byte) 5);
        shape.setByte("cutoutThickness", (byte) 2);
        shape.setBoolean("cutoutNegX", true);
        shape.setBoolean("cutoutNegY", false);
        shape.setBoolean("cutoutNegZ", true);
        for (int corner = 0; corner < 8; corner++) for (int axis = 0; axis < 3; axis++) shape.setInteger(
            "cutoutCorner" + corner + new String[] { "X", "Y", "Z" }[axis],
            (corner & (1 << axis)) != 0 ? 3 + axis : -axis);
        return shape;
    }

    private static NBTTagCompound host(NBTTagCompound shape) {
        NBTTagCompound host = new NBTTagCompound();
        host.setString("id", "LittleTilesTileEntity");
        host.setInteger("tilesCount", 1);
        NBTTagCompound tile = shape == null ? new NBTTagCompound() : (NBTTagCompound) shape.copy();
        tile.setInteger("bSize", 1);
        int[] box = { 1, 2, 3, 9, 8, 14 };
        String[] fields = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
        for (int i = 0; i < 6; i++) tile.setInteger("bBox0" + fields[i], box[i]);
        tile.setInteger("cVecx", 1);
        tile.setInteger("cVecy", 2);
        tile.setInteger("cVecz", 3);
        host.setTag("t0", tile);
        return host;
    }
}
