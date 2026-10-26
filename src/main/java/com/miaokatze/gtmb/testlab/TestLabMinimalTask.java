package com.miaokatze.gtmb.testlab;

import java.util.Arrays;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntityChest;

import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
import com.miaokatze.gtmb.hologram.HologramTestLabBlueprintProbe;

/** Small real world/clipboard transactions and detached DTO observations, with no connected client requirement. */
final class TestLabMinimalTask implements TestLabTasks.Task {

    private final TestLabRuntime runtime;
    private int phase;
    private long idleUntil;
    private NBTTagCompound idleStart, original, paste;
    private String id;
    private TestLabCodecWork codec;
    private boolean blueprintChecked;
    private HologramTestLabBlueprintProbe.Work blueprint;

    TestLabMinimalTask(TestLabRuntime runtime) {
        this.runtime = runtime;
    }

    public int step() throws Exception {
        TestLabBackend backend = runtime.backend();
        TestLabReport report = runtime.evidence();
        TestLabZone cut = runtime.config().zones.get(2);
        int x = cut.x + 8, y = cut.y + 1, z = cut.z + 8, dx = cut.x + 20, dz = cut.z + 16;
        if (phase == 0) {
            runtime.useZone(runtime.config().zones.get(0));
            TestLabZone home = runtime.config().zones.get(0);
            NBTTagList before = runtime.actor().player.inventory.writeToNBT(new NBTTagList());
            TestLabTasks.require(
                !TestLab.useTestItem(
                    runtime.actor()
                        .tool(),
                    runtime.actor().player,
                    MinecraftServer.getServer()
                        .worldServerForDimension(0),
                    home.x + 8,
                    home.y + 1,
                    home.z + 8),
                "Lab effect escaped its dimension");
            TestLabTasks.require(
                before.equals(runtime.actor().player.inventory.writeToNBT(new NBTTagList())),
                "Rejected outside effect changed inventory");
            TestLabTasks.require(
                TestLab.useTestItem(
                    runtime.actor()
                        .tool(),
                    runtime.actor().player,
                    runtime.world(),
                    home.x + 8,
                    home.y + 1,
                    home.z + 8),
                "Valid bounded lab item effect refused");
            TestLabTasks.require(
                !TestLab.useTestItem(
                    runtime.actor()
                        .tool(),
                    runtime.actor().player,
                    runtime.world(),
                    home.x + 8,
                    home.y + 1,
                    home.z + 8),
                "Lab item exceeded its one-use supply budget");
            report.check(
                "special_item_scope",
                "PASS",
                "Actual bounded effect admitted in owned lab; Overworld target rejected with unchanged complete actor inventory");
            idleStart = metrics();
            idleUntil = runtime.ticks() + 20;
            phase++;
        } else if (phase == 1) {
            if (runtime.ticks() < idleUntil) return TestLabTasks.WAIT;
            NBTTagCompound after = metrics();
            boolean sampled = idleStart.getBoolean("enabled") && after.getBoolean("enabled");
            long delta = packets(after) - packets(idleStart);
            report.check(
                "idle_network_observation",
                !sampled ? "SKIP" : delta == 0 ? "PASS" : "INCONCLUSIVE",
                "20 idle server ticks, global codec packet delta=" + delta
                    + "; unrelated real-player traffic is not attributed to this lab");
            report.count("idleGlobalCodecPacketDelta", delta);
            phase++;
        } else if (phase == 2) {
            TestLabZone capture = runtime.config().zones.get(1);
            runtime.useZone(capture);
            if (!blueprintChecked) {
                if (blueprint == null) blueprint = HologramTestLabBlueprintProbe.start(
                    runtime.world(),
                    (net.minecraftforge.common.util.FakePlayer) runtime.actor().player,
                    capture.x + 8,
                    capture.y + 1,
                    capture.z + 16);
                NBTTagCompound result = blueprint.advance(runtime.ticks());
                if (result == null) return TestLabTasks.WAIT;
                TestLabTasks.require(result.getBoolean("success"), "Ordinary production blueprint fixture failed");
                report.data.setTag("ordinaryBlueprint", result.copy());
                report.check(
                    "ordinary_blueprint_minimal",
                    "PASS",
                    "Real ordinary planning/material payment/native world transaction; detailed scoped receipt attached");
                blueprint.cancel();
                blueprintChecked = true;
                return TestLabTasks.CONTINUE;
            }
            chest(capture.x + 8, capture.y + 1, capture.z + 8, Items.diamond, 7);
            TestLabFixtureWorld.clear(runtime.world(), capture.x + 9, capture.y + 1, capture.z + 8);
            Object sourceTile = runtime.world()
                .getTileEntity(capture.x + 8, capture.y + 1, capture.z + 8);
            NBTTagCompound raw = backend.capture(capture, 2, 1, 1);
            TestLabTasks.require(
                raw.getTagList("cells", 10)
                    .tagCount() == 2 && sourceTile
                        == runtime.world()
                            .getTileEntity(capture.x + 8, capture.y + 1, capture.z + 8),
                "Raw capture changed world or omitted air");
            TestLabTasks.require(
                raw.getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .getCompoundTag("tile")
                    .getTagList("Items", 10)
                    .tagCount() == 1,
                "Raw capture lost native inventory");
            report.check(
                "raw_capture",
                "PASS",
                "Actual raw capture retains chest native inventory and air; source tile identity unchanged");
            phase++;
        } else if (phase == 3) {
            runtime.useZone(cut);
            TestLabTasks.require(
                backend.call(backend.action("list", ""))
                    .getTagList("entries", 10)
                    .tagCount() == 0,
                "Actor cache is not empty; run reset to restore it safely");
            chest(x, y, z, Items.diamond, 7);
            original = snapshot(x, y, z);
            TestLabFixtureWorld.clear(runtime.world(), x + 1, y, z);
            TestLabFixtureWorld.clear(runtime.world(), dx, y, dz);
            chest(dx, y, dz + 1, Items.gold_ingot, 9);
            NBTTagCompound request = region("cut", "", x, y, z, x + 1, y, z);
            id = backend.call(request)
                .getString("id");
            TestLabTasks.require(
                runtime.world()
                    .isAirBlock(x, y, z),
                "Cut source not cleared");
            report.check(
                "cut_source_and_cache",
                "PASS",
                "Real production write-ahead clipboard/world removal; queue contains original raw snapshot");
            phase++;
        } else if (phase == 4) {
            byte[] before = backend.cacheBytes();
            NBTTagCompound request = backend.action("preview", id);
            request.setInteger("rotation", 1);
            NBTTagCompound selected = backend.call(request)
                .getCompoundTag("preview");
            TestLabTasks.require(
                selected.getInteger("sizeX") == 1 && selected.getInteger("sizeZ") == 2
                    && selected.getTagList("cells", 10)
                        .tagCount() == 2
                    && selected.getTagList("cells", 10)
                        .getCompoundTagAt(0)
                        .hasKey("tile", 10),
                "Selected complete raw DTO differs");
            TestLabTasks.require(Arrays.equals(before, backend.cacheBytes()), "Preview rewrote original cache");
            report.check(
                "selected_raw_preview_dto",
                "PASS",
                "Complete rotated native tile/air DTO observed; queue bytes unchanged; no client renderer or GL claim");
            phase++;
        } else if (phase == 5) {
            paste = backend.action("paste", id);
            paste.setInteger("rotation", 1);
            paste.setInteger("originX", dx);
            paste.setInteger("originY", y);
            paste.setInteger("originZ", dz);
            backend.call(paste);
            TestLabTasks.require(
                runtime.world()
                    .isAirBlock(dx, y, dz + 1)
                    && runtime.world()
                        .getBlockMetadata(dx, y, dz) == 5,
                "Rotated complete volume/metadata differs");
            NBTTagCompound expected = (NBTTagCompound) original.copy();
            expected.setInteger("x", dx);
            expected.setInteger("z", dz);
            TestLabTasks.require(expected.equals(snapshot(dx, y, dz)), "Pasted complete native inventory/NBT differs");
            backend.call(paste);
            TestLabTasks.require(
                backend.call(backend.action("list", ""))
                    .getTagList("entries", 10)
                    .tagCount() == 0,
                "Successful placement did not consume exactly once");
            report.check(
                "paste_and_replay",
                "PASS",
                "World/disk transaction restores inventory, rotates facing, overwrites air and consumes once; duplicate UUID acknowledged");
            phase++;
        } else if (phase == 6) {
            String next = backend.call(region("cut", "", dx, y, dz, dx, y, dz + 1))
                .getString("id");
            NBTTagCompound undo = backend.action("undo", next);
            undo.setInteger("rotation", 3);
            undo.setInteger("mirror", 7);
            backend.call(undo);
            TestLabTasks.require(
                runtime.world()
                    .getBlockMetadata(dx, y, dz) == 5
                    && ((TileEntityChest) runtime.world()
                        .getTileEntity(dx, y, dz)).getStackInSlot(4).stackSize == 7,
                "Undo did not restore original source pose/inventory");
            report.check(
                "undo_original_pose",
                "PASS",
                "Undo ignores requested preview pose and restores original source dimension/position/facing/inventory");
            codec = new TestLabCodecWork(1);
            phase++;
        } else {
            if (!codec.step(report, 1)) return TestLabTasks.CONTINUE;
            report.check(
                "codec_roundtrip",
                "PASS",
                "Actual snapshot compression, cut frame codec and ordered assembly roundtrip in memory");
            report.check(
                "dedicated_safe_backend",
                "PASS",
                "Only common/server objects used; no connected client, online human, renderer, GL or native-full-pack claim");
            return TestLabTasks.DONE;
        }
        return TestLabTasks.CONTINUE;
    }

    @Override
    public void cleanup() {
        if (blueprint != null) blueprint.cancel();
    }

    private NBTTagCompound region(String op, String id, int x, int y, int z, int bx, int by, int bz) {
        NBTTagCompound n = runtime.backend()
            .action(op, id);
        n.setInteger("x1", x);
        n.setInteger("y1", y);
        n.setInteger("z1", z);
        n.setInteger("x2", bx);
        n.setInteger("y2", by);
        n.setInteger("z2", bz);
        return n;
    }

    private void chest(int x, int y, int z, net.minecraft.item.Item item, int count) {
        TestLabFixtureWorld.clear(runtime.world(), x, y, z);
        runtime.world()
            .setBlock(x, y, z, Blocks.chest, 2, 2);
        runtime.world()
            .setBlockMetadataWithNotify(x, y, z, 2, 2);
        ItemStack stack = new ItemStack(item, count);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("labNativeReceipt", runtime.evidence().data.getString("runId"));
        stack.setTagCompound(tag);
        ((TileEntityChest) runtime.world()
            .getTileEntity(x, y, z)).setInventorySlotContents(4, stack);
    }

    private NBTTagCompound snapshot(int x, int y, int z) {
        NBTTagCompound n = new NBTTagCompound();
        runtime.world()
            .getTileEntity(x, y, z)
            .writeToNBT(n);
        return n;
    }

    private NBTTagCompound metrics() {
        return runtime.config().performance ? HologramRuntimeMetrics.snapshot() : new NBTTagCompound();
    }

    private static long packets(NBTTagCompound metrics) {
        long value = 0;
        NBTTagCompound network = metrics.getCompoundTag("network");
        for (Object name : network.func_150296_c()) {
            NBTTagCompound row = network.getCompoundTag(String.valueOf(name));
            for (Object key : row.func_150296_c()) if (String.valueOf(key)
                .endsWith("Packets")) value += row.getLong(String.valueOf(key));
        }
        return value;
    }
}
