package com.miaokatze.gtmb.hologram;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Pure source reconciliation plus detached native deadline test; no snapshot transport or game launch. */
public final class HologramLocalAuthorStructuresRegression {

    private HologramLocalAuthorStructuresRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLocalAuthorStructuresRegression.class, args)) return;
        run();
        System.out.println("Local author structure reconciliation regression passed");
    }

    public static void run() throws Exception {
        JsonObject baseline = row("stone"), next = row("glass"), current = copy(baseline);
        String untouched = current.toString();
        JsonObject result = HologramLocalAuthorStructures.mergeCell(current, baseline, next, false);
        SimpleAssert.eq(
            "minecraft:glass",
            result.get("item")
                .getAsString(),
            "plain native defaults regenerate");
        SimpleAssert.eq(untouched, current.toString(), "staging never changes current source");
        SimpleAssert.that(
            HologramLocalAuthorStructures.mergeCell(current, baseline, null, false) == null,
            "removed unedited declaration disappears");
        current.addProperty("item", "minecraft:gold_block");
        result = HologramLocalAuthorStructures.mergeCell(current, baseline, next, false);
        SimpleAssert.eq(
            "minecraft:gold_block",
            result.get("item")
                .getAsString(),
            "explicit chosen target survives");
        SimpleAssert.eq(
            "minecraft:glass",
            result.get("defaultItem")
                .getAsString(),
            "restore default follows new declaration");
        SimpleAssert.that(
            HologramLocalAuthorStructures.mergeCell(current, baseline, null, false) != null,
            "explicit edits outside smaller declaration remain");
        current = copy(baseline);
        current.addProperty("tileNbt", "opaque-native-payload");
        current.addProperty("futureExtension", "opaque-json");
        current.addProperty("prompt", "keep note");
        result = HologramLocalAuthorStructures.mergeCell(current, baseline, next, false);
        SimpleAssert.eq(
            "opaque-native-payload",
            result.get("tileNbt")
                .getAsString(),
            "native payload preserved exactly");
        SimpleAssert.eq(
            "opaque-json",
            result.get("futureExtension")
                .getAsString(),
            "unknown source members preserved");
        SimpleAssert.eq(
            "keep note",
            result.get("prompt")
                .getAsString(),
            "annotation preserved");
        current = copy(baseline);
        result = HologramLocalAuthorStructures.mergeCell(current, baseline, next, true);
        SimpleAssert.eq(
            "minecraft:stone",
            result.get("item")
                .getAsString(),
            "shared target never overwritten");
        SimpleAssert.eq(
            0,
            result.getAsJsonArray("allowedTargets")
                .size(),
            "shared candidate constraints intersect, not union");
        SimpleAssert.that(
            !result.get("authorDeclarationComplete")
                .getAsBoolean(),
            "shared unresolved facts never claim completeness");
        SimpleAssert.that(
            HologramLocalAuthorStructures.mergeCell(current, baseline, null, true) == null,
            "core removes only this membership from departed shared cell");
        retainedContainerOwnership();
        nativeDeadline();
    }

    private static void retainedContainerOwnership() throws Exception {
        for (boolean removed : new boolean[] { false, true }) {
            JsonObject baseline = row("stone"), current = copy(baseline);
            // Intentionally non-interned; an opaque native payload must not be serialized/decoded/copied as text.
            String payload = new String(new char[] { 'o', 'p', 'a', 'q', 'u', 'e', '\n', '\u4e2d' });
            current.addProperty("tileNbt", payload);
            current.add(
                "futureExtension",
                new JsonParser()
                    .parse("{\"nested\":[{\"value\":\"before\"},null,1.2500,-0,9223372036854775808123,1e+20]}"));
            String before = current.toString();
            JsonObject result = HologramLocalAuthorStructures
                .mergeCell(current, baseline, removed ? null : row("glass"), false);
            SimpleAssert.eq(before, current.toString(), "reconciliation leaves retained source untouched");
            SimpleAssert.eq(
                current.get("futureExtension")
                    .toString(),
                result.get("futureExtension")
                    .toString(),
                "unknown nested null/numeric lexical values survive both retention branches");
            SimpleAssert.that(
                payload == result.get("tileNbt")
                    .getAsString(),
                "immutable opaque NBT String is shared, not serialized and reparsed");
            JsonArray sourceNested = current.getAsJsonObject("futureExtension")
                .getAsJsonArray("nested");
            JsonArray resultNested = result.getAsJsonObject("futureExtension")
                .getAsJsonArray("nested");
            resultNested.get(0)
                .getAsJsonObject()
                .addProperty("value", "result edit");
            SimpleAssert.eq(
                "before",
                sourceNested.get(0)
                    .getAsJsonObject()
                    .get("value")
                    .getAsString(),
                "mutating copied nested object cannot affect retained source");
            sourceNested.add(new JsonObject());
            SimpleAssert.eq(6, resultNested.size(), "mutating source nested array cannot affect copied result");
            SimpleAssert.that(
                sourceNested.get(2) == resultNested.get(2),
                "immutable numeric primitive retains its original lexical representation");
        }
    }

    private static void nativeDeadline() throws Exception {
        int id = 32754;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[id];
        // This headless JVM has no Minecraft resource manager. Warm the same common GT fixture as the existing
        // declaration suite before exercising owner-thread slicing; this is not a native client-render test.
        String threadName = Thread.currentThread()
            .getName();
        HologramDynamicDefinitionRegression.DynamicController prototype;
        try {
            Thread.currentThread()
                .setName("Server thread");
            prototype = new HologramDynamicDefinitionRegression.DynamicController();
        } finally {
            Thread.currentThread()
                .setName(threadName);
        }
        gregtech.api.GregTechAPI.METATILEENTITIES[id] = prototype;
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        try (com.miaokatze.gtmb.api.GTMBExtensions.Registration extension = com.miaokatze.gtmb.api.BlueprintSnapshots
            .register(
                "gtmb_test:declaration-not-capture",
                com.miaokatze.gtmb.api.BlueprintSnapshots.Stage.CAPTURE_COMPLETE,
                snapshot -> {
                    completed.incrementAndGet();
                    return new net.minecraft.nbt.NBTTagCompound();
                })) {
            HologramDynamicDefinitionRegression.DynamicController machine = (HologramDynamicDefinitionRegression.DynamicController) HologramDetachedController
                .create(id, com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.DEFAULT);
            net.minecraft.item.ItemStack signal = new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone, 1);
            com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
                .setChannelData(signal, "shape", 1);
            java.util.concurrent.atomic.AtomicInteger sourceCalls = new java.util.concurrent.atomic.AtomicInteger();
            try (com.miaokatze.gtmb.api.GTMBExtensions.Registration legacy = com.miaokatze.gtmb.api.StructureSources
                .register(
                    "gtmb_test:legacy-declaration-source",
                    HologramDynamicDefinitionRegression.DynamicController.class,
                    (controller, trigger) -> {
                        sourceCalls.incrementAndGet();
                        return null;
                    })) {
                try {
                    HologramCapture.collectDeclaration(
                        machine,
                        signal,
                        machine.getExtendedFacing(),
                        null,
                        System.nanoTime() + 100_000_000L);
                    throw new AssertionError("synthetic declaration accepted a legacy source callback");
                } catch (HologramExtensionExecution.ReviewRequired expected) {}
                SimpleAssert
                    .eq(0, sourceCalls.get(), "synthetic entry blocks matching legacy describe before invocation");
                SimpleAssert.eq(0, machine.constructions, "rejected source cannot fall through to native construct");
                SimpleAssert.eq(0, completed.get(), "rejected declaration cannot emit capture completion");
            }
            HologramCapture capture;
            try (com.miaokatze.gtmb.api.GTMBExtensions.Registration safe = com.miaokatze.gtmb.api.StructureSources
                .register(
                    "gtmb_test:safe-declaration-source",
                    HologramDynamicDefinitionRegression.DynamicController.class,
                    (controller, trigger) -> {
                        sourceCalls.incrementAndGet();
                        return null;
                    },
                    true)) {
                capture = HologramCapture
                    .collectDeclaration(machine, signal, machine.getExtendedFacing(), null, System.nanoTime() - 1L);
            }
            SimpleAssert.eq(1, sourceCalls.get(), "explicit client-safe declaration source executes once");
            SimpleAssert
                .that(capture.pending && !capture.incomplete, "explicit expired client slice yields native traversal");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (capture.pending && System.nanoTime() < deadline)
                capture = HologramCapture.collect(machine, signal, machine.getExtendedFacing(), capture);
            SimpleAssert
                .that(!capture.pending && !capture.incomplete, "existing server-default overload resumes same capture");
            SimpleAssert.eq(1, machine.constructions, "continuation never reenters native construct");
            SimpleAssert.eq(0, prototype.constructions, "native declaration never mutates registry prototype");
            SimpleAssert.eq(0, completed.get(), "synthetic declaration does not masquerade as captured world data");
            HologramCapture actual = HologramCapture.collect(machine, signal, machine.getExtendedFacing());
            deadline = System.nanoTime() + 5_000_000_000L;
            while (actual.pending && System.nanoTime() < deadline)
                actual = HologramCapture.collect(machine, signal, machine.getExtendedFacing(), actual);
            SimpleAssert.that(!actual.pending && !actual.incomplete, "actual capture completes");
            SimpleAssert.eq(1, completed.get(), "actual capture keeps complete callback");
            HologramCapture.collect(machine, signal, machine.getExtendedFacing(), actual);
            SimpleAssert.eq(1, completed.get(), "completed capture cannot repeat completion callback");
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = previous;
        }
    }

    private static JsonObject row(String material) {
        JsonObject row = new JsonObject();
        row.addProperty("item", "minecraft:" + material);
        row.addProperty("block", "minecraft:" + material);
        row.addProperty("defaultItem", "minecraft:" + material);
        row.addProperty("damage", 0);
        row.addProperty("defaultDamage", 0);
        row.addProperty("metadata", 0);
        row.addProperty("facing", -1);
        row.addProperty("authorDeclarationComplete", true);
        JsonArray allowed = new JsonArray();
        JsonObject candidate = new JsonObject();
        candidate.addProperty("item", "minecraft:" + material);
        candidate.addProperty("damage", 0);
        allowed.add(candidate);
        row.add("allowedTargets", allowed);
        return row;
    }

    private static JsonObject copy(JsonObject source) {
        return new JsonParser().parse(source.toString())
            .getAsJsonObject();
    }
}
