package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;

import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Tests the production resolver used by red area cursors, area packets and moving green previews. */
public final class HologramWorldTargetRegression {

    private HologramWorldTargetRegression() {}

    public static void run() {
        Vec3 eyes = Vec3.createVectorHelper(-.25, 65.62, 3.8), look = Vec3.createVectorHelper(1, 0, 0);
        int[] feet = { -1, 64, 3 };
        MovingObjectPosition hit = new MovingObjectPosition(2, 65, 3, ForgeDirection.WEST.ordinal(), eyes);
        for (int distance : new int[] { 0, 6, 64 }) {
            target(
                new int[] { 2, 65, 3 },
                false,
                HologramWorldTarget.resolve(hit, false, false, distance, feet, eyes, look),
                "unmodified area mark uses the actual block even with zero floating distance");
            target(
                new int[] { 1, 65, 3 },
                false,
                HologramWorldTarget.resolve(hit, false, true, distance, feet, eyes, look),
                "unmodified green preview attaches to the hit face independently of floating distance");
        }
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            MovingObjectPosition surface = new MovingObjectPosition(2, 65, 3, face.ordinal(), eyes);
            target(
                new int[] { 2 + face.offsetX, 65 + face.offsetY, 3 + face.offsetZ },
                false,
                HologramWorldTarget.resolve(surface, false, true, 6, feet, eyes, look),
                "moving preview supports each native hit face");
        }
        MovingObjectPosition miss = new MovingObjectPosition(2, 65, 3, 0, eyes);
        miss.typeOfHit = MovingObjectPosition.MovingObjectType.MISS;
        MovingObjectPosition entity = new MovingObjectPosition(2, 65, 3, 0, eyes);
        entity.typeOfHit = MovingObjectPosition.MovingObjectType.ENTITY;
        for (MovingObjectPosition noBlock : new MovingObjectPosition[] { null, miss, entity }) {
            for (boolean adjacent : new boolean[] { false, true }) {
                target(
                    new int[] { 5, 65, 3 },
                    false,
                    HologramWorldTarget.resolve(noBlock, false, adjacent, 6, feet, eyes, look),
                    "without a block hit both cursors default to the camera's floating ray");
                target(
                    feet,
                    true,
                    HologramWorldTarget.resolve(noBlock, false, adjacent, 0, feet, eyes, look),
                    "zero floating distance uses player feet rather than camera eyes");
            }
        }
        for (boolean adjacent : new boolean[] { false, true }) {
            target(
                new int[] { 5, 65, 3 },
                false,
                HologramWorldTarget.resolve(hit, true, adjacent, 6, feet, eyes, look),
                "Alt forces both cursors past the hit block to the floating ray");
            target(
                feet,
                true,
                HologramWorldTarget.resolve(hit, true, adjacent, 0, feet, eyes, look),
                "Alt with zero distance forces player feet despite a block hit");
        }
        SimpleAssert.eq(
            HologramWorldFlow.Intent.CONFIRM_POSITION,
            HologramWorldFlow.intent(HologramWorldFlow.Stage.MOVING, HologramWorldFlow.Gesture.ALT_RIGHT),
            "the forced floating green preview can be locked while Alt remains held");
        target(
            new int[] { 5, 65, 3 },
            false,
            HologramWorldTarget.resolve(hit, true, true, 6, feet, eyes, look),
            "Alt confirmation retains the same forced floating green target");
    }

    private static void target(int[] expected, boolean atPlayer, HologramWorldTarget.Target actual, String label) {
        SimpleAssert.that(Arrays.equals(expected, actual.anchor), label);
        SimpleAssert.eq(atPlayer, actual.atPlayer, label + ": packet foot flag matches the chosen target");
    }
}
