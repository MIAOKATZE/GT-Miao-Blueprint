package com.miaokatze.gtmb.hologram;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Test-only method-entry/return instrumentation. Never packaged, registered or armed by production code. */
public final class HologramDecodeProbe implements IClassTransformer {

    private static final String NBT = "com.miaokatze.gtmb.hologram.HologramBlueprintNbt";
    private static final String CODEC = "com.miaokatze.gtmb.hologram.HologramSnapshotCodec";
    private static final int[] counts = new int[4];
    private static int hooks;
    private static Thread measuring;

    static void install(Class<?> suite) {
        if (suite.getClassLoader() != HologramDecodeProbe.class.getClassLoader()
            || !(suite.getClassLoader() instanceof LaunchClassLoader))
            throw new AssertionError("Decode probe and execution must share the isolated launch loader");
        ((LaunchClassLoader) suite.getClassLoader()).registerTransformer(HologramDecodeProbe.class.getName());
    }

    static void begin() {
        if (hooks != 7) throw new AssertionError("Real decode/decodeData/strict-codec bytecode was not instrumented");
        if (measuring != null) throw new AssertionError("Nested decode measurement");
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
        if (source == null || !(NBT.equals(transformedName) || CODEC.equals(transformedName))) return source;
        ClassReader reader = new ClassReader(source);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {

            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                int selected = -1;
                if (NBT.equals(transformedName)
                    && "(Ljava/lang/String;)Lnet/minecraft/nbt/NBTTagCompound;".equals(descriptor)) {
                    if (method.equals("decode")) selected = 0;
                    if (method.equals("decodeData")) selected = 1;
                } else if (CODEC.equals(transformedName) && method.equals("decode")
                    && "([BIJ)Lnet/minecraft/nbt/NBTTagCompound;".equals(descriptor)) selected = 2;
                if (selected < 0) return delegate;
                final int kind = selected;
                hooks |= 1 << kind;
                return new MethodVisitor(Opcodes.ASM5, delegate) {

                    private void record(int value) {
                        super.visitInsn(Opcodes.ICONST_0 + value);
                        super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            HologramDecodeProbe.class.getName()
                                .replace('.', '/'),
                            "entered",
                            "(I)V",
                            false);
                    }

                    @Override
                    public void visitCode() {
                        super.visitCode();
                        record(kind);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (kind == 2 && opcode == Opcodes.ARETURN) record(3);
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
