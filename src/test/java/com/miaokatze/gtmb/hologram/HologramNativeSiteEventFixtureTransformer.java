// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Forge's actual event registration transform, restricted to the native StructureLib events under test. */
public final class HologramNativeSiteEventFixtureTransformer implements IClassTransformer {

    private final IClassTransformer nativeTransform = nativeTransformer();

    private static IClassTransformer nativeTransformer() {
        // ASM5 accepted an internal name passed to Type.getType; ASM9 correctly requires getObjectType.
        // Keep Forge's complete event setup, parent listener lists and constructors, fixing only that ABI use.
        String original = "cpw/mods/fml/common/asm/transformers/EventSubscriptionTransformer";
        try (var in = HologramNativeSiteEventFixtureTransformer.class.getResourceAsStream("/" + original + ".class")) {
            ClassNode type = new ClassNode();
            new ClassReader(in).accept(type, 0);
            String fixture = "com/miaokatze/gtmb/hologram/HologramNativeForgeEventTransformer";
            type.name = fixture;
            for (var method : type.methods)
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    if (call.owner.equals(original)) call.owner = fixture;
                    if (call.owner.equals("org/objectweb/asm/Type") && call.name.equals("getType")
                        && call.getPrevious() instanceof FieldInsnNode field
                        && field.name.equals("superName")) call.name = "getObjectType";
                }
            ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            type.accept(out);
            Class<?> nativeType = (Class<?>) java.lang.invoke.MethodHandles.Lookup.class
                .getMethod("defineClass", byte[].class)
                .invoke(java.lang.invoke.MethodHandles.lookup(), (Object) out.toByteArray());
            return (IClassTransformer) nativeType.getConstructor()
                .newInstance();
        } catch (Exception failure) {
            throw new AssertionError("Cannot restore native Forge event initialization", failure);
        }
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        // Forge's ASM5 visitor cannot inspect modern GT nest metadata. The selected events are Java 8 classes.
        return transformedName != null && transformedName.startsWith("com.gtnewhorizon.structurelib.StructureEvent")
            ? nativeTransform.transform(name, transformedName, bytes)
            : bytes;
    }
}
