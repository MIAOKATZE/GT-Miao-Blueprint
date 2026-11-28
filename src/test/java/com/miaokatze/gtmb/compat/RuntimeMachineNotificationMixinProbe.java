// SPDX-License-Identifier: AGPL-3.0-only
package com.miaokatze.gtmb.compat;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.launch.GlobalProperties;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** Applies the shipped notification Mixin to the ordinary GT runtime bytecode without launching Minecraft. */
public final class RuntimeMachineNotificationMixinProbe {

    private static final String TARGET = "gregtech.api.metatileentity.BaseMetaTileEntity";
    private static final String MIXIN = "com.miaokatze.gtmb.mixin.hologram.MixinHologramMachineNotifications";
    private static final String HANDLER = "gtmb$machineChanged";

    private RuntimeMachineNotificationMixinProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException(
                "release.jar gregtech-runtime.jar srg-minecraft.jar forge.jar [expect-missing-selector]");
        }
        File release = new File(args[0]);
        File gregtech = new File(args[1]);
        boolean negative = args.length == 5 && "expect-missing-selector".equals(args[4]);
        Path scratch = new File(".").toPath()
            .toAbsolutePath()
            .normalize();
        String config = "mixins.gtmb.runtime-notification-probe.json";
        Files.write(
            scratch.resolve(config),
            ("{\"required\":true,\"minVersion\":\"0.8\","
                + "\"package\":\"com.miaokatze.gtmb.mixin.hologram\",\"compatibilityLevel\":\"JAVA_8\","
                + "\"mixins\":[\"MixinHologramMachineNotifications\"],\"injectors\":{\"defaultRequire\":1}}")
                    .getBytes(StandardCharsets.UTF_8));

        // Use Mixin's existing LaunchWrapper service. No FML tweakers or game classes are initialized.
        Launch.blackboard = new HashMap<>();
        Launch.classLoader = new LaunchClassLoader(
            new URL[] { scratch.toUri()
                .toURL(),
                release.toURI()
                    .toURL(),
                gregtech.toURI()
                    .toURL(),
                new File(args[2]).toURI()
                    .toURL(),
                new File(args[3]).toURI()
                    .toURL() });
        Thread.currentThread()
            .setContextClassLoader(Launch.classLoader);
        byte[] target = read(gregtech, TARGET);
        byte[] mixin = read(release, MIXIN);
        require(Arrays.equals(target, Launch.classLoader.getClassBytes(TARGET)), "GT runtime byte source mismatch");
        require(Arrays.equals(mixin, Launch.classLoader.getClassBytes(MIXIN)), "Release Mixin byte source mismatch");
        ClassNode original = node(target);
        MethodNode originalEvent = method(original, "func_145842_c", "(II)Z");
        require(originalEvent != null, "The ordinary GT runtime must expose func_145842_c(II)Z");
        require(
            method(original, "receiveClientEvent", "(II)Z") == null,
            "Development GT bytecode entered runtime probe");

        // Register an empty platform through Mixin's supported bootstrap property. This prevents unrelated JAR
        // manifests from discovering FML coremods or adding their own Mixin configurations to this one-Mixin probe.
        GlobalProperties.put(GlobalProperties.Keys.PLATFORM_MANAGER, new MixinPlatformManager() {

            @Override
            public Collection<String> getPhaseProviderClasses() {
                return Collections.emptyList();
            }
        });
        MixinBootstrap.init();
        Method phase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        phase.setAccessible(true);
        phase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
        environment.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration(config);
        IMixinTransformer transformer = (IMixinTransformer) environment.getActiveTransformer();
        require(transformer != null, "Mixin did not install its real transformer");
        byte[] applied;
        try {
            applied = transformer.transformClass(environment, TARGET, target);
        } catch (Throwable failure) {
            if (!negative || !missingSelector(failure)) throw failure;
            String report = evidence(release, gregtech, target, mixin)
                + "result=EXPECTED_REAL_MIXIN_APPLY_FAILURE\nselector=receiveClientEvent\n"
                + "failure="
                + failure
                + "\n";
            Files.write(scratch.resolve("runtime-mixin-result.txt"), report.getBytes(StandardCharsets.UTF_8));
            System.out.println(report);
            return;
        }
        require(!negative, "The old release unexpectedly passed real Mixin APPLY");
        require(!Arrays.equals(applied, target), "Mixin returned unchanged runtime target bytecode");
        ClassNode transformed = node(applied);
        MethodNode event = method(transformed, "func_145842_c", "(II)Z");
        int handlers = 0;
        int hookCalls = 0;
        String handlerName = null;
        for (MethodNode candidate : transformed.methods) {
            if (!candidate.name.contains(HANDLER)) continue;
            handlers++;
            handlerName = candidate.name;
            for (AbstractInsnNode instruction : candidate.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals("com/miaokatze/gtmb/client/hologram/HologramClient")
                    && call.name.equals("nativeTileChanged")
                    && call.desc.equals("(Lnet/minecraft/tileentity/TileEntity;)V")) hookCalls++;
            }
        }
        require(handlers == 1 && hookCalls == 1, "Expected one merged production handler calling nativeTileChanged");
        int injections = 0;
        int returns = 0;
        for (AbstractInsnNode instruction : event.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.IRETURN) returns++;
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(transformed.name) && call.name.equals(handlerName)) injections++;
        }
        require(injections > 0 && injections == returns, "Expected actual handler injection at every runtime RETURN");
        require(returns == countReturns(originalEvent), "Runtime method RETURN count changed unexpectedly");
        Files.write(scratch.resolve("BaseMetaTileEntity.applied.class"), applied);
        String report = evidence(release, gregtech, target, mixin) + "result=REAL_MIXIN_APPLY_PASS\n"
            + "runtimeMethod=func_145842_c(II)Z\nmergedHandlers="
            + handlers
            + "\nreturnInjections="
            + injections
            + "\nnativeTileChangedCalls="
            + hookCalls
            + "\ntransformedSha256="
            + sha256(applied)
            + "\n";
        Files.write(scratch.resolve("runtime-mixin-result.txt"), report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
    }

    private static boolean missingSelector(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            if (cause.getClass()
                .getName()
                .endsWith("InvalidInjectionException") && message.contains("receiveClientEvent")
                && message.contains("could not find any targets")) return true;
        }
        return false;
    }

    private static String evidence(File release, File gregtech, byte[] target, byte[] mixin) throws Exception {
        return "javaVersion=" + System.getProperty("java.version")
            + "\ntargetResource="
            + Launch.classLoader.findResource(TARGET.replace('.', '/') + ".class")
            + "\nrelease="
            + release.getCanonicalPath()
            + "\nreleaseSha256="
            + sha256(Files.readAllBytes(release.toPath()))
            + "\ngregtech="
            + gregtech.getCanonicalPath()
            + "\ngregtechSha256="
            + sha256(Files.readAllBytes(gregtech.toPath()))
            + "\ntargetSha256="
            + sha256(target)
            + "\nmixinSha256="
            + sha256(mixin)
            + "\n";
    }

    private static byte[] read(File jar, String name) throws Exception {
        // URLClassLoader uses the same JDK multi-release selection as LaunchWrapper, including GT's Java 17 entry.
        try (URLClassLoader archive = new URLClassLoader(
            new URL[] { jar.toURI()
                .toURL() },
            null)) {
            try (InputStream input = archive.getResourceAsStream(name.replace('.', '/') + ".class")) {
                require(input != null, "Missing class " + name + " in " + jar);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                return output.toByteArray();
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode result = new ClassNode();
        new ClassReader(bytes).accept(result, 0);
        return result;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        return null;
    }

    private static int countReturns(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.IRETURN) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
