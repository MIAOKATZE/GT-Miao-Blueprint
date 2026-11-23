// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.hologram.HologramSnapshotPalette;

/** Client startup preference for the ordinary preparation of an explicit managed private Edit. */
final class HologramPrivateEditPerformance {

    private static final String FILE = "config/gtmb/client-blueprint-performance.properties";
    private static final String KEY = "ordinaryOpenPreset";
    private static final int MAX_BYTES = 4096;
    private static final Pattern MANAGED_ID = Pattern.compile("local_[a-fA-F0-9-]{36}");

    enum Preset {
        REGULAR(5120, 5_000_000L),
        FAST(51_200, 10_000_000L);

        final int workCap;
        final long softNanos;

        Preset(int workCap, long softNanos) {
            this.workCap = workCap;
            this.softNanos = softNanos;
        }
    }

    private HologramPrivateEditPerformance() {}

    static Preset load(Path gameDirectory) {
        if (gameDirectory == null) return Preset.REGULAR;
        Path file = gameDirectory.resolve(FILE);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Preset.REGULAR;
            byte[] bytes = new byte[MAX_BYTES + 1];
            int count = 0;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                while (count < bytes.length) {
                    int read = in.read(bytes, count, bytes.length - count);
                    if (read < 0) break;
                    if (read == 0) return Preset.REGULAR;
                    count += read;
                }
            }
            if (count > MAX_BYTES) return Preset.REGULAR;
            Properties values = new Properties();
            values.load(new ByteArrayInputStream(bytes, 0, count));
            return "fast".equals(values.getProperty(KEY, "").trim()) ? Preset.FAST : Preset.REGULAR;
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
            // Missing or malformed config does not close the editor or write a replacement.
            return Preset.REGULAR;
        }
    }

    static Request capture(String explicitPrivateEditId, Preset preset, boolean exactRoot) {
        if (!exactRoot || explicitPrivateEditId == null || preset == null
            || !MANAGED_ID.matcher(explicitPrivateEditId).matches()) return null;
        return new Request(explicitPrivateEditId, preset);
    }

    static final class Request {

        private final String fileId;
        private final Preset preset;

        private Request(String fileId, Preset preset) {
            this.fileId = fileId;
            this.preset = preset;
        }

        Bound bind(HologramLocalBlueprintDocument document, boolean materializedProjection, NBTTagCompound snapshot,
            HologramState.Preparation preparation, boolean exactOwner) {
            if (!exactOwner || !materializedProjection || document == null || snapshot == null || preparation == null
                || !preparation.usesEditorCells()
                || !fileId.equals(document.fileId()) || document.packedAuthoring() || document.dirty() || document.revision() != 0
                || !snapshot.getBoolean("localBlueprintDocument") || !snapshot.getBoolean("blueprintEditing")
                || !fileId.equals(snapshot.getString("libraryLocalId")) || snapshot.getLong("planRevision") != 0
                || snapshot.getBoolean("structureProjection") || snapshot.getBoolean("displayPartial")
                || snapshot.getBoolean("partial") || HologramSnapshotPalette.isPacked(snapshot) || preparation.canPrepareCompactIndex()
                || preparation.cellsPrepared() != 0 || preparation.total() == 0) return null;
            return new Bound(preparation, preset);
        }
    }

    static final class Bound {

        private final HologramState.Preparation preparation;
        private final Preset preset;

        private Bound(HologramState.Preparation preparation, Preset preset) {
            this.preparation = preparation;
            this.preset = preset;
        }

        Preset forPreparation(HologramState.Preparation owner, boolean exactOwner) {
            return owner == preparation && exactOwner ? preset : null;
        }
    }
}
