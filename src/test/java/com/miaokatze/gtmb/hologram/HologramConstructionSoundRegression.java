// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemSlab;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Public item-method ownership and scope isolation only; the real Mixin/IWorldAccess probe is separate. */
public final class HologramConstructionSoundRegression {

    private HologramConstructionSoundRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramConstructionSoundRegression.class, args)) return;
        run();
        System.out.println(
            "GTMB_CONSTRUCTION_SOUND_SCOPE_PASS public ownership, world/thread/coordinates, nesting, exceptions; runtime hook separate");
    }

    public static void run() {
        SimpleAssert
            .that(HologramNativeConstruction.inheritsVanillaItemUse(ItemBlock.class), "vanilla direct placement");
        SimpleAssert.that(
            HologramNativeConstruction.inheritsVanillaItemUse(gregtech.common.blocks.ItemCasings.class),
            "real .277 ItemCasings inherits the vanilla use method");
        SimpleAssert.that(
            HologramNativeConstruction.inheritsVanillaItemUse(VariantItem.class),
            "a metadata-only item subclass retains the native direct path");
        SimpleAssert.that(
            HologramNativeConstruction.inheritsVanillaItemUse(PlaceOverride.class),
            "a native placeBlockAt override is retained by the direct call");
        SimpleAssert.that(
            !HologramNativeConstruction.inheritsVanillaItemUse(ItemSlab.class),
            "actual slab merging/use semantics are not bypassed");
        SimpleAssert.that(
            !HologramNativeConstruction.inheritsVanillaItemUse(CustomUse.class),
            "an actual custom use override is not bypassed");
        World one = HologramResourceWorld.create(Blocks.air, 0);
        World other = HologramResourceWorld.create(Blocks.air, 0);
        SimpleAssert
            .that(!HologramConstructionSounds.suppressed(one, -2.5, 64.5, 2.5), "ordinary sounds start enabled");
        try (var outer = HologramConstructionSounds.enter(one, -3, 64, 2)) {
            SimpleAssert
                .that(HologramConstructionSounds.suppressed(one, -2.5, 64.5, 2.5), "exact negative target voxel");
            SimpleAssert.that(
                !HologramConstructionSounds.suppressed(one, -1.5, 64.5, 2.5),
                "different coordinates stay audible");
            SimpleAssert
                .that(!HologramConstructionSounds.suppressed(other, -2.5, 64.5, 2.5), "different worlds stay audible");
            SimpleAssert.that(
                !HologramConstructionSounds.suppressed(one, Double.NaN, 64.5, 2.5),
                "invalid position is never matched");
            boolean[] inherited = { true };
            Thread worker = new Thread(
                () -> inherited[0] = HologramConstructionSounds.suppressed(one, -2.5, 64.5, 2.5));
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                throw new AssertionError("scope isolation worker", interrupted);
            }
            SimpleAssert.that(!inherited[0], "another thread never inherits construction silence");
            try (var inner = HologramConstructionSounds.enter(one, 8, 64, 2)) {
                SimpleAssert
                    .that(HologramConstructionSounds.suppressed(one, 8.5, 64.5, 2.5), "nested placement target");
                SimpleAssert.that(
                    !HologramConstructionSounds.suppressed(one, -2.5, 64.5, 2.5),
                    "nested scope does not widen its target");
                throw new ExpectedFailure();
            } catch (ExpectedFailure expected) {
                SimpleAssert.that(
                    HologramConstructionSounds.suppressed(one, -2.5, 64.5, 2.5),
                    "exception restores the outer placement");
            }
        }
        SimpleAssert
            .that(!HologramConstructionSounds.suppressed(one, -2.5, 64.5, 2.5), "ordinary sounds recover after close");
    }

    private static final class ExpectedFailure extends RuntimeException {
    }

    private static class VariantItem extends ItemBlock {

        VariantItem() {
            super(Blocks.stone);
        }
    }

    private static final class PlaceOverride extends VariantItem {

        @Override
        public boolean placeBlockAt(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
            float hitX, float hitY, float hitZ, int metadata) {
            return false;
        }
    }

    private static final class CustomUse extends VariantItem {

        @Override
        public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
            float hitX, float hitY, float hitZ) {
            return false;
        }
    }
}
