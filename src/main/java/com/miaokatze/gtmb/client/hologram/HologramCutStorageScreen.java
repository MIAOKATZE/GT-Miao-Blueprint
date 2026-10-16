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

    HologramCutStorageScreen() {
        this(Math.min(456, resolution().getScaledWidth() - 12), Math.min(306, resolution().getScaledHeight() - 12));
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
        int leftWidth = (panelWidth - 28) * 46 / 100;
        int rightX = leftWidth + 18, rightWidth = panelWidth - rightX - 10;
        int bodyTop = 48, bodyHeight = panelHeight - 108;
        panel().child(HologramMuiTheme.label(() -> text("title"), 10, 9, panelWidth - 20, 14, 0xfff0ddb3));
        panel().child(HologramMuiTheme.label(() -> text("hint"), 10, 26, panelWidth - 20, 18, 0xffd4c5a8));
        list = new ListWidget<>().pos(10, bodyTop)
            .size(leftWidth, bodyHeight)
            .background(HologramMuiTheme.PANEL)
            .padding(3);
        panel().child(list);
        camera = new HologramCutStoragePreviewWidget(preview).pos(rightX, bodyTop)
            .size(rightWidth, Math.max(28, bodyHeight - 54));
        panel().child(camera);
        panel().child(
            HologramMuiTheme
                .label(
                    () -> trim(selectedName(), rightWidth),
                    rightX,
                    bodyTop + bodyHeight - 50,
                    rightWidth,
                    12,
                    0xfff0ddb3)
                .tooltipBuilder(t -> t.addLine(selectedName()))
                .tooltipAutoUpdate(true));
        panel().child(
            HologramMuiTheme
                .label(this::selectedDetails, rightX, bodyTop + bodyHeight - 36, rightWidth, 22, 0xffd4c5a8));
        panel().child(
            HologramMuiTheme
                .label(
                    () -> trim(previewNotice(), rightWidth),
                    rightX,
                    bodyTop + bodyHeight - 13,
                    rightWidth,
                    12,
                    0xffffc88a)
                .tooltipBuilder(t -> t.addLine(previewNotice()))
                .tooltipAutoUpdate(true));
        int gap = 4, bw = (panelWidth - 20 - gap * 3) / 4, y = panelHeight - 53;
        panel().child(
            HologramMuiTheme.button(() -> text("place"), 10, y, bw, 20, this::place)
                .name("cut_storage_place"));
        panel().child(
            HologramMuiTheme.button(() -> text("undo"), 10 + bw + gap, y, bw, 20, this::undo)
                .name("cut_storage_undo"));
        panel().child(HologramMuiTheme.button(() -> text("sync"), 10 + (bw + gap) * 2, y, bw, 20, () -> {
            client().refreshDirectory();
            preview.retry();
        })
            .name("cut_storage_sync"));
        panel().child(
            HologramMuiTheme
                .button(() -> text("close"), 10 + (bw + gap) * 3, y, bw, 20, () -> mc.displayGuiScreen(null))
                .name("cut_storage_close"));
        panel().child(
            new StorageProgress().pos(10, panelHeight - 27)
                .size(panelWidth - 20, 20));
        refreshRows();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (list == null) return;
        if (!directory.equals(client().entries())) refreshRows();
        preview.tick(!client().busy());
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
                    .color(0xffd4c5a8)
                    .asWidget()
                    .widthRel(1f)
                    .height(30));
            return;
        }
        for (NBTTagCompound entry : directory) {
            String id = entry.getString("id");
            // No explicit Y position: ListWidget owns layout, clipping and transformed hit testing.
            ButtonWidget<?> row = new ButtonWidget<>().height(40)
                .widthRel(1f)
                .marginBottom(3)
                .background(HologramMuiTheme.BUTTON)
                .hoverBackground(HologramMuiTheme.PRESSED)
                .overlay(
                    IKey.dynamic(() -> rowText(entry))
                        .color(0xfff0ddb3))
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
        String mark = preview.state.selected()
            .equals(entry.getString("id")) ? "\u00a76\u25b6 " : "";
        int width = Math.max(24, (panelWidth - 28) * 46 / 100 - 24);
        return mark + trim(entry.getString("name"), width)
            + "\n\u00a77"
            + dimensions(entry)
            + "\nDIM "
            + entry.getInteger("dimension");
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
        return entry == null ? "" : entry.getString("name");
    }

    private String selectedDetails() {
        NBTTagCompound entry = selectedEntry();
        return entry == null ? "" : dimensions(entry) + "\nDIM " + entry.getInteger("dimension");
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
            mc.displayGuiScreen(null);
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
            if (progress != null)
                HologramProgressView.draw(Minecraft.getMinecraft().fontRenderer, progress, 0, 0, getArea().w(), 3);
            else Minecraft.getMinecraft().fontRenderer.drawString(
                Minecraft.getMinecraft().fontRenderer.trimStringToWidth(client().status(), getArea().w()),
                0,
                0,
                0xffe8c58c);
        }
    }
}
