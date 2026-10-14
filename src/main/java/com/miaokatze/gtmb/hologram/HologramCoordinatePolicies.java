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
            row.setBoolean("implicitDimension", true);
            row.setBoolean("physicalAsset", "nc.sensor".equals(adapter));
            if ("nc.sensor".equals(adapter)) row.setString("clearDetail", "清除会重置传感卡绑定、已审阅的监控缓存和标题；卡片及其它付款数据仍严格核验");
            return row;
        }
    }

    static List<Relation> inspect(HologramPresets.Preset preset, int dimension, int originX, int originY, int originZ)
        throws IOException {
        List<Relation> relations = new ArrayList<>();
        if (preset == null) return relations;
        Set<String> selected = new HashSet<>();
        for (HologramPresets.Cell cell : preset.cells)
            if (!cell.excluded && activeCell(preset, cell)) selected.add(position(cell.x, cell.y, cell.z));
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null) continue;
            if (tag.hasKey(RELATIONS)) {
                NBTTagList existing = checkedRelations(tag);
                for (int i = 0; i < existing.tagCount(); i++) {
                    NBTTagCompound descriptor = existing.getCompoundTagAt(i);
                    Relation relation = read(descriptor);
                    relation.x = cell.x;
                    relation.y = cell.y;
                    relation.z = cell.z;
                    relation.id = id(cell, relation.adapter, relation.slot);
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
                        relation.internal = !"clear".equals(relation.previousMode) && selected.contains(
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
            if (panel(tileId) && tag.hasKey("Items", 9)) {
                NBTTagList inventory = tag.getTagList("Items", 10);
                for (int i = 0; i < inventory.tagCount(); i++) {
                    NBTTagCompound stack = inventory.getCompoundTagAt(i);
                    if (!sensorCard(stack) || !stack.hasKey("Slot", 1) || !stack.hasKey("tag", 10)) continue;
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
        if (relations.size() > 8192) throw new IOException("已适配坐标关系数量超出上限");
        return relations;
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
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null) continue;
            tag.removeTag(RELATIONS);
            NBTTagList descriptors = new NBTTagList();
            for (Relation relation : relations)
                if (relation.x == cell.x && relation.y == cell.y && relation.z == cell.z) {
                    if (target(tag, relation) == null) continue; // User explicitly omitted the containing NBT field.
                    String mode = mode(relation, policy);
                    if ("clear".equals(relation.previousMode) && !"clear".equals(mode))
                        throw new IOException("已经保存为清除的坐标关系没有原目标，不能伪造恢复；请重新捕获原设备");
                    if ("absolute".equals(mode)) {
                        writeCoordinates(tag, relation, relation.targetX, relation.targetY, relation.targetZ);
                        if ("nc.sensor".equals(relation.adapter)) target(tag, relation).setString(PAYMENT, "absolute");
                    } else scrubReviewedCoordinates(tag, relation, "clear".equals(mode));
                    descriptors.appendTag(relation.write(mode));
                }
            if (descriptors.tagCount() > 0) tag.setTag(RELATIONS, descriptors);
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        }
        // Reusable policies may contain relation choices from another source. Only currently inspected IDs apply.
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
        NBTTagCompound root = target(tag, relation);
        if (root == null) return;
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
        for (int i = 0; i < descriptors.tagCount(); i++) {
            NBTTagCompound row = descriptors.getCompoundTagAt(i);
            Relation relation = read(row);
            String mode = row.getString("mode");
            if ("clear".equals(mode)) {
                if (relation.adapter.startsWith("nc.screen.")) clearScreen = true;
                else clear(tag, relation);
            } else if ("relative".equals(mode)) {
                try {
                    writeCoordinates(
                        tag,
                        relation,
                        Math.addExact(x, relation.dx),
                        Math.addExact(y, relation.dy),
                        Math.addExact(z, relation.dz));
                } catch (ArithmeticException overflow) {
                    throw new IOException("放置坐标关系超出整数范围", overflow);
                }
            }
        }
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
        NBTTagCompound target = target(tag, relation);
        if (target == null) return;
        String prefix = relation.adapter.equals("nc.extender") ? "core"
            : relation.adapter.equals("nc.screen.min") ? "min" : relation.adapter.equals("nc.screen.max") ? "max" : "";
        target.setInteger(prefix.isEmpty() ? "x" : prefix + "X", x);
        target.setInteger(prefix.isEmpty() ? "y" : prefix + "Y", y);
        target.setInteger(prefix.isEmpty() ? "z" : prefix + "Z", z);
    }

    private static NBTTagCompound target(NBTTagCompound tag, Relation relation) throws IOException {
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
        return position(cell.x, cell.y, cell.z) + ":tile:" + adapter + ":" + slot;
    }

    private static boolean activeCell(HologramPresets.Preset preset, HologramPresets.Cell cell) {
        List<String> memberships = cell.memberships();
        if (memberships.isEmpty()) return true;
        for (HologramPresets.Structure structure : preset.structures)
            if (structure.enabled && memberships.contains(structure.id)) return true;
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
        relation.previousMode = row.getString("mode");
        relation.slot = row.getInteger("slot");
        for (String key : new String[] { "x", "y", "z", "slot", "targetX", "targetY", "targetZ", "targetDimension",
            "dx", "dy", "dz" }) if (!row.hasKey(key, 3)) throw new IOException("坐标关系字段类型无效");
        if (!java.util.Arrays.asList("clear", "absolute", "relative")
            .contains(relation.previousMode)
            || !java.util.Arrays.asList("nc.sensor", "nc.extender", "nc.screen.min", "nc.screen.max")
                .contains(relation.adapter)
            || relation.id.isEmpty()
            || relation.id.length() > 256
            || relation.label.length() > 256
            || "nc.sensor".equals(relation.adapter) && (relation.slot < 0 || relation.slot > 2)
            || !"nc.sensor".equals(relation.adapter) && relation.slot != -1) throw new IOException("坐标关系策略无效");
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
