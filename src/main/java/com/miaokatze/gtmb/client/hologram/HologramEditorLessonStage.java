package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.miaokatze.miaokatzecore.api.camera.CameraBasis;
import com.miaokatze.miaokatzecore.api.camera.CameraPose;
import com.miaokatze.miaokatzecore.api.camera.OrthographicProjection;
import com.miaokatze.miaokatzecore.api.camera.ProjectedPoint;
import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;

/** Isolated geometric teaching stage. No world renderer, player pose, WorldCameras or CutClient is changed. */
final class HologramEditorLessonStage {

    interface State {

        boolean isometric();

        boolean handles();

        boolean cut();

        int corners();

        int cutStage();

        int offsetX();

        int offsetZ();
    }

    private ViewportCoordinates viewport;
    private CameraBasis basis;
    private boolean isometric;
    private double aspect;
    private static final double DEPTH = 14, HALF_HEIGHT = 4.2;

    IWidget nativeWidget(HologramUiModules ui, State state) {
        return new Scene(ui, state).sizeRel(1f)
            .expanded()
            .name("tutorial.world.scene");
    }

    private final class Scene extends Widget<Scene> {

        private final HologramUiModules ui;
        private final State state;

        Scene(HologramUiModules ui, State state) {
            this.ui = ui;
            this.state = state;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            int[] region = ui.shell.nativeWidgetBounds(this);
            if (region == null) return;
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef(-region[0], -region[1], 0);
                HologramEditorLessonStage.this.draw(
                    ui,
                    state.isometric(),
                    state.corners(),
                    state.handles(),
                    state.cut(),
                    state.cutStage(),
                    state.offsetX(),
                    state.offsetZ(),
                    region);
            } finally {
                GL11.glPopMatrix();
            }
        }
    }

    private void draw(HologramUiModules ui, boolean iso, int corners, boolean handles, boolean cut, int cutStage,
        int offsetX, int offsetZ, int[] region) {
        isometric = iso;
        int x = region[0], y = region[1], w = region[2], h = region[3];
        viewport = new ViewportCoordinates(x, y, w, h);
        aspect = w / (double) h;
        CameraPose pose = new CameraPose(1.5, .2, 1, iso ? -43 : -25, iso ? 35 : 17);
        basis = new CameraBasis(pose);
        Gui.drawRect(x, y, x + w, y + h, 0xff263c42);
        Minecraft mc = ui.host.minecraft();
        ViewportCoordinates.FramebufferRect physical = viewport
            .framebufferRect(ui.host.screenWidth(), ui.host.screenHeight(), mc.displayWidth, mc.displayHeight);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        if (iso) GL11.glOrtho(-HALF_HEIGHT * aspect, HALF_HEIGHT * aspect, -HALF_HEIGHT, HALF_HEIGHT, .1, 100);
        else GLU.gluPerspective(52, (float) aspect, .1f, 100);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            GL11.glViewport(physical.left(), physical.bottom(), physical.width(), physical.height());
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(physical.left(), physical.bottom(), physical.width(), physical.height());
            GL11.glDepthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glTranslated(0, 0, -DEPTH);
            GL11.glRotated(pose.pitchDegrees(), 1, 0, 0);
            GL11.glRotated(pose.yawDegrees(), 0, 1, 0);
            GL11.glTranslated(-pose.anchorX(), -pose.anchorY(), -pose.anchorZ());
            for (int gx = -4; gx <= 7; gx++) for (int gz = -4; gz <= 6; gz++)
                cube(gx, -.35, gz, .985, .28, .985, ((gx + gz) & 1) == 0 ? 0x52604b : 0x5d6952);
            int move = cutStage >= 3 && cutStage <= 5 ? 3 : 0;
            for (int gx = 0; gx < 4; gx++) for (int gz = 0; gz < 3; gz++) {
                if (cut && cutStage >= 2 && cutStage < 3) continue;
                cube(
                    gx + move,
                    0,
                    gz,
                    .95,
                    gx == 0 && gz == 0 ? 1.6 : .9,
                    .95,
                    gx == 0 && gz == 0 ? 0xc79a52 : gz == 1 ? 0x5a9c9f : 0x8d9796);
            }
            if (corners >= 1) cube(-.10, -.04, -.10, .21, .21, .21, 0xe7bf58);
            if (corners >= 2) {
                cube(3.85, -.04, 2.85, .21, .21, .21, 0x75cce2);
                wire(offsetX, 0, offsetZ, 4, 1.8, 3, 0xf0d392);
                if (handles) {
                    cube(offsetX - .2, .8, offsetZ + 1.3, .24, .24, .24, 0xef7771);
                    cube(offsetX + 3.96, .8, offsetZ + 1.3, .24, .24, .24, 0xef7771);
                    cube(offsetX + 1.9, -.2, offsetZ + 1.3, .24, .24, .24, 0x84d88c);
                    cube(offsetX + 1.9, 1.76, offsetZ + 1.3, .24, .24, .24, 0x84d88c);
                    cube(offsetX + 1.9, .8, offsetZ - .2, .24, .24, .24, 0x7da5ea);
                    cube(offsetX + 1.9, .8, offsetZ + 2.96, .24, .24, .24, 0x7da5ea);
                    cube(offsetX + 1.35, .02, offsetZ + 1.05, .65, .04, .65, 0xdfbf77);
                }
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopAttrib();
        }
        if (!iso) {
            int cx = x + w / 2, cy = y + h / 2;
            Gui.drawRect(cx - 5, cy, cx + 6, cy + 1, 0xfff1dfb2);
            Gui.drawRect(cx, cy - 5, cx + 1, cy + 6, 0xfff1dfb2);
            Gui.drawRect(x + w - 38, y + h - 40, x + w - 16, y + h, 0xffb78c61);
            Gui.drawRect(x + w - 43, y + h - 47, x + w - 12, y + h - 29, 0xff71654b);
            HologramEditorIcons.draw("world", x + w - 35, y + h - 44, 12, true, true, false, false);
        }
        ui.canvas.text(iso ? "正交投影 · 世界轴固定" : "透视投影 · 独立示例准星", x + 7, y + h - 15, w - 14, 0xffead2a5);
    }

    double[] point(double x, double y, double z) {
        if (basis == null || viewport == null) return null;
        double nx, ny;
        if (isometric) {
            ProjectedPoint p = basis
                .project(new OrthographicProjection(HALF_HEIGHT * aspect, HALF_HEIGHT, DEPTH, .1, 100), x, y, z);
            nx = p.ndcX();
            ny = p.ndcY();
        } else {
            double dx = x - basis.pose()
                .anchorX(), dy = y
                    - basis.pose()
                        .anchorY(),
                dz = z - basis.pose()
                    .anchorZ();
            double distance = DEPTH + basis.forwardX() * dx + basis.forwardY() * dy + basis.forwardZ() * dz;
            double tangent = Math.tan(Math.toRadians(26));
            nx = (basis.rightX() * dx + basis.rightZ() * dz) / (distance * tangent * aspect);
            ny = (basis.upX() * dx + basis.upY() * dy + basis.upZ() * dz) / (distance * tangent);
        }
        return new double[] { viewport.screenX(nx), viewport.screenY(ny) };
    }

    private static void cube(double x, double y, double z, double w, double h, double d, int color) {
        double[][] p = { { x, y, z }, { x + w, y, z }, { x + w, y + h, z }, { x, y + h, z }, { x, y, z + d },
            { x + w, y, z + d }, { x + w, y + h, z + d }, { x, y + h, z + d } };
        int[][] faces = { { 0, 1, 2, 3 }, { 4, 5, 6, 7 }, { 0, 4, 7, 3 }, { 1, 5, 6, 2 }, { 3, 2, 6, 7 },
            { 0, 1, 5, 4 } };
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int f = 0; f < faces.length; f++) {
            double shade = f == 4 ? 1 : f < 2 ? .78 : .64;
            t.setColorOpaque(
                (int) ((color >> 16 & 255) * shade),
                (int) ((color >> 8 & 255) * shade),
                (int) ((color & 255) * shade));
            for (int v : faces[f]) t.addVertex(p[v][0], p[v][1], p[v][2]);
        }
        t.draw();
    }

    private static void wire(double x, double y, double z, double w, double h, double d, int color) {
        Tessellator t = Tessellator.instance;
        double[][] p = { { x, y, z }, { x + w, y, z }, { x + w, y + h, z }, { x, y + h, z }, { x, y, z + d },
            { x + w, y, z + d }, { x + w, y + h, z + d }, { x, y + h, z + d } };
        int[][] edges = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 }, { 0, 4 },
            { 1, 5 }, { 2, 6 }, { 3, 7 } };
        GL11.glLineWidth(2);
        t.startDrawing(GL11.GL_LINES);
        t.setColorOpaque_I(color);
        for (int[] edge : edges) for (int v : edge) t.addVertex(p[v][0], p[v][1], p[v][2]);
        t.draw();
    }
}
