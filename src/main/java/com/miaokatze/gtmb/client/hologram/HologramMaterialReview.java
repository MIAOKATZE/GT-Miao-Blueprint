package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtmb.hologram.HologramMaterialQuote;

/** Read-only server quote. Only visible rows resolve registry objects; identities are never merged by name. */
final class HologramMaterialReview {

    private NBTTagCompound quote;
    private boolean current;
    private NBTTagList rows = new NBTTagList();
    private final Map<Integer, Row> visible = new LinkedHashMap<Integer, Row>(32, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Row> entry) {
            return size() > 32;
        }
    };

    boolean update(NBTTagCompound state) {
        NBTTagCompound next = state.getCompoundTag("materialQuote");
        current = next.getBoolean("complete") && (!next.hasKey("session", 8) || next.getString("session")
            .equals(state.getString("session")))
            && (!next.hasKey("planRevision", 4) || next.getLong("planRevision") == state.getLong("planRevision"));
        if (quote == next) return false;
        quote = next;
        rows = next.getTagList("rows", 10);
        visible.clear();
        return true;
    }

    int size() {
        return rows.tagCount();
    }

    boolean ready() {
        return current && quote != null && quote.hasKey("rows", 9);
    }

    Row row(int index) {
        if (index < 0 || index >= size()) return null;
        Row cached = visible.get(index);
        if (cached == null) {
            cached = new Row(rows.getCompoundTagAt(index), index);
            visible.put(index, cached);
        }
        return cached;
    }

    static String amount(NBTTagCompound data, String key) {
        // Energy can exceed a long; the server uses a decimal string for those rows.
        if (data.hasKey(key + "Exact", 8)) return data.getString(key + "Exact");
        return data.hasKey(key, 8) ? data.getString(key) : Long.toString(Math.max(0, data.getLong(key)));
    }

    static final class Row {

        final NBTTagCompound data;
        final int index;
        final String name, required, available, missing, unit;
        final ItemStack item;
        final FluidStack fluid;
        final boolean shortage, tagged;

        Row(NBTTagCompound data, int index) {
            this.data = data;
            this.index = index;
            required = amount(data, "required");
            available = data.getBoolean("unlimited") ? "∞" : amount(data, "available");
            missing = amount(data, "missing");
            shortage = !"0".equals(missing) && !missing.isEmpty();
            unit = data.getString("unit");
            // Numeric registry IDs are not portable across installations. Never fall back to an unrelated item.
            item = HologramMaterialQuote.readItem(data);
            fluid = data.hasKey("fluid", 10) ? FluidStack.loadFluidStackFromNBT(data.getCompoundTag("fluid")) : null;
            if (fluid != null) fluid.amount = 1;
            tagged = item != null && item.hasTagCompound() || fluid != null && fluid.tag != null;
            String label = data.getString("label");
            name = item != null ? item.getDisplayName()
                : fluid != null ? fluid.getLocalizedName() : label.isEmpty() ? "无法解析的材料" : label;
        }

        String identity() {
            String registry = data.getString("itemRegistry");
            return registry.isEmpty() ? data.getString("kind") + " · " + data.getString("nbtKey")
                : registry + ":"
                    + data.getCompoundTag("item")
                        .getShort("Damage");
        }
    }
}
