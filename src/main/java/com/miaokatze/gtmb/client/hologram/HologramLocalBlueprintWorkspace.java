// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.end;
import static com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.mark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.client.hologram.HologramClientLibrary.EditorSource;
import com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.Stage;
import com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.Terminal;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintFiles;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.EditorRows;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.FrozenDraft;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.HistoryDelta;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.LocalEditorOrigin;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.LocalSaveIntent;
import com.miaokatze.gtmb.hologram.HologramNativeBlueprintStorage;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;

/** File I/O and viewport preparation for the ordinary in-client authoring workspace. */
final class HologramLocalBlueprintWorkspace {

    private static final boolean PACKED_AUTHORING = Boolean.getBoolean("gtmb.localBlueprint.packedAuthoring");
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

    /** Captured on the client thread; the file worker never queries current Minecraft ownership. */
    static final class PresentationOwner {

        private final Object world, connection;
        private final UUID actor;
        private final String session;
        private final long planRevision;

        PresentationOwner(Object world, Object connection, UUID actor, String session, long planRevision) {
            this.world = world;
            this.connection = connection;
            this.actor = actor;
            this.session = session;
            this.planRevision = planRevision;
        }

        boolean available() {
            return world != null && connection != null && actor != null;
        }

        boolean sameClient(Object world, Object connection, UUID actor) {
            return available() && this.world == world && this.connection == connection && this.actor.equals(actor);
        }

        boolean sameSource(Object world, Object connection, UUID actor, String session, long planRevision) {
            return sameClient(world, connection, actor) && this.session.equals(session)
                && this.planRevision == planRevision;
        }
    }

    private static final class Task {

        final HologramState source;
        final PresentationOwner owner;
        final HologramLocalBlueprintDocument draft;
        final HologramPrivateEditPerformance.Request privateEdit;
        final boolean viewOnly;
        HologramPrivateEditPerformance.Bound privateEditPreparation;
        volatile long revision;
        HologramState.Preparation preparation;
        HologramState openingPreview;
        HologramOpeningPreview openingCamera;
        HologramState preparedView;
        boolean opening, packedEditor;
        HologramLocalBlueprintDocument resumeDocument;
        long resumeRevision, advancedInFrame;
        Object resumePlayer;
        int resumeDimension;
        boolean resumeRetried;
        HologramLocalBlueprintDocument.StructureTask structure;
        String message;
        HologramLocalBlueprintDocument destination;
        String notice = "";
        String operation = "";
        long sequence, attachmentRequest;
        HologramLocalSaveTrace saveTrace;
        HologramLocalHistoryTrace historyTrace;

        Task(HologramState source, PresentationOwner owner, HologramLocalBlueprintDocument draft,
            HologramPrivateEditPerformance.Request privateEdit, boolean viewOnly) {
            this.source = source;
            this.owner = owner;
            this.draft = draft;
            this.privateEdit = privateEdit;
            this.viewOnly = viewOnly;
            revision = draft == null ? -1 : draft.revision();
        }
    }

    private final HologramUiModules ui;
    private volatile Task task;
    private HistoryWatch historyWatch; // client thread only; moved into the exact Task before IO submission

    private static final class HistoryWatch {

        final HologramLocalHistoryTrace trace;
        final HologramState source;
        final HologramLocalBlueprintDocument draft;

        HistoryWatch(HologramLocalHistoryTrace trace, HologramState source, HologramLocalBlueprintDocument draft) {
            this.trace = trace;
            this.source = source;
            this.draft = draft;
        }
    }

    HologramLocalBlueprintWorkspace(HologramUiModules ui) {
        this.ui = ui;
    }

    boolean busy() {
        return task != null;
    }

    boolean opening() {
        Task current = task;
        return current != null && current.opening && current(current) && screenCurrent(current);
    }

    HologramState openingPreview() {
        Task current = task;
        if (current == null || !current.packedEditor || !current(current) || !screenCurrent(current)) return null;
        return current.openingPreview;
    }

    HologramOpeningPreview openingCamera() {
        Task current = task;
        return current != null && current.opening && current(current) && screenCurrent(current)
            ? current.openingCamera : null;
    }

    private void beginOpening(Task current) {
        ui.preview.releasePointerCapture();
        current.opening = true;
    }

    private void offerOpeningPrefix(Task current, HologramState prefix) {
        current.openingPreview = prefix;
        if (current.opening && prefix != null)
            current.openingCamera = new HologramOpeningPreview(prefix, HologramUiPreview.frontCameraYaw(prefix));
    }

    private void retireOpeningPrefix(Task current) {
        if (current.openingCamera == null) return;
        current.openingCamera.invalidate();
        HologramRenderer.retireOpeningPrefix(current.openingCamera.state);
        current.openingCamera = null;
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
        openPreset(id, true);
    }

    void reloadPreset(String id) {
        openPreset(id, false);
    }

    private void openPreset(String id, boolean explicitEdit) {
        if (!catalogAvailable()) return;
        HologramClientLibrary store = HologramLibraryClient.INSTANCE.privateStore();
        if (id.startsWith("legacy:")) {
            open(() -> new EditorSource(store.readLegacyDocument(id), LocalEditorOrigin.STRICT), "", false);
            return;
        }
        String localId = id.startsWith("local_") ? id : "local_" + id;
        open(() -> store.readPrivateEditorSource(localId), localId, explicitEdit);
    }

    void openCapture(HologramLocalBlueprintDocument document, String notice) {
        Task current = beginView("正在准备本地蓝图", null);
        if (current != null) work(current, () -> prepareView(current, document, notice));
    }

    void resumeCapture(HologramLocalBlueprintDocument document, HologramLocalPresentation remembered) {
        resumeCapture(document, remembered, false);
    }

    private void resumeCapture(HologramLocalBlueprintDocument document, HologramLocalPresentation remembered,
        boolean retried) {
        Task task = beginView("正在准备本地蓝图", null);
        if (task == null) return;
        Minecraft client = ui.host.minecraft();
        beginOpening(task);
        task.resumeDocument = document;
        task.resumeRevision = document.revision();
        task.resumeRetried = retried;
        task.resumePlayer = client.thePlayer;
        task.resumeDimension = client.thePlayer.dimension;
        if (document.nativeDraft()!=null || remembered == null || !resumeOwner(document, remembered)) {
            work(task, () -> prepareView(task, document, ""));
            return;
        }
        work(task, () -> {
            HologramState rebased = null;
            if (remembered.sameRevision()) {
                NBTTagCompound metadata = document.resumePresentationMetadata(() -> current(task));
                rebased = remembered.rebase(metadata);
            }
            HologramState ready = rebased;
            main(() -> {
                if (!current(task) || !screenCurrent(task)) {
                    retireStaleView(task);
                    return;
                }
                if (ready == null || !resumeOwner(document, remembered)) {
                    work(task, () -> prepareView(task, document, ""));
                    return;
                }
                task.destination = document;
                task.preparedView = ready;
                publishView(task);
            });
        });
    }

    /** Final root disposal ends presentation work, not an authored in-flight Save Current. */
    void retireView() {
        Task current = task;
        if (current != null && current.viewOnly) {
            task = null;
            retireOpeningPrefix(current);
        }
    }

    private void retireStaleView(Task stale) {
        if (task != stale) return;
        task = null;
        retireOpeningPrefix(stale);
        // A save that committed on the old window may advance the retained document while this window opens.
        // Retry once from the same live draft and new revision, never an old row or an old prepared view.
        if (stale.resumeDocument != null && !stale.resumeRetried
            && screenCurrent(stale)
            && stale.resumeDocument.revision() != stale.resumeRevision) resumeCapture(stale.resumeDocument, null, true);
    }

    private boolean resumeOwner(HologramLocalBlueprintDocument document, HologramLocalPresentation remembered) {
        Minecraft mc = ui.host.minecraft();
        return mc.thePlayer != null && remembered.current(
            document,
            mc.theWorld,
            mc.getNetHandler(),
            mc.thePlayer,
            mc.thePlayer.dimension,
            HologramTextures.resourceGeneration());
    }

    void openPublic(String id) {
        Task current = beginView("正在读取服务器蓝图", null);
        if (current == null) return;
        beginOpening(current);
        HologramLibraryClient.INSTANCE.openPublic(
            id,
            source -> work(current, () -> prepareOpen(current, source,
                source.nativePair() == null ? "" : source.nativePair().logicalId, "已打开服务器蓝图的本地副本")),
            reason -> failed(current, new IOException(reason)));
    }

    void importNamed(String name) {
        if (!catalogAvailable()) return;
        HologramClientLibrary store = HologramLibraryClient.INSTANCE.privateStore();
        open(() -> store.readNamedEditorSource(shareName(name)), "", false);
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
                HologramBlueprintFiles
                    .exportHistoricShare(directory, alias, store.readLegacyDocument(id), () -> current(current));
                main(() -> exported(current));
                return;
            }
            String localId = id.startsWith("local_") ? id : "local_" + id;
            Path source = store.savedPath(localId);
            if (!java.nio.file.Files.isRegularFile(HologramBlueprintArtifact.previewPath(source))) {
                HologramBlueprintFiles
                    .exportHistoricShare(directory, alias, store.readPrivateDocument(localId), () -> current(current));
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
        if (!clientCurrent(current)) return;
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
                if (!clientCurrent(current)) return;
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

    private void open(FileSource source, String localId, boolean explicitPrivateEdit) {
        Task current = beginView("正在读取客户端蓝图", explicitPrivateEdit ? localId : null);
        if (current == null) return;
        beginOpening(current);
        work(current, () -> prepareOpen(current, source.read(), localId, "已打开客户端蓝图"));
    }

    private void prepareOpen(Task current, EditorSource source, String localId, String notice) throws IOException {
        // A file read already in progress may finish after disposal. Do not then start decoding it.
        if (retiredView(current)) return;
        if(source.nativePair()!=null){
            var document=openNativeEditorSource(source,localId);
            if(retiredView(current)){document.closeNative();return;}
            boolean owned=false;
            try{prepareView(current,document,notice);owned=true;}finally{if(!owned)document.closeNative();}
            return;
        }
        var opened = HologramLocalBlueprintDocument.openStoredForEditor(
            source.json(),
            localId,
            () -> current(current),
            editor -> stageView(current, null, editor, editor.metadata(), notice),
            PACKED_AUTHORING,
            source.origin());
        if (opened.editor() == null) prepareView(current, opened.document(), notice);
        else main(() -> {
            if (!current(current) || !screenCurrent(current)) return;
            current.destination = opened.document();
            publishView(current);
        });
    }

    static HologramLocalBlueprintDocument openNativeEditorSource(EditorSource source,String id)throws IOException{
        return HologramLocalBlueprintDocument.openNative(source.openNative(),id,source.origin(),source.nativePair());
    }

    /** The catalog already contains a typed native generation. No active legacy alias is migrated here. */
    private void saveNative(Task task,HologramClientLibrary store,NBTTagCompound options,String id,LocalSaveIntent intent,
        String author,UUID owner,double yaw,double pitch){
        work(task,()->{
            var expected=task.draft.nativeReceipt();
            var immutable=task.draft.nativeSaveRevision(options,author,owner,id,intent);
            HologramState view=task.source;
            if(view.nativeViewport==null||view.nativeViewport.draft!=task.draft.nativeDraft())
                throw new IOException("原生蓝图版本已更新，请重试保存");
            main(()->{
                if(!current(task)||!clientCurrent(task)){failed(task,new IOException("保存已取消"));return;}
                HologramBlueprintPreview.captureNative(view,yaw,pitch,()->current(task)&&clientCurrent(task),image->work(task,()->{
                    ByteArrayOutputStream png=new ByteArrayOutputStream();
                    if(!ImageIO.write(image,"png",png))throw new IOException("无法编码蓝图预览 PNG");
                    var published=saveNativePair(store,immutable,png.toByteArray(),id,intent,expected,()->current(task)&&clientCurrent(task));
                    main(()->{
                        if(!current(task)||!clientCurrent(task)){failed(task,new IOException("保存所有者已经变化"));return;}
                        try{task.draft.nativeSaved(task.revision,immutable,id,published);task.revision=task.draft.revision();
                            HologramLibraryClient.INSTANCE.select(false,id);HologramLibraryClient.INSTANCE.refreshLocal();
                            HologramUiLibrary.committedSave(task.source.session);
                            work(task,()->prepareOpen(task,new EditorSource(null,LocalEditorOrigin.TRACKED_PRIVATE,published),id,"原生蓝图已保存到客户端本地"));
                        }catch(IOException invalid){failed(task,invalid);}
                    });
                }),failure->failed(task,failure));
            });
        });
    }

    static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt saveNativePair(
        HologramClientLibrary store,com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintEdits immutable,
        byte[] png,String id,LocalSaveIntent intent,com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt expected,
        java.util.function.BooleanSupplier owner)throws IOException{
        if(!owner.getAsBoolean())throw new IOException("保存已取消");
        boolean current=intent==LocalSaveIntent.SAVE_CURRENT;
        if(current&&(expected==null||!id.equals(expected.logicalId)))throw new IOException("缺少打开时的蓝图版本；请重新打开");
        if(!current&&intent!=LocalSaveIntent.SAVE_AS_NEW)throw new IOException("原生蓝图保存必须指定当前版本或另存");
        Path temp=java.nio.file.Files.createTempFile("gtmb-frozen-native-",".gtnb");
        try{
            immutable.write(temp);
            try(var frozen=com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition.open(temp)){
                var prepared=store.prepareEditorSave(frozen,png,id,owner,current?expected:null,!current);
                return com.miaokatze.gtmb.hologram.HologramNativeBlueprintStorage.activate(store.directory(),prepared,frozen.revision(),owner);
            }
        }finally{java.nio.file.Files.deleteIfExists(temp);}
    }
    static boolean nativeNewSave(HologramLocalBlueprintDocument draft, LocalSaveIntent intent, String replaceId) {
        // Both normal first Save (including Ctrl+S) and Save As write native to a fresh literal slot.
        return replaceId.isEmpty() && (intent == LocalSaveIntent.SAVE_AS_NEW || intent == LocalSaveIntent.SAVE_CURRENT)
            && draft.nativeFirstSaveEligible();
    }

    static final class NewNativeCapture implements AutoCloseable {
        private final byte[] bytes;
        final String id, revision;
        final HologramState view;
        private final NativeBlueprintDefinition definition;
        private final Path temporary;

        private NewNativeCapture(String id, byte[] bytes, NativeBlueprintDefinition definition, Path temporary,
            HologramState view) {
            this.id = id;
            this.bytes = bytes;
            this.revision = definition.revision();
            this.definition = definition;
            this.temporary = temporary;
            this.view = view;
        }

        @Override
        public void close() throws IOException {
            try { definition.close(); }
            finally { Files.deleteIfExists(temporary); }
        }
    }

    static NewNativeCapture prepareNewNativeCapture(HologramLocalBlueprintDocument draft, NBTTagCompound options,
        String author, UUID actor, String id, java.util.function.BooleanSupplier current) throws IOException {
        if (!draft.unsavedLocalCapture()) throw new IOException("现有文件请走明确的原生蓝图迁移");
        var frozen = draft.prepareSaveDraft(options, author, actor, id, LocalSaveIntent.SAVE_AS_NEW);
        byte[] bytes = frozen.nativeArtifact(id, current).bytes;
        Path temporary = Files.createTempFile("gtmb-authored-capture-", ".gtnb");
        NativeBlueprintDefinition definition = null;
        try {
            Files.write(temporary, bytes);
            definition = NativeBlueprintDefinition.open(temporary);
            var view = HologramLibraryClient.nativeDisplayModel(definition, id, null, current);
            if (!current.getAsBoolean()) throw new IOException("保存已取消");
            return new NewNativeCapture(id, bytes, definition, temporary, view);
        } catch (IOException | RuntimeException failure) {
            try {
                if (definition != null) definition.close();
            } finally {
                Files.deleteIfExists(temporary);
            }
            throw failure;
        }
    }

    static NativeBlueprintPairStore.Receipt publishNewNativeCapture(HologramClientLibrary store,
        NewNativeCapture capture, String id, byte[] png, java.util.function.BooleanSupplier current)
        throws IOException {
        if (!capture.id.equals(id)) throw new IOException("本地蓝图保存目标已变化");
        var staged = HologramNativeBlueprintStorage.prepareAuthoredSave(store.directory(), id, capture.bytes,
            capture.revision, png, current);
        return HologramNativeBlueprintStorage.activate(store.directory(), staged, capture.revision, current);
    }

    private void saveNewNative(Task task, HologramClientLibrary store, NBTTagCompound options, String id,
        String author, UUID actor, double yaw, double pitch) {
        var owner = (java.util.function.BooleanSupplier) () -> current(task) && clientCurrent(task);
        work(task, () -> {
            var capture = prepareNewNativeCapture(task.draft, options, author, actor, id, owner);
            try {
                main(() -> {
                    if (!owner.getAsBoolean()) {
                        finishNewCapture(task, capture, new IOException("保存已取消"));
                        return;
                    }
                    try {
                        HologramBlueprintPreview.captureNative(capture.view, yaw, pitch, owner, image -> {
                            if (!finishNewCapture(task, capture, null)) return;
                            work(task, () -> {
                                ByteArrayOutputStream png = new ByteArrayOutputStream();
                                if (!ImageIO.write(image,"png",png)) throw new IOException("无法编码蓝图预览 PNG");
                                var receipt = publishNewNativeCapture(store, capture, id, png.toByteArray(), owner);
                                main(() -> {
                                    if (!owner.getAsBoolean()) return;
                                    HologramLibraryClient.INSTANCE.select(false,id);
                                    HologramLibraryClient.INSTANCE.refreshLocal();
                                    HologramUiLibrary.committedSave(task.source.session);
                                    work(task, () -> prepareOpen(task, new EditorSource(null, LocalEditorOrigin.TRACKED_PRIVATE,
                                        receipt), id, "原生蓝图已保存到客户端本地"));
                                });
                            });
                        }, failure -> finishNewCapture(task, capture, failure));
                    } catch (RuntimeException failure) {
                        finishNewCapture(task, capture, failure);
                    }
                });
            } catch (RuntimeException failure) {
                finishNewCapture(task, capture, failure);
            }
        });
    }

    private boolean finishNewCapture(Task task, NewNativeCapture capture, Throwable failure) {
        try { capture.close(); }
        catch (IOException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) {
            failed(task, failure);
            return false;
        }
        return true;
    }

    void save(NBTTagCompound options, String replaceId, LocalSaveIntent intent) {
        Minecraft mc = ui.host.minecraft();
        if (!catalogAvailable() || ui.session.document() == null || mc.thePlayer == null) return;
        Task current = begin("正在准备本地蓝图预览");
        if (current == null) return;
        var trace = HologramLocalSaveTrace.begin(options.getBoolean("saveCurrent") && !replaceId.isEmpty());
        current.saveTrace = trace;
        HologramClientLibrary store = HologramLibraryClient.INSTANCE.privateStore();
        String authorName = mc.thePlayer.getCommandSenderName();
        UUID authorId = mc.thePlayer.getUniqueID();
        String id = replaceId.isEmpty() ? "local_" + UUID.randomUUID() : replaceId;
        NBTTagCompound saveOptions = (NBTTagCompound) options.copy();
        double yaw = ui.preview.yaw(), pitch = ui.preview.pitch();
        if(current.draft.nativeDraft()!=null){
            saveNative(current,store,saveOptions,id,intent,authorName,authorId,yaw,pitch);return;
        }
        if (nativeNewSave(current.draft, intent, replaceId)) {
            saveNewNative(current,store,saveOptions,id,authorName,authorId,yaw,pitch);return;
        }
        tracedWork(current, Stage.DRAFT_IO_QUEUED, Stage.JSON_FREEZE_BEGIN, () -> {
            var prepared = current.draft.prepareSaveDraft(saveOptions, authorName, authorId, id, intent);
            byte[] json = prepared.artifact();
            mark(trace, Stage.JSON_READY);
            NBTTagCompound renderSnapshot = HologramBlueprintPreview.snapshot(prepared, () -> current(current));
            mark(trace, Stage.CAPTURE_INPUT_READY);
            main(() -> {
                mark(trace, Stage.CAPTURE_CLIENT_BEGIN);
                if (!current(current)) {
                    end(trace, Terminal.CANCELLED_OWNER);
                    return;
                }
                if (!clientCurrent(current)) {
                    end(trace, Terminal.CANCELLED_OWNER);
                    task = null;
                    return;
                }
                HologramBlueprintPreview.captureOwned(
                    renderSnapshot,
                    json,
                    yaw,
                    pitch,
                    trace,
                    // captureOwned checks this on RenderTick END, before actual submission/readback.
                    () -> {
                        boolean owned = current(current) && clientCurrent(current);
                        if (!owned) end(trace, Terminal.CANCELLED_OWNER);
                        return owned;
                    },
                    image -> tracedWork(current, Stage.PNG_IO_QUEUED, Stage.PNG_ENCODE_BEGIN, () -> {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        if (!ImageIO.write(image, "png", output)) throw new IOException("无法编码蓝图预览 PNG");
                        mark(trace, Stage.PNG_READY);
                        HologramClientLibrary.EditorSave pending = store
                            .prepareEditorSave(prepared, output.toByteArray(), id, () -> current(current), trace);
                        HologramClientLibrary.LocalSave staged = pending.receipt();
                        try {
                            if (!current(current)) throw new IOException("蓝图已修改，请重新保存");
                            mark(trace, Stage.PAIR_COMMIT_BEGIN);
                            staged.write.commit();
                            mark(trace, Stage.PAIR_COMMIT_DONE);
                        } catch (IOException | RuntimeException failure) {
                            try {
                                staged.write.rollback();
                            } catch (IOException rollback) {
                                failure.addSuppressed(rollback);
                            }
                            throw failure;
                        }
                        main(() -> saved(current, prepared, pending.exactJson()));
                    }),
                    failure -> failed(current, failure));
            });
        });
    }

    private void saved(Task current, FrozenDraft saved, byte[] json) {
        mark(current.saveTrace, Stage.ACK_CLIENT_BEGIN);
        if (task != current) {
            end(current.saveTrace, Terminal.CANCELLED_OWNER);
            return;
        }
        if (!clientCurrent(current)) {
            end(current.saveTrace, Terminal.CANCELLED_OWNER);
            task = null;
            return;
        }
        try {
            current.draft.saved(current.revision, saved, json);
            current.revision = current.draft.revision();
            HologramLibraryClient.INSTANCE.select(false, current.draft.fileId());
            HologramLibraryClient.INSTANCE.refreshLocal();
            HologramUiLibrary.committedSave(current.source.session);
            finishEdit(current, current.revision);
            if (screenCurrent(current)) ui.session.notice("蓝图已保存到客户端本地");
        } catch (IOException invalid) {
            failed(current, invalid);
        }
    }

    HologramLocalHistoryTrace watchHistory(String op, HologramState source, HologramLocalBlueprintDocument draft,
        long nextSequence, long pressNanos) {
        if (!HologramLocalHistoryTrace.isHistory(op) || historyWatch != null
            || busy()
            || draft == null
            || ui.session.document() != draft
            || draft.packedAuthoring()
            || ui.session.state() != source
            || ui.host.minecraft().currentScreen != ui.host.parent()
            || nextSequence != ui.session.sequence() + 1
            || draft.revision() != ui.session.revision()) return null;
        var trace = new HologramLocalHistoryTrace(op, draft.revision(), nextSequence, pressNanos, System::nanoTime);
        historyWatch = new HistoryWatch(trace, source, draft);
        return trace;
    }

    void stopWatchingHistory(HologramLocalHistoryTrace trace) {
        if (historyWatch == null || historyWatch.trace != trace) return;
        historyWatch = null;
        HologramLocalHistoryTrace.end(trace, HologramLocalHistoryTrace.Terminal.NOT_ADMITTED);
    }

    private void admitHistoryWatch(Task current, NBTTagCompound request) {
        var watch = historyWatch;
        if (watch == null || !HologramLocalHistoryTrace.isHistory(current.operation)) return;
        historyWatch = null;
        if (watch.trace.admit(
            current.operation,
            request.getLong("planRevision"),
            current.sequence,
            watch.source,
            current.source,
            watch.draft,
            current.draft,
            current.revision == watch.trace.sourceRevision && request.hasKey("planRevision", 4)
                && screenCurrent(current))) {
            current.historyTrace = watch.trace;
        } else HologramLocalHistoryTrace.end(watch.trace, HologramLocalHistoryTrace.Terminal.NOT_ADMITTED);
    }

    boolean edit(String op, NBTTagCompound request) {
        if (!HologramLocalBlueprintDocument.handles(op) || ui.session.document() == null) return false;
        Task current = begin("");
        if (current == null) return true;
        current.operation = op;
        current.sequence = request.getLong("uiSequence");
        current.attachmentRequest = request.getLong("textRequest");
        admitHistoryWatch(current, request);
        NBTTagCompound action = (NBTTagCompound) request.copy();
        if (!current.draft.packedAuthoring() || current.draft.supportsPackedCommand(op)) runEdit(current, op, action);
        else afterMaterialization(current, () -> runEdit(current, op, action));
        return true;
    }

    private void runEdit(Task current, String op, NBTTagCompound action) {
        try {
            current.structure = current.draft.beginStructureCommand(op, action);
            if (current.structure != null) {
                current.message = "正在读取所选结构的原生声明";
                ui.session.notice(current.message);
                return;
            }
        } catch (IOException failure) {
            failed(current, failure);
            return;
        }
        var history = current.historyTrace;
        HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.IO_ENQUEUED);
        work(current, () -> {
            HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.IO_STARTED);
            long previous = current.revision;
            HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.RESTORE_BEGIN);
            current.draft.apply(op, action, () -> current(current));
            HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.RESTORE_END);
            finishEdit(current, previous);
        });
    }

    /** Only this owner may install a converted initial version and resume the old command. */
    private void afterMaterialization(Task current, Runnable resume) {
        if (!current.draft.packedAuthoring()) {
            resume.run();
            return;
        }
        current.message = "正在准备蓝图编辑数据";
        ui.session.notice(current.message);
        var trace = current.saveTrace;
        tracedWork(current, Stage.MATERIALIZE_IO_QUEUED, Stage.MATERIALIZE_IO_BEGIN, () -> {
            var converted = current.draft.preparePackedMaterialization(current.revision, () -> current(current));
            mark(trace, Stage.MATERIALIZE_IO_DONE);
            main(() -> {
                mark(trace, Stage.MATERIALIZE_CLIENT_BEGIN);
                if (!current(current)) {
                    end(trace, Terminal.CANCELLED_OWNER);
                    return;
                }
                if (!materializationOwner(current)) {
                    end(trace, Terminal.CANCELLED_OWNER);
                    task = null;
                    return;
                }
                try {
                    current.draft
                        .acceptPackedMaterialization(converted, current.revision, () -> materializationOwner(current));
                    mark(trace, Stage.MATERIALIZE_CLIENT_DONE);
                    resume.run();
                } catch (IOException | RuntimeException failure) {
                    failed(current, failure);
                }
            });
        });
    }

    private boolean materializationOwner(Task current) {
        return current(current) && screenCurrent(current) && ui.session.document() == current.draft;
    }

    private void finishEdit(Task current, long previous) throws IOException {
        current.revision = current.draft.revision();
        if(current.draft.nativeDraft()!=null){prepareView(current,current.draft,"");return;}
        boolean changed = current.revision != previous;
        HistoryDelta history = changed && HologramLocalHistoryTrace.isHistory(current.operation)
            ? current.draft.historyDelta(previous)
            : null;
        if (history != null) {
            publishCertifiedHistory(current, history);
            return;
        }
        NBTTagCompound annotation = changed ? current.draft.annotationDelta() : null;
        boolean packedHeaderHistory = current.draft.packedAuthoring()
            && ("editUndo".equals(current.operation) || "editRedo".equals(current.operation));
        if (changed && annotation == null
            && !packedHeaderHistory
            && !HologramLocalBlueprintDocument.metadataOnlyEdit(current.operation))
            prepareView(current, current.draft, "");
        else {
            HologramLocalHistoryTrace.end(current.historyTrace, HologramLocalHistoryTrace.Terminal.NOT_FULL_REBUILD);
            NBTTagCompound metadata = commandResult(current, current.draft.snapshotMetadata(), true, "");
            main(() -> {
                if (task != current) {
                    end(current.saveTrace, Terminal.CANCELLED_OWNER);
                    return;
                }
                if (!screenCurrent(current)) {
                    end(current.saveTrace, Terminal.CANCELLED_OWNER);
                    task = null;
                    return;
                }
                HologramState base = ui.session.state();
                HologramState next;
                try {
                    next = annotation == null ? base.withMetadata(metadata)
                        : applyAnnotation(base, metadata, annotation);
                } catch (IllegalArgumentException invalidBaseline) {
                    work(current, () -> prepareView(current, current.draft, ""));
                    return;
                }
                task = null;
                ui.session.publishLocalDocument(current.draft, next);
                var trace = current.saveTrace;
                if (trace != null) {
                    if (ui.session.document() == current.draft && ui.session.state() == next) {
                        mark(trace, Stage.ACK_PUBLISHED);
                        end(trace, Terminal.PUBLISHED);
                    } else end(trace, Terminal.CANCELLED_OWNER);
                }
            });
        }
    }

    private void publishCertifiedHistory(Task current, HistoryDelta delta) {
        var metadata = commandResult(current, current.draft.snapshotMetadata(), true, "");
        var trace = current.historyTrace;
        long enqueued = trace == null ? 0 : trace.now();
        main(() -> {
            long started = trace == null ? 0 : trace.now();
            if (!current(current) || !screenCurrent(current)) {
                HologramLocalHistoryTrace.end(trace, HologramLocalHistoryTrace.Terminal.CANCELLED_OWNER);
                if (task == current) task = null;
                return;
            }
            var base = ui.session.state();
            var frame = HologramRenderer.editorMaterialPresentation();
            boolean exact = ui.host.minecraft().currentScreen == ui.host.parent() && base == current.source
                && ui.session.document() == current.draft
                && hasHistoryCertificate(current.draft.historyDelta(delta.fromRevision()), delta)
                && ui.session.sequence() == current.sequence
                && metadata.getLong("uiSequence") == current.sequence
                && frame != null
                && frame.state() == base
                && frame.complete()
                && frame.bodyMaterial()
                && frame.submittedCells() == delta.cellCount()
                && HologramRenderer.previewDisplayState() == base
                && HologramRenderer.previewMeshReady(base)
                && !ui.shell.hasModalPanel()
                && ui.shell.scene() instanceof HologramEditorSceneWidget view
                && view.editFrameReady();
            long patchBegin = trace == null ? 0 : trace.now();
            HologramState next = null;
            if (exact) try {
                next = certifiedHistoryView(base, metadata, delta);
            } catch (IllegalArgumentException invalidBaseline) {
                // The authoritative document is already restored. Never replay Undo/Redo to recover its view.
            }
            if (next == null) {
                work(current, () -> prepareView(current, current.draft, ""));
                return;
            }
            if (trace != null)
                trace.certified(delta.header(), delta.cellCount(), enqueued, started, patchBegin, trace.now());
            task = null;
            publishOwnedLocalView(current, current.draft, next);
        });
    }

    static boolean hasHistoryCertificate(HistoryDelta owned, HistoryDelta candidate) {
        return owned != null && owned == candidate;
    }

    static HologramState certifiedHistoryView(HologramState base, NBTTagCompound metadata, HistoryDelta delta) {
        if (base == null || delta == null
            || !base.data.getBoolean("localBlueprintDocument")
            || !base.data.getBoolean("blueprintEditing")
            || !base.data.getBoolean("virtual")
            || !base.data.getBoolean("target")
            || base.data.getBoolean("displayPartial")
            || base.data.getBoolean("areaSelectionShell")
            || base.data.getBoolean("virtualPlacement")
            || base.data.getLong("planRevision") != delta.fromRevision()
            || delta.revision() != delta.fromRevision() + 1
            || base.cells.size() != delta.cellCount()
            || metadata.getLong("planRevision") != delta.revision()
            || !metadata.getBoolean("localBlueprintDocument")
            || !base.session.equals(metadata.getString("session"))
            || !base.data.getString("generation")
                .equals(metadata.getString("generation"))
            || metadata.hasKey("cells")
            || metadata.hasKey("topology")
            || metadata.hasKey("structures")
            || metadata.hasKey("hintAreas")
            || metadata.hasKey("materials")) return null;
        HologramState next;
        if (delta.header()) {
            if (!base.data.getString("blueprintPrompt")
                .equals(delta.expectedPrompt())
                || !metadata.getString("blueprintPrompt")
                    .equals(delta.prompt()))
                return null;
            next = base.withMetadata(metadata);
        } else {
            // Existing partial construction shallow-copies the page directory and region maps. Constrain those
            // costs explicitly (512 directories, 256 regions, 4096 rows per region), never copy the row tree.
            int index = delta.index();
            if (index < 0 || index >= base.cells.size()
                || !boundedSinglePromptView(base.cells.size(), base.regions.regions.size())) return null;
            for (var area : base.regions.regions.values()) if (area.size() > 4096) return null;
            var row = base.cells.get(index);
            if (row.index != index || row.x != delta.x()
                || row.y != delta.y()
                || row.z != delta.z()
                || row.dx != delta.x()
                || row.dy != delta.y()
                || row.dz != delta.z()
                || row.anchor
                || row.iconOnly
                || row.missingBlock
                || row.sourceMissing
                || !row.structureIds.isEmpty()
                || !row.networkGroup.isEmpty()
                || !row.candidates.isEmpty()
                || row.hasDisplayTag(false, "nativeTile", 10)
                || row.targetStack != null && (row.targetStack.hasTagCompound()
                    || row.targetStack.getItem() instanceof gregtech.common.blocks.ItemMachines)
                || !row.prompt.equals(delta.expectedPrompt())
                || row.excluded != delta.excluded()
                || !row.wantId.equals(delta.block())
                || row.wantMeta != delta.metadata()
                || row.targetFacing != delta.facing()
                || base.regions.at(row.x, row.y, row.z) != row) return null;
            NBTTagCompound annotation = new NBTTagCompound();
            annotation.setInteger("index", index);
            annotation.setBoolean("annotationOnly", true);
            annotation.setString("prompt", delta.prompt());
            annotation.setBoolean("excluded", delta.excluded());
            next = applyAnnotation(base, metadata, annotation);
        }
        return MiaoVisonLiteRenderer.localRenderMetadataChanged(base, next) ? null : next;
    }

    static boolean boundedSinglePromptView(int cells, int regions) {
        return cells > 0 && cells <= 512 * 256 && regions > 0 && regions <= 256;
    }

    static HologramState applyAnnotation(HologramState base, NBTTagCompound metadata, NBTTagCompound annotation) {
        NBTTagList rows = new NBTTagList();
        rows.appendTag(annotation);
        metadata.setTag("cells", rows);
        metadata.setBoolean("partial", true);
        metadata.setInteger("cellCount", base.cells.size());
        metadata.setLong("baseRevision", base.data.getLong("planRevision"));
        return HologramState.apply(base, metadata);
    }

    void cancelAttachment(long request) {
        Task current = task;
        if (current != null && current.attachmentRequest == request && current(current)) task = null;
    }

    boolean savedNativePlacementReady(){
        var doc=ui.session.document();
        return doc!=null&&doc.nativeDraft()!=null&&!doc.dirty()&&doc.nativeReceipt()!=null;
    }

    boolean placeNativeSaved(){
        var doc=ui.session.document();
        if(doc==null||doc.nativeDraft()==null)return false;
        if(!savedNativePlacementReady()){
            ui.session.notice("请先保存蓝图，再提交施工");
            return false;
        }
        var saved=doc.nativeReceipt();
        return HologramLibraryClient.INSTANCE.previewLocal(saved.logicalId,true,saved);
    }

    void placeCurrent() {
        if (ui.session.document() == null) return;
        var document = ui.session.document();
        if (document.nativeDraft() != null) {
            placeNativeSaved();
            return;
        }
        ui.session.notice(document.dirty()||document.fileId().isEmpty()
            ?"请先保存蓝图，再提交施工"
            :HologramUiText.projector("library.convert_before_place"));
    }

    private void prepareView(Task current, HologramLocalBlueprintDocument document, String notice) throws IOException {
        if(document.nativeDraft()!=null){
            var nativeDraft=document.nativeDraft();var previous=current.source.nativeViewport;
            var index=previous!=null&&previous.geometry==nativeDraft.space()?previous.index:new HologramNativeViewport.Index(nativeDraft.space(),()->current(current));
            if(previous==null||previous.geometry!=nativeDraft.space())nativeDraft.preparePayloads(()->current(current));
            var metadata=commandResult(current,document.nativeEditorMetadata(),true,"");
            main(()->{
                if(!current(current)||!screenCurrent(current)){if(document!=current.draft&&document!=current.resumeDocument)document.closeNative();retireStaleView(current);return;}
                try{
                    // Provider and block access stays on the client owner; spatial indexing was detached on IO.
                    current.destination=document;current.preparedView=publishNativeView(metadata,nativeDraft,index,current.source,()->current(current)&&screenCurrent(current));
                    current.notice=notice;publishView(current);
                }catch(IOException|RuntimeException failure){if(document!=current.draft&&document!=current.resumeDocument)document.closeNative();failed(current,failure);}
            });
            return;
        }
        if (document.packedAuthoring()) {
            EditorRows editor = document.packedEditorRows(() -> current(current));
            if (editor == null) throw new IOException("蓝图紧凑预览已不可用");
            stageView(current, document, editor, editor.metadata(), notice);
        } else {
            HologramLocalHistoryTrace.mark(current.historyTrace, HologramLocalHistoryTrace.Stage.SNAPSHOT_BEGIN);
            var rows = document.detachedEditorRows(() -> current(current));
            HologramLocalHistoryTrace.mark(current.historyTrace, HologramLocalHistoryTrace.Stage.SNAPSHOT_END);
            stageView(
                current,
                document,
                new HologramBlueprintPreview.CompactCells(rows),
                rows.metadata(),
                notice,
                true);
        }
    }

    static HologramState nativeView(NBTTagCompound metadata,com.miaokatze.gtmb.hologram.HologramNativeBlueprintDraft draft,
        HologramNativeViewport.Index index,HologramNativeViewport previous)throws IOException{
        return new HologramState(metadata,new HologramNativeViewport(draft,index,metadata,previous));
    }
    static HologramState publishNativeView(NBTTagCompound metadata,com.miaokatze.gtmb.hologram.HologramNativeBlueprintDraft draft,
        HologramNativeViewport.Index index,HologramState previous,java.util.function.BooleanSupplier current)throws IOException{
        HologramNativeViewport viewport=previous.nativeViewport;
        if(viewport!=null&&viewport.currentProviders()&&viewport.draft==draft)return previous.withMetadata(metadata);
        return new HologramState(metadata,new HologramNativeViewport(draft,index,metadata,viewport,current),previous);
    }

    private void stageView(Task current, HologramLocalBlueprintDocument document, EditorRows editor,
        NBTTagCompound source, String notice) {
        stageView(current, document, new HologramBlueprintPreview.CompactCells(editor), source, notice, false);
    }

    private void stageView(Task current, HologramLocalBlueprintDocument document,
        HologramBlueprintPreview.CompactCells cells, NBTTagCompound source, String notice,
        boolean materializedProjection) {
        NBTTagCompound snapshot = commandResult(current, source, true, "");
        var history = current.historyTrace;
        HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.CLIENT_ENQUEUED);
        main(() -> {
            HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.CLIENT_STARTED);
            if (task != current) {
                HologramLocalHistoryTrace.end(history, HologramLocalHistoryTrace.Terminal.CANCELLED_OWNER);
                return;
            }
            if (!current(current) || !screenCurrent(current)) {
                HologramLocalHistoryTrace.end(history, HologramLocalHistoryTrace.Terminal.CANCELLED_OWNER);
                retireStaleView(current);
                return;
            }
            if (ui.host.minecraft().thePlayer != null)
                snapshot.setInteger("dimension", ui.host.minecraft().thePlayer.dimension);
            try {
                current.destination = document;
                current.packedEditor = true;
                current.preparation = new HologramState.Preparation(snapshot, true, cells);
                if (materializedProjection && current.privateEdit != null && current.privateEditPreparation == null)
                    current.privateEditPreparation = current.privateEdit.bind(
                        document,
                        true,
                        snapshot,
                        current.preparation,
                        document != current.draft && privateEditOwnerCurrent(current));
                current.notice = notice;
                HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.PREPARATION_READY);
            } catch (RuntimeException failure) {
                failed(current, failure);
            }
        });
    }

    void tick() {
        advance(false);
    }

    void advanceFrame() {
        advance(true);
    }

    private void advance(boolean frame) {
        Task current = task;
        if (current == null) return;
        if (!current.opening && current.preparation == null && current.structure == null) return;
        if (!current(current) || !screenCurrent(current)) {
            HologramLocalHistoryTrace.end(current.historyTrace, HologramLocalHistoryTrace.Terminal.CANCELLED_OWNER);
            if (current.structure != null) current.structure.close();
            retireStaleView(current);
            return;
        }
        if (current.structure != null) {
            if (!frame) advanceStructure(current);
            return;
        }
        if (current.preparation == null) return;
        long now = System.nanoTime();
        if (frame) current.advancedInFrame = now;
        else if (current.advancedInFrame != 0 && now - current.advancedInFrame < 60_000_000L) return;
        try {
            advanceView(current, 5_000_000L);
            publishView(current);
        } catch (RuntimeException failure) {
            HologramLocalHistoryTrace.end(current.historyTrace, HologramLocalHistoryTrace.Terminal.FAILED);
            task = null;
            retireOpeningPrefix(current);
            ui.session.notice(HologramUiText.reason(failure.getMessage()));
        }
    }

    private void publishView(Task current) {
        if (current.destination == null || current.preparedView == null) return;
        if ((current.opening || current.resumeDocument != null)
            && (!current(current) || !screenCurrent(current)
                || (current.resumeDocument != null && current.destination != current.resumeDocument))) {
            retireStaleView(current);
            return;
        }
        HologramState initial = current.preparedView;
        HologramOpeningPreview.Pose pose = current.openingCamera == null ? null
            : current.openingCamera.forInstalled(initial);
        task = null;
        retireOpeningPrefix(current);
        if (ui.session.document() == current.destination) publishOwnedLocalView(current, current.destination, initial);
        else {
            HologramLocalHistoryTrace.end(current.historyTrace, HologramLocalHistoryTrace.Terminal.CANCELLED_OWNER);
            String fileId = current.destination.fileId();
            if (current.resumeDocument == null && !fileId.isEmpty())
                HologramLibraryClient.INSTANCE.select(false, fileId);
            ui.session.installLocalDocument(current.destination, initial);
        }
        HologramScreen root = ui.host.parent();
        if (pose != null && root != null && root.ui.session.state() == initial
            && root.ui.session.document() == current.destination) root.ui.preview.adoptOpeningCamera(pose);
        if (!current.notice.isEmpty()) ui.session.notice(current.notice);
    }

    private void publishOwnedLocalView(Task current, HologramLocalBlueprintDocument destination, HologramState next) {
        var history = current.historyTrace;
        HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.PUBLISH_BEGIN);
        ui.session.publishLocalDocument(destination, next);
        if (history != null && ui.session.state() == next
            && ui.session.document() == destination
            && next.data.getLong("planRevision") == history.sourceRevision + 1
            && next.data.getLong("uiSequence") == history.sequence) {
            HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.PUBLISH_END);
            HologramLocalHistoryTrace.end(history, HologramLocalHistoryTrace.Terminal.PUBLISHED);
        } else HologramLocalHistoryTrace.end(history, HologramLocalHistoryTrace.Terminal.CANCELLED_OWNER);
    }

    private void advanceView(Task current, long nanos) {
        HologramState.Preparation preparation = current.preparation;
        if (preparation.canPrepareCompactIndex()) {
            HologramState.Preparation.DetachedIndex index = preparation.prepareCompactIndex(256, nanos);
            if (index == null) return;
            offerOpeningPrefix(current, index.firstDisplay());
            current.preparation = null;
            work(current, () -> {
                HologramState result = index.finish(() -> current(current));
                if (result != null) main(() -> acceptIndexedView(current, result));
            });
        } else {
            int cap = Integer.MAX_VALUE;
            if (current.privateEditPreparation != null) {
                var preset = current.privateEditPreparation
                    .forPreparation(preparation, privateEditOwnerCurrent(current));
                if (preset != null) {
                    cap = preset.workCap;
                    nanos = preset.softNanos;
                } else current.privateEditPreparation = null; // A lost owner cannot resume a tuned cursor.
            }
            var history = current.historyTrace;
            int cellsBefore = history == null ? 0 : preparation.cellsPrepared();
            long start = history == null ? 0 : history.now();
            boolean complete = preparation.step(cap, nanos);
            if (history != null) history.slice(
                start,
                history.now(),
                preparation.cellsPrepared() - cellsBefore,
                preparation.lastStepWork,
                cap,
                complete);
            if (current.openingPreview == null && (!complete || current.destination == null))
                offerOpeningPrefix(current, preparation.firstDisplay());
            if (complete) {
                HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.RESULT_BEGIN);
                current.preparedView = preparation.result();
                HologramLocalHistoryTrace.mark(history, HologramLocalHistoryTrace.Stage.RESULT_END);
                current.preparation = null;
            }
        }
    }

    private void acceptIndexedView(Task current, HologramState result) {
        if (!current(current) || !screenCurrent(current)) {
            retireStaleView(current);
            return;
        }
        current.preparedView = result;
        publishView(current);
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
        return begin(message, null, false);
    }

    private Task beginView(String message, String explicitPrivateEditId) {
        return begin(message, explicitPrivateEditId, true);
    }

    private Task begin(String message, String explicitPrivateEditId, boolean viewOnly) {
        if (busy()) return null;
        Minecraft mc = ui.host.minecraft();
        HologramState source = ui.session.state();
        PresentationOwner owner = new PresentationOwner(
            mc.theWorld,
            mc.getNetHandler(),
            actor(mc),
            source.session,
            source.data.getLong("planRevision"));
        if (!owner.available()) return null;
        var privateEdit = explicitPrivateEditId == null ? null
            : HologramPrivateEditPerformance
                .capture(explicitPrivateEditId, HologramClient.privateEditPreset(), privateEditRootCurrent(mc));
        Task current = new Task(source, owner, ui.session.document(), privateEdit, viewOnly);
        current.message = message;
        task = current;
        ui.session.notice(message);
        return current;
    }

    private boolean privateEditRootCurrent(Minecraft mc) {
        HologramScreen root = ui.host.parent();
        return root != null && mc.currentScreen == root && root.ui == ui && !ui.session.isTutorial();
    }

    /** Additional budget fence only; preserve the original scheduler if the existing task can still proceed. */
    private boolean privateEditOwnerCurrent(Task candidate) {
        return privateEditRootCurrent(ui.host.minecraft()) && ui.session.state() == candidate.source
            && ui.session.document() == candidate.draft;
    }

    private boolean current(Task candidate) {
        return task == candidate && (candidate.draft == null || candidate.draft.revision() == candidate.revision)
            && (candidate.resumeDocument == null || candidate.resumeDocument.revision() == candidate.resumeRevision);
    }

    private static UUID actor(Minecraft mc) {
        return mc.thePlayer == null ? null : mc.thePlayer.getUniqueID();
    }

    private boolean clientCurrent(Task candidate) {
        Minecraft mc = ui.host.minecraft();
        return candidate.owner.sameClient(mc.theWorld, mc.getNetHandler(), actor(mc));
    }

    private boolean screenCurrent(Task candidate) {
        Minecraft mc = ui.host.minecraft();
        HologramState active = ui.session.state();
        HologramScreen root = ui.host.parent();
        if (candidate.opening && (root == null || root.ui != ui)) return false;
        if (candidate.resumeDocument != null && (root == null || root.ui != ui
            || mc.thePlayer == null
            || mc.thePlayer != candidate.resumePlayer
            || mc.thePlayer.dimension != candidate.resumeDimension)) return false;
        return (mc.currentScreen == root
            || mc.currentScreen instanceof HologramQuantumGroupsScreen quantum && quantum.editorIs(ui.host.parent()))
            && candidate.owner.sameSource(
                mc.theWorld,
                mc.getNetHandler(),
                actor(mc),
                active.session,
                active.data.getLong("planRevision"));
    }

    private void failed(Task current, Throwable failure) {
        main(() -> {
            if (task != current) {
                end(current.saveTrace, Terminal.CANCELLED_OWNER);
                HologramLocalHistoryTrace.end(current.historyTrace, HologramLocalHistoryTrace.Terminal.CANCELLED_OWNER);
                return;
            }
            if (current.resumeDocument != null && current.resumeDocument.revision() != current.resumeRevision) {
                retireStaleView(current);
                return;
            }
            var trace = current.saveTrace;
            boolean sameRevision = trace == null || current(current);
            task = null;
            retireOpeningPrefix(current);
            boolean sameScreen = screenCurrent(current);
            end(trace, sameScreen && sameRevision ? Terminal.FAILED : Terminal.CANCELLED_OWNER);
            if (current.historyTrace != null) HologramLocalHistoryTrace.end(
                current.historyTrace,
                sameScreen && current.draft.revision() == current.revision ? HologramLocalHistoryTrace.Terminal.FAILED
                    : HologramLocalHistoryTrace.Terminal.CANCELLED_OWNER);
            if (!sameScreen) return;
            if (failure instanceof com.miaokatze.gtmb.hologram.HologramCompactRequiredException) {
                ui.library.requireCompactUpdate();
                return;
            }
            String reason = HologramUiText.reason(failure.getMessage());
            if (current.draft != null && !current.operation.isEmpty()) ui.session.publishLocalDocument(
                current.draft,
                ui.session.state()
                    .withMetadata(commandResult(current, new NBTTagCompound(), false, reason)));
            else ui.session.notice(reason);
        });
    }

    private static NBTTagCompound commandResult(Task current, NBTTagCompound metadata, boolean accepted,
        String reason) {
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
            if ("configureBlueprintQuantumGroups".equals(current.operation))
                metadata.setBoolean("quantumGroupsOperationSuccess", accepted);
        }
        return metadata;
    }

    private void tracedWork(Task current, Stage queued, Stage started, FileWork operation) {
        var trace = current.saveTrace;
        if (trace == null) {
            work(current, operation);
            return;
        }
        mark(trace, queued);
        work(current, () -> {
            mark(trace, started);
            operation.run();
        });
    }

    private boolean retiredView(Task candidate) {
        return candidate.viewOnly && task != candidate;
    }

    private void work(Task current, FileWork operation) {
        if (retiredView(current)) return; // A late server response must not enqueue an old editor open.
        try {
            IO.execute(() -> {
                try {
                    if (current(current)) operation.run();
                    else if (!retiredView(current)) failed(current, new IOException("蓝图已修改，请重试"));
                } catch (IOException | RuntimeException failure) {
                    if (!retiredView(current)) failed(current, failure);
                }
            });
        } catch (RuntimeException failure) {
            if (!retiredView(current)) failed(current, failure);
        }
    }

    private static void main(Runnable runnable) {
        Minecraft.getMinecraft()
            .func_152344_a(runnable);
    }

    private interface FileSource {

        EditorSource read() throws IOException;
    }

    private interface FileWork {

        void run() throws IOException;
    }
}
