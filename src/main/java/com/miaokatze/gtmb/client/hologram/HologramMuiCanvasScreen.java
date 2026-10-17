package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;

/** Native control layer above the projector's specialized scene renderer. */
abstract class HologramMuiCanvasScreen extends HologramMuiScreen {

    private final ControlLayer controls = new ControlLayer().sizeRel(1f)
        .background(IDrawable.NONE);
    private final Map<Integer, NativeButton> buttons = new HashMap<>();
    private final List<HologramMuiTextField> fields = new ArrayList<>();
    private int canvasX, canvasY;
    private float canvasScale = 1;
    private long nativeFrame;

    HologramMuiCanvasScreen(String name) {
        super(name, 1, 1);
        panel().sizeRel(1f)
            .pos(0, 0)
            .background(IDrawable.NONE);
    }

    @Override
    protected final void buildPanel() {
        panel().child(
            new SceneWidget().sizeRel(1f)
                .background(IDrawable.NONE));
        panel().child(controls);
    }

    protected abstract void renderScene(int mouseX, int mouseY, float ticks);

    protected abstract void activateNative(int id);

    protected void sceneScroll() {}

    protected String nativeTooltip(int id, boolean enabled) {
        return "";
    }

    final void canvasTransform(int x, int y, float scale) {
        canvasX = x;
        canvasY = y;
        canvasScale = scale;
    }

    final void nativeButton(int id, int x, int y, int width, int height, String text, boolean enabled, int accent) {
        NativeButton entry = buttons.get(id);
        if (entry == null) {
            entry = new NativeButton(id);
            buttons.put(id, entry);
            controls.child(entry.widget);
        }
        entry.frame = nativeFrame;
        entry.text = text;
        entry.enabled = enabled;
        entry.accent = accent;
        int px = x, py = y;
        int w = Math.max(1, width), h = Math.max(1, height);
        if (entry.x != px || entry.y != py || entry.w != w || entry.h != h) {
            entry.x = px;
            entry.y = py;
            entry.w = w;
            entry.h = h;
            entry.widget.pos(px, py)
                .size(w, h)
                .scheduleResize();
        }
        entry.widget.setEnabled(true);
    }

    final void registerField(HologramMuiTextField field) {
        fields.removeIf(previous -> {
            if (!previous.sameBounds(field)) return false;
            controls.remove(previous.widget());
            return true;
        });
        fields.add(field);
        controls.child(field.widget());
    }

    final void resetNativeFields() {
        for (HologramMuiTextField field : fields) controls.remove(field.widget());
        fields.clear();
    }

    final void clearNativeFieldVisibility() {
        for (HologramMuiTextField field : fields) field.hideForOverlay();
    }

    final void showField(HologramMuiTextField field) {
        field.show(nativeFrame, canvasX, canvasY, canvasScale);
    }

    private final class ControlLayer extends ParentWidget<ControlLayer> {

        @Override
        public boolean canHover() {
            return false;
        }

        @Override
        public boolean canClickThrough() {
            return true;
        }

        @Override
        public void transform(IViewportStack stack) {
            stack.translate(canvasX, canvasY);
            stack.scale(canvasScale, canvasScale);
        }
    }

    private final class SceneWidget extends Widget<SceneWidget> implements Interactable {

        @Override
        public boolean canHover() {
            return true;
        }

        @Override
        public boolean canClickThrough() {
            return false;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            nativeFrame++;
            renderScene(context.getMouseX(), context.getMouseY(), context.getPartialTicks());
            for (NativeButton button : buttons.values()) button.widget.setEnabled(button.frame == nativeFrame);
            for (HologramMuiTextField field : fields) field.finishFrame(nativeFrame);
        }

        @Override
        public Result onMousePressed(int button) {
            HologramMuiCanvasScreen.this.mouseClicked(getContext().getMouseX(), getContext().getMouseY(), button);
            return Result.SUCCESS;
        }

        @Override
        public void onMouseDrag(int button, long elapsed) {
            HologramMuiCanvasScreen.this
                .mouseClickMove(getContext().getMouseX(), getContext().getMouseY(), button, elapsed);
        }

        @Override
        public boolean onMouseRelease(int button) {
            HologramMuiCanvasScreen.this.mouseMovedOrUp(getContext().getMouseX(), getContext().getMouseY(), button);
            return true;
        }

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            sceneScroll();
            return true;
        }
    }

    private final class NativeButton {

        private long frame;
        private String text = "";
        private boolean enabled;
        private int accent, x = -1, y = -1, w, h;
        private final ButtonWidget<?> widget;

        NativeButton(int id) {
            widget = new ButtonWidget<>().name("projector_control_" + id)
                .background(HologramMuiTheme.guidanceBackground(false, () -> accent, () -> enabled))
                .hoverBackground(HologramMuiTheme.guidanceBackground(true, () -> accent, () -> enabled))
                .overlay(
                    IKey.dynamic(() -> text)
                        .color(() -> HologramUiAccent.textColor(accent, enabled))
                        .shadow(false))
                .tooltipBuilder(tooltip -> {
                    String help = nativeTooltip(id, enabled);
                    tooltip.addLine(help == null || help.isEmpty() ? text : help);
                })
                .tooltipAutoUpdate(true)
                .onMousePressed(button -> {
                    if (button != 0) return false;
                    if (enabled) activateNative(id);
                    return true;
                });
        }
    }
}
