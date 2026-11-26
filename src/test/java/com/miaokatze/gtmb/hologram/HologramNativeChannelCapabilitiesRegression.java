// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.Arrays;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Native UI publication must retain captured, automatic, explicit and saved named channels. */
final class HologramNativeChannelCapabilitiesRegression {

    static void run() {
        Object context = new Object();
        ItemStack trigger = new ItemStack(Items.feather, 9);
        ChannelDataAccessor.setChannelData(trigger, "coil", 2);
        var definition = StructureDefinition.<Object>builder()
            .addShape("main", new String[][] { { "ABC" } })
            .addElement('A', StructureUtility.withChannel("coil", StructureUtility.ofBlock(Blocks.stone, 0)))
            .addElement('B', StructureUtility.withChannel("casing", StructureUtility.ofBlock(Blocks.dirt, 0)))
            .addElement('C', StructureUtility.withChannel("glass", StructureUtility.ofBlock(Blocks.glass, 0)))
            .addChannel("glass", 1, 3)
            .build();
        SimpleAssert.eq(
            3,
            definition.getChannelDefinitions()
                .size(),
            "native elements register three channels");
        SimpleAssert.eq(
            1,
            definition.getExplicitChannelDefinitions()
                .size(),
            "two channels are automatic, not explicit");

        var scope = HologramChannelTrace.begin(context);
        HologramChannelTrace.record(trigger, "coil", 2, 1);
        HologramChannelTrace
            .declareMaterials("coil", Arrays.asList(new ItemStack(Items.feather), new ItemStack(Items.blaze_rod)));
        HologramChannelTrace.record(trigger, "dynamic_length", 9, 2);
        var report = HologramChannelTrace.end(scope);
        var capabilities = HologramCapabilities.describeNative(context, trigger, report, definition);
        NBTTagList rows = capabilities.toNBT();
        SimpleAssert.eq(5, rows.tagCount(), "main, captured dynamic and all native declared channels are published");
        SimpleAssert.eq(2, row(rows, "coil").getInteger("current"), "captured channel retains its effective value");
        SimpleAssert.eq(1, row(rows, "coil").getInteger("operations"), "captured channel provenance survives");
        SimpleAssert.eq(
            3,
            row(rows, "coil").getTagList("options", 10)
                .tagCount(),
            "material choices and inheritance survive");
        SimpleAssert.eq(9, row(rows, "dynamic_length").getInteger("current"), "runtime-only channel is discovered");
        SimpleAssert.that(row(rows, "casing").getBoolean("editable"), "automatic unbounded channel is editable");
        SimpleAssert.eq(
            1,
            row(rows, "casing").getTagList("options", 10)
                .tagCount(),
            "unbounded options are not enumerated");
        SimpleAssert.eq(
            4,
            row(rows, "glass").getTagList("options", 10)
                .tagCount(),
            "explicit bounds retain three suggestions");

        NBTTagList withoutTrace = HologramCapabilities.describeNative(context, trigger, null, definition)
            .toNBT();
        SimpleAssert
            .eq(4, withoutTrace.tagCount(), "automatic channels remain discoverable without runtime observations");
        NBTTagList foreign = HologramCapabilities.describeNative(new Object(), trigger, report, definition)
            .toNBT();
        SimpleAssert.eq(4, foreign.tagCount(), "another controller capture cannot contribute runtime-only channels");

        NBTTagCompound saved = new NBTTagCompound();
        saved.setInteger("casing", 17);
        saved.setInteger("dynamic_length", 25);
        saved.setInteger("future_dimension", Integer.MAX_VALUE);
        rows = capabilities.withSavedChannels(saved)
            .toNBT();
        SimpleAssert.eq(17, row(rows, "casing").getInteger("current"), "saved named value overrides inheritance");
        SimpleAssert.that(row(rows, "casing").getBoolean("explicit"), "saved named value stays explicit");
        SimpleAssert.eq(25, row(rows, "dynamic_length").getInteger("current"), "saved dynamic value is retained");
        SimpleAssert.eq(
            Integer.MAX_VALUE,
            row(rows, "future_dimension").getInteger("current"),
            "unseen saved channel is retained");
        SimpleAssert.that(capabilities.validConfiguration(9, saved), "named signals are not restricted to suggestions");
        SimpleAssert.that(capabilities.encodeOption(saved, "casing", 0), "inheritance removes the named override");
        SimpleAssert.that(!saved.hasKey("casing"), "inheritance does not write an explicit zero");
        SimpleAssert.eq(9, trigger.stackSize, "capability publication does not change the main signal");
        SimpleAssert.eq(
            2,
            ChannelDataAccessor.getChannelData(trigger, "coil"),
            "capability publication does not mutate the native trigger");
    }

    private static NBTTagCompound row(NBTTagList rows, String id) {
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            if (id.equals(row.getString("id"))) return row;
        }
        throw new AssertionError("Missing native channel: " + id);
    }
}
