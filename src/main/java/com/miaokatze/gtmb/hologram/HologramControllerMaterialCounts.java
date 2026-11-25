// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.github.bsideup.jabel.Desugar;

/** Base item occurrences in settled local targets. No resource, inventory or payment authority. */
public final class HologramControllerMaterialCounts {

    @Desugar
    public record Identity(Item item, String registry, int damage) {}

    @Desugar
    public record Row(Identity identity, long cells) {}

    @Desugar
    public record Contribution(boolean included, Identity identity) {}

    public static final Contribution EXCLUDED = new Contribution(false, null);
    public static final Contribution UNLISTED = new Contribution(true, null);

    private final Map<Identity, Long> amounts;
    private final List<Row> rows;
    private final int total, unlisted;

    private HologramControllerMaterialCounts(Map<Identity, Long> amounts, int total, int unlisted) {
        this.amounts = amounts;
        this.total = total;
        this.unlisted = unlisted;
        List<Row> ordered = new ArrayList<>(amounts.size());
        amounts.forEach((identity, count) -> ordered.add(new Row(identity, count)));
        rows = Collections.unmodifiableList(ordered);
    }

    public List<Row> rows() {
        return rows;
    }

    public int total() {
        return total;
    }

    public int unlisted() {
        return unlisted;
    }

    /** Available only to a caller with both exact sides of a topology-preserving point edit. */
    public HologramControllerMaterialCounts replace(Contribution before, Contribution after) {
        if (before.equals(after)) return this;
        Map<Identity, Long> changed = new LinkedHashMap<>(amounts);
        if (before.identity() != null) changed.compute(before.identity(), (identity, count) -> {
            if (count == null || count < 1) throw new IllegalStateException("Missing material baseline contribution");
            return count == 1 ? null : count - 1;
        });
        if (after.identity() != null) changed.merge(after.identity(), 1L, Long::sum);
        int count = total + (after.included() ? 1 : 0) - (before.included() ? 1 : 0);
        int unknown = unlisted + (after.equals(UNLISTED) ? 1 : 0) - (before.equals(UNLISTED) ? 1 : 0);
        if (count < 0 || unknown < 0 || unknown > count)
            throw new IllegalStateException("Invalid material baseline delta");
        return new HologramControllerMaterialCounts(changed, count, unknown);
    }

    /** No drops, adapter, displayed tile NBT, world read or alternative targets enter this description. */
    public static Contribution material(ItemStack target, boolean unresolved) {
        if (unresolved || target == null || target.hasTagCompound()) return UNLISTED;
        try {
            Item item = target.getItem();
            String registry = item == null ? null : (String) Item.itemRegistry.getNameForObject(item);
            return registry == null || registry.isEmpty() ? UNLISTED
                : new Contribution(true, new Identity(item, registry, target.getItemDamage()));
        } catch (RuntimeException | LinkageError unavailable) {
            return UNLISTED;
        }
    }

    public static boolean sourceWater(HologramControllerTarget.Cell cell, int x, int y, int z) {
        return cell != null && cell.kind() == HologramControllerTarget.TargetKind.MACHINE_SOURCE_WATER
            && cell.targetBlock() == Blocks.water
            && cell.wantMeta() == 0
            && cell.x() - x == cell.dx()
            && cell.y() - y == cell.dy()
            && cell.z() - z == cell.dz();
    }

    /** Owner-thread builder adds one contribution in the capture's existing settled target loop. */
    public static final class Builder {

        private Map<Identity, Long> amounts = new LinkedHashMap<>();
        private int total, unlisted;

        public void add(HologramControllerTarget.Cell cell, int x, int y, int z) {
            if (cell.anchor() || "minecraft:air".equals(cell.wantId())
                || cell.x() == x && cell.y() == y && cell.z() == z
                || sourceWater(cell, x, y, z)) return;
            add(material(cell.targetStack(), cell.targetBlock() == null || cell.missingBlock() || cell.iconOnly()));
        }

        public void add(Contribution contribution) {
            if (!contribution.included()) return;
            total++;
            Identity identity = contribution.identity();
            if (identity == null) unlisted++;
            else amounts.merge(identity, 1L, Long::sum);
        }

        public HologramControllerMaterialCounts seal() {
            if (amounts == null) throw new IllegalStateException("Material counts already published");
            HologramControllerMaterialCounts result = new HologramControllerMaterialCounts(amounts, total, unlisted);
            amounts = null;
            return result;
        }
    }
}
