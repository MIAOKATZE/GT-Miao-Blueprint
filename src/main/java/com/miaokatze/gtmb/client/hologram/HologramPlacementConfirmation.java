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
        // The server reports target validity, without an extra terminal-security gate.
        // Preserved groups need only the original world's reachable anchor, never a carried terminal.
        NBTTagList groups = state.getCompoundTag("networkBindings")
            .getTagList("groups", 10);
        for (int i = 0; i < groups.tagCount(); i++) if (!groups.getCompoundTagAt(i)
            .getBoolean("bound")) return false;
        return true;
    }

    static boolean needsTerminalBinding(NBTTagCompound state) {
        NBTTagList groups = state.getCompoundTag("networkBindings")
            .getTagList("groups", 10);
        for (int i = 0; i < groups.tagCount(); i++) {
            NBTTagCompound group = groups.getCompoundTagAt(i);
            if (group.getBoolean("terminalBinding") && !group.getBoolean("bound")) return true;
        }
        return false;
    }

    static int[] modalBounds(NBTTagCompound state) {
        return new int[] { 40, 58, 376, state.getCompoundTag("networkBindings")
            .getTagList("groups", 10)
            .tagCount() > 0 ? 280 : 254 };
    }

    static boolean forceReview(NBTTagCompound state, long reviewedRevision) {
        return lockedBlueprint(state) && state.getBoolean("forcePlacementReady")
            && reviewedRevision == state.getLong("planRevision");
    }

    static NBTTagList cacheResources(NBTTagCompound state) {
        NBTTagList result = new NBTTagList(), resources = state.getTagList("blueprintResources", 10);
        for (int i = 0; i < resources.tagCount(); i++) {
            NBTTagCompound row = resources.getCompoundTagAt(i);
            String key = row.getString("key");
            if (row.getBoolean("optionalCache")
                && ("cache.EU".equals(key) || "cache.AE".equals(key) || row.getBoolean("emptyFallback")))
                result.appendTag(row.copy());
        }
        return result;
    }

    static boolean emptyCacheFallback(NBTTagCompound state) {
        NBTTagList rows = cacheResources(state);
        for (int i = 0; i < rows.tagCount(); i++) if (rows.getCompoundTagAt(i)
            .getBoolean("emptyFallback")) return true;
        return false;
    }

    static String cacheSummary(NBTTagCompound state) {
        NBTTagList rows = cacheResources(state);
        if (rows.tagCount() == 0) return "";
        NBTTagCompound selected = rows.getCompoundTagAt(0);
        for (int i = 0; i < rows.tagCount(); i++) if ("cache.EU".equals(
            rows.getCompoundTagAt(i)
                .getString("key"))) {
                    selected = rows.getCompoundTagAt(i);
                    break;
                }
        String key = selected.getString("key");
        if (!"cache.EU".equals(key) && !"cache.AE".equals(key)) return "设备缓存无法付取，将以空缓存放置";
        return ("cache.EU".equals(key) ? "EU" : "AE") + " 缓存需 "
            + selected.getString("required")
            + " / 可用 "
            + selected.getString("available");
    }

    static NBTTagCompound cacheReviewSnapshot(NBTTagCompound state) {
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("session", state.getString("session"));
        snapshot.setLong("planRevision", state.getLong("planRevision"));
        snapshot.setLong("placementReviewNonce", state.getLong("placementReviewNonce"));
        snapshot.setTag("cacheResources", cacheResources(state));
        return snapshot;
    }

    static boolean cacheReviewMatches(NBTTagCompound state, NBTTagCompound reviewed) {
        return !emptyCacheFallback(state) || reviewed != null && reviewed.equals(cacheReviewSnapshot(state));
    }

    static String confirmationAction(NBTTagCompound state, long reviewedRevision, boolean placement) {
        if (emptyCacheFallback(state))
            return forceReview(state, reviewedRevision) ? "仅放已有 · 空缓存" : placement ? "确认空缓存放置" : "确认空缓存施工";
        return forceReview(state, reviewedRevision) ? "再次确认仅放已有" : placement ? "确认放置" : "确认执行施工";
    }
}
