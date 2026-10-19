package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.IFluidContainerItem;

import com.miaokatze.gtmb.register.DecorationVoucherRegistry;

/** Pure material classification shared by the server quote and the real placement transaction. */
public final class HologramDecorationVoucher {

    private static final Set<String> INDUSTRIAL_MOD_IDS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "gregtech",
                "gtpp",
                "miscutils",
                "goodgenerator",
                "bartworks",
                "dreamcraft",
                "newhorizonscoremod",
                "appliedenergistics2",
                "ae2stuff",
                "ae2fc",
                "ae2wct",
                "ae2wut",
                "thaumicenergistics",
                "ic2",
                "tectech",
                "gtnhintergalactic",
                "gtnhlanth",
                "galacticgreg",
                "avaritia",
                "enderio",
                "thermalexpansion",
                "thermaldynamics",
                "railcraft",
                "buildcraft|core",
                "buildcraft|transport",
                "buildcraft|factory",
                "buildcraft|energy",
                "extrautilities",
                "opencomputers",
                "computronics",
                "logisticspipes",
                "stevescarts")));

    private HologramDecorationVoucher() {}

    public static boolean enabled() {
        return DecorationVoucherConfig.view().enabled;
    }

    /** Ordinary decorative items must themselves belong to an allowed decoration namespace. */
    public static boolean eligible(ItemStack stack) {
        return stack != null && Block.getBlockFromItem(stack.getItem()) != null
            && Block.getBlockFromItem(stack.getItem()) != net.minecraft.init.Blocks.air
            && safe(stack)
            && DecorationVoucherConfig.view()
                .allows(stack);
    }

    public static boolean covered(Block host, ItemStack payment) {
        // Only the placed shell is waived. Container contents are quoted on independent paths.
        return host != null && payment != null
            && Block.getBlockFromItem(payment.getItem()) == host
            && eligible(payment);
    }

    /**
     * Only pass costs returned by the validated native decoration adapter. Its registered host establishes
     * provenance for vanilla skins/raw blocks; industrial parts and materials remain payable individually.
     */
    public static boolean covered(Block host, HologramDecorMaterials.Cost cost) {
        if (!hostAllowed(host) || !HologramDecorMaterials.supports(host)
            || cost == null
            || cost.numerator <= 0
            || !safe(cost.prebuilt)
            || !safe(cost.raw)) return false;
        if (DecorationVoucherConfig.view()
            .denied(cost.prebuilt)
            || DecorationVoucherConfig.view()
                .denied(cost.raw))
            return false;
        for (ItemStack alternative : cost.alternatives) if (!safe(alternative) || DecorationVoucherConfig.view()
            .denied(alternative)) return false;
        return true;
    }

    /** Filter before ledger quotation so a waived raw fraction never earns spendable fractional credit. */
    public static HologramDecorMaterials.Descriptor payable(Block host, HologramDecorMaterials.Descriptor descriptor) {
        if (descriptor == null) return null;
        List<HologramDecorMaterials.Cost> remaining = new ArrayList<>();
        for (HologramDecorMaterials.Cost cost : descriptor.costs) if (!covered(host, cost)) remaining.add(cost);
        return new HologramDecorMaterials.Descriptor(remaining);
    }

    public static boolean hasCovered(Block host, HologramDecorMaterials.Descriptor descriptor) {
        if (descriptor == null) return false;
        for (HologramDecorMaterials.Cost cost : descriptor.costs) if (covered(host, cost)) return true;
        return false;
    }

    public static ItemStack stack() {
        return DecorationVoucherRegistry.voucher == null ? null : new ItemStack(DecorationVoucherRegistry.voucher);
    }

    /** Registry identity plus the exact clean recipe output; arbitrary NBT never substitutes for this receipt. */
    public static boolean isVoucher(ItemStack stack) {
        return stack != null && stack.stackSize > 0
            && DecorationVoucherRegistry.voucher != null
            && stack.getItem() == DecorationVoucherRegistry.voucher
            && stack.getItemDamage() == 0
            && !stack.hasTagCompound();
    }

    /** Matches the material transaction's exact clean voucher demand, restricted to the player's main inventory. */
    public static long available(EntityPlayer player) {
        if (!enabled() || player == null) return 0;
        long result = 0;
        for (ItemStack stack : player.inventory.mainInventory) if (isVoucher(stack)) result += stack.stackSize;
        return result;
    }

    /**
     * Reserve exactly one clean voucher from the source displayed by the server UI. Call only inside
     * the placement transaction, after its main-inventory snapshot; every unsuccessful cell must restore
     * that snapshot. This deliberately does not depend on material-source priority or the main-source toggle.
     */
    public static boolean reserveMain(EntityPlayer player) {
        if (!enabled() || player == null || player.worldObj == null || player.worldObj.isRemote) return false;
        for (int slot = 0; slot < player.inventory.mainInventory.length; slot++) {
            ItemStack voucher = player.inventory.mainInventory[slot];
            if (!isVoucher(voucher)) continue;
            if (--voucher.stackSize == 0) player.inventory.mainInventory[slot] = null;
            player.inventory.markDirty();
            return true;
        }
        return false;
    }

    static boolean hostAllowed(Block block) {
        if (block == null) return false;
        String name = Block.blockRegistry.getNameForObject(block);
        String modId = namespace(name);
        return DecorationVoucherConfig.view()
            .allows(modId)
            && !industrial(
                modId,
                block.getClass()
                    .getName());
    }

    static boolean safe(ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return false;
        Item item = stack.getItem();
        String namespace = modId(item);
        boolean selectedMixedDecoration = "extrautilities".equals(namespace) && Block.getBlockFromItem(item) != null
            && Block.getBlockFromItem(item) != net.minecraft.init.Blocks.air
            && DecorationVoucherConfig.view()
                .allows(stack);
        return !namespace.isEmpty() && (!industrial(
            namespace,
            item.getClass()
                .getName())
            || selectedMixedDecoration)
            && !(item instanceof IFluidContainerItem)
            && !FluidContainerRegistry.isFilledContainer(stack);
    }

    static String modId(Item item) {
        return namespace(Item.itemRegistry.getNameForObject(item));
    }

    private static String namespace(String registryName) {
        if (registryName == null) return "";
        int colon = registryName.indexOf(':');
        return colon <= 0 ? "" : DecorationVoucherConfig.normalize(registryName.substring(0, colon));
    }

    static boolean industrial(String modId, String nativeClass) {
        String namespace = DecorationVoucherConfig.normalize(modId);
        String type = DecorationVoucherConfig.normalize(nativeClass);
        return INDUSTRIAL_MOD_IDS.contains(namespace) || namespace.startsWith("gregtech")
            || namespace.startsWith("gtplusplus")
            || namespace.startsWith("appliedenergistics")
            || type.startsWith("gregtech.")
            || type.startsWith("appeng.")
            || type.startsWith("gtplusplus.")
            || type.startsWith("bartworks.")
            || type.startsWith("com.github.bartimaeusnek.")
            || type.startsWith("tectech.")
            || type.startsWith("goodgenerator.");
    }
}
