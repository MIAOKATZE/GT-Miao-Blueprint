package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.cleanroommc.modularui.api.GuiAxis;
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
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** One persistent native widget graph per editing session. No domain state is copied into the shell. */
final class HologramEditorShell {

    private static final IDrawable EDITOR_BACKDROP = new Rectangle().color(0xff292d2f);

    private final HologramUiModules ui;
    private ModularPanel host;
    private Flow root;
    private ParentWidget<?> content;
    private IWidget scene;
    private IWidget sceneOverride;
    private final Map<ModularPanel, Boolean> pausedPanels = new java.util.IdentityHashMap<>();
    private Expandable dock;
    private Flow inspectorBody;
    private boolean inspectorOpen, inspectorFloating, inspectorAutoOverlay;
    private int inspectorX = 60, inspectorY = 72, inspectorWidth = 184, inspectorHeight = 250;
    private PagedWidget<?> pages;
    private HologramEditorPropertiesPane properties;
    private HologramEditorLibraryPane library;
    private HologramEditorBlueprintSettingsPane settings;
    private HologramEditorLegacyDialogs legacy;
    private HologramEditorAnnotationOverlay annotations;
    private HologramEditorStructurePane structures;
    private HologramEditorCutWorkspace cut;
    private Expandable libraryDrawer;
    private Flow libraryBody;
    private int libraryWidth = 220;
    private Flow editBody;
    private ParentWidget<?> cutBody;
    private Flow structureDrawer;
    private boolean structuresOpen;
    private boolean lastCut;
    private Flow launchers;
    private boolean launchersExpanded, launchersCompact;
    private HologramEditorTaskProgress taskProgress;
    private IWidget footer;
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
        taskProgress = new HologramEditorTaskProgress(ui, () -> cut == null ? null : cut.progress());
        presentation.child(buildDocumentBar());
        Flow top = HologramEditorNativeTheme.collapsingFlow(GuiAxis.X)
            .widthRel(1f)
            .height(22)
            .padding(4, 2)
            .childPadding(8)
            .background(HologramEditorNativeTheme.PANEL);
        top.child(ui.tools.buildModeBar());
        top.child(
            HologramEditorNativeTheme.label(this::workspaceHint, HologramEditorNativeTheme.MUTED)
                .widthRel(1f)
                .expanded()
                .height(18));
        top.child(ui.tools.buildTopTools());
        presentation.child(top);

        structures = new HologramEditorStructurePane(ui);
        if (cut == null) cut = new HologramEditorCutWorkspace(ui);
        editBody = HologramEditorNativeTheme.collapsingFlow(GuiAxis.X)
            .name("editor_native_edit_body")
            .sizeRel(1f)
            .childPadding(0);
        editBody.child(ui.tools.buildToolRail());
        structureDrawer = Flow.col()
            .name("editor_native_structure_drawer")
            .width(144)
            .heightRel(1f)
            .padding(5)
            .childPadding(4)
            .background(HologramEditorNativeTheme.PANEL)
            .child(
                HologramEditorNativeTheme.button(() -> "\u7ed3\u6784\u5217\u8868  \u2039", this::toggleStructures)
                    .widthRel(1f)
                    .height(22))
            .child(structures.build());
        structureDrawer.setEnabledIf(w -> structuresOpen);
        editBody.child(structureDrawer);
        scene = sceneOverride == null ? new HologramEditorSceneWidget(ui).sizeRel(1f) : sceneOverride;
        ParentWidget<?> viewport = new ParentWidget<>().name("editor_native_viewport")
            .widthRel(1f)
            .expanded()
            .heightRel(1f)
            .background(EDITOR_BACKDROP)
            .child(scene);
        viewport.child(
            ui.tools.buildViewPresets()
                .right(8)
                .top(8));
        viewport.child(
            HologramEditorNativeTheme
                .label(
                    () -> "\u7b49\u8ddd \u00b7 \u6b63\u4ea4   " + ui.session.state().cells.size() + " \u683c",
                    0xffc6c7c1)
                .name("editor_native_viewport_caption")
                .pos(10, 10)
                .size(140, 12));
        viewport.child(
            new ParentWidget<>().leftRel(.5f, 0, .5f)
                .width(138)
                .bottom(10)
                .height(20)
                .child(ui.tools.buildSelectedBar()));
        editBody.child(viewport);
        editBody.child(buildLaunchers());
        editBody.child(buildInspector());
        editBody.child(buildLibraryDrawer());
        cutBody = new ParentWidget<>().sizeRel(1f)
            .child(cut.build());
        ParentWidget<?> workspace = new ParentWidget<>().widthRel(1f)
            .heightRel(1f)
            .expanded()
            .background(EDITOR_BACKDROP)
            .child(editBody)
            .child(cutBody);
        ButtonWidget<?> empty = new ButtonWidget<>().name("editor_native_empty_projection")
            .sizeRel(1f)
            .background(IDrawable.NONE)
            .disableHoverBackground()
            .overlay((context, x, y, width, height, theme) -> {
                com.cleanroommc.modularui.drawable.GuiDraw.drawRect(x, y, width, height, 0xc8292d2f);
                String message = "右击控制器开启投影模式";
                ui.host.font()
                    .drawString(
                        message,
                        x + (width - ui.host.font()
                            .getStringWidth(message)) / 2,
                        y + height / 2,
                        0xffdad3c6);
            })
            .onMousePressed(button -> true)
            .setEnabledIf(w -> ui.workspaces.emptyProjection());
        workspace.child(empty);
        registerTarget("projection.empty", empty);
        editBody.setEnabledIf(w -> !ui.workspaces.isCut());
        cutBody.setEnabledIf(w -> ui.workspaces.isCut());
        presentation.child(workspace);
        content = new ParentWidget<>().sizeRel(1f)
            .expanded()
            .background(IDrawable.NONE)
            .child(presentation);
        // MUI 2.3.92 paints sibling backgrounds before their subtrees. A single-child overlay keeps the
        // inspector's paper background after the presentation's workspace backdrop, alongside its own content.
        content.child(
            new InspectorOverlay().name("editor_native_inspector_overlay")
                .sizeRel(1f)
                .background(IDrawable.NONE)
                .disableThemeBackground(true)
                .disableHoverThemeBackground(true)
                .child(inspectorBody));
        root.child(content);
        IWidget bottom = bottomOverride == null ? ui.guide.buildBar() : bottomOverride;
        footer = bottom;
        bottom.setEnabled(!ui.workspaces.emptyProjection());
        root.child(bottom);
        ui.tools.attach(host);
        ui.guide.attach(host);
        legacy.attach(host);
        annotations.attach(host);
        registerTarget("viewport", ui.workspaces.isCut() ? cut.scene() : scene);
        registerTarget("inspector", inspectorBody);
        registerTarget("library", libraryDrawer);
        return root;
    }

    void sceneOverride(IWidget widget) {
        if (root != null) throw new IllegalStateException("Set the scene before building the graph");
        sceneOverride = widget;
    }

    void cutOverride(HologramEditorCutWorkspace workspace) {
        if (root != null) throw new IllegalStateException("Set the cut workspace before building the graph");
        cut = workspace;
    }

    private IWidget buildDocumentBar() {
        Flow bar = HologramEditorNativeTheme.collapsingFlow(GuiAxis.X)
            .name("editor_native_document_bar")
            .widthRel(1f)
            .height(31)
            .padding(5, 3)
            .childPadding(4)
            .background(HologramEditorNativeTheme.PANEL);
        // Decoration only: the real structure-list command remains on the tool rail.
        bar.child(
            new com.cleanroommc.modularui.widget.Widget<>().size(HologramEditorNativeTheme.ICON_SIZE)
                .background(HologramEditorIcons.texture("structure", false, true, false, false))
                .disableHoverBackground());
        bar.child(
            Flow.col()
                .widthRel(1f)
                .expanded()
                .childPadding(2)
                .child(
                    HologramEditorNativeTheme.label(this::documentTitle, HologramEditorNativeTheme.TITLE)
                        .widthRel(1f)
                        .height(12))
                .child(
                    HologramEditorNativeTheme
                        .label(
                            () -> ui.workspaces.projection() ? "控制器投影 · 固定锚点"
                                : ui.workspaces.isCut() ? "当前存档 · 独立剪切列表" : ui.library.blueprintSaveState(),
                            HologramEditorNativeTheme.MUTED)
                        .widthRel(1f)
                        .height(10)));
        // Tasks share the existing document row; their arrival must never move the mode bar or canvas.
        bar.child(
            taskProgress.widthRel(.35f)
                .height(20));
        bar.child(headerButton("打开剪切", "cut", () -> ui.workspaces.activateCut(), ui.workspaces::blueprint));
        bar.child(headerButton("蓝图库", "library", () -> {
            if (!ui.workspaces.blueprint()) ui.workspaces.activateBlueprint();
            else toggleLibrary();
        }, ui.workspaces::blueprint));
        bar.child(headerButton("教程", "tutorial", ui.guide::openLessons, () -> true));
        bar.child(headerButton("保存", "save", () -> selectDockPage(7), ui.workspaces::blueprint));
        bar.child(headerButton("退出", "back", ui.router::closeRoot, () -> true));
        bar.child(ui.guide.buildChip());
        return bar;
    }

    private String documentTitle() {
        if (ui.workspaces.isCut()) return "剪切存储";
        String title = ui.session.state().data.getString("virtualPresetName")
            .trim();
        if (title.isEmpty()) title = ui.session.state().data.getString("title")
            .trim();
        if (ui.workspaces.blueprint() && (title.isEmpty() || "全息构造器".equals(title)))
            return ui.session.state().cells.isEmpty() ? "未命名蓝图" : "蓝图草稿";
        return title.isEmpty() ? "控制器投影" : title;
    }

    private IWidget headerButton(String name, String icon, Runnable action, BooleanSupplier visible) {
        boolean major = "save".equals(icon) || "back".equals(icon) || "library".equals(icon) || "tutorial".equals(icon);
        Runnable invoke = () -> ui.host.parent()
            .deferAction(action);
        ButtonWidget<?> button = (major ? HologramEditorNativeTheme.button(() -> name, icon, invoke)
            : HologramEditorNativeTheme.iconButton(icon, () -> name, invoke, () -> true, () -> false)).sizeRel(1f);
        if ("save".equals(icon)) HologramEditorNativeTheme.guided(
            button,
            () -> actionAccent(140, () -> true),
            () -> ui.workspaces.blueprint() && ui.session.editable()
                && ui.processing.processingReady()
                && !ui.session.state().cells.isEmpty());
        registerTarget("header." + icon, button);
        return new ParentWidget<>()
            .width(
                "library".equals(icon) ? 66
                    : "tutorial".equals(icon) ? 60 : major ? 44 : HologramEditorNativeTheme.ICON_BUTTON)
            .height(major ? 20 : HologramEditorNativeTheme.ICON_BUTTON)
            .setEnabledIf(w -> visible.getAsBoolean())
            .child(button);
    }

    private String workspaceHint() {
        return ui.workspaces.projection() ? "固定控制器 · 等级 / 信道 / 仓室"
            : ui.workspaces.isCut() ? "选择列表即时预览 · 放置与撤回" : "点选格 · 列表选整组 · G 移动";
    }

    private IWidget buildLaunchers() {
        launchers = HologramEditorNativeTheme.collapsingFlow(GuiAxis.Y)
            .name("editor_native_panel_launchers")
            .width(116)
            .heightRel(1f)
            .padding(2, 4)
            .childPadding(4)
            .background(HologramEditorNativeTheme.PANEL);
        String[] icons = { "select", "structure", "process", "material", "network", "info", "place" };
        String[] names = { "所选格属性", "结构配置", "蓝图处理", "材料与来源", "量子组与网络", "提示词与区域", "材料核验与施工" };
        int[] indices = { 0, 3, 1, 4, 5, 6, 8 };
        ListWidget<IWidget, ?> list = new ListWidget<>().widthRel(1f)
            .heightRel(1f)
            .expanded();
        for (int i = 0; i < names.length; i++) {
            final int index = indices[i];
            final String name = names[i];
            ButtonWidget<?> button = HologramEditorNativeTheme.toolButton(() -> name, icons[i], () -> {
                if (dockExpanded() && pages.getCurrentPageIndex() == index) setDockExpanded(false);
                else selectDockPage(index);
            }, () -> true, () -> dockExpanded() && pages.getCurrentPageIndex() == index, this::launcherLabels)
                .widthRel(1f)
                .height(18);
            HologramEditorNativeTheme.guided(button, () -> pageAccent(index), () -> true);
            ParentWidget<?> slot = new ParentWidget<>().widthRel(1f)
                .height(22)
                .child(button);
            if (index == 1 || index == 6) slot.setEnabledIf(w -> ui.workspaces.blueprint());
            if (index == 5) slot.setEnabledIf(
                w -> ui.workspaces.blueprint() || ui.bindings.networkGroups()
                    .tagCount() > 0);
            if (index == 8) slot.setEnabledIf(
                w -> ui.workspaces.projection() || ui.session.state().data.getBoolean("virtualPlacement"));
            list.child(slot);
            registerTarget("launcher." + index, button);
        }
        launchers.child(list);
        ButtonWidget<?> collapse = HologramEditorNativeTheme.button(() -> launcherLabels() ? ">" : "<", () -> {
            launchersExpanded = !launchersExpanded;
            refreshRailLayout();
        })
            .widthRel(1f)
            .height(18);
        collapse.overlay((context, x, y, width, height, theme) -> {
            String icon = launcherLabels() ? "collapse-right" : "collapse-left";
            int px = x + (width - HologramEditorNativeTheme.ICON_SIZE) / 2;
            int py = y + (height - HologramEditorNativeTheme.ICON_SIZE) / 2;
            HologramEditorIcons.draw(icon, px, py, 12, true, true, false, collapse.isHovering());
            int accent = 0;
            for (int index : indices) if (launcherAvailable(index)) accent = strongerAccent(accent, pageAccent(index));
            HologramEditorIcons.drawGlint(icon, px, py, 12, accent);
            HologramEditorTheme.drawGuidanceFrame(x, y, width, height, accent, true);
        });
        launchers.child(collapse);
        registerTarget("rail.right", launchers);
        registerTarget("rail.right.toggle", collapse);
        return launchers;
    }

    private boolean launcherLabels() {
        return launchersExpanded && !launchersCompact;
    }

    private boolean launcherAvailable(int page) {
        return switch (page) {
            case 1, 6 -> ui.workspaces.blueprint();
            case 5 -> ui.workspaces.blueprint() || ui.bindings.networkGroups()
                .tagCount() > 0;
            case 8 -> ui.workspaces.projection() || ui.session.state().data.getBoolean("virtualPlacement");
            default -> true;
        };
    }

    private int pageAccent(int page) {
        if (pages == null || !launcherAvailable(page)) return 0;
        IWidget pageRoot = pages.getPages()
            .get(page);
        int accent = 0;
        for (Map.Entry<Integer, List<Entry>> control : controls.entrySet()) for (Entry entry : control.getValue()) {
            if (!entry.enabled.getAsBoolean()) continue;
            IWidget ancestor = entry.widget;
            while (ancestor != pageRoot && ancestor.hasParent()) ancestor = ancestor.getParent();
            if (ancestor == pageRoot) accent = strongerAccent(accent, actionAccent(control.getKey(), entry.enabled));
        }
        return accent;
    }

    static int strongerAccent(int current, int candidate) {
        if (candidate == 0) return current;
        if (current == 0 || accentPriority(candidate) > accentPriority(current)) return candidate;
        return current;
    }

    private static int accentPriority(int accent) {
        if (HologramUiAccent.color(accent) == HologramUiAccent.NEXT) return 4;
        if (HologramUiAccent.color(accent) == HologramUiAccent.WARNING) return 3;
        return accent == HologramUiAccent.SPECTRUM ? 2 : 1;
    }

    void refreshRailLayout() {
        layoutSidebars();
        if (root != null) root.scheduleResize();
    }

    private Expandable expandable() {
        // The inspector's collapsed view is zero-width; MUI's default minimum of 8 leaves a dead strip.
        return new Expandable() {

            @Override
            public com.cleanroommc.modularui.api.widget.Interactable.Result onMousePressed(int button) {
                return com.cleanroommc.modularui.api.widget.Interactable.Result.STOP;
            }
        }.coverChildrenWidth(0)
            .disableCoverChildrenHeight()
            .heightRel(1f)
            .animationDuration(0);
    }

    private IWidget buildInspector() {
        dock = expandable().expanded(false);
        Flow body = Flow.col()
            .width(184)
            .height(250)
            .padding(5)
            .childPadding(3)
            .background(HologramEditorNativeTheme.PANEL);
        Flow heading = Flow.row()
            .widthRel(1f)
            .height(18)
            .childPadding(3);
        heading.child(
            new InspectorHandle().widthRel(1f)
                .expanded()
                .height(18));
        heading.child(
            HologramEditorNativeTheme
                .flatIconButton(
                    "detach",
                    () -> inspectorFloating ? "停靠到侧栏" : "浮出功能面板",
                    this::toggleInspectorFloating,
                    () -> true,
                    () -> inspectorFloating)
                .size(HologramEditorNativeTheme.ICON_BUTTON));
        heading.child(
            HologramEditorNativeTheme
                .flatIconButton("collapse-right", () -> "收起功能侧栏", () -> setDockExpanded(false), () -> true, () -> false)
                .size(HologramEditorNativeTheme.ICON_BUTTON));
        pages = new PagedWidget<>().sizeRel(1f)
            .expanded()
            .initialPage(0)
            .addPage(properties.buildSelection())
            .addPage(settings.build())
            .addPage(new ParentWidget<>().sizeRel(1f))
            .addPage(properties.buildStructure())
            .addPage(properties.buildMaterials())
            .addPage(buildNetworkPane())
            .addPage(buildAnnotationPane())
            .addPage(library.buildSave())
            .addPage(properties.buildConstruction());
        body.child(heading)
            .child(pages);
        inspectorBody = body;
        inspectorBody.setEnabledIf(w -> inspectorOpen && !ui.workspaces.isCut() && !ui.workspaces.emptyProjection());
        dock.collapsedView(
            new com.cleanroommc.modularui.widget.Widget<>().width(0)
                .heightRel(1f)
                .background(IDrawable.NONE))
            .expandedView(
                new com.cleanroommc.modularui.widget.Widget<>().width(184)
                    .heightRel(1f)
                    .background(IDrawable.NONE));
        return dock;
    }

    private IWidget buildNetworkPane() {
        return Flow.col()
            .sizeRel(1f)
            .childPadding(7)
            .child(
                nativeAction(196, () -> "已有量子类群绑定", () -> ui.session.editable(), () -> "当前蓝图登记的真实量子类群与网络").widthRel(1f)
                    .height(18))
            .child(
                nativeAction(179, () -> "AE 容器与资源标记", () -> ui.session.editable(), () -> "原容器标记与资源审查").widthRel(1f)
                    .height(18));
    }

    private IWidget buildAnnotationPane() {
        return Flow.col()
            .sizeRel(1f)
            .childPadding(7)
            .child(
                nativeAction(
                    146,
                    () -> "所选格提示词",
                    () -> ui.session.editable() && ui.session.selectedCell() != null,
                    () -> "编辑当前格的施工提示").widthRel(1f)
                        .height(18))
            .child(
                nativeAction(227, () -> "全局提示词", () -> ui.session.editable(), () -> "整份蓝图的说明").widthRel(1f)
                    .height(18))
            .child(
                nativeAction(166, () -> "所选格 NBT", () -> ui.processing.canEditBlueprintNbt(), () -> "审阅当前格的真实NBT")
                    .widthRel(1f)
                    .height(18));
    }

    private IWidget buildLibraryDrawer() {
        libraryDrawer = expandable().expanded(ui.workspaces.blueprint());
        ui.library.libraryDrawer(ui.workspaces.blueprint());
        ui.library.blueprintTab(ui.workspaces.blueprint());
        Flow collapsed = Flow.col()
            .width(18)
            .heightRel(1f)
            .padding(1, 6)
            .background(HologramEditorNativeTheme.PANEL);
        collapsed.child(
            HologramEditorNativeTheme
                .flatIconButton("library", () -> "展开蓝图库", this::toggleLibrary, () -> true, () -> false)
                .size(HologramEditorNativeTheme.ICON_BUTTON));
        libraryBody = Flow.col()
            .width(libraryWidth)
            .heightRel(1f)
            .padding(4)
            .childPadding(4)
            .background(HologramEditorNativeTheme.PANEL);
        libraryBody.child(
            new ParentWidget<>().sizeRel(1f)
                .expanded()
                .child(library.build()));
        libraryDrawer.collapsedView(collapsed)
            .expandedView(libraryBody)
            .setEnabledIf(w -> ui.workspaces.blueprint());
        return libraryDrawer;
    }

    void toggleStructures() {
        structuresOpen = !structuresOpen;
        if (editBody != null) editBody.scheduleResize();
    }

    void toggleLibrary() {
        if (libraryDrawer != null) libraryDrawer.expanded(!libraryDrawer.isExpanded());
        if (libraryDrawer != null) ui.library.libraryDrawer(libraryDrawer.isExpanded());
        if (ui.session.isTutorial() && libraryPageSelected()) setDockExpanded(false);
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
        if (footer != null) footer.setEnabled(!ui.workspaces.emptyProjection());
        if (taskProgress != null) taskProgress.refresh();
        boolean showingCut = ui.workspaces.isCut();
        cut.activate(showingCut);
        if (lastCut != showingCut) {
            lastCut = showingCut;
            root.scheduleResize();
            ui.preview.releasePointerCapture();
        }
        if (showingCut) {
            cut.tick();
            return;
        }
        layoutSidebars();
        layoutInspector();
        properties.refresh();
        structures.refresh();
        library.refresh();
        settings.refresh();
        legacy.refresh();
        annotations.refresh();
    }

    ButtonWidget<?> nativeAction(int id, Supplier<String> label, BooleanSupplier enabled, Supplier<String> tooltip) {
        return nativeAction(id, HologramEditorIcons.forControl(id, ""), label, enabled, tooltip, false);
    }

    void rotateBlueprint() {
        if (settings != null) settings.rotateBlueprint();
    }

    ButtonWidget<?> nativeIconAction(int id, String icon, Supplier<String> label, BooleanSupplier enabled,
        Supplier<String> tooltip) {
        return nativeAction(id, icon, label, enabled, tooltip, true);
    }

    private ButtonWidget<?> nativeAction(int id, String icon, Supplier<String> label, BooleanSupplier enabled,
        Supplier<String> tooltip, boolean iconOnly) {
        boolean primary = id == 140 || id == 141 || id == 210 || id == 224 || id == 54;
        Runnable invoke = () -> ui.host.parent()
            .deferAction(() -> {
                if (!enabled.getAsBoolean()) return;
                try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                    .fieldGraph(ui)) {
                    ui.router.activate(id);
                }
            });
        ButtonWidget<?> widget = (iconOnly
            ? HologramEditorNativeTheme.iconButton(icon, label, invoke, enabled, () -> false)
            : HologramEditorNativeTheme.button(label, icon, invoke, enabled, () -> false, primary))
                .tooltipBuilder(t -> {
                    if (iconOnly) t.addLine(label.get());
                    String text = tooltip.get();
                    if (text != null && !text.isEmpty()) t.addLine(text);
                })
                .tooltipAutoUpdate(true);
        HologramEditorNativeTheme.guided(widget, () -> actionAccent(id, enabled), enabled);
        registerControl(id, widget, label, enabled);
        return widget;
    }

    private int actionAccent(int id, BooleanSupplier enabled) {
        if (id == 50 && enabled.getAsBoolean() && !ui.workspaces.emptyProjection()) return HologramUiAccent.NEXT;
        return ui.canvas.controlAccent(id);
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
        return cut != null && ui.workspaces.isCut() ? cut.scene() : scene;
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

    void close() {
        if (cut != null) cut.close();
        ui.preview.releasePointerCapture();
    }

    boolean libraryPageSelected() {
        return libraryDrawer != null && libraryDrawer.isExpanded() && ui.workspaces.blueprint();
    }

    int dockPage() {
        return libraryPageSelected() && !dockExpanded() ? 2 : pages == null ? 0 : pages.getCurrentPageIndex();
    }

    boolean dockExpanded() {
        return inspectorOpen;
    }

    void selectDockPage(int page) {
        if (pages == null) return;
        if (page == 2) {
            if (ui.session.isTutorial()) setDockExpanded(false);
            if (libraryDrawer != null) libraryDrawer.expanded(true);
            ui.library.libraryDrawer(true);
            ui.library.blueprintTab(true);
            return;
        }
        if (ui.session.isTutorial()) closeTutorialLibrary();
        setDockExpanded(true);
        pages.setPage(page);
    }

    /** Lessons devote the viewport to manipulation instead of retaining unrelated inspectors. */
    void setTutorialSceneOnly() {
        if (!ui.session.isTutorial()) return;
        closeTutorialLibrary();
        setDockExpanded(false);
        if (structuresOpen) toggleStructures();
    }

    private void closeTutorialLibrary() {
        if (libraryDrawer != null) libraryDrawer.expanded(false);
        ui.library.libraryDrawer(false);
    }

    void setDockExpanded(boolean expanded) {
        inspectorOpen = expanded;
        if (dock != null) dock.expanded(expanded && !inspectorFloating && !inspectorAutoOverlay);
        if (root != null) root.scheduleResize();
    }

    private void toggleInspectorFloating() {
        inspectorFloating = !inspectorFloating;
        setDockExpanded(true);
        layoutInspector();
    }

    private void layoutSidebars() {
        if (content == null || !content.isValid() || libraryBody == null) return;
        int minimumLibrary = ui.workspaces.blueprint() ? libraryDrawer.isExpanded() ? 100 : 18 : 0;
        int railBudget = content.getArea().width - (structuresOpen ? 144 : 0) - minimumLibrary - 180;
        int left = ui.tools.nativeTools()
            .requestedRailWidth();
        launchersCompact = launchersExpanded && left + 116 > railBudget;
        int right = launcherLabels() ? 116 : HologramEditorNativeTheme.TOOL_RAIL_WIDTH;
        ui.tools.nativeTools()
            .compactRail(left + right > railBudget);
        if (launchers != null && launchers.getArea().width != right) launchers.width(right)
            .scheduleResize();
        int available = content.getArea().width - ui.tools.nativeTools()
            .railWidth() - right - (structuresOpen ? 144 : 0);
        int width = Math.min(220, Math.max(100, available - 180));
        if (libraryWidth != width) {
            libraryWidth = width;
            libraryBody.width(width);
            root.scheduleResize();
        }
        int librarySpace = ui.workspaces.blueprint() ? libraryDrawer.isExpanded() ? libraryWidth : 18 : 0;
        // Keep both sidebars at their declared widths. When they would leave less than 180 GUI pixels
        // for the model, present the inspector over the model instead of silently shrinking either pane.
        boolean overlay = available - librarySpace - 184 < 180;
        if (inspectorAutoOverlay != overlay) {
            inspectorAutoOverlay = overlay;
            dock.expanded(inspectorOpen && !inspectorFloating && !overlay);
            root.scheduleResize();
        }
    }

    private void layoutInspector() {
        if (inspectorBody == null || !inspectorOpen || content == null || !content.isValid()) return;
        int x, y, w, h;
        if (inspectorFloating) {
            w = Math.min(inspectorWidth, Math.max(140, content.getArea().width - 12));
            h = Math.min(inspectorHeight, Math.max(100, content.getArea().height - 16));
            x = Math.max(0, Math.min(inspectorX, content.getArea().width - w));
            y = Math.max(0, Math.min(inspectorY, content.getArea().height - h));
        } else if (inspectorAutoOverlay) {
            int[] area = absoluteBounds(dock), origin = bounds(content);
            if (origin == null) return;
            w = Math.min(184, Math.max(100, content.getArea().width - 24));
            h = area[3];
            int librarySpace = ui.workspaces.blueprint() ? libraryDrawer.isExpanded() ? libraryWidth : 18 : 0;
            x = Math.max(0, content.getArea().width - librarySpace - w);
            y = area[1] - origin[1];
        } else {
            int[] area = bounds(dock), origin = bounds(content);
            if (area == null || origin == null || area[2] < 100) return;
            int librarySpace = ui.workspaces.blueprint() ? libraryDrawer.isExpanded() ? libraryWidth : 18 : 0;
            x = Math.max(0, content.getArea().width - librarySpace - area[2]);
            y = area[1] - origin[1];
            w = area[2];
            h = area[3];
        }
        if (inspectorBody.getArea().rx != x || inspectorBody.getArea().ry != y
            || inspectorBody.getArea().width != w
            || inspectorBody.getArea().height != h)
            inspectorBody.pos(x, y)
                .size(w, h)
                .scheduleResize();
    }

    private static final class InspectorOverlay extends ParentWidget<InspectorOverlay> {

        @Override
        public boolean canHover() {
            return false;
        }

        @Override
        public boolean canHoverThrough() {
            return true;
        }

        @Override
        public boolean canClickThrough() {
            return true;
        }
    }

    private final class InspectorHandle extends com.cleanroommc.modularui.widget.Widget<InspectorHandle>
        implements com.cleanroommc.modularui.api.widget.Interactable {

        private int grabX, grabY;

        InspectorHandle() {
            tooltip(t -> t.addLine("拖动标题浮出并移动；图钉按钮停靠回侧栏"));
        }

        @Override
        public boolean canHover() {
            return true;
        }

        @Override
        public boolean canClickThrough() {
            return false;
        }

        @Override
        public void draw(com.cleanroommc.modularui.screen.viewport.ModularGuiContext context,
            com.cleanroommc.modularui.theme.WidgetThemeEntry<?> theme) {
            String[] titles = { "所选格属性", "蓝图处理", "", "结构配置", "材料与来源", "量子与网络", "提示词与区域", "保存蓝图", "材料核验与施工" };
            net.minecraft.client.gui.FontRenderer font = ui.host.minecraft().fontRenderer;
            font.drawString(
                font.trimStringToWidth(titles[pages.getCurrentPageIndex()], Math.max(8, getArea().width - 2)),
                1,
                Math.max(1, (getArea().height - 9) / 2),
                HologramEditorNativeTheme.TITLE);
        }

        @Override
        public Result onMousePressed(int button) {
            if (button != 0) return Result.STOP;
            int[] area = bounds(inspectorBody), origin = bounds(content);
            if (area == null || origin == null) return Result.STOP;
            grabX = getContext().getAbsMouseX() - area[0];
            grabY = getContext().getAbsMouseY() - area[1];
            inspectorX = area[0] - origin[0];
            inspectorY = area[1] - origin[1];
            return Result.SUCCESS;
        }

        @Override
        public void onMouseDrag(int button, long elapsed) {
            if (button != 0) return;
            int[] origin = bounds(content);
            if (origin == null) return;
            inspectorFloating = true;
            inspectorX = getContext().getAbsMouseX() - origin[0] - grabX;
            inspectorY = getContext().getAbsMouseY() - origin[1] - grabY;
            dock.expanded(false);
            layoutInspector();
        }
    }

    void blockInput(IWidget shield) {
        if (content != null) content.child(shield);
    }

    void pausePresentation() {
        pause(false);
    }

    void pauseWorkspace() {
        pause(true);
        if (cut != null) cut.activate(false);
    }

    void resumeWorkspace() {
        resumePresentation();
    }

    private void pause(boolean workspace) {
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
            if (panel.isOpen() && (!workspace || panel != host)) {
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
        return !paused && !ui.workspaces.isCut()
            && !ui.workspaces.switching()
            && !ui.library.browsePreviewActive()
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

    IWidget tutorialTarget(String key) {
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

    /** Direct teaching callbacks must respect the same modal owner as MUI2's physical input router. */
    boolean tutorialTargetAdmitted(String key) {
        ModularPanel modal = topModal();
        if (modal == null) return true;
        IWidget target = tutorialTarget(key);
        return target != null && target.getPanel() == modal;
    }

    String tutorialModalIdentity() {
        ModularPanel modal = topModal();
        return modal == null ? "" : modal.getName();
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

    /** Escape/E returns through the actual native owner, one visible layer per key press. */
    boolean consumeBackLayer() {
        ModularPanel modal = topModal();
        if (modal != null) {
            IWidget close = modalCloseWidget();
            if (close instanceof com.cleanroommc.modularui.api.widget.Interactable) {
                LocatedWidget located = LocatedWidget.of(close);
                located.applyMatrix(host.getContext());
                try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                    .fieldGraph(ui)) {
                    ((com.cleanroommc.modularui.api.widget.Interactable) close).onMousePressed(0);
                } finally {
                    located.unapplyMatrix(host.getContext());
                }
            } else ui.host.parent()
                .deferAction(modal::closeIfOpen);
            return true;
        }
        if (ui.tools.viewWorkspaceOpen()) {
            ui.tools.closeTransient();
            return true;
        }
        if (ui.tools.nativeTools()
            .consumeFloatingBack()) return true;
        if (libraryPageSelected() && library.consumeBackLayer()) return true;
        if (structuresOpen) {
            toggleStructures();
            return true;
        }
        if (dockExpanded()) {
            setDockExpanded(false);
            return true;
        }
        if (libraryPageSelected()) {
            toggleLibrary();
            return true;
        }
        return false;
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
