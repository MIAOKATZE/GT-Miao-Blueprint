package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

/** Detached, repeatable authoring projection. The server retains the unmodified review source in its session. */
final class HologramBlueprintProcessing {

    static final int VERSION = 1;
    static final List<String> STAGES = java.util.Collections
        .unmodifiableList(Arrays.asList("preset", "identity", "quantum", "coordinates", "nbt", "hints"));

    private HologramBlueprintProcessing() {}

    static final class CellNbtOverride {

        int x, y, z;
        boolean all = true;
        List<String> keys = new ArrayList<>();
        List<String> ignoredPaths = new ArrayList<>();

        NBTTagCompound write() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("x", x);
            tag.setInteger("y", y);
            tag.setInteger("z", z);
            tag.setBoolean("all", all);
            tag.setTag("keys", strings(keys));
            tag.setTag("ignoredPaths", strings(ignoredPaths));
            return tag;
        }
    }

    static final class Policy {

        String tendency = "self", quantum = "preserve", coordinateDefault = "auto", autoHints = "light";
        int deidentificationLevel;
        List<String> confirmedStages = new ArrayList<>();
        List<String> ignoredNbtKeys = new ArrayList<>(), ignoredNbtPaths = new ArrayList<>();
        Map<String, String> coordinateOverrides = new LinkedHashMap<>();
        List<CellNbtOverride> cellNbtOverrides = new ArrayList<>();

        NBTTagCompound write() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("version", VERSION);
            tag.setString("tendency", tendency);
            tag.setInteger("deidentificationLevel", deidentificationLevel);
            tag.setString("quantum", quantum);
            tag.setString("coordinateDefault", coordinateDefault);
            tag.setString("autoHints", autoHints);
            tag.setTag("confirmedStages", strings(confirmedStages));
            tag.setTag("ignoredNbtKeys", strings(ignoredNbtKeys));
            tag.setTag("ignoredNbtPaths", strings(ignoredNbtPaths));
            NBTTagList relations = new NBTTagList();
            for (Map.Entry<String, String> entry : coordinateOverrides.entrySet()) {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("id", entry.getKey());
                row.setString("mode", entry.getValue());
                relations.appendTag(row);
            }
            tag.setTag("coordinateOverrides", relations);
            NBTTagList overrides = new NBTTagList();
            for (CellNbtOverride cell : cellNbtOverrides) overrides.appendTag(cell.write());
            tag.setTag("cellNbtOverrides", overrides);
            return tag;
        }

        static Policy read(NBTTagCompound tag) throws IOException {
            if (tag == null || !tag.hasKey("version", 3) || tag.getInteger("version") != VERSION)
                throw new IOException("处理流程版本无效");
            Policy policy = new Policy();
            policy.tendency = choice(tag, "tendency", "self", "share");
            if (!tag.hasKey("deidentificationLevel", 3)) throw new IOException("请明确选择去标识等级");
            policy.deidentificationLevel = HologramDeidentification.checked(tag.getInteger("deidentificationLevel"));
            policy.quantum = choice(tag, "quantum", "preserve", "rebind", "clear");
            policy.coordinateDefault = choice(tag, "coordinateDefault", "auto", "clear", "absolute", "relative");
            policy.autoHints = choice(tag, "autoHints", "light", "full", "none");
            policy.confirmedStages = readStrings(tag, "confirmedStages", STAGES.size(), 32);
            for (int i = 0; i < policy.confirmedStages.size(); i++) if (!STAGES.get(i)
                .equals(policy.confirmedStages.get(i))) throw new IOException("请依次明确确认处理流程的每个阶段");
            policy.ignoredNbtKeys = readStrings(tag, "ignoredNbtKeys", 256, 256);
            policy.ignoredNbtPaths = readStrings(tag, "ignoredNbtPaths", 512, 1024);
            NBTTagList relations = compounds(tag, "coordinateOverrides", 8192);
            for (int i = 0; i < relations.tagCount(); i++) {
                NBTTagCompound row = relations.getCompoundTagAt(i);
                String id = text(row.getString("id"), 256);
                String mode = choice(row, "mode", "clear", "absolute", "relative");
                if (id.isEmpty() || policy.coordinateOverrides.put(id, mode) != null)
                    throw new IOException("坐标关系设置无效或重复");
            }
            NBTTagList cells = compounds(tag, "cellNbtOverrides", 8192);
            Set<String> positions = new LinkedHashSet<>();
            for (int i = 0; i < cells.tagCount(); i++) {
                NBTTagCompound row = cells.getCompoundTagAt(i);
                if (!row.hasKey("x", 3) || !row.hasKey("y", 3) || !row.hasKey("z", 3) || !row.hasKey("all", 1))
                    throw new IOException("单格NBT设置缺少位置或明确选择");
                CellNbtOverride cell = new CellNbtOverride();
                cell.x = row.getInteger("x");
                cell.y = row.getInteger("y");
                cell.z = row.getInteger("z");
                cell.all = row.getBoolean("all");
                cell.keys = readStrings(row, "keys", 256, 256);
                cell.ignoredPaths = readStrings(row, "ignoredPaths", 512, 1024);
                if (!positions.add(position(cell.x, cell.y, cell.z))) throw new IOException("单格NBT设置位置重复");
                policy.cellNbtOverrides.add(cell);
            }
            for (String path : policy.ignoredNbtPaths) checkPath(path);
            for (CellNbtOverride cell : policy.cellNbtOverrides) for (String path : cell.ignoredPaths) checkPath(path);
            return policy;
        }

        boolean complete() {
            return confirmedStages.equals(STAGES);
        }
    }

    static Policy defaults(String tendency, int minimumLevel) throws IOException {
        Policy policy = new Policy();
        if (!"self".equals(tendency) && !"share".equals(tendency)) throw new IOException("处理倾向无效");
        policy.tendency = tendency;
        policy.deidentificationLevel = Math
            .max(HologramDeidentification.checked(minimumLevel), "share".equals(tendency) ? 6 : 0);
        policy.quantum = "share".equals(tendency) ? "rebind" : "preserve";
        policy.autoHints = "share".equals(tendency) ? "full" : "light";
        return policy;
    }

    static Policy policy(HologramPresets.Preset preset) throws IOException {
        if (preset != null && preset.processingNbt != null) {
            NBTTagCompound receipt = HologramBlueprintNbt.decode(preset.processingNbt);
            if (receipt.hasKey("policy", 10)) return Policy.read(receipt.getCompoundTag("policy"));
        }
        return defaults("self", minimumLevel(preset));
    }

    static HologramPresets.Preset process(HologramPresets.Preset source, Policy requested, int dimension, int originX,
        int originY, int originZ) throws IOException {
        if (source == null || requested == null) throw new IOException("没有可处理的蓝图来源");
        Policy policy = Policy.read(requested.write());
        if (!policy.complete()) throw new IOException("保存前必须明确完成全部处理阶段");
        HologramQuantumGroups.requireConfirmed(source);
        int minimum = minimumLevel(source);
        if (policy.deidentificationLevel < minimum) throw new IOException("旧结果已清除的身份不能恢复，请选择至少等级 " + minimum);
        // Capture exact coordinate schemas before any identity projection removes a registered relation field.
        List<HologramCoordinatePolicies.Relation> relations = HologramCoordinatePolicies
            .inspect(source, dimension, originX, originY, originZ);
        HologramPresets.Preset selected = HologramPresets.copy(source);
        removeGeneratedHints(selected);
        selected.processingNbt = null;
        for (HologramPresets.Cell cell : selected.cells) {
            CellNbtOverride override = override(policy, cell);
            cell.tileNbt = select(
                cell.tileNbt,
                "tile",
                policy,
                override,
                selected.retainNbt,
                selected.retainedNbtKeys,
                selected.retainCovers,
                cell.block());
            cell.itemNbt = select(
                cell.itemNbt,
                "item",
                policy,
                override,
                selected.retainNbt,
                selected.retainedNbtKeys,
                selected.retainCovers,
                cell.block());
            cell.tileNbt = projectGtMe(cell, cell.tileNbt);
            cell.itemNbt = projectGtMe(cell, cell.itemNbt);
        }
        // All choices are now materialized in the detached cells. A legacy global filter must not override them later.
        selected.retainNbt = true;
        selected.retainedNbtKeys = null;
        // Palette entries are item/damage candidate samples, never installation or payment payloads.
        for (HologramPresets.Cell sample : selected.palette) {
            sample.tileNbt = null;
            sample.itemNbt = null;
        }
        HologramPresets.Preset result = HologramDeidentification.apply(selected, policy.deidentificationLevel);
        result.deidentificationChosen = true;
        HologramCoordinatePolicies.apply(result, relations, policy);
        if ("clear".equals(policy.quantum)) clearQuantum(result);
        else applyQuantumMode(result, policy.quantum);
        NBTTagCompound generatedHints = addHints(source, result, relations, policy);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", VERSION);
        receipt.setBoolean("complete", true);
        receipt.setBoolean("legacy", false);
        receipt.setInteger("minimumLevel", minimum);
        receipt.setTag("policy", policy.write());
        receipt.setTag("generatedHints", generatedHints);
        result.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
        return result;
    }

    static NBTTagCompound state(HologramPresets.Preset source, Policy policy, int dimension, int originX, int originY,
        int originZ) throws IOException {
        if (policy == null) policy = policy(source);
        NBTTagCompound state = new NBTTagCompound();
        state.setTag("policy", policy.write());
        state.setBoolean("complete", complete(source));
        state.setBoolean("legacy", legacy(source));
        state.setInteger("minimumLevel", minimumLevel(source));
        state.setTag("quantumGroups", HologramQuantumGroups.groups(source));
        state.setTag("quantumNodes", HologramQuantumGroups.nodes(source));
        state.setBoolean("quantumGroupsConfirmed", HologramQuantumGroups.confirmed(source));
        NBTTagList rows = new NBTTagList();
        for (HologramCoordinatePolicies.Relation relation : HologramCoordinatePolicies
            .inspect(source, dimension, originX, originY, originZ)) {
            NBTTagCompound row = relation.write(HologramCoordinatePolicies.mode(relation, policy));
            row.setInteger("targetX", relation.targetX);
            row.setInteger("targetY", relation.targetY);
            row.setInteger("targetZ", relation.targetZ);
            row.setInteger("targetDimension", relation.targetDimension);
            row.setBoolean("targetKnown", !"clear".equals(relation.previousMode));
            rows.appendTag(row);
        }
        state.setTag("relations", rows);
        Set<String> keys = new java.util.TreeSet<>(), paths = new java.util.TreeSet<>();
        if (source != null) for (HologramPresets.Cell cell : source.cells) {
            collect(cell.tileNbt, "tile:", keys, paths);
            collect(cell.itemNbt, "item:", keys, paths);
            if (keys.size() > 256 || paths.size() > 4096) break;
        }
        state.setTag("nbtKeys", strings(new ArrayList<>(keys).subList(0, Math.min(256, keys.size()))));
        state.setTag("nbtPaths", strings(new ArrayList<>(paths).subList(0, Math.min(4096, paths.size()))));
        state.setInteger("requiredStages", requiredStages(source, dimension, originX, originY, originZ));
        return state;
    }

    /** Derived from the actual server source, never from a client claim that sensitive data is absent. */
    static int requiredStages(HologramPresets.Preset source, int dimension, int x, int y, int z) throws IOException {
        if (source == null || source.cells.isEmpty()) return 63;
        int required = minimumLevel(source) > 0 ? 1 << 1 : 0;
        if (!HologramCoordinatePolicies.inspect(source, dimension, x, y, z)
            .isEmpty()) required |= 1 << 3;
        for (HologramPresets.Cell cell : source.cells) {
            NBTTagCompound tile = HologramBlueprintNbt.decode(cell.tileNbt);
            if (HologramNetworkBindings.node(cell.block()) || tile != null
                && (tile.hasKey(HologramNetworkBindings.GROUP) || tile.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR)))
                required |= 1 << 2;
            for (NBTTagCompound tag : new NBTTagCompound[] { tile, HologramBlueprintNbt.decode(cell.itemNbt) }) {
                if (tag == null) continue;
                Map<Integer, List<String>> risks = HologramDeidentification.retainedRiskFields(cell, tag, "");
                for (List<String> paths : risks.values())
                    for (String path : paths) if (!path.matches("/?[xyz]")) required |= 1 << 1;
                for (Object raw : tag.func_150296_c()) {
                    String key = (String) raw;
                    if (!Arrays.asList("id", "x", "y", "z", "gtitBlueprintItemRegistry", "gtitBlueprintCoverRegistry")
                        .contains(key)) required |= 1 << 4;
                }
            }
        }
        if (required != 0) required |= 1 << 5;
        return required;
    }

    static boolean complete(HologramPresets.Preset preset) throws IOException {
        if (preset == null || preset.processingNbt == null) return false;
        NBTTagCompound receipt = HologramBlueprintNbt.decode(preset.processingNbt);
        return receipt.getBoolean("complete") && !receipt.getBoolean("legacy")
            && receipt.hasKey("policy", 10)
            && Policy.read(receipt.getCompoundTag("policy"))
                .complete();
    }

    /** Canonical AE/quantum analysis may rediscover a shell node; the explicit completed author decision wins. */
    static HologramPresets.Preset enforcePolicy(HologramPresets.Preset preset) throws IOException {
        if (quantumCleared(preset)) clearQuantum(preset);
        else if (complete(preset)) applyQuantumMode(preset, policy(preset).quantum);
        return preset;
    }

    private static void applyQuantumMode(HologramPresets.Preset preset, String mode) throws IOException {
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null || !tag.hasKey(HologramNetworkBindings.GROUP)) continue;
            tag.setString(HologramNetworkBindings.MODE, mode);
            if ("rebind".equals(mode)) tag.removeTag(HologramNetworkBindings.ORIGINAL);
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        }
    }

    static boolean quantumCleared(HologramPresets.Preset preset) throws IOException {
        return complete(preset) && "clear".equals(policy(preset).quantum);
    }

    /** Native AE/verified network reconstruction already has an irreversible level-one identity boundary. */
    static int minimumLevel(HologramPresets.Preset preset) throws IOException {
        int minimum = HologramDeidentification.level(preset);
        if (preset == null || minimum > 0) return minimum;
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (HologramAeBlueprint.supports(cell.block(), tag) || HologramNetworkBindings.node(cell.block())
                || tag != null
                    && (tag.hasKey(HologramNetworkBindings.GROUP) || tag.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR))
                || HologramNetworkSchemas.removeExternalReferences(
                    cell.block(),
                    cell.damage,
                    tag == null ? null : (NBTTagCompound) tag.copy()))
                return 1;
        }
        return minimum;
    }

    private static boolean legacy(HologramPresets.Preset preset) throws IOException {
        return preset != null && (preset.processingNbt == null ? preset.id != null
            : HologramBlueprintNbt.decode(preset.processingNbt)
                .getBoolean("legacy"));
    }

    static void markLegacy(HologramPresets.Preset preset) throws IOException {
        if (preset == null || preset.processingNbt != null) return;
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", VERSION);
        receipt.setBoolean("legacy", true);
        receipt.setBoolean("complete", false);
        receipt.setInteger("minimumLevel", HologramDeidentification.level(preset));
        receipt.setString("reason", "旧蓝图没有逐阶段处理记录；已经清除的身份不能恢复");
        preset.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
    }

    static void validateReceipt(HologramPresets.Preset preset) throws IOException {
        if (preset.processingNbt == null) return;
        NBTTagCompound receipt = HologramBlueprintNbt.decode(preset.processingNbt);
        if (receipt == null || !receipt.hasKey("version", 3) || receipt.getInteger("version") != VERSION)
            throw new IOException("蓝图处理记录版本无效");
        if (receipt.hasKey("policy", 10)) {
            Policy policy = Policy.read(receipt.getCompoundTag("policy"));
            if (receipt.getBoolean("complete") && !policy.complete()) throw new IOException("蓝图处理记录不完整");
        } else if (receipt.getBoolean("complete")) throw new IOException("蓝图处理记录缺少阶段选择");
        generatedBlocks(preset);
    }

    static void invalidate(HologramPresets.Preset preset) throws IOException {
        invalidate(preset, "identity");
    }

    static void invalidate(HologramPresets.Preset preset, String dependency) throws IOException {
        if (preset == null) return;
        NBTTagCompound previous = HologramBlueprintNbt.decode(preset.processingNbt);
        Policy policy = policy(preset);
        String stage = "prompt".equals(dependency) ? "hints"
            : "quantum".equals(dependency) ? "quantum"
                : "geometry".equals(dependency) ? "coordinates" : "nbt".equals(dependency) ? "nbt" : "identity";
        int index = STAGES.indexOf(stage);
        while (policy.confirmedStages.size() > index) policy.confirmedStages.remove(policy.confirmedStages.size() - 1);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", VERSION);
        receipt.setBoolean("complete", false);
        receipt.setBoolean("legacy", legacy(preset));
        receipt.setInteger("minimumLevel", HologramDeidentification.level(preset));
        receipt.setTag("policy", policy.write());
        if (previous != null && previous.hasKey("generatedHints", 10)) receipt.setTag(
            "generatedHints",
            previous.getCompoundTag("generatedHints")
                .copy());
        preset.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
    }

    private static String select(String encoded, String kind, Policy policy, CellNbtOverride override,
        boolean retainNbt, List<String> retainedKeys, boolean covers, net.minecraft.block.Block block)
        throws IOException {
        NBTTagCompound tag = HologramBlueprintNbt.decode(encoded);
        if (tag == null) return null;
        if (override != null && !override.all)
            tag = HologramBlueprintNbt.filterSelection(block, tag, override.keys, covers);
        else if (override == null) {
            tag = HologramBlueprintNbt
                .filterSelection(block, tag, retainNbt ? retainedKeys : java.util.Collections.emptyList(), covers);
            for (String key : policy.ignoredNbtKeys)
                if (!HologramBlueprintNbt.protectedKey(block, tag, key)) tag.removeTag(key);
        } else tag = HologramBlueprintNbt.filterSelection(block, tag, null, covers);
        for (String path : override == null ? policy.ignoredNbtPaths : override.ignoredPaths)
            if (path.startsWith(kind + ":")) removePath(tag, path.substring(kind.length() + 1), block);
        HologramBlueprintNbt.normalizeResourceSelection(tag);
        return HologramBlueprintNbt.encodeCanonical(tag);
    }

    private static String projectGtMe(HologramPresets.Cell cell, String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty() || cell.block() != gregtech.api.GregTechAPI.sBlockMachines)
            return encoded;
        return HologramBlueprintNbt.encodeCanonical(HologramGtMeShadow.project(HologramBlueprintNbt.decode(encoded)));
    }

    private static CellNbtOverride override(Policy policy, HologramPresets.Cell cell) {
        for (CellNbtOverride entry : policy.cellNbtOverrides)
            if (entry.x == cell.x && entry.y == cell.y && entry.z == cell.z) return entry;
        return null;
    }

    static void removeNbtPath(HologramPresets.Preset preset, List<Integer> indices, String path) throws IOException {
        checkPath(path);
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= preset.cells.size()) throw new IOException("方块NBT编辑位置无效");
            HologramPresets.Cell cell = preset.cells.get(index);
            boolean tile = path.startsWith("tile:");
            NBTTagCompound tag = HologramBlueprintNbt.decode(tile ? cell.tileNbt : cell.itemNbt);
            if (tag == null) continue;
            removePath(tag, path.substring(path.indexOf(':') + 1), cell.block());
            HologramBlueprintNbt.normalizeResourceSelection(tag);
            if (tile) cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
            else cell.itemNbt = HologramBlueprintNbt.encodeCanonical(tag);
        }
        invalidate(preset, "nbt");
    }

    /** A value editor has no additive asset capability: only registered configuration fields may be changed. */
    static void editNbtValue(HologramPresets.Preset preset, int[] indices, String path, String type, String value)
        throws IOException {
        checkPath(path);
        if (indices == null || indices.length == 0 || indices.length > 8192) throw new IOException("请选择需要编辑NBT的方块");
        if (value == null || value.length() > 4096) throw new IOException("NBT值过长");
        List<NBTTagCompound> staged = new ArrayList<>();
        boolean tile = path.startsWith("tile:");
        for (int index : indices) {
            if (index < 0 || index >= preset.cells.size()) throw new IOException("方块NBT编辑位置无效");
            HologramPresets.Cell cell = preset.cells.get(index);
            NBTTagCompound tag = HologramBlueprintNbt.decode(tile ? cell.tileNbt : cell.itemNbt);
            if (tag == null) throw new IOException("所选方块没有该NBT数据");
            String[] segments = path.substring(path.indexOf(':') + 2)
                .split("/", -1);
            // Nested opaque data, physical ItemStacks, recipes and relationships never acquire a generic setter.
            if (segments.length != 1 || !editableConfig(cell, tag, unescape(segments[0])))
                throw new IOException("该条目属于系统身份、关系、真实资产或未审阅数据，只能明确忽略，不能改写数值");
            String key = unescape(segments[0]);
            NBTBase original = tag.getTag(key);
            if (original == null || !type(original).equals(type)) throw new IOException("NBT值必须保持已有条目的原始类型");
            tag.setTag(key, parseValue(type, value));
            staged.add(tag);
        }
        for (int i = 0; i < indices.length; i++) {
            String encoded = HologramBlueprintNbt.encodeCanonical(staged.get(i));
            if (tile) preset.cells.get(indices[i]).tileNbt = encoded;
            else preset.cells.get(indices[i]).itemNbt = encoded;
        }
        invalidate(preset, "nbt");
    }

    static NBTTagList nbtEntries(HologramPresets.Cell cell) throws IOException {
        NBTTagList entries = new NBTTagList();
        entryRows(cell, HologramBlueprintNbt.decode(cell.tileNbt), "tile:", entries, 0, null);
        entryRows(cell, HologramBlueprintNbt.decode(cell.itemNbt), "item:", entries, 0, null);
        return entries;
    }

    private static void entryRows(HologramPresets.Cell cell, NBTTagCompound tag, String prefix, NBTTagList entries,
        int depth, NBTTagCompound device) {
        if (tag == null || depth > 8 || entries.tagCount() >= 2048) return;
        if (device == null) device = tag;
        for (Object raw : tag.func_150296_c()) {
            String key = (String) raw;
            NBTBase value = tag.getTag(key);
            NBTTagCompound row = new NBTTagCompound();
            String path = prefix + "/"
                + key.replace("~", "~0")
                    .replace("/", "~1");
            boolean protectedField = depth == 0 && HologramBlueprintNbt.protectedKey(cell.block(), device, key);
            boolean editable = depth == 0 && editableConfig(cell, device, key)
                && value.getId() >= 1
                && value.getId() <= 8
                && value.getId() != 7;
            row.setString("key", key);
            row.setString("path", path);
            row.setString("type", type(value));
            String rendered = scalar(value);
            row.setString("value", rendered.length() > 4096 ? rendered.substring(0, 4096) : rendered);
            row.setBoolean("editable", editable);
            row.setBoolean("protected", protectedField);
            row.setString("category", category(cell, device, key, depth, editable, protectedField));
            row.setString(
                "detail",
                depth == 0 && "cbOwner".equals(key) && HologramDeidentification.carpenterOwner(cell.block(), device)
                    ? "Carpenter 原生所有者：等级0保留，等级1以上清空；放置时仅空身份绑定当前玩家"
                    : protectedField ? "系统身份由服务器维护"
                        : editable ? "已审阅的配置项；修改后须重新完成NBT核验" : "保留原数据；此项可能含真实资产或未审阅格式，仅支持明确忽略");
            entries.appendTag(row);
            if (value instanceof NBTTagCompound && !protectedField)
                entryRows(cell, (NBTTagCompound) value, path, entries, depth + 1, device);
            // Lists are described as a whole. Inventory and recipe lists are never recursively made editable.
            if (entries.tagCount() >= 2048) return;
        }
    }

    private static boolean editableConfig(HologramPresets.Cell cell, NBTTagCompound tag, String key) {
        if (HologramBlueprintNbt.protectedKey(key)) return false;
        boolean registered = cell.block() != null && cell.block() == gregtech.api.GregTechAPI.sBlockMachines
            || tag.getString("id")
                .startsWith("gtswn.");
        return registered && ("currentTab".equals(key) || "mActive".equals(key) || "mWasShutdown".equals(key));
    }

    private static String category(HologramPresets.Cell cell, NBTTagCompound tag, String key, int depth,
        boolean editable, boolean protectedField) {
        if (protectedField) return key.toLowerCase(java.util.Locale.ROOT)
            .contains("owner") ? "identity" : "system";
        if (editable) return "configuration";
        if (depth > 0) return "opaque";
        String id = tag.getString("id");
        boolean nc = id.equals("IC2NCInfoPanel") || id.equals("IC2NCAdvancedInfoPanel")
            || id.equals("IC2NCInfoPanelExtender")
            || id.equals("IC2NCAdvancedInfoPanelExtender");
        if (nc && java.util.Arrays.asList("coreX", "coreY", "coreZ", "partOfScreen", "screenData")
            .contains(key)) return "relation";
        if (nc && "Items".equals(key)) return "asset";
        if ((id.equals("TileJar") || id.equals("TileJarVoid")) && ("Aspect".equals(key) || "Amount".equals(key)))
            return "asset";
        if ((id.equals("TileAlchemyFurnace") || id.equals("TileAlchemyFurnaceAdvanced") || id.equals("TileTubeBuffer"))
            && "Aspects".equals(key)) return "asset";
        if (id.equals("TileSiphon") && "amount".equals(key)) return "asset";
        if (cell.block() != null && cell.block() == gregtech.api.GregTechAPI.sBlockMachines
            && (java.util.Arrays.asList("mInventory", "Inventory", "inventory", "Items", "mFluid", "mStoredEnergy")
                .contains(key) || key.matches("mFluid[0-9]+")))
            return "asset";
        return "opaque";
    }

    private static String type(NBTBase tag) {
        String[] types = { "end", "byte", "short", "int", "long", "float", "double", "byte_array", "string", "list",
            "compound", "int_array" };
        return tag.getId() >= 0 && tag.getId() < types.length ? types[tag.getId()] : "unknown";
    }

    private static String scalar(NBTBase tag) {
        if (tag instanceof NBTTagString) return ((NBTTagString) tag).func_150285_a_();
        if (tag instanceof NBTBase.NBTPrimitive) {
            NBTBase.NBTPrimitive number = (NBTBase.NBTPrimitive) tag;
            if (tag.getId() == 5 || tag.getId() == 6) return Double.toString(number.func_150286_g());
            return Long.toString(number.func_150291_c());
        }
        return tag.toString();
    }

    private static NBTBase parseValue(String type, String value) throws IOException {
        try {
            switch (type) {
                case "byte":
                    return new NBTTagByte(Byte.parseByte(value));
                case "short":
                    return new NBTTagShort(Short.parseShort(value));
                case "int":
                    return new NBTTagInt(Integer.parseInt(value));
                case "long":
                    return new NBTTagLong(Long.parseLong(value));
                case "float": {
                    float parsed = Float.parseFloat(value);
                    if (!Float.isFinite(parsed)) throw new NumberFormatException();
                    return new NBTTagFloat(parsed);
                }
                case "double": {
                    double parsed = Double.parseDouble(value);
                    if (!Double.isFinite(parsed)) throw new NumberFormatException();
                    return new NBTTagDouble(parsed);
                }
                case "string":
                    return new NBTTagString(value);
                default:
                    throw new IOException("该NBT类型不能作为配置值编辑");
            }
        } catch (NumberFormatException invalid) {
            throw new IOException("NBT值不符合原类型或超出范围", invalid);
        }
    }

    private static void removePath(NBTTagCompound root, String path, net.minecraft.block.Block block)
        throws IOException {
        String[] segments = path.substring(1)
            .split("/", -1);
        NBTTagCompound parent = root;
        for (int i = 0; i < segments.length; i++) {
            String key = unescape(segments[i]);
            if (i == 0 && HologramBlueprintNbt.protectedKey(block, root, key)) return;
            if (i == segments.length - 1) parent.removeTag(key);
            else {
                if (!parent.hasKey(key, 10)) return;
                parent = parent.getCompoundTag(key);
            }
        }
    }

    private static void collect(String encoded, String kind, Set<String> keys, Set<String> paths) throws IOException {
        NBTTagCompound tag = HologramBlueprintNbt.decode(encoded);
        if (tag == null) return;
        for (Object raw : tag.func_150296_c()) {
            String key = (String) raw;
            if (!HologramBlueprintNbt.protectedKey(tag, key)) keys.add(key);
        }
        collectPaths(tag, kind, paths, 0);
    }

    private static void collectPaths(NBTTagCompound tag, String prefix, Set<String> paths, int depth) {
        if (depth > 16 || paths.size() >= 4096) return;
        for (Object raw : tag.func_150296_c()) {
            String key = (String) raw;
            if (depth == 0 && HologramBlueprintNbt.protectedKey(tag, key)) continue;
            String path = prefix + "/"
                + key.replace("~", "~0")
                    .replace("/", "~1");
            paths.add(path);
            NBTBase value = tag.getTag(key);
            if (value instanceof NBTTagCompound) collectPaths((NBTTagCompound) value, path, paths, depth + 1);
            if (paths.size() >= 4096) return;
        }
    }

    private static void clearQuantum(HologramPresets.Preset preset) throws IOException {
        // Removing every logical slot also retires the review of that exact named cell map.
        // The completed processing receipt still authorizes explicit unbound placement; choosing a
        // binding policy in a later author pass requires confirming a new classification for this blueprint.
        preset.quantumGroupingNbt = null;
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null || !tag.hasKey(HologramNetworkBindings.GROUP)) continue;
            tag.removeTag(HologramNetworkBindings.GROUP);
            tag.removeTag(HologramNetworkBindings.ROLE);
            tag.removeTag(HologramNetworkBindings.HINT);
            tag.removeTag(HologramNetworkBindings.CAPTURE_ANCHOR);
            tag.removeTag(HologramNetworkBindings.ORIGINAL);
            tag.removeTag(HologramNetworkBindings.MODE);
            if (HologramNetworkBindings.node(cell.block())) {
                for (String key : new String[] { "anchorDim", "anchorX", "anchorY", "anchorZ", "proxy", "linked" })
                    tag.removeTag(key);
            }
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        }
    }

    private static NBTTagCompound addHints(HologramPresets.Preset source, HologramPresets.Preset preset,
        List<HologramCoordinatePolicies.Relation> relations, Policy policy) throws IOException {
        Set<String> blocks = new LinkedHashSet<>();
        NBTTagCompound generated = new NBTTagCompound();
        if ("none".equals(policy.autoHints)) {
            generated.setTag("blocks", strings(new ArrayList<>()));
            return generated;
        }
        boolean full = "full".equals(policy.autoHints);
        if ("full".equals(policy.autoHints)) {
            String hint = "作者处理：去标识等级 " + policy.deidentificationLevel
                + "；量子关系"
                + ("clear".equals(policy.quantum) ? "清除后重新配置"
                    : "preserve".equals(policy.quantum) ? "保持原存档的原网络绑定" : "放置时用自己的终端重新绑定")
                + "；未明确忽略的未适配数据保留原样";
            preset.prompt = appendGenerated(preset.prompt, java.util.Collections.singletonList(hint), blocks);
        }
        Map<String, List<String>> cellHints = new LinkedHashMap<>();
        List<String> previousBlocks = generatedBlocks(source);
        for (int index = 0; index < preset.cells.size(); index++) {
            HologramPresets.Cell cell = preset.cells.get(index), original = source.cells.get(index);
            List<String> hints = new ArrayList<>();
            cellHints.put(position(cell.x, cell.y, cell.z), hints);
            NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
            NBTTagCompound before = HologramBlueprintNbt.decode(original.tileNbt);
            Map<Integer, List<String>> changes = new java.util.TreeMap<>();
            mergeFields(changes, HologramDeidentification.changedFields(original, before, payload, "tile:"));
            mergeFields(
                changes,
                HologramDeidentification.changedFields(
                    original,
                    HologramBlueprintNbt.decode(original.itemNbt),
                    HologramBlueprintNbt.decode(cell.itemNbt),
                    "item:"));
            for (Map.Entry<Integer, List<String>> entry : changes.entrySet())
                if (full) hints.add(fieldHint(entry.getKey(), entry.getValue()));
                else if (entry.getKey() == 1) hints.add("设备所有者标识已清空；放置时按当前玩家身份重新建立，确认当前账户和权限");
                else if (entry.getKey() >= 3 && entry.getKey() <= 6)
                    hints.add("设备的网络、安全或显示屏连接设置已清除；放置后按已确认的关系策略重新连接并检查权限");
                else if (entry.getKey() == 2 && entry.getValue()
                    .stream()
                    .anyMatch(path -> !path.matches(".*/[xyz]"))) hints.add("设备的外部锚定设置已清除；放置后重新配置目标，根位置会使用新的放置坐标");
            // Persisted sources cannot recover removed identities. Keep their verified descriptions without raw values.
            for (String oldBlock : previousBlocks) if (original.prompt != null && original.prompt.contains(oldBlock)) {
                for (String line : oldBlock.split("\n")) {
                    for (int group = 1; group <= 10; group++)
                        if (line.startsWith(fieldPrefix(group)) && HologramDeidentification.level(source) >= group
                            && !changes.containsKey(group)) {
                                if (full && !hints.contains(line)) hints.add(line);
                                else if (!full && group == 1 && !changes.containsKey(1))
                                    hints.add("设备所有者标识已清空；放置时按当前玩家身份重新建立，确认当前账户和权限");
                            }
                }
            }
            Map<Integer, List<String>> retained = HologramDeidentification.retainedRiskFields(cell, payload, "tile:");
            mergeFields(
                retained,
                HologramDeidentification.retainedRiskFields(cell, HologramBlueprintNbt.decode(cell.itemNbt), "item:"));
            for (Map.Entry<Integer, List<String>> entry : retained.entrySet()) {
                String fields = full ? "（" + String.join("、", entry.getValue()) + "）" : "";
                if (entry.getKey() == 1) hints.add("模板保留原所有者字段" + fields + "；放置前确认当前玩家有权使用该设备");
                else if (entry.getKey() >= 2 && entry.getKey() <= 6)
                    hints.add("模板保留原锚定、网络或安全设置" + fields + "；移动蓝图不会自动修复这些外部设置，放置后检查目标与权限");
            }
            if (payload != null && payload.hasKey(HologramNetworkBindings.GROUP)) hints.add(
                "量子网络组 " + payload.getString(HologramNetworkBindings.GROUP)
                    + ("preserve".equals(policy.quantum) ? "：保持原存档的原量子网络绑定；原网络不可用或无权限时无法施工"
                        : "：放置前选择当前玩家自己的有效终端，按该终端重新绑定"));
            else if ("clear".equals(policy.quantum) && (HologramNetworkBindings.node(cell.block())
                || before != null && (before.hasKey(HologramNetworkBindings.GROUP)
                    || before.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR))))
                hints.add("量子网络绑定已清除；以未绑定状态放置，需要使用自己的有效终端重新连接");
        }
        for (HologramCoordinatePolicies.Relation relation : relations) {
            String mode = HologramCoordinatePolicies.mode(relation, policy);
            if (!full && "relative".equals(mode) && relation.internal) continue;
            List<String> hints = cellHints.get(position(relation.x, relation.y, relation.z));
            if (hints == null) continue;
            hints.add(
                relation.label + ("clear".equals(mode)
                    ? "：连接已清除，放置后重新设置" + ("nc.sensor".equals(relation.adapter) ? "；传感卡的绑定、监测缓存和标题也会重置" : "")
                    : "relative".equals(mode)
                        ? "：随蓝图平移和旋转校准，使用放置所在维度" + (relation.internal ? "，指向本蓝图实际选中的方块" : "；目标在蓝图外，放置后确认对应位置仍有有效设备")
                        : "：仍锚定原绝对坐标；移动和旋转蓝图不会改变此关系，请检查原目标在放置所在维度是否可用"));
        }
        for (HologramPresets.Cell cell : preset.cells)
            cell.prompt = appendGenerated(cell.prompt, cellHints.get(position(cell.x, cell.y, cell.z)), blocks);
        generated.setTag("blocks", strings(new ArrayList<>(blocks)));
        return generated;
    }

    private static void mergeFields(Map<Integer, List<String>> target, Map<Integer, List<String>> incoming) {
        for (Map.Entry<Integer, List<String>> entry : incoming.entrySet()) {
            Set<String> paths = new java.util.TreeSet<>(
                target.getOrDefault(entry.getKey(), java.util.Collections.emptyList()));
            paths.addAll(entry.getValue());
            target.put(entry.getKey(), new ArrayList<>(paths));
        }
    }

    private static String fieldPrefix(int group) {
        String[] prefixes = { "", "已清除设备所有者标识", "已处理位置与锚定字段", "已移除频道与网络标识", "已移除安全身份", "已移除外部连接", "已处理显示屏连接字段",
            "已移除监测缓存", "已移除显示页设置", "已移除停机记录", "已移除运行状态与采样字段" };
        return prefixes[group];
    }

    private static String fieldHint(int group, List<String> fields) {
        String[] guidance = { "", "放置时按当前玩家身份重建空身份", "根位置使用目标坐标，外部锚定需重新核验", "放置后重新选择网络或频道", "放置后重新授权", "放置后重新连接设备",
            "按已确认坐标策略重建；已清除的连接需重新设置", "放置后重新采集有效目标的数据", "放置后重新选择显示页", "原停机原因不再保留，请重新检查运行条件", "放置后由真实设备重新产生运行状态" };
        return fieldPrefix(group) + "（" + String.join("、", fields) + "）；" + guidance[group];
    }

    private static String appendGenerated(String original, List<String> lines, Set<String> generated)
        throws IOException {
        if (lines == null || lines.isEmpty()) return original;
        Set<String> unique = new LinkedHashSet<>();
        for (String line : lines) if (original == null || !original.contains(line)) unique.add(line);
        if (unique.isEmpty()) return original;
        String block = "自动说明：\n" + String.join("\n", unique);
        String result = original == null || original.isEmpty() ? block : original + "\n" + block;
        if (result.length() > 4096) throw new IOException("自动说明与原有提示合计超过4096字，请缩短原提示或调整自动提示模式");
        generated.add(block);
        return result;
    }

    private static List<String> generatedBlocks(HologramPresets.Preset preset) throws IOException {
        NBTTagCompound receipt = HologramBlueprintNbt.decode(preset.processingNbt);
        if (receipt == null || !receipt.hasKey("generatedHints", 10)) return java.util.Collections.emptyList();
        NBTTagCompound hints = receipt.getCompoundTag("generatedHints");
        NBTBase raw = hints.getTag("blocks");
        if (!(raw instanceof NBTTagList)) throw new IOException("自动提示记录格式无效");
        NBTTagList list = (NBTTagList) raw;
        if (list.tagCount() > 65536 || list.tagCount() > 0 && list.func_150303_d() != 8)
            throw new IOException("自动提示记录类型或数量无效");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.tagCount(); i++) {
            String block = list.getStringTagAt(i);
            if (!block.startsWith("自动说明：\n") || block.length() > 4096) throw new IOException("自动提示记录文本无效");
            result.add(block);
        }
        result.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return result;
    }

    private static void removeGeneratedHints(HologramPresets.Preset preset) throws IOException {
        List<String> blocks = generatedBlocks(preset);
        preset.prompt = removeGenerated(preset.prompt, blocks);
        for (HologramPresets.Cell cell : preset.cells) cell.prompt = removeGenerated(cell.prompt, blocks);
    }

    private static String removeGenerated(String original, List<String> blocks) {
        if (original == null) return null;
        String result = original;
        for (String block : blocks) {
            int index = result.indexOf(block);
            while (index >= 0) {
                int end = index + block.length();
                if ((index == 0 || result.charAt(index - 1) == '\n')
                    && (end == result.length() || result.charAt(end) == '\n')) {
                    int start = index > 0 ? index - 1 : index;
                    if (start == 0 && end < result.length()) end++;
                    result = result.substring(0, start) + result.substring(end);
                    index = result.indexOf(block);
                } else index = result.indexOf(block, index + 1);
            }
        }
        return result;
    }

    static NBTTagList strings(List<String> values) {
        NBTTagList result = new NBTTagList();
        for (String value : values) result.appendTag(new NBTTagString(value));
        return result;
    }

    static List<String> readStrings(NBTTagCompound tag, String key, int limit, int length) throws IOException {
        if (!tag.hasKey(key)) return new ArrayList<>();
        NBTBase raw = tag.getTag(key);
        if (!(raw instanceof NBTTagList)) throw new IOException("处理流程列表格式无效：" + key);
        NBTTagList list = (NBTTagList) raw;
        if (list.tagCount() > limit || list.tagCount() > 0 && list.func_150303_d() != 8)
            throw new IOException("处理流程列表超出上限或类型无效：" + key);
        List<String> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < list.tagCount(); i++) {
            String value = text(list.getStringTagAt(i), length);
            if (!unique.add(value)) throw new IOException("处理流程列表存在重复项：" + key);
            result.add(value);
        }
        return result;
    }

    private static NBTTagList compounds(NBTTagCompound tag, String key, int limit) throws IOException {
        if (!tag.hasKey(key)) return new NBTTagList();
        NBTBase raw = tag.getTag(key);
        if (!(raw instanceof NBTTagList)) throw new IOException("处理流程列表格式无效：" + key);
        NBTTagList list = (NBTTagList) raw;
        if (list.tagCount() > limit || list.tagCount() > 0 && list.func_150303_d() != 10)
            throw new IOException("处理流程列表类型或数量无效：" + key);
        return list;
    }

    private static String choice(NBTTagCompound tag, String key, String... choices) throws IOException {
        if (!tag.hasKey(key, 8) || !Arrays.asList(choices)
            .contains(tag.getString(key))) throw new IOException("请明确选择处理选项：" + key);
        return tag.getString(key);
    }

    private static String text(String value, int limit) throws IOException {
        if (value == null || value.length() > limit
            || value.chars()
                .anyMatch(c -> c < 32 || c == 167))
            throw new IOException("处理流程文本无效或过长");
        return value;
    }

    private static void checkPath(String path) throws IOException {
        text(path, 1024);
        if (!(path.startsWith("tile:/") || path.startsWith("item:/")))
            throw new IOException("NBT路径须以 tile:/ 或 item:/ 开头");
        for (String key : path.substring(path.indexOf(':') + 2)
            .split("/", -1)) unescape(key);
    }

    private static String unescape(String key) throws IOException {
        if (key.matches(".*~(?![01]).*")) throw new IOException("NBT路径转义无效");
        return key.replace("~1", "/")
            .replace("~0", "~");
    }

    private static String position(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
