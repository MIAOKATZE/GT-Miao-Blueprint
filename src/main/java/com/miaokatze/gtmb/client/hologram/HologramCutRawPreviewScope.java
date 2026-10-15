package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.ARBVertexArrayObject;
import org.lwjgl.opengl.ARBVertexBufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Private rendering scope: arbitrary native renderers may fail inside matrix, buffer, or lightmap changes. */
final class HologramCutRawPreviewScope {

    private static final int FRAMEBUFFER = 0x8d40, RENDERBUFFER = 0x8d41;

    private HologramCutRawPreviewScope() {}

    /** Attribute stacks omit FBOs, buffer objects, programs, matrices, and Minecraft's lightmap cache. */
    static final class RenderState {

        private final ContextCapabilities caps = GLContext.getCapabilities();
        private final boolean pixelTransferAvailable = legacyPixelTransferAvailable(caps);
        private final int[] pixelStoreNames = { GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS,
            GL11.GL_PACK_SKIP_PIXELS, GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS,
            GL11.GL_UNPACK_SKIP_PIXELS };
        private final int[] pixelStoreValues = new int[pixelStoreNames.length];
        private final boolean splitFramebuffer = caps.OpenGL30 || caps.GL_ARB_framebuffer_object;
        private final boolean buffers = caps.OpenGL15 || caps.GL_ARB_vertex_buffer_object;
        private final boolean pixelBuffers = caps.OpenGL21 || caps.GL_ARB_pixel_buffer_object;
        private final boolean vertexArrays = caps.OpenGL30 || caps.GL_ARB_vertex_array_object;
        private final int drawFramebuffer = GL11.glGetInteger(0x8ca6);
        private final int readFramebuffer = splitFramebuffer ? GL11.glGetInteger(0x8caa) : drawFramebuffer;
        private final int renderbuffer = GL11.glGetInteger(0x8ca7);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int clientTexture = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        private final int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        private final int arrayBuffer = buffers ? GL11.glGetInteger(0x8894) : 0;
        private final int elementBuffer = buffers ? GL11.glGetInteger(0x8895) : 0;
        private final int packBuffer = pixelBuffers ? GL11.glGetInteger(0x88ed) : 0;
        private final int unpackBuffer = pixelBuffers ? GL11.glGetInteger(0x88ef) : 0;
        private final int vertexArray = vertexArrays ? GL11.glGetInteger(0x85b5) : 0;
        private final int program = caps.OpenGL20 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
            : caps.GL_ARB_shader_objects ? ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB) : 0;
        private final float brightnessX = OpenGlHelper.lastBrightnessX, brightnessY = OpenGlHelper.lastBrightnessY;
        private final List<Matrix> matrices = new ArrayList<>();
        private boolean attributes, clientAttributes;

        RenderState() {
            for (int index = 0; index < pixelStoreNames.length; index++)
                pixelStoreValues[index] = GL11.glGetInteger(pixelStoreNames[index]);
        }

        private static boolean legacyPixelTransferAvailable(ContextCapabilities caps) {
            // This extension restores legacy pixel transfer even when a legacy-created context reports no profile.
            if (caps.GL_ARB_compatibility) return true;
            if (caps.OpenGL32) {
                int profile = GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK);
                if ((profile & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0) return false;
                if ((profile & GL32.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0) return true;
                throw new IllegalStateException("Native preview OpenGL profile is invalid: " + profile);
            }
            if (caps.OpenGL31) return caps.GL_ARB_compatibility;
            if (caps.OpenGL30)
                return (GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS) & GL30.GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) == 0;
            return true;
        }

        void push() {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributes = true;
            GL11.glPushClientAttrib(0xffffffff);
            clientAttributes = true;
            matrices.add(new Matrix(GL11.GL_PROJECTION, GL11.GL_PROJECTION_MATRIX, GL11.GL_PROJECTION_STACK_DEPTH, -1));
            matrices.add(new Matrix(GL11.GL_MODELVIEW, GL11.GL_MODELVIEW_MATRIX, GL11.GL_MODELVIEW_STACK_DEPTH, -1));
            for (int unit = 0; unit < GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS); unit++) {
                OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0 + unit);
                matrices.add(
                    new Matrix(
                        GL11.GL_TEXTURE,
                        GL11.GL_TEXTURE_MATRIX,
                        GL11.GL_TEXTURE_STACK_DEPTH,
                        GL13.GL_TEXTURE0 + unit));
            }
            unbindPixelBuffers();
            if (vertexArrays) {
                if (caps.OpenGL30) GL30.glBindVertexArray(0);
                else ARBVertexArrayObject.glBindVertexArray(0);
            }
            if (buffers) {
                bindBuffer(0x8892, 0);
                bindBuffer(0x8893, 0);
            }
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            // Matrix capture visits every fixed-function texture unit. Saving state must not leave native
            // RenderBlocks/TESR calls on the last unit or with GL_TEXTURE as their current matrix stack.
            OpenGlHelper.setClientActiveTexture(clientTexture);
            OpenGlHelper.setActiveTexture(activeTexture);
            GL11.glMatrixMode(matrixMode);
        }

        void useProgram(int value) {
            if (caps.OpenGL20) GL20.glUseProgram(value);
            else if (caps.GL_ARB_shader_objects) ARBShaderObjects.glUseProgramObjectARB(value);
        }

        void cleanTextureUnits() {
            for (int unit = 0; unit < GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS); unit++) {
                OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glDisable(GL11.GL_TEXTURE_1D);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                if (caps.OpenGL12) GL11.glDisable(0x806f); // GL_TEXTURE_3D
                if (caps.OpenGL13 || caps.GL_ARB_texture_cube_map) GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
                for (int axis : new int[] { GL11.GL_TEXTURE_GEN_S, GL11.GL_TEXTURE_GEN_T, GL11.GL_TEXTURE_GEN_R,
                    GL11.GL_TEXTURE_GEN_Q }) GL11.glDisable(axis);
                GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glLoadIdentity();
            }
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }

        void bindBuffer(int target, int value) {
            if (caps.OpenGL15) GL15.glBindBuffer(target, value);
            else if (buffers) ARBVertexBufferObject.glBindBufferARB(target, value);
        }

        void unbindPixelBuffers() {
            if (pixelBuffers) {
                bindBuffer(0x88eb, 0); // GL_PIXEL_PACK_BUFFER
                bindBuffer(0x88ec, 0); // GL_PIXEL_UNPACK_BUFFER
            }
        }

        void restore() {
            for (Matrix matrix : matrices) matrix.restore();
            if (vertexArrays) {
                if (caps.OpenGL30) GL30.glBindVertexArray(vertexArray);
                else ARBVertexArrayObject.glBindVertexArray(vertexArray);
            }
            // Restore the binding before PopAttrib re-applies the previous read/draw buffer selection.
            if (splitFramebuffer) {
                OpenGlHelper.func_153171_g(0x8ca8, readFramebuffer);
                OpenGlHelper.func_153171_g(0x8ca9, drawFramebuffer);
            } else OpenGlHelper.func_153171_g(FRAMEBUFFER, drawFramebuffer);
            OpenGlHelper.func_153176_h(RENDERBUFFER, renderbuffer);
            if (clientAttributes) GL11.glPopClientAttrib();
            if (attributes) GL11.glPopAttrib();
            // Angelica's client attribute stack stores vertex state, not pixel-store parameters.
            for (int index = 0; index < pixelStoreNames.length; index++)
                GL11.glPixelStorei(pixelStoreNames[index], pixelStoreValues[index]);
            if (buffers) {
                bindBuffer(0x8892, arrayBuffer);
                bindBuffer(0x8893, elementBuffer);
            }
            if (pixelBuffers) {
                bindBuffer(0x88eb, packBuffer);
                bindBuffer(0x88ec, unpackBuffer);
            }
            useProgram(program);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightnessX, brightnessY);
            OpenGlHelper.setClientActiveTexture(clientTexture);
            OpenGlHelper.setActiveTexture(activeTexture);
            GL11.glMatrixMode(matrixMode);
        }
    }

    /** Restore the original top and stack depth even when an optional native renderer throws inside a matrix push. */
    private static final class Matrix {

        private final int mode, depthName, textureUnit, depth;
        private final DoubleBuffer value = BufferUtils.createDoubleBuffer(16);

        Matrix(int mode, int valueName, int depthName, int textureUnit) {
            this.mode = mode;
            this.depthName = depthName;
            this.textureUnit = textureUnit;
            GL11.glMatrixMode(mode);
            depth = GL11.glGetInteger(depthName);
            GL11.glGetDouble(valueName, value);
        }

        void restore() {
            if (textureUnit >= 0) OpenGlHelper.setActiveTexture(textureUnit);
            GL11.glMatrixMode(mode);
            for (int current = GL11.glGetInteger(depthName); current > depth; current--) GL11.glPopMatrix();
            for (int current = GL11.glGetInteger(depthName); current < depth; current++) GL11.glPushMatrix();
            value.rewind();
            GL11.glLoadMatrix(value);
        }
    }

    /** Preview starts only between batches; preserve the caller's scalar state, backing arrays, and NIO cursors. */
    static final class ClientTessState {

        private final Tessellator tessellator;
        private final Map<Field, Object> values = new HashMap<>();
        private final Map<Buffer, int[]> cursors = new java.util.IdentityHashMap<>();

        ClientTessState(Tessellator tessellator) throws ReflectiveOperationException {
            this.tessellator = tessellator;
            if (ReflectionHelper.findField(Tessellator.class, "isDrawing", "field_78415_z")
                .getBoolean(tessellator))
                throw new IllegalStateException("Cannot render a raw cut inside an active tessellator batch");
            for (Class<?> type = tessellator.getClass(); type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    boolean staticField = Modifier.isStatic(field.getModifiers());
                    if ((!staticField && !Modifier.isFinal(field.getModifiers())
                        && field.getType()
                            .isPrimitive())
                        || (!staticField && field.getType() == int[].class)
                        || Buffer.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object value = field.get(staticField ? null : tessellator);
                        if (value instanceof Buffer) {
                            Buffer buffer = (Buffer) value;
                            cursors.put(buffer, new int[] { buffer.position(), buffer.limit() });
                        } else if (!Modifier.isFinal(field.getModifiers())) values.put(field, value);
                    }
                }
            }
        }

        void restore() throws IllegalAccessException {
            for (Map.Entry<Field, Object> entry : values.entrySet()) entry.getKey()
                .set(tessellator, entry.getValue());
            for (Map.Entry<Buffer, int[]> entry : cursors.entrySet()) {
                entry.getKey()
                    .position(0);
                entry.getKey()
                    .limit(entry.getValue()[1]);
                entry.getKey()
                    .position(entry.getValue()[0]);
            }
        }
    }

    /** Exact public cleanup APIs for the three audited handlers with auxiliary render context. */
    static void releaseNativeContext(Block block) {
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
                Field facadeField = ReflectionHelper
                    .findField(Class.forName("crazypants.enderio.conduit.render.ConduitBundleRenderer"), "facade");
                Object facade = ((ThreadLocal<?>) facadeField.get(null)).get();
                if (facade != null) facade.getClass()
                    .getMethod("setBlockOverride", Class.forName("crazypants.enderio.conduit.IConduitBundle"))
                    .invoke(facade, new Object[] { null });
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absentOptionalContext) {
            // The GL/Tessellator scope below is unconditional even without an optional shader bridge.
        }
    }

}
