// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HatchElement;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchMuffler;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.HatchElementBuilder;

/** Negative contracts at the common read boundary, independent of any installed controller family. */
final class HologramSemanticSafetyRegression {

    static void run() {
        standardHatchUnions();
        HologramLayeredHatchRegression.run();
        unknownFiltersAreNeverInvoked();
        metadataRemainsProvenance();
    }

    static void runPreset() {
        int energyId = 32750, mufflerId = 32751, otherEnergyId = 32749;
        Block previousMachines = GregTechAPI.sBlockMachines;
        IMetaTileEntity previousEnergy = GregTechAPI.METATILEENTITIES[energyId],
            previousMuffler = GregTechAPI.METATILEENTITIES[mufflerId],
            previousOtherEnergy = GregTechAPI.METATILEENTITIES[otherEnergyId];
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            Block machines = new Block(net.minecraft.block.material.Material.iron) {};
            Block.blockRegistry.addObject(4056, "gtmb:preset_fixture_machines", machines);
            Item.itemRegistry
                .addObject(4056, "gtmb:preset_fixture_machines", new gregtech.common.blocks.ItemMachines(machines));
            GregTechAPI.sBlockMachines = machines;
            IMetaTileEntity energy = (IMetaTileEntity) unsafe.allocateInstance(MTEHatchEnergy.class);
            GregTechAPI.METATILEENTITIES[energyId] = energy;
            GregTechAPI.METATILEENTITIES[mufflerId] = (IMetaTileEntity) unsafe.allocateInstance(MTEHatchMuffler.class);
            GregTechAPI.METATILEENTITIES[otherEnergyId] = (IMetaTileEntity) unsafe
                .allocateInstance(MTEHatchEnergy.class);
            ItemStack energyStack = new ItemStack(machines, 1, energyId),
                mufflerStack = new ItemStack(machines, 1, mufflerId);
            IHatchElement<?> combined = HatchElement.Energy.or(HatchElement.ExoticEnergy);
            Map<IHatchElement<?>, Integer> overlapping = new LinkedHashMap<>();
            overlapping.put(HatchElement.Energy, 1);
            overlapping.put(combined, 1);
            verifySparsePreset(
                presetSession(unsafe, overlapping, energyStack).capture.cells.get(0).element,
                energyStack);
            verifyTurbineOrGroups(unsafe);
            for (int path = 0; path < 3; path++) {
                HologramSession session = presetSession(unsafe, overlapping, energyStack);
                if (path == 1) session.pins.put(0, energyStack.copy());
                if (path == 2) {
                    BaseMetaTileEntity installed = (BaseMetaTileEntity) unsafe
                        .allocateInstance(BaseMetaTileEntity.class);
                    installed.setMetaTileID((short) energyId);
                    installed.setMetaTileEntity(energy);
                    ((HologramServiceEditRegression.ReadOnlyWorld) session.player.worldObj).occupant = installed;
                }
                HologramResourcePlanner.buildPreset(session);
                SimpleAssert.that(
                    session.presetSupported,
                    "one legal energy hatch satisfies both declared groups on path " + path);
                SimpleAssert.eq(
                    path == 0 ? 1 : 0,
                    session.autoTargets.size(),
                    "existing/manual/automatic paths count the same contributions");
                SimpleAssert.eq(
                    1,
                    session.player.inventory.mainInventory[1].stackSize,
                    "preset planning never debits inventory");
            }
            IHatchElement<MTEMultiBlockBase> repeated = HatchElement.Energy.or(HatchElement.Energy);
            SimpleAssert
                .eq(2, HologramHatchPolicy.contribution(repeated, energy), "native repeated Either leaves add twice");
            SimpleAssert.eq(
                3,
                HologramHatchPolicy.contribution(repeated.or(HatchElement.Energy), energy),
                "nested Either retains leaf multiplicity");
            for (int path = 0; path < 3; path++) {
                HologramSession session = presetSession(unsafe, Collections.singletonMap(repeated, 2), energyStack);
                if (path == 1) session.pins.put(0, energyStack.copy());
                if (path == 2) {
                    BaseMetaTileEntity installed = (BaseMetaTileEntity) unsafe
                        .allocateInstance(BaseMetaTileEntity.class);
                    installed.setMetaTileID((short) energyId);
                    installed.setMetaTileEntity(energy);
                    ((HologramServiceEditRegression.ReadOnlyWorld) session.player.worldObj).occupant = installed;
                }
                HologramResourcePlanner.buildPreset(session);
                SimpleAssert.that(session.presetSupported, "duplicate leaves satisfy native quota on path " + path);
                SimpleAssert.eq(
                    path == 0 ? 1 : 0,
                    session.autoTargets.size(),
                    "duplicate contributions consume one physical hatch");
            }
            overlapping.put(HatchElement.Energy, 2);
            HologramSession two = presetSession(unsafe, overlapping, energyStack);
            two.player.inventory.mainInventory[1].stackSize = 2;
            HologramResourcePlanner.buildPreset(two);
            SimpleAssert.that(
                two.presetSupported && two.autoTargets.size() == 2,
                "overlapping group satisfaction reserves each of two physical items only once");
            HologramSession shortStock = presetSession(unsafe, overlapping, energyStack);
            HologramResourcePlanner.buildPreset(shortStock);
            SimpleAssert.that(
                shortStock.presetSupported && shortStock.autoTargets.size() == 2,
                "short stock retains two separate required targets for the material quote");
            SimpleAssert.eq(
                1,
                HologramMaterialAccess.materialSource(shortStock)
                    .count(energyStack, 10),
                "one owned hatch leaves a real shortage against the two planned physical targets");
            overlapping.put(combined, 2);
            HologramSession reservations = presetSession(
                unsafe,
                overlapping,
                energyStack,
                new ItemStack(machines, 1, otherEnergyId));
            reservations.player.inventory.mainInventory[1].stackSize = 2;
            HologramResourcePlanner.buildPreset(reservations);
            SimpleAssert.that(
                reservations.presetSupported && reservations.autoTargets.size() == 2,
                "both overlapping quotas share the same two physical allocations");
            for (ItemStack target : reservations.autoTargets.values()) SimpleAssert.eq(
                energyId,
                target.getItemDamage(),
                "one reservation per physical item leaves the second preferred hatch stocked");
            Map<IHatchElement<?>, Integer> independent = new LinkedHashMap<>();
            independent.put(HatchElement.Energy, 1);
            independent.put(HatchElement.Muffler, 1);
            HologramSession separate = presetSession(unsafe, independent, energyStack, mufflerStack);
            HologramResourcePlanner.buildPreset(separate);
            SimpleAssert.that(
                separate.presetSupported && separate.autoTargets.size() == 2,
                "independent requirements still allocate distinct physical hatches");
            boundedPreset(unsafe, energy, energyId, energyStack, mufflerStack);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("native hatch quota accounting", failure);
        } finally {
            GregTechAPI.sBlockMachines = previousMachines;
            GregTechAPI.METATILEENTITIES[energyId] = previousEnergy;
            GregTechAPI.METATILEENTITIES[mufflerId] = previousMuffler;
            GregTechAPI.METATILEENTITIES[otherEnergyId] = previousOtherEnergy;
        }
    }

    private static void boundedPreset(sun.misc.Unsafe unsafe, IMetaTileEntity energy, int energyId,
        ItemStack energyStack, ItemStack mufflerStack) throws ReflectiveOperationException {
        Map<IHatchElement<?>, Integer> requirements = new LinkedHashMap<>();
        requirements.put(HatchElement.Energy, 2);
        requirements.put(HatchElement.Energy.or(HatchElement.ExoticEnergy), 2);
        requirements.put(HatchElement.Muffler, 1);
        for (int path = 0; path < 3; path++) {
            HologramSession s = presetSession(unsafe, requirements, energyStack, mufflerStack);
            HologramCapture.Cell example = s.capture.cells.get(0);
            for (int x = s.capture.cells.size(); x < 1024; x++) {
                HologramCapture.Cell cell = new HologramCapture.Cell(example.element, x, 64, 0, example.elementTrigger);
                cell.block = Blocks.stone;
                cell.candidates = example.candidates;
                s.capture.cells.add(cell);
            }
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) s.player.worldObj;
            if (path == 1) s.pins.put(0, energyStack.copy());
            if (path == 2) {
                BaseMetaTileEntity installed = (BaseMetaTileEntity) unsafe.allocateInstance(BaseMetaTileEntity.class);
                installed.setMetaTileID((short) energyId);
                installed.setMetaTileEntity(energy);
                world.occupant = installed;
            }
            HologramResourcePlanner.PresetTask task = new HologramResourcePlanner.PresetTask(s);
            java.util.Set<String> phases = new java.util.HashSet<>();
            int operations = 0;
            while (!task.done() && operations++ < 20000) {
                phases.add(task.phase());
                long before = world.tileReads;
                task.advance();
                SimpleAssert.that(
                    world.tileReads - before <= 1,
                    "one preset operation observes at most one position, including sort and allocation");
            }
            SimpleAssert.that(task.done() && operations > 1024, "large native quotas finish over bounded operations");
            SimpleAssert.that(
                phases.contains("preset.positions") && phases.contains("preset.allocate"),
                "position counts and quota choices both expose continuation phases");
            SimpleAssert.that(s.presetSupported, "known overlapping quotas are completely satisfied on path " + path);
            SimpleAssert.eq(
                path == 0 ? 3 : 2,
                s.autoTargets.size(),
                "fixed physical target count matches the original native quota semantics");
            int first = path == 0 ? 0 : 1;
            SimpleAssert.eq(
                energyStack.getItemDamage(),
                s.autoTargets.get(first)
                    .getItemDamage(),
                "stable native type and position order retain the first energy allocation");
            if (path == 0) SimpleAssert.eq(
                energyStack.getItemDamage(),
                s.autoTargets.get(1)
                    .getItemDamage(),
                "overlapping groups still use one shared second physical energy hatch");
            SimpleAssert.eq(
                mufflerStack.getItemDamage(),
                s.autoTargets.get(2)
                    .getItemDamage(),
                "independent muffler quota retains the next free physical position");
            SimpleAssert.eq(path == 1 ? 1 : 0, s.pins.size(), "budgeting preserves explicit target ownership");
            SimpleAssert.eq(1, s.player.inventory.mainInventory[1].stackSize, "planning never spends hatch stock");
        }
    }

    static HologramSession presetSession(sun.misc.Unsafe unsafe, Map<IHatchElement<?>, Integer> requirements,
        ItemStack... stock) throws ReflectiveOperationException {
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        UUID identity = UUID.randomUUID();
        for (Field member : Entity.class.getDeclaredFields()) if (member.getType() == UUID.class) {
            member.setAccessible(true);
            member.set(player, identity);
        }
        for (Field member : EntityPlayer.class.getDeclaredFields()) if (member.getType() == GameProfile.class) {
            member.setAccessible(true);
            member.set(player, new GameProfile(identity, "HatchQuota"));
        }
        player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        player.inventory = new InventoryPlayer(player);
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        for (int index = 0; index < stock.length; index++)
            player.inventory.mainInventory[index + 1] = stock[index].copy();
        player.capabilities = new PlayerCapabilities();
        HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
        session.materialSettings.containers = session.materialSettings.me = false;
        session.presetEnabled = true;
        session.hatchTier = 0;
        session.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        HatchElementBuilder<Object> builder = HatchElementBuilder.<Object>builder()
            .adder((context, tile, texture) -> { throw new AssertionError("preset ran hatch adder"); })
            .hint(1)
            .casingIndex(1);
        HologramHatchPolicy.declare(builder, requirements.keySet(), true);
        HologramHatchPolicy.requirements(builder, requirements);
        IStructureElement<?> element = builder.build();
        HologramHatchPolicy.built(builder, element);
        for (int x = 0; x < 3; x++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(element, x, 64, 0, new ItemStack(Blocks.stone));
            cell.block = Blocks.stone;
            cell.candidates = Arrays.asList(stock);
            session.capture.cells.add(cell);
        }
        return session;
    }

    private static void verifySparsePreset(IStructureElement<?> declaration, ItemStack hatch)
        throws java.io.IOException {
        HologramNativePresetIndex index = new HologramNativePresetIndex();
        index.record(declaration, 72, null, true, false);
        index.record(declaration, 412, null, true, false);
        var settings = new net.minecraft.nbt.NBTTagCompound();
        settings.setInteger("hatchTier", HologramCellPolicy.hatchTier(hatch));
        int[] lookups = { 0 };
        var prepared = index.prepare(settings, Collections.emptyMap(), position -> {
            lookups[0]++;
            return Collections.singletonList(hatch);
        });
        SimpleAssert.eq(2, lookups[0], "an element identity alone does not prove sparse position candidates identical");
        SimpleAssert.that(
            Arrays.equals(new int[] { hatch.getItemDamage() }, prepared.stockIds()),
            "stock request contains the native model only");
        var placed = prepared.resolve(new int[] { 1 }, false);
        SimpleAssert.that(
            placed.complete() && placed.choices()
                .size() == 1
                && placed.choices()
                    .get(0)
                    .index() == 72
                && placed.choices()
                    .get(0)
                    .facing() == -1,
            "one legal hatch satisfies overlapping native quotas");
        SimpleAssert.that(
            !prepared.resolve(new int[] { 0 }, false)
                .complete(),
            "insufficient stock never creates a partial preset");
        SimpleAssert.that(
            prepared.resolve(null, true)
                .complete(),
            "creative bypasses stock");
        var already = index.prepare(
            settings,
            Collections.singletonMap(72, hatch.getItemDamage()),
            position -> { throw new AssertionError("satisfied quotas must not query candidates"); });
        SimpleAssert.that(
            already.stockIds().length == 0 && already.resolve(new int[0], false)
                .complete()
                && already.resolve(new int[0], false)
                    .choices()
                    .isEmpty(),
            "manual choices remain intact");
        ItemStack tagged = hatch.copy();
        var extras = new net.minecraft.nbt.NBTTagCompound();
        extras.setString("variant", "exact");
        tagged.setTagCompound(extras);
        Map<Integer, ItemStack> exact = new LinkedHashMap<>();
        exact.put(72, tagged);
        exact.put(412, hatch);
        var saved = index.captureProfile(
            exact,
            settings,
            slot -> { throw new AssertionError("no installed slot requires work while saving"); });
        var savedRows = saved.getTagList("entries", 10);
        SimpleAssert.eq(2, savedRows.tagCount(), "exact profile saving does not collapse tagged and bare models");
        extras.setString("variant", "source changed");
        SimpleAssert.eq(
            "exact",
            savedRows.getCompoundTagAt(0)
                .getCompoundTag("tag")
                .getString("variant"),
            "profile retains its own exact NBT");
        index.record(declaration, 900, null, false, true);
        var unknown = index.prepare(
            settings,
            Collections.emptyMap(),
            position -> { throw new AssertionError("unread native site did not fail closed"); });
        SimpleAssert.that(
            !unknown.resolve(new int[0], false)
                .complete(),
            "unknown native positions block estimates");
    }

    private static void verifyTurbineOrGroups(sun.misc.Unsafe unsafe) throws InstantiationException {
        SimpleAssert.eq(
            Arrays.asList("Input", "Output", "Dynamo"),
            HologramGtsrBasicHatchPolicy.GROUPS,
            "turbine formation requires three logical groups, not every atLeast leaf");
        for (String name : Arrays.asList("InputHatch", "SteamInputBus", "PressureSteamInput", "OverpressureInput"))
            SimpleAssert.eq(0, HologramGtsrBasicHatchPolicy.group(name), "native turbine input alternative: " + name);
        for (String name : Arrays.asList("OutputHatch", "CoolingHatch"))
            SimpleAssert.eq(1, HologramGtsrBasicHatchPolicy.group(name), "native turbine output alternative: " + name);
        SimpleAssert.eq(2, HologramGtsrBasicHatchPolicy.group("DynamoOutput"), "native turbine dynamo role");
        SimpleAssert.eq(-1, HologramGtsrBasicHatchPolicy.group("Energy"), "unrelated role is not inferred");
        Map<String, IHatchElement<?>> bare = new LinkedHashMap<>();
        bare.put("InputHatch", HatchElement.InputHatch);
        bare.put("OutputHatch", HatchElement.OutputHatch);
        bare.put("DynamoOutput", HatchElement.Dynamo);
        SimpleAssert.that(
            !HologramGtsrBasicHatchPolicy.knownU(bare)
                && !HologramGtsrBasicHatchPolicy.capturedController(new Object()),
            "stock role names alone cannot activate the pinned turbine adapter");
        var input = (IMetaTileEntity) unsafe
            .allocateInstance(gregtech.api.metatileentity.implementations.MTEHatchInput.class);
        var output = (IMetaTileEntity) unsafe
            .allocateInstance(gregtech.api.metatileentity.implementations.MTEHatchOutput.class);
        var dynamo = (IMetaTileEntity) unsafe
            .allocateInstance(gregtech.api.metatileentity.implementations.MTEHatchDynamo.class);
        var energy = (IMetaTileEntity) unsafe.allocateInstance(MTEHatchEnergy.class);
        SimpleAssert.that(
            Arrays.equals(
                new boolean[] { true, false, false },
                HologramGtsrBasicHatchPolicy.provedGroups(bare, input, true))
                && Arrays.equals(
                    new boolean[] { false, true, false },
                    HologramGtsrBasicHatchPolicy.provedGroups(bare, output, true))
                && Arrays.equals(
                    new boolean[] { false, false, true },
                    HologramGtsrBasicHatchPolicy.provedGroups(bare, dynamo, true))
                && Arrays.equals(
                    new boolean[] { false, false, false },
                    HologramGtsrBasicHatchPolicy.provedGroups(bare, energy, true)),
            "GT input, output and dynamo prototypes keep distinct roles; energy input is not a dynamo");
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
            SimpleAssert.eq(
                0,
                HologramHatchPolicy
                    .contribution(HatchElement.Energy.or(unknown), GregTechAPI.METATILEENTITIES[energyId]),
                "unknown count trees grant no automatic quota contribution");
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
        HologramHatchPolicy.requirements(builder, Collections.singletonMap(HatchElement.Muffler, 1));
        HologramHatchPolicy.andFunction(builder, context -> {
            callbacks.incrementAndGet();
            return (java.util.function.Predicate<ItemStack>) stack -> true;
        });
        HologramHatchPolicy.built(builder, element);
        SimpleAssert.that(
            HologramHatchPolicy.predicate(element, null, null) == null,
            "unknown Function stays unavailable for automatic candidate planning");
        SimpleAssert.that(
            HologramHatchPolicy.structuralPredicate(element, null, null) != null
                && HologramHatchPolicy.declared(element)
                    .contains(HatchElement.Muffler)
                && HologramHatchPolicy.requirements(element)
                    .get(HatchElement.Muffler) == 1,
            "an additive funding filter cannot erase the same native adder and its declared role");
        HologramHatchPolicy.declare(builder, Collections.singletonList(HatchElement.Muffler), true);
        HologramHatchPolicy.andBiFunction(builder, (context, trigger) -> {
            callbacks.incrementAndGet();
            return (java.util.function.Predicate<ItemStack>) stack -> true;
        });
        HologramHatchPolicy.built(builder, element);
        SimpleAssert.that(
            HologramHatchPolicy.predicate(element, null, null) == null,
            "unknown BiFunction stays unavailable for automatic candidate planning");
        SimpleAssert.that(
            HologramHatchPolicy.structuralPredicate(element, null, null) != null,
            "the trigger-aware filter also leaves explicit native fitting rules intact");
        HologramHatchPolicy.reset(builder);
        HologramHatchPolicy.built(builder, element);
        SimpleAssert.that(
            HologramHatchPolicy.structuralPredicate(element, null, null) == null,
            "replacing the native adder invalidates structural proof");
        SimpleAssert.that(callbacks.get() == 0, "display and planning never evaluate unknown filters");
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
