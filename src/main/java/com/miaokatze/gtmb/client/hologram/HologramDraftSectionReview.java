// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.FrozenDraft;

/** In-memory geometry-only review of one owned private draft; not a portable import or save capability. */
final class HologramDraftSectionReview {

    static final int CELLS = 16_384, SECTIONS = 256, SLOTS = 4096, PALETTE = 256;
    private static final Pattern BLOCK = Pattern.compile("minecraft:[a-z0-9_]+");
    private final HologramRegionCache geometry;
    private final List<Section> manifest;
    private final byte[] identity;
    final int cells;

    private static final class Section {

        final HologramRegionCache.Key key;
        final byte[] digest;

        Section(HologramRegionCache.Key key, byte[] digest) {
            this.key = key;
            this.digest = digest;
        }
    }

    private HologramDraftSectionReview(HologramRegionCache geometry, List<Section> manifest, byte[] identity, int cells) {
        this.geometry = geometry;
        this.manifest = manifest;
        this.identity = identity;
        this.cells = cells;
    }

    static boolean available(HologramState view) {
        return view != null && !view.cells.isEmpty() && view.cells.size() <= CELLS
            && !view.regions.regions.isEmpty() && view.regions.regions.size() <= SECTIONS;
    }

    static HologramDraftSectionReview stage(FrozenDraft draft, HologramState view, BooleanSupplier current)
        throws IOException {
        check(current);
        if (!available(view) || draft.cellCount() != view.cells.size()) throw invalid();
        int[] visited = { 0 };
        BitSet checked = new BitSet(draft.cellCount());
        HashSet<String> palette = new HashSet<>();
        draft.visitDisplayCells((x, y, z, block, metadata, item, damage, facing, tile, itemTag) -> {
            HologramState.Cell cell = view.regions.at(x, y, z);
            if (++visited[0] > CELLS || cell == null || !block.equals(cell.wantId) || cell.wantMeta != metadata
                || cell.excluded || cell.anchor || cell.iconOnly || cell.missingBlock || cell.sourceMissing
                || !cell.structureIds.isEmpty()
                || !cell.prompt.isEmpty() || !cell.networkGroup.isEmpty()
                || Math.abs((long) x) > 60_000_000 || Math.abs((long) y) > 256 || Math.abs((long) z) > 60_000_000
                || metadata != 0 || damage != 0 || facing != -1 || !tile.isEmpty() || !itemTag.isEmpty()
                || !item.isEmpty() && !item.equals(block) || block.length() > 127) throw invalid();
            if (cell.index < 0 || cell.index >= draft.cellCount() || checked.get(cell.index)
                || view.cells.get(cell.index) != cell) throw invalid();
            checked.set(cell.index);
            if (palette.add(block)) {
                if (palette.size() > PALETTE || !BLOCK.matcher(block).matches()) throw invalid();
                var material = cell.block(false);
                if (material == null || material == Blocks.air || material.hasTileEntity(0)) throw invalid();
            }
        }, current);
        // The source visitor skips excluded rows. Reject the whole input rather than publish a subset.
        if (visited[0] != draft.cellCount()) throw invalid();

        List<HologramRegionCache.Key> keys = new ArrayList<>(view.regions.regions.keySet());
        keys.sort(Comparator.comparingInt((HologramRegionCache.Key k) -> k.y)
            .thenComparingInt(k -> k.z)
            .thenComparingInt(k -> k.x));
        List<Section> pages = new ArrayList<>(keys.size());
        MessageDigest root = sha();
        root.update("GTMB/private-draft-review/1".getBytes(StandardCharsets.US_ASCII));
        root.update(ByteBuffer.allocate(8).putInt(visited[0]).putInt(keys.size()).array());
        // Inline block keys are length-framed in canonical slot order, not catalog palette ordinals.
        // No ordinary JSON/PNG or archived GTPG file is created in this first connected stage.
        HologramState.Cell[] slots = new HologramState.Cell[SLOTS];
        int indexed = 0;
        for (var key : keys) {
            check(current);
            var rows = view.regions.regions.get(key);
            indexed += rows.size();
            Section page = new Section(key, pageDigest(rows, key, slots, current));
            pages.add(page);
            root.update(page.digest);
        }
        if (indexed != visited[0]) throw invalid();
        check(current);
        return new HologramDraftSectionReview(view.regions, pages, root.digest(), visited[0]);
    }

    int pages() {
        return manifest.size();
    }

    boolean sameIdentity(HologramDraftSectionReview other) {
        return other != null && MessageDigest.isEqual(identity, other.identity);
    }

    NBTTagCompound read(int ordinal, BooleanSupplier current) throws IOException {
        check(current);
        if (ordinal < 0 || ordinal >= pages()) throw invalid();
        Section page = manifest.get(ordinal);
        HologramState.Cell[] slots = new HologramState.Cell[SLOTS];
        if (!MessageDigest.isEqual(page.digest, pageDigest(geometry.regions.get(page.key), page.key, slots, current)))
            throw invalid();
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < slots.length; i++) {
            if ((i & 255) == 0) check(current);
            if (slots[i] == null) continue;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", i >>> 8);
            row.setInteger("y", (i >>> 4) & 15);
            row.setInteger("z", i & 15);
            row.setInteger("dx", i >>> 8);
            row.setInteger("dy", (i >>> 4) & 15);
            row.setInteger("dz", i & 15);
            row.setString("wantId", slots[i].wantId);
            row.setInteger("targetFacing", -1);
            rows.appendTag(row);
        }
        NBTTagCompound view = new NBTTagCompound();
        view.setString("session", "draft-page-review");
        view.setTag("cells", rows);
        view.setInteger("sectionX", page.key.x);
        view.setInteger("sectionY", page.key.y);
        view.setInteger("sectionZ", page.key.z);
        return view;
    }

    private static byte[] pageDigest(List<HologramState.Cell> rows, HologramRegionCache.Key key,
        HologramState.Cell[] slots, BooleanSupplier current) throws IOException {
        if (rows == null || rows.isEmpty() || rows.size() > SLOTS) throw invalid();
        Arrays.fill(slots, null);
        for (HologramState.Cell row : rows) {
            int slot = (row.x & 15) << 8 | (row.y & 15) << 4 | row.z & 15;
            if (slots[slot] != null || row.x >> 4 != key.x || row.y >> 4 != key.y || row.z >> 4 != key.z)
                throw invalid();
            slots[slot] = row;
        }
        MessageDigest hash = sha();
        hash.update(ByteBuffer.allocate(16).putInt(key.x).putInt(key.y).putInt(key.z).putInt(rows.size()).array());
        for (int i = 0; i < slots.length; i++) {
            if ((i & 255) == 0) check(current);
            HologramState.Cell row = slots[i];
            if (row == null) continue;
            byte[] block = row.wantId.getBytes(StandardCharsets.US_ASCII);
            hash.update((byte) (i >> 8));
            hash.update((byte) i);
            hash.update((byte) block.length);
            hash.update(block);
        }
        return hash.digest();
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void check(BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Private draft review cancelled");
    }

    private static IOException invalid() {
        return new IOException("Private draft is outside the read-only plain geometry review contract");
    }
}
