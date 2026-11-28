// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual 2.6.18 Backpack classes and save cache, isolated actor/world; not an FML/game acceptance claim. */
public final class HologramBackpackMaterialRegression {

    private static final String ROOT = "de.eydamos.backpack.";

    public static void main(String[] args) {
        int status = 0;
        try {
            if (MinecraftTestBootstrap.enter(HologramBackpackMaterialRegression.class, args)) {
                HologramNativeSavedWorldRegression.withConnectedWorld(0, world -> check(world));
                System.out.println(
                    "PASS real Backpack 2.6.18 save, large inventory, source aliases, debit and refund contract");
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            status = 1;
        }
        System.exit(status);
    }

    private static void check(HologramNativeSavedWorldRegression.ConnectedWorld world) throws Exception {
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var global = field(MinecraftServer.class, "mcServer");
        var previous = global.get(null);
        var server = unsafe.allocateInstance(DedicatedServer.class);
        var manager = unsafe.allocateInstance(DedicatedPlayerList.class);
        field(MinecraftServer.class, "serverConfigManager").set(server, manager);
        field(net.minecraft.server.management.ServerConfigurationManager.class, "playerEntityList")
            .set(manager, new java.util.ArrayList<>(java.util.Collections.singletonList(world.nativeActor())));
        var root = Files.createTempDirectory("gtmb-backpack-material-");
        String previousName = Thread.currentThread()
            .getName();
        global.set(null, server);
        Thread.currentThread()
            .setName("Server thread");
        Class<?> mod = Class.forName(ROOT + "Backpack");
        var saveField = mod.getField("saveFileHandler");
        Object oldSave = saveField.get(null);
        var handler = Class.forName(ROOT + "handler.SaveFileHandler")
            .getConstructor()
            .newInstance();
        field(handler.getClass(), "backpackDir").set(handler, root.toFile());
        saveField.set(null, handler);
        var config = Class.forName(ROOT + "misc.ConfigurationBackpack");
        Object oldDimensions = config.getField("FORBIDDEN_DIMENSIONS")
            .get(null);
        boolean oldPersonal = config.getField("OPEN_ONLY_PERSONAL_BACKPACK")
            .getBoolean(null);
        config.getField("FORBIDDEN_DIMENSIONS")
            .set(null, new String[0]);
        config.getField("OPEN_ONLY_PERSONAL_BACKPACK")
            .setBoolean(null, false);
        try {
            var item = (Item) Class.forName(ROOT + "item.ItemBackpackBase")
                .getConstructor()
                .newInstance();
            Item.itemRegistry.addObject(31990, "gtmb_backpack_api_fixture", item);
            var save = Class.forName(ROOT + "saves.BackpackSave");
            String id = UUID.randomUUID()
                .toString();
            NBTTagCompound data = new NBTTagCompound();
            data.setString("backpack-UID", id);
            data.setInteger("size", 133);
            data.setByte("type", (byte) 1);
            NBTTagCompound inventories = new NBTTagCompound();
            NBTTagList slots = new NBTTagList();
            var row = new ItemStack(Blocks.stone, 7).writeToNBT(new NBTTagCompound());
            row.setByte("slot", (byte) 132);
            slots.appendTag(row);
            inventories.setTag("backpack", slots);
            data.setTag("backpackInventories", inventories);
            save.getMethod("save")
                .invoke(
                    save.getConstructor(NBTTagCompound.class)
                        .newInstance(data));
            var bag = new ItemStack(item, 1, 200);
            bag.setTagCompound(new NBTTagCompound());
            bag.getTagCompound()
                .setString("backpack-UID", id);
            var player = world.nativeActor();
            player.inventory.mainInventory[1] = null;
            player.inventory.mainInventory[2] = bag;
            player.inventory.mainInventory[3] = bag.copy();
            var settings = new HologramMaterials.Settings();
            settings.main = settings.me = false;
            var stock = new HologramMaterials.Context(player, settings, 0);
            var stone = new ItemStack(Blocks.stone);
            SimpleAssert.that(
                HologramBackpackMaterials.adapter() != null && stock.countLong(stone, Long.MAX_VALUE) == 7,
                "large backpack last slot is read through real save API and UUID aliases are counted once");
            SimpleAssert.that(
                stock.takeOne(stone, false) && stock.countLong(stone, Long.MAX_VALUE) == 6,
                "partial stack debit is persisted even when the native inventory did not mark a split dirty");
            var payment = stock.begin();
            SimpleAssert
                .that(payment.takeOne(stone, false), "construction transaction debits the actual backpack source");
            payment.rollbackExternal();
            stock.flushRecovery();
            int refunded = 0;
            for (ItemStack carried : player.inventory.mainInventory)
                if (carried != null && carried.isItemEqual(stone)) refunded += carried.stackSize;
            SimpleAssert.that(
                stock.countLong(stone, Long.MAX_VALUE) == 5 && refunded == 1,
                "rollback keeps the real saved deduction and refunds exactly one actual item through existing recovery");
            settings.recoveryDestination = 2;
            stock.recoverWorld(java.util.Collections.singletonList(new ItemStack(Blocks.stone, 4)));
            stock.flushRecovery();
            SimpleAssert.that(
                stock.countLong(stone, Long.MAX_VALUE) == 9,
                "explicit backpack recovery inserts and saves through the native sink instead of the player inventory");
            ItemStack tagged = new ItemStack(Blocks.stone, 3);
            tagged.setTagCompound(new NBTTagCompound());
            tagged.getTagCompound()
                .setString("fixture", "exact native merge");
            stock.recoverWorld(java.util.Collections.singletonList(tagged));
            stock.flushRecovery();
            SimpleAssert.that(
                stock.countLong(tagged, Long.MAX_VALUE) == 3 && stock.countLong(stone, Long.MAX_VALUE) == 9,
                "native backpack insertion preserves exact component NBT and does not merge with the plain variant");
            int remainder = HologramBackpackMaterials.insert(bag, player, new ItemStack(Blocks.stone, 10000));
            SimpleAssert.that(
                remainder > 0 && remainder < 10000 && stock.countLong(stone, Long.MAX_VALUE) == 10009 - remainder,
                "native capacity returns only the actual unaccepted remainder and persists every accepted item");
            Container previousContainer = player.openContainer;
            var open = (Container) Class.forName(ROOT + "inventory.container.ContainerAdvanced")
                .getConstructor()
                .newInstance();
            field(open.getClass(), "backpackSave").set(
                open,
                save.getConstructor(String.class)
                    .newInstance(id));
            player.openContainer = open;
            try {
                SimpleAssert.that(
                    stock.countLong(stone, Long.MAX_VALUE) == 0,
                    "an open copy cannot race its own saved backpack");
            } finally {
                player.openContainer = previousContainer;
            }
        } finally {
            config.getField("FORBIDDEN_DIMENSIONS")
                .set(null, oldDimensions);
            config.getField("OPEN_ONLY_PERSONAL_BACKPACK")
                .setBoolean(null, oldPersonal);
            saveField.set(null, oldSave);
            global.set(null, previous);
            Thread.currentThread()
                .setName(previousName);
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList())) Files.delete(file);
            }
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
