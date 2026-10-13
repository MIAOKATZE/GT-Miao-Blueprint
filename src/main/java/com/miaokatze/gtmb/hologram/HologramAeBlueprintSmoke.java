package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.definitions.IItemDefinition;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.PlayerSource;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.api.parts.PartItemStack;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.data.IAEItemStack;
import appeng.helpers.IInterfaceHost;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.me.storage.ItemCellInventory;
import appeng.tile.AEBaseInvTile;
import appeng.tile.networking.TileCableBus;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Native FML v005 suite. Requires the reviewed AE adapter and the actual Service resource-plan hooks. */
public final class HologramAeBlueprintSmoke {

    private static final String PREFIX = "[GTMB-HOLOGRAM-AE-BLUEPRINT] ";
    private static int checks;
    private static final List<RunState> pendingRuns = new ArrayList<>();

    private HologramAeBlueprintSmoke() {}

    /** Shared native sources for the UI/GL harness. No source is produced by blueprint sanitization. */
    public static final class Fixtures {

        public final EntityPlayerMP actor;
        public final Block drive, iface, cableBus, multipart;
        public final int[] sourceX;
        public final int y, z, sourceCount;
        private final WorldServer world;

        private Fixtures(WorldServer world, EntityPlayerMP actor, Block[] blocks, int anchorX, int y, int z) {
            this.world = world;
            this.actor = actor;
            drive = blocks[0];
            iface = blocks[1];
            cableBus = blocks[2];
            multipart = blocks[3];
            sourceCount = multipart == null ? 3 : 4;
            sourceX = new int[] { anchorX, anchorX + 4, anchorX + 8, anchorX + 12 };
            this.y = y;
            this.z = z;
        }

        public Block block(int index) {
            return new Block[] { drive, iface, cableBus, multipart }[index];
        }

        public TileEntity sourceTile(int index) {
            return world.getTileEntity(sourceX[index], y, z);
        }

        public NBTTagCompound captured(int index) throws IOException {
            return HologramNativeCapture.capture(sourceTile(index));
        }

        public ItemStack sample(int index) throws IOException {
            return HologramAeBlueprint.sampleIdentity(block(index), 0, captured(index));
        }

        /** Stable inventory-only observation for UI epochs, independent of natural network/tick caches. */
        public NBTTagCompound sourceInventory(int index) throws IOException {
            NBTTagCompound raw = captured(index);
            if (index == 2) raw = raw.getCompoundTag("extra:2");
            else if (index == 3) raw = raw.getTagList("parts", 10)
                .getCompoundTagAt(aePartIndex())
                .getCompoundTag("extra:2");
            NBTTagCompound result = new NBTTagCompound();
            for (String key : Arrays.asList("inv", "config", "patterns", "storage", "upgrades", "waitingToSend"))
                if (raw.hasKey(key)) result.setTag(
                    key,
                    raw.getTag(key)
                        .copy());
            return result;
        }

        public List<ItemStack> componentCosts(int index) throws IOException {
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack item : HologramAeBlueprint.resourcePlan(block(index), 0, captured(index), null).items)
                result.add(item.copy());
            return result;
        }

        public ItemStack sourceCell() {
            return ((AEBaseInvTile) sourceTile(0)).getInternalInventory()
                .getStackInSlot(0)
                .copy();
        }

        public ItemStack sourcePattern() {
            return ((IInterfaceHost) sourceTile(1)).getInterfaceDuality()
                .getPatterns()
                .getStackInSlot(0)
                .copy();
        }

        public ItemStack sourceCard() {
            return ((IInterfaceHost) sourceTile(1)).getInventoryByName("upgrades")
                .getStackInSlot(0)
                .copy();
        }

        public ItemStack sourceCellCard() {
            ItemStack cell = sourceCell();
            return ((ICellWorkbenchItem) cell.getItem()).getUpgradesInventory(cell)
                .getStackInSlot(0)
                .copy();
        }

        public int aePartIndex() throws IOException {
            NBTTagList parts = captured(3).getTagList("parts", 10);
            for (int i = 0; i < parts.tagCount(); i++) if (parts.getCompoundTagAt(i)
                .getString("id")
                .equals("ae2_cablebus")) return i;
            throw new IOException("Missing actual AE multipart identity");
        }
    }

    /** Schedule genuine source/placement/rollback lifecycle checks across subsequent real server END ticks. */
    public static void run(WorldServer world) throws Exception {
        checks = 0;
        pendingRuns.clear();
        begin(world, false);
    }

    public static void runExtended(WorldServer world) throws Exception {
        begin(world, true);
    }

    private static void begin(WorldServer world, boolean extended) throws Exception {
        isolated(world);
        EntityPlayerMP actor = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("720a585f-0201-4e58-814d-639c7f8b92a1"), "GTMBAeBlueprint"));
        actor.inventory.mainInventory = new ItemStack[36];
        actor.inventory.currentItem = 0;
        actor.capabilities.isCreativeMode = false;
        actor.capabilities.allowEdit = true;
        actor.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        actor.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        Fixtures fixtures = createFixtures(world, actor, extended ? 544 : 448, 88, 192, extended);
        pendingRuns.add(new RunState(fixtures, extended));
    }

    public static final class Failure {

        public final String suite;
        public final Throwable cause;

        private Failure(String suite, Throwable cause) {
            this.suite = suite;
            this.cause = cause;
        }
    }

    public static final class PendingStatus {

        public final boolean complete;
        public final List<Failure> failures;

        private PendingStatus(boolean complete, List<Failure> failures) {
            this.complete = complete;
            this.failures = Collections.unmodifiableList(failures);
        }
    }

    private static final class RunState {

        final Fixtures fixtures;
        final boolean extended;
        final List<PlacementCheck> placements = new ArrayList<>();
        final List<FilteredPlacementCheck> filteredPlacements = new ArrayList<>();
        int phase;
        long phaseTime;
        boolean complete;
        Throwable failure;
        RollbackCheck rollback;

        RunState(Fixtures fixtures, boolean extended) {
            this.fixtures = fixtures;
            this.extended = extended;
            phaseTime = fixtures.world.getTotalWorldTime();
        }
    }

    private static final class PlacementCheck {

        final Fixtures fixtures;
        final int index, targetX;
        final TileEntity installed, canceled;
        final NBTTagCompound expected;
        final byte[] source;

        PlacementCheck(Fixtures fixtures, int index, int targetX, TileEntity installed, TileEntity canceled,
            NBTTagCompound expected, byte[] source) {
            this.fixtures = fixtures;
            this.index = index;
            this.targetX = targetX;
            this.installed = installed;
            this.canceled = canceled;
            this.expected = (NBTTagCompound) expected.copy();
            this.source = source.clone();
        }
    }

    private static final class RollbackCheck {

        final Fixtures fixtures;
        final NBTTagCompound journal;
        final ItemStack cell;
        final TileEntity restored;

        RollbackCheck(Fixtures fixtures, NBTTagCompound journal, ItemStack cell, TileEntity restored) {
            this.fixtures = fixtures;
            this.journal = (NBTTagCompound) journal.copy();
            this.cell = cell.copy();
            this.restored = restored;
        }
    }

    private static final class FilteredPlacementCheck {

        final Fixtures fixtures;
        final int index, targetX;
        final TileEntity installed;
        final byte[] source;

        FilteredPlacementCheck(Fixtures fixtures, int index, int targetX, TileEntity installed, byte[] source) {
            this.fixtures = fixtures;
            this.index = index;
            this.targetX = targetX;
            this.installed = installed;
            this.source = source.clone();
        }
    }

    /** Called only by the real ServerTickEvent.END listener; world time must advance two ticks per phase. */
    public static PendingStatus advancePending() {
        boolean complete = true;
        List<Failure> failures = new ArrayList<>();
        for (RunState state : pendingRuns) {
            if (!state.complete && state.fixtures.world.getTotalWorldTime() - state.phaseTime >= 2) {
                try {
                    Fixtures fixtures = state.fixtures;
                    if (state.phase == 0) {
                        assertSourceReady(fixtures);
                        if (!state.extended) {
                            Class.forName("com.miaokatze.gtmb.hologram.HologramAeBlueprintCoreRegression")
                                .getMethod("run", EntityPlayerMP.class)
                                .invoke(null, fixtures.actor);
                            drive(fixtures);
                            iface(fixtures);
                            HologramAeCellHandlerSmoke.run(fixtures.actor);
                        }
                        multipart(fixtures);
                        if (state.extended) populateBlockedVirtual(fixtures);
                        if (state.extended) state.placements.add(paidTransaction(fixtures, 3, 588));
                        else for (int i = 0; i < fixtures.sourceCount; i++)
                            state.placements.add(paidTransaction(fixtures, i, 480 + 4 * i));
                        if (!state.extended) for (int i = 0; i < 2; i++)
                            state.filteredPlacements.add(filteredOffTransaction(fixtures, i, 504 + 4 * i));
                    } else if (state.phase == 1) {
                        for (PlacementCheck placement : state.placements) finishPlacement(placement);
                        for (FilteredPlacementCheck placement : state.filteredPlacements)
                            finishFilteredPlacement(placement);
                        if (!state.extended) state.rollback = beginFilledRollback(fixtures);
                    } else {
                        finishFilledRollback(state.rollback);
                    }
                    if ((state.extended && state.phase == 1) || state.phase == 2) {
                        state.complete = true;
                        if (state.extended) System.out.println(
                            PREFIX
                                + "EXTENDED FINAL PASS genuine mixed FMP AE and microblock transaction; real native ticks");
                        else System.out.println(
                            PREFIX + "FINAL PASS assertions="
                                + checks
                                + "; native source/Service/rollback lifecycle verified across real server ticks");
                    } else {
                        state.phase++;
                        state.phaseTime = fixtures.world.getTotalWorldTime();
                    }
                } catch (Throwable failed) {
                    state.failure = failed;
                    state.complete = true;
                }
            }
            complete &= state.complete;
            if (state.failure != null)
                failures.add(new Failure(state.extended ? "AE-BLUEPRINT-FMP" : "AE-BLUEPRINT", state.failure));
        }
        return new PendingStatus(complete, failures);
    }

    /** Call only on a fresh isolated smoke-world region, after FML/native adapter integration. */
    public static Fixtures createFixtures(WorldServer world, EntityPlayerMP actor, int anchorX, int y, int z)
        throws Exception {
        return createFixtures(world, actor, anchorX, y, z, true);
    }

    public static Fixtures createFixtures(WorldServer world, EntityPlayerMP actor, int anchorX, int y, int z,
        boolean includeMultipart) throws Exception {
        isolated(world);
        if (actor == null || actor.worldObj != world) throw new IllegalArgumentException("Native world actor required");
        Block[] blocks = { AEApi.instance()
            .definitions()
            .blocks()
            .drive()
            .maybeBlock()
            .get(),
            AEApi.instance()
                .definitions()
                .blocks()
                .iface()
                .maybeBlock()
                .get(),
            registeredBlock("appeng.block.networking.BlockCableBus"),
            includeMultipart ? registeredBlock("codechicken.multipart.BlockMultipart") : null };
        Fixtures result = new Fixtures(world, actor, blocks, anchorX, y, z);
        EntityPlayerMP sourceActor = FakePlayerFactory
            .get(world, new GameProfile(UUID.fromString("720a585f-0201-4e58-814d-639c7f8b92a2"), "GTMBAeSourceOwner"));
        for (int i = 0; i < result.sourceCount; i++) {
            world.getChunkFromBlockCoords(result.sourceX[i], z);
            check(world.isAirBlock(result.sourceX[i], y, z), "fresh_source_" + i);
            check(world.setBlock(result.sourceX[i], y, z, blocks[i], 0, 3), "real_source_block_" + i);
        }
        TileEntity sourceDrive = result.sourceTile(0);
        check(sourceDrive instanceof AEBaseInvTile, "real_drive_inventory_type");
        ((AEBaseInvTile) sourceDrive).getInternalInventory()
            .setInventorySlotContents(0, filledCell(actor));
        bindSourceActor(sourceDrive, sourceActor);
        TileEntity sourceInterface = result.sourceTile(1);
        check(sourceInterface instanceof IInterfaceHost, "real_interface_type");
        configureInterface((IInterfaceHost) sourceInterface);
        bindSourceActor(sourceInterface, sourceActor);

        NBTTagCompound bus = interfaceBusSeed();
        for (int i = 2; i < result.sourceCount; i++) {
            NBTTagCompound payload = i == 2 ? (NBTTagCompound) bus.copy() : fmp(bus);
            if (i == 3) {
                // The mature native fixture creates an actual registered microblock beside the AE bus.
                NBTTagCompound micro = HologramNativeCompatibilityFixture.seed(blocks[i], result.sourceX[i], y, z);
                payload.getTagList("parts", 10)
                    .appendTag(
                        micro.getTagList("parts", 10)
                            .getCompoundTagAt(0)
                            .copy());
            }
            payload.setInteger("x", result.sourceX[i]);
            payload.setInteger("y", y);
            payload.setInteger("z", z);
            HologramNativePlacement.install(world, result.sourceX[i], y, z, blocks[i], 0, payload, sourceActor);
            IPart part = nativeInterface(result.sourceTile(i), i == 3);
            check(part instanceof IInterfaceHost, "real_native_interface_part_" + i);
            configureInterface((IInterfaceHost) part);
        }
        check(
            storedItems(
                ((AEBaseInvTile) sourceDrive).getInternalInventory()
                    .getStackInSlot(0))
                == 137L,
            "native_filled_source_cell_137_items");
        return result;
    }

    private static ItemStack filledCell(EntityPlayerMP actor) throws Exception {
        ItemStack cell = stack(
            AEApi.instance()
                .definitions()
                .items()
                .cell1k());
        // Actual cell inventory injection writes #0/@0/it/ic; no fabricated cell-content NBT.
        ItemCellInventory inventory = new ItemCellInventory(cell, null);
        IAEItemStack remainder = inventory.injectItems(
            AEItemStack.create(new ItemStack(Items.iron_ingot, 137)),
            Actionable.MODULATE,
            new PlayerSource(actor, null));
        check(remainder == null, "native_cell_injection_complete");
        ICellWorkbenchItem workbench = (ICellWorkbenchItem) cell.getItem();
        workbench.getConfigAEInventory(cell)
            .putAEStackInSlot(0, AEItemStack.create(new ItemStack(Items.iron_ingot)));
        IInventory cards = workbench.getUpgradesInventory(cell);
        ItemStack fuzzy = stack(
            AEApi.instance()
                .definitions()
                .materials()
                .cardFuzzy());
        check(cards.isItemValidForSlot(0, fuzzy), "official_cell_accepts_fuzzy_card");
        cards.setInventorySlotContents(0, fuzzy);
        check(
            inventory.getStoredItemCount() == 137L && cell.getTagCompound()
                .getLong("ic") == 137L,
            "official_cell_content_writer");
        return cell;
    }

    public static ItemStack encodedPattern(WorldServer world) {
        ItemStack pattern = stack(
            AEApi.instance()
                .definitions()
                .items()
                .encodedPattern());
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setBoolean("crafting", false);
        recipe.setBoolean("substitute", false);
        ItemStack ingredient = new ItemStack(Items.iron_ingot);
        NBTTagCompound legalRecipeData = new NBTTagCompound();
        legalRecipeData.setString("ownerUUID", "legal-recipe-owner-must-survive");
        legalRecipeData.setString("owner", "RecipeAuthor");
        legalRecipeData.setInteger("x", 19);
        legalRecipeData.setInteger("y", -28);
        legalRecipeData.setInteger("z", 31);
        ingredient.setTagCompound(legalRecipeData);
        NBTTagList inputs = new NBTTagList(), outputs = new NBTTagList();
        NBTTagCompound input = new NBTTagCompound(), output = new NBTTagCompound();
        ingredient.writeToNBT(input);
        new ItemStack(Items.gold_ingot).writeToNBT(output);
        inputs.appendTag(input);
        outputs.appendTag(output);
        recipe.setTag("in", inputs);
        recipe.setTag("out", outputs);
        pattern.setTagCompound(recipe);
        check(
            ((ICraftingPatternItem) pattern.getItem()).getPatternForItem(pattern, world) != null,
            "official_pattern_parser_accepts_exact_recipe");
        return pattern;
    }

    private static void configureInterface(IInterfaceHost host) {
        WorldServer world = (WorldServer) host.getTileEntity()
            .getWorldObj();
        host.getInterfaceDuality()
            .getPatterns()
            .setInventorySlotContents(0, encodedPattern(world));
        host.getInterfaceDuality()
            .getConfig()
            .setInventorySlotContents(0, new ItemStack(Items.diamond, 23));
        host.getInventoryByName("upgrades")
            .setInventorySlotContents(
                0,
                stack(
                    AEApi.instance()
                        .definitions()
                        .materials()
                        .cardCrafting()));
    }

    private static void drive(Fixtures fixtures) throws Exception {
        NBTTagCompound raw = fixtures.captured(0);
        byte[] source = bytes(raw), live = bytes(fixtures.captured(0));
        HologramAeBlueprint.Analysis result = HologramAeBlueprint.analyze(fixtures.drive, 0, raw);
        check(result.supported && !result.blocking, "filled_drive_reviewed " + analysisDiagnostic(result, raw));
        ItemStack blank = slot(result, "inv/item0").stack();
        check(blank != null && storedItems(blank) == 0L, "native_empty_cell_getter_has_zero_contents");
        check(issue(result, "cell_contents_cleared"), "filled_cell_loss_warning");
        NBTTagCompound tag = blank.getTagCompound();
        check(
            !tag.hasKey("ic") && !tag.hasKey("it") && !tag.hasKey("#0") && !tag.hasKey("@0"),
            "blank_cell_contains_no_content_rows_or_totals");
        ItemStack partition = ((ICellWorkbenchItem) blank.getItem()).getConfigAEInventory(blank)
            .getAEStackInSlot(0)
            .getItemStackForNEI();
        check(partition.isItemEqual(new ItemStack(Items.iron_ingot)), "native_empty_cell_partition_retained");
        ItemStack fuzzy = stack(
            AEApi.instance()
                .definitions()
                .materials()
                .cardFuzzy());
        check(
            cost(result.materials, new ItemStack(blank.getItem(), 1, blank.getItemDamage())) == 1
                && cost(result.materials, fuzzy) == 1
                && total(result.materials) == 2,
            "cell_shell_plus_embedded_card_paid_once_partition_and_contents_not_paid");
        check(
            Arrays.equals(source, bytes(raw)) && Arrays.equals(live, bytes(fixtures.captured(0))),
            "native_filled_source_unchanged_after_analysis");
        expectDenied(
            () -> HologramAeBlueprint
                .updateSlot(fixtures.drive, 0, result.payload(), "inv/item0", new ItemStack(Items.diamond)),
            "non_cell_mark_rejected_by_native_drive_slot");
        ItemStack doubleCell = blank.copy();
        doubleCell.stackSize = 2;
        expectDenied(
            () -> HologramAeBlueprint.updateSlot(fixtures.drive, 0, result.payload(), "inv/item0", doubleCell),
            "drive_cell_count_two_rejected");
        expectDenied(
            () -> HologramAeBlueprint.updateSlot(fixtures.drive, 0, result.payload(), "inv/item10", null),
            "drive_nonexistent_slot_clear_rejected");
        HologramAeBlueprint.Analysis cleared = HologramAeBlueprint
            .updateSlot(fixtures.drive, 0, result.payload(), "inv/item0", null);
        check(!cleared.blocking && total(cleared.materials) == 0, "clear_cell_removes_shell_and_embedded_card_cost");
        NBTTagCompound invalidCard = result.payload(), diamondCard = new NBTTagCompound();
        new ItemStack(Items.diamond).writeToNBT(diamondCard);
        invalidCard.getCompoundTag("inv")
            .getCompoundTag("item0")
            .getCompoundTag("tag")
            .getCompoundTag("upgrades")
            .setTag("#0", diamondCard);
        byte[] invalidBefore = bytes(invalidCard);
        HologramAeBlueprint.Analysis bad = HologramAeBlueprint.analyze(fixtures.drive, 0, invalidCard);
        check(bad.blocking && issue(bad, "cell_upgrade_invalid"), "non_module_embedded_card_is_editable_blocker");
        check(
            HologramAeBlueprint.analyze(fixtures.drive, 0, bad.payload()).blocking,
            "invalid_embedded_card_blocker_survives_reanalysis");
        check(
            !HologramAeBlueprint.updateSlot(fixtures.drive, 0, bad.payload(), "inv/item0", null).blocking,
            "explicit_cell_clear_can_resolve_invalid_embedded_card");
        check(Arrays.equals(invalidBefore, bytes(invalidCard)), "invalid_embedded_card_source_nbt_unchanged");
        NBTTagCompound wrongSlot = result.payload();
        wrongSlot.getCompoundTag("inv")
            .setString("item0", "malformed inherited slot");
        HologramAeBlueprint.Analysis slotBlocked = HologramAeBlueprint.analyze(fixtures.drive, 0, wrongSlot);
        check(
            slotBlocked.blocking && issue(slotBlocked, "missing_physical_item")
                && slot(slotBlocked, "inv/item0").stack() == null,
            "wrong_type_slot_keeps_native_resolution_descriptor");
        check(
            HologramAeBlueprint.analyze(fixtures.drive, 0, slotBlocked.payload()).blocking,
            "omitted_wrong_type_slot_blocker_persists");
        check(
            HologramAeBlueprint.updateSlot(fixtures.drive, 0, slotBlocked.payload(), "inv/item1", null).blocking,
            "unrelated_slot_clear_does_not_acknowledge_malformed_physical_slot");
        check(
            !HologramAeBlueprint.updateSlot(fixtures.drive, 0, slotBlocked.payload(), "inv/item0", null).blocking,
            "explicit_native_slot_clear_resolves_wrong_type_inherited_slot");
        check(
            Arrays.equals(live, bytes(fixtures.captured(0))),
            "native_drive_source_intact_after_slot_negative_and_clear");
    }

    private static void iface(Fixtures fixtures) throws Exception {
        NBTTagCompound raw = fixtures.captured(1);
        byte[] original = bytes(raw);
        HologramAeBlueprint.Analysis result = HologramAeBlueprint.analyze(fixtures.iface, 0, raw);
        check(!result.blocking, "native_interface_reviewed " + analysisDiagnostic(result, raw));
        ItemStack pattern = ((IInterfaceHost) fixtures.sourceTile(1)).getInterfaceDuality()
            .getPatterns()
            .getStackInSlot(0);
        ItemStack crafting = stack(
            AEApi.instance()
                .definitions()
                .materials()
                .cardCrafting());
        check(
            cost(result.materials, pattern) == 1 && cost(result.materials, crafting) == 1
                && total(result.materials) == 2,
            "exact_encoded_pattern_and_card_paid_once_ghost_not_paid");
        check(
            ItemStack.areItemStackTagsEqual(pattern, slot(result, "patterns/#0").stack()),
            "pattern_recipe_owner_uuid_and_coordinates_preserved");
        check(
            slot(result, "config/#0").kind.equals("ghost") && slot(result, "patterns/#0").kind.equals("physical"),
            "native_interface_ghost_and_physical_separate");
        expectDenied(
            () -> HologramAeBlueprint
                .updateSlot(fixtures.iface, 0, result.payload(), "patterns/#0", new ItemStack(Items.diamond)),
            "non_pattern_rejected");
        expectDenied(
            () -> HologramAeBlueprint
                .updateSlot(fixtures.iface, 0, result.payload(), "upgrades/#0", new ItemStack(Items.iron_ingot)),
            "non_card_rejected");
        expectDenied(
            () -> HologramAeBlueprint
                .updateSlot(fixtures.iface, 0, result.payload(), "config/#0", new ItemStack(Items.diamond, 65)),
            "ghost_slot_overflow_rejected");
        HologramAeBlueprint.Analysis ghostCleared = HologramAeBlueprint
            .updateSlot(fixtures.iface, 0, result.payload(), "config/#0", null);
        check(total(ghostCleared.materials) == total(result.materials), "ghost_clear_does_not_refund_physical_cost");
        NBTTagCompound hidden = result.payload();
        NBTTagCompound encoded = new NBTTagCompound();
        pattern.writeToNBT(encoded);
        hidden.getCompoundTag("patterns")
            .setTag("#9", encoded);
        HologramAeBlueprint.Analysis blocked = HologramAeBlueprint.analyze(fixtures.iface, 0, hidden);
        check(
            blocked.blocking && issue(blocked, "hidden_pattern") && !slot(blocked, "patterns/#9").enabled,
            "physical_pattern_outside_native_capacity_blocks");
        expectDenied(
            () -> HologramAeBlueprint.updateSlot(fixtures.iface, 0, blocked.payload(), "patterns/#9", pattern),
            "disabled_pattern_slot_set_rejected");
        check(
            HologramAeBlueprint.updateSlot(fixtures.iface, 0, blocked.payload(), "config/#1", null).blocking,
            "unrelated_ghost_clear_does_not_resolve_hidden_pattern");
        HologramAeBlueprint.Analysis expanded = HologramAeBlueprint.updateSlot(
            fixtures.iface,
            0,
            blocked.payload(),
            "upgrades/#1",
            stack(
                AEApi.instance()
                    .definitions()
                    .materials()
                    .cardPatternCapacity()));
        check(
            !expanded.blocking && slot(expanded, "patterns/#9").enabled && cost(expanded.materials, pattern) == 2,
            "native_capacity_card_enables_preserved_paid_hidden_pattern");
        check(
            !HologramAeBlueprint.updateSlot(fixtures.iface, 0, blocked.payload(), "patterns/#9", null).blocking,
            "explicit_disabled_slot_clear_resolves_hidden_pattern");
        check(
            Arrays.equals(original, bytes(raw)) && Arrays.equals(original, bytes(fixtures.captured(1))),
            "interface_native_source_inventory_and_recipe_unchanged");
    }

    private static void multipart(Fixtures fixtures) throws Exception {
        for (int i = 2; i < fixtures.sourceCount; i++) {
            NBTTagCompound raw = fixtures.captured(i);
            byte[] before = bytes(raw);
            HologramAeBlueprint.Analysis result = HologramAeBlueprint.analyze(fixtures.block(i), 0, raw);
            check(!result.blocking && result.supported, "native_cbc_fmp_part_reviewed_" + i);
            String prefix = i == 2 ? "" : "@part=" + fixtures.aePartIndex() + "/";
            check(
                slot(result, prefix + "extra:2/config/#0").kind.equals("ghost")
                    && slot(result, prefix + "extra:2/patterns/#0").kind.equals("physical"),
                "native_part_slots_" + i);
            IPart nativePart = nativeInterface(fixtures.sourceTile(i), i == 3);
            ItemStack pattern = ((IInterfaceHost) nativePart).getInterfaceDuality()
                .getPatterns()
                .getStackInSlot(0);
            check(
                cost(result.materials, pattern) == 1 && total(result.materials) == 2,
                "native_part_physical_cost_exact_" + i);
            List<ItemStack> shells = HologramAeBlueprint.nativeShellMaterials(fixtures.block(i), 0, result.payload());
            check(total(shells) == 2, "native_part_cable_and_interface_shells_once_" + i);
            HologramAeBlueprint.Analysis cleared = HologramAeBlueprint
                .updateSlot(fixtures.block(i), 0, result.payload(), prefix + "def:2", null);
            check(
                !cleared.blocking && cleared.materials.isEmpty()
                    && total(HologramAeBlueprint.nativeShellMaterials(fixtures.block(i), 0, cleared.payload())) == 1,
                "clear_part_also_clears_its_extra_inventory_and_cost_" + i);
            check(
                Arrays.equals(before, bytes(raw)) && Arrays.equals(before, bytes(fixtures.captured(i))),
                "cbc_fmp_real_source_unchanged_" + i);
        }
    }

    private static void populateBlockedVirtual(Fixtures fixtures) throws Exception {
        check(fixtures.sourceCount == 4, "blocked_virtual_fixture_has_all_four_native_sources");
        byte[][] original = new byte[fixtures.sourceCount][];
        for (int i = 0; i < original.length; i++) original[i] = bytes(fixtures.captured(i));
        IInventory patterns = ((IInterfaceHost) fixtures.sourceTile(1)).getInterfaceDuality()
            .getPatterns();
        ItemStack oldHidden = patterns.getStackInSlot(35);
        check(oldHidden == null, "native_hidden_pattern_slot35_starts_empty");
        try {
            patterns.setInventorySlotContents(35, fixtures.sourcePattern());
            check(patterns.getStackInSlot(35) != null, "native_interface_holds_actual_hidden_pattern35");
            byte[][] withHidden = new byte[fixtures.sourceCount][];
            HologramPresets.Preset input = new HologramPresets.Preset();
            input.schema = 2;
            input.id = "ae-native-blocked-admission";
            input.name = "AE native blocked admission";
            input.controllerType = "area";
            for (int i = 0; i < fixtures.sourceCount; i++) {
                NBTTagCompound raw = fixtures.captured(i);
                withHidden[i] = bytes(raw);
                ItemStack main = HologramAeBlueprint.sampleIdentity(fixtures.block(i), 0, raw);
                HologramPresets.Cell saved = HologramPresets
                    .cell(main, fixtures.sourceX[i] - fixtures.sourceX[0], 0, 0, -1, false);
                saved.block = String.valueOf(Block.blockRegistry.getNameForObject(fixtures.block(i)));
                saved.metadata = 0;
                saved.tileNbt = HologramBlueprintNbt.encodeCaptured(raw);
                saved.itemNbt = "";
                input.cells.add(saved);
            }
            HologramAePolicy.Draft draft = HologramAePolicy.sanitize(input);
            check(draft.blocking, "native_hidden_pattern35_produces_blocking_editable_draft");
            HologramCapture.Cell first = (HologramCapture.Cell) smokeCall(
                "cell",
                new Class<?>[] { Block.class, int.class, NBTTagCompound.class, ItemStack.class, int.class, int.class,
                    int.class },
                fixtures.drive,
                0,
                fixtures.captured(0),
                fixtures.sample(0),
                fixtures.sourceX[0],
                fixtures.y,
                fixtures.z);
            Object session = smokeCall(
                "session",
                new Class<?>[] { EntityPlayerMP.class, HologramCapture.Cell.class, ItemStack.class },
                fixtures.actor,
                first,
                fixtures.sample(0));
            Field presetField = session.getClass()
                .getDeclaredField("virtualPreset");
            presetField.setAccessible(true);
            presetField.set(session, draft.preset);
            ItemStack[] playerBefore = inventory(fixtures.actor);
            Method populate = HologramService.class.getDeclaredMethod("populateVirtual", session.getClass());
            populate.setAccessible(true);
            populate.invoke(null, session);
            HologramCapture capture = (HologramCapture) field(session, "capture");
            check(
                !capture.incomplete && !capture.pending && capture.cells.size() == 4 && capture.positions.size() == 4,
                "actual_service_blocked_interface_admission_keeps_all_four_native_cells");
            for (int i = 0; i < fixtures.sourceCount; i++) {
                HologramCapture.Cell admitted = capture.positions
                    .get(fixtures.sourceX[i] + "," + fixtures.y + "," + fixtures.z);
                check(
                    admitted != null && admitted.block == fixtures.block(i)
                        && admitted.meta == 0
                        && admitted.tileNbt.equals(draft.preset.cells.get(i).tileNbt),
                    "actual_service_blocked_admission_keeps_exact_native_coordinate_and_payload_" + i);
            }
            HologramCapture.Cell hidden = capture.positions
                .get(fixtures.sourceX[1] + "," + fixtures.y + "," + fixtures.z);
            HologramAeBlueprint.Analysis blocked = HologramAeBlueprint
                .analyze(hidden.block, hidden.meta, HologramBlueprintNbt.decode(hidden.tileNbt));
            check(
                blocked.blocking && !slot(blocked, "patterns/#35").enabled,
                "actual_service_admitted_interface_retains_hidden35_blocker");
            NBTTagCompound request = new NBTTagCompound();
            request.setInteger("x", hidden.x);
            request.setInteger("y", hidden.y);
            request.setInteger("z", hidden.z);
            request.setString("structureId", (String) field(session, "blueprintActiveStructure"));
            request.setLong("planRevision", (Long) field(session, "planRevision"));
            Method action = HologramService.class
                .getDeclaredMethod("aeAction", session.getClass(), NBTTagCompound.class, String.class);
            action.setAccessible(true);
            check(
                (Boolean) action.invoke(null, session, request, "aePreviewOpen"),
                "actual_service_handles_hidden35_preview_open");
            HologramAeProtocol.State ae = (HologramAeProtocol.State) field(session, "ae");
            check(
                ae.preview != null && ae.preview.x == hidden.x && ae.preview.y == hidden.y && ae.preview.z == hidden.z,
                "actual_service_opens_blocked_interface_preview_without_truncating_following_cells");
            NBTTagList previewSlots = ae.preview.snapshot(blocked)
                .getTagList("slots", 10);
            boolean hiddenPreviewSlot = false;
            for (int i = 0; i < previewSlots.tagCount(); i++) {
                NBTTagCompound row = previewSlots.getCompoundTagAt(i);
                if ("patterns/#35".equals(row.getString("id"))) hiddenPreviewSlot = !row.getBoolean("enabled");
            }
            check(hiddenPreviewSlot, "actual_service_preview_exposes_disabled_hidden35_for_explicit_repair");
            Method ready = HologramService.class.getDeclaredMethod("aeReady", session.getClass());
            ready.setAccessible(true);
            check(!(Boolean) ready.invoke(null, session), "blocked_virtual_admission_still_rejects_actual_placement");
            boolean rejectedCost = false;
            try {
                HologramAeBlueprint
                    .resourcePlan(hidden.block, hidden.meta, HologramBlueprintNbt.decode(hidden.tileNbt), null);
            } catch (IOException expected) {
                rejectedCost = true;
            }
            check(rejectedCost, "blocked_virtual_admission_cannot_bypass_strict_resource_plan");
            for (int i = 0; i < fixtures.sourceCount; i++) check(
                Arrays.equals(withHidden[i], bytes(fixtures.captured(i))),
                "actual_service_four_cell_population_and_preview_never_mutates_native_source_" + i);
            check(sameInventory(playerBefore, fixtures.actor), "blocked_virtual_population_and_preview_moves_no_items");
        } finally {
            patterns.setInventorySlotContents(35, oldHidden);
        }
        for (int i = 0; i < fixtures.sourceCount; i++) check(
            Arrays.equals(original[i], bytes(fixtures.captured(i))),
            "native_hidden_admission_fixture_restores_exact_original_source_" + i);
        assertSourceReady(fixtures);
    }

    private static PlacementCheck paidTransaction(Fixtures fixtures, int index, int targetX) throws Exception {
        WorldServer world = fixtures.world;
        EntityPlayerMP actor = fixtures.actor;
        world.getChunkFromBlockCoords(targetX, fixtures.z);
        check(world.isAirBlock(targetX, fixtures.y, fixtures.z), "fresh_paid_destination_" + index);
        NBTTagCompound raw = fixtures.captured(index);
        byte[] source = bytes(raw);
        HologramAeBlueprint.Analysis canonical = HologramAeBlueprint.analyze(fixtures.block(index), 0, raw);
        check(!canonical.blocking, "transaction_canonical_payload_" + index);
        HologramBlueprintResources.Plan fullPlan = HologramAeBlueprint
            .resourcePlan(fixtures.block(index), 0, raw, null);
        List<ItemStack> costs = fullPlan.items;
        check(
            total(costs) == (index < 2 ? 3 : index == 2 ? 4 : 5),
            "production_native_components_charge_exactly_once_" + index);
        ItemStack sample = fixtures.sample(index);
        HologramBlueprintResources.Plan extras = HologramAeBlueprint
            .resourcePlan(fixtures.block(index), 0, raw, sample);
        check(total(extras.items) + 1 == total(costs), "main_shell_is_not_charged_twice_" + index);
        check(
            fullPlan.fluids.isEmpty() && fullPlan.energy.signum() == 0
                && cost(costs, new ItemStack(Items.diamond)) == 0
                && cost(costs, new ItemStack(Items.iron_ingot)) == 0,
            "contents_partition_and_interface_ghosts_have_no_cost_" + index);
        if (index == 3) {
            NBTTagCompound remainder = HologramAeBlueprint.withoutAeMultipart(canonical.payload());
            List<ItemStack> matureCosts = HologramNativePlacement.materials(fixtures.multipart, 0, remainder);
            check(
                total(matureCosts) == 1 && cost(costs, matureCosts.get(0)) == matureCosts.get(0).stackSize,
                "mixed_fmp_non_ae_microblock_cost_uses_mature_native_provider");
        }
        HologramCapture.Cell cell = (HologramCapture.Cell) smokeCall(
            "cell",
            new Class<?>[] { Block.class, int.class, NBTTagCompound.class, ItemStack.class, int.class, int.class,
                int.class },
            fixtures.block(index),
            0,
            canonical.payload(),
            sample,
            targetX,
            fixtures.y,
            fixtures.z);
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(canonical.payload());
        Object session = smokeCall(
            "session",
            new Class<?>[] { EntityPlayerMP.class, HologramCapture.Cell.class, ItemStack.class },
            actor,
            cell,
            sample);
        HologramPresets.Preset preset = (HologramPresets.Preset) field(session, "virtualPreset");
        Field policy = preset.getClass()
            .getDeclaredField("aePolicyVersion");
        policy.setAccessible(true);
        policy.setInt(preset, HologramAeBlueprint.POLICY_VERSION);
        actor.setPosition(targetX + 0.5, fixtures.y, fixtures.z + 0.5);
        List<ItemStack> shortCosts = new ArrayList<>(costs);
        shortCosts.remove(shortCosts.size() - 1);
        fill(actor, shortCosts);
        ItemStack[] missingBefore = inventory(actor);
        check(
            !operate(session, cell) && world.isAirBlock(targetX, fixtures.y, fixtures.z)
                && sameInventory(missingBefore, actor),
            "missing_card_or_pattern_prevents_world_install_and_inventory_delta_" + index);
        if (index == 0) {
            fill(actor, costs);
            ItemStack playerCargo = fixtures.sourceCell();
            playerCargo.getTagCompound()
                .setString("diskuuid", "f22ed73b-8e6f-498a-8b45-6ca1f1c7865f");
            boolean replaced = false;
            for (int i = 0; i < costs.size(); i++) if (costs.get(i)
                .getItem() instanceof ICellWorkbenchItem) {
                    actor.inventory.mainInventory[i + 1] = playerCargo;
                    replaced = true;
                }
            check(replaced && storedItems(playerCargo) == 137L, "player_owns_filled_cell_but_no_exact_blank_shell");
            ItemStack[] cargoBefore = inventory(actor);
            check(
                !operate(session, cell) && world.isAirBlock(targetX, fixtures.y, fixtures.z)
                    && sameInventory(cargoBefore, actor)
                    && storedItems(playerCargo) == 137L,
                "filled_or_old_uuid_cell_never_consumed_as_blank_shell_material");
        } else {
            fill(actor, costs);
            boolean changed = false;
            for (int i = 0; i < costs.size(); i++) if (costs.get(i)
                .getItem() instanceof ICraftingPatternItem) {
                    ItemStack otherRecipe = costs.get(i)
                        .copy();
                    otherRecipe.getTagCompound()
                        .getTagList("in", 10)
                        .getCompoundTagAt(0)
                        .getCompoundTag("tag")
                        .setString("ownerUUID", "different-legal-recipe-owner");
                    actor.inventory.mainInventory[i + 1] = otherRecipe;
                    changed = true;
                }
            check(changed, "same_pattern_item_with_different_exact_recipe_supplied_" + index);
            ItemStack[] wrongPatternBefore = inventory(actor);
            check(
                !operate(session, cell) && world.isAirBlock(targetX, fixtures.y, fixtures.z)
                    && sameInventory(wrongPatternBefore, actor),
                "different_recipe_pattern_is_not_consumed_for_exact_required_pattern_" + index);
        }
        fill(actor, costs);
        classifyPaidNative(fixtures, index, session, cell, sample, canonical.payload());
        ItemStack[] before = inventory(actor);
        Veto veto = new Veto(fixtures.block(index), canonical.payload(), actor, targetX, fixtures.y, fixtures.z);
        MinecraftForge.EVENT_BUS.register(veto);
        boolean vetoProcessed;
        try {
            vetoProcessed = operate(session, cell);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(veto);
        }
        // operate reports that the cell was processed. A canceled Forge placement is processed/protected.
        check(
            vetoProcessed && "protected".equals(cell.status),
            "native_place_veto_processed_as_protected_" + index
                + " cell="
                + cell.status
                + " status="
                + field(session, "status")
                + " resourceError="
                + cell.resourceError
                + " events="
                + veto.events
                + " lifecycleValid="
                + veto.lifecycleValid
                + " cause="
                + veto.failure);
        check(
            veto.events == 1 && veto.complete && veto.lifecycleValid,
            "veto_observes_complete_actor_bound_native_lifecycle_" + index + "_" + veto.failure);
        check(
            world.isAirBlock(targetX, fixtures.y, fixtures.z)
                && world.getTileEntity(targetX, fixtures.y, fixtures.z) == null
                && sameInventory(before, actor),
            "veto_refunds_all_components_and_removes_native_tile_" + index);
        check(
            veto.observedTile != null && veto.observedTile.isInvalid(),
            "cancelled_tile_invalid_before_queued_ready_" + index);
        check(Arrays.equals(source, bytes(fixtures.captured(index))), "real_source_intact_after_place_veto_" + index);
        check(
            operate(session, cell) && "placed".equals(cell.status),
            "real_service_paid_placement_" + index + "_" + cell.status);
        check(materialCount(actor) == 0, "real_service_components_consumed_once_" + index);
        TileEntity installed = world.getTileEntity(targetX, fixtures.y, fixtures.z);
        check(
            HologramAeBlueprint.matchesPlaced(fixtures.block(index), 0, installed, canonical.payload()),
            "placed_persistent_configuration_matches_review_" + index);
        if (index == 0) {
            ItemStack cellStack = ((AEBaseInvTile) installed).getInternalInventory()
                .getStackInSlot(0);
            check(storedItems(cellStack) == 0L, "paid_drive_contains_empty_cell_not_source_items");
        }
        check(Arrays.equals(source, bytes(fixtures.captured(index))), "real_source_intact_after_paid_install_" + index);
        return new PlacementCheck(fixtures, index, targetX, installed, veto.observedTile, canonical.payload(), source);
    }

    private static void classifyPaidNative(Fixtures fixtures, int index, Object session, HologramCapture.Cell cell,
        ItemStack sample, NBTTagCompound canonical) throws Exception {
        ItemStack[] inventoryBefore = inventory(fixtures.actor);
        byte[] sourceBefore = bytes(fixtures.captured(index));
        Method classify = HologramService.class
            .getDeclaredMethod("classify", session.getClass(), HologramCapture.Cell.class, int.class);
        classify.setAccessible(true);
        check(
            "pending".equals(classify.invoke(null, session, cell, 0)),
            "actual_service_classifies_funded_empty_native_destination_pending_" + index);
        if (index >= 2) {
            rejectedNativeClassification(
                fixtures,
                index,
                cell,
                canonical,
                new ItemStack(Items.diamond),
                false,
                "arbitrary_plain_item");
            ItemStack wrongPart = stack(
                AEApi.instance()
                    .definitions()
                    .parts()
                    .quartzFiber());
            check(
                !wrongPart.isItemEqual(sample),
                "classification_negative_uses_distinct_registered_native_part_" + index);
            rejectedNativeClassification(fixtures, index, cell, canonical, wrongPart, false, "wrong_registered_part");
            rejectedNativeClassification(fixtures, index, cell, canonical, sample, true, "missing_native_payload");
            NBTTagCompound unreviewed = (NBTTagCompound) canonical.copy();
            NBTTagCompound bus = index == 2 ? unreviewed
                : unreviewed.getTagList("parts", 10)
                    .getCompoundTagAt(fixtures.aePartIndex());
            bus.getCompoundTag("extra:2")
                .setString("unreviewedNativeClassification", "unsupported-field");
            HologramAeBlueprint.Analysis blocked = HologramAeBlueprint.analyze(fixtures.block(index), 0, unreviewed);
            check(blocked.blocking, "classification_unknown_schema_payload_is_a_genuine_blocker_" + index);
            rejectedNativeClassification(
                fixtures,
                index,
                cell,
                unreviewed,
                sample,
                false,
                "noncanonical_unknown_schema");
            rejectedNativeClassification(
                fixtures,
                index,
                cell,
                blocked.payload(),
                sample,
                false,
                "canonical_persistent_blocker");
        }
        Method refresh = HologramService.class.getDeclaredMethod("refreshPlan", session.getClass());
        refresh.setAccessible(true);
        refresh.invoke(null, session);
        List<?> statuses = (List<?>) field(session, "planStatus");
        List<?> targets = (List<?>) field(session, "planTargets");
        check(
            statuses.size() == 1 && "pending".equals(statuses.get(0))
                && "pending".equals(cell.status)
                && targets.size() == 1
                && targets.get(0) instanceof ItemStack
                && ((ItemStack) targets.get(0)).isItemEqual(sample),
            "actual_service_refresh_plan_retains_native_pending_primary_" + index);
        Field placement = session.getClass()
            .getDeclaredField("virtualPlacement");
        placement.setAccessible(true);
        placement.setBoolean(session, true);
        Field locked = session.getClass()
            .getDeclaredField("virtualLocked");
        locked.setAccessible(true);
        locked.setBoolean(session, true);
        Method direct = HologramService.class.getDeclaredMethod("directBuild", session.getClass(), boolean.class);
        direct.setAccessible(true);
        direct.invoke(null, session, false);
        List<?> work = (List<?>) field(session, "work");
        check(
            ((Integer) field(session, "job")) == 1 && work.size() == 1
                && Integer.valueOf(0)
                    .equals(work.get(0)),
            "actual_service_direct_build_queues_pending_native_coordinate_" + index + "_" + field(session, "status"));
        check(
            fixtures.world.isAirBlock(cell.x, cell.y, cell.z)
                && fixtures.world.getTileEntity(cell.x, cell.y, cell.z) == null
                && sameInventory(inventoryBefore, fixtures.actor)
                && Arrays.equals(sourceBefore, bytes(fixtures.captured(index))),
            "native_classification_and_queue_do_not_install_consume_or_mutate_source_" + index);
        // This isolated Session is not in the live dispatcher. Reset the queued plan before the existing
        // paid/veto transaction, which continues to verify the actual installation and all resource deductions.
        refresh.invoke(null, session);
    }

    private static void rejectedNativeClassification(Fixtures fixtures, int index, HologramCapture.Cell destination,
        NBTTagCompound payload, ItemStack main, boolean missingPayload, String scenario) throws Exception {
        HologramCapture.Cell candidate = (HologramCapture.Cell) smokeCall(
            "cell",
            new Class<?>[] { Block.class, int.class, NBTTagCompound.class, ItemStack.class, int.class, int.class,
                int.class },
            fixtures.block(index),
            0,
            payload,
            main,
            destination.x,
            destination.y,
            destination.z);
        if (missingPayload) {
            candidate.tileNbt = "";
            candidate.itemNbt = "";
        }
        Object negative = smokeCall(
            "session",
            new Class<?>[] { EntityPlayerMP.class, HologramCapture.Cell.class, ItemStack.class },
            fixtures.actor,
            candidate,
            main);
        ((HologramPresets.Preset) field(
            negative,
            "virtualPreset")).aePolicyVersion = HologramAeBlueprint.POLICY_VERSION;
        Method classify = HologramService.class
            .getDeclaredMethod("classify", negative.getClass(), HologramCapture.Cell.class, int.class);
        classify.setAccessible(true);
        check(
            "unsupported".equals(classify.invoke(null, negative, candidate, 0)),
            "actual_service_native_classification_rejects_" + scenario + "_" + index);
    }

    private static void finishPlacement(PlacementCheck pending) throws Exception {
        Fixtures fixtures = pending.fixtures;
        int index = pending.index;
        TileEntity installed = fixtures.world.getTileEntity(pending.targetX, fixtures.y, fixtures.z);
        check(installed == pending.installed, "paid_target_object_survives_native_ticks_" + index);
        assertPlaced(fixtures.block(index), 0, installed, pending.expected, fixtures.actor);
        check(pending.canceled.isInvalid(), "cancelled_tile_stays_invalid_after_real_native_ticks_" + index);
        for (Object host : gridHosts(fixtures.block(index), pending.canceled)) {
            AENetworkProxy proxy = ((IGridProxyable) host).getProxy();
            check(
                proxyField(proxy, "node") == null && !Boolean.TRUE.equals(proxyField(proxy, "isReady")),
                "cancelled_native_proxy_never_joins_after_queued_tick_" + index);
        }
        IGridNode sourceNode = node(fixtures.sourceTile(index), index);
        IGridNode targetNode = node(installed, index);
        check(
            targetNode != null && sourceNode != null && targetNode.getGrid() != sourceNode.getGrid(),
            "placement_does_not_join_captured_old_grid_" + index);
        int actorId = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(fixtures.actor.getGameProfile());
        check(sourceNode.getPlayerID() != actorId, "source_owner_is_distinct_from_placing_actor_" + index);
        check(targetNode.getPlayerID() == actorId, "new_grid_node_bound_to_placing_actor_" + index);
        NBTTagCompound sourceIdentity = new NBTTagCompound(), targetIdentity = new NBTTagCompound();
        sourceNode.saveToNBT("n", sourceIdentity);
        targetNode.saveToNBT("n", targetIdentity);
        check(
            sourceIdentity.hasKey("n", 10) && targetIdentity.hasKey("n", 10)
                && sourceIdentity.getCompoundTag("n")
                    .getLong("g")
                    != targetIdentity.getCompoundTag("n")
                        .getLong("g"),
            "new_grid_storage_id_differs_from_captured_source_" + index);
        check(
            Arrays.equals(pending.source, bytes(fixtures.captured(index))),
            "real_source_intact_after_payment_veto_and_install_" + index);
    }

    private static FilteredPlacementCheck filteredOffTransaction(Fixtures fixtures, int index, int targetX)
        throws Exception {
        WorldServer world = fixtures.world;
        world.getChunkFromBlockCoords(targetX, fixtures.z);
        check(world.isAirBlock(targetX, fixtures.y, fixtures.z), "filtered_off_fresh_native_destination_" + index);
        NBTTagCompound raw = fixtures.captured(index);
        byte[] source = bytes(raw);
        HologramAeBlueprint.Analysis canonical = HologramAeBlueprint.analyze(fixtures.block(index), 0, raw);
        check(!canonical.blocking, "filtered_off_genuine_source_payload_valid_" + index);
        ItemStack sample = fixtures.sample(index);
        HologramCapture.Cell cell = (HologramCapture.Cell) smokeCall(
            "cell",
            new Class<?>[] { Block.class, int.class, NBTTagCompound.class, ItemStack.class, int.class, int.class,
                int.class },
            fixtures.block(index),
            0,
            canonical.payload(),
            sample,
            targetX,
            fixtures.y,
            fixtures.z);
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(canonical.payload());
        Object session = smokeCall(
            "session",
            new Class<?>[] { EntityPlayerMP.class, HologramCapture.Cell.class, ItemStack.class },
            fixtures.actor,
            cell,
            sample);
        HologramPresets.Preset preset = (HologramPresets.Preset) field(session, "virtualPreset");
        preset.aePolicyVersion = HologramAeBlueprint.POLICY_VERSION;
        preset.retainNbt = false;
        preset.retainCovers = false;
        NBTTagCompound selected = HologramBlueprintNbt.forPlacement(
            cell.block,
            cell.meta,
            cell.tileNbt,
            fixtures.actor.getUniqueID(),
            fixtures.actor.getCommandSenderName(),
            cell.x,
            cell.y,
            cell.z,
            false,
            false,
            Collections.emptyList());
        check(
            selected != null
                && (index == 0 ? !selected.hasKey("inv") : emptyInventoryTag(selected, "inv", "item", 9, true))
                && emptyInventoryTag(selected, "storage", "#", 9, false)
                && emptyInventoryTag(selected, "patterns", "#", 36, false)
                && emptyInventoryTag(selected, "upgrades", "#", 4, false)
                && emptyInventoryTag(selected, "config", "#", 9, false)
                && !selected.hasKey("proxy"),
            "filtered_off_native_payload_omits_source_inventories_ghosts_and_identity_" + index);
        Method planMethod = HologramService.class
            .getDeclaredMethod("resourcePlan", session.getClass(), HologramCapture.Cell.class);
        planMethod.setAccessible(true);
        HologramBlueprintResources.Plan extras = (HologramBlueprintResources.Plan) planMethod
            .invoke(null, session, cell);
        HologramBlueprintResources.Plan full = HologramAeBlueprint.resourcePlan(cell.block, cell.meta, selected, null);
        check(
            extras.items.isEmpty() && extras.fluids.isEmpty()
                && extras.energy.signum() == 0
                && total(full.items) == 1
                && cost(full.items, sample) == 1,
            "actual_service_filtered_off_native_plan_charges_only_one_shell_" + index);
        ItemStack twoShells = sample.copy();
        twoShells.stackSize = 2;
        fill(
            fixtures.actor,
            Arrays.asList(
                twoShells,
                fixtures.sourceCell(),
                fixtures.sourcePattern(),
                fixtures.sourceCard(),
                fixtures.sourceCellCard()));
        ItemStack[] afterOnlyShellPayment = inventory(fixtures.actor);
        afterOnlyShellPayment[1].stackSize--;
        fixtures.actor.setPosition(targetX + 0.5, fixtures.y, fixtures.z + 0.5);
        check(
            operate(session, cell) && "placed".equals(cell.status)
                && world.getBlock(targetX, fixtures.y, fixtures.z) == fixtures.block(index)
                && world.getBlockMetadata(targetX, fixtures.y, fixtures.z) == 0,
            "actual_service_paid_filtered_off_native_empty_host_installed_" + index);
        check(
            sameInventory(afterOnlyShellPayment, fixtures.actor),
            "filtered_off_payment_consumes_exactly_one_shell_and_no_cell_cards_or_patterns_" + index);
        TileEntity installed = world.getTileEntity(targetX, fixtures.y, fixtures.z);
        check(installed instanceof AEBaseInvTile, "filtered_off_installed_actual_native_inventory_tile_" + index);
        emptyFilteredInventories(installed, index);
        AENetworkProxy proxy = ((IGridProxyable) installed).getProxy();
        check(
            proxyField(proxy, "node") == null && !Boolean.TRUE.equals(proxyField(proxy, "isReady"))
                && proxyField(proxy, "data") == null
                && proxyField(proxy, "owner") == fixtures.actor,
            "filtered_off_native_actor_bound_before_real_ready_tick_" + index);
        check(
            Arrays.equals(source, bytes(fixtures.captured(index))),
            "filtered_off_paid_source_exact_unchanged_" + index);
        return new FilteredPlacementCheck(fixtures, index, targetX, installed, source);
    }

    private static void finishFilteredPlacement(FilteredPlacementCheck pending) throws Exception {
        Fixtures fixtures = pending.fixtures;
        int index = pending.index;
        TileEntity installed = fixtures.world.getTileEntity(pending.targetX, fixtures.y, fixtures.z);
        check(installed == pending.installed, "filtered_off_empty_host_survives_real_native_ticks_" + index);
        emptyFilteredInventories(installed, index);
        AENetworkProxy proxy = ((IGridProxyable) installed).getProxy();
        IGridNode actual = (IGridNode) proxyField(proxy, "node");
        check(
            Boolean.TRUE.equals(proxyField(proxy, "isReady")) && actual != null && actual.getGrid() != null,
            "filtered_off_host_already_joined_by_actual_native_ticks_" + index);
        int actorId = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(fixtures.actor.getGameProfile());
        IGridNode sourceNode = node(fixtures.sourceTile(index), index);
        check(
            actual.getPlayerID() == actorId && sourceNode != null
                && sourceNode.getPlayerID() != actorId
                && actual.getGrid() != sourceNode.getGrid(),
            "filtered_off_host_has_new_actor_and_distinct_existing_native_grid_" + index);
        NBTTagCompound actualIdentity = new NBTTagCompound(), sourceIdentity = new NBTTagCompound();
        actual.saveToNBT("node", actualIdentity);
        sourceNode.saveToNBT("node", sourceIdentity);
        check(
            actualIdentity.hasKey("node", 10) && actualIdentity.getCompoundTag("node")
                .getLong("g") > 0L
                && actualIdentity.getCompoundTag("node")
                    .getLong("g")
                    != sourceIdentity.getCompoundTag("node")
                        .getLong("g"),
            "filtered_off_host_persists_fresh_native_grid_identity_" + index);
        check(
            Arrays.equals(pending.source, bytes(fixtures.captured(index)))
                && storedItems(fixtures.sourceCell()) == 137L,
            "filtered_off_native_ticks_preserve_source_137_cargo_and_exact_patterns_cards_" + index);
        sourceReady(fixtures, index);
    }

    private static void emptyFilteredInventories(TileEntity installed, int index) {
        emptyInventory(
            ((AEBaseInvTile) installed).getInternalInventory(),
            "filtered_off_native_storage_empty_" + index);
        if (index == 1) {
            IInterfaceHost host = (IInterfaceHost) installed;
            emptyInventory(
                host.getInterfaceDuality()
                    .getPatterns(),
                "filtered_off_native_patterns_empty");
            emptyInventory(
                host.getInterfaceDuality()
                    .getConfig(),
                "filtered_off_native_ghost_config_empty");
            emptyInventory(
                host.getInterfaceDuality()
                    .getUpgrades(),
                "filtered_off_native_upgrade_cards_empty");
            emptyInventory(
                host.getInterfaceDuality()
                    .getStorage(),
                "filtered_off_native_storage_alias_empty");
        }
    }

    private static void emptyInventory(IInventory inventory, String name) {
        boolean empty = inventory != null;
        if (inventory != null)
            for (int i = 0; i < inventory.getSizeInventory(); i++) empty &= inventory.getStackInSlot(i) == null;
        check(empty, name);
    }

    private static boolean emptyInventoryTag(NBTTagCompound payload, String root, String prefix, int slots,
        boolean requireAll) {
        if (!payload.hasKey(root)) return true;
        if (!payload.hasKey(root, 10)) return false;
        NBTTagCompound inventory = payload.getCompoundTag(root);
        if (requireAll && inventory.func_150296_c()
            .size() != slots) return false;
        for (Object raw : inventory.func_150296_c()) {
            String key = (String) raw;
            boolean known = false;
            for (int slot = 0; slot < slots; slot++) known |= (prefix + slot).equals(key);
            if (!known || !inventory.hasKey(key, 10)
                || !inventory.getCompoundTag(key)
                    .hasNoTags())
                return false;
        }
        return true;
    }

    private static RollbackCheck beginFilledRollback(Fixtures fixtures) throws Exception {
        NBTTagCompound journal = fixtures.captured(0);
        System.out.println(PREFIX + "ROLLBACK journal " + rollbackIdentity(journal.getCompoundTag("proxy")));
        ItemStack sourceCell = ((AEBaseInvTile) fixtures.sourceTile(0)).getInternalInventory()
            .getStackInSlot(0)
            .copy();
        HologramRecovery
            .restore(fixtures.world, fixtures.sourceX[0], fixtures.y, fixtures.z, fixtures.drive, 0, journal);
        ItemStack restored = ((AEBaseInvTile) fixtures.sourceTile(0)).getInternalInventory()
            .getStackInSlot(0);
        check(
            ItemStack.areItemStacksEqual(sourceCell, restored) && storedItems(restored) == 137L,
            "trusted_world_rollback_preserves_filled_cell_and_exact_embedded_cards");
        AENetworkProxy proxy = ((IGridProxyable) fixtures.sourceTile(0)).getProxy();
        check(
            !Boolean.TRUE.equals(proxyField(proxy, "isReady")) && proxyField(proxy, "node") == null,
            "trusted_rollback_waits_for_real_native_tick");
        NBTTagCompound pendingData = (NBTTagCompound) proxyField(proxy, "data");
        check(
            pendingData != null && pendingData.getCompoundTag("proxy")
                .equals(journal.getCompoundTag("proxy")),
            "trusted_rollback_preserves_original_pending_identity_before_ready");
        return new RollbackCheck(fixtures, journal, sourceCell, fixtures.sourceTile(0));
    }

    private static void finishFilledRollback(RollbackCheck pending) throws Exception {
        Fixtures fixtures = pending.fixtures;
        check(fixtures.sourceTile(0) == pending.restored, "trusted_rollback_object_survives_real_native_ticks");
        ItemStack restored = ((AEBaseInvTile) fixtures.sourceTile(0)).getInternalInventory()
            .getStackInSlot(0);
        check(
            ItemStack.areItemStacksEqual(pending.cell, restored) && storedItems(restored) == 137L,
            "trusted_filled_cargo_and_cards_intact_after_native_ready");
        AENetworkProxy proxy = ((IGridProxyable) fixtures.sourceTile(0)).getProxy();
        check(
            Boolean.TRUE.equals(proxyField(proxy, "isReady")) && proxyField(proxy, "node") != null,
            "trusted_rollback_joined_by_real_native_tick");
        NBTTagCompound journal = pending.journal;
        NBTTagCompound current = fixtures.captured(0);
        IGridNode existingNode = (IGridNode) proxyField(proxy, "node");
        NBTTagCompound savedNode = new NBTTagCompound();
        existingNode.saveToNBT("node", savedNode);
        System.out.println(
            PREFIX + "ROLLBACK after native ticks journal="
                + rollbackIdentity(journal.getCompoundTag("proxy"))
                + " current="
                + rollbackIdentity(current.getCompoundTag("proxy"))
                + " existingNode="
                + rollbackIdentity(savedNode.getCompoundTag("node"))
                + " existingPlayerID="
                + existingNode.getPlayerID());
        NBTTagCompound originalIdentity = journal.getCompoundTag("proxy");
        NBTTagCompound currentIdentity = current.getCompoundTag("proxy");
        NBTTagCompound nativeIdentity = savedNode.getCompoundTag("node");
        int placingOwner = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(fixtures.actor.getGameProfile());
        check(
            originalIdentity.hasKey("p", 3) && currentIdentity.hasKey("p", 3)
                && nativeIdentity.hasKey("p", 3)
                && originalIdentity.getTag("p")
                    .equals(currentIdentity.getTag("p"))
                && originalIdentity.getTag("p")
                    .equals(nativeIdentity.getTag("p"))
                && existingNode.getPlayerID() == originalIdentity.getInteger("p")
                && existingNode.getPlayerID() != placingOwner,
            "trusted_world_rollback_retains_exact_old_owner_without_actor_takeover");
        check(
            originalIdentity.hasKey("k", 4) && currentIdentity.hasKey("k", 4)
                && nativeIdentity.hasKey("k", 4)
                && originalIdentity.getTag("k")
                    .equals(currentIdentity.getTag("k"))
                && originalIdentity.getTag("k")
                    .equals(nativeIdentity.getTag("k")),
            "trusted_world_rollback_retains_exact_old_security_identity");
        // AE1080 Grid.remove:112/121 retires the old single-node grid. Grid.add:138-173 may allocate
        // a new storage when the loaded storage still references that old grid. The raw journal was
        // checked in full before ready; after real native ticks the persisted g must match the live node.
        check(
            currentIdentity.hasKey("g", 4) && nativeIdentity.hasKey("g", 4)
                && currentIdentity.getLong("g") > 0L
                && nativeIdentity.getLong("g") == currentIdentity.getLong("g")
                && existingNode.getGrid() != null,
            "trusted_world_rollback_persists_actual_existing_native_grid_storage");
        check(
            Arrays.equals(bytes(pending.cell), bytes(restored)),
            "rollback_never_uses_blueprint_empty_cell_projection");
    }

    private static String rollbackIdentity(NBTTagCompound identity) {
        return "{g=" + identity.getLong("g") + ",p=" + identity.getInteger("p") + ",k=" + identity.getLong("k") + "}";
    }

    /** Read-only verification for the real Socket driver. Never ready/join/load/install or access a source bucket. */
    public static NBTTagCompound assertPlaced(Block block, int metadata, TileEntity installed, NBTTagCompound expected,
        EntityPlayerMP placingActor) throws Exception {
        check(
            installed != null && installed.getWorldObj() == placingActor.worldObj
                && HologramAeBlueprint.matchesPlaced(block, metadata, installed, expected),
            "socket_placed_configuration_matches_review");
        List<Object> hosts = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        boolean multipart = block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart");
        if (multipart || installed instanceof TileCableBus) {
            Object bus;
            if (multipart) {
                // FMP generates TileMultipart_cmp classes outside the package of their official native base.
                check(
                    Class.forName("codechicken.multipart.TileMultipart")
                        .isInstance(installed),
                    "socket_placed_multipart_native_base_matches");
                bus = nativeMultipartCableBus(installed);
            } else bus = installed;
            for (ForgeDirection side : ForgeDirection.values()) {
                Object part = bus.getClass()
                    .getMethod("getPart", ForgeDirection.class)
                    .invoke(bus, side);
                if (part != null) {
                    hosts.add(part);
                    paths.add("side:" + side.ordinal());
                }
            }
        } else {
            hosts.add(installed);
            paths.add("tile");
        }
        check(!hosts.isEmpty(), "socket_placed_native_grid_hosts_exist");
        Field ready = AENetworkProxy.class.getDeclaredField("isReady");
        ready.setAccessible(true);
        Field nodeField = AENetworkProxy.class.getDeclaredField("node");
        nodeField.setAccessible(true);
        int owner = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(placingActor.getGameProfile());
        NBTTagCompound receipt = new NBTTagCompound();
        NBTTagList nodes = new NBTTagList();
        for (int i = 0; i < hosts.size(); i++) {
            Object host = hosts.get(i);
            String path = paths.get(i);
            check(host instanceof IGridProxyable, "socket_placed_native_grid_host_exists_" + path);
            AENetworkProxy proxy = ((IGridProxyable) host).getProxy();
            IGridNode nativeNode = (IGridNode) nodeField.get(proxy);
            // Inspect every existing node. getNode() could create one and conceal a failed ready lifecycle.
            check(ready.getBoolean(proxy) && nativeNode != null, "socket_placed_node_was_already_ready_" + path);
            check(nativeNode.getPlayerID() == owner, "socket_placed_actor_owner_matches_" + path);
            NBTTagCompound saved = new NBTTagCompound();
            nativeNode.saveToNBT("node", saved);
            check(saved.hasKey("node", 10), "socket_placed_grid_identity_is_persisted_" + path);
            NBTTagCompound record = saved.getCompoundTag("node");
            if (i == 0) receipt.setTag("node", record.copy());
            record.setString("path", path);
            nodes.appendTag(record);
        }
        receipt.setTag("nodes", nodes);
        receipt.setInteger("nodeCount", hosts.size());
        receipt.setBoolean("configurationMatches", true);
        receipt.setBoolean("ready", true);
        receipt.setInteger("actorId", owner);
        if (block.getClass()
            .getName()
            .equals("appeng.block.storage.BlockDrive")) {
            ItemStack cell = ((AEBaseInvTile) installed).getInternalInventory()
                .getStackInSlot(0);
            long contents = cell == null ? 0L : storedItems(cell);
            check(contents == 0L, "socket_paid_drive_retains_no_source_cell_contents");
            receipt.setLong("cellContents", contents);
        }
        return receipt;
    }

    private static NBTTagCompound interfaceBusSeed() {
        ItemStack cable = AEApi.instance()
            .definitions()
            .parts()
            .cableGlass()
            .stack(appeng.api.util.AEColor.Transparent, 1);
        ItemStack iface = stack(
            AEApi.instance()
                .definitions()
                .parts()
                .iface());
        NBTTagCompound bus = new NBTTagCompound();
        for (ItemStack shell : new ItemStack[] { iface, cable }) {
            IPart part = ((IPartItem) shell.getItem()).createPartFromItemStack(shell);
            NBTTagCompound definition = new NBTTagCompound(), extra = new NBTTagCompound();
            part.getItemStack(PartItemStack.World)
                .writeToNBT(definition);
            part.writeToNBT(extra);
            int side = shell == iface ? 2 : 6;
            bus.setTag("def:" + side, definition);
            bus.setTag("extra:" + side, extra);
        }
        return bus;
    }

    private static NBTTagCompound fmp(NBTTagCompound cable) {
        NBTTagCompound payload = new NBTTagCompound(), part = (NBTTagCompound) cable.copy();
        part.setString("id", "ae2_cablebus");
        NBTTagList parts = new NBTTagList();
        parts.appendTag(part);
        payload.setString("id", "savedMultipart");
        payload.setTag("parts", parts);
        return payload;
    }

    private static IPart nativeInterface(TileEntity tile, boolean multipart) throws Exception {
        if (!multipart) return ((TileCableBus) tile).getPart(ForgeDirection.NORTH);
        Object bus = nativeMultipartCableBus(tile);
        return (IPart) bus.getClass()
            .getMethod("getPart", ForgeDirection.class)
            .invoke(bus, ForgeDirection.NORTH);
    }

    private static Object nativeMultipartCableBus(TileEntity tile) throws Exception {
        // Official PartRegistry.construct creates a combined instance of CableBusPart with AE capability layers.
        // Its generated runtime subclass is still this registered native base and retains the exact part id.
        Class<?> nativeBase = Class.forName("appeng.fmp.CableBusPart");
        List<?> parts = (List<?>) tile.getClass()
            .getMethod("jPartList")
            .invoke(tile);
        List<String> actual = new ArrayList<>();
        for (Object part : parts) {
            String type = (String) part.getClass()
                .getMethod("getType")
                .invoke(part);
            actual.add(
                part.getClass()
                    .getName() + "/"
                    + type);
            if (nativeBase.isInstance(part) && type.equals("ae2_cablebus")) return part.getClass()
                .getMethod("getCableBus")
                .invoke(part);
        }
        throw new AssertionError("Missing genuine registered FMP CableBusPart; actual=" + actual);
    }

    private static IGridNode node(TileEntity tile, int index) throws Exception {
        Object host = index < 2 ? tile : nativeInterface(tile, index == 3);
        return (IGridNode) proxyField(((IGridProxyable) host).getProxy(), "node");
    }

    private static void bindSourceActor(TileEntity tile, EntityPlayerMP actor) {
        AENetworkProxy proxy = ((IGridProxyable) tile).getProxy();
        proxy.setOwner(actor);
        // setBlock/validate already queued native init. Only the real native tick makes it ready.
    }

    private static Object proxyField(AENetworkProxy proxy, String name) throws Exception {
        Field field = AENetworkProxy.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(proxy);
    }

    private static List<Object> gridHosts(Block block, TileEntity tile) throws Exception {
        List<Object> hosts = new ArrayList<>();
        boolean multipart = block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart");
        if (multipart || tile instanceof TileCableBus) {
            Object bus = multipart ? nativeMultipartCableBus(tile) : tile;
            for (ForgeDirection side : ForgeDirection.values()) {
                Object part = bus.getClass()
                    .getMethod("getPart", ForgeDirection.class)
                    .invoke(bus, side);
                if (part != null) hosts.add(part);
            }
        } else hosts.add(tile);
        return hosts;
    }

    private static void sourceReady(Fixtures fixtures, int index) throws Exception {
        EntityPlayerMP sourceActor = FakePlayerFactory.get(
            fixtures.world,
            new GameProfile(UUID.fromString("720a585f-0201-4e58-814d-639c7f8b92a2"), "GTMBAeSourceOwner"));
        int owner = appeng.core.worlddata.WorldData.instance()
            .playerData()
            .getPlayerID(sourceActor.getGameProfile());
        List<Object> hosts = gridHosts(fixtures.block(index), fixtures.sourceTile(index));
        check(!hosts.isEmpty(), "real_source_native_hosts_exist_" + index);
        for (Object host : hosts) {
            check(host instanceof IGridProxyable, "real_source_native_proxy_type_" + index);
            AENetworkProxy proxy = ((IGridProxyable) host).getProxy();
            IGridNode node = (IGridNode) proxyField(proxy, "node");
            check(
                Boolean.TRUE.equals(proxyField(proxy, "isReady")) && node != null,
                "source_joined_only_after_real_native_ticks_" + index);
            check(node.getPlayerID() == owner, "real_source_old_owner_bound_by_native_tick_" + index);
            NBTTagCompound saved = new NBTTagCompound();
            node.saveToNBT("node", saved);
            check(saved.hasKey("node", 10), "real_source_old_grid_identity_captured_after_native_tick_" + index);
        }
    }

    /** Existing native source proxies and cargo only; caller must first allow the real init ticks to run. */
    public static void assertSourceReady(Fixtures fixtures) throws Exception {
        for (int i = 0; i < fixtures.sourceCount; i++) sourceReady(fixtures, i);
        check(storedItems(fixtures.sourceCell()) == 137L, "native_source_retains_137_items_after_real_ready_ticks");
    }

    private static void assertPreReady(Block block, TileEntity tile, NBTTagCompound expected, EntityPlayerMP actor)
        throws Exception {
        check(
            HologramAeBlueprint.matchesPlaced(block, 0, tile, expected),
            "pre_ready_device_configuration_matches_review");
        List<Object> hosts = gridHosts(block, tile);
        check(!hosts.isEmpty(), "pre_ready_native_hosts_exist");
        for (Object host : hosts) {
            check(host instanceof IGridProxyable, "pre_ready_native_proxy_type");
            AENetworkProxy proxy = ((IGridProxyable) host).getProxy();
            check(
                !Boolean.TRUE.equals(proxyField(proxy, "isReady")) && proxyField(proxy, "node") == null,
                "ordinary_place_event_precedes_native_ready_tick");
            check(
                proxyField(proxy, "data") == null && proxyField(proxy, "owner") == actor,
                "pre_ready_old_network_data_cleared_and_actual_actor_bound");
        }
    }

    private static long storedItems(ItemStack cell) throws Exception {
        return new ItemCellInventory(cell.copy(), null).getStoredItemCount();
    }

    private static ItemStack stack(IItemDefinition definition) {
        if (!definition.maybeStack(1)
            .isPresent()) throw new AssertionError("Required genuine AE definition disabled");
        return definition.maybeStack(1)
            .get();
    }

    private static HologramAeBlueprint.Slot slot(HologramAeBlueprint.Analysis analysis, String id) {
        for (HologramAeBlueprint.Slot slot : analysis.slots) if (slot.id.equals(id)) return slot;
        throw new AssertionError("Missing official slot " + id);
    }

    private static boolean issue(HologramAeBlueprint.Analysis analysis, String code) {
        for (HologramAeBlueprint.Issue issue : analysis.issues) if (issue.code.equals(code)) return true;
        return false;
    }

    private static String analysisDiagnostic(HologramAeBlueprint.Analysis analysis, NBTTagCompound raw) {
        StringBuilder result = new StringBuilder(
            "supported=" + analysis.supported
                + " blocking="
                + analysis.blocking
                + " issueCount="
                + analysis.issues.size()
                + " issues=[");
        int printed = 0;
        // Put blockers first so the exact denied schema remains visible within the bounded diagnostic.
        for (int pass = 0; pass < 2; pass++) for (HologramAeBlueprint.Issue issue : analysis.issues) {
            if (issue.severity.equals("blocker") != (pass == 0) || printed >= 8) continue;
            if (printed++ > 0) result.append("; ");
            result.append(issue.code)
                .append(":")
                .append(issue.severity)
                .append(":")
                .append(issue.path)
                .append(":")
                .append(issue.message.substring(0, Math.min(issue.message.length(), 120)));
        }
        result.append("] rawRoots=[");
        List<String> keys = new ArrayList<>();
        for (Object key : raw.func_150296_c()) keys.add((String) key);
        Collections.sort(keys);
        for (int i = 0; i < Math.min(keys.size(), 32); i++) {
            if (i > 0) result.append(",");
            String key = keys.get(i);
            result.append(key.substring(0, Math.min(key.length(), 80)))
                .append("=")
                .append(
                    raw.getTag(key)
                        .getId());
        }
        return result.append("] rootCount=")
            .append(keys.size())
            .toString();
    }

    private static int cost(List<ItemStack> costs, ItemStack sample) {
        int total = 0;
        for (ItemStack item : costs)
            if (item.isItemEqual(sample) && ItemStack.areItemStackTagsEqual(item, sample)) total += item.stackSize;
        return total;
    }

    private static int total(List<ItemStack> costs) {
        int total = 0;
        for (ItemStack item : costs) total += item.stackSize;
        return total;
    }

    private static void fill(EntityPlayerMP actor, List<ItemStack> costs) {
        for (int i = 1; i < actor.inventory.mainInventory.length; i++) actor.inventory.mainInventory[i] = null;
        if (costs.size() >= actor.inventory.mainInventory.length)
            throw new AssertionError("Fixture inventory capacity");
        for (int i = 0; i < costs.size(); i++) actor.inventory.mainInventory[i + 1] = costs.get(i)
            .copy();
    }

    private static ItemStack[] inventory(EntityPlayerMP actor) {
        ItemStack[] result = new ItemStack[actor.inventory.mainInventory.length];
        for (int i = 0; i < result.length; i++)
            if (actor.inventory.mainInventory[i] != null) result[i] = actor.inventory.mainInventory[i].copy();
        return result;
    }

    private static boolean sameInventory(ItemStack[] old, EntityPlayerMP actor) {
        for (int i = 0; i < old.length; i++)
            if (!ItemStack.areItemStacksEqual(old[i], actor.inventory.mainInventory[i])) return false;
        return true;
    }

    private static int materialCount(EntityPlayerMP actor) {
        int count = 0;
        for (int i = 1; i < actor.inventory.mainInventory.length; i++)
            if (actor.inventory.mainInventory[i] != null) count += actor.inventory.mainInventory[i].stackSize;
        return count;
    }

    private static boolean operate(Object session, HologramCapture.Cell cell) throws Exception {
        return (Boolean) smokeCall(
            "operate",
            new Class<?>[] { Object.class, HologramCapture.Cell.class },
            session,
            cell);
    }

    private static Object smokeCall(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = HologramNativePlacementSmoke.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception) throw (Exception) failed.getCause();
            if (failed.getCause() instanceof Error) throw (Error) failed.getCause();
            throw failed;
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Block registeredBlock(String type) {
        for (Object value : Block.blockRegistry) if (value instanceof Block && value.getClass()
            .getName()
            .equals(type)) return (Block) value;
        throw new AssertionError("Required native block unavailable: " + type);
    }

    private static byte[] bytes(ItemStack stack) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        stack.writeToNBT(tag);
        return bytes(tag);
    }

    private static byte[] bytes(NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            CompressedStreamTools.write(tag, out);
        }
        return bytes.toByteArray();
    }

    private static void isolated(WorldServer world) {
        if (world == null) throw new IllegalArgumentException("Isolated smoke/interaction world required");
        String name = world.getWorldInfo()
            .getWorldName();
        if (!name.startsWith("hologram-smoke-") && !name.startsWith("hologram-interaction-"))
            throw new IllegalArgumentException("Isolated smoke/interaction world required");
    }

    private interface Action {

        void run() throws Exception;
    }

    private static void expectDenied(Action action, String message) throws Exception {
        boolean denied = false;
        try {
            action.run();
        } catch (IOException expected) {
            denied = true;
        }
        check(denied, message);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(PREFIX + message);
        checks++;
        System.out.println(PREFIX + "PASS " + message);
    }

    public static final class Veto {

        private final Block block;
        private final NBTTagCompound expected;
        private final EntityPlayerMP actor;
        private final int x, y, z;
        int events;
        boolean complete, lifecycleValid;
        TileEntity observedTile;
        String failure = "";

        Veto(Block block, NBTTagCompound expected, EntityPlayerMP actor, int x, int y, int z) {
            this.block = block;
            this.expected = (NBTTagCompound) expected.copy();
            this.actor = actor;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @SubscribeEvent
        public void place(BlockEvent.PlaceEvent event) {
            if (event.x != x || event.y != y || event.z != z) return;
            events++;
            try {
                observedTile = event.world.getTileEntity(x, y, z);
                complete = event.world.getBlock(x, y, z) == block
                    && HologramAeBlueprint.matchesPlaced(block, 0, observedTile, expected);
                if (block.getClass()
                    .getName()
                    .equals("codechicken.multipart.BlockMultipart")) {
                    NBTTagCompound receipt = assertPlaced(block, 0, observedTile, expected, actor);
                    lifecycleValid = receipt.getBoolean("ready") && receipt.getBoolean("configurationMatches");
                } else {
                    assertPreReady(block, observedTile, expected, actor);
                    lifecycleValid = true;
                }
            } catch (Exception | AssertionError failed) {
                failure = failed.toString();
            } finally {
                // Even a failed native-ready observation must exercise actual cancellation and rollback.
                event.setCanceled(true);
            }
        }
    }
}
