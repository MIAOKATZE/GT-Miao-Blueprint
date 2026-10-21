package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Read-only input for the shared raw-preview widget. Implementations retain ownership of transport and raw sessions.
 */
interface HologramCutStoragePreviewView {

    HologramCutRawPreviewSession renderer();

    NBTTagCompound raw();

    String notice();
}
