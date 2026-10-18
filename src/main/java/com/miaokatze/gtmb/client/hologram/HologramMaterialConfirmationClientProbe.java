package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.cleanroommc.modularui.widget.AbstractScrollWidget;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.widgets.ButtonWidget;

/** Opt-in live-client observations and real MUI input, without bypassing the material dialog's callbacks. */
final class HologramMaterialConfirmationClientProbe {

    private HologramMaterialConfirmationClientProbe() {}

    static NBTTagCompound observe(HologramScreen screen) {
        requireProbe();
        NBTTagCompound observed = screen.ui.review.materialDialogProbeState();
        observed.setLong("planRevision", screen.ui.session.revision());
        observed.setLong("placementReviewNonce", screen.ui.session.state().data.getLong("placementReviewNonce"));
        observed.setBoolean("confirmEnabled", screen.ui.review.materialControlEnabled(55));
        observed.setBoolean("orderEnabled", screen.ui.review.materialControlEnabled(162));
        observed.setBoolean("voucherEnabledAction", screen.ui.review.materialControlEnabled(245));
        observed.setIntArray("confirmPoint", point(screen, 55));
        observed.setIntArray("voucherPoint", point(screen, 245));
        return observed;
    }

    static void click(HologramScreen screen, int id) {
        requireProbe();
        int[] position = point(screen, id);
        if (position.length != 2) throw new IllegalStateException("Material control has not rendered: " + id);
        ModularPanel panel = preparePointer(screen, position[0], position[1]);
        boolean buttonHovered = false;
        for (LocatedWidget hovered : panel.getHovering())
            buttonHovered |= hovered.getElement() instanceof ButtonWidget<?>;
        if (!buttonHovered)
            throw new IllegalStateException("Material control is absent from the actual MUI button hit list: " + id);
        HologramMuiProbeInput.click(screen, position[0], position[1], 0);
    }

    static NBTTagCompound scroll(HologramScreen screen, int amount) {
        requireProbe();
        int[] position = point(screen, 168);
        if (position.length != 2) throw new IllegalStateException("Material dialog has not rendered");
        // The list sits above the first action row. Derive the same panel transform as the observed control point.
        int height = screen.getScreen()
            .getScreenArea().height;
        int width = screen.getScreen()
            .getScreenArea().width;
        float scale = Math.min(1f, Math.min(width / 644f, height / 458f));
        ModularScreen mui = screen.getScreen();
        int mouseX = position[0], mouseY = position[1] - Math.round(160 * scale);
        ModularPanel panel = preparePointer(screen, mouseX, mouseY);
        AbstractScrollWidget<?, ?> target = null;
        for (IWidget child : panel.getChildren()) if (child instanceof AbstractScrollWidget<?, ?>scrollWidget) {
            target = scrollWidget;
            break;
        }
        if (target == null) throw new IllegalStateException("Material dialog has no actual scroll widget");
        VerticalScrollData data = target.getScrollArea()
            .getScrollY();
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("mouseX", mouseX);
        result.setInteger("mouseY", mouseY);
        result.setInteger("before", data.getScroll());
        result.setInteger("scrollSize", data.getScrollSize());
        result.setInteger("viewportHeight", target.getScrollArea().height);
        result.setBoolean(
            "samePanelContext",
            panel.getContext() == mui.getContext() && target.getContext() == mui.getContext());
        NBTTagList hovering = new NBTTagList();
        for (LocatedWidget hovered : panel.getHovering()) {
            IWidget widget = hovered.getElement();
            hovering.appendTag(
                new NBTTagString(
                    widget.getClass()
                        .getName() + ":"
                        + widget.getName()));
            if (widget == target) {
                result.setBoolean("listInActualHitTest", true);
                hovered.applyMatrix(mui.getContext());
                try {
                    result.setInteger(
                        "listLocalX",
                        mui.getContext()
                            .getMouseX());
                    result.setInteger(
                        "listLocalY",
                        mui.getContext()
                            .getMouseY());
                } finally {
                    hovered.unapplyMatrix(mui.getContext());
                }
            }
        }
        result.setTag("actualHovering", hovering);
        // MUI2 2.3.92 reads wheel from its context, not onMouseScroll's amount argument.
        // Stage that raw input only for this opt-in probe, then restore the context immediately.
        try {
            java.lang.reflect.Field wheel = com.cleanroommc.modularui.screen.viewport.GuiContext.class
                .getDeclaredField("mouseWheel");
            wheel.setAccessible(true);
            int previous = wheel.getInt(mui.getContext());
            try {
                wheel.setInt(mui.getContext(), amount);
                result.setBoolean(
                    "consumed",
                    mui.onMouseScroll(
                        amount > 0 ? com.cleanroommc.modularui.api.UpOrDown.UP
                            : com.cleanroommc.modularui.api.UpOrDown.DOWN,
                        Math.abs(amount)));
                result.setBoolean("nativeAnimating", data.isAnimating());
                result.setInteger("nativeAnimatingTo", data.getAnimatingTo());
                result.setInteger("immediateScroll", data.getScroll());
            } finally {
                wheel.setInt(mui.getContext(), previous);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot stage the pinned MUI2 wheel input", failure);
        }
        return result;
    }

    private static ModularPanel preparePointer(HologramScreen screen, int mouseX, int mouseY) {
        ModularScreen mui = screen.getScreen();
        mui.getContext()
            .updateState(mouseX, mouseY, 0);
        mui.onFrameUpdate();
        for (ModularPanel panel : mui.getPanelManager()
            .getOpenPanels()) if ("gtmb_material_confirmation".equals(panel.getName())) return panel;
        throw new IllegalStateException("The material input target is not in the actual open MUI panels");
    }

    private static int[] point(HologramScreen screen, int id) {
        int[] point = screen.ui.review.materialControlPoint(id);
        return point == null ? new int[0] : point;
    }

    private static void requireProbe() {
        if (!Boolean.getBoolean("gtmb.hologram.materialVisualSmoke")) HologramUiDiagnostics.requireInteractionSmoke();
    }
}
