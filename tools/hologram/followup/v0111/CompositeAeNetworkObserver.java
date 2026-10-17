package gtmb.v0111.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit test-only observation of existing AE .1080 objects in the owned composite fixture.
 * No registration, node creation, native NBT writer, saveState, network update or file I/O occurs here.
 * The caller must invoke snapshot from its natural FML server-tick callback, outside business timing.
 * This evidence never authorizes a world/material PASS or removes a native identity difference.
 */
public final class CompositeAeNetworkObserver {
    private static final Set<String> TILES = names(
        "appeng.tile.storage.TileDrive", "appeng.tile.storage.TileChest", "appeng.tile.storage.TileIOPort",
        "appeng.tile.misc.TileInterface", "appeng.tile.misc.TileCondenser", "appeng.tile.misc.TileInscriber",
        "appeng.tile.misc.TileAdvancedInscriber", "appeng.tile.crafting.TileMolecularAssembler",
        "appeng.tile.crafting.TileCraftingTile", "appeng.tile.crafting.TileCraftingStorageTile",
        "appeng.tile.crafting.TileCraftingMonitorTile", "appeng.tile.misc.TileQuartzGrowthAccelerator",
        "appeng.tile.misc.TileCharger", "appeng.tile.misc.TileVibrationChamber",
        "appeng.tile.misc.TileStorageReshuffle", "appeng.tile.misc.TilePatternOptimizationMatrix",
        "appeng.tile.networking.TileEnergyAcceptor", "appeng.tile.networking.TileEnergyCell",
        "appeng.tile.networking.TileDenseEnergyCell", "appeng.tile.networking.TileController",
        "appeng.tile.networking.TileWireless",
        "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode",
        "com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel");
    private static final Set<String> GT_MACHINES = names(
        "gregtech.common.tileentities.machines.MTEHatchInputBusME",
        "gregtech.common.tileentities.machines.MTEHatchInputME",
        "gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME",
        "gregtech.common.tileentities.machines.outputme.MTEHatchOutputME",
        "gregtech.common.tileentities.machines.MTEHatchCraftingInputME");
    private static final Set<String> PARTS = names(
        "appeng.parts.networking.PartCable", "appeng.parts.networking.PartCableGlass", "appeng.parts.reporting.PartTerminal",
        "appeng.parts.reporting.PartPatternTerminal",
        "appeng.parts.misc.PartToggleBus", "appeng.parts.networking.PartQuartzFiber",
        "appeng.parts.p2p.PartP2PTunnelME");
    private static final Set<String> OUTER_PARTS = names(
        "appeng.parts.misc.PartToggleBus", "appeng.parts.networking.PartQuartzFiber",
        "appeng.parts.p2p.PartP2PTunnelME");
    private static final Set<String> CABLE_TILES = names(
        "appeng.tile.networking.TileCableBus", "appeng.parts.layers.LayerIEnergyConnected_TileCableBus");
    private static final Set<String> PROXIES = names(
        "appeng.me.helpers.AENetworkProxy", "appeng.me.helpers.AENetworkProxyMultiblock");
    private static final String QUANTUM_NODE = "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode";
    private static final String QUANTUM_RELAY = "com.miaokatze.gtswn.common.quantum.QuantumNodeBridgeRelay";

    private final Object world;
    private final int[] origin;
    private final List<String> unknowns = new ArrayList<String>();
    private final List<Graph> graphs = new ArrayList<Graph>();
    private final Map<Object, Node> nodes = new IdentityHashMap<Object, Node>();

    private CompositeAeNetworkObserver(Object world, int[] origin) {
        this.world = world;
        this.origin = origin.clone();
    }

    /** Returns a detached native compound; root owns any later evidence-file serialization. */
    public static Object snapshot(Object world, int[] origin, String sampleLabel) throws Exception {
        check(world != null && Class.forName("net.minecraft.world.WorldServer").isInstance(world), "native WorldServer required");
        check(origin != null && origin.length == 3, "exact 40-cube origin required");
        check(!((Boolean) get(world, "field_72995_K")), "client world forbidden");
        check((Integer) get(get(world, "field_73011_w"), "field_76574_g") == 1048578, "owned fixture dimension required");
        // .1080 getGridList selects its server repository using effective side. Admit only the normal server callback.
        check("Server thread".equals(Thread.currentThread().getName()), "natural server thread required");
        Object common = call(Class.forName("cpw.mods.fml.common.FMLCommonHandler"), "instance");
        check("SERVER".equals(String.valueOf(call(common, "getEffectiveSide"))), "effective server side required");
        check(sampleLabel != null && !sampleLabel.isEmpty(), "explicit sample label required");
        return new CompositeAeNetworkObserver(world, origin).observe(sampleLabel);
    }

    private Object observe(String label) throws Exception {
        Object tick = get(Class.forName("appeng.hooks.TickHandler"), "INSTANCE");
        Iterable<?> existing = (Iterable<?>) call(tick, "getGridList");
        int gridIndex = 0;
        Set<Object> seenGrids = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object grid : existing) {
            if (!seenGrids.add(grid)) continue;
            check(++gridIndex <= 16384, "existing-grid observation limit exceeded");
            Graph graph = new Graph(grid, gridIndex);
            graphs.add(graph);
            if (!"appeng.me.Grid".equals(grid.getClass().getName())) {
                unknowns.add("grid[" + gridIndex + "]: unsupported native grid class " + grid.getClass().getName());
                continue;
            }
            for (Object value : (Iterable<?>) call(grid, "getNodes")) {
                check(nodes.size() < 262144, "existing-node observation limit exceeded");
                Node node = nodes.get(value);
                if (node != null) {
                    unknowns.add("grid[" + gridIndex + "]: same node appears in more than one grid");
                } else {
                    node = describe(value);
                    if (node.key.isEmpty()) node.key = "unlocated:" + nodes.size();
                    putText(node.row, "key", node.key);
                    putBool(node.row, "located", node.located);
                    putBool(node.row, "internal", node.internal);
                    nodes.put(value, node);
                }
                graph.members.add(node);
                if (node.internal) graph.selected = true;
                if (!node.located) graph.unlocated = true;
            }
        }

        Object result = nbt(), nodeRows = list(), edgeRows = list(), gridRows = list();
        int internalCount = 0, selectedCount = 0, nodeCount = 0;
        Set<String> keys = new HashSet<String>();
        Set<Object> seenEdges = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Graph graph : graphs) {
            if (!graph.selected) {
                if (graph.unlocated) {
                    unknowns.add("grid[" + graph.index + "]: unlocated node prevents proving this network is outside the selected cube");
                    for (Node node : graph.members) if (!node.located) unknowns.addAll(node.problems);
                }
                continue;
            }
            selectedCount++;
            for (Node node : graph.members) {
                if (node.quantumOwner != null) describeQuantumMembership(node);
                if (node.relayOwner != null) describeRelayMembership(node);
                if (!node.problems.isEmpty()) unknowns.addAll(node.problems);
                if (!keys.add(node.key)) unknowns.add("duplicate structural port key: " + node.key);
                if (node.internal) internalCount++;
                putInt(node.row, "observedGrid", graph.index);
                append(nodeRows, node.row);
                nodeCount++;
                if (node.value == null || !"appeng.me.GridNode".equals(node.value.getClass().getName())) continue;
                if (get(node.value, "myGrid") != graph.value) unknowns.add(node.key + ": node.myGrid disagrees with existing Grid membership");
                for (Object edge : (Iterable<?>) call(node.value, "getConnections")) {
                    if (!seenEdges.add(edge)) continue;
                    if (!"appeng.me.GridConnection".equals(edge.getClass().getName())) {
                        unknowns.add(node.key + ": unsupported connection class " + edge.getClass().getName());
                        continue;
                    }
                    Object a = call(edge, "a"), b = call(edge, "b");
                    Node first = nodes.get(a), second = nodes.get(b);
                    if (first == null || second == null || first.key.isEmpty() || second.key.isEmpty()) {
                        unknowns.add(node.key + ": connection endpoint absent from existing network inventory");
                        continue;
                    }
                    if (get(a, "myGrid") != graph.value || get(b, "myGrid") != graph.value) {
                        unknowns.add(node.key + ": connection endpoints disagree with this existing grid");
                        continue;
                    }
                    if (first.key.compareTo(second.key) > 0) { Node swap = first; first = second; second = swap; }
                    Object row = nbt();
                    putText(row, "a", first.key); putText(row, "b", second.key);
                    putInt(row, "directionA", ((Enum<?>) call(edge, "getDirection", first.value)).ordinal());
                    putInt(row, "directionB", ((Enum<?>) call(edge, "getDirection", second.value)).ordinal());
                    putBool(row, "hasDirection", (Boolean) call(edge, "hasDirection"));
                    putText(row, "scope", first.internal && second.internal ? "internal" : "absolute-boundary-or-external");
                    append(edgeRows, row);
                }
            }
            append(gridRows, describeGrid(graph));
        }
        Object issues = list();
        for (String problem : unknowns) append(issues, construct("net.minecraft.nbt.NBTTagString", problem));
        putInt(result, "schemaVersion", 2); putText(result, "algorithm", "ae-native-graph-v2");
        putText(result, "scope", "raw-observation-only"); putText(result, "sampleLabel", label);
        putBool(result, "evidenceOnly", true); putBool(result, "authorizesPass", false);
        putText(result, "serverThread", Thread.currentThread().getName());
        putInt(result, "dimension", 1048578); tag(result, "origin", construct("net.minecraft.nbt.NBTTagIntArray", origin.clone()));
        tag(result, "size", construct("net.minecraft.nbt.NBTTagIntArray", new int[] {40, 40, 40}));
        putLong(result, "worldTick", (Long) call(world, "func_82737_E"));
        putInt(result, "existingGridCount", gridIndex); putInt(result, "selectedGridCount", selectedCount);
        putInt(result, "internalNodeCount", internalCount); putInt(result, "nodeCount", nodeCount);
        putInt(result, "unknownCount", unknowns.size()); putBool(result, "coverageComplete", unknowns.isEmpty());
        tag(result, "nodes", nodeRows); tag(result, "edges", edgeRows); tag(result, "grids", gridRows); tag(result, "unknowns", issues);
        putText(result, "limitations", "existing networks only; no independent inventory/config/pattern/EU conservation proof; no self/share boundary exceptions; nonzero extra remains an open ledger requirement; sample time is exactly this observation, not an inferred capture time");
        return result;
    }

    private Node describe(Object value) throws Exception {
        Node node = new Node(value, nbt());
        if (!"appeng.me.GridNode".equals(value.getClass().getName())) {
            node.problems.add("unsupported node class " + value.getClass().getName());
            return node;
        }
        // Preserve native identities even when the host/path below is outside reviewed coverage.
        putInt(node.row, "p", (Integer) get(value, "playerID")); putLong(node.row, "k", (Long) get(value, "lastSecurityKey"));
        Object storage = get(value, "myStorage"); putBool(node.row, "gPresent", storage != null);
        if (storage == null) node.problems.add("existing native node has no GridStorage");
        else putLong(node.row, "g", (Long) call(storage, "getID"));
        Object proxy = call(value, "getGridBlock");
        if (QUANTUM_RELAY.equals(proxy.getClass().getName())) return describeRelay(node, proxy);
        if (!PROXIES.contains(proxy.getClass().getName())) {
            node.problems.add("unsupported proxy class " + proxy.getClass().getName());
            return node;
        }
        Object machine = get(proxy, "gp");
        String machineClass = machine.getClass().getName(), port = (String) get(proxy, "nbtName");
        Object tile = null;
        String path = port, partClass = "", partItem = "";
        int side = -1, damage = -1, metaId = -1;
        if (Class.forName("net.minecraft.tileentity.TileEntity").isInstance(machine)) {
            tile = machine;
            if (!TILES.contains(machineClass)) node.problems.add("unsupported fixture tile class " + machineClass);
            if (!"proxy".equals(port)) node.problems.add(machineClass + ": unsupported native proxy root " + port);
        } else if (Class.forName("appeng.parts.AEBasePart").isInstance(machine)) {
            tile = get(machine, "tile"); partClass = machineClass;
            if (!PARTS.contains(machineClass)) node.problems.add("unsupported fixture part class " + machineClass);
            Object direction = get(machine, "side");
            if (direction != null) side = ((Enum<?>) direction).ordinal();
            if (side < 0 || side > 6) node.problems.add(machineClass + ": missing native part side");
            if (!"part".equals(port) && !("outer".equals(port) && OUTER_PARTS.contains(machineClass)))
                node.problems.add(machineClass + ": unsupported native part proxy root " + port);
            Object itemStack = get(machine, "is");
            Object item = call(itemStack, "func_77973_b");
            partItem = String.valueOf(call(get(Class.forName("net.minecraft.item.Item"), "field_150901_e"), "func_148750_c", item));
            damage = (Integer) call(itemStack, "func_77960_j");
            String container = tile == null ? "" : tile.getClass().getName();
            if (CABLE_TILES.contains(container)) path = "extra:" + side + "/" + port;
            else if ("com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode".equals(container))
                path = "partContainer/extra:" + side + "/" + port;
            else {
                path = "UNRESOLVED_CONTAINER/extra:" + side + "/" + port;
                node.problems.add("unsupported part container " + container + "; FMP list identity is not yet covered");
            }
        } else if (Class.forName("gregtech.api.metatileentity.MetaTileEntity").isInstance(machine)) {
            // This final .205 getter returns its existing mBaseMetaTileEntity field, never creates a proxy.
            tile = call(machine, "getBaseMetaTileEntity");
            if (!GT_MACHINES.contains(machineClass)) node.problems.add("unsupported fixture GT MTE class " + machineClass);
            if (!"proxy".equals(port)) node.problems.add(machineClass + ": unsupported GT proxy root " + port);
            if (tile != null) metaId = ((Number) get(tile, "mID")).intValue();
        } else node.problems.add("unsupported proxy machine class " + machineClass);
        if (QUANTUM_NODE.equals(machineClass)) {
            node.quantumOwner = machine;
            if (get(machine, "gridProxy") != proxy || get(proxy, "node") != value)
                node.problems.add("quantum node proxy does not own this existing node");
        }
        putBool(node.row, "persistedPort", true);
        return finishHost(node, tile, machineClass, proxy.getClass().getName(), path, partClass, partItem, side, damage, metaId);
    }

    private Node finishHost(Node node, Object tile, String machineClass, String proxyClass, String path,
        String partClass, String partItem, int side, int damage, int metaId) throws Exception {
        if (tile == null) { node.problems.add(machineClass + ": no existing host tile"); return node; }
        Object tileWorld = get(tile, "field_145850_b");
        if (tileWorld == null) { node.problems.add(machineClass + ": host has no existing world"); return node; }
        int dimension = (Integer) get(get(tileWorld, "field_73011_w"), "field_76574_g");
        int x = (Integer) get(tile, "field_145851_c"), y = (Integer) get(tile, "field_145848_d"), z = (Integer) get(tile, "field_145849_e");
        node.located = true;
        node.internal = tileWorld == world && x >= origin[0] && x < origin[0] + 40 && y >= origin[1] && y < origin[1] + 40 && z >= origin[2] && z < origin[2] + 40;
        int kx = node.internal ? x - origin[0] : x, ky = node.internal ? y - origin[1] : y, kz = node.internal ? z - origin[2] : z;
        node.key = (node.internal ? "relative:" : "absolute:") + dimension + ":" + kx + "," + ky + "," + kz
            + "|" + tile.getClass().getName() + "|" + machineClass + "|mID=" + metaId + "|" + path
            + "|" + partClass + "|" + partItem + "@" + damage;
        putText(node.row, "key", node.key); putBool(node.row, "internal", node.internal);
        putInt(node.row, "dimension", dimension); putInt(node.row, "x", x); putInt(node.row, "y", y); putInt(node.row, "z", z);
        putInt(node.row, "relativeX", kx); putInt(node.row, "relativeY", ky); putInt(node.row, "relativeZ", kz);
        putText(node.row, "hostTileClass", tile.getClass().getName()); putText(node.row, "machineClass", machineClass);
        putText(node.row, "proxyClass", proxyClass); putText(node.row, "portPath", path);
        putInt(node.row, "metaTileId", metaId); putInt(node.row, "side", side);
        putText(node.row, "partClass", partClass); putText(node.row, "partItem", partItem); putInt(node.row, "partDamage", damage);
        return node;
    }

    /** Relay IGridBlock is a runtime port owned by a quantum TE, not an AENetworkProxy or a saved NBT root. */
    private Node describeRelay(Node node, Object relay) throws Exception {
        Object owner = get(relay, "owner");
        if (owner == null || !QUANTUM_NODE.equals(owner.getClass().getName())) {
            node.problems.add("quantum relay has no exact native quantum-node owner");
            return node;
        }
        node.relayOwner = owner;
        node.relay = relay;
        if (get(relay, "node") != node.value || get(owner, "bridgeRelayNode") != node.value)
            node.problems.add("quantum relay attachment/owner identity mismatch");
        Object location = get(relay, "location");
        if (location == null || !"appeng.api.util.DimensionalCoord".equals(location.getClass().getName()))
            node.problems.add("quantum relay has no exact stored native anchor location");
        else {
            putInt(node.row, "relayAnchorDimension", (Integer) get(location, "dimId"));
            putInt(node.row, "relayAnchorX", (Integer) get(location, "x"));
            putInt(node.row, "relayAnchorY", (Integer) get(location, "y"));
            putInt(node.row, "relayAnchorZ", (Integer) get(location, "z"));
        }
        putBool(node.row, "persistedPort", false);
        // Structural identity follows the real owner at its relative position. Native relay location remains absolute.
        return finishHost(node, owner, QUANTUM_RELAY, QUANTUM_RELAY, "@runtime/quantumBridgeRelay", "", "", -1, -1, -1);
    }

    private void describeRelayMembership(Node relayNode) throws Exception {
        Object owner = relayNode.relayOwner, proxy = get(owner, "gridProxy");
        Object ownNode = proxy == null ? null : get(proxy, "node");
        Object target = get(owner, "bridgeTargetNode"), connection = get(owner, "connection"), anchor = get(owner, "anchorConnection");
        Node own = nodes.get(ownNode), peer = nodes.get(target);
        boolean attached = get(relayNode.relay, "node") == relayNode.value && get(owner, "bridgeRelayNode") == relayNode.value;
        boolean local = edgeBetween(connection, ownNode, relayNode.value);
        boolean remote = edgeBetween(anchor, relayNode.value, target);
        boolean grouped = ownNode != null && target != null && get(ownNode, "myGrid") == get(relayNode.value, "myGrid")
            && get(target, "myGrid") == get(relayNode.value, "myGrid");
        boolean anchorLocation = peer != null && peer.located
            && getInt(relayNode.row, "relayAnchorDimension") == getInt(peer.row, "dimension")
            && getInt(relayNode.row, "relayAnchorX") == getInt(peer.row, "x")
            && getInt(relayNode.row, "relayAnchorY") == getInt(peer.row, "y")
            && getInt(relayNode.row, "relayAnchorZ") == getInt(peer.row, "z");
        putText(relayNode.row, "relayOwnerPort", own == null ? "" : own.key);
        putText(relayNode.row, "relayTargetPort", peer == null ? "" : peer.key);
        putBool(relayNode.row, "relayAttachmentMatches", attached);
        putBool(relayNode.row, "relayLocalEdgeLive", local); putBool(relayNode.row, "relayAnchorEdgeLive", remote);
        putBool(relayNode.row, "relaySameGrid", grouped); putBool(relayNode.row, "relayAnchorLocationMatches", anchorLocation);
        putBool(relayNode.row, "relayOwnerPlayerMatches", ownNode != null
            && get(ownNode, "playerID").equals(get(relayNode.value, "playerID")));
        boolean complete = attached && local && remote && grouped && anchorLocation && own != null && peer != null;
        putBool(relayNode.row, "relayTopologyComplete", complete);
        if (!complete) relayNode.problems.add(relayNode.key + ": native relay attachment, two live edges, anchor location or shared grid is incomplete");
    }

    /** Reproduce only the server's pure membership predicates, without its isLinked()->getNode() getter. */
    private void describeQuantumMembership(Node node) throws Exception {
        Object owner = node.quantumOwner, connection = get(owner, "connection"), anchor = get(owner, "anchorConnection");
        Object relay = get(owner, "bridgeRelayNode"), target = get(owner, "bridgeTargetNode");
        int dimension = (Integer) get(owner, "anchorDim");
        boolean hasAnchor = dimension != Integer.MIN_VALUE;
        if (relay != null && !nodes.containsKey(relay)) node.problems.add(node.key + ": quantum relay pointer is absent from existing grid nodes");
        if (target != null && !nodes.containsKey(target)) node.problems.add(node.key + ": quantum target pointer is absent from existing grid nodes");
        boolean coreLive = contains(node.value, connection);
        boolean linked = coreLive && (relay == null || target != null && contains(relay, connection)
            && contains(relay, anchor) && contains(target, anchor)
            && get(node.value, "myGrid") == get(relay, "myGrid") && get(node.value, "myGrid") == get(target, "myGrid"));
        Object row = nbt();
        putBool(row, "hasAnchor", hasAnchor); putInt(row, "anchorDimension", dimension);
        putInt(row, "anchorX", (Integer) get(owner, "anchorX")); putInt(row, "anchorY", (Integer) get(owner, "anchorY"));
        putInt(row, "anchorZ", (Integer) get(owner, "anchorZ"));
        putBool(row, "linkedByExistingMembership", linked); putBool(row, "connectionPresent", connection != null);
        putBool(row, "relayPresent", relay != null); putBool(row, "coreConnectionLive", coreLive);
        putText(row, "relayPort", nodes.containsKey(relay) ? nodes.get(relay).key : "");
        putText(row, "targetPort", nodes.containsKey(target) ? nodes.get(target).key : "");
        putText(row, "offlineReason", ((Enum<?>) get(owner, "offlineReason")).name());
        putBool(row, "proxyReady", (Boolean) get(get(owner, "gridProxy"), "isReady"));
        putBool(row, "clientLinkedCache", (Boolean) get(owner, "clientLinked"));
        putLong(row, "lastMaintenanceTick", (Long) get(owner, "lastMaintenanceTick"));
        putInt(row, "reconnectBackoff", (Integer) get(owner, "reconnectBackoff"));
        tag(node.row, "quantumBridge", row);
    }

    private static boolean contains(Object node, Object edge) throws Exception {
        if (node == null || edge == null) return false;
        if (!"appeng.me.GridNode".equals(node.getClass().getName()) || !"appeng.me.GridConnection".equals(edge.getClass().getName())) return false;
        for (Object existing : (Iterable<?>) call(node, "getConnections")) if (existing == edge) return true;
        return false;
    }

    private static boolean edgeBetween(Object edge, Object a, Object b) throws Exception {
        if (edge == null || a == null || b == null || !"appeng.me.GridConnection".equals(edge.getClass().getName())) return false;
        Object x = call(edge, "a"), y = call(edge, "b");
        return ((x == a && y == b) || (x == b && y == a)) && contains(a, edge) && contains(b, edge);
    }

    private Object describeGrid(Graph graph) throws Exception {
        Object row = nbt(), members = list();
        putInt(row, "observedGrid", graph.index);
        List<String> memberKeys = new ArrayList<String>();
        for (Node node : graph.members) memberKeys.add(node.key);
        Collections.sort(memberKeys);
        for (String key : memberKeys) append(members, construct("net.minecraft.nbt.NBTTagString", key));
        tag(row, "members", members);
        Object storage = get(graph.value, "myStorage"); putBool(row, "gPresent", storage != null);
        if (storage == null) unknowns.add("grid[" + graph.index + "]: existing grid has no GridStorage");
        else {
            putLong(row, "g", (Long) call(storage, "getID"));
            for (Node node : graph.members) if ("appeng.me.GridNode".equals(node.value.getClass().getName())
                && get(node.value, "myStorage") != storage)
                unknowns.add(node.key + ": node storage differs from its existing grid storage");
        }
        Object energy = call(graph.value, "getCache", Class.forName("appeng.api.networking.energy.IEnergyGrid"));
        boolean known = energy != null && "appeng.me.cache.EnergyGridCache".equals(energy.getClass().getName());
        putBool(row, "energyCacheKnown", known);
        if (!known) unknowns.add("grid[" + graph.index + "]: exact EnergyGridCache unavailable");
        else {
            double extra = (Double) get(energy, "extra");
            putLong(row, "extraRawBits", Double.doubleToRawLongBits(extra)); putText(row, "extraText", Double.toString(extra));
            putBool(row, "extraFinite", !Double.isNaN(extra) && !Double.isInfinite(extra)); putBool(row, "extraZero", extra == 0.0D);
            if (Double.isNaN(extra) || Double.isInfinite(extra)) unknowns.add("grid[" + graph.index + "]: nonfinite live extra");
            int providers = 0;
            for (Object provider : (Iterable<?>) get(energy, "energyGridProviders")) providers++;
            putInt(row, "energyProviderEntries", providers);
            if (providers != 0) unknowns.add("grid[" + graph.index + "]: energy-provider coupling outside GridConnection is not yet validated");
        }
        Object path = call(graph.value, "getCache", Class.forName("appeng.api.networking.pathing.IPathingGrid"));
        boolean pathKnown = path != null && "appeng.me.cache.PathGridCache".equals(path.getClass().getName());
        putBool(row, "pathCacheKnown", pathKnown);
        if (pathKnown) {
            // Both .1080 methods return existing scalar fields, not repath/update calls.
            putBool(row, "networkBooting", (Boolean) call(path, "isNetworkBooting"));
            putText(row, "controllerState", ((Enum<?>) call(path, "getControllerState")).name());
        } else unknowns.add("grid[" + graph.index + "]: exact PathGridCache unavailable");
        return row;
    }

    private static final class Node {
        final Object value, row;
        final List<String> problems = new ArrayList<String>();
        Object quantumOwner, relayOwner, relay;
        boolean located, internal;
        String key = "";
        Node(Object value, Object row) { this.value = value; this.row = row; }
    }
    private static final class Graph {
        final Object value;
        final int index;
        final List<Node> members = new ArrayList<Node>();
        boolean selected, unlocated;
        Graph(Object value, int index) { this.value = value; this.index = index; }
    }
    private static Set<String> names(String... values) { return new HashSet<String>(Arrays.asList(values)); }
    private static Object nbt() throws Exception { return construct("net.minecraft.nbt.NBTTagCompound"); }
    private static Object list() throws Exception { return construct("net.minecraft.nbt.NBTTagList"); }
    private static void putText(Object tag, String key, String value) throws Exception { call(tag, "func_74778_a", key, value); }
    private static void putInt(Object tag, String key, int value) throws Exception { call(tag, "func_74768_a", key, value); }
    private static void putLong(Object tag, String key, long value) throws Exception { call(tag, "func_74772_a", key, value); }
    private static void putBool(Object tag, String key, boolean value) throws Exception { call(tag, "func_74757_a", key, value); }
    private static void tag(Object tag, String key, Object value) throws Exception { call(tag, "func_74782_a", key, value); }
    private static void append(Object tag, Object value) throws Exception { call(tag, "func_74742_a", value); }
    private static int getInt(Object tag, String key) throws Exception { return (Integer) call(tag, "func_74762_e", key); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException("Composite AE observer: " + message); }
    private static Object get(Object object, String name) throws Exception {
        Class<?> type = object instanceof Class ? (Class<?>) object : object.getClass();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try {
            Field field = c.getDeclaredField(name); field.setAccessible(true); return field.get(object instanceof Class ? null : object);
        } catch (NoSuchFieldException absent) { }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Object construct(String name, Object... args) throws Exception {
        for (Constructor<?> c : Class.forName(name).getDeclaredConstructors()) if (compatible(c.getParameterTypes(), args)) { c.setAccessible(true); return c.newInstance(args); }
        throw new NoSuchMethodException(name + " constructor");
    }
    private static Object call(Object object, String name, Object... args) throws Exception {
        Class<?> type = object instanceof Class ? (Class<?>) object : object.getClass();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method method : c.getDeclaredMethods())
            if (method.getName().equals(name) && compatible(method.getParameterTypes(), args)) { method.setAccessible(true); return method.invoke(object instanceof Class ? null : object, args); }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static boolean compatible(Class<?>[] types, Object[] values) {
        if (types.length != values.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (values[i] == null) { if (types[i].isPrimitive()) return false; continue; }
            Class<?> t = types[i];
            if (t.isPrimitive()) t = t == int.class ? Integer.class : t == long.class ? Long.class : t == boolean.class ? Boolean.class : t;
            if (!t.isInstance(values[i])) return false;
        }
        return true;
    }
}
