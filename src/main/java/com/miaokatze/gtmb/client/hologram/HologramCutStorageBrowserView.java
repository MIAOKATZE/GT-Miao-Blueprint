package com.miaokatze.gtmb.client.hologram;

import java.util.List;
import java.util.function.BiConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;

/** The storage screen and its lesson share the actual list, row callbacks, details and raw-preview widget. */
final class HologramCutStorageBrowserView extends ParentWidget<HologramCutStorageBrowserView> {

    interface Selection {

        List<NBTTagCompound> entries();

        String selected();

        void select(String id);

        String notice();
    }

    private final int panelWidth;
    private final Selection selection;
    private final BiConsumer<String, IWidget> target;
    final ListWidget<IWidget, ?> list;
    final HologramCutStoragePreviewWidget camera;

    HologramCutStorageBrowserView(int width, int height, Selection selection, HologramCutStoragePreviewView preview,
        BiConsumer<String, IWidget> target) {
        panelWidth = width;
        this.selection = selection;
        this.target = target;
        size(width, height);
        int leftWidth = (width - 34) * 34 / 100;
        int rightX = leftWidth + 22, rightWidth = width - rightX - 12;
        int bodyTop = 54, bodyHeight = height - 112;
        list = new HologramRecessedList().pos(12, bodyTop)
            .size(leftWidth, bodyHeight)
            .background(HologramMuiTheme.PANEL)
            .padding(4)
            .name("cut_storage_list");
        child(list);
        camera = new HologramCutStoragePreviewWidget(preview).pos(rightX, bodyTop)
            .size(rightWidth, Math.max(28, bodyHeight - 36))
            .name("cut_storage_preview");
        child(camera);
        child(
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
        child(
            HologramMuiTheme.label(
                () -> details(selectedEntry()),
                rightX,
                bodyTop + bodyHeight - 17,
                rightWidth,
                13,
                HologramMuiTheme.MUTED));
        child(
            HologramMuiTheme
                .label(
                    () -> trim(selection.notice(), rightWidth),
                    rightX,
                    bodyTop + bodyHeight + 3,
                    rightWidth,
                    12,
                    HologramMuiTheme.MUTED)
                .tooltipBuilder(t -> t.addLine(selection.notice()))
                .tooltipAutoUpdate(true));
        register("cut.list", list);
        register("cut.preview", camera);
        refreshRows();
    }

    private void register(String name, IWidget widget) {
        if (target != null) target.accept(name, widget);
    }

    void refreshRows() {
        list.removeAll();
        if (selection.entries()
            .isEmpty()) {
            list.child(
                IKey.dynamic(() -> HologramCutStoragePreview.text("empty"))
                    .color(HologramEditorTheme.MUTED)
                    .shadow(false)
                    .asWidget()
                    .widthRel(1f)
                    .height(30));
            return;
        }
        for (NBTTagCompound entry : selection.entries()) {
            String id = entry.getString("id");
            ButtonWidget<?> row = new ButtonWidget<>().height(36)
                .widthRel(1f)
                .marginBottom(4)
                .background(
                    HologramMuiTheme.row(
                        () -> selection.selected()
                            .equals(id),
                        false))
                .hoverBackground(
                    HologramMuiTheme.row(
                        () -> selection.selected()
                            .equals(id),
                        true))
                .child(
                    IKey.dynamic(() -> rowText(entry))
                        .color(
                            () -> selection.selected()
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
            register("cut.row." + id, row);
        }
    }

    void choose(String id) {
        if (!selection.selected()
            .equals(id)) {
            selection.select(id);
            camera.resetCamera();
        }
    }

    private String rowText(NBTTagCompound entry) {
        int width = Math.max(24, (panelWidth - 34) * 34 / 100 - 24);
        return trim("#" + entry.getLong("sequence") + "  " + entry.getString("name"), width) + "\n\u00a77"
            + trim(details(entry), width);
    }

    private NBTTagCompound selectedEntry() {
        for (NBTTagCompound entry : selection.entries()) if (selection.selected()
            .equals(entry.getString("id"))) return entry;
        return null;
    }

    private String selectedName() {
        NBTTagCompound entry = selectedEntry();
        return entry == null ? "" : "#" + entry.getLong("sequence") + "  " + entry.getString("name");
    }

    private static String details(NBTTagCompound entry) {
        return entry == null ? ""
            : entry.getInteger("sizeX") + " × "
                + entry.getInteger("sizeY")
                + " × "
                + entry.getInteger("sizeZ")
                + " · DIM "
                + entry.getInteger("dimension");
    }

    private static String trim(String value, int width) {
        return Minecraft.getMinecraft().fontRenderer.trimStringToWidth(value, Math.max(20, width - 4));
    }
}
