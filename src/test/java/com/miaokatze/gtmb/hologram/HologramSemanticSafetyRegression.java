package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchMuffler;
import gregtech.api.util.HatchElementBuilder;

/** Negative contracts at the common read boundary, independent of any installed controller family. */
final class HologramSemanticSafetyRegression {

    static void run() {
        standardHatchUnions();
        unknownFiltersAreNeverInvoked();
        metadataRemainsProvenance();
    }

    private static void standardHatchUnions() {
        int energyId = 32752, mufflerId = 32753;
        Block previousMachines = GregTechAPI.sBlockMachines;
        IMetaTileEntity previousEnergy = GregTechAPI.METATILEENTITIES[energyId],
            previousMuffler = GregTechAPI.METATILEENTITIES[mufflerId];
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            Block machines = new Block(net.minecraft.block.material.Material.iron) {};
            Block.blockRegistry.addObject(4057, "gtmb:union_fixture_machines", machines);
            Item.itemRegistry
                .addObject(4057, "gtmb:union_fixture_machines", new gregtech.common.blocks.ItemMachines(machines));
            GregTechAPI.sBlockMachines = machines;
            GregTechAPI.METATILEENTITIES[energyId] = (IMetaTileEntity) unsafe.allocateInstance(MTEHatchEnergy.class);
            GregTechAPI.METATILEENTITIES[mufflerId] = (IMetaTileEntity) unsafe.allocateInstance(MTEHatchMuffler.class);
            IHatchElement<?> union = HatchElement.Energy.or(HatchElement.ExoticEnergy);
            HatchElementBuilder<Object> builder = HatchElementBuilder.<Object>builder()
                .adder((context, tile, texture) -> { throw new AssertionError("union hatch adder executed"); })
                .hint(1)
                .casingIndex(1);
            HologramHatchPolicy.declare(builder, Collections.singletonList(union), true);
            HologramHatchPolicy.requirements(builder, Collections.singletonMap(union, 1));
            IStructureElement<Object> element = builder.build();
            HologramHatchPolicy.built(builder, element);
            Predicate<ItemStack> accepts = HologramHatchPolicy.predicate(element, null, new ItemStack(Blocks.stone));
            SimpleAssert.that(accepts != null, "a standard energy or exotic-energy group has a reviewed declaration");
            SimpleAssert
                .that(accepts.test(new ItemStack(machines, 1, energyId)), "the union accepts its real energy hatch");
            SimpleAssert
                .that(!accepts.test(new ItemStack(machines, 1, mufflerId)), "the union rejects an unrelated hatch");
            SimpleAssert.eq(
                Collections.singletonList(union),
                HologramHatchPolicy.declared(element),
                "or remains one requirement group rather than two required hatches");
            SimpleAssert.eq(
                Collections.singletonMap(union, 1),
                HologramHatchPolicy.requirements(element),
                "the original combined quota survives declaration capture");

            IHatchElement<Object> unknown = new IHatchElement<Object>() {

                public java.util.List<Class<? extends IMetaTileEntity>> mteClasses() {
                    throw new AssertionError("unknown union classes executed");
                }

                public gregtech.api.util.IGTHatchAdder<Object> adder() {
                    throw new AssertionError("unknown union adder executed");
                }

                public String name() {
                    throw new AssertionError("unknown union name executed");
                }

                public long count(Object context) {
                    throw new AssertionError("unknown union count executed");
                }

                public boolean matchesHatch(IMetaTileEntity tile) {
                    throw new AssertionError("unknown union matcher executed");
                }
            };
            Object unknownBuilder = new Object();
            IStructureElement<Object> unknownElement = StructureUtility.ofBlock(Blocks.stone, 0);
            HologramHatchPolicy
                .declare(unknownBuilder, Collections.singletonList(HatchElement.Energy.or(unknown)), false);
            HologramHatchPolicy.built(unknownBuilder, unknownElement);
            SimpleAssert.that(
                HologramHatchPolicy.predicate(unknownElement, null, null) == null,
                "a standard or wrapper cannot grant authority to an unknown child");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("standard hatch union declarations", failure);
        } finally {
            GregTechAPI.sBlockMachines = previousMachines;
            GregTechAPI.METATILEENTITIES[energyId] = previousEnergy;
            GregTechAPI.METATILEENTITIES[mufflerId] = previousMuffler;
        }
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
