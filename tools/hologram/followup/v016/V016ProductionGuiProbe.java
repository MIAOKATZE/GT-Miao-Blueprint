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
import java.util.Map;
import java.io.InputStream;

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
    private boolean mui2, normalScene, operated, reloaded;
    private int transitionFrame;
    private Object modularScreen;
    private long packetsBefore;

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
            mui2 = Class.forName("com.cleanroommc.modularui.api.IMuiScreen").isAssignableFrom(screenType);
            if (mui2) {
                Object mod = ((Map<?, ?>) cpw.mods.fml.common.Loader.instance().getIndexedModList()).get("modularui2");
                String version = String.valueOf(method(mod.getClass(), "getVersion").invoke(mod));
                if (!version.equals("2.3.92-1.7.10")) throw new IllegalStateException("Actual MUI2 .92 required: " + version);
                receipt.setProperty("mui2Version", version);
                Class<?> wrapper = Class.forName("com.cleanroommc.modularui.screen.GuiScreenWrapper");
                receipt.setProperty("mui2Resource", String.valueOf(wrapper.getResource("GuiScreenWrapper.class")));
                receipt.setProperty("scope", "real production FML normal GTMB/MUI2 2.3.92; main-menu client snapshot, native controls and real screen hit testing; no world/backend/network business claim");
                Class.forName("com.miaokatze.gtmb.hologram.HologramRuntimeMetrics")
                    .getMethod("setEnabled", boolean.class).invoke(null, true);
                packetsBefore = cutPackets();
            }
            receipt.setProperty("screenResource", String.valueOf(screenType.getResource("HologramScreen.class")));
            receipt.setProperty("hostResource", String.valueOf(Class.forName(
                "com.miaokatze.gtmb.client.hologram.HologramUiHost").getResource("HologramUiHost.class")));
            if (!receipt.getProperty("screenResource").startsWith("jar:")
                || receipt.getProperty("screenResource").contains("-dev.jar"))
                throw new IllegalStateException("Actual normal JAR class origin required");
            Constructor<?> screenConstructor = screenType.getDeclaredConstructor(state);
            screenConstructor.setAccessible(true);
            screen = screenConstructor.newInstance(snapshot);
            if (mui2) modularScreen = method(screen.getClass(), "getScreen").invoke(screen);
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
            if (mui2 && !observeMigration()) return;
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

    private long cutPackets() throws Exception {
        Object metrics = Class.forName("com.miaokatze.gtmb.hologram.HologramRuntimeMetrics")
            .getMethod("snapshot").invoke(null);
        Method compound = method(metrics.getClass(), "func_74775_l", String.class);
        Object row = compound.invoke(compound.invoke(metrics, "network"), "cutAction");
        return ((Number) method(row.getClass(), "func_74763_f", String.class).invoke(row, "c2sTxPackets")).longValue();
    }

    /** Uses real MUI2 screen hit testing. No widget callback is called directly. */
    private boolean observeMigration() throws Exception {
        if (!normalScene) {
            Class<?> nbt = Class.forName("net.minecraft.nbt.NBTTagCompound");
            Object data = nbt.getConstructor().newInstance();
            method(nbt, "func_74778_a", String.class, String.class).invoke(data, "session", "v016-production-gui");
            Class<?> state = Class.forName("com.miaokatze.gtmb.client.hologram.HologramState");
            Constructor<?> constructor = state.getDeclaredConstructor(nbt);
            constructor.setAccessible(true);
            method(screen.getClass(), "updateState", state).invoke(screen, constructor.newInstance(data));
            normalScene = true;
            transitionFrame = frames;
            return false;
        }
        if (frames < transitionFrame + 3) return false;
        if (!operated) {
            Object panel = method(modularScreen.getClass(), "getMainPanel").invoke(modularScreen);
            if (!Boolean.TRUE.equals(method(panel.getClass(), "isValid").invoke(panel)))
                throw new IllegalStateException("Actual main Panel is not initialized");
            List<Object> widgets = new ArrayList<Object>();
            widgets.add(panel);
            Class<?> buttonType = Class.forName("com.cleanroommc.modularui.widgets.ButtonWidget");
            Class<?> fieldType = Class.forName("com.cleanroommc.modularui.widgets.textfield.TextFieldWidget");
            Class<?> widgetType = Class.forName("com.cleanroommc.modularui.api.widget.IWidget");
            // .92 defines getChildren as an interface default for leaves. Invoke that actual contract
            // so parent overrides expand naturally and leaf defaults return the library's empty list.
            Method childrenMethod = widgetType.getMethod("getChildren");
            Class<?> theme = Class.forName("com.miaokatze.gtmb.client.hologram.HologramMuiTheme");
            Field buttonTexture = theme.getDeclaredField("BUTTON"), pressedTexture = theme.getDeclaredField("PRESSED"), fieldTexture = theme.getDeclaredField("PANEL");
            buttonTexture.setAccessible(true); pressedTexture.setAccessible(true); fieldTexture.setAccessible(true);
            Object target = null;
            int buttons = 0, fields = 0;
            for (int i = 0; i < widgets.size(); i++) {
                Object widget = widgets.get(i);
                if (!widgetType.isInstance(widget)) throw new IllegalStateException("Non-widget in actual Panel tree");
                List<?> children = (List<?>) childrenMethod.invoke(widget);
                if (children == null) throw new IllegalStateException("Actual widget returned null children");
                widgets.addAll(children);
                if (buttonType.isInstance(widget)) {
                    buttons++;
                    if (method(widget.getClass(), "getBackground").invoke(widget) != buttonTexture.get(null)
                        || method(widget.getClass(), "getHoverBackground").invoke(widget) != pressedTexture.get(null))
                        throw new IllegalStateException("Actual button bypasses shared pocket texture");
                    if ("projector_control_10".equals(method(widget.getClass(), "getName").invoke(widget))) target = widget;
                }
                if (fieldType.isInstance(widget)) {
                    fields++;
                    if (method(widget.getClass(), "getBackground").invoke(widget) != fieldTexture.get(null))
                        throw new IllegalStateException("Actual text field bypasses shared pocket texture");
                }
            }
            if (buttons == 0 || fields == 0 || target == null) throw new IllegalStateException("Actual native controls missing");
            receipt.setProperty("actualNativeButtons", String.valueOf(buttons));
            receipt.setProperty("actualNativeTextFields", String.valueOf(fields));
            receipt.setProperty("actualMainPanel", panel.getClass().getName());
            Object preview = field(field(screen, "ui"), "preview");
            int before = ((Number) field(preview, "view")).intValue();
            Object area = widgetType.getMethod("getArea").invoke(target);
            float localX = ((Number) field(area, "width")).intValue() / 2f;
            float localY = ((Number) field(area, "height")).intValue() / 2f;
            Object located = Class.forName("com.cleanroommc.modularui.screen.viewport.LocatedWidget")
                .getMethod("of", widgetType).invoke(null, target);
            Object transform = located.getClass().getMethod("getTransformationMatrix").invoke(located);
            int x = ((Number) transform.getClass().getMethod("transformX", float.class, float.class)
                .invoke(transform, localX, localY)).intValue();
            int y = ((Number) transform.getClass().getMethod("transformY", float.class, float.class)
                .invoke(transform, localX, localY)).intValue();
            receipt.setProperty("target.name", String.valueOf(widgetType.getMethod("getName").invoke(target)));
            receipt.setProperty("target.area", String.valueOf(area));
            receipt.setProperty("target.relativeArea", field(area, "rx") + "," + field(area, "ry"));
            receipt.setProperty("target.localCenter", localX + "," + localY);
            receipt.setProperty("target.screenCenter", x + "," + y);
            receipt.setProperty("target.viewportMatrix", String.valueOf(transform.getClass().getMethod("getMatrix").invoke(transform)));
            receipt.setProperty("target.enabled", String.valueOf(widgetType.getMethod("isEnabled").invoke(target)));
            receipt.setProperty("target.ancestorsEnabled", String.valueOf(widgetType.getMethod("areAncestorsEnabled").invoke(target)));
            receipt.setProperty("target.valid", String.valueOf(widgetType.getMethod("isValid").invoke(target)));
            receipt.setProperty("target.canHover", String.valueOf(widgetType.getMethod("canHover").invoke(target)));
            receipt.setProperty("target.canClickThrough", String.valueOf(widgetType.getMethod("canClickThrough").invoke(target)));
            Object nativeButton = ((Map<?, ?>) field(screen, "buttons")).get(Integer.valueOf(10));
            receipt.setProperty("target.nativeEnabled", String.valueOf(field(nativeButton, "enabled")));
            receipt.setProperty("target.nativeFrame", String.valueOf(field(nativeButton, "frame")));
            receipt.setProperty("target.ownerFrame", String.valueOf(field(screen, "nativeFrame")));
            receipt.setProperty("target.nativeText", String.valueOf(field(nativeButton, "text")));
            receipt.setProperty("operationBefore", String.valueOf(before));
            Object context = method(modularScreen.getClass(), "getContext").invoke(modularScreen);
            method(context.getClass(), "updateState", int.class, int.class, float.class).invoke(context, x, y, 0f);
            method(modularScreen.getClass(), "onFrameUpdate").invoke(modularScreen);
            List<String> hovered = new ArrayList<String>();
            boolean targetHovered = false;
            for (Object hover : (Iterable<?>) method(panel.getClass(), "getHovering").invoke(panel)) {
                Object element = method(hover.getClass(), "getElement").invoke(hover);
                if (element == target) targetHovered = true;
                hovered.add(element == null ? "null" : element.getClass().getName() + ":"
                    + widgetType.getMethod("getName").invoke(element));
            }
            receipt.setProperty("target.actualHovering", hovered.toString());
            receipt.setProperty("target.foundInActualHovering", String.valueOf(targetHovered));
            if (!targetHovered) throw new IllegalStateException("Transformed target is absent from actual MUI hit-test list");
            if (Boolean.TRUE.equals(method(modularScreen.getClass(), "onMouseInputPre", int.class, boolean.class)
                .invoke(modularScreen, 0, true))) throw new IllegalStateException("Unexpected dragged element owns fixture input");
            Object pressed = method(modularScreen.getClass(), "onMousePressed", int.class).invoke(modularScreen, 0);
            receipt.setProperty("target.mousePressedResult", String.valueOf(pressed));
            if (!Boolean.TRUE.equals(pressed))
                throw new IllegalStateException("Native screen hit test did not consume control click");
            if (!Boolean.TRUE.equals(method(modularScreen.getClass(), "onMouseInputPre", int.class, boolean.class)
                .invoke(modularScreen, 0, false)))
                method(modularScreen.getClass(), "onMouseRelease", int.class).invoke(modularScreen, 0);
            int after = ((Number) field(preview, "view")).intValue();
            receipt.setProperty("operationAfter", String.valueOf(after));
            if (after != (before + 1) % 3) throw new IllegalStateException("Native button did not update actual preview view");
            receipt.setProperty("operation", "projector_control_10 real ModularScreen hit-test dispatch, no OS-input claim");
            receipt.setProperty("operationBefore", String.valueOf(before));
            receipt.setProperty("operationAfter", String.valueOf(after));
            verifyTextures();
            screenshot();
            method(minecraft.getClass(), "func_110436_a").invoke(minecraft);
            receipt.setProperty("resourceReload", "actual Minecraft.refreshResources called");
            operated = true;
            transitionFrame = frames;
            return false;
        }
        if (frames < transitionFrame + 4) return false;
        if (!reloaded) {
            if (field(minecraft, "field_71462_r") != screen) throw new IllegalStateException("Reload replaced actual screen");
            verifyTextures();
            if (cutPackets() != packetsBefore) throw new IllegalStateException("Client-only fixture sent a CutAction");
            receipt.setProperty("resourceReload", "PASS actual reload, four later natural renders, pocket resources rebound");
            receipt.setProperty("cutActionCodecPackets", "0");
            reloaded = true;
        }
        return true;
    }

    private void verifyTextures() throws Exception {
        Class<?> resourceLocation = Class.forName("net.minecraft.util.ResourceLocation");
        Class<?> theme = Class.forName("com.miaokatze.gtmb.client.hologram.HologramMuiTheme");
        Object resourceManager = method(minecraft.getClass(), "func_110442_L").invoke(minecraft);
        Object textures = method(minecraft.getClass(), "func_110434_K").invoke(minecraft);
        String[] names = { "PANEL", "CLOTH", "BUTTON", "PRESSED" };
        String[] files = { "panel", "cloth", "btn", "btn_pressed" };
        int[] expectedWidths = { 64, 32, 88, 88 }, expectedHeights = { 64, 32, 18, 18 };
        for (int i = 0; i < names.length; i++) {
            Field constant = theme.getDeclaredField(names[i]); constant.setAccessible(true);
            Object texture = constant.get(null);
            Object location = method(texture.getClass(), "getLocation").invoke(texture);
            String path = "gtmb:textures/gui/pocket/POCKET_C2_" + files[i] + ".png";
            if (!path.equals(location.toString())) throw new IllegalStateException("Pocket texture changed: " + location);
            Object resource = method(resourceManager.getClass(), "func_110536_a", resourceLocation).invoke(resourceManager, location);
            InputStream in = (InputStream) method(resource.getClass(), "func_110527_b").invoke(resource);
            BufferedImage decoded;
            try { decoded = ImageIO.read(in); } finally { in.close(); }
            if (decoded == null || decoded.getWidth() != expectedWidths[i] || decoded.getHeight() != expectedHeights[i])
                throw new IllegalStateException("Real resource dimensions differ: " + path);
            method(textures.getClass(), "func_110577_a", resourceLocation).invoke(textures, location);
            Class<?> gl = Class.forName("org.lwjgl.opengl.GL11");
            int width = ((Number) gl.getMethod("glGetTexLevelParameteri", int.class, int.class, int.class).invoke(null, 3553, 0, 4096)).intValue();
            int height = ((Number) gl.getMethod("glGetTexLevelParameteri", int.class, int.class, int.class).invoke(null, 3553, 0, 4097)).intValue();
            if (width != expectedWidths[i] || height != expectedHeights[i])
                throw new IllegalStateException("Actual GL pocket binding dimensions differ: " + path);
            int error = ((Number) gl.getMethod("glGetError").invoke(null)).intValue();
            if (error != 0) throw new IllegalStateException("Pocket binding GL error " + error + ": " + path);
            receipt.setProperty("texture." + names[i], path + " decoded and GL bound " + width + "x" + height);
        }
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
        String name = mui2 && normalScene && !operated ? "native-hologram-before-reload.png" : "native-hologram-screen.png";
        screenshot(name);
        receipt.setProperty(name.contains("before") ? "screenshotBeforeReload" : "screenshot", name);
    }

    private void screenshot(String name) throws Exception {
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
        ImageIO.write(image, "png", new File(output, name));
    }

    private void fail(Throwable failure) {
        complete = true;
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        receipt.setProperty("failure", cause.toString());
        cause.printStackTrace();
        if (minecraft != null && screen != null) {
            try {
                screenshot("native-hologram-failure.png");
                receipt.setProperty("failureScreenshot", "native-hologram-failure.png");
            } catch (Throwable screenshotFailure) {
                receipt.setProperty("failureScreenshotError", screenshotFailure.toString());
            }
        }
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
