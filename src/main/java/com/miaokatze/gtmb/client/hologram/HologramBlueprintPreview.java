package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramAeNativePreview;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Detached native model capture. JSON/NBT decoding and PNG encoding belong to the library IO worker. */
public final class HologramBlueprintPreview {

    static final int SIZE = 640;
    private static final int FRAMEBUFFER = 0x8d40, RENDERBUFFER = 0x8d41;
    private static final ArrayBlockingQueue<Request> REQUESTS = new ArrayBlockingQueue<>(4);
    private static final HologramBlueprintPreview LISTENER = new HologramBlueprintPreview();
    private static final Map<NBTTagCompound, SourceReceipt> SOURCES = new IdentityHashMap<>();
    private static final ArrayDeque<NBTTagCompound> SOURCE_ORDER = new ArrayDeque<>();
    private static final AtomicLong CAPTURE_GENERATION = new AtomicLong();
    private static volatile NBTTagCompound captureWitness = new NBTTagCompound();
    private static volatile boolean installed;
    private static boolean capturing;

    private HologramBlueprintPreview() {}

    /** No GL, live world access, registry lookups, native factories, or mutable GUI state on this worker path. */
    static NBTTagCompound snapshot(byte[] exactJson) throws IOException {
        return snapshot(exactJson, null);
    }

    static NBTTagCompound snapshot(byte[] exactJson, java.util.function.BiConsumer<Integer, Integer> progress)
        throws IOException {
        if (exactJson == null || exactJson.length == 0 || exactJson.length > HologramBlueprintArtifact.MAX_JSON_BYTES)
            throw new IOException("Blueprint preview JSON byte budget");
        boolean witness = Boolean.getBoolean("gtmb.hologram.previewCaptureWitness");
        byte[] sourceJson = witness ? exactJson.clone() : exactJson;
        try {
            JsonObject share = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec.parse(sourceJson);
            JsonObject blueprint = share.getAsJsonObject("blueprint");
            if (blueprint == null) throw new IOException("Blueprint preview requires the exact exported share file");
            JsonArray sourceCells = blueprint.getAsJsonArray("cells");
            if (sourceCells == null || sourceCells.size() == 0) throw new IOException("Blueprint preview has no cells");
            NBTTagCompound data = new NBTTagCompound();
            data.setString("session", "blueprint-artifact");
            data.setInteger("mode", 0);
            data.setBoolean("localMaterialPolicyPresent", blueprint.has("retainNbt"));
            data.setBoolean(
                "retainNbt",
                !blueprint.has("retainNbt") || blueprint.get("retainNbt")
                    .getAsBoolean());
            data.setBoolean(
                "retainCovers",
                !blueprint.has("retainCovers") || blueprint.get("retainCovers")
                    .getAsBoolean());
            if (blueprint.has("retainedNbtKeys") && blueprint.get("retainedNbtKeys")
                .isJsonArray()) {
                NBTTagList retained = new NBTTagList();
                for (JsonElement key : blueprint.getAsJsonArray("retainedNbtKeys"))
                    retained.appendTag(new net.minecraft.nbt.NBTTagString(key.getAsString()));
                data.setTag("retainedNbtKeys", retained);
            }
            NBTTagList cells = new NBTTagList();
            Payloads payloads = new Payloads();
            int processed = 0;
            if (progress != null) progress.accept(0, sourceCells.size());
            for (JsonElement row : sourceCells) {
                if (progress != null && (++processed & 255) == 0) progress.accept(processed, sourceCells.size());
                JsonObject source = row.getAsJsonObject();
                JsonElement excluded = source.get("excluded");
                if (excluded != null && excluded.isJsonPrimitive()
                    && excluded.getAsJsonPrimitive()
                        .isBoolean()
                    && excluded.getAsBoolean()) continue;
                NBTTagCompound cell = new NBTTagCompound();
                for (String axis : new String[] { "x", "y", "z" }) {
                    int coordinate = integer(source, axis, 0);
                    if (Math.abs((long) coordinate) > (axis.equals("y") ? 256L : 60000000L))
                        throw new IOException("Blueprint preview coordinate out of range");
                    cell.setInteger(axis, coordinate);
                    cell.setInteger("d" + axis, coordinate);
                }
                cell.setString("wantId", string(source, "block"));
                cell.setInteger("wantMeta", integer(source, "metadata", 0));
                cell.setInteger("targetFacing", integer(source, "facing", -1));
                cell.setString("previewItem", string(source, "item"));
                cell.setInteger("previewDamage", integer(source, "damage", 0));
                NBTTagCompound tile = payloads.decode(string(source, "tileNbt"));
                NBTTagCompound item = payloads.decode(string(source, "itemNbt"));
                if (tile != null) cell.setTag("previewTile", tile.copy());
                if (item != null) cell.setTag("previewItemTag", item.copy());
                // Controller cells use their target data too; anchor labels belong to the editor.
                cells.appendTag(cell);
            }
            if (cells.tagCount() == 0) throw new IOException("Blueprint preview has no buildable cells");
            data.setTag("cells", cells);
            if (progress != null) progress.accept(sourceCells.size(), sourceCells.size());
            if (witness) rememberSource(data, HologramBlueprintArtifact.hash(sourceJson));
            return data;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid blueprint preview JSON", invalid);
        }
    }

    /** Structural decoding bounds only, deliberately independent of construction policy. */
    static String boundedJson(byte[] bytes) throws IOException {
        String json = new String(bytes, StandardCharsets.UTF_8);
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') quoted = true;
            else if (ch == '{' || ch == '[') {
                if (++depth > 64) throw new IOException("Blueprint preview JSON nesting budget");
            } else if (ch == '}' || ch == ']') {
                if (--depth < 0) throw new IOException("Blueprint preview JSON nesting");
            }
        }
        if (quoted || depth != 0) throw new IOException("Incomplete blueprint preview JSON");
        return json;
    }

    /** Called from a worker. This takes its own immutable input copy and queues exactly one render-context capture. */
    static Request capture(NBTTagCompound exactSnapshot, BooleanSupplier current, Consumer<BufferedImage> done,
        Consumer<Throwable> failure) {
        return capture(
            exactSnapshot,
            HologramSavePreviewPose.DEFAULT_YAW,
            HologramSavePreviewPose.DEFAULT_PITCH,
            current,
            done,
            failure);
    }

    static Request capture(NBTTagCompound exactSnapshot, double yaw, double pitch, BooleanSupplier current,
        Consumer<BufferedImage> done, Consumer<Throwable> failure) {
        Request request = new Request(exactSnapshot, yaw, pitch, current, done, failure);
        if (!REQUESTS.offer(request)) {
            request.fail(new IOException("Blueprint preview queue is full"));
            return request;
        }
        try {
            Minecraft.getMinecraft()
                .func_152344_a(HologramBlueprintPreview::install);
        } catch (RuntimeException unavailable) {
            REQUESTS.remove(request);
            request.fail(unavailable);
        }
        return request;
    }

    static synchronized void install() {
        if (installed) return;
        FMLCommonHandler.instance()
            .bus()
            .register(LISTENER);
        installed = true;
    }

    /** The renderer uses this scoped flag to omit editor outlines, role labels, selection, and annotations. */
    static boolean cleanCapture() {
        return capturing;
    }

    /**
     * Read-only opt-in evidence from an actual successful END capture. RGB SHA-256 packs unsigned R, G, B bytes,
     * three bytes per pixel, top-left row-major order, without alpha. The published compound is never mutated.
     */
    public static NBTTagCompound lastCaptureWitness() {
        return (NBTTagCompound) captureWitness.copy();
    }

    private static void rememberSource(NBTTagCompound snapshot, String hash) {
        synchronized (SOURCES) {
            while (SOURCES.size() >= 4) SOURCES.remove(SOURCE_ORDER.removeFirst());
            // Retain a private frozen value as well as its identity: caller edits cannot inherit trusted provenance.
            SOURCES.put(snapshot, new SourceReceipt((NBTTagCompound) snapshot.copy(), hash));
            SOURCE_ORDER.addLast(snapshot);
        }
    }

    private static SourceReceipt takeSource(NBTTagCompound snapshot) {
        synchronized (SOURCES) {
            SourceReceipt source = SOURCES.remove(snapshot);
            SOURCE_ORDER.removeIf(value -> value == snapshot);
            return source;
        }
    }

    private static void recordWitness(Request request) {
        if (!Boolean.getBoolean("gtmb.hologram.previewCaptureWitness") || request.sourceJsonSha256.isEmpty()
            || request.rgbSha256.isEmpty()
            || !request.readbackSucceeded
            || !request.nativeRendering) return;
        NBTTagCompound witness = new NBTTagCompound();
        witness.setString("sourceJsonSha256", request.sourceJsonSha256);
        witness.setString("rgbSha256", request.rgbSha256);
        witness.setString("rgbPacking", "RGB8_TOP_LEFT_ROW_MAJOR");
        witness.setInteger("width", SIZE);
        witness.setInteger("height", SIZE);
        witness.setBoolean("renderTickEnd", true);
        witness.setBoolean("readbackSucceeded", true);
        witness.setBoolean("nativeRendering", true);
        witness.setDouble("cameraYaw", request.yaw);
        witness.setDouble("cameraPitch", request.pitch);
        witness.setInteger("preexistingErrorCount", request.preexistingErrorCount);
        witness.setLong("captureGeneration", CAPTURE_GENERATION.incrementAndGet());
        captureWitness = witness;
    }

    private static final class SourceReceipt {

        private final NBTTagCompound snapshot;
        private final String hash;

        SourceReceipt(NBTTagCompound snapshot, String hash) {
            this.snapshot = snapshot;
            this.hash = hash;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Request request = REQUESTS.poll();
        if (request == null) return;
        try {
            request.requireCurrent();
            if (request.preparation == null)
                request.preparation = new HologramState.Preparation(request.snapshot, true);
            if (!request.preparation.step(256, 2_000_000L)) {
                if (!REQUESTS.offer(request)) request.fail(new IOException("Blueprint preview queue is full"));
                return;
            }
            request.preparedState = request.preparation.result();
            BufferedImage image = render(request);
            request.requireCurrent();
            if (request.completed.compareAndSet(false, true)) {
                recordWitness(request);
                request.done.accept(image);
            }
        } catch (Throwable failure) {
            request.fail(failure);
        }
    }

    static final class Request {

        final double yaw, pitch;
        private final NBTTagCompound snapshot;
        private final BooleanSupplier current;
        private final Consumer<BufferedImage> done;
        private final Consumer<Throwable> failure;
        private final String sourceJsonSha256;
        private final AtomicBoolean completed = new AtomicBoolean();
        private String rgbSha256 = "";
        private int preexistingErrorCount;
        private boolean nativeRendering, readbackSucceeded;
        private volatile boolean cancelled;
        private HologramState.Preparation preparation;
        private HologramState preparedState;

        Request(NBTTagCompound snapshot, double yaw, double pitch, BooleanSupplier current,
            Consumer<BufferedImage> done, Consumer<Throwable> failure) {
            if (!Double.isFinite(yaw) || !Double.isFinite(pitch))
                throw new IllegalArgumentException("Invalid preview camera");
            this.yaw = yaw;
            this.pitch = pitch;
            SourceReceipt source = takeSource(Objects.requireNonNull(snapshot));
            this.sourceJsonSha256 = source == null ? "" : source.hash;
            this.snapshot = (NBTTagCompound) (source == null ? snapshot : source.snapshot).copy();
            this.current = Objects.requireNonNull(current);
            this.done = Objects.requireNonNull(done);
            this.failure = Objects.requireNonNull(failure);
        }

        void cancel() {
            cancelled = true;
        }

        private void requireCurrent() {
            if (cancelled || !current.getAsBoolean()) throw new CancellationException("Stale blueprint preview");
        }

        private void fail(Throwable cause) {
            if (!completed.compareAndSet(false, true)) return;
            try {
                failure.accept(cause);
            } catch (RuntimeException callbackFailure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                    .warn("Blueprint preview failure callback failed", callbackFailure);
            }
        }
    }

    private static BufferedImage render(Request request) throws Exception {
        boolean witness = Boolean.getBoolean("gtmb.hologram.previewCaptureWitness")
            && !request.sourceJsonSha256.isEmpty();
        if (witness) request.preexistingErrorCount = clearPreexistingErrors();
        if (!OpenGlHelper.isFramebufferEnabled()) throw new IOException("Native framebuffer previews are unavailable");
        HologramState state = request.preparedState;
        if (state == null || request.snapshot.getBoolean("displayPartial")
            || request.snapshot.getBoolean("partial")
            || state.cells.size() != request.snapshot.getTagList("cells", 10)
                .tagCount())
            throw new IOException("Blueprint preview is not completely prepared");
        for (HologramState.Cell cell : state.cells) if (cell.nativeModelPending != 0)
            throw new IOException("Blueprint preview model source is incomplete; canonical capture is required");
        ClientTessState tess = new ClientTessState(Tessellator.instance);
        RenderState saved = new RenderState();
        Framebuffer framebuffer = null;
        boolean oldEmpty = HologramRenderer.showEmptyCells;
        HologramSelection oldSelection = HologramRenderer.selection;
        String oldStructure = HologramRenderer.selectedStructureId;
        try {
            saved.push();
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            framebuffer = new Framebuffer(SIZE, SIZE, true);
            framebuffer.bindFramebuffer(true);
            if (OpenGlHelper.func_153167_i(FRAMEBUFFER) != 0x8cd5)
                throw new IOException("Native preview framebuffer is incomplete");
            GL11.glViewport(0, 0, SIZE, SIZE);
            // Enable the explicit FBO scissor before preview: its GUI fallback depends on the display scale.
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, SIZE, SIZE);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glDepthRange(0, 1);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_LINE);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_POINT);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            for (int plane = 0; plane < 6; plane++) GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
            GL11.glClearColor(.04f, .075f, .10f, 1);
            GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            saved.useProgram(0);
            saved.cleanTextureUnits();
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, SIZE, SIZE, 0, -1000, 1000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            HologramRenderer.showEmptyCells = false;
            HologramRenderer.selection = null;
            HologramRenderer.selectedStructureId = "";
            capturing = true;
            request.requireCurrent();
            HologramRenderer
                .preview(state, 0, 0, SIZE, SIZE, request.yaw, request.pitch, 1, Integer.MAX_VALUE, 0, -1, -1, -1, -1);
            // A native shape must never be silently replaced with the renderer's diagnostic cube fallback.
            for (HologramState.Cell cell : state.cells) {
                Block block = cell.block(false);
                if (HologramNativeTiles.supported(block)) {
                    if (!HologramBlockRender.ready(cell, false)) throw new IOException(
                        "Native blueprint model unavailable: " + cell.wantId
                            + ": "
                            + HologramBlockRender.failure(cell, false));
                    if (HologramBlockRender.missingTexture(cell, false))
                        throw new IOException("Native blueprint material unavailable: " + cell.wantId);
                } else if (block != Blocks.air && HologramTextures.material(cell, false).fallback)
                    throw new IOException("Blueprint material unavailable: " + cell.wantId);
            }
            request.nativeRendering = true;
            request.requireCurrent();
            saved.unbindPixelBuffers();
            GL11.glReadBuffer(0x8ce0); // GL_COLOR_ATTACHMENT0, independent of the window's front/back buffer.
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            if (saved.pixelTransferAvailable) {
                // Legacy readback applies this state. Core profiles removed it and Angelica does not emulate it.
                GL11.glPixelTransferi(GL11.GL_MAP_COLOR, GL11.GL_FALSE);
                for (int channel : new int[] { GL11.GL_RED_SCALE, GL11.GL_GREEN_SCALE, GL11.GL_BLUE_SCALE,
                    GL11.GL_ALPHA_SCALE }) GL11.glPixelTransferf(channel, 1);
                for (int channel : new int[] { GL11.GL_RED_BIAS, GL11.GL_GREEN_BIAS, GL11.GL_BLUE_BIAS,
                    GL11.GL_ALPHA_BIAS }) GL11.glPixelTransferf(channel, 0);
            }
            ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            if (witness) {
                int error = GL11.glGetError();
                if (error != GL11.GL_NO_ERROR) throw new IOException("Native preview readback GL error: " + error);
            }
            request.readbackSucceeded = true;
            int[] colors = new int[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                int offset = (y * SIZE + x) * 4;
                colors[(SIZE - 1 - y) * SIZE + x] = (pixels.get(offset) & 255) << 16
                    | (pixels.get(offset + 1) & 255) << 8
                    | pixels.get(offset + 2) & 255;
            }
            if (witness) {
                byte[] rgb = new byte[SIZE * SIZE * 3];
                for (int pixel = 0; pixel < colors.length; pixel++) {
                    rgb[pixel * 3] = (byte) (colors[pixel] >>> 16);
                    rgb[pixel * 3 + 1] = (byte) (colors[pixel] >>> 8);
                    rgb[pixel * 3 + 2] = (byte) colors[pixel];
                }
                request.rgbSha256 = HologramBlueprintArtifact.hash(rgb);
            }
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, SIZE, SIZE, colors, 0, SIZE);
            return image;
        } finally {
            capturing = false;
            HologramRenderer.showEmptyCells = oldEmpty;
            HologramRenderer.selection = oldSelection;
            HologramRenderer.selectedStructureId = oldStructure;
            try {
                // Release detached tiles and display lists; the next live preview rebuilds its normal caches.
                try {
                    HologramRenderer.release();
                } finally {
                    if (framebuffer != null) framebuffer.deleteFramebuffer();
                }
            } finally {
                try {
                    saved.restore();
                } finally {
                    tess.restore();
                }
            }
        }
    }

    /** The END callback may inherit an older GUI/mod error; retain all errors produced by this capture afterward. */
    private static int clearPreexistingErrors() throws IOException {
        for (int count = 0; count < 16; count++) {
            if (GL11.glGetError() == GL11.GL_NO_ERROR) return count;
        }
        throw new IOException("Native preview preexisting GL errors could not be cleared");
    }

    /** Resolve one disposable display row on the client thread, without world or payment validation. */
    static void completeCell(NBTTagCompound cell) throws IOException {
        Object item = Item.itemRegistry.getObject(cell.getString("previewItem"));
        ItemStack stack = item instanceof Item ? new ItemStack((Item) item, 1, cell.getInteger("previewDamage")) : null;
        if (stack == null && !cell.getString("previewItem")
            .isEmpty()) throw new IOException("Blueprint preview item is missing: " + cell.getString("previewItem"));
        if (stack != null && stack.getItem() instanceof gregtech.common.blocks.ItemMachines) {
            int id = stack.getItemDamage();
            if (id < 0 || id >= gregtech.api.GregTechAPI.METATILEENTITIES.length
                || gregtech.api.GregTechAPI.METATILEENTITIES[id] == null)
                throw new IOException("Blueprint preview GT machine is missing: " + id);
        }
        if (stack != null && cell.hasKey("previewItemTag", 10)) {
            NBTTagCompound itemTag = cell.getCompoundTag("previewItemTag");
            restoreStableItems(itemTag);
            stack.setTagCompound(itemTag);
        }
        boolean inferredBlock = cell.getString("wantId")
            .isEmpty();
        Block block = inferredBlock && stack != null ? Block.getBlockFromItem(stack.getItem())
            : Block.getBlockFromName(cell.getString("wantId"));
        if (block == null) throw new IOException("Blueprint preview block is missing: " + cell.getString("wantId"));
        if (block == Blocks.air && !"minecraft:air".equals(cell.getString("wantId")))
            throw new IOException("Blueprint preview material has no native block");
        if (inferredBlock && stack != null && !(stack.getItem() instanceof gregtech.common.blocks.ItemMachines))
            cell.setInteger(
                "wantMeta",
                stack.getItem()
                    .getMetadata(stack.getItemDamage()));
        cell.setString("wantId", String.valueOf(Block.blockRegistry.getNameForObject(block)));
        NBTTagCompound tile = cell.hasKey("previewTile", 10) ? cell.getCompoundTag("previewTile") : null;
        if (tile != null) restoreStableItems(tile);
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("color", -1);
        int facing = cell.getInteger("targetFacing");
        if (facing >= 0) display.setInteger("facing", facing);
        if (HologramAeNativePreview.supports(block)) {
            display.setTag(
                "nativeTile",
                HologramAeNativePreview.target(block, tile == null ? new NBTTagCompound() : tile));
        } else if (tile != null) display.setTag("nativeTile", tile.copy());
        if (tile != null) {
            for (String key : new String[] { "texturePage", "textureIndex" })
                if (tile.hasKey(key, 3)) display.setInteger(key, tile.getInteger(key));
            if (tile.hasKey("mColor")) display.setInteger("color", tile.getByte("mColor") - 1);
            if (tile.hasKey("mConnections")) display.setInteger("connections", tile.getByte("mConnections") & 63);
            if (tile.hasKey("mActive")) display.setBoolean("active", tile.getBoolean("mActive"));
            if (facing < 0 && tile.hasKey("mFacing")) {
                cell.setInteger("targetFacing", tile.getShort("mFacing"));
                display.setInteger("facing", tile.getShort("mFacing"));
            }
            if (tile.hasKey("gt.covers", 9)) display.setTag(
                "gt.covers",
                tile.getTag("gt.covers")
                    .copy());
        }
        if (stack != null) cell.setTag("targetStack", stack.writeToNBT(new NBTTagCompound()));
        cell.setTag("targetDisplay", display);
        cell.setString("id", cell.getString("wantId"));
        cell.setInteger("meta", cell.getInteger("wantMeta"));
        cell.removeTag("previewTile");
        cell.removeTag("previewItemTag");
        cell.removeTag("previewItem");
        cell.removeTag("previewDamage");
    }

    /** The same stable item/cover transport identities used by the native blueprint codec. */
    private static void restoreStableItems(NBTBase value) throws IOException {
        if (value instanceof NBTTagCompound) {
            NBTTagCompound tag = (NBTTagCompound) value;
            if (tag.hasKey("id", 99) && tag.hasKey("Count", 99)
                && tag.hasKey("Damage", 99)
                && tag.hasKey("gtitBlueprintItemRegistry", 8)) {
                Object item = Item.itemRegistry.getObject(tag.getString("gtitBlueprintItemRegistry"));
                if (!(item instanceof Item)) throw new IOException(
                    "Blueprint preview nested item is missing: " + tag.getString("gtitBlueprintItemRegistry"));
                tag.setShort("id", (short) Item.getIdFromItem((Item) item));
                tag.removeTag("gtitBlueprintMissingItem");
            }
            if (tag.hasKey("id", 3) && tag.hasKey("gtitBlueprintCoverRegistry", 8)) {
                Object item = Item.itemRegistry.getObject(tag.getString("gtitBlueprintCoverRegistry"));
                if (!(item instanceof Item)) throw new IOException(
                    "Blueprint preview cover is missing: " + tag.getString("gtitBlueprintCoverRegistry"));
                tag.setInteger(
                    "id",
                    Item.getIdFromItem((Item) item) | tag.getInteger("gtitBlueprintCoverDamage") << 16);
                tag.removeTag("gtitBlueprintMissingCover");
            }
            for (Object key : new ArrayList<>(tag.func_150296_c())) restoreStableItems(tag.getTag((String) key));
        } else if (value instanceof NBTTagList) {
            try {
                @SuppressWarnings("unchecked")
                List<NBTBase> entries = (List<NBTBase>) ReflectionHelper
                    .findField(NBTTagList.class, "tagList", "field_74747_a")
                    .get(value);
                for (NBTBase child : entries) restoreStableItems(child);
            } catch (IllegalAccessException inaccessible) {
                throw new IllegalStateException("Blueprint preview NBT list inaccessible", inaccessible);
            }
        }
    }

    private static int integer(JsonObject row, String key, int fallback) {
        JsonElement value = row.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static String string(JsonObject row, String key) {
        JsonElement value = row.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static final class Payloads {

        private final Map<String, NBTTagCompound> decoded = new HashMap<>();
        private final Map<String, Integer> expanded = new HashMap<>();
        private long total;

        NBTTagCompound decode(String encoded) throws IOException {
            if (encoded.isEmpty()) return null;
            if (encoded.length() > ((1024 * 1024 + 2) / 3) * 4)
                throw new IOException("Blueprint preview NBT byte budget");
            if (!decoded.containsKey(encoded)) {
                byte[] bytes = Base64.getDecoder()
                    .decode(encoded);
                if (bytes.length > 1024 * 1024) throw new IOException("Blueprint preview NBT byte budget");
                int size = 0;
                try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                    byte[] chunk = new byte[8192];
                    for (int count; (count = input.read(chunk)) != -1;) {
                        size += count;
                        if (size > 4 * 1024 * 1024) throw new IOException("Blueprint preview expanded NBT byte budget");
                    }
                }
                expanded.put(encoded, size);
                decoded.put(encoded, HologramSnapshotCodec.decode(bytes));
            }
            total += expanded.get(encoded);
            if (total > 64L * 1024 * 1024) throw new IOException("Blueprint preview total NBT byte budget");
            return decoded.get(encoded);
        }
    }

    /** Attribute stacks omit FBOs, buffer objects, programs, matrices, and Minecraft's lightmap cache. */
    private static final class RenderState {

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
    private static final class ClientTessState {

        private final Tessellator tessellator;
        private final Map<Field, Object> values = new HashMap<>();
        private final Map<Buffer, int[]> cursors = new java.util.IdentityHashMap<>();

        ClientTessState(Tessellator tessellator) throws ReflectiveOperationException {
            this.tessellator = tessellator;
            if (ReflectionHelper.findField(Tessellator.class, "isDrawing", "field_78415_z")
                .getBoolean(tessellator))
                throw new IllegalStateException("Cannot capture a blueprint inside an active tessellator batch");
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
}
