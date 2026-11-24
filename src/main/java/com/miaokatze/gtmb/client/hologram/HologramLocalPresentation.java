// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;

/** One local draft's last accepted CPU view. A new screen and renderer must publish/present it again. */
final class HologramLocalPresentation {

    private static final String[] PENDING = { "partial", "displayPartial", "capturePending", "snapshotPending",
        "areaImporting", "areaSelectionShell", "attachmentPending", "localPlanPending", "currentPlacementPending",
        "blueprintSavePending", "discardConfirmationRequired", "virtualPlacement", "placementValidating" };

    private final HologramLocalBlueprintDocument document;
    private final HologramState.LocalGeometry geometry;
    private final String session;
    private final long revision, resources;
    private final Object world, connection, player;
    private final int dimension;

    private HologramLocalPresentation(HologramLocalBlueprintDocument document, HologramState geometry, Object world,
        Object connection, Object player, int dimension, long resources) {
        this.document = document;
        this.geometry = new HologramState.LocalGeometry(geometry);
        session = geometry.session;
        revision = geometry.data.getLong("planRevision");
        this.world = world;
        this.connection = connection;
        this.player = player;
        this.dimension = dimension;
        this.resources = resources;
    }

    static HologramLocalPresentation capture(HologramLocalBlueprintDocument document, HologramState view, Object world,
        Object connection, Object player, int dimension, long resources, boolean idle) {
        if (!idle || document == null
            || view == null
            || world == null
            || connection == null
            || player == null
            || !view.data.getBoolean("localBlueprintDocument")
            || !view.data.getBoolean("virtual")
            || !view.data.getBoolean("blueprintEditing")
            || view.data.getBoolean("localStandardController")
            || !view.data.hasKey("dimension", 3)
            || view.data.getInteger("dimension") != dimension
            || !view.data.hasKey("planRevision", 4)
            || view.data.getInteger("job") != 0
            || !document.fileId()
                .equals(view.data.getString("libraryLocalId"))
            || !document.ownsPresentation(view.session, view.data.getLong("planRevision"), view.cells.size()))
            return null;
        for (String flag : PENDING) if (view.data.getBoolean(flag)) return null;
        return new HologramLocalPresentation(document, view, world, connection, player, dimension, resources);
    }

    boolean current(HologramLocalBlueprintDocument document, Object world, Object connection, Object player,
        int dimension, long resources) {
        return this.document == document && sameRevision()
            && this.world == world
            && this.connection == connection
            && this.player == player
            && this.dimension == dimension
            && this.resources == resources;
    }

    boolean sameRevision() {
        return document.revision() == revision;
    }

    HologramState rebase(NBTTagCompound fresh) {
        if (!sameRevision() || !session.equals(fresh.getString("session"))
            || !document.fileId()
                .equals(fresh.getString("libraryLocalId"))
            || fresh.getLong("planRevision") != revision) return null;
        fresh.setInteger("dimension", dimension);
        return geometry.rebase(fresh);
    }
}
