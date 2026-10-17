package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

/** Native GTSWN information-panel query templates, copied by the UI without consuming an inventory item. */
final class HologramGtswnPanelConfiguration {

    private HologramGtswnPanelConfiguration() {}

    static Set<String> paths(NBTTagCompound data) throws IOException {
        Set<String> paths = new HashSet<>();
        if (!"gtswn.network_info_panel".equals(data.getString("id"))) return paths;
        for (String key : Arrays.asList("chartItem", "chartFluid", "monitoredItems", "monitoredFluids")) {
            if (!data.hasKey(key)) continue;
            boolean fluid = key.endsWith("Fluid") || key.endsWith("Fluids");
            if (key.startsWith("chart")) {
                if (!data.hasKey(key, 10)) throw invalid(key);
                sample(data.getCompoundTag(key), fluid, key);
            } else {
                if (!data.hasKey(key, 9)) throw invalid(key);
                NBTTagList list = (NBTTagList) data.getTag(key);
                if (list.tagCount() > 4096 || list.tagCount() > 0 && list.func_150303_d() != 10) throw invalid(key);
                for (int i = 0; i < list.tagCount(); i++) sample(list.getCompoundTagAt(i), fluid, key + "/" + i);
            }
            paths.add(key);
        }
        return paths;
    }

    private static void sample(NBTTagCompound transported, boolean fluid, String path) throws IOException {
        NBTTagCompound row = HologramBlueprintNbt.nativePayload(transported);
        Set<String> fields = new HashSet<>(
            fluid ? Arrays.asList("FluidName", "Amount", "Tag") : Arrays.asList("id", "Count", "Damage", "tag"));
        for (Object field : row.func_150296_c()) if (!fields.contains(field)) throw invalid(path);
        if (fluid) {
            if (!row.hasKey("FluidName", 8) || !row.hasKey("Amount", 3)
                || row.getInteger("Amount") < 0
                || row.hasKey("Tag") && !row.hasKey("Tag", 10)
                || FluidStack.loadFluidStackFromNBT(row) == null) throw invalid(path);
        } else {
            if (!row.hasKey("id", 2) || !row.hasKey("Count", 1)
                || row.getByte("Count") < 0
                || !row.hasKey("Damage", 2)
                || row.hasKey("tag") && !row.hasKey("tag", 10)
                || HologramBlueprintNbt.hasMissingItems(transported)) throw invalid(path);
            ItemStack sample = ItemStack.loadItemStackFromNBT(row);
            if (sample == null || sample.getItem() == null) throw invalid(path);
        }
    }

    private static IOException invalid(String path) {
        return new IOException("GTSWN 信息面板查询样本格式无效：" + path + "；请核验或明确移除此条目");
    }
}
