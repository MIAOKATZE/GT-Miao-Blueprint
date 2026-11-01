package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.StructureSources;

/** Options read from captured channel declarations and explicit source contracts. */
public final class HologramCapabilities {

    private HologramCapabilities() {}

    public static Result describe(Object context, ItemStack trigger, HologramChannelTrace.Report report) {
        Result result = new Result(trigger);
        Row main = result.add("main", "主信号（未设子信道时回退）", "master");
        for (int value = 1; value <= 64; value++) main.option(value, Integer.toString(value), null);
        if (report != null && report.context == context) {
            for (HologramChannelTrace.Observation observed : report.observations.values()) {
                List<ItemStack> materials = report.materials.get(observed.id);
                Row row = result.add(
                    observed.id,
                    observed.id + "（原始整数）",
                    materials == null || materials.isEmpty() ? "observed" : "material");
                row.operations = observed.operations;
                row.current = observed.effective;
                if (materials != null)
                    for (int i = 0; i < materials.size(); i++) row.option(i + 1, null, materials.get(i));
            }
        }
        StructureSources.PolicyBinding source = context == null ? null : StructureSources.policy(context.getClass());
        if (source != null && trigger != null) {
            NBTTagList channels = source.channels(trigger);
            for (int i = 0; i < channels.tagCount(); i++) {
                NBTTagCompound declared = channels.getCompoundTagAt(i);
                Row row = result.add(declared.getString("id"), declared.getString("label"), declared.getString("kind"));
                NBTTagList options = declared.getTagList("options", 10);
                for (int j = 0; j < options.tagCount(); j++) {
                    NBTTagCompound option = options.getCompoundTagAt(j);
                    row.option(
                        option.getInteger("value"),
                        option.hasKey("label", 8) ? option.getString("label") : null,
                        option.hasKey("item", 10) ? ItemStack.loadItemStackFromNBT(option.getCompoundTag("item"))
                            : null);
                }
            }
        }
        return result;
    }

    public static final class Result {

        private final Map<String, Row> rows = new LinkedHashMap<>();
        private final NBTTagCompound previous;
        private final int main;

        private Result(ItemStack trigger) {
            main = trigger == null ? 1 : trigger.stackSize;
            previous = trigger != null && trigger.hasTagCompound() ? (NBTTagCompound) trigger.getTagCompound()
                .getCompoundTag("channels")
                .copy() : new NBTTagCompound();
        }

        private Row add(String id, String label, String kind) {
            Row row = new Row(
                id,
                label,
                kind,
                "main".equals(id) ? main : previous.hasKey(id, 3) ? previous.getInteger(id) : main,
                previous.hasKey(id, 3));
            Row old = rows.put(id, row);
            if (old != null) row.operations = old.operations;
            return row;
        }

        public NBTTagList toNBT() {
            NBTTagList list = new NBTTagList();
            for (Row row : rows.values()) list.appendTag(row.toNBT());
            return list;
        }

        /** Options are suggestions; StructureLib signals are positive integers. */
        public boolean validConfiguration(int requestedMain, NBTTagCompound channels) {
            if (requestedMain < 1 || channels == null
                || channels.func_150296_c()
                    .size() > 64)
                return false;
            for (Object key : channels.func_150296_c()) {
                String id = (String) key;
                if (!id.matches("[a-z0-9_.-]{1,48}") || !channels.hasKey(id, 3) || channels.getInteger(id) < 1)
                    return false;
            }
            return true;
        }

        /** Preserve all named signals so a previously unseen dynamic branch can be captured. */
        public NBTTagCompound sanitizeChannels(NBTTagCompound input) {
            return input == null ? new NBTTagCompound() : (NBTTagCompound) input.copy();
        }

        /** Bind the complete saved configuration when capture uses a filtered temporary trigger. */
        public Result withSavedChannels(NBTTagCompound saved) {
            for (Object key : previous.func_150296_c()
                .toArray()) previous.removeTag((String) key);
            if (saved != null) {
                for (Object key : saved.func_150296_c()) {
                    String id = (String) key;
                    if (saved.hasKey(id, 3)) {
                        previous.setInteger(id, saved.getInteger(id));
                        if (!rows.containsKey(id)) add(id, id + "（原始整数）", "observed");
                    }
                }
            }
            for (Row row : rows.values()) if (!"main".equals(row.id)) {
                row.explicit = previous.hasKey(row.id, 3);
                if (row.explicit) row.current = previous.getInteger(row.id);
            }
            return this;
        }

        /** Encodes a UI option as its original channel signal; zero means remove the named key. */
        public boolean encodeOption(NBTTagCompound channels, String id, int value) {
            Row row = rows.get(id);
            if (channels == null || id == null || !id.matches("[a-z0-9_.-]{1,48}") || "main".equals(id)) return false;
            if (row == null) row = add(id, id + "（原始整数）", "observed");
            if (value == 0) channels.removeTag(id);
            else if (value > 0) channels.setInteger(id, value);
            else return false;
            return true;
        }
    }

    private static final class Row {

        private final String id, label, kind;
        private int current;
        private boolean explicit;
        private final List<NBTTagCompound> options = new ArrayList<>();
        private boolean editable = true;
        private int operations;

        private Row(String id, String label, String kind, int current, boolean explicit) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.current = current;
            this.explicit = explicit;
        }

        private void option(int value, String name, ItemStack stack) {
            if (has(value) || (stack != null && stack.getItem() == null)) return;
            NBTTagCompound option = new NBTTagCompound();
            option.setInteger("value", value);
            option.setBoolean("present", true);
            option.setString("label", name == null ? stack.getDisplayName() : name);
            if (stack != null) option.setTag("item", stack.writeToNBT(new NBTTagCompound()));
            options.add(option);
            editable = true;
        }

        private boolean has(int value) {
            for (NBTTagCompound option : options) if (option.getInteger("value") == value) return true;
            return false;
        }

        private NBTTagCompound toNBT() {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", id);
            row.setString("label", label);
            row.setString("kind", kind);
            row.setBoolean("editable", editable);
            row.setBoolean("explicit", explicit);
            row.setInteger("current", "presence".equals(kind) ? explicit ? 1 : 0 : current);
            row.setInteger("operations", operations);
            NBTTagList values = new NBTTagList();
            if (!"main".equals(id) && editable) {
                NBTTagCompound unset = new NBTTagCompound();
                unset.setInteger("value", 0);
                unset.setBoolean("present", false);
                unset.setString("label", "presence".equals(kind) ? "关闭" : "继承主信号");
                values.appendTag(unset);
            }
            for (NBTTagCompound option : options) values.appendTag(option.copy());
            row.setTag("options", values);
            return row;
        }
    }
}
