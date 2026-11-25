// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

import static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintBytes.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Non-Forge command line consumer of exactly the same migration core and pair publisher as the game. */
public final class NativeBlueprintCli {

    private NativeBlueprintCli() {}

    public static void main(String[] args) throws Exception {
        run(args);
    }

    public static void run(String[] args) throws IOException {
        if (args.length < 2) throw bad(
            "usage: inspect FILE | convert OLD_JSON OUTPUT_DIRECTORY LOGICAL_ID [PNG] [MEMBER] | source-only OLD_JSON OUTPUT_GTNB [MEMBER]");
        if (args[0].equals("inspect")) {
            try (NativeBlueprintDefinition d = NativeBlueprintDefinition.open(Paths.get(args[1]))) {
                JsonObject report = new JsonObject();
                report.addProperty("cells", d.cells());
                report.addProperty("physicalHash", d.revision());
                report.addProperty("contentHash", NativeBlueprintIdentity.content(d));
                report.addProperty("geometryMutationSupported", d.allowsGeometryMutation());
                System.out.println(new String(NativeBlueprintJson.encode(report), StandardCharsets.US_ASCII));
            }
            return;
        }
        if (args[0].equals("convert")) {
            need(args.length >= 4 && args.length <= 6, "convert arguments");
            Path source = Paths.get(args[1]), out = Paths.get(args[2]);
            Path png = args.length >= 5 ? Paths.get(args[4]) : source.resolveSibling(stem(source) + ".png");
            int member = args.length == 6 ? Integer.parseInt(args[5]) : -1;
            NativeBlueprintPairStore.Receipt r = convertPair(source, png, out, args[3], member, () -> true);
            System.out.println("COMMITTED " + r.logicalId + " " + r.physicalHash + " " + r.source.getFileName());
            return;
        }
        if (args[0].equals("source-only")) {
            need(args.length >= 3 && args.length <= 4, "source-only arguments");
            byte[] source = NativeBlueprintPairStore.readRegular(Paths.get(args[1]), NativeBlueprintLimits.FILE_BYTES);
            NativeBlueprintMigration.Result r = NativeBlueprintMigration
                .convert(source, args.length == 4 ? Integer.parseInt(args[3]) : -1, () -> true);
            Path target = Paths.get(args[2])
                .toAbsolutePath();
            need(!Files.exists(target), "source-only output exists");
            Path staged = Files.createTempFile(target.getParent(), "gtmb-source-only-", ".tmp");
            try {
                Files.write(staged, r.bytes);
                try (NativeBlueprintDefinition d = NativeBlueprintDefinition.open(staged)) {}
                Files.createLink(target, staged); // exclusive publish; never replace an unrelated existing archive
            } finally {
                Files.deleteIfExists(staged);
            }
            System.out.println("SOURCE_ONLY_PREVIEW_REQUIRED " + r.cells + " " + r.bytes.length + " " + r.sectionBytes);
            return;
        }
        throw bad("unknown command");
    }

    public static NativeBlueprintPairStore.Receipt convertPair(Path source, Path png, Path output, String id,
        int member, java.util.function.BooleanSupplier current) throws IOException {
        Path sourceDir = source.toRealPath()
            .getParent();
        Path canonical = resolvedDestination(output);
        need(
            !canonical.equals(sourceDir) && !"MiaoBlueprints".equalsIgnoreCase(
                canonical.getFileName()
                    .toString())
                && !Files.exists(
                    canonical.resolve(".gtmb-blueprint-files.properties"),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !canonical.startsWith(sourceDir.resolve(".gtmb-native-staging")),
            "CLI conversion requires a separate export directory, not the source library");
        byte[] original = NativeBlueprintPairStore.readRegular(source, NativeBlueprintLimits.FILE_BYTES);
        byte[] image = NativeBlueprintPairStore.readRegular(png, 4 * 1024 * 1024);
        NativeBlueprintMigration.Result result = migratePair(original, image, member, current);
        return NativeBlueprintPairStore.publish(canonical, id, result.bytes, image, null, null, current);
    }

    private static Path resolvedDestination(Path output) throws IOException {
        Path absolute = output.toAbsolutePath()
            .normalize(), existing = absolute;
        while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            need(existing != null, "output path has no parent");
        }
        return existing.toRealPath()
            .resolve(existing.relativize(absolute));
    }

    public static NativeBlueprintMigration.Result migratePair(byte[] source, byte[] image, int member,
        java.util.function.BooleanSupplier current) throws IOException {
        validateLegacyPreview(source, image);
        return NativeBlueprintMigration.convert(source, member, current);
    }

    /** Legacy source identity for resolving a user-facing filename to its existing catalog row. */
    public static String legacyLogicalId(byte[] source) throws IOException {
        JsonObject root = new NativeBlueprintJson.LegacyInput(source).root;
        JsonElement member = root.has("blueprint") ? root.get("blueprint") : root;
        JsonElement id = member.isJsonObject() ? member.getAsJsonObject()
            .get("id") : null;
        return id != null && id.isJsonPrimitive()
            && id.getAsJsonPrimitive()
                .isString() ? id.getAsString() : "";
    }

    private static String stem(Path p) {
        String n = p.getFileName()
            .toString();
        int at = n.lastIndexOf('.');
        return at < 0 ? n : n.substring(0, at);
    }

    public static void validateLegacyPreview(byte[] source, byte[] png) throws IOException {
        // Always validate the complete PNG/CRC before using any text binding.
        NativeBlueprintPng.bind(png, "0000000000000000000000000000000000000000000000000000000000000000");
        JsonObject root = new NativeBlueprintJson.LegacyInput(source).root;
        byte[] detached = source;
        if (root.has("previewBinding")) {
            JsonObject binding = root.getAsJsonObject("previewBinding");
            need(
                binding.has("version") && NativeBlueprintDefinition.integer(binding.get("version")) == 1
                    && binding.has("pngSha256")
                    && binding.get("pngSha256")
                        .getAsString()
                        .equals(hash(png)),
                "legacy PNG digest mismatch");
            String prefix = "\"previewBinding\":{\"version\":1,\"pngSha256\":\"" + hash(png) + "\"},";
            byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
            int open = 0;
            while (open < source.length && Character.isWhitespace(source[open])) open++;
            need(
                open < source.length && source[open] == '{' && open + 1 + p.length <= source.length,
                "legacy binding syntax");
            for (int i = 0; i < p.length; i++) need(source[open + 1 + i] == p[i], "legacy noncanonical binding");
            detached = new byte[source.length - p.length];
            System.arraycopy(source, 0, detached, 0, open + 1);
            System.arraycopy(source, open + 1 + p.length, detached, open + 1, source.length - open - 1 - p.length);
        }
        // Old PNGs without binding are explicit legacy pairs, not evidence of an already verified source.
        int at = 8;
        String bound = null;
        while (at < png.length) {
            int n = (png[at] & 255) << 24 | (png[at + 1] & 255) << 16 | (png[at + 2] & 255) << 8 | (png[at + 3] & 255);
            String t = new String(png, at + 4, 4, StandardCharsets.US_ASCII);
            if (t.equals("tEXt")) {
                int split = 0;
                while (split < n && png[at + 8 + split] != 0) split++;
                String key = new String(png, at + 8, split, StandardCharsets.ISO_8859_1);
                if (key.equals("gtmb.sourceJsonSha256")) {
                    need(bound == null, "duplicate legacy PNG source");
                    bound = new String(png, at + 9 + split, n - split - 1, StandardCharsets.ISO_8859_1);
                }
            }
            at += n + 12;
        }
        need(bound == null ? !root.has("previewBinding") : bound.equals(hash(detached)), "legacy PNG source mismatch");
    }
}
