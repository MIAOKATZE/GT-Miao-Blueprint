// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import java.math.BigDecimal;
import java.math.BigInteger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Client review policy; the server verifies the displayed plan and owns the material transaction. */
final class HologramPlacementConfirmation {

    private HologramPlacementConfirmation() {}

    /** Without a frozen construction scope, require all interaction details. */
    static String localStructureReviewBlocker(HologramState state) {
        return localStructureReviewBlocker(state, null, true);
    }

    static String localStructureReviewBlocker(HologramState state, NBTTagCompound settings, boolean localEdits) {
        NBTTagCompound data = state.data;
        if (!data.getBoolean("localStandardController")) return "";
        if (data.getBoolean("localStructureUnavailable") || !data.getBoolean("supported"))
            return projectorOr("stock.unavailable", "当前控制器没有可用于施工的原生结构。");
        if (data.getBoolean("localPlanPending")) return projectorOr("stock.updating_plan", "正在更新计划");
        if (finishedWholeNativePlacement(state, settings, localEdits))
            return projectorOr("stock.already_built", "结构已建成。");
        if ((data.getBoolean("capturePending") || !data.getBoolean("projectionDataComplete"))
            && !wholeNativeTargetReady(state, settings, localEdits))
            return projectorOr("stock.reading_structure", "结构未就绪");
        if (state.cells.isEmpty()) return projectorOr("stock.no_structure_cells", "当前控制器没有可用于施工的结构格。");
        return "";
    }

    static boolean finishedWholeNativePlacement(HologramState state, NBTTagCompound settings, boolean localEdits) {
        return state.data.getBoolean("nativeBuildSatisfied") && wholeNativeScope(state.data, settings);
    }

    private static boolean wholeNativeScope(NBTTagCompound state, NBTTagCompound settings) {
        return settings != null && state.getBoolean("localStandardController")
            && state.getInteger("mode") == 0
            && settings.hasKey("mode", 3)
            && settings.getInteger("mode") == 0
            && settings.hasKey("scope", 3)
            && settings.getInteger("scope") == 0
            && settings.getIntArray("selectedIndices").length == 0
            && settings.getString("structureId")
                .isEmpty()
            && (!state.getBoolean("presetActive") || state.getBoolean("nativePresetExact"));
    }

    /** Candidate enrichment does not change the sealed target or its construction ledger. */
    static boolean wholeNativeTargetReady(HologramState state, NBTTagCompound settings, boolean localEdits) {
        NBTTagCompound data = state.data;
        return !localEdits && wholeNativeScope(data, settings)
            && !data.getBoolean("presetActive")
            && data.getBoolean("target")
            && data.getBoolean("supported")
            && !data.getBoolean("virtual")
            && !data.getBoolean("controllerOpening")
            && !data.getBoolean("targetClosed")
            && !data.getBoolean("localStructureUnavailable")
            && !data.getBoolean("localPlanPending")
            && data.hasKey("siteOccupied", 3)
            && data.hasKey("siteUnknown", 3)
            && data.getInteger("siteUnknown") == 0
            && (!data.hasKey("siteNativeUnknown", 3) || data.getInteger("siteNativeUnknown") == 0)
            && !state.cells.isEmpty()
            && state.controllerMaterialCounts != null
            && state.localMaterialIndex != null
            && state.localMaterialIndex.current();
    }

    /** A candidate-only publication may replace cell wrappers, but never the captured target and ledger. */
    static boolean sameWholeNativeTarget(HologramState before, HologramState now) {
        if (before == null || now == null
            || before.controllerMaterialCounts == null
            || before.localMaterialIndex == null
            || before.controllerMaterialCounts != now.controllerMaterialCounts
            || before.localMaterialIndex != now.localMaterialIndex
            || !before.session.equals(now.session)
            || before.cells.size() != now.cells.size()) return false;
        NBTTagCompound from = before.data, to = now.data;
        String key = from.getString("generation");
        return !key.isEmpty() && key.equals(to.getString("generation"))
            && from.getLong("planRevision") == to.getLong("planRevision")
            && from.getBoolean("mainExplicit") == to.getBoolean("mainExplicit")
            && from.getInteger("dimension") == to.getInteger("dimension")
            && from.getInteger("x") == to.getInteger("x")
            && from.getInteger("y") == to.getInteger("y")
            && from.getInteger("z") == to.getInteger("z");
    }

    static boolean lockedBlueprint(NBTTagCompound state) {
        return state.getBoolean("virtual") && state.getBoolean("virtualPlacement") && state.getBoolean("virtualLocked");
    }

    static boolean canBuild(NBTTagCompound state, long reviewedRevision) {
        return reviewedRevision == state.getLong("planRevision") && networksReady(state)
            && (!state.getBoolean("structureProjection") || state.getBoolean("operationPrepared"))
            && voucherPaymentReady(state)
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
        return lockedBlueprint(state) && state.getInteger("missing") > 0
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
        NBTTagCompound selected = cacheSummaryResource(state);
        if (selected == null) return "";
        String key = selected.getString("key");
        if (!"cache.EU".equals(key) && !"cache.AE".equals(key)) return "设备缓存无法付取，将以空缓存放置";
        return ("cache.EU".equals(key) ? "EU" : "AE") + " 缓存需 "
            + selected.getString("required")
            + " / 可用 "
            + selected.getString("available");
    }

    static int cacheSummaryColor(NBTTagCompound state) {
        NBTTagCompound selected = cacheSummaryResource(state);
        if (selected == null) return HologramMuiTheme.TEXT;
        String key = selected.getString("key");
        if (!"cache.EU".equals(key) && !"cache.AE".equals(key)) return HologramUiAccent.WARNING;
        try {
            if ("cache.AE".equals(key)) {
                BigDecimal required = cacheDecimalAmount(selected, "required"),
                    available = cacheDecimalAmount(selected, "available");
                if (required.signum() < 0 || available.signum() < 0) return HologramUiAccent.WARNING;
                return available.compareTo(required) >= 0 ? HologramMuiTheme.TEXT : HologramUiAccent.WARNING;
            }
            BigInteger required = cacheAmount(selected, "required"), available = cacheAmount(selected, "available");
            if (required.signum() < 0 || available.signum() < 0) return HologramUiAccent.WARNING;
            return available.compareTo(required) >= 0 ? HologramMuiTheme.TEXT : HologramUiAccent.WARNING;
        } catch (NumberFormatException unavailable) {
            // Missing/unavailable balances keep the existing warning rather than claiming sufficient stock.
            return HologramUiAccent.WARNING;
        }
    }

    private static BigInteger cacheAmount(NBTTagCompound row, String key) {
        if (row.hasKey(key + "Exact", 8)) return new BigInteger(row.getString(key + "Exact"));
        if (row.hasKey(key, 8)) return new BigInteger(row.getString(key));
        if (row.hasKey(key) && row.getTag(key)
            .getId() >= 1
            && row.getTag(key)
                .getId() <= 4)
            return BigInteger.valueOf(row.getLong(key));
        throw new NumberFormatException("Cache amount unavailable");
    }

    private static BigDecimal cacheDecimalAmount(NBTTagCompound row, String key) {
        if (row.hasKey(key + "Exact", 8)) return new BigDecimal(row.getString(key + "Exact"));
        if (row.hasKey(key, 8)) return new BigDecimal(row.getString(key));
        return new BigDecimal(cacheAmount(row, key));
    }

    static boolean voucherPaymentReady(NBTTagCompound state) {
        NBTTagCompound voucher = state.getCompoundTag("decorativeVoucher");
        return !voucher.getBoolean("selected")
            || voucher.getBoolean("enabled") && (state.getCompoundTag("materialQuote")
                .getBoolean("creativePaymentsFree") || voucher.getLong("available") > 0);
    }

    static boolean voucherChoiceAvailable(NBTTagCompound state, boolean demand, boolean selected) {
        NBTTagCompound voucher = state.getCompoundTag("decorativeVoucher");
        return voucher.getBoolean("enabled") && (selected || demand);
    }

    private static NBTTagCompound cacheSummaryResource(NBTTagCompound state) {
        NBTTagList rows = cacheResources(state);
        if (rows.tagCount() == 0) return null;
        NBTTagCompound selected = rows.getCompoundTagAt(0);
        for (int i = 0; i < rows.tagCount(); i++) if ("cache.EU".equals(
            rows.getCompoundTagAt(i)
                .getString("key"))) {
                    selected = rows.getCompoundTagAt(i);
                    break;
                }
        return selected;
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
            return forceReview(state, reviewedRevision) ? "确认仅放已有 · 空缓存" : placement ? "确认空缓存放置" : "确认空缓存施工";
        return forceReview(state, reviewedRevision) ? "确认仅放已有" : placement ? "确认放置" : "确认执行施工";
    }
}
