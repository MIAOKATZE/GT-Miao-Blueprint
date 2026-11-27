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
        if (raw.startsWith("GT alignment index is invalid: ")) return projectorOr(
            "native.reason.alignment.invalid_index",
            "GT 对齐参数无效：%1$s",
            raw.substring("GT alignment index is invalid: ".length()));
        if (raw.startsWith("原生目标没有可支付的物品身份：")) return projectorOr(
            "native.reason.target.unpayable_at",
            "原生目标没有可支付的物品身份：%1$s",
            raw.substring("原生目标没有可支付的物品身份：".length()));
        if (raw.startsWith("LOCAL_NATIVE_PREVIEW_RESULT:")) return projectorOr(
            "native.reason.preview.invalid_result",
            "原生构造返回了无效结果：%1$s",
            raw.substring("LOCAL_NATIVE_PREVIEW_RESULT:".length()));
        if (raw.startsWith("服务端缺少注册的施工材料：")) return projectorOr(
            "native.reason.local.missing_registered_material",
            "服务端缺少注册的施工材料：%1$s",
            raw.substring("服务端缺少注册的施工材料：".length())
                .replace("（无方块）", projectorOr("native.reason.local.no_block", "（无方块）"))
                .replace("（无物品）", projectorOr("native.reason.local.no_item", "（无物品）"))
                .replace("（无设备子型）", projectorOr("native.reason.local.no_subtype", "（无设备子型）")));
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
            case "GT alignment front has an invalid type" -> projectorOr(
                "native.reason.alignment.front_type",
                "GT 朝向数据类型无效。");
            case "GT alignment front differs from target geometry" -> projectorOr(
                "native.reason.alignment.front_mismatch",
                "GT 朝向数据与目标几何不一致。");
            case "GT alignment is missing a valid target front" -> projectorOr(
                "native.reason.alignment.front_missing",
                "GT 对齐缺少有效的目标朝向。");
            case "GT target does not support the requested native alignment", "原生设备不接受目标对齐。" -> projectorOr(
                "native.reason.alignment.unsupported",
                "原生设备不接受目标对齐。");
            case "GT alignment target is missing" -> projectorOr(
                "native.reason.alignment.target_missing",
                "GT 对齐目标设备不存在。");
            case "原生设备未完整应用目标对齐。" -> projectorOr("native.reason.alignment.incomplete", "原生设备未完整应用目标对齐。");
            case "GT native setter changed retained assets, ownership or network binding" -> projectorOr(
                "native.reason.alignment.protected_state_changed",
                "原生设置改变了受保护的资产、所有权或网络绑定。");
            case "原生仓室搭配含无效型号或数量。" -> projectorOr("native.reason.preview.invalid_hatch_profile", "原生仓室搭配含无效型号或数量。");
            case "原生仓室搭配为空。" -> projectorOr("native.reason.preview.empty_hatch_profile", "原生仓室搭配为空。");
            case "所选仓室方向与原生放置结果不同。" -> projectorOr("native.reason.preview.hatch_facing_mismatch", "所选仓室方向与原生放置结果不同。");
            case "所选仓室未被该位置的原生建造流程接受。" -> projectorOr("native.reason.preview.hatch_rejected", "所选仓室未被该位置的原生建造流程接受。");
            case "所选仓室搭配未被完整放入原生目标。" -> projectorOr(
                "native.reason.preview.hatch_profile_incomplete",
                "所选仓室搭配未被完整放入原生目标。");
            case "原生目标没有施工材料。" -> projectorOr("native.reason.construction.no_material", "原生目标没有施工材料。");
            case "装饰形状需要保留已登记的设备 NBT。" -> projectorOr("native.reason.construction.decor_nbt", "装饰形状需要保留已登记的设备 NBT。");
            case "原生组合方块需要保留设备 NBT。" -> projectorOr("native.reason.construction.composite_nbt", "原生组合方块需要保留设备 NBT。");
            case "封存设备必须提供完整原件，本格未施工。" -> projectorOr(
                "native.reason.construction.original_required",
                "封存设备必须提供完整原件，本格未施工。");
            case "装饰抵用券不足，本格未施工。" -> projectorOr("native.reason.construction.voucher_missing", "装饰抵用券不足，本格未施工。");
            case "装饰材料不足，本格未施工。" -> projectorOr("native.reason.construction.decor_missing", "装饰材料不足，本格未施工。");
            case "所选材料来源不足，本格未施工。" -> projectorOr("native.reason.construction.material_missing", "所选材料来源不足，本格未施工。");
            case "覆盖板材料不足，本格未施工。" -> projectorOr("native.reason.construction.cover_missing", "覆盖板材料不足，本格未施工。");
            case "设备资产材料不足，本格未施工。" -> projectorOr("native.reason.construction.assets_missing", "设备资产材料不足，本格未施工。");
            case "设备源质不足，本格未施工。" -> projectorOr("native.reason.construction.essentia_missing", "设备源质不足，本格未施工。");
            case "付款期间施工条件已变化。" -> projectorOr("native.reason.construction.payment_changed", "付款期间施工条件已变化。");
            case "原生放置后的身份、朝向或配置与目标不符。" -> projectorOr(
                "native.reason.construction.target_mismatch",
                "原生放置后的身份、朝向或配置与目标不符。");
            case "原生放置未提供世界变更凭据。" -> projectorOr("native.reason.construction.no_world_receipt", "原生放置未提供世界变更凭据。");
            case "保护事件已改变施工现场，结果待核定。" -> projectorOr(
                "native.reason.construction.protection_changed",
                "保护事件已改变施工现场，结果待核定。");
            case "领地或模组保护取消放置，本格已还原。" -> projectorOr(
                "native.reason.construction.protection_cancelled",
                "领地或模组保护取消放置，本格已还原。");
            case "保护事件后施工权限或付款库存已变化。" -> projectorOr(
                "native.reason.construction.authority_changed",
                "保护事件后施工权限或付款库存已变化。");
            case "原生通知后施工条件已变化。" -> projectorOr("native.reason.construction.notification_changed", "原生通知后施工条件已变化。");
            case "原生放置未提交。" -> projectorOr("native.reason.construction.not_committed", "原生放置未提交。");
            case "施工结果待核定，原世界与付款凭据已保留；请联系管理员 /gtmb recovery" -> projectorOr(
                "native.reason.construction.recovery_review",
                "施工结果待核定，原世界与付款凭据已保留；请联系管理员 /gtmb recovery");
            case "原生组合方块拒绝施工。" -> projectorOr("native.reason.construction.composite_rejected", "原生组合方块拒绝施工。");
            case "原生碰撞检查拒绝施工。" -> projectorOr("native.reason.construction.collision_rejected", "原生碰撞检查拒绝施工。");
            case "原生物品拒绝放置。" -> projectorOr("native.reason.construction.item_rejected", "原生物品拒绝放置。");
            case "该原生目标没有可用的物品放置接口。" -> projectorOr("native.reason.construction.no_placement_api", "该原生目标没有可用的物品放置接口。");
            case "封存设备没有完整恢复能力。" -> projectorOr("native.reason.construction.no_full_restore", "封存设备没有完整恢复能力。");
            case "付费设备状态恢复失败。" -> projectorOr("native.reason.construction.restore_failed", "付费设备状态恢复失败。");
            case "原生物品未初始化目标设备。" -> projectorOr("native.reason.construction.tile_not_initialized", "原生物品未初始化目标设备。");
            case "原生设备不接受目标朝向。" -> projectorOr("native.reason.construction.facing_rejected", "原生设备不接受目标朝向。");
            case "施工退款上下文已变化，原凭据已保留；请联系管理员 /gtmb recovery" -> projectorOr(
                "native.reason.construction.refund_context_changed",
                "施工退款上下文已变化，原凭据已保留；请联系管理员 /gtmb recovery");
            case "控制器未提供独立的原生投影实例。" -> projectorOr("native.reason.target.no_detached_instance", "控制器未提供独立的原生投影实例。");
            case "原生控制器未提供独立实例。" -> projectorOr("native.reason.target.no_independent_controller", "原生控制器未提供独立实例。");
            case "原生投影参数无效。" -> projectorOr("native.reason.target.invalid_signal", "原生投影参数无效。");
            case "原生蓝图施工" -> projectorOr("native.reason.local.title", "原生蓝图施工");
            case "原生蓝图版本或语义不可用于施工" -> projectorOr("native.reason.local.invalid_version", "原生蓝图版本或语义不可用于施工");
            case "原生蓝图语义不可用于施工" -> projectorOr("native.reason.local.invalid_semantics", "原生蓝图语义不可用于施工");
            case "此原生蓝图含当前原生施工不支持的整图规则；未放置或扣料" -> projectorOr(
                "native.reason.local.whole_rules",
                "此原生蓝图含当前原生施工不支持的整图规则；未放置或扣料");
            case "原生蓝图包含无方块身份或数量不正确的目标" -> projectorOr("native.reason.local.invalid_target", "原生蓝图包含无方块身份或数量不正确的目标");
            case "此蓝图没有可施工目标" -> projectorOr("native.reason.local.no_targets", "此蓝图没有可施工目标");
            case "AE 蓝图仍有未处理的净化问题；请修正对应标记后保存" -> projectorOr(
                "native.reason.local.ae_cleanup",
                "AE 蓝图仍有未处理的净化问题；请修正对应标记后保存");
            case "原生施工上下文已变化" -> projectorOr("native.reason.local.context_changed", "原生施工上下文已变化");
            case "请手持确认施工时的投影仪" -> projectorOr("native.reason.local.tool_changed", "请手持确认施工时的投影仪");
            case "施工锚点超出世界边界" -> projectorOr("native.reason.local.anchor_bounds", "施工锚点超出世界边界");
            case "施工锚点所在区块未加载" -> projectorOr("native.reason.local.anchor_unloaded", "施工锚点所在区块未加载");
            case "离施工锚点太远" -> projectorOr("native.reason.local.anchor_far", "离施工锚点太远");
            case "无效施工方向" -> projectorOr("native.reason.local.invalid_facing", "无效施工方向");
            case "原生蓝图施工范围超出世界边界" -> projectorOr("native.reason.local.blueprint_bounds", "原生蓝图施工范围超出世界边界");
            case "施工权限或材料来源不可用" -> projectorOr("native.reason.local.sources_unavailable", "施工权限或材料来源不可用");
            case "材料来源或投影仪已变化" -> projectorOr("native.reason.local.sources_changed", "材料来源或投影仪已变化");
            case "装饰抵用券不可用，未开始施工" -> projectorOr("native.reason.local.voucher_unavailable", "装饰抵用券不可用，未开始施工");
            case "蓝图已更新，请重新打开材料页" -> projectorOr("native.reason.local.version_changed", "蓝图已更新，请重新打开材料页");
            case "已有施工或待核定结果" -> projectorOr("native.reason.local.pending_task", "已有施工或待核定结果");
            case "该原生组合方块的跨坐标旋转尚未核对，请保存为目标方向后施工" -> projectorOr(
                "native.reason.local.composite_rotation",
                "该原生组合方块的跨坐标旋转尚未核对，请保存为目标方向后施工");
            case "0 级蓝图包含其他玩家所有权；请先完成去标识" -> projectorOr("native.reason.local.foreign_owner", "0 级蓝图包含其他玩家所有权；请先完成去标识");
            case "AE 附件存在未处理的净化问题" -> projectorOr("native.reason.local.ae_attachment_cleanup", "AE 附件存在未处理的净化问题");
            case "原生蓝图超出此施工方式的坐标范围" -> projectorOr("native.reason.local.coordinate_range", "原生蓝图超出此施工方式的坐标范围");
            case "无法开始原生蓝图施工" -> projectorOr("native.reason.local.cannot_start", "无法开始原生蓝图施工");
            case "施工已取消" -> projectorOr("native.reason.local.cancelled", "施工已取消");
            case "没有需要放置的方块" -> projectorOr("native.reason.local.no_work", "没有需要放置的方块");
            case "开始原生蓝图施工" -> projectorOr("native.reason.local.started", "开始原生蓝图施工");
            case "正在核对原生施工目标" -> projectorOr("native.reason.local.checking", "正在核对原生施工目标");
            case "原生蓝图施工完成" -> projectorOr("native.reason.local.complete", "原生蓝图施工完成");
            case "原生蓝图施工中" -> projectorOr("native.reason.local.running", "原生蓝图施工中");
            case "施工结果待核定，已停止" -> projectorOr("native.reason.local.uncertain", "施工结果待核定，已停止");
            case "施工受阻或缺料，已停止" -> projectorOr("native.reason.local.blocked", "施工受阻或缺料，已停止");
            case "读取或执行原生蓝图失败，已停止" -> projectorOr("native.reason.local.read_failed", "读取或执行原生蓝图失败，已停止");
            case "施工结果不能确认" -> projectorOr("native.reason.local.cannot_confirm", "施工结果不能确认");
            case "LOCAL_NATIVE_PREVIEW_UNSTABLE" -> projectorOr(
                "native.reason.preview.unstable",
                "Native structure generation did not finish within the pass limit.");
            case "LOCAL_NATIVE_PREVIEW_INCOMPLETE" -> projectorOr(
                "native.reason.preview.incomplete",
                "The native process did not provide a complete structure target.");
            case "LOCAL_NATIVE_PREVIEW_CELL_LIMIT" -> projectorOr(
                "native.reason.preview.cell_limit",
                "The native structure exceeds the cell limit.");
            case "LOCAL_NATIVE_PREVIEW_EMPTY" -> projectorOr(
                "native.reason.preview.empty",
                "The native process produced no structure geometry.");
            case "LOCAL_NATIVE_PREVIEW_COMPLETION_SCOPE" -> projectorOr(
                "native.reason.preview.completion_scope",
                "The native completion process did not provide an independent controller and world.");
            case "LOCAL_NATIVE_PREVIEW_DECLARATION_UNAVAILABLE" -> projectorOr(
                "native.reason.preview.no_declaration",
                "Native construction is unavailable on the independent controller.");
            case "LOCAL_NATIVE_PREVIEW_SCOPE" -> projectorOr(
                "native.reason.preview.scope",
                "The native structure capture scope is no longer valid.");
            case "LOCAL_NATIVE_PREVIEW_OUT_OF_BOUNDS" -> projectorOr(
                "native.reason.preview.out_of_bounds",
                "Native construction tried to write outside the preview world bounds.");
            case "LOCAL_NATIVE_PREVIEW_FOREIGN_TILE" -> projectorOr(
                "native.reason.preview.foreign_tile",
                "Native construction supplied a device belonging to another world.");
            case "LOCAL_NATIVE_PREVIEW_REQUIRES_DETACHED_CONTROLLER" -> projectorOr(
                "native.reason.preview.detached_required",
                "Native capture requires an independent controller in an isolated world.");
            case "LOCAL_NATIVE_HATCH_CONTEXT_UNAVAILABLE" -> projectorOr(
                "native.reason.preview.hatch_context",
                "An independent controller for native hatch queries is unavailable.");
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
