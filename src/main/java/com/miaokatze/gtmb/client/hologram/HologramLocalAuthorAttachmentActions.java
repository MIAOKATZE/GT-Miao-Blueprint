package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorReviewDeltaIO;
import com.miaokatze.gtmb.hologram.HologramAuthorReviewProtocol;
import com.miaokatze.gtmb.hologram.HologramExtensionExecution;
import com.miaokatze.gtmb.hologram.HologramLocalAuthorAttachments;
import com.miaokatze.gtmb.hologram.HologramTextBox;

/** Owner-local completion callbacks, not textPlacementAck or attachment upload transport. */
final class HologramLocalAuthorAttachmentActions implements AutoCloseable {

    private final HologramLocalAuthorRuntime runtime;
    private final HologramLocalAuthorAttachments attachments = new HologramLocalAuthorAttachments();
    private HologramUiModules requester;
    private NBTTagCompound pendingRequest;
    private AuthorDocument frozen;
    private Set<Long> selected;
    private String structure;
    private boolean serverPending, rotation;

    HologramLocalAuthorAttachmentActions(HologramLocalAuthorRuntime runtime) {
        this.runtime = runtime;
    }

    static boolean execute(HologramLocalAuthorRuntime runtime, HologramUiModules ui, String operation,
        NBTTagCompound request) {
        if ("cancelAttachment".equals(operation)) {
            runtime.attachments()
                .cancel();
            return true;
        }
        boolean place = "placeTextBox".equals(operation);
        if (!place && !"rotateCell".equals(operation)) return false;
        try {
            NBTTagCompound action = request;
            if (!place) {
                Set<Long> selected = request.hasKey("selected", 3)
                    ? runtime.selectionIds(new int[] { request.getInteger("selected") })
                    : runtime.editor()
                        .selection();
                NBTTagCompound definition = HologramLocalAuthorAttachments
                    .selectedDefinition(runtime.document(), selected);
                if (definition == null) return false;
                HologramTextBox text = HologramTextBox.read(definition);
                text.turn(request.getBoolean("vertical"));
                action = (NBTTagCompound) request.copy();
                action.setTag("textBox", text.write());
            }
            runtime.attachments()
                .start(ui, action, !place);
        } catch (IOException | RuntimeException | LinkageError failed) {
            String message = "本地附加对象操作失败，原稿保留：" + failed.getMessage();
            if (place) ui.textBoxes.localPlacementFinished(request.getLong("textRequest"), false, message);
            ui.session.notice(message);
        }
        return true;
    }

    void start(HologramUiModules ui, NBTTagCompound request, boolean rotate) throws IOException {
        if (busy()) throw new IOException("正在处理附加对象，请等待或取消");
        pendingRequest = (NBTTagCompound) request.copy();
        frozen = runtime.document();
        selected = new java.util.HashSet<>(
            runtime.editor()
                .selection());
        structure = runtime.view().data.getString("activeStructure");
        rotation = rotate;
        requester = ui;
        try {
            attachments.start(runtime.editor(), pendingRequest);
        } catch (HologramExtensionExecution.ReviewRequired serverOnly) {
            beginServerReview();
        } catch (IOException | RuntimeException | LinkageError failed) {
            clear();
            throw failed;
        }
    }

    private boolean purposeCurrent() {
        return runtime.document() == frozen && (!rotation || selected.equals(
            runtime.editor()
                .selection())
            && structure.equals(runtime.view().data.getString("activeStructure")));
    }

    /** Only an explicit attachment command refused by the local native boundary enters transport. */
    private void beginServerReview() {
        final NBTTagCompound action = pendingRequest;
        final AuthorDocument original = frozen;
        final HologramUiModules ui = requester;
        if (action == null || !purposeCurrent()) {
            finishServer(false, "文档或附加对象选择已变化；原稿保留");
            return;
        }
        serverPending = true;
        ui.session.notice("正在服务器核对附加对象扩展；只生成声明，不施工、不扣料，可取消");
        HologramAuthorReviewClient
            .request(runtime, "attachment", action, () -> pendingRequest == action && purposeCurrent(), reply -> {
                try {
                    NBTTagCompound report = reply.getCompoundTag("report");
                    if (report.getLong("textRequest") != action.getLong("textRequest"))
                        throw new IOException("Attachment response request changed");
                    JsonObject delta = AuthorReviewDeltaIO
                        .read(reply.getByteArray("source"), HologramAuthorReviewProtocol.limits());
                    java.util.List<JsonObject> cells = new java.util.ArrayList<>();
                    for (JsonElement row : delta.getAsJsonArray("cells")) cells.add(row.getAsJsonObject());
                    // Retire pending UI state before apply: installing the transaction cancels outstanding work.
                    serverPending = false;
                    runtime.apply(
                        runtime.editor()
                            .replaceAttachment(original.revision(), delta.getAsJsonObject("group"), cells));
                    finishServer(true, "服务器附加对象声明已更新；未执行世界施工");
                } catch (IOException | RuntimeException failed) {
                    finishServer(false, "服务器附加对象结果未应用，原稿保留：" + failed.getMessage());
                }
            }, failed -> finishServer(false, "服务器附加对象审核未完成，原稿保留：" + failed.getMessage()));
    }

    private void finishServer(boolean accepted, String message) {
        HologramUiModules ui = requester;
        long request = pendingRequest == null ? 0 : pendingRequest.getLong("textRequest");
        clear();
        if (ui != null) {
            ui.textBoxes.localPlacementFinished(request, accepted, message);
            ui.session.notice(message);
        }
    }

    private void clear() {
        serverPending = false;
        requester = null;
        pendingRequest = null;
        frozen = null;
        selected = null;
        structure = null;
    }

    boolean busy() {
        return serverPending || attachments.busy();
    }

    String phase() {
        return serverPending ? "server attachment review" : attachments.phase();
    }

    long completed() {
        return attachments.completed();
    }

    long total() {
        return attachments.total();
    }

    void tick() {
        if (serverPending) {
            if (!purposeCurrent()) cancel();
            return;
        }
        HologramLocalAuthorAttachments.Result result = attachments.tick();
        if (result != null && result.reviewRequired) beginServerReview();
        else finish(result);
    }

    void cancel() {
        if (serverPending) HologramAuthorReviewClient.cancel(runtime);
        else finish(attachments.cancel("附加对象任务已取消，原稿保留"));
    }

    @Override
    public void close() {
        cancel();
    }

    private void finish(HologramLocalAuthorAttachments.Result result) {
        if (result == null) return;
        HologramUiModules ui = requester;
        clear();
        if (result.change != null) runtime.apply(result.change);
        if (ui != null) {
            ui.textBoxes.localPlacementFinished(result.request, result.accepted, result.message);
            ui.session.notice(result.message);
        }
    }
}
