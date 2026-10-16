package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real library values and bytecode structure only; native rendering is witnessed by the separate FML addon. */
public final class HologramMuiMigrationRegression {

    private static final String BASE = "com/miaokatze/gtmb/client/hologram/";
    private static final String WRAPPER = "com/cleanroommc/modularui/screen/GuiScreenWrapper";
    private static final String MODULAR = "com/cleanroommc/modularui/screen/ModularScreen";

    private HologramMuiMigrationRegression() {}

    public static void run() {
        SimpleAssert.eq(
            "gtmb:textures/gui/pocket/POCKET_C2_panel.png",
            HologramMuiTheme.PANEL.location.toString(),
            "text fields retain the existing pocket panel resource");
        SimpleAssert.eq(
            "gtmb:textures/gui/pocket/POCKET_C2_cloth.png",
            HologramMuiTheme.CLOTH.location.toString(),
            "panel retains the existing pocket cloth resource");
        SimpleAssert.eq(
            "gtmb:textures/gui/pocket/POCKET_C2_btn.png",
            HologramMuiTheme.BUTTON.location.toString(),
            "buttons retain the existing pocket button resource");
        SimpleAssert.eq(
            "gtmb:textures/gui/pocket/POCKET_C2_btn_pressed.png",
            HologramMuiTheme.PRESSED.location.toString(),
            "hover retains the existing pocket pressed resource");
        ButtonWidget<?> button = HologramMuiTheme.button(() -> "test", 0, 0, 100, 20, () -> {});
        TextFieldWidget field = HologramMuiTheme.textField(new StringValue("draft"), 0, 0, 100, 20, 64);
        SimpleAssert.that(
            button.getBackground() == HologramMuiTheme.BUTTON
                && button.getHoverBackground() == HologramMuiTheme.PRESSED,
            "actual native button backgrounds bind the shared pocket textures");
        SimpleAssert.that(
            field.getBackground() == HologramMuiTheme.PANEL,
            "actual native text field binds the shared pocket texture");
        SimpleAssert.that(
            HologramMuiTheme.panel("test", 100, 100)
                .getBackground() == HologramMuiTheme.CLOTH,
            "actual native panel binds the shared pocket cloth");
        String[] screens = { "HologramScreen", "HologramSettingsScreen", "HologramQuantumGroupsScreen",
            "HologramCutModeScreen", "HologramCutStorageScreen", "HologramCutConfirmScreen",
            "HologramCutTransformScreen", "HologramCutNameScreen", "HologramAeContainerScreen" };
        try {
            Set<String> inspected = new HashSet<>();
            for (String screen : screens) {
                ClassReader reader = reader(BASE + screen);
                SimpleAssert.that(
                    inherits(reader.getClassName(), WRAPPER) || inherits(reader.getClassName(), MODULAR),
                    screen + " uses the real MUI2 screen route");
                inspect(reader.getClassName(), inspected);
            }
            // The workstation drafts sit in workflow adapters, so inspect those owners as well as the Screens.
            for (String type : new String[] { "HologramMuiCanvasScreen", "HologramMuiTextField", "HologramUiCanvas",
                "HologramUiConfiguration", "HologramUiLibrary", "HologramUiAnnotations", "HologramUiNetworkBindings",
                "HologramUiPosition", "HologramUiProcessing", "HologramUiNbtEditor" }) inspect(BASE + type, inspected);
        } catch (IOException failure) {
            throw new AssertionError("Cannot inspect actual migration classes", failure);
        }
    }

    private static void inspect(String type, Set<String> inspected) throws IOException {
        if (!inspected.add(type) || !type.startsWith(BASE)) return;
        ClassReader reader = reader(type);
        reader.accept(new ClassVisitor(9 << 16) {

            private void reject(String value) {
                SimpleAssert.that(
                    !value.contains("net/minecraft/client/gui/GuiButton")
                        && !value.contains("net/minecraft/client/gui/GuiTextField"),
                    type + " does not retain a vanilla button/text-field path: " + value);
            }

            @Override
            public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                reject(d);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                reject(d);
                return new MethodVisitor(9 << 16) {

                    @Override
                    public void visitTypeInsn(int op, String name) {
                        reject(name);
                    }

                    @Override
                    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        reject(owner);
                        reject(desc);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        inspect(reader.getSuperName(), inspected);
    }

    private static boolean inherits(String type, String parent) throws IOException {
        return type != null && (type.equals(parent) || inherits(reader(type).getSuperName(), parent));
    }

    private static ClassReader reader(String type) throws IOException {
        try (InputStream input = HologramMuiMigrationRegression.class.getClassLoader()
            .getResourceAsStream(type + ".class")) {
            if (input == null) throw new IOException("Missing actual class " + type);
            return new ClassReader(input);
        }
    }
}
