package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Method;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Uses the real author command, unified undo, geometry delta, projection and placement adapters. */
final class HologramBlueprintRotationRegression {

    private HologramBlueprintRotationRegression() {}

    static void run() {
        try {
            HologramSession s = fixture();
            HologramPresets.Preset source = s.processingSource;
            HologramPresets.Preset original = HologramBlueprintRotation.copyPreset(s.virtualPreset);
            HologramLocalEditRegression.CountingWorld world = (HologramLocalEditRegression.CountingWorld) s.player.worldObj;
            HologramBlueprintEditor.remember(s.undoEdits, new HologramEditState(s));
            long revision = s.planRevision;
            world.reads = 0;
            HologramBlueprintRotation.rotate(s, request(s));
            SimpleAssert.eq(0, world.reads, "whole author turn never observes the world");
            SimpleAssert.eq(0L, s.geometryEdits.fullValidations, "turn defers full admission validation");
            SimpleAssert.that(s.processingSource == source, "turn preserves old processing source until validation");
            SimpleAssert.that(s.geometryEdits.validationPending && s.blueprintDirty, "turn marks dirty geometry");
            SimpleAssert.that(!s.processingReviewed, "turn withdraws the old processing approval");
            SimpleAssert.eq(2, s.undoEdits.size(), "turn appends one entry and retains preceding edit history");
            SimpleAssert.eq(revision + 1, s.planRevision, "turn publishes one revision");
            assertTurned(s);
            NBTTagCompound delta = HologramGeometryEdits.delta(s);
            SimpleAssert.that(delta != null && delta.getBoolean("topology"), "turn publishes geometry delta");
            SimpleAssert.eq(
                3,
                delta.getTagList("cells", 10)
                    .tagCount(),
                "whole turn publishes changed indices once");
            HologramPresets.Preset saved = HologramBlueprintProjection.blueprint(s, "Turned");
            HologramPresets.Preset reloaded = HologramPresets.copy(saved);
            SimpleAssert.eq(2, reloaded.cells.get(1).metadata, "saved stair carries the rotated native metadata");
            SimpleAssert.eq(1, reloaded.cells.get(1).z, "saved offset is used by the next placement");
            SimpleAssert
                .eq(s.virtualPreset.controllerFacing, reloaded.controllerFacing, "save retains full controller pose");
            SimpleAssert.eq(1, turns(reloaded), "save retains attachment orientation definition");

            HologramBlueprintEditor.edit(s, new NBTTagCompound(), "editUndo");
            SimpleAssert.eq(1, s.capture.cells.get(1).x, "unified undo restores geometry");
            SimpleAssert.eq(0, s.capture.cells.get(1).meta, "undo restores stair state");
            SimpleAssert
                .eq(original.controllerFacing, s.virtualPreset.controllerFacing, "undo restores controller baseline");
            SimpleAssert.eq(1, s.virtualPreset.cells.get(1).x, "undo restores saved default-shell positions");
            SimpleAssert.eq(0, turns(s.virtualPreset), "undo restores attachment definition");
            SimpleAssert.eq(0, s.virtualPreset.hintAreas.get(0).x1, "undo restores hint bounds");
            SimpleAssert.that(!s.processingReviewed, "undo does not resurrect save approval");
            HologramBlueprintEditor.edit(s, new NBTTagCompound(), "editRedo");
            assertTurned(s);
            for (int turn = 0; turn < 3; turn++) HologramBlueprintRotation.rotate(s, request(s));
            SimpleAssert.eq(1, s.capture.cells.get(1).x, "four turns restore the asymmetric coordinates");
            SimpleAssert.eq(0, s.capture.cells.get(1).meta, "four turns restore stairs metadata");
            SimpleAssert.eq(4, s.capture.cells.get(2).meta, "four turns restore log axis");
            SimpleAssert.eq(
                original.controllerFacing,
                s.virtualPreset.controllerFacing,
                "four turns restore controller orientation");
            SimpleAssert.eq(0L, s.geometryEdits.fullValidations, "repeated explicit turns do not run full admission");
            rejectionIsAtomic();
            adapterPriority();
            proofLifecycle();
        } catch (Exception failure) {
            throw new AssertionError("Whole blueprint orientation regression", failure);
        }
    }

    private static void assertTurned(HologramSession s) throws Exception {
        SimpleAssert.eq(0, s.capture.cells.get(1).x, "turn rotates about the existing blueprint anchor");
        SimpleAssert.eq(1, s.capture.cells.get(1).z, "turn rotates the asymmetric extent");
        SimpleAssert.eq(2, s.capture.cells.get(1).meta, "stair follows the existing vanilla metadata adapter");
        SimpleAssert.eq(8, s.capture.cells.get(2).meta, "log axis follows the existing vanilla metadata adapter");
        SimpleAssert
            .eq(ExtendedFacing.EAST_NORMAL_NONE.ordinal(), s.facing, "session pose follows the same quarter turn");
        SimpleAssert.eq(1, s.selected, "stable selection index follows the same cell");
        SimpleAssert.that(s.selectedIndices.contains(2), "multi-selection follows stable cell order");
        SimpleAssert.eq(1, turns(s.virtualPreset), "text attachment records the same native turn");
        SimpleAssert.eq(-1, s.virtualPreset.hintAreas.get(0).x1, "hint cuboid bounds rotate and renormalize");
        SimpleAssert.that(s.manualPositions.containsKey("0,64,2"), "manual-position map is rekeyed");
    }

    private static int turns(HologramPresets.Preset preset) throws Exception {
        return HologramBlueprintNbt.decode(preset.structures.get(0).textBoxNbt)
            .getIntArray("turns").length;
    }

    private static NBTTagCompound request(HologramSession s) {
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("planRevision", s.planRevision);
        return request;
    }

    private static void rejectionIsAtomic() throws Exception {
        HologramSession s = fixture();
        NBTTagCompound unknown = new NBTTagCompound();
        unknown.setString("id", "gtmb:unknown_directional_fixture");
        unknown.setInteger("someDirection", 3);
        // Only the last live cell rejects, after earlier baseline and live cells have been staged.
        s.capture.cells.get(2).block = Blocks.stone;
        s.capture.cells.get(2).meta = 0;
        s.capture.cells.get(2).tileNbt = HologramBlueprintNbt.encodeCaptured(unknown);
        HologramCapture capture = s.capture;
        HologramPresets.Preset preset = s.virtualPreset;
        long revision = s.planRevision;
        try {
            HologramBlueprintRotation.rotate(s, request(s));
            throw new AssertionError("unknown native directional NBT must refuse the complete turn");
        } catch (IOException expected) {
            SimpleAssert.that(s.capture == capture && s.virtualPreset == preset, "late rejection retains live objects");
            SimpleAssert.eq(1, s.capture.cells.get(1).x, "late rejection retains earlier coordinates");
            SimpleAssert.eq(0, s.capture.cells.get(1).meta, "late rejection retains earlier metadata");
            SimpleAssert.eq(revision, s.planRevision, "late rejection retains revision");
            SimpleAssert.eq(0, s.undoEdits.size(), "late rejection creates no undo entry");
            SimpleAssert.that(!s.geometryEdits.validationPending, "late rejection creates no validation obligation");
        }
        NBTTagCompound stale = request(s);
        stale.setLong("planRevision", revision - 1);
        try {
            HologramBlueprintRotation.rotate(s, stale);
            throw new AssertionError("stale revision must refuse");
        } catch (IOException expected) {
            SimpleAssert.that(s.capture == capture, "stale command retains live geometry");
        }
    }

    private static void adapterPriority() throws Exception {
        try (AutoCloseable ignored = HologramBlockTransformApi.register(new HologramBlockTransformApi.Adapter() {

            public boolean accepts(HologramBlockTransformApi.State state) {
                return state.block == Blocks.oak_stairs;
            }

            public void rotate(HologramBlockTransformApi.State state, boolean vertical) {
                state.metadata = 7;
            }
        })) {
            SimpleAssert.eq(
                7,
                HologramBlockTransformApi.rotate(Blocks.oak_stairs, 0, null, false, 1).metadata,
                "registered native adapter remains ahead of vanilla metadata fallback");
        }
        int metadata = 0;
        for (int turn = 0; turn < 4; turn++)
            metadata = HologramBlockTransformApi.rotate(Blocks.oak_stairs, metadata, null, false, 1).metadata;
        SimpleAssert.eq(0, metadata, "public placement transform retains four-turn identity");
        SimpleAssert.eq(
            2,
            HologramBlockTransformApi.rotate(Blocks.oak_stairs, 0, null, false, 1).metadata,
            "placement and editing share the same vanilla metadata rule");
    }

    private static void proofLifecycle() throws Exception {
        HologramSession s = fixture();
        s.virtualPreset.id = "rotation-proof";
        s.blueprintSource = "saved";
        HologramPresets.Preset disk = HologramBlueprintRotation.copyPreset(s.virtualPreset);
        String hash = HologramBlueprintContentHash.of(disk);
        // The fixture supplies the already-bound disk identity; the real first turn obtains it from the library.
        s.rotationProof = new HologramBlueprintRotation.Proof(disk.id, hash, 0);
        HologramBlueprintRotation.rotate(s, request(s));
        SimpleAssert
            .eq(1, HologramBlueprintRotation.approvedTurns(s, disk), "proof permits only the recorded native turn");
        HologramPresets.Preset replay = HologramBlueprintRotation.rotatedBaseline(disk, 1);
        SimpleAssert.eq(
            s.virtualPreset.cells.get(1).metadata,
            replay.cells.get(1).metadata,
            "saved baseline replay shares the production native metadata transform");
        SimpleAssert.eq(
            s.virtualPreset.cells.get(2).z,
            replay.cells.get(2).z,
            "saved baseline replay shares the production coordinate transform");
        HologramBlueprintEditor.edit(s, new NBTTagCompound(), "editUndo");
        SimpleAssert
            .eq(0, HologramBlueprintRotation.approvedTurns(s, disk), "undo restores its matching proof turn count");
        HologramBlueprintEditor.edit(s, new NBTTagCompound(), "editRedo");
        SimpleAssert
            .eq(1, HologramBlueprintRotation.approvedTurns(s, disk), "redo restores its matching proof turn count");
        HologramPresets.Preset changedDisk = HologramBlueprintRotation.copyPreset(disk);
        changedDisk.cells.get(0).metadata = 1;
        SimpleAssert.eq(
            -1,
            HologramBlueprintRotation.approvedTurns(s, changedDisk),
            "changed disk content retires proof authority");
        HologramPresets.Preset otherDisk = HologramBlueprintRotation.copyPreset(disk);
        otherDisk.id = "other-source";
        SimpleAssert.eq(
            -1,
            HologramBlueprintRotation.approvedTurns(s, otherDisk),
            "same payload cannot substitute another source identity");
        // Model the successful receipt's new disk baseline independently of the existing undo entries.
        replay.id = "saved-as-rotation";
        s.virtualPreset.id = replay.id;
        s.rotationProof = new HologramBlueprintRotation.Proof(
            replay.id,
            HologramBlueprintContentHash.of(replay),
            s.rotationTurns);
        HologramBlueprintEditor.edit(s, new NBTTagCompound(), "editUndo");
        SimpleAssert.eq(replay.id, s.virtualPreset.id, "pose undo retains a newly saved file identity");
        SimpleAssert.eq(
            3,
            HologramBlueprintRotation.approvedTurns(s, replay),
            "undo after save proves the inverse relative to the new disk baseline");
        HologramBlueprintEditor.edit(s, new NBTTagCompound(), "editRedo");
        SimpleAssert.eq(
            0,
            HologramBlueprintRotation.approvedTurns(s, replay),
            "redo after save returns to the current disk orientation");
        HologramBlueprintRotation.reset(s);
        HologramBlueprintEditor.edit(s, new NBTTagCompound(), "editUndo");
        SimpleAssert.that(s.rotationProof == null, "undo after save/source reset cannot restore retired authority");
        HologramBlueprintEditor.edit(s, new NBTTagCompound(), "editRedo");
        SimpleAssert.that(s.rotationProof == null, "redo after save/source reset cannot restore retired authority");
    }

    private static HologramSession fixture() throws Exception {
        Method factory = HologramGeometryEditRegression.class.getDeclaredMethod("fixture", int.class);
        factory.setAccessible(true);
        HologramSession s = (HologramSession) factory.invoke(null, 3);
        net.minecraft.block.Block[] blocks = { Blocks.stone, Blocks.oak_stairs, Blocks.log };
        for (int i = 0; i < blocks.length; i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            cell.block = blocks[i];
            cell.meta = i == 2 ? 4 : 0;
            cell.blueprintBlock = String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(blocks[i]));
            cell.blueprintItem = cell.blueprintBlock;
            cell.actualStack = new ItemStack(blocks[i]);
            s.pins.put(i, cell.actualStack.copy());
            s.coordinatePins.put(HologramValues.position(cell), cell.actualStack.copy());
            s.planTargets.set(i, cell.actualStack.copy());
            HologramPresets.Cell saved = HologramPresets.cell(cell.actualStack, i, 0, 0, -1, false);
            saved.block = cell.blueprintBlock;
            saved.metadata = cell.meta;
            s.virtualPreset.cells.set(i, saved);
        }
        s.virtualPreset.controllerFacing = ExtendedFacing.NORTH_NORMAL_NONE.ordinal();
        s.facing = s.virtualPreset.controllerFacing;
        s.selected = 1;
        s.selectedIndices.add(1);
        s.selectedIndices.add(2);
        s.manualPositions.put("2,64,0", s.capture.cells.get(2));
        HologramPresets.HintArea hint = new HologramPresets.HintArea();
        hint.x2 = 2;
        hint.z2 = 1;
        s.virtualPreset.hintAreas.add(hint);
        HologramPresets.Structure text = new HologramPresets.Structure();
        text.id = HologramTextBox.PREFIX + "12345678-1234-1234-1234-123456789012";
        text.x = 1;
        HologramTextBox definition = new HologramTextBox();
        definition.id = text.id;
        definition.width = definition.height = 1;
        definition.bitmap = new byte[] { 1 };
        text.textBoxNbt = HologramBlueprintNbt.encodeCaptured(definition.write());
        s.virtualPreset.structures.add(text);
        s.processingReviewed = true;
        return s;
    }
}
