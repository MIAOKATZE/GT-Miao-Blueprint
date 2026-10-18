package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Authenticated material-review actions. The client selects an operation, never its payable material identities. */
final class HologramMaterialWorkflow {

    private HologramMaterialWorkflow() {}

    static boolean lightweight(String op) {
        return "orderMissingMaterials".equals(op) || "cancelMaterialOrder".equals(op)
            || "setDecorativeVoucher".equals(op)
            || "refreshMaterialQuote".equals(op);
    }

    static boolean action(HologramSession s, NBTTagCompound action, String op) {
        boolean material = lightweight(op);
        if (blockDuringOrder(s, op)) {
            s.status = "AE 缺料订单正在后台处理；请等待完成或取消订单后再修改蓝图与施工计划";
            if (material) HologramSnapshots.sendMaterialQuote(s, action.getLong("uiSequence"));
            else HologramSnapshots.sendUi(s);
            return true;
        }
        if (!material) return false;
        try {
            boolean cancellingOrder = "cancelMaterialOrder".equals(op) && HologramAeCraftingOrders.active(s.player);
            if (!(cancellingOrder ? HologramSessionPolicy.jobValid(s) : HologramSessionPolicy.valid(s)))
                throw new IOException("投影仪或蓝图会话已失效");
            requireReview(s, action);
            if ("cancelMaterialOrder".equals(op)) {
                HologramAeCraftingOrders.cancel(s.player, "AE 缺料订单已取消；已入网材料保留，待交付产物存入玩家回收暂存");
                s.status = "AE 缺料订单已取消；已入网材料保留，待交付产物存入玩家回收暂存";
            } else if ("setDecorativeVoucher".equals(op)) {
                boolean selected = action.getBoolean("enabled");
                if (selected
                    && (!HologramDecorationVoucher.enabled() || HologramDecorationVoucher.available(s.player) < 1))
                    throw new IOException("建筑材料抵扣券未启用或主背包中没有可用抵扣券");
                if (selected != s.jobVoucherRequested) {
                    if (!HologramMaterialQuote.cached(s)) throw new IOException("请等待完整材料计划后选择抵扣券");
                    if (selected && s.materialQuoteCache.eligibleDemand == 0)
                        throw new IOException("当前计划没有可由抵扣券减免的建筑材料");
                    s.jobVoucherRequested = selected;
                    s.placementReviewNonce++;
                    s.forcePlacementReady = false;
                    HologramMaterialQuote.project(s);
                    s.blueprintAwaitingConfirmation = true;
                    s.placementReviewIssued = true;
                    s.status = selected
                        ? s.player.capabilities.isCreativeMode ? "已按抵扣券显示建筑材料减免方案；创造施工不消耗抵扣券"
                            : "已按抵扣券重新核算建筑材料；确认施工并成功提交首格时消耗一张"
                        : "已取消抵扣券，材料恢复原生精确需求";
                }
            } else if ("refreshMaterialQuote".equals(op)) {
                HologramMaterialQuote.recheck(s);
                s.status = "已按当前材料需求更新已有与缺失数量；库存以确认施工时为准";
            } else {
                NBTTagCompound quote = s.materialQuote;
                if (quote == null || !quote.getBoolean("complete")
                    || quote.getLong("planRevision") != s.planRevision
                    || !s.id.equals(quote.getString("session"))) throw new IOException("材料报价尚未完成，请先刷新材料");
                HologramMaterialQuote.recheck(s);
                NBTTagCompound request = (NBTTagCompound) s.materialQuote.copy();
                request.setLong("uiSequence", action.getLong("uiSequence"));
                request.setLong("placementReviewNonce", s.placementReviewNonce);
                request.setString("presetId", s.virtualPreset.id == null ? "" : s.virtualPreset.id);
                s.status = HologramMaterialOrdering.available(s.player)
                    ? HologramMaterialOrdering.submit(s.player, extensionRequest(request))
                    : HologramAeCraftingOrders.submit(s.player, s.materialSettings, request);
            }
        } catch (IOException | RuntimeException invalid) {
            s.status = invalid.getMessage() == null ? "材料操作未完成，请刷新后重试" : invalid.getMessage();
        }
        HologramSnapshots.sendMaterialQuote(s, action.getLong("uiSequence"));
        return true;
    }

    static boolean blockDuringOrder(HologramSession s, String op) {
        if (!HologramAeCraftingOrders.active(s.player)) return false;
        return !("close".equals(op) || "open".equals(op)
            || "requestFullSnapshot".equals(op)
            || "refreshPresetLibrary".equals(op)
            || "orderMissingMaterials".equals(op)
            || "cancelMaterialOrder".equals(op));
    }

    static void validateConfirmation(HologramSession s, NBTTagCompound action) throws IOException {
        requireReview(s, action);
        if (HologramAeCraftingOrders.active(s.player)) throw new IOException("请等待 AE 合成完成并刷新材料后再施工");
        if (action.hasKey("useDecorationVoucher") && action.getBoolean("useDecorationVoucher") != s.jobVoucherRequested)
            throw new IOException("抵扣券选择已变化，请先更新材料报价再确认");
        if (s.jobVoucherRequested
            && (!HologramDecorationVoucher.enabled() || HologramDecorationVoucher.available(s.player) < 1))
            throw new IOException("主背包中没有可用建筑材料抵扣券，请取消抵扣或补充后再确认");
    }

    /** Replaces the startup inventory check with a recount of the already reviewed exact identities. */
    static boolean recheckBuild(HologramSession s, NBTTagCompound action) {
        int reviewedMissing = s.planMissing;
        NBTTagCompound reviewed = s.materialQuote;
        try {
            if (reviewed != null && reviewed.getBoolean("complete")
                && reviewed.getLong("planRevision") == s.planRevision
                && s.id.equals(reviewed.getString("session"))) {
                HologramMaterialQuote.recheck(s);
                NBTTagList before = reviewed.getTagList("rows", 10), after = s.materialQuote.getTagList("rows", 10);
                for (int i = 0; i < after.tagCount(); i++) {
                    NBTTagCompound row = after.getCompoundTagAt(i);
                    if (row.getBoolean("optionalCache")
                        || s.player.capabilities.isCreativeMode && !"essentia".equals(row.getString("kind"))) continue;
                    if (i >= before.tagCount() || missingIncreased(row, before.getCompoundTagAt(i)))
                        return updatedReview(s, "可用材料减少，缺料方案已更新；请重新核对材料后确认");
                }
            } else {
                HologramResourcePlanner.refreshResources(s);
                if (s.planMissing > reviewedMissing) return updatedReview(s, "可用材料减少，缺料方案已更新；请重新核对材料后确认");
            }
            if (s.virtual && action != null) {
                NBTTagList caches = HologramResourcePlanner.cacheResourceRows(s);
                for (int i = 0; i < caches.tagCount(); i++) if (caches.getCompoundTagAt(i)
                    .getBoolean("emptyFallback") && !action.getBoolean("allowEmptyCache"))
                    return updatedReview(s, "设备缓存报价已变化，本次将空缓存放置；请核对后确认");
            }
            s.forcePlacementReady = s.virtual && action != null
                && reviewedMissing > 0
                && action.getBoolean("forceExplicit");
            return true;
        } catch (IOException | RuntimeException invalid) {
            return updatedReview(s, "材料检查未完成，请重新核对：" + HologramAePolicy.fallback(invalid.getMessage()));
        }
    }

    private static boolean missingIncreased(NBTTagCompound current, NBTTagCompound reviewed) {
        if (current.hasKey("missingExact", 8) && reviewed.hasKey("missingExact", 8))
            return new java.math.BigInteger(current.getString("missingExact"))
                .compareTo(new java.math.BigInteger(reviewed.getString("missingExact"))) > 0;
        return current.getLong("missing") > reviewed.getLong("missing");
    }

    private static boolean updatedReview(HologramSession s, String reason) {
        s.forcePlacementReady = false;
        s.placementReviewNonce++;
        if (s.materialQuote != null) s.materialQuote.setLong("placementReviewNonce", s.placementReviewNonce);
        if (s.virtualPlacement && s.virtualLocked) s.blueprintAwaitingConfirmation = true;
        s.status = reason;
        return false;
    }

    private static void requireReview(HologramSession s, NBTTagCompound action) throws IOException {
        if (!s.virtual || s.virtualPreset == null
            || !s.virtualPlacement
            || !s.virtualLocked
            || !s.blueprintAwaitingConfirmation
            || !s.placementReviewIssued
            || s.job == 1
            || s.job == 2
            || !s.id.equals(action.getString("session"))) throw new IOException("请先确定蓝图位置并打开材料确认");
        if (!action.hasKey("planRevision", 4) || action.getLong("planRevision") != s.planRevision
            || !action.hasKey("placementReviewNonce", 4)
            || action.getLong("placementReviewNonce") != s.placementReviewNonce)
            throw new IOException("材料确认已更新，请重新核对材料与抵扣券选择");
    }

    static void writeState(HologramSession s, NBTTagCompound state) {
        boolean review = !HologramSessionPolicy.authoring(s) && s.virtual
            && s.virtualPreset != null
            && s.virtualPlacement
            && s.virtualLocked
            && s.blueprintAwaitingConfirmation;
        state.setBoolean(
            "materialOrderingAvailable",
            review && (HologramMaterialOrdering.available(s.player)
                || HologramAeCraftingOrders.available(s.player, s.materialSettings)));
        state.setTag("materialOrder", HologramAeCraftingOrders.snapshot(s.player));
        if (!HologramSessionPolicy.authoring(s) && s.materialQuote != null
            && s.materialQuote.getLong("planRevision") == s.planRevision)
            state.setTag("materialQuote", s.materialQuote.copy());
        NBTTagCompound voucher = new NBTTagCompound();
        voucher.setBoolean("enabled", HologramDecorationVoucher.enabled());
        voucher.setBoolean("selected", s.jobVoucherRequested);
        voucher.setLong("available", HologramDecorationVoucher.available(s.player));
        long eligibleDemand = HologramMaterialQuote.cached(s) ? s.materialQuoteCache.eligibleDemand : 0;
        voucher.setLong("eligibleDemand", eligibleDemand);
        voucher.setBoolean(
            "usable",
            HologramDecorationVoucher.enabled() && eligibleDemand > 0 && voucher.getLong("available") > 0);
        voucher.setString(
            "status",
            s.jobVoucherRequested ? s.player.capabilities.isCreativeMode ? "已选择减免方案；创造施工不消耗抵扣券" : "已选择；首次成功支付建筑材料时消耗一张"
                : "仅抵扣配置允许的建筑模组材料");
        state.setTag("decorativeVoucher", voucher);
    }

    /**
     * Retain the optional provider's original two-list contract while adding exact native identities and long counts.
     */
    private static NBTTagCompound extensionRequest(NBTTagCompound quote) {
        NBTTagCompound request = (NBTTagCompound) quote.copy();
        NBTTagList materials = new NBTTagList(), resources = new NBTTagList();
        NBTTagList rows = quote.getTagList("rows", 10);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (row.getLong("missing") <= 0) continue;
            if ("item".equals(row.getString("kind"))) {
                ItemStack item = HologramMaterialQuote.readItem(row);
                if (item == null) continue;
                NBTTagCompound nativeRow = item.writeToNBT(new NBTTagCompound());
                nativeRow.setString("itemRegistry", row.getString("itemRegistry"));
                for (String field : new String[] { "required", "available", "missing" })
                    nativeRow.setLong(field, row.getLong(field));
                materials.appendTag(nativeRow);
            } else {
                NBTTagCompound nativeRow = (NBTTagCompound) row.copy();
                nativeRow.setString("type", row.getString("kind"));
                nativeRow.setString("key", row.getString("nbtKey"));
                nativeRow.setString("name", row.getString("label"));
                for (String field : new String[] { "required", "available", "missing" }) nativeRow.setString(
                    field,
                    row.hasKey(field + "Exact", 8) ? row.getString(field + "Exact")
                        : Long.toString(row.getLong(field)));
                resources.appendTag(nativeRow);
            }
        }
        request.setTag("materials", materials);
        request.setTag("blueprintResources", resources);
        return request;
    }
}
