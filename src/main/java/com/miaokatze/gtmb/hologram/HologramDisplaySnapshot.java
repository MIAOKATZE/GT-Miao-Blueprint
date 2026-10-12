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

    private HologramDisplaySnapshot() {}

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
        if (nativeTile(tile)) {
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
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("color", -1);
        if (facing >= 0 && facing < 6) display.setInteger("facing", facing);
        int casing = casingIndex(element);
        if (casing >= 0) {
            display.setInteger("texturePage", casing >> 7);
            display.setInteger("textureIndex", casing & 127);
        }
        NBTTagCompound saved = null;
        if (HologramRecovery.hasSeal(stack)) saved = stack.getTagCompound()
            .getCompoundTag(HologramRecovery.TAG)
            .getCompoundTag("tile");
        else if (tileNbt != null && !tileNbt.isEmpty()) {
            try {
                saved = HologramBlueprintNbt.decode(tileNbt);
            } catch (java.io.IOException ignored) {}
        }
        if (saved != null) {
            // The planned tile has already passed the blueprint transform. Keep its native
            // geometry/material data together, rather than interpreting part NBT as a cube.
            if ((saved.getBoolean("gtmb.nativePreview") || saved.hasKey("parts", 9)
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
        return display;
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
        io.netty.buffer.ByteBuf bytes = io.netty.buffer.Unpooled.buffer(5);
        try {
            Object container = host.getClass()
                .getMethod("getCableBus")
                .invoke(host);
            Object cable = container.getClass()
                .getMethod("getPart", ForgeDirection.class)
                .invoke(container, ForgeDirection.UNKNOWN);
            if (!hasClass(cable, "appeng.parts.networking.PartCable")) return;
            cable.getClass()
                .getMethod("writeToStream", io.netty.buffer.ByteBuf.class)
                .invoke(cable, bytes);
            if (bytes.readableBytes() != 5) return;
            NBTTagCompound visual = new NBTTagCompound();
            visual.setInteger("connections", bytes.readUnsignedByte());
            visual.setInteger("channels", bytes.readInt());
            saved.setTag("gtmb.aeCableVisual", visual);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableVisual) {
            // Native persistence is still usable when an optional cable subtype has another packet format.
        } finally {
            bytes.release();
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
            || name.equals("appeng.block.networking.BlockCableBus");
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
