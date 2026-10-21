package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;

/** Read-only directory selection and an isolated raw camera; only explicit actions change the world flow. */
final class HologramCutStorageScreen extends HologramMuiScreen {

    private final int panelWidth, panelHeight;
    private final HologramCutStoragePreview preview = new HologramCutStoragePreview();
    private final List<NBTTagCompound> directory = new ArrayList<>();
    private ListWidget<IWidget, ?> list;
    private HologramCutStoragePreviewWidget camera;
    private boolean autoTutorialChecked, tutorialOpening;

    HologramCutStorageScreen() {
        this(Math.min(456, resolution().getScaledWidth() - 12), Math.min(292, resolution().getScaledHeight() - 12));
    }

    private HologramCutStorageScreen(int width, int height) {
        super("cut_storage", width, height);
        panelWidth = width;
        panelHeight = height;
    }

    private static ScaledResolution resolution() {
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    private HologramCutClient client() {
        return HologramCutClient.INSTANCE;
    }

    private static String text(String key) {
        return HologramCutStoragePreview.text(key);
    }

    @Override
    protected void buildPanel() {
        int leftWidth = (panelWidth - 34) * 34 / 100;
        int rightX = leftWidth + 22, rightWidth = panelWidth - rightX - 12;
        int bodyTop = 54, bodyHeight = panelHeight - 112;
        HologramMuiTheme
            .header(panel(), () -> trim(text("title") + " · " + directory.size(), panelWidth - 124), panelWidth);
        panel().child(
            HologramMuiTheme
                .button(() -> "剪切教程", panelWidth - 83, 8, 71, 20, () -> deferAction(() -> openTutorial(true)))
                .name("cut_storage_tutorial"));
        panel().child(HologramMuiTheme.label(() -> text("hint"), 12, 35, panelWidth - 24, 15, HologramMuiTheme.MUTED));
        list = new HologramRecessedList().pos(12, bodyTop)
            .size(leftWidth, bodyHeight)
            .background(HologramMuiTheme.PANEL)
            .padding(4);
        panel().child(list);
        camera = new HologramCutStoragePreviewWidget(preview).pos(rightX, bodyTop)
            .size(rightWidth, Math.max(28, bodyHeight - 36));
        panel().child(camera);
        panel().child(
            HologramMuiTheme
                .label(
                    () -> trim(selectedName(), rightWidth),
                    rightX,
                    bodyTop + bodyHeight - 30,
                    rightWidth,
                    12,
                    HologramMuiTheme.TITLE)
                .tooltipBuilder(t -> t.addLine(selectedName()))
                .tooltipAutoUpdate(true));
        panel().child(
            HologramMuiTheme.label(
                this::selectedDetails,
                rightX,
                bodyTop + bodyHeight - 17,
                rightWidth,
                13,
                HologramMuiTheme.MUTED));
        panel().child(
            HologramMuiTheme
                .label(
                    () -> trim(previewNotice(), rightWidth),
                    rightX,
                    bodyTop + bodyHeight + 3,
                    rightWidth,
                    12,
                    HologramMuiTheme.MUTED)
                .tooltipBuilder(t -> t.addLine(previewNotice()))
                .tooltipAutoUpdate(true));
        int gap = 5, bw = (panelWidth - 24 - gap * 3) / 4, y = panelHeight - 43;
        panel().child(
            HologramMuiTheme.primaryButton(() -> text("place"), 12, y, bw, 20, this::place)
                .name("cut_storage_place"));
        panel().child(
            HologramMuiTheme.button(() -> text("undo"), 12 + bw + gap, y, bw, 20, this::undo)
                .name("cut_storage_undo"));
        panel().child(HologramMuiTheme.button(() -> text("sync"), 12 + (bw + gap) * 2, y, bw, 20, () -> {
            client().refreshDirectory();
            preview.retry();
        })
            .name("cut_storage_sync"));
        panel().child(
            HologramMuiTheme
                .button(() -> text("close"), 12 + (bw + gap) * 3, y, bw, 20, () -> mc.displayGuiScreen(null))
                .name("cut_storage_close"));
        panel().child(
            new StorageProgress().pos(12, panelHeight - 18)
                .size(panelWidth - 24, 16));
        refreshRows();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (list == null) return;
        if (!directory.equals(client().entries())) refreshRows();
        if (!autoTutorialChecked) {
            autoTutorialChecked = true;
            if (!HologramEditorPreferences.get(mc.mcDataDir)
                .seen("cut")) deferAction(() -> openTutorial(false));
        }
        preview.tick(!client().busy());
    }

    /** MUI's child-screen stack retains this list, its selection and the same camera widget. */
    private void openTutorial(boolean replay) {
        if (mc.currentScreen != this || tutorialOpening) return;
        tutorialOpening = true;
        try {
            HologramScreen lesson = HologramScreen.detachedTutorial("cut", replay, () -> {
                tutorialOpening = false;
                mc.displayGuiScreen(this);
            });
            lesson.openFrom(this);
            if (mc.currentScreen != lesson) tutorialOpening = false;
        } catch (RuntimeException failure) {
            tutorialOpening = false;
            throw failure;
        }
    }

    private void refreshRows() {
        directory.clear();
        for (NBTTagCompound summary : client().entries()) directory.add((NBTTagCompound) summary.copy());
        String selected = preview.state.selected();
        boolean found = false;
        for (NBTTagCompound entry : directory) if (selected.equals(entry.getString("id"))) found = true;
        if (!found) {
            String initial = client().selected();
            found = false;
            for (NBTTagCompound entry : directory) if (initial.equals(entry.getString("id"))) found = true;
            choose(
                found ? initial
                    : directory.isEmpty() ? ""
                        : directory.get(0)
                            .getString("id"));
        }
        list.removeAll();
        if (directory.isEmpty()) {
            list.child(
                IKey.dynamic(() -> text("empty"))
                    .color(HologramEditorTheme.MUTED)
                    .shadow(false)
                    .asWidget()
                    .widthRel(1f)
                    .height(30));
            return;
        }
        for (NBTTagCompound entry : directory) {
            String id = entry.getString("id");
            // No explicit Y position: ListWidget owns layout, clipping and transformed hit testing.
            ButtonWidget<?> row = new ButtonWidget<>().height(36)
                .widthRel(1f)
                .marginBottom(4)
                .background(
                    HologramMuiTheme.row(
                        () -> preview.state.selected()
                            .equals(id),
                        false))
                .hoverBackground(
                    HologramMuiTheme.row(
                        () -> preview.state.selected()
                            .equals(id),
                        true))
                .child(
                    IKey.dynamic(() -> rowText(entry))
                        .color(
                            () -> preview.state.selected()
                                .equals(id) ? HologramMuiTheme.TITLE : HologramMuiTheme.TEXT)
                        .shadow(false)
                        .asWidget()
                        .textAlign(Alignment.CenterLeft)
                        .sizeRel(1f)
                        .padding(6))
                .name("cut_entry_" + id)
                .onMousePressed(button -> {
                    if (button != 0) return false;
                    choose(id);
                    return true;
                });
            row.tooltipBuilder(t -> {
                t.addLine(entry.getString("name"));
                t.addLine(details(entry));
                t.addLine(id);
            });
            list.child(row);
        }
    }

    private String rowText(NBTTagCompound entry) {
        int width = Math.max(24, (panelWidth - 34) * 34 / 100 - 24);
        return trim("#" + entry.getLong("sequence") + "  " + entry.getString("name"), width) + "\n\u00a77"
            + trim(details(entry), width);
    }

    private void choose(String id) {
        if (!preview.state.selected()
            .equals(id)) {
            preview.select(id);
            if (camera != null) camera.resetCamera();
        }
    }

    private NBTTagCompound selectedEntry() {
        for (NBTTagCompound entry : directory) if (preview.state.selected()
            .equals(entry.getString("id"))) return entry;
        return null;
    }

    private String selectedName() {
        NBTTagCompound entry = selectedEntry();
        return entry == null ? "" : "#" + entry.getLong("sequence") + "  " + entry.getString("name");
    }

    private String selectedDetails() {
        NBTTagCompound entry = selectedEntry();
        return entry == null ? "" : details(entry);
    }

    private static String details(NBTTagCompound entry) {
        return dimensions(entry) + " · DIM " + entry.getInteger("dimension");
    }

    private static String dimensions(NBTTagCompound entry) {
        return entry.getInteger("sizeX") + " × " + entry.getInteger("sizeY") + " × " + entry.getInteger("sizeZ");
    }

    private static String trim(String value, int width) {
        return Minecraft.getMinecraft().fontRenderer.trimStringToWidth(value, Math.max(20, width - 4));
    }

    private String previewNotice() {
        return preview.notice()
            .isEmpty() ? text("camera") : preview.notice();
    }

    private void place() {
        if (selectedEntry() != null && !client().busy()) client().beginStoragePlacement(preview.state.selected());
    }

    private void undo() {
        if (selectedEntry() != null && !client().busy()) client().undo(preview.state.selected());
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            client().exitCutMode();
            return true;
        }
        if (HologramCutClient.alt() && (key == Keyboard.KEY_A || key == Keyboard.KEY_D)) {
            if (!directory.isEmpty()) {
                int index = 0;
                for (int i = 0; i < directory.size(); i++) if (preview.state.selected()
                    .equals(
                        directory.get(i)
                            .getString("id")))
                    index = i;
                choose(
                    directory.get((index + (key == Keyboard.KEY_A ? -1 : 1) + directory.size()) % directory.size())
                        .getString("id"));
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onMuiClose() {
        preview.close();
    }

    private final class StorageProgress extends Widget<StorageProgress> {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
            HologramProgress progress = client().progress(true);
            if (preview.progress() != null && !client().busy()) progress = preview.progress();
            if (progress != null && !progress.complete)
                HologramProgressView.draw(Minecraft.getMinecraft().fontRenderer, progress, 0, 0, getArea().w(), 2);
            else Minecraft.getMinecraft().fontRenderer.drawString(
                Minecraft.getMinecraft().fontRenderer.trimStringToWidth(client().status(), getArea().w()),
                0,
                0,
                HologramEditorTheme.MUTED);
        }
    }
}
