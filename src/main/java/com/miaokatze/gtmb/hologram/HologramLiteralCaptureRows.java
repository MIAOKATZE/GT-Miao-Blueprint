// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

import net.minecraft.block.Block;

/** Private append-only capture backing. Relative coordinates fit 26+26+8 bits. Rich rows keep full identity. */
final class HologramLiteralCaptureRows extends AbstractList<HologramPresets.Cell> implements RandomAccess {

    private static final int SHIFT = 12, PAGE = 1 << SHIFT, MASK = PAGE - 1;
    private final List<long[]> positions = new ArrayList<>();
    private final List<int[]> ids = new ArrayList<>();
    private final List<HologramPresets.Cell> ordinary = new ArrayList<>();
    private final List<HologramPresets.Cell> special = new ArrayList<>();

    @com.github.bsideup.jabel.Desugar
    private record Plain(int state, String item, int damage) {}

    private final Map<Plain, Integer> palette = new HashMap<>();
    private Plain last;
    private int lastId;
    private int count;
    // The raw server editor still materializes a full projection downstream. Bound that path
    // separately; it must never inherit the higher limit of the packed native-file route by accident.
    private final boolean feedsRawProjection;

    HologramLiteralCaptureRows() {
        this(false);
    }

    HologramLiteralCaptureRows(boolean feedsRawProjection) {
        this.feedsRawProjection = feedsRawProjection;
    }

    List<HologramPresets.Cell> sensitive() {
        return special;
    }

    @Override
    public int size() {
        return count;
    }

    void append(HologramPresets.Cell row, Block block, boolean withTile) throws IOException {
        if (feedsRawProjection) HologramBlueprintLimits.require((long) count + 1);
        else HologramBlueprintLimits.requireLocal((long) count + 1);
        if (row.x < 0 || row.x >= 1 << 26 || row.y < 0 || row.y > 255 || row.z < 0 || row.z >= 1 << 26)
            throw new IOException("区域相对坐标超出范围");
        // Compress only a vanilla non-TE host that has no captured per-coordinate state. Other mods,
        // fluids with custom state, quantum nodes, actual tiles and signed identity use the ordinary adapters.
        boolean plain = !withTile && !block.hasTileEntity(row.metadata)
            && row.item != null
            && row.tileNbt == null
            && row.itemNbt == null
            && row.facing == -1
            && row.block != null
            && row.block.startsWith("minecraft:")
            && block.getClass()
                .getName()
                .startsWith("net.minecraft.block.");
        int id;
        if (plain) {
            int key = (Block.getIdFromBlock(block) << 4) | (row.metadata & 15);
            if (last != null && last.state == key && last.damage == row.damage && row.item.equals(last.item))
                id = lastId;
            else {
                var next = new Plain(key, row.item, row.damage);
                Integer known = palette.get(next);
                if (known == null) {
                    if (ordinary.size() >= 65536) {
                        // Bound the interning cache, not the supported metadata. Preserve this full row exactly.
                        HologramBlueprintLimits.require((long) special.size() + 1);
                        id = ~special.size();
                        special.add(row);
                    } else {
                        id = ordinary.size();
                        ordinary.add(row);
                        palette.put(next, id);
                    }
                } else id = known;
                if (id >= 0) {
                    last = next;
                    lastId = id;
                }
            }
        } else {
            // Do not turn the old raw DTO ceiling into an unbounded heap of unrelated machine/NBT objects.
            HologramBlueprintLimits.require((long) special.size() + 1);
            id = ~special.size();
            special.add(row);
        }
        int offset = count & MASK;
        if (offset == 0) {
            positions.add(new long[PAGE]);
            ids.add(new int[PAGE]);
        }
        positions.get(count >> SHIFT)[offset] = ((long) row.x << 34) | ((long) row.z << 8) | row.y;
        ids.get(count >> SHIFT)[offset] = id;
        count++;
        modCount++;
    }

    int descriptor(int i) {
        return ids.get(i >> SHIFT)[i & MASK];
    }

    int coordinate(int i, int axis) {
        long pos = positions.get(i >> SHIFT)[i & MASK];
        return axis == 0 ? (int) (pos >>> 34) : axis == 1 ? (int) (pos & 255) : (int) ((pos >>> 8) & 0x3ffffff);
    }

    @Override
    public HologramPresets.Cell get(int i) {
        if (i < 0 || i >= count) throw new IndexOutOfBoundsException();
        int page = i >> SHIFT, offset = i & MASK, id = ids.get(page)[offset];
        if (id < 0) return special.get(~id);
        var prototype = ordinary.get(id);
        long pos = positions.get(page)[offset];
        var cell = new HologramPresets.Cell();
        cell.item = prototype.item;
        cell.damage = prototype.damage;
        cell.block = prototype.block;
        cell.metadata = prototype.metadata;
        cell.x = (int) (pos >>> 34);
        cell.z = (int) ((pos >>> 8) & 0x3ffffff);
        cell.y = (int) (pos & 255);
        return cell;
    }
}
