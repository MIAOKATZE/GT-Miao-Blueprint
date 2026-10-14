package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.Tags;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramLibraryFacade;
import com.miaokatze.gtmb.hologram.HologramV011Evidence;

import cpw.mods.fml.common.Loader;

/** Seven actual native world-source skins retained in the normal Angelica framebuffer PNG. */
final class HologramV011NormalDecorProbe {

    private static NBTTagCompound targetReceipt;

    private HologramV011NormalDecorProbe() {}

    /** Observe the actual completed author transaction before the fixture discards any state or reloads disk. */
    static NBTTagCompound verifySavedArtifact(HologramState state, Path output, int expectedCells, String name)
        throws Exception {
        require(Boolean.getBoolean("gtmb.hologram.previewCaptureWitness"), "native capture witness enabled");
        require(
            state.data.getBoolean("blueprintSaved") && state.data.getBoolean("blueprintOperationSuccess")
                && !state.data.getBoolean("blueprintDirty")
                && !state.data.getBoolean("blueprintSavePending")
                && !HologramLibraryClient.INSTANCE.busy(),
            "successful final owner ACK precedes normal artifact proof");
        if (Boolean.getBoolean("gtmb.hologram.followupNormalDecor"))
            require(expectedCells == 60 && state.cells.size() == 60, "complete sixty-cell normal world source");
        NBTTagCompound transaction = HologramLibraryClient.actualSaveWitness();
        require(transaction != null, "actual library save transaction witness exists");
        NBTTagCompound verified = transaction.getCompoundTag("previewVerifiedReply");
        NBTTagCompound ack = transaction.getCompoundTag("ownerDiskAck");
        NBTTagCompound outcome = transaction.getCompoundTag("saveCommittedReply");
        String localId = ack.getString("localId");
        require(localId.matches("local_[a-fA-F0-9-]{36}"), "actual pair has bounded native local ID");
        Path jsonPath = Minecraft.getMinecraft().mcDataDir.toPath()
            .resolve("MiaoBlueprints")
            .resolve(localId + ".json");
        Path pngPath = HologramBlueprintArtifact.previewPath(jsonPath);
        HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact.readPair(jsonPath);
        HologramLibraryFacade.validateNativeJson(artifact.json);
        JsonObject root = new JsonParser().parse(new String(artifact.json, StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonObject blueprint = root.getAsJsonObject("blueprint");
        require(blueprint != null, "actual saved native JSON carries blueprint");
        String presetId = string(blueprint, "id");
        require(
            (localId.equals(presetId) || localId.equals("local_" + presetId)) && name.equals(string(blueprint, "name"))
                && blueprint.getAsJsonArray("cells")
                    .size() == expectedCells,
            "actual saved JSON agrees with original author name, identity and source cell count");
        require(
            blueprint.get("retainNbt")
                .isJsonPrimitive()
                && blueprint.get("retainNbt")
                    .getAsJsonPrimitive()
                    .isBoolean()
                && blueprint.get("retainNbt")
                    .getAsBoolean()
                && blueprint.get("retainCovers")
                    .isJsonPrimitive()
                && blueprint.get("retainCovers")
                    .getAsJsonPrimitive()
                    .isBoolean()
                && blueprint.get("retainCovers")
                    .getAsBoolean()
                && (!blueprint.has("retainedNbtKeys") || blueprint.get("retainedNbtKeys")
                    .isJsonNull()),
            "actual saved pair preserves reviewed complete NBT and covers");
        String actor = Minecraft.getMinecraft().thePlayer.getUniqueID()
            .toString();
        require(actor.equals(string(blueprint, "authorUuid")), "actual pair records native author UUID");
        require(
            blueprint.has("nativeBlueprint") && blueprint.get("nativeBlueprint")
                .isJsonObject(),
            "actual saved JSON has native provenance");
        JsonObject metadata = blueprint.getAsJsonObject("nativeBlueprint");
        JsonElement version = metadata.get("formatVersion");
        require(
            version != null && version.isJsonPrimitive()
                && version.getAsJsonPrimitive()
                    .isNumber()
                && "1".equals(version.getAsString()),
            "actual saved native formatVersion is numeric one");
        String pack = (String) Class.forName("com.dreammaster.coremod.DreamCoreMod")
            .getField("displayedModpackVersion")
            .get(null);
        require(
            "0.1.0".equals(Tags.RELEASE_VERSION) && Tags.RELEASE_VERSION.equals(string(metadata, "gtmbVersion")),
            "normal candidate records stable GTMB release 0.1.0");
        require(
            "2.9.0-RC-2".equals(pack) && pack.equals(string(metadata, "gtnhVersion"))
                && "dreamcoremod.displayedModpackVersion".equals(string(metadata, "gtnhVersionSource")),
            "normal candidate records exact runtime DreamCore pack version");
        NBTTagCompound nativeMetadata = new NBTTagCompound();
        nativeMetadata.setInteger("formatVersion", 1);
        for (String key : new String[] { "gtmbVersion", "gtnhVersion", "gtnhVersionSource" })
            nativeMetadata.setString(key, string(metadata, key));
        require("previewVerified".equals(verified.getString("op")), "actual server previewVerified packet");
        require("ackBlueprintSaved".equals(ack.getString("op")), "actual pair owner disk acknowledgement packet");
        require("saveCommitted".equals(outcome.getString("op")), "actual final server save commit packet");
        require(
            !ack.getString("saveReceipt")
                .isEmpty(),
            "actual save transaction nonce exists");
        for (NBTTagCompound packet : new NBTTagCompound[] { verified, ack, outcome }) {
            require(
                state.session.equals(packet.getString("session"))
                    && state.data.getLong("planRevision") == packet.getLong("planRevision")
                    && ack.getString("saveReceipt")
                        .equals(packet.getString("saveReceipt"))
                    && localId.equals(packet.getString("localId"))
                    && artifact.jsonSha256.equals(packet.getString("jsonSha256"))
                    && artifact.pngSha256.equals(packet.getString("previewSha256"))
                    && packet.getInteger("artifactVersion") == HologramBlueprintArtifact.VERSION,
                "native save packets bind exact disk pair, nonce, session and revision");
        }
        NBTTagCompound nativeWitness = transaction.getCompoundTag("nativePreviewWitness");
        require(
            nativeWitness != null && nativeWitness.getBoolean("renderTickEnd")
                && nativeWitness.getBoolean("readbackSucceeded")
                && nativeWitness.getBoolean("nativeRendering")
                && "RGB8_TOP_LEFT_ROW_MAJOR".equals(nativeWitness.getString("rgbPacking"))
                && nativeWitness.getLong("captureGeneration") > 0
                && nativeWitness.getInteger("width") == 640
                && nativeWitness.getInteger("height") == 640
                && artifact.jsonSha256.equals(nativeWitness.getString("sourceJsonSha256")),
            "actual native 640-square END readback binds the exact saved JSON");
        require(
            nativeWitness.equals(HologramBlueprintPreview.lastCaptureWitness()),
            "transaction native witness is the actual last successful readback");
        BufferedImage image = ImageIO.read(pngPath.toFile());
        require(
            image != null && image.getWidth() == 640 && image.getHeight() == 640,
            "actual same-basename PNG decodes at 640 by 640");
        MessageDigest rgb = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int pixel = image.getRGB(x, y);
            rgb.update((byte) (pixel >>> 16));
            rgb.update((byte) (pixel >>> 8));
            rgb.update((byte) pixel);
        }
        require(
            hex(rgb.digest()).equals(nativeWitness.getString("rgbSha256")),
            "saved PNG pixels equal the actual native END readback");
        Files.copy(jsonPath, output.resolve("normal-native-blueprint.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pngPath, output.resolve("normal-native-blueprint.png"), StandardCopyOption.REPLACE_EXISTING);
        Path sample = output.resolve("native-sample");
        Files.createDirectories(sample);
        Files.copy(jsonPath, sample.resolve(jsonPath.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pngPath, sample.resolve(pngPath.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("passed", true);
        receipt.setBoolean("actualNettyClient", true);
        receipt.setBoolean("nativeAuthorSave", true);
        receipt.setBoolean("syntheticSnapshotsUsed", false);
        receipt.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
        receipt.setString("localId", localId);
        receipt.setString("savedPresetId", presetId);
        receipt.setString("savedName", name);
        receipt.setString("actor", actor);
        receipt.setString("session", state.session);
        receipt.setLong("planRevision", state.data.getLong("planRevision"));
        receipt.setString("saveReceipt", ack.getString("saveReceipt"));
        receipt.setString(
            "actualPath",
            jsonPath.toAbsolutePath()
                .toString());
        receipt.setString(
            "actualPngPath",
            pngPath.toAbsolutePath()
                .toString());
        receipt.setString("actualFileSha256", artifact.jsonSha256);
        receipt.setString("actualPngSha256", artifact.pngSha256);
        receipt.setString("sourceJsonSha256", artifact.jsonSha256);
        receipt.setInteger("actualJsonBytes", artifact.json.length);
        receipt.setInteger("actualPngBytes", artifact.png.length);
        receipt.setInteger("previewWidth", artifact.width);
        receipt.setInteger("previewHeight", artifact.height);
        receipt.setInteger("expectedSourceCells", expectedCells);
        receipt.setString("nativeJson", "normal-native-blueprint.json");
        receipt.setString("nativePng", "normal-native-blueprint.png");
        receipt.setString("sampleJson", "native-sample/" + jsonPath.getFileName());
        receipt.setString("samplePng", "native-sample/" + pngPath.getFileName());
        receipt.setTag("actualNativeBlueprint", nativeMetadata);
        receipt.setTag("actualNativePreviewWitness", nativeWitness.copy());
        receipt.setTag("actualSaveWitness", transaction.copy());
        receipt.setTag("previewVerifiedReply", verified.copy());
        receipt.setTag("ownerDiskAck", ack.copy());
        receipt.setTag("finalSaveOutcome", outcome.copy());
        receipt.setTag("actualSnapshot", state.data.copy());
        return receipt;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        require(
            value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive()
                    .isString(),
            "native JSON string field " + key);
        return value.getAsString();
    }

    static void verify(HologramState state, boolean current, File png) throws Exception {
        if (!Boolean.getBoolean("gtmb.hologram.followupNormalDecor")) return;
        require(Loader.isModLoaded("angelica"), "normal full-mod Angelica must actually be loaded");
        Object frame = field(HologramNativeRasterSmoke.class, "last");
        require(
            frame != null && field(frame, "state") == state && (Boolean) field(frame, "current") == current,
            "native pixel statistics bind this actual displayed END screen state");
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) field(frame, "stats");
        BufferedImage image = ImageIO.read(png);
        require(image != null, "normal actual framebuffer PNG decodes");
        int[] shapes = { 0, 12, 28, 41, 45, 60, 64 };
        Set<Integer> required = new HashSet<>();
        for (int shape : shapes) required.add(shape);
        NBTTagList rows = new NBTTagList();
        for (HologramState.Cell cell : state.cells) {
            NBTTagCompound tile = (current ? cell.actualDisplay : cell.targetDisplay).getCompoundTag("nativeTile");
            int shape = tile.getInteger("cbMetadata");
            // The two original wedge controls are separate from the seven new source cells inside the floor area.
            if (!required.contains(shape) || cell.dz + 8 < 11
                || !cell.block(current)
                    .getClass()
                    .getName()
                    .equals("com.carpentersblocks.block.BlockCarpentersSlope"))
                continue;
            String key = (current ? cell.id : cell.wantId) + "@" + cell.index;
            Object pixels = stats.get(key);
            require(pixels != null, "every complex real source slope was actually drawn: " + shape);
            int area = (Integer) field(pixels, "area"), changed = (Integer) field(pixels, "changed");
            int colors = (Integer) field(pixels, "colors");
            require(
                area > 0 && changed >= Math.max(12, area / 20) && colors >= 3,
                "real complex slope material is visibly rasterized: " + shape + " pixels=" + changed + "/" + area);
            int x = (Integer) field(pixels, "x"), y = (Integer) field(pixels, "y"),
                width = (Integer) field(pixels, "width");
            int[] samples = (int[]) field(pixels, "samples");
            int retained = 0;
            Set<Integer> materialColors = new HashSet<>();
            for (int pixel = 0; pixel < samples.length; pixel++) {
                if (samples[pixel] == 0) continue;
                int sx = x + pixel % width, sy = image.getHeight() - 1 - (y + pixel / width);
                require(
                    sx >= 0 && sy >= 0 && sx < image.getWidth() && sy < image.getHeight(),
                    "normal pixel maps inside saved PNG");
                int expected = samples[pixel] - 1, actual = image.getRGB(sx, sy) & 0xffffff;
                int difference = Math.abs((expected & 255) - (actual & 255))
                    + Math.abs((expected >> 8 & 255) - (actual >> 8 & 255))
                    + Math.abs((expected >> 16 & 255) - (actual >> 16 & 255));
                if (difference <= 9) {
                    retained++;
                    materialColors.add(actual);
                }
            }
            require(
                retained >= Math.max(5, changed / 4) && materialColors.size() >= 3,
                "real slope skin persists in final normal PNG: " + shape + " retained=" + retained + "/" + changed);
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("shape", shape);
            row.setInteger("cell", cell.index);
            row.setTag("nativeTile", tile.copy());
            row.setInteger("rasterArea", area);
            row.setInteger("rasterChangedPixels", changed);
            row.setInteger("rasterColors", colors);
            row.setInteger("retainedMaterialPixels", retained);
            row.setInteger("retainedMaterialColors", materialColors.size());
            rows.appendTag(row);
            required.remove(shape);
        }
        require(
            required.isEmpty() && rows.tagCount() == 7,
            "all seven real complex slope source cells have material pixels");
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("passed", true);
        receipt.setBoolean("angelicaLoaded", true);
        receipt.setBoolean("current", current);
        receipt.setInteger("nativeSourceCells", 7);
        receipt.setTag("actualWorldSourceSlopes", rows);
        receipt.setString("capturePhase", "RenderTick.END");
        receipt.setString("session", state.session);
        receipt.setLong("planRevision", state.data.getLong("planRevision"));
        receipt.setInteger("stateObjectIdentity", System.identityHashCode(state));
        receipt.setString("png", png.getName());
        receipt.setString(
            "pngSha256",
            hex(
                MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(png.toPath()))));
        receipt.setString(
            "nativeControlVerification",
            HologramDecorationPreviewSmoke
                .verify(new HologramState(HologramDecorationPreviewSmoke.fixture()), current));
        HologramV011Evidence.write("followup-normal-decor-" + (current ? "current" : "target") + ".nbt", receipt);
        if (!current) targetReceipt = receipt;
        else {
            require(targetReceipt != null, "normal target witness precedes current witness");
            NBTTagCompound combined = new NBTTagCompound();
            combined.setBoolean("passed", true);
            combined.setBoolean("angelicaLoaded", true);
            combined.setIntArray("nativeControlKinds", shapes);
            combined.setTag("target", targetReceipt.copy());
            combined.setTag("current", receipt.copy());
            combined.setString("targetControlVerification", targetReceipt.getString("nativeControlVerification"));
            combined.setString("currentControlVerification", receipt.getString("nativeControlVerification"));
            HologramV011Evidence.write("followup-normal-decoration.nbt", combined);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field value = (owner instanceof Class ? (Class<?>) owner : owner.getClass()).getDeclaredField(name);
        value.setAccessible(true);
        return value.get(owner instanceof Class ? null : owner);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }
}
