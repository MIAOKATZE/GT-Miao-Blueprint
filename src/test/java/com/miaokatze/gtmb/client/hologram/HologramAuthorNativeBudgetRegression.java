package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.Collections;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.hologram.HologramProcessGovernor;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Real decoded-row admission and bounded retry contracts; does not claim GL or gameplay performance. */
public final class HologramAuthorNativeBudgetRegression {

    private static final long MIB = 1L << 20;

    private HologramAuthorNativeBudgetRegression() {}

    /** Exact Core-headroom assertions run in an isolated JVM, away from other suites' weak retired owners. */
    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorNativeBudgetRegression.class, args)) return;
        canonicalPayloadAndCumulativeTransientAdmission();
        run();
        System.out.println("HologramAuthorNativeBudgetRegression PASS");
    }

    public static void run() {
        policy();
        try {
            stationaryPressureStopsScanningAndDeletionResumes();
            capturePayloadUsesCanonicalBoundsAndKeepsAggregate();
        } catch (Exception failure) {
            throw new AssertionError("author native budget", failure);
        }
    }

    private static void policy() {
        SimpleAssert.eq(
            ((HologramSnapshotCodec.BLUEPRINT_NBT_MAX_COMPRESSED + 2) / 3) * 4,
            AuthorDocumentIO.Limits.defaults().maxStringChars,
            "pure editor format envelope matches canonical native payload limit without a Minecraft dependency");
        SimpleAssert.eq(160L * MIB, HologramAuthorNativeBudget.bytes(5L << 30, null), "5 GiB auto allocation");
        SimpleAssert.eq(64L * MIB, HologramAuthorNativeBudget.bytes(2L << 30, "0"), "smaller heap scales down");
        SimpleAssert.eq(256L * MIB, HologramAuthorNativeBudget.bytes(32L << 30, null), "auto remains bounded");
        SimpleAssert.eq(320L * MIB, HologramAuthorNativeBudget.bytes(5L << 30, "320"), "explicit client tuning");
        SimpleAssert.eq(512L * MIB, HologramAuthorNativeBudget.bytes(5L << 30, "9223372036854775807"), "no multiplication overflow");
        SimpleAssert.eq(128L * MIB, HologramAuthorNativeBudget.bytes(1L << 30, "512"), "heap safety cap wins");
        SimpleAssert.eq(8L * MIB, HologramAuthorNativeBudget.bytes(5L << 30, "1"), "explicit minimum");
        SimpleAssert.eq(160L * MIB, HologramAuthorNativeBudget.bytes(5L << 30, "invalid"), "invalid is automatic");
        SimpleAssert.eq(160L * MIB, HologramAuthorNativeBudget.bytes(5L << 30, "-1"), "negative is automatic");
        SimpleAssert.eq(1L, HologramAuthorNativeBudget.bytes(1L, "512"), "tiny JVM cap has precedence");
    }

    private static void stationaryPressureStopsScanningAndDeletionResumes() throws Exception {
        AuthorDocument document = document(65);
        long capacity = 0;
        try (HologramAuthorProjection measured = new HologramAuthorProjection(document, 32L * MIB)) {
            requestAll(measured);
            measured.advance(() -> 0);
            Field raw = HologramState.Cell.class.getDeclaredField("raw");
            raw.setAccessible(true);
            for (int i = 0; i < 64; i++) {
                HologramState.Cell cell = measured.view().cells.get(i);
                SimpleAssert.eq("", cell.sourceWarning, "actual valid row decoded");
                capacity += HologramAuthorProjection.residentCharge((NBTTagCompound) raw.get(cell));
            }
        }
        AuthorEditor editor = new AuthorEditor(document, 4);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document, capacity)) {
            HologramState initial = projection.view();
            for (int i = 0; i < 64; i++) projection.request(initial, initial.cells.get(i));
            projection.advance(() -> 0);
            SimpleAssert.eq(capacity, diagnostics(projection).getLong("nativeResidentBytes"), "exact row ledger fills quota");
            for (int i = 0; i < 8; i++) {
                requestAll(projection);
                projection.advance(() -> 0);
            }
            HologramState blocked = projection.view();
            SimpleAssert.eq("localBudget", blocked.cells.get(64).renderKind, "over-cap demand is explicit budget state");
            long checks = diagnostics(projection).getLong("nativeBudgetAdmissionChecks");
            SimpleAssert.that(checks >= 64, "bounded eviction traversal reaches beyond first 32 residents");
            for (int i = 0; i < 100; i++) {
                requestAll(projection);
                SimpleAssert.eq(false, projection.advance(() -> 0), "fixed pressure does not republish");
            }
            SimpleAssert.that(projection.view() == blocked, "no phantom readiness publication");
            SimpleAssert.eq(checks, diagnostics(projection).getLong("nativeBudgetAdmissionChecks"), "no repeated eviction scans after positive pin proof");
            SimpleAssert.eq(0, diagnostics(projection).getInteger("nativeQueued"), "unchanged impossible demand stays out of queue");
            long waiting = projection.cellId(64);
            editor.select(Collections.singleton(projection.cellId(0)));
            projection.update(editor.delete());
            // Deleting slot zero moves the old tail; source retirement must also retire its old demand safely.
            requestAll(projection);
            projection.advance(() -> 0);
            HologramState.Cell recovered = projection.view().cells.get(projection.index(waiting));
            SimpleAssert.eq("", recovered.renderKind, "capacity release resumes real native row");
            SimpleAssert.eq(false, recovered.modelPending(false), "resumed demand completes");
            SimpleAssert.that(diagnostics(projection).getLong("nativeResidentBytes") <= capacity, "resume never overcommits");
        }
    }

    private static NBTTagCompound diagnostics(HologramAuthorProjection projection) {
        NBTTagCompound result = new NBTTagCompound();
        projection.budgetDiagnostics(result);
        return result;
    }

    private static void capturePayloadUsesCanonicalBoundsAndKeepsAggregate() throws Exception {
        byte[] data = new byte[5 * 1024 * 1024];
        new java.util.Random(8).nextBytes(data);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setByteArray("data", data);
        String encoded = Base64.getEncoder().encodeToString(HologramSnapshotCodec.compress(payload));
        SimpleAssert.that(encoded.length() > ((1024 * 1024 + 2) / 3) * 4, "capture fixture exceeds former compressed cap");
        HologramBlueprintPreview.Payloads cache = new HologramBlueprintPreview.Payloads();
        NBTTagCompound first = cache.decode(encoded);
        SimpleAssert.eq(payload, first, "capture accepts canonical payload beyond former expanded cap");
        for (int i = 1; i < 12; i++) SimpleAssert.that(first == cache.decode(encoded), "decoded blob is still deduplicated");
        boolean aggregateRejected = false;
        try {
            cache.decode(encoded);
        } catch (java.io.IOException expected) {
            aggregateRejected = expected.getMessage().contains("total NBT");
        }
        SimpleAssert.eq(true, aggregateRejected, "repeated uses still count toward unchanged64MiB aggregate");
        NBTTagCompound oversized = new NBTTagCompound();
        oversized.setByteArray("data", new byte[HologramSnapshotCodec.BLUEPRINT_NBT_MAX_EXPANDED + 1]);
        boolean rejected = false;
        try {
            new HologramBlueprintPreview.Payloads().decode(Base64.getEncoder().encodeToString(HologramSnapshotCodec.compress(oversized)));
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        SimpleAssert.eq(true, rejected, "capture still rejects canonical expanded overflow");
    }

    private static void canonicalPayloadAndCumulativeTransientAdmission() throws Exception {
        byte[] bytes = new byte[512 * 1024];
        new java.util.Random(27).nextBytes(bytes);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setByteArray("data", bytes);
        byte[] compressed = HologramSnapshotCodec.compress(payload);
        SimpleAssert.that(compressed.length > 500 * 1024, "fixture includes a real large incompressible payload");
        long[] estimate = { 0 };
        try {
            HologramSnapshotCodec.decodeBounded(compressed, HologramSnapshotCodec.BLUEPRINT_NBT_MAX_EXPANDED,
                HologramSnapshotCodec.BLUEPRINT_NBT_MAX_TRACKED_BYTES, charge -> {
                    estimate[0] = charge;
                    return false;
                });
            throw new AssertionError("pre-allocation refusal must stop native decode");
        } catch (HologramSnapshotCodec.BusyException expected) {
            SimpleAssert.eq(estimate[0], expected.requestedBytes, "refusal reports measured working requirement");
        }
        SimpleAssert.that(estimate[0] > bytes.length, "working charge includes tree ownership, not just array bytes");
        String encoded = Base64.getEncoder().encodeToString(compressed);
        JsonObject cell = new JsonObject();
        cell.addProperty("x", 0);
        cell.addProperty("y", 0);
        cell.addProperty("z", 0);
        cell.addProperty("block", "minecraft:stone");
        cell.addProperty("item", "minecraft:stone");
        cell.addProperty("tileNbt", encoded);
        cell.addProperty("itemNbt", encoded);
        JsonArray cells = new JsonArray();
        cells.add(cell);
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        source.add("cells", cells);
        AuthorDocument document = AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
        long baseline = HologramProcessGovernor.retainedBytes();
        long pressure = HologramProcessGovernor.retainedByteLimit() - baseline - estimate[0] * 3 / 2;
        SimpleAssert.that(pressure > 0, "test leaves capacity for one input tree but not both");
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document, 32L * MIB)) {
            try (HologramProcessGovernor.Lease held = HologramProcessGovernor.retain("test.nativeDecodePressure", pressure)) {
                requestAll(projection);
                projection.advance(() -> 0);
                HologramState blocked = projection.view();
                SimpleAssert.eq("localBudget", blocked.cells.get(0).renderKind, "second input Core denial is resumable, not malformed");
                SimpleAssert.eq(0L, diagnostics(projection).getLong("nativeResidentBytes"), "transient requirement is not owned-row charge");
                SimpleAssert.eq(1, diagnostics(projection).getInteger("nativeDecodeBudgetDeferred"), "transient denial has separate provenance");
                long passes = diagnostics(projection).getLong("nativeDecodePasses");
                SimpleAssert.eq(2L, passes, "first attempt measures tile and item cumulatively");
                for (int i = 0; i < 12; i++) {
                    requestAll(projection);
                    SimpleAssert.eq(false, projection.advance(() -> 0), "unchanged Core pressure has no publication");
                }
                SimpleAssert.that(projection.view() == blocked, "Core pressure preserves exact blocked view");
                SimpleAssert.eq(passes, diagnostics(projection).getLong("nativeDecodePasses"), "known transient denial precedes decompression");
                SimpleAssert.eq(baseline + pressure, HologramProcessGovernor.retainedBytes(), "first tree reservation released after second input denial");
            }
            requestAll(projection);
            SimpleAssert.eq(true, projection.advance(() -> 0), "free Core capacity resumes existing demand");
            HologramState.Cell ready = projection.view().cells.get(0);
            SimpleAssert.eq("", ready.renderKind, "canonical payload over former256KiB limit is supported");
            SimpleAssert.eq(payload, ready.targetDisplay.getCompoundTag("nativeTile"), "large tile payload preserved");
            SimpleAssert.eq(payload, ready.targetStack.getTagCompound(), "large independent item payload preserved");
            SimpleAssert.eq(0, diagnostics(projection).getInteger("nativeDecodeBudgetDeferred"), "success retires transient wait");
            SimpleAssert.eq(baseline, HologramProcessGovernor.retainedBytes(), "both transient leases released after owned-row construction");
        }
    }

    private static void requestAll(HologramAuthorProjection projection) {
        HologramState state = projection.view();
        for (HologramState.Cell cell : state.cells) projection.request(state, cell);
    }

    private static AuthorDocument document(int count) throws Exception {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("marker", "native-budget-regression");
        payload.setByteArray("data", new byte[1024]);
        String encoded = Base64.getEncoder().encodeToString(HologramSnapshotCodec.compress(payload));
        JsonArray cells = new JsonArray();
        for (int i = 0; i < count; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", i * 16);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            cell.addProperty("tileNbt", encoded);
            cells.add(cell);
        }
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        source.add("cells", cells);
        return AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
    }
}
