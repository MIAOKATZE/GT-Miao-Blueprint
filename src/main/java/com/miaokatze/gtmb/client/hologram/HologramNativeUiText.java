// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import net.minecraft.nbt.NBTTagCompound;

/** Presentation-only translations for this native protocol; unknown diagnostics remain intact. */
final class HologramNativeUiText {

    private HologramNativeUiText() {}

    static String presetTierPolicy(boolean allowLower) {
        return allowLower ? projectorOr("native.preset.allow_lower_tiers", "允许较低等级仓室 ✓")
            : projectorOr("native.preset.selected_tier_only", "仅使用所选等级");
    }

    static String presetTierPolicyHint() {
        return projectorOr("native.preset.tier_policy_hint", "若必需型号没有所选等级，可允许较低等级，或使用自定义精确混搭。实际施工始终按投影中的型号核对材料。");
    }

    static String presetTargetNote() {
        return projectorOr("native.preset.complete_target_note", "更新完整结构中所有可替换外壳目标；未配置的可选位置使用机壳，声明必需仓室保留。");
    }

    static String capabilityLabel(NBTTagCompound capability) {
        String raw = capability.getString("label"), id = capability.getString("id");
        if ("main".equals(id) && "主信号（未设子信道时回退）".equals(raw))
            return projectorOr("native.channel.main", "主信号（未设子信道时回退）");
        if (raw.equals(id + "（原始整数）")) return projectorOr("native.channel.raw_integer", "%1$s（原始整数）", id);
        return raw;
    }

    static String optionLabel(NBTTagCompound option) {
        String raw = option.getString("label");
        if (option.hasKey("item", 10)) return raw;
        return switch (raw) {
            case "关闭" -> projectorOr("native.channel.off", "关闭");
            case "恢复默认" -> projectorOr("native.channel.restore_default", "恢复默认");
            case "继承主信号" -> projectorOr("native.channel.inherit_main", "继承主信号");
            default -> raw;
        };
    }

    static String unit(String raw) {
        return switch (raw) {
            case "个" -> projectorOr("native.unit.items", "个");
            case "点" -> projectorOr("native.unit.points", "点");
            default -> raw;
        };
    }

    static String reason(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.startsWith("当前结构状态不能执行此操作："))
            return projectorOr("native.reason.this_operation_is_unavailable_in_the_current_structure", "当前结构状态不能执行此操作：")
                + " "
                + raw.substring(14);
        if (raw.startsWith("未取得原生操作许可："))
            return projectorOr("native.reason.native_operation_permission_was_not_granted", "未取得原生操作许可：") + " "
                + raw.substring(10);
        if (raw.startsWith("原生流程未生成此位置的真实材料："))
            return projectorOr("native.reason.the_native_process_supplied_no_real_material_at", "原生流程未生成此位置的真实材料：")
                + " "
                + raw.substring(16);
        return switch (raw) {
            case "客户端投影与服务器原生目标不同，请重新读取后确认。" -> projectorOr(
                "native.reason.the_client_projection_differs_from_the_server_s",
                "客户端投影与服务器原生目标不同，请重新读取后确认。");
            case "请先停止正在运行的控制器，再执行结构操作。" -> projectorOr(
                "native.reason.stop_the_running_controller_before_operating_on_the",
                "请先停止正在运行的控制器，再执行结构操作。");
            case "原生目标准备期间控制器、访问权限或工具已变化。" -> projectorOr(
                "native.reason.the_controller_access_permissions_or_tool_changed_while",
                "原生目标准备期间控制器、访问权限或工具已变化。");
            case "原生观察期间控制器或访问权限已变化。" -> projectorOr(
                "native.reason.the_controller_or_access_permissions_changed_during_native",
                "原生观察期间控制器或访问权限已变化。");
            case "控制器正在运行，请先停止。" -> projectorOr(
                "native.reason.the_controller_is_running_stop_it_first",
                "控制器正在运行，请先停止。");
            case "原生控制器未提供可读取的结构范围。" -> projectorOr(
                "native.reason.the_native_controller_did_not_provide_a_readable",
                "原生控制器未提供可读取的结构范围。");
            case "原生投影未返回目标。" -> projectorOr("native.reason.the_native_projection_returned_no_target", "原生投影未返回目标。");
            case "原生控制器、位置或朝向已变化。" -> projectorOr(
                "native.reason.the_native_controller_position_or_facing_changed",
                "原生控制器、位置或朝向已变化。");
            case "你没有访问此控制器的权限，请由机主解除锁定或授权后重试。" -> projectorOr(
                "native.reason.you_cannot_access_this_controller_ask_its_owner",
                "你没有访问此控制器的权限，请由机主解除锁定或授权后重试。");
            case "权限核对期间控制器已变化，请重新读取。" -> projectorOr(
                "native.reason.the_controller_changed_during_permission_checks_read_it",
                "权限核对期间控制器已变化，请重新读取。");
            case "当前结构范围包含未加载区块。" -> projectorOr(
                "native.reason.the_current_structure_footprint_contains_unloaded_chunks",
                "当前结构范围包含未加载区块。");
            case "施工范围包含未加载区块。" -> projectorOr(
                "native.reason.the_construction_footprint_contains_unloaded_chunks",
                "施工范围包含未加载区块。");
            case "所选材料来源不足，未开始修改结构。" -> projectorOr(
                "native.reason.selected_material_sources_are_insufficient_the_structure_was",
                "所选材料来源不足，未开始修改结构。");
            case "原生结构、工具或恢复状态已变化。" -> projectorOr(
                "native.reason.the_native_structure_tool_or_recovery_state_changed",
                "原生结构、工具或恢复状态已变化。");
            case "任务许可已变化。" -> projectorOr("native.reason.the_task_permit_changed", "任务许可已变化。");
            case "施工期间控制器或区块已变化。" -> projectorOr(
                "native.reason.the_controller_or_chunks_changed_during_construction",
                "施工期间控制器或区块已变化。");
            case "新建范围已出现方块，请重新读取后补建。" -> projectorOr(
                "native.reason.blocks_appeared_in_the_new_build_area_read",
                "新建范围已出现方块，请重新读取后补建。");
            case "原生结构操作异常，已保留实际完成结果。" -> projectorOr(
                "native.reason.the_native_operation_failed_actual_completed_results_were",
                "原生结构操作异常，已保留实际完成结果。");
            case "原生操作结果待核定，未继续执行。" -> projectorOr(
                "native.reason.the_native_operation_result_needs_recovery_review_execution",
                "原生操作结果待核定，未继续执行。");
            case "本格未提交，已停止。" -> projectorOr(
                "native.reason.this_cell_was_not_committed_execution_stopped",
                "本格未提交，已停止。");
            case "回收队列尚未交付，未继续拆除。" -> projectorOr(
                "native.reason.recovery_delivery_is_pending_demolition_stopped",
                "回收队列尚未交付，未继续拆除。");
            case "原生采收或交付停止；实际掉落已保留，不恢复源块或重复投递。" -> projectorOr(
                "native.reason.native_harvesting_or_delivery_stopped_actual_drops_were",
                "原生采收或交付停止；实际掉落已保留，不恢复源块或重复投递。");
            case "原生采收结果待核定，未重放、恢复源块或重复发放掉落。" -> projectorOr(
                "native.reason.native_harvesting_needs_recovery_review_no_replay_source",
                "原生采收结果待核定，未重放、恢复源块或重复发放掉落。");
            case "已停止；实际提交的放置、拆除与回收保留。" -> projectorOr(
                "native.reason.stopped_committed_placement_demolition_and_recovery_were_kept",
                "已停止；实际提交的放置、拆除与回收保留。");
            case "施工连接或所在世界已变化。" -> projectorOr(
                "native.reason.the_construction_connection_or_world_changed",
                "施工连接或所在世界已变化。");
            case "当前玩家没有施工权限。" -> projectorOr("native.reason.this_player_cannot_modify_the_world", "当前玩家没有施工权限。");
            case "游戏模式已变化，请重新确认。" -> projectorOr("native.reason.the_game_mode_changed_confirm_again", "游戏模式已变化，请重新确认。");
            case "手持投影仪已变化，请重新确认。" -> projectorOr(
                "native.reason.the_held_projector_changed_confirm_again",
                "手持投影仪已变化，请重新确认。");
            case "原生操作的玩家、投影仪或维度已变化。" -> projectorOr(
                "native.reason.the_native_operation_s_player_projector_or_dimension",
                "原生操作的玩家、投影仪或维度已变化。");
            case "原生操作确认已失效。" -> projectorOr("native.reason.the_native_operation_confirmation_expired", "原生操作确认已失效。");
            case "手持栏已变化。" -> projectorOr("native.reason.the_selected_hotbar_slot_changed", "手持栏已变化。");
            case "投影仪身份未同步，请重新打开结构。" -> projectorOr(
                "native.reason.the_projector_identity_is_not_synchronized_reopen_the",
                "投影仪身份未同步，请重新打开结构。");
            case "当前材料来源选择无效。" -> projectorOr(
                "native.reason.the_current_material_source_selection_is_invalid",
                "当前材料来源选择无效。");
            case "原生观察请求已变化。" -> projectorOr("native.reason.the_native_observation_request_changed", "原生观察请求已变化。");
            case "没有当前有效的原生操作确认。" -> projectorOr(
                "native.reason.no_valid_native_operation_confirmation_is_available",
                "没有当前有效的原生操作确认。");
            case "原生操作连接已变化。" -> projectorOr("native.reason.the_native_operation_connection_changed", "原生操作连接已变化。");
            case "控制器、工作区或投影仪已变化。" -> projectorOr(
                "native.reason.the_controller_workspace_or_projector_changed",
                "控制器、工作区或投影仪已变化。");
            case "施工结果已定；详细回执已过期。" -> projectorOr(
                "native.reason.the_construction_result_is_settled_its_detailed_receipt",
                "施工结果已定；详细回执已过期。");
            case "已取消，未开始施工。" -> projectorOr("native.reason.cancelled_before_construction_started", "已取消，未开始施工。");
            case "控制器或区块在观察前已变化，请重新读取。" -> projectorOr(
                "native.reason.the_controller_or_chunks_changed_before_observation_read",
                "控制器或区块在观察前已变化，请重新读取。");
            case "原生观察期间控制器已被替换，请重新读取。" -> projectorOr(
                "native.reason.the_controller_was_replaced_during_observation_read_the",
                "原生观察期间控制器已被替换，请重新读取。");
            case "服务器无法重建原生目标。" -> projectorOr(
                "native.reason.the_server_could_not_rebuild_the_native_target",
                "服务器无法重建原生目标。");
            case "没有当前可核实的原生逐格施工结果。" -> projectorOr(
                "native.reason.no_native_per_cell_construction_result_can_currently",
                "没有当前可核实的原生逐格施工结果。");
            case "首次短请求回执已失效；未开始施工。" -> projectorOr(
                "native.reason.the_initial_query_receipt_expired_construction_did_not",
                "首次短请求回执已失效；未开始施工。");
            case "已确认的控制器被替换、移动或卸载；请重新读取后确认。" -> projectorOr(
                "native.reason.the_confirmed_controller_was_replaced_moved_or_unloaded",
                "已确认的控制器被替换、移动或卸载；请重新读取后确认。");
            case "此服务器会话未完成原生确认；未开始施工。" -> projectorOr(
                "native.reason.native_confirmation_is_incomplete_for_this_server_session",
                "此服务器会话未完成原生确认；未开始施工。");
            case "当前另有原生施工任务。" -> projectorOr(
                "native.reason.another_native_construction_task_is_running",
                "当前另有原生施工任务。");
            case "原生现场或配置无法核对；未开始施工。" -> projectorOr(
                "native.reason.the_native_world_or_configuration_could_not_be",
                "原生现场或配置无法核对；未开始施工。");
            case "原生控制器或投影参数无效。" -> projectorOr(
                "native.reason.invalid_native_controller_or_projection_parameters",
                "原生控制器或投影参数无效。");
            case "控制器未提供原生投影流程。" -> projectorOr(
                "native.reason.the_controller_has_no_native_projection_process",
                "控制器未提供原生投影流程。");
            case "控制器未提供原生生存建造流程。" -> projectorOr(
                "native.reason.the_controller_has_no_native_survival_construction_process",
                "控制器未提供原生生存建造流程。");
            case "原生流程无法读取当前结构范围。" -> projectorOr(
                "native.reason.the_native_process_could_not_read_the_current",
                "原生流程无法读取当前结构范围。");
            case "原生结构范围超过读取上限。" -> projectorOr(
                "native.reason.the_native_footprint_exceeds_the_reading_limit",
                "原生结构范围超过读取上限。");
            case "原生投影没有提供结构范围。" -> projectorOr(
                "native.reason.the_native_projection_supplied_no_structure_footprint",
                "原生投影没有提供结构范围。");
            case "原生目标初始化或配置无效。" -> projectorOr(
                "native.reason.native_target_initialization_or_configuration_is_invalid",
                "原生目标初始化或配置无效。");
            case "原生目标生成未收敛。" -> projectorOr("native.reason.native_target_generation_did_not_converge", "原生目标生成未收敛。");
            case "所选仓室配置未通过控制器的原生结构检查。" -> projectorOr(
                "native.reason.the_selected_hatch_configuration_failed_the_controller_s",
                "所选仓室配置未通过原生结构检查。请核对信道和必需仓室等级，可允许较低等级或使用自定义精确混搭。");
            case "原生流程未提供可构建的完整结构足迹。" -> projectorOr(
                "native.reason.the_native_process_did_not_provide_a_complete",
                "原生流程未提供可构建的完整结构足迹。");
            case "原生投影流程无法生成完整目标。" -> projectorOr(
                "native.reason.the_native_projection_could_not_generate_a_complete",
                "原生投影流程无法生成完整目标。");
            case "原生流程在未声明位置生成了目标。" -> projectorOr(
                "native.reason.the_native_process_generated_a_target_outside_its",
                "原生流程在未声明位置生成了目标。");
            case "原生目标没有可支付的物品身份。" -> projectorOr(
                "native.reason.the_native_target_has_no_payable_item_identity",
                "原生目标没有可支付的物品身份。");
            default -> raw;
        };
    }
}
