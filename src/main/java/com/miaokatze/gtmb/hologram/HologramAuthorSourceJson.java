package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/** Raw author source encoding, distinct from typed DTO serialization used by native before/after deltas. */
final class HologramAuthorSourceJson {

    private static final Gson SOURCE = new GsonBuilder().serializeNulls()
        .create();

    private HologramAuthorSourceJson() {}

    static byte[] encode(JsonObject source, int limit) throws IOException {
        return HologramBlueprintSharing.encodeBounded(SOURCE, source, limit);
    }
}
