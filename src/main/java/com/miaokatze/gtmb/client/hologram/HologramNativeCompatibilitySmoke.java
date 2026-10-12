package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.hologram.HologramBlockTransformApi;

/** Strict opt-in real-mod fixture. Missing mods or cube substitutions fail the GL smoke. */
final class HologramNativeCompatibilitySmoke {

    private static final String[] TYPES = { "com.carpentersblocks.block.BlockCarpentersSlope",
        "codechicken.multipart.BlockMultipart", "com.creativemd.littletiles.common.blocks.BlockTile",
        "gcewing.architecture.common.block.BlockShape", "crazypants.enderio.conduit.BlockConduitBundle",
        "appeng.block.networking.BlockCableBus" };

    private HologramNativeCompatibilitySmoke() {}

    static NBTTagCompound fixture() {
        return fixture(false);
    }

    /** The second view also exercises a legal clad roof, alongside the first view's curved mesh. */
    static NBTTagCompound fixture(boolean roofCladding) {
        try {
            NBTTagCompound data = new NBTTagCompound();
            data.setString("session", "native-six-mod-fixture");
            data.setInteger("x", 0);
            data.setInteger("y", 64);
            data.setInteger("z", 0);
            data.setBoolean("virtual", true);
            NBTTagList cells = new NBTTagList();
            for (int i = 0; i < TYPES.length; i++) {
                Block block = find(TYPES[i]);
                NBTTagCompound tile = com.miaokatze.gtmb.hologram.HologramNativeCompatibilityFixture
                    .seed(block, i * 2, 64, 0);
                if (i == 3 && roofCladding) {
                    tile.setInteger("Shape", 0); // RoofTile accepts secondary cladding; CylinderQuarter does not.
                    tile.setString("Name2", "minecraft:stone");
                    tile.setInteger("Data2", 0);
                }
                NBTTagCompound display = new NBTTagCompound();
                display.setTag("nativeTile", tile);
                NBTTagCompound cell = new NBTTagCompound();
                cell.setString("id", Block.blockRegistry.getNameForObject(block));
                cell.setString("wantId", Block.blockRegistry.getNameForObject(block));
                cell.setInteger("x", i * 2);
                cell.setInteger("y", 64);
                cell.setInteger("z", 0);
                cell.setInteger("dx", i * 2);
                cell.setString("status", "pending");
                cell.setString("structureId", "native-" + i);
                cell.setTag("actualDisplay", display.copy());
                cell.setTag("targetDisplay", display.copy());
                if (net.minecraft.item.Item.getItemFromBlock(block) != null) {
                    NBTTagCompound item = new NBTTagCompound();
                    new ItemStack(block).writeToNBT(item);
                    cell.setTag("actualStack", item.copy());
                    cell.setTag("targetStack", item.copy());
                }
                cells.appendTag(cell);
            }
            data.setTag("cells", cells);
            // Canonicalize with the installed mods' real read/write callbacks, then verify
            // that capturing a native tile cannot retain a live-world object reference.
            HologramState state = new HologramState(data);
            HologramBlockAccess access = new HologramBlockAccess(state);
            for (int i = 0; i < cells.tagCount(); i++) {
                HologramState.Cell cell = state.cells.get(i);
                TileEntity detached = access.getTileEntity(cell.x, cell.y, cell.z);
                require(
                    detached != null,
                    TYPES[i] + " detached source factory failed: " + access.failure(cell.x, cell.y, cell.z));
                require(detached.getWorldObj() != Minecraft.getMinecraft().theWorld, "fixture owns its render world");
                NBTTagCompound nativeTag = new NBTTagCompound();
                detached.writeToNBT(nativeTag);
                NBTTagCompound old = cell.targetDisplay.getCompoundTag("nativeTile");
                if (old.hasKey("gtmb.aeCableVisual", 10)) nativeTag.setTag(
                    "gtmb.aeCableVisual",
                    old.getTag("gtmb.aeCableVisual")
                        .copy());
                NBTTagCompound display = new NBTTagCompound();
                display.setTag("nativeTile", nativeTag);
                cells.getCompoundTagAt(i)
                    .setTag("actualDisplay", display.copy());
                cells.getCompoundTagAt(i)
                    .setTag("targetDisplay", display.copy());
            }
            return data;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Real native mod fixture could not be constructed", failure);
        }
    }

    static NBTTagCompound rotatedFixture() {
        return rotate(fixture(), false, 1);
    }

    /** Fixture cells use the same explicit tile/meta transform as blueprint placement. */
    static NBTTagCompound rotate(NBTTagCompound source, boolean vertical, int turns) {
        NBTTagCompound result = (NBTTagCompound) source.copy();
        NBTTagList cells = result.getTagList("cells", 10);
        try {
            for (int i = 0; i < cells.tagCount(); i++) {
                NBTTagCompound cell = cells.getCompoundTagAt(i);
                Block block = Block.getBlockFromName(cell.getString("wantId"));
                for (String name : new String[] { "actualDisplay", "targetDisplay" }) {
                    NBTTagCompound display = cell.getCompoundTag(name);
                    HologramBlockTransformApi.RotationResult changed = HologramBlockTransformApi.rotate(
                        block,
                        cell.getInteger(name.equals("actualDisplay") ? "meta" : "wantMeta"),
                        display.getCompoundTag("nativeTile"),
                        vertical,
                        turns);
                    cell.setInteger(name.equals("actualDisplay") ? "meta" : "wantMeta", changed.metadata);
                    display.setTag("nativeTile", changed.tile);
                }
                int dx = cell.getInteger("dx"), dy = cell.getInteger("dy"), dz = cell.getInteger("dz");
                for (int turn = 0; turn < Math.floorMod(turns, 4); turn++) {
                    if (vertical) {
                        int oldY = dy;
                        dy = -dz;
                        dz = oldY;
                    } else {
                        int oldX = dx;
                        dx = -dz;
                        dz = oldX;
                    }
                }
                cell.setInteger("dx", dx);
                cell.setInteger("dy", dy);
                cell.setInteger("dz", dz);
                cell.setInteger("x", result.getInteger("x") + dx);
                cell.setInteger("y", result.getInteger("y") + dy);
                cell.setInteger("z", result.getInteger("z") + dz);
            }
            return result;
        } catch (java.io.IOException failedTransform) {
            throw new IllegalStateException("Native smoke cannot substitute a visual-only rotation", failedTransform);
        }
    }

    static String verify(HologramState state, boolean current) {
        require(state.cells.size() == TYPES.length, "all six installed mods must be represented");
        HologramBlockRender.begin(state);
        HologramBlockAccess access = new HologramBlockAccess(state, current);
        StringBuilder result = new StringBuilder();
        for (HologramState.Cell cell : state.cells) {
            NBTTagCompound source = (NBTTagCompound) (current ? cell.actualDisplay : cell.targetDisplay).copy();
            TileEntity tile = access.getTileEntity(cell.x, cell.y, cell.z);
            require(
                tile != null && tile.getWorldObj() != Minecraft.getMinecraft().theWorld,
                cell.wantId + " tile must be detached");
            require(
                HologramBlockRender.ready(cell, current),
                cell.wantId + ": " + HologramBlockRender.failure(cell, current));
            require(HologramBlockRender.vertexCount(cell, current) > 0, "native renderer must emit real vertices");
            require(
                HologramBlockRender.uniquePositions(cell, current) > 4
                    && HologramBlockRender.uniquePositions(cell, current) != 8,
                cell.wantId + " fixture must not become an ordinary cube");
            require(!HologramBlockRender.missingTexture(cell, current), cell.wantId + " contains missing atlas quads");
            HologramEnderIoPreviewSmoke.verify(state, cell, tile, current);
            NBTTagCompound nativeTile = source.getCompoundTag("nativeTile");
            if (cell.block(current)
                .getClass()
                .getName()
                .equals(TYPES[3]) && nativeTile.getInteger("Shape") == 0) {
                require(
                    HologramBlockRender.usesIcon(cell, current, Blocks.planks.getIcon(1, 0)),
                    "legal roof must emit its primary plank texture");
                require(
                    HologramBlockRender.usesIcon(cell, current, Blocks.stone.getIcon(1, 0)),
                    "legal roof must emit its secondary stone cladding texture");
            }
            require(
                source.equals(current ? cell.actualDisplay : cell.targetDisplay),
                "native renderer must not mutate snapshot NBT");
            result.append(cell.wantId)
                .append(" vertices=")
                .append(HologramBlockRender.vertexCount(cell, current))
                .append(" positions=")
                .append(HologramBlockRender.uniquePositions(cell, current))
                .append('\n');
        }
        return result.toString();
    }

    static List<List<double[]>> geometry(HologramState state, boolean current) {
        HologramBlockRender.begin(state);
        List<List<double[]>> points = new ArrayList<>();
        for (HologramState.Cell cell : state.cells) {
            require(HologramBlockRender.ready(cell, current), cell.wantId + " native geometry unavailable");
            List<double[]> copied = new ArrayList<>();
            for (double[] point : HologramBlockRender.positions(cell, current)) copied.add(point.clone());
            points.add(copied);
        }
        return points;
    }

    static void verifyTurn(List<List<double[]>> before, List<List<double[]>> after, boolean vertical) {
        require(before.size() == after.size(), "rotation preserves fixture membership");
        for (int i = 0; i < before.size(); i++) {
            require(
                !before.get(i)
                    .isEmpty()
                    && !after.get(i)
                        .isEmpty(),
                "native geometry capture must be enabled");
            for (double[] point : before.get(i)) {
                double[] expected = vertical ? new double[] { point[0], 1 - point[2], point[1] }
                    : new double[] { 1 - point[2], point[1], point[0] };
                boolean found = false;
                for (double[] rotated : after.get(i))
                    if (Math.abs(expected[0] - rotated[0]) < .002 && Math.abs(expected[1] - rotated[1]) < .002
                        && Math.abs(expected[2] - rotated[2]) < .002) {
                            found = true;
                            break;
                        }
                require(found, TYPES[i] + " native mesh does not follow the placement quarter turn");
            }
        }
    }

    static void verifyTurn(HologramState before, HologramState after, boolean current, boolean vertical) {
        HologramEnderIoPreviewSmoke.verifyTurn(before, after, current, vertical);
        verify(before, current);
        verify(after, current);
        // Multiple EIO conduit types use a fixed layout selected anew for each arm
        // axis. Verify the full native rigid mesh with a genuine single conduit,
        // while verify() checks every multi-type arm's offset, endpoint and material.
        verifyTurn(
            geometry(HologramEnderIoPreviewSmoke.rigidWitness(before), current),
            geometry(HologramEnderIoPreviewSmoke.rigidWitness(after), current),
            vertical);
    }

    private static Block find(String name) {
        for (Object entry : Block.blockRegistry) if (entry != null && entry.getClass()
            .getName()
            .equals(name)) return (Block) entry;
        throw new IllegalStateException("Strict native smoke requires installed block " + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
