package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramNetwork;

/** The shared tool settings menu uses only server supplied preset identifiers. */
final class HologramSettingsScreen extends HologramMuiScreen {

    private HologramState state;
    private boolean main, containers, me, downgrade;
    private int priority, recovery, presetIndex = -1;
    private long sequence;
    private long pendingSequence = -1;
    private final HologramProgress.Tracker progress = new HologramProgress.Tracker();
    private String progressNotice = "";

    HologramSettingsScreen(HologramState snapshot) {
        super("settings", 300, 282);
        updateState(snapshot);
    }

    void updateState(HologramState snapshot) {
        progressNotice = "";
        if (state != null && state.session.equals(snapshot.session)
            && pendingSequence >= 0
            && snapshot.data.getLong("uiSequence") >= pendingSequence) progress.finish(System.nanoTime());
        else if (state != null && !state.session.equals(snapshot.session)) progress.clear();
        state = snapshot;
        if (snapshot.data.getLong("uiSequence") >= pendingSequence) pendingSequence = -1;
        sequence = Math.max(sequence, snapshot.data.getLong("uiSequence"));
        main = snapshot.data.getBoolean("materialMain");
        containers = snapshot.data.getBoolean("materialContainers");
        me = snapshot.data.getBoolean("materialMe");
        downgrade = snapshot.data.getBoolean("hatchDowngrade");
        priority = Math.max(0, Math.min(2, snapshot.data.getInteger("materialPriority")));
        recovery = snapshot.data.getInteger("recoveryDestination") == 1 ? 1 : 0;
        presetIndex = -1;
        NBTTagList presets = presets();
        for (int i = 0; i < presets.tagCount(); i++) if (presets.getCompoundTagAt(i)
            .getString("id")
            .equals(snapshot.data.getString("selectedPresetId"))) presetIndex = i;

    }

    private NBTTagList presets() {
        return state.data.getTagList("presets", 10);
    }

    private String on(boolean enabled) {
        return enabled ? "开启" : "关闭";
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), () -> "投影仪设置 · 与编辑界面共享", 300);
        for (int id = 0; id <= 6; id++) {
            final int action = id;
            panel().child(
                HologramMuiTheme.button(() -> label(action), 20, 34 + id * 24, 260, 20, () -> activate(action))
                    .setEnabledIf(widget -> pendingSequence < 0 && (action != 5 || presets().tagCount() > 0)));
        }
        panel().child(
            HologramMuiTheme.primaryButton(() -> "保存设置", 20, 208, 126, 20, () -> activate(7))
                .setEnabledIf(widget -> pendingSequence < 0));
        panel().child(
            HologramMuiTheme.button(() -> "返回编辑", 154, 208, 126, 20, () -> activate(8))
                .setEnabledIf(widget -> pendingSequence < 0));
        panel().child(HologramMuiTheme.label(() -> "ME 不可用时回收至主背包；背包满则持久暂存", 12, 234, 276, 20, 0xffbacbd1));
        panel().child(HologramMuiTheme.label(this::status, 12, 254, 276, 20, 0xffe5c488));
    }

    private String label(int id) {
        return switch (id) {
            case 0 -> "取料 · 主背包：" + on(main);
            case 1 -> "取料 · 背包容器：" + on(containers);
            case 2 -> "取料 · ME：" + on(me);
            case 3 -> "优先取料：" + new String[] { "主背包", "背包容器", "ME" }[priority];
            case 4 -> "缺料时仓室等级降级：" + on(downgrade);
            case 5 -> "自定义模板：" + (presetIndex < 0 ? "未选择"
                : presets().getCompoundTagAt(presetIndex)
                    .getString("name"));
            default -> "默认回收：" + (recovery == 1 ? "ME 优先" : "主背包");
        };
    }

    private void activate(int id) {
        if (pendingSequence >= 0 || id == 5 && presets().tagCount() == 0) return;
        switch (id) {
            case 0 -> main = !main;
            case 1 -> containers = !containers;
            case 2 -> me = !me;
            case 3 -> priority = (priority + 1) % 3;
            case 4 -> downgrade = !downgrade;
            case 5 -> presetIndex = (presetIndex + 1) % presets().tagCount();
            case 6 -> recovery = 1 - recovery;
            case 7 -> save();
            case 8 -> {
                NBTTagCompound request = new NBTTagCompound();
                request.setString("action", "useAir");
                HologramNetwork.sendAction(request);
            }
            default -> {}
        }
    }

    private void save() {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("op", "settings");
        request.setString("session", state.session);
        request.setLong("uiSequence", ++sequence);
        pendingSequence = sequence;
        progressNotice = "";
        progress.update(state.session + ":" + sequence, "settings", 0, 0, "", false, System.nanoTime());
        request.setBoolean("materialMain", main);
        request.setBoolean("materialContainers", containers);
        request.setBoolean("materialMe", me);
        request.setInteger("materialPriority", priority);
        request.setBoolean("hatchDowngrade", downgrade);
        request.setInteger("recoveryDestination", recovery);
        request.setString(
            "selectedPresetId",
            presetIndex < 0 ? ""
                : presets().getCompoundTagAt(presetIndex)
                    .getString("id"));
        HologramNetwork.sendAction(request);
    }

    void transportRejected(NBTTagCompound event) {
        if (!state.session.equals(event.getString("session")) || pendingSequence < 0
            || pendingSequence != event.getLong("uiSequence")) return;
        pendingSequence = -1;
        progress.clear();
        progressNotice = HologramProgressView.phase("busy");
    }

    private String status() {
        HologramProgress waiting = progress.view(System.nanoTime());
        if (waiting != null) return HologramProgressView.summary(waiting, System.nanoTime());
        String error = state.data.getString("presetConfigError");
        return !progressNotice.isEmpty() ? progressNotice
            : error.isEmpty() ? state.data.getString("status") : "预设列表读取失败：" + error;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
