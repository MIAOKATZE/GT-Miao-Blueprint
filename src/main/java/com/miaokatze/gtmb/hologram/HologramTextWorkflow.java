package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Atomic authoring operation: validate generated geometry before replacing any workspace content. */
final class HologramTextWorkflow {

    private HologramTextWorkflow() {}

    /** Text owns one rigid orientation; never feed its hosts to the per-cell rotation loop. */
    static boolean rotateSelected(HologramSession s, NBTTagCompound action) {
        if (s.capture == null || s.virtualPreset == null) return false;
        int selected = action.hasKey("selected", 3) ? action.getInteger("selected") : s.selected;
        if (selected < 0 || selected >= s.capture.cells.size()) return false;
        HologramCapture.Cell cell = s.capture.cells.get(selected);
        for (HologramPresets.Structure group : s.virtualPreset.structures) {
            if (!cell.belongsTo(group.id) || group.textBoxNbt == null || group.textBoxNbt.isEmpty()) continue;
            try {
                HologramTextBox text = HologramTextBox.read(definition(group));
                text.turn(action.getBoolean("vertical"));
                NBTTagCompound request = (NBTTagCompound) action.copy();
                request.setTag("textBox", text.write());
                apply(s, request);
            } catch (IOException failure) {
                s.status = "文字旋转被拒绝：" + failure.getMessage();
                HologramSnapshots.send(s);
            }
            return true;
        }
        return false;
    }

    static void apply(HologramSession s, NBTTagCompound request) {
        try {
            if (s.attachmentTask != null) throw new IOException("正在处理附加对象，请等待或取消");
            validateAdmission(s, request.getLong("planRevision"));
            s.attachmentTask = new Task(s, request);
            s.attachmentTask.progress();
        } catch (IOException | RuntimeException | LinkageError failure) {
            acknowledge(s, request.getLong("textRequest"), false, failure.getMessage());
            HologramSnapshots.send(s);
        }
    }

    private static void validateAdmission(HologramSession s, long revision) throws IOException {
        if ("projection".equals(HologramEditorWorkspaceRegistry.mode(s)))
            throw new IOException("投影模式不支持文字和图片；请先切换到蓝图模式");
        if (s.cancelled || s.virtualPlacement
            || s.presetBrowseForPlacement
            || s.job == 1
            || s.job == 2
            || s.job == 4
            || s.capturePending
            || s.capture != null && (s.capture.pending || s.capture.incomplete)
            || s.localPlanPending
            || !s.player.capabilities.allowEdit
            || !HologramSessionPolicy.valid(s)) throw new IOException("附加对象仅用于空工作区、结构编辑及放置前的蓝图编辑");
        if (revision != s.planRevision) throw new IOException("蓝图已变化，请重新确认附加位置");
    }

    private static void acknowledge(HologramSession s, long request, boolean accepted, String message) {
        NBTTagCompound ack = new NBTTagCompound();
        ack.setLong("request", request);
        ack.setBoolean("accepted", accepted);
        s.status = message == null ? "附加对象处理失败，请重试" : message;
        ack.setString("message", s.status);
        s.textPlacementAck = ack;
    }

    /** Stage the actual projection before geometry edits; rejected additions retain the physical source unchanged. */
    static void stageAttachment(HologramSession s, HologramPresets.Preset preset, List<HologramCapture.Cell> additions,
        Set<String> remove, java.util.Map<String, Integer> payloadSizes) throws IOException {
        HologramCapture previousCapture = s.capture;
        HologramPresets.Preset previousPreset = s.virtualPreset;
        boolean previousVirtual = s.virtual;
        int previousSchema = preset.schema;
        String previousName = preset.name;
        java.util.Map<String, ItemStack> projectedTargets = null;
        if (!previousVirtual && previousPreset == null) {
            projectedTargets = new java.util.HashMap<>();
            for (HologramPresets.Cell cell : preset.cells)
                projectedTargets.put((s.x + cell.x) + "," + (s.y + cell.y) + "," + (s.z + cell.z), cell.stack());
        }
        HologramCapture stagingCapture = previousCapture == null
            ? new HologramCapture(null, HologramSessionPolicy.trigger(s))
            : !previousVirtual && previousPreset == null
                ? HologramBlueprintData.projectedCapture(previousCapture, preset, s.x, s.y, s.z)
                : previousCapture;
        s.capture = stagingCapture;
        s.virtual = true;
        s.virtualPreset = preset;
        preset.schema = 2;
        if (preset.name == null) preset.name = "附加蓝图";
        try {
            HologramBlueprintEditor.stageGeometry(
                s,
                additions,
                remove,
                false,
                java.util.Collections.emptyMap(),
                false,
                payloadSizes,
                projectedTargets);
        } catch (IOException | RuntimeException | LinkageError failure) {
            if (s.capture == stagingCapture) {
                s.capture = previousCapture;
                s.virtualPreset = previousPreset;
                s.virtual = previousVirtual;
                preset.schema = previousSchema;
                preset.name = previousName;
            }
            throw failure;
        }
    }

    /** A private draft is staged over ticks; only the final validated batch changes the workspace. */
    static final class Task {

        private final HologramSession s;
        private final HologramCapture capture;
        private final HologramTextBox text;
        private final HologramPresets.Preset preset;
        private final HologramPresets.Structure existing;
        private final HologramAttachmentGeometry.Job geometry;
        private final List<HologramCapture.Cell> additions = new ArrayList<>();
        private final List<HologramTextBox.Cell> exportedGeometry = new ArrayList<>();
        private final Set<String> remove = new HashSet<>();
        private final Set<String> occupiedPositions = new HashSet<>();
        private final Block host;
        private final long revision, request;
        private int cursor;
        private long lastProgress;

        Task(HologramSession s, NBTTagCompound action) throws IOException {
            this.s = s;
            capture = s.capture;
            revision = s.planRevision;
            request = action.getLong("textRequest");
            text = HologramTextBox.read(action.getCompoundTag("textBox"));
            if (text.id.isEmpty()) throw new IOException("缺少附加对象标识");
            if (text.picture == null) validateMaterial(text.material, text.metadata);
            else for (int i = 0; i < text.picture.paletteSize(); i++)
                validateMaterial(text.picture.material(i), text.picture.metadata(i));
            host = Block.getBlockFromName("littletiles:BlockLittleTiles");
            if (host == null || host == Blocks.air || !HologramNativePlacement.supported(host))
                throw new IOException("需要 LittleTiles 1.6.59 兼容方块");
            preset = s.virtualPreset != null ? s.virtualPreset
                : capture == null ? new HologramPresets.Preset() : HologramBlueprintProjection.blueprint(s, "附加蓝图");
            HologramPresets.Structure found = null;
            for (HologramPresets.Structure group : preset.structures) if (text.id.equals(group.id)) found = group;
            existing = found;
            if (existing != null && (existing.textBoxNbt == null || existing.textBoxNbt.isEmpty()))
                throw new IOException("目标不是附加对象");
            if (existing == null && preset.structures.size() >= 256) throw new IOException("结构与附加对象数量超过上限");
            List<String> retained = new ArrayList<>();
            boolean projectsPhysical = !s.virtual && s.virtualPreset == null && capture != null;
            if (capture != null) for (int i = 0; i < capture.cells.size(); i++) {
                HologramCapture.Cell cell = capture.cells.get(i);
                if (cell.belongsTo(text.id)) remove.add(HologramValues.position(cell));
                else {
                    if (occupiesBlueprintPosition(cell, HologramCellPolicy.targetFor(s, cell, i)))
                        occupiedPositions.add(HologramValues.position(cell));
                    if (!projectsPhysical) {
                        retained.add(cell.tileNbt);
                        retained.add(cell.itemNbt);
                    }
                }
            }
            // Initial physical conversion commits the projection's refreshed payload, not the old capture payload.
            if (projectsPhysical) for (HologramPresets.Cell cell : preset.cells) {
                retained.add(cell.tileNbt);
                retained.add(cell.itemNbt);
            }
            for (HologramPresets.Cell cell : preset.palette) {
                retained.add(cell.tileNbt);
                retained.add(cell.itemNbt);
            }
            for (HologramPresets.Structure group : preset.structures)
                if (group != existing) retained.add(group.textBoxNbt);
            geometry = HologramAttachmentGeometry.submit(text, retained);
        }

        boolean ready() {
            return geometry.done;
        }

        void cancel(String reason) {
            geometry.cancel();
            if (s.attachmentTask != this) return;
            s.attachmentTask = null;
            acknowledge(s, request, false, reason);
            HologramSnapshots.send(s);
        }

        void progress() {
            NBTTagCompound ack = new NBTTagCompound();
            ack.setLong("request", request);
            ack.setBoolean("pending", true);
            ack.setString("phase", geometry.done ? "validation" : geometry.phase);
            ack.setLong("done", geometry.done ? cursor : geometry.completed);
            ack.setLong("total", geometry.done ? geometry.cells.size() : geometry.total);
            s.status = "后台处理" + text.noun()
                + "："
                + (geometry.done ? "核对位置 " + cursor + "/" + geometry.cells.size()
                    : "生成与合并 " + geometry.completed + "/" + geometry.total);
            ack.setString("message", s.status);
            s.textPlacementAck = ack;
            HologramSnapshots.sendUi(s);
            lastProgress = System.nanoTime();
        }

        void tick() {
            try {
                validateAdmission(s, revision);
                if (s.capture != capture || HologramWorkspace.find(s.player.getUniqueID()) != s)
                    throw new IOException("工作区已变化，旧附加任务已取消");
                if (!geometry.done) {
                    if (System.nanoTime() - lastProgress > 500_000_000L) progress();
                    return;
                }
                if (geometry.failure != null) throw new IOException(geometry.failure.getMessage(), geometry.failure);
                long deadline = HologramWorkBudget.deadline(2_000_000L);
                int units = 0;
                while (cursor < geometry.cells.size() && units++ < 8 && System.nanoTime() < deadline)
                    stage(geometry.cells.get(cursor++));
                if (cursor < geometry.cells.size()) {
                    if (System.nanoTime() - lastProgress > 500_000_000L) progress();
                    return;
                }
                commit();
            } catch (IOException | RuntimeException | LinkageError failure) {
                cancel(failure.getMessage());
            }
        }

        private void stage(HologramTextBox.Cell cell) throws IOException {
            int wx = s.x + cell.x, wy = s.y + cell.y, wz = s.z + cell.z;
            HologramBlueprintEditor.validateBlueprintCoordinate(s, wx, wy, wz);
            if (!HologramSessionPolicy.previewReach(s.player, wx, wy, wz))
                throw new IOException(text.noun() + "位置超出编辑距离");
            String key = wx + "," + wy + "," + wz;
            HologramCapture.Cell occupied = capture == null ? null : capture.positions.get(key);
            if (occupiedPositions.contains(key)) throw new IOException(text.noun() + "与工作区内容冲突，请移动后确认");
            // These are virtual blueprint coordinates, not a request to place blocks at the player's site.
            int paint = cell.boxes[0];
            String materialName = cell.materials[paint];
            int metadata = cell.metadata[paint];
            Block material = Block.getBlockFromName(materialName);
            boolean ordinary = cell.fullBlock && metadata <= 15
                && material.isOpaqueCube()
                && material.renderAsNormalBlock();
            HologramCapture.Cell added = new HologramCapture.Cell(null, wx, wy, wz);
            added.block = ordinary ? material : host;
            added.meta = ordinary ? metadata : 0;
            added.blueprintBlock = ordinary ? materialName : "littletiles:BlockLittleTiles";
            added.tileNbt = ordinary ? "" : cell.encodedTile();
            if (!ordinary) exportedGeometry.add(cell);
            // The first exact child supplies only the primary sample; the full payload remains payable later.
            added.actualStack = ordinary ? new ItemStack(material, 1, metadata)
                : HologramNativePlacement.sample(host, 0, cell.sampleTile());
            if (added.actualStack == null) throw new IOException("LittleTiles 材料样本生成失败");
            added.blueprintItem = Item.itemRegistry.getNameForObject(added.actualStack.getItem());
            added.blueprintDamage = added.actualStack.getItemDamage();
            added.sourceClass = "";
            added.sourceWarning = "";
            HologramBlueprintData.setMemberships(added, java.util.Collections.singletonList(text.id));
            additions.add(added);
            if (occupied != null) remove.add(key);
        }

        private void commit() throws IOException {
            // The task owns this plan revision. Physical site checks belong to actual construction, not authoring.
            for (HologramCapture.Cell cell : additions) {
                if (!HologramSessionPolicy.previewReach(s.player, cell.x, cell.y, cell.z))
                    throw new IOException("附加对象位置超出编辑距离，请移动后重新确认");
            }
            stageAttachment(s, preset, additions, remove, geometry.payloadSizes);
            for (HologramTextBox.Cell cell : exportedGeometry) cell.exportEncodedTile();
            HologramPresets.Structure group = existing == null ? new HologramPresets.Structure() : existing;
            group.id = text.id;
            String title = text.picture == null ? text.text : text.picture.filename;
            group.name = text.noun() + " · " + title.substring(0, Math.min(28, title.length()));
            group.sourceClass = "";
            group.sourceMod = "gtmb";
            group.x = text.x;
            group.y = text.y;
            group.z = text.z;
            group.textBoxNbt = geometry.exportDefinition();
            geometry.close();
            if (existing == null) preset.structures.add(group);
            s.target = null;
            s.machine = null;
            s.constructable = null;
            s.blueprintSource = "draft";
            s.attachmentTask = null;
            HologramAuthoringWorkflow.blueprintEdited(s, "geometry");
            s.openPurpose = "blueprintEdit";
            acknowledge(s, request, true, text.noun() + "已加入工作区；点击对象整体编辑或移动，保存后随蓝图保留");
            s.textPlacementAck.setString("id", text.id);
            s.localEdits.invalidate();
            HologramSnapshots.send(s);
        }
    }

    static boolean occupiesBlueprintPosition(HologramCapture.Cell cell, ItemStack target) {
        return !cell.excluded && (cell.unknown || cell.block != Blocks.air || target != null);
    }

    private static void validateMaterial(String name, int metadata) throws IOException {
        Block block = Block.getBlockFromName(name);
        if (block == null || block == Blocks.air
            || block.hasTileEntity(metadata)
            || Item.getItemFromBlock(block) == null)
            throw new IOException("附加材料不可用：" + name + "；混凝土组合需要 Et Futurum Requiem");
    }

    static NBTTagCompound definition(HologramPresets.Structure structure) throws IOException {
        NBTTagCompound result = HologramBlueprintNbt.decode(structure.textBoxNbt);
        result.setString("id", structure.id);
        result.setInteger("x", structure.x);
        result.setInteger("y", structure.y);
        result.setInteger("z", structure.z);
        return result;
    }
}
