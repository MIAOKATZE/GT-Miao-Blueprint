package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Checks the host boundary without initializing Minecraft or a GL context. */
public final class HologramUiHostRegression {

    private static final String PACKAGE = "com/miaokatze/gtmb/client/hologram/";

    private HologramUiHostRegression() {}

    public static void run() {
        try {
            Map<String, Integer> ports = methods(PACKAGE + "HologramUiHost");
            Map<String, Integer> implementations = methods(PACKAGE + "HologramScreen");
            Map<String, Integer> inherited = new LinkedHashMap<>();
            for (String parent = reader(PACKAGE + "HologramScreen").getSuperName(); parent != null
                && !"java/lang/Object".equals(parent);) {
                inherited.putAll(methods(parent));
                parent = reader(parent).getSuperName();
            }
            SimpleAssert.that(!ports.isEmpty(), "the production host contract was actually inspected");
            for (String port : ports.keySet()) {
                SimpleAssert.that(
                    !inherited.containsKey(port),
                    "host port must have its own name across Minecraft remapping: " + port);
                Integer access = implementations.get(port);
                SimpleAssert.that(access != null, "screen explicitly implements host port: " + port);
                SimpleAssert.that(
                    (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) == Opcodes.ACC_PUBLIC,
                    "host implementation is a public concrete instance method: " + port);
            }
        } catch (IOException failure) {
            throw new AssertionError("cannot inspect the production host contract", failure);
        }
    }

    private static Map<String, Integer> methods(String type) throws IOException {
        Map<String, Integer> methods = new LinkedHashMap<>();
        reader(type).accept(new ClassVisitor(Opcodes.ASM5) {

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
                if (!name.startsWith("<")) methods.put(name + descriptor, access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static ClassReader reader(String type) throws IOException {
        try (InputStream input = HologramUiHostRegression.class.getClassLoader()
            .getResourceAsStream(type + ".class")) {
            if (input == null) throw new IOException("missing class resource: " + type);
            return new ClassReader(input);
        }
    }
}
