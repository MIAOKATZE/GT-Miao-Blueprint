package com.miaokatze.gtmb.client.hologram;

import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramWorldFlow;

/** Area marks and moving previews share block-first aiming, with Alt forcing the floating view ray. */
final class HologramWorldTarget {

    private HologramWorldTarget() {}

    static final class Target {

        final int[] anchor;
        final boolean atPlayer;

        private Target(int[] anchor, boolean atPlayer) {
            this.anchor = anchor;
            this.atPlayer = atPlayer;
        }
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
