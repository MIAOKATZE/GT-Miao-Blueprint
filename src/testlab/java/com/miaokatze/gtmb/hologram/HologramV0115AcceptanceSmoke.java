package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.Arrays;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.GregTechAPI;

/** Explicitly invoked installed-mod witnesses. No registration, normal ticks, synthetic grid, or success defaults. */
public final class HologramV0115AcceptanceSmoke {

    private HologramV0115AcceptanceSmoke() {}

    /** The isolated runner owns persistence, artifact hashes, shutdown and every process. */
    public static NBTTagCompound run(WorldServer world, EntityPlayerMP player) throws Exception {
        guard(player);
        require(player.worldObj == world && player instanceof FakePlayer, "native Forge actor in selected world");
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("quote", quoteIdentity());
        result.setTag("voucher", voucherTransactions(world, player));
        result.setTag("hubs", HologramV0115HubAcceptanceSmoke.run(world, player));
        result.setString(
            "aeOrderLifecycle",
            "NOT_RUN: requires a connected authenticated native wireless network, pattern and CPU");
        result.setString("largeSurvivalAePayment", "NOT_RUN: no massive connected AE storage fixture supplied");
        result.setBoolean("realNettyClient", false);
        result.setString("status", "PASS_SELECTED_QUOTE_AND_VOUCHER_CONTRACTS");
        System.out.println("[GTMB-V0115-ACCEPTANCE] " + result);
        return result;
    }

    private static NBTTagCompound quoteIdentity() {
        ItemStack controller = new ItemStack(GregTechAPI.sBlockMachines, 1, 1000);
        ItemStack hatch = new ItemStack(GregTechAPI.sBlockMachines, 1, 51);
        require(
            GregTechAPI.METATILEENTITIES[1000] != null && GregTechAPI.METATILEENTITIES[51] != null,
            "native GT registry entries exist");
        ItemStack part = AEApi.instance()
            .definitions()
            .parts()
            .iface()
            .maybeStack(1)
            .get();
        NBTTagCompound identity = new NBTTagCompound();
        identity.setString("owner", "native-quote-probe");
        identity.setLong("serial", 0x100000001L);
        part.setTagCompound(identity);
        long demand = (long) Integer.MAX_VALUE + 65000L, available = (long) Integer.MAX_VALUE + 17L;
        NBTTagList legacy = new NBTTagList();
        legacy.appendTag(legacy(controller, demand, available));
        legacy.appendTag(legacy(hatch, 11, 4));
        legacy.appendTag(legacy(part, demand, available));
        ItemStack other = part.copy();
        other.getTagCompound()
            .setString("owner", "different-native-quote-probe");
        legacy.appendTag(legacy(other, 3, 1));
        NBTTagCompound quote = HologramMaterialQuote.fromRows(legacy, new NBTTagList());
        NBTTagList rows = quote.getTagList("rows", 10);
        require(rows.tagCount() == 4, "GT damage and AE NBT identities remain distinct");
        ItemStack[] samples = { controller, hatch, part, other };
        for (int index = 0; index < samples.length; index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            ItemStack sample = samples[index];
            require(
                row.getString("itemRegistry")
                    .equals(Item.itemRegistry.getNameForObject(sample.getItem())),
                "native registered item identity " + index);
            // A process-local numeric id must not override the stable registry identity.
            row.getCompoundTag("item")
                .setShort("id", (short) Item.getIdFromItem(Item.getItemFromBlock(Blocks.dirt)));
            ItemStack restored = HologramMaterialQuote.readItem(row);
            require(
                restored != null && restored.stackSize == 1
                    && restored.isItemEqual(sample)
                    && ItemStack.areItemStackTagsEqual(restored, sample),
                "exact native quote identity " + index);
            if (index == 0 || index == 2) require(
                row.getLong("required") == demand && row.getLong("available") == available
                    && row.getLong("missing") == demand - available,
                "long quantity independent of ItemStack Count " + index);
        }
        IAEItemStack nativeStack = AEItemStack.create(part);
        nativeStack.setStackSize(demand);
        NBTTagCompound nativeNbt = new NBTTagCompound();
        nativeStack.writeToNBT(nativeNbt);
        IAEItemStack roundtrip = AEItemStack.loadItemStackFromNBT(nativeNbt);
        require(
            roundtrip != null && roundtrip.getStackSize() == demand
                && ItemStack.areItemStackTagsEqual(part, roundtrip.getItemStack()),
            "actual AE long serialization");
        NBTTagCompound result = new NBTTagCompound();
        result.setString("status", "PASS");
        result.setLong("required", demand);
        result.setLong("available", available);
        result.setTag("rows", rows);
        result.setTag("nativeAeStack", nativeNbt);
        return result;
    }

    private static NBTTagCompound legacy(ItemStack sample, long required, long available) {
        NBTTagCompound row = sample.writeToNBT(new NBTTagCompound());
        row.setLong("required", required);
        row.setLong("available", available);
        return row;
    }

    private static NBTTagCompound voucherTransactions(WorldServer world, EntityPlayerMP player) throws Exception {
        require(HologramDecorationVoucher.enabled(), "voucher enabled in isolated fixture config");
        ItemStack[] original = HologramMaterialAccess.copyInventory(player);
        int slot = player.inventory.currentItem;
        boolean creative = player.capabilities.isCreativeMode;
        double px = player.posX, py = player.posY, pz = player.posZ;
        final int sx = 1040, x = 1080, y = 100, z = 1040;
        try {
            player.inventory.currentItem = 0;
            player.capabilities.isCreativeMode = false;
            Arrays.fill(player.inventory.mainInventory, null);
            player.inventory.mainInventory[0] = original[slot];
            require(player.inventory.mainInventory[0] != null, "runner supplies registered projector");
            ItemStack voucher = HologramDecorationVoucher.stack();
            require(HologramDecorationVoucher.isVoucher(voucher), "actual registered clean voucher");
            voucher.stackSize = 2;
            player.inventory.mainInventory[1] = voucher;
            player.inventory.mainInventory[2] = new ItemStack(Blocks.stone);
            player.setPosition(x + .5, y, z + .5);
            for (int cx = sx >> 4; cx <= (x + 2) >> 4; cx++) world.theChunkProviderServer.loadChunk(cx, z >> 4);
            Block block = HologramNativeCompatibilityFixture.blocks()
                .get(1);
            require(
                world.isAirBlock(sx, y, z) && world.isAirBlock(x, y, z)
                    && world.isAirBlock(x + 1, y, z)
                    && world.isAirBlock(x + 2, y, z),
                "fresh native voucher fixture");
            NBTTagCompound seed = (NBTTagCompound) helper(
                "canonicalSeed",
                new Class<?>[] { Block.class, int.class, int.class, int.class },
                block,
                sx,
                y,
                z);
            require(world.setBlock(sx, y, z, block, 0, 3), "native source host");
            HologramNativePlacement.install(world, sx, y, z, block, 0, seed);
            NBTTagCompound captured = HologramNativeCapture.capture(world.getTileEntity(sx, y, z));
            ItemStack sample = HologramArea.serverSample(block, 0, world.getTileEntity(sx, y, z));
            require(sample != null, "native multipart registered sample");
            HologramCapture.Cell first = cell(block, sample, captured, x, y, z);
            HologramSession session = (HologramSession) helper(
                "session",
                new Class<?>[] { EntityPlayerMP.class, HologramCapture.Cell.class, ItemStack.class },
                player,
                first,
                sample);
            session.jobVoucherRequested = true;
            ItemStack[] before = HologramMaterialAccess.copyInventory(player);
            Veto veto = new Veto(x, y, z);
            boolean vetoHandled;
            MinecraftForge.EVENT_BUS.register(veto);
            try {
                // operate reports that the cell was handled; a protected rollback may legitimately return true.
                // Only the native event, final cell/world state and exact refund establish rejection of commit.
                vetoHandled = HologramWorldTransactions.operate(session, first, 0);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(veto);
            }
            boolean vetoWorldEmpty = world.isAirBlock(x, y, z) && world.getTileEntity(x, y, z) == null;
            boolean vetoInventoryEqual = sameInventory(before, player);
            boolean vetoVoucherConsumed = session.jobVoucherConsumed;
            String vetoCellStatus = first.status;
            String vetoDiagnostic = " handled=" + vetoHandled
                + " events="
                + veto.count
                + " cell="
                + vetoCellStatus
                + " worldEmpty="
                + vetoWorldEmpty
                + " inventoryEqual="
                + vetoInventoryEqual
                + " voucherConsumed="
                + vetoVoucherConsumed
                + " status="
                + session.status;
            System.out.println("[GTMB-V0115-ACCEPTANCE] native PlaceEvent veto" + vetoDiagnostic);
            require(
                veto.count == 1 && "protected".equals(vetoCellStatus) && vetoWorldEmpty,
                "real veto event and protected native world rollback" + vetoDiagnostic);
            require(
                !vetoVoucherConsumed && vetoInventoryEqual,
                "voucher reservation and all inventory refunded" + vetoDiagnostic);
            require(
                HologramWorldTransactions.operate(session, first, 0) && "placed".equals(first.status),
                "native multipart voucher retry " + first.status + " " + session.status);
            require(
                session.jobVoucherConsumed && HologramDecorationVoucher.available(player) == 1,
                "successful first covered cell consumes one voucher");
            HologramCapture.Cell second = cell(block, sample, captured, x + 1, y, z);
            append(session, second, sample);
            require(
                HologramWorldTransactions.operate(session, second, 1) && "placed".equals(second.status),
                "second native multipart cell covered by same job");
            ItemStack stone = new ItemStack(Blocks.stone);
            HologramCapture.Cell ordinary = cell(Blocks.stone, stone, new NBTTagCompound(), x + 2, y, z);
            ordinary.tileNbt = "";
            append(session, ordinary, stone);
            require(
                HologramWorldTransactions.operate(session, ordinary, 2) && "placed".equals(ordinary.status),
                "ordinary non-decoration cost still charged");
            require(
                HologramDecorationVoucher.available(player) == 1 && player.inventory.mainInventory[2] == null,
                "one voucher per job and exact ordinary material delta");
            for (int dx = 0; dx < 2; dx++) require(
                world.getBlock(x + dx, y, z) == block && world.getTileEntity(x + dx, y, z) != null
                    && HologramNativeCapture.capture(world.getTileEntity(x + dx, y, z))
                        .getTagList("parts", 10)
                        .tagCount() == 2,
                "two actual native multipart elements " + dx);
            require(
                captured.equals(HologramNativeCapture.capture(world.getTileEntity(sx, y, z))),
                "native source untouched");
            NBTTagCompound result = new NBTTagCompound();
            result.setString("status", "PASS");
            result.setString(
                "source",
                "real Forge FakePlayer, native multipart factory, real Service transaction and PlaceEvent veto");
            result.setInteger("cancelledPlaceEvents", veto.count);
            result.setBoolean("cancelledCellHandled", vetoHandled);
            result.setString("cancelledCellStatus", vetoCellStatus);
            result.setBoolean("cancelledWorldEmpty", vetoWorldEmpty);
            result.setBoolean("cancelledInventoryEqual", vetoInventoryEqual);
            result.setBoolean("cancelledVoucherConsumed", vetoVoucherConsumed);
            result.setInteger("successfulNativeCells", 2);
            result.setLong("voucherBefore", 2);
            result.setLong("voucherAfter", 1);
            result.setInteger("ordinaryStoneConsumed", 1);
            result.setBoolean("failedCellRefunded", true);
            return result;
        } finally {
            for (int i = 0; i < original.length; i++) player.inventory.mainInventory[i] = original[i];
            player.inventory.currentItem = slot;
            player.capabilities.isCreativeMode = creative;
            player.setPosition(px, py, pz);
        }
    }

    private static HologramCapture.Cell cell(Block block, ItemStack sample, NBTTagCompound nativeTag, int x, int y,
        int z) throws Exception {
        return (HologramCapture.Cell) helper(
            "cell",
            new Class<?>[] { Block.class, int.class, NBTTagCompound.class, ItemStack.class, int.class, int.class,
                int.class },
            block,
            0,
            nativeTag,
            sample,
            x,
            y,
            z);
    }

    private static void append(HologramSession session, HologramCapture.Cell cell, ItemStack sample) {
        int index = session.capture.cells.size();
        session.capture.cells.add(cell);
        session.capture.positions.put(HologramValues.position(cell), cell);
        session.pins.put(index, sample.copy());
        session.planTargets.add(sample.copy());
    }

    private static boolean sameInventory(ItemStack[] before, EntityPlayerMP player) {
        for (int i = 0; i < before.length; i++)
            if (!ItemStack.areItemStacksEqual(before[i], player.inventory.mainInventory[i])) return false;
        return true;
    }

    private static Object helper(String name, Class<?>[] parameters, Object... values) throws Exception {
        Method method = HologramNativePlacementSmoke.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, values);
    }

    public static final class Veto {

        private final int x, y, z;
        int count;

        Veto(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @SubscribeEvent
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.x == x && event.y == y && event.z == z) {
                count++;
                event.setCanceled(true);
            }
        }
    }

    /**
     * Start only against a caller-provided REAL network already authenticated by the carried terminal. The caller
     * supplies a quote for an existing encoded pattern and free CPU; this class creates no patterns or grid mocks.
     * Call observe() once per native END tick while the normal server continues ticking AE and the order service.
     */
    public static NativeOrderWitness startNativeOrder(EntityPlayerMP player, NBTTagCompound serverQuote) {
        guard(player);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = true;
        settings.main = settings.containers = false;
        IGrid grid = new HologramMaterials.Context(player, settings).craftingGrid();
        require(grid != null, "actual carried terminal admits authenticated native network");
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        require(
            crafting != null && crafting.getClass()
                .getName()
                .startsWith("appeng.") && !java.lang.reflect.Proxy.isProxyClass(crafting.getClass()),
            "actual installed AE crafting cache");
        require(!HologramAeCraftingOrders.active(player), "fresh order slot");
        HologramAeCraftingOrders.submit(player, settings, serverQuote);
        require(HologramAeCraftingOrders.active(player), "production order accepted");
        return new NativeOrderWitness(player, grid, crafting, serverQuote);
    }

    /** Observer only: does not call future.get, tick a native tile, complete a callback, or modify an order. */
    public static final class NativeOrderWitness {

        private final EntityPlayerMP player;
        private final IGrid grid;
        private final ICraftingGrid crafting;
        private final NBTTagCompound quote;
        private final NBTTagList observed = new NBTTagList();
        private boolean sawPlanning, sawCrafting, sawRequesterLink;
        private int ticks;

        NativeOrderWitness(EntityPlayerMP player, IGrid grid, ICraftingGrid crafting, NBTTagCompound quote) {
            this.player = player;
            this.grid = grid;
            this.crafting = crafting;
            this.quote = (NBTTagCompound) quote.copy();
        }

        public NBTTagCompound observe() {
            guard(player);
            require(grid.getCache(ICraftingGrid.class) == crafting, "same actual native crafting cache");
            NBTTagCompound state = HologramAeCraftingOrders.snapshot(player);
            String phase = state.getString("state");
            sawPlanning |= "planning".equals(phase);
            sawCrafting |= "crafting".equals(phase);
            if (state.hasKey("standalone", 1) && !state.getString("craftingId")
                .isEmpty()) {
                require(
                    !state.getBoolean("standalone"),
                    "actual production order owns a non-standalone requester link");
                sawRequesterLink = true;
            }
            NBTTagCompound row = (NBTTagCompound) state.copy();
            row.setInteger("observedNativeTick", ++ticks);
            if (observed.tagCount() == 0 || !phase.equals(
                observed.getCompoundTagAt(observed.tagCount() - 1)
                    .getString("state")))
                observed.appendTag(row);
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setString(
                "status",
                state.getBoolean("active") ? "RUNNING"
                    : "complete".equals(phase) && sawPlanning && sawCrafting && sawRequesterLink ? "PASS" : "FAIL");
            receipt.setString(
                "cacheClass",
                crafting.getClass()
                    .getName());
            receipt.setBoolean("sawPlanning", sawPlanning);
            receipt.setBoolean("sawCrafting", sawCrafting);
            receipt.setBoolean("sawRequesterLink", sawRequesterLink);
            receipt.setTag("serverQuote", quote.copy());
            receipt.setTag("observedStates", observed.copy());
            receipt.setTag("terminal", state);
            receipt.setString(
                "scope",
                "actual production requester order and native link completion; output stock delta must be recorded by fixture owner");
            return receipt;
        }
    }

    private static void guard(EntityPlayerMP player) {
        require(Boolean.getBoolean("gtmb.v0115.acceptanceSmoke"), "explicit acceptance opt-in");
        require(
            player != null && player.worldObj instanceof WorldServer
                && player.worldObj.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-smoke-v0115-"),
            "owned isolated v0115 world");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("v0115 acceptance: " + message);
    }
}
