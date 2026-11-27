// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.hologram.HologramPreviewPose;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteSpatialIndex;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteTopology;

/** Validates front-face identity without a GL context or mutable GT prototypes. */
public final class HologramRenderingRegression {

    private HologramRenderingRegression() {}

    public static void run() {
        previewOutlineDensity();
        completePreviewMaterial();
        occupancyBoundary();
        detachedTopologyRuns();
        editorDescriptorOwnership();
        worldRegionCandidates();
        worldIndexPublications();
        sourceSwitchFence();
        cameraNavigationWhilePreparing();
        orderedPreviewBatches();
        standardLineBatchBoundary();
        for (int front = 0; front < 6; front++) {
            boolean[] seen = new boolean[6];
            for (int side = 0; side < 6; side++) {
                int source = HologramTextures.inventorySide(front, side);
                SimpleAssert.that(source >= 0 && source < 6, "every world face resolves to an inventory face");
                SimpleAssert.that(!seen[source], "orientation never duplicates or loses an inventory face");
                seen[source] = true;
            }
            SimpleAssert.eq(
                4,
                HologramTextures.inventorySide(front, front),
                "GT inventory WEST front follows the actual world front");
            SimpleAssert
                .eq(5, HologramTextures.inventorySide(front, front ^ 1), "inventory rear remains opposite the front");
        }
        for (int side = 0; side < 6; side++) {
            SimpleAssert
                .eq(side, HologramTextures.inventorySide(-1, side), "old snapshots without orientation remain safe");
        }
        NBTTagCompound cellData = new NBTTagCompound();
        cellData.setInteger("actualFacing", 3);
        cellData.setInteger("targetFacing", 5);
        cellData.setInteger("hatchFacing", 2);
        HologramState.Cell cell = new HologramState.Cell(0, cellData);
        SimpleAssert.eq(3, cell.actualFacing, "current preview retains the actual front");
        SimpleAssert.eq(5, cell.targetFacing, "target preview retains its independent planned front");
    }

    private static void previewOutlineDensity() {
        SimpleAssert.eq(
            0f,
            MiaoVisonLiteRenderer.previewOutlineDensity(1, 1, 1),
            "subpixel default grids do not cover their material");
        SimpleAssert
            .eq(0f, MiaoVisonLiteRenderer.previewOutlineDensity(2, 9, 9), "two physical pixels is the hidden-grid boundary");
        SimpleAssert.eq(
            .5f,
            MiaoVisonLiteRenderer.previewOutlineDensity(4, 9, 9),
            "four physical pixels gives half default wire opacity");
        SimpleAssert.eq(
            1f,
            MiaoVisonLiteRenderer.previewOutlineDensity(6, 9, 9),
            "six physical pixels restores the existing close view");
        SimpleAssert.eq(
            1f,
            MiaoVisonLiteRenderer.previewOutlineDensity(8, 0, 8),
            "an exactly head-on axis does not suppress a readable top view");
        float small = MiaoVisonLiteRenderer.previewOutlineDensity(3, 4, 5);
        float enlarged = MiaoVisonLiteRenderer.previewOutlineDensity(6, 8, 10);
        SimpleAssert
            .that(small > 0 && enlarged > small, "zoom or framebuffer scaling restores the same cached grid gradually");
        SimpleAssert.eq(
            0f,
            MiaoVisonLiteRenderer.previewOutlineAlpha(false, false, false, true, 0),
            "dense ordinary material wireframes are skipped");
        SimpleAssert.eq(
            .45f,
            MiaoVisonLiteRenderer.previewOutlineAlpha(false, false, false, true, 1),
            "close-view ordinary opacity is unchanged");
        SimpleAssert.eq(
            1f,
            MiaoVisonLiteRenderer.previewOutlineAlpha(true, false, false, true, 0),
            "chosen cells keep their full highlight at every zoom");
        SimpleAssert.eq(
            1f,
            MiaoVisonLiteRenderer.previewOutlineAlpha(false, true, false, true, 0),
            "related cells keep their full highlight at every zoom");
        SimpleAssert.eq(
            .18f,
            MiaoVisonLiteRenderer.previewOutlineAlpha(false, false, true, true, 0),
            "explicit air retains its original opacity");
        SimpleAssert.eq(
            .45f,
            MiaoVisonLiteRenderer.previewOutlineAlpha(false, false, false, false, 0),
            "unknown or diagnostic models retain their original wireframe");
    }

    private static void occupancyBoundary() {
        HologramSelectionOutline.Occupancy one = (x, y, z) -> x == 0 && y == 0 && z == 0;
        SimpleAssert
            .eq(4095, MiaoVisonLiteRenderer.boundaryEdges(0, 0, 0, one), "a lone occupied voxel has its twelve real edges");
        HologramSelectionOutline.Occupancy pair = (x, y, z) -> (x == 0 || x == 1) && y == 0 && z == 0;
        SimpleAssert.that(
            (MiaoVisonLiteRenderer.boundaryEdges(0, 0, 0, pair) & 1 << 1) == 0,
            "coplanar neighboring voxels do not add the shared vertical seam");
        HologramSelectionOutline.Occupancy ring = (x, y,
            z) -> y == 0 && x >= 0 && x <= 2 && z >= 0 && z <= 2 && (x != 1 || z != 1);
        SimpleAssert.that(
            (MiaoVisonLiteRenderer.boundaryEdges(0, 0, 0, ring) & 1 << 5) != 0,
            "an inner concave corner around a hole survives instead of becoming a bounding box");
        int original = 0, rotated = 0;
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) if (ring.contains(x, 0, z)) {
            original += Integer.bitCount(MiaoVisonLiteRenderer.boundaryEdges(x, 0, z, ring));
            rotated += Integer
                .bitCount(MiaoVisonLiteRenderer.boundaryEdges(-z, 0, x, (rx, ry, rz) -> ring.contains(rz, ry, -rx)));
        }
        SimpleAssert.eq(original, rotated, "quarter turns preserve all hole and outer boundary segments");
        NBTTagCompound source = boundarySource("boundary");
        NBTTagList cells = new NBTTagList();
        cells.appendTag(boundaryCell(15, 0, 0, "minecraft:glass"));
        cells.appendTag(boundaryCell(-17, -16, -16, "minecraft:stone"));
        cells.appendTag(boundaryCell(16, 0, 0, "minecraft:stone"));
        cells.appendTag(boundaryCell(-16, -16, -16, "minecraft:glass"));
        source.setTag("cells", cells);
        MiaoVisonLiteRenderer.PreviewBoundary boundary = new MiaoVisonLiteRenderer.PreviewBoundary(new HologramState(source));
        int steps = 0;
        while (!boundary.complete() && steps++ < 20) {
            int count = boundary.cells.size();
            boolean indexing = boundary.stage == 0;
            boundary.advance(Integer.MAX_VALUE, 0, false, 1, Long.MAX_VALUE);
            SimpleAssert.that(
                boundary.cells.size() - count <= (indexing ? 0 : 1),
                "bounded cursor waits for complete occupancy and then emits at most one boundary cell per step");
        }
        SimpleAssert
            .that(boundary.complete() && steps > 2, "the real occupancy cursor completes through bounded steps");
        SimpleAssert.eq(
            4,
            boundary.cells.size(),
            "both transparent and solid occupied cells survive inside positive and negative region seams");
        SimpleAssert.eq(4, boundary.occupancy.size(), "the boundary uses the existing spatial region identities");
        SimpleAssert
            .eq(0, boundary.projected, "building real occupancy never needs projection, textures or a native model");
        localBoundaryMasks();
    }

    private static void localBoundaryMasks() {
        java.util.Random random = new java.util.Random(793310L);
        for (int[] center : new int[][] { { -17, -17, -17 }, { -16, -16, -16 }, { -1, 0, 15 }, { 15, 16, -16 },
            { 0, -1, -17 }, { 7, -25, 24 } }) {
            for (int axis = 0; axis < 3; axis++) for (int plane = 0; plane < 256; plane++) {
                int occupied = 1 << 13, sourceBit = 0;
                for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++) {
                    if (u == 0 && v == 0) continue;
                    if ((plane & 1 << sourceBit++) == 0) continue;
                    int x = axis == 0 ? 0 : u;
                    int y = axis == 0 ? u : axis == 1 ? 0 : v;
                    int z = axis == 2 ? 0 : v;
                    occupied |= 1 << ((x + 1) * 9 + (y + 1) * 3 + z + 1);
                }
                assertLocalBoundary(center[0], center[1], center[2], occupied);
            }
            for (int sample = 0; sample < 512; sample++)
                assertLocalBoundary(center[0], center[1], center[2], random.nextInt(1 << 27) | 1 << 13);
        }
    }

    private static void editorDescriptorOwnership() {
        String json = "{\"schema\":2,\"blueprint\":{\"schema\":2,\"cells\":["
            + "{\"x\":15,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\"},"
            + "{\"x\":16,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\"}],\"palette\":[]}}";
        try {
            byte[] exact = HologramBlueprintCompactCodec.recompress(
                json.getBytes(StandardCharsets.UTF_8), HologramBlueprintArtifact.MAX_JSON_BYTES);
            var editor = HologramLocalBlueprintDocument.openForEditor(exact, "", () -> true).editor();
            HologramState.Preparation prep = new HologramState.Preparation(
                editor.metadata(), true, new HologramBlueprintPreview.CompactCells(editor));
            prep.step(1, Long.MAX_VALUE);
            SimpleAssert.that(prep.firstDisplay().plainDisplayExamples.isEmpty(), "never schedule a partial editor");
            while (!prep.step(2, Long.MAX_VALUE)) {}
            HologramState state = prep.result();
            SimpleAssert.eq(1, state.plainDisplayExamples.size(), "one frozen appearance, even across region seams");
            HologramState.Cell first = state.cells.get(0), second = state.cells.get(1);
            SimpleAssert.that(first.descriptorIdentity() == second.descriptorIdentity(), "same template for topology");
            SimpleAssert.that(first.display(false) != second.display(false) && first.hintIcons != second.hintIcons
                && first.candidates != second.candidates, "editor still owns its per-cell mutable values");
            first.display(false).setInteger("test", 7);
            SimpleAssert.that(!second.display(false).hasKey("test"), "editing a row cannot modify its neighbor");
        } catch (IOException invalid) {
            throw new AssertionError(invalid);
        }
    }

    private static void detachedTopologyRuns() {
        NBTTagCompound source = boundarySource("detached-topology");
        NBTTagList rows = new NBTTagList();
        for (int x = -19; x <= -13; x++) {
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
                rows.appendTag(boundaryCell(x, y, z, x == -16 ? "minecraft:glass" : "minecraft:stone"));
        }
        source.setTag("cells", rows);
        HologramState state = new HologramState(source);
        MiaoVisonLiteRenderer.OrdinaryTemplate opaque = new MiaoVisonLiteRenderer.OrdinaryTemplate(
            true,
            true,
            "",
            null);
        MiaoVisonLiteRenderer.OrdinaryTemplate transparent = new MiaoVisonLiteRenderer.OrdinaryTemplate(
            true,
            false,
            "",
            null);
        java.util.Map<Object, MiaoVisonLiteRenderer.OrdinaryTemplate> material = new java.util.IdentityHashMap<>();
        for (HologramState.Cell cell : state.cells) {
            if (cell.x == -16 && cell.y == 0 && cell.z == 0) continue;
            material.put(cell.descriptorIdentity(), cell.x == -16 ? transparent : opaque);
        }
        int ordinary = 0, fallback = 0, interior = 0;
        MiaoVisonLiteRenderer.PreviewTopologyView topology = new MiaoVisonLiteRenderer.PreviewTopologyView(
            state.regions,
            material);
        for (List<HologramState.Cell> region : state.regions.regions.values()) {
            int next = 0;
            for (MiaoVisonLiteTopology.Run<HologramState.Cell, MiaoVisonLiteRenderer.PreviewCell> run : MiaoVisonLiteTopology
                .prepareRegion(region, topology)) {
                int drawn = 0, start = next;
                for (; next < run.end; next++) {
                    HologramState.Cell cell = region.get(next);
                    int exposed = 0;
                    boolean known = material.containsKey(cell.descriptorIdentity());
                    int[][] offset = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
                        { 1, 0, 0 } };
                    for (int side = 0; side < offset.length; side++) {
                        int[] delta = offset[side];
                        HologramState.Cell neighbor = state.regions
                            .at(cell.x + delta[0], cell.y + delta[1], cell.z + delta[2]);
                        if (neighbor != null && !material.containsKey(neighbor.descriptorIdentity())) known = false;
                        if (neighbor == null || "minecraft:glass".equals(neighbor.wantId)) exposed |= 1 << side;
                    }
                    SimpleAssert
                        .eq(known, run.ordinary, "unknown models and touching ordinary cells stay on the client");
                    if (!known) {
                        fallback++;
                        continue;
                    }
                    ordinary++;
                    SimpleAssert.that(run.visible.get(next - start) == cell, "batch preserves source cell and order");
                    if (exposed == 0) {
                        interior++;
                        continue;
                    }
                    MiaoVisonLiteRenderer.PreviewCell face = run.faces.get(drawn++);
                    SimpleAssert.that(face.cell == cell, "visible geometry retains the original pick identity");
                    SimpleAssert.eq(next + 1, face.sourceOrdinal, "worker uses the original region ordinal");
                    SimpleAssert.eq(exposed, face.exposed, "worker preserves the exact exposed-face mask");
                }
                SimpleAssert.eq(drawn, run.faces.size(), "only cells with visible faces enter the depth ordering");
            }
            SimpleAssert.eq(region.size(), next, "every region source row is admitted exactly once");
        }
        SimpleAssert.eq(7, fallback, "special fallback includes only the unknown cell and its six immediate neighbors");
        SimpleAssert.eq(state.cells.size() - fallback, ordinary, "remaining cells complete in ordinary runs");
        SimpleAssert.that(interior > 0, "covered ordinary cells skip projections even across negative region seams");
        java.util.Deque<java.util.concurrent.FutureTask<Void>> queue = new java.util.ArrayDeque<>();
        HologramRegionCache.Key first = state.regions.regions.keySet()
            .iterator()
            .next();
        try (
            MiaoVisonLiteTopology<HologramRegionCache.Key, HologramState.Cell, MiaoVisonLiteRenderer.PreviewCell> job = MiaoVisonLiteTopology
                .start(state.regions.regions, topology, queue::offer, queue::remove)) {
            SimpleAssert.that(!job.done() && job.take(first) == null, "frame never waits for queued topology");
            queue.remove()
                .run();
            SimpleAssert
                .that(job.done() && job.take(first) != null, "finished region is available to the render thread");
            SimpleAssert.eq(null, job.take(first), "each finished region can transfer ownership only once");
        }
        MiaoVisonLiteTopology<HologramRegionCache.Key, HologramState.Cell, MiaoVisonLiteRenderer.PreviewCell> cancelled = MiaoVisonLiteTopology
            .start(state.regions.regions, topology, queue::offer, queue::remove);
        java.util.concurrent.FutureTask<Void> abandoned = queue.element();
        cancelled.close();
        abandoned.run();
        SimpleAssert.that(
            queue.isEmpty() && cancelled.done() && cancelled.take(first) == null,
            "closing removes queued work and an old task can never publish into the next preview");
        SimpleAssert.eq(
            null,
            MiaoVisonLiteTopology.start(state.regions.regions, topology, task -> false, queue::remove),
            "a busy shared worker falls through to the render-thread cursor");
    }

    private static void assertLocalBoundary(int cx, int cy, int cz, int occupied) {
        java.util.BitSet[] regions = new java.util.BitSet[27];
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int neighbor = (dx + 1) * 9 + (dy + 1) * 3 + dz + 1;
            if ((occupied & 1 << neighbor) == 0) continue;
            int x = cx + dx, y = cy + dy, z = cz + dz;
            int region = ((x >> 4) - (cx >> 4) + 1) * 9 + ((y >> 4) - (cy >> 4) + 1) * 3 + (z >> 4) - (cz >> 4) + 1;
            if (regions[region] == null) regions[region] = new java.util.BitSet();
            regions[region].set(((x & 15) << 8) | ((y & 15) << 4) | (z & 15));
        }
        HologramSelectionOutline.Occupancy cells = (x, y, z) -> {
            int dx = x - cx, dy = y - cy, dz = z - cz;
            return (occupied & 1 << ((dx + 1) * 9 + (dy + 1) * 3 + dz + 1)) != 0;
        };
        int expected = MiaoVisonLiteRenderer.boundaryEdges(cx, cy, cz, cells);
        int[] queried = { 0 };
        int world = MiaoVisonLiteRenderer.BoundaryNeighborhood.worldEdges(cx, cy, cz, (x, y, z) -> {
            int bit = 1 << ((x - cx + 1) * 9 + (y - cy + 1) * 3 + z - cz + 1);
            if ((queried[0] & bit) != 0) throw new AssertionError("repeated world outline occupancy read");
            queried[0] |= bit;
            return cells.contains(x, y, z);
        });
        SimpleAssert.eq(expected, world, "world outline preserves exact per-cell edge ownership");
        SimpleAssert.eq(19, Integer.bitCount(queried[0]), "world outline reads only the center and 18 neighbors");
        SimpleAssert.eq(0, MiaoVisonLiteRenderer.BoundaryNeighborhood.worldEdges(cx, cy, cz,
            (x, y, z) -> (x != cx || y != cy || z != cz) && cells.contains(x, y, z)),
            "removed center owns no edge even with adjacent occupied cells");
        int local = ((cx & 15) << 8) | ((cy & 15) << 4) | (cz & 15);
        int actual = MiaoVisonLiteRenderer.BoundaryNeighborhood.edges(regions, local);
        SimpleAssert.eq(expected, actual, "all twelve edge positions match the original ownership and crease rule");
    }

    /** A metadata receipt may arrive between any two cooperative world-index slices. No GL context is needed. */
    static void worldIndexPublications() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "world-index");
        source.setInteger("mode", 2);
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < 1000; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", i);
            row.setString("id", "minecraft:stone");
            row.setString("wantId", "minecraft:air");
            rows.appendTag(row);
        }
        source.setTag("cells", rows);
        HologramState before = new HologramState(source);
        Class<?> renderer = MiaoVisonLiteRenderer.class;
        try {
            Field owner = renderer.getDeclaredField("worldState"),
                cursor = renderer.getDeclaredField("worldIndex"),
                occupancy = renderer.getDeclaredField("worldOutlineCells");
            Method one = renderer.getDeclaredMethod("indexWorldCell", HologramState.class, HologramState.Cell.class),
                finish = renderer.getDeclaredMethod("indexWorld", HologramState.class, long.class);
            for (AccessibleObject field : new AccessibleObject[] {
                owner, cursor, occupancy, one, finish }) field.setAccessible(true);
            int[][] trials = { { 100, -1 }, { 0, -1 }, { 1000, -1 }, { 100, 3 }, { 100, 900 }, { 1000, 3 } };
            for (int[] trial : trials) {
                MiaoVisonLiteRenderer.releaseWorld();
                owner.set(null, before);
                int prefix = trial[0], changed = trial[1];
                for (int i = 0; i < prefix; i++) one.invoke(null, before, before.cells.get(i));
                cursor.setInt(null, prefix);
                NBTTagCompound patch = new NBTTagCompound();
                patch.setBoolean("partial", true);
                patch.setString("session", before.session);
                patch.setLong("baseRevision", 0);
                patch.setLong("planRevision", 1);
                patch.setInteger("cellCount", before.cells.size());
                patch.setLong("uiSequence", 7);
                if (changed >= 0) {
                    NBTTagCompound row = (NBTTagCompound) rows.getCompoundTagAt(changed).copy();
                    row.setInteger("index", changed);
                    row.setString("id", "minecraft:air");
                    NBTTagList delta = new NBTTagList();
                    delta.appendTag(row);
                    patch.setTag("cells", delta);
                }
                HologramState after = HologramState.apply(before, patch);
                if (changed < 0) SimpleAssert.that(after.cells == before.cells && after.regions == before.regions,
                    "metadata shares original geometry");
                HologramRenderer.snapshotChanged(before, after);
                int saved = cursor.getInt(null);
                finish.invoke(null, after, Long.MAX_VALUE);
                int total = ((java.util.Set<?>) occupancy.get(null)).size();
                SimpleAssert.eq(changed < 0 ? 1000 : 999, total, "all unfiltered cells must reach world occupancy");
                SimpleAssert.eq(changed < 0 || prefix == 1000 ? prefix : 0, saved,
                    "retain metadata prefix, restart incomplete changed geometry, patch complete geometry in place");
                SimpleAssert.that(owner.get(null) == after && cursor.getInt(null) == after.cells.size(),
                    "the new state owns the fully resumed index");
                HologramRenderer.snapshotChanged(after, before);
                SimpleAssert.that(owner.get(null) == null && cursor.getInt(null) == 0,
                    "a reverse publication cannot inherit another state's occupancy");
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        } finally {
            MiaoVisonLiteRenderer.releaseWorld();
        }
    }

    private static void worldRegionCandidates() {
        MiaoVisonLiteSpatialIndex<String> regions = new MiaoVisonLiteSpatialIndex<>(16);
        regions.add(16, 0, 0, "east");
        for (int i = 0; i < 48; i++) regions.add(i * 16, 64, i * -16, "distant");
        regions.add(0, -32, 0, "below");
        regions.add(-32, 0, 0, "west");
        SimpleAssert.eq(
            Arrays.asList("east", "below", "west"),
            regions.select(0, 0, 0, 16),
            "distance boundaries preserve original region submission order");
        SimpleAssert.eq(
            Collections.singletonList("east"),
            regions.select(1, 1, 0, 16),
            "the next query excludes regions beyond the distance bounds");
        regions.clear();
        SimpleAssert.that(
            regions.select(0, 0, 0, 16)
                .isEmpty(),
            "retiring the scene drops all indexed regions");

        HologramPreviewPose pose = new HologramPreviewPose().turn(false)
            .turn(true);
        int[] transformed = pose.apply(-17, 5, 32);
        double[] source = pose.inverse(transformed[0], transformed[1], transformed[2]);
        SimpleAssert.that(
            Arrays.equals(new double[] { -17, 5, 32 }, source),
            "local placement culls using the inverse of its exact quarter turns");
    }

    private static void sourceSwitchFence() {
        HologramState state = new HologramState(boundarySource("old"));
        Object world = new Object();
        HologramUiLibrary.LibraryNavigation navigation = new HologramUiLibrary.LibraryNavigation(
            state,
            world,
            "new",
            false,
            true,
            "blueprintEdit",
            0);
        SimpleAssert.that(
            HologramUiPreview.sourceSwitchWaiting(navigation, state, world, true, true, -1),
            "an actual library source transfer pauses its old scene");
        SimpleAssert.that(
            !HologramUiPreview.sourceSwitchWaiting(null, state, world, true, true, 9),
            "an unrelated pending edit does not pause or flash the current scene");
        SimpleAssert.that(
            !HologramUiPreview.sourceSwitchWaiting(navigation, state, world, true, false, -1),
            "a failed or completed wait immediately restores the same source");
        SimpleAssert.that(
            !HologramUiPreview.sourceSwitchWaiting(navigation, state, new Object(), true, true, 9),
            "a stale world cannot retain a scene wait");
        SimpleAssert.that(
            !HologramUiPreview
                .sourceSwitchWaiting(navigation, new HologramState(boundarySource("new")), world, true, true, 9),
            "a new installed session cannot inherit the old wait");
        navigation.cancel();
        SimpleAssert.that(
            !HologramUiPreview.sourceSwitchWaiting(navigation, state, world, true, true, 9),
            "cancelled source navigation cannot freeze a later scene");
    }

    private static void cameraNavigationWhilePreparing() {
        try {
            NBTTagCompound source = boundarySource("camera-navigation");
            NBTTagList cells = new NBTTagList();
            cells.appendTag(boundaryCell(0, 0, 0, "minecraft:stone"));
            source.setTag("cells", cells);
            HologramState state = new HologramState(source);
            HologramUiModules ui = new HologramUiModules(headlessRenderingHost());
            java.lang.reflect.Field session = HologramUiSession.class.getDeclaredField("state"),
                teaching = HologramUiSession.class.getDeclaredField("tutorialSink"),
                displayed = MiaoVisonLiteRenderer.class.getDeclaredField("displayedState"),
                preparing = MiaoVisonLiteRenderer.class.getDeclaredField("previewPreparation");
            session.setAccessible(true);
            teaching.setAccessible(true);
            displayed.setAccessible(true);
            preparing.setAccessible(true);
            session.set(ui.session, state);
            teaching
                .set(ui.session, (java.util.function.BiConsumer<String, NBTTagCompound>) (operation, request) -> {});
            Class<?> cursor = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewPreparation");
            java.lang.reflect.Constructor<?> constructor = cursor
                .getDeclaredConstructor(MiaoVisonLiteRenderer.class, HologramState.class, boolean.class);
            constructor.setAccessible(true);
            Object previousDisplay = displayed.get(HologramRenderer.editor()), previousPreparation = preparing.get(HologramRenderer.editor());
            try {
                // The scene has submitted this identity's light outline while its actual model cursor is incomplete.
                displayed.set(HologramRenderer.editor(), state);
                preparing.set(HologramRenderer.editor(), constructor.newInstance(HologramRenderer.editor(), state, true));
                SimpleAssert.that(
                    !ui.preview.previewInteractive() && !ui.shell.sceneActive(),
                    "model reprojection keeps picking and geometry command context unavailable");
                SimpleAssert.that(
                    ui.preview.previewNavigable() && ui.shell.sceneNavigable(),
                    "a submitted outline remains available for camera navigation while preparing");
                double zoom = ui.preview.zoom();
                ui.preview.scrollNativeScene(1);
                SimpleAssert
                    .that(ui.preview.zoom() > zoom, "the actual scene-wheel workflow zooms during reprojection");
                SimpleAssert.that(
                    ui.tools.commands()
                        .available("cameraForward"),
                    "camera key commands share the navigation admission instead of edit readiness");
                SimpleAssert.that(
                    !ui.tools.commands()
                        .commandContextAvailable(),
                    "allowing navigation does not reopen edit commands");
                completedPresentationAdmission(ui, state, constructor);
                ui.review.confirmation(true);
                zoom = ui.preview.zoom();
                ui.preview.scrollNativeScene(1);
                SimpleAssert.eq(zoom, ui.preview.zoom(), "a modal review still blocks camera wheel input");
                ui.review.confirmation(false);
                displayed.set(HologramRenderer.editor(), new HologramState(boundarySource("other-source")));
                ui.preview.scrollNativeScene(1);
                SimpleAssert
                    .eq(zoom, ui.preview.zoom(), "another submitted source cannot authorize the current scene camera");
            } finally {
                displayed.set(HologramRenderer.editor(), previousDisplay);
                preparing.set(HologramRenderer.editor(), previousPreparation);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("progressive scene camera navigation", failure);
        }
    }

    private static NBTTagCompound boundarySource(String session) {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", session);
        source.setString("generation", session);
        source.setBoolean("virtual", true);
        source.setLong("planRevision", 1);
        return source;
    }

    private static Object coreSubmission(Object adapter) throws ReflectiveOperationException {
        return field(adapter.getClass(), "submission").get(adapter);
    }

    private static java.lang.reflect.Field coreSubmissionField(Object adapter, String name)
        throws ReflectiveOperationException {
        return field(coreSubmission(adapter).getClass(), name);
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static HologramUiHost headlessRenderingHost() throws ReflectiveOperationException {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        net.minecraft.client.Minecraft client = (net.minecraft.client.Minecraft) unsafe
            .allocateInstance(net.minecraft.client.Minecraft.class);
        HologramScreen parent = (HologramScreen) unsafe.allocateInstance(HologramScreen.class);
        // A real host boundary with a detached inactive client. held("air") observes currentScreen != parent before
        // physical Display/Keyboard polling; no global Minecraft singleton or native renderer is installed.
        return (HologramUiHost) java.lang.reflect.Proxy.newProxyInstance(
            HologramUiHost.class.getClassLoader(),
            new Class<?>[] { HologramUiHost.class },
            (proxy, method, arguments) -> switch (method.getName()) {
            case "minecraft" -> client;
            case "parent" -> parent;
            case "screenWidth" -> HologramScreenLayout.WIDTH;
            case "screenHeight" -> HologramScreenLayout.HEIGHT;
            default -> throw new AssertionError("headless rendering admission requested " + method.getName());
            });
    }

    /**
     * Headless control fixtures explicitly supply the new actual-presentation precondition; no GL success is claimed.
     */
    static Runnable completePresentation(HologramUiModules ui) throws ReflectiveOperationException {
        String[] names = { "previewPreparation", "previewState", "presentedState", "presentedRequest",
            "projectedRequest", "previewSubmitted", "displayedState" };
        Object[] saved = new Object[names.length];
        for (int i = 0; i < names.length; i++) saved[i] = field(MiaoVisonLiteRenderer.class, names[i]).get(HologramRenderer.editor());
        HologramState state = ui.session.state();
        Class<?> preparationType = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewPreparation"),
            projectionType = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewProjection"),
            requestType = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewRequest");
        java.lang.reflect.Constructor<?> preparationConstructor = preparationType
            .getDeclaredConstructor(MiaoVisonLiteRenderer.class, HologramState.class, boolean.class),
            projectionConstructor = projectionType.getDeclaredConstructor(MiaoVisonLiteRenderer.class, HologramState.class, String.class),
            requestConstructor = requestType.getDeclaredConstructors()[0];
        preparationConstructor.setAccessible(true);
        projectionConstructor.setAccessible(true);
        requestConstructor.setAccessible(true);
        Object preparation = preparationConstructor.newInstance(HologramRenderer.editor(), state, false),
            projection = projectionConstructor.newInstance(HologramRenderer.editor(), state, "headless-presented");
        field(preparationType, "completed").setInt(preparation, state.cells.size());
        field(preparationType, "completeGeometryPresented").setBoolean(preparation, true);
        field(projectionType, "published").setInt(projection, state.cells.size());
        field(preparationType, "projection").set(preparation, projection);
        com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates area = ui.canvas.viewport();
        Object request = requestConstructor.newInstance(
            state,
            (int) area.x(),
            (int) area.y(),
            (int) area.width(),
            (int) area.height(),
            ui.preview.yaw(),
            ui.preview.pitch(),
            ui.preview.zoom(),
            0d,
            0d,
            0d,
            Integer.MAX_VALUE,
            0,
            false,
            false);
        field(MiaoVisonLiteRenderer.class, "previewPreparation").set(HologramRenderer.editor(), preparation);
        for (String name : new String[] { "previewState", "presentedState", "displayedState" })
            field(MiaoVisonLiteRenderer.class, name).set(HologramRenderer.editor(), state);
        for (String name : new String[] { "projectedRequest", "presentedRequest" })
            field(MiaoVisonLiteRenderer.class, name).set(HologramRenderer.editor(), request);
        field(MiaoVisonLiteRenderer.class, "previewSubmitted").setBoolean(HologramRenderer.editor(), true);
        return () -> {
            try {
                for (int i = 0; i < names.length; i++) field(MiaoVisonLiteRenderer.class, names[i]).set(HologramRenderer.editor(), saved[i]);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("restore headless presentation", failure);
            }
        };
    }

    private static void completedPresentationAdmission(HologramUiModules ui, HologramState state,
        java.lang.reflect.Constructor<?> preparationConstructor) throws ReflectiveOperationException {
        String[] fields = { "previewState", "presentedState", "presentedRequest", "projectedRequest", "previewKey",
            "previewSubmitted", "previewPreparation" };
        Object[] saved = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) saved[i] = field(MiaoVisonLiteRenderer.class, fields[i]).get(HologramRenderer.editor());
        try {
            Object preparation = preparationConstructor.newInstance(HologramRenderer.editor(), state, false);
            Class<?> type = preparation.getClass();
            Class<?> projectionType = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewProjection");
            java.lang.reflect.Constructor<?> projectionConstructor = projectionType
                .getDeclaredConstructor(MiaoVisonLiteRenderer.class, HologramState.class, String.class);
            projectionConstructor.setAccessible(true);
            Object projection = projectionConstructor.newInstance(HologramRenderer.editor(), state, "previous-pose");
            field(type, "completed").setInt(preparation, state.cells.size());
            field(projectionType, "published").setInt(projection, state.cells.size());
            field(type, "projection").set(preparation, projection);
            field(MiaoVisonLiteRenderer.class, "previewPreparation").set(HologramRenderer.editor(), preparation);
            field(MiaoVisonLiteRenderer.class, "previewState").set(HologramRenderer.editor(), state);
            field(MiaoVisonLiteRenderer.class, "presentedState").set(HologramRenderer.editor(), state);
            field(MiaoVisonLiteRenderer.class, "previewKey").set(HologramRenderer.editor(), "previous-pose");
            field(MiaoVisonLiteRenderer.class, "previewSubmitted").setBoolean(HologramRenderer.editor(), true);
            Class<?> requestType = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewRequest");
            java.lang.reflect.Constructor<?> requestConstructor = requestType.getDeclaredConstructors()[0];
            requestConstructor.setAccessible(true);
            com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates area = ui.canvas.viewport();
            Object request = requestConstructor.newInstance(
                state,
                (int) area.x(),
                (int) area.y(),
                (int) area.width(),
                (int) area.height(),
                ui.preview.yaw(),
                ui.preview.pitch(),
                ui.preview.zoom(),
                0d,
                0d,
                0d,
                Integer.MAX_VALUE,
                0,
                false,
                false);
            field(MiaoVisonLiteRenderer.class, "presentedRequest").set(HologramRenderer.editor(), request);
            field(MiaoVisonLiteRenderer.class, "projectedRequest").set(HologramRenderer.editor(), request);
            java.lang.reflect.Method cameraOnly = MiaoVisonLiteRenderer.class.getDeclaredMethod(
                "preparedCameraOnly",
                HologramState.class,
                String.class,
                int.class,
                int.class,
                boolean.class);
            cameraOnly.setAccessible(true);
            SimpleAssert.that(
                !(boolean) cameraOnly.invoke(HologramRenderer.editor(), state, "next-pose", Integer.MAX_VALUE, 0, false),
                "merely reaching N in a cold/dirty cursor cannot select synchronous camera presentation");
            field(type, "completeGeometryPresented").setBoolean(preparation, true);
            SimpleAssert.that(
                (boolean) cameraOnly.invoke(HologramRenderer.editor(), state, "next-pose", Integer.MAX_VALUE, 0, false),
                "a previously completely drawn preparation admits the next camera before it is presented");
            SimpleAssert.that(
                !(boolean) cameraOnly.invoke(HologramRenderer.editor(), state, "next-pose", 0, 0, false),
                "a new layer is preparation invalidation, not a camera-only change");
            SimpleAssert.that(
                !(boolean) cameraOnly.invoke(HologramRenderer.editor(), state, "next-pose", Integer.MAX_VALUE, 1, false),
                "a new view is not a camera-only change");
            SimpleAssert.that(
                !(boolean) cameraOnly.invoke(HologramRenderer.editor(), state, "next-pose", Integer.MAX_VALUE, 0, true),
                "a visibility change is not a camera-only change");
            SimpleAssert.that(
                !(boolean) cameraOnly
                    .invoke(HologramRenderer.editor(), new HologramState(state.fullData()), "next-pose", Integer.MAX_VALUE, 0, false),
                "another snapshot cannot borrow the old complete geometry stamp");
            SimpleAssert.that(ui.preview.previewInteractive(), "the exact complete presentation admits editing");
            ui.preview.scrollNativeScene(1);
            SimpleAssert.that(
                !ui.preview.previewInteractive() && ui.preview.previewNavigable(),
                "wheel changes desired immediately: editing closes before the next draw but navigation stays open");
            ui.preview.scrollNativeScene(1);
            SimpleAssert.that(
                ui.preview.zoom() > (double) field(requestType, "zoom").get(request),
                "a second wheel is accepted without waiting for a replacement presentation");
            int startX = (int) area.x() + 40, startY = (int) area.y() + 40;
            SimpleAssert.that(
                ui.preview.consumeViewportRightMouse(startX, startY, 1),
                "right navigation starts while the newly desired zoom is still unpresented");
            SimpleAssert.that(
                ui.preview.cameraGestureActive(),
                "an owned navigation gesture suppresses cold captures even before its next integer pointer move");
            ui.preview.mouseClickMove(startX + 8, startY, 1, 10);
            double firstYaw = ui.preview.yaw();
            ui.preview.mouseClickMove(startX + 16, startY, 1, 20);
            SimpleAssert.that(
                ui.preview.yaw() < firstYaw,
                "successive navigation moves are not blocked by the strict edit stamp");
            ui.preview.mouseMovedOrUp(startX + 16, startY, 1);
            SimpleAssert.that(
                !ui.preview.cameraGestureActive(),
                "releasing navigation immediately restores capture admission without a timer or pose delay");
            HologramGizmo gizmo = (HologramGizmo) field(HologramUiPreview.class, "gizmo").get(ui.preview);
            HologramGizmo textGizmo = (HologramGizmo) field(HologramUiTextBoxes.class, "gizmo").get(ui.textBoxes);
            gizmo.begin(0, 1, 1);
            textGizmo.begin(0, 1, 1);
            ui.preview.mouseMovedOrUp(40, 40, 0);
            SimpleAssert.eq(-1, gizmo.axis(), "stale release cancels the block gizmo without committing a move");
            SimpleAssert.eq(-1, textGizmo.axis(), "stale release also cancels the independent text capture");
            field(MiaoVisonLiteRenderer.class, "presentedRequest").set(HologramRenderer.editor(), null);
            SimpleAssert.that(!ui.preview.previewInteractive(), "a closed/unsubmitted scene cannot admit edits");
        } finally {
            for (int i = 0; i < fields.length; i++) field(MiaoVisonLiteRenderer.class, fields[i]).set(HologramRenderer.editor(), saved[i]);
        }
    }

    private static NBTTagCompound boundaryCell(int x, int y, int z, String block) {
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", x);
        cell.setInteger("dx", x);
        cell.setInteger("y", y);
        cell.setInteger("dy", y);
        cell.setInteger("z", z);
        cell.setInteger("dz", z);
        cell.setString("wantId", block);
        return cell;
    }

    @SuppressWarnings("unchecked")
    private static void orderedPreviewBatches() {
        try {
            java.lang.reflect.Constructor<TextureAtlasSprite> iconConstructor = TextureAtlasSprite.class
                .getDeclaredConstructor(String.class);
            iconConstructor.setAccessible(true);
            TextureAtlasSprite icon = iconConstructor.newInstance("fixture:static");
            icon.setIconWidth(16);
            icon.setIconHeight(16);
            icon.initSprite(64, 64, 16, 16, false);
            SimpleAssert.that(MiaoVisonLiteRenderer.stablePreviewIcon(icon), "exact static atlas UVs permit slice reuse");
            SimpleAssert.that(
                !MiaoVisonLiteRenderer.stablePreviewIcon(new TextureAtlasSprite("fixture:custom") {}),
                "custom sprite subclasses keep their live UV callbacks");
            TextureAtlasSprite animated = iconConstructor.newInstance("fixture:animated");
            animated.setFramesTextureData(java.util.Arrays.asList(new int[1][], new int[1][]));
            SimpleAssert
                .that(!MiaoVisonLiteRenderer.stablePreviewIcon(animated), "multi-frame sprites retain live commands");
            List<HologramTextures.Sample>[] sides = new List[6];
            for (int side = 0; side < 6; side++) sides[side] = java.util.Arrays
                .asList(new HologramTextures.Sample(icon, 0xff0000), new HologramTextures.Sample(icon, 0x00ff00));
            HologramTextures.Material material = new HologramTextures.Material(
                sides,
                sides,
                HologramGeometry.cube(),
                false,
                false,
                false,
                false);
            HologramState.Cell cell = new HologramState.Cell(0, boundaryCell(0, 0, 0, "minecraft:stone"));
            java.lang.reflect.Field listener = HologramTextures.class.getDeclaredField("reloadInstalled");
            listener.setAccessible(true);
            HologramPreviewAssets assets = HologramPreviewAssets.EDITOR;
            java.util.Map<String, java.util.Map<Integer, HologramTextures.Material>> materials = assets.textures.plain;
            boolean installed = listener.getBoolean(null);
            HologramState previousNative = assets.blocks.snapshot;
            HologramSelection previousSelection = HologramRenderer.editor().selection;
            int[] previousOffset = HologramRenderer.editor().moveOffset;
            listener.setBoolean(null, true);
            assets.blocks.snapshot = null;
            java.util.Map<Integer, HologramTextures.Material> previousStone = materials
                .put("minecraft:stone", Collections.singletonMap(0, material));
            try {
                completeCameraProjection(material, materials);
                tutorialSnapshotOwnership();
                Class<?> faceClass = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$Face");
                java.lang.reflect.Constructor<?> faceConstructor = faceClass.getDeclaredConstructor(
                    HologramState.Cell.class,
                    double[].class,
                    int.class,
                    boolean.class,
                    HologramGeometry.Surface.class);
                faceConstructor.setAccessible(true);
                List<Object> faces = new java.util.ArrayList<>();
                for (int face = 0; face < 1025; face++) {
                    double[] points = new double[24];
                    for (int vertex = 0; vertex < 8; vertex++) {
                        points[vertex * 3] = face * 10 + vertex;
                        points[vertex * 3 + 1] = vertex;
                        points[vertex * 3 + 2] = face;
                    }
                    faces.add(faceConstructor.newInstance(cell, points, 1, false, material.geometry.get(1)));
                }
                Class<?> submissionClass = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewSubmission");
                java.lang.reflect.Constructor<?> submissionConstructor = submissionClass
                    .getDeclaredConstructor(List.class, double.class);
                java.lang.reflect.Method draw = submissionClass.getDeclaredMethod("draw", Tessellator.class);
                submissionConstructor.setAccessible(true);
                draw.setAccessible(true);
                java.lang.reflect.Method drawWithoutCapture = submissionClass
                    .getDeclaredMethod("draw", Tessellator.class, boolean.class);
                drawWithoutCapture.setAccessible(true);
                Object deferred = submissionConstructor.newInstance(faces, 2);
                RecordingTessellator deferredTess = new RecordingTessellator();
                drawWithoutCapture.invoke(deferred, deferredTess, false);
                drawWithoutCapture.invoke(deferred, deferredTess, false);
                SimpleAssert.eq(
                    16400,
                    deferredTess.vertices.size(),
                    "stationary poses within a gesture still emit all ordered layered commands exactly once");
                SimpleAssert.that(
                    !coreSubmissionField(deferred, "unsupported").getBoolean(coreSubmission(deferred)),
                    "gesture admission skips capture itself rather than causing permanent unsupported fallback");
                SimpleAssert.eq(
                    0,
                    coreSubmissionField(deferred, "retainedInts").getInt(coreSubmission(deferred)),
                    "deferred cold capture retains no raw slice ints");
                drawWithoutCapture.invoke(deferred, deferredTess, true);
                SimpleAssert.that(
                    coreSubmissionField(deferred, "unsupported").getBoolean(coreSubmission(deferred)),
                    "ending the gesture reopens actual capture, whose existing foreign-tessellator fallback is retained");
                SimpleAssert.eq(
                    24600,
                    deferredTess.vertices.size(),
                    "reopened unsupported capture still executes each original command once");
                Object submission = submissionConstructor.newInstance(faces, 2);
                RecordingTessellator tess = new RecordingTessellator();
                draw.invoke(submission, tess);
                SimpleAssert.eq(
                    java.util.Arrays.asList(4092, 4092, 16),
                    tess.batchSizes,
                    "real submission partitions complete quads below the vanilla buffer shrink threshold");
                SimpleAssert.eq(8200, tess.vertices.size(), "unsupported capture executes every original vertex once");
                double[] boundary = tess.vertices.get(4092);
                SimpleAssert.eq(5113d, boundary[0], "batch boundary preserves the original face order");
                SimpleAssert
                    .eq(1022.015d, boundary[2], "batch boundary preserves a split face's second UV layer depth");
                SimpleAssert.eq(.96f, (float) boundary[7], "static submission keeps the original translucent alpha");
                SimpleAssert.eq(icon.getMinU(), (float) boundary[3], "cached commands use the actual atlas UV extent");
                SimpleAssert.eq(1f, (float) boundary[6], "second material layer keeps its green tint");
                draw.invoke(submission, tess);
                SimpleAssert.eq(16400, tess.vertices.size(), "unsupported replay does not duplicate or omit commands");
                java.lang.reflect.Method dynamic = MiaoVisonLiteRenderer.class.getDeclaredMethod(
                    "drawPreviewQuads",
                    Tessellator.class,
                    List.class,
                    double.class,
                    double.class,
                    double.class);
                dynamic.setAccessible(true);
                HologramRenderer.editor().selection = new HologramSelection();
                HologramRenderer.editor().selection.click(0, false);
                HologramRenderer.editor().moveOffset = new int[] { 1, 0, 0 };
                RecordingTessellator moving = new RecordingTessellator();
                dynamic.invoke(HologramRenderer.editor(), moving, faces.subList(0, 1), 2d, 3d, 4d);
                SimpleAssert.eq(8, moving.vertices.size(), "drag fallback keeps all layered quad vertices once");
                SimpleAssert.eq(6d, moving.vertices.get(0)[0], "drag applies the existing projected horizontal shift");
                SimpleAssert.eq(7d, moving.vertices.get(0)[1], "drag applies the existing projected vertical shift");
                SimpleAssert.eq(.38f, (float) moving.vertices.get(0)[7], "drag preserves its original alpha");
                double[] pickPoints = { 0, 0, 0, 10, 0, 0, 10, 10, 0, 0, 10, 0, 0, 0, 1,
                    10, 0, 1, 10, 10, 1, 0, 10, 1 };
                List<Object> pickFaces = new java.util.ArrayList<>();
                pickFaces.add(faceConstructor.newInstance(cell, pickPoints, 2, false, material.geometry.get(2)));
                java.lang.reflect.Method hover = MiaoVisonLiteRenderer.class
                    .getDeclaredMethod("previewHovered", List.class, int.class, int.class, boolean.class);
                java.lang.reflect.Method invalidate = MiaoVisonLiteRenderer.class
                    .getDeclaredMethod("invalidatePreviewDrawCache");
                hover.setAccessible(true);
                invalidate.setAccessible(true);
                boolean metrics = com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.isEnabled();
                long scans = MiaoVisonLiteRenderer.previewHoverScans;
                com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.setEnabled(true);
                try {
                    invalidate.invoke(HologramRenderer.editor());
                    SimpleAssert.eq(0, hover.invoke(HologramRenderer.editor(), pickFaces, 5, 5, true), "real polygon query picks its cell");
                    SimpleAssert
                        .eq(0, hover.invoke(HologramRenderer.editor(), pickFaces, 5, 5, true), "stationary hover retains the same hit");
                    SimpleAssert.eq(scans + 1, MiaoVisonLiteRenderer.previewHoverScans, "stationary hover scans faces once");
                    SimpleAssert
                        .eq(-1, hover.invoke(HologramRenderer.editor(), pickFaces, 15, 5, true), "mouse movement recomputes the hit");
                    SimpleAssert.eq(scans + 2, MiaoVisonLiteRenderer.previewHoverScans, "mouse movement triggers one scan");
                    double[] projected = pickPoints.clone();
                    for (int at = 0; at < projected.length; at += 3) projected[at] += 20;
                    List<Object> rotated = new java.util.ArrayList<>();
                    rotated.add(faceConstructor.newInstance(cell, projected, 2, false, material.geometry.get(2)));
                    SimpleAssert.eq(
                        -1,
                        hover.invoke(HologramRenderer.editor(), rotated, 5, 5, true),
                        "reprojected geometry cannot reuse an old hit");
                    SimpleAssert.eq(scans + 3, MiaoVisonLiteRenderer.previewHoverScans, "new projection triggers one scan");
                    SimpleAssert.eq(
                        -1,
                        hover.invoke(HologramRenderer.editor(), rotated, 25, 5, false),
                        "viewport/preparation gate never reuses hover");
                    SimpleAssert.eq(0, hover.invoke(HologramRenderer.editor(), rotated, 25, 5, true), "reopening picking recomputes hover");
                    SimpleAssert.eq(scans + 4, MiaoVisonLiteRenderer.previewHoverScans, "gated query does not scan faces");
                    invalidate.invoke(HologramRenderer.editor());
                    rotated.clear();
                    SimpleAssert
                        .eq(-1, hover.invoke(HologramRenderer.editor(), rotated, 25, 5, true), "in-place scene clear cannot retain a hit");
                } finally {
                    invalidate.invoke(HologramRenderer.editor());
                    MiaoVisonLiteRenderer.previewHoverScans = scans;
                    com.miaokatze.gtmb.hologram.HologramRuntimeMetrics.setEnabled(metrics);
                }
            } finally {
                if (previousStone == null) materials.remove("minecraft:stone");
                else materials.put("minecraft:stone", previousStone);
                listener.setBoolean(null, installed);
                assets.blocks.snapshot = previousNative;
                HologramRenderer.editor().selection = previousSelection;
                HologramRenderer.editor().moveOffset = previousOffset;
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("ordered preview submission", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void tutorialSnapshotOwnership() throws ReflectiveOperationException {
        NBTTagCompound data = boundarySource("tutorial-owner");
        NBTTagList rows = new NBTTagList();
        rows.appendTag(boundaryCell(0, 0, 0, "minecraft:stone"));
        data.setTag("cells", rows);
        HologramState world = new HologramState(data), teaching = new HologramState(data);
        HologramUiModules ui = new HologramUiModules(null), sandbox = new HologramUiModules(null);
        ui.session.initializeIsolated(world, (op, request) -> {});
        sandbox.session.initializeIsolated(teaching, (op, request) -> {});
        java.lang.reflect.Field tutorialSandbox = field(HologramEditorTutorial.class, "sandbox");
        String[] names = { "previewPreparation", "previewState", "visibleState", "cachedFaces", "cachedOutlines",
            "cachedMarkers", "projectedFrame", "previewKey", "boundsState", "previewSubmitted", "presentedState",
            "presentedFrame", "presentedRequest", "previewSubmission", "hoverFaces" };
        Object[] saved = new Object[names.length];
        for (int i = 0; i < names.length; i++) saved[i] = field(MiaoVisonLiteRenderer.class, names[i]).get(HologramRenderer.editor());
        java.util.Map<Object, Object> regions = (java.util.Map<Object, Object>) field(
            MiaoVisonLiteRenderer.class,
            "previewRegions").get(HologramRenderer.editor()), savedRegions = new java.util.LinkedHashMap<>(regions);
        String[] listNames = { "nativePreviewCells", "linkedPreviewCells", "visibleCells" };
        List<?>[] savedLists = new List<?>[listNames.length];
        for (int i = 0; i < listNames.length; i++) savedLists[i] = new java.util.ArrayList<>(
            (List<?>) field(MiaoVisonLiteRenderer.class, listNames[i]).get(HologramRenderer.editor()));
        java.util.BitSet published = (java.util.BitSet) field(MiaoVisonLiteRenderer.class, "publishedPreviewCells")
            .get(HologramRenderer.editor()), savedPublished = (java.util.BitSet) published.clone();
        boolean worldPreview = HologramClient.worldPreview;
        try {
            tutorialSandbox.set(ui.tutorial, sandbox);
            SimpleAssert.that(HologramClient.ownsNativeRenderSnapshot(ui), "an active lesson owns its native snapshot");
            HologramPreviewAssets worldAssets = new HologramPreviewAssets();
            for (int frame = 0; frame < 6; frame++) {
                tutorialPreparationFrame(world, teaching, ui, worldAssets, false);
            }
            SimpleAssert.eq(1, HologramRenderer.previewMeshCompleted(teaching), "alternating world renders finish the teaching mesh");
            SimpleAssert.that(
                !((List<?>) field(MiaoVisonLiteRenderer.class, "cachedFaces").get(HologramRenderer.editor())).isEmpty(),
                "the isolated viewport publishes actual stone faces");
            SimpleAssert.eq(
                1,
                ((List<?>) field(MiaoVisonLiteRenderer.class, "visibleCells").get(HologramRenderer.editor())).size(),
                "the isolated viewport publishes the visible stone cell");
            tutorialSandbox.set(ui.tutorial, null);
            SimpleAssert
                .that(!HologramClient.ownsNativeRenderSnapshot(ui), "closing teaching restores world admission");
            SimpleAssert.that(
                worldAssets.blocks.snapshot == world && HologramPreviewAssets.EDITOR.blocks.snapshot == teaching,
                "world and teaching retain their own exact native snapshot");
            SimpleAssert.that(
                ui.session.state() == world && ui.session.sequence() == 0
                    && HologramClient.worldPreview == worldPreview,
                "teaching ownership does not change the real document, command sequence or world-preview preference");
            field(HologramUiLibrary.class, "browsePreview").setBoolean(ui.library, true);
            ui.library.libraryDrawer(true);
            SimpleAssert
                .that(HologramClient.ownsNativeRenderSnapshot(ui), "read-only library ownership remains intact");
        } finally {
            tutorialSandbox.set(ui.tutorial, null);
            for (int i = 0; i < names.length; i++) field(MiaoVisonLiteRenderer.class, names[i]).set(HologramRenderer.editor(), saved[i]);
            regions.clear();
            regions.putAll(savedRegions);
            for (int i = 0; i < listNames.length; i++) {
                List<Object> list = (List<Object>) field(MiaoVisonLiteRenderer.class, listNames[i]).get(HologramRenderer.editor());
                list.clear();
                list.addAll(savedLists[i]);
            }
            published.clear();
            published.or(savedPublished);
        }
    }

    /** Runs the real native snapshot switch and cooperative mesh cursors, without claiming a GL draw. */
    private static void tutorialPreparationFrame(HologramState world, HologramState teaching, HologramUiModules ui,
        HologramPreviewAssets worldAssets, boolean fence) throws ReflectiveOperationException {
        if (!fence || !HologramClient.ownsNativeRenderSnapshot(ui))
            try (HologramPreviewAssets.Scope ignored = worldAssets.enter()) {
                HologramBlockRender.beginViewport(world);
            }
        HologramBlockRender.beginViewport(teaching);
        Class<?> type = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewPreparation");
        if (field(MiaoVisonLiteRenderer.class, "visibleState").get(HologramRenderer.editor()) != teaching) {
            field(MiaoVisonLiteRenderer.class, "visibleState").set(HologramRenderer.editor(), teaching);
            ((java.util.Map<?, ?>) field(MiaoVisonLiteRenderer.class, "previewRegions").get(HologramRenderer.editor())).clear();
            java.lang.reflect.Constructor<?> constructor = type
                .getDeclaredConstructor(MiaoVisonLiteRenderer.class, HologramState.class, boolean.class);
            constructor.setAccessible(true);
            field(MiaoVisonLiteRenderer.class, "previewPreparation").set(HologramRenderer.editor(), constructor.newInstance(HologramRenderer.editor(), teaching, true));
        }
        java.lang.reflect.Method prepare = MiaoVisonLiteRenderer.class.getDeclaredMethod(
            "prepareProgressiveFrame",
            HologramState.class,
            String.class,
            int.class,
            int.class,
            int.class,
            int.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class);
        prepare.setAccessible(true);
        prepare.invoke(HologramRenderer.editor(), teaching, "tutorial-owner-pose", 0, 0, 380, 321, .4, .4, 1d, 0d, 0d, 0d);
        Object preparation = field(MiaoVisonLiteRenderer.class, "previewPreparation").get(HologramRenderer.editor());
        java.lang.reflect.Method advance = type
            .getDeclaredMethod("advance", int.class, int.class, boolean.class, String.class, long.class);
        advance.setAccessible(true);
        advance.invoke(preparation, Integer.MAX_VALUE, 0, false, "tutorial-owner-pose", 2_000_000L);
        // Headless fixture supplies only the boundary-drawn signal; cell preparation/projection/publication is real.
        ((MiaoVisonLiteRenderer.PreviewBoundary) field(type, "boundary").get(preparation)).presented = true;
    }

    @SuppressWarnings("unchecked")
    private static void completeCameraProjection(HologramTextures.Material material,
        java.util.Map<String, java.util.Map<Integer, HologramTextures.Material>> materials)
        throws ReflectiveOperationException {
        // The same prepared region inputs drive both a bounded cursor and the camera-only complete cursor. No GL draw
        // or copied projection algorithm is involved; the native candidate checks the actual screen submission.
        NBTTagCompound source = boundarySource("prepared-camera");
        NBTTagList rows = new NBTTagList();
        // Interleaved input indices deliberately differ from region/cell generation order.
        for (int[] point : new int[][] { { 16, 0 }, { 14, 1 }, { 16, 1 }, { 15, 0 }, { 16, 2 }, { 14, 0 }, { 14, 2 },
            { 15, 2 }, { -16, 0 }, { -17, -1 }, { -16, -1 }, { -17, 0 } })
            rows.appendTag(boundaryCell(point[0], 0, point[1], "minecraft:glass"));
        // The plain material cache distinguishes this fixture's partial surface by its exact metadata.
        rows.getCompoundTagAt(7)
            .setInteger("meta", 1);
        rows.getCompoundTagAt(7)
            .setInteger("wantMeta", 1);
        source.setTag("cells", rows);
        HologramState state = new HologramState(source);
        boolean[] missingIcon = { false };
        TextureAtlasSprite diagnosticIcon = new TextureAtlasSprite("fixture:diagnostic") {

            @Override
            public String getIconName() {
                return missingIcon[0] ? "missingno" : "fixture:diagnostic";
            }
        };
        List<HologramTextures.Sample>[] diagnosticSides = new List[6];
        for (int side = 0; side < 6; side++)
            diagnosticSides[side] = Collections.singletonList(new HologramTextures.Sample(diagnosticIcon, 0xffffff));
        HologramTextures.Material diagnosticMaterial = new HologramTextures.Material(
            diagnosticSides,
            diagnosticSides,
            material.geometry,
            false,
            false,
            false,
            false);
        HologramTextures.Material partialMaterial = new HologramTextures.Material(
            diagnosticSides,
            diagnosticSides,
            HologramGeometry.pipe(.5f, (1 << 2) | (1 << 5), 1),
            false,
            false,
            true,
            true);
        String[] names = { "previewPreparation", "previewState", "cachedFaces", "cachedOutlines", "cachedMarkers",
            "projectedFrame", "previewSubmission", "hoverFaces", "previewCameraReprojects", "previewCameraOrderReuses",
            "previewCameraOrderSorts", "previewBrowseSurfaceReuses" };
        Object[] saved = new Object[names.length];
        for (int i = 0; i < names.length; i++) saved[i] = field(MiaoVisonLiteRenderer.class, names[i]).get(HologramRenderer.editor());
        java.util.Map<Object, Object> regions = (java.util.Map<Object, Object>) field(
            MiaoVisonLiteRenderer.class,
            "previewRegions").get(HologramRenderer.editor());
        java.util.Map<Object, Object> savedRegions = new java.util.LinkedHashMap<>(regions);
        String[] listNames = { "nativePreviewCells", "linkedPreviewCells", "visibleCells" };
        List<?>[] lists = new List<?>[listNames.length];
        for (int i = 0; i < lists.length; i++)
            lists[i] = new java.util.ArrayList<>((List<?>) field(MiaoVisonLiteRenderer.class, listNames[i]).get(HologramRenderer.editor()));
        java.util.BitSet published = (java.util.BitSet) field(MiaoVisonLiteRenderer.class, "publishedPreviewCells")
            .get(HologramRenderer.editor()), savedPublished = (java.util.BitSet) published.clone();
        java.util.Map<Integer, HologramTextures.Material> fixtureGlass = new java.util.HashMap<>();
        fixtureGlass.put(0, diagnosticMaterial);
        fixtureGlass.put(1, partialMaterial);
        java.util.Map<Integer, HologramTextures.Material> previousGlass = materials
            .put("minecraft:glass", fixtureGlass);
        try {
            java.lang.reflect.Method prepareRegion = MiaoVisonLiteRenderer.class.getDeclaredMethod(
                "previewRegion",
                HologramState.class,
                List.class,
                int.class,
                int.class,
                boolean.class);
            prepareRegion.setAccessible(true);
            Class<?> preparationType = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewPreparation"),
                projectionType = Class.forName(MiaoVisonLiteRenderer.class.getName() + "$PreviewProjection");
            java.lang.reflect.Constructor<?> preparationConstructor = preparationType
                .getDeclaredConstructor(MiaoVisonLiteRenderer.class, HologramState.class, boolean.class);
            java.lang.reflect.Constructor<?> reusedProjectionConstructor = projectionType
                .getDeclaredConstructor(MiaoVisonLiteRenderer.class, HologramState.class, String.class, projectionType);
            preparationConstructor.setAccessible(true);
            reusedProjectionConstructor.setAccessible(true);
            java.lang.reflect.Method advance = projectionType
                .getDeclaredMethod("advance", boolean.class, int.class, long.class),
                publish = projectionType.getDeclaredMethod("publish"),
                reproject = projectionType.getDeclaredMethod("reprojectComplete", String.class, boolean.class);
            advance.setAccessible(true);
            publish.setAccessible(true);
            reproject.setAccessible(true);
            regions.clear();
            for (java.util.Map.Entry<HologramRegionCache.Key, List<HologramState.Cell>> entry : state.regions.regions
                .entrySet())
                regions.put(
                    entry.getKey(),
                    prepareRegion.invoke(HologramRenderer.editor(), state, entry.getValue(), Integer.MAX_VALUE, 0, false));
            Object preparation = preparationConstructor.newInstance(HologramRenderer.editor(), state, false);
            field(preparationType, "completed").setInt(preparation, state.cells.size());
            field(MiaoVisonLiteRenderer.class, "previewPreparation").set(HologramRenderer.editor(), preparation);
            Object previousProjection = null;
            for (double yaw : new double[] { 0, .4, -.8, 2.1 }) {
                List<double[]> reference = null;
                List<double[]> reprojected = new java.util.ArrayList<>();
                java.util.Set<Object> ownedFaces = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (int path = 0; path < 5; path++) {
                    // Revisit the reference pose after a genuinely different pose mutated every owned output.
                    double currentYaw = path == 1 || yaw != 0 && path >= 3 ? yaw + .13 : yaw;
                    boolean resize = yaw == 0 && path >= 3;
                    missingIcon[0] = path == 1 || path >= 3;
                    String key = yaw + ":" + path;
                    if (path == 3 && yaw == 0) {
                        field(MiaoVisonLiteRenderer.class, "projectedFrame")
                            .set(HologramRenderer.editor(), new HologramEditorCameraFrame(0, 0, 380, 321, .13, .4, 1, 15, 0, 1, 6));
                        SimpleAssert.eq(
                            false,
                            reproject.invoke(previousProjection, "across-facing-mask", false),
                            "a changed facing mask needs the newly exposed faces from a fresh projection");
                    }
                    field(MiaoVisonLiteRenderer.class, "projectedFrame").set(
                        HologramRenderer.editor(),
                        new HologramEditorCameraFrame(
                            0,
                            0,
                            resize ? 412 : 380,
                            resize ? 365 : 321,
                            currentYaw,
                            .4,
                            resize ? 1.2 : 1,
                            15,
                            0,
                            1,
                            6));
                    if (path == 3) {
                        List<?> faces = (List<?>) field(MiaoVisonLiteRenderer.class, "cachedFaces").get(HologramRenderer.editor());
                        long reuseBefore = field(MiaoVisonLiteRenderer.class, "previewCameraOrderReuses").getLong(HologramRenderer.editor());
                        SimpleAssert.eq(
                            true,
                            reproject.invoke(previousProjection, key, false),
                            "a settled projection reuses the presented face collection");
                        SimpleAssert.that(
                            faces == field(MiaoVisonLiteRenderer.class, "cachedFaces").get(HologramRenderer.editor()),
                            "camera navigation retains the complete sorted storage");
                        if (yaw == 0) SimpleAssert.eq(
                            reuseBefore + 1,
                            field(MiaoVisonLiteRenderer.class, "previewCameraOrderReuses").getLong(HologramRenderer.editor()),
                            "resize and zoom preserve exact equal-depth ties and skip sorting");
                        for (Object face : faces) reprojected.add(faceSnapshot(face));
                        for (Object outline : (List<?>) field(MiaoVisonLiteRenderer.class, "cachedOutlines").get(HologramRenderer.editor()))
                            SimpleAssert.eq(
                                false,
                                field(outline.getClass(), "fadeDefault").get(outline),
                                "reprojected outline still observes live material diagnostics");
                        continue;
                    }
                    for (String name : new String[] { "cachedFaces", "cachedOutlines", "cachedMarkers" })
                        field(MiaoVisonLiteRenderer.class, name).set(HologramRenderer.editor(), new java.util.ArrayList<>());
                    for (String name : listNames)
                        ((List<?>) field(MiaoVisonLiteRenderer.class, name).get(HologramRenderer.editor())).clear();
                    published.clear();
                    // The full read-only source can reuse local surfaces while its original cursor keeps the budget.
                    Object projection = reusedProjectionConstructor.newInstance(HologramRenderer.editor(), state, key,
                        previousProjection);
                    field(projectionType, "cacheBrowseSurfaces").setBoolean(projection, path == 1 || path == 4);
                    if (previousProjection != null) SimpleAssert.that(
                        field(projectionType, "faces").get(projection)
                            == field(projectionType, "faces").get(previousProjection),
                        "retired cursor output capacity is reused without a new aggregate backing array");
                    field(preparationType, "projection").set(preparation, projection);
                    if (path == 0 || path == 4) {
                        long before = field(MiaoVisonLiteRenderer.class, "previewBrowseSurfaceReuses").getLong(HologramRenderer.editor());
                        int steps = 0;
                        while (field(projectionType, "published").getInt(projection) < state.cells.size()
                            && steps++ < 100) {
                            advance.invoke(projection, false, 1, Long.MAX_VALUE);
                            publish.invoke(projection);
                            if (path == 4 && steps == 1) SimpleAssert.that(
                                field(projectionType, "published").getInt(projection) < state.cells.size(),
                                "read-only camera navigation still respects a bounded advance");
                        }
                        if (path == 4 && yaw != 0) SimpleAssert.that(
                            field(MiaoVisonLiteRenderer.class, "previewBrowseSurfaceReuses").getLong(HologramRenderer.editor()) > before,
                            "completed read-only source reuses the prior camera-facing local surfaces");
                    } else {
                        advance.invoke(projection, false, Integer.MAX_VALUE, Long.MAX_VALUE);
                        publish.invoke(projection);
                    }
                    SimpleAssert.eq(
                        state.cells.size(),
                        field(projectionType, "published").getInt(projection),
                        "the prepared camera path publishes every source cell in its single advance/publish");
                    List<?> faces = (List<?>) field(MiaoVisonLiteRenderer.class, "cachedFaces").get(HologramRenderer.editor());
                    SimpleAssert.that(!faces.isEmpty(), "concave transparent multi-region fixture has real faces");
                    if (path == 0) {
                        reference = new java.util.ArrayList<>();
                        for (Object face : faces) reference.add(faceSnapshot(face));
                        ownedFaces.addAll(faces);
                        if (yaw == 0) {
                            boolean groupedTie = false;
                            for (int at = 1; at < reference.size(); at++)
                                if (reference.get(at - 1)[2] == reference.get(at)[2]
                                    && reference.get(at - 1)[0] > reference.get(at)[0]) groupedTie = true;
                            SimpleAssert.that(
                                groupedTie,
                                "equal-depth fixture distinguishes canonical region order from global cell-index sorting");
                        }
                    } else if (path != 4) {
                        SimpleAssert.that(
                            faces == field(projectionType, "faces").get(projection),
                            "complete publication transfers the sorted cursor output without a second copy");
                    }
                    if (path == 2) {
                        SimpleAssert.eq(reference.size(), faces.size(), "complete projection keeps the same coverage");
                        for (int at = 0; at < faces.size(); at++) {
                            Object actual = faces.get(at);
                            SimpleAssert.that(
                                java.util.Arrays.equals(reference.get(at), faceSnapshot(actual)),
                                "deep numeric oracle preserves cell/side/depth/order/vertices after another camera");
                            SimpleAssert.that(
                                ownedFaces.contains(actual),
                                "revisiting a prepared camera reuses its actual Face outputs");
                        }
                    }
                    if (path == 4) {
                        SimpleAssert
                            .eq(reprojected.size(), faces.size(), "reprojection keeps all camera-facing surfaces");
                        for (int at = 0; at < faces.size(); at++) SimpleAssert.that(
                            java.util.Arrays.equals(reprojected.get(at), faceSnapshot(faces.get(at))),
                            "fresh projection preserves identical mixed-region vertices, exact depth ties and ordering");
                    }
                    for (Object outline : (List<?>) field(MiaoVisonLiteRenderer.class, "cachedOutlines").get(HologramRenderer.editor()))
                        SimpleAssert.eq(
                            !missingIcon[0],
                            field(outline.getClass(), "fadeDefault").get(outline),
                            "mutable IIcon diagnostics are reevaluated even when owned Faces are reused");
                    previousProjection = projection;
                }
            }
            Object blueprintPreparation = preparationConstructor.newInstance(HologramRenderer.editor(), state, true);
            SimpleAssert.that(
                field(preparationType, "boundary").get(blueprintPreparation) != null,
                "ordinary Blueprint keeps its independent early occupancy outline");
            List<double[]> expected = new java.util.ArrayList<>();
            for (Object face : (List<?>) field(MiaoVisonLiteRenderer.class, "cachedFaces").get(HologramRenderer.editor()))
                expected.add(faceSnapshot(face));
            state.data.setBoolean("structureProjection", true);
            Object direct = preparationConstructor.newInstance(HologramRenderer.editor(), state, true);
            SimpleAssert.that(
                field(preparationType, "boundary").get(direct) == null,
                "typed structure geometry needs no second all-cell occupancy pass");
            for (String name : new String[] { "cachedFaces", "cachedOutlines", "cachedMarkers" })
                field(MiaoVisonLiteRenderer.class, name).set(HologramRenderer.editor(), new java.util.ArrayList<>());
            for (String name : listNames) ((List<?>) field(MiaoVisonLiteRenderer.class, name).get(HologramRenderer.editor())).clear();
            published.clear();
            Object directProjection = reusedProjectionConstructor.newInstance(HologramRenderer.editor(), state, "direct-structure", null);
            field(preparationType, "projection").set(direct, directProjection);
            field(MiaoVisonLiteRenderer.class, "previewPreparation").set(HologramRenderer.editor(), direct);
            java.lang.reflect.Method advancePreparation = preparationType
                .getDeclaredMethod("advance", int.class, int.class, boolean.class, String.class, long.class);
            advancePreparation.setAccessible(true);
            int steps = 0;
            while (field(projectionType, "published").getInt(directProjection) < state.cells.size() && steps++ < 1000)
                advancePreparation.invoke(direct, Integer.MAX_VALUE, 0, false, "direct-structure", 2_000_000L);
            SimpleAssert.eq(
                state.cells.size(),
                field(projectionType, "published").getInt(directProjection),
                "structure preparation reaches complete publication without awaiting an occupancy draw");
            List<?> directFaces = (List<?>) field(MiaoVisonLiteRenderer.class, "cachedFaces").get(HologramRenderer.editor());
            SimpleAssert.eq(expected.size(), directFaces.size(), "direct structure path keeps full geometry coverage");
            for (int i = 0; i < expected.size(); i++) SimpleAssert.that(
                java.util.Arrays.equals(expected.get(i), faceSnapshot(directFaces.get(i))),
                "direct structure mesh preserves the ordinary cell, surface, depth and vertex oracle");
        } finally {
            if (previousGlass == null) materials.remove("minecraft:glass");
            else materials.put("minecraft:glass", previousGlass);
            regions.clear();
            regions.putAll(savedRegions);
            for (int i = 0; i < names.length; i++) field(MiaoVisonLiteRenderer.class, names[i]).set(HologramRenderer.editor(), saved[i]);
            for (int i = 0; i < listNames.length; i++) {
                List<Object> list = (List<Object>) field(MiaoVisonLiteRenderer.class, listNames[i]).get(HologramRenderer.editor());
                list.clear();
                list.addAll(lists[i]);
            }
            published.clear();
            published.or(savedPublished);
        }
    }

    /** Values only: retaining Face references here would turn the camera round trip into a false self-comparison. */
    private static double[] faceSnapshot(Object face) throws ReflectiveOperationException {
        Class<?> type = face.getClass();
        double[] result = new double[27];
        result[0] = ((HologramState.Cell) field(type, "cell").get(face)).index;
        result[1] = field(type, "side").getInt(face);
        result[2] = field(type, "depth").getDouble(face);
        double[] points = (double[]) field(type, "points").get(face);
        System.arraycopy(points, 0, result, 3, points.length);
        return result;
    }

    private static void standardLineBatchBoundary() {
        Tessellator standard = new Tessellator();
        int limit = MiaoVisonLiteRenderer.previewLineBatchLimit(standard);
        SimpleAssert.eq(16368, limit, "only the exact eight-int vanilla layout uses the larger line batch");
        SimpleAssert.eq(
            4092,
            MiaoVisonLiteRenderer.previewLineBatchLimit(new RecordingTessellator()),
            "foreign tessellators retain their existing conservative cap");
        standard.startDrawing(org.lwjgl.opengl.GL11.GL_LINES);
        standard.setColorRGBA_F(.2f, .7f, .8f, .45f);
        for (int vertex = 0; vertex < limit; vertex++) standard.addVertex(vertex, 0, .15);
        try {
            int used = field(Tessellator.class, "rawBufferIndex").getInt(standard),
                capacity = ((int[]) field(Tessellator.class, "rawBuffer").get(standard)).length;
            SimpleAssert.eq(limit * 8, used, "real vanilla line vertices retain their eight-int layout");
            SimpleAssert.that(
                used + 32 <= capacity && capacity <= 0x20000,
                "a real full line batch fits addVertex headroom without crossing the shrinking-buffer threshold");
            SimpleAssert.that(limit % 24 == 0, "the line cap preserves complete boxes and edge pairs");
            Tessellator successor = new Tessellator();
            successor.setTextureUV(.3, .6);
            successor.setBrightness(0xf000f0);
            successor.setNormal(1, 0, 0);
            successor.setColorRGBA_F(1, 0, 0, .1f);
            successor.disableColor();
            successor.startDrawing(org.lwjgl.opengl.GL11.GL_LINES);
            for (String flag : new String[] { "hasTexture", "hasBrightness", "hasNormals", "hasColor",
                "isColorDisabled" })
                SimpleAssert.that(
                    !field(Tessellator.class, flag).getBoolean(successor),
                    "the following legacy pass startDrawing resets its own prior attribute flag: " + flag);
            successor.setColorRGBA_F(.45f, .85f, 1, .72f);
            successor.addVertex(1, 2, 3);
            int[] raw = (int[]) field(Tessellator.class, "rawBuffer").get(successor);
            SimpleAssert.eq(
                field(Tessellator.class, "color").getInt(successor),
                raw[5],
                "the following structure-line pass uses its explicit color, never a previous stream's Tess state");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("standard line batch boundary", failure);
        }
    }

    private static final class RecordingTessellator extends Tessellator {

        final java.util.List<double[]> vertices = new java.util.ArrayList<>();
        final java.util.List<Integer> batchSizes = new java.util.ArrayList<>();
        int batchStart;
        float red, green, blue, alpha;

        @Override
        public void startDrawing(int mode) {
            SimpleAssert.eq(7, mode, "preview material batches remain quads");
            batchStart = vertices.size();
        }

        @Override
        public int draw() {
            int count = vertices.size() - batchStart;
            SimpleAssert.that(count <= 4092 && count % 4 == 0, "draw boundaries never split a primitive");
            batchSizes.add(count);
            return count;
        }

        @Override
        public void setColorRGBA_F(float red, float green, float blue, float alpha) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }

        @Override
        public void addVertexWithUV(double x, double y, double z, double u, double v) {
            vertices.add(new double[] { x, y, z, u, v, red, green, alpha });
        }
    }

    @SuppressWarnings("unchecked")
    private static void completePreviewMaterial() {
        List<HologramTextures.Sample>[] sides = new List[6];
        for (int side = 0; side < 6; side++) sides[side] = Collections
            .singletonList(new HologramTextures.Sample(new TextureAtlasSprite("minecraft:stone") {}, 0xffffff));
        HologramTextures.Material material = new HologramTextures.Material(
            sides,
            sides,
            HologramGeometry.cube(),
            false,
            false,
            false,
            false);
        SimpleAssert.that(
            MiaoVisonLiteRenderer.completePreviewMaterial(material),
            "all cached real material samples permit density fading");
        SimpleAssert.that(!MiaoVisonLiteRenderer.completePreviewMaterial(null), "absent material keeps diagnostic lines");
        SimpleAssert.that(
            !MiaoVisonLiteRenderer.completePreviewMaterial(
                new HologramTextures.Material(sides, sides, HologramGeometry.cube(), false, true, false, false)),
            "declared texture fallback keeps diagnostic lines");
        sides[0] = Collections
            .singletonList(new HologramTextures.Sample(new TextureAtlasSprite("minecraft:missingno") {}, 0xffffff));
        SimpleAssert.that(
            !MiaoVisonLiteRenderer.completePreviewMaterial(material),
            "an actual missing atlas icon is not proof of a normal material");
        sides[0] = Collections.emptyList();
        SimpleAssert.that(
            !MiaoVisonLiteRenderer.completePreviewMaterial(material),
            "unavailable face samples keep diagnostic lines");
    }
}
