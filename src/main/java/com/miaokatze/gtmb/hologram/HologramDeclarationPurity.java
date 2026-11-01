package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.Declarations;

import gregtech.api.util.GTStructureUtility;

/** Only reviewed pure leaves and transparent declaration wrappers can share raw candidates across positions. */
final class HologramDeclarationPurity {

    private HologramDeclarationPurity() {}

    static boolean contextFree(IStructureElement<?> element) {
        return contextFree(element, 0, new IdentityHashMap<>());
    }

    private static boolean contextFree(IStructureElement<?> element, int depth,
        IdentityHashMap<IStructureElement<?>, Boolean> seen) {
        if (element == null || depth > 12 || seen.size() >= 128 || seen.put(element, Boolean.TRUE) != null)
            return false;
        if (element instanceof Declarations.PureElement) return true;
        // Keep chains contextual even if some children are pure: hatch/predicate branches must never borrow authority.
        if (element instanceof IStructureElementChain) return false;
        Method factory = element.getClass()
            .getEnclosingMethod();
        if (factory != null) {
            String name = factory.getName();
            if (factory.getDeclaringClass() == StructureUtility.class
                && ("ofBlock".equals(name) || "ofBlocksFlat".equals(name)
                    || "ofBlocksTiered".equals(name)
                    || "ofBlockAnyMeta".equals(name)))
                return true;
            if (factory.getDeclaringClass() == GTStructureUtility.class
                && ("ofCoil".equals(name) || "ofFrame".equals(name))) return true;
        }
        if (element == StructureUtility.isAir()) return true;
        try {
            IStructureElement<?> backing = HologramDeclarationWrappers.backing(element);
            return backing != null && contextFree(backing, depth + 1, seen);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
