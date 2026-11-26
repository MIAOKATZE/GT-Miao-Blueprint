// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

import appeng.api.storage.StorageName;
import appeng.parts.reporting.PartPatternTerminal;
import appeng.tile.inventory.IAEStackInventory;

/** The reviewed Ex geometry, measured from the target's inventories rather than inlined AE constants. */
final class HologramAeTerminalCapabilities {

    final int inputs;
    final int outputs;
    final int pages;
    final boolean extended;

    private HologramAeTerminalCapabilities(int inputs, int outputs, int pages, boolean extended) {
        this.inputs = inputs;
        this.outputs = outputs;
        this.pages = pages;
        this.extended = extended;
    }

    static HologramAeTerminalCapabilities inspect(PartPatternTerminal terminal) throws IOException {
        IAEStackInventory inputs = terminal.getAEInventoryByName(StorageName.CRAFTING_INPUT);
        IAEStackInventory outputs = terminal.getAEInventoryByName(StorageName.CRAFTING_OUTPUT);
        if (inputs == null || outputs == null) throw new IOException("AE pattern terminal inventories are unavailable");
        return measured(
            inputs.getSizeInventory(),
            outputs.getSizeInventory(),
            terminal.getClass()
                .getName());
    }

    static HologramAeTerminalCapabilities measured(int inputs, int outputs, String type) throws IOException {
        if (type.equals("appeng.parts.reporting.PartPatternTerminal") && inputs == 9 && outputs == 3)
            return new HologramAeTerminalCapabilities(inputs, outputs, 1, false);
        if (type.equals("appeng.parts.reporting.PartPatternTerminalEx") && inputs == outputs
            && (inputs == 32 || inputs == 64))
            return new HologramAeTerminalCapabilities(inputs, outputs, inputs / 16, true);
        throw new IOException("Unreviewed AE pattern terminal geometry: " + type + " " + inputs + "/" + outputs);
    }

    int effectiveInputs(boolean inverted) {
        return extended && inverted ? inputs / 4 : inputs;
    }

    int effectiveOutputs(boolean inverted) {
        return extended && !inverted ? outputs / 4 : outputs;
    }

    /** Must run before any native reader: Ex.setInverted and old inventory readers silently discard high slots. */
    void requireRepresentable(NBTTagCompound data) throws IOException {
        if (!extended) return;
        if (data.hasKey("inverted")
            && (!data.hasKey("inverted", 1) || data.getByte("inverted") != 0 && data.getByte("inverted") != 1))
            throw new IOException("AE Ex inverted must be a native boolean");
        if (data.hasKey("activePage") && (!data.hasKey("activePage", 3) || data.getInteger("activePage") < 0
            || data.getInteger("activePage") >= pages))
            throw new IOException("AE Ex activePage cannot be represented by this target's " + pages + " pages");
        boolean inverted = data.getBoolean("inverted");
        requireSlots(data, "craftingGrid", effectiveInputs(inverted));
        requireSlots(data, "outputList", effectiveOutputs(inverted));
    }

    private static void requireSlots(NBTTagCompound data, String root, int capacity) throws IOException {
        if (!data.hasKey(root, 10)) return; // The ordinary inventory validator diagnoses malformed roots.
        NBTTagCompound inventory = data.getCompoundTag(root);
        for (Object raw : inventory.func_150296_c()) {
            String key = (String) raw;
            if (!key.matches("#(?:0|[1-9][0-9]*)")) continue;
            long index;
            try {
                index = Long.parseLong(key.substring(1));
            } catch (NumberFormatException invalid) {
                throw new IOException("AE Ex slot index exceeds target capacity: " + root + "/" + key, invalid);
            }
            if (index >= capacity && (!inventory.hasKey(key, 10) || !inventory.getCompoundTag(key)
                .hasNoTags()))
                throw new IOException(
                    "AE Ex slot cannot be represented by this target/orientation: " + root
                        + "/"
                        + key
                        + " (capacity "
                        + capacity
                        + ")");
        }
    }
}
