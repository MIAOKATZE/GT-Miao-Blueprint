// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Versioned, detachable JSON metadata. Removing it recovers the exact reviewed source bytes. */
public final class HologramBlueprintPreviewBinding {

    public static final int VERSION = 1;
    public static final String JSON_PREFIX = "\"previewBinding\":{\"version\":1,\"pngSha256\":\"";
    public static final String JSON_SUFFIX = "\"},";
    private static final byte[] PREFIX = JSON_PREFIX.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SUFFIX = JSON_SUFFIX.getBytes(StandardCharsets.US_ASCII);
    private static final int MEMBER_BYTES = PREFIX.length + 64 + SUFFIX.length;

    private HologramBlueprintPreviewBinding() {}

    public static String pngSha256(byte[] json) throws IOException {
        return pngSha256(json, opening(json));
    }

    private static String pngSha256(byte[] json, int opening) throws IOException {
        boolean canonical = matches(json, opening + 1, PREFIX);
        boolean present = validate(json);
        if (!present) return "";
        int hashStart = opening + 1 + PREFIX.length;
        if (!canonical || !matches(json, hashStart + 64, SUFFIX))
            throw new IOException("Noncanonical blueprint preview binding; save the blueprint again");
        String hash = new String(json, hashStart, 64, StandardCharsets.US_ASCII);
        if (!hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid blueprint preview PNG digest");
        return hash;
    }

    /** Only for the freshly read, exclusively owned bytes inside one readLocalJson call. */
    static LocalBinding inspectLocalJson(byte[] json) throws IOException {
        int opening = opening(json);
        return new LocalBinding(json, pngSha256(json, opening), opening + 1);
    }

    static final class LocalBinding {

        private final byte[] json;
        final String pngSha256;
        private final int insertion;

        private LocalBinding(byte[] json, String pngSha256, int insertion) {
            this.json = json;
            this.pngSha256 = pngSha256;
            this.insertion = insertion;
        }

        String sourceSha256() {
            if (pngSha256.isEmpty()) return HologramBlueprintArtifact.hash(json);
            // Exactly sourceJson's two arraycopy ranges; no detached JSON is materialized.
            MessageDigest digest = newSha256();
            digest.update(json, 0, insertion);
            int suffix = insertion + MEMBER_BYTES;
            digest.update(json, suffix, json.length - suffix);
            return hex(digest.digest());
        }
    }

    /** No reserialization: every other byte, including whitespace and extension fields, survives. */
    public static byte[] sourceJson(byte[] json) throws IOException {
        if (pngSha256(json).isEmpty()) return json.clone();
        int insertion = opening(json) + 1;
        byte[] source = new byte[json.length - MEMBER_BYTES];
        System.arraycopy(json, 0, source, 0, insertion);
        System.arraycopy(json, insertion + MEMBER_BYTES, source, insertion, source.length - insertion);
        return source;
    }

    public static byte[] bind(byte[] json, String pngSha256) throws IOException {
        byte[] source = sourceJson(json);
        if (!pngSha256.matches("[a-f0-9]{64}")) throw new IOException("Invalid blueprint preview PNG digest");
        if (source.length > HologramBlueprintArtifact.MAX_JSON_BYTES - MEMBER_BYTES)
            throw new IOException("Preview-bound blueprint JSON exceeds its byte budget");
        int insertion = opening(source) + 1;
        int next = insertion;
        while (next < source.length && whitespace(source[next])) next++;
        if (next >= source.length || source[next] == '}') throw new IOException("Missing blueprint content");
        byte[] member = (JSON_PREFIX + pngSha256 + JSON_SUFFIX).getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[source.length + member.length];
        System.arraycopy(source, 0, result, 0, insertion);
        System.arraycopy(member, 0, result, insertion, member.length);
        System.arraycopy(source, insertion, result, insertion + member.length, source.length - insertion);
        return result;
    }

    /** Verify a disk ACK against server-prepared source bytes without allocating another full JSON. */
    static String boundJsonSha256(byte[] source, String pngSha256) throws IOException {
        if (!pngSha256.matches("[a-f0-9]{64}")) throw new IOException("Invalid blueprint preview PNG digest");
        int insertion = opening(source) + 1;
        MessageDigest digest = newSha256();
        digest.update(source, 0, insertion);
        digest.update((JSON_PREFIX + pngSha256 + JSON_SUFFIX).getBytes(StandardCharsets.US_ASCII));
        digest.update(source, insertion, source.length - insertion);
        return hex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] hash) {
        char[] hex = new char[64], digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < hash.length; index++) {
            hex[index * 2] = digits[(hash[index] & 255) >>> 4];
            hex[index * 2 + 1] = digits[hash[index] & 15];
        }
        return new String(hex);
    }

    private static int opening(byte[] json) throws IOException {
        if (json == null || json.length == 0 || json.length > HologramBlueprintArtifact.MAX_JSON_BYTES)
            throw new IOException("Invalid blueprint JSON byte budget");
        int at = 0;
        while (at < json.length && whitespace(json[at])) at++;
        if (at >= json.length || json[at] != '{') throw new IOException("Missing blueprint JSON object");
        return at;
    }

    private static boolean whitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    private static boolean matches(byte[] bytes, int at, byte[] expected) {
        if (at < 0 || at > bytes.length - expected.length) return false;
        for (int index = 0; index < expected.length; index++) if (bytes[at + index] != expected[index]) return false;
        return true;
    }

    /** Exact strict JSON syntax for a display whose compact dictionary/shape was already validated. */
    public static void validateStoredSyntax(byte[] json) throws IOException {
        if (json == null || json.length == 0 || json.length > HologramBlueprintArtifact.MAX_JSON_BYTES)
            throw new IOException("Blueprint JSON byte budget");
        validate(json);
    }

    private static boolean validate(byte[] json) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(json))
            .toString();
        return validate(text);
    }

    static boolean validate(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            boolean binding = object(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing blueprint JSON data");
            return binding;
        } catch (IllegalStateException | NumberFormatException invalid) {
            throw new IOException("Invalid blueprint preview JSON", invalid);
        }
    }

    private static boolean object(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new IOException("Blueprint JSON nesting budget");
        reader.beginObject();
        Set<String> keys = new HashSet<>();
        boolean binding = false;
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (!keys.add(key)) throw new IOException("Duplicate blueprint JSON key");
            if (depth == 0 && key.equals("previewBinding")) binding = true;
            value(reader, depth + 1);
        }
        reader.endObject();
        return binding;
    }

    private static void value(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new IOException("Blueprint JSON nesting budget");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> object(reader, depth);
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) value(reader, depth + 1);
                reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IOException("Invalid blueprint JSON value");
        }
    }
}
