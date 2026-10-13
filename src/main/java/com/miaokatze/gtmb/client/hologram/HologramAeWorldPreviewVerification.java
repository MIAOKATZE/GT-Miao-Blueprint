package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.parts.IPart;
import appeng.api.util.AEColor;
import appeng.block.networking.BlockCableBus;
import appeng.me.helpers.AENetworkProxy;
import appeng.parts.AEBasePart;
import appeng.parts.CableBusContainer;
import appeng.parts.networking.PartCable;
import appeng.tile.networking.TileCableBus;

/** Verifies only a real received Service snapshot of the small native AE world fixture. */
public final class HologramAeWorldPreviewVerification {

    private HologramAeWorldPreviewVerification() {}

    public static NBTTagCompound verify(NBTTagCompound snapshot) throws ReflectiveOperationException {
        return verify(snapshot, false);
    }

    public static NBTTagCompound verify(NBTTagCompound snapshot, boolean current) throws ReflectiveOperationException {
        require(Boolean.getBoolean("gtmb.hologram.packagedPreviewProbe"), "packaged preview probe opt-in required");
        NBTTagCompound before = (NBTTagCompound) snapshot.copy();
        HologramState state = new HologramState(snapshot);
        HologramBlockRender.begin(state);
        HologramBlockAccess access = new HologramBlockAccess(state, current);
        NBTTagCompound receipt = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        int cables = 0, terminals = 0, facades = 0, connectedCables = 0;
        for (HologramState.Cell cell : state.cells) {
            Block block = cell.block(current);
            if (block == null) continue;
            boolean cbc = block.getClass() == BlockCableBus.class;
            boolean fmp = block.getClass()
                .getName()
                .equals("codechicken.multipart.BlockMultipart")
                && hasAeBus((current ? cell.actualDisplay : cell.targetDisplay).getCompoundTag("nativeTile"));
            if (!cbc && !fmp) continue;
            cables++;
            require(
                HologramBlockRender.ready(cell, current),
                "native geometry unavailable: " + HologramBlockRender.failure(cell, current));
            require(!HologramBlockRender.missingTexture(cell, current), "missing native cable atlas surface");
            int vertices = HologramBlockRender.vertexCount(cell, current);
            require(vertices >= 24 && vertices % 4 == 0, "native cable emits complete quads");
            TileEntity tile = access.getTileEntity(cell.x, cell.y, cell.z);
            require(
                tile != null && tile.getWorldObj() instanceof HologramRenderWorld
                    && tile.getWorldObj() != Minecraft.getMinecraft().theWorld,
                "detached bounded native cable host");
            CableBusContainer bus = bus(tile);
            require(bus != null, "official CBC/FMP native cable bus restored");
            require(!bus.isInWorld() && bus.getColor() == AEColor.Red, "offline native red cable visual");
            IPart center = bus.getPart(ForgeDirection.UNKNOWN);
            require(center instanceof PartCable, "native center cable definition restored");
            @SuppressWarnings("unchecked")
            EnumSet<ForgeDirection> connections = (EnumSet<ForgeDirection>) field(PartCable.class, "connections")
                .get(center);
            if (!connections.isEmpty()) connectedCables++;
            int mask = 0;
            for (ForgeDirection side : connections) mask |= 1 << side.ordinal();
            for (ForgeDirection side : ForgeDirection.values()) {
                IPart part = bus.getPart(side);
                if (part == null) continue;
                require(part instanceof AEBasePart, "reviewed official visual part");
                AENetworkProxy proxy = ((AEBasePart) part).getProxy();
                require(
                    !proxy.isReady() && field(AENetworkProxy.class, "node").get(proxy) == null
                        && field(AENetworkProxy.class, "data").get(proxy) == null,
                    "visual part has no network lifecycle");
                if (side != ForgeDirection.UNKNOWN) {
                    require(
                        side == ForgeDirection.NORTH && part.getClass()
                            .getName()
                            .equals("appeng.parts.reporting.PartTerminal"),
                        "exact native north terminal restored");
                    terminals++;
                }
            }
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                if (bus.getFacadeContainer()
                    .getFacade(side) == null) continue;
                require(side == ForgeDirection.UP, "exact native upper facade restored");
                require(
                    bus.getFacadeContainer()
                        .getFacade(side)
                        .getItem() == Item.getItemFromBlock(Blocks.stone)
                        && bus.getFacadeContainer()
                            .getFacade(side)
                            .getItemDamage() == 0,
                    "exact native stone facade material restored");
                facades++;
            }
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("position", new int[] { cell.x, cell.y, cell.z });
            row.setInteger("vertices", vertices);
            row.setInteger("connections", mask);
            row.setString(
                "factory",
                tile.getClass()
                    .getName());
            row.setString("block", Block.blockRegistry.getNameForObject(block));
            rows.appendTag(row);
        }
        require(cables == 3 && terminals == 1 && facades == 1, "three native cables, one terminal and one facade");
        require(connectedCables == 2, "two adjacent native cables restore their connecting arms");
        require(before.equals(snapshot), "client verification changed received snapshot");
        receipt.setTag("cells", rows);
        receipt.setInteger("cables", cables);
        receipt.setInteger("terminals", terminals);
        receipt.setInteger("facades", facades);
        receipt.setBoolean("snapshotUnchanged", true);
        receipt.setBoolean("nativeGeometry", true);
        receipt.setBoolean("noMissingTexture", true);
        receipt.setBoolean("noNetworkLifecycle", true);
        return receipt;
    }

    private static boolean hasAeBus(NBTTagCompound nativeTile) {
        NBTTagList parts = nativeTile.getTagList("parts", 10);
        for (int i = 0; i < parts.tagCount(); i++) if (parts.getCompoundTagAt(i)
            .getString("id")
            .equals("ae2_cablebus")) return true;
        return false;
    }

    private static CableBusContainer bus(TileEntity tile) throws ReflectiveOperationException {
        if (tile instanceof TileCableBus) return ((TileCableBus) tile).getCableBus();
        if (!Class.forName("codechicken.multipart.TileMultipart")
            .isInstance(tile)) return null;
        Class<?> cablePart = Class.forName("appeng.fmp.CableBusPart");
        for (Object part : (List<?>) tile.getClass()
            .getMethod("jPartList")
            .invoke(tile))
            if (cablePart.isInstance(part) && part.getClass()
                .getMethod("getType")
                .invoke(part)
                .equals("ae2_cablebus"))
                return (CableBusContainer) part.getClass()
                    .getMethod("getCableBus")
                    .invoke(part);
        return null;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException("AE world preview verification: " + message);
    }
}
