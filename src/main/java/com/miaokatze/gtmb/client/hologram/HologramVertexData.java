package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.minecraft.client.renderer.Tessellator;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Type;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Copies emitted vertices before draw() resets either native Tessellator implementation. */
final class HologramVertexData {

    private static final Field RAW_BUFFER = ReflectionHelper.findField(Tessellator.class, "rawBuffer", "field_78405_h");
    private static final Field RAW_INDEX = ReflectionHelper
        .findField(Tessellator.class, "rawBufferIndex", "field_147569_p");

    private HologramVertexData() {}

    /** Canonical vanilla layout: position, atlas UV, packed color, normal and light. */
    static int[] read(Tessellator tess, int vertices) throws IllegalAccessException {
        if (vertices <= 0) return new int[0];
        if (tess instanceof DirectTessellator) return readDirect((DirectTessellator) tess, vertices);
        int[] raw = (int[]) RAW_BUFFER.get(tess);
        int used = RAW_INDEX.getInt(tess);
        if (raw == null || used > raw.length || used % vertices != 0 || used / vertices < 8)
            throw new IllegalStateException("inconsistent native Tessellator vertex buffer");
        int stride = used / vertices;
        int[] copy = new int[Math.multiplyExact(vertices, 8)];
        for (int vertex = 0; vertex < vertices; vertex++) System.arraycopy(raw, vertex * stride, copy, vertex * 8, 8);
        return copy;
    }

    private static int[] readDirect(DirectTessellator tess, int vertices) {
        VertexFormat format = tess.getVertexFormat();
        if (format == null || tess.getVertexCount() != vertices)
            throw new IllegalStateException("inconsistent native DirectTessellator vertex count");
        ByteBuffer data = tess.getWriteBuffer()
            .duplicate();
        // DirectTessellator writes from its base address, independent of the shared buffer's position.
        data.position(0);
        return decode(data, format, vertices);
    }

    static int[] decode(ByteBuffer buffer, VertexFormat format, int vertices) {
        ByteBuffer data = buffer.duplicate()
            .order(ByteOrder.nativeOrder());
        int stride = format.getVertexSize();
        if (data.remaining() != Math.multiplyExact(vertices, stride))
            throw new IllegalStateException("inconsistent native DirectTessellator vertex buffer");
        int[] copy = new int[Math.multiplyExact(vertices, 8)];
        int position = -1;
        int offset = 0;
        for (VertexFormatElement element : format.elementsArray) {
            Usage usage = element.getUsage();
            if (usage == Usage.POSITION) {
                require(element, Type.FLOAT, 3);
                position = offset;
            } else if (usage == Usage.PRIMARY_UV) {
                require(element, Type.FLOAT, 2);
            } else if (usage == Usage.COLOR) {
                require(element, Type.UBYTE, 4);
            } else if (usage == Usage.NORMAL) {
                require(element, Type.BYTE, 3);
            } else if (usage == Usage.SECONDARY_UV && element.getIndex() == 1) {
                require(element, Type.SHORT, 2);
            } else if (usage != Usage.PADDING) {
                throw new IllegalStateException("unsupported native vertex attribute: " + element);
            }
            for (int vertex = 0; vertex < vertices; vertex++) {
                int source = data.position() + vertex * stride + offset;
                int target = vertex * 8;
                if (usage == Usage.POSITION) {
                    copy[target] = data.getInt(source);
                    copy[target + 1] = data.getInt(source + 4);
                    copy[target + 2] = data.getInt(source + 8);
                } else if (usage == Usage.PRIMARY_UV) {
                    copy[target + 3] = data.getInt(source);
                    copy[target + 4] = data.getInt(source + 4);
                } else if (usage == Usage.COLOR) {
                    copy[target + 5] = data.getInt(source);
                } else if (usage == Usage.NORMAL) {
                    copy[target + 6] = (data.get(source) & 255) | (data.get(source + 1) & 255) << 8
                        | (data.get(source + 2) & 255) << 16;
                } else if (usage == Usage.SECONDARY_UV && element.getIndex() == 1) {
                    copy[target + 7] = data.getInt(source);
                }
            }
            offset += element.getByteSize();
        }
        if (position < 0 || offset != stride)
            throw new IllegalStateException("native DirectTessellator format has no usable position");
        return copy;
    }

    private static void require(VertexFormatElement element, Type type, int count) {
        if (element.getType() != type || element.getCount() != count)
            throw new IllegalStateException("unsupported native vertex attribute: " + element);
    }
}
