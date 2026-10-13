package com.miaokatze.gtmb.client.hologram;

/** A single contextual next step, shared by the planner and blueprint surfaces. */
final class HologramGuidance {

    private HologramGuidance() {}

    static String next(HologramState state, int selected, boolean blueprints, boolean pending) {
        if (pending) return "等待服务端校验…";
        if (state.data.getBoolean("areaSelecting")) return "步骤 1/3 · 左键第一角 / 右键第二角 · Alt 使用玩家坐标 · Shift+右击完成 · Esc 取消";
        if (HologramPlacementConfirmation.forceReview(state.data, state.data.getLong("planRevision")))
            return "缺料：再次确认仅放已有；编辑规划或更换材料来源后重新确认";
        if (state.data.getBoolean("blueprintAwaitingConfirmation")) return "放置预检 · 调整本次 NBT / 核对材料 · 确认放置直接施工";
        if (state.data.getInteger("job") == 1) return "正在施工；可暂停或取消";
        if (state.data.getInteger("job") == 2) return "施工已暂停；确认继续或取消";
        if (blueprints) return "步骤 1/3 · 列表选择 → 放置 → 世界右键锁定 → 再次右键核对材料 · Alt+右键快捷预览 · Esc/E 关闭";
        if (state.cells.isEmpty()) return "右键控制器导入结构 · Shift+右键框选区域 · 或点击“框选区域”";
        if (state.data.getBoolean("virtualPlacement")) return state.data.getBoolean("virtualLocked")
            ? "位置已锁定（青色）· 方向键水平微调，Alt+上下调高度 · 右键重新核对材料 · Shift+右键解锁 · Esc 取消"
            : "位置预览（绿色）· Alt+滚轮调悬空距离（0=脚下）· 右键锁定 · R 旋转 · Esc 取消";
        if (selected >= 0) return "T+点击同种 · Ctrl 多选 · Shift 框选 · Alt 选空气 · Esc/E 关闭面板"
            + (state.data.getBoolean("virtual") ? " · 完整结构 G 移动轴 · Ctrl+拖轴复制" : "");
        return "点击选方块 · 线圈等部件自动同族 · T+点击同种 · 点击结构边缘整选 · Alt 选空气";
    }

    static int recommended(HologramState state, int selected, boolean blueprints, boolean confirmation) {
        if (confirmation) return 55;
        if (state.data.getInteger("job") == 2) return 51;
        if (blueprints) return state.data.getString("selectedPresetId")
            .isEmpty() ? 149 : 141;
        if (state.data.getBoolean("virtualPlacement") && !state.data.getBoolean("virtualLocked")) return 135;
        if (selected >= 0) return 146;
        if (state.data.getBoolean("target") && !state.cells.isEmpty()) return 50;
        return 149;
    }

    static int accent(int id, HologramState state, int selected, boolean blueprints, boolean confirmation) {
        if (id == 52 && (state.data.getInteger("job") == 1 || state.data.getInteger("job") == 2)
            || id == 54 && confirmation
            || id == 136
            || id == 151) return 0xef7b7b;
        if (id != recommended(state, selected, blueprints, confirmation)) return 0;
        return id == 50 || id == 55 || id == 51 || id == 135 ? 0x80efaa : 0x68dded;
    }

    static int modalAccent(int id, int confirmation, int cancellation) {
        return id == confirmation ? 0x80efaa : id == cancellation ? 0xef7b7b : 0;
    }
}
