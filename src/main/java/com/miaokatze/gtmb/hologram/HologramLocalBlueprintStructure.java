// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.miaokatze.gtmb.hologram.HologramPresets.Cell;
import com.miaokatze.gtmb.hologram.HologramPresets.Preset;
import com.miaokatze.gtmb.hologram.HologramPresets.Structure;
import com.miaokatze.gtmb.hologram.HologramPresets.Target;

import gregtech.api.GregTechAPI;
import gregtech.common.blocks.ItemMachines;

/** Recognizes a file-owned structure and reconciles only the selected source's declared coordinates. */
final class HologramLocalBlueprintStructure {

    @Desugar
    record Position(int x, int y, int z) {

        static Position of(Cell cell) {
            return new Position(cell.x, cell.y, cell.z);
        }
    }

    private HologramLocalBlueprintStructure() {}

    static NBTTagCompound settings(Structure source) {
        NBTTagCompound result = new NBTTagCompound(), channels = new NBTTagCompound();
        result.setInteger("main", Math.max(1, source.main));
        result.setBoolean("mainExplicit", source.mainExplicit);
        result.setBoolean("noHatches", source.noHatches);
        if (source.channels != null) source.channels.forEach((id, value) -> {
            if (id != null && value != null) channels.setInteger(id, value);
        });
        result.setTag("channels", channels);
        return result;
    }

    static NBTTagCompound requested(Structure source, NBTTagCompound request) throws IOException {
        NBTTagCompound result = settings(source);
        if (request.hasKey("main")) {
            if (!request.hasKey("main", 3) || request.getInteger("main") < 1)
                throw new IOException("主信号必须为正整数");
            if (result.getInteger("main") != request.getInteger("main")) {
                result.setInteger("main", request.getInteger("main"));
                result.setBoolean("mainExplicit", true);
            }
        }
        if (request.hasKey("channels")) {
            if (!request.hasKey("channels", 10) || !HologramSessionPolicy.validChannels(request.getCompoundTag("channels")))
                throw new IOException("结构信道无效");
            result.setTag("channels", request.getCompoundTag("channels").copy());
        }
        if (request.hasKey("noHatches", 1)) result.setBoolean("noHatches", request.getBoolean("noHatches"));
        return result;
    }

    static void applyConfiguration(Structure source, NBTTagCompound settings) {
        source.main = settings.getInteger("main");
        source.mainExplicit = settings.getBoolean("mainExplicit");
        source.noHatches = settings.getBoolean("noHatches");
        source.channels.clear();
        NBTTagCompound channels = settings.getCompoundTag("channels");
        for (Object key : channels.func_150296_c()) source.channels.put((String) key, channels.getInteger((String) key));
    }

    static HologramLocalControllerCapture capture(Preset document, Structure source, NBTTagCompound settings)
        throws IOException {
        if (!HologramLocalControllerCapture.validSettings(settings)) throw new IOException("保存的结构信道无效");
        if (source.textBoxNbt != null && !source.textBoxNbt.isEmpty())
            throw new IOException("所选对象没有原生结构信道");
        Cell anchor = null;
        for (Cell cell : document.cells)
            if (cell.controller && cell.x == source.x && cell.y == source.y && cell.z == source.z
                && (cell.belongsTo(source.id) || cell.memberships().isEmpty())) {
                if (anchor != null) throw new IOException("蓝图结构的控制器位置不唯一");
                anchor = cell;
            }
        if (anchor == null) throw new IOException("蓝图缺少该结构的控制器");
        ItemStack item = anchor.stack();
        int id = source.controllerId;
        if (item != null && item.getItem() instanceof ItemMachines) {
            if (id >= 0 && id != item.getItemDamage()) throw new IOException("蓝图来源的控制器物品身份不匹配");
            id = item.getItemDamage();
        } else if (id >= 0) throw new IOException("蓝图缺少已注册的控制器物品");
        Block block = anchor.block();
        int metadata = anchor.metadata;
        if (block == null && (anchor.block == null || anchor.block.isEmpty()) && item != null) {
            block = Block.getBlockFromItem(item.getItem());
            if (id >= 0 && id < GregTechAPI.METATILEENTITIES.length && GregTechAPI.METATILEENTITIES[id] != null)
                metadata = GregTechAPI.METATILEENTITIES[id].getTileEntityBaseType();
            else metadata = item.getItem().getMetadata(item.getItemDamage());
        }
        if (id >= 0 && block != GregTechAPI.sBlockMachines) throw new IOException("蓝图来源的控制器方块身份不匹配");
        NBTTagCompound state = HologramBlueprintNbt.decode(anchor.tileNbt);
        if (id >= 0 && (state == null || state.hasNoTags())) {
            state = HologramBlueprintNbt.decode(anchor.itemNbt);
            if (state != null && state.hasKey(HologramRecovery.TAG, 10)) {
                NBTTagCompound seal = state.getCompoundTag(HologramRecovery.TAG);
                if (id < 0 || !HologramRecovery.matchesEnvelope(seal, anchor.block, anchor.item, anchor.damage)
                    || seal.getInteger("mID") != id) throw new IOException("蓝图控制器封存身份不匹配");
                state = seal.getCompoundTag("tile");
            }
        }
        state = HologramBlueprintNbt.nativePayload(state);
        if (id >= 0 && state != null && state.hasKey("mID") && (!state.hasKey("mID", 99) || state.getInteger("mID") != id))
            throw new IOException("蓝图控制器 NBT 身份不匹配");
        ExtendedFacing facing = document.controllerFacing == null ? ExtendedFacing.DEFAULT
            : HologramVirtualTransform.controller(document);
        int front = anchor.facing;
        if (id >= 0 && state != null && state.hasKey("mID", 99)) {
            if (front < 0 && state.hasKey("mFacing", 99)) front = state.getInteger("mFacing");
            int rotation = state.hasKey("eRotation", 99) ? state.getInteger("eRotation") : -1;
            int flip = state.hasKey("eFlip", 99) ? state.getInteger("eFlip") : -1;
            if (rotation >= 0 && rotation < Rotation.VALUES.length) facing = facing.with(Rotation.byIndex(rotation));
            if (flip >= 0 && flip < Flip.VALUES.length) facing = facing.with(Flip.byIndex(flip));
        }
        if (front >= 0 && front < 6) facing = facing.with(ForgeDirection.getOrientation(front));
        return HologramLocalControllerCapture.forBlueprint(
            block, metadata, id, HologramBlueprintData.sourceClass(document, source), facing, state, settings);
    }

    static Map<Position, Cell> declared(Structure source, NBTTagCompound snapshot) throws IOException {
        Map<Position, Cell> result = new LinkedHashMap<>();
        NBTTagList rows = snapshot.getTagList("cells", 10);
        for (int index = 0; index < rows.tagCount(); index++) {
            NBTTagCompound row = rows.getCompoundTagAt(index);
            String target = row.getString("wantId");
            if (row.getBoolean("anchor") || row.getBoolean("missingBlock") || row.getBoolean("iconOnly")
                || target.isEmpty()) continue;
            int x = offset(source.x, row.getInteger("dx"), 60_000_000);
            int y = offset(source.y, row.getInteger("dy"), 256);
            int z = offset(source.z, row.getInteger("dz"), 60_000_000);
            ItemStack stack = ItemStack.loadItemStackFromNBT(row.getCompoundTag("targetStack"));
            NBTTagList candidates = row.getTagList("candidates", 10);
            List<ItemStack> values = new ArrayList<>(candidates.tagCount());
            for (int i = 0; i < candidates.tagCount(); i++) {
                ItemStack candidate = ItemStack.loadItemStackFromNBT(candidates.getCompoundTagAt(i));
                if (candidate != null) values.add(candidate);
            }
            int metadata = row.getInteger("wantMeta"), facing = row.getInteger("targetFacing");
            if (snapshot.getBoolean("noHatches") && HologramCellPolicy.hatchTier(stack) != Integer.MAX_VALUE) {
                for (ItemStack alternative : values) {
                    Block casing = Block.getBlockFromItem(alternative.getItem());
                    if (alternative.getItem() instanceof ItemMachines || casing == null || casing == Blocks.air) continue;
                    stack = alternative;
                    target = Block.blockRegistry.getNameForObject(casing);
                    metadata = alternative.getItem().getMetadata(alternative.getItemDamage());
                    facing = -1;
                    break;
                }
            }
            // This snapshot is produced by this editor's own detached controller capture, not read from the file.
            // Keep the editable, itemless geometry; the portable data does not convey construction authority.
            if (stack == null && !"minecraft:air".equals(target) && !HologramNativeSourceWater.previewRow(row)) continue;
            Cell cell = stack == null ? new Cell() : HologramPresets.cell(stack, x, y, z, facing, false);
            cell.x = x;
            cell.y = y;
            cell.z = z;
            cell.block = target;
            cell.metadata = metadata;
            cell.defaultItem = cell.item;
            cell.defaultDamage = cell.damage;
            cell.structureId = source.id;
            cell.structureRole = row.getString("role");
            cell.structureFamily = row.getString("family");
            cell.structureHint = row.getString("prompt");
            cell.structureCandidatesComplete = row.getBoolean("candidateComplete");
            if (row.hasKey("hatchFacings", 11)) cell.structureHatchFacings = row.getIntArray("hatchFacings");
            if (!values.isEmpty()) cell.allowedTargets = HologramBlueprintData.savedTargets(values);
            if (result.put(Position.of(cell), cell) != null) throw new IOException("原生声明包含重复的蓝图位置");
        }
        return result;
    }

    private static int offset(int origin, int relative, int limit) throws IOException {
        long value = (long) origin + relative;
        if (Math.abs(value) > limit) throw new IOException("新结构超出蓝图坐标范围");
        return (int) value;
    }

    static List<Cell> merge(List<Cell> saved, String group, Map<Position, Cell> before, Map<Position, Cell> after,
        boolean allowLegacyScope, UnaryOperator<Cell> copy, Consumer<Cell> replacedTargets) throws IOException {
        List<Cell> merged = new ArrayList<>(saved.size());
        Map<Position, Cell> additions = new LinkedHashMap<>(after);
        boolean legacyScope = allowLegacyScope && exactLegacyScope(saved, group, before);
        boolean knownScope = false;
        for (Cell original : saved) {
            Position position = Position.of(original);
            Cell replacement = additions.remove(position), previous = before.get(position);
            boolean inferred = legacyScope && previous != null && original.memberships().isEmpty();
            if (original.controller || !original.belongsTo(group) && !inferred) {
                merged.add(original);
                continue;
            }
            knownScope |= previous != null;
            List<String> members = original.memberships();
            if (members.size() > 1) {
                if (replacement == null && previous != null) {
                    Cell retained = copy.apply(original);
                    members.remove(group);
                    retained.structureId = members.get(0);
                    retained.structureIds = members;
                    retained.structureCandidatesComplete = false;
                    merged.add(retained);
                } else merged.add(original);
                continue;
            }
            boolean userMaterial = previous == null || !samePhysical(original, previous)
                || present(original.tileNbt) || present(original.itemNbt);
            if (replacement == null) {
                if (userMaterial || original.excluded || present(original.prompt)) {
                    Cell retained = copy.apply(original);
                    retained.structureId = null;
                    retained.structureIds = null;
                    retained.structureRole = retained.structureFamily = retained.structureHint = null;
                    retained.structureCandidatesComplete = null;
                    retained.structureHatchFacings = null;
                    retained.allowedTargets = null;
                    replacedTargets.accept(retained);
                    retained.defaultItem = null;
                    merged.add(retained);
                }
                continue;
            }
            Cell next = copy.apply(original);
            if (inferred) next.structureId = group;
            if (!userMaterial) {
                next.block = replacement.block;
                next.item = replacement.item;
                next.damage = replacement.damage;
                next.metadata = replacement.metadata;
                next.facing = replacement.facing;
            }
            next.defaultItem = replacement.defaultItem;
            next.defaultDamage = replacement.defaultDamage;
            next.structureRole = replacement.structureRole;
            next.structureFamily = replacement.structureFamily;
            next.structureHint = replacement.structureHint;
            next.structureCandidatesComplete = replacement.structureCandidatesComplete;
            next.structureHatchFacings = replacement.structureHatchFacings;
            next.allowedTargets = replacement.allowedTargets;
            replacedTargets.accept(next);
            merged.add(next);
        }
        if (!knownScope) throw new IOException("未能核实原蓝图的结构归属，已保留原有格子和设置");
        for (Map.Entry<Position, Cell> addition : additions.entrySet())
            if (!before.containsKey(addition.getKey())) merged.add(addition.getValue());
        HologramBlueprintLimits.require(merged.size());
        return merged;
    }

    private static boolean exactLegacyScope(List<Cell> saved, String group, Map<Position, Cell> before) {
        int exact = 0;
        for (Cell cell : saved) {
            if (cell.controller) continue;
            if (cell.belongsTo(group)) return false;
            Cell previous = before.get(Position.of(cell));
            if (previous != null) {
                if (!cell.memberships().isEmpty() || !samePhysical(cell, previous)
                    || present(cell.tileNbt) || present(cell.itemNbt)) return false;
                exact++;
            }
        }
        return !before.isEmpty() && exact == before.size();
    }

    private static boolean samePhysical(Cell left, Cell right) {
        if (!Objects.equals(left.item, right.item) || left.damage != right.damage || left.facing != right.facing)
            return false;
        if (present(left.block)) return left.block.equals(right.block) && left.metadata == right.metadata;
        return left.item != null;
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }
}
