package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

/** Real reviewed jobs: mixed stock, repeated identities and atomic extra-resource shortages. */
public final class HologramPartialPlacementSmoke {

    private static final String PREFIX = "[GTMB-HOLOGRAM-PARTIAL-PLACEMENT] ";
    private static final String ADAPTER_ID = "gtmb:partial-placement-smoke";
    private static final UUID USER = UUID.fromString("f61fdc71-9df5-431f-bd98-99193673bea0");
    private static final int X = 416, Y = 84, SOURCE_Z = 608, TARGET_Z = 624;
    private final CaptureNetwork capture = new CaptureNetwork();
    private WorldServer world;
    private FakePlayer player;
    private int assertions;

    private HologramPartialPlacementSmoke() {}

    public static void run(WorldServer world) throws Exception {
        HologramPartialPlacementSmoke smoke = new HologramPartialPlacementSmoke();
        try {
            smoke.exercise(world);
            System.out.println(PREFIX + "FINAL PASS assertions=" + smoke.assertions);
        } catch (Throwable failure) {
            System.err.println(PREFIX + "FINAL FAIL " + failure);
            throw failure;
        }
    }

    private void exercise(WorldServer serverWorld) throws Exception {
        world = serverWorld;
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-smoke-"),
            "isolated_world_guard");
        player = FakePlayerFactory.get(world, new GameProfile(USER, "GTMBPartial"));
        player.inventory.mainInventory = new ItemStack[36];
        player.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        player.inventory.currentItem = 0;
        player.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        player.capabilities.allowEdit = true;
        player.setPosition(X + 3.5, Y, TARGET_Z - 1.5);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        HologramMaterials.write(player.getHeldItem(), settings);
        EmbeddedChannel transport = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        NetworkManager manager = new NetworkManager(false);
        for (Field field : NetworkManager.class.getDeclaredFields()) if (field.getType() == Channel.class) {
            field.setAccessible(true);
            field.set(manager, transport);
        }
        player.playerNetServerHandler = new NetHandlerPlayServer(MinecraftServer.getServer(), manager, player);
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previousChannel = channel.get(null);
        NativeExtraFixture adapter = new NativeExtraFixture();
        String presetId = null;
        boolean registered = false;
        HologramService.clear();
        channel.set(null, capture);
        try {
            HologramNativePlacement.register(ADAPTER_ID, adapter);
            registered = true;
            HologramPresets.Preset preset = fixture();
            HologramPresets.save(preset);
            presetId = preset.id;
            stock(1, new ItemStack(Blocks.chest));
            stock(2, new ItemStack(Blocks.stone));
            stock(3, new ItemStack(Blocks.cobblestone, 2));
            stock(4, new ItemStack(Blocks.furnace));
            HologramService.openAir(player);
            HologramSmokeTest.flushTransport();
            action("choosePlacementPreset", tag("presetId", preset.id));
            require(
                capture.state.getBoolean("moving") && capture.state.getInteger("missing") > 0,
                "mixed_blueprint_starts_only_unpaid_preview");
            NBTTagCompound position = new NBTTagCompound();
            position.setString("session", capture.state.getString("session"));
            position.setLong("planRevision", capture.state.getLong("planRevision"));
            position.setInteger("x", X);
            position.setInteger("y", Y);
            position.setInteger("z", TARGET_Z);
            position.setInteger("side", 1);
            HologramService.confirmWorldPosition(player, position);
            HologramSmokeTest.flushTransport();
            require(
                capture.state.getBoolean("virtualLocked") && capture.state.getBoolean("blueprintAwaitingConfirmation"),
                "real_position_confirmation_opens_locked_material_review");
            NBTTagCompound inspect = new NBTTagCompound();
            for (String key : new String[] { "main", "facing", "mode" })
                inspect.setInteger(key, capture.state.getInteger(key));
            inspect.setTag(
                "channels",
                capture.state.getCompoundTag("channels")
                    .copy());
            inspect.setInteger("scope", 2);
            inspect.setInteger("selected", 1);
            inspect.setIntArray("selectedIndices", new int[] { 1 });
            inspect.setInteger("layer", 0);
            action("configure", inspect);
            require(
                capture.state.getBoolean("wholeBlueprintPlacement") && capture.state.getInteger("missing") > 0,
                "inspecting_one_stocked_cell_cannot_hide_whole_blueprint_material_shortage");
            action("confirmBlueprintPlacement", new NBTTagCompound());
            require(
                capture.state.getInteger("job") != 1 && capture.state.getBoolean("forcePlacementReady"),
                "first_material_review_issues_second_confirmation_without_job");
            require(
                allAir() && stock(Blocks.chest) == 1
                    && stock(Blocks.stone) == 1
                    && stock(Blocks.cobblestone) == 2
                    && stock(Blocks.furnace) == 1,
                "first_confirmation_changes_neither_world_nor_any_stock");
            action("confirmBlueprintPlacement", new NBTTagCompound());
            require(
                capture.state.getInteger("job") != 1 && allAir(),
                "ordinary_duplicate_confirmation_cannot_force_construction");
            NBTTagCompound force = new NBTTagCompound();
            force.setBoolean("forceExplicit", true);
            action("confirmBlueprintPlacement", force);
            require(
                capture.state.getInteger("job") == 1 && capture.state.getBoolean("jobPartialPlacement")
                    && !capture.state.getBoolean("blueprintAwaitingConfirmation"),
                "second_explicit_review_schedules_actual_partial_job");
            settle();
            require(
                world.isAirBlock(X, Y, TARGET_Z) && world.getTileEntity(X, Y, TARGET_Z) == null,
                "unpaid_chest_contents_never_create_world_or_tile");
            require(
                world.getBlock(X + 1, Y, TARGET_Z) == Blocks.stone && world.isAirBlock(X + 3, Y, TARGET_Z),
                "shared_one_stone_stock_places_exactly_one_of_two_cells");
            require(
                world.getBlock(X + 2, Y, TARGET_Z) == Blocks.cobblestone
                    && world.getBlock(X + 5, Y, TARGET_Z) == Blocks.cobblestone
                    && world.isAirBlock(X + 4, Y, TARGET_Z),
                "missing_gold_does_not_block_other_fully_stocked_material");
            require(
                world.getBlock(X + 6, Y, TARGET_Z) == Blocks.chest
                    && ((TileEntityChest) world.getTileEntity(X + 6, Y, TARGET_Z)).getStackInSlot(0) == null,
                "failed_resource_reservation_returns_chest_for_later_plain_cell");
            require(
                world.isAirBlock(X + 7, Y, TARGET_Z) && world.getTileEntity(X + 7, Y, TARGET_Z) == null
                    && stock(Blocks.furnace) == 1
                    && stock(Items.diamond) == 0,
                "missing_registered_native_extra_never_generates_tile_or_consumes_base_material");
            require(
                stock(Blocks.stone) == 0 && stock(Blocks.cobblestone) == 0 && stock(Blocks.chest) == 0,
                "partial_job_consumes_each_available_block_once_without_global_reservation");
            require(
                capture.state.getInteger("job") == 5 && capture.state.getInteger("completed") == 4
                    && capture.state.getInteger("jobMissingCells") == 4
                    && capture.state.getInteger("jobSkippedCells") == 4,
                "partial_finish_receipt_reports_four_placed_and_four_shortage_skips");
            require(
                capture.state.getString("status")
                    .contains("4")
                    && capture.state.getString("status")
                        .contains("缺料"),
                "partial_job_explains_missing_material_in_player_summary");
            unchangedSource();

            stock(1, new ItemStack(Blocks.chest));
            stock(2, new ItemStack(Blocks.stone));
            stock(3, new ItemStack(Blocks.gold_block));
            stock(5, new ItemStack(Items.diamond, 3));
            action("scan", new NBTTagCompound());
            dumpCells("remaining-review");
            require(
                capture.state.getInteger("missing") == 0,
                "supplying_exact_remaining_base_and_extra_materials_clears_shortage");
            action("build", new NBTTagCompound());
            require(
                capture.state.getInteger("job") == 1,
                "reviewed_retry_builds_remaining_positions_without_a_second_generic_click");
            settle();
            dumpCells("remaining-finished");
            require(
                capture.state.getInteger("job") == 3 && capture.state.getInteger("completed") == 4
                    && capture.state.getInteger("jobMissingCells") == 0
                    && capture.state.getInteger("jobSkippedCells") == 0,
                "full_remaining_material_retry_finishes_four_remaining_cells");
            require(
                world.getBlock(X + 3, Y, TARGET_Z) == Blocks.stone
                    && world.getBlock(X + 4, Y, TARGET_Z) == Blocks.gold_block
                    && world.getBlock(X + 7, Y, TARGET_Z) == Blocks.furnace
                    && world.getTileEntity(X + 7, Y, TARGET_Z) != null,
                "paid_native_base_extra_and_other_remaining_cells_are_actually_placed");
            ItemStack restored = ((TileEntityChest) world.getTileEntity(X, Y, TARGET_Z)).getStackInSlot(0);
            require(
                restored != null && restored.getItem() == Items.diamond && restored.stackSize == 2,
                "paid_inventory_contents_restore_exact_original_count");
            require(
                stock(Blocks.chest) == 0 && stock(Blocks.stone) == 0
                    && stock(Blocks.gold_block) == 0
                    && stock(Blocks.furnace) == 0
                    && stock(Items.diamond) == 0,
                "retry_consumes_remaining_materials_and_all_three_extra_diamonds_exactly_once");
            unchangedSource();
        } finally {
            HologramService.clear();
            channel.set(null, previousChannel);
            try {
                if (registered) removeAdapter(adapter);
                if (presetId != null) HologramPresets.delete(presetId);
            } finally {
                transport.finish();
                Object pending;
                while ((pending = transport.readOutbound()) != null) io.netty.util.ReferenceCountUtil.release(pending);
            }
        }
    }

    private HologramPresets.Preset fixture() throws Exception {
        for (int offset = 0; offset < 8; offset++) {
            world.getChunkFromBlockCoords(X + offset, SOURCE_Z);
            world.getChunkFromBlockCoords(X + offset, TARGET_Z);
            require(world.isAirBlock(X + offset, Y, TARGET_Z), "fresh_destination_" + offset);
        }
        require(world.isAirBlock(X, Y, SOURCE_Z) && world.isAirBlock(X + 7, Y, SOURCE_Z), "fresh_source_tiles");
        world.setBlock(X, Y, SOURCE_Z, Blocks.chest, 2, 3);
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(X, Y, SOURCE_Z);
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 2));
        NBTTagCompound contents = new NBTTagCompound();
        chest.writeToNBT(contents);
        TileEntityChest plain = new TileEntityChest();
        NBTTagCompound plainPayload = new NBTTagCompound();
        plain.writeToNBT(plainPayload);
        world.setBlock(X + 7, Y, SOURCE_Z, Blocks.furnace, 2, 3);
        NBTTagCompound nativePayload = new NBTTagCompound();
        world.getTileEntity(X + 7, Y, SOURCE_Z)
            .writeToNBT(nativePayload);
        require(
            HologramNativePlacement.materials(Blocks.furnace, 2, nativePayload)
                .size() == 2,
            "registered_native_provider_declares_base_and_extra_real_items");
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.id = UUID.randomUUID()
            .toString();
        preset.schema = 2;
        preset.name = "Runtime mixed partial receipt";
        preset.controllerType = "area";
        preset.retainNbt = preset.retainCovers = true;
        preset.cells.add(cell(Blocks.chest, 0, 2, contents));
        preset.cells.add(cell(Blocks.stone, 1, 0, null));
        preset.cells.add(cell(Blocks.cobblestone, 2, 0, null));
        preset.cells.add(cell(Blocks.stone, 3, 0, null));
        preset.cells.add(cell(Blocks.gold_block, 4, 0, null));
        preset.cells.add(cell(Blocks.cobblestone, 5, 0, null));
        preset.cells.add(cell(Blocks.chest, 6, 2, plainPayload));
        preset.cells.add(cell(Blocks.furnace, 7, 2, nativePayload));
        return preset;
    }

    private static HologramPresets.Cell cell(Block block, int x, int metadata, NBTTagCompound payload)
        throws Exception {
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(block), x, 0, 0, -1, false);
        cell.block = Block.blockRegistry.getNameForObject(block);
        cell.metadata = metadata;
        cell.tileNbt = payload == null ? "" : HologramBlueprintNbt.encodeCaptured(payload);
        return cell;
    }

    private void unchangedSource() {
        ItemStack source = ((TileEntityChest) world.getTileEntity(X, Y, SOURCE_Z)).getStackInSlot(0);
        require(
            source != null && source.getItem() == Items.diamond
                && source.stackSize == 2
                && world.getBlock(X + 7, Y, SOURCE_Z) == Blocks.furnace,
            "source_inventory_and_native_tile_remain_untouched");
    }

    private void dumpCells(String phase) {
        net.minecraft.nbt.NBTTagList cells = capture.state.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound row = cells.getCompoundTagAt(i);
            int x = row.getInteger("x"), y = row.getInteger("y"), z = row.getInteger("z");
            TileEntity tile = world.getTileEntity(x, y, z);
            System.out.println(
                PREFIX + "CELL phase="
                    + phase
                    + " index="
                    + i
                    + " at="
                    + x
                    + ","
                    + y
                    + ","
                    + z
                    + " status="
                    + row.getString("status")
                    + " reason="
                    + row.getString("reason")
                    + " actualBlock="
                    + Block.blockRegistry.getNameForObject(world.getBlock(x, y, z))
                    + " actualMeta="
                    + world.getBlockMetadata(x, y, z)
                    + " actualTile="
                    + (tile == null ? "none"
                        : tile.getClass()
                            .getName()));
        }
    }

    private boolean allAir() {
        for (int offset = 0; offset < 8; offset++) if (!world.isAirBlock(X + offset, Y, TARGET_Z)) return false;
        return true;
    }

    private void stock(int slot, ItemStack stack) {
        player.inventory.mainInventory[slot] = stack;
    }

    private int stock(Block block) {
        return stock(Item.getItemFromBlock(block));
    }

    private int stock(Item item) {
        int count = 0;
        for (int slot = 1; slot < player.inventory.mainInventory.length; slot++) {
            ItemStack stack = player.inventory.mainInventory[slot];
            if (stack != null && stack.getItem() == item) count += stack.stackSize;
        }
        return count;
    }

    private static NBTTagCompound tag(String key, String value) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(key, value);
        return tag;
    }

    private void action(String op, NBTTagCompound fields) {
        require(capture.state != null, "server_receipt_before_" + op);
        NBTTagCompound action = (NBTTagCompound) fields.copy();
        action.setString("session", capture.state.getString("session"));
        action.setString("op", op);
        action.setLong("planRevision", capture.state.getLong("planRevision"));
        advance();
        HologramService.handle(player, action);
        HologramSmokeTest.flushTransport();
    }

    private void settle() {
        for (int tick = 0; tick < 128; tick++) {
            advance();
            HologramService.tick();
            HologramSmokeTest.flushTransport();
            if (capture.state.getInteger("job") != 1) return;
        }
        throw new AssertionError("bounded partial placement job timeout");
    }

    private void advance() {
        world.getWorldInfo()
            .incrementTotalWorldTime(world.getTotalWorldTime() + 20);
    }

    @SuppressWarnings("unchecked")
    private static void removeAdapter(HologramNativePlacement.Adapter expected) throws Exception {
        Field field = HologramNativePlacement.class.getDeclaredField("ADAPTERS");
        field.setAccessible(true);
        synchronized (HologramNativePlacement.class) {
            Map<String, HologramNativePlacement.Adapter> adapters = (Map<String, HologramNativePlacement.Adapter>) field
                .get(null);
            if (adapters.get(ADAPTER_ID) != expected)
                throw new AssertionError("temporary native adapter ownership changed");
            adapters.remove(ADAPTER_ID);
        }
    }

    private void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(
            label + " job="
                + (capture.state == null ? -1 : capture.state.getInteger("job"))
                + " status="
                + (capture.state == null ? "none" : capture.state.getString("status")));
        assertions++;
        System.out.println(PREFIX + "PASS " + label);
    }

    private static final class NativeExtraFixture implements HologramNativePlacement.Adapter {

        public boolean supports(Block block) {
            return block == Blocks.furnace;
        }

        public TileEntity create(Block block, int metadata, NBTTagCompound tag, World detachedWorld) {
            TileEntityFurnace tile = new TileEntityFurnace();
            tile.readFromNBT(tag);
            tile.setWorldObj(detachedWorld);
            return tile;
        }

        public List<ItemStack> materials(Block block, int metadata, TileEntity tile) {
            List<ItemStack> costs = new ArrayList<>();
            costs.add(new ItemStack(Blocks.furnace));
            costs.add(new ItemStack(Items.diamond));
            return costs;
        }
    }

    private static final class CaptureNetwork extends SimpleNetworkWrapper {

        NBTTagCompound state;
        private final HologramSnapshotAssembly assembly = new HologramSnapshotAssembly();

        CaptureNetwork() {
            super("gtmb_partial_observer");
        }

        public void sendTo(IMessage message, EntityPlayerMP recipient) {
            if (!USER.equals(recipient.getUniqueID())) return;
            NBTTagCompound next = HologramSmokeTest.decodeState(message, assembly);
            if (next != null) state = next;
        }
    }
}
