package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Source ownership, sparse invalidation and lazy payload contracts. Does not claim native GL equivalence. */
public final class HologramAuthorProjectionRegression {

    private HologramAuthorProjectionRegression() {}

    public static void run() {
        try {
            coldAndIncremental();
            topologyKeepsPartialRefinement();
            deleteTailAndUndo();
            failedPayloadIsExplicitShell();
            metadataCannotImportServerAuthority();
            residentBudgetUsesBytes();
            overflowRecoversAfterCameraTurn();
        } catch (IOException | ReflectiveOperationException failure) {
            throw new AssertionError("local document projection", failure);
        }
    }

    private static void coldAndIncremental() throws IOException {
        AuthorDocument document = document(3);
        AuthorEditor editor = new AuthorEditor(document, 16);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            HologramState before = projection.view();
            SimpleAssert.eq(3, before.cells.size(), "cold projection retains all source cells");
            SimpleAssert.eq(
                0,
                before.cells.get(0).actualDisplay.func_150296_c()
                    .size(),
                "opaque invalid NBT is not decoded while opening geometry");
            SimpleAssert.eq(
                true,
                before.cells.get(0)
                    .modelPending(false),
                "native payload is explicitly unresolved");
            SimpleAssert.eq("source prompt", before.cells.get(0).prompt, "cold geometry preserves exact prompt");
            SimpleAssert.eq(4, before.cells.get(0).targetFacing, "cold geometry preserves source facing");
            SimpleAssert.eq("templateController", before.cells.get(0).role, "saved controller is a template role");
            SimpleAssert.eq(false, before.cells.get(0).anchor, "template role cannot synthesize physical protection");
            editor.select(Collections.singleton(projection.cellId(0)));
            HologramState after = projection.update(editor.move(1, 0, 0));
            SimpleAssert
                .that(after.follows(before), "local publications have direct provenance without wire revisions");
            SimpleAssert.that(after.cells.get(1) == before.cells.get(1), "untouched cells keep identity");
            HologramRegionCache.Key far = new HologramRegionCache.Key(64, 0, 0);
            SimpleAssert.that(
                after.regions.regions.get(far) == before.regions.regions.get(far),
                "untouched region input remains shared");
            SimpleAssert.eq(1, after.parsedCells, "one-cell move projects one cell, not a full NBT snapshot");
            SimpleAssert.that(
                ((HologramDocumentIndex<?, ?>) after.regions.regions).copiedNodes < 16,
                "point update replaces an indexed region path, not a complete region directory");
            SimpleAssert.eq(1, after.cells.get(0).x, "local geometry is document-relative");
        }
    }

    @SuppressWarnings("unchecked")
    private static void topologyKeepsPartialRefinement() throws IOException, ReflectiveOperationException {
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        for (int i = 0; i < 101; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", i == 100 ? 1024 : i % 10);
            cell.addProperty("y", 0);
            cell.addProperty("z", i == 100 ? 0 : i / 10);
            cell.addProperty("block", "minecraft:stone");
            cells.add(cell);
        }
        source.add("cells", cells);
        AuthorEditor editor = new AuthorEditor(
            AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()),
            16);
        Field active = field(HologramRenderer.class, "previewPreparation");
        Object saved = active.get(null);
        Map<Object, Object> regions = (Map<Object, Object>) field(HologramRenderer.class, "previewRegions").get(null);
        Map<Object, Object> savedRegions = new java.util.LinkedHashMap<>(regions);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current())) {
            regions.clear();
            Class<?> type = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation");
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(HologramState.class);
            constructor.setAccessible(true);
            Object preparation = constructor.newInstance(projection.view());
            active.set(null, preparation);
            java.lang.reflect.Method advance = type.getDeclaredMethod(
                "advance",
                int.class,
                int.class,
                boolean.class,
                long.class,
                java.util.function.LongSupplier.class);
            advance.setAccessible(true);
            java.util.function.LongSupplier available = () -> 0;
            advance.invoke(preparation, Integer.MIN_VALUE, 0, false, Long.MAX_VALUE, available);
            // Cheap-first scheduling visits the far one-cell region before refining the first region.
            advance.invoke(preparation, Integer.MIN_VALUE, 0, false, 0L, available);
            SimpleAssert.eq(
                101,
                field(type, "completed").getInt(preparation),
                "fixture finishes every source hint before detail");
            SimpleAssert.eq(
                0,
                field(type, "detailedCompleted").getInt(preparation),
                "full cheap coverage is not native refinement");
            advance.invoke(preparation, Integer.MIN_VALUE, 0, false, 0L, available);
            Map<?, ?> pending = (Map<?, ?>) field(type, "pending").get(preparation);
            Object task = pending.get(new HologramRegionCache.Key(0, 0, 0));
            SimpleAssert.eq(
                true,
                field(task.getClass(), "refining").getBoolean(task),
                "fixture pauses in native refinement, not the cheap hint pass");
            SimpleAssert.eq(1, field(task.getClass(), "cell").getInt(task), "fixture has partial refinement");
            editor.select(Collections.singleton(projection.cellId(100)));
            projection.update(editor.move(1, 0, 0));
            SimpleAssert.that(active.get(null) == preparation, "local topology edit retains preparation owner");
            SimpleAssert.that(
                pending.get(new HologramRegionCache.Key(0, 0, 0)) == task,
                "remote local edit preserves unrelated refinement task");
            SimpleAssert.eq(1, field(task.getClass(), "cell").getInt(task), "partial refinement cursor is retained");
            SimpleAssert.eq(101, field(type, "completed").getInt(preparation), "unaffected completed work stays exact");
        } finally {
            active.set(null, saved);
            regions.clear();
            regions.putAll(savedRegions);
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void deleteTailAndUndo() throws IOException {
        AuthorEditor editor = new AuthorEditor(document(5), 16);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current())) {
            long retained = projection.cellId(2), first = projection.cellId(0), tail = projection.cellId(4);
            HologramState.Cell untouched = projection.view().cells.get(2);
            editor.select(Arrays.asList(first, tail));
            projection.update(editor.delete());
            SimpleAssert.eq(3, projection.view().cells.size(), "multiple removals shrink only render slots");
            SimpleAssert.eq(-1, projection.index(first), "deleted durable identity is absent");
            SimpleAssert.eq(2, projection.index(retained), "unaffected middle slot stays stable");
            SimpleAssert.that(projection.view().cells.get(2) == untouched, "multi-delete retains unaffected object");
            projection.update(editor.undo());
            SimpleAssert.eq(5, projection.view().cells.size(), "undo restores deleted source cells");
            SimpleAssert.that(
                projection.index(first) >= 0 && projection.index(tail) >= 0,
                "undo restores durable identities, not stale dense indices");
            for (int i = 0; i < projection.view().cells.size(); i++)
                SimpleAssert.eq(i, projection.view().cells.get(i).index, "dense slots and cell indices agree");
        }
    }

    private static void failedPayloadIsExplicitShell() throws IOException, ReflectiveOperationException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(3))) {
            HologramState before = projection.view();
            HologramSnapshotModelsClient.requestVisible(before, before.cells.get(0), false);
            Field queue = HologramAuthorProjection.class.getDeclaredField("queued");
            queue.setAccessible(true);
            SimpleAssert
                .eq(1, ((java.util.Set<?>) queue.get(projection)).size(), "visible demand stays in document owner");
            SimpleAssert.eq(true, projection.advance(), "one demanded payload publishes its bounded result");
            HologramState after = projection.view();
            SimpleAssert.eq(
                false,
                after.cells.get(0)
                    .modelPending(false),
                "invalid payload has explicit terminal fallback");
            SimpleAssert.that(!after.cells.get(0).sourceWarning.isEmpty(), "native fallback has a visible diagnostic");
            SimpleAssert.that(after.cells.get(1) == before.cells.get(1), "unrequested invalid payload remains cold");
        }
    }

    private static void metadataCannotImportServerAuthority() throws IOException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(1))) {
            NBTTagCompound metadata = new NBTTagCompound();
            metadata.setString("session", "server-session");
            metadata.setLong("planRevision", 9999);
            metadata.setTag("detailManifest", new NBTTagCompound());
            metadata.setBoolean("processingReady", true);
            metadata.setBoolean("blueprintDirty", true);
            HologramState view = projection.refreshMetadata(metadata);
            SimpleAssert.eq(true, view.data.getBoolean("blueprintDirty"), "UI decoration can express local dirty");
            SimpleAssert.eq(false, view.data.hasKey("detailManifest"), "local view cannot enter server detail RPC");
            SimpleAssert.eq(false, view.data.getBoolean("processingReady"), "decorations cannot certify native review");
            SimpleAssert.eq(0L, view.data.getLong("planRevision"), "document revision cannot be overwritten by UI");
        }
    }

    @SuppressWarnings("unchecked")
    private static void residentBudgetUsesBytes() throws IOException, ReflectiveOperationException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(1300))) {
            Map<Long, Long> loaded = (Map<Long, Long>) field(HologramAuthorProjection.class, "loaded").get(projection);
            Field resident = field(HologramAuthorProjection.class, "resident");
            for (int i = 0; i < 1254; i++) loaded.put(projection.cellId(i), 8192L);
            resident.setLong(projection, 1254L * 8192);
            java.lang.reflect.Method room = HologramAuthorProjection.class
                .getDeclaredMethod("makeRoom", long.class, Map.class);
            room.setAccessible(true);
            SimpleAssert.eq(
                true,
                room.invoke(projection, 16384L, new java.util.LinkedHashMap<>()),
                "1254 small detailed cells below byte quota do not hit an arbitrary 256-cell cap");
            resident.setLong(projection, 32L * 1024 * 1024);
            SimpleAssert.eq(
                false,
                room.invoke(projection, 16384L, new java.util.LinkedHashMap<>()),
                "unknown viewport cannot evict visible details merely to cycle the quota");
            SimpleAssert.eq(1254, loaded.size(), "quota pressure retains visible native models without thrashing");
        }
    }

    @SuppressWarnings("unchecked")
    private static void overflowRecoversAfterCameraTurn() throws IOException, ReflectiveOperationException {
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        NBTTagCompound nativeData = new NBTTagCompound();
        nativeData.setString("marker", "visible-after-turn");
        String payload = java.util.Base64.getEncoder()
            .encodeToString(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(nativeData));
        for (int i = 0; i < 3; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", i * 1024);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            cell.addProperty("tileNbt", payload);
            cells.add(cell);
        }
        source.add("cells", cells);
        HologramAuthorProjection projection = new HologramAuthorProjection(
            AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()));
        Field visible = field(HologramRenderer.class, "visibleState"),
            frame = field(HologramRenderer.class, "projectedFrame"),
            layer = field(HologramRenderer.class, "visibleLayer"), mode = field(HologramRenderer.class, "visibleMode"),
            viewMode = field(HologramRenderer.class, "visibleView");
        Object oldVisible = visible.get(null), oldFrame = frame.get(null);
        int oldLayer = layer.getInt(null), oldMode = mode.getInt(null), oldView = viewMode.getInt(null),
            oldDirty = HologramRenderer.lastSnapshotDirtyRegions;
        Map<Object, Object> regions = (Map<Object, Object>) field(HologramRenderer.class, "previewRegions").get(null);
        Map<Object, Object> savedRegions = new java.util.LinkedHashMap<>(regions);
        try {
            HologramState before = projection.view();
            visible.set(null, before);
            layer.setInt(null, Integer.MAX_VALUE);
            mode.setInt(null, 0);
            viewMode.setInt(null, 0);
            frame.set(null, new HologramEditorCameraFrame(0, 0, 200, 200, 0, 0, 1, 0, 0, 0, 16));
            SimpleAssert.eq(
                true,
                HologramRenderer.authorCellVisible(before, before.cells.get(0)),
                "old camera sees resident model");
            SimpleAssert.eq(
                false,
                HologramRenderer.authorCellVisible(before, before.cells.get(1)),
                "future demand starts offscreen");
            for (int i = 0; i <= 65536; i++) projection.dependency(before.cells.get(0), i * 16, 0, 0);
            Field overflow = field(HologramAuthorProjection.class, "dependencyOverflow");
            SimpleAssert
                .eq(true, overflow.getBoolean(projection), "real reader-edge limit switches to conservative mode");
            Map<Long, Long> loaded = (Map<Long, Long>) field(HologramAuthorProjection.class, "loaded").get(projection);
            loaded.put(projection.cellId(0), 32L * 1024 * 1024);
            field(HologramAuthorProjection.class, "resident").setLong(projection, 32L * 1024 * 1024);
            Map<Long, Long> waiting = (Map<Long, Long>) field(HologramAuthorProjection.class, "budgetDeferred")
                .get(projection);
            waiting.put(projection.cellId(1), 16384L);
            Class<?> regionType = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion");
            java.lang.reflect.Constructor<?> constructor = regionType.getDeclaredConstructor();
            constructor.setAccessible(true);
            regions.clear();
            for (HologramRegionCache.Key key : before.regions.regions.keySet())
                regions.put(key, constructor.newInstance());
            frame.set(null, new HologramEditorCameraFrame(0, 0, 200, 200, 0, 0, 1, 1024, 0, 0, 16));
            SimpleAssert.eq(
                false,
                HologramRenderer.authorCellVisible(before, before.cells.get(0)),
                "camera turn leaves old payload offscreen");
            SimpleAssert.eq(
                true,
                HologramRenderer.authorCellVisible(before, before.cells.get(1)),
                "camera turn reveals deferred demand");
            SimpleAssert.eq(true, projection.advance(), "overflow cannot permanently block visible budget recovery");
            HologramState after = projection.view();
            SimpleAssert.that(
                !loaded.containsKey(projection.cellId(0)),
                "offscreen resident bytes are reclaimed despite unknown readers");
            SimpleAssert.that(
                loaded.containsKey(projection.cellId(1)),
                "new visible payload reaches the bounded native-data consumer");
            SimpleAssert.eq(
                "visible-after-turn",
                after.cells.get(1).targetDisplay.getCompoundTag("nativeTile")
                    .getString("marker"),
                "recovered visible data is attached, not a resource-limited or unsupported shell");
            SimpleAssert.eq(
                false,
                after.cells.get(1)
                    .modelPending(false),
                "recovered payload completes its demand");
            SimpleAssert.that(waiting.isEmpty(), "recovered visible demand leaves budget wait list");
            SimpleAssert.that(
                regions.isEmpty(),
                "unknown-reader eviction retires even the unrelated third region's old output");
            SimpleAssert.eq(
                false,
                overflow.getBoolean(projection),
                "dependency tracking restarts only after whole-owner retirement");
            SimpleAssert.eq(
                0,
                field(HologramAuthorProjection.class, "dependencyCount").getInt(projection),
                "retired graph accounting resets");
            projection.dependency(after.cells.get(1), 2048, 0, 0);
            SimpleAssert.eq(
                1,
                field(HologramAuthorProjection.class, "dependencyCount").getInt(projection),
                "new native readers can be tracked again");
        } finally {
            visible.set(null, oldVisible);
            frame.set(null, oldFrame);
            layer.setInt(null, oldLayer);
            mode.setInt(null, oldMode);
            viewMode.setInt(null, oldView);
            HologramRenderer.lastSnapshotDirtyRegions = oldDirty;
            regions.clear();
            regions.putAll(savedRegions);
            projection.close();
        }
    }

    private static AuthorDocument document(int count) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("name", "local");
        JsonArray cells = new JsonArray();
        for (int i = 0; i < count; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", i * 1024);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            cell.addProperty("tileNbt", "not-base64!");
            if (i == 0) {
                cell.addProperty("prompt", "source prompt");
                cell.addProperty("facing", 4);
                cell.addProperty("controller", true);
            }
            cells.add(cell);
        }
        root.add("cells", cells);
        return AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults());
    }
}
