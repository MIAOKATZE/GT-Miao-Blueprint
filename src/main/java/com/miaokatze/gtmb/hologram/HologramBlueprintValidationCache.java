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
    private final Map<Key, String> deidentified = new HashMap<>();
    private final Map<String, Integer> networkGroups = new HashMap<>();
    private final Map<Key, HologramNetworkBindings.Projection> networkProjections = new HashMap<>();
    private final Map<String, Boolean> unsealedItems = new HashMap<>();
    private final Map<Key, HologramAePolicy.Identity> identities = new HashMap<>();
    private final Map<Key, String> aeShells = new HashMap<>();
    private int entries, stringChars;
    long registryHits, registryMisses, filterHits, filterMisses, gtHits, gtMisses, damageHits, damageMisses;
    long syntaxHits, syntaxMisses;
    long deidentifyHits, deidentifyMisses, networkGroupHits, networkGroupMisses, networkHits, networkMisses;
    long unsealedItemHits, unsealedItemMisses;
    long identityHits, identityMisses, aeShellHits, aeShellMisses;

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
        net.minecraft.nbt.NBTTagCompound source = HologramBlueprintNbt.decode(encoded);
        HologramCarpentersCompatibility.stripOwner(block, source);
        if (tile) HologramCoordinatePolicies.validatePaymentPayload(source);
        String result = HologramBlueprintNbt.filterDecoded(tile ? block : null, source, keys, retainCovers);
        if (admit(encoded.length() + (result == null ? 0 : result.length()))) filtered.put(key, result);
        return result;
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

    String deidentify(Block block, String encoded, int level) throws IOException {
        if (encoded == null || encoded.isEmpty()) return encoded;
        Key key = new Key(block, level, encoded, null);
        String cached = deidentified.get(key);
        if (cached != null) {
            deidentifyHits++;
            return cached;
        }
        deidentifyMisses++;
        String result = HologramDeidentification.project(block, encoded, level);
        if (admit(encoded.length() + result.length())) deidentified.put(key, result);
        return result;
    }

    int networkGroup(String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty()) return 0;
        Integer cached = networkGroups.get(encoded);
        if (cached != null) {
            networkGroupHits++;
            return cached;
        }
        networkGroupMisses++;
        net.minecraft.nbt.NBTTagCompound tag = HologramBlueprintNbt.decode(encoded);
        int result = tag != null && tag.hasKey(HologramNetworkBindings.GROUP) ? Integer.parseInt(
            HologramNetworkBindings.group(tag)
                .substring(1))
            : 0;
        if (admit(encoded.length())) networkGroups.put(encoded, result);
        return result;
    }

    HologramNetworkBindings.Projection networkProjection(Block block, int damage, String tile, String item) {
        HologramNetworkBindings.Projection result = networkProjections.get(new Key(block, damage, tile, item));
        if (result == null) networkMisses++;
        else networkHits++;
        return result;
    }

    void networkProjection(Block block, int damage, String tile, String item,
        HologramNetworkBindings.Projection result) {
        int chars = length(tile) + length(item) + length(result.tile) + length(result.item);
        if (admit(chars)) networkProjections.put(new Key(block, damage, tile, item), result);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    /** A pass-local root-key absence summary, never a decoded item tree or a placement authorization. */
    boolean knownUnsealedItem(String encoded) {
        if (unsealedItems.containsKey(encoded)) {
            unsealedItemHits++;
            return true;
        }
        unsealedItemMisses++;
        return false;
    }

    void rememberUnsealedItem(String encoded) {
        if (admit(encoded.length())) unsealedItems.put(encoded, Boolean.TRUE);
    }

    HologramAePolicy.Identity identity(Block block, int damage, String tile, String item) {
        HologramAePolicy.Identity result = identities.get(new Key(block, damage, tile, item));
        if (result == null) identityMisses++;
        else identityHits++;
        return result;
    }

    void identity(Block block, int damage, String tile, String item, HologramAePolicy.Identity result) {
        if (admit(length(tile) + length(item) + length(result.tile) + length(result.item)))
            identities.put(new Key(block, damage, tile, item), result);
    }

    String aeShell(Block block, int metadata, String input, HologramAeBlueprint.Analysis analysis) throws IOException {
        Key key = new Key(block, metadata, input, null);
        String result = aeShells.get(key);
        if (result != null) {
            aeShellHits++;
            return result;
        }
        aeShellMisses++;
        result = HologramBlueprintNbt.encodeCanonical(block, analysis.payload());
        if (admit(length(input) + length(result))) aeShells.put(key, result);
        return result;
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
