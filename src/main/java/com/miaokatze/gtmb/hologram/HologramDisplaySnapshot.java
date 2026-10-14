package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.covers.CoverRegistry;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaPipeEntity;
import gregtech.api.util.HatchElementBuilder;
import gregtech.common.covers.Cover;

/** Detached native render payloads and GT visual data; no load/tick hook or shared prototype mutation. */
final class HologramDisplaySnapshot {

    static final String QUANTUM_SIDES = "gtmb.quantumSides";

    private HologramDisplaySnapshot() {}

    /** Six native ForgeDirection bits only; a similarly named compound is never presentation data. */
    static boolean validQuantumSides(NBTTagCompound saved) {
        return saved.hasKey(QUANTUM_SIDES, 3) && saved.getInteger(QUANTUM_SIDES) >= 0
            && saved.getInteger(QUANTUM_SIDES) <= 63;
    }

    static NBTTagCompound capture(TileEntity tile) {
        try {
            return captureChecked(tile);
        } catch (NativeCaptureFailure failedCapture) {
            throw failedCapture;
        } catch (RuntimeException | LinkageError unavailableDisplay) {
            // A broken optional cover/display serializer cannot change structural construction authority.
            return new NBTTagCompound();
        }
    }

    private static NBTTagCompound captureChecked(TileEntity tile) {
        NBTTagCompound display = new NBTTagCompound();
        if (HologramAeNativePreview.supports(tile)) {
            display.setTag("nativeTile", HologramAeNativePreview.capture(tile));
        } else if (nativeTile(tile)) {
            NBTTagCompound saved;
            try {
                saved = HologramNativeCapture.capture(tile);
            } catch (java.io.IOException failedCapture) {
                throw new NativeCaptureFailure(failedCapture);
            }
            display.setTag("nativeTile", saved.copy());
        }
        if (!(tile instanceof IGregTechTileEntity)) return display;
        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
        display.setInteger(
            "facing",
            gt.getFrontFacing()
                .ordinal());
        display.setInteger("color", gt.getColorization());
        display.setBoolean("active", gt.isActive());
        int redstone = 0;
        NBTTagList covers = new NBTTagList();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (gt.getOutputRedstoneSignal(side) > 0) redstone |= 1 << side.ordinal();
            Cover cover = gt.getCoverAtSide(side);
            if (cover == null || !cover.isValid()) continue;
            NBTTagCompound row = new NBTTagCompound();
            row.setByte("s", (byte) side.ordinal());
            CoverRegistry.writeCoverToNbt(cover, row);
            covers.appendTag(row);
        }
        display.setInteger("redstoneMask", redstone);
        if (covers.tagCount() > 0) display.setTag("gt.covers", covers);
        if (tile instanceof BaseMetaPipeEntity)
            display.setInteger("connections", ((BaseMetaPipeEntity) tile).getConnections() & 63);
        Object machine = gt.getMetaTileEntity();
        Integer page = integerField(machine, "texturePage", "mTexturePage");
        Integer index = integerField(machine, "textureIndex", "mTextureIndex");
        if (page != null) display.setInteger("texturePage", page);
        if (index != null) display.setInteger("textureIndex", index);
        return display;
    }

    static NBTTagCompound target(ItemStack stack, int facing, IStructureElement<?> element, String tileNbt,
        boolean retainCovers) {
        return target(null, stack, facing, element, tileNbt, retainCovers);
    }

    /** The primary AE part material does not identify its CBC host; only the server's confirmed target may do so. */
    static NBTTagCompound target(net.minecraft.block.Block declaredHost, ItemStack stack, int facing,
        IStructureElement<?> element, String tileNbt, boolean retainCovers) {
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("color", -1);
        if (facing >= 0 && facing < 6) display.setInteger("facing", facing);
        applyCasing(element, display);
        NBTTagCompound saved = null;
        if (HologramRecovery.hasSeal(stack)) saved = stack.getTagCompound()
            .getCompoundTag(HologramRecovery.TAG)
            .getCompoundTag("tile");
        else if (tileNbt != null && !tileNbt.isEmpty()) {
            try {
                saved = HologramBlueprintNbt.decode(tileNbt);
            } catch (java.io.IOException ignored) {}
        }
        net.minecraft.block.Block nativeBlock = stack == null ? null
            : net.minecraft.block.Block.getBlockFromItem(stack.getItem());
        boolean quantum = nativeBlock != null && nativeBlock.getClass()
            .getName()
            .equals("com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode");
        if (saved == null && quantum) saved = new NBTTagCompound();
        if (saved == null && HologramAeNativePreview.supports(nativeBlock)) saved = new NBTTagCompound();
        if (saved != null) {
            // The planned tile has already passed the blueprint transform. Keep its native
            // geometry/material data together, rather than interpreting part NBT as a cube.
            if (HologramAeNativePreview.supports(nativeBlock))
                display.setTag("nativeTile", HologramAeNativePreview.target(nativeBlock, saved));
            else if ((quantum || registeredCableBus(declaredHost)
                || saved.getBoolean("gtmb.nativePreview")
                || saved.hasKey("parts", 9)
                || saved.hasKey("tilesCount")
                || saved.hasKey("Shape")
                || saved.hasKey("shape")
                || saved.hasKey("cbMetadata")
                || nativeItem(stack))) display.setTag("nativeTile", saved.copy());
            for (String key : new String[] { "texturePage", "textureIndex" })
                if (saved.hasKey(key, 3)) display.setInteger(key, saved.getInteger(key));
            if (saved.hasKey("mColor")) display.setInteger("color", saved.getByte("mColor") - 1);
            if (saved.hasKey("mConnections")) display.setInteger("connections", saved.getByte("mConnections") & 63);
            if (saved.hasKey("mActive")) display.setBoolean("active", saved.getBoolean("mActive"));
            if (facing < 0 && saved.hasKey("mFacing")) display.setInteger("facing", saved.getShort("mFacing"));
            if (retainCovers && saved.hasKey("gt.covers", 9)) display.setTag(
                "gt.covers",
                saved.getTag("gt.covers")
                    .copy());
        }
        // The planned hatch belongs to this declaration even if an unformed source saved its default 0:0 skin.
        applyCasing(element, display);
        return display;
    }

    static void applyCasing(IStructureElement<?> element, NBTTagCompound display) {
        int casing = casingIndex(element);
        if (casing >= 0) {
            display.setInteger("texturePage", casing >> 7);
            display.setInteger("textureIndex", casing & 127);
        }
    }

    /** Material indices are native hatch persistence, so an unbuilt target retains its casing in a saved blueprint. */
    static String retainCasing(ItemStack stack, IStructureElement<?> element, String tileNbt)
        throws java.io.IOException {
        if (!(gregtech.common.blocks.ItemMachines
            .getMetaTileEntity(stack) instanceof gregtech.api.metatileentity.implementations.MTEHatch)
            || casingIndex(element) < 0) return tileNbt;
        NBTTagCompound saved = HologramBlueprintNbt.decode(tileNbt);
        if (saved == null) {
            saved = new NBTTagCompound();
            // BaseMetaTileEntity reads mID when restoring any native GT payload, including a newly planned hatch.
            saved.setShort("mID", (short) stack.getItemDamage());
        }
        applyCasing(element, saved);
        return HologramBlueprintNbt.encodeCaptured(saved);
    }

    private static boolean registeredCableBus(net.minecraft.block.Block block) {
        if (block == null || block.getClass() != appeng.block.networking.BlockCableBus.class) return false;
        String name = net.minecraft.block.Block.blockRegistry.getNameForObject(block);
        Class<?> factory = ((appeng.block.AEBaseTileBlock) block).getTileEntityClass();
        return name != null && net.minecraft.block.Block.getBlockFromName(name) == block
            && factory != null
            && appeng.tile.networking.TileCableBus.class.isAssignableFrom(factory);
    }

    private static boolean nativeItem(ItemStack stack) {
        if (stack == null) return false;
        String name = stack.getItem()
            .getClass()
            .getName();
        net.minecraft.block.Block block = net.minecraft.block.Block.getBlockFromItem(stack.getItem());
        return nativeClass(name) || block != null && nativeClass(
            block.getClass()
                .getName());
    }

    private static final class NativeCaptureFailure extends RuntimeException {

        NativeCaptureFailure(java.io.IOException cause) {
            super("Native display capture failed without modifying the source", cause);
        }
    }

    /** AE cable topology is a visual packet, not persistent grid NBT. Preserve its native visual payload. */
    static void enrichNativeTile(TileEntity tile, NBTTagCompound saved) {
        if (nativeTile(tile)) saved.setBoolean("gtmb.nativePreview", true);
        if (tile != null && HologramNetworkBindings.NODE_TILE.equals(
            tile.getClass()
                .getName())) {
            try {
                Field sides = tile.getClass()
                    .getDeclaredField(
                        tile.getWorldObj() != null && tile.getWorldObj().isRemote ? "clientConnectedSides"
                            : "lastSyncedSides");
                sides.setAccessible(true);
                if (sides.getType() != int.class)
                    throw new IllegalArgumentException("GTSWN quantum visual field type changed");
                saved.setInteger(QUANTUM_SIDES, sides.getInt(tile) & 63);
                if (saved.hasKey("partContainer", 10)) enrichAeCable(tile, saved.getCompoundTag("partContainer"));
            } catch (ReflectiveOperationException unavailableVisual) {
                throw new IllegalArgumentException("GTSWN quantum visual fields unavailable", unavailableVisual);
            }
        }
        if (hasClass(tile, "appeng.tile.networking.TileCableBus")) enrichAeCable(tile, saved);
        if (hasClass(tile, "codechicken.multipart.TileMultipart")) {
            try {
                java.util.List<?> parts = (java.util.List<?>) tile.getClass()
                    .getMethod("jPartList")
                    .invoke(tile);
                NBTTagList rows = saved.getTagList("parts", 10);
                for (int i = 0; i < parts.size() && i < rows.tagCount(); i++)
                    if (hasClass(parts.get(i), "appeng.fmp.CableBusPart"))
                        enrichAeCable(parts.get(i), rows.getCompoundTagAt(i));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableVisual) {}
        }
    }

    private static void enrichAeCable(Object host, NBTTagCompound saved) {
        try {
            Object container = host.getClass()
                .getMethod(
                    hasClass(host, "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode") ? "getPartContainer"
                        : "getCableBus")
                .invoke(host);
            if (!(container instanceof appeng.parts.CableBusContainer)) return;
            NBTTagCompound visual = HologramAeNativePreview.cableVisual((appeng.parts.CableBusContainer) container);
            if (visual != null) saved.setTag("gtmb.aeCableVisual", visual);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableVisual) {
            // Native persistence remains usable when an optional host has another visual schema.
        }
    }

    private static boolean hasClass(Object instance, String name) {
        if (instance == null) return false;
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) if (type.getName()
            .equals(name)) return true;
        return false;
    }

    private static boolean nativeTile(TileEntity tile) {
        if (tile == null) return false;
        if (HologramNativeDisplayRegistry.supports(tile)) return true;
        for (Class<?> type = tile.getClass(); type != null; type = type.getSuperclass())
            if (nativeClass(type.getName())) return true;
        return false;
    }

    private static boolean nativeClass(String name) {
        return name.startsWith("com.carpentersblocks.") || name.startsWith("codechicken.multipart.")
            || name.startsWith("codechicken.microblock.")
            || name.startsWith("com.creativemd.littletiles.")
            || name.startsWith("gcewing.architecture.")
            || name.startsWith("crazypants.enderio.conduit.")
            || name.startsWith("appeng.tile.networking.")
            || name.equals("appeng.block.networking.BlockCableBus")
            || name.equals("com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode");
    }

    private static Integer integerField(Object value, String... names) {
        if (value == null) return null;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) for (String name : names) {
            try {
                Field field = type.getDeclaredField(name);
                if (field.getType() != int.class && field.getType() != byte.class) continue;
                field.setAccessible(true);
                return ((Number) field.get(value)).intValue();
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        return null;
    }

    private static int casingIndex(IStructureElement<?> element) {
        for (IStructureElement<?> part : HologramElementCatalog.parts(element)) {
            Method factory = part.getClass()
                .getEnclosingMethod();
            if (factory == null || factory.getDeclaringClass() != HatchElementBuilder.class
                || !"build".equals(factory.getName())) continue;
            try {
                Field enclosing = part.getClass()
                    .getDeclaredField("this$0");
                if (enclosing.getType() != HatchElementBuilder.class) continue;
                enclosing.setAccessible(true);
                return ((HatchElementBuilder<?>) enclosing.get(part)).getCasingIndex();
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        return -1;
    }
}
