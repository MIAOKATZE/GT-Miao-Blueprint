package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;

/**
 * Two explicit in-memory raw examples. Never instantiates the live browser transport or touches its static active
 * owner.
 */
final class HologramEditorCutListLesson
    implements HologramCutStorageBrowserView.Selection, HologramCutStoragePreviewView {

    private final List<NBTTagCompound> directory = new ArrayList<>();
    private final Map<String, NBTTagCompound> examples = new LinkedHashMap<>();
    private HologramCutStorageBrowserView browser;
    private ListWidget<IWidget, ?> frameScroll;
    private HologramCutRawPreviewSession renderer;
    private NBTTagCompound raw, dragCamera;
    private String selected = "", notice = "只读教学内存示例 · 未读取真实剪切目录";
    private int refreshes;
    private boolean closed;

    HologramEditorCutListLesson() {
        example("tutorial-cut-a", "教学示例 · 石砖", 1, 3, 1, 2, "minecraft:stonebrick");
        example("tutorial-cut-b", "教学示例 · 红砖", 2, 2, 2, 2, "minecraft:brick_block");
        select("tutorial-cut-a");
    }

    ParentWidget<?> build(HologramUiModules ui) {
        int width = Math.min(456, Math.max(220, ui.host.screenWidth() - 16));
        int availableHeight = ui.host.screenHeight() - HologramEditorTutorialOverlay.RESERVED_HEIGHT;
        int height = Math.min(292, Math.max(220, availableHeight - 12));
        ParentWidget<?> body = new ParentWidget<>().sizeRel(1f)
            .expanded()
            .name("tutorial.cut.container");
        browser = new HologramCutStorageBrowserView(width, height, this, this, ui.shell::registerTarget);
        browser.pos(Math.max(0, (ui.host.screenWidth() - width) / 2), Math.max(6, (availableHeight - height) / 2))
            .background(HologramEditorNativeTheme.PANEL)
            .name("tutorial.cut.browser");
        // Keep the shared browser's camera/details readable; short teaching surfaces scroll the complete form.
        frameScroll = new ListWidget<>().sizeRel(1f)
            .name("tutorial.cut.viewport");
        frameScroll.child(
            new ParentWidget<>().widthRel(1f)
                .height(Math.max(availableHeight, height + 12))
                .child(browser));
        body.child(frameScroll);
        ui.shell.registerTarget("cut.browser", browser);
        browser.child(
            HologramEditorNativeTheme.label(() -> "私有剪切列表 · 教学内存示例", HologramEditorNativeTheme.TITLE)
                .pos(12, 10)
                .size(width - 24, 18));
        browser.child(
            HologramEditorNativeTheme.label(() -> "点列表只读预览；真实条目与世界操作保持不变。", HologramEditorNativeTheme.MUTED)
                .pos(12, 35)
                .size(width - 24, 15));
        int gap = 5, buttonWidth = (width - 24 - gap * 3) / 4, y = height - 43;
        button(ui, "place", "放置所选", 12, y, buttonWidth, false, () -> {});
        button(ui, "undo", "撤回原处", 12 + buttonWidth + gap, y, buttonWidth, false, () -> {});
        button(ui, "sync", "同步列表", 12 + (buttonWidth + gap) * 2, y, buttonWidth, true, this::refresh);
        button(ui, "close", "关闭", 12 + (buttonWidth + gap) * 3, y, buttonWidth, true, () -> {
            closed = true;
            browser.setEnabled(false);
        });
        IWidget closedMessage = HologramEditorNativeTheme
            .label(() -> "列表演示已关闭；完成教程后回到原列表与原相机。", HologramEditorTheme.BUTTON_TEXT)
            .leftRel(.5f)
            .topRel(.5f)
            .size(Math.min(width, 380), 24)
            .setEnabledIf(w -> closed)
            .name("tutorial.cut.closed");
        body.child(closedMessage);
        ui.shell.registerTarget("cut.closed", closedMessage);
        return body;
    }

    private void button(HologramUiModules ui, String key, String label, int x, int y, int width, boolean enabled,
        Runnable action) {
        ButtonWidget<?> button = HologramEditorNativeTheme
            .button(() -> label, null, action, () -> enabled, () -> false, false)
            .pos(x, y)
            .size(width, 20)
            .name("tutorial.cut." + key)
            .tooltipBuilder(t -> t.addLine(enabled ? label : "列表教学不执行此世界操作；请查看普通或等距剪切操作课程。"));
        browser.child(button);
        ui.shell.registerTarget("cut." + key, button);
    }

    IWidget previewWidget() {
        return browser.camera;
    }

    private void example(String id, String name, long sequence, int x, int y, int z, String block) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("id", id);
        entry.setString("name", name);
        entry.setLong("sequence", sequence);
        entry.setInteger("dimension", 0);
        entry.setInteger("sizeX", x);
        entry.setInteger("sizeY", y);
        entry.setInteger("sizeZ", z);
        directory.add((NBTTagCompound) entry.copy());
        NBTTagList cells = new NBTTagList();
        for (int xx = 0; xx < x; xx++) for (int yy = 0; yy < y; yy++) for (int zz = 0; zz < z; zz++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", xx);
            cell.setInteger("y", yy);
            cell.setInteger("z", zz);
            cell.setString("block", block);
            cell.setInteger("meta", 0);
            cells.appendTag(cell);
        }
        entry.setTag("cells", cells);
        examples.put(id, entry);
    }

    public List<NBTTagCompound> entries() {
        return directory;
    }

    public String selected() {
        return selected;
    }

    public void select(String id) {
        if (selected.equals(id) || !examples.containsKey(id)) return;
        if (renderer != null) renderer.release();
        selected = id;
        raw = (NBTTagCompound) examples.get(id)
            .copy();
        renderer = new HologramCutRawPreviewSession();
        renderer.update(raw);
    }

    public HologramCutRawPreviewSession renderer() {
        return renderer;
    }

    public NBTTagCompound raw() {
        return raw;
    }

    public String notice() {
        if (renderer != null && !renderer.diagnostics()
            .isEmpty()) return renderer.diagnostics()
                .get(0);
        return notice;
    }

    private void refresh() {
        refreshes++;
        notice = "已刷新教学内存目录 · 未向服务器发送请求";
    }

    void beginOrbit() {
        dragCamera = browser.camera.cameraState();
    }

    void orbit(float x, float y) {
        if (dragCamera == null) return;
        browser.camera.restoreCamera(dragCamera);
        browser.camera.orbitBy(x, y);
    }

    NBTTagCompound snapshot() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("selected", selected);
        state.setInteger("refreshes", refreshes);
        state.setBoolean("closed", closed);
        state.setString("notice", notice);
        state.setInteger("rows", directory.size());
        state.setInteger(
            "cells",
            raw == null ? 0
                : raw.getTagList("cells", 10)
                    .tagCount());
        if (browser != null) {
            state.setTag("camera", browser.camera.cameraState());
            if (frameScroll.isValid() && frameScroll.getScrollData() != null) state.setInteger(
                "outerScroll",
                frameScroll.getScrollData()
                    .getScroll());
            if (browser.list.isValid() && browser.list.getScrollData() != null) state.setInteger(
                "scroll",
                browser.list.getScrollData()
                    .getScroll());
        }
        return state;
    }

    void restore(NBTTagCompound state) {
        select(state.getString("selected"));
        refreshes = state.getInteger("refreshes");
        closed = state.getBoolean("closed");
        notice = state.getString("notice");
        dragCamera = null;
        browser.setEnabled(!closed);
        browser.camera.restoreCamera(state.getCompoundTag("camera"));
        if (frameScroll.isValid() && frameScroll.getScrollData() != null) frameScroll.getScrollData()
            .scrollTo(frameScroll.getScrollArea(), state.getInteger("outerScroll"));
        if (browser.list.isValid() && browser.list.getScrollData() != null) browser.list.getScrollData()
            .scrollTo(browser.list.getScrollArea(), state.getInteger("scroll"));
    }

    void close() {
        if (renderer != null) renderer.release();
        renderer = null;
        raw = null;
        dragCamera = null;
        if (browser != null) browser.camera.releaseTarget();
    }
}
