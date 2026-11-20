// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;

/** Runs the actual private catalog and editor through native controls in a disconnected client. */
final class HologramPrivateBlueprintProbe implements AutoCloseable {

    private static final String ID = "local_10000000-0000-0000-0000-000000000001";
    private static final HologramPrivateBlueprint.Position FIRST = new HologramPrivateBlueprint.Position(0, 0, 0);

    private final Map<String, Object> receipt;
    private final Path isolated;
    private final GuiScreen parent;
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    private HologramPrivateBlueprintScreen screen;
    private Path member;
    private HologramBlueprintArtifact.Artifact pair;
    private int step;

    HologramPrivateBlueprintProbe(GuiScreen parent, Map<String, Object> receipt) throws IOException {
        this.parent = parent;
        this.receipt = receipt;
        isolated = Files.createTempDirectory("gtmb-private-ui-");
    }

    void open() throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        require(mc.theWorld == null && mc.getNetHandler() == null, "private GUI witness needs a disconnected client");
        receipt.put("passed", false);
        receipt.put("connected", false);
        receipt.put("input", "MUI2 native widget dispatch");

        HologramClientLibrary store = new HologramClientLibrary(isolated);
        member = Files.createDirectories(store.directory())
            .resolve(ID + ".json");
        String json = "{\"schema\":2,\"blueprint\":{\"name\":\"Private witness\",\"schema\":2,\"cells\":["
            + "{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\"},"
            + "{\"x\":1,\"y\":0,\"z\":0,\"block\":\"minecraft:planks\"},"
            + "{\"x\":0,\"y\":1,\"z\":0,\"block\":\"minecraft:glass\"}]}}";
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        require(
            ImageIO.write(
                new BufferedImage(
                    HologramBlueprintArtifact.WIDTH,
                    HologramBlueprintArtifact.HEIGHT,
                    BufferedImage.TYPE_INT_RGB),
                "png",
                png),
            "fixture PNG encoder unavailable");
        pair = HologramBlueprintArtifact.prepareSave(json.getBytes(StandardCharsets.UTF_8), png.toByteArray());
        Files.write(member, pair.json);
        Files.write(HologramBlueprintArtifact.previewPath(member), pair.png);

        screen = new HologramPrivateBlueprintScreen(HologramLibraryClient.INSTANCE, store, parent, null, "");
        screen.openFrom(parent);
        require(owns(mc.currentScreen), "normal openFrom did not retain the private library");
        receipt.put("isolatedFixture", true);
    }

    boolean owns(GuiScreen owner) {
        return screen != null && owner == screen;
    }

    void check() {
        require(owns(Minecraft.getMinecraft().currentScreen), "private GUI ownership changed during the test");
        require(System.nanoTime() < deadline, "private GUI timed out at step " + step);
    }

    boolean frame(Capture capture) throws Exception {
        check();
        receipt.put("step", step);
        if ((boolean) field("busy")) return false;

        HologramPrivateBlueprint document = (HologramPrivateBlueprint) field("document");
        switch (step) {
            case 0 -> {
                List<?> rows = (List<?>) field("rows");
                if (rows.isEmpty()) return false;
                require(
                    rows.size() == 1 && ((HologramClientLibrary.Entry) rows.get(0)).id.equals(ID),
                    "private catalog did not show its isolated pair");
                if (!receipt.containsKey("catalogFrame"))
                    receipt.put("catalogFrame", capture.png(screen, "gtmb-private-catalog.png"));
                if (!nativeClick(1000, "catalogRow")) return false;
            }
            case 1 -> {
                if (!ID.equals(field("selectedId"))) return false;
                if (!nativeClick(1, "edit")) return false;
            }
            case 2 -> {
                if (document == null || !renderedDocument(3)) return false;
                require(
                    !document.dirty()
                        && "minecraft:stone".equals(HologramPrivateBlueprint.text(document.cell(FIRST), "block")),
                    "private editor did not open the selected file with its original source");
                if (!receipt.containsKey("editorFrame"))
                    receipt.put("editorFrame", capture.png(screen, "gtmb-private-editor.png"));
                if (!nativeClick(8, "removeCell")) return false;
            }
            case 3 -> {
                if (document.visibleCells() != 2 || !renderedDocument(2)) return false;
                require(document.dirty() && document.cell(FIRST) == null, "native removal did not edit the document");
                receipt.put("cellsAfterRemoval", document.visibleCells());
                if (!nativeClick(9, "undo")) return false;
            }
            case 4 -> {
                if (document.visibleCells() != 3 || !renderedDocument(3)) return false;
                require(
                    !document.dirty()
                        && "minecraft:stone".equals(HologramPrivateBlueprint.text(document.cell(FIRST), "block")),
                    "native undo did not restore the selected cell and clean revision");
                HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.readPair(member);
                require(
                    Arrays.equals(pair.json, actual.json) && Arrays.equals(pair.png, actual.png),
                    "unsaved edits modified the isolated source pair");
                receipt.put("cellsAfterUndo", document.visibleCells());
                receipt.put("sourcePairUnchanged", true);
                receipt.put("backend", "MiaoVisonLite");
                receipt.put("gpuDrawsAfterUndo", HologramRenderer.litePreviewDraws());
                receipt.put("passed", true);
                return true;
            }
            default -> throw new IllegalStateException("Unknown private GUI step " + step);
        }
        step++;
        return false;
    }

    private Object field(String field) throws ReflectiveOperationException {
        return HologramTestLabUiDiagnostics.readDiagnosticField(screen, field);
    }

    private boolean renderedDocument(int count) throws ReflectiveOperationException {
        HologramState view = (HologramState) field("view");
        return view != null && (boolean) field("previewCurrent")
            && field("preparation") == null
            && view.cells.size() == count
            && HologramRenderer.previewDisplayState() == view
            && HologramRenderer.litePreviewActive()
            && HologramRenderer.litePreviewDraws() > 0
            && HologramRenderer.previewMeshReady(view)
            && HologramRenderer.previewMeshVisible(view);
    }

    private boolean nativeClick(int id, String action) {
        String name = "projector_control_" + id;
        ModularScreen gui = screen.getScreen();
        List<IWidget> widgets = new ArrayList<>();
        widgets.add(gui.getMainPanel());
        for (int i = 0; i < widgets.size(); i++) {
            IWidget widget = widgets.get(i);
            if (!name.equals(widget.getName())) {
                widgets.addAll(widget.getChildren());
                continue;
            }
            if (!widget.isValid() || !widget.isEnabled() || widget.getArea().width <= 0 || widget.getArea().height <= 0)
                return false;
            var transform = LocatedWidget.of(widget)
                .getTransformationMatrix();
            int x = transform.transformX(widget.getArea().width / 2f, widget.getArea().height / 2f);
            int y = transform.transformY(widget.getArea().width / 2f, widget.getArea().height / 2f);
            gui.getContext()
                .updateState(x, y, 0);
            gui.onFrameUpdate();
            boolean hovered = false;
            for (LocatedWidget hit : widget.getPanel()
                .getHovering()) hovered |= hit.getElement() == widget;
            if (!hovered) return false;
            try {
                require(
                    !gui.onMouseInputPre(0, true) && gui.onMousePressed(0),
                    "private native control unhandled: " + name);
            } finally {
                if (!gui.onMouseInputPre(0, false)) gui.onMouseRelease(0);
            }
            receipt.put(action, new int[] { x, y });
            return true;
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        Minecraft mc = Minecraft.getMinecraft();
        if (owns(mc.currentScreen)) mc.displayGuiScreen(parent);
        try (Stream<Path> paths = Files.walk(isolated)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(path);
        }
        receipt.put("fixtureRemoved", true);
    }

    interface Capture {

        Map<String, Object> png(GuiScreen owner, String name) throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
