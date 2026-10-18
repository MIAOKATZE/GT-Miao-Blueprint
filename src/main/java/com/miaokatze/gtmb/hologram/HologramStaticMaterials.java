package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

/** Detached theoretical demand. It never observes inventory, opens a session, or authorizes payment. */
public final class HologramStaticMaterials {

    private HologramStaticMaterials() {}

    /** Call native providers on the owning game thread, with a bounded number of cells per tick. */
    public static final class Builder {

        private final Map<Object, NBTTagCompound> rows = new LinkedHashMap<>();
        private int unaccounted;
        private String detail = "";
        private boolean cacheReviewRequired, alternatives;

        /** Inputs are the retained source payload, not appearance-only NBT from a public preview. */
        public void add(Block block, int metadata, ItemStack target, NBTTagCompound tile, NBTTagCompound item)
            throws IOException {
            if (block == Blocks.air) return;
            if (block == null || target == null || target.getItem() == null) throw new IOException("材料身份无法解析");
            ItemStack shell = new ItemStack(target.getItem(), 1, target.getItemDamage());
            if (HologramRecovery.hasSeal(target)) {
                shell = target.copy();
                shell.stackSize = 1;
                addItems(Collections.singletonList(shell));
                addItems(HologramGtswnPlacementMaterials.extraMaterials(block, tile));
                return;
            }
            HologramEnergyPayload.Prepared cache = HologramEnergyPayload.prepare(block, metadata, tile, item);
            NBTTagCompound selectedTile = cache.tileEmpty, selectedItem = cache.itemEmpty;
            HologramEssentia.validatePlacement(block, metadata, selectedTile);
            HologramNativePlacement.Adapter adapter = HologramNativePlacement.adapter(block);
            HologramBlueprintResources.Plan plan;
            List<ItemStack> nativeComponents = Collections.emptyList();
            boolean decoration = HologramDecorMaterials.supports(block, selectedTile);
            if (HologramAeBlueprint.supports(block, selectedTile)) {
                plan = HologramAeBlueprint.resourcePlan(block, metadata, selectedTile, shell);
            } else {
                if (adapter != null)
                    nativeComponents = HologramNativePlacement.materials(block, metadata, selectedTile);
                plan = HologramBlueprintResources.plan(selectedTile, nativeComponents)
                    .merge(HologramBlueprintResources.plan(selectedItem));
                if (adapter != null) {
                    if (adapter.includesInventoryDrops(block)) plan.removeItemsIncludedByNativeDrops(nativeComponents);
                    if (decoration) {
                        // The prebuilt bill is an exact theoretical option. Inventory-dependent raw substitution
                        // belongs to the final server review and must never be inferred from unknown stock.
                        plan.addNativeItems(nativeComponents);
                        alternatives = true;
                    } else plan
                        .addNativeItems(HologramNativePlacement.additionalMaterials(block, nativeComponents, shell));
                }
            }
            if (!decoration && !HologramGtswnPlacementMaterials.freeShell(block, shell))
                plan.addItems(Collections.singletonList(HologramEssentia.shellMaterial(shell)));
            plan.addNativeItems(HologramGtswnPlacementMaterials.extraMaterials(block, selectedTile));
            plan.addItems(HologramBlueprintCovers.materials(HologramBlueprintNbt.encodeCanonical(selectedTile)));
            // Commit only after all schemas have been understood, so a failed cell cannot publish half a bill.
            addItems(plan.items);
            for (HologramBlueprintResources.FluidCost cost : plan.fluids) addFluid(cost.stack, cost.amount);
            for (HologramEssentia.Cost cost : plan.essentia) addScalar(
                "essentia",
                cost.aspect,
                HologramEssentia.displayName(cost.aspect),
                "点",
                BigInteger.valueOf(cost.amount));
            addScalar("energy", "", "设备内部资源 EU", "EU", plan.energy);
            // RF/AE conversion rates and the selected cache payment policy are server configuration.
            // Keep these out of the accepted material budget; their final cost requires an explicit review.
            cacheReviewRequired |= !cache.demand.empty() || !cache.warnings.isEmpty();
        }

        public void unaccounted(String reason) {
            unaccounted++;
            if (detail.isEmpty()) detail = reason == null ? "材料未核算" : reason;
        }

        public NBTTagCompound snapshot() {
            NBTTagCompound result = new NBTTagCompound();
            result.setInteger("version", 1);
            result.setBoolean("complete", unaccounted == 0);
            result.setInteger("unaccounted", unaccounted);
            result.setString("detail", detail);
            result.setBoolean("cacheReviewRequired", cacheReviewRequired);
            result.setBoolean("alternatives", alternatives);
            NBTTagList list = new NBTTagList();
            for (NBTTagCompound row : rows.values()) list.appendTag(row.copy());
            result.setTag("rows", list);
            return result;
        }

        /** Public appearance previews cannot disclose private inventories, recipes, owners, or device tags. */
        public NBTTagCompound publicSnapshot() {
            NBTTagCompound result = snapshot();
            NBTTagList visible = new NBTTagList();
            NBTTagList all = result.getTagList("rows", 10);
            int hidden = 0;
            for (int i = 0; i < all.tagCount(); i++) {
                NBTTagCompound row = all.getCompoundTagAt(i);
                if (row.getCompoundTag("item")
                    .hasKey("tag", 10)
                    || row.getCompoundTag("fluid")
                        .hasKey("Tag", 10))
                    hidden++;
                else visible.appendTag(row);
            }
            if (hidden > 0) {
                result.setBoolean("complete", false);
                result.setInteger("unaccounted", result.getInteger("unaccounted") + hidden);
            }
            // Provider failures may contain a device owner/frequency or a private NBT value.
            if (!result.getBoolean("complete")) result.setString("detail", "部分材料未公开或无法核算；开始建设后核对完整需求");
            result.setTag("rows", visible);
            return result;
        }

        private void addItems(List<ItemStack> items) throws IOException {
            for (ItemStack item : items) {
                if (item == null || item.getItem() == null || item.stackSize <= 0) continue;
                Object key = new HologramBlueprintResources.ExactKey(item, null);
                NBTTagCompound row = rows.get(key);
                if (row == null) {
                    row = row("item", "件");
                    ItemStack identity = item.copy();
                    identity.stackSize = 1;
                    row.setTag("item", identity.writeToNBT(new NBTTagCompound()));
                    String registry = (String) Item.itemRegistry.getNameForObject(identity.getItem());
                    if (registry == null || registry.isEmpty()) throw new IOException("物品未注册");
                    row.setString("itemRegistry", registry);
                    rows.put(key, row);
                }
                amount(row, BigInteger.valueOf(item.stackSize));
            }
        }

        private void addFluid(FluidStack fluid, long quantity) {
            Object key = new HologramBlueprintResources.ExactKey(null, fluid);
            NBTTagCompound row = rows.get(key);
            if (row == null) {
                row = row("fluid", "mB");
                FluidStack identity = fluid.copy();
                identity.amount = 1;
                row.setTag("fluid", identity.writeToNBT(new NBTTagCompound()));
                rows.put(key, row);
            }
            amount(row, BigInteger.valueOf(quantity));
        }

        private void addScalar(String kind, String identity, String label, String unit, BigInteger quantity) {
            if (quantity.signum() <= 0) return;
            String key = kind + ":" + identity;
            NBTTagCompound row = rows.get(key);
            if (row == null) {
                row = row(kind, unit);
                row.setString("aspect", identity);
                row.setString("label", label);
                rows.put(key, row);
            }
            amount(row, quantity);
        }

        private static NBTTagCompound row(String kind, String unit) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("kind", kind);
            row.setString("unit", unit);
            row.setBoolean("availableKnown", false);
            row.setString("requiredExact", "0");
            return row;
        }

        private static void amount(NBTTagCompound row, BigInteger quantity) {
            row.setString(
                "requiredExact",
                new BigInteger(row.getString("requiredExact")).add(quantity)
                    .toString());
        }
    }
}
