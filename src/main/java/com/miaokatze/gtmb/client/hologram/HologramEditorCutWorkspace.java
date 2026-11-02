package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** A retained workbench page. Only explicit live actions enter the existing cut controller. */
final class HologramEditorCutWorkspace {

    private final HologramUiModules ui;
    private final List<NBTTagCompound> directory = new ArrayList<>();
    private final Map<String, IWidget> targets = new LinkedHashMap<>();
    private final HologramCutStorageBrowserView.Selection selection;
    private final HologramCutStoragePreviewView preview;
    private final Runnable lessonRefresh;
    private final Runnable lessonPlace;
    private final BooleanSupplier lessonCanPlace;
    private final boolean isolated;
    private HologramCutStoragePreview live;
    private HologramCutStorageBrowserView browser;
    private Flow root;
    private String selected = "", confirmUndo = "";
    private boolean active, closed, activatedOnce;

    HologramEditorCutWorkspace(HologramUiModules ui) {
        this.ui = ui;
        isolated = ui.session.isTutorial();
        lessonRefresh = () -> {};
        lessonPlace = null;
        lessonCanPlace = () -> false;
        selection = new HologramCutStorageBrowserView.Selection() {

            public List<NBTTagCompound> entries() {
                return directory;
            }

            public String selected() {
                return live == null ? selected : live.state.selected();
            }

            public void select(String id) {
                selected = id;
                confirmUndo = "";
                if (live != null) live.select(id);
            }

            public String notice() {
                return live == null || live.notice()
                    .isEmpty() ? HologramCutStoragePreview.text("camera") : live.notice();
            }
        };
        preview = new HologramCutStoragePreviewView() {

            public HologramCutRawPreviewSession renderer() {
                return live == null ? null : live.renderer();
            }

            public NBTTagCompound raw() {
                return live == null ? null : live.raw();
            }

            public String notice() {
                return selection.notice();
            }
        };
    }

    /** Teaching owns its in-memory data and raw renderer. This constructor never creates live transport. */
    HologramEditorCutWorkspace(HologramUiModules ui, HologramCutStorageBrowserView.Selection selection,
        HologramCutStoragePreviewView preview) {
        this(ui, selection, preview, () -> {});
    }

    HologramEditorCutWorkspace(HologramUiModules ui, HologramCutStorageBrowserView.Selection selection,
        HologramCutStoragePreviewView preview, Runnable refresh) {
        this(ui, selection, preview, refresh, null, () -> false);
    }

    HologramEditorCutWorkspace(HologramUiModules ui, HologramCutStorageBrowserView.Selection selection,
        HologramCutStoragePreviewView preview, Runnable refresh, Runnable place, BooleanSupplier canPlace) {
        this.ui = ui;
        this.selection = selection;
        this.preview = preview;
        lessonRefresh = refresh;
        lessonPlace = place;
        lessonCanPlace = canPlace;
        isolated = true;
    }

    IWidget build() {
        if (root != null) return root;
        root = Flow.column()
            .sizeRel(1f)
            .padding(4)
            .childPadding(4)
            .name("editor_cut_workspace");
        browser = new HologramCutStorageBrowserView(selection, preview, this::register);
        root.child(
            browser.widthRel(1f)
                .expanded());
        register("cut.browser", browser);
        Flow actions = Flow.column()
            .widthRel(1f)
            .height(44)
            .childPadding(4);
        actions.child(button("place", "放置所选", "world", this::canPlace, this::place).widthRel(1f));
        Flow secondary = Flow.row()
            .widthRel(1f)
            .height(18)
            .childPadding(3);
        secondary.child(
            button("undo", "撤回原处", "undo", this::canAct, () -> { if (canAct()) confirmUndo = selection.selected(); })
                .expanded());
        secondary.child(
            button("sync", "同步 / 重试", "reset-view", () -> active && !closed, this::refresh).expanded()
                .tooltipBuilder(t -> t.addLine("同步剪切列表 / 重试预览")));
        actions.child(secondary);
        browser.addFooter(actions);
        Flow confirmation = HologramEditorNativeTheme.collapsingFlow(GuiAxis.Y)
            .widthRel(1f)
            .height(48)
            .padding(4)
            .childPadding(4)
            .background(HologramEditorNativeTheme.PANEL)
            .setEnabledIf(w -> !confirmUndo.isEmpty());
        confirmation.child(
            HologramEditorNativeTheme.label(() -> "确认撤回到原处？")
                .widthRel(1f)
                .height(18));
        Flow confirmationActions = Flow.row()
            .widthRel(1f)
            .height(18)
            .childPadding(4);
        confirmationActions.child(
            button("undo.confirm", "确认撤回", null, () -> canAct() && confirmUndo.equals(selection.selected()), () -> {
                String id = confirmUndo;
                confirmUndo = "";
                if (!isolated && !ui.session.isTutorial() && active) client().requestStorageUndo(id);
            }).expanded());
        confirmationActions.child(button("undo.cancel", "取消", "cancel", () -> true, () -> confirmUndo = "").expanded());
        confirmation.child(confirmationActions);
        browser.addFooter(confirmation);
        if (!isolated) refreshDirectory();
        return root;
    }

    private ButtonWidget<?> button(String name, String label, String icon, BooleanSupplier enabled, Runnable action) {
        ButtonWidget<?> button = HologramEditorNativeTheme
            .button(() -> label, icon, action, enabled, () -> false, "place".equals(name))
            .height(
                "place".equals(name) ? HologramEditorNativeTheme.PRIMARY_BUTTON_HEIGHT
                    : HologramEditorNativeTheme.TEXT_BUTTON_HEIGHT)
            .name("cut_storage_" + name);
        register("cut." + name, button);
        return button;
    }

    private void register(String key, IWidget widget) {
        targets.put(key, widget);
        ui.shell.registerTarget(key, widget);
    }

    Map<String, IWidget> targets() {
        return Collections.unmodifiableMap(targets);
    }

    IWidget scene() {
        return browser == null ? null : browser.camera;
    }

    HologramCutStorageBrowserView browser() {
        return browser;
    }

    private HologramCutClient client() {
        return HologramCutClient.INSTANCE;
    }

    private boolean canAct() {
        return active && !closed
            && !isolated
            && !ui.session.isTutorial()
            && client().canUseStorageEntry(selection.selected());
    }

    private boolean canPlace() {
        return isolated ? active && !closed && lessonPlace != null && lessonCanPlace.getAsBoolean() : canAct();
    }

    private void place() {
        if (!canPlace()) return;
        if (isolated) lessonPlace.run();
        else client().beginStoragePlacement(selection.selected());
    }

    void activate(boolean value) {
        if (closed) return;
        active = value;
        if (!value || isolated) return;
        if (live == null) {
            live = new HologramCutStoragePreview();
            live.select(selected);
        }
        if (!directory.equals(client().entries())) refreshDirectory();
        if (!activatedOnce && client().entries()
            .isEmpty() && !client().syncingDirectory()) client().refreshDirectory();
        activatedOnce = true;
    }

    void tick() {
        if (!active || closed || browser == null) return;
        if (isolated) return;
        if (!directory.equals(client().entries())) refreshDirectory();
        if (live != null) live.tick(!client().busy());
    }

    private void refreshDirectory() {
        directory.clear();
        for (NBTTagCompound entry : client().entries()) directory.add((NBTTagCompound) entry.copy());
        String id = selection.selected();
        boolean found = false;
        for (NBTTagCompound entry : directory) if (id.equals(entry.getString("id"))) found = true;
        if (!found) {
            id = client().selected();
            found = false;
            for (NBTTagCompound entry : directory) if (id.equals(entry.getString("id"))) found = true;
            selection.select(
                found ? id
                    : directory.isEmpty() ? ""
                        : directory.get(0)
                            .getString("id"));
            if (browser != null) browser.camera.resetCamera();
        }
        if (!confirmUndo.equals(selection.selected())) confirmUndo = "";
        if (browser != null) browser.refreshRows();
    }

    private void refresh() {
        if (!active || closed) return;
        if (isolated) lessonRefresh.run();
        else {
            client().refreshDirectory();
            if (live != null) live.retry();
        }
        browser.refreshRows();
    }

    void close() {
        if (closed) return;
        closed = true;
        active = false;
        if (live != null) live.close();
        if (browser != null) browser.camera.releaseTarget();
    }

    private final class Progress extends Widget<Progress> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            HologramProgress progress = isolated ? null : client().progress(true);
            if (live != null && live.progress() != null && !client().busy()) progress = live.progress();
            if (progress != null && !progress.complete) {
                HologramProgressView.draw(Minecraft.getMinecraft().fontRenderer, progress, 0, 0, getArea().w(), 2);
            } else Minecraft.getMinecraft().fontRenderer.drawSplitString(
                isolated ? selection.notice() : client().status(),
                2,
                2,
                Math.max(20, getArea().w() - 4),
                HologramEditorNativeTheme.MUTED);
        }
    }

    HologramProgress progress() {
        if (isolated || !active || closed) return null;
        HologramProgress progress = client().progress(true);
        return live != null && live.progress() != null && !client().busy() ? live.progress() : progress;
    }
}
