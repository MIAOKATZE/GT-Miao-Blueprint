package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.MetaPipeEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Server-owned native storage. Internal schema 1 templates and schema 2 blueprints share version metadata. */
final class HologramPresets {

    // The whole library has a separate budget; one large entry must not consume every other slot.
    static final int MAX_LIBRARY_BYTES = 64 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().create();
    private static List<Preset> cached;

    private HologramPresets() {}

    static Path libraryPath() {
        return HologramLibraryFacade.blueprintDirectory()
            .resolve("hologram-presets.json");
    }

    static synchronized void clearCache() {
        cached = null;
    }

    static final class Cell {

        String item;
        String defaultItem;
        String block, tileNbt, itemNbt, prompt, structureId;
        List<String> structureIds;
        List<Target> allowedTargets;
        int metadata;
        boolean excluded;
        int defaultDamage;
        int damage, count = 1, x, y, z, facing = -1;
        boolean controller;

        ItemStack stack() {
            Object found = item == null ? null : Item.itemRegistry.getObject(item);
            return found instanceof Item ? new ItemStack((Item) found, 1, damage) : null;
        }

        ItemStack defaultStack() {
            Object found = defaultItem == null ? null : Item.itemRegistry.getObject(defaultItem);
            return found instanceof Item ? new ItemStack((Item) found, 1, defaultDamage) : null;
        }

        Block block() {
            Object found = block == null ? null : Block.blockRegistry.getObject(block);
            return found instanceof Block && (found != Blocks.air || "minecraft:air".equals(block)) ? (Block) found
                : null;
        }

        boolean missing() {
            ItemStack resolved = stack();
            return (item != null && resolved == null) || (block != null && block() == null)
                || (resolved != null && resolved.getItem() instanceof ItemMachines
                    && (damage < 0 || damage >= GregTechAPI.METATILEENTITIES.length
                        || GregTechAPI.METATILEENTITIES[damage] == null));
        }

        List<String> memberships() {
            if (structureIds != null && !structureIds.isEmpty()) return new ArrayList<>(structureIds);
            if (structureId == null || structureId.isEmpty()) return new ArrayList<>();
            return new ArrayList<>(java.util.Collections.singletonList(structureId));
        }

        boolean belongsTo(String id) {
            if (id == null || id.isEmpty()) return false;
            return structureIds != null && !structureIds.isEmpty() ? structureIds.contains(id) : id.equals(structureId);
        }
    }

    static final class HintArea {

        int x1, y1, z1, x2, y2, z2;
        String prompt;
    }

    /** Per-position declared item identities, never the union of a region's palette. */
    static final class Target {

        String item;
        int damage;

        Target(ItemStack stack) {
            item = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
            damage = stack.getItemDamage();
        }

        ItemStack stack() {
            Object found = item == null ? null : Item.itemRegistry.getObject(item);
            return found instanceof Item ? new ItemStack((Item) found, 1, damage) : null;
        }
    }

    /** Captured provenance and relative outline anchor; live formation is always recomputed by the server. */
    static final class Structure {

        String id, name, sourceMod, sourceClass;
        int x, y, z, controllerId = -1;
        boolean enabled = true;
        int main = 1;
        boolean mainExplicit, noHatches;
        Map<String, Integer> channels = new LinkedHashMap<>();
    }

    static final class Preset {

        String id, name, controllerType;
        // Creator attribution is descriptive; public-library ownership is verified separately by the server.
        String authorName, authorUuid;
        long createdAt;
        HologramBlueprintVersions.Metadata nativeBlueprint;
        int schema = 1;
        int aePolicyVersion;
        int deidentificationVersion, deidentificationLevel;
        boolean deidentificationChosen;
        // Schema 2 extension: a bounded binary NBT receipt, never a recoverable copy of stripped identities.
        String processingNbt;
        List<HologramAePolicy.Issue> aeIssues = new ArrayList<>();
        boolean retainNbt = true, retainCovers = true;
        String prompt, sourceMod, sourceClass;
        Map<String, String> modVersions = new LinkedHashMap<>();
        List<HintArea> hintAreas = new ArrayList<>();
        List<Structure> structures = new ArrayList<>();
        // Absent in legacy files: null selects the complete captured NBT baseline.
        List<String> retainedNbtKeys;
        int controllerId = -1;
        // Optional in schema 1: old templates infer NORMAL/NONE from the controller cell's front.
        Integer controllerFacing;
        List<Cell> cells = new ArrayList<>();
        List<Cell> palette = new ArrayList<>();

        boolean allows(ItemStack stack) {
            if (stack == null) return false;
            String item = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
            for (Cell entry : palette)
                if (item.equals(entry.item) && entry.damage == stack.getItemDamage()) return true;
            return false;
        }
    }

    private static final class FileData {

        int schema = 2;
        List<Preset> presets = new ArrayList<>();
    }

    static Cell cell(ItemStack stack, int x, int y, int z, int facing, boolean controller) {
        Cell cell = new Cell();
        cell.item = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        cell.damage = stack.getItemDamage();
        cell.x = x;
        cell.y = y;
        cell.z = z;
        cell.facing = HologramBlockIdentity.front(facing);
        cell.controller = controller;
        return cell;
    }

    static synchronized List<Preset> all() throws IOException {
        if (cached != null) return copies(cached);
        cached = readLibrary(libraryPath());
        return copies(cached);
    }

    private static List<Preset> readLibrary(Path file) throws IOException {
        List<Preset> result = new ArrayList<>();
        if (Files.exists(file)) {
            if (Files.size(file) > MAX_LIBRARY_BYTES) throw new IOException("蓝图库超过64MiB上限");
            try {
                FileData data = decode(
                    new String(HologramBlueprintSharing.readBytes(file, MAX_LIBRARY_BYTES), StandardCharsets.UTF_8));
                if (data == null || (data.schema != 1 && data.schema != 2)
                    || data.presets == null
                    || data.presets.size() > 32) throw new IOException("模板配置格式或数量无效");
                Set<String> ids = new HashSet<>();
                for (Preset preset : data.presets) {
                    Preset loaded = preset != null && preset.schema == 1 ? upgradeLegacy(preset) : preset;
                    HologramBlueprintProcessing.markLegacy(loaded);
                    validate(loaded);
                    HologramBlueprintSharing.encodeBounded(GSON, loaded, HologramBlueprintSharing.MAX_BYTES);
                    if (!ids.add(loaded.id)) throw new IOException("模板ID重复");
                    result.add(loaded);
                }
            } catch (RuntimeException invalid) {
                throw new IOException("模板JSON无效", invalid);
            }
        }
        return result;
    }

    static Preset get(String id) throws IOException {
        for (Preset preset : all()) if (preset.id.equals(id))
            return HologramBlueprintProcessing.enforcePolicy(HologramAePolicy.sanitize(copy(preset)).preset);
        return null;
    }

    static synchronized void save(Preset preset) throws IOException {
        List<Preset> list = all();
        Preset saved = put(list, preset, false);
        validate(saved);
        write(libraryPath(), list);
        preset.id = saved.id;
        preset.nativeBlueprint = saved.nativeBlueprint;
    }

    static synchronized void update(Preset preset) throws IOException {
        List<Preset> list = all();
        Preset updated = put(list, preset, true);
        validate(updated);
        write(libraryPath(), list);
        preset.nativeBlueprint = updated.nativeBlueprint;
    }

    /** Prepare the final identity and canonical payload before a client captures its matching preview. */
    static synchronized Preset prepareSaved(Preset preset, boolean update) throws IOException {
        return copy(put(all(), preset, update));
    }

    /** Commit the reviewed bytes without generating another ID or replacing the prepared provenance. */
    static synchronized void commitSaved(Preset prepared, boolean update) throws IOException {
        HologramBlueprintVersions.validate(prepared);
        Preset result = copy(prepared);
        HologramAePolicy.Draft canonical = HologramAePolicy.sanitize(result);
        if (canonical.blocking)
            throw new IOException("AE unresolved issues cannot be persisted without an explicit repair");
        result = HologramBlueprintProcessing.enforcePolicy(canonical.preset);
        validate(result);
        byte[] reviewed = HologramBlueprintSharing.encodeBounded(GSON, prepared, HologramBlueprintSharing.MAX_BYTES);
        byte[] checked = HologramBlueprintSharing.encodeBounded(GSON, result, HologramBlueprintSharing.MAX_BYTES);
        if (!java.util.Arrays.equals(reviewed, checked)) throw new IOException(
            "Prepared blueprint changed during canonical validation: "
                + canonicalDifference(GSON.toJsonTree(prepared), GSON.toJsonTree(result), ""));
        List<Preset> list = all();
        int index = indexOf(list, result.id);
        if (update) {
            if (index < 0) throw new IOException("要更新的模板不存在");
            Preset original = list.get(index);
            if (!java.util.Objects.equals(result.authorName, original.authorName)
                || !java.util.Objects.equals(result.authorUuid, original.authorUuid)
                || result.createdAt != original.createdAt)
                throw new IOException("Prepared blueprint author metadata no longer matches the stored entry");
            list.set(index, result);
        } else {
            if (index >= 0) throw new IOException("模板ID重复");
            if (list.size() >= 32) throw new IOException("最多保存32个模板");
            list.add(result);
        }
        write(libraryPath(), list);
    }

    /** Rejection diagnostics expose a field path, never the player's saved device contents. */
    private static String canonicalDifference(JsonElement reviewed, JsonElement checked, String path)
        throws IOException {
        if (java.util.Objects.equals(reviewed, checked)) return path.isEmpty() ? "/ (JSON representation differs)" : "";
        if (reviewed != null && checked != null && reviewed.isJsonObject() && checked.isJsonObject()) {
            Set<String> fields = new java.util.TreeSet<>();
            for (Map.Entry<String, JsonElement> entry : reviewed.getAsJsonObject()
                .entrySet()) fields.add(entry.getKey());
            for (Map.Entry<String, JsonElement> entry : checked.getAsJsonObject()
                .entrySet()) fields.add(entry.getKey());
            for (String field : fields) {
                String difference = canonicalDifference(
                    reviewed.getAsJsonObject()
                        .get(field),
                    checked.getAsJsonObject()
                        .get(field),
                    path + "/" + field);
                if (!difference.isEmpty()) return difference;
            }
        } else if (reviewed != null && checked != null && reviewed.isJsonArray() && checked.isJsonArray()) {
            int count = Math.min(
                reviewed.getAsJsonArray()
                    .size(),
                checked.getAsJsonArray()
                    .size());
            for (int i = 0; i < count; i++) {
                String difference = canonicalDifference(
                    reviewed.getAsJsonArray()
                        .get(i),
                    checked.getAsJsonArray()
                        .get(i),
                    path + "/" + i);
                if (!difference.isEmpty()) return difference;
            }
            return path + "/length";
        } else if ((path.endsWith("/tileNbt") || path.endsWith("/itemNbt") || path.endsWith("/processingNbt"))
            && reviewed != null
            && checked != null
            && reviewed.isJsonPrimitive()
            && checked.isJsonPrimitive()) {
                boolean same = java.util.Objects.equals(
                    HologramBlueprintNbt.decode(reviewed.getAsString()),
                    HologramBlueprintNbt.decode(checked.getAsString()));
                return path + (same ? " (decoded NBT equal)" : " (decoded NBT differs)");
            }
        return path.isEmpty() ? "/" : path;
    }

    static synchronized void delete(String id) throws IOException {
        delete(id, HologramBlueprintArtifact::deletePair);
    }

    interface ArtifactDeleteOperation {

        void delete(Path file) throws IOException;
    }

    /** Keep the logical collection and both native artifact members together on failed deletion. */
    static synchronized void delete(String id, ArtifactDeleteOperation removeArtifact) throws IOException {
        List<Preset> list = all();
        remove(list, id);
        if (id == null || !id.matches("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"))
            throw new IOException("蓝图必须具有当前原生保存格式的完整 JSON 和 PNG 文件");
        Path library = libraryPath();
        Path artifact = library.getParent()
            .resolve("local_" + id + ".json");
        Preset artifactBlueprint = HologramBlueprintSharing.read(artifact);
        if (!id.equals(artifactBlueprint.id)) throw new IOException("蓝图文件身份与集合条目不一致");
        byte[] previousBytes = HologramBlueprintSharing.readBytes(library, MAX_LIBRARY_BYTES);
        List<Preset> previousCache = cached;
        write(library, list);
        try {
            removeArtifact.delete(artifact);
        } catch (IOException | RuntimeException failure) {
            try {
                restoreLibraryBytes(library, previousBytes);
                cached = previousCache;
            } catch (IOException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    private static void restoreLibraryBytes(Path file, byte[] bytes) throws IOException {
        if (bytes.length > MAX_LIBRARY_BYTES) throw new IOException("蓝图库超过64MiB上限");
        Path temp = Files.createTempFile(file.getParent(), "hologram-presets-restore-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void remove(List<Preset> list, String id) throws IOException {
        int index = indexOf(list, id);
        if (index < 0) throw new IOException("要删除的蓝图不存在");
        list.remove(index);
    }

    private static Preset put(List<Preset> list, Preset preset, boolean update) throws IOException {
        if (preset == null) throw new IOException("模板无效");
        // A selected NBT subset can shrink a captured draft before the per-entry write budget is applied.
        Preset result = GSON.fromJson(
            new String(HologramBlueprintSharing.encodeBounded(GSON, preset, MAX_LIBRARY_BYTES), StandardCharsets.UTF_8),
            Preset.class);
        // Defensive canonical boundary for non-UI callers; the interactive service has already reviewed this result.
        HologramAePolicy.Draft canonical = HologramAePolicy.sanitize(result);
        if (canonical.blocking)
            throw new IOException("AE unresolved issues cannot be persisted without an explicit repair");
        result = canonical.preset;
        result = HologramBlueprintProcessing.enforcePolicy(result);
        HologramBlueprintVersions.stampSaved(result);
        validate(result);
        if (update) {
            int index = indexOf(list, result.id);
            if (index < 0) throw new IOException("要更新的模板不存在");
            Preset original = list.get(index);
            result.authorName = original.authorName;
            result.authorUuid = original.authorUuid;
            result.createdAt = original.createdAt;
            list.set(index, result);
        } else {
            if (list.size() >= 32) throw new IOException("最多保存32个模板");
            result.id = UUID.randomUUID()
                .toString();
            list.add(result);
        }
        return result;
    }

    private static int indexOf(List<Preset> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i;
        return -1;
    }

    static Preset copy(Preset preset) {
        return GSON.fromJson(GSON.toJson(preset), Preset.class);
    }

    /** Runtime-only migration: registry availability never determines whether a legacy template can be inspected. */
    static Preset upgradeLegacy(Preset preset) throws IOException {
        if (preset == null || preset.schema != 1
            || preset.controllerType == null
            || preset.controllerType.length() > 256
            || preset.controllerId < 0
            || preset.cells == null
            || preset.cells.isEmpty()
            || preset.cells.size() > HologramCapture.LIMIT
            || preset.palette == null
            || preset.palette.isEmpty()
            || preset.palette.size() > 4096) throw new IOException("旧模板格式无效");
        Preset upgraded = copy(preset);
        Set<String> allowed = new HashSet<>();
        for (Cell cell : upgraded.palette) {
            legacyCellFormat(cell);
            allowed.add(cell.item + ":" + cell.damage);
        }
        int controllers = 0;
        Set<String> positions = new HashSet<>();
        for (Cell cell : upgraded.cells) {
            legacyCellFormat(cell);
            if (!positions.add(cell.x + "," + cell.y + "," + cell.z) || !allowed.contains(cell.item + ":" + cell.damage)
                || cell.defaultItem != null && !allowed.contains(cell.defaultItem + ":" + cell.defaultDamage))
                throw new IOException("旧模板位置重复或目标不在候选表中");
            if (cell.controller) {
                controllers++;
                if (cell.x != 0 || cell.y != 0
                    || cell.z != 0
                    || cell.damage != upgraded.controllerId
                    || !upgraded.controllerType.endsWith(":" + upgraded.controllerId))
                    throw new IOException("旧模板控制器锚点或声明身份无效");
                if (upgraded.controllerFacing != null
                    && (upgraded.controllerFacing < 0 || upgraded.controllerFacing >= ExtendedFacing.values().length
                        || ExtendedFacing.byIndex(upgraded.controllerFacing)
                            .getDirection()
                            .ordinal() != cell.facing))
                    throw new IOException("旧模板控制器姿态与正面不一致");
                ItemStack stack = cell.stack();
                if (stack != null && !(stack.getItem() instanceof ItemMachines)) throw new IOException("旧模板控制器物品身份无效");
                if (stack != null && cell.damage < GregTechAPI.METATILEENTITIES.length
                    && GregTechAPI.METATILEENTITIES[cell.damage] != null
                    && (!(GregTechAPI.METATILEENTITIES[cell.damage] instanceof MTEEnhancedMultiBlockBase)
                        || !upgraded.controllerType.equals(
                            GregTechAPI.METATILEENTITIES[cell.damage].getClass()
                                .getName() + ":" + cell.damage)
                        || upgraded.controllerFacing != null
                            && !((MTEEnhancedMultiBlockBase<?>) GregTechAPI.METATILEENTITIES[cell.damage])
                                .getAlignmentLimits()
                                .isNewExtendedFacingValid(ExtendedFacing.byIndex(upgraded.controllerFacing))))
                    throw new IOException("旧模板控制器声明身份与已注册机器不一致");
            }
            legacyBlockIdentity(cell);
        }
        if (controllers != 1) throw new IOException("旧模板必须包含一个控制器");
        for (Cell cell : upgraded.palette) legacyBlockIdentity(cell);
        upgraded.schema = 2;
        upgraded.retainedNbtKeys = null;
        upgraded.sourceClass = upgraded.controllerType.substring(0, upgraded.controllerType.lastIndexOf(':'));
        for (Cell cell : upgraded.cells) legacyDependency(upgraded, cell.item);
        for (Cell cell : upgraded.palette) legacyDependency(upgraded, cell.item);
        return upgraded;
    }

    private static void legacyDependency(Preset preset, String registry) {
        int separator = registry.indexOf(':');
        if (separator > 0 && !preset.modVersions.containsKey(registry.substring(0, separator)))
            preset.modVersions.put(registry.substring(0, separator), "unknown");
    }

    private static void legacyCellFormat(Cell cell) throws IOException {
        if (cell == null || cell.item == null
            || cell.count != 1
            || cell.damage < 0
            || cell.defaultDamage < 0
            || cell.facing < -1
            || cell.facing > 5
            || cell.tileNbt != null
            || cell.itemNbt != null) throw new IOException("旧模板物品、数量、朝向或设备NBT无效");
        registryText(cell.item, false);
        registryText(cell.defaultItem, true);
        coordinates(cell.x, cell.y, cell.z);
        ItemStack stack = cell.stack();
        if (stack != null && (!(stack.getItem() instanceof ItemMachines)
            || cell.damage < GregTechAPI.METATILEENTITIES.length && GregTechAPI.METATILEENTITIES[cell.damage] != null))
            validateCell(cell);
    }

    private static void legacyBlockIdentity(Cell cell) {
        cell.block = null;
        cell.metadata = 0;
        ItemStack stack = cell.stack();
        if (stack == null) return;
        Block block = Block.getBlockFromItem(stack.getItem());
        if (block == null || block == Blocks.air) return;
        cell.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
        cell.metadata = stack.getItem() instanceof ItemMachines ? 0
            : stack.getItem()
                .getMetadata(cell.damage);
    }

    private static List<Preset> copies(List<Preset> list) {
        List<Preset> result = new ArrayList<>();
        for (Preset preset : list) result.add(copy(preset));
        return result;
    }

    static void exportBlueprint(String fileId, Preset preset) throws IOException {
        HologramBlueprintSharing.exportFile(fileId, preset);
    }

    static Preset importBlueprint(String fileId) throws IOException {
        return HologramBlueprintSharing.importFile(fileId);
    }

    private static void write(Path file, List<Preset> list) throws IOException {
        FileData data = new FileData();
        data.presets = copies(list);
        for (Preset preset : data.presets) {
            HologramBlueprintVersions.stamp(preset);
            HologramBlueprintSharing.encodeBounded(GSON, preset, HologramBlueprintSharing.MAX_BYTES);
        }
        byte[] bytes = HologramBlueprintSharing.encodeBounded(GSON, data, MAX_LIBRARY_BYTES);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "hologram-presets-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            cached = copies(data.presets);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static synchronized NBTTagList labels() throws IOException {
        if (cached == null) all();
        NBTTagList result = new NBTTagList();
        // State packets need only immutable labels/counts, never detached copies of the device payloads.
        for (Preset preset : cached) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", preset.id);
            row.setString("name", preset.name);
            row.setString("authorName", preset.authorName == null ? "" : preset.authorName);
            row.setString("authorUuid", preset.authorUuid == null ? "" : preset.authorUuid);
            row.setLong("createdAt", preset.createdAt);
            row.setString("sourceMod", preset.sourceMod == null ? "" : preset.sourceMod);
            row.setString("sourceClass", preset.sourceClass == null ? "" : preset.sourceClass);
            row.setInteger("structures", preset.structures.size());
            row.setInteger("cells", preset.cells.size());
            row.setInteger("schema", preset.schema);
            result.appendTag(row);
        }
        return result;
    }

    private static FileData decode(String json) throws IOException {
        HologramBlueprintSharing.boundedJson(json, MAX_LIBRARY_BYTES);
        JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonObject() || !root.getAsJsonObject()
            .has("schema")
            || !root.getAsJsonObject()
                .get("schema")
                .isJsonPrimitive()
            || !root.getAsJsonObject()
                .get("schema")
                .getAsJsonPrimitive()
                .isNumber()
            || !root.getAsJsonObject()
                .get("schema")
                .getAsString()
                .matches("[12]"))
            throw new IOException("模板schema无效");
        if (root.isJsonObject() && root.getAsJsonObject()
            .has("presets")) {
            JsonElement presets = root.getAsJsonObject()
                .get("presets");
            if (presets.isJsonArray()) for (JsonElement entry : presets.getAsJsonArray()) {
                HologramBlueprintVersions.validateJson(entry);
                HologramBlueprintSharing.validatePresetSchema(entry);
                if (!entry.isJsonObject() || !entry.getAsJsonObject()
                    .has("controllerFacing")) continue;
                JsonElement facing = entry.getAsJsonObject()
                    .get("controllerFacing");
                if (!facing.isJsonNull() && (!facing.isJsonPrimitive() || !facing.getAsJsonPrimitive()
                    .isNumber()
                    || !facing.getAsString()
                        .matches("[0-9]+")))
                    throw new IOException("模板控制器姿态必须是合法整数，不接受字符串或小数");
                if (!facing.isJsonNull() && new java.math.BigInteger(facing.getAsString())
                    .compareTo(java.math.BigInteger.valueOf(ExtendedFacing.values().length)) >= 0)
                    throw new IOException("模板控制器姿态超出合法范围");
            }
        }
        return GSON.fromJson(root, FileData.class);
    }

    static void validate(Preset preset) throws IOException {
        HologramBlueprintVersions.validateIfPresent(preset);
        if (preset != null) {
            HologramBlueprintProcessing.validateReceipt(preset);
            HologramDeidentification.checked(preset.deidentificationLevel);
            if (preset.deidentificationVersion < 0 || preset.deidentificationVersion > HologramDeidentification.VERSION)
                throw new IOException("去标识版本无效");
            if (preset.aePolicyVersion < 0 || preset.aePolicyVersion > HologramAePolicy.VERSION)
                throw new IOException("Unsupported AE policy version");
            HologramAePolicy.validateIssues(preset.aeIssues);
        }
        if (preset != null && preset.schema == 2) {
            validateBlueprint(preset);
            return;
        }
        if (preset != null && preset.schema != 1) throw new IOException("模板schema无效");
        if (preset == null || preset.id == null
            || !preset.id.matches("[a-zA-Z0-9_-]{1,64}")
            || preset.name == null
            || preset.name.trim()
                .isEmpty()
            || preset.name.length() > 48
            || preset.controllerType == null
            || preset.controllerType.length() > 256
            || preset.cells == null
            || preset.cells.isEmpty()
            || preset.cells.size() > HologramCapture.LIMIT
            || preset.palette == null
            || preset.palette.isEmpty()
            || preset.palette.size() > 4096) throw new IOException("模板名称、位置或候选数量无效");
        if (preset.controllerFacing != null
            && (preset.controllerFacing < 0 || preset.controllerFacing >= ExtendedFacing.values().length))
            throw new IOException("模板控制器姿态无效");
        Set<String> positions = new HashSet<>();
        int controllers = 0;
        for (Cell cell : preset.palette) validateCell(cell);
        for (Cell cell : preset.cells) {
            validateCell(cell);
            if (!positions.add(cell.x + "," + cell.y + "," + cell.z) || !preset.allows(cell.stack()))
                throw new IOException("模板位置重复或目标不在候选表中");
            if (cell.defaultItem != null && (cell.defaultStack() == null || !preset.allows(cell.defaultStack())))
                throw new IOException("模板默认外壳不在候选表中");
            if (cell.controller && (cell.x != 0 || cell.y != 0 || cell.z != 0 || cell.damage != preset.controllerId))
                throw new IOException("模板控制器锚点无效");
            if (cell.controller && preset.controllerFacing != null) {
                ExtendedFacing facing = ExtendedFacing.byIndex(preset.controllerFacing);
                if (facing.getDirection()
                    .ordinal() != cell.facing || preset.controllerId < 0
                    || preset.controllerId >= GregTechAPI.METATILEENTITIES.length
                    || !(GregTechAPI.METATILEENTITIES[preset.controllerId] instanceof MTEEnhancedMultiBlockBase)
                    || !((MTEEnhancedMultiBlockBase<?>) GregTechAPI.METATILEENTITIES[preset.controllerId])
                        .getAlignmentLimits()
                        .isNewExtendedFacingValid(facing))
                    throw new IOException("模板控制器姿态与正面不一致或机器不允许");
            }
            if (cell.controller) controllers++;
            if (cell.controller && (!(cell.stack()
                .getItem() instanceof ItemMachines) || cell.damage < 0
                || cell.damage >= GregTechAPI.METATILEENTITIES.length
                || !(GregTechAPI.METATILEENTITIES[cell.damage] instanceof MTEEnhancedMultiBlockBase)
                || !preset.controllerType.equals(
                    GregTechAPI.METATILEENTITIES[cell.damage].getClass()
                        .getName() + ":" + cell.damage)))
                throw new IOException("模板控制器物品身份无效");
        }
        if (controllers != 1) throw new IOException("模板必须包含一个控制器");
    }

    private static void validateBlueprint(Preset preset) throws IOException {
        if (preset.id == null || !preset.id.matches("[a-zA-Z0-9_-]{1,64}")
            || preset.name == null
            || preset.name.trim()
                .isEmpty()
            || preset.name.length() > 48
            || preset.cells == null
            || preset.cells.isEmpty()
            || preset.cells.size() > HologramCapture.LIMIT
            || preset.palette == null
            || preset.palette.size() > 4096
            || preset.hintAreas == null
            || preset.hintAreas.size() > 256
            || preset.modVersions == null
            || preset.modVersions.size() > 512
            || preset.structures == null
            || preset.structures.size() > 256) throw new IOException("蓝图名称、方块或提示区域数量无效");
        if (preset.retainedNbtKeys != null) {
            if (preset.retainedNbtKeys.size() > 512) throw new IOException("蓝图NBT条目选择超出上限");
            Set<String> selected = new HashSet<>();
            for (String key : preset.retainedNbtKeys) {
                if (key == null || key.isEmpty()
                    || key.length() > 256
                    || !selected.add(key)
                    || key.chars()
                        .anyMatch(value -> value < 32))
                    throw new IOException("蓝图NBT条目选择无效");
            }
        }
        text(preset.prompt, 4096);
        payloadBudget(preset);
        text(preset.controllerType, 256);
        text(preset.sourceMod, 256);
        text(preset.sourceClass, 512);
        if (preset.controllerFacing != null
            && (preset.controllerFacing < 0 || preset.controllerFacing >= ExtendedFacing.values().length))
            throw new IOException("蓝图控制器姿态无效");
        for (Map.Entry<String, String> entry : preset.modVersions.entrySet()) {
            registryText(entry.getKey(), false);
            text(entry.getValue(), 256);
        }
        Set<String> positions = new HashSet<>();
        Set<String> structureIds = new HashSet<>();
        for (Structure structure : preset.structures) {
            if (structure == null || structure.id == null
                || !structure.id.matches("[a-zA-Z0-9_,:-]{1,64}")
                || !structureIds.add(structure.id)
                || structure.controllerId < -1) throw new IOException("蓝图结构标识无效或重复");
            text(structure.name, 256);
            text(structure.sourceMod, 256);
            text(structure.sourceClass, 512);
            if (structure.main < 1 || structure.channels == null || structure.channels.size() > 64)
                throw new IOException("蓝图结构通道或规模设置无效");
            for (Map.Entry<String, Integer> channel : structure.channels.entrySet()) {
                if (channel.getKey() == null || !channel.getKey()
                    .matches("[a-z0-9_.-]{1,48}") || channel.getValue() == null || channel.getValue() < 1)
                    throw new IOException("蓝图结构通道值无效");
            }
            coordinates(structure.x, structure.y, structure.z);
        }
        for (Cell cell : preset.cells) {
            validateBlueprintCell(cell);
            filterSelectedPayload(preset, cell);
            if (!positions.add(cell.x + "," + cell.y + "," + cell.z)) throw new IOException("蓝图位置重复");
            validateMembership(cell, structureIds);
        }
        for (Cell cell : preset.palette) {
            validateBlueprintCell(cell, false);
            validateMembership(cell, structureIds);
            filterSelectedPayload(preset, cell);
        }
        for (HintArea area : preset.hintAreas) {
            if (area == null || area.x1 > area.x2 || area.y1 > area.y2 || area.z1 > area.z2)
                throw new IOException("蓝图提示区域无效");
            coordinates(area.x1, area.y1, area.z1);
            coordinates(area.x2, area.y2, area.z2);
            text(area.prompt, 4096);
        }
    }

    private static void validateMembership(Cell cell, Set<String> structures) throws IOException {
        if (cell.structureId != null && !cell.structureId.isEmpty() && !structures.contains(cell.structureId))
            throw new IOException("蓝图方块引用不存在的结构");
        if (cell.structureIds != null) {
            if (cell.structureIds.size() > structures.size()) throw new IOException("蓝图方块结构归属数量无效");
            Set<String> unique = new HashSet<>();
            for (String id : cell.structureIds) {
                if (id == null || !structures.contains(id) || !unique.add(id)) throw new IOException("蓝图方块结构归属无效或重复");
            }
        }
    }

    private static void payloadBudget(Preset preset) throws IOException {
        Map<String, Integer> expanded = new java.util.HashMap<>();
        long total = 0;
        for (List<Cell> cells : java.util.Arrays.asList(preset.cells, preset.palette)) for (Cell cell : cells) {
            if (cell == null) throw new IOException("蓝图方块无效");
            for (String value : new String[] { cell.tileNbt, cell.itemNbt }) {
                if (value == null || value.isEmpty()) continue;
                Integer size = expanded.get(value);
                if (size == null) {
                    size = HologramBlueprintNbt.expandedBytes(value);
                    expanded.put(value, size);
                }
                total += size;
                if (total > 64L * 1024 * 1024) throw new IOException("蓝图设备NBT展开总量超过64MiB上限；请减少保存的NBT条目或拆分蓝图");
            }
        }
    }

    private static void filterSelectedPayload(Preset preset, Cell cell) throws IOException {
        HologramCoordinatePolicies.validatePaymentPayload(HologramBlueprintNbt.decode(cell.tileNbt));
        List<String> keys = preset.retainNbt ? preset.retainedNbtKeys : java.util.Collections.emptyList();
        cell.tileNbt = HologramAePolicy.handles(cell.block(), cell.tileNbt)
            ? HologramBlueprintNbt.filterCanonical(cell.block(), cell.tileNbt, keys, preset.retainCovers)
            : HologramBlueprintNbt.filterCaptured(cell.tileNbt, keys, preset.retainCovers);
        cell.itemNbt = HologramBlueprintNbt.filterCaptured(cell.itemNbt, keys, preset.retainCovers);
    }

    private static void validateBlueprintCell(Cell cell) throws IOException {
        validateBlueprintCell(cell, true);
    }

    private static void validateBlueprintCell(Cell cell, boolean placedCell) throws IOException {
        if (cell == null || (cell.item == null && cell.block == null)
            || cell.count != 1
            || cell.damage < 0
            || cell.metadata < 0
            || cell.metadata > 65535
            || cell.facing < -1
            || cell.facing > 5) throw new IOException("蓝图方块身份、数量或朝向无效");
        registryText(cell.item, true);
        registryText(cell.block, true);
        registryText(cell.defaultItem, true);
        validateMaterialIdentity(cell, placedCell);
        coordinates(cell.x, cell.y, cell.z);
        text(cell.prompt, 4096);
        if (cell.allowedTargets != null) {
            if (cell.allowedTargets.size() > 4096) throw new IOException("蓝图格候选数量超出上限");
            Set<String> unique = new HashSet<>();
            for (Target target : cell.allowedTargets) {
                if (target == null || target.damage < 0) throw new IOException("蓝图格候选无效");
                registryText(target.item, false);
                if (!unique.add(target.item + ":" + target.damage)) throw new IOException("蓝图格候选重复");
            }
        }
        // filterSelectedPayload validates binary NBT budgets and strips unselected entries before storage.
    }

    private static void validateMaterialIdentity(Cell cell, boolean placedCell) throws IOException {
        ItemStack material = cell.stack();
        if (material == null) return; // Missing registry entries remain inspectable; placement rejects them separately.
        Block actual = Block.getBlockFromItem(material.getItem());
        Block declared = cell.block();
        boolean gt = material.getItem() instanceof ItemMachines;
        if (gt && actual != GregTechAPI.sBlockMachines) throw new IOException("GT 材料没有对应的真实机器方块");
        if (material.getItem() instanceof ItemBlock && actual != null && actual != Blocks.air) {
            if (cell.block == null) cell.block = Block.blockRegistry.getNameForObject(actual);
            else if (!materialBlockAlias(actual, declared)) {
                // Composite adapters derive their complete materials from the actual reviewed native factory.
                if (gt || declared == null
                    || !HologramNativePlacement.supported(declared)
                    || !HologramNativePlacement
                        .matchesSample(declared, cell.metadata, HologramBlueprintNbt.decode(cell.tileNbt), material))
                    throw new IOException("蓝图方块与实际付款物品对应的方块不一致");
            }
        }
        if (declared != null && declared == GregTechAPI.sBlockMachines && !gt)
            throw new IOException("GT 机器必须使用对应的真实 GT 机器物品付款");
        if (!gt) {
            if (placedCell) validateMaterialVariant(cell.block(), cell.metadata, material);
            return;
        }
        int expected = material.getItemDamage();
        if (expected < 0 || expected >= GregTechAPI.METATILEENTITIES.length)
            throw new IOException("GT 机器材料 ID 超出原生注册范围");
        String tileId = GregTechAPI.METATILEENTITIES[expected] instanceof MetaPipeEntity ? "BaseMetaPipeEntity"
            : "BaseMetaTileEntity";
        // An absent add-on prototype can still be inspected; its canonical pipe header remains descriptive only.
        NBTTagCompound tile = HologramBlueprintNbt.decode(cell.tileNbt);
        if (GregTechAPI.METATILEENTITIES[expected] == null && tile != null
            && "BaseMetaPipeEntity".equals(tile.getString("id"))) tileId = "BaseMetaPipeEntity";
        validateGtPayload(tile, expected, tileId);
        NBTTagCompound item = HologramBlueprintNbt.decode(cell.itemNbt);
        if (item != null && (item.hasKey("mID") || item.hasKey("id"))) validateGtPayload(item, expected, tileId);
    }

    private static boolean materialBlockAlias(Block actual, Block declared) {
        return HologramBlockIdentity.sameForPlacement(actual, 0, declared, 0)
            || actual == Blocks.furnace && declared == Blocks.lit_furnace
            || actual == Blocks.lit_furnace && declared == Blocks.furnace
            || actual == Blocks.redstone_torch && declared == Blocks.unlit_redstone_torch
            || actual == Blocks.unlit_redstone_torch && declared == Blocks.redstone_torch;
    }

    /** Common-side native pick identity, including state/axis folding and Dirt's podzol exception. */
    static int materialDamage(Block block, int metadata) throws IOException {
        if (block == null || block == Blocks.air) throw new IOException("无法核验缺失方块的实际材料变种");
        try {
            Block identity = block == Blocks.lit_furnace ? Blocks.furnace
                : block == Blocks.unlit_redstone_torch ? Blocks.redstone_torch : block;
            ItemStack sample = HologramBlockIdentity.materialStack(identity, 0);
            if (sample != null && !sample.getItem()
                .getHasSubtypes()) return 0;
            HologramResourceWorld world = HologramResourceWorld.create(block, metadata);
            int damage = block.getDamageValue(world, 0, 64, 0);
            if (damage < 0) throw new IOException("原生方块返回了无效的实际材料变种");
            return damage;
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("无法使用原生服务端规则核验方块材料变种", failure);
        }
    }

    /** Ordinary saved states must consume their native physical variant; composite bills are validated separately. */
    static void validateMaterialVariant(Block block, int metadata, ItemStack paid) throws IOException {
        if (paid == null || paid.getItem() == null) throw new IOException("方块没有可核验的实际付款材料");
        if (paid.getItem() instanceof ItemMachines || HologramNativePlacement.supported(block)) return;
        if (!(paid.getItem() instanceof ItemBlock)) return; // Non-block native carriers have their own audited planner.
        Block material = Block.getBlockFromItem(paid.getItem());
        if (!materialBlockAlias(material, block)) throw new IOException("实际方块与付款物品的原生方块不一致");
        if (materialDamage(block, metadata) != paid.getItemDamage()) throw new IOException("实际方块材料变种与付款物品 damage 不一致");
    }

    /** Shared pure boundary: world metadata is not the machine material damage/MetaTile ID. */
    static void validateGtPayload(NBTTagCompound payload, int expectedDamage, String expectedTileId)
        throws IOException {
        if (payload == null) return; // A deliberately omitted payload installs the paid shell's native defaults.
        if (!payload.hasKey("mID", 99) || payload.getTag("mID")
            .getId() < 1
            || payload.getTag("mID")
                .getId() > 4
            || payload.getLong("mID") != expectedDamage) throw new IOException("GT 设备 NBT 的 mID 必须精确对应实际付款物品 damage");
        // Planned casing visuals contain mID/texture only; a supplied native root ID must be canonical.
        if (payload.hasKey("id") && (!payload.hasKey("id", 8) || !expectedTileId.equals(payload.getString("id"))))
            throw new IOException("GT 设备 NBT 的原生类型与实际付款机器不一致");
    }

    private static void coordinates(int x, int y, int z) throws IOException {
        if (Math.abs((long) x) > 60000000L || Math.abs((long) y) > 256L || Math.abs((long) z) > 60000000L)
            throw new IOException("蓝图坐标超出范围");
    }

    private static void registryText(String value, boolean optional) throws IOException {
        if (value == null && optional) return;
        if (value == null || !value.matches("[a-zA-Z0-9_.:/-]{1,256}")) throw new IOException("蓝图注册名无效");
    }

    private static void text(String value, int limit) throws IOException {
        if (value != null && value.length() > limit) throw new IOException("蓝图文本超出上限");
    }

    private static void validateCell(Cell cell) throws IOException {
        if (cell == null || cell.stack() == null
            || cell.tileNbt != null
            || cell.itemNbt != null
            || cell.damage < 0
            || cell.count != 1
            || Math.abs((long) cell.x) > 60000000L
            || Math.abs((long) cell.y) > 256L
            || Math.abs((long) cell.z) > 60000000L
            || cell.facing < -1
            || cell.facing > 5) throw new IOException("模板物品、坐标或朝向无效");
        ItemStack stack = cell.stack();
        if (stack.getItem() instanceof ItemMachines) {
            int id = stack.getItemDamage();
            if (id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] == null)
                throw new IOException("模板GT机器ID未注册");
        } else {
            Block block = Block.getBlockFromItem(stack.getItem());
            if (!(stack.getItem() instanceof ItemBlock) || block == Blocks.air
                || block.hasTileEntity(
                    stack.getItem()
                        .getMetadata(stack.getItemDamage())))
                throw new IOException("模板普通方块没有安全放置路径");
        }
    }
}
