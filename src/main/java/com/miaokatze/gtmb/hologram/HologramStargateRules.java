// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.File;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.oredict.OreDictionary;

import gregtech.api.util.GTUtility;

/** Server-owned opt-in material rules; automatic construction and ordinary device recovery remain available. */
public final class HologramStargateRules {

    public static final String FILE_NAME = "gtmb-stargate-rules.cfg";
    public static final String RESTRICTION_CODE = "STARGATE_ORE_MOVE_BLOCKED";
    private static final String CATEGORY = "stargateRules";
    private static volatile boolean enabled;

    private HologramStargateRules() {}

    /** Read once during preInit on each side. The server owns all gameplay admission. */
    public static void load(File configDirectory) {
        Configuration config = new Configuration(new File(configDirectory, FILE_NAME), true);
        config.load();
        enabled = config.get(
            CATEGORY,
            "enabled",
            false,
            "Keep automatic construction, replacement, obstruction removal and demolition enabled with normal "
                + "material costs, but disable decoration vouchers and discard all drops from removed ore blocks. "
                + "Cut/paste/undo may move ordinary buildings and devices, but may not move or overwrite ore blocks. "
                + "Existing blueprints and recovery records are retained; ore-related cut journals are not replayed. "
                + "Edit and restart the server.")
            .getBoolean(false);
        // Forge does not mark comment-only updates dirty; replace the earlier candidate's obsolete rule text.
        config.save();
    }

    public static boolean enabled() {
        return enabled;
    }

    public static String restrictionMessage() {
        return "星门规则模式下不能剪切、搬运矿石或覆盖矿石；原方块、暂存和恢复记录已保留";
    }

    /** Inspect the removed world block, never the contents of a recovered device or its item inventory. */
    static boolean suppressOreDrops(Block block, int metadata) {
        return enabled && isOre(block, metadata);
    }

    static boolean isOre(Block block, int metadata) {
        if (block == null) return false;
        if (GTUtility.isOre(block, metadata)) return true;
        Item item = Item.getItemFromBlock(block);
        if (item == null) return false;
        for (int oreId : OreDictionary.getOreIDs(new ItemStack(item, 1, metadata))) if (OreDictionary.getOreName(oreId)
            .startsWith("ore")) return true;
        return false;
    }
}
