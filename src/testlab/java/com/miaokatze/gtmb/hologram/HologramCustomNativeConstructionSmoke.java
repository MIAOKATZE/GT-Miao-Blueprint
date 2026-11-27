// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;

/** A custom native constructor with no definition, elements, visits, or real-world writes. */
public final class HologramCustomNativeConstructionSmoke {

    private HologramCustomNativeConstructionSmoke() {}

    public static NBTTagCompound run() throws Exception {
        HologramResourceWorld source = HologramResourceWorld.declarations(Blocks.stone, 0);
        Controller controller = new Controller();
        controller.setWorldObj(source);
        controller.yCoord = 128;
        source.tile = controller;
        Set<Integer> found = new HashSet<>();
        int advances = 0;
        try (HologramNativeStructurePreview preview = new HologramNativeStructurePreview(
            controller,
            controller,
            controller,
            false,
            new ItemStack(Blocks.stone))) {
            boolean done = false;
            while (!done && advances++ < 16) done = preview.advance(System.nanoTime());
            require(done && advances > 1, "zero-result rounds must resume before completion");
            require(preview.definition() == null, "fixture must not expose a structure definition");
            for (var rows = preview.placements(); rows.hasNext();) {
                var row = rows.next();
                require(
                    row.y() == 128 && row.z() == 0 && (row.x() == 1 || row.x() == 2),
                    "only custom constructor cells enter the placement set");
                HologramCapture.Cell cell = preview.targetCell(row);
                require(
                    cell.element == null && cell.block == Blocks.wool && cell.meta == row.x(),
                    "custom target survives without element visits");
                ItemStack paid = preview.targetWorld()
                    .materialAt(row.x(), row.y(), row.z());
                require(
                    paid != null && paid.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.wool)
                        && paid.getItemDamage() == row.x(),
                    "material is bound to the matching custom write");
                require(found.add(row.x()), "custom target coordinates are unique");
            }
            require(found.size() == 2 && controller.round == 5, "all zero and productive rounds precede -1");
        }
        require(
            controller.getWorldObj() == source && source.isAirBlock(1, 128, 0) && source.isAirBlock(2, 128, 0),
            "detached declaration world remains unchanged");
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("cells", found.size());
        result.setInteger("nativeRounds", controller.round);
        result.setInteger("cooperativeAdvances", advances);
        result.setBoolean("noDefinitionOrVisitedElements", true);
        result.setBoolean("exactMaterialCoordinateBinding", true);
        result.setBoolean("sourceWorldUnchanged", true);
        result.setString(
            "scope",
            "representative custom native memory constructor; no installed GTSR/GTNL, GUI or TCP claim");
        return result;
    }

    private static final class Controller extends TileEntity implements ISurvivalConstructable {

        int round;

        @Override
        public int survivalConstruct(ItemStack trigger, int budget, ISurvivalBuildEnvironment env) {
            int step = round++;
            if (step == 0 || step == 2) return 0;
            if (step >= 4) return -1;
            int x = step == 1 ? 1 : 2;
            ItemStack material = new ItemStack(Blocks.wool, 1, x);
            require(
                env.getSource()
                    .takeOne(material, true),
                "custom native material simulation");
            require(worldObj.setBlock(x, yCoord, zCoord, Blocks.wool, x, 3), "custom native world write");
            require(
                env.getSource()
                    .takeOne(material, false),
                "custom native material commit");
            return 1;
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            throw new AssertionError("survival preview must not use the creative constructor");
        }

        @Override
        public String[] getStructureDescription(ItemStack trigger) {
            return new String[0];
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError("custom native construction: " + message);
    }
}
