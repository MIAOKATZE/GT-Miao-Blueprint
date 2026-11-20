// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.miaovisonlite;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.renderer.Tessellator;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;

import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.miaokatze.miaokatzecore.client.render.internal.AngelicaFfpBridge;
import com.miaokatze.miaokatzecore.client.render.internal.TessellatorCapture;
import com.miaokatze.miaokatzecore.mixin.render.TessellatorAccessor;

import cpw.mods.fml.common.Loader;

/** Retains one complete atlas-material quad batch in the renderer's actual vertex format. */
public final class MiaoVisonLiteMaterialBatch implements AutoCloseable {

    private static final boolean DIAGNOSTICS_ENABLED = Boolean.getBoolean("gtmb.testlab.renderParity");
    private static final Map<String, Long> DIAGNOSTICS = new LinkedHashMap<>();
    private static final int[] ANGELICA_TRIANGLE_VERTICES = { 0, 1, 3, 1, 2, 3 };
    private final MiaoVisonLiteDisplayList geometry;
    private final Frame frame;
    private final long xOffset, yOffset, zOffset;
    private final double u, v;
    private final int color;

    private MiaoVisonLiteMaterialBatch(MiaoVisonLiteDisplayList geometry, Frame frame, TessellatorAccessor tess) {
        this.geometry = geometry;
        this.frame = frame;
        xOffset = bits(tess.miaokatze$getXOffset());
        yOffset = bits(tess.miaokatze$getYOffset());
        zOffset = bits(tess.miaokatze$getZOffset());
        u = tess.miaokatze$getTextureU();
        v = tess.miaokatze$getTextureV();
        color = tess.miaokatze$getColor();
    }

    /**
     * Checks once per material pass before starting tessellation. Extended or intercepted draw paths retain
     * their caller's commands and capture semantics.
     */
    public static Frame currentFrame(Tessellator tess) {
        if (tess.getClass() != Tessellator.class || !(tess instanceof TessellatorAccessor))
            return reject("tessellator");
        if (!TessellatorManager.isOnMainThread() || TessellatorManager.get() != tess) return reject("owner");
        if (TessellatorManager.isCurrentlyCapturing() || TessellatorManager.getDirectCaptureDepth() != 0
            || TessellatorManager.shouldInterceptDraw(tess)
            || TessellatorCapture.active(tess)) return reject("capture");
        ContextCapabilities caps = boundContext();
        if (caps == null) return reject("context");
        if (Loader.isModLoaded("angelica")) return Angelica.inspect(caps);
        if (caps.OpenGL32 && (GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK) & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0
            || caps.OpenGL30
                && (GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS) & GL30.GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) != 0)
            return reject("profile");
        if (!caps.OpenGL13 || GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE) != GL13.GL_TEXTURE0)
            return reject("client-texture");
        if (GL11.glGetInteger(GL11.GL_LIST_INDEX) != 0) return reject("recording");
        if (caps.OpenGL20 && GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != 0) return reject("program");
        if (GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) != GL13.GL_TEXTURE0) return reject("texture");
        count("admitted-vanilla");
        return new Frame(caps, false, null);
    }

    private static Frame reject(String reason) {
        count("rejected-" + reason);
        return null;
    }

    public static void count(String name) {
        if (DIAGNOSTICS_ENABLED) DIAGNOSTICS.merge(name, 1L, Long::sum);
    }

    public static Map<String, Long> diagnostics() {
        return new LinkedHashMap<>(DIAGNOSTICS);
    }

    private static ContextCapabilities boundContext() {
        try {
            return GLContext.getCapabilities();
        } catch (IllegalStateException noContext) {
            return null;
        }
    }

    static OutlineFrame outlineFrame(Tessellator tess) {
        if (!Loader.isModLoaded("angelica") || AngelicaFfpBridge.isSDLGPU() || !Angelica.widensLines()) return null;
        Frame frame = currentFrame(tess);
        if (frame == null || !frame.angelica) return null;
        return Angelica.outlineDefaults(frame, (TessellatorAccessor) tess);
    }

    static void expandOutline(Tessellator tess, Frame frame) {
        if (frame.defaults == null) return;
        Angelica.fillMissingAttributes(tess);
        TessellatorAccessor access = (TessellatorAccessor) tess;
        access.miaokatze$setHasTexture(true);
        access.miaokatze$setHasBrightness(true);
        access.miaokatze$setHasNormals(true);
    }

    /** Exact defaults read by Angelica's live outline expansion, including the Tessellator's translation. */
    static final class OutlineFrame {

        final Frame frame;
        final int bytesPerVertex;
        private final int textureU, textureV, width;
        private final long x, y, z;

        OutlineFrame(Frame frame, int bytesPerVertex, Vector4f tex, TessellatorAccessor tess) {
            this.frame = frame;
            this.bytesPerVertex = bytesPerVertex;
            textureU = frame.defaults == null ? 0 : Float.floatToRawIntBits(tex.x);
            textureV = frame.defaults == null ? 0 : Float.floatToRawIntBits(tex.y);
            width = Float.floatToRawIntBits(GL11.glGetFloat(GL11.GL_LINE_WIDTH));
            x = bits(tess.miaokatze$getXOffset());
            y = bits(tess.miaokatze$getYOffset());
            z = bits(tess.miaokatze$getZOffset());
        }

        boolean matches(OutlineFrame next) {
            return frame.context == next.frame.context && frame.sameDefaults(next.frame)
                && textureU == next.textureU
                && textureV == next.textureV
                && width == next.width
                && x == next.x
                && y == next.y
                && z == next.z;
        }

        boolean currentContext() {
            return boundContext() == frame.context;
        }
    }

    /**
     * Records the Tessellator's real draw and consumes the populated batch on success. A rejected batch is untouched.
     */
    public static MiaoVisonLiteMaterialBatch record(Tessellator tess, Frame frame, int vertices) {
        if (frame == null) return null;
        TessellatorAccessor access = (TessellatorAccessor) tess;
        if (vertices <= 0 || (vertices & 3) != 0
            || !quadBatch(access, vertices)
            || !access.miaokatze$getHasTexture()
            || !access.miaokatze$getHasColor()) {
            count("rejected-batch-layout");
            return null;
        }
        MiaoVisonLiteDisplayList list = MiaoVisonLiteDisplayList.allocate();
        if (list == null) {
            count("rejected-list-allocation");
            return null;
        }
        try {
            MiaoVisonLiteMaterialBatch batch = new MiaoVisonLiteMaterialBatch(list, frame, access);
            if (frame.defaults != null) {
                // Iris can be enabled without an active pack. Live Angelica still expands each vertex.
                // GTNHLib captures before that draw hook during GL_COMPILE, so use the same expansion first.
                Angelica.fillMissingAttributes(tess);
                access.miaokatze$setHasNormals(true);
                access.miaokatze$setHasBrightness(true);
            }
            list.begin();
            if (frame.angelica) drawAngelicaTriangles(tess, access, vertices);
            else tess.draw();
            list.finish();
            // GTNHLib's recording discard clears flags the ordinary Tessellator draw leaves set.
            batch.applyAttributes(tess, access);
            return batch;
        } catch (RuntimeException | Error failure) {
            try {
                list.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            access.miaokatze$setHasBrightness(false);
            access.miaokatze$setHasNormals(false);
        }
    }

    private static void drawAngelicaTriangles(Tessellator tess, TessellatorAccessor access, int vertices) {
        // Live Angelica GL_QUADS shares vertex 3 between both triangles. The GTNHLib display-list EBO
        // splits the same quads along 0–2. Record the live ordering to preserve rasterization and interpolation.
        int[] quads = access.miaokatze$getRawBuffer();
        int capacity = access.miaokatze$getRawBufferSize();
        int triangleVertices = vertices / 4 * ANGELICA_TRIANGLE_VERTICES.length;
        int[] triangles = new int[triangleVertices * 8];
        int write = 0;
        for (int base = 0; base < vertices * 8; base += 32) {
            for (int corner : ANGELICA_TRIANGLE_VERTICES) {
                System.arraycopy(quads, base + corner * 8, triangles, write, 8);
                write += 8;
            }
        }
        access.miaokatze$setRawBuffer(triangles);
        access.miaokatze$setRawBufferSize(triangles.length);
        access.miaokatze$setRawBufferIndex(triangles.length);
        access.miaokatze$setAddedVertices(triangleVertices);
        access.miaokatze$setVertexCount(triangleVertices);
        access.miaokatze$setDrawMode(GL11.GL_TRIANGLES);
        try {
            tess.draw();
        } finally {
            access.miaokatze$setDrawMode(GL11.GL_QUADS);
            access.miaokatze$setRawBuffer(quads);
            access.miaokatze$setRawBufferSize(capacity);
        }
    }

    /** Draws a newly compiled batch whose Tessellator draw has already completed. */
    public void draw() {
        geometry.draw();
    }

    public boolean belongsTo(Frame current) {
        return frame.context == current.context;
    }

    /** Reuses geometry only with equal translation and renderer-generated default attributes. */
    public boolean replay(Tessellator tess, Frame current) {
        if (current == null || !belongsTo(current) || !frame.sameDefaults(current)) return false;
        TessellatorAccessor access = (TessellatorAccessor) tess;
        if (!quadBatch(access, 0) || access.miaokatze$getIsColorDisabled()
            || xOffset != bits(access.miaokatze$getXOffset())
            || yOffset != bits(access.miaokatze$getYOffset())
            || zOffset != bits(access.miaokatze$getZOffset())) return false;
        // Preserve terminal vanilla attributes and use its empty draw to reset its own batch state.
        applyAttributes(tess, access);
        tess.draw();
        draw();
        return true;
    }

    private void applyAttributes(Tessellator tess, TessellatorAccessor access) {
        tess.setTextureUV(u, v);
        access.miaokatze$setColor(color);
        access.miaokatze$setHasColor(true);
    }

    private static boolean quadBatch(TessellatorAccessor tess, int count) {
        return tess.miaokatze$getIsDrawing() && tess.miaokatze$getDrawMode() == GL11.GL_QUADS
            && tess.miaokatze$getVertexCount() == count
            && tess.miaokatze$getAddedVertices() == count
            && tess.miaokatze$getRawBufferIndex() == count * 8
            && !tess.miaokatze$getHasBrightness()
            && !tess.miaokatze$getHasNormals();
    }

    private static long bits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    @Override
    public void close() {
        // A replaced context reclaims its own resources. Its numeric list name can name another object here.
        if (boundContext() == frame.context) geometry.close();
    }

    /** The GL context and any implicit attributes the current backend writes into the draw's vertex format. */
    public static final class Frame {

        private final ContextCapabilities context;
        private final boolean angelica;
        private final VertexDefaults defaults;

        private Frame(ContextCapabilities context, boolean angelica, VertexDefaults defaults) {
            this.context = context;
            this.angelica = angelica;
            this.defaults = defaults;
        }

        private boolean sameDefaults(Frame current) {
            return angelica == current.angelica
                && (defaults == null ? current.defaults == null : defaults.matches(current.defaults));
        }
    }

    private static final class VertexDefaults {

        private final int brightness, normalX, normalY, normalZ;

        private VertexDefaults(int brightness, float normalX, float normalY, float normalZ) {
            this.brightness = brightness;
            this.normalX = Float.floatToRawIntBits(normalX);
            this.normalY = Float.floatToRawIntBits(normalY);
            this.normalZ = Float.floatToRawIntBits(normalZ);
        }

        private boolean matches(VertexDefaults current) {
            return current != null && brightness == current.brightness
                && normalX == current.normalX
                && normalY == current.normalY
                && normalZ == current.normalZ;
        }
    }

    /** Optional renderer APIs are resolved lazily, without loading Angelica on a vanilla client. */
    private static final class Angelica {

        private static final String GLSM = "com.gtnewhorizons.angelica.glsm.";
        private static final Method IRIS = method(
            "com.gtnewhorizons.angelica.rendering.celeritas.api.IrisShaderProviderHolder",
            "isActive");
        private static final Method CACHING = method(GLSM + "GLStateManager", "isCachingEnabled");
        private static final Method EXIT_CAPTURE = method(GLSM + "GLStateManager", "isExitCaptureActive");
        private static final Method GET_INTEGER = queryMethod();
        private static final Field EXPANDED = field(GLSM + "hooks.GLSMConfig", "expandVertexFormats");
        private static final Field EXTENDED = field(GLSM + "hooks.GLSMHooks", "immediateExtendedHandler");
        private static final Method[] FORMATS = formats();
        private static final Method FILL = method(
            GLSM + "streaming.TessellatorStreamingDrawer",
            "fillMissingAttributes",
            Tessellator.class);
        private static final Method BRIGHTNESS = method(GLSM + "hooks.GLSMConfig", "packedLastBrightness");
        private static final Method NORMAL = method(GLSM + "ffp.ShaderManager", "getCurrentNormal");
        private static final Method TEXTURE = method(GLSM + "ffp.ShaderManager", "getCurrentTexCoord");
        private static final Method WIDENS_LINES = method(GLSM + "GLSMDraws", "ffpWidensLineQuads");
        private static final Method QUAD_FORMAT = method(
            GLSM + "streaming.LineQuads",
            "quadFormat",
            VertexFormat.class);

        private static boolean widensLines() {
            if (WIDENS_LINES == null || QUAD_FORMAT == null) return false;
            try {
                return (boolean) WIDENS_LINES.invoke(null);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return false;
            }
        }

        private static OutlineFrame outlineDefaults(Frame frame, TessellatorAccessor tess) {
            if (frame.defaults != null && TEXTURE == null) return null;
            try {
                VertexFormat format = DefaultVertexFormat.ALL_FORMATS[frame.defaults == null ? VertexFlags.COLOR_BIT
                    : VertexFlags.COLOR_BIT | VertexFlags.TEXTURE_BIT
                        | VertexFlags.NORMAL_BIT
                        | VertexFlags.BRIGHTNESS_BIT];
                VertexFormat wide = (VertexFormat) QUAD_FORMAT.invoke(null, format);
                int bytesPerVertex = format.getVertexSize() + 3 * wide.getVertexSize();
                Vector4f tex = frame.defaults == null ? null : (Vector4f) TEXTURE.invoke(null);
                return new OutlineFrame(frame, bytesPerVertex, tex, tess);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return null;
            }
        }

        private static Frame inspect(ContextCapabilities caps) {
            if (IRIS == null || CACHING == null
                || GET_INTEGER == null
                || EXPANDED == null
                || EXTENDED == null
                || FORMATS == null) return reject("angelica-api");
            try {
                if (!Boolean.TRUE.equals(CACHING.invoke(null))) return reject("angelica-caching");
                if (Boolean.TRUE.equals(IRIS.invoke(null))) return reject("iris-pack");
                if (EXIT_CAPTURE != null && Boolean.TRUE.equals(EXIT_CAPTURE.invoke(null)))
                    return reject("exit-capture");
                if ((int) GET_INTEGER.invoke(null, GL11.GL_LIST_INDEX) != 0) return reject("recording");
                if ((int) GET_INTEGER.invoke(null, GL20.GL_CURRENT_PROGRAM) != 0) return reject("program");
                if ((int) GET_INTEGER.invoke(null, GL13.GL_ACTIVE_TEXTURE) != GL13.GL_TEXTURE0)
                    return reject("texture");
                Object handler = EXTENDED.get(null);
                if (handler != null)
                    for (Method getter : FORMATS) if (getter != null && Boolean.TRUE.equals(getter.invoke(handler)))
                        return reject("extended-attributes");
                if (!EXPANDED.getBoolean(null)) {
                    count("admitted-angelica");
                    return new Frame(caps, true, null);
                }
                if (FILL == null || BRIGHTNESS == null || NORMAL == null) return reject("expanded-format-api");
                Vector3f normal = (Vector3f) NORMAL.invoke(null);
                int brightness = (int) BRIGHTNESS.invoke(null);
                count("admitted-angelica-expanded");
                return new Frame(caps, true, new VertexDefaults(brightness, normal.x, normal.y, normal.z));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return reject("angelica-linkage");
            }
        }

        private static void fillMissingAttributes(Tessellator tess) {
            try {
                FILL.invoke(null, tess);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException("Angelica vertex expansion failed", cause);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Angelica vertex expansion could not be invoked", failure);
            }
        }

        private static Method queryMethod() {
            Method query = method(GLSM + "GLSMQueries", "glGetInteger", int.class);
            return query == null ? method(GLSM + "GLStateManager", "glGetInteger", int.class) : query;
        }

        private static Field field(String owner, String name) {
            try {
                return Class.forName(owner)
                    .getField(name);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return null;
            }
        }

        private static Method[] formats() {
            String type = GLSM + "hooks.ImmediateExtendedAttribHandler";
            Method extended = method(type, "wantsExtended"), tangent = method(type, "wantsTangent");
            Method capture = method(type, "wantsExtendedCapture"), normals = method(type, "wantsFaceNormals");
            return extended == null || tangent == null || capture == null || normals == null ? null
                : new Method[] { extended, tangent, capture, normals, method(type, "wantsEntityIds") };
        }

        private static Method method(String owner, String name, Class<?>... signature) {
            try {
                return Class.forName(owner)
                    .getMethod(name, signature);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return null;
            }
        }
    }
}
