package com.miaokatze.gtmb.client.hologram;

import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;
import com.miaokatze.miaokatzecore.api.camera.CameraIntersections;
import com.miaokatze.miaokatzecore.api.camera.CameraRay;
import com.miaokatze.miaokatzecore.api.camera.HeightSlice;
import com.miaokatze.miaokatzecore.api.camera.SliceSurfaceRule;

/** Area marks and moving previews share block-first aiming, with Alt forcing the floating view ray. */
final class HologramWorldTarget {

    private HologramWorldTarget() {}

    interface NativeFaces {

        MovingObjectPosition trace(double from, double to);
    }

    static final class Target {

        final int[] anchor;
        final boolean atPlayer;

        Target(int[] anchor, boolean atPlayer) {
            this.anchor = anchor;
            this.atPlayer = atPlayer;
        }
    }

    /** Filters an actual native collision face; never synthesizes a surface at the slice plane. */
    static boolean visibleSurface(HeightSlice slice, MovingObjectPosition hit, int pixelX, int pixelY) {
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
            || hit.hitVec == null
            || !slice.containsBlockY(hit.blockY)) return false;
        ForgeDirection face = ForgeDirection.getOrientation(hit.sideHit);
        return face != ForgeDirection.UNKNOWN
            && SliceSurfaceRule.keeps(slice, hit.hitVec.yCoord, face.offsetY, pixelX, pixelY);
    }

    /** A feather-discarded entrance can reveal a real exit face of the same native collision shape. */
    static MovingObjectPosition firstVisibleNativeSurface(CameraRay ray, double start, double end, HeightSlice slice,
        int pixelX, int pixelY, NativeFaces faces) {
        double from = start;
        for (int attempt = 0; attempt < 16 && from <= end; attempt++) {
            MovingObjectPosition candidate = faces.trace(from, end);
            if (candidate == null || candidate.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || candidate.hitVec == null) return null;
            double distance = (candidate.hitVec.xCoord - ray.originX()) * ray.directionX()
                + (candidate.hitVec.yCoord - ray.originY()) * ray.directionY()
                + (candidate.hitVec.zCoord - ray.originZ()) * ray.directionZ();
            if (!Double.isFinite(distance) || distance < from - 1.0E-7 || distance > end + 1.0E-7) return null;
            if (visibleSurface(slice, candidate, pixelX, pixelY)) return candidate;
            from = Math.max(from + 1.0E-6, distance + 1.0E-6);
        }
        return null;
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
