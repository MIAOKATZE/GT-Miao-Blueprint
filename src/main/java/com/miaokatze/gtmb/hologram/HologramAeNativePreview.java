package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.util.AEColor;
import appeng.block.AEBaseTileBlock;
import appeng.block.misc.BlockInterface;
import appeng.block.storage.BlockDrive;
import appeng.tile.misc.TileInterface;
import appeng.tile.storage.TileDrive;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Bounded visual projection only. Never deserializes an inventory, instantiates a cell handler or joins a grid. */
public final class HologramAeNativePreview {

    public static final String FRAME = "gtmb.aeStaticVisual";
    private static final int VERSION = 1;

    private HologramAeNativePreview() {}

    public static boolean supports(Block block) {
        if (!(block instanceof AEBaseTileBlock)) return false;
        Class<?> host = ((AEBaseTileBlock) block).getTileEntityClass();
        return block.getClass() == BlockDrive.class && host == TileDrive.class
            || block.getClass() == BlockInterface.class && host == TileInterface.class;
    }

    static boolean supports(TileEntity tile) {
        return tile != null && (tile.getClass() == TileDrive.class || tile.getClass() == TileInterface.class);
    }

    /**
     * Live read is restricted to the official Drive's 49-byte visual writer; Interface's network writer is forbidden.
     */
    public static NBTTagCompound capture(TileEntity tile) {
        if (!supports(tile)) throw new IllegalArgumentException("Unreviewed AE preview host");
        boolean drive = tile.getClass() == TileDrive.class;
        NBTTagCompound frame = new NBTTagCompound();
        frame.setInteger("version", VERSION);
        frame.setString("host", drive ? "drive" : "interface");
        appeng.tile.AEBaseTile ae = (appeng.tile.AEBaseTile) tile;
        putOrientation(frame, ae.getForward(), ae.getUp(), drive);
        if (drive) {
            ByteBuf data = Unpooled.buffer(49, 49);
            try {
                ((TileDrive) tile).writeToStream_TileDrive(data);
                if (data.readableBytes() != 49)
                    throw new IllegalStateException("Unexpected official Drive visual frame");
                frame.setInteger("state", data.readInt() & 0x7fffffff);
                frame.setInteger("type", data.readInt() & 0xfffff);
                frame.setByte("paint", (byte) validColor(data.readUnsignedByte()));
                // Native storage item ids are not needed for static RenderDrive and never enter the display payload.
                data.skipBytes(40);
            } finally {
                data.release();
            }
        } else {
            frame.setByte(
                "paint",
                (byte) ((TileInterface) tile).getColor()
                    .ordinal());
            // Read only the existing client display flag. Do not run the native writer, which accesses the grid.
            frame.setBoolean("stuck", ((TileInterface) tile).isStuck());
        }
        return wrap(frame);
    }

    /** Planned visuals are rebuilt from canonical markers. Saved live active/full/stuck flags are never inherited. */
    public static NBTTagCompound target(Block block, NBTTagCompound canonical) {
        if (!supports(block)) throw new IllegalArgumentException("Unreviewed AE preview block/factory");
        boolean drive = block.getClass() == BlockDrive.class;
        NBTTagCompound frame = new NBTTagCompound();
        frame.setInteger("version", VERSION);
        frame.setString("host", drive ? "drive" : "interface");
        ForgeDirection forward = direction(canonical, "orientation_forward");
        ForgeDirection up = direction(canonical, "orientation_up");
        putOrientation(frame, forward, up, drive);
        frame.setByte(
            "paint",
            (byte) validColor(
                canonical.hasKey("paintedColor", 99) ? canonical.getByte("paintedColor")
                    : AEColor.Transparent.ordinal()));
        if (drive) {
            int state = 0;
            NBTTagCompound inv = canonical.getCompoundTag("inv");
            for (int slot = 0; slot < 10; slot++) {
                NBTTagCompound row = inv.getCompoundTag("item" + slot);
                Item item = HologramBlueprintNbt.stableItem(row);
                if (row.hasKey("Count", 99) && row.getInteger("Count") > 0 && reviewedItemCell(item))
                    state |= 1 << (slot * 3); // Known empty item cell, offline. No item NBT or handler is read.
            }
            frame.setInteger("state", state);
            frame.setInteger("type", 0); // Audited cell classes all use ITEM_STACK_TYPE.
        } else frame.setBoolean("stuck", false);
        return wrap(frame);
    }

    public static NBTTagCompound frame(NBTTagCompound payload, String host) {
        if (!payload.hasKey(FRAME, 10)) throw new IllegalArgumentException("Missing AE visual projection");
        NBTTagCompound frame = payload.getCompoundTag(FRAME);
        if (!frame.hasKey("version", 3) || frame.getInteger("version") != VERSION
            || !frame.hasKey("host", 8)
            || !host.equals(frame.getString("host")))
            throw new IllegalArgumentException("Wrong AE visual projection family/version");
        if (!frame.hasKey("orientation_forward", 8) || !frame.hasKey("orientation_up", 8) || !frame.hasKey("paint", 1))
            throw new IllegalArgumentException("Malformed AE visual projection");
        if (host.equals("drive")) {
            if (frame.func_150296_c()
                .size() != 7 || !frame.hasKey("state", 3)
                || !frame.hasKey("type", 3)) throw new IllegalArgumentException("Malformed Drive visual projection");
        } else if (!host.equals("interface") || frame.func_150296_c()
            .size() != 6 || !frame.hasKey("stuck", 1))
            throw new IllegalArgumentException("Malformed Interface visual projection");
        return frame;
    }

    public static ForgeDirection direction(NBTTagCompound tag, String key) {
        if (!tag.hasKey(key, 8)) return ForgeDirection.UNKNOWN;
        try {
            return ForgeDirection.valueOf(tag.getString(key));
        } catch (IllegalArgumentException invalidDirection) {
            return ForgeDirection.UNKNOWN;
        }
    }

    public static int validColor(int value) {
        return value >= 0 && value < AEColor.values().length ? value : AEColor.Transparent.ordinal();
    }

    private static boolean reviewedItemCell(Item item) {
        if (item == null) return false;
        String type = item.getClass()
            .getName();
        return type.equals("appeng.items.storage.ItemBasicStorageCell")
            || type.equals("appeng.items.storage.ItemExtremeStorageCell")
            || type.equals("appeng.items.storage.ItemAdvancedStorageCell")
            || type.equals("appeng.items.storage.ItemVoidStorageCell");
    }

    private static void putOrientation(NBTTagCompound frame, ForgeDirection forward, ForgeDirection up, boolean drive) {
        if (forward == null || up == null
            || forward == ForgeDirection.UNKNOWN
            || up == ForgeDirection.UNKNOWN
            || forward == up
            || forward == up.getOpposite()) {
            forward = drive ? ForgeDirection.SOUTH : ForgeDirection.UNKNOWN;
            up = drive ? ForgeDirection.UP : ForgeDirection.UNKNOWN;
        }
        frame.setString("orientation_forward", forward.name());
        frame.setString("orientation_up", up.name());
    }

    private static NBTTagCompound wrap(NBTTagCompound frame) {
        NBTTagCompound result = new NBTTagCompound();
        result.setTag(FRAME, frame);
        return result;
    }
}
