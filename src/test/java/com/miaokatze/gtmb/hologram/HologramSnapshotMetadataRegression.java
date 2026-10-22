package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Compares complete field/NBT semantics with the original metadata writer using actual budget boundaries. */
final class HologramSnapshotMetadataRegression {

    static void run() {
        try {
            typedScalarCopyOracle();
            frozenWorkerContext();
            repeatedPayloadSlices();
            coordinateMembershipComplexity();
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            UUID identity = UUID.randomUUID();
            for (Field member : Entity.class.getDeclaredFields()) if (member.getType() == UUID.class) {
                member.setAccessible(true);
                member.set(player, identity);
            }
            for (Field member : EntityPlayer.class.getDeclaredFields()) if (member.getType() == GameProfile.class) {
                member.setAccessible(true);
                member.set(player, new GameProfile(identity, "Metadata"));
            }
            player.worldObj = world;
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.isCreativeMode = true;
            player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            candidateSummaryGeneration(player);
            HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
            session.virtual = true;
            session.virtualPlacement = true;
            session.virtualPreset = new HologramPresets.Preset();
            session.virtualPreset.schema = 2;
            session.virtualPreset.name = "metadata fixture";
            session.virtualPreset.controllerType = "area";
            session.materialSettings.main = false;
            session.materialSettings.me = false;
            session.materialSettings.containers = false;
            for (int i = 0; i < 512; i++) {
                HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), i, 0, 0, -1, false);
                if (i % 17 == 0) {
                    NBTTagCompound payload = new NBTTagCompound();
                    payload.setInteger("configuration", i);
                    NBTTagCompound nested = new NBTTagCompound();
                    nested.setString("text", "kept value " + i);
                    payload.setTag("nested", nested);
                    cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
                }
                session.virtualPreset.cells.add(cell);
            }
            HologramBlueprintProjection.Population population = new HologramBlueprintProjection.Population(session);
            while (!population.step(31, Long.MAX_VALUE)) {}
            session.selected = 17;
            session.selectedIndices.add(17);
            session.selectedIndices.add(18);
            session.processingPolicy = HologramBlueprintProcessing.defaults("self", 0);
            HologramBlueprintProcessing.CellNbtOverride override = new HologramBlueprintProcessing.CellNbtOverride();
            override.x = 17;
            override.all = false;
            override.keys.add("configuration");
            override.ignoredPaths.add("tile:/nested");
            session.processingPolicy.cellNbtOverrides.add(override);
            HologramPresets.HintArea hint = new HologramPresets.HintArea();
            hint.x1 = 2;
            hint.x2 = 3;
            hint.prompt = "preserved hint";
            session.virtualPreset.hintAreas.add(hint);
            NBTTagCompound actual = new NBTTagCompound();
            HologramSnapshotMetadataTask task = new HologramSnapshotMetadataTask(session, actual);
            SimpleAssert.that(session.processingSource == null, "metadata constructor does no source projection");
            long deadline = System.nanoTime() + 10_000_000_000L;
            int batches = 0;
            while (!task.step(7, Long.MAX_VALUE) && System.nanoTime() < deadline) {
                SimpleAssert.that(
                    task.done() >= 0 && task.done() <= task.total(),
                    "real metadata phase counts remain coherent");
                batches++;
                Thread.yield();
            }
            SimpleAssert.that(
                "metadata.complete".equals(task.phase()) && batches > 100,
                "full metadata finishes through many bounded main-thread continuations");
            NBTTagCompound expected = new NBTTagCompound();
            HologramSnapshots.writeBlueprintState(session, expected);
            HologramSnapshots.aeSnapshot(session, expected);
            SimpleAssert
                .eq(expected, actual, "budgeted metadata preserves every existing snapshot field and NBT entry");
            SimpleAssert.that(
                session.nbtKeysCapture == session.capture && session.availableNbtKeys.contains("configuration"),
                "completed metadata publishes the existing available-key cache generation");
            creativeSnapshotMaterialDemand(session);
            unchangedReviewControls(session);
            nativeMetadata(unsafe, player, world);
            pendingErrorMetadata(session);
            HologramSnapshotMetadataTask stale = new HologramSnapshotMetadataTask(session, new NBTTagCompound());
            session.planRevision++;
            try {
                stale.step(7, Long.MAX_VALUE);
                throw new AssertionError("obsolete metadata accepted");
            } catch (IllegalStateException correct) {
                SimpleAssert.that(true, "new plan revision rejects old metadata work");
            }
        } catch (Exception failed) {
            throw new AssertionError("snapshot metadata", failed);
        }
    }

    private static void candidateSummaryGeneration(EntityPlayerMP player) {
        HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
        session.materialSettings.main = session.materialSettings.me = session.materialSettings.containers = false;
        ItemStack signal = new ItemStack(Blocks.stone);
        session.capture = new HologramCapture(null, signal);
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> declared = com.gtnewhorizon.structurelib.structure.StructureUtility
            .ofBlock(Blocks.stone, 0);
        HologramCapture.Cell cell = new HologramCapture.Cell(declared, 1, 64, 0, signal);
        cell.block = Blocks.stone;
        session.capture.cells.add(cell);
        session.capture.positions.put(HologramValues.position(cell), cell);
        session.planTargets.add(signal.copy());
        session.planStatus.add("pending");
        session.selected = -1;
        NBTTagCompound baseline = HologramSnapshots.cellRow(session, 0);
        SimpleAssert
            .that(baseline.getBoolean("candidateComplete"), "unselected native row has a real complete declaration");
        SimpleAssert.eq(
            0,
            baseline.getTagList("candidates", 10)
                .tagCount(),
            "unselected rows still omit candidate options");
        java.util.List<ItemStack> cached = cell.candidates;
        HologramDeclarationAdapters.register(
            com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock(Blocks.dirt, 0),
            new HologramDeclarationAdapters.Declaration(declared));
        HologramCellPolicy.refreshDeclarationGeneration(session);
        SimpleAssert.that(
            cell.candidates == null && !cell.candidateComplete,
            "real unrelated adapter registration invalidates the prior candidate generation");
        NBTTagCompound restored = HologramSnapshots.cellRow(session, 0);
        SimpleAssert
            .eq(baseline, restored, "full row publication reinterprets the unchanged declaration after invalidation");
        SimpleAssert.that(cell.candidates != cached, "new generation recomputes the native cell cache");
        cached = cell.candidates;
        for (int repeat = 0; repeat < 50; repeat++) HologramSnapshots.cellRow(session, 0);
        SimpleAssert.that(
            cell.candidates == cached,
            "stable generations reuse the parsed candidates across repeated full rows");
        session.selected = 0;
        SimpleAssert.eq(
            1,
            HologramSnapshots.cellRow(session, 0)
                .getTagList("candidates", 10)
                .tagCount(),
            "selected native rows retain their actual editable candidate options");
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> incomplete = com.gtnewhorizon.structurelib.structure.StructureUtility
            .onlyIf(
                context -> { throw new AssertionError("snapshot evaluated an arbitrary declaration predicate"); },
                declared);
        HologramCapture.Cell unknown = new HologramCapture.Cell(incomplete, 2, 64, 0, signal);
        unknown.block = Blocks.stone;
        session.capture.cells.add(unknown);
        session.capture.positions.put(HologramValues.position(unknown), unknown);
        session.planTargets.add(signal.copy());
        session.planStatus.add("pending");
        NBTTagCompound protectedRow = HologramSnapshots.cellRow(session, 1);
        SimpleAssert.that(
            !protectedRow.getBoolean("candidateComplete") && !protectedRow.getString("candidateBoundary")
                .isEmpty(),
            "a genuinely incomplete custom declaration remains explicitly incomplete after snapshot interpretation");
    }

    /** Exercise the actual DTO collector which formerly omitted all ordinary items in creative. */
    private static void creativeSnapshotMaterialDemand(HologramSession session) throws Exception {
        session.capture.cells.get(1).status = "pending";
        HologramLocalPlanRefresh.snapshotBegin(session);
        Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSnapshotPreparation$Task");
        java.lang.reflect.Constructor<?> constructor = type
            .getDeclaredConstructor(HologramSession.class, HologramNetwork.SnapshotTicket.class);
        constructor.setAccessible(true);
        Object collector = constructor.newInstance(session, null);
        java.lang.reflect.Method cell = type.getDeclaredMethod("addCell", int.class);
        cell.setAccessible(true);
        cell.invoke(collector, 1);
        java.lang.reflect.Method resource = type.getDeclaredMethod("addResource", ItemStack.class);
        resource.setAccessible(true);
        resource.invoke(collector, new ItemStack(net.minecraft.init.Items.stick, 256));
        Field field = type.getDeclaredField("requirements");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<HologramBlueprintResources.ExactKey, NBTTagCompound> rows = (java.util.Map<HologramBlueprintResources.ExactKey, NBTTagCompound>) field
            .get(collector);
        NBTTagCompound stone = rows.get(HologramValues.lookupKey(new ItemStack(Blocks.stone)));
        NBTTagCompound contents = rows.get(HologramValues.lookupKey(new ItemStack(net.minecraft.init.Items.stick)));
        SimpleAssert.that(
            stone != null && stone.getLong("required") == 1,
            "creative DTO keeps ordinary block shell demand despite free construction payments");
        SimpleAssert.that(
            contents != null && contents.getLong("required") == 256 && contents.getByte("Count") == 1,
            "creative DTO keeps exact internal item demand independent of native byte Count");
    }

    private static void unchangedReviewControls(HologramSession session) {
        session.virtualLocked = session.blueprintAwaitingConfirmation = session.placementReviewIssued = true;
        session.virtualPreset.deidentificationLevel = 1;
        session.virtualPreset.deidentificationChosen = session.deidentificationChosen = true;
        session.placementDeidentificationChosen = true;
        session.placementDeidentificationLevel = 1;
        NBTTagCompound published = new NBTTagCompound();
        published.setInteger("protected", 0);
        HologramLocalPlanRefresh.snapshotComplete(session, published);
        long revision = session.planRevision, nonce = session.placementReviewNonce;
        NBTTagCompound repeated = new NBTTagCompound();
        repeated.setString("session", session.id);
        repeated.setLong("planRevision", revision);
        repeated.setLong("placementReviewNonce", nonce);
        repeated.setInteger("level", 1);
        repeated.setBoolean("all", true);
        repeated.setBoolean("retainCovers", HologramSessionPolicy.placementRetainCovers(session));
        for (int i = 0; i < 10; i++) {
            SimpleAssert.that(
                HologramAuthoringWorkflow.blueprintAction(session, repeated, "configurePlacementNbt"),
                "unchanged NBT retention uses the existing review");
            SimpleAssert.that(
                HologramAuthoringWorkflow.blueprintAction(session, repeated, "configurePlacementDeidentification"),
                "repeated identity selection uses the existing review");
            SimpleAssert.that(
                HologramAuthoringWorkflow.blueprintAction(session, repeated, "removeBlueprintObstructions"),
                "published zero blockers require no world traversal");
        }
        SimpleAssert.that(
            session.planRevision == revision && session.placementReviewNonce == nonce,
            "thirty no-op review actions never rebuild the plan or invalidate review authority");
        repeated.setBoolean("all", false);
        SimpleAssert.that(
            !HologramAuthoringWorkflow.unchangedBlueprintAction(session, repeated, "configurePlacementNbt"),
            "an actual NBT retention change still reaches the planner");
        repeated.setInteger("level", 2);
        SimpleAssert.that(
            !HologramAuthoringWorkflow
                .unchangedBlueprintAction(session, repeated, "configurePlacementDeidentification"),
            "a stronger identity selection is not swallowed as a retry");
        net.minecraft.nbt.NBTTagList invalidKeys = new net.minecraft.nbt.NBTTagList();
        invalidKeys.appendTag(new net.minecraft.nbt.NBTTagString(""));
        repeated.setTag("keys", invalidKeys);
        SimpleAssert.that(
            !HologramAuthoringWorkflow.unchangedBlueprintAction(session, repeated, "configurePlacementNbt"),
            "invalid NBT keys reach the standard error handler instead of becoming a no-op");
    }

    /** Every declared scalar participates, so adding a DTO field without updating its typed copy fails here. */
    private static void typedScalarCopyOracle() throws ReflectiveOperationException {
        for (Class<?> type : new Class<?>[] { HologramPresets.Preset.class, HologramPresets.Cell.class,
            HologramBlueprintProcessing.Policy.class }) {
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object source = constructor.newInstance(), target = constructor.newInstance();
            java.util.List<Field> scalars = new java.util.ArrayList<>();
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || java.lang.reflect.Modifier.isTransient(field.getModifiers())) continue;
                Class<?> value = field.getType();
                field.setAccessible(true);
                if (value == String.class) field.set(source, "scalar:" + field.getName());
                else if (value == int.class || value == Integer.class) field.set(source, 17);
                else if (value == long.class) field.set(source, 29L);
                else if (value == boolean.class) field.set(source, true);
                else continue;
                scalars.add(field);
            }
            java.lang.reflect.Method copy = HologramSnapshotMetadataSource.class
                .getDeclaredMethod("copyScalars", type, type);
            copy.setAccessible(true);
            copy.invoke(null, source, target);
            SimpleAssert.that(scalars.size() >= 5, "typed DTO oracle covers all scalar values");
            for (Field field : scalars) SimpleAssert.eq(
                field.get(source),
                field.get(target),
                "typed native-safe scalar copy preserves " + type.getSimpleName() + "." + field.getName());
        }
    }

    private static void nativeMetadata(sun.misc.Unsafe unsafe, EntityPlayerMP player,
        HologramServiceEditRegression.ReadOnlyWorld world) throws Exception {
        HologramSession session = new HologramSession(player, 0, 100, 0, false, 2);
        HologramDynamicDefinitionRegression.DynamicController controller = new HologramDynamicDefinitionRegression.DynamicController();
        HologramDynamicDefinitionRegression.NativeTile tile = (HologramDynamicDefinitionRegression.NativeTile) unsafe
            .allocateInstance(HologramDynamicDefinitionRegression.NativeTile.class);
        tile.setWorldObj(world);
        tile.yCoord = 100;
        controller.setBaseMetaTileEntity(tile);
        session.target = tile;
        session.machine = controller;
        session.context = controller;
        session.capture = new HologramCapture(controller, new ItemStack(Blocks.stone));
        HologramCapture.Cell cell = new HologramCapture.Cell(null, 1, 100, 0);
        cell.block = Blocks.stone;
        cell.userPosition = true;
        session.capture.cells.add(cell);
        session.capture.positions.put("1,100,0", cell);
        session.pins.put(0, new ItemStack(Blocks.stone));
        NBTTagCompound actual = new NBTTagCompound();
        HologramSnapshotMetadataTask task = new HologramSnapshotMetadataTask(session, actual);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!task.step(3, Long.MAX_VALUE) && System.nanoTime() < deadline) Thread.yield();
        SimpleAssert.eq("metadata.complete", task.phase(), "native metadata completes in bounded phases");
        NBTTagCompound expected = new NBTTagCompound();
        HologramSnapshots.writeBlueprintState(session, expected);
        HologramSnapshots.aeSnapshot(session, expected);
        SimpleAssert
            .eq(expected, actual, "native metadata preserves all legacy snapshot fields without a virtual preset");
        SimpleAssert.that(
            actual.hasKey("sourceMod", 8) && actual.hasKey("sourceClass", 8)
                && controller.getClass()
                    .getName()
                    .equals(actual.getString("sourceClass")),
            "a native controller always publishes its source identity even without saved blueprint dependencies");
    }

    private static void pendingErrorMetadata(HologramSession session) throws Exception {
        session.localPlanPending = true;
        session.localPlanError = "metadata fixture: retry required";
        session.processingSource = null;
        session.capture.cells.get(0).actualFacing = 2;
        session.capture.cells.get(17).actualFacing = 2;
        session.planFacings.put(0, 5);
        session.planFacings.put(17, 3);
        session.facingPins.put(HologramValues.position(session.capture.cells.get(17)), 4);
        session.planTargets.clear();
        session.planTargets.add(new ItemStack(Blocks.dirt));
        session.blueprintResources = new HologramBlueprintResources.Plan()
            .addItems(java.util.Collections.singletonList(new ItemStack(Blocks.diamond_block, 63)));
        NBTTagCompound state = new NBTTagCompound();
        HologramSnapshotMetadataTask task = new HologramSnapshotMetadataTask(session, state);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!task.step(7, Long.MAX_VALUE) && System.nanoTime() < deadline) Thread.yield();
        SimpleAssert
            .eq("metadata.complete", task.phase(), "persistent plan errors can publish complete authoring metadata");
        SimpleAssert.that(
            state.getBoolean("planIncomplete") && state.getBoolean("quotesIncomplete")
                && state.getTagList("blueprintResources", 10)
                    .tagCount() == 0,
            "failed partial plans cannot publish nonempty material or cache quotes");
        Field resourceTask = HologramSnapshotMetadataTask.class.getDeclaredField("resources");
        resourceTask.setAccessible(true);
        SimpleAssert
            .that(resourceTask.get(task) == null, "draft error metadata never creates a native resource quote task");
        SimpleAssert.that(
            session.localPlanPending && !session.localPlanError.isEmpty(),
            "publishing a faithful draft never clears the persistent planning failure");
        SimpleAssert.eq(
            2,
            session.processingSource.cells.get(0).facing,
            "a draft source uses its real source facing instead of a partial automatic facing");
        SimpleAssert.eq(
            4,
            session.processingSource.cells.get(17).facing,
            "an authored facing pin remains authoritative while the plan is unavailable");
        SimpleAssert.eq(
            "minecraft:stone",
            session.processingSource.cells.get(0).item,
            "authoring targets do not read a poisoned partial plan target");
        session.processingSource.cells.get(0).facing = 5;
        HologramSnapshotMetadataSource cached = new HologramSnapshotMetadataSource(
            session,
            new HologramSnapshotDataContext());
        while (!cached.step(7, Long.MAX_VALUE)) {}
        SimpleAssert.eq(
            2,
            cached.result.cells.get(0).facing,
            "the cached processing source is also projected through authoring facing rules on an error reopen");
    }

    private static void frozenWorkerContext() throws Exception {
        HologramPresets.Cell known = new HologramPresets.Cell();
        known.block = "minecraft:stone";
        HologramSnapshotMetadataSource.freezeBlock(known);
        known.block = "minecraft:dirt";
        HologramPresets.Cell missing = new HologramPresets.Cell();
        missing.block = "missing:metadata-block";
        HologramSnapshotMetadataSource.freezeBlock(missing);
        missing.block = "minecraft:stone";
        HologramPresets.Cell unsealed = new HologramPresets.Cell();
        unsealed.block = "minecraft:stone";
        HologramSnapshotDataContext frozenContext = new HologramSnapshotDataContext();
        NBTTagCompound payload = new NBTTagCompound();
        payload.setTag("recipe", new ItemStack(Blocks.diamond_block, 7).writeToNBT(new NBTTagCompound()));
        String encoded = HologramBlueprintNbt.encodeCaptured(payload);
        frozenContext.freeze(encoded);
        NBTTagCompound unknownPayload = new NBTTagCompound();
        unknownPayload.setInteger("unfrozen", 42);
        String unknownEncoded = HologramBlueprintNbt.encodeCaptured(unknownPayload);
        NBTTagCompound generated = new NBTTagCompound();
        generated.setString("id", "q1");
        generated.setInteger("x", 7);
        SimpleAssert.eq(
            HologramBlueprintNbt.encodeCanonical(generated),
            HologramBlueprintNbt.encodeData(generated),
            "generated quantum metadata keeps the exact canonical fingerprint bytes through its data-only encoder");
        Thread owner = Thread.currentThread();
        java.util.concurrent.FutureTask<Boolean> task = new java.util.concurrent.FutureTask<>(() -> {
            SimpleAssert
                .that(Thread.currentThread() != owner, "classification check runs on the actual compute worker");
            HologramSnapshotMetadataSource.enterDetached(frozenContext);
            try {
                SimpleAssert.that(
                    known.block() == Blocks.stone,
                    "worker uses the frozen Block reference instead of the current registry key");
                SimpleAssert.that(
                    missing.block() == null,
                    "a frozen missing block cannot fall back to a newly resolvable registry key");
                NBTTagCompound first = HologramBlueprintNbt.decode(encoded);
                SimpleAssert.eq(
                    (byte) 7,
                    first.getCompoundTag("recipe")
                        .getByte("Count"),
                    "detached NBT retains the exact registry-resolved source item count");
                first.getCompoundTag("recipe")
                    .setByte("Count", (byte) 1);
                SimpleAssert.eq(
                    (byte) 7,
                    HologramBlueprintNbt.decode(encoded)
                        .getCompoundTag("recipe")
                        .getByte("Count"),
                    "each detached NBT read is an independent copy of the main-resolved value");
                try {
                    HologramBlueprintNbt.decode(unknownEncoded);
                    throw new AssertionError("worker resolved unfrozen NBT");
                } catch (java.io.IOException correct) {
                    SimpleAssert.that(
                        correct.getMessage()
                            .contains("not frozen"),
                        "unknown encoded values are rejected before any item/cover registry remapping");
                }
                try {
                    unsealed.block();
                    throw new AssertionError("worker accepted an unfrozen block");
                } catch (IllegalStateException correct) {
                    return true;
                }
            } finally {
                HologramSnapshotMetadataSource.exitDetached();
            }
        });
        SimpleAssert.that(HologramAreaPreparation.execute(task), "classification task admitted");
        SimpleAssert.eq(
            Boolean.TRUE,
            task.get(5, java.util.concurrent.TimeUnit.SECONDS),
            "worker context rejects implicit native identity resolution");
        SimpleAssert
            .that(known.block() == Blocks.dirt, "owner thread retains the original live registry lookup semantics");
    }

    /** Real NBT/native classification work uses the production time-only loop, without a fake fast clock. */
    private static void repeatedPayloadSlices() throws Exception {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setInteger("configuration", 73);
        payload.setString("preserved", "complete repeated payload");
        String encoded = HologramBlueprintNbt.encodeCaptured(payload);
        HologramSnapshotDataContext context = new HologramSnapshotDataContext();
        HologramPresets.Cell[] cells = new HologramPresets.Cell[50_000];
        for (int i = 0; i < cells.length; i++) {
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.block = "minecraft:stone";
            cell.tileNbt = encoded;
            cells[i] = cell;
        }
        for (int i = 0; i < 1024; i++) context.freezeBlock(cells[i]);
        java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger();
        int slices = 0;
        long started = System.nanoTime();
        while (cursor.get() < cells.length) {
            HologramSnapshotPreparation.runSlice(() -> {
                try {
                    context.freezeBlock(cells[cursor.getAndIncrement()]);
                } catch (java.io.IOException invalid) {
                    throw new AssertionError(invalid);
                }
            },
                () -> cursor.get() == cells.length,
                System.nanoTime() + HologramWorkBudget.SLICE_NANOS,
                System::nanoTime);
            if (++slices >= 160)
                throw new AssertionError("Repeated 50k metadata rows exhausted " + slices + " real slices");
        }
        SimpleAssert.eq(cells.length, cursor.get(), "all 50k repeated payloads are frozen through real time slices");
        SimpleAssert.that(
            cells[0].frozenSensorSlots == cells[cells.length - 1].frozenSensorSlots,
            "identical full native payloads reuse their immutable snapshot classification");
        HologramPresets.Cell different = new HologramPresets.Cell();
        different.block = "minecraft:stone";
        payload.setString(HologramNetworkBindings.GROUP, "different-group");
        different.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        context.freezeBlock(different);
        SimpleAssert.that(
            !cells[0].metadataIdentityBoundary && different.metadataIdentityBoundary,
            "the same block with different full NBT retains its distinct native identity boundary");
        SimpleAssert.eq(
            73,
            context.freeze(encoded)
                .getInteger("configuration"),
            "classification reuse preserves the complete original payload");
        System.out.println(
            "Snapshot 50k repeated payload owner slices=" + slices + " cpuNanos=" + (System.nanoTime() - started));
    }

    private static void coordinateMembershipComplexity() throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        CountingStructures structures = new CountingStructures();
        preset.structures = structures;
        for (int i = 0; i < 1024; i++) {
            HologramPresets.Structure structure = new HologramPresets.Structure();
            structure.id = "structure" + i;
            structure.enabled = i % 2 == 0;
            structures.add(structure);
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.x = i;
            cell.structureId = structure.id;
            NBTTagCompound data = new NBTTagCompound();
            data.setString("id", "IC2NCInfoPanelExtender");
            data.setBoolean("partOfScreen", true);
            data.setInteger("coreX", 200 + i);
            data.setInteger("coreY", 64);
            data.setInteger("coreZ", 300);
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(data);
            preset.cells.add(cell);
        }
        java.util.List<HologramCoordinatePolicies.Relation> relations = HologramCoordinatePolicies
            .inspect(preset, 0, 200, 64, 300);
        SimpleAssert.eq(1024, relations.size(), "all real extender descriptors remain present");
        for (int i = 0; i < relations.size(); i++) SimpleAssert.eq(
            i % 2 == 0,
            relations.get(i).internal,
            "relation internal/external classification matches its authoritative enabled membership");
        SimpleAssert.eq(
            1024,
            structures.visits,
            "enabled membership classification scans structures once for the entire graph");
    }

    private static final class CountingStructures extends java.util.ArrayList<HologramPresets.Structure> {

        private static final long serialVersionUID = 1L;
        int visits;

        @Override
        public java.util.Iterator<HologramPresets.Structure> iterator() {
            java.util.Iterator<HologramPresets.Structure> delegate = super.iterator();
            return new java.util.Iterator<HologramPresets.Structure>() {

                public boolean hasNext() {
                    return delegate.hasNext();
                }

                public HologramPresets.Structure next() {
                    visits++;
                    return delegate.next();
                }
            };
        }
    }
}
