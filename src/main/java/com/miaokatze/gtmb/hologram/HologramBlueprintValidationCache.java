package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.block.Block;
import net.minecraft.block.BlockColored;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockGlass;
import net.minecraft.block.BlockNewLog;
import net.minecraft.block.BlockOldLog;
import net.minecraft.block.BlockSand;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.BlockStone;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** One synchronous validation pass. Only immutable identities/results are retained; never player/world state. */
final class HologramBlueprintValidationCache {

    private static final int PROJECTION_EPOCH = 1;
    private static final long PROJECTION_BYTES = 8L * 1024 * 1024;
    private static final java.util.LinkedHashMap<ProjectionKey, Projection> PROJECTIONS = new java.util.LinkedHashMap<>(
        16,
        0.75f,
        true);
    private static long projectionBytes;

    static final int MAX_ENTRIES = 4096;
    static final int MAX_STRING_CHARS = 4 * 1024 * 1024;
    private final int entryLimit, charLimit;
    private final List<String> keys;
    private final boolean retainCovers;
    private final Map<String, Item> items = new HashMap<>();
    private final Map<String, Block> blocks = new HashMap<>();
    private final Map<String, Boolean> registryTexts = new HashMap<>();
    private final Map<Key, String> filtered = new HashMap<>();
    private final Map<Key, String> gtPayloads = new HashMap<>();
    private final Map<Key, Integer> vanillaDamage = new HashMap<>();
    private int entries, stringChars;
    long registryHits, registryMisses, filterHits, filterMisses, gtHits, gtMisses, damageHits, damageMisses;
    long syntaxHits, syntaxMisses;
    long projectionHits, projectionMisses, filterComputations;

    HologramBlueprintValidationCache(HologramPresets.Preset preset) {
        this(preset, MAX_ENTRIES, MAX_STRING_CHARS);
    }

    HologramBlueprintValidationCache(HologramPresets.Preset preset, int entryLimit, int charLimit) {
        this.entryLimit = Math.max(0, entryLimit);
        this.charLimit = Math.max(0, charLimit);
        keys = !preset.retainNbt ? java.util.Collections.emptyList()
            : preset.retainedNbtKeys == null ? null : new ArrayList<>(preset.retainedNbtKeys);
        retainCovers = preset.retainCovers;
    }

    ItemStack stack(String id, int damage) {
        if (id == null) return null;
        Item item;
        if (items.containsKey(id)) {
            registryHits++;
            item = items.get(id);
        } else {
            registryMisses++;
            Object found = Item.itemRegistry.getObject(id);
            item = found instanceof Item ? (Item) found : null;
            if (admit(id.length())) items.put(id, item);
        }
        return item == null ? null : new ItemStack(item, 1, damage);
    }

    Block block(String id) {
        if (id == null) return null;
        if (blocks.containsKey(id)) {
            registryHits++;
            return blocks.get(id);
        }
        registryMisses++;
        Object found = Block.blockRegistry.getObject(id);
        Block block = found instanceof Block && (found != Blocks.air || "minecraft:air".equals(id)) ? (Block) found
            : null;
        if (admit(id.length())) blocks.put(id, block);
        return block;
    }

    Block block(HologramPresets.Cell cell) {
        return HologramSnapshotMetadataSource.detached() ? cell.block() : block(cell.block);
    }

    void registryText(String value, boolean optional) throws IOException {
        if (value == null) {
            HologramPresets.registryText(null, optional);
            return;
        }
        if (registryTexts.containsKey(value)) {
            syntaxHits++;
            return;
        }
        syntaxMisses++;
        HologramPresets.registryText(value, optional);
        if (admit(value.length())) registryTexts.put(value, Boolean.TRUE);
    }

    void filter(HologramPresets.Cell cell) throws IOException {
        Block block = block(cell);
        cell.tileNbt = filter(block, cell.tileNbt, true);
        cell.itemNbt = filter(block, cell.itemNbt, false);
    }

    private String filter(Block block, String encoded, boolean tile) throws IOException {
        if (encoded == null || encoded.isEmpty()) return null;
        Key key = new Key(block, tile ? 1 : 0, encoded, null);
        if (filtered.containsKey(key)) {
            filterHits++;
            return filtered.get(key);
        }
        filterMisses++;
        HologramBlueprintNbt.ExactBlob blob = HologramBlueprintNbt.exact(encoded);
        // Only audited built-in block semantics with no item/cover registry dependencies can cross passes.
        // Unknown integrations retain the complete old filtering path, even with a parsed blob hit.
        boolean share = entryLimit > 0 && charLimit > 0
            && !HologramSnapshotDataContext.active()
            && vanillaMaterial(block)
            && blob.registryIndependent()
            && !"false".equals(System.getProperty("gtmb.segment.validationCache"));
        ProjectionKey projectionKey = share ? new ProjectionKey(block, tile, encoded, keys, retainCovers) : null;
        if (share) synchronized (PROJECTIONS) {
            Projection found = PROJECTIONS.get(projectionKey);
            if (found != null) {
                projectionHits++;
                if (admit(encoded.length() + (found.value == null ? 0 : found.value.length())))
                    filtered.put(key, found.value);
                return found.value;
            }
            projectionMisses++;
        }
        filterComputations++;
        net.minecraft.nbt.NBTTagCompound source = HologramBlueprintNbt.decode(encoded);
        HologramCarpentersCompatibility.stripOwner(block, source);
        if (tile) HologramCoordinatePolicies.validatePaymentPayload(source);
        String result = HologramBlueprintNbt.filterDecoded(tile ? block : null, source, keys, retainCovers);
        if (share) retainProjection(projectionKey, result, blob.expandedBytes());
        if (admit(encoded.length() + (result == null ? 0 : result.length()))) filtered.put(key, result);
        return result;
    }

    static void clearProjectionCache() {
        synchronized (PROJECTIONS) {
            PROJECTIONS.clear();
            projectionBytes = 0;
        }
    }

    private static void retainProjection(ProjectionKey key, String value, int expanded) {
        long cost = 256L + 2L * key.encoded.length() + (value == null ? 0 : 2L * value.length()) + 4L * expanded;
        if (key.keys != null) for (String selected : key.keys) cost += 40L + 2L * selected.length();
        if (cost > PROJECTION_BYTES) return;
        synchronized (PROJECTIONS) {
            while (!PROJECTIONS.isEmpty()
                && (PROJECTIONS.size() >= MAX_ENTRIES || cost > PROJECTION_BYTES - projectionBytes)) {
                ProjectionKey oldest = PROJECTIONS.keySet()
                    .iterator()
                    .next();
                projectionBytes -= PROJECTIONS.remove(oldest).cost;
            }
            Projection previous = PROJECTIONS.put(key, new Projection(value, cost));
            projectionBytes += cost - (previous == null ? 0 : previous.cost);
        }
    }

    private static final class Projection {

        final String value;
        final long cost;

        Projection(String value, long cost) {
            this.value = value;
            this.cost = cost;
        }
    }

    private static final class ProjectionKey {

        final Block block;
        final boolean tile, covers;
        final String encoded;
        final List<String> keys;
        final int epoch = PROJECTION_EPOCH;

        ProjectionKey(Block block, boolean tile, String encoded, List<String> keys, boolean covers) {
            this.block = block;
            this.tile = tile;
            this.encoded = encoded;
            this.keys = keys;
            this.covers = covers;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(block), tile, covers, encoded, keys, epoch);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ProjectionKey)) return false;
            ProjectionKey key = (ProjectionKey) other;
            return block == key.block && tile == key.tile
                && covers == key.covers
                && epoch == key.epoch
                && encoded.equals(key.encoded)
                && Objects.equals(keys, key.keys);
        }
    }

    String validateGt(String encoded, int damage, String tileId, boolean optionalItem, boolean absentPrototype)
        throws IOException {
        Key key = new Key(null, damage, encoded, tileId + ":" + optionalItem + ":" + absentPrototype);
        if (gtPayloads.containsKey(key)) {
            gtHits++;
            return gtPayloads.get(key);
        }
        gtMisses++;
        net.minecraft.nbt.NBTTagCompound payload = HologramBlueprintNbt.decode(encoded);
        if (!optionalItem && absentPrototype && payload != null && "BaseMetaPipeEntity".equals(payload.getString("id")))
            tileId = "BaseMetaPipeEntity";
        if (!optionalItem || payload != null && (payload.hasKey("mID") || payload.hasKey("id")))
            HologramPresets.validateGtPayload(payload, damage, tileId);
        if (admit(encoded == null ? 0 : encoded.length())) gtPayloads.put(key, tileId);
        return tileId;
    }

    int materialDamage(Block block, int metadata) throws IOException {
        // Third-party getDamageValue callbacks may inspect more than the detached block/meta carrier.
        if (block == null || !vanillaMaterialClass(block.getClass()))
            return HologramPresets.materialDamage(block, metadata);
        Key key = new Key(block, metadata, null, null);
        Integer value = vanillaDamage.get(key);
        if (value != null) {
            damageHits++;
            return value;
        }
        damageMisses++;
        int result = HologramPresets.materialDamage(block, metadata);
        if (admit(0)) vanillaDamage.put(key, result);
        return result;
    }

    /** Rechecks the payment and native-adapter boundaries on every call; only vanilla pick damage is memoized. */
    void validateMaterialVariant(Block block, int metadata, ItemStack paid) throws IOException {
        HologramPresets.validateMaterialVariant(block, metadata, paid, this);
    }

    static boolean vanillaMaterial(Block block) {
        return block != null && vanillaMaterialClass(block.getClass());
    }

    boolean ordinaryMaterial(Block block, int metadata) {
        return block != null && !block.hasTileEntity(metadata)
            && (vanillaMaterial(block) || HologramMaterialDescriptor.kind(block) != 0);
    }

    /** Audited vanilla pick/drop implementations read only metadata; subclasses keep the uncached callback. */
    private static boolean vanillaMaterialClass(Class<?> type) {
        return type == BlockStone.class || type == BlockDirt.class
            || type == BlockColored.class
            || type == BlockGlass.class
            || type == BlockStainedGlass.class
            || type == BlockSand.class
            || type == BlockOldLog.class
            || type == BlockNewLog.class;
    }

    int entries() {
        return entries;
    }

    int stringChars() {
        return stringChars;
    }

    private boolean admit(int chars) {
        if (entries >= entryLimit || chars > charLimit - stringChars) return false;
        entries++;
        stringChars += chars;
        return true;
    }

    /** Strings already belong to the immutable source encoding: no mutable NBT hashing or copying per lookup. */
    private static final class Key {

        private final Block block;
        private final int variant;
        private final String encoded, extra;

        Key(Block block, int variant, String encoded, String extra) {
            this.block = block;
            this.variant = variant;
            this.encoded = encoded;
            this.extra = extra;
        }

        @Override
        public int hashCode() {
            int hash = 31 * System.identityHashCode(block) + variant;
            hash = 31 * hash + Objects.hashCode(encoded);
            return 31 * hash + Objects.hashCode(extra);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return block == key.block && variant == key.variant
                && Objects.equals(encoded, key.encoded)
                && Objects.equals(extra, key.extra);
        }
    }
}
