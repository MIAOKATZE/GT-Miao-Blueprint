// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.Collections;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.client.hologram.HologramLibraryClient.PlainAvailability;
import com.miaokatze.gtmb.client.hologram.HologramLocalPlacement.Submission;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalAction;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalFrame;
import com.miaokatze.gtmb.client.hologram.HologramMaterialConfirmationDialog.LocalView;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

/** Lightweight local modal over the already-present world ghost; no second workspace or source read. */
final class HologramLibraryMaterialReviewScreen extends HologramMuiScreen implements LocalView {

    private final HologramLocalPlacement placement;
    private final Ticket ticket;
    private final CapturedDescription description;
    private HologramMaterialConfirmationDialog dialog;
    private PlainAvailability availability = PlainAvailability.SOURCE;

    HologramLibraryMaterialReviewScreen(HologramLocalPlacement placement, Submission frozen, Minecraft client) {
        super("library_material_review", 1, 1);
        this.placement = placement;
        ticket = new Ticket(placement, frozen, client, this);
        description = new CapturedDescription(HologramLibraryMaterialForecast.fromCapture(frozen.source()));
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
        Minecraft client = Minecraft.getMinecraft();
        if (!ticket.current(client)) return null;
        availability = HologramLibraryClient.INSTANCE.plainPlacementAvailability(ticket.frozen, client);
        LocalFrame frame = description.frame(() -> client.thePlayer.inventory.mainInventory, ticket.slot);
        return availability == PlainAvailability.READY ? frame : new LocalFrame(
            frame.rows(), frame.scope(), frame.status() + " · " + availability.reason,
            frame.emptyLabel(), frame.ready(), frame.description());
    }

    @Override
    public String localSubmitLabel() {
        return availability == PlainAvailability.READY ? "开始施工（仅使用主背包）" : "施工不可用 · " + availability.reason;
    }

    @Override
    public boolean localSubmitAvailable() {
        return availability == PlainAvailability.READY && ticket.current(Minecraft.getMinecraft());
    }

    @Override
    public void localAction(LocalAction action) {
        deferAction(() -> {
            Minecraft client = Minecraft.getMinecraft();
            if (action == LocalAction.BACK) returnToWorld(client);
            else if (action == LocalAction.REFRESH) {
                if (ticket.current(client))
                    description.refresh(() -> client.thePlayer.inventory.mainInventory, ticket.slot);
            } else if (action == LocalAction.SUBMIT && ticket.current(client)) {
                availability = HologramLibraryClient.INSTANCE.plainPlacementAvailability(ticket.frozen, client);
                if (availability != PlainAvailability.READY) {
                    if (availability == PlainAvailability.TRANSPORT)
                        HologramClientProjectedPlacement.logMissingTransport();
                    HologramClient.selectionNotice(availability.reason + "，未开始施工。");
                } else if (dispatchRefused(action, () -> ticket.consume(client), placement::submitFrozen)) {
                    returnToWorld(client);
                    HologramClient.selectionNotice("当前锁定范围或连接未通过施工检查；已保留位置，未开始施工。");
                }
            }
        });
    }

    static boolean dispatchRefused(LocalAction action, Supplier<Submission> consume,
        BiPredicate<Submission, Boolean> submit) {
        if (action != LocalAction.SUBMIT) return false;
        Submission frozen = consume.get();
        return frozen != null && !submit.test(frozen, true);
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

    /** The frozen source projection survives local stock refresh; only an owned sample reads slots. */
    static final class CapturedDescription {

        private static final int MAIN_SLOTS = 36;
        private final HologramLibraryMaterialForecast forecast;
        private LocalFrame cached;

        CapturedDescription(HologramLibraryMaterialForecast forecast) {
            this.forecast = forecast;
        }

        LocalFrame frame(Supplier<ItemStack[]> mainInventory, int toolSlot) {
            if (cached == null) refresh(mainInventory, toolSlot);
            return cached;
        }

        void refresh(Supplier<ItemStack[]> mainInventory, int toolSlot) {
            boolean canObserve = forecast != null && !forecast.candidates()
                .isEmpty();
            if (!canObserve && cached != null) return;
            ItemStack[] source = canObserve ? mainInventory.get() : null;
            ItemStack[] observed = source == null || source.length < MAIN_SLOTS ? null
                : source.length == MAIN_SLOTS ? source : Arrays.copyOf(source, MAIN_SLOTS);
            HologramLibraryMaterialDescription.Description result = HologramLibraryMaterialDescription
                .describe(forecast, observed, toolSlot);
            String status = "施工用量未核算";
            if (result == null) status += " · 本机没有可核对的来源记录";
            else if (result.unlistedCells() > 0) status += " · " + result.unlistedCells() + " 格未列明";
            cached = new LocalFrame(
                new NBTTagCompound(),
                "已锁定蓝图 · 整份范围",
                status,
                "本机没有可列明的蓝图材料记录",
                canObserve,
                result == null ? Collections.emptyList() : result.rows());
        }
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
            return !retired && client != null
                && client.currentScreen == owner
                && !placement.submitting()
                && frozen.current(placement, client)
                && !client.thePlayer.isDead
                && ItemNekoHologramProjector.isProjector(tool)
                && client.thePlayer.dimension == dimension
                && client.thePlayer.inventory.currentItem == slot
                && client.thePlayer.getHeldItem() == tool
                && toolId.equals(id(tool));
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
            return stack != null && stack.hasTagCompound() ? stack.getTagCompound()
                .getString("gtitHologramToolId") : "";
        }
    }
}
