package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Audited native static block models rendered from a detached snapshot, never from live tiles. */
final class HologramBlockRender {

    private static final Map<HologramState.Cell, Model[]> MODELS = new IdentityHashMap<>();
    private static final Map<String, String> FAILURES = new HashMap<>();
    private static HologramState snapshot;
    private static HologramBlockAccess target, actual;
    private static final Field DRAWING = field(Tessellator.class, "isDrawing", "field_78415_z");
    private static final Field OFFSET_X = field(Tessellator.class, "xOffset", "field_78408_v");
    private static final Field OFFSET_Y = field(Tessellator.class, "yOffset", "field_78407_w");
    private static final Field OFFSET_Z = field(Tessellator.class, "zOffset", "field_78417_x");
    private static final Field VERTEX_COUNT = field(Tessellator.class, "vertexCount", "field_78406_i");
    private static final Field RAW_BUFFER = field(Tessellator.class, "rawBuffer", "field_78405_h");
    private static final Field RAW_INDEX = field(Tessellator.class, "rawBufferIndex", "field_147569_p");
    private static final Field WORLD_PASS = field(ForgeHooksClient.class, "worldRenderPass");
    private static final Field HANDLERS = field(RenderingRegistry.class, "blockRenderers");
    private static final Field[] TESS_STATE = { field(Tessellator.class, "textureU", "field_78403_j"),
        field(Tessellator.class, "textureV", "field_78404_k"), field(Tessellator.class, "brightness", "field_78401_l"),
        field(Tessellator.class, "color", "field_78402_m"), field(Tessellator.class, "normal", "field_78416_y"),
        field(Tessellator.class, "hasColor", "field_78399_n"), field(Tessellator.class, "hasTexture", "field_78400_o"),
        field(Tessellator.class, "hasBrightness", "field_78414_p"),
        field(Tessellator.class, "hasNormals", "field_78413_q"),
        field(Tessellator.class, "isColorDisabled", "field_78410_t"),
        field(Tessellator.class, "drawMode", "field_78409_u") };

    static int compiled, vertices;

    private HologramBlockRender() {}

    static void begin(HologramState state) {
        if (snapshot == state) return;
        HologramRenderer.nativeSnapshotChanged();
        clear();
        snapshot = state;
        target = new HologramBlockAccess(state, false);
        actual = new HologramBlockAccess(state, true);
    }

    static void clear() {
        for (Model[] pair : MODELS.values())
            for (Model model : pair) if (model != null && model.list != 0) GL11.glDeleteLists(model.list, 1);
        MODELS.clear();
        FAILURES.clear();
        snapshot = null;
        target = actual = null;
        compiled = vertices = 0;
    }

    static String failure(HologramState.Cell cell, boolean current) {
        return FAILURES.getOrDefault((current ? cell.id : cell.wantId), "");
    }

    static boolean ready(HologramState.Cell cell, boolean current) {
        if (snapshot == null) return false;
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        if (!HologramNativeTiles.supported(cell.block(current))) return false;
        Model[] pair = MODELS.computeIfAbsent(cell, unused -> new Model[2]);
        int side = current ? 1 : 0;
        if (pair[side] == null) pair[side] = compile(cell, current);
        return pair[side].list != 0;
    }

    static void draw(HologramState.Cell cell, boolean current) {
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        Model[] pair = MODELS.get(cell);
        if (pair == null || pair[current ? 1 : 0] == null) return;
        Model model = pair[current ? 1 : 0];
        if (model.list == 0) return;
        int originalMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        pushMatrices();
        try {
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            GL11.glCallList(model.list);
        } finally {
            popMatrices(originalMode);
            GL11.glPopAttrib();
        }
    }

    static int vertexCount(HologramState.Cell cell, boolean current) {
        Model[] pair = MODELS.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? 0 : pair[current ? 1 : 0].vertices;
    }

    static int uniquePositions(HologramState.Cell cell, boolean current) {
        Model[] pair = MODELS.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? 0 : pair[current ? 1 : 0].positions;
    }

    static boolean missingTexture(HologramState.Cell cell, boolean current) {
        Model[] pair = MODELS.get(cell);
        return pair != null && pair[current ? 1 : 0] != null && pair[current ? 1 : 0].missingTexture;
    }

    static List<double[]> positions(HologramState.Cell cell, boolean current) {
        Model[] pair = MODELS.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? java.util.Collections.emptyList()
            : pair[current ? 1 : 0].points;
    }

    /** Smoke evidence that a nondegenerate native face used a particular atlas material. */
    static boolean usesIcon(HologramState.Cell cell, boolean current, IIcon icon) {
        Model[] pair = MODELS.get(cell);
        if (pair == null || pair[current ? 1 : 0] == null || icon == null) return false;
        for (double[] uv : pair[current ? 1 : 0].textureCenters)
            if (uv[0] > icon.getMinU() + 1e-6 && uv[0] < icon.getMaxU() - 1e-6
                && uv[1] > icon.getMinV() + 1e-6
                && uv[1] < icon.getMaxV() - 1e-6) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Model compile(HologramState.Cell cell, boolean current) {
        Block block = cell.block(current);
        HologramBlockAccess access = current ? actual : target;
        TileEntity tile = access.getTileEntity(cell.x, cell.y, cell.z);
        HologramNativeRenderRegistry.Adapter adapter = HologramNativeRenderRegistry.adapter(block);
        if (tile == null
            && (adapter == null || adapter.requiresTile(block, access.getBlockMetadata(cell.x, cell.y, cell.z))))
            return failed(cell, current, "native tile unavailable: " + access.failure(cell.x, cell.y, cell.z));
        Tessellator tess = Tessellator.instance;
        int list = 0, count = 0;
        boolean opened = false;
        boolean invokedNative = false;
        Set<String> positions = new HashSet<>();
        List<double[]> points = new ArrayList<>();
        List<double[]> textureCenters = new ArrayList<>();
        boolean missing = false;
        int previousPass = MinecraftForgeClient.getRenderPass();
        int previousWorldPass = ForgeHooksClient.getWorldRenderPass();
        double ox = 0, oy = 0, oz = 0;
        Object[] tessState = new Object[TESS_STATE.length];
        boolean capturedTessState = false;
        double[] bounds = { block.getBlockBoundsMinX(), block.getBlockBoundsMinY(), block.getBlockBoundsMinZ(),
            block.getBlockBoundsMaxX(), block.getBlockBoundsMaxY(), block.getBlockBoundsMaxZ() };
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(0xffffffff); // GL_CLIENT_ALL_ATTRIB_BITS (OpenGL compatibility profile)
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        pushMatrices();
        try {
            ox = OFFSET_X.getDouble(tess);
            oy = OFFSET_Y.getDouble(tess);
            oz = OFFSET_Z.getDouble(tess);
            for (int i = 0; i < TESS_STATE.length; i++) tessState[i] = TESS_STATE[i].get(tess);
            capturedTessState = true;
            if (DRAWING.getBoolean(tess)) return failed(cell, current, "caller tessellator is active");
            ISimpleBlockRenderingHandler handler = ((Map<Integer, ISimpleBlockRenderingHandler>) HANDLERS
                .get(RenderingRegistry.instance())).get(block.getRenderType());
            if (handler == null) return failed(cell, current, "native ISBRH unavailable");
            if (adapter != null) handler = adapter.renderer(block, handler);
            if (handler == null) return failed(cell, current, "adapter renderer unavailable");
            // Carpenter's registered renderer stores the current TE in mutable fields.
            // Use a fresh handler so a detached preview never replaces its live-world context.
            if (adapter == null && handler.getClass()
                .getName()
                .startsWith("com.carpentersblocks.renderer."))
                handler = (ISimpleBlockRenderingHandler) handler.getClass()
                    .getConstructor()
                    .newInstance();
            list = GL11.glGenLists(1);
            if (list == 0) return failed(cell, current, "display list allocation failed");
            RenderBlocks renderer = new RenderBlocks(access);
            renderer.renderAllFaces = true;
            renderer.enableAO = false;
            GL11.glNewList(list, GL11.GL_COMPILE);
            opened = true;
            for (int pass = 0; pass <= 1; pass++) {
                WORLD_PASS.setInt(null, pass);
                ForgeHooksClient.setRenderPass(pass);
                tess.startDrawingQuads();
                tess.setTranslation(-cell.x, -cell.y, -cell.z);
                tess.setBrightness(0xf000f0);
                tess.setColorOpaque_F(1, 1, 1);
                invokedNative = true;
                handler.renderWorldBlock(access, cell.x, cell.y, cell.z, block, block.getRenderType(), renderer);
                int emitted = VERTEX_COUNT.getInt(tess);
                count += emitted;
                if (emitted > 0) {
                    int[] raw = (int[]) RAW_BUFFER.get(tess);
                    int stride = RAW_INDEX.getInt(tess) / emitted;
                    if (stride >= 3) for (int v = 0; v < emitted; v++) {
                        int quad = v / 4 * 4;
                        if (quad + 3 < emitted && !usableQuad(raw, stride, quad)) continue;
                        int start = v * stride;
                        if (positions.add(raw[start] + ":" + raw[start + 1] + ":" + raw[start + 2])
                            && Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"))
                            points.add(
                                new double[] { Float.intBitsToFloat(raw[start]), Float.intBitsToFloat(raw[start + 1]),
                                    Float.intBitsToFloat(raw[start + 2]) });
                    }
                    IIcon absent = Minecraft.getMinecraft()
                        .getTextureMapBlocks()
                        .getAtlasSprite("missingno");
                    if (stride >= 5) for (int v = 0; v + 3 < emitted; v += 4) {
                        if (!usableQuad(raw, stride, v)) continue;
                        double u = 0, w = 0;
                        for (int corner = 0; corner < 4; corner++) {
                            u += Float.intBitsToFloat(raw[(v + corner) * stride + 3]) / 4.0;
                            w += Float.intBitsToFloat(raw[(v + corner) * stride + 4]) / 4.0;
                        }
                        if (Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"))
                            textureCenters.add(new double[] { u, w });
                        if (u > absent.getMinU() + 1e-6 && u < absent.getMaxU() - 1e-6
                            && w > absent.getMinV() + 1e-6
                            && w < absent.getMaxV() - 1e-6) missing = true;
                    }
                }
                tess.draw();
            }
            GL11.glEndList();
            opened = false;
            // EnderIO deliberately emits a zero-area quad in its empty alpha pass.
            // That sentinel alone is not a usable native preview model.
            if (count == 0 || positions.size() < 3) {
                GL11.glDeleteLists(list, 1);
                return failed(cell, current, "native renderer produced no usable geometry");
            }
            compiled++;
            vertices += count;
            return new Model(list, count, positions.size(), points, textureCenters, missing);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failedNative) {
            if (list != 0) {
                if (opened) {
                    try {
                        if (DRAWING.getBoolean(tess)) tess.draw();
                    } catch (ReflectiveOperationException | RuntimeException ignored) {}
                    GL11.glEndList();
                    opened = false;
                }
                GL11.glDeleteLists(list, 1);
            }
            return failed(
                cell,
                current,
                failedNative.getClass()
                    .getSimpleName() + ": "
                    + failedNative.getMessage());
        } finally {
            if (opened) GL11.glEndList();
            if (invokedNative && adapter == null) releaseNativeContext(block);
            tess.setTranslation(ox, oy, oz);
            if (capturedTessState) for (int i = 0; i < TESS_STATE.length; i++) {
                try {
                    TESS_STATE[i].set(tess, tessState[i]);
                } catch (IllegalAccessException ignored) {}
            }
            ForgeHooksClient.setRenderPass(previousPass);
            try {
                WORLD_PASS.setInt(null, previousWorldPass);
            } catch (IllegalAccessException ignored) {}
            block.setBlockBounds(
                (float) bounds[0],
                (float) bounds[1],
                (float) bounds[2],
                (float) bounds[3],
                (float) bounds[4],
                (float) bounds[5]);
            popMatrices(matrixMode);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }

    private static Model failed(HologramState.Cell cell, boolean current, String message) {
        FAILURES.put(current ? cell.id : cell.wantId, message);
        return new Model(0, 0, 0, java.util.Collections.emptyList(), java.util.Collections.emptyList(), false);
    }

    private static Field field(Class<?> type, String... names) {
        return ReflectionHelper.findField(type, names);
    }

    /** Exact public cleanup APIs for the three audited handlers with auxiliary render context. */
    private static void releaseNativeContext(Block block) {
        try {
            String name = block.getClass()
                .getName();
            if (name.equals("codechicken.multipart.BlockMultipart")) {
                Class<?> type = Class.forName("codechicken.lib.render.CCRenderState");
                Object state = type.getMethod("instance")
                    .invoke(null);
                Object light = type.getField("lightMatrix")
                    .get(state);
                if (light != null) light.getClass()
                    .getField("access")
                    .set(light, null);
                type.getMethod("resetInstance")
                    .invoke(state);
            } else if (name.equals("gcewing.architecture.common.block.BlockShape")) {
                Object shader = Class.forName("gcewing.architecture.ArchitectureCraftClient")
                    .getField("angelicaCompat")
                    .get(null);
                if (shader != null) shader.getClass()
                    .getMethod("resetShaderMaterialOverride")
                    .invoke(shader);
            } else if (name.equals("crazypants.enderio.conduit.BlockConduitBundle")) {
                Field facadeField = field(
                    Class.forName("crazypants.enderio.conduit.render.ConduitBundleRenderer"),
                    "facade");
                Object facade = ((ThreadLocal<?>) facadeField.get(null)).get();
                if (facade != null) facade.getClass()
                    .getMethod("setBlockOverride", Class.forName("crazypants.enderio.conduit.IConduitBundle"))
                    .invoke(facade, new Object[] { null });
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absentOptionalContext) {
            // The GL/Tessellator scope below is unconditional even without an optional shader bridge.
        }
    }

    private static boolean usableQuad(int[] raw, int stride, int first) {
        for (int triangle = 0; triangle < 2; triangle++) {
            int a = first * stride, b = (first + triangle + 1) * stride, c = (first + triangle + 2) * stride;
            double bx = Float.intBitsToFloat(raw[b]) - Float.intBitsToFloat(raw[a]);
            double by = Float.intBitsToFloat(raw[b + 1]) - Float.intBitsToFloat(raw[a + 1]);
            double bz = Float.intBitsToFloat(raw[b + 2]) - Float.intBitsToFloat(raw[a + 2]);
            double cx = Float.intBitsToFloat(raw[c]) - Float.intBitsToFloat(raw[a]);
            double cy = Float.intBitsToFloat(raw[c + 1]) - Float.intBitsToFloat(raw[a + 1]);
            double cz = Float.intBitsToFloat(raw[c + 2]) - Float.intBitsToFloat(raw[a + 2]);
            double nx = by * cz - bz * cy, ny = bz * cx - bx * cz, nz = bx * cy - by * cx;
            if (nx * nx + ny * ny + nz * nz > 1e-18) return true;
        }
        return false;
    }

    private static void pushMatrices() {
        for (int mode : new int[] { GL11.GL_PROJECTION, GL11.GL_TEXTURE, GL11.GL_MODELVIEW }) {
            GL11.glMatrixMode(mode);
            GL11.glPushMatrix();
        }
    }

    private static void popMatrices(int originalMode) {
        for (int mode : new int[] { GL11.GL_MODELVIEW, GL11.GL_TEXTURE, GL11.GL_PROJECTION }) {
            GL11.glMatrixMode(mode);
            GL11.glPopMatrix();
        }
        GL11.glMatrixMode(originalMode);
    }

    private static final class Model {

        final int list, vertices, positions;
        final List<double[]> points, textureCenters;
        final boolean missingTexture;

        Model(int list, int vertices, int positions, List<double[]> points, List<double[]> textureCenters,
            boolean missingTexture) {
            this.list = list;
            this.vertices = vertices;
            this.positions = positions;
            this.points = points;
            this.textureCenters = textureCenters;
            this.missingTexture = missingTexture;
        }
    }
}
