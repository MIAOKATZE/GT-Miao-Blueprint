// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

/** Outbound presentation port. Payloads are snapshots, never server operation authority. */
interface HologramSnapshotSink {

    void send(EntityPlayerMP recipient, NBTTagCompound snapshot);
}
