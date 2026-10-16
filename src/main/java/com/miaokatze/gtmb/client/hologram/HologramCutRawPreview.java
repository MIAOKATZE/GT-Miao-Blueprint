package com.miaokatze.gtmb.client.hologram;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.relauncher.ReflectionHelper;

/**
 * Raw, save-local cut previews. This deliberately bypasses ordinary blueprint admission and visual NBT schemas.
 * Native readers/renderers are not a sandbox: failures are visible, but never change the stored cut or paste admission.
 * All methods run on the Minecraft client thread. No tile lifecycle, world tick, network request or real world binding.
 */
public final class HologramCutRawPreview {

    private static final int REGION_SIZE = 8, CACHED_REGIONS = 64, COMPILE_PER_FRAME = 192;
    private static final Map<String, Region> REGIONS = new LinkedHashMap<>();
    private static final Map<String, Region> RESIDENT = new LinkedHashMap<>(16, .75f, true);
    private static final Map<String, String> FAILURES = new LinkedHashMap<>();
    private static final Map<ISimpleBlockRenderingHandler, ISimpleBlockRenderingHandler> HANDLER_COPIES = new java.util.IdentityHashMap<>();
    private static NBTTagCompound source;
    private static HologramCutRawPreviewWorld world;
    private static String digest = "";
    private static long generation, frames, staticAttempts, staticRendered, tesrAttempts, tesrRendered;
    private static long textureChecks, textureMismatches;
    private static int observedActiveTexture, observedClientTexture, observedTexture, expectedTexture,
        observedMatrixMode;
    private static int nonAir, tilePayloads, pending;
    private static HologramCutRawPreviewGhost ghost = new HologramCutRawPreviewGhost();
    private static String ghostFailure = "";
    private static long outlineCells, stateChecks, stateMismatches;
    private static final Field DRAWING = ReflectionHelper.findField(Tessellator.class, "isDrawing", "field_78415_z");
    private static final Field VERTICES = ReflectionHelper.findField(Tessellator.class, "vertexCount", "field_78406_i");
    private static final Field WORLD_PASS = ReflectionHelper.findField(ForgeHooksClient.class, "worldRenderPass");
    private static final Field HANDLERS = ReflectionHelper.findField(RenderingRegistry.class, "blockRenderers");
    private static final Field NBT_MAP = ReflectionHelper.findField(NBTTagCompound.class, "tagMap", "field_74784_a");
    private static final Field NBT_ROWS = ReflectionHelper.findField(NBTTagList.class, "tagList", "field_74747_a");
    private static final Field NBT_LIST_TYPE = ReflectionHelper.findField(NBTTagList.class, "tagType", "field_74746_b");

    private HologramCutRawPreview() {}

    public static void update(NBTTagCompound entry) {
        if (entry == null || !entry.hasKey("cells", 9)) {
            release();
            return;
        }
        // Keep an exact private source. The factory gets additional copies and may only relocate their root XYZ.
        if (source != null && source.equals(entry)) return;
        release();
        source = (NBTTagCompound) entry.copy();
        digest = digest(source);
        generation++;
        world = new HologramCutRawPreviewWorld(source, (cell, error) -> fail(cell, "tile", error));
        for (HologramCutRawPreviewWorld.Cell cell : world.cells.values()) {
            if (cell.raw.hasKey("tile", 10)) tilePayloads++;
            if (cell.block == Blocks.air) continue;
            nonAir++;
            String key = HologramCutRawPreviewWorld
                .key(cell.x / REGION_SIZE, cell.y / REGION_SIZE, cell.z / REGION_SIZE);
            Region region = REGIONS.computeIfAbsent(
                key,
                unused -> new Region(
                    key,
                    cell.x / REGION_SIZE * REGION_SIZE,
                    cell.y / REGION_SIZE * REGION_SIZE,
                    cell.z / REGION_SIZE * REGION_SIZE));
            region.cells.add(cell);
            if (cell.block == null)
                fail(cell, "registry", new IllegalStateException("Missing block " + cell.raw.getString("block")));
        }
    }

    /** World anchor coordinates, with the normal RenderWorldLast view transform already installed by Minecraft. */
    public static void render(double anchorX, double anchorY, double anchorZ, float partialTicks) {
        if (world == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.renderViewEntity;
        if (camera == null) return;
        double cx = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double cy = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double cz = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        Frustrum frustum = new Frustrum();
        frustum.setPosition(cx, cy, cz);
        List<Region> visible = new ArrayList<>();
        Set<String> pinned = new HashSet<>();
        for (Region region : REGIONS.values()) if (frustum.isBoundingBoxInFrustum(
            AxisAlignedBB.getBoundingBox(
                anchorX + region.x,
                anchorY + region.y,
                anchorZ + region.z,
                anchorX + region.x + REGION_SIZE,
                anchorY + region.y + REGION_SIZE,
                anchorZ + region.z + REGION_SIZE))) {
                    visible.add(region);
                    pinned.add(region.key);
                }
        HologramCutRawPreviewScope.StateWitness witness = Boolean.getBoolean("gtmb.hologram.cutNativeClient")
            ? new HologramCutRawPreviewScope.StateWitness()
            : null;
        HologramCutRawPreviewScope.RenderState frame = new HologramCutRawPreviewScope.RenderState();
        frame.push();
        int budget = COMPILE_PER_FRAME;
        pending = 0;
        frames++;
        try {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glTranslated(anchorX - cx, anchorY - cy, anchorZ - cz);
            if (witness != null) frame.prepareReadback();
            if (!ghost.begin()) {
                outlines(visible, frame);
                return;
            }
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, .1f);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderHelper.disableStandardItemLighting();
            // RenderWorldLast can follow the particle pass with the lightmap disabled. Use Minecraft's own
            // secondary-unit binding and matrix setup, then return to the terrain unit before native rendering.
            mc.entityRenderer.enableLightmap(partialTicks);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
            prepareBlockTexture();
            for (Region region : visible) {
                touch(region, pinned);
                for (HologramCutRawPreviewWorld.Cell cell : region.cells) {
                    Model model = region.models.get(cell);
                    if (model == null && budget-- > 0) {
                        model = compile(cell);
                        region.models.put(cell, model);
                    }
                    if (model == null) {
                        pending++;
                        placeholder(cell, false);
                        continue;
                    }
                    if (model.list != 0) draw(model, cell);
                    renderTile(cell, partialTicks);
                    if (cell.block == null || cell.staticFailed
                        || cell.dynamicFailed
                        || cell.attempted && cell.tile == null) placeholder(cell, true);
                    else if (model.vertices == 0 && !model.hasTesr) placeholder(cell, false);
                }
            }
            ghost.composite(frame);
            outlines(visible, frame);
            ghost.finishFrame();
        } catch (Throwable failure) {
            requireRecoverable(failure);
            ghostFailure = failure.getClass()
                .getSimpleName() + ": "
                + String.valueOf(failure.getMessage());
            ghost.bindDestination();
            outlines(visible, frame);
        } finally {
            frame.restore();
            if (witness != null) {
                stateChecks++;
                if (!witness.matches()) stateMismatches++;
            }
        }
    }

    /** Cell edges remain independent of native mesh colors, opacity, alpha tests and TESR state changes. */
    private static void outlines(List<Region> visible, HologramCutRawPreviewScope.RenderState scope) {
        scope.useProgram(0);
        scope.cleanTextureUnits();
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glColorMask(true, true, true, false);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1);
        GL11.glColor4f(.40f, .85f, .94f, .80f);
        GL11.glBegin(GL11.GL_LINES);
        for (Region region : visible) for (HologramCutRawPreviewWorld.Cell cell : region.cells) {
            if (cell.block == Blocks.air) continue;
            outlineCells++;
            double x = cell.x - .003, y = cell.y - .003, z = cell.z - .003, d = 1.006;
            for (int axis = 0; axis < 3; axis++) for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
                if (axis == 0) {
                    GL11.glVertex3d(x, y + a * d, z + b * d);
                    GL11.glVertex3d(x + d, y + a * d, z + b * d);
                } else if (axis == 1) {
                    GL11.glVertex3d(x + a * d, y, z + b * d);
                    GL11.glVertex3d(x + a * d, y + d, z + b * d);
                } else {
                    GL11.glVertex3d(x + a * d, y + b * d, z);
                    GL11.glVertex3d(x + a * d, y + b * d, z + d);
                }
            }
        }
        GL11.glEnd();
    }

    private static void touch(Region region, Set<String> pinned) {
        RESIDENT.put(region.key, region);
        while (RESIDENT.size() > CACHED_REGIONS) {
            String oldest = null;
            for (String key : RESIDENT.keySet()) if (!pinned.contains(key)) {
                oldest = key;
                break;
            }
            // All visible regions stay resident, so a large view makes forward progress instead of LRU thrashing.
            if (oldest == null) break;
            Region evicted = RESIDENT.remove(oldest);
            evicted.clear();
        }
    }

    private static Model compile(HologramCutRawPreviewWorld.Cell cell) {
        if (cell.block == null) return new Model(0, 0, false);
        TileEntitySpecialRenderer tesr = null;
        int list = 0, vertices = 0;
        boolean opened = false;
        Block block = cell.block;
        double[] bounds = { block.getBlockBoundsMinX(), block.getBlockBoundsMinY(), block.getBlockBoundsMinZ(),
            block.getBlockBoundsMaxX(), block.getBlockBoundsMaxY(), block.getBlockBoundsMaxZ() };
        int oldPass = MinecraftForgeClient.getRenderPass(), oldWorldPass = ForgeHooksClient.getWorldRenderPass();
        HologramCutRawPreviewScope.RenderState scope = new HologramCutRawPreviewScope.RenderState();
        HologramCutRawPreviewScope.ClientTessState tessState = null;
        HologramAeNativeRender.CableContext cableContext = null;
        scope.push();
        try {
            TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            tesr = tile == null ? null : TileEntityRendererDispatcher.instance.getSpecialRenderer(tile);
            cableContext = HologramAeNativeRender.cableContext(block, tile);
            tessState = new HologramCutRawPreviewScope.ClientTessState(Tessellator.instance);
            prepareBlockTexture();
            list = GL11.glGenLists(1);
            if (list == 0) throw new IllegalStateException("Display-list allocation failed");
            GL11.glNewList(list, GL11.GL_COMPILE);
            opened = true;
            RenderBlocks renderer = new RenderBlocks(world);
            renderer.enableAO = false;
            renderer.renderAllFaces = false;
            for (int pass = 0; pass < 2; pass++) {
                if (!block.canRenderInPass(pass)) continue;
                WORLD_PASS.setInt(null, pass);
                ForgeHooksClient.setRenderPass(pass);
                Tessellator tess = Tessellator.instance;
                tess.startDrawingQuads();
                tess.setTranslation(0, 0, 0);
                tess.setBrightness(0xf000f0);
                tess.setColorOpaque_F(1, 1, 1);
                staticAttempts++;
                ISimpleBlockRenderingHandler handler = handler(block);
                if (handler == null) renderer.renderBlockByRenderType(block, cell.x, cell.y, cell.z);
                else handler.renderWorldBlock(world, cell.x, cell.y, cell.z, block, block.getRenderType(), renderer);
                vertices += VERTICES.getInt(tess);
                tess.draw();
            }
            GL11.glEndList();
            opened = false;
            if (vertices == 0) {
                GL11.glDeleteLists(list, 1);
                list = 0;
            } else staticRendered++;
            if (vertices == 0 && tesr == null && block.getRenderType() < 0)
                fail(cell, "static", new IllegalStateException("No native block geometry or TESR"));
            return new Model(list, vertices, tesr != null);
        } catch (Throwable failure) {
            requireRecoverable(failure);
            cell.staticFailed = true;
            fail(cell, "static", failure);
            if (opened) {
                try {
                    if (DRAWING.getBoolean(Tessellator.instance)) Tessellator.instance.draw();
                } catch (Throwable cleanupFailure) {
                    requireRecoverable(cleanupFailure);
                }
                GL11.glEndList();
                opened = false;
            }
            if (list != 0) GL11.glDeleteLists(list, 1);
            return new Model(0, 0, tesr != null);
        } finally {
            if (opened) GL11.glEndList();
            try {
                if (tessState != null) tessState.restore();
                WORLD_PASS.setInt(null, oldWorldPass);
            } catch (IllegalAccessException failure) {
                fail(cell, "restore", failure);
            }
            ForgeHooksClient.setRenderPass(oldPass);
            block.setBlockBounds(
                (float) bounds[0],
                (float) bounds[1],
                (float) bounds[2],
                (float) bounds[3],
                (float) bounds[4],
                (float) bounds[5]);
            world.endRender();
            try {
                if (cableContext != null) cableContext.close();
                HologramCutRawPreviewScope.releaseNativeContext(block);
            } finally {
                scope.restore();
            }
        }
    }

    private static void draw(Model model, HologramCutRawPreviewWorld.Cell cell) {
        try {
            // A preceding TESR may have used an entity texture. Bind the terrain atlas on the unit sampled by
            // native block UVs, independently of the caller's active/client texture selectors and matrix mode.
            prepareBlockTexture();
            GL11.glCallList(model.list);
        } catch (Throwable failure) {
            requireRecoverable(failure);
            cell.staticFailed = true;
            fail(cell, "static replay", failure);
        }
    }

    private static void renderTile(HologramCutRawPreviewWorld.Cell cell, float partialTicks) {
        if (cell.tile == null || cell.dynamicFailed) return;
        int oldPass = MinecraftForgeClient.getRenderPass();
        int oldWorldPass = ForgeHooksClient.getWorldRenderPass();
        TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
        net.minecraft.world.World previousWorld = dispatcher.field_147550_f;
        net.minecraft.entity.EntityLivingBase previousCamera = dispatcher.field_147551_g;
        HologramCutRawPreviewScope.RenderState scope = new HologramCutRawPreviewScope.RenderState();
        scope.push();
        HologramCutRawPreviewScope.ClientTessState tessState = null;
        try {
            TileEntitySpecialRenderer renderer = dispatcher.getSpecialRenderer(cell.tile);
            if (renderer == null) return;
            dispatcher.field_147550_f = world;
            dispatcher.field_147551_g = null;
            tessState = new HologramCutRawPreviewScope.ClientTessState(Tessellator.instance);
            for (int pass = 0; pass < 2; pass++) {
                if (!cell.tile.shouldRenderInPass(pass)) continue;
                ForgeHooksClient.setRenderPass(pass);
                WORLD_PASS.setInt(null, pass);
                prepareBlockTexture();
                RenderHelper.enableStandardItemLighting();
                // Match TileEntityRendererDispatcher.renderTileEntity without its live-world distance gate.
                int brightness = world.getLightBrightnessForSkyBlocks(cell.x, cell.y, cell.z, 0);
                OpenGlHelper
                    .setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightness & 0xffff, brightness >>> 16);
                tesrAttempts++;
                renderer.renderTileEntityAt(cell.tile, cell.x, cell.y, cell.z, partialTicks);
                tesrRendered++;
            }
        } catch (Throwable failure) {
            requireRecoverable(failure);
            cell.dynamicFailed = true;
            fail(cell, "TESR", failure);
        } finally {
            try {
                if (tessState != null) tessState.restore();
                WORLD_PASS.setInt(null, oldWorldPass);
            } catch (IllegalAccessException failure) {
                fail(cell, "restore", failure);
            }
            ForgeHooksClient.setRenderPass(oldPass);
            dispatcher.field_147550_f = previousWorld;
            dispatcher.field_147551_g = previousCamera;
            world.endRender();
            HologramCutRawPreviewScope.releaseNativeContext(cell.block);
            scope.restore();
        }
    }

    private static void prepareBlockTexture() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        GL11.glColor4f(1, 1, 1, 1);
        if (Boolean.getBoolean("gtmb.hologram.cutNativeClient")) {
            textureChecks++;
            observedActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            observedClientTexture = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
            observedTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            expectedTexture = mc.getTextureMapBlocks()
                .getGlTextureId();
            observedMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            if (observedActiveTexture != OpenGlHelper.defaultTexUnit
                || observedClientTexture != OpenGlHelper.defaultTexUnit
                || observedTexture != expectedTexture
                || observedMatrixMode != GL11.GL_MODELVIEW) textureMismatches++;
        }
    }

    private static void placeholder(HologramCutRawPreviewWorld.Cell cell, boolean failed) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glLineWidth(failed ? 2 : 1);
        GL11.glColor4f(failed ? 1 : .25f, failed ? .1f : .8f, 1, 1);
        double x = cell.x + .04, y = cell.y + .04, z = cell.z + .04, d = .92;
        GL11.glBegin(GL11.GL_LINES);
        for (int axis = 0; axis < 3; axis++) for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            if (axis == 0) {
                GL11.glVertex3d(x, y + a * d, z + b * d);
                GL11.glVertex3d(x + d, y + a * d, z + b * d);
            }
            if (axis == 1) {
                GL11.glVertex3d(x + a * d, y, z + b * d);
                GL11.glVertex3d(x + a * d, y + d, z + b * d);
            }
            if (axis == 2) {
                GL11.glVertex3d(x + a * d, y + b * d, z);
                GL11.glVertex3d(x + a * d, y + b * d, z + d);
            }
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void fail(HologramCutRawPreviewWorld.Cell cell, String stage, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String position = HologramCutRawPreviewWorld.key(cell.x, cell.y, cell.z);
        FAILURES.put(
            position + " " + stage,
            position + " ["
                + cell.raw.getString("block")
                + "] "
                + stage
                + ": "
                + cause.getClass()
                    .getSimpleName()
                + " "
                + String.valueOf(cause.getMessage()));
    }

    private static void requireRecoverable(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
    }

    @SuppressWarnings("unchecked")
    private static ISimpleBlockRenderingHandler handler(Block block) throws ReflectiveOperationException {
        ISimpleBlockRenderingHandler registered = ((Map<Integer, ISimpleBlockRenderingHandler>) HANDLERS
            .get(RenderingRegistry.instance())).get(block.getRenderType());
        if (registered == null) return null;
        if (HANDLER_COPIES.containsKey(registered)) return HANDLER_COPIES.get(registered);
        ISimpleBlockRenderingHandler isolated = registered;
        try {
            isolated = registered.getClass()
                .getConstructor()
                .newInstance();
        } catch (ReflectiveOperationException noIndependentConstructor) {
            // Singleton native handlers (including FMP) retain their official identity; scoped public caches are
            // cleared.
        }
        HANDLER_COPIES.put(registered, isolated);
        return isolated;
    }

    public static List<String> diagnostics() {
        List<String> result = new ArrayList<>(FAILURES.values());
        if (!ghost.failure()
            .isEmpty()) result.add(ghost.failure());
        if (!ghostFailure.isEmpty()) result.add("Cut ghost layer: " + ghostFailure);
        if (pending > 0) result.add("Raw native preview loading: " + pending + " visible cells");
        return Collections.unmodifiableList(result);
    }

    /** Detached evidence for the native probe; never returns a tile, live world, or mutable source buffer. */
    public static NBTTagCompound inspection() {
        NBTTagCompound out = new NBTTagCompound();
        out.setString("rawEntrySha256", digest);
        out.setLong("generation", generation);
        out.setLong("frames", frames);
        out.setInteger("cells", world == null ? 0 : world.cells.size());
        out.setInteger("nonAirCells", nonAir);
        out.setInteger("tilePayloads", tilePayloads);
        int loaded = 0, parts = 0, lists = 0;
        if (world != null) for (HologramCutRawPreviewWorld.Cell cell : world.cells.values()) if (cell.tile != null) {
            loaded++;
            if ("savedMultipart".equals(
                cell.raw.getCompoundTag("tile")
                    .getString("id")))
                try {
                    parts += ((List<?>) cell.tile.getClass()
                        .getMethod("jPartList")
                        .invoke(cell.tile)).size();
                } catch (ReflectiveOperationException invalid) {
                    fail(cell, "multipart inspection", invalid);
                }
        }
        for (Region region : RESIDENT.values()) for (Model model : region.models.values()) if (model.list != 0) lists++;
        out.setInteger("loadedTiles", loaded);
        out.setInteger("multipartParts", parts);
        out.setInteger("displayLists", lists);
        out.setLong("staticAttempts", staticAttempts);
        out.setLong("staticRendered", staticRendered);
        out.setLong("tesrAttempts", tesrAttempts);
        out.setLong("tesrRendered", tesrRendered);
        out.setLong("textureChecks", textureChecks);
        out.setLong("textureMismatches", textureMismatches);
        out.setInteger("activeTextureUnit", observedActiveTexture);
        out.setInteger("clientTextureUnit", observedClientTexture);
        out.setInteger("boundBlockAtlas", observedTexture);
        out.setInteger("expectedBlockAtlas", expectedTexture);
        out.setInteger("matrixMode", observedMatrixMode);
        ghost.inspect(out);
        out.setString("ghostRenderFailure", ghostFailure);
        out.setLong("ghostOutlineCells", outlineCells);
        out.setLong("ghostStateChecks", stateChecks);
        out.setLong("ghostStateMismatches", stateMismatches);
        out.setInteger("pendingVisibleCells", pending);
        out.setInteger("failureCount", FAILURES.size());
        NBTTagList failures = new NBTTagList();
        for (Map.Entry<String, String> entry : FAILURES.entrySet()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("key", entry.getKey());
            row.setString("reason", entry.getValue());
            failures.appendTag(row);
        }
        out.setTag("failures", failures);
        return out;
    }

    public static NBTTagCompound snapshot() {
        return source == null ? new NBTTagCompound() : (NBTTagCompound) source.copy();
    }

    /** Exact reader evidence is captured only with cutNativeClient enabled; returned compounds are always copies. */
    public static NBTTagCompound inspectionCell(int x, int y, int z) {
        NBTTagCompound out = new NBTTagCompound();
        HologramCutRawPreviewWorld.Cell cell = world == null ? null : world.cell(x, y, z);
        if (cell == null) {
            out.setBoolean("present", false);
            return out;
        }
        out.setBoolean("present", true);
        out.setTag("sourceCell", cell.raw.copy());
        out.setString("sourceCellSha256", digest(cell.raw));
        out.setBoolean("factoryInputCaptured", cell.factoryPayload != null);
        if (cell.factoryPayload != null) {
            out.setTag("factoryPayload", cell.factoryPayload.copy());
            out.setString("factoryPayloadSha256", digest(cell.factoryPayload));
        }
        if (cell.partReaderInputs != null) out.setTag("partReaderInputs", cell.partReaderInputs.copy());
        out.setString("factoryPayloadKind", cell.factoryPayloadKind);
        out.setString("nativeClass", cell.nativeClass);
        out.setBoolean("nativeReaderAttempted", cell.nativeReaderCalls > 0);
        out.setInteger("nativeReaderCalls", cell.nativeReaderCalls);
        out.setBoolean("nativeReaderCompleted", cell.nativeReaderCompleted);
        out.setBoolean("worldBoundToPreview", cell.tile != null && cell.tile.getWorldObj() == world);
        out.setInteger("previewWorldIdentity", System.identityHashCode(world));
        if (cell.tile != null) {
            net.minecraft.world.World bound = cell.tile.getWorldObj();
            out.setString(
                "boundWorldClass",
                bound == null ? ""
                    : bound.getClass()
                        .getName());
            out.setInteger("boundWorldIdentity", bound == null ? 0 : System.identityHashCode(bound));
            out.setInteger("tileX", cell.tile.xCoord);
            out.setInteger("tileY", cell.tile.yCoord);
            out.setInteger("tileZ", cell.tile.zCoord);
        }
        return out;
    }

    /** Native typed NBT bytes, sorting every compound's keys; list order and all native value types remain exact. */
    public static String digest(NBTTagCompound entry) {
        try {
            MessageDigest algorithm = MessageDigest.getInstance("SHA-256");
            java.io.OutputStream sink = new java.io.OutputStream() {

                @Override
                public void write(int value) {}

                @Override
                public void write(byte[] values, int offset, int length) {}
            };
            DataOutputStream encoded = new DataOutputStream(
                new java.io.BufferedOutputStream(new java.security.DigestOutputStream(sink, algorithm)));
            CompressedStreamTools.write((NBTTagCompound) canonical(entry), encoded);
            encoded.flush();
            byte[] hash = algorithm.digest();
            StringBuilder text = new StringBuilder();
            for (byte value : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (IOException | NoSuchAlgorithmException | ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot fingerprint raw cut preview", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static NBTBase canonical(NBTBase tag) throws IllegalAccessException {
        // Only container wrappers are rebuilt. Native scalar/array values are read, never mutated or decoded again.
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound sorted = new NBTTagCompound();
            Map<String, NBTBase> values = new java.util.TreeMap<>();
            for (Map.Entry<String, NBTBase> entry : ((Map<String, NBTBase>) NBT_MAP.get(tag)).entrySet())
                values.put(entry.getKey(), canonical(entry.getValue()));
            NBT_MAP.set(sorted, values);
            return sorted;
        }
        if (tag instanceof NBTTagList) {
            NBTTagList sorted = new NBTTagList();
            List<NBTBase> values = new ArrayList<>();
            for (NBTBase value : (List<NBTBase>) NBT_ROWS.get(tag)) values.add(canonical(value));
            NBT_ROWS.set(sorted, values);
            NBT_LIST_TYPE.setByte(sorted, NBT_LIST_TYPE.getByte(tag));
            return sorted;
        }
        return tag;
    }

    public static void release() {
        ghost.release();
        ghost = new HologramCutRawPreviewGhost();
        ghostFailure = "";
        outlineCells = stateChecks = stateMismatches = 0;
        for (Region region : REGIONS.values()) region.clear();
        RESIDENT.clear();
        REGIONS.clear();
        FAILURES.clear();
        HANDLER_COPIES.clear();
        if (world != null) world.release();
        world = null;
        source = null;
        digest = "";
        nonAir = tilePayloads = pending = 0;
        frames = staticAttempts = staticRendered = tesrAttempts = tesrRendered = 0;
        textureChecks = textureMismatches = 0;
        observedActiveTexture = observedClientTexture = observedTexture = expectedTexture = observedMatrixMode = 0;
    }

    private static final class Region {

        final String key;
        final int x, y, z;
        final List<HologramCutRawPreviewWorld.Cell> cells = new ArrayList<>();
        final Map<HologramCutRawPreviewWorld.Cell, Model> models = new LinkedHashMap<>();

        Region(String key, int x, int y, int z) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void clear() {
            for (Model model : models.values()) if (model.list != 0) GL11.glDeleteLists(model.list, 1);
            models.clear();
        }
    }

    private static final class Model {

        final int list, vertices;
        final boolean hasTesr;

        Model(int list, int vertices, boolean hasTesr) {
            this.list = list;
            this.vertices = vertices;
            this.hasTesr = hasTesr;
        }
    }
}
