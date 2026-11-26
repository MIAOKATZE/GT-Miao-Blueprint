// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;

import org.lwjgl.input.Keyboard;

import com.google.gson.GsonBuilder;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramFourMachineSpec;
import com.miaokatze.gtmb.hologram.HologramFourMachineWorldAudit;
import com.miaokatze.gtmb.hologram.HologramFourMachineWorldFixture;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire;
import com.miaokatze.gtmb.register.ProjectorRegistry;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** New action-wire acceptance. The initial EBF slice is explicitly a slice, never four-machine acceptance. */
final class HologramFourMachineRuntimeProbe {

    static final String FLAG = "gtmb.testlab.fourMachineRuntime";

    static boolean enabled() {
        return Boolean.getBoolean(FLAG);
    }

    private final Path save, output;
    private final UUID actorId;
    private final boolean fullMatrix;
    private final List<MachineCase> cases = new ArrayList<>();
    private final List<Map<String, Object>> caseResults = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final java.util.Set<String> capturedPanels = new java.util.HashSet<>();
    private volatile HologramFourMachineWorldFixture fixture;
    private volatile boolean ready;
    private HologramScreen screen;
    private HologramFourMachineWireAudit wire;
    private CompletableFuture<?> pending;
    private CompletableFuture<NBTTagCompound> cleanup;
    private List<Target> target, previous = new ArrayList<>();
    private NBTTagCompound before;
    private UUID oldTerminal;
    private int beginsBefore;
    private long submittedAt;
    private long cleanupAuditAt;
    private int phase, ticks, submitPhase;
    private long since = System.nanoTime();
    private boolean tutorialExit, done, restored;
    private boolean initialStageAttempted, unformedCycle;
    private int caseIndex, sourcePhase, editPhase, channelIndex;
    private int disabledActionIndex, disabledActionBegins;
    private NBTTagCompound unchangedWorld, expectedDrops;
    private Map<NBTTagCompound, Long> plannedDelta;
    private List<Target> damaged;
    private String screenshot;

    HologramFourMachineRuntimeProbe(Path save, Path output, UUID actorId, Map<String, Object> parent) {
        require(enabled() && Boolean.getBoolean("miaotest.host.environment"), "Exact isolated host opt-in required");
        String slice = System.getProperty("gtmb.testlab.fourMachineSlice", "ebf");
        require("ebf".equals(slice) || "all".equals(slice), "Unknown bounded native action matrix");
        fullMatrix = "all".equals(slice);
        if (fullMatrix) for (HologramFourMachineSpec spec : HologramFourMachineSpec.values())
            for (ExtendedFacing facing : spec.orientations()) cases.add(new MachineCase(spec, facing));
        else cases.add(new MachineCase(HologramFourMachineSpec.EBF, ExtendedFacing.NORTH_NORMAL_NONE));
        this.save = save;
        this.output = output;
        this.actorId = actorId;
        report.put("schema", "gtmb-four-machine-action-runtime-v1");
        report.put(
            "scope",
            fullMatrix ? "Four live native machine action matrix including NORTH/EAST, inventory and finite AE"
                : "EBF vertical slice: default BUILD and LV preset REPAIR; remaining matrix NOT_RUN");
        report.put("runId", System.getProperty("miaotest.host.runId"));
        report.put(
            "artifact",
            com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity.capture(getClass())
                .asMap());
        report.put(
            "coreArtifact",
            com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity
                .capture(com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity.class)
                .asMap());
        report.put(
            "nativeGTArtifact",
            com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity.capture(GregTechAPI.class)
                .asMap());
        report.put("cases", caseResults);
        report.put(
            "actualMaxHeapBytes",
            Runtime.getRuntime()
                .maxMemory());
        require(
            Runtime.getRuntime()
                .maxMemory() <= 5120L * 1024 * 1024,
            "Registered client heap cap exceeded");
        report.put("passed", false);
        report.put("steps", steps);
        report.put("cleanupPending", true);
        report.put("input", "Real MUI widget events, normal stock PROBE/BEGIN wire, real non-OP survival actor");
        parent.put("fourMachineRuntime", report);
    }

    void server(WorldServer world) throws Exception {
        require(
            world.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .toRealPath()
                .equals(save.toRealPath()),
            "Fixture save changed");
        if (fixture == null) {
            if (world.playerEntities.size() != 1 || !(world.playerEntities.get(0) instanceof EntityPlayerMP)) return;
            EntityPlayerMP actor = (EntityPlayerMP) world.playerEntities.get(0);
            if (actor.ticksExisted < 60) return;
            require(
                !(actor instanceof net.minecraftforge.common.util.FakePlayer),
                "A connected real actor is required");
            fixture = new HologramFourMachineWorldFixture(
                world,
                actor,
                actorId,
                output.resolve("four-machine-actor-recovery.snbt"));
            fixture.prepare(fullMatrix);
        }
        fixture.tick(world);
        if (fixture.isClosed() || fixture.preparationBlocked()) return;
        if (!initialStageAttempted) {
            if (fullMatrix && !fixture.bankReady()) return;
            initialStageAttempted = true;
            fixture.stageController(current().spec, current().facing);
            List<ItemStack> hatches = new ArrayList<>();
            for (String tier : new String[] { "LV", "MV" })
                for (String role : new String[] { "Energy", "Input", "Output", "Input_Bus", "Output_Bus", "Muffler" })
                    hatches.add(
                        ItemList.valueOf("Hatch_" + role + "_" + tier)
                            .get(8));
            hatches.add(ItemList.Hatch_Maintenance.get(8));
            fixture.supply(hatches, false);
            ready = true;
        }
    }

    boolean client(Minecraft mc) throws Exception {
        if (done) return true;
        require(System.nanoTime() - since < 100_000_000_000L, "Four-machine EBF phase timed out: " + phase);
        if (!ready || mc.thePlayer == null || mc.theWorld == null) return false;
        report.put("phase", phase);
        report.put("machine", current().spec.key);
        report.put("orientation", current().facing.toString());
        report.put("unformedDemolitionCycle", unformedCycle);
        if (pending != null && !pending.isDone()) return false;
        if (pending != null) {
            pending.join();
            pending = null;
        }
        if (phase == 0) {
            int[] p = fixture.anchor();
            if (mc.theWorld.getTileEntity(p[0], p[1], p[2]) == null
                || mc.thePlayer.inventory.currentItem != fixture.toolSlot()
                || mc.thePlayer.getHeldItem() == null
                || mc.thePlayer.getHeldItem()
                    .getItem() != ProjectorRegistry.projector)
                return false;
            if (!(mc.theWorld
                .getTileEntity(p[0], p[1], p[2]) instanceof gregtech.api.metatileentity.BaseMetaTileEntity base)
                || base.getMetaTileID() != current().spec.controllerId
                || base.getMetaTileEntity() == null
                || !mc.thePlayer.getHeldItem()
                    .hasTagCompound()
                || HologramClientStockNative.canonicalTool(
                    mc.thePlayer.getHeldItem()
                        .getTagCompound()
                        .getString("gtitHologramToolId"))
                    == null)
                return false;
            if (wire == null) wire = new HologramFourMachineWireAudit(mc);
            screen = HologramScreen.localController(
                mc.theWorld.getTileEntity(p[0], p[1], p[2]),
                0,
                current().facing.getDirection()
                    .ordinal());
            screen.openFrom(mc.currentScreen);
            next();
            return false;
        }
        require(
            mc.currentScreen == screen || phase == 27 || phase == 28 || phase == 29,
            "Native machine screen ownership changed");
        report.put("lastState", screen.ui.session.state().data.toString());
        report.put(
            "lastMaterialDialog",
            screen.ui.review.materialDialogProbeState()
                .toString());
        report.put("lastUiNotice", screen.ui.session.notice());
        report.put("flowPending", HologramClientStockNative.OWNER.pendingHere(HologramClientStockNative.context(mc)));
        report.put(
            "flowRequest",
            String.valueOf(HologramClientStockNative.OWNER.requestFor(screen, HologramClientStockNative.context(mc))));
        if (screen.ui.tutorial.active()) {
            if (!tutorialExit) {
                tutorialExit = true;
                HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
            }
            return false;
        }
        // The normal result review deliberately remains open after COMPLETE. Leave it through its actual Back
        // widget before accessing an inspector launcher or planning the next action; never click through a modal.
        if (mc.currentScreen == screen && postBusinessReviewPhase() && screen.ui.review.materialDialogOpen()) {
            if (!present("materials.control.54") || !screen.ui.review.materialControlEnabled(54)) return false;
            record("completed-material-review-before-back", screen.ui.review.materialDialogProbeState());
            click("materials.control.54");
            return false;
        }
        if (++ticks < 3 || screenshot != null) return false;
        switch (phase) {
            case 1 -> {
                if (!targetReady("CONTROLLER_ONLY")) return false;
                if (!present("control.1720")) {
                    click("launcher.3");
                    return false;
                }
                require(
                    !screen.ui.session.state().data.getBoolean("presetActive"),
                    "Fresh controller applied an optional preset");
                require(
                    screen.nativeActionBlocker(HologramStockNativeWire.Operation.BUILD)
                        .isEmpty(),
                    "Controller-only BUILD unavailable");
                for (HologramStockNativeWire.Operation action : new HologramStockNativeWire.Operation[] {
                    HologramStockNativeWire.Operation.REPAIR, HologramStockNativeWire.Operation.REPLACE,
                    HologramStockNativeWire.Operation.DEMOLISH })
                    require(
                        !screen.nativeActionBlocker(action)
                            .isEmpty(),
                        "Controller-only non-build action enabled: " + action);
                if (!disabledActions()) return false;
                target = freeze();
                int hatchCount = 0, materials = 0;
                for (Target cell : target) if (cell.item != null) materials++;
                for (Target cell : target) if (cell.hatch()) {
                    hatchCount++;
                    require(
                        current().spec == HologramFourMachineSpec.EBF,
                        "Default optional hatch was not a casing on " + current().spec.key);
                    require(
                        GregTechAPI.METATILEENTITIES[cell.item
                            .getItemDamage()] instanceof gregtech.api.metatileentity.implementations.MTEHatchMuffler,
                        "Default optional hatch was not a casing");
                    require(cell.dy == 3, "Forced EBF muffler is not at native top");
                }
                if (current().spec == HologramFourMachineSpec.EBF) require(
                    hatchCount == 1 && materials == 33,
                    "EBF default native body or mandatory muffler differs: materials=" + materials
                        + " cells="
                        + target.size());
                else require(hatchCount == 0 && materials > 0, "Default optional-free native target differs");
                record("controller-only-default", targetTag(target));
                pending = fixture.request(() -> {
                    fixture.supply(materials(target), fullMatrix);
                    return null;
                });
                screenshot = imageName("default-target");
                next();
            }
            case 2 -> {
                if (fullMatrix && !sources(!aeOnlyBuild())) return false;
                pending = fixture.request(() -> {
                    fixture.watch(points(target, true), "BUILD");
                    before = fixture.resources();
                    plannedDelta = prepareDelta(previous, target);
                    return before;
                });
                next();
            }
            case 3 -> {
                if (!submit(HologramStockNativeWire.Operation.BUILD)) return false;
                next();
            }
            case 4 -> {
                if (!completed()) return false;
                pending = fixture.request(this::audit);
                next();
            }
            case 5 -> {
                record("default-build-world", lastAudit);
                compareWorld(lastAudit, target);
                compareResources(before, lastAudit.getCompoundTag("resources"), plannedDelta);
                if (aeOnlyBuild()) {
                    compareAeOnly(before, lastAudit.getCompoundTag("resources"), plannedDelta, "AE-only default BUILD");
                    report.put("aeOnlyBuildPaid", true);
                }
                require(
                    !lastAudit.getCompoundTag("controller")
                        .getBoolean("naturalFormed"),
                    "Default optional-free EBF unexpectedly formed");
                if (fullMatrix && unformedCycle) {
                    phase = 25;
                    ticks = 0;
                    since = System.nanoTime();
                    return false;
                }
                if (fullMatrix && current().spec == HologramFourMachineSpec.PRECISE_ASSEMBLER && !initialGlass())
                    return false;
                if (!present("control.94")) {
                    click("launcher.4");
                    return false;
                }
                previous = target;
                click("control.94");
                next();
            }
            case 6 -> {
                if (!present("control.1701")) return false;
                click("control.1701");
                next();
            }
            case 7 -> {
                if (!present("control.106")) return false;
                click("control.106");
                next();
            }
            case 8 -> {
                if (!screen.ui.session.state().data.getBoolean("presetActive") || !targetReady("UNFORMED"))
                    return false;
                require(screen.ui.session.state().data.getInteger("hatchTier") == 1, "Real LV preset was not applied");
                target = freeze();
                int hatches = 0;
                for (Target cell : target) if (cell.hatch()) hatches++;
                require(
                    hatches >= (current().spec == HologramFourMachineSpec.EXTRUDER
                        || current().spec == HologramFourMachineSpec.FLUID_EXTRACTOR ? 5 : 6),
                    "Preset did not generate real native hatch targets");
                record("lv-preset-target", targetTag(target));
                pending = fixture.request(() -> {
                    fixture.supply(materials(target), fullMatrix);
                    return null;
                });
                screenshot = imageName("lv-preset-target");
                next();
            }
            case 9 -> {
                pending = fixture.request(() -> {
                    fixture.watch(points(target, true), "PRESET_REPAIR");
                    before = fixture.resources();
                    plannedDelta = prepareDelta(previous, target);
                    return before;
                });
                next();
            }
            case 10 -> {
                if (!submit(HologramStockNativeWire.Operation.REPAIR)) return false;
                next();
            }
            case 11 -> {
                if (!completed()) return false;
                pending = fixture.request(this::audit);
                next();
            }
            case 12 -> {
                compareWorld(lastAudit, target);
                compareResources(before, lastAudit.getCompoundTag("resources"), plannedDelta);
                firstHatchSkins(lastAudit, target, previous);
                record("preset-repair-immediate-world", lastAudit);
                next();
            }
            case 13 -> {
                if (pending == null) pending = fixture.request(this::audit);
                if (!lastAudit.getCompoundTag("controller")
                    .getBoolean("naturalFormed")) return false;
                compareWorld(lastAudit, target);
                record("preset-repair-naturally-formed-world", lastAudit);
                if (!fullMatrix) {
                    report.put("ebfSlicePassed", true);
                    report.put("remainingMatrix", "NOT_RUN; this report cannot satisfy four-machine acceptance");
                    report.put("wire", wire.snapshot());
                    done = true;
                    write();
                    return true;
                }
                unchangedWorld = lastAudit;
                previous = target;
                next();
            }
            case 14 -> {
                List<Target> plain = new ArrayList<>();
                NBTTagList cells = lastAudit.getTagList("cells", 10);
                for (int i = 0; i < target.size(); i++) if (target.get(i).item != null && cells.getCompoundTagAt(i)
                    .getString("tileClass")
                    .isEmpty()) plain.add(target.get(i));
                require(plain.size() >= 2, "No reviewed real non-TE damage cells");
                Target missing = plain.get(0), wrong = plain.get(1);
                damaged = new ArrayList<>(target);
                damaged.set(target.indexOf(missing), new Target(missing, "minecraft:air", 0, null));
                damaged.set(
                    target.indexOf(wrong),
                    new Target(
                        wrong,
                        "minecraft:cobblestone",
                        0,
                        new ItemStack(net.minecraft.init.Blocks.cobblestone)));
                pending = fixture.request(() -> {
                    NBTTagCompound result = fixture.damage(missing.position(), wrong.position());
                    record("explicit-fixture-damage", result);
                    return result;
                });
                next();
            }
            case 15 -> {
                if (pending == null) pending = fixture.request(this::audit);
                if (lastAudit.getCompoundTag("controller")
                    .getBoolean("naturalFormed") || !targetReady("UNFORMED")) return false;
                pending = fixture.request(() -> {
                    fixture.watch(points(target, true), "DAMAGE_REPAIR");
                    before = fixture.resources();
                    plannedDelta = prepareDelta(damaged, target);
                    return before;
                });
                next();
            }
            case 16 -> {
                if (!submit(HologramStockNativeWire.Operation.REPAIR)) return false;
                next();
            }
            case 17 -> {
                if (!completed()) return false;
                pending = fixture.request(this::audit);
                next();
            }
            case 18 -> {
                compareWorld(lastAudit, target);
                compareResources(before, lastAudit.getCompoundTag("resources"), plannedDelta);
                retainedTiles(unchangedWorld, lastAudit);
                record("damage-repair-world", lastAudit);
                next();
            }
            case 19 -> {
                if (pending == null) pending = fixture.request(this::audit);
                if (!lastAudit.getCompoundTag("controller")
                    .getBoolean("naturalFormed") || !targetReady("FORMED")) return false;
                unchangedWorld = lastAudit;
                editPhase = 0;
                sourcePhase = 0;
                channelIndex = 0;
                next();
            }
            case 20 -> {
                if (!changedTarget()) return false;
                next();
            }
            case 21 -> {
                rejectFailedNativeTarget("MV preset replacement");
                if (!targetReady("FORMED") || !screen.ui.session.state().data.getBoolean("presetActive")
                    || screen.ui.session.state().data.getInteger("hatchTier") != 2) return false;
                require(
                    screen.ui.session.state().data.getBoolean("hatchDowngrade"),
                    "MV preset lost allowed downgrade");
                if (aeRecoveryReplace() && !sources(false)) return false;
                target = freeze();
                requireMixedMvHatches(target);
                require(
                    !targetTag(previous).equals(targetTag(target)),
                    "Main/channel/MV preset change did not change rendered target");
                record("changed-native-render-target", targetTag(target));
                pending = fixture.request(() -> {
                    NBTTagCompound snapshot = audit();
                    sameWorld(unchangedWorld, snapshot);
                    fixture.supply(materials(target), true);
                    fixture.watch(points(target, true), "REPLACE");
                    before = fixture.resources();
                    plannedDelta = prepareDelta(previous, target);
                    return before;
                });
                screenshot = imageName("changed-target");
                next();
            }
            case 22 -> {
                if (!submit(HologramStockNativeWire.Operation.REPLACE)) return false;
                next();
            }
            case 23 -> {
                if (!completed()) return false;
                pending = fixture.request(this::audit);
                next();
            }
            case 24 -> {
                compareWorld(lastAudit, target);
                compareResources(before, lastAudit.getCompoundTag("resources"), plannedDelta);
                if (aeRecoveryReplace()) {
                    compareAeOnly(
                        before,
                        lastAudit.getCompoundTag("resources"),
                        plannedDelta,
                        "AE-only REPLACE with AE recovery");
                    report.put("aeReplacementRecovered", true);
                }
                firstHatchSkins(lastAudit, target, previous);
                record("replacement-world", lastAudit);
                previous = target;
                if (current().spec == HologramFourMachineSpec.EBF && !unformedCycle) {
                    phase = 30;
                    ticks = 0;
                    since = System.nanoTime();
                } else next();
            }
            case 25 -> {
                if (pending == null) pending = fixture.request(this::audit);
                boolean formed = lastAudit.getCompoundTag("controller")
                    .getBoolean("naturalFormed");
                if (formed == unformedCycle || !targetReady(unformedCycle ? "UNFORMED" : "FORMED")) return false;
                compareWorld(lastAudit, target);
                screen.ui.preview.clearSelection();
                pending = fixture.request(() -> {
                    expectedDrops = fixture.expectedDrops(points(target, true));
                    fixture.watch(points(target, true), "DEMOLISH");
                    before = fixture.resources();
                    return before;
                });
                next();
            }
            case 26 -> {
                if (!submit(HologramStockNativeWire.Operation.DEMOLISH)) return false;
                next();
            }
            case 27 -> {
                if (!completed()) return false;
                pending = fixture.request(this::audit);
                next();
            }
            case 28 -> {
                for (int i = 0; i < lastAudit.getTagList("cells", 10)
                    .tagCount(); i++) {
                    NBTTagCompound cell = lastAudit.getTagList("cells", 10)
                        .getCompoundTagAt(i);
                    require(
                        "minecraft:air".equals(cell.getString("block")) && cell.getString("tileClass")
                            .isEmpty(),
                        "Whole demolition left body block or orphan TE");
                }
                require(
                    "minecraft:air".equals(
                        lastAudit.getCompoundTag("controller")
                            .getString("block"))
                        && lastAudit.getCompoundTag("controller")
                            .getString("tileClass")
                            .isEmpty(),
                    "Whole demolition left controller or orphan TE");
                compareResources(
                    before,
                    lastAudit.getCompoundTag("resources"),
                    readCounts(expectedDrops.getTagList("items", 10)));
                compareDelivery(
                    before,
                    lastAudit.getCompoundTag("resources"),
                    readCounts(expectedDrops.getTagList("items", 10)),
                    unformedCycle);
                controllerLast(lastAudit);
                record(unformedCycle ? "unformed-demolished-to-AE" : "formed-demolished-to-inventory", lastAudit);
                next();
            }
            case 29 -> {
                Map<String, Object> finished = new LinkedHashMap<>();
                finished.put("machine", current().spec.key);
                finished.put("orientation", current().facing.toString());
                finished.put("cycle", unformedCycle ? "UNFORMED_TO_AE" : "FULL_CHAIN_TO_INVENTORY");
                finished.put("passed", true);
                caseResults.add(finished);
                mc.displayGuiScreen(null);
                if (!unformedCycle) unformedCycle = true;
                else {
                    unformedCycle = false;
                    caseIndex++;
                }
                if (caseIndex >= cases.size()) {
                    require(
                        wire.failure()
                            .isEmpty(),
                        wire.failure());
                    report.put("allActionsPassed", true);
                    report.put("wire", wire.snapshot());
                    done = true;
                    write();
                    return true;
                }
                previous = new ArrayList<>();
                target = null;
                submitPhase = 0;
                sourcePhase = 0;
                editPhase = 0;
                disabledActionIndex = 0;
                tutorialExit = false;
                pending = fixture.request(() -> fixture.stageController(current().spec, current().facing));
                phase = 0;
                ticks = 0;
                since = System.nanoTime();
            }
            case 30 -> {
                if (!present("control.94")) {
                    click("launcher.4");
                    return false;
                }
                click("control.94");
                next();
            }
            case 31 -> {
                if (!present("control.107")) return false;
                click("control.107");
                next();
            }
            case 32 -> {
                if (screen.ui.session.state().data.getBoolean("presetActive") || !targetReady("FORMED")) return false;
                target = freeze();
                requireOptionalFreeEbf(target);
                record("preset-cleared-casing-target", targetTag(target));
                pending = fixture.request(() -> {
                    fixture.supply(materials(target), true);
                    fixture.watch(points(target, true), "CLEAR_PRESET_REPLACE");
                    before = fixture.resources();
                    plannedDelta = prepareDelta(previous, target);
                    return before;
                });
                screenshot = imageName("cleared-preset-target");
                next();
            }
            case 33 -> {
                if (!submit(HologramStockNativeWire.Operation.REPLACE)) return false;
                next();
            }
            case 34 -> {
                if (!completed()) return false;
                pending = fixture.request(this::audit);
                next();
            }
            case 35 -> {
                compareWorld(lastAudit, target);
                requireOptionalFreeEbf(target);
                compareResources(before, lastAudit.getCompoundTag("resources"), plannedDelta);
                compareAeOnly(
                    before,
                    lastAudit.getCompoundTag("resources"),
                    plannedDelta,
                    "AE-only CLEAR_PRESET_REPLACE with AE recovery");
                if (lastAudit.getCompoundTag("controller")
                    .getBoolean("naturalFormed") || !targetReady("UNFORMED")) {
                    pending = fixture.request(this::audit);
                    return false;
                }
                record("preset-cleared-physical-casing-unformed", lastAudit);
                report.put("presetClearedToCasing", true);
                previous = target;
                if (!present("control.94")) {
                    click("launcher.4");
                    return false;
                }
                click("control.94");
                next();
            }
            case 36 -> {
                if (!present("control.1702")) return false;
                click("control.1702");
                next();
            }
            case 37 -> {
                if (!present("control.106")) return false;
                if (!allowPresetDowngrade()) return false;
                click("control.106");
                next();
            }
            case 38 -> {
                rejectFailedNativeTarget("Reapplied MV preset");
                if (!screen.ui.session.state().data.getBoolean("presetActive") || !targetReady("UNFORMED"))
                    return false;
                require(
                    screen.ui.session.state().data.getInteger("hatchTier") == 2
                        && screen.ui.session.state().data.getBoolean("hatchDowngrade"),
                    "Reapplied MV preset lost tier or allowed downgrade");
                target = freeze();
                requireMixedMvHatches(target);
                long hatches = target.stream()
                    .filter(Target::hatch)
                    .count();
                require(hatches >= 6, "Reapplied MV preset has no optional native hatches");
                pending = fixture.request(() -> {
                    fixture.supply(materials(target), true);
                    fixture.watch(points(target, true), "REAPPLY_PRESET_REPAIR");
                    before = fixture.resources();
                    plannedDelta = prepareDelta(previous, target);
                    return before;
                });
                screenshot = imageName("reapplied-preset-target");
                next();
            }
            case 39 -> {
                if (!submit(HologramStockNativeWire.Operation.REPAIR)) return false;
                next();
            }
            case 40 -> {
                if (!completed()) return false;
                pending = fixture.request(this::audit);
                next();
            }
            case 41 -> {
                compareWorld(lastAudit, target);
                compareResources(before, lastAudit.getCompoundTag("resources"), plannedDelta);
                firstHatchSkins(lastAudit, target, previous);
                if (!lastAudit.getCompoundTag("controller")
                    .getBoolean("naturalFormed") || !targetReady("FORMED")) {
                    pending = fixture.request(this::audit);
                    return false;
                }
                record("preset-reapplied-repair-naturally-formed", lastAudit);
                report.put("presetRepairedAfterClear", true);
                previous = target;
                phase = 25;
                ticks = 0;
                since = System.nanoTime();
            }
            default -> throw new IllegalStateException("Unknown new runtime phase");
        }
        return false;
    }

    private NBTTagCompound lastAudit;

    private NBTTagCompound audit() throws Exception {
        NBTTagCompound result = new NBTTagCompound();
        int[][] points = new int[target.size()][];
        for (int i = 0; i < target.size(); i++) points[i] = target.get(i)
            .position();
        result.setTag("cells", fixture.cells(points));
        result.setTag("resources", fixture.resources());
        result.setTag("controller", fixture.controller());
        result.setTag("worldWrites", fixture.worldWrites());
        result.setTag("outsideFootprintSentinels", fixture.sentinels());
        lastAudit = result;
        return result;
    }

    private boolean targetReady(String stage) {
        NBTTagCompound data = screen.ui.session.state().data;
        return data.getBoolean("nativeTargetReady") && data.getLong("nativeObservationToken") > 0
            && stage.equals(data.getString("nativeStage"))
            && !data.getBoolean("capturePending");
    }

    private boolean submit(HologramStockNativeWire.Operation operation) throws Exception {
        if (submitPhase == 0) {
            if (!present("control." + (1720 + operation.ordinal()))) {
                click("launcher.3");
                return false;
            }
            HologramStockNativeWire.Reply terminal = wire.latestTerminal();
            oldTerminal = terminal == null ? null
                : terminal.key()
                    .request();
            beginsBefore = wire.beginCount();
            require(
                screen.nativeActionBlocker(operation)
                    .isEmpty(),
                "Native action refused before submission: " + operation);
            click("control." + (1720 + operation.ordinal()));
            submitPhase++;
            return false;
        }
        if (submitPhase == 1) {
            if (!present("control.50")) {
                click("launcher.8");
                return false;
            }
            require(
                screen.nativeControllerState()
                    .action() == operation,
                "Real action button did not select action");
            click("control.50");
            submitPhase++;
            return false;
        }
        if (submitPhase == 2) {
            int destination = operation == HologramStockNativeWire.Operation.DEMOLISH ? (unformedCycle ? 1 : 0)
                : operation == HologramStockNativeWire.Operation.REPLACE && aeRecoveryReplace() ? 1 : 0;
            if (operation != HologramStockNativeWire.Operation.BUILD
                && screen.ui.review.nativeRecoveryDestination() != destination) {
                if (!present("materials.control.252") || !screen.ui.review.materialControlEnabled(252)) return false;
                click("materials.control.252");
                return false;
            }
            if (!present("materials.control.55") || !screen.ui.review.materialControlEnabled(55)) return false;
            record("material-confirm-" + operation, screen.ui.review.materialDialogProbeState());
            click("materials.control.55");
            record("material-submit-post-input-" + operation, screen.ui.review.materialDialogProbeState());
            record("material-submit-post-notice-" + operation, screen.ui.session.notice());
            submittedAt = System.nanoTime();
            submitPhase = 0;
            return true;
        }
        return false;
    }

    private boolean completed() {
        HologramStockNativeWire.Reply reply = wire.latestTerminal();
        if (reply == null || reply.key()
            .request()
            .equals(oldTerminal)) {
            if (System.nanoTime() - submittedAt > 2_000_000_000L && !HologramClientStockNative.OWNER
                .pendingHere(HologramClientStockNative.context(Minecraft.getMinecraft())))
                throw new IllegalStateException(
                    "Native submit produced no active Flow or terminal within 2s; notice=" + screen.ui.session.notice()
                        + "; material="
                        + screen.ui.review.materialDialogProbeState());
            return false;
        }
        record("native-terminal", reply.toString());
        require(
            reply.phase() == HologramStockNativeWire.Phase.COMPLETE,
            "Actual native action did not complete: " + reply);
        require(wire.beginCount() > beginsBefore, "Terminal arrived without a real outbound business BEGIN");
        if (aeOnlyBuild() && phase == 4 || aeRecoveryReplace() && phase == 23) require(
            (wire.beginSources() & 7) == 4,
            "AE-only GUI selection did not enter actual business wire source mask");
        if (aeRecoveryReplace() && (phase == 23 || phase == 34)) require(
            wire.beginDestination() == 1,
            "AE replacement recovery did not enter actual business wire destination");
        return true;
    }

    private List<Target> freeze() {
        List<Target> result = new ArrayList<>();
        for (HologramState.Cell cell : screen.ui.session.state().cells) if (!cell.anchor) result.add(new Target(cell));
        require(
            !result.isEmpty() && result.size() <= HologramFourMachineSpec.MAX_TARGET_CELLS,
            "Unbounded or empty native target");
        return result;
    }

    private static List<ItemStack> materials(List<Target> cells) {
        List<ItemStack> result = new ArrayList<>();
        for (Target cell : cells) if (cell.item != null) {
            ItemStack item = cell.item.copy();
            item.stackSize = 1;
            result.add(item);
        }
        return result;
    }

    private void compareWorld(NBTTagCompound audit, List<Target> expected) {
        NBTTagList actual = audit.getTagList("cells", 10);
        java.util.Set<Integer> actualHatchPalettes = new java.util.HashSet<>();
        require(actual.tagCount() == expected.size(), "Actual footprint count differs");
        for (int i = 0; i < actual.tagCount(); i++) {
            Target want = expected.get(i);
            NBTTagCompound got = actual.getCompoundTagAt(i);
            require(
                want.block.equals(got.getString("block")) && want.meta == got.getInteger("metadata"),
                "Actual block/meta differs at " + want.key() + ": " + got);
            if (want.item != null && want.item.getItem() instanceof ItemMachines) {
                require(
                    got.getInteger("metaTileId") == want.item.getItemDamage(),
                    "Actual MTE ID differs at " + want.key());
                if (want.facing >= 0)
                    require(got.getInteger("front") == want.facing, "Actual facing differs at " + want.key());
                if (want.hatch()) for (String field : new String[] { "texturePage", "textureIndex" }) {
                    actualHatchPalettes.add((got.getInteger("texturePage") << 7) | got.getInteger("textureIndex"));
                    require(want.display.hasKey(field), "Preview omitted exact hatch skin at " + want.key());
                    require(
                        got.getInteger(field) == want.display.getInteger(field),
                        "Actual/preview hatch skin differs at " + want.key() + " " + field);
                    int palette = HologramFourMachinePalette.texture(
                        current().spec,
                        current().facing,
                        want.dx,
                        want.dy,
                        want.dz,
                        screen.ui.session.state().data);
                    int independent = "texturePage".equals(field) ? palette >> 7 : palette & 127;
                    require(
                        got.getInteger(field) == independent && want.display.getInteger(field) == independent,
                        "Independent .265 native casing palette differs at " + want.key()
                            + " "
                            + field
                            + " expected="
                            + independent
                            + " actual="
                            + got.getInteger(field)
                            + " preview="
                            + want.display.getInteger(field));
                }
            }
        }
        if (current().spec == HologramFourMachineSpec.EXTRUDER
            && screen.ui.session.state().data.getBoolean("presetActive"))
            require(
                actualHatchPalettes.size() == 2,
                "Extruder fixture did not physically exercise both native A/C casing backgrounds");
    }

    private Map<NBTTagCompound, Long> prepareDelta(List<Target> old, List<Target> next) throws Exception {
        Map<String, Target> previous = new LinkedHashMap<>();
        for (Target cell : old) previous.put(cell.key(), cell);
        Map<NBTTagCompound, Long> result = new LinkedHashMap<>();
        List<int[]> removed = new ArrayList<>();
        for (Target cell : next) {
            Target existing = previous.remove(cell.key());
            if (existing != null && existing.sameMaterial(cell)) continue;
            if (existing != null && existing.item != null) removed.add(existing.position());
            if (cell.item != null) result.merge(HologramFourMachineWorldAudit.identity(cell.item), -1L, Long::sum);
        }
        require(previous.isEmpty(), "Expected target footprint unexpectedly shrank");
        NBTTagCompound nativeRefund = fixture.expectedDrops(removed.toArray(new int[0][]));
        for (var row : readCounts(nativeRefund.getTagList("items", 10)).entrySet())
            result.merge(row.getKey(), row.getValue(), Long::sum);
        result.entrySet()
            .removeIf(entry -> entry.getValue() == 0);
        return result;
    }

    private static void compareResources(NBTTagCompound before, NBTTagCompound after,
        Map<NBTTagCompound, Long> expected) {
        Map<NBTTagCompound, Long> oldLoose = readCounts(before.getTagList("looseItems", 10));
        for (var row : readCounts(after.getTagList("looseItems", 10)).entrySet()) require(
            row.getValue() <= oldLoose.getOrDefault(row.getKey(), 0L),
            "Business recovery leaked additional real loose items: " + row);
        java.util.Set<String> oldEntities = new java.util.HashSet<>();
        NBTTagList oldRows = before.getTagList("looseEntityDetails", 10),
            newRows = after.getTagList("looseEntityDetails", 10);
        for (int i = 0; i < oldRows.tagCount(); i++) oldEntities.add(
            oldRows.getCompoundTagAt(i)
                .getString("entity"));
        for (int i = 0; i < newRows.tagCount(); i++) require(
            oldEntities.contains(
                newRows.getCompoundTagAt(i)
                    .getString("entity")),
            "Business recovery spawned a new loose item entity instead of storage delivery");
        Map<NBTTagCompound, Long> actual = resourceCounts(after);
        for (var entry : resourceCounts(before).entrySet()) actual.merge(entry.getKey(), -entry.getValue(), Long::sum);
        actual.entrySet()
            .removeIf(entry -> entry.getValue() == 0);
        require(
            actual.equals(expected),
            "Actual survival material debit/refund differs: expected=" + expected + " actual=" + actual);
    }

    private static void compareAeOnly(NBTTagCompound before, NBTTagCompound after, Map<NBTTagCompound, Long> expected,
        String operation) {
        require(
            difference(inventoryCounts(before), inventoryCounts(after)).isEmpty(),
            operation + " changed the nonselected real main inventory");
        Map<NBTTagCompound, Long> ae = difference(
            readCounts(
                before.getCompoundTag("ae")
                    .getTagList("items", 10)),
            readCounts(
                after.getCompoundTag("ae")
                    .getTagList("items", 10)));
        require(
            ae.equals(expected),
            operation + " actual AE debit/recovery differs: expected=" + expected + " actual=" + ae);
        require(
            expected.values()
                .stream()
                .anyMatch(value -> value < 0),
            operation + " had no positive AE payment witness");
        if (operation.contains("REPLACE")) require(
            expected.values()
                .stream()
                .anyMatch(value -> value > 0),
            "Replacement had no actual native AE recovery witness");
    }

    private static void compareDelivery(NBTTagCompound before, NBTTagCompound after, Map<NBTTagCompound, Long> expected,
        boolean toAe) {
        Map<NBTTagCompound, Long> inventory = difference(inventoryCounts(before), inventoryCounts(after));
        Map<NBTTagCompound, Long> ae = difference(
            readCounts(
                before.getCompoundTag("ae")
                    .getTagList("items", 10)),
            readCounts(
                after.getCompoundTag("ae")
                    .getTagList("items", 10)));
        require(
            (toAe ? ae : inventory).equals(expected),
            "Actual selected demolition destination received wrong exact drops: destination="
                + (toAe ? "AE" : "inventory")
                + " expected="
                + expected
                + " actual="
                + (toAe ? ae : inventory));
        require(
            (toAe ? inventory : ae).isEmpty(),
            "Demolition changed the unselected storage destination: " + (toAe ? inventory : ae));
    }

    private static Map<NBTTagCompound, Long> inventoryCounts(NBTTagCompound snapshot) {
        NBTTagCompound inventory = new NBTTagCompound();
        inventory.setTag(
            "inventory",
            snapshot.getTagList("inventory", 10)
                .copy());
        NBTTagCompound terminal = new NBTTagCompound();
        terminal.setTag(
            "terminal",
            snapshot.getCompoundTag("ae")
                .getCompoundTag("terminal")
                .copy());
        inventory.setTag("ae", terminal);
        return resourceCounts(inventory);
    }

    private static Map<NBTTagCompound, Long> difference(Map<NBTTagCompound, Long> before,
        Map<NBTTagCompound, Long> after) {
        Map<NBTTagCompound, Long> result = new LinkedHashMap<>(after);
        for (var row : before.entrySet()) result.merge(row.getKey(), -row.getValue(), Long::sum);
        result.entrySet()
            .removeIf(row -> row.getValue() == 0);
        return result;
    }

    private static Map<NBTTagCompound, Long> resourceCounts(NBTTagCompound snapshot) {
        Map<NBTTagCompound, Long> result = new LinkedHashMap<>();
        String projector = String
            .valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(ProjectorRegistry.projector));
        ItemStack terminal = ItemStack.loadItemStackFromNBT(
            snapshot.getCompoundTag("ae")
                .getCompoundTag("terminal"));
        String terminalItem = terminal == null ? ""
            : String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(terminal.getItem()));
        for (String source : new String[] { "inventory", "looseItems" }) {
            NBTTagList rows = snapshot.getTagList(source, 10);
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound key = (NBTTagCompound) rows.getCompoundTagAt(i)
                    .copy();
                long amount = key.getLong("amount");
                key.removeTag("amount");
                if (!projector.equals(key.getString("item")) && !terminalItem.equals(key.getString("item")))
                    result.merge(key, amount, Long::sum);
            }
        }
        for (var entry : readCounts(
            snapshot.getCompoundTag("ae")
                .getTagList("items", 10)).entrySet())
            result.merge(entry.getKey(), entry.getValue(), Long::sum);
        return result;
    }

    private static final class Target {

        final int x, y, z, dx, dy, dz, meta, facing;
        final String block;
        final ItemStack item;
        final NBTTagCompound display;

        Target(HologramState.Cell cell) {
            x = cell.x;
            y = cell.y;
            z = cell.z;
            dx = cell.dx;
            dy = cell.dy;
            dz = cell.dz;
            meta = cell.wantMeta;
            block = cell.wantId;
            facing = cell.targetFacing;
            item = cell.targetStack == null ? null : cell.targetStack.copy();
            display = (NBTTagCompound) cell.display(false)
                .copy();
        }

        Target(Target source, String block, int meta, ItemStack item) {
            x = source.x;
            y = source.y;
            z = source.z;
            dx = source.dx;
            dy = source.dy;
            dz = source.dz;
            this.block = block;
            this.meta = meta;
            this.item = item;
            facing = -1;
            display = new NBTTagCompound();
        }

        boolean hatch() {
            return item != null && item.getItem() instanceof ItemMachines
                && GregTechAPI.METATILEENTITIES[item.getItemDamage()] instanceof MTEHatch;
        }

        String key() {
            return x + "," + y + "," + z;
        }

        int[] position() {
            return new int[] { x, y, z };
        }

        boolean same(Target other) {
            return block.equals(other.block) && meta == other.meta
                && facing == other.facing
                && (item == null ? other.item == null
                    : other.item != null && HologramFourMachineWorldAudit.identity(item)
                        .equals(HologramFourMachineWorldAudit.identity(other.item)));
        }

        boolean sameMaterial(Target other) {
            return block.equals(other.block) && meta == other.meta
                && (item == null ? other.item == null
                    : other.item != null && HologramFourMachineWorldAudit.identity(item)
                        .equals(HologramFourMachineWorldAudit.identity(other.item)));
        }

        NBTTagCompound tag() {
            NBTTagCompound n = new NBTTagCompound();
            n.setIntArray("position", position());
            n.setString("block", block);
            n.setInteger("metadata", meta);
            n.setInteger("facing", facing);
            if (item != null) n.setTag("item", item.writeToNBT(new NBTTagCompound()));
            n.setTag("display", display.copy());
            return n;
        }
    }

    private static NBTTagList targetTag(List<Target> cells) {
        NBTTagList result = new NBTTagList();
        for (Target cell : cells) result.appendTag(cell.tag());
        return result;
    }

    private boolean present(String key) {
        return screen.ui.shell.targetBounds(key) != null;
    }

    private void click(String key) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        HologramMuiProbeInput.nativeTarget(screen, key, event);
        record("input", event);
    }

    private void next() {
        phase++;
        ticks = 0;
        since = System.nanoTime();
    }

    private void record(String name, Object value) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("phase", phase);
        step.put("machine", current().spec.key);
        step.put("orientation", current().facing.toString());
        step.put("unformedCycle", unformedCycle);
        step.put("evidence", value instanceof NBTTagCompound || value instanceof NBTTagList ? value.toString() : value);
        steps.add(step);
    }

    void guiPost(GuiScreen gui, long frame) throws Exception {
        if (gui != screen) return;
        capturePanelFrame("presets", "config.presets", frame);
        capturePanelFrame("channels", "config.channels", frame);
        if (screen.ui.review.materialDialogOpen() && screen.ui.review.materialDialogProbeState()
            .getLong("drawnFrames") > 0) capturePanelFrame("materials", "materials.control.55", frame);
        if (screenshot == null) return;
        HologramState displayed = HologramRenderer.previewDisplayState();
        if (displayed == null || displayed.identity != screen.ui.session.state()
            .controllerView(0).identity) return;
        NBTTagCompound rendered = HologramRenderer.editor()
            .lessonInspection(displayed);
        if (!rendered.getBoolean("snapshotPresented") || rendered.getInteger("presentedVisibleCellCount") <= 0) return;
        require(
            displayed.data.getString("nativeTargetDigest")
                .equals(screen.ui.session.state().data.getString("nativeTargetDigest")),
            "Presented native frame belongs to an old target digest");
        record("rendered-native-target-frame", rendered);
        String name = "four-machine-" + screenshot + ".png";
        Minecraft mc = Minecraft.getMinecraft();
        Files.createDirectories(output.resolve("screenshots"));
        ScreenShotHelper.saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        require(
            Files.isRegularFile(
                output.resolve("screenshots")
                    .resolve(name)),
            "Natural GUI frame screenshot missing");
        record(
            "natural-draw-post",
            "screenshots/" + name + "; frame=" + frame + "; clientWorldTick=" + mc.theWorld.getTotalWorldTime());
        screenshot = null;
    }

    /** Read-only capture after the normal attached modal graph has drawn. No extra phase, click or wait gate. */
    private void capturePanelFrame(String panel, String target, long frame) throws Exception {
        String key = imageName("panel-" + panel);
        if (capturedPanels.contains(key) || screen.ui.shell.targetBounds(target) == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        Files.createDirectories(output.resolve("screenshots"));
        String name = "four-machine-" + key + ".png";
        ScreenShotHelper.saveScreenshot(output.toFile(), name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        require(
            Files.isRegularFile(
                output.resolve("screenshots")
                    .resolve(name)),
            "Natural panel PNG missing");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("png", "screenshots/" + name);
        evidence.put("panel", panel);
        evidence.put("target", target);
        evidence.put("actualBounds", screen.ui.shell.targetBounds(target));
        evidence.put("captureEvent", "GuiScreenEvent.DrawScreenEvent.Post after actual native modal graph");
        evidence.put("frame", frame);
        evidence.put("clientWorldTick", mc.theWorld.getTotalWorldTime());
        evidence.put("language", mc.gameSettings.language);
        if ("materials".equals(panel)) evidence.put(
            "actualDialogState",
            screen.ui.review.materialDialogProbeState()
                .toString());
        // Capability labels are presentation helpers; raw native metadata is deliberately not a locale assertion.
        record("natural-native-panel-frame", evidence);
        capturedPanels.add(key);
    }

    boolean finish(Minecraft mc) throws Exception {
        if (restored) return true;
        var context = HologramClientStockNative.context(mc);
        if (HologramClientStockNative.OWNER.pendingHere(context)) {
            report.put("pendingRequest", String.valueOf(HologramClientStockNative.OWNER.requestFor(screen, context)));
            HologramClientStockNative.OWNER.cancelHere(context, System.nanoTime());
            report.put("cleanupPending", true);
            if (wire != null) report.put("wire", wire.snapshot());
            write();
            return false; // Real STOP/terminal and resource settlement must precede actor restoration.
        }
        if (wire != null) {
            report.put("wire", wire.snapshot());
            wire.close();
        }
        if (fixture != null) {
            if (System.nanoTime() < cleanupAuditAt) return false;
            if (cleanup == null) cleanup = fixture.request(fixture::restore);
            if (!cleanup.isDone()) return false;
            NBTTagCompound attempt = cleanup.join(), guard = attempt.getCompoundTag("recoveryGuard");
            report.put("recoveryAudit", guard.toString());
            report.put("recoveryClear", guard.getBoolean("clear"));
            report.put("journalPending", guard.getBoolean("journalPending"));
            report.put("deliveryPending", guard.getBoolean("deliveryPending"));
            report.put("knownRecovery", guard.getBoolean("knownRecovery"));
            if (attempt.getBoolean("cleanupPending")) {
                report.put("restored", false);
                report.put("cleanupPending", true);
                report.put("passed", false);
                report.put("recoveryEntry", attempt.getString("recoveryEntry"));
                report.put("actorSnapshot", attempt.getString("actorSnapshot"));
                report.put("retainedOwnedWorld", save.toString());
                report.put("recoveryAttempt", attempt.toString());
                if (attempt.getBoolean("prepareBlocked") && !attempt.getBoolean("fixtureMutationStarted")) {
                    report.put("businessOutcome", "BLOCKED");
                    report.put("prepareBlocked", true);
                    report.put("fixtureMutationStarted", false);
                    report.put("originalStateNotBorrowed", true);
                    write();
                    return true; // Stop the owned world, retain it and its original recovery records.
                }
                cleanup = null;
                cleanupAuditAt = System.nanoTime() + 1_000_000_000L;
                write();
                return false;
            }
            if (attempt.getBoolean("originalStateUnchanged") && !attempt.getBoolean("restorationRequired")) {
                report.put("fixtureRestorationNotNeeded", true);
                report.put("restored", false);
                report.put("cleanupPending", false);
                write();
                return true;
            }
            require(
                guard.getBoolean("clear") && attempt.getBoolean("inventoryAndSlotRestored"),
                "Native restore guard did not prove complete settlement");
            record("actor-restoration", attempt);
        }
        report.put("restored", true);
        report.put("cleanupPending", false);
        report.put(
            "passed",
            fullMatrix && Boolean.TRUE.equals(report.get("allActionsPassed"))
                && Boolean.TRUE.equals(report.get("aeOnlyBuildPaid"))
                && Boolean.TRUE.equals(report.get("aeReplacementRecovered"))
                && Boolean.TRUE.equals(report.get("presetClearedToCasing"))
                && Boolean.TRUE.equals(report.get("presetRepairedAfterClear"))
                && !report.containsKey("failure"));
        restored = true;
        write();
        return true;
    }

    void fail(String failure) throws Exception {
        if (report.containsKey("failure")) return;
        report.put("failure", failure);
        report.put("passed", false);
        if (wire != null) report.put("wire", wire.snapshot());
        write();
    }

    private void write() throws Exception {
        Files.write(
            output.resolve("gtmb-four-machine-runtime.json"),
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(report)
                .getBytes(StandardCharsets.UTF_8));
    }

    private MachineCase current() {
        return cases.get(Math.min(caseIndex, cases.size() - 1));
    }

    private String imageName(String suffix) {
        return current().spec.key + "-"
            + current().facing.toString()
                .replace(' ', '-')
            + (unformedCycle ? "-unformed" : "")
            + "-"
            + suffix;
    }

    private static final class MachineCase {

        final HologramFourMachineSpec spec;
        final ExtendedFacing facing;

        MachineCase(HologramFourMachineSpec spec, ExtendedFacing facing) {
            this.spec = spec;
            this.facing = facing;
        }
    }

    private int[][] points(List<Target> cells, boolean anchor) {
        int[][] result = new int[cells.size() + (anchor ? 1 : 0)][];
        for (int i = 0; i < cells.size(); i++) result[i] = cells.get(i)
            .position();
        if (anchor) result[cells.size()] = fixture.anchor();
        return result;
    }

    private boolean sources(boolean main) throws Exception {
        if (sourcePhase == 0) {
            if (!present("control.78")) {
                click("launcher.4");
                return false;
            }
            click("control.78");
            sourcePhase++;
            return false;
        }
        if (sourcePhase == 1) {
            if (!present("control.86")) return false;
            if (!screen.ui.config.sourceEnabled("materialMe")) {
                click("control.86");
                return false;
            }
            if (screen.ui.config.sourceEnabled("materialMain") != main) {
                click("control.84");
                return false;
            }
            if (screen.ui.config.sourceEnabled("materialContainers")) {
                click("control.85");
                return false;
            }
            require(!screen.ui.config.sourceEnabled("materialContainers"), "Unreviewed container source enabled");
            require(
                screen.ui.session.state().data.getInteger("materialPriority") == 0,
                "Unexpected native source priority");
            click("config.sources.close");
            sourcePhase++;
            return false;
        }
        return !screen.ui.shell.hasModalPanel();
    }

    private boolean aeOnlyBuild() {
        return fullMatrix && unformedCycle && current().spec == HologramFourMachineSpec.EBF;
    }

    private boolean aeRecoveryReplace() {
        return fullMatrix && !unformedCycle && current().spec == HologramFourMachineSpec.EBF;
    }

    private static void requireOptionalFreeEbf(List<Target> target) {
        int hatches = 0;
        for (Target cell : target) if (cell.hatch()) {
            hatches++;
            require(
                cell.dy == 3 && GregTechAPI.METATILEENTITIES[cell.item
                    .getItemDamage()] instanceof gregtech.api.metatileentity.implementations.MTEHatchMuffler,
                "Clearing preset left optional hatch instead of casing");
        }
        require(hatches == 1, "Clearing preset lost forced EBF muffler");
    }

    private boolean postBusinessReviewPhase() {
        return phase == 5 || phase == 12 || phase == 18 || phase == 24 || phase == 28 || phase == 35 || phase == 41;
    }

    private boolean disabledActions() throws Exception {
        require(
            screen.nativeControllerState()
                .action() == HologramStockNativeWire.Operation.BUILD,
            "A disabled controller-only action changed selection");
        if (disabledActionIndex > 0) require(
            wire.beginCount() == disabledActionBegins && !HologramClientStockNative.OWNER
                .pendingHere(HologramClientStockNative.context(Minecraft.getMinecraft())),
            "A grey controller-only action dispatched business work");
        if (disabledActionIndex >= 3) return true;
        String key = "control." + (1721 + disabledActionIndex);
        Object registry = HologramTestLabUiDiagnostics.readDiagnosticField(screen.ui.shell, "targets");
        Object registered = ((Map<?, ?>) registry).get(key);
        require(registered instanceof List, "Missing grey native action widget");
        com.cleanroommc.modularui.api.widget.IWidget widget = null;
        int[] bounds = null;
        for (Object candidate : (List<?>) registered) {
            int[] rect = screen.ui.shell.nativeWidgetBounds((com.cleanroommc.modularui.api.widget.IWidget) candidate);
            if (rect != null) {
                widget = (com.cleanroommc.modularui.api.widget.IWidget) candidate;
                bounds = rect;
            }
        }
        require(widget != null && bounds != null, "Grey action is not visibly rendered");
        java.lang.reflect.Method available = widget.getClass()
            .getDeclaredMethod("available");
        available.setAccessible(true);
        require(
            Boolean.FALSE.equals(available.invoke(widget)),
            "Controller-only action is not actually grey/unavailable");
        int x = bounds[0] + bounds[2] / 2, y = bounds[1] + bounds[3] / 2;
        var nativeScreen = screen.getScreen();
        nativeScreen.getContext()
            .updateState(x, y, 0);
        nativeScreen.onFrameUpdate();
        boolean hit = false;
        for (var hovered : widget.getPanel()
            .getHovering()) if (hovered.getElement() == widget) hit = true;
        require(hit, "Grey action native hit test missed");
        disabledActionBegins = wire.beginCount();
        boolean pre = nativeScreen.onMouseInputPre(0, true), handled = false;
        try {
            if (!pre) handled = nativeScreen.onMousePressed(0);
        } finally {
            if (!nativeScreen.onMouseInputPre(0, false)) nativeScreen.onMouseRelease(0);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("target", key);
        input.put("bounds", bounds);
        input.put("available", false);
        input.put("nativeHit", hit);
        input.put("pressPreConsumed", pre);
        input.put("pressHandled", handled);
        record("grey-action-native-input", input);
        disabledActionIndex++;
        return false;
    }

    private int glassSignal() {
        for (var glass : gregtech.api.util.GlassTier.getGlassList()) {
            if (glass == null) continue;
            Integer tier = gregtech.api.util.GlassTier.getGlassBlockTier(glass.getLeft(), glass.getRight());
            if (tier != null && tier >= 4) {
                int value = gregtech.api.util.GlassTier.getGlassChannelValue(glass.getLeft(), glass.getRight());
                require(value > 0, "Live EV glass channel unavailable");
                return value;
            }
        }
        throw new IllegalStateException("Registered .265 glass list contains no real EV glass");
    }

    private boolean initialGlass() throws Exception {
        if (editPhase == 0) {
            if (!present("control.60")) {
                click("launcher.3");
                return false;
            }
            click("control.60");
            editPhase++;
            return false;
        }
        if (editPhase == 1) {
            if (!present("control.67")) return false;
            screen.ui.config.channelNameField()
                .setText("glass");
            screen.ui.config.channelValueField()
                .setText(Integer.toString(glassSignal()));
            editPhase++;
            return false;
        }
        if (editPhase == 2) {
            click("control.67");
            editPhase++;
            return false;
        }
        if (editPhase == 3) {
            if (!targetReady("UNFORMED")) return false;
            click("config.channels.close");
            editPhase++;
            return false;
        }
        return !screen.ui.shell.hasModalPanel();
    }

    private List<String[]> changedChannels() {
        List<String[]> result = new ArrayList<>();
        if (current().spec == HologramFourMachineSpec.EBF || current().spec == HologramFourMachineSpec.FLUID_EXTRACTOR)
            result.add(new String[] { "coil", "3" });
        if (current().spec == HologramFourMachineSpec.FLUID_EXTRACTOR) {
            result.add(new String[] { "solenoid", "2" });
            result.add(new String[] { "glass", Integer.toString(glassSignal()) });
        }
        if (current().spec == HologramFourMachineSpec.PRECISE_ASSEMBLER) {
            result.add(new String[] { "unit_casing", "3" });
            result.add(new String[] { "machine_casing", "4" });
            result.add(new String[] { "glass", Integer.toString(glassSignal()) });
        }
        return result;
    }

    private boolean changedTarget() throws Exception {
        switch (editPhase) {
            case 0:
                if (!present("control.60")) {
                    click("launcher.3");
                    return false;
                }
                click("control.60");
                editPhase++;
                return false;
            case 1:
                if (!present("control.30")) return false;
                screen.ui.config.mainField()
                    .setText("2");
                editPhase++;
                return false;
            case 2:
                click("control.30");
                editPhase++;
                return false;
            case 3:
                if (!targetReady("FORMED") || screen.ui.session.state().data.getInteger("main") != 2) return false;
                if (channelIndex >= changedChannels().size()) {
                    click("config.channels.close");
                    editPhase = 6;
                    return false;
                }
                String[] signal = changedChannels().get(channelIndex);
                screen.ui.config.channelNameField()
                    .setText(signal[0]);
                screen.ui.config.channelValueField()
                    .setText(signal[1]);
                editPhase++;
                return false;
            case 4:
                click("control.67");
                editPhase++;
                return false;
            case 5:
                if (!targetReady("FORMED") || screen.ui.session.state().data.getCompoundTag("channels")
                    .getInteger(changedChannels().get(channelIndex)[0])
                    != Integer.parseInt(changedChannels().get(channelIndex)[1])) return false;
                channelIndex++;
                editPhase = 3;
                return false;
            case 6:
                if (screen.ui.shell.hasModalPanel()) return false;
                if (!present("control.94")) {
                    click("launcher.4");
                    return false;
                }
                click("control.94");
                editPhase++;
                return false;
            case 7:
                if (!present("control.1702")) return false;
                click("control.1702");
                editPhase++;
                return false;
            case 8:
                if (!present("control.106")) return false;
                if (!allowPresetDowngrade()) return false;
                click("control.106");
                editPhase++;
                return false;
            default:
                return !screen.ui.shell.hasModalPanel();
        }
    }

    private boolean allowPresetDowngrade() throws Exception {
        if (!screen.ui.config.nativePresetDowngrade()) {
            click("control.105");
            return false;
        }
        require(screen.ui.config.nativePresetDowngrade(), "Native preset downgrade toggle did not remain enabled");
        record("mv-preset-ui-downgrade", "tier=2; actual native preset UI downgrade=true; normal control.106 follows");
        return true;
    }

    private void rejectFailedNativeTarget(String operation) {
        NBTTagCompound state = screen.ui.session.state().data;
        String reason = state.getString("nativeTargetReason");
        require(
            state.getBoolean("capturePending") || state.getBoolean("nativeTargetReady") || reason.isEmpty(),
            operation + " target rejected by native validation: " + reason);
    }

    private void requireMixedMvHatches(List<Target> targets) {
        boolean maintenance = false;
        for (Target cell : targets) if (cell.hatch()) {
            int id = cell.item.getItemDamage();
            int tier = ((MTEHatch) GregTechAPI.METATILEENTITIES[id]).mTier;
            if (id == 90) {
                require(tier == 1, "Native maintenance90 is not the reviewed fixed LV device");
                maintenance = true;
            } else require(
                tier == 2 && (id == 42 || id == 52 || id == 62 || id == 72 || id == 82 || id == 92),
                "Allowed downgrade selected a non-MV standard hatch instead of fixed LV maintenance: " + id);
        }
        require(maintenance, "MV mixed native target omitted required maintenance90");
    }

    private static Map<NBTTagCompound, Long> readCounts(NBTTagList rows) {
        Map<NBTTagCompound, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound key = (NBTTagCompound) rows.getCompoundTagAt(i)
                .copy();
            long amount = key.getLong("amount");
            key.removeTag("amount");
            result.merge(key, amount, Long::sum);
        }
        return result;
    }

    private static Map<String, NBTTagCompound> actualCells(NBTTagCompound audit) {
        Map<String, NBTTagCompound> result = new LinkedHashMap<>();
        NBTTagList cells = audit.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound n = cells.getCompoundTagAt(i);
            result.put(java.util.Arrays.toString(n.getIntArray("position")), n);
        }
        return result;
    }

    private static void sameWorld(NBTTagCompound before, NBTTagCompound after) {
        Map<String, NBTTagCompound> a = actualCells(before), b = actualCells(after);
        require(
            a.keySet()
                .equals(b.keySet()),
            "Configuration changed actual footprint");
        for (var entry : a.entrySet()) {
            NBTTagCompound first = (NBTTagCompound) entry.getValue()
                .copy(),
                second = (NBTTagCompound) b.get(entry.getKey())
                    .copy();
            first.removeTag("worldTick");
            second.removeTag("worldTick");
            require(first.equals(second), "UI target planning wrote actual world at " + entry.getKey());
        }
    }

    private static void retainedTiles(NBTTagCompound before, NBTTagCompound after) {
        Map<String, NBTTagCompound> b = actualCells(after);
        for (var entry : actualCells(before).entrySet()) {
            NBTTagCompound first = entry.getValue();
            if (first.getString("tileClass")
                .isEmpty()) continue;
            NBTTagCompound second = b.get(entry.getKey());
            require(
                second != null && first.getInteger("liveTileIdentity") == second.getInteger("liveTileIdentity"),
                "REPAIR replaced matching native TE at " + entry.getKey());
            require(
                first.getTagList("nativeInventory", 10)
                    .equals(second.getTagList("nativeInventory", 10))
                    && first.getLong("storedEU") == second.getLong("storedEU"),
                "REPAIR overwrote native inventory/energy");
        }
    }

    private void firstHatchSkins(NBTTagCompound audit, List<Target> targets, List<Target> previous) {
        NBTTagCompound trace = audit.getCompoundTag("worldWrites");
        require(trace.getBoolean("complete"), "Physical write trace incomplete: " + trace);
        Map<String, Target> old = new LinkedHashMap<>();
        for (Target cell : previous) old.put(cell.key(), cell);
        NBTTagList writes = trace.getTagList("writes", 10);
        for (Target cell : targets) if (cell.hatch()) {
            int palette = HologramFourMachinePalette
                .texture(current().spec, current().facing, cell.dx, cell.dy, cell.dz, screen.ui.session.state().data);
            Target before = old.get(cell.key());
            if (before != null && before.same(cell) && before.display.equals(cell.display)) continue;
            boolean found = false;
            for (int i = 0; i < writes.tagCount(); i++) {
                NBTTagCompound written = writes.getCompoundTagAt(i);
                if (!java.util.Arrays.equals(cell.position(), written.getIntArray("position"))) continue;
                if (written.getInteger("metaTileId") == cell.item.getItemDamage()
                    && written.getInteger("front") == cell.facing
                    && written.getInteger("texturePage") == (palette >> 7)
                    && written.getInteger("textureIndex") == (palette & 127)) found = true;
            }
            require(found, "No actual first-write hatch ID/facing/skin witness at " + cell.key());
        }
    }

    private void controllerLast(NBTTagCompound audit) {
        NBTTagCompound trace = audit.getCompoundTag("worldWrites");
        require(trace.getBoolean("complete"), "Demolition physical write evidence incomplete");
        NBTTagList writes = trace.getTagList("writes", 10);
        long anchorSequence = -1, bodySequence = -1;
        for (int i = 0; i < writes.tagCount(); i++) {
            NBTTagCompound cell = writes.getCompoundTagAt(i);
            if (!"minecraft:air".equals(cell.getString("block"))) continue;
            if (java.util.Arrays.equals(fixture.anchor(), cell.getIntArray("position")))
                anchorSequence = cell.getLong("writeSequence");
            else bodySequence = Math.max(bodySequence, cell.getLong("writeSequence"));
        }
        require(
            anchorSequence > bodySequence && bodySequence > 0,
            "Controller was not physically removed after every body cell");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
