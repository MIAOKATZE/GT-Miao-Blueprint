package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;

import appeng.api.AEApi;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;
import appeng.tile.networking.TileDenseEnergyCell;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in native UI fixture. Observes real sessions; never creates or edits a Service Session. */
public final class HologramGuidanceNativeServerProbe {

    private EntityPlayerMP actor;
    private WorldServer world;
    private TileController controller;
    private Item terminalItem;
    private int ticks, warmup;
    private boolean ready, bound, done;
    private final NBTTagList observations = new NBTTagList();
    private String lastObservation = "";

    private HologramGuidanceNativeServerProbe() {}

    public static void install() {
        if (Boolean.getBoolean("gtmb.hologram.guidanceNativeServer")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramGuidanceNativeServerProbe());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = MinecraftServer.getServer();
        try {
            require(!Boolean.getBoolean("gtmb.hologram.sulfurLineServer"), "exclusive guidance fixture");
            require(++ticks < 12000, "guidance server exceeded ten minutes");
            world = server.worldServerForDimension(0);
            require(
                world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-guidance-v013-"),
                "fresh isolated guidance save");
            if (actor == null) {
                if (server.getConfigurationManager().playerEntityList.isEmpty()) return;
                require(server.getConfigurationManager().playerEntityList.size() == 1, "one actual client");
                actor = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
                require(!(actor instanceof net.minecraftforge.common.util.FakePlayer), "real Netty player");
                actor.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
                actor.capabilities.allowFlying = actor.capabilities.isFlying = true;
                actor.sendPlayerAbilities();
                for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++)
                    actor.inventory.mainInventory[slot] = null;
                actor.inventory.mainInventory[0] = new ItemStack(
                    (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
                for (Object item : Item.itemRegistry) if (item instanceof Item && item.getClass()
                    .getName()
                    .equals("com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal")) terminalItem = (Item) item;
                require(terminalItem != null, "reviewed native GTSWN terminal registered");
                actor.inventory.mainInventory[1] = new ItemStack(terminalItem);
                actor.inventory.currentItem = 0;
                Block controllerBlock = AEApi.instance()
                    .definitions()
                    .blocks()
                    .controller()
                    .maybeBlock()
                    .get();
                Block cellBlock = AEApi.instance()
                    .definitions()
                    .blocks()
                    .energyCellDense()
                    .maybeBlock()
                    .get();
                world.getChunkFromBlockCoords(48, 72);
                require(
                    world.isAirBlock(48, 80, 72) && world.setBlock(48, 80, 72, controllerBlock, 0, 3),
                    "fresh real controller");
                controller = (TileController) world.getTileEntity(48, 80, 72);
                ((IGridProxyable) controller).getProxy()
                    .setOwner(actor);
                require(world.setBlock(47, 80, 72, cellBlock, 0, 3), "finite native AE cell");
                TileDenseEnergyCell cell = (TileDenseEnergyCell) world.getTileEntity(47, 80, 72);
                ((IGridProxyable) cell).getProxy()
                    .setOwner(actor);
                require(!cell.isInfinite() && cell.getAEMaxPower() >= 100000, "finite native cell capacity");
                NBTTagCompound energy = new NBTTagCompound();
                energy.setDouble("internalCurrentPower", 100000);
                cell.readFromNBT_TileEnergyCell(energy);
                cell.markDirty();
                require(world.setBlock(64, 79, 64, Blocks.stone, 0, 3), "physical placement sight target");
                actor.playerNetServerHandler.setPlayerLocation(48.5, 80, 75.5, 180, 0);
                actor.inventoryContainer.detectAndSendChanges();
                return;
            }
            if (!ready) {
                if (++warmup < 40 || !((IGridProxyable) controller).getProxy()
                    .isReady()) return;
                NBTTagCompound proof = base();
                proof.setTag("nativeController", tile(controller));
                proof.setTag("nativePowerCell", tile(world.getTileEntity(47, 80, 72)));
                proof.setInteger("finiteInitialAE", 100000);
                proof.setIntArray("controller", new int[] { 48, 80, 72 });
                proof.setIntArray("placementOrigin", new int[] { 64, 80, 64 });
                proof.setTag("inventory", inventory());
                HologramV011Evidence.write("guidance-server-ready.nbt", proof);
                ready = true;
            }
            if (!bound) {
                ItemStack terminal = actor.inventory.mainInventory[1];
                if (terminal == null || !terminal.hasTagCompound()
                    || !terminal.getTagCompound()
                        .getBoolean("QT_Bound"))
                    return;
                NBTTagCompound tag = terminal.getTagCompound();
                require(
                    tag.getInteger("QT_AnchorDim") == 0 && tag.getInteger("QT_AnchorX") == 48
                        && tag.getInteger("QT_AnchorY") == 80
                        && tag.getInteger("QT_AnchorZ") == 72,
                    "native controller click bound actual terminal");
                actor.inventory.mainInventory[35] = terminal.copy();
                actor.inventoryContainer.detectAndSendChanges();
                actor.playerNetServerHandler.setPlayerLocation(64.5, 80, 68.5, 180, 0);
                NBTTagCompound proof = base();
                proof.setTag("nativeTerminal", terminal.writeToNBT(new NBTTagCompound()));
                proof.setTag("nativeController", tile(controller));
                proof.setTag("inventory", inventory());
                proof.setString("bindingMethod", "actual client C08 native terminal use on active controller");
                HologramV011Evidence.write("guidance-terminal-bound.nbt", proof);
                bound = true;
            }
            observeSession();
            if (HologramV011Evidence.read("guidance-private-no-terminal-request.nbt") != null
                && HologramV011Evidence.read("guidance-private-no-terminal-ack.nbt") == null) {
                boolean absent = true;
                for (ItemStack stack : actor.inventory.mainInventory)
                    absent &= stack == null || stack.getItem() != terminalItem;
                if (absent) {
                    NBTTagCompound proof = base();
                    proof.setBoolean("noQuantumTerminal", true);
                    proof.setTag("inventory", inventory());
                    HologramV011Evidence.write("guidance-private-no-terminal-ack.nbt", proof);
                }
            }
            require(
                !Files.isRegularFile(
                    HologramV011Evidence.directory()
                        .resolve("guidance-client-fail.txt")),
                "paired actual client has no failure");
            NBTTagCompound client = HologramV011Evidence.read("guidance-client-final.nbt");
            if (client == null) return;
            require(
                client.getBoolean("passed") && client.getBoolean("actualNettyClient"),
                "actual native UI client completed");
            require(observations.tagCount() >= 4, "independent live Service observations exist");
            NBTTagCompound result = base();
            result.setTag("actualSessionObservations", observations);
            result.setTag("actualClient", client.copy());
            result.setTag("nativeController", tile(controller));
            result.setBoolean("productionOrMaterialsRetested", false);
            HologramV011Evidence.write("guidance-server-final.nbt", result);
            HologramV011Evidence
                .marker("guidance-server-pass.txt", "PASS native guidance UI; actual sessions and inventory observed");
            done = true;
            server.initiateShutdown();
        } catch (Throwable failure) {
            failure.printStackTrace();
            try {
                HologramV011Evidence.marker("guidance-server-fail.txt", "FAIL " + failure);
            } catch (Exception evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            done = true;
            server.initiateShutdown();
        }
    }

    private void observeSession() throws Exception {
        Field sessions = HologramService.class.getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        Object live = ((Map<?, ?>) sessions.get(null)).get(actor.getUniqueID());
        if (live == null) return;
        HologramPresets.Preset preset = (HologramPresets.Preset) field(live, "virtualPreset");
        HologramNetworkBindings.State bindings = (HologramNetworkBindings.State) field(live, "networkBindings");
        NBTTagCompound snapshot = HologramNetworkBindings.snapshot(preset, actor, bindings);
        String signature = field(live, "id") + ":"
            + field(live, "virtualPlacement")
            + ":"
            + field(live, "virtualLocked")
            + ":"
            + snapshot
            + ":"
            + (actor.inventory.mainInventory[35] == null)
            + ":"
            + (actor.inventory.mainInventory[1] == null);
        if (signature.equals(lastObservation)) return;
        lastObservation = signature;
        NBTTagCompound proof = base();
        proof.setString("session", (String) field(live, "id"));
        proof.setBoolean("virtualPlacement", (Boolean) field(live, "virtualPlacement"));
        proof.setBoolean("virtualLocked", (Boolean) field(live, "virtualLocked"));
        proof.setString("status", (String) field(live, "status"));
        proof.setTag("networkBindings", snapshot);
        proof.setTag("inventory", inventory());
        observations.appendTag(proof);
        HologramV011Evidence.write("guidance-server-observations.nbt", proofWithObservations());
    }

    private NBTTagCompound proofWithObservations() {
        NBTTagCompound proof = base();
        proof.setTag("observations", observations.copy());
        return proof;
    }

    private NBTTagCompound base() {
        NBTTagCompound proof = new NBTTagCompound();
        proof.setBoolean("passed", true);
        proof.setBoolean("actualServerTickEnd", true);
        proof.setString("releaseVersion", "0.1.3");
        proof.setString(
            "actor",
            actor.getUniqueID()
                .toString());
        proof.setString(
            "world",
            world.getWorldInfo()
                .getWorldName());
        proof.setLong("worldTick", world.getTotalWorldTime());
        return proof;
    }

    private NBTTagList inventory() {
        NBTTagList rows = new NBTTagList();
        for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++)
            if (actor.inventory.mainInventory[slot] != null) {
                NBTTagCompound row = actor.inventory.mainInventory[slot].writeToNBT(new NBTTagCompound());
                row.setInteger("slot", slot);
                rows.appendTag(row);
            }
        return rows;
    }

    private static NBTTagCompound tile(TileEntity tile) {
        NBTTagCompound proof = new NBTTagCompound();
        tile.writeToNBT(proof);
        return proof;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError("native guidance: " + message);
    }
}
