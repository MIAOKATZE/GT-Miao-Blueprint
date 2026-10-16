package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

/** Opt-in witness of actual server snapshot markers through the existing GuiScreen hover and mouse paths. */
public final class HologramQuantumAnnotationClientProbe {

    private HologramQuantumAnnotationClientProbe() {}

    public static String verifyActiveScreen() throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof HologramScreen))
            throw new IllegalStateException("Quantum marker probe requires the active real hologram editor");
        HologramScreen screen = (HologramScreen) mc.currentScreen;
        HologramState state = (HologramState) read(screen, "state");
        if (!state.data.getBoolean("virtual") || HologramClient.state == null
            || !state.session.equals(HologramClient.state.session)
            || (Boolean) read(screen, "captureRenderSmokeAction"))
            throw new IllegalStateException(
                "Quantum marker probe requires an actual network-delivered virtual snapshot");
        if (!((String) read(screen, "pinnedAnnotation")).isEmpty())
            throw new IllegalStateException("Quantum marker probe starts with the annotation surface unpinned");
        long sequence = (Long) read(screen, "sequence"), pending = (Long) read(screen, "pendingSequence");
        screen.drawScreen(0, 0, 0);
        @SuppressWarnings("unchecked")
        List<HologramAnnotationMarkers.Marker> visible = new ArrayList<>(
            (List<HologramAnnotationMarkers.Marker>) read(screen, "visibleMarkers"));
        int checked = 0;
        for (HologramAnnotationMarkers.Marker marker : visible) {
            if (!marker.key.startsWith("quantum:")) continue;

            int[] point = (int[]) HologramUiDiagnostics.invokeDiagnostic(
                screen,
                "smokePreviewPoint",
                new Class<?>[] { int.class, int.class },
                marker.x,
                marker.y);
            screen.drawScreen(point[0], point[1], 0);
            Object tooltip = read(screen, "tooltip");
            if (tooltip == null || !tooltip.toString()
                .contains(marker.title))
                throw new IllegalStateException("Actual quantum marker hover omitted group title " + marker.key);
            String[] promptLines = marker.prompt.split("\n");
            for (String prompt : promptLines) {
                String prefix = prompt.substring(0, Math.min(12, prompt.length()));
                if (!prefix.isEmpty() && !tooltip.toString()
                    .contains(prefix))
                    throw new IllegalStateException(
                        "Actual quantum marker hover omitted its group/member hint " + marker.key);
            }
            screen.mouseClicked(point[0], point[1], 0);
            screen.mouseMovedOrUp(point[0], point[1], 0);
            screen.drawScreen(0, 0, 0);
            if (!marker.key.equals(read(screen, "pinnedAnnotation")))
                throw new IllegalStateException("Actual quantum marker click failed to pin " + marker.key);
            screen.mouseClicked(point[0], point[1], 0);
            screen.mouseMovedOrUp(point[0], point[1], 0);
            if (!((String) read(screen, "pinnedAnnotation")).isEmpty())
                throw new IllegalStateException("Actual quantum marker repeat click failed to unpin " + marker.key);
            checked++;
        }
        if (checked == 0)
            throw new IllegalStateException("No specialized quantum network marker was visibly projected");
        if (sequence != (Long) read(screen, "sequence") || pending != (Long) read(screen, "pendingSequence")
            || read(screen, "state") != state
            || mc.currentScreen != screen)
            throw new IllegalStateException(
                "Quantum annotation interaction changed server action/session/editor state");
        return "quantum groups=" + checked + " actual snapshot hover/pin/unpin; server actions=0";
    }

    private static Object read(HologramScreen screen, String name) throws ReflectiveOperationException {

        return ((Object) HologramUiDiagnostics.readDiagnosticField(screen, name));
    }
}
