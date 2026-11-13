// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Calls the actual native button predicate using a server-accepted attachment snapshot. */
public final class HologramUnsavedPlacementUiRegression {

    private HologramUnsavedPlacementUiRegression() {}

    public static void run(NBTTagCompound acceptedAttachment) throws ReflectiveOperationException {
        Field singleton = null;
        for (Field field : net.minecraft.client.Minecraft.class.getDeclaredFields())
            if (field.getType() == net.minecraft.client.Minecraft.class
                && java.lang.reflect.Modifier.isStatic(field.getModifiers())) singleton = field;
        if (singleton == null) throw new NoSuchFieldException("Minecraft singleton");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        try {
            singleton
                .set(null, ((sun.misc.Unsafe) unsafe.get(null)).allocateInstance(net.minecraft.client.Minecraft.class));
            verify(acceptedAttachment);
        } finally {
            singleton.set(null, previous);
        }
    }

    private static void verify(NBTTagCompound acceptedAttachment) throws ReflectiveOperationException {
        HologramUiModules ui = new HologramUiModules(null);
        HologramState snapshot = new HologramState(acceptedAttachment);
        Field state = HologramUiSession.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(ui.session, snapshot);
        SimpleAssert.that(
            snapshot.data.getCompoundTag("textPlacementAck")
                .getBoolean("accepted"),
            "the button fixture originates from a server-accepted attachment");
        snapshot.data.setBoolean("blueprintProcessingRequired", true);
        snapshot.data.setBoolean("blueprintProcessingComplete", false);
        SimpleAssert.that(
            !ui.session.isTutorial() && !ui.processing.processingReady(),
            "the actual production session has freshly invalidated processing metadata");
        SimpleAssert.that(
            ui.library.nativeCanPlaceCurrent(),
            "current-draft button stays enabled while server distinguishes generated geometry from reviewable data");
        for (String pending : new String[] { "attachmentPending", "currentPlacementPending", "localPlanPending",
            "capturePending", "snapshotPending", "blueprintSavePending" }) {
            snapshot.data.setBoolean(pending, true);
            SimpleAssert.that(!ui.library.nativeCanPlaceCurrent(), "button rejects unfinished work: " + pending);
            snapshot.data.setBoolean(pending, false);
        }
    }
}
