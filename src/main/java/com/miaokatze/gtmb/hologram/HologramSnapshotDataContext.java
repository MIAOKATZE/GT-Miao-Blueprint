package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

/** Main-resolved NBT values. Detached consumers can copy known values and can never resolve new registry identities. */
final class HologramSnapshotDataContext {

    private static final ThreadLocal<HologramSnapshotDataContext> ACTIVE = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final Map<String, NBTTagCompound> decoded = new HashMap<>();
    private final Map<BlockIdentity, FrozenBlock> blocks = new HashMap<>();

    static boolean active() {
        return ACTIVE.get() != null;
    }

    static void enter(HologramSnapshotDataContext context) {
        ACTIVE.set(context);
    }

    static void exit() {
        ACTIVE.remove();
    }

    NBTTagCompound freeze(String encoded) throws IOException {
        if (Thread.currentThread() != owner || active())
            throw new IllegalStateException("NBT identity freezing requires its owner thread");
        if (encoded == null || encoded.isEmpty()) return null;
        NBTTagCompound tag = decoded.get(encoded);
        if (tag == null) {
            tag = HologramBlueprintNbt.decode(encoded);
            decoded.put(encoded, tag);
        }
        return tag;
    }

    /** Native identity checks depend on the complete payload, never merely the shared block type. */
    void freezeBlock(HologramPresets.Cell cell) throws IOException {
        if (Thread.currentThread() != owner || active())
            throw new IllegalStateException("Block identity freezing requires its owner thread");
        Block block = cell.block();
        NBTTagCompound payload = freeze(cell.tileNbt);
        freeze(cell.itemNbt);
        BlockIdentity identity = new BlockIdentity(block, cell.damage, cell.tileNbt);
        FrozenBlock frozen = blocks.get(identity);
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
        NBTTagCompound tag = context == null ? null : context.decoded.get(encoded);
        if (tag == null) throw new IOException("Snapshot NBT identity was not frozen");
        return (NBTTagCompound) tag.copy();
    }
}
