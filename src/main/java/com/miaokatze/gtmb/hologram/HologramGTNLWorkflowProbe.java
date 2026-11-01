package com.miaokatze.gtmb.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testlab.TestLab;
import com.miaokatze.gtmb.testlab.TestLabWorldProvider;

import cpw.mods.fml.common.Loader;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;

/** Five independent, paid, server-domain workflows. Never supplies a network connection or bypasses admission. */
public final class HologramGTNLWorkflowProbe {

    private static final String PREFIX = "com.science.gtnl.common.machine.multiblock.";
    private static final String GT_HATCH = "gregtech.api.metatileentity.implementations.";
    private static final String NL_HATCH = "com.science.gtnl.common.machine.hatch.";
    private static final int WIDTH = 64, HEIGHT = 32, DEPTH = 32;

    private HologramGTNLWorkflowProbe() {}

    public static Run begin(WorldServer world, FakePlayer actor, int x, int y, int z, Path artifacts)
        throws IOException {
        requireOwned(world, actor, x, y, z);
        return new Run(world, actor, x, y, z, artifacts);
    }

    public static final class Run implements AutoCloseable {

        private final WorldServer world;
        private final FakePlayer actor;
        private final int x, y, z, sx, dx, cy, cz;
        private final String runId;
        private final Path artifacts;
        private final NBTTagList originalInventory;
        private final int originalSlot;
        private final double originalX, originalY, originalZ;
        private final float originalYaw, originalPitch;
        private final NBTTagCompound report = new NBTTagCompound();
        private final NBTTagList rows = new NBTTagList(), timings = new NBTTagList();
        private final Map<String, int[]> touched = new LinkedHashMap<>();
        private final List<Spec> specs = specs();
        private final List<Edit> edits = new ArrayList<>();
        private final Map<HologramBlueprintResources.ExactKey, Long> demand = new LinkedHashMap<>();
        private HologramSession source, placement, live, transition;
        private HologramBlueprintProjection.Population population;
        private HologramAreaSignalInference inference;
        private MTEEnhancedMultiBlockBase<?> builtMachine;
        private HologramPresets.Preset loaded;
        private NBTTagCompound row, buildRequest;
        private List<int[]> cleanup;
        private int preflight, index, phase, editIndex, cleanupIndex, failures, caseSteps, captureSlices, jobSlices;
        private boolean originalEmpty, finished, closed;
        private long maximumStep;

        private Run(WorldServer world, FakePlayer actor, int x, int y, int z, Path artifacts) throws IOException {
            this.world = world;
            this.actor = actor;
            this.x = x;
            this.y = y;
            this.z = z;
            sx = x + 12;
            dx = x + 44;
            cy = y + 23;
            cz = z + 3;
            runId = TestLab.status()
                .getString("runId");
            Path owned = world.getSaveHandler()
                .getWorldDirectory()
                .toPath()
                .toRealPath()
                .resolve("gtmb-testlab");
            this.artifacts = artifacts.toAbsolutePath()
                .normalize();
            require(
                this.artifacts.equals(owned.resolve("workflow-" + runId)) && !Files.isSymbolicLink(owned)
                    && !Files.isSymbolicLink(this.artifacts),
                "Workflow artifacts must stay in the current owned lab run directory");
            originalInventory = actor.inventory.writeToNBT(new NBTTagList());
            originalSlot = actor.inventory.currentItem;
            originalX = actor.posX;
            originalY = actor.posY;
            originalZ = actor.posZ;
            originalYaw = actor.rotationYaw;
            originalPitch = actor.rotationPitch;
            require(
                Loader.instance()
                    .getIndexedModList()
                    .containsKey("sciencenotleisure")
                    && "0.2.7-rc2.1".equals(
                        Loader.instance()
                            .getIndexedModList()
                            .get("sciencenotleisure")
                            .getVersion())
                    && Loader.instance()
                        .getIndexedModList()
                        .containsKey("gtnllib")
                    && "1.1.1".equals(
                        Loader.instance()
                            .getIndexedModList()
                            .get("gtnllib")
                            .getVersion()),
                "Workflow native fixtures require the exact reviewed GTNL rc2.1 and GTNLLib 1.1.1");
            report.setBoolean("applicable", true);
            report.setBoolean("actualWorld", true);
            report.setBoolean("survivalActor", !actor.capabilities.isCreativeMode);
            report.setBoolean("serverDomainOnly", true);
            report.setBoolean("actualNetty", false);
            report.setBoolean("clientGuiVerified", false);
            report.setBoolean("fullRecipeVerified", false);
            report.setBoolean("nativeControllerTicksSuppressed", true);
            report.setString(
                "nativeTickScope",
                "Owned fixture controllers keep real chunk identity while automatic ticks are suppressed; final checkMachine is invoked explicitly on the paid structure");
            report.setString(
                "scope",
                "Production Session/BuildPreparation, editor, artifact codec, exact material plan, reviewed paid jobs and native checkMachine");
            report.setString(
                "scheduler",
                "Owned FakePlayer domain continuations are driven one bounded step per TestLab tick; no connected-session scheduler is fabricated");
            report.setString(
                "previewScope",
                "Synthetic 640x640 PNG fixture exercises artifact binding only; no client rendering claim");
            report.setString(
                "performanceCoverage",
                "PARTIAL: actual phase samples only, no fabricated 32-sample percentiles or TPS");
            report.setString(
                "cancellationCoverage",
                "NOT_RUN: replay and stale-request checks are separate from in-flight cancellation");
            report.setInteger("dimension", world.provider.dimensionId);
            report.setIntArray("zoneMin", new int[] { x, y, z });
            report.setIntArray("zoneSize", new int[] { WIDTH, HEIGHT, DEPTH });
            report.setTag("cases", rows);
            report.setTag("stepTimings", timings);
        }

        public boolean step() throws Exception {
            require(!closed, "GTNL workflow probe is closed");
            requireCurrent();
            long started = System.nanoTime();
            int measuredPhase = phase;
            try {
                if (!originalEmpty) return preflight();
                if (index == specs.size()) {
                    finished = true;
                    report.setBoolean("checksComplete", true);
                    return true;
                }
                try {
                    if (row == null) startCase();
                    require(++caseSteps <= 6000, "Finite workflow case step budget exhausted");
                    advance();
                } catch (Exception | LinkageError failure) {
                    if (phase == 10) throw failure;
                    fail(failure);
                    prepareCleanup();
                }
                return false;
            } finally {
                long elapsed = System.nanoTime() - started;
                maximumStep = Math.max(maximumStep, elapsed);
                report.setLong("maximumStepNanos", maximumStep);
                if (timings.tagCount() < 4096) {
                    NBTTagCompound sample = new NBTTagCompound();
                    sample.setInteger("case", index);
                    sample.setInteger("phase", measuredPhase);
                    sample.setLong("nanos", elapsed);
                    timings.appendTag(sample);
                }
            }
        }

        private boolean preflight() throws IOException {
            int total = WIDTH * HEIGHT * DEPTH;
            int end = Math.min(total, preflight + 512);
            for (; preflight < end; preflight++) {
                int px = x + preflight % WIDTH;
                int py = y + preflight / (WIDTH * DEPTH);
                int pz = z + preflight / WIDTH % DEPTH;
                require(
                    world.blockExists(px, py, pz) && world.isAirBlock(px, py, pz)
                        && world.getTileEntity(px, py, pz) == null,
                    "New GTNL workflow region is not originally empty");
            }
            if (preflight == total) {
                originalEmpty = true;
                report.setBoolean("originalZoneVerifiedEmpty", true);
            }
            return false;
        }

        private void startCase() throws Exception {
            Spec spec = specs.get(index);
            row = new NBTTagCompound();
            row.setString("name", spec.family + "/NEW_TEMPLATE");
            row.setString("status", "RUNNING");
            row.setString("controllerClass", spec.type);
            if ("assembler_matrix".equals(spec.family)) row.setString(
                "physicalVariant",
                "Wall, pattern, crafter, singularity and speed cores; the damage18 native lifecycle has its separate executable NativeProbe receipt");
            row.setTag("stages", new NBTTagList());
            rows.appendTag(row);
            phase = caseSteps = captureSlices = jobSlices = editIndex = 0;
            edits.clear();
            demand.clear();
            actor.setPositionAndRotation(sx + .5, cy + 1, cz - 2, 0, 0);
            int id = registered(spec.type, -1);
            BaseMetaTileEntity tile = placeFixtureController(id, sx, cy, cz);
            source = new HologramSession(actor, sx, cy, cz, true, ForgeDirection.NORTH.ordinal());
            source.open = true;
            source.captureIntent = StructureSources.CaptureIntent.NEW_TEMPLATE;
            source.materialSettings.main = true;
            source.materialSettings.containers = source.materialSettings.me = false;
            HologramBuildPreparation.scan(source);
            row.setInteger("controllerId", id);
            row.setIntArray("source", new int[] { sx, cy, cz });
            row.setIntArray("destination", new int[] { dx, cy, cz });
            require(source.machine == tile.getMetaTileEntity(), "Actual controller discovery differs");
            phase = 1;
        }

        private void advance() throws Exception {
            Spec spec = specs.get(index);
            switch (phase) {
                case 1:
                    if (!captureReady(source)) return;
                    stage("native_controller_projection_capture");
                    require(
                        source.capture.captureIntent == StructureSources.CaptureIntent.NEW_TEMPLATE,
                        "Controller projection did not use new-template intent");
                    NBTTagCompound configure = configuration(source, spec.channels);
                    require(
                        HologramActionDispatcher.configure(source, configure),
                        "Production parameter edit refused: " + source.status);
                    phase = 2;
                    return;
                case 2:
                    if (!captureReady(source)) return;
                    stage("parameter_edit_and_redeclaration");
                    row.setTag("selectedChannels", source.channels.copy());
                    prepareEdits(spec);
                    phase = 3;
                    return;
                case 3:
                    if (source.localPlanPending) {
                        HologramDeferredPlanRefresh.tick(source);
                        return;
                    }
                    if (editIndex < edits.size()) {
                        applyEdit(source, edits.get(editIndex++));
                        return;
                    }
                    source.scope = 0;
                    source.selectedIndices.clear();
                    HologramResourcePlanner.refreshPlan(source);
                    stage("candidate_material_and_hatch_direction_edits");
                    require(recordSelections("source_edited", source) == 0, "Production edits differ before save");
                    exportReload(spec);
                    phase = 4;
                    return;
                case 4:
                    if (population == null) createPlacement();
                    if (!population.step(128, System.nanoTime() + HologramWorkBudget.SLICE_NANOS)) return;
                    require(
                        !placement.capture.incomplete,
                        "Reloaded blueprint population failed: " + placement.capture.failure);
                    HologramPresets.Structure member = loaded.structures.get(0);
                    placement.blueprintActiveStructure = member.id;
                    HologramBlueprintData.applyStructureConfiguration(placement, member);
                    require(
                        recordSelections("reloaded_before_rescan", placement) == 0,
                        "Saved material or facing differs after production disk reload");
                    require(
                        HologramVirtualStructureEditing.prepare(placement, placement.main, placement.channels, false),
                        "Saved GTNL source re-admission refused: " + placement.status);
                    phase = 5;
                    return;
                case 5:
                    if (HologramVirtualStructureEditing.pending(placement)) {
                        HologramVirtualStructureEditing.advance(placement);
                        return;
                    }
                    require(
                        placement.capture != null && !placement.capture.incomplete && placement.capture.sourceCurrent(),
                        "Reloaded semantic declaration is unavailable: " + placement.status);
                    placement.blueprintActiveStructure = "";
                    placement.scope = 0;
                    HologramResourcePlanner.refreshPlan(placement);
                    require(!placement.localPlanPending, "Reloaded material plan remains incomplete");
                    stage("reloaded_blueprint_semantic_readmission");
                    int selectionMismatches = recordSelections("redeclared_before_admission", placement);
                    row.setInteger("reloadedSelectionMismatches", selectionMismatches);
                    require(
                        selectionMismatches == 0,
                        "Pure semantic re-admission replaced saved material or hatch-facing choices");
                    prepareDemandAndNegatives();
                    phase = 6;
                    return;
                case 6:
                    supplyDemand();
                    HologramResourcePlanner.refreshPlan(placement);
                    require(
                        placement.planMissing == 0,
                        "Exact supplied material does not satisfy production plan: " + placement.status);
                    require(
                        HologramPlanPolicy.constructionAllowed(placement),
                        "Funded plan lacks construction qualification: " + placement.status);
                    staleAndStart();
                    phase = 7;
                    return;
                case 7:
                    if (placement.job == 1 || placement.job == 2) {
                        HologramPlacementJobs.tickJob(placement);
                        freezePaidController();
                        jobSlices++;
                        return;
                    }
                    require(
                        placement.job == 3 && placement.jobMissingCells == 0
                            && placement.jobProtectedCells == 0
                            && placement.jobUnsupportedCells == 0,
                        "Production paid job did not complete: " + placement.status);
                    verifyPaidAndReplay();
                    nativeCheck(spec);
                    stage("paid_survival_construction_and_native_check");
                    inference = new HologramAreaSignalInference(builtMachine, HologramSessionPolicy.trigger(placement));
                    phase = 8;
                    return;
                case 8:
                    if (!inference.step(System.nanoTime() + HologramWorkBudget.SLICE_NANOS)) return;
                    row.setTag("areaInference", inference.diagnostics());
                    require(
                        inference.verifiedScope(),
                        "Actual-world bounded region inference was incomplete or ambiguous");
                    stage("actual_world_region_declaration_inference");
                    phase = 9;
                    return;
                case 9:
                    if ("aeep".equals(spec.family)) {
                        if (!liveAirBoundary()) return;
                        stage("aeep_live_air_snapshot_rejects_new_construction");
                    }
                    row.setString("status", "PASS");
                    prepareCleanup();
                    return;
                case 10:
                    cleanupStep();
                    return;
                default:
                    throw new IOException("Unknown finite GTNL workflow phase");
            }
        }

        private boolean captureReady(HologramSession session) throws Exception {
            if (session.capturePending) {
                HologramBuildPreparation.tickPending(session);
                captureSlices++;
                return false;
            }
            require(
                session.capture != null && !session.capture.incomplete
                    && !session.capture.pending
                    && session.capture.sourceCurrent(),
                "Production source capture failed: " + session.status);
            require(
                session.capture.nativeConstructCalls == 0 && session.capture.nativeDefinitionCalls == 0
                    && session.capture.nativeHintCalls == 0,
                "GTMB discovery ran an upstream callback");
            require(
                session.capture.cells.size() <= 2048,
                "Reviewed workflow geometry exceeded its finite fixture bound");
            for (HologramCapture.Cell cell : session.capture.cells)
                require(inside(cell.x, cell.y, cell.z), "Reviewed machine geometry leaves its owned workflow region");
            return true;
        }

        private void prepareEdits(Spec spec) throws Exception {
            String shell = "assembler_matrix".equals(spec.family) ? "assembler.shell"
                : "large_steam_mixer".equals(spec.family) ? "mixer.shell"
                    : "aeep".equals(spec.family) ? "aeep.shell" : "precision.unit";
            if ("nuclear_reactor".equals(spec.family)) {
                edits.add(
                    new Edit(
                        findCell(source, "nuclear.core", 0),
                        machineStack(registered(NL_HATCH + "NuclearItemBus", -1)),
                        ForgeDirection.UP.ordinal()));
                edits.add(
                    new Edit(
                        findCell(source, "nuclear.core", 1),
                        machineStack(registered(NL_HATCH + "NuclearFluidHatch", -1)),
                        ForgeDirection.UP.ordinal()));
                edits.add(
                    new Edit(
                        findCell(source, "nuclear.shell", 0),
                        machineStack(registered(GT_HATCH + "MTEHatchOutput", 1)),
                        ForgeDirection.NORTH.ordinal()));
                return;
            }
            List<ItemStack> hatches = new ArrayList<>();
            if ("large_steam_mixer".equals(spec.family)) hatches.add(machineStack(22518));
            hatches.add(machineStack(registered(GT_HATCH + "MTEHatchMaintenance", -1)));
            hatches.add(machineStack(registered(GT_HATCH + "MTEHatchInputBus", 1)));
            if (!"aeep".equals(spec.family)) hatches.add(machineStack(registered(GT_HATCH + "MTEHatchOutputBus", 1)));
            if (!"assembler_matrix".equals(spec.family))
                hatches.add(machineStack(registered(GT_HATCH + "MTEHatchInput", 1)));
            if ("aeep".equals(spec.family) || "large_steam_mixer".equals(spec.family))
                hatches.add(machineStack(registered(GT_HATCH + "MTEHatchOutput", 1)));
            if (!"large_steam_mixer".equals(spec.family))
                hatches.add(machineStack(registered(GT_HATCH + "MTEHatchEnergy", 1)));
            for (int i = 0; i < hatches.size(); i++)
                edits.add(new Edit(findCell(source, shell, i), hatches.get(i), ForgeDirection.NORTH.ordinal()));
            if ("assembler_matrix".equals(spec.family)) {
                for (int i = 0; i < 4; i++) {
                    int damage = new int[] { 6, 7, 8, 9 }[i];
                    int cell = findCell(source, "assembler.core", i);
                    ItemStack chosen = null;
                    for (ItemStack candidate : HologramCellPolicy.candidates(source, source.capture.cells.get(cell)))
                        if (candidate.getItemDamage() == damage) {
                            chosen = candidate;
                            break;
                        }
                    require(chosen != null, "Reviewed AssemblerMatrix core material is absent");
                    edits.add(new Edit(cell, chosen, -1));
                }
            }
        }

        private void applyEdit(HologramSession session, Edit edit) throws Exception {
            HologramCapture.Cell cell = session.capture.cells.get(edit.index);
            ItemStack chosen = null;
            for (ItemStack candidate : HologramCellPolicy.candidates(session, cell))
                if (HologramValues.sameMaterial(candidate, edit.target)) {
                    chosen = candidate;
                    break;
                }
            require(chosen != null, "Required real hatch/material is not in GTMB's reviewed candidate set");
            NBTTagCompound action = new NBTTagCompound();
            action.setIntArray("indices", new int[] { edit.index });
            HologramBlueprintEditor.pinSelection(session, action, edit.index, chosen);
            if (edit.facing >= 0) {
                boolean allowed = false;
                for (int facing : HologramCellPolicy.legalFacings(session, cell, chosen))
                    allowed |= facing == edit.facing;
                require(allowed, "Required hatch direction is not admitted by the production declaration");
                HologramResourcePlanner.freezeAllocations(session);
                session.facingPins.put(HologramValues.position(cell), edit.facing);
                HologramBlueprintEditor.refreshEdited(session, java.util.Collections.singleton(edit.index));
            }
            require(
                HologramValues.sameMaterial(chosen, HologramCellPolicy.targetFor(session, cell, edit.index)),
                "Production material edit did not retain the chosen exact identity");
        }

        private void exportReload(Spec spec) throws Exception {
            HologramPresets.Preset preset = HologramBlueprintProjection
                .blueprint(source, "GTNL workflow " + spec.family);
            if ("aeep".equals(spec.family)) recordBartWorksMaterials(preset);
            preset.id = java.util.UUID.randomUUID()
                .toString();
            preset.authorName = actor.getCommandSenderName();
            preset.authorUuid = actor.getUniqueID()
                .toString();
            preset.createdAt = System.currentTimeMillis();
            HologramBlueprintVersions.stampSaved(preset);
            HologramPresets.validate(preset);
            byte[] before = HologramBlueprintCompactCodec.encode(preset, (done, total) -> {});
            Files.createDirectories(artifacts);
            Path path = artifacts.resolve(spec.family + ".json");
            require(
                !Files.exists(path) && !Files.exists(HologramBlueprintArtifact.previewPath(path)),
                "Workflow artifact path already exists");
            HologramBlueprintSharing.write(path, preset, preview());
            loaded = HologramBlueprintSharing.read(path);
            byte[] after = HologramBlueprintCompactCodec.encode(loaded, (done, total) -> {});
            require(Arrays.equals(before, after), "Production save/export/read changed frozen blueprint data");
            require(
                loaded.semanticNbt != null && preset.semanticNbt.equals(loaded.semanticNbt),
                "Semantic provenance was lost on disk reload");
            HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact.readPair(path);
            row.setString("artifact", path.toString());
            row.setString("jsonSha256", pair.jsonSha256);
            row.setString("pngSha256", pair.pngSha256);
            row.setInteger("savedCells", loaded.cells.size());
            int highDamage = 0;
            for (HologramPresets.Cell cell : loaded.cells) if (cell.damage > 15) highDamage++;
            row.setInteger("savedFullDamageCells", highDamage);
            if ("aeep".equals(spec.family))
                require(highDamage >= 39, "AEEP high-damage BW casing identity was truncated");
            stage("production_projection_export_save_reload");
        }

        private void recordBartWorksMaterials(HologramPresets.Preset preset) {
            Map<String, NBTTagCompound> materials = new LinkedHashMap<>();
            for (int i = 0; i < preset.cells.size(); i++) {
                HologramCapture.Cell declaration = source.capture.cells.get(i);
                if (!(declaration.element instanceof HologramGTNLElement)) continue;
                String id = ((HologramGTNLElement) declaration.element).id;
                if (!"aeep.bolted".equals(id) && !"aeep.rebolted".equals(id)) continue;
                NBTTagCompound identity = materials.get(id);
                if (identity == null) {
                    HologramPresets.Cell saved = preset.cells.get(i);
                    ItemStack paid = saved.stack();
                    identity = new NBTTagCompound();
                    identity.setString("elementId", id);
                    identity.setString("block", saved.block);
                    identity.setString(
                        "blockClass",
                        saved.block()
                            .getClass()
                            .getName());
                    identity.setString("item", saved.item);
                    identity.setString(
                        "itemClass",
                        paid.getItem()
                            .getClass()
                            .getName());
                    identity.setInteger("numericItemId", Item.getIdFromItem(paid.getItem()));
                    identity.setInteger("fullItemDamage", saved.damage);
                    identity.setInteger("plannedWorldMetadata", saved.metadata);
                    identity.setIntArray("firstRelativePosition", new int[] { saved.x, saved.y, saved.z });
                    identity.setString(
                        "sourceWorldBlock",
                        String.valueOf(
                            Block.blockRegistry
                                .getNameForObject(world.getBlock(declaration.x, declaration.y, declaration.z))));
                    identity.setInteger(
                        "sourceWorldMetadata",
                        world.getBlockMetadata(declaration.x, declaration.y, declaration.z));
                    TileEntity actual = world.getTileEntity(declaration.x, declaration.y, declaration.z);
                    identity.setString(
                        "sourceWorldTileClass",
                        actual == null ? ""
                            : actual.getClass()
                                .getName());
                    identity.setString(
                        "expectedNativeTileClass",
                        "bartworks.system.material."
                            + ("aeep.bolted".equals(id) ? "BWTileEntityMetaGeneratedBlocksCasing"
                                : "BWTileEntityMetaGeneratedBlocksCasingAdvanced"));
                    identity.setBoolean("savedTilePayloadPresent", saved.tileNbt != null && !saved.tileNbt.isEmpty());
                    materials.put(id, identity);
                }
                identity.setInteger("cells", identity.getInteger("cells") + 1);
            }
            NBTTagList identities = new NBTTagList();
            for (NBTTagCompound identity : materials.values()) identities.appendTag(identity);
            row.setTag("plannedBartWorksMaterials", identities);
        }

        private void createPlacement() throws Exception {
            placement = new HologramSession(actor, dx, cy, cz, false, ForgeDirection.NORTH.ordinal());
            placement.virtual = placement.virtualPlacement = placement.virtualLocked = true;
            placement.open = true;
            placement.virtualPreset = loaded;
            placement.materialSettings.main = true;
            placement.materialSettings.containers = placement.materialSettings.me = false;
            placement.materialContext = new HologramMaterials.Context(
                actor,
                placement.materialSettings,
                placement.slot);
            population = new HologramBlueprintProjection.Population(placement);
        }

        private int recordSelections(String stage, HologramSession session) {
            NBTTagCompound checkpoint = new NBTTagCompound();
            checkpoint.setString("stage", stage);
            checkpoint.setTag("sessionState", sessionState(session));
            NBTTagList selections = new NBTTagList();
            int mismatches = 0;
            for (Edit edit : edits) {
                HologramCapture.Cell original = source.capture.cells.get(edit.index);
                int px = session.x + original.x - source.x;
                int py = session.y + original.y - source.y;
                int pz = session.z + original.z - source.z;
                HologramCapture.Cell current = session.capture.positions.get(px + "," + py + "," + pz);
                NBTTagCompound entry = new NBTTagCompound();
                entry.setIntArray(
                    "relativePosition",
                    new int[] { original.x - source.x, original.y - source.y, original.z - source.z });
                entry.setTag("selectedMaterial", edit.target.writeToNBT(new NBTTagCompound()));
                entry.setInteger("selectedFacing", edit.facing);
                boolean retained = false;
                if (current != null) {
                    int cellIndex = HologramSessionPolicy.cellIndex(session, current);
                    entry.setTag("current", cellIdentity(session, current, cellIndex));
                    retained = HologramValues
                        .sameMaterial(edit.target, HologramCellPolicy.targetFor(session, current, cellIndex))
                        && (edit.facing < 0
                            || session.facingPins.getOrDefault(HologramValues.position(current), -1) == edit.facing);
                }
                entry.setBoolean("retained", retained);
                if (!retained) mismatches++;
                selections.appendTag(entry);
            }
            checkpoint.setTag("cells", selections);
            checkpoint.setInteger("mismatches", mismatches);
            NBTTagList checkpoints = row.getTagList("selectionCheckpoints", 10);
            checkpoints.appendTag(checkpoint);
            row.setTag("selectionCheckpoints", checkpoints);
            return mismatches;
        }

        private NBTTagCompound cellIdentity(HologramSession session, HologramCapture.Cell cell, int cellIndex) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("index", cellIndex);
            entry.setInteger("stableIndex", cell.stableIndex);
            entry.setIntArray("position", new int[] { cell.x, cell.y, cell.z });
            entry.setString("role", cell.role);
            entry.setString("cellStatus", cell.status);
            entry.setString("declaredBlock", String.valueOf(Block.blockRegistry.getNameForObject(cell.block)));
            entry.setInteger("declaredMetadata", cell.meta);
            entry.setString("savedItem", cell.blueprintItem);
            entry.setInteger("savedFullDamage", cell.blueprintDamage);
            entry.setInteger("facingPin", session.facingPins.getOrDefault(HologramValues.position(cell), -1));
            entry.setInteger("plannedFacing", session.planFacings.getOrDefault(cellIndex, -1));
            entry.setBoolean("savedTilePayloadPresent", !cell.tileNbt.isEmpty());
            entry.setBoolean("savedItemPayloadPresent", !cell.itemNbt.isEmpty());
            entry.setString(
                "elementClass",
                cell.element == null ? ""
                    : cell.element.getClass()
                        .getName());
            HologramGTNLElement reviewed = HologramGTNLSemantics.reviewedElement(cell.element);
            if (reviewed != null) entry.setString("elementId", reviewed.id);
            ItemStack expected = HologramCellPolicy.targetFor(session, cell, cellIndex);
            if (expected != null) {
                entry.setTag("expectedMaterial", expected.writeToNBT(new NBTTagCompound()));
                entry.setString("expectedItem", String.valueOf(Item.itemRegistry.getNameForObject(expected.getItem())));
                entry.setInteger("expectedFullDamage", expected.getItemDamage());
                entry.setString(
                    "expectedBlock",
                    String.valueOf(Block.blockRegistry.getNameForObject(Block.getBlockFromItem(expected.getItem()))));
                entry.setInteger(
                    "expectedNativeMetadata",
                    expected.getItem()
                        .getMetadata(expected.getItemDamage()));
                if (reviewed != null) entry.setBoolean("reviewedElementAccepts", reviewed.accepts(expected));
            }
            Block actual = world.getBlock(cell.x, cell.y, cell.z);
            entry.setString("actualBlock", String.valueOf(Block.blockRegistry.getNameForObject(actual)));
            entry.setInteger("actualMetadata", world.getBlockMetadata(cell.x, cell.y, cell.z));
            TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            entry.setString(
                "actualTileClass",
                tile == null ? ""
                    : tile.getClass()
                        .getName());
            if (tile instanceof IGregTechTileEntity) {
                IGregTechTileEntity gt = (IGregTechTileEntity) tile;
                entry.setInteger("actualMetaTileId", gt.getMetaTileID());
                entry.setString(
                    "actualMetaTileClass",
                    gt.getMetaTileEntity() == null ? ""
                        : gt.getMetaTileEntity()
                            .getClass()
                            .getName());
                entry.setInteger(
                    "actualFacing",
                    gt.getFrontFacing()
                        .ordinal());
            }
            if (tile instanceof bartworks.system.material.TileEntityMetaGeneratedBlock) entry.setInteger(
                "actualFullMaterialDamage",
                ((bartworks.system.material.TileEntityMetaGeneratedBlock) tile).mMetaData);
            return entry;
        }

        private static NBTTagCompound sessionState(HologramSession session) {
            NBTTagCompound state = new NBTTagCompound();
            state.setInteger("job", session.job);
            state.setString("status", session.status);
            state.setString("phase", session.phase);
            state.setInteger("completed", session.completed);
            state.setInteger("cursor", session.cursor);
            state.setInteger("workCells", session.work.size());
            state.setInteger("missing", session.planMissing);
            state.setLong("planRevision", session.planRevision);
            state.setBoolean("sourceCurrent", session.capture.sourceCurrent());
            return state;
        }

        private void prepareDemandAndNegatives() throws Exception {
            require(
                HologramPlanPolicy.constructionAllowed(placement),
                "Reloaded whole-machine qualification failed: " + placement.status);
            for (HologramCapture.Cell cell : placement.capture.cells) touch(cell.x, cell.y, cell.z);
            demand.clear();
            demand.putAll(placement.pendingAssetBudget);
            require(!demand.isEmpty(), "Production whole-machine material plan is empty");
            NBTTagList materials = new NBTTagList();
            long total = 0;
            for (Map.Entry<HologramBlueprintResources.ExactKey, Long> entry : demand.entrySet()) {
                ItemStack material = sample(entry.getKey());
                NBTTagCompound cost = material.writeToNBT(new NBTTagCompound());
                cost.setLong("required", entry.getValue());
                materials.appendTag(cost);
                total = Math.addExact(total, entry.getValue());
            }
            row.setTag("productionMaterialPlan", materials);
            row.setLong("plannedPhysicalItems", total);
            NBTTagList before = actor.inventory.writeToNBT(new NBTTagList());
            NBTTagCompound scene = worldIdentity(placement);
            NBTTagCompound missing = request(placement);
            HologramBuildPreparation.reviewedBuild(placement, missing);
            HologramBuildPreparation.reviewedBuild(placement, (NBTTagCompound) missing.copy());
            require(
                placement.job != 1 && placement.planMissing > 0
                    && before.equals(actor.inventory.writeToNBT(new NBTTagList()))
                    && scene.equals(worldIdentity(placement)),
                "Missing-material whole-machine request changed world or inventory");
            row.setBoolean("missingAndReplayZeroSideEffects", true);
            stage("exact_material_plan_and_missing_material_rejection");
        }

        private void supplyDemand() throws Exception {
            for (Map.Entry<HologramBlueprintResources.ExactKey, Long> entry : demand.entrySet()) {
                ItemStack material = sample(entry.getKey());
                long remaining = entry.getValue();
                while (remaining > 0) {
                    int slot = -1;
                    for (int candidate = 0; candidate < actor.inventory.mainInventory.length; candidate++)
                        if (candidate != originalSlot && actor.inventory.mainInventory[candidate] == null) {
                            slot = candidate;
                            break;
                        }
                    require(
                        slot >= 0,
                        "Exact representative material demand does not fit the finite owned actor inventory");
                    ItemStack stack = material.copy();
                    stack.stackSize = (int) Math.min(remaining, material.getMaxStackSize());
                    require(stack.stackSize > 0, "Material has no native stack capacity");
                    actor.inventory.setInventorySlotContents(slot, stack);
                    remaining -= stack.stackSize;
                }
            }
            actor.inventory.markDirty();
            for (Map.Entry<HologramBlueprintResources.ExactKey, Long> entry : demand.entrySet())
                require(stock(entry.getKey()) == entry.getValue(), "Fixture supplied an inexact material count");
            row.setBoolean("supplyWasExact", true);
        }

        private void staleAndStart() throws Exception {
            NBTTagList stock = actor.inventory.writeToNBT(new NBTTagList());
            NBTTagCompound scene = worldIdentity(placement);
            NBTTagCompound stale = request(placement);
            stale.setLong("planRevision", placement.planRevision - 1);
            HologramBuildPreparation.reviewedBuild(placement, stale);
            require(
                placement.job != 1 && stock.equals(actor.inventory.writeToNBT(new NBTTagList()))
                    && scene.equals(worldIdentity(placement)),
                "Stale whole-machine request changed world or inventory");
            row.setBoolean("staleRevisionZeroSideEffects", true);
            buildRequest = request(placement);
            HologramBuildPreparation.reviewedBuild(placement, buildRequest);
            require(
                placement.job == 1 && !placement.work.isEmpty(),
                "Production reviewed whole-machine job was not admitted: " + placement.status);
            row.setInteger("admittedWorkCells", placement.work.size());
        }

        private void verifyPaidAndReplay() throws Exception {
            for (Map.Entry<HologramBlueprintResources.ExactKey, Long> entry : demand.entrySet())
                require(stock(entry.getKey()) == 0, "Paid production job did not debit its exact material plan");
            row.setInteger("jobSlices", jobSlices);
            row.setInteger("productionCompleted", placement.completed);
            row.setBoolean("exactMaterialDebit", true);
            NBTTagList paid = actor.inventory.writeToNBT(new NBTTagList());
            NBTTagCompound scene = worldIdentity(placement);
            HologramBuildPreparation.reviewedBuild(placement, (NBTTagCompound) buildRequest.copy());
            HologramBuildPreparation.reviewedBuild(placement, (NBTTagCompound) buildRequest.copy());
            require(
                placement.job != 1 && paid.equals(actor.inventory.writeToNBT(new NBTTagList()))
                    && scene.equals(worldIdentity(placement)),
                "Paid request replay placed or charged again");
            row.setBoolean("paidReplayZeroSideEffects", true);
            NBTTagList mismatches = new NBTTagList();
            int mismatchCount = 0;
            for (int i = 0; i < placement.capture.cells.size(); i++) {
                HologramCapture.Cell cell = placement.capture.cells.get(i);
                if (HologramSessionPolicy.ignoredPlacementAir(placement, cell, i)) continue;
                if (!HologramCellPolicy.targetSatisfied(
                    placement,
                    cell,
                    i,
                    world.getBlock(cell.x, cell.y, cell.z),
                    world.getBlockMetadata(cell.x, cell.y, cell.z))) {
                    mismatchCount++;
                    if (mismatches.tagCount() < 16) mismatches.appendTag(cellIdentity(placement, cell, i));
                }
            }
            row.setInteger("paidIdentityMismatches", mismatchCount);
            row.setTag("paidMismatchCells", mismatches);
            row.setTag("paidVerificationState", sessionState(placement));
            require(mismatchCount == 0, "Paid production cell differs from its exact material/TE identity");
        }

        private void nativeCheck(Spec spec) throws Exception {
            require(world.getTileEntity(dx, cy, cz) instanceof BaseMetaTileEntity, "Paid controller was not placed");
            BaseMetaTileEntity tile = (BaseMetaTileEntity) world.getTileEntity(dx, cy, cz);
            require(
                tile.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase && spec.type.equals(
                    tile.getMetaTileEntity()
                        .getClass()
                        .getName()),
                "Paid native controller type differs");
            builtMachine = (MTEEnhancedMultiBlockBase<?>) tile.getMetaTileEntity();
            invoke(builtMachine, "clearHatches", new Class<?>[0]);
            List<Object> errors = new ArrayList<>();
            invoke(
                builtMachine,
                "checkMachine",
                new Class<?>[] { IGregTechTileEntity.class, ItemStack.class, List.class },
                tile,
                HologramSessionPolicy.trigger(placement),
                errors);
            NBTTagList list = new NBTTagList();
            for (Object error : errors) list.appendTag(
                new NBTTagString(
                    error.getClass()
                        .getName() + ": "
                        + error));
            row.setTag("nativeStructureErrors", list);
            row.setBoolean("nativeStructureAccepted", errors.isEmpty());
            require(errors.isEmpty(), "Real upstream checkMachine rejected the paid representative structure");
        }

        private boolean liveAirBoundary() throws Exception {
            if (transition == null) {
                // Use the reviewed normal state transition, never a reflected boolean pretending a sphere exists.
                invoke(builtMachine, "buildSphere", new Class<?>[0]);
                transition = new HologramSession(actor, dx, cy, cz, true, ForgeDirection.NORTH.ordinal());
                transition.open = true;
                HologramBuildPreparation.scan(transition);
                return false;
            }
            if (live == null) {
                // The reviewed native transition changes the actual paid sphere and its own state together.
                // This is an isolated state-transition fixture, not a claim that a production recipe ran.
                if (!captureReady(transition)) return false;
                require(
                    !transition.capture.sourceMetadata.getString("state")
                        .equals("render_active"),
                    "Native AEEP sphere transition did not establish a dormant physical sphere");
                invoke(builtMachine, "destroySphere", new Class<?>[0]);
                row.setString(
                    "activeTransitionEntry",
                    "reviewed native buildSphere/destroySphere after successful paid structure check");
                row.setBoolean("productionRecipeCreatedActiveState", false);
                NBTTagList inventory = actor.inventory.writeToNBT(new NBTTagList());
                NBTTagCompound scene = worldIdentity(placement);
                HologramBuildPreparation.reviewedBuild(transition, request(transition));
                require(
                    transition.job != 1 && inventory.equals(actor.inventory.writeToNBT(new NBTTagList()))
                        && scene.equals(worldIdentity(placement)),
                    "State-expired request changed world or inventory");
                row.setBoolean("stateChangeRejectedWithoutFurtherSideEffects", true);
                transition.cancelled = true;
                live = new HologramSession(actor, dx, cy, cz, true, ForgeDirection.NORTH.ordinal());
                live.open = true;
                live.captureIntent = StructureSources.CaptureIntent.LIVE_SNAPSHOT;
                HologramBuildPreparation.scan(live);
                return false;
            }
            if (!captureReady(live)) return false;
            int air = 0;
            for (HologramCapture.Cell cell : live.capture.cells) if (!cell.anchor && cell.block == Blocks.air) air++;
            row.setInteger("liveSphereAirCells", air);
            require(
                air == 187 && "sphere_air".equals(live.capture.sourceMetadata.getString("sphere")),
                "Actual active AEEP snapshot did not describe exactly 187 air cells");
            NBTTagList stock = actor.inventory.writeToNBT(new NBTTagList());
            NBTTagCompound scene = worldIdentity(placement);
            require(
                !HologramPlanPolicy.constructionAllowed(live),
                "Live air-shell snapshot acquired new-construction authority");
            HologramBuildPreparation.reviewedBuild(live, request(live));
            require(
                live.job != 1 && stock.equals(actor.inventory.writeToNBT(new NBTTagList()))
                    && scene.equals(worldIdentity(placement)),
                "Live air-shell construction attempt changed world or inventory");
            row.setString("liveConstructionRefusal", live.status);
            row.setBoolean("liveSnapshotCannotConstructNewMachine", true);
            return true;
        }

        private void prepareCleanup() {
            retire(source);
            retire(placement);
            retire(live);
            retire(transition);
            if (inference != null) inference.cancel();
            cleanup = new ArrayList<>(touched.values());
            cleanupIndex = 0;
            phase = 10;
        }

        private void cleanupStep() throws Exception {
            int end = Math.min(cleanup.size(), cleanupIndex + 128);
            for (; cleanupIndex < end; cleanupIndex++) {
                int[] p = cleanup.get(cleanupIndex);
                clear(p[0], p[1], p[2]);
                require(
                    world.isAirBlock(p[0], p[1], p[2]) && world.getTileEntity(p[0], p[1], p[2]) == null,
                    "Workflow did not restore an originally empty cell");
            }
            if (cleanupIndex < cleanup.size()) return;
            restoreActor();
            row.setBoolean("ownedWorldRestored", true);
            row.setBoolean("actorRestored", true);
            row.setInteger("captureContinuationCalls", captureSlices);
            touched.clear();
            source = placement = live = transition = null;
            population = null;
            inference = null;
            builtMachine = null;
            loaded = null;
            cleanup = null;
            row = null;
            index++;
            phase = 0;
        }

        private void fail(Throwable failure) {
            if (!"FAIL".equals(row.getString("status"))) failures++;
            row.setString("status", "FAIL");
            row.setString("error", failure.toString());
            row.setInteger("failedPhase", phase);
            if (source != null) row.setString("sourceStatus", source.status);
            if (placement != null) {
                row.setString("placementStatus", placement.status);
                row.setInteger("job", placement.job);
                row.setInteger("jobCompleted", placement.completed);
                row.setInteger("missing", placement.planMissing);
            }
            report.setInteger("failedCases", failures);
        }

        private void stage(String name) {
            row.getTagList("stages", 8)
                .appendTag(new NBTTagString(name));
        }

        private BaseMetaTileEntity placeFixtureController(int id, int px, int py, int pz) throws Exception {
            touch(px, py, pz);
            ItemStack item = machineStack(id);
            require(
                item.getItem() instanceof ItemMachines && ((ItemMachines) item.getItem())
                    .placeBlockAt(item, actor, world, px, py, pz, ForgeDirection.NORTH.ordinal(), .5F, .5F, .5F, 0),
                "Native fixture controller placement failed");
            require(world.getTileEntity(px, py, pz) instanceof BaseMetaTileEntity, "Fixture controller TE is absent");
            BaseMetaTileEntity tile = (BaseMetaTileEntity) world.getTileEntity(px, py, pz);
            tile.setFrontFacing(ForgeDirection.NORTH);
            tile.disableWorking();
            tile.setOwnerName(actor.getCommandSenderName());
            tile.setOwnerUuid(actor.getUniqueID());
            HologramGTNLNativeProbe.suppressNativeTick(world, tile);
            return tile;
        }

        private void freezePaidController() throws Exception {
            TileEntity tile = world.getTileEntity(dx, cy, cz);
            if (tile instanceof BaseMetaTileEntity
                && ((BaseMetaTileEntity) tile).getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase)
                HologramGTNLNativeProbe.suppressNativeTick(world, tile);
        }

        private void touch(int px, int py, int pz) throws IOException {
            require(inside(px, py, pz), "Workflow leaves its new owned region");
            touched.put(px + "," + py + "," + pz, new int[] { px, py, pz });
        }

        private boolean inside(int px, int py, int pz) {
            return px >= x && px < x + WIDTH && py >= y && py < y + HEIGHT && pz >= z && pz < z + DEPTH;
        }

        private void clear(int px, int py, int pz) throws IOException {
            requireCurrent();
            require(inside(px, py, pz), "Cleanup leaves the owned workflow region");
            Chunk chunk = world.getChunkFromChunkCoords(px >> 4, pz >> 4);
            TileEntity old = world.getTileEntity(px, py, pz);
            chunk.removeTileEntity(px & 15, py, pz & 15);
            if (old != null) world.loadedTileEntityList.remove(old);
            ExtendedBlockStorage storage = chunk.getBlockStorageArray()[py >> 4];
            if (storage != null) {
                storage.func_150818_a(px & 15, py & 15, pz & 15, Blocks.air);
                storage.setExtBlockMetadata(px & 15, py & 15, pz & 15, 0);
            }
            chunk.setChunkModified();
            world.markBlockForUpdate(px, py, pz);
        }

        private void restoreActor() throws IOException {
            requireCurrent();
            actor.inventory.readFromNBT((NBTTagList) originalInventory.copy());
            actor.inventory.currentItem = originalSlot;
            actor.inventory.markDirty();
            actor.setPositionAndRotation(originalX, originalY, originalZ, originalYaw, originalPitch);
            require(
                originalInventory.equals(actor.inventory.writeToNBT(new NBTTagList())),
                "Workflow actor restoration failed");
        }

        private long stock(HologramBlueprintResources.ExactKey key) {
            long count = 0;
            for (ItemStack stack : actor.inventory.mainInventory) if (key.matches(stack)) count += stack.stackSize;
            return count;
        }

        private void requireCurrent() throws IOException {
            requireOwned(world, actor, x, y, z);
            require(
                runId.equals(
                    TestLab.status()
                        .getString("runId")),
                "Workflow run ownership changed");
        }

        public NBTTagCompound result() {
            return (NBTTagCompound) report.copy();
        }

        @Override
        public void close() throws Exception {
            if (closed) return;
            if (row != null && "RUNNING".equals(row.getString("status")))
                fail(new IOException("Workflow stopped before this machine case completed"));
            retire(source);
            retire(placement);
            retire(live);
            retire(transition);
            if (inference != null) inference.cancel();
            if (originalEmpty) for (int[] p : touched.values()) clear(p[0], p[1], p[2]);
            restoreActor();
            report.setBoolean("ownedWorldRestored", true);
            report.setBoolean("actorRestored", true);
            report.setInteger("failedCases", failures);
            report.setBoolean("success", finished && failures == 0);
            closed = true;
        }
    }

    private static NBTTagCompound configuration(HologramSession session, NBTTagCompound channels) {
        NBTTagCompound action = new NBTTagCompound();
        action.setInteger("main", 1);
        action.setTag("channels", channels.copy());
        action.setInteger("mode", 0);
        action.setInteger("scope", 0);
        action.setInteger("layer", 0);
        action.setInteger("selected", -1);
        action.setInteger(
            "facing",
            session.machine.getExtendedFacing()
                .ordinal());
        action.setBoolean("noHatches", false);
        return action;
    }

    private static NBTTagCompound request(HologramSession session) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", session.id);
        request.setLong("planRevision", session.planRevision);
        request.setLong("placementReviewNonce", session.placementReviewNonce);
        request.setBoolean("forceExplicit", false);
        return request;
    }

    private static int findCell(HologramSession session, String id, int ordinal) throws IOException {
        for (int i = 0; i < session.capture.cells.size(); i++) {
            HologramGTNLElement element = HologramGTNLSemantics.reviewedElement(session.capture.cells.get(i).element);
            if (element != null && element.id.equals(id) && ordinal-- == 0) return i;
        }
        throw new IOException("Reviewed edit location absent: " + id);
    }

    private static ItemStack sample(HologramBlueprintResources.ExactKey key) throws IOException {
        require(
            key.type instanceof Item,
            "Representative empty-machine workflow requires an item-only physical demand");
        ItemStack sample = new ItemStack((Item) key.type, 1, key.meta);
        if (key.tag != null) sample.setTagCompound((NBTTagCompound) key.tag.copy());
        return sample;
    }

    private static ItemStack machineStack(int id) {
        return new ItemStack(GregTechAPI.sBlockMachines, 1, id);
    }

    private static int registered(String name, int tier) throws IOException {
        for (int id = 0; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity prototype = GregTechAPI.METATILEENTITIES[id];
            if (prototype != null && prototype.getClass()
                .getName()
                .equals(name) && (tier < 0 || prototype instanceof MTEHatch && ((MTEHatch) prototype).mTier == tier))
                return id;
        }
        throw new IOException("Actual registered machine/hatch absent: " + name + "/" + tier);
    }

    private static NBTTagCompound worldIdentity(HologramSession session) {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList cells = new NBTTagList();
        for (HologramCapture.Cell cell : session.capture.cells) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setIntArray("position", new int[] { cell.x, cell.y, cell.z });
            entry.setString(
                "block",
                String.valueOf(
                    Block.blockRegistry.getNameForObject(session.player.worldObj.getBlock(cell.x, cell.y, cell.z))));
            entry.setInteger("meta", session.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z));
            TileEntity actual = session.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
            if (actual != null) {
                entry.setString(
                    "tileClass",
                    actual.getClass()
                        .getName());
                if (actual instanceof IGregTechTileEntity) {
                    entry.setInteger("mteId", ((IGregTechTileEntity) actual).getMetaTileID());
                    entry.setInteger(
                        "facing",
                        ((IGregTechTileEntity) actual).getFrontFacing()
                            .ordinal());
                }
                if (actual instanceof bartworks.system.material.TileEntityMetaGeneratedBlock) entry.setInteger(
                    "fullMaterialDamage",
                    ((bartworks.system.material.TileEntityMetaGeneratedBlock) actual).mMetaData);
            }
            cells.appendTag(entry);
        }
        result.setTag("cells", cells);
        return result;
    }

    private static Object invoke(Object object, String name, Class<?>[] signature, Object... arguments)
        throws Exception {
        try {
            Method method = object.getClass()
                .getMethod(name, signature);
            return method.invoke(object, arguments);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception) throw (Exception) failed.getCause();
            if (failed.getCause() instanceof Error) throw (Error) failed.getCause();
            throw new IllegalStateException(failed.getCause());
        }
    }

    private static void retire(HologramSession session) {
        if (session == null) return;
        HologramDeferredPlanRefresh.cancel(session);
        HologramVirtualStructureEditing.cancel(session);
        HologramSession.cancelCaptureRetry(session);
        HologramSession.clearPending(session);
        session.job = 4;
        session.cancelled = true;
    }

    private static byte[] preview() throws IOException {
        BufferedImage image = new BufferedImage(
            HologramBlueprintArtifact.WIDTH,
            HologramBlueprintArtifact.HEIGHT,
            BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        require(ImageIO.write(image, "png", bytes), "Server PNG fixture writer is unavailable");
        return bytes.toByteArray();
    }

    private static void requireOwned(WorldServer world, FakePlayer actor, int x, int y, int z) throws IOException {
        require(
            TestLab.isEnabled() && world != null
                && actor != null
                && !world.isRemote
                && world.provider instanceof TestLabWorldProvider
                && actor.worldObj == world
                && !actor.isDead
                && !actor.capabilities.isCreativeMode
                && actor.capabilities.allowEdit
                && ItemNekoHologramProjector.isProjector(actor.getHeldItem())
                && TestLab.isOwnedFixture(world, actor, x, y, z, WIDTH, HEIGHT, DEPTH),
            "Workflow requires the new leased GTNL region and its own survival FakePlayer");
        for (Object user : world.playerEntities)
            require(user instanceof FakePlayer, "Human presence stops workflow mutation");
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static List<Spec> specs() {
        return Arrays.asList(
            new Spec("assembler_matrix", "AssemblerMatrix"),
            new Spec("large_steam_mixer", "steam.LargeSteamMixer", "machine_casing", 2),
            new Spec(
                "precision_assembler",
                "structuralReconstructionPlan.PrecisionAssembler",
                "machine_casing",
                3,
                "unit_casing",
                2),
            new Spec("nuclear_reactor", "NuclearReactor", "casing", 2),
            new Spec("aeep", "AtomicEnergyExcitationPlant", "machine_casing", 2, "coil", 2));
    }

    private static final class Spec {

        final String family, type;
        final NBTTagCompound channels = new NBTTagCompound();

        Spec(String family, String type, Object... values) {
            this.family = family;
            this.type = PREFIX + type;
            for (int i = 0; i < values.length; i += 2) channels.setInteger((String) values[i], (Integer) values[i + 1]);
        }
    }

    private static final class Edit {

        final int index, facing;
        final ItemStack target;

        Edit(int index, ItemStack target, int facing) {
            this.index = index;
            this.target = target.copy();
            this.facing = facing;
        }
    }
}
