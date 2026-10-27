package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real temp-file boundaries, without a world, native writer, global queue or crash/power-loss claim. */
final class HologramCutStorageBoundaryRegression {

    private HologramCutStorageBoundaryRegression() {}

    static void run() throws IOException {
        Path root = Files.createTempDirectory("gtmb-cut-boundary");
        try {
            UUID owner = UUID.randomUUID();
            HologramCutStorage storage = new HologramCutStorage(root, owner);
            NBTTagCompound data = storage.load();
            data.setString("value", "encoded-original");
            List<String> summaries = new ArrayList<>();
            try (HologramCutDiagnostics trace = new HologramCutDiagnostics(summaries::add, System::nanoTime)) {
                HologramCutStorage.PreparedWrite write = storage.prepare(data, trace);
                HologramCutStorage.PreparedWrite other = storage.prepare(data, HologramCutDiagnostics.QUIET);
                data.setString("value", "caller-mutated");
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread wrongOwner = new Thread(() -> {
                    try {
                        write.write();
                    } catch (Throwable expected) {
                        failure.set(expected);
                    }
                }, "cut-storage-boundary-test");
                wrongOwner.start();
                try {
                    wrongOwner.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IOException("storage boundary test interrupted", interrupted);
                }
                SimpleAssert
                    .that(failure.get() instanceof IOException, "prepared journal refuses a different owner thread");
                HologramCutStorage.WriteReceipt receipt = write.write();
                SimpleAssert.that(
                    receipt.confirms(write) && !receipt.confirms(other),
                    "journal receipt binds exact prepared write");
                SimpleAssert.eq(
                    "encoded-original",
                    storage.load()
                        .getString("value"),
                    "prepared bytes isolate later caller mutation");
                rejects(write, "a prepared replacement cannot be replayed");
                trace.completed();
            }
            SimpleAssert.that(summaries.size() == 1, "explicit recovery trace emits one summary");
            for (String phase : new String[] { "JOURNAL_ENCODE", "JOURNAL_WRITE", "JOURNAL_SYNC", "JOURNAL_REPLACE" })
                SimpleAssert.that(
                    summaries.get(0)
                        .contains(phase + "[calls=1,"),
                    "explicit trace covers quiet storage " + phase);
            Path temporary = root.resolve("gtmb-cut")
                .resolve(owner + ".nbt.tmp");
            Files.createDirectory(temporary);
            HologramCutStorage.PreparedWrite blocked = storage.prepare(data, HologramCutDiagnostics.QUIET);
            rejects(blocked, "failed temporary write is reported");
            SimpleAssert.eq(
                "encoded-original",
                storage.load()
                    .getString("value"),
                "failed write preserves authoritative journal");
            Files.delete(temporary);
            rejects(blocked, "failed attempt requires a fresh authoritative operation, not stale replay");
            storage.save(data);
            SimpleAssert.eq(
                "caller-mutated",
                storage.load()
                    .getString("value"),
                "fresh ordinary save remains supported");
        } finally {
            try (Stream<Path> files = Files.walk(root)) {
                Path[] paths = files.sorted(Comparator.reverseOrder())
                    .toArray(Path[]::new);
                for (Path path : paths) Files.deleteIfExists(path);
            }
        }
    }

    private static void rejects(HologramCutStorage.PreparedWrite write, String label) throws IOException {
        try {
            write.write();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(label);
    }
}
