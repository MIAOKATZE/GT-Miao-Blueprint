package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Hidden-layer budget dependencies remain unknown and recover through bounded existing demand ownership. */
public final class HologramAuthorBudgetRecoveryRegression {

    private static final long LIMIT = 32L * 1024 * 1024;

    private HologramAuthorBudgetRecoveryRegression() {}

    public static void run() {
        HologramNativeRenderRegistry.Adapter adapter = block -> block == Blocks.stone;
        HologramNativeRenderRegistry.register(adapter);
        try {
            hiddenDependencyRecovers(false, false);
            hiddenDependencyRecovers(true, false);
            hiddenDependencyRecovers(true, true);
            deniedAdmissionsHonorDeadline();
        } catch (IOException | ReflectiveOperationException failure) {
            throw new AssertionError("author budget dependency recovery", failure);
        } finally {
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    @SuppressWarnings("unchecked")
    private static void hiddenDependencyRecovers(boolean shellBeforeReader, boolean overflow)
        throws IOException, ReflectiveOperationException {
        AuthorEditor editor = editor(3, false);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current(), 32L << 20)) {
            HologramState original = projection.view();
            String[] fields = { "visibleState", "projectedFrame", "visibleLayer", "visibleView", "visibleMode" };
            Object[] saved = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) saved[i] = field(HologramRenderer.class, fields[i]).get(null);
            try {
                Map<Integer, HologramState.Cell> cells = new LinkedHashMap<>();
                for (HologramState.Cell cell : original.cells) cells.put(cell.index, cell);
                // Same owner but not the publication identity: visibility is real, no render/GL cache is installed.
                HologramState visibility = new HologramState(projection, original.data, null, cells.size(), cells);
                field(HologramRenderer.class, "visibleState").set(null, visibility);
                field(HologramRenderer.class, "projectedFrame")
                    .set(null, new HologramEditorCameraFrame(0, 0, 512, 512, 0, 0, 1, 16, 0, 0, 64));
                field(HologramRenderer.class, "visibleLayer").setInt(null, 0);
                field(HologramRenderer.class, "visibleView").setInt(null, 0);
                field(HologramRenderer.class, "visibleMode").setInt(null, 0);
                SimpleAssert.eq(
                    true,
                    HologramRenderer.authorCellVisible(original, original.cells.get(0)),
                    "reader layer is visible");
                SimpleAssert.eq(
                    false,
                    HologramRenderer.authorCellVisible(original, original.cells.get(1)),
                    "neighbor is outside selected layer");

                // Synthetic charged resident at the tail models quota pressure without allocating 32MiB in a unit test.
                Map<Long, Long> loaded = (Map<Long, Long>) field(HologramAuthorProjection.class, "loaded")
                    .get(projection);
                loaded.put(projection.cellId(2), LIMIT);
                field(HologramAuthorProjection.class, "resident").setLong(projection, LIMIT);
                if (shellBeforeReader) projection.request(original, original.cells.get(1));
                else SimpleAssert.eq(
                    false,
                    HologramSnapshotModelsClient.ready(original, original.cells.get(0), false),
                    "reader demands hidden neighbor");
                projection.advance(() -> 0);
                HologramState shell = projection.view();
                HologramState.Cell reader = shell.cells.get(0), neighbor = shell.cells.get(1);
                SimpleAssert.eq("localBudget", neighbor.renderKind, "pressure creates resumable shell");
                SimpleAssert.eq(0, neighbor.nativeModelPending, "budget state is distinct from wire pending bits");
                Map<Long, Long> deferred = (Map<Long, Long>) field(HologramAuthorProjection.class, "budgetDeferred")
                    .get(projection);
                long need = deferred.get(neighbor.nativeModelId);
                SimpleAssert.eq(
                    false,
                    HologramSnapshotModelsClient.ready(shell, neighbor, false),
                    "budget shell itself is not ready");
                // Discard that artificial hidden-cell self-probe; only the actual visible reader may retain it.
                ((Map<?, ?>) field(HologramAuthorProjection.class, "readers").get(projection)).clear();
                SimpleAssert.eq(
                    false,
                    HologramSnapshotModelsClient.ready(shell, reader, false),
                    "reader cannot mistake budget neighbor for empty");
                SimpleAssert.eq(
                    false,
                    projection.advance(() -> 0),
                    "renewed demand does not decode or republish under unchanged pressure");
                SimpleAssert.that(projection.view() == shell, "denied known cost preserves exact publication");
                SimpleAssert.eq(need, deferred.get(neighbor.nativeModelId), "denial preserves known cost");
                assertUnknownTileAndBudgetPlaceholder(shell, reader, neighbor);

                deferred.put(neighbor.nativeModelId, LIMIT + 1);
                queued(projection).clear();
                SimpleAssert.eq(
                    false,
                    HologramSnapshotModelsClient.ready(shell, reader, false),
                    "known oversize is still unresolved");
                SimpleAssert.eq(0, queued(projection).size(), "known oversize is never renewed for decoding");
                SimpleAssert
                    .eq(false, projection.advance(() -> 0), "known oversize only rotates bounded deferred metadata");
                deferred.put(neighbor.nativeModelId, need);

                // Delete the charged tail through the real lifecycle; the neighbor keeps its slot and deferred cost.
                editor.select(Collections.singleton(projection.cellId(2)));
                projection.update(editor.delete());
                SimpleAssert.eq(
                    0L,
                    field(HologramAuthorProjection.class, "resident").getLong(projection),
                    "deletion frees charged resident");
                HologramState available = projection.view();
                if (overflow) {
                    ((Map<?, ?>) field(HologramAuthorProjection.class, "readers").get(projection)).clear();
                    field(HologramAuthorProjection.class, "dependencyOverflow").setBoolean(projection, true);
                    SimpleAssert.eq(
                        false,
                        HologramSnapshotModelsClient.ready(available, available.cells.get(0), false),
                        "overflowed graph still renews visible reader demand");
                    SimpleAssert.eq(
                        true,
                        queued(projection).contains(neighbor.nativeModelId),
                        "existing bounded queue carries graph-independent renewal");
                } else queued(projection).clear(); // Prove the autonomous retained-reader path without an explicit
                                                   // hint.
                SimpleAssert
                    .eq(true, projection.advance(() -> 0), "hidden neighbor recovers after capacity becomes available");
                HologramState recovered = projection.view();
                SimpleAssert
                    .eq("", recovered.cells.get(1).renderKind, "recovery prepares actual display, not fallback");
                SimpleAssert.eq(
                    7,
                    recovered.cells.get(1).targetDisplay.getCompoundTag("nativeTile")
                        .getInteger("marker"),
                    "decoded native payload survives recovery");
                SimpleAssert.eq(
                    false,
                    deferred.containsKey(neighbor.nativeModelId),
                    "successful admission retires deferred cost");
                SimpleAssert.eq(
                    true,
                    HologramSnapshotModelsClient.ready(recovered, recovered.cells.get(0), false),
                    "reader becomes ready only after neighbor recovery");
            } finally {
                for (int i = 0; i < fields.length; i++) field(HologramRenderer.class, fields[i]).set(null, saved[i]);
            }
        }
    }

    private static void assertUnknownTileAndBudgetPlaceholder(HologramState state, HologramState.Cell reader,
        HologramState.Cell neighbor) throws ReflectiveOperationException {
        HologramBlockAccess access = new HologramBlockAccess(state);
        access.beginModel(reader);
        try {
            SimpleAssert
                .eq(null, access.getTileEntity(neighbor.x, neighbor.y, neighbor.z), "budget tile is unavailable");
            SimpleAssert.eq(false, access.endModel(), "native read is incomplete, not successful empty topology");
            SimpleAssert.eq(
                0,
                ((Set<?>) field(HologramBlockAccess.class, "loaded").get(access)).size(),
                "unknown tile never enters known-empty cache");
            SimpleAssert.eq(
                0,
                ((Map<?, ?>) field(HologramBlockAccess.class, "failures").get(access)).size(),
                "budget is not a permanent native failure");
        } finally {
            ((Map<?, ?>) field(HologramBlockRender.class, "DEFERRED").get(null)).remove(reader);
        }
        Class<?> type = Class.forName(HologramRenderer.class.getName() + "$PreviewRegion");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object region = constructor.newInstance();
        Method preview = HologramRenderer.class.getDeclaredMethod(
            "previewCell",
            HologramState.class,
            type,
            HologramState.Cell.class,
            int.class,
            int.class,
            boolean.class,
            boolean.class);
        preview.setAccessible(true);
        preview.invoke(null, state, region, neighbor, 1, 0, false, true);
        SimpleAssert
            .eq(1, field(type, "budgetLimited").getInt(region), "explicit budget placeholder remains reachable");
    }

    @SuppressWarnings("unchecked")
    private static void deniedAdmissionsHonorDeadline() throws IOException, ReflectiveOperationException {
        AuthorEditor editor = editor(600, true);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current(), 32L << 20)) {
            HologramState before = projection.view();
            Map<Long, Long> deferred = (Map<Long, Long>) field(HologramAuthorProjection.class, "budgetDeferred")
                .get(projection);
            for (HologramState.Cell cell : before.cells) {
                deferred.put(cell.nativeModelId, LIMIT + 1);
                projection.request(before, cell);
            }
            SimpleAssert.eq(512, queued(projection).size(), "renewal still uses existing bounded queue");
            final int[] reads = { 0 };
            projection.advance(() -> reads[0]++ == 0 ? 0 : 2_000_000L);
            SimpleAssert.eq(
                511,
                queued(projection).size(),
                "failed admission consumes deadline work, not an unbounded zero-work loop");
            SimpleAssert.that(
                projection.view() == before,
                "known cost rejected before malformed payload could decode into fallback");
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> queued(HologramAuthorProjection projection) throws ReflectiveOperationException {
        return (Set<Long>) field(HologramAuthorProjection.class, "queued").get(projection);
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static AuthorEditor editor(int count, boolean malformed) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("marker", 7);
        String encoded = Base64.getEncoder()
            .encodeToString(HologramSnapshotCodec.compress(tag));
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        for (int i = 0; i < count; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", malformed ? i * 16 : i == 0 ? 15 : i == 1 ? 16 : 0);
            cell.addProperty("y", !malformed && i == 1 ? 1 : 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            if (malformed || i == 1) cell.addProperty("tileNbt", malformed ? "not-base64!" : encoded);
            cells.add(cell);
        }
        root.add("cells", cells);
        return new AuthorEditor(AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults()), 4);
    }
}
