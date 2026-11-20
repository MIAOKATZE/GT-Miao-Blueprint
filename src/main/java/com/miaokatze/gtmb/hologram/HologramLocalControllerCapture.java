// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Read-only standard geometry for the original workbench; this does not create a server session. */
public final class HologramLocalControllerCapture {

    private final Thread owner = Thread.currentThread();
    private final TileEntity tile;
    private final World world;
    private final int dimension, x, y, z, side;
    private final MTEEnhancedMultiBlockBase<?> liveMachine;
    private final int controllerId;
    private final ExtendedFacing facing;
    private final String title;
    private final String session = "local-controller:" + UUID.randomUUID();
    private final NBTTagList rows = new NBTTagList();
    private MTEEnhancedMultiBlockBase<?> detached;
    private ItemStack trigger;
    private HologramCapture capture;
    private int cursor;
    private boolean complete;

    public HologramLocalControllerCapture(TileEntity tile, int dimension, int side) {
        this.tile = tile;
        this.world = tile.getWorldObj();
        this.dimension = dimension;
        this.x = tile.xCoord;
        this.y = tile.yCoord;
        this.z = tile.zCoord;
        this.side = side;
        if (tile instanceof IGregTechTileEntity gt
            && gt.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>machine) {
            liveMachine = machine;
            controllerId = gt.getMetaTileID();
            facing = machine.getExtendedFacing();
            title = machine.getLocalName();
        } else {
            liveMachine = null;
            controllerId = -1;
            facing = null;
            title = tile.getBlockType()
                .getLocalizedName();
        }
    }

    public boolean matches(World current, int dimension) {
        return current == world && current != null
            && current.isRemote
            && this.dimension == dimension
            && !tile.isInvalid()
            && current.blockExists(x, y, z)
            && current.getTileEntity(x, y, z) == tile
            && (liveMachine == null || ((IGregTechTileEntity) tile).getMetaTileEntity() == liveMachine
                && ((IGregTechTileEntity) tile).getMetaTileID() == controllerId
                && liveMachine.getExtendedFacing() == facing);
    }

    /** A header has the existing projection shape but no server session identity or receipts. */
    public NBTTagCompound opening() {
        NBTTagCompound state = header();
        state.setBoolean("capturePending", true);
        return state;
    }

    public NBTTagCompound unavailable(String status) {
        NBTTagCompound state = header();
        state.setBoolean("supported", false);
        state.setBoolean("localStructureUnavailable", true);
        state.setString("status", status);
        return state;
    }

    /** One client tick owns the native declaration walk or the row conversion work. */
    public NBTTagCompound step(int maxRows, long maxNanos) throws IOException {
        if (Thread.currentThread() != owner || maxRows <= 0 || maxNanos <= 0)
            throw new IllegalArgumentException("Invalid local controller capture step");
        if (complete) throw new IllegalStateException("Local controller model already published");
        if (liveMachine == null || facing == null || !HologramStructureDiscovery.captureSupported(liveMachine))
            throw new IOException("LOCAL_DECLARATION_UNAVAILABLE");
        if (!matches(world, dimension)) throw new IOException("LOCAL_CONTROLLER_CHANGED");
        long started = System.nanoTime();
        if (detached == null) {
            detached = HologramDetachedController.create(controllerId, facing);
            trigger = new ItemStack(StructureLibAPI.getDefaultHologramItem(), 1);
        }
        if (capture == null || capture.pending) {
            capture = HologramCapture.collectLocalProjection(
                detached,
                trigger,
                facing,
                capture,
                Math.max(1, maxNanos - (System.nanoTime() - started)));
            if (capture.pending) return null;
            if (capture.incomplete || capture.cells.size() < 2)
                throw new IOException(capture.failure.isEmpty() ? "LOCAL_DECLARATION_UNAVAILABLE" : capture.failure);
        }
        int limit = Math.min(cursor + maxRows, capture.cells.size());
        int first = cursor;
        while (cursor < limit && (cursor == first || System.nanoTime() - started < maxNanos))
            rows.appendTag(row(capture.cells.get(cursor++)));
        if (cursor < capture.cells.size()) return null;
        NBTTagCompound state = header();
        state.setBoolean("projectionDataComplete", true);
        state.setTag("cells", rows);
        state.setTag(
            "capabilities",
            HologramCapabilities.describe(detached, trigger, capture.channelReport)
                .toNBT());
        complete = true;
        capture = null;
        detached = null;
        trigger = null;
        return state;
    }

    private NBTTagCompound header() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", session);
        state.setString("generation", session);
        state.setString("editorWorkspace", "projection");
        state.setString("workspaceTab", "structure");
        state.setString("openPurpose", "controllerStructure");
        state.setBoolean("localStandardController", true);
        state.setBoolean("structureProjection", true);
        state.setBoolean("target", true);
        state.setBoolean("supported", true);
        state.setBoolean("keepController", true);
        state.setBoolean("quotesIncomplete", true);
        state.setInteger("dimension", dimension);
        state.setInteger("x", x);
        state.setInteger("y", y);
        state.setInteger("z", z);
        state.setInteger("side", side);
        state.setInteger("selected", -1);
        state.setInteger("main", 1);
        state.setString("title", title);
        if (facing != null) {
            state.setInteger("facing", facing.ordinal());
            state.setIntArray("facings", new int[] { facing.ordinal() });
        }
        return state;
    }

    private NBTTagCompound row(HologramCapture.Cell cell) {
        IGregTechTileEntity original = detached.getBaseMetaTileEntity();
        int dx = cell.x - original.getXCoord(), dy = cell.y - original.getYCoord(), dz = cell.z - original.getZCoord();
        Block block = cell.block != null || cell.renderHint == null ? cell.block : cell.renderHint.block;
        int metadata = cell.block != null || cell.renderHint == null ? cell.meta : cell.renderHint.metadata;
        Block hint = StructureLibAPI.getBlockHint();
        ItemStack material = cell.anchor ? cell.actualStack
            : block == null || block == hint || block == Blocks.air ? null
                : HologramBlockIdentity.materialStack(block, metadata);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("x", Math.addExact(x, dx));
        nbt.setInteger("y", Math.addExact(y, dy));
        nbt.setInteger("z", Math.addExact(z, dz));
        nbt.setInteger("dx", dx);
        nbt.setInteger("dy", dy);
        nbt.setInteger("dz", dz);
        String registry = block == null ? "" : String.valueOf(Block.blockRegistry.getNameForObject(block));
        nbt.setString("id", cell.anchor ? registry : "minecraft:air");
        nbt.setInteger("meta", cell.anchor ? metadata : 0);
        nbt.setString("wantId", registry);
        nbt.setInteger("wantMeta", metadata);
        nbt.setBoolean("anchor", cell.anchor);
        nbt.setBoolean("inScope", true);
        nbt.setBoolean("missingBlock", cell.unknown || block == hint);
        nbt.setBoolean("iconOnly", cell.iconOnly);
        nbt.setString("status", cell.anchor ? "protected" : cell.unknown ? "unsupported" : "missing");
        nbt.setString("role", cell.role);
        nbt.setString("family", cell.family);
        nbt.setString("renderKind", cell.renderKind);
        nbt.setInteger("targetFacing", cell.anchor ? cell.actualFacing : -1);
        nbt.setInteger("actualFacing", cell.anchor ? cell.actualFacing : -1);
        if (material != null) {
            NBTTagCompound stack = material.writeToNBT(new NBTTagCompound());
            nbt.setTag("targetStack", stack);
            if (cell.anchor) nbt.setTag("actualStack", stack.copy());
        }
        if (cell.anchor && cell.actualDisplay != null) {
            nbt.setTag("actualDisplay", cell.actualDisplay.copy());
            nbt.setTag("targetDisplay", cell.actualDisplay.copy());
        }
        if (cell.hintIcons != null) {
            NBTTagList icons = new NBTTagList();
            for (String icon : cell.hintIcons) icons.appendTag(new NBTTagString(icon == null ? "" : icon));
            nbt.setTag("hintIcons", icons);
        }
        if (cell.hintTint != null) {
            int[] tint = new int[cell.hintTint.length];
            for (int i = 0; i < tint.length; i++) tint[i] = cell.hintTint[i];
            nbt.setIntArray("hintTint", tint);
        }
        nbt.setInteger("hintIndex", cell.hintIndex);
        return nbt;
    }
}
