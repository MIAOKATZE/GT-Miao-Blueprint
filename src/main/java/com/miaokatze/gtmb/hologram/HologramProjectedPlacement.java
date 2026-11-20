// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.block.BlockHay;
import net.minecraft.block.BlockLog;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.api.CutTransforms;

import gregtech.api.GregTechAPI;
import gregtech.common.blocks.ItemMachines;

/** Compiles a private portable document to placement data. The plan never contains original tile or item NBT. */
public final class HologramProjectedPlacement {

    private static final Set<String> SIMPLE_VARIANTS = new HashSet<>(
        Arrays.asList(
            "minecraft:bedrock",
            "minecraft:brick_block",
            "minecraft:clay",
            "minecraft:coal_block",
            "minecraft:cobblestone",
            "minecraft:dirt",
            "minecraft:diamond_block",
            "minecraft:emerald_block",
            "minecraft:end_stone",
            "minecraft:glass",
            "minecraft:glowstone",
            "minecraft:gold_block",
            "minecraft:gravel",
            "minecraft:hardened_clay",
            "minecraft:ice",
            "minecraft:iron_block",
            "minecraft:lapis_block",
            "minecraft:mossy_cobblestone",
            "minecraft:nether_brick",
            "minecraft:netherrack",
            "minecraft:obsidian",
            "minecraft:packed_ice",
            "minecraft:planks",
            "minecraft:redstone_block",
            "minecraft:sand",
            "minecraft:sandstone",
            "minecraft:stained_glass",
            "minecraft:stained_hardened_clay",
            "minecraft:stone",
            "minecraft:stonebrick",
            "minecraft:wool"));
    private static final Set<String> GT_IDENTITY_KEYS = new HashSet<>(
        Arrays.asList("id", "mID", "x", "y", "z", "mFacing"));

    private HologramProjectedPlacement() {}

    @Desugar
    public record Cell(int x, int y, int z, String block, int metadata, String item, int damage, int facing) {}

    @Desugar
    public record Need(String item, int damage, int count) {}

    @Desugar
    public record Plan(List<Cell> cells, List<Need> needs) {

        public Plan {
            cells = Collections.unmodifiableList(new ArrayList<>(cells));
            needs = Collections.unmodifiableList(new ArrayList<>(needs));
        }
    }

    @Desugar
    private record Material(String item, int damage) {}

    public static Plan compile(byte[] portableSource, int[] anchor, int[] pose, int worldHeight,
        BooleanSupplier current) throws IOException {
        if (portableSource == null || portableSource.length == 0
            || portableSource.length > HologramBlueprintSharing.MAX_BYTES)
            throw new IOException("当前预览缺少原始蓝图内容，请在客户端蓝图库打开蓝图后再放置");
        if (anchor == null || anchor.length != 3
            || !HologramWorldFlow.worldPosition(anchor[0], anchor[1], anchor[2], worldHeight))
            throw new IOException("放置锚点超出当前世界范围");
        if (pose == null || pose.length > HologramPreviewPose.MAX_RECIPE_LENGTH) throw new IOException("放置朝向无效");
        boolean pitched = false;
        for (int axis : pose) {
            if (axis != 0 && axis != 1) throw new IOException("放置朝向无效");
            pitched |= axis == 1;
        }
        ensureCurrent(current);
        HologramPresets.Preset preset = HologramBlueprintSharing
            .decodeUnchecked(new String(portableSource, StandardCharsets.UTF_8));
        if (preset.schema == 1) preset = HologramPresets.upgradeLegacy(preset);
        HologramPresets.validatePlacementData(preset);
        HologramBlueprintValidationCache payloads = new HologramBlueprintValidationCache(preset);
        List<Cell> placements = new ArrayList<>();
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (HologramPresets.Cell source : preset.cells) {
            ensureCurrent(current);
            if (source.excluded || plainAir(source)) continue;
            Cell cell;
            try {
                payloads.filter(source);
                cell = project(preset, source, anchor, pose, pitched, worldHeight);
            } catch (IOException | RuntimeException | LinkageError invalid) {
                String detail = invalid.getMessage();
                throw new IOException(
                    "蓝图格 (" + source.x
                        + ", "
                        + source.y
                        + ", "
                        + source.z
                        + ") "
                        + (source.block == null ? source.item : source.block)
                        + " 无法放置"
                        + (detail == null ? "" : "，" + detail),
                    invalid);
            }
            placements.add(cell);
            counts.merge(new Material(cell.item, cell.damage), 1, Integer::sum);
        }
        if (placements.isEmpty()) throw new IOException("当前蓝图没有可施工的方块");
        List<Need> needs = new ArrayList<>(counts.size());
        counts.forEach((material, count) -> needs.add(new Need(material.item, material.damage, count)));
        ensureCurrent(current);
        return new Plan(placements, needs);
    }

    private static void ensureCurrent(BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) throw new IOException("蓝图放置来源已变化，请重新确认");
    }

    private static boolean plainAir(HologramPresets.Cell source) {
        return "minecraft:air".equals(source.block) && (source.item == null || "minecraft:air".equals(source.item))
            && (source.tileNbt == null || source.tileNbt.isEmpty())
            && (source.itemNbt == null || source.itemNbt.isEmpty())
            && !source.controller;
    }

    private static Cell project(HologramPresets.Preset preset, HologramPresets.Cell source, int[] anchor, int[] pose,
        boolean pitched, int height) throws IOException {
        ItemStack sample = source.stack();
        Block block = source.block();
        if (sample == null || !(sample.getItem() instanceof ItemBlock))
            throw new IOException("当前施工需要已安装的方块物品，请替换此格或手动放置");
        if (block == null && source.block == null) block = Block.getBlockFromItem(sample.getItem());
        if (block == null || block == Blocks.air || source.metadata > 15) throw new IOException("方块注册名或世界元数据无效");
        String blockName = Block.blockRegistry.getNameForObject(block);
        String itemName = Item.itemRegistry.getNameForObject(sample.getItem());
        if (Block.getBlockFromItem(sample.getItem()) != block) throw new IOException("该方块需要原生复合材料或其他物品变体，请手动放置");

        int[] location = { source.x, source.y, source.z };
        for (int axis : pose)
            location = HologramVirtualTransform.turn(location[0], location[1], location[2], axis == 1);
        if (!HologramWorldFlow
            .worldPosition(anchor[0] + location[0], anchor[1] + location[1], anchor[2] + location[2], height))
            throw new IOException("旋转后的目标位置超出世界范围");

        int metadata = source.metadata;
        int facing = -1;
        NBTTagCompound tile = HologramBlueprintNbt.decode(source.tileNbt);
        NBTTagCompound item = HologramBlueprintNbt.decode(source.itemNbt);
        if (sample.getItem() instanceof ItemMachines) {
            if (block != GregTechAPI.sBlockMachines || ItemMachines.getMetaTileEntity(sample) == null)
                throw new IOException("缺少此 GT 机器的原生变体");
            boolean pipe = HologramBlockTransforms.isPipe(sample);
            String tileId = pipe ? "BaseMetaPipeEntity" : "BaseMetaTileEntity";
            checkGtShell(tile, sample.getItemDamage(), tileId, pipe);
            checkGtShell(item, sample.getItemDamage(), tileId, pipe);
            if (!pipe) {
                facing = source.facing;
                if (facing < 0 && tile != null && tile.hasKey("mFacing", 99)) facing = tile.getInteger("mFacing");
                if (source.controller && preset.controllerFacing != null) {
                    ExtendedFacing alignment = HologramVirtualTransform.controller(preset);
                    for (int axis : pose) alignment = HologramVirtualTransform.alignment(alignment, axis == 1);
                    if (alignment != ExtendedFacing.of(alignment.getDirection()))
                        throw new IOException("此 GT 控制器包含额外翻转或滚转，请恢复默认姿态或手动放置");
                    facing = alignment.getDirection()
                        .ordinal();
                } else {
                    for (int axis : pose) facing = HologramVirtualTransform.front(facing, axis == 1);
                }
                if (facing < -1 || facing > 5) throw new IOException("GT 机器朝向无效");
                if (!HologramCellPolicy.virtualFrontAllowed(sample, facing))
                    throw new IOException("GT 机器无法采用旋转后的朝向，请选择可用朝向");
            }
        } else {
            if (block.hasTileEntity(metadata) || hasData(tile) || hasData(item))
                throw new IOException("此原生设备或保留的数据需要手动放置。普通方块可先取消保留相关 NBT");
            metadata = ordinaryMetadata(block, blockName, metadata, pose, pitched);
            HologramPresets.validateMaterialVariant(block, metadata, sample);
        }
        return new Cell(
            location[0],
            location[1],
            location[2],
            blockName,
            metadata,
            itemName,
            sample.getItemDamage(),
            facing);
    }

    private static int ordinaryMetadata(Block block, String name, int metadata, int[] pose, boolean pitched)
        throws IOException {
        if (pose.length == 0 || SIMPLE_VARIANTS.contains(name) || block.getClass() == Block.class) return metadata;
        boolean axisState = block instanceof BlockLog || block instanceof BlockHay
            || block == Blocks.quartz_block && metadata >= 2 && metadata <= 4;
        if (pitched && !axisState) throw new IOException("该方块不能安全竖向旋转，请选择水平朝向或手动放置");
        for (int axis : pose) {
            CutTransforms.State state = new CutTransforms.State(block, metadata, null, 0, 0, 0, 0, 0, 0);
            if (!block.getClass()
                .getName()
                .startsWith("net.minecraft.block.")
                || !HologramCutTransformMetadata
                    .apply(state, new CutTransforms.Transform(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, 0, 0)))
                throw new IOException("该方块没有可核验的旋转规则，请恢复原朝向或手动放置");
            metadata = state.metadata;
        }
        return metadata;
    }

    private static boolean hasData(NBTTagCompound tag) {
        return tag != null && !tag.hasNoTags();
    }

    private static void checkGtShell(NBTTagCompound tag, int damage, String tileId, boolean pipe) throws IOException {
        if (!hasData(tag)) return;
        HologramPresets.validateGtPayload(tag, damage, tileId);
        for (Object raw : tag.func_150296_c()) {
            String key = (String) raw;
            if (GT_IDENTITY_KEYS.contains(key) && (!pipe || !"mFacing".equals(key))) {
                if (!"mFacing".equals(key) || tag.hasKey(key, 99) && tag.getLong(key) >= -1 && tag.getLong(key) <= 5)
                    continue;
                throw new IOException("GT 机器朝向无效");
            }
            if (("mColor".equals(key) || "mConnections".equals(key) || "mStrongRedstone".equals(key))
                && tag.hasKey(key, 99)
                && tag.getLong(key) == 0) continue;
            throw new IOException("存在保留的 GT 设备设置、覆盖板或资源，请调整蓝图保留项或手动放置此设备");
        }
    }
}
