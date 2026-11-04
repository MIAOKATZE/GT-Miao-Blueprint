package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Opt-in installed-mod GL evidence from detached visual payloads only. */
final class HologramFloodLightsPreviewSmoke {

    private HologramFloodLightsPreviewSmoke() {}

    static String verify() {
        if (!Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"))
            throw new IllegalStateException("Native light smoke is opt-in only");
        StringBuilder result = new StringBuilder();
        if (cpw.mods.fml.common.Loader.isModLoaded("FloodLights")) {
            for (String id : new String[] { "growLight", "smallElectricFloodlightMetaBlock" }) {
                Block block = (Block) Block.blockRegistry.getObject("FloodLights:" + id);
                for (int meta = 0; meta < (id.equals("growLight") ? 1 : 2); meta++) {
                    NBTTagCompound payload = new NBTTagCompound();
                    payload.setByte("teDirection", (byte) 4);
                    payload.setInteger("color", 7);
                    payload.setBoolean("wasActive", true);
                    payload.setBoolean("teRotationState", true);
                    HologramState state = state(block, meta, payload);
                    NBTTagCompound before = state.fullData();
                    double[] bounds = bounds(block);
                    HologramBlockRender.begin(state);
                    HologramState.Cell cell = state.cells.get(0);
                    require(
                        HologramBlockRender.ready(cell, false),
                        id + ": " + HologramBlockRender.failure(cell, false));
                    require(HologramBlockRender.vertexCount(cell, false) == 24, id + " actual native faces");
                    require(!HologramBlockRender.missingTexture(cell, false), id + " actual native image");
                    HologramBlockRender.draw(cell, false);
                    require(Arrays.equals(bounds, bounds(block)), id + " global bounds restored");
                    require(before.equals(state.fullData()), id + " full source payload retained");
                    result.append(id)
                        .append(" meta=")
                        .append(meta)
                        .append(" nativeVertices=24 nativeMaterial=present sourceNbtAndBounds=unchanged\n");
                }
            }
        }
        if (cpw.mods.fml.common.Loader.isModLoaded("ExtraUtilities")) {
            Block block = (Block) Block.blockRegistry.getObject("ExtraUtilities:datablock");
            HologramState state = state(block, 0, new NBTTagCompound());
            NBTTagCompound before = state.fullData();
            HologramBlockRender.begin(state);
            HologramState.Cell cell = state.cells.get(0);
            require(
                HologramBlockRender.invisible(cell, false) && HologramBlockRender.ready(cell, false),
                "explicit data-only policy");
            require(HologramBlockRender.vertexCount(cell, false) == 0, "data carrier has no fabricated geometry");
            HologramBlockRender.draw(cell, false);
            require(before.equals(state.fullData()), "data carrier cell and payload preserved");
            result.append("extrautils-datablock declaredInvisible=true nativeVertices=0 fullCell=preserved\n");
        }
        HologramBlockRender.clear();
        return result.toString();
    }

    private static HologramState state(Block block, int meta, NBTTagCompound payload) {
        NBTTagCompound cell = new NBTTagCompound(), data = new NBTTagCompound(), display = new NBTTagCompound();
        cell.setString("id", Block.blockRegistry.getNameForObject(block));
        cell.setString("wantId", cell.getString("id"));
        cell.setInteger("meta", meta);
        cell.setInteger("wantMeta", meta);
        cell.setString("status", "pending");
        display.setTag("nativeTile", payload.copy());
        cell.setTag("targetDisplay", display.copy());
        cell.setTag("actualDisplay", display.copy());
        cell.setTag("targetStack", new ItemStack(block, 1, meta).writeToNBT(new NBTTagCompound()));
        cell.setTag(
            "actualStack",
            cell.getCompoundTag("targetStack")
                .copy());
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        data.setTag("cells", cells);
        return new HologramState(data);
    }

    private static double[] bounds(Block block) {
        return new double[] { block.getBlockBoundsMinX(), block.getBlockBoundsMinY(), block.getBlockBoundsMinZ(),
            block.getBlockBoundsMaxX(), block.getBlockBoundsMaxY(), block.getBlockBoundsMaxZ() };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
