// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.stats.StatBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.oredict.OreDictionary;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.CommonBaseMetaTileEntity;
import gregtech.api.metatileentity.CommonMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.threads.RunnableMachineUpdate;
import gregtech.common.blocks.BlockMachines;
import gregtech.common.blocks.ItemMachines;

/** Exact geometry, real vanilla chest callbacks, native failure receipts and Forge capture/permission boundaries. */
public final class HologramDemolitionRegression {

    private HologramDemolitionRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramDemolitionRegression.class, args)) return;
        run();
        System.out.println("HologramDemolitionRegression PASS");
    }

    public static void run() {
        try {
            geometry();
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            Scene world = scene(unsafe);
            Player player = (Player) unsafe.allocateInstance(Player.class);
            player.worldObj = world;
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.inventory.mainInventory[0] = new ItemStack(Items.diamond_pickaxe);
            player.edit = true;
            EventBus originalBus = MinecraftForge.EVENT_BUS;
            Guard guard = new Guard(originalBus);
            Field busField = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            Object busBase = unsafe.staticFieldBase(busField);
            long busOffset = unsafe.staticFieldOffset(busField);
            unsafe.putObject(busBase, busOffset, guard);
            try {
                captureIsolation(unsafe, world);
                protection(world, player, guard);
                chest(world, player);
                nativeFailure(world, player);
                declaredToolLifecycle(world, player, guard);
                stargateOres(world, player);
                try (AutoCloseable nativeScope = HologramDemolitionDestinationRegression.nativeClassScope()) {
                    nativeGtHatches(unsafe, world, player);
                }
            } finally {
                unsafe.putObject(busBase, busOffset, originalBus);
            }
        } catch (Exception failure) {
            throw new AssertionError("area demolition", failure);
        }
    }

    private static void geometry() throws IOException {
        HologramAreaSelection selection = new HologramAreaSelection();
        selection.point(1, -17, 14, 15);
        selection.point(2, -15, 17, 17);
        selection.point(1, -16, 13, 15, "add");
        selection.point(2, -14, 18, 16, "add");
        selection.point(1, -16, 15, 15, "subtract");
        selection.point(2, -16, 16, 16, "subtract");
        HologramDemolition.Cursor cursor = new HologramDemolition.Cursor(selection.resolvedRegions(), 256);
        Set<String> expected = new HashSet<>(), actual = new HashSet<>();
        for (int y = 13; y <= 18; y++) for (int x = -17; x <= -14; x++)
            for (int z = 15; z <= 17; z++) if (selection.contains(x, y, z)) expected.add(x + "," + y + "," + z);
        int previous = Integer.MAX_VALUE, steps = 0;
        while (!cursor.done()) {
            SimpleAssert.that(++steps < 1000, "bounded cursor completes disjoint masks");
            if (!cursor.step((cx, sy, cz) -> false)) continue;
            SimpleAssert.that(cursor.y <= previous, "every global upper layer precedes every lower layer");
            previous = cursor.y;
            SimpleAssert.that(actual.add(cursor.x + "," + cursor.y + "," + cursor.z), "overlap never demolishes twice");
        }
        SimpleAssert
            .eq(expected, actual, "add/subtract masks visit exactly the selected cells across chunk boundaries");
        HologramDemolition.Cursor skipped = new HologramDemolition.Cursor(selection.resolvedRegions(), 256);
        Set<String> nonempty = new HashSet<>();
        while (!skipped.done())
            if (skipped.step((cx, sy, cz) -> sy == 1)) nonempty.add(skipped.x + "," + skipped.y + "," + skipped.z);
        expected.removeIf(key -> Integer.parseInt(key.split(",")[1]) >= 16);
        SimpleAssert.eq(expected, nonempty, "clipped empty sections skip only their own selected planes");
        HologramDemolition.Cursor unloaded = new HologramDemolition.Cursor(selection.resolvedRegions(), 256);
        boolean rejected = false;
        try {
            while (!unloaded.done()) unloaded.step((cx, sy, cz) -> { throw new IOException("unloaded"); });
        } catch (IOException expectedFailure) {
            rejected = true;
        }
        SimpleAssert.that(rejected && !unloaded.done(), "missing chunk stops before advancing any cell in that plane");
        HologramAreaSelection invalid = new HologramAreaSelection();
        invalid.point(1, 0, -1, 0);
        invalid.point(2, 0, 1, 0);
        rejected = false;
        try {
            new HologramDemolition.Cursor(invalid.resolvedRegions(), 256);
        } catch (IOException expectedFailure) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "invalid world height is rejected before task admission");
    }

    private static Scene scene(sun.misc.Unsafe unsafe) throws Exception {
        Scene scene = (Scene) unsafe.allocateInstance(Scene.class);
        Field random = World.class.getDeclaredField("rand");
        random.setAccessible(true);
        random.set(scene, new Random(1));
        Field providerField = World.class.getDeclaredField("provider");
        providerField.setAccessible(true);
        WorldProviderSurface provider = new WorldProviderSurface();
        provider.worldObj = scene;
        providerField.set(scene, provider);
        scene.rules = new GameRules();
        scene.mine = true;
        return scene;
    }

    private static void captureIsolation(sun.misc.Unsafe unsafe, Scene world) throws Exception {
        EntityItem outside = new EntityItem(world, 0, 64, 0, new ItemStack(Items.diamond, 3));
        SimpleAssert.that(
            !MinecraftForge.EVENT_BUS.post(joinEvent(outside, world)),
            "ordinary drops outside scope stay native");
        try (HologramDemolition.DropScope scope = new HologramDemolition.DropScope(world)) {
            Scene other = scene(unsafe);
            EntityItem foreign = new EntityItem(other, 0, 64, 0, new ItemStack(Items.diamond));
            SimpleAssert
                .that(!MinecraftForge.EVENT_BUS.post(joinEvent(foreign, other)), "another world is never captured");
            EntityJoinWorldEvent canceled = joinEvent(outside, world);
            canceled.setCanceled(true);
            MinecraftForge.EVENT_BUS.post(canceled);
            SimpleAssert.eq(0, scope.items.size(), "previously canceled drops are not fabricated as refunds");
            Throwable[] error = { null };
            Thread thread = new Thread(() -> {
                try {
                    SimpleAssert.that(
                        !MinecraftForge.EVENT_BUS.post(joinEvent(outside, world)),
                        "another thread is never captured");
                } catch (Throwable failure) {
                    error[0] = failure;
                }
            });
            thread.start();
            thread.join();
            if (error[0] != null) throw new AssertionError(error[0]);
            EntityItem forced = new EntityItem(world, 0, 64, 0, new ItemStack(Items.emerald, 7));
            forced.forceSpawn = true;
            SimpleAssert.that(
                MinecraftForge.EVENT_BUS.post(joinEvent(forced, world)),
                "actual scoped native drops are captured");
            SimpleAssert.eq(7, scope.items.get(0).stackSize, "captured actual count remains intact");
            SimpleAssert.that(
                forced.isDead && forced.getEntityItem().stackSize == 0,
                "forceSpawn cannot keep a second live item");
        }
        SimpleAssert.that(
            !MinecraftForge.EVENT_BUS.post(joinEvent(outside, world)),
            "capture always ends with the native callback scope");
        try (HologramDemolition.DropScope source = new HologramDemolition.DropScope(world, 0, 64, 0, false)) {
            EntityItem forced = new EntityItem(world, .5, 64.5, .5, new ItemStack(Items.emerald));
            forced.forceSpawn = true;
            EntityJoinWorldEvent canceled = joinEvent(forced, world);
            canceled.setCanceled(true);
            MinecraftForge.EVENT_BUS.post(canceled);
            SimpleAssert.that(
                forced.isDead && forced.getEntityItem().stackSize == 0,
                "previously canceled forceSpawn ore loot cannot bypass source suppression");
            SimpleAssert.that(source.items.isEmpty(), "suppressed ore loot never becomes a recovery receipt");
        }
    }

    private static void protection(Scene world, Player player, Guard guard) throws IOException {
        world.block = Blocks.bedrock;
        HologramDemolition.Removal result = HologramDemolition.removeBlock(player, world, 0, 64, 0);
        SimpleAssert.that(
            !result.removed && world.block == Blocks.bedrock,
            "hardness -1 survives even direct server invocation");
        world.block = Blocks.stone;
        world.mine = false;
        SimpleAssert.that(
            !HologramDemolition.removeBlock(player, world, 0, 64, 0).removed,
            "spawn/world mining protection is honored");
        world.mine = true;
        player.edit = false;
        SimpleAssert.that(
            !HologramDemolition.removeBlock(player, world, 0, 64, 0).removed,
            "player edit permission is honored");
        player.edit = true;
        guard.cancel = true;
        result = HologramDemolition.removeBlock(player, world, 0, 64, 0);
        SimpleAssert.that(
            !result.removed && result.drops.isEmpty() && world.block == Blocks.stone,
            "Forge cancellation retains original block and creates no drops");
        guard.cancel = false;
    }

    private static void chest(Scene world, Player player) throws IOException {
        world.block = Blocks.chest;
        world.metadata = 2;
        TileEntityChest chest = new TileEntityChest();
        world.tile = chest;
        ItemStack cargo = new ItemStack(Items.diamond, 57);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("native-cargo", "箱内完整 NBT");
        cargo.setTagCompound(tag);
        chest.setInventorySlotContents(3, cargo);
        chest.setWorldObj(world);
        HologramDemolition.Removal result = HologramDemolition.removeBlock(player, world, 0, 64, 0);
        SimpleAssert.that(
            result.removed && result.failure == null && world.block == Blocks.air && world.tile == null,
            "real vanilla chest is demolished through native lifecycle");
        int diamonds = 0, shells = 0;
        for (ItemStack drop : result.drops) {
            if (drop.getItem() == Items.diamond) {
                diamonds += drop.stackSize;
                SimpleAssert.eq(tag, drop.getTagCompound(), "native chest split stacks retain exact cargo NBT");
            } else
                if (drop.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.chest)) shells += drop.stackSize;
                else throw new AssertionError("unexpected fabricated chest drop");
        }
        SimpleAssert.eq(57, diamonds, "all native chest contents captured exactly once");
        SimpleAssert.eq(1, shells, "native chest shell captured exactly once");
        SimpleAssert.eq(0, world.liveItems, "captured chest contents do not remain at source coordinates");
    }

    private static void nativeFailure(Scene world, Player player) throws IOException {
        world.block = new FailingBlock();
        world.metadata = 0;
        world.tile = new TileEntityChest();
        HologramDemolition.Removal result = HologramDemolition.removeBlock(player, world, 0, 64, 0);
        SimpleAssert.that(
            result.removed && result.failure != null && world.block == Blocks.air,
            "unknown TE native failure keeps actual changed world instead of duplicating a restored source");
        SimpleAssert
            .eq(1, result.drops.size(), "drops generated before a native failure remain owned by the recovery result");
        SimpleAssert.eq(11, result.drops.get(0).stackSize, "partial native failure retains the actual generated count");
        EntityItem later = new EntityItem(world, 0, 64, 0, new ItemStack(Items.diamond));
        SimpleAssert.that(
            !MinecraftForge.EVENT_BUS.post(joinEvent(later, world)),
            "exception never leaks capture to later world activity");
    }

    private static void stargateOres(Scene world, Player player) throws Exception {
        String threadName = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        try (AutoCloseable nativeOres = HologramStargateRulesRegression.nativeOreEnvironment()) {
            OreBlock ore = (OreBlock) HologramStargateRulesRegression
                .registeredOre("gtmb:stargate_native_ore_fixture", new OreBlock());
            OreDictionary.registerOre("oreGtmbNativeFixture", new ItemStack(ore, 1, 7));
            SimpleAssert.that(HologramStargateRules.isOre(ore, 7), "ore dictionary identifies the original ore block");
            SimpleAssert
                .that(!HologramStargateRules.isOre(ore, 8), "ore metadata remains distinct from other variants");
            world.block = ore;
            world.metadata = 7;
            world.tile = null;
            world.liveItems = world.liveXp = 0;
            try (HologramStargateRulesRegression.Mode ordinary = new HologramStargateRulesRegression.Mode(false)) {
                HologramDemolition.Removal result = HologramDemolition.removeBlock(player, world, -3, 64, -2);
                SimpleAssert.that(result.removed && !result.drops.isEmpty(), "default mode retains native ore drops");
                SimpleAssert.that(world.liveXp > 0, "default mode retains native ore experience");
            }
            try (HologramStargateRulesRegression.Mode rules = new HologramStargateRulesRegression.Mode(true)) {
                for (int path = 0; path < 3; path++) {
                    world.block = ore;
                    world.metadata = 7;
                    world.tile = null;
                    world.liveItems = world.liveXp = 0;
                    ore.fail = path == 1;
                    ore.early = path == 2;
                    ore.xpCalls = 0;
                    HologramDemolition.Removal result = HologramDemolition.removeBlock(player, world, -3, 64, -2);
                    SimpleAssert.that(result.removed && world.block == Blocks.air, "rules allow native ore removal");
                    SimpleAssert.eq(path == 1, result.failure != null, "real native failure remains visible");
                    SimpleAssert
                        .that(result.drops.isEmpty(), "normal, failed and early native paths return no ore loot");
                    SimpleAssert
                        .eq(0, world.liveItems + world.liveXp, "ore drops and experience leave no live entities");
                    SimpleAssert.eq(0, ore.xpCalls, "native ore experience callback is not invoked");
                }
                ore.fail = ore.early = false;
                ore.neighbor = true;
                world.block = ore;
                world.metadata = 7;
                HologramDemolition.Removal neighbor = HologramDemolition.removeBlock(player, world, -3, 64, -2);
                SimpleAssert.eq(1, neighbor.drops.size(), "neighbor callback drops remain recoverable");
                SimpleAssert.eq(
                    Item.getItemFromBlock(ore),
                    neighbor.drops.get(0)
                        .getItem(),
                    "neighbor ore items are kept");
                SimpleAssert.eq(3, neighbor.drops.get(0).stackSize, "neighbor drops keep their complete count");
                ore.neighbor = false;

                world.block = Blocks.chest;
                world.metadata = 2;
                TileEntityChest chest = new TileEntityChest();
                world.tile = chest;
                chest.setInventorySlotContents(0, new ItemStack(ore, 13, 7));
                chest.setWorldObj(world);
                HologramDemolition.Removal device = HologramDemolition.removeBlock(player, world, 0, 64, 0);
                int cargo = 0;
                for (ItemStack drop : device.drops)
                    if (drop.getItem() == Item.getItemFromBlock(ore) && drop.getItemDamage() == 7)
                        cargo += drop.stackSize;
                SimpleAssert.eq(13, cargo, "ordinary device inventories retain all ore cargo under Stargate rules");
            }
        } finally {
            Thread.currentThread()
                .setName(threadName);
        }
    }

    private static void declaredToolLifecycle(Scene world, Player player, Guard guard) throws Exception {
        List<String> lifecycle = new ArrayList<>();
        TrackingProjector projector = new TrackingProjector(lifecycle);
        ItemStack held = new ItemStack(projector);
        NBTTagCompound configuration = new NBTTagCompound();
        configuration.setBoolean("gtitHologramAreaSelecting", true);
        held.setTagCompound(configuration);
        player.inventory.mainInventory[0] = held;
        DeclaredToolBlock block = new DeclaredToolBlock(lifecycle);
        world.block = block;
        world.metadata = 7;
        world.tile = null;
        SimpleAssert
            .eq(-1, projector.getHarvestLevel(held, "cutter"), "ordinary held projector gains no harvest power");
        SimpleAssert
            .that(projector.onBlockStartBreak(held, 0, 64, 0, player), "ordinary selection still blocks mining");
        lifecycle.clear();
        guard.toolCheck = () -> {
            lifecycle.add("event");
            SimpleAssert.eq(held, player.getHeldItem(), "BreakEvent sees the original held projector identity");
            SimpleAssert.that(
                projector.getToolClasses(held)
                    .contains("cutter"),
                "actual metadata selects its tool class");
            SimpleAssert.eq(12, projector.getHarvestLevel(held, "cutter"), "declared high harvest level is adapted");
            SimpleAssert
                .eq(-1, projector.getHarvestLevel(held.copy(), "cutter"), "a copied stack gains no scoped ability");
            Throwable[] otherThread = { null };
            Thread reader = new Thread(() -> {
                try {
                    SimpleAssert
                        .eq(-1, projector.getHarvestLevel(held, "cutter"), "other threads gain no harvest power");
                } catch (Throwable failure) {
                    otherThread[0] = failure;
                }
            });
            reader.start();
            try {
                reader.join();
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
            if (otherThread[0] != null) throw new AssertionError(otherThread[0]);
        };
        try {
            HologramDemolition.Removal result = HologramDemolition.removeBlock(player, world, 0, 64, 0);
            SimpleAssert.that(result.removed && result.failure == null, "declared tool follows native removal");
            List<String> expected = new ArrayList<>();
            Collections.addAll(
                expected,
                "event",
                "item-start",
                "can-harvest",
                "item-destroyed",
                "harvested",
                "removed",
                "destroyed",
                "harvest");
            SimpleAssert.eq(expected, lifecycle, "Forge block and item hooks run in native order");
            SimpleAssert
                .eq(-1, projector.getHarvestLevel(held, "cutter"), "normal mining remains unchanged after scope");
            SimpleAssert.eq(configuration, held.getTagCompound(), "tool scope does not write projector configuration");

            lifecycle.clear();
            block.refuse = true;
            world.block = block;
            world.metadata = 7;
            result = HologramDemolition.removeBlock(player, world, 0, 64, 0);
            SimpleAssert.that(!result.removed && world.block == block, "custom harvest rejection preserves the block");
            SimpleAssert.that(!lifecycle.contains("removed"), "a refused native harvest never removes without drops");
            block.refuse = false;
            projector.fail = true;
            result = HologramDemolition.removeBlock(player, world, 0, 64, 0);
            SimpleAssert.that(
                !result.removed && result.failure != null && result.drops.size() == 1,
                "tool callback failure keeps real emitted drops and the actual unchanged source");
            SimpleAssert.eq(-1, projector.getHarvestLevel(held, "cutter"), "tool failure always closes harvest scope");
        } finally {
            guard.toolCheck = null;
            player.inventory.mainInventory[0] = new ItemStack(Items.diamond_pickaxe);
        }
    }

    /** Real GT .265 block/TE/hatch methods; only registry setup, MTE allocation and world storage are fixtures. */
    @SuppressWarnings("unchecked")
    private static void nativeGtHatches(sun.misc.Unsafe unsafe, Scene world, Player player) throws Exception {
        String name = "gtmb:regression_native_demolition_machine";
        BlockMachines machines = Block.blockRegistry.containsKey(name)
            ? (BlockMachines) Block.blockRegistry.getObject(name)
            : null;
        if (machines == null) {
            machines = (BlockMachines) unsafe.allocateInstance(BlockMachines.class);
            Block vanillaDefaults = new VanillaBlockDefaults();
            // Copy every real vanilla callback ThreadLocal initializer skipped by Unsafe, including silk checks,
            // harvesters and Forge drop capture. The fixture does not replace any native GT/block callback.
            for (Field initialized : Block.class.getDeclaredFields())
                if (!java.lang.reflect.Modifier.isStatic(initialized.getModifiers())
                    && ThreadLocal.class.isAssignableFrom(initialized.getType())) {
                        initialized.setAccessible(true);
                        initialized.set(machines, initialized.get(vanillaDefaults));
                    }
            Field delegate = Block.class.getDeclaredField("delegate");
            delegate.setAccessible(true);
            delegate.set(
                machines,
                ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Block.blockRegistry)
                    .getDelegate(machines, Block.class));
            Field material = Block.class.getDeclaredField("blockMaterial");
            material.setAccessible(true);
            material.set(machines, Material.iron);
            machines.setHardness(1);
            int blockId = 4094;
            while (Block.blockRegistry.containsId(blockId) || Item.itemRegistry.containsId(blockId)) blockId--;
            Block.blockRegistry.addObject(blockId, name, machines);
            Item.itemRegistry.addObject(blockId, name, new ItemMachines(machines));
        }
        Field temporaryField = BlockMachines.class.getDeclaredField("mTemporaryTileEntity");
        temporaryField.setAccessible(true);
        ThreadLocal<Object> temporary = (ThreadLocal<Object>) temporaryField.get(null);
        Object previousTemporary = temporary.get();
        Block previousMachines = GregTechAPI.sBlockMachines;
        ItemStack previousHeld = player.getHeldItem();
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        GregTechAPI.sBlockMachines = machines;
        RunnableMachineUpdate.setCurrentThreadEnabled(false);
        player.inventory.mainInventory[0] = new ItemStack(new ItemNekoHologramProjector());
        try {
            BaseMetaTileEntity first = nativeHatch(unsafe, world, 0, null);
            world.block = machines;
            world.metadata = 0;
            world.tile = first;
            HologramDemolition.Removal firstResult = HologramDemolition.removeBlock(player, world, 0, 64, 0);
            ItemStack empty = nativeMachineDrop(firstResult);
            BaseMetaTileEntity second = nativeHatch(unsafe, world, 193, null);
            world.block = machines;
            world.metadata = 0;
            world.tile = second;
            HologramDemolition.Removal secondResult = HologramDemolition.removeBlock(player, world, 193, 64, -21);
            ItemStack elsewhere = nativeMachineDrop(secondResult);
            SimpleAssert.that(
                ItemStack.areItemStackTagsEqual(empty, elsewhere),
                "two real empty GT input buses at different coordinates have equal native item NBT");
            ItemStack[] recovered = new ItemStack[3];
            recovered[1] = empty.copy();
            ItemStack additional = elsewhere.copy();
            HologramMaterials.insertRecovery(recovered, 0, 64, additional);
            SimpleAssert.eq(
                2,
                recovered[1].stackSize,
                "real native GT input bus drops stack through ordinary inventory insertion");
            SimpleAssert.eq(0, additional.stackSize, "the second native bus needs no extra slot");

            ItemStack cargo = new ItemStack(Items.diamond, 57);
            NBTTagCompound cargoNbt = new NBTTagCompound();
            cargoNbt.setString("native-cargo", "原生 GT 仓室内容物完整 NBT");
            cargo.setTagCompound(cargoNbt);
            BaseMetaTileEntity loaded = nativeHatch(unsafe, world, -51, cargo);
            world.block = machines;
            world.metadata = 0;
            world.tile = loaded;
            HologramDemolition.Removal loadedResult = HologramDemolition.removeBlock(player, world, -51, 64, -21);
            ItemStack loadedShell = nativeMachineDrop(loadedResult);
            int diamonds = 0;
            for (ItemStack drop : loadedResult.drops) if (drop.getItem() == Items.diamond) {
                diamonds += drop.stackSize;
                SimpleAssert
                    .eq(cargoNbt, drop.getTagCompound(), "real GT native inventory drops preserve exact item NBT");
            }
            SimpleAssert.eq(57, diamonds, "real GT block break drops every cargo item exactly once");
            SimpleAssert.eq(2, loadedResult.drops.size(), "real GT cargo is independent of its single machine shell");
            SimpleAssert.that(
                loaded.getStackInSlot(0) == null && world.tile == null,
                "real GT breakBlock clears the delivered inventory and removes its tile");
            SimpleAssert.that(
                ItemStack.areItemStackTagsEqual(empty, loadedShell),
                "ordinary GT bus contents are native independent drops rather than sealed full-TE payload");

            BaseMetaTileEntity configured = nativeHatch(unsafe, world, 71, null);
            Field color = CommonBaseMetaTileEntity.class.getDeclaredField("mColor");
            color.setAccessible(true);
            color.setByte(configured, (byte) 5);
            world.block = machines;
            world.metadata = 0;
            world.tile = configured;
            ItemStack colored = nativeMachineDrop(HologramDemolition.removeBlock(player, world, 71, 64, -21));
            SimpleAssert.eq(
                (byte) 5,
                colored.getTagCompound()
                    .getByte("mColor"),
                "GT native retained configuration survives");
            SimpleAssert.that(
                !ItemStack.areItemStackTagsEqual(empty, colored),
                "different legitimate native item NBT never force-merges");
        } finally {
            player.inventory.mainInventory[0] = previousHeld;
            GregTechAPI.sBlockMachines = previousMachines;
            RunnableMachineUpdate.setCurrentThreadEnabled(updates);
            if (previousTemporary == null) temporary.remove();
            else temporary.set(previousTemporary);
        }
    }

    private static BaseMetaTileEntity nativeHatch(sun.misc.Unsafe unsafe, Scene world, int x, ItemStack cargo)
        throws Exception {
        BaseMetaTileEntity tile = new BaseMetaTileEntity();
        MTEHatchInputBus hatch = (MTEHatchInputBus) unsafe.allocateInstance(MTEHatchInputBus.class);
        Field inventory = CommonMetaTileEntity.class.getDeclaredField("mInventory");
        inventory.setAccessible(true);
        inventory.set(hatch, new ItemStack[] { cargo, null });
        hatch.setBaseMetaTileEntity(tile);
        tile.setMetaTileID((short) 28);
        tile.setWorldObj(world);
        tile.xCoord = x;
        tile.yCoord = 64;
        tile.zCoord = -21;
        return tile;
    }

    private static ItemStack nativeMachineDrop(HologramDemolition.Removal result) {
        if (result.failure != null) throw new AssertionError("real native GT removal failed", result.failure);
        SimpleAssert.that(result.removed, "real native GT harvest removes its source");
        ItemStack machine = null;
        for (ItemStack drop : result.drops) if (drop.getItem() instanceof ItemMachines) {
            SimpleAssert.that(machine == null && drop.stackSize == 1, "exactly one native GT machine item is emitted");
            machine = drop;
            SimpleAssert
                .that(!HologramRecovery.hasSeal(drop), "new demolition drops never receive GTMB sealed TE data");
            SimpleAssert.eq(28, drop.getItemDamage(), "native machine item keeps the actual hatch ID");
        }
        SimpleAssert.that(machine != null, "actual GT setItemNBT/getDrops path produces its native machine item");
        return machine;
    }

    /**
     * Raw JVM lacks FML's event transformer/default constructors. Only listener logic and the bus's boolean
     * cancellation contract are exercised here; a native Forge run must verify actual listener registration.
     */
    private static final class Guard extends EventBus {

        boolean cancel;
        Runnable toolCheck;
        final EventBus original;

        Guard(EventBus original) {
            this.original = original;
        }

        @Override
        public boolean post(Event event) {
            if (event instanceof EntityJoinWorldEvent) {
                HologramDemolition.DROPS.spawned((EntityJoinWorldEvent) event);
                return event.isCanceled();
            }
            if (event instanceof BlockEvent.HarvestDropsEvent)
                HologramDemolition.DROPS.harvested((BlockEvent.HarvestDropsEvent) event);
            if (event instanceof BlockEvent.BreakEvent && toolCheck != null) toolCheck.run();
            return event instanceof BlockEvent.BreakEvent && cancel || original.post(event);
        }
    }

    private static final class TrackingProjector extends ItemNekoHologramProjector {

        final List<String> lifecycle;
        boolean fail;

        TrackingProjector(List<String> lifecycle) {
            this.lifecycle = lifecycle;
        }

        @Override
        public boolean onBlockStartBreak(ItemStack stack, int x, int y, int z, EntityPlayer player) {
            lifecycle.add("item-start");
            return super.onBlockStartBreak(stack, x, y, z, player);
        }

        @Override
        public boolean onBlockDestroyed(ItemStack stack, World world, Block block, int x, int y, int z,
            EntityLivingBase player) {
            lifecycle.add("item-destroyed");
            if (fail) {
                world.spawnEntityInWorld(new EntityItem(world, x, y, z, new ItemStack(Items.diamond)));
                throw new IllegalStateException("native tool callback failure after a real drop");
            }
            return false;
        }
    }

    private static final class VanillaBlockDefaults extends Block {

        VanillaBlockDefaults() {
            super(Material.iron);
        }
    }

    private static final class DeclaredToolBlock extends Block {

        final List<String> lifecycle;
        boolean refuse;

        DeclaredToolBlock(List<String> lifecycle) {
            super(Material.iron);
            this.lifecycle = lifecycle;
        }

        @Override
        public String getHarvestTool(int metadata) {
            return metadata == 7 ? "cutter" : "wrench";
        }

        @Override
        public int getHarvestLevel(int metadata) {
            return 12;
        }

        @Override
        public boolean canHarvestBlock(EntityPlayer player, int metadata) {
            lifecycle.add("can-harvest");
            return !refuse && super.canHarvestBlock(player, metadata);
        }

        @Override
        public void onBlockHarvested(World world, int x, int y, int z, int metadata, EntityPlayer player) {
            lifecycle.add("harvested");
        }

        @Override
        public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean harvest) {
            lifecycle.add("removed");
            SimpleAssert.that(harvest, "native delayed removal receives the positive harvest decision");
            return true;
        }

        @Override
        public void onBlockDestroyedByPlayer(World world, int x, int y, int z, int metadata) {
            lifecycle.add("destroyed");
        }

        @Override
        public void harvestBlock(World world, EntityPlayer player, int x, int y, int z, int metadata) {
            lifecycle.add("harvest");
            world.setBlockToAir(x, y, z);
        }
    }

    private static EntityJoinWorldEvent joinEvent(Entity entity, World world) {
        return new EntityJoinWorldEvent(entity, world) {

            @Override
            public boolean isCancelable() {
                return true;
            }
        };
    }

    private static final class FailingBlock extends Block {

        FailingBlock() {
            super(Material.wood);
        }

        @Override
        public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
            world.spawnEntityInWorld(new EntityItem(world, x, y, z, new ItemStack(Items.emerald, 11)));
            throw new IllegalStateException("native failure after real contents were emitted");
        }
    }

    private static final class OreBlock extends Block {

        boolean fail, early, neighbor;
        int xpCalls;

        OreBlock() {
            super(Material.rock);
        }

        @Override
        public boolean canHarvestBlock(EntityPlayer player, int metadata) {
            return true;
        }

        @Override
        public void onBlockHarvested(World world, int x, int y, int z, int metadata, EntityPlayer player) {
            if (early) world.setBlockToAir(x, y, z);
        }

        @Override
        public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
            EntityItem item = new EntityItem(world, x + .5, y + .5, z + .5, new ItemStack(Items.emerald, 11));
            item.forceSpawn = true;
            world.spawnEntityInWorld(item);
            world.spawnEntityInWorld(new EntityXPOrb(world, x + .5, y + .5, z + .5, 7));
            if (neighbor)
                world.spawnEntityInWorld(new EntityItem(world, x + 1.5, y + .5, z + .5, new ItemStack(this, 3, 7)));
            if (fail) throw new IllegalStateException("native ore failure after loot");
        }

        @Override
        public void dropXpOnBlockBreak(World world, int x, int y, int z, int experience) {
            xpCalls++;
            super.dropXpOnBlockBreak(world, x, y, z, experience);
        }
    }

    private static final class Player extends EntityPlayerMP {

        boolean edit;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public boolean canPlayerEdit(int x, int y, int z, int side, ItemStack stack) {
            return edit;
        }

        @Override
        public void addStat(StatBase stat, int count) {}

        @Override
        public void addExhaustion(float amount) {}

        @Override
        public boolean isSneaking() {
            return false;
        }
    }

    private static final class Scene extends WorldServer {

        Block block;
        int metadata, liveItems, liveXp;
        TileEntity tile;
        boolean mine;
        GameRules rules;

        private Scene() {
            super(null, null, "demolition fixture", 0, null, null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return block;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return metadata;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tile;
        }

        @Override
        public Chunk getChunkFromBlockCoords(int x, int z) {
            return null;
        }

        @Override
        public void removeTileEntity(int x, int y, int z) {
            tile = null;
        }

        @Override
        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
            return mine;
        }

        @Override
        public GameRules getGameRules() {
            return rules;
        }

        @Override
        public void func_147453_f(int x, int y, int z, Block block) {}

        @Override
        public boolean setBlock(int x, int y, int z, Block next, int meta, int flags) {
            Block old = block;
            int previous = metadata;
            block = next;
            metadata = meta;
            old.breakBlock(this, x, y, z, old, previous);
            tile = null;
            return true;
        }

        @Override
        public boolean spawnEntityInWorld(Entity entity) {
            if (MinecraftForge.EVENT_BUS.post(joinEvent(entity, this)) && !entity.forceSpawn) return false;
            if (entity instanceof EntityItem && !entity.isDead && ((EntityItem) entity).getEntityItem().stackSize > 0)
                liveItems++;
            if (entity instanceof EntityXPOrb && !entity.isDead) liveXp++;
            return true;
        }
    }
}
