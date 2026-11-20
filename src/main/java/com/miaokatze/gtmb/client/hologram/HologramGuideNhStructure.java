// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Iterator;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.hfstudio.guidenh.integration.structurelib.StructureLibBuildService;
import com.hfstudio.guidenh.integration.structurelib.StructureLibImportRequest;
import com.hfstudio.guidenh.integration.structurelib.StructureLibImportResult;
import com.hfstudio.guidenh.integration.structurelib.StructureLibImportResult.PlacedBlock;
import com.hfstudio.guidenh.integration.structurelib.StructureLibSceneImportService;
import com.miaokatze.gtmb.hologram.HologramBlueprintLimits;

/** Optional client integration. GuideNH owns the detached template world and fake player. */
final class HologramGuideNhStructure {

    private HologramGuideNhStructure() {}

    static Iterator<NBTTagCompound> standard(HologramControllerTemplate.Source source) {
        // The compatibility facade returns ordinary classes and avoids a compile/runtime dependency
        // on GuideNH's Java record downgrader classes.
        StructureLibImportResult result = new StructureLibSceneImportService()
            .importScene(new StructureLibImportRequest(source.guideId(), null, null, null, null, 1));
        if (result == null) throw new IllegalStateException("GuideNH returned no structure result");
        if (!result.isSuccess()) throw new IllegalStateException(
            result.getErrors()
                .toString());
        List<PlacedBlock> blocks = result.getBlocks();
        if (blocks.size() < 2 || !HologramBlueprintLimits.accepts(blocks.size()))
            throw new IllegalStateException("GuideNH returned " + blocks.size() + " structure blocks");
        // GuideNH normalizes the returned coordinates to its min corner. The tile's scratch location
        // remains absolute, so it identifies the true controller even when another cell shares its block ID.
        PlacedBlock controller = null;
        for (PlacedBlock block : blocks) {
            NBTTagCompound tile = block.getTileTag();
            if (!source.matchesController(block.getBlock(), block.getMeta(), block.getBlockId(), tile)
                || tile.getInteger("x") != StructureLibBuildService.CONTROLLER_X
                || tile.getInteger("y") != StructureLibBuildService.CONTROLLER_Y
                || tile.getInteger("z") != StructureLibBuildService.CONTROLLER_Z) continue;
            if (controller != null) throw new IllegalStateException("GuideNH returned multiple controller origins");
            controller = block;
        }
        if (controller == null) throw new IllegalStateException("GuideNH returned no matching controller origin");
        PlacedBlock anchor = controller;
        Iterator<PlacedBlock> cells = blocks.iterator();
        return new Iterator<NBTTagCompound>() {

            @Override
            public boolean hasNext() {
                return cells.hasNext();
            }

            @Override
            public NBTTagCompound next() {
                PlacedBlock cell = cells.next();
                return HologramControllerTemplate.row(
                    cell.getX() - anchor.getX(),
                    cell.getY() - anchor.getY(),
                    cell.getZ() - anchor.getZ(),
                    cell.getBlock(),
                    cell.getMeta(),
                    cell.getTileTag(),
                    cell == anchor ? source.machineId : -1);
            }
        };
    }
}
