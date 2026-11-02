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
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Native continuation work counts and candidate/plan tail parity on the owning server thread. */
public final class HologramControllerCaptureRegression {

    private HologramControllerCaptureRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramControllerCaptureRegression.class, args)) return;
        run();
        System.out.println("Hologram controller capture continuation regressions passed");
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
            changedSignal(unsafe);
        } catch (ReflectiveOperationException | InterruptedException failure) {
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
        HologramControllerCapture.collect(s);
        SimpleAssert.eq(3, previous.reads, "new capture reads only pinned and selected cells from the old plan");
        SimpleAssert.that(s.capture.pending && s.capturePending, "large native capture yields before finalization");
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

    private static void changedSignal(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        EntityPlayerMP player = player(unsafe);
        HologramSession s = session(player);
        HologramControllerCapture.FinishWork work = new HologramControllerCapture.FinishWork(new HashMap<>());
        s.captureWork = work;
        s.capturePending = true;
        work.beginFinish();
        SimpleAssert.that(!work.step(s, 7, Long.MAX_VALUE), "signal fixture yields during candidate resolution");
        player.getHeldItem()
            .getTagCompound()
            .setInteger("customStructureSignal", 9);
        HologramControllerCapture.collect(s, false);
        SimpleAssert.that(s.capture.incomplete, "a real custom signal change rejects the pending plan tail");
        SimpleAssert.eq("SOURCE_TRIGGER_CHANGED", s.capture.failure, "full trigger NBT guard survives finalization");
        SimpleAssert.that(!s.capture.sourceCurrent(), "stale trigger rejection also invalidates the retained source");
        SimpleAssert.that(s.planStatus.isEmpty(), "no classified plan is published for a stale capture");
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
