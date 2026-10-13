package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Portable private storage must not depend on a server working directory or trust public ownership labels. */
public final class HologramClientLibraryRegression {

    private HologramClientLibraryRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        try {
            exercise();
            exerciseQuickFence();
        } catch (Exception failure) {
            throw new IllegalStateException("Client blueprint persistence regression", failure);
        }
    }

    private static void exercise() throws Exception {
        Path game = Files.createTempDirectory("gtmb-client-library-");
        try {
            HologramClientLibrary library = new HologramClientLibrary(game);
            String first = library.save(json("Original", null), "", "Alice", "alice-uuid");
            SimpleAssert.that(first.startsWith("local_"), "local id is distinct from public server id");
            SimpleAssert.eq(
                game.resolve("config/gtmb/client-blueprints")
                    .toAbsolutePath(),
                library.directory(),
                "private files are rooted under the actual client game directory");
            HologramClientLibrary restarted = new HologramClientLibrary(game);
            SimpleAssert.eq(
                1,
                restarted.list()
                    .size(),
                "private list survives a new client session");
            HologramClientLibrary.Entry entry = restarted.list()
                .get(0);
            SimpleAssert.eq("Alice", entry.authorName, "new capture receives current player author");
            SimpleAssert.eq("alice-uuid", entry.authorUuid, "new capture records creator UUID");
            SimpleAssert.that(entry.createdAt > 0, "new capture records creation timestamp");
            restarted.save(json("Revised", "Mallory"), first, "Bob", "bob-uuid");
            HologramClientLibrary.Entry updated = restarted.list()
                .get(0);
            SimpleAssert.eq("Alice", updated.authorName, "updating retains the original creator");
            SimpleAssert.eq(entry.createdAt, updated.createdAt, "updating retains original timestamp");
            SimpleAssert.eq("Revised", updated.name, "updating changes blueprint content in place");
            String publicId = "public_12345678-1234-1234-1234-123456789abc";
            restarted.uploadedId("server-a:25565", first, publicId);
            SimpleAssert.eq(
                publicId,
                new HologramClientLibrary(game).uploadedId("server-a:25565", first),
                "verified upload update mapping survives restart");
            SimpleAssert
                .eq("", restarted.uploadedId("server-b:25565", first), "public upload mapping is server-specific");
            restarted.forgetUpload("server-b:25565", publicId);
            SimpleAssert.eq(
                publicId,
                restarted.uploadedId("server-a:25565", first),
                "deleting another server's upload retains this mapping");
            restarted.forgetUpload("server-a:25565", publicId);
            SimpleAssert
                .eq("", restarted.uploadedId("server-a:25565", first), "deleted public upload can be published anew");
            restarted.rememberLocal("server-a:25565", first);
            SimpleAssert.eq(
                first,
                new HologramClientLibrary(game).lastSource("server-b:25565").localId,
                "last private blueprint is portable after restart to another server");
            restarted.rememberPublic("server-a:25565", publicId);
            HologramClientLibrary.LastSource source = new HologramClientLibrary(game).lastSource("server-a:25565");
            SimpleAssert.that(
                source.publicLibrary && publicId.equals(source.publicId),
                "last public opaque ID persists per server");
            SimpleAssert.that(
                !restarted.lastSource("server-b:25565").publicLibrary
                    && restarted.lastSource("server-b:25565").publicId.isEmpty(),
                "another server falls back to portable private source");
            restarted.forgetUpload("server-a:25565", publicId);
            SimpleAssert.that(
                !restarted.lastSource("server-a:25565").publicLibrary
                    && first.equals(restarted.lastSource("server-a:25565").localId),
                "deleting the public last source retains portable private fallback");
            restarted.rememberLocal("server-a:25565", first);
            SimpleAssert.that(
                !restarted.lastSource("server-a:25565").publicLibrary,
                "a successful private placement replaces last-source preference");
            SimpleAssert.eq(
                1,
                new HologramClientLibrary(game).list()
                    .size(),
                "changing server does not partition the private list");
            Files.write(
                library.directory()
                    .resolve("local_aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.json"),
                "broken JSON".getBytes(StandardCharsets.UTF_8));
            SimpleAssert.eq(
                1,
                restarted.list()
                    .size(),
                "one damaged file does not hide healthy entries");
            reject(() -> restarted.read("../server-blueprints/library"), "path traversal is rejected");
            reject(
                () -> restarted.save("{}".getBytes(StandardCharsets.UTF_8), "", "Alice", "uuid"),
                "invalid draft cannot become a client entry");
            reject(
                () -> restarted.save(new byte[HologramClientLibrary.MAX_BYTES + 1], "", "Alice", "uuid"),
                "oversized file rejected before JSON parsing");
            StringBuilder nested = new StringBuilder();
            for (int i = 0; i < 65; i++) nested.append('[');
            for (int i = 0; i < 65; i++) nested.append(']');
            reject(
                () -> restarted.save(
                    nested.toString()
                        .getBytes(StandardCharsets.UTF_8),
                    "",
                    "Alice",
                    "uuid"),
                "deeply nested JSON rejected before recursive parsing");
            String legacy = restarted.save(json("Imported legacy", null), "", "Alice", "uuid");
            SimpleAssert.that(!legacy.equals(first), "importing a public copy creates a distinct private identity");
            restarted.delete(first);
            SimpleAssert.eq(
                1,
                restarted.list()
                    .size(),
                "private delete removes only chosen entry");
            JsonObject stored = new JsonParser().parse(new String(restarted.read(legacy), StandardCharsets.UTF_8))
                .getAsJsonObject();
            SimpleAssert.that(
                stored.getAsJsonObject("blueprint")
                    .has("authorName"),
                "legacy import upgrades absent attribution safely");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(game)) {
                paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
            }
        }
    }

    private static void exerciseQuickFence() {
        HologramLibraryQuickState quick = new HologramLibraryQuickState();
        String first = quick.begin();
        SimpleAssert.that(quick.matches(first), "bootstrap token exists before async IO begins");
        SimpleAssert
            .that(quick.bootstrap(first, "first-session"), "matching fresh-session ACK can begin the private upload");
        SimpleAssert
            .that(!quick.bootstrap(first, "first-session"), "duplicate bootstrap ACK cannot start another upload");
        SimpleAssert.that(quick.matches(first), "upload completion retains cancellation authority until world lock");
        SimpleAssert.eq(first, quick.cancel(), "Escape can cancel the token after the final chunk");
        SimpleAssert.that(
            !quick.bootstrap(first, "first-session") && !quick.matches(first),
            "late bootstrap/final ACK cannot revive a canceled placement");
        String second = quick.begin();
        SimpleAssert.that(
            !quick.bootstrap(first, "first-session") && quick.bootstrap(second, "second-session"),
            "replaced/world-reset token cannot cross into fresh placement");
        SimpleAssert.that(
            !quick.commitLockedSession("first-session") && quick.active(),
            "an unrelated locked snapshot cannot retire the current shortcut");
        SimpleAssert.that(
            quick.commitLockedSession("second-session"),
            "the acknowledged session lock retires the current shortcut");
        SimpleAssert
            .that(!quick.active() && !quick.matches(second), "world lock retires temporary cancellation capability");
        String third = quick.begin();
        SimpleAssert.that(
            !quick.commitLockedSession("second-session") && quick.matches(third),
            "post-completion Alt remains active while the old locked snapshot is still displayed");
        SimpleAssert.that(
            quick.bootstrap(third, "third-session"),
            "post-completion fresh bootstrap remains accepted after the old snapshot");
        SimpleAssert.that(
            !quick.commitLockedSession("second-session") && quick.matches(third),
            "old completion cannot retire a newly acknowledged upload");
        quick.cancel();
        SimpleAssert.that(!quick.bootstrap(third, "third-session"), "Escape prevents a later upload from starting");
        String fourth = quick.begin();
        SimpleAssert
            .that(!quick.bootstrap(fourth, ""), "a bootstrap without a server session grants no upload authority");
        quick.cancel();
        SimpleAssert.that(
            !quick.bootstrap(fourth, "fourth-session"),
            "Escape during disk loading prevents a later upload from starting");
    }

    private interface Operation {

        void run() throws Exception;
    }

    private static void reject(Operation operation, String description) throws Exception {
        boolean rejected = false;
        try {
            operation.run();
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, description);
    }

    private static byte[] json(String name, String author) {
        JsonObject blueprint = new JsonObject();
        blueprint.addProperty("schema", 2);
        blueprint.addProperty("name", name);
        blueprint.add("cells", new com.google.gson.JsonArray());
        if (author != null) blueprint.addProperty("authorName", author);
        JsonObject share = new JsonObject();
        share.addProperty("schema", 2);
        share.add("blueprint", blueprint);
        return share.toString()
            .getBytes(StandardCharsets.UTF_8);
    }
}
