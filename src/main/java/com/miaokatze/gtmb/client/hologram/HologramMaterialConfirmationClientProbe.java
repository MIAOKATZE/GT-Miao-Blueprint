package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

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
        HologramMuiProbeInput.click(screen, position[0], position[1], 0);
    }

    static void scroll(HologramScreen screen, int amount) {
        requireProbe();
        int[] position = point(screen, 168);
        if (position.length != 2) throw new IllegalStateException("Material dialog has not rendered");
        // The list sits above the first action row. Derive the same panel transform as the observed control point.
        int height = screen.getScreen()
            .getScreenArea().height;
        int width = screen.getScreen()
            .getScreenArea().width;
        float scale = Math.min(1f, Math.min(width / 644f, height / 458f));
        com.cleanroommc.modularui.screen.ModularScreen mui = screen.getScreen();
        mui.getContext()
            .updateState(position[0], position[1] - Math.round(160 * scale), 0);
        mui.onFrameUpdate();
        // MUI2 2.3.92 reads wheel from its context, not onMouseScroll's amount argument.
        // Stage that raw input only for this opt-in probe, then restore the context immediately.
        try {
            java.lang.reflect.Field wheel = com.cleanroommc.modularui.screen.viewport.GuiContext.class
                .getDeclaredField("mouseWheel");
            wheel.setAccessible(true);
            int previous = wheel.getInt(mui.getContext());
            try {
                wheel.setInt(mui.getContext(), amount);
                mui.onMouseScroll(
                    amount > 0 ? com.cleanroommc.modularui.api.UpOrDown.UP
                        : com.cleanroommc.modularui.api.UpOrDown.DOWN,
                    Math.abs(amount));
            } finally {
                wheel.setInt(mui.getContext(), previous);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot stage the pinned MUI2 wheel input", failure);
        }
    }

    private static int[] point(HologramScreen screen, int id) {
        int[] point = screen.ui.review.materialControlPoint(id);
        return point == null ? new int[0] : point;
    }

    private static void requireProbe() {
        if (!Boolean.getBoolean("gtmb.hologram.materialVisualSmoke")) HologramUiDiagnostics.requireInteractionSmoke();
    }
}
