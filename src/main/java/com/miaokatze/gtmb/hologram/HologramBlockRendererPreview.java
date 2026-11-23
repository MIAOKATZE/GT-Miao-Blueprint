// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;

import cpw.mods.fml.common.Loader;
import gregtech.api.GregTechAPI;

/** Optional preview-world identity required by GTSR's Critical Machine. */
final class HologramBlockRendererPreview {

    static final int CONTROLLER_Y = 64;
    private static final String TRACKED_WORLD = "blockrenderer6343.client.world.TrackedDummyWorld";
    private static final String DUMMY_WORLD = "blockrenderer6343.client.world.DummyWorld";
    private static final String CRITICAL = "com.miaokatze.gtsr.common.machine.MTECriticalMachine";

    final World world;
    private final Collection<?> positions;
    private final Method hasChanged;

    static boolean required(Object context) {
        return context != null && CRITICAL.equals(context.getClass().getName());
    }

    HologramBlockRendererPreview(TileEntity controller) throws IOException {
        if (!Loader.isModLoaded("blockrenderer6343")) throw new IOException("LOCAL_CRITICAL_PREVIEW_REQUIRES_BLOCKRENDERER");
        try {
            Class<? extends World> tracked = Class.forName(TRACKED_WORLD, false, getClass().getClassLoader())
                .asSubclass(World.class);
            if (!DUMMY_WORLD.equals(tracked.getSuperclass().getName()))
                throw new IOException("LOCAL_CRITICAL_PREVIEW_WORLD_UNAVAILABLE");
            GregTechAPI.addDummyWorld(tracked);
            world = HologramResourceWorld.create(() -> create(tracked));
            Object blockMap = tracked.getField("blockMap").get(world);
            if (!(blockMap instanceof Map<?, ?> map))
                throw new IOException("LOCAL_CRITICAL_PREVIEW_WORLD_UNAVAILABLE");
            positions = map.keySet();
            hasChanged = tracked.getMethod("hasChanged");
            int x = controller.xCoord, y = controller.yCoord, z = controller.zCoord;
            world.setBlock(x, y, z, controller.getBlockType(), controller.getBlockMetadata(), 2);
            world.setTileEntity(x, y, z, controller);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError incompatible) {
            throw new IOException("LOCAL_CRITICAL_PREVIEW_WORLD_UNAVAILABLE", incompatible);
        }
    }

    private static World create(Class<? extends World> type) {
        try {
            return type.getConstructor().newInstance();
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    boolean takeChanged() throws IOException {
        if (positions.size() > HologramBlueprintLimits.MAX_CELLS)
            throw new IOException("LOCAL_NATIVE_PREVIEW_CELL_LIMIT");
        try {
            return (Boolean) hasChanged.invoke(world);
        } catch (ReflectiveOperationException incompatible) {
            throw new IOException("LOCAL_CRITICAL_PREVIEW_WORLD_UNAVAILABLE", incompatible);
        }
    }

    Iterator<HologramNativePreviewWorld.Placement> placements(TileEntity controller) throws IOException {
        if (positions.size() > HologramBlueprintLimits.MAX_CELLS)
            throw new IOException("LOCAL_NATIVE_PREVIEW_CELL_LIMIT");
        final long anchor = CoordinatePacker.pack(controller.xCoord, controller.yCoord, controller.zCoord);
        List<HologramNativePreviewWorld.Placement> snapshot = new ArrayList<>(positions.size());
        for (Object position : positions) {
            long key = (Long) position;
            if (key != anchor) snapshot.add(placement(key, controller.yCoord));
        }
        return snapshot.iterator();
    }

    private HologramNativePreviewWorld.Placement placement(long position, int controllerY) throws IOException {
        int x = CoordinatePacker.unpackX(position);
        int y = CoordinatePacker.unpackY(position);
        int z = CoordinatePacker.unpackZ(position);
        requirePortableHeight(y, controllerY);
        return new HologramNativePreviewWorld.Placement(
            x, y, z, world.getBlock(x, y, z), world.getBlockMetadata(x, y, z), world.getTileEntity(x, y, z));
    }

    static void requirePortableHeight(int y, int controllerY) throws IOException {
        // The tracked browser world records signed Y independently of the nominal Minecraft world height.
        long relativeY = (long) y - controllerY;
        if (Math.abs(relativeY) > 256L)
            throw new IOException("LOCAL_NATIVE_PREVIEW_OUT_OF_BOUNDS: relative Y " + relativeY);
    }
}
