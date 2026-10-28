package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Disk contract only. Test pixels are fixtures, not a claim of native preview capture. */
public final class HologramAuthorCandidatePersistenceRegression {

    private HologramAuthorCandidatePersistenceRegression() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        Path directory = Files.createTempDirectory("gtmb-author-candidate-");
        try {
            HologramClientLibrary store = new HologramClientLibrary(directory);
            byte[] json = "{\"schema\":2,\"blueprint\":{\"schema\":2,\"name\":\"candidate\",\"cells\":[]}}"
                .getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(640, 640, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png",
                output);
            HologramBlueprintArtifact.Artifact artifact = HologramBlueprintArtifact
                .prepareSave(json, output.toByteArray());
            Path file = store.savePortableCandidate(artifact, () -> true);
            SimpleAssert.that(
                file.getParent()
                    .getFileName()
                    .toString()
                    .equals("candidates"),
                "unreviewed candidates never masquerade as reviewed library rows");
            SimpleAssert.that(
                file.getFileName()
                    .toString()
                    .startsWith("unreviewed_"),
                "export filename clearly names candidate status");
            HologramBlueprintArtifact.Artifact actual = HologramBlueprintArtifact.readPair(file);
            SimpleAssert.eq(artifact.jsonSha256, actual.jsonSha256, "candidate JSON exact readback");
            SimpleAssert.eq(artifact.pngSha256, actual.pngSha256, "candidate PNG exact readback");
            SimpleAssert.eq(
                0,
                store.list()
                    .size(),
                "candidate does not acquire reviewed-library status by successful IO");
            try {
                store.savePortableCandidate(artifact, () -> false);
                throw new AssertionError("stale candidate write accepted");
            } catch (IOException expected) { /* frozen source fence failed before any file write */ }
            SimpleAssert.eq(
                artifact.jsonSha256,
                HologramBlueprintArtifact.readPair(file).jsonSha256,
                "rejected stale write leaves prior artifact intact");
            java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
            HologramClientLibrary racing = new HologramClientLibrary(directory, new HologramClientLibrary.PairIo() {

                public HologramBlueprintArtifact.PairWrite write(Path path, byte[] source, byte[] preview,
                    java.util.function.BooleanSupplier valid) throws IOException {
                    return HologramBlueprintArtifact.writePairPending(path, source, preview, valid);
                }

                public HologramBlueprintArtifact.Artifact read(Path path) throws IOException {
                    HologramBlueprintArtifact.Artifact result = HologramBlueprintArtifact.readPair(path);
                    current.set(false);
                    return result;
                }
            });
            try {
                racing.savePortableCandidate(artifact, current::get);
                throw new AssertionError("revision changed during readback accepted");
            } catch (IOException expected) { /* pair written but never accepted must roll back */ }
            try (java.util.stream.Stream<Path> files = Files.list(file.getParent())) {
                SimpleAssert.eq(
                    1L,
                    files.filter(
                        path -> path.toString()
                            .endsWith(".json"))
                        .count(),
                    "stale post-write candidate is rolled back, previous export remains");
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }
}
