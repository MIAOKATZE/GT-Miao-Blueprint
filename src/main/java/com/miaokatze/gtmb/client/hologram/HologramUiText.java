package com.miaokatze.gtmb.client.hologram;

import java.util.Locale;

/** Human-readable Chinese context for third-party/server diagnostics; raw identifiers remain inspectable. */
final class HologramUiText {

    private HologramUiText() {}

    static String severity(String value) {
        return "blocker".equals(value) ? "阻塞" : "warning".equals(value) ? "注意" : "说明";
    }

    static String reason(String value) {
        if (value == null || value.isEmpty()) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("needs a real bound quantum terminal")) return "这个网络组尚未绑定，请选择背包中已绑定真实网络的量子终端";
        if (lower.contains("selected quantum terminal changed")) return "背包里的量子终端已变化，请重新选择并绑定";
        if (lower.contains("select a real quantum node or terminal")) return "请选择携带网络坐标的真实量子节点或终端";
        if (lower.contains("quantum terminal is not bound")) return "该量子终端尚未绑定网络，请先在世界中完成绑定";
        if (lower.contains("quantum anchor is not loaded")) return "量子网络的锚点区块未加载，请先加载该网络控制器";
        if (lower.contains("quantum anchor controller is unavailable")) return "量子网络锚点处没有可用控制器，请重新绑定终端";
        if (lower.contains("selected controller is not a quantum network")) return "终端指向的控制器不是量子网络，请选择正确的网络终端";
        if (lower.contains("no permission to bind")) return "你没有权限绑定这个量子网络，请使用有权限的终端";
        if (lower.contains("quantum network verification failed")
            || lower.contains("cannot verify existing quantum binding")) return "无法验证量子网络绑定，请核对终端、控制器与访问权限后重试";
        if (lower.contains("quantum incorporation was rejected")) return "量子网络拒绝并入该设备，请核对网络类型和权限";
        if (lower.contains("rollback")) return "网络操作回退未完成，请核对实际设备状态后重新检查";
        if (lower.contains("quantum anchor height is invalid")) return "量子锚点高度超出世界范围，请重新绑定正确位置";
        if (lower.contains("incomplete or malformed quantum anchor")) return "量子锚点坐标不完整，请重新绑定终端";
        if (lower.contains("quantum network inventory selection")) return "背包选择不包含可用量子终端，请重新选择槽位";
        if (lower.contains("quantum group hint")) return "网络组提示需为不超过 256 字的普通文本，请修改后重试";
        if (lower.contains("unknown blueprint quantum group") || lower.contains("invalid blueprint quantum group"))
            return "蓝图中的量子网络组不存在或记录无效，请重新读取蓝图";
        if (lower.contains("schema changed") || lower.contains("type changed"))
            return "模组接口或数据格式发生变化，当前版本无法校验该网络，请更新兼容适配";
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) >= '\u4e00' && value.charAt(i) <= '\u9fff') return value;
        String meaning = lower.contains("permission") || lower.contains("owner") ? "没有操作权限或所有者不匹配"
            : lower.contains("revision") || lower.contains("nonce") || lower.contains("session") ? "校验已过期，请重新打开当前蓝图核对"
                : lower.contains("obstruct") || lower.contains("occupied") ? "放置位置存在阻塞物，请清理后重新核对"
                    : lower.contains("unloaded") || lower.contains("source unavailable") ? "来源区块或结构不可读取，请重新加载来源"
                        : lower.contains("missing") || lower.contains("unavailable") ? "所需物品、资源或接口不可用"
                            : lower.contains("unsupported") || lower.contains("unrecognized")
                                || lower.contains("unknown")
                                    ? "该数据类型尚不支持，请修正或明确清除对应项目"
                                    : lower.contains("invalid") || lower.contains("overflow") || lower.contains("limit")
                                        ? "数据或数量不符合允许范围，请修正后重试"
                                        : lower.contains("network") || lower.contains("binding")
                                            ? "网络绑定未完成或不可用，请核对绑定的网络组"
                                            : lower.contains("contents") || lower.contains("cleared")
                                                || lower.contains("omitted") ? "相关内容将清除或不予保留，请核对处理范围"
                                                    : "该项目未通过服务端校验，请展开技术详情检查具体字段，修正后重试";
        return meaning;
    }
}
