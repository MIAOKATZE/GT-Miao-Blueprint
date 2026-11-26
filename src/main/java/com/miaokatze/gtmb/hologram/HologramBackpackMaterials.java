// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.function.Predicate;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.util.InventoryUtility;
import com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackExtractor;

/** Optional Backpack inventory access. Its disk save is shared by UUID, not embedded in the carried item. */
final class HologramBackpackMaterials implements ItemStackExtractor {

    private static final String ROOT = "de.eydamos.backpack.";
    private static final HologramBackpackMaterials INSTANCE = load();
    private final Class<?> item, saveType, container;
    private final Method ender, read, write, save, manual, opened, containerSave, uuid;
    private final Class<?> config;

    private HologramBackpackMaterials() throws ReflectiveOperationException {
        item = Class.forName(ROOT + "item.ItemBackpackBase");
        saveType = Class.forName(ROOT + "saves.BackpackSave");
        Class<?> inventory = Class.forName(ROOT + "inventory.InventoryBackpack");
        container = Class.forName(ROOT + "inventory.container.ContainerAdvanced");
        config = Class.forName(ROOT + "misc.ConfigurationBackpack");
        ender = Class.forName(ROOT + "util.BackpackUtil")
            .getMethod("isEnderBackpack", ItemStack.class);
        read = inventory.getMethod("readFromNBT", saveType);
        write = inventory.getMethod("writeToNBT", saveType);
        save = saveType.getMethod("save");
        manual = saveType.getMethod("setManualSaving");
        opened = Class.forName(ROOT + "helper.GuiHelper")
            .getMethod("isOpenedByOtherPlayer", String.class, net.minecraft.entity.player.EntityPlayer.class);
        containerSave = container.getMethod("getBackpackSave");
        uuid = saveType.getMethod("getUUID");
    }

    private static HologramBackpackMaterials load() {
        try {
            return new HologramBackpackMaterials();
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Backpack material integration unavailable", incompatible);
            return null;
        }
    }

    static ItemStackExtractor adapter() {
        return INSTANCE;
    }

    static String identity(ItemStack source) {
        return INSTANCE != null && source != null
            && INSTANCE.item.isInstance(source.getItem())
            && source.hasTagCompound()
                ? source.getTagCompound()
                    .getString("backpack-UID")
                : "";
    }

    @Override
    public boolean isAPIImplemented(APIType api) {
        return api == APIType.MAIN || api == APIType.IS_VALID_SOURCE;
    }

    @Override
    public boolean isValidSource(ItemStack source, EntityPlayerMP player) {
        String id = identity(source);
        if (player == null || id.isEmpty()) return false;
        try {
            if ((boolean) ender.invoke(null, source) || config.getField("OPEN_ONLY_PERSONAL_BACKPACK")
                .getBoolean(null)) return false;
            for (String dimension : (String[]) config.getField("FORBIDDEN_DIMENSIONS")
                .get(null)) if (dimension.equals(Integer.toString(player.dimension))) return false;
            if ((boolean) opened.invoke(null, id, player)) return false;
            if (container.isInstance(player.openContainer)) {
                Object current = containerSave.invoke(player.openContainer);
                if (current != null && id.equals(uuid.invoke(current))) return false;
            }
            return true;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot validate Backpack material source", failure);
        }
    }

    @Override
    public int takeFromStack(Predicate<ItemStack> predicate, boolean simulate, int count,
        InventoryUtility.ItemStackCounter counter, ItemStack source, ItemStack filter, EntityPlayerMP player) {
        if (count <= 0 || !isValidSource(source, player)) return 0;
        try {
            // Reload before every operation: another actor may have modified an alias since the previous tick.
            Object data = saveType.getConstructor(String.class)
                .newInstance(identity(source));
            IInventory inventory = (IInventory) Class.forName(ROOT + "inventory.InventoryBackpack")
                .getConstructor(String.class, String.class)
                .newInstance("", "");
            read.invoke(inventory, data);
            int taken = 0;
            java.util.List<ItemStack> extracted = new java.util.ArrayList<>();
            for (int slot = 0; slot < inventory.getSizeInventory() && taken < count; slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (stack == null || stack.stackSize <= 0 || !predicate.test(stack)) continue;
                int amount = Math.min(count - taken, stack.stackSize);
                ItemStack part = stack.copy();
                part.stackSize = amount;
                extracted.add(part);
                taken += amount;
                if (!simulate) inventory.decrStackSize(slot, amount);
            }
            if (!simulate && taken > 0) {
                // Flush once per extraction, before handing real items to the construction transaction.
                manual.invoke(data);
                inventory.markDirty();
                write.invoke(inventory, data);
                save.invoke(data);
            }
            for (ItemStack stack : extracted) counter.add(stack, stack.stackSize);
            return taken;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot access Backpack material save", failure);
        }
    }
}
