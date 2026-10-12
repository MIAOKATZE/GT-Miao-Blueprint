package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Concrete persisted annotation scopes, shared by the editor and its visible preview summary. */
final class HologramAnnotations {

    private HologramAnnotations() {}

    static String scope(HologramState state, int[] indices, int scope) {
        if (scope == 0) return "范围：整份蓝图（" + state.cells.size() + " 格）";
        int[] bounds = bounds(state, indices);
        if (bounds == null) return "范围：未选中方块";
        return "范围：" + (scope == 1 ? "所选 " + indices.length + " 格" : "立体提示区") + " · " + position(bounds);
    }

    static String saved(HologramState state, int[] indices, int scope) {
        if (scope == 0) return empty(state.data.getString("blueprintPrompt"));
        if (scope == 1) {
            String value = null;
            for (int index : indices) {
                if (index < 0 || index >= state.cells.size()) continue;
                String prompt = state.cells.get(index).prompt;
                if (value != null && !value.equals(prompt)) return "所选方块提示不同，保存后统一应用输入内容";
                value = prompt;
            }
            return empty(value == null ? "" : value);
        }
        int[] selected = bounds(state, indices);
        NBTTagList areas = state.data.getTagList("hintAreas", 10);
        for (int i = areas.tagCount() - 1; i >= 0; i--) {
            NBTTagCompound area = areas.getCompoundTagAt(i);
            int[] bounds = areaBounds(area);
            if (selected == null || overlap(selected, bounds))
                return "提示区 " + (i + 1) + " · " + position(bounds) + "：" + empty(area.getString("prompt"));
        }
        return selected == null ? "尚无提示区" : "此位置尚无提示区";
    }

    static String input(HologramState state, int[] indices, int scope) {
        if (scope == 0) return state.data.getString("blueprintPrompt");
        if (scope == 1) {
            String value = null;
            for (int index : indices) {
                if (index < 0 || index >= state.cells.size()) continue;
                String prompt = state.cells.get(index).prompt;
                if (value != null && !value.equals(prompt)) return "";
                value = prompt;
            }
            return value == null ? "" : value;
        }
        int[] selected = bounds(state, indices);
        if (selected == null) return "";
        NBTTagList areas = state.data.getTagList("hintAreas", 10);
        for (int i = areas.tagCount() - 1; i >= 0; i--) {
            NBTTagCompound area = areas.getCompoundTagAt(i);
            if (java.util.Arrays.equals(selected, areaBounds(area))) return area.getString("prompt");
        }
        return "";
    }

    static int[] bounds(HologramState state, int[] indices) {
        int[] result = null;
        for (int index : indices) {
            if (index < 0 || index >= state.cells.size()) continue;
            HologramState.Cell cell = state.cells.get(index);
            if (result == null) result = new int[] { cell.dx, cell.dy, cell.dz, cell.dx, cell.dy, cell.dz };
            else {
                result[0] = Math.min(result[0], cell.dx);
                result[1] = Math.min(result[1], cell.dy);
                result[2] = Math.min(result[2], cell.dz);
                result[3] = Math.max(result[3], cell.dx);
                result[4] = Math.max(result[4], cell.dy);
                result[5] = Math.max(result[5], cell.dz);
            }
        }
        return result;
    }

    private static int[] areaBounds(NBTTagCompound area) {
        int[] bounds = area.getIntArray("bounds");
        return bounds.length == 6 ? bounds : new int[6];
    }

    private static boolean overlap(int[] a, int[] b) {
        return a[0] <= b[3] && a[3] >= b[0] && a[1] <= b[4] && a[4] >= b[1] && a[2] <= b[5] && a[5] >= b[2];
    }

    private static String position(int[] b) {
        return "(" + b[0] + "," + b[1] + "," + b[2] + ") → (" + b[3] + "," + b[4] + "," + b[5] + ")";
    }

    private static String empty(String value) {
        return value.isEmpty() ? "未设置" : value;
    }
}
