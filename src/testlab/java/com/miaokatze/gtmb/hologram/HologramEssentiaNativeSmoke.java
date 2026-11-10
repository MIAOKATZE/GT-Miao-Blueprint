package com.miaokatze.gtmb.hologram;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

/** Genuine TC item/WorldServer checks. Full service and native ME network checks have separate world fixtures. */
public final class HologramEssentiaNativeSmoke {

    private static int assertions;

    private HologramEssentiaNativeSmoke() {}

    public static void run(WorldServer world, EntityPlayerMP actor) throws Exception {
        if (world == null || actor.worldObj != world
            || !(world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-smoke-")
                || world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-followup-")))
            throw new IllegalArgumentException("Isolated real FML smoke world and server actor required");
        assertions = 0;
        System.out.println("[GTMB-ESSENTIA-NATIVE] START lifecycle=real-FML source=real-TC-item monitor=none");
        Object registered = Block.blockRegistry.getObject("Thaumcraft:blockJar");
        check(registered instanceof Block, "actual_registered_tc_jar_block");
        Block jarBlock = (Block) registered;
        ItemStack[] original = copyInventory(actor);
        boolean creative = actor.capabilities.isCreativeMode;
        int x = 760, y = 90, z = 412;
        world.getChunkFromBlockCoords(x, z);
        check(world.isAirBlock(x, y, z), "isolated_target_is_air");
        try {
            for (int slot = 0; slot < actor.inventory.mainInventory.length; slot++)
                if (slot != actor.inventory.currentItem) actor.inventory.mainInventory[slot] = null;
            int sourceSlot = actor.inventory.currentItem == 1 ? 2 : 1;
            actor.inventory.mainInventory[sourceSlot] = filledJar("aer", 42);
            NBTTagCompound target = new NBTTagCompound();
            target.setString("id", "TileJar");
            target.setString("Aspect", "aer");
            target.setShort("Amount", (short) 32);
            target.setInteger("x", x);
            target.setInteger("y", y);
            target.setInteger("z", z);
            HologramEssentia.validatePlacement(jarBlock, 0, target);
            HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(target);
            check(
                plan.essentia.size() == 1 && plan.essentia.get(0).amount == 32,
                "final_native_tile_contents_quote_32_points");
            HologramMaterials.Settings settings = new HologramMaterials.Settings();
            settings.containers = false;
            settings.me = false;
            HologramMaterials.Context source = new HologramMaterials.Context(actor, settings);
            ItemStack before = actor.inventory.mainInventory[sourceSlot].copy();
            check(source.essentiaCount("aer", 64) == 42, "actual_native_item_source_contains_42");
            check(
                ItemStack.areItemStacksEqual(before, actor.inventory.mainInventory[sourceSlot]),
                "native_quote_leaves_source_nbt_unchanged");
            HologramMaterials.Transaction payment = source.begin();
            check(payment.takeEssentia("aer", 32, true), "native_simulation_can_pay_32");
            check(
                HologramEssentia.jarAmount(actor.inventory.mainInventory[sourceSlot], "aer") == 42,
                "simulation_never_debits_native_item");
            check(plan.reserve(payment), "native_reservation_extracts_real_points");
            check(
                HologramEssentia.jarAmount(actor.inventory.mainInventory[sourceSlot], "aer") == 10,
                "native_source_debit_is_exactly_32");
            check(world.setBlock(x, y, z, jarBlock, 0, 3), "actual_world_jar_created_after_payment");
            TileEntity placed = world.getTileEntity(x, y, z);
            check(
                placed != null && placed.getClass()
                    .getName()
                    .equals("thaumcraft.common.tiles.TileJarFillable"),
                "native_registered_jar_tile_created");
            placed.readFromNBT(target);
            NBTTagCompound nativeResult = new NBTTagCompound();
            placed.writeToNBT(nativeResult);
            check(
                HologramEssentia.jarCost(nativeResult).amount == 32 && nativeResult.getString("Aspect")
                    .equals("aer"),
                "actual_native_target_contains_paid_32");
            payment.commit();
            check(
                !source.begin()
                    .takeEssentia("aer", 32, false),
                "insufficient_real_stock_rejects_payment");
            check(
                HologramEssentia.jarAmount(actor.inventory.mainInventory[sourceSlot], "aer") == 10,
                "shortage_preserves_the_remaining_10");
            actor.inventory.mainInventory[sourceSlot] = filledJar("aer", 64);
            ItemStack[] rollbackSnapshot = copyInventory(actor);
            HologramMaterials.Transaction failed = source.begin();
            check(plan.reserve(failed), "reservation_before_later_placement_failure");
            check(
                HologramEssentia.jarAmount(actor.inventory.mainInventory[sourceSlot], "aer") == 32,
                "pre_failure_debit_is_real");
            failed.rollbackExternal();
            // This is the documented service contract: local items restore from its inventory snapshot.
            restoreInventory(actor, rollbackSnapshot);
            failed.rollbackExternal();
            check(
                HologramEssentia.jarAmount(actor.inventory.mainInventory[sourceSlot], "aer") == 64,
                "snapshot_rollback_returns_local_points_once");
            actor.inventory.mainInventory[sourceSlot] = null;
            actor.capabilities.isCreativeMode = true;
            check(!plan.reserveEssentia(source.begin()), "creative_shell_policy_still_requires_real_essentia");
            target.setShort("Amount", (short) 0);
            target.removeTag("Aspect");
            check(
                HologramBlueprintResources.plan(target)
                    .reserveEssentia(source.begin()),
                "explicit_empty_creative_jar_requires_no_content_payment");
            ItemStack full = filledJar("aer", 64);
            check(
                !HologramMaterials.shellPredicate(full)
                    .test(full),
                "fuzzy_native_item_identity_never_consumes_loaded_jar_as_shell");
            ItemStack empty = HologramEssentia.drainJar(full, "aer", 64);
            check(
                empty != null && empty.getItem() == Item.getItemFromBlock(jarBlock),
                "draining_a_whole_native_jar_returns_the_real_empty_shell");
            check(HologramEssentia.jarAmount(full, "aer") == 64, "adapter_projection_does_not_edit_its_input_item");
            System.out.println(
                "[GTMB-ESSENTIA-NATIVE] FINAL PASS assertions=" + assertions
                    + " lifecycle=real-FML source=real-TC-item target=real-WorldServer monitor=none");
        } finally {
            world.setBlockToAir(x, y, z);
            actor.capabilities.isCreativeMode = creative;
            restoreInventory(actor, original);
        }
    }

    public static ItemStack filledJar(String aspectTag, int amount) throws Exception {
        if (amount <= 0 || amount > 64) throw new IllegalArgumentException("Native jar capacity is 64");
        Item jar = null;
        for (Object registered : Item.itemRegistry) if (registered instanceof Item && registered.getClass()
            .getName()
            .equals("thaumcraft.common.blocks.ItemJarFilled")) {
                jar = (Item) registered;
                break;
            }
        if (jar == null) throw new IllegalStateException("Real TC jar item is required");
        Class<?> aspectClass = Class.forName("thaumcraft.api.aspects.Aspect");
        Object aspect = aspectClass.getMethod("getAspect", String.class)
            .invoke(null, aspectTag);
        if (aspect == null) throw new IllegalArgumentException("Registered aspect required");
        Class<?> listClass = Class.forName("thaumcraft.api.aspects.AspectList");
        Object list = listClass.getConstructor()
            .newInstance();
        listClass.getMethod("add", aspectClass, int.class)
            .invoke(list, aspect, amount);
        ItemStack result = new ItemStack(jar, 1, 0);
        jar.getClass()
            .getMethod("setAspects", ItemStack.class, listClass)
            .invoke(jar, result, list);
        return result;
    }

    private static ItemStack[] copyInventory(EntityPlayerMP actor) {
        ItemStack[] copy = new ItemStack[actor.inventory.mainInventory.length];
        for (int i = 0; i < copy.length; i++)
            copy[i] = actor.inventory.mainInventory[i] == null ? null : actor.inventory.mainInventory[i].copy();
        return copy;
    }

    private static void restoreInventory(EntityPlayerMP actor, ItemStack[] inventory) {
        for (int i = 0; i < inventory.length; i++)
            actor.inventory.mainInventory[i] = inventory[i] == null ? null : inventory[i].copy();
        actor.inventory.markDirty();
        if (actor.inventoryContainer != null) actor.inventoryContainer.detectAndSendChanges();
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Real TC essentia smoke: " + name);
        assertions++;
    }
}
