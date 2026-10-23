package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real decoded state provenance must survive metadata stripping without admitting partial task handoffs. */
public final class HologramAreaWorkspaceReceiptRegression {

    public static void run() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "projection-A");
        source.setString("editorWorkspace", "projection");
        source.setLong("editorWorkspaceEpoch", 4);
        HologramState previous = new HologramState(source);
        NBTTagCompound completed = new NBTTagCompound();
        completed.setString("session", "blueprint-B");
        completed.setString("generation", "blueprint-B");
        completed.setLong("planRevision", 7);
        completed.setString("editorWorkspacePreviousSession", "projection-A");
        completed.setString("editorWorkspace", "blueprint");
        completed.setString("blueprintSource", "area");
        completed.setLong("editorWorkspaceEpoch", 5);
        completed.setLong("uiSequence", 12);
        completed.setTag("cells", new NBTTagList());
        HologramState next = new HologramState(completed);
        SimpleAssert.that(!next.data.hasKey("cells"), "geometry is intentionally absent from decoded metadata");
        SimpleAssert.that(next.completeBaseline(), "an explicitly empty full baseline keeps its provenance");
        SimpleAssert.that(advance(previous, next), "completed area import can advance its exact pending task");
        SimpleAssert.that(
            !HologramBackgroundTasks.advancesAreaWorkspace(previous, next, "other", 12),
            "another session cannot take the task");
        SimpleAssert.that(
            !HologramBackgroundTasks.advancesAreaWorkspace(previous, next, "projection-A", 11),
            "another command cannot complete the task");
        SimpleAssert.that(
            advance(previous, next.withMetadata(new NBTTagCompound())),
            "client-side visual decoration preserves the incoming full baseline fact");
        NBTTagCompound partial = new NBTTagCompound();
        partial.setBoolean("partial", true);
        partial.setString("session", "blueprint-B");
        partial.setString("generation", "blueprint-B");
        partial.setLong("baseRevision", 7);
        partial.setLong("planRevision", 8);
        partial.setInteger("cellCount", 0);
        SimpleAssert.that(
            !advance(previous, HologramState.apply(next, partial)),
            "a new wire patch cannot inherit the earlier full-baseline provenance");
        for (String marker : new String[] { "partial", "areaSelectionOnly", "displayPartial", "areaSelectionShell" }) {
            NBTTagCompound receipt = (NBTTagCompound) completed.copy();
            receipt.setBoolean(marker, true);
            SimpleAssert.that(!advance(previous, new HologramState(receipt)), marker + " is not a full task receipt");
        }
        NBTTagCompound noGeometry = (NBTTagCompound) completed.copy();
        noGeometry.removeTag("cells");
        SimpleAssert.that(!advance(previous, new HologramState(noGeometry)), "a UI receipt has no geometry baseline");
        completed.setLong("editorWorkspaceEpoch", 4);
        SimpleAssert.that(!advance(previous, new HologramState(completed)), "an old workspace cannot finish new work");
    }

    private static boolean advance(HologramState previous, HologramState next) {
        return HologramBackgroundTasks.advancesAreaWorkspace(previous, next, "projection-A", 12);
    }
}
