package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.mojang.authlib.GameProfile;

/** Survival FakePlayer for small suites, or one configured connected test client for native composite workflows. */
final class TestLabActor {

    final EntityPlayerMP player;
    private NBTTagList originalInventory;
    private int originalDimension, originalSlot;
    private double originalX, originalY, originalZ;
    private float originalYaw, originalPitch;
    private boolean returned;
    final UUID id;
    private final String owner, run;
    private final WorldServer world;
    private final TestLabConfig config;
    private int supplies;
    private final ItemStack tool;

    TestLabActor(WorldServer world, TestLabOwnership ownership, TestLabConfig config, String run) {
        this(world, ownership, config, run, false);
    }

    TestLabActor(WorldServer world, TestLabOwnership ownership, TestLabConfig config, String run, boolean connected) {
        this.world = world;
        this.config = config;
        this.owner = ownership.owner.toString();
        this.run = run;
        if (ProjectorRegistry.projector == null) throw new IllegalStateException("Projector registry is unavailable");
        if (connected) {
            if (config.compositeActorUuid.isEmpty())
                throw new IllegalStateException("Composite requires configured test client UUID");
            id = UUID.fromString(config.compositeActorUuid);
            EntityPlayerMP selected = null;
            for (Object online : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList) {
                EntityPlayerMP candidate = (EntityPlayerMP) online;
                if (id.equals(candidate.getUniqueID())) selected = candidate;
            }
            if (selected == null || selected instanceof FakePlayer
                || selected.playerNetServerHandler == null
                || selected.capabilities.isCreativeMode
                || selected.theItemInWorldManager.getGameType() != net.minecraft.world.WorldSettings.GameType.SURVIVAL
                || selected.isDead)
                throw new IllegalStateException(
                    "Configured composite test client must be connected, alive and survival");
            player = selected;
            originalInventory = player.inventory.writeToNBT(new NBTTagList());
            originalDimension = player.dimension;
            originalSlot = player.inventory.currentItem;
            originalX = player.posX;
            originalY = player.posY;
            originalZ = player.posZ;
            originalYaw = player.rotationYaw;
            originalPitch = player.rotationPitch;
            TestLabZone bank = config.zone("composite-bank");
            // Actor admission precedes the facility lease; ordinary provideChunk may return EmptyChunk here.
            for (int cx = (bank.x + 1) >> 4; cx <= (bank.x + 3) >> 4; cx++)
                for (int cz = (bank.z + 19) >> 4; cz <= (bank.z + 21) >> 4; cz++) {
                    Chunk chunk = world.theChunkProviderServer.loadChunk(cx, cz);
                    if (chunk == null || chunk instanceof EmptyChunk
                        || !chunk.isChunkLoaded
                        || chunk.xPosition != cx
                        || chunk.zPosition != cz)
                        throw new IllegalStateException("Composite actor platform chunk did not load natively");
                }
            for (int dx = 1; dx <= 3; dx++)
                for (int dz = 19; dz <= 21; dz++) world.setBlock(bank.x + dx, bank.y, bank.z + dz, Blocks.glass, 0, 3);
            // EmptyChunk accepts writes without storing blocks, so check the actual world before transferring.
            for (int dx = 1; dx <= 3; dx++)
                for (int dz = 19; dz <= 21; dz++) if (world.getBlock(bank.x + dx, bank.y, bank.z + dz) != Blocks.glass)
                    throw new IllegalStateException("Composite actor platform glass did not persist");
            if (world
                .func_147461_a(
                    AxisAlignedBB.getBoundingBox(
                        bank.x + 2.2,
                        bank.y + 0.99,
                        bank.z + 20.2,
                        bank.x + 2.8,
                        bank.y + 1.01,
                        bank.z + 20.8))
                .isEmpty()) throw new IllegalStateException("Composite actor platform has no supporting collision");
            transfer(world, bank.x + 2.5, bank.y + 1, bank.z + 20.5);
        } else {
            id = UUID.nameUUIDFromBytes(("gtmb-testlab-agent:" + owner).getBytes(StandardCharsets.UTF_8));
            for (Object online : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList)
                if (id.equals(((EntityPlayerMP) online).getUniqueID()))
                    throw new IllegalStateException("Lab actor UUID is online; refusing impersonation");
            player = FakePlayerFactory.get(world, new GameProfile(id, "GTMBLabAgent"));
            if (player.worldObj != world) throw new IllegalStateException("FakePlayer belongs to another world");
            player.dimension = config.dimension;
            player.capabilities.isCreativeMode = false;
            player.capabilities.allowFlying = false;
        }
        for (int i = 0; i < player.inventory.getSizeInventory(); i++)
            player.inventory.setInventorySlotContents(i, null);
        if (ProjectorRegistry.projector == null) throw new IllegalStateException("Projector registry is unavailable");
        tool = new ItemStack(ProjectorRegistry.projector);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("testLabOwner", owner);
        tag.setString("testLabRun", run);
        tag.setInteger("testLabSupplyLimit", 1);
        tool.setTagCompound(tag);
        player.inventory.currentItem = 0;
        player.inventory.setInventorySlotContents(0, tool.copy());
    }

    void at(TestLabZone zone) {
        if (player instanceof FakePlayer) player.setPosition(zone.x + 8.5, zone.y + 1, zone.z + 8.5);
        else {
            TestLabZone bank = config.zone("composite-bank");
            player.playerNetServerHandler
                .setPlayerLocation(bank.x + 2.5, bank.y + 1, bank.z + 20.5, player.rotationYaw, player.rotationPitch);
        }
    }

    ItemStack tool() {
        return tool.copy();
    }

    boolean use(ItemStack item, EntityPlayerMP actor, World target, int x, int y, int z) {
        if (actor != player || target != world
            || actor.worldObj != world
            || actor.dimension != config.dimension
            || !config.contains(x, y, z, 1, 1, 1)
            || item == null
            || item.getItem() != tool.getItem()
            || !item.hasTagCompound()
            || supplies != 0) return false;
        NBTTagCompound tag = item.getTagCompound();
        if (!owner.equals(tag.getString("testLabOwner")) || !run.equals(tag.getString("testLabRun"))) return false;
        supplies++;
        player.inventory.setInventorySlotContents(1, new ItemStack(Items.diamond, 7));
        return true;
    }

    void clear() {
        if (originalInventory != null) {
            if (returned) return;
            player.inventory.readFromNBT((NBTTagList) originalInventory.copy());
            player.inventory.currentItem = originalSlot;
            player.inventory.markDirty();
            WorldServer target = MinecraftServer.getServer()
                .worldServerForDimension(originalDimension);
            if (target == null) throw new IllegalStateException("Original actor return world is unavailable");
            boolean online = MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.contains(player) && player.playerNetServerHandler != null
                && player.playerNetServerHandler.netManager.isChannelOpen();
            if (online) {
                transfer(target, originalX, originalY, originalZ);
                player.playerNetServerHandler
                    .setPlayerLocation(originalX, originalY, originalZ, originalYaw, originalPitch);
            } else restoreDisconnected(target);
            returned = true;
            return;
        }
        for (int i = 0; i < player.inventory.getSizeInventory(); i++)
            player.inventory.setInventorySlotContents(i, null);
        player.inventory.markDirty();
    }

    /** A logged-out test actor must not re-enter online dimension-change hooks or receive packets. */
    private void restoreDisconnected(WorldServer target) {
        World previous = player.worldObj;
        if (previous != null && previous.playerEntities.contains(player))
            previous.removePlayerEntityDangerously(player);
        player.worldObj = target;
        player.dimension = originalDimension;
        player.theItemInWorldManager.setWorld(target);
        player.setPositionAndRotation(originalX, originalY, originalZ, originalYaw, originalPitch);
        NBTTagCompound expected = new NBTTagCompound();
        player.writeToNBT(expected);
        target.getSaveHandler()
            .getSaveHandler()
            .writePlayerData(player);
        Path file = target.getSaveHandler()
            .getWorldDirectory()
            .toPath()
            .resolve("playerdata")
            .resolve(
                player.getUniqueID()
                    .toString() + ".dat");
        try (InputStream input = Files.newInputStream(file)) {
            NBTTagCompound saved = CompressedStreamTools.readCompressed(input);
            for (String key : new String[] { "Inventory", "SelectedItemSlot", "Dimension", "Pos", "Rotation" })
                if (expected.getTag(key) == null || !expected.getTag(key)
                    .equals(saved.getTag(key)))
                    throw new IOException("Restored disconnected native playerdata differs at " + key);
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Disconnected test actor restoration did not persist native playerdata",
                failure);
        }
    }

    private void transfer(final WorldServer target, final double x, final double y, final double z) {
        if (player.dimension != target.provider.dimensionId) MinecraftServer.getServer()
            .getConfigurationManager()
            .transferPlayerToDimension(player, target.provider.dimensionId, new Teleporter(target) {

                @Override
                public void placeInPortal(net.minecraft.entity.Entity entity, double ox, double oy, double oz,
                    float yaw) {
                    entity.setLocationAndAngles(x, y, z, entity.rotationYaw, entity.rotationPitch);
                }
            });
        player.playerNetServerHandler.setPlayerLocation(x, y, z, player.rotationYaw, player.rotationPitch);
    }

    NBTTagCompound tag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id.toString());
        tag.setString("name", player.getCommandSenderName());
        tag.setString("kind", player instanceof FakePlayer ? "Forge FakePlayer" : "configured connected test client");
        tag.setBoolean("onlineHuman", !(player instanceof FakePlayer));
        tag.setBoolean("creative", player.capabilities.isCreativeMode);
        tag.setInteger(
            "gameMode",
            player.theItemInWorldManager.getGameType()
                .getID());
        tag.setString(
            "gameModeName",
            player.theItemInWorldManager.getGameType()
                .getName());
        tag.setBoolean("alive", !player.isDead);
        tag.setInteger("actualDimension", player.dimension);
        tag.setDouble("actualX", player.posX);
        tag.setDouble("actualY", player.posY);
        tag.setDouble("actualZ", player.posZ);
        tag.setString(
            "worldProviderClass",
            player.worldObj.provider.getClass()
                .getName());
        return tag;
    }
}
