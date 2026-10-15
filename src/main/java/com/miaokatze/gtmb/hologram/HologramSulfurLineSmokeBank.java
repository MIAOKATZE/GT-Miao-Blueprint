package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.helpers.WirelessTerminalGuiObject;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileDenseEnergyCell;
import appeng.tile.networking.TileWireless;
import appeng.tile.storage.TileDrive;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;

/** A finite native payment network outside the blueprint footprint, attached to the placer's existing controller. */
public final class HologramSulfurLineSmokeBank {

    private static final double INITIAL_AE = 1_000_000;
    private static final double TERMINAL_AE_LIMIT = 100_000;
    private static final int TERMINAL_SLOT = 34;

    private HologramSulfurLineSmokeBank() {}

    public static Bank create(WorldServer world, EntityPlayerMP actor, int controllerX, int y, int z) throws Exception {
        require(world != null && actor != null && actor.worldObj == world, "native world actor required");
        require(
            world.getWorldInfo()
                .getWorldName()
                .contains("hologram"),
            "isolated hologram world required");
        TileEntity controller = world.getTileEntity(controllerX, y, z);
        require(
            controller != null && controller.getClass()
                .getName()
                .equals("appeng.tile.networking.TileController"),
            "existing registered native AE controller required");
        require(controller.getWorldObj() == world && !controller.isInvalid(), "existing native controller required");
        int[][] positions = { { controllerX - 1, y, z }, { controllerX - 1, y + 1, z }, { controllerX + 4, y, z + 3 },
            { controllerX + 3, y, z + 3 } };
        for (int[] position : positions) {
            world.getChunkFromBlockCoords(position[0], position[2]);
            require(world.isAirBlock(position[0], position[1], position[2]), "fresh payment bank footprint required");
        }
        TileDenseEnergyCell power = place(
            world,
            actor,
            AEApi.instance()
                .definitions()
                .blocks()
                .energyCellDense()
                .maybeBlock()
                .get(),
            controllerX - 1,
            y,
            z,
            TileDenseEnergyCell.class);
        require(!power.isInfinite() && power.getAEMaxPower() >= INITIAL_AE, "real finite dense AE cell capacity");
        NBTTagCompound initialPower = new NBTTagCompound();
        initialPower.setDouble("internalCurrentPower", INITIAL_AE);
        power.readFromNBT_TileEnergyCell(initialPower);
        power.markDirty();
        TileWireless wap = place(
            world,
            actor,
            AEApi.instance()
                .definitions()
                .blocks()
                .wireless()
                .maybeBlock()
                .get(),
            controllerX - 1,
            y + 1,
            z,
            TileWireless.class);
        // The WAP connects directly DOWN into the real finite cell; no bank AE cable is placed.
        wap.setOrientation(ForgeDirection.UP, ForgeDirection.NORTH);
        wap.getInternalInventory()
            .setInventorySlotContents(
                0,
                AEApi.instance()
                    .definitions()
                    .materials()
                    .wirelessBooster()
                    .maybeStack(4)
                    .get());
        wap.markDirty();
        require(wap.getRange() >= 24, "four real native boosters cover the actual placement camera");
        // This drive has no physical route to the controller or AE power. A real terminal places its adjacent gateway.
        TileDrive drive = place(
            world,
            actor,
            AEApi.instance()
                .definitions()
                .blocks()
                .drive()
                .maybeBlock()
                .get(),
            controllerX + 4,
            y,
            z + 3,
            TileDrive.class);
        drive.getInternalInventory()
            .setInventorySlotContents(
                0,
                AEApi.instance()
                    .definitions()
                    .items()
                    .cell64k()
                    .maybeStack(1)
                    .get());
        drive.getInternalInventory()
            .setInventorySlotContents(1, fluidCell());
        drive.markDirty();
        ItemStack terminal = AEApi.instance()
            .definitions()
            .items()
            .wirelessTerminal()
            .maybeStack(1)
            .get();
        IAEItemPowerStorage terminalPower = cast(terminal.getItem(), IAEItemPowerStorage.class);
        double terminalOffered = Math.min(TERMINAL_AE_LIMIT, terminalPower.getAEMaxPower(terminal));
        require(Double.isFinite(terminalOffered) && terminalOffered > 0, "finite native terminal battery");
        double terminalInjected = terminalOffered - terminalPower.injectAEPower(terminal, terminalOffered);
        require(
            terminalInjected > 0 && terminalPower.getAECurrentPower(terminal) == terminalInjected,
            "native terminal charge accepted");
        return new Bank(world, actor, controllerX, y, z, controller, power, wap, drive, terminal, terminalInjected);
    }

    public static final class Bank {

        private final WorldServer world;
        private final EntityPlayerMP actor;
        private final int x, y, z;
        private final TileEntity controller;
        private final TileDenseEnergyCell power;
        private final TileWireless wap;
        private final TileDrive drive;
        private final ItemStack terminal;
        private final double terminalInjected;
        private final List<IAEItemStack> suppliedItems = new ArrayList<>();
        private final List<IAEFluidStack> suppliedFluids = new ArrayList<>();
        private NBTTagCompound suppliedSnapshot;
        private long suppliedTick = -1;
        private boolean supplied;

        private Bank(WorldServer world, EntityPlayerMP actor, int x, int y, int z, TileEntity controller,
            TileDenseEnergyCell power, TileWireless wap, TileDrive drive, ItemStack terminal, double terminalInjected) {
            this.world = world;
            this.actor = actor;
            this.x = x;
            this.y = y;
            this.z = z;
            this.controller = controller;
            this.power = power;
            this.wap = wap;
            this.drive = drive;
            this.terminal = terminal;
            this.terminalInjected = terminalInjected;
        }

        /** Natural AE initialization and physical connectivity determine readiness. */
        public boolean ready() {
            try {
                return live(controller, x, y, z) && live(power, x - 1, y, z)
                    && live(wap, x - 1, y + 1, z)
                    && live(drive, x + 4, y, z + 3)
                    && controllerProxy().isActive()
                    && drive.getProxy()
                        .isActive()
                    && wap.getProxy()
                        .isActive()
                    && wap.getLocatableSerial() != 0
                    && drive.getProxy()
                        .getGrid() == controllerProxy().getGrid()
                    && wap.getProxy()
                        .getGrid() == controllerProxy().getGrid()
                    && power.getProxy()
                        .getGrid() == controllerProxy().getGrid();
            } catch (Exception unavailable) {
                return false;
            }
        }

        public boolean controllerReady() {
            return live(controller, x, y, z) && live(power, x - 1, y, z) && controllerProxy().isActive();
        }

        /** One fixture supply of the actual server quote. Production payment owns every subsequent extraction. */
        public void supply(HologramBlueprintResources.Plan plan) throws Exception {
            require(
                !supplied && suppliedItems.isEmpty() && suppliedFluids.isEmpty(),
                "payment bank supply is once only");
            require(
                plan != null && plan.energy.signum() == 0,
                "quoted wireless EU requires a real wireless EU provider");
            require(plan.essentia.isEmpty(), "this native bank has no quoted essentia provider");
            require(ready(), "naturally active native payment grid required");
            ISecurityGrid security = controllerProxy().getGrid()
                .getCache(ISecurityGrid.class);
            require(
                security.hasPermission(actor, SecurityPermissions.EXTRACT)
                    && security.hasPermission(actor, SecurityPermissions.INJECT)
                    && security.hasPermission(actor, SecurityPermissions.BUILD),
                "native placing actor grid permissions");
            require(
                snapshot().getTagList("items", 10)
                    .tagCount() == 0
                    && snapshot().getTagList("fluids", 10)
                        .tagCount() == 0,
                "payment bank begins with empty native cells");
            IWirelessTermHandler handler = AEApi.instance()
                .registries()
                .wireless()
                .getWirelessTerminalHandler(terminal);
            require(
                handler != null && !handler.hasInfinityRange(terminal) && !handler.hasInfinityPower(terminal),
                "finite registered ordinary wireless terminal");
            handler.setEncryptionKey(terminal, Long.toString(wap.getLocatableSerial()), "Sulfur physical fixture bank");
            require(actor.inventory.mainInventory[TERMINAL_SLOT] == null, "payment terminal slot is empty");
            actor.inventory.mainInventory[TERMINAL_SLOT] = terminal;
            actor.inventory.markDirty();
            actor.inventoryContainer.detectAndSendChanges();
            WirelessTerminalGuiObject gui = terminalGui();
            require(
                gui.rangeCheck() && gui.getActionableNode() != null
                    && gui.getActionableNode()
                        .isActive()
                    && gui.getGrid() == drive.getProxy()
                        .getGrid(),
                "actual carried terminal resolves the native bank in range");
            require(
                gui.getItemInventory() == items() && gui.getFluidInventory() == fluids(),
                "native terminal authenticates the actual item and fluid monitors");
            PlayerSource action = new PlayerSource(actor, drive);
            for (ItemStack item : plan.items) {
                require(
                    item != null && item.getItem() != null && item.stackSize > 0,
                    "positive quoted native item stack");
                IAEItemStack offered = AEItemStack.create(item.copy());
                require(
                    items().injectItems(offered, Actionable.MODULATE, action) == null,
                    "finite quoted item supply accepted by native ME monitor");
                suppliedItems.add(offered.copy());
            }
            for (HologramBlueprintResources.FluidCost cost : plan.fluids) {
                require(cost != null && cost.stack != null && cost.amount > 0, "positive quoted native fluid stock");
                IAEFluidStack offered = AEFluidStack.create(cost.stack.copy());
                offered.setStackSize(cost.amount);
                require(
                    fluids().injectItems(offered, Actionable.MODULATE, action) == null,
                    "finite quoted fluid supply accepted by native ME monitor");
                suppliedFluids.add(offered.copy());
            }
            supplied = true;
            suppliedTick = world.getTotalWorldTime();
            suppliedSnapshot = snapshot();
            for (IAEItemStack quoted : suppliedItems)
                require(itemRemaining(quoted) == quoted.getStackSize(), "native cell holds exact quoted item supply");
            for (IAEFluidStack quoted : suppliedFluids)
                require(fluidRemaining(quoted) == quoted.getStackSize(), "native cell holds exact quoted fluid supply");
        }

        /** All precisely supplied material types must be exhausted in the live native monitors. */
        public boolean paid() {
            if (!supplied || !ready()) return false;
            try {
                for (IAEItemStack quoted : suppliedItems) if (itemRemaining(quoted) != 0) return false;
                for (IAEFluidStack quoted : suppliedFluids) if (fluidRemaining(quoted) != 0) return false;
                return true;
            } catch (Exception unavailable) {
                return false;
            }
        }

        public void verifyPaid() {
            require(paid(), "all actual quoted item and fluid supplies must be natively debited");
        }

        public NBTTagCompound receipt() throws Exception {
            NBTTagCompound result = new NBTTagCompound();
            result.setIntArray("existingController", new int[] { x, y, z });
            result.setIntArray("bankBounds", new int[] { x - 1, y, z, x + 4, y + 1, z + 3 });
            result.setIntArray("storageQuantumGateway", new int[] { x + 3, y, z + 3 });
            result.setIntArray("remoteDrivePosition", new int[] { x + 4, y, z + 3 });
            result.setString(
                "actor",
                actor.getUniqueID()
                    .toString());
            result.setBoolean("controllerReused", true);
            result.setBoolean("ready", ready());
            result.setBoolean("supplied", supplied);
            result.setBoolean("paid", paid());
            result.setLong("suppliedTick", suppliedTick);
            result.setLong("worldTick", world.getTotalWorldTime());
            result.setDouble("initialAE", INITIAL_AE);
            result.setDouble("terminalInitialAE", terminalInjected);
            result.setDouble("nativeCellAE", power.getAECurrentPower());
            result.setDouble("nativeCellCapacityAE", power.getAEMaxPower());
            result.setBoolean("nativeCellInfinite", power.isInfinite());
            result.setInteger("terminalSlot", TERMINAL_SLOT);
            result.setBoolean("terminalCarried", actor.inventory.mainInventory[TERMINAL_SLOT] == terminal);
            result.setLong("wapSerial", wap.getLocatableSerial());
            result.setDouble("wapRange", wap.getRange());
            NBTTagCompound nativeWap = new NBTTagCompound();
            wap.writeToNBT(nativeWap);
            result.setTag("nativeWap", nativeWap);
            result.setTag(
                "nativeWapBooster",
                wap.getInternalInventory()
                    .getStackInSlot(0)
                    .writeToNBT(new NBTTagCompound()));
            result.setBoolean("ordinaryAeCablesPlaced", false);
            result.setString(
                "nativePowerTile",
                power.getClass()
                    .getName());
            result.setTag("terminal", terminal.writeToNBT(new NBTTagCompound()));
            if (ready()) {
                result.setDouble(
                    "nativeGridAE",
                    controllerProxy().getEnergy()
                        .extractAEPower(1_000_000_000, Actionable.SIMULATE, PowerMultiplier.ONE));
                result.setInteger(
                    "nativeControllerOwner",
                    controllerProxy().getNode()
                        .getPlayerID());
                result.setTag("currentStock", snapshot());
                if (suppliedSnapshot != null) result.setTag("suppliedStock", suppliedSnapshot.copy());
                NBTTagList itemLedger = new NBTTagList();
                for (IAEItemStack quoted : suppliedItems) {
                    NBTTagCompound row = new NBTTagCompound();
                    quoted.writeToNBT(row);
                    long remaining = itemRemaining(quoted);
                    row.setLong("supplied", quoted.getStackSize());
                    row.setLong("remaining", remaining);
                    row.setLong("nativeDebited", quoted.getStackSize() - remaining);
                    itemLedger.appendTag(row);
                }
                result.setTag("itemLedger", itemLedger);
                NBTTagList fluidLedger = new NBTTagList();
                for (IAEFluidStack quoted : suppliedFluids) {
                    NBTTagCompound row = new NBTTagCompound();
                    quoted.writeToNBT(row);
                    long remaining = fluidRemaining(quoted);
                    row.setLong("supplied", quoted.getStackSize());
                    row.setLong("remaining", remaining);
                    row.setLong("nativeDebited", quoted.getStackSize() - remaining);
                    fluidLedger.appendTag(row);
                }
                result.setTag("fluidLedger", fluidLedger);
                if (supplied) {
                    WirelessTerminalGuiObject gui = terminalGui();
                    result.setBoolean("terminalNativeRange", gui.rangeCheck());
                    result.setBoolean(
                        "terminalNativeGrid",
                        gui.getGrid() == drive.getProxy()
                            .getGrid());
                }
            }
            NBTTagCompound nativeDrive = new NBTTagCompound();
            drive.writeToNBT(nativeDrive);
            result.setTag("nativeDrive", nativeDrive);
            result.setString(
                "debitEvidence",
                "exact quoted item/fluid balances in real native monitors; bank performs no material extraction");
            return result;
        }

        private boolean live(TileEntity tile, int px, int py, int pz) {
            return world.getTileEntity(px, py, pz) == tile && tile.getWorldObj() == world && !tile.isInvalid();
        }

        private AENetworkProxy controllerProxy() {
            return ((IGridProxyable) controller).getProxy();
        }

        private WirelessTerminalGuiObject terminalGui() {
            IWirelessTermHandler handler = AEApi.instance()
                .registries()
                .wireless()
                .getWirelessTerminalHandler(terminal);
            require(handler != null, "native wireless terminal handler");
            return new WirelessTerminalGuiObject(handler, terminal, actor, world, TERMINAL_SLOT, 0, 0);
        }

        private IMEMonitor<IAEItemStack> items() throws Exception {
            return drive.getProxy()
                .getStorage()
                .getItemInventory();
        }

        private IMEMonitor<IAEFluidStack> fluids() throws Exception {
            return drive.getProxy()
                .getStorage()
                .getFluidInventory();
        }

        private long itemRemaining(IAEItemStack quoted) throws Exception {
            IAEItemStack request = quoted.copy();
            request.setStackSize(Long.MAX_VALUE);
            IAEItemStack actual = items().extractItems(request, Actionable.SIMULATE, new PlayerSource(actor, drive));
            return actual == null ? 0 : actual.getStackSize();
        }

        private long fluidRemaining(IAEFluidStack quoted) throws Exception {
            IAEFluidStack request = quoted.copy();
            request.setStackSize(Long.MAX_VALUE);
            IAEFluidStack actual = fluids().extractItems(request, Actionable.SIMULATE, new PlayerSource(actor, drive));
            return actual == null ? 0 : actual.getStackSize();
        }

        private NBTTagCompound snapshot() throws Exception {
            NBTTagCompound result = new NBTTagCompound();
            NBTTagList itemStock = new NBTTagList();
            for (IAEItemStack stack : items().getStorageList()) {
                if (stack.getStackSize() <= 0) continue;
                NBTTagCompound row = new NBTTagCompound();
                stack.writeToNBT(row);
                row.setLong("nativeAmount", stack.getStackSize());
                itemStock.appendTag(row);
            }
            result.setTag("items", itemStock);
            NBTTagList fluidStock = new NBTTagList();
            for (IAEFluidStack stack : fluids().getStorageList()) {
                if (stack.getStackSize() <= 0) continue;
                NBTTagCompound row = new NBTTagCompound();
                stack.writeToNBT(row);
                row.setLong("nativeAmount", stack.getStackSize());
                fluidStock.appendTag(row);
            }
            result.setTag("fluids", fluidStock);
            return result;
        }
    }

    private static <T extends TileEntity> T place(WorldServer world, EntityPlayerMP actor, Block block, int x, int y,
        int z, Class<T> type) {
        require(world.setBlock(x, y, z, block, 0, 3), "registered native AE block placement");
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null) {
            tile = block.createTileEntity(world, 0);
            if (tile == null && block instanceof appeng.block.AEBaseTileBlock)
                tile = ((appeng.block.AEBaseTileBlock) block).createNewTileEntity(world, 0);
            require(type.isInstance(tile), "registered native AE factory");
            world.setTileEntity(x, y, z, tile);
        }
        T result = cast(tile, type);
        require(result.getWorldObj() == world && !result.isInvalid(), "actual native AE world tile");
        ((IGridProxyable) result).getProxy()
            .setOwner(actor);
        result.markDirty();
        return result;
    }

    private static ItemStack fluidCell() throws Exception {
        Object item = Class.forName("com.glodblock.github.loader.ItemAndBlockHolder")
            .getField("CELL64KM")
            .get(null);
        require(item instanceof Item, "registered finite AE2FC multi-fluid cell");
        ItemStack stack = new ItemStack((Item) item, 1, 0);
        require(
            AEApi.instance()
                .registries()
                .cell()
                .getCellInventory(stack, null, StorageChannel.FLUIDS) != null,
            "native registered finite fluid cell handler");
        return stack;
    }

    private static <T> T cast(Object value, Class<T> type) {
        require(type.isInstance(value), "expected native " + type.getName());
        return type.cast(value);
    }

    private static void require(boolean accepted, String message) {
        if (!accepted) throw new IllegalStateException("Native sulfur payment bank: " + message);
    }
}
