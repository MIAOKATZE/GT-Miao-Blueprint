// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntityChest;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramBlockIdentity;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramLibraryFacade;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;
import com.miaokatze.miaokatzecore.client.preview.PreviewRenderScope;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Paired RAW compatibility and native blueprint image evidence after the real tutorial restored its menu. */
public final class HologramRawExportParityProbe {

    private static final String RAW_PNG = "screenshots/gtmb-raw-parity.png";
    private static final String EXPORT_PNG = "screenshots/gtmb-export-parity.png";
    private static final String[] BLOCKS = { "minecraft:stonebrick", "minecraft:stained_glass", "minecraft:oak_stairs",
        "minecraft:glowstone" };
    private static final int[][] CELLS = { { 0, 0, 0, 0 }, { 1, 1, 0, 14 }, { 2, 0, 1, 2 }, { 3, 0, 0, 0 } };
    private final Path output;
    private final GuiScreen menu = Minecraft.getMinecraft().currentScreen;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final long started = System.nanoTime();
    private HologramCutRawPreviewSession raw;
    private HologramBlueprintPreview.Request request;
    private long staticAttempts;
    private int draws;
    private boolean finished;

    private HologramRawExportParityProbe(Path output) {
        this.output = output;
        result.put("schema", "gtmb-raw-export-parity-v1");
        result.put(
            "artifact",
            ArtifactIdentity.capture(HologramRawExportParityProbe.class)
                .asMap());
    }

    static boolean install(Path output) {
        if (!Boolean.getBoolean("gtmb.testlab.rawExportParity")) return false;
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramRawExportParityProbe(output));
        return true;
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            require(System.nanoTime() - started < 30_000_000_000L, "RAW/export probe timed out");
            require(mc.currentScreen == menu && mc.theWorld == null && mc.thePlayer == null, "Menu ownership changed");
            if (request != null) return;
            if (raw == null) {
                require(Boolean.getBoolean("gtmb.hologram.previewCaptureWitness"), "Native export witness required");
                require(Boolean.getBoolean("gtmb.hologram.cutRenderStateWitness"), "RAW state witness required");
                raw = new HologramCutRawPreviewSession();
                raw.update(rawFixture());
            }
            BufferedImage image = renderRaw(mc);
            NBTTagCompound evidence = raw.inspection();
            require(evidence.getLong("ghostComposites") > 0, "RAW preview did not composite");
            require(evidence.getLong("ghostDepthCopies") > 0, "RAW preview did not copy destination depth");
            require(
                evidence.getLong("ghostStateChecks") > 0 && evidence.getLong("ghostStateMismatches") == 0,
                "RAW preview leaked caller state");
            require(
                evidence.getInteger("failureCount") == 0,
                raw.diagnostics()
                    .toString());
            require(evidence.getLong("tesrRendered") > 0, "Native chest TESR did not render");
            require(evidence.getInteger("displayLists") >= BLOCKS.length, "Static RAW meshes did not render");
            if (++draws == 1) {
                staticAttempts = evidence.getLong("staticAttempts");
                return;
            }
            require(
                evidence.getLong("staticAttempts") == staticAttempts,
                "RAW replay recompiled resident static meshes");
            Map<String, Object> rawEvidence = new LinkedHashMap<>();
            rawEvidence.put("rawEntrySha256", evidence.getString("rawEntrySha256"));
            for (String key : new String[] { "frames", "nonAirCells", "displayLists", "staticAttempts",
                "staticRendered", "tesrRendered", "ghostComposites", "ghostDepthCopies", "ghostStateChecks",
                "ghostStateMismatches", "failureCount" }) rawEvidence.put(key, evidence.getLong(key));
            result.put("raw", rawEvidence);
            writeImage("raw", RAW_PNG, image);
            raw.release();
            raw = null;
            byte[] json = exportFixture();
            HologramLibraryFacade.validateNativeJson(json);
            String source = HologramBlueprintArtifact.hash(json);
            result.put("sourceJsonSha256", source);
            NBTTagCompound snapshot = HologramBlueprintPreview.snapshot(json);
            request = HologramBlueprintPreview.capture(
                snapshot,
                () -> !finished && mc.currentScreen == menu,
                imageExport -> exportComplete(source, imageExport),
                this::failed);
        } catch (Exception | LinkageError failure) {
            failed(failure);
        }
    }

    private BufferedImage renderRaw(Minecraft mc) throws IOException {
        require(OpenGlHelper.isFramebufferEnabled(), "Raw framebuffer not available");
        int width = mc.displayWidth, height = mc.displayHeight;
        PreviewRenderScope.RenderState scope = new PreviewRenderScope.RenderState();
        Framebuffer target = null;
        scope.push();
        try {
            for (int errors = 0; GL11.glGetError() != GL11.GL_NO_ERROR; errors++)
                require(errors < 16, "Inherited GL error could not be cleared");
            target = new Framebuffer(width, height, true);
            target.bindFramebuffer(true);
            require(
                OpenGlHelper.func_153167_i(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                "Raw destination is incomplete");
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glClearDepth(1);
            GL11.glClearColor(.075f, .10f, .12f, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            double extent = 4.0, aspect = (double) width / height;
            GL11.glOrtho(-extent * aspect, extent * aspect, -extent, extent, -32, 32);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glRotatef(24, 1, 0, 0);
            GL11.glRotatef(-35, 0, 1, 0);
            GL11.glTranslated(-2, -1, -1);
            raw.renderLocal(0);
            scope.prepareReadback();
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "RAW render or readback generated a GL error");
            int[] colors = new int[width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                colors[(height - y - 1) * width + x] = ((pixels.get(offset) & 255) << 16)
                    | ((pixels.get(offset + 1) & 255) << 8)
                    | (pixels.get(offset + 2) & 255);
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, width, height, colors, 0, width);
            return image;
        } finally {
            try {
                if (target != null) target.deleteFramebuffer();
            } finally {
                scope.restore();
            }
        }
    }

    private void exportComplete(String source, BufferedImage image) {
        try {
            NBTTagCompound evidence = HologramBlueprintPreview.lastCaptureWitness();
            require(
                evidence.getBoolean("nativeRendering") && evidence.getBoolean("renderTickEnd")
                    && evidence.getBoolean("readbackSucceeded"),
                "Export did not complete native readback");
            require(source.equals(evidence.getString("sourceJsonSha256")), "Export consumed different share bytes");
            String hash = writeImage("export", EXPORT_PNG, image);
            require(hash.equals(evidence.getString("rgbSha256")), "PNG pixels differ from production readback");
            Map<String, Object> exportEvidence = new LinkedHashMap<>();
            exportEvidence.put("sourceJsonSha256", source);
            exportEvidence.put("rgbSha256", hash);
            exportEvidence.put("width", evidence.getInteger("width"));
            exportEvidence.put("height", evidence.getInteger("height"));
            exportEvidence.put("cameraYaw", evidence.getDouble("cameraYaw"));
            exportEvidence.put("cameraPitch", evidence.getDouble("cameraPitch"));
            exportEvidence.put("nativeRendering", evidence.getBoolean("nativeRendering"));
            exportEvidence.put("renderTickEnd", evidence.getBoolean("renderTickEnd"));
            exportEvidence.put("readbackSucceeded", evidence.getBoolean("readbackSucceeded"));
            result.put("export", exportEvidence);
            finish(null);
        } catch (Exception | LinkageError failure) {
            failed(failure);
        }
    }

    private String writeImage(String kind, String relative, BufferedImage image) throws IOException {
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] rgb = new byte[pixels.length * 3];
        int changed = 0;
        for (int index = 0; index < pixels.length; index++) {
            int value = pixels[index];
            if (value != pixels[0]) changed++;
            rgb[index * 3] = (byte) (value >>> 16);
            rgb[index * 3 + 1] = (byte) (value >>> 8);
            rgb[index * 3 + 2] = (byte) value;
        }
        require(changed > 500, kind + " framebuffer contains no usable structure pixels");
        Path path = output.resolve(relative);
        Files.createDirectories(path.getParent());
        require(ImageIO.write(image, "png", path.toFile()), kind + " PNG encoder unavailable");
        String hash = HologramBlueprintArtifact.hash(rgb);
        result.put(kind + "Png", relative);
        result.put(kind + "RgbSha256", hash);
        result.put(kind + "ChangedPixels", changed);
        result.put(kind + "Dimensions", new int[] { width, height });
        return hash;
    }

    private static NBTTagCompound rawFixture() {
        NBTTagCompound entry = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < BLOCKS.length; i++) rows.appendTag(rawCell(BLOCKS[i], CELLS[i]));
        NBTTagCompound chest = rawCell("minecraft:chest", new int[] { 2, 0, 0, 3 });
        NBTTagCompound tile = new NBTTagCompound();
        new TileEntityChest().writeToNBT(tile);
        chest.setTag("tile", tile);
        rows.appendTag(chest);
        entry.setTag("cells", rows);
        return entry;
    }

    private static NBTTagCompound rawCell(String block, int[] cell) {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("block", block);
        row.setInteger("x", cell[0]);
        row.setInteger("y", cell[1]);
        row.setInteger("z", cell[2]);
        row.setInteger("meta", cell[3]);
        return row;
    }

    private static byte[] exportFixture() throws IOException {
        JsonObject blueprint = new JsonObject();
        blueprint.addProperty("schema", 2);
        blueprint.addProperty("id", "local_33333333-3333-3333-3333-333333333333");
        blueprint.addProperty("name", "RAW Export Parity");
        blueprint.addProperty("controllerType", "area");
        blueprint.addProperty("authorName", "Miao TestLab");
        blueprint.addProperty("authorUuid", "33333333-3333-3333-3333-333333333333");
        blueprint.addProperty("createdAt", 1234);
        JsonObject version = new JsonObject();
        version.addProperty("formatVersion", 1);
        version.addProperty("gtmbVersion", "0.3.39");
        version.addProperty("gtnhVersion", "unknown");
        version.addProperty("gtnhVersionSource", "unavailable");
        blueprint.add("nativeBlueprint", version);
        JsonArray cells = new JsonArray();
        for (int i = 0; i < BLOCKS.length; i++) {
            Block block = Block.getBlockFromName(BLOCKS[i]);
            require(block != null, "Missing export fixture block " + BLOCKS[i]);
            int metadata = CELLS[i][3];
            ItemStack material = HologramBlockIdentity.materialStack(block, block.damageDropped(metadata));
            require(material != null, "Missing export fixture material " + BLOCKS[i]);
            Item item = material.getItem();
            JsonObject row = new JsonObject();
            row.addProperty("item", Item.itemRegistry.getNameForObject(item));
            row.addProperty("block", BLOCKS[i]);
            row.addProperty("count", 1);
            row.addProperty("facing", -1);
            row.addProperty("x", CELLS[i][0]);
            row.addProperty("y", CELLS[i][1]);
            row.addProperty("z", CELLS[i][2]);
            row.addProperty("metadata", metadata);
            row.addProperty("damage", item.getHasSubtypes() ? material.getItemDamage() : 0);
            cells.add(row);
        }
        blueprint.add("cells", cells);
        JsonObject share = new JsonObject();
        share.addProperty("schema", 2);
        share.add("blueprint", blueprint);
        return share.toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    private void failed(Throwable failure) {
        finish(failure.toString());
    }

    private void finish(String failure) {
        if (finished) return;
        finished = true;
        FMLCommonHandler.instance()
            .bus()
            .unregister(this);
        if (request != null) request.cancel();
        try {
            if (raw != null) raw.release();
        } catch (RuntimeException | LinkageError cleanup) {
            failure = failure == null ? cleanup.toString() : failure + " / cleanup: " + cleanup;
        }
        result.put("outcome", failure == null ? "PASS" : "FAIL");
        result.put("failure", failure == null ? "" : failure);
        boolean recorded = false;
        try {
            Files.createDirectories(output);
            Files.write(
                output.resolve("gtmb-raw-export-parity.json"),
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(result)
                    .getBytes(StandardCharsets.UTF_8));
            recorded = true;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot publish RAW/export parity receipt", error);
        } finally {
            String combinedFailure = !recorded ? "Could not publish the RAW/export receipt"
                : failure != null ? "RAW/export probe failed: " + failure : null;
            if (!HologramRenderParityProbe.completeCombined(output, combinedFailure))
                HologramTestLabUiProfile.install();
        }
    }

    private static void require(boolean condition, String detail) throws IOException {
        if (!condition) throw new IOException(detail);
    }
}
