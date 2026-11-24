// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

/** Pure prepared-stock lease, directory and minimal NBT regressions; never starts Minecraft. */
public final class HologramEbfPreparedStockRegression {

    private static final String RUN = "10000000-0000-4000-8000-000000000001";
    private static final UUID ACTOR = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final String TOOL = "30000000-0000-4000-8000-000000000003";
    private static final int ITEM = 961;
    private static final String INSTANCE = "fixed-ebf-test";
    private static final String BAD_PIN = "0000000000000000000000000000000000000000000000000000000000000000";

    private HologramEbfPreparedStockRegression() {}

    public static void run() throws Exception {
        var canonical = HologramEbfPreparedStock.expectedData(TOOL, ITEM);
        byte[] bytes = encoded(canonical);
        String digest = HologramBlueprintArtifact.hash(bytes);
        check(canonical.equals(HologramEbfPreparedStock.validate(bytes, digest, ITEM)), "exact vanilla seed");
        reject(() -> HologramEbfPreparedStock.validate(bytes, BAD_PIN, ITEM), "wrong pinned digest");
        reject(() -> HologramEbfPreparedStock.validate(bytes, digest, ITEM + 1), "changed expected runtime item map");
        reject(() -> HologramEbfPreparedStock.validate(new byte[4097], digest, ITEM), "oversized seed");
        for (String unknown : new String[] { "UUIDMost", "ForgeData", "abilities", "playerGameType", "EnderItems" }) {
            var changed = copy(canonical);
            changed.setInteger(unknown, 0);
            rejectShape(changed, "no donor state/authority: " + unknown);
        }
        var wrongSlot = copy(canonical);
        wrongSlot.setInteger("SelectedItemSlot", 0);
        rejectShape(wrongSlot, "wrong selected slot");
        var badItem = copy(canonical);
        stack(badItem).setShort("Count", (short) 1);
        rejectShape(badItem, "wrong count type");
        var secondItem = copy(canonical);
        secondItem.getTagList("Inventory", 10)
            .appendTag(stack(canonical).copy());
        rejectShape(secondItem, "only one item row");
        for (String unwanted : new String[] { "gtitHologramDraft", "gtitHologramPreset", "display", "testlabBook" }) {
            var changed = copy(canonical);
            stack(changed).getCompoundTag("tag")
                .setTag(unwanted, new NBTTagCompound());
            rejectShape(changed, "no extra stack state: " + unwanted);
        }
        var nondefault = copy(canonical);
        stack(nondefault).getCompoundTag("tag")
            .getCompoundTag("gtitHologramMaterials")
            .setInteger("priority", 2);
        rejectShape(nondefault, "not the actual default tool");
        var invalidTool = copy(canonical);
        stack(invalidTool).getCompoundTag("tag")
            .setString("gtitHologramToolId", "malformed");
        rejectShape(invalidTool, "invalid tool identity");
        check(
            ACTOR.equals(
                HologramEbfPreparedStock.loginActor(
                    ACTOR.toString()
                        .replace("-", ""))),
            "compact launcher UUID");
        reject(() -> HologramEbfPreparedStock.loginActor("not-a-profile"), "no name-derived actor");
        reject(() -> HologramEbfPreparedStock.loginActor("1-2-3-4-5"), "no lenient truncated UUID");

        Path base = Files.createTempDirectory("gtmb-ebf-prepared-stock-")
            .toRealPath();
        try {
            Path receipt = Files.createDirectories(
                base.resolve("receipts")
                    .resolve(INSTANCE));
            Path game = Files.createDirectories(
                base.resolve("launcher-data")
                    .resolve("instances")
                    .resolve(INSTANCE)
                    .resolve(".minecraft"));
            Path saves = Files.createDirectory(game.resolve("saves"));
            Path world = Files.createDirectory(saves.resolve("MiaoHost-" + RUN));
            check(
                HologramEbfPreparedStock.ownedGame(game, receipt)
                    .equals(game),
                "receipt bound to exact instance");
            reject(() -> HologramEbfPreparedStock.ownedGame(saves, receipt), "receipt cannot cross game instances");
            reject(() -> HologramEbfPreparedStock.destination(world, saves, RUN, ACTOR), "marker is required");
            Path mark = world.resolve(".miaotest-host-owner.json");
            marker(mark, "wrong-run", saves);
            reject(() -> HologramEbfPreparedStock.destination(world, saves, RUN, ACTOR), "wrong marker run");
            Files.delete(mark);
            marker(mark, RUN, base);
            reject(() -> HologramEbfPreparedStock.destination(world, saves, RUN, ACTOR), "wrong marker parent");
            Files.delete(mark);
            marker(mark, RUN, saves);
            Path expected = world.resolve("playerdata")
                .resolve(ACTOR + ".dat");
            check(
                HologramEbfPreparedStock.destination(world, saves, RUN, ACTOR)
                    .equals(expected),
                "only this freshly marked actor path");
            Files.createDirectory(expected.getParent());
            Files.write(expected, new byte[] { 19 }, StandardOpenOption.CREATE_NEW);
            reject(() -> HologramEbfPreparedStock.destination(world, saves, RUN, ACTOR), "no existing playerdata");
            check(Files.readAllBytes(expected)[0] == 19, "failed gate does not overwrite old data");
            if (base.getFileSystem()
                .supportedFileAttributeViews()
                .contains("posix")) {
                Path alias = Files.createSymbolicLink(base.resolve("alias"), world);
                reject(() -> HologramEbfPreparedStock.destination(alias, saves, RUN, ACTOR), "no symlink world");
            }

            var lease = lease(receipt, digest);
            HologramEbfPreparedStock.verifyLease(lease, RUN, ACTOR, ACTOR, digest, receipt);
            reject(
                () -> HologramEbfPreparedStock.verifyLease(lease, RUN, ACTOR, UUID.fromString(TOOL), digest, receipt),
                "same authenticated actor required");
            reject(
                () -> HologramEbfPreparedStock.verifyLease(lease, RUN, ACTOR, ACTOR, BAD_PIN, receipt),
                "lease hash does not match pinned recipe");
            reject(
                () -> HologramEbfPreparedStock.verifyLease(lease, RUN, ACTOR, ACTOR, digest, game),
                "lease receipt root");
            var foreign = lease(receipt, digest);
            foreign.getAsJsonObject("ebfPreparedStock")
                .addProperty("nativeDonorActorSha256", BAD_PIN);
            reject(
                () -> HologramEbfPreparedStock.verifyLease(foreign, RUN, ACTOR, ACTOR, digest, receipt),
                "authenticated actor must also match native tool's donor actor");
            lease.getAsJsonObject("ebfPreparedStock")
                .addProperty("borrowedWorld", "must not exist");
            reject(
                () -> HologramEbfPreparedStock.verifyLease(lease, RUN, ACTOR, ACTOR, digest, receipt),
                "foreign lease field");
        } finally {
            try (var entries = Files.walk(base)) {
                for (Path path : (Iterable<Path>) entries.sorted(Comparator.reverseOrder())::iterator)
                    Files.delete(path);
            }
        }
        System.out
            .println("GTMB EBF prepared stock PASS: exact vanilla seed, actor/hash lease and fresh owned destination");
        String input = System.getProperty("gtmb.testlab.headlessEbfSeed", "");
        if (!input.isEmpty()) {
            Path exact = java.nio.file.Paths.get(input)
                .toRealPath();
            check(Files.size(exact) <= 4096, "bounded authentic seed");
            HologramEbfPreparedStock
                .validate(Files.readAllBytes(exact), System.getProperty("gtmb.testlab.headlessEbfSeedSha"), 10363);
            System.out.println("GTMB EBF prepared stock authentic seed bytes PASS");
        }
    }

    private static JsonObject lease(Path root, String digest) {
        var lease = new JsonObject();
        var fixture = new JsonObject();
        lease.addProperty("runId", RUN);
        fixture.addProperty("schema", HologramEbfPreparedStock.SCHEMA);
        fixture.addProperty("actorUuid", ACTOR.toString());
        fixture.addProperty(
            "nativeDonorActorSha256",
            HologramBlueprintArtifact.hash(
                ACTOR.toString()
                    .getBytes(StandardCharsets.US_ASCII)));
        fixture.addProperty("sha256", digest);
        fixture.addProperty("receiptRoot", root.toString());
        lease.add("ebfPreparedStock", fixture);
        return lease;
    }

    private static void marker(Path target, String run, Path parent) throws IOException {
        var marker = new JsonObject();
        marker.addProperty("runId", run);
        marker.addProperty("parent", parent.toString());
        Files.write(
            target,
            marker.toString()
                .getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW);
    }

    private static NBTTagCompound copy(NBTTagCompound value) {
        return (NBTTagCompound) value.copy();
    }

    private static NBTTagCompound stack(NBTTagCompound data) {
        return data.getTagList("Inventory", 10)
            .getCompoundTagAt(0);
    }

    private static byte[] encoded(NBTTagCompound data) throws IOException {
        return HologramSnapshotCodec.compress(data);
    }

    private static void rejectShape(NBTTagCompound data, String label) throws Exception {
        byte[] bytes = encoded(data);
        reject(() -> HologramEbfPreparedStock.validate(bytes, HologramBlueprintArtifact.hash(bytes), ITEM), label);
    }

    private static void reject(Check action, String label) throws Exception {
        try {
            action.run();
        } catch (IOException denied) {
            return;
        }
        throw new AssertionError("Accepted " + label);
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    @FunctionalInterface
    private interface Check {

        void run() throws Exception;
    }
}
