package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Display data from one fixed native hint shape. Never executes the inspected method or grants material authority. */
final class HologramStructureHintData {

    static final class Hint {

        final Block block;
        final int metadata;
        final String[] icons;
        final short[] tint;

        Hint(Block block, int metadata) {
            this.block = block;
            this.metadata = metadata;
            icons = null;
            tint = null;
        }

        Hint(String[] icons, short[] tint) {
            block = null;
            metadata = 0;
            this.icons = icons.clone();
            this.tint = tint == null ? null : tint.clone();
        }
    }

    private static final class Reader {

        final Field block, meta;
        final int fixedMeta;

        Reader(Field block, Field meta, int fixedMeta) {
            this.block = block;
            this.meta = meta;
            this.fixedMeta = fixedMeta;
        }
    }

    private static final Reader NONE = new Reader(null, null, 0);
    private static final ClassValue<Reader> READERS = new ClassValue<Reader>() {

        @Override
        protected Reader computeValue(Class<?> type) {
            String owner = type.getName()
                .replace('.', '/');
            try (InputStream stream = type.getResourceAsStream("/" + owner + ".class")) {
                if (stream == null) return NONE;
                Reader[] result = { NONE };
                new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM5) {

                    public void visitNestHost(String owner) {}

                    public void visitNestMember(String member) {}

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                        if (!"spawnHint".equals(name) || (access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_STATIC)) != 0
                            || !descriptor.endsWith("Lnet/minecraft/world/World;IIILnet/minecraft/item/ItemStack;)Z"))
                            return null;
                        return new MethodVisitor(Opcodes.ASM5) {

                            final List<String> code = new ArrayList<>();
                            final List<String> fields = new ArrayList<>();
                            boolean invalid;

                            @Override
                            public void visitVarInsn(int opcode, int var) {
                                code.add("v" + opcode + ":" + var);
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode != Opcodes.NOP) code.add("i" + opcode);
                            }

                            @Override
                            public void visitIntInsn(int opcode, int operand) {
                                if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) code.add("n" + operand);
                                else invalid = true;
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                if (value instanceof Integer) code.add("n" + value);
                                else invalid = true;
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String fieldOwner, String name, String descriptor) {
                                if (opcode != Opcodes.GETFIELD || !owner.equals(fieldOwner)) invalid = true;
                                fields.add(name);
                                code.add("f" + descriptor + ":" + (fields.size() - 1));
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String methodOwner, String name, String descriptor,
                                boolean itf) {
                                if (opcode != Opcodes.INVOKESTATIC
                                    || !"com/gtnewhorizon/structurelib/StructureLibAPI".equals(methodOwner)
                                    || !"hintParticle".equals(name)
                                    || !"(Lnet/minecraft/world/World;IIILnet/minecraft/block/Block;I)V"
                                        .equals(descriptor))
                                    invalid = true;
                                code.add("hint");
                            }

                            @Override
                            public void visitJumpInsn(int opcode, Label label) {
                                invalid = true;
                            }

                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                invalid = true;
                            }

                            @Override
                            public void visitIincInsn(int var, int increment) {
                                invalid = true;
                            }

                            @Override
                            public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                                invalid = true;
                            }

                            @Override
                            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                Object... arguments) {
                                invalid = true;
                            }

                            @Override
                            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                                invalid = true;
                            }

                            @Override
                            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                                invalid = true;
                            }

                            @Override
                            public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                                invalid = true;
                            }

                            @Override
                            public void visitEnd() {
                                if (invalid || code.size() < 10 || code.size() > 11) return;
                                if (!code.subList(0, 6)
                                    .equals(
                                        java.util.Arrays.asList(
                                            "v25:2",
                                            "v21:3",
                                            "v21:4",
                                            "v21:5",
                                            "v25:0",
                                            "fLnet/minecraft/block/Block;:0")))
                                    return;
                                int call = code.size() - 3;
                                if (!"hint".equals(code.get(call)) || !"i4".equals(code.get(call + 1))
                                    || !"i172".equals(code.get(call + 2))) return;
                                try {
                                    Field block = field(type, fields.get(0), Block.class);
                                    if (code.size() == 11) {
                                        if (!"v25:0".equals(code.get(6)) || !"fI:1".equals(code.get(7))) return;
                                        result[0] = new Reader(block, field(type, fields.get(1), int.class), 0);
                                    } else {
                                        String value = code.get(6);
                                        int meta;
                                        if (value.startsWith("n")) meta = Integer.parseInt(value.substring(1));
                                        else if (value.startsWith("i")) {
                                            int opcode = Integer.parseInt(value.substring(1));
                                            if (opcode < Opcodes.ICONST_M1 || opcode > Opcodes.ICONST_5) return;
                                            meta = opcode - Opcodes.ICONST_0;
                                        } else return;
                                        result[0] = new Reader(block, null, meta);
                                    }
                                } catch (ReflectiveOperationException invalidField) {
                                    result[0] = NONE;
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return result[0];
            } catch (IOException | IllegalArgumentException | UnsupportedOperationException unreadableClass) {
                return NONE;
            }
        }
    };

    private HologramStructureHintData() {}

    private static Field field(Class<?> owner, String name, Class<?> expected) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected || !Modifier.isFinal(field.getModifiers())
            || Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field;
    }

    static Hint read(Object element) throws ReflectiveOperationException {
        Reader reader = READERS.get(element.getClass());
        if (reader == NONE) return null;
        Block block = (Block) reader.block.get(element);
        return block == null ? null
            : new Hint(block, reader.meta == null ? reader.fixedMeta : reader.meta.getInt(element));
    }
}
