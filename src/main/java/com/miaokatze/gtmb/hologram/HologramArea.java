package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.blocks.BlockFrameBox;

/** Server-owned region selection and resumable read-only world import. */
final class HologramArea {

    boolean selecting, imported, importing;
    int[] first, second;
    int x, y, z, minX, minY, minZ, maxX, maxY, maxZ;
    HologramPresets.Preset blueprint;
    final Map<String, Structure> structures = new LinkedHashMap<>();
    final Map<String, java.util.LinkedHashSet<String>> members = new java.util.HashMap<>();
    final java.util.Set<String> paletteKeys = new java.util.HashSet<>();
    final java.util.Set<String> edited = new java.util.HashSet<>();
    final List<Structure> discovery = new ArrayList<>();
    int discoveryIndex;
    long expandedNbt;
    String active = "";
    String pointMode = "replace";
    final List<Region> regions = new ArrayList<>();
    final List<List<Region>> regionUndo = new ArrayList<>();

    static final class Region {

        final int minX, minY, minZ, maxX, maxY, maxZ;
        final String mode;

        Region(int[] a, int[] b, String mode) {
            minX = Math.min(a[0], b[0]);
            minY = Math.min(a[1], b[1]);
            minZ = Math.min(a[2], b[2]);
            maxX = Math.max(a[0], b[0]);
            maxY = Math.max(a[1], b[1]);
            maxZ = Math.max(a[2], b[2]);
            this.mode = mode;
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    void point(int index, int xx, int yy, int zz, String mode) throws IOException {
        if (!("replace".equals(mode) || "add".equals(mode) || "subtract".equals(mode)))
            throw new IOException("区域组合模式无效");
        if (index == 1) {
            first = new int[] { xx, yy, zz };
            second = null;
            pointMode = mode;
        }
        if (index == 2) {
            if (first == null) throw new IOException("请先选择第一角点");
            if (regions.size() >= 128 || regionUndo.size() >= 128) throw new IOException("区域组合过多，请撤销或完成");
            second = new int[] { xx, yy, zz };
            regionUndo.add(new ArrayList<>(regions));
            if ("replace".equals(pointMode)) regions.clear();
            regions.add(new Region(first, second, pointMode));
        }
    }

    void undoRegion() {
        if (regionUndo.isEmpty()) {
            first = null;
            second = null;
            return;
        }
        regions.clear();
        regions.addAll(regionUndo.remove(regionUndo.size() - 1));
        first = null;
        second = null;
    }

    boolean contains(int xx, int yy, int zz) {
        if (regions.isEmpty()) return xx >= minX && xx <= maxX && yy >= minY && yy <= maxY && zz >= minZ && zz <= maxZ;
        boolean selected = false;
        for (Region region : regions) if (region.contains(xx, yy, zz)) selected = !"subtract".equals(region.mode);
        return selected;
    }

    NBTTagList regionSnapshot() {
        NBTTagList rows = new NBTTagList();
        for (Region region : regions) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("minX", region.minX);
            row.setInteger("minY", region.minY);
            row.setInteger("minZ", region.minZ);
            row.setInteger("maxX", region.maxX);
            row.setInteger("maxY", region.maxY);
            row.setInteger("maxZ", region.maxZ);
            row.setString("mode", region.mode);
            rows.appendTag(row);
        }
        return rows;
    }

    static final class Structure {

        final String id;
        final int x, y, z;
        final MTEEnhancedMultiBlockBase<?> machine;
        boolean enabled = true;
        HologramCapture capture;

        Structure(int x, int y, int z, MTEEnhancedMultiBlockBase<?> machine) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.machine = machine;
            id = x + "," + y + "," + z;
        }
    }

    void point(int index, int xx, int yy, int zz) {
        if (index == 1) first = new int[] { xx, yy, zz };
        if (index == 2) second = new int[] { xx, yy, zz };
    }

    void begin(World world) throws IOException {
        int[] bounds = bounds();
        minX = bounds[0];
        minY = bounds[1];
        minZ = bounds[2];
        maxX = bounds[3];
        maxY = bounds[4];
        maxZ = bounds[5];
        long width = (long) maxX - minX + 1, height = (long) maxY - minY + 1, depth = (long) maxZ - minZ + 1;
        if (width > 1000000 || height > 1000000
            || depth > 1000000
            || width * height > 1000000
            || width * height * depth > 1000000) throw new IOException("区域包围盒超过一百万格，请缩小选区");
        if (minY < 0 || maxY >= world.getHeight()) throw new IOException("区域高度无效");
        if (minX < -30000000 || maxX >= 30000000 || minZ < -30000000 || maxZ >= 30000000)
            throw new IOException("区域超出世界坐标边界");
        x = minX;
        y = minY;
        z = minZ;
        blueprint = new HologramPresets.Preset();
        blueprint.schema = 2;
        blueprint.name = "施工区域";
        blueprint.controllerType = "area";
        structures.clear();
        members.clear();
        discovery.clear();
        paletteKeys.clear();
        edited.clear();
        discoveryIndex = 0;
        expandedNbt = 0;
        importing = true;
        imported = false;
    }

    /** Subtraction can only reduce the mask, so its exterior bounds never enlarge the source scan. */
    int[] bounds() throws IOException {
        if (regions.isEmpty()) {
            if (first == null || second == null) throw new IOException("请先选择两个区域角点");
            regions.add(new Region(first, second, "replace"));
        }
        int[] bounds = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE };
        for (Region region : regions) {
            if ("subtract".equals(region.mode)) continue;
            bounds[0] = Math.min(bounds[0], region.minX);
            bounds[1] = Math.min(bounds[1], region.minY);
            bounds[2] = Math.min(bounds[2], region.minZ);
            bounds[3] = Math.max(bounds[3], region.maxX);
            bounds[4] = Math.max(bounds[4], region.maxY);
            bounds[5] = Math.max(bounds[5], region.maxZ);
        }
        if (bounds[0] == Integer.MAX_VALUE) throw new IOException("减选后没有可导入的正选区域");
        return bounds;
    }

    /** No world mutation or material extraction occurs during import. */
    boolean tick(EntityPlayerMP player, ItemStack trigger) throws IOException {
        World world = player.worldObj;
        for (int budget = 4096; y <= maxY && budget > 0; budget--) {
            if (contains(x, y, z)) {
                if (!world.blockExists(x, y, z)) throw new IOException("区域包含未加载区块，导入已停止");
                Block block = world.getBlock(x, y, z);
                int meta = world.getBlockMetadata(x, y, z);
                TileEntity tile = world.getTileEntity(x, y, z);
                if (block != Blocks.air) {
                    if (blueprint.cells.size() >= HologramCapture.LIMIT) throw new IOException("区域非空气方块超过蓝图格数上限，请拆分选区");
                    NBTTagCompound data = tile == null ? null : HologramNativeCapture.capture(tile);
                    ItemStack sample = serverSample(block, meta, tile, data);
                    HologramPresets.Cell cell = sample == null ? new HologramPresets.Cell()
                        : HologramPresets.cell(sample, x - minX, y - minY, z - minZ, -1, false);
                    cell.x = x - minX;
                    cell.y = y - minY;
                    cell.z = z - minZ;
                    cell.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
                    cell.metadata = meta;
                    if (sample != null && sample.hasTagCompound())
                        cell.itemNbt = HologramBlueprintNbt.encodeCaptured(sample.getTagCompound());
                    if (tile != null) {
                        cell.tileNbt = HologramAeBlueprint.supports(block, data)
                            ? HologramBlueprintNbt.encodeCanonical(block, data)
                            : HologramBlueprintNbt.encodeCaptured(data);
                    }
                    expandedNbt += HologramBlueprintNbt.expandedBytes(cell.tileNbt)
                        + HologramBlueprintNbt.expandedBytes(cell.itemNbt);
                    if (expandedNbt > 64L * 1024 * 1024) throw new IOException("区域设备 NBT 超过 64MiB 上限，请拆分选区");
                    if (tile instanceof IGregTechTileEntity) {
                        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
                        cell.facing = HologramBlockIdentity.front(gt);
                        if (gt.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase
                            && gt.getMetaTileEntity() instanceof IConstructable) {
                            cell.controller = true;
                            Structure structure = new Structure(
                                x,
                                y,
                                z,
                                (MTEEnhancedMultiBlockBase<?>) gt.getMetaTileEntity());
                            structures.put(structure.id, structure);
                            addMember(structure.id, structure.id);
                            discovery.add(structure);
                        }
                    }
                    blueprint.cells.add(cell);
                    if (sample != null
                        && paletteKeys.add(String.valueOf(sample.getItem()) + ":" + sample.getItemDamage()))
                        blueprint.palette.add(HologramPresets.cell(sample, 0, 0, 0, -1, false));
                }
            }
            if (++x > maxX) {
                x = minX;
                if (++z > maxZ) {
                    z = minZ;
                    y++;
                }
            }
        }
        if (y <= maxY) return false;
        if (discoveryIndex < discovery.size()) {
            Structure structure = discovery.get(discoveryIndex);
            if (world.getTileEntity(structure.x, structure.y, structure.z) != structure.machine.getBaseMetaTileEntity())
                throw new IOException("控制器在导入期间变化，请重新选择区域");
            // Tool configuration belongs to the previously selected machine, never every controller in a region.
            ItemStack structureTrigger = trigger.copy();
            structureTrigger.stackSize = 1;
            if (structure.machine instanceof tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods)
                structureTrigger.stackSize = Math.max(
                    1,
                    Math.min(
                        3,
                        ((tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods) structure.machine).getData()
                            .getRingAmount()));
            List<String> channels = new ArrayList<>();
            com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
                .iterateChannelData(structureTrigger)
                .forEach(entry -> channels.add(entry.getKey()));
            for (String channel : channels) com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
                .unsetChannelData(structureTrigger, channel);
            structure.capture = HologramCapture
                .collect(structure.machine, structureTrigger, structure.machine.getExtendedFacing(), structure.capture);
            if (!structure.capture.pending) {
                if (!structure.capture.incomplete)
                    for (Map.Entry<String, HologramCapture.Cell> entry : structure.capture.positions.entrySet()) {
                        HologramCapture.Cell cell = entry.getValue();
                        if (cell.x >= minX && cell.x <= maxX
                            && cell.y >= minY
                            && cell.y <= maxY
                            && cell.z >= minZ
                            && cell.z <= maxZ
                            && contains(cell.x, cell.y, cell.z)) addMember(entry.getKey(), structure.id);
                    }
                discoveryIndex++;
            }
            return false;
        }
        // Publish only a canonical detached draft, keeping the source world's cells and identities untouched.
        blueprint = HologramAePolicy.sanitize(blueprint).preset;
        importing = false;
        imported = true;
        return true;
    }

    /** Current import phase; no source-world reads are needed for the progress display. */
    String importStage() {
        return y <= maxY ? "blocks" : "declarations";
    }

    /** Progress belongs to the current phase and reads only the detached scan cursor. */
    int importProgress() {
        if (y > maxY) return discovery.isEmpty() ? 100 : (int) (100D * discoveryIndex / discovery.size());
        long width = (long) maxX - minX + 1, depth = (long) maxZ - minZ + 1;
        long total = width * depth * ((long) maxY - minY + 1);
        long visited = (((long) y - minY) * depth + z - minZ) * width + x - minX;
        return total <= 0 ? 0 : Math.max(0, Math.min(100, (int) (100D * visited / total)));
    }

    /** Inventory identity is read without client-only pick hooks or harvesting callbacks. */
    static ItemStack serverSample(Block block, int metadata, TileEntity tile) {
        boolean ae = HologramAeBlueprint.supports(block) || (block != null && block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart"));
        try {
            return serverSample(
                block,
                metadata,
                tile,
                !ae || tile == null ? null : HologramNativeCapture.capture(tile));
        } catch (IOException unsupported) {
            return null;
        }
    }

    private static ItemStack serverSample(Block block, int metadata, TileEntity tile, NBTTagCompound data) {
        if (tile instanceof IGregTechTileEntity)
            return new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) tile).getMetaTileID());
        if (block instanceof BlockFrameBox) return ((BlockFrameBox) block).getStackForm(1, metadata);
        if (HologramAeBlueprint.supports(block, data)) {
            try {
                return HologramAeBlueprint.sampleIdentity(block, metadata, data);
            } catch (IOException unsupported) {
                return null;
            }
        }
        if (HologramNativePlacement.supported(block)) {
            try {
                return HologramNativePlacement.sample(block, metadata, tile);
            } catch (IOException unsupported) {
                // Preserve its real block and complete tile NBT as an unsupported draft cell.
                return null;
            }
        }
        return HologramBlockIdentity.materialStack(block, block.damageDropped(metadata));
    }

    String member(int xx, int yy, int zz) {
        List<String> ids = memberships(xx, yy, zz);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    void addMember(String position, String id) {
        // Controllers retain their own structure; shared declared walls may belong to several.
        if (structures.containsKey(position) && !position.equals(id)) return;
        members.computeIfAbsent(position, ignored -> new java.util.LinkedHashSet<>())
            .add(id);
    }

    List<String> memberships(int xx, int yy, int zz) {
        java.util.Set<String> ids = members.get(xx + "," + yy + "," + zz);
        return ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }

    NBTTagList labels() {
        NBTTagList list = new NBTTagList();
        for (Structure structure : structures.values()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", structure.id);
            row.setInteger("x", structure.x);
            row.setInteger("y", structure.y);
            row.setInteger("z", structure.z);
            row.setString("name", structure.machine.getLocalName());
            row.setBoolean("enabled", structure.enabled);
            row.setBoolean("formed", structure.machine.mMachine);
            row.setBoolean("declared", structure.capture != null && !structure.capture.incomplete);
            list.appendTag(row);
        }
        return list;
    }

    int[] points() {
        if (first == null) return second == null ? new int[0] : second.clone();
        if (second == null) return first.clone();
        return new int[] { first[0], first[1], first[2], second[0], second[1], second[2] };
    }
}
