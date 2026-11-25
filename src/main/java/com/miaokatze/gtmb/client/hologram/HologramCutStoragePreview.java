// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

/** The real browser uses only its accepted directory; the isolated local tutorial keeps its own raw renderer. */
final class HologramCutStoragePreview implements HologramCutStoragePreviewView {

    private static HologramCutStoragePreview active;
    final HologramCutStoragePreviewState state = new HologramCutStoragePreviewState();
    private final Object world = Minecraft.getMinecraft().theWorld;
    private final Object connection = Minecraft.getMinecraft()
        .getNetHandler();

    HologramCutStoragePreview() {
        if (active != null) active.close();
        active = this;
    }

    void select(String id) {
        state.select(id);
    }

    void tick(boolean ignoredMayRequest) {
        Minecraft mc = Minecraft.getMinecraft();
        if (world == null || world != mc.theWorld || connection != mc.getNetHandler()) close();
    }

    /** Claim legacy browser IDs without reading their raw data or reusing their progress. */
    static boolean receive(NBTTagCompound reply) {
        return reply.getString("requestId")
            .startsWith(HologramCutStoragePreviewState.PREFIX);
    }

    void retry() {
        state.retry();
    }

    public HologramCutRawPreviewSession renderer() {
        return null;
    }

    public NBTTagCompound raw() {
        return null;
    }

    HologramProgress progress() {
        return null;
    }

    public String notice() {
        if (state.selected()
            .isEmpty()) return "";
        HologramCutClient.ClipBounds bounds = HologramCutClient.INSTANCE.clipboardBounds(state.selected());
        if (bounds == null) return "没有已确认的剪切范围。";
        return bounds.sx + " × " + bounds.sy + " × " + bounds.sz + " · 仅显示范围，不下载完整结构。";
    }

    void close() {
        state.close();
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
