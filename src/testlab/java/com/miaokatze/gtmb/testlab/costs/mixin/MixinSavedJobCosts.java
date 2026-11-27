// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab.costs.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.miaokatze.gtmb.testlab.costs.ConstructionCosts;

@Mixin(targets = "com.miaokatze.gtmb.hologram.HologramNativeLocalConstruction$Job", remap = false)
public abstract class MixinSavedJobCosts {

    @WrapMethod(method = "step(J)Z", remap = false)
    private boolean gtmbtestcost$savedJob(long deadline, Operation<Boolean> original) {
        ConstructionCosts.Ticket ticket = ConstructionCosts.begin(ConstructionCosts.SAVED_JOB);
        boolean normal = false;
        try {
            boolean result = original.call(deadline);
            normal = true;
            return result;
        } finally {
            ConstructionCosts.finish(ticket, normal);
        }
    }
}
