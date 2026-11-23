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
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.api.CutTransforms;

import gregtech.api.GregTechAPI;
import gregtech.common.blocks.ItemMachines;

/** Compiles a private document into exact target operations and a main-shell material bill. */
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
    private HologramProjectedPlacement() {}

    @Desugar
    public record Cell(int x, int y, int z, String block, int metadata, String item, int damage, int facing,
        boolean controller, String tile, String itemState) {

        public Cell(int x, int y, int z, String block, int metadata, String item, int damage, int facing) {
            this(x, y, z, block, metadata, item, damage, facing, false, "", "");
        }
    }

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
            .decodePlacementSource(new String(portableSource, StandardCharsets.UTF_8));
        if (preset.schema == 1) preset = HologramPresets.upgradeLegacy(preset);
        if (preset.cells == null || preset.cells.isEmpty() || !HologramBlueprintLimits.accepts(preset.cells.size()))
            throw new IOException("当前蓝图没有可施工的方块或方块数量超出范围");
        List<HologramPresets.Cell> included = new ArrayList<>();
        for (HologramPresets.Cell source : preset.cells) {
            ensureCurrent(current);
            if (source == null) throw new IOException("蓝图包含无法读取的方块记录");
            if (!source.excluded && !plainAir(source)) included.add(source);
        }
        preset.cells = included;
        // The saved grid supplies placement operations independently of the original declaration provider.
        HologramBlueprintValidationCache payloads = new HologramBlueprintValidationCache(preset);
        HologramNetworkBindings.sanitize(
            preset, Collections.emptyMap(), (phase, completed, total) -> ensureCurrent(current), payloads);
        List<Cell> placements = new ArrayList<>();
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (HologramPresets.Cell source : preset.cells) {
            ensureCurrent(current);
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

    /** Freezes the selectable targets of a local controller view for a real build review. */
    public static Plan compileDisplayed(NBTTagCompound state, int[] anchor, int worldHeight, BooleanSupplier current)
        throws IOException {
        if (anchor == null || anchor.length != 3
            || !HologramWorldFlow.worldPosition(anchor[0], anchor[1], anchor[2], worldHeight))
            throw new IOException("放置锚点超出当前世界范围");
        if (state.getInteger("mode") != 0) throw new IOException("替换或拆除必须通过原机施工确认核验结构成员");
        if (state.getBoolean("capturePending") || !state.getBoolean("supported"))
            throw new IOException("请等待完整的本地结构目标");
        NBTTagList rows = state.getTagList("cells", 10);
        HologramBlueprintLimits.require(rows.tagCount());
        List<Cell> cells = new ArrayList<>();
        Map<Material, Integer> counts = new LinkedHashMap<>();
        DisplayScope scope = new DisplayScope(state, rows);
        for (int index = 0; index < rows.tagCount(); index++) {
            ensureCurrent(current);
            NBTTagCompound row = rows.getCompoundTagAt(index);
            if (!scope.includes(row, index) || row.getBoolean("anchor") || "minecraft:air".equals(row.getString("wantId")))
                continue;
            ItemStack sample = ItemStack.loadItemStackFromNBT(row.getCompoundTag("targetStack"));
            if (sample == null || row.getBoolean("missingBlock"))
                throw new IOException("所选范围包含未确定材料的结构格，请先明确目标方块");
            int x = row.getInteger("x"), y = row.getInteger("y"), z = row.getInteger("z");
            if (!HologramWorldFlow.worldPosition(x, y, z, worldHeight)) throw new IOException("结构格超出当前世界范围");
            if (x == anchor[0] && y == anchor[1] && z == anchor[2]) continue;
            String blockName = row.getString("wantId");
            Block block = Block.getBlockFromName(blockName);
            if (block == null && blockName.isEmpty()) block = Block.getBlockFromItem(sample.getItem());
            if (block == null) throw new IOException("服务器需要有效的结构目标方块");
            int metadata = row.getInteger("wantMeta");
            int facing = -1;
            NBTTagCompound item = sample.getTagCompound();
            if (sample.getItem() instanceof ItemMachines) {
                if (!HologramBlockTransforms.isPipe(sample) && row.hasKey("targetFacing", 3))
                    facing = row.getInteger("targetFacing");
                item = HologramExecutionEligibility.gtState(item, sample, false, true);
            }
            HologramExecutionEligibility.validate(block, metadata, sample, facing, false, null, item);
            String itemName = Item.itemRegistry.getNameForObject(sample.getItem());
            cells.add(new Cell(x - anchor[0], y - anchor[1], z - anchor[2], Block.blockRegistry.getNameForObject(block),
                metadata, itemName, sample.getItemDamage(), facing, false, "", transport(item)));
            counts.merge(new Material(itemName, sample.getItemDamage()), 1, Integer::sum);
        }
        if (cells.isEmpty()) throw new IOException("所选范围没有可施工的方块");
        List<Need> needs = new ArrayList<>(counts.size());
        counts.forEach((material, count) -> needs.add(new Need(material.item, material.damage, count)));
        ensureCurrent(current);
        return new Plan(cells, needs);
    }

    static final class DisplayScope {

        private final int scope, layer, selected;
        private final Set<Integer> selection = new HashSet<>();
        private final String family;

        DisplayScope(NBTTagCompound state, NBTTagList cells) {
            scope = state.getInteger("scope");
            layer = state.getInteger("layer");
            selected = state.hasKey("selected", 3) ? state.getInteger("selected") : -1;
            for (int index : state.getIntArray("selectedIndices")) selection.add(index);
            family = selected < 0 || selected >= cells.tagCount() ? "" : cells.getCompoundTagAt(selected)
                .getString("family");
        }

        boolean includes(NBTTagCompound row, int index) {
            if (row.getBoolean("excluded") || row.hasKey("inScope", 1) && !row.getBoolean("inScope")) return false;
            return switch (scope) {
                case 0 -> true;
                case 1 -> row.getInteger("dy") == layer;
                case 2 -> selection.isEmpty() ? selected == index : selection.contains(index);
                case 3 -> !family.isEmpty() && family.equals(row.getString("family"));
                default -> false;
            };
        }
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
        if (sample == null) throw new IOException("当前施工需要已安装的方块材料，请替换此格或手动放置");
        if (block == null && source.block == null) block = Block.getBlockFromItem(sample.getItem());
        if (block == null || block == Blocks.air || source.metadata < 0 || source.metadata > 15)
            throw new IOException("方块注册名或世界元数据无效");
        String blockName = Block.blockRegistry.getNameForObject(block);

        // Bound the stored offsets before quarter turns and addition so malformed ints cannot wrap.
        if (Math.abs((long) source.x) > 60_000_000 || Math.abs((long) source.y) > 256
            || Math.abs((long) source.z) > 60_000_000) throw new IOException("蓝图坐标超出范围");
        int[] location = { source.x, source.y, source.z };
        for (int axis : pose)
            location = HologramVirtualTransform.turn(location[0], location[1], location[2], axis == 1);
        if (!HologramWorldFlow
            .worldPosition(anchor[0] + location[0], anchor[1] + location[1], anchor[2] + location[2], height))
            throw new IOException("旋转后的目标位置超出世界范围");

        int metadata = source.metadata;
        int facing = -1;
        String tileState = source.tileNbt, itemState = source.itemNbt;
        NBTTagCompound tile = HologramBlueprintNbt.decode(tileState);
        NBTTagCompound item = HologramBlueprintNbt.decode(itemState);
        boolean nativeState = hasData(tile) || hasData(item) || HologramNativePlacement.supported(block, tile);
        if (nativeState) {
            for (int axis : pose) {
                HologramBlockTransforms.Result result = HologramBlockTransforms.turnForExecution(
                    block, sample.getItem() instanceof ItemMachines ? "gregtech." : "", metadata,
                    tileState, itemState, axis == 1);
                metadata = result.metadata;
                tileState = HologramCoordinatePolicies.rotatePayload(result.tileNbt, axis == 1);
                itemState = HologramCoordinatePolicies.rotatePayload(result.itemNbt, axis == 1);
            }
            tile = HologramBlueprintNbt.decode(tileState);
            item = HologramBlueprintNbt.decode(itemState);
        }
        if (tile != null && tile.hasKey(HologramNetworkBindings.GROUP)) {
            tile.removeTag(HologramNetworkBindings.ORIGINAL);
            tile.removeTag(HologramNetworkBindings.MODE);
            tileState = transport(tile);
        }
        if (sample.getItem() instanceof ItemMachines) {
            if (block != GregTechAPI.sBlockMachines || ItemMachines.getMetaTileEntity(sample) == null)
                throw new IOException("缺少此 GT 机器的原生变体");
            boolean pipe = HologramBlockTransforms.isPipe(sample);
            tile = HologramExecutionEligibility.gtState(tile, sample, true, true);
            item = HologramExecutionEligibility.gtState(item, sample, false, true);
            if (!pipe) {
                facing = source.facing;
                NBTTagCompound originalTile = HologramBlueprintNbt.decode(source.tileNbt);
                if (facing < 0 && originalTile != null && originalTile.hasKey("mFacing", 99))
                    facing = originalTile.getInteger("mFacing");
                if (source.controller && preset.controllerFacing != null) {
                    if (preset.controllerFacing < 0 || preset.controllerFacing >= ExtendedFacing.values().length)
                        throw new IOException("GT 控制器姿态无效");
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
                if (tile != null && tile.hasKey("mFacing")) tile.setShort("mFacing", (short) facing);
            }
        } else if (!nativeState) {
            metadata = ordinaryMetadata(block, blockName, metadata, pose, pitched);
        } else if (HologramNativePlacement.supported(block, tile)) {
            sample.setTagCompound(HologramBlueprintNbt.nativePayload(item));
            ItemStack nativeMaterial = HologramBlockTransforms.nativeSample(block, metadata, tileState, sample);
            if (nativeMaterial != null) {
                sample = nativeMaterial;
                item = sample.getTagCompound();
            }
        }
        HologramExecutionEligibility.validate(block, metadata, sample, facing, source.controller, tile, item);
        return new Cell(
            location[0],
            location[1],
            location[2],
            blockName,
            metadata,
            Item.itemRegistry.getNameForObject(sample.getItem()),
            sample.getItemDamage(),
            facing,
            source.controller,
            transport(tile),
            transport(item));
    }

    private static String transport(NBTTagCompound tag) throws IOException {
        return hasData(tag) ? HologramBlueprintNbt.encodeCanonical(tag) : "";
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

}
