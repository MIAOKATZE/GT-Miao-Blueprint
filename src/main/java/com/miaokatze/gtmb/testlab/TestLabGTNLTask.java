package com.miaokatze.gtmb.testlab;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtmb.hologram.HologramGTNLGtsrProbe;
import com.miaokatze.gtmb.hologram.HologramGTNLNativeProbe;
import com.miaokatze.gtmb.hologram.HologramGTNLWorkflowProbe;

import cpw.mods.fml.common.Loader;

/** Independent native-source and GTSR integration phases share only the owned laboratory lease. */
final class TestLabGTNLTask implements TestLabTasks.Task {

    private final TestLabRuntime runtime;
    private HologramGTNLNativeProbe.Run probe;
    private HologramGTNLGtsrProbe.Run integration;
    private HologramGTNLWorkflowProbe.Run workflow;
    private boolean baseDone, integrationDone, workflowDone;
    private boolean baseSuccess = true, integrationSuccess = true, workflowSuccess = true;

    TestLabGTNLTask(TestLabRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public int step() throws Exception {
        if (!baseDone) return baseStep();
        if (!integrationDone) return integrationStep();
        if (!workflowDone) return workflowStep();
        boolean success = baseSuccess && integrationSuccess && workflowSuccess;
        runtime.evidence()
            .check(
                "gtnl_combined_runtime",
                success ? "PASS" : "FAIL",
                "Independent declarations, GTSR E/P integration and five-machine paid workflow phases completed");
        TestLabTasks.require(success, "GTNL/GTSR independent runtime phase failure; see per-case evidence");
        return TestLabTasks.DONE;
    }

    private int baseStep() throws Exception {
        if (!Loader.isModLoaded("sciencenotleisure")) {
            notApplicable("gtnl", "gtnl_native_source_and_materials", "GTNL_NOT_LOADED");
            baseDone = true;
            return TestLabTasks.WAIT;
        }
        try {
            if (probe == null) {
                TestLabZone zone = zone();
                probe = HologramGTNLNativeProbe.begin(runtime.world(), actor(), zone.x + 16, zone.y + 8, zone.z + 6);
            }
            boolean done = probe.step();
            runtime.evidence().data.setTag("gtnl", probe.result());
            if (!done) return TestLabTasks.WAIT;
            probe.close();
            NBTTagCompound result = probe.result();
            runtime.evidence().data.setTag("gtnl", result);
            cases(result, "gtnl/");
            baseSuccess = result.getBoolean("success");
            runtime.evidence()
                .check(
                    "gtnl_native_source_and_materials",
                    baseSuccess ? "PASS" : "FAIL",
                    "Independent declaration/material cases completed; failedCases="
                        + result.getInteger("failedCases"));
        } catch (Exception | LinkageError failure) {
            baseSuccess = false;
            closeBaseAfterFailure(failure);
            phaseFailure("gtnl", "gtnl_native_source_and_materials", failure);
        }
        baseDone = true;
        return TestLabTasks.WAIT;
    }

    private int integrationStep() throws Exception {
        if (!Loader.isModLoaded("sciencenotleisure") || !Loader.isModLoaded("gtsr")) {
            notApplicable(
                "gtnlGtsr",
                "gtnl_gtsr_native_integration",
                Loader.isModLoaded("sciencenotleisure") ? "GTSR_NOT_LOADED" : "GTNL_NOT_LOADED");
            integrationDone = true;
            return TestLabTasks.WAIT;
        }
        try {
            if (integration == null) {
                TestLabZone zone = zone();
                integration = HologramGTNLGtsrProbe
                    .begin(runtime.world(), actor(), zone.x + 12, zone.y + 4, zone.z + 8);
            }
            boolean done = integration.step();
            runtime.evidence().data.setTag("gtnlGtsr", integration.result());
            if (!done) return TestLabTasks.WAIT;
            integration.close();
            NBTTagCompound result = integration.result();
            runtime.evidence().data.setTag("gtnlGtsr", result);
            cases(result, "gtnl_gtsr/");
            integrationSuccess = result.getBoolean("success");
            runtime.evidence()
                .check(
                    "gtnl_gtsr_native_integration",
                    integrationSuccess ? "PASS" : "FAIL",
                    "Real World/TE structure and payment calls; synthetic work counters, no full recipe claim; failedCases="
                        + result.getInteger("failedCases"));
        } catch (Exception | LinkageError failure) {
            integrationSuccess = false;
            closeIntegrationAfterFailure(failure);
            phaseFailure("gtnlGtsr", "gtnl_gtsr_native_integration", failure);
        }
        integrationDone = true;
        return TestLabTasks.WAIT;
    }

    private TestLabZone zone() throws Exception {
        TestLabZone zone = runtime.config()
            .zone("capture");
        runtime.useZone(zone);
        return zone;
    }

    private int workflowStep() throws Exception {
        if (!Loader.isModLoaded("sciencenotleisure")) {
            notApplicable("gtnlWorkflow", "gtnl_five_machine_paid_workflows", "GTNL_NOT_LOADED");
            workflowDone = true;
            return TestLabTasks.WAIT;
        }
        try {
            if (workflow == null) {
                TestLabZone zone = runtime.config()
                    .zone("gtnl-workflow");
                runtime.useZone(zone);
                java.nio.file.Path artifacts = runtime.ownership().directory.resolve(
                    "workflow-" + TestLab.status()
                        .getString("runId"));
                workflow = HologramGTNLWorkflowProbe.begin(runtime.world(), actor(), zone.x, zone.y, zone.z, artifacts);
            }
            boolean done = workflow.step();
            runtime.evidence().data.setTag("gtnlWorkflow", workflow.result());
            if (!done) return TestLabTasks.WAIT;
            workflow.close();
            NBTTagCompound result = workflow.result();
            runtime.evidence().data.setTag("gtnlWorkflow", result);
            cases(result, "gtnl_workflow/");
            workflowSuccess = result.getBoolean("success");
            runtime.evidence()
                .check(
                    "gtnl_five_machine_paid_workflows",
                    workflowSuccess ? "PASS" : "FAIL",
                    "Owned server-domain projection/edit/artifact reload/materials/paid jobs/native formation; no GUI or recipe claim; failedCases="
                        + result.getInteger("failedCases"));
        } catch (Exception | LinkageError failure) {
            workflowSuccess = false;
            if (workflow != null) {
                try {
                    workflow.close();
                } catch (Exception | LinkageError cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                runtime.evidence().data.setTag("gtnlWorkflow", workflow.result());
                cases(workflow.result(), "gtnl_workflow/");
            }
            phaseFailure("gtnlWorkflow", "gtnl_five_machine_paid_workflows", failure);
        }
        workflowDone = true;
        return TestLabTasks.WAIT;
    }

    private FakePlayer actor() throws Exception {
        TestLabTasks
            .require(runtime.actor().player instanceof FakePlayer, "GTNL suite requires the owned lab FakePlayer");
        return (FakePlayer) runtime.actor().player;
    }

    private void cases(NBTTagCompound result, String prefix) {
        NBTTagList cases = result.getTagList("cases", 10);
        for (int i = 0; i < cases.tagCount(); i++) {
            NBTTagCompound row = cases.getCompoundTagAt(i);
            runtime.evidence()
                .check(
                    prefix + row.getString("name"),
                    row.getString("status"),
                    row.getString("error")
                        .isEmpty() ? "Owned world and actor inventory restored" : row.getString("error"));
        }
    }

    private void notApplicable(String key, String check, String reason) {
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("applicable", false);
        result.setString("state", "NOT_APPLICABLE");
        result.setString("reason", reason);
        runtime.evidence().data.setTag(key, result);
        runtime.evidence()
            .check(check, "NOT_APPLICABLE", reason);
    }

    private void phaseFailure(String key, String check, Throwable failure) {
        NBTTagCompound result = runtime.evidence().data.getCompoundTag(key);
        result.setBoolean("success", false);
        result.setString("phaseFailure", failure.toString());
        runtime.evidence().data.setTag(key, result);
        runtime.evidence()
            .check(check, "FAIL", failure.toString());
    }

    private void closeBaseAfterFailure(Throwable failure) {
        if (probe == null) return;
        try {
            probe.close();
        } catch (Exception | LinkageError cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        runtime.evidence().data.setTag("gtnl", probe.result());
        cases(probe.result(), "gtnl/");
    }

    private void closeIntegrationAfterFailure(Throwable failure) {
        if (integration == null) return;
        try {
            integration.close();
        } catch (Exception | LinkageError cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        runtime.evidence().data.setTag("gtnlGtsr", integration.result());
        cases(integration.result(), "gtnl_gtsr/");
    }

    @Override
    public void cleanup() throws Exception {
        Throwable failure = null;
        if (probe != null) {
            try {
                probe.close();
            } catch (Exception | LinkageError closedFailure) {
                failure = closedFailure;
            } finally {
                runtime.evidence().data.setTag("gtnl", probe.result());
            }
        }
        if (integration != null) {
            try {
                integration.close();
            } catch (Exception | LinkageError closedFailure) {
                if (failure == null) failure = closedFailure;
                else failure.addSuppressed(closedFailure);
            } finally {
                runtime.evidence().data.setTag("gtnlGtsr", integration.result());
            }
        }
        if (workflow != null) {
            try {
                workflow.close();
            } catch (Exception | LinkageError closedFailure) {
                if (failure == null) failure = closedFailure;
                else failure.addSuppressed(closedFailure);
            } finally {
                runtime.evidence().data.setTag("gtnlWorkflow", workflow.result());
            }
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
    }
}
