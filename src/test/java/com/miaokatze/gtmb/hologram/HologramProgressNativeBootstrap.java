package com.miaokatze.gtmb.hologram;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.relauncher.Side;

/** Test-only mod. The common bootstrap has no client type references and never enables the lab. */
@Mod(
    modid = "gtmb_progress_native_probe",
    name = "GTMB isolated paired progress observer",
    version = "1",
    dependencies = "required-after:gtmb",
    acceptableRemoteVersions = "*")
public final class HologramProgressNativeBootstrap {

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        if (event.getSide() == Side.CLIENT && Boolean.getBoolean("gtmb.progress.pairedClient"))
            Class.forName("com.miaokatze.gtmb.client.hologram.HologramProgressNativeProbe")
                .getMethod("install")
                .invoke(null);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (Boolean.getBoolean("gtmb.progress.pairedServer")) HologramProgressNativeFixture.install();
    }

    public static Path root() {
        Path value = Paths.get(System.getProperty("gtmb.progress.pairedRoot", ""))
            .toAbsolutePath()
            .normalize();
        require(
            value.getParent() != null && "gtmb-progress-paired".equals(
                value.getParent()
                    .getFileName()
                    .toString())
                && value.getFileName()
                    .toString()
                    .matches("v015-paired-[a-z0-9-]+")
                && Files.isDirectory(value.resolve("receipts"))
                && !Files.isSymbolicLink(value),
            "fresh prepared paired root");
        return value;
    }

    public static void write(String name, NBTTagCompound data) throws Exception {
        require(name.matches("[a-z0-9-]+"), "local native receipt name");
        Path target = root().resolve("receipts")
            .resolve(name + ".nbt");
        require(!Files.exists(target), "preserve prior native receipt bytes");
        Path temporary = Files.createTempFile(target.getParent(), "native-receipt-", ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE)) {
            CompressedStreamTools.writeCompressed(data, output);
        }
        Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public static NBTTagCompound read(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
