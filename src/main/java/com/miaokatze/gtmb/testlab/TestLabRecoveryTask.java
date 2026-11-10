package com.miaokatze.gtmb.testlab;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramConstructionRecoverySmoke;

/** Owned two-cell recovery fault test; stage/resume deliberately supports a process restart between commands. */
final class TestLabRecoveryTask implements TestLabTasks.Task {

    private final TestLabRuntime runtime;
    private final boolean resume;

    TestLabRecoveryTask(TestLabRuntime runtime, boolean resume) {
        this.runtime = runtime;
        this.resume = resume;
    }

    public int step() throws Exception {
        TestLabZone zone = runtime.config().zones.get(0);
        runtime.useZone(zone);
        int x = zone.x + 12, y = zone.y + 1, z = zone.z + 12;
        runtime.requireOwned(x, y, z, 2, 1, 1);
        NBTTagCompound evidence = HologramConstructionRecoverySmoke
            .run(runtime.world(), runtime.actor().player, x, y, z, resume);
        runtime.evidence().data.setTag("constructionRecovery", evidence);
        runtime.evidence()
            .check("construction_recovery_" + (resume ? "resume" : "stage"), "PASS", evidence.toString());
        return TestLabTasks.DONE;
    }
}
