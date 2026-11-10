package com.miaokatze.gtmb.client.hologram;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;

/** Checks EIO's native, axis-dependent bundle layout without treating the bundle as a rigid mesh. */
final class HologramEnderIoPreviewSmoke {

    private static final String BUNDLE = "crazypants.enderio.conduit.BlockConduitBundle";

    private HologramEnderIoPreviewSmoke() {}

    static HologramState rigidWitness(HologramState state) {
        NBTTagCompound data = (NBTTagCompound) state.fullData();
        NBTTagList cells = data.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            if (!state.cells.get(i)
                .block(false)
                .getClass()
                .getName()
                .equals(BUNDLE)) continue;
            for (String name : new String[] { "actualDisplay", "targetDisplay" }) {
                NBTTagCompound tile = cells.getCompoundTagAt(i)
                    .getCompoundTag(name)
                    .getCompoundTag("nativeTile");
                NBTTagList parts = tile.getTagList("conduits", 10), single = new NBTTagList();
                require(parts.tagCount() == 2, "multi-conduit witness must retain item and power in the display scene");
                require(
                    parts.getCompoundTagAt(0)
                        .getString("conduitType")
                        .endsWith(".ItemConduit"),
                    "rigid witness must use the real item conduit");
                single.appendTag(
                    parts.getCompoundTagAt(0)
                        .copy());
                tile.setTag("conduits", single);
            }
        }
        return new HologramState(data);
    }

    static void verifyTurn(HologramState before, HologramState after, boolean current, boolean vertical) {
        try {
            Map<String, Object> oldParts = parts(before, current), newParts = parts(after, current);
            require(
                oldParts.size() == 2 && oldParts.keySet()
                    .equals(newParts.keySet()),
                "native rotation preserves both real conduit types");
            for (String type : oldParts.keySet()) {
                Set<ForgeDirection> expected = new HashSet<>();
                for (ForgeDirection direction : directions(oldParts.get(type))) {
                    int x = direction.offsetX, y = direction.offsetY, z = direction.offsetZ;
                    int nx = vertical ? x : -z, ny = vertical ? -z : y, nz = vertical ? y : x;
                    for (ForgeDirection candidate : ForgeDirection.VALID_DIRECTIONS)
                        if (candidate.offsetX == nx && candidate.offsetY == ny && candidate.offsetZ == nz)
                            expected.add(candidate);
                }
                require(
                    expected.equals(directions(newParts.get(type))),
                    type + " native reader did not rotate arm topology with the placement coordinate transform");
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native EIO topology API unavailable", failure);
        }
    }

    /** Native collision arms and emitted shell corners must both follow the source mod's fixed offset formula. */
    static void verify(HologramState state, HologramState.Cell cell, TileEntity tile, boolean current) {
        if (!cell.block(current)
            .getClass()
            .getName()
            .equals(BUNDLE)) return;
        try {
            Collection<?> conduits = (Collection<?>) tile.getClass()
                .getMethod("getConduits")
                .invoke(tile);
            require(conduits.size() == 2, "display scene must render both real conduits");
            double width = Class.forName("crazypants.enderio.conduit.geom.ConduitGeometryUtil")
                .getField("WIDTH")
                .getFloat(null);
            Class<?> componentClass = Class.forName("crazypants.enderio.conduit.geom.CollidableComponent");
            Set<ForgeDirection> allArms = new HashSet<>();
            for (Object conduit : conduits) {
                String type = conduit.getClass()
                    .getName();
                boolean item = type.endsWith(".ItemConduit"), power = type.endsWith(".PowerConduit");
                require(item || power, "fixed-layout witness uses audited item and power conduits");
                Set<ForgeDirection> connected = directions(conduit);
                require(connected.size() == 2, type + " must retain its asymmetric two-arm connection");
                Collection<?> components = (Collection<?>) conduit.getClass()
                    .getMethod("getCollidableComponents")
                    .invoke(conduit);
                Set<ForgeDirection> seen = new HashSet<>();
                for (Object component : components) {
                    Object data = componentClass.getField("data")
                        .get(component);
                    if (data != null) {
                        // PowerConduit adds its connection-mode controller to the collision list,
                        // even for an internal arm; its renderer draws it only for external ports.
                        Object controller = Class.forName("crazypants.enderio.conduit.power.IPowerConduit")
                            .getField("COLOR_CONTROLLER_ID")
                            .get(null);
                        require(power && controller.equals(data), "unexpected extra conduit component");
                        continue;
                    }
                    ForgeDirection direction = (ForgeDirection) componentClass.getField("dir")
                        .get(component);
                    if (direction == ForgeDirection.UNKNOWN) continue;
                    require(connected.contains(direction), "native collision component contains an unexpected arm");
                    seen.add(direction);
                    allArms.add(direction);
                    double[] expected = armBounds(direction, width, item);
                    Object bounds = componentClass.getField("bound")
                        .get(component);
                    String[] fields = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
                    for (int i = 0; i < fields.length; i++) require(
                        Math.abs(
                            bounds.getClass()
                                .getField(fields[i])
                                .getDouble(bounds) - expected[i])
                            < .000002,
                        type + " native collision bounds violate fixed type/axis offset at " + direction);
                    // DefaultConduitRenderer scales only the transverse shell dimensions by .75.
                    for (int axis = 0; axis < 3; axis++) if (axis != axis(direction)) {
                        double center = (expected[axis] + expected[axis + 3]) / 2;
                        double half = (expected[axis + 3] - expected[axis]) * .375;
                        expected[axis] = center - half;
                        expected[axis + 3] = center + half;
                    }
                    for (int corner = 0; corner < 8; corner++) {
                        double[] point = new double[3];
                        for (int axis = 0; axis < 3; axis++) point[axis] = expected[axis + ((corner >> axis & 1) * 3)];
                        require(
                            contains(HologramBlockRender.positions(cell, current), point),
                            type + " emitted shell omits native " + direction + " endpoint corner");
                    }
                    IIcon icon = (IIcon) conduit.getClass()
                        .getMethod("getTextureForState", componentClass)
                        .invoke(conduit, component);
                    require(HologramBlockRender.usesIcon(cell, current, icon), type + " native arm material is absent");
                }
                require(seen.equals(connected), type + " native collision topology must include every arm");
            }
            // Every emitted boundary vertex must end on one of the native connected block faces.
            for (double[] point : HologramBlockRender.positions(cell, current))
                for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                    double end = side.offsetX + side.offsetY + side.offsetZ < 0 ? 0 : 1;
                    if (Math.abs(point[axis(side)] - end) < .000002)
                        require(allArms.contains(side), "native mesh reaches an unconnected block face " + side);
                }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native EIO geometry/material API unavailable", failure);
        }
    }

    private static double[] armBounds(ForgeDirection direction, double width, boolean item) {
        double min = .5 - width / 2, max = .5 + width / 2;
        double[] result = { min, min, min, max, max, max };
        int axis = axis(direction);
        if (direction.offsetX + direction.offsetY + direction.offsetZ < 0) {
            result[axis] = 0;
            result[axis + 3] = min;
        } else {
            result[axis] = max;
            result[axis + 3] = 1;
        }
        // Offsets.java: item X-axis SOUTH, Y/Z-axis EAST; power Y-axis SOUTH, X/Z-axis DOWN.
        int offsetAxis = item ? axis == 0 ? 2 : 0 : axis == 1 ? 2 : 1;
        double offset = item || axis == 1 ? width : -width;
        result[offsetAxis] = Math.max(0, Math.min(1, result[offsetAxis] + offset));
        result[offsetAxis + 3] = Math.max(0, Math.min(1, result[offsetAxis + 3] + offset));
        return result;
    }

    private static int axis(ForgeDirection direction) {
        return direction.offsetX != 0 ? 0 : direction.offsetY != 0 ? 1 : 2;
    }

    private static boolean contains(Collection<double[]> points, double[] expected) {
        for (double[] point : points)
            if (Math.abs(point[0] - expected[0]) < .002 && Math.abs(point[1] - expected[1]) < .002
                && Math.abs(point[2] - expected[2]) < .002) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Set<ForgeDirection> directions(Object conduit) throws ReflectiveOperationException {
        return new HashSet<>(
            (Collection<ForgeDirection>) conduit.getClass()
                .getMethod("getConduitConnections")
                .invoke(conduit));
    }

    private static Map<String, Object> parts(HologramState state, boolean current) throws ReflectiveOperationException {
        HologramBlockAccess access = new HologramBlockAccess(state, current);
        Map<String, Object> result = new HashMap<>();
        for (HologramState.Cell cell : state.cells) {
            if (!cell.block(current)
                .getClass()
                .getName()
                .equals(BUNDLE)) continue;
            TileEntity tile = access.getTileEntity(cell.x, cell.y, cell.z);
            require(tile != null, "native EIO topology witness needs its detached tile");
            for (Object conduit : (Collection<?>) tile.getClass()
                .getMethod("getConduits")
                .invoke(tile))
                result.put(
                    conduit.getClass()
                        .getName(),
                    conduit);
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
