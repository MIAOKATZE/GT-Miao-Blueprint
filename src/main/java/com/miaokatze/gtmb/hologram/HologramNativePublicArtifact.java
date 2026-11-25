// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPng;

import net.minecraft.nbt.NBTTagCompound;

/** The public-library pair, not a construction upload. Existing library transfer limits still apply. */
public final class HologramNativePublicArtifact {

    public static final int VERSION = 2;
    private static final int MAGIC = 0x47544E50; // GTNP
    private static final int HEADER = 12;
    private static final Gson GSON = new Gson();

    @Desugar
    public record Pair(byte[] source, byte[] cover) {
        public String revision() { return HologramBlueprintArtifact.hash(source); }
    }

    @Desugar
    record Description(String name, String author, String version, int cells, int structures) {}

    private HologramNativePublicArtifact() {}

    public static boolean supported(int version) {
        return version == HologramBlueprintArtifact.VERSION || version == VERSION;
    }

    public static byte[] encode(byte[] source, byte[] cover) throws IOException {
        lengths(source.length, cover.length);
        return ByteBuffer.allocate(HEADER + source.length + cover.length)
            .putInt(MAGIC).putInt(source.length).putInt(cover.length).put(source).put(cover).array();
    }

    public static byte[] read(NativeBlueprintPairStore.Receipt receipt) throws IOException {
        byte[] source = NativeBlueprintPairStore.readRegular(receipt.source, HologramBlueprintArtifact.MAX_JSON_BYTES);
        byte[] cover = NativeBlueprintPairStore.readRegular(receipt.png, HologramBlueprintArtifact.MAX_PNG_BYTES);
        need(receipt.physicalHash.equals(HologramBlueprintArtifact.hash(source))
            && receipt.pngHash.equals(HologramBlueprintArtifact.hash(cover)), "The selected saved blueprint changed");
        return encode(source, cover);
    }

    public static Pair decode(byte[] bytes) throws IOException {
        need(bytes.length >= HEADER && bytes.length <= HologramLibraryProtocol.MAX_BYTES, "Public native pair length");
        ByteBuffer stream = ByteBuffer.wrap(bytes);
        need(stream.getInt() == MAGIC, "Public native artifact format");
        int sourceSize = stream.getInt(), pngSize = stream.getInt();
        lengths(sourceSize, pngSize);
        need((long) sourceSize + pngSize == stream.remaining(), "Public native artifact length");
        return new Pair(Arrays.copyOfRange(bytes, HEADER, HEADER + sourceSize),
            Arrays.copyOfRange(bytes, HEADER + sourceSize, bytes.length));
    }

    private static void lengths(int source, int cover) throws IOException {
        need(source > 0 && source <= HologramBlueprintArtifact.MAX_JSON_BYTES
            && cover > 0 && cover <= HologramBlueprintArtifact.MAX_PNG_BYTES
            && HEADER + (long) source + cover <= HologramLibraryProtocol.MAX_BYTES, "Public native file exceeds library budget");
    }

    /**
     * Admit only public-safe fixed points of the installed sanitizers. Pure GTNB data stays in GTNB;
     * only the distinct, typed payload descriptors are presented to existing policy. A transform
     * that cannot be preserved here (e.g. a private AE link), or an unknown schema, is a refusal,
     * not permission to publish the original or drop part of it.
     */
    static Description audit(Pair pair, Path staging) throws IOException {
        NativeBlueprintPng.verify(pair.cover, pair.revision());
        NativeBlueprintPng.validatePreview(pair.cover);
        java.nio.file.Files.write(staging, pair.source);
        try (NativeBlueprintDefinition document = NativeBlueprintDefinition.open(staging)) {
            need(document.allowsGeometryMutation(), "Unsupported native source semantics for public sharing");
            JsonObject meta = document.metadata();
            if (meta.has("migration")) {
                JsonObject migration = meta.getAsJsonObject("migration");
                for (var entry : migration.entrySet()) {
                    String key = entry.getKey();
                    JsonElement value = migration.get(key);
                    need(switch (key) {
                        case "format" -> "gtmb-dictionary-1".equals(value.getAsString())
                            || "expanded-json".equals(value.getAsString());
                        case "sourceSha256", "legacyContentIdentity" -> value.isJsonNull()
                            || value.getAsString().matches("[a-f0-9]{64}");
                        case "unknownSemantics" -> value.isJsonPrimitive()
                            && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean();
                        default -> false;
                    }, "Unrecognized native migration metadata");
                }
            }
            JsonObject region = plainSection(document, 8);
            JsonObject header = plainSection(document, 9);
            JsonObject fields = header == null ? new JsonObject() : header.getAsJsonObject("document");
            if (header != null) auditLegacyEnvelope(header.getAsJsonObject("legacyEnvelope"));
            for (String key : new String[] {"processingNbt", "semanticNbt", "quantumGroupingNbt", "aeIssues"}) {
                JsonElement value = fields.get(key);
                need(value == null || value.isJsonNull() || value.isJsonArray() && value.getAsJsonArray().size() == 0
                    || value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().isEmpty(),
                    "Unresolved or private native blueprint data: " + key);
            }
            for (long descriptor : document.spatial().counts().keySet()) {
                JsonObject extra = document.spatial().wrapper(descriptor);
                int tile = document.payloadRef(extra, "tileNbt"), item = document.payloadRef(extra, "itemNbt");
                if (tile == 0 && item == 0) {
                    // Even a bare registered AE/quantum shell may require canonical public state.
                    HologramPresets.Cell cell = GSON.fromJson(document.spatial().fields(descriptor), HologramPresets.Cell.class);
                    if (cell.block() != null
                        && (HologramAeBlueprint.supports(cell.block()) || HologramNetworkBindings.node(cell.block())))
                        auditCell(cell);
                    continue;
                }
                JsonObject full = document.spatial().fields(descriptor);
                for (var entry : document.restoredFields(extra).entrySet()) full.add(entry.getKey(), entry.getValue());
                auditCell(GSON.fromJson(full, HologramPresets.Cell.class));
            }
            JsonObject identity = meta.getAsJsonObject("fields");
            String name = string(identity, "name");
            String author = string(identity, "authorName");
            String authorId = string(identity, "authorUuid");
            need(name.codePoints().anyMatch(cp -> !Character.isWhitespace(cp)) && name.length() <= 128 && author.length() <= 64
                && (authorId.isEmpty() || UUID.fromString(authorId).toString().equals(authorId)),
                "Missing or invalid native author metadata");
            var stamp = fields.get("nativeBlueprint");
            String version = stamp == null || stamp.isJsonNull()
                ? "" : string(stamp.getAsJsonObject(), "gtmbVersion");
            need(version.length() <= 64, "Invalid native blueprint version metadata");
            return new Description(name, author, version, (int) document.cells(),
                region == null || !region.has("structures") || region.get("structures").isJsonNull()
                    ? 0 : region.getAsJsonArray("structures").size());
        } catch (RuntimeException | LinkageError invalid) {
            throw new IOException("Invalid or unsupported public native blueprint; check the installed payload adapter", invalid);
        }
    }

    private static void auditLegacyEnvelope(JsonObject legacy) throws IOException {
        for (var entry : legacy.entrySet()) {
            JsonElement value = entry.getValue();
            need(switch (entry.getKey()) {
                case "shape" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && Arrays.asList("direct", "blueprint", "collection-member").contains(value.getAsString());
                case "schema" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.toString().matches("[1-9][0-9]{0,2}");
                case "collectionOrdinal" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.toString().matches("0|[1-9][0-9]{0,8}");
                case "encoding" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && "gtmb-dictionary-1".equals(value.getAsString());
                case "previewBinding" -> value.isJsonObject() && value.getAsJsonObject().entrySet().size() == 2
                    && "1".equals(value.getAsJsonObject().get("version").toString())
                    && string(value.getAsJsonObject(), "pngSha256").matches("[a-f0-9]{64}");
                default -> false;
            }, "Legacy private envelope must not be published");
        }
    }

    private static JsonObject plainSection(NativeBlueprintDefinition document, int section) throws IOException {
        JsonObject wrapper = document.sectionWrapper(section);
        if (wrapper == null) return null;
        need(!wrapper.has("nbt"), "Private or unsupported document/attachment payload cannot be published");
        return wrapper.getAsJsonObject("fields");
    }

    private static void auditCell(HologramPresets.Cell cell) throws IOException {
        NBTTagCompound tile = HologramBlueprintNbt.decode(cell.tileNbt);
        NBTTagCompound item = HologramBlueprintNbt.decode(cell.itemNbt);
        HologramServerLibrary.rejectDropPayload(tile);
        HologramServerLibrary.rejectDropPayload(item);
        need(cell.block() != null && !cell.missing(), "Register the native payload host before publishing");
        if (tile != null || item != null) HologramExecutionEligibility.validate(cell.block(), cell.metadata,
            cell.stack(), cell.facing, cell.controller, tile, item);
        String carrier = cell.item;
        int damage = cell.damage;
        boolean excluded = cell.excluded;
        HologramPresets.Preset specimen = new HologramPresets.Preset();
        specimen.cells.add(cell);
        HologramAePolicy.Draft audit = HologramAePolicy.sanitizeOwned(specimen, Collections.emptyMap(),
            Collections.emptyMap(), HologramAePolicy.PreparationProgress.NONE);
        HologramPresets.Cell safe = audit.preset.cells.get(0);
        need(!audit.blocking && Objects.equals(carrier, safe.item) && damage == safe.damage
            && excluded == safe.excluded && Objects.equals(tile, HologramBlueprintNbt.decode(safe.tileNbt))
            && Objects.equals(item, HologramBlueprintNbt.decode(safe.itemNbt)),
            "Public native payload requires sanitization; repair it before publishing");
    }

    static String string(JsonObject from, String key) {
        JsonElement value = from.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    static void need(boolean condition, String reason) throws IOException {
        if (!condition) throw new IOException(reason);
    }
}
