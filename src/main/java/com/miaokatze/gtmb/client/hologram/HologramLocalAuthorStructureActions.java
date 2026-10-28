package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramLocalAuthorStructures;

/** Runtime-owned local declaration continuation; completion publishes one source/history transaction. */
final class HologramLocalAuthorStructureActions implements AutoCloseable {

    private final HologramLocalAuthorRuntime runtime;
    private HologramLocalAuthorStructures task;
    private HologramUiModules requester;
    private long revision;
    private String structure;
    private boolean inspection;

    HologramLocalAuthorStructureActions(HologramLocalAuthorRuntime runtime) {
        this.runtime = runtime;
    }

    static void start(HologramLocalAuthorRuntime runtime, HologramUiModules ui, NBTTagCompound request)
        throws IOException {
        runtime.structures()
            .begin(ui, request, false);
    }

    static void inspect(HologramLocalAuthorRuntime runtime, HologramUiModules ui) throws IOException {
        if (runtime.structureCapabilitiesCurrent() || runtime.structures()
            .busy()) return;
        runtime.structures()
            .begin(ui, new NBTTagCompound(), true);
    }

    private void begin(HologramUiModules ui, NBTTagCompound request, boolean inspect) throws IOException {
        if (task != null) throw new IOException("正在重建结构声明，请等待或取消");
        if (!runtime.editable()) throw new IOException(runtime.diagnostic());
        inspection = inspect;
        revision = runtime.document()
            .revision();
        if (request.hasKey("planRevision", 4) && revision != request.getLong("planRevision"))
            throw new IOException("文档已变化，请重新打开结构参数");
        NBTTagCompound view = runtime.view().data;
        structure = view.getString("activeStructure");
        if (structure.isEmpty()
            || request.hasKey("structureId", 8) && !structure.equals(request.getString("structureId")))
            throw new IOException("请选择当前原生来源结构");
        ItemStack seed = ui.host.minecraft().thePlayer == null ? null : ui.host.minecraft().thePlayer.getHeldItem();
        task = new HologramLocalAuthorStructures(
            runtime.document(),
            structure,
            request.hasKey("main", 3) ? request.getInteger("main") : view.getInteger("main"),
            request.hasKey("channels", 10) ? request.getCompoundTag("channels") : view.getCompoundTag("channels"),
            request.hasKey("noHatches", 1) ? request.getBoolean("noHatches") : view.getBoolean("noHatches"),
            seed,
            inspection);
        requester = ui;
        ui.session.notice(inspection ? "正在本地读取结构信道，原稿保持不变" : "正在本地重建所选结构，原稿保持不变");
    }

    boolean busy() {
        return task != null;
    }

    String phase() {
        return task == null ? "" : task.phase();
    }

    long completed() {
        return task == null ? 0 : task.completed();
    }

    long total() {
        return task == null ? 0 : task.total();
    }

    void tick() {
        HologramLocalAuthorStructures pending = task;
        if (pending == null) return;
        try {
            if (!pending.current(runtime.document())
                || !structure.equals(runtime.view().data.getString("activeStructure")))
                throw new IOException("文档或结构选择已变化，旧声明任务已取消");
            if (!pending.tick()) return;
            if (!inspection) runtime.apply(
                runtime.editor()
                    .replaceStructure(revision, pending.group(), pending.cells(), pending.headerPatch()));
            runtime.setStructureCapabilities(
                runtime.document()
                    .revision(),
                structure,
                pending.capabilities(),
                pending.facingOrdinal());
            finish(inspection ? "原生结构信道已读取；文档与撤销历史未变化" : "所选结构的几何与候选材料已更新；其他结构及明确编辑已保留");
        } catch (IOException | RuntimeException | LinkageError failure) {
            finish("结构参数未应用，原稿保留：" + failure.getMessage());
        }
    }

    void cancel() {
        if (task != null) finish("结构声明重建已取消，原稿保留");
    }

    @Override
    public void close() {
        cancel();
    }

    private void finish(String message) {
        HologramLocalAuthorStructures pending = task;
        HologramUiModules ui = requester;
        task = null;
        requester = null;
        if (pending != null) pending.close();
        if (ui != null) ui.session.notice(message);
    }
}
