package gtmb.v016.probe;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.imageio.ImageIO;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Test-only addon; compiled without GTMB or Minecraft method/field links. Never shipped. */
@Mod(modid = "gtmb_v016_gui_probe", name = "GTMB v016 production GUI witness", version = "1")
public final class V016ProductionGuiProbe {
    private final Properties receipt = new Properties();
    private Object minecraft;
    private Object screen;
    private int ticks;
    private int frames;
    private boolean complete;
    private boolean dispatched;
    private long initialGeneration;
    private long lastObservedGeneration;
    private final List<Long> observedGenerations = new ArrayList<Long>();
    private File output;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (!Boolean.getBoolean("gtmb.v016.productionGui")) return;
        output = new File(System.getProperty("gtmb.v016.output"));
        if (!output.isDirectory() || new File(output, "native-gui.properties").exists())
            throw new IllegalStateException("Fresh precreated v016 output required");
        receipt.setProperty("scope", "real production FML normal JAR; main-menu empty import Screen; no world/network/business retest");
        receipt.setProperty("javaVersion", System.getProperty("java.version"));
        FMLCommonHandler.instance().bus().register(this);
        persist("RUNNING");
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method m = current.getDeclaredMethod(name, args); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private long renderGeneration() throws Exception {
        Object canvas = field(field(screen, "ui"), "canvas");
        return ((Number) method(canvas.getClass(), "renderGeneration").invoke(canvas)).longValue();
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (complete || event.phase != TickEvent.Phase.END) return;
        try {
            if (++ticks > 2400) throw new IllegalStateException("Startup/render timeout");
            if (screen != null || ticks < 40) return;
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch");
            Object deobfuscated = ((java.util.Map<?, ?>) launch.getField("blackboard").get(null))
                .get("fml.deobfuscatedEnvironment");
            receipt.setProperty("fmlDeobfuscatedEnvironment", String.valueOf(deobfuscated));
            if (!Boolean.FALSE.equals(deobfuscated)) throw new IllegalStateException("Production FML required");
            Class<?> mc = Class.forName("net.minecraft.client.Minecraft");
            minecraft = method(mc, "func_71410_x").invoke(null);
            Object current = field(minecraft, "field_71462_r");
            if (current == null) return;
            if (field(minecraft, "field_71441_e") != null) throw new IllegalStateException("Fresh main-menu fixture required");
            receipt.setProperty("previousScreen", current.getClass().getName());
            Class<?> nbt = Class.forName("net.minecraft.nbt.NBTTagCompound");
            Object data = nbt.getConstructor().newInstance();
            method(nbt, "func_74778_a", String.class, String.class).invoke(data, "session", "v016-production-gui");
            method(nbt, "func_74757_a", String.class, boolean.class).invoke(data, "areaImporting", true);
            Class<?> state = Class.forName("com.miaokatze.gtmb.client.hologram.HologramState");
            Constructor<?> stateConstructor = state.getDeclaredConstructor(nbt);
            stateConstructor.setAccessible(true);
            Object snapshot = stateConstructor.newInstance(data);
            Class<?> screenType = Class.forName("com.miaokatze.gtmb.client.hologram.HologramScreen");
            receipt.setProperty("screenResource", String.valueOf(screenType.getResource("HologramScreen.class")));
            receipt.setProperty("hostResource", String.valueOf(Class.forName(
                "com.miaokatze.gtmb.client.hologram.HologramUiHost").getResource("HologramUiHost.class")));
            if (!receipt.getProperty("screenResource").startsWith("jar:")
                || receipt.getProperty("screenResource").contains("-dev.jar"))
                throw new IllegalStateException("Actual normal JAR class origin required");
            Constructor<?> screenConstructor = screenType.getDeclaredConstructor(state);
            screenConstructor.setAccessible(true);
            screen = screenConstructor.newInstance(snapshot);
            initialGeneration = renderGeneration();
            lastObservedGeneration = initialGeneration;
            method(mc, "func_147108_a", Class.forName("net.minecraft.client.gui.GuiScreen")).invoke(minecraft, screen);
            receipt.setProperty("openedScreen", field(minecraft, "field_71462_r").getClass().getName());
            Class.forName("org.lwjgl.opengl.GL11").getMethod("glGetError").invoke(null);
            persist("OPENED");
        } catch (Throwable failure) { fail(failure); }
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (complete || screen == null || event.phase != TickEvent.Phase.END) return;
        try {
            if (field(minecraft, "field_71462_r") != screen) throw new IllegalStateException("Probe Screen was replaced");
            long generation = renderGeneration();
            if (generation < lastObservedGeneration) throw new IllegalStateException("Render generation regressed");
            if (generation == lastObservedGeneration) return;
            int error = ((Number) Class.forName("org.lwjgl.opengl.GL11").getMethod("glGetError").invoke(null)).intValue();
            if (error != 0) throw new IllegalStateException("Native GL error " + error);
            lastObservedGeneration = generation;
            observedGenerations.add(Long.valueOf(generation));
            frames++;
            if (!dispatched) {
                dispatchEveryHostMethod();
                dispatched = true;
                int dispatchError = ((Number) Class.forName("org.lwjgl.opengl.GL11").getMethod("glGetError").invoke(null)).intValue();
                if (dispatchError != 0) throw new IllegalStateException("Host dispatch GL error " + dispatchError);
            }
            if (frames < 4) return;
            receipt.setProperty("nativeFramesObserved", String.valueOf(frames));
            receipt.setProperty("initialGeneration", String.valueOf(initialGeneration));
            receipt.setProperty("finalGeneration", String.valueOf(generation));
            receipt.setProperty("observedGenerations", observedGenerations.toString());
            receipt.setProperty("glError", "0");
            screenshot();
            complete = true;
            persist("PASS");
            System.out.println("GTMB_V016_PRODUCTION_GUI_PASS " + output);
            method(minecraft.getClass(), "func_71400_g").invoke(minecraft);
        } catch (Throwable failure) { fail(failure); }
    }

    private void dispatchEveryHostMethod() throws Exception {
        Class<?> host = Class.forName("com.miaokatze.gtmb.client.hologram.HologramUiHost");
        Object font = field(screen, "field_146289_q");
        List<String> dispatchedMethods = new ArrayList<String>();
        for (Method m : host.getDeclaredMethods()) {
            m.setAccessible(true);
            Object[] args = new Object[m.getParameterTypes().length];
            for (int i = 0; i < args.length; i++) {
                Class<?> t = m.getParameterTypes()[i];
                args[i] = t == int.class ? Integer.valueOf(12) : t == String.class ? "v016 production bridge"
                    : t == List.class ? Arrays.asList("v016 native tooltip") : font;
            }
            m.invoke(screen, args);
            dispatchedMethods.add(m.getName());
        }
        if (dispatchedMethods.size() != 9) throw new IllegalStateException("Review changed host surface");
        receipt.setProperty("hostMethodsDispatched", dispatchedMethods.toString());
        receipt.setProperty("hostMethodCount", String.valueOf(dispatchedMethods.size()));
    }

    private void screenshot() throws Exception {
        int width = ((Number) field(minecraft, "field_71443_c")).intValue();
        int height = ((Number) field(minecraft, "field_71440_d")).intValue();
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        Class.forName("org.lwjgl.opengl.GL11").getMethod("glReadPixels", int.class, int.class,
            int.class, int.class, int.class, int.class, ByteBuffer.class)
            .invoke(null, 0, 0, width, height, 6408, 5121, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = ((height - y - 1) * width + x) * 4;
            image.setRGB(x, y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
        }
        ImageIO.write(image, "png", new File(output, "native-hologram-screen.png"));
        receipt.setProperty("screenshot", "native-hologram-screen.png");
    }

    private void fail(Throwable failure) {
        complete = true;
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        receipt.setProperty("failure", cause.toString());
        cause.printStackTrace();
        persist("FAIL");
        if (minecraft != null) {
            try { method(minecraft.getClass(), "func_71400_g").invoke(minecraft); }
            catch (Throwable ignored) { }
        }
    }

    private void persist(String status) {
        receipt.setProperty("status", status);
        try {
            FileOutputStream out = new FileOutputStream(new File(output, "native-gui.properties"));
            try { receipt.store(out, "Actual v016 production FML GUI observation"); } finally { out.close(); }
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
