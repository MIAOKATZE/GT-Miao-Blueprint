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
        HologramState.Preparation preparation=new HologramState.Preparation(model);
        while (!preparation.step(4093,Long.MAX_VALUE)) { /* deliberately cross region and frame boundaries */ }
        HologramState state=preparation.result();
        Method candidate=MiaoVisonLiteRenderer.class.getDeclaredMethod("previewVisible",
            HologramState.Cell.class,int.class,int.class,boolean.class,boolean.class);
        candidate.setAccessible(true);
        int target=0,site=0;
        for (var section:state.regions.regions.values()) for (var cell:section) {
            SimpleAssert.that(cell==state.regions.at(cell.x,cell.y,cell.z),"region lookup lost or aliased a cell");
            if ((boolean)candidate.invoke(null,cell,Integer.MAX_VALUE,0,false,false)) target++;
            if ((boolean)candidate.invoke(null,cell,Integer.MAX_VALUE,1,true,false)) site++;
        }
        return new int[]{target,site,state.regions.regions.size()};
    }
}
