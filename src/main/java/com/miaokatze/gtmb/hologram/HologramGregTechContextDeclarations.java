package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

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
            for (Method method : enclosing.getDeclaredMethods()) {
                if (!method.getName()
                    .startsWith("lambda$") || method.getReturnType() == void.class) continue;
                if (!method.isSynthetic() || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != IStructureElement.class
                    || method.getParameterTypes().length != 1
                    || method.getParameterTypes()[0] != MTEXLTurbineBase.class
                    || !expected.remove(method.getName())) return false;
            }
            return expected.isEmpty();
        }
        return false;
    }

    /** Check the actual virtual dispatch target before the known native factory can call any context getter. */
    private static boolean getter(Class<?> type, String name, Class<?> result, Class<?>... parameters)
        throws NoSuchMethodException {
        Method method = null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                method = current.getDeclaredMethod(name, parameters);
                break;
            } catch (NoSuchMethodException inherited) {}
        }
        if (method == null) throw new NoSuchMethodException(name);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers())
            || method.getDeclaringClass()
                .getClassLoader() != MTEXLTurbineBase.class.getClassLoader())
            return false;
        String owner = method.getDeclaringClass()
            .getName();
        if (name.equals("getAllowedHatches")) return method.getDeclaringClass() == MTEXLTurbineBase.class;
        if (name.equals("getPollutionPerSecond"))
            return method.getDeclaringClass() == MTEMultiBlockBase.class || owner.equals(PACKAGE + "MTEXLTurbineGas");
        if (name.equals("requiresOutputHatch"))
            return method.getDeclaringClass() == MTEXLTurbineBase.class || owner.equals(PACKAGE + "MTEXLTurbineGas");
        return owner.equals(PACKAGE + "MTEXLTurbineBase") || owner.equals(PACKAGE + "MTEXLTurbineHPSteam")
            || owner.equals(PACKAGE + "MTEXLTurbineSCSteam")
            || owner.equals(PACKAGE + "MTEXLTurbinePlasma")
            || owner.equals(PACKAGE + "MTEXLTurbineGas");
    }
}
