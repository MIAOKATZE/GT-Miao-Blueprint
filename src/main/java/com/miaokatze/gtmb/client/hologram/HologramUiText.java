package com.miaokatze.gtmb.client.hologram;

import java.util.Locale;

/** Human-readable Chinese context for third-party/server diagnostics; raw identifiers remain inspectable. */
final class HologramUiText {

    private HologramUiText() {}

    static String localized(String key, String fallback) {
        String result = net.minecraft.util.StatCollector.translateToLocal(key);
        return key.equals(result) ? fallback : result;
    }

    static String severity(String value) {
        return "blocker".equals(value) ? "阻塞" : "warning".equals(value) ? "注意" : "说明";
    }

    static String nbtMeaning(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if ("Aspect".equals(key)) return "源质罐中的源质类型；与 Amount 数量一起构成实际源质资源。";
        if ("Amount".equals(key)) return "资源数量字段；源质罐中表示源质单位数，流体记录中表示流体数量，具体以所属设备格式为准。";
        if ("mstoredenergy".equals(lower)) return "设备中保存的 EU 能量；保留时按实际资源计费。";
        if ("mstoredsteam".equals(lower)) return "设备内部保存的蒸汽能量；保留时按已登记换算计入蒸汽资源。";
        if (lower.equals("items") || lower.equals("mitems")
            || lower.equals("inventory")
            || lower.equals("minventory")
            || lower.equals("itemhandler")
            || lower.equals("itemstorage")) return "实际物品库存记录；保留时逐项核对并支付对应物品，带标签物品精确匹配。";
        if (lower.equals("fluid") || lower.equals("fluids")
            || lower.equals("mfluid")
            || lower.equals("mfluids")
            || lower.equals("tank")
            || lower.equals("tanks")) return "设备流体储存记录；保留时核对流体类型与数量并支付对应资源。";
        if (lower.equals("owner") || lower.equals("ownerid")
            || lower.equals("owneruuid")
            || lower.equals("ownername")
            || lower.equals("mowneruuid")
            || lower.equals("mownername")) return "设备或网络所有者身份；去标识阶段按登记规则处理，放置时仍核验权限。";
        if (lower.equals("x") || lower.equals("y")
            || lower.equals("z")
            || lower.equals("dimension")
            || lower.equals("dim")
            || lower.equals("anchorx")
            || lower.equals("anchory")
            || lower.equals("anchorz")
            || lower.equals("anchordim")) return "已登记的设备位置或网络锚点字段；请在坐标关系步骤核对清除、绝对或相对方式。";
        if (lower.equals("frequency") || lower.equals("freq") || lower.equals("channelid") || lower.equals("networkid"))
            return "登记的网络频率、频道或网络标识；去标识等级可能清除此项。";
        if (lower.equals("id") || lower.equals("mid")
            || lower.equals("gtitblueprintitemregistry")
            || lower.equals("gtitblueprintblockregistry")) return "设备或物品的注册身份，影响类型恢复；此类必需字段由服务端保护。";
        return "该模组的未登记字段；没有通用含义说明。忽略会从所有匹配方块清除此同名根条目及其子内容。";
    }

    static String reason(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.startsWith("蓝图库索引保存失败：")) return "蓝图库索引保存失败，请检查磁盘空间与存储目录后重试；当前修改尚未保存";
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("framebuffer") || lower.contains("preview readback gl error"))
            return "当前图形环境无法生成结构预览图，请检查渲染设置后重试，蓝图尚未保存";
        if (lower.contains("native blueprint model unavailable") || lower.contains("blueprint material unavailable"))
            return "部分方块的模型或材质无法生成预览图，请核对模组与资源包后重试";
        if (lower.contains("preview has no cells") || lower.contains("preview has no buildable cells"))
            return "蓝图中没有可构建的方块，请检查被排除的区域";
        if (lower.contains("blueprint preview queue is full") || lower.contains("save result queue is busy"))
            return "蓝图保存队列繁忙，请稍候重试";
        if (lower.contains("png") || lower.contains("artifact")
            || lower.contains("saved json")
            || lower.contains("blueprint preview source")) {
            // Product progress and completion messages also name PNG. Preserve their existing meaning.
            for (int i = 0; i < value.length(); i++)
                if (value.charAt(i) >= '\u4e00' && value.charAt(i) <= '\u9fff') return value;
            return lower.contains("rollback") ? "蓝图文件回退尚未完成，请核对 JSON 与预览图后重试" : "蓝图与预览图的保存校验未完成，请保持当前蓝图并重试";
        }
        if (lower.contains("native tile id does not match the registered block factory"))
            return "方块 NBT 的设备类型与实际方块不一致，请重新采集该方块或移除这条错误记录";
        if (lower.contains("native tile id must be a nonempty registered string"))
            return "方块 NBT 的设备类型无效，必须使用已登记的原生类型名称";
        if (lower.contains("unsupported native block factory identity")) return "无法核验该方块的原生设备类型，请重新采集并核对模组版本";
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
