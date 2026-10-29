package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Final-owned accounting and admission contracts; not native geometry, sample heap or GL acceptance. */
public final class HologramAuthorOwnershipBudgetRegression {

    private static final long LIMIT = 32L * 1024 * 1024;

    private HologramAuthorOwnershipBudgetRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorOwnershipBudgetRegression.class, args)) return;
        run();
        System.out.println("HologramAuthorOwnershipBudgetRegression PASS");
    }

    public static void run() {
        try {
            countsOwnedInstancesAndVariableFields();
            finalRowsAndEditsHaveExactLedger();
            knownCostPrecedesDecode();
            oversizeDoesNotEvictAndRetirementDebitsOnce();
            codecFailurePreservesSource();
            accountingEnvelopesPreserveCodecDepthBoundary();
        } catch (IOException | ReflectiveOperationException failure) {
            throw new AssertionError("author owned payload accounting", failure);
        }
    }

    private static void countsOwnedInstancesAndVariableFields() {
        NBTTagCompound row = ledgerRow(8, 8);
        long before = HologramAuthorProjection.residentCharge(row);
        row.getCompoundTag("targetDisplay")
            .getCompoundTag("nativeTile")
            .setByteArray("bytes", new byte[108]);
        SimpleAssert
            .eq(before + 100, HologramAuthorProjection.residentCharge(row), "one shared display tree, not four");
        before = HologramAuthorProjection.residentCharge(row);
        row.getCompoundTag("targetStack")
            .getCompoundTag("tag")
            .setByteArray("bytes", new byte[108]);
        SimpleAssert.eq(before + 300, HologramAuthorProjection.residentCharge(row), "raw and two mutable stack tags");
        before = HologramAuthorProjection.residentCharge(row);
        row.setTag(
            "actualDisplay",
            row.getCompoundTag("targetDisplay")
                .copy());
        SimpleAssert.that(
            HologramAuthorProjection.residentCharge(row) > before + 108,
            "content-equal but separately owned display is charged separately");
        row.setString("prompt", "a");
        before = HologramAuthorProjection.residentCharge(row);
        row.setString("prompt", "abcdef");
        SimpleAssert
            .eq(before + 10, HologramAuthorProjection.residentCharge(row), "root text is not hidden in fixed fee");
        NBTTagCompound extra = new NBTTagCompound();
        extra.setByteArray("future", new byte[2048]);
        before = HologramAuthorProjection.residentCharge(row);
        row.setTag("futureDisplayField", extra);
        SimpleAssert.that(
            HologramAuthorProjection.residentCharge(row) >= before + 2048,
            "future nested row fields are not omitted");
        NBTTagList members = new NBTTagList();
        members.appendTag(new NBTTagString("membership"));
        before = HologramAuthorProjection.residentCharge(row);
        row.setTag("structureIds", members);
        SimpleAssert.that(
            HologramAuthorProjection.residentCharge(row) > before + 128,
            "both membership tree and Cell membership-list ownership are included");
        NBTTagCompound display = row.getCompoundTag("targetDisplay");
        before = HologramAuthorProjection.residentCharge(row);
        display.setTag("gt.covers", extra.copy());
        SimpleAssert.that(
            HologramAuthorProjection.residentCharge(row) >= before + 2048,
            "display-added copies such as gt.covers are charged in addition to nativeTile");
    }

    private static void finalRowsAndEditsHaveExactLedger() throws IOException, ReflectiveOperationException {
        NBTTagCompound tile = new NBTTagCompound();
        tile.setByteArray("tile", new byte[64]);
        NBTTagList covers = new NBTTagList();
        NBTTagCompound cover = new NBTTagCompound();
        cover.setByteArray("unknown", new byte[128]);
        covers.appendTag(cover);
        tile.setTag("gt.covers", covers);
        NBTTagCompound item = new NBTTagCompound();
        item.setString("marker", "original");
        AuthorEditor editor = new AuthorEditor(document(2, payload(tile), payload(item)), 8);
        byte[] original = AuthorDocumentIO.writeDraft(editor.current(), AuthorDocumentIO.Limits.defaults());
        try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current())) {
            for (HologramState.Cell cell : projection.view().cells) projection.request(projection.view(), cell);
            projection.advance(() -> 0);
            long sum = 0;
            for (HologramState.Cell cell : projection.view().cells) {
                NBTTagCompound raw = (NBTTagCompound) field(HologramState.Cell.class, "raw").get(cell);
                SimpleAssert.eq("", cell.sourceWarning, "real completeCell fixture prepares successfully");
                SimpleAssert.that(raw.getTag("actualDisplay") == raw.getTag("targetDisplay"), "display alias retained");
                SimpleAssert
                    .that(raw.getTag("actualStack") == raw.getTag("targetStack"), "serialized stack alias retained");
                SimpleAssert.that(cell.actualDisplay == cell.targetDisplay, "Cell uses the same owned display");
                SimpleAssert.that(
                    cell.actualStack.getTagCompound() != cell.targetStack.getTagCompound(),
                    "mutable tags independent");
                cell.actualStack.getTagCompound()
                    .setString("marker", "changed");
                SimpleAssert.eq(
                    "original",
                    cell.targetStack.getTagCompound()
                        .getString("marker"),
                    "target isolated");
                SimpleAssert.eq(
                    "original",
                    raw.getCompoundTag("targetStack")
                        .getCompoundTag("tag")
                        .getString("marker"),
                    "raw isolated");
                sum += HologramAuthorProjection.residentCharge(raw);
            }
            SimpleAssert.eq(
                sum,
                field(HologramAuthorProjection.class, "resident").getLong(projection),
                "final rows exactly charged");
            SimpleAssert.that(
                java.util.Arrays.equals(
                    original,
                    AuthorDocumentIO.writeDraft(editor.current(), AuthorDocumentIO.Limits.defaults())),
                "source bytes remain unchanged");
            editor.select(Collections.singleton(projection.cellId(0)));
            projection.update(editor.move(0, 1, 0));
            long remaining = loaded(projection).values()
                .iterator()
                .next();
            SimpleAssert.eq(
                remaining,
                field(HologramAuthorProjection.class, "resident").getLong(projection),
                "edit releases old charge exactly once");
            SimpleAssert.eq(1, loaded(projection).size(), "unrelated prepared cell remains resident");
        }
    }

    @SuppressWarnings("unchecked")
    private static void knownCostPrecedesDecode() throws IOException, ReflectiveOperationException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(1, "not-base64!", ""))) {
            Map<Long, Long> deferred = (Map<Long, Long>) field(HologramAuthorProjection.class, "budgetDeferred")
                .get(projection);
            long id = projection.cellId(0);
            deferred.put(id, LIMIT + 1);
            HologramState before = projection.view();
            projection.request(before, before.cells.get(0));
            SimpleAssert.eq(false, projection.advance(() -> 0), "known oversize never reaches malformed decoder");
            SimpleAssert.that(projection.view() == before, "no fallback publication or warning from forbidden decode");
            SimpleAssert.eq(Long.valueOf(LIMIT + 1), deferred.get(id), "known cost stays resumable metadata");
        }
    }

    private static void oversizeDoesNotEvictAndRetirementDebitsOnce() throws IOException, ReflectiveOperationException {
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document(1, "", ""))) {
            loaded(projection).put(999999L, LIMIT / 2);
            loaded(projection).put(999998L, LIMIT / 2);
            Field resident = field(HologramAuthorProjection.class, "resident");
            resident.setLong(projection, LIMIT);
            Method room = HologramAuthorProjection.class.getDeclaredMethod("makeRoom", long.class, Map.class);
            room.setAccessible(true);
            SimpleAssert.eq(
                false,
                room.invoke(projection, LIMIT + 1, new LinkedHashMap<>()),
                "oversize rejected before eviction");
            SimpleAssert.eq(2, loaded(projection).size(), "oversize does not destroy useful residents");
            SimpleAssert.eq(
                true,
                room.invoke(projection, 1L, new LinkedHashMap<>()),
                "offscreen retired identity releases room");
            SimpleAssert.eq(LIMIT / 2, resident.getLong(projection), "eviction subtracts actual stored charge");
            SimpleAssert.eq(1, loaded(projection).size(), "only required victim removed");
            room.invoke(projection, 1L, new LinkedHashMap<>());
            SimpleAssert.eq(LIMIT / 2, resident.getLong(projection), "later admission cannot debit victim again");
        }
    }

    private static void codecFailurePreservesSource() throws IOException, ReflectiveOperationException {
        NBTTagCompound excessive = new NBTTagCompound();
        excessive.setByteArray("expanded", new byte[256 * 1024 + 1]);
        AuthorDocument document = document(1, payload(excessive), "");
        String encoded = document.cells()
            .iterator()
            .next()
            .tileNbt();
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            projection.request(projection.view(), projection.view().cells.get(0));
            projection.advance(() -> 0);
            SimpleAssert.eq(
                "localFallback",
                projection.view().cells.get(0).renderKind,
                "codec overlimit stays explicit fallback");
            SimpleAssert.eq(0, loaded(projection).size(), "failed decode never acquires resident entry");
            SimpleAssert.eq(
                0L,
                field(HologramAuthorProjection.class, "resident").getLong(projection),
                "failed decode never charges resident");
            SimpleAssert.eq(
                encoded,
                document.cells()
                    .iterator()
                    .next()
                    .tileNbt(),
                "oversized source NBT preserved");
        }
    }

    private static void accountingEnvelopesPreserveCodecDepthBoundary()
        throws IOException, ReflectiveOperationException {
        for (int depth : new int[] { 510, 511, 512, 513 }) {
            NBTTagCompound root = new NBTTagCompound(), cursor = root;
            for (int i = 0; i < depth; i++) {
                NBTTagCompound next = new NBTTagCompound();
                cursor.setTag("nested", next);
                cursor = next;
            }
            String encoded = payload(root);
            if (depth <= 512) SimpleAssert.eq(
                root,
                HologramSnapshotCodec.decodeBounded(
                    Base64.getDecoder()
                        .decode(encoded),
                    256 * 1024,
                    2L * 1024 * 1024),
                "source depth is accepted by the unchanged codec: " + depth);
            AuthorDocument source = document(1, encoded, encoded);
            try (HologramAuthorProjection projection = new HologramAuthorProjection(source)) {
                projection.request(projection.view(), projection.view().cells.get(0));
                projection.advance(() -> 0);
                HologramState.Cell cell = projection.view().cells.get(0);
                if (depth <= 512) {
                    SimpleAssert
                        .eq("", cell.renderKind, "local envelopes cannot reduce accepted source depth: " + depth);
                    SimpleAssert
                        .eq(root, cell.targetDisplay.getCompoundTag("nativeTile"), "deep tile survives publication");
                    SimpleAssert
                        .eq(root, cell.targetStack.getTagCompound(), "deep mutable item tag survives publication");
                    NBTTagCompound raw = (NBTTagCompound) field(HologramState.Cell.class, "raw").get(cell);
                    SimpleAssert.eq(
                        HologramAuthorProjection.residentCharge(raw),
                        field(HologramAuthorProjection.class, "resident").getLong(projection),
                        "deep row uses owned ledger");
                } else {
                    SimpleAssert.eq("localFallback", cell.renderKind, "untrusted depth 513 is still rejected");
                    SimpleAssert
                        .eq(0, loaded(projection).size(), "rejected depth is never charged or published as ready");
                }
                SimpleAssert.eq(
                    encoded,
                    source.cells()
                        .iterator()
                        .next()
                        .tileNbt(),
                    "depth handling preserves source");
            }
        }
    }

    private static NBTTagCompound ledgerRow(int tileBytes, int itemBytes) {
        NBTTagCompound row = new NBTTagCompound(), display = new NBTTagCompound(), tile = new NBTTagCompound();
        tile.setByteArray("bytes", new byte[tileBytes]);
        display.setTag("nativeTile", tile);
        row.setTag("actualDisplay", display);
        row.setTag("targetDisplay", display);
        NBTTagCompound stack = new NBTTagCompound(), item = new NBTTagCompound();
        item.setByteArray("bytes", new byte[itemBytes]);
        stack.setTag("tag", item);
        row.setTag("actualStack", stack);
        row.setTag("targetStack", stack);
        return row;
    }

    private static String payload(NBTTagCompound tag) throws IOException {
        return Base64.getEncoder()
            .encodeToString(HologramSnapshotCodec.compress(tag));
    }

    private static AuthorDocument document(int count, String tile, String item) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        for (int i = 0; i < count; i++) {
            JsonObject row = new JsonObject();
            row.addProperty("x", i * 16);
            row.addProperty("y", 0);
            row.addProperty("z", 0);
            row.addProperty("block", "minecraft:stone");
            row.addProperty("item", "minecraft:stone");
            row.addProperty("tileNbt", tile);
            row.addProperty("itemNbt", item);
            cells.add(row);
        }
        root.add("cells", cells);
        return AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults());
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Long> loaded(HologramAuthorProjection projection) throws ReflectiveOperationException {
        return (Map<Long, Long>) field(HologramAuthorProjection.class, "loaded").get(projection);
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
