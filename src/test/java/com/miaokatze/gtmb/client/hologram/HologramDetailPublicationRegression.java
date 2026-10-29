package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.LongSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Production region-publication contracts; hidden-layer cursors deliberately do not claim native/GL performance. */
public final class HologramDetailPublicationRegression {

    private HologramDetailPublicationRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramDetailPublicationRegression.class, args)) return;
        run();
        System.out.println("HologramDetailPublicationRegression PASS");
    }

    public static void run() throws Exception {
        completedRegionsPublishBeforeGlobalCompletion();
        sameFrameCompletionsCoalesce();
    }

    private static void completedRegionsPublishBeforeGlobalCompletion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareHints();
            Object cheap = fixture.regions()
                .get(new HologramRegionCache.Key(0, 0, 0));
            fixture.step(false);
            Object detail = fixture.regions()
                .get(new HologramRegionCache.Key(0, 0, 0));
            SimpleAssert.that(detail != cheap, "the production cursor replaces the first cheap region");
            SimpleAssert.eq(true, field(detail.getClass(), "complete").getBoolean(detail), "replacement is complete");
            SimpleAssert.eq(4, fixture.count("completed"), "first detail region stays below the 2N milestone");
            SimpleAssert.eq(
                2,
                fixture.pending()
                    .size(),
                "other regions remain pending");
            SimpleAssert.eq(true, fixture.publish(), "first completed region invalidates presented cheap output");
            SimpleAssert.eq(false, fixture.publish(), "unchanged output does not request another publication");
            fixture.step(false);
            SimpleAssert.eq(5, fixture.count("completed"), "second completion remains below global completion");
            SimpleAssert.eq(true, fixture.publish(), "each later completed region is independently publishable");
            SimpleAssert.eq(false, fixture.publish(), "second event is consumed exactly once");
            fixture.step(false);
            SimpleAssert.eq(true, fixture.publish(), "last detail region still publishes");
            SimpleAssert.eq(
                0,
                fixture.pending()
                    .size(),
                "full detail preparation finishes");
            SimpleAssert.eq(false, fixture.publish(), "terminal output is not republished on idle frames");
        }
    }

    private static void sameFrameCompletionsCoalesce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareHints();
            fixture.step(true);
            SimpleAssert.eq(
                0,
                fixture.pending()
                    .size(),
                "one available frame completes all three regions");
            SimpleAssert.eq(true, fixture.publish(), "multiple replacements require one frame publication");
            SimpleAssert.eq(false, fixture.publish(), "coalesced replacements do not leave stale events");
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class Fixture implements AutoCloseable {

        final HologramAuthorProjection projection;
        final Class<?> type;
        final Object preparation;
        final Method advance, publish;

        Fixture() throws Exception {
            HologramRenderer.release();
            JsonObject source = new JsonObject();
            source.addProperty("schema", 2);
            JsonArray rows = new JsonArray();
            for (int region = 0; region < 3; region++) {
                JsonObject row = new JsonObject();
                row.addProperty("x", region * 32);
                row.addProperty("y", 0);
                row.addProperty("z", 0);
                row.addProperty("block", "minecraft:stone");
                rows.add(row);
            }
            source.add("cells", rows);
            projection = new HologramAuthorProjection(
                AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()));
            type = Class.forName(HologramRenderer.class.getName() + "$PreviewPreparation");
            Constructor<?> constructor = type.getDeclaredConstructor(HologramState.class);
            constructor.setAccessible(true);
            preparation = constructor.newInstance(projection.view());
            field(HologramRenderer.class, "previewPreparation").set(null, preparation);
            advance = type
                .getDeclaredMethod("advance", int.class, int.class, boolean.class, long.class, LongSupplier.class);
            advance.setAccessible(true);
            publish = type.getDeclaredMethod("publishProgress");
            publish.setAccessible(true);
        }

        void prepareHints() throws Exception {
            for (int region = 0; region < 3; region++) {
                step(false);
                publish();
            }
            SimpleAssert.eq(3, count("published"), "all cheap output is already published");
            SimpleAssert.eq(0, count("detailedCompleted"), "no detail was prepared before the test boundary");
            SimpleAssert.eq(false, publish(), "no pending cheap publication remains");
        }

        void step(boolean unlimited) throws Exception {
            advance.invoke(
                preparation,
                Integer.MIN_VALUE,
                0,
                false,
                unlimited ? Long.MAX_VALUE : 0L,
                (LongSupplier) () -> 0L);
        }

        boolean publish() throws Exception {
            return (Boolean) publish.invoke(preparation);
        }

        int count(String name) throws Exception {
            return field(type, name).getInt(preparation);
        }

        Map<?, ?> pending() throws Exception {
            return (Map<?, ?>) field(type, "pending").get(preparation);
        }

        Map<?, ?> regions() throws Exception {
            return (Map<?, ?>) field(HologramRenderer.class, "previewRegions").get(null);
        }

        public void close() {
            HologramRenderer.release();
            projection.close();
        }
    }
}
