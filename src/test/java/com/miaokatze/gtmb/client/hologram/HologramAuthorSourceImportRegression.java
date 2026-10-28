package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorSourceImporter;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;

/** Exercises the actual local/downloaded/draft author-open boundary, not only the standalone core reader. */
public final class HologramAuthorSourceImportRegression {

    private HologramAuthorSourceImportRegression() {}

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("HologramAuthorSourceImportRegression OK");
    }

    public static void run() throws Exception {
        duplicateFieldsAndGrammar();
        compactFidelityAndOwnership();
        compactLimits();
        draftReopen();
    }

    private static void duplicateFieldsAndGrammar() throws Exception {
        String duplicate = "{\"schema\":2,\"cells\":[{\"x\":0,\"vendorExtension\":{\"first\":1},"
            + "\"vendorExtension\":{\"second\":2}}]}";
        reject(duplicate, "duplicate unknown field at actual author open");
        check(
            HologramBlueprintCompactCodec.parse(bytes(duplicate))
                .getAsJsonArray("cells")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("vendorExtension")
                .has("second"),
            "legacy parser remains compatible");
        reject("{\"schema\":2,\"schema\":2}", "duplicate root field");
        reject("{\"schema\":2,\"extension\":{\"a\":1,\"a\":2}}", "duplicate nested field");
        reject("{\"schema\":2} {}", "trailing root");
        reject("{/* comment */\"schema\":2}", "comment grammar");
        reject("{\"schema\":2,\"extension\":NaN}", "nonfinite number");
        reject("{\"schema\":2,\"extension\":1e2147483648}", "unbounded decimal exponent");
        reject("{\"schema\":2,\"extension\":\"\\uD800\"}", "isolated escaped surrogate");
        try {
            HologramLibraryClient.readAuthorSource(new byte[] { '{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}' });
            throw new AssertionError("malformed UTF-8 accepted");
        } catch (IOException expected) {}
        String numeric = "{\"schema\":2,\"extension\":-0,\"decimal\":1.00}";
        check(
            new JsonParser().parse(numeric)
                .equals(
                    HologramLibraryClient.readAuthorSource(bytes(numeric))
                        .sourceCopy()),
            "numeric source fidelity is semantic, not original token spelling");
    }

    private static void compactFidelityAndOwnership() throws Exception {
        String compact = compact(
            "{\"block\":\"missing:block\",\"tileNbt\":0,\"future\":{\"v\":[null,true,1.00]}}",
            "[0,{\"x\":0}],[0,{\"x\":1}]");
        AuthorDocument imported = HologramLibraryClient.readAuthorSource(bytes(compact));
        JsonObject expanded = HologramBlueprintCompactCodec.parse(bytes(compact));
        check(expanded.equals(imported.sourceCopy()), "compact unknown fields and opaque NBT fidelity");
        check(
            imported.cell(1)
                .tileNbt()
                == imported.cell(2)
                    .tileNbt(),
            "dictionary payload identity remains shared");
        check(
            "not-base64!!+/=".equals(
                imported.cell(1)
                    .tileNbt()),
            "opening never decodes or normalizes native NBT");
        imported.sourceCopy()
            .getAsJsonObject("blueprint")
            .getAsJsonArray("cells")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("future")
            .addProperty("mutated", true);
        check(
            !imported.cell(1)
                .sourceCopy()
                .getAsJsonObject("future")
                .has("mutated"),
            "no mutable owned alias escapes");
        AuthorDocument defensive = AuthorDocumentIO.readExpanded(expanded, AuthorDocumentIO.Limits.defaults());
        expanded.getAsJsonObject("blueprint")
            .getAsJsonArray("cells")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("future")
            .addProperty("mutated", true);
        check(
            !defensive.cell(1)
                .sourceCopy()
                .getAsJsonObject("future")
                .has("mutated"),
            "public tree API stays defensive");
        reject(compact.replace("\"tileNbt\":0", "\"tileNbt\":0,\"tileNbt\":0"), "duplicate compact dictionary field");
        String negativeZero = compact("{}", "[-0,{\"x\":0}]");
        check(
            HologramBlueprintCompactCodec.parse(bytes(negativeZero))
                .equals(
                    HologramLibraryClient.readAuthorSource(bytes(negativeZero))
                        .sourceCopy()),
            "negative-zero reference retains actual legacy Gson acceptance");
        reject(compact("{}", "[0e0,{\"x\":0}]"), "exponent reference must not canonicalize to zero");
        reject(compact("{}", "[1,{\"x\":0}]"), "out-of-range dictionary reference");
        reject(compact("{}", "[0,{\"x\":0,\"extension\":1}]"), "compact coordinates remain narrow");
    }

    private static void compactLimits() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i > 0) rows.append(',');
            rows.append("[0,{\"x\":")
                .append(i)
                .append("}]");
        }
        String compact = compact("{\"future\":[1,2,3,4,5,6,7,8]}", rows.toString());
        rejectWithLimits(
            compact,
            new AuthorDocumentIO.Limits(20000, 200, 32, 800, 1000, 1000, 10),
            "expanded nodes must be admitted before material cloning");
        rejectWithLimits(
            compact,
            new AuthorDocumentIO.Limits(20000, 20, 32, 10000, 1000, 1000, 10),
            "expanded cell count");
        rejectWithLimits(
            compact("{\"future\":{\"a\":{\"b\":1}}}", "[0,{\"x\":0}]"),
            new AuthorDocumentIO.Limits(20000, 200, 6, 10000, 1000, 1000, 10),
            "expanded draft depth reservation");
        rejectWithLimits(compact, new AuthorDocumentIO.Limits(200, 200, 32, 10000, 1000, 1000, 10), "input bytes");
        rejectWithLimits(
            "{\"schema\":2,\"future\":\"123456789\"}",
            new AuthorDocumentIO.Limits(20000, 200, 32, 10000, 8, 1000, 10),
            "string length");
    }

    private static void draftReopen() throws Exception {
        AuthorDocument source = HologramLibraryClient
            .readAuthorSource(bytes(compact("{\"tileNbt\":0}", "[0,{\"x\":0}]")));
        byte[] draft = AuthorDocumentIO.writeDraft(source, AuthorDocumentIO.Limits.defaults());
        AuthorDocument reopened = HologramLibraryClient.readAuthorSource(draft);
        check(
            source.documentId()
                .equals(reopened.documentId()) && source.revision() == reopened.revision()
                && source.sourceCopy()
                    .equals(reopened.sourceCopy()),
            "actual draft reopen preserves identity and source");
        reject(
            new String(draft, StandardCharsets.UTF_8).replace("\"version\":1", "\"version\":1,\"version\":1"),
            "duplicate draft metadata");
    }

    private static String compact(String material, String rows) {
        return "{\"encoding\":\"gtmb-dictionary-1\",\"materials\":[" + material
            + "],\"nbt\":[\"not-base64!!+/=\"],\"futureEnvelope\":{\"preserved\":null},"
            + "\"blueprint\":{\"schema\":2,\"cells\":["
            + rows
            + "]}}";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void reject(String text, String label) throws Exception {
        try {
            HologramLibraryClient.readAuthorSource(bytes(text));
            throw new AssertionError(label + " accepted");
        } catch (IOException expected) {}
    }

    private static void rejectWithLimits(String text, AuthorDocumentIO.Limits limits, String label) throws Exception {
        try {
            AuthorSourceImporter.read(bytes(text), limits);
            throw new AssertionError(label + " accepted");
        } catch (IOException expected) {}
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
