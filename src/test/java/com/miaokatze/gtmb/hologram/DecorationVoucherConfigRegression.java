package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.config.Configuration;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real Forge configuration migration, durable edits and policy-version fences. */
final class DecorationVoucherConfigRegression {

    static void run() throws IOException, ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            Configuration legacy = new Configuration(fixture.file.toFile());
            legacy.get("decorationVoucher", "enabled", false)
                .set(false);
            legacy.get("decorationVoucher", "allowedModIds", new String[0])
                .set(new String[] { "chisel" });
            legacy.get("decorationVoucher", "allowedBlocks", new String[0])
                .set(new String[] { "minecraft:glass@2" });
            legacy.get("decorationVoucher", "deniedBlocks", new String[0])
                .set(new String[] { "minecraft:wool@3" });
            legacy.save();
            DecorationVoucherConfig.load(fixture.directory.toFile());
            DecorationVoucherConfig.View migrated = DecorationVoucherConfig.view();
            SimpleAssert.that(
                migrated.allowedModIds.contains("ztones") && migrated.allowedModIds.contains("chiseltones"),
                "legacy partial decoration lists gain both complete Ztones namespaces");
            SimpleAssert.that(!migrated.enabled, "migration preserves the administrator's disabled setting");
            for (String material : new String[] { "minecraft:wool", "minecraft:hardened_clay",
                "minecraft:stained_hardened_clay", "etfuturum:concrete", HologramPictureBox.WOOLEN_CLAY })
                SimpleAssert
                    .that(migrated.allowedBlocks.contains(material), "old configs gain tile material: " + material);
            SimpleAssert
                .that(migrated.allowedBlocks.contains("minecraft:glass@2"), "custom allow rules survive migration");
            for (int meta = 0; meta < 6; meta++) SimpleAssert.that(
                migrated.allowedBlocks.contains("enderio:blockfusedquartz@" + meta),
                "old configs gain all registered Ender IO quartz-glass variants");
            DecorationVoucherConfig.View enabled = copy(migrated, true, migrated.allowedBlocks);
            SimpleAssert.that(
                DecorationVoucherConfig.save(enabled, DecorationVoucherConfig.revision()),
                "runtime save succeeds");
            for (int meta = 0; meta < 16; meta++) {
                SimpleAssert.eq(
                    meta != 3,
                    enabled.allows(new ItemStack(Blocks.wool, 1, meta)),
                    "all wool colors covered, blacklist wins");
                SimpleAssert.that(
                    enabled.allows(new ItemStack(Blocks.stained_hardened_clay, 1, meta)),
                    "all terracotta colors covered");
            }
            long revision = DecorationVoucherConfig.revision();
            DecorationVoucherConfig.View removed = copy(enabled, true, Collections.singleton("minecraft:glass@2"));
            SimpleAssert
                .that(!DecorationVoucherConfig.save(removed, revision - 1), "stale administrator save rejected");
            SimpleAssert.eq(enabled, DecorationVoucherConfig.view(), "rejected save retains current policy");
            SimpleAssert.eq(revision, DecorationVoucherConfig.revision(), "rejected save does not invalidate quotes");
            SimpleAssert.that(DecorationVoucherConfig.save(removed, revision), "explicit removal is saved");
            DecorationVoucherConfig.load(fixture.directory.toFile());
            SimpleAssert.eq(
                removed.allowedBlocks,
                DecorationVoucherConfig.view().allowedBlocks,
                "one-time migration never restores deliberately removed entries on restart");
            SimpleAssert.eq(
                enabled.deniedBlocks,
                DecorationVoucherConfig.view().deniedBlocks,
                "blacklist persists across restart");
            byte[] saved = Files.readAllBytes(fixture.file);
            Path blocked = Files.createDirectory(fixture.directory.resolve("blocked.cfg"));
            fixture.field("configurationFile")
                .set(null, blocked.toFile());
            revision = DecorationVoucherConfig.revision();
            DecorationVoucherConfig.View beforeFailure = DecorationVoucherConfig.view();
            boolean failed = false;
            try {
                DecorationVoucherConfig.save(enabled, revision);
            } catch (IllegalStateException expected) {
                failed = true;
            }
            SimpleAssert.that(failed, "IO failure cannot be reported as saved");
            SimpleAssert.eq(beforeFailure, DecorationVoucherConfig.view(), "failed persistence keeps live rules");
            SimpleAssert.eq(revision, DecorationVoucherConfig.revision(), "failed persistence keeps policy revision");
            SimpleAssert.that(
                Arrays.equals(saved, Files.readAllBytes(fixture.file)),
                "failed persistence preserves original file");
        }
    }

    static DecorationVoucherConfig.View copy(DecorationVoucherConfig.View old, boolean enabled,
        Iterable<String> blocks) {
        return new DecorationVoucherConfig.View(
            enabled,
            old.allowedModIds,
            blocks,
            old.allowedOres,
            old.deniedModIds,
            old.deniedBlocks,
            old.deniedOres);
    }

    static final class Fixture implements AutoCloseable {

        final Path directory = Files.createTempDirectory("gtmb-voucher-rules-");
        final Path file = directory.resolve(DecorationVoucherConfig.FILE_NAME);
        final Map<Field, Object> original = new LinkedHashMap<>();

        Fixture() throws IOException, ReflectiveOperationException {
            for (String name : new String[] { "configuration", "configurationFile", "current", "revision" }) {
                Field field = field(name);
                original.put(field, field.get(null));
            }
        }

        Field field(String name) throws NoSuchFieldException {
            Field field = DecorationVoucherConfig.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        @Override
        public void close() throws IOException {
            try {
                for (Map.Entry<Field, Object> entry : original.entrySet()) entry.getKey()
                    .set(null, entry.getValue());
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            } finally {
                try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                    for (Path path : files.sorted(java.util.Comparator.reverseOrder())
                        .toArray(Path[]::new)) Files.delete(path);
                }
            }
        }
    }
}
