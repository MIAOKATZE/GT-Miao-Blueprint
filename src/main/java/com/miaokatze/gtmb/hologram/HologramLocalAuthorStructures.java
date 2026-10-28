package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;

import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Detached native declaration job. No session, snapshot, ACK, source-world write or worker-thread callback. */
public final class HologramLocalAuthorStructures implements AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final long SLICE = 2_000_000L;
    private final Thread owner = Thread.currentThread();
    private final AuthorDocument document;
    private final JsonObject group;
    private final HologramPresets.Preset preset;
    private final HologramPresets.Structure original, requested;
    private final ItemStack seed;
    private final Map<String, JsonObject> baseline = new LinkedHashMap<>(), generated = new LinkedHashMap<>();
    private final List<JsonObject> result = new ArrayList<>();
    private Iterator<AuthorDocument.Cell> mergeCursor, controllerCursor;
    private Iterator<Map.Entry<String, JsonObject>> additions;
    private Declaration declaration;
    private NBTTagList capabilities;
    private int facingOrdinal = -1;
    private int phase;
    private long completed;
    private boolean cancelled;
    private final boolean inspectionOnly;

    public HologramLocalAuthorStructures(AuthorDocument document, String id, int main, NBTTagCompound channels,
        boolean noHatches, ItemStack seed) throws IOException {
        this(document, id, main, channels, noHatches, seed, false);
    }

    /**
     * Read-only capability inspection collects native channel declarations, never candidate geometry or source edits.
     */
    public HologramLocalAuthorStructures(AuthorDocument document, String id, int main, NBTTagCompound channels,
        boolean noHatches, ItemStack seed, boolean inspectionOnly) throws IOException {
        this.inspectionOnly = inspectionOnly;
        if (inspectionOnly) phase = 1;
        if (!document.editable()) throw new IOException(document.diagnostic());
        if (!inspectionOnly && document.hasPendingObligations()) throw new IOException("请先完成已有原生坐标变换，再重建结构声明");
        if (main < 1 || channels == null
            || channels.func_150296_c()
                .size() > 64
            || seed == null) throw new IOException("结构信号无效");
        this.document = document;
        this.seed = seed.copy();
        // Author storage and unrelated held-item identity never enter native declaration callbacks.
        this.seed.setTagCompound(new NBTTagCompound());
        JsonObject found = null;
        for (JsonObject row : document.structures()) if (id.equals(string(row, "id"))) found = row;
        if (found == null || !string(found, "textBoxNbt").isEmpty()) throw new IOException("请选择原生来源结构");
        group = found;
        original = GSON.fromJson(found, HologramPresets.Structure.class);
        requested = GSON.fromJson(found, HologramPresets.Structure.class);
        requested.main = main;
        requested.mainExplicit = true;
        requested.noHatches = noHatches;
        requested.channels.clear();
        for (Object key : channels.func_150296_c()) {
            String name = (String) key;
            if (!channels.hasKey(name, 3) || !name.matches("[a-z0-9_.-]{1,48}") || channels.getInteger(name) < 1)
                throw new IOException("结构信道值无效");
            requested.channels.put(name, channels.getInteger(name));
        }
        preset = HologramLocalAuthorSemantics.preset(document.headerCopy());
        preset.structures.add(original);
        controllerCursor = document.cells()
            .iterator();
    }

    public boolean current(AuthorDocument value) {
        return value == document && !cancelled;
    }

    public boolean done() {
        return phase == 4 && !cancelled;
    }

    public String phase() {
        return controllerCursor != null ? "source controllers"
            : phase == 0 ? "baseline" : phase == 1 ? "declarations" : "merge";
    }

    public long completed() {
        return completed;
    }

    public long total() {
        return phase < 2 ? 0 : document.cellCount() + generated.size();
    }

    public JsonObject group() throws IOException {
        requireDone();
        return HologramLocalAuthorSemantics.delta(group, GSON.toJsonTree(original), GSON.toJsonTree(requested))
            .getAsJsonObject();
    }

    public int facingOrdinal() throws IOException {
        requireDone();
        return facingOrdinal;
    }

    public NBTTagList capabilities() throws IOException {
        requireDone();
        return capabilities == null ? new NBTTagList() : (NBTTagList) capabilities.copy();
    }

    public List<JsonObject> cells() throws IOException {
        requireDone();
        if (inspectionOnly) throw new IOException("只读声明检查没有可提交的几何");
        return Collections.unmodifiableList(result);
    }

    public JsonObject headerPatch() throws IOException {
        requireDone();
        return HologramLocalAuthorConfigurations.invalidateHeader(document.headerCopy(), "geometry");
    }

    /** Cooperative client game-thread continuation; individual native callbacks are indivisible. */
    public boolean tick() throws IOException {
        requireOwner();
        if (cancelled) throw new IOException("结构重建已取消");
        if (done()) return true;
        long deadline = System.nanoTime() + SLICE;
        if (controllerCursor != null) {
            int units = 0;
            while (controllerCursor.hasNext() && units++ < 256 && System.nanoTime() < deadline) {
                AuthorDocument.Cell cell = controllerCursor.next();
                if (cell.controller() && cell.memberships()
                    .contains(original.id)) preset.cells.add(HologramLocalAuthorSemantics.cell(cell.sourceCopy()));
                completed++;
            }
            if (controllerCursor.hasNext()) return false;
            controllerCursor = null;
            if (preset.cells.isEmpty()) throw new IOException("来源控制器记录缺失，原稿保留");
            return false;
        }
        if (phase < 2) {
            if (declaration == null) {
                declaration = new Declaration(phase == 0 ? original : requested);
                return false;
            }
            if (!declaration.step(deadline, phase == 0 ? baseline : generated)) return false;
            if (phase == 1) {
                capabilities = HologramCapabilities
                    .describe(declaration.machine, declaration.signal, declaration.capture.channelReport)
                    .toNBT();
                facingOrdinal = declaration.machine.getExtendedFacing()
                    .ordinal();
            }
            declaration = null;
            if (inspectionOnly) {
                phase = 4;
                return true;
            }
            phase++;
            if (phase == 2) mergeCursor = document.cells()
                .iterator();
            return false;
        }
        int units = 0;
        while (phase == 2 && mergeCursor.hasNext() && units++ < 256 && System.nanoTime() < deadline) {
            AuthorDocument.Cell cell = mergeCursor.next();
            JsonObject raw = cell.sourceCopy();
            String key = position(raw);
            JsonObject next = generated.remove(key), old = baseline.get(key);
            boolean own = cell.memberships()
                .contains(original.id);
            if (own) {
                JsonObject merged = mergeCell(
                    raw,
                    old,
                    next,
                    cell.memberships()
                        .size() > 1);
                if (merged != null) result.add(merged);
            } else if (next != null) {
                // Never overwrite an independent source cell or silently appropriate an attachment.
                if (!sameTarget(raw, next)) throw new IOException("新结构与现有独立格冲突：" + key);
                List<String> ids = new ArrayList<>(cell.memberships());
                ids.add(original.id);
                memberships(raw, ids);
                result.add(mergeCell(raw, old, next, true));
            }
            completed++;
        }
        if (phase == 2 && !mergeCursor.hasNext()) {
            phase = 3;
            additions = generated.entrySet()
                .iterator();
        }
        while (phase == 3 && additions.hasNext() && units++ < 256 && System.nanoTime() < deadline) {
            Map.Entry<String, JsonObject> entry = additions.next();
            // Missing old-declared coordinates are an intentional deletion, not permission to resurrect them.
            if (!baseline.containsKey(entry.getKey())) result.add(entry.getValue());
            completed++;
        }
        if (phase == 3 && !additions.hasNext()) phase = 4;
        return done();
    }

    @Override
    public void close() {
        requireOwner();
        cancelled = true;
        if (declaration != null && declaration.capture != null) declaration.capture.cancelPending();
        declaration = null;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Native declaration callback left owner game thread");
    }

    private void requireDone() throws IOException {
        if (!done()) throw new IOException("结构声明尚未完成");
    }

    /** Source overlay reconciliation is pure and independently regression-testable. */
    static JsonObject mergeCell(JsonObject current, JsonObject baseline, JsonObject generated, boolean shared)
        throws IOException {
        boolean edited = baseline == null || !sameTarget(current, baseline)
            || hasPayload(current)
            || !string(current, "prompt").isEmpty()
            || bool(current, "excluded")
            || hasUnknown(current);
        if (generated == null) return edited && !shared ? copy(current) : null;
        JsonObject result = copy(current);
        if (!edited && !shared) for (String key : new String[] { "item", "damage", "block", "metadata", "defaultItem",
            "defaultDamage", "facing", "controller", "itemNbt" }) {
                if (generated.has(key)) result.add(key, generated.get(key));
                else result.remove(key);
            }
        JsonArray candidates = generated.has("allowedTargets") ? generated.getAsJsonArray("allowedTargets")
            : new JsonArray();
        if (shared) {
            JsonArray intersection = new JsonArray();
            if (current.has("allowedTargets") && current.get("allowedTargets")
                .isJsonArray())
                for (JsonElement candidate : candidates)
                    for (JsonElement old : current.getAsJsonArray("allowedTargets")) if (candidate.equals(old)) {
                        intersection.add(candidate);
                        break;
                    }
            candidates = intersection;
        }
        result.add("allowedTargets", candidates);
        result.addProperty("authorDeclarationComplete", !shared && bool(generated, "authorDeclarationComplete"));
        // Keep target choice edits, but restore always follows this revision's actual native default.
        if (generated.has("defaultItem")) result.add("defaultItem", generated.get("defaultItem"));
        if (generated.has("defaultDamage")) result.add("defaultDamage", generated.get("defaultDamage"));
        return result;
    }

    private static boolean hasUnknown(JsonObject row) {
        for (Map.Entry<String, JsonElement> field : row.entrySet())
            if (!(" item defaultItem block tileNbt itemNbt prompt structureId structureIds allowedTargets metadata excluded defaultDamage damage count x y z facing controller authorDeclarationComplete ")
                .contains(" " + field.getKey() + " ")) return true;
        return false;
    }

    private static boolean hasPayload(JsonObject row) {
        return !string(row, "tileNbt").isEmpty() || !string(row, "itemNbt").isEmpty();
    }

    private static boolean sameTarget(JsonObject a, JsonObject b) {
        return string(a, "item").equals(string(b, "item")) && number(a, "damage") == number(b, "damage")
            && string(a, "block").equals(string(b, "block"))
            && number(a, "metadata") == number(b, "metadata")
            && number(a, "facing") == number(b, "facing");
    }

    private static String position(JsonObject row) {
        return number(row, "x") + "," + number(row, "y") + "," + number(row, "z");
    }

    private static int number(JsonObject row, String key) {
        return row.has(key) && !row.get(key)
            .isJsonNull() ? row.get(key)
                .getAsInt() : "facing".equals(key) ? -1 : 0;
    }

    private static boolean bool(JsonObject row, String key) {
        return row.has(key) && !row.get(key)
            .isJsonNull()
            && row.get(key)
                .getAsBoolean();
    }

    private static String string(JsonObject row, String key) {
        return row.has(key) && !row.get(key)
            .isJsonNull() ? row.get(key)
                .getAsString() : "";
    }

    private static JsonObject copy(JsonObject row) {
        return new com.google.gson.JsonParser().parse(row.toString())
            .getAsJsonObject();
    }

    private static void memberships(JsonObject row, List<String> ids) {
        JsonArray array = new JsonArray();
        for (String id : ids) array.add(new JsonPrimitive(id));
        row.add("structureIds", array);
        row.addProperty("structureId", ids.isEmpty() ? "" : ids.get(0));
    }

    private final class Declaration {

        final HologramPresets.Structure settings;
        final MTEEnhancedMultiBlockBase<?> machine;
        final ItemStack signal;
        HologramCapture capture;
        int cursor;

        Declaration(HologramPresets.Structure settings) throws IOException {
            this.settings = settings;
            machine = HologramVirtualStructureEditing.controller(preset, original);
            signal = seed.copy();
            signal.stackSize = settings.main;
            for (Map.Entry<String, Integer> channel : settings.channels.entrySet())
                ChannelDataAccessor.setChannelData(signal, channel.getKey(), channel.getValue());
        }

        boolean step(long deadline, Map<String, JsonObject> output) throws IOException {
            if (capture == null || capture.pending) {
                capture = HologramCapture.collect(machine, signal, machine.getExtendedFacing(), capture, deadline);
                if (capture.pending) return false;
                if (capture.incomplete) throw new IOException(capture.failure);
                return false;
            }
            if (inspectionOnly) return true; // No candidate enumeration, before/after generation, or merge.
            int units = 0;
            while (cursor < capture.cells.size() && units++ < 32 && System.nanoTime() < deadline) {
                HologramCapture.Cell nativeCell = capture.cells.get(cursor++);
                HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
                if (!nativeCell.anchor) candidates.collect(
                    nativeCell.element,
                    machine,
                    machine.getBaseMetaTileEntity()
                        .getWorld(),
                    nativeCell.x,
                    nativeCell.y,
                    nativeCell.z,
                    nativeCell.elementTrigger,
                    null,
                    0);
                List<ItemStack> allowed = new ArrayList<>();
                for (ItemStack stack : candidates.stacks) {
                    Block block = Block.getBlockFromItem(stack.getItem());
                    if (!settings.noHatches
                        || nativeCell.block == StructureLibAPI.getBlockHint() && "hatch".equals(nativeCell.role)
                        || block != Blocks.air && (!block.hasTileEntity(stack.getItemDamage())
                            || HologramFrameSupport.inertFrameBlock(block, stack.getItemDamage())))
                        allowed.add(stack);
                }
                ItemStack target = nativeCell.anchor ? machine.getStackForm(1)
                    : nativeCell.block == null || nativeCell.block == Blocks.air
                        || nativeCell.block == StructureLibAPI.getBlockHint() ? null
                            : HologramBlockIdentity.materialStack(nativeCell.block, nativeCell.meta);
                if (target == null && !allowed.isEmpty()) target = allowed.get(0)
                    .copy();
                HologramPresets.Cell saved = target == null ? new HologramPresets.Cell()
                    : HologramPresets.cell(
                        target,
                        0,
                        0,
                        0,
                        nativeCell.anchor ? machine.getExtendedFacing()
                            .getDirection()
                            .ordinal() : -1,
                        nativeCell.anchor);
                saved.x = Math.addExact(original.x, nativeCell.x);
                saved.y = Math.addExact(original.y, Math.subtractExact(nativeCell.y, 128));
                saved.z = Math.addExact(original.z, nativeCell.z);
                Block materialBlock = nativeCell.block;
                int materialMetadata = nativeCell.meta;
                if (target != null && (materialBlock == null || materialBlock == Blocks.air
                    || materialBlock == StructureLibAPI.getBlockHint())) {
                    materialBlock = Block.getBlockFromItem(target.getItem());
                    materialMetadata = target.getItem()
                        .getMetadata(target.getItemDamage());
                }
                if (materialBlock == null || materialBlock == StructureLibAPI.getBlockHint()
                    || nativeCell.unknown && target == null)
                    throw new IOException("原生声明未提供可确定的目标：" + saved.x + "," + saved.y + "," + saved.z);
                saved.block = String.valueOf(Block.blockRegistry.getNameForObject(materialBlock));
                saved.metadata = materialMetadata;
                if (target != null && target.hasTagCompound())
                    saved.itemNbt = HologramBlueprintNbt.encodeCaptured(materialBlock, target.getTagCompound());
                saved.defaultItem = saved.item;
                saved.defaultDamage = saved.damage;
                saved.structureId = original.id;
                saved.structureIds = Collections.singletonList(original.id);
                saved.allowedTargets = HologramBlueprintData.savedTargets(allowed);
                JsonObject row = GSON.toJsonTree(saved)
                    .getAsJsonObject();
                row.addProperty("authorDeclarationComplete", candidates.complete);
                if (settings == original) {
                    // Baseline needs only target identity/deletion coordinates, not another million candidate lists.
                    JsonObject identity = new JsonObject();
                    for (String key : new String[] { "item", "damage", "block", "metadata", "facing" })
                        if (row.has(key)) identity.add(key, row.get(key));
                    output.put(position(row), identity);
                } else output.put(position(row), row);
            }
            return cursor >= capture.cells.size();
        }
    }
}
