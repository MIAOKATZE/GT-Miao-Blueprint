package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;

import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.api.camera.CameraRay;
import com.miaokatze.miaokatzecore.api.camera.HeightSlice;

/** Tests the production resolver used by red area cursors, area packets and moving green previews. */
public final class HologramWorldTargetRegression {

    private HologramWorldTargetRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("HologramWorldTargetRegression passed");
    }

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
        orthographic(feet, hit);
        sliceSurfaces();
    }

    private static void sliceSurfaces() {
        HeightSlice slice = HeightSlice.between(60, 80, 1);
        MovingObjectPosition cap = new MovingObjectPosition(
            2,
            79,
            3,
            ForgeDirection.UP.ordinal(),
            Vec3.createVectorHelper(2.5, 80, 3.5));
        for (int pixelX = 0; pixelX < 4; pixelX++) for (int pixelY = 0; pixelY < 4; pixelY++) SimpleAssert.that(
            HologramWorldTarget.visibleSurface(slice, cap, pixelX, pixelY),
            "a real native upper boundary face stays pickable at every coverage sample");
        MovingObjectPosition side = new MovingObjectPosition(
            2,
            79,
            3,
            ForgeDirection.EAST.ordinal(),
            Vec3.createVectorHelper(3, 79.75, 3.5));
        SimpleAssert.that(
            HologramWorldTarget.visibleSurface(slice, side, 0, 0)
                && !HologramWorldTarget.visibleSurface(slice, side, 0, 3),
            "native side-face picks use the same displayed-pixel feather coverage as rendering");
        MovingObjectPosition hidden = new MovingObjectPosition(
            2,
            80,
            3,
            ForgeDirection.DOWN.ordinal(),
            Vec3.createVectorHelper(2.5, 80, 3.5));
        SimpleAssert.that(
            !HologramWorldTarget.visibleSurface(slice, hidden, 0, 0),
            "a hidden block cannot be picked through its boundary face");
        SimpleAssert.that(
            !HologramWorldTarget.visibleSurface(slice, null, 0, 0),
            "crossing a slice plane does not synthesize a collision face");
        CameraRay ray = new CameraRay(0, 80, 0, 1, -1, 0);
        MovingObjectPosition front = new MovingObjectPosition(
            0,
            79,
            0,
            ForgeDirection.WEST.ordinal(),
            Vec3.createVectorHelper(ray.directionX() * .1, 80 + ray.directionY() * .1, 0));
        MovingObjectPosition back = new MovingObjectPosition(
            0,
            79,
            0,
            ForgeDirection.EAST.ordinal(),
            Vec3.createVectorHelper(ray.directionX() * .7, 80 + ray.directionY() * .7, 0));
        int[] calls = { 0 };
        MovingObjectPosition revealed = HologramWorldTarget
            .firstVisibleNativeSurface(ray, 0, 1, slice, 0, 0, (from, to) -> {
                calls[0]++;
                return from < .1 ? front : back;
            });
        SimpleAssert.that(
            revealed == back && calls[0] == 2,
            "a feather-discarded front face reveals the same voxel's real native back face");
    }

    private static void orthographic(int[] feet, MovingObjectPosition hit) {
        CameraRay near = new CameraRay(0, 400, 0, 0, -1, 1);
        CameraRay further = new CameraRay(0, 800, -400, 0, -1, 1);
        HologramWorldTarget.Target a = HologramWorldTarget.orthographic(null, true, false, 6, feet, 65, near);
        HologramWorldTarget.Target b = HologramWorldTarget.orthographic(null, true, false, 6, feet, 65, further);
        target(a.anchor, false, b, "orthographic floating anchor is independent of the clipping-plane origin");
        target(new int[] { 0, 60, 339 }, false, a, "floating distance begins at the player reference plane");
        target(
            feet,
            true,
            HologramWorldTarget.orthographic(hit, true, true, 0, feet, 65, near),
            "orthographic distance zero retains feet semantics");
        target(
            new int[] { 1, 65, 3 },
            false,
            HologramWorldTarget.orthographic(hit, false, true, 6, feet, 65, near),
            "orthographic preview uses its ray's actual hit face");
        SimpleAssert.that(
            HologramWorldTarget.orthographic(hit, false, false, 0, feet, 65, null).anchor == null,
            "missing displayed camera frame refuses even an available vanilla hit or feet target");
        SimpleAssert.that(
            HologramWorldTarget.orthographic(null, true, false, 6, feet, 65, new CameraRay(0, 400, 0, 1, 0, 0)).anchor
                == null,
            "parallel orthographic floating ray refuses a missing reference-plane intersection");
    }

    private static void target(int[] expected, boolean atPlayer, HologramWorldTarget.Target actual, String label) {
        SimpleAssert.that(Arrays.equals(expected, actual.anchor), label);
        SimpleAssert.eq(atPlayer, actual.atPlayer, label + ": packet foot flag matches the chosen target");
    }
}
