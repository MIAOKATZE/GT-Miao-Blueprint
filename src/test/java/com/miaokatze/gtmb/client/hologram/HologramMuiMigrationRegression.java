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
        semanticNativeGuidance();
        SimpleAssert.eq(
            "gtmb:textures/gui/editor/kraft/paper.png",
            HologramMuiTheme.PANEL.location.toString(),
            "native panels bind the editor paper surface");
        SimpleAssert.eq(
            "gtmb:textures/gui/editor/kraft/paper.png",
            HologramMuiTheme.CLOTH.location.toString(),
            "the shared panel surface uses the editor paper resource");
        SimpleAssert.eq(
            "gtmb:textures/gui/editor/kraft/button.png",
            HologramMuiTheme.BUTTON.location.toString(),
            "native button policy uses the editor pixel texture");
        SimpleAssert.eq(
            "gtmb:textures/gui/editor/kraft/button-hover.png",
            HologramMuiTheme.PRESSED.location.toString(),
            "the compatibility hover token uses the actual editor hover texture");
        ButtonWidget<?> button = HologramMuiTheme.button(() -> "test", 0, 0, 100, 20, () -> {});
        TextFieldWidget field = HologramMuiTheme.textField(new StringValue("draft"), 0, 0, 100, 20, 64);
        SimpleAssert.that(
            button.getBackground() != null && button.getHoverBackground() != null
                && button.getBackground() != button.getHoverBackground(),
            "actual native buttons have separate normal and hover render policies");
        SimpleAssert.that(
            field.getBackground() == HologramEditorTheme.INPUT,
            "actual native text fields bind the paper input surface");
        SimpleAssert.that(
            HologramMuiTheme.panel("test", 100, 100)
                .getBackground() == HologramMuiTheme.CLOTH,
            "actual native panels bind the shared editor surface");
        nativeButtonStates();
        String[] screens = { "HologramScreen", "HologramSettingsScreen", "HologramQuantumGroupsScreen",
            "HologramCutModeScreen", "HologramCutStorageScreen", "HologramCutConfirmScreen",
            "HologramCutTransformScreen", "HologramCutNameScreen", "HologramAeContainerScreen",
            "HologramTextEditorScreen", "HologramPictureEditorScreen" };
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
            SimpleAssert.that(
                inherits(
                    BASE + "HologramMuiTextField$NativeField",
                    "com/cleanroommc/modularui/widgets/textfield/TextFieldWidget"),
                "workflow draft adapters retain the actual MUI2 text editing and focus implementation");
        } catch (IOException failure) {
            throw new AssertionError("Cannot inspect actual migration classes", failure);
        }
    }

    private static void nativeButtonStates() {
        int[] actions = { 0 };
        boolean[] available = { true };
        ButtonWidget<?> action = HologramMuiTheme.button(() -> "action", 0, 0, 100, 20, () -> actions[0]++)
            .playClickSound(false);
        action.setEnabledIf(widget -> available[0]);
        action.onUpdate();
        action.onMousePressed(1);
        SimpleAssert.eq(0, actions[0], "a secondary click cannot invoke a primary native action");
        action.onMousePressed(0);
        SimpleAssert.eq(1, actions[0], "the actual native button invokes its bound action once");
        available[0] = false;
        action.onUpdate();
        action.onMousePressed(0);
        SimpleAssert.eq(1, actions[0], "an unavailable native action consumes input without invoking its callback");
        SimpleAssert.that(action.isEnabled(), "unavailable actions remain visible so their explanation can be read");
        available[0] = true;
        action.onUpdate();
        action.onMousePressed(0);
        SimpleAssert.eq(2, actions[0], "availability can recover without replacing the native widget");
        SimpleAssert.that(
            HologramEditorTheme.button(true, false, false) == HologramEditorTheme.BUTTON,
            "an ordinary enabled action uses its normal surface");
        SimpleAssert.that(
            HologramEditorTheme.button(true, false, true) == HologramEditorTheme.HOVER,
            "an enabled action has a distinct hover surface");
        SimpleAssert.that(
            HologramEditorTheme.button(true, true, false) == HologramEditorTheme.PRESSED,
            "selection remains visible without hovering");
        SimpleAssert.that(
            HologramEditorTheme.button(true, true, true) == HologramEditorTheme.SELECTED_HOVER,
            "selection remains distinguishable while hovering");
        for (boolean selected : new boolean[] { false, true })
            for (boolean hover : new boolean[] { false, true }) SimpleAssert.that(
                HologramEditorTheme.button(false, selected, hover) == HologramEditorTheme.DISABLED,
                "an unavailable action cannot acquire an enabled or selected surface");
    }

    private static void semanticNativeGuidance() {
        int[] oldAccents = { 0xef7b7b, 0x68dded, 0x86baff, 0x80efaa };
        int[] colors = { HologramUiAccent.WARNING, HologramUiAccent.SUGGESTION, HologramUiAccent.INFORMATION,
            HologramUiAccent.NEXT };
        int[] paperColors = { HologramEditorTheme.PAPER_WARNING, HologramEditorTheme.PAPER_SUGGESTION,
            HologramEditorTheme.PAPER_INFORMATION, HologramEditorTheme.PAPER_NEXT };
        Set<Integer> paperSemantics = new HashSet<>();
        for (int i = 0; i < oldAccents.length; i++) {
            SimpleAssert.eq(
                colors[i],
                HologramUiAccent.textColor(oldAccents[i], true),
                "native highlighted text preserves the distinct guidance semantic " + i);
            SimpleAssert.eq(
                colors[i],
                HologramUiAccent.borderColor(oldAccents[i], true),
                "the frame retains the same semantic color source as its label " + i);
            SimpleAssert.eq(
                paperColors[i],
                HologramEditorTheme.textColor(oldAccents[i], false),
                "paper labels retain a readable version of guidance semantic " + i);
            paperSemantics.add(paperColors[i]);
            SimpleAssert.that(
                colors[i] != HologramMuiTheme.TITLE,
                "highlighted actions never collapse into the ordinary orange trim");
            SimpleAssert.eq(
                0,
                HologramUiAccent.borderColor(oldAccents[i], false),
                "an unavailable action cannot advertise itself as an active next step");
            SimpleAssert.eq(
                HologramMuiTheme.MUTED,
                HologramUiAccent.textColor(oldAccents[i], false),
                "an unavailable native label cannot retain an active guidance color");
        }
        SimpleAssert.eq(4, paperSemantics.size(), "warning, suggestion, information and next remain distinct on paper");
        SimpleAssert.that(HologramUiAccent.BORDER_WIDTH >= 2, "semantic guidance covers a visible two-pixel edge");
        try {
            int[] calls = new int[6];
            reader(BASE + "HologramMuiCanvasScreen$NativeButton").accept(new ClassVisitor(9 << 16) {

                @Override
                public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                    return new MethodVisitor(9 << 16) {

                        @Override
                        public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            if (owner.equals(BASE + "HologramMuiTheme") && name.equals("guidanceBackground"))
                                calls[0]++;
                            if (owner.equals(BASE + "HologramUiAccent") && name.equals("textColor")) calls[1]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            reader(BASE + "HologramMuiTheme").accept(new ClassVisitor(9 << 16) {

                @Override
                public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                    boolean guidance = n.startsWith("lambda$guidanceBackground");
                    boolean nativeButton = n.startsWith("lambda$button");
                    if (!guidance && !nativeButton) return null;
                    return new MethodVisitor(9 << 16) {

                        @Override
                        public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            if (owner.equals(BASE + "HologramEditorTheme") && name.equals("drawGuidanceFrame"))
                                calls[2]++;
                            if (owner.equals(BASE + "HologramEditorTheme") && name.equals("button"))
                                calls[guidance ? 3 : 4]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            reader(BASE + "HologramEditorTheme").accept(new ClassVisitor(9 << 16) {

                @Override
                public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                    if (!n.equals("drawGuidanceFrame")) return null;
                    return new MethodVisitor(9 << 16) {

                        @Override
                        public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            if (owner.equals("com/cleanroommc/modularui/drawable/GuiDraw") && name.equals("drawRect"))
                                calls[5]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            SimpleAssert.eq(2, calls[0], "actual native normal and hover backgrounds both bind the semantic frame");
            SimpleAssert.eq(1, calls[1], "actual native overlay reads the semantic text color");
            SimpleAssert.eq(1, calls[2], "actual guidance backgrounds delegate to the editor pixel frame renderer");
            SimpleAssert.eq(1, calls[3], "guidance surfaces use the enabled and hover-aware editor button policy");
            SimpleAssert.eq(2, calls[4], "actual native normal and hover renderers both use the editor button policy");
            SimpleAssert
                .that(calls[5] >= 4, "the actual pixel guidance renderer retains edge drawing for all four sides");
        } catch (IOException failure) {
            throw new AssertionError("Cannot inspect native guidance rendering", failure);
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
