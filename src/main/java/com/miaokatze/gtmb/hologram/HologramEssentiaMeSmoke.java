package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.helpers.WirelessTerminalGuiObject;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileEnergyCell;
import appeng.tile.networking.TileWireless;
import appeng.tile.storage.TileDrive;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Real finite ThE cell in an AE drive, wireless grid and production Service placement/rollback. */
public final class HologramEssentiaMeSmoke {

    private static final int X = 1460, Y = 88, Z = 448;
    private static Fixture prepared;

    private HologramEssentiaMeSmoke() {}

    /** Called before natural FML ticks; readiness and storage registration are never replaced or forced. */
    public static void prepare(WorldServer world, EntityPlayerMP actor) throws Exception {
        guard(world, actor);
        if (prepared != null) throw new IllegalStateException("An essentia ME fixture is already prepared");
        Fixture fixture = new Fixture(world, actor);
        try {
            fixture.prepare();
            prepared = fixture;
        } catch (Exception | Error failure) {
            fixture.release();
            throw failure;
        }
    }

    /** Called after at least fifty actual world ticks, once the original registered AE nodes have booted. */
    public static void run(WorldServer world, EntityPlayerMP actor) throws Exception {
        guard(world, actor);
        Fixture fixture = prepared;
        if (fixture == null || fixture.world != world || fixture.actor != actor)
            throw new IllegalStateException("The same prepared native world and actor are required");
        try {
            fixture.run();
        } catch (Exception | Error failure) {
            fixture.failed(failure);
            throw failure;
        } finally {
            fixture.release();
            prepared = null;
        }
    }

    private static void guard(WorldServer world, EntityPlayerMP actor) {
        if (world == null || actor == null
            || actor.worldObj != world
            || !world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-followup-"))
            throw new IllegalArgumentException("An isolated follow-up FML world and native server actor are required");
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static final class Fixture {

        final WorldServer world;
        final EntityPlayerMP actor;
        final NBTTagCompound receipt = new NBTTagCompound();
        TileEnergyCell power;
        TileDrive drive;
        TileWireless wap;
        ItemStack storageCell, terminal;
        ForgeChunkManager.Ticket ticket;
        long startTick;
        int assertions;
        IMEMonitor<?> liveMonitor;
        PlayerSource liveAction;
        Object activeSession;
        HologramCapture.Cell activeTarget;

        Fixture(WorldServer world, EntityPlayerMP actor) {
            this.world = world;
            this.actor = actor;
        }

        void prepare() throws Exception {
            startTick = world.getTotalWorldTime();
            HologramEnergyNetworkSmoke.ensureFixtureChunkCallback();
            ticket = ForgeChunkManager.requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
            check(ticket != null, "actual_fixture_chunk_ticket");
            ticket.getModData()
                .setBoolean("gtmbPreviewSourceProbe", true);
            ChunkCoordIntPair coordinate = new ChunkCoordIntPair(X >> 4, Z >> 4);
            ForgeChunkManager.forceChunk(ticket, coordinate);
            Chunk chunk = world.theChunkProviderServer.loadChunk(coordinate.chunkXPos, coordinate.chunkZPos);
            check(
                chunk != null && !(chunk instanceof EmptyChunk) && chunk.isChunkLoaded,
                "native_provider_chunk_loaded");
            power = place(block("appeng.block.networking.BlockEnergyCell"), X, TileEnergyCell.class);
            drive = place(
                AEApi.instance()
                    .definitions()
                    .blocks()
                    .drive()
                    .maybeBlock()
                    .get(),
                X + 1,
                TileDrive.class);
            wap = place(block("appeng.block.networking.BlockWireless"), X + 2, TileWireless.class);
            wap.setOrientation(ForgeDirection.EAST, ForgeDirection.UP);
            // Seed the registered finite tile through its own native reader before natural node boot.
            NBTTagCompound initialPower = new NBTTagCompound();
            initialPower.setDouble("internalCurrentPower", 100000);
            power.readFromNBT_TileEnergyCell(initialPower);
            power.markDirty();
            Item cellItem = item("thaumicenergistics.common.items.ItemEssentiaCell");
            storageCell = new ItemStack(cellItem, 1, 0); // audited Type_1K, not Type_Creative (meta 4)
            IAEStackType<?> type = HologramEssentia.stackType();
            check(
                type != null && type.getId()
                    .equals("essentia"),
                "actual_registered_the_stack_type");
            IMEInventoryHandler handler = AEApi.instance()
                .registries()
                .cell()
                .getCellInventory(storageCell, null, type);
            check(
                handler != null && handler.getClass()
                    .getName()
                    .equals("thaumicenergistics.common.inventory.EssentiaCellInventoryHandler"),
                "actual_finite_the_cell_native_handler");
            drive.getInternalInventory()
                .setInventorySlotContents(0, storageCell);
            terminal = AEApi.instance()
                .definitions()
                .items()
                .wirelessTerminal()
                .maybeStack(1)
                .get();
            receipt.setString("scenario", "essentia-ae-native-monitor");
            receipt.setString(
                "world",
                world.getWorldInfo()
                    .getWorldName());
            receipt.setString(
                "fixtureKind",
                "real finite ThE cell, AE grid, native wireless terminal and Service fixture Session");
            receipt.setString("cellItem", Item.itemRegistry.getNameForObject(cellItem));
            receipt.setInteger("cellMetadata", storageCell.getItemDamage());
            receipt.setString(
                "cellHandlerClass",
                handler.getClass()
                    .getName());
            System.out
                .println("[GTMB-ESSENTIA-ME] PREPARED finite native cell/drive/WAP; awaiting natural FML grid boot");
        }

        <T extends TileEntity> T place(Block block, int x, Class<T> type) {
            check(world.isAirBlock(x, Y, Z), "fresh_grid_position_" + x);
            check(world.setBlock(x, Y, Z, block, 0, 3), "registered_grid_block_" + x);
            TileEntity tile = world.getTileEntity(x, Y, Z);
            if (tile == null) {
                TileEntity nativeTile = block.createTileEntity(world, 0);
                if (nativeTile == null && block instanceof appeng.block.AEBaseTileBlock)
                    nativeTile = ((appeng.block.AEBaseTileBlock) block).createNewTileEntity(world, 0);
                check(type.isInstance(nativeTile), "registered_native_factory_" + x);
                world.setTileEntity(x, Y, Z, nativeTile);
                tile = world.getTileEntity(x, Y, Z);
            }
            check(type.isInstance(tile) && tile.getWorldObj() == world && !tile.isInvalid(), "actual_grid_tile_" + x);
            T nativeTile = type.cast(tile);
            check(nativeTile instanceof IGridProxyable, "native_grid_proxy_contract_" + x);
            ((IGridProxyable) nativeTile).getProxy()
                .setOwner(actor);
            nativeTile.markDirty();
            return nativeTile;
        }

        void run() throws Exception {
            check(world.getTotalWorldTime() - startTick >= 50, "natural_world_ticks_elapsed");
            check(
                world.getPersistentChunks()
                    .containsEntry(new ChunkCoordIntPair(X >> 4, Z >> 4), ticket),
                "fixture_native_chunk_still_retained");
            check(
                world.getTileEntity(X + 1, Y, Z) == drive && world.getTileEntity(X + 2, Y, Z) == wap,
                "original_native_grid_tiles_retained_through_boot");
            check(
                drive.getProxy()
                    .isActive() && wap.getActionableNode() != null
                    && wap.getActionableNode()
                        .isActive(),
                "actual_drive_and_wap_nodes_active");
            check(
                drive.getProxy()
                    .getGrid()
                    == wap.getProxy()
                        .getGrid(),
                "physically_adjacent_nodes_share_real_grid");
            ISecurityGrid security = wap.getProxy()
                .getGrid()
                .getCache(ISecurityGrid.class);
            check(
                security.hasPermission(actor, SecurityPermissions.EXTRACT)
                    && security.hasPermission(actor, SecurityPermissions.INJECT)
                    && security.hasPermission(actor, SecurityPermissions.BUILD),
                "native_actor_grid_permissions");
            IWirelessTermHandler handler = AEApi.instance()
                .registries()
                .wireless()
                .getWirelessTerminalHandler(terminal);
            check(handler != null && wap.getLocatableSerial() != 0, "native_locatable_wireless_identity");
            handler.setEncryptionKey(terminal, Long.toString(wap.getLocatableSerial()), "Actual essentia ME fixture");
            for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++)
                if (slot != actor.inventory.currentItem) actor.inventory.mainInventory[slot] = null;
            actor.capabilities.isCreativeMode = false;
            actor.inventory.mainInventory[1] = new ItemStack(block("thaumcraft.common.blocks.BlockJar"));
            actor.inventory.mainInventory[2] = terminal;
            actor.setPosition(X + 2.5, Y, Z + 1.5);
            WirelessTerminalGuiObject gui = new WirelessTerminalGuiObject(handler, terminal, actor, world, -1, 0, 0);
            check(
                gui.rangeCheck() && gui.getActionableNode() != null
                    && gui.getActionableNode()
                        .isActive(),
                "carried_linked_terminal_in_native_wap_range");
            IAEStackType<?> type = HologramEssentia.stackType();
            IMEMonitor monitor = gui.getMEMonitor(type);
            check(
                monitor != null && monitor == wap.getProxy()
                    .getStorage()
                    .getMEMonitor(type),
                "terminal_resolves_the_actual_grid_essentia_monitor");
            check(
                drive.getCellArray(type) != null && !drive.getCellArray(type)
                    .isEmpty(),
                "real_drive_exposes_native_essentia_cell_to_grid");
            receipt.setString(
                "monitorClass",
                monitor.getClass()
                    .getName());
            receipt.setString("aspect", "aer");
            PlayerSource action = new PlayerSource(actor, gui);
            liveMonitor = monitor;
            liveAction = action;
            receipt.setBoolean("realNativeMonitor", true);
            check(
                monitor.injectItems(HologramEssentia.request("aer", 64), Actionable.MODULATE, action) == null,
                "actual_monitor_injected_64_aer");
            check(
                monitor.injectItems(HologramEssentia.request("ignis", 11), Actionable.MODULATE, action) == null,
                "independent_aspect_injected_without_conversion");
            check(
                count(monitor, action, "aer") == 64 && cellCount(type, action, "aer") == 64,
                "real_grid_and_persisted_finite_cell_both_hold_64_aer");
            receipt.setTag("cellBefore", storageCell.writeToNBT(new NBTTagCompound()));
            HologramMaterials.Settings settings = new HologramMaterials.Settings();
            settings.containers = false;
            HologramMaterials.Context context = new HologramMaterials.Context(actor, settings);
            IEnergyGrid energy = wap.getProxy()
                .getEnergy();
            double powerBeforeQuote = energy.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE);
            check(
                context.essentiaCount("aer", 32) == 32 && context.essentiaCount("aer", 128) == 64,
                "production_quote_uses_real_native_monitor");
            check(
                count(monitor, action, "aer") == 64 && cellCount(type, action, "aer") == 64
                    && energy.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE) == powerBeforeQuote,
                "quote_changes_neither_real_essentia_nor_transfer_power");
            receipt.setLong("quoteBefore", 64);
            receipt.setLong("quoteAfter", count(monitor, action, "aer"));
            receipt.setTag("inventoryBefore", inventory(actor));

            Block jar = block("thaumcraft.common.blocks.BlockJar");
            NBTTagCompound template = jarPayload(X + 4, 32);
            check(
                world.isAirBlock(X + 4, Y, Z) && world.setBlock(X + 4, Y, Z, jar, 0, 3),
                "real_source_template_jar_created");
            world.getTileEntity(X + 4, Y, Z)
                .readFromNBT(template);
            TileEntity sourceTile = world.getTileEntity(X + 4, Y, Z);
            NBTTagCompound sourceBefore = snapshot(sourceTile);
            receipt.setTag("sourceTileBefore", sourceBefore.copy());
            Object service = session(actor, jar, sourceBefore, X + 5);
            HologramCapture.Cell target = ((HologramCapture) field(service, "capture")).cells.get(0);
            activeSession = service;
            activeTarget = target;
            Veto veto = new Veto(target, monitor, action);
            double beforeCancelledPower = energy.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE);
            MinecraftForge.EVENT_BUS.register(veto);
            try {
                // A true result means the cell was handled, including a protected cancellation.
                // The world, status, real event and refunded stock below prove that placement was cancelled.
                receipt.setBoolean("cancelledCellHandled", operate(service, target));
            } finally {
                MinecraftForge.EVENT_BUS.unregister(veto);
                receipt.setInteger("cancelledEvents", veto.cancelledEvents);
                receipt.setInteger("nativeEmptyShellEvents", veto.emptyShellEvents);
                receipt.setInteger("unexpectedEvents", veto.unexpectedEvents);
                receipt.setTag("placeEventObservations", veto.observations);
                receipt.setTag("inventoryAfterCancelled", inventory(actor));
                receipt.setString("cancelledCellStatus", target.status);
                receipt.setString("cancelledServiceStatus", String.valueOf(field(service, "status")));
                receipt.setString("cancelledResourceError", target.resourceError);
                receipt.setBoolean("cancelledTargetAir", world.isAirBlock(target.x, Y, Z));
                TileEntity cancelledTile = world.getTileEntity(target.x, Y, Z);
                if (cancelledTile != null) receipt.setTag("targetAfterCancelled", snapshot(cancelledTile));
                receipt.setLong("refundBefore", 64);
                receipt.setLong("refundDuring", veto.debited);
                receipt.setLong("refundAfter", count(monitor, action, "aer"));
                receipt.setLong("refundCellAfter", cellCount(type, action, "aer"));
                receipt.setDouble("cancelledPowerBefore", beforeCancelledPower);
                receipt.setDouble(
                    "cancelledPowerAfter",
                    energy.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE));
            }
            check(
                veto.events == 2 && veto.emptyShellEvents == 1
                    && veto.cancelledEvents == 1
                    && veto.unexpectedEvents == 0
                    && veto.complete
                    && veto.debited == 32,
                "real_place_event_observes_paid_32_point_jar_and_actual_source_debit");
            check(
                world.isAirBlock(target.x, Y, Z) && "protected".equals(target.status)
                    && count(monitor, action, "aer") == 64
                    && cellCount(type, action, "aer") == 64,
                "cancelled_service_refunds_actual_32_points");
            check(
                inventory(actor).equals(receipt.getTagList("inventoryBefore", 10)),
                "cancelled_service_returns_exact_empty_shell_and_terminal");
            check(
                energy.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE) == beforeCancelledPower,
                "cancelled_transfer_restores_actual_finite_grid_fee");
            receipt.setLong("refundBefore", 64);
            receipt.setLong("refundDuring", veto.debited);
            receipt.setLong("refundAfter", count(monitor, action, "aer"));
            new HologramMaterials.Context(actor, settings).flushRecovery();
            new HologramMaterials.Context(actor, settings).flushRecovery();
            check(count(monitor, action, "aer") == 64, "recovery_retry_cannot_duplicate_cancelled_service_refund");
            receipt.setLong("refundAfterRetry", count(monitor, action, "aer"));

            check(operate(service, target), "production_service_pays_real_me_essentia_and_places_jar");
            check(
                count(monitor, action, "aer") == 32 && cellCount(type, action, "aer") == 32,
                "successful_service_debits_actual_grid_and_cell_from_64_to_32");
            NBTTagCompound placed = snapshot(world.getTileEntity(target.x, Y, Z));
            HologramEssentia.Cost contents = HologramEssentia.jarCost(placed);
            check(
                contents != null && contents.amount == 32 && contents.aspect.equals("aer"),
                "actual_target_jar_contains_exact_paid_aspect_points");
            check(count(monitor, action, "ignis") == 11, "different_native_aspect_remains_untouched");
            receipt.setLong("successBefore", 64);
            receipt.setLong("successAfter", 32);
            receipt.setTag("targetAfter", placed.copy());
            receipt.setLong("targetAspectAfter", contents.amount);
            check(
                operate(service, target) && count(monitor, action, "aer") == 32,
                "already_placed_target_never_pays_a_second_time");

            // A production transaction's close fence is checked against the genuine monitor, independently of events.
            HologramMaterials.Context live = new HologramMaterials.Context(actor, settings);
            HologramMaterials.Transaction failed = live.begin();
            check(
                failed.takeEssentia("aer", 8, false) && count(monitor, action, "aer") == 24,
                "actual_native_transaction_reservation_8");
            failed.rollbackExternal();
            failed.rollbackExternal();
            live.flushRecovery();
            check(
                count(monitor, action, "aer") == 32 && cellCount(type, action, "aer") == 32,
                "native_transaction_rollback_returns_8_exactly_once");
            receipt.setLong("transactionRollbackAfterTwice", count(monitor, action, "aer"));

            actor.inventory.mainInventory[1] = new ItemStack(jar);
            Object deficient = session(actor, jar, jarPayload(X + 4, 64), X + 6);
            HologramCapture.Cell shortage = ((HologramCapture) field(deficient, "capture")).cells.get(0);
            activeSession = deficient;
            activeTarget = shortage;
            NBTTagList deficitInventory = inventory(actor);
            double deficitPower = energy.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE);
            receipt.setBoolean("deficitCellHandled", operate(deficient, shortage));
            check(
                world.isAirBlock(shortage.x, Y, Z) && "missing".equals(shortage.status),
                "real_native_monitor_deficit_blocks_copied_64_point_target");
            check(
                count(monitor, action, "aer") == 32 && cellCount(type, action, "aer") == 32
                    && deficitInventory.equals(inventory(actor))
                    && energy.extractAEPower(1000000, Actionable.SIMULATE, PowerMultiplier.ONE) == deficitPower,
                "deficit_preserves_actual_cell_stock_shell_and_network_power");
            receipt.setLong("deficitRequired", 64);
            receipt.setLong("deficitBefore", 32);
            receipt.setLong("deficitAfter", count(monitor, action, "aer"));
            check(
                world.getTileEntity(X + 4, Y, Z) == sourceTile && sourceBefore.equals(snapshot(sourceTile)),
                "actual_source_template_identity_and_contents_unchanged");
            receipt.setTag("sourceTileAfter", snapshot(sourceTile));
            receipt.setTag("cellAfter", storageCell.writeToNBT(new NBTTagCompound()));
            receipt.setTag("inventoryAfter", inventory(actor));
            receipt.setBoolean("sourceUnchanged", true);
            receipt.setBoolean("passed", true);
            receipt.setBoolean("realNativeMonitor", true);
            receipt.setBoolean("actualPlaceEventRefund", true);
            receipt.setInteger("assertions", assertions);
            receipt.setLong("naturalWorldTicks", world.getTotalWorldTime() - startTick);
            HologramV011Evidence.write("essentia-ae-native-monitor.nbt", receipt);
            System.out.println(
                "[GTMB-ESSENTIA-ME] FINAL PASS assertions=" + assertions
                    + " lifecycle=real-FML monitor=actual-AE-grid cell=actual-finite-ThE-cell service=production");
        }

        long cellCount(IAEStackType<?> type, PlayerSource action, String aspect) {
            IMEInventoryHandler handler = AEApi.instance()
                .registries()
                .cell()
                .getCellInventory(storageCell, null, type);
            IAEStack<?> actual = (IAEStack<?>) handler
                .extractItems(HologramEssentia.request(aspect, 128), Actionable.SIMULATE, action);
            return actual == null ? 0 : actual.getStackSize();
        }

        void release() {
            if (ticket != null) {
                ForgeChunkManager.releaseTicket(ticket);
                ticket = null;
            }
        }

        void failed(Throwable failure) {
            receipt.setBoolean("passed", false);
            receipt.setString("failure", failure.toString());
            receipt.setInteger("assertions", assertions);
            receipt.setLong("naturalWorldTicks", world.getTotalWorldTime() - startTick);
            receipt.setTag("inventoryAtFailure", inventory(actor));
            try {
                if (activeTarget != null) {
                    receipt.setString("failedCellStatus", activeTarget.status);
                    receipt.setString("failedResourceError", activeTarget.resourceError);
                    receipt.setBoolean(
                        "failedTargetAir",
                        world.isAirBlock(activeTarget.x, activeTarget.y, activeTarget.z));
                    TileEntity tile = world.getTileEntity(activeTarget.x, activeTarget.y, activeTarget.z);
                    if (tile != null) receipt.setTag("failedTargetTile", snapshot(tile));
                }
                if (activeSession != null) {
                    receipt.setString("failedServiceStatus", String.valueOf(field(activeSession, "status")));
                    receipt.setString(
                        "failedResourceMissing",
                        String.valueOf(field(activeSession, "blueprintResourceMissing")));
                }
                if (liveMonitor != null && liveAction != null) {
                    receipt.setLong("actualAerAtFailure", count(liveMonitor, liveAction, "aer"));
                    receipt.setLong("actualIgnisAtFailure", count(liveMonitor, liveAction, "ignis"));
                    receipt.setTag("actualCellAtFailure", storageCell.writeToNBT(new NBTTagCompound()));
                }
            } catch (Exception | LinkageError evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
                receipt.setString("diagnosticFailure", evidenceFailure.toString());
            }
            try {
                HologramV011Evidence.write("essentia-ae-native-monitor.nbt", receipt);
            } catch (Exception evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
        }

        void check(boolean condition, String description) {
            if (!condition) throw new AssertionError("Real essentia ME smoke: " + description);
            assertions++;
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static long count(IMEMonitor monitor, PlayerSource action, String aspect) {
        IAEStack<?> result = (IAEStack<?>) monitor
            .extractItems(HologramEssentia.request(aspect, 128), Actionable.SIMULATE, action);
        return result == null ? 0 : result.getStackSize();
    }

    public static final class Veto {

        final HologramCapture.Cell target;
        final IMEMonitor<?> monitor;
        final PlayerSource action;
        final NBTTagList observations = new NBTTagList();
        int events;
        int emptyShellEvents, cancelledEvents, unexpectedEvents;
        long debited;
        boolean complete;

        Veto(HologramCapture.Cell target, IMEMonitor<?> monitor, PlayerSource action) {
            this.target = target;
            this.monitor = monitor;
            this.action = action;
        }

        @SubscribeEvent
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.x != target.x || event.y != target.y || event.z != target.z) return;
            events++;
            TileEntity actual = event.world.getTileEntity(event.x, event.y, event.z);
            NBTTagCompound payload = actual == null ? null : snapshot(actual);
            boolean nativeJar = actual != null && actual.getClass()
                .getName()
                .equals("thaumcraft.common.tiles.TileJarFillable")
                && event.world.getBlock(event.x, event.y, event.z) == target.block
                && event.world.getBlockMetadata(event.x, event.y, event.z) == target.meta;
            boolean paidContents = nativeJar && HologramEssentia.isJar(payload)
                && payload.getShort("Amount") == 32
                && payload.getString("Aspect")
                    .equals("aer");
            debited = count(monitor, action, "aer");
            NBTTagCompound observation = new NBTTagCompound();
            observation.setInteger("event", events);
            observation.setIntArray("position", new int[] { event.x, event.y, event.z });
            observation.setString(
                "block",
                Block.blockRegistry.getNameForObject(event.world.getBlock(event.x, event.y, event.z)));
            observation.setInteger("metadata", event.world.getBlockMetadata(event.x, event.y, event.z));
            observation.setString(
                "tileClass",
                actual == null ? ""
                    : actual.getClass()
                        .getName());
            observation.setLong("sourceAer", debited);
            observation.setTag("inventoryDuring", inventory((EntityPlayerMP) event.player));
            if (payload != null) observation.setTag("targetTile", payload);
            if (paidContents && events == 2 && emptyShellEvents == 1 && debited == 32) {
                complete = true;
                cancelledEvents++;
                observation.setString("phase", "service-paid-contents");
                event.setCanceled(true);
            } else if (nativeJar && HologramEssentia.isJar(payload)
                && payload.getShort("Amount") == 0
                && payload.getString("Aspect")
                    .isEmpty()
                && events == 1
                && debited == 32) {
                    // StructureLib's native ItemBlock placement emits this before Service installs the paid contents.
                    emptyShellEvents++;
                    observation.setString("phase", "native-empty-shell");
                } else {
                    unexpectedEvents++;
                    observation.setString("phase", "unexpected-native-payload");
                    event.setCanceled(true);
                }
            observation.setBoolean("cancelled", event.isCanceled());
            observations.appendTag(observation);
        }
    }

    private static NBTTagCompound jarPayload(int sourceX, int amount) {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("id", "TileJar");
        payload.setString("Aspect", "aer");
        payload.setShort("Amount", (short) amount);
        payload.setInteger("x", sourceX);
        payload.setInteger("y", Y);
        payload.setInteger("z", Z);
        return payload;
    }

    /**
     * Explicit fixture Session; all pricing, extraction, PlaceEvent and rollback run through the production service.
     */
    @SuppressWarnings("unchecked")
    private static Object session(EntityPlayerMP actor, Block jar, NBTTagCompound payload, int x) throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
        Constructor<?> constructor = type.getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(actor, x, Y, Z);
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.controllerType = "area";
        preset.deidentificationLevel = 0;
        set(session, "virtual", true);
        set(session, "virtualPreset", preset);
        HologramCapture capture = new HologramCapture(null, actor.getHeldItem());
        set(session, "capture", capture);
        ItemStack shell = new ItemStack(jar);
        HologramCapture.Cell target = new HologramCapture.Cell(null, x, Y, Z);
        target.block = jar;
        target.meta = 0;
        target.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        target.itemNbt = "";
        target.blueprintBlock = Block.blockRegistry.getNameForObject(jar);
        target.blueprintItem = Item.itemRegistry.getNameForObject(shell.getItem());
        target.blueprintDamage = 0;
        target.role = "prefab";
        target.userPosition = true;
        capture.cells.add(target);
        String position = x + "," + Y + "," + Z;
        capture.positions.put(position, target);
        HologramPresets.Cell saved = HologramPresets.cell(shell, 0, 0, 0, -1, false);
        saved.block = target.blueprintBlock;
        saved.metadata = 0;
        saved.tileNbt = target.tileNbt;
        saved.itemNbt = "";
        preset.cells.add(saved);
        for (String key : new String[] { "pins", "coordinatePins", "autoTargets", "frozenAutoTargets" })
            ((Map<Object, ItemStack>) field(session, key))
                .put(key.equals("pins") || key.equals("autoTargets") ? 0 : position, shell.copy());
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = true;
        settings.containers = false;
        set(session, "materialSettings", settings);
        return session;
    }

    private static boolean operate(Object session, HologramCapture.Cell target) throws Exception {
        invoke("refreshPlan", session, new Class<?>[0]);
        return (Boolean) invoke(
            "operate",
            session,
            new Class<?>[] { HologramCapture.Cell.class, int.class },
            target,
            0);
    }

    private static Object invoke(String name, Object session, Class<?>[] argumentTypes, Object... arguments)
        throws Exception {
        Class<?>[] signature = new Class<?>[argumentTypes.length + 1];
        signature[0] = session.getClass();
        System.arraycopy(argumentTypes, 0, signature, 1, argumentTypes.length);
        Object[] values = new Object[arguments.length + 1];
        values[0] = session;
        System.arraycopy(arguments, 0, values, 1, arguments.length);
        Method method = HologramEngineInspection.declaredMethod(name, signature);
        method.setAccessible(true);
        try {
            return method.invoke(null, values);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static NBTTagList inventory(EntityPlayerMP actor) {
        NBTTagList result = new NBTTagList();
        for (int i = 0; i < actor.inventory.mainInventory.length; i++) {
            if (i == actor.inventory.currentItem || actor.inventory.mainInventory[i] == null) continue;
            NBTTagCompound stack = actor.inventory.mainInventory[i].writeToNBT(new NBTTagCompound());
            stack.setInteger("slot", i);
            result.appendTag(stack);
        }
        return result;
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound result = new NBTTagCompound();
        tile.writeToNBT(result);
        return result;
    }

    private static Block block(String className) {
        for (Object registered : Block.blockRegistry) if (registered instanceof Block && registered.getClass()
            .getName()
            .equals(className)) return (Block) registered;
        throw new IllegalStateException("Registered native block required: " + className);
    }

    private static Item item(String className) {
        for (Object registered : Item.itemRegistry) if (registered instanceof Item && registered.getClass()
            .getName()
            .equals(className)) return (Item) registered;
        throw new IllegalStateException("Registered native item required: " + className);
    }
}
