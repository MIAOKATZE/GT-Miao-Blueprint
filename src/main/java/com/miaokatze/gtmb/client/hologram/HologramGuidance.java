package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** A single contextual next step, shared by the planner and blueprint surfaces. */
final class HologramGuidance {

    private HologramGuidance() {}

    static boolean authorWorkspace(NBTTagCompound state) {
        return authorWorkspace(
            state,
            state.getTagList("cells", 10)
                .tagCount() > 0);
    }

    static boolean authorWorkspace(HologramState state) {
        return authorWorkspace(state.data, !state.cells.isEmpty());
    }

    private static boolean authorWorkspace(NBTTagCompound state, boolean hasCells) {
        if (state.getBoolean("virtualPlacement")) return false;
        if ("blueprintEdit".equals(state.getString("openPurpose")) || state.getBoolean("blueprintDirty")) return true;
        return !state.getBoolean("blueprintSaved") && !state.getBoolean("presetBrowseForPlacement")
            && !"blueprintBrowse".equals(state.getString("openPurpose"))
            && hasCells;
    }

    static boolean selectedSavedBlueprint(NBTTagCompound state, boolean clientServer, String clientServerSelection) {
        if (clientServer) return !clientServerSelection.isEmpty();
        String selected = state.getString("selectedPresetId");
        if (selected.isEmpty()) return false;
        NBTTagList rows = state.getTagList("presets", 10);
        for (int i = 0; i < rows.tagCount(); i++) if (selected.equals(
            rows.getCompoundTagAt(i)
                .getString("id")))
            return true;
        return false;
    }

    static String next(HologramState state, int selected, boolean blueprints, boolean pending) {
        if (pending) return "等待服务端校验…";
        if (state.data.getBoolean("areaSelecting"))
            return "步骤 1/3 · 区域框选：左键第一角 / 右键第二角 · Alt+滚轮调距 · Shift+右击完成 · Esc 取消";
        if (HologramPlacementConfirmation.forceReview(state.data, state.data.getLong("planRevision")))
            return "缺料：再次确认仅放已有；编辑规划或更换材料来源后重新确认";
        if (state.data.getBoolean("blueprintAwaitingConfirmation"))
            return HologramPlacementConfirmation.needsTerminalBinding(state.data)
                ? "放置预检 · 先点击绿色“量子网络绑定”，逐组绑定目标网络后返回确认放置"
                : "放置预检 · 调整本次 NBT / 核对材料 · 确认放置直接施工";
        if (state.data.getInteger("job") == 1) return "正在施工；可暂停或取消";
        if (state.data.getInteger("job") == 2) return "施工已暂停；确认继续或取消";
        if (blueprints) return authorWorkspace(state) && state.data.getBoolean("blueprintProcessingRequired")
            && !state.data.getBoolean("blueprintProcessingComplete") ? "先完成蓝图处理流程，再保存蓝图或编辑方块 NBT"
                : "拖动预览旋转查看 · 列表选择蓝图 · 上方放置进入世界定位";
        if (state.cells.isEmpty()) return "右键控制器导入结构 · Shift+右键框选区域 · 或点击“框选区域”";
        if (state.data.getBoolean("virtualPlacement")) return state.data.getBoolean("virtualLocked")
            ? "位置已锁定（青色）· 方向键水平微调，Alt+上下调高度 · 右键重新核对材料 · Shift+右键解锁 · Esc 取消"
            : "位置预览（绿色）· Alt+滚轮调悬空距离（0=脚下）· 右键锁定 · R 旋转 · Esc 取消";
        if (selected >= 0) return "T 同种全选 · Ctrl 多选 · Shift 框选 · Alt 选空气 · Esc/E 关闭面板"
            + (state.data.getBoolean("virtual") ? " · 完整结构 G 移动轴 · Ctrl+拖轴复制" : "");
        return "点击选方块 · T 同种全选 · 点击结构边缘整选 · Alt 选空气";
    }

    static int recommended(HologramState state, int selected, boolean blueprints, boolean confirmation) {
        return recommended(state, selected, blueprints, confirmation, selectedSavedBlueprint(state.data, false, ""));
    }

    static int recommended(HologramState state, int selected, boolean blueprints, boolean confirmation,
        boolean savedSelected) {
        if (confirmation) return HologramPlacementConfirmation.needsTerminalBinding(state.data) ? 196 : 55;
        if (state.data.getInteger("job") == 2) return 51;
        if (blueprints) return savedSelected ? 141 : 149;
        if (state.data.getBoolean("virtualPlacement") && !state.data.getBoolean("virtualLocked")) return 135;
        if (selected >= 0) return 97;
        if (state.data.getBoolean("target") && !state.cells.isEmpty())
            return state.data.getBoolean("presetActive") ? 50 : 94;
        return 149;
    }

    static int accent(int id, HologramState state, int selected, boolean blueprints, boolean confirmation) {
        return accent(state, id, selected, blueprints, confirmation, selectedSavedBlueprint(state.data, false, ""));
    }

    static int accent(HologramState state, int id, int selected, boolean blueprints, boolean confirmation,
        boolean savedSelected) {
        if (id == 52 && (state.data.getInteger("job") == 1 || state.data.getInteger("job") == 2)
            || id == 54 && confirmation
            || id == 136
            || id == 151) return 0xef7b7b;
        if (id != recommended(state, selected, blueprints, confirmation, savedSelected)) return 0;
        return id == 50 || id == 55 || id == 51 || id == 135 || id == 141 || id == 196 ? 0x80efaa : 0x68dded;
    }

    static int modalAccent(int id, int confirmation, int cancellation) {
        return id == confirmation ? 0x80efaa : id == cancellation ? 0xef7b7b : 0;
    }

    static int structureAccent(NBTTagCompound state, int id) {
        if (state.getBoolean("virtual")) return 0;
        return id == 50 || id == 94 && state.getBoolean("presetAvailable") ? 0x80efaa : 0;
    }
}
