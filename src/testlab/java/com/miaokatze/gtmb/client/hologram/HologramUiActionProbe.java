// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

/** Synthetic action implementations are absent from the ordinary distribution. */
public final class HologramUiActionProbe {

    private HologramUiActionProbe() {}

    public static void quantumControl(HologramQuantumGroupsScreen screen, int id) {
        int[] point = screen.smokeControlPoint(id);
        screen.mouseClicked(point[0], point[1], 0);
    }

    public static void quantumNode(HologramQuantumGroupsScreen screen, int index, boolean control)
        throws ReflectiveOperationException {
        int[] point = screen.smokeNodePoint(index);
        HologramTestLabUiDiagnostics.invokeDiagnostic(
            screen,
            "click",
            new Class<?>[] { int.class, int.class, int.class, boolean.class },
            point[0],
            point[1],
            0,
            control);
    }

    public static void quantumKey(HologramQuantumGroupsScreen screen, int key, boolean control)
        throws ReflectiveOperationException {
        ((HologramMuiTextField) HologramTestLabUiDiagnostics.readDiagnosticField(screen, "nameField"))
            .setFocused(false);
        HologramTestLabUiDiagnostics.invokeDiagnostic(
            screen,
            "key",
            new Class<?>[] { char.class, int.class, boolean.class },
            '\0',
            key,
            control);
    }

    public static void quantumName(HologramQuantumGroupsScreen screen, String value)
        throws ReflectiveOperationException {
        int[] point = screen.smokeNamePoint();
        screen.mouseClicked(point[0], point[1], 0);
        HologramMuiTextField field = (HologramMuiTextField) HologramTestLabUiDiagnostics
            .readDiagnosticField(screen, "nameField");
        field.setCursorPositionEnd();
        field.setSelectionPos(0);
        for (int i = 0; i < value.length(); i++) screen.keyTyped(value.charAt(i), 0);
    }

    public static void aeMouse(HologramAeContainerScreen screen, int x, int y, int button, boolean pressed) {
        screen.getContext()
            .updateState(x, y, 0);
        screen.onFrameUpdate();
        if (pressed) screen.onMousePressed(button);
        else screen.onMouseRelease(button);
    }

    public static boolean cancelWebsite() throws ReflectiveOperationException {
        net.minecraft.client.gui.GuiScreen screen = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        if (screen == null || !screen.getClass()
            .getName()
            .equals("com.miaokatze.gtmb.client.hologram.HologramBlueprintWebsite$WebsiteConfirmation")) return false;
        HologramTestLabUiDiagnostics.invokeDiagnostic(screen, "cancel", new Class<?>[0]);
        return true;
    }
}
