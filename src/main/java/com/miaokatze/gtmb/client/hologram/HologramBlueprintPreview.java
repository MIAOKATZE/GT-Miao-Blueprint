// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.mark;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.client.hologram.HologramLocalSaveTrace.Stage;
import com.miaokatze.gtmb.hologram.HologramAeNativePreview;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramGtMeOutputDisplay;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.DetachedEditorRows;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.EditorRows;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.FrozenDraft;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteRenderScope;
import com.miaokatze.miaokatzecore.client.miaovisonlite.MiaoVisonLiteTarget;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Detached native model capture. JSON/NBT decoding and PNG encoding belong to the library IO worker. */
public final class HologramBlueprintPreview {

    static final int SIZE = 640;
    private static final ArrayBlockingQueue<Request> REQUESTS = new ArrayBlockingQueue<>(4);
    private static final HologramBlueprintPreview LISTENER = new HologramBlueprintPreview();
    private static final Map<NBTTagCompound, SourceReceipt> SOURCES = new IdentityHashMap<>();
    private static final ArrayDeque<NBTTagCompound> SOURCE_ORDER = new ArrayDeque<>();
    private static final AtomicLong CAPTURE_GENERATION = new AtomicLong();
    private static volatile NBTTagCompound captureWitness = new NBTTagCompound();
    private static volatile boolean installed;

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
            JsonObject share = HologramBlueprintCompactCodec.parse(sourceJson);
            JsonObject blueprint = HologramBlueprintCompactCodec.documentBlueprint(share);
            JsonArray sourceCells = blueprint.getAsJsonArray("cells");
            NBTTagCompound data = expandedDisplay(blueprint, sourceCells, progress);
            if (witness) rememberSource(data, HologramBlueprintArtifact.hash(sourceJson));
            return data;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid blueprint preview JSON", invalid);
        }
    }

    /** Worker-side local file decoding. Native resolution remains in the client preparation cursor. */
    static DisplayInput displayInput(byte[] bytes, java.util.function.BiConsumer<Integer, Integer> progress)
        throws IOException {
        return displayInput(bytes, progress, false);
    }

    static DisplayInput displayInput(byte[] bytes, java.util.function.BiConsumer<Integer, Integer> progress,
        boolean savedPrivateSource) throws IOException {
        return displayInput(bytes, progress, savedPrivateSource, false);
    }

    static DisplayInput displayInput(byte[] bytes, java.util.function.BiConsumer<Integer, Integer> progress,
        boolean savedPrivateSource, boolean indexedPrivate) throws IOException {
        try {
            var view=savedPrivateSource?HologramBlueprintCompactCodec.localDisplayGeometry(bytes,indexedPrivate)
                :HologramBlueprintCompactCodec.displayGeometry(bytes);
            if (!view.compact()) {
                HologramSavedMaterialSource.Tally tally = savedPrivateSource
                    ? new HologramSavedMaterialSource.Tally(view.blueprint, view.cells()) : null;
                NBTTagCompound display = expandedDisplay(view.blueprint, view.blueprint.getAsJsonArray("cells"),
                    progress, tally);
                return new DisplayInput(display, null, tally == null ? null : tally.snapshot());
            }
            CompactCells cells = new CompactCells(view.cells(), view.materials());
            Payloads payloads = new Payloads();
            boolean[] excluded = new boolean[view.materials()];
            long[] materialBytes = new long[view.materials()];
            HologramSavedMaterialSource.Tally tally = savedPrivateSource
                ? new HologramSavedMaterialSource.Tally(view.blueprint, view.materials())
                : null;
            if (progress != null) progress.accept(0, view.cells());
            for (int index = 0; index < view.cells(); index++) {
                if (progress != null && ((index + 1) & 255) == 0) progress.accept(index + 1, view.cells());
                int material = view.materialIndex(index);
                if (excluded[material]) continue;
                NBTTagCompound known = cells.materials[material];
                JsonObject definition = known == null ? view.material(material) : null;
                if (definition != null && excluded(definition)) {
                    excluded[material] = true;
                    continue;
                }
                int x = checkedCoordinate(view.coordinate(index, 0), 60000000L);
                int y = checkedCoordinate(view.coordinate(index, 1), 256L);
                int z = checkedCoordinate(view.coordinate(index, 2), 60000000L);
                if (known == null) {
                    long before = payloads.total;
                    cells.materials[material] = displayMaterial(definition, payloads);
                    materialBytes[material] = payloads.total - before;
                } else payloads.account(materialBytes[material]);
                cells.add(x, y, z, material);
                if (tally != null) tally.include(material, definition);
            }
            if (cells.size() == 0) throw new IOException("Blueprint preview has no buildable cells");
            if (progress != null) progress.accept(view.cells(), view.cells());
            return new DisplayInput(
                displayMetadata(view.blueprint),
                cells,
                tally == null ? null : tally.snapshot());
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid blueprint preview JSON", invalid);
        }
    }

    static DisplayInput displayInput(FrozenDraft draft, BooleanSupplier current,
        java.util.function.BiConsumer<Integer, Integer> progress) throws IOException {
        int total = draft.cellCount();
        CompactCells cells = new CompactCells(total, Math.min(total, 64));
        Map<DisplayMaterial, Integer> index = new HashMap<>();
        Payloads payloads = new Payloads();
        HologramSavedMaterialSource.Tally tally = new HologramSavedMaterialSource.Tally(total,
            draft.materialSummarySchemaKnown(), draft.materialSummaryNeedsReview());
        if (progress != null) progress.accept(0, total);
        draft.visitDisplayCells((x, y, z, block, metadata, item, damage, facing, tileNbt, itemNbt, count, controller) -> {
            DisplayMaterial material = new DisplayMaterial(block, metadata, item, damage, facing, tileNbt, itemNbt);
            Integer position = index.get(material);
            if (position == null) {
                position = index.size();
                cells.putMaterial(position, displayMaterial(material, payloads));
                index.put(material, position);
            } else {
                payloads.account(payloads.expanded.getOrDefault(tileNbt, 0));
                payloads.account(payloads.expanded.getOrDefault(itemNbt, 0));
            }
            cells.add(
                checkedCoordinate(x, 60000000L),
                checkedCoordinate(y, 256L),
                checkedCoordinate(z, 60000000L),
                position);
            tally.includeFrozen(block, metadata, item, damage, facing, count, controller, tileNbt, itemNbt);
            if (progress != null && (cells.size() & 255) == 0) progress.accept(cells.size(), total);
        }, current);
        if (cells.size() == 0) throw new IOException("Blueprint preview has no buildable cells");
        if (progress != null) progress.accept(total, total);
        return new DisplayInput(displayMetadata(draft), cells, tally.snapshot());
    }

    static NBTTagCompound snapshot(FrozenDraft draft, BooleanSupplier current) throws IOException {
        NBTTagCompound data = displayMetadata(draft);
        NBTTagList cells = new NBTTagList();
        Payloads payloads = new Payloads();
        draft.visitDisplayCells(
            (x, y, z, block, metadata, item, damage, facing, tileNbt, itemNbt, count, controller) -> cells.appendTag(
                displayRow(
                    displayMaterial(
                        new DisplayMaterial(block, metadata, item, damage, facing, tileNbt, itemNbt),
                        payloads),
                    checkedCoordinate(x, 60000000L),
                    checkedCoordinate(y, 256L),
                    checkedCoordinate(z, 60000000L))),
            current);
        if (cells.tagCount() == 0) throw new IOException("Blueprint preview has no buildable cells");
        data.setTag("cells", cells);
        return data;
    }

    static final class DisplayInput {

        final NBTTagCompound snapshot;
        final CompactCells cells;
        final HologramSavedMaterialSource savedMaterials;

        DisplayInput(NBTTagCompound snapshot, CompactCells cells) {
            this(snapshot,cells,null);
        }

        DisplayInput(NBTTagCompound snapshot, CompactCells cells, HologramSavedMaterialSource savedMaterials) {
            this.snapshot = snapshot;
            this.cells = cells;
            this.savedMaterials = savedMaterials;
        }
    }

    /** Each cell occupies four integers. Material data remains detached and unmodified until client preparation. */
    static final class CompactCells {

        private final int[] locations;
        private final EditorRows editor;
        private final boolean editable;
        private final int[] bounds;
        private NBTTagCompound[] materials;
        private int size;

        CompactCells(int cells, int materials) {
            locations = new int[Math.multiplyExact(cells, 4)];
            editor = null;
            editable = false;
            bounds = new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE };
            this.materials = new NBTTagCompound[materials];
        }

        CompactCells(EditorRows editor) {
            locations = null;
            this.editor = editor;
            editable = true;
            bounds = editor.bounds();
            materials = editor.materials();
            size = editor.rows()
                .cells();
        }

        CompactCells(DetachedEditorRows editor) {
            locations = editor.coordinates();
            this.editor = null;
            editable = true;
            bounds = editor.bounds();
            materials = editor.materials();
            size = locations.length / 4;
        }

        private void add(int x, int y, int z, int material) {
            int at = size++ * 4;
            locations[at] = x;
            locations[at + 1] = y;
            locations[at + 2] = z;
            locations[at + 3] = material;
            bounds[0] = Math.min(bounds[0], x);
            bounds[1] = Math.min(bounds[1], y);
            bounds[2] = Math.min(bounds[2], z);
            bounds[3] = Math.max(bounds[3], x);
            bounds[4] = Math.max(bounds[4], y);
            bounds[5] = Math.max(bounds[5], z);
        }

        private void putMaterial(int index, NBTTagCompound material) {
            if (index == materials.length)
                materials = java.util.Arrays.copyOf(materials, Math.min(locations.length / 4, materials.length * 2));
            materials[index] = material;
        }

        int size() {
            return size;
        }

        int coordinate(int row, int dimension) {
            if (editor == null) return locations[row * 4 + dimension];
            return dimension == 3 ? editor.rows()
                .materialIndex(row)
                : editor.rows()
                    .coordinate(row, dimension);
        }

        boolean editorRows() {
            return editable;
        }

        int[] bounds() {
            return bounds;
        }

        int materialCount() {
            return materials.length;
        }

        boolean definedMaterial(int index) {
            return materials[index] != null;
        }

        NBTTagCompound copyMaterial(int index) {
            return (NBTTagCompound) materials[index].copy();
        }
    }

    private static NBTTagCompound expandedDisplay(JsonObject blueprint, JsonArray sourceCells,
        java.util.function.BiConsumer<Integer, Integer> progress) throws IOException {
        return expandedDisplay(blueprint, sourceCells, progress, null);
    }

    private static NBTTagCompound expandedDisplay(JsonObject blueprint, JsonArray sourceCells,
        java.util.function.BiConsumer<Integer, Integer> progress, HologramSavedMaterialSource.Tally tally)
        throws IOException {
        if (sourceCells == null || sourceCells.size() == 0) throw new IOException("Blueprint preview has no cells");
        NBTTagCompound data = displayMetadata(blueprint);
        NBTTagList cells = new NBTTagList();
        Payloads payloads = new Payloads();
        int processed = 0;
        if (progress != null) progress.accept(0, sourceCells.size());
        for (JsonElement row : sourceCells) {
            if (progress != null && (++processed & 255) == 0) progress.accept(processed, sourceCells.size());
            JsonObject source = row.getAsJsonObject();
            if (excluded(source)) continue;
            int x = coordinate(source, "x"), y = coordinate(source, "y"), z = coordinate(source, "z");
            cells.appendTag(displayRow(displayMaterial(source, payloads), x, y, z));
            if (tally != null) tally.includeExpanded(source);
        }
        if (cells.tagCount() == 0) throw new IOException("Blueprint preview has no buildable cells");
        data.setTag("cells", cells);
        if (progress != null) progress.accept(sourceCells.size(), sourceCells.size());
        return data;
    }

    private static NBTTagCompound displayRow(NBTTagCompound cell, int x, int y, int z) {
        cell.setInteger("x", x);
        cell.setInteger("y", y);
        cell.setInteger("z", z);
        cell.setInteger("dx", x);
        cell.setInteger("dy", y);
        cell.setInteger("dz", z);
        return cell;
    }

    private static NBTTagCompound displayMetadata(FrozenDraft draft) {
        NBTTagCompound data = draft.displayMetadata();
        data.setString("session", "blueprint-artifact");
        data.setInteger("mode", 0);
        return data;
    }

    private static NBTTagCompound displayMetadata(JsonObject blueprint) {
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
        return data;
    }

    private static boolean excluded(JsonObject source) {
        JsonElement value = source.get("excluded");
        return value != null && value.isJsonPrimitive()
            && value.getAsJsonPrimitive()
                .isBoolean()
            && value.getAsBoolean();
    }

    private static int coordinate(JsonObject source, String axis) throws IOException {
        return checkedCoordinate(integer(source, axis, 0), axis.equals("y") ? 256L : 60000000L);
    }

    private static int checkedCoordinate(int value, long limit) throws IOException {
        if (Math.abs((long) value) > limit) throw new IOException("Blueprint preview coordinate out of range");
        return value;
    }

    private static NBTTagCompound displayMaterial(JsonObject source, Payloads payloads) throws IOException {
        return displayMaterial(
            new DisplayMaterial(
                string(source, "block"),
                integer(source, "metadata", 0),
                string(source, "item"),
                integer(source, "damage", 0),
                integer(source, "facing", -1),
                string(source, "tileNbt"),
                string(source, "itemNbt")),
            payloads);
    }

    @Desugar
    private record DisplayMaterial(String block, int metadata, String item, int damage, int facing, String tileNbt,
        String itemNbt) {}

    private static NBTTagCompound displayMaterial(DisplayMaterial material, Payloads payloads) throws IOException {
        NBTTagCompound cell = new NBTTagCompound();
        cell.setString("wantId", material.block());
        cell.setInteger("wantMeta", material.metadata());
        cell.setInteger("targetFacing", material.facing());
        cell.setString("previewItem", material.item());
        cell.setInteger("previewDamage", material.damage());
        NBTTagCompound tile = payloads.decode(material.tileNbt());
        NBTTagCompound item = payloads.decode(material.itemNbt());
        if (tile != null) cell.setTag("previewTile", tile.copy());
        if (item != null) cell.setTag("previewItemTag", item.copy());
        return cell;
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
        return queue(new Request(exactSnapshot, null, yaw, pitch, current, done, failure));
    }

    /** The local save worker owns the detached display rows and never mutates them after submission. */
    static Request captureOwned(NBTTagCompound exactSnapshot, byte[] exactJson, double yaw, double pitch,
        HologramLocalSaveTrace trace, BooleanSupplier current, Consumer<BufferedImage> done,
        Consumer<Throwable> failure) {
        String hash = Boolean.getBoolean("gtmb.hologram.previewCaptureWitness")
            ? HologramBlueprintArtifact.hash(exactJson)
            : "";
        return queue(new Request(exactSnapshot, hash, yaw, pitch, current, done, failure, trace));
    }

    static Request captureNative(HologramState view,double yaw,double pitch,BooleanSupplier current,
        Consumer<BufferedImage> done,Consumer<Throwable> failure){
        if(view.nativeViewport==null)throw new IllegalArgumentException("Native render revision required");
        Request request=new Request(view.data,"",yaw,pitch,current,done,failure);request.nativeView=view;
        return queue(request);
    }

    private static Request queue(Request request) {
        if (!REQUESTS.offer(request)) {
            request.fail(new IOException("Blueprint preview queue is full"));
            return request;
        }
        mark(request.saveTrace, Stage.CAPTURE_RENDER_QUEUED);
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
        mark(request.saveTrace, Stage.RENDER_END_POLL);
        try {
            request.requireCurrent();
            BufferedImage image = render(request);
            request.requireCurrent();
            if (request.completed.compareAndSet(false, true)) {
                recordWitness(request);
                mark(request.saveTrace, Stage.RENDER_IMAGE_READY);
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
        private final HologramLocalSaveTrace saveTrace;
        private final AtomicBoolean completed = new AtomicBoolean();
        private String rgbSha256 = "";
        private int preexistingErrorCount;
        private boolean nativeRendering, readbackSucceeded;
        private volatile boolean cancelled;
        private HologramState nativeView;

        Request(NBTTagCompound snapshot, String ownedSourceHash, double yaw, double pitch, BooleanSupplier current,
            Consumer<BufferedImage> done, Consumer<Throwable> failure) {
            this(snapshot, ownedSourceHash, yaw, pitch, current, done, failure, null);
        }

        Request(NBTTagCompound snapshot, String ownedSourceHash, double yaw, double pitch, BooleanSupplier current,
            Consumer<BufferedImage> done, Consumer<Throwable> failure, HologramLocalSaveTrace saveTrace) {
            this.saveTrace = saveTrace;
            if (!Double.isFinite(yaw) || !Double.isFinite(pitch))
                throw new IllegalArgumentException("Invalid preview camera");
            this.yaw = yaw;
            this.pitch = pitch;
            Objects.requireNonNull(snapshot);
            if (ownedSourceHash == null) {
                SourceReceipt source = takeSource(snapshot);
                sourceJsonSha256 = source == null ? "" : source.hash;
                this.snapshot = (NBTTagCompound) (source == null ? snapshot : source.snapshot).copy();
            } else {
                sourceJsonSha256 = ownedSourceHash;
                this.snapshot = snapshot;
            }
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
            if (!(cause instanceof CancellationException))
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Blueprint preview capture failed", cause);
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
        HologramState state = request.nativeView!=null?request.nativeView:completeSnapshot(request.snapshot);
        mark(request.saveTrace, Stage.RENDER_STATE_READY);
        MiaoVisonLiteRenderer viewport = new MiaoVisonLiteRenderer(
            new HologramPreviewAssets(),
            MiaoVisonLiteRenderer.View.CAPTURE);
        MiaoVisonLiteRenderScope.ClientTessState tess = new MiaoVisonLiteRenderScope.ClientTessState(
            Tessellator.instance);
        MiaoVisonLiteRenderScope saved = MiaoVisonLiteRenderScope.enter();
        try (HologramPreviewAssets.Scope ignored = viewport.assets()
            .enter()) {
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            MiaoVisonLiteTarget framebuffer = viewport.target(SIZE, SIZE);
            framebuffer.bind(true);
            if (!framebuffer.complete()) throw new IOException("Native preview framebuffer is incomplete");
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
            request.requireCurrent();
            mark(request.saveTrace, Stage.NATIVE_SUBMIT_BEGIN);
            viewport
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
            mark(request.saveTrace, Stage.NATIVE_SUBMIT_CHECKED);
            request.requireCurrent();
            ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
            mark(request.saveTrace, Stage.GL_READBACK_BEGIN);
            framebuffer.readRgba(saved, pixels);
            if (witness) {
                int error = GL11.glGetError();
                if (error != GL11.GL_NO_ERROR) throw new IOException("Native preview readback GL error: " + error);
            }
            request.readbackSucceeded = true;
            mark(request.saveTrace, Stage.GL_READBACK_DONE);
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
            try {
                viewport.close();
            } finally {
                try {
                    saved.close();
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

    /** Registry resolution and audited AE visual factories run only in the client render context. */
    private static HologramState completeSnapshot(NBTTagCompound data) throws IOException {
        boolean showMeOutputCapacity = showMeOutputCapacity(data);
        NBTTagList cells = data.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            completeCell(cell, showMeOutputCapacity);
        }
        return new HologramState(data);
    }

    /** Browse snapshots filter on projection; editable observations survive metadata-only policy changes. */
    static boolean showMeOutputCapacity(NBTTagCompound data) {
        if (localEditor(data)) return true;
        if (data.getBoolean("localMaterialPolicyPresent") && !data.getBoolean("retainNbt")) return false;
        return !data.hasKey("retainedNbtKeys") || includesMeOutputCapacity(data.getTagList("retainedNbtKeys", 8));
    }

    /** Only the local editor's target caption uses its live, typed authoring policy. */
    static boolean editorAllowsMeOutputCapacity(NBTTagCompound data) {
        return !localEditor(data) || data.hasKey("blueprintRetainNbt", 1) && data.getBoolean("blueprintRetainNbt")
            && data.hasKey("blueprintNbtAll", 1)
            && (data.getBoolean("blueprintNbtAll") || includesMeOutputCapacity(data.getTagList("blueprintNbtKeys", 8)));
    }

    private static boolean localEditor(NBTTagCompound data) {
        return data.hasKey("localBlueprintDocument", 1) && data.getBoolean("localBlueprintDocument");
    }

    private static boolean includesMeOutputCapacity(NBTTagList retained) {
        for (int i = 0; i < retained.tagCount(); i++) {
            if ("baseCapacity".equals(retained.getStringTagAt(i))) return true;
        }
        return false;
    }

    static void completeCell(NBTTagCompound cell) throws IOException {
        completeCell(cell, true);
    }

    /** Resolve one disposable display row on the client thread, without world or payment validation. */
    static void completeCell(NBTTagCompound cell, boolean showMeOutputCapacity) throws IOException {
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
            com.miaokatze.gtmb.hologram.HologramBlueprintNbt.resolvePreviewIdentities(itemTag);
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
        if (tile != null) com.miaokatze.gtmb.hologram.HologramBlueprintNbt.resolvePreviewIdentities(tile);
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
        if (showMeOutputCapacity && (!cell.hasKey("previewItemTag") || cell.hasKey("previewItemTag", 10)))
            HologramGtMeOutputDisplay.observeStored(block, stack, tile, display);
        if (stack != null) cell.setTag("targetStack", stack.writeToNBT(new NBTTagCompound()));
        cell.setTag("targetDisplay", display);
        cell.setString("id", cell.getString("wantId"));
        cell.setInteger("meta", cell.getInteger("wantMeta"));
        cell.removeTag("previewTile");
        cell.removeTag("previewItemTag");
        cell.removeTag("previewItem");
        cell.removeTag("previewDamage");
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
            account(expanded.get(encoded));
            return decoded.get(encoded);
        }

        void account(long bytes) throws IOException {
            total += bytes;
            if (total > 64L * 1024 * 1024) throw new IOException("Blueprint preview total NBT byte budget");
        }
    }

}
