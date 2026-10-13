package com.miaokatze.gtmb.client.hologram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises actual GTNHLib vertex layouts without constructing an OpenGL Tessellator. */
public final class HologramVertexDataRegression {

    private HologramVertexDataRegression() {}

    public static void run() {
        int[] expected = { Float.floatToRawIntBits(0.25f), Float.floatToRawIntBits(-2f), Float.floatToRawIntBits(1.5f),
            Float.floatToRawIntBits(0.125f), Float.floatToRawIntBits(0.75f), 0xd47fa521, 0x0040807f, 0x00f000d0 };
        layout(DefaultVertexFormat.POSITION_COLOR_TEXTURE_LIGHT_NORMAL, expected);
        layout(DefaultVertexFormat.POSITION_TEXTURE_COLOR_NORMAL, expected);
        VertexFormat format = DefaultVertexFormat.POSITION_TEXTURE_COLOR;
        ByteBuffer shortBuffer = ByteBuffer.allocate(format.getVertexSize() - 1);
        boolean rejected = false;
        try {
            HologramVertexData.decode(shortBuffer, format, 1);
        } catch (IllegalStateException expectedFailure) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "truncated direct geometry is rejected, never treated as a valid model");
    }

    private static void layout(VertexFormat format, int[] expected) {
        int prefix = 7;
        ByteBuffer source = ByteBuffer.allocate(prefix + format.getVertexSize() * 2 + 9)
            .order(ByteOrder.nativeOrder());
        source.position(prefix);
        source.limit(prefix + format.getVertexSize() * 2);
        for (int vertex = 0; vertex < 2; vertex++) {
            int offset = prefix + vertex * format.getVertexSize();
            for (VertexFormatElement element : format.elementsArray) {
                Usage usage = element.getUsage();
                if (usage == Usage.POSITION) {
                    source.putInt(offset, expected[0]);
                    source.putInt(offset + 4, expected[1]);
                    source.putInt(offset + 8, expected[2]);
                } else if (usage == Usage.PRIMARY_UV) {
                    source.putInt(offset, expected[3]);
                    source.putInt(offset + 4, expected[4]);
                } else if (usage == Usage.COLOR) {
                    source.putInt(offset, expected[5]);
                } else if (usage == Usage.NORMAL) {
                    source.put(offset, (byte) expected[6]);
                    source.put(offset + 1, (byte) (expected[6] >> 8));
                    source.put(offset + 2, (byte) (expected[6] >> 16));
                } else if (usage == Usage.SECONDARY_UV) {
                    source.putInt(offset, expected[7]);
                }
                offset += element.getByteSize();
            }
        }
        int[] canonical = HologramVertexData.decode(source, format, 2);
        SimpleAssert.eq(prefix, source.position(), "collector leaves source position unchanged");
        SimpleAssert.eq(prefix + format.getVertexSize() * 2, source.limit(), "collector leaves source limit unchanged");
        int[] expectedVertex = expected.clone();
        if (!format.hasBrightness()) expectedVertex[7] = 0;
        for (int vertex = 0; vertex < 2; vertex++) SimpleAssert.that(
            Arrays.equals(expectedVertex, Arrays.copyOfRange(canonical, vertex * 8, vertex * 8 + 8)),
            "layout offsets preserve exact position/UV/color/normal/light bits for every vertex");
    }
}
