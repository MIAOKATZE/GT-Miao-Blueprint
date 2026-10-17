package com.miaokatze.gtmb.testlab;

import java.math.BigInteger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramCompositeLabBridge;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;

import gregtech.common.misc.WirelessNetworkManager;

/** Real registered fixture and connected native workflow witness; READY alone can never finish the run. */
final class TestLabCompositeTask implements TestLabTasks.Task {

    private static final BigInteger FIXTURE_EU = new BigInteger("100000000000");
    private final TestLabRuntime runtime;
    private final TestLabCompositeFixture fixture;
    private HologramCompositeLabBridge bridge;
    private final NBTTagList stages = new NBTTagList();
    private int phase, cursor;
    private long stageStart, stageTick;
    private String stage = "";
    private NBTTagCompound metrics;
    private boolean euSupplied;

    TestLabCompositeTask(TestLabRuntime runtime) {
        this.runtime = runtime;
        fixture = new TestLabCompositeFixture(runtime);
    }

    public int step() throws Exception {
        if (phase == 0) {
            runtime.useZone(
                runtime.config()
                    .zone("composite-source"));
            TestLabTasks.require(
                runtime.actor().player.playerNetServerHandler != null
                    && !(runtime.actor().player instanceof net.minecraftforge.common.util.FakePlayer),
                "Composite acceptance requires the configured connected survival client");
            WirelessNetworkManager.strongCheckOrAddUser(runtime.actor().player.getUniqueID());
            TestLabTasks.require(
                WirelessNetworkManager.getUserEU(runtime.actor().player.getUniqueID())
                    .signum() == 0,
                "Owned composite actor wireless EU starts empty; an existing balance is never overwritten");
            TestLabTasks.require(
                WirelessNetworkManager.addEUToGlobalEnergyMap(runtime.actor().player.getUniqueID(), FIXTURE_EU),
                "Finite native fixture wireless EU supply refused");
            euSupplied = true;
            runtime.evidence().data.setString("nativeFixtureEuSupplied", FIXTURE_EU.toString());
            bridge = new HologramCompositeLabBridge(
                runtime.world(),
                runtime.actor().player,
                runtime.compositeManifest());
            stage("finite-native-bank");
            bridge.initialize();
            phase++;
            return TestLabTasks.WAIT;
        }
        if (phase == 1) {
            if (!bridge.bankReady()) return TestLabTasks.WAIT;
            stage("source-64000-cells");
            phase++;
            cursor = 0;
        } else if (phase == 2) {
            fixture.fill(cursor++);
            if (cursor == 64000) {
                stage("native-100-multiblocks");
                phase++;
                cursor = 0;
            }
        } else if (phase == 3) {
            fixture.machine(cursor++);
            if (cursor == runtime.config().compositeMachines) {
                stage("native-extra-gt");
                phase++;
                cursor = 0;
            }
        } else if (phase == 4) {
            fixture.extra(cursor++);
            if (cursor == 20) {
                stage("native-ae-devices");
                phase++;
                cursor = 0;
            }
        } else if (phase == 5) {
            fixture.aeDevice(cursor++);
            if (cursor == TestLabCompositeFixture.AE_DEVICES.length * 3) {
                stage("native-eio-devices");
                phase++;
                cursor = 0;
            }
        } else if (phase == 6) {
            fixture.eioDevice(cursor++);
            if (cursor == TestLabCompositeFixture.EIO_DEVICES.length * 3) {
                stage("native-ae-cables");
                phase++;
                cursor = 0;
            }
        } else if (phase == 7) {
            fixture.cable(cursor++);
            if (cursor == 40) {
                stage("native-gtswn-screens");
                phase++;
                cursor = 0;
            }
        } else if (phase == 8) {
            fixture.screen(cursor++);
            if (cursor == 4) {
                stage("native-gtswn-links");
                phase++;
                cursor = 0;
            }
        } else if (phase == 9) {
            fixture.link(cursor++);
            if (cursor == 4) {
                stage("native-encoded-patterns");
                fixture.patternAssets();
                stage("native-quantum-gateways");
                phase++;
                cursor = 0;
            }
            return TestLabTasks.WAIT; // Native link terminal cooldown advances only on real world ticks.
        } else if (phase == 10) {
            fixture.connect(cursor++, bridge.sourceTerminal());
            if (cursor == fixture.gatewayCount()) {
                stage("natural-formed-idle");
                phase++;
            }
        } else if (phase == 11) {
            runtime.evidence().data.setTag("fixture", fixture.receipt());
            if (!fixture.formed()) return TestLabTasks.WAIT;
            runtime.evidence()
                .check(
                    "native_multiblocks",
                    "PASS",
                    "Actual registered EBF/LCR structures formed naturally and remain stopped with zero recipe progress");
            stage("immutable-native-baseline");
            bridge.startBaseline();
            phase++;
        } else if (phase == 12) {
            bridge.tick();
            runtime.evidence().data.setTag("composite", bridge.report());
            if (bridge.ready()) {
                runtime.evidence().data.setTag("fixture", fixture.receipt());
                stage("connected-native-workflows");
                phase++;
                runtime.evidence().data.setString("compositeStage", "NATIVE_READY");
            }
            return TestLabTasks.WAIT;
        } else {
            bridge.tick();
            NBTTagCompound observed = bridge.report();
            runtime.evidence().data.setTag("composite", observed);
            if ("PASS".equals(observed.getString("status"))) {
                stage("complete");
                runtime.evidence()
                    .check(
                        "native_composite_workflows",
                        "PASS",
                        "Connected self/share owner Save, real finite payment and raw cut/paste independent postchecks completed");
                return TestLabTasks.DONE;
            }
            return TestLabTasks.WAIT;
        }
        runtime.evidence().data.setInteger("compositeCursor", cursor);
        return TestLabTasks.CONTINUE;
    }

    private void stage(String next) {
        long now = System.nanoTime();
        if (!stage.isEmpty()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("name", stage);
            row.setLong("elapsedNanos", now - stageStart);
            row.setLong("ticks", runtime.ticks() - stageTick);
            row.setTag("metricsStart", metrics);
            row.setTag("metricsEnd", HologramRuntimeMetrics.snapshot());
            stages.appendTag(row);
        }
        stage = next;
        stageStart = now;
        stageTick = runtime.ticks();
        metrics = HologramRuntimeMetrics.snapshot();
        runtime.evidence().data.setTag("compositeStages", stages);
        runtime.evidence().data.setString("compositeStage", next);
    }

    public void cleanup() throws Exception {
        if (bridge != null) runtime.evidence().data.setTag("composite", bridge.report());
        if (euSupplied) {
            BigInteger extraCredits = bridge == null ? BigInteger.ZERO : bridge.fixtureEuCredits();
            BigInteger totalCredits = FIXTURE_EU.add(extraCredits);
            BigInteger remaining = WirelessNetworkManager.getUserEU(runtime.actor().player.getUniqueID());
            runtime.evidence().data.setString("nativeFixturePlacementEuCredits", extraCredits.toString());
            runtime.evidence().data.setString("nativeFixtureTotalEuCredits", totalCredits.toString());
            runtime.evidence().data.setString("nativeFixtureEuRemaining", remaining.toString());
            TestLabTasks.require(
                remaining.signum() >= 0 && remaining.compareTo(totalCredits) <= 0,
                "Fixture EU remainder exceeds the actual finite native supply");
            TestLabTasks.require(
                WirelessNetworkManager.addEUToGlobalEnergyMap(runtime.actor().player.getUniqueID(), remaining.negate()),
                "Finite fixture EU remainder removal failed");
            runtime.evidence().data.setString(
                "nativeFixtureEuConsumed",
                totalCredits.subtract(remaining)
                    .toString());
            euSupplied = false;
        }
        if (bridge != null) bridge.close();
    }
}
