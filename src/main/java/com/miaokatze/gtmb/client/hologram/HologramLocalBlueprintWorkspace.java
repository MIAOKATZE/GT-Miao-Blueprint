// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintFiles;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;

/** File I/O and viewport preparation for the ordinary in-client authoring workspace. */
final class HologramLocalBlueprintWorkspace {

    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(8),
        job -> {
            Thread thread = new Thread(job, "GTMB private blueprint files");
            thread.setDaemon(true);
            return thread;
        });

    private static final class Task {

        final HologramState source;
        final Object world;
        final HologramLocalBlueprintDocument draft;
        volatile long revision;
        HologramState.Preparation preparation;
        HologramLocalBlueprintDocument.StructureTask structure;
        String message;
        HologramLocalBlueprintDocument destination;
        String notice;
        String operation = "";
        long sequence, attachmentRequest;

        Task(HologramState source, Object world, HologramLocalBlueprintDocument draft) {
            this.source = source;
            this.world = world;
            this.draft = draft;
            revision = draft == null ? -1 : draft.revision();
        }
    }

    private final HologramUiModules ui;
    private volatile Task task;

    HologramLocalBlueprintWorkspace(HologramUiModules ui) {
        this.ui = ui;
    }

    boolean busy() {
        return task != null;
    }

    String status() {
        Task current = task;
        return current == null ? "" : current.message;
    }

    boolean catalogAvailable() {
        Minecraft mc = ui.host.minecraft();
        return mc != null && mc.mcDataDir != null;
    }

    void openPreset(String id) {
        if (!catalogAvailable()) return;
        HologramClientLibrary store = HologramLibraryClient.INSTANCE.privateStore();
        if (id.startsWith("legacy:")) {
            open(() -> store.readLegacyDocument(id), "");
            return;
        }
        String localId = id.startsWith("local_") ? id : "local_" + id;
        open(() -> store.readPrivateDocument(localId), localId);
    }

    void openCapture(HologramLocalBlueprintDocument document, String notice) {
        Task current = begin("正在准备本地蓝图");
        if (current != null) work(current, () -> prepareView(current, document, notice));
    }

    void openPublic(String id) {
        Task current = begin("正在读取服务器蓝图");
        if (current == null) return;
        HologramLibraryClient.INSTANCE.openPublic(
            id,
            json -> work(current, () -> prepareView(
                current, HologramLocalBlueprintDocument.open(json, ""), "已打开服务器蓝图的本地副本")),
            reason -> failed(current, new IOException(reason)));
    }

    void importNamed(String name) {
        if (!catalogAvailable()) return;
        Path directory = ui.host.minecraft().mcDataDir.toPath()
            .resolve("MiaoBlueprints");
        open(
            () -> HologramBlueprintArtifact.readLocalJson(HologramBlueprintFiles.resolve(directory, shareName(name))),
            "");
    }

    void exportNamed(String id, String name) {
        if (id.isEmpty() || !catalogAvailable()) return;
        Task current = begin("正在导出客户端蓝图");
        if (current == null) return;
        HologramClientLibrary store = HologramLibraryClient.INSTANCE.privateStore();
        Path directory = ui.host.minecraft().mcDataDir.toPath()
            .resolve("MiaoBlueprints");
        work(current, () -> {
            String alias = shareName(name);
            if (id.startsWith("legacy:")) {
                HologramBlueprintFiles.exportHistoricShare(directory, alias, store.readLegacyDocument(id), () -> current(current));
                main(() -> exported(current));
                return;
            }
            String localId = id.startsWith("local_") ? id : "local_" + id;
            Path source = store.savedPath(localId);
            if (!java.nio.file.Files.isRegularFile(HologramBlueprintArtifact.previewPath(source))) {
                HologramBlueprintFiles.exportHistoricShare(directory, alias, store.readPrivateDocument(localId), () -> current(current));
                main(() -> exported(current));
                return;
            }
            HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact.readPair(source);
            Path target = HologramBlueprintFiles.target(directory, alias, pair.json);
            HologramBlueprintArtifact.PairWrite staged = HologramBlueprintArtifact
                .writePairPending(target, pair.json, pair.png, () -> current(current));
            try {
                HologramBlueprintArtifact.readPair(target);
                HologramBlueprintFiles.track(directory, alias, staged);
                staged.commit();
            } catch (IOException | RuntimeException failure) {
                try {
                    staged.rollback();
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
            main(() -> exported(current));
        });
    }

    private void exported(Task current) {
        if (task != current) return;
        task = null;
        HologramLibraryClient.INSTANCE.refreshLocal();
        if (screenCurrent(current)) ui.session.notice("蓝图已导出到客户端");
    }

    void deletePreset(String localId) {
        if (!catalogAvailable()) return;
        Task current = begin("正在删除客户端蓝图");
        if (current == null) return;
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        HologramClientLibrary store = library.privateStore();
        work(current, () -> {
            store.delete(localId);
            main(() -> {
                if (task != current) return;
                task = null;
                if (localId.equals(library.selected(false))) library.select(false, "");
                library.refreshLocal();
                if (screenCurrent(current)) ui.session.notice("客户端蓝图已删除");
            });
        });
    }

    private static String shareName(String name) throws IOException {
        String basename = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        if (basename.isEmpty() || basename.equalsIgnoreCase("hologram-presets")
            || basename.matches("(?i)(?:local_|public_)[a-f0-9-]{36}")) throw new IOException("请输入本地分享文件名");
        return basename;
    }

    private void open(FileSource source, String localId) {
        Task current = begin("正在读取客户端蓝图");
        if (current == null) return;
        work(current, () -> {
            HologramLocalBlueprintDocument document = HologramLocalBlueprintDocument.open(source.read(), localId);
            prepareView(current, document, "已打开客户端蓝图");
        });
    }

    void save(NBTTagCompound options, String replaceId) {
        Minecraft mc = ui.host.minecraft();
        if (!catalogAvailable() || ui.session.document() == null || mc.thePlayer == null) return;
        Task current = begin("正在准备本地蓝图预览");
        if (current == null) return;
        HologramClientLibrary store = HologramLibraryClient.INSTANCE.privateStore();
        String authorName = mc.thePlayer.getCommandSenderName();
        UUID authorId = mc.thePlayer.getUniqueID();
        String id = replaceId.isEmpty() ? "local_" + UUID.randomUUID() : replaceId;
        NBTTagCompound saveOptions = (NBTTagCompound) options.copy();
        double yaw = ui.preview.yaw(), pitch = ui.preview.pitch();
        work(current, () -> {
            byte[] json = current.draft.prepareSave(saveOptions, authorName, authorId, id);
            NBTTagCompound renderSnapshot = HologramBlueprintPreview.snapshot(json);
            main(() -> {
                if (!current(current)) return;
                HologramBlueprintPreview
                    .capture(renderSnapshot, yaw, pitch, () -> current(current), image -> work(current, () -> {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        if (!ImageIO.write(image, "png", output)) throw new IOException("无法编码蓝图预览 PNG");
                        HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact
                            .prepareSave(json, output.toByteArray());
                        HologramLocalBlueprintDocument saved = HologramLocalBlueprintDocument.open(pair.json, id);
                        HologramClientLibrary.LocalSave staged = store
                            .preparePrivateDocumentSave(pair.json, pair.png, id, () -> current(current));
                        try {
                            if (!current(current)) throw new IOException("蓝图已修改，请重新保存");
                            staged.write.commit();
                        } catch (IOException | RuntimeException failure) {
                            try {
                                staged.write.rollback();
                            } catch (IOException rollback) {
                                failure.addSuppressed(rollback);
                            }
                            throw failure;
                        }
                        main(() -> saved(current, saved, pair.json));
                    }), failure -> failed(current, failure));
            });
        });
    }

    private void saved(Task current, HologramLocalBlueprintDocument saved, byte[] json) {
        if (task != current) return;
        try {
            current.draft.saved(current.revision, saved, json);
            current.revision = current.draft.revision();
            HologramLibraryClient.INSTANCE.select(false, current.draft.fileId());
            HologramLibraryClient.INSTANCE.refreshLocal();
            HologramUiLibrary.committedSave(current.source.session);
            if (screenCurrent(current)) work(current, () -> prepareView(current, current.draft, "蓝图已保存到客户端本地"));
            else task = null;
        } catch (IOException invalid) {
            failed(current, invalid);
        }
    }

    boolean edit(String op, NBTTagCompound request) {
        if (!HologramLocalBlueprintDocument.handles(op) || ui.session.document() == null) return false;
        Task current = begin("");
        if (current == null) return true;
        current.operation = op;
        current.sequence = request.getLong("uiSequence");
        current.attachmentRequest = request.getLong("textRequest");
        NBTTagCompound action = (NBTTagCompound) request.copy();
        try {
            current.structure = current.draft.beginStructureCommand(op, action);
            if (current.structure != null) {
                current.message = "正在读取所选结构的原生声明";
                ui.session.notice(current.message);
                return true;
            }
        } catch (IOException failure) {
            failed(current, failure);
            return true;
        }
        work(current, () -> {
            long previous = current.revision;
            current.draft.apply(op, action, () -> current(current));
            finishEdit(current, previous);
        });
        return true;
    }

    private void finishEdit(Task current, long previous) throws IOException {
        current.revision = current.draft.revision();
        if (current.revision != previous) prepareView(current, current.draft, "");
        else {
            NBTTagCompound metadata = commandResult(current, current.draft.snapshotMetadata(), true, "");
            main(() -> {
                if (task != current) return;
                task = null;
                if (screenCurrent(current))
                    ui.session.publishLocalDocument(current.draft, ui.session.state().withMetadata(metadata));
            });
        }
    }

    void cancelAttachment(long request) {
        Task current = task;
        if (current != null && current.attachmentRequest == request && current(current)) task = null;
    }

    void placeCurrent() {
        if (ui.session.document() == null) return;
        Task current = begin("正在准备蓝图预览");
        if (current == null) return;
        work(current, () -> {
            byte[] json = current.draft.artifact();
            main(() -> {
                if (task != current) return;
                task = null;
                if (screenCurrent(current)) HologramLibraryClient.INSTANCE.previewDraftPlacement(json);
            });
        });
    }

    private void prepareView(Task current, HologramLocalBlueprintDocument document, String notice) throws IOException {
        NBTTagCompound snapshot = commandResult(current, document.snapshot(), true, "");
        main(() -> {
            if (task != current) return;
            if (!current(current) || !screenCurrent(current)) {
                task = null;
                return;
            }
            if (ui.host.minecraft().thePlayer != null)
                snapshot.setInteger("dimension", ui.host.minecraft().thePlayer.dimension);
            try {
                current.destination = document;
                current.preparation = new HologramState.Preparation(snapshot, true);
                current.notice = notice;
            } catch (RuntimeException failure) {
                failed(current, failure);
            }
        });
    }

    void tick() {
        Task current = task;
        if (current == null || current.preparation == null && current.structure == null) return;
        if (!current(current) || !screenCurrent(current)) {
            if (current.structure != null) current.structure.close();
            task = null;
            return;
        }
        if (current.structure != null) {
            advanceStructure(current);
            return;
        }
        try {
            if (!current.preparation.step(512, 2_000_000L)) return;
            task = null;
            HologramState initial = current.preparation.result();
            if (ui.session.document() == current.destination)
                ui.session.publishLocalDocument(current.destination, initial);
            else {
                String fileId = current.destination.fileId();
                if (!fileId.isEmpty()) HologramLibraryClient.INSTANCE.select(false, fileId);
                ui.session.installLocalDocument(current.destination, initial);
            }
            if (!current.notice.isEmpty()) ui.session.notice(current.notice);
        } catch (RuntimeException failure) {
            task = null;
            ui.session.notice(HologramUiText.reason(failure.getMessage()));
        }
    }

    private void advanceStructure(Task current) {
        HologramLocalBlueprintDocument.StructureTask structure = current.structure;
        try {
            if (!structure.step(512, 2_000_000L)) return;
            current.structure = null;
            work(current, () -> {
                try (structure) {
                    long previous = current.revision;
                    structure.commit(() -> current(current));
                    finishEdit(current, previous);
                }
            });
        } catch (IOException | RuntimeException failure) {
            structure.close();
            current.structure = null;
            failed(current, failure);
        }
    }

    private Task begin(String message) {
        if (busy()) return null;
        Task current = new Task(ui.session.state(), ui.host.minecraft().theWorld, ui.session.document());
        current.message = message;
        task = current;
        ui.session.notice(message);
        return current;
    }

    private boolean current(Task candidate) {
        return task == candidate && (candidate.draft == null || candidate.draft.revision() == candidate.revision);
    }

    private boolean screenCurrent(Task candidate) {
        Minecraft mc = ui.host.minecraft();
        HologramState active = ui.session.state();
        return (mc.currentScreen == ui.host.parent()
            || mc.currentScreen instanceof HologramQuantumGroupsScreen quantum && quantum.editorIs(ui.host.parent()))
            && mc.theWorld == candidate.world
            && active.session.equals(candidate.source.session)
            && active.data.getLong("planRevision") == candidate.source.data.getLong("planRevision");
    }

    private void failed(Task current, Throwable failure) {
        main(() -> {
            if (task != current) return;
            task = null;
            if (!screenCurrent(current)) return;
            String reason = HologramUiText.reason(failure.getMessage());
            if (current.draft != null && !current.operation.isEmpty()) ui.session.publishLocalDocument(
                current.draft, ui.session.state().withMetadata(commandResult(current, new NBTTagCompound(), false, reason)));
            else ui.session.notice(reason);
        });
    }

    private static NBTTagCompound commandResult(Task current, NBTTagCompound metadata, boolean accepted, String reason) {
        if (current.operation.isEmpty()) return metadata;
        metadata.setLong("uiSequence", current.sequence);
        if (!accepted) metadata.setString("status", reason);
        if ("placeTextBox".equals(current.operation) && !accepted) {
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setLong("request", current.attachmentRequest);
            receipt.setBoolean("accepted", false);
            receipt.setString("message", reason);
            metadata.setTag("textPlacementAck", receipt);
        }
        if (HologramLocalBlueprintDocument.processingCommand(current.operation)) {
            metadata.setString("processingPresetOperation", current.operation);
            metadata.setBoolean("processingPresetOperationSuccess", accepted);
            if ("configureBlueprintQuantumGroups".equals(current.operation)) metadata.setBoolean("quantumGroupsOperationSuccess", accepted);
        }
        return metadata;
    }

    private void work(Task current, FileWork operation) {
        try {
            IO.execute(() -> {
                try {
                    if (current(current)) operation.run();
                    else failed(current, new IOException("蓝图已修改，请重试"));
                } catch (IOException | RuntimeException failure) {
                    failed(current, failure);
                }
            });
        } catch (RuntimeException failure) {
            failed(current, failure);
        }
    }

    private static void main(Runnable runnable) {
        Minecraft.getMinecraft()
            .func_152344_a(runnable);
    }

    private interface FileSource {

        byte[] read() throws IOException;
    }

    private interface FileWork {

        void run() throws IOException;
    }
}
