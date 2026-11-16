// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.miaokatze.gtmb.hologram.HologramLibraryPermissions;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual production ADMIN widget tree and callbacks; no window, GL frame or TCP connection is claimed. */
public final class HologramLibraryAdminUiRegression {

    private HologramLibraryAdminUiRegression() {}

    @SuppressWarnings("unchecked")
    public static void run() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Field singleton = null;
        for (Field field : Minecraft.class.getDeclaredFields())
            if (field.getType() == Minecraft.class && java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                singleton = field;
        if (singleton == null) throw new NoSuchFieldException("Minecraft singleton");
        singleton.setAccessible(true);
        Object beforeMinecraft = singleton.get(null);
        HologramLibraryClient client = HologramLibraryClient.INSTANCE;
        NBTTagCompound beforeConfig = client.config();
        try {
            Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
            net.minecraft.client.gui.FontRenderer font = (net.minecraft.client.gui.FontRenderer) unsafe
                .allocateInstance(net.minecraft.client.gui.FontRenderer.class);
            int[] widths = new int[256];
            java.util.Arrays.fill(widths, 6);
            byte[] glyphs = new byte[65536];
            java.util.Arrays.fill(glyphs, (byte) 7);
            field(net.minecraft.client.gui.FontRenderer.class, "charWidth").set(font, widths);
            field(net.minecraft.client.gui.FontRenderer.class, "glyphWidth").set(font, glyphs);
            font.FONT_HEIGHT = 9;
            minecraft.fontRenderer = font;
            minecraft.theWorld = (WorldClient) unsafe.allocateInstance(WorldClient.class);
            singleton.set(null, minecraft);
            HologramScreen screen = (HologramScreen) unsafe.allocateInstance(HologramScreen.class);
            field(HologramMuiCanvasScreen.class, "fields").set(screen, new ArrayList<>());
            HologramUiModules ui = new HologramUiModules(screen);
            screen.ui = ui;
            field(HologramUiSession.class, "state").set(ui.session, new HologramState(new NBTTagCompound()));
            NBTTagCompound config = new NBTTagCompound();
            config.setBoolean("admin", true);
            config.setLong("configRevision", 41);
            config.setInteger("permissionGroupsVersion", HologramLibraryPermissions.VERSION);
            config.setInteger("uploadKiB", 128);
            config.setInteger("downloadKiB", 512);
            config.setInteger("maxUploads", 10);
            config.setTag(
                "permissionGroups",
                HologramLibraryPermissions.defaults(10)
                    .tag());
            field(HologramLibraryClient.class, "config").set(client, config);
            ui.library.openLibraryAdmin();
            HologramLibraryAdminEditor editor = ui.library.adminEditor();
            SimpleAssert.that(editor != null, "production library entry creates permission editor");
            ui.library.openLibraryAdmin();
            SimpleAssert.that(
                ui.library.adminEditor() == editor,
                "reopening the retained ADMIN handler keeps the same field owner");
            HologramEditorNativeLibraryDialogs dialogs = new HologramEditorNativeLibraryDialogs(ui);
            Class<?> kind = Class.forName(HologramEditorNativeLibraryDialogs.class.getName() + "$Kind");
            Method build = HologramEditorNativeLibraryDialogs.class.getDeclaredMethod("build", kind);
            build.setAccessible(true);
            ModularPanel panel = (ModularPanel) build.invoke(dialogs, kind.getEnumConstants()[3]);
            Map<String, List<IWidget>> targets = (Map<String, List<IWidget>>) field(
                HologramEditorShell.class,
                "targets").get(ui.shell);
            SimpleAssert.that(panel.disablePanelsBelow(), "production ADMIN owns the modal root");
            SimpleAssert.that(
                editor.upload.nativeOwned() && editor.download.nativeOwned() && editor.quota.nativeOwned(),
                "the real ADMIN panel adopts editor fields instead of stale scalar copies");
            SimpleAssert.that(
                targets.containsKey("library.permissions.member") && targets.containsKey("library.permissions.name"),
                "native group name and UUID inputs are part of the actual dialog tree");
            click(targets, "library.permissions.action.18002");
            ((HologramMuiTextField) field(HologramLibraryAdminEditor.class, "name").get(editor)).setText("玩家组");
            editor.quota.setText("2");
            click(targets, "library.permissions.action.18005");
            UUID member = UUID.randomUUID();
            HologramMuiTextField memberField = (HologramMuiTextField) field(HologramLibraryAdminEditor.class, "member")
                .get(editor);
            memberField.setText(member.toString());
            click(targets, "library.permissions.action.18006");
            click(targets, "control.210");
            SimpleAssert
                .that(client.configSavePending(), "real native save button reaches the actual client write owner");
            NBTTagCompound draft = editor.state();
            HologramLibraryPermissions saved = HologramLibraryPermissions
                .read(draft.getTagList("permissionGroups", 10));
            SimpleAssert.that(
                !saved.effective(member).download && saved.effective(member).quota == 2,
                "native group capability, quota, name and member controls submit one draft");
            NBTTagCompound ack = (NBTTagCompound) config.copy();
            ack.setString("op", "config");
            ack.setLong("configRevision", 42);
            ack.setTag("permissionGroups", saved.tag());
            ack.setString(
                "configRequestId",
                String.valueOf(field(HologramLibraryClient.class, "pendingConfigRequestId").get(client)));
            Method receive = HologramLibraryClient.class.getDeclaredMethod("receive", NBTTagCompound.class);
            receive.setAccessible(true);
            receive.invoke(client, ack);
            editor.tick();
            SimpleAssert.that(
                !client.configSavePending() && editor.state()
                    .getLong("configRevision") == 42,
                "matched server acknowledgement refreshes the same native fields and revision");
            click(targets, "library.permissions.action.18001");
            memberField.setText(member.toString());
            click(targets, "library.permissions.action.18007");
            HologramLibraryPermissions removed = HologramLibraryPermissions.read(
                editor.state()
                    .getTagList("permissionGroups", 10));
            SimpleAssert.eq(
                HologramLibraryPermissions.DEFAULT,
                removed.effective(member).id,
                "native member removal returns UUID to default policy");
            click(targets, "library.permissions.action.18003");
            SimpleAssert.eq(
                1,
                editor.state()
                    .getTagList("permissionGroups", 10)
                    .tagCount(),
                "native group deletion keeps the default");
            ack.setLong("configRevision", 43);
            receive.invoke(client, ack);
            SimpleAssert.that(
                !editor.canSave() && editor.state()
                    .getLong("configRevision") == 42,
                "concurrent server broadcast leaves editable snapshot intact and disables stale native save");
        } finally {
            field(HologramLibraryClient.class, "config").set(client, beforeConfig);
            field(HologramLibraryClient.class, "configWritePending").set(client, false);
            singleton.set(null, beforeMinecraft);
        }
    }

    private static void click(Map<String, List<IWidget>> targets, String key) {
        List<IWidget> widgets = targets.get(key);
        SimpleAssert.that(widgets != null && !widgets.isEmpty(), "native target exists: " + key);
        ButtonWidget<?> button = (ButtonWidget<?>) widgets.get(widgets.size() - 1);
        button.playClickSound(false);
        button.onMousePressed(0);
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
