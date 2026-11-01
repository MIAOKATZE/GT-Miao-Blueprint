package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Real Source API and production capture traversal, with native callbacks and world writes guarded by tripwires. */
public final class HologramSourceContractRegression {

    private HologramSourceContractRegression() {}

    public static void main(String[] args) throws Exception {
        if (MinecraftTestBootstrap.enter(HologramSourceContractRegression.class, args)) run();
    }

    static void run() {
        String previous = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try {
            legacyAndExplicitResults();
            frozenDefinitionAndReadSet();
            registryLifecycle();
            continuationAdmission();
            observationLineage();
            unknownLeafDoesNotRun();
            explicitPolicyAndSavedAuthority();
            independentSourceBoundary();
        } catch (Exception failure) {
            throw new AssertionError("source capture contract", failure);
        } finally {
            Thread.currentThread()
                .setName(previous);
        }
    }

    private static void legacyAndExplicitResults() throws Exception {
        Fixture fixture = fixture();
        ItemStack trigger = new ItemStack(Blocks.stone, 3);
        SimpleAssert.that(StructureSources.resolve(fixture.controller, trigger) == null, "absent legacy source");
        SimpleAssert.eq(
            StructureSources.Status.ABSENT,
            StructureSources.resolve(fixture.controller, trigger, StructureSources.CaptureIntent.NEW_TEMPLATE).status,
            "no optional GTNL source preserves the ordinary native path");
        try (GTMBExtensions.Registration registration = StructureSources
            .register("gtmb_test:legacy-null-source", Controller.class, (controller, signal) -> null)) {
            SimpleAssert
                .that(StructureSources.resolve(fixture.controller, trigger) == null, "old Source null still delegates");
            HologramCapture capture = complete(
                fixture.controller,
                trigger,
                StructureSources.CaptureIntent.NEW_TEMPLATE);
            SimpleAssert.eq(1, fixture.controller.constructs, "legacy null invokes native construct once");
            SimpleAssert.eq(1, fixture.controller.definitions, "legacy null obtains native definition once");
            SimpleAssert.that(!capture.incomplete, "legacy native capture remains usable");
        }
        fixture.controller.forbidNative = true;
        StructureSources.Plan legacyPlan = plan(2, trigger, StructureUtility.ofBlock(Blocks.stone, 0));
        try (GTMBExtensions.Registration registration = StructureSources
            .register("gtmb_test:legacy-plan-source", Controller.class, (controller, signal) -> legacyPlan)) {
            SimpleAssert.that(
                StructureSources.resolve(fixture.controller, trigger) == legacyPlan,
                "old Plan-returning source retains its original contract");
            HologramCapture legacy = complete(fixture.controller, trigger, StructureSources.CaptureIntent.NEW_TEMPLATE);
            SimpleAssert.that(legacy.definition == legacyPlan.definition, "old plan enters the same capture path");
        }
        AtomicInteger descriptions = new AtomicInteger();
        try (GTMBExtensions.Registration registration = StructureSources
            .registerContextual("gtmb_test:explicit-source", Controller.class, (controller, signal, intent) -> {
                descriptions.incrementAndGet();
                signal.stackSize = 99;
                return StructureSources.Resolution.unsupported("FIXTURE_UNSUPPORTED");
            })) {
            HologramCapture rejected = HologramCapture.collect(
                fixture.controller,
                trigger,
                ExtendedFacing.DEFAULT,
                StructureSources.CaptureIntent.LIVE_SNAPSHOT);
            SimpleAssert.eq("FIXTURE_UNSUPPORTED", rejected.failure, "matched source rejection remains explicit");
            SimpleAssert.that(
                rejected.cells.isEmpty() && rejected.incomplete && !rejected.pending,
                "unsupported source never walks or falls back to native");
            SimpleAssert.eq(3, trigger.stackSize, "source receives a detached trigger");
            SimpleAssert.eq(1, descriptions.get(), "one source description per rejected capture");
            try {
                StructureSources.resolve(fixture.controller, trigger);
                throw new AssertionError("old resolver must not turn explicit rejection into null");
            } catch (StructureSources.UnsupportedSourceException expected) {
                SimpleAssert.eq("FIXTURE_UNSUPPORTED", expected.failureCode, "old resolver exposes stable rejection");
            }
        }
        try (GTMBExtensions.Registration registration = StructureSources.registerContextual(
            "gtmb_test:null-contextual-source",
            Controller.class,
            (controller, signal, intent) -> null)) {
            SimpleAssert.eq(
                "SOURCE_NO_DECLARATION",
                StructureSources
                    .resolve(fixture.controller, trigger, StructureSources.CaptureIntent.NEW_TEMPLATE).failureCode,
                "new Source null cannot request native fallback");
        }
        StructureSources.Plan plan = plan(2, trigger, StructureUtility.ofBlock(Blocks.stone, 0));
        NBTTagCompound owned = plan.metadata();
        owned.setBoolean("mutated", true);
        SimpleAssert.that(
            !plan.metadata()
                .hasKey("mutated"),
            "source metadata is detached on read");
        ItemStack pieceSignal = plan.pieces.get(0)
            .trigger();
        pieceSignal.stackSize = 91;
        SimpleAssert.eq(
            3,
            plan.pieces.get(0)
                .trigger().stackSize,
            "piece trigger is detached on read");
        fixture.unchanged();
    }

    private static void frozenDefinitionAndReadSet() throws Exception {
        Fixture fixture = fixture();
        fixture.controller.forbidNative = true;
        ItemStack signal = new ItemStack(Blocks.stone, 1);
        AtomicInteger descriptions = new AtomicInteger();
        try (GTMBExtensions.Registration registration = readySource("gtmb_test:state-source", descriptions, 6200)) {
            HologramCapture first = HologramCapture.collect(
                fixture.controller,
                signal,
                ExtendedFacing.DEFAULT,
                StructureSources.CaptureIntent.NEW_TEMPLATE);
            SimpleAssert.that(first.pending && !first.incomplete, "large source capture genuinely yields a batch");
            IStructureDefinition<?> definition = first.definition;
            HologramCapture completed = finish(
                fixture.controller,
                signal,
                StructureSources.CaptureIntent.NEW_TEMPLATE,
                first);
            SimpleAssert.that(completed.definition == definition, "continuation retains the exact definition graph");
            SimpleAssert.eq(1, descriptions.get(), "continuation does not describe again");
            SimpleAssert.eq(6201, completed.cells.size(), "all declaration cells and anchor survive batching");
            SimpleAssert.eq("NEW_TEMPLATE", completed.sourceMetadata.getString("purpose"), "template intent captured");
            HologramCapture live = complete(fixture.controller, signal, StructureSources.CaptureIntent.LIVE_SNAPSHOT);
            SimpleAssert
                .eq("LIVE_SNAPSHOT", live.sourceMetadata.getString("purpose"), "live intent captured separately");
            SimpleAssert.that(live.definition != completed.definition, "new capture obtains a fresh definition graph");
            SimpleAssert.eq(2, descriptions.get(), "new intent starts one fresh description");

            HologramCapture stale = HologramCapture.collect(
                fixture.controller,
                signal,
                ExtendedFacing.DEFAULT,
                StructureSources.CaptureIntent.NEW_TEMPLATE);
            int cellsBefore = stale.cells.size();
            fixture.controller.state++;
            HologramCapture resumed = HologramCapture.collect(
                fixture.controller,
                signal,
                ExtendedFacing.DEFAULT,
                StructureSources.CaptureIntent.NEW_TEMPLATE,
                stale);
            SimpleAssert.eq("FIXTURE_STATE_CHANGED", resumed.failure, "read-set rejects changed state");
            SimpleAssert.eq(cellsBefore, resumed.cells.size(), "expired continuation does not visit another cell");
            SimpleAssert.that(!resumed.pending && resumed.incomplete, "expired work cannot continue");
            SimpleAssert.eq(3, descriptions.get(), "expiry does not silently recreate the definition");
            SimpleAssert.eq(0, fixture.controller.constructs, "source capture never calls native construct");
            SimpleAssert.eq(0, fixture.controller.definitions, "source capture never calls native definition");
            fixture.controller.state = 0;
        }
        fixture.unchanged();
    }

    private static void registryLifecycle() throws Exception {
        Fixture fixture = fixture();
        fixture.controller.forbidNative = true;
        ItemStack signal = new ItemStack(Blocks.stone);
        AtomicInteger descriptions = new AtomicInteger();
        GTMBExtensions.Registration first = readySource("gtmb_test:revision-source", descriptions, 6200);
        try {
            HologramCapture capture = HologramCapture.collect(fixture.controller, signal, ExtendedFacing.DEFAULT);
            long revision = capture.sourceResolution.sourceRevision;
            int cellsBefore = capture.cells.size();
            first.close();
            try (GTMBExtensions.Registration replacement = readySource(
                "gtmb_test:revision-source",
                descriptions,
                6200)) {
                SimpleAssert.that(StructureSources.revision() > revision, "close and re-register advance revision");
                HologramCapture result = HologramCapture
                    .collect(fixture.controller, signal, ExtendedFacing.DEFAULT, capture);
                SimpleAssert
                    .eq("SOURCE_REVISION_CHANGED", result.failure, "old registration cannot survive replacement");
                SimpleAssert.eq(cellsBefore, result.cells.size(), "old graph never mixes with replacement graph");
                SimpleAssert.eq(1, descriptions.get(), "replacement is not described by stale continuation");
                HologramCapture current = complete(
                    fixture.controller,
                    signal,
                    StructureSources.CaptureIntent.NEW_TEMPLATE);
                SimpleAssert.that(current.sourceCurrent(), "fresh registration captures normally");
            }
        } finally {
            first.close();
        }
        SimpleAssert.that(!StructureSources.hasSource(Controller.class), "source closes exactly its own registration");
        fixture.unchanged();
    }

    private static void continuationAdmission() throws Exception {
        Fixture fixture = fixture();
        fixture.controller.forbidNative = true;
        ItemStack signal = new ItemStack(Blocks.stone);
        try (GTMBExtensions.Registration registration = readySource(
            "gtmb_test:admission-source",
            new AtomicInteger(),
            6200)) {
            HologramCapture triggerCapture = HologramCapture
                .collect(fixture.controller, signal, ExtendedFacing.DEFAULT);
            ItemStack changed = signal.copy();
            changed.stackSize = 2;
            HologramCapture.collect(fixture.controller, changed, ExtendedFacing.DEFAULT, triggerCapture);
            SimpleAssert.eq("SOURCE_TRIGGER_CHANGED", triggerCapture.failure, "changed trigger rejects continuation");

            HologramCapture intentCapture = HologramCapture.collect(fixture.controller, signal, ExtendedFacing.DEFAULT);
            HologramCapture.collect(
                fixture.controller,
                signal,
                ExtendedFacing.DEFAULT,
                StructureSources.CaptureIntent.LIVE_SNAPSHOT,
                intentCapture);
            SimpleAssert.eq("SOURCE_INTENT_CHANGED", intentCapture.failure, "changed intent rejects continuation");

            HologramCapture unloaded = complete(
                fixture.controller,
                signal,
                StructureSources.CaptureIntent.NEW_TEMPLATE);
            fixture.world.unloaded = true;
            SimpleAssert.that(!unloaded.sourceCurrent(), "completed capture expires when source chunk unloads");
            SimpleAssert.eq("SOURCE_CHUNK_UNLOADED", unloaded.failure, "unload has actionable stable reason");
            fixture.world.unloaded = false;

            HologramCapture replaced = complete(
                fixture.controller,
                signal,
                StructureSources.CaptureIntent.NEW_TEMPLATE);
            fixture.world.occupant = null;
            SimpleAssert.that(!replaced.sourceCurrent(), "completed capture expires when source tile is replaced");
            SimpleAssert.eq("SOURCE_CONTROLLER_CHANGED", replaced.failure, "replacement has stable reason");
            fixture.world.occupant = fixture.tile;

            HologramCapture declarations = HologramCapture.collect(fixture.controller, signal, ExtendedFacing.DEFAULT);
            try (AutoCloseable reader = HologramDeclarationAdapters.registerScopedReader(
                UnknownLeaf.class,
                leaf -> new HologramDeclarationAdapters.Declaration(StructureUtility.ofBlock(Blocks.stone, 0)))) {
                SimpleAssert
                    .that(!declarations.sourceCurrent(), "reader revision also invalidates captured coordinates");
                SimpleAssert.eq("SOURCE_DECLARATIONS_CHANGED", declarations.failure, "reader change requests rescan");
            }
        }
        fixture.unchanged();
    }

    private static void unknownLeafDoesNotRun() throws Exception {
        Fixture fixture = fixture();
        fixture.controller.forbidNative = true;
        UnknownLeaf leaf = new UnknownLeaf();
        ItemStack signal = new ItemStack(Blocks.stone);
        try (GTMBExtensions.Registration registration = StructureSources.registerContextual(
            "gtmb_test:unknown-leaf-source",
            Controller.class,
            (controller, trigger, intent) -> StructureSources.Resolution.ready(plan(2, trigger, leaf), () -> null))) {
            HologramCapture capture = complete(fixture.controller, signal, StructureSources.CaptureIntent.NEW_TEMPLATE);
            SimpleAssert.eq(0, leaf.calls, "unknown leaf is never asked to hint/check/place");
            SimpleAssert.that(
                capture.cells.stream()
                    .anyMatch(cell -> !cell.anchor && cell.unknown),
                "unknown leaf remains explicitly unknown");
            Method hint = HologramCapture.class.getDeclaredMethod(
                "spawnHint",
                IStructureElement.class,
                World.class,
                int.class,
                int.class,
                int.class,
                ItemStack.class,
                int.class);
            hint.setAccessible(true);
            SimpleAssert.eq(
                false,
                hint.invoke(capture, leaf, fixture.world, 1, 100, 0, signal, 0),
                "direct hint helper also rejects unknown leaf");
            SimpleAssert.eq(
                "SOURCE_DECLARATION_UNVERIFIED",
                HologramSourcePolicy.constructionFailure(session(fixture, capture)),
                "an unknown hint cannot gain whole-plan authority");
        }
        fixture.unchanged();
    }

    @SuppressWarnings("unchecked")
    private static void explicitPolicyAndSavedAuthority() throws Exception {
        Fixture fixture = fixture();
        fixture.controller.forbidNative = true;
        ItemStack signal = new ItemStack(Blocks.stone);
        AtomicInteger validations = new AtomicInteger();
        StructureSources.Policy policy = plan -> {
            validations.incrementAndGet();
            if (!plan.fresh) return "FIXTURE_FRESH_SOURCE_REQUIRED";
            if (plan.metadata()
                .getInteger("fixtureSchema") != 7) return "FIXTURE_SCHEMA_REJECTED";
            if (plan.saved && plan.savedMetadata()
                .getInteger("fixtureSchema") != 7) return "FIXTURE_SAVED_SCHEMA_REJECTED";
            NBTTagCompound detached = plan.metadata();
            detached.setInteger("fixtureSchema", 99);
            SimpleAssert.eq(
                7,
                plan.metadata()
                    .getInteger("fixtureSchema"),
                "policy metadata is detached on read");
            int stone = 0;
            for (StructureSources.ValidationCell cell : plan.cells) {
                if (cell.anchor || cell.canonicalAir || cell.templateController) continue;
                ItemStack target = cell.target();
                if (HologramValues.sameMaterial(target, signal)) stone++;
                if (target != null) {
                    target.stackSize = 99;
                    SimpleAssert.eq(1, cell.target().stackSize, "policy cannot mutate the planned material");
                }
            }
            return stone == 2 ? null : "FIXTURE_TWO_STONE_REQUIRED";
        };
        GTMBExtensions.Registration registration = StructureSources
            .registerContextual("gtmb_test:readonly-policy", Controller.class, (controller, trigger, intent) -> {
                int state = controller.state;
                IStructureElement<Controller> leaf = (IStructureElement<Controller>) (IStructureElement<?>) new HologramPureElementsRegression.Leaf(
                    true);
                StructureSources.Plan geometry = plan(2, trigger, leaf);
                NBTTagCompound metadata = geometry.metadata();
                metadata.setInteger("fixtureSchema", 7);
                return StructureSources.Resolution.ready(
                    new StructureSources.Plan(geometry.definition, geometry.pieces, metadata),
                    () -> controller.state == state ? null : "FIXTURE_STATE_CHANGED");
            }, policy);
        int controllerId = 31968;
        IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[controllerId];
        try {
            GregTechAPI.METATILEENTITIES[controllerId] = fixture.controller;
            fixture.tile.setMetaTileID((short) controllerId);
            HologramCapture capture = complete(fixture.controller, signal, StructureSources.CaptureIntent.NEW_TEMPLATE);
            HologramSession session = session(fixture, capture);
            SimpleAssert.that(capture.hasSourcePolicy(), "capture retains a scoped policy binding");
            SimpleAssert
                .that(HologramSourcePolicy.requiresFence(session), "registered policies share the revision fence");
            SimpleAssert.that(
                HologramSourcePolicy.constructionFailure(session) == null,
                "a community-defined controller and pure element reach the production whole-plan gate");
            int first = 0;
            while (capture.cells.get(first).anchor) first++;
            session.pins.put(first, new ItemStack(Blocks.dirt));
            SimpleAssert.eq(
                "FIXTURE_TWO_STONE_REQUIRED",
                HologramSourcePolicy.constructionFailure(session),
                "an individually legal choice still passes the registered whole-plan rule");
            session.pins.put(first, new ItemStack(Blocks.cobblestone));
            SimpleAssert.eq(
                "SOURCE_TARGET_REJECTED",
                HologramSourcePolicy.constructionFailure(session),
                "an unrelated material is rejected by the current pure declaration");
            session.pins.clear();
            capture.sourceMetadata.setInteger("fixtureSchema", 99);
            SimpleAssert.that(
                HologramSourcePolicy.constructionFailure(session) == null,
                "mutable receipt metadata cannot replace the immutable fresh source facts");
            HologramCapture.Cell member = capture.cells.get(first);
            SimpleAssert.that(
                HologramVirtualTransform.at(member, member.x + 1, member.y, member.z).sourceAuthority
                    == member.sourceAuthority,
                "runtime transforms retain the same graph-free source qualification");

            HologramPresets.Preset saved = new HologramPresets.Preset();
            saved.schema = 2;
            saved.id = "source_policy_fixture";
            saved.name = "Source policy fixture";
            saved.sourceClass = Controller.class.getName();
            saved.controllerId = controllerId;
            saved.semanticNbt = HologramBlueprintNbt.encodeCanonical(member.sourceAuthority.metadata());
            for (HologramCapture.Cell cell : capture.cells) if (!cell.anchor) saved.cells.add(
                HologramPresets.cell(signal, cell.x - session.x, cell.y - session.y, cell.z - session.z, -1, false));
            session.virtual = session.virtualPlacement = session.virtualLocked = true;
            session.virtualPreset = saved;
            HologramBlueprintProjection.Population population = new HologramBlueprintProjection.Population(session);
            SimpleAssert
                .that(population.step(Integer.MAX_VALUE, Long.MAX_VALUE), "the real saved-blueprint path loads");
            SimpleAssert.eq(
                "FIXTURE_FRESH_SOURCE_REQUIRED",
                HologramSourcePolicy.constructionFailure(session),
                "saved metadata and restored material pins cannot recreate runtime source authority");
            session.capture = capture;
            SimpleAssert.that(
                HologramSourcePolicy.constructionFailure(session) == null,
                "fresh elements and current binding requalify the same saved physical choices");
            rereadPreservesPhysicalTargets(fixture, capture, signal, controllerId);
            registration.close();
            SimpleAssert.that(!capture.sourceCurrent(), "closing the source retires its captured qualification");
            SimpleAssert.eq("SOURCE_REVISION_CHANGED", capture.failure, "source retirement requires a fresh capture");
            SimpleAssert.that(validations.get() >= 5, "the custom policy was exercised through real consumers");
            SimpleAssert.eq(0, fixture.controller.constructs, "policy capture never invokes native construct");
            SimpleAssert.eq(0, fixture.controller.definitions, "policy capture never invokes native definition");
        } finally {
            registration.close();
            GregTechAPI.METATILEENTITIES[controllerId] = previous;
        }
        fixture.unchanged();
    }

    private static void rereadPreservesPhysicalTargets(Fixture fixture, HologramCapture captured, ItemStack signal,
        int controllerId) throws Exception {
        HologramSession session = session(fixture, captured);
        session.virtual = session.virtualPlacement = session.virtualLocked = true;
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.schema = 2;
        session.virtualPreset.id = "source_reread_fixture";
        HologramPresets.Structure source = new HologramPresets.Structure();
        source.id = "member";
        source.sourceClass = Controller.class.getName();
        source.controllerId = controllerId;
        source.main = 1;
        source.mainExplicit = true;
        source.semanticNbt = HologramBlueprintNbt.encodeCanonical(captured.cells.get(0).sourceAuthority.metadata());
        session.virtualPreset.structures.add(source);
        session.blueprintActiveStructure = source.id;
        HologramCapture original = new HologramCapture(null, signal);
        original.inheritSources(captured);
        int body = 0;
        for (HologramCapture.Cell old : captured.cells) {
            HologramCapture.Cell cell = HologramBlueprintData.blueprintCell(old, old.x, old.y, old.z);
            cell.anchor = false;
            cell.userPosition = true;
            cell.role = old.anchor ? "templateController" : "prefab";
            cell.sourceClass = Controller.class.getName();
            HologramBlueprintData.setMemberships(cell, java.util.Collections.singletonList(source.id));
            ItemStack target = old.anchor ? signal.copy()
                : new ItemStack(body++ == 0 ? Blocks.dirt : Blocks.cobblestone);
            cell.block = net.minecraft.block.Block.getBlockFromItem(target.getItem());
            cell.blueprintBlock = String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(cell.block));
            cell.blueprintItem = String
                .valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(target.getItem()));
            cell.blueprintDamage = target.getItemDamage();
            session.pins.put(original.cells.size(), target.copy());
            original.cells.add(cell);
            original.positions.put(HologramValues.position(cell), cell);
            HologramPresets.Cell saved = HologramPresets.cell(
                target,
                cell.x - session.x,
                cell.y - session.y,
                cell.z - session.z,
                old.anchor ? ForgeDirection.NORTH.ordinal() : -1,
                old.anchor);
            saved.structureId = source.id;
            session.virtualPreset.cells.add(saved);
        }
        session.capture = original;
        HologramVirtualStructureEditing.Task task = new HologramVirtualStructureEditing.Task(
            session,
            source,
            1,
            new NBTTagCompound());
        int batches = 0;
        while (!task.step()) if (++batches > 64) throw new AssertionError("source reread did not finish");
        task.publish();
        for (int index = 0; index < session.capture.cells.size(); index++) {
            HologramCapture.Cell cell = session.capture.cells.get(index);
            if ("templateController".equals(cell.role)) continue;
            HologramCapture.Cell previous = original.positions.get(HologramValues.position(cell));
            SimpleAssert
                .that(cell.element != previous.element, "same-parameter reread obtains the new declaration leaf");
            SimpleAssert.eq(previous.block, cell.block, "same-parameter reread preserves the physical block choice");
            SimpleAssert.eq(
                previous.blueprintItem,
                cell.blueprintItem,
                "same-parameter reread preserves the exact selected item identity");
            SimpleAssert.that(
                cell.sourceAuthority != previous.sourceAuthority,
                "preserved physical choices receive the fresh runtime qualification");
        }
        SimpleAssert.eq(
            "SOURCE_TARGET_REJECTED",
            HologramSourcePolicy.constructionFailure(session),
            "an invalid preserved target stays visible and is rejected by the new declaration before payment");
        SimpleAssert.eq(0, task.nativeCapture.nativeConstructCalls, "registered reread never invokes native construct");
        SimpleAssert
            .eq(0, task.nativeCapture.nativeDefinitionCalls, "registered reread never invokes native definition");
    }

    private static void independentSourceBoundary() throws Exception {
        Fixture fixture = fixture();
        fixture.controller.forbidNative = true;
        ItemStack signal = new ItemStack(Blocks.stone);
        try (GTMBExtensions.Registration boundary = StructureSources.registerBoundary(
            "gtmb_test:independent-boundary",
            type -> Controller.class.getName()
                .equals(type) ? "FIXTURE_SOURCE_REQUIRED" : null)) {
            HologramCapture missing = HologramCapture.collect(fixture.controller, signal, ExtendedFacing.DEFAULT);
            SimpleAssert.eq("FIXTURE_SOURCE_REQUIRED", missing.failure, "a claimed family cannot fall back to native");
            HologramCapture qualified;
            try (GTMBExtensions.Registration source = StructureSources.registerContextual(
                "gtmb_test:boundary-source",
                Controller.class,
                (controller, trigger, intent) -> StructureSources.Resolution
                    .ready(plan(2, trigger, StructureUtility.ofBlock(Blocks.stone, 0)), () -> null),
                plan -> null)) {
                qualified = complete(fixture.controller, signal, StructureSources.CaptureIntent.NEW_TEMPLATE);
                SimpleAssert.that(qualified.sourceCurrent(), "an explicit policy satisfies the independent boundary");
            }
            SimpleAssert.that(!qualified.sourceCurrent(), "closing its source invalidates the captured graph");
            SimpleAssert.eq(
                "FIXTURE_SOURCE_REQUIRED",
                StructureSources
                    .resolve(fixture.controller, signal, StructureSources.CaptureIntent.NEW_TEMPLATE).failureCode,
                "the boundary survives source removal without acquiring a native fallback");
        }
        SimpleAssert.eq(0, fixture.controller.constructs, "boundary handling never probes native construct");
        fixture.unchanged();
    }

    private static HologramSession session(Fixture fixture, HologramCapture capture) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) ((sun.misc.Unsafe) field.get(null))
            .allocateInstance(EntityPlayerMP.class);
        UUID identity = UUID.fromString("d5d5f836-c283-4e5c-a144-edc4eb331246");
        for (Field candidate : EntityPlayer.class.getDeclaredFields()) if (candidate.getType() == GameProfile.class) {
            candidate.setAccessible(true);
            candidate.set(player, new GameProfile(identity, "SourcePolicyFixture"));
        }
        for (Field candidate : Entity.class.getDeclaredFields()) if (candidate.getType() == UUID.class) {
            candidate.setAccessible(true);
            candidate.set(player, identity);
        }
        player.worldObj = fixture.world;
        player.inventory = new InventoryPlayer(player);
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.isCreativeMode = true;
        player.capabilities.allowEdit = true;
        HologramSession session = new HologramSession(player, 0, 100, 0, false, 2);
        session.machine = fixture.controller;
        session.capture = capture;
        return session;
    }

    private static void observationLineage() throws Exception {
        Fixture fixture = fixture();
        fixture.controller.forbidNative = true;
        fixture.world.occupied = Blocks.dirt;
        ItemStack signal = new ItemStack(Blocks.stone);
        try (GTMBExtensions.Registration registration = readySource(
            "gtmb_test:lineage-source",
            new AtomicInteger(),
            2)) {
            HologramCapture source = complete(fixture.controller, signal, StructureSources.CaptureIntent.LIVE_SNAPSHOT);
            source.observe(() -> fixture.world.getBlock(0, 100, 0) == Blocks.dirt ? null : "FIXTURE_BLOCK_CHANGED");
            HologramCapture derived = new HologramCapture(null, signal, StructureSources.CaptureIntent.LIVE_SNAPSHOT);
            derived.inheritSources(source);
            derived.inheritSources(source);
            SimpleAssert.that(derived.sourceCurrent(), "derived capture carries valid source and world facts");
            SimpleAssert.that(
                derived.definition == null && derived.cells.isEmpty() && derived.context == null,
                "inheriting validity does not copy declaration graph or cells");
            fixture.world.occupied = Blocks.stone;
            SimpleAssert.that(!derived.sourceCurrent(), "derived capture expires with observed world block");
            SimpleAssert.eq("FIXTURE_BLOCK_CHANGED", derived.failure, "observation reason survives lineage");
        }
        fixture.unchanged();
    }

    private static GTMBExtensions.Registration readySource(String id, AtomicInteger descriptions, int cells) {
        return StructureSources.registerContextual(id, Controller.class, (controller, signal, intent) -> {
            descriptions.incrementAndGet();
            int state = controller.state;
            return StructureSources.Resolution.ready(
                plan(cells, signal, StructureUtility.ofBlock(Blocks.stone, 0)),
                () -> controller.state == state ? null : "FIXTURE_STATE_CHANGED");
        });
    }

    private static StructureSources.Plan plan(int count, ItemStack signal, IStructureElement<Controller> element) {
        String row = new String(new char[count]).replace('\0', 'A');
        IStructureDefinition<Controller> definition = StructureDefinition.<Controller>builder()
            .addShape("main", new String[][] { { row } })
            .addElement('A', element)
            .build();
        NBTTagCompound metadata = new NBTTagCompound();
        metadata.setString("family", "source-contract-fixture");
        return new StructureSources.Plan(
            definition,
            Arrays.asList(new StructureSources.Piece("main", -1, 0, 0, signal)),
            metadata);
    }

    private static HologramCapture complete(Controller controller, ItemStack signal,
        StructureSources.CaptureIntent intent) {
        return finish(controller, signal, intent, null);
    }

    private static HologramCapture finish(Controller controller, ItemStack signal,
        StructureSources.CaptureIntent intent, HologramCapture capture) {
        int batches = 0;
        do {
            capture = HologramCapture.collect(controller, signal, ExtendedFacing.DEFAULT, intent, capture);
            if (++batches > 100) throw new AssertionError("capture did not finish");
        } while (capture.pending);
        SimpleAssert.that(!capture.incomplete, "capture completed without fallback: " + capture.failure);
        return capture;
    }

    private static Fixture fixture() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        NativeTile tile = (NativeTile) unsafe.allocateInstance(NativeTile.class);
        tile.setWorldObj(world);
        tile.yCoord = 100;
        Controller controller = new Controller();
        controller.setBaseMetaTileEntity(tile);
        world.occupant = tile;
        return new Fixture(controller, tile, world);
    }

    private static final class Fixture {

        final Controller controller;
        final NativeTile tile;
        final HologramServiceEditRegression.ReadOnlyWorld world;

        Fixture(Controller controller, NativeTile tile, HologramServiceEditRegression.ReadOnlyWorld world) {
            this.controller = controller;
            this.tile = tile;
            this.world = world;
        }

        void unchanged() {
            SimpleAssert.eq(0, controller.state, "source inspection leaves controller state unchanged");
            SimpleAssert
                .that(world.occupant == tile && tile.yCoord == 100, "world/controller identity remains unchanged");
        }
    }

    private static final class NativeTile extends BaseMetaTileEntity {

        @Override
        public ForgeDirection getFrontFacing() {
            return ForgeDirection.NORTH;
        }
    }

    private static final class Controller extends MTEEnhancedMultiBlockBase<Controller> {

        int state, definitions, constructs;
        boolean forbidNative;

        Controller() {
            super("source_contract_fixture");
        }

        @Override
        public IStructureDefinition<Controller> getStructureDefinition() {
            if (forbidNative) throw new AssertionError("source capture invoked native definition");
            definitions++;
            return StructureDefinition.<Controller>builder()
                .addShape("main", new String[][] { { "AA" } })
                .addElement('A', StructureUtility.ofBlock(Blocks.stone, 0))
                .build();
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            if (forbidNative) throw new AssertionError("source capture invoked native construct");
            constructs++;
            HologramCapture.piece(this, "main", trigger, hints, -1, 0, 0);
        }

        @Override
        public ItemStack getStackForm(long count) {
            return new ItemStack(Blocks.stone, (int) count);
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            throw new AssertionError("source capture invoked tooltip");
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new Controller();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }

    private static final class UnknownLeaf implements IStructureElement<Controller> {

        int calls;

        @Override
        public boolean check(Controller controller, World world, int x, int y, int z) {
            calls++;
            throw new AssertionError("unknown check callback");
        }

        @Override
        public boolean spawnHint(Controller controller, World world, int x, int y, int z, ItemStack trigger) {
            calls++;
            throw new AssertionError("unknown hint callback");
        }

        @Override
        public boolean placeBlock(Controller controller, World world, int x, int y, int z, ItemStack trigger) {
            calls++;
            throw new AssertionError("unknown placement callback");
        }
    }
}
