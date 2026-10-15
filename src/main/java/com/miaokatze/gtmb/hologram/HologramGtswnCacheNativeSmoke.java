package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.util.GTUtility;
import gregtech.common.misc.WirelessNetworkManager;

/** Opt-in native Service/cache evidence using an isolated actor, bank and temporary GT hosts; no GUI claim. */
public final class HologramGtswnCacheNativeSmoke {

    private static final UUID ACTOR = UUID.fromString("62cd3656-6b44-4132-ab85-d86f102d6c3e");
    private static final UUID AUTHOR = UUID.fromString("8677ccdc-c54a-4606-ade7-a5b3559f5b5b");
    private static final int X = 192, Y = 85, Z = 48;

    private HologramGtswnCacheNativeSmoke() {}

    public static NBTTagCompound run(WorldServer world) throws Exception {
        require(
            world != null && !world.isRemote
                && world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-sulfur-"),
            "isolated sulfur source world required");
        int playersBefore = world.playerEntities.size();
        HologramEnergyNetworkSmoke.ensureFixtureChunkCallback();
        ForgeChunkManager.Ticket ticket = ForgeChunkManager
            .requestTicket(GTMiaoBlueprint.instance, world, ForgeChunkManager.Type.NORMAL);
        require(ticket != null, "bounded native cache fixture ticket");
        WirelessNetworkManager.strongCheckOrAddUser(ACTOR);
        WirelessNetworkManager.strongCheckOrAddUser(AUTHOR);
        BigInteger actorBefore = WirelessNetworkManager.getUserEU(ACTOR);
        BigInteger authorBefore = WirelessNetworkManager.getUserEU(AUTHOR);
        Set<Integer> cleanup = new LinkedHashSet<>();
        NBTTagCompound receipt = new NBTTagCompound();
        Throwable primaryFailure = null;
        try {
            ChunkCoordIntPair coordinate = new ChunkCoordIntPair(X >> 4, Z >> 4);
            ForgeChunkManager.forceChunk(ticket, coordinate);
            Chunk loaded = world.theChunkProviderServer.loadChunk(coordinate.chunkXPos, coordinate.chunkZPos);
            require(
                loaded != null && !(loaded instanceof EmptyChunk) && loaded.isChunkLoaded,
                "actual bounded fixture chunk loaded");
            ChatSinkActor actor = new ChatSinkActor(world, new GameProfile(ACTOR, "GTMB-EUCache"));
            actor.inventory.mainInventory = new ItemStack[36];
            actor.inventory.currentItem = 0;
            actor.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
            actor.capabilities.isCreativeMode = false;
            actor.capabilities.allowEdit = true;
            Item tool = (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector");
            require(tool != null, "actual registered projector");
            actor.inventory.mainInventory[0] = new ItemStack(tool);
            WirelessNetworkManager.setUserEU(AUTHOR, BigInteger.valueOf(777));
            int machineId = energyHatchId();
            NBTTagList cases = new NBTTagList();
            for (int kind = 0; kind < 2; kind++) {
                BigInteger authorBalanceBefore = WirelessNetworkManager.getUserEU(AUTHOR);
                int sourceX = X + kind * 8;
                for (int position : new int[] { sourceX, sourceX + 2, sourceX + 4 }) {
                    require(
                        world.blockExists(position, Y, Z) && world.isAirBlock(position, Y, Z),
                        "fresh auxiliary native cache coordinate");
                    cleanup.add(position);
                }
                ItemStack cover = coverItem(kind);
                require(
                    HologramGtswnCacheAdapter.kindForCover(cover) == kind,
                    "registered native cover class and item identity");
                BaseMetaTileEntity source = source(world, sourceX, machineId, cover);
                NBTTagCompound captured = HologramNativeCapture.capture(source);
                require(
                    stored(captured) == 100 && coverData(captured).hasKey("storedEU", 4),
                    "actual native cover writer preserves a typed finite storedEU buffer");
                HologramEnergyPayload.Prepared prepared = HologramEnergyPayload.prepare(captured, null);
                double loss = Class.forName("com.miaokatze.gtswn.config.Config")
                    .getField("downlinkLossEU")
                    .getDouble(null);
                BigInteger bill = (BigInteger) Class.forName("com.miaokatze.gtswn.common.util.CoverMaths")
                    .getMethod("downlinkDeduction", long.class, double.class)
                    .invoke(null, 100L, loss);
                require(
                    prepared.demand.eu.equals(bill) && prepared.demand.ae.signum() == 0
                        && prepared.demand.rf.signum() == 0
                        && stored(prepared.tilePaid) == 100
                        && stored(prepared.tileEmpty) == 0,
                    "production adapter uses only the exact native EU bill");
                ItemStack shell = new ItemStack(GregTechAPI.sBlockMachines, 1, machineId);
                HologramPresets.Preset preset = preset(shell, captured);
                Object session = session(actor, preset, shell, sourceX + 2);
                HologramCapture.Cell cell = cell(session);
                actor.inventory.mainInventory[1] = shell.copy();
                BigInteger initial = bill.add(BigInteger.valueOf(500));
                WirelessNetworkManager.setUserEU(ACTOR, initial);
                invoke("refreshPlan", session);
                require(
                    cell.resourceError.isEmpty() && "pending".equals(cell.status)
                        && WirelessNetworkManager.getUserEU(ACTOR)
                            .equals(initial),
                    "native preview is payable without debiting current actor EU");
                BigInteger actorBalanceBefore = WirelessNetworkManager.getUserEU(ACTOR);
                Veto veto = new Veto(sourceX + 2, actor, bill, initial);
                NBTTagList refundBalances = new NBTTagList();
                MinecraftForge.EVENT_BUS.register(veto);
                try {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        invoke("refreshPlan", session);
                        invoke("operate", session, cell, 0);
                        require(
                            world.isAirBlock(sourceX + 2, Y, Z) && WirelessNetworkManager.getUserEU(ACTOR)
                                .equals(initial)
                                && ItemStack.areItemStacksEqual(actor.inventory.mainInventory[1], shell),
                            "each real place veto refunds actor EU and exact machine material once");
                        NBTTagCompound refund = new NBTTagCompound();
                        refund.setInteger("attempt", attempt);
                        refund.setString(
                            "actorEuAfterRefund",
                            WirelessNetworkManager.getUserEU(ACTOR)
                                .toString());
                        refundBalances.appendTag(refund);
                    }
                } finally {
                    MinecraftForge.EVENT_BUS.unregister(veto);
                }
                require(
                    veto.shellEvents == 2 && veto.events == 2 && veto.fundedEvents == 2,
                    "both native shell events precede cancelled events with complete paid covers");
                invoke("refreshPlan", session);
                invoke("operate", session, cell, 0);
                NBTTagCompound paid = HologramNativeCapture.capture(world.getTileEntity(sourceX + 2, Y, Z));
                require(
                    stored(paid) == 100 && WirelessNetworkManager.getUserEU(ACTOR)
                        .equals(initial.subtract(bill))
                        && actor.inventory.mainInventory[1] == null
                        && ACTOR.equals(((BaseMetaTileEntity) world.getTileEntity(sourceX + 2, Y, Z)).getOwnerUuid()),
                    "successful Service placement restores paid native cache owned by current actor");
                BigInteger afterPaid = WirelessNetworkManager.getUserEU(ACTOR);
                invoke("refreshPlan", session);
                invoke("operate", session, cell, 0);
                require(
                    WirelessNetworkManager.getUserEU(ACTOR)
                        .equals(initial.subtract(bill)),
                    "matching placed target cannot debit the same cache again");
                BigInteger afterRepeat = WirelessNetworkManager.getUserEU(ACTOR);
                Object poorSession = session(actor, preset, shell, sourceX + 4);
                actor.inventory.mainInventory[1] = shell.copy();
                BigInteger insufficient = bill.subtract(BigInteger.ONE);
                WirelessNetworkManager.setUserEU(ACTOR, insufficient);
                BigInteger beforeInsufficient = WirelessNetworkManager.getUserEU(ACTOR);
                invoke("refreshPlan", poorSession);
                NBTTagList resourceRows = (NBTTagList) invoke("blueprintResourceRows", poorSession);
                boolean fallback = false;
                for (int i = 0; i < resourceRows.tagCount(); i++) {
                    NBTTagCompound row = resourceRows.getCompoundTagAt(i);
                    fallback |= row.getBoolean("optionalCache") && row.getBoolean("emptyFallback")
                        && row.getString("required")
                            .equals(bill.toString())
                        && row.getString("available")
                            .equals(insufficient.toString());
                }
                require(fallback, "native shortage reports the actual required/available EU before construction");
                invoke("operate", poorSession, cell(poorSession), 0);
                NBTTagCompound empty = HologramNativeCapture.capture(world.getTileEntity(sourceX + 4, Y, Z));
                require(
                    stored(empty) == 0 && WirelessNetworkManager.getUserEU(ACTOR)
                        .equals(insufficient) && !((String) field(poorSession, "blueprintEnergyNotice")).isEmpty(),
                    "insufficient actor EU uses the visible empty-cache fallback without debit");
                BigInteger afterInsufficient = WirelessNetworkManager.getUserEU(ACTOR);
                require(
                    HologramNativeCapture.capture(source)
                        .equals(captured)
                        && WirelessNetworkManager.getUserEU(AUTHOR)
                            .equals(BigInteger.valueOf(777)),
                    "capture and target payment leave source cover and author EU bank unchanged");
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger("kind", kind);
                row.setIntArray("sourcePosition", new int[] { sourceX, Y, Z });
                row.setIntArray("paidPosition", new int[] { sourceX + 2, Y, Z });
                row.setIntArray("emptyPosition", new int[] { sourceX + 4, Y, Z });
                row.setString("nativeBill", bill.toString());
                row.setDouble("nativeLoss", loss);
                row.setLong(
                    "nativeCapacity",
                    kind == 0 ? coverData(prepared.tilePaid).getLong("capacity") : Long.MAX_VALUE);
                row.setString("actorEuBefore", actorBalanceBefore.toString());
                row.setString("actorEuAfterPaid", afterPaid.toString());
                row.setString("actorEuAfterRepeat", afterRepeat.toString());
                row.setString("actorEuInsufficientBefore", beforeInsufficient.toString());
                row.setString("actorEuInsufficientAfter", afterInsufficient.toString());
                row.setString("authorEuBefore", authorBalanceBefore.toString());
                row.setString(
                    "authorEuAfter",
                    WirelessNetworkManager.getUserEU(AUTHOR)
                        .toString());
                row.setTag("refundBalances", refundBalances);
                row.setTag("actorBalancesDuringVeto", veto.balances);
                row.setTag("sourceNative", captured);
                row.setTag("paidNative", paid);
                row.setTag("emptyNative", empty);
                row.setTag("insufficientResources", resourceRows);
                row.setInteger("cancelledPaidNativeEvents", veto.fundedEvents);
                row.setInteger("nativeShellEvents", veto.shellEvents);
                row.setBoolean("refundOncePerCancelledAttempt", true);
                row.setBoolean("currentActorDebited", true);
                row.setBoolean("sourceAndAuthorUnchanged", true);
                row.setBoolean("repeatDoesNotDebit", true);
                row.setBoolean("insufficientQuoteVisible", fallback);
                row.setString("emptyNotice", (String) field(poorSession, "blueprintEnergyNotice"));
                cases.appendTag(row);
            }
            require(world.playerEntities.size() == playersBefore, "auxiliary fake actor never joins the player list");
            receipt.setTag("cases", cases);
            receipt.setString("fixtureActor", ACTOR.toString());
            receipt.setString("sourceAuthor", AUTHOR.toString());
            receipt.setBoolean("actualNativeWorld", true);
            receipt.setBoolean("actualService", true);
            receipt.setBoolean("actualGui", false);
            receipt.setBoolean("fakeFixtureActor", true);
            receipt.setInteger("nativeChatMessages", actor.messages);
            receipt.setBoolean("passed", true);
            return receipt;
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            for (int position : cleanup) {
                try {
                    if (world.getTileEntity(position, Y, Z) instanceof BaseMetaTileEntity)
                        require(HologramRecovery.removeWithoutDrops(world, position, Y, Z), "temporary host cleanup");
                } catch (Throwable failure) {
                    cleanupFailure = collect(cleanupFailure, failure);
                }
            }
            for (UUID owner : new UUID[] { ACTOR, AUTHOR }) {
                try {
                    BigInteger previous = owner.equals(ACTOR) ? actorBefore : authorBefore;
                    WirelessNetworkManager.setUserEU(owner, previous);
                    require(
                        WirelessNetworkManager.getUserEU(owner)
                            .equals(previous),
                        "fixture bank restored");
                } catch (Throwable failure) {
                    cleanupFailure = collect(cleanupFailure, failure);
                }
            }
            try {
                ForgeChunkManager.releaseTicket(ticket);
            } catch (Throwable failure) {
                cleanupFailure = collect(cleanupFailure, failure);
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof Error) throw (Error) cleanupFailure;
                else if (cleanupFailure instanceof Exception) throw (Exception) cleanupFailure;
                else throw new IllegalStateException("Native cache fixture cleanup failed", cleanupFailure);
            } else if (primaryFailure == null) {
                receipt.setBoolean("cleanupComplete", true);
                System.out
                    .println("[GTMB-GTSWN-CACHE-NATIVE] FINAL PASS native=energy+dynamo paid+insufficient+refund-once");
            }
        }
    }

    private static Throwable collect(Throwable existing, Throwable failure) {
        if (existing == null) return failure;
        existing.addSuppressed(failure);
        return existing;
    }

    private static final class ChatSinkActor extends FakePlayer {

        int messages;

        ChatSinkActor(WorldServer world, GameProfile profile) {
            super(world, profile);
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            if (message != null) messages++;
        }

        @Override
        public void addChatComponentMessage(IChatComponent message) {
            if (message != null) messages++;
        }
    }

    private static int energyHatchId() {
        for (int id = 1; id < GregTechAPI.METATILEENTITIES.length; id++)
            if (GregTechAPI.METATILEENTITIES[id] instanceof MTEHatchEnergy) return id;
        throw new AssertionError("Native GTSWN cache fixture requires a registered GT energy hatch");
    }

    private static ItemStack coverItem(int kind) {
        String expected = "com.miaokatze.gtswn.common.items.GTSwnCover" + (kind == 0 ? "Energy" : "Dynamo")
            + "Wireless";
        for (Object registered : Item.itemRegistry) if (registered instanceof Item && registered.getClass()
            .getName()
            .equals(expected)) return new ItemStack((Item) registered);
        throw new AssertionError("Native GTSWN cache fixture requires " + expected);
    }

    private static BaseMetaTileEntity source(WorldServer world, int x, int machineId, ItemStack cover)
        throws Exception {
        require(world.setBlock(x, Y, Z, GregTechAPI.sBlockMachines, 0, 3), "actual native GT source host installed");
        BaseMetaTileEntity source = (BaseMetaTileEntity) world.getTileEntity(x, Y, Z);
        source.setInitialValuesAsNBT(null, (short) machineId);
        source.setOwnerUuid(AUTHOR);
        source.setOwnerName("GTMB-EUAuthor");
        source.setFrontFacing(ForgeDirection.NORTH);
        source.setStoredEU(0);
        NBTTagCompound payload = HologramNativeCapture.capture(source);
        NBTTagCompound row = new NBTTagCompound();
        row.setByte("s", (byte) ForgeDirection.EAST.ordinal());
        row.setInteger("id", GTUtility.stackToInt(cover));
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("voltage", 32);
        data.setDouble("amperageDecimal", 0.1);
        data.setBoolean("configured", true);
        data.setLong("storedEU", 100);
        data.setDouble("fractionalEU", 0.25);
        row.setTag("d", data);
        NBTTagList covers = new NBTTagList();
        covers.appendTag(row);
        payload.setTag("gt.covers", covers);
        source.readFromNBT(payload);
        return source;
    }

    private static HologramPresets.Preset preset(ItemStack shell, NBTTagCompound captured) throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.id = "native_eu_cache";
        preset.controllerType = "area";
        HologramPresets.Cell cell = HologramPresets.cell(shell, 0, 0, 0, -1, false);
        cell.block = Block.blockRegistry.getNameForObject(GregTechAPI.sBlockMachines);
        cell.metadata = 0;
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(captured);
        preset.cells.add(cell);
        preset.palette.add(HologramPresets.cell(shell, 0, 0, 0, -1, false));
        return preset;
    }

    private static Object session(EntityPlayerMP actor, HologramPresets.Preset preset, ItemStack shell, int x)
        throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramService$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(actor, x, Y, Z);
        set(session, "virtual", true);
        set(session, "virtualPreset", HologramPresets.copy(preset));
        HologramCapture capture = new HologramCapture(null, actor.getHeldItem());
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, Y, Z);
        cell.block = GregTechAPI.sBlockMachines;
        cell.meta = 0;
        cell.tileNbt = preset.cells.get(0).tileNbt;
        cell.blueprintBlock = Block.blockRegistry.getNameForObject(cell.block);
        cell.blueprintItem = Item.itemRegistry.getNameForObject(shell.getItem());
        cell.blueprintDamage = shell.getItemDamage();
        cell.role = "prefab";
        cell.userPosition = true;
        capture.cells.add(cell);
        String position = x + "," + Y + "," + Z;
        capture.positions.put(position, cell);
        set(session, "capture", capture);
        for (String name : new String[] { "pins", "autoTargets" }) map(session, name).put(0, shell.copy());
        for (String name : new String[] { "coordinatePins", "frozenAutoTargets" })
            map(session, name).put(position, shell.copy());
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = false;
        settings.containers = false;
        set(session, "materialSettings", settings);
        actor.setPosition(x + .5, Y, Z + .5);
        return session;
    }

    private static HologramCapture.Cell cell(Object session) throws Exception {
        return ((HologramCapture) field(session, "capture")).cells.get(0);
    }

    private static NBTTagCompound coverData(NBTTagCompound payload) {
        NBTTagList covers = payload.getTagList("gt.covers", 10);
        require(covers.tagCount() == 1, "exactly one native cover in auxiliary host");
        return covers.getCompoundTagAt(0)
            .getCompoundTag("d");
    }

    private static long stored(NBTTagCompound payload) {
        return coverData(payload).getLong("storedEU");
    }

    public static final class Veto {

        private final int x;
        private final EntityPlayerMP actor;
        private final BigInteger bill, initial;
        int shellEvents, events, fundedEvents;
        final NBTTagList balances = new NBTTagList();

        Veto(int x, EntityPlayerMP actor, BigInteger bill, BigInteger initial) {
            this.x = x;
            this.actor = actor;
            this.bill = bill;
            this.initial = initial;
        }

        @SubscribeEvent
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.x != x || event.y != Y || event.z != Z || event.world != actor.worldObj || event.player != actor)
                return;
            NBTTagCompound nativeTile;
            try {
                nativeTile = HologramNativeCapture.capture(event.world.getTileEntity(x, Y, Z));
            } catch (Exception failure) {
                throw new IllegalStateException("native cache place capture failed", failure);
            }
            // ForgeHooks posts the item placement event before Service applies the paid cover NBT.
            // Observe that real shell without cancelling it; veto the later complete Service event below.
            if (!nativeTile.hasKey("gt.covers")
                || nativeTile.hasKey("gt.covers", 9) && nativeTile.getTagList("gt.covers", 10)
                    .tagCount() == 0) {
                require(
                    event.world.getTileEntity(x, Y, Z) instanceof BaseMetaTileEntity
                        && WirelessNetworkManager.getUserEU(ACTOR)
                            .equals(initial.subtract(bill)),
                    "native shell event follows actual actor EU reservation before cover restoration");
                shellEvents++;
                return;
            }
            events++;
            try {
                require(
                    stored(nativeTile) == 100 && WirelessNetworkManager.getUserEU(ACTOR)
                        .equals(initial.subtract(bill)),
                    "place event sees the paid native cover after the actual actor debit");
                NBTTagCompound balance = new NBTTagCompound();
                balance.setString(
                    "actorEuDuringVeto",
                    WirelessNetworkManager.getUserEU(ACTOR)
                        .toString());
                balances.appendTag(balance);
                fundedEvents++;
            } catch (Exception failure) {
                throw new IllegalStateException("native cache place witness failed", failure);
            } finally {
                event.setCanceled(true);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, ItemStack> map(Object owner, String name) throws Exception {
        return (Map<Object, ItemStack>) field(owner, name);
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

    private static Object invoke(String name, Object session, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length + 1];
        Object[] values = new Object[args.length + 1];
        types[0] = session.getClass();
        values[0] = session;
        for (int i = 0; i < args.length; i++) {
            types[i + 1] = args[i] instanceof Integer ? int.class : args[i].getClass();
            values[i + 1] = args[i];
        }
        Method method = HologramService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, values);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("Native GTSWN cache fixture: " + message);
    }
}
