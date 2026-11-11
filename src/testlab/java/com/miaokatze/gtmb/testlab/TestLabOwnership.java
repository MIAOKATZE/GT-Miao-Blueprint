// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

/** A durable save-local ownership claim, checked before loading an existing DIM directory. */
final class TestLabOwnership {

    final Path root, directory;
    final UUID owner;
    private final int dimension;

    TestLabOwnership(Path worldRoot, TestLabConfig cfg) throws IOException {
        dimension = cfg.dimension;
        root = worldRoot.toRealPath();
        directory = root.resolve("gtmb-testlab");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(root.resolve("DIM" + cfg.dimension)))
            throw new IOException("Lab ownership/dimension paths cannot be symbolic links");
        Path marker = directory.resolve("ownership-" + cfg.dimension + ".nbt");
        NBTTagCompound tag;
        if (Files.exists(marker)) {
            if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker) || Files.size(marker) > 65536)
                throw new IOException("Invalid lab ownership marker");
            tag = HologramSnapshotCodec.decode(Files.readAllBytes(marker));
            if (tag.getInteger("version") != 1 || tag.getInteger("dimension") != cfg.dimension
                || tag.getInteger("provider") != cfg.provider
                || !tag.getString("layout")
                    .equals(cfg.layout()))
                throw new IOException(
                    "Lab ownership/layout differs: restore its configuration; no existing world is adopted");
            try {
                owner = UUID.fromString(tag.getString("owner"));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid lab ownership identity", invalid);
            }
        } else {
            if (Files.exists(root.resolve("DIM" + cfg.dimension))) throw new IOException(
                "Existing dimension directory has no GTMB lab ownership; refusing to load or reset it");
            owner = UUID.randomUUID();
            tag = new NBTTagCompound();
            tag.setInteger("version", 1);
            tag.setInteger("dimension", cfg.dimension);
            tag.setInteger("provider", cfg.provider);
            tag.setString("owner", owner.toString());
            tag.setString("layout", cfg.layout());
            write(marker, HologramSnapshotCodec.compress(tag));
        }
        if (cfg.composite) claimComposite(cfg);
        if (cfg.nativePlatforms) claimNative(cfg);
    }

    private void claimNative(TestLabConfig cfg) throws IOException {
        Path marker = directory.resolve("native-ownership-" + cfg.dimension + ".nbt");
        NBTTagCompound tag = readNative(marker);
        if (tag != null) {
            if (!owner.toString()
                .equals(tag.getString("owner"))
                || !cfg.nativeLayout()
                    .equals(tag.getString("layout")))
                throw new IOException("Native platform ownership/layout differs; restore its original configuration");
            return;
        }
        tag = new NBTTagCompound();
        tag.setInteger("version", 1);
        tag.setString("owner", owner.toString());
        tag.setString("layout", cfg.nativeLayout());
        write(marker, HologramSnapshotCodec.compress(tag));
    }

    /** Claim only the selected page's region. Merely enabling native platforms adopts no existing region. */
    NBTTagCompound nativePage(TestLabNativeCatalog.Page page, TestLabConfig cfg, boolean claim) throws IOException {
        Path marker = directory.resolve("native-platforms")
            .resolve("page-" + page.index + ".nbt");
        NBTTagCompound tag = readNative(marker);
        if (tag != null) {
            if (!owner.toString()
                .equals(tag.getString("owner")) || !page.id.equals(tag.getString("id"))
                || !page.fingerprint.equals(tag.getString("sourceFingerprint")))
                throw new IOException("Native page identity/catalog differs: " + page.id);
            return tag;
        }
        if (!claim) throw new IOException("Native page has not been built: " + page.id);
        TestLabZone source = cfg.nativeZone(page.index, false);
        Path region = root.resolve("DIM" + cfg.dimension)
            .resolve("region")
            .resolve("r." + (source.x >> 9) + "." + (source.z >> 9) + ".mca");
        if (Files.exists(region)) throw new IOException("Unclaimed native page region already exists: " + page.id);
        tag = page.summary(cfg);
        tag.setString("owner", owner.toString());
        tag.setString("sourceFingerprint", page.fingerprint);
        tag.setString("state", "CLAIMED");
        write(marker, HologramSnapshotCodec.compress(tag));
        tag.setBoolean("freshClaim", true);
        return tag;
    }

    void nativeManifest(int page, NBTTagCompound tag) throws IOException {
        nativeArtifact("page-" + page, tag);
    }

    void nativeIndex(NBTTagCompound tag) throws IOException {
        nativeArtifact("index", tag);
    }

    NBTTagCompound nativeIndex() throws IOException {
        return readNative(
            directory.resolve("native-platforms")
                .resolve("index.nbt"));
    }

    NBTTagCompound nativeMachineClaim(String id, String layout, List<TestLabZone> zones) throws IOException {
        NBTTagCompound existing = nativeMachineManifest(id);
        if (existing != null) {
            if (!owner.toString()
                .equals(existing.getString("owner")) || !layout.equals(existing.getString("layout")))
                throw new IOException("Native machine ownership/layout differs: " + id);
            return existing;
        }
        Path regions = root.resolve("DIM" + dimension)
            .resolve("region");
        for (TestLabZone zone : zones) {
            for (int rx = zone.x >> 9; rx <= (zone.x + zone.sizeX - 1) >> 9; rx++)
                for (int rz = zone.z >> 9; rz <= (zone.z + zone.sizeZ - 1) >> 9; rz++)
                    if (Files.exists(regions.resolve("r." + rx + "." + rz + ".mca")))
                        throw new IOException("Unclaimed native machine region already exists: " + id);
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id);
        tag.setString("owner", owner.toString());
        tag.setString("layout", layout);
        tag.setString("state", "CLAIMED");
        nativeMachineManifest(id, tag);
        return tag;
    }

    NBTTagCompound nativeMachineManifest(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9_.-]+")) throw new IOException("Invalid fixed native machine ID");
        return readNative(
            directory.resolve("native-platforms")
                .resolve("machine-" + id + ".nbt"));
    }

    void nativeMachineManifest(String id, NBTTagCompound tag) throws IOException {
        if (!id.matches("[A-Za-z0-9_.-]+")) throw new IOException("Invalid fixed native machine ID");
        nativeArtifact("machine-" + id, tag);
    }

    private void nativeArtifact(String name, NBTTagCompound tag) throws IOException {
        byte[] nbt = HologramSnapshotCodec.compress(tag);
        byte[] json = new com.google.gson.GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(TestLabReport.json(tag))
            .getBytes(StandardCharsets.UTF_8);
        if (nbt.length > 4 * 1024 * 1024 || json.length > 16 * 1024 * 1024)
            throw new IOException("Native platform manifest exceeds artifact budget");
        Path target = directory.resolve("native-platforms");
        write(target.resolve(name + ".nbt"), nbt);
        write(target.resolve(name + ".json"), json);
    }

    private static NBTTagCompound readNative(Path marker) throws IOException {
        if (Files.isSymbolicLink(marker.getParent()))
            throw new IOException("Native ownership path cannot be a symbolic link");
        if (!Files.exists(marker)) return null;
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker) || Files.size(marker) > 4 * 1024 * 1024)
            throw new IOException("Invalid native ownership manifest");
        return HologramSnapshotCodec.decode(Files.readAllBytes(marker));
    }

    /** Additive claim leaves the five original facility layout bytes unchanged. Never adopts existing regions. */
    private void claimComposite(TestLabConfig cfg) throws IOException {
        Path marker = directory.resolve("composite-ownership-" + cfg.dimension + ".nbt");
        if (Files.exists(marker)) {
            if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker) || Files.size(marker) > 65536)
                throw new IOException("Invalid composite ownership marker");
            NBTTagCompound tag = HologramSnapshotCodec.decode(Files.readAllBytes(marker));
            if (!owner.toString()
                .equals(tag.getString("owner"))
                || !cfg.compositeLayout()
                    .equals(tag.getString("layout")))
                throw new IOException("Composite ownership/layout differs; restore its original configuration");
            return;
        }
        Path regions = root.resolve("DIM" + cfg.dimension)
            .resolve("region");
        for (TestLabZone zone : cfg.zones) if (zone.id.startsWith("composite-")) {
            for (int rx = zone.x >> 9; rx <= (zone.x + zone.sizeX - 1) >> 9; rx++)
                for (int rz = zone.z >> 9; rz <= (zone.z + zone.sizeZ - 1) >> 9; rz++)
                    if (Files.exists(regions.resolve("r." + rx + "." + rz + ".mca")))
                        throw new IOException("Unclaimed composite region already exists; no world adoption");
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("version", 1);
        tag.setString("owner", owner.toString());
        tag.setString("layout", cfg.compositeLayout());
        write(marker, HologramSnapshotCodec.compress(tag));
    }

    static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
            out.write(bytes);
            out.getFD()
                .sync();
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    void report(NBTTagCompound report) throws IOException {
        byte[] nbt = HologramSnapshotCodec.compress(report);
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(TestLabReport.json(report));
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        if (nbt.length > 512 * 1024 || text.length > 512 * 1024)
            throw new IOException("Lab report exceeds 512KiB artifact budget");
        write(directory.resolve("latest-report.nbt"), nbt);
        write(directory.resolve("latest-report.json"), text);
    }
}
