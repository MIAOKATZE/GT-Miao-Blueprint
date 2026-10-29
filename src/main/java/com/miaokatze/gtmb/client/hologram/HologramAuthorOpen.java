package com.miaokatze.gtmb.client.hologram;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;

/** Real asynchronous source acquisition. GUI installation and selection publication run on its owner executor. */
final class HologramAuthorOpen {

    interface Source {

        byte[] read() throws Exception;
    }

    private HologramAuthorOpen() {}

    static void begin(Source source, Executor io, Executor owner, BooleanSupplier current,
        Consumer<AuthorDocument> install, Consumer<Exception> failure) {
        begin(source, io, owner, current, (document, bytes) -> install.accept(document), failure);
    }

    static void begin(Source source, Executor io, Executor owner, BooleanSupplier current,
        java.util.function.BiConsumer<AuthorDocument, byte[]> install, Consumer<Exception> failure) {
        try {
            io.execute(() -> {
                try {
                    byte[] bytes = source.read();
                    AuthorDocument document = HologramLibraryClient.readAuthorSource(bytes);
                    owner.execute(() -> {
                        if (!current.getAsBoolean()) return;
                        try {
                            install.accept(document, bytes);
                        } catch (RuntimeException rejected) {
                            failure.accept(rejected);
                        }
                    });
                } catch (Exception rejected) {
                    owner.execute(() -> { if (current.getAsBoolean()) failure.accept(rejected); });
                }
            });
        } catch (RuntimeException rejected) {
            failure.accept(rejected);
        }
    }
}
