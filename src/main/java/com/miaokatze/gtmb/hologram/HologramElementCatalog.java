package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.miaokatze.gtmb.api.Declarations;

import gregtech.api.util.HatchElementBuilder;

/** Bounded inspection of reviewed StructureLib/GT wrappers; never invokes element check or machine callbacks. */
final class HologramElementCatalog {

    private HologramElementCatalog() {}

    /** A pure contract can be inherited only through an explicitly transparent declaration wrapper. */
    static Declarations.PureElement<?> pure(IStructureElement<?> element) {
        IdentityHashMap<IStructureElement<?>, Boolean> seen = new IdentityHashMap<>();
        for (int depth = 0; element != null && depth <= 12; depth++) {
            if (seen.put(element, Boolean.TRUE) != null) return null;
            if (element instanceof Declarations.PureElement) return (Declarations.PureElement<?>) element;
            try {
                element = HologramDeclarationWrappers.backing(element);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
                return null;
            }
        }
        return null;
    }

    static String hatchRole(IStructureElement<?> element) {
        for (IStructureElement<?> part : parts(element)) {
            Declarations.PureElement<?> declaration = pure(part);
            if (declaration != null) return declaration.role();
            Method method = part.getClass()
                .getEnclosingMethod();
            if (method != null && method.getDeclaringClass() == HatchElementBuilder.class
                && "build".equals(method.getName())) return "hatch";
        }
        return "block";
    }

    static List<IStructureElement<?>> parts(IStructureElement<?> root) {
        List<IStructureElement<?>> result = new ArrayList<>();
        visit(root, 0, new IdentityHashMap<>(), result);
        return result;
    }

    private static void visit(IStructureElement<?> element, int depth, IdentityHashMap<Object, Boolean> seen,
        List<IStructureElement<?>> result) {
        if (element == null || depth > 12 || seen.size() >= 128 || seen.put(element, Boolean.TRUE) != null) return;
        result.add(element);
        try {
            visit(HologramDeclarationWrappers.backing(element), depth + 1, seen, result);
            visit(HologramDeclarationWrappers.alternativesBacking(element), depth + 1, seen, result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        if (element instanceof IStructureElementChain) {
            IStructureElement<?>[] children = ((IStructureElementChain<?>) element).fallbacks();
            if (children != null && children.length <= 64)
                for (IStructureElement<?> child : children) visit(child, depth + 1, seen, result);
        }
    }
}
