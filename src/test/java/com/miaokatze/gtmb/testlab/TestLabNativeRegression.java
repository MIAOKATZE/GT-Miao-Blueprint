package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Additive claims, bounded leases and precise stack variants; no native world/creative callback is simulated. */
final class TestLabNativeRegression {

    private TestLabNativeRegression() {}

    static void run() {
        Path directory = null;
        try {
            TestLabConfig cfg = new TestLabConfig(
                false,
                true,
                1048578,
                1048578,
                4096,
                4096,
                256,
                80,
                8,
                512,
                5,
                60,
                64,
                512);
            SimpleAssert.that(
                !cfg.nativePlatforms && cfg.nativeMods.isEmpty() && cfg.nativeMachines.isEmpty(),
                "native persistent platforms default off and require explicit selections");
            directory = Files.createTempDirectory("gtmb-native-claims-");
            new TestLabOwnership(directory, cfg);
            Path original = directory.resolve("gtmb-testlab/ownership-" + cfg.dimension + ".nbt");
            byte[] originalBytes = Files.readAllBytes(original);
            cfg.nativePlatforms = true;
            cfg.nativeMods = Collections.singleton("minecraft");
            TestLabOwnership ownership = new TestLabOwnership(directory, cfg);
            SimpleAssert.that(
                Arrays.equals(originalBytes, Files.readAllBytes(original)),
                "native additive ownership leaves the original five-zone marker unchanged");
            SimpleAssert.eq(5, cfg.zones.size(), "native zones never join legacy reset/refresh facilities");
            TestLabZone a = cfg.nativeZone(0, false), b = cfg.nativeZone(0, true), c = cfg.nativeZone(64, false);
            SimpleAssert.that(
                a.x % 512 == 0 && a.z % 512 == 0 && b.x == a.x + 32 && c.z == a.z + 512,
                "page source and target stay inside one dedicated region with stable grid coordinates");
            SimpleAssert.that(
                cfg.nativeMachineBaseX() > cfg.nativeZone(4095, true).x + 31,
                "large machine column remains beyond every catalog page even at minimum legacy spacing");
            ItemStack variant = new ItemStack(Items.diamond, 1, 7);
            NBTTagCompound body = new NBTTagCompound();
            body.setString("nativeVariant", "exact-nbt");
            variant.setTagCompound(body);
            TestLabNativeCatalog.Page page = new TestLabNativeCatalog.Page(
                "minecraft",
                0,
                0,
                Collections.singletonList(variant));
            NBTTagCompound stack = TestLabNativeCatalog.stack(page.stacks.get(0));
            SimpleAssert.eq(7, (int) stack.getShort("Damage"), "catalog keeps actual native metadata");
            SimpleAssert.eq(body, stack.getCompoundTag("tag"), "catalog keeps full native variant NBT");
            Path region = directory.resolve("DIM" + cfg.dimension)
                .resolve("region")
                .resolve("r." + (a.x >> 9) + "." + (a.z >> 9) + ".mca");
            Files.createDirectories(region.getParent());
            Files.write(region, new byte[] { 1 });
            refused(
                () -> ownership.nativePage(page, cfg, true),
                "native page never adopts an existing unclaimed region");
            Files.delete(region);
            NBTTagCompound claimed = ownership.nativePage(page, cfg, true);
            SimpleAssert.eq("CLAIMED", claimed.getString("state"), "new native page requires completed build evidence");
            claimed.setString("state", "READY");
            claimed.removeTag("freshClaim");
            ownership.nativeManifest(0, claimed);
            NBTTagCompound detached = ownership.nativePage(page, cfg, false);
            detached.setString("state", "tampered");
            SimpleAssert.eq(
                "READY",
                ownership.nativePage(page, cfg, false)
                    .getString("state"),
                "native page manifest reads cannot rewrite persistent readiness");
            TestLabNativeCatalog.Page changed = new TestLabNativeCatalog.Page(
                "minecraft",
                0,
                0,
                Collections.singletonList(new ItemStack(Items.diamond, 1, 8)));
            refused(
                () -> ownership.nativePage(changed, cfg, true),
                "native variant changes cannot silently reuse a page");
        } catch (Exception failure) {
            throw new AssertionError("native platform claims and catalog identities", failure);
        } finally {
            if (directory != null) try (Stream<Path> paths = Files.walk(directory)) {
                Path[] all = paths.sorted(Comparator.reverseOrder())
                    .toArray(Path[]::new);
                for (Path path : all) Files.delete(path);
            } catch (IOException failure) {
                throw new AssertionError("native fixture temporary directory cleanup", failure);
            }
        }
    }

    private static void refused(Checked operation, String message) throws Exception {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (IOException expected) {}
    }

    private interface Checked {

        void run() throws Exception;
    }
}
