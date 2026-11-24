// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import org.apache.commons.lang3.tuple.Pair;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.util.HatchElementBuilder;

/** Native tier/hatch candidates still run; only the lifetime of their trace snapshots changes. */
final class HologramNativeChannelTraceRegression {

    private static final String GRADE = "fixture_grade";

    private HologramNativeChannelTraceRegression() {}

    static void run() {
        Object controller = new Object();
        ItemStack master = new ItemStack(Items.feather, 3);
        ItemStack explicit = master.copy();
        ChannelDataAccessor.setChannelData(explicit, GRADE, 2);
        IStructureElement<Object> tier = StructureUtility.withChannel(
            GRADE,
            StructureUtility.ofBlocksTiered(
                (block, meta) -> meta,
                Arrays.asList(Pair.of(Blocks.stone, 0), Pair.of(Blocks.wool, 2), Pair.of(Blocks.wool, 3)),
                -1,
                (context, value) -> { throw new AssertionError("candidate read invoked tier setter"); },
                context -> { throw new AssertionError("candidate read invoked tier getter"); }));
        IStructureElement<Object> hatch = StructureUtility.withChannel(
            GRADE,
            HatchElementBuilder.<Object>builder()
                .adder((context, tile, casing) -> { throw new AssertionError("candidate read invoked hatch adder"); })
                .casingIndex(1)
                .hint(1)
                .hatchItemFilter((context, signal) -> stack -> stack.getItemDamage() == signal.stackSize)
                .build());
        checkNativeOptions(controller, explicit, tier, hatch, 2);
        checkNativeOptions(controller, master, tier, hatch, 3);

        HologramChannelTrace.Accumulator accumulator = new HologramChannelTrace.Accumulator(controller);
        SimpleAssert.that(accumulator.snapshot() == null, "no evidence before the first scope is closed");
        HologramChannelTrace.Report reference = null;
        for (int i = 0; i < 128; i++) {
            ItemStack signal = (i & 1) == 0 ? explicit : master;
            reference = HologramChannelTrace.merge(reference, query(controller, signal, i, null));
            query(controller, signal, i, accumulator);
        }
        HologramChannelTrace.Report snapshot = accumulator.snapshot();
        eqReport(reference, snapshot);
        SimpleAssert.eq(
            Arrays.asList(GRADE, "fixture_hatch"),
            new ArrayList<>(snapshot.observations.keySet()),
            "scopes keep the channels in first-observed order");
        SimpleAssert.eq(3, snapshot.observations.get(GRADE).operations, "operations from prior scopes are not lost");
        SimpleAssert.that(!snapshot.observations.get(GRADE).explicit, "last observed input uses master fallback");
        SimpleAssert.that(snapshot == accumulator.snapshot(), "unchanged trace returns the same frozen snapshot");

        // A different tier, another subchannel choice, or another controller request must publish independent evidence.
        ItemStack newTier = master.copy();
        newTier.stackSize = 1;
        ChannelDataAccessor.setChannelData(newTier, GRADE, 1);
        Object anotherController = new Object();
        eqReport(query(anotherController, newTier, 0, null), readNewCapture(anotherController, newTier));
        HologramChannelTrace.Scope outer = HologramChannelTrace.begin(controller);
        HologramChannelTrace.record(master, "outer", 3, 1);
        HologramChannelTrace.Accumulator inner = new HologramChannelTrace.Accumulator(anotherController);
        query(anotherController, explicit, 0, inner);
        HologramChannelTrace.record(master, "after_nested", 3, 1);
        SimpleAssert.eq(
            Arrays.asList("outer", "after_nested"),
            new ArrayList<>(HologramChannelTrace.end(outer).observations.keySet()),
            "nested native capture restores its caller's scope");
        SimpleAssert.that(!inner.snapshot().observations.containsKey("outer"), "controller evidence stays isolated");
        query(controller, newTier, 0, accumulator);
        SimpleAssert.eq(3, snapshot.observations.get(GRADE).effective, "published older snapshot remains immutable");
        HologramChannelTrace.Report updated = accumulator.snapshot();
        SimpleAssert.eq(1, updated.observations.get(GRADE).effective, "latest native input stays visible");
        SimpleAssert.that(updated.observations.get(GRADE).explicit, "new named choice invalidates the old fallback");
        SimpleAssert.that(updated.generation > snapshot.generation, "trace retains latest completed scope generation");
    }

    private static void checkNativeOptions(Object context, ItemStack signal, IStructureElement<Object> tier,
        IStructureElement<Object> hatch, int expected) {
        ItemStack candidate = tier.getBlocksToPlace(context, null, 12, 64, -6, signal, null)
            .getStacks()
            .iterator()
            .next();
        SimpleAssert.eq(expected, candidate.getItemDamage(), "native tier selects the subchannel or master fallback");
        Predicate<ItemStack> allowed = hatch.getBlocksToPlace(context, null, 11, 64, -6, signal, null)
            .getPredicate();
        SimpleAssert.that(allowed.test(candidate), "native GT hatch filter sees the effective tier");
        SimpleAssert.that(!allowed.test(new ItemStack(Blocks.wool, 1, 8)), "different hatch metadata is rejected");
    }

    private static HologramChannelTrace.Report readNewCapture(Object controller, ItemStack signal) {
        HologramChannelTrace.Accumulator next = new HologramChannelTrace.Accumulator(controller);
        query(controller, signal, 0, next);
        return next.snapshot();
    }

    private static HologramChannelTrace.Report query(Object controller, ItemStack signal, int i,
        HologramChannelTrace.Accumulator accumulator) {
        HologramChannelTrace.Scope scope = HologramChannelTrace.begin(controller);
        if (i % 11 != 5) {
            // The headless suite does not load the production mixin; feed it the real native accessor's result.
            HologramChannelTrace.record(signal, GRADE, ChannelDataAccessor.withChannel(signal, GRADE).stackSize, 1);
            HologramChannelTrace.record(signal, "fixture_hatch", signal.stackSize, 4);
            if (i % 3 == 0)
                HologramChannelTrace.record(signal, GRADE, ChannelDataAccessor.getChannelData(signal, GRADE), 2);
            if (i % 8 == 0) {
                ItemStack material = new ItemStack(Blocks.wool, 1, signal.stackSize);
                HologramChannelTrace.declareMaterials(GRADE, Collections.singletonList(material));
                material.stackSize = 8;
            }
        }
        if (accumulator == null) return HologramChannelTrace.end(scope);
        HologramChannelTrace.endInto(scope, accumulator);
        return null;
    }

    private static void eqReport(HologramChannelTrace.Report expected, HologramChannelTrace.Report actual) {
        SimpleAssert.that(expected.context == actual.context, "same owning controller evidence");
        List<String> keys = new ArrayList<>(expected.observations.keySet());
        SimpleAssert.eq(keys, new ArrayList<>(actual.observations.keySet()), "same observation order");
        for (String key : keys) {
            HologramChannelTrace.Observation before = expected.observations.get(key),
                after = actual.observations.get(key);
            SimpleAssert.eq(before.effective, after.effective, "same effective tier");
            SimpleAssert.eq(before.operations, after.operations, "same accumulated native operations");
            SimpleAssert.eq(before.explicit, after.explicit, "same named channel presence");
        }
        SimpleAssert.eq(expected.materials.keySet(), actual.materials.keySet(), "same material declaration groups");
        ItemStack material = actual.materials.get(GRADE)
            .get(0);
        SimpleAssert.eq(
            expected.materials.get(GRADE)
                .get(0)
                .getItemDamage(),
            material.getItemDamage(),
            "same material");
        SimpleAssert.eq(1, material.stackSize, "capture keeps an independent defensive material copy");
    }
}
