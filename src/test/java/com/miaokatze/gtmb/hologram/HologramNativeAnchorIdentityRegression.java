// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** A cold native confirmation belongs to the observed object, not a replacement at equal coordinates. */
final class HologramNativeAnchorIdentityRegression {

    private HologramNativeAnchorIdentityRegression() {}

    static void run() {
        try {
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            var world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            var otherWorld = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            var original = new TileEntity();
            original.setWorldObj(world);
            original.xCoord = world.occupiedX = 12;
            original.yCoord = 80;
            original.zCoord = -9;
            world.occupant = original;
            var proof = HologramStockNativeServer.AnchorIdentity.capture(world, 12, 80, -9);
            SimpleAssert.that(
                proof != null && proof.current(world, 12, 80, -9),
                "unchanged observed controller retains its cold identity proof");
            var replacement = new TileEntity();
            replacement.setWorldObj(world);
            replacement.xCoord = 12;
            replacement.yCoord = 80;
            replacement.zCoord = -9;
            world.occupant = replacement;
            SimpleAssert.that(
                !proof.current(world, 12, 80, -9),
                "a same-class replacement at identical coordinates cannot inherit the observed proof");
            world.occupant = original;
            world.unloaded = true;
            long reads = world.tileReads;
            SimpleAssert.that(
                !proof.current(world, 12, 80, -9) && world.tileReads == reads,
                "an unloaded anchor is refused without reading or loading its tile");
            world.unloaded = false;
            SimpleAssert.that(
                !proof.current(otherWorld, 12, 80, -9),
                "the same coordinates in another world cannot inherit the proof");
            original.xCoord++;
            SimpleAssert.that(!proof.current(world, 12, 80, -9), "moving the original tile invalidates confirmation");
            original.xCoord--;
            original.setWorldObj(otherWorld);
            SimpleAssert
                .that(!proof.current(world, 12, 80, -9), "moving the tile between worlds invalidates confirmation");
            original.setWorldObj(world);
            original.invalidate();
            SimpleAssert.that(!proof.current(world, 12, 80, -9), "an invalidated original tile is refused");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("raw native anchor identity fixture", failure);
        }
        HologramNativeColdAdmissionRegression.run();
    }
}
