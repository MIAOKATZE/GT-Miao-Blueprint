package com.miaokatze.gtmb.hologram;

import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;

/** Persistent StructureLib V2 array/navigation cursor. A continuation never replays the completed prefix. */
final class HologramStructureCursor {

    private static final ClassValue<Boolean> NATIVE_ITERATION = new ClassValue<Boolean>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type
                    .getMethod(
                        "iterate",
                        String.class,
                        World.class,
                        ExtendedFacing.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        IStructureWalker.class)
                    .getDeclaringClass() == IStructureDefinition.class;
            } catch (NoSuchMethodException changedContract) {
                return false;
            }
        }
    };

    static boolean supports(IStructureDefinition<?> definition) {
        return NATIVE_ITERATION.get(definition.getClass());
    }

    private final IStructureElement[] elements;
    private final int[] base, abc, xyz = new int[3];
    private int index;

    HologramStructureCursor(IStructureElement[] elements, int a, int b, int c) {
        this.elements = elements;
        base = new int[] { -a, -b, -c };
        abc = base.clone();
    }

    int done() {
        return index;
    }

    int total() {
        return elements.length;
    }

    /** Budget includes navigation entries and repeated/overlapping positions, not only newly captured cells. */
    @SuppressWarnings("unchecked")
    boolean advance(World world, ExtendedFacing facing, int x, int y, int z, IStructureWalker walker, int budget,
        long deadline) {
        int start = index;
        while (index < elements.length && index - start < budget) {
            if (index > start && System.nanoTime() >= deadline) return false;
            IStructureElement element = elements[index];
            if (element.isNavigating()) {
                abc[0] = (element.resetA() ? base[0] : abc[0]) + element.getStepA();
                abc[1] = (element.resetB() ? base[1] : abc[1]) + element.getStepB();
                abc[2] = (element.resetC() ? base[2] : abc[2]) + element.getStepC();
            } else {
                facing.getWorldOffset(abc, xyz);
                int xx = xyz[0] + x, yy = xyz[1] + y, zz = xyz[2] + z;
                boolean keepWalking;
                if (world.blockExists(xx, yy, zz)) {
                    if (StructureLibAPI.isInstrumentEnabled()) StructureElementVisitedEvent
                        .fireEvent(world, xx, yy, zz, abc[0] - base[0], abc[1] - base[1], abc[2] - base[2], element);
                    keepWalking = walker.visit(element, world, xx, yy, zz, abc[0], abc[1], abc[2]);
                } else keepWalking = walker.blockNotLoaded(element, world, xx, yy, zz, abc[0], abc[1], abc[2]);
                abc[0]++;
                if (!keepWalking) {
                    index = elements.length;
                    return true;
                }
            }
            index++;
        }
        return index == elements.length;
    }
}
