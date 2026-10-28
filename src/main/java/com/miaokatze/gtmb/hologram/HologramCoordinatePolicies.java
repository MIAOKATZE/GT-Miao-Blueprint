package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Exact, audited world-coordinate relationships; never a recursive search for x/y/z in arbitrary assets. */
final class HologramCoordinatePolicies {

    static final String RELATIONS = "gtmbCoordinateRelations";
    static final String PAYMENT = "gtmbCoordinatePayment";
    private static final String NC_CARD_PACKAGE = "shedar.mods.ic2.nuclearcontrol.items.";
    private static final String GT_SENSOR_CARD = "gregtech.common.items.ItemSensorCard";

    private HologramCoordinatePolicies() {}

    static final class Relation {

        String id, label, adapter, payload = "tile";
        int x, y, z, slot = -1, targetX, targetY, targetZ, targetDimension, dx, dy, dz;
        boolean internal;
        String previousMode;

        NBTTagCompound write(String mode) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", id);
            row.setString("label", label);
            row.setString("adapter", adapter);
            row.setString("payload", payload);
            row.setString("mode", mode);
            row.setInteger("x", x);
            row.setInteger("y", y);
            row.setInteger("z", z);
            row.setInteger("slot", slot);
            boolean absolute = "absolute".equals(mode);
            row.setInteger("targetX", absolute ? targetX : 0);
            row.setInteger("targetY", absolute ? targetY : 0);
            row.setInteger("targetZ", absolute ? targetZ : 0);
            row.setInteger("targetDimension", absolute ? targetDimension : 0);
            row.setBoolean("internal", internal);
            boolean relative = "relative".equals(mode);
            row.setInteger("dx", relative ? dx : 0);
            row.setInteger("dy", relative ? dy : 0);
            row.setInteger("dz", relative ? dz : 0);
            // NC coordinates implicitly refer to the host's dimension. Never invent a native dimension key.
            row.setBoolean("implicitDimension", !HologramNativeHubData.adapter(adapter));
            row.setBoolean("physicalAsset", "nc.sensor".equals(adapter));
            if ("nc.sensor".equals(adapter)) row.setString("clearDetail", "清除会重置传感卡绑定、已审阅的监控缓存和标题；卡片及其它付款数据仍严格核验");
            if (HologramNativeHubData.adapter(adapter)) row.setString("clearDetail", "分享会清除枢纽和节点绑定；放置后使用设备原生交互重新绑定");
            return row;
        }
    }

    static List<Relation> inspect(HologramPresets.Preset preset, int dimension, int originX, int originY, int originZ)
        throws IOException {
        return inspect(preset, dimension, originX, originY, originZ, new HologramAnalysisFacts());
    }

    static List<Relation> inspect(HologramPresets.Preset preset, int dimension, int originX, int originY, int originZ,
        HologramAnalysisFacts facts) throws IOException {
        List<Relation> relations = new ArrayList<>();
        if (preset == null) return relations;
        Set<String> enabledStructures = new HashSet<>();
        for (HologramPresets.Structure structure : preset.structures)
            if (structure.enabled) enabledStructures.add(structure.id);
        Set<String> selected = new HashSet<>();
        for (HologramPresets.Cell cell : preset.cells)
            if (!cell.excluded && activeCell(enabledStructures, cell)) selected.add(position(cell.x, cell.y, cell.z));
        for (HologramPresets.Cell cell : preset.cells) {
            // A known block with no serialized scopes has no coordinate record to inspect or retain.
            if (cell.block() != null && !HologramSnapshotMetadataSource.hasPayload(cell.tileNbt)
                && !HologramSnapshotMetadataSource.hasPayload(cell.itemNbt)) continue;
            NBTTagCompound certificate = facts.certificate(
                cell,
                HologramSegmentContract.Domain.RELATIONS,
                "coordinate-schemas",
                new Object[] { cell.block(), cell.x, cell.y, cell.z, dimension, originX, originY, originZ,
                    cell.frozenSensorSlots == null ? null : cell.frozenSensorSlots.toArray(),
                    HologramSegmentContract.SCHEMA_EPOCH },
                true,
                () -> coordinateFacts(cell, dimension, originX, originY, originZ, facts));
            NBTTagList rows = certificate.getTagList("rows", 10);
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound row = rows.getCompoundTagAt(i);
                Relation relation = read(row);
                relation.previousMode = row.getString("sourceMode")
                    .isEmpty() ? null : row.getString("sourceMode");
                try {
                    relation.internal = !"clear".equals(relation.previousMode)
                        && (!HologramNativeHubData.adapter(relation.adapter) || relation.targetDimension == dimension)
                        && selected.contains(
                            position(
                                Math.subtractExact(relation.targetX, originX),
                                Math.subtractExact(relation.targetY, originY),
                                Math.subtractExact(relation.targetZ, originZ)));
                } catch (ArithmeticException overflow) {
                    throw new IOException("坐标关系超出整数范围", overflow);
                }
                relations.add(relation);
            }
        }
        if (relations.size() > 8192) throw new IOException("已适配坐标关系数量超出上限");
        facts.relations(relations);
        return relations;
    }

    private static NBTTagCompound coordinateFacts(HologramPresets.Cell cell, int dimension, int originX, int originY,
        int originZ, HologramAnalysisFacts facts) throws IOException {
        List<Relation> relations = new ArrayList<>();
        Set<String> selected = java.util.Collections.emptySet();
        for (String payload : new String[] { "tile", "item" }) {
            NBTTagCompound tag = facts.read("item".equals(payload) ? cell.itemNbt : cell.tileNbt);
            if (tag == null) continue;
            if (tag.hasKey(RELATIONS)) {
                NBTTagList existing = checkedRelations(tag);
                for (int i = 0; i < existing.tagCount(); i++) {
                    NBTTagCompound descriptor = existing.getCompoundTagAt(i);
                    Relation relation = read(descriptor);
                    relation.x = cell.x;
                    relation.y = cell.y;
                    relation.z = cell.z;
                    if (!payload.equals(relation.payload)) throw new IOException("坐标关系记录与所在NBT范围不一致");
                    relation.id = id(cell, payload, relation.adapter, relation.slot);
                    try {
                        if ("relative".equals(relation.previousMode)) {
                            relation.targetX = Math.addExact(Math.addExact(originX, cell.x), relation.dx);
                            relation.targetY = Math.addExact(Math.addExact(originY, cell.y), relation.dy);
                            relation.targetZ = Math.addExact(Math.addExact(originZ, cell.z), relation.dz);
                            relation.targetDimension = dimension;
                        } else if ("absolute".equals(relation.previousMode)) {
                            relation.dx = Math.subtractExact(Math.subtractExact(relation.targetX, originX), cell.x);
                            relation.dy = Math.subtractExact(Math.subtractExact(relation.targetY, originY), cell.y);
                            relation.dz = Math.subtractExact(Math.subtractExact(relation.targetZ, originZ), cell.z);
                        }
                        relation.internal = !"clear".equals(relation.previousMode)
                            && (!HologramNativeHubData.adapter(relation.adapter)
                                || relation.targetDimension == dimension)
                            && selected.contains(
                                position(
                                    Math.subtractExact(relation.targetX, originX),
                                    Math.subtractExact(relation.targetY, originY),
                                    Math.subtractExact(relation.targetZ, originZ)));
                    } catch (ArithmeticException overflow) {
                        throw new IOException("坐标关系超出整数范围", overflow);
                    }
                    relations.add(relation);
                }
                continue;
            }
            String tileId = tag.getString("id");
            for (HologramNativeHubData.Binding binding : inspectBindings(tag)) {
                NBTTagCompound target = binding.target;
                Relation relation = relation(
                    cell,
                    binding.adapter,
                    HologramNativeHubData.HUB.equals(binding.adapter) ? "节点枢纽绑定" : "枢纽节点绑定 " + (binding.slot + 1),
                    binding.slot,
                    target.getInteger("x"),
                    target.getInteger("y"),
                    target.getInteger("z"),
                    target.getInteger("dim"),
                    originX,
                    originY,
                    originZ,
                    selected);
                relation.internal &= target.getInteger("dim") == dimension;
                relation.payload = payload;
                if ("item".equals(payload)) relation.label += "（物品）";
                relation.id = id(cell, payload, binding.adapter, binding.slot);
                relations.add(relation);
            }
            if ("item".equals(payload)) continue;
            for (HologramGtCoordinateData.Binding binding : HologramGtCoordinateData.bindings(tag)) relations.add(
                relation(
                    cell,
                    binding.adapter,
                    HologramGtCoordinateData.UNIT.equals(binding.adapter) ? "净化单元控制器"
                        : HologramGtCoordinateData.PCB.equals(binding.adapter) ? "PCB工厂控制器 " + (binding.slot + 1)
                            : HologramGtCoordinateData.BIO.equals(binding.adapter) ? "PCB生物处理室" : "PCB冷却塔",
                    binding.slot,
                    binding.x,
                    binding.y,
                    binding.z,
                    dimension,
                    originX,
                    originY,
                    originZ,
                    selected));
            if (panel(tileId) && tag.hasKey("Items", 9)) {
                NBTTagList inventory = tag.getTagList("Items", 10);
                for (int i = 0; i < inventory.tagCount(); i++) {
                    NBTTagCompound stack = inventory.getCompoundTagAt(i);
                    boolean sensor = HologramSnapshotMetadataSource.detached() ? cell.frozenSensorSlots.contains(i)
                        : sensorCard(stack);
                    if (!sensor || !stack.hasKey("Slot", 1) || !stack.hasKey("tag", 10)) continue;
                    int slot = stack.getByte("Slot") & 255;
                    if (slot > ("IC2NCAdvancedInfoPanel".equals(tileId) ? 2 : 0)) continue;
                    NBTTagCompound card = stack.getCompoundTag("tag");
                    // This complete-key check is a GTMB boundary; native NC itself reads absent keys as zero.
                    if (!xyz(card, "x", "y", "z")) continue;
                    Relation relation = relation(
                        cell,
                        "nc.sensor",
                        "传感卡 " + (slot + 1),
                        slot,
                        card.getInteger("x"),
                        card.getInteger("y"),
                        card.getInteger("z"),
                        dimension,
                        originX,
                        originY,
                        originZ,
                        selected);
                    relations.add(relation);
                }
            }
            if (extender(tileId) && tag.getBoolean("partOfScreen") && xyz(tag, "coreX", "coreY", "coreZ"))
                relations.add(
                    relation(
                        cell,
                        "nc.extender",
                        "信息面板扩展核心",
                        -1,
                        tag.getInteger("coreX"),
                        tag.getInteger("coreY"),
                        tag.getInteger("coreZ"),
                        dimension,
                        originX,
                        originY,
                        originZ,
                        selected));
            if (panel(tileId) && tag.hasKey("screenData", 10)) {
                NBTTagCompound screen = tag.getCompoundTag("screenData");
                if (xyz(screen, "minX", "minY", "minZ") && xyz(screen, "maxX", "maxY", "maxZ")) {
                    relations.add(
                        relation(
                            cell,
                            "nc.screen.min",
                            "屏幕范围首角",
                            -1,
                            screen.getInteger("minX"),
                            screen.getInteger("minY"),
                            screen.getInteger("minZ"),
                            dimension,
                            originX,
                            originY,
                            originZ,
                            selected));
                    relations.add(
                        relation(
                            cell,
                            "nc.screen.max",
                            "屏幕范围末角",
                            -1,
                            screen.getInteger("maxX"),
                            screen.getInteger("maxY"),
                            screen.getInteger("maxZ"),
                            dimension,
                            originX,
                            originY,
                            originZ,
                            selected));
                }
            }
        }
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (Relation relation : relations) {
            NBTTagCompound row = relation.write("absolute");
            row.setInteger("dx", relation.dx);
            row.setInteger("dy", relation.dy);
            row.setInteger("dz", relation.dz);
            row.setString("sourceMode", relation.previousMode == null ? "" : relation.previousMode);
            rows.appendTag(row);
        }
        result.setTag("rows", rows);
        return result;
    }

    private static List<HologramNativeHubData.Binding> inspectBindings(NBTTagCompound tag) {
        try {
            return HologramNativeHubData.bindings(tag);
        } catch (IOException unparsed) {
            // The resource-review page must remain available so the author can remove an opaque/malformed root.
            // The final resource inspector still rejects it; an unparsed record never grants a coordinate policy.
            return java.util.Collections.emptyList();
        }
    }

    private static Relation relation(HologramPresets.Cell cell, String adapter, String label, int slot, int tx, int ty,
        int tz, int dimension, int ox, int oy, int oz, Set<String> selected) throws IOException {
        Relation relation = new Relation();
        relation.x = cell.x;
        relation.y = cell.y;
        relation.z = cell.z;
        relation.adapter = adapter;
        relation.id = id(cell, adapter, slot);
        relation.label = label;
        relation.slot = slot;
        relation.targetX = tx;
        relation.targetY = ty;
        relation.targetZ = tz;
        relation.targetDimension = dimension;
        try {
            int localX = Math.subtractExact(tx, ox), localY = Math.subtractExact(ty, oy),
                localZ = Math.subtractExact(tz, oz);
            relation.internal = selected.contains(position(localX, localY, localZ));
            relation.dx = Math.subtractExact(localX, cell.x);
            relation.dy = Math.subtractExact(localY, cell.y);
            relation.dz = Math.subtractExact(localZ, cell.z);
        } catch (ArithmeticException overflow) {
            throw new IOException("坐标关系超出整数范围", overflow);
        }
        return relation;
    }

    static String mode(Relation relation, HologramBlueprintProcessing.Policy policy) {
        if (HologramNativeHubData.adapter(relation.adapter)) {
            if ("share".equals(policy.tendency) || "clear".equals(relation.previousMode)) return "clear";
            String selected = policy.coordinateOverrides.get(relation.id);
            if (selected != null) return selected;
            // Native node bindings keep their exact original dimension and coordinates for personal reuse.
            return relation.previousMode == null ? "absolute" : relation.previousMode;
        }
        String override = policy.coordinateOverrides.get(relation.id);
        if (override != null) return override;
        if (!"auto".equals(policy.coordinateDefault)) return policy.coordinateDefault;
        if ("clear".equals(relation.previousMode)) return "clear";
        if ("share".equals(policy.tendency)) return relation.internal ? "relative" : "clear";
        if (relation.previousMode != null) return relation.previousMode;
        return relation.internal ? "relative" : "absolute";
    }

    static void apply(HologramPresets.Preset preset, List<Relation> relations,
        HologramBlueprintProcessing.Policy policy) throws IOException {
        apply(preset, relations, policy, new HologramAnalysisFacts());
    }

    static void apply(HologramPresets.Preset preset, List<Relation> relations,
        HologramBlueprintProcessing.Policy policy, HologramAnalysisFacts facts) throws IOException {
        applyIndexed(preset, new HologramRelationIndex(relations), policy, facts);
    }

    static final class ApplyWork {

        long payloads, relations, encoded;
    }

    static ApplyWork applyIndexed(HologramPresets.Preset preset, HologramRelationIndex relations,
        HologramBlueprintProcessing.Policy policy) throws IOException {
        return applyIndexed(preset, relations, policy, new HologramAnalysisFacts());
    }

    private static ApplyWork applyIndexed(HologramPresets.Preset preset, HologramRelationIndex relations,
        HologramBlueprintProcessing.Policy policy, HologramAnalysisFacts facts) throws IOException {
        ApplyWork work = new ApplyWork();
        for (HologramPresets.Cell cell : preset.cells) {
            for (String payload : new String[] { "tile", "item" }) {
                work.payloads++;
                String original = "item".equals(payload) ? cell.itemNbt : cell.tileNbt;
                NBTTagCompound tag = facts.read(original);
                if (tag == null) continue;
                List<Relation> selected = relations.relations(cell.x, cell.y, cell.z, payload);
                if (selected.isEmpty() && !tag.hasKey(RELATIONS)) continue;
                NBTTagCompound before = tag;
                tag = (NBTTagCompound) tag.copy();
                tag.removeTag(RELATIONS);
                NBTTagList descriptors = new NBTTagList();
                java.util.Map<String, Boolean> bindingClears = new java.util.HashMap<>();
                for (Relation relation : selected) {
                    work.relations++;
                    if (target(tag, relation) == null && !"clear".equals(relation.previousMode)) continue;
                    String mode = mode(relation, policy);
                    if (HologramNativeHubData.adapter(relation.adapter)) {
                        if ("relative".equals(mode) && !relation.internal)
                            throw new IOException("枢纽/节点只能校准到蓝图内同维度实际选中的设备");
                        Boolean previous = bindingClears.put(relation.adapter, "clear".equals(mode));
                        if (previous != null && previous.booleanValue() != "clear".equals(mode))
                            throw new IOException("同一枢纽绑定列表必须整体清除，或保留/校准其中各节点");
                    }
                    if ("clear".equals(relation.previousMode) && !"clear".equals(mode))
                        throw new IOException("已经保存为清除的坐标关系没有原目标，不能伪造恢复；请重新捕获原设备");
                    if ("absolute".equals(mode)) {
                        writeCoordinates(tag, relation, relation.targetX, relation.targetY, relation.targetZ);
                        if ("nc.sensor".equals(relation.adapter)) target(tag, relation).setString(PAYMENT, "absolute");
                    } else scrubReviewedCoordinates(tag, relation, "clear".equals(mode));
                    descriptors.appendTag(relation.write(mode));
                }
                for (java.util.Map.Entry<String, Boolean> clear : bindingClears.entrySet())
                    if (clear.getValue()) HologramNativeHubData.clear(tag, clear.getKey());
                if (descriptors.tagCount() > 0) tag.setTag(RELATIONS, descriptors);
                if (before.equals(tag)) continue;
                work.encoded++;
                if ("item".equals(payload)) cell.itemNbt = HologramBlueprintNbt.encodeCanonical(tag);
                else cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
            }
        }
        // Reusable policies may contain relation choices from another source. Only currently inspected IDs apply.
        return work;
    }

    /** Keeps reviewed payment markers for the narrowly scoped item matcher; transport descriptors are removed. */
    static NBTTagCompound paymentProjection(NBTTagCompound source) throws IOException {
        if (source == null) return null;
        NBTTagCompound copy = (NBTTagCompound) source.copy();
        if (copy.hasKey(RELATIONS)) checkedRelations(copy);
        copy.removeTag(RELATIONS);
        return copy;
    }

    static boolean reviewedCoordinateItem(ItemStack expected) {
        return expected != null && expected.hasTagCompound()
            && !paymentMode(expected.getTagCompound()).isEmpty()
            && sensorCard(expected.getItem());
    }

    private static String paymentMode(NBTTagCompound tag) {
        if (tag.hasKey(PAYMENT, 8)) {
            String mode = tag.getString(PAYMENT);
            return java.util.Arrays.asList("relative", "clear", "absolute")
                .contains(mode) ? mode : "";
        }
        // The v0.1.0 draft bool marker only authorized relative/clear coordinate normalization.
        return tag.hasKey(PAYMENT, 1) && tag.getBoolean(PAYMENT) ? "relative" : "";
    }

    /** Validates only the registered panel/card layout; malformed marker modes never reach a priced lifecycle. */
    static void validatePaymentPayload(NBTTagCompound tag) throws IOException {
        if (tag == null || !panel(tag.getString("id"))) return;
        NBTTagList descriptors = tag.hasKey(RELATIONS) ? checkedRelations(tag) : new NBTTagList();
        NBTTagList inventory = tag.getTagList("Items", 10);
        for (int i = 0; i < inventory.tagCount(); i++) {
            NBTTagCompound stack = inventory.getCompoundTagAt(i);
            if (!sensorCard(stack) || !stack.hasKey("tag", 10)) continue;
            NBTTagCompound card = stack.getCompoundTag("tag");
            if (!card.hasKey(PAYMENT)) continue;
            String mode = paymentMode(card);
            if (mode.isEmpty()) throw new IOException("传感卡付款模式无效或尚未适配");
            if (descriptors.tagCount() == 0) throw new IOException("传感卡付款标记缺少已审阅坐标关系");
            boolean matched = false;
            int slot = stack.getByte("Slot") & 255;
            for (int j = 0; j < descriptors.tagCount(); j++) {
                NBTTagCompound relation = descriptors.getCompoundTagAt(j);
                if (!"nc.sensor".equals(relation.getString("adapter")) || relation.getInteger("slot") != slot) continue;
                String selected = relation.getString("mode");
                matched = card.hasKey(PAYMENT, 8) ? mode.equals(selected)
                    : "relative".equals(selected) || "clear".equals(selected);
            }
            if (!matched) throw new IOException("传感卡付款模式与已审阅坐标关系不一致");
        }
    }

    static ItemStack normalizePaymentItem(ItemStack source) {
        if (source != null && source.hasTagCompound()
            && source.getTagCompound()
                .hasKey(PAYMENT)
            && paymentMode(source.getTagCompound()).isEmpty()) return source.copy();
        String mode = source != null && source.hasTagCompound() ? paymentMode(source.getTagCompound()) : "";
        return normalizePaymentItem(source, mode.isEmpty() ? "relative" : mode);
    }

    private static ItemStack normalizePaymentItem(ItemStack source, String mode) {
        if (source == null) return null;
        ItemStack copy = source.copy();
        if (!sensorCard(copy.getItem())) return copy;
        if (copy.hasTagCompound()) {
            NBTTagCompound tag = normalizePaymentTag(
                copy.getItem()
                    .getClass()
                    .getName(),
                copy.getTagCompound(),
                !"absolute".equals(mode));
            copy.setTagCompound(tag);
            if (tag.hasNoTags()) copy.setTagCompound(null);
        }
        return copy;
    }

    /** Runtime observations never confer material value. Configuration and unknown fields remain exact. */
    static NBTTagCompound normalizePaymentTag(String type, NBTTagCompound source) {
        return normalizePaymentTag(type, source, true);
    }

    static NBTTagCompound normalizePaymentTag(String type, NBTTagCompound source, boolean coordinates) {
        NBTTagCompound tag = (NBTTagCompound) source.copy();
        if (!sensorCardType(type)) return tag;
        for (Object raw : new ArrayList<>(tag.func_150296_c())) {
            String key = (String) raw;
            int reviewed = runtimeCacheType(type, key);
            if (coordinates && ("x".equals(key) || "y".equals(key) || "z".equals(key)) && tag.hasKey(key, 3)
                || PAYMENT.equals(key) && !paymentMode(tag).isEmpty()
                || reviewed >= 0 && tag.hasKey(key, reviewed)) tag.removeTag(key);
        }
        return tag;
    }

    static boolean paymentEquivalent(ItemStack expected, ItemStack candidate) {
        if (expected == null || candidate == null || !expected.isItemEqual(candidate)) return false;
        if (sensorCard(expected.getItem()) && expected.hasTagCompound()
            && expected.getTagCompound()
                .hasKey(PAYMENT)
            && paymentMode(expected.getTagCompound()).isEmpty()) return false;
        if (!reviewedCoordinateItem(expected)) return ItemStack.areItemStackTagsEqual(expected, candidate);
        String mode = paymentMode(expected.getTagCompound());
        return ItemStack
            .areItemStackTagsEqual(normalizePaymentItem(expected, mode), normalizePaymentItem(candidate, mode));
    }

    private static void scrubReviewedCoordinates(NBTTagCompound tag, Relation relation, boolean clear)
        throws IOException {
        if (HologramGtCoordinateData.adapter(relation.adapter)) {
            HologramGtCoordinateData.scrub(tag, relation.adapter, relation.slot, clear);
            return;
        }
        NBTTagCompound root = target(tag, relation);
        if (root == null) return;
        if (HologramNativeHubData.adapter(relation.adapter)) {
            // Keep a complete, inert native tuple while the reviewed relative descriptor owns the real target.
            for (String key : new String[] { "x", "y", "z", "dim" }) root.setInteger(key, 0);
            return;
        }
        String prefix = relation.adapter.equals("nc.extender") ? "core"
            : relation.adapter.equals("nc.screen.min") ? "min" : relation.adapter.equals("nc.screen.max") ? "max" : "";
        root.removeTag(prefix.isEmpty() ? "x" : prefix + "X");
        root.removeTag(prefix.isEmpty() ? "y" : prefix + "Y");
        root.removeTag(prefix.isEmpty() ? "z" : prefix + "Z");
        if ("nc.sensor".equals(relation.adapter)) {
            root.removeTag(PAYMENT);
            if (clear && !clearableSensorTag(
                HologramBlueprintNbt.stableItem(sensorStack(tag, relation.slot))
                    .getClass()
                    .getName(),
                root)) throw new IOException("传感卡有额外不透明NBT，不能安全清除绑定及缓存；请选择保持绝对或校准相对");
            root.setString(PAYMENT, clear ? "clear" : "relative");
        } else if (clear && "nc.extender".equals(relation.adapter)) root.setBoolean("partOfScreen", false);
    }

    /** Installation/placed-state projection, after pricing. Source inventory and payment tags remain immutable. */
    static NBTTagCompound placementProjection(NBTTagCompound source, int targetDimension, int x, int y, int z)
        throws IOException {
        if (source == null) return null;
        NBTTagCompound copy = (NBTTagCompound) source.copy();
        bindPlacement(copy, targetDimension, x, y, z);
        copy.removeTag(RELATIONS);
        removeCardPayment(copy);
        return copy;
    }

    static void bindPlacement(NBTTagCompound tag, int targetDimension, int x, int y, int z) throws IOException {
        if (tag == null || !tag.hasKey(RELATIONS)) return;
        NBTTagList descriptors = checkedRelations(tag);
        boolean clearScreen = false;
        java.util.BitSet clearedControllers = new java.util.BitSet();
        for (int i = 0; i < descriptors.tagCount(); i++) {
            NBTTagCompound row = descriptors.getCompoundTagAt(i);
            Relation relation = read(row);
            String mode = row.getString("mode");
            if ("clear".equals(mode)) {
                if (relation.adapter.startsWith("nc.screen.")) clearScreen = true;
                else if (HologramGtCoordinateData.PCB.equals(relation.adapter)) clearedControllers.set(relation.slot);
                else clear(tag, relation);
            } else if ("relative".equals(mode)) {
                try {
                    writeCoordinates(
                        tag,
                        relation,
                        Math.addExact(x, relation.dx),
                        Math.addExact(y, relation.dy),
                        Math.addExact(z, relation.dz));
                    if (HologramNativeHubData.adapter(relation.adapter)) {
                        NBTTagCompound target = target(tag, relation);
                        if (target != null) target.setInteger("dim", targetDimension);
                    }
                } catch (ArithmeticException overflow) {
                    throw new IOException("放置坐标关系超出整数范围", overflow);
                }
            }
        }
        HologramGtCoordinateData.removeClearedControllers(tag, clearedControllers);
        if (clearScreen) tag.removeTag("screenData");
        else if (tag.hasKey("screenData", 10)) {
            NBTTagCompound screen = tag.getCompoundTag("screenData");
            for (String axis : new String[] { "X", "Y", "Z" }) {
                int low = screen.getInteger("min" + axis), high = screen.getInteger("max" + axis);
                screen.setInteger("min" + axis, Math.min(low, high));
                screen.setInteger("max" + axis, Math.max(low, high));
            }
        }
        removeCardPayment(tag);
    }

    private static void removeCardPayment(NBTTagCompound tag) {
        if (panel(tag.getString("id"))) {
            NBTTagList items = tag.getTagList("Items", 10);
            for (int i = 0; i < items.tagCount(); i++) {
                NBTTagCompound stack = items.getCompoundTagAt(i);
                if (sensorCard(stack) && stack.hasKey("tag", 10)) stack.getCompoundTag("tag")
                    .removeTag(PAYMENT);
            }
        }
    }

    static String rotatePayload(String encoded, boolean vertical) throws IOException {
        NBTTagCompound tag = HologramBlueprintNbt.decode(encoded);
        if (tag == null || !tag.hasKey(RELATIONS)) return encoded;
        NBTTagList descriptors = checkedRelations(tag);
        for (int i = 0; i < descriptors.tagCount(); i++) {
            NBTTagCompound row = descriptors.getCompoundTagAt(i);
            if (!"relative".equals(row.getString("mode"))) continue;
            int[] turned = HologramVirtualTransform
                .turn(row.getInteger("dx"), row.getInteger("dy"), row.getInteger("dz"), vertical);
            row.setInteger("dx", turned[0]);
            row.setInteger("dy", turned[1]);
            row.setInteger("dz", turned[2]);
        }
        return HologramBlueprintNbt.encodeCanonical(tag);
    }

    private static void clear(NBTTagCompound tag, Relation relation) throws IOException {
        if (HologramGtCoordinateData.adapter(relation.adapter)) {
            HologramGtCoordinateData.clear(tag, relation.adapter, relation.slot);
            return;
        }
        if (HologramNativeHubData.adapter(relation.adapter)) {
            HologramNativeHubData.clear(tag, relation.adapter);
            return;
        }
        if ("nc.sensor".equals(relation.adapter)) {
            NBTTagCompound stack = sensorStack(tag, relation.slot);
            if (stack != null) {
                NBTTagCompound card = stack.getCompoundTag("tag");
                Item item = HologramBlueprintNbt.stableItem(stack);
                if (!clearableSensorTag(
                    item.getClass()
                        .getName(),
                    card)) throw new IOException("传感卡附加NBT未通过清除格式核验");
                stack.removeTag("tag"); // NC's native getTarget returns null only when the card tag is absent.
            }
        } else if ("nc.extender".equals(relation.adapter)) {
            tag.removeTag("coreX");
            tag.removeTag("coreY");
            tag.removeTag("coreZ");
            tag.setBoolean("partOfScreen", false);
        }
    }

    private static void writeCoordinates(NBTTagCompound tag, Relation relation, int x, int y, int z)
        throws IOException {
        if (HologramGtCoordinateData.adapter(relation.adapter)) {
            HologramGtCoordinateData.write(tag, relation.adapter, relation.slot, x, y, z);
            return;
        }
        NBTTagCompound target = target(tag, relation);
        if (target == null) return;
        String prefix = relation.adapter.equals("nc.extender") ? "core"
            : relation.adapter.equals("nc.screen.min") ? "min" : relation.adapter.equals("nc.screen.max") ? "max" : "";
        target.setInteger(prefix.isEmpty() ? "x" : prefix + "X", x);
        target.setInteger(prefix.isEmpty() ? "y" : prefix + "Y", y);
        target.setInteger(prefix.isEmpty() ? "z" : prefix + "Z", z);
        if (HologramNativeHubData.adapter(relation.adapter)) target.setInteger("dim", relation.targetDimension);
    }

    private static NBTTagCompound target(NBTTagCompound tag, Relation relation) throws IOException {
        if (HologramGtCoordinateData.adapter(relation.adapter))
            return HologramGtCoordinateData.present(tag, relation.adapter, relation.slot) ? tag : null;
        if (HologramNativeHubData.adapter(relation.adapter))
            return HologramNativeHubData.target(tag, relation.adapter, relation.slot);
        if ("nc.sensor".equals(relation.adapter)) {
            if (!panel(tag.getString("id"))) throw new IOException("传感卡关系宿主格式不匹配");
            NBTTagCompound stack = sensorStack(tag, relation.slot);
            return stack != null && stack.hasKey("tag", 10) ? stack.getCompoundTag("tag") : null;
        }
        if ("nc.extender".equals(relation.adapter)) {
            if (!extender(tag.getString("id"))) throw new IOException("面板扩展关系宿主格式不匹配");
            return tag;
        }
        if (relation.adapter.startsWith("nc.screen.")) {
            if (!panel(tag.getString("id"))) throw new IOException("屏幕关系宿主格式不匹配");
            return tag.hasKey("screenData", 10) ? tag.getCompoundTag("screenData") : null;
        }
        throw new IOException("未适配的坐标关系格式");
    }

    private static NBTTagCompound sensorStack(NBTTagCompound tag, int slot) throws IOException {
        NBTTagList items = tag.getTagList("Items", 10);
        NBTTagCompound result = null;
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound stack = items.getCompoundTagAt(i);
            if ((stack.getByte("Slot") & 255) != slot) continue;
            if (!sensorCard(stack)) throw new IOException("传感卡关系对应物品已改变");
            if (result != null) throw new IOException("传感卡库存槽位重复");
            result = stack;
        }
        return result;
    }

    private static boolean sensorCard(NBTTagCompound stack) {
        return sensorCard(HologramBlueprintNbt.stableItem(stack));
    }

    static void freezeSensorSlots(HologramPresets.Cell cell, NBTTagCompound payload) {
        cell.frozenSensorSlots = new HashSet<>();
        if (payload == null || !panel(payload.getString("id")) || !payload.hasKey("Items", 9)) return;
        NBTTagList inventory = payload.getTagList("Items", 10);
        int maximum = "IC2NCAdvancedInfoPanel".equals(payload.getString("id")) ? 2 : 0;
        for (int i = 0; i < inventory.tagCount(); i++) {
            NBTTagCompound stack = inventory.getCompoundTagAt(i);
            if (stack.hasKey("Slot", 1) && (stack.getByte("Slot") & 255) <= maximum && sensorCard(stack))
                cell.frozenSensorSlots.add(i);
        }
    }

    /** Native panel reads may update CardState. All serialized identity, configuration and assets remain exact. */
    static NBTTagCompound sensorStateProjection(NBTTagCompound serializedStack) {
        return nativeSensorProjection(serializedStack, true);
    }

    static boolean nativePanelPayload(NBTTagCompound payload) {
        return payload != null && payload.hasKey("id", 8) && panel(payload.getString("id"));
    }

    /** Static native panel/configuration comparison; only registered typed sensor observations are projected out. */
    static NBTTagCompound nativePanelProjection(NBTTagCompound payload) {
        if (payload == null) return null;
        NBTTagCompound copy = (NBTTagCompound) payload.copy();
        if (!nativePanelPayload(copy) || !copy.hasKey("Items", 9)) return copy;
        NBTTagList items = (NBTTagList) copy.getTag("Items");
        if (items.func_150303_d() != 10 && !(items.tagCount() == 0 && items.func_150303_d() == 0)) return copy;
        NBTTagList projected = new NBTTagList();
        for (int i = 0; i < items.tagCount(); i++)
            projected.appendTag(nativeSensorProjection(items.getCompoundTagAt(i), false));
        copy.setTag("Items", projected);
        return copy;
    }

    private static NBTTagCompound nativeSensorProjection(NBTTagCompound serializedStack, boolean stateOnly) {
        if (serializedStack == null) return null;
        NBTTagCompound copy = (NBTTagCompound) serializedStack.copy();
        if (!sensorCard(copy) || !copy.hasKey("tag", 10)) return copy;
        NBTTagCompound tag = copy.getCompoundTag("tag");
        String type = HologramBlueprintNbt.stableItem(copy)
            .getClass()
            .getName();
        boolean changed = false;
        for (Object raw : new java.util.ArrayList<>(tag.func_150296_c())) {
            String key = (String) raw;
            int expectedType = stateOnly && !"state".equals(key) ? -1 : runtimeCacheType(type, key);
            if (expectedType >= 0 && tag.hasKey(key, expectedType)) {
                tag.removeTag(key);
                changed = true;
            }
        }
        if (changed && tag.hasNoTags()) copy.removeTag("tag");
        return copy;
    }

    private static boolean sensorCard(Item item) {
        if (item == null) return false;
        String type = item.getClass()
            .getName();
        return sensorCardType(type);
    }

    private static boolean sensorCardType(String type) {
        return type.equals(GT_SENSOR_CARD) || type.equals(NC_CARD_PACKAGE + "ItemCardReactorSensorLocation")
            || type.equals(NC_CARD_PACKAGE + "ItemCardEnergySensorLocation")
            || type.equals(NC_CARD_PACKAGE + "ItemCardMultipleSensorLocation");
    }

    /** Exact writes audited in NC 2.7.16 CardWrapperImpl and each sensor update method. These are observations. */
    static boolean clearableSensorTag(String type, NBTTagCompound tag) {
        if (!sensorCardType(type)) return false;
        for (Object raw : tag.func_150296_c()) {
            String key = (String) raw;
            int expected = runtimeCacheType(type, key);
            if ("x".equals(key) || "y".equals(key) || "z".equals(key)) expected = 3;
            else if ("title".equals(key)) expected = 8;
            else if (PAYMENT.equals(key)) expected = !paymentMode(tag).isEmpty() ? tag.getTag(key)
                .getId() : -1;
            else if ((NC_CARD_PACKAGE + "ItemCardEnergySensorLocation").equals(type) && "targetType".equals(key))
                expected = 3;
            if (expected < 0 || !tag.hasKey(key, expected)) return false;
        }
        return true;
    }

    /** Class-specific native set* writes: only typed observations, never title, targetType, recipes or assets. */
    private static int runtimeCacheType(String type, String key) {
        if (!sensorCardType(type)) return -1;
        if ("state".equals(key)) return 3; // NC CardWrapperImpl.setState / TileEntityInfoPanel.processCard.
        if (GT_SENSOR_CARD.equals(type)) {
            if ("mString".equals(key)) return 3;
            if (key.matches("mString[0-9]+")) return 8;
        } else if ((NC_CARD_PACKAGE + "ItemCardReactorSensorLocation").equals(type)) {
            if (java.util.Arrays.asList("heat", "maxHeat", "output", "timeLeft")
                .contains(key)) return 3;
            if ("reactorPoweredB".equals(key) || "isSteam".equals(key)) return 1;
        } else if ((NC_CARD_PACKAGE + "ItemCardEnergySensorLocation").equals(type)) {
            if (java.util.Arrays.asList("energyL", "maxStorageL", "range_trigger_amount")
                .contains(key)) return 6;
        } else if ((NC_CARD_PACKAGE + "ItemCardMultipleSensorLocation").equals(type)) {
            if (java.util.Arrays.asList("capacity", "amount", "liquidId", "powerType", "average", "production")
                .contains(key)) return 3;
            if ("liquidTag".equals(key)) return 10;
            if ("energy".equals(key) || "range_trigger_amount".equals(key)) return 6;
        }
        return -1;
    }

    private static boolean panel(String id) {
        return "IC2NCInfoPanel".equals(id) || "IC2NCAdvancedInfoPanel".equals(id);
    }

    private static boolean extender(String id) {
        return "IC2NCInfoPanelExtender".equals(id) || "IC2NCAdvancedInfoPanelExtender".equals(id);
    }

    private static boolean xyz(NBTTagCompound tag, String x, String y, String z) {
        return tag.hasKey(x, 3) && tag.hasKey(y, 3) && tag.hasKey(z, 3);
    }

    private static String position(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static String id(HologramPresets.Cell cell, String adapter, int slot) {
        return id(cell, "tile", adapter, slot);
    }

    private static String id(HologramPresets.Cell cell, String payload, String adapter, int slot) {
        return position(cell.x, cell.y, cell.z) + ":" + payload + ":" + adapter + ":" + slot;
    }

    private static boolean activeCell(Set<String> enabledStructures, HologramPresets.Cell cell) {
        List<String> memberships = cell.memberships();
        if (memberships.isEmpty()) return true;
        for (String id : memberships) if (enabledStructures.contains(id)) return true;
        return false;
    }

    private static NBTTagList checkedRelations(NBTTagCompound tag) throws IOException {
        if (!tag.hasKey(RELATIONS, 9)) throw new IOException("坐标关系数据类型无效");
        NBTTagList list = (NBTTagList) tag.getTag(RELATIONS);
        if (list.tagCount() > 8192 || list.tagCount() > 0 && list.func_150303_d() != 10)
            throw new IOException("坐标关系数据数量或类型无效");
        for (int i = 0; i < list.tagCount(); i++) read(list.getCompoundTagAt(i));
        return list;
    }

    private static Relation read(NBTTagCompound row) throws IOException {
        Relation relation = new Relation();
        relation.id = row.getString("id");
        relation.label = row.getString("label");
        relation.adapter = row.getString("adapter");
        relation.payload = row.hasKey("payload", 8) ? row.getString("payload") : "tile";
        relation.previousMode = row.getString("mode");
        relation.slot = row.getInteger("slot");
        for (String key : new String[] { "x", "y", "z", "slot", "targetX", "targetY", "targetZ", "targetDimension",
            "dx", "dy", "dz" }) if (!row.hasKey(key, 3)) throw new IOException("坐标关系字段类型无效");
        if (!java.util.Arrays.asList("clear", "absolute", "relative")
            .contains(relation.previousMode)
            || !java.util.Arrays.asList("nc.sensor", "nc.extender", "nc.screen.min", "nc.screen.max")
                .contains(relation.adapter) && !HologramNativeHubData.adapter(relation.adapter)
                && !HologramGtCoordinateData.adapter(relation.adapter)
            || relation.id.isEmpty()
            || relation.id.length() > 256
            || relation.label.length() > 256
            || !java.util.Arrays.asList("tile", "item")
                .contains(relation.payload)
            || "item".equals(relation.payload) && !HologramNativeHubData.adapter(relation.adapter)
            || "nc.sensor".equals(relation.adapter) && (relation.slot < 0 || relation.slot > 2)
            || !"nc.sensor".equals(relation.adapter) && !HologramNativeHubData.LIST.equals(relation.adapter)
                && !HologramNativeHubData.MAP.equals(relation.adapter)
                && !HologramGtCoordinateData.PCB.equals(relation.adapter)
                && relation.slot != -1
            || HologramGtCoordinateData.adapter(relation.adapter)
                && !HologramGtCoordinateData.validSlot(relation.adapter, relation.slot)
            || (HologramNativeHubData.LIST.equals(relation.adapter)
                || HologramNativeHubData.MAP.equals(relation.adapter)) && (relation.slot < 0 || relation.slot >= 8192))
            throw new IOException("坐标关系策略无效");
        relation.x = row.getInteger("x");
        relation.y = row.getInteger("y");
        relation.z = row.getInteger("z");
        relation.targetX = row.getInteger("targetX");
        relation.targetY = row.getInteger("targetY");
        relation.targetZ = row.getInteger("targetZ");
        relation.targetDimension = row.getInteger("targetDimension");
        relation.internal = row.getBoolean("internal");
        relation.dx = row.getInteger("dx");
        relation.dy = row.getInteger("dy");
        relation.dz = row.getInteger("dz");
        if (!"absolute".equals(relation.previousMode) && (relation.targetX != 0 || relation.targetY != 0
            || relation.targetZ != 0
            || relation.targetDimension != 0)) throw new IOException("相对或清除关系不能保留旧绝对坐标");
        if ("clear".equals(relation.previousMode) && (relation.dx != 0 || relation.dy != 0 || relation.dz != 0))
            throw new IOException("清除关系不能隐藏保存旧相对目标");
        return relation;
    }
}
