package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/** Real persisted receipts and spawn acknowledgements; native ME permissions are covered by opt-in acceptance. */
final class HologramRecoveryDeliveryRegression {

    private HologramRecoveryDeliveryRegression() {}

    static void run() {
        try {
            Fixture ordinary = new Fixture();
            ordinary.context.recoverWorld(Collections.singletonList(ordinary.stack(130)));
            ordinary.context.flushRecovery();
            require(
                ordinary.pending() == 130 && ordinary.world.drops.isEmpty() && !ordinary.context.pendingWorldRecovery(),
                "ordinary recovery stays queued without a demolition delivery flag");

            Fixture demolition = new Fixture();
            demolition.player.inventory.mainInventory[1] = demolition.stack(60);
            ItemStack committedDrop = demolition.stack(130);
            demolition.context.recoverWorldAtPlayer(Collections.singletonList(committedDrop));
            require(demolition.context.pendingWorldRecovery(), "committed demolition receipts remain observable");
            demolition.flushFully();
            require(committedDrop.stackSize == 130, "enqueue does not mutate a committed world-drop identity");
            require(
                demolition.player.inventory.mainInventory[1].stackSize == 64 && demolition.dropped() == 126
                    && demolition.pending() == 0
                    && !demolition.context.pendingWorldRecovery(),
                "the backpack receives four before only the exact remaining 126 are dropped");
            demolition.verifyDrops();
            demolition.context.flushRecovery();
            require(demolition.dropped() == 126, "successful delivery is not replayed");

            Fixture partial = new Fixture();
            partial.world.acceptedSpawns = 1;
            partial.context.recoverWorldAtPlayer(Collections.singletonList(partial.stack(130)));
            for (int attempt = 0; attempt < 4; attempt++) partial.context.flushRecovery();
            require(
                partial.dropped() == 64 && partial.pending() == 66,
                "a rejected spawn retains only the quantity not acknowledged by the world");
            partial.world.acceptedSpawns = Integer.MAX_VALUE;
            partial.flushFully();
            require(partial.dropped() == 130 && partial.pending() == 0, "only the stored remainder is retried");

            Fixture offline = new Fixture();
            offline.world.playerEntities.clear();
            offline.context.recoverWorldAtPlayer(Collections.singletonList(offline.stack(70)));
            offline.context.flushRecovery();
            require(
                offline.dropped() == 0 && offline.pending() == 70,
                "offline players retain real drops in the ledger");
            offline.world.playerEntities.add(offline.player);
            offline.flushFully();
            require(
                offline.dropped() == 70 && offline.pending() == 0,
                "online recovery preserves the demolition policy");

            Fixture moved = new Fixture();
            moved.context.recoverWorldAtPlayer(Collections.singletonList(moved.stack(70)));
            moved.player.worldObj = ordinary.world;
            moved.context.flushRecovery();
            require(moved.dropped() == 0 && moved.pending() == 70, "a stale context cannot spawn in another world");
            moved.player.worldObj = moved.world;
            moved.flushFully();
            require(
                moved.dropped() == 70 && moved.pending() == 0,
                "changing world does not discard committed receipts");

            Fixture callbackFailure = new Fixture();
            callbackFailure.player.inventory.mainInventory[1] = callbackFailure.stack(60);
            callbackFailure.world.failAfterSpawns = 1;
            callbackFailure.context.recoverWorldAtPlayer(Collections.singletonList(callbackFailure.stack(130)));
            boolean failed = false;
            for (int attempt = 0; attempt < 4 && !failed; attempt++) try {
                callbackFailure.context.flushRecovery();
            } catch (IllegalStateException expected) {
                failed = true;
            }
            require(
                failed && callbackFailure.dropped() == 64
                    && callbackFailure.pending() == 62
                    && callbackFailure.player.inventory.mainInventory[1].stackSize == 64,
                "a later spawn failure cannot replay earlier acknowledged inventory or world delivery");
            callbackFailure.world.failAfterSpawns = Integer.MAX_VALUE;
            callbackFailure.flushFully();
            require(
                callbackFailure.dropped() == 126 && callbackFailure.pending() == 0,
                "the next flush offers only real remainder");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Recovery delivery regression", failure);
        }
    }

    private static final class Fixture {

        final EntityPlayerMP player;
        final RecoveryWorld world;
        final HologramMaterials.Context context;

        Fixture() throws ReflectiveOperationException {
            Method factory = HologramRegressionTest.class.getDeclaredMethod("materialPlayer");
            factory.setAccessible(true);
            player = (EntityPlayerMP) factory.invoke(null);
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            world = (RecoveryWorld) unsafe.allocateInstance(RecoveryWorld.class);
            Field players = World.class.getDeclaredField("playerEntities");
            players.setAccessible(true);
            players.set(world, new ArrayList<>());
            Field provider = World.class.getDeclaredField("provider");
            provider.setAccessible(true);
            provider.set(world, new net.minecraft.world.WorldProviderSurface());
            world.drops = new ArrayList<>();
            world.acceptedSpawns = Integer.MAX_VALUE;
            world.failAfterSpawns = Integer.MAX_VALUE;
            world.playerEntities.add(player);
            player.worldObj = world;
            player.posX = 21.25;
            player.posY = 64;
            player.posZ = -37.5;
            for (int slot = 1; slot < player.inventory.mainInventory.length; slot++)
                player.inventory.mainInventory[slot] = new ItemStack(Items.stick, 64);
            HologramMaterials.Settings settings = new HologramMaterials.Settings();
            settings.me = settings.containers = false;
            settings.recoveryDestination = 1;
            context = new HologramMaterials.Context(player, settings, 0);
            // Initialize native EntityItem outside the bounded delivery loop.
            new EntityItem(world, player.posX, player.posY, player.posZ, stack(1));
        }

        ItemStack stack(int count) {
            ItemStack stack = new ItemStack(Items.apple, count, 0);
            NBTTagCompound identity = new NBTTagCompound();
            identity.setString("fixture", "exact demolition drop");
            stack.setTagCompound(identity);
            return stack;
        }

        long pending() {
            NBTTagList queue = player.getEntityData()
                .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
                .getTagList("gtmbHologramMaterialRecovery", 10);
            long amount = 0;
            for (int index = 0; index < queue.tagCount(); index++)
                amount += HologramMaterials.recoveryItem(queue.getCompoundTagAt(index)).stackSize;
            return amount;
        }

        int dropped() {
            int amount = 0;
            for (EntityItem drop : world.drops) amount += drop.getEntityItem().stackSize;
            return amount;
        }

        void flushFully() {
            for (int attempt = 0; attempt < 8 && pending() > 0; attempt++) context.flushRecovery();
        }

        void verifyDrops() {
            for (EntityItem drop : world.drops) {
                ItemStack stack = drop.getEntityItem();
                require(
                    stack.stackSize > 0 && stack.stackSize <= stack.getMaxStackSize()
                        && stack.isItemEqual(stack(1))
                        && ItemStack.areItemStackTagsEqual(stack, stack(1)),
                    "world drops preserve exact identity in legal native stacks");
                require(
                    drop.posX == player.posX && drop.posY == player.posY && drop.posZ == player.posZ,
                    "overflow appears at the player's feet");
            }
        }
    }

    private static final class RecoveryWorld extends WorldServer {

        List<EntityItem> drops;
        int acceptedSpawns;
        int failAfterSpawns;

        private RecoveryWorld() {
            super(null, null, "recovery-delivery", 0, null, null);
        }

        @Override
        public boolean spawnEntityInWorld(Entity entity) {
            require(entity instanceof EntityItem, "recovery spawns actual item entities");
            if (drops.size() >= failAfterSpawns) throw new IllegalStateException("fixture spawn callback failure");
            if (drops.size() >= acceptedSpawns) return false;
            drops.add((EntityItem) entity);
            return true;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
