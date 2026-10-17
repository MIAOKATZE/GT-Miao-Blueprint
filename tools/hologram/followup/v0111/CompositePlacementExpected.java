package gtmb.v0111.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Unwired test-only checkpoint. Explicit detached NBT rows, never arbitrary DTOs or a live World.
 * Audits source-to-selected changes separately from a strict, complete native-baseline World comparison.
 * Does not call producer selection/projection/install/matching code or infer expected values from actual World.
 * Native identity/graph and lifecycle predicates deliberately remain UNKNOWN until independently bound.
 */
public final class CompositePlacementExpected {
    private static final String GT = "gregtech.api.metatileentity.BaseMetaTileEntity";
    private static final String QN = "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode";
    private static final String PANEL = "com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel";
    private static final String CAP = "crazypants.enderio.machine.capbank.TileCapBank";
    private static final String VIB = "appeng.tile.misc.TileVibrationChamber";
    private static final Set<String> AE = names("appeng.tile.storage.TileDrive", "appeng.tile.storage.TileChest",
        "appeng.tile.storage.TileIOPort", "appeng.tile.misc.TileInterface", "appeng.tile.misc.TileCondenser",
        "appeng.tile.misc.TileInscriber", "appeng.tile.misc.TileAdvancedInscriber",
        "appeng.tile.crafting.TileMolecularAssembler", "appeng.tile.crafting.TileCraftingTile",
        "appeng.tile.crafting.TileCraftingStorageTile", "appeng.tile.crafting.TileCraftingMonitorTile",
        "appeng.tile.misc.TileQuartzGrowthAccelerator", "appeng.tile.misc.TileCharger", VIB,
        "appeng.tile.misc.TileStorageReshuffle", "appeng.tile.misc.TilePatternOptimizationMatrix",
        "appeng.tile.networking.TileEnergyAcceptor", "appeng.tile.networking.TileEnergyCell",
        "appeng.tile.networking.TileDenseEnergyCell", "appeng.tile.networking.TileController",
        "appeng.tile.networking.TileWireless", QN, PANEL);
    private static final Set<String> ROW_KEYS = names("block", "meta", "tileClass", "tile");
    private static Field listEntries;
    private CompositePlacementExpected() {}

    /**
     * Row schema: block:String, meta:Int, optional tileClass:String and tile:Compound.
     * Saved/capture rows are original decoded inputs, not producer-generated expected values.
     * Frozen context: kind (String); inputBindingComplete, finalChoicesBound, retainNbt, retainCovers,
     * allNbtSelected, identityPose (Bytes); deidentificationLevel, aePolicyVersion (Ints);
     * actorUUID/actorName (Strings); sourceOrigin/targetOrigin (IntArray[3]).
     * Registry annotations additionally need itemRegistries (Compound of numeric item ID to registry String).
     * No caller PASS flag is consumed. Creative RF initialization is not admitted by this checkpoint.
     */
    public static Decision prepare(Object baseline, Object selectedSaved, Object selectedCapture,
        Object context, int index) throws Exception {
        return prepare(baseline, selectedSaved, selectedCapture, context, index, null);
    }

    /** Optional independently constructed full native self-embedding proof; never a caller-provided LongMap. */
    public static Decision prepare(Object baseline, Object selectedSaved, Object selectedCapture,
        Object context, int index, CompositeAeSelfCopyProof.Proof selfProof) throws Exception {
        Decision d = new Decision(index);
        try {
            row(baseline); row(selectedSaved); row(selectedCapture); requireType(context, 10, "context");
            d.cls = string(baseline, "tileClass"); d.context = context; d.selected = selectedCapture;
            d.source = baseline; d.selfProof = selfProof;
            d.expected = copy(baseline);
            String kind = string(context, "kind");
            if (!kind.equals("self") && !kind.equals("share")) d.reject("/context/kind", "unsupported scenario");
            if (selfProof != null && !kind.equals("self")) d.reject("/context/kind", "self embedding proof supplied outside self scope");
            for (String flag : new String[] { "inputBindingComplete", "finalChoicesBound", "retainNbt",
                "retainCovers", "allNbtSelected", "identityPose" })
                if (!flag(context, flag)) d.unknown("/context/" + flag, "required explicit frozen input evidence missing");
            int level = integer(context, "deidentificationLevel", -1);
            if (kind.equals("self") && level != 1 || kind.equals("share") && level != 6)
                d.unknown("/context/deidentificationLevel", "this finite checkpoint reviews self1/share6 only");
            if (index < 0 || index >= 64000) d.reject("/index", "outside complete fixture volume");
            if (type(child(baseline, "tile")) == 10) {
                int[] source = intArray(context, "sourceOrigin"); Object tile = child(baseline, "tile");
                if (source == null || source.length != 3) d.unknown("/context/sourceOrigin", "exact source origin unavailable");
                else for (int axis = 0; axis < 3; axis++) {
                    String key = "xyz".substring(axis, axis + 1);
                    long coordinate = (long) source[axis] + offset(index, axis);
                    if (type(child(tile, key)) != 3 || number(child(tile, key)) != coordinate)
                        d.reject("/tile/" + key, "original native root coordinate does not match independently bound source cell");
                }
            }
            if (!same(child(baseline, "block"), child(selectedSaved, "block"))
                || !same(child(baseline, "meta"), child(selectedSaved, "meta")))
                d.reject("/", "selected block/metadata variant changed; no material-calibration rule is admitted here");
            List<Change> captureChanges = differences(selectedSaved, selectedCapture, "");
            for (Change c : captureChanges) { d.record("selected_to_capture", c, "REJECTED");
                d.sourceReject(c.path, "identity-pose saved/capture raw rows differ; final native fields remain protected by baseline"); }
            if (!same(child(baseline, "tileClass"), child(selectedSaved, "tileClass")))
                d.reject("/tileClass", "selected exact native class binding changed or is absent");
            for (String key : new String[] { "block", "meta", "tileClass" })
                if (!same(child(selectedSaved, key), child(selectedCapture, key)))
                    d.reject("/" + key, "capture variant/class differs from the bound selected source");
            d.selected = selectedSaved;
            for (Change c : differences(baseline, selectedSaved, "")) {
                String rule = auditChange(d, c);
                d.record("baseline_to_selected", c, rule == null ? "REJECTED" : rule);
                if (rule == null) d.sourceReject(c.path, "unreviewed source-policy modification; original native tags retained in expected World");
            }
            d.selected = selectedCapture;
            if (selfProof != null && kind.equals("self")) {
                try {
                    // Pass a detached copy: even a rejected proof must not change the native baseline expected values.
                    Object patched = selfProof.patchExpectedNodeIdentities(copy(d.expected), index); row(patched);
                    List<Change> identityChanges = differences(d.expected, patched, "");
                    for (Change c : identityChanges) {
                        int required = c.path.endsWith("/g") ? 4 : c.path.endsWith("/p") ? 3 : -1;
                        if (required < 0 || type(c.before) != required || type(c.after) != required)
                            throw new IllegalStateException("embedding patch changed a field outside typed g/p");
                    }
                    for (Change c : identityChanges) d.recordPatch(c, "independent full self-embedding proof exact native port g/p; source k retained");
                    d.expected = patched; d.identityProofStatus = "APPLIED_INDEPENDENT_SELF_EMBEDDING";
                } catch (Exception invalidProof) {
                    d.identityProofStatus = "REJECTED_BASELINE_IDENTITIES_RETAINED";
                    d.sourceUnknown("/nativeIdentityProof", "independent self proof rejected this original source row; g/p remain unchanged: " + invalidProof.toString());
                }
            }
            if (type(child(baseline, "tile")) == 10 && type(child(selectedSaved, "tile")) == 10) {
                Object originalTile = child(baseline, "tile"), chosen = child(selectedCapture, "tile"), expected = child(d.expected, "tile");
                // This observed source-state value is taken from the actual selected preset, never from actual target.
                if (d.cls.equals(GT) && type(child(chosen, "mActive")) == 1
                    && number(child(chosen, "mActive")) >= 0 && number(child(chosen, "mActive")) <= 1)
                    d.patch(expected, "mActive", child(chosen, "mActive"), "/tile/mActive", "actual selected Byte state");
                if (d.cls.equals(GT) && kind.equals("self") && level == 1) for (String key : new String[] { "mOwnerName", "mOwnerUuid" }) {
                    if (type(child(chosen, key)) == 8 && string(chosen, key).isEmpty() && type(child(originalTile, key)) == 8) {
                        String actorKey = key.equals("mOwnerName") ? "actorName" : "actorUUID";
                        if (type(child(context, actorKey)) != 8 || string(context, actorKey).isEmpty())
                            d.unknown("/context/" + actorKey, "native actor binding evidence missing");
                        else d.patch(expected, key, child(context, actorKey), "/tile/" + key, "exact GT actor root binding");
                    }
                }
                if (d.cls.equals(VIB) && child(chosen, "burnSpeed") == null && type(child(originalTile, "burnSpeed")) == 3
                    && integer(context, "aePolicyVersion", -1) == 1)
                    d.patch(expected, "burnSpeed", tagInt(0), "/tile/burnSpeed", "reviewed native reader missing-Int default after explicit preset omission");
                // Runtime, cover, screen, display and RF fields stay byte-for-byte at their native baseline values.
                // Their mere presence is not an unmet obligation: only an actual mismatch needs further evidence.
                int[] target = intArray(context, "targetOrigin");
                if (target == null || target.length != 3) d.unknown("/context/targetOrigin", "exact target origin unavailable");
                else for (int axis = 0; axis < 3; axis++) {
                    long coordinate = (long) target[axis] + offset(index, axis);
                    if (coordinate < Integer.MIN_VALUE || coordinate > Integer.MAX_VALUE)
                        d.reject("/context/targetOrigin", "native coordinate exceeds Int range");
                    else d.patch(expected, "xyz".substring(axis, axis + 1), tagInt((int) coordinate),
                        "/tile/" + "xyz".charAt(axis), "root-only identity-pose relocation");
                }
            }
            if (kind.equals("share")) d.sourceUnknown("/context/shareTargetProof", "paid share target/bank/terminal policy proof not implemented; no identity or private-NBT exception granted");
        } catch (Exception invalid) { d.reject("/", "malformed or unsupported explicit input: " + invalid.toString()); }
        return d;
    }

    private static String auditChange(Decision d, Change c) throws Exception {
        String p = c.path;
        if (c.before != null && c.after != null && type(c.before) != type(c.after)) return null;
        if (c.after == null && d.selfProof != null) {
            try {
                if (d.selfProof.permitsIdentityOmission(d.source, d.selected, d.index, p))
                    return "independent graph-bound exact native proxy omission; entire original g/p/k shape and selected host/part definition checked";
            } catch (Exception invalidProof) {
                d.sourceUnknown(p, "independent identity omission proof rejected this row: " + invalidProof.toString());
            }
        }
        if (d.cls.equals(GT) && p.equals("/tile/mActive") && type(c.before) == 1 && type(c.after) == 1
            && number(c.before) >= 0 && number(c.before) <= 1 && number(c.after) >= 0 && number(c.after) <= 1)
            return "selected source activity state, not a volatile-field exclusion";
        if (d.cls.equals(GT) && (p.equals("/tile/mOwnerName") || p.equals("/tile/mOwnerUuid"))
            && type(c.before) == 8 && type(c.after) == 8 && text(c.after).isEmpty()) return "explicit level1 GT actor-root clearing; native actor binding remains required";
        if (AE.contains(d.cls) && p.matches("/tile/[xyz]") && type(c.before) == 3 && type(c.after) == 3) {
            int expected = p.endsWith("/y") ? 64 : 0;
            return number(c.after) == expected ? "exact AE canonical transport root coordinate; native root relocation remains separate" : null;
        }
        if (d.cls.equals(VIB) && p.equals("/tile/burnSpeed") && type(c.before) == 3 && number(c.before) > 0
            && c.after == null && integer(d.context, "aePolicyVersion", -1) == 1) return "explicit reviewed AE burn-cache omission; native missing Int defaults to0";
        if (p.matches("/tile/proxy/(g|p|k)") && c.after == null && (AE.contains(d.cls) || d.cls.equals(GT))) {
            int required = p.endsWith("/p") ? 3 : 4;
            if (type(c.before) != required) return null;
            d.sourceUnknown(p, "independent full ordinary embedding/native proxy host-path proof required; no caller LongMap consumed");
            return "source identity omission observed; NOT permission to replace native p/g/k";
        }
        if (d.cls.equals(GT) && p.equals("/tile/cache") && type(c.before) == 9
            && elements(c.before).isEmpty() && c.after == null) return "exact native empty ME shadow list; baseline shape stays strict";
        if (AE.contains(d.cls) && c.after == null && type(c.before) == 10 && keys(c.before).isEmpty()
            && p.matches("/tile/(inv|config|patterns|storage)/(item[0-9]+|#[0-9]+)(/tag)?"))
            return "exact empty native inventory/default compound only; nonempty private subtree cannot use this rule";
        // Registry annotations are approved only at actual ItemStack/cover shapes and against explicit raw registry facts.
        if (c.before == null && type(c.after) == 8 && p.endsWith("/gtitBlueprintItemRegistry")) {
            Object parent = at(d.selected, parent(p));
            if (type(child(parent, "id")) != 2 || type(child(parent, "Count")) != 1 || type(child(parent, "Damage")) != 2) return null;
            Object registry = child(d.context, "itemRegistries");String id = String.valueOf(number(child(parent, "id")) & 65535);
            return type(registry) == 10 && same(child(registry, id), c.after) ? "raw registered ItemStack transport annotation, exact id/type/registry verified" : null;
        }
        if (c.before == null && p.equals("/tile/gtmb.quantumSides") && d.cls.equals(QN)
            && type(c.after) == 3 && number(c.after) >= 0 && number(c.after) <= 63) return "exact QN six-bit preview annotation; not a native expected field";
        // Original-network backups and authored coordinate descriptors require their own exact binding proof.
        if (p.startsWith("/tile/gtmbQuantumOriginal") || p.startsWith("/tile/gtmbNetwork") || p.equals("/tile/gtmbQuantumMode")) {
            d.sourceUnknown(p, "quantum original/group descriptor-to-real world/controller/generation binding not implemented");
            return "descriptor retained verbatim as unresolved evidence";
        }
        return null;
    }

    public static final class Decision {
        private final int index;
        private String cls = "";
        private Object context, source, selected, expected;
        private CompositeAeSelfCopyProof.Proof selfProof;
        private String identityProofStatus = "UNSUPPLIED_BASELINE_IDENTITIES_RETAINED";
        private final List<String> rejected = new ArrayList<String>(), unknown = new ArrayList<String>();
        private final List<String> sourceRejected = new ArrayList<String>(), sourceUnknown = new ArrayList<String>();
        private final Object audit, patches;
        private Decision(int index) throws Exception { this.index = index; audit = list(); patches = list(); }
        public boolean scopeAccepted() { return expected != null && rejected.isEmpty() && unknown.isEmpty(); }
        public boolean sourcePolicyAuditComplete() { return sourceRejected.isEmpty() && sourceUnknown.isEmpty(); }
        public Object expectedRow() throws Exception { return scopeAccepted() ? copy(expected) : null; }
        public Object receipt() throws Exception {
            Object out = compound(); putInt(out, "index", index); putString(out, "sourceObservedNativeClass", cls);
            putString(out, "scopeStatus", !rejected.isEmpty() ? "REJECTED" : !unknown.isEmpty() ? "UNKNOWN" : "BOUND");
            putString(out, "sourcePolicyStatus", !sourceRejected.isEmpty() ? "UNREVIEWED_CHANGES" : !sourceUnknown.isEmpty() ? "UNKNOWN" : "COMPLETE");
            putByte(out, "sourcePolicyAuditComplete", (byte) (sourcePolicyAuditComplete() ? 1 : 0));
            putString(out, "worldResultStatus", "NOT_COMPARED");
            putString(out, "identityProofStatus", identityProofStatus);
            putByte(out, "authorizesPass", (byte) 0); put(out, "sourcePolicyAudit", copy(audit)); put(out, "finitePatches", copy(patches));
            put(out, "scopeRejected", strings(rejected)); put(out, "scopeUnknown", strings(unknown));
            put(out, "sourcePolicyUnreviewed", strings(sourceRejected)); put(out, "sourcePolicyUnknown", strings(sourceUnknown)); return out;
        }
        /** Complete native World comparison. Input scope remains mandatory; intermediate policy audit is independent. */
        public Object compare(Object actual) throws Exception {
            Object out = receipt(); putByte(out, "comparisonExecuted", (byte) (expected != null ? 1 : 0));
            if (expected == null) return out;
            row(actual); Object changes = list();
            for (Change c : differences(expected, actual, "")) {
                Object entry = change(c); String reason = unprovedNativeDifference(cls, c);
                putString(entry, "assessment", reason == null ? "STRICT_TYPED_MISMATCH" : "UNKNOWN_NATIVE_SEMANTICS");
                if (reason != null) putString(entry, "missingEvidence", reason);
                append(changes, entry);
            }
            put(out, "expectedToActual", changes); putByte(out, "equal", (byte) (count(changes) == 0 ? 1 : 0));
            putByte(out, "scopeAccepted", (byte) (scopeAccepted() ? 1 : 0));
            putByte(out, "fullWorldMatch", (byte) (scopeAccepted() && count(changes) == 0 ? 1 : 0));
            putString(out, "worldResultStatus", !scopeAccepted() ? "INPUT_SCOPE_UNRESOLVED"
                : count(changes) == 0 ? "FULL_NATIVE_TYPED_MATCH" : "FAIL_UNPROVED_WORLD_DIFFERENCE"); return out;
        }
        private void reject(String path, String reason) { rejected.add(path + ": " + reason); }
        private void unknown(String path, String reason) { unknown.add(path + ": " + reason); }
        private void sourceReject(String path, String reason) { sourceRejected.add(path + ": " + reason); }
        private void sourceUnknown(String path, String reason) { sourceUnknown.add(path + ": " + reason); }
        private void record(String stage, Change c, String rule) throws Exception {
            Object row = change(c); putString(row, "stage", stage); putString(row, "assessment", rule); append(audit, row);
        }
        private void patch(Object target, String key, Object value, String path, String rule) throws Exception {
            Object before = child(target, key); if (same(before, value)) return;
            recordPatch(new Change(path, before, value), rule); put(target, key, copy(value));
        }
        private void recordPatch(Change c, String rule) throws Exception {
            Object row = change(c); putString(row, "rule", rule); append(patches, row);
        }
    }

    /** Labels evidence still needed; this function never changes, omits, or accepts a mismatching tag. */
    private static String unprovedNativeDifference(String cls, Change c) throws Exception {
        int before = type(c.before), after = type(c.after);
        if (cls.equals(GT) && c.path.equals("/tile/mTotalRunTime") && before == 4 && after == 4)
            return "ordinary baseline/sample tick bounds and native elapsed-runtime predicate";
        if (cls.equals(GT) && c.path.matches("/tile/gt\\.covers\\[[0-9]+\\]/d/ticksSinceLastRefill") && before == 4 && after == 4)
            return "exact cover native class/id/side, unchanged configuration/energy and ordinary tick/modulo bounds";
        if (cls.equals(PANEL) && c.path.equals("/tile/lastAESampleTick") && before == 4 && after == 4)
            return "ordinary panel sampling configuration/controller/tick scope";
        if (cls.equals(PANEL) && c.path.matches("/tile/screen/(core|min|max)[XYZ]") && before == 3 && after == 3)
            return "exact native screen topology/facing/bounds evidence";
        if (cls.equals(CAP) && c.path.equals("/tile/storedEnergyRF") && before == 3 && after == 3)
            return "actual paid material ItemStack and native placement RF initialization; class/meta/capacity alone do not prove half-capacity";
        if (cls.equals(CAP) && ((c.path.equals("/tile/hasDisplayTypes") && before == 1)
            || (c.path.matches("/tile/faceDisplay[0-5]") && before == 2)))
            return "real native neighbors and complete native display map predicate";
        return null;
    }

    private static final class Change {
        final String path; final Object before, after;
        Change(String path, Object before, Object after) { this.path = path; this.before = before; this.after = after; }
    }
    private static Object change(Change c) throws Exception {
        Object row = compound(); putString(row, "path", c.path); putInt(row, "beforeTagId", type(c.before)); putInt(row, "afterTagId", type(c.after));
        if (c.before != null) put(row, "before", copy(c.before)); if (c.after != null) put(row, "after", copy(c.after)); return row;
    }
    private static List<Change> differences(Object a, Object b, String path) throws Exception {
        List<Change> out = new ArrayList<Change>(); diff(a, b, path, out); return out;
    }
    private static void diff(Object a, Object b, String path, List<Change> out) throws Exception {
        if (same(a, b)) return;
        if (type(a) == 10 && type(b) == 10) { Set<String> names = new TreeSet<String>(keys(a)); names.addAll(keys(b));
            for (String name : names) diff(child(a, name), child(b, name), path + "/" + escape(name), out); return; }
        // Missing compounds are expanded to retain exact changed leaf paths; empty roots remain explicit differences.
        if (a == null && type(b) == 10 && !keys(b).isEmpty() || b == null && type(a) == 10 && !keys(a).isEmpty()) {
            Object present = a == null ? b : a; for (String name : new TreeSet<String>(keys(present)))
                diff(a == null ? null : child(a, name), b == null ? null : child(b, name), path + "/" + escape(name), out); return;
        }
        if (type(a) == 9 && type(b) == 9 && elementType(a) == elementType(b)) {
            List<?> aa = elements(a), bb = elements(b); for (int i = 0; i < Math.max(aa.size(), bb.size()); i++)
                diff(i < aa.size() ? aa.get(i) : null, i < bb.size() ? bb.get(i) : null, path + "[" + i + "]", out); return;
        }
        out.add(new Change(path, a, b));
    }
    private static boolean same(Object a, Object b) throws Exception {
        int t = type(a); if (t != type(b)) return false; if (a == null) return true;
        if (t == 10) { if (!keys(a).equals(keys(b))) return false; for (String key : keys(a)) if (!same(child(a, key), child(b, key))) return false; return true; }
        if (t == 9) { if (elementType(a) != elementType(b)) return false; List<?> aa = elements(a), bb = elements(b); if (aa.size() != bb.size()) return false; for (int i = 0; i < aa.size(); i++) if (!same(aa.get(i), bb.get(i))) return false; return true; }
        if (t == 5) return Float.floatToRawIntBits((Float) call(a, "func_150288_h")) == Float.floatToRawIntBits((Float) call(b, "func_150288_h"));
        if (t == 6) return Double.doubleToRawLongBits((Double) call(a, "func_150286_g")) == Double.doubleToRawLongBits((Double) call(b, "func_150286_g"));
        if (t == 7) return Arrays.equals((byte[]) call(a, "func_150292_c"), (byte[]) call(b, "func_150292_c"));
        if (t == 11) return Arrays.equals((int[]) call(a, "func_150302_c"), (int[]) call(b, "func_150302_c"));
        if (t >= 1 && t <= 4) return number(a) == number(b); if (t == 8) return text(a).equals(text(b));
        throw new IllegalStateException("Unsupported native NBT tag ID " + t);
    }
    private static void row(Object value) throws Exception {
        requireType(value, 10, "row"); if (!ROW_KEYS.containsAll(keys(value))) throw new IllegalArgumentException("unknown explicit row field");
        requireType(child(value, "block"), 8, "row/block"); requireType(child(value, "meta"), 3, "row/meta");
        if (child(value, "tileClass") != null) requireType(child(value, "tileClass"), 8, "row/tileClass");
        if (child(value, "tile") != null) { requireType(child(value, "tile"), 10, "row/tile"); requireType(child(value, "tileClass"), 8, "row/tileClass"); }
    }
    private static int offset(int index, int axis) { return axis == 0 ? index % 40 : axis == 1 ? index / 1600 : index / 40 % 40; }
    private static String parent(String path) { return path.substring(0, path.lastIndexOf('/')); }
    private static Object at(Object root, String path) throws Exception {
        Object value = root;
        for (String segment : path.substring(1).split("/")) {
            int bracket = segment.indexOf('[');
            String key = bracket < 0 ? segment : segment.substring(0, bracket);
            value = child(value, key.replace("~1", "/").replace("~0", "~"));
            if (value == null) return null;
            while (bracket >= 0) {
                int end = segment.indexOf(']', bracket); if (end < 0 || type(value) != 9) return null;
                String digits = segment.substring(bracket + 1, end); if (!digits.matches("[0-9]+")) return null;
                int index = Integer.parseInt(digits); List<?> entries = elements(value);
                if (index >= entries.size()) return null; value = entries.get(index);
                bracket = end + 1 < segment.length() && segment.charAt(end + 1) == '[' ? end + 1 : -1;
                if (bracket < 0 && end + 1 != segment.length()) return null;
            }
        }
        return value;
    }
    private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private static int type(Object value) throws Exception { return value == null ? -1 : ((Byte) call(value, "func_74732_a")).intValue(); }
    private static void requireType(Object value, int expected, String path) throws Exception { if (type(value) != expected) throw new IllegalArgumentException(path + " requires native tag " + expected); }
    private static Object child(Object value, String key) throws Exception { return type(value) == 10 ? call(value, "func_74781_a", key) : null; }
    private static String string(Object value, String key) throws Exception { Object field = child(value, key); return type(field) == 8 ? text(field) : ""; }
    private static String text(Object tag) throws Exception { return (String) call(tag, "func_150285_a_"); }
    private static int integer(Object value, String key, int fallback) throws Exception { Object field = child(value, key); return type(field) == 3 ? (int) number(field) : fallback; }
    private static boolean flag(Object value, String key) throws Exception { Object field = child(value, key); return type(field) == 1 && number(field) == 1; }
    private static long number(Object value) throws Exception { return (Long) call(value, "func_150291_c"); }
    private static int[] intArray(Object value, String key) throws Exception { Object field = child(value, key); return type(field) == 11 ? (int[]) call(field, "func_150302_c") : null; }
    @SuppressWarnings("unchecked") private static Set<String> keys(Object value) throws Exception { return (Set<String>) call(value, "func_150296_c"); }
    private static int count(Object value) throws Exception { return (Integer) call(value, "func_74745_c"); }
    private static int elementType(Object value) throws Exception { return (Integer) call(value, "func_150303_d"); }
    private static List<?> elements(Object value) throws Exception { if (listEntries == null) for (Field field : Class.forName("net.minecraft.nbt.NBTTagList").getDeclaredFields()) if (List.class.isAssignableFrom(field.getType())) { if (listEntries != null) throw new IllegalStateException("ambiguous native list fields"); field.setAccessible(true); listEntries = field; } if (listEntries == null) throw new IllegalStateException("native list elements unavailable"); return (List<?>) listEntries.get(value); }
    private static Object copy(Object value) throws Exception { return call(value, "func_74737_b"); }
    private static Object compound() throws Exception { return construct("net.minecraft.nbt.NBTTagCompound"); }
    private static Object list() throws Exception { return construct("net.minecraft.nbt.NBTTagList"); }
    private static Object tagInt(int value) throws Exception { return construct("net.minecraft.nbt.NBTTagInt", value); }
    private static Object strings(List<String> values) throws Exception { Object out = list(); for (String value : values) append(out, construct("net.minecraft.nbt.NBTTagString", value)); return out; }
    private static void put(Object out, String key, Object value) throws Exception { call(out, "func_74782_a", key, value); }
    private static void append(Object out, Object value) throws Exception { call(out, "func_74742_a", value); }
    private static void putInt(Object out, String key, int value) throws Exception { call(out, "func_74768_a", key, value); }
    private static void putByte(Object out, String key, byte value) throws Exception { call(out, "func_74774_a", key, value); }
    private static void putString(Object out, String key, String value) throws Exception { call(out, "func_74778_a", key, value); }
    private static Set<String> names(String... values) { return Collections.unmodifiableSet(new TreeSet<String>(Arrays.asList(values))); }
    private static Object construct(String name, Object... args) throws Exception { for (Constructor<?> c : Class.forName(name).getDeclaredConstructors()) if (compatible(c.getParameterTypes(), args)) { c.setAccessible(true); return c.newInstance(args); } throw new NoSuchMethodException(name); }
    private static Object call(Object object, String name, Object... args) throws Exception { for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) for (Method method : c.getDeclaredMethods()) if (method.getName().equals(name) && compatible(method.getParameterTypes(), args)) { method.setAccessible(true); return method.invoke(object, args); } throw new NoSuchMethodException(object.getClass().getName() + "." + name); }
    private static boolean compatible(Class<?>[] types, Object[] args) { if (types.length != args.length) return false; for (int i = 0; i < types.length; i++) { Class<?> t = types[i]; if (t.isPrimitive()) t = t == int.class ? Integer.class : t == byte.class ? Byte.class : t == long.class ? Long.class : t; if (args[i] == null ? t.isPrimitive() : !t.isInstance(args[i])) return false; } return true; }
}
