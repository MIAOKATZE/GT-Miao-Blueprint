// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exactly the production MixinHologramHints placement-attempt notification, for the non-Mixin offline launcher. */
public final class HologramNativeSiteFixtureTransformer implements IClassTransformer {

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (!"com.gtnewhorizon.structurelib.StructureLibAPI".equals(transformedName) || bytes == null) return bytes;
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        for (MethodNode method : type.methods) {
            if (!"isBlockTriviallyReplaceable".equals(method.name)
                || !"(Lnet/minecraft/world/World;IIILnet/minecraft/entity/player/EntityPlayer;)Z".equals(method.desc))
                continue;
            InsnList head = new InsnList();
            head.add(new VarInsnNode(Opcodes.ALOAD, 0));
            head.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/miaokatze/gtmb/hologram/HologramNativeTargetPlan",
                    "sitePlacementAttempt",
                    "(Lnet/minecraft/world/World;)V",
                    false));
            method.instructions.insert(head);
            ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            type.accept(output);
            return output.toByteArray();
        }
        throw new AssertionError("Native site fixture could not install the production placement notification");
    }
}
