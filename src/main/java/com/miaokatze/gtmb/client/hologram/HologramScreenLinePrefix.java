// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.IVertexArrayObject;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.VAOManager;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VertexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.miaokatze.miaokatzecore.client.render.ScreenLineStream;
import com.miaokatze.miaokatzecore.mixin.render.TessellatorAccessor;

/** Immutable leading boxes of an admitted Core POSITION_COLOR / GL_LINES editor pass. Never a wide-line packet. */
final class HologramScreenLinePrefix implements AutoCloseable {

    static final int BOXES = ScreenLineStream.MAX_VERTICES / 24;
    private static final int PACKET_BYTES = BOXES * 24 * ScreenLineStream.BYTES_PER_VERTEX;
    private static final int MAX_PACKETS = 96, NEW_PACKETS_PER_FRAME = 8;

    // The list identity (not its contents) and camera change before any projected coordinates are overwritten.
    @com.github.bsideup.jabel.Desugar
    record Stamp(Object source, Object pose, Object outlines, Object selection, int version, int selected,
        float density, double depth, double ox, double oy, double oz) {

        boolean matches(Stamp other) {
            return other != null && source == other.source && pose == other.pose && outlines == other.outlines
                && selection == other.selection && version == other.version && selected == other.selected
                && Float.floatToRawIntBits(density) == Float.floatToRawIntBits(other.density)
                && bits(depth) == bits(other.depth) && bits(ox) == bits(other.ox) && bits(oy) == bits(other.oy)
                && bits(oz) == bits(other.oz);
        }

        private static long bits(double value) {
            return Double.doubleToRawLongBits(value);
        }
    }

    interface Packet {
        void draw();

        void delete();
    }

    interface Gpu {
        Object context();

        Packet upload(ByteBuffer bytes, int vertices);

        default ByteBuffer allocate(int capacity) {
            return MemoryUtilities.memAlloc(capacity);
        }

        default void free(ByteBuffer data) {
            MemoryUtilities.memFree(data);
        }
    }

    private static final Gpu GL = new Gpu() {
        public Object context() {
            try {
                return GLContext.getCapabilities();
            } catch (IllegalStateException gone) {
                return null;
            }
        }

        public Packet upload(ByteBuffer bytes, int vertices) {
            VertexBuffer buffer = new VertexBuffer(DefaultVertexFormat.POSITION_COLOR, GL11.GL_LINES);
            IVertexArrayObject object = null;
            try {
                object = VAOManager.vao(buffer);
                buffer.upload(bytes, vertices);
                final IVertexArrayObject array = object;
                return new Packet() {
                    public void draw() {
                        array.bind();
                        buffer.setupState();
                        array.draw();
                    }

                    public void delete() {
                        array.delete();
                    }
                };
            } catch (RuntimeException | Error failed) {
                if (object == null) buffer.delete();
                else object.delete();
                throw failed;
            }
        }
    };

    private final Gpu gpu;
    private Stamp stamp;
    private Object context;
    private final List<Packet> packets = new ArrayList<>();
    private ByteBuffer staging;
    private int count, covered, vertices, packetEnd, color;
    int replayedVertices, recordings;

    HologramScreenLinePrefix() {
        this(GL);
    }

    HologramScreenLinePrefix(Gpu gpu) {
        this.gpu = gpu;
    }

    /** Called only after Core admitted the renderer/thread/context/shader and saved caller GL state. */
    int begin(Tessellator tess, Object state, Object camera, Object outlines, int count, Object selection, int version,
        int selected, float density, double depth) {
        TessellatorAccessor input = (TessellatorAccessor) tess;
        Stamp next = new Stamp(state, camera, outlines, selection, version, selected, density, depth,
            input.miaokatze$getXOffset(), input.miaokatze$getYOffset(), input.miaokatze$getZOffset());
        return begin(next, count);
    }

    int begin(Stamp next, int count) {
        Object active = gpu.context();
        boolean same = next.matches(stamp) && count >= this.count && context == active;
        if (!same) close();
        stamp = next;
        this.count = count;
        context = active;
        recordings = 0;
        for (Packet packet : packets) packet.draw();
        replayedVertices = vertices;
        packetEnd = same ? nextPacketEnd() : 0;
        return covered;
    }

    private int nextPacketEnd() {
        return count - covered >= BOXES && packets.size() < MAX_PACKETS && recordings < NEW_PACKETS_PER_FRAME
            ? covered + BOXES : 0;
    }

    boolean recording() {
        return packetEnd != 0;
    }

    int packetEnd() {
        return packetEnd;
    }

    void color(float r, float g, float b, float a) {
        color = packedColor(r, g, b, a, ByteOrder.nativeOrder());
    }

    static int packedColor(float r, float g, float b, float a, ByteOrder order) {
        int red = channel(r), green = channel(g), blue = channel(b), alpha = channel(a);
        return order == ByteOrder.LITTLE_ENDIAN ? red | green << 8 | blue << 16 | alpha << 24
            : red << 24 | green << 16 | blue << 8 | alpha;
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, (int) (value * 255F)));
    }

    void vertex(double x, double y, double z) {
        if (staging == null) staging = gpu.allocate(PACKET_BYTES).order(ByteOrder.nativeOrder());
        put(staging, x, y, z, stamp.ox, stamp.oy, stamp.oz, color);
    }

    static void put(ByteBuffer out, double x, double y, double z, double ox, double oy, double oz, int rgba) {
        // Same double offset, then float conversion and native-order RGBA bytes as Core ScreenLineStream.
        out.putFloat((float) (x + ox)).putFloat((float) (y + oy)).putFloat((float) (z + oz)).putInt(rgba);
    }

    void finishPacket() {
        if (staging != null && staging.position() != 0) {
            staging.flip();
            int size = staging.remaining() / ScreenLineStream.BYTES_PER_VERTEX;
            Packet packet = null;
            try {
                packet = gpu.upload(staging, size);
                packet.draw();
                packets.add(packet);
                vertices += size;
            } catch (RuntimeException | Error failed) {
                if (packet != null) packet.delete();
                throw failed; // A submitted draw must not be replayed on a fallback path.
            } finally {
                staging.clear();
            }
        }
        recordings++;
        covered = packetEnd;
        packetEnd = nextPacketEnd();
    }

    long retainedBytes() {
        return (long) vertices * ScreenLineStream.BYTES_PER_VERTEX;
    }

    @Override
    public void close() {
        try {
            // A replaced context already destroyed its objects; never delete their numeric names in the new one.
            if (context != null && gpu.context() == context) for (Packet packet : packets) packet.delete();
        } finally {
            packets.clear();
            if (staging != null) gpu.free(staging);
            staging = null;
            context = null;
            stamp = null;
            count = covered = vertices = packetEnd = replayedVertices = recordings = 0;
        }
    }
}
