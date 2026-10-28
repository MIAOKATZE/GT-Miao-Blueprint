package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramLocalAuthorAttachments;
import com.miaokatze.gtmb.hologram.HologramTextBox;

/** Owner-local completion callbacks, not textPlacementAck or attachment upload transport. */
final class HologramLocalAuthorAttachmentActions implements AutoCloseable {

    private final HologramLocalAuthorRuntime runtime;
    private final HologramLocalAuthorAttachments attachments = new HologramLocalAuthorAttachments();
    private HologramUiModules requester;

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
                .start(ui, action);
        } catch (IOException | RuntimeException | LinkageError failed) {
            String message = "本地附加对象操作失败，原稿保留：" + failed.getMessage();
            if (place) ui.textBoxes.localPlacementFinished(request.getLong("textRequest"), false, message);
            ui.session.notice(message);
        }
        return true;
    }

    void start(HologramUiModules ui, NBTTagCompound request) throws IOException {
        attachments.start(runtime.editor(), request);
        requester = ui;
    }

    boolean busy() {
        return attachments.busy();
    }

    String phase() {
        return attachments.phase();
    }

    long completed() {
        return attachments.completed();
    }

    long total() {
        return attachments.total();
    }

    void tick() {
        finish(attachments.tick());
    }

    void cancel() {
        finish(attachments.cancel("附加对象任务已取消，原稿保留"));
    }

    @Override
    public void close() {
        cancel();
    }

    private void finish(HologramLocalAuthorAttachments.Result result) {
        if (result == null) return;
        HologramUiModules ui = requester;
        requester = null;
        if (result.change != null) runtime.apply(result.change);
        if (ui != null) {
            ui.textBoxes.localPlacementFinished(result.request, result.accepted, result.message);
            ui.session.notice(result.message);
        }
    }
}
