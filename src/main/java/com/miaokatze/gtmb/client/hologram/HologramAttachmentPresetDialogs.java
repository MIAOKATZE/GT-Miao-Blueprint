package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.miaokatze.gtmb.hologram.HologramAttachmentPresets;

/** The same native MUI list and save prompt serve text and pictures. */
final class HologramAttachmentPresetDialogs {

    private HologramAttachmentPresetDialogs() {}

    private static HologramAttachmentPresets store() throws IOException {
        return new HologramAttachmentPresets(
            Minecraft.getMinecraft().mcDataDir.toPath()
                .resolve("config/gtmb-attachment-presets.nbt"));
    }

    static void confirm(HologramMuiScreen screen, boolean picture, NBTTagCompound settings, Runnable proceed) {
        try {
            HologramAttachmentPresets store = store();
            if (store.matches(picture, settings)) {
                screen.deferAction(proceed);
                return;
            }
            screen.openDialog("gtmb_attachment_save_preset", (parent, player) -> {
                Dialog<?> dialog = new Dialog<>("gtmb_attachment_save_preset", null);
                dialog.size(350, 164)
                    .background(HologramMuiTheme.CLOTH);
                HologramMuiTheme.header(dialog, () -> "是否保存为预设", 350);
                dialog
                    .child(HologramMuiTheme.label(() -> "保存当前处理参数，方便下次直接套用。", 16, 36, 318, 18, HologramMuiTheme.TEXT));
                StringValue name = new StringValue(
                    (picture ? "图片" : "字体") + "预设 "
                        + (store.rows(picture)
                            .size() + 1));
                dialog.child(HologramMuiTheme.textField(name, 16, 62, 318, 22, 48));
                String[] error = { "" };
                dialog.child(HologramMuiTheme.label(() -> error[0], 16, 90, 318, 28, HologramMuiTheme.TITLE));
                dialog.child(HologramMuiTheme.primaryButton(() -> "保存", 16, 126, 150, 22, () -> {
                    try {
                        store.save(picture, name.getStringValue(), settings);
                        dialog.closeIfOpen();
                        screen.deferAction(proceed);
                    } catch (IOException failure) {
                        error[0] = failure.getMessage();
                    }
                }));
                dialog.child(HologramMuiTheme.button(() -> "不保存", 184, 126, 150, 22, () -> {
                    dialog.closeIfOpen();
                    screen.deferAction(proceed);
                }));
                return dialog;
            });
        } catch (IOException failure) {
            error(screen, failure.getMessage());
        }
    }

    static void open(HologramMuiScreen screen, boolean picture, Consumer<NBTTagCompound> apply) {
        try {
            HologramAttachmentPresets store = store();
            screen.openDialog("gtmb_attachment_preset_list", (parent, player) -> {
                Dialog<?> dialog = new Dialog<>("gtmb_attachment_preset_list", null);
                dialog.size(400, 310)
                    .background(HologramMuiTheme.CLOTH);
                HologramMuiTheme.header(dialog, () -> picture ? "图片处理预设" : "字体处理预设", 400);
                String[] error = { "" };
                HologramRecessedList list = new HologramRecessedList().pos(14, 36)
                    .size(372, 214);
                for (HologramAttachmentPresets.Entry entry : store.rows(picture)) {
                    ParentWidget<?> row = new ParentWidget<>().size(350, 26)
                        .setEnabledIf(widget -> !entry.deleted);
                    row.child(
                        HologramMuiTheme.button(
                            () -> entry.name.length() > 20 ? entry.name.substring(0, 20) + "…" : entry.name,
                            4,
                            2,
                            226,
                            22,
                            () -> {
                                try {
                                    apply.accept((NBTTagCompound) entry.settings.copy());
                                    dialog.closeIfOpen();
                                } catch (RuntimeException failure) {
                                    error[0] = failure.getMessage();
                                }
                            })
                            .tooltipBuilder(tooltip -> tooltip.addLine(entry.name))
                            .tooltipAutoUpdate(true));
                    row.child(HologramMuiTheme.button(() -> "改名", 236, 2, 50, 22, () -> rename(screen, store, entry)));
                    row.child(HologramMuiTheme.button(() -> "删除", 292, 2, 50, 22, () -> {
                        try {
                            store.delete(entry);
                        } catch (IOException failure) {
                            error[0] = failure.getMessage();
                        }
                    }));
                    list.child(row);
                }
                dialog.child(list);
                dialog.child(
                    HologramMuiTheme.label(
                        () -> store.rows(picture)
                            .isEmpty() ? "还没有预设；确认内容时可以保存。" : error[0],
                        14,
                        254,
                        372,
                        22,
                        HologramMuiTheme.TITLE));
                dialog.child(HologramMuiTheme.button(() -> "返回编辑", 110, 280, 180, 22, dialog::closeIfOpen));
                return dialog;
            });
        } catch (IOException failure) {
            error(screen, failure.getMessage());
        }
    }

    private static void rename(HologramMuiScreen screen, HologramAttachmentPresets store,
        HologramAttachmentPresets.Entry entry) {
        screen.openDialog("gtmb_attachment_rename_preset", (parent, player) -> {
            Dialog<?> dialog = new Dialog<>("gtmb_attachment_rename_preset", null);
            dialog.size(320, 144)
                .background(HologramMuiTheme.CLOTH);
            HologramMuiTheme.header(dialog, () -> "修改预设名称", 320);
            StringValue name = new StringValue(entry.name);
            String[] error = { "" };
            dialog.child(HologramMuiTheme.textField(name, 16, 40, 288, 22, 48));
            dialog.child(HologramMuiTheme.label(() -> error[0], 16, 68, 288, 22, HologramMuiTheme.TITLE));
            dialog.child(HologramMuiTheme.primaryButton(() -> "保存名称", 16, 104, 136, 22, () -> {
                try {
                    store.rename(entry, name.getStringValue());
                    dialog.closeIfOpen();
                } catch (IOException failure) {
                    error[0] = failure.getMessage();
                }
            }));
            dialog.child(HologramMuiTheme.button(() -> "返回列表", 168, 104, 136, 22, dialog::closeIfOpen));
            return dialog;
        });
    }

    private static void error(HologramMuiScreen screen, String reason) {
        screen.openDialog("gtmb_attachment_preset_error", (parent, player) -> {
            Dialog<?> dialog = new Dialog<>("gtmb_attachment_preset_error", null);
            dialog.size(350, 130)
                .background(HologramMuiTheme.CLOTH);
            HologramMuiTheme.header(dialog, () -> "处理预设", 350);
            dialog.child(HologramMuiTheme.label(() -> reason, 16, 36, 318, 46, HologramMuiTheme.TITLE));
            dialog.child(HologramMuiTheme.button(() -> "返回编辑", 90, 94, 170, 22, dialog::closeIfOpen));
            return dialog;
        });
    }
}
