package com.miaokatze.gtmb.api;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.main.CommonProxy;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.register.ProjectorRegistry;

/** 兼容模组的只读入口；不暴露会话、材料事务或绕过服务端检查的放置接口。 */
public final class GTMBApi {

    /** v1：GTMB ready 后提供唯一投影仪身份并承担 hologram engine，旧模组只处理旧物品映射。 */
    public static final int LEGACY_MAPPING_CONTRACT_VERSION = 1;

    /** Version of the community extension contracts in this package. */
    public static int getExtensionContractVersion() {
        return GTMBExtensions.CONTRACT_VERSION;
    }

    private GTMBApi() {}

    public static int getLegacyMappingContractVersion() {
        return LEGACY_MAPPING_CONTRACT_VERSION;
    }

    /** FML init 完成前返回 null，不创建占位物品。 */
    public static Item getProjectorItem() {
        return isEngineOwner() ? ProjectorRegistry.projector : null;
    }

    public static boolean isEngineOwner() {
        return CommonProxy.isEngineReady();
    }

    public static String getEngineOwnerModId() {
        return isEngineOwner() ? GTMiaoBlueprint.MODID : "";
    }

    public static boolean isProjector(ItemStack stack) {
        Item item = getProjectorItem();
        return item != null && stack != null && stack.getItem() == item;
    }
}
