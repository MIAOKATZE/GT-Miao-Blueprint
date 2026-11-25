// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import com.miaokatze.gtmb.client.hologram.HologramArchiveCameraTiles.Ticket;
import com.miaokatze.gtmb.client.hologram.HologramArchiveRegionSource.Region;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Backend;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Report;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Slice;
import com.miaokatze.gtmb.client.hologram.HologramArchiveTileSubmission.Status;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteMaterialBatch;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteMesh;

/** Private BROWSE adapter only. No archive admission, file reads, worker, CPU fallback, or UI activation. */
final class HologramArchiveTileGl {

    private static final int[][] FACES = { { 0, 1, 5, 4 }, { 3, 7, 6, 2 }, { 0, 3, 2, 1 }, { 4, 5, 6, 7 },
        { 0, 4, 7, 3 }, { 1, 2, 6, 5 } };
    private static final double[][] VERTICES = { { -.5, -.5, -.5 }, { .5, -.5, -.5 }, { .5, .5, -.5 },
        { -.5, .5, -.5 }, { -.5, -.5, .5 }, { .5, -.5, .5 }, { .5, .5, .5 }, { -.5, .5, .5 } };

    private final Thread render = Thread.currentThread();
    private final AtomicBoolean retiring = new AtomicBoolean();
    private final IntBuffer scopeQuery = BufferUtils.createIntBuffer(16);
    private final HologramArchiveTileSubmission<MiaoVisonLiteMesh> submission = new HologramArchiveTileSubmission<>(
        this::current);
    private CoreBackend phase;
    private Palette palette;
    private AtlasPin atlasPin;
    private boolean hasHandles;
    private long atlas = -1, lastFrame = -1, lastDraw = -1, lastPump = -1;

    enum Refusal {
        NONE,
        CORE_UNAVAILABLE,
        ORDINARY_ATLAS_EPOCH_UNAVAILABLE,
        ATLAS_RESIDENCY_UNAVAILABLE,
        RENDER_SCOPE_CHANGED
    }

    static final class Result {

        final Status status;
        final Ticket enqueue;
        final Report commands;
        final Refusal refusal;

        private Result(Status status, Ticket enqueue, Report commands) {
            this(status, enqueue, commands, Refusal.NONE);
        }

        private Result(Status status, Ticket enqueue, Report commands, Refusal refusal) {
            this.status = status;
            this.enqueue = enqueue;
            this.commands = commands;
            this.refusal = refusal;
        }
    }

    private static Result unsupported(Refusal refusal) {
        return new Result(Status.UNSUPPORTED, null, null, refusal);
    }

    /**
     * The eventual caller owns a real physical render stamp, the current trusted source and an existing MUI scissor.
     * The painter never accepts a caller-supplied positive opacity claim. CPU texture data has not been audited.
     */
    Result draw(long physicalFrame, HologramArchiveCameraTiles source, HologramEditorCameraFrame pose,
        List<Region> candidates, int layer, double extent) {
        requireRender();
        if (!monotonic(physicalFrame) || source == null || pose == null || candidates == null
            || !Double.isFinite(pose.scale) || pose.scale <= 0 || !validExtent(extent))
            return new Result(Status.STALE, null, null);
        if (lastDraw == physicalFrame) return new Result(Status.FRAME_SPENT, null, null);
        lastDraw = physicalFrame;
        applyRetirement();
        submission.bind(source);
        phase = open(true, extent);
        try {
            if (phase == null) return unsupported(Refusal.CORE_UNAVAILABLE);
            // The ordinary renderer owns listener setup and atlas creation. Neither is safe to initiate here.
            long epoch = HologramTextures.resourceGeneration();
            if (epoch <= 0) return unsupported(Refusal.ORDINARY_ATLAS_EPOCH_UNAVAILABLE);
            AtlasPin before = AtlasPin.loaded(phase.token);
            if (before == null || atlas == epoch && !before.same(atlasPin))
                return unsupported(Refusal.ATLAS_RESIDENCY_UNAVAILABLE);
            Palette resolved = atlas == epoch ? palette : Palette.blocks();
            AtlasPin after = AtlasPin.loaded(phase.token);
            if (resolved == null || !before.same(after)) return unsupported(Refusal.ATLAS_RESIDENCY_UNAVAILABLE);
            // Atlas and block-icon lookups are outside all GL pushes. They may have changed context, FBO or clip.
            if (retiring.get() || phase.supported() == null || HologramTextures.resourceGeneration() != epoch
                || !after.resident(phase.token)) return unsupported(Refusal.RENDER_SCOPE_CHANGED);
            palette = resolved;
            atlas = epoch;
            atlasPin = after;
            phase.palette = resolved;
            phase.texture = after.name;
            phase.paletteEpoch = epoch;
            var frame = source.plan(pose, candidates, epoch, layer, 0); // No runtime opacity proof: nothing occludes.
            if (frame == null) return new Result(Status.STALE, null, null);
            Report report = submission.submit(physicalFrame, frame, HologramTextures::resourceGeneration);
            hasHandles = report.handles != 0;
            Ticket enqueue = frame.enqueue;
            if (enqueue != null && (report.status == Status.UNSUPPORTED || report.status == Status.STALE
                || retiring.get())) {
                source.abandon(enqueue);
                enqueue = null;
            }
            return new Result(report.status, enqueue, report);
        } finally {
            phase = null;
        }
    }

    /** Retiring is safe outside a GL phase. Never deletes or opens an archive here. */
    void retire() {
        retiring.set(true);
    }

    /** The same host stamp and exact supported context are needed even after the source/scene has closed. */
    Report pump(long physicalFrame) {
        requireRender();
        if (!monotonic(physicalFrame) || lastPump == physicalFrame) return null;
        lastPump = physicalFrame;
        applyRetirement();
        if (!hasHandles) return null;
        phase = open(false, 0);
        try {
            Report report = submission.pumpRetired(physicalFrame);
            hasHandles = report.handles != 0;
            return report;
        } finally {
            phase = null;
        }
    }

    private boolean monotonic(long stamp) {
        if (stamp < 0 || stamp < lastFrame) return false;
        lastFrame = stamp;
        return true;
    }

    private void applyRetirement() {
        if (retiring.getAndSet(false)) {
            submission.bind(null);
            palette = null;
            atlasPin = null;
            atlas = -1;
        }
    }

    private CoreBackend current() {
        return !retiring.get() ? phase : null;
    }

    private void requireRender() {
        if (Thread.currentThread() != render) throw new IllegalStateException("Archive GL belongs to render thread");
    }

    private CoreBackend open(boolean viewport, double extent) {
        try {
            ContextCapabilities token = bound();
            if (token == null || MiaoVisonLiteMesh.currentFrame(Tessellator.instance) == null || bound() != token)
                return null;
            Target target = viewport ? readTarget(token) : null;
            return viewport && target == null ? null : new CoreBackend(token, extent, target);
        } catch (RuntimeException | LinkageError unsupported) {
            return null;
        }
    }

    private static ContextCapabilities bound() {
        try {
            return GLContext.getCapabilities(); // Same reference used by pinned Core. No GL state mutation.
        } catch (IllegalStateException | LinkageError unavailable) {
            return null;
        }
    }

    private Target readTarget(ContextCapabilities token) {
        if (bound() != token || !GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
            || GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) != GL13.GL_TEXTURE0) return null;
        int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        scopeQuery.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, scopeQuery);
        int vx = scopeQuery.get(0), vy = scopeQuery.get(1), vw = scopeQuery.get(2), vh = scopeQuery.get(3);
        scopeQuery.clear();
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scopeQuery);
        int sx = scopeQuery.get(0), sy = scopeQuery.get(1), sw = scopeQuery.get(2), sh = scopeQuery.get(3);
        return bound() == token && vw > 0 && vh > 0 && sw > 0 && sh > 0
            ? new Target(token, drawFramebuffer, vx, vy, vw, vh, sx, sy, sw, sh) : null;
    }

    /** Snapshot equality, not permission to create/bind a standalone viewport. The caller still owns the scissor. */
    static final class Target {

        private final Object context;
        private final int framebuffer, vx, vy, vw, vh, sx, sy, sw, sh;

        Target(Object context, int framebuffer, int vx, int vy, int vw, int vh, int sx, int sy, int sw, int sh) {
            this.context = context;
            this.framebuffer = framebuffer;
            this.vx = vx;
            this.vy = vy;
            this.vw = vw;
            this.vh = vh;
            this.sx = sx;
            this.sy = sy;
            this.sw = sw;
            this.sh = sh;
        }

        boolean same(Target other) {
            return other != null && context == other.context && framebuffer == other.framebuffer && vx == other.vx
                && vy == other.vy && vw == other.vw && vh == other.vh && sx == other.sx && sy == other.sy
                && sw == other.sw && sh == other.sh;
        }
    }

    private static final class AtlasPin {

        final TextureMap atlas;
        final int name;

        AtlasPin(TextureMap atlas, int name) {
            this.atlas = atlas;
            this.name = name;
        }

        static AtlasPin loaded(ContextCapabilities context) {
            try {
                if (bound() != context || TextureName.FIELD == null) return null;
                Minecraft mc = Minecraft.getMinecraft();
                TextureMap map = mc.getTextureMapBlocks();
                if (map == null || map.getClass() != TextureMap.class
                    || mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture) != map
                    || bound() != context) return null;
                // Existing field access only. AbstractTexture.getGlTextureId() may allocate an unbound name.
                AtlasPin pin = new AtlasPin(map, TextureName.FIELD.getInt(map));
                return pin.resident(context) ? pin : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError inaccessible) {
                return null;
            }
        }

        boolean resident(ContextCapabilities context) {
            return name > 0 && bound() == context && GL11.glIsTexture(name) && bound() == context;
        }

        boolean same(AtlasPin other) {
            return other != null && atlas == other.atlas && name == other.name;
        }
    }

    private static final class TextureName {

        static final Field FIELD = existingField();

        private static Field existingField() {
            // Pinned 1.7.10 descriptor: AbstractTexture#glTextureId (MCP), #field_110553_a (SRG), instance int.
            // If inaccessible or unmapped, report ATLAS_RESIDENCY_UNAVAILABLE; never try the allocating getter.
            for (String name : new String[] { "glTextureId", "field_110553_a" }) {
                try {
                    Field field = AbstractTexture.class.getDeclaredField(name);
                    if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    return field;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {}
            }
            return null;
        }
    }

    private final class CoreBackend implements Backend<MiaoVisonLiteMesh> {

        private final ContextCapabilities token;
        private final double extent;
        private final Target target;
        private Palette palette;
        private int texture;
        private long paletteEpoch = -1;
        private HologramEditorCameraFrame pose;
        private boolean unavailable;

        CoreBackend(ContextCapabilities token, double extent, Target target) {
            this.token = token;
            this.extent = extent;
            this.target = target;
        }

        @Override
        public Object context() {
            return !unavailable && Thread.currentThread() == render && bound() == token ? token : null;
        }

        /** Actual operation barrier in addition to A's pure identity/resource queries. */
        private MiaoVisonLiteMaterialBatch.Frame supported() {
            if (context() == null || retiring.get()) return null;
            try {
                if (target == null || target.same(readTarget(token))) {
                    var exact = MiaoVisonLiteMesh.currentFrame(Tessellator.instance);
                    if (exact != null && bound() == token && (target == null || target.same(readTarget(token))))
                        return exact;
                }
            } catch (RuntimeException | LinkageError incompatible) {}
            unavailable = true;
            return null;
        }

        @Override
        public MiaoVisonLiteMesh upload(Slice slice) {
            if (target == null || palette == null || context() == null) return null;
            int count = slice.quads * 4;
            if (count <= 0 || count > HologramArchiveTileSubmission.UPLOAD_QUADS * 4
                || count > MiaoVisonLiteMesh.MAX_VERTICES) return null;
            var builder = new MiaoVisonLiteMesh.Builder(count); // Zero Tessellator offset; tile-local floats only.
            encode(slice, palette, builder::color, builder::vertex);
            var exact = supported();
            return exact == null || HologramTextures.resourceGeneration() != paletteEpoch ? null
                : MiaoVisonLiteMesh.upload(exact, builder, MiaoVisonLiteMesh.Quads.RECORDED);
        }

        @Override
        public boolean begin(HologramEditorCameraFrame pose) {
            if (target == null || supported() == null || !clearDepth()) return false;
            this.pose = pose;
            return true;
        }

        @Override
        public boolean draw(MiaoVisonLiteMesh handle, Slice slice) {
            if (target == null || pose == null || context() == null) return false;
            var exact = supported();
            if (exact == null || !handle.matches(exact) || HologramTextures.resourceGeneration() != paletteEpoch
                || texture <= 0 || !GL11.glIsTexture(texture) || bound() != token) return false;
            Transform tile = new Transform(pose, slice.tile.region, extent);
            int previousMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            boolean matrix = false;
            try {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();
                matrix = true;
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .01f);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); // Loaded atlas name, no TextureManager callback.
                if (context() == null || retiring.get() || HologramTextures.resourceGeneration() != paletteEpoch)
                    return false;
                GL11.glTranslated(tile.screenX, tile.screenY, 0);
                GL11.glScaled(tile.scale, -tile.scale, tile.depthScale);
                GL11.glRotated(tile.pitch, 1, 0, 0);
                GL11.glRotated(tile.yaw, 0, 1, 0);
                GL11.glTranslated(tile.x, tile.y, tile.z);
                handle.draw(); // A successful command is not a swap/display witness.
                return true;
            } finally {
                // No state remains pushed across distinct scheduler operations in a live context.
                if (Thread.currentThread() == render && bound() == token) {
                    if (matrix) {
                        GL11.glMatrixMode(GL11.GL_MODELVIEW);
                        GL11.glPopMatrix();
                    }
                    GL11.glPopAttrib();
                    if (previousTexture == 0 || GL11.glIsTexture(previousTexture))
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                    GL11.glMatrixMode(previousMode);
                }
            }
        }

        @Override
        public void end() {
            pose = null;
            if (target != null && supported() != null) clearDepth();
        }

        @Override
        public boolean release(MiaoVisonLiteMesh handle) {
            var exact = supported();
            if (exact == null || !handle.belongsTo(exact)) return false;
            handle.close();
            return true;
        }

        private boolean clearDepth() {
            if (context() == null || target == null || !target.same(readTarget(token))) return false;
            GL11.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT);
            try {
                GL11.glDepthMask(true);
                GL11.glClearDepth(1);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                return true;
            } finally {
                if (Thread.currentThread() == render && bound() == token) GL11.glPopAttrib();
            }
        }
    }

    private static boolean validExtent(double extent) {
        return Double.isFinite(extent) && extent >= 1;
    }

    static final class Transform {

        final double screenX, screenY, scale, depthScale, pitch, yaw, x, y, z;

        Transform(HologramEditorCameraFrame frame, Region region, double extent) {
            screenX = frame.coordinates.screenX(0);
            screenY = frame.coordinates.screenY(0);
            scale = frame.scale;
            depthScale = 128 / extent;
            pitch = frame.pose.pitchDegrees();
            yaw = frame.pose.yawDegrees();
            x = 16.0 * region.x - frame.pose.anchorX();
            y = 16.0 * region.y - frame.pose.anchorY();
            z = 16.0 * region.z - frame.pose.anchorZ();
        }
    }

    static final class Face {

        final double minU, maxU, minV, maxV;
        final int color;

        Face(double minU, double maxU, double minV, double maxV, int color) {
            if (!valid(minU, maxU) || !valid(minV, maxV)) throw new IllegalArgumentException("Invalid archive atlas UV");
            this.minU = minU;
            this.maxU = maxU;
            this.minV = minV;
            this.maxV = maxV;
            this.color = color;
        }

        private static boolean valid(double min, double max) {
            return Double.isFinite(min) && Double.isFinite(max) && min >= 0 && max <= 1 && max > min;
        }
    }

    static final class Palette {

        private final Face[][] samples = new Face[3][6];

        Palette(Face[][] exact) {
            if (exact.length != samples.length) throw new IllegalArgumentException("Archive palette size");
            for (int i = 0; i < samples.length; i++) {
                if (exact[i].length != samples[i].length) throw new IllegalArgumentException("Archive palette faces");
                for (int side = 0; side < samples[i].length; side++)
                    samples[i][side] = java.util.Objects.requireNonNull(exact[i][side]);
            }
        }

        static Palette blocks() {
            try {
                Block[] palette = { Blocks.stonebrick, Blocks.quartz_block, Blocks.glowstone };
                Face[][] faces = new Face[palette.length][6];
                for (int i = 0; i < palette.length; i++) {
                    Block block = palette[i];
                    if (block == null) return null;
                    int color = block.getRenderColor(0);
                    for (int side = 0; side < 6; side++) {
                        IIcon icon = block.getIcon(side, 0);
                        if (icon == null || icon.getClass() != TextureAtlasSprite.class) return null;
                        String name = icon.getIconName();
                        if (name == null || name.isEmpty() || name.equals("missingno") || name.endsWith(":missingno"))
                            return null;
                        faces[i][side] = new Face(icon.getMinU(), icon.getMaxU(), icon.getMinV(), icon.getMaxV(), color);
                    }
                }
                return new Palette(faces);
            } catch (RuntimeException | LinkageError unsupported) {
                return null;
            }
        }
    }

    @FunctionalInterface
    interface Color {
        void set(float red, float green, float blue, float alpha);
    }

    @FunctionalInterface
    interface Vertex {
        void add(double x, double y, double z, double u, double v);
    }

    static void encode(Slice slice, Palette palette, Color color, Vertex vertices) {
        for (int i = 0; i < slice.quads; i++) encodeQuad(slice.quad(i), palette, color, vertices);
    }

    /** Same six side/UV orientations as the existing model preview, with no projection or pose bakes. */
    static void encodeQuad(int quad, Palette palette, Color color, Vertex vertices) {
        int side = HologramArchiveTileMesh.side(quad);
        int code = HologramArchiveTileMesh.code(quad);
        Face face = palette.samples[code - 1][side];
        double x = HologramArchiveTileMesh.x(quad), y = HologramArchiveTileMesh.y(quad),
            z = HologramArchiveTileMesh.z(quad);
        float shade = side == 1 ? 1 : side == 0 ? .65f : .85f;
        color.set((face.color >>> 16 & 255) / 255f * shade, (face.color >>> 8 & 255) / 255f * shade,
            (face.color & 255) / 255f * shade, 1);
        for (int index : FACES[side]) {
            double[] p = VERTICES[index];
            double u = side == 4 ? p[2] + .5 : side == 5 ? .5 - p[2] : side == 2 ? .5 - p[0] : p[0] + .5;
            double v = side == 0 ? p[2] + .5 : side == 1 ? .5 - p[2] : .5 - p[1];
            vertices.add(x + p[0], y + p[1], z + p[2],
                face.minU + (face.maxU - face.minU) * u, face.minV + (face.maxV - face.minV) * v);
        }
    }
}
