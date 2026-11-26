// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Checks placed LittleTiles data before a native loader can silently discard unsupported geometry. */
final class HologramLittleTilesCapabilities {

    private HologramLittleTilesCapabilities() {}

    static final class Capabilities {

        final int shapeTypes, orientations, furtherShapes;
        final boolean inverted, disableConnection;

        Capabilities(int shapeTypes, int orientations, boolean inverted, int furtherShapes, boolean disableConnection) {
            this.shapeTypes = shapeTypes;
            this.orientations = orientations;
            this.inverted = inverted;
            this.furtherShapes = furtherShapes;
            this.disableConnection = disableConnection;
        }
    }

    /** Resolve only LittleTiles' named schema fields, once; never dispatch on a pack or mod version string. */
    private static final class RuntimeCapabilities {

        static final Capabilities VALUE;
        static final Throwable FAILURE;
        static {
            Capabilities value = null;
            Throwable failure = null;
            try {
                Class<?> cutout = Class.forName("com.creativemd.littletiles.common.utils.LittleTileCutoutInfo");
                Class<?> tile = Class.forName("com.creativemd.littletiles.common.utils.LittleTile");
                Class<?> shapes = Class.forName("com.creativemd.littletiles.common.utils.LittleTileShapeMode");
                Class<?> orientation = Class.forName("com.creativemd.littletiles.client.util3d.OrientationMapper");
                int further = hasField(cutout, "furtherShapes", List.class) ? cutout.getField("MAX_FURTHER_SHAPES")
                    .getInt(null) : 0;
                if (further < 0 || further > 16) throw new IllegalStateException("Unreviewed LittleTiles shape bound");
                value = new Capabilities(
                    shapes.getEnumConstants().length,
                    orientation.getField("NUM_ORIENTATIONS")
                        .getInt(null),
                    hasField(cutout, "inverted", boolean.class),
                    further,
                    hasField(tile, "disableConnection", boolean.class));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
                failure = invalid;
            }
            VALUE = value;
            FAILURE = failure;
        }
    }

    private static boolean hasField(Class<?> type, String name, Class<?> fieldType) {
        try {
            Field field = type.getField(name);
            return field.getType() == fieldType;
        } catch (NoSuchFieldException missing) {
            return false;
        }
    }

    static void validateHost(NBTTagCompound host) throws IOException {
        validateHost(host, null);
    }

    /** Explicit capabilities also allow deterministic old/new schema tests without replacing the running mod. */
    static void validateHost(NBTTagCompound host, Capabilities capabilities) throws IOException {
        if (host == null) return;
        numeric(host, "tilesCount");
        if (!host.hasKey("tilesCount", 99)) throw invalid("missing tile count");
        int count = host.getInteger("tilesCount");
        if (count < 0 || count > 65536) throw invalid("tile count");
        for (int i = 0; i < count; i++) {
            if (!host.hasKey("t" + i, 10)) throw invalid("missing child t" + i);
            NBTTagCompound tile = host.getCompoundTag("t" + i);
            numeric(tile, "disableConnection");
            if (tile.getBoolean("disableConnection") && !capabilities(capabilities).disableConnection)
                throw invalid("target cannot preserve disableConnection");
            if (tile.hasKey("cutoutType")) {
                validateCutout(tile, capabilities(capabilities), true);
            } else {
                for (Object key : tile.func_150296_c())
                    if (((String) key).startsWith("cutout")) throw invalid("cutout fields without cutoutType");
            }
        }
    }

    private static Capabilities capabilities(Capabilities explicit) throws IOException {
        if (explicit != null) return explicit;
        if (RuntimeCapabilities.VALUE == null)
            throw new IOException("LittleTiles schema API unavailable", RuntimeCapabilities.FAILURE);
        return RuntimeCapabilities.VALUE;
    }

    private static void validateCutout(NBTTagCompound tag, Capabilities capabilities, boolean root) throws IOException {
        for (String field : new String[] { "cutoutType", "cutoutOrientation", "cutoutFaceStart", "cutoutFaceEnd",
            "cutoutThickness", "cutoutNegX", "cutoutNegY", "cutoutNegZ", "cutoutInverted" }) numeric(tag, field);
        if (!tag.hasKey("cutoutType", 99)) throw invalid("condition without cutoutType");
        int type = tag.getInteger("cutoutType"), orientation = tag.getInteger("cutoutOrientation");
        // BOX is the no-cutout sentinel: both native mesh builders explicitly reject it as a cutout.
        if (type <= 0 || type >= capabilities.shapeTypes) throw invalid("cutout type " + type);
        if (orientation < 0 || orientation >= capabilities.orientations)
            throw invalid("cutout orientation " + orientation);
        for (String face : new String[] { "cutoutFaceStart", "cutoutFaceEnd" })
            if (tag.getInteger(face) < 0 || tag.getInteger(face) > 6) throw invalid("cutout face");
        for (String vector : new String[] { "cutoutSize", "cutoutPos" })
            for (String axis : new String[] { "X", "Y", "Z" }) numeric(tag, vector + axis);
        for (int i = 0; i < 8; i++)
            for (String axis : new String[] { "X", "Y", "Z" }) numeric(tag, "cutoutCorner" + i + axis);
        if (tag.getBoolean("cutoutInverted") && !capabilities.inverted)
            throw invalid("target cannot preserve inverted cutout");
        if (!tag.hasKey("cutoutFurtherShapes")) return;
        if (!tag.hasKey("cutoutFurtherShapes", 9)) throw invalid("furtherShapes is not a list");
        NBTTagList list = (NBTTagList) tag.getTag("cutoutFurtherShapes");
        if (list.tagCount() > 0 && list.func_150303_d() != 10) throw invalid("non-compound furtherShapes");
        if (list.tagCount() > (root ? capabilities.furtherShapes : 0))
            throw invalid(root ? "target furtherShapes bound exceeded" : "nested furtherShapes");
        for (int i = 0; i < list.tagCount(); i++) validateCutout(list.getCompoundTagAt(i), capabilities, false);
    }

    private static void numeric(NBTTagCompound tag, String field) throws IOException {
        if (tag.hasKey(field) && !tag.hasKey(field, 99)) throw invalid("non-numeric " + field);
    }

    private static IOException invalid(String reason) {
        return new IOException("Unsupported LittleTiles data: " + reason);
    }
}
