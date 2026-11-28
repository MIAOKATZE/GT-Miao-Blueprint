// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Native permission revocation and private ownership remain effective even for a capable creative actor. */
final class HologramNativeControllerAccessRegression {

    private HologramNativeControllerAccessRegression() {}

    static void run() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) field.get(null);
            UUID actor = UUID.randomUUID();
            var player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            for (Field identity : Entity.class.getDeclaredFields()) if (identity.getType() == UUID.class) {
                identity.setAccessible(true);
                identity.set(player, actor);
            }
            for (Field profile : EntityPlayer.class.getDeclaredFields()) if (profile.getType() == GameProfile.class) {
                profile.setAccessible(true);
                profile.set(player, new GameProfile(actor, "NativeAccessActor"));
            }
            player.capabilities = new PlayerCapabilities();
            player.capabilities.allowEdit = true;
            var tile = (PrivateController) unsafe.allocateInstance(PrivateController.class);
            var machine = (AccessMachine) unsafe.allocateInstance(AccessMachine.class);
            machine.allow = true;
            machine.setBaseMetaTileEntity(tile);
            tile.locked = true;
            tile.setOwnerUuid(UUID.randomUUID());
            tile.setOwnerName("AnotherOwner");
            SimpleAssert.that(
                !HologramNativeProjectionJob.controllerAccessAllowed(player, tile, machine),
                "a non-owner cannot observe or operate a private controller");
            player.capabilities.isCreativeMode = true;
            SimpleAssert.that(
                !HologramNativeProjectionJob.controllerAccessAllowed(player, tile, machine),
                "creative capability cannot substitute for native private ownership");
            tile.setOwnerUuid(actor);
            SimpleAssert.that(
                HologramNativeProjectionJob.controllerAccessAllowed(player, tile, machine),
                "the matching owner UUID is accepted through the native controller access API");
            machine.allow = false;
            SimpleAssert.that(
                !HologramNativeProjectionJob.controllerAccessAllowed(player, tile, machine),
                "native isAccessAllowed denial overrides matching private ownership");
            machine.allow = true;
            tile.setOwnerUuid(UUID.randomUUID());
            tile.setOwnerName("NativeAccessActor");
            SimpleAssert.that(
                HologramNativeProjectionJob.controllerAccessAllowed(player, tile, machine),
                "the existing GT legacy owner-name fallback is preserved");
            tile.setOwnerName("AnotherOwner");
            tile.locked = false;
            SimpleAssert.that(
                HologramNativeProjectionJob.controllerAccessAllowed(player, tile, machine),
                "a native public controller does not impose private ownership");
            machine.allow = false;
            SimpleAssert.that(
                !HologramNativeProjectionJob.controllerAccessAllowed(player, tile, machine),
                "revoking native access also stops a previously accessible public controller");
            SimpleAssert.that(machine.accessReads >= 7, "every attempt consults the actual native access callback");
            pendingObservationOwnership(unsafe);
            baselineIdentity(tile, machine);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("native controller business access fixture", failure);
        }
    }

    /** Exercise the existing inbox's ownership boundaries without constructing or checking a live world. */
    @SuppressWarnings("unchecked")
    private static void pendingObservationOwnership(sun.misc.Unsafe unsafe) throws ReflectiveOperationException {
        var server = new HologramStockNativeServer(
            (endpoint, reply) -> {
                throw new AssertionError("an invalid endpoint must not receive an observation reply");
            });
        var gateField = HologramStockNativeServer.class.getDeclaredField("requests");
        gateField.setAccessible(true);
        var gate = (HologramRequestGate) gateField.get(server);
        var inboxField = HologramStockNativeServer.class.getDeclaredField("inbox");
        inboxField.setAccessible(true);
        var inbox = (java.util.ArrayDeque<Object>) inboxField.get(server);
        var waitingType = Class.forName("com.miaokatze.gtmb.hologram.HologramStockNativeServer$Waiting");
        var create = waitingType.getDeclaredConstructor(
            HologramWorkspace.ActionEndpoint.class,
            HologramStockNativeWire.Request.class,
            HologramRequestGate.Lease.class);
        create.setAccessible(true);
        var ownedObservation = waitingType.getDeclaredField("observation");
        ownedObservation.setAccessible(true);
        var offsets = HologramNativeProjectionJob.Observation.class.getDeclaredField("offsets");
        offsets.setAccessible(true);
        for (boolean reset : new boolean[] { false, true }) {
            var lease = gate.acquire(new Object());
            var waiting = create.newInstance(null, null, lease);
            var pending = (HologramNativeProjectionJob.Observation) unsafe
                .allocateInstance(HologramNativeProjectionJob.Observation.class);
            var coordinates = new java.util.ArrayList<HologramNativeTargetPlan.Offset>();
            coordinates.add(new HologramNativeTargetPlan.Offset(1, 2, 3));
            offsets.set(pending, coordinates);
            ownedObservation.set(waiting, pending);
            inbox.addLast(waiting);
            SimpleAssert.eq(1, gate.size(), "a sliced observation retains its ingress capacity until it terminates");
            if (reset) server.reset();
            else server.tick(Long.MAX_VALUE);
            SimpleAssert.that(
                inbox.isEmpty() && gate.size() == 0 && !lease.active(),
                "disconnect and reset both release pending observation ingress ownership");
            SimpleAssert.that(coordinates.isEmpty(), "unpublished footprint coordinates are released at termination");
            try {
                pending.advance(Long.MAX_VALUE);
                throw new AssertionError("retired observation resumed");
            } catch (java.io.IOException expected) {}
            pending.close();
            lease.close();
            SimpleAssert.eq(0, gate.size(), "repeated terminal cleanup does not release another request's capacity");
        }
        server.tick(Long.MAX_VALUE);
        server.reset();
    }

    private static void baselineIdentity(PrivateController tile, AccessMachine machine)
        throws ReflectiveOperationException {
        var facing = MTEEnhancedMultiBlockBase.class.getDeclaredField("mExtendedFacing");
        facing.setAccessible(true);
        facing.set(machine, com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.DEFAULT);
        var save = HologramNativeProjectionJob.class
            .getDeclaredMethod("baseline", BaseMetaTileEntity.class, java.util.List.class);
        save.setAccessible(true);
        var read = HologramNativeProjectionJob.class.getDeclaredMethod("cachedBaseline", BaseMetaTileEntity.class);
        read.setAccessible(true);
        HologramNativeProjectionJob.clearBaselines();
        try {
            var coordinates = java.util.Arrays
                .asList(new HologramNativeTargetPlan.Offset(1, 0, 0), new HologramNativeTargetPlan.Offset(8, 0, 0));
            var saved = save.invoke(null, tile, coordinates);
            SimpleAssert.that(
                read.invoke(null, tile) == saved,
                "a compact footprint remains available for a later replacement or whole-structure demolition");
            var cells = saved.getClass()
                .getDeclaredField("offsets");
            cells.setAccessible(true);
            SimpleAssert
                .eq(coordinates, cells.get(saved), "both old and newly authorized positions survive in the footprint");
            tile.xCoord++;
            SimpleAssert
                .eq(null, read.invoke(null, tile), "moving the controller invalidates its historical footprint");
        } finally {
            HologramNativeProjectionJob.clearBaselines();
        }
    }

    private static final class PrivateController extends BaseMetaTileEntity {

        boolean locked;

        @Override
        public boolean privateAccess() {
            return locked;
        }
    }

    // Access contracts do not require a real machine shape. Loading EBF here would initialize its static
    // definition before the offline casing registry is prepared and poison later EBF fixtures in this JVM.
    private static final class AccessMachine extends MTEEnhancedMultiBlockBase<AccessMachine> {

        boolean allow;
        int accessReads;

        private AccessMachine() {
            super("gtmb_native_access_fixture");
        }

        @Override
        public boolean isAccessAllowed(EntityPlayer player) {
            accessReads++;
            return allow;
        }

        @Override
        public IStructureDefinition<AccessMachine> getStructureDefinition() {
            throw new AssertionError("access validation must not inspect a structure definition");
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            throw new AssertionError("access validation must not construct a structure");
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            throw new AssertionError("access validation must not create a tooltip");
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            throw new AssertionError("access validation must not create a replacement controller");
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            throw new AssertionError("access validation must not resolve controller textures");
        }
    }
}
