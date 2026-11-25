// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Detached source tests need AE's real source registries, not its unrelated complete block/item definition tree.
 * The latter loads absent optional power APIs that FML normally strips. No wireless registry, source handler,
 * extraction, permission check, native transaction or world method is replaced.
 */
public final class HologramSourceRegistryFixtureTransformer implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if ("appeng.util.item.AEStack".equals(transformedName) && bytes != null) return withoutPatternIcon(bytes);
        if (!"appeng.core.Api".equals(transformedName) || bytes == null) return bytes;
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        for (MethodNode method : type.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                    || !"appeng/core/ApiDefinitions".equals(allocation.desc)) continue;
                AbstractInsnNode start = insn.getPrevious(), end = insn;
                while (end != null && !(end instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && "definitions".equals(field.name))) end = end.getNext();
                if (start == null || start.getOpcode() != Opcodes.ALOAD || end == null)
                    throw new AssertionError("Unsupported native ApiDefinitions constructor shape");
                AbstractInsnNode after = end.getNext();
                for (AbstractInsnNode current = start; current != after;) {
                    AbstractInsnNode next = current.getNext();
                    method.instructions.remove(current);
                    current = next;
                }
                ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                type.accept(output);
                return output.toByteArray();
            }
        }
        throw new AssertionError("Native source registry fixture could not isolate optional block definitions");
    }
    // AEStack's unrelated craft-pattern rendering icon reads the detached block/item definitions.
    private static byte[] withoutPatternIcon(byte[] bytes) {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        for (MethodNode method : type.methods) {
            if (!"<clinit>".equals(method.name)) continue;
            AbstractInsnNode api = null;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && "appeng/api/AEApi".equals(call.owner) && "instance".equals(call.name)) api = insn;
                if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !"PATTERN".equals(field.name) || api == null) continue;
                for (AbstractInsnNode cursor = api; cursor != insn;) {
                    AbstractInsnNode next = cursor.getNext();
                    method.instructions.remove(cursor);
                    cursor = next;
                }
                method.instructions.insertBefore(insn, new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
                ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                type.accept(output);
                return output.toByteArray();
            }
        }
        throw new AssertionError("Native AE stack fixture could not isolate the optional pattern icon");
    }
}
