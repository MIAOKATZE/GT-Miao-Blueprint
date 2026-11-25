// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.util.StringTranslate;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.CommonMetaTileEntity;
import gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME;
import gregtech.common.blocks.ItemMachines;

/** The formal local material dialog's row path with a real registry, GT ItemMachines and GT subtype localization. */
public final class HologramMaterialNamesRegression {
    private static final String HOST = "gtmb_fixture:material_display_machines";

    private HologramMaterialNamesRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramMaterialNamesRegression.class, args)) return;
        run();
        System.out.println("PASS material names: GT item registry + two real metatiles, missing mod/language, row cache");
    }

    static void run() throws Exception {
        final int first = 6001, second = 6002, untranslated = 6003;
        IMetaTileEntity oldFirst = GregTechAPI.METATILEENTITIES[first];
        IMetaTileEntity oldSecond = GregTechAPI.METATILEENTITIES[second];
        IMetaTileEntity oldThird = GregTechAPI.METATILEENTITIES[untranslated];
        try {
            Block host = new Block(Material.iron) {};
            ItemMachines item = new ItemMachines(host);
            Method register = FMLControlledNamespacedRegistry.class.getDeclaredMethod(
                "add", int.class, String.class, Object.class, BitSet.class);
            register.setAccessible(true);
            // Never take a registry ID reserved by another fixture in the same offline suite.
            int id = 3723;
            while (id > 0 && (Item.getItemById(id) != null || Block.getBlockById(id) != Blocks.air)) id--;
            if (id == 0) throw new AssertionError("No free registry ID for the GT material fixture");
            register.invoke(Block.blockRegistry, id, HOST, host, new BitSet());
            register.invoke(Item.itemRegistry, id, HOST, item, new BitSet());
            // Genuine MTE#getLocalName and GT ItemMachines#getItemStackDisplayName. No mod lifecycle/world starts.
            GregTechAPI.METATILEENTITIES[first] = prototype("fixture_gtmb_material_name_1");
            GregTechAPI.METATILEENTITIES[second] = prototype("fixture_gtmb_material_name_2");
            GregTechAPI.METATILEENTITIES[untranslated] = prototype("fixture_gtmb_material_missing");
            StringTranslate.inject(new ByteArrayInputStream(
                ("gt.blockmachines.fixture_gtmb_material_name_1.name=First hatch\n"
                    + "gt.blockmachines.fixture_gtmb_material_name_2.name=Second hatch\n")
                    .getBytes(StandardCharsets.UTF_8)));
            HologramMaterialConfirmationDialog dialog = new HologramMaterialConfirmationDialog(
                new ModularPanel("material_name_fixture"), new HologramMaterialConfirmationDialog.LocalView() {
                    @Override public HologramMaterialConfirmationDialog.LocalFrame localFrame() { return null; }
                    @Override public boolean localSubmitAvailable() { return false; }
                    @Override public void localAction(HologramMaterialConfirmationDialog.LocalAction action) {}
                });
            var a = dialog.descriptionMaterial(row(HOST, first));
            var b = dialog.descriptionMaterial(row(HOST, second));
            SimpleAssert.eq("First hatch", a.name, "real localized machine subtype label");
            SimpleAssert.eq("Second hatch", b.name, "another damage on the same machine item must not alias");
            SimpleAssert.that(a.icon.getItem() == item && b.icon.getItem() == item
                && a.icon.getItemDamage() == first && b.icon.getItemDamage() == second, "exact subtype icons");
            SimpleAssert.that(a == dialog.descriptionMaterial(row(HOST, first)), "visible identity reused on next draw");
            SimpleAssert.eq(HOST + ":" + first, a.identity, "debug registry identity remains separate from label");
            var noLang = dialog.descriptionMaterial(row(HOST, untranslated));
            SimpleAssert.that("材料名称不可用".equals(noLang.name) && noLang.icon.getItemDamage() == untranslated
                && noLang.identity.endsWith(":" + untranslated), "untranslated key not shown as a material name");
            var missing = dialog.descriptionMaterial(row("absentmod:missing_part", 62));
            SimpleAssert.that(missing.icon == null && "无法解析的材料".equals(missing.name)
                && "absentmod:missing_part:62".equals(missing.identity), "unavailable mod never invents a substitute");
        } finally {
            GregTechAPI.METATILEENTITIES[first] = oldFirst;
            GregTechAPI.METATILEENTITIES[second] = oldSecond;
            GregTechAPI.METATILEENTITIES[untranslated] = oldThird;
        }
    }

    private static HologramLibraryMaterialDescription.Row row(String id, int meta) {
        return new HologramLibraryMaterialDescription.Row(new HologramLibraryMaterialForecast.Identity(id, meta), 2, null);
    }

    private static MTEHatchOutputBusME prototype(String name) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var prototype = (MTEHatchOutputBusME) ((sun.misc.Unsafe) field.get(null)).allocateInstance(MTEHatchOutputBusME.class);
        Field key = CommonMetaTileEntity.class.getDeclaredField("mName");
        key.setAccessible(true);
        key.set(prototype, name);
        return prototype;
    }
}