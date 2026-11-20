// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.hologram.HologramPresets.Cell;
import com.miaokatze.gtmb.hologram.HologramPresets.HintArea;
import com.miaokatze.gtmb.hologram.HologramPresets.Preset;
import com.miaokatze.gtmb.hologram.HologramPresets.Structure;

/** A private authoring document. It receives no world, inventory, connection, or server session authority. */
public final class HologramLocalBlueprintDocument {

    private static final int HISTORY_LIMIT = 32;

    @Desugar
    private record Version(Preset document, Object content) {}

    @Desugar
    private record Clipboard(List<Cell> cells, List<Structure> structures, Position origin) {}

    @Desugar
    private record Position(int x, int y, int z) {

        static Position of(Cell cell) {
            return new Position(cell.x, cell.y, cell.z);
        }

        static Position of(Structure structure) {
            return new Position(structure.x, structure.y, structure.z);
        }

        Position translate(long dx, long dy, long dz) throws IOException {
            long nextX = x + dx, nextY = y + dy, nextZ = z + dz;
            if (Math.abs(nextX) > 60000000L || Math.abs(nextY) > 256L || Math.abs(nextZ) > 60000000L)
                throw new IOException("蓝图坐标超出范围");
            return new Position((int) nextX, (int) nextY, (int) nextZ);
        }

        void write(Cell cell) {
            cell.x = x;
            cell.y = y;
            cell.z = z;
        }

        void write(Structure structure) {
            structure.x = x;
            structure.y = y;
            structure.z = z;
        }
    }

    private final String session = "local-blueprint:" + UUID.randomUUID();
    private final Deque<Version> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private volatile Preset document;
    private String fileId = "";
    private Object content = new Object();
    private Object savedContent = content;
    private byte[] encoded;
    private Clipboard clipboard;
    private volatile long revision;
    private int selected = -1, layer, scope, mode;
    private int[] selection = new int[0];
    private String activeStructure = "", status = "";

    private HologramLocalBlueprintDocument(Preset document, byte[] encoded) {
        this.document = document;
        this.encoded = encoded;
    }

    public static HologramLocalBlueprintDocument open(byte[] exactJson) throws IOException {
        return open(exactJson, null);
    }

    /** A file ID records the library slot. A named import starts as an unsaved copy. */
    public static HologramLocalBlueprintDocument open(byte[] exactJson, String fileId) throws IOException {
        if (exactJson == null || exactJson.length == 0 || exactJson.length > HologramBlueprintArtifact.MAX_JSON_BYTES)
            throw new IOException("蓝图文件大小超出范围");
        String json = StandardCharsets.UTF_8.newDecoder()
            .decode(ByteBuffer.wrap(exactJson))
            .toString();
        Preset source = HologramBlueprintSharing.decodeUnchecked(json);
        boolean legacy = source.schema == 1;
        if (legacy) source = HologramPresets.upgradeLegacy(source);
        HologramPresets.validatePlacementData(source);
        HologramLocalBlueprintDocument opened = new HologramLocalBlueprintDocument(
            source,
            legacy ? null : exactJson.clone());
        if (fileId != null) {
            opened.fileId = HologramLibraryService.localId(fileId, true);
            if (fileId.isEmpty()) opened.savedContent = null;
        } else if (source.id.startsWith("local_")) opened.fileId = HologramLibraryService.localId(source.id, false);
        return opened;
    }

    public long revision() {
        return revision;
    }

    public boolean dirty() {
        return content != savedContent;
    }

    public byte[] artifact() throws IOException {
        if (document.cells.isEmpty()) throw new IOException("蓝图没有方块");
        if (encoded == null) encoded = encode(document);
        return encoded.clone();
    }

    public String fileId() {
        return fileId;
    }

    public byte[] prepareSave(String name, String authorName, UUID authorUuid, String localId) throws IOException {
        NBTTagCompound options = new NBTTagCompound();
        if (name != null) options.setString("name", name);
        return prepareSave(options, authorName, authorUuid, localId);
    }

    public byte[] prepareSave(NBTTagCompound options, String authorName, UUID authorUuid, String localId)
        throws IOException {
        Preset prepared = copyForEdit(document);
        prepared.id = localId == null || localId.isEmpty() ? "local_" + UUID.randomUUID()
            : HologramLibraryService.localId(localId, false);
        String name = options.getString("name")
            .trim();
        if (!name.isEmpty()) prepared.name = name;
        if (options.hasKey("retainNbt", 1)) prepared.retainNbt = options.getBoolean("retainNbt");
        if (options.hasKey("retainCovers", 1)) prepared.retainCovers = options.getBoolean("retainCovers");
        if (options.hasKey("retainAllNbt", 1)) prepared.retainedNbtKeys = options.getBoolean("retainAllNbt") ? null
            : HologramAuthoringQueries.selectedNbtKeys(options.getTagList("retainedNbtKeys", 8));
        if (options.hasKey("deidentificationLevel", 3)
            && options.getInteger("deidentificationLevel") != HologramDeidentification.level(prepared))
            prepared = HologramDeidentification.apply(prepared, options.getInteger("deidentificationLevel"));
        if (text(prepared.authorName).isEmpty() && text(prepared.authorUuid).isEmpty()
            && authorName != null
            && !authorName.trim()
                .isEmpty()
            && authorUuid != null) {
            prepared.authorName = authorName.trim();
            prepared.authorUuid = authorUuid.toString();
        }
        if (text(prepared.authorName).isEmpty() || text(prepared.authorUuid).isEmpty())
            throw new IOException("蓝图缺少完整作者信息");
        if (prepared.createdAt <= 0) prepared.createdAt = System.currentTimeMillis();
        HologramBlueprintVersions.stampSaved(prepared);
        HologramPresets.validatePlacementData(prepared);
        return encode(prepared);
    }

    /** The caller publishes a save only after the exact JSON and preview pair has committed. */
    public void saved(long expectedRevision, byte[] json) throws IOException {
        saved(expectedRevision, open(json), json);
    }

    public void saved(long expectedRevision, HologramLocalBlueprintDocument prepared, byte[] exactJson)
        throws IOException {
        if (expectedRevision != revision) throw new IOException("蓝图已修改，请重新保存");
        Preset saved = prepared.document;
        fileId = HologramLibraryService.localId(saved.id, false);
        document = saved;
        savedContent = content;
        encoded = exactJson.clone();
        revision++;
        status = "蓝图已保存到客户端本地";
    }

    private static byte[] encode(Preset source) throws IOException {
        return HologramBlueprintCompactCodec.encode(source, (done, total) -> {});
    }

    /** A version owns its header and list; cells are copied when a command changes them. */
    private static Preset copyForEdit(Preset source) {
        Preset next = HologramSnapshotMetadataSource.copyMetadata(source);
        next.cells.addAll(source.cells);
        next.palette.addAll(source.palette);
        return next;
    }

    public static boolean handles(String op) {
        return switch (op) {
            case "editCopy", "editCut", "editPaste", "editMove", "editDelete", "editUndo", "editRedo", "addPosition", "restoreCell", "clearPin", "pin", "customPin", "sourcePin", "hatchFacing", "rotateCell", "rotateBlueprint", "editBlueprintPrompt", "editCellFlags", "editHintArea", "configureBlueprintNbt", "selectStructure", "selectCell", "configure" -> true;
            default -> false;
        };
    }

    /** Reuse the original workbench commands and the selection indices from this exact document revision. */
    public boolean apply(String op, NBTTagCompound request) throws IOException {
        if (!handles(op)) return false;
        if (request.hasKey("planRevision")
            && (!request.hasKey("planRevision", 4) || request.getLong("planRevision") != revision))
            throw new IOException("蓝图已更新，请重新选择编辑目标");
        switch (op) {
            case "editUndo" -> restore(undo, redo, "已撤销规划编辑");
            case "editRedo" -> restore(redo, undo, "已重做规划编辑");
            case "selectStructure" -> selectStructure(request.getString("structureId"));
            case "selectCell" -> setSelection(request.getInteger("selected"), request.getIntArray("selectedIndices"));
            case "configure" -> {
                if (request.hasKey("scope", 3)) scope = HologramValues.clamp(request.getInteger("scope"), 0, 3);
                if (request.hasKey("layer", 3)) layer = HologramValues.clamp(request.getInteger("layer"), -256, 256);
                if (request.hasKey("mode", 3)) mode = HologramValues.clamp(request.getInteger("mode"), 0, 1);
            }
            case "editCopy" -> {
                clipboard = copySelection(indices(request));
                status = "已复制选中蓝图格及设备配置";
            }
            default -> edit(op, request);
        }
        return true;
    }

    private void edit(String op, NBTTagCompound request) throws IOException {
        Preset next = "rotateBlueprint".equals(op) ? HologramBlueprintRotation.copyPreset(document)
            : copyForEdit(document);
        Clipboard copied = null;
        int[] affected = new int[0];
        String message = "蓝图已修改";
        switch (op) {
            case "editCut" -> {
                affected = indices(request);
                copied = copySelection(affected);
                for (int index : affected) change(next, index).excluded = true;
                message = "已剪切规划，原位置已标为排除";
            }
            case "editPaste" -> {
                if (clipboard == null) throw new IOException("请先复制蓝图格");
                Cell anchor = document.cells.get(primary(request));
                Position from = clipboard.origin();
                Position to = Position.of(anchor);
                appendCopies(next, clipboard, (long) to.x - from.x, (long) to.y - from.y, (long) to.z - from.z, true);
                message = "已粘贴蓝图规划";
            }
            case "editMove" -> {
                affected = indices(request);
                int dx = offset(request, "dx"), dy = offset(request, "dy"), dz = offset(request, "dz");
                if (dx == 0 && dy == 0 && dz == 0) return;
                if (request.getBoolean("copy")) appendCopies(next, copySelection(affected), dx, dy, dz, false);
                else move(next, affected, dx, dy, dz);
                message = request.getBoolean("copy") ? "已复制蓝图格到新坐标" : "已移动选中蓝图格";
            }
            case "editDelete" -> {
                affected = indices(request);
                remove(next, affected);
                message = "已删除选中蓝图格，可撤销";
            }
            case "addPosition" -> {
                addPosition(next, request);
                message = "位置已添加";
            }
            case "restoreCell", "clearPin" -> {
                affected = indices(request);
                for (int index : affected) {
                    Cell cell = editableCell(next, index);
                    ItemStack material = cell.defaultStack();
                    if (material == null) throw new IOException("所选蓝图格没有可恢复的默认外壳");
                    replaceMaterial(cell, material);
                }
            }
            case "pin", "customPin", "sourcePin" -> {
                affected = indices(request);
                int choice = request.hasKey("choice", 3) ? request.getInteger("choice") : -1;
                ItemStack sample = request.hasKey("expectedSample", 10)
                    ? ItemStack.loadItemStackFromNBT(request.getCompoundTag("expectedSample"))
                    : null;
                for (int index : affected) {
                    Cell cell = editableCell(next, index);
                    ItemStack target = sample;
                    if (target == null && "pin".equals(op)
                        && cell.allowedTargets != null
                        && choice >= 0
                        && choice < cell.allowedTargets.size())
                        target = cell.allowedTargets.get(choice)
                            .stack();
                    replaceMaterial(cell, target);
                }
            }
            case "rotateBlueprint" -> HologramWorldPlacementWorkflow.turnSavedPreset(next);
            case "rotateCell" -> {
                affected = indices(request);
                for (int index : affected) {
                    Cell cell = editableCell(next, index);
                    for (String member : cell.memberships()) {
                        Structure structure = HologramBlueprintData.blueprintSource(next, member);
                        if (structure != null && !text(structure.textBoxNbt).isEmpty())
                            throw new IOException("请使用整图旋转保留文字或图片对象");
                    }
                    rotateCell(cell, request.getBoolean("vertical"));
                }
            }
            case "hatchFacing" -> {
                int index = primary(request);
                if (!request.hasKey("facing", 3)) throw new IOException("仓室朝向无效");
                int direction = request.getInteger("facing");
                Cell cell = editableCell(next, index);
                if (direction < -1 || !HologramCellPolicy.virtualFrontAllowed(cell.stack(), direction))
                    throw new IOException("仓室不支持所选方向");
                cell.facing = direction;
                affected = new int[] { index };
            }
            case "editBlueprintPrompt" -> next.prompt = HologramSessionPolicy
                .boundedPrompt(request.getString("prompt"));
            case "editCellFlags", "editHintArea" -> {
                affected = indices(request);
                String prompt = HologramSessionPolicy.boundedPrompt(request.getString("prompt"));
                if ("editHintArea".equals(op)) updateHint(next, affected, prompt);
                for (int index : affected) {
                    Cell cell = change(next, index);
                    if (request.hasKey("excluded", 1)) cell.excluded = request.getBoolean("excluded");
                    if ("editCellFlags".equals(op) && request.hasKey("prompt", 8)) cell.prompt = prompt;
                }
            }
            case "configureBlueprintNbt" -> {
                if (request.hasKey("all", 1)) next.retainedNbtKeys = request.getBoolean("all") ? null
                    : HologramAuthoringQueries.selectedNbtKeys(request.getTagList("keys", 8));
                if (request.hasKey("retainNbt", 1)) next.retainNbt = request.getBoolean("retainNbt");
                if (request.hasKey("retainCovers", 1)) next.retainCovers = request.getBoolean("retainCovers");
            }
            default -> throw new IllegalArgumentException(op);
        }
        if (!text(next.processingNbt).isEmpty()) {
            String dependency = switch (op) {
                case "editBlueprintPrompt", "editHintArea" -> "prompt";
                case "configureBlueprintNbt" -> "nbt";
                case "editCellFlags" -> request.hasKey("excluded", 1) ? "geometry" : "prompt";
                default -> "geometry";
            };
            HologramBlueprintProcessing.invalidate(next, dependency);
        }
        if (!next.cells.isEmpty()) HologramPresets.validatePlacementData(next);
        remember(undo);
        redo.clear();
        document = next;
        content = new Object();
        encoded = null;
        revision++;
        status = message;
        if (copied != null) clipboard = copied;
        if ("addPosition".equals(op)) setSelection(document.cells.size() - 1, new int[] { document.cells.size() - 1 });
        else if ("editDelete".equals(op) || "editPaste".equals(op) || "editMove".equals(op))
            setSelection(-1, new int[0]);
        else if (affected.length > 0) setSelection(affected[0], affected);
    }

    private void remember(Deque<Version> history) {
        if (history.size() >= HISTORY_LIMIT) history.removeFirst();
        history.addLast(new Version(document, content));
    }

    private void restore(Deque<Version> from, Deque<Version> to, String message) {
        if (from.isEmpty()) return;
        remember(to);
        Version previous = from.removeLast();
        Preset replacement = copyForEdit(previous.document());
        replacement.id = document.id;
        replacement.name = document.name;
        replacement.authorName = document.authorName;
        replacement.authorUuid = document.authorUuid;
        replacement.createdAt = document.createdAt;
        replacement.nativeBlueprint = document.nativeBlueprint;
        document = replacement;
        content = previous.content();
        encoded = null;
        revision++;
        status = message;
        if (!activeStructure.isEmpty() && HologramBlueprintData.blueprintSource(document, activeStructure) == null)
            activeStructure = "";
        setSelection(-1, new int[0]);
    }

    private int[] indices(NBTTagCompound request) throws IOException {
        int[] values = request.getIntArray("indices");
        if (values.length == 0) values = request.getIntArray("selectedIndices");
        if (values.length == 0) values = new int[] { primary(request) };
        if (values.length > HologramCapture.LIMIT) throw new IOException("选择数量超过蓝图上限");
        if (request.hasKey("structureId", 8) && !request.getString("structureId")
            .equals(activeStructure)) throw new IOException("结构选择已变化，请重新选择编辑目标");
        for (int index : values) if (index < 0 || index >= document.cells.size()
            || !activeStructure.isEmpty() && !document.cells.get(index)
                .memberships()
                .isEmpty()
                && !document.cells.get(index)
                    .belongsTo(activeStructure))
            throw new IOException("选择位置或结构已变化，请重新选择");
        return Arrays.stream(values)
            .distinct()
            .toArray();
    }

    private int primary(NBTTagCompound request) throws IOException {
        int index = request.hasKey("selected", 3) ? request.getInteger("selected") : selected;
        if (index < 0 || index >= document.cells.size()) throw new IOException("请先选择蓝图格");
        return index;
    }

    private void setSelection(int primary, int[] values) {
        selected = primary >= 0 && primary < document.cells.size() ? primary : -1;
        selection = Arrays.stream(values)
            .filter(index -> index >= 0 && index < document.cells.size())
            .distinct()
            .toArray();
    }

    private void selectStructure(String id) throws IOException {
        if (!id.isEmpty() && HologramBlueprintData.blueprintSource(document, id) == null)
            throw new IOException("结构不存在");
        activeStructure = id;
        setSelection(-1, new int[0]);
    }

    private static Cell change(Preset draft, int index) {
        Cell copy = HologramSnapshotMetadataSource.copyCell(draft.cells.get(index));
        draft.cells.set(index, copy);
        return copy;
    }

    private static Cell editableCell(Preset draft, int index) throws IOException {
        Cell copy = change(draft, index);
        if (copy.controller) throw new IOException("控制器始终受保护");
        return copy;
    }

    private static int offset(NBTTagCompound request, String key) throws IOException {
        if (request.hasKey(key) && !request.hasKey(key, 3)) throw new IOException("蓝图移动坐标无效");
        return request.getInteger(key);
    }

    private void addPosition(Preset draft, NBTTagCompound request) throws IOException {
        if (!request.hasKey("dx", 3) || !request.hasKey("dy", 3) || !request.hasKey("dz", 3))
            throw new IOException("蓝图位置无效");
        Position position = new Position(0, 0, 0)
            .translate(request.getInteger("dx"), request.getInteger("dy"), request.getInteger("dz"));
        for (Cell cell : draft.cells) if (position.equals(Position.of(cell))) throw new IOException("蓝图位置已存在");
        Cell cell = new Cell();
        position.write(cell);
        cell.block = "minecraft:air";
        if (!activeStructure.isEmpty()) cell.structureId = activeStructure;
        draft.cells.add(cell);
    }

    private static void replaceMaterial(Cell cell, ItemStack target) throws IOException {
        if (target == null || HologramRecovery.hasSeal(target)) throw new IOException("请先选择可放置的普通方块或设备");
        Block block = Block.getBlockFromItem(target.getItem());
        if (block == null || block == Blocks.air) throw new IOException("样本没有可放置方块");
        if (target.getItem() instanceof gregtech.common.blocks.ItemMachines
            && gregtech.common.blocks.ItemMachines.getMetaTileEntity(target) == null) throw new IOException("样本设备尚未注册");
        String item = String.valueOf(Item.itemRegistry.getNameForObject(target.getItem()));
        if (item.equals(cell.item) && cell.damage == target.getItemDamage()) {
            cell.excluded = false;
            return;
        }
        cell.item = item;
        cell.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
        cell.damage = target.getItemDamage();
        cell.metadata = target.getItem()
            .getMetadata(target.getItemDamage());
        cell.count = 1;
        cell.itemNbt = null;
        cell.tileNbt = null;
        cell.facing = -1;
        cell.excluded = false;
    }

    private static void rotateCell(Cell cell, boolean vertical) throws IOException {
        ItemStack target = cell.stack();
        if (target != null && target.getItem() instanceof gregtech.common.blocks.ItemMachines
            && !HologramBlockTransforms.isPipe(target)) {
            int[] legal = java.util.stream.IntStream.range(0, 6)
                .filter(side -> HologramCellPolicy.virtualFrontAllowed(target, side))
                .toArray();
            int next = HologramCellRotation.nextFacing(cell.facing, legal);
            if (next < 0) throw new IOException("本格目标没有可轮换的合法朝向");
            cell.facing = next;
        } else {
            HologramBlockTransforms.turn(cell, vertical, java.util.Collections.emptyMap(), true);
            HologramBlockTransforms.nativeSample(cell);
        }
    }

    private Clipboard copySelection(int[] indices) throws IOException {
        Set<String> complete = completeStructures(document, indices);
        List<Cell> cells = new ArrayList<>(indices.length);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (int index : indices) {
            Cell original = document.cells.get(index);
            if (original.controller) throw new IOException("控制器始终受保护");
            requireCopyable(original);
            Cell copy = HologramSnapshotMetadataSource.copyCell(original);
            List<String> members = copy.memberships();
            members.retainAll(complete);
            memberships(copy, members);
            cells.add(copy);
            minX = Math.min(minX, copy.x);
            minY = Math.min(minY, copy.y);
            minZ = Math.min(minZ, copy.z);
        }
        List<Structure> structures = new ArrayList<>();
        for (Structure structure : document.structures)
            if (complete.contains(structure.id)) structures.add(HologramBlueprintData.copyStructure(structure));
        return new Clipboard(cells, structures, new Position(minX, minY, minZ));
    }

    private static void requireCopyable(Cell cell) throws IOException {
        NBTTagCompound item = HologramBlueprintNbt.decode(cell.itemNbt);
        NBTTagCompound tile = HologramBlueprintNbt.decode(cell.tileNbt);
        if (item != null && item.hasKey(HologramRecovery.TAG) || tile != null && tile.hasKey(HologramRecovery.TAG))
            throw new IOException("封存设备不能复制");
    }

    /** Keep complete attachment metadata with its blocks, and never move half an attachment. */
    private static Set<String> completeStructures(Preset draft, int[] indices) throws IOException {
        Set<Integer> selected = new HashSet<>();
        for (int index : indices) selected.add(index);
        Map<String, Integer> chosen = new HashMap<>(), total = new HashMap<>();
        for (int index = 0; index < draft.cells.size(); index++) {
            for (String member : draft.cells.get(index)
                .memberships()) {
                total.merge(member, 1, Integer::sum);
                if (selected.contains(index)) chosen.merge(member, 1, Integer::sum);
            }
        }
        Set<String> complete = new HashSet<>();
        for (Structure structure : draft.structures) {
            int count = chosen.getOrDefault(structure.id, 0);
            if (count == 0) continue;
            if (count == total.get(structure.id)) complete.add(structure.id);
            else if (!text(structure.textBoxNbt).isEmpty()) throw new IOException("文字或图片须整体选择后编辑");
        }
        return complete;
    }

    private static void appendCopies(Preset draft, Clipboard clipboard, long dx, long dy, long dz, boolean overlay)
        throws IOException {
        Map<Position, Cell> cells = positions(draft.cells);
        Map<String, String> replacements = new HashMap<>();
        for (Structure original : clipboard.structures()) {
            Structure copy = HologramBlueprintData.copyStructure(original);
            if (!text(original.textBoxNbt).isEmpty())
                copy.id = (original.id.startsWith(HologramPictureBox.PREFIX) ? HologramPictureBox.PREFIX
                    : HologramTextBox.PREFIX) + UUID.randomUUID();
            else copy.id = UUID.randomUUID()
                .toString();
            Position.of(copy)
                .translate(dx, dy, dz)
                .write(copy);
            replacements.put(original.id, copy.id);
            draft.structures.add(copy);
        }
        for (Cell original : clipboard.cells()) {
            Cell copy = HologramSnapshotMetadataSource.copyCell(original);
            Position target = Position.of(copy)
                .translate(dx, dy, dz);
            Cell previous = cells.get(target);
            if (previous != null && (!overlay || previous.controller
                || !previous.memberships()
                    .isEmpty()))
                throw new IOException("目标与其他蓝图格重叠，坐标未修改");
            target.write(copy);
            List<String> members = copy.memberships();
            members.replaceAll(replacements::get);
            memberships(copy, members);
            cells.put(target, copy);
        }
        draft.cells = new ArrayList<>(cells.values());
    }

    private static void move(Preset draft, int[] indices, long dx, long dy, long dz) throws IOException {
        Set<String> complete = completeStructures(draft, indices);
        Map<Position, Cell> cells = positions(draft.cells);
        Map<Position, Position> translated = new HashMap<>();
        List<Cell> moved = new ArrayList<>(indices.length);
        for (int index : indices) {
            Cell original = draft.cells.get(index);
            if (original.controller) throw new IOException("控制器始终受保护");
            Position initial = Position.of(original);
            Cell target = HologramSnapshotMetadataSource.copyCell(original);
            List<String> remaining = original.memberships(), moving = original.memberships();
            remaining.removeAll(complete);
            moving.retainAll(complete);
            Position position = initial.translate(dx, dy, dz);
            position.write(target);
            memberships(target, moving);
            moved.add(target);
            if (remaining.isEmpty()) {
                cells.remove(initial);
                translated.put(initial, position);
            } else {
                requireCopyable(original);
                Cell retained = HologramSnapshotMetadataSource.copyCell(original);
                memberships(retained, remaining);
                cells.put(initial, retained);
            }
        }
        for (Cell target : moved)
            if (cells.putIfAbsent(Position.of(target), target) != null) throw new IOException("目标与其他蓝图格重叠，坐标未修改");
        for (Structure structure : draft.structures) if (complete.contains(structure.id)) Position.of(structure)
            .translate(dx, dy, dz)
            .write(structure);
        for (HologramAePolicy.Issue issue : draft.aeIssues) {
            if ("policy".equals(issue.path)) continue;
            Position position = translated.get(new Position(issue.x, issue.y, issue.z));
            if (position != null) {
                issue.x = position.x;
                issue.y = position.y;
                issue.z = position.z;
            }
        }
        draft.cells = new ArrayList<>(cells.values());
    }

    private void remove(Preset draft, int[] indices) throws IOException {
        if (!activeStructure.isEmpty()) throw new IOException("请在区域总览删除蓝图方块");
        Set<String> complete = completeStructures(draft, indices);
        Set<Position> remove = new HashSet<>();
        for (int index : indices) {
            Cell cell = draft.cells.get(index);
            if (cell.controller) throw new IOException("控制器始终受保护");
            remove.add(Position.of(cell));
        }
        draft.cells.removeIf(cell -> remove.contains(Position.of(cell)));
        draft.structures.removeIf(structure -> complete.contains(structure.id));
        for (int i = 0; i < draft.palette.size(); i++) {
            Cell cell = draft.palette.get(i);
            List<String> members = cell.memberships();
            if (!members.removeAll(complete)) continue;
            Cell copy = HologramSnapshotMetadataSource.copyCell(cell);
            memberships(copy, members);
            draft.palette.set(i, copy);
        }
        draft.aeIssues.removeIf(
            issue -> !"policy".equals(issue.path) && remove.contains(new Position(issue.x, issue.y, issue.z)));
    }

    private static Map<Position, Cell> positions(List<Cell> rows) {
        Map<Position, Cell> result = new LinkedHashMap<>();
        for (Cell cell : rows) result.put(Position.of(cell), cell);
        return result;
    }

    private static void memberships(Cell cell, List<String> members) {
        cell.structureIds = new ArrayList<>(members);
        cell.structureId = members.isEmpty() ? null : members.get(0);
    }

    private static void updateHint(Preset draft, int[] indices, String prompt) throws IOException {
        HintArea hint = new HintArea();
        Cell first = draft.cells.get(indices[0]);
        hint.x1 = hint.x2 = first.x;
        hint.y1 = hint.y2 = first.y;
        hint.z1 = hint.z2 = first.z;
        for (int index : indices) {
            Cell cell = draft.cells.get(index);
            hint.x1 = Math.min(hint.x1, cell.x);
            hint.y1 = Math.min(hint.y1, cell.y);
            hint.z1 = Math.min(hint.z1, cell.z);
            hint.x2 = Math.max(hint.x2, cell.x);
            hint.y2 = Math.max(hint.y2, cell.y);
            hint.z2 = Math.max(hint.z2, cell.z);
        }
        hint.prompt = prompt;
        HologramBlueprintData.updateHintArea(draft.hintAreas, hint);
    }

    public NBTTagCompound snapshotMetadata() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", session);
        state.setString("generation", session);
        state.setLong("planRevision", revision);
        state.setBoolean("localBlueprintDocument", true);
        state.setBoolean("virtual", true);
        state.setBoolean("target", true);
        state.setBoolean("supported", true);
        state.setBoolean("blueprintEditing", true);
        state.setBoolean("blueprintPreview", true);
        state.setBoolean("blueprintSaved", !fileId.isEmpty());
        state.setBoolean("blueprintDirty", dirty());
        state.setBoolean("blueprintDirectSave", true);
        state.setBoolean("blueprintRetainNbt", document.retainNbt);
        state.setBoolean("blueprintRetainCovers", document.retainCovers);
        state.setBoolean("blueprintNbtAll", document.retainedNbtKeys == null);
        state.setTag("blueprintNbtKeys", HologramSessionPolicy.nbtKeys(document.retainedNbtKeys));
        state.setBoolean("editCanUndo", !undo.isEmpty());
        state.setBoolean("editCanRedo", !redo.isEmpty());
        state.setBoolean("editCanPaste", clipboard != null);
        state.setString("editorWorkspace", "blueprint");
        state.setString("workspaceTab", "blueprint");
        state.setString("blueprintSource", fileId.isEmpty() ? "imported" : "saved");
        state.setString("title", "全息构造器");
        state.setString("status", status);
        state.setString("openPurpose", "blueprintEdit");
        state.setString("virtualPresetId", document.id);
        state.setString("virtualPresetName", document.name);
        state.setString("libraryLocalId", fileId);
        state.setString("selectedPresetId", fileId);
        state.setString("blueprintAuthorName", text(document.authorName));
        state.setString("blueprintAuthorUuid", text(document.authorUuid));
        state.setString("blueprintPrompt", text(document.prompt));
        state.setInteger("deidentificationLevel", HologramDeidentification.level(document));
        state.setString("activeStructure", activeStructure);
        state.setInteger("selected", selected);
        state.setIntArray("selectedIndices", selection);
        state.setInteger("scope", scope);
        state.setInteger("mode", mode);
        state.setInteger("layer", layer);
        return state;
    }

    /** Editable rows use precisely the portable document order, including disabled and missing-mod cells. */
    public NBTTagCompound snapshot() throws IOException {
        NBTTagCompound state = snapshotMetadata();
        NBTTagList cells = new NBTTagList();
        for (Cell cell : document.cells) cells.appendTag(cellRow(cell));
        state.setTag("cells", cells);
        NBTTagList structures = new NBTTagList();
        for (Structure structure : document.structures) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", structure.id);
            row.setString("name", text(structure.name));
            row.setString("sourceClass", text(structure.sourceClass));
            row.setString("sourceMod", text(structure.sourceMod));
            row.setInteger("x", structure.x);
            row.setInteger("y", structure.y);
            row.setInteger("z", structure.z);
            row.setBoolean("enabled", structure.enabled);
            HologramAttachmentSnapshot.capture(row, structure);
            structures.appendTag(row);
        }
        state.setTag("structures", structures);
        NBTTagList hints = new NBTTagList();
        for (HintArea hint : document.hintAreas) {
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("bounds", new int[] { hint.x1, hint.y1, hint.z1, hint.x2, hint.y2, hint.z2 });
            row.setString("prompt", text(hint.prompt));
            hints.appendTag(row);
        }
        state.setTag("hintAreas", hints);
        return state;
    }

    private static NBTTagCompound cellRow(Cell cell) throws IOException {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", cell.x);
        row.setInteger("y", cell.y);
        row.setInteger("z", cell.z);
        row.setInteger("dx", cell.x);
        row.setInteger("dy", cell.y);
        row.setInteger("dz", cell.z);
        ItemStack stack = cell.stack();
        Block block = text(cell.block).isEmpty() && stack != null ? Block.getBlockFromItem(stack.getItem())
            : cell.block();
        boolean missing = cell.missing() || block == null || block == Blocks.air && !"minecraft:air".equals(cell.block);
        row.setBoolean("missingBlock", missing);
        row.setString("wantId", missing ? "minecraft:air" : text(cell.block));
        row.setInteger("wantMeta", cell.metadata);
        row.setInteger("targetFacing", cell.facing);
        row.setString("previewItem", missing ? "" : text(cell.item));
        row.setInteger("previewDamage", cell.damage);
        if (!missing) {
            NBTTagCompound tile = HologramBlueprintNbt.decode(cell.tileNbt),
                item = HologramBlueprintNbt.decode(cell.itemNbt);
            if (tile != null) row.setTag("previewTile", tile);
            if (item != null) row.setTag("previewItemTag", item);
        }
        row.setString("status", "preview");
        row.setString("operation", "EDIT");
        row.setString("exactIdentity", text(cell.item).isEmpty() ? text(cell.block) : cell.item);
        row.setString("prompt", text(cell.prompt));
        row.setString("structureId", text(cell.structureId));
        row.setTag("structureIds", HologramSessionPolicy.nbtKeys(cell.memberships()));
        row.setBoolean("anchor", cell.controller);
        row.setBoolean("inScope", true);
        row.setBoolean("excluded", cell.excluded);
        NBTTagList candidates = new NBTTagList();
        if (cell.allowedTargets != null) for (HologramPresets.Target target : cell.allowedTargets) {
            ItemStack candidate = target.stack();
            if (candidate != null) candidates.appendTag(candidate.writeToNBT(new NBTTagCompound()));
        }
        row.setTag("candidates", candidates);
        row.setBoolean("candidateComplete", cell.allowedTargets != null);
        if (stack != null && stack.getItem() instanceof gregtech.common.blocks.ItemMachines
            && !HologramBlockTransforms.isPipe(stack)) {
            int[] facings = java.util.stream.IntStream.range(0, 6)
                .filter(side -> HologramCellPolicy.virtualFrontAllowed(stack, side))
                .toArray();
            row.setIntArray("hatchFacings", facings);
        }
        return row;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
