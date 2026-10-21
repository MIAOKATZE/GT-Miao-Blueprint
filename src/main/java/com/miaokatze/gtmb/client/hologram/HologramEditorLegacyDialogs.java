package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.ScrollWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.scroll.HorizontalScrollData;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/**
 * Advanced domain bodies retained inside independent native modal panels during the layout migration.
 * Each panel owns its buttons and draft fields. The main scene never paints these bodies.
 */
final class HologramEditorLegacyDialogs {

    private final HologramUiModules ui;
    private final List<Domain> domains = new ArrayList<>();
    private boolean attached;

    HologramEditorLegacyDialogs(HologramUiModules ui) {
        this.ui = ui;
        domain(
            "position",
            "添加位置",
            157,
            59,
            248,
            198,
            ui.position::positionDrawer,
            ui.position::drawPosition,
            (c, key) -> {
                if (key == Keyboard.KEY_ESCAPE) ui.router.activate(77);
                else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) ui.router.activate(76);
            },
            null);
        domain(
            "prompt",
            "提示词与施工排除",
            50,
            77,
            378,
            191,
            ui.annotations::promptDrawer,
            ui.annotations::drawPromptEditor,
            ui.annotations::consumeAnnotationModalKey,
            null);
        domain(
            "versions",
            "来源与版本",
            50,
            58,
            378,
            221,
            ui.annotations::versionsDrawer,
            ui.annotations::drawVersions,
            ui.annotations::consumeAnnotationModalKey,
            ui.annotations::consumeVersionsWheel);
        domain(
            "nbt",
            "NBT 条目",
            50,
            58,
            498,
            246,
            ui.nbt::nbtDrawer,
            ui.nbt::drawNbtEditor,
            ui.nbt::consumeEditorKey,
            ui.nbt::consumeEditorWheel);
        domain(
            "ae",
            "蓝图综合告知",
            30,
            42,
            620,
            260,
            () -> ui.ae.aeReview() != null,
            ui.ae::drawAeReview,
            ui.ae::consumeReviewKey,
            ui.ae::consumeReviewWheel);
        domain(
            "ae-navigation",
            "定位相关结构",
            90,
            96,
            500,
            152,
            () -> ui.ae.aeReviewNavigation() != null,
            ui.ae::drawAeReviewNavigation,
            ui.ae::consumeNavigationKey,
            null);
        domain(
            "network",
            "量子网络类群",
            35,
            50,
            610,
            249,
            ui.bindings::networkDrawer,
            ui.bindings::drawNetworkBindings,
            ui.bindings::consumeBindingsKey,
            null);
        domain(
            "identity",
            "校验与去标识",
            40,
            47,
            610,
            263,
            ui.identity::deidentificationDrawer,
            ui.identity::drawDeidentification,
            ui.identity::consumeIdentityKey,
            null);
        domain(
            "processing",
            "蓝图处理",
            28,
            48,
            622,
            256,
            () -> ui.processing.processingFlow() != null,
            ui.processing::drawProcessingFlow,
            ui.processing::consumeProcessingKey,
            ui.processing::consumeProcessingWheel);
        domain(
            "preparing",
            "蓝图准备",
            30,
            62,
            620,
            220,
            () -> ui.session.areaImporting() || ui.session.state().data.getBoolean("snapshotPending")
                || ui.session.state().data.getBoolean("placementValidating"),
            ui.router::drawImportBusy,
            (c, key) -> { if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) ui.router.activate(193); },
            null);
    }

    private void domain(String id, String title, int x, int y, int width, int height, BooleanSupplier visible,
        BiConsumer<Integer, Integer> paint, BiConsumer<Character, Integer> key, IntPredicate wheel) {
        domains.add(new Domain(id, title, x, y, width, height, visible, paint, key, wheel));
    }

    void attach(ModularPanel host) {
        if (attached) return;
        attached = true;
        for (Domain domain : domains) {
            domain.panel = new LegacyDialog(domain);
            domain.handler = IPanelHandler.simple(host, (parent, player) -> domain.panel, true);
            ui.shell.registerTarget(domain.id, domain.panel);
        }
    }

    /** Called by the native shell after its domain tick, never from a legacy scene render. */
    void refresh() {
        if (!attached) return;
        for (Domain domain : domains) {
            boolean visible = !ui.tutorial.active() && domain.visible.getAsBoolean();
            domain.panel.fit();
            if (visible && !domain.handler.isPanelOpen()) domain.handler.openPanel();
            else if (!visible && domain.handler.isPanelOpen()) domain.panel.closeIfOpen();
        }
    }

    private final class Domain {

        final String id, title;
        final int x, y, width, height;
        final BooleanSupplier visible;
        final BiConsumer<Integer, Integer> paint;
        final BiConsumer<Character, Integer> key;
        final IntPredicate wheel;
        IPanelHandler handler;
        LegacyDialog panel;

        Domain(String id, String title, int x, int y, int width, int height, BooleanSupplier visible,
            BiConsumer<Integer, Integer> paint, BiConsumer<Character, Integer> key, IntPredicate wheel) {
            this.id = id;
            this.title = title;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.visible = visible;
            this.paint = paint;
            this.key = key;
            this.wheel = wheel;
        }
    }

    private final class LegacyDialog extends Dialog<Void> {

        private final Domain domain;
        private final ParentWidget<?> content = new ParentWidget<>();
        private final ParentWidget<?> buttons = new NativeLayer();
        private final ParentWidget<?> fields = new NativeLayer();
        private final Widget<?> heading;
        private final Map<Integer, NativeControl> controls = new LinkedHashMap<>();
        private final LegacyViewport viewport;
        private int fittedWidth = -1, fittedHeight = -1;

        LegacyDialog(Domain domain) {
            super(ui.panelName("gtmb_editor_advanced_" + domain.id));
            this.domain = domain;
            setDisablePanelsBelow(true);
            setCloseOnOutOfBoundsClick(false);
            setDraggable(false);
            background(HologramEditorTheme.PANEL);
            heading = HologramMuiTheme.label(() -> domain.title, 10, 8, domain.width, 16, HologramEditorTheme.TITLE);
            child(heading);
            content.size(domain.width, domain.height)
                .background(IDrawable.NONE);
            content.child(
                new LegacyPaint().size(domain.width, domain.height)
                    .background(IDrawable.NONE));
            content.child(
                buttons.size(domain.width, domain.height)
                    .background(IDrawable.NONE));
            content.child(
                fields.size(domain.width, domain.height)
                    .background(IDrawable.NONE));
            viewport = new LegacyViewport();
            viewport.pos(8, 30)
                .child(content)
                .background(IDrawable.NONE);
            child(viewport);
            fit();
        }

        void fit() {
            int width = Math.max(32, Math.min(domain.width + 16, ui.host.screenWidth() - 12));
            int height = Math.max(48, Math.min(domain.height + 38, ui.host.screenHeight() - 12));
            if (width == fittedWidth && height == fittedHeight) return;
            fittedWidth = width;
            fittedHeight = height;
            size(width, height).center();
            heading.size(Math.max(1, width - 20), 16);
            viewport.size(Math.max(1, width - 16), Math.max(1, height - 38));
            scheduleResize();
        }

        private final class NativeLayer extends ParentWidget<NativeLayer> {

            @Override
            public boolean canHover() {
                return false;
            }

            @Override
            public boolean canClickThrough() {
                return true;
            }
        }

        @Override
        public boolean onKeyPressed(char character, int key) {
            // Enter/Escape keep the domain's existing intent and RPC gates; ordinary text stays native.
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                domain.key.accept(character, key);
                return true;
            }
            if (super.onKeyPressed(character, key)) return true;
            if (key == Keyboard.KEY_E) domain.key.accept(character, key);
            return true;
        }

        private final class LegacyViewport extends ScrollWidget<LegacyViewport> {

            private final HorizontalScrollData horizontal = new HorizontalScrollData();
            private final VerticalScrollData vertical = new VerticalScrollData();

            LegacyViewport() {
                getScrollArea().setScrollDataX(horizontal);
                getScrollArea().setScrollDataY(vertical);
                showScrollShadows(false);
            }

            private boolean overflow() {
                return domain.width > Math.max(1, fittedWidth - 16) || domain.height > Math.max(1, fittedHeight - 38);
            }

            @Override
            public void beforeResize(boolean onOpen) {
                horizontal.setScrollSize(overflow() ? domain.width : 0);
                vertical.setScrollSize(overflow() ? domain.height : 0);
                super.beforeResize(onOpen);
            }

            @Override
            public boolean onMouseScroll(UpOrDown direction, int amount) {
                boolean page = !overflow() || Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                    || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
                if (page && domain.wheel != null && domain.wheel.test(direction.isUp() ? 1 : -1)) return true;
                return super.onMouseScroll(direction, amount);
            }
        }

        private final class LegacyPaint extends Widget<LegacyPaint> {

            @Override
            public boolean canHover() {
                return false;
            }

            @Override
            public boolean canClickThrough() {
                return true;
            }

            @Override
            public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
                HologramMuiCanvasScreen owner = ui.host.parent();
                List<HologramUiCanvas.Control> previous = new ArrayList<>(ui.canvas.controls());
                List<String> previousTooltip = ui.canvas.tooltip();
                CanvasTransform space = new CanvasTransform(domain.x, domain.y, 0, 0, 1);
                int mx = context.getMouseX() + domain.x, my = context.getMouseY() + domain.y;
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                try (var graph = owner.fieldGraph(ui);
                    var mount = owner.legacyFieldMount(fields);
                    HologramUiCanvas.Space ignored = ui.canvas.space(space)) {
                    ui.canvas.controls()
                        .clear();
                    ui.canvas.tooltip(null);
                    for (NativeControl control : controls.values()) control.visible = false;
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    domain.paint.accept(mx, my);
                    for (HologramUiCanvas.Control source : ui.canvas.controls()) {
                        NativeControl control = controls.computeIfAbsent(source.id, NativeControl::new);
                        control.update(source);
                        if (mx - domain.x >= source.x && mx - domain.x < source.x + source.w
                            && my - domain.y >= source.y
                            && my - domain.y < source.y + source.h
                            && ui.canvas.tooltip() != null) control.help = String.join("\n", ui.canvas.tooltip());
                    }
                    for (NativeControl control : controls.values()) control.widget.setEnabled(control.visible);
                } finally {
                    // Restore the descriptor buffer without invoking its legacy clear-field side effect.
                    ui.canvas.controls()
                        .subList(
                            0,
                            ui.canvas.controls()
                                .size())
                        .clear();
                    ui.canvas.controls()
                        .addAll(previous);
                    ui.canvas.tooltip(previousTooltip);
                    GL11.glPopAttrib();
                }
            }
        }

        private final class NativeControl {

            final ButtonWidget<?> widget;
            final IDrawable normalBackground, hoverBackground;
            String label = "", help = "";
            boolean enabled, visible, selected;
            int accent;
            int x = Integer.MIN_VALUE, y, width, height;

            NativeControl(int id) {
                widget = ui.shell.nativeAction(id, () -> label, () -> enabled && visible, () -> help);
                normalBackground = widget.getBackground();
                hoverBackground = widget.getHoverBackground();
                // Item slots are painted by their domain body. Their native hit target must not cover the item.
                widget.background((context, x, y, w, h, theme) -> paintButton(context, x, y, w, h, theme, false))
                    .hoverBackground((context, x, y, w, h, theme) -> paintButton(context, x, y, w, h, theme, true));
                buttons.child(widget);
            }

            private void paintButton(GuiContext context, int x, int y, int w, int h, WidgetTheme theme, boolean hover) {
                if (!label.isEmpty()) {
                    IDrawable background = hover ? hoverBackground : normalBackground;
                    if (background != null) background.draw(context, x, y, w, h, theme);
                    HologramEditorTheme.drawGuidanceFrame(x, y, w, h, accent, enabled);
                } else if (hover || selected) {
                    int color = enabled ? 0xffd8b779 : 0xff786956;
                    GuiDraw.drawRect(x, y, w, 1, color);
                    GuiDraw.drawRect(x, y + h - 1, w, 1, color);
                    GuiDraw.drawRect(x, y, 1, h, color);
                    GuiDraw.drawRect(x + w - 1, y, 1, h, color);
                }
            }

            void update(HologramUiCanvas.Control source) {
                visible = true;
                enabled = source.enabled;
                label = source.label;
                help = label;
                accent = source.accent;
                selected = source.selected;
                if (x == source.x && y == source.y && width == source.w && height == source.h) return;
                x = source.x;
                y = source.y;
                width = source.w;
                height = source.h;
                widget.pos(x, y)
                    .size(width, height)
                    .scheduleResize();
            }
        }
    }
}
