// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises detached display decoding: unknown construction identities stay outside validation and authority. */
public final class HologramLocalPreviewRegression {

    public static void run() throws Exception {
        admissionReceipts();
        compactDisplay();
        sourceReuse();
        coldPortableRecentSource();
        earlyDisplay();
        displayPicture();
        uploadRepresentation();
        frozenPlacement();
        NBTTagCompound review = HologramLocalPlacement.reviewOptions();
        SimpleAssert.eq(true, review.getBoolean("openReview"), "second world use opens the existing server review");
        SimpleAssert.eq(false, review.getBoolean("executeIntent"), "opening review cannot request construction");
        SimpleAssert.eq(false, review.hasKey("acknowledgedMaterials"), "display never invents a material acceptance");
        String json = "{\"blueprint\":{\"schema\":999,\"retainNbt\":false,\"retainCovers\":true,"
            + "\"retainedNbtKeys\":[\"configuration\"],\"cells\":["
            + "{\"x\":2,\"y\":-3,\"z\":4,\"block\":\"not_installed:display_only\","
            + "\"item\":\"not_installed:display_only\",\"facing\":5},"
            + "{\"x\":1,\"y\":1,\"z\":1,\"excluded\":true}]}}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8), original = bytes.clone();
        NBTTagCompound display = HologramBlueprintPreview.snapshot(bytes);
        SimpleAssert.that(Arrays.equals(original, bytes), "preview never mutates the original portable source");
        NBTTagList rows = display.getTagList("cells", 10);
        SimpleAssert.eq(1, rows.tagCount(), "excluded cells do not create display geometry");
        SimpleAssert.eq(
            "not_installed:display_only",
            rows.getCompoundTagAt(0)
                .getString("wantId"),
            "detached decode does not ask construction registry validation to approve the source");
        SimpleAssert.eq(
            -3,
            rows.getCompoundTagAt(0)
                .getInteger("dy"),
            "display preserves relative source offsets");
        SimpleAssert.eq(false, display.getBoolean("retainNbt"), "local demand receives source NBT selection");
        SimpleAssert.eq(true, display.getBoolean("retainCovers"), "local demand receives source cover selection");
        SimpleAssert.eq(
            "configuration",
            display.getTagList("retainedNbtKeys", 8)
                .getStringTagAt(0),
            "local demand receives selected NBT roots");
        SimpleAssert.eq(false, display.hasKey("planRevision"), "display decoding grants no reviewed plan revision");
        SimpleAssert
            .eq(false, display.hasKey("placementReviewNonce"), "display decoding grants no construction review");
        expectFailure(new String(new char[65]).replace('\0', '{') + new String(new char[65]).replace('\0', '}'));
        expectFailure("{\"blueprint\":{\"cells\":[{\"x\":60000001}]}}");
        expectFailure("{\"blueprint\":{\"cells\":[]}}");
        expectFailure("{\"blueprint\":{\"cells\":[{\"tileNbt\":\"not base64\"}]}}");
    }

    private static void compactDisplay() throws Exception {
        NBTTagCompound configuration = new NBTTagCompound();
        configuration.setString("configuration", "exact local display payload");
        String encoded = java.util.Base64.getEncoder()
            .encodeToString(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(configuration));
        String policy = "\"schema\":2,\"retainNbt\":true,\"retainCovers\":true,";
        String legacy = "{\"schema\":2,\"blueprint\":{" + policy
            + "\"cells\":[{\"x\":1,\"y\":2,\"z\":3,\"block\":\"missing:display\",\"itemNbt\":\""
            + encoded
            + "\"}],\"palette\":[]}}";
        String compact = "{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":{" + policy
            + "\"cells\":[[0,{\"x\":1,\"y\":2,\"z\":3}]],\"palette\":[]},"
            + "\"materials\":[{\"block\":\"missing:display\",\"itemNbt\":0}],\"nbt\":[\""
            + encoded
            + "\"]}";
        SimpleAssert.eq(
            HologramBlueprintPreview.snapshot(legacy.getBytes(StandardCharsets.UTF_8)),
            HologramBlueprintPreview.snapshot(compact.getBytes(StandardCharsets.UTF_8)),
            "compact material and NBT references produce exactly the same detached display");
    }

    private static void sourceReuse() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("gtmb-display-only-", ".json");
        try {
            byte[] first = "{\"blueprint\":{\"cells\":[{\"x\":1,\"block\":\"missing:display\"}]}}"
                .getBytes(StandardCharsets.UTF_8);
            java.nio.file.Files.write(file, first);
            HologramLibraryClient client = new HologramLibraryClient();
            HologramLibraryClient.DisplaySource cold = client.readDisplaySource("fixture", file, "local-fixture", "");
            SimpleAssert.eq("", cold.hash, "ordinary source decoding computes no semantic content hash");
            SimpleAssert.eq(null, cold.artifact, "ordinary source decoding needs no PNG or artifact bundle");
            cold.state = new HologramState(cold.snapshot);
            cold.snapshot = null;
            client.retainDisplaySource(cold);
            HologramLibraryClient.DisplaySource warm = client.readDisplaySource("fixture", file, "local-fixture", "");
            SimpleAssert.that(
                cold == warm && cold.state == warm.state,
                "unchanged source identity reuses the exact prepared state without decoding or copying cells");
            java.nio.file.Files.write(
                file,
                "{\"blueprint\":{\"cells\":[{\"x\":42,\"block\":\"missing:changed\"}]}}"
                    .getBytes(StandardCharsets.UTF_8));
            HologramLibraryClient.DisplaySource changed = client
                .readDisplaySource("fixture", file, "local-fixture", "");
            SimpleAssert.that(
                changed != cold && changed.state == null && changed.artifact == null && changed.hash.isEmpty(),
                "actual source changes discard display, bundle and admission identity together");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static void earlyDisplay() {
        NBTTagCompound source = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < 600; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", i * 32);
            row.setInteger("dx", i * 32);
            rows.appendTag(row);
        }
        source.setTag("cells", rows);
        HologramState.Preparation preparation = new HologramState.Preparation(source);
        SimpleAssert.eq(false, preparation.step(64, Long.MAX_VALUE), "a large display remains in preparation");
        HologramState first = preparation.firstDisplay();
        SimpleAssert.eq(64, first.cells.size(), "the first completed batch can display before full preparation");
        SimpleAssert.eq(true, first.data.getBoolean("displayPartial"), "early geometry carries no full-ready claim");
        int regions = first.regions.regions.size();
        while (!preparation.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
        HologramState complete = preparation.result();
        SimpleAssert.eq(600, complete.cells.size(), "final geometry retains every cell");
        SimpleAssert.that(
            first.cells.get(0) == complete.cells.get(0),
            "early and final displays share immutable completed cells");
        SimpleAssert.eq(64, first.cells.size(), "continuing preparation never mutates published early cells");
        SimpleAssert
            .eq(regions, first.regions.regions.size(), "continuing preparation never mutates the early spatial index");
        SimpleAssert
            .that(first.regions != complete.regions, "the mutable builder is never published as an early region index");
    }

    private static void coldPortableRecentSource() throws Exception {
        Path game = Files.createTempDirectory("gtmb-recent-portable-");
        try {
            HologramClientLibrary original = new HologramClientLibrary(game);
            Path member = Files.createDirectories(original.directory())
                .resolve("Arbitrary portable name.json");
            byte[] json = "{\"blueprint\":{\"name\":\"Recent\",\"cells\":[{\"block\":\"unavailable:fixture\"}]}}"
                .getBytes(StandardCharsets.UTF_8);
            Files.write(member, json);
            Path png = com.miaokatze.gtmb.hologram.HologramBlueprintArtifact.previewPath(member);
            Files.write(png, new byte[] { 1 }); // Placement does not attempt to open the optional picture.
            String id = original.list()
                .get(0).id;
            original.rememberLocal("integrated", id);

            HologramClientLibrary restarted = new HologramClientLibrary(game);
            SimpleAssert.that(
                !restarted.savedPath(id)
                    .equals(member),
                "cold store has no transient display path");
            HologramLibraryClient client = new HologramLibraryClient();
            HologramLibraryClient.DisplaySource source = client
                .readRecentLocalSource(restarted, restarted.lastSource("integrated"));
            SimpleAssert
                .that(source != null && Arrays.equals(json, source.json), "cold Alt placement restores a portable ID");
            SimpleAssert.eq("", source.hash, "reopening preview does not hash the saved blueprint");
            SimpleAssert.eq(false, source.thumbnailRead, "reopening placement does not read the saved picture");
            HologramState.Preparation preparation = new HologramState.Preparation(source.snapshot, true);
            while (!preparation.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
            HologramState state = preparation.result();
            SimpleAssert
                .eq("minecraft:wool", state.cells.get(0).wantId, "missing private material has a display placeholder");
            SimpleAssert.that(Arrays.equals(json, source.json), "placement still submits the exact original source");
            SimpleAssert.eq(false, state.data.hasKey("planRevision"), "display never grants a server plan revision");
            Files.delete(member);
            SimpleAssert.eq(
                null,
                client.readRecentLocalSource(restarted, restarted.lastSource("integrated")),
                "deleted recent file yields no ghost and leaves the fallback usable");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(game)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void displayPicture() throws Exception {
        java.awt.image.BufferedImage original = new java.awt.image.BufferedImage(
            32,
            32,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        original.setRGB(5, 7, 0xff234567);
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(original, "png", encoded);
        byte[] png = encoded.toByteArray();
        java.awt.image.BufferedImage decoded = HologramLibraryClient.decodeDisplayImage(png);
        SimpleAssert.eq(
            original.getRGB(5, 7),
            decoded.getRGB(5, 7),
            "optional source pictures display without JSON binding or content admission");
        byte[] oversized = png.clone();
        oversized[16] = oversized[17] = oversized[19] = 0;
        oversized[18] = 8;
        try {
            HologramLibraryClient.decodeDisplayImage(oversized);
            throw new AssertionError("PNG header dimensions must be bounded before pixel allocation");
        } catch (IOException expected) {}
    }

    private static void uploadRepresentation() throws Exception {
        StringBuilder json = new StringBuilder("{\"blueprint\":{\"cells\":[");
        for (int i = 0; i < 128; i++) {
            if (i > 0) json.append(',');
            json.append("{\"x\":")
                .append(i)
                .append(",\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"metadata\":0,\"damage\":0}");
        }
        byte[] original = json.append("]}}")
            .toString()
            .getBytes(StandardCharsets.UTF_8), before = original.clone();
        byte[] compact = HologramLibraryClient.uploadJson(original, () -> true);
        SimpleAssert.that(compact.length < original.length, "a real miss uses existing lossless transport compression");
        SimpleAssert.eq(
            com.miaokatze.gtmb.hologram.HologramBlueprintContentHash.of(original),
            com.miaokatze.gtmb.hologram.HologramBlueprintContentHash.of(compact),
            "transport compression preserves the admitted semantic content identity");
        SimpleAssert.that(Arrays.equals(before, original), "upload compression never rewrites the frozen source bytes");
        SimpleAssert.that(
            compact == HologramLibraryClient.uploadJson(compact, () -> true),
            "already compact sources are reused without another compression pass");
    }

    private static void admissionReceipts() {
        String first = new String(new char[64]).replace('\0', 'a');
        String second = new String(new char[64]).replace('\0', 'b');
        HologramContentAdmissionFence source = new HologramContentAdmissionFence(17, "first-source", first);
        SimpleAssert.eq(false, source.accept(18, 17, "first-source", first), "a newer task cannot inherit old ready");
        SimpleAssert.eq(
            false,
            source.accept(17, 0, "first-source", first),
            "a token-less control receipt cannot open world preview");
        SimpleAssert
            .eq(false, source.accept(17, 17, "other-source", first), "another selection cannot inherit admission");
        SimpleAssert.eq(
            false,
            source.accept(17, 17, "first-source", second),
            "a changed content digest cannot inherit admission");
        SimpleAssert.eq(
            true,
            source.accept(17, 17, "first-source", first),
            "the exact current UI selection may enter world preview");
        SimpleAssert.eq(false, source.accept(17, 17, "first-source", first), "a duplicate ready cannot reopen preview");
        HologramContentAdmissionFence cancelled = new HologramContentAdmissionFence(18, "second-source", second);
        cancelled.cancel();
        SimpleAssert.eq(
            false,
            cancelled.accept(18, 18, "second-source", second),
            "cancelled preparation cannot revive on late ready");
        HologramContentAdmissionFence serverHeld = new HologramContentAdmissionFence(19, "known-id", "");
        SimpleAssert.eq(
            false,
            serverHeld.accept(19, 19, "known-id", ""),
            "a known ID still needs a valid server content digest");
        SimpleAssert
            .eq(true, serverHeld.accept(19, 19, "known-id", first), "server-held source supplies its admission digest");
    }

    private static void frozenPlacement() {
        HologramLibraryClient.DisplaySource source = new HologramLibraryClient.DisplaySource(
            "private-fixture",
            "file-stamp",
            new byte[] { 1 },
            new NBTTagCompound(),
            "local_fixture",
            "",
            "",
            "fixture-revision");
        int[] anchor = { 12, 64, -9 };
        com.miaokatze.gtmb.hologram.HologramPreviewPose pose = new com.miaokatze.gtmb.hologram.HologramPreviewPose()
            .turn(true)
            .turn(false);
        NBTTagCompound options = HologramLocalPlacement.reviewOptions();
        HologramLocalPlacement.Submission submission = new HologramLocalPlacement.Submission(
            source,
            "",
            anchor,
            pose,
            options);
        anchor[0] = -500;
        options.setBoolean("executeIntent", true);
        NBTTagCompound issued = submission.request("admitted-digest");
        issued.setInteger("placementZ", 900);
        issued.getIntArray("previewPose")[0] = 9;
        NBTTagCompound retry = submission.request("admitted-digest");
        SimpleAssert.that(submission.source == source, "the exact displayed file is bound to the review request");
        SimpleAssert.eq(12, retry.getInteger("x"), "the first valid submission freezes its original locked anchor");
        SimpleAssert.eq(-9, retry.getInteger("placementZ"), "request packet mutation cannot move the frozen target");
        SimpleAssert.that(Arrays.equals(pose.recipe(), retry.getIntArray("previewPose")), "submission freezes pose");
        SimpleAssert.eq("fixture-revision", retry.getString("sourceRevision"), "source revision crosses admission");
        SimpleAssert.eq("admitted-digest", retry.getString("jsonHash"), "only admitted digest binds the server intent");
        SimpleAssert.eq(false, retry.getBoolean("executeIntent"), "review intent cannot become a build after freezing");
    }

    private static void expectFailure(String json) throws Exception {
        try {
            HologramBlueprintPreview.snapshot(json.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("Malformed/over-budget display data must be refused");
        } catch (IOException expected) {}
    }
}
