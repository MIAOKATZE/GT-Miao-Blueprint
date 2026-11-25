// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection;
import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** A swap/remove preview belongs only to the explicit selection, never to the declaration-wide material index.
 * Server ownership, admissibility, funds and actual mutation remain authoritative. */
final class HologramLocalHatchDelta {
    final NBTTagCompound theory = new NBTTagCompound();
    final boolean removal;
    String blocker = "";
    int exchanges, turns, unchanged, removals;

    private static final class Group {
        final ItemStack target;
        final Map<Integer, Integer> originals = new LinkedHashMap<>();
        int needed, rotations;
        Group(ItemStack target) { this.target = target; }
    }

    HologramLocalHatchDelta(HologramState source, NBTTagCompound settings) {
        removal = source.data.getInteger("mode") == 2;
        int[] selected = settings.getIntArray("selectedIndices");
        NBTTagList rows = new NBTTagList();
        theory.setTag("rows", rows);
        if (settings.getInteger("scope") != 2 || selected.length == 0) {
            blocker = "请先选中要" + (removal ? "拆除" : "换装") + "的仓室。";
            return;
        }
        if (selected.length > HologramDeclaredNativeSelection.MAX_POINTS) {
            blocker = "所选仓室超过单次上限 " + HologramDeclaredNativeSelection.MAX_POINTS + "。";
            return;
        }
        var seen = new HashSet<Integer>();
        var groups = new LinkedHashMap<Integer, Group>();
        for (int index : selected) {
            if (index < 0 || index >= source.cells.size() || !seen.add(index)) {
                blocker = "所选位置无效，请重新选择。"; return;
            }
            var cell = source.cells.get(index);
            var old = cell.actualStack;
            if (cell.anchor || cell.excluded || !cell.inScope || !cell.hasDisplayTag(true, "siteHatchTier", 3)
                || old == null || !(old.getItem() instanceof ItemMachines) || old.hasTagCompound()
                || cell.actualFacing < 0 || cell.actualFacing > 5
                || !(ItemMachines.getMetaTileEntity(old) instanceof MTEHatch before)) {
                blocker = "所选位置未确认是现场仓室。"; return;
            }
            if (removal) {
                Group group = groups.computeIfAbsent(old.getItemDamage(), k -> new Group(old));
                group.originals.merge(old.getItemDamage(), 1, Integer::sum);
                removals++;
                continue;
            }
            var target = cell.targetStack;
            if (!"manual".equals(cell.pinScope) && cell.hatchFacing < 0) {
                blocker = "请为每个所选仓室指定替换类型或朝向。"; return;
            }
            if (target == null || target.getItem() != old.getItem() || target.hasTagCompound()
                || !(ItemMachines.getMetaTileEntity(target) instanceof MTEHatch after) || before.getClass() != after.getClass()) {
                blocker = "所选换装不是可核对的同类仓室。"; return;
            }
            boolean same = target.getItemDamage() == old.getItemDamage();
            if (same && (cell.hatchFacing < 0 || cell.hatchFacing == cell.actualFacing)) { unchanged++; continue; }
            Group group = groups.computeIfAbsent(target.getItemDamage(), k -> new Group(target));
            group.originals.merge(old.getItemDamage(), 1, Integer::sum);
            if (same) { group.rotations++; turns++; }
            else { group.needed++; exchanges++; }
        }
        if (!removal && exchanges + turns == 0) { blocker = "所选仓室和朝向没有变化。"; return; }
        for (Group group : groups.values()) {
            var row = HologramMaterialQuote.itemRow(group.target, group.needed, 0);
            String registry = row.getString("itemRegistry");
            var note = new StringBuilder(removal ? "回收：" : "原件：");
            group.originals.forEach((id, number) -> {
                var item = new ItemStack(group.target.getItem(), 1, id);
                note.append(HologramMaterialRowPresentation.itemName(item, registry)).append(" ×").append(number).append("；");
            });
            if (group.rotations > 0) note.append("仅转向 ").append(group.rotations).append("，不扣料");
            row.setString("hatchChange", note.toString());
            rows.appendTag(row);
        }
    }

    String status() {
        if (!blocker.isEmpty()) return blocker;
        return removal ? "拆除 " + removals + " 个仓室，不扣料。"
            : "替换 " + exchanges + " 个，转向 " + turns + " 个，未变 " + unchanged + " 个。";
    }
}
