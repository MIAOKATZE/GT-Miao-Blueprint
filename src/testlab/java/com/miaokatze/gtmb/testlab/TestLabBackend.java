package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;

import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

/** Exact server implementation calls for a save-owned lab actor, never a fabricated network connection. */
final class TestLabBackend {

    private final TestLabRuntime runtime;
    private final Method execute, capture;

    TestLabBackend(TestLabRuntime runtime) throws ReflectiveOperationException {
        this.runtime = runtime;
        execute = Class.forName("com.miaokatze.gtmb.hologram.HologramCutService")
            .getDeclaredMethod("execute", EntityPlayerMP.class, NBTTagCompound.class);
        capture = Class.forName("com.miaokatze.gtmb.hologram.HologramCutEntry")
            .getDeclaredMethod(
                "capture",
                WorldServer.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class);
        execute.setAccessible(true);
        capture.setAccessible(true);
    }

    NBTTagCompound capture(TestLabZone zone, int sx, int sy, int sz) throws Exception {
        runtime.requireOwned(zone.x + 8, zone.y + 1, zone.z + 8, sx, sy, sz);
        return (NBTTagCompound) invoke(capture, runtime.world(), zone.x + 8, zone.y + 1, zone.z + 8, sx, sy, sz);
    }

    NBTTagCompound action(String operation, String id) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("action", operation);
        n.setString("id", id);
        n.setString(
            "requestId",
            UUID.randomUUID()
                .toString());
        return n;
    }

    NBTTagCompound call(NBTTagCompound request) throws Exception {
        runtime.requireActor();
        verifyCache();
        NBTTagCompound reply = (NBTTagCompound) invoke(execute, runtime.actor().player, request.copy());
        if (!reply.getBoolean("success"))
            throw new IOException("Production cut rejected: " + reply.getString("message"));
        return reply;
    }

    /** A failed previous run may retain a real pending transaction: never erase that cache or reset around it. */
    void verifyCache() throws IOException {
        Path file = runtime.ownership().root.resolve("gtmb-cut")
            .resolve(runtime.actor().id + ".nbt");
        if (!Files.exists(file)) return;
        if (!Files.isRegularFile(file) || Files.size(file) > 4 * 1024 * 1024) throw new IOException(
            "Lab actor cache is not a bounded regular record; recovery needs operator inspection");
        NBTTagCompound state = HologramSnapshotCodec.decode(Files.readAllBytes(file));
        NBTTagList entries = state.getTagList("entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) origin(entries.getCompoundTagAt(i));
        if (state.hasKey("pending", 10)) {
            NBTTagCompound pending = state.getCompoundTag("pending"), entry = pending.getCompoundTag("entry");
            if (pending.getInteger("dimension") != runtime.config().dimension)
                throw new IOException("Lab pending dimension is outside ownership");
            runtime.requireOwned(
                pending.getInteger("x"),
                pending.getInteger("y"),
                pending.getInteger("z"),
                entry.getInteger("sizeX"),
                entry.getInteger("sizeY"),
                entry.getInteger("sizeZ"));
            runtime.useContainingZone(pending.getInteger("x"), pending.getInteger("y"), pending.getInteger("z"));
        }
    }

    private void origin(NBTTagCompound entry) throws IOException {
        if (entry.getInteger("dimension") != runtime.config().dimension)
            throw new IOException("Lab cache origin is outside its dimension");
        runtime.requireOwned(
            entry.getInteger("x"),
            entry.getInteger("y"),
            entry.getInteger("z"),
            entry.getInteger("sizeX"),
            entry.getInteger("sizeY"),
            entry.getInteger("sizeZ"));
    }

    byte[] cacheBytes() throws IOException {
        verifyCache();
        Path file = runtime.ownership().root.resolve("gtmb-cut")
            .resolve(runtime.actor().id + ".nbt");
        return Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
    }

    private static Object invoke(Method method, Object... args) throws Exception {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException cause) {
            if (cause.getCause() instanceof Exception) throw (Exception) cause.getCause();
            throw new IOException("Native server callback failed", cause.getCause());
        }
    }
}
