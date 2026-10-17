package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.AxisAlignedBB;

import org.lwjgl.opengl.GL11;

/** Read-only bounded overlay of local selection geometry or the last authoritative server mirror. */
final class HologramAreaOverlay {

    static final class RenderWitness {

        final HologramState state;
        private final int[] cursor;

        RenderWitness(HologramState state, int[] cursor) {
            this.state = state;
            this.cursor = cursor == null ? null : cursor.clone();
        }

        int[] cursor() {
            return cursor == null ? null : cursor.clone();
        }
    }

    private static RenderWitness lastRenderWitness;

    private HologramAreaOverlay() {}

    static RenderWitness lastRenderWitness() {
        return lastRenderWitness;
    }

    static void clearRenderWitness() {
        lastRenderWitness = null;
    }

    static void render(HologramState state, float partialTicks) {
        render(state, partialTicks, HologramClient.areaProjectorWorld() ? HologramClient.areaSelectionAnchor() : null);
    }

    static void render(HologramState state, float partialTicks, int[] cursor) {
        renderSelection(state, state.data, partialTicks, cursor);
    }

    static void renderSelection(HologramState state, NBTTagCompound selection, float partialTicks, int[] cursor) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.renderViewEntity;
        int[] points = selection.getIntArray("areaPoints");
        lastRenderWitness = null;
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
            NBTTagList regions = selection.getTagList("areaRegions", 10);
            for (int i = 0; i < regions.tagCount(); i++) {
                NBTTagCompound region = regions.getCompoundTagAt(i);
                boolean current = selection.hasKey("areaCurrentRegion", 3)
                    && i == selection.getInteger("areaCurrentRegion");
                GL11.glLineWidth(current ? 4 : 2);
                if (current) GL11.glColor4f(.35f, .85f, 1, 1);
                else if ("subtract".equals(region.getString("mode"))) GL11.glColor4f(1, .35f, .35f, .8f);
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
                GL11.glLineWidth(2);
                int bx = points[0], by = points[1], bz = points[2];
                if (points.length >= 6) {
                    bx = points[3];
                    by = points[4];
                    bz = points[5];
                } else if (cursor != null) {
                    bx = cursor[0];
                    by = cursor[1];
                    bz = cursor[2];
                }
                GL11.glColor4f(.35f, .85f, 1, .85f);
                draw(points[0], points[1], points[2], bx, by, bz, px, py, pz);
            }
            // Draw last so the live red target remains legible on confirmed regions and the pending cyan box.
            if (cursor != null) {
                GL11.glLineWidth(3);
                GL11.glColor4f(1, .15f, .15f, 1);
                draw(cursor[0], cursor[1], cursor[2], cursor[0], cursor[1], cursor[2], px, py, pz);
            }
            lastRenderWitness = new RenderWitness(state, cursor);
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
