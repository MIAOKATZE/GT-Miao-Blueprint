// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab.costs.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.miaokatze.gtmb.testlab.costs.ConstructionCosts;

@Mixin(targets = "gregtech.api.threads.RunnableMachineUpdate", remap = false)
public abstract class MixinGtEndTickCosts {

    @WrapMethod(method = "endTick()V", remap = false)
    private static void gtmbtestcost$gtEndTick(Operation<Void> original) {
        ConstructionCosts.Ticket ticket = ConstructionCosts.begin(ConstructionCosts.GT_END_TICK);
        boolean normal = false;
        try {
            original.call();
            normal = true;
        } finally {
            ConstructionCosts.finish(ticket, normal);
        }
    }
}
