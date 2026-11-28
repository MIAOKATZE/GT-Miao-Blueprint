// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.threads.RunnableMachineUpdate;
import gregtech.common.blocks.ItemMachines;

/** Counts the expensive native operations that previously scaled with unrelated registered items. */
final class HologramPreparationPerformanceRegression {

    private static final int HATCH_ID = 32654;
    private static Block machines;
    private static VariantTrap trap;

    private HologramPreparationPerformanceRegression() {}

    static void run() {
        try {
            HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                try {
                    items();
                    actorScope();
                } catch (IOException failure) {
                    throw new AssertionError("native preparation operations", failure);
                }
            });
        } catch (Exception failure) {
            throw new AssertionError("native preparation registry fixture", failure);
        }
    }

    private static void registerItems() {
        if (machines != null) return;
        int slot = 4000;
        while (slot > 3800 && (Block.blockRegistry.containsId(slot) || Item.itemRegistry.containsId(slot))) slot--;
        if (slot <= 3800) throw new AssertionError("No native preparation machine fixture slot");
        machines = new Block(Material.iron) {};
        Block.blockRegistry.addObject(slot, "gtmb:preparation_machine_fixture", machines);
        Item.itemRegistry.addObject(slot, "gtmb:preparation_machine_fixture", new ItemMachines(machines));
        do {
            slot--;
        } while (slot > 3800 && (Block.blockRegistry.containsId(slot) || Item.itemRegistry.containsId(slot)));
        if (slot <= 3800) throw new AssertionError("No native preparation variant fixture slot");
        Block block = new Block(Material.rock) {};
        trap = new VariantTrap(block);
        Block.blockRegistry.addObject(slot, "gtmb:preparation_variant_fixture", block);
        Item.itemRegistry.addObject(slot, "gtmb:preparation_variant_fixture", trap);
    }

    private static void items() {
        registerItems();
        Block oldBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity oldHatch = GregTechAPI.METATILEENTITIES[HATCH_ID];
        try {
            GregTechAPI.sBlockMachines = machines;
            GregTechAPI.METATILEENTITIES[HATCH_ID] = new MTEHatchOutput(
                "preparation_port",
                1,
                new String[0],
                new ITexture[0][][]);
            var base = new gregtech.api.metatileentity.BaseMetaTileEntity();
            GregTechAPI.METATILEENTITIES[HATCH_ID].setBaseMetaTileEntity(base);
            base.setMetaTileID((short) HATCH_ID);
            ItemStack hatch = new ItemStack(machines, 1, HATCH_ID);
            trap.calls = 0;
            HologramNativePreviewItems plain = new HologramNativePreviewItems();
            SimpleAssert.that(
                plain.hatches()
                    .stream()
                    .anyMatch(stack -> stack.getItemDamage() == HATCH_ID),
                "native hatch registry is available without the creative item catalog");
            SimpleAssert.that(plain.takeOne(hatch, true), "explicit hatch stacks need no item enumeration");
            SimpleAssert.that(
                !plain.take(stack -> stack.isItemEqual(hatch), true, 1)
                    .isEmpty(),
                "native hatch predicates are resolved before unrelated creative variants");
            plain.catalog();
            SimpleAssert.eq(0, trap.calls, "hatch selection and catalog preparation never call unrelated getSubItems");

            int[] installedCalls = { 0 };
            NBTTagCompound config = new NBTTagCompound();
            HologramNativePreviewItems defaults = new HologramNativePreviewItems(config, Collections.emptySet(), () -> {
                installedCalls[0]++;
                return Collections.emptyMap();
            });
            for (int n = 0; n < 8; n++) {
                SimpleAssert.that(defaults.takeOne(hatch, true), "default native hatch is allowed");
                SimpleAssert.that(defaults.takeOne(hatch, false), "default native hatch commit is allowed");
            }
            SimpleAssert
                .eq(0, installedCalls[0], "unconfigured hatch selection never scans installed structure counts");
            config.setBoolean("presetActive", true);
            config.setInteger("hatchTier", 1);
            HologramNativePreviewItems tiered = new HologramNativePreviewItems(config, Collections.emptySet(), () -> {
                installedCalls[0]++;
                return Collections.emptyMap();
            });
            SimpleAssert.that(tiered.takeOne(hatch, true), "tier-only selection uses the native hatch tier");
            SimpleAssert.eq(0, installedCalls[0], "tier-only presets have no installed-count work");

            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("item", String.valueOf(Item.itemRegistry.getNameForObject(hatch.getItem())));
            entry.setInteger("damage", HATCH_ID);
            entry.setInteger("count", 2);
            NBTTagList entries = new NBTTagList();
            entries.appendTag(entry);
            NBTTagCompound profile = new NBTTagCompound();
            profile.setTag("entries", entries);
            config.setTag("hatchProfile", profile);
            Map<Integer, Integer> installed = new HashMap<>();
            HologramNativePreviewItems counted = new HologramNativePreviewItems(config, Collections.emptySet(), () -> {
                installedCalls[0]++;
                return installed;
            });
            for (int n = 0; n < 2; n++) {
                SimpleAssert.that(counted.takeOne(hatch, true), "quantity preset admits remaining capacity");
                installed.put(HATCH_ID, n + 1);
                SimpleAssert
                    .that(counted.takeOne(hatch, false), "approved placement commits after the world count changes");
            }
            SimpleAssert.that(!counted.takeOne(hatch, true), "quantity preset rejects a third hatch");
            SimpleAssert.eq(3, installedCalls[0], "only quantity admission queries installed counts, never its commit");
            counted.position("0,0,0", new ItemStack(Blocks.stone), true);
            for (int n = 0; n < 100; n++) SimpleAssert.that(
                counted.take(stack -> stack.isItemEqual(hatch), true, 1)
                    .isEmpty(),
                "an exhausted native hatch quota falls through without a full item-catalog search");
            var fallback = counted.take(stack -> stack.getItem() == Item.getItemFromBlock(Blocks.stone), true, 1);
            SimpleAssert.that(!fallback.isEmpty(), "the same native coordinate retains its exact casing fallback");
            SimpleAssert.eq(0, trap.calls, "repeated quota misses never enumerate unrelated creative variants");
            installed.put(HATCH_ID, 1);
            SimpleAssert.that(counted.takeOne(hatch, true), "restoring a removed hatch restores quantity capacity");
            SimpleAssert.eq(0, trap.calls, "configured sources still avoid unrelated creative variant calls");
        } finally {
            GregTechAPI.METATILEENTITIES[HATCH_ID] = oldHatch;
            GregTechAPI.sBlockMachines = oldBlock;
        }
    }

    private static void actorScope() throws IOException {
        HologramResourceWorld base = HologramResourceWorld.declarations(Blocks.iron_block, 0);
        TileEntity tile = new TileEntity();
        tile.yCoord = 128;
        tile.setWorldObj(base);
        base.tile = tile;
        NativePasses fixture = new NativePasses(tile);
        boolean instrument = StructureLibAPI.isInstrumentEnabled();
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        try (HologramProjectorSimulation simulation = new HologramProjectorSimulation(
            tile,
            fixture,
            fixture,
            new ItemStack(Items.stick),
            null,
            ExtendedFacing.DEFAULT,
            false)) {
            EntityPlayer actor = simulation.actor();
            for (int n = 0; n < 32; n++)
                SimpleAssert.that(simulation.actor() == actor, "one native simulation owns one actor");
            SimpleAssert.eq(simulation.world, actor.worldObj, "actor belongs to the owned simulation world");
            SimpleAssert.that(simulation.step(Long.MAX_VALUE), "native callback passes finish normally");
            SimpleAssert.eq(5, fixture.calls, "several native passes exercise the same survival environment");
            SimpleAssert.that(fixture.actor == actor, "native environment reuses the actor used by probe queries");
            SimpleAssert
                .eq(base, tile.getWorldObj(), "returning from preparation restores the controller's base world");
            SimpleAssert
                .that(!HologramProjectorSimulation.consumesHint(simulation.world), "native active scope is released");
        }
        SimpleAssert.eq(instrument, StructureLibAPI.isInstrumentEnabled(), "preparation restores instrument state");
        SimpleAssert
            .eq(updates, RunnableMachineUpdate.isCurrentThreadEnabled(), "preparation restores machine-update state");
        SimpleAssert.eq(base, tile.getWorldObj(), "closing preparation preserves the original world");
    }

    private static final class VariantTrap extends ItemBlock {

        int calls;

        VariantTrap(Block block) {
            super(block);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void getSubItems(Item item, CreativeTabs tab, List variants) {
            calls++;
            throw new IllegalStateException("Unrelated creative variants must not be enumerated during preparation");
        }
    }

    private static final class NativePasses implements ISurvivalConstructable {

        private final TileEntity tile;
        private EntityPlayer actor;
        private int calls;

        NativePasses(TileEntity tile) {
            this.tile = tile;
        }

        @Override
        public int survivalConstruct(ItemStack trigger, int budget, ISurvivalBuildEnvironment environment) {
            SimpleAssert
                .that(budget > 0 && budget < Integer.MAX_VALUE, "native calls receive a finite placement budget");
            if (actor == null) actor = environment.getActor();
            SimpleAssert.that(actor == environment.getActor(), "survival passes share their native actor");
            calls++;
            if (calls == 5) return -1;
            tile.getWorldObj()
                .setBlock(calls, 128, 0, Blocks.stone, 0, 2);
            return 1;
        }

        @Override
        public void construct(ItemStack trigger, boolean hintsOnly) {
            throw new AssertionError("Native preparation must retain survival construction");
        }

        @Override
        public String[] getStructureDescription(ItemStack trigger) {
            return new String[0];
        }
    }
}
