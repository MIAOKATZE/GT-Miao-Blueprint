package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** CPU preparation contracts. These are not real GL/texture/capture acceptance tests. */
public final class HologramNativePreparationRegression {

    private HologramNativePreparationRegression() {}

    public static void run() {
        try {
            scheduler();
            authorAccessContinuation();
            nativeReads();
            pendingAndFailure();
            authorBudgetRecovery();
            for (boolean budget : new boolean[] { false, true })
                for (boolean failedFirst : new boolean[] { false, true }) mixedFailure(budget, failedFirst);
            preparedWorldMaterials();
            inspectionSides();
            deferredSides();
            dataPrivilege();
            wakeupAndIdentity();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("native preparation fixture", failure);
        }
    }

    private static HologramNativePreparation.Budget budget(int callbacks) {
        return new HologramNativePreparation.Budget(1, callbacks, () -> 0L);
    }

    private static void scheduler() {
        Graph graph = new Graph();
        Node[] chain = nodes(80);
        for (int i = 0; i + 1 < chain.length; i++) chain[i].loads.add(chain[i + 1]);
        HologramNativePreparation<Node> lane = new HologramNativePreparation<>(graph);
        lane.request(chain[0], HologramNativePreparation.Stage.READY);
        HologramNativePreparation.Budget shared = budget(32);
        lane.advance(shared);
        lane.advance(shared);
        SimpleAssert.eq(32, graph.attempts, "constant clock still shares exactly one bounded callback allowance");
        SimpleAssert.eq(32, shared.used(), "progress reports actual work, not polls");
        SimpleAssert.that(lane.active(), "long dependency chain yields with explicit continuation");
        Node unrelated = new Node();
        SimpleAssert
            .that(!lane.request(unrelated, HologramNativePreparation.Stage.READY), "busy lane adds no root queue");
        drain(lane);
        SimpleAssert.eq(
            HologramNativePreparation.Outcome.READY,
            graph.outcome(chain[0], HologramNativePreparation.Stage.READY),
            "deep chain converges without recursion");
        SimpleAssert
            .that(lane.request(unrelated, HologramNativePreparation.Stage.READY), "busy admission retries later");
        drain(lane);

        Node left = new Node(), right = new Node();
        left.topology.add(right);
        right.topology.add(left);
        lane.request(left, HologramNativePreparation.Stage.READY);
        drain(lane);
        lane.request(right, HologramNativePreparation.Stage.READY);
        drain(lane);
        SimpleAssert.that(left.ready && right.ready, "reviewed DATA-only topology adjacency is a legal cycle");

        Node a = new Node(), b = new Node();
        a.loads.add(b);
        b.loads.add(a);
        lane.request(a, HologramNativePreparation.Stage.READY);
        drain(lane);
        SimpleAssert.that(
            a.failure.contains("cycle") && b.failure.contains("cycle"),
            "load cycle is explicit terminal failure");
        int attempts = graph.attempts;
        SimpleAssert
            .that(!lane.request(a, HologramNativePreparation.Stage.READY), "terminal cycle cannot perpetually retry");
        lane.advance(budget(32));
        SimpleAssert.eq(attempts, graph.attempts, "terminal cached cycle runs no more callbacks");

        Node self = new Node();
        self.loads.add(self);
        lane.request(self, HologramNativePreparation.Stage.READY);
        drain(lane);
        SimpleAssert.that(self.failure.contains("cycle"), "self cycle is diagnosed");

        Node[] excessive = nodes(514);
        for (int i = 0; i + 1 < excessive.length; i++) excessive[i].loads.add(excessive[i + 1]);
        lane.request(excessive[0], HologramNativePreparation.Stage.READY);
        drain(lane);
        SimpleAssert.that(excessive[0].failure.contains("depth budget"), "oversized closure terminates explicitly");
        SimpleAssert.eq(0, lane.depth(), "terminal closure releases every frame");

        Node changing = new Node();
        for (Node dependency : nodes(513)) changing.loads.add(dependency);
        lane.request(changing, HologramNativePreparation.Stage.READY);
        drain(lane);
        SimpleAssert.that(changing.failure.contains("attempt budget"), "ever-new blockers cannot reset attempt limit");

        Node waiting = new Node(), parent = new Node();
        waiting.unknown = true;
        parent.loads.add(waiting);
        lane.request(parent, HologramNativePreparation.Stage.READY);
        drain(lane);
        SimpleAssert.eq(0, lane.depth(), "appearance wait frees the native lane");
        SimpleAssert.that(parent.failure.isEmpty() && !parent.data, "unknown is neither failure nor completed tile");
        waiting.unknown = false;
        lane.request(parent, HologramNativePreparation.Stage.READY);
        drain(lane);
        SimpleAssert.that(parent.ready, "later appearance demand recovery can retry");

        Node abandoned = new Node();
        abandoned.loads.add(new Node());
        lane.request(abandoned, HologramNativePreparation.Stage.READY);
        lane.advance(budget(1));
        lane.close();
        attempts = graph.attempts;
        lane.advance(budget(32));
        SimpleAssert.eq(attempts, graph.attempts, "closed generation executes no stale continuation");
        SimpleAssert.eq(0, lane.depth(), "close drops pending frames");
    }

    @SuppressWarnings("unchecked")
    private static void authorAccessContinuation() throws ReflectiveOperationException {
        com.google.gson.JsonObject source = new com.google.gson.JsonObject();
        source.addProperty("schema", 2);
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        int[] positions = { 0, 16, 48, 64, 80, 96 };
        for (int x : positions) {
            com.google.gson.JsonObject row = new com.google.gson.JsonObject();
            row.addProperty("x", x);
            row.addProperty("y", 0);
            row.addProperty("z", 0);
            row.addProperty("block", "minecraft:stone");
            rows.add(row);
        }
        source.add("cells", rows);
        com.miaokatze.gtmb.blueprint.editor.AuthorDocument document;
        try {
            document = com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO
                .readExpanded(source, com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO.Limits.defaults());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        try (Fixture fixture = new Fixture();
            HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            java.lang.reflect.Method publish = HologramAuthorProjection.class
                .getDeclaredMethod("publish", NBTTagCompound.class, Map.class, boolean.class);
            publish.setAccessible(true);
            Map<Integer, HologramState.Cell> replacements = new java.util.LinkedHashMap<>();
            for (int i = 0; i < positions.length; i++) replacements.put(
                i,
                appearance(
                    projection.view().cells.get(i),
                    i == 0 ? 31 : 47,
                    i == 1 ? 0 : i == 2 ? 16 : i == 3 ? 80 : -1));
            publish.invoke(projection, projection.view().data, replacements, true);
            HologramState first = projection.view();
            HologramBlockAccess access = isolated(first);
            HologramBlockAccess actualAccess = isolated(first, true);
            SimpleAssert
                .eq(31, ((Probe) access.getTileEntity(48, 0, 0)).value, "transitive reader starts with original C");
            Probe stable = (Probe) access.getTileEntity(96, 0, 0);
            actualAccess.prepare(first.cells.get(5), budget(1));
            Probe stableActual = (Probe) ((Map<?, ?>) field(HologramBlockAccess.class, "tiles").get(actualAccess))
                .get("96:0:0");
            SimpleAssert.that(stableActual != null, "actual DATA tile exists before READY stage can run");
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.WAITING,
                actualAccess.outcome(first.cells.get(5)),
                "actual DATA-only preparation remains pending topology stage");
            SimpleAssert.that(stableActual != stable, "actual and target own independent detached tiles");
            Map<HologramRegionCache.Key, Set<HologramRegionCache.Key>> readers = (Map<HologramRegionCache.Key, Set<HologramRegionCache.Key>>) field(
                HologramAuthorProjection.class,
                "readers").get(projection);
            SimpleAssert.that(
                readers.get(HologramRegionCache.key(first.cells.get(0)))
                    .contains(HologramRegionCache.key(first.cells.get(1))),
                "tile callback records B reading C");
            SimpleAssert.that(
                readers.get(HologramRegionCache.key(first.cells.get(1)))
                    .contains(HologramRegionCache.key(first.cells.get(2))),
                "root records A reading B");
            access.prepare(first.cells.get(3), budget(1));
            HologramNativePreparation<HologramState.Cell> lane = (HologramNativePreparation<HologramState.Cell>) field(
                HologramBlockAccess.class,
                "preparation").get(access);
            int depth = lane.depth();
            SimpleAssert.that(depth > 0, "unrelated native lane is suspended");

            publish.invoke(
                projection,
                first.data,
                Collections.singletonMap(0, appearance(first.cells.get(0), 53, -1)),
                true);
            HologramState next = projection.view();
            Set<HologramRegionCache.Key> dirty = new java.util.HashSet<>();
            for (int index : new int[] { 0, 1, 2 }) dirty.add(HologramRegionCache.key(first.cells.get(index)));
            SimpleAssert
                .that(!access.advance(first, next, Collections.emptySet()), "replaced cell cannot escape invalidation");
            access.beginModel(first.cells.get(5));
            SimpleAssert.that(!access.advance(first, next, dirty), "active model scope cannot rebind midway");
            access.endModel();
            SimpleAssert.that(access.advance(first, next, dirty), "author appearance retains access owner");
            SimpleAssert.that(!access.advance(first, next, dirty), "old immutable owner cannot be rebound twice");
            SimpleAssert.that(
                actualAccess.advance(first, next, dirty) && actualAccess.owns(next, true),
                "actual owner independently follows same publication");
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.WAITING,
                actualAccess.outcome(next.cells.get(5)),
                "retaining DATA does not falsely promote it to READY");
            SimpleAssert.that(
                ((Map<?, ?>) field(HologramBlockAccess.class, "tiles").get(actualAccess)).get("96:0:0") == stableActual,
                "unrelated already-created DATA survives publication");
            SimpleAssert.that(
                actualAccess.getTileEntity(96, 0, 0) == stableActual,
                "actual side retains its own unrelated tile identity");
            actualAccess.close();
            SimpleAssert.that(
                access.owns(next, false) && !access.owns(first, false),
                "owner rebind rejects old state and wrong side");
            SimpleAssert.that(!access.owns(next, true), "target access never becomes actual access");
            SimpleAssert.eq(depth, lane.depth(), "unrelated suspended lane keeps exact frames");
            SimpleAssert.that(access.getTileEntity(96, 0, 0) == stable, "unrelated READY tile retains identity");
            SimpleAssert.that(
                stable.getWorldObj() == field(HologramBlockAccess.class, "renderWorld").get(access),
                "retained tile keeps detached world referring to rebound access");
            access.prepare(next.cells.get(3), budget(32));
            SimpleAssert
                .eq(53, ((Probe) access.getTileEntity(48, 0, 0)).value, "transitive dirty reader rebuilds with new C");

            // Recreate an active lane, then invalidate its dependency. Cancellation must not close the owner.
            java.lang.reflect.Method discard = HologramBlockAccess.class
                .getDeclaredMethod("discard", HologramState.Cell.class);
            discard.setAccessible(true);
            discard.invoke(access, next.cells.get(3));
            discard.invoke(access, next.cells.get(4));
            access.prepare(next.cells.get(3), budget(1));
            SimpleAssert.that(lane.active(), "second lane waits on child");
            publish.invoke(
                projection,
                next.data,
                Collections.singletonMap(4, appearance(next.cells.get(4), 67, -1)),
                true);
            HologramState childChanged = projection.view();
            dirty.clear();
            dirty.add(HologramRegionCache.key(next.cells.get(3)));
            dirty.add(HologramRegionCache.key(next.cells.get(4)));
            SimpleAssert.that(access.advance(next, childChanged, dirty), "dirty lane publication rebinds");
            SimpleAssert.eq(0, lane.depth(), "dirty child cancels every old lane frame");
            SimpleAssert.eq(67, ((Probe) access.getTileEntity(64, 0, 0)).value, "cancelled lane can admit fresh root");

            fixture.failureAt = 0;
            discard.invoke(access, childChanged.cells.get(0));
            access.prepare(childChanged.cells.get(0), budget(32));
            SimpleAssert.that(
                !access.failure(0, 0, 0)
                    .isEmpty(),
                "terminal failure is cached before publication");
            fixture.failureAt = Integer.MIN_VALUE;
            publish.invoke(
                projection,
                childChanged.data,
                Collections.singletonMap(0, appearance(childChanged.cells.get(0), 71, -1)),
                true);
            HologramState recovered = projection.view();
            dirty.clear();
            for (int index : new int[] { 0, 1, 2 }) dirty.add(HologramRegionCache.key(childChanged.cells.get(index)));
            SimpleAssert.that(access.advance(childChanged, recovered, dirty), "failure publication rebinds");
            SimpleAssert.eq("", access.failure(0, 0, 0), "dirty failure marker is retired");
            SimpleAssert.eq(71, ((Probe) access.getTileEntity(0, 0, 0)).value, "formerly failed tile is rebuilt");

            publish.invoke(projection, recovered.data, Collections.emptyMap(), true);
            HologramState empty = projection.view();
            SimpleAssert.that(
                access.advance(recovered, empty, Collections.emptySet()),
                "empty model-only delta retains access");
            SimpleAssert.that(access.getTileEntity(96, 0, 0) == stable, "empty delta does not flush READY cache");
            publish.invoke(projection, empty.data, Collections.emptyMap(), false);
            HologramState metadata = projection.view();
            SimpleAssert.that(
                !access.advance(empty, metadata, Collections.emptySet()),
                "non-model metadata keeps conservative boundary");
            SimpleAssert.that(access.owns(empty, false), "rejected rebind does not mutate owner");
            HologramState closedNext = new HologramState(
                projection,
                empty.data,
                empty,
                empty.cells.size(),
                Collections.emptyMap());
            closedNext.modelOnly = true;
            access.close();
            SimpleAssert.that(
                !access.advance(empty, closedNext, Collections.emptySet()),
                "closed owner rejects otherwise-valid same-owner model-only successor");
            authorPublicationBridge(projection, publish, fixture);
        }
    }

    @SuppressWarnings("unchecked")
    private static void authorPublicationBridge(HologramAuthorProjection projection, java.lang.reflect.Method publish,
        Fixture fixture) throws ReflectiveOperationException {
        Map<Field, Object> saved = new java.util.LinkedHashMap<>();
        for (String name : new String[] { "snapshot", "target", "actual", "preparationBudget" }) {
            Field value = field(HologramBlockRender.class, name);
            saved.put(value, value.get(null));
        }
        for (String name : new String[] { "visibleState", "previewState", "previewPreparation", "worldState",
            "boundsState", "structureBoundsState", "lastSnapshotDirtyRegions" }) {
            Field value = field(HologramRenderer.class, name);
            saved.put(value, value.get(null));
            if (!value.getType()
                .isPrimitive()) value.set(null, null);
        }
        Map<Object, Object> models = (Map<Object, Object>) field(HologramBlockRender.class, "MODELS").get(null);
        Map<Object, Object> savedModels = new IdentityHashMap<>(models);
        Map<HologramState.Cell, Integer> deferred = (Map<HologramState.Cell, Integer>) field(
            HologramBlockRender.class,
            "DEFERRED").get(null);
        Map<HologramState.Cell, Integer> savedDeferred = new IdentityHashMap<>(deferred);
        Map<Object, Object> regions = (Map<Object, Object>) field(HologramRenderer.class, "previewRegions").get(null);
        Map<Object, Object> savedRegions = new java.util.LinkedHashMap<>(regions);
        Map<Object, Object> failures = (Map<Object, Object>) field(HologramBlockRender.class, "FAILURES").get(null);
        Map<Object, Object> savedFailures = new java.util.HashMap<>(failures);
        Set<String> warnings = (Set<String>) field(HologramBlockRender.class, "WARNED_FAILURES").get(null);
        Set<String> savedWarnings = new java.util.HashSet<>(warnings);
        try {
            models.clear();
            deferred.clear();
            regions.clear();
            HologramState before = projection.view();
            HologramBlockAccess target = isolated(before), actual = isolated(before, true);
            field(HologramBlockRender.class, "snapshot").set(null, before);
            field(HologramBlockRender.class, "target").set(null, target);
            field(HologramBlockRender.class, "actual").set(null, actual);
            field(HologramRenderer.class, "visibleState").set(null, before);
            Probe original = (Probe) target.getTileEntity(48, 0, 0);
            Probe retained = (Probe) target.getTileEntity(96, 0, 0);
            publish.invoke(
                projection,
                before.data,
                Collections.singletonMap(0, appearance(before.cells.get(0), 83, -1)),
                true);
            HologramState after = projection.view();
            SimpleAssert.that(
                field(HologramBlockRender.class, "target").get(null) == target
                    && field(HologramBlockRender.class, "actual").get(null) == actual,
                "real active publication bridge retains both access objects");
            SimpleAssert
                .that(target.owns(after, false) && actual.owns(after, true), "bridge advances exact side owners");
            Probe rebuilt = (Probe) target.getTileEntity(48, 0, 0);
            SimpleAssert.that(
                rebuilt != original && rebuilt.value == 83,
                "production dependency closure retires distant A through B to changed C");
            SimpleAssert.that(target.getTileEntity(96, 0, 0) == retained, "bridge preserves unrelated READY tile");

            // Renderer-discovered work has a root outside its lane frames. Root-only dirtiness must cancel it.
            java.lang.reflect.Method discard = HologramBlockAccess.class
                .getDeclaredMethod("discard", HologramState.Cell.class);
            discard.setAccessible(true);
            discard.invoke(target, after.cells.get(3));
            discard.invoke(target, after.cells.get(4));
            target.beginModel(after.cells.get(5), true);
            target.getTileEntity(64, 0, 0);
            target.endModel();
            HologramNativePreparation<HologramState.Cell> lane = (HologramNativePreparation<HologramState.Cell>) field(
                HologramBlockAccess.class,
                "preparation").get(target);
            SimpleAssert.that(lane.active(), "renderer-discovered child owns active lane");
            publish.invoke(
                projection,
                after.data,
                Collections.singletonMap(5, appearance(after.cells.get(5), 89, -1)),
                true);
            HologramState rootChanged = projection.view();
            SimpleAssert.eq(0, lane.depth(), "dirty model root cancels lane even when all frames are outside dirty");
            SimpleAssert.that(target.owns(rootChanged, false), "root-only cancellation keeps native owner open");

            // Drive exact retry transition through terminal preparation without allocating a GL list.
            deferred.clear();
            fixture.failureAt = 0;
            discard.invoke(target, rootChanged.cells.get(0));
            HologramBlockRender.defer(rootChanged.cells.get(0), false);
            field(HologramBlockRender.class, "preparationBudget").set(null, budget(32));
            Set<HologramState.Cell> resolved = HologramBlockRender.retryDeferredCells(rootChanged);
            SimpleAssert.that(
                resolved.size() == 1 && resolved.contains(rootChanged.cells.get(0)),
                "retry bridge returns only exact resolved consumer, including terminal failure");
            SimpleAssert.that(
                !HologramBlockRender.deferred(rootChanged.cells.get(0), false),
                "terminal preparation clears only its stored wait");
            fixture.failureAt = Integer.MIN_VALUE;

            publish.invoke(projection, rootChanged.data, Collections.emptyMap(), false);
            HologramState metadata = projection.view();
            SimpleAssert.that(
                field(HologramBlockRender.class, "target").get(null) != target
                    && field(HologramBlockRender.class, "actual").get(null) != actual,
                "non-model metadata bridge takes conservative full-access fallback");
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.FAILED,
                target.outcome(rootChanged.cells.get(5)),
                "fallback closes old access instead of leaving live stale owner");
            SimpleAssert.that(
                ((HologramBlockAccess) field(HologramBlockRender.class, "target").get(null)).owns(metadata, false),
                "fallback installs correct new owner");
        } finally {
            fixture.failureAt = Integer.MIN_VALUE;
            for (String name : new String[] { "target", "actual" }) {
                Field value = field(HologramBlockRender.class, name);
                HologramBlockAccess access = (HologramBlockAccess) value.get(null);
                if (access != null && access != saved.get(value)) access.close();
            }
            models.clear();
            models.putAll(savedModels);
            deferred.clear();
            deferred.putAll(savedDeferred);
            regions.clear();
            regions.putAll(savedRegions);
            failures.clear();
            failures.putAll(savedFailures);
            warnings.clear();
            warnings.addAll(savedWarnings);
            for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey()
                .set(null, entry.getValue());
        }
    }

    private static HologramState.Cell appearance(HologramState.Cell cell, int value, int dependency) {
        NBTTagCompound tag = cell.tag(), appearance = row(cell.x, value, dependency, false);
        tag.setTag("targetDisplay", appearance.getTag("targetDisplay"));
        tag.setTag("actualDisplay", appearance.getTag("actualDisplay"));
        tag.setInteger("nativeModelPending", 0);
        return HologramState.Cell.takeOwnedLocalRow(cell.index, tag);
    }

    private static Node[] nodes(int count) {
        Node[] result = new Node[count];
        for (int i = 0; i < count; i++) result[i] = new Node();
        return result;
    }

    private static void drain(HologramNativePreparation<?> lane) {
        int slices = 0;
        while (lane.active() && slices++ < 4096) lane.advance(budget(32));
        SimpleAssert.that(!lane.active(), "bounded fixture reaches a definitive outcome");
    }

    private static final class Node {

        boolean data, ready, unknown;
        String failure = "";
        final List<Node> loads = new ArrayList<>(), topology = new ArrayList<>();
    }

    private static final class Graph implements HologramNativePreparation.Backend<Node> {

        int attempts;

        public HologramNativePreparation.Outcome outcome(Node node, HologramNativePreparation.Stage stage) {
            if (!node.failure.isEmpty()) return HologramNativePreparation.Outcome.FAILED;
            return (stage == HologramNativePreparation.Stage.DATA ? node.data : node.ready)
                ? HologramNativePreparation.Outcome.READY
                : HologramNativePreparation.Outcome.WAITING;
        }

        public HologramNativePreparation.Attempt<Node> attempt(Node node, HologramNativePreparation.Stage stage) {
            attempts++;
            if (node.unknown) return new HologramNativePreparation.Attempt<>(node, stage, true);
            List<Node> inputs = stage == HologramNativePreparation.Stage.DATA ? node.loads : node.topology;
            HologramNativePreparation.Stage needed = stage == HologramNativePreparation.Stage.DATA
                ? HologramNativePreparation.Stage.READY
                : HologramNativePreparation.Stage.DATA;
            for (Node input : inputs) if (outcome(input, needed) != HologramNativePreparation.Outcome.READY)
                return new HologramNativePreparation.Attempt<>(input, needed, false);
            if (stage == HologramNativePreparation.Stage.DATA) node.data = true;
            else node.ready = true;
            return HologramNativePreparation.Attempt.complete();
        }

        public void fail(Node node, String reason) {
            discard(node);
            node.failure = reason;
        }

        public void discard(Node node) {
            node.data = node.ready = false;
            node.failure = "";
        }
    }

    private static void nativeReads() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramState state = state(row(0, 17, -1, false), row(16, 0, 0, false), row(48, 0, -1, false));
            HologramBlockAccess access = isolated(state);
            access.beginModel(state.cells.get(2));
            Probe b = (Probe) access.getTileEntity(16, 0, 0);
            SimpleAssert.that(access.endModel(), "same-access iterative load reaches complete input");
            SimpleAssert.that(b != null && b.value == 17, "unprimed ready C is prepared before accepting cached B");
            SimpleAssert.eq(1, fixture.maximumDepth, "native callbacks never recursively nest");
            SimpleAssert.eq(
                2,
                fixture.loads.get(16)
                    .intValue(),
                "B is rebuilt fresh after its speculative unknown read");
            SimpleAssert.that(access.getTileEntity(16, 0, 0) == b, "complete cache hit reuses exact B");

            NBTTagCompound[] chainRows = new NBTTagCompound[40];
            for (int i = 0; i < chainRows.length; i++) chainRows[i] = row(
                i * 16,
                i + 1 == chainRows.length ? 47 : 0,
                i + 1 == chainRows.length ? -1 : (i + 1) * 16,
                false);
            HologramState chain = state(chainRows);
            HologramBlockAccess chainAccess = isolated(chain);
            int slices = 0;
            while (chainAccess.outcome(chain.cells.get(0)) == HologramNativePreparation.Outcome.WAITING
                && slices++ < 64) chainAccess.prepare(chain.cells.get(0), budget(32));
            SimpleAssert.eq(
                47,
                ((Probe) chainAccess.getTileEntity(0, 0, 0)).value,
                "real acyclic public-loader chain converges across bounded slices");
            SimpleAssert.eq(1, fixture.maximumDepth, "real long chain never nests adapter callbacks");
            chainAccess.close();

            // A renderer can discover a distant dependency after its own tile was already prepared.
            HologramBlockAccess rendered = isolated(state);
            rendered.prepare(state.cells.get(2), budget(32));
            int before = fixture.total;
            rendered.beginModel(state.cells.get(2), true);
            SimpleAssert.eq(
                null,
                rendered.getTileEntity(16, 0, 0),
                "renderer observes provisional null only in rejected scope");
            SimpleAssert.that(!rendered.endModel(), "renderer result cannot accept a ready-payload cache miss");
            SimpleAssert.eq(before, fixture.total, "renderer callback cannot trigger nested native hydration");
            rendered.prepare(state.cells.get(2), budget(32));
            rendered.beginModel(state.cells.get(2), true);
            Probe recovered = (Probe) rendered.getTileEntity(16, 0, 0);
            SimpleAssert.that(
                recovered != null && recovered.value == 17 && rendered.endModel(),
                "later safe pump resolves renderer-discovered dependency without a source publication");
            rendered.close();
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.FAILED,
                rendered.outcome(state.cells.get(2)),
                "closed snapshot cannot appear ready");
        }
    }

    private static void pendingAndFailure() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramState pending = state(row(0, 17, -1, true), row(16, 0, 0, false));
            HologramBlockAccess access = isolated(pending);
            for (int attempt = 0; attempt < 2; attempt++) {
                access.beginModel(pending.cells.get(1));
                SimpleAssert
                    .eq(null, access.getTileEntity(16, 0, 0), "pending neighbor never becomes an accepted tile");
                SimpleAssert.that(!access.endModel(), "every pending retry remains incomplete");
                for (String name : new String[] { "tiles", "failures" }) SimpleAssert.that(
                    !((Map<?, ?>) field(HologramBlockAccess.class, name).get(access)).containsKey("16:0:0"),
                    "pending leaves no cached tile/failure");
                for (String name : new String[] { "loaded", "topologyPrepared" }) SimpleAssert.that(
                    !((Set<?>) field(HologramBlockAccess.class, name).get(access)).contains("16:0:0"),
                    "pending leaves no completed marker");
            }
            HologramState ready = state(row(0, 19, -1, false), row(16, 0, 0, false));
            HologramBlockAccess fresh = isolated(ready);
            SimpleAssert.eq(
                19,
                ((Probe) fresh.getTileEntity(16, 0, 0)).value,
                "new snapshot recovers without explicitly priming C");

            fixture.failureAt = 0;
            HologramBlockAccess failed = isolated(ready);
            failed.prepare(ready.cells.get(0), budget(32));
            failed.prepare(ready.cells.get(1), budget(32));
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.FAILED,
                failed.outcome(ready.cells.get(1)),
                "terminal C failure propagates through B instead of cached absence");
            failed.beginModel(ready.cells.get(1), true);
            failed.getTileEntity(0, 0, 0);
            SimpleAssert.that(
                !failed.endModel() && !failed.modelFailure()
                    .isEmpty(),
                "ignored terminal null still rejects model");
            int before = fixture.total;
            failed.prepare(ready.cells.get(1), budget(32));
            SimpleAssert.eq(before, fixture.total, "terminal cached failure never endlessly defers/reloads");
            fixture.failureAt = Integer.MIN_VALUE;

            HologramState cycle = state(row(0, 0, 16, false), row(16, 0, 0, false));
            HologramBlockAccess cyclic = isolated(cycle);
            cyclic.prepare(cycle.cells.get(0), budget(32));
            SimpleAssert.that(
                cyclic.failure(0, 0, 0)
                    .contains("cycle"),
                "real public adapter cycle is terminal");
            SimpleAssert.eq(
                0,
                ((java.util.Deque<?>) field(HologramBlockAccess.class, "tileReaders").get(cyclic)).size(),
                "cycle returns every tile-owner scope");

            fixture.nullFactory = true;
            HologramBlockAccess nullTile = isolated(ready);
            nullTile.prepare(ready.cells.get(0), budget(32));
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.FAILED,
                nullTile.outcome(ready.cells.get(0)),
                "required null factory is explicit failure, not ABSENT");
        }
    }

    @SuppressWarnings("unchecked")
    private static void authorBudgetRecovery() throws ReflectiveOperationException {
        com.google.gson.JsonObject source = new com.google.gson.JsonObject();
        source.addProperty("schema", 2);
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        for (int x : new int[] { 0, 16, 48 }) {
            com.google.gson.JsonObject row = new com.google.gson.JsonObject();
            row.addProperty("x", x);
            row.addProperty("y", 0);
            row.addProperty("z", 0);
            row.addProperty("block", "minecraft:stone");
            rows.add(row);
        }
        source.add("cells", rows);
        com.miaokatze.gtmb.blueprint.editor.AuthorDocument document;
        try {
            document = com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO
                .readExpanded(source, com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO.Limits.defaults());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        try (Fixture fixture = new Fixture();
            HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            Map<Integer, HologramState.Cell> replacements = new java.util.LinkedHashMap<>();
            for (int i = 0; i < 2; i++) {
                NBTTagCompound tag = projection.view().cells.get(i)
                    .tag();
                NBTTagCompound appearance = row(i * 16, i == 0 ? 31 : 0, i == 0 ? -1 : 0, false);
                tag.setTag("targetDisplay", appearance.getTag("targetDisplay"));
                tag.setTag("actualDisplay", appearance.getTag("actualDisplay"));
                tag.setInteger("nativeModelPending", 0);
                if (i == 0) tag.setString("renderKind", "localBudget");
                replacements.put(i, HologramState.Cell.takeOwnedLocalRow(i, tag));
            }
            java.lang.reflect.Method publish = HologramAuthorProjection.class
                .getDeclaredMethod("publish", NBTTagCompound.class, Map.class, boolean.class);
            publish.setAccessible(true);
            publish.invoke(projection, projection.view().data, replacements, true);
            HologramState state = projection.view();
            long id = state.cells.get(0).nativeModelId;
            Map<Long, Long> costs = (Map<Long, Long>) field(HologramAuthorProjection.class, "budgetDeferred")
                .get(projection);
            Set<Long> queued = (Set<Long>) field(HologramAuthorProjection.class, "queued").get(projection);
            costs.put(id, 1L);
            queued.clear();
            for (long i = 1; i <= 512; i++) queued.add(-i);
            HologramBlockAccess access = isolated(state);
            access.beginModel(state.cells.get(2));
            SimpleAssert.eq(null, access.getTileEntity(16, 0, 0), "localBudget is unknown, not absent");
            SimpleAssert.that(!access.endModel(), "localBudget rejects distant dependent model");
            SimpleAssert.eq(512, queued.size(), "native preparation does not expand saturated appearance queue");
            queued.clear();
            access.beginModel(state.cells.get(2));
            access.getTileEntity(16, 0, 0);
            SimpleAssert.that(!access.endModel() && queued.contains(id), "later retry renews known-cost budget demand");
            NBTTagCompound recovered = state.cells.get(0)
                .tag();
            recovered.setString("renderKind", "block");
            publish.invoke(
                projection,
                state.data,
                Collections.singletonMap(0, HologramState.Cell.takeOwnedLocalRow(0, recovered)),
                true);
            HologramBlockAccess fresh = isolated(projection.view());
            SimpleAssert.eq(
                31,
                ((Probe) fresh.getTileEntity(16, 0, 0)).value,
                "budget recovery on new snapshot prepares unprimed input correctly");
        }
    }

    @SuppressWarnings("unchecked")
    private static void mixedFailure(boolean budget, boolean failedFirst) throws ReflectiveOperationException {
        com.google.gson.JsonObject source = new com.google.gson.JsonObject();
        source.addProperty("schema", 2);
        com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
        for (int x : new int[] { 0, 16, 32 }) {
            com.google.gson.JsonObject row = new com.google.gson.JsonObject();
            row.addProperty("x", x);
            row.addProperty("y", 0);
            row.addProperty("z", 0);
            row.addProperty("block", "minecraft:stone");
            rows.add(row);
        }
        source.add("cells", rows);
        com.miaokatze.gtmb.blueprint.editor.AuthorDocument document;
        try {
            document = com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO
                .readExpanded(source, com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO.Limits.defaults());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        try (Fixture fixture = new Fixture();
            HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            Map<Integer, HologramState.Cell> replacements = new java.util.LinkedHashMap<>();
            for (int i = 0; i < 3; i++) {
                NBTTagCompound tag = projection.view().cells.get(i)
                    .tag();
                NBTTagCompound appearance = row(i * 16, 0, -1, false).getCompoundTag("targetDisplay");
                if (i == 2) {
                    appearance.getCompoundTag("nativeTile")
                        .setInteger("depends", failedFirst ? 0 : 16);
                    appearance.getCompoundTag("nativeTile")
                        .setInteger("depends2", failedFirst ? 16 : 0);
                }
                tag.setTag("targetDisplay", appearance);
                tag.setTag("actualDisplay", appearance.copy());
                tag.setInteger("nativeModelPending", i == 1 && !budget ? 3 : 0);
                if (i == 1 && budget) tag.setString("renderKind", "localBudget");
                replacements.put(i, HologramState.Cell.takeOwnedLocalRow(i, tag));
            }
            java.lang.reflect.Method publish = HologramAuthorProjection.class
                .getDeclaredMethod("publish", NBTTagCompound.class, Map.class, boolean.class);
            publish.setAccessible(true);
            publish.invoke(projection, projection.view().data, replacements, true);
            HologramState state = projection.view();
            long unknownId = state.cells.get(1).nativeModelId;
            if (budget) ((Map<Long, Long>) field(HologramAuthorProjection.class, "budgetDeferred").get(projection))
                .put(unknownId, 1L);
            Set<Long> queued = (Set<Long>) field(HologramAuthorProjection.class, "queued").get(projection);
            HologramBlockAccess access = isolated(state);
            fixture.failureAt = 0;
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.FAILED,
                access.prepare(state.cells.get(0), budget(32)),
                "prime authoritative terminal dependency failure");
            queued.clear();
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.FAILED,
                access.prepare(state.cells.get(2), budget(32)),
                "failed dependency dominates simultaneous unknown in either read order");
            SimpleAssert.that(
                access.failure(32, 0, 0)
                    .contains("synthetic terminal"),
                "dependency failure is preserved, not replaced by indefinite pending");
            if (failedFirst)
                SimpleAssert.that(queued.isEmpty(), "reads after authoritative failure renew no unknown demand");
            else SimpleAssert
                .that(queued.contains(unknownId), "unknown first may issue one legitimate demand before failure");
            int calls = fixture.total;
            queued.clear();
            for (int retry = 0; retry < 3; retry++) {
                SimpleAssert.eq(
                    HologramNativePreparation.Outcome.FAILED,
                    access.prepare(state.cells.get(2), budget(32)),
                    "terminal mixed-input result stays terminal");
                access.beginModel(state.cells.get(2), true);
                access.getTileEntity(32, 0, 0);
                SimpleAssert.that(
                    !access.endModel() && !access.modelFailure()
                        .isEmpty(),
                    "cached failed read rejects model");
            }
            SimpleAssert.eq(calls, fixture.total, "terminal result invokes no repeated native callback");
            SimpleAssert.that(queued.isEmpty(), "terminal result issues no repeated unknown demand");
            access.close();
        }
        // A callback exception caused by unknown data alone is not authoritative dependency failure.
        try (Fixture fixture = new Fixture()) {
            NBTTagCompound dependent = row(32, 0, 16, false);
            dependent.getCompoundTag("targetDisplay")
                .getCompoundTag("nativeTile")
                .setBoolean("throwAfterReads", true);
            HologramState state = state(row(16, 0, -1, true), dependent);
            HologramBlockAccess access = isolated(state);
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.WAITING,
                access.prepare(state.cells.get(1), budget(32)),
                "exception after unknown remains retryable without failedRead");
            SimpleAssert.that(
                access.failure(32, 0, 0)
                    .isEmpty(),
                "unknown exception is not cached as terminal");
            access.close();
        }
    }

    private static void preparedWorldMaterials() {
        HologramState state = state(row(0, 1, -1, false), row(16, 2, -1, false), row(32, 3, -1, false));
        HologramTextures.Material light = new HologramTextures.Material(
            null,
            null,
            Collections.emptyList(),
            false,
            false,
            false,
            false);
        HologramTextures.Material detailed = new HologramTextures.Material(
            null,
            null,
            Collections.emptyList(),
            false,
            false,
            false,
            false);
        HologramRenderer.PreparedWorldMaterials prepared = new HologramRenderer.PreparedWorldMaterials();
        boolean[] parentList = { false };
        int[] calls = { 0, 0 };
        java.util.function.Supplier<HologramTextures.Material> lightProvider = () -> {
            SimpleAssert.that(!parentList[0], "cold light material/getIcon must precede parent list");
            calls[0]++;
            return light;
        };
        java.util.function.Supplier<HologramTextures.Material> detailedProvider = () -> {
            SimpleAssert.that(!parentList[0], "detailed material callback must precede parent list");
            calls[1]++;
            return detailed;
        };
        prepared.prepare(state.cells.get(0), true, true, lightProvider, detailedProvider);
        prepared.prepare(state.cells.get(1), true, false, lightProvider, detailedProvider);
        prepared.prepare(state.cells.get(2), false, false, lightProvider, detailedProvider);
        SimpleAssert.eq(2, calls[0], "payload-ready/native-deferred chooses the light provider in the prepass");
        SimpleAssert.eq(1, calls[1], "only resolved detailed material runs detailed provider");
        parentList[0] = true;
        for (int repeat = 0; repeat < 3; repeat++) {
            SimpleAssert
                .that(prepared.get(state.cells.get(0)) == light, "deferred list uses exact preselected light object");
            SimpleAssert
                .that(prepared.get(state.cells.get(1)) == detailed, "detailed list uses exact preselected object");
            SimpleAssert
                .that(prepared.get(state.cells.get(2)) == light, "payload-waiting list uses stored light object");
        }
        SimpleAssert.eq(2, calls[0], "list consumption never invokes even a cold light callback");
        SimpleAssert.eq(1, calls[1], "list consumption invokes no detailed callback");
    }

    @SuppressWarnings("unchecked")
    private static void inspectionSides() throws ReflectiveOperationException {
        Map<HologramState.Cell, Object> models = (Map<HologramState.Cell, Object>) field(
            HologramBlockRender.class,
            "MODELS").get(null);
        Map<HologramState.Cell, Object> savedModels = new IdentityHashMap<>(models);
        Map<String, String> failures = (Map<String, String>) field(HologramBlockRender.class, "FAILURES").get(null);
        Map<String, String> savedFailures = new java.util.HashMap<>(failures);
        try {
            Class<?> model = Class.forName(HologramBlockRender.class.getName() + "$Model");
            Constructor<?> constructor = model
                .getDeclaredConstructor(int.class, int.class, int.class, List.class, List.class, boolean.class);
            constructor.setAccessible(true);
            for (String kind : new String[] { "ordinary", "anchor", "protected" }) {
                NBTTagCompound row = row(0, 0, -1, false);
                row.setString("id", "minecraft:dirt");
                if (kind.equals("anchor")) row.setBoolean("anchor", true);
                if (kind.equals("protected")) {
                    row.setString("status", "protected");
                    row.setTag(
                        "actualStack",
                        new net.minecraft.item.ItemStack(Blocks.dirt).writeToNBT(new NBTTagCompound()));
                }
                HologramState.Cell cell = state(row).cells.get(0);
                Object pair = java.lang.reflect.Array.newInstance(model, 2);
                java.lang.reflect.Array.set(
                    pair,
                    0,
                    constructor.newInstance(0, 4, 3, Collections.emptyList(), Collections.emptyList(), false));
                java.lang.reflect.Array.set(
                    pair,
                    1,
                    constructor.newInstance(0, 8, 6, Collections.emptyList(), Collections.emptyList(), true));
                models.put(cell, pair);
                failures.put(cell.wantId, "target failure");
                failures.put(cell.id, "actual failure");
                boolean effective = !kind.equals("ordinary");
                SimpleAssert.eq(
                    effective,
                    HologramBlockRender.missingTexture(cell, false),
                    "missingTexture follows effective renderer side for " + kind);
                SimpleAssert.eq(
                    effective ? "actual failure" : "target failure",
                    HologramBlockRender.failure(cell, false),
                    "failure follows effective renderer side for " + kind);
                SimpleAssert.eq(
                    effective ? 8 : 4,
                    HologramBlockRender.vertexCount(cell, false),
                    "inspection counts use same side");
                SimpleAssert.that(
                    HologramBlockRender.missingTexture(cell, true),
                    "explicit current/mode2 sees actual missing texture");
            }
        } finally {
            models.clear();
            models.putAll(savedModels);
            failures.clear();
            failures.putAll(savedFailures);
        }
    }

    @SuppressWarnings("unchecked")
    private static void deferredSides() throws ReflectiveOperationException {
        Map<HologramState.Cell, Integer> deferred = (Map<HologramState.Cell, Integer>) field(
            HologramBlockRender.class,
            "DEFERRED").get(null);
        Map<HologramState.Cell, Integer> saved = new IdentityHashMap<>(deferred);
        java.lang.reflect.Method undefer = HologramBlockRender.class
            .getDeclaredMethod("undefer", HologramState.Cell.class, boolean.class);
        java.lang.reflect.Method stored = HologramBlockRender.class
            .getDeclaredMethod("deferredSide", HologramState.Cell.class, boolean.class);
        undefer.setAccessible(true);
        stored.setAccessible(true);
        try {
            deferred.clear();
            for (String kind : new String[] { "anchor", "protected" }) {
                NBTTagCompound row = row(0, 0, -1, false);
                if (kind.equals("anchor")) row.setBoolean("anchor", true);
                else {
                    row.setString("status", "protected");
                    row.setTag(
                        "actualStack",
                        new net.minecraft.item.ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
                }
                HologramState.Cell cell = state(row).cells.get(0);
                HologramBlockRender.defer(cell, true); // ready(cell,false) normalizes to this actual side.
                SimpleAssert.that(
                    HologramBlockRender.deferred(cell, false),
                    kind + " requested-target consumer sees the actual-side WAITING result");
                SimpleAssert.eq(
                    1,
                    deferred.get(cell)
                        .intValue(),
                    "effective wait is stored only as the actual bit");
                SimpleAssert
                    .eq(false, stored.invoke(null, cell, false), "raw retry traversal does not invent target work");
                SimpleAssert.eq(true, stored.invoke(null, cell, true), "raw retry traversal retains actual-side work");
                HologramTextures.Material light = new HologramTextures.Material(
                    null,
                    null,
                    Collections.emptyList(),
                    false,
                    false,
                    false,
                    false);
                HologramRenderer.PreparedWorldMaterials materials = new HologramRenderer.PreparedWorldMaterials();
                int[] calls = { 0, 0 };
                materials.prepare(cell, true, HologramBlockRender.deferred(cell, false), () -> {
                    calls[0]++;
                    return light;
                }, () -> {
                    calls[1]++;
                    throw new AssertionError("waiting effective side must not resolve detailed material");
                });
                SimpleAssert.that(
                    materials.get(cell) == light && calls[0] == 1 && calls[1] == 0,
                    kind + " payload-ready/actual-waiting selects stored light material before the list");
                undefer.invoke(null, cell, false);
                SimpleAssert.that(
                    !HologramBlockRender.deferred(cell, true) && !deferred.containsKey(cell),
                    "requested-target completion clears exactly the effective actual wait");
                HologramBlockRender.defer(cell, false);
                SimpleAssert.eq(
                    1,
                    deferred.get(cell)
                        .intValue(),
                    "raw requested false write normalizes to actual bit too");
                undefer.invoke(null, cell, true);
                SimpleAssert.that(!deferred.containsKey(cell), "actual retry completion removes normalized entry once");
            }
            HologramState.Cell ordinary = state(row(0, 0, -1, false)).cells.get(0);
            HologramBlockRender.defer(ordinary, false);
            HologramBlockRender.defer(ordinary, true);
            SimpleAssert.eq(
                3,
                deferred.get(ordinary)
                    .intValue(),
                "ordinary target and actual waits remain independently stored");
            undefer.invoke(null, ordinary, false);
            SimpleAssert.that(
                !HologramBlockRender.deferred(ordinary, false) && HologramBlockRender.deferred(ordinary, true),
                "finishing target cannot lose an ordinary actual-side retry");
            undefer.invoke(null, ordinary, true);
            SimpleAssert.that(!deferred.containsKey(ordinary), "both ordinary sides retire independently");
        } finally {
            deferred.clear();
            deferred.putAll(saved);
        }
    }

    private static void dataPrivilege() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramState state = state(row(0, 23, -1, false));
            HologramBlockAccess access = isolated(state);
            access.prepare(state.cells.get(0), budget(1));
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.WAITING,
                access.outcome(state.cells.get(0)),
                "decoded DATA alone is not ordinary READY");
            Class<?> wrapper = Class.forName(HologramBlockAccess.class.getName() + "$DataAccess");
            Constructor<?> constructor = wrapper.getDeclaredConstructor(HologramBlockAccess.class);
            constructor.setAccessible(true);
            IBlockAccess data = (IBlockAccess) constructor.newInstance(access);
            SimpleAssert.that(data.getTileEntity(0, 0, 0) != null, "reviewed wrapper can consume decoded DATA");
            access.beginModel(state.cells.get(0), true);
            SimpleAssert
                .eq(null, access.getTileEntity(0, 0, 0), "ordinary renderer access cannot inherit DATA privilege");
            SimpleAssert.that(!access.endModel(), "ordinary intermediate-state read remains incomplete");
            access.prepare(state.cells.get(0), budget(32));
            SimpleAssert.eq(
                HologramNativePreparation.Outcome.READY,
                access.outcome(state.cells.get(0)),
                "final topology stage makes normal access ready");
        }
    }

    @SuppressWarnings("unchecked")
    private static void wakeupAndIdentity() throws ReflectiveOperationException {
        HologramState state = state(row(0, 1, -1, false), row(16, 2, -1, false), row(48, 3, -1, false));
        Field regionsField = field(HologramRenderer.class, "previewRegions");
        Map<Object, Object> regions = (Map<Object, Object>) regionsField.get(null);
        Map<Object, Object> savedRegions = new java.util.LinkedHashMap<>(regions);
        Field preparationField = field(HologramRenderer.class, "previewPreparation");
        Field previewStateField = field(HologramRenderer.class, "previewState");
        Field worldStateField = field(HologramRenderer.class, "worldState");
        Object savedPreparation = preparationField.get(null), savedState = previewStateField.get(null),
            savedWorld = worldStateField.get(null);
        Map<HologramState.Cell, Integer> deferred = (Map<HologramState.Cell, Integer>) field(
            HologramBlockRender.class,
            "DEFERRED").get(null);
        Map<HologramState.Cell, Integer> savedDeferred = new IdentityHashMap<>(deferred);
        try {
            regions.clear();
            Class<?> regionType = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion");
            Constructor<?> regionConstructor = regionType.getDeclaredConstructor();
            regionConstructor.setAccessible(true);
            Object complete = regionConstructor.newInstance();
            field(regionType, "complete").setBoolean(complete, true);
            HologramRegionCache.Key first = HologramRegionCache.key(state.cells.get(0));
            regions.put(first, complete);
            Class<?> preparationType = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation");
            Constructor<?> constructor = preparationType.getDeclaredConstructor(HologramState.class);
            constructor.setAccessible(true);
            Object preparation = constructor.newInstance(state);
            Map<Object, Object> pending = (Map<Object, Object>) field(preparationType, "pending").get(preparation);
            HologramRegionCache.Key other = HologramRegionCache.key(state.cells.get(1));
            Object untouched = pending.get(other);
            field(untouched.getClass(), "cell").setInt(untouched, 1);
            preparationField.set(null, preparation);
            previewStateField.set(null, state);
            worldStateField.set(null, null);
            HologramRenderer.nativePreparationChanged(state, Collections.singleton(first));
            SimpleAssert.that(
                pending.containsKey(first),
                "same-access progress wakes a previously completed unresolved region");
            SimpleAssert.that(
                pending.get(other) == untouched && field(untouched.getClass(), "cell").getInt(untouched) == 1,
                "native wakeup preserves unrelated partial region/cursor");
            SimpleAssert
                .eq(null, previewStateField.get(null), "resolved region publication requests projection refresh");
            SimpleAssert.that(!regions.containsKey(first), "only affected consumer region is rebuilt");

            deferred.clear();
            HologramState duplicate = state(row(0, 1, -1, false));
            HologramBlockRender.defer(state.cells.get(0), false);
            HologramBlockRender.defer(duplicate.cells.get(0), false);
            SimpleAssert
                .eq(2, deferred.size(), "fair retry map preserves Cell identity, not coordinate/value equality");
            java.lang.reflect.Method rotate = deferred.getClass()
                .getDeclaredMethod("rotate");
            rotate.setAccessible(true);
            SimpleAssert
                .that(rotate.invoke(deferred) == state.cells.get(0), "oldest deferred consumer gets first turn");
            SimpleAssert
                .that(rotate.invoke(deferred) == duplicate.cells.get(0), "retry rotation reaches next identity");
        } finally {
            regions.clear();
            regions.putAll(savedRegions);
            preparationField.set(null, savedPreparation);
            previewStateField.set(null, savedState);
            worldStateField.set(null, savedWorld);
            deferred.clear();
            deferred.putAll(savedDeferred);
        }
    }

    private static NBTTagCompound row(int x, int value, int dependency, boolean pending) {
        NBTTagCompound row = new NBTTagCompound(), display = new NBTTagCompound(), payload = new NBTTagCompound();
        row.setInteger("x", x);
        row.setString("id", "minecraft:stone");
        row.setString("wantId", "minecraft:stone");
        payload.setInteger("value", value);
        if (dependency >= 0) payload.setInteger("depends", dependency);
        display.setTag("nativeTile", payload);
        row.setTag("actualDisplay", display.copy());
        row.setTag("targetDisplay", display);
        row.setInteger("nativeModelPending", pending ? 3 : 0);
        return row;
    }

    private static HologramState state(NBTTagCompound... rows) {
        NBTTagCompound data = new NBTTagCompound();
        NBTTagList cells = new NBTTagList();
        for (NBTTagCompound row : rows) cells.appendTag(row);
        data.setTag("cells", cells);
        return new HologramState(data);
    }

    private static HologramBlockAccess isolated(HologramState state) throws ReflectiveOperationException {
        return isolated(state, false);
    }

    private static HologramBlockAccess isolated(HologramState state, boolean current)
        throws ReflectiveOperationException {
        HologramBlockAccess access = new HologramBlockAccess(state, current);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        HologramRenderWorld world;
        try {
            world = (HologramRenderWorld) unsafe.allocateInstance(HologramRenderWorld.class);
        } catch (InstantiationException failure) {
            throw new ReflectiveOperationException(failure);
        }
        world.isRemote = true;
        field(HologramRenderWorld.class, "access").set(world, access);
        field(HologramBlockAccess.class, "renderWorld").set(access, world);
        return access;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Probe extends TileEntity {

        int value;
    }

    private static final class Fixture implements AutoCloseable {

        int depth, maximumDepth, total, failureAt = Integer.MIN_VALUE;
        boolean nullFactory;
        final Map<Integer, Integer> loads = new java.util.HashMap<>();
        final HologramNativeRenderRegistry.Adapter adapter = new HologramNativeRenderRegistry.Adapter() {

            public boolean supports(Block block) {
                return block == Blocks.stone;
            }

            public boolean requiresTile(Block block, int metadata) {
                return true;
            }

            public TileEntity createTile(Block block, int metadata, World world) {
                return nullFactory ? null : new Probe();
            }

            public void loadTile(TileEntity tile, NBTTagCompound payload) {
                total++;
                depth++;
                maximumDepth = Math.max(maximumDepth, depth);
                loads.put(tile.xCoord, loads.getOrDefault(tile.xCoord, 0) + 1);
                try {
                    if (tile.xCoord == failureAt) throw new IllegalStateException("synthetic terminal native failure");
                    Probe probe = (Probe) tile;
                    probe.value = payload.getInteger("value");
                    for (String dependency : new String[] { "depends", "depends2" }) if (payload.hasKey(dependency)) {
                        Probe input = (Probe) tile.getWorldObj()
                            .getTileEntity(payload.getInteger(dependency), 0, 0);
                        probe.value = input == null ? -1 : input.value;
                    }
                    if (payload.getBoolean("throwAfterReads"))
                        throw new IllegalStateException("exception after unknown read");
                } finally {
                    depth--;
                }
            }
        };

        Fixture() {
            HologramNativeRenderRegistry.register(adapter);
        }

        public void close() {
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }
}
