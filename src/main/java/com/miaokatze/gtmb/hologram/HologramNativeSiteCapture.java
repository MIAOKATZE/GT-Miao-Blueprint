// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** One explicit native formation check, scoped to the pieces of the requested controller. */
public final class HologramNativeSiteCapture {

    @Desugar
    record Result(Map<HologramNativeTargetPlan.Offset, IStructureElement<?>> elements, boolean complete,
        String failure) {

        Result {
            elements = Collections.unmodifiableMap(new LinkedHashMap<>(elements));
            failure = failure == null ? "" : failure;
        }
    }

    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

    private HologramNativeSiteCapture() {}

    static Result capture(BaseMetaTileEntity controller) throws IOException {
        if (ACTIVE.get() != null || StructureLibAPI.isInstrumentEnabled())
            throw new IOException("NATIVE_SITE_CAPTURE_INSTRUMENT_BUSY");
        if (controller == null || !(controller.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>machine))
            throw new IOException("NATIVE_SITE_CAPTURE_CONTROLLER_UNAVAILABLE");
        Scope scope = new Scope(controller, machine);
        if (!scope.identityCurrent() || !machine.mMachine)
            throw new IOException("NATIVE_SITE_CAPTURE_CONTROLLER_UNAVAILABLE");
        VisitedListener listener = new VisitedListener();
        boolean registered = false, instrument = false;
        try {
            MinecraftForge.EVENT_BUS.register(listener);
            registered = true;
            ACTIVE.set(scope);
            StructureLibAPI.enableInstrument(scope);
            instrument = true;
            boolean formed = machine.checkStructure(true, controller);
            if (!scope.frames.isEmpty()) scope.fail("NATIVE_SITE_CAPTURE_UNCLOSED_PIECE");
            if (!scope.identityCurrent()) scope.fail("NATIVE_SITE_CAPTURE_CONTROLLER_CHANGED");
            if (!formed || !machine.mMachine) scope.fail("NATIVE_SITE_CAPTURE_UNFORMED");
            // checkPiece returns do not describe checkMachine's branch decisions. Mixed successful and failed
            // probes cannot prove that the successful branch is the final complete declaration.
            if (scope.failedPieces > 0) scope.fail("NATIVE_SITE_CAPTURE_AMBIGUOUS_BRANCH");
            if (scope.elements.isEmpty()) scope.fail("NATIVE_SITE_CAPTURE_NO_MEMBERS");
            return scope.failure.isEmpty() ? new Result(scope.elements, true, "")
                : new Result(Collections.emptyMap(), false, scope.failure);
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("NATIVE_SITE_CAPTURE_CHECK_FAILED", failure);
        } finally {
            // RETURN injections do not run when native callbacks throw. Never let an abandoned piece survive
            // this one explicit request, including a partially entered foreign-controller check.
            scope.frames.clear();
            ACTIVE.remove();
            try {
                if (instrument && StructureLibAPI.isInstrumentEnabled()) StructureLibAPI.disableInstrument();
            } finally {
                if (registered) MinecraftForge.EVENT_BUS.unregister(listener);
            }
        }
    }

    /** Internal Mixin seam; it is not a producer API. Foreign nested pieces suppress their own events. */
    public static void pieceHead(Object machine, String name, int a, int b, int c) {
        Scope scope = ACTIVE.get();
        if (scope == null || scope.owner != Thread.currentThread()) return;
        boolean owned = machine == scope.machine;
        if (owned && !scope.identityCurrent()) scope.fail("NATIVE_SITE_CAPTURE_CONTROLLER_CHANGED");
        if (owned && !scope.frames.isEmpty() && scope.frames.peek().owned)
            scope.fail("NATIVE_SITE_CAPTURE_NESTED_PIECE");
        scope.frames.push(new Piece(machine, name, a, b, c, owned));
    }

    /** Internal Mixin seam for both native checkPiece overloads. */
    public static void pieceReturn(Object machine, String name, int a, int b, int c, boolean success) {
        Scope scope = ACTIVE.get();
        if (scope == null || scope.owner != Thread.currentThread()) return;
        Piece piece = scope.frames.poll();
        if (piece == null || piece.machine != machine
            || !java.util.Objects.equals(piece.name, name)
            || piece.a != a
            || piece.b != b
            || piece.c != c) {
            scope.fail("NATIVE_SITE_CAPTURE_PIECE_MISMATCH");
            return;
        }
        if (!piece.owned) return;
        if (!scope.identityCurrent()) scope.fail("NATIVE_SITE_CAPTURE_CONTROLLER_CHANGED");
        if (!success) {
            scope.failedPieces++;
            return;
        }
        for (var entry : piece.elements.entrySet()) {
            if (scope.elements.size() >= HologramBlueprintLimits.MAX_CELLS
                && !scope.elements.containsKey(entry.getKey())) {
                scope.fail("NATIVE_SITE_CAPTURE_CELL_LIMIT");
                return;
            }
            IStructureElement<?> previous = scope.elements.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && previous != entry.getValue())
                scope.fail("NATIVE_SITE_CAPTURE_OVERLAPPING_DECLARATIONS");
        }
    }

    public static final class VisitedListener {

        @SubscribeEvent
        public void visited(StructureElementVisitedEvent event) {
            Scope scope = ACTIVE.get();
            if (scope == null || scope.owner != Thread.currentThread()
                || event.getInstrumentIdentifier() != scope
                || event.getWorld() != scope.world) return;
            if (scope.frames.isEmpty()) {
                scope.fail("NATIVE_SITE_CAPTURE_UNSCOPED_ELEMENT");
                return;
            }
            Piece piece = scope.frames.peek();
            if (!piece.owned) return;
            try {
                if (!scope.world.blockExists(event.getX(), event.getY(), event.getZ())) {
                    scope.fail("NATIVE_SITE_CAPTURE_UNLOADED");
                    return;
                }
                var offset = new HologramNativeTargetPlan.Offset(
                    event.getX() - scope.x,
                    event.getY() - scope.y,
                    event.getZ() - scope.z);
                if (event.getElement() == null) {
                    scope.fail("NATIVE_SITE_CAPTURE_MISSING_ELEMENT");
                    return;
                }
                if (piece.elements.size() >= HologramBlueprintLimits.MAX_CELLS && !piece.elements.containsKey(offset)) {
                    scope.fail("NATIVE_SITE_CAPTURE_CELL_LIMIT");
                    return;
                }
                IStructureElement<?> previous = piece.elements.putIfAbsent(offset, event.getElement());
                if (previous != null && previous != event.getElement())
                    scope.fail("NATIVE_SITE_CAPTURE_OVERLAPPING_DECLARATIONS");
            } catch (RuntimeException | LinkageError failure) {
                scope.fail("NATIVE_SITE_CAPTURE_EVENT_FAILED");
            }
        }
    }

    private static final class Piece {

        final Object machine;
        final String name;
        final int a, b, c;
        final boolean owned;
        final Map<HologramNativeTargetPlan.Offset, IStructureElement<?>> elements = new LinkedHashMap<>();

        Piece(Object machine, String name, int a, int b, int c, boolean owned) {
            this.machine = machine;
            this.name = name;
            this.a = a;
            this.b = b;
            this.c = c;
            this.owned = owned;
        }
    }

    private static final class Scope {

        final BaseMetaTileEntity controller;
        final MTEEnhancedMultiBlockBase<?> machine;
        final World world;
        final Thread owner = Thread.currentThread();
        final int x, y, z, id;
        final Object facing;
        final Deque<Piece> frames = new ArrayDeque<>();
        final Map<HologramNativeTargetPlan.Offset, IStructureElement<?>> elements = new LinkedHashMap<>();
        int failedPieces;
        String failure = "";

        Scope(BaseMetaTileEntity controller, MTEEnhancedMultiBlockBase<?> machine) {
            this.controller = controller;
            this.machine = machine;
            world = controller.getWorldObj();
            x = controller.xCoord;
            y = controller.yCoord;
            z = controller.zCoord;
            id = controller.getMetaTileID();
            facing = machine.getExtendedFacing();
        }

        boolean identityCurrent() {
            return world != null && !world.isRemote
                && owner == Thread.currentThread()
                && !controller.isInvalid()
                && controller.getWorldObj() == world
                && controller.xCoord == x
                && controller.yCoord == y
                && controller.zCoord == z
                && world.blockExists(x, y, z)
                && world.getTileEntity(x, y, z) == controller
                && controller.getMetaTileID() == id
                && controller.getMetaTileEntity() == machine
                && machine.getBaseMetaTileEntity() == controller
                && machine.getExtendedFacing() == facing;
        }

        void fail(String value) {
            if (failure.isEmpty()) failure = value;
        }
    }
}
