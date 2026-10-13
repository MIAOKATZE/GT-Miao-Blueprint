package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.opengl.GL11;

/** Read-only bounded world overlay; the inclusive coordinates always come from the server mirror. */
final class HologramAreaOverlay {

    private HologramAreaOverlay() {}

    static void render(HologramState state, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.renderViewEntity;
        int[] points = state.data.getIntArray("areaPoints");
        if (camera == null) return;
        double px = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double py = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double pz = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glLineWidth(2);
            NBTTagList regions = state.data.getTagList("areaRegions", 10);
            for (int i = 0; i < regions.tagCount(); i++) {
                NBTTagCompound region = regions.getCompoundTagAt(i);
                if ("subtract".equals(region.getString("mode"))) GL11.glColor4f(1, .35f, .35f, .8f);
                else GL11.glColor4f(.35f, 1, .6f, .8f);
                draw(
                    region.getInteger("minX"),
                    region.getInteger("minY"),
                    region.getInteger("minZ"),
                    region.getInteger("maxX"),
                    region.getInteger("maxY"),
                    region.getInteger("maxZ"),
                    px,
                    py,
                    pz);
            }
            // A completed box is already part of areaRegions (including red subtraction boxes).
            if (points.length >= 3 && (points.length < 6 || regions.tagCount() == 0)) {
                int bx = points[0], by = points[1], bz = points[2];
                if (points.length >= 6) {
                    bx = points[3];
                    by = points[4];
                    bz = points[5];
                } else if (org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LMENU)
                    || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RMENU)) {
                        int[] anchor = HologramClient.airSelectionAnchor();
                        bx = anchor[0];
                        by = anchor[1];
                        bz = anchor[2];
                    } else {
                        MovingObjectPosition hit = mc.objectMouseOver;
                        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                            bx = hit.blockX;
                            by = hit.blockY;
                            bz = hit.blockZ;
                        }
                    }
                GL11.glColor4f(.35f, .85f, 1, .85f);
                draw(points[0], points[1], points[2], bx, by, bz, px, py, pz);
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static void draw(int ax, int ay, int az, int bx, int by, int bz, double px, double py, double pz) {
        AxisAlignedBB box = AxisAlignedBB
            .getBoundingBox(
                Math.min(ax, bx),
                Math.min(ay, by),
                Math.min(az, bz),
                Math.max(ax, bx) + 1D,
                Math.max(ay, by) + 1D,
                Math.max(az, bz) + 1D)
            .expand(.002, .002, .002)
            .getOffsetBoundingBox(-px, -py, -pz);
        RenderGlobal.drawOutlinedBoundingBox(box, -1);
    }
}
