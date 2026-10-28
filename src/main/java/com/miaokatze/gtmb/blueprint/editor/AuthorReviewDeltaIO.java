package com.miaokatze.gtmb.blueprint.editor;

import java.io.IOException;

import com.google.gson.JsonObject;

/** Bounded structural delta decoding; this is not a document, completion receipt or world capability. */
public final class AuthorReviewDeltaIO {

    private AuthorReviewDeltaIO() {}

    public static JsonObject read(byte[] bytes, AuthorDocumentIO.Limits limits) throws IOException {
        return AuthorDocumentIO.parseOwned(bytes, limits);
    }
}
