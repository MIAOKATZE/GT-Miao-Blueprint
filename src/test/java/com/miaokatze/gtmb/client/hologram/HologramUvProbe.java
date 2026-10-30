package com.miaokatze.gtmb.client.hologram;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Test-only entry counts of the actual atlas/wrapper getters, never registered or packaged by production. */
public final class HologramUvProbe implements IClassTransformer {

    private static final String BASE = "net.minecraft.client.renderer.texture.TextureAtlasSprite";
    private static final String WRAPPER = "appeng.client.texture.FlippableIcon";
    private static final String[] GETTERS = { "getMinU", "getMaxU", "getMinV", "getMaxV" };
    private static final int[] counts = new int[8];
    private static int hooks;
    private static Thread measuring;

    static void install(Class<?> suite) {
        if (suite.getClassLoader() != HologramUvProbe.class.getClassLoader()
            || !(suite.getClassLoader() instanceof LaunchClassLoader))
            throw new AssertionError("UV probe and execution must share the isolated launch loader");
        ((LaunchClassLoader) suite.getClassLoader()).registerTransformer(HologramUvProbe.class.getName());
    }

    static void begin() {
        if (hooks != 255) throw new AssertionError("The eight real atlas/wrapper getter bodies were not instrumented");
        if (measuring != null) throw new AssertionError("Nested UV measurement");
        java.util.Arrays.fill(counts, 0);
        measuring = Thread.currentThread();
    }

    static int[] end() {
        measuring = null;
        return counts.clone();
    }

    public static void entered(int kind) {
        if (measuring == Thread.currentThread()) counts[kind]++;
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] source) {
        if (source == null || !(BASE.equals(transformedName) || WRAPPER.equals(transformedName))) return source;
        ClassReader reader = new ClassReader(source);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {

            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (!"()F".equals(descriptor)) return delegate;
                int selected = -1;
                for (int at = 0; at < GETTERS.length; at++)
                    if (GETTERS[at].equals(method)) selected = at + (WRAPPER.equals(transformedName) ? 4 : 0);
                if (selected < 0) return delegate;
                final int kind = selected;
                hooks |= 1 << kind;
                return new MethodVisitor(Opcodes.ASM5, delegate) {

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitIntInsn(Opcodes.BIPUSH, kind);
                        super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            HologramUvProbe.class.getName()
                                .replace('.', '/'),
                            "entered",
                            "(I)V",
                            false);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
