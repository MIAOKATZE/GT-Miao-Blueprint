// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Freeze the editable attachment's source and pose without inflating its bitmap on a server tick. */
final class HologramAttachmentSnapshot {

    private static final String SOURCE = "_gtmbAttachmentSource";

    private HologramAttachmentSnapshot() {}

    static void capture(NBTTagCompound row, HologramPresets.Structure saved) {
        if (saved.textBoxNbt == null || saved.textBoxNbt.isEmpty()) return;
        // The string is immutable. The pose in the label is absolute; the editor definition uses local coordinates.
        NBTTagCompound source = new NBTTagCompound();
        source.setString("data", saved.textBoxNbt);
        source.setInteger("x", saved.x);
        source.setInteger("y", saved.y);
        source.setInteger("z", saved.z);
        row.setTag(SOURCE, source);
    }

    /** Only the owner of the detached snapshot may replace these private labels before wire encoding. */
    static void expand(NBTTagCompound state, BooleanSupplier active) throws IOException {
        NBTTagList rows = state.getTagList("structures", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (!row.hasKey(SOURCE, 10)) continue;
            if (!active.getAsBoolean()) throw new IOException("Attachment snapshot cancelled");
            NBTTagCompound source = row.getCompoundTag(SOURCE);
            row.removeTag(SOURCE);
            try {
                // Text and picture definitions contain editor properties, not world or registry-backed ItemStacks.
                NBTTagCompound definition = HologramBlueprintNbt.decodeData(source.getString("data"));
                definition.setString("id", row.getString("id"));
                for (String axis : new String[] { "x", "y", "z" }) definition.setInteger(axis, source.getInteger(axis));
                row.setTag("textBox", definition);
            } catch (IOException invalid) {
                row.setString("textBoxError", "文字定义损坏");
            }
        }
    }
}
