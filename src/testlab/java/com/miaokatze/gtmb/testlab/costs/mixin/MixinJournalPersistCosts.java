// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.testlab.costs.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.miaokatze.gtmb.testlab.costs.ConstructionCosts;

@Mixin(targets = "com.miaokatze.gtmb.hologram.HologramConstructionJournal", remap = false)
public abstract class MixinJournalPersistCosts {

    @WrapMethod(
        method = "persist(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/nbt/NBTTagCompound;Lnet/minecraft/world/World;Z)V",
        remap = false)
    private static void gtmbtestcost$journalPersist(EntityPlayer player, NBTTagCompound journal, World world,
        boolean rejectLink, Operation<Void> original) {
        ConstructionCosts.Ticket ticket = ConstructionCosts.begin(ConstructionCosts.JOURNAL_PERSIST);
        boolean normal = false;
        try {
            original.call(player, journal, world, rejectLink);
            normal = true;
        } finally {
            ConstructionCosts.finish(ticket, normal);
        }
    }
}
