package gtmb.v0111.probe;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Test-only, raw relocation evidence. Reads detached v2 native observations and the actual durable CUT entry.
 * Does not read/write a World, call native proxy/node getters, save a grid, or edit an input tag.
 * A successful proof permits ONLY individually bound Long g replacements on an independent expected-row copy.
 * It is not an inventory/configuration/world PASS or a cut-gap energy-conservation proof.
 */
public final class CompositeAeRawRelocation {
    private static final String QUANTUM = "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode";
    private static final String RELAY = "com.miaokatze.gtswn.common.quantum.QuantumNodeBridgeRelay";
    private static final Set<String> TILES = names(
        "appeng.tile.storage.TileDrive", "appeng.tile.storage.TileChest", "appeng.tile.storage.TileIOPort",
        "appeng.tile.misc.TileInterface", "appeng.tile.misc.TileCondenser", "appeng.tile.misc.TileInscriber",
        "appeng.tile.misc.TileAdvancedInscriber", "appeng.tile.crafting.TileMolecularAssembler",
        "appeng.tile.crafting.TileCraftingTile", "appeng.tile.crafting.TileCraftingStorageTile",
        "appeng.tile.crafting.TileCraftingMonitorTile", "appeng.tile.misc.TileQuartzGrowthAccelerator",
        "appeng.tile.misc.TileCharger", "appeng.tile.misc.TileVibrationChamber", "appeng.tile.misc.TileStorageReshuffle",
        "appeng.tile.misc.TilePatternOptimizationMatrix", "appeng.tile.networking.TileEnergyAcceptor",
        "appeng.tile.networking.TileEnergyCell", "appeng.tile.networking.TileDenseEnergyCell",
        "appeng.tile.networking.TileController", "appeng.tile.networking.TileWireless", QUANTUM,
        "com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel");
    private static final Set<String> GT = names(
        "gregtech.common.tileentities.machines.MTEHatchInputBusME",
        "gregtech.common.tileentities.machines.MTEHatchInputME",
        "gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME",
        "gregtech.common.tileentities.machines.outputme.MTEHatchOutputME",
        "gregtech.common.tileentities.machines.MTEHatchCraftingInputME");
    private static final Set<String> PARTS = names(
        "appeng.parts.networking.PartCable", "appeng.parts.networking.PartCableGlass",
        "appeng.parts.reporting.PartTerminal", "appeng.parts.reporting.PartPatternTerminal",
        "appeng.parts.misc.PartToggleBus", "appeng.parts.networking.PartQuartzFiber", "appeng.parts.p2p.PartP2PTunnelME");
    private static final Set<String> OUTER = names("appeng.parts.misc.PartToggleBus",
        "appeng.parts.networking.PartQuartzFiber", "appeng.parts.p2p.PartP2PTunnelME");
    private static final Set<String> CABLE = names("appeng.tile.networking.TileCableBus",
        "appeng.parts.layers.LayerIEnergyConnected_TileCableBus");
    private CompositeAeRawRelocation() {}

    /** Readiness booleans must come from Witness's current native pending/connection gate, at each graph's sample. */
    public static Proof prepare(Object sourceAE, Object targetAE, Object actualDurableCutEntry,
        int[] sourceOrigin, int[] targetOrigin, boolean sourcePathReady, boolean targetPathReady) throws Exception {
        require(sourcePathReady && targetPathReady, "both explicit current native readiness gates are required");
        require(sourceOrigin != null && targetOrigin != null && sourceOrigin.length == 3 && targetOrigin.length == 3,
            "two exact fixture origins are required");
        require(!Arrays.equals(sourceOrigin, targetOrigin), "only raw relocation to a distinct origin is admitted");
        Graph before = graph(sourceAE, sourceOrigin), after = graph(targetAE, targetOrigin);
        require(after.tick >= before.tick, "target native graph predates the source graph");
        require(before.nodes.keySet().equals(after.nodes.keySet()), "complete native port sets differ");
        require(before.edges.equals(after.edges), "native endpoint/direction/boundary edge sets differ");
        require(before.groups.keySet().equals(after.groups.keySet()), "complete native grid-member groups differ (split/merge/missing)");
        Map<Long, Long> forward = new LinkedHashMap<Long, Long>(), reverse = new LinkedHashMap<Long, Long>();
        Object receipt = compound(), mappings = list(), differences = list(), groups = list();
        for (List<String> members : before.groups.keySet()) {
            Grid old = before.groups.get(members), next = after.groups.get(members);
            require(old.extra == next.extra, "raw live extra bits differ for group " + members);
            require(!forward.containsKey(old.g) || forward.get(old.g).longValue() == next.g, "old g maps to more than one new g");
            require(!reverse.containsKey(next.g) || reverse.get(next.g).longValue() == old.g, "new g merges more than one old g");
            forward.put(old.g, next.g); reverse.put(next.g, old.g);
            Object row = compound(); number(row, "oldG", old.g); number(row, "newG", next.g);
            number(row, "extraRawBits", old.extra); tag(row, "members", strings(members)); append(groups, row);
        }
        Object[] cells = cutCells(actualDurableCutEntry, sourceOrigin);
        Map<Integer, List<Binding>> bindings = new TreeMap<Integer, List<Binding>>();
        Set<String> boundPaths = new HashSet<String>();
        Set<Object> forbidden = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        forbidden.add(actualDurableCutEntry);
        for (Object cell : cells) { forbidden.add(cell); if (has(cell, "tile")) forbidden.add(child(cell, "tile")); }
        int bound = 0, changed = 0, nativeUnpersisted = 0;
        for (String key : before.nodes.keySet()) {
            Node old = before.nodes.get(key), next = after.nodes.get(key);
            require(old.p == next.p && old.k == next.k, "native owner/security identity differs at " + key);
            require(old.persisted == next.persisted && old.internal == next.internal, "port role differs at " + key);
            require(old.host.equals(next.host) && old.machine.equals(next.machine) && old.path.equals(next.path)
                && old.proxy.equals(next.proxy), "native host/proxy/path differs at " + key);
            if (old.quantum != null) {
                require(next.quantum != null, "quantum evidence missing at target " + key);
                for (String name : new String[] {"hasAnchor", "anchorDimension", "anchorX", "anchorY", "anchorZ"})
                    require(raw(old.quantum, name).equals(raw(next.quantum, name)), "raw quantum anchor differs at " + key + "/" + name);
            }
            Object row = compound(); text(row, "port", key); number(row, "oldG", old.g); number(row, "newG", next.g);
            integer(row, "p", old.p); number(row, "k", old.k); bool(row, "internal", old.internal);
            bool(row, "observerDeclaredPersistedPort", old.persisted);
            if (old.internal && old.persisted) {
                int index = old.rx + 40 * old.rz + 1600 * old.ry;
                integer(row, "cellIndex", index); text(row, "nativePath", "tile/" + old.path);
                if (old.machine.equals("appeng.tile.networking.TileEnergyAcceptor")) {
                    // .1080 TileEnergyAcceptor:45-58 overrides both AENetwork NBT hooks without calling super.
                    // Its existing runtime proxy is real; its writer emits internalCurrentPower, but no proxy/g/p/k.
                    require(old.host.equals(old.machine) && old.path.equals("proxy") && old.metaId == -1
                        && old.side == -1 && old.part.isEmpty(), "unreviewed energy-acceptor host/path");
                    durableHost(old, cells[index], sourceOrigin);
                    require(!has(child(cells[index], "tile"), "proxy"), "energy-acceptor durable proxy is unexpected and must remain unknown");
                    typed(child(cells[index], "tile"), "internalCurrentPower", 6);
                    text(row, "durableBinding", "native-writer-does-not-persist-this-proxy");
                    text(row, "nativeWriterEvidence", "AE1080 TileEnergyAcceptor.writeToNBT_AENetwork:54-59 overrides AENetworkTile hook; internalCurrentPower only; no super/proxy write");
                    bool(row, "boundToActualDurableCutEntry", false); nativeUnpersisted++;
                } else {
                    Binding binding = bind(old, next, cells[index], index, sourceOrigin);
                    require(boundPaths.add(index + ":" + old.path), "more than one native port claims a durable NBT path");
                    if (!bindings.containsKey(index)) bindings.put(index, new ArrayList<Binding>());
                    bindings.get(index).add(binding); forbidden.add(binding.originalNode); bound++;
                    bool(row, "boundToActualDurableCutEntry", true);
                }
            } else bool(row, "boundToActualDurableCutEntry", false);
            append(mappings, row);
            if (old.g != next.g) { append(differences, copy(row)); changed++; }
        }
        require(bound > 0, "no actual durable persisted internal AE port was bound");
        text(receipt, "algorithm", "ae-raw-grid-relocation-v1"); text(receipt, "status", "SCOPED_G_RELOCATION_PROVED");
        text(receipt, "scope", "raw-only; exact durable CUT port paths; expected-copy Long g replacement only");
        bool(receipt, "authorizesPass", false); bool(receipt, "sourceCurrentNativeReadiness", true); bool(receipt, "targetCurrentNativeReadiness", true);
        bool(receipt, "cutGapEnergyConservationProved", false);
        text(receipt, "energyLimitation", "zero live extra at these two graph samples only; no cut-instant observer or energy ledger; no EU/owner/security/configuration exclusions");
        text(receipt, "cutEntryId", string(actualDurableCutEntry, "id")); number(receipt, "cutEntrySequence", longValue(actualDurableCutEntry, "sequence"));
        number(receipt, "sourceGraphWorldTick", before.tick); number(receipt, "targetGraphWorldTick", after.tick);
        text(receipt, "sourceGraphSampleLabel", before.label); text(receipt, "targetGraphSampleLabel", after.label);
        tag(receipt, "sourceOrigin", construct("net.minecraft.nbt.NBTTagIntArray", sourceOrigin.clone()));
        tag(receipt, "targetOrigin", construct("net.minecraft.nbt.NBTTagIntArray", targetOrigin.clone()));
        integer(receipt, "boundPersistedPorts", bound); integer(receipt, "changedNativePortGridIds", changed);
        integer(receipt, "exactNativeUnpersistedEnergyAcceptorPorts", nativeUnpersisted);
        text(receipt, "observerPortRoleLimitation", "observer v2 persistedPort is retained as observerDeclaredPersistedPort; exact TileEnergyAcceptor writer does not persist proxy/g/p/k; its runtime identity/topology are compared, no nonexistent NBT g is compared or synthesized");
        tag(receipt, "portMappings", mappings); tag(receipt, "rawGridIdDifferences", differences); tag(receipt, "completeGridGroups", groups);
        tag(receipt, "exactNativeEdges", copy(raw(sourceAE, "edges")));
        return new Proof(bindings, forbidden, receipt, sourceOrigin.clone());
    }

    public static final class Proof {
        private final Map<Integer, List<Binding>> bindings;
        private final Set<Object> forbidden;
        private final Object evidence;
        private final int[] source;
        private Proof(Map<Integer, List<Binding>> bindings, Set<Object> forbidden, Object evidence, int[] source) {
            this.bindings = bindings; this.forbidden = forbidden; this.evidence = evidence; this.source = source;
        }
        /** Call before expected-row normalization. The original durable entry/graph may never be passed here. */
        public Object rewriteExpectedGridIds(Object copiedRow, int index) throws Exception {
            require(index >= 0 && index < 64000, "expected row index outside exact fixture");
            require(!forbidden.contains(copiedRow), "expected row is an original input, not an independent copy");
            require(intValue(copiedRow, "x") == index % 40 && intValue(copiedRow, "y") == index / 1600
                && intValue(copiedRow, "z") == index / 40 % 40, "expected cell offset/index mismatch");
            List<Binding> rows = bindings.get(index);
            if (rows == null) return copiedRow;
            Object tile = child(copiedRow, "tile");
            require(!forbidden.contains(tile), "expected tile aliases an original input");
            List<Object> writes = new ArrayList<Object>();
            for (Binding b : rows) {
                require(string(copiedRow, "tileClass").equals(b.host), "independent expected tileClass does not match proven host at " + index);
                require(string(tile, "id").equals(b.tileId), "expected native tile id differs from durable proof");
                require(intValue(tile, "x") == source[0] + index % 40 && intValue(tile, "y") == source[1] + index / 1600
                    && intValue(tile, "z") == source[2] + index / 40 % 40, "expected native source coordinates differ");
                if (b.metaId >= 0) require(intValue(tile, "mID") == b.metaId, "expected native mID differs");
                Object node = path(tile, b.path);
                require(!forbidden.contains(node), "expected node aliases an original input");
                nodeIdentity(node, b.oldG, b.p, b.k, "expected " + index + "/" + b.path);
                if (b.side >= 0) partDefinition(tile, b.container, b.side, b.partItem, b.partDamage);
                writes.add(node);
            }
            // Validate every path before the first expected-copy write; p/k and every other tag remain exact.
            for (int i = 0; i < rows.size(); i++) number(writes.get(i), "g", rows.get(i).newG);
            return copiedRow;
        }
        public Object receipt() throws Exception { return copy(evidence); }
    }

    private static Binding bind(Node old, Node next, Object cell, int index, int[] source) throws Exception {
        require(old.proxy.equals("appeng.me.helpers.AENetworkProxy") || old.proxy.equals("appeng.me.helpers.AENetworkProxyMultiblock"), "unsupported persisted proxy class " + old.proxy);
        Object tile = child(cell, "tile");
        String tileId = durableHost(old, cell, source);
        String container = "";
        if (GT.contains(old.machine)) {
            require(old.host.equals("gregtech.api.metatileentity.BaseMetaTileEntity") && old.path.equals("proxy") && old.metaId >= 0,
                "unsupported durable GT host/path at " + old.key);
            require(intValue(tile, "mID") == old.metaId, "durable GT mID differs at " + old.key);
            Object prototypes = field(Class.forName("gregtech.api.GregTechAPI"), "METATILEENTITIES");
            require(old.metaId < Array.getLength(prototypes), "durable GT mID outside native registry");
            Object prototype = Array.get(prototypes, old.metaId);
            require(prototype != null && prototype.getClass().getName().equals(old.machine), "durable GT mID does not bind native machine class");
            require(old.side == -1 && old.part.isEmpty(), "GT root cannot claim a part identity");
        } else if (PARTS.contains(old.machine)) {
            require(old.part.equals(old.machine) && old.side >= 0 && old.side <= 6, "missing exact native part class/side");
            String suffix = old.path.endsWith("/part") ? "part" : old.path.endsWith("/outer") ? "outer" : "";
            require(!suffix.isEmpty() && (!suffix.equals("outer") || OUTER.contains(old.part)), "unsupported native part proxy path");
            if (CABLE.contains(old.host)) container = "";
            else { require(old.host.equals(QUANTUM), "unreviewed native part host " + old.host); container = "partContainer"; }
            require(old.path.equals((container.isEmpty() ? "" : container + "/") + "extra:" + old.side + "/" + suffix), "native part path does not match host/side");
            partDefinition(tile, container, old.side, old.item, old.damage);
        } else {
            require(TILES.contains(old.host) && old.machine.equals(old.host) && old.path.equals("proxy")
                && old.metaId == -1 && old.side == -1 && old.part.isEmpty(), "unreviewed persisted tile host/path " + old.key);
        }
        Object node = path(tile, old.path); nodeIdentity(node, old.g, old.p, old.k, "durable " + old.key);
        return new Binding(index, old.path, old.host, tileId, old.metaId, old.side, container, old.item, old.damage,
            old.g, next.g, old.p, old.k, node);
    }

    private static String durableHost(Node node, Object cell, int[] source) throws Exception {
        Object tile = child(cell, "tile"); String id = registeredTileId(node.host);
        require(string(tile, "id").equals(id), "durable native tile id does not bind host class at " + node.key);
        require(intValue(tile, "x") == source[0] + node.rx && intValue(tile, "y") == source[1] + node.ry
            && intValue(tile, "z") == source[2] + node.rz, "durable native source coordinates differ at " + node.key);
        return id;
    }

    private static void partDefinition(Object tile, String container, int side, String item, int damage) throws Exception {
        Object owner = container.isEmpty() ? tile : child(tile, container), def = child(owner, "def:" + side);
        // Native stack loading creates only a detached ItemStack, never an AE part/proxy/node or a World object.
        Object stack = call(Class.forName("net.minecraft.item.ItemStack"), "func_77949_a", def);
        require(stack != null, "durable part definition does not load as a native item");
        Object nativeItem = call(stack, "func_77973_b");
        String actual = String.valueOf(call(field(Class.forName("net.minecraft.item.Item"), "field_150901_e"), "func_148750_c", nativeItem));
        require(item.equals(actual) && ((Integer) call(stack, "func_77960_j")) == damage, "durable part item/damage does not bind native observed part");
    }

    private static String registeredTileId(String name) throws Exception {
        Object registry = field(Class.forName("net.minecraft.tileentity.TileEntity"), "field_145853_j");
        Object id = ((Map<?, ?>) registry).get(Class.forName(name));
        require(id instanceof String && !((String) id).isEmpty(), "native TE class has no exact registered id: " + name);
        return (String) id;
    }

    private static Object[] cutCells(Object entry, int[] origin) throws Exception {
        require(intValue(entry, "dimension") == 1048578 && intValue(entry, "x") == origin[0]
            && intValue(entry, "y") == origin[1] && intValue(entry, "z") == origin[2], "durable CUT source origin/dimension differs");
        require(intValue(entry, "sizeX") == 40 && intValue(entry, "sizeY") == 40 && intValue(entry, "sizeZ") == 40
            && intValue(entry, "selectionVersion") == 1 && intValue(entry, "selectedCells") == 64000, "durable CUT must be the full exact 40-cube");
        require(!string(entry, "id").isEmpty(), "durable CUT identity missing"); longValue(entry, "sequence");
        Object[] result = new Object[64000]; List<?> cells = elements(entry, "cells", 10);
        require(cells.size() == 64000, "durable CUT does not contain all 64000 cells");
        int blocks = 0;
        for (Object cell : cells) {
            int x = intValue(cell, "x"), y = intValue(cell, "y"), z = intValue(cell, "z");
            require(x >= 0 && x < 40 && y >= 0 && y < 40 && z >= 0 && z < 40, "durable CUT offset out of range");
            int index = x + 40 * z + 1600 * y; require(result[index] == null, "durable CUT duplicate offset"); result[index] = cell;
            if (!string(cell, "block").equals("minecraft:air")) blocks++; intValue(cell, "meta");
            if (has(cell, "tile")) child(cell, "tile");
        }
        require(intValue(entry, "blocks") == blocks, "durable CUT non-air count differs from cells");
        return result;
    }

    private static Graph graph(Object tag, int[] origin) throws Exception {
        require(intValue(tag, "schemaVersion") == 2 && string(tag, "algorithm").equals("ae-native-graph-v2"), "complete v2 native graph required");
        require(string(tag, "scope").equals("raw-observation-only") && flag(tag, "evidenceOnly") && !flag(tag, "authorizesPass"), "graph evidence scope differs");
        require(intValue(tag, "dimension") == 1048578 && Arrays.equals(intArray(tag, "origin"), origin)
            && Arrays.equals(intArray(tag, "size"), new int[] {40, 40, 40}), "native graph fixture origin/dimensions differ");
        require(flag(tag, "coverageComplete") && intValue(tag, "unknownCount") == 0 && elements(tag, "unknowns", 8).isEmpty(), "unknown native graph coverage is never empty");
        Graph result = new Graph(); result.tick = longValue(tag, "worldTick"); result.label = string(tag, "sampleLabel");
        int internal = 0;
        for (Object row : elements(tag, "nodes", 10)) {
            Node node = new Node(row); require(flag(row, "located") && flag(row, "gPresent"), "unlocated or storage-less native port");
            require(!result.nodes.containsKey(node.key), "duplicate native structural port");
            boolean inside = intValue(row, "dimension") == 1048578 && node.x >= origin[0] && node.x < origin[0] + 40
                && node.y >= origin[1] && node.y < origin[1] + 40 && node.z >= origin[2] && node.z < origin[2] + 40;
            require(inside == node.internal, "native internal/external coordinate role differs");
            require(node.rx == (inside ? node.x - origin[0] : node.x) && node.ry == (inside ? node.y - origin[1] : node.y)
                && node.rz == (inside ? node.z - origin[2] : node.z), "native relative coordinate differs");
            String key = (inside ? "relative:" : "absolute:") + intValue(row, "dimension") + ":" + node.rx + "," + node.ry + "," + node.rz
                + "|" + node.host + "|" + node.machine + "|mID=" + node.metaId + "|" + node.path + "|" + node.part + "|" + node.item + "@" + node.damage;
            require(key.equals(node.key), "native structural key does not bind exact host/path");
            if (!node.persisted) {
                require(node.machine.equals(RELAY) && node.proxy.equals(RELAY) && node.path.equals("@runtime/quantumBridgeRelay"), "unreviewed runtime port");
                for (String name : new String[] {"relayTopologyComplete", "relayOwnerPlayerMatches", "relayAttachmentMatches",
                    "relayLocalEdgeLive", "relayAnchorEdgeLive", "relaySameGrid", "relayAnchorLocationMatches"})
                    require(flag(row, name), "incomplete runtime relay predicate " + name);
            }
            if (node.machine.equals(QUANTUM)) {
                node.quantum = child(row, "quantumBridge");
                require(flag(node.quantum, "proxyReady"), "native quantum proxy is not ready");
                if (flag(node.quantum, "hasAnchor")) require(flag(node.quantum, "linkedByExistingMembership"), "native configured quantum node is unlinked");
            }
            result.nodes.put(node.key, node); if (inside) internal++;
        }
        require(!result.nodes.isEmpty() && internal > 0 && intValue(tag, "nodeCount") == result.nodes.size()
            && intValue(tag, "internalNodeCount") == internal, "native port counts differ or empty sample");
        Map<Integer, Grid> byIndex = new LinkedHashMap<Integer, Grid>(); Set<String> grouped = new HashSet<String>(); Set<Long> ids = new HashSet<Long>();
        for (Object row : elements(tag, "grids", 10)) {
            Grid grid = new Grid(); grid.index = intValue(row, "observedGrid"); grid.g = longValue(row, "g"); grid.extra = longValue(row, "extraRawBits");
            require(flag(row, "gPresent") && ids.add(grid.g), "native GridStorage id is missing or reused by distinct grids");
            require(flag(row, "energyCacheKnown") && flag(row, "extraFinite") && flag(row, "extraZero")
                && Double.longBitsToDouble(grid.extra) == 0.0D, "nonzero/unknown/nonfinite extra requires an independent energy ledger");
            require(intValue(row, "energyProviderEntries") == 0, "native energy-provider coupling is not covered");
            require(flag(row, "pathCacheKnown") && !flag(row, "networkBooting")
                && !string(row, "controllerState").equals("CONTROLLER_CONFLICT"), "native path cache is unknown, booting or conflicting");
            List<String> members = new ArrayList<String>(); boolean selected = false;
            for (Object member : elements(row, "members", 8)) {
                String key = (String) call(member, "func_150285_a_"); Node node = result.nodes.get(key);
                require(node != null && grouped.add(key) && node.grid == grid.index && node.g == grid.g, "native complete grid membership disagrees with node");
                members.add(key); selected |= node.internal;
            }
            Collections.sort(members); require(selected && !byIndex.containsKey(grid.index), "unselected/duplicate native grid");
            require(!result.groups.containsKey(members), "duplicate native member group"); result.groups.put(members, grid); byIndex.put(grid.index, grid);
        }
        require(grouped.equals(result.nodes.keySet()) && intValue(tag, "selectedGridCount") == result.groups.size(), "native graph has ungrouped ports or grid count differs");
        Set<List<String>> endpointPairs = new HashSet<List<String>>();
        for (Object row : elements(tag, "edges", 10)) {
            require(keys(row).equals(names("a", "b", "directionA", "directionB", "hasDirection", "scope")), "unknown native edge fields");
            String a = string(row, "a"), b = string(row, "b"); Node first = result.nodes.get(a), second = result.nodes.get(b);
            int da = intValue(row, "directionA"), db = intValue(row, "directionB"); boolean directional = flag(row, "hasDirection");
            require(first != null && second != null && a.compareTo(b) < 0 && first.grid == second.grid, "native edge endpoints/group/order differ");
            require(da >= 0 && da <= 6 && db >= 0 && db <= 6 && directional == (da != 6 && db != 6)
                && (!directional || (da ^ 1) == db), "native edge directions differ");
            String scope = string(row, "scope"); require(scope.equals(first.internal && second.internal ? "internal" : "absolute-boundary-or-external"), "native boundary edge role differs");
            // String lengths make arbitrary delimiters in an endpoint unambiguous.
            require(result.edges.add(a.length() + ":" + a + b.length() + ":" + b + ":" + da + ":" + db + ":" + directional + ":" + scope), "duplicate native edge");
            endpointPairs.add(Arrays.asList(a, b));
        }
        for (Node relay : result.nodes.values()) if (!relay.persisted) {
            String ownerKey = string(relay.row, "relayOwnerPort"), targetKey = string(relay.row, "relayTargetPort");
            Node owner = result.nodes.get(ownerKey), peer = result.nodes.get(targetKey);
            require(owner != null && peer != null && owner.machine.equals(QUANTUM) && owner.quantum != null
                && owner.persisted && owner.dim == relay.dim && owner.x == relay.x && owner.y == relay.y && owner.z == relay.z,
                "runtime relay does not bind its exact native owner host");
            require(owner.grid == relay.grid && peer.grid == relay.grid && owner.p == relay.p
                && connected(endpointPairs, owner.key, relay.key) && connected(endpointPairs, relay.key, peer.key), "runtime relay's two edges or owner/security actor disagree");
            require(string(owner.quantum, "relayPort").equals(relay.key) && string(owner.quantum, "targetPort").equals(peer.key), "quantum owner pointer does not bind this relay and target");
            require(peer.machine.equals("appeng.tile.networking.TileController") && peer.persisted && peer.path.equals("proxy")
                && intValue(relay.row, "relayAnchorDimension") == peer.dim && intValue(relay.row, "relayAnchorX") == peer.x
                && intValue(relay.row, "relayAnchorY") == peer.y && intValue(relay.row, "relayAnchorZ") == peer.z,
                "runtime relay native anchor location does not bind the actual controller host");
            require(flag(owner.quantum, "hasAnchor") && intValue(owner.quantum, "anchorDimension") == peer.dim
                && intValue(owner.quantum, "anchorX") == peer.x && intValue(owner.quantum, "anchorY") == peer.y
                && intValue(owner.quantum, "anchorZ") == peer.z, "quantum configured anchor differs from the relay target host");
        }
        return result;
    }

    private static boolean connected(Set<List<String>> pairs, String a, String b) {
        return pairs.contains(a.compareTo(b) < 0 ? Arrays.asList(a, b) : Arrays.asList(b, a));
    }

    private static void nodeIdentity(Object node, long g, int p, long k, String label) throws Exception {
        require(keys(node).equals(names("g", "p", "k")), "unreviewed native GridNode schema at " + label);
        require(longValue(node, "g") == g && intValue(node, "p") == p && longValue(node, "k") == k, "durable/expected exact Long g, Int p, Long k differs at " + label);
    }
    private static Object path(Object tile, String path) throws Exception { Object at = tile; for (String key : path.split("/")) at = child(at, key); return at; }
    private static final class Binding {
        final int index, metaId, side, partDamage, p; final long oldG, newG, k;
        final String path, host, tileId, container, partItem; final Object originalNode;
        Binding(int index, String path, String host, String tileId, int metaId, int side, String container, String partItem, int partDamage,
            long oldG, long newG, int p, long k, Object originalNode) {
            this.index=index;this.path=path;this.host=host;this.tileId=tileId;this.metaId=metaId;this.side=side;this.container=container;
            this.partItem=partItem;this.partDamage=partDamage;this.oldG=oldG;this.newG=newG;this.p=p;this.k=k;this.originalNode=originalNode;
        }
    }
    private static final class Node {
        final String key, host, machine, path, proxy, part, item; final int dim,x,y,z,rx,ry,rz,metaId,side,damage,p,grid;
        final long g,k; final boolean internal,persisted; final Object row; Object quantum;
        Node(Object row) throws Exception {
            this.row=row;dim=intValue(row,"dimension");
            key=string(row,"key");host=string(row,"hostTileClass");machine=string(row,"machineClass");path=string(row,"portPath");
            proxy=string(row,"proxyClass");part=string(row,"partClass");item=string(row,"partItem");
            x=intValue(row,"x");y=intValue(row,"y");z=intValue(row,"z");rx=intValue(row,"relativeX");ry=intValue(row,"relativeY");rz=intValue(row,"relativeZ");
            metaId=intValue(row,"metaTileId");side=intValue(row,"side");damage=intValue(row,"partDamage");p=intValue(row,"p");grid=intValue(row,"observedGrid");
            g=longValue(row,"g");k=longValue(row,"k");internal=flag(row,"internal");persisted=flag(row,"persistedPort");
        }
    }
    private static final class Grid { int index; long g,extra; }
    private static final class Graph {
        long tick; String label;
        final Map<String,Node> nodes=new TreeMap<String,Node>();
        final Map<List<String>,Grid> groups=new LinkedHashMap<List<String>,Grid>();
        final Set<String> edges=new TreeSet<String>();
    }

    private static Set<String> names(String... names) { return new HashSet<String>(Arrays.asList(names)); }
    private static Object raw(Object tag,String key)throws Exception { Object value=call(tag,"func_74781_a",key);require(value!=null,"missing native tag "+key);return value; }
    private static Object typed(Object tag,String key,int type)throws Exception { Object value=raw(tag,key);require(((Byte)call(value,"func_74732_a")).intValue()==type,"wrong native tag type at "+key+", expected "+type);return value; }
    private static boolean has(Object tag,String key)throws Exception { return (Boolean)call(tag,"func_74764_b",key); }
    private static Object child(Object tag,String key)throws Exception { return typed(tag,key,10); }
    private static String string(Object tag,String key)throws Exception { typed(tag,key,8);return (String)call(tag,"func_74779_i",key); }
    private static int intValue(Object tag,String key)throws Exception { typed(tag,key,3);return (Integer)call(tag,"func_74762_e",key); }
    private static long longValue(Object tag,String key)throws Exception { typed(tag,key,4);return (Long)call(tag,"func_74763_f",key); }
    private static int[] intArray(Object tag,String key)throws Exception { typed(tag,key,11);return (int[])call(tag,"func_74759_k",key); }
    private static boolean flag(Object tag,String key)throws Exception { typed(tag,key,1);byte b=(Byte)call(tag,"func_74771_c",key);require(b==0||b==1,"nonboolean native byte "+key);return b==1; }
    @SuppressWarnings("unchecked") private static Set<String> keys(Object tag)throws Exception { return (Set<String>)call(tag,"func_150296_c"); }
    private static List<?> elements(Object tag,String key,int elementType)throws Exception {
        Object list=typed(tag,key,9);List<?> rows=(List<?>)field(list,"field_74747_a");
        for(Object row:rows)require(((Byte)call(row,"func_74732_a")).intValue()==elementType,"wrong native list element type at "+key);
        return rows;
    }
    private static Object compound()throws Exception { return construct("net.minecraft.nbt.NBTTagCompound"); }
    private static Object list()throws Exception { return construct("net.minecraft.nbt.NBTTagList"); }
    private static Object copy(Object tag)throws Exception { return call(tag,"func_74737_b"); }
    private static Object strings(List<String> values)throws Exception { Object out=list();for(String value:values)append(out,construct("net.minecraft.nbt.NBTTagString",value));return out; }
    private static void text(Object tag,String key,String value)throws Exception { call(tag,"func_74778_a",key,value); }
    private static void integer(Object tag,String key,int value)throws Exception { call(tag,"func_74768_a",key,value); }
    private static void number(Object tag,String key,long value)throws Exception { call(tag,"func_74772_a",key,value); }
    private static void bool(Object tag,String key,boolean value)throws Exception { call(tag,"func_74757_a",key,value); }
    private static void tag(Object tag,String key,Object value)throws Exception { call(tag,"func_74782_a",key,value); }
    private static void append(Object list,Object value)throws Exception { call(list,"func_74742_a",value); }
    private static void require(boolean condition,String reason) { if(!condition)throw new IllegalStateException("AE raw relocation proof rejected: "+reason); }
    private static Object field(Object object,String name)throws Exception {
        Class<?> type=object instanceof Class?(Class<?>)object:object.getClass();
        for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object instanceof Class?null:object);}catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(type.getName()+"."+name);
    }
    private static Object construct(String name,Object... args)throws Exception {
        for(Constructor<?> c:Class.forName(name).getDeclaredConstructors())if(compatible(c.getParameterTypes(),args)){c.setAccessible(true);return c.newInstance(args);}
        throw new NoSuchMethodException(name+" constructor");
    }
    private static Object call(Object object,String name,Object... args)throws Exception {
        Class<?> type=object instanceof Class?(Class<?>)object:object.getClass();
        for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args)){m.setAccessible(true);return m.invoke(object instanceof Class?null:object,args);}
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    private static boolean compatible(Class<?>[] types,Object[] args) {
        if(types.length!=args.length)return false;
        for(int i=0;i<types.length;i++){if(args[i]==null){if(types[i].isPrimitive())return false;continue;}Class<?> t=types[i];if(t.isPrimitive())t=t==int.class?Integer.class:t==long.class?Long.class:t==boolean.class?Boolean.class:t;if(!t.isInstance(args[i]))return false;}return true;
    }
}
