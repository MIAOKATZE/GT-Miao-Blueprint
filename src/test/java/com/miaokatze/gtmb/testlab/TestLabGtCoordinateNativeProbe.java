package com.miaokatze.gtmb.testlab;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.hologram.HologramGtCoordinateRegression;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.tileentities.machines.multi.purification.MTEPurificationUnitBase;

/** Explicit test-only FML observer. No production routing, default opt-in or machine formation is changed. */
@Mod(
    modid = "gtmb_gt_coordinate_native_probe",
    name = "GTMB bounded GT relationship observer",
    version = "1",
    dependencies = "required-after:gtmb",
    acceptableRemoteVersions = "*")
public final class TestLabGtCoordinateNativeProbe {

    private MinecraftServer server;
    private Path root;
    private boolean active, launched;
    private long started;
    private int ticks;
    private final NBTTagCompound receipt = new NBTTagCompound();

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (!Boolean.getBoolean("gtmb.gtCoordinate.nativeProbe")) return;
        try {
            server = MinecraftServer.getServer();
            require(server != null && server.isDedicatedServer(), "dedicated server required");
            root = Paths.get(System.getProperty("gtmb.gtCoordinate.nativeRoot", ""))
                .toAbsolutePath()
                .normalize();
            require(
                root.getParent() != null && "gtmb-gt-coordinate-native".equals(
                    root.getParent()
                        .getFileName()
                        .toString()),
                "isolated fixture namespace required");
            require(
                Paths.get("")
                    .toAbsolutePath()
                    .normalize()
                    .equals(root.resolve("server")),
                "isolated server cwd");
            require(
                Files.isDirectory(root.resolve("receipts")) && !Files.isSymbolicLink(root),
                "fresh receipt directory");
            require(
                TestLab.isEnabled() && TestLab.status()
                    .getBoolean("configEnabled"),
                "local config enables owned lab");
            require(
                !TestLab.status()
                    .getBoolean("worldLoaded"),
                "fresh lab has no world before explicit operation");
            HologramGtCoordinateRegression.runNativeFactory();
            receipt.setBoolean("nativeFactorySchemaPassed", true);
            receipt.setString(
                "scope",
                "Registered native factories and natural tile ticks; unformed, disabled controllers; no recipe/GUI/Netty claim");
            started = System.nanoTime();
            active = true;
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            System.out.println("[GTMB-GT-COORDINATE-NATIVE] FACTORY PASS after genuine FML registration");
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (!active || event.phase != TickEvent.Phase.END) return;
        try {
            require(++ticks <= 5000 && System.nanoTime() - started < 240000000000L, "240 second native watchdog");
            require(
                server.getConfigurationManager().playerEntityList.isEmpty(),
                "headless fixture has no online players");
            if (!launched) {
                require(
                    server.getCommandManager()
                        .executeCommand(server, "gtmbtestlab run minimal") > 0,
                    "actual minimal command accepted");
                Field holder = TestLab.class.getDeclaredField("runtime");
                holder.setAccessible(true);
                TestLabRuntime runtime = (TestLabRuntime) holder.get(null);
                Field task = TestLabRuntime.class.getDeclaredField("task");
                task.setAccessible(true);
                TestLabTasks.Task original = (TestLabTasks.Task) task.get(runtime);
                require(original instanceof TestLabMinimalTask, "unchanged production minimal task precedes extension");
                task.set(runtime, new Relationships(runtime, original));
                launched = true;
                return;
            }
            if (TestLab.status()
                .getBoolean("active")) return;
            NBTTagCompound report = TestLab.report();
            receipt.setTag("labReport", report);
            require(
                "PASS".equals(report.getString("state")),
                "bounded extended minimal run passed: " + report.getString("detail"));
            require(
                report.getTagList("gtCoordinateCases", 10)
                    .tagCount() == 6,
                "six native relationship cases completed");
            require(
                TestLab.status()
                    .getInteger("ownedChunks") == 0,
                "all owned tickets released");
            finish(true);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void fail(Throwable failure) {
        active = false;
        failure.printStackTrace();
        receipt.setString("failure", failure.toString());
        try {
            if (server != null) server.getCommandManager()
                .executeCommand(server, "gtmbtestlab stop");
            finish(false);
        } catch (Exception writeFailure) {
            writeFailure.printStackTrace();
            if (server != null) server.initiateShutdown();
        }
    }

    private void finish(boolean passed) throws Exception {
        active = false;
        receipt.setBoolean("passed", passed);
        receipt.setInteger("serverTicks", ticks);
        receipt.setTag("finalLabStatus", TestLab.status());
        if (root != null) {
            Path target = root.resolve("receipts/native-relationships.nbt");
            require(!Files.exists(target), "preserve previous native receipt");
            Path temporary = Files.createTempFile(target.getParent(), "native-relationships-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                CompressedStreamTools.writeCompressed(receipt, output);
            }
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            Files.write(
                root.resolve("receipts/native-result.txt"),
                ((passed ? "PASS" : "FAIL") + " factory=" + receipt.getBoolean("nativeFactorySchemaPassed") + "\n")
                    .getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        System.out.println("[GTMB-GT-COORDINATE-NATIVE] FINAL " + (passed ? "PASS" : "FAIL"));
        if (server != null) server.initiateShutdown();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    /** Wrap, rather than replace, every existing minimal check. Native fixtures then share its owned lease/budgets. */
    private static final class Relationships implements TestLabTasks.Task {

        private final TestLabRuntime runtime;
        private final TestLabTasks.Task original;
        private boolean originalDone;
        private int test, phase;
        private long waitStarted;
        private String clipboard = "";
        private NBTTagCompound observation;
        private BaseMetaTileEntity[] source, target;
        private int sx, sy, sz, tx, tz;
        private boolean dirty;

        Relationships(TestLabRuntime runtime, TestLabTasks.Task original) {
            this.runtime = runtime;
            this.original = original;
        }

        public int step() throws Exception {
            if (!originalDone) {
                int result = original.step();
                if (result != TestLabTasks.DONE) return result;
                originalDone = true;
                runtime.evidence().data.setBoolean("testOnlyNativeRelationshipExtension", true);
                runtime.evidence().data.setTag("gtCoordinateCases", new NBTTagList());
                return TestLabTasks.CONTINUE;
            }
            if (test == 6) return TestLabTasks.DONE;
            TestLabZone zone = runtime.config().zones.get(2);
            runtime.useZone(zone);
            sx = zone.x + 4;
            sy = zone.y + 3;
            sz = zone.z + 4;
            tx = zone.x + 20;
            tz = zone.z + 20;
            runtime.requireOwned(sx, sy, sz, 3, 1, 3);
            runtime.requireOwned(tx, sy, tz, 3, 1, 3);
            boolean pcb = test >= 2 && test <= 3 || test == 5;
            boolean gap = test >= 4;
            int rotation = test == 1 || test == 3 || gap ? 1 : 0;
            if (phase == 0) {
                clearCells();
                dirty = true;
                source = pcb
                    ? new BaseMetaTileEntity[] { machine(0, 730, "MTEPCBBioChamber"), machine(1, 356, "MTEPCBFactory"),
                        machine(2, 731, "MTEPCBCoolingTower") }
                    : new BaseMetaTileEntity[] { machine(0, 9403, "MTEPurificationUnitClarifier"),
                        machine(1, 9402, "MTEPurificationPlant") };
                nativeLink(source[1], source[0]);
                if (pcb) nativeLink(source[1], source[2]);
                observation = new NBTTagCompound();
                observation.setString(
                    "case",
                    (pcb ? "pcb" : "purification") + (gap ? "-mask-gap-external" : "-complete") + "-Y" + rotation * 90);
                observation.setBoolean("formedStructureRequired", false);
                observation.setTag("sourceImmediatelyLinked", snapshots(source));
                waitStarted = runtime.ticks();
                phase++;
                return TestLabTasks.WAIT;
            }
            if (phase == 1) {
                if (!aged(source) || runtime.ticks() - waitStarted < 105) return TestLabTasks.WAIT;
                linked(source, pcb, source[1]);
                observation.setTag("sourceAfter105Ticks", snapshots(source));
                NBTTagCompound cut = runtime.backend()
                    .action("cut", "");
                if (gap) {
                    HologramAreaSelection selection = new HologramAreaSelection();
                    selection.point(1, sx, sy, sz, "replace");
                    selection.point(2, sx, sy, sz, "replace");
                    selection.beginNext("add");
                    selection.point(1, sx + 2, sy, sz, "add");
                    selection.point(2, sx + 2, sy, sz, "add");
                    cut.setTag("areaSelection", selection.snapshot());
                } else {
                    cut.setInteger("x1", sx);
                    cut.setInteger("y1", sy);
                    cut.setInteger("z1", sz);
                    cut.setInteger("x2", sx + 2);
                    cut.setInteger("y2", sy);
                    cut.setInteger("z2", sz);
                }
                NBTTagCompound reply = runtime.backend()
                    .call(cut);
                clipboard = reply.getString("id");
                observation.setTag(
                    "capturedEntry",
                    reply.getCompoundTag("preview")
                        .copy());
                NBTTagCompound paste = runtime.backend()
                    .action("paste", clipboard);
                paste.setInteger("originX", tx);
                paste.setInteger("originY", sy);
                paste.setInteger("originZ", tz);
                paste.setInteger("rotation", rotation);
                runtime.backend()
                    .call(paste);
                clipboard = "";
                target = new BaseMetaTileEntity[source.length];
                for (int i = 0; i < source.length; i++) {
                    if (gap && i == 1) target[i] = source[i];
                    else target[i] = base(rotation == 0 ? tx + i : tx, sy, rotation == 0 ? tz : tz + i);
                }
                observation.setTag("targetBeforeNaturalRelink", snapshots(target));
                boolean initiallyAssociated = associated(target, pcb, target[1]);
                observation.setBoolean("targetReferencePresentBeforeNaturalTicks", initiallyAssociated);
                require(
                    !initiallyAssociated,
                    "restored target must establish its actual association through later native ticks");
                waitStarted = runtime.ticks();
                phase++;
                return TestLabTasks.WAIT;
            }
            if (!aged(target) || runtime.ticks() - waitStarted < 105) return TestLabTasks.WAIT;
            linked(target, pcb, target[1]);
            observation.setTag("targetAfter105Ticks", snapshots(target));
            NBTTagCompound controller = snapshot(target[1]);
            if (pcb) {
                coords(controller.getCompoundTag("mBioChamber"), target[0]);
                coords(controller.getCompoundTag("mCoolingTower"), target[2]);
                int[] expected = { target[1].xCoord, target[1].yCoord, target[1].zCoord };
                require(
                    java.util.Arrays.equals(expected, snapshot(target[0]).getIntArray("controllers")),
                    "bio native saved link coordinates");
                require(
                    java.util.Arrays.equals(expected, snapshot(target[2]).getIntArray("controllers")),
                    "cooling native saved link coordinates");
            } else coords(snapshot(target[0]).getCompoundTag("controller"), target[1]);
            observation.setLong("naturalTicksAfterPaste", runtime.ticks() - waitStarted);
            observation.setBoolean("actualReferenceIdentityPassed", true);
            runtime.evidence().data.getTagList("gtCoordinateCases", 10)
                .appendTag(observation.copy());
            runtime.evidence()
                .check(
                    "gt_native_relationship_" + test,
                    "PASS",
                    observation.getString("case")
                        + "; natural tile timer >=105; actual native association and persisted xyz verified");
            clearCells();
            dirty = false;
            phase = 0;
            test++;
            return TestLabTasks.CONTINUE;
        }

        private BaseMetaTileEntity machine(int dx, int id, String exactSimpleName) throws Exception {
            IMetaTileEntity prototype = GregTechAPI.METATILEENTITIES[id];
            require(
                prototype != null && prototype.getClass()
                    .getSimpleName()
                    .equals(exactSimpleName),
                "exact registered .205 machine " + id);
            runtime.requireOwned(sx + dx, sy, sz, 1, 1, 1);
            require(
                runtime.world()
                    .isAirBlock(sx + dx, sy, sz),
                "fresh native fixture air");
            require(
                runtime.world()
                    .setBlock(sx + dx, sy, sz, GregTechAPI.sBlockMachines, prototype.getTileEntityBaseType(), 3),
                "native GT block placement");
            BaseMetaTileEntity base = base(sx + dx, sy, sz);
            base.setInitialValuesAsNBT(null, (short) id);
            base.setFrontFacing(ForgeDirection.NORTH);
            base.disableWorking();
            require(
                base.getMetaTileEntity()
                    .getClass() == prototype.getClass(),
                "native factory created exact registered MTE");
            return base;
        }

        private void nativeLink(BaseMetaTileEntity controller, BaseMetaTileEntity unit) {
            ItemStack previous = runtime.actor().player.getHeldItem();
            try {
                runtime.actor().player.inventory.setInventorySlotContents(
                    runtime.actor().player.inventory.currentItem,
                    ItemList.Tool_DataStick.get(1));
                controller.getMetaTileEntity()
                    .onLeftclick(controller, runtime.actor().player);
                require(
                    unit.getMetaTileEntity()
                        .onRightclick(
                            unit,
                            runtime.actor().player,
                            net.minecraftforge.common.util.ForgeDirection.NORTH,
                            0.5F,
                            0.5F,
                            0.5F),
                    "native data-stick link accepted");
            } finally {
                runtime.actor().player.inventory
                    .setInventorySlotContents(runtime.actor().player.inventory.currentItem, previous);
            }
        }

        private static void linked(BaseMetaTileEntity[] tiles, boolean pcb, BaseMetaTileEntity controller)
            throws Exception {
            require(associated(tiles, pcb, controller), "native target relationship reference identity");
        }

        private static boolean associated(BaseMetaTileEntity[] tiles, boolean pcb, BaseMetaTileEntity controller)
            throws Exception {
            if (pcb) {
                return field(controller.getMetaTileEntity(), "mBioChamber") == tiles[0].getMetaTileEntity()
                    && field(controller.getMetaTileEntity(), "mCoolingTower") == tiles[2].getMetaTileEntity();
            }
            return ((MTEPurificationUnitBase<?>) tiles[0].getMetaTileEntity()).getController()
                == controller.getMetaTileEntity();
        }

        private static Object field(Object object, String name) throws Exception {
            Field field = object.getClass()
                .getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        }

        private static boolean aged(BaseMetaTileEntity[] tiles) {
            for (BaseMetaTileEntity tile : tiles) if (tile.getTimer() < 105) return false;
            return true;
        }

        private BaseMetaTileEntity base(int x, int y, int z) {
            TileEntity tile = runtime.world()
                .getTileEntity(x, y, z);
            require(tile instanceof BaseMetaTileEntity, "attached native GT base at " + x + "," + y + "," + z);
            return (BaseMetaTileEntity) tile;
        }

        private static NBTTagCompound snapshot(BaseMetaTileEntity tile) {
            NBTTagCompound nbt = new NBTTagCompound();
            tile.writeToNBT(nbt);
            return nbt;
        }

        private static NBTTagList snapshots(BaseMetaTileEntity[] tiles) {
            NBTTagList result = new NBTTagList();
            for (BaseMetaTileEntity tile : tiles) {
                NBTTagCompound nbt = snapshot(tile);
                nbt.setLong("probeObservedNativeTimer", tile.getTimer());
                result.appendTag(nbt);
            }
            return result;
        }

        private static void coords(NBTTagCompound coordinates, BaseMetaTileEntity expected) {
            require(
                coordinates.hasKey("x", 3) && coordinates.hasKey("y", 3)
                    && coordinates.hasKey("z", 3)
                    && coordinates.getInteger("x") == expected.xCoord
                    && coordinates.getInteger("y") == expected.yCoord
                    && coordinates.getInteger("z") == expected.zCoord,
                "native persisted relationship xyz");
        }

        private void clearCells() throws Exception {
            for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) {
                runtime.requireOwned(sx + dx, sy, sz + dz, 1, 1, 1);
                runtime.requireOwned(tx + dx, sy, tz + dz, 1, 1, 1);
                TestLabFixtureWorld.clear(runtime.world(), sx + dx, sy, sz + dz);
                TestLabFixtureWorld.clear(runtime.world(), tx + dx, sy, tz + dz);
            }
        }

        public void cleanup() throws Exception {
            original.cleanup();
            if (!clipboard.isEmpty()) {
                runtime.backend()
                    .call(
                        runtime.backend()
                            .action("undo", clipboard));
                clipboard = "";
            }
            if (dirty) clearCells();
        }
    }
}
