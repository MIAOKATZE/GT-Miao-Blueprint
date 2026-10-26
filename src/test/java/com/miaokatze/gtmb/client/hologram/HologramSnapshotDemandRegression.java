package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** No native tiles or GL: demand retry cadence and exact report completeness at the real client boundary. */
public final class HologramSnapshotDemandRegression {

    private HologramSnapshotDemandRegression() {}

    public static void run() {
        try {
            modelBackoff();
            detailCompleteness();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void modelBackoff() throws ReflectiveOperationException {
        HologramState previous = HologramClient.state;
        Consumer<NBTTagCompound> sender = HologramSnapshotModelsClient.sender;
        List<NBTTagCompound> sent = new ArrayList<>();
        HologramSnapshotModelsClient.clear();
        try {
            NBTTagCompound data = state();
            NBTTagCompound manifest = new NBTTagCompound();
            manifest.setBoolean("available", true);
            manifest.setLong("generation", 3);
            data.setTag("modelManifest", manifest);
            NBTTagList cells = new NBTTagList();
            NBTTagCompound cell = new NBTTagCompound();
            cell.setString("id", "minecraft:stone");
            cell.setString("wantId", "minecraft:stone");
            cell.setLong("nativeModelId", 7);
            cell.setInteger("nativeModelPending", 1);
            cells.appendTag(cell);
            data.setTag("cells", cells);
            HologramState state = new HologramState(data);
            HologramClient.state = state;
            HologramSnapshotModelsClient.sender = sent::add;
            HologramSnapshotModelsClient.requestVisible(state, state.cells.get(0), true);
            Method flush = HologramSnapshotModelsClient.class.getDeclaredMethod("flush");
            flush.setAccessible(true);
            flush.invoke(null);
            check(sent.size() == 1, "visible native appearance is requested once");
            NBTTagCompound reply = (NBTTagCompound) sent.get(0)
                .copy();
            reply.setString("modelError", "busy");
            AtomicInteger closed = new AtomicInteger();
            HologramSnapshotModelsClient.receive(reply, closed::incrementAndGet, 0);
            flush.invoke(null);
            check(sent.size() == 1 && closed.get() == 1, "busy releases ownership and suppresses next-frame retry");
            Map<?, ?> demands = (Map<?, ?>) field(HologramSnapshotModelsClient.class, "queued").get(null);
            Object demand = demands.get(0);
            Field retryAt = field(demand.getClass(), "retryAt");
            check(field(demand.getClass(), "failures").getInt(demand) == 1, "first error records one retry failure");
            retryAt.setLong(demand, 0);
            flush.invoke(null);
            check(sent.size() == 2, "request resumes after its backoff window");
            reply = (NBTTagCompound) sent.get(1)
                .copy();
            reply.setString("modelError", "stale");
            HologramSnapshotModelsClient.receive(reply, closed::incrementAndGet, 0);
            check(field(demand.getClass(), "failures").getInt(demand) == 2, "repeated failures increase the backoff");
            check(
                retryAt.getLong(demand) - System.nanoTime() > 500_000_000L,
                "second failure waits longer than the base delay");
            flush.invoke(null);
            check(sent.size() == 2, "repeated errors cannot flood the server each frame");
        } finally {
            HologramSnapshotModelsClient.clear();
            HologramSnapshotModelsClient.sender = sender;
            HologramClient.state = previous;
        }
    }

    private static void detailCompleteness() throws ReflectiveOperationException {
        HologramSnapshotDetailsClient.clear();
        AtomicInteger closed = new AtomicInteger();
        NBTTagCompound data = state(), manifest = new NBTTagCompound();
        manifest.setBoolean("available", true);
        manifest.setLong("revision", 2);
        manifest.setLong("generation", 3);
        manifest.setInteger("resourceReview", 1);
        data.setTag("detailManifest", manifest);
        try {
            NBTTagCompound unavailable = (NBTTagCompound) data.copy();
            unavailable.getCompoundTag("detailManifest")
                .setBoolean("available", false);
            unavailable.getCompoundTag("detailManifest")
                .setInteger("resourceReview", 0);
            check(
                !HologramSnapshotDetailsClient.complete(unavailable, "resourceReview"),
                "an unavailable zero-row source is not a completed report");
            HologramSnapshotDetailsClient.clear();
            HologramSnapshotDetailsClient.list(data, "resourceReview", 10, 0, 1);
            NBTTagCompound reply = reply(pendingId(), 1);
            reply.setBoolean("complete", false);
            reply.setBoolean("detailTruncated", true);
            HologramSnapshotDetailsClient.receive(reply, closed::incrementAndGet, 100);
            check(
                !HologramSnapshotDetailsClient.complete(data, "resourceReview"),
                "loading all retained rows cannot certify a truncated source");
            HologramSnapshotDetailsClient.clear();
            HologramSnapshotDetailsClient.list(data, "resourceReview", 10, 0, 1);
            reply = reply(pendingId(), 1);
            reply.setInteger("total", -1);
            HologramSnapshotDetailsClient.receive(reply, closed::incrementAndGet, 100);
            check(
                HologramSnapshotDetailsClient.count(data, "resourceReview") == 1,
                "malformed totals cannot replace the manifest count");
            check(
                !HologramSnapshotDetailsClient.complete(data, "resourceReview"),
                "malformed pages leave details pending");
            HologramSnapshotDetailsClient.clear();
            HologramSnapshotDetailsClient.list(data, "resourceReview", 10, 0, 1);
            reply = reply(pendingId(), 1);
            reply.setBoolean("complete", true);
            HologramSnapshotDetailsClient.receive(reply, closed::incrementAndGet, 100);
            check(
                HologramSnapshotDetailsClient.complete(data, "resourceReview"),
                "an exact complete report can be certified after its rows arrive");
        } finally {
            HologramSnapshotDetailsClient.clear();
        }
        check(closed.get() == 3, "rejected pages and cleared accepted pages release all inbound ownership");
    }

    private static NBTTagCompound state() {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "demand");
        data.setLong("planRevision", 2);
        return data;
    }

    private static NBTTagCompound reply(String id, int total) {
        NBTTagCompound reply = state();
        reply.setString("requestId", id);
        reply.setLong("detailGeneration", 3);
        reply.setString("domain", "resourceReview");
        reply.setInteger("offset", 0);
        reply.setInteger("total", total);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(new NBTTagCompound());
        reply.setTag("rows", rows);
        return reply;
    }

    private static String pendingId() throws ReflectiveOperationException {
        Map<?, ?> pending = (Map<?, ?>) field(HologramSnapshotDetailsClient.class, "pending").get(null);
        check(pending.size() == 1, "one bounded detail request exists");
        return (String) pending.keySet()
            .iterator()
            .next();
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
