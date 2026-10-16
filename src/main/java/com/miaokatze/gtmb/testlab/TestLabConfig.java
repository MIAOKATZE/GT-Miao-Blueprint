package com.miaokatze.gtmb.testlab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraftforge.common.config.Configuration;

/** Only this local Forge configuration can opt in. No system property or command changes this switch. */
final class TestLabConfig {

    static final int DEFAULT_DIMENSION = 1048578;
    final boolean enabled, performance;
    final int dimension, provider, maxChunks, operations, tickMillis, runSeconds, iterations, payloadKiB;
    final List<TestLabZone> zones;

    TestLabConfig(boolean enabled, boolean performance, int dimension, int provider, int baseX, int baseZ, int spacing,
        int y, int maxChunks, int operations, int tickMillis, int runSeconds, int iterations, int payloadKiB) {
        if (dimension < 2 || dimension > 2000000 || provider < 2 || provider > 2000000)
            throw new IllegalArgumentException("testlab IDs must be 2..2000000");
        if (spacing < 256 || spacing > 65536
            || (spacing & 15) != 0
            || (baseX & 15) != 0
            || (baseZ & 15) != 0
            || Math.abs((long) baseX) > 1000000
            || Math.abs((long) baseZ) > 1000000
            || y < 32
            || y > 224)
            throw new IllegalArgumentException(
                "testlab layout must be aligned, separated and inside safe world bounds");
        if (maxChunks < 4 || maxChunks > 32
            || operations < 1
            || operations > 4096
            || tickMillis < 1
            || tickMillis > 20
            || runSeconds < 10
            || runSeconds > 300
            || iterations < 1
            || iterations > 10000
            || payloadKiB < 1
            || payloadKiB > 1024) throw new IllegalArgumentException("testlab work budget out of range");
        this.enabled = enabled;
        this.performance = performance;
        this.dimension = dimension;
        this.provider = provider;
        this.maxChunks = maxChunks;
        this.operations = operations;
        this.tickMillis = tickMillis;
        this.runSeconds = runSeconds;
        this.iterations = iterations;
        this.payloadKiB = payloadKiB;
        List<TestLabZone> layout = new ArrayList<>();
        String[] names = { "minimal", "capture", "cut", "codec", "stress" };
        for (int i = 0; i < names.length; i++)
            layout.add(new TestLabZone(names[i], baseX + i * spacing, y, baseZ + (i == 4 ? spacing * 2 : 0)));
        zones = Collections.unmodifiableList(layout);
    }

    static TestLabConfig load(File directory) throws IOException {
        Path file = new File(directory, "gtmb-testlab.cfg").toPath()
            .toAbsolutePath()
            .normalize();
        if (Files.exists(file) && (!Files.isRegularFile(file) || Files.size(file) > 65536))
            throw new IOException("testlab configuration must be a regular file <=64KiB");
        Files.createDirectories(file.getParent());
        Path copy = Files.createTempFile(file.getParent(), "gtmb-testlab-read-", ".cfg");
        try {
            if (Files.exists(file)) Files.copy(file, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Configuration cfg = new Configuration(copy.toFile());
            cfg.load();
            String c = "testlab";
            TestLabConfig value = new TestLabConfig(
                cfg.get(
                    c,
                    "enabled",
                    false,
                    "Local opt-in only. False registers no lab dimension, handlers, commands, probes or worlds.")
                    .getBoolean(false),
                cfg.get(
                    c,
                    "performanceDiagnostics",
                    true,
                    "Collect bounded common-side counters only while this opt-in lab is enabled.")
                    .getBoolean(true),
                cfg.get(
                    c,
                    "dimensionId",
                    DEFAULT_DIMENSION,
                    "High dimension ID. Occupied IDs disable the lab; no automatic replacement.")
                    .getInt(DEFAULT_DIMENSION),
                cfg.get(
                    c,
                    "providerId",
                    DEFAULT_DIMENSION,
                    "Provider ID. A collision disables registration without taking ownership.")
                    .getInt(DEFAULT_DIMENSION),
                cfg.get(c, "baseX", 4096)
                    .getInt(4096),
                cfg.get(c, "baseZ", 4096)
                    .getInt(4096),
                cfg.get(c, "zoneSpacing", 4096)
                    .getInt(4096),
                cfg.get(c, "zoneY", 80)
                    .getInt(80),
                cfg.get(c, "maxOwnedChunks", 8)
                    .getInt(8),
                cfg.get(c, "operationsPerTick", 512)
                    .getInt(512),
                cfg.get(c, "tickBudgetMillis", 5)
                    .getInt(5),
                cfg.get(c, "runTimeoutSeconds", 60)
                    .getInt(60),
                cfg.get(c, "stressIterations", 64)
                    .getInt(64),
                cfg.get(c, "stressPayloadKiB", 512)
                    .getInt(512));
            if (!Files.exists(file)) {
                cfg.save();
                if (!Files.isRegularFile(copy) || Files.size(copy) == 0)
                    throw new IOException("testlab default config save failed");
                Files.move(copy, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            return value;
        } catch (RuntimeException failure) {
            throw new IOException("testlab configuration rejected; lab disabled", failure);
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    boolean contains(int x, int y, int z, int sx, int sy, int sz) {
        for (TestLabZone zone : zones) if (zone.contains(x, y, z, sx, sy, sz)) return true;
        return false;
    }

    String layout() {
        StringBuilder out = new StringBuilder();
        for (TestLabZone zone : zones) out.append(zone.id)
            .append(':')
            .append(zone.x)
            .append(',')
            .append(zone.y)
            .append(',')
            .append(zone.z)
            .append(';');
        return out.toString();
    }
}
