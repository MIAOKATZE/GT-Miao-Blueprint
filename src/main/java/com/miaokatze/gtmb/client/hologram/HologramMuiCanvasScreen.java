package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

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
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** Native control layer above the projector's specialized scene renderer. */
abstract class HologramMuiCanvasScreen extends HologramMuiScreen {

    private final ControlLayer controls = new ControlLayer().sizeRel(1f)
        .background(IDrawable.NONE);
    private final Map<Integer, NativeButton> buttons = new HashMap<>();
    private List<HologramMuiTextField> fields = new ArrayList<>();
    private Object activeFieldGraph;
    private final Map<Object, List<HologramMuiTextField>> fieldGraphs = new IdentityHashMap<>();
    private ParentWidget<?> fieldMount;
    private List<HologramMuiTextField> suspendedFields;
    private HologramMuiTextField suspendedFocus;
    private int canvasX, canvasY;
    private float canvasScale = 1;
    private long nativeFrame;
    private CanvasTransform fieldTransform = CanvasTransform.identity();

    HologramMuiCanvasScreen(String name) {
        super(name, 1, 1);
        panel().sizeRel(1f)
            .pos(0, 0)
            .background(IDrawable.NONE);
    }

    @Override
    protected final void buildPanel() {
        if (buildNativePanel()) return;
        panel().child(
            new SceneWidget().sizeRel(1f)
                .background(IDrawable.NONE));
        panel().child(controls);
    }

    protected boolean buildNativePanel() {
        return false;
    }

    protected abstract void renderScene(int mouseX, int mouseY, float ticks);

    protected abstract void activateNative(int id);

    protected void sceneScroll() {}

    protected String nativeTooltip(int id, boolean enabled) {
        return "";
    }

    protected String nativeIcon(int id, String label) {
        return null;
    }

    protected boolean nativeIconOnly(int id) {
        return false;
    }

    final void canvasTransform(int x, int y, float scale) {
        canvasX = x;
        canvasY = y;
        canvasScale = scale;
    }

    final void nativeButton(int id, int x, int y, int width, int height, String text, boolean enabled, int accent) {
        nativeButton(id, x, y, width, height, text, enabled, accent, false);
    }

    final void nativeButton(int id, int x, int y, int width, int height, String text, boolean enabled, int accent,
        boolean selected) {
        NativeButton entry = buttons.get(id);
        if (entry == null) {
            entry = new NativeButton(id);
            buttons.put(id, entry);
            controls.child(entry.widget);
        }
        entry.frame = nativeFrame;
        entry.text = text;
        entry.enabled = enabled;
        entry.selected = selected;
        entry.accent = accent;
        entry.icon = nativeIcon(id, text);
        entry.iconOnly = nativeIconOnly(id);
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
        // Native editor graphs keep unclaimed adapters pending until their unique page/dialog is built.
        if (!(this instanceof HologramScreen)) fields.removeIf(previous -> {
            if (!previous.sameBounds(field) || previous.nativeOwned()) return false;
            if (previous.widget()
                .isValid()) controls.remove(previous.widget());
            return true;
        });
        fields.add(field);
        if (!(this instanceof HologramScreen)) controls.child(field.widget());
    }

    final com.cleanroommc.modularui.widgets.textfield.TextFieldWidget adoptNativeField(HologramMuiTextField field) {
        return field.claimNative();
    }

    final FieldGraph fieldGraph(Object graph) {
        return new FieldGraph(graph);
    }

    @Override
    protected Runnable guardDeferredAction(Runnable action) {
        return activeFieldGraph instanceof HologramUiModules
            ? ((HologramUiModules) activeFieldGraph).session.guardTutorialAction(action)
            : action;
    }

    final void releaseFieldGraph(Object graph) {
        List<HologramMuiTextField> released = fieldGraphs.remove(graph);
        if (fields == released) fields = new ArrayList<>();
    }

    final class FieldGraph implements AutoCloseable {

        private final List<HologramMuiTextField> previous;
        private final Object previousGraph;

        FieldGraph(Object graph) {
            previous = fields;
            previousGraph = activeFieldGraph;
            activeFieldGraph = graph;
            fields = fieldGraphs.computeIfAbsent(graph, ignored -> new ArrayList<>());
        }

        @Override
        public void close() {
            fields = previous;
            activeFieldGraph = previousGraph;
        }
    }

    final LegacyFieldMount legacyFieldMount(ParentWidget<?> mount) {
        return new LegacyFieldMount(mount);
    }

    final class LegacyFieldMount implements AutoCloseable {

        private final ParentWidget<?> previous;
        private final ParentWidget<?> mount;
        private final long frame;

        LegacyFieldMount(ParentWidget<?> mount) {
            previous = fieldMount;
            this.mount = mount;
            fieldMount = mount;
            frame = ++nativeFrame;
        }

        @Override
        public void close() {
            for (HologramMuiTextField field : fields) if (field.legacyParent() == mount) field.finishFrame(frame);
            fieldMount = previous;
        }
    }

    final void mountLegacyField(HologramMuiTextField field, ParentWidget<?> mount) {
        field.claimLegacy(mount);
    }

    final void nativeVoucherRibbon(int id, boolean visible) {
        NativeButton entry = buttons.get(id);
        if (entry != null) entry.voucherRibbon = visible;
    }

    final void nativeSceneTooltip(int id, boolean owned) {
        NativeButton entry = buttons.get(id);
        if (entry != null) entry.sceneTooltip = owned;
    }

    final void resetNativeFields() {
        for (HologramMuiTextField field : fields) controls.remove(field.widget());
        fields.clear();
    }

    /** The tutorial has a distinct widget namespace; live drafts and cursor ownership remain in their objects. */
    final void beginTutorialFields() {
        if (suspendedFields != null) return;
        suspendedFields = fields;
        fields = new ArrayList<>();
        for (HologramMuiTextField field : suspendedFields) {
            if (field.isFocused()) suspendedFocus = field;
            field.setFocused(false);
            field.widget()
                .setEnabled(false);
        }
    }

    final void endTutorialFields() {
        if (suspendedFields == null) return;
        List<HologramMuiTextField> tutorialFields = fields;
        HologramMuiTextField restoreFocus = suspendedFocus;
        // Restore namespace ownership before disposal callbacks can throw or reenter the screen.
        fields = suspendedFields;
        suspendedFields = null;
        suspendedFocus = null;
        try {
            for (HologramMuiTextField field : tutorialFields) {
                try {
                    field.setFocused(false);
                    field.widget()
                        .setEnabled(false);
                } catch (RuntimeException failure) {
                    com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Could not deactivate a tutorial field", failure);
                } finally {
                    try {
                        controls.remove(field.widget());
                    } catch (RuntimeException failure) {
                        com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Could not dispose a tutorial field", failure);
                    }
                }
            }
        } finally {
            if (restoreFocus != null) restoreFocus.setFocused(true);
        }
    }

    final void paintTutorialFields() {
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(canvasX, canvasY, 0);
            GL11.glScalef(canvasScale, canvasScale, 1);
            for (HologramMuiTextField field : fields) field.drawPassive(nativeFrame);
        } finally {
            GL11.glPopMatrix();
        }
    }

    final void clearNativeFieldVisibility() {
        for (HologramMuiTextField field : fields) if (!field.nativeOwned()) field.hideForOverlay();
    }

    final void showField(HologramMuiTextField field) {
        if (field.nativeOwned()) return;
        if (fieldMount != null) mountLegacyField(field, fieldMount);
        field.show(nativeFrame, fieldTransform);
    }

    final void nativeFieldTransform(CanvasTransform transform) {
        fieldTransform = transform;
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
        private String icon;
        private boolean iconOnly;
        private boolean enabled;
        private boolean selected;
        private boolean voucherRibbon, sceneTooltip;
        private int accent, x = -1, y = -1, w, h;
        private final ButtonWidget<?> widget;

        NativeButton(int id) {
            widget = new ButtonWidget<>().name("projector_control_" + id)
                .background(HologramMuiTheme.guidanceBackground(false, () -> accent, () -> enabled, () -> selected))
                .hoverBackground(HologramMuiTheme.guidanceBackground(true, () -> accent, () -> enabled, () -> selected))
                .overlay(
                    IKey.dynamic(() -> iconOnly ? "" : text)
                        .color(() -> HologramUiAccent.textColor(accent, enabled))
                        .shadow(false),
                    (context, x, y, width, height, theme) -> {
                        if (icon != null && iconOnly) {
                            int size = height >= 24 && width >= 24 ? 24 : 12;
                            HologramEditorIcons.draw(
                                icon,
                                Math.round(x + (width - size) / 2),
                                Math.round(y + (height - size) / 2),
                                size,
                                true,
                                enabled,
                                selected,
                                hovering());
                        }
                        if (voucherRibbon) HologramVoucherRibbon.draw(x, y, width, height);
                    })
                .tooltipBuilder(tooltip -> {
                    if (sceneTooltip) return;
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

        private boolean hovering() {
            return widget.isHovering();
        }
    }
}
