// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.hologram.HologramStockEbfProfile.Installed;
import com.miaokatze.gtmb.hologram.HologramStockEbfProfile.Part;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import cpw.mods.fml.relauncher.Side;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.metatileentity.implementations.MTEHatchMuffler;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.metatileentity.implementations.MTETieredMachineBlock;
import gregtech.common.blocks.BlockCasings1;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.blocks.ItemCasings;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Dedicated offline process: other historical fixtures initialize GT's same static declaration with dummy casings. */
public final class HologramStockEbfProfileRegression {

    private static final int X = 42, Y = 80, Z = -21;
    private static final int[] IDS = { 1000, 44, 71, 61, 90, 91, 81 };

    private HologramStockEbfProfileRegression() {}

    public static void main(String[] args) {
        int status = 0;
        try {
            if (MinecraftTestBootstrap.enter(HologramStockEbfProfileRegression.class, args)) {
                HologramNativeSavedWorldRegression.withOfflineRegistries(HologramStockEbfProfileRegression::run);
                System.out.println("HologramStockEbfProfileRegression passed (offline, no world mutation)");
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            status = 1;
        }
        // This separate JavaExec owns vanilla's lifetime non-daemon I/O threads.
        System.exit(status);
    }

    @FunctionalInterface
    interface ConstructionCheck {

        void run(sun.misc.Unsafe unsafe, HologramStockEbfConstructionRegression.RawWorld world,
            BaseMetaTileEntity controller, ExtendedFacing facing, List<Installed> installed)
            throws java.io.IOException, ReflectiveOperationException;
    }

    static void run() {
        run(null);
    }

    static void run(ConstructionCheck check) {
        Thread thread = Thread.currentThread();
        String originalName = thread.getName();
        // Forge's effective side uses the actual owner-thread name, not the side of the bootstrap logger.
        thread.setName("Server thread");
        try {
            SimpleAssert.eq(
                Side.SERVER,
                FMLCommonHandler.instance()
                    .getEffectiveSide(),
                "standalone EBF is on Forge's effective server side before initializing GregTech");
            runOnServerThread(check);
        } finally {
            thread.setName(originalName);
        }
    }

    private static void runOnServerThread(ConstructionCheck check) {
        var saved = new IMetaTileEntity[IDS.length];
        for (int i = 0; i < IDS.length; i++) saved[i] = GregTechAPI.METATILEENTITIES[IDS[i]];
        Block savedCasing = GregTechAPI.sBlockCasings1, savedCoil = GregTechAPI.sBlockCasings5;
        Block savedMachines = GregTechAPI.sBlockMachines;
        String adapterId = "gtmb_test:ebf_must_not_probe_extension";
        HologramNativePlacement.Adapter unexpected = new HologramNativePlacement.Adapter() {

            @Override
            public boolean supports(Block block) {
                throw new AssertionError("audited stock qualification entered an unrelated extension callback");
            }

            @Override
            public TileEntity create(Block block, int meta, NBTTagCompound payload, World world) {
                throw new AssertionError("qualification must not construct an extension tile");
            }

            @Override
            public List<ItemStack> materials(Block block, int meta, TileEntity tile) {
                throw new AssertionError("qualification must not request extension materials");
            }
        };
        // The standalone profile checks prove the closed EBF path never queries an extension. The supplied
        // construction fixture also exercises generic declared materials, whose admission deliberately queries
        // registered placement adapters. Do not apply the closed-profile tripwire to that separate contract.
        if (check == null) HologramNativePlacement.register(adapterId, unexpected);
        try {
            var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            access.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) access.get(null);
            GregTechAPI.sBlockCasings1 = registered(
                unsafe,
                savedCasing,
                BlockCasings1.class,
                "gtmb_test:ebf_real_casing");
            GregTechAPI.sBlockCasings5 = registered(unsafe, savedCoil, BlockCasings5.class, "gtmb_test:ebf_real_coil");
            if (savedMachines == null) GregTechAPI.sBlockMachines = Blocks.iron_block;
            var machine = new MTEElectricBlastFurnace("gtmb_stock_ebf_offline");
            GregTechAPI.METATILEENTITIES[1000] = machine;
            var world = (FixtureWorld) unsafe.allocateInstance(FixtureWorld.class);
            world.blocks = new HashMap<>();
            world.tiles = new HashMap<>();
            Pos origin = new Pos(X, Y, Z);
            var controller = install(unsafe, world, origin, machine, 1000, ForgeDirection.EAST);
            ExtendedFacing facing = machine.getExtendedFacing();
            List<Installed> selections = Arrays.asList(
                hatch(unsafe, world, facing, MTEHatchEnergy.class, 44, 4, -1, 0, 0),
                hatch(unsafe, world, facing, MTEHatchInputBus.class, 71, 1, 1, 0, 0),
                hatch(unsafe, world, facing, MTEHatchOutput.class, 61, 1, -1, -3, 1),
                hatch(unsafe, world, facing, MTEHatchMaintenance.class, 90, 1, -1, 0, 1),
                hatch(unsafe, world, facing, MTEHatchMuffler.class, 91, 1, 0, -3, 1));
            if (check != null) {
                var placed = HologramStockEbfConstructionRegression.RawWorld.create(unsafe, origin.x, origin.z);
                for (var entry : world.blocks.entrySet()) {
                    var pos = entry.getKey();
                    var block = entry.getValue();
                    placed.seed(pos.x, pos.y, pos.z, block.block, block.meta);
                }
                for (var entry : world.tiles.entrySet()) {
                    var pos = entry.getKey();
                    var tile = entry.getValue();
                    tile.setWorldObj(placed);
                    placed.seedTile(pos.x, pos.y, pos.z, tile);
                }
                check.run(unsafe, placed, controller, facing, selections);
                return;
            }
            var profile = HologramStockEbfProfile.inspectRegistered();
            SimpleAssert.that(
                profile != null && HologramStockEbfProfile.CONTROLLER == 1000,
                "actual stock EBF static declaration, not a synthetic shape");
            SimpleAssert.eq(
                0,
                HologramStockEbfProfile.coilMetadata(1, new NBTTagCompound()),
                "default master signal resolves stock Cupronickel metadata");
            var kanthal = new NBTTagCompound();
            kanthal.setInteger("coil", 2);
            SimpleAssert.eq(1, HologramStockEbfProfile.coilMetadata(6, kanthal), "explicit coil overrides master");
            var channels = new NBTTagCompound();
            channels.setInteger("coil", 6);
            SimpleAssert.eq(9, HologramStockEbfProfile.coilMetadata(1, channels), "LuV HSSS is meta 9, not 5");
            var edited = (NBTTagCompound) channels.copy();
            edited.setInteger("gt_hatch", 3);
            SimpleAssert
                .eq(-1, HologramStockEbfProfile.coilMetadata(1, edited), "never discard edited hatch selection");

            var firstCoil = at(facing, -1, -2, 0);
            world.blocks.put(firstCoil, new BlockState(GregTechAPI.sBlockCasings5, 9));
            long before = world.loadReads;
            var qualified = profile.qualify(world, controller, facing, 1, channels, selections);
            SimpleAssert.that(
                qualified != null && qualified.coilMeta() == 9
                    && qualified.linksCurrent()
                    && world.loadReads - before == 28,
                "real rotated role pins plus 16 coil checks, no whole shell/world capture");
            SimpleAssert.that(
                Block.getBlockFromItem(
                    qualified.component(Part.TOP)
                        .getItem())
                    == GregTechAPI.sBlockCasings1
                    && qualified.component(Part.TOP)
                        .getItemDamage() == 11
                    && qualified.component(Part.COIL)
                        .getItem() == Item.getItemFromBlock(GregTechAPI.sBlockCasings5)
                    && qualified.component(Part.MUFFLER) == null
                    && qualified.component(Part.AIR) == null,
                "qualified two-material proof contains no material for air or installed hatches");
            world.blocks.put(firstCoil, new BlockState(GregTechAPI.sBlockCasings5, 1));
            SimpleAssert.that(
                profile.qualify(world, controller, facing, 1, channels, selections) == null,
                "an installed different coil tier makes qualification impossible before any mutation");
            world.blocks.put(firstCoil, new BlockState(GregTechAPI.sBlockCasings5, 9));
            world.missing = at(facing, 1, -1, 2);
            SimpleAssert.that(
                profile.qualify(world, controller, facing, 1, channels, selections) == null,
                "an unloaded coil coordinate is refused without a chunk load");
            world.missing = null;

            var topOutputBus = hatch(unsafe, world, facing, MTEHatchOutputBus.class, 81, 1, 1, -3, 0);
            var invalidRole = new ArrayList<>(selections);
            invalidRole.set(2, topOutputBus);
            SimpleAssert.that(
                profile.qualify(world, controller, facing, 1, channels, invalidRole) == null,
                "a real stock output bus at the top cannot satisfy the top fluid-output chain");
            var duplicate = new ArrayList<>(selections);
            duplicate.set(3, selections.get(0));
            SimpleAssert.that(
                profile.qualify(world, controller, facing, 1, channels, duplicate) == null,
                "duplicate coordinates/roles cannot fund a missing maintenance position");
            var wronglyFaced = new ArrayList<>(selections);
            var muffler = selections.get(4);
            wronglyFaced.set(
                4,
                new Installed(muffler.a(), muffler.b(), muffler.c(), muffler.id(), ForgeDirection.DOWN.ordinal()));
            SimpleAssert.that(
                profile.qualify(world, controller, facing, 1, channels, wronglyFaced) == null,
                "exact installed muffler pose is retained, not overwritten");

            var existing = at(facing, muffler.a(), muffler.b(), muffler.c());
            var originalTile = world.tiles.remove(existing);
            SimpleAssert.that(
                qualified.linksCurrent() && !qualified.physicalCurrent(),
                "detached but still-valid original hatch cannot satisfy live physical revalidation");
            world.tiles.put(existing, originalTile);
            world.onTileReadAt = existing;
            world.onTileRead = () -> world.blocks.put(firstCoil, new BlockState(GregTechAPI.sBlockCasings5, 1));
            SimpleAssert.that(
                qualified.linksCurrent() && !qualified.physicalCurrent() && world.onTileRead == null,
                "post-callback revalidation rejects changed coil metadata before another permit could commit");
            world.blocks.put(firstCoil, new BlockState(GregTechAPI.sBlockCasings5, 9));
            world.onTileReadAt = existing;
            world.onTileRead = () -> world.blocks.put(firstCoil, new BlockState(GregTechAPI.sBlockCasings5, 1));
            SimpleAssert.that(
                profile.qualify(world, controller, facing, 1, channels, selections) == null && world.onTileRead == null,
                "initial qualification also rejects the coil altered from a protected read");
            world.blocks.put(firstCoil, new BlockState(GregTechAPI.sBlockCasings5, 9));
            var elements = machine.getStructureDefinition()
                .getStructureFor("main");
            var originalElement = elements[0];
            elements[0] = StructureUtility.isAir();
            try {
                SimpleAssert
                    .that(!qualified.linksCurrent(), "declared backing array swap invalidates the qualified proof");
            } finally {
                elements[0] = originalElement;
            }
            var actor = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            actor.inventory = new InventoryPlayer(actor);
            var inventory = actor.inventory;
            var settings = new HologramMaterials.Settings();
            settings.containers = settings.me = false;
            var casing = qualified.component(Part.TOP);
            var coil = qualified.component(Part.COIL);
            casing.stackSize = HologramStockEbfProfile.CASING_BOUND;
            coil.stackSize = HologramStockEbfProfile.COIL_BOUND;
            inventory.mainInventory[1] = casing;
            inventory.mainInventory[2] = coil;
            SimpleAssert.that(
                qualified.physicalCurrent() && qualified.fullyFunded(inventory, 0, settings)
                    && casing.stackSize + coil.stackSize == 28,
                "both exact stock identities fund at most 28 shell writes");
            settings.priority = 1;
            SimpleAssert.that(
                qualified.fullyFunded(inventory, 0, settings) && casing.stackSize + coil.stackSize == 28,
                "priority orders enabled providers; an enabled main source remains valid and is not debited");
            settings.main = false;
            settings.containers = true;
            SimpleAssert.that(
                !qualified.fullyFunded(inventory, 0, settings) && casing.stackSize + coil.stackSize == 28,
                "container-only qualification cannot count or debit disabled main stock");
            settings.main = true;
            settings.containers = false;
            settings.priority = 0;
            coil.setItemDamage(1);
            SimpleAssert
                .that(!qualified.fullyFunded(inventory, 0, settings), "wrong coil metadata cannot fund selected tier");
            coil.setItemDamage(9);
            coil.setTagCompound(new NBTTagCompound());
            SimpleAssert.that(!qualified.fullyFunded(inventory, 0, settings), "tagged stock is not interchangeable");
            coil.setTagCompound(null);
            inventory.mainInventory[0] = coil;
            inventory.mainInventory[2] = null;
            SimpleAssert.that(
                !qualified.fullyFunded(inventory, 0, settings) && casing.stackSize == 12
                    && coil.stackSize == 16
                    && world.permissionReads == 0,
                "never count the protected slot, debit, call placement permissions, or write world during qualification");
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("actual EBF offline profile fixture", failure);
        } finally {
            if (check == null) HologramNativePlacement.unregister(adapterId, unexpected);
            for (int i = 0; i < IDS.length; i++) GregTechAPI.METATILEENTITIES[IDS[i]] = saved[i];
            GregTechAPI.sBlockCasings1 = savedCasing;
            GregTechAPI.sBlockCasings5 = savedCoil;
            GregTechAPI.sBlockMachines = savedMachines;
        }
    }

    private static Block registered(sun.misc.Unsafe unsafe, Block prior, Class<? extends Block> type, String name)
        throws ReflectiveOperationException {
        if (prior != null && prior.getClass() == type && Item.getItemFromBlock(prior) instanceof ItemCasings)
            return prior;
        Block held = (Block) Block.blockRegistry.getObject(name);
        if (held != null && held.getClass() == type && Item.itemRegistry.getObject(name) instanceof ItemCasings)
            return held;
        var block = (Block) unsafe.allocateInstance(type);
        var delegate = Block.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        delegate.set(block, ((FMLControlledNamespacedRegistry) Block.blockRegistry).getDelegate(block, Block.class));
        block.stepSound = Blocks.iron_block.stepSound;
        int id = 3775;
        while (id >= 3728 && (Block.getBlockById(id) != Blocks.air || Item.getItemById(id) != null)) id--;
        SimpleAssert.that(
            id >= 3728 && (held == null || held == Blocks.air) && Item.itemRegistry.getObject(name) == null,
            "unused paired exact EBF fixture id and name");
        Item item = new ItemCasings(block);
        Block.blockRegistry.addObject(id, name, block);
        Item.itemRegistry.addObject(id, name, item);
        SimpleAssert.that(
            Block.getBlockById(id) == block && Item.getItemById(id) == item && Item.getItemFromBlock(block) == item,
            "native EBF fixture retained actual registered ItemCasings identity");
        return block;
    }

    private static Installed hatch(sun.misc.Unsafe unsafe, FixtureWorld world, ExtendedFacing facing,
        Class<? extends MTEHatch> type, int id, int tier, int a, int b, int c) throws ReflectiveOperationException {
        var installed = (MTEHatch) unsafe.allocateInstance(type);
        var prototype = (MTEHatch) unsafe.allocateInstance(type);
        var field = MTETieredMachineBlock.class.getDeclaredField("mTier");
        long tierOffset = unsafe.objectFieldOffset(field);
        unsafe.putByte(installed, tierOffset, (byte) tier);
        unsafe.putByte(prototype, tierOffset, (byte) tier);
        GregTechAPI.METATILEENTITIES[id] = prototype;
        install(unsafe, world, at(facing, a, b, c), installed, id, ForgeDirection.WEST);
        return new Installed(a, b, c, id, ForgeDirection.WEST.ordinal());
    }

    private static BaseMetaTileEntity install(sun.misc.Unsafe unsafe, FixtureWorld world, Pos position,
        IMetaTileEntity machine, int id, ForgeDirection facing) throws ReflectiveOperationException {
        var tile = new BaseMetaTileEntity();
        tile.setWorldObj(world);
        tile.xCoord = position.x;
        tile.yCoord = position.y;
        tile.zCoord = position.z;
        tile.setMetaTileID((short) id);
        var direction = BaseMetaTileEntity.class.getDeclaredField("mFacing");
        direction.setAccessible(true);
        direction.set(tile, facing);
        machine.setBaseMetaTileEntity(tile);
        world.blocks.put(position, new BlockState(GregTechAPI.sBlockMachines, 0));
        world.tiles.put(position, tile);
        return tile;
    }

    private static Pos at(ExtendedFacing facing, int a, int b, int c) {
        int[] offset = new int[3];
        facing.getWorldOffset(new int[] { a, b, c }, offset);
        return new Pos(X + offset[0], Y + offset[1], Z + offset[2]);
    }

    private static final class Pos {

        final int x, y, z;

        Pos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Pos)) return false;
            Pos point = (Pos) other;
            return x == point.x && y == point.y && z == point.z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }
    }

    private static final class BlockState {

        final Block block;
        final int meta;

        BlockState(Block block, int meta) {
            this.block = block;
            this.meta = meta;
        }
    }

    private static final class FixtureWorld extends HologramServiceEditRegression.ReadOnlyWorld {

        Map<Pos, BlockState> blocks;
        Map<Pos, TileEntity> tiles;
        Pos missing, onTileReadAt;
        Runnable onTileRead;

        @Override
        public boolean blockExists(int x, int y, int z) {
            super.blockExists(x, y, z);
            return !new Pos(x, y, z).equals(missing);
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            var state = blocks.get(new Pos(x, y, z));
            return state == null ? Blocks.air : state.block;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            var state = blocks.get(new Pos(x, y, z));
            return state == null ? 0 : state.meta;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            var position = new Pos(x, y, z);
            if (onTileRead != null && position.equals(onTileReadAt)) {
                Runnable mutation = onTileRead;
                onTileRead = null;
                mutation.run();
            }
            return tiles.get(position);
        }
    }
}
