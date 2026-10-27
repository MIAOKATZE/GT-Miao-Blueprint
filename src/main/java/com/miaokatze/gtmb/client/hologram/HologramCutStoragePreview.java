package com.miaokatze.gtmb.client.hologram;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramNetwork;

/** Read-only storage browser, deliberately separate from placement pose, locks and request accounting. */
final class HologramCutStoragePreview implements HologramCutStoragePreviewView {

    private static HologramCutStoragePreview active;
    final HologramCutStoragePreviewState state = new HologramCutStoragePreviewState();
    private final Object world = Minecraft.getMinecraft().theWorld;
    private final Object connection = Minecraft.getMinecraft()
        .getNetHandler();
    private HologramCutRawPreviewSession renderer;
    private NBTTagCompound raw;
    private String notice = "";
    private final HologramProgress.Tracker progress = new HologramProgress.Tracker();

    HologramCutStoragePreview() {
        if (active != null) active.close();
        active = this;
    }

    void select(String id) {
        if (state.select(id)) clearRaw();
    }

    private boolean validWorld() {
        Minecraft mc = Minecraft.getMinecraft();
        return world != null && world == mc.theWorld && connection == mc.getNetHandler();
    }

    void tick(boolean mayRequest) {
        if (!validWorld()) {
            close();
            return;
        }
        if (state.expire(System.nanoTime())) {
            notice = text("timeout");
            progress.clear();
        }
        if (!mayRequest || !state.needsRequest()) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", "preview");
        request.setString("id", state.selected());
        UUID random = UUID.randomUUID();
        String id = new UUID(
            0x47544d426d750000L | (random.getMostSignificantBits() & 0xffffL),
            random.getLeastSignificantBits()).toString();
        request.setString("requestId", id);
        request.setString("previewPurpose", "storage");
        // No placement transform: the directory always previews the original complete raw structure.
        state.begin(id, System.nanoTime());
        progress.update(id, "cut.preview", 0, 0, "", false, System.nanoTime());
        notice = text("loading");
        HologramNetwork.sendCutAction(request);
    }

    /** Also claims stale browser IDs so they cannot enter the placement controller's ACK path. */
    static boolean receive(NBTTagCompound reply) {
        String id = reply.getString("requestId");
        if (!id.startsWith(HologramCutStoragePreviewState.PREFIX)) return false;
        HologramCutStoragePreview owner = active;
        if (owner == null) return true;
        if (!owner.validWorld()) {
            owner.close();
            return true;
        }
        if (!owner.state.accept(id)) return true;
        boolean present = false;
        for (NBTTagCompound summary : HologramCutClient.INSTANCE.entries()) if (owner.state.selected()
            .equals(summary.getString("id"))) present = true;
        if (!present) {
            owner.select("");
            return true;
        }
        if (!reply.getBoolean("success") || !reply.hasKey("preview", 10)
            || !"storage".equals(reply.getString("previewPurpose"))
            || !owner.state.selected()
                .equals(reply.getString("id"))) {
            owner.notice = text("unavailable") + " " + reply.getString("message");
            owner.progress.clear();
            return true;
        }
        owner.raw = (NBTTagCompound) reply.getCompoundTag("preview")
            .copy();
        try {
            owner.renderer = HologramCutRawPreview.newSession();
            owner.renderer.update(owner.raw);
            owner.notice = "";
            owner.progress.finish(id, System.nanoTime());
        } catch (RuntimeException failure) {
            owner.clearRaw();
            owner.notice = text("unavailable") + " "
                + failure.getClass()
                    .getSimpleName();
        }
        return true;
    }

    /** The transport binds its materialized-byte lease to the actual retained raw owner. */
    static NBTTagCompound retainedRaw() {
        return active == null ? null : active.raw;
    }

    void retry() {
        state.retry();
        clearRaw();
    }

    public HologramCutRawPreviewSession renderer() {
        return renderer;
    }

    public NBTTagCompound raw() {
        return raw;
    }

    HologramProgress progress() {
        HologramProgress transport = HologramClientProgress.view("cut", "");
        if (transport != null && !state.request()
            .isEmpty()
            && state.request()
                .equals(transport.operation))
            return transport;
        return progress.view(System.nanoTime());
    }

    public String notice() {
        if (renderer != null && !renderer.diagnostics()
            .isEmpty()) return renderer.diagnostics()
                .get(0);
        return notice;
    }

    private void clearRaw() {
        if (renderer != null) renderer.release();
        renderer = null;
        raw = null;
        notice = "";
        progress.clear();
    }

    void close() {
        state.close();
        clearRaw();
        if (active == this) active = null;
    }

    static void invalidate() {
        if (active != null) active.close();
    }

    /** An acknowledged directory can consume a row while a recipe viewer temporarily hides this panel. */
    static void directoryChanged(java.util.List<NBTTagCompound> summaries) {
        if (active == null || active.state.selected()
            .isEmpty()) return;
        for (NBTTagCompound summary : summaries) if (active.state.selected()
            .equals(summary.getString("id"))) return;
        active.select("");
    }

    static String text(String key) {
        return HologramClient.text("cut.storage." + key, key);
    }
}
