package com.miaokatze.gtmb.hologram;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.config.Configuration;

/** The GUI and command share this single persistent Forge configuration and revision. */
public final class HologramLibraryConfig {

    public static final int DEFAULT_UPLOAD_KIB_PER_SECOND = 1024;
    public static final int DEFAULT_DOWNLOAD_KIB_PER_SECOND = 128;
    /** Legacy shared default remains 128 for callers compiled against the original constant. */
    @Deprecated
    public static final int DEFAULT_KIB_PER_SECOND = DEFAULT_DOWNLOAD_KIB_PER_SECOND;
    private static final String CATEGORY = "serverBlueprintLibrary";
    private final Path file;
    private volatile View current;

    HologramLibraryConfig(File path) throws IOException {
        file = path.toPath()
            .toAbsolutePath()
            .normalize();
        requireRegularTarget();
        Files.createDirectories(file.getParent());
        Path source = Files.createTempFile(file.getParent(), "hologram-library-config-read-", ".cfg");
        try {
            // Forge may rename malformed files during load; only a detached source copy may be touched.
            if (Files.exists(file)) Files.write(source, HologramBlueprintSharing.readBytes(file, 64 * 1024));
            Configuration loaded = new Configuration(source.toFile());
            loaded.load();
            View candidate = new View(
                // Missing keys use the new default; an existing 128 may be an administrator's choice.
                loaded
                    .get(
                        CATEGORY,
                        "uploadKiBPerSecond",
                        DEFAULT_UPLOAD_KIB_PER_SECOND,
                        "Global aggregate upload KiB/s (default 1024 = 1 MiB/s; existing values are preserved).")
                    .getInt(DEFAULT_UPLOAD_KIB_PER_SECOND),
                loaded
                    .get(
                        CATEGORY,
                        "downloadKiBPerSecond",
                        DEFAULT_DOWNLOAD_KIB_PER_SECOND,
                        "Global aggregate download KiB/s.")
                    .getInt(DEFAULT_DOWNLOAD_KIB_PER_SECOND),
                loaded.get(CATEGORY, "maxUploadsPerPlayer", 10, "Maximum published blueprints per player UUID.")
                    .getInt(10),
                1);
            if (!valid(candidate.uploadKiB, candidate.downloadKiB, candidate.maxUploads))
                candidate = new View(DEFAULT_UPLOAD_KIB_PER_SECOND, DEFAULT_DOWNLOAD_KIB_PER_SECOND, 10, 1);
            persist(candidate);
            current = candidate;
        } finally {
            Files.deleteIfExists(source);
        }
    }

    public static final class View {

        public final int uploadKiB, downloadKiB, maxUploads;
        public final long revision;

        View(int up, int down, int max, long revision) {
            uploadKiB = up;
            downloadKiB = down;
            maxUploads = max;
            this.revision = revision;
        }

        public NBTTagCompound tag(boolean admin) {
            NBTTagCompound tag = HologramLibraryProtocol.reply("config");
            tag.setInteger("uploadKiB", uploadKiB);
            tag.setInteger("downloadKiB", downloadKiB);
            tag.setInteger("maxUploads", maxUploads);
            tag.setLong("configRevision", revision);
            tag.setBoolean("admin", admin);
            return tag;
        }
    }

    View view() {
        return current;
    }

    static boolean valid(int up, int down, int max) {
        return up >= 1 && up <= 1024 && down >= 1 && down <= 1024 && max >= 1 && max <= 1000;
    }

    synchronized View update(boolean admin, long expected, int up, int down, int max) throws IOException {
        if (!admin) throw new IOException("Operator level 4 required");
        if (expected != current.revision) throw new IOException("Configuration changed; refresh before saving");
        if (!valid(up, down, max)) throw new IOException("Bandwidth must be 1..1024 KiB/s; quota must be 1..1000");
        View next = new View(up, down, max, current.revision + 1);
        persist(next);
        current = next;
        return next;
    }

    private void persist(View view) throws IOException {
        requireRegularTarget();
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "hologram-library-config-write-", ".cfg");
        try {
            Configuration output = new Configuration(temp.toFile());
            output
                .get(
                    CATEGORY,
                    "uploadKiBPerSecond",
                    DEFAULT_UPLOAD_KIB_PER_SECOND,
                    "Global aggregate upload KiB/s (default 1024 = 1 MiB/s; existing values are preserved).")
                .set(view.uploadKiB);
            output
                .get(
                    CATEGORY,
                    "downloadKiBPerSecond",
                    DEFAULT_DOWNLOAD_KIB_PER_SECOND,
                    "Global aggregate download KiB/s.")
                .set(view.downloadKiB);
            output.get(CATEGORY, "maxUploadsPerPlayer", 10, "Maximum published blueprints per player UUID.")
                .set(view.maxUploads);
            output.save(); // Forge catches IOExceptions; verify the actual artifact before publishing.
            if (!Files.isRegularFile(temp)) throw new IOException("Forge library configuration was not written");
            byte[] bytes = HologramBlueprintSharing.readBytes(temp, 64 * 1024);
            if (bytes.length == 0) throw new IOException("Forge library configuration save failed");
            Configuration read = new Configuration(temp.toFile());
            read.load();
            String category = CATEGORY.toLowerCase(Locale.ENGLISH);
            if (!read.hasKey(category, "uploadKiBPerSecond") || !read.hasKey(category, "downloadKiBPerSecond")
                || !read.hasKey(category, "maxUploadsPerPlayer")
                || read.get(CATEGORY, "uploadKiBPerSecond", -1)
                    .getInt(-1) != view.uploadKiB
                || read.get(CATEGORY, "downloadKiBPerSecond", -1)
                    .getInt(-1) != view.downloadKiB
                || read.get(CATEGORY, "maxUploadsPerPlayer", -1)
                    .getInt(-1) != view.maxUploads)
                throw new IOException("Forge library configuration read-back failed");
            HologramBlueprintSharing.atomicWrite(file, bytes);
        } catch (RuntimeException failure) {
            throw new IOException("Forge library configuration save failed", failure);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void requireRegularTarget() throws IOException {
        if (Files.exists(file) && !Files.isRegularFile(file))
            throw new IOException("Library configuration target is not a regular file");
    }
}
