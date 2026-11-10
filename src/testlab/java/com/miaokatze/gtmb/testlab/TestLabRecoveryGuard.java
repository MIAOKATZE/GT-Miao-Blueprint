package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

/** Read-only durable guard. Reset must never erase the fixture of an unsettled production transaction. */
final class TestLabRecoveryGuard {

    private TestLabRecoveryGuard() {}

    static UUID recoveryActor(Path root) throws IOException {
        return UUID.nameUUIDFromBytes(
            ("gtmb-construction-recovery-probe:" + root.toFile()
                .getCanonicalPath()).getBytes(StandardCharsets.UTF_8));
    }

    static void verify(Path root, TestLabConfig config, UUID owner, String kind) throws IOException {
        Set<UUID> actors = new LinkedHashSet<UUID>();
        UUID recovery = recoveryActor(root);
        actors.add(recovery);
        actors.add(UUID.nameUUIDFromBytes(("gtmb-testlab-agent:" + owner).getBytes(StandardCharsets.UTF_8)));
        if (!config.compositeActorUuid.isEmpty()) actors.add(UUID.fromString(config.compositeActorUuid));
        if (!config.nativeActorUuid.isEmpty()) actors.add(UUID.fromString(config.nativeActorUuid));
        Path directory = root.resolve("gtmb-construction-recovery");
        if (Files.isSymbolicLink(directory))
            throw new IOException("Construction recovery directory may not be a symbolic link");
        for (UUID actor : actors) {
            Path file = directory.resolve(actor.toString() + ".nbt");
            if (!Files.exists(file) && !Files.isSymbolicLink(file)) continue;
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 32L * 1024 * 1024)
                throw new IOException(
                    "Construction recovery record is not a bounded regular file; no lab mutation allowed");
            NBTTagCompound journal = HologramSnapshotCodec.decode(Files.readAllBytes(file));
            if (!actor.toString()
                .equals(journal.getString("player")))
                throw new IOException("Construction recovery record owner differs");
            if (!"settled".equals(journal.getString("phase"))
                && !(actor.equals(recovery) && "recovery-resume".equals(kind)))
                throw new IOException(
                    "Unsettled construction recovery for " + actor
                        + "; reset/refresh and new fixture mutations are blocked; resolve or explicitly resume recovery first");
        }
    }
}
