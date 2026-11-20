// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.miaokatze.gtmb.main.GTMiaoBlueprint;
import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;

/** Local standard model and the deliberate entry to the existing live-controller workflow. */
final class HologramControllerPreviewScreen extends HologramMuiScreen {

    private static final int WIDTH = 485, HEIGHT = 310;
    private static final int[] NO_OFFSET = new int[3];
    private final Minecraft client = Minecraft.getMinecraft();
    private final TileEntity tile;
    private final HologramControllerTemplate.Source source;
    private final HologramControllerOpenIntent.Context context;
    private final Runnable readWorld;
    private final Scene scene = new Scene();
    private HologramControllerTemplate template;
    private HologramState model;
    private boolean loading = true;
    private boolean drawn;
    private String notice = "";

    HologramControllerPreviewScreen(TileEntity tile, Runnable readWorld) {
        super("controller_standard_preview", WIDTH, HEIGHT);
        this.tile = tile;
        source = new HologramControllerTemplate.Source(tile);
        context = new HologramControllerOpenIntent.Context(client);
        this.readWorld = readWorld;
    }

    @Override
    protected void buildPanel() {
        HologramMuiTheme.header(panel(), source::title, WIDTH);
        panel().child(HologramMuiTheme.label(this::subtitle, 14, 38, WIDTH - 28, 14, HologramMuiTheme.TEXT));
        panel().child(
            scene.pos(14, 57)
                .size(WIDTH - 28, HEIGHT - 123));
        panel().child(
            HologramMuiTheme.label(
                () -> text("controllerPreviewControls", "Drag to orbit · Scroll to zoom"),
                14,
                HEIGHT - 62,
                WIDTH - 28,
                15,
                HologramMuiTheme.MUTED));
        panel().child(
            HologramMuiTheme
                .button(() -> text("close", "Close"), 14, HEIGHT - 34, 105, 21, () -> deferAction(this::close)));
        panel().child(
            HologramMuiTheme.primaryButton(
                () -> text("controllerPreviewReadWorld", "Read live controller on server"),
                WIDTH - 206,
                HEIGHT - 34,
                192,
                21,
                () -> deferAction(this::openLiveController)));
    }

    private String subtitle() {
        if (!notice.isEmpty()) return notice;
        if (loading) return text("controllerPreviewLoading", "Preparing local standard structure…");
        return text("controllerPreviewStandard", "Local standard structure · Default settings");
    }

    private boolean controllerCurrent() {
        return context.current(client) && client.theWorld.blockExists(tile.xCoord, tile.yCoord, tile.zCoord)
            && client.theWorld.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile;
    }

    private void openLiveController() {
        if (client.currentScreen != this) return;
        if (!controllerCurrent()) {
            notice = text("controllerPreviewChanged", "The controller or equipped projector changed");
            return;
        }
        readWorld.run();
    }

    private void prepare() {
        try {
            if (template == null) template = new HologramControllerTemplate(source);
            model = template.step(2048, 3_000_000L);
            if (model == null) return;
            loading = false;
            if (!template.standard)
                notice = text("controllerPreviewUnavailable", "Standard structure unavailable · Controller only");
            template = null;
        } catch (RuntimeException | LinkageError failure) {
            GTMiaoBlueprint.LOG.warn("Could not prepare local controller display for " + source.guideId(), failure);
            loading = false;
            template = null;
            notice = text("controllerPreviewFailed", "Local structure preview could not be prepared");
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // Present the shell first. GuideNH's scratch scene is shared with its own client screens.
        if (client.currentScreen == this && drawn && loading) prepare();
    }

    private void close() {
        if (client.currentScreen == this) client.displayGuiScreen(null);
    }

    @Override
    protected boolean muiKey(char character, int key) {
        if (key != Keyboard.KEY_ESCAPE) return false;
        deferAction(this::close);
        return true;
    }

    @Override
    protected void onMuiClose() {
        if (model != null) HologramRenderer.sceneClosed();
        template = null;
    }

    private static String text(String key, String fallback) {
        return HologramClient.text(key, fallback);
    }

    private final class Scene extends Widget<Scene> implements Interactable {

        private final HologramSelection emptySelection = new HologramSelection();
        private double yaw = Math.toRadians(-35), pitch = Math.toRadians(24), zoom = 1;
        private int dragX, dragY;
        private int hovered = -1;

        Scene() {
            tooltipBuilder(tooltip -> {
                if (model == null || hovered < 0 || hovered >= model.cells.size()) return;
                HologramState.Cell cell = model.cells.get(hovered);
                if (cell.targetStack != null) tooltip.addLine(cell.targetStack.getDisplayName());
            }).tooltipAutoUpdate(true);
        }

        @Override
        public void draw(ModularGuiContext gui, WidgetThemeEntry<?> theme) {
            int w = getArea().width, h = getArea().height;
            Gui.drawRect(0, 0, w, h, 0xff292d2f);
            drawn = true;
            if (model == null || w <= 0 || h <= 0) return;
            double left = gui.transformX(0, 0), top = gui.transformY(0, 0);
            ScaledResolution scaled = new ScaledResolution(client, client.displayWidth, client.displayHeight);
            ViewportCoordinates.FramebufferRect rect = new ViewportCoordinates(
                left,
                top,
                gui.transformX(w, 0) - left,
                gui.transformY(0, h) - top).framebufferRect(
                    scaled.getScaledWidth(),
                    scaled.getScaledHeight(),
                    client.displayWidth,
                    client.displayHeight);
            HologramSelection selected = HologramRenderer.selection;
            String structure = HologramRenderer.selectedStructureId;
            boolean emptyCells = HologramRenderer.showEmptyCells;
            int layer = HologramRenderer.projectionLayer;
            int[] offset = HologramRenderer.moveOffset;
            GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(rect.left(), rect.bottom(), rect.width(), rect.height());
            try {
                HologramRenderer.selection = emptySelection;
                HologramRenderer.selectedStructureId = "";
                HologramRenderer.showEmptyCells = false;
                HologramRenderer.projectionLayer = Integer.MAX_VALUE;
                HologramRenderer.moveOffset = NO_OFFSET;
                hovered = HologramRenderer.preview(
                    model,
                    0,
                    0,
                    w,
                    h,
                    yaw,
                    pitch,
                    zoom,
                    Integer.MAX_VALUE,
                    0,
                    -1,
                    gui.getMouseX(),
                    gui.getMouseY(),
                    -1);
            } finally {
                HologramRenderer.selection = selected;
                HologramRenderer.selectedStructureId = structure;
                HologramRenderer.showEmptyCells = emptyCells;
                HologramRenderer.projectionLayer = layer;
                HologramRenderer.moveOffset = offset;
                GL11.glPopAttrib();
            }
        }

        @Override
        public Result onMousePressed(int button) {
            if (button != 0 || model == null) return Result.IGNORE;
            dragX = getContext().getAbsMouseX();
            dragY = getContext().getAbsMouseY();
            return Result.SUCCESS;
        }

        @Override
        public void onMouseDrag(int button, long elapsed) {
            if (button != 0 || model == null) return;
            int x = getContext().getAbsMouseX(), y = getContext().getAbsMouseY();
            yaw += (x - dragX) * .012;
            pitch = Math.max(-1.52, Math.min(1.52, pitch + (y - dragY) * .012));
            dragX = x;
            dragY = y;
        }

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            if (model == null) return false;
            zoom = HologramPreviewCamera.clampZoom(zoom * (direction.isUp() ? 1.12 : 1 / 1.12));
            return true;
        }
    }
}
