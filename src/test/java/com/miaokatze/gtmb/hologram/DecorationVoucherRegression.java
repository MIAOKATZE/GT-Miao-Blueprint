package com.miaokatze.gtmb.hologram;

import java.util.Arrays;
import java.util.Collections;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ShapelessRecipes;

import com.gtnewhorizon.structurelib.item.ItemConstructableTrigger;
import com.miaokatze.gtmb.common.items.hologram.ItemDecorationVoucher;
import com.miaokatze.gtmb.register.CreativeTabManager;
import com.miaokatze.gtmb.register.DecorationVoucherRegistry;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** No Forge startup needed: actual shapeless matching, namespace policy, and industrial vetoes. */
public final class DecorationVoucherRegression {

    private DecorationVoucherRegression() {}

    public static void main(String[] args) throws Exception {
        if (com.miaokatze.gtmb.testutil.MinecraftTestBootstrap.enter(DecorationVoucherRegression.class, args)) run();
    }

    public static void run() {
        try {
            DecorationVoucherConfigRegression.run();
            HologramVoucherCoverageRegression.run();
            enderIoGlass();
        } catch (Exception failure) {
            throw new AssertionError("Voucher configuration", failure);
        }
        Item voucher = new ItemDecorationVoucher();
        Item official = new ItemConstructableTrigger();
        SimpleAssert.eq(64, voucher.getItemStackLimit(), "voucher stacks as consumable banknotes");
        SimpleAssert.eq(CreativeTabManager.CREATIVE_TAB, voucher.getCreativeTab(), "voucher is on own item tab");
        ShapelessRecipes recipe = DecorationVoucherRegistry.createRecipe(voucher, official);
        InventoryCrafting grid = new InventoryCrafting(new Container() {

            @Override
            public boolean canInteractWith(EntityPlayer player) {
                return true;
            }
        }, 3, 3);
        for (int left = 0; left < 9; left++) for (int right = left + 1; right < 9; right++) {
            grid.setInventorySlotContents(left, new ItemStack(official));
            SimpleAssert.that(!recipe.matches(grid, null), "one native projector cannot buy a voucher");
            grid.setInventorySlotContents(right, new ItemStack(official));
            SimpleAssert.that(recipe.matches(grid, null), "two native projectors work at any distinct positions");
            ItemStack output = recipe.getCraftingResult(grid);
            SimpleAssert.eq(voucher, output.getItem(), "voucher recipe produces the registered item type");
            SimpleAssert.eq(1, output.stackSize, "two projectors produce one voucher");
            output.stackSize = 63;
            SimpleAssert.eq(1, recipe.getCraftingResult(grid).stackSize, "craft results are detached");
            grid.setInventorySlotContents(left, null);
            grid.setInventorySlotContents(right, null);
        }
        grid.setInventorySlotContents(0, new ItemStack(official));
        grid.setInventorySlotContents(1, new ItemStack(official));
        grid.setInventorySlotContents(2, new ItemStack(official));
        SimpleAssert.that(!recipe.matches(grid, null), "extra native projector rejected");
        grid.setInventorySlotContents(2, new ItemStack(Items.feather));
        SimpleAssert.that(!recipe.matches(grid, null), "unrelated ingredient rejected");
        DecorationVoucherConfig.View enabled = new DecorationVoucherConfig.View(
            true,
            Arrays.asList(" LittleTiles ", "ArchitectureCraft", "minecraft", "", "bad:id", null));
        SimpleAssert.that(enabled.allows("LITTLETILES"), "native mixed case IDs compare consistently");
        SimpleAssert.that(enabled.allows("ArchitectureCraft"), "ArchitectureCraft namespace accepted");
        SimpleAssert.that(!enabled.allows("bad:id"), "full item IDs cannot accidentally become allowed mods");
        SimpleAssert.that(!enabled.allows("unknown"), "unknown namespace fails closed");
        SimpleAssert.that(
            !new DecorationVoucherConfig.View(false, Collections.singletonList("LittleTiles")).allows("LittleTiles"),
            "disabled policy rejects otherwise allowed decorations");
        for (String industrial : new String[] { "gregtech", "appliedenergistics2", "bartworks", "miscutils",
            "GoodGenerator", "IC2", "tectech", "ExtraUtilities", "EnderIO" }) {
            SimpleAssert.that(
                HologramDecorationVoucher.industrial(industrial, "example.Item"),
                "administrator mod allowlist cannot waive industrial resources: " + industrial);
        }
        SimpleAssert.that(
            HologramDecorationVoucher.industrial("decor", "appeng.items.ItemMultiPart"),
            "native AE item remains protected under an alternate namespace");
        SimpleAssert.that(
            HologramDecorationVoucher.industrial("decor", "gregtech.common.blocks.ItemMachines"),
            "native GT machine remains protected under an alternate namespace");
        SimpleAssert.that(
            !HologramDecorationVoucher.industrial("minecraft", "net.minecraft.item.ItemBlock"),
            "native validated decor adapters may waive vanilla raw blocks");
        SimpleAssert.that(
            !HologramDecorationVoucher.eligible(new ItemStack(Items.feather)),
            "ordinary vanilla items cannot become decorative merely through a display label");
        SimpleAssert.that(
            !HologramDecorationVoucher.safe(new ItemStack(Items.water_bucket)),
            "filled fluid containers remain payable resources");
        SimpleAssert.that(
            !HologramDecorationVoucher.isVoucher(new ItemStack(voucher)),
            "unregistered voucher placeholder does not authenticate payment");
    }

    private static void enderIoGlass() throws ReflectiveOperationException {
        java.lang.reflect.Field config = DecorationVoucherConfig.class.getDeclaredField("current");
        config.setAccessible(true);
        Object previous = config.get(null);
        try {
            config.set(null, DecorationVoucherConfig.defaults());
            for (String name : new String[] { "appliedenergistics2:tile.BlockQuartzGlass",
                "appliedenergistics2:tile.BlockQuartzLamp" }) {
                Block quartz = fixture(name, Material.glass);
                SimpleAssert.that(
                    HologramDecorationVoucher.covered(quartz, new ItemStack(quartz, 1, 0)),
                    "ordinary and vibrant AE quartz glass are explicitly covered");
                SimpleAssert.that(
                    !HologramDecorationVoucher.eligible(new ItemStack(quartz, 1, 1)),
                    "unregistered AE glass variants are not admitted by the exception");
            }
            Block aeMachine = fixture("appliedenergistics2:tile.BlockController", Material.iron);
            SimpleAssert.that(
                !HologramDecorationVoucher.eligible(new ItemStack(aeMachine)),
                "AE controllers retain the industrial veto");
            Block glass = fixture("EnderIO:blockFusedQuartz", Material.glass);
            for (int meta = 0; meta < 6; meta++) {
                ItemStack sample = new ItemStack(glass, 1, meta);
                SimpleAssert.that(
                    HologramDecorationVoucher.covered(glass, sample),
                    "registered Ender IO glass metadata is covered: " + meta
                        + " block="
                        + Block.blockRegistry.getNameForObject(glass)
                        + " item="
                        + Item.itemRegistry.getNameForObject(sample.getItem())
                        + " match="
                        + (Block.getBlockFromItem(sample.getItem()) == glass)
                        + " safe="
                        + HologramDecorationVoucher.safe(sample)
                        + " allowed="
                        + DecorationVoucherConfig.view()
                            .allows(sample));
            }
            SimpleAssert.that(
                !HologramDecorationVoucher.eligible(new ItemStack(glass, 1, 6)),
                "undefined metadata cannot acquire the Ender IO glass exception");
            Block machine = fixture("EnderIO:blockAlloySmelter", Material.iron);
            config.set(null, new DecorationVoucherConfig.View(true, Collections.singleton("EnderIO")));
            SimpleAssert.that(
                !HologramDecorationVoucher.eligible(new ItemStack(machine)),
                "even an entire Ender IO allow rule never waives machines");
            DecorationVoucherConfig.View defaults = DecorationVoucherConfig.defaults();
            config.set(
                null,
                new DecorationVoucherConfig.View(
                    true,
                    defaults.allowedModIds,
                    defaults.allowedBlocks,
                    defaults.allowedOres,
                    defaults.deniedModIds,
                    Collections.singleton("enderio:blockfusedquartz@2"),
                    defaults.deniedOres));
            SimpleAssert.that(
                !HologramDecorationVoucher.eligible(new ItemStack(glass, 1, 2))
                    && HologramDecorationVoucher.eligible(new ItemStack(glass, 1, 0)),
                "individual glass blacklist overrides the default exception");
        } finally {
            config.set(null, previous);
        }
    }

    private static Block fixture(String name, Material material) throws ReflectiveOperationException {
        return registeredFixture(name, new Block(material) {});
    }

    static Block registeredFixture(String name, Block block) throws ReflectiveOperationException {
        Block existing = (Block) Block.blockRegistry.getObject(name);
        if (existing != null && existing != net.minecraft.init.Blocks.air) return existing;
        int free = 3700;
        while (free > 0
            && (Item.getItemById(free) != null || Block.getBlockById(free) != net.minecraft.init.Blocks.air)) free--;
        if (free == 0) throw new AssertionError("No isolated glass fixture registry slot");
        // Vanilla's addObject routes through GameData and prefixes minecraft: outside an active Mod container.
        java.lang.reflect.Method add = cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry.class
            .getDeclaredMethod("add", int.class, String.class, Object.class, java.util.BitSet.class);
        add.setAccessible(true);
        add.invoke(Block.blockRegistry, free, name, block, new java.util.BitSet());
        add.invoke(Item.itemRegistry, free, name, new ItemBlock(block), new java.util.BitSet());
        return block;
    }
}
