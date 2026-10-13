package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.EnumSet;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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

    /** Resolve the audited physical definition only; part item NBT never enters a detached visual factory. */
    public static ItemStack partDefinition(NBTTagCompound definition) {
        Item item = HologramBlueprintNbt.stableItem(definition);
        if (item == null || item.getClass() != appeng.items.parts.ItemMultiPart.class
            || !definition.hasKey("Damage", 99)
            || !definition.hasKey("Count", 99)
            || definition.getInteger("Count") <= 0)
            throw new IllegalArgumentException("Unreviewed AE visual part definition");
        return new ItemStack(item, 1, definition.getInteger("Damage"));
    }

    public static ItemStack facadeDefinition(NBTTagCompound definition) {
        Item item = HologramBlueprintNbt.stableItem(definition);
        if (item == null || item.getClass() != appeng.items.parts.ItemFacade.class)
            throw new IllegalArgumentException("Unreviewed AE visual facade factory");
        ItemStack result = new ItemStack(item, 1, definition.getInteger("Damage"));
        NBTTagCompound input = definition.getCompoundTag("tag"), material = new NBTTagCompound();
        int[] ids = input.getIntArray("x");
        if (ids.length != 2) throw new IllegalArgumentException("Malformed AE visual facade material");
        material.setIntArray("x", ids.clone());
        for (String key : new String[] { "modid", "itemname" })
            if (input.hasKey(key, 8)) material.setString(key, input.getString(key));
        result.setTagCompound(material);
        return result;
    }

    /** Read existing client visual fields or an already-created official grid node; never execute a native writer. */
    public static NBTTagCompound cableVisual(appeng.parts.CableBusContainer bus) throws ReflectiveOperationException {
        appeng.api.parts.IPart part = bus.getPart(ForgeDirection.UNKNOWN);
        if (!(part instanceof appeng.parts.networking.PartCable)) return null;
        appeng.parts.networking.PartCable cable = (appeng.parts.networking.PartCable) part;
        int connections = 0, channels = 0;
        @SuppressWarnings("unchecked")
        EnumSet<ForgeDirection> sides = (EnumSet<ForgeDirection>) field(
            appeng.parts.networking.PartCable.class,
            "connections").get(cable);
        for (ForgeDirection direction : sides) connections |= 1 << direction.ordinal();
        int[] amounts = (int[]) field(appeng.parts.networking.PartCable.class, "channelsOnSide").get(cable);
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS)
            channels |= (amounts[direction.ordinal()] & 15) << (4 * direction.ordinal());
        if (field(appeng.parts.networking.PartCable.class, "powered").getBoolean(cable)) connections |= 64;
        Object cableProxy = field(appeng.parts.AEBasePart.class, "proxy").get(cable);
        Object existing = field(appeng.me.helpers.AENetworkProxy.class, "node").get(cableProxy);
        if (existing != null) {
            if (existing.getClass() != appeng.me.GridNode.class)
                throw new IllegalArgumentException("Unreviewed AE visual grid node");
            appeng.api.networking.IGridNode node = (appeng.api.networking.IGridNode) existing;
            connections = 0;
            channels = 0;
            for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
                appeng.api.parts.IPart sidePart = bus.getPart(direction);
                if (!(sidePart instanceof appeng.parts.AEBasePart)) continue;
                Object sideNode = field(appeng.me.helpers.AENetworkProxy.class, "node")
                    .get(field(appeng.parts.AEBasePart.class, "proxy").get(sidePart));
                if (sideNode != null && sideNode.getClass() == appeng.me.GridNode.class)
                    for (appeng.api.networking.IGridConnection connection : ((appeng.api.networking.IGridNode) sideNode)
                        .getConnections()) channels |= visualChannels(node, connection) << (4 * direction.ordinal());
            }
            for (appeng.api.networking.IGridConnection connection : node.getConnections()) {
                ForgeDirection direction = connection.getDirection(node);
                if (direction == ForgeDirection.UNKNOWN) continue;
                connections |= 1 << direction.ordinal();
                channels |= visualChannels(node, connection) << (4 * direction.ordinal());
            }
            appeng.api.networking.IGrid grid = node.getGrid();
            if (grid != null && grid.getClass() == appeng.me.Grid.class) {
                appeng.api.networking.energy.IEnergyGrid energy = grid
                    .getCache(appeng.api.networking.energy.IEnergyGrid.class);
                if (energy != null && energy.getClass() == appeng.me.cache.EnergyGridCache.class
                    && energy.isNetworkPowered()) connections |= 64;
            }
        }
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("connections", connections);
        result.setInteger("channels", channels);
        return result;
    }

    private static int visualChannels(appeng.api.networking.IGridNode center,
        appeng.api.networking.IGridConnection connection) {
        boolean dense = center.hasFlag(appeng.api.networking.GridFlags.DENSE_CAPACITY)
            && connection.getOtherSide(center)
                .hasFlag(appeng.api.networking.GridFlags.DENSE_CAPACITY);
        return dense ? Math.min(connection.getUsedChannels(), 32) / 4 : Math.min(connection.getUsedChannels(), 8);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

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
            || type.equals("appeng.items.storage.ItemVoidStorageCell")
            || type.equals("com.miaokatze.gtit.common.items.infinitycell.ItemInfinityStorageCell")
            || type.equals("com.miaokatze.gtit.common.items.infinitycell.ItemInfinityStorageFluidCell")
            || type.equals("com.miaokatze.gtit.common.items.infinitycell.ItemNekoInfinityStorageUnit");
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
