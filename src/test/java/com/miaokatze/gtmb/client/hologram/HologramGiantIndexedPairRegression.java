// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

/** Exercise the precise legacy-name retirement and managed-slot evidence boundary, without a client. */
public final class HologramGiantIndexedPairRegression {

    private static final String ID = "local_00000000-0000-4000-8000-000000000030";
    private static final String OTHER = "local_00000000-0000-4000-8000-000000000031";

    private HologramGiantIndexedPairRegression() {}

    public static void run() throws IOException {
        Path root = Files.createTempDirectory("gtmb-giant-indexed-pair").toRealPath();
        try {
            Path library = Files.createDirectory(root.resolve("MiaoBlueprints"));
            Path index = library.resolve(".gtmb-blueprint-files.properties");
            Files.createFile(library.resolve(ID + ".json"));
            Files.createFile(library.resolve(ID + ".png"));
            denied(library); // a legacy UUID-named pair is not evidence of this Save
            String leaf = "蓝图_日本語_" + "abc012abc012abc012abc012abc012abc012abc012abc012abc012abc012" + ".json";
            Path saved = Files.createFile(library.resolve(leaf));
            Path preview = library.resolve(leaf.substring(0, leaf.length() - 5) + ".png");
            mapping(index, OTHER, leaf);
            denied(library); // neither an adjacent file nor an unrelated logical mapping may satisfy the probe
            mapping(index, ID, leaf);
            denied(library); // the exact index entry is still not a complete pair
            Files.createFile(preview);
            Files.delete(library.resolve(ID + ".json"));
            Files.delete(library.resolve(ID + ".png"));
            if (!saved.equals(HologramGiantWriteArtifact.resolveIndexedPair(library, ID)))
                throw new AssertionError("Expected the actual renamed indexed pair");
            mapping(index, ID, "../" + leaf);
            denied(library);
            Path external = Files.createFile(root.resolve("external.json"));
            Files.delete(saved);
            boolean linked = false;
            try {
                Files.createSymbolicLink(saved, external);
                linked = true;
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                // Symlink coverage is supported by the Linux runner, but not required on every Windows host.
            }
            if (linked) {
                mapping(index, ID, leaf);
                denied(library);
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        System.out.println("GTMB exact indexed native Save pair contract PASS");
    }

    private static void mapping(Path index, String id, String member) throws IOException {
        var properties = new Properties();
        properties.setProperty(id, member);
        try (var stream = Files.newOutputStream(index)) {
            properties.store(stream, null);
        }
    }

    private static void denied(Path root) throws IOException {
        try {
            HologramGiantWriteArtifact.resolveIndexedPair(root, ID);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("An incomplete, unindexed, unrelated or escaped managed pair was accepted");
    }
}
