package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import gregtech.api.casing.Casings;
import gregtech.api.enums.Materials;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.tileentities.machines.multi.xlturbines.MTEXLTurbineBase;

/**
 * Audited GT 5.09.54.265 cold metadata factories only. This is not an interpreter for arbitrary lazy functions.
 * The three Base factories and the Gas factory only construct native declarations from the getters below;
 * their onElementPass consumer is created but never invoked here. The original lazy object is never initialized.
 */
final class HologramGregTechContextDeclarations {

    private static final String PACKAGE = "gregtech.common.tileentities.machines.multi.xlturbines.";
    /** Method headers do not resolve unrelated client-only signature classes on the dedicated server. */
    private static final ClassValue<List<Member>> MEMBERS = new ClassValue<List<Member>>() {

        @Override
        protected List<Member> computeValue(Class<?> type) {
            String owner = type.getName().replace('.', '/');
            try (InputStream source = type.getResourceAsStream("/" + owner + ".class")) {
                if (source == null) return null;
                ClassReader reader = new ClassReader(source);
                if (!reader.getClassName().equals(owner)) return null;
                List<Member> members = new ArrayList<>();
                reader.accept(new ClassVisitor(Opcodes.ASM5) {

                    public void visitNestHost(String owner) {}

                    public void visitNestMember(String member) {}

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                        members.add(new Member(name, descriptor, access));
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return members;
            } catch (IOException | IllegalArgumentException | UnsupportedOperationException unreadableMetadata) {
                return null;
            }
        }
    };

    private static final class Member {

        final String name, descriptor;
        final int access;

        Member(String name, String descriptor, int access) {
            this.name = name;
            this.descriptor = descriptor;
            this.access = access;
        }
    }
    private static final ClassValue<Boolean> CONTEXTS = new ClassValue<Boolean>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return getter(type, "getCasing", Casings.class) && getter(type, "getFrameMaterial", Materials.class)
                    && getter(type, "getRotorCasing", Casings.class)
                    && getter(type, "getAllowedHatches", java.util.List.class)
                    && getter(type, "requiresOutputHatch", boolean.class)
                    && getter(type, "getPollutionPerSecond", int.class, ItemStack.class);
            } catch (NoSuchMethodException invalidContract) {
                return false;
            }
        }
    };

    private HologramGregTechContextDeclarations() {}

    /** Return a capture-owned native declaration, never register a controller value against the shared lazy key. */
    @SuppressWarnings("unchecked")
    static IStructureElement<?> resolve(IStructureElement<?> element, Object context)
        throws ReflectiveOperationException {
        if (!(context instanceof MTEXLTurbineBase) || !CONTEXTS.get(context.getClass())
            || !element.getClass()
                .getName()
                .equals("com.gtnewhorizon.structurelib.structure.LazyStructureElement")
            || element.getClass()
                .getClassLoader() != StructureUtility.class.getClassLoader())
            return null;
        Field initialized = element.getClass()
            .getDeclaredField("elem"),
            deferred = element.getClass()
                .getDeclaredField("to");
        if (initialized.getType() != IStructureElement.class || deferred.getType() != Function.class
            || Modifier.isStatic(initialized.getModifiers())
            || Modifier.isStatic(deferred.getModifiers())) return null;
        initialized.setAccessible(true);
        if (initialized.get(element) != null) return null;
        deferred.setAccessible(true);
        Object function = deferred.get(element);
        if (!(function instanceof Function) || !reviewed(function.getClass())) return null;
        return ((Function<Object, IStructureElement<?>>) function).apply(context);
    }

    private static boolean reviewed(Class<?> type) throws ClassNotFoundException {
        if (!type.isSynthetic() || type.getDeclaredFields().length != 0
            || type.getClassLoader() != MTEXLTurbineBase.class.getClassLoader()) return false;
        String name = type.getName();
        for (String owner : new String[] { PACKAGE + "MTEXLTurbineBase$1", PACKAGE + "MTEXLTurbineGas" }) {
            String prefix = owner + "$$Lambda";
            if (!(name.startsWith(prefix + "$") || name.startsWith(prefix + "/"))) continue;
            Class<?> enclosing = Class.forName(owner, false, MTEXLTurbineBase.class.getClassLoader());
            Set<String> expected = new HashSet<>();
            if (owner.endsWith("$1")) {
                expected.add("lambda$computeValue$0");
                expected.add("lambda$computeValue$1");
                expected.add("lambda$computeValue$2");
            } else expected.add("lambda$getStructureDefinition$0");
            List<Member> members = MEMBERS.get(enclosing);
            if (members == null) return false;
            String descriptor = Type.getMethodDescriptor(Type.getType(IStructureElement.class),
                Type.getType(MTEXLTurbineBase.class));
            for (Member method : members) {
                if (!method.name.startsWith("lambda$") || method.descriptor.endsWith(")V")) continue;
                if ((method.access & Opcodes.ACC_SYNTHETIC) == 0 || !Modifier.isStatic(method.access)
                    || !method.descriptor.equals(descriptor) || !expected.remove(method.name)) return false;
            }
            return expected.isEmpty();
        }
        return false;
    }

    /** Check the actual virtual dispatch target before the known native factory can call any context getter. */
    private static boolean getter(Class<?> type, String name, Class<?> result, Class<?>... parameters)
        throws NoSuchMethodException {
        Member method = null;
        Class<?> declaring = null;
        Type[] arguments = new Type[parameters.length];
        for (int index = 0; index < parameters.length; index++) arguments[index] = Type.getType(parameters[index]);
        String descriptor = Type.getMethodDescriptor(Type.getType(result), arguments);
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            List<Member> members = MEMBERS.get(current);
            if (members == null) throw new NoSuchMethodException(name);
            for (Member member : members) if (member.name.equals(name) && member.descriptor.equals(descriptor)) {
                method = member;
                declaring = current;
                break;
            }
            if (method != null) break;
        }
        if (method == null) throw new NoSuchMethodException(name);
        if (Modifier.isStatic(method.access)
            || declaring.getClassLoader() != MTEXLTurbineBase.class.getClassLoader())
            return false;
        String owner = declaring.getName();
        if (name.equals("getAllowedHatches")) return declaring == MTEXLTurbineBase.class;
        if (name.equals("getPollutionPerSecond"))
            return declaring == MTEMultiBlockBase.class || owner.equals(PACKAGE + "MTEXLTurbineGas");
        if (name.equals("requiresOutputHatch"))
            return declaring == MTEXLTurbineBase.class || owner.equals(PACKAGE + "MTEXLTurbineGas");
        return owner.equals(PACKAGE + "MTEXLTurbineBase") || owner.equals(PACKAGE + "MTEXLTurbineHPSteam")
            || owner.equals(PACKAGE + "MTEXLTurbineSCSteam")
            || owner.equals(PACKAGE + "MTEXLTurbinePlasma")
            || owner.equals(PACKAGE + "MTEXLTurbineGas");
    }
}
