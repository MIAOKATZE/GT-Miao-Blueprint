// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exact legacy receipt settlement, independent of a scan's UI token or a new artifact transfer. */
final class HologramLegacySaveTaskIdentityRegression {

    private HologramLegacySaveTaskIdentityRegression() {}

    static void run() throws ReflectiveOperationException {
        var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) access.get(null);
        var world = HologramPlacementFixtureWorld.create(unsafe);
        EntityPlayerMP player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "LegacySaveTask");
        HologramSession owner = new HologramSession(player, 0, 64, 0, false, 2);
        var sessions = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessions.setAccessible(true);
        var results = HologramLibraryFacade.class.getDeclaredField("savedResults");
        results.setAccessible(true);
        var remember = HologramLibraryFacade.class
            .getDeclaredMethod("rememberSavedResult", EntityPlayerMP.class, NBTTagCompound.class);
        remember.setAccessible(true);
        try {
            HologramWorkspace.activate(player.getUniqueID(), owner);
            NBTTagCompound old = stage(owner, 79L, false);
            remember.invoke(null, player, old);
            owner.blueprintClientTaskToken = 0L; // An ordinary scan may retire UI correlation independently.
            for (int form = 0; form < 4; form++) {
                NBTTagCompound stale = (NBTTagCompound) old.copy();
                switch (form) {
                    case 0 -> stale.removeTag("clientTaskToken");
                    case 1 -> stale.setLong("clientTaskToken", 0L);
                    case 2 -> stale.setLong("clientTaskToken", 83L);
                    case 3 -> stale.setInteger("clientTaskToken", 79);
                    default -> throw new AssertionError("fixture token form");
                }
                HologramLibraryFacade.resolveSavedResult(player, stale, true);
                SimpleAssert.that(!HologramLibraryWorkflow.acknowledgeBlueprintSave(owner, stale)
                    && owner.blueprintSaveReceipt.equals(old.getString("saveReceipt")) && owner.blueprintDirty,
                    "wrong or ill-typed task cannot ACK or cancel the exact staged old-verifier receipt: " + form);
            }
            SimpleAssert.that(HologramLibraryWorkflow.acknowledgeBlueprintSave(owner, old)
                && owner.blueprintSaveReceipt.isEmpty() && !owner.blueprintDirty,
                "the original positive token still ACKs even after unrelated UI correlation changes");

            NBTTagCompound pending = stage(owner, 91L, true);
            NBTTagCompound unbound = (NBTTagCompound) pending.copy();
            unbound.setString("previewSha256", "");
            remember.invoke(null, player, unbound);
            owner.blueprintClientTaskToken = 90L;
            NBTTagCompound wrongCancel = (NBTTagCompound) pending.copy();
            wrongCancel.setLong("clientTaskToken", 90L);
            HologramLibraryFacade.resolveSavedResult(player, wrongCancel, true);
            SimpleAssert.that(HologramLibraryWorkflow.acknowledgeBlueprintSave(owner, pending)
                && owner.blueprintSaveReceipt.isEmpty() && !owner.blueprintDirty,
                "a staged positive client-only PNG receipt and its exact disk ACK remain valid");

            NBTTagCompound cancelled = stage(owner, 111L, false);
            remember.invoke(null, player, cancelled);
            owner.blueprintClientTaskToken = 112L;
            HologramLibraryFacade.resolveSavedResult(player, cancelled, true);
            SimpleAssert.that(owner.blueprintSaveReceipt.isEmpty() && owner.blueprintDirty
                && !HologramLibraryWorkflow.acknowledgeBlueprintSave(owner, cancelled),
                "the same exact old token can cancel instead, and a late ACK cannot revive it");

            for (boolean explicitZero : new boolean[] { false, true }) {
                NBTTagCompound legacy = stage(owner, 0L, false);
                if (!explicitZero) legacy.removeTag("clientTaskToken");
                remember.invoke(null, player, legacy);
                SimpleAssert.that(HologramLibraryWorkflow.acknowledgeBlueprintSave(owner, legacy)
                    && owner.blueprintSaveReceipt.isEmpty() && !owner.blueprintDirty,
                    "the actual token-zero compatibility receipt still ACKs, including omitted token");
            }
            SimpleAssert.eq(0, world.writes().size(), "receipt bookkeeping does not write world blocks");
        } finally {
            HologramSnapshotPreparation.cancel(owner);
            ((Map<?, ?>) results.get(null)).remove(player.getUniqueID());
            ((Map<?, ?>) sessions.get(null)).remove(player.getUniqueID());
            HologramEditorWorkspaceRegistry.forget(player.getUniqueID());
            player.playerNetServerHandler.netManager.channel().close();
        }
    }

    private static NBTTagCompound stage(HologramSession owner, long task, boolean local) {
        owner.blueprintClientTaskToken = task;
        owner.blueprintSaveReceipt = UUID.randomUUID().toString();
        owner.blueprintSaveLocalId = "local_" + UUID.randomUUID();
        owner.blueprintSaveRevision = owner.planRevision;
        owner.blueprintSaveJsonSha256 = HologramBlueprintArtifact.hash(new byte[] { 1, 2, 3 });
        owner.blueprintSavePreviewSha256 = local ? "" : HologramBlueprintArtifact.hash(new byte[] { 4, 5, 6 });
        owner.blueprintDirty = true;
        NBTTagCompound proof = new NBTTagCompound();
        proof.setString("op", "ackBlueprintSaved");
        proof.setString("session", owner.id);
        proof.setLong("planRevision", owner.blueprintSaveRevision);
        proof.setString("saveReceipt", owner.blueprintSaveReceipt);
        proof.setString("localId", owner.blueprintSaveLocalId);
        proof.setLong("clientTaskToken", task);
        proof.setString("jsonSha256", owner.blueprintSaveJsonSha256);
        proof.setString("previewSha256", HologramBlueprintArtifact.hash(new byte[] { 4, 5, 6 }));
        proof.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
        proof.setBoolean("localSaveReceipt", local);
        return proof;
    }
}
