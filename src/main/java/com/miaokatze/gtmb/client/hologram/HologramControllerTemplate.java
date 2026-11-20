// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Collections;
import java.util.Iterator;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.main.GTMiaoBlueprint;

import cpw.mods.fml.common.Loader;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Prepares a display-only model from the local registered controller ID. */
final class HologramControllerTemplate {

    private static String cachedKey;
    private static HologramState cached;

    private final Source source;
    private final NBTTagList rows = new NBTTagList();
    private final Iterator<NBTTagCompound> remaining;
    private HologramState.Preparation preparation;
    private HologramState finished;
    final boolean standard;
    final String unavailableReason;

    static final class Source {

        final Block block;
        final int blockMeta, machineId;
        final String blockId;

        Source(TileEntity tile) {
            this(
                tile.getBlockType(),
                tile.getBlockMetadata(),
                tile instanceof IGregTechTileEntity gt ? gt.getMetaTileID() : -1);
        }

        Source(Block block, int blockMeta, int machineId) {
            this.block = block;
            this.blockMeta = blockMeta;
            this.machineId = machineId;
            blockId = String.valueOf(Block.blockRegistry.getNameForObject(block));
        }

        String guideId() {
            int meta = machineId >= 0 ? machineId : blockMeta;
            return meta == 0 ? blockId : blockId + ":" + meta;
        }

        boolean matchesController(Block placed, int meta, String placedId, NBTTagCompound tile) {
            if (placed != block || tile == null || !blockId.equals(placedId) && !guideId().equals(placedId))
                return false;
            return machineId >= 0 ? tile.hasKey("mID", 99) && tile.getInteger("mID") == machineId : blockMeta == meta;
        }

        String title() {
            Item item = Item.getItemFromBlock(block);
            return item == null ? block.getLocalizedName()
                : new ItemStack(item, 1, machineId >= 0 ? machineId : blockMeta).getDisplayName();
        }
    }

    HologramControllerTemplate(Source source) {
        this(source, Loader.isModLoaded("guidenh"));
    }

    HologramControllerTemplate(Source source, boolean guideAvailable) {
        this.source = source;
        if (guideAvailable && source.guideId()
            .equals(cachedKey) && cached != null) {
            finished = cached;
            remaining = Collections.emptyIterator();
            standard = true;
            unavailableReason = "";
            return;
        }
        Iterator<NBTTagCompound> template = null;
        String reason = guideAvailable ? "" : "GuideNH unavailable";
        if (guideAvailable) try {
            template = HologramGuideNhStructure.standard(source);
        } catch (RuntimeException | LinkageError failure) {
            reason = failure.toString();
            GTMiaoBlueprint.LOG.debug("Could not read local standard structure for " + source.guideId(), failure);
        }
        standard = template != null;
        unavailableReason = reason;
        remaining = standard ? template
            : Collections.singleton(row(0, 0, 0, source.block, source.blockMeta, null, source.machineId))
                .iterator();
    }

    HologramState step(int maxWork, long nanos) {
        if (finished != null) return finished;
        long start = System.nanoTime();
        if (preparation == null) {
            int work = 0;
            while (remaining.hasNext() && work++ < maxWork && System.nanoTime() - start < nanos)
                rows.appendTag(remaining.next());
            if (remaining.hasNext()) return null;
            NBTTagCompound snapshot = new NBTTagCompound();
            snapshot.setString("session", "standard-controller:" + source.guideId());
            snapshot.setBoolean("localControllerPreview", true);
            snapshot.setTag("cells", rows);
            preparation = new HologramState.Preparation(snapshot, true);
        }
        long remainingNanos = nanos - (System.nanoTime() - start);
        if (remainingNanos <= 0 || !preparation.step(maxWork, remainingNanos)) return null;
        finished = preparation.result();
        if (standard) {
            cachedKey = source.guideId();
            cached = finished;
        }
        return finished;
    }

    static NBTTagCompound row(int x, int y, int z, Block block, int meta, NBTTagCompound tile, int controllerId) {
        if (block == null || block == Blocks.air)
            throw new IllegalArgumentException("Missing controller preview block");
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", x);
        cell.setInteger("y", y);
        cell.setInteger("z", z);
        cell.setInteger("dx", x);
        cell.setInteger("dy", y);
        cell.setInteger("dz", z);
        cell.setString("wantId", String.valueOf(Block.blockRegistry.getNameForObject(block)));
        cell.setInteger("wantMeta", meta);
        cell.setInteger("targetFacing", -1);
        Item item = Item.getItemFromBlock(block);
        if (item != null) {
            int machine = controllerId >= 0 ? controllerId
                : tile != null && tile.hasKey("mID", 99) ? tile.getInteger("mID") : -1;
            if (!(item instanceof ItemMachines) || machine >= 0) {
                cell.setString("previewItem", String.valueOf(Item.itemRegistry.getNameForObject(item)));
                cell.setInteger("previewDamage", item instanceof ItemMachines ? machine : block.damageDropped(meta));
            }
        }
        if (tile != null) cell.setTag("previewTile", tile.copy());
        return cell;
    }
}
