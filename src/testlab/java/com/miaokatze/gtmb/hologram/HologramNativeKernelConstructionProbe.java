// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.miaokatze.gtmb.testlab.TestLab;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Direct shared-kernel contract with real world/payment/events; no workspace TaskGate, GUI or TCP claim. */
public final class HologramNativeKernelConstructionProbe {

    private final WorldServer world;
    private final FakePlayer actor;
    private final HologramSession session;
    private final HologramNativeConstruction.Prepared target;
    private final ItemStack tool, toolBefore;
    private final NBTTagList inventoryBefore;
    private final int x, y, z, toolSlot, supplySlot;
    private final double originalX, originalY, originalZ;
    private final int[] events = new int[21];
    private int cursor, activeIndex = -1, stockDuringVeto = -1, stockAfterVeto = -1;
    private boolean observing, restored;

    public HologramNativeKernelConstructionProbe(WorldServer world, FakePlayer actor, int x, int y, int z)
        throws Exception {
        require(Boolean.getBoolean("gtmb.testlab.nativeConstructionContracts"), "Explicit candidate contract required");
        this.world = world;
        this.actor = actor;
        this.x = x;
        this.y = y;
        this.z = z;
        toolSlot = actor.inventory.currentItem;
        tool = actor.getHeldItem();
        require(tool != null && tool.getItem() == ProjectorRegistry.projector, "Owned real projector required");
        toolBefore = tool.copy();
        require(authority(), "Owned volume, world, tool and non-OP survival actor required");
        for (Object player : world.playerEntities)
            require(player instanceof FakePlayer, "Human presence forbids kernel fixture mutation");
        for (int n = 0; n < 21; n++) require(
            world.isAirBlock(cellX(n), y, cellZ(n)) && world.getTileEntity(cellX(n), y, cellZ(n)) == null,
            "Kernel fixture must start empty");
        require(
            stock() == 0 && !HologramConstructionJournal.pending(actor),
            "Kernel fixture requires no existing stone or journal");
        int vacant = -1;
        for (int n = 0; n < actor.inventory.mainInventory.length; n++)
            if (n != toolSlot && actor.inventory.mainInventory[n] == null) {
                vacant = n;
                break;
            }
        require(vacant >= 0, "Kernel fixture requires an empty supply slot");
        supplySlot = vacant;
        inventoryBefore = actor.inventory.writeToNBT(new NBTTagList());
        originalX = actor.posX;
        originalY = actor.posY;
        originalZ = actor.posZ;
        session = new HologramSession(actor, x, y, z, false, 1, false);
        session.stockNativePrivate = true;
        session.virtual = session.virtualPlacement = session.virtualLocked = session.open = true;
        session.virtualPreset = HologramTestLabBlueprintProbe.preset(actor.getUniqueID(), actor.getCommandSenderName());
        session.materialSettings.main = true;
        session.materialSettings.containers = session.materialSettings.me = false;
        session.materialContext = new HologramMaterials.Context(actor, session.materialSettings, toolSlot);
        target = HologramNativeConstruction.saved(session, Blocks.stone, 0, new ItemStack(Blocks.stone), -1, "", "");
        actor.inventory.mainInventory[supplySlot] = new ItemStack(Blocks.stone, 21);
        actor.inventory.markDirty();
        actor.setPosition(x + 2.5, y + 2, z + 2.5);
        MinecraftForge.EVENT_BUS.register(this);
        observing = true;
    }

    /** Exactly one shared production transaction on each real laboratory tick. */
    public boolean step() {
        require(cursor < 21 && !restored && authority(), "Kernel fixture authority changed");
        activeIndex = cursor;
        HologramNativeConstruction.Outcome outcome;
        try {
            outcome = HologramNativeConstruction
                .place(session, target, cellX(cursor), y, cellZ(cursor), this::authority);
        } finally {
            activeIndex = -1;
        }
        require(
            events[cursor] == 1,
            "Expected one final Forge event for kernel cell " + cursor + ", observed " + events[cursor]);
        require(
            !HologramConstructionJournal.pending(actor) && session.job != 4,
            "Kernel transaction left an unresolved world/payment journal");
        if (cursor < 20) {
            require(outcome == HologramNativeConstruction.Outcome.PLACED, "Kernel placement failed: " + session.status);
            require(
                world.getBlock(cellX(cursor), y, cellZ(cursor)) == Blocks.stone,
                "Kernel success did not leave the actual stone target");
            require(stock() == 20 - cursor, "Successful kernel debit differs");
        } else {
            require(
                outcome == HologramNativeConstruction.Outcome.FAILED,
                "Explicit Forge cancellation was not rejected");
            require(
                world.isAirBlock(cellX(cursor), y, cellZ(cursor))
                    && world.getTileEntity(cellX(cursor), y, cellZ(cursor)) == null,
                "Cancelled kernel world cell was not restored");
            stockAfterVeto = stock();
            require(
                stockDuringVeto == 1 && stockAfterVeto == 1,
                "Ordinary shell must remain unpaid during the protection event and after its rejection");
        }
        cursor++;
        return cursor == 21;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void placed(BlockEvent.PlaceEvent event) {
        if (!observing || event.world != world
            || event.player != actor
            || event.y != y
            || event.x < x
            || event.x > x + 5
            || event.z < z
            || event.z >= z + 4) return;
        require(
            activeIndex >= 0 && event.x == cellX(activeIndex) && event.z == cellZ(activeIndex),
            "Kernel event escaped the active fixture cell");
        require(
            authority() && world.getBlock(event.x, event.y, event.z) == Blocks.stone
                && world.getBlockMetadata(event.x, event.y, event.z) == 0
                && world.getTileEntity(event.x, event.y, event.z) == null,
            "Kernel protection event must observe the final actual target");
        events[activeIndex]++;
        if (activeIndex == 20) {
            stockDuringVeto = stock();
            event.setCanceled(true);
        } else require(!event.isCanceled(), "An external listener cancelled a kernel success fixture");
    }

    public NBTTagCompound evidence() {
        require(cursor == 21 && restored, "Kernel contract and its fixture cleanup must complete first");
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("placed", 20);
        result.setInteger("successfulMaterialDebit", 20);
        result.setInteger("cancelled", 1);
        result.setInteger("stockDuringCancellation", stockDuringVeto);
        result.setInteger("stockAfterCancellation", stockAfterVeto);
        result
            .setString("ordinaryShellPayment", "after accepted Forge protection event; vetoed shell is never debited");
        result.setIntArray("finalPlaceEventsPerCell", events.clone());
        result.setBoolean("journalClear", true);
        result.setBoolean("fixtureWorldAndBorrowedInventoryRestored", true);
        result.setBoolean("workspaceTaskGateExercised", false);
        result.setBoolean("guiExercised", false);
        result.setBoolean("tcpExercised", false);
        result.setString(
            "entry",
            "HologramNativeConstruction.saved/place with real Context/Transaction/WorldServer/Forge PlaceEvent");
        result.setString(
            "scope",
            "kernel-only common-side contract with an owned non-OP survival FakePlayer; modern saved-blueprint end-to-end is separate client coverage");
        return result;
    }

    public void cleanup() {
        if (restored) return;
        if (observing) {
            observing = false;
            MinecraftForge.EVENT_BUS.unregister(this);
        }
        require(
            authority() && !HologramConstructionJournal.pending(actor),
            "Kernel fixture recovery is pending; preserve borrowed resources and world");
        for (int n = 0; n < 21; n++) {
            int cx = cellX(n), cz = cellZ(n);
            require(
                world.getTileEntity(cx, y, cz) == null
                    && (world.isAirBlock(cx, y, cz) || world.getBlock(cx, y, cz) == Blocks.stone),
                "Kernel fixture changed externally; preserve world for inspection");
        }
        require(stock() == 21 - Math.min(cursor, 20), "Kernel borrowed material accounting changed before cleanup");
        for (int n = 0; n < 20; n++)
            if (world.getBlock(cellX(n), y, cellZ(n)) == Blocks.stone) world.setBlockToAir(cellX(n), y, cellZ(n));
        actor.inventory.mainInventory[supplySlot] = null;
        actor.inventory.markDirty();
        require(
            inventoryBefore.equals(actor.inventory.writeToNBT(new NBTTagList())),
            "Kernel cleanup did not restore the original actor inventory");
        actor.setPosition(originalX, originalY, originalZ);
        restored = true;
    }

    private boolean authority() {
        return actor.worldObj == world && actor.dimension == world.provider.dimensionId
            && !actor.isDead
            && !actor.capabilities.isCreativeMode
            && actor.capabilities.allowEdit
            && actor.theItemInWorldManager.getGameType() == GameType.SURVIVAL
            && !actor.canCommandSenderUseCommand(2, "gtmbtestlab")
            && actor.inventory.currentItem == toolSlot
            && actor.getHeldItem() == tool
            && ItemStack.areItemStacksEqual(tool, toolBefore)
            && TestLab.isOwnedFixture(world, actor, x, y, z, 6, 1, 4);
    }

    private int stock() {
        int result = 0;
        for (ItemStack stack : actor.inventory.mainInventory)
            if (stack != null && stack.getItem() == Item.getItemFromBlock(Blocks.stone) && stack.getItemDamage() == 0)
                result += stack.stackSize;
        return result;
    }

    private int cellX(int index) {
        return index == 20 ? x + 5 : x + index % 5;
    }

    private int cellZ(int index) {
        return index == 20 ? z : z + index / 5;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException("native kernel contract: " + message);
    }
}
