// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.register.ProjectorRegistry;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;

/** Explicit owned-server contract; observes the real World/IWorldAccess entry before any client volume setting. */
public final class HologramConstructionSoundProbe {

    private final WorldServer world;
    private final EntityPlayerMP actor;
    private final int x, y, z, toolSlot, supplySlot;
    private final ItemStack tool;
    private final NBTTagList inventoryBefore;
    private final double actorX, actorY, actorZ;
    private final float yaw, pitch;
    private final List<NBTTagCompound> sounds = new ArrayList<>();
    private final IWorldAccess listener;
    private int placeEvents;
    private boolean veto, registered;
    private Block ownedTarget = Blocks.air;

    /** Caller must run on the server thread and reserve two loaded, empty owned cells: (x,y,z) and (x+2,y,z). */
    public static NBTTagCompound run(WorldServer world, EntityPlayerMP actor, int x, int y, int z) throws Exception {
        HologramConstructionSoundProbe probe = new HologramConstructionSoundProbe(world, actor, x, y, z);
        NBTTagCompound result;
        try {
            result = probe.verify();
        } finally {
            probe.restore();
        }
        result.setBoolean("fixtureWorldAndActorRestored", true);
        result.setBoolean("listenerRemoved", true);
        return result;
    }

    private HologramConstructionSoundProbe(WorldServer world, EntityPlayerMP actor, int x, int y, int z)
        throws Exception {
        require(
            world != null && actor != null
                && actor.worldObj == world
                && !actor.capabilities.isCreativeMode
                && actor.theItemInWorldManager.getGameType() == GameType.SURVIVAL
                && !actor.canCommandSenderUseCommand(2, "gtmbtestlab"),
            "Real non-OP survival actor/world required");
        require(
            Boolean.TRUE.equals(
                Class.forName("com.miaokatze.miaokatzecore.testlab.LabWorldPolicy")
                    .getMethod("owns", World.class)
                    .invoke(null, world)),
            "Exact host-owned save required");
        this.world = world;
        this.actor = actor;
        this.x = x;
        this.y = y;
        this.z = z;
        toolSlot = actor.inventory.currentItem;
        tool = actor.getHeldItem();
        require(tool != null && tool.getItem() == ProjectorRegistry.projector, "Owned projector must remain held");
        require(!HologramConstructionJournal.pending(actor), "Pending construction must be settled first");
        for (int dx : new int[] { 0, 2 }) require(
            world.blockExists(x + dx, y, z) && world.isAirBlock(x + dx, y, z)
                && world.getTileEntity(x + dx, y, z) == null,
            "Sound fixture requires explicitly reserved loaded empty cells");
        int vacant = -1;
        for (int i = 0; i < actor.inventory.mainInventory.length; i++)
            if (i != toolSlot && actor.inventory.mainInventory[i] == null) {
                vacant = i;
                break;
            }
        require(vacant >= 0, "Sound fixture requires an empty temporary supply slot");
        supplySlot = vacant;
        inventoryBefore = actor.inventory.writeToNBT(new NBTTagList());
        actorX = actor.posX;
        actorY = actor.posY;
        actorZ = actor.posZ;
        yaw = actor.rotationYaw;
        pitch = actor.rotationPitch;
        listener = sounds(sounds);
    }

    private NBTTagCompound verify() throws Exception {
        world.addWorldAccess(listener);
        registered = true;
        MinecraftForge.EVENT_BUS.register(this);
        actor.setPositionAndUpdate(x - 3.5, y + 1, z - 3.5);
        NBTTagCompound result = new NBTTagCompound();
        result.setString(
            "source",
            "Actual World.playSoundEffect -> IWorldAccess.playSound, before client sound settings");
        result.setString(
            "scope",
            "Shared production placement kernel, native item callbacks, survival ledger and Forge veto; GUI/TCP not claimed");
        int before = sounds.size();
        world.playSoundEffect(x + .5, y + .5, z + .5, "dig.stone", 1, 1);
        require(sounds.size() == before + 1, "Unscoped real World sound positive control failed");
        before = sounds.size();
        try (var scope = HologramConstructionSounds.enter(world, x, y, z)) {
            world.playSoundEffect(x + .5, y + .5, z + .5, "dig.stone", 1, 1);
            require(
                sounds.size() == before,
                "Actual World sound Mixin was not applied or did not cancel the scoped target");
            world.playSoundEffect(x + 2.5, y + .5, z + .5, "dig.stone", 1, 1);
            require(sounds.size() == before + 1, "Different-coordinate sound was incorrectly muted");
            try (var nested = HologramConstructionSounds.enter(world, x + 2, y, z)) {
                world.playSoundEffect(x + 2.5, y + .5, z + .5, "dig.stone", 1, 1);
                require(sounds.size() == before + 1, "Nested native target sound was not suppressed");
            }
            world.playSoundEffect(x + .5, y + .5, z + .5, "dig.stone", 1, 1);
            require(sounds.size() == before + 1, "Nested close did not restore the original sound target");
            World other = HologramResourceWorld.create(Blocks.air, 0);
            List<NBTTagCompound> otherSounds = new ArrayList<>();
            IWorldAccess otherListener = sounds(otherSounds);
            other.addWorldAccess(otherListener);
            try {
                other.playSoundEffect(x + .5, y + .5, z + .5, "dig.stone", 1, 1);
                require(otherSounds.size() == 1, "Another World's actual IWorldAccess sound was incorrectly muted");
            } finally {
                other.removeWorldAccess(otherListener);
            }
        }
        NBTTagList rows = new NBTTagList();
        ItemStack[] samples = { new ItemStack(Blocks.stone), new ItemStack(GregTechAPI.sBlockCasings1, 1, 0),
            ItemList.Hatch_Maintenance.get(1), new ItemStack(Blocks.stone_slab, 1, 0) };
        String[] names = { "ordinary-block", "actual-ItemCasings", "actual-ItemMachines",
            "actual-ItemSlab-custom-use" };
        for (boolean saved : new boolean[] { false, true })
            for (int i = 0; i < samples.length; i++) rows.appendTag(place(names[i], samples[i], saved, false));
        rows.appendTag(place("Forge-veto-after-native-slab-use", samples[3], true, true));
        try (var scope = HologramConstructionSounds.enter(world, x, y, z)) {
            throw new ExpectedFailure();
        } catch (ExpectedFailure expected) {
            before = sounds.size();
            manualPositive();
            require(sounds.size() > before, "Manual placement lost native sound after exceptional scope exit");
        }
        result.setTag("placements", rows);
        result.setInteger("scopedPlacementSounds", 0);
        result.setInteger("observedPositiveSounds", sounds.size());
        NBTTagList soundRows = new NBTTagList();
        for (NBTTagCompound sound : sounds) soundRows.appendTag(sound.copy());
        result.setTag("realSoundEvents", soundRows);
        result.setBoolean("mixinAppliedByRealSoundWitness", true);
        result.setBoolean("passed", true);
        return result;
    }

    private NBTTagCompound place(String name, ItemStack sample, boolean saved, boolean rejected) throws Exception {
        require(
            sample != null && sample.getItem() instanceof ItemBlock,
            "Registered native block item required: " + name);
        require(
            world.isAirBlock(x, y, z) && world.getTileEntity(x, y, z) == null,
            "Previous sound fixture was not cleared");
        actor.inventory.mainInventory[supplySlot] = sample.copy();
        actor.inventory.markDirty();
        HologramSession session = new HologramSession(actor, x, y, z, false, 1, false);
        session.stockNativePrivate = true;
        session.virtual = session.virtualPlacement = session.virtualLocked = session.open = true;
        session.virtualPreset = HologramTestLabBlueprintProbe.preset(actor.getUniqueID(), actor.getCommandSenderName());
        session.materialSettings.main = true;
        session.materialSettings.containers = session.materialSettings.me = false;
        session.materialContext = new HologramMaterials.Context(actor, session.materialSettings, toolSlot);
        ItemBlock item = (ItemBlock) sample.getItem();
        Block block = item.field_150939_a;
        ownedTarget = block;
        // GT machine item damage is the MTE ID, not its placed block metadata. Use the
        // same registered prototype/base type as the actual ItemMachines.placeBlockAt.
        int metadata = item instanceof gregtech.common.blocks.ItemMachines
            ? GregTechAPI.METATILEENTITIES[sample.getItemDamage()].getTileEntityBaseType()
            : item.getMetadata(sample.getItemDamage());
        HologramNativeConstruction.Prepared target = saved
            ? HologramNativeConstruction.saved(session, block, metadata, sample, -1, "", "")
            : HologramNativeConstruction.projection(
                new HologramNativeTargetPlan.Cell(
                    0,
                    0,
                    0,
                    block,
                    metadata,
                    sample,
                    -1,
                    new NBTTagCompound(),
                    new NBTTagCompound(),
                    null,
                    null,
                    false));
        int stock = stock(sample), soundCount = sounds.size(), events = placeEvents;
        veto = rejected;
        HologramNativeConstruction.Outcome outcome;
        try {
            outcome = HologramNativeConstruction.place(session, target, x, y, z, this::authority);
        } finally {
            veto = false;
        }
        require(sounds.size() == soundCount, "Production placement emitted an actual World sound: " + name);
        require(
            placeEvents == events + 1,
            "Native final protection event count differs: " + name
                + "; outcome="
                + outcome
                + "; status="
                + session.status);
        require(!HologramConstructionJournal.pending(actor), "Placement/veto left an unresolved payment journal");
        require(
            outcome
                == (rejected ? HologramNativeConstruction.Outcome.FAILED : HologramNativeConstruction.Outcome.PLACED),
            "Native placement/veto result differs: " + name + " " + session.status);
        require(stock(sample) == stock - (rejected ? 0 : 1), "Native debit/refund differs: " + name);
        if (rejected) require(
            world.isAirBlock(x, y, z) && world.getTileEntity(x, y, z) == null,
            "Forge-vetoed sound fixture did not restore air/no tile");
        else require(
            HologramNativeConstruction.matches(session, target, x, y, z),
            "Native placed block/state differs: " + name);
        NBTTagCompound row = new NBTTagCompound();
        row.setString("case", name);
        row.setBoolean("savedBlueprint", saved);
        row.setString(
            "itemClass",
            item.getClass()
                .getName());
        row.setBoolean("inheritsVanillaUse", HologramNativeConstruction.inheritsVanillaItemUse(item.getClass()));
        row.setInteger("soundEvents", sounds.size() - soundCount);
        row.setInteger("protectionEvents", placeEvents - events);
        row.setInteger("materialDebit", stock - stock(sample));
        row.setBoolean("vetoed", rejected);
        clear();
        manualPositive();
        actor.inventory.mainInventory[supplySlot] = null;
        return row;
    }

    private void manualPositive() {
        require(world.isAirBlock(x + 2, y, z), "Manual sound positive target is not empty");
        int before = sounds.size();
        ItemStack manual = new ItemStack(Blocks.stone);
        require(
            manual.getItem()
                .onItemUse(manual, actor, world, x + 2, y, z, 1, .5f, .5f, .5f),
            "Actual manual ItemBlock.onItemUse refused positive control");
        require(
            sounds.size() == before + 1 && world.getBlock(x + 2, y, z) == Blocks.stone,
            "Unscoped manual placement did not dispatch exactly one native sound");
        world.setBlockToAir(x + 2, y, z);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void placed(BlockEvent.PlaceEvent event) {
        if (event.world == world && event.player == actor && event.x == x && event.y == y && event.z == z) {
            placeEvents++;
            if (veto) event.setCanceled(true);
        }
    }

    private int stock(ItemStack sample) {
        int count = 0;
        for (ItemStack stack : actor.inventory.mainInventory)
            if (stack != null && stack.isItemEqual(sample) && ItemStack.areItemStackTagsEqual(stack, sample))
                count += stack.stackSize;
        return count;
    }

    private boolean authority() {
        return actor.worldObj == world && actor.inventory.currentItem == toolSlot
            && actor.getHeldItem() == tool
            && !actor.capabilities.isCreativeMode
            && !actor.isDead
            && actor.capabilities.allowEdit;
    }

    private void clear() {
        require(
            world.isAirBlock(x, y, z) || world.getBlock(x, y, z) == ownedTarget,
            "Sound fixture target changed externally; preserve it for inspection");
        if (!world.isAirBlock(x, y, z)) world.setBlockToAir(x, y, z);
    }

    private void restore() {
        if (registered) {
            MinecraftForge.EVENT_BUS.unregister(this);
            world.removeWorldAccess(listener);
        }
        require(
            !HologramConstructionJournal.pending(actor),
            "Sound fixture recovery pending; preserve world/resources");
        clear();
        require(
            world.isAirBlock(x + 2, y, z) || world.getBlock(x + 2, y, z) == Blocks.stone,
            "Manual sound fixture changed externally; preserve it for inspection");
        if (!world.isAirBlock(x + 2, y, z)) world.setBlockToAir(x + 2, y, z);
        actor.inventory.readFromNBT((NBTTagList) inventoryBefore.copy());
        actor.inventory.currentItem = toolSlot;
        actor.inventory.markDirty();
        actor.inventoryContainer.detectAndSendChanges();
        actor.setPositionAndUpdate(actorX, actorY, actorZ);
        actor.rotationYaw = yaw;
        actor.rotationPitch = pitch;
        require(
            inventoryBefore.equals(actor.inventory.writeToNBT(new NBTTagList())),
            "Sound fixture inventory restoration failed");
    }

    private static IWorldAccess sounds(List<NBTTagCompound> events) {
        return (IWorldAccess) Proxy.newProxyInstance(
            IWorldAccess.class.getClassLoader(),
            new Class<?>[] { IWorldAccess.class },
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName()
                        .equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName()
                        .equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                    return "GTMB scoped construction sound witness";
                }
                if (args != null && args.length == 6
                    && args[0] instanceof String
                    && args[1] instanceof Double
                    && args[2] instanceof Double
                    && args[3] instanceof Double
                    && args[4] instanceof Float
                    && args[5] instanceof Float) {
                    NBTTagCompound row = new NBTTagCompound();
                    row.setString("sound", (String) args[0]);
                    row.setDouble("x", (Double) args[1]);
                    row.setDouble("y", (Double) args[2]);
                    row.setDouble("z", (Double) args[3]);
                    events.add(row);
                }
                return null;
            });
    }

    private static final class ExpectedFailure extends RuntimeException {
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException("construction sound probe: " + message);
    }
}
