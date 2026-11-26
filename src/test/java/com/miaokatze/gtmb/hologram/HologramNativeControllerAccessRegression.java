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
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("native controller business access fixture", failure);
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
