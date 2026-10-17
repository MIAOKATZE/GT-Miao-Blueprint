package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** Detached presentation values. Never retains a screen, widget, host, module, or action callback. */
final class HologramBackgroundUiState {

    final String session;
    final long token;
    private final NBTTagCompound values;

    HologramBackgroundUiState(String session, long token, NBTTagCompound values) {
        this.session = session;
        this.token = token;
        this.values = (NBTTagCompound) values.copy();
    }

    static HologramBackgroundUiState capture(HologramUiModules ui, long token) {
        NBTTagCompound values = new NBTTagCompound();
        values.setTag("library", ui.library.backgroundUiState());
        values.setTag("configuration", ui.config.backgroundUiState());
        values.setTag("preview", ui.preview.backgroundUiState());
        values.setTag("processing", ui.processing.backgroundUiState());
        values.setTag("review", ui.review.backgroundUiState());
        values.setTag("ae", ui.ae.backgroundUiState());
        values.setTag("session", ui.session.backgroundUiState());
        return new HologramBackgroundUiState(ui.session.state().session, token, values);
    }

    boolean matches(String session, long token) {
        return this.token == token && this.session.equals(session);
    }

    NBTTagCompound values() {
        return (NBTTagCompound) values.copy();
    }

    void restore(HologramUiModules ui) {
        if (!session.equals(ui.session.state().session)) return;
        // All fields were freshly built by the normal screen lifecycle before restoring their values.
        NBTTagCompound copy = values();
        ui.library.restoreBackgroundUiState(copy.getCompoundTag("library"));
        ui.preview.restoreBackgroundUiState(copy.getCompoundTag("preview"));
        ui.config.restoreBackgroundUiState(copy.getCompoundTag("configuration"));
        ui.processing.restoreBackgroundUiState(copy.getCompoundTag("processing"));
        ui.review.restoreBackgroundUiState(copy.getCompoundTag("review"));
        ui.ae.restoreBackgroundUiState(copy.getCompoundTag("ae"));
        ui.session.restoreBackgroundUiState(copy.getCompoundTag("session"));
    }
}
