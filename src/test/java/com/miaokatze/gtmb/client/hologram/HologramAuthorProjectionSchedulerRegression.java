package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Deterministic scheduler/ownership contracts, not native callback timing or a game performance claim. */
public final class HologramAuthorProjectionSchedulerRegression {

    private HologramAuthorProjectionSchedulerRegression() {}

    public static void run() {
        try {
            cheapBatchUsesAvailableTime();
            successfulBatchKeepsResidentAccounting();
            deadlineYieldsWithoutDroppingDemand();
            queueRemainsBoundedAndDeduplicated();
            deferredRetriesStayBounded();
            editsRetireDemandAndOldViewsUseCurrentSource();
            ownerAndCloseRemainEnforced();
        } catch (IOException | ReflectiveOperationException | InterruptedException failure) {
            throw new AssertionError("author native model scheduling", failure);
        }
    }

    private static void successfulBatchKeepsResidentAccounting() throws IOException, ReflectiveOperationException {
        NBTTagCompound nativeData = new NBTTagCompound();
        nativeData.setString("marker", "prepared");
        String payload = java.util.Base64.getEncoder()
            .encodeToString(HologramSnapshotCodec.compress(nativeData));
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(13, payload))) {
            request(projection, 12);
            projection.advance(() -> 0);
            SimpleAssert.eq(12, resolved(projection.view()), "successful preparations also exceed four per tick");
            SimpleAssert
                .eq(12, ((java.util.Map<?, ?>) field("loaded").get(projection)).size(), "all successes are charged");
            long charge = HologramAuthorProjection.residentCharge(ownedRow(projection.view().cells.get(0)));
            SimpleAssert
                .eq(12 * charge, field("resident").getLong(projection), "resident accounting is exact per batch");
            for (int i = 0; i < 12; i++) SimpleAssert.eq(
                nativeData,
                projection.view().cells.get(i).targetDisplay.getCompoundTag("nativeTile"),
                "successful native payload is retained, not replaced with a fallback");
        }
    }

    private static void cheapBatchUsesAvailableTime() throws IOException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(13))) {
            HologramState before = projection.view();
            request(projection, 12);
            SimpleAssert.eq(true, projection.advance(() -> 0), "cheap demand publishes in one bounded batch");
            HologramState after = projection.view();
            SimpleAssert.eq(12, resolved(after), "available budget is not throttled to four models per UI tick");
            SimpleAssert.that(after.follows(before), "batch has one direct-provenance publication");
            SimpleAssert.that(after.cells.get(12) == before.cells.get(12), "unrequested cell remains untouched");
            SimpleAssert.eq(false, projection.advance(() -> 0), "drained demand does not publish or spin");
        }
    }

    private static void deadlineYieldsWithoutDroppingDemand() throws IOException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(6))) {
            request(projection, 6);
            LongSupplier clock = new LongSupplier() {

                private long now;

                @Override
                public long getAsLong() {
                    long result = now;
                    now += 1_000_000L;
                    return result;
                }
            };
            projection.advance(clock);
            SimpleAssert.eq(2, resolved(projection.view()), "no new payload starts at the two-millisecond deadline");
            projection.advance(() -> 0);
            SimpleAssert.eq(6, resolved(projection.view()), "yielded demand remains available for the next tick");
        }
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(2))) {
            request(projection, 2);
            final long[] reads = { 0 };
            projection.advance(() -> reads[0]++ == 0 ? 0 : 2_000_000L);
            SimpleAssert.eq(1, resolved(projection.view()), "one indivisible payload still makes forward progress");
        }
    }

    private static void queueRemainsBoundedAndDeduplicated() throws IOException, ReflectiveOperationException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(600))) {
            request(projection, 600);
            request(projection, 600);
            SimpleAssert.eq(512, ((Set<?>) field("queued").get(projection)).size(), "existing demand cap is unchanged");
            projection.advance(() -> 0);
            SimpleAssert.eq(512, resolved(projection.view()), "a stalled clock still drains only the bounded queue");
            request(projection, 600);
            SimpleAssert.eq(88, ((Set<?>) field("queued").get(projection)).size(), "terminal cells are not redecoded");
            projection.advance(() -> 0);
            SimpleAssert.eq(600, resolved(projection.view()), "later visible demand can enter the freed queue");
        }
    }

    @SuppressWarnings("unchecked")
    private static void deferredRetriesStayBounded() throws IOException, ReflectiveOperationException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(9))) {
            LinkedHashMap<Long, Long> waiting = (LinkedHashMap<Long, Long>) field("budgetDeferred").get(projection);
            for (int i = 0; i < 9; i++) waiting.put(projection.cellId(i), 32L * 1024 * 1024 + 1);
            SimpleAssert.eq(false, projection.advance(() -> 0), "oversized demands do not decode or publish again");
            SimpleAssert.eq(9, waiting.size(), "resumable demands are retained");
            SimpleAssert.eq(
                projection.cellId(8),
                waiting.keySet()
                    .iterator()
                    .next()
                    .longValue(),
                "only eight deferred entries rotate even when the clock does not advance");
        }
    }

    private static void editsRetireDemandAndOldViewsUseCurrentSource() throws IOException {
        AuthorEditor editor = new AuthorEditor(document(2), 16);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current())) {
            HologramState before = projection.view();
            request(projection, 2);
            editor.select(Collections.singleton(projection.cellId(0)));
            projection.update(editor.move(1, 0, 0));
            projection.advance(() -> 0);
            SimpleAssert.eq(
                true,
                projection.view().cells.get(0)
                    .modelPending(false),
                "edit retires old queued demand");
            SimpleAssert.eq(
                false,
                projection.view().cells.get(1)
                    .modelPending(false),
                "unrelated demand survives edit");
            projection.request(before, before.cells.get(0));
            projection.advance(() -> 0);
            SimpleAssert.eq(1, projection.view().cells.get(0).x, "old view demand prepares current source coordinates");
            SimpleAssert.eq(
                false,
                projection.view().cells.get(0)
                    .modelPending(false),
                "current revision is resolved");
        }
    }

    private static void ownerAndCloseRemainEnforced() throws IOException, InterruptedException {
        HologramAuthorProjection projection = new HologramAuthorProjection(document(1));
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                projection.advance(() -> 0);
            } catch (Throwable failure) {
                result.set(failure);
            }
        });
        try {
            other.start();
            other.join();
            SimpleAssert.that(result.get() instanceof IllegalStateException, "scheduler remains owner-thread only");
        } finally {
            projection.close();
        }
        boolean refused = false;
        try {
            projection.advance(() -> 0);
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.eq(true, refused, "closed projections cannot resume queued work");
    }

    private static NBTTagCompound ownedRow(HologramState.Cell cell) throws ReflectiveOperationException {
        Field raw = HologramState.Cell.class.getDeclaredField("raw");
        raw.setAccessible(true);
        return (NBTTagCompound) raw.get(cell);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field result = HologramAuthorProjection.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static int resolved(HologramState state) {
        int result = 0;
        for (HologramState.Cell cell : state.cells) if (!cell.modelPending(false)) result++;
        return result;
    }

    private static void request(HologramAuthorProjection projection, int count) {
        HologramState state = projection.view();
        for (int i = 0; i < count; i++) projection.request(state, state.cells.get(i));
    }

    private static AuthorDocument document(int count) throws IOException {
        return document(count, "not-base64!");
    }

    private static AuthorDocument document(int count, String payload) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        for (int i = 0; i < count; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", i * 16);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            cell.addProperty("tileNbt", payload);
            cells.add(cell);
        }
        root.add("cells", cells);
        return AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults());
    }
}
