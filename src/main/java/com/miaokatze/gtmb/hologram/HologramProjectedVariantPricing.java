// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.MaterialVariants;

/**
 * Disconnected, coordinates-free pricing for a small audited saved palette, not a stock provider, transport, or
 * placement authorization. Identity pose only. Charges choose the same exact raw recipe for dry stock and later
 * incremental payment; prebuilt alternatives must not be mixed into this bill without a new verified policy.
 */
final class HologramProjectedVariantPricing {

    private static final String HEMP = "chisel:hempcrete", LAVE = "Ztones:tile.laveBlock", GLAXX = "Ztones:tile.glaxx";
    private static final String GREEN = "ExtraUtilities:greenscreen", SHAPE = "ArchitectureCraft:shape";
    private static final String SLOPE = "CarpentersBlocks:blockCarpentersSlope";
    private static final String PREVIEW = "gtmb.nativePreview", NETWORK = "GTSWN_NetworkSafetyEnabled";
    private static final String PORTABLE_ITEM = "gtitBlueprintItemRegistry";
    private static final int RAW_BYTES = 4096, COMPRESSED_BYTES = 2048;
    private static final long OCCURRENCE_BYTES = 64L * 1024 * 1024;
    private static final Set<String> ARCH = keys(
        "id", "BaseName", "BaseData", "Shape", "side", "turn", "Name2", "Data2", "Disconnected", PREVIEW, NETWORK);
    private static final Set<String> ARCH_ITEM = keys("BaseName", "BaseData", "Shape");
    private static final Set<String> CARPENTER = keys("id", "cbMetadata", "cbAttrList", "cbOwner", "cbDesign",
        "cbChiselDesign_0", "cbChiselDesign_1", "cbChiselDesign_2", "cbChiselDesign_3", "cbChiselDesign_4",
        "cbChiselDesign_5", "cbChiselDesign_6", PREVIEW, NETWORK);
    private static final Set<String> CARPENTER_SKIN = keys("cbAttribute", "cbUniqueId", PORTABLE_ITEM,
        "id", "Count", "Damage");

    private HologramProjectedVariantPricing() {}

    static final class Row {

        final String block, item, tileNbt, itemNbt;
        final int metadata, damage, facing, count;
        final boolean controller;

        Row(String block, String item, int metadata, int damage, int facing, boolean controller, String tileNbt,
            String itemNbt, int count) {
            this.block = block;
            this.item = item;
            this.metadata = metadata;
            this.damage = damage;
            this.facing = facing;
            this.controller = controller;
            this.tileNbt = tileNbt;
            this.itemNbt = itemNbt;
            this.count = count;
        }
    }

    static final class Variant {

        final Block block;
        final int metadata, count;
        private final ItemStack sample;
        private final NBTTagCompound tile;

        private Variant(Block block, Row row, ItemStack sample, NBTTagCompound tile) {
            this.block = block;
            metadata = row.metadata;
            count = row.count;
            this.sample = sample;
            this.tile = tile;
        }

        ItemStack sample() {
            return sample.copy();
        }

        NBTTagCompound tile() {
            return tile == null ? null : (NBTTagCompound) tile.copy();
        }

        private boolean same(Variant other) {
            return block == other.block && metadata == other.metadata && sample.isItemEqual(other.sample)
                && ItemStack.areItemStackTagsEqual(sample, other.sample) && java.util.Objects.equals(tile, other.tile);
        }
    }

    static final class Catalog {

        final List<Variant> variants;
        final HologramLayerStockBill bill;
        final long expandedByOccurrence;
        private final long nativeGeneration, materialGeneration, descriptorGeneration;

        private Catalog(List<Variant> variants, HologramLayerStockBill bill, long bytes, long nativeGeneration,
            long materialGeneration, long descriptorGeneration) {
            this.variants = Collections.unmodifiableList(variants);
            this.bill = bill;
            expandedByOccurrence = bytes;
            this.nativeGeneration = nativeGeneration;
            this.materialGeneration = materialGeneration;
            this.descriptorGeneration = descriptorGeneration;
        }

        boolean current() {
            return nativeGeneration == HologramNativePlacement.generation()
                && materialGeneration == MaterialVariants.generation()
                && descriptorGeneration == HologramMaterialDescriptor.generation();
        }
    }

    static final class Shape {

        final NBTTagCompound tile, item;
        final int bytes;

        private Shape(NBTTagCompound tile, NBTTagCompound item, int bytes) {
            this.tile = tile;
            this.item = item;
            this.bytes = bytes;
        }
    }

    /** This syntax step permits default offline verification without mistaking it for native provider admission. */
    static Shape inspect(Row row, boolean identityPose) throws IOException {
        if (!identityPose || row == null || row.block == null || !row.block.equals(row.item) || row.facing != -1
            || row.controller || row.count < 1 || row.count > HologramBlueprintLimits.LOCAL_CELLS
            || row.metadata != row.damage) throw invalid();
        if (!SHAPE.equals(row.block) && !SLOPE.equals(row.block)) {
            if (!plain(row.block, row.metadata) || !absent(row.tileNbt) || !absent(row.itemNbt)) throw invalid();
            return new Shape(null, null, 0);
        }
        if (row.metadata != 0 || absent(row.tileNbt)) throw invalid();
        Payload tile = payload(row.tileNbt);
        if (SLOPE.equals(row.block)) {
            if (!absent(row.itemNbt)) throw invalid();
            carpenter(tile.tag);
            return new Shape(tile.tag, null, tile.bytes);
        }
        if (absent(row.itemNbt)) throw invalid();
        Payload item = payload(row.itemNbt);
        architecture(tile.tag, item.tag);
        return new Shape(tile.tag, item.tag, tile.bytes + item.bytes);
    }

    /** O(palette) work only. Every native quote uses the exact registered detached family, never client BOM. */
    static Catalog prepare(List<Row> rows, boolean identityPose) throws IOException {
        if (!identityPose || rows == null) throw invalid();
        Builder builder = new Builder(rows.size());
        for (Row row : rows) builder.add(row);
        return builder.finish();
    }

    /** An exact registered-provider observation can be advanced one palette row per owner tick. */
    static final class Builder {

        private final Thread owner = Thread.currentThread();
        private final long nativeGeneration = HologramNativePlacement.generation();
        private final long materialGeneration = MaterialVariants.generation();
        private final long descriptorGeneration = HologramMaterialDescriptor.generation();
        private final int expected;
        private final List<Variant> variants;
        private final List<HologramLayerStockBill.Row> rates;
        private long bytes, occurrences;
        private boolean finished;

        Builder(int rows) throws IOException {
            if (rows <= 0 || rows > HologramLayerStockBill.MAX_ROWS) throw invalid();
            expected = rows;
            variants = new ArrayList<>(rows);
            rates = new ArrayList<>(rows);
        }

        private boolean current() {
            return Thread.currentThread() == owner && !finished
                && nativeGeneration == HologramNativePlacement.generation()
                && materialGeneration == MaterialVariants.generation()
                && descriptorGeneration == HologramMaterialDescriptor.generation();
        }

        void add(Row row) throws IOException {
            if (!current() || variants.size() >= expected) throw invalid();
            Shape shape = inspect(row, true);
            bytes += (long) shape.bytes * row.count;
            occurrences += row.count;
            if (bytes > OCCURRENCE_BYTES || occurrences > HologramBlueprintLimits.LOCAL_CELLS) throw invalid();
            try {
                Block block = block(row.block);
                ItemStack sample = exactItem(block, row.block, row.damage);
                NBTTagCompound tile = shape.tile == null ? null
                    : HologramBlueprintNbt.restorePortableObservation(shape.tile);
                NBTTagCompound item = shape.item == null ? null
                    : HologramBlueprintNbt.restorePortableObservation(shape.item);
                sample.setTagCompound(HologramBlueprintNbt.nativePayload(item));
                HologramLayerStockBill.Term[] terms;
                if (tile == null) {
                    checkPlain(block, row.block, row.metadata, sample);
                    terms = new HologramLayerStockBill.Term[] { new HologramLayerStockBill.Term(sample, 1, 1) };
                } else {
                    var adapter = HologramNativePlacement.adapter(block);
                    String expected = SHAPE.equals(row.block)
                        ? "com.miaokatze.gtmb.hologram.HologramNativePlacement$Builtin"
                        : "com.miaokatze.gtmb.compat.carpenters.common.CarpentersCompatibility$Placement";
                    if (adapter == null || !expected.equals(adapter.getClass().getName())) throw invalid();
                    HologramExecutionEligibility.validate(block, row.metadata, sample, -1, false, tile, item);
                    terms = priceNative(block, row, tile, sample);
                }
                Variant variant = new Variant(block, row, sample, tile);
                for (Variant prior : variants) if (variant.same(prior)) throw invalid();
                if (!current()) throw invalid();
                variants.add(variant);
                rates.add(new HologramLayerStockBill.Row(row.count, terms));
            } catch (RuntimeException | LinkageError failure) {
                throw new IOException("Reviewed blueprint material provider unavailable", failure);
            }
        }

        Catalog finish() throws IOException {
            if (!current() || variants.size() != expected) throw invalid();
            Catalog catalog = new Catalog(variants, new HologramLayerStockBill(rates), bytes,
                nativeGeneration, materialGeneration, descriptorGeneration);
            if (!catalog.current()) throw invalid();
            finished = true;
            return catalog;
        }
    }

    private static HologramLayerStockBill.Term[] priceNative(Block block, Row row, NBTTagCompound tile,
        ItemStack sample) throws IOException {
        List<HologramDecorMaterials.Cost> costs = HologramDecorMaterials.describe(block, row.metadata, tile).costs;
        boolean arch = SHAPE.equals(row.block), extra = !arch || tile.hasKey("Name2", 8);
        if (costs.size() != (extra ? 2 : 1)) throw invalid();
        ItemStack main = arch ? exactPlain(tile.getString("BaseName"), tile.getInteger("BaseData")) : sample;
        ItemStack second = !extra ? null : arch ? exactPlain(GREEN, 3) : exactPlain(GLAXX, 0);
        var terms = new HologramLayerStockBill.Term[costs.size()];
        for (int i = 0; i < costs.size(); i++) {
            var cost = costs.get(i);
            ItemStack target = i == 0 ? main : second;
            if (!cost.raw.isItemEqual(target) || !ItemStack.areItemStackTagsEqual(cost.raw, target)
                || (!arch || i == 1) && cost.numerator != cost.denominator) throw invalid();
            terms[i] = new HologramLayerStockBill.Term(cost.raw, cost.numerator, cost.denominator);
        }
        return terms;
    }

    private static void architecture(NBTTagCompound tile, NBTTagCompound item) throws IOException {
        only(tile, ARCH);
        only(item, ARCH_ITEM);
        if (!tile.hasKey("id", 8) || !"gcewing.shape".equals(tile.getString("id")) || !tile.hasKey("BaseName", 8)
            || !tile.hasKey("BaseData", 3) || !tile.hasKey("Shape", 3) || !item.hasKey("BaseName", 8)
            || !item.hasKey("BaseData", 3) || !item.hasKey("Shape", 3)) throw invalid();
        String base = tile.getString("BaseName");
        int meta = tile.getInteger("BaseData"), shape = tile.getInteger("Shape");
        if (!(HEMP.equals(base) && meta == 15 && shape == 0
            || LAVE.equals(base) && meta == 12 && (shape == 0 || shape == 18 || shape == 19 || shape == 116))
            || !base.equals(item.getString("BaseName")) || meta != item.getInteger("BaseData")
            || shape != item.getInteger("Shape")) throw invalid();
        normalizeByte(tile, "side", 5);
        normalizeByte(tile, "turn", 3);
        if (tile.hasKey("Name2") || tile.hasKey("Data2")) {
            if (shape != 116 || !tile.hasKey("Name2", 8) || !GREEN.equals(tile.getString("Name2"))
                || !tile.hasKey("Data2", 3) || tile.getInteger("Data2") != 3) throw invalid();
        }
        if (tile.hasKey("Disconnected") && (!tile.hasKey("Disconnected", 3) || !tile.hasKey("Name2", 8)
            || tile.getInteger("Disconnected") != 4)) throw invalid();
        normalizeHints(tile);
    }

    private static void carpenter(NBTTagCompound tile) throws IOException {
        only(tile, CARPENTER);
        if (!tile.hasKey("id", 8) || !"TileEntityCarpentersBlock".equals(tile.getString("id"))
            || !tile.hasKey("cbMetadata", 3) || (tile.getInteger("cbMetadata") & ~1) != 0
            || !tile.hasKey("cbAttrList", 9)) throw invalid();
        NBTTagList list = (NBTTagList) tile.getTag("cbAttrList");
        if (list.func_150303_d() != 10 || list.tagCount() != 1) throw invalid();
        NBTTagCompound skin = list.getCompoundTagAt(0);
        only(skin, CARPENTER_SKIN);
        if (!skin.hasKey("id", 2) || !skin.hasKey("Count", 1) || skin.getByte("Count") != 1
            || !skin.hasKey("Damage", 2) || skin.getShort("Damage") != 0
            || !skin.hasKey("cbAttribute", 1) || skin.getByte("cbAttribute") != 6
            || !skin.hasKey("cbUniqueId", 8) || !GLAXX.equals(skin.getString("cbUniqueId"))
            || !skin.hasKey(PORTABLE_ITEM, 8) || !GLAXX.equals(skin.getString(PORTABLE_ITEM))) throw invalid();
        // The numeric item id is only an original archive hint; restorePortableObservation rebinds by exact name.
        blank(tile, "cbOwner");
        blank(tile, "cbDesign");
        for (int slot = 0; slot < 7; slot++) blank(tile, "cbChiselDesign_" + slot);
        normalizeHints(tile);
    }

    private static ItemStack exactPlain(String name, int metadata) throws IOException {
        if (!plain(name, metadata)) throw invalid();
        Block block = block(name);
        ItemStack stack = exactItem(block, name, metadata);
        checkPlain(block, name, metadata, stack);
        return stack;
    }

    private static void checkPlain(Block block, String name, int metadata, ItemStack item) throws IOException {
        if (!plain(name, metadata) || block.hasTileEntity(metadata) || HologramNativePlacement.supported(block)
            || item.hasTagCompound()) throw invalid();
        HologramExecutionEligibility.validate(block, metadata, item, -1, false, null, null);
    }

    private static boolean plain(String name, int metadata) {
        return HEMP.equals(name) && metadata == 15 || LAVE.equals(name) && metadata == 12
            || GLAXX.equals(name) && metadata == 0 || GREEN.equals(name) && (metadata == 0 || metadata == 3);
    }

    private static Block block(String name) throws IOException {
        String type = switch (name) {
            case HEMP -> "team.chisel.block.BlockCarvable";
            case LAVE -> "com.riciJak.Ztones.block.MBlocks.BlockLave";
            case GLAXX -> "com.riciJak.Ztones.block.VBlocks.BlockGlaxx";
            case GREEN -> "com.rwtema.extrautils.block.BlockGreenScreen";
            case SHAPE -> "gcewing.architecture.common.block.BlockShape";
            case SLOPE -> "com.carpentersblocks.block.BlockCarpentersSlope";
            default -> throw invalid();
        };
        Block block = Block.getBlockFromName(name);
        if (block == null || block == Blocks.air || !name.equals(Block.blockRegistry.getNameForObject(block))
            || !type.equals(block.getClass().getName())) throw invalid();
        return block;
    }

    private static ItemStack exactItem(Block block, String name, int damage) throws IOException {
        Item item = Item.getItemFromBlock(block);
        if (!(item instanceof ItemBlock) || Item.itemRegistry.getObject(name) != item
            || !name.equals(Item.itemRegistry.getNameForObject(item)) || Block.getBlockFromItem(item) != block)
            throw invalid();
        return new ItemStack(item, 1, damage);
    }

    private static final class Payload {

        final NBTTagCompound tag;
        final int bytes;

        Payload(NBTTagCompound tag, int bytes) {
            this.tag = tag;
            this.bytes = bytes;
        }
    }

    private static Payload payload(String text) throws IOException {
        if (text.length() > ((COMPRESSED_BYTES + 2) / 3) * 4) throw invalid();
        try {
            byte[] compressed = Base64.getDecoder().decode(text);
            if (compressed.length < 2 || compressed.length > COMPRESSED_BYTES || (compressed[0] & 255) != 31
                || (compressed[1] & 255) != 139) throw invalid();
            int expanded = 0;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                byte[] buffer = new byte[512];
                for (int read; (read = in.read(buffer)) != -1;) {
                    if (read > RAW_BYTES - expanded) throw invalid();
                    expanded += read;
                }
            }
            return new Payload(HologramSnapshotCodec.decode(compressed, RAW_BYTES, 64 * 1024), expanded);
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("Invalid bounded native blueprint payload", failure);
        }
    }

    private static void only(NBTTagCompound tag, Set<String> keys) throws IOException {
        for (Object key : tag.func_150296_c()) if (!keys.contains(key)) throw invalid();
    }

    private static Set<String> keys(String... names) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
    }

    private static void normalizeByte(NBTTagCompound tag, String key, int max) throws IOException {
        if (!tag.hasKey(key)) return;
        if (!tag.hasKey(key, 1) || tag.getByte(key) < 0 || tag.getByte(key) > max) throw invalid();
        if (tag.getByte(key) == 0) tag.removeTag(key);
    }

    private static void normalizeHints(NBTTagCompound tag) throws IOException {
        normalizeByte(tag, PREVIEW, 1);
        normalizeByte(tag, NETWORK, 0);
        tag.removeTag(PREVIEW);
    }

    private static void blank(NBTTagCompound tag, String key) throws IOException {
        if (tag.hasKey(key) && (!tag.hasKey(key, 8) || !tag.getString(key).isEmpty())) throw invalid();
        tag.removeTag(key);
    }

    private static boolean absent(String text) {
        return text == null || text.isEmpty();
    }

    private static IOException invalid() {
        return new IOException("Unreviewed projected variant, pose, or native material recipe");
    }
}
