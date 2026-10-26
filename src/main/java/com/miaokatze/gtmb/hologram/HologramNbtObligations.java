package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTBase.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.api.ResourceContainers;

import appeng.api.config.Settings;
import appeng.block.AEBaseTileBlock;
import gregtech.api.GregTechAPI;

/**
 * Audited dependency projections, not resource quotes or permission certificates. Each claimed root owns its
 * complete typed subtree. A physical boundary is kept exact; it is never interpreted as a free configuration.
 * Unknown roots/native readers bind the complete source to every domain and retain the existing strict readers.
 */
final class HologramNbtObligations {

    static final int EPOCH = 1;
    private static final int APPEARANCE = 1, ASSETS = 2, BINDINGS = 4, ALL = 7;
    private static final long MAX_CACHE_BYTES = 16L * 1024 * 1024;
    private static final LinkedHashMap<Key, Facts> CACHE = new LinkedHashMap<>(16, .75f, true);
    private static long cacheBytes;
    private static final Field LIST_ENTRIES = listEntriesField();

    enum Obligation {
        IDENTITY,
        APPEARANCE,
        ITEM,
        FLUID,
        ENERGY,
        GHOST_RECIPE,
        CAPACITY,
        NETWORK_BINDING,
        UNKNOWN
    }

    enum ThreadAffinity {
        PURE_DETACHED_FACTS,
        MAIN_THREAD_NATIVE
    }

    /** Immutable descriptor: block/factory/prototype and providers, never an id taken on trust from payload. */
    static final class Descriptor {

        final String schema;
        final Object block, adapter, prototype;
        final int metadata, prototypeId;
        final long codecEpoch, nativeEpoch;
        final Map<String, Rule> roots;
        final boolean nativeDependencyUnknown;

        Descriptor(String schema, Block block, int metadata, Object adapter, Object prototype, int prototypeId,
            Map<String, Rule> roots, boolean nativeDependencyUnknown) {
            this.schema = schema;
            this.block = block;
            this.metadata = metadata;
            this.adapter = adapter;
            this.prototype = prototype;
            this.prototypeId = prototypeId;
            codecEpoch = ResourceContainers.generation();
            nativeEpoch = HologramNativePlacement.generation();
            this.roots = Collections.unmodifiableMap(roots);
            this.nativeDependencyUnknown = nativeDependencyUnknown;
        }

        boolean current(String blockName) {
            if (HologramSnapshotMetadataSource.detached())
                throw new IllegalStateException("Native descriptor refresh requires the owner thread");
            Block current = resolve(blockName);
            return block == current && nativeEpoch == HologramNativePlacement.generation()
                && codecEpoch == ResourceContainers.generation()
                && (!schema.startsWith("ae-") || current instanceof AEBaseTileBlock
                    && prototype == ((AEBaseTileBlock) current).getTileEntityClass())
                && (prototypeId < 0 || prototypeId < GregTechAPI.METATILEENTITIES.length
                    && prototype == GregTechAPI.METATILEENTITIES[prototypeId]);
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Descriptor)) return false;
            Descriptor other = (Descriptor) value;
            return schema.equals(other.schema) && block == other.block
                && metadata == other.metadata
                && adapter == other.adapter
                && prototype == other.prototype
                && codecEpoch == other.codecEpoch
                && nativeEpoch == other.nativeEpoch
                && nativeDependencyUnknown == other.nativeDependencyUnknown
                && roots.equals(other.roots);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                schema,
                System.identityHashCode(block),
                metadata,
                System.identityHashCode(adapter),
                System.identityHashCode(prototype),
                codecEpoch,
                nativeEpoch,
                nativeDependencyUnknown,
                roots);
        }

        Object domainKey(HologramSegmentContract.Domain domain) {
            // Resource codecs own costing, not native models. A codec registration creates a new record but
            // preserves an independent appearance input; native adapter/prototype changes affect both.
            return new Input(
                schema,
                EPOCH,
                new Identity(block),
                metadata,
                new Identity(adapter),
                new Identity(prototype),
                nativeEpoch,
                nativeDependencyUnknown,
                roots,
                domain == HologramSegmentContract.Domain.ASSET_NEEDS || domain == HologramSegmentContract.Domain.REPORT
                    ? codecEpoch
                    : null);
        }
    }

    static final class Rule {

        final int domains;
        final Obligation obligation;
        final int type;

        Rule(int domains, Obligation obligation, int type) {
            this.domains = domains;
            this.obligation = obligation;
            this.type = type;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Rule)) return false;
            Rule other = (Rule) value;
            return domains == other.domains && obligation == other.obligation && type == other.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(domains, obligation, type);
        }
    }

    /** Classification coverage cannot be used as a native-material credit or a successful zero-cost quote. */
    static final class Facts {

        final Descriptor descriptor;
        final Set<String> claimedPaths, unclaimedPaths, dependencyKeys;
        final Map<String, Obligation> obligations;
        final ThreadAffinity threadAffinity = ThreadAffinity.MAIN_THREAD_NATIVE;
        final long weight;
        private final EnumMap<HologramSegmentContract.Domain, Object> inputs;
        private final Map<String, Integer> pathDomains;
        private final boolean needsAssets, needsBindings;
        private final boolean sourceAbsent;

        Facts(Descriptor descriptor, String tile, String item, NBTTagCompound raw, long weight) throws IOException {
            this.descriptor = descriptor;
            this.weight = weight;
            sourceAbsent = !present(tile) && !present(item);
            Set<String> claimed = new LinkedHashSet<>(), unclaimed = new LinkedHashSet<>();
            Map<String, Obligation> roles = new LinkedHashMap<>();
            Map<String, Integer> domains = new LinkedHashMap<>();
            Map<String, Frozen> appearance = new TreeMap<>(), assets = new TreeMap<>(), bindings = new TreeMap<>();
            boolean physical = present(item), binding = false;
            if (raw != null) {
                if (raw.hasNoTags()) claimed.add("tile/");
                for (Object entry : raw.func_150296_c()) {
                    String root = (String) entry, path = "tile/" + root;
                    Rule rule = descriptor.roots.get(root);
                    NBTBase value = raw.getTag(root);
                    if (rule == null || rule.type != 0 && value.getId() != rule.type) {
                        unclaimed.add(path);
                        roles.put(path, Obligation.UNKNOWN);
                        domains.put(path, ALL);
                        continue;
                    }
                    claimed.add(path);
                    roles.put(path, rule.obligation);
                    domains.put(path, rule.domains);
                    // Unknown closure already uses exact source strings. Do not allocate a second whole tree.
                    Frozen frozen = descriptor.nativeDependencyUnknown || weight > MAX_CACHE_BYTES ? null
                        : freeze(value);
                    if ((rule.domains & APPEARANCE) != 0) appearance.put(root, frozen);
                    if ((rule.domains & ASSETS) != 0) assets.put(root, frozen);
                    if ((rule.domains & BINDINGS) != 0) bindings.put(root, frozen);
                    physical |= rule.obligation == Obligation.ITEM || rule.obligation == Obligation.FLUID
                        || rule.obligation == Obligation.ENERGY
                        || rule.obligation == Obligation.CAPACITY
                        || rule.obligation == Obligation.IDENTITY && !root.equals("id") && (rule.domains & ASSETS) != 0;
                    binding |= (rule.domains & BINDINGS) != 0;
                }
            }
            // The primary item is an atomic exact payment identity. No nested-item/recipe free inference.
            if (present(item)) {
                claimed.add("item/");
                roles.put("item/", Obligation.ITEM);
                domains.put("item/", ALL);
            }
            claimedPaths = Collections.unmodifiableSet(claimed);
            unclaimedPaths = Collections.unmodifiableSet(unclaimed);
            obligations = Collections.unmodifiableMap(roles);
            pathDomains = Collections.unmodifiableMap(domains);
            dependencyKeys = Collections.unmodifiableSet(
                new LinkedHashSet<>(
                    Arrays.asList(
                        "schema:" + descriptor.schema + ":" + EPOCH,
                        "resource-codecs:" + descriptor.codecEpoch,
                        "native-adapters:" + descriptor.nativeEpoch,
                        "registered-block-factory",
                        "native-target-and-actor-at-commit")));
            boolean fallback = descriptor.nativeDependencyUnknown || !unclaimed.isEmpty() || weight > MAX_CACHE_BYTES;
            needsAssets = physical || fallback && present(tile);
            needsBindings = binding || fallback && present(tile);
            inputs = new EnumMap<>(HologramSegmentContract.Domain.class);
            Object presence = raw == null ? HologramSegmentContract.Presence.ABSENT
                : raw.hasNoTags() ? HologramSegmentContract.Presence.EXPLICIT_EMPTY
                    : HologramSegmentContract.Presence.PRESENT_UNKNOWN;
            for (HologramSegmentContract.Domain domain : HologramSegmentContract.Domain.values()) {
                Object exact = fallback || domain == HologramSegmentContract.Domain.SCHEMA
                    || domain == HologramSegmentContract.Domain.REPORT ? tile
                        : domain == HologramSegmentContract.Domain.ASSET_NEEDS ? assets
                            : domain == HologramSegmentContract.Domain.RELATIONS ? bindings : appearance;
                inputs.put(domain, new Input(descriptor.domainKey(domain), presence, exact, item, unclaimed));
            }
        }

        Object input(HologramSegmentContract.Domain domain) {
            return inputs.get(domain);
        }

        boolean coverageComplete() {
            return sourceAbsent
                || unclaimedPaths.isEmpty() && !descriptor.nativeDependencyUnknown && weight <= MAX_CACHE_BYTES;
        }

        boolean needsAssets() {
            return needsAssets;
        }

        boolean needsBindings() {
            return needsBindings;
        }

        /** Policy includes the retained roots affecting this domain, not every unrelated checkbox. */
        Object policy(HologramSegmentContract.Domain domain, Object[] policy) {
            int mask = domain == HologramSegmentContract.Domain.ASSET_NEEDS ? ASSETS
                : domain == HologramSegmentContract.Domain.RELATIONS ? BINDINGS : APPEARANCE;
            if (domain == HologramSegmentContract.Domain.SCHEMA || sourceAbsent) return null;
            if (!coverageComplete() || domain == HologramSegmentContract.Domain.REPORT) return policy;
            List<String> selected = new ArrayList<>();
            if (policy[2] != null) for (Object root : (Object[]) policy[2]) {
                Integer affected = pathDomains.get("tile/" + root);
                if (affected != null && (affected & mask) != 0) selected.add((String) root);
            }
            Collections.sort(selected);
            boolean relevant = false, covers = false;
            for (Map.Entry<String, Integer> entry : pathDomains.entrySet()) if ((entry.getValue() & mask) != 0) {
                relevant = true;
                covers |= entry.getKey()
                    .equals("tile/cbAttrList")
                    || entry.getKey()
                        .equals("tile/gt.covers");
            }
            return new Object[] { relevant ? policy[0] : null, covers ? policy[1] : null,
                policy[2] == null ? null : selected.toArray(), descriptor.schema.startsWith("ae-") ? policy[3] : null,
                needsBindings && (mask & BINDINGS) != 0 ? policy[4] : null,
                needsBindings && (mask & BINDINGS) != 0 ? policy[5] : null };
        }
    }

    private static final class Input {

        private final Object[] values;

        Input(Object... values) {
            this.values = values;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof Input && Arrays.deepEquals(values, ((Input) value).values);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(values);
        }
    }

    private static final class Identity {

        private final Object value;

        Identity(Object value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Identity && value == ((Identity) other).value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }

    private static final class Key {

        final Descriptor descriptor;
        final String tile, item;

        Key(Descriptor descriptor, String tile, String item) {
            this.descriptor = descriptor;
            this.tile = tile;
            this.item = item;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return descriptor.equals(other.descriptor) && Objects.equals(tile, other.tile)
                && Objects.equals(item, other.item);
        }

        @Override
        public int hashCode() {
            return Objects.hash(descriptor, tile, item);
        }
    }

    static synchronized Facts analyze(HologramPresets.Cell cell) throws IOException {
        if (HologramSnapshotDataContext.active()) {
            Descriptor frozen = HologramSnapshotDataContext.descriptor(cell);
            Key key = new Key(frozen, cell.tileNbt, cell.itemNbt);
            Facts previous = CACHE.get(key);
            if (previous != null) return previous;
            NBTTagCompound raw = present(cell.tileNbt) ? HologramSnapshotDataContext.decode(cell.tileNbt) : null;
            return remember(cell, frozen, key, raw);
        }
        if (HologramSnapshotMetadataSource.detached())
            throw new IOException("NBT obligation descriptor was not frozen");
        Block block = resolve(cell.block);
        // Lookup is independent of decoding. Cached GT entries recheck the exact registered mID prototype below.
        Key key = new Key(descriptor(block, cell.metadata, null), cell.tileNbt, cell.itemNbt);
        Facts previous = CACHE.get(key);
        if (previous != null && previous.descriptor.current(cell.block)) return previous;
        if (previous != null) cacheBytes -= CACHE.remove(key).weight;
        NBTTagCompound raw = present(cell.tileNbt) ? HologramBlueprintNbt.decodeData(cell.tileNbt) : null;
        Descriptor descriptor = descriptor(block, cell.metadata, raw);
        return remember(cell, descriptor, key, raw);
    }

    private static Facts remember(HologramPresets.Cell cell, Descriptor descriptor, Key key, NBTTagCompound raw)
        throws IOException {
        long weight = 1024L + 2L * (length(cell.tileNbt) + length(cell.itemNbt))
            + 64L * (expandedBytes(cell.tileNbt) + expandedBytes(cell.itemNbt));
        // Evict before retaining immutable projection copies. The document admission accounts its own references.
        if (weight <= MAX_CACHE_BYTES)
            while (!CACHE.isEmpty() && (CACHE.size() >= 1024 || cacheBytes + weight > MAX_CACHE_BYTES)) {
                Key oldest = CACHE.keySet()
                    .iterator()
                    .next();
                cacheBytes -= CACHE.remove(oldest).weight;
            }
        Facts result = new Facts(descriptor, cell.tileNbt, cell.itemNbt, raw, weight);
        if (weight <= MAX_CACHE_BYTES) {
            CACHE.put(key, result);
            cacheBytes += weight;
        }
        return result;
    }

    static int expandedBytes(String source) throws IOException {
        return !present(source) ? 0
            : HologramSnapshotDataContext.active() ? HologramSnapshotDataContext.expandedBytes(source)
                : HologramBlueprintNbt.expandedBytes(source);
    }

    /** Operation-local deduplication survives eviction from the bounded cross-operation working set. */
    static List<Object> sourceKey(HologramPresets.Cell cell) {
        return Arrays.asList(cell.block, cell.metadata, cell.tileNbt, cell.itemNbt);
    }

    static Descriptor descriptor(Block block, int metadata, NBTTagCompound raw) {
        if (HologramSnapshotMetadataSource.detached() || HologramSnapshotDataContext.active())
            throw new IllegalStateException("Native descriptor selection requires the owner thread");
        // Adapter selection and its registration epoch must be one snapshot, even for off-thread registration.
        synchronized (HologramNativePlacement.class) {
            return selectDescriptor(block, metadata, raw);
        }
    }

    private static Descriptor selectDescriptor(Block block, int metadata, NBTTagCompound raw) {
        Map<String, Rule> rules = new LinkedHashMap<>();
        root(rules, "id", ALL, Obligation.IDENTITY, 8);
        for (String coordinate : Arrays.asList("x", "y", "z"))
            root(rules, coordinate, BINDINGS, Obligation.NETWORK_BINDING, 3);
        root(rules, HologramCoordinatePolicies.RELATIONS, BINDINGS, Obligation.NETWORK_BINDING, 9);
        String type = block == null ? ""
            : block.getClass()
                .getName();
        Object adapter = HologramNativePlacement.adapter(block), prototype = null;
        String schema = "unknown";
        boolean unknown = true;
        int prototypeId = -1;
        boolean builtin = adapter == null || adapter.getClass()
            .getName()
            .equals(HologramNativePlacement.class.getName() + "$Builtin")
            || adapter.getClass() == HologramAeBlueprint.NativeAdapter.class;
        if (builtin && (block == Blocks.chest || block == Blocks.trapped_chest
            || block == Blocks.hopper
            || block == Blocks.dispenser
            || block == Blocks.dropper)) {
            schema = "vanilla-container";
            unknown = false;
            // ItemStack tags may contain reviewed coordinate cards; their values also bind relationship facts.
            root(rules, "Items", ASSETS | BINDINGS, Obligation.ITEM, 9);
            root(rules, "CustomName", APPEARANCE, Obligation.APPEARANCE, 8);
            if (block == Blocks.hopper) root(rules, "TransferCooldown", ASSETS, Obligation.CAPACITY, 3);
        } else if (builtin && HologramBlockIdentity.vanillaSign(block)) {
            schema = "vanilla-sign";
            unknown = false;
            for (int i = 1; i <= 4; i++) root(rules, "Text" + i, APPEARANCE, Obligation.APPEARANCE, 8);
        } else if (builtin && carpenter(type)) {
            schema = "carpenters-3.7.3";
            unknown = false;
            root(rules, "cbMetadata", APPEARANCE | ASSETS, Obligation.IDENTITY, 3);
            root(rules, "cbAttrList", ALL, Obligation.ITEM, 9);
            root(rules, "cbOwner", BINDINGS, Obligation.NETWORK_BINDING, 8);
            root(rules, "cbDesign", APPEARANCE, Obligation.APPEARANCE, 8);
            for (int i = 0; i < 7; i++) root(rules, "cbChiselDesign_" + i, APPEARANCE, Obligation.APPEARANCE, 8);
        } else if (builtin && type.equals("gcewing.architecture.common.block.BlockShape")) {
            schema = "architecture-1.12.18";
            unknown = false;
            for (String root : Arrays.asList("Shape", "BaseData", "Data2"))
                root(rules, root, APPEARANCE | ASSETS, Obligation.ITEM, 3);
            for (String root : Arrays.asList("BaseName", "Name2"))
                root(rules, root, APPEARANCE | ASSETS, Obligation.ITEM, 8);
            for (String root : Arrays.asList("side", "turn", "offsetX"))
                root(rules, root, APPEARANCE, Obligation.APPEARANCE, 1);
            // Disconnected is also written to the paid primary shape item.
            root(rules, "Disconnected", APPEARANCE | ASSETS | BINDINGS, Obligation.IDENTITY, 3);
        } else if (builtin && type.equals("com.creativemd.littletiles.common.blocks.BlockTile")) {
            schema = "littletiles-1.6.59";
            unknown = false;
            root(rules, "tilesCount", ALL, Obligation.IDENTITY, 3);
            // Tile plugins and embedded TEs can read arbitrary fields. Each exact tile subtree remains coupled.
            if (raw != null) for (Object key : raw.func_150296_c())
                if (((String) key).matches("t[0-9]+")) root(rules, (String) key, ALL, Obligation.UNKNOWN, 10);
            unknown = raw != null && raw.getInteger("tilesCount") > 0;
        } else if (builtin && type.equals("appeng.block.misc.BlockInterface")
            && block instanceof AEBaseTileBlock
            && ((AEBaseTileBlock) block).getTileEntityClass() != null
            && ((AEBaseTileBlock) block).getTileEntityClass()
                .getName()
                .equals("appeng.tile.misc.TileInterface")) {
                    schema = "ae-interface-1080";
                    prototype = ((AEBaseTileBlock) block).getTileEntityClass();
                    unknown = false;
                    for (String root : Arrays.asList("storage", "patterns", "upgrades", "inv"))
                        root(rules, root, ASSETS | BINDINGS, Obligation.ITEM, 10);
                    root(rules, "waitingToSend", ASSETS | BINDINGS, Obligation.ITEM, 9);
                    root(rules, "config", ASSETS, Obligation.GHOST_RECIPE, 10);
                    for (String root : Arrays.asList("orientation_forward", "orientation_up", "customName"))
                        root(rules, root, APPEARANCE, Obligation.APPEARANCE, 8);
                    root(rules, "paintedColor", APPEARANCE, Obligation.APPEARANCE, 1);
                    root(rules, "pointAt", APPEARANCE | BINDINGS, Obligation.NETWORK_BINDING, 3);
                    root(rules, "priority", BINDINGS, Obligation.NETWORK_BINDING, 3);
                    root(rules, "proxy", ASSETS | BINDINGS, Obligation.NETWORK_BINDING, 10);
                    root(rules, "internalCurrentPower", ASSETS, Obligation.ENERGY, 6);
                    for (Settings setting : Settings.values())
                        root(rules, setting.name(), ASSETS | BINDINGS, Obligation.GHOST_RECIPE, 8);
                    for (String root : Arrays.asList(
                        HologramNetworkBindings.GROUP,
                        HologramNetworkBindings.ROLE,
                        HologramNetworkBindings.HINT,
                        HologramNetworkBindings.CAPTURE_ANCHOR))
                        root(rules, root, ASSETS | BINDINGS, Obligation.NETWORK_BINDING, 0);
                } else
            if (type.equals("gregtech.common.blocks.BlockMachines")) {
                schema = "gt-base-5.09.54.205";
                if (raw != null && raw.hasKey("mID", 3)) {
                    prototypeId = raw.getInteger("mID");
                    if (prototypeId >= 0 && prototypeId < GregTechAPI.METATILEENTITIES.length)
                        prototype = GregTechAPI.METATILEENTITIES[prototypeId];
                    else prototypeId = -1;
                }
                root(rules, "mID", ALL, Obligation.IDENTITY, 3);
                root(rules, "Inventory", ASSETS, Obligation.ITEM, 9);
                for (String root : Arrays.asList("mStoredSteam", "mStoredEnergy"))
                    root(rules, root, ASSETS, Obligation.ENERGY, 4);
                root(rules, "gt.covers", APPEARANCE | ASSETS, Obligation.ITEM, 9);
                for (String root : Arrays.asList("mFacing", "mColor", "mLightValue"))
                    root(rules, root, APPEARANCE, Obligation.APPEARANCE, 0);
                for (String root : Arrays.asList("mOwnerName", "mOwnerUuid"))
                    root(rules, root, ASSETS | BINDINGS, Obligation.NETWORK_BINDING, 8);
                // mID selects plugin code including custom textures; base-field ownership does not prove reader
                // independence.
            }
        return new Descriptor(schema, block, metadata, adapter, prototype, prototypeId, rules, unknown);
    }

    private static void root(Map<String, Rule> rules, String path, int domains, Obligation obligation, int type) {
        rules.put(path, new Rule(domains, obligation, type));
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    private static Block resolve(String value) {
        return value == null || value.isEmpty() ? null : Block.getBlockFromName(value);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean carpenter(String type) {
        String prefix = "com.carpentersblocks.block.BlockCarpenters";
        return type.startsWith(prefix) && Arrays
            .asList(
                "Block",
                "Barrier",
                "Bed",
                "Button",
                "CollapsibleBlock",
                "DaylightSensor",
                "Door",
                "FlowerPot",
                "GarageDoor",
                "Gate",
                "Hatch",
                "Ladder",
                "Lever",
                "PressurePlate",
                "Safe",
                "Slope",
                "Stairs",
                "Torch")
            .contains(type.substring(prefix.length()));
    }

    /** Private typed snapshots preserve list order/type, numeric type and signed zero; no mutable NBT escapes. */
    private static final class Frozen {

        final Object[] value;

        Frozen(Object... value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof Frozen && Arrays.deepEquals(this.value, ((Frozen) value).value);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(value);
        }
    }

    private static Frozen freeze(NBTBase tag) throws IOException {
        Object value;
        if (tag instanceof NBTTagCompound) {
            Map<String, Frozen> children = new TreeMap<>();
            for (Object key : ((NBTTagCompound) tag).func_150296_c())
                children.put((String) key, freeze(((NBTTagCompound) tag).getTag((String) key)));
            value = children;
        } else if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            List<Frozen> children = new ArrayList<>();
            try {
                for (Object child : (List<?>) LIST_ENTRIES.get(list)) children.add(freeze((NBTBase) child));
            } catch (IllegalAccessException failure) {
                throw new IOException("NBT list projection unavailable", failure);
            }
            return new Frozen(tag.getId(), list.func_150303_d(), children);
        } else if (tag instanceof NBTTagByteArray) value = ((NBTTagByteArray) tag).func_150292_c()
            .clone();
        else if (tag instanceof NBTTagIntArray) value = ((NBTTagIntArray) tag).func_150302_c()
            .clone();
        else if (tag instanceof NBTTagString) value = ((NBTTagString) tag).func_150285_a_();
        else if (tag instanceof NBTPrimitive) {
            NBTPrimitive number = (NBTPrimitive) tag;
            value = tag.getId() == 5 ? Float.floatToRawIntBits(number.func_150288_h())
                : tag.getId() == 6 ? Double.doubleToRawLongBits(number.func_150286_g()) : number.func_150291_c();
        } else throw new IOException("Invalid typed NBT projection");
        return new Frozen(tag.getId(), value);
    }

    private static Field listEntriesField() {
        Field selected = null;
        for (Field field : NBTTagList.class.getDeclaredFields()) if (List.class.isAssignableFrom(field.getType())) {
            if (selected != null) throw new IllegalStateException("Ambiguous NBT list storage");
            selected = field;
        }
        if (selected == null) throw new IllegalStateException("NBT list storage unavailable");
        selected.setAccessible(true);
        return selected;
    }

    private HologramNbtObligations() {}
}
