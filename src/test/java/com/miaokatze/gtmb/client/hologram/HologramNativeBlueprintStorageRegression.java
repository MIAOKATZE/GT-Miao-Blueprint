// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintFiles;
import com.miaokatze.gtmb.hologram.HologramNativeBlueprintStorage;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCli;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCodecRegression;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;

/** Real local-storage worker seam; stage1 does not claim native editor/render support. */
public final class HologramNativeBlueprintStorageRegression {

    private HologramNativeBlueprintStorageRegression() {}

    public static void run() {
        try {
            exercise();
        } catch (Exception e) {
            throw new IllegalStateException("native local staging: " + e, e);
        }
    }

    private static void exercise() throws Exception {
        Path game = Files.createTempDirectory("gtmb-native-game-");
        try {
            HologramClientLibrary library = new HologramClientLibrary(game);
            Path dir = library.directory();
            Files.createDirectories(dir);
            String id = "local_77777777-7777-7777-7777-777777777777";
            byte[] json = ("{\"schema\":2,\"blueprint\":{\"id\":\"" + id
                + "\",\"name\":\"Native Stage\",\"authorName\":\"Fixture\",\"authorUuid\":\"77777777-7777-7777-7777-777777777777\",\"cells\":[{\"x\":-2,\"y\":1,\"z\":3,\"block\":\"minecraft:stone\",\"metadata\":0,\"item\":\"minecraft:stone\",\"damage\":0,\"count\":1}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] image = NativeBlueprintCodecRegression.png();
            Path original = dir.resolve(id + ".json");
            HologramBlueprintArtifact.writePair(original, json, image);
            byte[] originalJson = Files.readAllBytes(original),
                originalPng = Files.readAllBytes(HologramBlueprintArtifact.previewPath(original));
            HologramNativeBlueprintStorage.Prepared prepared = library.prepareNativeUpgrade(id, () -> true);
            require(
                HologramBlueprintFiles.resolve(dir, id)
                    .equals(original),
                "preparation does not activate unconsumable generation");
            require(
                library.list()
                    .size() == 1
                    && library.list()
                        .get(0).id.equals(id),
                "staging no duplicate visible cards");
            try (NativeBlueprintDefinition d = HologramNativeBlueprintStorage.openPrepared(prepared)) {
                require(d.cells() == 1 && d.elementAt(-2, 1, 3) > 0, "game seam owns native definition directly");
            }
            Path cli = game.resolve("cli");
            NativeBlueprintCli.run(
                new String[] { "convert", original.toString(), cli.toString(), id,
                    HologramBlueprintArtifact.previewPath(original)
                        .toString() });
            NativeBlueprintPairStore.Receipt cliPair = NativeBlueprintPairStore.receipt(cli, id);
            require(
                Arrays.equals(Files.readAllBytes(prepared.source), Files.readAllBytes(cliPair.source)),
                "game and CLI dispatch bytes agree");
            byte[] sourceBytes = Files.readAllBytes(prepared.source), preparedPng = Files.readAllBytes(prepared.png);
            require(
                prepared.revision.equals(HologramBlueprintArtifact.hash(sourceBytes))
                    && prepared.pngHash.equals(HologramBlueprintArtifact.hash(preparedPng)),
                "exact pair receipt");
            com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPng.verify(preparedPng, prepared.revision);
            require(
                Arrays.equals(
                    com.miaokatze.gtmb.hologram.HologramBlueprintArtifactRegression.idat(originalPng),
                    com.miaokatze.gtmb.hologram.HologramBlueprintArtifactRegression.idat(preparedPng)),
                "identical compressed pixels");

            // Failure *after* the atomic index replace is distinct from post-commit cleanup.
            try {
                HologramNativeBlueprintStorage.activate(
                    dir,
                    prepared,
                    prepared.revision,
                    () -> true,
                    s -> {
                        if (s.equals("index-replaced"))
                            throw new java.io.IOException("injected post-rename index failure");
                    });
                throw new AssertionError("index failure hidden");
            } catch (java.io.IOException expected) {
                require(
                    expected.getMessage()
                        .contains("injected"),
                    "exact injected fault");
            }
            NativeBlueprintPairStore.recover(dir);
            HologramClientLibrary fresh = new HologramClientLibrary(game);
            require(
                HologramBlueprintFiles.resolve(dir, id)
                    .equals(original),
                "late failure restores original slot");
            require(
                Arrays.equals(originalJson, Files.readAllBytes(original))
                    && Arrays.equals(originalPng, Files.readAllBytes(HologramBlueprintArtifact.previewPath(original))),
                "late failure retains exact pair");
            require(
                fresh.list()
                    .size() == 1
                    && fresh.list()
                        .get(0).id.equals(id),
                "ordinary list after rollback");
            // Test-owned acceptance only. The regular editor must not activate its unconsumable candidate.
            HologramNativeBlueprintStorage.activate(dir, prepared, prepared.revision, () -> true);
            require(
                HologramBlueprintFiles.resolve(dir, id)
                    .toString()
                    .endsWith(".gtnb"),
                "native generation resolves");
            require(
                fresh.list()
                    .size() == 1
                    && fresh.list()
                        .get(0).id.equals(id)
                    && fresh.list()
                        .get(0).cells == 1,
                "ordinary list after retry");
            require(
                HologramBlueprintFiles.managed(dir)
                    .size() == 1,
                "preserved pair not re-adopted");
            try (NativeBlueprintDefinition nativeSource = fresh.readPrivateEditorSource(id)
                .openNative()) {
                require(
                    nativeSource.revision()
                        .equals(prepared.revision),
                    "formal private read gives accepted native revision");
                try {
                    fresh.readPrivateEditorSource(id)
                        .json();
                    throw new AssertionError("native read leaked into old editor");
                } catch (java.io.IOException expected) {}
                String second = "local_88888888-8888-8888-8888-888888888888";
                HologramNativeBlueprintStorage.Prepared saved = fresh
                    .prepareEditorSave(nativeSource, originalPng, second, () -> true, null, true);
                require(
                    fresh.list()
                        .size() == 1,
                    "prepared native save not visible");
                try (NativeBlueprintDefinition reopened = HologramNativeBlueprintStorage.openPrepared(saved)) {
                    require(
                        reopened.revision()
                            .equals(nativeSource.revision()) && reopened.elementAt(-2, 1, 3) > 0,
                        "native save/open exact revision and geometry");
                }
            }
            require(
                Arrays.equals(originalJson, Files.readAllBytes(original))
                    && Arrays.equals(originalPng, Files.readAllBytes(HologramBlueprintArtifact.previewPath(original))),
                "original pair exact preserved");
            NativeBlueprintPairStore.recover(dir);
            HologramClientLibrary reopened = new HologramClientLibrary(game);
            require(
                reopened.list()
                    .size() == 1
                    && reopened.list()
                        .get(0).id.equals(id),
                "normal library discovery after reopen");
            portableRetry(game, id, json, image);
            namedTransaction(game, id, json, image);
            unicodePhysicalNames(game, id, json, image);
            retainedCollectionMirror(game, id, json, image);
            realMixedFixture(game);
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(game)) {
                files.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
            }
        }
    }

    private static void portableRetry(Path game, String id, byte[] json, byte[] image) throws Exception {
        HologramClientLibrary store = new HologramClientLibrary(game.resolve("portable"));
        Path dir = store.directory();
        Files.createDirectories(dir);
        Path old = HologramBlueprintFiles.targetLocal(dir, id, json);
        HologramBlueprintArtifact.PairWrite pending = HologramBlueprintArtifact
            .writeLocalPairPending(old, json, image, () -> true);
        HologramBlueprintFiles.track(dir, id, pending);
        pending.commit();
        require(old.equals(store.savedPath(id)), "ordinary file resolver owns portable fixture");
        require(
            !old.getFileName()
                .toString()
                .equals(id + ".json"),
            "fixture actually uses portable hash name");
        byte[] original = Files.readAllBytes(old),
            pixels = Files.readAllBytes(HologramBlueprintArtifact.previewPath(old));
        HologramNativeBlueprintStorage.Prepared p = store.prepareNativeUpgrade(id, () -> true);
        try {
            HologramNativeBlueprintStorage.activate(
                dir,
                p,
                p.revision,
                () -> true,
                s -> { if (s.equals("index-replaced")) throw new java.io.IOException("portable-injected"); });
            throw new AssertionError("portable CAS fault not observed");
        } catch (java.io.IOException error) {
            require(
                error.getMessage()
                    .contains("portable-injected"),
                "portable fault reached");
        }
        require(
            store.savedPath(id)
                .equals(old),
            "original portable mapping retained");
        HologramNativeBlueprintStorage.activate(dir, p, p.revision, () -> true);
        NativeBlueprintPairStore.recover(dir);
        require(
            HologramBlueprintFiles.managed(dir)
                .size() == 1,
            "adoption cannot create portable fallback UUID");
        HologramClientLibrary fresh = new HologramClientLibrary(game.resolve("portable"));
        require(
            fresh.list()
                .size() == 1
                && fresh.list()
                    .get(0).id.equals(id),
            "one logical card survived restart");
        require(
            Arrays.equals(original, Files.readAllBytes(old))
                && Arrays.equals(pixels, Files.readAllBytes(HologramBlueprintArtifact.previewPath(old))),
            "both portable originals exact");
    }

    private static void namedTransaction(Path game, String id, byte[] json, byte[] image) throws Exception {
        Path gameRoot = game.resolve("named");
        HologramClientLibrary store = new HologramClientLibrary(gameRoot);
        Path dir = store.directory();
        Files.createDirectories(dir);
        Path old = dir.resolve("FieldReport.json"), oldPng = HologramBlueprintArtifact.previewPath(old);
        HologramBlueprintArtifact.writePair(old, json, image);
        byte[] exactOld = Files.readAllBytes(old), exactImage = Files.readAllBytes(oldPng);
        require(
            store.list()
                .size() == 1
                && store.list()
                    .get(0).id.equals(id),
            "loose named source appears under its preexisting UUID");
        HologramNativeBlueprintStorage.Prepared staged = store.prepareNativeUpgrade("FieldReport", () -> true);
        require(
            staged.logicalId.equals(id) && store.list()
                .size() == 1,
            "input name does not take ownership of the logical ID");
        var first = HologramNativeBlueprintStorage.activate(
            dir,
            staged,
            staged.revision,
            () -> true,
            s -> {
                if (s.equals("after-catalog")) throw new java.io.IOException("fault after publish acknowledgement");
            });
        require(journalCount(dir) == 1, "committed publish cleanup intentionally interrupted");
        HologramClientLibrary cold = new HologramClientLibrary(gameRoot);
        try (var byName = cold.readNamedEditorSource("FieldReport.json")
            .openNative();
            var byId = cold.readPrivateEditorSource(id)
                .openNative()) {
            require(
                byName.revision()
                    .equals(staged.revision)
                    && byId.revision()
                        .equals(staged.revision),
                "both real open routes read the same revision");
        }
        require(journalCount(dir) == 0, "ordinary cold open reconciles committed publication");
        require(
            cold.list()
                .size() == 1
                && cold.list()
                    .get(0).id.equals(id)
                && HologramBlueprintFiles.managed(dir)
                    .size() == 1,
            "active named source + retained original appear exactly once");
        require(
            cold.commitSelectedNativeUpgrade(id, () -> true)
                .contains("已使用"),
            "repeated conversion of the selected card is idempotent");
        // Same source revision, newly captured pixels: both files get a different complete-generation name.
        byte[] freshImage = thumbnail(0xff0000ff);
        HologramNativeBlueprintStorage.Prepared thumb;
        try (var opened = cold.readPrivateEditorSource(id)
            .openNative()) {
            thumb = cold.prepareEditorSave(
                opened,
                freshImage,
                id,
                () -> true,
                cold.readPrivateEditorSource(id)
                    .nativePair(),
                false);
        }
        Path catalog = dir.resolve(".gtmb-native-catalog.properties");
        byte[] beforeThumb = Files.readAllBytes(catalog);
        injected(
            () -> HologramNativeBlueprintStorage.activate(
                dir,
                thumb,
                thumb.revision,
                () -> true,
                s -> { if (s.equals("index-replaced")) throw new java.io.IOException("injected late thumb CAS"); }));
        require(
            Arrays.equals(beforeThumb, Files.readAllBytes(catalog)) && Files.isRegularFile(first.source)
                && Files.isRegularFile(first.png),
            "failed same-revision preview write leaves exact previous index and pair");
        var updated = HologramNativeBlueprintStorage.activate(
            dir,
            thumb,
            thumb.revision,
            () -> true,
            s -> {
                if (s.equals("after-catalog")) throw new java.io.IOException("fault after replacement acknowledgement");
            });
        require(
            updated.physicalHash.equals(first.physicalHash) && !updated.pngHash.equals(first.pngHash)
                && !updated.source.equals(first.source),
            "thumbnail-only save publishes same source with a new exact image");
        cold = new HologramClientLibrary(gameRoot);
        require(
            cold.list()
                .size() == 1 && journalCount(dir) == 0
                && !Files.exists(first.source)
                && !Files.exists(first.png),
            "ordinary cold list reconciles owned retired thumbnail pair");
        require(
            Arrays.equals(
                com.miaokatze.gtmb.hologram.HologramBlueprintArtifactRegression.idat(freshImage),
                com.miaokatze.gtmb.hologram.HologramBlueprintArtifactRegression.idat(Files.readAllBytes(updated.png))),
            "replacement retains exact new screenshot pixels");
        cold.upgradeLegacy();
        require(
            Arrays.equals(exactOld, Files.readAllBytes(old)) && Arrays.equals(exactImage, Files.readAllBytes(oldPng)),
            "bulk historic upgrade cannot republish or delete the retained original");
        byte[] beforeDelete = Files.readAllBytes(catalog);
        HologramClientLibrary owner = cold;
        injected(
            () -> owner.delete(
                id,
                s -> { if (s.equals("index-replaced")) throw new java.io.IOException("injected late delete CAS"); }));
        require(
            Arrays.equals(beforeDelete, Files.readAllBytes(catalog)) && owner.list()
                .size() == 1 && Files.isRegularFile(updated.source) && Files.isRegularFile(updated.png),
            "native delete failed after atomic index rename, rollback restores exact active pair");
        owner.delete(
            id,
            s -> {
                if (s.equals("after-catalog")) throw new java.io.IOException("fault after delete acknowledgement");
            });
        require(
            journalCount(dir) == 1 && NativeBlueprintPairStore.receipt(dir, id) == null
                && NativeBlueprintPairStore.receipt(dir, "FieldReport") == null,
            "delete removes both active and alias before filesystem retirement");
        cold = new HologramClientLibrary(gameRoot);
        require(
            cold.list()
                .isEmpty()
                && HologramBlueprintFiles.managed(dir)
                    .isEmpty()
                && journalCount(dir) == 0
                && !Files.exists(updated.source)
                && !Files.exists(updated.png),
            "ordinary cold list reconciles committed deletion without resurrection");
        require(
            Arrays.equals(exactOld, Files.readAllBytes(old)) && Arrays.equals(exactImage, Files.readAllBytes(oldPng))
                && !Files.isRegularFile(HologramBlueprintFiles.resolve(dir, "FieldReport")),
            "retained original invisible and untouched");
        // The still-default legacy saver must also retain the archived input when reusing the ID.
        com.google.gson.JsonObject ordinary = new com.google.gson.JsonParser()
            .parse(new String(json, StandardCharsets.UTF_8))
            .getAsJsonObject();
        ordinary.getAsJsonObject("blueprint")
            .addProperty("schema", 2);
        ordinary.getAsJsonObject("blueprint")
            .addProperty("controllerType", "area");
        ordinary.getAsJsonObject("blueprint")
            .add(
                "nativeBlueprint",
                new com.google.gson.JsonParser().parse(
                    "{\"formatVersion\":1,\"gtmbVersion\":\"0.3.46\",\"gtnhVersion\":\"2.9.0\","
                        + "\"gtnhVersionSource\":\"dreamcoremod.displayedModpackVersion\"}"));
        cold.save(
            ordinary.toString()
                .getBytes(StandardCharsets.UTF_8),
            image,
            id);
        require(
            Arrays.equals(exactOld, Files.readAllBytes(old)) && Arrays.equals(exactImage, Files.readAllBytes(oldPng))
                && cold.list()
                    .size() == 1,
            "ordinary legacy save may reuse absent ID without replacing archived originals");
        cold.delete(id);
        // A deliberately reused logical slot is available, even with the exact same source bytes.
        try (var frozen = HologramNativeBlueprintStorage.openPrepared(staged)) {
            var again = cold.prepareEditorSave(frozen, image, id, () -> true, null, true);
            HologramNativeBlueprintStorage.activate(dir, again, again.revision, () -> true);
        }
        require(
            new HologramClientLibrary(gameRoot).list()
                .size() == 1 && NativeBlueprintPairStore.receipt(dir, "FieldReport") == null,
            "same-slot reuse does not resurrect retired name or original");
        System.out.println(
            "[GTMB-NATIVE-STORAGE] typed-name identity, exact thumbnail CAS, rollback, cold recovery, native deletion and slot reuse PASS");
    }

    private static void unicodePhysicalNames(Path game, String id, byte[] json, byte[] image) throws Exception {
        HologramClientLibrary store = new HologramClientLibrary(game.resolve("unicode"));
        Path dir = store.directory();
        Files.createDirectories(dir);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 65; i++) name.append("矿");
        name.append("🌿");
        String text = new String(json, StandardCharsets.UTF_8).replace("Native Stage", name.toString());
        Path source = dir.resolve(id + ".json");
        HologramBlueprintArtifact.writePair(source, text.getBytes(StandardCharsets.UTF_8), image);
        var candidate = store.prepareNativeUpgrade(id, () -> true);
        var active = HologramNativeBlueprintStorage.activate(dir, candidate, candidate.revision, () -> true);
        require(
            active.source.getFileName()
                .toString()
                .getBytes(StandardCharsets.UTF_8).length <= 255
                && active.png.getFileName()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8).length <= 255,
            "safe UTF-8 physical component budget");
        require(
            store.list()
                .get(0).name.equals(name.toString()),
            "full logical CJK/emoji title preserved");
        Path cli = game.resolve("unicode-cli");
        NativeBlueprintCli.run(
            new String[] { "convert", source.toString(), cli.toString(), id,
                HologramBlueprintArtifact.previewPath(source)
                    .toString() });
        var same = NativeBlueprintPairStore.receipt(cli, id);
        require(
            same.source.getFileName()
                .equals(active.source.getFileName()) && same.pngHash.equals(active.pngHash),
            "game/CLI identical portable path and binding");
        // The long truncated prefix, source and preview can all be identical without merging independent slots.
        String other = "local_88888888-8888-8888-8888-888888888888";
        NativeBlueprintCli.run(
            new String[] { "convert", source.toString(), cli.toString(), other,
                HologramBlueprintArtifact.previewPath(source)
                    .toString() });
        require(
            !NativeBlueprintPairStore.receipt(cli, other).source.equals(same.source),
            "same prefix and physical content in another logical slot differs");
        require(
            NativeBlueprintPairStore.portableStem(new String(new char[65]).replace("\0", "🌿"))
                .getBytes(StandardCharsets.UTF_8).length == 80,
            "emoji truncation preserves complete code points");
    }

    private static byte[] thumbnail(int color) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            640,
            640,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(10, 10, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static long journalCount(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files.filter(
                p -> p.getFileName()
                    .toString()
                    .startsWith(".gtmb-native-pending-"))
                .count();
        }
    }

    private interface Action {

        void run() throws Exception;
    }

    private static void injected(Action action) throws Exception {
        try {
            action.run();
        } catch (java.io.IOException e) {
            require(
                e.getMessage()
                    .startsWith("injected"),
                "expected injected failure: " + e);
            return;
        }
        throw new AssertionError("injected fault went unreported");
    }

    /** Actual library workers, not an in-memory duplicate filter. Both archives stay byte-exact. */
    private static void retainedCollectionMirror(Path parent, String id, byte[] json, byte[] image) throws Exception {
        Path game = parent.resolve("collection");
        HologramClientLibrary lib = new HologramClientLibrary(game);
        Path dir = lib.directory();
        Files.createDirectories(dir);
        Path source = dir.resolve(id + ".json"), collection = dir.resolve("hologram-presets.json");
        HologramBlueprintArtifact.writePair(source, json, image);
        var parsed = new com.google.gson.JsonParser().parse(new String(json, StandardCharsets.UTF_8))
            .getAsJsonObject();
        var member = parsed.getAsJsonObject("blueprint");
        var clone = new com.google.gson.JsonObject();
        // A mirror may reorder object keys and use a bare UUID. Neither changes ownership/geometry.
        var keys = new java.util.ArrayList<String>();
        for (var entry : member.entrySet()) keys.add(entry.getKey());
        java.util.Collections.reverse(keys);
        for (String key : keys) clone.add(key, member.get(key));
        clone.addProperty("id", id.substring("local_".length()));
        // Keep exact same raw ID in conflicting second row to exercise list/read/delete discrimination.
        var other = new com.google.gson.JsonParser().parse(clone.toString())
            .getAsJsonObject();
        other.getAsJsonArray("cells")
            .get(0)
            .getAsJsonObject()
            .addProperty("block", "minecraft:dirt");
        String cloneId = clone.get("id")
            .getAsString(), entry = "legacy:" + cloneId;
        byte[] cloneOnly = ("{\"presets\":[" + clone + "]}").getBytes(StandardCharsets.UTF_8);
        byte[] both = ("{\"presets\":[" + clone + "," + other + "]}").getBytes(StandardCharsets.UTF_8);
        Path png = HologramBlueprintArtifact.previewPath(source);
        byte[] original = Files.readAllBytes(source), originalPng = Files.readAllBytes(png);
        Files.write(collection, cloneOnly);
        require(
            lib.list()
                .size() == 1 && lib.listLegacy()
                    .isEmpty(),
            "ordinary legacy merged listing unchanged");
        require(
            lib.readPrivateEditorSource(id)
                .nativePair() == null && lib.savedPath(id)
                    .equals(source),
            "default stays legacy after stage");
        var staged = lib.prepareNativeUpgrade(id, () -> true);
        HologramNativeBlueprintStorage.activate(dir, staged, staged.revision, () -> true);
        require(
            lib.list()
                .size() == 1 && lib.listLegacy()
                    .isEmpty(),
            "accepted native + exact mirror are one card");
        Files.write(collection, both);
        require(
            lib.list()
                .size() == 1
                && lib.listLegacy()
                    .size() == 1
                && lib.listLegacy()
                    .get(0).id.equals(entry),
            "same UUID but different business content remains available");
        require(
            new String(lib.readLegacyDocument(entry), StandardCharsets.UTF_8).contains("minecraft:dirt"),
            "legacy read selects visible conflict even when exact mirror comes first");
        try {
            lib.delete(
                id,
                stage -> {
                    if (stage.equals("index-replaced")) throw new java.io.IOException("mirror-delete-rollback");
                });
            throw new AssertionError("delete rollback failure not delivered");
        } catch (java.io.IOException expected) {
            require(
                expected.getMessage()
                    .contains("mirror-delete-rollback"),
                "fault crossed actual delete transaction");
        }
        HologramClientLibrary cold = new HologramClientLibrary(game);
        require(
            cold.list()
                .size() == 1
                && cold.listLegacy()
                    .size() == 1
                && Arrays.equals(Files.readAllBytes(collection), both),
            "rollback preserves active pair, conflict and exact archive");
        cold.delete(id);
        cold = new HologramClientLibrary(game);
        require(
            cold.list()
                .isEmpty()
                && cold.listLegacy()
                    .size() == 1,
            "cold delete never resurrects mirror or hides conflict");
        require(
            new String(cold.readLegacyDocument(entry), StandardCharsets.UTF_8).contains("minecraft:dirt"),
            "visible conflict survives native delete");
        require(
            Arrays.equals(Files.readAllBytes(source), original) && Arrays.equals(Files.readAllBytes(png), originalPng)
                && Arrays.equals(Files.readAllBytes(collection), both),
            "native delete never mutates loose or collection bytes");
        cold.delete(entry); // Existing explicit delete of the visible collection row must not remove the hidden first
                            // row.
        require(
            cold.listLegacy()
                .isEmpty()
                && new String(Files.readAllBytes(collection), StandardCharsets.UTF_8).contains("minecraft:stone"),
            "explicit conflict delete targets visible row only");
        Files.write(collection, cloneOnly);
        require(
            cold.listLegacy()
                .isEmpty(),
            "copy reimported verbatim as retained mirror remains hidden");
        try {
            cold.readLegacyDocument(entry);
            throw new AssertionError("hidden mirror reopens by stale ID");
        } catch (java.io.IOException expected) {
            require(
                expected.getMessage()
                    .contains("不存在"),
                "stale exact mirror private open refuses");
        }
        byte[] compactCollection = com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec
            .recompress(cloneOnly, HologramBlueprintArtifact.MAX_LIBRARY_JSON_BYTES);
        Files.write(collection, compactCollection);
        require(
            cold.listLegacy()
                .isEmpty() && Arrays.equals(Files.readAllBytes(collection), compactCollection),
            "dictionary-encoded historic clone matches expanded original without changing file");
        byte[] differentEnvelope = ("{\"opaqueArchivePolicy\":\"independent\",\"presets\":[" + clone + "]}")
            .getBytes(StandardCharsets.UTF_8);
        Files.write(collection, differentEnvelope);
        require(
            cold.listLegacy()
                .size() == 1 && Arrays.equals(Files.readAllBytes(collection), differentEnvelope),
            "same geometry with an unknown differing envelope is preserved, not guessed to be a mirror");

        // Simulate a catalog entry from the last checkpoint: previous source SHA recorded, mirror receipt absent.
        Path oldGame = parent.resolve("collection-checkpoint");
        HologramClientLibrary old = new HologramClientLibrary(oldGame);
        Path oldDir = old.directory();
        Files.createDirectories(oldDir);
        Path oldSource = oldDir.resolve(id + ".json");
        HologramBlueprintArtifact.writePair(oldSource, json, image);
        byte[] oldExact = Files.readAllBytes(oldSource);
        var oldStaged = old.prepareNativeUpgrade(id, () -> true);
        NativeBlueprintPairStore
            .publish(oldDir, id, Files.readAllBytes(oldStaged.source), image, oldSource, oldExact, () -> true);
        Path oldCollection = oldDir.resolve("hologram-presets.json");
        Files.write(oldCollection, cloneOnly);
        require(
            old.listLegacy()
                .isEmpty(),
            "checkpoint exact loose origin recovers mirror proof without catalog rewrite");
        old.delete(
            id,
            stage -> { if (stage.equals("after-catalog")) throw new java.io.IOException("mirror journal crash"); });
        var oldCold = new HologramClientLibrary(oldGame);
        require(
            oldCold.list()
                .isEmpty()
                && oldCold.listLegacy()
                    .isEmpty()
                && Arrays.equals(Files.readAllBytes(oldCollection), cloneOnly)
                && Arrays.equals(Files.readAllBytes(oldSource), oldExact),
            "old native catalog can delete and recover exact mirror tombstone on ordinary cold open");
        System.out.println(
            "[GTMB-NATIVE-COLLECTION] exact clone retained/invisible; distinct same-UUID cell survives; native rollback, cold recovery and explicit conflict delete verified");
    }

    private static void realMixedFixture(Path game) throws Exception {
        String metadata = System.getProperty("gtmb.reviewedMetadata.fixture", "");
        if (metadata.isEmpty()) return;
        Path sample = java.nio.file.Paths.get(metadata)
            .toAbsolutePath()
            .getParent();
        String id = "local_9861e912-720f-5417-a01a-6e6e28859680";
        HologramClientLibrary library = new HologramClientLibrary(game.resolve("mixed"));
        Path dir = library.directory();
        Files.createDirectories(dir);
        Path source = dir.resolve(id + ".json"), png = dir.resolve(id + ".png");
        Files.copy(sample.resolve(id + ".json"), source);
        Files.copy(sample.resolve(id + ".png"), png);
        HologramNativeBlueprintStorage.Prepared prepared = library.prepareNativeUpgrade(id, () -> true);
        Path cli = game.resolve("mixed-cli");
        NativeBlueprintCli.run(new String[] { "convert", source.toString(), cli.toString(), id, png.toString() });
        NativeBlueprintPairStore.Receipt receipt = NativeBlueprintPairStore.receipt(cli, id);
        require(
            Arrays.equals(Files.readAllBytes(prepared.source), Files.readAllBytes(receipt.source)),
            "123369 game and CLI native bytes");
        require(
            Arrays.equals(Files.readAllBytes(prepared.png), Files.readAllBytes(receipt.png)),
            "123369 game and CLI PNG bytes");
        require(
            Arrays.equals(
                com.miaokatze.gtmb.hologram.HologramBlueprintArtifactRegression.idat(Files.readAllBytes(png)),
                com.miaokatze.gtmb.hologram.HologramBlueprintArtifactRegression.idat(Files.readAllBytes(receipt.png))),
            "123369 original pixels");
        try (NativeBlueprintDefinition d = HologramNativeBlueprintStorage.openPrepared(prepared)) {
            require(d.cells() == 123369, "123369 actual real pair");
        }
        System.out.println(
            "[GTMB-NATIVE] real mixed pair: " + Files.size(
                prepared.source) + " source bytes; " + Files.size(prepared.png) + " PNG bytes; " + prepared.revision);
    }

    private static void require(boolean v, String m) {
        if (!v) throw new AssertionError(m);
    }
}
