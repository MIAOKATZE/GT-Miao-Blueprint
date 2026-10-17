package gtmb.v0111.probe;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Independent test-only SELF model. No World writer, registration, lazy grid/proxy getter or saveState is called.
 * Reuses only Raw's frozen pure graph/schema readers, never its relocation model or caller-provided mappings.
 * Source and target must be complete serialized v2 observations with their original same-tick path facts.
 * This is an observed graph embedding, not inventory/configuration/energy conservation or a whole-world PASS.
 */
public final class CompositeAeSelfCopyProof {
    private static final String RAW = "gtmb.v0111.probe.CompositeAeRawRelocation";
    private static final String QUANTUM = "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode";
    private static final String RELAY = "com.miaokatze.gtswn.common.quantum.QuantumNodeBridgeRelay";
    private static final String ACCEPTOR = "appeng.tile.networking.TileEnergyAcceptor";
    // AE1080 AENetworkProxy:131-142/359-360; GTMB fresh construction prepareActor:1082-1093.
    // No GT MTE, part, QN, relay or unreviewed class is granted a player-ID exception.
    private static final Set<String> OWNER_ROOTS = names(
        "appeng.tile.crafting.TileCraftingMonitorTile", "appeng.tile.crafting.TileCraftingStorageTile",
        "appeng.tile.crafting.TileCraftingTile", "appeng.tile.crafting.TileMolecularAssembler",
        "appeng.tile.misc.TileAdvancedInscriber", "appeng.tile.misc.TileCharger", "appeng.tile.misc.TileInscriber",
        "appeng.tile.misc.TileInterface", "appeng.tile.misc.TilePatternOptimizationMatrix",
        "appeng.tile.misc.TileQuartzGrowthAccelerator", "appeng.tile.misc.TileStorageReshuffle",
        "appeng.tile.misc.TileVibrationChamber", "appeng.tile.networking.TileDenseEnergyCell", ACCEPTOR,
        "appeng.tile.networking.TileEnergyCell", "appeng.tile.storage.TileChest", "appeng.tile.storage.TileDrive",
        "appeng.tile.storage.TileIOPort");
    private CompositeAeSelfCopyProof() {}

    /**
     * Detached raw facts, not a security capability. PlayerData.getPlayerID/getCategory/save are never called.
     * AE1080 PlayerData:75-102 distinguishes an existing UUID property from the next-ID counter.
     */
    public static Object observeExistingActor(Object actor) throws Exception {
        require("Server thread".equals(Thread.currentThread().getName()), "natural server thread required");
        require(Class.forName("net.minecraft.entity.player.EntityPlayerMP").isInstance(actor), "actual connected native actor required");
        Object world = field(actor, "field_70170_p");
        require((Integer) field(field(world, "field_73011_w"), "field_76574_g") == 1048578, "owned fixture dimension required");
        UUID actorId = (UUID) call(actor, "func_110124_au");
        Object profile = call(actor, "func_146103_bH");
        require(actorId.equals(call(profile, "getId")), "native actor/profile UUID mismatch");
        Object worldData = field(Class.forName("appeng.core.worlddata.WorldData"), "instance");
        require(worldData != null && worldData.getClass().getName().equals("appeng.core.worlddata.WorldData"), "existing native WorldData required");
        Object playerData = field(worldData, "playerData");
        require(playerData.getClass().getName().equals("appeng.core.worlddata.PlayerData"), "exact existing PlayerData required");
        Object config = field(playerData, "config");
        Object category = ((Map<?, ?>) field(config, "categories")).get("players");
        require(category != null, "existing players category is missing; no category is created");
        Map<?, ?> properties = (Map<?, ?>) field(category, "properties");
        Object playerMapping = field(playerData, "playerMapping");
        require(playerMapping.getClass().getName().equals("appeng.core.worlddata.PlayerMapping"), "exact existing reverse mapping required");
        Map<?, ?> reverse = (Map<?, ?>) field(playerMapping, "mappings");
        Object out = compound(), forwards = list(), backwards = list();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            Object property = entry.getValue(), row = compound();
            text(row, "uuid", String.valueOf(entry.getKey()));
            text(row, "propertyType", ((Enum<?>) field(property, "type")).name());
            bool(row, "propertyIsList", (Boolean) field(property, "isList"));
            text(row, "propertyValue", (String) field(property, "value")); append(forwards, row);
        }
        for (Map.Entry<?, ?> entry : reverse.entrySet()) {
            require(entry.getKey() instanceof Integer && entry.getValue() instanceof UUID, "unsupported native reverse mapping entry");
            Object row = compound(); integer(row, "p", (Integer) entry.getKey()); text(row, "uuid", entry.getValue().toString()); append(backwards, row);
        }
        text(out, "format", "ae-existing-actor-registry-v1"); text(out, "observationBoundary", "same-tick-existing-native-registry");
        text(out, "actorUUID", actorId.toString()); text(out, "profileUUID", call(profile, "getId").toString());
        integer(out, "dimension", 1048578); number(out, "sampleWorldTick", (Long) call(world, "func_82737_E"));
        text(out, "forwardSource", "WorldData.instance.playerData.config.categories[players].properties");
        text(out, "reverseSource", "WorldData.instance.playerData.playerMapping.mappings");
        tag(out, "forwardMappings", forwards); tag(out, "reverseMappings", backwards);
        text(out, "scope", "existing raw forward/reverse registry only; no getter that registers/saves; signatures are not a security boundary");
        actorId(out); // Reject malformed/ambiguous raw registry facts before returning them.
        return out;
    }

    /** Existing rawGraphPathReady receipts are accepted only with all original raw flags, never a ready shortcut. */
    public static Proof prepare(Object sourceBefore, Object targetAfter, int[] sourceOrigin, int[] targetOrigin,
        Object actorRegistryFacts, Object sourcePathFacts, Object targetPathFacts) throws Exception {
        require(sourceBefore != null && targetAfter != null && actorRegistryFacts != null
            && sourcePathFacts != null && targetPathFacts != null, "UNKNOWN missing original graph/registry/path facts");
        require(sourceOrigin != null && targetOrigin != null && sourceOrigin.length == 3 && targetOrigin.length == 3,
            "two exact fixture origins required");
        boolean disjoint = false;
        for (int axis = 0; axis < 3; axis++) disjoint |= (long) sourceOrigin[axis] + 40 <= targetOrigin[axis]
            || (long) targetOrigin[axis] + 40 <= sourceOrigin[axis];
        require(disjoint, "SELF source remains present; overlapping source/target is unsupported");
        // Raw.graph validates one detached graph only: typed schema, all nodes/groups/edges, native relay facts,
        // exact g/p/k, zero live extra, no providers, complete coverage, known nonbooting path caches.
        Graph before = graph(sourceBefore, sourceOrigin), after = graph(targetAfter, targetOrigin);
        require(after.tick >= before.tick, "target graph predates source graph");
        pathFacts(sourceBefore, before, sourceOrigin, sourcePathFacts);
        pathFacts(targetAfter, after, targetOrigin, targetPathFacts);
        int actor = actorId(actorRegistryFacts);
        require(intValue(actorRegistryFacts, "dimension") == 1048578, "actor registry dimension differs");
        String boundary = string(actorRegistryFacts, "observationBoundary");
        long actorTick = longValue(actorRegistryFacts, "sampleWorldTick");
        require((boundary.equals("same-tick-existing-native-registry") && (actorTick == before.tick || actorTick == after.tick))
            || (boundary.equals("closed-world-native-settings") && actorTick == -1), "actor registry observation boundary is unknown");
        Port bank = bank(before); require(bank.p == actor, "exact source-bank controller is not bound to the existing actor ID");
        Embedding model = embedding(before, bank.key);
        same(model.ports, after.ports.keySet(), "complete SELF port embedding");
        same(model.edges, after.edges, "complete SELF directed/boundary edges");
        same(model.groups, after.groups.keySet(), "complete SELF grid-member groups");
        Map<Integer, List<Binding>> bindings = new TreeMap<Integer, List<Binding>>();
        Object receipt = compound(), mapped = list(), patches = list(), groupRows = list();
        Map<String, String> tileIds = new HashMap<String, String>();
        int changedP = 0, changedG = 0, persisted = 0, unpersistedAcceptor = 0;
        for (String key : model.retained) {
            Port old = before.ports.get(key), next = after.ports.get(model.oldMap.get(key));
            retainedIdentity(old, next); sameHost(old, next);
            compareLinks(old, next, model.oldMap);
            append(mapped, mapping(old, next, "retained-original-source-bank-component"));
        }
        Set<Long> oldGridIds = new HashSet<Long>(before.groups.values());
        for (List<String> members : before.groups.keySet()) {
            List<String> expected = model.groupMap.get(members); long oldG = before.groups.get(members), nextG = after.groups.get(expected);
            if (!members.contains(bank.key)) require(!oldGridIds.contains(nextG), "new isolated SELF group reuses an original source GridStorage ID");
            else require(nextG == oldG, "retained source-bank GridStorage changed; this minimal SELF model does not authorize it");
            Object row = compound(); number(row, "oldG", oldG); number(row, "newG", nextG);
            integer(row, "originalMembers", members.size()); tag(row, "expectedTargetMembers", strings(expected)); append(groupRows, row);
        }
        for (Port old : before.ports.values()) if (old.internal) {
            Port next = after.ports.get(old.key); sameHost(old, next); newIdentity(old, next, actor); compareLinks(old, next, model.copyMap);
            if (old.p != next.p) changedP++; if (old.g != next.g) changedG++;
            append(mapped, mapping(old, next, "new-target-copy"));
            if (!old.persisted) continue; // Runtime relay remains in exact topology/p/k checks; never invent an NBT root.
            Binding binding = reviewed(old, next, tileIds);
            int index = old.rx + 40 * old.rz + 1600 * old.ry;
            if (!bindings.containsKey(index)) bindings.put(index, new ArrayList<Binding>());
            for (Binding b : bindings.get(index)) require(!b.source.path.equals(old.path), "duplicate native source path");
            bindings.get(index).add(binding);
            if (old.machine.equals(ACCEPTOR)) { unpersistedAcceptor++; continue; }
            persisted++;
            if (old.g != next.g) append(patches, patch(old, next, index, "g", 4, old.g, next.g));
            if (old.p != next.p) append(patches, patch(old, next, index, "p", 3, old.p, next.p));
        }
        require(persisted > 0, "no reviewed native persisted source port");
        text(receipt, "algorithm", "ae-self-copy-embedding-v1"); text(receipt, "status", "SCOPED_SELF_GRAPH_PROVED_ROW_BINDING_REQUIRED");
        bool(receipt, "authorizesPass", false); bool(receipt, "sourceConsumptionAuthorized", false); bool(receipt, "shareSupported", false);
        text(receipt, "energyScope", "zero native grid extra at two observed instants and no provider coupling only; no energy migration/conservation authorization");
        bool(receipt, "energyConservationProved", false); bool(receipt, "excludedOriginalSourcePersistenceProved", false);
        text(receipt, "rowBindingScope", "Each returned expected-copy patch must first bind original native source xyz/class/id/mID/part definition and exact typed g/p/k. Baseline time is not relabeled as capture time.");
        text(receipt, "securityScope", "all original p/g/k and every copied k exact; no owner/security key deletion or permission PASS; source/target SecurityCache provider list is outside v2 observations");
        number(receipt, "sourceWorldTick", before.tick); number(receipt, "targetWorldTick", after.tick);
        text(receipt, "sourceGraphCanonicalSha256", string(sourcePathFacts,"snapshotCanonicalSha256"));
        text(receipt, "targetGraphCanonicalSha256", string(targetPathFacts,"snapshotCanonicalSha256"));
        tag(receipt,"sourceOrigin",copy(typed(sourcePathFacts,"origin",11)));tag(receipt,"targetOrigin",copy(typed(targetPathFacts,"origin",11)));
        tag(receipt,"sourcePathFacts",copy(sourcePathFacts));tag(receipt,"targetPathFacts",copy(targetPathFacts));
        integer(receipt, "existingActorId", actor); tag(receipt, "actorRegistryFacts", copy(actorRegistryFacts));
        bool(receipt, "actorRegistryObservedAtGraphTick", actorTick == before.tick || actorTick == after.tick);
        integer(receipt, "originalRetainedPorts", model.retained.size()); integer(receipt, "newTargetPorts", model.ports.size() - model.retained.size());
        integer(receipt, "newRuntimePChanges", changedP); integer(receipt, "newRuntimeGChanges", changedG);
        integer(receipt, "reviewedPersistedPorts", persisted); integer(receipt, "nativeUnpersistedEnergyAcceptorPorts", unpersistedAcceptor);
        integer(receipt,"sourcePorts",before.ports.size());integer(receipt,"targetPorts",after.ports.size());
        integer(receipt,"expectedDirectedEdges",model.edges.size());integer(receipt,"actualDirectedEdges",after.edges.size());integer(receipt,"completeTargetGroups",after.groups.size());
        tag(receipt, "finitePatchCandidates", patches); tag(receipt, "portMappings", mapped); tag(receipt, "groupMappings", groupRows);
        tag(receipt, "excludedOriginalAbsolutePorts", strings(model.excluded));
        text(receipt, "excludedScope", "Original isolated source groups are outside the target-associated graph, not deleted or consumed by this proof.");
        text(receipt, "pureValidator", "CompositeAeRawRelocation.graph and native schema readers only; Raw relocation prepare/model is not used");
        return new Proof(bindings, receipt, sourceOrigin);
    }

    public static final class Proof {
        private final Map<Integer, List<Binding>> bindings;
        private final Object evidence;
        private final int[] source;
        private Proof(Map<Integer, List<Binding>> input, Object receipt, int[] source) {
            Map<Integer, List<Binding>> fixed = new TreeMap<Integer, List<Binding>>();
            for (Map.Entry<Integer, List<Binding>> e : input.entrySet()) fixed.put(e.getKey(), Collections.unmodifiableList(new ArrayList<Binding>(e.getValue())));
            this.bindings = Collections.unmodifiableMap(fixed); this.evidence = receipt; this.source = source.clone();
        }
        /** Returns a NEW copy. Never mutates the supplied baseline, an actual row, a graph, or an original source port. */
        public Object patchExpectedNodeIdentities(Object originalExpectedRow, int index) throws Exception {
            require(index >= 0 && index < 64000, "source index outside exact fixture");
            Object out = copy(originalExpectedRow); List<Binding> rows = bindings.get(index);
            if (rows == null) return out;
            List<Object> nodes = validateOriginalRow(out, index, rows);
            // All per-row native bindings are checked before any write. No k or other NBT is changed.
            for (int i = 0; i < rows.size(); i++) if (nodes.get(i) != null) {
                Binding b = rows.get(i);
                if (b.source.g != b.target.g) number(nodes.get(i), "g", b.target.g);
                if (b.source.p != b.target.p) integer(nodes.get(i), "p", b.target.p);
            }
            return out;
        }
        /** Source-policy audit only. This never permits deletion/change of k in a native World expectation. */
        public boolean permitsIdentityOmission(Object baselineRow, Object selectedRow, int index, String exactPath) throws Exception {
            if (index < 0 || index >= 64000 || exactPath == null || !bindings.containsKey(index)) return false;
            List<Binding> rows = bindings.get(index);
            validateOriginalRow(baselineRow, index, rows); // Every original proxy in this row is checked as a whole.
            if (!has(selectedRow, "tile")) return false;
            Object tile = child(selectedRow, "tile");
            for (Binding b : rows) {
                if (b.source.machine.equals(ACCEPTOR)) continue;
                String prefix = "/tile/" + b.source.path;
                if (!exactPath.equals(prefix) && !exactPath.equals(prefix+"/g")
                    && !exactPath.equals(prefix+"/p") && !exactPath.equals(prefix+"/k")) continue;
                if (!has(tile,"id") || !string(tile,"id").equals(b.tileId)) return false;
                if (has(selectedRow,"tileClass") && !string(selectedRow,"tileClass").equals(b.source.host)) return false;
                if (b.source.metaId >= 0 && (!has(tile,"mID") || intValue(tile,"mID") != b.source.metaId)) return false;
                if (b.source.side >= 0) raw("partDefinition",tile,b.container,b.source.side,b.source.item,b.source.damage);
                String[] path=b.source.path.split("/");Object parent=tile;
                // Missing private ancestors are not authorized by a proxy identity omission.
                for(int i=0;i<path.length-1;i++){if(!has(parent,path[i]))return false;parent=child(parent,path[i]);}
                return !has(parent,path[path.length-1]); // Empty/wrong-typed/partial containers are not full omission.
            }
            return false;
        }
        private List<Object> validateOriginalRow(Object row, int index, List<Binding> rows) throws Exception {
            Object tile = child(row, "tile"); List<Object> nodes = new ArrayList<Object>();
            for (Binding b : rows) {
                Port old = b.source;
                require(string(row, "tileClass").equals(old.host) && string(tile, "id").equals(b.tileId), "original native source host/id differs at " + index);
                require(intValue(tile, "x") == source[0] + index % 40 && intValue(tile, "y") == source[1] + index / 1600
                    && intValue(tile, "z") == source[2] + index / 40 % 40, "original source xyz differs; call before relocation");
                if (old.metaId >= 0) require(intValue(tile, "mID") == old.metaId, "original native mID differs");
                if (old.machine.equals(ACCEPTOR)) {
                    require(!has(tile, "proxy"), "exact EnergyAcceptor native writer omits proxy; unexpected data is not authorized");
                    typed(tile, "internalCurrentPower", 6); nodes.add(null); continue;
                }
                if (old.side >= 0) raw("partDefinition", tile, b.container, old.side, old.item, old.damage);
                Object node = raw("path", tile, old.path);
                raw("nodeIdentity", node, old.g, old.p, old.k, "SELF original source " + index + "/" + old.path);
                nodes.add(node);
            }
            return nodes;
        }
        public Object receipt() throws Exception { return copy(evidence); }
    }

    private static Port bank(Graph source) throws Exception {
        String anchor = null;
        for (Port p : source.ports.values()) if (p.internal && p.machine.equals(QUANTUM) && p.links != null) {
            String next = p.links.anchor;
            require(anchor == null || anchor.equals(next), "this SELF fixture model supports one exact original bank anchor only"); anchor = next;
        }
        require(anchor != null, "configured original quantum-node roster missing"); Port result = null;
        for (Port p : source.ports.values()) if (p.machine.equals("appeng.tile.networking.TileController") && !p.internal
            && p.path.equals("proxy") && (p.dim + ":" + p.x + "," + p.y + "," + p.z).equals(anchor)) {
            require(result == null, "ambiguous original native bank controller"); result = p;
        }
        require(result != null, "original configured native bank controller is absent"); return result;
    }

    private static Embedding embedding(Graph source, String bankKey) {
        List<String> bankGroup = null;
        for (List<String> members : source.groups.keySet()) if (members.contains(bankKey)) bankGroup = members;
        require(bankGroup != null, "original bank group is missing");
        Embedding out = new Embedding(); out.retained.addAll(bankGroup);
        for (Port p : source.ports.values()) {
            out.oldMap.put(p.key, p.absolute); out.copyMap.put(p.key, p.internal ? p.key : p.absolute);
            if (out.retained.contains(p.key)) out.ports.add(p.absolute);
            else { require(p.internal, "unreviewed other external source component"); out.excluded.add(p.absolute); }
            if (p.internal) out.ports.add(p.key);
        }
        for (Edge edge : source.edges) {
            if (out.retained.contains(edge.a) && out.retained.contains(edge.b)) out.edges.add(edge.map(out.oldMap));
            if (source.ports.get(edge.a).internal || source.ports.get(edge.b).internal) out.edges.add(edge.map(out.copyMap));
        }
        for (List<String> members : source.groups.keySet()) {
            Set<String> expected = new TreeSet<String>(); boolean bank = members.contains(bankKey);
            for (String key : members) {
                if (bank) expected.add(out.oldMap.get(key));
                else require(source.ports.get(key).internal, "unreviewed original external group");
                if (source.ports.get(key).internal) expected.add(key);
            }
            List<String> group = Collections.unmodifiableList(new ArrayList<String>(expected));
            require(out.groups.add(group), "duplicate projected SELF member group"); out.groupMap.put(members, group);
        }
        return out;
    }

    private static void retainedIdentity(Port old, Port next) {
        require(next != null && old.g == next.g && old.p == next.p && old.k == next.k, "original source/bank g/p/k changed at " + old.key);
    }
    private static void newIdentity(Port old, Port next, int actor) {
        require(next != null && old.k == next.k, "copied security key changed at " + old.key);
        if (old.p != next.p) require(ownerRoot(old) && old.p == -1 && next.p == actor, "unreviewed copied player identity change at " + old.key);
    }
    private static boolean ownerRoot(Port p) { return OWNER_ROOTS.contains(p.machine) && p.host.equals(p.machine)
        && p.path.equals("proxy") && p.side == -1 && p.metaId == -1 && p.part.isEmpty(); }
    private static void sameHost(Port old, Port next) {
        require(next != null && old.host.equals(next.host) && old.machine.equals(next.machine) && old.proxy.equals(next.proxy)
            && old.path.equals(next.path) && old.metaId == next.metaId && old.side == next.side && old.part.equals(next.part)
            && old.item.equals(next.item) && old.damage == next.damage && old.persisted == next.persisted, "native host/part/path role differs at " + old.key);
    }
    private static void compareLinks(Port old, Port next, Map<String, String> map) {
        if (old.links == null) { require(next.links == null, "unexpected quantum/relay link evidence"); return; }
        require(map.containsKey(old.links.first) && map.containsKey(old.links.second), "UNKNOWN unsupported quantum owner/relay target projection");
        require(next.links != null && old.links.anchor.equals(next.links.anchor)
            && map.get(old.links.first).equals(next.links.first) && map.get(old.links.second).equals(next.links.second), "native owner/relay/anchor mapping differs at " + old.key);
    }

    private static Binding reviewed(Port old, Port next, Map<String, String> tileIds) throws Exception {
        require(old.proxy.equals("appeng.me.helpers.AENetworkProxy") || old.proxy.equals("appeng.me.helpers.AENetworkProxyMultiblock"), "unreviewed persisted proxy class");
        String container = "";
        if (rawSet("GT").contains(old.machine)) {
            require(old.host.equals("gregtech.api.metatileentity.BaseMetaTileEntity") && old.path.equals("proxy") && old.metaId >= 0 && old.side == -1 && old.part.isEmpty(), "unreviewed GT source host/path");
            Object prototypes = field(Class.forName("gregtech.api.GregTechAPI"), "METATILEENTITIES");
            require(old.metaId < Array.getLength(prototypes) && Array.get(prototypes, old.metaId) != null
                && Array.get(prototypes, old.metaId).getClass().getName().equals(old.machine), "native GT mID/prototype differs");
        } else if (rawSet("PARTS").contains(old.machine)) {
            require(old.part.equals(old.machine) && old.side >= 0 && old.side <= 6, "unreviewed source part identity");
            if (rawSet("CABLE").contains(old.host)) container = "";
            else { require(old.host.equals(QUANTUM), "unreviewed part host"); container = "partContainer"; }
            String prefix = (container.isEmpty() ? "" : container + "/") + "extra:" + old.side + "/";
            require(old.path.equals(prefix + "part") || (old.path.equals(prefix + "outer") && rawSet("OUTER").contains(old.machine)), "unreviewed part proxy path");
        } else require(rawSet("TILES").contains(old.host) && old.machine.equals(old.host) && old.path.equals("proxy")
            && old.metaId == -1 && old.side == -1 && old.part.isEmpty(), "unreviewed native tile source path");
        if (!tileIds.containsKey(old.host)) tileIds.put(old.host, (String) raw("registeredTileId", old.host));
        return new Binding(old, next, tileIds.get(old.host), container);
    }

    private static Graph graph(Object snapshot, int[] origin) throws Exception {
        Object checked = raw("graph", snapshot, origin); Graph out = new Graph(longValue(snapshot, "worldTick"));
        for (Object value : ((Map<?, ?>) field(checked, "nodes")).values()) {
            Port p = new Port(value); out.ports.put(p.key, p);
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) field(checked, "groups")).entrySet()) {
            List<String> members = new ArrayList<String>(); for (Object key : (List<?>) e.getKey()) members.add((String) key);
            long g=(Long)field(e.getValue(),"g"),extra=(Long)field(e.getValue(),"extra");
            require(extra==0L,"nonzero or noncanonical raw extra bits are outside this SELF proof energy scope");
            out.groups.put(Collections.unmodifiableList(members),g);
        }
        for (Object row : elements(snapshot, "edges", 10)) out.edges.add(new Edge(string(row,"a"),string(row,"b"),intValue(row,"directionA"),intValue(row,"directionB"),flag(row,"hasDirection")));
        return out;
    }

    private static void pathFacts(Object snapshot, Graph graph, int[] origin, Object facts) throws Exception {
        require(string(facts,"format").equals("ae-path-facts-v1") && string(facts,"snapshotHashAlgorithm").equals("canonical-nbt-v1"), "UNKNOWN original native path-facts schema");
        require(string(facts,"snapshotCanonicalSha256").equals(canonicalHash(snapshot)), "path facts do not bind the complete original snapshot");
        require(longValue(facts,"sampleWorldTick")==graph.tick && longValue(facts,"snapshotWorldTick")==graph.tick
            && intValue(facts,"dimension")==1048578 && Arrays.equals((int[])raw("intArray",facts,"origin"),origin), "path facts tick/dimension/origin differs");
        Set<Long> expected=new HashSet<Long>(graph.groups.values()), observed=new HashSet<Long>();
        Map<Long,String> states=new HashMap<Long,String>();
        for(Object row:elements(snapshot,"grids",10))states.put(longValue(row,"g"),string(row,"controllerState"));
        for(Object row:elements(facts,"groups",10)){
            long g=longValue(row,"g");require(observed.add(g),"duplicate native path-facts grid");
            require(string(row,"gridClass").equals("appeng.me.Grid") && string(row,"pathClass").equals("appeng.me.cache.PathGridCache"),"UNKNOWN native path cache class");
            String state=string(row,"controllerState");require((state.equals("NO_CONTROLLER")||state.equals("CONTROLLER_ONLINE"))&&state.equals(states.get(g)),"UNKNOWN/different native controller state");
            require(!flag(row,"recalculateControllerNextTick") && !flag(row,"updateNetwork") && !flag(row,"booting"),"native path cache has pending work/conflict");
            if(has(row,"ready"))require(flag(row,"ready"),"contradictory native path ready field");
            if(has(row,"pendingReason"))require(string(row,"pendingReason").isEmpty(),"contradictory pending reason");
        }
        same(expected,observed,"complete same-tick path-facts g scope");
        require(intValue(facts,"expectedGroups")==expected.size() && intValue(facts,"observedNativeGroups")==observed.size(),"path-facts counts differ");
    }

    private static int actorId(Object facts) throws Exception {
        require(string(facts,"format").equals("ae-existing-actor-registry-v1"),"UNKNOWN raw actor registry schema");
        UUID actor=UUID.fromString(string(facts,"actorUUID"));require(actor.toString().equals(string(facts,"profileUUID")),"actor/profile UUID differs");
        Map<String,Integer> forward=new TreeMap<String,Integer>();Map<Integer,String> reverse=new TreeMap<Integer,String>();
        for(Object row:elements(facts,"forwardMappings",10)){
            String uuid=UUID.fromString(string(row,"uuid")).toString();require(string(row,"propertyType").equals("INTEGER")&&!flag(row,"propertyIsList"),"UNKNOWN native player property type");
            int p=Integer.parseInt(string(row,"propertyValue"));require(p>=0&&!forward.containsKey(uuid),"ambiguous native forward player mapping");forward.put(uuid,p);
        }
        for(Object row:elements(facts,"reverseMappings",10)){
            int p=intValue(row,"p");String uuid=UUID.fromString(string(row,"uuid")).toString();require(p>=0&&!reverse.containsKey(p),"ambiguous native reverse player mapping");reverse.put(p,uuid);
        }
        require(!forward.isEmpty()&&forward.size()==reverse.size(),"UNKNOWN/incomplete raw player registry scope");
        for(Map.Entry<String,Integer> e:forward.entrySet())require(e.getKey().equals(reverse.get(e.getValue())),"native forward/reverse player mapping differs");
        require(forward.containsKey(actor.toString()),"actor has no existing native player ID");return forward.get(actor.toString());
    }

    private static Object mapping(Port old,Port next,String role)throws Exception {Object row=compound();text(row,"role",role);text(row,"sourcePort",old.key);text(row,"targetPort",next.key);number(row,"oldG",old.g);number(row,"newG",next.g);integer(row,"oldP",old.p);integer(row,"newP",next.p);number(row,"k",old.k);return row;}
    private static Object patch(Port old,Port next,int index,String field,int type,long from,long to)throws Exception {Object row=compound();integer(row,"index",index);text(row,"sourcePort",old.key);text(row,"targetPort",next.key);text(row,"path","/tile/"+old.path+"/"+field);integer(row,"tagId",type);if(type==3){integer(row,"before",(int)from);integer(row,"after",(int)to);}else{number(row,"before",from);number(row,"after",to);}text(row,"status","REQUIRES_EXACT_ORIGINAL_SOURCE_ROW_BINDING");return row;}
    private static final class Binding {final Port source,target;final String tileId,container;Binding(Port a,Port b,String id,String container){source=a;target=b;tileId=id;this.container=container;}}
    private static final class Links {final String first,second,anchor;Links(String a,String b,String c){first=a;second=b;anchor=c;}}
    private static final class Port {
        final String key,absolute,host,machine,proxy,path,part,item;final int dim,x,y,z,rx,ry,rz,metaId,side,damage,p;final long g,k;final boolean internal,persisted;final Links links;
        Port(Object node)throws Exception {
            key=(String)field(node,"key");host=(String)field(node,"host");machine=(String)field(node,"machine");proxy=(String)field(node,"proxy");path=(String)field(node,"path");part=(String)field(node,"part");item=(String)field(node,"item");
            dim=(Integer)field(node,"dim");x=(Integer)field(node,"x");y=(Integer)field(node,"y");z=(Integer)field(node,"z");rx=(Integer)field(node,"rx");ry=(Integer)field(node,"ry");rz=(Integer)field(node,"rz");metaId=(Integer)field(node,"metaId");side=(Integer)field(node,"side");damage=(Integer)field(node,"damage");p=(Integer)field(node,"p");g=(Long)field(node,"g");k=(Long)field(node,"k");internal=(Boolean)field(node,"internal");persisted=(Boolean)field(node,"persisted");
            absolute="absolute:"+dim+":"+x+","+y+","+z+key.substring(key.indexOf('|'));
            Object row=field(node,"row");
            if(machine.equals(QUANTUM)){Object q=child(row,"quantumBridge");require(flag(q,"hasAnchor"),"unconfigured QN is outside this fixture SELF model");links=new Links(string(q,"relayPort"),string(q,"targetPort"),intValue(q,"anchorDimension")+":"+intValue(q,"anchorX")+","+intValue(q,"anchorY")+","+intValue(q,"anchorZ"));}
            else if(machine.equals(RELAY))links=new Links(string(row,"relayOwnerPort"),string(row,"relayTargetPort"),intValue(row,"relayAnchorDimension")+":"+intValue(row,"relayAnchorX")+","+intValue(row,"relayAnchorY")+","+intValue(row,"relayAnchorZ"));
            else links=null;
        }
        // Only pure-JDK negative controls use this constructor; it is not an observation/evidence API.
        Port(String key,String absolute,boolean inside,String host,long g,int p,long k){this.key=key;this.absolute=absolute;internal=inside;this.host=host;machine=host;proxy="appeng.me.helpers.AENetworkProxy";path="proxy";part="";item="";dim=1048578;x=y=z=rx=ry=rz=0;metaId=side=damage=-1;this.g=g;this.p=p;this.k=k;persisted=true;links=null;}
    }
    private static final class Graph {final long tick;final Map<String,Port> ports=new TreeMap<String,Port>();final Map<List<String>,Long> groups=new LinkedHashMap<List<String>,Long>();final Set<Edge> edges=new HashSet<Edge>();Graph(long tick){this.tick=tick;}}
    private static final class Embedding {final Set<String> retained=new TreeSet<String>(),ports=new TreeSet<String>();final List<String> excluded=new ArrayList<String>();final Map<String,String> oldMap=new HashMap<String,String>(),copyMap=new HashMap<String,String>();final Set<Edge> edges=new HashSet<Edge>();final Set<List<String>> groups=new HashSet<List<String>>();final Map<List<String>,List<String>> groupMap=new HashMap<List<String>,List<String>>();}
    private static final class Edge {
        final String a,b;final int da,db;final boolean directional;
        Edge(String a,String b,int da,int db,boolean directional){if(a.compareTo(b)>0){String t=a;a=b;b=t;int d=da;da=db;db=d;}this.a=a;this.b=b;this.da=da;this.db=db;this.directional=directional;}
        Edge map(Map<String,String> map){require(map.containsKey(a)&&map.containsKey(b),"edge endpoint has no exact projection");return new Edge(map.get(a),map.get(b),da,db,directional);}
        @Override public boolean equals(Object value){if(!(value instanceof Edge))return false;Edge e=(Edge)value;return a.equals(e.a)&&b.equals(e.b)&&da==e.da&&db==e.db&&directional==e.directional;}
        @Override public int hashCode(){return Arrays.asList(a,b,da,db,directional).hashCode();}
        @Override public String toString(){return a+" -> "+b+" directions="+da+","+db+" nativeDirectional="+directional;}
    }

    /** Pure-JDK model negative controls; never loads Minecraft, a World, fixtures or a game instance. */
    public static void main(String[] args) {
        require(Arrays.equals(args,new String[]{"--negative-controls"}),"only explicit pure-model negative controls are supported");
        Graph source=new Graph(1);String bank="absolute:bank",core="relative:core",drive="relative:drive",isolated="relative:isolated";
        source.ports.put(bank,new Port(bank,bank,false,"appeng.tile.networking.TileController",7,3,-1));
        source.ports.put(core,new Port(core,"absolute:core",true,QUANTUM,7,3,-1));
        source.ports.put(drive,new Port(drive,"absolute:drive",true,"appeng.tile.storage.TileDrive",7,3,-1));
        source.ports.put(isolated,new Port(isolated,"absolute:isolated",true,"appeng.tile.misc.TileCharger",8,-1,-1));
        source.groups.put(Arrays.asList(bank,core,drive),7L);source.groups.put(Arrays.asList(isolated),8L);
        source.edges.add(new Edge(bank,core,6,6,false));source.edges.add(new Edge(core,drive,5,4,true));
        Embedding model=embedding(source,bank);require(model.ports.size()==6&&model.edges.size()==4&&model.groups.size()==2,"positive SELF model control");
        Set<String> missing=new HashSet<String>(model.ports);missing.remove("absolute:core");expectRejected(new Runnable(){public void run(){same(model.ports,missing,"missing original source port");}});
        Set<String> extra=new HashSet<String>(model.ports);extra.add("absolute:isolated");expectRejected(new Runnable(){public void run(){same(model.ports,extra,"unrelated original isolated port");}});
        Set<Edge> directions=new HashSet<Edge>(model.edges);directions.remove(new Edge(core,drive,5,4,true));directions.add(new Edge(core,drive,4,5,true));expectRejected(new Runnable(){public void run(){same(model.edges,directions,"wrong copied direction");}});
        Set<List<String>> groups=new HashSet<List<String>>(model.groups);groups.clear();expectRejected(new Runnable(){public void run(){same(model.groups,groups,"missing/split group");}});
        Port unowned=source.ports.get(isolated);Port owned=new Port(isolated,"unused",true,"appeng.tile.misc.TileCharger",99,3,-1);newIdentity(unowned,owned,3);
        expectRejected(new Runnable(){public void run(){newIdentity(unowned,new Port(isolated,"unused",true,"appeng.tile.misc.TileCharger",99,4,-1),3);}});
        expectRejected(new Runnable(){public void run(){newIdentity(unowned,new Port(isolated,"unused",true,"appeng.tile.misc.TileCharger",99,3,42),3);}});
        expectRejected(new Runnable(){public void run(){retainedIdentity(source.ports.get(bank),new Port(bank,bank,false,"appeng.tile.networking.TileController",9,3,-1));}});
        final Port unreviewed=new Port("relative:unknown","absolute:unknown",true,"unknown.native.Owner",7,-1,-1);
        expectRejected(new Runnable(){public void run(){newIdentity(unreviewed,new Port("relative:unknown","unused",true,"unknown.native.Owner",99,3,-1),3);}});
        System.out.println("SELF pure model: positive embedding and 8 rejection controls passed; no native/whole-world PASS");
    }
    private static void expectRejected(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalStateException expected){rejected=true;}require(rejected,"negative control was incorrectly accepted");}
    private static <T> void same(Set<T> expected,Set<T> actual,String scope){if(expected.equals(actual))return;Set<T> missing=new HashSet<T>(expected);missing.removeAll(actual);Set<T> extra=new HashSet<T>(actual);extra.removeAll(expected);require(false,scope+" differs: missing="+missing.size()+" unexpected="+extra.size());}
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalStateException("AE SELF proof UNKNOWN/rejected: "+reason);}
    @SuppressWarnings("unchecked") private static Set<String> rawSet(String name)throws Exception{return (Set<String>)field(Class.forName(RAW),name);}
    private static String canonicalHash(Object snapshot)throws Exception {byte[] bytes=(byte[])call(Class.forName("gtmb.v0111.probe.CompositeWorldWitness"),"canonicalBytes",snapshot);StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}
    private static Object raw(String name,Object... args)throws Exception{return call(Class.forName(RAW),name,args);}
    private static Object typed(Object tag,String key,int type)throws Exception{return raw("typed",tag,key,type);}
    private static Object child(Object tag,String key)throws Exception{return typed(tag,key,10);}
    private static String string(Object tag,String key)throws Exception{return (String)raw("string",tag,key);}
    private static int intValue(Object tag,String key)throws Exception{return (Integer)raw("intValue",tag,key);}
    private static long longValue(Object tag,String key)throws Exception{return (Long)raw("longValue",tag,key);}
    private static boolean flag(Object tag,String key)throws Exception{return (Boolean)raw("flag",tag,key);}
    private static boolean has(Object tag,String key)throws Exception{return (Boolean)raw("has",tag,key);}
    private static List<?> elements(Object tag,String key,int type)throws Exception{return (List<?>)raw("elements",tag,key,type);}
    private static Object compound()throws Exception{return raw("compound");}
    private static Object list()throws Exception{return raw("list");}
    private static Object copy(Object tag)throws Exception{return raw("copy",tag);}
    private static Object strings(List<String> values)throws Exception{return raw("strings",values);}
    private static void text(Object tag,String key,String value)throws Exception{raw("text",tag,key,value);}
    private static void integer(Object tag,String key,int value)throws Exception{raw("integer",tag,key,value);}
    private static void number(Object tag,String key,long value)throws Exception{raw("number",tag,key,value);}
    private static void bool(Object tag,String key,boolean value)throws Exception{raw("bool",tag,key,value);}
    private static void tag(Object tag,String key,Object value)throws Exception{raw("tag",tag,key,value);}
    private static void append(Object list,Object value)throws Exception{raw("append",list,value);}
    private static Set<String> names(String... names){return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(names)));}
    private static Object field(Object object,String name)throws Exception {Class<?> type=object instanceof Class?(Class<?>)object:object.getClass();for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object instanceof Class?null:object);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(type.getName()+"."+name);}
    private static Object call(Object object,String name,Object... args)throws Exception {Class<?> type=object instanceof Class?(Class<?>)object:object.getClass();for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args)){m.setAccessible(true);try{return m.invoke(object instanceof Class?null:object,args);}catch(InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw (Exception)cause;throw wrapped;}}throw new NoSuchMethodException(type.getName()+"."+name);}
    private static boolean compatible(Class<?>[] types,Object[] args){if(types.length!=args.length)return false;for(int i=0;i<types.length;i++){if(args[i]==null){if(types[i].isPrimitive())return false;continue;}Class<?> t=types[i];if(t.isPrimitive())t=t==int.class?Integer.class:t==long.class?Long.class:t==boolean.class?Boolean.class:t;if(!t.isInstance(args[i]))return false;}return true;}
}
