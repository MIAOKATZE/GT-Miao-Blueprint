// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;

/** An execution request contains block operations and a material bill, never a blueprint document. */
public final class HologramPlacementRequest {

    private static final int VERSION = 1;
    private static final int MAX_VARIANTS = 4096;
    private static final int FIELDS = 4;

    private HologramPlacementRequest() {}

    public static NBTTagCompound encode(UUID requestId, int[] anchor, HologramProjectedPlacement.Plan plan)
        throws IOException {
        if (anchor.length != 3 || plan.cells()
            .isEmpty()
            || !HologramBlueprintLimits.accepts(
                plan.cells()
                    .size()))
            throw malformed();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("action", "projectedPlacement");
        tag.setString("placementRequestId", requestId.toString());
        tag.setInteger("placementWireVersion", VERSION);
        tag.setIntArray("placementAnchor", anchor.clone());
        Map<Variant, Integer> index = new LinkedHashMap<>();
        NBTTagList variants = new NBTTagList();
        int[] operations = new int[plan.cells()
            .size() * FIELDS];
        int offset = 0;
        for (HologramProjectedPlacement.Cell cell : plan.cells()) {
            Variant key = new Variant(cell.block(), cell.metadata(), cell.item(), cell.damage(), cell.facing());
            Integer entry = index.get(key);
            if (entry == null) {
                if (index.size() >= MAX_VARIANTS) throw malformed();
                entry = index.size();
                index.put(key, entry);
                variants.appendTag(key.write());
            }
            operations[offset++] = cell.x();
            operations[offset++] = cell.y();
            operations[offset++] = cell.z();
            operations[offset++] = entry;
        }
        NBTTagList materials = new NBTTagList();
        for (HologramProjectedPlacement.Need need : plan.needs()) {
            NBTTagCompound material = new NBTTagCompound();
            material.setString("item", need.item());
            material.setInteger("damage", need.damage());
            material.setInteger("count", need.count());
            materials.appendTag(material);
        }
        tag.setTag("placementVariants", variants);
        tag.setIntArray("placementOperations", operations);
        tag.setTag("placementMaterials", materials);
        return tag;
    }

    static Header header(NBTTagCompound tag) throws IOException {
        if (!tag.hasKey("placementWireVersion", 3) || tag.getInteger("placementWireVersion") != VERSION
            || !tag.hasKey("placementAnchor", 11)
            || !tag.hasKey("placementOperations", 11)
            || !tag.hasKey("placementVariants", 9)
            || !tag.hasKey("placementMaterials", 9)) throw malformed();
        String id = tag.getString("placementRequestId");
        int[] anchor = tag.getIntArray("placementAnchor");
        try {
            if (id.length() != 36 || !UUID.fromString(id)
                .toString()
                .equals(id) || anchor.length != 3) throw malformed();
        } catch (IllegalArgumentException invalid) {
            throw malformed();
        }
        return new Header(id, anchor[0], anchor[1], anchor[2]);
    }

    /** Resolves only the packet syntax on the worker. Forge and world checks run in bounded server ticks. */
    static Decoded decode(Header header, NBTTagCompound tag, int height) throws IOException, InterruptedException {
        int[] cells = tag.getIntArray("placementOperations");
        NBTTagList palette = tag.getTagList("placementVariants", 10);
        NBTTagList bill = tag.getTagList("placementMaterials", 10);
        if (cells.length == 0 || cells.length % FIELDS != 0
            || !HologramBlueprintLimits.accepts(cells.length / FIELDS)
            || palette.tagCount() < 1
            || palette.tagCount() > MAX_VARIANTS
            || bill.tagCount() < 1
            || bill.tagCount() > MAX_VARIANTS) throw malformed();
        List<Variant> variants = new ArrayList<>(palette.tagCount());
        for (int i = 0; i < palette.tagCount(); i++) variants.add(Variant.read(palette.getCompoundTagAt(i)));
        Map<Material, Integer> supplied = new HashMap<>();
        for (int i = 0; i < bill.tagCount(); i++) {
            NBTTagCompound row = bill.getCompoundTagAt(i);
            Material key = new Material(name(row, "item"), number(row, "damage", 0, Short.MAX_VALUE));
            if (supplied.putIfAbsent(key, number(row, "count", 1, cells.length / FIELDS)) != null) throw malformed();
        }
        int[] variantCounts = new int[variants.size()];
        for (int offset = 0; offset < cells.length; offset += FIELDS) {
            if ((offset & 1023) == 0 && Thread.interrupted()) throw new InterruptedException();
            long x = (long) header.x + cells[offset], y = (long) header.y + cells[offset + 1],
                z = (long) header.z + cells[offset + 2];
            int variant = cells[offset + 3];
            if (x < -30_000_000 || x >= 30_000_000
                || z < -30_000_000
                || z >= 30_000_000
                || y < 0
                || y >= height
                || variant < 0
                || variant >= variants.size()) throw malformed();
            variantCounts[variant]++;
        }
        Map<Material, Integer> expected = new HashMap<>();
        for (int i = 0; i < variantCounts.length; i++) {
            if (variantCounts[i] == 0) throw malformed();
            Variant variant = variants.get(i);
            expected.merge(new Material(variant.item, variant.damage), variantCounts[i], Integer::sum);
        }
        if (!supplied.equals(expected)) throw new IOException("施工耗材与提交的放置指令不符");
        return new Decoded(java.util.Collections.unmodifiableList(variants), cells);
    }

    private static String name(NBTTagCompound row, String key) throws IOException {
        String value = row.getString(key);
        if (value.isEmpty() || value.length() > 256 || value.indexOf(':') <= 0) throw malformed();
        return value;
    }

    private static int number(NBTTagCompound row, String key, int min, int max) throws IOException {
        int value = row.getInteger(key);
        if (!row.hasKey(key, 3) || value < min || value > max) throw malformed();
        return value;
    }

    private static IOException malformed() {
        return new IOException("施工指令格式错误或超出范围");
    }

    @Desugar
    record Header(String id, int x, int y, int z) {}

    @Desugar
    record Decoded(List<Variant> variants, int[] operations) {

        int size() {
            return operations.length / FIELDS;
        }

        HologramPresets.Cell cell(int i) {
            int at = i * FIELDS;
            Variant variant = variants.get(operations[at + 3]);
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.x = operations[at];
            cell.y = operations[at + 1];
            cell.z = operations[at + 2];
            cell.item = variant.item;
            cell.damage = variant.damage;
            cell.block = variant.block;
            cell.metadata = variant.metadata;
            cell.facing = variant.facing;
            return cell;
        }
    }

    @Desugar
    record Material(String item, int damage) {}

    @Desugar
    record Variant(String block, int metadata, String item, int damage, int facing) {

        NBTTagCompound write() {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("block", block);
            row.setInteger("metadata", metadata);
            row.setString("item", item);
            row.setInteger("damage", damage);
            row.setInteger("facing", facing);
            return row;
        }

        static Variant read(NBTTagCompound row) throws IOException {
            return new Variant(
                name(row, "block"),
                number(row, "metadata", 0, 15),
                name(row, "item"),
                number(row, "damage", 0, Short.MAX_VALUE),
                number(row, "facing", -1, 5));
        }
    }
}
