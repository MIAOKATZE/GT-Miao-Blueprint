// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

final class HologramPlacementRequestRegression {

    private HologramPlacementRequestRegression() {}

    static void run() {
        HologramProjectedPlacement.Cell first = new HologramProjectedPlacement.Cell(
            -2,
            0,
            5,
            "minecraft:stone",
            0,
            "minecraft:stone",
            0,
            -1);
        HologramProjectedPlacement.Cell second = new HologramProjectedPlacement.Cell(
            0,
            2,
            -2,
            "minecraft:stone",
            0,
            "minecraft:stone",
            0,
            -1);
        HologramProjectedPlacement.Plan plan = new HologramProjectedPlacement.Plan(
            Arrays.asList(first, second),
            Collections.singletonList(new HologramProjectedPlacement.Need("minecraft:stone", 0, 2)));
        try {
            UUID id = UUID.randomUUID();
            NBTTagCompound tag = HologramPlacementRequest.encode(id, new int[] { 100, 64, 200 }, plan);
            HologramPlacementRequest.Header header = HologramPlacementRequest.header(tag);
            HologramPlacementRequest.Decoded decoded = HologramPlacementRequest.decode(header, tag, 256);
            if (!id.toString()
                .equals(header.id()) || decoded.size() != 2
                || decoded.cell(0).x != -2
                || decoded.cell(1).z != -2
                || !tag.getCompoundTag("blueprint")
                    .hasNoTags()
                || tag.getTagList("placementVariants", 10)
                    .tagCount() != 1)
                throw new AssertionError("Projected operation wire differs from the client plan");

            NBTTagCompound underpaid = (NBTTagCompound) tag.copy();
            underpaid.getTagList("placementMaterials", 10)
                .getCompoundTagAt(0)
                .setInteger("count", 1);
            reject(header, underpaid);
            NBTTagCompound invalidVariant = (NBTTagCompound) tag.copy();
            invalidVariant.getIntArray("placementOperations")[3] = 1;
            reject(header, invalidVariant);
            NBTTagCompound aboveWorld = (NBTTagCompound) tag.copy();
            aboveWorld.getIntArray("placementOperations")[1] = 200;
            reject(header, aboveWorld);
        } catch (IOException | InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void reject(HologramPlacementRequest.Header header, NBTTagCompound tag) throws InterruptedException {
        try {
            HologramPlacementRequest.decode(header, tag, 256);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Malformed projected operation was accepted");
    }
}
