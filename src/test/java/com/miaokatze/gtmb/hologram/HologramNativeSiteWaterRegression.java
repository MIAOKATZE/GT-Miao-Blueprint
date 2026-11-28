// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.util.GTStructureUtility;
import gregtech.common.tileentities.machines.multi.MTEOreWashingPlant;

/** Actual .277 washer D declaration through source collection; it does not simulate machine formation. */
public final class HologramNativeSiteWaterRegression {

    private HologramNativeSiteWaterRegression() {}

    public static void main(String[] args) throws Exception {
        if (!com.miaokatze.gtmb.testutil.MinecraftTestBootstrap.enter(HologramNativeSiteWaterRegression.class, args))
            return;
        Thread.currentThread()
            .setName("Server thread");
        HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
            try {
                verify();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        System.out.println(
            "NativeSiteWater PASS: washer optional water/air omitted; required water/air and saved water retained");
    }

    private static void verify() throws Exception {
        var definition = new MTEOreWashingPlant("gtmb_site_water_definition").getStructureDefinition();
        IStructureElement<?> optional = null;
        for (var element : definition.getStructureFor("main")) {
            if (!(element instanceof IStructureElementChain<?>chain)) continue;
            var children = HologramDeclarationWrappers.nativeChainChildren(chain);
            if (children == null) continue;
            boolean water = false, air = false;
            for (var child : children) {
                Method factory = HologramDeclarationWrappers.factory(child.getClass());
                water |= factory != null && factory.getDeclaringClass() == GTStructureUtility.class
                    && factory.getName()
                        .equals("ofAnyWater");
                air |= HologramElementCatalog.nativeAir(child);
            }
            if (water && air) {
                optional = element;
                break;
            }
        }
        require(optional != null, "Actual washer D water-or-air declaration was not found");
        collect(optional, Blocks.water, false, false);
        collect(optional, Blocks.flowing_water, false, false);
        collect(optional, Blocks.air, false, false);
        collect(GTStructureUtility.ofAnyWater(false), Blocks.water, true, false);
        collect(StructureUtility.isAir(), Blocks.water, true, false);
        collect(StructureUtility.isAir(), Blocks.air, true, true);
        collect(StructureUtility.ofBlock(Blocks.stone, 0), Blocks.air, false, false);
        collect(null, Blocks.air, false, false);
        IStructureElementChain<Object> unreviewed = new IStructureElementChain<>() {

            @Override
            public IStructureElement<Object>[] fallbacks() {
                throw new AssertionError("Source collection executed an unreviewed alternatives callback");
            }
        };
        collect(unreviewed, Blocks.water, true, false);
        byte[] saved = ("{\"schema\":2,\"blueprint\":{\"schema\":2,\"id\":\"test\",\"name\":\"Water\",\"cells\":["
            + "{\"x\":2,\"y\":0,\"z\":0,\"block\":\"minecraft:water\",\"metadata\":0}]}}")
                .getBytes(StandardCharsets.UTF_8);
        var rows = HologramLocalBlueprintDocument.open(saved, "")
            .snapshot()
            .getTagList("cells", 10);
        require(
            rows.tagCount() == 1 && rows.getCompoundTagAt(0)
                .getString("wantId")
                .equals("minecraft:water"),
            "Ordinary saved blueprint water was removed by a machine-only rule");
    }

    private static void collect(IStructureElement<?> element, Block actual, boolean retained, boolean requiredAir)
        throws Exception {
        BaseMetaTileEntity live = new BaseMetaTileEntity();
        var machine = new MTEOreWashingPlant("gtmb_site_water_collect");
        live.setMetaTileEntity(machine);
        machine.setBaseMetaTileEntity(live);
        live.yCoord = 128;
        var world = HologramResourceWorld.create(() -> new Scene(actual));
        world.tile = live;
        live.setWorldObj(world);
        var settings = new NBTTagCompound();
        settings.setInteger("main", 1);
        try (var plan = HologramNativeSitePlan.begin(live, settings)) {
            Method read = HologramNativeSitePlan.class.getDeclaredMethod("read", int.class, int.class, int.class);
            read.setAccessible(true);
            Object cell = read.invoke(plan, 1, 128, 0);
            Method collect = HologramNativeSitePlan.class.getDeclaredMethod(
                "collect",
                HologramNativeTargetPlan.Offset.class,
                IStructureElement.class,
                cell.getClass());
            collect.setAccessible(true);
            collect.invoke(plan, new HologramNativeTargetPlan.Offset(1, 0, 0), element, cell);
            var field = HologramNativeSitePlan.class.getDeclaredField("cells");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cells = (List<HologramNativeTargetPlan.Cell>) field.get(plan);
            require(
                plan.footprint()
                    .size() == (retained ? 1 : 0) && cells.size() == (retained ? 1 : 0),
                "Source material/removal membership disagrees for " + actual + " / " + element);
            if (retained) require(
                cells.get(0)
                    .block() == actual
                    && cells.get(0)
                        .requiredAir() == requiredAir,
                "Retained required water/air identity changed");
            var readset = HologramNativeSitePlan.class.getDeclaredField("scene");
            readset.setAccessible(true);
            require(
                ((java.util.Map<?, ?>) readset.get(plan)).size() == 1,
                "Omitted optional cells must remain in the consistency readset");
        }
    }

    private static final class Scene extends HologramResourceWorld {

        private final Block actual;

        Scene(Block actual) {
            super(GregTechAPI.sBlockMachines, 0);
            this.actual = actual;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return x == 1 && y == 128 && z == 0 ? actual : Blocks.air;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return 0;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return x == 0 && y == 128 && z == 0 ? tile : null;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
