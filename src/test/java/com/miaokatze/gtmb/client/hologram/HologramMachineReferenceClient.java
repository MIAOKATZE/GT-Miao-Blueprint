package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import com.miaokatze.gtmb.hologram.HologramMachineReference;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.render.RenderOverlay;
import gregtech.common.render.IIconTexture;

/** Full-size registered native world rendering of already formed server machines; no hologram mesh fallback. */
public final class HologramMachineReferenceClient {

    private static final String[] VIEWS = { "front-left", "front-right", "rear-left", "cutaway" };
    private static final float[] YAW = { -35, 35, 145, -35 };
    private static final float PITCH = 25;
    private static final float FOV = 32;
    private static final int BACKGROUND = 0x151b24;
    private int stage = -1;
    private long stageAt;
    private final long installedAt = System.nanoTime();
    private boolean finished;
    private ReferenceScreen screen;

    public static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.referenceClient") || Boolean.getBoolean("gtmb.hologram.clientSmoke"))
            throw new IllegalStateException("Machine references require a separate client profile");
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramMachineReferenceClient());
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (stage < 0) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) {
                    require(System.nanoTime() - installedAt < 300000000000L, "main menu initialization timeout");
                    return;
                }
                mc.gameSettings.guiScale = 1;
                mc.gameSettings.ambientOcclusion = 0;
                next(mc);
                return;
            }
            if (System.nanoTime() - stageAt < 2000000000L) return;
            require(mc.currentScreen == screen && screen.draws >= 2, "reference screen must actually render");
            require(screen.failure == null, "native render failure: " + screen.failure);
            require(screen.vertices > 200 && screen.gtTiles > 0, "actual registered GT triangles must render");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error");
            String filename = screen.state.getString("name") + "-"
                + VIEWS[stage % 4]
                + (screen.comparison ? "-comparison" : "")
                + ".png";
            File screenshot = new File(new File(mc.mcDataDir, "screenshots"), filename);
            if (screenshot.exists()) require(screenshot.delete(), "replace reference screenshot");
            ScreenShotHelper
                .saveScreenshot(mc.mcDataDir, filename, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            String raster = screen.comparison ? auditComparison(screenshot) : audit(screenshot);
            Files.write(
                new File(screenshot.getParentFile(), filename.replace(".png", ".camera.json")).toPath(),
                screen.cameraMetadata()
                    .getBytes(StandardCharsets.UTF_8));
            System.out.println(
                "[GTMB-MACHINE-REFERENCE-CLIENT] " + (screen.comparison ? "COMPARISON PASS " : "PASS ")
                    + filename
                    + " nativeVertices="
                    + screen.vertices
                    + " wireframeVertices="
                    + screen.wireframeVertices
                    + " restoredGT="
                    + screen.gtTiles
                    + " nativeOverlayCells="
                    + screen.overlayCells
                    + " nativeOverlayVertices="
                    + screen.overlayVertices
                    + " nativeOverlayUvRects="
                    + screen.overlayUvRects.size()
                    + " nativeOverlayUniqueVertices="
                    + screen.nativeOverlayUniqueVertices
                    + " nativeOverlayEmittedVertices="
                    + screen.nativeOverlayEmittedVertices
                    + " nativeOverlayDuplicatePassVertices="
                    + screen.nativeOverlayDuplicatePassVertices
                    + " nativeVertexDelta="
                    + screen.nativeVertexDelta
                    + " additionalNativeVertices="
                    + screen.additionalNativeVertices
                    + " perspectiveFov="
                    + FOV
                    + " yaw="
                    + YAW[screen.view]
                    + " pitch="
                    + PITCH
                    + " "
                    + raster
                    + " screenshot="
                    + screenshot.getAbsolutePath());
            if (!screen.comparison) {
                screen.comparison = true;
                screen.draws = 0;
                stageAt = System.nanoTime();
            } else next(mc);
        } catch (Throwable failure) {
            failure.printStackTrace();
            finished = true;
            if (screen != null) screen.release();
            System.err.println("[GTMB-MACHINE-REFERENCE-CLIENT] FINAL FAIL " + failure);
            mc.shutdown();
        }
    }

    private void next(Minecraft mc) throws Exception {
        if (screen != null) screen.release();
        if (++stage == HologramMachineReference.MACHINES.length * VIEWS.length) {
            finished = true;
            System.out.println(
                "[GTMB-MACHINE-REFERENCE-CLIENT] FINAL PASS screenshots=16 comparisons=16 full native registered renderers");
            mc.shutdown();
            return;
        }
        String name = HologramMachineReference.MACHINES[stage / VIEWS.length];
        File file = new File(System.getProperty("gtmb.hologram.referenceStateDir", ""), name + ".nbt");
        NBTTagCompound state;
        try (FileInputStream input = new FileInputStream(file)) {
            state = CompressedStreamTools.readCompressed(input);
        }
        require(state.getBoolean("formed"), "reference machine must have passed real server formation");
        screen = new ReferenceScreen(state, stage % VIEWS.length);
        mc.displayGuiScreen(screen);
        stageAt = System.nanoTime();
    }

    private static String audit(File file) throws Exception {
        BufferedImage image = ImageIO.read(file);
        require(image != null, "saved screenshot must decode");
        int foreground = 0, minY = image.getHeight(), maxY = 0;
        Set<Integer> colors = new HashSet<>();
        for (int y = 90; y < image.getHeight() - 90; y++) for (int x = 50; x < image.getWidth() - 50; x++) {
            int rgb = image.getRGB(x, y) & 0xffffff;
            int difference = Math.abs((rgb & 255) - (BACKGROUND & 255))
                + Math.abs((rgb >> 8 & 255) - (BACKGROUND >> 8 & 255))
                + Math.abs((rgb >> 16 & 255) - (BACKGROUND >> 16 & 255));
            if (difference > 30) {
                foreground++;
                colors.add(rgb);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        require(
            foreground > 30000 && colors.size() > 100 && maxY - minY >= 600,
            "machine must retain large detailed material pixels: foreground=" + foreground
                + " colors="
                + colors.size()
                + " height="
                + (maxY - minY));
        return "materialPixels=" + foreground + " colors=" + colors.size() + " machineHeight=" + (maxY - minY);
    }

    private static String auditComparison(File file) throws Exception {
        BufferedImage image = ImageIO.read(file);
        require(image != null, "saved comparison must decode");
        Set<Integer> materialColors = new HashSet<>();
        int cyanPixels = 0;
        for (int y = 90; y < image.getHeight() - 90; y++) {
            for (int x = 80; x < image.getWidth() / 2 - 20; x++) {
                int rgb = image.getRGB(x, y) & 0xffffff;
                if (rgb != BACKGROUND) materialColors.add(rgb);
            }
            for (int x = image.getWidth() / 2 + 20; x < image.getWidth() - 80; x++) {
                int rgb = image.getRGB(x, y);
                int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
                if (green > 140 && blue > 150 && green > red * 1.5 && blue > red * 1.5) cyanPixels++;
            }
        }
        require(
            materialColors.size() > 100 && cyanPixels > 2000,
            "native comparison material and cyan mesh must both render");
        return "solidColors=" + materialColors.size() + " cyanMeshPixels=" + cyanPixels;
    }

    private static final class ReferenceScreen extends GuiScreen {

        private static final Field VERTEX_COUNT = ReflectionHelper
            .findField(Tessellator.class, "vertexCount", "field_78406_i");
        private static final Field RAW_BUFFER = ReflectionHelper
            .findField(Tessellator.class, "rawBuffer", "field_78405_h");
        final NBTTagCompound state;
        final int view;
        final ReferenceWorld world;
        final List<NBTTagCompound> blocks = new ArrayList<>();
        int draws, vertices, gtTiles, list, wireframeList, wireframeVertices, overlayCells, overlayVertices;
        boolean comparison, captureGeometry;
        final List<float[]> nativeQuads = new ArrayList<>();
        double cameraDistance;
        int viewportWidth, viewportHeight;
        boolean baselineOverlayAudit;
        final Map<String, List<AuditQuad>> baselineOverlayQuads = new HashMap<>();
        final Map<String, List<AuditQuad>> renderedAuditQuads = new HashMap<>();
        final Map<String, Set<Integer>> acceptedRenderPasses = new HashMap<>();
        final Map<String, Integer> emittedOverlayQuads = new HashMap<>();
        final Set<String> overlayUvRects = new HashSet<>();
        int nativeVertexDelta, additionalNativeVertices;
        int nativeOverlayUniqueVertices, nativeOverlayEmittedVertices, nativeOverlayDuplicatePassVertices;
        String overlayAuditRows = "[]", additionalNativeAuditRows = "[]";
        String nativeOverlayPassGeometryRows = "[]";
        Throwable failure;

        ReferenceScreen(NBTTagCompound state, int view) {
            this.state = state;
            this.view = view;
            world = new ReferenceWorld(state, view == 3);
            NBTTagList rows = state.getTagList("blocks", 10);
            for (int i = 0; i < rows.tagCount(); i++) blocks.add(rows.getCompoundTagAt(i));
            VERTEX_COUNT.setAccessible(true);
            RAW_BUFFER.setAccessible(true);
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTick) {
            drawRect(0, 0, width, height, 0xff000000 | BACKGROUND);
            int[] b = state.getIntArray("bounds");
            String title = state.getString("name")
                .replace('-', ' ');
            drawCenteredString(
                fontRendererObj,
                title + " | " + (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1) + " blocks",
                width / 2,
                32,
                0xe8edf5);
            drawCenteredString(
                fontRendererObj,
                "GT5U native renderer | server formation verified | " + VIEWS[view],
                width / 2,
                height - 52,
                0xa5b3c8);
            if (view == 3) drawCenteredString(
                fontRendererObj,
                "CUTAWAY: front block plane hidden for internal material reference",
                width / 2,
                height - 35,
                0xf2bb65);
            if (comparison) {
                drawCenteredString(fontRendererObj, "NATIVE MATERIALS", width / 4, 60, 0xe8edf5);
                drawCenteredString(
                    fontRendererObj,
                    "BLUEPRINT | SAME NATIVE MESH / CAMERA",
                    width * 3 / 4,
                    60,
                    0x54dcea);
            }
            int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                if (list == 0 && failure == null) compile();
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .1f);
                GL11.glColor4f(1, 1, 1, 1);
                mc.getTextureManager()
                    .bindTexture(TextureMap.locationBlocksTexture);
                viewportWidth = comparison ? (mc.displayWidth - 200) / 2 : mc.displayWidth - 160;
                viewportHeight = mc.displayHeight - 180;
                GL11.glViewport(80, 90, viewportWidth, viewportHeight);
                double angle = Math.toRadians(YAW[view]), pitch = Math.toRadians(PITCH);
                double aspect = (double) viewportWidth / viewportHeight;
                double halfFov = Math.tan(Math.toRadians(FOV / 2.0));
                cameraDistance = fitCamera(b, angle, pitch, aspect, halfFov);
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                GL11.glFrustum(-aspect * halfFov * .1, aspect * halfFov * .1, -halfFov * .1, halfFov * .1, .1, 1000);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                double cx = (b[0] + b[3] + 1) / 2.0, cy = (b[1] + b[4] + 1) / 2.0;
                double cz = (b[2] + b[5] + 1) / 2.0;
                GLU.gluLookAt(
                    (float) (cx + cameraDistance * Math.cos(pitch) * Math.sin(angle)),
                    (float) (cy + cameraDistance * Math.sin(pitch)),
                    (float) (cz - cameraDistance * Math.cos(pitch) * Math.cos(angle)),
                    (float) cx,
                    (float) cy,
                    (float) cz,
                    0,
                    1,
                    0);
                if (list != 0) GL11.glCallList(list);
                if (comparison) {
                    // The second panel uses identical projection/modelview, changing only its viewport origin.
                    GL11.glViewport(120 + viewportWidth, 90, viewportWidth, viewportHeight);
                    GL11.glColorMask(false, false, false, false);
                    GL11.glCallList(list);
                    GL11.glColorMask(true, true, true, true);
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glColor4f(.22f, .85f, .95f, 1);
                    GL11.glLineWidth(1.25f);
                    GL11.glEnable(GL11.GL_POLYGON_OFFSET_LINE);
                    GL11.glPolygonOffset(-1, -1);
                    GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
                    GL11.glCallList(wireframeList);
                }
                draws++;
            } catch (Throwable failed) {
                failure = failed;
            } finally {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(matrixMode);
                GL11.glPopAttrib();
            }
        }

        private void compile() throws Exception {
            RenderBlocks renderer = new RenderBlocks(world);
            renderer.enableAO = false;
            renderer.renderAllFaces = false;
            Tessellator tess = Tessellator.instance;
            int previousPass = net.minecraftforge.client.MinecraftForgeClient.getRenderPass();
            try {
                for (NBTTagCompound row : blocks) {
                    if (!row.hasKey("machineId")) continue;
                    if (view == 3 && row.getInteger("z") == state.getIntArray("bounds")[2]) continue;
                    TileEntity tile = world
                        .getTileEntity(row.getInteger("x"), row.getInteger("y"), row.getInteger("z"));
                    require(tile instanceof IGregTechTileEntity, "actual GT tile restored");
                    require(
                        ((IGregTechTileEntity) tile).getMetaTileID() == row.getInteger("machineId"),
                        "exact registered GT ID restored");
                    gtTiles++;
                }
                int baseline = 0;
                boolean centrifuge = state.getInteger("controllerId") == 15512;
                if (centrifuge) {
                    // Count the same registered renderers before the native lifecycle installs its overlays.
                    RenderOverlay.onWorldUnload(world);
                    int probe = GL11.glGenLists(1);
                    require(probe != 0, "native overlay audit list allocation");
                    GL11.glNewList(probe, GL11.GL_COMPILE);
                    try {
                        baselineOverlayAudit = true;
                        baseline = emitNative(renderer, tess);
                    } finally {
                        baselineOverlayAudit = false;
                        GL11.glEndList();
                        GL11.glDeleteLists(probe, 1);
                    }
                }
                world.initializeNativeTextures();
                if (centrifuge) {
                    TileEntity controller = world.getTileEntity(0, 0, 0);
                    require(controller instanceof IGregTechTileEntity, "centrifuge controller tile");
                    IGregTechTileEntity gt = (IGregTechTileEntity) controller;
                    require(gt.getMetaTileID() == 15512, "current registered industrial centrifuge, not legacy 790");
                    require(gt.getFrontFacing() == ForgeDirection.NORTH, "reference controller faces north");
                    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                        gregtech.api.interfaces.ITexture[] overlays = RenderOverlay.get(world, x, y, 0);
                        require(
                            overlays != null && overlays[ForgeDirection.NORTH.ordinal()] != null,
                            "native centrifuge front overlay at " + x + "," + y + ",0");
                        require(world.getBlock(x, y, 0) != net.minecraft.init.Blocks.air, "real overlay support block");
                        overlayCells++;
                    }
                }
                list = GL11.glGenLists(1);
                require(list != 0, "native GL display list allocation");
                GL11.glNewList(list, GL11.GL_COMPILE);
                try {
                    captureGeometry = true;
                    vertices = emitNative(renderer, tess);
                } finally {
                    captureGeometry = false;
                    GL11.glEndList();
                }
                compileWireframe();
                if (centrifuge) {
                    finishOverlayAudit(baseline);
                }
            } finally {
                ForgeHooksClient.setRenderPass(previousPass);
            }
        }

        private int emitNative(RenderBlocks renderer, Tessellator tess) throws Exception {
            int count = 0;
            for (int pass = 0; pass <= 1; pass++) {
                ForgeHooksClient.setRenderPass(pass);
                if (pass == 1) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                }
                for (NBTTagCompound row : blocks) {
                    int x = row.getInteger("x"), y = row.getInteger("y"), z = row.getInteger("z");
                    if (view == 3 && z == state.getIntArray("bounds")[2]) continue;
                    Block block = world.getBlock(x, y, z);
                    if (!block.canRenderInPass(pass)) continue;
                    tess.startDrawingQuads();
                    tess.setTranslation(0, 0, 0);
                    tess.setBrightness(0xf000f0);
                    tess.setColorOpaque_F(1, 1, 1);
                    require(block.getRenderType() >= 0, "registered static renderer unavailable: " + block);
                    renderer.renderBlockByRenderType(block, x, y, z);
                    int emitted = VERTEX_COUNT.getInt(tess);
                    if (captureGeometry) captureNativeQuads(tess, emitted);
                    auditOverlayQuads(x, y, z, pass, tess, emitted);
                    count += emitted;
                    tess.draw();
                }
            }
            return count;
        }

        private void captureNativeQuads(Tessellator tess, int count) throws Exception {
            require(count % 4 == 0, "registered native reference emits quad geometry");
            int[] raw = (int[]) RAW_BUFFER.get(tess);
            for (int vertex = 0; vertex < count; vertex += 4) {
                float[] quad = new float[12];
                for (int corner = 0; corner < 4; corner++) for (int coordinate = 0; coordinate < 3; coordinate++)
                    quad[corner * 3 + coordinate] = Float.intBitsToFloat(raw[(vertex + corner) * 8 + coordinate]);
                nativeQuads.add(quad);
                wireframeVertices += 4;
            }
        }

        private void compileWireframe() {
            require(wireframeVertices == vertices, "blueprint mesh retains every actual native emitted vertex");
            wireframeList = GL11.glGenLists(1);
            require(wireframeList != 0, "native wireframe display list allocation");
            GL11.glNewList(wireframeList, GL11.GL_COMPILE);
            try {
                GL11.glBegin(GL11.GL_QUADS);
                for (float[] quad : nativeQuads) for (int corner = 0; corner < 4; corner++)
                    GL11.glVertex3f(quad[corner * 3], quad[corner * 3 + 1], quad[corner * 3 + 2]);
                GL11.glEnd();
            } finally {
                GL11.glEndList();
            }
            nativeQuads.clear();
        }

        private void auditOverlayQuads(int x, int y, int z, int pass, Tessellator tess, int count) throws Exception {
            if (state.getInteger("controllerId") != 15512) return;
            String cell = x + "," + y + "," + z;
            acceptedRenderPasses.computeIfAbsent(cell, ignored -> new HashSet<>())
                .add(pass);
            Map<String, List<AuditQuad>> target = baselineOverlayAudit ? baselineOverlayQuads : renderedAuditQuads;
            List<AuditQuad> quads = target.computeIfAbsent(cell, ignored -> new ArrayList<>());
            int[] raw = (int[]) RAW_BUFFER.get(tess);
            for (int vertex = 0; vertex + 3 < count; vertex += 4) {
                quads.add(new AuditQuad(raw, vertex * 8, pass));
            }
        }

        private void finishOverlayAudit(int baselineVertices) {
            nativeVertexDelta = vertices - baselineVertices;
            Map<String, IIcon> expectedIcons = new HashMap<>();
            Set<String> iconNames = new HashSet<>();
            StringBuilder overlays = new StringBuilder("[");
            for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                String cell = x + "," + y + ",0";
                ITexture registered = RenderOverlay.get(world, x, y, 0)[ForgeDirection.NORTH.ordinal()];
                require(registered instanceof IIconTexture, "actual registered GT overlay has an atlas icon");
                IIcon icon = ((IIconTexture) registered).getIcon(ForgeDirection.NORTH.ordinal(), null);
                require(
                    icon != null && icon.getIconName()
                        .matches("gregtech:iconsets/LARGETURBINE_NEW(?:_ACTIVE)?[1-9]"),
                    "actual GT turbine overlay sprite, not an arbitrary UV rectangle");
                expectedIcons.put(cell, icon);
                iconNames.add(icon.getIconName());
                Set<Integer> accepted = acceptedRenderPasses.get(cell);
                require(accepted != null && accepted.contains(0), "native front support must accept render pass zero");
                require(
                    (x == 0 && y == 0) ? accepted.size() == 2 && accepted.contains(1) : accepted.size() == 1,
                    "native GT controller accepts both passes; each casing accepts only pass zero");
                overlayUvRects.add(icon.getMinU() + "," + icon.getMaxU() + "," + icon.getMinV() + "," + icon.getMaxV());
                if (overlays.length() > 1) overlays.append(',');
                overlays.append("{\"cell\":[")
                    .append(cell)
                    .append("],\"sprite\":\"")
                    .append(icon.getIconName())
                    .append("\",\"passes\":[");
                boolean firstPass = true;
                for (int pass = 0; pass <= 1; pass++) {
                    if (!accepted.contains(pass)) continue;
                    int before = countAtlasQuads(baselineOverlayQuads.get(cell), icon, x, y, pass);
                    int after = countAtlasQuads(renderedAuditQuads.get(cell), icon, x, y, pass);
                    System.out.println(
                        "[GTMB-MACHINE-REFERENCE-CLIENT] NATIVE OVERLAY SPRITE cell=" + cell
                            + " pass="
                            + pass
                            + " sprite="
                            + icon.getIconName()
                            + " baselineQuads="
                            + before
                            + " renderedQuads="
                            + after);
                    require(
                        after - before == 1,
                        "exactly one additional registered overlay sprite quad per accepted pass at " + cell);
                    emittedOverlayQuads.put(cell + "@" + pass, after - before);
                    nativeOverlayEmittedVertices += (after - before) * 4;
                    if (!firstPass) overlays.append(',');
                    firstPass = false;
                    overlays.append("{\"renderPass\":")
                        .append(pass)
                        .append(",\"baselineQuads\":")
                        .append(before)
                        .append(",\"renderedQuads\":")
                        .append(after)
                        .append(",\"emittedOverlayVertices\":4}");
                }
                overlays.append("]}");
            }
            overlayAuditRows = overlays.append(']')
                .toString();
            require(
                overlayCells == 9 && nativeOverlayEmittedVertices == 40
                    && emittedOverlayQuads.size() == 10
                    && overlayUvRects.size() == 9
                    && iconNames.size() == 9,
                "nine actual registered GT sprites emit ten native quads across the accepted render passes");
            int addedQuads = 0;
            StringBuilder passGeometry = new StringBuilder("[");
            Map<String, Set<String>> uniqueOverlayGeometry = new HashMap<>();
            for (Map.Entry<String, List<AuditQuad>> entry : renderedAuditQuads.entrySet()) {
                String cell = entry.getKey();
                String[] xyz = cell.split(",");
                int x = Integer.parseInt(xyz[0]), y = Integer.parseInt(xyz[1]);
                Map<String, Integer> remainingBaseline = new HashMap<>();
                for (AuditQuad quad : baselineOverlayQuads.get(cell))
                    remainingBaseline.put(quad.signature, remainingBaseline.getOrDefault(quad.signature, 0) + 1);
                Set<Integer> consumedOverlayPasses = new HashSet<>();
                for (AuditQuad quad : entry.getValue()) {
                    int remaining = remainingBaseline.getOrDefault(quad.signature, 0);
                    if (remaining > 0) {
                        remainingBaseline.put(quad.signature, remaining - 1);
                        continue;
                    }
                    addedQuads++;
                    IIcon expected = expectedIcons.get(cell);
                    require(
                        expected != null && quad.matches(expected)
                            && quad.northFace(x, y)
                            && consumedOverlayPasses.add(quad.renderPass),
                        "each added native quad belongs to its registered overlay sprite and unique accepted pass at "
                            + cell);
                    uniqueOverlayGeometry.computeIfAbsent(cell, ignored -> new HashSet<>())
                        .add(quad.geometrySignature);
                    if (passGeometry.length() > 1) passGeometry.append(',');
                    passGeometry.append("{\"cell\":[")
                        .append(cell)
                        .append("],\"sprite\":\"")
                        .append(expected.getIconName())
                        .append("\",\"renderPass\":")
                        .append(quad.renderPass)
                        .append(",\"emittedOverlayVertices\":4,\"uv\":")
                        .append(quad.uvRectangle())
                        .append(",\"xyz\":")
                        .append(quad.positions())
                        .append('}');
                }
                for (int remaining : remainingBaseline.values())
                    require(remaining == 0, "native baseline geometry must be retained at " + cell);
                require(
                    expectedIcons.get(cell) == null || consumedOverlayPasses.equals(acceptedRenderPasses.get(cell)),
                    "native overlay quad attribution covers every accepted render pass at " + cell);
            }
            require(uniqueOverlayGeometry.size() == 9, "nine unique native overlay support faces");
            nativeOverlayPassGeometryRows = passGeometry.append(']')
                .toString();
            for (Map.Entry<String, Set<String>> entry : uniqueOverlayGeometry.entrySet()) {
                require(
                    entry.getValue()
                        .size() == 1,
                    "native overlay geometry and UV are identical across accepted passes at " + entry.getKey());
                nativeOverlayUniqueVertices += 4;
            }
            require(
                nativeOverlayUniqueVertices == 36,
                "nine unique original GT overlay faces retain 36 unique vertices");
            nativeOverlayDuplicatePassVertices = nativeOverlayEmittedVertices - nativeOverlayUniqueVertices;
            // Existing receipt alias denotes unique geometry, never actual emission.
            overlayVertices = nativeOverlayUniqueVertices;
            require(
                addedQuads * 4 == nativeVertexDelta && nativeVertexDelta == nativeOverlayEmittedVertices
                    && nativeOverlayDuplicatePassVertices == 4,
                "all 40 actual added native vertices are attributed, including the controller's identical second-pass quad");
        }

        private static int countAtlasQuads(List<AuditQuad> quads, IIcon icon, int x, int y, int pass) {
            int count = 0;
            for (AuditQuad quad : quads)
                if (quad.renderPass == pass && quad.matches(icon) && quad.northFace(x, y)) count++;
            return count;
        }

        private static final class AuditQuad {

            final String signature;
            final String geometrySignature;
            final int renderPass;
            final float[] xyz = new float[12];
            float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;

            AuditQuad(int[] raw, int start, int pass) {
                renderPass = pass;
                StringBuilder value = new StringBuilder();
                for (int corner = 0; corner < 4; corner++) {
                    int offset = start + corner * 8;
                    for (int field = 0; field < 5; field++) value.append(raw[offset + field])
                        .append(',');
                    for (int coordinate = 0; coordinate < 3; coordinate++)
                        xyz[corner * 3 + coordinate] = Float.intBitsToFloat(raw[offset + coordinate]);
                    float u = Float.intBitsToFloat(raw[offset + 3]), v = Float.intBitsToFloat(raw[offset + 4]);
                    minU = Math.min(minU, u);
                    maxU = Math.max(maxU, u);
                    minV = Math.min(minV, v);
                    maxV = Math.max(maxV, v);
                }
                geometrySignature = value.toString();
                signature = pass + ":" + geometrySignature;
            }

            boolean matches(IIcon icon) {
                return maxU > minU && maxV > minV
                    && Math.abs(minU - icon.getMinU()) < .000001
                    && Math.abs(maxU - icon.getMaxU()) < .000001
                    && Math.abs(minV - icon.getMinV()) < .000001
                    && Math.abs(maxV - icon.getMaxV()) < .000001;
            }

            boolean northFace(int x, int y) {
                for (int corner = 0; corner < 4; corner++) {
                    if (Math.abs(xyz[corner * 3 + 2]) >= .01 || xyz[corner * 3] < x - .01
                        || xyz[corner * 3] > x + 1.01
                        || xyz[corner * 3 + 1] < y - .01
                        || xyz[corner * 3 + 1] > y + 1.01) return false;
                }
                return true;
            }

            String uvRectangle() {
                return "[" + minU + "," + maxU + "," + minV + "," + maxV + "]";
            }

            String positions() {
                return java.util.Arrays.toString(xyz);
            }
        }

        private static double fitCamera(int[] b, double yaw, double pitch, double aspect, double tangent) {
            double distance = 1;
            double cx = (b[0] + b[3] + 1) / 2.0, cy = (b[1] + b[4] + 1) / 2.0;
            double cz = (b[2] + b[5] + 1) / 2.0;
            for (int i = 0; i < 8; i++) {
                double x = ((i & 1) == 0 ? b[0] : b[3] + 1) - cx;
                double y = ((i & 2) == 0 ? b[1] : b[4] + 1) - cy;
                double z = ((i & 4) == 0 ? b[2] : b[5] + 1) - cz;
                double horizontal = -Math.cos(yaw) * x - Math.sin(yaw) * z;
                double vertical = -Math.sin(pitch) * Math.sin(yaw) * x + Math.cos(pitch) * y
                    + Math.sin(pitch) * Math.cos(yaw) * z;
                double towardCamera = Math.cos(pitch) * Math.sin(yaw) * x + Math.sin(pitch) * y
                    - Math.cos(pitch) * Math.cos(yaw) * z;
                distance = Math.max(distance, towardCamera + Math.abs(horizontal) / (tangent * aspect * .94));
                distance = Math.max(distance, towardCamera + Math.abs(vertical) / (tangent * .94));
            }
            return distance;
        }

        String cameraMetadata() {
            return "{\n  \"projection\": \"perspective\",\n  \"verticalFovDegrees\": " + FOV
                + ",\n  \"yawDegreesFromNorth\": "
                + YAW[view]
                + ",\n  \"pitchDegrees\": "
                + PITCH
                + ",\n  \"distanceBlocks\": "
                + cameraDistance
                + ",\n  \"target\": ["
                + (state.getIntArray("bounds")[0] + state.getIntArray("bounds")[3] + 1) / 2.0
                + ", "
                + (state.getIntArray("bounds")[1] + state.getIntArray("bounds")[4] + 1) / 2.0
                + ", "
                + (state.getIntArray("bounds")[2] + state.getIntArray("bounds")[5] + 1) / 2.0
                + "],\n  \"viewport\": [80, 90, "
                + viewportWidth
                + ", "
                + viewportHeight
                + "],\n  \"comparison\": "
                + comparison
                + ",\n  \"secondViewportOrigin\": "
                + (comparison ? "[" + (120 + viewportWidth) + ", 90]" : "null")
                + ",\n  \"wireframeVertices\": "
                + wireframeVertices
                + ",\n  \"near\": 0.1,\n  \"far\": 1000.0,\n  \"nativeOverlayCells\": "
                + overlayCells
                + ",\n  \"nativeOverlayVertices\": "
                + overlayVertices
                + ",\n  \"nativeOverlayVerticesMeaning\": \"unique geometry alias\""
                + ",\n  \"nativeOverlayUniqueVertices\": "
                + nativeOverlayUniqueVertices
                + ",\n  \"nativeOverlayEmittedVertices\": "
                + nativeOverlayEmittedVertices
                + ",\n  \"nativeOverlayDuplicatePassVertices\": "
                + nativeOverlayDuplicatePassVertices
                + ",\n  \"nativeVertexDelta\": "
                + nativeVertexDelta
                + ",\n  \"additionalNativeVertices\": "
                + additionalNativeVertices
                + ",\n  \"nativeOverlaySprites\": "
                + overlayAuditRows
                + ",\n  \"nativeOverlayPassGeometry\": "
                + nativeOverlayPassGeometryRows
                + ",\n  \"additionalNativeQuads\": "
                + additionalNativeAuditRows
                + ",\n  \"nativeOverlayUvRects\": "
                + overlayUvRects.size()
                + "\n}\n";
        }

        void release() {
            RenderOverlay.onWorldUnload(world);
            if (list != 0) {
                GL11.glDeleteLists(list, 1);
                list = 0;
            }
            if (wireframeList != 0) {
                GL11.glDeleteLists(wireframeList, 1);
                wireframeList = 0;
            }
        }

    }

    /** Detached native tile deserialization is confined to this opt-in tool; no ticks or network connection occur. */
    private static final class ReferenceWorld extends World {

        private Map<String, NBTTagCompound> rows;
        private Map<String, TileEntity> tiles;

        ReferenceWorld(NBTTagCompound state, boolean cutaway) {
            super(
                new SaveHandlerMP(),
                "GTMB machine reference",
                new WorldProviderSurface(),
                new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT),
                new Profiler());
            isRemote = true;
            rows = new HashMap<>();
            tiles = new HashMap<>();
            NBTTagList blocks = state.getTagList("blocks", 10);
            for (int i = 0; i < blocks.tagCount(); i++) {
                NBTTagCompound row = blocks.getCompoundTagAt(i);
                if (cutaway && row.getInteger("z") == state.getIntArray("bounds")[2]) continue;
                rows.put(key(row.getInteger("x"), row.getInteger("y"), row.getInteger("z")), row);
            }
        }

        void initializeNativeTextures() {
            // This is the original GT client texture lifecycle callback, not reconstructed overlay geometry.
            for (TileEntity tile : new ArrayList<>(tiles.values())) {
                if (tile instanceof IGregTechTileEntity) {
                    IGregTechTileEntity gt = (IGregTechTileEntity) tile;
                    require(gt.isClientSide() && gt.getWorld() == this, "native GT client world context");
                    // Only these four exact native controllers have been audited for this fixture.
                    int id = gt.getMetaTileID();
                    String controller = id == 1000 ? "MTEElectricBlastFurnace"
                        : id == 15514 ? "MTEIndustrialElectrolyzer"
                            : id == 1169 ? "MTELargeChemicalReactor" : id == 15512 ? "MTEIndustrialCentrifuge" : null;
                    if (controller != null) {
                        require(
                            gt.getMetaTileEntity()
                                .getClass()
                                .getName()
                                .equals("gregtech.common.tileentities.machines.multi." + controller),
                            "audited native controller callback class");
                        gt.getMetaTileEntity()
                            .onTextureUpdate();
                    }
                }
            }
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return new ChunkProviderClient(this);
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public net.minecraft.entity.Entity getEntityByID(int id) {
            return null;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            NBTTagCompound row = rows == null ? null : rows.get(key(x, y, z));
            return row == null ? net.minecraft.init.Blocks.air : Block.getBlockFromName(row.getString("block"));
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            NBTTagCompound row = rows == null ? null : rows.get(key(x, y, z));
            return row == null ? 0 : row.getInteger("meta");
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            String key = key(x, y, z);
            if (tiles == null || rows == null) return null;
            if (tiles.containsKey(key)) return tiles.get(key);
            NBTTagCompound row = rows.get(key);
            if (row == null || !row.hasKey("tile", 10)) return null;
            Block block = getBlock(x, y, z);
            TileEntity tile = block.createTileEntity(this, row.getInteger("meta"));
            require(tile != null, "native tile factory for " + block);
            tile.setWorldObj(this);
            NBTTagCompound saved = (NBTTagCompound) row.getCompoundTag("tile")
                .copy();
            saved.setInteger("x", x);
            saved.setInteger("y", y);
            saved.setInteger("z", z);
            tile.readFromNBT(saved);
            tile.xCoord = x;
            tile.yCoord = y;
            tile.zCoord = z;
            tiles.put(key, tile);
            return tile;
        }

        @Override
        public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minimum) {
            return 0xf000f0;
        }

        @Override
        public float getLightBrightness(int x, int y, int z) {
            return 1;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return rows != null && rows.containsKey(key(x, y, z));
        }

        @Override
        public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
            return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            return false;
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            return false;
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {}

        @Override
        public void removeTileEntity(int x, int y, int z) {}

        @Override
        public void markBlockForUpdate(int x, int y, int z) {}

        @Override
        public void markTileEntityChunkModified(int x, int y, int z, TileEntity tile) {}

        @Override
        public void notifyBlockChange(int x, int y, int z, Block block) {}

        @Override
        public void notifyBlocksOfNeighborChange(int x, int y, int z, Block block) {}

        @Override
        public void notifyBlockOfNeighborChange(int x, int y, int z, Block block) {}

        @Override
        public boolean spawnEntityInWorld(net.minecraft.entity.Entity entity) {
            return false;
        }

        @Override
        public void scheduleBlockUpdate(int x, int y, int z, Block block, int delay) {}

        @Override
        public boolean updateLightByType(net.minecraft.world.EnumSkyBlock type, int x, int y, int z) {
            return false;
        }

        @Override
        public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
