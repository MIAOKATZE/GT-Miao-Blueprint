package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.client.render.TessellatorSlice;

import appeng.client.texture.FlippableIcon;

/** Independent original-getter numerical oracle; a foreign Tessellator proves commands, not real Slice replay. */
public final class HologramPreviewUvRegression {

    private static final int[][] ORDER = { { 0, 1, 5, 4 }, { 3, 7, 6, 2 }, { 0, 3, 2, 1 }, { 4, 5, 6, 7 },
        { 0, 4, 7, 3 }, { 1, 2, 6, 5 } };

    private HologramPreviewUvRegression() {}

    public static void main(String[] args) throws Exception {
        if (HologramPreviewUvRegression.class.getClassLoader() instanceof LaunchClassLoader)
            HologramUvProbe.install(HologramPreviewUvRegression.class);
        if (!MinecraftTestBootstrap.enter(HologramPreviewUvRegression.class, args)) return;
        run();
        getterCounts();
        System.out.println("HologramPreviewUvRegression PASS (real getters; headless replay explicitly not claimed)");
    }

    public static void run() {
        try {
            numericalOracle();
            invalidationAndAccounting();
            conservativeFallbacks();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("submission-owned live UV snapshots", failure);
        }
    }

    private static TextureAtlasSprite sprite(int x) throws ReflectiveOperationException {
        Constructor<TextureAtlasSprite> constructor = TextureAtlasSprite.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        TextureAtlasSprite icon = constructor.newInstance("fixture:uv");
        icon.setIconWidth(17);
        icon.setIconHeight(11);
        icon.initSprite(128, 64, x, -13, false);
        return icon;
    }

    private static void numericalOracle() throws ReflectiveOperationException {
        TextureAtlasSprite base = sprite(-21);
        FlippableIcon wrapper = new FlippableIcon(base);
        Fixture fixture = new Fixture(new IIcon[] { base, wrapper }, 1);
        Object submission = submission(fixture.faces, true);
        Object records = field(submission.getClass(), "iconRecords").get(submission);
        for (int flip = 0; flip < 4; flip++) {
            wrapper.setFlip((flip & 1) != 0, (flip & 2) != 0);
            compare(
                fixture.oracle(),
                draw(submission, false).vertices,
                "six sides/full and partial geometry/flip=" + flip);
        }
        wrapper.setOriginal(sprite(-21));
        compare(fixture.oracle(), draw(submission, false).vertices, "different endpoint identity, identical UV values");
        wrapper.setOriginal(sprite(47));
        compare(
            fixture.oracle(),
            draw(submission, false).vertices,
            "different endpoint UV is used in the change frame");
        wrapper.setOriginal(new TextureAtlasSprite("fixture:unknown-endpoint") {});
        compare(
            fixture.oracle(),
            draw(submission, false).vertices,
            "known to unknown draws the original getter commands");
        SimpleAssert.that(
            !(Boolean) method(
                batches(submission).get(0)
                    .getClass(),
                "valid").invoke(batches(submission).get(0)),
            "an unknown endpoint is ineligible in the same draw");
        SimpleAssert.that(
            !field(submission.getClass(), "unsupported").getBoolean(submission),
            "dynamic endpoint changes do not permanently reject Core capture");
        wrapper.setOriginal(base);
        compare(
            fixture.oracle(),
            draw(submission, false).vertices,
            "unknown to known resumes live snapshot eligibility");
        base.setFramesTextureData(Arrays.asList(new int[1][], new int[1][]));
        compare(
            fixture.oracle(),
            draw(submission, false).vertices,
            "static frame gate is rechecked on every used draw");
        SimpleAssert.that(
            !(Boolean) method(
                batches(submission).get(0)
                    .getClass(),
                "valid").invoke(batches(submission).get(0)),
            "a newly animated endpoint rejects cached replay immediately");
        base.setFramesTextureData(java.util.Collections.singletonList(new int[1][]));
        compare(fixture.oracle(), draw(submission, false).vertices, "a restored static gate can recover");
        SimpleAssert.that(
            (Boolean) method(
                batches(submission).get(0)
                    .getClass(),
                "valid").invoke(batches(submission).get(0)),
            "returning to one frame restores current eligibility");
        SimpleAssert.that(
            records == field(submission.getClass(), "iconRecords").get(submission),
            "records are reused across frames, flips and endpoint/qualification changes");
    }

    private static void invalidationAndAccounting() throws ReflectiveOperationException {
        TextureAtlasSprite base = sprite(3);
        FlippableIcon wrapper = new FlippableIcon(base);
        Fixture first = new Fixture(new IIcon[] { base }, 86), second = new Fixture(new IIcon[] { wrapper }, 1);
        List<Object> faces = new ArrayList<>(first.faces.subList(0, 1023));
        faces.addAll(second.faces);
        Object submission = submission(faces, true);
        prepare(submission);
        List<?> batches = batches(submission);
        SimpleAssert.eq(2, batches.size(), "two batches have independent base and wrapper dependencies");
        installSentinel(submission, batches.get(0), 32);
        installSentinel(submission, batches.get(1), 64);
        Object unchanged = field(
            batches.get(0)
                .getClass(),
            "slice").get(batches.get(0));
        Object versions = field(
            batches.get(1)
                .getClass(),
            "versions").get(batches.get(1));
        wrapper.setFlip(true, false);
        prepare(submission);
        SimpleAssert.eq(
            32,
            field(submission.getClass(), "retainedInts").getInt(submission),
            "only the changed dependency's slice is deducted, exactly once");
        SimpleAssert.that(
            unchanged == field(
                batches.get(0)
                    .getClass(),
                "slice").get(batches.get(0)),
            "an unrelated slice is retained");
        prepare(submission);
        SimpleAssert.eq(
            32,
            field(submission.getClass(), "retainedInts").getInt(submission),
            "repeated invalidation cannot double-deduct");
        installSentinel(submission, batches.get(1), 96);
        wrapper.setOriginal(sprite(3));
        prepare(submission);
        SimpleAssert.eq(
            32,
            field(submission.getClass(), "retainedInts").getInt(submission),
            "a new same-UV endpoint still invalidates the old identity after a replacement slice");
        installSentinel(submission, batches.get(1), 128);
        wrapper.setOriginal(new FlippableIcon(base));
        prepare(submission);
        SimpleAssert.eq(
            32,
            field(submission.getClass(), "retainedInts").getInt(submission),
            "nested endpoints cannot retain an old slice");
        wrapper.setOriginal(base);
        prepare(submission);
        SimpleAssert.that(
            (Boolean) method(
                batches.get(1)
                    .getClass(),
                "valid").invoke(batches.get(1)),
            "nested to exact static endpoint recovers without reconstructing the submission");
        SimpleAssert.that(
            versions == field(
                batches.get(1)
                    .getClass(),
                "versions").get(batches.get(1)),
            "captured-version arrays are reused");
        SimpleAssert.that(
            !field(submission.getClass(), "unsupported").getBoolean(submission),
            "dependency invalidation is distinct from actual Core unsupported");
        Field live = field(HologramRenderer.class, "previewSubmission");
        Object saved = live.get(null);
        try {
            live.set(null, submission);
            method(HologramRenderer.class, "invalidatePreviewDrawCache").invoke(null);
            SimpleAssert.that(
                live.get(null) == null,
                "the existing reload/close invalidation owns all icon records and slices");
        } finally {
            live.set(null, saved);
        }
    }

    /** Synthetic slices exercise ownership/accounting only; no fake capture/replay success is reported. */
    private static void installSentinel(Object submission, Object batch, int ints) throws ReflectiveOperationException {
        Constructor<?> constructor = TessellatorSlice.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object slice = constructor.newInstance(new int[ints], new byte[ints / 8], null, null);
        field(batch.getClass(), "slice").set(batch, slice);
        method(batch.getClass(), "captureVersions").invoke(batch);
        Field retained = field(submission.getClass(), "retainedInts");
        retained.setInt(submission, retained.getInt(submission) + ints);
    }

    private static void conservativeFallbacks() throws ReflectiveOperationException {
        TextureAtlasSprite base = sprite(8);
        FlippableIcon wrapper = new FlippableIcon(base);
        Fixture fixture = new Fixture(new IIcon[] { wrapper }, 100);
        Object legacy = submission(fixture.faces, false);
        SimpleAssert.that(
            field(legacy.getClass(), "icons").get(legacy) == null,
            "browse/partial legacy submission has no new live-record map");
        compare(fixture.oracle(), draw(legacy, true).vertices, "legacy wrapper fallback remains unchanged");
        SimpleAssert.that(
            !field(legacy.getClass(), "unsupported").getBoolean(legacy),
            "legacy wrapper is not sent to Core capture");
        for (IIcon unsupported : new IIcon[] { new TextureAtlasSprite("fixture:subclass") {},
            new FlippableIcon(base) {}, new FlippableIcon(new FlippableIcon(base)) }) {
            Fixture unknown = new Fixture(new IIcon[] { unsupported }, 1);
            Object submission = submission(unknown.faces, true);
            compare(unknown.oracle(), draw(submission, true).vertices, "subclass/nested fallback emits once");
            SimpleAssert.that(
                !field(submission.getClass(), "unsupported").getBoolean(submission),
                "ineligible icons do not poison Core unsupported");
        }
        Object foreign = submission(fixture.faces, true);
        RecordingTessellator actual = draw(foreign, true);
        compare(fixture.oracle(), actual.vertices, "foreign Core capture fallback still emits once");
        SimpleAssert.that(
            field(foreign.getClass(), "unsupported").getBoolean(foreign),
            "only a real unsupported Core capture enters persistent fallback");
        compare(
            fixture.oracle(),
            draw(foreign, true).vertices,
            "subsequent Core unsupported fallback remains once-only");
    }

    private static void getterCounts() throws ReflectiveOperationException {
        TextureAtlasSprite base = sprite(9);
        FlippableIcon wrapper = new FlippableIcon(base);
        Fixture fixture = new Fixture(new IIcon[] { base, wrapper }, 100);
        Object submission = submission(fixture.faces, true);
        for (int pass = 0; pass < 2; pass++) {
            wrapper.setFlip(pass != 0, pass != 0);
            List<double[]> expected = fixture.oracle();
            HologramUvProbe.begin();
            int[] counts;
            RecordingTessellator actual;
            try {
                actual = draw(submission, false);
            } finally {
                counts = HologramUvProbe.end();
            }
            SimpleAssert.that(
                Arrays.equals(new int[] { 2, 2, 2, 2, 1, 1, 1, 1 }, counts),
                "actual exact base/wrapper getter bodies run once per unique icon, not per face: "
                    + Arrays.toString(counts));
            SimpleAssert
                .eq(Arrays.asList(4092, 4092, 1416), actual.batchSizes, "the 1023-quad boundary/order does not change");
            compare(expected, actual.vertices, "large ordered multilayer numerical oracle pass " + pass);
            System.out.println("GTMB_UV_GETTERS U=2 quads=2400 actualBaseThenWrapper=" + Arrays.toString(counts));
        }
    }

    private static final class Fixture {

        final List<Object> faces = new ArrayList<>();
        final List<ExpectedFace> expected = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Fixture(IIcon[] icons, int repeats) throws ReflectiveOperationException {
            List<HologramTextures.Sample>[] sides = new List[6];
            for (int side = 0; side < 6; side++) {
                sides[side] = new ArrayList<>();
                for (int sample = 0; sample < icons.length; sample++)
                    sides[side].add(new HologramTextures.Sample(icons[sample], sample == 0 ? 0x31abed : 0xe86517));
            }
            HologramTextures.Material material = new HologramTextures.Material(
                sides,
                sides,
                HologramGeometry.cube(),
                false,
                false,
                false,
                false);
            Class<?> input = Class.forName(HologramRenderer.class.getName() + "$PreviewCell");
            Constructor<?> inputConstructor = input.getDeclaredConstructor(
                HologramState.Cell.class,
                int.class,
                String.class,
                int.class,
                HologramTextures.Material.class,
                boolean.class,
                boolean.class);
            inputConstructor.setAccessible(true);
            Class<?> face = Class.forName(HologramRenderer.class.getName() + "$Face");
            Constructor<?> faceConstructor = face
                .getDeclaredConstructor(input, double[][].class, int.class, HologramGeometry.Surface.class);
            faceConstructor.setAccessible(true);
            for (int repeat = 0; repeat < repeats; repeat++)
                for (int partial = 0; partial < 2; partial++) for (int side = 0; side < 6; side++) {
                    NBTTagCompound data = new NBTTagCompound();
                    boolean diagnostic = side == 3;
                    data.setBoolean("sourceMissing", diagnostic);
                    HologramState.Cell cell = new HologramState.Cell(faces.size(), data);
                    HologramGeometry.Surface surface = new HologramGeometry.Surface(
                        partial == 0 ? new double[] { 0, 0, 0, 1, 1, 1 }
                            : new double[] { .125, .25, .375, .875, .75, .625 },
                        side,
                        -1);
                    double[][] points = new double[8][3];
                    for (int vertex = 0; vertex < 8; vertex++)
                        points[vertex] = new double[] { repeat * 11.25 + surface.vertices[vertex][0] * 7.5,
                            surface.vertices[vertex][1] * -13.25, surface.vertices[vertex][2] * 3.125 + side };
                    Object prepared = inputConstructor.newInstance(cell, 63, "", 0, material, false, false);
                    faces.add(faceConstructor.newInstance(prepared, points, side, surface));
                    expected.add(new ExpectedFace(side, points, surface.vertices, sides[side], diagnostic));
                }
        }

        List<double[]> oracle() {
            List<double[]> result = new ArrayList<>();
            for (ExpectedFace face : expected) for (int sample = 0; sample < face.samples.size(); sample++) {
                HologramTextures.Sample layer = face.samples.get(sample);
                float shade = face.side == 1 ? 1 : face.side < 2 ? .6f : .8f;
                float red = ((layer.color >> 16) & 255) / 255f * shade;
                float green = ((layer.color >> 8) & 255) / 255f * shade;
                float blue = (layer.color & 255) / 255f * shade;
                for (int vertex : ORDER[face.side]) {
                    double[] local = face.local[vertex], point = face.points[vertex];
                    double u = face.side == 4 ? local[2] + .5
                        : face.side == 5 ? .5 - local[2] : face.side == 2 ? .5 - local[0] : local[0] + .5;
                    double v = face.side == 0 ? local[2] + .5 : face.side == 1 ? .5 - local[2] : .5 - local[1];
                    // Original IIcon expressions are independent of all production snapshot fields/methods.
                    double textureU = layer.icon.getMinU() + (layer.icon.getMaxU() - layer.icon.getMinU()) * u;
                    double textureV = layer.icon.getMinV() + (layer.icon.getMaxV() - layer.icon.getMinV()) * v;
                    result.add(
                        new double[] { point[0], point[1], point[2] * 2 + sample * .015, textureU, textureV,
                            face.diagnostic ? .55f : red, face.diagnostic ? .55f : green, face.diagnostic ? .55f : blue,
                            face.diagnostic ? .78f : .96f });
                }
            }
            return result;
        }
    }

    private static final class ExpectedFace {

        final int side;
        final double[][] points, local;
        final List<HologramTextures.Sample> samples;
        final boolean diagnostic;

        ExpectedFace(int side, double[][] points, double[][] local, List<HologramTextures.Sample> samples,
            boolean diagnostic) {
            this.side = side;
            this.points = new double[points.length][];
            this.local = new double[local.length][];
            for (int at = 0; at < points.length; at++) this.points[at] = points[at].clone();
            for (int at = 0; at < local.length; at++) this.local[at] = local[at].clone();
            this.samples = samples;
            this.diagnostic = diagnostic;
        }
    }

    private static Object submission(List<Object> faces, boolean snapshots) throws ReflectiveOperationException {
        Class<?> type = Class.forName(HologramRenderer.class.getName() + "$PreviewSubmission");
        Constructor<?> constructor = type.getDeclaredConstructor(List.class, double.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(faces, 2d, snapshots);
    }

    private static void prepare(Object submission) throws ReflectiveOperationException {
        method(submission.getClass(), "prepareIcons").invoke(submission);
    }

    private static List<?> batches(Object submission) throws ReflectiveOperationException {
        return (List<?>) field(submission.getClass(), "batches").get(submission);
    }

    private static RecordingTessellator draw(Object submission, boolean capture) throws ReflectiveOperationException {
        RecordingTessellator tess = new RecordingTessellator();
        method(submission.getClass(), "draw", Tessellator.class, boolean.class).invoke(submission, tess, capture);
        return tess;
    }

    private static void compare(List<double[]> expected, List<double[]> actual, String label) {
        SimpleAssert.eq(expected.size(), actual.size(), label + " vertex count");
        for (int vertex = 0; vertex < expected.size(); vertex++)
            for (int column = 0; column < 9; column++) SimpleAssert.eq(
                Double.doubleToRawLongBits(expected.get(vertex)[column]),
                Double.doubleToRawLongBits(actual.get(vertex)[column]),
                label + " vertex=" + vertex + " column=" + column);
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters)
        throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static final class RecordingTessellator extends Tessellator {

        final List<double[]> vertices = new ArrayList<>();
        final List<Integer> batchSizes = new ArrayList<>();
        int start;
        float red, green, blue, alpha;

        @Override
        public void startDrawing(int mode) {
            SimpleAssert.eq(7, mode, "ordinary quads");
            start = vertices.size();
        }

        @Override
        public int draw() {
            int count = vertices.size() - start;
            SimpleAssert.that(count <= 4092 && count % 4 == 0, "complete original batch boundaries");
            batchSizes.add(count);
            return count;
        }

        @Override
        public void setColorRGBA_F(float red, float green, float blue, float alpha) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }

        @Override
        public void addVertexWithUV(double x, double y, double z, double u, double v) {
            vertices.add(new double[] { x, y, z, u, v, red, green, blue, alpha });
        }
    }
}
