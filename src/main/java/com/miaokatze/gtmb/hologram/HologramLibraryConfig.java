// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

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

    public static final int DEFAULT_UPLOAD_KIB_PER_SECOND = 128;
    public static final int DEFAULT_DOWNLOAD_KIB_PER_SECOND = 512;
    /** Legacy shared default follows the download default. */
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
                // Missing keys use the current defaults; preserve independently configured limits.
                loaded
                    .get(
                        CATEGORY,
                        "uploadKiBPerSecond",
                        DEFAULT_UPLOAD_KIB_PER_SECOND,
                        "Global aggregate upload KiB/s (default 128).")
                    .getInt(DEFAULT_UPLOAD_KIB_PER_SECOND),
                loaded
                    .get(
                        CATEGORY,
                        "downloadKiBPerSecond",
                        DEFAULT_DOWNLOAD_KIB_PER_SECOND,
                        "Global aggregate download KiB/s (default 512).")
                    .getInt(DEFAULT_DOWNLOAD_KIB_PER_SECOND),
                loaded.get(CATEGORY, "maxUploadsPerPlayer", 10, "Maximum published blueprints per player UUID.")
                    .getInt(10),
                1);
            // Upgrade the paired legacy 16/16 defaults once. A versioned explicit
            // 16/16 selection, or any independently customized pair, remains intact.
            if (loaded.get(CATEGORY, "bandwidthDefaultsVersion", 0)
                .getInt(0) == 0 && candidate.uploadKiB == 16
                && candidate.downloadKiB == 16)
                candidate = new View(
                    DEFAULT_UPLOAD_KIB_PER_SECOND,
                    DEFAULT_DOWNLOAD_KIB_PER_SECOND,
                    candidate.maxUploads,
                    1);
            if (!valid(candidate.uploadKiB, candidate.downloadKiB, candidate.maxUploads))
                candidate = new View(DEFAULT_UPLOAD_KIB_PER_SECOND, DEFAULT_DOWNLOAD_KIB_PER_SECOND, 10, 1);
            if (loaded.hasKey(CATEGORY.toLowerCase(Locale.ENGLISH), "permissionGroupsVersion")) {
                if (loaded.get(CATEGORY, "permissionGroupsVersion", 0)
                    .getInt(0) != HologramLibraryPermissions.VERSION)
                    throw new IOException("Unsupported library permission groups version");
                HologramLibraryPermissions permissions = HologramLibraryPermissions.json(
                    loaded.get(CATEGORY, "permissionGroupsJson", "")
                        .getString());
                long revision;
                try {
                    revision = Long.parseLong(
                        loaded.get(CATEGORY, "revision", "1")
                            .getString());
                } catch (NumberFormatException invalid) {
                    throw new IOException("Invalid library revision", invalid);
                }
                if (revision < 1 || revision == Long.MAX_VALUE) throw new IOException("Invalid library revision");
                candidate = new View(candidate.uploadKiB, candidate.downloadKiB, permissions, revision);
            }
            persist(candidate);
            current = candidate;
        } finally {
            Files.deleteIfExists(source);
        }
    }

    public static final class View {

        public final int uploadKiB, downloadKiB, maxUploads;
        public final long revision;
        public final HologramLibraryPermissions permissions;

        View(int up, int down, int max, long revision) {
            this(up, down, HologramLibraryPermissions.defaults(max), revision);
        }

        View(int up, int down, HologramLibraryPermissions permissions, long revision) {
            uploadKiB = up;
            downloadKiB = down;
            this.permissions = permissions;
            maxUploads = permissions.effective(null).quota;
            this.revision = revision;
        }

        public NBTTagCompound tag(boolean admin) {
            NBTTagCompound tag = HologramLibraryProtocol.reply("config");
            tag.setInteger("uploadKiB", uploadKiB);
            tag.setInteger("downloadKiB", downloadKiB);
            tag.setInteger("maxUploads", maxUploads);
            tag.setLong("configRevision", revision);
            tag.setBoolean("admin", admin);
            tag.setInteger("permissionGroupsVersion", HologramLibraryPermissions.VERSION);
            if (admin) tag.setTag("permissionGroups", permissions.tag());
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
        return update(admin, expected, up, down, current.permissions.withDefaultQuota(max));
    }

    synchronized View update(boolean admin, long expected, int up, int down, HologramLibraryPermissions permissions)
        throws IOException {
        if (!admin) throw new IOException("Operator level 4 required");
        if (expected != current.revision) throw new IOException("Configuration changed; refresh before saving");
        if (permissions == null || !valid(up, down, permissions.effective(null).quota))
            throw new IOException("Bandwidth must be 1..1024 KiB/s; quota must be 1..1000");
        if (current.revision >= Long.MAX_VALUE - 1) throw new IOException("Library revision exhausted");
        View next = new View(up, down, permissions, current.revision + 1);
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
                    "Global aggregate upload KiB/s (default 128).")
                .set(view.uploadKiB);
            output
                .get(
                    CATEGORY,
                    "downloadKiBPerSecond",
                    DEFAULT_DOWNLOAD_KIB_PER_SECOND,
                    "Global aggregate download KiB/s (default 512).")
                .set(view.downloadKiB);
            output.get(CATEGORY, "maxUploadsPerPlayer", 10, "Maximum published blueprints per player UUID.")
                .set(view.maxUploads);
            output.get(CATEGORY, "bandwidthDefaultsVersion", 1, "Legacy bandwidth defaults migration version.")
                .set(1);
            output.get(CATEGORY, "permissionGroupsVersion", HologramLibraryPermissions.VERSION)
                .set(HologramLibraryPermissions.VERSION);
            output.get(CATEGORY, "permissionGroupsJson", "")
                .set(view.permissions.json());
            output.get(CATEGORY, "revision", "1")
                .set(String.valueOf(view.revision));
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
                    .getInt(-1) != view.maxUploads
                || !read.get(CATEGORY, "permissionGroupsJson", "")
                    .getString()
                    .equals(view.permissions.json())
                || !read.get(CATEGORY, "revision", "0")
                    .getString()
                    .equals(String.valueOf(view.revision))
                || read.get(CATEGORY, "permissionGroupsVersion", 0)
                    .getInt(0) != HologramLibraryPermissions.VERSION)
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
