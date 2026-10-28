package com.miaokatze.gtmb.blueprint.editor;

import java.io.IOException;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;

/**
 * Author-facing format adapter. Kept beside the core importer solely for its package-private ownership boundary;
 * AuthorDocumentIO itself remains independent of hologram/game classes. No mutable parse tree escapes this adapter.
 */
public final class AuthorSourceImporter {

    private AuthorSourceImporter() {}

    public static AuthorDocument read(byte[] bytes, AuthorDocumentIO.Limits limits) throws IOException {
        JsonObject owned = AuthorDocumentIO.parseOwned(bytes, limits);
        HologramBlueprintCompactCodec.expandAuthorInput(owned, limits);
        return AuthorDocumentIO.importOwned(owned, limits);
    }
}
