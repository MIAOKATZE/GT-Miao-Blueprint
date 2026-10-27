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
    private final HologramCutDiagnostics diagnostics;

    HologramCutStorage(Path root, UUID owner) {
        this(root, owner, HologramCutDiagnostics.QUIET);
    }

    HologramCutStorage(Path root, UUID owner, HologramCutDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
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
        NBTTagCompound result;
        try (HologramCutDiagnostics.Scope ignored = diagnostics.phase(HologramCutDiagnostics.Phase.JOURNAL_LOAD, length)) {
            result = HologramSnapshotCodec.decode(Files.readAllBytes(file));
        }
        if (result.getInteger("version") != HologramCutProtocol.VERSION) throw new IOException("剪切存储版本无效");
        return result;
    }

    void save(NBTTagCompound data) throws IOException {
        byte[] bytes;
        try (HologramCutDiagnostics.Scope ignored = diagnostics.phase(HologramCutDiagnostics.Phase.JOURNAL_ENCODE, 1)) {
            bytes = HologramSnapshotCodec.compress(data);
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
            try (HologramCutDiagnostics.Scope ignored = diagnostics.phase(HologramCutDiagnostics.Phase.JOURNAL_WRITE, bytes.length)) {
                output.write(bytes);
            }
            try (HologramCutDiagnostics.Scope ignored = diagnostics.phase(HologramCutDiagnostics.Phase.JOURNAL_SYNC, bytes.length)) {
                output.getFD().sync();
            }
        }
        // No non-atomic fallback: failure leaves the previous authoritative record intact.
        try (HologramCutDiagnostics.Scope ignored = diagnostics.phase(HologramCutDiagnostics.Phase.JOURNAL_REPLACE, bytes.length)) {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
