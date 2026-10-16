package com.miaokatze.gtmb.testlab;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.mojang.authlib.GameProfile;

/** A survival FakePlayer, not an online human. Its tagged effects have no global item/event hooks. */
final class TestLabActor {

    final FakePlayer player;
    final UUID id;
    private final String owner, run;
    private final WorldServer world;
    private final TestLabConfig config;
    private int supplies;
    private final ItemStack tool;

    TestLabActor(WorldServer world, TestLabOwnership ownership, TestLabConfig config, String run) {
        this.world = world;
        this.config = config;
        this.owner = ownership.owner.toString();
        this.run = run;
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
        clear();
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
        player.setPosition(zone.x + 8.5, zone.y + 1, zone.z + 8.5);
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
        for (int i = 0; i < player.inventory.getSizeInventory(); i++)
            player.inventory.setInventorySlotContents(i, null);
        player.inventory.markDirty();
    }

    NBTTagCompound tag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id.toString());
        tag.setString("name", "GTMBLabAgent");
        tag.setString("kind", "Forge FakePlayer");
        tag.setBoolean("onlineHuman", false);
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
