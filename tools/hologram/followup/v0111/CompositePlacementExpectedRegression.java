package gtmb.v0111.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Standalone test-only contracts using real Minecraft NBT classes and synthetic, explicitly specified rows.
 * Requires the native compile/runtime dependency classpath; starts no Minecraft/FML server or World.
 * Does not call a producer, derive actual from expectedRow(), or exercise the separate AE graph proof.
 */
public final class CompositePlacementExpectedRegression {
    private static final int INDEX = 1657; // dx17, dy1, dz1
    private static final String GT = "gregtech.api.metatileentity.BaseMetaTileEntity";
    private static final String VIB = "appeng.tile.misc.TileVibrationChamber";
    private static final String CAP = "crazypants.enderio.machine.capbank.TileCapBank";
    private static final String PANEL = "com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel";
    private static final String ORDINARY = "regression.NativeMachine";
    private static int cases;
    private CompositePlacementExpectedRegression() {}

    public static void main(String[] args) throws Exception {
        run("zero differences and all assets remain strict", () -> {
            Object source = source(ORDINARY);
            CompositePlacementExpected.Decision d = prepare(source, copy(source), context());
            check(d.scopeAccepted() && d.sourcePolicyAuditComplete(), "unchanged inputs bind and audit completely");
            match(d.compare(target(source)), true);
            check(!bool(d.receipt(), "authorizesPass"), "helper does not authorize an overall placement PASS");
        });
        run("unknown value, numeric type, missing and added fields fail", () -> {
            Object source = source(ORDINARY);
            Object actual = target(source); put(tile(actual), "amount", tag("Int", 8));
            mismatch(source, actual, "/tile/amount", 3, 3);
            actual = target(source); put(tile(actual), "amount", tag("Long", 7L));
            mismatch(source, actual, "/tile/amount", 3, 4);
            actual = target(source); remove(tile(actual), "amount");
            mismatch(source, actual, "/tile/amount", 3, -1);
            actual = target(source); put(tile(actual), "newPrivateState", tag("String", "unexpected"));
            mismatch(source, actual, "/tile/newPrivateState", -1, 8);
        });
        run("inventory, pattern, owner, energy and facing are never wildcarded", () -> {
            Object source = source(ORDINARY), actual = target(source);
            put(item(actual), "Count", tag("Byte", (byte) 2));
            mismatch(source, actual, "/tile/Items[0]/Count", 1, 1);
            actual = target(source); put(child(tile(actual), "pattern"), "recipe", tag("String", "other"));
            mismatch(source, actual, "/tile/pattern/recipe", 8, 8);
            actual = target(source); put(tile(actual), "owner", tag("String", "other"));
            mismatch(source, actual, "/tile/owner", 8, 8);
            actual = target(source); put(tile(actual), "energy", tag("Long", 999L));
            mismatch(source, actual, "/tile/energy", 4, 4);
            actual = target(source); put(tile(actual), "facing", tag("Byte", (byte) 3));
            mismatch(source, actual, "/tile/facing", 1, 1);
        });
        run("arrays, list element types and IEEE signed zero remain distinct", () -> {
            Object source = source(ORDINARY), actual = target(source);
            put(tile(actual), "bytes", tag("ByteArray", new byte[] { 1, 2, 4 }));
            mismatch(source, actual, "/tile/bytes", 7, 7);
            actual = target(source); put(tile(actual), "ints", tag("IntArray", new int[] { 7, 9 }));
            mismatch(source, actual, "/tile/ints", 11, 11);
            actual = target(source); Object list = list(); append(list, tag("Long", 1L)); put(tile(actual), "samples", list);
            mismatch(source, actual, "/tile/samples", 9, 9);
            actual = target(source); put(tile(actual), "float", tag("Float", 0.0F));
            mismatch(source, actual, "/tile/float", 5, 5);
            actual = target(source); put(tile(actual), "double", tag("Double", 0.0D));
            mismatch(source, actual, "/tile/double", 6, 6);
        });
        run("missing TE, changed block and metadata fail", () -> {
            Object source = source(ORDINARY), actual = target(source); remove(actual, "tile");
            match(prepare(source, copy(source), context()).compare(actual), false);
            actual = target(source); put(actual, "block", tag("String", "regression:other"));
            mismatch(source, actual, "/block", 8, 8);
            actual = target(source); put(actual, "meta", tag("Int", 5)); mismatch(source, actual, "/meta", 3, 3);
        });
        run("source audit remains separate from final native equality", () -> {
            Object source = source(ORDINARY), selected = copy(source);
            put(tile(selected), "amount", tag("Int", 99));
            put(tile(selected), "unreviewedTransportMarker", tag("String", "retained evidence"));
            CompositePlacementExpected.Decision d = prepare(source, selected, context());
            Object result = d.compare(target(source)); match(result, true);
            check(!d.sourcePolicyAuditComplete() && !bool(result, "sourcePolicyAuditComplete"), "unreviewed source edits remain visible");
            check(count(child(result, "sourcePolicyAudit")) >= 2, "all source edits retained in audit");
            Object actual = target(source); put(tile(actual), "amount", tag("Int", 99));
            match(d.compare(actual), false); // Matching a destructive intermediate edit cannot define expected.
        });
        run("saved-to-capture evidence is retained without weakening final World", () -> {
            Object source = source(ORDINARY), capture = copy(source); remove(tile(capture), "pattern");
            CompositePlacementExpected.Decision d = CompositePlacementExpected.prepare(source, copy(source), capture, context(), INDEX, null);
            match(d.compare(target(source)), true);
            check(!d.sourcePolicyAuditComplete(), "capture deletion remains unreviewed");
            Object actual = target(source); remove(tile(actual), "pattern"); match(d.compare(actual), false);
        });
        run("root relocation does not relocate nested private coordinates", () -> {
            Object source = source(ORDINARY); match(prepare(source, copy(source), context()).compare(target(source)), true);
            Object actual = target(source); put(child(tile(actual), "privateCoordinates"), "x", tag("Int", 200));
            mismatch(source, actual, "/tile/privateCoordinates/x", 3, 3);
            Object wrongSource = copy(source); put(tile(wrongSource), "x", tag("Int", 118));
            check(!prepare(wrongSource, copy(wrongSource), context()).scopeAccepted(), "source root bound to source origin/index");
            Object overflow = context(); put(overflow, "targetOrigin", tag("IntArray", new int[] { Integer.MAX_VALUE, 80, 400 }));
            check(!prepare(source, copy(source), overflow).scopeAccepted(), "target coordinate Int overflow rejected");
        });
        run("scope evidence rejects even an exactly equal candidate", () -> {
            Object source = source(ORDINARY), scope = context(); put(scope, "finalChoicesBound", tag("Int", 1));
            Object result = prepare(source, copy(source), scope).compare(target(source));
            check(bool(result, "equal") && !bool(result, "fullWorldMatch"), "wrong evidence type cannot authorize equality");
            scope = context(); put(scope, "retainNbt", tag("Byte", (byte) 0));
            match(prepare(source, copy(source), scope).compare(target(source)), false);
            scope = context(); put(scope, "deidentificationLevel", tag("Int", 6));
            match(prepare(source, copy(source), scope).compare(target(source)), false);
            Object selected = copy(source); put(selected, "meta", tag("Int", 5));
            match(prepare(source, selected, context()).compare(target(source)), false);
        });
        run("GT selected activity state is a finite Byte0-or1 patch", () -> {
            Object source = source(GT); put(tile(source), "mActive", tag("Byte", (byte) 0));
            Object selected = copy(source); put(tile(selected), "mActive", tag("Byte", (byte) 1));
            Object actual = target(source); put(tile(actual), "mActive", tag("Byte", (byte) 1));
            match(prepare(source, selected, context()).compare(actual), true);
            selected = copy(source); put(tile(selected), "mActive", tag("Byte", (byte) 42));
            actual = target(source); put(tile(actual), "mActive", tag("Byte", (byte) 42));
            match(prepare(source, selected, context()).compare(actual), false);
            selected = copy(source); put(tile(selected), "mActive", tag("Int", 1));
            actual = target(source); put(tile(actual), "mActive", tag("Int", 1));
            match(prepare(source, selected, context()).compare(actual), false);
        });
        run("GT root actor binding does not replace nested or other-class owners", () -> {
            Object source = ownedSource(GT), selected = clearedOwners(source), actual = actorTarget(source);
            match(prepare(source, selected, context()).compare(actual), true);
            put(child(tile(actual), "pattern"), "mOwnerName", tag("String", "Actor"));
            match(prepare(source, selected, context()).compare(actual), false);
            Object scope = context(); remove(scope, "actorUUID");
            match(prepare(source, selected, scope).compare(actorTarget(source)), false);
            source = ownedSource(ORDINARY); selected = clearedOwners(source);
            match(prepare(source, selected, context()).compare(actorTarget(source)), false);
            source = ownedSource(GT); selected = clearedOwners(source); scope = context();
            put(scope, "kind", tag("String", "share")); put(scope, "deidentificationLevel", tag("Int", 6));
            match(prepare(source, selected, scope).compare(actorTarget(source)), false);
        });
        run("VibrationChamber omitted Int burnSpeed requires explicit reviewed policy", () -> {
            Object source = source(VIB); put(tile(source), "burnSpeed", tag("Int", 100));
            Object selected = copy(source); remove(tile(selected), "burnSpeed");
            Object actual = target(source); put(tile(actual), "burnSpeed", tag("Int", 0));
            match(prepare(source, selected, context()).compare(actual), true);
            match(prepare(source, copy(source), context()).compare(actual), false);
            Object scope = context(); remove(scope, "aePolicyVersion"); match(prepare(source, selected, scope).compare(actual), false);
            actual = target(source); remove(tile(actual), "burnSpeed"); match(prepare(source, selected, context()).compare(actual), false);
            source = source(ORDINARY); put(tile(source), "burnSpeed", tag("Int", 100));
            selected = copy(source); remove(tile(selected), "burnSpeed"); actual = target(source); put(tile(actual), "burnSpeed", tag("Int", 0));
            match(prepare(source, selected, context()).compare(actual), false);
        });
        run("runtime and scalar cover data do not cause automatic UNKNOWN or a cast", () -> {
            Object source = source(GT); put(tile(source), "mTotalRunTime", tag("Long", 50L));
            Object covers = list(), cover = compound(); put(cover, "id", tag("Int", 17)); put(cover, "d", tag("Int", 9));
            append(covers, cover); put(tile(source), "gt.covers", covers);
            match(prepare(source, copy(source), context()).compare(target(source)), true);
            Object actual = target(source); put(tile(actual), "mTotalRunTime", tag("Long", 51L));
            Object result = prepare(source, copy(source), context()).compare(actual); match(result, false);
            check(text(changeAt(result, "/tile/mTotalRunTime"), "assessment").equals("UNKNOWN_NATIVE_SEMANTICS"), "unproved runtime difference stays failed");
            actual = target(source); put(entry(child(tile(actual), "gt.covers"), 0), "d", tag("Int", 10));
            mismatch(source, actual, "/tile/gt.covers[0]/d", 3, 3);
        });
        run("panel, CapBank RF and display fields stay strict without native predicates", () -> {
            Object source = source(PANEL); put(tile(source), "lastAESampleTick", tag("Long", 10L));
            match(prepare(source, copy(source), context()).compare(target(source)), true);
            Object actual = target(source); put(tile(actual), "lastAESampleTick", tag("Long", 11L));
            match(prepare(source, copy(source), context()).compare(actual), false);
            source = source(CAP); put(tile(source), "storedEnergyRF", tag("Int", 0));
            put(tile(source), "hasDisplayTypes", tag("Byte", (byte) 1)); put(tile(source), "faceDisplay2", tag("Short", (short) 1));
            match(prepare(source, copy(source), context()).compare(target(source)), true);
            actual = target(source); put(tile(actual), "storedEnergyRF", tag("Int", 2500000));
            match(prepare(source, copy(source), context()).compare(actual), false);
            actual = target(source); remove(tile(actual), "hasDisplayTypes"); remove(tile(actual), "faceDisplay2");
            match(prepare(source, copy(source), context()).compare(actual), false);
        });
        run("null AE proof preserves g p k and complete proxy payload", () -> {
            Object source = source("appeng.tile.storage.TileDrive"), proxy = compound();
            put(proxy, "g", tag("Long", 3005L)); put(proxy, "p", tag("Int", -1)); put(proxy, "k", tag("Long", 42L));
            put(tile(source), "proxy", proxy); Object selected = copy(source); remove(tile(selected), "proxy");
            CompositePlacementExpected.Decision d = prepare(source, selected, context());
            match(d.compare(target(source)), true); check(!d.sourcePolicyAuditComplete(), "proxy omission remains unproved in source audit");
            for (String key : new String[] { "g", "p", "k" }) {
                Object actual = target(source); put(child(tile(actual), "proxy"), key, key.equals("p") ? tag("Int", 0) : tag("Long", 9999L));
                match(d.compare(actual), false);
            }
            Object actual = target(source); remove(tile(actual), "proxy"); match(d.compare(actual), false);
        });
        run("null SHARE proof cannot certify calibration even when native tags match", () -> {
            Object source = source(ORDINARY), scope = shareContext();
            CompositePlacementExpected.Decision d = CompositePlacementExpected.prepare(source, copy(source), copy(source), scope, INDEX, null, null);
            Object result = d.compare(target(source));
            check(bool(result, "equal"), "zero typed differences still reported honestly");
            check(!d.scopeAccepted() && d.sourcePolicyAuditComplete(), "missing share binding is scope, not an invented source-policy failure");
            match(result, false);
        });
        run("null SHARE proof retains exact QN anchors generation and proxy g p k", () -> {
            Object source = quantumSource();
            for (String key : new String[] { "anchorDim", "anchorX", "anchorY", "anchorZ", "anchorGeneration" }) {
                Object actual = target(source); put(tile(actual), key, key.equals("anchorGeneration") ? tag("String", "unproved-new-generation") : tag("Int", 999));
                Object result = CompositePlacementExpected.prepare(source, copy(source), copy(source), shareContext(), INDEX, null, null).compare(actual);
                match(result, false); Object delta = changeAt(result, "/tile/" + key);
                check(number(child(delta, "beforeTagId")) == (key.equals("anchorGeneration") ? 8 : 3), "original native anchor type retained");
            }
            for (String key : new String[] { "g", "p", "k" }) {
                Object actual = target(source); put(child(tile(actual), "proxy"), key, key.equals("p") ? tag("Int", 0) : tag("Long", 9999L));
                Object result = CompositePlacementExpected.prepare(source, copy(source), copy(source), shareContext(), INDEX, null, null).compare(actual);
                match(result, false); changeAt(result, "/tile/proxy/" + key);
            }
            Object actual = target(source); put(tile(actual), "anchorX", tag("Long", 100L));
            Object result = CompositePlacementExpected.prepare(source, copy(source), copy(source), shareContext(), INDEX, null, null).compare(actual);
            check(number(child(changeAt(result, "/tile/anchorX"), "afterTagId")) == 4, "anchor numeric-width mismatch is not coerced"); match(result, false);
        });
        run("wrong kind or level never supplies an implicit QN anchor patch", () -> {
            Object source = quantumSource(), actual = target(source); put(tile(actual), "anchorX", tag("Int", 999));
            Object self = CompositePlacementExpected.prepare(source, copy(source), copy(source), context(), INDEX, null, null).compare(actual);
            check(bool(self, "scopeAccepted"), "self1 scope itself remains valid with null self proof"); match(self, false); changeAt(self, "/tile/anchorX");
            Object wrongLevel = shareContext(); put(wrongLevel, "deidentificationLevel", tag("Int", 1));
            match(CompositePlacementExpected.prepare(source, copy(source), copy(source), wrongLevel, INDEX, null, null).compare(actual), false);
            Object wrongKind = context(); put(wrongKind, "kind", tag("String", "raw"));
            match(CompositePlacementExpected.prepare(source, copy(source), copy(source), wrongKind, INDEX, null, null).compare(actual), false);
        });
        run("returned snapshots and diff payloads are detached", () -> {
            Object source = source(ORDINARY), selected = copy(source), actual = target(source);
            CompositePlacementExpected.Decision d = prepare(source, selected, context());
            Object exposed = d.expectedRow(); put(tile(exposed), "amount", tag("Int", 99));
            check(number(child(tile(d.expectedRow()), "amount")) == 7, "expectedRow copy is detached");
            put(tile(actual), "amount", tag("Int", 8)); Object result = d.compare(actual);
            put(tile(actual), "amount", tag("Int", 99));
            check(number(child(changeAt(result, "/tile/amount"), "after")) == 8, "diff preserves independently copied actual value");
            actual = target(source); put(tile(actual), "bytes", tag("ByteArray", new byte[] { 1, 2, 4 }));
            result = d.compare(actual); ((byte[]) call(child(tile(actual), "bytes"), "func_150292_c"))[2] = 99;
            byte[] recorded = (byte[]) call(child(changeAt(result, "/tile/bytes"), "after"), "func_150292_c");
            check(recorded[2] == 4, "mutable actual array is detached from full diff payload");
            check(number(child(tile(source), "amount")) == 7 && number(child(tile(selected), "amount")) == 7, "source inputs unchanged");
            match(d.compare(target(source)), true);
        });
        System.out.println("CompositePlacementExpectedRegression: " + cases + " synthetic real-NBT cases passed; no game, placement, or AE proof executed.");
    }

    private interface Case { void execute() throws Exception; }
    private static void run(String name, Case test) throws Exception { test.execute(); cases++; System.out.println("OK " + name); }
    private static CompositePlacementExpected.Decision prepare(Object source, Object selected, Object scope) throws Exception {
        return CompositePlacementExpected.prepare(source, selected, copy(selected), scope, INDEX, null);
    }
    private static Object source(String cls) throws Exception {
        Object row = compound(), tile = compound(); put(row, "block", tag("String", "regression:machine"));
        put(row, "meta", tag("Int", 4)); put(row, "tileClass", tag("String", cls)); put(row, "tile", tile);
        put(tile, "id", tag("String", "regression-native")); put(tile, "x", tag("Int", 117));
        put(tile, "y", tag("Int", 65)); put(tile, "z", tag("Int", -299)); put(tile, "amount", tag("Int", 7));
        put(tile, "owner", tag("String", "original")); put(tile, "energy", tag("Long", 1000L)); put(tile, "facing", tag("Byte", (byte) 2));
        put(tile, "float", tag("Float", -0.0F)); put(tile, "double", tag("Double", -0.0D));
        put(tile, "bytes", tag("ByteArray", new byte[] { 1, 2, 3 })); put(tile, "ints", tag("IntArray", new int[] { 7, 8 }));
        Object samples = list(); append(samples, tag("Int", 1)); put(tile, "samples", samples);
        Object pattern = compound(); put(pattern, "recipe", tag("String", "preserved")); put(pattern, "mOwnerName", tag("String", "private-owner")); put(tile, "pattern", pattern);
        Object coordinates = compound(); put(coordinates, "x", tag("Int", 100)); put(tile, "privateCoordinates", coordinates);
        Object items = list(), item = compound(), privateTag = compound(); put(item, "id", tag("Short", (short) 17));
        put(item, "Count", tag("Byte", (byte) 3)); put(item, "Damage", tag("Short", (short) 2));
        put(privateTag, "configuration", tag("String", "must survive")); put(item, "tag", privateTag); append(items, item); put(tile, "Items", items);
        return row;
    }
    private static Object context() throws Exception {
        Object out = compound(); put(out, "kind", tag("String", "self"));
        for (String key : new String[] { "inputBindingComplete", "finalChoicesBound", "retainNbt", "retainCovers", "allNbtSelected", "identityPose" }) put(out, key, tag("Byte", (byte) 1));
        put(out, "deidentificationLevel", tag("Int", 1)); put(out, "aePolicyVersion", tag("Int", 1));
        put(out, "sourceOrigin", tag("IntArray", new int[] { 100, 64, -300 })); put(out, "targetOrigin", tag("IntArray", new int[] { 200, 80, 400 }));
        put(out, "actorName", tag("String", "Actor")); put(out, "actorUUID", tag("String", "00000000-0000-0000-0000-000000000001")); return out;
    }
    private static Object shareContext() throws Exception { Object out = context(); put(out, "kind", tag("String", "share")); put(out, "deidentificationLevel", tag("Int", 6)); return out; }
    private static Object quantumSource() throws Exception {
        Object out = source("com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode"), tile = tile(out), proxy = compound();
        put(tile, "anchorDim", tag("Int", 1048578)); put(tile, "anchorX", tag("Int", 100)); put(tile, "anchorY", tag("Int", 64)); put(tile, "anchorZ", tag("Int", -300)); put(tile, "anchorGeneration", tag("String", "original-generation"));
        put(proxy, "g", tag("Long", 3005L)); put(proxy, "p", tag("Int", -1)); put(proxy, "k", tag("Long", 42L)); put(tile, "proxy", proxy); return out;
    }
    /** Independently specified target cell: fixture target origin200,80,400 plus dx17,dy1,dz1. */
    private static Object target(Object source) throws Exception { Object out = copy(source); put(tile(out), "x", tag("Int", 217)); put(tile(out), "y", tag("Int", 81)); put(tile(out), "z", tag("Int", 401)); return out; }
    private static Object ownedSource(String cls) throws Exception { Object source = source(cls); put(tile(source), "mOwnerName", tag("String", "Original")); put(tile(source), "mOwnerUuid", tag("String", "original-uuid")); return source; }
    private static Object clearedOwners(Object source) throws Exception { Object out = copy(source); put(tile(out), "mOwnerName", tag("String", "")); put(tile(out), "mOwnerUuid", tag("String", "")); return out; }
    private static Object actorTarget(Object source) throws Exception { Object out = target(source); put(tile(out), "mOwnerName", tag("String", "Actor")); put(tile(out), "mOwnerUuid", tag("String", "00000000-0000-0000-0000-000000000001")); return out; }
    private static void match(Object receipt, boolean expected) throws Exception { check(bool(receipt, "fullWorldMatch") == expected, "fullWorldMatch expected " + expected + "; status=" + text(receipt, "worldResultStatus")); }
    private static void mismatch(Object source, Object actual, String path, int before, int after) throws Exception {
        Object result = prepare(source, copy(source), context()).compare(actual); match(result, false); Object delta = changeAt(result, path);
        check(number(child(delta, "beforeTagId")) == before && number(child(delta, "afterTagId")) == after, "typed mismatch retained at " + path);
    }
    private static Object changeAt(Object result, String path) throws Exception { Object list = child(result, "expectedToActual"); for (int i = 0; i < count(list); i++) { Object row = entry(list, i); if (text(row, "path").equals(path)) return row; } throw new AssertionError("missing full diff at " + path); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Object tile(Object row) throws Exception { return child(row, "tile"); }
    private static Object item(Object row) throws Exception { return entry(child(tile(row), "Items"), 0); }
    private static Object child(Object tag, String key) throws Exception { return call(tag, "func_74781_a", key); }
    private static Object entry(Object list, int index) throws Exception { return call(list, "func_150305_b", index); }
    private static int count(Object list) throws Exception { return (Integer) call(list, "func_74745_c"); }
    private static long number(Object tag) throws Exception { return (Long) call(tag, "func_150291_c"); }
    private static boolean bool(Object compound, String key) throws Exception { Object value = child(compound, key); return value != null && number(value) == 1; }
    private static String text(Object compound, String key) throws Exception { return (String) call(child(compound, key), "func_150285_a_"); }
    private static Object copy(Object value) throws Exception { return call(value, "func_74737_b"); }
    private static Object compound() throws Exception { return tag("Compound"); }
    private static Object list() throws Exception { return tag("List"); }
    private static void put(Object out, String key, Object value) throws Exception { call(out, "func_74782_a", key, value); }
    private static void append(Object out, Object value) throws Exception { call(out, "func_74742_a", value); }
    private static void remove(Object out, String key) throws Exception { call(out, "func_82580_o", key); }
    private static Object tag(String suffix, Object... args) throws Exception { Class<?> type = Class.forName("net.minecraft.nbt.NBTTag" + suffix); for (Constructor<?> c : type.getDeclaredConstructors()) if (compatible(c.getParameterTypes(), args)) { c.setAccessible(true); return c.newInstance(args); } throw new NoSuchMethodException(type.getName()); }
    private static Object call(Object value, String name, Object... args) throws Exception { for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) for (Method m : type.getDeclaredMethods()) if (m.getName().equals(name) && compatible(m.getParameterTypes(), args)) { m.setAccessible(true); return m.invoke(value, args); } throw new NoSuchMethodException(value.getClass().getName() + "." + name); }
    private static boolean compatible(Class<?>[] types, Object[] args) { if (types.length != args.length) return false; for (int i = 0; i < types.length; i++) { Class<?> type = types[i]; if (type.isPrimitive()) type = type == byte.class ? Byte.class : type == short.class ? Short.class : type == int.class ? Integer.class : type == long.class ? Long.class : type == float.class ? Float.class : type == double.class ? Double.class : type; if (args[i] == null || !type.isInstance(args[i])) return false; } return true; }
}
