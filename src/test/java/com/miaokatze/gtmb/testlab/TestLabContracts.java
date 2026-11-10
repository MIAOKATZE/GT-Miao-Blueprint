package com.miaokatze.gtmb.testlab;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Focused source-set, bounds, ownership and public contract regression; no game/server process is started. */
public final class TestLabContracts {

    private TestLabContracts() {}

    private static void verifyPermissionGate() throws Exception {
        java.lang.reflect.Field configured = TestLab.class.getDeclaredField("config");
        java.lang.reflect.Field registered = TestLab.class.getDeclaredField("dimensionRegistered");
        configured.setAccessible(true);
        registered.setAccessible(true);
        Object original = configured.get(null);
        boolean previous = registered.getBoolean(null);
        try {
            configured.set(
                null,
                new TestLabConfig(true, false, 1048578, 1048578, 4096, 4096, 4096, 80, 8, 512, 5, 60, 64, 512));
            registered.setBoolean(null, true);
            TestLabCommand command = new TestLabCommand(null);
            for (int level = 0; level <= 4; level++) {
                final int permissions = level;
                net.minecraft.command.ICommandSender sender = (net.minecraft.command.ICommandSender) java.lang.reflect.Proxy
                    .newProxyInstance(
                        TestLabContracts.class.getClassLoader(),
                        new Class<?>[] { net.minecraft.command.ICommandSender.class },
                        (proxy, method, arguments) -> "canCommandSenderUseCommand".equals(method.getName())
                            ? (Integer) arguments[0] <= permissions
                            : null);
                SimpleAssert.that(
                    command.canCommandSenderUseCommand(sender) == (level >= 2),
                    "configured test command OP2 gate at level " + level);
            }
        } finally {
            configured.set(null, original);
            registered.setBoolean(null, previous);
        }
    }

    private static void verifyRecoveryGuard() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("gtmb-reset-recovery-guard-");
        java.nio.file.Path directory = java.nio.file.Files.createDirectory(root.resolve("gtmb-construction-recovery"));
        java.util.UUID actor = TestLabRecoveryGuard.recoveryActor(root);
        java.nio.file.Path file = directory.resolve(actor.toString() + ".nbt");
        net.minecraft.nbt.NBTTagCompound journal = new net.minecraft.nbt.NBTTagCompound();
        journal.setString("player", actor.toString());
        journal.setString("phase", "world-uncertain");
        byte[] original = com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(journal);
        java.nio.file.Files.write(file, original);
        for (String action : new String[] { "reset", "refresh", "minimal", "construction-performance" }) {
            boolean rejected = false;
            try {
                TestLabRecoveryGuard.verify(root, TestLabRegression.config(4096), java.util.UUID.randomUUID(), action);
            } catch (java.io.IOException expected) {
                rejected = true;
            }
            SimpleAssert.that(rejected, "pending production recovery rejects " + action);
        }
        TestLabRecoveryGuard
            .verify(root, TestLabRegression.config(4096), java.util.UUID.randomUUID(), "recovery-resume");
        SimpleAssert.that(
            java.util.Arrays.equals(original, java.nio.file.Files.readAllBytes(file)),
            "recovery guard never rewrites evidence");
        journal.setString("phase", "settled");
        java.nio.file.Files.write(file, com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(journal));
        TestLabRecoveryGuard.verify(root, TestLabRegression.config(4096), java.util.UUID.randomUUID(), "reset");
    }

    public static void main(String[] args) throws Exception {
        int status = 0;
        try {
            if (!MinecraftTestBootstrap.enter(TestLabContracts.class, args)) return;
            TestLabRegression.run();
            SimpleAssert.eq(2, new TestLabCommand(null).getRequiredPermissionLevel(), "test operations require OP2");
            java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
            int defaults = 0;
            for (com.miaokatze.miaokatzecore.api.testlab.LabCase entry : TestLabModule.INSTANCE.cases()) {
                SimpleAssert.that(ids.add(entry.id()), "catalog IDs remain unique");
                if (entry.defaultSuite()) defaults++;
                if (entry.id()
                    .startsWith("legacy-"))
                    SimpleAssert.that(!entry.defaultSuite(), "legacy probes never join the default suite");
            }
            SimpleAssert.eq(1, defaults, "minimal is the only default case");
            SimpleAssert.that(
                ids.containsAll(
                    java.util.Arrays.asList(
                        "minimal",
                        "stress",
                        "composite",
                        "native-build",
                        "native-test",
                        "recovery-stage",
                        "recovery-resume",
                        "construction-performance",
                        "reset",
                        "refresh")),
                "all ten existing kinds remain discoverable");
            SimpleAssert.that(!TestLabModule.INSTANCE.ownsWorld(null), "null world never gains ownership");
            verifyPermissionGate();
            verifyRecoveryGuard();
            TestLabReport evidence = new TestLabReport("contract-run", "minimal", TestLabRegression.config(4096));
            SimpleAssert.that(evidence.data.getBoolean("notNetty"), "common test does not claim network dispatch");
            SimpleAssert.eq(
                "NOT_RUN",
                evidence.data.getCompoundTag("coverage")
                    .getString("render"),
                "no GL evidence fabricated");
            SimpleAssert.that(
                !evidence.data.getCompoundTag("artifact")
                    .getString("codeSource")
                    .isEmpty(),
                "actual loaded code source recorded");
            System.out.println(
                "GTMB testlab contracts PASS: ownership/config/native catalogs, OP2, ten kinds, legacy discovery, artifact/coverage");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            status = 1;
        }
        System.exit(status);
    }
}
