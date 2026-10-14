package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechDeviceInformation;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Native decorative source fixtures for the opt-in sulfur chemistry line; no blueprint/session construction. */
final class HologramSulfurLineSmokeDecor {

    private static final String CARPENTER = "com.carpentersblocks.tileentity.TEBase";
    private static final String LITTLE_TILE = "com.creativemd.littletiles.common.utils.LittleTile";
    private static final String LITTLE_HOST = "com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles";
    private static final String ARCHITECTURE = "gcewing.architecture.common.tile.TileShape";
    private static final String PANEL = "shedar.mods.ic2.nuclearcontrol.tileentities.TileEntityAdvancedInfoPanel";
    private static final String WRAPPER = "shedar.mods.ic2.nuclearcontrol.panel.CardWrapperImpl";
    private static final String GT_CARD = "gregtech.common.items.ItemSensorCard";
    // GT .205 LCR has at most eleven native information lines, including conditional running/formed metrics.
    // NC's legacy GT card adapter reads bits 0..30 through DisplaySettingHelper.getAsInteger().
    private static final int LCR_MONITOR_SETTINGS = 11;
    private static final String[] FAMILIES = { "CarpentersBlocks", "ForgeMultipart", "LittleTiles", "ArchitectureCraft",
        "NuclearControl" };
    private static final String[] PANEL_SETTINGS = { "facing", "rotation", "showLabels", "colorText", "colorBackground",
        "dSettings1", "dSettings2", "dSettings3", "rotateHor", "rotateVert", "thickness", "powerMode",
        "transparencyMode", "textRotation", "dataSorters" };

    private HologramSulfurLineSmokeDecor() {}

    /** Four decorated native tile entities and one advanced panel, separated by one block of air. */
    public static Created create(WorldServer world, int x, int y, int z, int machineX, int machineY, int machineZ)
        throws ReflectiveOperationException {
        require(Boolean.getBoolean("gtmb.hologram.sulfurLineServer"), "sulfur line server opt-in required");
        require(
            world != null && !world.isRemote
                && world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-"),
            "decor fixture requires an isolated native server world");
        IGregTechTileEntity machine = machine(world, machineX, machineY, machineZ);
        require(
            machine instanceof IGregTechDeviceInformation
                && ((IGregTechDeviceInformation) machine).isGivingInformation(),
            "reactor exposes actual GT information");
        for (int i = 0; i < FAMILIES.length; i++) {
            int xx = x + i * 2;
            world.getChunkFromBlockCoords(xx, z);
            require(world.isAirBlock(xx, y, z), "fresh native decoration position " + FAMILIES[i]);
        }

        createCarpenter(world, x, y, z);
        createMultipart(world, x + 2, y, z);
        createLittleTiles(world, x + 4, y, z);
        createArchitecture(world, x + 6, y, z);
        createPanel(world, x + 8, y, z, machineX, machineY, machineZ);
        Created created = new Created(world, x, y, z, machineX, machineY, machineZ, machine);
        created.verifySource();
        return created;
    }

    /** Convenience for the fixed sulfur fixture whose first reactor is one east, one up and eight north of decor. */
    public static NBTTagList verifyPlaced(WorldServer target, int dx, int dy, int dz, int sourceDecorX,
        int sourceDecorY, int sourceDecorZ) throws ReflectiveOperationException {
        return verifyPlaced(
            target,
            dx,
            dy,
            dz,
            sourceDecorX,
            sourceDecorY,
            sourceDecorZ,
            sourceDecorX + 1,
            sourceDecorY + 1,
            sourceDecorZ - 8);
    }

    /** Independent fresh-process verification; supply the native line's recorded first-controller coordinate. */
    public static NBTTagList verifyPlaced(WorldServer target, int dx, int dy, int dz, int sourceDecorX,
        int sourceDecorY, int sourceDecorZ, int sourceMachineX, int sourceMachineY, int sourceMachineZ)
        throws ReflectiveOperationException {
        require(target != null && !target.isRemote, "placed decor requires native server world");
        int x = sourceDecorX + dx, y = sourceDecorY + dy, z = sourceDecorZ + dz;
        TileEntity[] placed = new TileEntity[FAMILIES.length];
        String[] types = { CARPENTER, "codechicken.multipart.TileMultipart", LITTLE_HOST, ARCHITECTURE, PANEL };
        for (int i = 0; i < placed.length; i++) {
            placed[i] = target.getTileEntity(x + 2 * i, y, z);
            require(
                Class.forName(types[i])
                    .isInstance(placed[i]) && placed[i].getWorldObj() == target
                    && placed[i].xCoord == x + 2 * i
                    && placed[i].yCoord == y
                    && placed[i].zCoord == z,
                "actual attached native tile in target world " + FAMILIES[i]);
        }
        require(
            target.getBlock(x, y, z) == registered("com.carpentersblocks.block.BlockCarpentersSlope")
                && target.getBlockMetadata(x, y, z) == 0,
            "placed native Carpenter slope block");
        Class<?> carpenter = Class.forName(CARPENTER);
        int wedge = Class.forName("com.carpentersblocks.data.Slope")
            .getField("ID_WEDGE_POS_N")
            .getByte(null);
        require(
            (Integer) carpenter.getMethod("getData")
                .invoke(placed[0]) == wedge,
            "placed native wedge geometry");
        sameStack(
            (ItemStack) carpenter.getMethod("getAttribute", byte.class)
                .invoke(placed[0], (byte) 6),
            new ItemStack(Blocks.quartz_block),
            "placed native quartz skin");
        sameStack(
            (ItemStack) carpenter.getMethod("getAttribute", byte.class)
                .invoke(placed[0], (byte) 5),
            new ItemStack(Blocks.planks, 1, 3),
            "placed native jungle side skin");
        require(
            snapshot(placed[0]).getTagList("cbAttrList", 10)
                .tagCount() == 2,
            "exactly two Carpenter skins");
        NBTTagList parts = snapshot(placed[1]).getTagList("parts", 10);
        require(parts.tagCount() == 2, "placed FMP contains two actual parts");
        part(parts, "mcr_face", 2 << 4, "minecraft:quartz_block");
        part(parts, "mcr_cnr", 3 << 4 | 7, "minecraft:brick_block");
        verifyLittleGeometry(snapshot(placed[2]));
        require(
            target.getBlock(x + 4, y, z) == Class.forName("com.creativemd.littletiles.LittleTiles")
                .getField("blockTile")
                .get(null),
            "placed native LittleTiles block");
        NBTTagCompound roof = snapshot(placed[3]);
        Class<?> shape = Class.forName("gcewing.architecture.common.shape.Shape");
        require(
            target.getBlock(x + 6, y, z) == registered("gcewing.architecture.common.block.BlockShape")
                && roof.getInteger("Shape") == shape.getField("id")
                    .getInt(
                        shape.getField("RoofTile")
                            .get(null))
                && "minecraft:quartz_block".equals(roof.getString("BaseName"))
                && roof.getInteger("BaseData") == 0
                && placed[3].getClass()
                    .getField("side")
                    .getByte(placed[3]) == 0
                && placed[3].getClass()
                    .getField("turn")
                    .getByte(placed[3]) == 1,
            "placed ArchitectureCraft native roof orientation and quartz material");
        int mx = sourceMachineX + dx, my = sourceMachineY + dy, mz = sourceMachineZ + dz;
        TileEntity panel = placed[4];
        int panelMetadata = Class.forName("shedar.mods.ic2.nuclearcontrol.utils.BlockDamages")
            .getField("DAMAGE_ADVANCED_PANEL")
            .getInt(null);
        require(
            target.getBlock(x + 8, y, z) == Class.forName("shedar.mods.ic2.nuclearcontrol.IC2NuclearControl")
                .getField("blockNuclearControlMain")
                .get(null) && target.getBlockMetadata(x + 8, y, z) == panelMetadata,
            "placed advanced industrial information panel block");
        IInventory inventory = (IInventory) panel;
        ItemStack card = inventory.getStackInSlot(0), registeredCard = ItemList.NC_SensorCard.get(1);
        require(
            card != null && card.stackSize == 1
                && card.getItem() == registeredCard.getItem()
                && card.getItemDamage() == registeredCard.getItemDamage()
                && GT_CARD.equals(
                    card.getItem()
                        .getClass()
                        .getName()),
            "placed real registered GT sensor card");
        for (int slot = 1; slot < inventory.getSizeInventory(); slot++)
            require(inventory.getStackInSlot(slot) == null, "placed panel has only the required native sensor card");
        Object wrapper = Class.forName(WRAPPER)
            .getConstructor(ItemStack.class, int.class)
            .newInstance(card, 0);
        ChunkCoordinates coordinate = (ChunkCoordinates) wrapper.getClass()
            .getMethod("getTarget")
            .invoke(wrapper);
        require(
            coordinate != null && coordinate.posX == mx && coordinate.posY == my && coordinate.posZ == mz,
            "placed native GT card coordinates translated to target reactor");
        require(
            "Sulfur chemistry reactor".equals(
                card.getTagCompound()
                    .getString("title"))
                && (Boolean) panel.getClass()
                    .getMethod("getPowered")
                    .invoke(panel)
                && snapshot(panel).getShort("facing") == 2,
            "native monitor title, orientation and power preserved");
        IGregTechTileEntity reactor = machine(target, mx, my, mz);
        require(
            ((IGregTechDeviceInformation) reactor).isGivingInformation(),
            "target reactor reports actual information");
        panel.getClass()
            .getMethod("processCard", ItemStack.class, int.class, int.class)
            .invoke(panel, card, 0, 0);
        verifyObservation(panel, reactor);
        Object display = panel.getClass()
            .getMethod("getNewDisplaySettingsForCardInSlot", int.class)
            .invoke(panel, 0);
        for (int i = 0; i < card.getTagCompound()
            .getInteger("mString"); i++)
            require(
                (Boolean) display.getClass()
                    .getMethod("getSetting", int.class)
                    .invoke(display, i),
                "native target display shows reactor information line " + i);
        NBTTagList evidence = new NBTTagList();
        for (int i = 0; i < placed.length; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("family", FAMILIES[i]);
            row.setIntArray("position", new int[] { x + 2 * i, y, z });
            row.setString("block", Block.blockRegistry.getNameForObject(target.getBlock(x + 2 * i, y, z)));
            row.setInteger("metadata", target.getBlockMetadata(x + 2 * i, y, z));
            row.setTag("nativeTile", snapshot(placed[i]));
            evidence.appendTag(row);
        }
        return evidence;
    }

    private static void createCarpenter(WorldServer world, int x, int y, int z) throws ReflectiveOperationException {
        TileEntity tile = put(world, x, y, z, registered("com.carpentersblocks.block.BlockCarpentersSlope"), 0);
        Class<?> type = Class.forName(CARPENTER);
        require(type.isInstance(tile), "native Carpenter slope tile");
        int wedge = Class.forName("com.carpentersblocks.data.Slope")
            .getField("ID_WEDGE_POS_N")
            .getByte(null);
        byte[] covers = (byte[]) type.getField("ATTR_COVER")
            .get(null);
        type.getMethod("setData", int.class)
            .invoke(tile, wedge);
        type.getMethod("addAttribute", byte.class, ItemStack.class)
            .invoke(tile, covers[6], new ItemStack(Blocks.quartz_block));
        type.getMethod("addAttribute", byte.class, ItemStack.class)
            .invoke(tile, covers[5], new ItemStack(Blocks.planks, 1, 3));
        require(
            (Integer) type.getMethod("getData")
                .invoke(tile) == wedge,
            "native slope geometry applied");
        sameStack(
            (ItemStack) type.getMethod("getAttribute", byte.class)
                .invoke(tile, covers[6]),
            new ItemStack(Blocks.quartz_block),
            "native quartz slope skin");
        sameStack(
            (ItemStack) type.getMethod("getAttribute", byte.class)
                .invoke(tile, covers[5]),
            new ItemStack(Blocks.planks, 1, 3),
            "native jungle slope side skin");
    }

    private static void createMultipart(WorldServer world, int x, int y, int z) throws ReflectiveOperationException {
        // Use the real Scala singleton factories. Neither hand-written part NBT nor a bare TileMultipart is installed.
        Object registry = module("codechicken.microblock.MicroMaterialRegistry");
        Object multipart = module("codechicken.multipart.TileMultipart");
        Class<?> coordinate = Class.forName("codechicken.lib.vec.BlockCoord");
        Class<?> partType = Class.forName("codechicken.multipart.TMultiPart");
        Object position = coordinate.getConstructor(int.class, int.class, int.class)
            .newInstance(x, y, z);
        Method canPlace = multipart.getClass()
            .getMethod("canPlacePart", World.class, coordinate, partType);
        Method add = multipart.getClass()
            .getMethod("addPart", World.class, coordinate, partType);
        String[] classes = { "codechicken.microblock.FaceMicroClass", "codechicken.microblock.CornerMicroClass" };
        String[] materials = { "minecraft:quartz_block", "minecraft:brick_block" };
        int[] sizes = { 2, 3 }, slots = { 0, 14 };
        for (int i = 0; i < classes.length; i++) {
            require(
                registry.getClass()
                    .getMethod("getMaterial", String.class)
                    .invoke(registry, materials[i]) != null,
                "registered real FMP material " + materials[i]);
            int material = (Integer) registry.getClass()
                .getMethod("materialID", String.class)
                .invoke(registry, materials[i]);
            require(
                materials[i].equals(
                    registry.getClass()
                        .getMethod("materialName", int.class)
                        .invoke(registry, material)),
                "FMP material ID resolves exactly");
            Object microClass = module(classes[i]);
            Object part = microClass.getClass()
                .getMethod("create", boolean.class, int.class)
                .invoke(microClass, false, material);
            part.getClass()
                .getMethod("setShape", int.class, int.class)
                .invoke(part, sizes[i], slots[i]);
            require((Boolean) canPlace.invoke(multipart, world, position, part), "native FMP part passes occlusion");
            require(
                add.invoke(multipart, world, position, part) == world.getTileEntity(x, y, z),
                "native multipart factory installs generated host");
        }
        NBTTagList parts = snapshot(world.getTileEntity(x, y, z)).getTagList("parts", 10);
        require(parts.tagCount() == 2, "two native microblocks saved");
        part(parts, "mcr_face", 2 << 4, materials[0]);
        part(parts, "mcr_cnr", 3 << 4 | 7, materials[1]);
    }

    private static void createLittleTiles(WorldServer world, int x, int y, int z) throws ReflectiveOperationException {
        Block block = (Block) Class.forName("com.creativemd.littletiles.LittleTiles")
            .getField("blockTile")
            .get(null);
        TileEntity host = put(world, x, y, z, block, 0);
        Class<?> hostType = Class.forName(LITTLE_HOST), littleType = Class.forName(LITTLE_TILE);
        Class<?> blockType = Class.forName("com.creativemd.littletiles.common.utils.LittleTileBlock");
        Class<?> boxType = Class.forName("com.creativemd.littletiles.common.utils.small.LittleTileBox");
        require(hostType.isInstance(host), "native LittleTiles host created");
        Block[] materials = { Blocks.quartz_block, Blocks.planks };
        int[][] boxes = { { 1, 0, 2, 7, 12, 14 }, { 7, 0, 2, 15, 4, 14 } };
        for (int i = 0; i < boxes.length; i++) {
            Object little = blockType.getConstructor(Block.class, int.class)
                .newInstance(materials[i], i == 0 ? 0 : 3);
            int[] b = boxes[i];
            Object box = boxType.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                .newInstance(b[0], b[1], b[2], b[3], b[4], b[5]);
            require(
                (Boolean) hostType.getMethod("isSpaceForLittleTile", boxType)
                    .invoke(host, box),
                "non-full LittleTiles box fits native host");
            littleType.getField("boundingBox")
                .set(little, box);
            littleType.getField("te")
                .set(little, host);
            littleType.getMethod("place")
                .invoke(little);
        }
        host.markDirty();
        world.markBlockForUpdate(x, y, z);
        verifyLittleGeometry(snapshot(host));
    }

    private static void verifyLittleGeometry(NBTTagCompound saved) {
        require(saved.getInteger("tilesCount") == 2, "two native LittleTiles boxes saved");
        Block[] materials = { Blocks.quartz_block, Blocks.planks };
        int[][] boxes = { { 1, 0, 2, 7, 12, 14 }, { 7, 0, 2, 15, 4, 14 } };
        for (int i = 0; i < boxes.length; i++) {
            NBTTagCompound tile = saved.getCompoundTag("t" + i);
            require(
                "BlockTileBlock".equals(tile.getString("tID")) && Block.blockRegistry.getNameForObject(materials[i])
                    .equals(tile.getString("block")) && tile.getInteger("meta") == (i == 0 ? 0 : 3),
                "native LittleTiles material saved " + i);
            String[] coordinates = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
            for (int j = 0; j < coordinates.length; j++)
                require(tile.getInteger("bBox0" + coordinates[j]) == boxes[i][j], "native LittleTiles box saved");
        }
    }

    private static void createArchitecture(WorldServer world, int x, int y, int z) throws ReflectiveOperationException {
        put(world, x, y, z, registered("gcewing.architecture.common.block.BlockShape"), 0);
        Class<?> shapeType = Class.forName("gcewing.architecture.common.shape.Shape");
        Object roof = shapeType.getField("RoofTile")
            .get(null);
        Class<?> tileType = Class.forName(ARCHITECTURE);
        TileEntity tile = (TileEntity) tileType.getConstructor(shapeType, Block.class, int.class)
            .newInstance(roof, Blocks.quartz_block, 0);
        tileType.getMethod("setSide", int.class)
            .invoke(tile, 0);
        tileType.getMethod("setTurn", int.class)
            .invoke(tile, 1);
        world.setTileEntity(x, y, z, tile);
        tile.markDirty();
        world.markBlockForUpdate(x, y, z);
        NBTTagCompound saved = snapshot(tile);
        require(
            saved.getInteger("Shape") == shapeType.getField("id")
                .getInt(roof) && "minecraft:quartz_block".equals(saved.getString("BaseName")),
            "native roof shape and quartz saved");
    }

    private static void createPanel(WorldServer world, int x, int y, int z, int machineX, int machineY, int machineZ)
        throws ReflectiveOperationException {
        Class<?> nuclear = Class.forName("shedar.mods.ic2.nuclearcontrol.IC2NuclearControl");
        Block block = (Block) nuclear.getField("blockNuclearControlMain")
            .get(null);
        int metadata = Class.forName("shedar.mods.ic2.nuclearcontrol.utils.BlockDamages")
            .getField("DAMAGE_ADVANCED_PANEL")
            .getInt(null);
        TileEntity panel = put(world, x, y, z, block, metadata);
        Class<?> type = Class.forName(PANEL);
        require(type.isInstance(panel) && panel instanceof IInventory, "native advanced industrial panel created");
        ItemStack card = ItemList.NC_SensorCard.get(1);
        require(
            card != null && GT_CARD.equals(
                card.getItem()
                    .getClass()
                    .getName()),
            "real registered GT machine card");
        Object wrapper = Class.forName(WRAPPER)
            .getConstructor(ItemStack.class, int.class)
            .newInstance(card, 0);
        wrapper.getClass()
            .getMethod("setTarget", int.class, int.class, int.class)
            .invoke(wrapper, machineX, machineY, machineZ);
        wrapper.getClass()
            .getMethod("setTitle", String.class)
            .invoke(wrapper, "Sulfur chemistry reactor");
        type.getMethod("setFacing", short.class)
            // The native setter stores Facing.oppositeSide[input]; input SOUTH yields persisted NORTH.
            .invoke(panel, (short) 3);
        type.getMethod("setPowerMode", byte.class)
            .invoke(
                panel,
                (byte) type.getField("POWER_ON")
                    .getInt(null));
        ((IInventory) panel).setInventorySlotContents(0, card);
        // Native processCard performs range/target validation, calls ItemSensorCard.update and records CardState.
        type.getMethod("processCard", ItemStack.class, int.class, int.class)
            .invoke(panel, card, 0, 0);
        Class<?> settings = Class.forName("shedar.mods.ic2.nuclearcontrol.api.DisplaySettingHelper");
        Object display = settings.getConstructor()
            .newInstance();
        // all_true is an in-memory option and is not serialized by DisplaySettingHelper.toBitString().
        // The just-placed LCR has fewer lines than the naturally formed/running LCR. Persist its full audited
        // eleven-line option range so later energy, efficiency and recipe-count rows remain visible.
        int informationLines = card.getTagCompound()
            .getInteger("mString");
        require(
            informationLines > 0 && informationLines <= LCR_MONITOR_SETTINGS && LCR_MONITOR_SETTINGS <= 31,
            "native LCR monitor information fits the audited GT card display option range");
        for (int i = 0; i < LCR_MONITOR_SETTINGS; i++) settings.getMethod("addSetting", boolean.class)
            .invoke(display, true);
        type.getMethod("setDisplaySettings", byte.class, settings)
            .invoke(panel, (byte) 0, display);
        panel.markDirty();
        verifyObservation(panel, machine(world, machineX, machineY, machineZ));
        world.markBlockForUpdate(x, y, z);
    }

    private static IGregTechTileEntity machine(WorldServer world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        require(
            tile instanceof IGregTechTileEntity && ((IGregTechTileEntity) tile).getMetaTileEntity() != null,
            "actual reactor machine at monitored coordinate");
        return (IGregTechTileEntity) tile;
    }

    private static void verifyObservation(TileEntity panel, IGregTechTileEntity machine) {
        ItemStack card = ((IInventory) panel).getStackInSlot(0);
        NBTTagCompound tag = card.getTagCompound();
        IGregTechDeviceInformation information = (IGregTechDeviceInformation) machine;
        List<String> lines = new ArrayList<>(Arrays.asList(information.getInfoData()));
        information.getExtraInfoData(lines);
        require(
            !lines.isEmpty() && tag != null && tag.getInteger("mString") == lines.size(),
            "GT sensor cache contains actual machine information");
        for (int i = 0; i < lines.size(); i++) require(
            lines.get(i)
                .equals(tag.getString("mString" + i)),
            "native GT sensor reads real machine line " + i);
    }

    private static TileEntity put(WorldServer world, int x, int y, int z, Block block, int metadata) {
        require(block != null && world.setBlock(x, y, z, block, metadata, 3), "native decoration block placed");
        TileEntity tile = world.getTileEntity(x, y, z);
        require(tile != null, "native decoration tile created by block");
        return tile;
    }

    private static Object module(String name) throws ReflectiveOperationException {
        return Class.forName(name + "$")
            .getField("MODULE$")
            .get(null);
    }

    private static Block registered(String type) {
        for (Object entry : Block.blockRegistry) if (entry != null && type.equals(
            entry.getClass()
                .getName()))
            return (Block) entry;
        throw new IllegalStateException("installed native block required: " + type);
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        require(tile != null, "native tile exists for snapshot");
        NBTTagCompound tag = new NBTTagCompound();
        tile.writeToNBT(tag);
        return tag;
    }

    private static NBTTagCompound localPayload(NBTTagCompound tag) {
        NBTTagCompound copy = (NBTTagCompound) tag.copy();
        copy.removeTag("x");
        copy.removeTag("y");
        copy.removeTag("z");
        return copy;
    }

    private static void sameStack(ItemStack actual, ItemStack expected, String message) {
        require(actual != null && ItemStack.areItemStacksEqual(actual, expected), message);
    }

    private static void part(NBTTagList parts, String id, int shape, String material) {
        for (int i = 0; i < parts.tagCount(); i++) {
            NBTTagCompound tag = parts.getCompoundTagAt(i);
            if (id.equals(tag.getString("id")) && tag.getByte("shape") == shape
                && material.equals(tag.getString("material"))) return;
        }
        throw new IllegalStateException("missing native microblock geometry/material " + id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static final class Created {

        final int x, y, z, machineX, machineY, machineZ;
        private final WorldServer world;
        private final Block[] blocks = new Block[FAMILIES.length];
        private final TileEntity[] tiles = new TileEntity[FAMILIES.length];
        private final NBTTagCompound[] saved = new NBTTagCompound[FAMILIES.length];
        private final int[] metadata = new int[FAMILIES.length];
        private final String machineType;
        private final ItemStack card;

        private Created(WorldServer world, int x, int y, int z, int machineX, int machineY, int machineZ,
            IGregTechTileEntity machine) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.machineX = machineX;
            this.machineY = machineY;
            this.machineZ = machineZ;
            machineType = machine.getMetaTileEntity()
                .getClass()
                .getName();
            for (int i = 0; i < FAMILIES.length; i++) {
                blocks[i] = world.getBlock(x + i * 2, y, z);
                metadata[i] = world.getBlockMetadata(x + i * 2, y, z);
                tiles[i] = world.getTileEntity(x + i * 2, y, z);
                saved[i] = snapshot(tiles[i]);
            }
            card = ((IInventory) tiles[4]).getStackInSlot(0)
                .copy();
        }

        /** The driver unions these decorative positions with the complete native machine/pipe area. */
        public int[] bounds() {
            return new int[] { x, y, z, x + 8, y, z };
        }

        public int count() {
            return FAMILIES.length;
        }

        public NBTTagCompound sourceNbt(int index) {
            return (NBTTagCompound) saved[index].copy();
        }

        /** Real live tile snapshots, including NC's naturally changing native sensor observations. */
        public NBTTagList checkpoint() {
            NBTTagList rows = new NBTTagList();
            for (int i = 0; i < FAMILIES.length; i++) {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("family", FAMILIES[i]);
                row.setIntArray("position", new int[] { x + i * 2, y, z });
                row.setString("block", Block.blockRegistry.getNameForObject(blocks[i]));
                row.setInteger("metadata", world.getBlockMetadata(x + i * 2, y, z));
                row.setTag("nativeTile", snapshot(world.getTileEntity(x + i * 2, y, z)));
                rows.appendTag(row);
            }
            return rows;
        }

        /** Exact decor preservation; NC card bindings/configuration persist while native observations may update. */
        public void verifySource() throws ReflectiveOperationException {
            for (int i = 0; i < FAMILIES.length; i++) {
                int xx = x + i * 2;
                require(
                    world.getBlock(xx, y, z) == blocks[i] && world.getBlockMetadata(xx, y, z) == metadata[i],
                    "source native block/metadata unchanged " + FAMILIES[i]);
                require(
                    world.getTileEntity(xx, y, z) == tiles[i] && tiles[i].getWorldObj() == world
                        && tiles[i].xCoord == xx
                        && tiles[i].yCoord == y
                        && tiles[i].zCoord == z,
                    "source native tile identity unchanged " + FAMILIES[i]);
                if (i < 4) require(
                    saved[i].equals(snapshot(tiles[i])),
                    "source native geometry/skin unchanged " + FAMILIES[i]);
            }
            verifyPanel(tiles[4], machineX, machineY, machineZ);
        }

        /** Translation is read from actual target-world tile persistence and CardWrapperImpl, never a preview DTO. */
        public void verifyTarget(WorldServer target, int dx, int dy, int dz) throws ReflectiveOperationException {
            require(target != null && !target.isRemote, "native target server world");
            for (int i = 0; i < FAMILIES.length; i++) {
                int xx = x + i * 2 + dx, yy = y + dy, zz = z + dz;
                require(
                    target.getBlock(xx, yy, zz) == blocks[i] && target.getBlockMetadata(xx, yy, zz) == metadata[i],
                    "target native block/metadata " + FAMILIES[i]);
                TileEntity tile = target.getTileEntity(xx, yy, zz);
                require(
                    tile != null && tile.getWorldObj() == target
                        && tile.xCoord == xx
                        && tile.yCoord == yy
                        && tile.zCoord == zz,
                    "target native tile attached " + FAMILIES[i]);
                if (i < 4) require(
                    localPayload(saved[i]).equals(localPayload(snapshot(tile))),
                    "target native geometry/material preserved " + FAMILIES[i]);
                else {
                    verifyPanel(tile, machineX + dx, machineY + dy, machineZ + dz);
                    IGregTechTileEntity reactor = machine(target, machineX + dx, machineY + dy, machineZ + dz);
                    require(
                        machineType.equals(
                            reactor.getMetaTileEntity()
                                .getClass()
                                .getName()),
                        "monitor points to actual translated reactor in target world");
                    tile.getClass()
                        .getMethod("processCard", ItemStack.class, int.class, int.class)
                        .invoke(tile, ((IInventory) tile).getStackInSlot(0), 0, 0);
                    verifyObservation(tile, reactor);
                }
            }
        }

        private void verifyPanel(TileEntity tile, int mx, int my, int mz) throws ReflectiveOperationException {
            require(
                Class.forName(PANEL)
                    .isInstance(tile) && tile instanceof IInventory,
                "actual advanced NC panel");
            IInventory inventory = (IInventory) tile;
            ItemStack installed = inventory.getStackInSlot(0);
            require(
                installed != null && installed.stackSize == 1
                    && installed.getItem() == card.getItem()
                    && installed.getItemDamage() == card.getItemDamage()
                    && GT_CARD.equals(
                        installed.getItem()
                            .getClass()
                            .getName()),
                "original native GT machine card type preserved");
            for (int slot = 1; slot < inventory.getSizeInventory(); slot++)
                require(inventory.getStackInSlot(slot) == null, "NC inventory contains exactly one real sensor card");
            Object wrapper = Class.forName(WRAPPER)
                .getConstructor(ItemStack.class, int.class)
                .newInstance(installed, 0);
            ChunkCoordinates target = (ChunkCoordinates) wrapper.getClass()
                .getMethod("getTarget")
                .invoke(wrapper);
            require(
                target != null && target.posX == mx && target.posY == my && target.posZ == mz,
                "native machine card target follows captured reactor translation");
            require(
                card.getTagCompound()
                    .getString("title")
                    .equals(
                        installed.getTagCompound()
                            .getString("title")),
                "native sensor title preserved");
            NBTTagCompound current = snapshot(tile);
            for (String key : PANEL_SETTINGS) require(
                saved[4].hasKey(key) == current.hasKey(key) && (!saved[4].hasKey(key) || saved[4].getTag(key)
                    .equals(current.getTag(key))),
                "advanced NC display configuration preserved " + key);
        }
    }
}
