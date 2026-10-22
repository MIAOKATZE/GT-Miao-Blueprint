package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Two copies of the observed EBF planning geometry. Never instantiates live transport or touches its static active
 * owner.
 */
final class HologramEditorCutListLesson
    implements HologramCutStorageBrowserView.Selection, HologramCutStoragePreviewView {

    private final List<NBTTagCompound> directory = new ArrayList<>();
    private final Map<String, NBTTagCompound> examples = new LinkedHashMap<>();
    private HologramCutStorageBrowserView browser;
    private HologramEditorCutWorkspace workspace;
    private HologramCutRawPreviewSession renderer;
    private NBTTagCompound raw, dragCamera;
    private String selected = "", notice = "只读教学内存示例 · 未读取真实剪切目录";
    private int refreshes;
    private boolean closed;

    HologramEditorCutListLesson() {
        try {
            HologramState sample = new HologramState(
                HologramEditorTutorialSamples.snapshot(HologramEditorTutorialSamples.Kind.PHYSICAL));
            observedExample("tutorial-cut-a", "电力高炉 · 采集规划", 1, sample);
            observedExample("tutorial-cut-b", "电力高炉 · 同一规划副本", 2, sample);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("电力高炉教学样例不可用", error);
        }
        select("tutorial-cut-a");
    }

    private void observedExample(String id, String name, long sequence, HologramState state) {
        NBTTagCompound entry = HologramEditorTutorialGeometry.raw(state);
        entry.setString("id", id);
        entry.setString("name", name);
        entry.setLong("sequence", sequence);
        directory.add((NBTTagCompound) entry.copy());
        examples.put(id, entry);
    }

    HologramEditorCutWorkspace prepareWorkspace(HologramUiModules ui) {
        if (workspace != null) return workspace;
        workspace = new HologramEditorCutWorkspace(ui, this, this, this::refresh);
        workspace.build();
        browser = workspace.browser();
        return workspace;
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
        if (browser.list.isValid() && browser.list.getScrollData() != null) browser.list.getScrollData()
            .scrollTo(browser.list.getScrollArea(), state.getInteger("scroll"));
    }

    void close() {
        if (workspace != null) workspace.close();
        workspace = null;
        if (renderer != null) renderer.release();
        renderer = null;
        raw = null;
        dragCamera = null;
        if (browser != null) browser.camera.releaseTarget();
    }
}
