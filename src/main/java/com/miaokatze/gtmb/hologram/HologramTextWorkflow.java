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
        NBTTagCompound ack = new NBTTagCompound();
        ack.setLong("request", request.getLong("textRequest"));
        try {
            if (s.virtualPlacement || s.presetBrowseForPlacement
                || s.job == 1
                || s.job == 2
                || s.job == 4
                || s.capturePending
                || s.capture != null && (s.capture.pending || s.capture.incomplete)
                || s.localPlanPending
                || !s.player.capabilities.allowEdit
                || !HologramSessionPolicy.valid(s)) throw new IOException("文字仅用于空工作区、结构编辑及放置前的蓝图编辑");
            if (request.getLong("planRevision") != s.planRevision) throw new IOException("蓝图已变化，请重新确认文字位置");
            HologramTextBox text = HologramTextBox.read(request.getCompoundTag("textBox"));
            if (text.id.isEmpty()) throw new IOException("缺少文字对象标识");
            Block material = Block.getBlockFromName(text.material);
            if (material == null || material == Blocks.air
                || material.hasTileEntity(text.metadata)
                || Item.getItemFromBlock(material) == null) throw new IOException("文字材料必须是无容器的普通方块");
            Block host = Block.getBlockFromName("littletiles:BlockLittleTiles");
            if (host == null || host == Blocks.air || !HologramNativePlacement.supported(host))
                throw new IOException("需要 LittleTiles 1.6.59 兼容方块");
            HologramPresets.Preset preset = s.virtualPreset != null ? s.virtualPreset
                : s.capture == null ? new HologramPresets.Preset() : HologramBlueprintProjection.blueprint(s, "文字蓝图");
            HologramPresets.Structure existing = null;
            for (HologramPresets.Structure structure : preset.structures)
                if (text.id.equals(structure.id)) existing = structure;
            if (existing != null && (existing.textBoxNbt == null || existing.textBoxNbt.isEmpty()))
                throw new IOException("目标不是文字对象");
            if (existing == null && preset.structures.size() >= 256) throw new IOException("结构与文字对象数量超过上限");
            List<HologramTextBox.Cell> generated = text.generate();
            List<HologramCapture.Cell> additions = new ArrayList<>();
            Set<String> remove = new HashSet<>();
            if (s.capture != null) for (HologramCapture.Cell cell : s.capture.cells)
                if (cell.belongsTo(text.id)) remove.add(HologramValues.position(cell));
            for (HologramTextBox.Cell cell : generated) {
                int wx = s.x + cell.x, wy = s.y + cell.y, wz = s.z + cell.z;
                HologramBlueprintEditor.validateBlueprintCoordinate(s, wx, wy, wz);
                if (!HologramSessionPolicy.previewReach(s.player, wx, wy, wz)) throw new IOException("文字位置超出编辑距离");
                String key = wx + "," + wy + "," + wz;
                HologramCapture.Cell occupied = s.capture == null ? null : s.capture.positions.get(key);
                if (occupied != null && !remove.contains(key) && occupied.block != Blocks.air && !occupied.excluded)
                    throw new IOException("文字与工作区内容冲突，请移动后确认");
                if (!s.player.worldObj.blockExists(wx, wy, wz) || !s.player.worldObj.isAirBlock(wx, wy, wz))
                    throw new IOException("文字目标必须是已加载的空气位置，请移动后确认");
                HologramCapture.Cell added = new HologramCapture.Cell(null, wx, wy, wz);
                boolean ordinary = cell.fullBlock && material.isOpaqueCube() && material.renderAsNormalBlock();
                added.block = ordinary ? material : host;
                added.meta = ordinary ? text.metadata : 0;
                added.blueprintBlock = ordinary ? text.material : "littletiles:BlockLittleTiles";
                added.tileNbt = ordinary ? "" : HologramBlueprintNbt.encodeCaptured(cell.tile);
                added.actualStack = ordinary ? new ItemStack(material, 1, text.metadata)
                    : HologramNativePlacement.sample(host, 0, cell.tile);
                if (added.actualStack == null) throw new IOException("LittleTiles 材料样本生成失败");
                added.blueprintItem = Item.itemRegistry.getNameForObject(added.actualStack.getItem());
                added.blueprintDamage = added.actualStack.getItemDamage();
                added.sourceClass = "";
                added.sourceWarning = "";
                HologramBlueprintData.setMemberships(added, java.util.Collections.singletonList(text.id));
                additions.add(added);
                if (occupied != null && (occupied.block == Blocks.air || occupied.excluded)) remove.add(key);
            }
            // Everything above is staging. Failed collision checks retain the original text and draft.
            if (s.capture == null) s.capture = new HologramCapture(null, HologramSessionPolicy.trigger(s));
            s.virtual = true;
            s.virtualPreset = preset;
            preset.schema = 2;
            if (preset.name == null) preset.name = "文字蓝图";
            HologramBlueprintEditor.stageGeometry(s, additions, remove, false, java.util.Collections.emptyMap(), false);
            HologramPresets.Structure group = existing == null ? new HologramPresets.Structure() : existing;
            group.id = text.id;
            group.name = "文字 · " + text.text.substring(0, Math.min(28, text.text.length()));
            group.sourceClass = "";
            group.sourceMod = "gtmb";
            group.x = text.x;
            group.y = text.y;
            group.z = text.z;
            group.textBoxNbt = HologramBlueprintNbt.encodeCaptured(text.write());
            if (existing == null) preset.structures.add(group);
            s.target = null;
            s.machine = null;
            s.constructable = null;
            s.blueprintSource = "draft";
            HologramAuthoringWorkflow.blueprintEdited(s, "geometry");
            s.openPurpose = "blueprintEdit";
            s.status = "文字已加入工作区；点击文字整体编辑或移动，保存后随蓝图保留";
            ack.setBoolean("accepted", true);
            ack.setString("id", text.id);
        } catch (IOException | RuntimeException | LinkageError failure) {
            s.status = failure.getMessage() == null ? "文字生成失败，请调整后重试" : failure.getMessage();
            ack.setBoolean("accepted", false);
        }
        ack.setString("message", s.status);
        s.textPlacementAck = ack;
        s.localEdits.invalidate();
        HologramSnapshots.send(s);
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
