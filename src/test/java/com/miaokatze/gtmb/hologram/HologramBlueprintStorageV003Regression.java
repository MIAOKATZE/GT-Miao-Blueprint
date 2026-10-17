package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.miaokatze.gtmb.testutil.BlueprintArtifactTestData;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Byte-bounded large libraries and transactional deletion, using only temporary files. */
final class HologramBlueprintStorageV003Regression {

    private HologramBlueprintStorageV003Regression() {}

    static void run() {
        Path directory = null;
        Field cache = null;
        Object previousCache = null;
        try {
            SimpleAssert.eq(
                128 * 1024 * 1024,
                HologramPresets.MAX_LIBRARY_BYTES,
                "logical library has a separate bounded 128MiB capacity");
            cache = HologramPresets.class.getDeclaredField("cached");
            cache.setAccessible(true);
            previousCache = cache.get(null);
            directory = Files.createTempDirectory("gtmb-large-blueprint-");
            Path share = directory.resolve("share.json"), library = directory.resolve("library.json");
            HologramPresets.Preset large = preset("large_a");
            large.prompt = "整体提示";
            for (int i = 0; i < 64000; i++) {
                HologramPresets.Cell cell = new HologramPresets.Cell();
                cell.block = "missing:stone";
                cell.x = i;
                if (i < 500) {
                    NBTTagCompound tile = new NBTTagCompound();
                    tile.setInteger("fixtureIndex", i);
                    tile.setString("retainedPayload", "capacity fixture keeps all selected tile data");
                    cell.tileNbt = HologramBlueprintNbt.encodeCaptured(tile);
                }
                large.cells.add(cell);
            }
            large.cells.get(0).prompt = "方块提示";
            HologramPresets.HintArea hint = new HologramPresets.HintArea();
            hint.x2 = 100;
            hint.prompt = "区域提示";
            large.hintAreas.add(hint);
            HologramPresets.Structure structure = new HologramPresets.Structure();
            structure.id = "main";
            structure.main = 128;
            structure.mainExplicit = true;
            structure.noHatches = true;
            structure.channels.put("height", 128);
            large.structures.add(structure);
            HologramBlueprintVersions.stamp(large);
            SimpleAssert.that(
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(large)
                    .getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024,
                "large structure reproduces pretty JSON exceeding the old share budget");
            HologramBlueprintSharing.write(share, large, BlueprintArtifactTestData.previewPng());
            SimpleAssert.that(
                Files.size(share) < HologramBlueprintSharing.MAX_BYTES,
                "64k cells and 500 tile payloads fit the expanded bounded share budget");
            SimpleAssert.that(Files.size(share) > 8 * 1024 * 1024, "compact 64k fixture exceeds the old 8MiB limit");
            HologramPresets.Preset restored = HologramBlueprintSharing.read(share);
            SimpleAssert.eq(64000, restored.cells.size(), "all large structure cells survive");
            for (int i = 0; i < 500; i++) SimpleAssert.eq(
                large.cells.get(i).tileNbt,
                restored.cells.get(i).tileNbt,
                "tile payload survives full-capacity share round trip " + i);
            SimpleAssert.eq("整体提示", restored.prompt, "overall prompt survives");
            SimpleAssert.eq("方块提示", restored.cells.get(0).prompt, "cell prompt survives");
            SimpleAssert.eq("区域提示", restored.hintAreas.get(0).prompt, "area prompt survives");
            SimpleAssert.eq(128, restored.structures.get(0).main, "structure size has no arbitrary 64 limit");
            SimpleAssert.eq(128, restored.structures.get(0).channels.get("height"), "independent channel survives");
            SimpleAssert.that(restored.structures.get(0).noHatches, "independent hatch exclusion survives");

            Method write = HologramPresets.class.getDeclaredMethod("write", Path.class, List.class);
            Method remove = HologramPresets.class.getDeclaredMethod("remove", List.class, String.class);
            Method read = HologramPresets.class.getDeclaredMethod("readLibrary", Path.class);
            write.setAccessible(true);
            remove.setAccessible(true);
            read.setAccessible(true);
            HologramPresets.Preset second = HologramPresets.copy(large);
            second.id = "large_b";
            List<HologramPresets.Preset> entries = new ArrayList<>(Arrays.asList(large, second));
            write.invoke(null, library, entries);
            SimpleAssert.that(
                Files.size(library) > 8 * 1024 * 1024,
                "two bounded blueprints may exceed the old whole-library limit");
            SimpleAssert.eq(2, ((List<?>) read.invoke(null, library)).size(), "larger library reloads completely");
            byte[] original = Files.readAllBytes(library);
            remove.invoke(null, entries, "large_a");
            SimpleAssert.eq(1, entries.size(), "deletion removes exactly its selected ID");
            SimpleAssert.that(HologramPresets.get("large_a") != null, "staging deletion leaves cache unchanged");
            expectIo(() -> remove.invoke(null, entries, "missing"));
            Path blocked = directory.resolve("blocked");
            Files.createDirectory(blocked);
            Files.write(blocked.resolve("keep"), new byte[] { 1 });
            expectIo(() -> write.invoke(null, blocked, entries));
            SimpleAssert.that(HologramPresets.get("large_a") != null, "failed deletion write preserves cache");
            SimpleAssert.that(Arrays.equals(original, Files.readAllBytes(library)), "failed deletion preserves disk");
            write.invoke(null, library, entries);
            SimpleAssert.that(HologramPresets.get("large_a") == null, "successful deletion publishes cache");
            SimpleAssert.that(HologramPresets.get("large_b") != null, "deletion preserves other entries");
            Files.delete(blocked.resolve("keep"));
            Files.delete(blocked);
            Files.delete(share);
            Files.delete(HologramBlueprintArtifact.previewPath(share));
            Files.delete(library);

            try (java.nio.channels.FileChannel oversized = java.nio.channels.FileChannel
                .open(library, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
                oversized.position(HologramPresets.MAX_LIBRARY_BYTES);
                oversized.write(java.nio.ByteBuffer.wrap(new byte[] { 1 }));
            }
            expectIo(() -> read.invoke(null, library));
            Files.delete(library);

            expectIo(() -> HologramBlueprintSharing.encodeBounded(new Gson(), "猫猫猫", 8));
            expectIo(() -> HologramBlueprintSharing.boundedJson("\"猫猫猫\"", 8));
            NBTTagCompound data = new NBTTagCompound();
            data.setByteArray("compressedBomb", new byte[4 * 1024 * 1024 - 1024]);
            String encoded = HologramBlueprintNbt.encodeCaptured(data);
            HologramPresets.Preset aggregate = preset("aggregate");
            for (int i = 0; i < 17; i++) {
                HologramPresets.Cell cell = new HologramPresets.Cell();
                cell.block = "missing:stone";
                cell.x = i;
                cell.tileNbt = encoded;
                aggregate.cells.add(cell);
            }
            expectIo(() -> HologramPresets.validate(aggregate));
        } catch (Exception failure) {
            throw new AssertionError("large blueprint storage and deletion regression", failure);
        } finally {
            try {
                if (cache != null) cache.set(null, previousCache);
                if (directory != null) {
                    Files.deleteIfExists(directory.resolve("blocked/keep"));
                    Files.deleteIfExists(directory.resolve("blocked"));
                    Files.deleteIfExists(directory.resolve("share.json"));
                    Files.deleteIfExists(directory.resolve("share.png"));
                    Files.deleteIfExists(directory.resolve("library.json"));
                    Files.deleteIfExists(directory);
                }
            } catch (Exception failure) {
                throw new AssertionError("large blueprint fixture cleanup", failure);
            }
        }
    }

    private static HologramPresets.Preset preset(String id) {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.id = id;
        preset.name = "Storage fixture";
        preset.schema = 2;
        return preset;
    }

    private interface IoAction {

        void run() throws Exception;
    }

    private static void expectIo(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected a bounded input or IO failure");
        } catch (InvocationTargetException failure) {
            if (!(failure.getCause() instanceof IOException)) throw failure;
        } catch (IOException expected) {
            // Required checked rejection.
        }
    }
}
