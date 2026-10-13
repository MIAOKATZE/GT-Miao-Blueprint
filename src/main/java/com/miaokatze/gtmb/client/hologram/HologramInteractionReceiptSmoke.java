package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Locale;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/** Reads one real publication, closing the Windows filesystem handle before decoding; never returns stale/fake NBT. */
final class HologramInteractionReceiptSmoke {

    private final File file;
    private int retries;

    HologramInteractionReceiptSmoke(File receipts) {
        file = new File(receipts, "world-receipt.nbt");
    }

    NBTTagCompound read() throws IOException {
        try {
            byte[] encoded = Files.readAllBytes(file.toPath());
            NBTTagCompound decoded;
            try (ByteArrayInputStream bytes = new ByteArrayInputStream(encoded)) {
                decoded = CompressedStreamTools.readCompressed(bytes);
            }
            retries = 0;
            return decoded;
        } catch (IOException unavailable) {
            boolean transientPublication = unavailable instanceof NoSuchFileException
                || unavailable instanceof EOFException;
            if (unavailable instanceof FileSystemException) {
                FileSystemException filesystemFailure = (FileSystemException) unavailable;
                boolean receiptAccessDenied = unavailable instanceof java.nio.file.AccessDeniedException
                    && filesystemFailure.getFile() != null
                    && file.toPath()
                        .toAbsolutePath()
                        .normalize()
                        .equals(
                            java.nio.file.Paths.get(filesystemFailure.getFile())
                                .toAbsolutePath()
                                .normalize());
                String reason = String.valueOf(((FileSystemException) unavailable).getReason())
                    .toLowerCase(Locale.ROOT);
                transientPublication |= receiptAccessDenied || reason.contains("另一个程序")
                    || reason.contains("another process")
                    || reason.contains("being used")
                    || reason.contains("sharing violation");
            }
            if (!transientPublication || ++retries > 20) throw unavailable;
            if (retries == 1) System.out.println(
                "[GTMB-HOLOGRAM-INTERACTION-CLIENT] receipt transient " + unavailable.getClass()
                    .getSimpleName() + "; bounded 20 tick retry");
            return null;
        }
    }
}
