// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.miaovisonlite;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VertexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.miaokatze.miaokatzecore.client.render.internal.AngelicaFfpBridge;

import cpw.mods.fml.common.Loader;

/** Render-thread-owned resident geometry. Positions are local to one spatial region. */
public final class MiaoVisonLite {

    public enum Layout {

        MATERIAL(DefaultVertexFormat.POSITION_TEXTURE_COLOR, GL11.GL_QUADS, 4),
        OUTLINE(DefaultVertexFormat.POSITION, GL11.GL_LINES, 2);

        private final VertexFormat format;
        private final int mode, primitiveVertices;

        Layout(VertexFormat format, int mode, int primitiveVertices) {
            this.format = format;
            this.mode = mode;
            this.primitiveVertices = primitiveVertices;
        }
    }

    public enum Layer {

        OPAQUE(Layout.MATERIAL),
        TRANSLUCENT(Layout.MATERIAL),
        OUTLINE(Layout.OUTLINE);

        private final Layout layout;

        Layer(Layout layout) {
            this.layout = layout;
        }
    }

    private MiaoVisonLite() {}

    /** The vanilla backend needs compatibility GL. Angelica must own the logical fixed-function pass. */
    public static boolean available() {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (!caps.OpenGL15) return false;
        if (Loader.isModLoaded("angelica")) {
            try {
                return caps.OpenGL30 && !AngelicaFfpBridge.isSDLGPU() && AngelicaFfpBridge.supported();
            } catch (LinkageError unavailable) {
                return false;
            }
        }
        return !caps.OpenGL32 || (GL11.glGetInteger(GL32_PROFILE_MASK) & GL32_CORE_PROFILE_BIT) == 0;
    }

    private static final int GL32_PROFILE_MASK = 0x9126;
    private static final int GL32_CORE_PROFILE_BIT = 1;

    /** Reusable bounded staging storage, with whole primitive and upload boundaries chosen by the caller. */
    public static final class Writer implements AutoCloseable {

        private static final int STAGING_BYTES = 256 * 1024;
        private final Layout layout;
        private ByteBuffer bytes;

        public Writer(Layout layout) {
            this.layout = layout;
        }

        public int vertices() {
            return bytes == null ? 0 : bytes.position() / layout.format.getVertexSize();
        }

        public int remainingVertices() {
            return (bytes == null ? STAGING_BYTES : bytes.remaining()) / layout.format.getVertexSize();
        }

        private ByteBuffer buffer() {
            if (bytes == null) bytes = MemoryUtilities.memAlloc(STAGING_BYTES)
                .order(ByteOrder.nativeOrder());
            return bytes;
        }

        public void position(double x, double y, double z) {
            buffer().putFloat((float) x)
                .putFloat((float) y)
                .putFloat((float) z);
        }

        public void material(double x, double y, double z, double u, double v, int rgba) {
            position(x, y, z);
            bytes.putFloat((float) u)
                .putFloat((float) v);
            bytes.put((byte) (rgba >>> 24))
                .put((byte) (rgba >>> 16))
                .put((byte) (rgba >>> 8))
                .put((byte) rgba);
        }

        private Mesh upload() {
            int count = vertices();
            if (count == 0) return null;
            if (count % layout.primitiveVertices != 0) throw new IllegalStateException("Incomplete mesh primitive");
            VertexBuffer buffer = new VertexBuffer(layout.format, layout.mode);
            int saved = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            try {
                if (buffer.getId() == 0) throw new IllegalStateException("Could not allocate blueprint mesh buffer");
                bytes.flip();
                buffer.allocate(bytes, count);
                return new Mesh(buffer);
            } catch (RuntimeException | Error failure) {
                buffer.delete();
                throw failure;
            } finally {
                bytes.clear();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, saved);
            }
        }

        @Override
        public void close() {
            if (bytes != null) {
                MemoryUtilities.memFree(bytes);
                bytes = null;
            }
        }
    }

    private static final class Mesh implements AutoCloseable {

        private final VertexBuffer buffer;

        private Mesh(VertexBuffer buffer) {
            this.buffer = buffer;
        }

        public void draw() {
            buffer.setupState();
            try {
                buffer.draw();
            } finally {
                buffer.cleanupState();
            }
        }

        @Override
        public void close() {
            buffer.delete();
        }
    }

    /**
     * Resident structure mesh session. The source publishes finished batches in region-local coordinates and owns
     * atlas/material selection, spatial visibility, camera transforms, blend and depth. Publish, draw and close on
     * the same client render thread. Replace the session on resource reload or source change.
     */
    public static final class Scene implements AutoCloseable {

        private final Thread owner = Thread.currentThread();
        private final List<Region> regions = new ArrayList<>();
        private int vertexArray;
        private boolean closed;

        public Region region(int x, int y, int z) {
            requireOpen();
            Region region = new Region(this, x, y, z);
            regions.add(region);
            return region;
        }

        /** Retains a completed batch on the GPU and resets the caller's staging writer for reuse. */
        public void publish(Region region, Layer layer, Writer writer) {
            requireRegion(region);
            if (writer.layout != layer.layout) throw new IllegalArgumentException("Blueprint mesh layout mismatch");
            Mesh mesh = writer.upload();
            if (mesh != null) region.meshes.computeIfAbsent(layer, unused -> new ArrayList<>())
                .add(mesh);
        }

        public Frame beginFrame() {
            requireOpen();
            if (vertexArray == 0 && GLContext.getCapabilities().OpenGL30) vertexArray = GL30.glGenVertexArrays();
            return new Frame(this, vertexArray);
        }

        private void requireOpen() {
            if (closed || Thread.currentThread() != owner) throw new IllegalStateException("Invalid blueprint scene");
        }

        private void requireRegion(Region region) {
            requireOpen();
            if (region.scene != this) throw new IllegalArgumentException("Blueprint region belongs to another scene");
        }

        @Override
        public void close() {
            if (closed) return;
            requireOpen();
            for (Region region : regions)
                for (List<Mesh> meshes : region.meshes.values()) for (Mesh mesh : meshes) mesh.close();
            regions.clear();
            if (vertexArray != 0) GL30.glDeleteVertexArrays(vertexArray);
            vertexArray = 0;
            closed = true;
        }
    }

    /** Bounds and visibility are source-owned. A region's origin remains constant for this scene. */
    public static final class Region {

        private final Scene scene;
        private final int x, y, z;
        private final Map<Layer, List<Mesh>> meshes = new EnumMap<>(Layer.class);

        private Region(Scene scene, int x, int y, int z) {
            this.scene = scene;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** Restores both classic GL vertex state and Angelica's logical FFP input state. */
    public static final class Frame implements AutoCloseable {

        private final Scene scene;
        private final boolean angelica = Loader.isModLoaded("angelica");
        private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        private final int vertexArray;
        private final int clientTexture;
        private final int drawingArray;

        private Frame(Scene scene, int drawingArray) {
            this.scene = scene;
            this.drawingArray = drawingArray;
            vertexArray = drawingArray != 0 ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
            clientTexture = angelica ? AngelicaFfpBridge.clientTexture()
                : GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_CLIENT_ACTIVE_TEXTURE);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            if (drawingArray != 0) GL30.glBindVertexArray(drawingArray);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        }

        /** Draw one region under the caller's viewport or world transform. Returns actual batch submissions. */
        public int draw(Region region, Layer layer) {
            scene.requireRegion(region);
            List<Mesh> meshes = region.meshes.get(layer);
            if (meshes == null) return 0;
            GL11.glPushMatrix();
            try {
                GL11.glTranslated(region.x, region.y, region.z);
                for (Mesh mesh : meshes) mesh.draw();
                return meshes.size();
            } finally {
                GL11.glPopMatrix();
            }
        }

        @Override
        public void close() {
            if (drawingArray != 0) GL30.glBindVertexArray(vertexArray);
            GL11.glPopClientAttrib();
            OpenGlHelper.setClientActiveTexture(clientTexture);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            if (angelica) AngelicaFfpBridge.restore();
        }
    }
}
