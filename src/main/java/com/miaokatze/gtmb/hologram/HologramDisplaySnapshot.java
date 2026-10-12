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

/** Detached visual data only; no machine inventory, load hook, or shared prototype mutation. */
final class HologramDisplaySnapshot {

    private HologramDisplaySnapshot() {}

    static NBTTagCompound capture(TileEntity tile) {
        try {
            return captureChecked(tile);
        } catch (RuntimeException | LinkageError unavailableDisplay) {
            // A broken optional cover/display serializer cannot change structural construction authority.
            return new NBTTagCompound();
        }
    }

    private static NBTTagCompound captureChecked(TileEntity tile) {
        NBTTagCompound display = new NBTTagCompound();
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
