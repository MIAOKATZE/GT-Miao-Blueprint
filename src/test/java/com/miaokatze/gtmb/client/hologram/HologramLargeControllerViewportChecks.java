// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Method;

import com.miaokatze.gtmb.hologram.HologramControllerTarget;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Uses the stock preparation, sealed region index and renderer candidate predicate. No GL/pixel claim. */
public final class HologramLargeControllerViewportChecks {

    private HologramLargeControllerViewportChecks() {}

    public static int[] verify(HologramControllerTarget.Projection model) throws Exception {
        HologramState.Preparation preparation = new HologramState.Preparation(model);
        while (!preparation.step(4093, Long.MAX_VALUE)) { /* deliberately cross region and frame boundaries */ }
        HologramState state = preparation.result();
        Method candidate = MiaoVisonLiteRenderer.class.getDeclaredMethod(
            "previewVisible",
            HologramState.Cell.class,
            int.class,
            int.class,
            boolean.class,
            boolean.class);
        candidate.setAccessible(true);
        HologramState installed = state.controllerView(1);
        SimpleAssert.that(
            state.controllerView(0) == state && state.controllerView(2) == state,
            "target and difference geometry remain the original plan, not the installed machine");
        if (model.installedSite() != null) {
            SimpleAssert.that(
                installed != state && installed.localMaterialIndex == null,
                "installed geometry has no quote or build index");
            HologramState changedTarget = state.withMetadata(new net.minecraft.nbt.NBTTagCompound());
            SimpleAssert.that(
                changedTarget.controllerView(1) == installed,
                "target publication never replaces the installed presentation");
            for (var cell : installed.cells) {
                int expected = state.regions.at(cell.x, cell.y, cell.z) == null ? -1
                    : state.regions.at(cell.x, cell.y, cell.z).index;
                SimpleAssert.eq(
                    expected,
                    installed.matchingIndex(state, cell.index),
                    "viewport selection can only edit the same target coordinate");
            }
        }
        return new int[] { visible(state, candidate, false), visible(installed, candidate, true),
            state.regions.regions.size() };
    }

    private static int visible(HologramState state, Method candidate, boolean site) throws Exception {
        int count = 0;
        for (var section : state.regions.regions.values()) for (var cell : section) {
            SimpleAssert.that(cell == state.regions.at(cell.x, cell.y, cell.z), "region lookup lost or aliased a cell");
            if ((boolean) candidate.invoke(null, cell, Integer.MAX_VALUE, site ? 1 : 0, site, false)) count++;
        }
        return count;
    }
}
