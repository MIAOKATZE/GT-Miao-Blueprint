package com.miaokatze.gtmb.hologram;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** Server-owned opt-in rules: planning remains available, automatic world changes are disabled. */
public final class HologramStargateRules {

    public static final String FILE_NAME = "gtmb-stargate-rules.cfg";
    public static final String RESTRICTION_CODE = "STARGATE_RULES_ACTIVE";
    private static final String CATEGORY = "stargateRules";
    private static volatile boolean enabled;

    private HologramStargateRules() {}

    /** Read once during preInit on each side. The server owns all gameplay admission. */
    public static void load(File configDirectory) {
        Configuration config = new Configuration(new File(configDirectory, FILE_NAME), true);
        config.load();
        enabled = config
            .get(
                CATEGORY,
                "enabled",
                false,
                "Disable all GTMB automatic world changes, including construction, replacement, obstruction removal, "
                    + "cut/paste/undo and area demolition, without an operator exemption. Recognition, blueprint "
                    + "editing, previews, materials and existing authorized AE orders remain available. "
                    + "Existing blueprints and recovery records are retained; cut world journals are not replayed. "
                    + "Edit and restart the server.")
            .getBoolean(false);
        if (config.hasChanged()) config.save();
    }

    public static boolean enabled() {
        return enabled;
    }

    public static String restrictionMessage() {
        return "星门规则模式已开启：仅允许识别、编辑、预览和材料功能，自动建造、替换、清障、剪切、粘贴、撤销及区域拆除已禁用";
    }
}
