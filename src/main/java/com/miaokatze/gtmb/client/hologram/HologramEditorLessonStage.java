package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.nbt.NBTTagCompound;

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

/** Native raw renderer over the observed EBF geometry, with a private camera and finite teaching transactions. */
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
    private double depth, halfHeight;
    final HologramEditorTutorialGeometry geometry = new HologramEditorTutorialGeometry();
    private final HologramCutRawPreviewSession renderer = new HologramCutRawPreviewSession();
    private NBTTagCompound rendered;

    void initialize(HologramState state) {
        geometry.initialize(state);
        rendered = null;
    }

    void close() {
        renderer.release();
        rendered = null;
    }

    NBTTagCompound inspection() {
        NBTTagCompound result = renderer.inspection();
        result.setString("sample", "MTEElectricBlastFurnace / physical 36-cell observed plan");
        result.setBoolean("formationClaim", false);
        result.setBoolean("worldMutation", false);
        result.setBoolean("snapshotPresented", rendered == geometry.scene() && result.getLong("frames") > 0);
        return result;
    }

    double[] cornerPoint(boolean last) {
        int[] point = geometry.corner(last);
        return point(point[0] + .5, point[1] + .5, point[2] + .5);
    }

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
                ui.canvas.incrementRenderGeneration(1, false);
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
        NBTTagCompound scene = geometry.scene();
        if (rendered != scene) {
            renderer.update(scene);
            rendered = scene;
        }
        int[] bounds = geometry.bounds();
        CameraPose pose = new CameraPose(
            (bounds[0] + bounds[3]) / 2d,
            (bounds[1] + bounds[4]) / 2d,
            (bounds[2] + bounds[5]) / 2d,
            iso ? -43 : -25,
            iso ? 35 : 17);
        basis = new CameraBasis(pose);
        double horizontal = 0, vertical = 0, distance = 0;
        for (int corner = 0; corner < 8; corner++) {
            double dx = bounds[(corner & 1) == 0 ? 0 : 3] - pose.anchorX();
            double dy = bounds[(corner & 2) == 0 ? 1 : 4] - pose.anchorY();
            double dz = bounds[(corner & 4) == 0 ? 2 : 5] - pose.anchorZ();
            horizontal = Math.max(horizontal, Math.abs(basis.rightX() * dx + basis.rightZ() * dz));
            vertical = Math.max(vertical, Math.abs(basis.upX() * dx + basis.upY() * dy + basis.upZ() * dz));
            distance = Math
                .max(distance, Math.abs(basis.forwardX() * dx + basis.forwardY() * dy + basis.forwardZ() * dz));
        }
        // Fit the actually drawn native snapshot and selection in this viewport, with room for labels/edges.
        halfHeight = Math.max(1, Math.max(vertical, horizontal / aspect) * 1.16);
        depth = iso ? distance + 8 : distance + halfHeight / Math.tan(Math.toRadians(26));
        Gui.drawRect(x, y, x + w, y + h, 0xff263c42);
        Minecraft mc = ui.host.minecraft();
        ViewportCoordinates.FramebufferRect physical = viewport
            .framebufferRect(ui.host.screenWidth(), ui.host.screenHeight(), mc.displayWidth, mc.displayHeight);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        if (iso) GL11.glOrtho(-halfHeight * aspect, halfHeight * aspect, -halfHeight, halfHeight, .1, 100);
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
            GL11.glTranslated(0, 0, -depth);
            GL11.glRotated(pose.pitchDegrees(), 1, 0, 0);
            GL11.glRotated(pose.yawDegrees(), 0, 1, 0);
            GL11.glTranslated(-pose.anchorX(), -pose.anchorY(), -pose.anchorZ());
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            renderer.renderLocal(0);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            wire(0, 0, 0, scene.getInteger("sizeX"), scene.getInteger("sizeY"), scene.getInteger("sizeZ"), 0x8d978d);
            int[] first = geometry.first(), second = geometry.second();
            if (first != null) wire(first[0], first[1], first[2], 1, 1, 1, 0xe7bf58);
            if (second != null) {
                wire(second[0], second[1], second[2], 1, 1, 1, 0x75cce2);
                wire(
                    first[0],
                    first[1],
                    first[2],
                    second[0] - first[0] + 1,
                    second[1] - first[1] + 1,
                    second[2] - first[2] + 1,
                    0xf0d392);
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
        }
        ui.canvas.text(
            (iso ? "等距" : "透视") + " · 电力高炉采集规划 · "
                + scene.getTagList("cells", 10)
                    .tagCount()
                + " 格 · 仅教学内存",
            x + 7,
            y + h - 15,
            w - 14,
            0xffead2a5);
    }

    double[] point(double x, double y, double z) {
        if (basis == null || viewport == null) return null;
        double nx, ny;
        if (isometric) {
            ProjectedPoint p = basis
                .project(new OrthographicProjection(halfHeight * aspect, halfHeight, depth, .1, 100), x, y, z);
            nx = p.ndcX();
            ny = p.ndcY();
        } else {
            double dx = x - basis.pose()
                .anchorX(), dy = y
                    - basis.pose()
                        .anchorY(),
                dz = z - basis.pose()
                    .anchorZ();
            double distance = depth + basis.forwardX() * dx + basis.forwardY() * dy + basis.forwardZ() * dz;
            double tangent = Math.tan(Math.toRadians(26));
            nx = (basis.rightX() * dx + basis.rightZ() * dz) / (distance * tangent * aspect);
            ny = (basis.upX() * dx + basis.upY() * dy + basis.upZ() * dz) / (distance * tangent);
        }
        return new double[] { viewport.screenX(nx), viewport.screenY(ny) };
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
