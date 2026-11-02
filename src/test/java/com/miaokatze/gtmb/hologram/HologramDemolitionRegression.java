package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.stats.StatBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;

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

    /**
     * Raw JVM lacks FML's event transformer/default constructors. Only listener logic and the bus's boolean
     * cancellation contract are exercised here; a native Forge run must verify actual listener registration.
     */
    private static final class Guard extends EventBus {

        boolean cancel;
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
            return event instanceof BlockEvent.BreakEvent && cancel || original.post(event);
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
    }

    private static final class Scene extends WorldServer {

        Block block;
        int metadata, liveItems;
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
            return true;
        }
    }
}
