// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.zip.CRC32;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.nbt.NBTTagCompound;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VertexBuffer;

/** Opt-in synthetic large blueprint, prepared and drawn in the production client contexts. */
final class HologramLiteGiantProbe implements AutoCloseable {

    private static final int CELLS = 123_369, X = 72, Y = 48, Z = 64;
    private static final int VIEWPORT = 384, WARM_FRAMES = 8, FRAMEBUFFER = 0x8d40;
    private static final long PREPARE_NANOS = 2_000_000L;
    private static final double YAW = .66, PITCH = .48, ZOOM = 1;

    private final long started = System.nanoTime();
    private final GuiScreen owner;
    private final Map<String, Object> receipt;
    private final FutureTask<Decoded> decoding = new FutureTask<>(HologramLiteGiantProbe::decode);
    private HologramState.Preparation preparation;
    private HologramState display, complete;
    private int preparationTicks, renderFrames, warmFrames;
    private long clientPreparationNanos, maxPreparationNanos, maxRenderNanos, warmNanos;
    private long fullCrc;
    private Object residentScene;
    private Set<Integer> fullBuffers;
    private boolean decodeReceived, firstMaterial;

    HologramLiteGiantProbe(GuiScreen owner, Map<String, Object> receipt) {
        this.owner = owner;
        this.receipt = receipt;
        receipt.put("passed", false);
        receipt.put("source", "deterministic compact grid with hollow areas and a solid core");
        receipt.put("sourceCells", CELLS);
        receipt.put("fixtureUsesPrivateData", false);
        receipt.put("drawContext", "RenderTick.END isolated 384x384 framebuffer");
        receipt.put("firstMaterialCriterion", "At least 24 red-dominant material pixels");
        receipt.put("prepareBudgetNanosPerClientTick", PREPARE_NANOS);
        receipt.put(
            "renderTimingScope",
            "CPU elapsed around HologramRenderer.preview, excluding test framebuffer and readback");
        Thread worker = new Thread(decoding, "GTMB-TestLab-CompactGiant");
        worker.setDaemon(true);
        worker.start();
    }

    void tick() throws Exception {
        requireOwner();
        if (!decodeReceived) {
            if (!decoding.isDone()) return;
            Decoded result = decoding.get();
            if (!(result.snapshot instanceof HologramBlueprintPreview.PackedDisplay) || result.snapshot.hasKey("cells"))
                throw new IllegalStateException("Large source did not retain the compact display representation");
            preparation = new HologramState.Preparation(result.snapshot, true);
            receipt.put("sourceBytes", result.bytes);
            receipt.put("fixtureGenerationMs", millis(result.generationNanos));
            receipt.put("compactDecodeMs", millis(result.decodeNanos));
            decodeReceived = true;
        }
        if (preparation == null) return;
        preparationTicks++;
        long before = System.nanoTime();
        boolean done = preparation.step(Integer.MAX_VALUE, PREPARE_NANOS);
        if (done) {
            complete = preparation.result();
            preparation = null;
            if (complete.cells.size() != CELLS || complete.data.getBoolean("displayPartial"))
                throw new IllegalStateException("Large complete snapshot has the wrong cell count or partial marker");
            display = complete;
        } else if (display == null) {
            display = preparation.firstDisplay();
            if (display != null) receipt.put("prefixStateCells", display.cells.size());
        }
        long elapsed = System.nanoTime() - before;
        clientPreparationNanos += elapsed;
        maxPreparationNanos = Math.max(maxPreparationNanos, elapsed);
        receipt.put("preparationTicks", preparationTicks);
        receipt.put("clientPreparationMs", millis(clientPreparationNanos));
        receipt.put("maxPreparationTickMs", millis(maxPreparationNanos));
        if (done) receipt.put("completeStateWallMs", elapsedMillis());
    }

    boolean frame() throws Exception {
        requireOwner();
        if (display == null) return false;
        renderFrames++;
        boolean warm = fullBuffers != null;
        int camera = warm ? ++warmFrames : 0;
        double yaw = YAW + camera * .074, pitch = PITCH - camera * .027, zoom = ZOOM + camera * .014;
        boolean readback = !firstMaterial || display == complete && (!warm || camera == WARM_FRAMES);
        Drawn drawn = draw(display, yaw, pitch, zoom, readback);
        maxRenderNanos = Math.max(maxRenderNanos, drawn.renderNanos);
        receipt.put("renderFrames", renderFrames);
        receipt.put("maxRendererCpuMs", millis(maxRenderNanos));
        if (!drawn.active) throw new IllegalStateException("Large preview fell back from MiaoVisonLite");
        if (!firstMaterial && drawn.pixels != null && drawn.pixels.red >= 24) {
            firstMaterial = true;
            receipt.put("firstMaterialFrame", renderFrames);
            receipt.put("firstMaterialWallMs", elapsedMillis());
            receipt.put("firstMaterialSnapshotCells", display.cells.size());
            receipt.put("firstMaterialPreparedCells", drawn.completed);
            receipt.put("firstMaterialRedPixels", drawn.pixels.red);
            receipt.put("firstMaterialWasPartial", display != complete || !drawn.ready);
            if (display == complete && drawn.ready)
                throw new IllegalStateException("Large preview delayed all material until full geometry was ready");
        }
        if (display != complete || !drawn.ready) return false;
        if (!firstMaterial || drawn.completed != CELLS || drawn.draws == 0)
            throw new IllegalStateException("Large readiness was published without all geometry and a material draw");
        if (!warm) {
            requirePixels(drawn);
            residentScene = residentScene();
            fullBuffers = bufferIds(residentScene);
            if (fullBuffers.isEmpty()) throw new IllegalStateException("No resident GL mesh buffers after full draw");
            fullCrc = drawn.pixels.rgbCrc;
            receipt.put("fullMaterialFrame", renderFrames);
            receipt.put("fullMaterialWallMs", elapsedMillis());
            receipt.put("fullPreparedCells", drawn.completed);
            receipt.put("fullResidentBuffers", fullBuffers.size());
            receipt.put("fullSubmittedDraws", drawn.draws);
            receipt.put("fullRedPixels", drawn.pixels.red);
            receipt.put("fullRgbCrc32", fullCrc);
            return false;
        }
        warmNanos += drawn.renderNanos;
        if (residentScene() != residentScene)
            throw new IllegalStateException("Changing only the camera replaced the resident mesh scene");
        if (camera < WARM_FRAMES) return false;
        requirePixels(drawn);
        if (!bufferIds(residentScene).equals(fullBuffers))
            throw new IllegalStateException("Changing only the camera replaced resident GL buffers");
        if (drawn.pixels.rgbCrc == fullCrc)
            throw new IllegalStateException("Moved camera produced identical framebuffer pixels");
        receipt.put("cameraFrames", WARM_FRAMES);
        receipt.put("cameraAverageRendererCpuMs", millis(warmNanos) / WARM_FRAMES);
        receipt.put("cameraLastSubmittedDraws", drawn.draws);
        receipt.put("cameraLastRedPixels", drawn.pixels.red);
        receipt.put("cameraLastRgbCrc32", drawn.pixels.rgbCrc);
        receipt.put("cameraResidentBuffersReused", true);
        receipt.put("passed", true);
        return true;
    }

    private Drawn draw(HologramState state, double yaw, double pitch, double zoom, boolean readback) {
        if (!OpenGlHelper.isFramebufferEnabled())
            throw new IllegalStateException("Large preview requires framebuffer support");
        boolean split = GLContext.getCapabilities().OpenGL30 || GLContext.getCapabilities().GL_ARB_framebuffer_object;
        int draw = GL11.glGetInteger(0x8ca6), read = split ? GL11.glGetInteger(0x8caa) : draw;
        int renderbuffer = GL11.glGetInteger(0x8ca7), matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        HologramSelection selection = HologramRenderer.selection;
        boolean empty = HologramRenderer.showEmptyCells;
        int[] offset = HologramRenderer.moveOffset;
        String structure = HologramRenderer.selectedStructureId;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        Framebuffer target = null;
        try {
            HologramRenderer.selection = null;
            HologramRenderer.showEmptyCells = false;
            HologramRenderer.moveOffset = new int[3];
            HologramRenderer.selectedStructureId = "";
            target = new Framebuffer(VIEWPORT, VIEWPORT, true);
            target.bindFramebuffer(true);
            if (OpenGlHelper.func_153167_i(FRAMEBUFFER) != 0x8cd5)
                throw new IllegalStateException("Large preview framebuffer is incomplete");
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, VIEWPORT, VIEWPORT);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glDepthRange(0, 1);
            GL11.glClearColor(17 / 255f, 27 / 255f, 42 / 255f, 1);
            GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, VIEWPORT, VIEWPORT, 0, -1000, 1000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            long before = System.nanoTime();
            HologramRenderer
                .preview(state, 0, 0, VIEWPORT, VIEWPORT, yaw, pitch, zoom, Integer.MAX_VALUE, 0, -1, -1, -1, -1);
            long renderNanos = System.nanoTime() - before;
            boolean active = HologramRenderer.litePreviewActive(), ready = HologramRenderer.previewMeshReady(state);
            int completed = HologramRenderer.previewMeshCompleted(state), draws = HologramRenderer.litePreviewDraws();
            PixelEvidence pixels = readback && HologramRenderer.previewMeshVisible(state) && (!firstMaterial || ready)
                ? pixels()
                : null;
            return new Drawn(renderNanos, active, ready, completed, draws, pixels);
        } finally {
            HologramRenderer.selection = selection;
            HologramRenderer.showEmptyCells = empty;
            HologramRenderer.moveOffset = offset;
            HologramRenderer.selectedStructureId = structure;
            if (target != null) target.deleteFramebuffer();
            OpenGlHelper.func_153171_g(split ? 0x8ca9 : FRAMEBUFFER, draw);
            if (split) OpenGlHelper.func_153171_g(0x8ca8, read);
            OpenGlHelper.func_153176_h(0x8d41, renderbuffer);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            GL11.glPopAttrib();
        }
    }

    private static PixelEvidence pixels() {
        GL11.glReadBuffer(0x8ce0);
        ByteBuffer rgba = new HologramNativeRasterSmoke.Slice(0, 0, VIEWPORT, VIEWPORT).read();
        int red = 0;
        CRC32 crc = new CRC32();
        for (int at = 0; at < rgba.capacity(); at += 4) {
            int r = rgba.get(at) & 255, g = rgba.get(at + 1) & 255, b = rgba.get(at + 2) & 255;
            crc.update(r);
            crc.update(g);
            crc.update(b);
            if (r >= 64 && r >= g + 22 && r >= b + 22) red++;
        }
        return new PixelEvidence(red, crc.getValue());
    }

    private static void requirePixels(Drawn drawn) {
        if (drawn.pixels == null || drawn.pixels.red < 24)
            throw new IllegalStateException("Large preview submitted calls without visible red wool material");
    }

    private void requireOwner() {
        if (Minecraft.getMinecraft().currentScreen != owner)
            throw new IllegalStateException("Large preview lost its client UI context");
    }

    private static Object residentScene() throws ReflectiveOperationException {
        return field(HologramRenderer.class, "liteScene").get(null);
    }

    private static Set<Integer> bufferIds(Object adapter) throws ReflectiveOperationException {
        Object scene = field(adapter.getClass(), "geometry").get(adapter);
        List<?> regions = (List<?>) field(scene.getClass(), "regions").get(scene);
        Set<Integer> ids = new HashSet<>();
        if (regions.isEmpty()) return ids;
        Field meshes = field(
            regions.get(0)
                .getClass(),
            "meshes");
        Field buffer = null;
        for (Object region : regions) {
            Map<?, ?> layers = (Map<?, ?>) meshes.get(region);
            for (Object layer : layers.values()) for (Object mesh : (List<?>) layer) {
                if (buffer == null) buffer = field(mesh.getClass(), "buffer");
                int id = ((VertexBuffer) buffer.get(mesh)).getId();
                if (id <= 0 || !ids.add(id)) throw new IllegalStateException("Invalid resident GL buffer identity");
            }
        }
        return ids;
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Decoded decode() throws Exception {
        long start = System.nanoTime();
        byte[] json = fixture();
        long generated = System.nanoTime();
        NBTTagCompound snapshot = HologramBlueprintPreview.displaySnapshot(json, null);
        snapshot.setBoolean("localBlueprintDocument", true);
        return new Decoded(snapshot, json.length, generated - start, System.nanoTime() - generated);
    }

    private static byte[] fixture() {
        BitSet occupied = new BitSet(X * Y * Z);
        for (int z = 0; z < Z; z++) for (int y = 0; y < Y; y++) for (int x = 0; x < X; x++) {
            boolean surface = x == 0 || x == X - 1 || y == 0 || y == Y - 1 || z == 0 || z == Z - 1;
            boolean opening = (z == Z - 1 || x == X - 1) && y > 8 && y < 37 && (x > 20 && x < 57 || z > 23 && z < 50);
            boolean solid = x >= 2 && x < 58 && y >= 3 && y < 40 && z >= 2 && z < 49;
            if (surface && !opening || solid) occupied.set((z * Y + y) * X + x);
        }
        int remaining = CELLS - occupied.cardinality();
        for (int at = occupied.nextClearBit(0); remaining > 0 && at < X * Y * 52; at = occupied.nextClearBit(at + 1)) {
            occupied.set(at);
            remaining--;
        }
        if (remaining != 0) throw new IllegalStateException("Large synthetic blueprint cardinality");
        StringBuilder json = new StringBuilder(CELLS * 30);
        json.append("{\"encoding\":\"gtmb-dictionary-1\",\"materials\":[");
        json.append("{\"block\":\"minecraft:wool\",\"metadata\":14},{\"block\":\"minecraft:wool\",\"metadata\":11},");
        json.append("{\"block\":\"minecraft:wool\",\"metadata\":4},{\"block\":\"minecraft:stone\"}],");
        json.append("\"nbt\":[],\"blueprint\":{\"cells\":[");
        boolean comma = false;
        for (int at = occupied.nextSetBit(0); at >= 0; at = occupied.nextSetBit(at + 1)) {
            int x = at % X, y = at / X % Y, z = at / (X * Y);
            if (comma) json.append(',');
            comma = true;
            json.append('[')
                .append((x / 7 + y / 5 + z / 11) & 3)
                .append(",{\"x\":")
                .append(x - X / 2);
            json.append(",\"y\":")
                .append(y - Y / 2)
                .append(",\"z\":")
                .append(z - Z / 2)
                .append("}]");
        }
        return json.append("]}}")
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    private double elapsedMillis() {
        return millis(System.nanoTime() - started);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    @Override
    public void close() {
        decoding.cancel(true);
        if (display != null) HologramRenderer.sceneClosed();
        preparation = null;
        display = complete = null;
        residentScene = null;
        fullBuffers = null;
    }

    private static final class Decoded {

        final NBTTagCompound snapshot;
        final int bytes;
        final long generationNanos, decodeNanos;

        Decoded(NBTTagCompound snapshot, int bytes, long generationNanos, long decodeNanos) {
            this.snapshot = snapshot;
            this.bytes = bytes;
            this.generationNanos = generationNanos;
            this.decodeNanos = decodeNanos;
        }
    }

    private static final class PixelEvidence {

        final int red;
        final long rgbCrc;

        PixelEvidence(int red, long rgbCrc) {
            this.red = red;
            this.rgbCrc = rgbCrc;
        }
    }

    private static final class Drawn {

        final long renderNanos;
        final boolean active, ready;
        final int completed, draws;
        final PixelEvidence pixels;

        Drawn(long renderNanos, boolean active, boolean ready, int completed, int draws, PixelEvidence pixels) {
            this.renderNanos = renderNanos;
            this.active = active;
            this.ready = ready;
            this.completed = completed;
            this.draws = draws;
            this.pixels = pixels;
        }
    }
}
