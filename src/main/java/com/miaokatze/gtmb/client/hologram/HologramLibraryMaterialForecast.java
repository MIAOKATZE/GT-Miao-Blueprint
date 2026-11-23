// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;

/** A partial ordinary-material forecast. Nothing here proposes a payable bill or reads a registry or world. */
final class HologramLibraryMaterialForecast {

    @Desugar
    record Identity(String registry, int damage) {}

    @Desugar
    record Need(Identity identity, long cells) {}

    @Desugar
    record Unpriced(long payload, long unsupported, long limit, long nonOrdinary, long retention) {

        long cells() {
            return payload + unsupported + limit + nonOrdinary + retention;
        }
    }

    /** The resolver must supply the canonical (not requested/aliased) name and a detached, plain item. */
    @Desugar
    record Resolved(String canonicalRegistry, ItemStack sample) {}

    /** CLIENT MAIN THREAD ONLY. Return null when identity or absence of provider-dependent costs is unproven. */
    @FunctionalInterface
    interface ClientExactResolver {

        Resolved resolveOrdinary(Identity identity);
    }

    private final List<Need> candidates;
    private final Unpriced unpriced;
    private final int airCells;
    private final boolean retentionNeedsReview;

    private HologramLibraryMaterialForecast(List<Need> candidates, Unpriced unpriced, int airCells,
        boolean retentionNeedsReview) {
        this.candidates = Collections.unmodifiableList(candidates);
        this.unpriced = unpriced;
        this.airCells = airCells;
        this.retentionNeedsReview = retentionNeedsReview;
    }

    static HologramLibraryMaterialForecast fromCapture(HologramLibraryClient.DisplaySource captured) {
        return captured != null && captured.capturedIndexedMaterialSource() ? project(captured.savedMaterials) : null;
    }

    /** Reads only the bounded source summary, never geometry, an NBT payload, or the file. */
    static HologramLibraryMaterialForecast project(HologramSavedMaterialSource source) {
        Map<Identity, Long> counts = new LinkedHashMap<>();
        long nonOrdinary = 0, retained = 0;
        for (HologramSavedMaterialSource.Count row : source.variants()) {
            HologramSavedMaterialSource.Variant variant = row.variant();
            if (source.retentionNeedsReview()) retained += row.cells();
            else if (!ordinary(variant)) nonOrdinary += row.cells();
            else counts.merge(new Identity(variant.item(), variant.damage()), (long) row.cells(), Long::sum);
        }
        List<Need> known = new ArrayList<>(counts.size());
        for (Map.Entry<Identity, Long> entry : counts.entrySet()) known.add(new Need(entry.getKey(), entry.getValue()));
        return new HologramLibraryMaterialForecast(
            known, new Unpriced(source.payloadCells(), source.unsupportedCells(), source.limitCells(), nonOrdinary,
                retained),
            source.airCells(), source.retentionNeedsReview());
    }

    List<Need> candidates() {
        return candidates;
    }

    Unpriced unpriced() {
        return unpriced;
    }

    /**
     * Client main thread adapter. Only already classified ordinary identities reach the supplied resolver.
     * No default resolver: community providers may override vanilla identities and must not be probed here.
     */
    NBTTagCompound stockRows(ClientExactResolver resolver, ItemStack[] inventory, int protectedSlot) {
        NBTTagCompound theory = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        Map<Identity, ItemStack> resolved = new LinkedHashMap<>();
        long unavailable = 0;
        for (Need need : candidates) {
            Identity identity = need.identity();
            Resolved item = resolve(resolver, identity);
            if (item == null) {
                unavailable += need.cells();
                continue;
            }
            resolved.put(identity, item.sample());
            NBTTagCompound row = new NBTTagCompound();
            row.setString("kind", "item");
            row.setString("itemRegistry", identity.registry());
            row.setString("label", identity.registry());
            row.setString("unit", "个");
            row.setLong("required", need.cells());
            // Stable identity only: the existing local model accepts registry and Damage without a numeric item id.
            NBTTagCompound portable = new NBTTagCompound();
            portable.setShort("Damage", (short) identity.damage());
            row.setTag("item", portable);
            rows.appendTag(row);
        }
        theory.setTag("rows", rows);
        theory.setInteger("airCells", airCells);
        theory.setLong("unaccounted", unpriced.cells() + unavailable);
        theory.setLong("unresolvedItems", unavailable);
        theory.setBoolean("retentionNeedsReview", retentionNeedsReview);
        return HologramLocalMaterialReview.stockRows(theory, inventory, protectedSlot,
            row -> resolved.get(new Identity(row.getString("itemRegistry"), row.getCompoundTag("item")
                .getShort("Damage"))));
    }

    private static Resolved resolve(ClientExactResolver resolver, Identity identity) {
        if (resolver == null) return null;
        try {
            Resolved result = resolver.resolveOrdinary(identity);
            ItemStack sample = result == null ? null : result.sample();
            return sample != null && identity.registry().equals(result.canonicalRegistry())
                && sample.getItem() != null && !sample.hasTagCompound() && sample.getItemDamage() == identity.damage()
                    ? result : null;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Fixed-cost candidates only, even at default pose. Aliases, mod blocks, and directional shapes stay unknown. */
    private static boolean ordinary(HologramSavedMaterialSource.Variant variant) {
        int damage = variant.damage();
        if (variant.controller() || variant.facing() != -1 || damage < 0 || damage != variant.metadata()
            || !variant.block().equals(variant.item())) return false;
        return switch (variant.item()) {
            case "minecraft:stone", "minecraft:cobblestone", "minecraft:glass", "minecraft:brick_block" -> damage == 0;
            case "minecraft:stonebrick" -> damage <= 3;
            case "minecraft:planks" -> damage <= 5;
            case "minecraft:wool", "minecraft:stained_glass" -> damage <= 15;
            default -> false;
        };
    }
}
