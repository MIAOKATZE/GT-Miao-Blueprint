package com.miaokatze.gtmb.common.items.hologram;

import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.common.util.ProjectorTooltip;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.gtmb.register.CreativeTabManager;
import com.miaokatze.gtmb.register.DecorationVoucherRegistry;

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
        ProjectorTooltip
            .description(tooltip, "item.decoration_voucher.description.0", "item.decoration_voucher.description.2");
        ProjectorTooltip.controls(tooltip, "item.decoration_voucher.description.1");
        if (GuiScreen.isShiftKeyDown()) {
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
        } else ProjectorTooltip.add(tooltip, "gtmb.tooltip.hold_shift.voucher");
        ProjectorTooltip.finish(tooltip);
    }
}
