package com.miaokatze.gtmb.hologram;

import static com.miaokatze.gtmb.hologram.HologramSnapshotAeTaskRegression.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** No decoded trees are cached. Quantitative checks run only in the independently instrumented JVM. */
public final class HologramEmptyPayloadRegression {

    private HologramEmptyPayloadRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramEmptyPayloadRegression.class, args)) return;
        HologramDecodeProbe.install(HologramEmptyPayloadRegression.class);
        run();
        strictCounts();
        System.out.println("HologramEmptyPayloadRegression P0b PASS (actual strict codec entry/success counts)");
    }

    public static void run() {
        try {
            factsAndActiveBoundary();
            rowsAndReports();
        } catch (Exception failure) {
            throw new AssertionError("task-local successful empty payload facts", failure);
        }
    }

    private static String variant(String encoded) {
        byte[] gzip = Base64.getDecoder()
            .decode(encoded);
        gzip[4] ^= 1; // GZIP MTIME changes the complete string, never the root NBT or its checksum.
        return Base64.getEncoder()
            .encodeToString(gzip);
    }

    private static HologramBlueprintValidationCache cache() {
        return new HologramBlueprintValidationCache(new HologramPresets.Preset());
    }

    private static void factsAndActiveBoundary() throws Exception {
        String e = encoded(new NBTTagCompound()), other = variant(e);
        SimpleAssert.that(!e.equals(other), "two complete valid encodings are distinct strings");
        SimpleAssert.eq(HologramBlueprintNbt.decode(e), HologramBlueprintNbt.decode(other), "same empty NBT root");
        HologramBlueprintValidationCache cache = cache();
        NBTTagCompound first = cache.decodePayload(e);
        first.setLong("callerMutation", Long.MAX_VALUE);
        NBTTagCompound second = cache.decodePayload(new String(e));
        second.setTag("callerList", new NBTTagList());
        SimpleAssert.that(
            cache.decodePayload(e)
                .hasNoTags(),
            "each hit returns a fresh empty tree, never caller data");
        SimpleAssert.eq(1, cache.entries(), "only the successful complete-string fact is admitted");
        SimpleAssert.eq(e.length(), cache.stringChars(), "the original complete string consumes shared characters");
        SimpleAssert.that(cache.decodePayload(null) == null && cache.decodePayload("") == null, "absence stays null");
        SimpleAssert.eq(1, cache.entries(), "absent strings do not acquire empty-root facts");
        cache.decodePayload(other);
        SimpleAssert.eq(2, cache.entries(), "a distinct valid encoding has its own first strict admission");
        SimpleAssert
            .eq(e.length() + other.length(), cache.stringChars(), "different complete strings count separately");
        String full = encoded(payload());
        for (int i = 0; i < 2; i++) {
            NBTTagCompound decoded = cache.decodePayload(full);
            SimpleAssert.eq(payload(), decoded, "nonempty unknown/list/long fields remain exact");
            decoded.setLong("unknownTyped", 0);
        }
        for (String invalid : new String[] { "%%%invalid%%%", badGzip() }) {
            String expected = rejected(() -> HologramBlueprintNbt.decode(invalid));
            for (int i = 0; i < 2; i++) SimpleAssert
                .eq(expected, rejected(() -> cache.decodePayload(invalid)), "same original exception chain");
        }
        SimpleAssert.eq(2, cache.entries(), "nonempty and failed decodes do not enter the fact cache");
        HologramSnapshotDataContext unadmitted = new HologramSnapshotDataContext();
        HologramSnapshotDataContext.enter(unadmitted);
        try {
            String failure = rejected(() -> cache.decodePayload(e));
            SimpleAssert.that(
                failure.contains("Snapshot NBT identity was not frozen"),
                "known owner fact cannot bypass ACTIVE admission");
        } finally {
            HologramSnapshotDataContext.exit();
        }
        HologramSnapshotDataContext admitted = new HologramSnapshotDataContext();
        admitted.freeze(e);
        HologramSnapshotDataContext.enter(admitted);
        try {
            cache.decodePayload(e)
                .setInteger("workerMutation", 1);
            SimpleAssert.that(
                cache.decodePayload(e)
                    .hasNoTags(),
                "ACTIVE still returns its own isolated decoded copy");
        } finally {
            HologramSnapshotDataContext.exit();
        }
    }

    private static HologramSession rowSession(String e) throws Exception {
        HologramSession s = session();
        s.virtual = true;
        s.virtualPreset.schema = 2;
        for (int i = 0; i < 6; i++) {
            HologramCapture.Cell cell = add(s, Blocks.stone, new NBTTagCompound(), i, 1, -i);
            cell.tileNbt = i == 2 ? variant(e) : i == 3 ? encoded(payload()) : i == 4 ? badGzip() : e;
            cell.blueprintBlock = "minecraft:stone";
        }
        return s;
    }

    private static void rowsAndReports() throws Exception {
        String e = encoded(new NBTTagCompound());
        HologramSession s = rowSession(e);
        HologramSnapshots.CellRows shared = new HologramSnapshots.CellRows(s);
        for (int repeat = 0; repeat < 2; repeat++) for (int i = 0; i < s.capture.cells.size(); i++) {
            NBTTagCompound reference = HologramSnapshots.cellRow(s, i, true);
            NBTTagCompound actual = HologramSnapshots.cellRowData(s, i, true, shared).row;
            SimpleAssert.eq(
                reference,
                actual,
                "entire row equals independent per-row context, including malformed errors and display NBT");
        }
        HologramSession ae = session();
        Block host = host("appeng.block.networking.BlockCableBus");
        for (int i = 0; i < 5; i++) add(ae, host, new NBTTagCompound(), i, -i, 2);
        add(ae, host, payload(), -3, 4, 5);
        NBTTagCompound expected = new NBTTagCompound();
        expected.setLong("unknownReportLong", Long.MIN_VALUE);
        expected.setTag("aeIssues", HologramAuthoringQueries.aeCurrentIssues(ae));
        expected.setBoolean("aePreviewIncomplete", true);
        for (int batch : new int[] { 1, 3, 100 }) {
            NBTTagCompound actual = new NBTTagCompound();
            actual.setLong("unknownReportLong", Long.MIN_VALUE);
            complete(new HologramSnapshotAeTask(ae, actual), batch);
            SimpleAssert
                .eq(expected, actual, "old full AE report oracle preserves every DTO field, order and report flag");
        }
        HologramSnapshotAeTaskRegression.run(); // Includes historical resolution, error precedence and Work isolation.
    }

    private static void strictCounts() throws Exception {
        String e = encoded(new NBTTagCompound()), e2 = variant(e);
        Block ae = host("appeng.block.networking.BlockCableBus");
        HologramCapture.Cell cell = cell(ae, e, 0, 0, 0);
        int k = 4;
        measured("AE baseline", k * 2, k * 2, () -> { for (int i = 0; i < k; i++) legacy(cell); });
        HologramSession noPreset = session();
        noPreset.virtualPreset = null;
        HologramSnapshotAeTask p0a = new HologramSnapshotAeTask(noPreset, new NBTTagCompound());
        measured("AE P0a/no-preset original decode", k, k, () -> { for (int i = 0; i < k; i++) analyze(p0a, cell); });
        HologramSnapshotAeTask task = new HologramSnapshotAeTask(session(), new NBTTagCompound());
        measured("AE P0a+b", 1, 1, () -> { for (int i = 0; i < k; i++) analyze(task, cell); });
        cell.tileNbt = e2;
        measured("AE E-prime first admission", 1, 1, () -> { for (int i = 0; i < k; i++) analyze(task, cell); });
        HologramSnapshotAeTask a = new HologramSnapshotAeTask(session(), new NBTTagCompound());
        HologramSnapshotAeTask b = new HologramSnapshotAeTask(session(), new NBTTagCompound());
        measured("interleaved independent owner tasks", 2, 2, () -> {
            analyze(a, cell);
            analyze(b, cell);
            analyze(a, cell);
            analyze(b, cell);
        });
        HologramSnapshotAeTask ordinary = new HologramSnapshotAeTask(session(), new NBTTagCompound());
        cell.block = Blocks.stone;
        measured(
            "unhandled host strict first decode",
            1,
            1,
            () -> { for (int i = 0; i < k; i++) analyze(ordinary, cell); });
        HologramBlueprintValidationCache cache = cache();
        measured("E and distinct E-prime", 2, 2, () -> {
            cache.decodePayload(e);
            cache.decodePayload(new String(e));
            cache.decodePayload(e2);
            cache.decodePayload(e2);
        });
        String full = encoded(payload());
        measured(
            "nonempty always strictly decoded",
            3,
            3,
            () -> { for (int i = 0; i < 3; i++) cache.decodePayload(full); });
        measured(
            "bad GZIP never cached",
            3,
            0,
            () -> { for (int i = 0; i < 3; i++) rejected(() -> cache.decodePayload(badGzip())); });
        measured(
            "bad base64 before strict codec",
            0,
            0,
            () -> { for (int i = 0; i < 3; i++) rejected(() -> cache.decodePayload("%%%invalid%%%")); });
        measured("null and empty text are not strict decodes", 0, 0, () -> {
            cache.decodePayload(null);
            cache.decodePayload("");
        });
        budgets(e, e2);
        HologramSession rows = rowSession(e);
        // Each row already shares its own decoded source with preview(); only cross-row repetition changes.
        for (HologramCapture.Cell row : rows.capture.cells) row.tileNbt = e;
        measured(
            "rows per-row contexts baseline/P0a",
            6,
            6,
            () -> { for (int i = 0; i < 6; i++) HologramSnapshots.cellRow(rows, i, true); });
        HologramSnapshots.CellRows shared = new HologramSnapshots.CellRows(rows);
        measured(
            "rows shared owner context P0b",
            1,
            1,
            () -> { for (int i = 0; i < 6; i++) HologramSnapshots.cellRowData(rows, i, true, shared); });
    }

    private static void budgets(String e, String e2) throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        String item = "minecraft:stone";
        HologramBlueprintValidationCache entries = new HologramBlueprintValidationCache(preset, 1, 10000);
        entries.stack(item, 0);
        measured("item exhausts shared entries", 2, 2, () -> {
            entries.decodePayload(e);
            entries.decodePayload(e);
        });
        SimpleAssert.eq(1, entries.entries(), "empty fact obeys existing item entry saturation");
        HologramBlueprintValidationCache chars = new HologramBlueprintValidationCache(
            preset,
            10,
            item.length() + e.length() - 1);
        chars.stack(item, 0);
        measured("item exhausts shared characters", 2, 2, () -> {
            chars.decodePayload(e);
            chars.decodePayload(e);
        });
        SimpleAssert.eq(item.length(), chars.stringChars(), "rejected facts add no characters");
        HologramBlueprintValidationCache emptyFirst = new HologramBlueprintValidationCache(preset, 1, e.length());
        emptyFirst.decodePayload(e);
        emptyFirst.stack(item, 0);
        emptyFirst.stack(item, 0);
        SimpleAssert
            .eq(2L, emptyFirst.registryMisses, "empty fact consumes the same entry/char budget as item identities");
        measured("admitted hit survives saturation", 0, 0, () -> emptyFirst.decodePayload(e));
        measured("different string cannot bypass saturation", 2, 2, () -> {
            emptyFirst.decodePayload(e2);
            emptyFirst.decodePayload(e2);
        });
        HologramPresets.Cell filtered = new HologramPresets.Cell();
        filtered.block = item;
        filtered.tileNbt = encoded(payload());
        HologramBlueprintValidationCache filterFirst = new HologramBlueprintValidationCache(preset, 2, 10000);
        filterFirst.filter(filtered);
        SimpleAssert.eq(2, filterFirst.entries(), "fixture fills block/filter entries in the existing shared budget");
        measured("filter exhausts shared entries", 2, 2, () -> {
            filterFirst.decodePayload(e);
            filterFirst.decodePayload(e);
        });
        int filterChars = filterFirst.stringChars();
        HologramBlueprintValidationCache filterCharLimit = new HologramBlueprintValidationCache(
            preset,
            10,
            filterChars + e.length() - 1);
        filtered.tileNbt = encoded(payload());
        filterCharLimit.filter(filtered);
        SimpleAssert.eq(
            filterChars,
            filterCharLimit.stringChars(),
            "block/filter character charges match the original admission");
        measured("filter exhausts shared characters", 2, 2, () -> {
            filterCharLimit.decodePayload(e);
            filterCharLimit.decodePayload(e);
        });
        SimpleAssert.eq(
            filterChars,
            filterCharLimit.stringChars(),
            "rejected empty facts cannot exceed filter character budget");
        filtered.tileNbt = encoded(payload());
        emptyFirst.filter(filtered);
        filtered.tileNbt = encoded(payload());
        emptyFirst.filter(filtered);
        SimpleAssert.eq(2L, emptyFirst.filterMisses, "empty facts cannot create a separate filter budget");
        SimpleAssert.eq(1, emptyFirst.entries(), "fallback leaves original bounded accounting unchanged");
        SimpleAssert.eq(
            e.length(),
            emptyFirst.stringChars(),
            "all rejected item/filter/fact admissions leave bounded characters");
    }

    private static String badGzip() {
        return Base64.getEncoder()
            .encodeToString(new byte[] { 1, 2, 3 });
    }

    private interface Io {

        void run() throws Exception;
    }

    private static String rejected(Io action) throws Exception {
        try {
            action.run();
        } catch (IOException failure) {
            return error(failure);
        }
        throw new AssertionError("expected original IOException");
    }

    private static void measured(String label, int entries, int successes, Io action) throws Exception {
        HologramDecodeProbe.begin();
        int[] observed;
        try {
            action.run();
        } finally {
            observed = HologramDecodeProbe.end();
        }
        SimpleAssert.that(
            observed[2] == entries && observed[3] == successes,
            label + " actual API/data/strict-entry/success=" + Arrays.toString(observed));
        System.out.println("GTMB_P0B " + label + " actual=" + Arrays.toString(observed));
    }
}
