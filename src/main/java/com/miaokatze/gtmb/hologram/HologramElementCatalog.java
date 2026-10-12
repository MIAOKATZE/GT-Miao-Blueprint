package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.util.HatchElementBuilder;

/** Bounded inspection of reviewed StructureLib/GT wrappers; never invokes element check or machine callbacks. */
final class HologramElementCatalog {

    private HologramElementCatalog() {}

    static String hatchRole(IStructureElement<?> element) {
        for (IStructureElement<?> part : parts(element)) {
            Method method = part.getClass()
                .getEnclosingMethod();
            if (method != null && method.getDeclaringClass() == HatchElementBuilder.class
                && "build".equals(method.getName())) return "hatch";
        }
        return "block";
    }

    static List<ItemStack> predicateCandidates(IStructureElement<?> element, IStructureElement.BlocksToPlace blocks) {
        List<ItemStack> result = new ArrayList<>();
        if (blocks == null || !"hatch".equals(hatchRole(element))) return result;
        // Enumerate registered prototypes, rather than inferring machines from numeric hint metadata or inventory.
        for (IMetaTileEntity prototype : GregTechAPI.METATILEENTITIES) {
            if (!(prototype instanceof MTEHatch)) continue;
            try {
                ItemStack stack = prototype.getStackForm(1);
                if (stack == null || stack.getItem() == null) continue;
                if (blocks.getPredicate()
                    .test(stack)) result.add(stack.copy());
            } catch (RuntimeException | LinkageError ignored) {
                // A rejected/broken predicate provides no authority and no fabricated candidate.
            }
        }
        return result;
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
