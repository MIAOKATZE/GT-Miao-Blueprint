package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.screen.viewport.TransformationMatrix;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;

/** MUI owns the viewport area and event routing; the renderer and its rays both consume widget-local pixels. */
final class HologramEditorSceneWidget extends Widget<HologramEditorSceneWidget> implements Interactable {

    private final HologramUiModules ui;
    private boolean presented;
    private Object presentedIdentity;
    private ViewportCoordinates presentedArea;

    HologramEditorSceneWidget(HologramUiModules ui) {
        this.ui = ui;
        tooltipBuilder(tooltip -> {
            HologramEditorAnnotationOverlay annotations = ui.shell.annotations();
            if (annotations != null) for (String line : annotations.tooltipLines()) tooltip.addLine(line);
        }).tooltipAutoUpdate(true);
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public boolean canClickThrough() {
        return false;
    }

    private ViewportCoordinates guiBounds() {
        TransformationMatrix matrix = LocatedWidget.of(this)
            .getTransformationMatrix();
        double x = matrix.transformX(0, 0), y = matrix.transformY(0, 0);
        return new ViewportCoordinates(
            x,
            y,
            matrix.transformX(getArea().width, 0) - x,
            matrix.transformY(0, getArea().height) - y);
    }

    private HologramUiCanvas.NativeSceneSpace space() {
        return ui.canvas.nativeSceneSpace(getArea().width, getArea().height, guiBounds());
    }

    void invalidatePresentation() {
        presented = false;
        presentedIdentity = null;
        presentedArea = null;
        ui.preview.releasePointerCapture();
    }

    private Object snapshotIdentity() {
        HologramState state = ui.library.browsePreviewActive() ? HologramLibraryClient.INSTANCE.browsePreviewState()
            : ui.session.state();
        return state == null ? null : state.identity;
    }

    private boolean frameValid() {
        if (!presented || presentedArea == null || presentedIdentity != snapshotIdentity()) return false;
        ViewportCoordinates current = guiBounds();
        return current.x() == presentedArea.x() && current.y() == presentedArea.y()
            && current.width() == presentedArea.width()
            && current.height() == presentedArea.height();
    }

    boolean canDropInventorySample(int screenX, int screenY) {
        int[] point = ui.shell.sceneLocalPoint(screenX, screenY);
        if (point == null || !frameValid()
            || !ui.preview.inventory()
            || !ui.session.editable()
            || ui.preview.pickedTarget() == null) return false;
        try (HologramUiCanvas.NativeSceneSpace ignored = space()) {
            int target = HologramRenderer.pick(point[0], point[1]);
            return target >= 0 && target < ui.session.state().cells.size();
        }
    }

    boolean dropInventorySample(int screenX, int screenY) {
        if (!canDropInventorySample(screenX, screenY)) return false;
        int[] point = ui.shell.sceneLocalPoint(screenX, screenY);
        try (HologramUiCanvas.NativeSceneSpace ignored = space()) {
            ui.preview.pinInventory(HologramRenderer.pick(point[0], point[1]));
        }
        return true;
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
        presented = false;
        if (getArea().width <= 0 || getArea().height <= 0) return;
        try (HologramUiCanvas.NativeSceneSpace ignored = space()) {
            if (!ui.session.isTutorial()) ui.preview.movePreviewCamera();
            ui.canvas.tooltip(null);
            ui.preview.drawNativeScene(context.getMouseX(), context.getMouseY());
            ui.shell.annotations()
                .drawScene(context.getMouseX(), context.getMouseY());
            ui.canvas.incrementRenderGeneration(1, false);
            presentedIdentity = snapshotIdentity();
            presentedArea = guiBounds();
            presented = true;
            ui.shell.presentationDrawn();
        }
    }

    @Override
    public Result onMousePressed(int button) {
        if (!frameValid()) return Result.STOP;
        if (!ui.shell.sceneActive() && !ui.library.browsePreviewActive()) return Result.STOP;
        int x = getContext().getMouseX(), y = getContext().getMouseY();
        if (button == 1 && ui.tools.consumeMouse(getContext().getAbsMouseX(), getContext().getAbsMouseY(), button))
            return Result.SUCCESS;
        try (HologramUiCanvas.NativeSceneSpace ignored = space()) {
            if (button == 0 && ui.shell.annotations()
                .consumeSceneMouse(x, y, button)) return Result.SUCCESS;
            if (button == 0) ui.preview.consumeViewportLeftMouse(x, y, button);
            else if (button == 1) ui.preview.consumeViewportRightMouse(x, y, button);
            else if (button == 2) ui.preview.resetCamera();
        }
        return Result.SUCCESS;
    }

    @Override
    public void onMouseDrag(int button, long elapsed) {
        if (!frameValid()) {
            ui.preview.releasePointerCapture();
            return;
        }
        try (HologramUiCanvas.NativeSceneSpace ignored = space()) {
            ui.preview.mouseClickMove(getContext().getMouseX(), getContext().getMouseY(), button, elapsed);
        }
    }

    @Override
    public boolean onMouseRelease(int button) {
        if (!frameValid()) {
            ui.preview.releasePointerCapture();
            return true;
        }
        try (HologramUiCanvas.NativeSceneSpace ignored = space()) {
            ui.preview.mouseMovedOrUp(getContext().getMouseX(), getContext().getMouseY(), button);
        }
        return true;
    }

    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        if (!frameValid()) return true;
        try (HologramUiCanvas.NativeSceneSpace ignored = space()) {
            ui.preview.scrollNativeScene(direction == UpOrDown.UP ? 1 : -1);
        }
        return true;
    }
}
