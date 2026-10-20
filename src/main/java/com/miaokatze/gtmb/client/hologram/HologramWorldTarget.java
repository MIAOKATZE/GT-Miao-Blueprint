package com.miaokatze.gtmb.client.hologram;

import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;
import com.miaokatze.miaokatzecore.api.camera.CameraIntersections;
import com.miaokatze.miaokatzecore.api.camera.CameraRay;

/** Area marks and moving previews share block-first aiming, with Alt forcing the floating view ray. */
final class HologramWorldTarget {

    private HologramWorldTarget() {}

    static final class Target {

        final int[] anchor;
        final boolean atPlayer;

        Target(int[] anchor, boolean atPlayer) {
            this.anchor = anchor;
            this.atPlayer = atPlayer;
        }
    }

    /** The displayed orthographic ray must exist even when distance zero selects the player's feet. */
    static Target orthographic(MovingObjectPosition hit, boolean forceFloating, boolean adjacent, int distance,
        int[] feet, double referenceY, CameraRay ray) {
        if (ray == null) return new Target(null, false);
        if (!forceFloating && hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            ForgeDirection face = adjacent ? ForgeDirection.getOrientation(hit.sideHit) : ForgeDirection.UNKNOWN;
            return new Target(
                new int[] { hit.blockX + face.offsetX, hit.blockY + face.offsetY, hit.blockZ + face.offsetZ },
                false);
        }
        if (distance == 0) return new Target(feet, true);
        // Rebase at a stable player-height plane. Near-plane origin depends on zoom and may be hundreds of blocks away.
        double base = CameraIntersections.plane(ray, 0, 1, 0, referenceY, 0, 8192);
        if (Double.isNaN(base)) return new Target(null, false);
        return new Target(
            HologramWorldFlow.airAnchor(
                ray.originX() + ray.directionX() * base,
                ray.originY() + ray.directionY() * base,
                ray.originZ() + ray.directionZ() * base,
                ray.directionX(),
                ray.directionY(),
                ray.directionZ(),
                distance),
            false);
    }

    static Target resolve(MovingObjectPosition hit, boolean forceFloating, boolean adjacent, int distance, int[] feet,
        Vec3 eyes, Vec3 look) {
        if (!forceFloating && hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            ForgeDirection face = adjacent ? ForgeDirection.getOrientation(hit.sideHit) : ForgeDirection.UNKNOWN;
            return new Target(
                new int[] { hit.blockX + face.offsetX, hit.blockY + face.offsetY, hit.blockZ + face.offsetZ },
                false);
        }
        if (distance == 0) return new Target(feet, true);
        return new Target(
            HologramWorldFlow
                .airAnchor(eyes.xCoord, eyes.yCoord, eyes.zCoord, look.xCoord, look.yCoord, look.zCoord, distance),
            false);
    }
}
