// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.Locale;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;

/** One localized chat receipt per operation, measured from request/task creation through its terminal state. */
public final class HologramOperationResult {

    private final String operation;
    private final long started;
    private boolean finished;

    private HologramOperationResult(String operation, UUID id, long started) {
        this.operation = operation;
        this.started = started;
    }

    public static HologramOperationResult start(String operation, UUID id) {
        return start(operation, id, System.nanoTime());
    }

    public static HologramOperationResult start(String operation, UUID id, long started) {
        return new HologramOperationResult(operation, id == null ? UUID.randomUUID() : id, started);
    }

    /** Call on the owning game thread. Replayed terminal packets/queries must reuse the original request UUID. */
    public void finish(EntityPlayer player, String outcome, long completed, long satisfied, long skipped, long total) {
        finish(player, outcome, completed, satisfied, skipped, total, "");
    }

    public void finish(EntityPlayer player, String outcome, long completed, long satisfied, long skipped, long total,
        String detail) {
        if (finished || player == null) return;
        finished = true;
        try {
            net.minecraft.util.IChatComponent summary = new ChatComponentTranslation(
                "gtmb.hologram.result.summary",
                new ChatComponentTranslation("gtmb.hologram.result.operation." + operation),
                new ChatComponentTranslation("gtmb.hologram.result.outcome." + outcome),
                completed,
                satisfied,
                skipped,
                total,
                String.format(Locale.ROOT, "%.2f", Math.max(0, System.nanoTime() - started) / 1_000_000_000.0));
            if (detail != null && !detail.isEmpty())
                summary = new ChatComponentTranslation("gtmb.hologram.result.detail", summary, reason(detail));
            player.addChatMessage(summary);
        } catch (RuntimeException unavailable) {
            // A terminal notification cannot change a committed operation or prevent its cleanup on disconnect.
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.debug("Operation result chat unavailable", unavailable);
        }
    }

    private static net.minecraft.util.IChatComponent reason(String detail) {
        if ("装饰抵用券不足，本格未施工。".equals(detail))
            return new ChatComponentTranslation("gtmb.hologram.projector.native.reason.construction.voucher_missing");
        String limited = HologramNativeProjectionJob.LIMITED_SCOPE_DETAIL;
        if (detail.endsWith(limited)) {
            var warning = new ChatComponentTranslation("gtmb.hologram.projector.native.reason.job.scope_limited");
            String cause = detail.substring(0, detail.length() - limited.length())
                .trim();
            return cause.isEmpty() ? warning
                : reason(cause).appendText(" ")
                    .appendSibling(warning);
        }
        String key = switch (detail) {
            case "施工完成。" -> "complete";
            case "施工请求已处理，未重复执行。" -> "replayed";
            case "已有施工任务，请先取消或等待结束。" -> "busy";
            case "存在待核定或待交付施工记录。" -> "recovery";
            case "施工上下文已变化。", "施工上下文已变化，未执行部分已停止。" -> "changed";
            case "施工已取消，已提交结果保留。" -> "cancelled";
            case "施工区块已卸载。" -> "unloaded";
            case "关闭施工界面失败，未开始施工。" -> "close_failed";
            case "目标被占用，未替换原方块。" -> "occupied";
            case "回收队列尚未交付。" -> "delivery";
            case "原生拆除回调失败，实际掉落已保留。" -> "removal_failed";
            case "施工异常，已提交结果保留。" -> "failed";
            case "施工受阻，已提交结果保留。" -> "blocked";
            default -> "";
        };
        return key.isEmpty() ? new net.minecraft.util.ChatComponentText(detail)
            : new ChatComponentTranslation("gtmb.hologram.projector.native.reason.job." + key);
    }
}
