// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.hologram.HologramProjectedVariantPricing.Row;
import com.miaokatze.gtmb.hologram.HologramWorkspace.ActionEndpoint;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Dormant proof for the exact archived Roof Tile tuples. This never installs, serializes, or publishes a tile. */
final class HologramProjectedRoofTileProof {

    private static final String SHAPE = "ArchitectureCraft:shape", TILE_ID = "gcewing.shape";
    private static final String ARCH = "gcewing.architecture.";
    private static final String LAVE = "Ztones:tile.laveBlock", HEMP = "chisel:hempcrete";

    private HologramProjectedRoofTileProof() {}

    static final class Declared {

        final String base;
        final int metadata, side, turn, occurrences;
        private final Row exact;

        private Declared(Row exact, String base, int metadata, int side, int turn) {
            this.exact = exact;
            this.base = base;
            this.metadata = metadata;
            this.side = side;
            this.turn = turn;
            occurrences = exact.count;
        }

        boolean matchesDecoded(String actualBase, int actualMeta, int actualShape, int actualSide, int actualTurn,
            boolean secondary, int disconnected, double offset, boolean chunkTicket) {
            return base.equals(actualBase) && metadata == actualMeta && actualShape == 0 && side == actualSide
                && turn == actualTurn && !secondary && disconnected == 0 && offset == 0 && !chunkTicket;
        }
    }

    /** Worker-safe: bounded detached syntax inspection only; no registered blocks, items, factories, or worlds. */
    static Declared declare(Row row) throws IOException {
        if (row == null || !SHAPE.equals(row.block)) throw refused();
        var schema = HologramProjectedVariantPricing.inspect(row, true);
        var tile = schema.tile;
        var item = schema.item;
        if (tile == null || item == null || tile.getInteger("Shape") != 0 || item.getInteger("Shape") != 0
            || !TILE_ID.equals(tile.getString("id")) || tile.hasKey("Name2") || tile.hasKey("Data2")
            || tile.hasKey("Disconnected")) throw refused();
        String base = tile.getString("BaseName");
        int meta = tile.getInteger("BaseData"), side = tile.getByte("side"), turn = tile.getByte("turn");
        if (!observedTuple(base, meta, side, turn) || !base.equals(item.getString("BaseName"))
            || meta != item.getInteger("BaseData")) throw refused();
        return new Declared(row, base, meta, side, turn);
    }

    private static boolean observedTuple(String base, int meta, int side, int turn) {
        if (side < 0 || side > 5 || turn < 0 || turn > 3) return false;
        int pose = side << 2 | turn;
        return HEMP.equals(base) && meta == 15 && (pose == 19 || pose == 9 || pose == 11)
            || LAVE.equals(base) && meta == 12 && switch (pose) {
                case 0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 15, 21 -> true;
                default -> false;
            };
    }

    static final class Verified {

        final Declared declared;
        final boolean posteaActive;

        private Verified(Declared declared, boolean posteaActive) {
            this.declared = declared;
            this.posteaActive = posteaActive;
        }
    }

    /** A proof only, not world/stock permission. The currently published server owner must run native decoding. */
    static Verified validateRegistered(ActionEndpoint endpoint, Declared expected) throws IOException {
        HologramConstructionTaskGate gate = HologramWorkspace.constructionGate(endpoint);
        if (gate == null || expected == null) throw refused();
        long generation = HologramNativePlacement.generation();
        try {
            Block block = registeredBlock(SHAPE, ARCH + "common.block.BlockShape");
            Item item = registeredItem(block, SHAPE, ARCH + "common.shape.ShapeItem");
            String baseType = HEMP.equals(expected.base) ? "team.chisel.block.BlockCarvable"
                : "com.riciJak.Ztones.block.MBlocks.BlockLave";
            String baseItemType = HEMP.equals(expected.base) ? "team.chisel.item.ItemCarvable"
                : "com.riciJak.Ztones.item.block.ItemDecoBlocks2";
            Block base = registeredBlock(expected.base, baseType);
            Item baseItem = registeredItem(base, expected.base, baseItemType);
            var adapter = HologramNativePlacement.adapter(block);
            // Other registered adapters can run virtual supports() even though only Builtin is accepted.
            requireCurrent(endpoint, gate, generation, block, item, expected.base, base, baseItem);
            if (adapter == null
                || !"com.miaokatze.gtmb.hologram.HologramNativePlacement$Builtin".equals(adapter.getClass().getName())
                || item.getMetadata(0) != 0 || !block.hasTileEntity(0)
                || baseItem.getMetadata(expected.metadata) != expected.metadata || base.hasTileEntity(expected.metadata))
                throw refused();

            ClassLoader loader = block.getClass().getClassLoader();
            Class<?> tileType = Class.forName(ARCH + "common.tile.TileShape", false, loader);
            Class<?> shapeType = Class.forName(ARCH + "common.shape.Shape", false, loader);
            Class<?> stateType = Class.forName(ARCH + "compat.MetaBlockState", false, loader);
            Class<?> mod = Class.forName(ARCH + "ArchitectureCraft", false, loader);
            Object roof = shapeType.getField("RoofTile").get(null);
            if (roof == null || roof.getClass() != shapeType || shapeType.getField("id").getInt(roof) != 0
                || shapeType.getMethod("forId", int.class).invoke(null, 0) != roof
                || shapeType.getField("materialUsed").getInt(roof) != 1
                || shapeType.getField("itemsProduced").getInt(roof) != 2
                || !registeredTile(tileType)) throw refused();
            boolean postea = mod.getField("posteaLoaded").getBoolean(null);

            // Decode fresh private bytes on the owner. The declaration crossed threads only as immutable primitives
            // and strings, never a mutable compound, registry value, future world, or preconstructed provider tile.
            var tag = HologramProjectedVariantPricing.inspect(expected.exact, true).tile;
            var detached = HologramResourceWorld.create(block, 0);
            requireCurrent(endpoint, gate, generation, block, item, expected.base, base, baseItem);
            if (!registeredTile(tileType)) throw refused();
            TileEntity actual = block.createTileEntity(detached, 0);
            if (actual == null || actual.getClass() != tileType
                || actual.getWorldObj() != null && actual.getWorldObj() != detached) throw refused();
            HologramNativePlacement.bindDetached(actual, detached, tag);
            requireCurrent(endpoint, gate, generation, block, item, expected.base, base, baseItem);
            if (!registeredTile(tileType)) throw refused();
            // Do not use Builtin.create/validateFactoryId: its factory check serializes a prototype tile.
            actual.readFromNBT(tag);
            Object decodedBase = tileType.getField("baseBlockState").get(actual);
            if (actual.getWorldObj() != detached || actual.isInvalid()
                || actual.xCoord != 0 || actual.yCoord != 0 || actual.zCoord != 0
                || tileType.getField("shape").get(actual) != roof
                || decodedBase == null || decodedBase.getClass() != stateType
                || stateType.getMethod("getBlock").invoke(decodedBase) != base) throw refused();

            // A canonical expected/actual NBT comparison would share TileShape's Postea remap/fallback.
            // Compare runtime fields independently to the already accepted original declared values.
            if (!expected.matchesDecoded(
                Block.blockRegistry.getNameForObject(base),
                stateType.getField("meta").getInt(decodedBase),
                shapeType.getField("id").getInt(roof),
                tileType.getField("side").getByte(actual),
                tileType.getField("turn").getByte(actual),
                tileType.getField("secondaryBlockState").get(actual) != null,
                tileType.getField("disabledConnections").getInt(actual),
                (Double) tileType.getMethod("getOffsetX").invoke(actual),
                tileType.getField("chunkTicket").get(actual) != null)) throw refused();
            requireCurrent(endpoint, gate, generation, block, item, expected.base, base, baseItem);
            if (mod.getField("posteaLoaded").getBoolean(null) != postea || !registeredTile(tileType)) throw refused();
            return new Verified(expected, postea);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IOException("Reviewed Roof Tile provider proof unavailable", failure);
        }
    }

    private static void requireCurrent(ActionEndpoint endpoint, HologramConstructionTaskGate gate, long generation,
        Block block, Item item, String baseName, Block base, Item baseItem) throws IOException {
        // Resolve transport first, then compare only the original registered objects and exact in-memory scope.
        if (HologramWorkspace.constructionGate(endpoint) != gate || !registrationCurrent(SHAPE, block, item)
            || !registrationCurrent(baseName, base, baseItem) || HologramNativePlacement.generation() != generation
            || !gate.scopeCurrent(endpoint.constructionScope)) throw refused();
    }

    private static boolean registrationCurrent(String name, Block block, Item item) {
        return Block.blockRegistry.getObject(name) == block && name.equals(Block.blockRegistry.getNameForObject(block))
            && Item.itemRegistry.getObject(name) == item && name.equals(Item.itemRegistry.getNameForObject(item))
            && Item.getItemFromBlock(block) == item && Block.getBlockFromItem(item) == block;
    }

    private static Block registeredBlock(String name, String type) throws IOException {
        Block block = Block.getBlockFromName(name);
        if (block == null || block == Blocks.air || !name.equals(Block.blockRegistry.getNameForObject(block))
            || !type.equals(block.getClass().getName())) throw refused();
        return block;
    }

    private static Item registeredItem(Block block, String name, String type) throws IOException {
        Item item = Item.getItemFromBlock(block);
        if (item == null || Item.itemRegistry.getObject(name) != item || Block.getBlockFromItem(item) != block
            || !name.equals(Item.itemRegistry.getNameForObject(item)) || !type.equals(item.getClass().getName()))
            throw refused();
        return item;
    }

    private static boolean registeredTile(Class<?> type) throws ReflectiveOperationException {
        Map<?, ?> names = (Map<?, ?>) ReflectionHelper.findField(TileEntity.class, "nameToClassMap", "field_145855_i")
            .get(null);
        Map<?, ?> types = (Map<?, ?>) ReflectionHelper.findField(TileEntity.class, "classToNameMap", "field_145853_j")
            .get(null);
        return names.get(TILE_ID) == type && TILE_ID.equals(types.get(type));
    }

    private static IOException refused() {
        return new IOException("Unreviewed Roof Tile declaration or detached native state");
    }
}
