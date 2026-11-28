package com.miaokatze.gtmb.client.hologram;

import static miaotest.host.ClientAccess.call;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

/** Host-only event bridge to the existing testlab probe. It never calls or replaces production business methods. */
public final class GtmbEbfActionsProbe {
    private static GtmbEbfActionsProbe active;
    private final Object mc, server, delegate;
    private final Map<String, Object> parent = new LinkedHashMap<String, Object>();
    private volatile Throwable error;
    private boolean ending, terminal, cleaned, errorRecorded;
    private long frame;

    public static void begin(Object minecraft, String run, Path output) throws Exception {
        if (active != null) throw new IllegalStateException("EBF actions probe already exists");
        active = new GtmbEbfActionsProbe(minecraft, run, output);
        FMLCommonHandler.instance().bus().register(active);
        MinecraftForge.EVENT_BUS.register(active);
    }

    private GtmbEbfActionsProbe(Object minecraft, String run, Path output) throws Exception {
        if (!"ebf-actions".equals(System.getProperty("miaotest.host.profile"))
            || !Boolean.getBoolean("miaotest.host.environment")) throw new IllegalStateException("Exact host profile required");
        mc = minecraft;
        server = call(mc, "getIntegratedServer|func_71401_C");
        if (server == null) throw new IllegalStateException("Owned integrated server required");
        Path save = Paths.get(System.getProperty("miaotest.host.worldPath")).toRealPath();
        JsonObject marker = new JsonParser().parse(new String(java.nio.file.Files.readAllBytes(
            save.resolve(".miaotest-host-owner.json")), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (!run.equals(marker.get("runId").getAsString()) || !save.getFileName().toString().equals("MiaoHost-" + run))
            throw new IllegalStateException("Exact run-owned save marker required");
        // Selected only after this host-owned world logged in. No global launch flag changes other profiles.
        System.setProperty("gtmb.testlab.fourMachineRuntime", "true");
        System.setProperty("gtmb.testlab.fourMachineSlice", "ebf-actions");
        System.setProperty("gtmb.testlab.fourMachinePerformance", "true");
        Class<?> probe = Class.forName("com.miaokatze.gtmb.client.hologram.HologramFourMachineRuntimeProbe");
        Constructor<?> create = probe.getDeclaredConstructor(Path.class, Path.class, UUID.class, Map.class);
        create.setAccessible(true);
        delegate = create.newInstance(save, output.toRealPath(), UUID.fromString(System.getProperty("miaotest.host.actorUuid")), parent);
    }

    private Object invoke(String name, Object... args) throws Exception {
        Method found = null;
        for (Method method : delegate.getClass().getDeclaredMethods()) if (method.getName().equals(name)
            && method.getParameterTypes().length == args.length) {
            if (found != null) throw new IllegalStateException("Ambiguous testlab bridge: " + name);
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("Existing testlab probe: " + name);
        found.setAccessible(true);
        try { return found.invoke(delegate, args); }
        catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException(cause);
        }
    }

    public static void tick() throws Exception { if (active != null) active.client(); }
    private void client() throws Exception {
        if (terminal) return;
        try {
            if (error != null && !errorRecorded) {
                invoke("fail", error.toString());
                errorRecorded = true;
                ending = true;
            }
            if (!ending) ending = Boolean.TRUE.equals(invoke("client", mc));
        } catch (Throwable failed) {
            if (error == null) error = failed;
            invoke("fail", failed.toString());
            errorRecorded = true;
            ending = true;
        }
        if (ending && Boolean.TRUE.equals(invoke("finish", mc))) terminal = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (terminal || event.phase != TickEvent.Phase.END) return;
        try { invoke("server", call(server, "worldServerForDimension|func_71218_a", 0)); }
        catch (Throwable failed) { if (error == null) error = failed; }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void guiPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (terminal || ending) return;
        try { invoke("guiPost", event.gui, ++frame); }
        catch (Throwable failed) { if (error == null) error = failed; }
    }

    public static boolean done() { return active != null && active.terminal; }
    public static Throwable failure() { return active == null ? null : active.error; }
    public static JsonObject report() {
        return active == null ? new JsonObject() : new JsonParser().parse(new Gson().toJson(
            active.parent.get("fourMachineRuntime"))).getAsJsonObject();
    }
    public static void cleanup() {
        if (active == null || active.cleaned) return;
        if (!active.terminal) throw new IllegalStateException("Business restoration must finish before bridge removal");
        FMLCommonHandler.instance().bus().unregister(active);
        MinecraftForge.EVENT_BUS.unregister(active);
        active.cleaned = true;
    }
}
