package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

/** Task-local compressed references and owner-resolved identities; only a small window retains expanded trees. */
final class HologramSnapshotDataContext {

    // A retained-tree count, not an estimate of Java heap bytes. Existing per-payload limits still apply.
    private static final int MAX_DECODED = 2;
    private static final ThreadLocal<HologramSnapshotDataContext> ACTIVE = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final Map<String, Summary> admitted = new HashMap<>();
    private final Map<String, Integer> itemIds = new HashMap<>();
    private final Map<String, NBTTagCompound> decoded = new LinkedHashMap<>(4, 0.75f, true);
    private final Map<BlockIdentity, FrozenBlock> blocks = new HashMap<>();
    private long ownerDecodes, detachedDecodes;

    static boolean active() {
        return ACTIVE.get() != null;
    }

    static void enter(HologramSnapshotDataContext context) {
        ACTIVE.set(context);
    }

    static void exit() {
        HologramSnapshotDataContext context = ACTIVE.get();
        if (context != null) context.decoded.clear();
        ACTIVE.remove();
    }

    /** Owner consumers borrow a read-only view; detached consumers always receive their own mutable copy. */
    NBTTagCompound freeze(String encoded) throws IOException {
        requireOwner();
        if (encoded == null || encoded.isEmpty()) return null;
        return resolve(encoded, true);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner || active())
            throw new IllegalStateException("NBT identity freezing requires its owner thread");
    }

    private Summary summary(String encoded) throws IOException {
        requireOwner();
        if (encoded == null || encoded.isEmpty()) return null;
        if (!admitted.containsKey(encoded)) resolve(encoded, true);
        return admitted.get(encoded);
    }

    void rootKeys(String tile, String item, Set<String> keys) throws IOException {
        Summary tileSummary = summary(tile), itemSummary = summary(item);
        if (tileSummary != null) java.util.Collections.addAll(keys, tileSummary.rootKeys);
        if (itemSummary != null) java.util.Collections.addAll(keys, itemSummary.rootKeys);
    }

    boolean hasNetwork(String encoded) throws IOException {
        Summary summary = summary(encoded);
        return summary != null && summary.network;
    }

    private NBTTagCompound resolve(String encoded, boolean ownerRead) throws IOException {
        boolean first = !admitted.containsKey(encoded);
        if (first && !ownerRead) throw new IOException("Snapshot NBT identity was not frozen");
        NBTTagCompound tag = decoded.get(encoded);
        if (tag == null) {
            if (decoded.size() == MAX_DECODED) decoded.remove(
                decoded.keySet()
                    .iterator()
                    .next());
            if (ownerRead) ownerDecodes++;
            else detachedDecodes++;
            tag = HologramBlueprintNbt.decodeSnapshot(encoded, itemIds, first);
            if (first) admitted.put(encoded, new Summary(tag));
            decoded.put(encoded, tag);
        }
        return tag;
    }

    /** Native identity checks depend on the complete payload, never merely the shared block type. */
    void freezeBlock(HologramPresets.Cell cell) throws IOException {
        if (Thread.currentThread() != owner || active())
            throw new IllegalStateException("Block identity freezing requires its owner thread");
        Block block = cell.block();
        BlockIdentity identity = new BlockIdentity(block, cell.damage, cell.tileNbt);
        FrozenBlock frozen = blocks.get(identity);
        NBTTagCompound payload = frozen != null || cell.tileNbt == null || cell.tileNbt.isEmpty() ? null
            : resolve(cell.tileNbt, true);
        summary(cell.itemNbt);
        if (frozen == null) {
            HologramCoordinatePolicies.freezeSensorSlots(cell, payload);
            boolean boundary = HologramAeBlueprint.supports(block, payload) || HologramNetworkBindings.node(block)
                || payload != null && (payload.hasKey(HologramNetworkBindings.GROUP)
                    || payload.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR))
                || HologramNetworkSchemas.removeExternalReferences(
                    block,
                    cell.damage,
                    payload == null ? null : (NBTTagCompound) payload.copy());
            frozen = new FrozenBlock(boundary, java.util.Collections.unmodifiableSet(cell.frozenSensorSlots));
            // Bound auxiliary classification storage when every cell carries a distinct native payload.
            if (blocks.size() < 4096) blocks.put(identity, frozen);
        }
        cell.frozenSensorSlots = frozen.sensorSlots;
        cell.metadataIdentityBoundary = frozen.boundary;
        cell.frozenBlock = block;
        cell.frozenBlockResolved = true;
    }

    private static final class Summary {

        final String[] rootKeys;
        final boolean network;

        Summary(NBTTagCompound tag) {
            rootKeys = (String[]) tag.func_150296_c()
                .toArray(new String[0]);
            network = tag.hasKey(HologramNetworkBindings.GROUP);
        }
    }

    private static final class FrozenBlock {

        final boolean boundary;
        final Set<Integer> sensorSlots;

        FrozenBlock(boolean boundary, Set<Integer> sensorSlots) {
            this.boundary = boundary;
            this.sensorSlots = sensorSlots;
        }
    }

    private static final class BlockIdentity {

        final Block block;
        final int damage;
        final String tile;

        BlockIdentity(Block block, int damage, String tile) {
            this.block = block;
            this.damage = damage;
            this.tile = tile;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * System.identityHashCode(block) + damage) + Objects.hashCode(tile);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BlockIdentity)) return false;
            BlockIdentity identity = (BlockIdentity) other;
            return block == identity.block && damage == identity.damage && Objects.equals(tile, identity.tile);
        }
    }

    static NBTTagCompound decode(String encoded) throws IOException {
        HologramSnapshotDataContext context = ACTIVE.get();
        if (context == null) throw new IOException("Snapshot NBT identity was not frozen");
        return (NBTTagCompound) context.resolve(encoded, false)
            .copy();
    }
}
