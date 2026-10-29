package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Structural typed-transport preflight only; no native provider or server save execution. */
final class HologramAuthorFormalSaveCompatibilityRegression {

    static void run() throws Exception {
        String known = "{\"schema\":2,\"name\":\"known\",\"fullyVoucherCovered\":true,"
            + "\"modVersions\":{\"future_mod_id\":\"1.2\"},\"retainedNbtKeys\":[\"custom-key\"],"
            + "\"nativeBlueprint\":{\"formatVersion\":1,\"gtmbVersion\":\"0.1.56\","
            + "\"gtnhVersion\":\"unknown\",\"gtnhVersionSource\":\"unknown\"},"
            + "\"cells\":[{\"x\":0,\"block\":\"minecraft:wool\",\"tileNbt\":\"opaque-not-decoded\","
            + "\"authorDeclarationComplete\":true,\"allowedTargets\":[{\"item\":\"minecraft:wool\",\"damage\":0}]}],"
            + "\"structures\":[{\"id\":\"s\",\"channels\":{\"future_channel\":2}}],"
            + "\"hintAreas\":[{\"x1\":0,\"prompt\":\"known\"}],\"palette\":[],\"prompt\":null}";
        accepts(known);
        accepts(
            "{\"schema\":2,\"owner\":\"descriptive\",\"uploadedBy\":\"descriptive\","
                + "\"previewBinding\":{\"version\":1,\"pngSha256\":\"presentation\"},\"blueprint\":"
                + known
                + "}");
        for (String unknown : new String[] {
            known.replace("\"name\":\"known\"", "\"name\":\"known\",\"vendorHeader\":null"),
            known.replace("\"x\":0", "\"x\":0,\"vendorCell\":{\"keep\":true}"),
            known.replace("\"id\":\"s\"", "\"id\":\"s\",\"vendorStructure\":1"),
            known.replace("\"x1\":0", "\"x1\":0,\"vendorHint\":1"),
            known.replace("\"damage\":0", "\"damage\":0,\"vendorTarget\":1"),
            known.replace("\"formatVersion\":1", "\"formatVersion\":1,\"vendorVersion\":1"),
            "{\"blueprint\":" + known + ",\"vendorEnvelope\":{\"preserve\":true}}",
            "{\"blueprint\":" + known + ",\"previewBinding\":{\"version\":1,\"vendorBinding\":true}}" }) {
            AuthorDocument source = AuthorDocumentIO.read(bytes(unknown), AuthorDocumentIO.Limits.defaults());
            JsonObject before = source.sourceCopy();
            try {
                HologramAuthorFormalSaveCompatibility.requireCompatible(source);
                throw new AssertionError("destructive typed save accepted an unknown extension");
            } catch (IOException expected) {
                SimpleAssert.that(
                    expected.getMessage()
                        .contains("未覆盖原成品"),
                    "refusal explains preserved original");
            }
            SimpleAssert.eq(before, source.sourceCopy(), "guard refusal never mutates source history");
            AuthorDocument recovered = AuthorDocumentIO.read(
                AuthorDocumentIO.writeDraft(source, AuthorDocumentIO.Limits.defaults()),
                AuthorDocumentIO.Limits.defaults());
            SimpleAssert.eq(before, recovered.sourceCopy(), "refused extensions remain recoverable losslessly");
        }
    }

    private static void accepts(String json) throws Exception {
        byte[] bytes = bytes(json);
        HologramAuthorFormalSaveCompatibility.requireCompatible(bytes);
        SimpleAssert.eq(json, new String(bytes, StandardCharsets.UTF_8), "preflight keeps exact input bytes");
        JsonObject source = new JsonParser().parse(json)
            .getAsJsonObject();
        HologramAuthorFormalSaveCompatibility
            .requireCompatible(AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults()));
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
