package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.Declarations;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Native continuation work counts and candidate/plan tail parity on the owning server thread. */
public final class HologramControllerCaptureRegression {

    private HologramControllerCaptureRegression() {}

    public static void main(String[] args) throws Exception {
        int status = 0;
        try {
            if (!MinecraftTestBootstrap.enter(HologramControllerCaptureRegression.class, args)) return;
            run();
            System.out.println("Hologram controller capture continuation regressions passed");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            status = 1;
        }
        // This dedicated JavaExec owns vanilla's process-lifetime, non-daemon File IO Thread.
        System.exit(status);
    }

    static void run() {
        String threadName = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            nativePrefix(unsafe);
            finishSlices(unsafe);
            builtProofSlices(unsafe);
            lifecycle(unsafe);
            HologramDynamicDefinitionRegression.virtualMemberRebuild(unsafe);
        } catch (ReflectiveOperationException | InterruptedException | java.io.IOException failure) {
            throw new AssertionError("controller capture continuation", failure);
        } finally {
            Thread.currentThread()
                .setName(threadName);
        }
    }

    private static EntityPlayerMP player(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.isCreativeMode = true;
        player.capabilities.allowEdit = true;
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        UUID identity = UUID.randomUUID();
        for (Field member : Entity.class.getDeclaredFields()) if (member.getType() == UUID.class) {
            member.setAccessible(true);
            member.set(player, identity);
        }
        for (Field member : EntityPlayer.class.getDeclaredFields()) if (member.getType() == GameProfile.class) {
            member.setAccessible(true);
            member.set(player, new GameProfile(identity, "CaptureSlices"));
        }
        return player;
    }

    private static void nativePrefix(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        EntityPlayerMP player = player(unsafe);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) player.worldObj;
        HologramDynamicDefinitionRegression.DynamicController controller = new HologramDynamicDefinitionRegression.DynamicController();
        HologramDynamicDefinitionRegression.NativeTile tile = (HologramDynamicDefinitionRegression.NativeTile) unsafe
            .allocateInstance(HologramDynamicDefinitionRegression.NativeTile.class);
        tile.setWorldObj(world);
        tile.yCoord = 100;
        controller.setBaseMetaTileEntity(tile);
        world.occupant = tile;
        HologramSession s = new HologramSession(player, 0, 100, 0, false, 2);
        s.target = tile;
        s.machine = controller;
        s.context = controller;
        s.channels.setInteger("shape", 100);
        // This detached fixture uses real main-inventory material access without FML/AE registries.
        s.materialSettings.containers = s.materialSettings.me = false;
        s.capture = new HologramCapture(null, HologramSessionPolicy.trigger(s));
        for (int i = 0; i < 4096; i++) s.capture.cells.add(new HologramCapture.Cell(null, i, 64, 0));
        s.pins.put(4000, new ItemStack(Blocks.stone));
        s.selected = 4001;
        s.selectedIndices.add(4002);
        CountingCells previous = count(s.capture);
        HologramWorkBudget.startServerTick();
        try {
            HologramControllerCapture.collect(s);
        } finally {
            HologramWorkBudget.endServerTick();
        }
        SimpleAssert.eq(3, previous.reads, "new capture reads only pinned and selected cells from the old plan");
        SimpleAssert.that(s.capture.pending && s.capturePending, "large native capture yields before finalization");
        SimpleAssert.that(
            s.capture.deadline <= s.capture.started + HologramWorkBudget.SLICE_NANOS,
            "real first game capture uses a cooperative slice rather than the standalone 100 ms walk");
        SimpleAssert.eq(123L, HologramWorkBudget.captureDeadline(123L), "request releases its budget before returning");
        HologramControllerCapture.FinishWork work = s.captureWork;
        CountingCells cells = count(s.capture);
        int constructs = controller.constructions, definitions = controller.definitionBuilds;
        HologramControllerCapture.collect(s, false);
        SimpleAssert.that(s.capture.pending, "second native slice still precedes the candidate tail");
        SimpleAssert.eq(0, cells.reads, "native continuation never reads the previously captured prefix for pins");
        SimpleAssert.that(s.captureWork == work, "one edit/selection snapshot is retained across native slices");
        SimpleAssert.eq(constructs, controller.constructions, "continuation never repeats native construct");
        SimpleAssert.eq(definitions, controller.definitionBuilds, "continuation keeps its frozen definition");
        HologramSession.cancelCaptureRetry(s);
        SimpleAssert.that(s.captureWork == null && !s.capturePending, "cancellation releases the retained tail");
    }

    private static HologramSession session(EntityPlayerMP player) {
        HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
        s.materialSettings.containers = s.materialSettings.me = false;
        s.capture = new HologramCapture(null, HologramSessionPolicy.trigger(s));
        for (int i = 0; i < 257; i++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(
                i == 256 ? null : StructureUtility.ofBlock(Blocks.stone, 0),
                i,
                64,
                0);
            cell.block = Blocks.stone;
            cell.anchor = i == 0;
            cell.unknown = i == 256;
            s.capture.cells.add(cell);
            s.capture.positions.put(HologramValues.position(cell), cell);
        }
        s.scanSelectedPosition = "3,64,0";
        s.scanSelectedPositions.add("4,64,0");
        NBTTagCompound annotation = new NBTTagCompound();
        annotation.setIntArray("position", new int[] { 5, 0, 0 });
        annotation.setString("prompt", "preserved annotation");
        s.blueprintDraftAnnotations.appendTag(annotation);
        return s;
    }

    private static void finishSlices(sun.misc.Unsafe unsafe) throws ReflectiveOperationException, InterruptedException {
        EntityPlayerMP player = player(unsafe);
        HologramSession staged = session(player), sync = session(player);
        Map<String, ItemStack> saved = new HashMap<>();
        saved.put("0,64,0", new ItemStack(Blocks.dirt));
        saved.put("3,64,0", new ItemStack(Blocks.stone));
        saved.put("1000,64,0", new ItemStack(Blocks.dirt));
        HologramControllerCapture.FinishWork work = new HologramControllerCapture.FinishWork(saved);
        staged.captureWork = work;
        staged.capturePending = true;
        work.beginFinish();
        CountingCells cells = count(staged.capture);
        SimpleAssert.that(!work.step(staged, 0, Long.MAX_VALUE), "zero work cannot begin candidate restoration");
        SimpleAssert.that(!work.step(staged, 1, 0), "consumed capture time defers the finish/planning tail");
        SimpleAssert.eq(0, cells.reads, "zero budget performs no candidate or plan reads");
        Throwable[] wrongThread = { null };
        Thread worker = new Thread(() -> {
            try {
                work.step(staged, 1, Long.MAX_VALUE);
            } catch (Throwable rejected) {
                wrongThread[0] = rejected;
            }
        });
        worker.start();
        worker.join();
        SimpleAssert.that(wrongThread[0] instanceof IllegalStateException, "world planning rejects worker threads");
        Set<String> phases = new HashSet<>();
        int slices = 0;
        boolean done = false;
        while (!done && slices++ < 20000) {
            phases.add(work.phase());
            done = work.step(staged, 7, Long.MAX_VALUE);
            SimpleAssert.that(work.lastWork() <= 7, "candidate and plan phases share one operation budget");
            SimpleAssert.that(staged.capturePending, "partially built plans retain the capture admission fence");
        }
        SimpleAssert.that(done && slices > 100, "candidate and native plan tails finish over many slices");
        SimpleAssert.that(
            phases.contains("capture.candidates") && phases.contains("preset.cells")
                && phases.contains("plan.classify")
                && phases.contains("resources.cells"),
            "the real candidate, native preset, classify and resource loops all yield");
        HologramCapture.Cell manual = new HologramCapture.Cell(null, 1000, 64, 0);
        manual.userPosition = true;
        manual.block = Blocks.air;
        manual.role = "manual";
        sync.capture.cells.add(manual);
        sync.capture.positions.put(HologramValues.position(manual), manual);
        for (int i = 0; i < sync.capture.cells.size(); i++) {
            HologramCapture.Cell cell = sync.capture.cells.get(i);
            ItemStack pin = saved.get(HologramValues.position(cell));
            if (pin != null && !cell.anchor) sync.pins.put(i, pin.copy());
            cell.candidates = HologramCellPolicy.candidates(sync, cell);
        }
        sync.selected = 3;
        sync.selectedIndices.add(4);
        HologramDraftPersistence.restoreBlueprintAnnotations(sync);
        HologramResourcePlanner.refreshPlan(sync);
        SimpleAssert
            .eq(sync.planStatus, staged.planStatus, "bounded native classification equals synchronous planning");
        SimpleAssert
            .eq(sync.expectedBlocks, staged.expectedBlocks, "bounded world observations preserve their indexes");
        SimpleAssert.eq(sync.planFacings, staged.planFacings, "native facing plan parity");
        SimpleAssert.eq(sync.planMissing, staged.planMissing, "native material shortage parity");
        SimpleAssert.eq(sync.planRevision, staged.planRevision, "one plan revision is issued per completed capture");
        SimpleAssert.eq(3, staged.selected, "single selection is restored by position");
        SimpleAssert.that(staged.selectedIndices.contains(4), "multi-selection is restored by position");
        SimpleAssert.that(
            !staged.pins.containsKey(0) && staged.pins.containsKey(257),
            "anchors stay protected and missing edited positions remain manual cells");
        SimpleAssert.eq("protected", staged.capture.cells.get(0).status, "anchor cannot become a build target");
        SimpleAssert.that(
            !"pending".equals(staged.capture.cells.get(256).status),
            "unknown declaration remains outside automatic construction");
        SimpleAssert
            .eq("preserved annotation", staged.capture.cells.get(5).prompt, "draft annotations survive slicing");
        long revision = staged.planRevision;
        SimpleAssert.that(work.step(staged, 7, Long.MAX_VALUE), "completed capture tail remains complete");
        SimpleAssert.eq(revision, staged.planRevision, "completed tail cannot issue another revision");
        HologramControllerCapture.collect(staged, false);
        SimpleAssert.that(
            staged.captureWork == null && !staged.capturePending,
            "production continuation releases the fence only after the complete plan exists");
    }

    private static CountingCells count(HologramCapture capture) throws ReflectiveOperationException {
        CountingCells cells = new CountingCells(capture.cells);
        Field field = HologramCapture.class.getDeclaredField("cells");
        field.setAccessible(true);
        field.set(capture, cells);
        return cells;
    }

    private static HologramSession proofSession(sun.misc.Unsafe unsafe, ProofLeaf leaf)
        throws ReflectiveOperationException {
        HologramSession s = session(player(unsafe));
        HologramDynamicDefinitionRegression.NativeTile tile = (HologramDynamicDefinitionRegression.NativeTile) unsafe
            .allocateInstance(HologramDynamicDefinitionRegression.NativeTile.class);
        tile.setWorldObj(s.player.worldObj);
        s.target = tile;
        s.machine = new HologramDynamicDefinitionRegression.DynamicController();
        s.machine.setBaseMetaTileEntity(tile);
        s.capture.cells.clear();
        s.capture.positions.clear();
        for (int i = 0; i < 257; i++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(leaf, i, 64, 0);
            cell.anchor = i == 0;
            cell.block = Blocks.air;
            s.capture.cells.add(cell);
            s.capture.positions.put(HologramValues.position(cell), cell);
        }
        return s;
    }

    private static void builtProofSlices(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        ProofLeaf leaf = new ProofLeaf();
        HologramSession s = proofSession(unsafe, leaf);
        s.replacementReady = s.replacementAuthorized = true;
        HologramResourcePlanner.PlanTask task = HologramResourcePlanner.beginCapturePlan(s);
        SimpleAssert.that(!task.step(1, Long.MAX_VALUE), "initialization cannot finish a native plan");
        SimpleAssert.eq(0, leaf.calls, "initial plan unit does not scan the complete original structure");
        SimpleAssert.eq("plan.structure", task.phase(), "native built proof has an explicit continuation phase");
        SimpleAssert
            .that(!s.replacementReady && !s.replacementAuthorized, "partial proof grants no replacement authority");
        SimpleAssert.that(!task.step(7, 0), "zero time cannot advance native built proof");
        SimpleAssert.eq(0, leaf.calls, "zero proof time performs no native declaration reads");
        int slices = 0;
        while ("plan.structure".equals(task.phase()) && slices++ < 1000) {
            int before = leaf.calls;
            task.step(3, Long.MAX_VALUE);
            SimpleAssert.that(leaf.calls - before <= 3, "one proof slice reads at most its assigned cell units");
            if ("plan.structure".equals(task.phase()))
                SimpleAssert.that(!s.replacementReady, "successful prefix cannot publish a whole-structure proof");
        }
        SimpleAssert.that(slices > 80 && slices < 1000, "complete native proof yields over many slices");
        SimpleAssert.eq(256, leaf.calls, "proof examines every non-anchor once");
        SimpleAssert.eq(
            HologramPlanPolicy.replacementStructureBuilt(s),
            s.replacementReady,
            "incremental proof equals the unchanged synchronous replacement preflight");
        SimpleAssert
            .that(s.replacementReady && !s.replacementAuthorized, "complete proof does not authorize replacement");

        ProofLeaf rejected = new ProofLeaf();
        rejected.failAt = 11;
        HologramSession bad = proofSession(unsafe, rejected);
        HologramResourcePlanner.PlanTask badTask = HologramResourcePlanner.beginCapturePlan(bad);
        badTask.step(1, Long.MAX_VALUE);
        for (int i = 0; i < 20 && "plan.structure".equals(badTask.phase()); i++) badTask.step(2, Long.MAX_VALUE);
        SimpleAssert.eq(11, rejected.calls, "negative proof stops at the first unsupported original member");
        SimpleAssert.that(!bad.replacementReady && !bad.replacementAuthorized, "negative proof cannot grant authority");
        rejected.calls = 0;
        SimpleAssert.that(!HologramPlanPolicy.replacementStructureBuilt(bad), "synchronous negative preflight agrees");
        SimpleAssert.eq(11, rejected.calls, "synchronous negative proof keeps its original early return");

        HologramSession changed = proofSession(unsafe, new ProofLeaf());
        HologramResourcePlanner.PlanTask stale = HologramResourcePlanner.beginCapturePlan(changed);
        stale.step(2, Long.MAX_VALUE);
        changed.capture = new HologramCapture(null, HologramSessionPolicy.trigger(changed));
        boolean captureRejected = false;
        try {
            stale.step(3, Long.MAX_VALUE);
        } catch (IllegalStateException expected) {
            captureRejected = true;
        }
        SimpleAssert.that(captureRejected && !changed.replacementReady, "changed capture cannot reuse a partial proof");

        HologramSession invalid = proofSession(unsafe, new ProofLeaf());
        HologramResourcePlanner.PlanTask invalidTask = HologramResourcePlanner.beginCapturePlan(invalid);
        invalidTask.step(2, Long.MAX_VALUE);
        invalid.capture.invalidateSource("fixture declaration source changed");
        boolean sourceRejected = false;
        try {
            invalidTask.step(3, Long.MAX_VALUE);
        } catch (IllegalStateException expected) {
            sourceRejected = true;
        }
        SimpleAssert
            .that(sourceRejected && !invalid.replacementReady, "invalidated source cannot finish a partial proof");
    }

    private static final class ProofLeaf extends HologramPureElementsRegression.Leaf {

        int calls, failAt;

        ProofLeaf() {
            super(true);
        }

        @Override
        public boolean matches(Declarations.WorldState state) {
            return state.loaded && (++calls != failAt);
        }
    }

    private static void changedSignal(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        EntityPlayerMP player = player(unsafe);
        connect(player);
        HologramSession s = session(player);
        s.uiSequence = 88;
        HologramWorkspace.activate(player.getUniqueID(), s);
        HologramControllerCapture.FinishWork work = new HologramControllerCapture.FinishWork(new HashMap<>());
        s.captureWork = work;
        s.capturePending = true;
        work.beginFinish();
        SimpleAssert.that(!work.step(s, 7, Long.MAX_VALUE), "signal fixture yields during candidate resolution");
        SimpleAssert.eq("capture.candidates", work.phase(), "fixture really retains a pending candidate tail");
        Field cursor = HologramControllerCapture.FinishWork.class.getDeclaredField("cursor");
        cursor.setAccessible(true);
        int next = cursor.getInt(work);
        int[] callbacks = { 0 };
        HologramCapture.Cell tripwire = new HologramCapture.Cell(new HologramPureElementsRegression.Leaf(true) {

            @Override
            public List<ItemStack> candidates() {
                callbacks[0]++;
                return super.candidates();
            }
        }, next, 64, 0);
        tripwire.block = Blocks.stone;
        s.capture.cells.set(next, tripwire);
        s.capture.positions.put(HologramValues.position(tripwire), tripwire);
        player.getHeldItem()
            .getTagCompound()
            .setInteger("customStructureSignal", 9);
        // Execute the real next unit without wall-clock flakiness, before the normal owning workflow retires it.
        work.step(s, 1, Long.MAX_VALUE);
        SimpleAssert.eq(0, callbacks[0], "known expired source cannot invoke another declaration candidate callback");
        HologramControllerCapture.collect(s, false);
        SimpleAssert.that(s.capture.incomplete, "a real custom signal change rejects the pending plan tail");
        SimpleAssert.eq("SOURCE_TRIGGER_CHANGED", s.capture.failure, "full trigger NBT guard survives finalization");
        SimpleAssert.that(!s.capture.sourceCurrent(), "stale trigger rejection also invalidates the retained source");
        SimpleAssert.that(s.planStatus.isEmpty(), "no classified plan is published for a stale capture");
        SimpleAssert
            .that(!s.capturePending && s.captureWork == null, "source rejection retires the pending tail immediately");
        SimpleAssert.eq(4, s.job, "source rejection reaches the reliable stopped workflow");
        SimpleAssert.eq("STOPPED", s.phase, "failed source cannot resume through the ordinary retry lane");
        NBTTagCompound terminal = WIRE.get(WIRE.size() - 1);
        SimpleAssert
            .that(terminal.getBoolean("transportError"), "source rejection emits the actual client failure envelope");
        SimpleAssert.eq(
            "capture.prepare",
            terminal.getString("errorCode"),
            "source failure uses the existing capture terminal protocol");
        SimpleAssert.eq(s.id, terminal.getString("session"), "source terminal belongs to the exact pending session");
        SimpleAssert.eq(88L, terminal.getLong("uiSequence"), "source terminal retires the exact scan command");
        int emitted = WIRE.size();
        work.step(s, 1, Long.MAX_VALUE);
        SimpleAssert.eq(emitted, WIRE.size(), "retired work cannot emit a second failure or invoke candidates");
        HologramSnapshots.send(s);
        SimpleAssert.that(
            !HologramSnapshotPreparation.pending(s),
            "source failure does not depend on an invalid normal snapshot");
        player.playerNetServerHandler.netManager.channel()
            .close();
    }

    private static void failedFinalization(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        EntityPlayerMP player = player(unsafe);
        connect(player);
        HologramSession s = session(player);
        s.uiSequence = 71;
        HologramWorkspace.activate(player.getUniqueID(), s);
        HologramCapture.Cell broken = new HologramCapture.Cell(new HologramPureElementsRegression.Leaf(true) {

            @Override
            public List<ItemStack> candidates() {
                throw new IllegalArgumentException("fixture native candidate failure");
            }
        }, 1, 64, 0);
        broken.block = Blocks.stone;
        s.capture.cells.set(1, broken);
        s.capture.positions.put(HologramValues.position(broken), broken);
        s.captureWork = new HologramControllerCapture.FinishWork(new HashMap<>());
        s.captureWork.beginFinish();
        s.capturePending = s.buildAfterCapture = s.calibratePending = true;
        try {
            HologramControllerCapture.collect(s, false);
        } catch (IllegalArgumentException beforeFix) {
            // The counterexample must establish the missing terminal state, not merely detect a thrown callback.
        }
        SimpleAssert.that(s.capture.incomplete, "candidate failure must reject the native capture");
        SimpleAssert.that(!s.capturePending && s.captureWork == null, "failed capture releases its continuation");
        SimpleAssert.that(!s.buildAfterCapture && !s.calibratePending, "failed capture cannot start deferred actions");
        SimpleAssert.eq(4, s.job, "failed capture reaches the stopped terminal state");
        SimpleAssert.that(s.status.contains("采集失败"), "failure remains visible instead of a frozen pending UI");
        SimpleAssert.that(s.capture.failure.contains("IllegalArgumentException"), "callback type is diagnosed");
        SimpleAssert.that(s.planStatus.isEmpty(), "failed candidate resolution publishes no classified plan");
        NBTTagCompound terminal = WIRE.get(WIRE.size() - 1);
        SimpleAssert
            .that(terminal.getBoolean("transportError"), "native candidate failure sends a real terminal receipt");
        SimpleAssert.eq(s.id, terminal.getString("session"), "failure receipt retains the exact capture session");
        SimpleAssert.eq(71L, terminal.getLong("uiSequence"), "failure receipt retains the exact capture command");
        SimpleAssert.eq("capture.prepare", terminal.getString("errorCode"), "native failure has a capture diagnostic");
        HologramSnapshots.send(s);
        SimpleAssert.that(!HologramSnapshotPreparation.pending(s), "failed capture cannot enqueue a broken baseline");
        s.virtual = true;
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.schema = 2;
        s.virtualPreset.name = "Capture recovery fixture";
        s.virtualPreset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
        HologramBlueprintProjection.populateVirtual(s);
        SimpleAssert.that(s.captureError.isEmpty(), "explicit virtual reconstruction clears the previous native error");
        HologramSnapshots.send(s);
        SimpleAssert
            .that(HologramSnapshotPreparation.pending(s), "a rebuilt virtual draft can publish its normal baseline");
        player.posY = 64;
        controller(unsafe, (HologramServiceEditRegression.ReadOnlyWorld) player.worldObj, 16);
        HologramProjectorWorkflow.open(player, 16, 64, 0, 2);
        SimpleAssert.that(
            HologramWorkspace.find(player.getUniqueID()) == s && s.discardConfirmationRequired,
            "an unsaved virtual author draft still requires confirmation before reading a controller");
        player.playerNetServerHandler.netManager.channel()
            .close();
    }

    private static final List<NBTTagCompound> WIRE = new ArrayList<>();

    private static void lifecycle(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        Field channel = HologramNetwork.class.getDeclaredField("channel");
        channel.setAccessible(true);
        Object previous = channel.get(null);
        try {
            channel.set(null, unsafe.allocateInstance(RecordingChannel.class));
            failedFinalization(unsafe);
            changedSignal(unsafe);
            replacedCapture(unsafe);
            movedController(unsafe);
            invalidatedController(unsafe);
        } finally {
            HologramWorkspace.clear();
            WIRE.clear();
            channel.set(null, previous);
        }
    }

    private static void connect(EntityPlayerMP player) throws ReflectiveOperationException {
        net.minecraft.network.NetworkManager connection = new net.minecraft.network.NetworkManager(false);
        for (Field field : net.minecraft.network.NetworkManager.class.getDeclaredFields())
            if (field.getType() == io.netty.channel.Channel.class) {
                field.setAccessible(true);
                field.set(
                    connection,
                    new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter()));
            }
        new net.minecraft.network.NetHandlerPlayServer(null, connection, player);
    }

    private static HologramDynamicDefinitionRegression.DynamicController controller(sun.misc.Unsafe unsafe,
        HologramServiceEditRegression.ReadOnlyWorld world, int x) throws ReflectiveOperationException {
        HologramDynamicDefinitionRegression.DynamicController controller = new HologramDynamicDefinitionRegression.DynamicController();
        HologramDynamicDefinitionRegression.NativeTile tile = (HologramDynamicDefinitionRegression.NativeTile) unsafe
            .allocateInstance(HologramDynamicDefinitionRegression.NativeTile.class);
        tile.setWorldObj(world);
        tile.xCoord = x;
        tile.yCoord = 64;
        controller.setBaseMetaTileEntity(tile);
        world.occupiedX = x;
        world.occupant = tile;
        if (world.occupants == null) world.occupants = new HashMap<>();
        world.occupants.put(x + ",64,0", tile);
        return controller;
    }

    private static EntityPlayerMP controllerPlayer(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        EntityPlayerMP player = player(unsafe);
        player.posY = 64;
        connect(player);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        HologramMaterials.write(player.getHeldItem(), settings);
        return player;
    }

    private static void complete(HologramSession s) {
        int slices = 0;
        while (s.capturePending && slices++ < 2048) HologramBuildPreparation.scan(s, false);
        SimpleAssert.that(
            !s.capturePending && s.capture != null && !s.capture.incomplete,
            "real controller entry reaches a complete native plan: " + s.status);
    }

    private static void movedController(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        EntityPlayerMP player = controllerPlayer(unsafe);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) player.worldObj;
        HologramDynamicDefinitionRegression.DynamicController first = controller(unsafe, world, 0);
        try {
            HologramProjectorWorkflow.open(player, 0, 64, 0, 2);
            HologramSession old = HologramWorkspace.find(player.getUniqueID());
            complete(old);
            SimpleAssert.that(old.context == first, "first controller is read through the production world entry");
            SimpleAssert
                .that(HologramAuthoringWorkflow.blueprintHasUnsavedChanges(old), "old live projection has content");
            HologramDynamicDefinitionRegression.DynamicController moved = controller(unsafe, world, 16);
            HologramProjectorWorkflow.open(player, 16, 64, 0, 2);
            SimpleAssert.that(
                HologramWorkspace.find(player.getUniqueID()) == old && old.discardConfirmationRequired,
                "an existing valid projection still requires confirmation before changing its source");
            HologramAuthoringWorkflow.clearSourceSwitch(old);
            world.occupants.remove("0,64,0");
            HologramProjectorWorkflow.open(player, 16, 64, 0, 2);
            HologramSession next = HologramWorkspace.find(player.getUniqueID());
            SimpleAssert.that(
                next != old && next.context == moved,
                "removed controller cannot trap the new read in an impossible source confirmation");
            complete(next);
            SimpleAssert
                .that(next.capture.positions.get("16,64,0").anchor, "new capture binds the moved controller position");
            SimpleAssert.that(
                next.capture.definition != old.capture.definition,
                "moving and replacing freezes a fresh native graph");
            HologramControllerCapture
                .stop(next, "fixture failed native continuation", new IllegalArgumentException("fixture failure"));
            HologramProjectorWorkflow.open(player, 16, 64, 0, 2);
            HologramSession retry = HologramWorkspace.find(player.getUniqueID());
            SimpleAssert.that(
                retry != next && retry.context == moved,
                "reading the same failed controller starts a fresh session");
            complete(retry);
        } finally {
            HologramWorkspace.clear();
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static void invalidatedController(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        EntityPlayerMP player = controllerPlayer(unsafe);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) player.worldObj;
        controller(unsafe, world, 0);
        try {
            WIRE.clear();
            HologramProjectorWorkflow.open(player, 0, 64, 0, 2);
            HologramSession s = HologramWorkspace.find(player.getUniqueID());
            SimpleAssert.that(s.capturePending, "large native controller starts with a pending capture receipt");
            SimpleAssert.eq(1, WIRE.size(), "world entry sends its actual initial capture progress receipt");
            SimpleAssert.that(
                WIRE.get(0)
                    .getBoolean("progressOnly"),
                "capture publishes pending progress before invalidation");
            NBTTagCompound progress = WIRE.get(0)
                .getCompoundTag("progress");
            SimpleAssert.eq("capture", progress.getString("phase"), "world entry reports the native capture phase");
            SimpleAssert.eq(s.id, progress.getString("session"), "initial progress owns the controller session");
            SimpleAssert
                .eq(s.uiSequence, progress.getLong("uiSequence"), "initial progress owns the world entry command");
            world.occupants.remove("0,64,0");
            HologramBuildPreparation.tickPending(s);
            SimpleAssert.eq(2, WIRE.size(), "invalidation sends exactly one terminal after the initial progress");
            NBTTagCompound terminal = WIRE.get(WIRE.size() - 1);
            SimpleAssert.that(
                terminal.getBoolean("transportError"),
                "invalid target sends a terminal receipt without a valid world snapshot");
            SimpleAssert
                .eq(s.id, terminal.getString("session"), "invalidated terminal owns the old controller session");
            SimpleAssert.eq(
                progress.getLong("uiSequence"),
                terminal.getLong("uiSequence"),
                "invalidated terminal owns the pending command");
            SimpleAssert.that(
                !s.capturePending && !HologramSnapshotPreparation.pending(s),
                "invalidated capture has no stranded continuation or baseline");
        } finally {
            HologramWorkspace.clear();
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static final class RecordingChannel extends cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper {

        private RecordingChannel() {
            super("capture_fixture");
        }

        @Override
        public void sendTo(cpw.mods.fml.common.network.simpleimpl.IMessage message, EntityPlayerMP player) {
            if (message instanceof HologramNetwork.State) {
                NBTTagCompound state = ((HologramNetwork.State) message).tag;
                if (state.getBoolean("transportError")) SimpleAssert.eq(
                    HologramWorkspace.find(player.getUniqueID()).id,
                    state.getString("session"),
                    "capture terminal is sent while its exact session still owns the workspace");
                WIRE.add(state);
            }
        }
    }

    private static void replacedCapture(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        EntityPlayerMP player = player(unsafe);
        connect(player);
        HologramSession previous = session(player), replacement = session(player);
        previous.uiSequence = 73;
        previous.blueprintClientTaskToken = 901;
        replacement.uiSequence = 74;
        replacement.blueprintClientTaskToken = 902;
        previous.captureWork = new HologramControllerCapture.FinishWork(new HashMap<>());
        previous.capturePending = previous.capture.pending = true;
        HologramExtensionSnapshots.CaptureTask extension = new HologramExtensionSnapshots.CaptureTask(previous.capture);
        java.util.concurrent.FutureTask<com.miaokatze.gtmb.api.BlueprintSnapshots.Snapshot> future = new java.util.concurrent.FutureTask<>(
            () -> null);
        Field result = HologramExtensionSnapshots.CaptureTask.class.getDeclaredField("result");
        result.setAccessible(true);
        result.set(extension, future);
        previous.capture.extensionTask = extension;
        replacement.captureWork = new HologramControllerCapture.FinishWork(new HashMap<>());
        HologramControllerCapture.FinishWork replacementWork = replacement.captureWork;
        replacement.capturePending = replacement.capture.pending = true;
        replacement.capture.extensionTask = new HologramExtensionSnapshots.CaptureTask(replacement.capture);
        java.util.concurrent.FutureTask<com.miaokatze.gtmb.api.BlueprintSnapshots.Snapshot> replacementFuture = new java.util.concurrent.FutureTask<>(
            () -> null);
        result.set(replacement.capture.extensionTask, replacementFuture);
        HologramWorkspace.activate(player.getUniqueID(), previous);
        try {
            WIRE.clear();
            HologramWorkspace.activate(player.getUniqueID(), replacement);
            SimpleAssert.eq(1, WIRE.size(), "replacing a pending capture sends exactly one old terminal receipt");
            NBTTagCompound terminal = WIRE.get(0);
            SimpleAssert
                .that(terminal.getBoolean("transportError"), "replacement retires the old client task explicitly");
            SimpleAssert.eq(previous.id, terminal.getString("session"), "replacement terminal owns the old session");
            SimpleAssert.eq(73L, terminal.getLong("uiSequence"), "replacement terminal owns the old command");
            SimpleAssert.eq(901L, terminal.getLong("clientTaskToken"), "replacement terminal owns the old task token");
            SimpleAssert.that(
                !previous.capturePending && previous.captureWork == null && future.isCancelled(),
                "replacing a controller session retires the old native task and its queued extension work");
            SimpleAssert.that(
                replacement.capturePending && replacement.captureWork == replacementWork
                    && !replacementFuture.isCancelled(),
                "retirement never cancels the replacement controller's task");
            HologramControllerCapture.stop(previous, "late old capture stop", null);
            SimpleAssert.eq(1, WIRE.size(), "a late old stop cannot send another terminal for the new owner");
            SimpleAssert.that(
                replacement.capturePending && replacement.captureWork == replacementWork
                    && !replacementFuture.isCancelled(),
                "a late old stop cannot cancel the new continuation or extension future");
            HologramSnapshots.sendProgress(replacement);
            SimpleAssert.eq(2, WIRE.size(), "the new capture can publish progress after the old terminal");
            NBTTagCompound progress = WIRE.get(1)
                .getCompoundTag("progress");
            SimpleAssert.eq("capture", progress.getString("phase"), "replacement progress reports native capture");
            SimpleAssert.eq(replacement.id, progress.getString("session"), "replacement progress owns the new session");
            SimpleAssert.eq(74L, progress.getLong("uiSequence"), "replacement progress owns the new command");
        } finally {
            HologramSession.cancelCaptureRetry(replacement);
            HologramWorkspace.clear();
            player.playerNetServerHandler.netManager.channel()
                .close();
        }
    }

    private static final class CountingCells extends ArrayList<HologramCapture.Cell> {

        private static final long serialVersionUID = 1L;
        int reads;

        CountingCells(List<HologramCapture.Cell> cells) {
            super(cells);
        }

        @Override
        public HologramCapture.Cell get(int index) {
            reads++;
            return super.get(index);
        }
    }
}
