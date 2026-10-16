package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Portable private storage must not depend on a server working directory or trust public ownership labels. */
public final class HologramClientLibraryRegression {

    private HologramClientLibraryRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramClientLibraryRegression.class, args)) return;
        run();
    }

    public static void run() {
        try {
            exercise();
            exerciseSaveReadbackReuse();
            exerciseSaveMessages();
            exerciseQuickFence();
        } catch (Exception failure) {
            throw new IllegalStateException("Client blueprint persistence regression", failure);
        }
    }

    private static void exercise() throws Exception {
        Path game = Files.createTempDirectory("gtmb-client-library-");
        try {
            Path oldDirectory = Files.createDirectories(game.resolve("config/gtmb/client-blueprints"));
            Path oldFile = oldDirectory.resolve("local_11111111-1111-1111-1111-111111111111.json");
            byte[] oldBytes = json("Old directory only", null);
            Files.write(oldFile, oldBytes);
            HologramClientLibrary library = new HologramClientLibrary(game);
            SimpleAssert.eq(
                0,
                library.list()
                    .size(),
                "old client directory is neither scanned nor migrated");
            SimpleAssert.that(
                java.util.Arrays.equals(oldBytes, Files.readAllBytes(oldFile)),
                "old client blueprint remains untouched");
            byte[] original = json("Original", null);
            String first = library.save(original, png(0xff335577), "");
            SimpleAssert.that(first.startsWith("local_"), "local id is distinct from public server id");
            SimpleAssert.eq(
                game.resolve("MiaoBlueprints")
                    .toAbsolutePath(),
                library.directory(),
                "private files are rooted under the actual client game directory");
            SimpleAssert.eq(
                game.resolve("MiaoBlueprints/" + first + ".json")
                    .toAbsolutePath(),
                library.savedPath(first),
                "new saves use the exact top-level blueprint path");
            SimpleAssert.that(Files.isRegularFile(library.savedPath(first)), "new blueprint is persisted at that path");
            SimpleAssert.that(
                Files.isRegularFile(HologramBlueprintArtifact.previewPath(library.savedPath(first))),
                "save commits a same-basename PNG beside JSON");
            SimpleAssert.that(
                java.util.Arrays.equals(original, library.read(first)),
                "client persistence keeps exact server JSON for strict nonce acknowledgement");
            Files.write(
                library.directory()
                    .resolve("hologram-presets.json"),
                oldBytes);
            Files.write(
                library.directory()
                    .resolve("shared_fixture.json"),
                oldBytes);
            Files.write(
                library.directory()
                    .resolve("public_22222222-2222-2222-2222-222222222222.json"),
                oldBytes);
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
            byte[] revised = json("Revised", "Alice"), revisedPng = png(0xff776644);
            restarted.save(revised, revisedPng, first);
            HologramClientLibrary.Entry updated = restarted.list()
                .get(0);
            SimpleAssert.eq("Alice", updated.authorName, "updating retains the original creator");
            SimpleAssert.eq(entry.createdAt, updated.createdAt, "updating retains original timestamp");
            SimpleAssert.eq("Revised", updated.name, "updating changes blueprint content in place");
            SimpleAssert.that(
                java.util.Arrays.equals(revised, restarted.read(first)),
                "overwrite preserves reviewed JSON bytes without local provenance rewrites");
            SimpleAssert.eq(
                0xff776644,
                javax.imageio.ImageIO.read(
                    HologramBlueprintArtifact.previewPath(library.savedPath(first))
                        .toFile())
                    .getRGB(0, 0),
                "overwrite replaces previous preview pixels at the same path");
            reject(
                () -> restarted.save(json("Broken preview", null), new byte[] { 1, 2, 3 }, first),
                "invalid PNG cannot replace a saved artifact");
            SimpleAssert.that(
                java.util.Arrays.equals(revised, restarted.read(first)),
                "invalid PNG leaves the old JSON/PNG pair intact");
            String publicId = "public_12345678-1234-1234-1234-123456789abc";
            restarted.uploadedId("server-a:25565", first, publicId);
            SimpleAssert.that(
                Files.isRegularFile(game.resolve("config/gtmb/client-blueprint-library.properties")),
                "upload and source settings remain in the mod config directory");
            SimpleAssert.that(
                !Files.exists(
                    library.directory()
                        .resolve("uploads.properties")),
                "blueprint directory is separate from client upload metadata");
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
            reject(() -> restarted.read(publicId), "client files cannot be addressed with server public IDs");
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
            String legacy = restarted.save(json("Imported current format", null), png(0xff224466), "");
            SimpleAssert.that(!legacy.equals(first), "importing a public copy creates a distinct private identity");
            restarted.delete(first);
            SimpleAssert.that(
                !Files.exists(HologramBlueprintArtifact.previewPath(library.savedPath(first))),
                "private delete removes its preview alongside JSON");
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
                "received author attribution is preserved exactly");
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

    private static void exerciseSaveReadbackReuse() throws Exception {
        Path game = Files.createTempDirectory("gtmb-local-save-reuse-");
        CountingPairs pairs = new CountingPairs();
        HologramClientLibrary library = new HologramClientLibrary(game, pairs);
        String id = "local_11111111-1111-1111-1111-111111111111";
        try {
            byte[] original = json("Original", null);
            byte[] preview = png(0xff224466);
            HologramClientLibrary.LocalSave saved = library.prepareLocalSave(original, preview, id, () -> true);
            SimpleAssert.eq(1, pairs.writes, "private save performs one actual pair write");
            SimpleAssert.eq(1, pairs.reads, "private save performs one actual pair readback");
            SimpleAssert
                .eq(HologramBlueprintArtifact.hash(original), saved.jsonHash, "receipt retains exact JSON digest");
            SimpleAssert.eq(
                HologramBlueprintArtifact
                    .hash(Files.readAllBytes(HologramBlueprintArtifact.previewPath(library.savedPath(id)))),
                saved.pngHash,
                "receipt hashes the actual bound PNG on disk");
            saved.write.commit();
            library.list();
            library.list();
            SimpleAssert.eq(1, pairs.reads, "unchanged catalog refreshes reuse validated readback metadata");
            Path path = library.savedPath(id);
            java.nio.file.attribute.FileTime time = Files.getLastModifiedTime(path);
            byte[] changed = original.clone();
            String text = new String(changed, StandardCharsets.UTF_8).replace("Original", "Modified");
            changed = text.getBytes(StandardCharsets.UTF_8);
            SimpleAssert.eq(original.length, changed.length, "mutation preserves byte length");
            Files.write(path, changed);
            Files.setLastModifiedTime(path, time);
            SimpleAssert.eq(
                0,
                library.list()
                    .size(),
                "same-size same-mtime JSON change invalidates PNG binding");
            SimpleAssert.eq(2, pairs.reads, "changed content is actually revalidated");
            Files.write(path, original);
            library.list();
            SimpleAssert.eq(3, pairs.reads, "restored pair is validated after invalidation");
            Path pngPath = HologramBlueprintArtifact.previewPath(path);
            byte[] originalPng = Files.readAllBytes(pngPath);
            java.nio.file.attribute.FileTime pngTime = Files.getLastModifiedTime(pngPath);
            byte[] corrupt = originalPng.clone();
            corrupt[corrupt.length - 1] ^= 1;
            Files.write(pngPath, corrupt);
            Files.setLastModifiedTime(pngPath, pngTime);
            SimpleAssert.eq(
                0,
                library.list()
                    .size(),
                "same-size same-mtime PNG damage invalidates metadata");
            SimpleAssert.eq(4, pairs.reads, "changed PNG receives full validation");
            Files.write(pngPath, originalPng);
            library.list();
            pairs.failRead = true;
            reject(
                () -> library.prepareLocalSave(json("Replaced", null), preview, id, () -> true),
                "readback failure cannot acknowledge a save");
            pairs.failRead = false;
            SimpleAssert.that(
                java.util.Arrays.equals(original, Files.readAllBytes(path)),
                "failed readback restores the previous exact JSON");
            SimpleAssert.that(
                java.util.Arrays.equals(originalPng, Files.readAllBytes(pngPath)),
                "failed readback restores the previous exact PNG");
            java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
            pairs.afterRead = () -> current.set(false);
            reject(
                () -> library.prepareLocalSave(json("Canceled", null), preview, id, current::get),
                "source change after actual readback rejects the receipt");
            SimpleAssert.that(
                java.util.Arrays.equals(original, Files.readAllBytes(path)),
                "source-change cancellation restores the old pair");
            pairs.afterRead = null;
            SimpleAssert.eq(
                "Original",
                library.list()
                    .get(0).name,
                "rollback does not leave cached replacement metadata");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(game)) {
                for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static final class CountingPairs implements HologramClientLibrary.PairIo {

        int writes, reads;
        boolean failRead;
        Runnable afterRead;

        @Override
        public HologramBlueprintArtifact.PairWrite write(Path path, byte[] json, byte[] png,
            java.util.function.BooleanSupplier current) throws IOException {
            writes++;
            return HologramBlueprintArtifact.writePairPending(path, json, png, current);
        }

        @Override
        public HologramBlueprintArtifact.Artifact read(Path path) throws IOException {
            reads++;
            if (failRead) throw new IOException("Injected readback failure");
            HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.readPair(path);
            if (afterRead != null) afterRead.run();
            return actual;
        }
    }

    private static void exerciseSaveMessages() throws ReflectiveOperationException {
        HologramLibraryClient client = HologramLibraryClient.INSTANCE;
        java.lang.reflect.Field status = HologramLibraryClient.class.getDeclaredField("status");
        status.setAccessible(true);
        String previous = (String) status.get(client);
        try {
            for (String message : new String[] { "正在生成真实结构预览 PNG", "JSON 与 PNG 已写入，正在确认蓝图库索引", "JSON 与结构预览 PNG 已校验保存",
                "蓝图保存未提交，原 JSON 与 PNG 已恢复：保存已取消" }) {
                status.set(client, message);
                SimpleAssert.eq(message, client.status(), "actual status getter preserves localized save outcome");
            }
            status.set(client, "Invalid blueprint PNG size");
            SimpleAssert
                .eq("蓝图与预览图的保存校验未完成，请保持当前蓝图并重试", client.status(), "real English PNG diagnostic remains actionable");
            status.set(client, "Pending blueprint artifact rollback failed");
            SimpleAssert.eq(
                "蓝图文件回退尚未完成，请核对 JSON 与预览图后重试",
                client.status(),
                "real English artifact rollback failure remains visible");
        } finally {
            status.set(client, previous);
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
        String fallback = quick.begin();
        SimpleAssert.that(quick.bootstrap(fallback, "fallback-bootstrap"), "fallback gets its own server capability");
        SimpleAssert.that(
            !quick.snapshotMatches(third, 21, 21) && !quick.snapshotMatches(fallback, 22, 21),
            "old shortcut snapshot and old task token cannot complete a fresh background shortcut");
        SimpleAssert.that(
            !quick.placement(third, "fallback-bootstrap", "placed"),
            "old fallback reply cannot replace the current session");
        SimpleAssert.that(
            quick.placement(fallback, "fallback-bootstrap", "placed"),
            "current fallback reply binds the actual new placement session");
        SimpleAssert.that(
            !quick.commitLockedSession("fallback-bootstrap"),
            "bootstrap or old locked session does not imply final placement");
        SimpleAssert.that(
            quick.snapshotMatches(fallback, 22, 22) && quick.commitLockedSession("placed"),
            "matching task, quick capability and actual locked placement finish the background shortcut");
        SimpleAssert.that(
            !quick.placement(fallback, "fallback-bootstrap", "placed"),
            "late fallback success cannot revive the completed task");
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
        blueprint.addProperty("id", "local_33333333-3333-3333-3333-333333333333");
        blueprint.addProperty("name", name);
        blueprint.addProperty("controllerType", "area");
        JsonObject cell = new JsonObject();
        cell.addProperty("item", "minecraft:stone");
        cell.addProperty("block", "minecraft:stone");
        cell.addProperty("count", 1);
        cell.addProperty("facing", -1);
        com.google.gson.JsonArray cells = new com.google.gson.JsonArray();
        cells.add(cell);
        blueprint.add("cells", cells);
        blueprint.addProperty("authorName", author == null ? "Alice" : author);
        blueprint.addProperty("authorUuid", "alice-uuid");
        blueprint.addProperty("createdAt", 1234);
        JsonObject version = new JsonObject();
        version.addProperty("formatVersion", 1);
        version.addProperty("gtmbVersion", "0.1.0");
        version.addProperty("gtnhVersion", "unknown");
        version.addProperty("gtnhVersionSource", "unavailable");
        blueprint.add("nativeBlueprint", version);
        JsonObject share = new JsonObject();
        share.addProperty("schema", 2);
        share.add("blueprint", blueprint);
        return share.toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] png(int color) throws IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            HologramBlueprintArtifact.WIDTH,
            HologramBlueprintArtifact.HEIGHT,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
