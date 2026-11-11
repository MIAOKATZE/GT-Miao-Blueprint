// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Original ghost framebuffer readback/comparison, isolated from ordinary renderer output. */
final class HologramCutGhostProbe extends HologramCutGhostDiagnostics {

    private final float OPACITY;
    private final float[] TINT;
    private int width, height;
    private long sampleChecks, sampleMismatches, depthChecks, depthMismatches, backgroundPixels, depthCopyChecks,
        depthCopyMismatches;
    private String depthBefore = "", depthCopied = "", depthAfter = "";
    private final NBTTagList pixelEvidence = new NBTTagList();
    private boolean sampled, depthPending;
    private List<Sample> samples = new ArrayList<Sample>();

    HologramCutGhostProbe(float opacity, float[] tint) {
        OPACITY = opacity;
        TINT = tint.clone();
    }

    static HologramCutGhostDiagnostics create(float opacity, float[] tint) {
        return new HologramCutGhostProbe(opacity, tint);
    }

    void beforeDepthCopy(int destination, int width, int height) {
        this.width = width;
        this.height = height;
        if (!sampled && Boolean.getBoolean("gtmb.hologram.cutNativeClient")) {
            OpenGlHelper.func_153171_g(0x8ca8, destination);
            pixelStore();
            depthBefore = sceneDepthDigest();
        }
    }

    void afterDepthCopy() {
        if (!sampled && Boolean.getBoolean("gtmb.hologram.cutNativeClient")) {
            depthCopied = sceneDepthDigest();
            depthCopyChecks++;
            if (!depthBefore.equals(depthCopied)) depthCopyMismatches++;
        }
    }

    void sampleLayer() {
        samples = samples();
    }

    void beforeComposite() {
        for (Sample sample : samples) sample.before = pixel(sample.x, sample.y);
    }

    void afterComposite() {
        for (Sample sample : samples) {
            byte[] after = pixel(sample.x, sample.y);
            float alpha = (sample.rgba[3] & 255) / 255f * OPACITY;
            boolean mismatch = after[3] != sample.before[3];
            boolean differsFromBackground = false, differsFromOpaqueModel = false;
            for (int channel = 0; channel < 3; channel++) {
                float expected = (sample.rgba[channel] & 255) * TINT[channel] * alpha
                    + (sample.before[channel] & 255) * (1 - alpha);
                if (Math.abs((after[channel] & 255) - expected) > 3) mismatch = true;
                if (Math.abs((after[channel] & 255) - (sample.before[channel] & 255)) > 8) differsFromBackground = true;
                if (Math.abs((after[channel] & 255) - (sample.rgba[channel] & 255) * TINT[channel]) > 8)
                    differsFromOpaqueModel = true;
            }
            sampleChecks++;
            if (mismatch) sampleMismatches++;
            if (differsFromBackground && differsFromOpaqueModel) backgroundPixels++;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", sample.x);
            row.setInteger("y", sample.y);
            row.setByteArray("nativeRgba", sample.rgba);
            row.setByteArray("sceneBeforeRgba", sample.before);
            row.setByteArray("compositedRgba", after);
            row.setFloat("effectiveAlpha", alpha);
            pixelEvidence.appendTag(row);
        }
        if (!samples.isEmpty()) {
            sampled = true;
            depthPending = true;
        }
    }

    /** Opt-in readback measures the actual composite, before borders, rather than treating draw calls as pixels. */
    private List<Sample> samples() {
        List<Sample> result = new ArrayList<>();
        if (sampled || !Boolean.getBoolean("gtmb.hologram.cutNativeClient")) return result;
        GL11.glReadBuffer(0x8ce0);
        ByteBuffer image = BufferUtils.createByteBuffer(width * height * 4);
        pixelStore();
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
        // Spread native-covered samples across the frame, including fully opaque native pixels.
        for (int y = 0; y < height && result.size() < 16; y += Math.max(1, height / 64))
            for (int x = 0; x < width && result.size() < 16; x += Math.max(1, width / 64)) {
                int offset = (y * width + x) * 4;
                if ((image.get(offset + 3) & 255) < 240) continue;
                byte[] rgba = new byte[4];
                for (int channel = 0; channel < 4; channel++) rgba[channel] = image.get(offset + channel);
                result.add(new Sample(x, y, rgba));
            }
        return result;
    }

    private static byte[] pixel(int x, int y) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        byte[] result = new byte[4];
        pixel.get(result);
        return result;
    }

    private static void pixelStore() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, GL11.GL_FALSE);
        GL11.glPixelStorei(GL11.GL_PACK_LSB_FIRST, GL11.GL_FALSE);
    }

    private String sceneDepthDigest() {
        ByteBuffer values = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, values);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(values);
            StringBuilder result = new StringBuilder();
            for (byte value : hash.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    void finishFrame() {
        if (!depthPending) return;
        // Includes both the native layer and the subsequent real-world edge pass.
        depthAfter = sceneDepthDigest();
        depthChecks++;
        if (!depthBefore.equals(depthAfter)) depthMismatches++;
        depthPending = false;
    }

    void inspect(NBTTagCompound out) {
        out.setLong("ghostDepthCopyChecks", depthCopyChecks);
        out.setLong("ghostDepthCopyMismatches", depthCopyMismatches);
        out.setString("ghostCopiedSceneDepthSha256", depthCopied);
        out.setLong("ghostPixelChecks", sampleChecks);
        out.setLong("ghostPixelMismatches", sampleMismatches);
        out.setLong("ghostVisibleBackgroundPixels", backgroundPixels);
        out.setTag("ghostPixelEvidence", pixelEvidence.copy());
        out.setLong("ghostSceneDepthChecks", depthChecks);
        out.setLong("ghostSceneDepthMismatches", depthMismatches);
        out.setString("ghostSceneDepthBeforeSha256", depthBefore);
        out.setString("ghostSceneDepthAfterSha256", depthAfter);
    }

    void reset() {
        sampled = false;
    }

    private static final class Sample {

        final int x, y;
        final byte[] rgba;
        byte[] before;

        Sample(int x, int y, byte[] rgba) {
            this.x = x;
            this.y = y;
            this.rgba = rgba;
        }
    }
}
