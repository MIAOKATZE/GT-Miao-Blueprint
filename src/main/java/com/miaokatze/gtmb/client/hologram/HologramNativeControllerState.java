// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage;

/** One editable native target and an independently server-observed machine state. */
final class HologramNativeControllerState {

    private long targetRevision = 1;
    private boolean observed;
    private boolean targetReady;
    private Stage stage = Stage.UNKNOWN;
    private Operation action = Operation.BUILD;
    private String reason = projectorOr("native.ui.reading_server_structure_state", "正在读取服务器结构状态。");
    private String targetReason = projectorOr("native.ui.updating_the_complete_target", "正在更新当前完整目标。");

    long targetRevision() {
        return targetRevision;
    }

    Operation action() {
        return action;
    }

    void targetChanged() {
        targetRevision++;
        targetReady = false;
        invalidateObservation();
        targetReason = projectorOr("native.ui.updating_the_complete_target", "正在更新当前完整目标。");
    }

    void targetReady() {
        targetReady = true;
        targetReason = "";
        if (!observed) reason = projectorOr("native.ui.reading_server_structure_state", "正在读取服务器结构状态。");
    }

    void targetFailed(String failure) {
        targetReady = false;
        invalidateObservation();
        targetReason = failure;
    }

    void invalidateObservation() {
        observed = false;
        stage = Stage.UNKNOWN;
        reason = projectorOr("native.ui.reading_server_structure_state", "正在读取服务器结构状态。");
    }

    void observationFailed(String failure) {
        invalidateObservation();
        reason = failure.isEmpty()
            ? projectorOr("native.ui.server_structure_state_unavailable_please_retry_shortly", "未取得服务器结构状态，请稍候重试。")
            : failure;
    }

    boolean observe(ServerObservation observation) {
        if (observation == null || observation.targetRevision() != targetRevision) return false;
        observed = true;
        stage = observation.stage();
        reason = observation.reason();
        if (!allowed(stage, action)) action = defaultAction(stage);
        return true;
    }

    boolean select(Operation next) {
        if (!blocker(next).isEmpty()) return false;
        action = next;
        return true;
    }

    String blocker(Operation next) {
        if (!targetReady && next != Operation.DEMOLISH)
            return targetReason.isEmpty() ? projectorOr("native.ui.updating_the_complete_target", "正在更新当前完整目标。")
                : HologramNativeUiText.reason(targetReason);
        if (!observed || stage == Stage.UNKNOWN) return reason.isEmpty()
            ? projectorOr("native.ui.server_structure_state_has_not_been_received", "尚未取得服务器结构状态。")
            : HologramNativeUiText.reason(reason);
        if (!reason.isEmpty()) return HologramNativeUiText.reason(reason);
        if (allowed(stage, next)) return "";
        return switch (stage) {
            case CONTROLLER_ONLY -> projectorOr(
                "native.ui.only_the_controller_is_present_build_the",
                "当前只有控制器，请先建造结构。");
            case UNFORMED -> projectorOr("native.ui.the_structure_is_incomplete_use_repair", "结构尚未成型，请使用补建。");
            case FORMED -> projectorOr("native.ui.the_structure_is_formed_use_replace", "结构已成型，请使用替换。");
            default -> projectorOr("native.ui.server_structure_state_has_not_been_received", "尚未取得服务器结构状态。");
        };
    }

    static boolean allowed(Stage stage, Operation action) {
        return switch (stage) {
            case CONTROLLER_ONLY -> action == Operation.BUILD;
            case UNFORMED -> action == Operation.REPAIR || action == Operation.DEMOLISH;
            case FORMED -> action == Operation.REPLACE || action == Operation.DEMOLISH;
            default -> false;
        };
    }

    private static Operation defaultAction(Stage stage) {
        return stage == Stage.FORMED ? Operation.REPLACE : stage == Stage.UNFORMED ? Operation.REPAIR : Operation.BUILD;
    }

    static String label(Operation action) {
        return switch (action) {
            case BUILD -> projectorOr("native.ui.build", "建造");
            case REPAIR -> projectorOr("native.ui.repair", "补建");
            case REPLACE -> projectorOr("native.ui.replace", "替换");
            case DEMOLISH -> projectorOr("native.ui.demolish", "拆除");
        };
    }

    static String confirmationLabel(Operation action) {
        return projectorOr("native.confirm_action", "确认%1$s", label(action));
    }

    static String reviewLabel(Operation action) {
        return projectorOr("native.review_action", "核对并确认%1$s", label(action));
    }

    String statusCaption() {
        String status = switch (stage) {
            case CONTROLLER_ONLY -> projectorOr("native.ui.controller_only", "仅控制器");
            case UNFORMED -> projectorOr("native.ui.structure_incomplete", "结构未成型");
            case FORMED -> projectorOr("native.ui.structure_formed", "结构已成型");
            default -> projectorOr("native.ui.reading_server_structure_state_14", "正在读取服务器结构状态");
        };
        if (!targetReason.isEmpty()) status += " · " + HologramNativeUiText.reason(targetReason);
        if (!reason.isEmpty() && (stage != Stage.UNKNOWN
            || !projectorOr("native.ui.reading_server_structure_state", "正在读取服务器结构状态。").equals(reason)))
            status += " · " + HologramNativeUiText.reason(reason);
        return status;
    }

    static int legacyMode(Operation action) {
        return action == Operation.REPLACE ? 1 : action == Operation.DEMOLISH ? 2 : 0;
    }

    NBTTagCompound metadata() {
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("nativeAction", action.ordinal());
        result.setInteger("mode", legacyMode(action));
        result.setString("nativeStage", stage.name());
        result.setLong("nativeTargetRevision", targetRevision);
        result.setBoolean("nativeTargetReady", targetReady);
        result.setString("nativeObservationReason", reason);
        result.setString("nativeTargetReason", targetReason);
        return result;
    }
}
