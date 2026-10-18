package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Volume selection includes hidden interior cells; axis movement remains world-oriented after camera turns. */
public final class HologramSelectionRegression {

    private HologramSelectionRegression() {}

    public static void run() {
        HologramSelection selection = new HologramSelection();
        selection.click(1, false);
        selection.click(2, true);
        SimpleAssert.eq(2, selection.size(), "control click extends selection");
        selection.click(1, true);
        SimpleAssert.that(!selection.contains(1) && selection.contains(2), "control click toggles only its cell");
        HologramState state = volume();
        selection.previewClick(state, 0, "", false, false);
        SimpleAssert.eq(1, selection.size(), "ordinary interior click selects one cell even inside a structure");
        SimpleAssert.that(!selection.movable(state), "single structure member cannot expose a movement handle");
        selection.previewClick(state, 0, "", true, false);
        SimpleAssert.eq(4, selection.size(), "held T selects exact block and metadata within the structure");
        SimpleAssert.that(!selection.contains(4), "T never merges another metadata variant of the same block");
        SimpleAssert.that(!selection.movable(state), "same-type partial structure selection cannot move");
        selection.previewClick(state, 8, "", true, false);
        SimpleAssert.eq(1, selection.size(), "held T on a loose cell never selects unrelated structures");
        SimpleAssert.eq(8, selection.first(), "exact-type selection retains the clicked primary cell");
        SimpleAssert.that(selection.movable(state), "loose blueprint cell may move independently");
        selection.previewClick(state, 0, "machine-a", false, false);
        SimpleAssert.eq(8, selection.size(), "outline click remains the explicit whole-structure selection path");
        SimpleAssert.that(selection.movable(state), "complete structure selection may move");
        selection.previewClick(state, 8, "", false, true);
        SimpleAssert.eq(9, selection.size(), "Ctrl click after outline selection adds only the clicked ungrouped cell");
        selection.load(new int[] { 0, 7, -1, 99, 7 }, 8, state.cells.size());
        SimpleAssert.that(
            selection.size() == 2 && selection.contains(0) && selection.contains(7),
            "authoritative edit result restores validated batch selection without duplicate or out-of-range cells");
        selection.load(new int[0], 8, state.cells.size());
        SimpleAssert.that(
            selection.size() == 1 && selection.contains(8),
            "new session restores a legacy single-cell selection when batch selection is absent");
        SimpleAssert.that(
            state.cells.get(8).sourceMissing && !state.cells.get(8).missingBlock
                && state.cells.get(8).sourceWarning.equals("source unavailable"),
            "vanilla or legacy members preserve missing-source warning independently of missing block and mod version rows");
        selection.box(state, 0, 7, false);
        SimpleAssert.eq(8, selection.size(), "shift volume includes all eight cells regardless of visible faces");
        SimpleAssert.that(!selection.contains(8), "volume excludes adjacent cell outside all three bounds");
        selection.box(state, 7, 0, false);
        SimpleAssert.eq(8, selection.size(), "reverse corner order produces the same volume");
        selection.click(8, true);
        selection.structure(state, "machine-a", true);
        SimpleAssert.eq(9, selection.size(), "additive whole structure keeps unrelated selection");
        selection.structure(state, "machine-a", false);
        SimpleAssert
            .eq(8, selection.size(), "whole structure selection follows membership rather than screen rectangle");
        selection.retain(4);
        SimpleAssert.eq(4, selection.size(), "snapshot shrink never leaves invalid indices");
        snapshotShrinkRegression();
        HologramInputContextRegression.run();
        HologramDeidentificationMemoryRegression.run();
        sharedWallRegression();
        placementConfirmationRegression();
        annotationRegression();
        replacementFamilyRegression();
        metaTileIdentityRegression();
        HologramAnnotationMarkersRegression.run();
        HologramGizmo gizmo = new HologramGizmo();
        gizmo.project(100, 100, Math.PI / 4, Math.PI / 6);
        for (int axis = 0; axis < 3; axis++) {
            double[] end = gizmo.end(axis);
            SimpleAssert.eq(
                axis,
                gizmo.pick((int) Math.round(end[0]), (int) Math.round(end[1])),
                "each RGB handle has independent hit region");
        }
        gizmo.project(100, 100, Math.PI, 0);
        gizmo.begin(0, 100, 100);
        SimpleAssert.eq(2, gizmo.update(76, 100), "rotated camera drag still produces positive world X");
        gizmo.cancel();
        SimpleAssert.eq(-1, gizmo.axis(), "escape/release cancels active axis");
        gizmo.project(100, 100, 0, 0);
        gizmo.begin(1, 100, 100);
        SimpleAssert.eq(2, gizmo.update(100, 76), "screen-up drag produces positive world Y");
        gizmo.project(100, 100, Math.PI / 2, 0);
        gizmo.begin(2, 100, 100);
        SimpleAssert.eq(2, gizmo.update(76, 100), "Z handle follows camera orientation");
        SimpleAssert.that(HologramGizmo.distance(5, 3, 0, 0, 10, 0) == 3, "axis pick uses segment distance");
        HologramNbtSelection storage = new HologramNbtSelection();
        storage.load(true, new NBTTagList());
        java.util.List<String> available = java.util.Arrays.asList("Items", "Tank", "Configuration");
        storage.toggle("Items", available);
        SimpleAssert.that(
            !storage.all() && !storage.selected("Items")
                && storage.selected("Tank")
                && storage.selected("Configuration"),
            "deselecting one root key materializes all other actual entries");
        HologramNbtSelection placement = new HologramNbtSelection();
        placement.load(storage.all(), storage.keys());
        placement.toggle("Tank", available);
        SimpleAssert.that(
            storage.selected("Tank") && !placement.selected("Tank"),
            "one-placement downgrade never edits stored blueprint key selection");
        placement.retainCovers(false);
        SimpleAssert.that(
            storage.retainCovers() && !placement.retainCovers(),
            "one-placement cover downgrade never changes the blueprint cover option");
        storage.all(false);
        SimpleAssert.that(storage.retainCovers(), "root-key cancellation leaves covers independently retained");
        placement.all(false);
        SimpleAssert.eq(
            0,
            placement.keys()
                .tagCount(),
            "empty key list explicitly cancels optional root entries");
        storage.all(true);
        SimpleAssert.that(storage.selected("NewMachineKey"), "default all retains newly encountered actual keys");
    }

    private static void snapshotShrinkRegression() {
        NBTTagCompound largeTag = (NBTTagCompound) volume().fullData();
        NBTTagList cells = largeTag.getTagList("cells", 10);
        for (int i = cells.tagCount(); i <= 153; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", i);
            cells.appendTag(cell);
        }
        HologramState large = new HologramState(largeTag), small = volume();
        HologramSelection selection = new HologramSelection();
        selection.load(new int[] { 0, 153 }, -1, large.cells.size());
        SimpleAssert.eq(6, selection.positions(large).length, "memory stores both selected source positions");
        SimpleAssert.eq(
            3,
            selection.positions(small).length,
            "memory read during snapshot shrink ignores index 153 without placeholder coordinates or crash");
        selection.retain(small.cells.size());
        SimpleAssert.that(
            selection.size() == 1 && selection.contains(0),
            "replacement snapshot validation retains the live selection before opening-purpose memory writes");
        selection.retain(0);
        SimpleAssert.eq(
            0,
            selection.positions(new HologramState(new NBTTagCompound())).length,
            "empty import progress snapshot safely clears all selected positions");
    }

    private static HologramState volume() {
        NBTTagCompound data = new NBTTagCompound();
        NBTTagList cells = new NBTTagList();
        for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = 0; z <= 1; z++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("dx", x);
            cell.setInteger("dy", y);
            cell.setInteger("dz", z);
            cell.setString("wantId", "minecraft:stone");
            cell.setInteger("wantMeta", x);
            cell.setString("structureId", "machine-a");
            cells.appendTag(cell);
        }
        NBTTagCompound outside = new NBTTagCompound();
        outside.setInteger("dx", 2);
        outside.setString("wantId", "minecraft:stone");
        outside.setBoolean("sourceMissing", true);
        outside.setString("sourceWarning", "source unavailable");
        outside.setString("sourceClass", "missing.Controller");
        cells.appendTag(outside);
        data.setTag("cells", cells);
        return new HologramState(data);
    }

    private static void annotationRegression() {
        NBTTagCompound data = (NBTTagCompound) volume().fullData();
        data.setString("blueprintPrompt", "Build foundation first");
        NBTTagList cells = data.getTagList("cells", 10);
        cells.getCompoundTagAt(0)
            .setString("prompt", "Energy hatch here");
        cells.getCompoundTagAt(1)
            .setString("prompt", "Output here");
        NBTTagCompound area = new NBTTagCompound();
        area.setIntArray("bounds", new int[] { 0, 0, 0, 1, 1, 1 });
        area.setString("prompt", "Keep this volume clear");
        NBTTagList areas = new NBTTagList();
        areas.appendTag(area);
        data.setTag("hintAreas", areas);
        HologramState state = new HologramState(data);
        SimpleAssert.that(
            HologramAnnotations.scope(state, new int[] { 0, 7 }, 2)
                .contains("(0,0,0) → (1,1,1)"),
            "prompt scope shows concrete inclusive selected volume coordinates");
        SimpleAssert.eq(
            "Build foundation first",
            HologramAnnotations.input(state, new int[0], 0),
            "global editor reads the persisted blueprint prompt");
        SimpleAssert.eq(
            "Energy hatch here",
            HologramAnnotations.input(state, new int[] { 0 }, 1),
            "cell editor reads its own persisted prompt");
        SimpleAssert.eq(
            "",
            HologramAnnotations.input(state, new int[] { 0, 1 }, 1),
            "mixed cell prompts do not imply that one cell value applies to the whole selection");
        SimpleAssert.that(
            HologramAnnotations.saved(state, new int[] { 0, 1 }, 1)
                .contains("不同"),
            "mixed saved cell annotations remain visibly identified");
        SimpleAssert.eq(
            "Keep this volume clear",
            HologramAnnotations.input(state, new int[] { 0, 7 }, 2),
            "exact saved hint volume repopulates the area editor");
        SimpleAssert.that(
            HologramAnnotations.saved(state, new int[] { 0 }, 2)
                .contains("Keep this volume clear"),
            "selected cell summary identifies a persisted containing hint area without hover");
        SimpleAssert.eq(
            "",
            HologramAnnotations.input(state, new int[] { 0 }, 2),
            "different hint volume starts a fresh area prompt instead of silently copying a containing area");
    }

    private static void replacementFamilyRegression() {
        NBTTagCompound data = (NBTTagCompound) volume().fullData();
        NBTTagList cells = data.getTagList("cells", 10);
        for (String family : new String[] { "coil", "tiered:fixture", "frame:fixture", "glass:fixture" }) {
            for (int index : new int[] { 0, 4, 8 }) cells.getCompoundTagAt(index)
                .setString("family", family);
            // A hatch can retain its source casing's declaration family after an independent edit.
            cells.getCompoundTagAt(2)
                .setString("family", family);
            cells.getCompoundTagAt(2)
                .setString("wantId", "gregtech:gt.blockmachines");
            HologramState state = new HologramState(data);
            HologramSelection selection = new HologramSelection();
            selection.previewClick(state, 0, "", false, false);
            SimpleAssert.eq(1, selection.size(), "grade casing stays independently editable: " + family);
            SimpleAssert.that(selection.canSelectRelated(state), "unselected related casings keep the T hint visible");
            SimpleAssert.that(
                selection.related(state)
                    .contains(4)
                    && !selection.related(state)
                        .contains(8)
                    && !selection.related(state)
                        .contains(2),
                "related outlines stay in the structure and exclude current hatch targets");
            selection.selectRelated(state, false);
            SimpleAssert.that(
                selection.size() == 2 && selection.contains(0) && selection.contains(4),
                "explicit T selects the declared grade family across metadata variants");
            SimpleAssert.that(!selection.canSelectRelated(state), "fully selected grade group clears the hint");
            selection.previewClick(state, 2, "", false, false);
            SimpleAssert.eq(1, selection.size(), "current hatch with casing provenance remains independently editable");
            SimpleAssert.that(!selection.canSelectRelated(state), "current hatch never offers casing grade expansion");
            selection.previewClick(state, 2, "", true, false);
            SimpleAssert.eq(1, selection.size(), "T on the current hatch uses its exact device identity");
            selection.clear();
            SimpleAssert.that(
                selection.related(state)
                    .isEmpty(),
                "clearing selection invalidates related outlines");
        }
        for (String family : new String[] { "declared:fixture", "wildcard:fixture" }) {
            for (int index : new int[] { 0, 4, 8 }) cells.getCompoundTagAt(index)
                .setString("family", family);
            HologramState state = new HologramState(data);
            HologramSelection selection = new HologramSelection();
            selection.previewClick(state, 0, "", false, false);
            SimpleAssert.eq(2, selection.size(), "ordinary declared family retains existing selection semantics");
            selection.previewClick(state, 0, "", true, false);
            SimpleAssert.that(
                selection.size() == 3 && selection.contains(1) && !selection.contains(4),
                "T on ordinary declared families retains exact variant semantics");
        }
    }

    private static void metaTileIdentityRegression() {
        NBTTagCompound data = (NBTTagCompound) volume().fullData();
        NBTTagList cells = data.getTagList("cells", 10);
        for (int i = 0; i < 8; i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            cell.setString("wantId", "gregtech:gt.blockmachines");
            cell.setInteger("wantMeta", 0);
            cell.setString("exactIdentity", "gregtech:gt.blockmachines@" + (i < 2 ? 31 : 32));
        }
        HologramState state = new HologramState(data);
        HologramSelection selection = new HologramSelection();
        selection.previewClick(state, 0, "", true, false);
        SimpleAssert.that(
            selection.size() == 2 && selection.contains(1) && !selection.contains(2),
            "T distinguishes GT meta-tile item IDs even when world block metadata is always zero");
    }

    private static void placementConfirmationRegression() {
        NBTTagCompound state = new NBTTagCompound();
        state.setLong("planRevision", 42);
        state.setInteger("missing", 1);
        SimpleAssert.that(
            !HologramPlacementConfirmation.canBuild(state, 42),
            "ordinary structure still blocks missing materials");
        state.setBoolean("virtual", true);
        state.setBoolean("virtualPlacement", true);
        SimpleAssert
            .that(!HologramPlacementConfirmation.canBuild(state, 42), "unlocked blueprint cannot request construction");
        state.setBoolean("virtualLocked", true);
        SimpleAssert.that(
            HologramPlacementConfirmation.canBuild(state, 42),
            "locked missing-material blueprint can request its displayed partial plan");
        SimpleAssert.that(
            HologramPlacementConfirmation.forceReview(state, 42)
                && "确认仅放已有".equals(HologramPlacementConfirmation.confirmationAction(state, 42, true)),
            "initial material review explicitly presents partial consent without a preceding rejected request");
        state.setBoolean("forcePlacementReady", true);
        SimpleAssert.that(
            HologramPlacementConfirmation.canBuild(state, 42) && HologramPlacementConfirmation.forceReview(state, 42),
            "legacy force flag does not change the already displayed partial plan");
        state.setLong("planRevision", 43);
        SimpleAssert.that(
            !HologramPlacementConfirmation.canBuild(state, 42) && !HologramPlacementConfirmation.forceReview(state, 42),
            "editing/source revision change invalidates the reviewed construction intent");
        state.setBoolean("forcePlacementReady", false);
        state.setInteger("missing", 0);
        SimpleAssert
            .that(!HologramPlacementConfirmation.forceReview(state, 43), "fully stocked plan needs no partial consent");
        state.setBoolean("virtual", false);
        state.setInteger("missing", 0);
        SimpleAssert.that(
            HologramPlacementConfirmation.canBuild(state, 43),
            "fully stocked ordinary construction remains supported");
    }

    private static void sharedWallRegression() {
        NBTTagCompound data = new NBTTagCompound();
        NBTTagList cells = new NBTTagList();
        NBTTagCompound first = new NBTTagCompound();
        first.setInteger("dx", -2);
        first.setInteger("x", -2);
        first.setString("structureId", "machine-a");
        cells.appendTag(first);
        NBTTagCompound shared = new NBTTagCompound();
        shared.setInteger("dx", 4);
        shared.setInteger("x", 4);
        shared.setString("structureId", "machine-a");
        NBTTagList memberships = new NBTTagList();
        memberships.appendTag(new NBTTagString("machine-a"));
        memberships.appendTag(new NBTTagString("machine-b"));
        memberships.appendTag(new NBTTagString("machine-b"));
        memberships.appendTag(new NBTTagString(""));
        shared.setTag("structureIds", memberships);
        cells.appendTag(shared);
        NBTTagCompound last = new NBTTagCompound();
        last.setInteger("dx", 9);
        last.setInteger("x", 9);
        last.setString("structureId", "machine-b");
        last.setTag("structureIds", new NBTTagList());
        cells.appendTag(last);
        data.setTag("cells", cells);
        HologramState state = new HologramState(data);
        SimpleAssert.eq(3, state.cells.size(), "shared wall remains one captured/indexed/renderable cell");
        SimpleAssert.that(
            state.cells.get(0)
                .belongsToStructure("machine-a")
                && state.cells.get(2)
                    .belongsToStructure("machine-b"),
            "absent and empty membership lists retain legacy primary ID");
        SimpleAssert
            .eq(2, state.cells.get(1).structureIds.size(), "membership snapshot filters duplicate and empty IDs");
        SimpleAssert.that(
            state.cells.get(1)
                .belongsToStructure("machine-a")
                && state.cells.get(1)
                    .belongsToStructure("machine-b"),
            "shared wall belongs to both declared structures");
        HologramSelection selection = new HologramSelection();
        selection.structure(state, "machine-a", false);
        SimpleAssert.that(
            selection.size() == 2 && selection.contains(0) && selection.contains(1) && !selection.contains(2),
            "first structure selects shared wall plus its own exclusive cell");
        SimpleAssert.that(selection.movable(state), "complete first structure may move despite a shared wall");
        selection.structure(state, "machine-b", false);
        SimpleAssert.that(
            selection.size() == 2 && !selection.contains(0) && selection.contains(1) && selection.contains(2),
            "secondary membership selects shared wall even when primary ID belongs to another structure");
        SimpleAssert.that(
            "machine-b".equals(selection.structureId(state, "machine-a", selection.first())),
            "clicked secondary group takes precedence over both active group and shared first cell primary ID");
        selection.click(1, false);
        SimpleAssert.that(
            "machine-a".equals(selection.structureId(state, "", selection.first())),
            "single-cell click resets edge intent and resolves its primary group");
        SimpleAssert.that(
            "machine-b".equals(selection.structureId(state, "machine-b", selection.first())),
            "without edge intent an explicit active group takes precedence over shared primary membership");
        selection.structure(state, "machine-b", false);
        NBTTagCompound missingGroupData = (NBTTagCompound) data.copy();
        NBTTagList declared = new NBTTagList();
        NBTTagCompound remaining = new NBTTagCompound();
        remaining.setString("id", "machine-a");
        declared.appendTag(remaining);
        missingGroupData.setTag("structures", declared);
        HologramState missingGroup = new HologramState(missingGroupData);
        SimpleAssert.that(
            "machine-a".equals(selection.structureId(missingGroup, "machine-b", selection.first())),
            "snapshot declaration removal rejects stale clicked and active group even if shared membership tags remain");
        SimpleAssert.that(
            "machine-a".equals(selection.structureId(state, "", selection.first())),
            "returning group cannot resurrect the cleared clicked-edge intent");
        selection.clear();
        SimpleAssert.that(
            selection.structureId(state, "", -1)
                .isEmpty(),
            "clearing selection resets clicked group");
        selection.structure(state, "machine-b", false);
        selection.structure(state, "machine-a", true);
        SimpleAssert.eq(3, selection.size(), "additive two-structure selection never duplicates shared wall index");
        java.util.Map<String, int[]> bounds = HologramRenderer.structureBounds(state);
        SimpleAssert.eq(2, bounds.size(), "renderer retains independently selectable boxes for both structures");
        SimpleAssert.eq(-2, bounds.get("machine-a")[0], "first box starts at its exclusive cell");
        SimpleAssert.eq(4, bounds.get("machine-a")[3], "shared wall extends first box maximum");
        SimpleAssert.eq(4, bounds.get("machine-b")[0], "shared wall extends second box minimum");
        SimpleAssert.eq(9, bounds.get("machine-b")[3], "second box ends at its exclusive cell");
    }
}
