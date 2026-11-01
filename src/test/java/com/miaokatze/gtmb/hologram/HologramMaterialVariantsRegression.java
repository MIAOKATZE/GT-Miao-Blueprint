package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.function.Function;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.MaterialVariants;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Independent native types prove that planned material support is extensible and grants no actual-world proof. */
public final class HologramMaterialVariantsRegression {

    private static final int OFFSET = 1000;

    private HologramMaterialVariantsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramMaterialVariantsRegression.class, args)) return;
        run();
    }

    static void run() throws Exception {
        int before = GTMBExtensions.descriptors()
            .size();
        exactLifecycle();
        vanillaCacheGeneration();
        SimpleAssert.eq(
            before,
            GTMBExtensions.descriptors()
                .size(),
            "material registration tests retire every public descriptor");
    }

    private static void exactLifecycle() throws Exception {
        NativeBlock block = (NativeBlock) fixture("material_variants", new NativeBlock(), NativeItem::new);
        Block unknownBlock = fixture("material_variants_subclass", new UnreviewedBlock(), NativeItem::new);
        Block unknownItem = fixture("material_variants_item_subclass", new NativeBlock(), UnreviewedItem::new);
        int metadata = 7;
        ItemStack paid = new ItemStack(block, 1, OFFSET + metadata);
        HologramBlueprintValidationCache validation = new HologramBlueprintValidationCache(
            new HologramPresets.Preset());
        expectMaterialFailure(() -> validation.validateMaterialVariant(block, metadata, paid));
        block.picks = block.factories = 0;
        long before = MaterialVariants.generation();
        GTMBExtensions.Registration registration = MaterialVariants.registerExact(
            "gtmb_test:material-variant",
            NativeBlock.class,
            NativeItem.class,
            NativeBlock.class,
            value -> OFFSET + value);
        SimpleAssert.eq("material-variant", registration.descriptor().kind, "catalog exposes the material extension");
        SimpleAssert.that(MaterialVariants.generation() > before, "install changes the material generation");
        long installed = MaterialVariants.generation();
        try {
            SimpleAssert.eq(0, block.picks, "registration matches native method identity without invoking its pick");
            SimpleAssert.eq(0, block.factories, "registration never probes a tile factory");
            SimpleAssert.eq(
                OFFSET + metadata,
                validation.materialDamage(block, metadata),
                "the same validation owner observes an installed independent material projection");
            validation.validateMaterialVariant(block, metadata, paid);
            SimpleAssert.eq(0, block.picks, "planned validation invokes only the pure metadata projection");
            NativeTile actual = new NativeTile();
            actual.materialDamage = paid.getItemDamage();
            SimpleAssert.eq(
                null,
                HologramMaterialDescriptor.actualDamage(block, metadata, actual),
                "a public planned projection grants no actual snapshot reader");
            SimpleAssert.eq(0, block.picks, "an unproved actual snapshot never probes the native pick");
            SimpleAssert.that(
                !validation.ordinaryMaterial(block, metadata),
                "a planned projection cannot grant the ordinary fast path even when the block reports no tile");
            expectMaterialFailure(
                () -> validation.validateMaterialVariant(block, metadata, new ItemStack(block, 1, metadata)));
            SimpleAssert.eq(
                0,
                HologramMaterialDescriptor.kind(unknownBlock),
                "an unknown block subclass does not inherit a registered material identity");
            SimpleAssert.eq(
                0,
                HologramMaterialDescriptor.kind(unknownItem),
                "an unknown native item subclass does not inherit a registered material identity");
            expectMaterialFailure(
                () -> HologramPresets.validateMaterialVariant(
                    unknownBlock,
                    metadata,
                    new ItemStack(unknownBlock, 1, OFFSET + metadata)));
            expectMaterialFailure(
                () -> HologramPresets
                    .validateMaterialVariant(unknownItem, metadata, new ItemStack(unknownItem, 1, OFFSET + metadata)));
            rejectedRegistration(
                () -> MaterialVariants.registerExact(
                    "gtmb_test:material-variant-duplicate",
                    NativeBlock.class,
                    NativeItem.class,
                    NativeBlock.class,
                    value -> value),
                "an existing exact block/item binding cannot be replaced");
            rejectedRegistration(
                () -> MaterialVariants.registerExact(
                    "gtmb_test:material-variant-wrong-native",
                    NativeBlock.class,
                    UnreviewedItem.class,
                    Block.class,
                    value -> value),
                "a registration cannot claim a different native pick implementation");
            actualWorldIdentity(block, metadata, paid);
            SimpleAssert.eq(0, block.factories, "planning and actual identity checks never construct a tile");
        } finally {
            registration.close();
        }
        long retired = MaterialVariants.generation();
        SimpleAssert.that(!registration.active(), "the public material handle retires");
        registration.close();
        SimpleAssert.eq(retired, MaterialVariants.generation(), "repeated close does not change generation");
        SimpleAssert.that(retired > installed, "removal changes the material generation");
        SimpleAssert.eq(0, HologramMaterialDescriptor.kind(block), "retirement removes the exact planned identity");
        expectMaterialFailure(() -> validation.validateMaterialVariant(block, metadata, paid));
        try (GTMBExtensions.Registration replacement = MaterialVariants.registerExact(
            "gtmb_test:material-variant",
            NativeBlock.class,
            NativeItem.class,
            NativeBlock.class,
            value -> OFFSET * 2 + value)) {
            registration.close();
            SimpleAssert.eq(
                OFFSET * 2 + metadata,
                validation.materialDamage(block, metadata),
                "an old handle cannot remove a later registration with the same ID");
        }
    }

    private static void actualWorldIdentity(NativeBlock block, int metadata, ItemStack paid) throws Exception {
        HologramResourceWorld world = HologramResourceWorld.create(block, metadata);
        HologramCapture.Cell placed = new HologramCapture.Cell(null, 0, 64, 0);
        placed.block = block;
        placed.meta = metadata;
        expectMaterialFailure(() -> HologramCellPolicy.validateOrdinaryPlacedVariant(world, placed, paid));
        NativeTile tile = new NativeTile();
        tile.setWorldObj(world);
        tile.xCoord = placed.x;
        tile.yCoord = placed.y;
        tile.zCoord = placed.z;
        world.tile = tile;
        tile.materialDamage = paid.getItemDamage();
        SimpleAssert.that(
            world.getBlock(placed.x, placed.y, placed.z) == block && Block.getBlockFromItem(paid.getItem()) == block,
            "the actual fixture and paid item bind the same native block at the tested coordinate");
        HologramCellPolicy.validateOrdinaryPlacedVariant(world, placed, paid);
        tile.materialDamage++;
        expectMaterialFailure(() -> HologramCellPolicy.validateOrdinaryPlacedVariant(world, placed, paid));
        SimpleAssert.eq(
            paid.getItemDamage() + 1,
            block.getDamageValue(world, placed.x, placed.y, placed.z),
            "planned metadata never replaces the actual native tile identity");
    }

    private static void vanillaCacheGeneration() throws Exception {
        Block block = Blocks.wool;
        int metadata = 3;
        HologramBlueprintValidationCache validation = new HologramBlueprintValidationCache(
            new HologramPresets.Preset());
        int nativeDamage = validation.materialDamage(block, metadata);
        SimpleAssert.eq(nativeDamage, validation.materialDamage(block, metadata), "native vanilla damage is cached");
        SimpleAssert.eq(1L, validation.damageHits, "the fixture exercises a retained vanilla result");
        int[] projections = { 0 };
        try (GTMBExtensions.Registration registration = MaterialVariants.registerExact(
            "gtmb_test:material-variant-vanilla-generation",
            block.getClass(),
            Item.getItemFromBlock(block)
                .getClass(),
            Block.class,
            value -> {
                projections[0]++;
                return nativeDamage + OFFSET;
            })) {
            SimpleAssert.eq(
                nativeDamage + OFFSET,
                validation.materialDamage(block, metadata),
                "install invalidates an already retained vanilla result");
            SimpleAssert.eq(
                nativeDamage + OFFSET,
                validation.materialDamage(block, metadata),
                "extension callback results are never cached, including for exact vanilla block classes");
            SimpleAssert.eq(2, projections[0], "every registered projection is evaluated again");
            SimpleAssert.that(
                !validation.ordinaryMaterial(block, metadata),
                "vanilla class recognition cannot elevate a registered projection to an actual-world proof");
        }
        SimpleAssert.eq(
            nativeDamage,
            validation.materialDamage(block, metadata),
            "the same validation owner returns to native behavior after retirement");
        SimpleAssert
            .that(validation.ordinaryMaterial(block, metadata), "retirement restores the original vanilla proof");
    }

    private static void rejectedRegistration(Runnable attempt, String message) {
        int before = GTMBExtensions.descriptors()
            .size();
        long generation = MaterialVariants.generation();
        try {
            attempt.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {}
        SimpleAssert.eq(
            before,
            GTMBExtensions.descriptors()
                .size(),
            "failed material installation leaves no catalog descriptor");
        SimpleAssert.eq(generation, MaterialVariants.generation(), "failed installation leaves no material generation");
    }

    private static Block fixture(String name, Block block, Function<Block, ItemBlock> item) {
        String registry = "gtmb:" + name;
        Object existing = Block.blockRegistry.getObject(registry);
        if (existing instanceof Block && existing != Blocks.air) return (Block) existing;
        int slot = 3960;
        while (slot > 0 && (Item.getItemById(slot) != null || Block.getBlockById(slot) != Blocks.air)) slot--;
        if (slot == 0) throw new AssertionError("No isolated material fixture registry slot");
        Block.blockRegistry.addObject(slot, registry, block);
        Item.itemRegistry.addObject(slot, registry, item.apply(block));
        return block;
    }

    private static void expectMaterialFailure(MaterialCheck check) throws Exception {
        try {
            check.run();
            throw new AssertionError("an unproved or unpaid material variant was accepted");
        } catch (IOException expected) {}
    }

    private interface MaterialCheck {

        void run() throws Exception;
    }

    private static class NativeBlock extends Block {

        int picks, factories;

        NativeBlock() {
            super(Material.iron);
        }

        @Override
        public int getDamageValue(World world, int x, int y, int z) {
            picks++;
            TileEntity tile = world.getTileEntity(x, y, z);
            return tile instanceof NativeTile ? ((NativeTile) tile).materialDamage : 0;
        }

        @Override
        public TileEntity createTileEntity(World world, int metadata) {
            factories++;
            throw new AssertionError("material identity cannot probe a tile factory");
        }
    }

    private static final class UnreviewedBlock extends NativeBlock {
    }

    private static class NativeItem extends ItemBlock {

        NativeItem(Block block) {
            super(block);
            setHasSubtypes(true);
        }

        @Override
        public int getMetadata(int damage) {
            return damage - OFFSET;
        }
    }

    private static final class UnreviewedItem extends NativeItem {

        UnreviewedItem(Block block) {
            super(block);
        }
    }

    private static final class NativeTile extends TileEntity {

        int materialDamage;
    }
}
