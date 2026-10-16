package com.miaokatze.gtmb.testlab;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

/** A durable save-local ownership claim, checked before loading an existing DIM directory. */
final class TestLabOwnership {

    final Path root, directory;
    final UUID owner;

    TestLabOwnership(Path worldRoot, TestLabConfig cfg) throws IOException {
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
