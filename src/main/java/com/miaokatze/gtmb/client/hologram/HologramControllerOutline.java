// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.opengl.GL11;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** A loading-screen thumbnail of an already available, single-piece GregTech declaration. */
final class HologramControllerOutline {

    private static final int MAX_LAYOUT_ENTRIES = 512;
    private static final int MAX_LAYOUT_EXTENT = 64;
    private static final int LINE_COLOR = 0xA9E1ED;
    private static final double ISO_X = Math.sqrt(3) / 2;

    private final int sizeX, sizeY, sizeZ;

    private HologramControllerOutline(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    static HologramControllerOutline create(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity gt)) return null;
        try {
            if (!(gt.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?> machine)) return null;
            // Read the existing cache. A controller getter may build an entire definition on its first call.
            Field field = machine.getClass()
                .getDeclaredField("STRUCTURE_DEFINITION");
            if (!Modifier.isStatic(field.getModifiers()) || !IStructureDefinition.class.isAssignableFrom(field.getType()))
                return null;
            field.setAccessible(true);
            if (!(field.get(null) instanceof StructureDefinition<?> definition)
                || definition.getClass() != StructureDefinition.class) return null;
            int[] size = dimensions(definition);
            if (size == null) return null;
            int[] orientedSize = new int[3];
            machine.getExtendedFacing()
                .getWorldOffset(size, orientedSize);
            return new HologramControllerOutline(
                Math.abs(orientedSize[0]),
                Math.abs(orientedSize[1]),
                Math.abs(orientedSize[2]));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The server still handles controllers whose definition is unavailable locally.
            return null;
        }
    }

    static int[] dimensions(StructureDefinition<?> definition) {
        Map<String, String> shapes = definition.getShapes();
        if (shapes.size() != 1) return null;
        Map.Entry<String, String> piece = shapes.entrySet()
            .iterator()
            .next();
        String shape = piece.getValue();
        if (shape == null || shape.isEmpty() || shape.length() > MAX_LAYOUT_ENTRIES) return null;
        IStructureElement<?>[] elements = definition.getStructureFor(piece.getKey());
        if (elements.length != shape.length()) return null;

        int a = 0, b = 0, c = 0;
        int minA = MAX_LAYOUT_EXTENT, minB = MAX_LAYOUT_EXTENT, minC = MAX_LAYOUT_EXTENT;
        int maxA = -1, maxB = -1, maxC = -1;
        for (int index = 0; index < elements.length; index++) {
            char symbol = shape.charAt(index);
            if (symbol >= '\uD000') {
                IStructureElement<?> step = elements[index];
                // StructureDefinition.Builder reserves this range for StructureUtility's generated steps.
                if (step.getClass().getEnclosingClass() != StructureUtility.class) return null;
                a = (step.resetA() ? 0 : a) + step.getStepA();
                b = (step.resetB() ? 0 : b) + step.getStepB();
                c = (step.resetC() ? 0 : c) + step.getStepC();
                if (a < 0 || b < 0 || c < 0 || a >= MAX_LAYOUT_EXTENT || b >= MAX_LAYOUT_EXTENT
                    || c >= MAX_LAYOUT_EXTENT) return null;
                continue;
            }
            if (symbol != '-') {
                minA = Math.min(minA, a);
                minB = Math.min(minB, b);
                minC = Math.min(minC, c);
                maxA = Math.max(maxA, a);
                maxB = Math.max(maxB, b);
                maxC = Math.max(maxC, c);
            }
            if (++a > MAX_LAYOUT_EXTENT) return null;
        }
        return maxA < 0 ? null : new int[] { maxA - minA + 1, maxB - minB + 1, maxC - minC + 1 };
    }

    void draw(int centerX, int centerY, int width, int height) {
        if (width < 4 || height < 4) return;
        double projectedWidth = ISO_X * (sizeX + sizeZ);
        double projectedHeight = 0.5 * (sizeX + sizeZ) + sizeY;
        double scale = Math.min((width - 3) / projectedWidth, (height - 3) / projectedHeight);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(1);
            Tessellator lines = Tessellator.instance;
            lines.startDrawing(GL11.GL_LINES);
            for (int corner = 0; corner < 8; corner++) {
                for (int axis = 1; axis <= 4; axis <<= 1) {
                    if ((corner & axis) != 0) continue;
                    lines.setColorRGBA_I(LINE_COLOR, corner == 0 ? 72 : 214);
                    vertex(lines, corner, centerX, centerY, scale);
                    vertex(lines, corner | axis, centerX, centerY, scale);
                }
            }
            lines.draw();
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void vertex(Tessellator lines, int corner, int centerX, int centerY, double scale) {
        double x = (corner & 1) == 0 ? -sizeX * .5 : sizeX * .5;
        double y = (corner & 2) == 0 ? -sizeY * .5 : sizeY * .5;
        double z = (corner & 4) == 0 ? -sizeZ * .5 : sizeZ * .5;
        lines.addVertex(centerX + (x - z) * ISO_X * scale, centerY + ((x + z) * .5 - y) * scale, 0);
    }
}
