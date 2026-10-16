package com.miaokatze.gtmb.hologram;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Atomic write-ahead record, scoped to this save and the authenticated player UUID. */
final class HologramCutStorage {

    private final Path file;

    HologramCutStorage(Path root, UUID owner) {
        file = root.resolve("gtmb-cut")
            .resolve(owner.toString() + ".nbt");
    }

    NBTTagCompound load() throws IOException {
        if (!Files.exists(file)) {
            NBTTagCompound result = new NBTTagCompound();
            result.setInteger("version", HologramCutProtocol.VERSION);
            result.setTag("entries", new NBTTagList());
            result.setTag("requests", new NBTTagList());
            return result;
        }
        long length = Files.size(file);
        if (length <= 0 || length > HologramSnapshotAssembly.MAX_BYTES) throw new IOException("剪切存储大小无效");
        NBTTagCompound result = HologramSnapshotCodec.decode(Files.readAllBytes(file));
        if (result.getInteger("version") != HologramCutProtocol.VERSION) throw new IOException("剪切存储版本无效");
        return result;
    }

    void save(NBTTagCompound data) throws IOException {
        byte[] bytes = HologramSnapshotCodec.compress(data);
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
            output.write(bytes);
            output.getFD()
                .sync();
        }
        // No non-atomic fallback: failure leaves the previous authoritative record intact.
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
