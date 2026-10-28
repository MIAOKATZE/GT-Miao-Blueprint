package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Optional material-key dedup bounds; no native resolution, GL, or whole-heap claims. */
public final class HologramMaterialCacheRegression {

    private HologramMaterialCacheRegression() {}

    public static void run() {
        entryBoundAndAccessOrder();
        keyBytesReplacementOversizeAndClear();
        stringKeyEqualitySurvivesHashCollision();
        try {
            snapshotLineageAndOwnerCleanup();
        } catch (IOException | ReflectiveOperationException failure) {
            throw new AssertionError("material dedup ownership", failure);
        }
    }

    private static void entryBoundAndAccessOrder() {
        HologramTextures.MaterialCache cache = new HologramTextures.MaterialCache(2, 1024);
        HologramTextures.Material a = material(), b = material(), c = material();
        cache.put("a", a);
        cache.put("b", b);
        SimpleAssert.that(cache.get("a") == a, "lookup promotes least-recently-used entry");
        cache.put("c", c);
        SimpleAssert.eq(2, cache.size(), "entry count remains bounded");
        SimpleAssert.eq(null, cache.get("b"), "least-recently-used key is evicted");
        SimpleAssert.that(cache.get("a") == a && cache.get("c") == c, "recent exact values retained");
        SimpleAssert.eq(4L, cache.retainedKeyBytes(), "entry eviction releases key accounting");
    }

    private static void keyBytesReplacementOversizeAndClear() {
        HologramTextures.MaterialCache cache = new HologramTextures.MaterialCache(8, 12);
        HologramTextures.Material a = material(), replacement = material(), b = material();
        cache.put("aa", a);
        cache.put("bbb", b);
        SimpleAssert.eq(10L, cache.retainedKeyBytes(), "keys charged by two bytes per UTF-16 character");
        cache.put(new String("aa"), replacement);
        SimpleAssert.eq(2, cache.size(), "equal-key replacement does not add an entry");
        SimpleAssert.eq(10L, cache.retainedKeyBytes(), "replacement is not double-charged");
        cache.put("cccc", material());
        SimpleAssert.eq(null, cache.get("bbb"), "byte budget evicts stale key after replacement promotion");
        SimpleAssert.that(cache.get("aa") == replacement, "replacement preserves exact new material");
        SimpleAssert.eq(12L, cache.retainedKeyBytes(), "byte cap includes all remaining keys");
        cache.put("oversized-key", material());
        SimpleAssert.eq(null, cache.get("oversized-key"), "oversized key is not retained");
        SimpleAssert.eq(2, cache.size(), "oversized key does not flush useful cached entries");
        cache.clear();
        SimpleAssert.eq(0, cache.size(), "clear releases index entries");
        SimpleAssert.eq(0L, cache.retainedKeyBytes(), "clear releases key accounting");
        cache.put("\u0100", a);
        SimpleAssert.eq(2L, cache.retainedKeyBytes(), "non-ASCII key follows the same conservative charge");
    }

    private static void stringKeyEqualitySurvivesHashCollision() {
        HologramTextures.MaterialCache cache = new HologramTextures.MaterialCache(8, 1024);
        HologramTextures.Material a = material(), b = material();
        String first = "nativeTile:{payload:Aa}", second = "nativeTile:{payload:BB}";
        SimpleAssert.eq(first.hashCode(), second.hashCode(), "fixture has colliding string hashes");
        cache.put(first, a);
        cache.put(second, b);
        SimpleAssert.that(cache.get(first) == a && cache.get(second) == b, "distinct existing string keys never merge");
    }

    @SuppressWarnings("unchecked")
    private static void snapshotLineageAndOwnerCleanup() throws IOException, ReflectiveOperationException {
        Field installed = field("reloadInstalled");
        boolean oldInstalled = installed.getBoolean(null);
        // Test ownership without requiring Minecraft's resource manager or creating a GL context.
        installed.setBoolean(null, true);
        HologramTextures.clear();
        try {
            HologramTextures.MaterialCache cache = (HologramTextures.MaterialCache) field("CACHE").get(null);
            Map<HologramState.Cell, HologramTextures.Material[]> resolved = (Map<HologramState.Cell, HologramTextures.Material[]>) field(
                "RESOLVED").get(null);
            AuthorEditor editor = editor();
            try (HologramAuthorProjection projection = new HologramAuthorProjection(editor.current())) {
                HologramState before = projection.view();
                HologramTextures.begin(before);
                HologramTextures.Material active = material();
                cache.put("obsolete-variant", active);
                resolved.put(before.cells.get(0), new HologramTextures.Material[] { active, null });
                resolved.put(before.cells.get(1), new HologramTextures.Material[] { active, null });
                editor.select(Collections.singleton(projection.cellId(0)));
                HologramState after = projection.update(editor.move(1, 0, 0));
                HologramTextures.advance(before, after);
                SimpleAssert
                    .eq(false, resolved.containsKey(before.cells.get(0)), "changed cell releases resolved reference");
                SimpleAssert
                    .that(resolved.get(before.cells.get(1))[0] == active, "untouched cell keeps its required material");
                for (int i = 0; i < 3000; i++) cache.put("variant-" + i, material());
                SimpleAssert.eq(2048, cache.size(), "long revision lineage remains entry-bounded");
                SimpleAssert.eq(null, cache.get("obsolete-variant"), "old variant eventually leaves dedup index");
                SimpleAssert.that(
                    resolved.get(before.cells.get(1))[0] == active,
                    "dedup eviction does not invalidate live material");
                HologramTextures.begin(new HologramState(new net.minecraft.nbt.NBTTagCompound()));
                SimpleAssert.eq(0, cache.size(), "new snapshot owner clears dedup index");
                SimpleAssert.eq(0, resolved.size(), "new snapshot owner releases old per-cell references");
                SimpleAssert.eq(0L, cache.retainedKeyBytes(), "owner change resets byte accounting");
                cache.put("clear", active);
                HologramTextures.clear();
                SimpleAssert.eq(null, field("snapshot").get(null), "explicit release forgets snapshot owner");
                SimpleAssert.eq(0L, cache.retainedKeyBytes(), "explicit release clears key accounting");
            }
        } finally {
            HologramTextures.clear();
            installed.setBoolean(null, oldInstalled);
        }
    }

    private static HologramTextures.Material material() {
        return new HologramTextures.Material(null, null, Collections.emptyList(), false, false, false, false);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = HologramTextures.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static AuthorEditor editor() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        for (int x : new int[] { 0, 64 }) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", x);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            cells.add(cell);
        }
        root.add("cells", cells);
        return new AuthorEditor(AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults()), 4);
    }
}
