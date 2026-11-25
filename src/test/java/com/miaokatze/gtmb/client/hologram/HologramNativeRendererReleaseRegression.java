// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.hologram.HologramNativeBlueprintStorage;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCodecRegression;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Cold native reopen -> real renderer's CPU projection -> the same projection release called on world change. */
public final class HologramNativeRendererReleaseRegression {

    private static final String ID = "local_63fde59e-7b9e-4779-a90e-2a63e0211d02";

    private HologramNativeRendererReleaseRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramNativeRendererReleaseRegression.class, args)) return;
        Path root = Files.createTempDirectory("gtmb-native-renderer-release-");
        try {
            var first = open(root.resolve("first"), "groupA", 3);
            try {
                var second = open(root.resolve("second"), "groupB", 12);
                try {
                    var a = view(first);
                    var b = view(second);
                    var editor = HologramRenderer.editor();
                    var browse = HologramRenderer.browse();
                    int[] aBounds = expected(a, "groupA"), bBounds = expected(b, "groupB");
                    if (Arrays.equals(aBounds, bBounds))
                        throw new AssertionError("two native snapshots should have distinct extents");

                    project(editor, a);
                    project(browse, a);
                    HologramClient.clearProjection();
                    HologramClient.clearProjection(); // second cleanup after the original exception also used to crash
                    verify(a, "groupA", aBounds);

                    project(editor, b);
                    project(browse, a);
                    project(browse, b); // replace A with B in the same live renderer
                    HologramClient.clearProjection();
                    HologramClient.clearProjection();
                    verify(a, "groupA", aBounds);
                    verify(b, "groupB", bBounds);
                    if (field("structureBoundsState").get(editor) != null
                        || field("structureBoundsState").get(browse) != null
                        || field("cachedStructureBounds").get(editor) == b.nativeViewport.structureBounds
                        || field("cachedStructureBounds").get(browse) == b.nativeViewport.structureBounds)
                        throw new AssertionError("released renderer retained its previous blueprint");
                    System.out.println(
                        "GTMB_NATIVE_RENDERER_RELEASE_PASS 2 snapshots, editor+browse, replacement, repeated world cleanup");
                } finally {
                    second.closeNative();
                }
            } finally {
                first.closeNative();
            }
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }

    private static HologramLocalBlueprintDocument open(Path root, String group, int x) throws Exception {
        var lib = new HologramClientLibrary(root);
        Files.createDirectories(lib.directory());
        String cell = "{\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"count\":1,"
            + "\"y\":0,\"z\":0,\"structureIds\":[\""
            + group
            + "\"],\"x\":";
        String json = "{\"schema\":2,\"blueprint\":{\"id\":\"" + ID
            + "\",\"name\":\"Preview fixture\","
            + "\"cells\":["
            + cell
            + "-1},"
            + cell
            + x
            + "}]}}";
        HologramBlueprintArtifact.writePair(
            lib.directory()
                .resolve(ID + ".json"),
            json.getBytes(StandardCharsets.UTF_8),
            NativeBlueprintCodecRegression.png());
        var prepared = lib.prepareNativeUpgrade(ID, () -> true);
        HologramNativeBlueprintStorage.activate(lib.directory(), prepared, prepared.revision, () -> true);
        return HologramLocalBlueprintWorkspace
            .openNativeEditorSource(new HologramClientLibrary(root).readPrivateEditorSource(ID), ID);
    }

    private static HologramState view(HologramLocalBlueprintDocument doc) throws Exception {
        return HologramLocalBlueprintWorkspace.nativeView(
            doc.nativeEditorMetadata(),
            doc.nativeDraft(),
            new HologramNativeViewport.Index(
                doc.nativeDraft()
                    .space(),
                () -> true),
            null);
    }

    private static int[] expected(HologramState state, String group) {
        int[] extents = state.nativeViewport.structureBounds.get(group);
        if (extents == null || extents.length != 6) throw new AssertionError("fixture missing native group " + group);
        return extents.clone();
    }

    private static void verify(HologramState state, String group, int[] extents) {
        if (state.nativeViewport.structureBounds.size() != 1 || !Arrays.equals(extents, expected(state, group)))
            throw new AssertionError("renderer altered native extents " + group);
    }

    private static Field field(String name) throws Exception {
        Field field = MiaoVisonLiteRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    // Reach the exact CPU stage used by the viewport, without a test-only setter or a GL context/framebuffer.
    private static void project(MiaoVisonLiteRenderer renderer, HologramState state) throws Exception {
        field("projectedFrame").set(renderer, new HologramEditorCameraFrame(0, 0, 128, 128, .3, -.5, 1, 0, 0, 0, 20));
        Method build = MiaoVisonLiteRenderer.class.getDeclaredMethod("buildStructureBoxes", HologramState.class);
        build.setAccessible(true);
        build.invoke(renderer, state);
        if (!((Map<?, ?>) field("structureBoxes").get(renderer)).keySet()
            .equals(state.nativeViewport.structureBounds.keySet())
            || field("cachedStructureBounds").get(renderer) != state.nativeViewport.structureBounds)
            throw new AssertionError("fixture did not exercise the renderer's real native snapshot bounds");
    }
}
