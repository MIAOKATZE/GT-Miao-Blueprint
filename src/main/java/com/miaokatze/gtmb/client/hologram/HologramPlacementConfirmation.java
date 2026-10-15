package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Client review policy; the server owns the second-confirmation capability and material transaction. */
final class HologramPlacementConfirmation {

    private HologramPlacementConfirmation() {}

    static boolean lockedBlueprint(NBTTagCompound state) {
        return state.getBoolean("virtual") && state.getBoolean("virtualPlacement") && state.getBoolean("virtualLocked");
    }

    static boolean canBuild(NBTTagCompound state, long reviewedRevision) {
        return reviewedRevision == state.getLong("planRevision") && networksReady(state)
            && (!state.getBoolean("virtual") || lockedBlueprint(state))
            && (state.getInteger("missing") == 0 || lockedBlueprint(state));
    }

    static boolean networksReady(NBTTagCompound state) {
        NBTTagList groups = state.getCompoundTag("networkBindings")
            .getTagList("groups", 10);
        for (int i = 0; i < groups.tagCount(); i++) if (!groups.getCompoundTagAt(i)
            .getBoolean("bound")) return false;
        return true;
    }

    static boolean forceReview(NBTTagCompound state, long reviewedRevision) {
        return lockedBlueprint(state) && state.getBoolean("forcePlacementReady")
            && reviewedRevision == state.getLong("planRevision");
    }
}
