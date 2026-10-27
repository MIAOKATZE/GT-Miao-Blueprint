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
        try (HologramCutDiagnostics.Scope ignored = diagnostics
            .phase(HologramCutDiagnostics.Phase.JOURNAL_LOAD, length)) {
            result = HologramSnapshotCodec.decode(Files.readAllBytes(file));
        }
        if (result.getInteger("version") != HologramCutProtocol.VERSION) throw new IOException("剪切存储版本无效");
        return result;
    }

    void save(NBTTagCompound data) throws IOException {
        save(data, diagnostics);
    }

    void save(NBTTagCompound data, HologramCutDiagnostics trace) throws IOException {
        prepare(data, trace).write();
    }

    /**
     * Pure encoding of caller-owned NBT; no world/registry access or I/O. The caller must not mutate data during
     * this call. The returned bytes are private and immutable, so later changes to data cannot alter the write.
     * This is a synchronous boundary, not permission to serialize shared mutable NBT on a worker.
     */
    PreparedWrite prepare(NBTTagCompound data, HologramCutDiagnostics trace) throws IOException {
        try (HologramCutDiagnostics.Scope ignored = trace.phase(HologramCutDiagnostics.Phase.JOURNAL_ENCODE, 1)) {
            return new PreparedWrite(HologramSnapshotCodec.compress(data), trace);
        }
    }

    /** One attempted replacement, tied to this exact storage and preparing owner thread. Never cached or shared. */
    final class PreparedWrite {

        private final byte[] bytes;
        private final HologramCutDiagnostics trace;
        private final Thread owner = Thread.currentThread();
        private boolean attempted;

        private PreparedWrite(byte[] bytes, HologramCutDiagnostics trace) {
            this.bytes = bytes;
            this.trace = trace;
        }

        WriteReceipt write() throws IOException {
            if (Thread.currentThread() != owner) throw new IOException("剪切存储写入必须由原操作线程完成");
            if (attempted) throw new IOException("剪切存储写入已尝试，请重新读取权威记录");
            attempted = true;
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                try (HologramCutDiagnostics.Scope ignored = trace
                    .phase(HologramCutDiagnostics.Phase.JOURNAL_WRITE, bytes.length)) {
                    output.write(bytes);
                }
                try (HologramCutDiagnostics.Scope ignored = trace
                    .phase(HologramCutDiagnostics.Phase.JOURNAL_SYNC, bytes.length)) {
                    output.getFD()
                        .sync();
                }
            }
            // No non-atomic fallback: failure leaves the previous authoritative record intact.
            try (HologramCutDiagnostics.Scope ignored = trace
                .phase(HologramCutDiagnostics.Phase.JOURNAL_REPLACE, bytes.length)) {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            return new WriteReceipt(this);
        }
    }

    /** Completion of this journal replacement only; NOT a native chunk writer completion receipt. */
    static final class WriteReceipt {

        private final PreparedWrite write;

        private WriteReceipt(PreparedWrite write) {
            this.write = write;
        }

        boolean confirms(PreparedWrite expected) {
            return write == expected;
        }
    }
}
