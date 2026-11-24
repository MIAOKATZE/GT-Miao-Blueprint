// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

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
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.miaokatze.gtmb.util.ProbeChunkTickets;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.ISecurityGrid;
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

    public static final String OWNED_ORDER = "gtmb.testlab.issue100OwnedAeOrder";
    private static final UUID HOST_ACTOR = UUID.nameUUIDFromBytes("OfflinePlayer:MiaoHostQA".getBytes(StandardCharsets.UTF_8));
    private static final int TERMINAL_SLOT = 34;
    private final int x, y, z;
    private final boolean owned;
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
    private int expectedInput, outputCount, toolSlot;
    private ItemStack fixtureTool, fixtureTerminal;
    private IGrid fixtureGrid;
    private String reviewRequest = "", orderSession = "", orderId = "";
    private long orderRevision, orderNonce, orderSequence;
    private boolean sawPlanning, sawCrafting, sawLink, retired;

    public static HologramV0115AeAcceptanceFixture create(WorldServer world, EntityPlayerMP actor) throws Exception {
        require(
            Boolean.getBoolean("gtmb.v0115.acceptanceSmoke") && actor.worldObj == world
                && world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-smoke-v0115-"),
            "explicit isolated fixture opt-in");
        return new HologramV0115AeAcceptanceFixture(world, actor, false, 1200, 100, 1200);
    }

    /** Called only after Issue100 verified the exact stock lease, marker and Core world policy. */
    public static HologramV0115AeAcceptanceFixture createOwned(WorldServer world, EntityPlayerMP actor) throws Exception {
        require(Boolean.getBoolean(OWNED_ORDER) && Boolean.getBoolean("miaotest.host.environment")
            && "gtmb".equals(System.getProperty("miaotest.host.module"))
            && "client-ui".equals(System.getProperty("miaotest.host.profile"))
            && Boolean.getBoolean("gtmb.testlab.tutorialIssue100")
            && world.getSaveHandler().getWorldDirectory().toPath().toRealPath()
                .equals(Paths.get(System.getProperty("miaotest.host.worldPath")).toRealPath()),
            "exact canonical private integrated world");
        requireOwnedActor(world, actor);
        requireNoOrderingExtension();
        int x = MathHelper.floor_double(actor.posX) + 1, y = MathHelper.floor_double(actor.posY) + 3;
        int z = MathHelper.floor_double(actor.posZ) + 2;
        require(actor.getHeldItem() == null && actor.inventory.mainInventory[TERMINAL_SLOT] == null
            && actor.inventory.currentItem != TERMINAL_SLOT && HologramWorkspace.find(actor.getUniqueID()) == null
            && !HologramAeCraftingOrders.active(actor), "fresh actor with independent empty fixture slots");
        for (int[] cell : new int[][] { { -1, -1, 0 }, { -1, 0, 0 }, { -1, 1, 0 }, { 0, -1, 0 },
            { 0, 0, 0 }, { 1, 0, 0 }, { 2, 0, 0 }, { 0, 1, 0 }, { 1, 1, 0 }, { 5, 0, 0 } })
            ownedAir(world, actor, x + cell[0], y + cell[1], z + cell[2]);
        return new HologramV0115AeAcceptanceFixture(world, actor, true, x, y, z);
    }

    private static void requireOwnedActor(WorldServer world, EntityPlayerMP actor) {
        require(actor != null && actor.worldObj == world && actor.dimension == 0 && !actor.isDead
            && !(actor instanceof FakePlayer) && HOST_ACTOR.equals(actor.getUniqueID()) && world.playerEntities.size() == 1
            && "MiaoHostQA".equals(actor.getCommandSenderName()) && !actor.capabilities.isCreativeMode
            && actor.capabilities.allowEdit && !actor.canCommandSenderUseCommand(2, "give")
            && HologramNetwork.progressConnected(actor) && actor.playerNetServerHandler.netManager.isLocalChannel(),
            "one exact connected local survival non-OP fixture actor");
    }

    private static void ownedAir(WorldServer world, EntityPlayerMP actor, int x, int y, int z) {
        require(world.blockExists(x, y, z) && world.isAirBlock(x, y, z) && world.getTileEntity(x, y, z) == null
            && world.canMineBlock(actor, x, y, z) && actor.canPlayerEdit(x, y, z, 1, actor.getHeldItem()),
            "already loaded, editable, empty fixture voxel");
    }

    private HologramV0115AeAcceptanceFixture(WorldServer world, EntityPlayerMP actor, boolean owned, int x, int y, int z)
        throws Exception {
        this.world = world;
        this.actor = actor;
        this.owned = owned;
        this.x = x;
        this.y = y;
        this.z = z;
        started = world.getTotalWorldTime();
        if (owned) {
            ticket = null; // Stock host owns loaded chunks. Never allocate a ticket or reposition the actor.
        } else {
            ProbeChunkTickets.ensureRegistered();
            ticket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
            require(ticket != null, "native Forge ticket admitted");
            ticket.getModData().setBoolean("gtmbNetworkProbe", true);
            for (int cx = (x - 1) >> 4; cx <= (x + 5) >> 4; cx++) {
                world.theChunkProviderServer.loadChunk(cx, z >> 4);
                ForgeChunkManager.forceChunk(ticket, new ChunkCoordIntPair(cx, z >> 4));
            }
            actor.setPosition(x + .5, y, z + 2.5);
        }
        controller = place(
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get(),
            x,
            y,
            z);
        bank = HologramCompositeLabBank.create(world, actor, x, y, z);
        // A rectangular 2x1x1 native CPU cluster. Both units connect to the controller through real shared faces.
        place(
            AEApi.instance()
                .definitions()
                .blocks()
                .craftingStorage1k()
                .maybeStack(1)
                .get(),
            x + 1,
            y,
            z);
        place(
            AEApi.instance()
                .definitions()
                .blocks()
                .craftingAccelerator()
                .maybeStack(1)
                .get(),
            x + 2,
            y,
            z);
        iface = (TileInterface) place(
            AEApi.instance()
                .definitions()
                .blocks()
                .iface()
                .maybeStack(1)
                .get(),
            x,
            y + 1,
            z);
        place(
            AEApi.instance()
                .definitions()
                .blocks()
                .molecularAssembler()
                .maybeStack(1)
                .get(),
            x + 1,
            y + 1,
            z);
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
        if (owned) requireOwnedActor(world, actor);
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
            if (owned) require(actor.getHeldItem() == null && actor.inventory.mainInventory[TERMINAL_SLOT] == null,
                "fixture slots remained empty until admission");
            bank.bindTerminal();
            if (owned) fixtureTerminal = actor.inventory.mainInventory[TERMINAL_SLOT];
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
            receipt.setLong("inputBefore", inputBefore);
            receipt.setLong("outputBefore", 0);
            receipt.setTag("bankBefore", bank.receipt());
            supplied = true;
            if (owned) {
                openOwnedReview(grid);
                return (NBTTagCompound) receipt.copy();
            }
            NBTTagCompound quote = new NBTTagCompound();
            quote.setString("session", "native-ae-fixture");
            quote.setBoolean("complete", true);
            NBTTagList rows = new NBTTagList();
            rows.appendTag(HologramMaterialQuote.itemRow(nativeRecipeOutput, 64, 0));
            quote.setTag("rows", rows);
            witness = HologramV0115AcceptanceSmoke.startNativeOrder(actor, quote);
        }
        if (owned) return pollOwnedReview(grid);
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

    private void openOwnedReview(IGrid grid) throws Exception {
        fixtureGrid = grid;
        requireGridAuthority(grid);
        int[] anchor = { x - 1, y - 1, z - 4 };
        require(HologramSessionPolicy.previewReach(actor, anchor[0], anchor[1], anchor[2]), "owned review anchor in reach");
        String name = Item.itemRegistry.getNameForObject(nativeRecipeOutput.getItem());
        require("minecraft:planks".equals(name) && !nativeRecipeOutput.hasTagCompound() && outputCount > 0
            && outputCount <= 8, "one actual plain planks recipe batch");
        int damage = nativeRecipeOutput.getItemDamage();
        ArrayList<HologramProjectedPlacement.Cell> cells = new ArrayList<>();
        for (int i = 0; i < outputCount; i++) {
            int dx = i % 4, dz = i / 4;
            ownedAir(world, actor, anchor[0] + dx, anchor[1], anchor[2] + dz);
            cells.add(new HologramProjectedPlacement.Cell(dx, 0, dz, name, damage, name, damage, -1));
        }
        toolSlot = actor.inventory.currentItem;
        fixtureTool = new ItemStack(ProjectorRegistry.projector);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(HologramSessionKeys.TOOL_ID, UUID.randomUUID().toString());
        fixtureTool.setTagCompound(tag);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.main = settings.containers = false;
        settings.me = true;
        HologramMaterials.write(fixtureTool, settings);
        actor.inventory.setInventorySlotContents(toolSlot, fixtureTool);
        actor.inventory.markDirty();
        UUID id = UUID.randomUUID();
        reviewRequest = id.toString();
        HologramProjectedPlacement.Plan plan = new HologramProjectedPlacement.Plan(cells,
            Collections.singletonList(new HologramProjectedPlacement.Need(name, damage, outputCount)));
        // Real v2 server acceptance/review, not an authored quote, fake State or packet/UI witness.
        HologramProjectedPlacementService.receive(actor, HologramPlacementRequest.encode(id, anchor, plan));
        require(HologramProjectedPlacementService.preparing(actor), "real v2 preparation accepted the exact actor");
        receipt.setString("request", reviewRequest);
        receipt.setString("entry", "server-side v2 review and real orderMissingMaterials; no GUI/FML proof");
        receipt.setString("status", "WAITING_AUTHORITATIVE_REVIEW");
    }

    private static void requireNoOrderingExtension() throws ReflectiveOperationException {
        // Validate absence without calling an arbitrary provider's availability hook.
        java.lang.reflect.Field provider = HologramMaterialOrdering.class.getDeclaredField("provider");
        provider.setAccessible(true);
        require(provider.get(null) == null, "built-in AE order service only");
    }

    private void requireGridAuthority(IGrid grid) throws ReflectiveOperationException {
        requireNoOrderingExtension();
        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        require(grid == fixtureGrid && crafting != null && crafting.getClass().getName().startsWith("appeng.")
            && !java.lang.reflect.Proxy.isProxyClass(crafting.getClass())
            && security != null && security.hasPermission(actor, SecurityPermissions.CRAFT)
            && security.hasPermission(actor, SecurityPermissions.EXTRACT)
            && security.hasPermission(actor, SecurityPermissions.INJECT), "same real native grid permissions");
        receipt.setString("craftingCacheClass", crafting.getClass().getName());
    }

    private NBTTagCompound pollOwnedReview(IGrid grid) throws Exception {
        require(actor.inventory.currentItem == toolSlot && actor.getHeldItem() == fixtureTool
            && actor.inventory.mainInventory[TERMINAL_SLOT] == fixtureTerminal, "exact fixture still owns its two slots");
        HologramSession s = HologramWorkspace.find(actor.getUniqueID());
        if (s == null) {
            require(HologramProjectedPlacementService.preparing(actor), "v2 preparation ended without a reviewed workspace");
            return (NBTTagCompound) receipt.copy();
        }
        require(s.player == actor && reviewRequest.equals(s.executionRequestId) && s.job == 0
            && !s.jobVoucherRequested, "same unbuilt reviewed workspace");
        if (orderId.isEmpty()) {
            if (!s.placementReviewIssued || !s.materialQuote.getBoolean("complete")) return (NBTTagCompound) receipt.copy();
            requireGridAuthority(grid);
            NBTTagCompound quote = s.materialQuote;
            NBTTagList rows = quote.getTagList("rows", 10);
            NBTTagCompound row = rows.getCompoundTagAt(0);
            ItemStack exact = nativeRecipeOutput.copy();
            exact.stackSize = 1;
            require(s.id.equals(quote.getString("session")) && s.planRevision == quote.getLong("planRevision")
                && s.placementReviewNonce == quote.getLong("placementReviewNonce") && rows.tagCount() == 1
                && "item".equals(row.getString("kind")) && ItemStack.areItemStacksEqual(exact,
                    HologramMaterialQuote.readItem(row))
                && row.getLong("required") == outputCount && row.getLong("missing") == outputCount
                && amount(items(), nativeRecipeOutput) == 0 && amount(items(), new ItemStack(Blocks.log)) == 1,
                "exact authoritative shortage and zero native output before explicit order");
            NBTTagCompound action = new NBTTagCompound();
            action.setString("op", "orderMissingMaterials");
            action.setString("session", orderSession = s.id);
            action.setLong("planRevision", orderRevision = s.planRevision);
            action.setLong("placementReviewNonce", orderNonce = s.placementReviewNonce);
            action.setLong("uiSequence", orderSequence = s.uiSequence + 1);
            receipt.setTag("serverQuote", quote.copy());
            require(HologramMaterialWorkflow.action(s, action, "orderMissingMaterials")
                && HologramAeCraftingOrders.matchesActiveOrder(s, action), "production order accepted: " + s.status);
            orderId = HologramAeCraftingOrders.snapshot(actor).getString("orderId");
            require(!orderId.isEmpty(), "real native order identity");
        }
        NBTTagCompound order = HologramAeCraftingOrders.snapshot(actor);
        require(orderSession.equals(s.id) && orderRevision == s.planRevision && orderNonce == s.placementReviewNonce
            && orderId.equals(order.getString("orderId")) && orderRevision == order.getLong("planRevision")
            && orderNonce == order.getLong("placementReviewNonce") && orderSequence == order.getLong("orderUiSequence")
            && order.getInteger("totalRows") == 1 && order.getLong("total") == outputCount
            && grid == fixtureGrid, "the production order retains one exact scoped row and native grid");
        String phase = order.getString("state");
        sawPlanning |= "planning".equals(phase);
        sawCrafting |= "crafting".equals(phase);
        sawLink |= !order.getString("craftingId").isEmpty() && order.hasKey("standalone", 1)
            && !order.getBoolean("standalone");
        receipt.setTag("order", order);
        receipt.setString("status", phase);
        receipt.setBoolean("sawPlanning", sawPlanning);
        receipt.setBoolean("sawCrafting", sawCrafting);
        receipt.setBoolean("sawNativeRequesterLink", sawLink);
        if (order.getBoolean("active")) return (NBTTagCompound) receipt.copy();
        long inputAfter = amount(items(), new ItemStack(Blocks.log)), outputAfter = amount(items(), nativeRecipeOutput);
        receipt.setLong("inputAfter", inputAfter);
        receipt.setLong("outputAfter", outputAfter);
        require("complete".equals(phase) && sawPlanning && sawCrafting && sawLink && order.getLong("done") == outputCount
            && order.getLong("pendingDelivery") == 0 && inputAfter == 0 && outputAfter == outputCount,
            "native one-batch terminal receipt and exact stock delta: " + order);
        for (HologramCapture.Cell cell : s.capture.cells) ownedAir(world, actor, cell.x, cell.y, cell.z);
        receipt.setString("status", "PASS");
        finished = true;
        return (NBTTagCompound) receipt.copy();
    }

    private IMEMonitor<IAEItemStack> items() throws Exception {
        return ((IGridProxyable) controller).getProxy()
            .getStorage()
            .getItemInventory();
    }

    /** Keep this naturally initialized native network alive for the opt-in combined quote/snapshot contract. */
    public HologramV0116AeQuoteAcceptanceSmoke quoteAcceptance() throws Exception {
        require(
            finished && "PASS".equals(receipt.getString("status")),
            "native crafting must pass before combined quote");
        return HologramV0116AeQuoteAcceptanceSmoke.create(world, actor, bank, items(), new PlayerSource(actor, iface));
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
        outputCount = owned ? nativeRecipeOutput.stackSize : 64;
        expectedInput = outputCount / nativeRecipeOutput.stackSize;
        receipt.setLong("recipeOutputPerLog", nativeRecipeOutput.stackSize);
        receipt.setLong("expectedInput", expectedInput);
        receipt.setTag("nativeRecipeOutput", nativeRecipeOutput.writeToNBT(new NBTTagCompound()));
        final int encoderX = x + 5;
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
                appeng.parts.PartPlacement.placeItemPart(cable, actor, world, encoderX, y, z, ForgeDirection.UP),
                "native encoder cable");
            actor.inventory.setInventorySlotContents(actor.inventory.currentItem, terminal);
            require(
                appeng.parts.PartPlacement.placeItemPart(terminal, actor, world, encoderX, y, z, ForgeDirection.NORTH),
                "native encoder terminal");
        } finally {
            actor.inventory.setInventorySlotContents(actor.inventory.currentItem, held);
        }
        IPartHost host = appeng.parts.PartPlacement.getExistingHost(world.getTileEntity(encoderX, y, z));
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
        if (retired) return;
        if (owned) {
            NBTTagCompound order = HologramAeCraftingOrders.snapshot(actor);
            boolean sameOrder = !orderId.isEmpty() && orderId.equals(order.getString("orderId"));
            if (sameOrder) HologramAeCraftingOrders.cancel(actor, "Owned acceptance order retired");
            if (!reviewRequest.isEmpty()) HologramProjectedPlacementService.cancel(actor, reviewRequest);
            boolean exact = (fixtureTool == null || actor.inventory.mainInventory[toolSlot] == fixtureTool)
                && (fixtureTerminal == null || actor.inventory.mainInventory[TERMINAL_SLOT] == fixtureTerminal);
            if (fixtureTool != null && actor.inventory.mainInventory[toolSlot] == fixtureTool)
                actor.inventory.setInventorySlotContents(toolSlot, null);
            if (fixtureTerminal != null && actor.inventory.mainInventory[TERMINAL_SLOT] == fixtureTerminal)
                actor.inventory.setInventorySlotContents(TERMINAL_SLOT, null);
            actor.inventory.markDirty();
            retired = true;
            require(exact && !HologramAeCraftingOrders.active(actor) && !HologramProjectedPlacementService.preparing(actor),
                "owned order/review retired without overwriting foreign slots");
        } else {
            HologramAeCraftingOrders.cancel(actor, "Native acceptance fixture closed");
            ForgeChunkManager.releaseTicket(ticket);
            retired = true;
        }
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("v0115 native AE: " + description);
    }
}
