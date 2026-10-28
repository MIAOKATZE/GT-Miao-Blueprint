package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Set;

import net.minecraft.init.Blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Queue saturation recovery, without GL/native rendering or wall-clock performance assertions. */
public final class HologramAuthorModelDemandRegression {

    private HologramAuthorModelDemandRegression() {}

    public static void run() {
        HologramNativeRenderRegistry.Adapter adapter = block -> block == Blocks.stone;
        HologramNativeRenderRegistry.register(adapter);
        try {
            rejectedNeighborIsRetriedAfterReaderLoaded();
            retriesKeepCountTimeAndOwnerBounds();
        } catch (IOException | ReflectiveOperationException failure) {
            throw new AssertionError("author connected model demand", failure);
        } finally {
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    private static void rejectedNeighborIsRetriedAfterReaderLoaded() throws IOException, ReflectiveOperationException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document())) {
            HologramState state = projection.view();
            HologramState.Cell reader = state.cells.get(0), neighbor = state.cells.get(513);
            SimpleAssert.eq(false, reader.modelPending(false), "reader already has its own appearance");
            for (int i = 1; i <= 512; i++) projection.request(state, state.cells.get(i));
            SimpleAssert.eq(512, queued(projection).size(), "fill the real bounded queue");
            SimpleAssert.eq(false, HologramSnapshotModelsClient.ready(state, reader, false), "neighbor is unresolved");
            SimpleAssert.eq(false, queued(projection).contains(neighbor.nativeModelId), "full queue rejected neighbor");

            // Far-away demand cannot invalidate the reader's region or its adjacent dependency region.
            projection.advance(() -> 0);
            state = projection.view();
            SimpleAssert.eq(0, queued(projection).size(), "unrelated demand has drained");
            SimpleAssert.that(state.cells.get(0) == reader, "reader remains cached and unchanged");
            SimpleAssert.eq(
                true,
                state.cells.get(513)
                    .modelPending(false),
                "dropped neighbor remains pending");
            HologramSnapshotModelsClient.requestVisible(state, reader, false);
            SimpleAssert.eq(0, queued(projection).size(), "old per-frame reader-only retry cannot recover neighbor");

            HologramRenderer.retryAuthorModelDemands(state, 1, index -> reader, 0, false, 1, () -> 0);
            SimpleAssert.eq(
                true,
                queued(projection).contains(neighbor.nativeModelId),
                "readiness retry restores neighbor demand");
            projection.advance(() -> 0);
            SimpleAssert.eq(
                false,
                projection.view().cells.get(513)
                    .modelPending(false),
                "restored demand reaches terminal result");
            SimpleAssert.eq(
                true,
                HologramSnapshotModelsClient.ready(projection.view(), reader, false),
                "no stranded pending neighbor");
        }
    }

    private static void retriesKeepCountTimeAndOwnerBounds() throws IOException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document())) {
            HologramState state = projection.view();
            HologramState.Cell reader = state.cells.get(0);
            final int[] calls = { 0 };
            int cursor = HologramRenderer.retryAuthorModelDemands(state, 100, index -> {
                calls[0]++;
                return reader;
            }, 0, false, 1, () -> 0);
            SimpleAssert.eq(16, calls[0], "nonadvancing clock cannot cause a full-view scan");
            SimpleAssert.eq(16, cursor, "bounded batch preserves round-robin cursor");
            final int[] seen = { -1 };
            cursor = HologramRenderer.retryAuthorModelDemands(state, 100, index -> {
                seen[0] = index;
                return reader;
            }, cursor, false, 1, () -> 1);
            SimpleAssert.eq(16, seen[0], "deadline yield resumes where prior batch stopped");
            SimpleAssert.eq(17, cursor, "only one indivisible neighborhood proceeds at an exhausted deadline");
            cursor = HologramRenderer.retryAuthorModelDemands(
                null,
                100,
                index -> { throw new AssertionError("non-author contexts must not be read"); },
                cursor,
                false,
                1,
                () -> 0);
            SimpleAssert.eq(17, cursor, "absent owner cannot schedule local demand");
            SimpleAssert.eq(
                17,
                HologramRenderer.retryAuthorModelDemands(state, 0, index -> reader, cursor, false, 1, () -> 0),
                "empty viewport does not advance cursor");
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> queued(HologramAuthorProjection projection) throws ReflectiveOperationException {
        Field field = HologramAuthorProjection.class.getDeclaredField("queued");
        field.setAccessible(true);
        return (Set<Long>) field.get(projection);
    }

    private static AuthorDocument document() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        for (int i = 0; i < 514; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", i == 0 ? 15 : i == 513 ? 16 : 64 + i * 16);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            // Explicit unsupported result is enough to test scheduling without native callbacks or GL.
            if (i != 0) cell.addProperty("tileNbt", "not-base64!");
            cells.add(cell);
        }
        root.add("cells", cells);
        return AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults());
    }
}
