// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;

/** An execution request contains block operations and a material bill, never a blueprint document. */
public final class HologramPlacementRequest {

    private static final int VERSION = 2;
    private static final int MAX_VARIANTS = 4096;
    private static final int MAX_QUOTE_ITEMS = 512;
    private static final int MAX_PAYLOAD = 512 * 1024;
    private static final long MAX_OPERATION_PAYLOAD = 64L * 1024 * 1024;
    private static final int FIELDS = 4;

    private HologramPlacementRequest() {}

    public static NBTTagCompound encode(UUID requestId, int[] anchor, HologramProjectedPlacement.Plan plan)
        throws IOException {
        return encode(requestId, anchor, plan, null);
    }

    public static NBTTagCompound encode(UUID requestId, int[] anchor, HologramProjectedPlacement.Plan plan,
        NBTTagCompound displayedMetadata) throws IOException {
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
            Variant key = new Variant(
                cell.block(), cell.metadata(), cell.item(), cell.damage(), cell.facing(), cell.controller(),
                cell.tile(), cell.itemState());
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
        writeSources(tag, displayedMetadata);
        return tag;
    }

    /** A stock query sends only item quantities, with no coordinates or construction authority. */
    public static NBTTagCompound encodeMaterialCheck(UUID requestId, NBTTagCompound displayedState) throws IOException {
        NBTTagList cells = displayedState.getTagList("cells", 10);
        if (!HologramBlueprintLimits.accepts(cells.tagCount())) throw malformed();
        Map<Material, Integer> amounts = new LinkedHashMap<>();
        HologramProjectedPlacement.DisplayScope scope = new HologramProjectedPlacement.DisplayScope(displayedState, cells);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (cell.getBoolean("anchor") || !scope.includes(cell, i)) continue;
            ItemStack stack = ItemStack.loadItemStackFromNBT(cell.getCompoundTag("targetStack"));
            if (stack == null) continue;
            String item = Item.itemRegistry.getNameForObject(stack.getItem());
            if (item != null) amounts.merge(new Material(item, stack.getItemDamage()), 1, Integer::sum);
        }
        if (amounts.size() > MAX_QUOTE_ITEMS) throw new IOException("材料种类过多，请缩小核对范围");
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", "checkPlacementMaterials");
        request.setString("placementRequestId", requestId.toString());
        request.setInteger("placementWireVersion", VERSION);
        NBTTagList rows = new NBTTagList();
        amounts.forEach((material, count) -> {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("item", material.item);
            row.setInteger("damage", material.damage);
            row.setInteger("count", count);
            rows.appendTag(row);
        });
        request.setTag("placementMaterials", rows);
        writeSources(request, displayedState);
        return request;
    }

    static Map<Material, Integer> materialCheck(NBTTagCompound request) throws IOException {
        requestId(request);
        if (!request.hasKey("placementMaterials", 9) || request.hasKey("placementAnchor")
            || request.hasKey("placementVariants") || request.hasKey("placementOperations")) throw malformed();
        NBTTagList rows = request.getTagList("placementMaterials", 10);
        if (rows.tagCount() > MAX_QUOTE_ITEMS) throw malformed();
        Map<Material, Integer> result = new LinkedHashMap<>();
        int total = 0;
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            Material material = new Material(name(row, "item"), number(row, "damage", 0, Short.MAX_VALUE));
            int amount = number(row, "count", 1, HologramBlueprintLimits.MAX_CELLS);
            total += amount;
            if (total < 0 || !HologramBlueprintLimits.accepts(total) || result.putIfAbsent(material, amount) != null)
                throw malformed();
        }
        return result;
    }

    static Header header(NBTTagCompound tag) throws IOException {
        if (!tag.hasKey("placementAnchor", 11)
            || !tag.hasKey("placementOperations", 11)
            || !tag.hasKey("placementVariants", 9)
            || !tag.hasKey("placementMaterials", 9)) throw malformed();
        String id = requestId(tag);
        int[] anchor = tag.getIntArray("placementAnchor");
        if (anchor.length != 3) throw malformed();
        return new Header(id, anchor[0], anchor[1], anchor[2], sourceOptions(tag));
    }

    private static void writeSources(NBTTagCompound request, NBTTagCompound displayed) throws IOException {
        if (displayed == null) return;
        NBTTagCompound options = new NBTTagCompound();
        for (String key : new String[] { "materialMain", "materialContainers", "materialMe" })
            if (displayed.hasKey(key, 1)) options.setBoolean(key, displayed.getBoolean(key));
        if (displayed.hasKey("materialPriority"))
            options.setInteger("materialPriority", number(displayed, "materialPriority", 0, 2));
        if (!options.hasNoTags()) request.setTag("materialSources", options);
    }

    static SourceOptions sourceOptions(NBTTagCompound request) throws IOException {
        if (request.hasKey("materialSources") && !request.hasKey("materialSources", 10)) throw malformed();
        NBTTagCompound options = request.getCompoundTag("materialSources");
        return new SourceOptions(
            sourceBoolean(options, "materialMain"),
            sourceBoolean(options, "materialContainers"),
            sourceBoolean(options, "materialMe"),
            options.hasKey("materialPriority") ? number(options, "materialPriority", 0, 2) : -1);
    }

    private static Boolean sourceBoolean(NBTTagCompound options, String key) throws IOException {
        if (!options.hasKey(key)) return null;
        if (!options.hasKey(key, 1) || options.getByte(key) < 0 || options.getByte(key) > 1) throw malformed();
        return options.getBoolean(key);
    }

    private static String requestId(NBTTagCompound tag) throws IOException {
        if (!tag.hasKey("placementWireVersion", 3) || tag.getInteger("placementWireVersion") != VERSION)
            throw malformed();
        String id = tag.getString("placementRequestId");
        try {
            if (id.length() != 36 || !UUID.fromString(id)
                .toString()
                .equals(id)) throw malformed();
        } catch (IllegalArgumentException invalid) {
            throw malformed();
        }
        return id;
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
        int[] payloadSizes = new int[palette.tagCount()];
        long expandedPayload = 0;
        for (int i = 0; i < palette.tagCount(); i++) {
            if (Thread.interrupted()) throw new InterruptedException();
            Variant variant = Variant.read(palette.getCompoundTagAt(i));
            payloadSizes[i] = expandedBytes(variant.tile) + expandedBytes(variant.itemState);
            expandedPayload += payloadSizes[i];
            if (expandedPayload > HologramAeProtocol.MAX_STACK_AGGREGATE) throw malformed();
            variants.add(variant);
        }
        Map<Material, Integer> supplied = new HashMap<>();
        for (int i = 0; i < bill.tagCount(); i++) {
            NBTTagCompound row = bill.getCompoundTagAt(i);
            Material key = new Material(name(row, "item"), number(row, "damage", 0, Short.MAX_VALUE));
            if (supplied.putIfAbsent(key, number(row, "count", 1, cells.length / FIELDS)) != null) throw malformed();
        }
        int[] variantCounts = new int[variants.size()];
        Set<Long> positions = new HashSet<>();
        long operationPayload = 0;
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
            long position = ((x & 0x3ffffffL) << 38) | ((z & 0x3ffffffL) << 12) | y;
            if (!positions.add(position)) throw new IOException("施工指令包含重复目标位置");
            operationPayload += payloadSizes[variant];
            if (operationPayload > MAX_OPERATION_PAYLOAD) throw new IOException("施工设备状态超过展开上限，请拆分蓝图");
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

    private static int expandedBytes(String payload) throws IOException {
        int bytes = HologramBlueprintNbt.expandedBytes(payload);
        if (bytes > MAX_PAYLOAD) throw malformed();
        return bytes;
    }

    private static String name(NBTTagCompound row, String key) throws IOException {
        String value = row.getString(key);
        if (!row.hasKey(key, 8) || value.indexOf(':') <= 0) throw malformed();
        HologramPresets.registryText(value, false);
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
    record Header(String id, int x, int y, int z, SourceOptions sources) {}

    @Desugar
    record SourceOptions(Boolean main, Boolean containers, Boolean me, int priority) {

        void apply(HologramMaterials.Settings settings) {
            if (main != null) settings.main = main;
            if (containers != null) settings.containers = containers;
            if (me != null) settings.me = me;
            if (priority >= 0) settings.priority = priority;
        }
    }

    @Desugar
    record Decoded(List<Variant> variants, int[] operations) {

        int size() {
            return operations.length / FIELDS;
        }

        HologramProjectedPlacement.Cell cell(int i) {
            int at = i * FIELDS;
            Variant variant = variants.get(operations[at + 3]);
            return new HologramProjectedPlacement.Cell(
                operations[at], operations[at + 1], operations[at + 2], variant.block, variant.metadata, variant.item,
                variant.damage, variant.facing, variant.controller, variant.tile, variant.itemState);
        }

        int variantIndex(int i) {
            return operations[i * FIELDS + 3];
        }
    }

    @Desugar
    record Material(String item, int damage) {}

    @Desugar
    record Variant(String block, int metadata, String item, int damage, int facing, boolean controller, String tile,
        String itemState) {

        NBTTagCompound write() throws IOException {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("block", block);
            row.setInteger("metadata", metadata);
            row.setString("item", item);
            row.setInteger("damage", damage);
            row.setInteger("facing", facing);
            row.setBoolean("controller", controller);
            writePayload(row, "device", tile);
            writePayload(row, "itemState", itemState);
            return row;
        }

        static Variant read(NBTTagCompound row) throws IOException {
            return new Variant(
                name(row, "block"),
                number(row, "metadata", 0, 15),
                name(row, "item"),
                number(row, "damage", 0, Short.MAX_VALUE),
                number(row, "facing", -1, 5), row.getBoolean("controller"),
                readPayload(row, "device"), readPayload(row, "itemState"));
        }

        private static void writePayload(NBTTagCompound row, String key, String payload) throws IOException {
            if (payload == null || payload.isEmpty()) return;
            try {
                byte[] bytes = Base64.getDecoder().decode(payload);
                if (bytes.length > MAX_PAYLOAD) throw malformed();
                row.setByteArray(key, bytes);
            } catch (IllegalArgumentException invalid) {
                throw malformed();
            }
        }

        private static String readPayload(NBTTagCompound row, String key) throws IOException {
            if (!row.hasKey(key)) return "";
            if (!row.hasKey(key, 7)) throw malformed();
            byte[] bytes = row.getByteArray(key);
            if (bytes.length == 0 || bytes.length > MAX_PAYLOAD) throw malformed();
            return Base64.getEncoder().encodeToString(bytes);
        }
    }
}
