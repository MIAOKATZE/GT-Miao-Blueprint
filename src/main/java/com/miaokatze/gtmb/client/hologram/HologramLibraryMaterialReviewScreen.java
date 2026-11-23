// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.client.hologram.HologramLocalPlacement.Submission;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalFrame;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalView;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

/** Lightweight local modal over the already-present world ghost; no second workspace or source read. */
final class HologramLibraryMaterialReviewScreen extends HologramMuiScreen implements LocalView {

    private final HologramLocalPlacement placement;
    private final Ticket ticket;
    private final LocalFrame frame;
    private HologramMaterialConfirmationDialog dialog;

    HologramLibraryMaterialReviewScreen(HologramLocalPlacement placement, Submission frozen, Minecraft client) {
        super("library_material_review", 1, 1);
        this.placement = placement;
        ticket = new Ticket(placement, frozen, client, this);
        frame = unknownFrame(frozen.source());
    }

    static LocalFrame unknownFrame(HologramLibraryClient.DisplaySource captured) {
        HologramLibraryMaterialForecast forecast = HologramLibraryMaterialForecast.fromCapture(captured);
        // No production resolver has proved these costs yet. Never attempt a download or server quote to fill them.
        NBTTagCompound rows = forecast == null ? new NBTTagCompound() : forecast.stockRows(null, null, -1);
        return new LocalFrame(rows, "已锁定蓝图 · 整份范围", "材料未核算 · 尚未查询服务端或 AE",
            "本机无法确认此蓝图的材料需求", true);
    }

    @Override
    protected void buildPanel() {
        panel().invisible();
        dialog = new HologramMaterialConfirmationDialog(panel(), this);
    }

    @Override
    public void updateScreen() {
        Minecraft client = Minecraft.getMinecraft();
        if (!ticket.current(client)) returnToWorld(client);
        else if (dialog != null) dialog.tick(true);
    }

    @Override
    public LocalFrame localFrame() {
        return ticket.current(Minecraft.getMinecraft()) ? frame : null;
    }

    @Override
    public boolean localSubmitAvailable() {
        return ticket.current(Minecraft.getMinecraft());
    }

    @Override
    public void localAction(LocalAction action) {
        deferAction(() -> {
            Minecraft client = Minecraft.getMinecraft();
            if (action == LocalAction.BACK) returnToWorld(client);
            else if (action == LocalAction.SUBMIT) {
                Submission frozen = ticket.consume(client);
                if (frozen != null && !placement.submitFrozen(frozen)) {
                    returnToWorld(client);
                    HologramClient.selectionNotice("未开始施工核验；位置保留，请重新打开材料窗口。");
                }
            }
            // Refresh cannot update unavailable sources. Keep the same local frame and never request stock.
        });
    }

    @Override
    protected boolean muiKeyBefore(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) localAction(LocalAction.BACK);
        return true;
    }

    private void returnToWorld(Minecraft client) {
        ticket.retire();
        if (client != null && client.currentScreen == this) client.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        ticket.retire();
    }

    @Override
    protected void onMuiClose() {
        ticket.retire();
    }

    static final class Ticket {

        private final HologramLocalPlacement placement;
        private final Submission frozen;
        private final GuiScreen owner;
        private final int slot, dimension;
        private final ItemStack tool;
        private final String toolId;
        private boolean retired;

        Ticket(HologramLocalPlacement placement, Submission frozen, Minecraft client, GuiScreen owner) {
            this.placement = placement;
            this.frozen = frozen;
            this.owner = owner;
            slot = client.thePlayer.inventory.currentItem;
            dimension = client.thePlayer.dimension;
            tool = client.thePlayer.getHeldItem();
            toolId = id(tool);
        }

        boolean current(Minecraft client) {
            return !retired && client != null && client.currentScreen == owner && !placement.submitting()
                && frozen.current(placement, client) && ItemNekoHologramProjector.isProjector(tool)
                && client.thePlayer.dimension == dimension && client.thePlayer.inventory.currentItem == slot
                && client.thePlayer.getHeldItem() == tool && toolId.equals(id(tool));
        }

        Submission consume(Minecraft client) {
            boolean usable = current(client);
            // A rejected deferred click cannot be retried by restoring the previous slot or screen.
            retire();
            return usable ? frozen : null;
        }

        void retire() {
            retired = true;
        }

        private static String id(ItemStack stack) {
            return stack != null && stack.hasTagCompound() ? stack.getTagCompound().getString("gtitHologramToolId") : "";
        }
    }
}
