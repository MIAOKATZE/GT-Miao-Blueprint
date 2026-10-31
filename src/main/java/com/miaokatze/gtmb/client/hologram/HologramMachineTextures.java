package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.metatileentity.IMetaTileEntityPipe;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaPipeEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.render.TextureFactory;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;

/** Read-only GT texture access across the inventory-model API introduced after beta2. */
final class HologramMachineTextures {

    private static final Method INVENTORY_TEXTURES = findInventoryTextures();
    private static final int[][] NORTH_TO_FRONT = { { 2, 3, 1, 0, 4, 5 }, { 3, 2, 0, 1, 4, 5 }, { 0, 1, 2, 3, 4, 5 },
        { 0, 1, 3, 2, 5, 4 }, { 0, 1, 5, 4, 2, 3 }, { 0, 1, 4, 5, 3, 2 } };
    private static final int[] NORTH_TO_WEST = { 0, 1, 4, 5, 3, 2 };

    private HologramMachineTextures() {}

    private static Method findInventoryTextures() {
        try {
            return IMetaTileEntity.class.getMethod("getInventoryTextures");
        } catch (NoSuchMethodException absentOnBeta1And2) {
            return null;
        }
    }

    static ITexture[][] resolve(IMetaTileEntity machine, int front, int[] textureSides) {
        ITexture[][] inventory = inventory(machine);
        if (front < 0 || front >= 6) return inventory;
        ITexture[][] textures = new ITexture[6][];
        for (int side = 0; side < 6; side++) {
            // World-side callbacks preserve controller overlays and facing without mutating the prototype.
            try {
                textures[side] = machine.getTexture(
                    null,
                    ForgeDirection.getOrientation(side),
                    ForgeDirection.getOrientation(front),
                    -1,
                    false,
                    false);
            } catch (RuntimeException requiresWorldTile) {}
            if (empty(textures[side]) && inventory != null) {
                int source = inventorySide(front, side);
                if (source < inventory.length) {
                    textures[side] = inventory[source];
                    textureSides[side] = source;
                }
            }
        }
        return textures;
    }

    static ITexture[][] resolve(IMetaTileEntity machine, int front, int[] textureSides, NBTTagCompound display) {
        int color = display.hasKey("color") ? Math.max(-1, Math.min(15, display.getInteger("color"))) : -1;
        boolean active = display.getBoolean("active");
        int redstone = display.getInteger("redstoneMask");
        if (machine instanceof MetaPipeEntity || machine instanceof IMetaTileEntityPipe) {
            ITexture[][] textures = new ITexture[6][];
            int connections = textureConnections(display.getInteger("connections"));
            for (int side = 0; side < 6; side++) {
                ForgeDirection direction = ForgeDirection.getOrientation(side);
                boolean connected = connections == 0 || (connections & (1 << side)) != 0;
                boolean powered = (redstone & (1 << side)) != 0;
                // Current GT native pipes extend MetaPipeEntity but do not implement the legacy pipe interface.
                textures[side] = machine instanceof MetaPipeEntity
                    ? ((MetaPipeEntity) machine).getTexture(null, direction, connections, color, connected, powered)
                    : ((IMetaTileEntityPipe) machine)
                        .getTexture(null, direction, connections, color, connected, powered);
            }
            return textures;
        }
        if (machine instanceof MTEHatch && display.hasKey("texturePage") && display.hasKey("textureIndex")) {
            int page = display.getInteger("texturePage"), index = display.getInteger("textureIndex");
            ITexture[][] pages = Textures.BlockIcons.casingTexturePages;
            if ((page > 0 || index > 0) && page >= 0
                && page < pages.length
                && pages[page] != null
                && index >= 0
                && index < pages[page].length
                && pages[page][index] != null) {
                ITexture background = pages[page][index];
                MTEHatch hatch = (MTEHatch) machine;
                ITexture[][] textures = new ITexture[6][];
                for (int side = 0; side < 6; side++) {
                    textures[side] = side == front
                        ? active ? hatch.getTexturesActive(background) : hatch.getTexturesInactive(background)
                        : new ITexture[] { background };
                    if (side == front) textures[side] = capturedCraftingColor(machine, textures[side], color);
                }
                return textures;
            }
        }
        if (display.hasKey("color") || display.hasKey("active") || display.hasKey("redstoneMask")) {
            ITexture[][] textures = resolve(machine, front, textureSides);
            if (front >= 0 && front < 6) for (int side = 0; side < 6; side++) {
                try {
                    ITexture[] world = machine.getTexture(
                        null,
                        ForgeDirection.getOrientation(side),
                        ForgeDirection.getOrientation(front),
                        color,
                        active,
                        (redstone & (1 << side)) != 0);
                    if (!empty(world)) {
                        textures[side] = side == front ? capturedCraftingColor(machine, world, color) : world;
                        textureSides[side] = side;
                    }
                } catch (RuntimeException requiresWorldTile) {}
            }
            return textures;
        }
        return resolve(machine, front, textureSides);
    }

    private static ITexture[] capturedCraftingColor(IMetaTileEntity machine, ITexture[] textures, int color) {
        // This native hatch reads its prototype base color instead of the getTexture color argument.
        // Keep its buffer/bus overlay and replace only the color layer using the captured instance.
        if (machine.getClass() == MTEHatchCraftingInputME.class) {
            return new ITexture[] { textures[0], textures[1],
                TextureFactory.of(Textures.BlockIcons.OVERLAY_ME_CRAFTING_INPUT_COLORS[color + 1]) };
        }
        return textures;
    }

    /** GT's single-ended pipes use the opposite end's texture but retain their original geometry mask. */
    static int textureConnections(int connections) {
        connections &= 63;
        if (Integer.bitCount(connections) != 1) return connections;
        int side = Integer.numberOfTrailingZeros(connections);
        return connections | 1 << (side ^ 1);
    }

    private static ITexture[][] inventory(IMetaTileEntity machine) {
        if (INVENTORY_TEXTURES != null) {
            try {
                ITexture[][] inventory = (ITexture[][]) INVENTORY_TEXTURES.invoke(machine);
                if (inventory != null) return inventory;
            } catch (InvocationTargetException failedDelegate) {
                Throwable cause = failedDelegate.getCause();
                if (cause instanceof Error) throw (Error) cause;
                if (!(cause instanceof RuntimeException))
                    throw new IllegalStateException("Inventory texture callback failed", cause);
                // A world-dependent optional model can still expose its legacy inventory faces.
            } catch (IllegalAccessException inaccessibleDelegate) {
                throw new IllegalStateException("Cannot invoke inventory texture callback", inaccessibleDelegate);
            }
        }
        ITexture[][] inventory = new ITexture[6][];
        IGregTechTileEntity base = machine.getBaseMetaTileEntity();
        // Match GT's inventory model: WEST front, active overlays, existing prototype base only.
        for (int side = 0; side < 6; side++) {
            try {
                inventory[side] = machine
                    .getTexture(base, ForgeDirection.getOrientation(side), ForgeDirection.WEST, -1, true, false);
            } catch (RuntimeException requiresWorldTile) {}
        }
        return inventory;
    }

    static int inventorySide(int front, int side) {
        return front >= 0 && front < 6 && side >= 0 && side < 6 ? NORTH_TO_WEST[NORTH_TO_FRONT[front][side]] : side;
    }

    private static boolean empty(ITexture[] textures) {
        return textures == null || textures.length == 0;
    }
}
