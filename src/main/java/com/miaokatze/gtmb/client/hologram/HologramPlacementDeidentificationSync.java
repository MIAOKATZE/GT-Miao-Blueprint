package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** One preference application per authoritative draft revision; this never confirms construction. */
final class HologramPlacementDeidentificationSync {

    private String session = "";
    private long revision = -1;
    private int level = -1;

    boolean shouldRestore(NBTTagCompound snapshot, int desiredLevel) {
        if (!snapshot.getBoolean("virtualPlacement")) {
            clear();
            return false;
        }
        // Applying preferences must not reset an in-progress or completed placement job.
        if (snapshot.getInteger("job") != 0 || snapshot.getBoolean("areaImporting")
            || snapshot.getBoolean("capturePending")
            || snapshot.getBoolean("targetClosed")) return false;
        if (snapshot.getBoolean("placementDeidentificationChosen")
            && snapshot.getInteger("placementDeidentificationLevel") >= desiredLevel) return false;
        if (snapshot.getString("session")
            .equals(session) && snapshot.getLong("planRevision") == revision
            && level == desiredLevel) return false;
        requested(snapshot, desiredLevel);
        return true;
    }

    void requested(NBTTagCompound snapshot, int desiredLevel) {
        session = snapshot.getString("session");
        revision = snapshot.getLong("planRevision");
        level = desiredLevel;
    }

    void clear() {
        session = "";
        revision = -1;
        level = -1;
    }
}
