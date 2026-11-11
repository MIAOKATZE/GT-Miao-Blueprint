// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

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
