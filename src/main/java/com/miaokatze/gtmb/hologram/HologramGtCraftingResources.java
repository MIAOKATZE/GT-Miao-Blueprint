package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import appeng.api.AEApi;
import appeng.items.misc.ItemEncodedPattern;
import gregtech.api.GregTechAPI;

/** GT .205 crafting hatch persistence: pattern mirrors are configuration; buffered inputs are real assets. */
final class HologramGtCraftingResources {

    private HologramGtCraftingResources() {}

    static boolean supports(NBTTagCompound data) {
        if (data == null || !"BaseMetaTileEntity".equals(data.getString("id")) || !data.hasKey("mID", 99)) return false;
        long id = data.getLong("mID");
        return id >= 0 && id < GregTechAPI.METATILEENTITIES.length
            && GregTechAPI.METATILEENTITIES[(int) id] != null
            && GregTechAPI.METATILEENTITIES[(int) id].getClass()
                .getName()
                .equals("gregtech.common.tileentities.machines.MTEHatchCraftingInputME");
    }

    /** Payment-only projection. The retained payload, including every encoded recipe, is never rewritten. */
    static NBTTagCompound paymentPayload(NBTTagCompound source) throws IOException {
        if (!supports(source)) return source;
        if (source.hasKey("gtmbCraftingResourceCosts")) throw invalid("gtmbCraftingResourceCosts", "字段与内部计价投影冲突");
        NBTTagCompound result = (NBTTagCompound) source.copy();
        Map<Integer, ItemStack> patterns = new HashMap<>();
        NBTTagList inventory = list(result, "Inventory");
        for (int i = 0; i < inventory.tagCount(); i++) {
            NBTTagCompound row = inventory.getCompoundTagAt(i);
            if (!row.hasKey("IntSlot", 3)) throw invalid("Inventory", "物品槽位必须为 IntSlot:int");
            int slot = row.getInteger("IntSlot");
            if (slot < 0 || slot >= 46) throw invalid("Inventory", "物品槽位越界");
            ItemStack item = read(row, "Inventory/" + i);
            if (slot < 36) {
                if (item.stackSize != 1 || !(item.getItem() instanceof ItemEncodedPattern))
                    throw invalid("Inventory", "样板槽必须为一张原生 AE 编码样板");
                if (patterns.put(slot, item) != null) throw invalid("Inventory", "样板槽位重复");
                NBTTagCompound blank = patternMaterial(item).writeToNBT(new NBTTagCompound());
                blank.setInteger("IntSlot", slot);
                // This is only the cost projection, and transport markers describe the original encoded item.
                inventory.func_150304_a(i, blank);
            }
        }
        NBTTagList slots = list(result, "internalInventory"), costs = new NBTTagList();
        java.util.Set<Integer> occupied = new java.util.HashSet<>();
        for (int i = 0; i < slots.tagCount(); i++) {
            NBTTagCompound row = slots.getCompoundTagAt(i);
            if (!row.hasKey("patternSlot", 3) || !row.hasKey("patternSlotNBT", 10)
                || row.func_150296_c()
                    .size() != 2)
                throw invalid("internalInventory", "样板条目结构无效");
            int slot = row.getInteger("patternSlot");
            if (slot < 0 || slot >= 36 || !occupied.add(slot)) throw invalid("internalInventory", "样板槽位越界或重复");
            NBTTagCompound body = row.getCompoundTag("patternSlotNBT");
            for (Object key : body.func_150296_c())
                if (!key.equals("pattern") && !key.equals("inventory") && !key.equals("fluidInventory"))
                    throw invalid("internalInventory", "存在未审阅的样板字段 " + key);
            if (!body.hasKey("pattern", 10)) throw invalid("internalInventory", "缺少编码样板");
            ItemStack pattern = read(
                body.getCompoundTag("pattern"),
                "internalInventory/" + i + "/patternSlotNBT/pattern");
            if (pattern.stackSize != 1 || !(pattern.getItem() instanceof ItemEncodedPattern))
                throw invalid("internalInventory", "样板必须为一张原生 AE 编码样板");
            ItemStack installed = patterns.get(slot);
            if (installed != null && !ItemStack.areItemStacksEqual(installed, pattern))
                throw invalid("internalInventory", "编码样板与实体槽位不一致");
            // Native load restores mInventory[slot] from this mirror. Price it once even if Inventory omitted it.
            if (installed == null) costs.appendTag(patternMaterial(pattern).writeToNBT(new NBTTagCompound()));
            NBTTagCompound buffered = new NBTTagCompound();
            buffered.setTag("inventory", list(body, "inventory").copy());
            buffered.setTag("fluids", list(body, "fluidInventory").copy());
            costs.appendTag(buffered);
        }
        // Use an audited compound wrapper, not a generic exemption for anything named internalInventory.
        result.removeTag("internalInventory");
        NBTTagCompound wrapper = new NBTTagCompound();
        for (int i = 0; i < costs.tagCount(); i++) wrapper.setTag("slot" + i, costs.getCompoundTagAt(i));
        result.setTag("gtmbCraftingResourceCosts", wrapper);
        return result;
    }

    static ItemStack patternMaterial(ItemStack source) throws IOException {
        if (!(source.getItem() instanceof ItemEncodedPattern)) return source.copy();
        ItemStack blank = AEApi.instance()
            .definitions()
            .materials()
            .blankPattern()
            .maybeStack(source.stackSize)
            .orNull();
        if (blank == null || blank.getItem() == null) throw invalid("pattern", "原生空白样板尚未注册");
        return blank;
    }

    private static ItemStack read(NBTTagCompound row, String path) throws IOException {
        if (!row.hasKey("Count", 99) || row.getTag("Count")
            .getId() > 4
            || row.getLong("Count") <= 0
            || row.getLong("Count") > Integer.MAX_VALUE
            || HologramBlueprintNbt.hasMissingItems(row)) throw invalid(path, "物品身份或数量无效");
        ItemStack item = ItemStack.loadItemStackFromNBT(HologramBlueprintNbt.nativePayload(row));
        if (item == null || item.getItem() == null) throw invalid(path, "物品未注册");
        item.stackSize = (int) row.getLong("Count");
        return item;
    }

    private static NBTTagList list(NBTTagCompound data, String key) throws IOException {
        if (!data.hasKey(key)) return new NBTTagList();
        if (!data.hasKey(key, 9)) throw invalid(key, "必须为原生列表");
        NBTTagList list = (NBTTagList) data.getTag(key);
        if (list.tagCount() > 0 && list.func_150303_d() != 10) throw invalid(key, "列表条目必须为复合项");
        return list;
    }

    private static IOException invalid(String path, String reason) {
        return new IOException("GT ME 样板输入总成 " + path + "：" + reason + "；请移除此条目或修复来源后重新采集");
    }
}
