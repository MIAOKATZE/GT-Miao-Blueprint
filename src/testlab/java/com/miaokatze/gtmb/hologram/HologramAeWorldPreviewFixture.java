package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import appeng.api.AEApi;
import appeng.api.parts.IPartHost;
import appeng.api.util.AEColor;
import appeng.items.parts.ItemFacade;
import appeng.parts.PartPlacement;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Small native world source for the packaged preview probe; never constructs a display snapshot. */
public final class HologramAeWorldPreviewFixture {

    private HologramAeWorldPreviewFixture() {}

    public static Fixture create(WorldServer world, EntityPlayerMP actor, int x, int y, int z) throws Exception {
        require(Boolean.getBoolean("gtmb.hologram.packagedPreviewProbe"), "packaged preview probe opt-in required");
        require(world != null && actor != null && actor.worldObj == world, "native world actor required");
        require(
            world.getWorldInfo()
                .getWorldName()
                .contains("hologram"),
            "isolated hologram world required");
        Block block = AEApi.instance()
            .definitions()
            .blocks()
            .multiPart()
            .maybeBlock()
            .get();
        ItemStack cable = AEApi.instance()
            .definitions()
            .parts()
            .cableGlass()
            .stack(AEColor.Red, 1);
        ItemStack terminal = AEApi.instance()
            .definitions()
            .parts()
            .terminal()
            .maybeStack(1)
            .get();
        ItemFacade facadeItem = (ItemFacade) AEApi.instance()
            .definitions()
            .items()
            .facade()
            .maybeItem()
            .get();
        ItemStack facade = facadeItem.createFacadeForItem(new ItemStack(Blocks.stone), false);
        require(cable != null && facade != null, "official cable and stone facade enabled");
        Fixture result = new Fixture(world, x, y, z);
        // Check the complete footprint before placing its first native block.
        for (int[] position : result.positions()) {
            world.getChunkFromBlockCoords(position[0], position[2]);
            require(world.isAirBlock(position[0], position[1], position[2]), "fixture source requires fresh air");
        }
        PlacementWitness witness = new PlacementWitness(actor);
        int heldSlot = actor.inventory.currentItem;
        ItemStack originalHeld = actor.inventory.mainInventory[heldSlot];
        MinecraftForge.EVENT_BUS.register(witness);
        try {
            for (int i = 0; i < result.tiles.length; i++) {
                int[] position = result.positions()[i];
                ItemStack cableInHand = cable.copy();
                actor.inventory.mainInventory[heldSlot] = cableInHand;
                witness.events.clear();
                String before = diagnostic(world, block, position, actor, cable);
                // This is the official onItemUse entry after hit testing. It owns host creation,
                // place events and FMP conversion; an empty raw CableBus block is not a placed cable.
                boolean placed = PartPlacement
                    .placeItemPart(cableInHand, actor, world, position[0], position[1], position[2], ForgeDirection.UP);
                String after = diagnostic(world, block, position, actor, cable) + " placementEvents="
                    + witness.summary();
                System.out
                    .println("[GTMB-PRODUCTION-PREVIEW] AE native placement before=" + before + " after=" + after);
                require(placed, "official native cable placement failed; before=" + before + "; after=" + after);
                TileEntity tile = world.getTileEntity(position[0], position[1], position[2]);
                IPartHost host = PartPlacement.getExistingHost(tile);
                require(
                    host != null && host.getPart(ForgeDirection.UNKNOWN) != null,
                    "official native cable host unavailable; " + after);
                if (i == 2) {
                    ItemStack terminalInHand = terminal.copy();
                    actor.inventory.mainInventory[heldSlot] = terminalInHand;
                    require(
                        PartPlacement.placeItemPart(
                            terminalInHand,
                            actor,
                            world,
                            position[0],
                            position[1],
                            position[2],
                            ForgeDirection.NORTH),
                        "official native terminal installation; " + diagnostic(world, block, position, actor, terminal)
                            + " placementEvents="
                            + witness.summary());
                    host = PartPlacement.getExistingHost(world.getTileEntity(position[0], position[1], position[2]));
                    ItemStack facadeInHand = facade.copy();
                    actor.inventory.mainInventory[heldSlot] = facadeInHand;
                    require(
                        PartPlacement.facadeLogic(facadeInHand, actor, ForgeDirection.UP, host, false),
                        "official native stone facade; " + diagnostic(world, block, position, actor, facade));
                }
                result.tiles[i] = world.getTileEntity(position[0], position[1], position[2]);
                require(
                    result.tiles[i] != null,
                    "native placed tile exists; " + diagnostic(world, block, position, actor, cable));
                result.tiles[i].markDirty();
            }
        } finally {
            MinecraftForge.EVENT_BUS.unregister(witness);
            actor.inventory.mainInventory[heldSlot] = originalHeld;
            actor.inventory.markDirty();
            actor.inventoryContainer.detectAndSendChanges();
        }
        return result;
    }

    public static final class Fixture {

        private final WorldServer world;
        private final int x, y, z;
        private final TileEntity[] tiles = new TileEntity[3];
        private NBTTagCompound[] baseline;

        private Fixture(WorldServer world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int[][] positions() {
            return new int[][] { { x, y, z }, { x + 1, y, z }, { x + 3, y, z } };
        }

        /** Call after native server ticks have initialized the placed sources. */
        public NBTTagCompound checkpoint() throws Exception {
            baseline = new NBTTagCompound[tiles.length];
            NBTTagCompound receipt = new NBTTagCompound();
            NBTTagList rows = new NBTTagList();
            for (int i = 0; i < tiles.length; i++) {
                baseline[i] = persisted(tiles[i]);
                NBTTagCompound captured = HologramNativeCapture.capture(tiles[i]);
                require(baseline[i].equals(persisted(tiles[i])), "native capture changed its source");
                NBTTagCompound cableData = cableData(captured);
                require(cableData.hasKey("def:6", 10), "native capture retains physical cable definition");
                if (i == 2) require(
                    cableData.hasKey("def:2", 10) && cableData.hasKey("facade:1", 10),
                    "native capture retains terminal and stone facade");
                NBTTagCompound row = new NBTTagCompound();
                row.setIntArray("position", positions()[i]);
                row.setString(
                    "factory",
                    tiles[i].getClass()
                        .getName());
                row.setString(
                    "block",
                    Block.blockRegistry
                        .getNameForObject(world.getBlock(positions()[i][0], positions()[i][1], positions()[i][2])));
                row.setTag("persistence", baseline[i].copy());
                row.setTag("capture", captured);
                rows.appendTag(row);
            }
            receipt.setTag("sources", rows);
            receipt.setBoolean("captureSourceUnchanged", true);
            return receipt;
        }

        public void assertUnchanged() {
            require(baseline != null, "source checkpoint required after native initialization");
            for (int i = 0; i < tiles.length; i++) {
                int[] position = positions()[i];
                require(world.getTileEntity(position[0], position[1], position[2]) == tiles[i], "source tile replaced");
                require(baseline[i].equals(persisted(tiles[i])), "source persistence changed during preview/save");
            }
        }
    }

    private static NBTTagCompound persisted(TileEntity tile) {
        NBTTagCompound result = new NBTTagCompound();
        tile.writeToNBT(result);
        return result;
    }

    private static NBTTagCompound cableData(NBTTagCompound captured) {
        if (captured.hasKey("def:6", 10)) return captured;
        NBTTagList parts = captured.getTagList("parts", 10);
        for (int i = 0; i < parts.tagCount(); i++) {
            NBTTagCompound part = parts.getCompoundTagAt(i);
            if (part.getString("id")
                .equals("ae2_cablebus")) return part;
        }
        throw new IllegalStateException(
            "AE world preview fixture: no native CableBus in captured persistence " + captured);
    }

    private static String diagnostic(WorldServer world, Block registeredCable, int[] position, EntityPlayerMP actor,
        ItemStack intended) {
        Block actual = world.getBlock(position[0], position[1], position[2]);
        TileEntity tile = world.getTileEntity(position[0], position[1], position[2]);
        int metadata = world.getBlockMetadata(position[0], position[1], position[2]);
        Class<?> factory = ((appeng.block.AEBaseTileBlock) registeredCable).getTileEntityClass();
        return "position=" + position[0]
            + ","
            + position[1]
            + ","
            + position[2]
            + " actualBlock="
            + Block.blockRegistry.getNameForObject(actual)
            + "/"
            + Block.getIdFromBlock(actual)
            + "/"
            + actual.getClass()
                .getName()
            + " meta="
            + metadata
            + " hasTile="
            + actual.hasTileEntity(metadata)
            + " actualTile="
            + (tile == null ? "null"
                : tile.getClass()
                    .getName())
            + " registeredCable="
            + Block.blockRegistry.getNameForObject(registeredCable)
            + "/"
            + Block.getIdFromBlock(registeredCable)
            + " factory="
            + (factory == null ? "null" : factory.getName())
            + " actor="
            + actor.getUniqueID()
            + " allowEdit="
            + actor.capabilities.allowEdit
            + " creative="
            + actor.capabilities.isCreativeMode
            + " canMine="
            + world.canMineBlock(actor, position[0], position[1], position[2])
            + " registeredCanPlace="
            + registeredCable.canPlaceBlockAt(world, position[0], position[1], position[2])
            + " held="
            + actor.getHeldItem()
            + " heldItemClass="
            + (actor.getHeldItem() == null ? "null"
                : actor.getHeldItem()
                    .getItem()
                    .getClass()
                    .getName())
            + " heldPartItem="
            + (actor.getHeldItem() != null && actor.getHeldItem()
                .getItem() instanceof appeng.api.parts.IPartItem)
            + " intended="
            + intended
            + " intendedItemClass="
            + intended.getItem()
                .getClass()
                .getName()
            + " intendedPartItem="
            + (intended.getItem() instanceof appeng.api.parts.IPartItem);
    }

    /** Observes the actual native placement events after all handlers return; never changes cancellation. */
    public static final class PlacementWitness {

        private final EntityPlayerMP actor;
        private final List<BlockEvent.PlaceEvent> events = new ArrayList<>();

        private PlacementWitness(EntityPlayerMP actor) {
            this.actor = actor;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.player == actor) events.add(event);
        }

        private String summary() {
            StringBuilder result = new StringBuilder("count=").append(events.size());
            for (BlockEvent.PlaceEvent event : events) {
                result.append(" [position=")
                    .append(event.x)
                    .append(',')
                    .append(event.y)
                    .append(',')
                    .append(event.z)
                    .append(" canceled=")
                    .append(event.isCanceled())
                    .append(" itemInHand=")
                    .append(event.itemInHand);
                if (event.isCanceled()) {
                    try {
                        java.lang.reflect.Field id = MinecraftForge.EVENT_BUS.getClass()
                            .getDeclaredField("busID");
                        id.setAccessible(true);
                        result.append(" registeredHandlers=");
                        for (Object handler : event.getListenerList()
                            .getListeners(id.getInt(MinecraftForge.EVENT_BUS)))
                            result.append('{')
                                .append(handler)
                                .append('}');
                    } catch (ReflectiveOperationException unavailable) {
                        result.append(" registeredHandlersUnavailable=")
                            .append(unavailable);
                    }
                }
                result.append(']');
            }
            return result.toString();
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException("AE world preview fixture: " + message);
    }
}
