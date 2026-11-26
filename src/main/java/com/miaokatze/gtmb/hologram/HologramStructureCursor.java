// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureWalker;

/** Persistent StructureLib V2 array/navigation cursor. A continuation never replays the completed prefix. */
final class HologramStructureCursor {

    @Desugar
    record Offset(int x, int y, int z) {}

    private static boolean coordinate(int value) {
        return value >= -4096 && value <= 4096;
    }

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

    /**
     * An independently authorized single-step cursor consumes the exact object it was given. The backing slot's
     * identity check is only a fence; an exchanged element cannot supply callbacks or affect the cursor.
     */
    boolean consumePinned(World world, ExtendedFacing facing, int x, int y, int z, IStructureWalker walker,
        int expectedIndex, IStructureElement<?> expected) {
        if (expected == null || index != expectedIndex || index >= elements.length || elements[index] != expected)
            return false;
        // Proved construction is not a StructureLib debug visitor. A World callback can enable
        // instrumentation after the input fence; do not dispatch an arbitrary event during this pinned step.
        advanceElement(world, facing, x, y, z, walker, expected, false);
        return true;
    }

    /** Budget includes navigation entries and repeated/overlapping positions, not only newly captured cells. */
    boolean advance(World world, ExtendedFacing facing, int x, int y, int z, IStructureWalker walker, int budget,
        long deadline) {
        int start = index;
        while (index < elements.length && index - start < budget) {
            if (index > start && System.nanoTime() >= deadline) return false;
            advanceElement(world, facing, x, y, z, walker, elements[index], true);
        }
        return index == elements.length;
    }

    /** Declaration-only cursor. It does not read or load any world cell or call the structure walker. */
    Offset declarationOffset(ExtendedFacing facing) {
        IStructureElement<?> element = elements[index++];
        if (element.isNavigating()) {
            abc[0] = Math.addExact(element.resetA() ? base[0] : abc[0], element.getStepA());
            abc[1] = Math.addExact(element.resetB() ? base[1] : abc[1], element.getStepB());
            abc[2] = Math.addExact(element.resetC() ? base[2] : abc[2], element.getStepC());
            return null;
        }
        if (!coordinate(abc[0]) || !coordinate(abc[1]) || !coordinate(abc[2]))
            throw new IllegalArgumentException("Out-of-range StructureLib declaration");
        facing.getWorldOffset(abc, xyz);
        abc[0] = Math.incrementExact(abc[0]);
        return new Offset(xyz[0], xyz[1], xyz[2]);
    }

    @SuppressWarnings("unchecked")
    private void advanceElement(World world, ExtendedFacing facing, int x, int y, int z, IStructureWalker walker,
        IStructureElement element, boolean instrument) {
        if (element.isNavigating()) {
            abc[0] = (element.resetA() ? base[0] : abc[0]) + element.getStepA();
            abc[1] = (element.resetB() ? base[1] : abc[1]) + element.getStepB();
            abc[2] = (element.resetC() ? base[2] : abc[2]) + element.getStepC();
        } else {
            facing.getWorldOffset(abc, xyz);
            int xx = xyz[0] + x, yy = xyz[1] + y, zz = xyz[2] + z;
            boolean keepWalking;
            if (world.blockExists(xx, yy, zz)) {
                if (instrument && StructureLibAPI.isInstrumentEnabled()) StructureElementVisitedEvent
                    .fireEvent(world, xx, yy, zz, abc[0] - base[0], abc[1] - base[1], abc[2] - base[2], element);
                keepWalking = walker.visit(element, world, xx, yy, zz, abc[0], abc[1], abc[2]);
            } else keepWalking = walker.blockNotLoaded(element, world, xx, yy, zz, abc[0], abc[1], abc[2]);
            abc[0]++;
            if (!keepWalking) {
                index = elements.length;
                return;
            }
        }
        index++;
    }
}
