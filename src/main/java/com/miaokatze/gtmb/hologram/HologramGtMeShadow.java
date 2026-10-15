package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.util.GTUtility;

/** GT .205 ME hatches retain configuration and empty components, never network mirrors or pending cargo. */
public final class HologramGtMeShadow {

    private static final String FLUID = "gregtech.common.tileentities.machines.MTEHatchInputME";
    private static final String ITEM = "gregtech.common.tileentities.machines.MTEHatchInputBusME";
    private static final String OUTPUT_FLUID = "gregtech.common.tileentities.machines.outputme.MTEHatchOutputME";
    private static final String OUTPUT_ITEM = "gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME";
    private static final int SLOT_COUNT = 16;
    private static final Set<String> SLOT_FIELDS = new HashSet<>(
        java.util.Arrays.asList("index", "config", "extracted", "extractedAmount"));

    private HologramGtMeShadow() {}

    /** Pure storage/payment/placement projection. The captured tile and connected ME inventory remain untouched. */
    public static NBTTagCompound project(NBTTagCompound source) throws IOException {
        if (source == null) return null;
        NBTTagCompound copy = (NBTTagCompound) source.copy();
        String owner = owner(copy);
        if (owner == null) return copy;
        if (owner.equals(OUTPUT_FLUID) || owner.equals(OUTPUT_ITEM)) return output(copy, owner.equals(OUTPUT_FLUID));
        boolean fluid = owner.equals(FLUID);
        inputContents(copy, fluid);
        if (!copy.hasKey("version", 3) || copy.getInteger("version") != 1) {
            // Omitting every ME slot field means an explicitly empty host, never a guessed legacy migration.
            if (!copy.hasKey("version") && !copy.hasKey("slots")
                && !copy.hasKey("storedFluids")
                && !copy.hasKey("sizes")
                && (fluid || !copy.hasKey("Inventory"))) {
                copy.setInteger("version", 1);
                return copy;
            }
            throw unsupported("version", "只支持已审阅的 version:int=1；旧版镜像必须先显式忽略对应存储字段");
        }
        if (copy.hasKey("storedFluids") || copy.hasKey("sizes")) throw unsupported("slots", "当前格式中存在未迁移的旧版 ME 镜像字段");
        if (!copy.hasKey("slots")) return copy;
        if (!copy.hasKey("slots", 9)) throw unsupported("slots", "槽位必须为列表");
        NBTTagList slots = (NBTTagList) copy.getTag("slots");
        if (slots.tagCount() > SLOT_COUNT || slots.tagCount() > 0 && slots.func_150303_d() != 10)
            throw unsupported("slots", "槽位数量或条目类型无效");
        int occupied = 0;
        for (int i = 0; i < slots.tagCount(); i++) {
            NBTTagCompound row = slots.getCompoundTagAt(i);
            String path = "slots/" + i;
            for (Object raw : row.func_150296_c())
                if (!SLOT_FIELDS.contains((String) raw)) throw unsupported(path + "/" + raw, "未审核的额外槽位字段");
            if (!row.hasKey("index", 3)) throw unsupported(path + "/index", "索引必须为 int");
            int index = row.getInteger("index");
            if (index < 0 || index >= SLOT_COUNT || (occupied & (1 << index)) != 0)
                throw unsupported(path + "/index", "索引越界或重复");
            occupied |= 1 << index;
            if (!row.hasKey("config", 10)) throw unsupported(path + "/config", "缺少完整的原生筛选样本");
            validateStack(row.getCompoundTag("config"), fluid, path + "/config");
            if (row.hasKey("extracted")) {
                if (!row.hasKey("extracted", 10)) throw unsupported(path + "/extracted", "镜像必须为原生栈对象");
                validateStack(row.getCompoundTag("extracted"), fluid, path + "/extracted");
            }
            if (row.hasKey("extractedAmount")
                && (!row.hasKey("extractedAmount", 3) || row.getInteger("extractedAmount") < 0))
                throw unsupported(path + "/extractedAmount", "镜像数量必须为非负 int");
            row.removeTag("extracted");
            row.removeTag("extractedAmount");
        }
        return copy;
    }

    /** Only this exact owner/version/path grants the sample exemption; unrelated slots and config fields do not. */
    static Set<String> configurationPaths(NBTTagCompound projected) throws IOException {
        if (projected == null || owner(projected) == null) return Collections.emptySet();
        NBTTagCompound checked = project(projected);
        Set<String> result = new HashSet<>();
        NBTTagList inventory = checked.getTagList("Inventory", 10);
        for (int i = 0; i < inventory.tagCount(); i++) result.add("Inventory/" + i);
        NBTTagList slots = checked.getTagList("slots", 10);
        for (int i = 0; i < slots.tagCount(); i++) result.add("slots/" + i + "/config");
        return result;
    }

    /** Empty cell bodies and installed cards have independent real costs; partitions remain ghost configuration. */
    static List<ItemStack> componentMaterials(NBTTagCompound payload) throws IOException {
        String owner = payload == null ? null : owner(payload);
        if (!OUTPUT_FLUID.equals(owner) && !OUTPUT_ITEM.equals(owner)) return Collections.emptyList();
        List<ItemStack> result = new ArrayList<>();
        NBTTagList inventory = project(payload).getTagList("Inventory", 10);
        for (int i = 0; i < inventory.tagCount(); i++) {
            ItemStack cell = ItemStack
                .loadItemStackFromNBT(HologramBlueprintNbt.nativePayload(inventory.getCompoundTagAt(i)));
            for (ItemStack material : HologramAeBlueprint.emptyCellProjection(cell).materials)
                result.add(material.copy());
        }
        return result;
    }

    private static void inputContents(NBTTagCompound data, boolean fluid) throws IOException {
        NBTTagList kept = new NBTTagList();
        NBTTagList inventory = inventory(data);
        int occupied = 0;
        for (int i = 0; i < inventory.tagCount(); i++) {
            NBTTagCompound row = inventory.getCompoundTagAt(i);
            int slot = slot(row, fluid ? 1 : 2, occupied);
            occupied |= 1 << slot;
            validateStack(row, false, "Inventory/" + i);
            if (!fluid && slot == 0) {
                if (!ItemList.Circuit_Integrated.hasBeenSet())
                    throw unsupported("Inventory/" + i, "原生集成电路物品尚未注册，无法核验配置电路");
                ItemStack circuit = ItemStack.loadItemStackFromNBT(HologramBlueprintNbt.nativePayload(row));
                if (!GTUtility.isAnyIntegratedCircuit(circuit)) throw unsupported("Inventory/" + i, "配置电路槽不是原生集成电路");
                kept.appendTag(row.copy());
            }
        }
        if (data.hasKey("Inventory")) data.setTag("Inventory", kept);
        omitTank(data);
    }

    private static NBTTagCompound output(NBTTagCompound data, boolean fluid) throws IOException {
        NBTTagList inventory = inventory(data), kept = new NBTTagList();
        int occupied = 0;
        for (int i = 0; i < inventory.tagCount(); i++) {
            NBTTagCompound row = inventory.getCompoundTagAt(i);
            int slot = slot(row, 1, occupied);
            occupied |= 1 << slot;
            validateStack(row, false, "Inventory/" + i);
            ItemStack source = ItemStack.loadItemStackFromNBT(HologramBlueprintNbt.nativePayload(row));
            if (row.getLong("Count") != 1) throw unsupported("Inventory/" + i, "存储元件数量必须为一件");
            HologramAeBlueprint.EmptyCellProjection cell = HologramAeBlueprint.emptyCellProjection(source);
            NBTTagCompound clean = cell.stack.writeToNBT(new NBTTagCompound());
            clean.setInteger("IntSlot", slot);
            kept.appendTag(clean);
        }
        if (data.hasKey("Inventory")) data.setTag("Inventory", kept);
        for (String key : fluid ? new String[] { "cache", "cachedFluids" } : new String[] { "cache", "cachedItems" }) {
            if (!data.hasKey(key)) continue;
            if (!data.hasKey(key, 9)) throw unsupported(key, "输出缓存必须是列表");
            NBTTagList cache = (NBTTagList) data.getTag(key);
            if (cache.tagCount() > 0 && cache.func_150303_d() != 10) throw unsupported(key, "输出缓存条目必须是NBT对象");
            for (int i = 0; i < cache.tagCount(); i++) {
                NBTTagCompound row = cache.getCompoundTagAt(i);
                String stackKey = fluid ? "fluidStack" : "itemStack";
                if (!fluid && key.equals("cachedItems") && !row.hasKey(stackKey))
                    validateStack(row, false, key + "/" + i);
                else {
                    if (!row.hasKey(stackKey, 10) || !row.hasKey("size", 4)
                        || row.getLong("size") < 0
                        || row.func_150296_c()
                            .size() != 2)
                        throw unsupported(key + "/" + i, "输出缓存记录格式无效");
                    validateStack(row.getCompoundTag(stackKey), fluid, key + "/" + i + "/" + stackKey);
                }
            }
            data.removeTag(key);
        }
        if (!fluid && data.hasKey("cachedStack")) {
            if (!data.hasKey("cachedStack", 10)) throw unsupported("cachedStack", "旧输出缓存记录必须是NBT对象");
            validateStack(data.getCompoundTag("cachedStack"), false, "cachedStack");
            data.removeTag("cachedStack");
        }
        omitTank(data);
        return data;
    }

    private static NBTTagList inventory(NBTTagCompound data) throws IOException {
        if (!data.hasKey("Inventory")) return new NBTTagList();
        if (!data.hasKey("Inventory", 9)) throw unsupported("Inventory", "库存必须是列表");
        NBTTagList inventory = (NBTTagList) data.getTag("Inventory");
        if (inventory.tagCount() > 2 || inventory.tagCount() > 0 && inventory.func_150303_d() != 10)
            throw unsupported("Inventory", "库存数量或条目类型无效");
        return inventory;
    }

    private static int slot(NBTTagCompound row, int size, int occupied) throws IOException {
        if (!row.hasKey("IntSlot", 3)) throw unsupported("Inventory", "槽位索引必须为原生IntSlot");
        int slot = row.getInteger("IntSlot");
        if (slot < 0 || slot >= size || (occupied & 1 << slot) != 0) throw unsupported("Inventory", "槽位越界或重复");
        return slot;
    }

    private static void omitTank(NBTTagCompound data) throws IOException {
        if (!data.hasKey("mFluid")) return;
        if (!data.hasKey("mFluid", 10)) throw unsupported("mFluid", "仓室流体必须是原生栈对象");
        NBTTagCompound fluid = data.getCompoundTag("mFluid");
        if (!fluid.hasNoTags()) validateStack(fluid, true, "mFluid");
        data.removeTag("mFluid");
    }

    private static String owner(NBTTagCompound payload) {
        if (!payload.getString("id")
            .equals("BaseMetaTileEntity") || !payload.hasKey("mID", 99)
            || payload.getTag("mID")
                .getId() > 4)
            return null;
        long id = payload.getLong("mID");
        if (id < 0 || id >= GregTechAPI.METATILEENTITIES.length) return null;
        IMetaTileEntity registered = GregTechAPI.METATILEENTITIES[(int) id];
        if (registered == null) return null;
        String name = registered.getClass()
            .getName();
        return name.equals(FLUID) || name.equals(ITEM) || name.equals(OUTPUT_FLUID) || name.equals(OUTPUT_ITEM) ? name
            : null;
    }

    private static void validateStack(NBTTagCompound stack, boolean fluid, String path) throws IOException {
        if (fluid) {
            if (!stack.hasKey("FluidName", 8) || !stack.hasKey("Amount", 3)
                || stack.getInteger("Amount") < 0
                || stack.hasKey("Tag") && !stack.hasKey("Tag", 10)
                || FluidStack.loadFluidStackFromNBT(stack) == null) throw unsupported(path, "流体筛选或镜像记录类型/注册身份无效");
            return;
        }
        if (!stack.hasKey("Count", 99) || stack.getTag("Count")
            .getId() > 4
            || stack.getLong("Count") < 0
            || stack.getLong("Count") > Integer.MAX_VALUE
            || !stack.hasKey("Damage", 99)
            || stack.getTag("Damage")
                .getId() > 4
            || !stack.hasKey("id")
            || stack.hasKey("tag") && !stack.hasKey("tag", 10)
            || HologramBlueprintNbt.hasMissingItems(stack)) throw unsupported(path, "物品筛选或镜像记录类型/注册身份无效");
        ItemStack nativeStack = ItemStack.loadItemStackFromNBT(HologramBlueprintNbt.nativePayload(stack));
        if (nativeStack == null || nativeStack.getItem() == null) throw unsupported(path, "物品筛选或镜像未注册");
    }

    private static IOException unsupported(String path, String detail) {
        return new IOException("GT ME 输入仓数据不受支持 " + path + "：" + detail + "；请显式忽略该 ME 槽位内容后重试");
    }
}
