package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.hologram.HologramLocalAuthorPolicies;

/** Narrow adapter for existing annotation/policy widgets; no session envelope, encoding or server ACK. */
final class HologramLocalAuthorActions {

    private HologramLocalAuthorActions() {}

    static boolean execute(HologramLocalAuthorRuntime runtime, HologramUiModules ui, String operation,
        NBTTagCompound request) {
        if (!handles(operation)) return false;
        try {
            if (request.hasKey("planRevision", 4) && request.getLong("planRevision") != runtime.document()
                .revision()) throw new IOException("文档已变化，请重新选择");
            if ("clearWorkspace".equals(operation) || "discardBlueprint".equals(operation)) {
                if ("discardBlueprint".equals(operation) || request.getBoolean("confirmDiscard") || !runtime.dirty())
                    HologramLocalAuthorRuntime.newWorkspace();
                else ui.library.confirmLocalSourceSwitch(HologramLocalAuthorRuntime::newWorkspace);
                return true;
            } else if ("addPosition".equals(operation)) {
                addPosition(runtime, request);
            } else if ("selectStructure".equals(operation)) {
                runtime.selectStructure(request.getString("structureId"));
            } else if ("editBlueprintPrompt".equals(operation)) {
                JsonObject patch = new JsonObject();
                patch.addProperty("prompt", prompt(request));
                runtime.apply(
                    runtime.editor()
                        .patchHeader(patch));
            } else if ("editCellFlags".equals(operation) || "editHintArea".equals(operation)) {
                Set<Long> ids = selected(runtime, ui, request);
                if (ids.isEmpty()) throw new IOException("请先选择蓝图格");
                Map<Long, JsonObject> patches = new LinkedHashMap<>();
                for (long id : ids) {
                    JsonObject patch = new JsonObject();
                    if (request.hasKey("excluded", 1)) patch.addProperty("excluded", request.getBoolean("excluded"));
                    if ("editCellFlags".equals(operation) && request.hasKey("prompt", 8))
                        patch.addProperty("prompt", prompt(request));
                    if (!patch.entrySet()
                        .isEmpty()) patches.put(id, patch);
                }
                JsonObject header = new JsonObject();
                if ("editHintArea".equals(operation)) header = hint(runtime, ids, prompt(request));
                runtime.editor()
                    .select(ids);
                runtime.apply(
                    runtime.editor()
                        .patchDocument(header, patches));
            } else if ("structureState".equals(operation)) {
                JsonObject patch = new JsonObject();
                patch.addProperty("enabled", request.getBoolean("enabled"));
                runtime.apply(
                    runtime.editor()
                        .patchStructure(request.getString("structureId"), patch));
            } else if ("configureBlueprintNbt".equals(operation)) {
                JsonObject patch = new JsonObject();
                if (request.hasKey("all", 1)) {
                    if (request.getBoolean("all")) patch.add("retainedNbtKeys", JsonNull.INSTANCE);
                    else {
                        NBTTagList keys = request.getTagList("keys", 8);
                        if (keys.tagCount() > 256) throw new IOException("NBT 根条目数量超过256");
                        JsonArray list = new JsonArray();
                        for (int i = 0; i < keys.tagCount(); i++) {
                            String key = keys.getStringTagAt(i);
                            if (key.length() > 256 || key.isEmpty()) throw new IOException("NBT 根条目无效");
                            list.add(new JsonPrimitive(key));
                        }
                        patch.add("retainedNbtKeys", list);
                    }
                }
                runtime.apply(
                    runtime.editor()
                        .patchHeader(patch));
            } else if ("configureBlueprintCellNbt".equals(operation)) {
                Set<Long> ids = selected(runtime, ui, request);
                List<JsonObject> cells = new ArrayList<>();
                for (long id : ids) cells.add(runtime.sourceCell(id));
                runtime.apply(
                    runtime.editor()
                        .patchHeader(
                            HologramLocalAuthorPolicies.configureCellNbtPolicy(
                                runtime.document()
                                    .headerCopy(),
                                cells,
                                request)));
            } else if ("calibrateBlueprintVersions".equals(operation)) {
                runtime.apply(
                    runtime.editor()
                        .patchHeader(HologramLocalAuthorPolicies.calibrateVersions(runtime.sourceJson())));
            }
            ui.session.notice("本地修改已应用；请保存草稿");
        } catch (IOException | RuntimeException failure) {
            ui.session.notice("本地修改失败，原稿保留：" + failure.getMessage());
        }
        return true;
    }

    private static boolean handles(String operation) {
        switch (operation) {
            case "addPosition":
            case "selectStructure":
            case "editBlueprintPrompt":
            case "editCellFlags":
            case "editHintArea":
            case "structureState":
            case "configureBlueprintNbt":
            case "configureBlueprintCellNbt":
            case "calibrateBlueprintVersions":
            case "clearWorkspace":
            case "discardBlueprint":
                return true;
            default:
                return false;
        }
    }

    private static void addPosition(HologramLocalAuthorRuntime runtime, NBTTagCompound request) throws IOException {
        if (!runtime.editable()) throw new IOException(runtime.diagnostic());
        for (String key : new String[] { "dx", "dy", "dz" })
            if (!request.hasKey(key, 3)) throw new IOException("缺少本地坐标");
        int x = request.getInteger("dx"), y = request.getInteger("dy"), z = request.getInteger("dz");
        String active = runtime.view().data.getString("activeStructure"), requested = request.getString("structureId");
        if (!active.isEmpty() && !requested.isEmpty() && !active.equals(requested)) throw new IOException("结构选择已变化");
        String group = active.isEmpty() ? requested : active;
        long minX = Long.MAX_VALUE, minY = minX, minZ = minX, maxX = Long.MIN_VALUE, maxY = maxX, maxZ = maxX;
        for (AuthorDocument.Cell cell : runtime.document()
            .cells()) {
            if (cell.x() == x && cell.y() == y && cell.z() == z) {
                runtime.editor()
                    .select(java.util.Collections.singleton(cell.id()));
                runtime.selectExisting(cell.id());
                return;
            }
            if (!group.isEmpty() && !cell.memberships()
                .contains(group)) continue;
            minX = Math.min(minX, cell.x());
            minY = Math.min(minY, cell.y());
            minZ = Math.min(minZ, cell.z());
            maxX = Math.max(maxX, cell.x());
            maxY = Math.max(maxY, cell.y());
            maxZ = Math.max(maxZ, cell.z());
        }
        if (request.getBoolean("airOnly") && (minX == Long.MAX_VALUE || x < minX - 1
            || x > maxX + 1
            || y < minY - 1
            || y > maxY + 1
            || z < minZ - 1
            || z > maxZ + 1)) throw new IOException("请选择当前蓝图或结构边界内的空气位置");
        JsonObject cell = new JsonObject();
        cell.addProperty("x", x);
        cell.addProperty("y", y);
        cell.addProperty("z", z);
        cell.addProperty("block", "minecraft:air");
        cell.addProperty("item", "");
        cell.addProperty("metadata", 0);
        cell.addProperty("damage", 0);
        cell.addProperty("count", 1);
        runtime.apply(
            runtime.editor()
                .addCell(cell));
    }

    private static Set<Long> selected(HologramLocalAuthorRuntime runtime, HologramUiModules ui,
        NBTTagCompound request) {
        int[] indices = request.hasKey("indices", 11) ? request.getIntArray("indices")
            : request.hasKey("selectedIndices", 11) ? request.getIntArray("selectedIndices")
                : ui.preview.selection()
                    .indices();
        return runtime.selectionIds(indices);
    }

    private static String prompt(NBTTagCompound request) {
        String text = request.getString("prompt");
        return text.length() <= 2048 ? text : text.substring(0, 2048);
    }

    private static JsonObject hint(HologramLocalAuthorRuntime runtime, Set<Long> ids, String prompt)
        throws IOException {
        int x1 = Integer.MAX_VALUE, y1 = x1, z1 = x1, x2 = Integer.MIN_VALUE, y2 = x2, z2 = x2;
        for (long id : ids) {
            AuthorDocument.Cell cell = runtime.document()
                .cell(id);
            x1 = Math.min(x1, cell.x());
            y1 = Math.min(y1, cell.y());
            z1 = Math.min(z1, cell.z());
            x2 = Math.max(x2, cell.x());
            y2 = Math.max(y2, cell.y());
            z2 = Math.max(z2, cell.z());
        }
        JsonArray result = new JsonArray();
        JsonObject header = runtime.document()
            .headerCopy();
        boolean matched = false;
        if (header.has("hintAreas") && !header.get("hintAreas")
            .isJsonNull()) for (JsonElement value : header.getAsJsonArray("hintAreas")) {
                JsonObject old = value.getAsJsonObject();
                if (coordinate(old, "x1") != x1 || coordinate(old, "y1") != y1
                    || coordinate(old, "z1") != z1
                    || coordinate(old, "x2") != x2
                    || coordinate(old, "y2") != y2
                    || coordinate(old, "z2") != z2) result.add(old);
                else {
                    matched = true;
                    if (!prompt.isEmpty()) {
                        // headerCopy already detached this row; preserve unknown extension fields and its order.
                        old.addProperty("prompt", prompt);
                        result.add(old);
                    }
                }
            }
        if (!matched && !prompt.isEmpty()) {
            if (result.size() >= 256) throw new IOException("提示区已达256个上限；请先删除已有提示区");
            JsonObject area = new JsonObject();
            area.addProperty("x1", x1);
            area.addProperty("y1", y1);
            area.addProperty("z1", z1);
            area.addProperty("x2", x2);
            area.addProperty("y2", y2);
            area.addProperty("z2", z2);
            area.addProperty("prompt", prompt);
            result.add(area);
        }
        JsonObject patch = new JsonObject();
        patch.add("hintAreas", result);
        return patch;
    }

    private static int coordinate(JsonObject source, String key) {
        return source.has(key) ? source.get(key)
            .getAsInt() : 0;
    }
}
