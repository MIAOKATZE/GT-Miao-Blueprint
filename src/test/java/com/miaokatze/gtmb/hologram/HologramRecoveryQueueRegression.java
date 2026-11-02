package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/** Persisted native NBT ledgers, bounded reads/copies and actual demolition scheduler backpressure. */
public final class HologramRecoveryQueueRegression {

    private static final String QUEUE = "gtmbHologramMaterialRecovery";
    private static final String CURSOR = "gtmbRecoveryCursor";
    private static final String PENDING = "gtmbRecoveryPendingDrops";
    private static final String BYTES = "gtmbRecoveryBytes";
    private static final String SCAN = "gtmbRecoveryIndexCursor";
    private static final String DROP = "gtmbRecoveryDropAtPlayer";

    private HologramRecoveryQueueRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramRecoveryQueueRegression.class, args)) return;
        run();
        System.out.println("HologramRecoveryQueueRegression PASS");
    }

    public static void run() {
        try {
            fairnessAndReconnect();
            legacyIndexReconnect();
            boundedBacklog(10000);
            boundedBacklog(100000);
            exactMerging();
            exceptionalRemainder();
            demolitionBackpressure();
        } catch (Exception failure) {
            throw new AssertionError("Recovery queue regression", failure);
        }
    }

    private static void fairnessAndReconnect() throws Exception {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        fixture.world.acceptedSpawns = 0;
        for (int i = 0; i < 96; i++) enqueue(fixture, unique(fixture, i));
        ItemStack deliverable = new ItemStack(Items.stick);
        fixture.player.inventory.mainInventory[1].stackSize = 63;
        enqueue(fixture, deliverable);
        for (int tick = 0; tick < 128 && fixture.player.inventory.mainInventory[1].stackSize == 63; tick++)
            fixture.context.flushRecovery();
        SimpleAssert.eq(64, fixture.player.inventory.mainInventory[1].stackSize, "the 97th receipt passes 96 refusals");
        SimpleAssert.eq(96L, fixture.pending(), "only the acknowledged tail quantity leaves the ledger");
        SimpleAssert.eq(96, owner(fixture).getInteger(PENDING), "pending summary follows swap removal exactly");

        // The successful tail removal left exactly 96 rows: a full attempt budget then correctly wraps to zero.
        // Use 97 refused rows for the reconnect phase so real progress cannot coincide with that full cycle.
        enqueue(fixture, unique(fixture, 97));
        for (int tick = 0; tick < 128 && owner(fixture).getInteger(CURSOR) == 0; tick++)
            fixture.context.flushRecovery();
        int savedCursor = owner(fixture).getInteger(CURSOR);
        SimpleAssert
            .that(savedCursor > 0, "97 refused rows leave a persisted partial cycle within the 96-attempt budget");
        byte[] saved = CompressedStreamTools.compress(owner(fixture));
        NBTTagCompound reloaded = CompressedStreamTools.func_152457_a(saved, NBTSizeTracker.field_152451_a);
        fixture.player.getEntityData()
            .setTag(EntityPlayer.PERSISTED_NBT_TAG, reloaded);
        CountingList queue = observe(reloaded.getTagList(QUEUE, 10));
        reloaded.setTag(QUEUE, queue);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        HologramMaterials.Context reconnected = new HologramMaterials.Context(fixture.player, settings, 0);
        queue.reset();
        reconnected.flushRecovery();
        SimpleAssert.eq(savedCursor, queue.firstRead, "a new login context resumes the native persisted cursor");
        SimpleAssert.eq(97L, fixture.pending(), "reconnection cannot replay or lose refused receipts");
        queue.reset();
        for (int call = 0; call < 100; call++) SimpleAssert.that(reconnected.pendingWorldRecovery(), "pending summary");
        SimpleAssert.eq(0, queue.reads, "completion checks do not rescan even a refused queue");
    }

    private static void boundedBacklog(int count) throws Exception {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        fixture.world.acceptedSpawns = 0;
        NBTTagCompound template = HologramMaterials.itemReceipt(unique(fixture, 1));
        template.setBoolean(DROP, true);
        template.getCompoundTag("tag")
            .setByteArray("payload", new byte[2048]);
        CountingList queue = new CountingList();
        Counters counters = new Counters();
        for (int i = 0; i < count; i++) queue.appendTag(new CountingReceipt(template, counters));
        NBTTagCompound owner = owner(fixture);
        owner.setTag(QUEUE, queue);
        queue.reset();
        SimpleAssert
            .that(fixture.context.pendingWorldRecovery(), "unindexed legacy drops remain conservatively pending");
        SimpleAssert.that(fixture.context.recoveryBacklogged(), "legacy indexing applies backpressure before removal");
        SimpleAssert.eq(0, queue.reads, "legacy pending and pressure checks do not visit the tail");
        fixture.context.flushRecovery();
        int indexed = owner.getInteger(SCAN);
        SimpleAssert.that(indexed > 0 && indexed <= HologramMaterials.RECOVERY_VISITS, "legacy indexing is bounded");
        SimpleAssert.eq(indexed, queue.reads, "only indexed legacy rows are read");
        SimpleAssert.eq(0, counters.copies, "a large legacy queue never deep-copies its tail");
        SimpleAssert.eq(count, queue.tagCount(), "indexing retains every legacy receipt");

        // Model the same ledger after incremental indexing, without spending 100k rows in one regression tick.
        for (int i = 0; i < count; i++) queue.getCompoundTagAt(i)
            .setLong(BYTES, 4096L);
        owner.removeTag(SCAN);
        owner.setLong(BYTES, 4096L * count);
        owner.setInteger(PENDING, count);
        owner.setInteger(CURSOR, count / 2);
        counters.copies = 0;
        queue.reset();
        fixture.context.flushRecovery();
        SimpleAssert.that(
            queue.reads > 0 && queue.reads <= HologramMaterials.RECOVERY_VISITS,
            "indexed " + count + "-row refusals touch only the bounded attempt window");
        SimpleAssert.eq(0, counters.copies, "indexed refusals never copy untouched receipts");
        SimpleAssert.eq(count, queue.tagCount(), "rejected native spawns preserve all queued rows");
        queue.reset();
        fixture.context.pendingWorldRecovery();
        fixture.context.recoveryBacklogged();
        SimpleAssert.eq(0, queue.reads, "indexed status remains constant work for " + count + " rows");
    }

    private static void legacyIndexReconnect() throws Exception {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        fixture.world.acceptedSpawns = 0;
        NBTTagList legacy = new NBTTagList();
        for (int i = 0; i < 97; i++) {
            NBTTagCompound receipt = HologramMaterials.itemReceipt(unique(fixture, i));
            receipt.setBoolean(DROP, true);
            legacy.appendTag(receipt);
        }
        owner(fixture).setTag(QUEUE, legacy);
        fixture.context.flushRecovery();
        SimpleAssert.that(owner(fixture).hasKey(SCAN, 3), "legacy indexing yields before inspecting all 97 rows");
        int indexed = owner(fixture).getInteger(SCAN);
        NBTTagCompound restored = CompressedStreamTools
            .func_152457_a(CompressedStreamTools.compress(owner(fixture)), NBTSizeTracker.field_152451_a);
        fixture.player.getEntityData()
            .setTag(EntityPlayer.PERSISTED_NBT_TAG, restored);
        SimpleAssert.eq(indexed, restored.getInteger(SCAN), "partially indexed legacy progress survives native NBT");
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        HologramMaterials.Context reconnected = new HologramMaterials.Context(fixture.player, settings, 0);
        for (int tick = 0; tick < 128 && restored.hasKey(SCAN, 3); tick++) reconnected.flushRecovery();
        SimpleAssert.that(!restored.hasKey(SCAN, 3), "a new context finishes the remaining legacy index");
        SimpleAssert.eq(97, restored.getInteger(PENDING), "legacy summary counts each refused receipt once");
        SimpleAssert.eq(97L, fixture.pending(), "incremental legacy indexing preserves every real item");
        fixture.world.acceptedSpawns = Integer.MAX_VALUE;
        for (int tick = 0; tick < 128 && reconnected.pendingWorldRecovery(); tick++) reconnected.flushRecovery();
        SimpleAssert.eq(97, fixture.dropped(), "all old receipts are delivered once after destinations reopen");
        SimpleAssert.eq(0L, fixture.pending(), "completed old recovery leaves no replayable quantity");
        SimpleAssert.eq(0L, restored.getLong(BYTES), "legacy byte pressure clears after exact delivery");
    }

    private static void exactMerging() throws Exception {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        ItemStack committed = fixture.stack(130);
        enqueue(fixture, committed);
        enqueue(fixture, committed);
        NBTTagList queue = owner(fixture).getTagList(QUEUE, 10);
        SimpleAssert.eq(1, queue.tagCount(), "exact adjacent committed receipts coalesce");
        SimpleAssert.eq(260L, fixture.pending(), "coalescing preserves full integer quantities");
        SimpleAssert.eq(130, committed.stackSize, "committed source drops remain read-only");
        SimpleAssert.eq(1, owner(fixture).getInteger(PENDING), "coalescing retains a single pending row");
        ItemStack distinct = committed.copy();
        distinct.getTagCompound()
            .setString("fixture", "different configuration");
        enqueue(fixture, distinct);
        fixture.context.recoverWorld(Collections.singletonList(committed));
        SimpleAssert.eq(3, queue.tagCount(), "different item NBT and ordinary delivery policy are never merged");

        HologramRecoveryDeliveryRegression.Fixture overflow = new HologramRecoveryDeliveryRegression.Fixture();
        enqueue(overflow, overflow.stack(Integer.MAX_VALUE));
        enqueue(overflow, overflow.stack(1));
        SimpleAssert.eq(
            2,
            owner(overflow).getTagList(QUEUE, 10)
                .tagCount(),
            "integer overflow keeps separate receipts");
        SimpleAssert.eq(2147483648L, overflow.pending(), "coalescing never truncates a real large quantity");
    }

    private static void exceptionalRemainder() throws Exception {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        fixture.player.inventory.mainInventory[1] = fixture.stack(60);
        enqueue(fixture, fixture.stack(130));
        fixture.world.failAfterSpawns = 1;
        boolean failed = false;
        for (int tick = 0; tick < 8 && !failed; tick++) try {
            fixture.context.flushRecovery();
        } catch (IllegalStateException expected) {
            failed = true;
        }
        SimpleAssert.that(failed, "native spawn failure remains visible");
        SimpleAssert.eq(62L, fixture.pending(), "the failed callback retains only the known remainder");
        SimpleAssert.eq(64, fixture.dropped(), "the first acknowledged native spawn is not refunded again");
        SimpleAssert
            .eq(64, fixture.player.inventory.mainInventory[1].stackSize, "inventory delivery precedes spawn failure");
        fixture.world.failAfterSpawns = Integer.MAX_VALUE;
        fixture.flushFully();
        SimpleAssert.eq(126, fixture.dropped(), "retry delivers only the 62 real remaining items");
        SimpleAssert.eq(0, owner(fixture).getInteger(PENDING), "completion summary retires the last receipt");
        SimpleAssert.eq(0L, owner(fixture).getLong(BYTES), "empty queues release their byte backpressure");
    }

    private static void demolitionBackpressure() throws Exception {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture(
            EmptyWorld.class);
        fixture.world.acceptedSpawns = 0;
        for (int i = 0; i < HologramMaterials.RECOVERY_BACKLOG_ENTRIES; i++) enqueue(fixture, unique(fixture, i));
        SimpleAssert.that(fixture.context.recoveryBacklogged(), "entry limit pauses additional removal");
        for (Field field : Entity.class.getDeclaredFields()) if (field.getType() == UUID.class) {
            field.setAccessible(true);
            field.set(fixture.player, UUID.randomUUID());
        }
        fixture.player.capabilities = new PlayerCapabilities();
        fixture.player.capabilities.allowEdit = true;
        NetworkManager connection = new NetworkManager(false);
        for (Field field : NetworkManager.class.getDeclaredFields()) if (field.getType() == Channel.class) {
            field.setAccessible(true);
            field.set(connection, new EmbeddedChannel(new ChannelInboundHandlerAdapter()));
        }
        new NetHandlerPlayServer(null, connection, fixture.player);
        EmptyWorld world = (EmptyWorld) fixture.world;
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        world.chunks = (EmptyProvider) unsafe.allocateInstance(EmptyProvider.class);
        HologramAreaSelection selection = new HologramAreaSelection();
        selection.point(1, 0, 64, 0);
        selection.point(2, 0, 65, 0);
        HologramDemolition.Cursor cursor = new HologramDemolition.Cursor(selection.resolvedRegions(), 256);
        Class<?> jobType = Class.forName(HologramDemolition.class.getName() + "$Job");
        Object job = unsafe.allocateInstance(jobType);
        set(job, "player", fixture.player);
        set(job, "world", world);
        set(job, "tool", fixture.player.getHeldItem());
        set(job, "cursor", cursor);
        set(job, "materials", fixture.context);
        Method advance = HologramDemolition.class.getDeclaredMethod("advance", jobType, long.class);
        advance.setAccessible(true);
        try {
            advance.invoke(null, job, System.nanoTime() + 50_000_000L);
            SimpleAssert
                .eq(0, world.geometryReads, "backpressure stops before even querying the next geometry section");
            SimpleAssert.that(!cursor.done(), "backpressure cannot consume the selected geometry");
            NBTTagCompound receipt = owner(fixture).getTagList(QUEUE, 10)
                .getCompoundTagAt(owner(fixture).getInteger(CURSOR));
            ItemStack space = HologramMaterials.recoveryItem(receipt);
            space.stackSize = 63;
            fixture.player.inventory.mainInventory[1] = space;
            for (int tick = 0; tick < 8 && fixture.context.recoveryBacklogged(); tick++)
                fixture.context.flushRecovery();
            SimpleAssert
                .that(!fixture.context.recoveryBacklogged(), "acknowledged delivery releases entry backpressure");
            advance.invoke(null, job, System.nanoTime() + 50_000_000L);
            SimpleAssert.that(world.geometryReads > 0, "the same admitted job resumes geometry after pressure clears");
        } finally {
            HologramDemolition.clear();
            connection.channel()
                .close();
        }

        HologramRecoveryDeliveryRegression.Fixture bytes = new HologramRecoveryDeliveryRegression.Fixture();
        ItemStack large = unique(bytes, 1);
        large.getTagCompound()
            .setByteArray("payload", new byte[(int) HologramMaterials.RECOVERY_BACKLOG_BYTES]);
        enqueue(bytes, large);
        SimpleAssert
            .that(bytes.context.recoveryBacklogged(), "one large committed item also applies byte backpressure");
        bytes.flushFully();
        SimpleAssert
            .that(!bytes.context.recoveryBacklogged(), "delivery releases byte backpressure without dropping its data");
        SimpleAssert.eq(
            (int) HologramMaterials.RECOVERY_BACKLOG_BYTES,
            bytes.world.drops.get(0)
                .getEntityItem()
                .getTagCompound()
                .getByteArray("payload").length,
            "large committed item NBT reaches the destination intact");
    }

    private static NBTTagCompound owner(HologramRecoveryDeliveryRegression.Fixture fixture) {
        NBTTagCompound data = fixture.player.getEntityData();
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG, 10))
            data.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        return data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }

    private static ItemStack unique(HologramRecoveryDeliveryRegression.Fixture fixture, int identity) {
        ItemStack stack = fixture.stack(1);
        stack.getTagCompound()
            .setInteger("identity", identity);
        return stack;
    }

    private static void enqueue(HologramRecoveryDeliveryRegression.Fixture fixture, ItemStack stack) {
        fixture.context.recoverWorldAtPlayer(Collections.singletonList(stack));
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static CountingList observe(NBTTagList source) {
        CountingList result = new CountingList();
        for (int i = 0; i < source.tagCount(); i++) result.appendTag(source.getCompoundTagAt(i));
        return result;
    }

    private static final class Counters {

        int copies;
    }

    private static final class CountingReceipt extends NBTTagCompound {

        final Counters counters;

        CountingReceipt(NBTTagCompound source, Counters counters) {
            this.counters = counters;
            for (Object key : source.func_150296_c()) setTag((String) key, source.getTag((String) key));
        }

        @Override
        public NBTBase copy() {
            counters.copies++;
            return super.copy();
        }
    }

    private static final class CountingList extends NBTTagList {

        int reads, firstRead = -1;

        @Override
        public NBTTagCompound getCompoundTagAt(int index) {
            if (firstRead < 0) firstRead = index;
            reads++;
            return super.getCompoundTagAt(index);
        }

        void reset() {
            reads = 0;
            firstRead = -1;
        }
    }

    private static final class EmptyWorld extends HologramRecoveryDeliveryRegression.RecoveryWorld {

        EmptyProvider chunks;
        int geometryReads;

        @Override
        public IChunkProvider getChunkProvider() {
            geometryReads++;
            return chunks;
        }

        @Override
        public Chunk getChunkFromChunkCoords(int x, int z) {
            return new Chunk(this, x, z);
        }
    }

    private static final class EmptyProvider extends ChunkProviderServer {

        private EmptyProvider() {
            super(null, null, null);
        }

        @Override
        public boolean chunkExists(int x, int z) {
            return true;
        }
    }
}
