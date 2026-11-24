// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;

import org.lwjgl.opengl.GL11;

import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteDisplayList;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteRenderScope;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Produces Lite compatibility draws from audited static renderers and detached snapshot tiles. */
final class HologramBlockRender {

    static final class Cache {

        final Map<HologramState.Cell, Model[]> models = new IdentityHashMap<>();
        final Map<String, String> failures = new HashMap<>();
        HologramState snapshot;
        int compiled;
        HologramBlockAccess target, actual;
        VertexScratch scratch;
    }

    private static final Set<String> WARNED_FAILURES = new HashSet<>();
    private static final Field DRAWING = field(Tessellator.class, "isDrawing", "field_78415_z");
    private static final Field OFFSET_X = field(Tessellator.class, "xOffset", "field_78408_v");
    private static final Field OFFSET_Y = field(Tessellator.class, "yOffset", "field_78407_w");
    private static final Field OFFSET_Z = field(Tessellator.class, "zOffset", "field_78417_x");
    private static final Field VERTEX_COUNT = field(Tessellator.class, "vertexCount", "field_78406_i");
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

    private HologramBlockRender() {}

    private static Cache cache() {
        return HologramPreviewAssets.active().blocks;
    }

    static void begin(HologramState state) {
        Cache cache = cache();
        if (cache.snapshot == state) return;
        if (HologramPreviewAssets.active() == HologramPreviewAssets.EDITOR)
            HologramRenderer.editorNativeSnapshotChanged();
        beginViewport(state);
    }

    static void beginViewport(HologramState state) {
        Cache cache = cache();
        if (cache.snapshot == state) return;
        clear();
        cache.snapshot = state;
        HologramLinkNodePreview.begin();
        cache.target = new HologramBlockAccess(state, false);
        cache.actual = new HologramBlockAccess(state, true);
    }

    static void clear() {
        Cache cache = cache();
        for (Model[] pair : cache.models.values()) for (Model model : pair) if (model != null) model.close();
        cache.models.clear();
        cache.failures.clear();
        cache.snapshot = null;
        cache.target = cache.actual = null;
        cache.compiled = 0;
    }

    static void advance(HologramState previous, HologramState next, Set<HologramRegionCache.Key> dirty) {
        Cache cache = cache();
        if (cache.snapshot != previous) return;
        for (HologramRegionCache.Key key : dirty) {
            List<HologramState.Cell> cells = previous.regions.regions.get(key);
            if (cells == null) continue;
            for (HologramState.Cell cell : cells) {
                Model[] pair = cache.models.remove(cell);
                if (pair == null) continue;
                for (Model model : pair) if (model != null) model.close();
            }
        }
        cache.snapshot = next;
        // The spatial index is shared; constructing these views does not walk all cells.
        cache.target = new HologramBlockAccess(next, false);
        cache.actual = new HologramBlockAccess(next, true);
    }

    static String failure(HologramState.Cell cell, boolean current) {
        return cache().failures.getOrDefault((current ? cell.id : cell.wantId), "");
    }

    static boolean invisible(HologramState.Cell cell, boolean current) {
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        Block block = cell.block(current);
        HologramNativeRenderRegistry.Adapter adapter = HologramNativeRenderRegistry.adapter(block);
        return adapter != null && adapter.invisible(block, current ? cell.meta : cell.wantMeta);
    }

    /** An empty/air-only or partly unsupported snapshot is never inferred to be an intentional data-only model. */
    static boolean dataOnly(HologramState state, boolean current) {
        boolean declared = false;
        for (HologramState.Cell cell : state.cells) {
            boolean actual = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
            if ((actual ? cell.id : cell.wantId).equals("minecraft:air") || cell.status.equals("removed")) continue;
            if (!invisible(cell, actual)) return false;
            declared = true;
        }
        return declared;
    }

    static boolean ready(HologramState.Cell cell, boolean current) {
        if (cache().snapshot == null) return false;
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        if (!HologramNativeTiles.supported(cell.block(current))) return false;
        Model[] pair = cache().models.computeIfAbsent(cell, unused -> new Model[2]);
        int side = current ? 1 : 0;
        if (pair[side] == null) pair[side] = compile(cell, current);
        return pair[side].packet != null || pair[side].acceptedEmpty || pair[side].invisible;
    }

    static void draw(HologramState.Cell cell, boolean current) {
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        Model[] pair = cache().models.get(cell);
        if (pair == null || pair[current ? 1 : 0] == null) return;
        Model model = pair[current ? 1 : 0];
        if (model.packet == null) return;
        int originalMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        pushMatrices();
        try {
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            if (HologramNativeRenderRegistry.doubleSided(cell.block(current))) GL11.glDisable(GL11.GL_CULL_FACE);
            model.packet.draw();
        } finally {
            popMatrices(originalMode);
            GL11.glPopAttrib();
        }
    }

    static int vertexCount(HologramState.Cell cell, boolean current) {
        Model[] pair = cache().models.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? 0 : pair[current ? 1 : 0].vertices;
    }

    static int uniquePositions(HologramState.Cell cell, boolean current) {
        Model[] pair = cache().models.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? 0 : pair[current ? 1 : 0].positions;
    }

    static boolean missingTexture(HologramState.Cell cell, boolean current) {
        Model[] pair = cache().models.get(cell);
        return pair != null && pair[current ? 1 : 0] != null && pair[current ? 1 : 0].missingTexture;
    }

    static List<double[]> positions(HologramState.Cell cell, boolean current) {
        Model[] pair = cache().models.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? java.util.Collections.emptyList()
            : pair[current ? 1 : 0].points;
    }

    /** Opt-in AE GL evidence: detached geometry, UVs and emitted packed vertex colors. */
    static List<double[]> attributes(HologramState.Cell cell, boolean current) {
        Model[] pair = cache().models.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? java.util.Collections.emptyList()
            : pair[current ? 1 : 0].attributes;
    }

    /** Smoke evidence that a nondegenerate native face used a particular atlas material. */
    static boolean usesIcon(HologramState.Cell cell, boolean current, IIcon icon) {
        Model[] pair = cache().models.get(cell);
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
        Cache cache = cache();
        HologramBlockAccess access = current ? cache.actual : cache.target;
        HologramNativeRenderRegistry.Adapter adapter = HologramNativeRenderRegistry.adapter(block);
        if (adapter != null && adapter.invisible(block, access.getBlockMetadata(cell.x, cell.y, cell.z)))
            return new Model(
                null,
                0,
                0,
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                false,
                java.util.Collections.emptyList(),
                false,
                true);
        TileEntity tile = access.getTileEntity(cell.x, cell.y, cell.z);
        if (tile == null
            && (adapter == null || adapter.requiresTile(block, access.getBlockMetadata(cell.x, cell.y, cell.z))))
            return failed(cell, current, "native tile unavailable: " + access.failure(cell.x, cell.y, cell.z));
        return compile(cell, current, access, tile, adapter);
    }

    /** Opt-in control invokes the original registered ISBRH against independent native tiles. */
    static List<double[]> nativeControl(HologramState.Cell cell, boolean current, IBlockAccess world) {
        if (!Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"))
            throw new IllegalStateException("native renderer control requires extended compatibility smoke");
        try (Model control = compile(cell, current, world, world.getTileEntity(cell.x, cell.y, cell.z), null)) {
            if (control.packet == null)
                throw new IllegalStateException("native control failed: " + failure(cell, current));
            return control.attributes;
        }
    }

    /** Opt-in source reference uses native world face selection, including vanilla RenderBlocks. */
    static List<double[]> drawSourceReference(HologramState.Cell cell, boolean current, IBlockAccess world) {
        if (!Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"))
            throw new IllegalStateException("source render reference requires extended compatibility smoke");
        try (Model control = compile(cell, current, world, world.getTileEntity(cell.x, cell.y, cell.z), null, true)) {
            if (control.packet != null) control.packet.draw();
            return control.attributes;
        }
    }

    @SuppressWarnings("unchecked")
    private static Model compile(HologramState.Cell cell, boolean current, IBlockAccess access, TileEntity tile,
        HologramNativeRenderRegistry.Adapter adapter) {
        return compile(cell, current, access, tile, adapter, false);
    }

    @SuppressWarnings("unchecked")
    private static Model compile(HologramState.Cell cell, boolean current, IBlockAccess access, TileEntity tile,
        HologramNativeRenderRegistry.Adapter adapter, boolean sourceReference) {
        Block block = cell.block(current);
        Tessellator tess = Tessellator.instance;
        Tessellator callerTess = tess;
        MiaoVisonLiteDisplayList packet = null;
        int count = 0;
        boolean opened = false;
        boolean retained = false;
        boolean invokedNative = false;
        HologramAeNativeRender.CableContext aeCableContext = null;
        HologramNativeRenderRegistry.RenderScope renderScope = null;
        Cache cache = cache();
        VertexScratch scratch = cache.scratch == null ? new VertexScratch() : cache.scratch;
        cache.scratch = null;
        boolean captureGeometry = Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke");
        boolean captureAttributes = captureGeometry || Boolean.getBoolean("gtmb.hologram.aeClientSmoke");
        List<double[]> points = captureGeometry ? new ArrayList<>() : Collections.emptyList();
        List<double[]> textureCenters = captureGeometry ? new ArrayList<>() : Collections.emptyList();
        List<double[]> attributes = captureAttributes ? new ArrayList<>() : Collections.emptyList();
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
            HologramNativeRenderRegistry.Adapter rendererAdapter = adapter == null
                ? HologramNativeRenderRegistry.builtin(block)
                : adapter;
            if (handler == null && rendererAdapter == null && !sourceReference)
                return failed(cell, current, "native ISBRH unavailable");
            if (rendererAdapter != null) handler = rendererAdapter.renderer(block, handler);
            if (handler == null && !sourceReference) return failed(cell, current, "adapter renderer unavailable");
            aeCableContext = HologramAeNativeRender.cableContext(block, tile);
            if (rendererAdapter != null) {
                renderScope = rendererAdapter.renderScope(block, tile, handler);
                if (renderScope != null) access = renderScope.access();
            }
            packet = MiaoVisonLiteDisplayList.allocate();
            if (packet == null) return failed(cell, current, "display list allocation failed");
            RenderBlocks renderer = new RenderBlocks(access);
            renderer.renderAllFaces = !sourceReference;
            renderer.enableAO = false;
            // A native handler can interleave Tessellator flushes, texture changes and other GL commands.
            // Keep both full Forge passes in a single ordered packet. The raw vertices below are diagnostics.
            packet.begin();
            opened = true;
            // Angelica starts a GTNHLib DirectTessellator capture in glNewList.
            // Resolve it after the list opens, just as the native renderer does.
            tess = Tessellator.instance;
            for (int pass = 0; pass <= 1; pass++) {
                WORLD_PASS.setInt(null, pass);
                ForgeHooksClient.setRenderPass(pass);
                if (sourceReference && !block.canRenderInPass(pass)) continue;
                if (aeCableContext != null && block.getClass() == appeng.block.networking.BlockCableBus.class
                    && !block.canRenderInPass(pass)) continue;
                tess.startDrawingQuads();
                tess.setTranslation(-cell.x, -cell.y, -cell.z);
                tess.setBrightness(0xf000f0);
                tess.setColorOpaque_F(1, 1, 1);
                invokedNative = true;
                if (handler == null) renderer.renderBlockByRenderType(block, cell.x, cell.y, cell.z);
                else {
                    // Match RenderBlocks' native entry point before invoking the selected snapshot handler.
                    block.setBlockBoundsBasedOnState(access, cell.x, cell.y, cell.z);
                    renderer.setRenderBoundsFromBlock(block);
                    handler.renderWorldBlock(access, cell.x, cell.y, cell.z, block, block.getRenderType(), renderer);
                }
                int emitted = VERTEX_COUNT.getInt(tess);
                count += emitted;
                if (emitted > 0) {
                    int[] raw = scratch.raw = HologramVertexData.read(tess, emitted, scratch.raw);
                    IIcon absent = Minecraft.getMinecraft()
                        .getTextureMapBlocks()
                        .getAtlasSprite("missingno");
                    for (int first = 0; first < emitted; first += 4) {
                        int corners = Math.min(4, emitted - first);
                        if (corners == 4 && !usableQuad(raw, 8, first)) continue;
                        double u = 0, w = 0;
                        for (int vertex = first; vertex < first + corners; vertex++) {
                            int start = vertex * 8;
                            boolean added = scratch.add(raw[start], raw[start + 1], raw[start + 2]);
                            if (added && captureGeometry) points.add(
                                new double[] { Float.intBitsToFloat(raw[start]), Float.intBitsToFloat(raw[start + 1]),
                                    Float.intBitsToFloat(raw[start + 2]) });
                            if (corners == 4) {
                                float textureU = Float.intBitsToFloat(raw[start + 3]);
                                float textureV = Float.intBitsToFloat(raw[start + 4]);
                                u += textureU / 4.0;
                                w += textureV / 4.0;
                                if (captureAttributes) attributes.add(
                                    new double[] { Float.intBitsToFloat(raw[start]),
                                        Float.intBitsToFloat(raw[start + 1]), Float.intBitsToFloat(raw[start + 2]),
                                        textureU, textureV, raw[start + 5] });
                            }
                        }
                        if (corners == 4) {
                            if (captureGeometry) textureCenters.add(new double[] { u, w });
                            if (u > absent.getMinU() + 1e-6 && u < absent.getMaxU() - 1e-6
                                && w > absent.getMinV() + 1e-6
                                && w < absent.getMaxV() - 1e-6) missing = true;
                        }
                    }

                }
                tess.draw();
            }
            packet.finish();
            opened = false;
            // EnderIO deliberately emits a zero-area quad in its empty alpha pass.
            // That sentinel alone is not a usable native preview model.
            if (count == 0 || scratch.size < 3) {
                if (!sourceReference && count == 0
                    && adapter != null
                    && adapter.allowsEmptyGeometry(
                        block,
                        current ? cell.meta : cell.wantMeta,
                        access,
                        cell.x,
                        cell.y,
                        cell.z)) {
                    cache.compiled++;
                    return new Model(null, 0, 0, points, textureCenters, missing, attributes, true);
                }
                if (sourceReference) return new Model(null, 0, 0, points, textureCenters, missing, attributes);
                return failed(cell, current, "native renderer produced no usable geometry");
            }
            if (!sourceReference) {
                cache.compiled++;
            }
            Model model = new Model(packet, count, scratch.size, points, textureCenters, missing, attributes);
            retained = true;
            return model;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failedNative) {
            if (opened) {
                try {
                    if (DRAWING.getBoolean(tess)) tess.draw();
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
            return failed(
                cell,
                current,
                failedNative.getClass()
                    .getSimpleName() + ": "
                    + failedNative.getMessage());
        } finally {
            scratch.reset();
            cache.scratch = scratch;
            if (!retained && packet != null) packet.close();
            if (invokedNative && adapter == null) MiaoVisonLiteRenderScope.releaseNativeContext(block);
            if (aeCableContext != null) aeCableContext.close();
            if (renderScope != null) renderScope.close();
            callerTess.setTranslation(ox, oy, oz);
            if (capturedTessState) for (int i = 0; i < TESS_STATE.length; i++) {
                try {
                    TESS_STATE[i].set(callerTess, tessState[i]);
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
        String id = current ? cell.id : cell.wantId;
        int meta = current ? cell.meta : cell.wantMeta;
        cache().failures.put(id, message);
        String diagnostic = id + ":" + meta + " " + message;
        // Keep diagnostics useful across snapshot changes without logging once per missing block/frame.
        if (WARNED_FAILURES.size() < 128 && WARNED_FAILURES.add(diagnostic))
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn(
                "[GTMB-NATIVE-PREVIEW] block={} meta={} side={} reason={}",
                id,
                meta,
                current ? "current" : "target",
                message);
        return new Model(null, 0, 0, java.util.Collections.emptyList(), java.util.Collections.emptyList(), false);
    }

    private static Field field(Class<?> type, String... names) {
        return ReflectionHelper.findField(type, names);
    }

    /** Reused raw vertex storage and an exact set of the three position bit patterns. */
    private static final class VertexScratch {

        private static final int MAX_RETAINED_INTS = 1 << 16;
        int[] raw = new int[0];
        private int[] slots = new int[128];
        int size;

        boolean add(int x, int y, int z) {
            if (size >= slots.length / 8) {
                int[] previous = slots;
                slots = new int[previous.length * 2];
                size = 0;
                for (int at = 0; at < previous.length; at += 4)
                    if (previous[at] != 0) add(previous[at + 1], previous[at + 2], previous[at + 3]);
            }
            int hash = Integer.rotateLeft(x, 11) ^ Integer.rotateLeft(y, 22) ^ z;
            hash = it.unimi.dsi.fastutil.HashCommon.mix(hash);
            int mask = slots.length - 1;
            int at = (hash * 4) & mask;
            while (slots[at] != 0) {
                if (slots[at + 1] == x && slots[at + 2] == y && slots[at + 3] == z) return false;
                at = (at + 4) & mask;
            }
            slots[at] = 1;
            slots[at + 1] = x;
            slots[at + 2] = y;
            slots[at + 3] = z;
            size++;
            return true;
        }

        void reset() {
            if (slots.length > MAX_RETAINED_INTS) slots = new int[128];
            else Arrays.fill(slots, 0);
            if (raw.length > MAX_RETAINED_INTS) raw = new int[0];
            size = 0;
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

    private static final class Model implements AutoCloseable {

        final MiaoVisonLiteDisplayList packet;
        final int vertices, positions;
        final List<double[]> points, textureCenters;
        final List<double[]> attributes;
        final boolean missingTexture;
        final boolean acceptedEmpty;
        final boolean invisible;

        Model(MiaoVisonLiteDisplayList packet, int vertices, int positions, List<double[]> points,
            List<double[]> textureCenters, boolean missingTexture) {
            this(
                packet,
                vertices,
                positions,
                points,
                textureCenters,
                missingTexture,
                java.util.Collections.emptyList());
        }

        Model(MiaoVisonLiteDisplayList packet, int vertices, int positions, List<double[]> points,
            List<double[]> textureCenters, boolean missingTexture, List<double[]> attributes) {
            this(packet, vertices, positions, points, textureCenters, missingTexture, attributes, false);
        }

        Model(MiaoVisonLiteDisplayList packet, int vertices, int positions, List<double[]> points,
            List<double[]> textureCenters, boolean missingTexture, List<double[]> attributes, boolean acceptedEmpty) {
            this(packet, vertices, positions, points, textureCenters, missingTexture, attributes, acceptedEmpty, false);
        }

        Model(MiaoVisonLiteDisplayList packet, int vertices, int positions, List<double[]> points,
            List<double[]> textureCenters, boolean missingTexture, List<double[]> attributes, boolean acceptedEmpty,
            boolean invisible) {
            this.packet = packet;
            this.vertices = vertices;
            this.positions = positions;
            this.points = points;
            this.textureCenters = textureCenters;
            this.attributes = attributes;
            this.missingTexture = missingTexture;
            this.acceptedEmpty = acceptedEmpty;
            this.invisible = invisible;
        }

        @Override
        public void close() {
            if (packet != null) packet.close();
        }
    }
}
