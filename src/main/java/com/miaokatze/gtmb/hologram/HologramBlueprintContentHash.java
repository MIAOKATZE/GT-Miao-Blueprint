package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

/** Pure content identity; no registry, world, sanitization or full blueprint validation. */
public final class HologramBlueprintContentHash {

    private HologramBlueprintContentHash() {}

    public static String of(byte[] json) throws IOException {
        try {
            JsonElement blueprint = HologramBlueprintCompactCodec.parse(json)
                .get("blueprint");
            if (blueprint == null || !blueprint.isJsonObject()) throw new IOException("Missing blueprint content");
            return ofBlueprint(blueprint);
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid blueprint content JSON", malformed);
        }
    }

    /** Already admitted owned DTO; serialization only, without game validation or compression. */
    static String of(HologramPresets.Preset preset) throws IOException {
        com.google.gson.JsonObject metadata = HologramBlueprintCompactCodec.metadata(preset);
        List<String> keys = new ArrayList<>();
        for (java.util.Map.Entry<String, JsonElement> entry : metadata.entrySet())
            if (!descriptive(entry.getKey())) keys.add(entry.getKey());
        if (preset.cells != null) keys.add("cells");
        if (preset.palette != null) keys.add("palette");
        Collections.sort(keys);
        MessageDigest digest = HologramLibraryProtocol.digest();
        token(digest, "gtmb-content-1");
        token(digest, "{");
        Gson gson = new Gson();
        for (String key : keys) {
            token(digest, key);
            if (key.equals("cells") || key.equals("palette")) {
                token(digest, "[");
                for (HologramPresets.Cell cell : key.equals("cells") ? preset.cells : preset.palette)
                    hash(digest, gson.toJsonTree(cell), false);
                token(digest, "]");
            } else hash(digest, metadata.get(key), false);
        }
        token(digest, "}");
        return HologramLibraryProtocol.hex(digest.digest());
    }

    private static String ofBlueprint(JsonElement blueprint) {
        MessageDigest digest = HologramLibraryProtocol.digest();
        token(digest, "gtmb-content-1");
        hash(digest, blueprint, true);
        return HologramLibraryProtocol.hex(digest.digest());
    }

    private static void hash(MessageDigest digest, JsonElement value, boolean blueprint) {
        if (value.isJsonObject()) {
            token(digest, "{");
            List<String> keys = new ArrayList<>();
            for (java.util.Map.Entry<String, JsonElement> entry : value.getAsJsonObject()
                .entrySet()) if (!blueprint || !descriptive(entry.getKey())) keys.add(entry.getKey());
            Collections.sort(keys);
            for (String key : keys) {
                token(digest, key);
                hash(
                    digest,
                    value.getAsJsonObject()
                        .get(key),
                    false);
            }
            token(digest, "}");
        } else if (value.isJsonArray()) {
            token(digest, "[");
            for (JsonElement child : value.getAsJsonArray()) hash(digest, child, false);
            token(digest, "]");
        } else token(digest, value.toString());
    }

    private static boolean descriptive(String key) {
        return "id".equals(key) || "name".equals(key)
            || "authorUuid".equals(key)
            || "authorName".equals(key)
            || "createdAt".equals(key);
    }

    private static void token(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (bytes.length >>> shift));
        digest.update(bytes);
    }
}
