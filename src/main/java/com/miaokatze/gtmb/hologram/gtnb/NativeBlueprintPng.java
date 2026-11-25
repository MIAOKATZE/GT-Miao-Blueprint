// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

import static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintBytes.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;

/** Metadata-only PNG rebinding; IDAT pixel bytes and unrelated valid chunks are kept byte-for-byte. */
public final class NativeBlueprintPng {

    private static final byte[] SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };

    private NativeBlueprintPng() {}

    public static void validatePreview(byte[] png) throws IOException {
        rewrite(png, "0000000000000000000000000000000000000000000000000000000000000000", false);
        need(be(png, 16) == 640 && be(png, 20) == 640, "preview must be 640 by 640");
        java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream();
        int at = 8;
        while (at < png.length) {
            int n = be(png, at);
            if (new String(png, at + 4, 4, StandardCharsets.US_ASCII).equals("IDAT")) idat.write(png, at + 8, n);
            at += n + 12;
        }
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(idat.toByteArray());
            byte[] b = new byte[8192];
            long total = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(b);
                total += n;
                need(total <= 8L * 1024 * 1024, "PNG expanded budget");
                need(n > 0 || inflater.finished(), "PNG truncated zlib");
            }
            need(inflater.getRemaining() == 0, "PNG zlib trailer");
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Native blueprint: PNG zlib", e);
        } finally {
            inflater.end();
        }
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        need(image != null && image.getWidth() == 640 && image.getHeight() == 640, "PNG image decode");
    }

    public static byte[] bind(byte[] png, String sourceHash) throws IOException {
        need(sourceHash.matches("[0-9a-f]{64}"), "PNG source hash");
        return rewrite(png, sourceHash, false);
    }

    public static void verify(byte[] png, String hash) throws IOException {
        rewrite(png, hash, true);
    }

    private static byte[] rewrite(byte[] png, String hash, boolean verify) throws IOException {
        need(
            png.length <= 4 * 1024 * 1024 && png.length >= 33 && Arrays.equals(Arrays.copyOf(png, 8), SIGNATURE),
            "PNG signature/budget");
        Out out = new Out();
        out.bytes(SIGNATURE);
        int at = 8;
        boolean ihdr = false, idat = false, endedIdat = false, iend = false, palette = false;
        int chunks = 0;
        String kind = null, source = null;
        Set<String> bindings = new HashSet<>();
        while (at < png.length) {
            need(png.length - at >= 12, "PNG chunk header");
            int n = be(png, at);
            need(n >= 0 && n <= png.length - at - 12, "PNG chunk length");
            String type = new String(png, at + 4, 4, StandardCharsets.US_ASCII);
            need(
                ++chunks <= 4096 && type.matches("[A-Za-z]{4}") && Character.isUpperCase(type.charAt(2)),
                "PNG chunk type/budget");
            if (Character.isUpperCase(type.charAt(0))) need(
                type.equals("IHDR") || type.equals("IDAT") || type.equals("IEND") || type.equals("PLTE"),
                "unsupported critical PNG chunk");
            need(
                !type.equals("iCCP") && !type.equals("iTXt") && !type.equals("zTXt"),
                "compressed or international PNG metadata unsupported");
            if (type.equals("PLTE")) {
                need(!palette && !idat && n > 0 && n <= 768 && n % 3 == 0, "PNG palette");
                palette = true;
            }
            if (idat && !type.equals("IDAT")) endedIdat = true;
            if (type.equals("IDAT")) need(!endedIdat, "non-contiguous PNG IDAT");
            CRC32 crc = new CRC32();
            crc.update(png, at + 4, n + 4);
            need((int) crc.getValue() == be(png, at + 8 + n), "PNG CRC");
            if (!ihdr) {
                need(
                    type.equals("IHDR") && n == 13
                        && be(png, at + 8) > 0
                        && be(png, at + 12) > 0
                        && be(png, at + 8) <= 4096
                        && be(png, at + 12) <= 4096,
                    "PNG IHDR");
                ihdr = true;
            } else need(!type.equals("IHDR"), "duplicate PNG IHDR");
            boolean drop = false;
            if (type.equals("tEXt")) {
                int split = 0;
                while (split < n && png[at + 8 + split] != 0) split++;
                need(split > 0 && split <= 79 && split < n, "PNG text");
                for (int i = split + 1; i < n; i++) need(png[at + 8 + i] != 0, "PNG text contains NUL");
                String key = new String(png, at + 8, split, StandardCharsets.ISO_8859_1),
                    value = new String(png, at + 9 + split, n - split - 1, StandardCharsets.ISO_8859_1);
                if (key.equals("gtmb.sourceKind") || key.equals("gtmb.sourceSha256")
                    || key.equals("gtmb.sourceJsonSha256")
                    || key.equals("gtmb.previewBinding")) {
                    need(bindings.add(key), "duplicate PNG binding");
                    drop = true;
                    if (key.equals("gtmb.sourceKind")) kind = value;
                    if (key.equals("gtmb.sourceSha256")) source = value;
                }
            }
            if (type.equals("IDAT")) idat = true;
            if (type.equals("IEND")) {
                need(n == 0 && at + 12 == png.length && idat, "PNG IEND");
                iend = true;
                if (!verify) {
                    chunk(out, "tEXt", ("gtmb.sourceKind\0gtmb-native-1").getBytes(StandardCharsets.ISO_8859_1));
                    chunk(out, "tEXt", ("gtmb.sourceSha256\0" + hash).getBytes(StandardCharsets.ISO_8859_1));
                }
            }
            if (!drop) out.write(png, at, n + 12);
            at += n + 12;
        }
        need(iend, "PNG missing end");
        if (verify) need(
            "gtmb-native-1".equals(kind) && hash.equals(source) && !bindings.contains("gtmb.sourceJsonSha256"),
            "PNG binding mismatch");
        return out.toByteArray();
    }

    private static int be(byte[] b, int o) {
        return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255);
    }

    private static void big(Out o, long n) {
        o.u8((int) (n >>> 24));
        o.u8((int) (n >>> 16));
        o.u8((int) (n >>> 8));
        o.u8((int) n);
    }

    private static void chunk(Out out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        big(out, data.length);
        out.bytes(t);
        out.bytes(data);
        CRC32 c = new CRC32();
        c.update(t);
        c.update(data);
        big(out, c.getValue());
    }
}
