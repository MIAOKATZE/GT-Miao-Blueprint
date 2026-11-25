// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.UUID;

import com.github.bsideup.jabel.Desugar;

/** A single atomic version record binds public metadata, native source and its PNG. Not a player/cache directory. */
final class HologramNativePublicStore {

    private static final int MAGIC = 0x47544E32; // GTN2, server disk only
    static final String SUFFIX = ".gtnb-public";
    private final Path directory;

    @Desugar
    record Slot(String id, UUID owner, String uploader, long revision, String digest,
        HologramNativePublicArtifact.Description label, int bytes) {}

    HologramNativePublicStore(Path library) {
        directory = library.resolve(".gtmb-public-native-v2");
    }

    Path directory() {
        return directory;
    }

    void initialize() throws IOException {
        Files.createDirectories(directory);
        HologramNativePublicArtifact.need(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS),
            "Public native repository is not a real directory");
        try (var pending = Files.newDirectoryStream(directory, "incoming-*.tmp")) {
            for (Path path : pending) Files.deleteIfExists(path);
        }
    }

    Slot prepare(String id, UUID owner, String uploader, long revision, byte[] artifact) throws IOException {
        slotPath(id);
        HologramNativePublicArtifact.need(owner != null && uploader != null && uploader.length() <= 64
            && revision > 0 && revision < Long.MAX_VALUE, "Invalid native public owner or version");
        var pair = HologramNativePublicArtifact.decode(artifact);
        Path temporary = Files.createTempFile(directory, "incoming-audit-", ".tmp");
        try {
            var label = HologramNativePublicArtifact.audit(pair, temporary);
            return new Slot(id, owner, uploader, revision, HologramBlueprintArtifact.hash(artifact), label,
                artifact.length);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    String commit(Slot slot, byte[] artifact, Slot prior, Check current) throws IOException {
        Path target = slotPath(slot.id);
        Path pending = Files.createTempFile(directory, "incoming-record-", ".tmp");
        try {
            MessageDigest recordDigest = HologramLibraryProtocol.digest();
            try (FileChannel file = FileChannel.open(pending, StandardOpenOption.WRITE);
                DataOutputStream out = new DataOutputStream(
                    new DigestOutputStream(Channels.newOutputStream(file), recordDigest))) {
                out.writeInt(MAGIC);
                out.writeUTF(slot.id);
                out.writeUTF(slot.owner.toString());
                out.writeUTF(slot.uploader);
                out.writeLong(slot.revision);
                out.writeUTF(slot.digest);
                out.writeUTF(slot.label.name());
                out.writeUTF(slot.label.author());
                out.writeUTF(slot.label.version());
                out.writeInt(slot.label.cells());
                out.writeInt(slot.label.structures());
                out.writeInt(slot.bytes);
                out.write(artifact);
                // The disk seal covers server-authored owner, revision and display data as well as both file members.
                out.write(recordDigest.digest());
                out.flush();
                file.force(true);
            }
            current.check();
            if (prior == null) {
                HologramNativePublicArtifact.need(!Files.exists(target, LinkOption.NOFOLLOW_LINKS),
                    "Public slot was already occupied");
            } else {
                HologramNativePublicArtifact.need(prior.equals(load(target)), "Public revision changed on disk");
            }
            current.check();
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel index = FileChannel.open(directory, StandardOpenOption.READ)) {
                index.force(true);
                return "";
            } catch (IOException unavailable) {
                // Rename committed. Report the reduced crash durability, not a false refusal allowing a retry.
                return "已发布；系统未确认目录持久化";
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    interface Check { void check() throws IOException; }

    void delete(Slot expected) throws IOException {
        Path path = slotPath(expected.id);
        HologramNativePublicArtifact.need(expected.equals(load(path)), "Public revision changed on disk");
        Files.delete(path);
    }

    Slot load(Path path) throws IOException {
        return read(path, null, null);
    }

    byte[] download(Slot expected) throws IOException {
        byte[][] artifact = new byte[1][];
        read(slotPath(expected.id), expected, artifact);
        return artifact[0];
    }

    private Slot read(Path path, Slot expected, byte[][] retained) throws IOException {
        HologramNativePublicArtifact.need(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && Files.size(path) <= HologramLibraryProtocol.MAX_BYTES + 4096L, "Public native disk budget");
        MessageDigest recordDigest = HologramLibraryProtocol.digest();
        try (DataInputStream in = new DataInputStream(
            new DigestInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), recordDigest))) {
            HologramNativePublicArtifact.need(in.readInt() == MAGIC, "Public native record format");
            String id = HologramLibraryService.publicId(in.readUTF());
            UUID owner = UUID.fromString(in.readUTF());
            String uploader = in.readUTF();
            long revision = in.readLong();
            String digest = in.readUTF();
            String name = in.readUTF(), author = in.readUTF(), version = in.readUTF();
            int cells = in.readInt(), structures = in.readInt(), size = in.readInt();
            HologramNativePublicArtifact.need(slotPath(id).equals(path) && uploader.length() <= 64
                && revision > 0 && revision < Long.MAX_VALUE && digest.matches("[a-f0-9]{64}")
                && name.length() <= 128 && name.codePoints().anyMatch(cp -> !Character.isWhitespace(cp)) && author.length() <= 64 && version.length() <= 64
                && cells >= 0 && cells <= 4_000_000 && structures >= 0 && size > 0
                && size <= HologramLibraryProtocol.MAX_BYTES, "Invalid public native record");
            Slot slot = new Slot(id, owner, uploader, revision, digest,
                new HologramNativePublicArtifact.Description(name, author, version, cells, structures), size);
            HologramNativePublicArtifact.need(expected == null || expected.equals(slot), "Public revision changed on disk");
            byte[] body = new byte[size];
            in.readFully(body);
            byte[] calculated = recordDigest.digest(), storedSeal = new byte[calculated.length];
            in.readFully(storedSeal);
            HologramNativePublicArtifact.need(in.read() == -1 && MessageDigest.isEqual(calculated, storedSeal)
                && digest.equals(HologramBlueprintArtifact.hash(body)), "Public native record content changed");
            var pair = HologramNativePublicArtifact.decode(body);
            com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPng.verify(pair.cover(), pair.revision());
            if (retained != null) retained[0] = body;
            return slot;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid public native record", invalid);
        }
    }

    private Path slotPath(String id) throws IOException {
        return directory.resolve(HologramLibraryService.publicId(id) + SUFFIX);
    }
}
