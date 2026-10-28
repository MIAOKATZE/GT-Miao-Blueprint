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
        nativeDeadline();
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
        try {
            HologramDynamicDefinitionRegression.DynamicController machine = (HologramDynamicDefinitionRegression.DynamicController) HologramDetachedController
                .create(id, com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.DEFAULT);
            net.minecraft.item.ItemStack signal = new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone, 1);
            com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
                .setChannelData(signal, "shape", 1);
            HologramCapture capture = HologramCapture
                .collect(machine, signal, machine.getExtendedFacing(), null, System.nanoTime() - 1L);
            SimpleAssert
                .that(capture.pending && !capture.incomplete, "explicit expired client slice yields native traversal");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (capture.pending && System.nanoTime() < deadline)
                capture = HologramCapture.collect(machine, signal, machine.getExtendedFacing(), capture);
            SimpleAssert
                .that(!capture.pending && !capture.incomplete, "existing server-default overload resumes same capture");
            SimpleAssert.eq(1, machine.constructions, "continuation never reenters native construct");
            SimpleAssert.eq(0, prototype.constructions, "native declaration never mutates registry prototype");
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
