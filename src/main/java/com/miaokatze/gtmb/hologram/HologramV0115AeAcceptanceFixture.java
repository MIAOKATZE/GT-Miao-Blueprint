package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.util.ProbeChunkTickets;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.PlayerSource;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPatternTerminal;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.StorageName;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.misc.TileInterface;
import appeng.util.item.AEItemStack;

/** Opt-in finite native AE network. The runner calls poll() on natural server END ticks, then close(). */
public final class HologramV0115AeAcceptanceFixture implements AutoCloseable {

    private static final int X = 1200, Y = 100, Z = 1200;
    private final WorldServer world;
    private final EntityPlayerMP actor;
    private final HologramCompositeLabBank.Bank bank;
    private final TileEntity controller;
    private final TileInterface iface;
    private final ForgeChunkManager.Ticket ticket;
    private final NBTTagCompound receipt = new NBTTagCompound();
    private final long started;
    private HologramV0115AcceptanceSmoke.NativeOrderWitness witness;
    private long lastTick = -1;
    private boolean supplied, finished;
    private ItemStack nativeRecipeOutput;
    private int expectedInput;

    public static HologramV0115AeAcceptanceFixture create(WorldServer world, EntityPlayerMP actor) throws Exception {
        require(
            Boolean.getBoolean("gtmb.v0115.acceptanceSmoke") && actor.worldObj == world
                && world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-smoke-v0115-"),
            "explicit isolated fixture opt-in");
        return new HologramV0115AeAcceptanceFixture(world, actor);
    }

    private HologramV0115AeAcceptanceFixture(WorldServer world, EntityPlayerMP actor) throws Exception {
        this.world = world;
        this.actor = actor;
        started = world.getTotalWorldTime();
        ProbeChunkTickets.ensureRegistered();
        ticket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
        require(ticket != null, "native Forge ticket admitted");
        ticket.getModData()
            .setBoolean("gtmbNetworkProbe", true);
        for (int cx = (X - 1) >> 4; cx <= (X + 5) >> 4; cx++) {
            world.theChunkProviderServer.loadChunk(cx, Z >> 4);
            ForgeChunkManager.forceChunk(ticket, new ChunkCoordIntPair(cx, Z >> 4));
        }
        actor.setPosition(X + .5, Y, Z + 2.5);
        controller = place(
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get(),
            X,
            Y,
            Z);
        bank = HologramCompositeLabBank.create(world, actor, X, Y, Z);
        // A rectangular 2x1x1 native CPU cluster. Both units connect to the controller through real shared faces.
        place(
            AEApi.instance()
                .definitions()
                .blocks()
                .craftingStorage1k()
                .maybeStack(1)
                .get(),
            X + 1,
            Y,
            Z);
        place(
            AEApi.instance()
                .definitions()
                .blocks()
                .craftingAccelerator()
                .maybeStack(1)
                .get(),
            X + 2,
            Y,
            Z);
        iface = (TileInterface) place(
            AEApi.instance()
                .definitions()
                .blocks()
                .iface()
                .maybeStack(1)
                .get(),
            X,
            Y + 1,
            Z);
        place(
            AEApi.instance()
                .definitions()
                .blocks()
                .molecularAssembler()
                .maybeStack(1)
                .get(),
            X + 1,
            Y + 1,
            Z);
        ItemStack encoded = encode();
        require(encoded.getItem() instanceof ICraftingPatternItem, "native encoder produced a registered pattern");
        ICraftingPatternDetails details = ((ICraftingPatternItem) encoded.getItem()).getPatternForItem(encoded, world);
        require(
            details != null && details.isCraftable()
                && details.getCondensedAEInputs().length == 1
                && details.getCondensedAEOutputs().length == 1,
            "native encoder produced a valid one-input one-output crafting pattern");
        require(
            details.getCondensedAEInputs()[0] instanceof IAEItemStack
                && details.getCondensedAEOutputs()[0] instanceof IAEItemStack
                && details.getCondensedAEInputs()[0].getStackSize() == 1
                && ((IAEItemStack) details.getCondensedAEInputs()[0]).getItemStack()
                    .isItemEqual(new ItemStack(Blocks.log))
                && ItemStack.areItemStacksEqual(
                    ((IAEItemStack) details.getCondensedAEOutputs()[0]).getItemStack(),
                    nativeRecipeOutput),
            "encoded native input and output equal the actual loaded crafting recipe");
        iface.getInterfaceDuality()
            .getPatterns()
            .setInventorySlotContents(0, encoded);
        iface.markDirty();
        receipt.setTag("encodedPattern", encoded.writeToNBT(new NBTTagCompound()));
        receipt.setString("status", "WAITING_NATIVE_NETWORK");
        receipt.setBoolean("syntheticGrid", false);
        receipt.setBoolean("manualTileTicks", false);
        receipt.setDouble("initialFiniteAE", 1_000_000);
    }

    /** No blocking waits. AE nodes, cluster formation, crafting calculations and machine work tick naturally. */
    public NBTTagCompound poll() throws Exception {
        if (finished) return (NBTTagCompound) receipt.copy();
        long now = world.getTotalWorldTime();
        if (now == lastTick) return (NBTTagCompound) receipt.copy();
        lastTick = now;
        receipt.setLong("nativeWorldTicks", now - started);
        require(now - started < 2400, "native fixture deadline expired: " + receipt);
        if (!bank.ready()) return (NBTTagCompound) receipt.copy();
        IGrid grid = (IGrid) bank.nativeGrid();
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        receipt.setInteger(
            "nativeCpus",
            crafting.getCpus()
                .size());
        if (crafting.getCpus()
            .isEmpty()
            || !iface.getProxy()
                .isActive())
            return (NBTTagCompound) receipt.copy();
        if (!supplied) {
            bank.bindTerminal();
            require(
                HologramAeCraftingOrders.available(actor, new HologramMaterials.Settings()),
                "actual wireless terminal CRAFT/EXTRACT permissions and range");
            IMEMonitor<IAEItemStack> items = items();
            require(
                amount(items, new ItemStack(Blocks.log)) == 0 && amount(items, nativeRecipeOutput) == 0,
                "native cells start with no input or output");
            IAEItemStack input = AEItemStack.create(new ItemStack(Blocks.log, expectedInput));
            require(
                items.injectItems(input, Actionable.MODULATE, new PlayerSource(actor, iface)) == null,
                "runtime recipe input physically accepted in real ME cells");
            long inputBefore = amount(items, new ItemStack(Blocks.log));
            require(inputBefore == expectedInput, "native input stock equals runtime recipe demand");
            NBTTagCompound quote = new NBTTagCompound();
            quote.setString("session", "native-ae-fixture");
            quote.setBoolean("complete", true);
            NBTTagList rows = new NBTTagList();
            rows.appendTag(HologramMaterialQuote.itemRow(nativeRecipeOutput, 64, 0));
            quote.setTag("rows", rows);
            receipt.setLong("inputBefore", inputBefore);
            receipt.setLong("outputBefore", 0);
            receipt.setTag("bankBefore", bank.receipt());
            witness = HologramV0115AcceptanceSmoke.startNativeOrder(actor, quote);
            supplied = true;
        }
        NBTTagCompound observed = witness.observe();
        receipt.setTag("order", observed);
        receipt.setString("status", observed.getString("status"));
        if ("RUNNING".equals(observed.getString("status"))) return (NBTTagCompound) receipt.copy();
        long inputAfter = amount(items(), new ItemStack(Blocks.log));
        long outputAfter = amount(items(), nativeRecipeOutput);
        receipt.setLong("inputAfter", inputAfter);
        receipt.setLong("outputAfter", outputAfter);
        receipt.setTag("bankAfter", bank.receipt());
        require(
            "PASS".equals(observed.getString("status")) && inputAfter == 0 && outputAfter == 64,
            "production order completed and runtime recipe input is exhausted into exactly sixty-four native planks");
        NBTTagCompound terminal = observed.getCompoundTag("terminal");
        require(
            terminal.hasKey("pendingDelivery", 4) && terminal.getLong("pendingDelivery") == 0,
            "production delivery receipts cleared before fixture success");
        receipt.setString("status", "PASS");
        finished = true;
        return (NBTTagCompound) receipt.copy();
    }

    private IMEMonitor<IAEItemStack> items() throws Exception {
        return ((IGridProxyable) controller).getProxy()
            .getStorage()
            .getItemInventory();
    }

    private long amount(IMEMonitor<IAEItemStack> items, ItemStack sample) {
        IAEItemStack request = AEItemStack.create(sample);
        request.setStackSize(Long.MAX_VALUE);
        IAEItemStack found = items.extractItems(request, Actionable.SIMULATE, new PlayerSource(actor, iface));
        return found == null ? 0 : found.getStackSize();
    }

    private TileEntity place(ItemStack stack, int x, int y, int z) {
        Block block = Block.getBlockFromItem(stack.getItem());
        int metadata = stack.getItemDamage();
        require(world.isAirBlock(x, y, z) && world.setBlock(x, y, z, block, metadata, 3), "fresh registered AE block");
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null) {
            tile = block.createTileEntity(world, metadata);
            if (tile == null && block instanceof appeng.block.AEBaseTileBlock)
                tile = ((appeng.block.AEBaseTileBlock) block).createNewTileEntity(world, metadata);
            require(tile != null, "registered native AE factory");
            world.setTileEntity(x, y, z, tile);
        }
        require(tile instanceof IGridProxyable && !tile.isInvalid(), "actual native AE tile");
        ((IGridProxyable) tile).getProxy()
            .setOwner(actor);
        tile.markDirty();
        return tile;
    }

    private ItemStack encode() throws Exception {
        InventoryCrafting table = new InventoryCrafting(new appeng.container.ContainerNull(), 3, 3);
        table.setInventorySlotContents(0, new ItemStack(Blocks.log));
        ItemStack matched = CraftingManager.getInstance()
            .findMatchingRecipe(table, world);
        require(
            matched != null && matched.getItem() == Item.getItemFromBlock(Blocks.planks)
                && matched.stackSize > 0
                && 64 % matched.stackSize == 0,
            "actual loaded one-log recipe must produce a positive integral divisor of sixty-four planks");
        nativeRecipeOutput = matched.copy();
        expectedInput = 64 / nativeRecipeOutput.stackSize;
        receipt.setLong("recipeOutputPerLog", nativeRecipeOutput.stackSize);
        receipt.setLong("expectedInput", expectedInput);
        receipt.setTag("nativeRecipeOutput", nativeRecipeOutput.writeToNBT(new NBTTagCompound()));
        final int x = X + 5;
        ItemStack cable = AEApi.instance()
            .definitions()
            .parts()
            .cableGlass()
            .stack(appeng.api.util.AEColor.Transparent, 1);
        ItemStack terminal = AEApi.instance()
            .definitions()
            .parts()
            .patternTerminal()
            .maybeStack(1)
            .get();
        ItemStack held = actor.getHeldItem();
        try {
            actor.inventory.setInventorySlotContents(actor.inventory.currentItem, cable);
            require(
                appeng.parts.PartPlacement.placeItemPart(cable, actor, world, x, Y, Z, ForgeDirection.UP),
                "native encoder cable");
            actor.inventory.setInventorySlotContents(actor.inventory.currentItem, terminal);
            require(
                appeng.parts.PartPlacement.placeItemPart(terminal, actor, world, x, Y, Z, ForgeDirection.NORTH),
                "native encoder terminal");
        } finally {
            actor.inventory.setInventorySlotContents(actor.inventory.currentItem, held);
        }
        IPartHost host = appeng.parts.PartPlacement.getExistingHost(world.getTileEntity(x, Y, Z));
        require(
            host != null && host.getPart(ForgeDirection.NORTH) instanceof IPatternTerminal,
            "actual pattern terminal");
        IPatternTerminal encoder = (IPatternTerminal) host.getPart(ForgeDirection.NORTH);
        encoder.setCraftingRecipe(true);
        encoder.setSubstitution(false);
        encoder.setCanBeSubstitution(false);
        encoder.getAEInventoryByName(StorageName.CRAFTING_INPUT)
            .putAEStackInSlot(0, AEItemStack.create(new ItemStack(Blocks.log)));
        encoder.getAEInventoryByName(StorageName.CRAFTING_OUTPUT)
            .putAEStackInSlot(0, AEItemStack.create(nativeRecipeOutput.copy()));
        IInventory slots = encoder.getInventoryByName("pattern");
        slots.setInventorySlotContents(
            0,
            AEApi.instance()
                .definitions()
                .materials()
                .blankPattern()
                .maybeStack(1)
                .get());
        require(encoder.encode(null, null, null, actor.getCommandSenderName(), world), "actual native crafting encode");
        require(slots.getStackInSlot(0) == null && slots.getStackInSlot(1) != null, "native encoder consumed blank");
        return slots.getStackInSlot(1)
            .copy();
    }

    @Override
    public void close() {
        HologramAeCraftingOrders.cancel(actor, "Native acceptance fixture closed");
        ForgeChunkManager.releaseTicket(ticket);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("v0115 native AE: " + description);
    }
}
