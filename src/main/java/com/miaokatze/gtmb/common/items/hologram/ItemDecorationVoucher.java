// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.common.items.hologram;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.common.util.ProjectorTooltip;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.register.CreativeTabManager;
import com.miaokatze.gtmb.register.DecorationVoucherRegistry;
import com.miaokatze.miaokatzecore.api.tooltip.TooltipPages;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** A consumable authorization for one placement job; no client-side right-click behavior. */
public final class ItemDecorationVoucher extends Item {

    public ItemDecorationVoucher() {
        setUnlocalizedName(DecorationVoucherRegistry.ITEM_ID);
        setTextureName(GTMiaoBlueprint.MODID + ":" + DecorationVoucherRegistry.ITEM_ID);
        setCreativeTab(CreativeTabManager.CREATIVE_TAB);
        setMaxStackSize(64);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> tooltip, boolean advanced) {
        if (TooltipPages.expanded(stack)) {
            ProjectorTooltip
                .description(tooltip, "item.decoration_voucher.description.0", "item.decoration_voucher.description.2");
            ProjectorTooltip.controls(tooltip, "item.decoration_voucher.description.1");
            ProjectorTooltip.section(
                tooltip,
                "coverage",
                "item.decoration_voucher.detail.scope",
                "item.decoration_voucher.detail.industrial",
                "item.decoration_voucher.detail.assets");
            ProjectorTooltip.section(
                tooltip,
                "payment",
                "item.decoration_voucher.detail.consume",
                "item.decoration_voucher.detail.rollback",
                "item.decoration_voucher.detail.creative");
            ProjectorTooltip.section(
                tooltip,
                "configuration",
                "item.decoration_voucher.detail.config",
                "item.decoration_voucher.detail.reload");
        } else {
            ProjectorTooltip.description(
                tooltip,
                "gtmb.voucher.compact.scope",
                "gtmb.voucher.compact.excluded",
                "gtmb.voucher.compact.consume",
                "gtmb.voucher.compact.payment");
        }
        TooltipPages.hint(stack, tooltip, false);
        ProjectorTooltip.finish(tooltip);
    }
}
