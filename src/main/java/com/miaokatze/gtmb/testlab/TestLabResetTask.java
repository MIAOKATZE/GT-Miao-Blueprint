package com.miaokatze.gtmb.testlab;

import java.io.IOException;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Both clear and rebuild consume one cell per scheduler operation, never a whole synchronous facility scan. */
final class TestLabResetTask implements TestLabTasks.Task {

    private final TestLabRuntime runtime;
    private final boolean refresh;
    private NBTTagList queued;
    private int undo, zone, cell, phase;

    TestLabResetTask(TestLabRuntime runtime, boolean refresh) {
        this.runtime = runtime;
        this.refresh = refresh;
    }

    public int step() throws Exception {
        if (phase == 0) {
            runtime.useZone(runtime.config().zones.get(2));
            queued = runtime.backend()
                .call(
                    runtime.backend()
                        .action("list", ""))
                .getTagList("entries", 10);
            if (queued.tagCount() > 64) throw new IOException("Unexpected actor queue length");
            phase = 1;
        } else if (phase == 1) {
            if (undo < queued.tagCount()) {
                NBTTagCompound entry = queued.getCompoundTagAt(undo++);
                runtime.useContainingZone(entry.getInteger("x"), entry.getInteger("y"), entry.getInteger("z"));
                runtime.backend()
                    .call(
                        runtime.backend()
                            .action("undo", entry.getString("id")));
                runtime.evidence()
                    .count("restoredActorCacheEntries", 1);
            } else phase = 2;
        } else if (phase == 2) {
            if (zone == runtime.config().zones.size()) {
                runtime.evidence()
                    .check(
                        "owned_facilities_reset",
                        "PASS",
                        "Only five manifest-owned 32x16x32 facilities cleared; no DIM or region files deleted");
                if (!refresh) return TestLabTasks.DONE;
                zone = cell = 0;
                phase = 3;
                return TestLabTasks.CONTINUE;
            }
            TestLabZone target = runtime.config().zones.get(zone);
            if (cell == 0) runtime.useZone(target);
            int[] at = target.cell(cell++);
            runtime.requireOwned(at[0], at[1], at[2], 1, 1, 1);
            TestLabFixtureWorld.clear(runtime.world(), at[0], at[1], at[2]);
            runtime.evidence()
                .count("clearedCells", 1);
            if (cell == target.cells()) {
                TestLabFixtureWorld.finish(runtime.world(), target);
                cell = 0;
                zone++;
            }
        } else {
            if (zone == runtime.config().zones.size()) {
                runtime.evidence()
                    .check(
                        "owned_facilities_refreshed",
                        "PASS",
                        "Five distant small fixture floors and markers rebuilt under the same tick budget");
                return TestLabTasks.DONE;
            }
            TestLabZone target = runtime.config().zones.get(zone);
            if (cell == 0) runtime.useZone(target);
            if (cell < 256) runtime.world()
                .setBlock(target.x + 8 + cell % 16, target.y, target.z + 8 + cell / 16, Blocks.stone, 0, 2);
            else runtime.world()
                .setBlock(target.x + 8, target.y + 1, target.z + 8, Blocks.wool, zone, 2);
            runtime.evidence()
                .count("fixtureWrites", 1);
            if (++cell == 257) {
                cell = 0;
                zone++;
            }
        }
        return TestLabTasks.CONTINUE;
    }
}
