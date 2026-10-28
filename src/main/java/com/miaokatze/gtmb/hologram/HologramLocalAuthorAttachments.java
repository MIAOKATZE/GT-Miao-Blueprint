package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;

/** Owner-thread attachment transaction; also reusable by a detached server-review editor. */
public final class HologramLocalAuthorAttachments implements AutoCloseable {

    private Task task;

    public boolean busy() {
        return task != null;
    }

    public String phase() {
        return task == null ? "" : task.geometry.done ? "validation" : task.geometry.phase;
    }

    public long completed() {
        return task == null ? 0 : task.geometry.done ? task.cursor : task.geometry.completed;
    }

    public long total() {
        return task == null ? 0 : task.geometry.done ? task.geometry.cells.size() : task.geometry.total;
    }

    public void start(AuthorEditor editor, NBTTagCompound request) throws IOException {
        if (task != null) throw new IOException("正在处理附加对象，请等待或取消");
        if (!editor.current()
            .editable())
            throw new IOException(
                editor.current()
                    .diagnostic());
        if (request.hasKey("planRevision", 4) && request.getLong("planRevision") != editor.current()
            .revision()) throw new IOException("蓝图已变化，请重新确认附加位置");
        // Preflight probes native applicability too: guard before constructing (and submitting geometry).
        try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
            task = new Task(editor, request);
        }
    }

    /** Called only by the document owner; a single successful result publishes one edit to that editor. */
    public Result tick() {
        Task pending = task;
        if (pending == null) return null;
        try {
            if (pending.editor.current() != pending.source) throw new IOException("文档已变化，旧附加任务已取消");
            if (!pending.geometry.done) return null;
            if (pending.geometry.failure != null)
                throw new IOException(pending.geometry.failure.getMessage(), pending.geometry.failure);
            long deadline = System.nanoTime() + 2_000_000L;
            int units = 0;
            while (pending.cursor < pending.geometry.cells.size() && units++ < 8 && System.nanoTime() < deadline)
                try (HologramExtensionExecution.Scope local = HologramExtensionExecution.localAuthor()) {
                    pending.stage(pending.geometry.cells.get(pending.cursor++));
                }
            if (pending.cursor < pending.geometry.cells.size()) return null;
            AuthorEditor.Change change = pending.commit();
            task = null;
            pending.geometry.close();
            return new Result(
                pending.request,
                true,
                pending.text.noun() + "已加入本地蓝图",
                change,
                pending.group,
                pending.additions,
                false);
        } catch (IOException | RuntimeException | LinkageError failure) {
            return cancel(failure.getMessage(), failure instanceof HologramExtensionExecution.ReviewRequired);
        }
    }

    public Result cancel(String reason) {
        return cancel(reason, false);
    }

    private Result cancel(String reason, boolean reviewRequired) {
        Task pending = task;
        if (pending == null) return null;
        task = null;
        pending.geometry.cancel();
        return new Result(
            pending.request,
            false,
            reason == null ? "附加对象处理失败，原稿保留" : reason,
            null,
            null,
            null,
            reviewRequired);
    }

    @Override
    public void close() {
        cancel("附加对象任务已取消，原稿保留");
    }

    public static final class Result {

        public final long request;
        public final boolean accepted;
        public final String message;
        public final AuthorEditor.Change change;
        public final boolean reviewRequired;
        // Exclusively owned prepared delta, available only to the detached server review adapter.
        final JsonObject preparedGroup;
        final List<JsonObject> preparedCells;

        Result(long request, boolean accepted, String message, AuthorEditor.Change change, JsonObject preparedGroup,
            List<JsonObject> preparedCells, boolean reviewRequired) {
            this.request = request;
            this.accepted = accepted;
            this.message = message;
            this.change = change;
            this.reviewRequired = reviewRequired;
            this.preparedGroup = preparedGroup;
            this.preparedCells = preparedCells;
        }
    }

    /** Decode only the selected object's author definition, never a render DTO. */
    public static NBTTagCompound selectedDefinition(AuthorDocument source, Set<Long> selected) throws IOException {
        for (long id : selected) {
            AuthorDocument.Cell cell = source.cell(id);
            if (cell == null) continue;
            for (JsonObject group : source.structures()) {
                String groupId = string(group, "id");
                if (cell.memberships()
                    .contains(groupId) && !string(group, "textBoxNbt").isEmpty()) {
                    NBTTagCompound definition = HologramBlueprintNbt.decode(string(group, "textBoxNbt"));
                    definition.setString("id", groupId);
                    definition.setInteger("x", integer(group, "x"));
                    definition.setInteger("y", integer(group, "y"));
                    definition.setInteger("z", integer(group, "z"));
                    return definition;
                }
            }
        }
        return null;
    }

    private static final class Task {

        final AuthorEditor editor;
        final AuthorDocument source;
        final HologramTextBox text;
        final HologramAttachmentGeometry.Job geometry;
        final JsonObject group;
        final List<JsonObject> additions = new ArrayList<>();
        final List<HologramTextBox.Cell> exported = new ArrayList<>();
        final Block host;
        final long request;
        int cursor;

        Task(AuthorEditor editor, NBTTagCompound action) throws IOException {
            this.editor = editor;
            source = editor.current();
            request = action.getLong("textRequest");
            text = HologramTextBox.read(mergeDefinition(source, action.getCompoundTag("textBox")));
            if (text.id.isEmpty()) throw new IOException("缺少附加对象标识");
            if (text.picture == null) validateMaterial(text.material, text.metadata);
            else for (int i = 0; i < text.picture.paletteSize(); i++)
                validateMaterial(text.picture.material(i), text.picture.metadata(i));
            host = Block.getBlockFromName("littletiles:BlockLittleTiles");
            if (host == null || host == Blocks.air || !HologramNativePlacement.supported(host))
                throw new IOException("需要 LittleTiles 1.6.59 兼容方块");
            JsonObject found = null;
            List<String> retained = new ArrayList<>();
            for (JsonObject row : source.structures()) {
                if (text.id.equals(string(row, "id"))) found = row;
                else retained.add(string(row, "textBoxNbt"));
            }
            if (found != null && string(found, "textBoxNbt").isEmpty()) throw new IOException("目标不是附加对象");
            if (found == null && source.structures()
                .size() >= 256) throw new IOException("结构与附加对象数量超过上限");
            group = found == null ? new JsonObject() : found;
            for (AuthorDocument.Cell cell : source.cells()) {
                if (cell.memberships()
                    .contains(text.id)) {
                    if (cell.memberships()
                        .size() != 1 || cell.controller()) throw new IOException("附加对象包含共享成员或受保护锚点");
                } else {
                    retained.add(cell.tileNbt());
                    retained.add(cell.itemNbt());
                }
            }
            JsonElement palette = source.headerValue("palette");
            if (palette != null && palette.isJsonArray()) for (JsonElement row : palette.getAsJsonArray()) {
                if (!row.isJsonObject()) throw new IOException("蓝图调色板格式无效");
                retained.add(string(row.getAsJsonObject(), "tileNbt"));
                retained.add(string(row.getAsJsonObject(), "itemNbt"));
            }
            geometry = HologramAttachmentGeometry.submit(text, retained);
        }

        void stage(HologramTextBox.Cell cell) throws IOException {
            AuthorDocument.Cell occupied = source.cellAt(cell.x, cell.y, cell.z);
            if (occupied != null && !occupied.memberships()
                .contains(text.id)
                && (!occupied.memberships()
                    .isEmpty() || occupied.controller() || occupies(occupied)))
                throw new IOException(text.noun() + "与工作区内容冲突，请移动后确认");
            int paint = cell.boxes[0];
            String materialName = cell.materials[paint];
            int metadata = cell.metadata[paint];
            Block material = Block.getBlockFromName(materialName);
            validateMaterial(materialName, metadata);
            boolean ordinary = cell.fullBlock && metadata <= 15
                && material.isOpaqueCube()
                && material.renderAsNormalBlock();
            ItemStack sample = ordinary ? new ItemStack(material, 1, metadata)
                : HologramNativePlacement.sample(host, 0, cell.sampleTile());
            if (sample == null) throw new IOException("LittleTiles 材料样本生成失败");
            // Existing extension fields survive where a regenerated host retains its position.
            JsonObject raw = occupied != null && occupied.memberships()
                .contains(text.id) ? occupied.sourceCopy() : new JsonObject();
            raw.addProperty("x", cell.x);
            raw.addProperty("y", cell.y);
            raw.addProperty("z", cell.z);
            raw.addProperty("block", ordinary ? materialName : "littletiles:BlockLittleTiles");
            raw.addProperty("metadata", ordinary ? metadata : 0);
            raw.addProperty("item", Item.itemRegistry.getNameForObject(sample.getItem()));
            raw.addProperty("damage", sample.getItemDamage());
            raw.addProperty("tileNbt", ordinary ? "" : cell.encodedTile());
            raw.addProperty("itemNbt", "");
            raw.addProperty("excluded", false);
            raw.addProperty("controller", false);
            raw.addProperty("count", 1);
            raw.addProperty("facing", -1);
            raw.remove("defaultItem");
            raw.remove("defaultDamage");
            raw.remove("allowedTargets");
            raw.addProperty("structureId", text.id);
            JsonArray memberships = new JsonArray();
            memberships.add(new JsonPrimitive(text.id));
            raw.add("structureIds", memberships);
            additions.add(raw);
            if (!ordinary) exported.add(cell);
        }

        AuthorEditor.Change commit() throws IOException {
            if (additions.isEmpty()) throw new IOException("附加对象没有可放置像素");
            group.addProperty("id", text.id);
            String title = text.picture == null ? text.text : text.picture.filename;
            group.addProperty("name", text.noun() + " · " + title.substring(0, Math.min(28, title.length())));
            group.addProperty("sourceClass", "");
            group.addProperty("sourceMod", "gtmb");
            group.addProperty("x", text.x);
            group.addProperty("y", text.y);
            group.addProperty("z", text.z);
            group.addProperty("textBoxNbt", geometry.exportDefinition());
            for (HologramTextBox.Cell cell : exported) cell.exportEncodedTile();
            return editor.replaceAttachment(source.revision(), group, additions);
        }
    }

    /** Native widgets may rebuild known fields; source extensions are merged before freezing the worker input. */
    static NBTTagCompound mergeDefinition(AuthorDocument source, NBTTagCompound edited) throws IOException {
        NBTTagCompound result = new NBTTagCompound();
        for (JsonObject group : source.structures()) if (edited.getString("id")
            .equals(string(group, "id"))) {
                String encoded = string(group, "textBoxNbt");
                if (!encoded.isEmpty()) result = HologramBlueprintNbt.decode(encoded);
                break;
            }
        NBTTagCompound picture = result.hasKey("picture", 10) && edited.hasKey("picture", 10)
            ? (NBTTagCompound) result.getCompoundTag("picture")
                .copy()
            : null;
        for (Object key : edited.func_150296_c()) {
            String name = (String) key;
            result.setTag(
                name,
                edited.getTag(name)
                    .copy());
        }
        if (!edited.hasKey("picture", 10)) result.removeTag("picture");
        else if (picture != null) {
            NBTTagCompound changes = edited.getCompoundTag("picture");
            for (Object key : changes.func_150296_c()) {
                String name = (String) key;
                picture.setTag(
                    name,
                    changes.getTag(name)
                        .copy());
            }
            result.setTag("picture", picture);
        }
        return result;
    }

    private static boolean occupies(AuthorDocument.Cell cell) {
        return !cell.excluded() && (!"minecraft:air".equals(cell.block()) || cell.item() != null && !cell.item()
            .isEmpty());
    }

    private static void validateMaterial(String name, int metadata) throws IOException {
        Block block = Block.getBlockFromName(name);
        if (block == null || block == Blocks.air
            || block.hasTileEntity(metadata)
            || Item.getItemFromBlock(block) == null)
            throw new IOException("附加材料不可用：" + name + "；混凝土组合需要 Et Futurum Requiem");
    }

    private static String string(JsonObject raw, String key) {
        JsonElement value = raw.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static int integer(JsonObject raw, String key) {
        JsonElement value = raw.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }
}
