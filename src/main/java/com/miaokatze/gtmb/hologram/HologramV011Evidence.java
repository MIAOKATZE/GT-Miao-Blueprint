package com.miaokatze.gtmb.hologram;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/** Fail-closed evidence IO for the separately opted-in follow-up fixtures. */
public final class HologramV011Evidence {

    private HologramV011Evidence() {}

    public static Path directory() throws Exception {
        String value = System.getProperty("gtmb.hologram.followupReceiptDir", "");
        String fixture = System.getProperty("gtmb.hologram.followupRoot", "");
        require(!value.isEmpty() && !fixture.isEmpty(), "explicit follow-up fixture and receipts required");
        Path root = Paths.get(fixture)
            .toRealPath();
        Path output = Paths.get(value)
            .toRealPath();
        require(
            output.startsWith(root) && output.getFileName()
                .toString()
                .equals("receipts"),
            "receipts must stay in the prepared isolated fixture");
        require(Files.isRegularFile(root.resolve("environment.json")), "prepared environment manifest required");
        return output;
    }

    public static void write(String name, NBTTagCompound data) throws Exception {
        require(name.matches("[a-zA-Z0-9_.-]+\\.nbt"), "bounded NBT receipt name");
        Path output = directory();
        Path target = output.resolve(name);
        Path temporary = output.resolve(name + ".writing");
        try (FileOutputStream stream = new FileOutputStream(temporary.toFile())) {
            CompressedStreamTools.writeCompressed(data, stream);
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static NBTTagCompound read(String name) throws Exception {
        Path target = directory().resolve(name);
        if (!Files.isRegularFile(target)) return null;
        try (FileInputStream input = new FileInputStream(target.toFile())) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    public static void marker(String name, String value) throws Exception {
        require(name.matches("[a-zA-Z0-9_.-]+\\.txt"), "bounded marker name");
        Files.write(directory().resolve(name), (value + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("follow-up fixture: " + message);
    }
}
