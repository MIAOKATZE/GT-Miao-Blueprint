package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.hologram.HologramLocalAuthorSemantics;

/**
 * Client document owner. The disposable UI and renderer are read-only projections of this editor;
 * neither server sessions nor display snapshots can be used to reconstruct its source.
 */
final class HologramLocalAuthorRuntime {

    private static volatile HologramLocalAuthorRuntime active;
    private final AuthorEditor editor;
    private final HologramAuthorProjection projection;
    private HologramUiSession attached;
    private final HologramLocalAuthorAttachmentActions attachmentActions = new HologramLocalAuthorAttachmentActions(
        this);
    private final HologramLocalAuthorStructureActions structureActions = new HologramLocalAuthorStructureActions(this);
    private final HologramProgress.Tracker localProgress = new HologramProgress.Tracker();

    private long savedRevision;
    private boolean saving, retired, hasDraftSaved;
    private String savedPath = "";
    private String activeStructure = "";
    private AuthorDocument structureMetadataDocument;
    private List<JsonObject> structureMetadata = java.util.Collections.emptyList();
    private AuthorEditor.Clipboard clipboard;
    private NBTTagCompound previewContext;
    private NBTTagCompound processing;
    private long processingRevision = -1;
    private boolean processingCompleted;
    private NBTTagList structureCapabilities = new NBTTagList();
    private long capabilitiesRevision = -1;
    private int structureFacing = -1;
    private String capabilitiesStructure = "";

    private long globalNbtRevision = -1;
    private NBTTagCompound globalNbt;

    NBTTagCompound globalNbtDetails() throws IOException {
        if (globalNbt == null || globalNbtRevision != document().revision()) {
            NBTTagCompound details = HologramLocalAuthorSemantics.globalNbtDetails(document());
            globalNbt = details;
            globalNbtRevision = document().revision();
        }
        return (NBTTagCompound) globalNbt.copy();
    }

    private long inspectorRevision = -1, inspectorCell = -1;
    private final LinkedHashMap<Integer, NBTTagCompound> inspectorPages = new LinkedHashMap<Integer, NBTTagCompound>(
        16,
        .75f,
        true) {

        protected boolean removeEldestEntry(Map.Entry<Integer, NBTTagCompound> entry) {
            return size() > 16;
        }
    };

    private HologramLocalAuthorRuntime(AuthorDocument source, boolean recovered) {
        editor = new AuthorEditor(source, 64);
        projection = new HologramAuthorProjection(source);
        savedRevision = recovered ? source.revision() : -1;
        hasDraftSaved = recovered;
        refreshMetadata();
    }

    static HologramLocalAuthorRuntime active() {
        return active;
    }

    static void openWorkspace() {
        if (active != null) {
            Minecraft.getMinecraft()
                .displayGuiScreen(new HologramScreen(active));
            return;
        }
        newWorkspace();
    }

    static void newWorkspace() {
        try {
            JsonObject source = new JsonObject();
            source.addProperty("schema", 2);
            source.addProperty("name", "未命名蓝图");
            source.add("cells", new com.google.gson.JsonArray());
            source.add("structures", new com.google.gson.JsonArray());
            install(AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()), false);
        } catch (IOException impossible) {
            throw new IllegalStateException("Empty local document", impossible);
        }
    }

    /** Candidate parsing has already completed: replacing the current owner cannot lose a failed read. */
    static void install(AuthorDocument source, boolean recovered) {
        String migrationFailure = "";
        if (!source.editable() && !recovered) {
            try {
                JsonObject root = source.sourceCopy();
                JsonObject legacy = root.has("blueprint") ? root.getAsJsonObject("blueprint") : root;
                JsonObject converted = HologramLocalAuthorSemantics.upgradeLegacy(legacy);
                if (root.has("blueprint")) root.add("blueprint", converted);
                else root = converted;
                source = AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults());
            } catch (IOException | RuntimeException failure) {
                migrationFailure = "旧模板转换未完成；原文档只读保留，仍可保存恢复草稿：" + failure.getMessage();
            }
        }
        HologramLocalAuthorRuntime candidate = new HologramLocalAuthorRuntime(source, recovered);
        HologramLocalAuthorRuntime previous = active;
        HologramScreen screen = new HologramScreen(candidate);
        active = candidate;
        try {
            Minecraft.getMinecraft()
                .displayGuiScreen(screen);
        } catch (RuntimeException failure) {
            active = previous;
            candidate.projection.close();
            throw failure;
        }
        if (!migrationFailure.isEmpty() && candidate.attached != null) candidate.attached.notice(migrationFailure);
        if (previous != null) {
            previous.retired = true;
            previous.attached = null;
            previous.attachmentActions.close();
            previous.structureActions.close();
            previous.projection.close();
        }
    }

    AuthorDocument document() {
        return editor.current();
    }

    AuthorEditor editor() {
        return editor;
    }

    Set<Long> selectionIds(int[] indices) {
        return ids(indices);
    }

    JsonObject sourceCell(long id) {
        return document().cell(id)
            .sourceCopy();
    }

    JsonObject sourceJson() {
        JsonObject root = document().sourceCopy();
        return root.has("blueprint") ? root.getAsJsonObject("blueprint") : root;
    }

    void apply(AuthorEditor.Change change) {
        changed(change);
    }

    void commitSource(long expectedRevision, JsonObject barePreset) {
        if (document().revision() != expectedRevision) throw new IllegalStateException("文档已变化，请重新执行处理");
        JsonObject source = document().sourceCopy();
        if (source.has("blueprint")) source.add("blueprint", barePreset);
        else source = barePreset;
        changed(editor.replaceSource(source));
    }

    void commitTransformedSource(long expectedRevision, JsonObject barePreset) {
        if (document().revision() != expectedRevision) throw new IllegalStateException("文档已变化，请重新执行变换");
        JsonObject source = document().sourceCopy();
        if (source.has("blueprint")) source.add("blueprint", barePreset);
        else source = barePreset;
        changed(editor.replaceTransformedSource(expectedRevision, source));
    }

    void commitResolvedSource(long expectedRevision, JsonObject barePreset) {
        if (document().revision() != expectedRevision) throw new IllegalStateException("文档已变化，请重新执行处理");
        JsonObject source = document().sourceCopy();
        if (source.has("blueprint")) source.add("blueprint", barePreset);
        else source = barePreset;
        changed(editor.replaceResolvedSource(expectedRevision, source));
    }

    boolean structureCapabilitiesCurrent() {
        return capabilitiesRevision == document().revision() && capabilitiesStructure.equals(activeStructure);
    }

    void setStructureCapabilities(long revision, String structureId, NBTTagList capabilities) {
        setStructureCapabilities(revision, structureId, capabilities, -1);
    }

    void setStructureCapabilities(long revision, String structureId, NBTTagList capabilities, int facing) {
        if (revision != document().revision() || !activeStructure.equals(structureId)) return;
        capabilitiesRevision = revision;
        capabilitiesStructure = structureId;
        structureCapabilities = (NBTTagList) capabilities.copy();
        structureFacing = facing;
        refreshMetadata();
        publish();
    }

    void setViewMetadata(NBTTagCompound metadata) {
        projection.refreshMetadata(metadata);
        if (attached != null) attached.localProjectionChanged(projection.view());
    }

    void setProcessing(NBTTagCompound report, boolean completed) {
        processing = (NBTTagCompound) report.copy();
        processingRevision = document().revision();
        processingCompleted = completed;
        refreshMetadata();
        publish();
    }

    HologramState view() {
        return projection.view();
    }

    boolean editable() {
        return document().editable() && !attachmentActions.busy() && !structureActions.busy();
    }

    boolean dirty() {
        return savedRevision != document().revision();
    }

    boolean saving() {
        return saving;
    }

    boolean canUndo() {
        return editor.canUndo();
    }

    boolean canRedo() {
        return editor.canRedo();
    }

    boolean processingCurrent() {
        return processing != null && processingRevision == document().revision() && processingCompleted;
    }

    NBTTagCompound confirmedPolicy() {
        if (!processingCurrent()) throw new IllegalStateException("请先完成当前版本的本地处理流程");
        return (NBTTagCompound) processing.getCompoundTag("policy")
            .copy();
    }

    String diagnostic() {
        if (attachmentActions.busy()) return "正在生成文字或图片，可取消后继续编辑";
        if (structureActions.busy()) return "正在读取或重建原生结构声明，原稿保持不变；可取消后继续编辑";
        return document().diagnostic();
    }

    HologramLocalAuthorAttachmentActions attachments() {
        return attachmentActions;
    }

    HologramLocalAuthorStructureActions structures() {
        return structureActions;
    }

    HologramProgress progress() {
        if (structureActions.busy()) return localProgress.update(
            document().documentId(),
            structureActions.phase(),
            structureActions.completed(),
            structureActions.total(),
            "cells",
            false,
            System.nanoTime());
        if (!attachmentActions.busy()) {
            localProgress.clear();
            return null;
        }
        return localProgress.update(
            document().documentId(),
            attachmentActions.phase(),
            attachmentActions.completed(),
            attachmentActions.total(),
            "cells",
            false,
            System.nanoTime());
    }

    void rememberPreview(NBTTagCompound context) {
        previewContext = (NBTTagCompound) context.copy();
    }

    NBTTagCompound previewContext() {
        return previewContext == null ? null : (NBTTagCompound) previewContext.copy();
    }

    void attach(HologramUiSession session) {
        attached = session;
        refreshMetadata();
    }

    void detach(HologramUiSession session) {
        if (attached == session) {
            attachmentActions.cancel();
            structureActions.cancel();
            refreshMetadata();
            attached = null;
        }
    }

    void tick() {
        HologramLocalAuthorMaterialCatalog.tick();
        attachmentActions.tick();
        structureActions.tick();
        if (projection.advance() && attached != null) attached.localProjectionChanged(projection.view());
    }

    private Set<Long> ids(int[] indices) {
        Set<Long> selected = new LinkedHashSet<>();
        for (int index : indices) {
            long id = projection.cellId(index);
            if (document().cell(id) == null) throw new IllegalArgumentException("所选格已变化，请重新选择");
            selected.add(id);
        }
        return selected;
    }

    void select(int[] indices) {
        editor.select(ids(indices));
    }

    void move(int[] indices, int dx, int dy, int dz, boolean copy) {
        select(indices);
        changed(copy ? editor.copy(dx, dy, dz) : editor.move(dx, dy, dz));
    }

    void delete(int[] indices) {
        select(indices);
        changed(editor.delete());
    }

    void copy(int[] indices) {
        select(indices);
        clipboard = editor.clipboard();
        refreshMetadata();
        publish();
    }

    void cut(int[] indices) {
        select(indices);
        clipboard = editor.clipboard();
        changed(editor.cut());
    }

    void paste(int anchorIndex) {
        AuthorDocument.Cell anchor = document().cell(projection.cellId(anchorIndex));
        if (anchor == null || clipboard == null) throw new IllegalArgumentException("请先复制并选择粘贴锚点");
        changed(editor.paste(clipboard, anchor.x(), anchor.y(), anchor.z()));
    }

    void undo() {
        changed(editor.undo());
    }

    void redo() {
        changed(editor.redo());
    }

    void rename(String name) {
        if (!document().title()
            .equals(name)) changed(editor.rename(name));
    }

    NBTTagCompound inspectNbt(int index, int offset, int limit) throws IOException {
        long id = projection.cellId(index);
        AuthorDocument.Cell cell = document().cell(id);
        if (cell == null) return new NBTTagCompound();
        if (inspectorRevision != document().revision() || inspectorCell != id) {
            inspectorPages.clear();
            inspectorRevision = document().revision();
            inspectorCell = id;
        }
        NBTTagCompound first = inspectorPage(index, cell, offset / 64 * 64);
        NBTTagCompound row = (NBTTagCompound) first.copy();
        final long revision = document().revision();
        final int total = first.getInteger("total");
        // The existing widget uses global indices. Pages remain bounded and cached, never network-backed.
        row.setTag("entries", new NBTTagList() {

            public int tagCount() {
                return total;
            }

            public int func_150303_d() {
                return 10;
            }

            public NBTTagCompound getCompoundTagAt(int requested) {
                if (requested < 0 || requested >= total || document().revision() != revision || inspectorCell != id)
                    return new NBTTagCompound();
                try {
                    int base = requested / 64 * 64;
                    NBTTagCompound page = inspectorPage(index, cell, base);
                    return page.getTagList("entries", 10)
                        .getCompoundTagAt(requested - base);
                } catch (IOException failure) {
                    if (attached != null) attached.notice("NBT 读取失败，原始内容已保留：" + failure.getMessage());
                    return new NBTTagCompound();
                }
            }
        });
        return row;
    }

    private NBTTagCompound inspectorPage(int index, AuthorDocument.Cell cell, int offset) throws IOException {
        NBTTagCompound page = inspectorPages.get(offset);
        if (page == null) {
            page = HologramLocalAuthorSemantics.inspect(cell.sourceCopy(), document().headerCopy(), index, offset, 64);
            if (!page.getString("detailError")
                .isEmpty()) throw new IOException(page.getString("detailError"));
            inspectorPages.put(offset, page);
        }
        return page;
    }

    void editNbt(int[] indices, NBTTagList changes) throws IOException {
        Set<Long> selected = ids(indices);
        List<JsonObject> source = new ArrayList<>();
        for (long id : selected) source.add(
            document().cell(id)
                .sourceCopy());
        List<JsonObject> edited = HologramLocalAuthorSemantics.editNbt(source, changes);
        if (edited.size() != selected.size()) throw new IOException("NBT edit source count changed");
        Map<Long, JsonObject> replacements = new LinkedHashMap<>();
        int index = 0;
        for (long id : selected) replacements.put(id, edited.get(index++));
        editor.select(selected);
        changed(editor.replaceCells(replacements));
    }

    void selectExisting(long id) {
        select(new int[] { projection.index(id) });
        refreshMetadata();
        publish();
    }

    void selectStructure(String id) {
        activeStructure = id == null ? "" : id;
        refreshMetadata();
        publish();
    }

    void save(String name) {
        if (saving) return;
        if (document().editable()) rename(name);
        saving = true;
        // Immutable root captured before worker IO; later edits remain dirty after this save completes.
        AuthorDocument captured = document();
        HologramLibraryClient.INSTANCE.saveAuthorDraft(this, captured);
    }

    void saveCopy(String name) {
        if (saving) return;
        AuthorDocument captured = namedCopy(document().forkCopy(), name);
        saving = true;
        HologramLibraryClient.INSTANCE.saveAuthorDraft(this, captured);
    }

    void saveOverwrite(String draftId, String name) {
        if (saving) return;
        if (draftId == null || !draftId.startsWith("draft_"))
            throw new IllegalArgumentException("恢复草稿只能覆盖恢复草稿；成品请使用独立导出");
        if (document().documentId()
            .equals(draftId.substring(6))) {
            save(name);
            return;
        }
        AuthorDocument captured = namedCopy(document().copyWithIdentity(draftId.substring(6)), name);
        saving = true;
        HologramLibraryClient.INSTANCE.saveAuthorDraft(this, captured);
    }

    private static AuthorDocument namedCopy(AuthorDocument copy, String name) {
        if (!copy.editable() || copy.title()
            .equals(name)) return copy;
        AuthorEditor detached = new AuthorEditor(copy, 1);
        detached.rename(name);
        return detached.current();
    }

    void saved(AuthorDocument captured, String path, String error) {
        saving = false;
        if (retired) return; // Disk completion belongs to the captured document, never a replacement owner.
        boolean currentDocument = document().documentId()
            .equals(captured.documentId());
        if (error == null && currentDocument) {
            savedRevision = captured.revision();
            savedPath = path;
            hasDraftSaved = true;
        }
        if (error == null) HologramLibraryClient.INSTANCE.refreshLocal();
        refreshMetadata();
        publish();
        if (attached != null) attached
            .notice(error == null ? (currentDocument ? "本地草稿已保存：" : "恢复副本已保存，当前稿不变：") + path : "本地草稿保存失败：" + error);
    }

    private void changed(AuthorEditor.Change change) {
        projection.update(change);
        refreshMetadata();
        publish();
    }

    private void refreshMetadata() {
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setBoolean("localAuthor", true);
        metadata.setString("authorDiagnostic", document().diagnostic());
        metadata.setBoolean("blueprintDirty", dirty());
        metadata.setBoolean("editCanUndo", editor.canUndo());
        metadata.setBoolean("editCanRedo", editor.canRedo());
        metadata.setBoolean("editCanPaste", clipboard != null);
        metadata.setLong("planRevision", document().revision());
        metadata.setString("blueprintName", document().title());
        metadata.setString("presetName", document().title());
        if (structureMetadataDocument == null || !document().sameStructures(structureMetadataDocument)) {
            structureMetadata = document().structures();
            structureMetadataDocument = document();
        }
        JsonObject selectedStructure = null;
        for (JsonObject group : structureMetadata) if (group.has("id") && activeStructure.equals(
            group.get("id")
                .getAsString()))
            selectedStructure = group;
        metadata.setString("activeStructure", activeStructure);
        NBTTagCompound channels = new NBTTagCompound();
        try {
            metadata.setInteger(
                "main",
                selectedStructure != null && selectedStructure.has("main") ? selectedStructure.get("main")
                    .getAsInt() : 1);
            metadata.setBoolean(
                "mainExplicit",
                selectedStructure != null && selectedStructure.has("mainExplicit")
                    && selectedStructure.get("mainExplicit")
                        .getAsBoolean());
            metadata.setBoolean(
                "noHatches",
                selectedStructure != null && selectedStructure.has("noHatches")
                    && selectedStructure.get("noHatches")
                        .getAsBoolean());
            if (selectedStructure != null && selectedStructure.has("channels")
                && !selectedStructure.get("channels")
                    .isJsonNull())
                for (Map.Entry<String, com.google.gson.JsonElement> entry : selectedStructure
                    .getAsJsonObject("channels")
                    .entrySet())
                    channels.setInteger(
                        entry.getKey(),
                        entry.getValue()
                            .getAsInt());
        } catch (RuntimeException unsupportedMetadata) {
            metadata.setInteger("main", -1);
            channels = new NBTTagCompound();
            metadata.setString("authorDiagnostic", "结构参数无法投影；原始参数保留，原生检查前不可应用结构设置");
        }
        metadata.setTag("channels", channels);
        metadata.setInteger("facing", structureCapabilitiesCurrent() ? structureFacing : -1);
        metadata.setTag(
            "capabilities",
            capabilitiesRevision == document().revision() && capabilitiesStructure.equals(activeStructure)
                ? structureCapabilities.copy()
                : new NBTTagList());
        metadata.setString("localDraftPath", savedPath);
        metadata.setBoolean("blueprintSaved", hasDraftSaved);
        metadata.setString("libraryLocalId", hasDraftSaved ? "draft_" + document().documentId() : "");
        metadata.setString("selectedPresetId", hasDraftSaved ? "draft_" + document().documentId() : "");
        boolean reportCurrent = processing != null && processingRevision == document().revision();
        metadata.setBoolean("blueprintProcessingRequired", true);
        metadata.setBoolean("blueprintProcessingComplete", reportCurrent && processingCompleted);
        metadata.setTag("processing", reportCurrent ? processing.copy() : new NBTTagCompound());
        metadata.setTag(
            "processingPolicy",
            reportCurrent ? processing.getCompoundTag("policy")
                .copy() : new NBTTagCompound());
        metadata.setTag(
            "processingPresets",
            reportCurrent ? processing.getTagList("processingPresets", 10)
                .copy() : new NBTTagList());

        int[] selected = editor.selection()
            .stream()
            .mapToInt(projection::index)
            .filter(i -> i >= 0)
            .toArray();
        metadata.setIntArray("selectedIndices", selected);
        metadata.setInteger("selected", selected.length == 0 ? -1 : selected[0]);
        projection.refreshMetadata(metadata);
    }

    private void publish() {
        if (attached != null) attached.localDocumentChanged(projection.view());
    }
}
