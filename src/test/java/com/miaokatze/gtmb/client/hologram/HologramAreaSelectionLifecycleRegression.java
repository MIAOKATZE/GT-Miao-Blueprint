// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** A local finish can be cancelled independently of the player's editable selection. */
public final class HologramAreaSelectionLifecycleRegression {

    private HologramAreaSelectionLifecycleRegression() {}

    public static void run() {
        try {
            runChecked();
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("Cannot exercise the local area lifecycle", failure);
        }
    }

    private static void runChecked() throws ReflectiveOperationException, java.io.IOException {
        Field area = field("localArea");
        Field view = field("localAreaView");
        Field finishing = field("localAreaFinishing");
        HologramState previousState = HologramClient.state;
        Object previousArea = area.get(null), previousView = view.get(null);
        boolean previouslyFinishing = finishing.getBoolean(null);
        try {
            HologramClient.state = new HologramState(new NBTTagCompound());
            HologramAreaSelection selection = new HologramAreaSelection();
            selection.worldPoint(1, 0, 64, 0);
            selection.worldPoint(2, 2, 64, 2);
            area.set(null, selection);
            view.set(null, selection.snapshot());
            finishing.setBoolean(null, true);
            SimpleAssert.that(HologramClient.blueprintInputBusy(), "a local selection owns the world input");

            HologramClient.pauseUnsubmittedLocalAreaFinish();
            SimpleAssert.that(!finishing.getBoolean(null), "Escape retires the pending local capture");
            SimpleAssert.that(area.get(null) == selection, "Escape leaves the original selection editable");
            selection.deleteLatest();
            SimpleAssert.that(!selection.hasSelectionContent(), "the retained selection accepts Delete");
            selection.undoRegion();
            SimpleAssert.that(selection.complete(), "the retained selection accepts undo");

            Method cancel = HologramClient.class.getDeclaredMethod("worldAction", String.class);
            cancel.setAccessible(true);
            cancel.invoke(null, "cancelAreaSelection");
            SimpleAssert.that(!HologramClient.blueprintInputBusy(), "discard releases local input without a receipt");
            SimpleAssert.that(view.get(null) == null, "discard also releases its cached overlay");
            SimpleAssert.eq(
                HologramCutInput.Intent.SWITCHER,
                HologramCutInput
                    .right(HologramCutInput.Stage.IDLE, true, false, HologramClient.blueprintInputBusy(), false, false),
                "the next Shift-right can open the mode selector");
        } finally {
            HologramClient.state = previousState;
            area.set(null, previousArea);
            view.set(null, previousView);
            finishing.setBoolean(null, previouslyFinishing);
        }
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = HologramClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
