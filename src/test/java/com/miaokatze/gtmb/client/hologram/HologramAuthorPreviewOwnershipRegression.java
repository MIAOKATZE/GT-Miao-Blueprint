package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real native-owner switches and region cursors; hidden-layer work avoids claiming headless GL evidence. */
public final class HologramAuthorPreviewOwnershipRegression {

    private HologramAuthorPreviewOwnershipRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorPreviewOwnershipRegression.class, args)) return;
        run();
        System.out.println("HologramAuthorPreviewOwnershipRegression PASS");
    }

    public static void run() throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray rows = new JsonArray();
        for (int region = 0; region < 3; region++) for (int index = 0; index < 32; index++) {
            JsonObject row = new JsonObject();
            row.addProperty("x", region * 32 + index % 16);
            row.addProperty("y", 0);
            row.addProperty("z", index / 16);
            row.addProperty("block", "minecraft:stone");
            // A finite model publication in each region invalidates real partial preparation work.
            if (index == 0) row.addProperty("tileNbt", "unsupported-fixture-payload");
            rows.add(row);
        }
        source.add("cells", rows);
        AuthorDocument document = AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
        Constructor<HologramLocalAuthorRuntime> constructor = HologramLocalAuthorRuntime.class
            .getDeclaredConstructor(AuthorDocument.class, boolean.class);
        constructor.setAccessible(true);
        HologramLocalAuthorRuntime author = constructor.newInstance(document, false);
        HologramAuthorProjection projection = (HologramAuthorProjection) field(
            HologramLocalAuthorRuntime.class,
            "projection").get(author);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        HologramScreen screen = (HologramScreen) unsafe.allocateInstance(HologramScreen.class);
        HologramUiHost host = (HologramUiHost) Proxy.newProxyInstance(
            HologramUiHost.class.getClassLoader(),
            new Class<?>[] { HologramUiHost.class },
            (proxy, method, args) -> { throw new AssertionError("No UI/GL calls: " + method.getName()); });
        screen.ui = new HologramUiModules(host);
        field(HologramUiSession.class, "author").set(screen.ui.session, author);
        field(HologramUiSession.class, "state").set(screen.ui.session, author.view());
        NBTTagCompound worldData = new NBTTagCompound();
        worldData.setString("session", "physical-world-owner");
        HologramState world = new HologramState(worldData);
        Class<?> type = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation");
        Constructor<?> prepare = type.getDeclaredConstructor(HologramState.class);
        prepare.setAccessible(true);
        Method advance = type
            .getDeclaredMethod("advance", int.class, int.class, boolean.class, long.class, LongSupplier.class);
        advance.setAccessible(true);
        Field active = field(HologramRenderer.class, "previewPreparation");
        try {
            HologramRenderer.release();
            for (int frame = 0; frame < 100; frame++) {
                // renderWorldPreview's exact ownership gate, followed by world()/preview()'s actual begin chain.
                if (!HologramClient.screenOwnsNativePreview(screen)) HologramBlockRender.begin(world);
                if (frame == 5 || frame == 9 || frame == 13) {
                    HologramState current = projection.view();
                    projection.request(current, current.cells.get((frame - 5) / 4 * 32));
                    projection.advance(() -> 0);
                }
                HologramState current = projection.view();
                HologramBlockRender.begin(current);
                HologramRenderer.preparePreviewView(current, Integer.MIN_VALUE, 0);
                Object preparation = active.get(null);
                if (preparation == null) {
                    preparation = prepare.newInstance(current);
                    active.set(null, preparation);
                }
                AtomicLong clock = new AtomicLong();
                advance.invoke(preparation, Integer.MIN_VALUE, 0, false, 4L, (LongSupplier) clock::getAndIncrement);
            }
            Object preparation = active.get(null);
            SimpleAssert.eq(
                96,
                field(type, "detailedCompleted").getInt(preparation),
                "all three regions finish after frame yields and native model publications");
            SimpleAssert.that(
                ((Map<?, ?>) field(type, "pending").get(preparation)).isEmpty(),
                "a local editor cannot restart at its first hint region every world frame");
            SimpleAssert.eq(
                3,
                ((Map<?, ?>) field(HologramRenderer.class, "previewRegions").get(null)).size(),
                "every source region produces retained preparation output");
            SimpleAssert
                .that(HologramClient.screenOwnsNativePreview(screen), "current local editor owns native models");
            SimpleAssert.that(
                !HologramClient.screenOwnsNativePreview(null),
                "closing the UI restores world rendering even while the document remains alive");
            HologramBlockRender.begin(world);
            SimpleAssert.eq(
                null,
                field(HologramRenderer.class, "visibleState").get(null),
                "world native ownership actually resumes after the screen closes");
            field(HologramUiSession.class, "author").set(screen.ui.session, null);
            SimpleAssert.that(
                !HologramClient.screenOwnsNativePreview(screen),
                "physical workspace screens keep world projection available");
            field(HologramUiLibrary.class, "libraryDrawer").setBoolean(screen.ui.library, true);
            field(HologramUiLibrary.class, "browsePreview").setBoolean(screen.ui.library, true);
            SimpleAssert.that(HologramClient.screenOwnsNativePreview(screen), "existing library ownership is retained");
        } finally {
            projection.close();
            HologramRenderer.release();
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
