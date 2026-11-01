package com.miaokatze.gtmb.hologram;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.enums.HatchElement;

/** Negative contracts at the common read boundary, independent of any installed controller family. */
final class HologramSemanticSafetyRegression {

    static void run() {
        unknownFiltersAreNeverInvoked();
        metadataRemainsProvenance();
    }

    private static void unknownFiltersAreNeverInvoked() {
        Object builder = new Object();
        IStructureElement<Object> element = StructureUtility.isAir();
        AtomicInteger callbacks = new AtomicInteger();
        HologramHatchPolicy.declare(builder, Collections.singletonList(HatchElement.Muffler), true);
        HologramHatchPolicy.andFunction(builder, context -> {
            callbacks.incrementAndGet();
            return (java.util.function.Predicate<ItemStack>) stack -> true;
        });
        HologramHatchPolicy.built(builder, element);
        SimpleAssert.that(
            HologramHatchPolicy.predicate(element, null, null) == null,
            "unknown Function invalidates the recoverable declaration");
        HologramHatchPolicy.declare(builder, Collections.singletonList(HatchElement.Muffler), true);
        HologramHatchPolicy.andBiFunction(builder, (context, trigger) -> {
            callbacks.incrementAndGet();
            return (java.util.function.Predicate<ItemStack>) stack -> true;
        });
        HologramHatchPolicy.built(builder, element);
        SimpleAssert.that(
            HologramHatchPolicy.predicate(element, null, null) == null,
            "unknown BiFunction invalidates the recoverable declaration");
        SimpleAssert.that(callbacks.get() == 0, "display and planning never evaluate unknown filters");
        HologramHatchPolicy.reset(builder);
    }

    private static void metadataRemainsProvenance() {
        try {
            NBTTagCompound semantic = new NBTTagCompound();
            semantic.setString("purpose", "LIVE_SNAPSHOT");
            semantic.setInteger("fixtureSchema", 1);
            HologramPresets.Preset original = new HologramPresets.Preset();
            original.semanticNbt = HologramBlueprintNbt.encodeCanonical(semantic);
            HologramPresets.Structure source = new HologramPresets.Structure();
            source.id = "machine";
            source.semanticNbt = original.semanticNbt;
            original.structures.add(source);
            HologramPresets.Preset copied = HologramSnapshotMetadataSource.copyMetadata(original);
            SimpleAssert.that(original.semanticNbt.equals(copied.semanticNbt), "preset semantic receipt survives copy");
            SimpleAssert.that(
                source.semanticNbt.equals(copied.structures.get(0).semanticNbt),
                "member semantic receipt survives copy");
            String raw = "{\"blueprint\":{\"semanticNbt\":\"" + original.semanticNbt
                + "\",\"cells\":[],\"palette\":[]}}";
            byte[] compact = HologramBlueprintCompactCodec.recompress(raw.getBytes(StandardCharsets.UTF_8), 1024);
            SimpleAssert.that(
                HologramBlueprintCompactCodec.parse(compact)
                    .getAsJsonObject("blueprint")
                    .get("semanticNbt")
                    .getAsString()
                    .equals(original.semanticNbt),
                "dictionary roundtrip preserves semantic receipt exactly");
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

}
