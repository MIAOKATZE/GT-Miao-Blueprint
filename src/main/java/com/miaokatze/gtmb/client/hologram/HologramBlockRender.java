package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
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

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Audited native static block models rendered from a detached snapshot, never from live tiles. */
final class HologramBlockRender {

    private static final Map<HologramState.Cell, Model[]> MODELS = new IdentityHashMap<>();
    private static final DeferredMap DEFERRED = new DeferredMap();
    private static final Map<String, String> FAILURES = new HashMap<>();
    private static final Set<String> WARNED_FAILURES = new HashSet<>();
    private static HologramState snapshot;
    private static HologramBlockAccess target, actual;
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

    private static HologramNativePreparation.Budget preparationBudget;
    static int compiled, vertices;

    private HologramBlockRender() {}

    static void begin(HologramState state) {
        if (snapshot == state) return;
        HologramRenderer.nativeSnapshotChanged();
        clear();
        snapshot = state;
        HologramLinkNodePreview.begin();
        target = new HologramBlockAccess(state, false);
        actual = new HologramBlockAccess(state, true);
    }

    static void clear() {
        for (Model[] pair : MODELS.values())
            for (Model model : pair) if (model != null && model.list != 0) GL11.glDeleteLists(model.list, 1);
        MODELS.clear();
        DEFERRED.clear();
        FAILURES.clear();
        snapshot = null;
        if (target != null) target.close();
        if (actual != null) actual.close();
        target = actual = null;
        preparationBudget = null;
        compiled = vertices = 0;
    }

    static void advance(HologramState previous, HologramState next, Set<HologramRegionCache.Key> dirty) {
        if (snapshot != previous) return;
        Set<Integer> indices = new HashSet<>();
        for (HologramRegionCache.Key key : dirty)
            for (HologramState.Cell cell : previous.regions.regions.getOrDefault(key, java.util.Collections.emptyList()))
                indices.add(cell.index);
        advanceCells(previous, next, indices);
    }

    static void advanceCells(HologramState previous, HologramState next, Set<Integer> dirty) {
        if (snapshot != previous) return;
        for (int index : dirty) {
            if (index < 0 || index >= previous.cells.size()) continue;
            HologramState.Cell cell = previous.cells.get(index);
            DEFERRED.remove(cell);
            Model[] pair = MODELS.remove(cell);
            if (pair == null) continue;
            for (Model model : pair) if (model != null && model.list != 0) GL11.glDeleteLists(model.list, 1);
        }
        snapshot = next;
        // Source/topology publications still fail the access continuation guard and replace the owner.
        if (target == null || !target.advanceCells(previous, next, dirty)) {
            if (target != null) target.close();
            target = new HologramBlockAccess(next, false);
        }
        if (actual == null || !actual.advanceCells(previous, next, dirty)) {
            if (actual != null) actual.close();
            actual = new HologramBlockAccess(next, true);
        }
    }

    static String failure(HologramState.Cell cell, boolean current) {
        current = effectiveCurrent(cell, current);
        return FAILURES.getOrDefault((current ? cell.id : cell.wantId), "");
    }

    static boolean effectiveCurrent(HologramState.Cell cell, boolean current) {
        return current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
    }

    /** Pure query, safe inside a parent display list/tessellator. No adapters or factories run. */
    static boolean cachedReady(HologramState.Cell cell, boolean current) {
        Model[] pair = MODELS.get(cell);
        Model model = pair == null ? null : pair[effectiveCurrent(cell, current) ? 1 : 0];
        return model != null && model.list != 0;
    }

    static void beginPreparationSlice(long deadline) {
        preparationBudget = new HologramNativePreparation.Budget(deadline, 32, System::nanoTime);
    }

    static void endPreparationSlice() {
        preparationBudget = null;
    }

    /** The existing deferred map is also the fair retry order; this is not a second preparation queue. */
    static Set<HologramRegionCache.Key> retryDeferred(HologramState state) {
        Set<HologramRegionCache.Key> changed = new HashSet<>();
        for (HologramState.Cell cell : retryDeferredCells(state)) changed.add(HologramRegionCache.key(cell));
        return changed;
    }

    /** Return the consumers that actually resolved, not every neighbor sharing their region. */
    static Set<HologramState.Cell> retryDeferredCells(HologramState state) {
        Set<HologramState.Cell> changed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (snapshot != state || preparationBudget == null || !callbacksSafe()) return changed;
        int remaining = Math.min(32, DEFERRED.size());
        while (remaining-- > 0 && preparationBudget.available() && !DEFERRED.isEmpty()) {
            HologramState.Cell cell = DEFERRED.rotate();
            int mask = DEFERRED.getOrDefault(cell, 0);
            for (boolean current : new boolean[] { false, true }) {
                int bit = current ? 1 : 2;
                if ((mask & bit) == 0 || !preparationBudget.available()) continue;
                ready(cell, current);
                // Iterate stored sides explicitly; public queries normalize requested sides for display consumers.
                if (!deferredSide(cell, current)) changed.add(cell);
            }
        }
        return changed;
    }

    /** Whole-owner handoff for a completed capture preflight; detached tiles are never transplanted. */
    static void adoptPreparedAccesses(HologramState state, HologramBlockAccess preparedTarget,
        HologramBlockAccess preparedActual) {
        if (preparedTarget != null && !preparedTarget.owns(state, false)
            || preparedActual != null && !preparedActual.owns(state, true))
            throw new IllegalArgumentException("native capture access owner mismatch");
        if (!callbacksSafe()) throw new IllegalStateException("native capture handoff inside active tessellator");
        HologramRenderer.nativeSnapshotChanged();
        clear();
        snapshot = state;
        HologramLinkNodePreview.begin();
        target = preparedTarget == null ? new HologramBlockAccess(state, false) : preparedTarget;
        actual = preparedActual == null ? new HologramBlockAccess(state, true) : preparedActual;
    }

    private static boolean callbacksSafe() {
        try {
            return !DRAWING.getBoolean(Tessellator.instance);
        } catch (IllegalAccessException | RuntimeException unavailable) {
            return false;
        }
    }

    static boolean ready(HologramState.Cell cell, boolean current) {
        if (snapshot == null) return false;
        current = effectiveCurrent(cell, current);
        Model[] pair = MODELS.get(cell);
        int side = current ? 1 : 0;
        if (pair != null && pair[side] != null) return pair[side].list != 0;
        // In particular, never turn an active parent tessellator into a cached native failure.
        if (!callbacksSafe()) return false;
        if (!HologramNativeTiles.supported(cell.block(current))) {
            undefer(cell, current);
            return false;
        }
        if (!HologramSnapshotModelsClient.ready(snapshot, cell, current)) {
            defer(cell, current);
            return false;
        }
        HologramNativePreparation.Budget budget = preparationBudget == null
            ? new HologramNativePreparation.Budget(Long.MAX_VALUE, 32, System::nanoTime)
            : preparationBudget;
        if (!budget.available()) {
            defer(cell, current);
            return false;
        }
        pair = MODELS.computeIfAbsent(cell, unused -> new Model[2]);
        pair[side] = compile(cell, current, budget);
        return pair[side] != null && pair[side].list != 0;
    }

    private static void undefer(HologramState.Cell cell, boolean current) {
        current = effectiveCurrent(cell, current);
        int remaining = DEFERRED.getOrDefault(cell, 0) & ~(current ? 1 : 2);
        if (remaining == 0) DEFERRED.remove(cell);
        else DEFERRED.put(cell, remaining);
    }

    static void defer(HologramState owner, HologramState.Cell cell, boolean current) {
        // Independent capture preflight must never enqueue cells into a live preview's retry map.
        if (snapshot == owner) defer(cell, current);
    }

    static void defer(HologramState.Cell cell, boolean current) {
        current = effectiveCurrent(cell, current);
        DEFERRED.put(cell, DEFERRED.getOrDefault(cell, 0) | (current ? 1 : 2));
    }

    static boolean deferred(HologramState.Cell cell, boolean current) {
        return deferredSide(cell, effectiveCurrent(cell, current));
    }

    /** Raw stored-side predicate for retry traversal only; target and actual ordinary models stay independent. */
    private static boolean deferredSide(HologramState.Cell cell, boolean current) {
        return (DEFERRED.getOrDefault(cell, 0) & (current ? 1 : 2)) != 0;
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
            // A world hologram enables back-face culling for ordinary solid cube meshes.
            // Carpenter slopes include open triangular faces: their material remains visible
            // from either side in the translucent preview, including mirrored/negative slopes.
            if (cell.block(current)
                .getClass()
                .getName()
                .equals("com.carpentersblocks.block.BlockCarpentersSlope")) GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glCallList(model.list);
        } finally {
            popMatrices(originalMode);
            GL11.glPopAttrib();
        }
    }

    static int vertexCount(HologramState.Cell cell, boolean current) {
        current = effectiveCurrent(cell, current);
        Model[] pair = MODELS.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? 0 : pair[current ? 1 : 0].vertices;
    }

    static int uniquePositions(HologramState.Cell cell, boolean current) {
        current = effectiveCurrent(cell, current);
        Model[] pair = MODELS.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? 0 : pair[current ? 1 : 0].positions;
    }

    static boolean missingTexture(HologramState.Cell cell, boolean current) {
        current = effectiveCurrent(cell, current);
        Model[] pair = MODELS.get(cell);
        return pair != null && pair[current ? 1 : 0] != null && pair[current ? 1 : 0].missingTexture;
    }

    static List<double[]> positions(HologramState.Cell cell, boolean current) {
        current = effectiveCurrent(cell, current);
        Model[] pair = MODELS.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? java.util.Collections.emptyList()
            : pair[current ? 1 : 0].points;
    }

    /** Opt-in AE GL evidence: detached geometry, UVs and emitted packed vertex colors. */
    static List<double[]> attributes(HologramState.Cell cell, boolean current) {
        current = effectiveCurrent(cell, current);
        Model[] pair = MODELS.get(cell);
        return pair == null || pair[current ? 1 : 0] == null ? java.util.Collections.emptyList()
            : pair[current ? 1 : 0].attributes;
    }

    /** Smoke evidence that a nondegenerate native face used a particular atlas material. */
    static boolean usesIcon(HologramState.Cell cell, boolean current, IIcon icon) {
        current = effectiveCurrent(cell, current);
        Model[] pair = MODELS.get(cell);
        if (pair == null || pair[current ? 1 : 0] == null || icon == null) return false;
        for (double[] uv : pair[current ? 1 : 0].textureCenters)
            if (uv[0] > icon.getMinU() + 1e-6 && uv[0] < icon.getMaxU() - 1e-6
                && uv[1] > icon.getMinV() + 1e-6
                && uv[1] < icon.getMaxV() - 1e-6) return true;
        return false;
    }

    private static Model compile(HologramState.Cell cell, boolean current, HologramNativePreparation.Budget budget) {
        Block block = cell.block(current);
        HologramBlockAccess access = current ? actual : target;
        HologramNativePreparation.Outcome outcome = access.prepare(cell, budget);
        if (outcome == HologramNativePreparation.Outcome.WAITING) {
            defer(cell, current);
            return null;
        }
        if (outcome == HologramNativePreparation.Outcome.FAILED) {
            undefer(cell, current);
            return failed(cell, current, "native tile unavailable: " + access.failure(cell.x, cell.y, cell.z));
        }
        TileEntity tile;
        boolean complete;
        access.beginModel(cell);
        try {
            tile = access.getTileEntity(cell.x, cell.y, cell.z);
        } finally {
            complete = access.endModel();
        }
        if (!complete) {
            defer(cell, current);
            return null;
        }
        HologramNativeRenderRegistry.Adapter adapter = HologramNativeRenderRegistry.adapter(block);
        if (tile == null
            && (adapter == null || adapter.requiresTile(block, access.getBlockMetadata(cell.x, cell.y, cell.z)))) {
            undefer(cell, current);
            return failed(cell, current, "native tile unavailable: " + access.failure(cell.x, cell.y, cell.z));
        }
        if (!budget.take()) {
            defer(cell, current);
            return null;
        }
        Model model;
        // An earlier yield must not suppress a genuine terminal renderer diagnostic.
        undefer(cell, current);
        access.beginModel(cell, true);
        try {
            model = compile(cell, current, access, tile, adapter);
        } finally {
            complete = access.endModel();
        }
        if (!complete) {
            if (model.list != 0) {
                GL11.glDeleteLists(model.list, 1);
                compiled--;
                vertices -= model.vertices;
            }
            if (!access.modelFailure()
                .isEmpty()) {
                undefer(cell, current);
                return failed(cell, current, access.modelFailure());
            }
            defer(cell, current);
            return null;
        }
        undefer(cell, current);
        return model;
    }

    /** Opt-in control invokes the original registered ISBRH against independent native tiles. */
    static List<double[]> nativeControl(HologramState.Cell cell, boolean current, IBlockAccess world) {
        if (!Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"))
            throw new IllegalStateException("native renderer control requires extended compatibility smoke");
        Model control = compile(cell, current, world, world.getTileEntity(cell.x, cell.y, cell.z), null);
        try {
            if (control.list == 0) throw new IllegalStateException("native control failed: " + failure(cell, current));
            return control.attributes;
        } finally {
            if (control.list != 0) GL11.glDeleteLists(control.list, 1);
        }
    }

    /** Opt-in source reference uses native world face selection, including vanilla RenderBlocks. */
    static List<double[]> drawSourceReference(HologramState.Cell cell, boolean current, IBlockAccess world) {
        if (!Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"))
            throw new IllegalStateException("source render reference requires extended compatibility smoke");
        Model control = compile(cell, current, world, world.getTileEntity(cell.x, cell.y, cell.z), null, true);
        try {
            if (control.list != 0) GL11.glCallList(control.list);
            return control.attributes;
        } finally {
            if (control.list != 0) GL11.glDeleteLists(control.list, 1);
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
        int list = 0, count = 0;
        boolean opened = false;
        boolean invokedNative = false;
        HologramAeNativeRender.CableContext aeCableContext = null;
        HologramRenderWorld.CarpenterContext carpenterContext = null;
        boolean extendedSmoke = Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke");
        HologramNativeGeometryInspection inspection = new HologramNativeGeometryInspection(
            extendedSmoke,
            extendedSmoke || Boolean.getBoolean("gtmb.hologram.aeClientSmoke"));
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
            if (handler == null && !sourceReference) return failed(cell, current, "native ISBRH unavailable");
            if (adapter != null) handler = adapter.renderer(block, handler);
            if (handler == null && !sourceReference) return failed(cell, current, "adapter renderer unavailable");
            aeCableContext = HologramAeNativeRender.cableContext(block, tile);
            // Carpenter's registered renderer stores the current TE in mutable fields.
            // Use a fresh handler so a detached preview never replaces its live-world context.
            if (adapter == null && handler != null
                && handler.getClass()
                    .getName()
                    .startsWith("com.carpentersblocks.renderer."))
                handler = (ISimpleBlockRenderingHandler) handler.getClass()
                    .getConstructor()
                    .newInstance();
            if (adapter == null && handler != null
                && handler.getClass()
                    .getName()
                    .startsWith("com.carpentersblocks.renderer.")
                && tile != null
                && tile.getWorldObj() instanceof HologramRenderWorld) {
                HologramRenderWorld world = (HologramRenderWorld) tile.getWorldObj();
                carpenterContext = world.carpenterRender(tile);
                access = world;
            }
            list = GL11.glGenLists(1);
            if (list == 0) return failed(cell, current, "display list allocation failed");
            RenderBlocks renderer = new RenderBlocks(access);
            renderer.renderAllFaces = !sourceReference;
            renderer.enableAO = false;
            GL11.glNewList(list, GL11.GL_COMPILE);
            opened = true;
            // Angelica starts a GTNHLib DirectTessellator capture in glNewList.
            // Resolve it after the list opens, just as the native renderer does.
            tess = Tessellator.instance;
            for (int pass = 0; pass <= 1; pass++) {
                WORLD_PASS.setInt(null, pass);
                ForgeHooksClient.setRenderPass(pass);
                if (sourceReference && !block.canRenderInPass(pass)) continue;
                if (adapter == HologramChiselNativeRender.INSTANCE && !block.canRenderInPass(pass)) continue;
                if (aeCableContext != null && block.getClass() == appeng.block.networking.BlockCableBus.class
                    && !block.canRenderInPass(pass)) continue;
                tess.startDrawingQuads();
                tess.setTranslation(-cell.x, -cell.y, -cell.z);
                tess.setBrightness(0xf000f0);
                tess.setColorOpaque_F(1, 1, 1);
                invokedNative = true;
                if (handler == null) renderer.renderBlockByRenderType(block, cell.x, cell.y, cell.z);
                else handler.renderWorldBlock(access, cell.x, cell.y, cell.z, block, block.getRenderType(), renderer);
                int emitted = VERTEX_COUNT.getInt(tess);
                count += emitted;
                if (emitted > 0) {
                    int[] raw = HologramVertexData.read(tess, emitted);
                    IIcon absent = Minecraft.getMinecraft()
                        .getTextureMapBlocks()
                        .getAtlasSprite("missingno");
                    inspection
                        .inspect(raw, emitted, absent.getMinU(), absent.getMaxU(), absent.getMinV(), absent.getMaxV());
                }
                tess.draw();
            }
            GL11.glEndList();
            opened = false;
            // EnderIO deliberately emits a zero-area quad in its empty alpha pass.
            // That sentinel alone is not a usable native preview model.
            if (count == 0 || inspection.uniquePositions() < 3) {
                GL11.glDeleteLists(list, 1);
                if (sourceReference) return new Model(
                    0,
                    0,
                    0,
                    inspection.points,
                    inspection.textureCenters,
                    inspection.missingTexture,
                    inspection.attributes);
                return failed(cell, current, "native renderer produced no usable geometry");
            }
            if (!sourceReference) {
                compiled++;
                vertices += count;
            }
            return new Model(
                list,
                count,
                inspection.uniquePositions(),
                inspection.points,
                inspection.textureCenters,
                inspection.missingTexture,
                inspection.attributes);
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
            if (aeCableContext != null) aeCableContext.close();
            if (carpenterContext != null) carpenterContext.close();
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
        if (deferred(cell, current))
            return new Model(0, 0, 0, java.util.Collections.emptyList(), java.util.Collections.emptyList(), false);
        String id = current ? cell.id : cell.wantId;
        int meta = current ? cell.meta : cell.wantMeta;
        FAILURES.put(id, message);
        String diagnostic = id + ":" + meta + " " + message;
        // Keep diagnostics useful across snapshot changes without logging once per missing block/frame.
        if (WARNED_FAILURES.size() < 128 && WARNED_FAILURES.add(diagnostic))
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn(
                "[GTMB-NATIVE-PREVIEW] block={} meta={} side={} reason={}",
                id,
                meta,
                current ? "current" : "target",
                message);
        return new Model(0, 0, 0, java.util.Collections.emptyList(), java.util.Collections.emptyList(), false);
    }

    /** Identity keys with a bounded-work rotation; unlike LinkedHashMap this never calls Cell.equals. */
    private static final class DeferredMap extends java.util.AbstractMap<HologramState.Cell, Integer> {

        private final IdentityHashMap<HologramState.Cell, Entry> index = new IdentityHashMap<>();
        private Entry first, last;

        private static final class Entry extends java.util.AbstractMap.SimpleEntry<HologramState.Cell, Integer> {

            Entry before, after;

            Entry(HologramState.Cell key, Integer value) {
                super(key, value);
            }
        }

        public Integer get(Object key) {
            Entry entry = index.get(key);
            return entry == null ? null : entry.getValue();
        }

        public boolean containsKey(Object key) {
            return index.containsKey(key);
        }

        public int size() {
            return index.size();
        }

        public Integer put(HologramState.Cell key, Integer value) {
            Entry entry = index.get(key);
            if (entry != null) return entry.setValue(value);
            entry = new Entry(key, value);
            index.put(key, entry);
            append(entry);
            return null;
        }

        private void append(Entry entry) {
            entry.before = last;
            entry.after = null;
            if (last == null) first = entry;
            else last.after = entry;
            last = entry;
        }

        private void unlink(Entry entry) {
            if (entry.before == null) first = entry.after;
            else entry.before.after = entry.after;
            if (entry.after == null) last = entry.before;
            else entry.after.before = entry.before;
        }

        public Integer remove(Object key) {
            Entry entry = index.remove(key);
            if (entry == null) return null;
            unlink(entry);
            return entry.getValue();
        }

        public void clear() {
            index.clear();
            first = last = null;
        }

        HologramState.Cell rotate() {
            Entry entry = first;
            if (entry != last) {
                unlink(entry);
                append(entry);
            }
            return entry.getKey();
        }

        public Set<Map.Entry<HologramState.Cell, Integer>> entrySet() {
            return new java.util.AbstractSet<Map.Entry<HologramState.Cell, Integer>>() {

                public int size() {
                    return index.size();
                }

                public java.util.Iterator<Map.Entry<HologramState.Cell, Integer>> iterator() {
                    return new java.util.Iterator<Map.Entry<HologramState.Cell, Integer>>() {

                        Entry next = first, previous;

                        public boolean hasNext() {
                            return next != null;
                        }

                        public Map.Entry<HologramState.Cell, Integer> next() {
                            if (next == null) throw new java.util.NoSuchElementException();
                            previous = next;
                            next = next.after;
                            return previous;
                        }

                        public void remove() {
                            if (previous == null) throw new IllegalStateException();
                            DeferredMap.this.remove(previous.getKey());
                            previous = null;
                        }
                    };
                }
            };
        }
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
        final List<double[]> attributes;
        final boolean missingTexture;

        Model(int list, int vertices, int positions, List<double[]> points, List<double[]> textureCenters,
            boolean missingTexture) {
            this(list, vertices, positions, points, textureCenters, missingTexture, java.util.Collections.emptyList());
        }

        Model(int list, int vertices, int positions, List<double[]> points, List<double[]> textureCenters,
            boolean missingTexture, List<double[]> attributes) {
            this.list = list;
            this.vertices = vertices;
            this.positions = positions;
            this.points = points;
            this.textureCenters = textureCenters;
            this.attributes = attributes;
            this.missingTexture = missingTexture;
        }
    }
}
