package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IFocusedWidget;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.cleanroommc.modularui.screen.viewport.TransformationMatrix;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Expandable;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** One persistent native widget graph per editing session. No domain state is copied into the shell. */
final class HologramEditorShell {

    private static final IDrawable EDITOR_BACKDROP = new Rectangle().color(0xff0c1e28);

    private final HologramUiModules ui;
    private ModularPanel host;
    private Flow root;
    private ParentWidget<?> content;
    private IWidget scene;
    private IWidget sceneOverride;
    private final Map<ModularPanel, Boolean> pausedPanels = new java.util.IdentityHashMap<>();
    private Expandable dock;
    private PagedWidget<?> pages;
    private HologramEditorPropertiesPane properties;
    private HologramEditorLibraryPane library;
    private HologramEditorBlueprintSettingsPane settings;
    private HologramEditorLegacyDialogs legacy;
    private HologramEditorAnnotationOverlay annotations;
    private IFocusedWidget savedFocus;
    private boolean paused;
    private final Map<Integer, List<Entry>> controls = new HashMap<>();
    private final Map<String, List<IWidget>> targets = new HashMap<>();

    HologramEditorShell(HologramUiModules ui) {
        this.ui = ui;
    }

    IWidget build(ModularPanel host) {
        return build(host, null);
    }

    IWidget buildLessonContent(ModularPanel host, ParentWidget<?> body, IWidget preview, IWidget bottom) {
        if (root != null) {
            if (this.host != host) throw new IllegalStateException("A shell belongs to one native panel");
            return root;
        }
        this.host = host;
        content = body;
        scene = preview;
        HologramEditorNativeTheme.apply(
            ui.host.parent()
                .getScreen());
        root = Flow.column()
            .sizeRel(1f)
            .childPadding(0)
            .background(EDITOR_BACKDROP)
            .child(body);
        if (bottom != null) root.child(bottom);
        registerTarget("viewport", preview);
        return root;
    }

    IWidget build(ModularPanel host, IWidget bottomOverride) {
        if (root != null) {
            if (this.host != host) throw new IllegalStateException("A shell belongs to one native panel");
            return root;
        }
        this.host = host;
        HologramEditorNativeTheme.apply(
            ui.host.parent()
                .getScreen());
        properties = new HologramEditorPropertiesPane(ui);
        library = new HologramEditorLibraryPane(ui);
        settings = new HologramEditorBlueprintSettingsPane(ui);
        legacy = new HologramEditorLegacyDialogs(ui);
        annotations = new HologramEditorAnnotationOverlay(ui);
        root = Flow.column()
            .sizeRel(1f)
            .childPadding(0)
            .background(EDITOR_BACKDROP);
        Flow presentation = Flow.column()
            .sizeRel(1f)
            .childPadding(0)
            .background(IDrawable.NONE);
        Flow top = Flow.row()
            .widthRel(1f)
            .height(28)
            .padding(2)
            .background(HologramEditorNativeTheme.PANEL);
        top.child(((Flow) ui.tools.buildModeBar()).width(225));
        top.child(
            new com.cleanroommc.modularui.widget.Widget<>().widthRel(1f)
                .expanded()
                .height(20)
                .background(IDrawable.NONE));
        top.child(ui.guide.buildChip());
        Flow body = Flow.row()
            .sizeRel(1f)
            .expanded()
            .childPadding(4)
            .padding(4);
        scene = sceneOverride == null ? new HologramEditorSceneWidget(ui).sizeRel(1f)
            .expanded() : sceneOverride;
        body.child(ui.tools.buildToolRail());
        Flow sceneColumn = Flow.column()
            .expanded()
            .heightRel(1f)
            .childPadding(3)
            .collapseDisabledChild(true);
        sceneColumn.child(scene);
        sceneColumn.child(ui.tools.buildSelectedBar());
        body.child(sceneColumn);
        dock = new Expandable() {

            @Override
            public com.cleanroommc.modularui.api.widget.Interactable.Result onMousePressed(int button) {
                return com.cleanroommc.modularui.api.widget.Interactable.Result.STOP;
            }
        }.coverChildrenWidth()
            .disableCoverChildrenHeight()
            .heightRel(1f)
            .animationDuration(0)
            .expanded(true);
        Flow rail = Flow.column()
            .width(20)
            .heightRel(1f)
            .background(HologramEditorNativeTheme.PANEL);
        rail.child(
            HologramEditorNativeTheme
                .iconButton("collapse-left", () -> "展开侧栏", () -> setDockExpanded(true), () -> true, () -> false)
                .size(20, 26)
                .name("editor_inspector_expand"));
        Flow expanded = Flow.column()
            .width(236)
            .heightRel(1f)
            .padding(6)
            .childPadding(4)
            .background(HologramEditorNativeTheme.PANEL);
        Flow tabs = Flow.row()
            .widthRel(1f)
            .height(24)
            .childPadding(2);
        PagedWidget.Controller controller = new PagedWidget.Controller();
        pages = new PagedWidget<>().sizeRel(1f)
            .expanded()
            .controller(controller)
            .initialPage(ui.library.libraryDrawer() ? 2 : 0)
            .addPage(properties.build())
            .addPage(settings.build())
            .addPage(library.build())
            .onPageChange(page -> {
                ui.library.libraryDrawer(page == 2);
                if (page == 2) ui.library.blueprintTab(true);
            });
        String[] names = { "\u5c5e\u6027", "\u84dd\u56fe\u8bbe\u7f6e", "\u84dd\u56fe\u5e93" };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            IWidget tab = HologramEditorNativeTheme.pageButton(i, controller, () -> names[index])
                .widthRel(1f)
                .expanded()
                .height(24);
            tabs.child(tab);
            registerControl(HologramEditorControlIds.SHELL + 20 + i, tab, () -> names[index], () -> true);
        }
        tabs.child(
            HologramEditorNativeTheme
                .iconButton("collapse-right", () -> "收起侧栏", () -> setDockExpanded(false), () -> true, () -> false)
                .size(20, 24)
                .name("editor_inspector_collapse"));
        expanded.child(tabs)
            .child(pages);
        dock.collapsedView(rail)
            .expandedView(expanded);
        body.child(dock);
        presentation.child(top)
            .child(body);
        content = new ParentWidget<>().sizeRel(1f)
            .expanded()
            .background(IDrawable.NONE);
        content.child(presentation);
        root.child(content);
        IWidget bottom = bottomOverride == null ? ui.guide.buildBar() : bottomOverride;
        root.child(bottom);
        ui.tools.attach(host);
        ui.guide.attach(host);
        legacy.attach(host);
        annotations.attach(host);
        registerTarget("viewport", scene);
        registerTarget("inspector", expanded);
        registerTarget(
            "library",
            pages.getPages()
                .get(2));
        return root;
    }

    void sceneOverride(IWidget widget) {
        if (root != null) throw new IllegalStateException("Set the scene before building the graph");
        sceneOverride = widget;
    }

    boolean controlEnabled(int id) {
        return controlBounds(id) != null;
    }

    boolean propertiesGroupExpanded(String key) {
        return properties != null && properties.groupExpanded(key);
    }

    void restorePropertiesGroups(boolean target, boolean source, boolean material) {
        if (properties != null) properties.restoreGroups(target, source, material);
    }

    void tick() {
        if (root == null || paused || properties == null) return;
        if (ui.host.screenWidth() > 0 && ui.host.screenWidth() < 460) dock.expanded(false);
        properties.refresh();
        library.refresh();
        settings.refresh();
        legacy.refresh();
        annotations.refresh();
    }

    ButtonWidget<?> nativeAction(int id, Supplier<String> label, BooleanSupplier enabled, Supplier<String> tooltip) {
        boolean primary = id == 140 || id == 141 || id == 210 || id == 224 || id == 54;
        ButtonWidget<?> widget = HologramEditorNativeTheme.button(
            label,
            HologramEditorIcons.forControl(id, ""),
            () -> ui.host.parent()
                .deferAction(() -> {
                    if (!enabled.getAsBoolean()) return;
                    try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                        .fieldGraph(ui)) {
                        ui.router.activate(id);
                    }
                }),
            enabled,
            () -> false,
            primary)
            .tooltipBuilder(t -> {
                String text = tooltip.get();
                if (text != null && !text.isEmpty()) t.addLine(text);
            })
            .tooltipAutoUpdate(true);
        HologramEditorNativeTheme.guided(widget, () -> ui.canvas.controlAccent(id), enabled);
        registerControl(id, widget, label, enabled);
        return widget;
    }

    void registerControl(int id, IWidget widget, Supplier<String> label, BooleanSupplier enabled) {
        controls.computeIfAbsent(id, k -> new ArrayList<>())
            .add(new Entry(widget, label, enabled));
        registerTarget("control." + id, widget);
    }

    void registerControl(int id, IWidget widget) {
        registerControl(id, widget, () -> "", () -> true);
    }

    void registerTarget(String key, IWidget widget) {
        List<IWidget> list = targets.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.contains(widget)) list.add(widget);
    }

    TextFieldWidget adoptField(HologramMuiTextField field) {
        return ui.host.parent()
            .adoptNativeField(field);
    }

    ModularPanel host() {
        return host;
    }

    IWidget root() {
        return root;
    }

    IWidget contentRoot() {
        return content;
    }

    IWidget scene() {
        return scene;
    }

    HologramEditorAnnotationOverlay annotations() {
        return annotations;
    }

    int[] sceneLocalPoint(int screenX, int screenY) {
        int[] area = bounds(scene);
        if (area == null || screenX < area[0]
            || screenY < area[1]
            || screenX >= area[0] + area[2]
            || screenY >= area[1] + area[3]) return null;
        TransformationMatrix matrix = LocatedWidget.of(scene)
            .getTransformationMatrix();
        return new int[] { matrix.unTransformX(screenX, screenY), matrix.unTransformY(screenX, screenY) };
    }

    boolean canDropInventorySample(int screenX, int screenY) {
        return scene instanceof HologramEditorSceneWidget
            && ((HologramEditorSceneWidget) scene).canDropInventorySample(screenX, screenY);
    }

    boolean dropInventorySample(int screenX, int screenY) {
        return scene instanceof HologramEditorSceneWidget
            && ((HologramEditorSceneWidget) scene).dropInventorySample(screenX, screenY);
    }

    boolean built() {
        return root != null;
    }

    boolean libraryPageSelected() {
        return pages != null && pages.getCurrentPageIndex() == 2;
    }

    int dockPage() {
        return pages == null ? 0 : pages.getCurrentPageIndex();
    }

    boolean dockExpanded() {
        return dock != null && dock.isExpanded();
    }

    void selectDockPage(int page) {
        if (pages == null) return;
        setDockExpanded(true);
        pages.setPage(page);
        if (page == 2) {
            ui.library.libraryDrawer(true);
            ui.library.blueprintTab(true);
        }
    }

    void setDockExpanded(boolean expanded) {
        if (dock != null) dock.expanded(expanded && (ui.host.screenWidth() == 0 || ui.host.screenWidth() >= 460));
    }

    void blockInput(IWidget shield) {
        if (content != null) content.child(shield);
    }

    void pausePresentation() {
        if (root == null || paused) return;
        if (scene instanceof HologramEditorSceneWidget) ((HologramEditorSceneWidget) scene).invalidatePresentation();
        LocatedWidget focused = host.getContext()
            .getFocusedWidget();
        savedFocus = focused != null && focused.getElement() instanceof IFocusedWidget
            ? (IFocusedWidget) focused.getElement()
            : null;
        host.getContext()
            .removeFocus();
        pausedPanels.clear();
        for (ModularPanel panel : host.getContext()
            .getScreen()
            .getPanelManager()
            .getOpenPanels()) {
            if (panel.isOpen()) {
                pausedPanels.put(panel, panel.isEnabled());
                panel.setEnabled(false);
            }
        }
        root.setEnabled(false);
        paused = true;
    }

    void resumePresentation() {
        if (root == null || !paused) return;
        paused = false;
        for (Map.Entry<ModularPanel, Boolean> entry : pausedPanels.entrySet()) entry.getKey()
            .setEnabled(entry.getValue());
        pausedPanels.clear();
        root.setEnabled(true);
        root.scheduleResize();
        if (scene instanceof HologramEditorSceneWidget) ((HologramEditorSceneWidget) scene).invalidatePresentation();
    }

    /** Called after the restored native layout has actually presented its scene. */
    void presentationDrawn() {
        if (paused || savedFocus == null) return;
        IFocusedWidget focus = savedFocus;
        savedFocus = null;
        if (focus instanceof IWidget && bounds((IWidget) focus) != null) host.getContext()
            .focus(focus);
    }

    boolean fieldFocused() {
        if (host == null || !host.isValid()) return false;
        LocatedWidget focus = host.getContext()
            .getFocusedWidget();
        return focus != null
            && focus.getElement() instanceof com.cleanroommc.modularui.widgets.textfield.BaseTextFieldWidget;
    }

    boolean sceneActive() {
        return !paused && !ui.library.browsePreviewActive()
            && !ui.preview.inventory()
            && ui.preview.previewInteractive()
            && !ui.library.libraryAdminDrawer()
            && !ui.bindings.networkDrawer()
            && !HologramBackgroundTasks.busy();
    }

    boolean hasModalPanel() {
        if (host == null || !host.isValid()) return false;
        for (ModularPanel panel : host.getContext()
            .getScreen()
            .getPanelManager()
            .getOpenPanels()) if (panel != host && panel.isEnabled() && panel.disablePanelsBelow()) return true;
        return false;
    }

    int[] nativeWidgetBounds(IWidget widget) {
        return bounds(widget);
    }

    int[] controlBounds(int id) {
        List<Entry> entries = controls.get(id);
        if (entries == null) return null;
        IWidget best = null;
        int order = Integer.MAX_VALUE;
        for (Entry entry : entries) if (entry.enabled.getAsBoolean() && visible(entry.widget)) {
            int index = host.getContext()
                .getScreen()
                .getPanelManager()
                .getOpenPanels()
                .indexOf(entry.widget.getPanel());
            if (index >= 0 && index <= order) {
                best = entry.widget;
                order = index;
            }
        }
        return bounds(best);
    }

    private IWidget tutorialTarget(String key) {
        if (!ui.session.isTutorial()) return null;
        if ("modal.close".equals(key)) return modalCloseWidget();
        if (key.startsWith("control.")) {
            List<Entry> entries;
            try {
                entries = controls.get(Integer.parseInt(key.substring(8)));
            } catch (NumberFormatException invalid) {
                return null;
            }
            if (entries != null) for (Entry entry : entries) if (visible(entry.widget)) return entry.widget;
        } else {
            List<IWidget> entries = targets.get(key);
            if (entries != null)
                for (int i = entries.size() - 1; i >= 0; i--) if (visible(entries.get(i))) return entries.get(i);
        }
        return null;
    }

    boolean tutorialTargetClipped(String key) {
        IWidget widget = tutorialTarget(key);
        if (widget == null) return false;
        int[] clipped = bounds(widget), full = absoluteBounds(widget);
        return clipped == null || clipped[2] < full[2] || clipped[3] < full[3];
    }

    /** Read-only presentation also includes visible disabled buttons whose restriction a lesson explains. */
    int[] presentationBounds(String key) {
        return bounds(tutorialTarget(key));
    }

    /** Called only as a deferred sandbox action, using the same native scrolling as the player. */
    void revealTutorialTarget(String key) {
        IWidget widget = tutorialTarget(key);
        if (widget == null) return;
        IWidget parent = widget;
        while (parent.hasParent()) {
            parent = parent.getParent();
            if (parent instanceof com.cleanroommc.modularui.widget.AbstractScrollWidget) {
                com.cleanroommc.modularui.widget.AbstractScrollWidget<?, ?> list = (com.cleanroommc.modularui.widget.AbstractScrollWidget<?, ?>) parent;
                com.cleanroommc.modularui.widget.scroll.VerticalScrollData vertical = list.getScrollArea()
                    .getScrollY();
                int[] viewport = bounds(parent), full = absoluteBounds(widget);
                if (viewport == null || vertical == null) continue;
                int delta = full[1] < viewport[1] ? full[1] - viewport[1]
                    : Math.max(0, full[1] + full[3] - viewport[1] - viewport[3]);
                if (delta != 0) {
                    int before = vertical.getScroll();
                    vertical.scrollTo(list.getScrollArea(), vertical.getScroll() + delta);
                    // Re-measure after native scrolling/layout, before considering another ancestor.
                    if (vertical.getScroll() != before) return;
                }
            }
        }
    }

    int[] targetBounds(String key) {
        if (key.startsWith("control.")) try {
            return controlBounds(Integer.parseInt(key.substring(8)));
        } catch (NumberFormatException ignored) {}
        if ("modal".equals(key)) return bounds(topModal());
        if ("modal.close".equals(key)) return modalCloseBounds();
        List<IWidget> list = targets.get(key);
        if (list == null) return null;
        for (int i = list.size() - 1; i >= 0; i--) if (visible(list.get(i))) return bounds(list.get(i));
        return null;
    }

    private ModularPanel topModal() {
        if (host == null || !host.isValid()) return null;
        for (ModularPanel panel : host.getContext()
            .getScreen()
            .getPanelManager()
            .getOpenPanels()) if (panel != host && visible(panel) && panel.disablePanelsBelow()) return panel;
        return null;
    }

    private int[] modalCloseBounds() {
        return bounds(modalCloseWidget());
    }

    private IWidget modalCloseWidget() {
        ModularPanel panel = topModal();
        if (panel == null) return null;
        List<IWidget> named = targets.get("modal.close");
        if (named != null) for (int i = named.size() - 1; i >= 0; i--) {
            IWidget widget = named.get(i);
            if (visible(widget) && widget.getPanel() == panel) return widget;
        }
        return null;
    }

    /** Semantic lesson actions use the sandbox's actual native callback and its current transform. */
    boolean activateTarget(String key) {
        if (!ui.session.isTutorial()) return false;
        List<IWidget> list = targets.get(key);
        if ("modal.close".equals(key)) {
            IWidget close = modalCloseWidget();
            list = close == null ? null : java.util.Collections.singletonList(close);
        }
        if (list == null) return false;
        for (int i = list.size() - 1; i >= 0; i--) {
            IWidget widget = list.get(i);
            if ("modal.close".equals(key) && (!widget.isValid() || widget.getPanel() != topModal())) continue;
            if (bounds(widget) == null || !(widget instanceof com.cleanroommc.modularui.api.widget.Interactable))
                continue;
            LocatedWidget located = LocatedWidget.of(widget);
            if (widget instanceof IFocusedWidget) host.getContext()
                .focus(located);
            located.applyMatrix(host.getContext());
            try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                .fieldGraph(ui)) {
                ((com.cleanroommc.modularui.api.widget.Interactable) widget).onMousePressed(0);
            } finally {
                located.unapplyMatrix(host.getContext());
            }
            return true;
        }
        return false;
    }

    int[] sceneToScreen(int x, int y) {
        if (!visible(scene)) return null;
        TransformationMatrix matrix = LocatedWidget.of(scene)
            .getTransformationMatrix();
        return new int[] { Math.round(matrix.transformX(x, y)), Math.round(matrix.transformY(x, y)) };
    }

    private boolean visible(IWidget widget) {
        if (widget == null || !widget.isValid()
            || !widget.getPanel()
                .isOpen()
            || widget.getArea().width <= 0
            || widget.getArea().height <= 0) return false;
        IWidget ancestor = widget;
        while (ancestor != null) {
            if (!ancestor.isEnabled()) return false;
            if (ancestor instanceof ModularPanel || !ancestor.hasParent()) break;
            ancestor = ancestor.getParent();
        }
        return true;
    }

    private int[] bounds(IWidget widget) {
        if (!visible(widget)) return null;
        int[] rect = absoluteBounds(widget);
        IWidget parent = widget;
        while (parent.hasParent()) {
            parent = parent.getParent();
            int[] clip = absoluteBounds(parent);
            int right = Math.min(rect[0] + rect[2], clip[0] + clip[2]),
                bottom = Math.min(rect[1] + rect[3], clip[1] + clip[3]);
            rect[0] = Math.max(rect[0], clip[0]);
            rect[1] = Math.max(rect[1], clip[1]);
            rect[2] = right - rect[0];
            rect[3] = bottom - rect[1];
            if (rect[2] <= 0 || rect[3] <= 0) return null;
        }
        return rect;
    }

    private static int[] absoluteBounds(IWidget widget) {
        TransformationMatrix matrix = LocatedWidget.of(widget)
            .getTransformationMatrix();
        // LocatedWidget includes a viewport's transformChildren, including its own content scroll.
        // Its clipping rectangle stays fixed; ancestor scroll remains in the matrix for nested viewports.
        int scrollX = 0, scrollY = 0;
        if (widget instanceof com.cleanroommc.modularui.widget.AbstractScrollWidget) {
            com.cleanroommc.modularui.widget.AbstractScrollWidget<?, ?> scroll = (com.cleanroommc.modularui.widget.AbstractScrollWidget<?, ?>) widget;
            scrollX = scroll.getScrollX();
            scrollY = scroll.getScrollY();
        }
        int x = Math.round(matrix.transformX(scrollX, scrollY)), y = Math.round(matrix.transformY(scrollX, scrollY));
        int right = Math.round(matrix.transformX(scrollX + widget.getArea().width, scrollY + widget.getArea().height));
        int bottom = Math.round(matrix.transformY(scrollX + widget.getArea().width, scrollY + widget.getArea().height));
        return new int[] { x, y, right - x, bottom - y };
    }

    private static final class Entry {

        final IWidget widget;
        final Supplier<String> label;
        final BooleanSupplier enabled;

        Entry(IWidget widget, Supplier<String> label, BooleanSupplier enabled) {
            this.widget = widget;
            this.label = label;
            this.enabled = enabled;
        }
    }
}
