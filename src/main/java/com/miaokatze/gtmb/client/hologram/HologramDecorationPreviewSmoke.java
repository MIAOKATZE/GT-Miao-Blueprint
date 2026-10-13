package com.miaokatze.gtmb.client.hologram;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

/** Installed decoration models: independently decoded native controls, skins, metadata and alpha geometry. */
final class HologramDecorationPreviewSmoke {

    private HologramDecorationPreviewSmoke() {}

    static NBTTagCompound fixture() {
        try {
            NBTTagCompound data = new NBTTagCompound();
            data.setString("session", "native-decoration-materials-v007");
            data.setInteger("y", 64);
            data.setBoolean("virtual", true);
            NBTTagList cells = new NBTTagList();
            Block slope = find("com.carpentersblocks.block.BlockCarpentersSlope");
            Block little = find("com.creativemd.littletiles.common.blocks.BlockTile");
            Block shape = find("gcewing.architecture.common.block.BlockShape");
            Block saw = find("gcewing.architecture.common.block.BlockSawbench");
            NBTTagCompound uncovered = new NBTTagCompound();
            uncovered.setInteger("cbMetadata", 8);
            add(cells, slope, 0, uncovered, "carp-uncovered");
            NBTTagCompound covered = (NBTTagCompound) uncovered.copy();
            NBTTagList attributes = new NBTTagList();
            attributes.appendTag(attribute(new ItemStack(Blocks.quartz_block, 1, 1), 6));
            attributes.appendTag(attribute(new ItemStack(Blocks.planks, 1, 3), 5));
            attributes.appendTag(attribute(new ItemStack(Items.dye, 1, 1), 13));
            attributes.appendTag(attribute(new ItemStack(Items.glowstone_dust), 21));
            covered.setTag("cbAttrList", attributes);
            covered.setString("cbChiselDesign_6", "chiseled");
            add(cells, slope, 0, covered, "carp-quartz-jungle-red-chisel-light");
            NBTTagCompound micro = new NBTTagCompound();
            micro.setInteger("tilesCount", 2);
            NBTTagCompound colored = little("minecraft:stained_hardened_clay", 9, 0, 0, 0, 8, 4, 12);
            colored.setString("tID", "BlockTileColored");
            colored.setInteger("color", 0x44ccff);
            micro.setTag("t0", colored);
            micro.setTag("t1", little("minecraft:planks", 2, 8, 0, 4, 16, 12, 8));
            add(cells, little, 0, micro, "little-cyan-clay-birch");
            add(
                cells,
                shape,
                0,
                architecture(0, "minecraft:brick_block", 0, "minecraft:quartz_block", 1),
                "architecture-roof-brick-chiseled-quartz");
            add(
                cells,
                shape,
                0,
                architecture(12, "minecraft:quartz_block", 2, null, 0),
                "architecture-quarter-pillar-quartz");
            add(
                cells,
                shape,
                0,
                architecture(30, "minecraft:planks", 2, "minecraft:stained_glass", 11),
                "architecture-window-birch-blue-glass");
            Object content = Class.forName("gcewing.architecture.ArchitectureCraft")
                .getField("content")
                .get(null);
            Block shapeSE = (Block) content.getClass()
                .getField("blockShapeSE")
                .get(content);
            add(cells, shapeSE, 0, architecture(12, "minecraft:wool", 4, null, 0), "architecture-shapeSE-yellow-wool");
            NBTTagCompound bench = new NBTTagCompound();
            bench.setInteger("Page", 3);
            bench.setIntArray("Slots", new int[] { 1, 2, 3, 4, 0, 0, 0 });
            add(cells, saw, 1, bench, "architecture-sawbench-native-wood-metal");
            data.setTag("cells", cells);
            // Canonicalize through an independent native block factory, never the
            // GTMB restoration path that is being tested. Capture the real tile NBT.
            HologramState source = new HologramState(data);
            ReferenceAccess reference = new ReferenceAccess(source, false);
            for (int i = 0; i < source.cells.size(); i++) {
                HologramState.Cell cell = source.cells.get(i);
                TileEntity tile = reference.getTileEntity(cell.x, cell.y, cell.z);
                require(tile != null, "native source factory required for " + cell.wantId);
                NBTTagCompound display = capture(tile);
                require(display.hasKey("nativeTile", 10), "capture must retain original decoration NBT");
                cells.getCompoundTagAt(i)
                    .setTag("actualDisplay", display.copy());
                cells.getCompoundTagAt(i)
                    .setTag("targetDisplay", display.copy());
            }
            return data;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("installed decoration fixture cannot be constructed", failure);
        }
    }

    /** Exact opt-in bridge to the existing common capture boundary; no production API widening. */
    private static NBTTagCompound capture(TileEntity tile) throws ReflectiveOperationException {
        java.lang.reflect.Method capture = Class.forName("com.miaokatze.gtmb.hologram.HologramDisplaySnapshot")
            .getDeclaredMethod("capture", TileEntity.class);
        capture.setAccessible(true);
        return (NBTTagCompound) capture.invoke(null, tile);
    }

    static String verify(HologramState state, boolean current) {
        require(state.cells.size() == 8, "all native decoration witnesses must be present");
        NBTTagCompound before = (NBTTagCompound) state.data.copy();
        ReferenceAccess reference = new ReferenceAccess(state, current);
        HologramBlockRender.begin(state);
        StringBuilder result = new StringBuilder();
        for (HologramState.Cell cell : state.cells) {
            require(
                HologramBlockRender.ready(cell, current),
                cell.role + ": " + HologramBlockRender.failure(cell, current));
            require(!HologramBlockRender.missingTexture(cell, current), cell.role + " native material is missing");
            List<double[]> original = HologramBlockRender.nativeControl(cell, current, reference.world);
            List<double[]> preview = HologramBlockRender.attributes(cell, current);
            require(
                !original.isEmpty() && original.size() == preview.size(),
                cell.role + " original vertex count differs");
            for (int i = 0; i < original.size(); i++) {
                double[] a = original.get(i), b = preview.get(i);
                for (int field = 0; field < 5; field++) require(
                    Math.abs(a[field] - b[field]) < 0.00002,
                    cell.role + " original position/UV differs at vertex " + i);
                require(a[5] == b[5], cell.role + " original packed material color differs at vertex " + i);
            }
            verifySkin(cell, current);
            result.append(cell.role)
                .append(" originalVertices=")
                .append(original.size())
                .append(" previewVertices=")
                .append(preview.size())
                .append(" originalVertexHash=")
                .append(vertexHash(original))
                .append(" previewVertexHash=")
                .append(vertexHash(preview))
                .append(" nativePositionUvColor=equal\n");
        }
        verifyUnsupportedNestedTile(state);
        verifyCarpenterContext(reference, state.cells.get(1));
        require(before.equals(state.data), "native source/control/render must preserve snapshot NBT");
        return result.toString();
    }

    private static String vertexHash(List<double[]> vertices) {
        int hash = 1;
        for (double[] vertex : vertices) hash = 31 * hash + java.util.Arrays.hashCode(vertex);
        return Integer.toUnsignedString(hash, 16);
    }

    static void verifyTurn(NBTTagCompound source, NBTTagCompound rotated) {
        for (boolean current : new boolean[] { false, true }) {
            HologramState before = new HologramState(source), after = new HologramState(rotated);
            verify(before, current);
            verify(after, current);
            List<List<double[]>> a = HologramNativeCompatibilitySmoke.geometry(before, current);
            List<List<double[]>> b = HologramNativeCompatibilitySmoke.geometry(after, current);
            for (int i = 0; i < a.size(); i++) for (double[] point : a.get(i)) {
                boolean found = false;
                for (double[] turned : b.get(i))
                    if (Math.abs(turned[0] - (1 - point[2])) < .002 && Math.abs(turned[1] - point[1]) < .002
                        && Math.abs(turned[2] - point[0]) < .002) {
                            found = true;
                            break;
                        }
                require(found, before.cells.get(i).role + " native mesh must follow the placement quarter turn");
            }
        }
    }

    private static void verifySkin(HologramState.Cell cell, boolean current) {
        switch (cell.index) {
            case 0:
                require(
                    HologramBlockRender.uniquePositions(cell, current) > 4
                        && HologramBlockRender.uniquePositions(cell, current) != 8,
                    "uncovered wedge retains native slope faces");
                break;
            case 1:
                icon(cell, current, Blocks.quartz_block.getIcon(2, 1));
                icon(cell, current, Blocks.planks.getIcon(2, 3));
                require(hasTint(cell, current, true), "Carpenter native red dye must color the covered wedge");
                break;
            case 2:
                icon(cell, current, Blocks.stained_hardened_clay.getIcon(2, 9));
                icon(cell, current, Blocks.planks.getIcon(2, 2));
                require(hasTint(cell, current, false), "LittleTiles native cyan tint must color its clay skin");
                break;
            case 3:
                icon(cell, current, Blocks.brick_block.getIcon(2, 0));
                icon(cell, current, Blocks.quartz_block.getIcon(2, 1));
                break;
            case 4:
                icon(cell, current, Blocks.quartz_block.getIcon(2, 2));
                require(HologramBlockRender.uniquePositions(cell, current) > 8, "curved native mesh retained");
                break;
            case 5:
                icon(cell, current, Blocks.planks.getIcon(2, 2));
                icon(cell, current, Blocks.stained_glass.getIcon(2, 11));
                break;
            case 6:
                icon(cell, current, Blocks.wool.getIcon(2, 4));
                break;
            case 7:
                TextureMap atlas = (TextureMap) Minecraft.getMinecraft()
                    .getTextureManager()
                    .getTexture(TextureMap.locationBlocksTexture);
                icon(cell, current, atlas.getAtlasSprite("architecturecraft:sawbench-wood"));
                icon(cell, current, atlas.getAtlasSprite("architecturecraft:sawbench-metal"));
                require(
                    HologramBlockRender.uniquePositions(cell, current) > 8,
                    "sawbench original objson model retained");
                break;
            default:
                throw new IllegalStateException("unreviewed decoration fixture");
        }
    }

    private static boolean hasTint(HologramState.Cell cell, boolean current, boolean red) {
        for (double[] vertex : HologramBlockRender.attributes(cell, current)) {
            int packed = (int) vertex[5];
            int r = packed & 255, g = packed >> 8 & 255, b = packed >> 16 & 255;
            if (red ? r > g * 1.2 && r > b * 1.2 : g > r * 1.5 && b > r * 1.5) return true;
        }
        return false;
    }

    private static void verifyCarpenterContext(ReferenceAccess reference, HologramState.Cell cell) {
        HologramRenderWorld world = reference.world;
        int original = world.getBlockMetadata(cell.x, cell.y, cell.z);
        require(
            !world.setBlockMetadataWithNotify(cell.x, cell.y, cell.z, 9, 4),
            "cover metadata writes outside render must fail");
        try (HologramRenderWorld.CarpenterContext ignored = world
            .carpenterRender(world.getTileEntity(cell.x, cell.y, cell.z))) {
            require(
                !world.setBlockMetadataWithNotify(cell.x, cell.y, cell.z, 9, 3),
                "cover metadata writes with notification flags must fail");
            require(
                !world.setBlockMetadataWithNotify(cell.x + 1, cell.y, cell.z, 9, 4),
                "cover metadata writes at other coordinates must fail");
            require(
                world.setBlockMetadataWithNotify(cell.x, cell.y, cell.z, 9, 4),
                "native cover metadata query can use isolated scratch state");
            require(
                world.getBlockMetadata(cell.x, cell.y, cell.z) == 9,
                "same render world sees temporary cover metadata");
            require(
                reference.getBlockMetadata(cell.x, cell.y, cell.z) == original,
                "temporary cover metadata cannot change source cells");
            require(
                !world.setBlock(cell.x, cell.y, cell.z, Blocks.air, 0, 4),
                "render context cannot permit block writes");
        }
        require(
            world.getBlockMetadata(cell.x, cell.y, cell.z) == original,
            "render context always restores cover metadata");
        require(
            !world.setBlockMetadataWithNotify(cell.x, cell.y, cell.z, 9, 4),
            "closed render context rejects cover metadata writes");
    }

    private static void verifyUnsupportedNestedTile(HologramState state) {
        NBTTagCompound hostile = (NBTTagCompound) state.data.copy();
        NBTTagCompound child = hostile.getTagList("cells", 10)
            .getCompoundTagAt(2)
            .getCompoundTag("targetDisplay")
            .getCompoundTag("nativeTile")
            .getCompoundTag("t0");
        child.setString("tID", "BlockTileEntity");
        NBTTagCompound nested = new NBTTagCompound();
        nested.setString("id", "gtmb.unreviewed-tesr-probe");
        child.setTag("tileEntity", nested);
        HologramBlockAccess rejected = new HologramBlockAccess(new HologramState(hostile), false);
        HologramState.Cell little = state.cells.get(2);
        require(
            rejected.getTileEntity(little.x, little.y, little.z) == null,
            "unknown LittleTiles nested TESR must not be instantiated or silently reduced to block cubes");
        require(
            rejected.failure(little.x, little.y, little.z)
                .contains("adapter required"),
            "unsupported LittleTiles nested renderer produces explicit adapter failure");
    }

    private static void icon(HologramState.Cell cell, boolean current, IIcon expected) {
        require(
            HologramBlockRender.usesIcon(cell, current, expected),
            cell.role + " must emit original atlas skin " + expected.getIconName());
    }

    private static NBTTagCompound attribute(ItemStack stack, int id) {
        NBTTagCompound row = stack.writeToNBT(new NBTTagCompound());
        row.setByte("cbAttribute", (byte) id);
        row.setString(
            "cbUniqueId",
            cpw.mods.fml.common.registry.GameRegistry.findUniqueIdentifierFor(stack.getItem())
                .toString());
        return row;
    }

    private static NBTTagCompound architecture(int shape, String base, int meta, String secondary, int secondaryMeta) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Shape", shape);
        tag.setString("BaseName", base);
        tag.setInteger("BaseData", meta);
        if (secondary != null) {
            tag.setString("Name2", secondary);
            tag.setInteger("Data2", secondaryMeta);
        }
        return tag;
    }

    private static NBTTagCompound little(String block, int meta, int x0, int y0, int z0, int x1, int y1, int z1) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("tID", "BlockTileBlock");
        row.setString("block", block);
        row.setInteger("meta", meta);
        row.setInteger("bSize", 1);
        String[] names = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
        int[] values = { x0, y0, z0, x1, y1, z1 };
        for (int i = 0; i < names.length; i++) row.setInteger("bBox0" + names[i], values[i]);
        return row;
    }

    private static void add(NBTTagList cells, Block block, int meta, NBTTagCompound tile, String role) {
        NBTTagCompound cell = new NBTTagCompound();
        int index = cells.tagCount();
        cell.setInteger("x", index % 4 * 2);
        cell.setInteger("y", 64 + index / 4 * 2);
        cell.setInteger("dx", index % 4 * 2);
        cell.setInteger("dy", index / 4 * 2);
        cell.setInteger("meta", meta);
        cell.setInteger("wantMeta", meta);
        cell.setString("id", Block.blockRegistry.getNameForObject(block));
        cell.setString("wantId", cell.getString("id"));
        cell.setString("status", "pending");
        cell.setString("role", role);
        cell.setString("structureId", "native-decoration-" + index);
        NBTTagCompound display = new NBTTagCompound();
        display.setTag("nativeTile", tile.copy());
        cell.setTag("actualDisplay", display.copy());
        cell.setTag("targetDisplay", display.copy());
        NBTTagCompound item = new ItemStack(block, 1, meta).writeToNBT(new NBTTagCompound());
        cell.setTag("actualStack", item.copy());
        cell.setTag("targetStack", item.copy());
        cells.appendTag(cell);
    }

    private static Block find(String type) {
        for (Object entry : Block.blockRegistry) if (entry != null && entry.getClass()
            .getName()
            .equals(type)) return (Block) entry;
        throw new IllegalStateException("installed native decoration block required: " + type);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /** Original factory/readers, independent of HologramNativeTiles and its adapter selection. */
    private static final class ReferenceAccess implements IBlockAccess {

        final Map<String, HologramState.Cell> cells = new HashMap<>();
        final Map<String, TileEntity> tiles = new HashMap<>();
        final HologramRenderWorld world = new HologramRenderWorld(this);
        final boolean current;

        ReferenceAccess(HologramState state, boolean current) {
            this.current = current;
            for (HologramState.Cell cell : state.cells) cells.put(key(cell.x, cell.y, cell.z), cell);
            for (HologramState.Cell cell : state.cells) {
                TileEntity tile = cell.block(current)
                    .createTileEntity(world, current ? cell.meta : cell.wantMeta);
                require(tile != null, "original native tile factory required");
                tiles.put(key(cell.x, cell.y, cell.z), tile);
            }
            for (HologramState.Cell cell : state.cells) {
                TileEntity tile = getTileEntity(cell.x, cell.y, cell.z);
                NBTTagCompound tag = (NBTTagCompound) (current ? cell.actualDisplay : cell.targetDisplay)
                    .getCompoundTag("nativeTile")
                    .copy();
                tag.setInteger("x", cell.x);
                tag.setInteger("y", cell.y);
                tag.setInteger("z", cell.z);
                tile.setWorldObj(world);
                tile.readFromNBT(tag);
            }
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }

        public Block getBlock(int x, int y, int z) {
            HologramState.Cell cell = cells.get(key(x, y, z));
            return cell == null ? Blocks.air : cell.block(current);
        }

        public int getBlockMetadata(int x, int y, int z) {
            HologramState.Cell cell = cells.get(key(x, y, z));
            return cell == null ? 0 : current ? cell.meta : cell.wantMeta;
        }

        public TileEntity getTileEntity(int x, int y, int z) {
            return tiles.get(key(x, y, z));
        }

        public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minimum) {
            return 0xf000f0;
        }

        public int isBlockProvidingPowerTo(int x, int y, int z, int side) {
            return 0;
        }

        public boolean isAirBlock(int x, int y, int z) {
            return getBlock(x, y, z) == Blocks.air;
        }

        public BiomeGenBase getBiomeGenForCoords(int x, int z) {
            return BiomeGenBase.plains;
        }

        public int getHeight() {
            return 256;
        }

        public boolean extendedLevelsInChunkCache() {
            return false;
        }

        public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
            return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
        }
    }
}
