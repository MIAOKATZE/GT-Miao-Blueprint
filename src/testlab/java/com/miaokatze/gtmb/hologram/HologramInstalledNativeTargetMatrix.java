// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Explicit candidate-only installed native geometry checks; not world placement, GUI or TCP acceptance. */
public final class HologramInstalledNativeTargetMatrix {

    private static final String CENTER = "com.miaokatze.gtsr.common.machine.orecenter.MTEMineralIntegrationCenter";
    private final List<Case> cases = new ArrayList<>();
    private final NBTTagList results = new NBTTagList();
    private final Map<String, Integer> centerCounts = new HashMap<>();
    private final Map<String, Variant> variants = new LinkedHashMap<>();
    private int cursor;

    public HologramInstalledNativeTargetMatrix() {
        for (HologramFourMachineSpec spec : HologramFourMachineSpec.values())
            for (ExtendedFacing facing : spec.orientations()) cases.add(new Case(spec.className, facing, 1, 0));
        for (ExtendedFacing facing : new ExtendedFacing[] { ExtendedFacing.NORTH_NORMAL_NONE,
            ExtendedFacing.EAST_NORMAL_NONE }) {
            cases.add(new Case("gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor", facing, 1, 0));
            cases.add(
                new Case(
                    "com.science.gtnl.common.machine.multiblock.structuralReconstructionPlan.LargeEngravingLaser",
                    facing,
                    1,
                    0));
        }
        for (ExtendedFacing facing : new ExtendedFacing[] { ExtendedFacing.NORTH_NORMAL_NONE,
            ExtendedFacing.EAST_NORMAL_NONE, ExtendedFacing.SOUTH_NORMAL_NONE, ExtendedFacing.WEST_NORMAL_NONE })
            for (int tier = 1; tier <= 4; tier++)
                for (int sectors = 1; sectors <= 6; sectors++) cases.add(new Case(CENTER, facing, tier, sectors));
    }

    /** One bounded native parameter combination per actual laboratory tick. */
    public boolean step() throws Exception {
        if (cursor >= cases.size()) return true;
        Case input = cases.get(cursor);
        int id = registeredId(input.type);
        ItemStack trigger = HologramNativeTargetPlan.canonicalTrigger(input.tier, new NBTTagCompound());
        if (input.sectors > 0) ChannelDataAccessor.setChannelData(trigger, "ore_center_sectors", input.sectors);
        long started = System.nanoTime();
        HologramNativeTargetPlan target = HologramNativeTargetPlan
            .compile(id, input.facing, trigger, new NBTTagCompound());
        require(target.complete(), "target failed: " + target.failure());
        HologramNativeTargetPlan footprint = HologramNativeTargetPlan.compileFootprint(id, input.facing, trigger);
        require(footprint.complete(), "footprint failed: " + footprint.failure());
        Set<HologramNativeTargetPlan.Offset> positions = new HashSet<>();
        int blocks = 0, withoutElement = 0;
        for (HologramNativeTargetPlan.Cell cell : target.cells()) {
            require(
                positions.add(new HologramNativeTargetPlan.Offset(cell.dx(), cell.dy(), cell.dz())),
                "duplicate target coordinate");
            if (cell.requiredAir()) continue;
            blocks++;
            require(
                cell.material() != null && cell.material()
                    .getItem() != null && cell.material().stackSize > 0,
                "every non-air target must have an actual native material");
            if (cell.element() == null) withoutElement++;
            if (CENTER.equals(input.type) || input.type.startsWith("com.science.gtnl."))
                rememberVariant(input.type, cell);
        }
        require(
            blocks > 0 && footprint.footprint()
                .equals(positions),
            "default target and independent destruction footprint must cover exactly the same cells");
        if (CENTER.equals(input.type)) {
            require(withoutElement > 0, "installed integration center must exercise direct custom writes");
            String key = input.tier + ":" + input.facing;
            if (input.sectors == 1) centerCounts.put(key, blocks);
            else require(blocks > centerCounts.get(key), "multi-sector geometry must exceed one sector");
        }
        NBTTagCompound result = new NBTTagCompound();
        result.setString("class", input.type);
        result.setInteger("registeredControllerId", id);
        result.setString("facing", input.facing.toString());
        result.setInteger("mainTier", input.tier);
        result.setInteger("sectors", input.sectors);
        result.setInteger("nonAirTargets", blocks);
        result.setInteger("targetsWithoutVisitedElement", withoutElement);
        result.setInteger("footprintCells", positions.size());
        result.setString("targetDigest", target.targetDigest());
        result.setString("footprintDigest", footprint.footprintDigest());
        result.setLong("elapsedNanos", System.nanoTime() - started);
        results.appendTag(result);
        cursor++;
        return cursor == cases.size();
    }

    public NBTTagCompound evidence() {
        require(cursor == cases.size(), "installed native matrix is incomplete");
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("cases", results.copy());
        result.setInteger("completedCases", cursor);
        result.setInteger("communityMaterialStateVariants", variants.size());
        result.setString(
            "scope",
            "installed native target and footprint only; actual construction/payment, saved blueprint, GUI and TCP NOT_RUN");
        return result;
    }

    /** Narrow detached block/material/state samples only; no controller, element graph or full plan is retained. */
    public List<Variant> variants() {
        require(cursor == cases.size(), "installed native matrix is incomplete");
        require(!variants.isEmpty(), "installed community matrix produced no material/state variants");
        return Collections.unmodifiableList(new ArrayList<>(variants.values()));
    }

    private void rememberVariant(String source, HologramNativeTargetPlan.Cell cell) {
        ItemStack material = cell.material()
            .copy();
        material.stackSize = 1;
        require(
            !HologramRecovery.hasSeal(material),
            "UNSUPPORTED sealed community target material; no variant PASS is permitted");
        require(
            !material.hasTagCompound() || material.getTagCompound()
                .hasNoTags(),
            "UNSUPPORTED retained/unknown community material NBT; no variant PASS is permitted");
        material.setTagCompound(null);
        NBTTagCompound appearance = new NBTTagCompound();
        NBTTagCompound nativeState = cell.tileData();
        if (nativeState != null && (nativeState.hasKey("texturePage") || nativeState.hasKey("textureIndex"))) {
            require(
                nativeState.hasKey("texturePage", 3) && nativeState.hasKey("textureIndex", 3),
                "UNSUPPORTED incomplete community texture state");
            appearance.setInteger("texturePage", nativeState.getInteger("texturePage"));
            appearance.setInteger("textureIndex", nativeState.getInteger("textureIndex"));
        }
        String key = net.minecraft.block.Block.blockRegistry.getNameForObject(cell.block()) + ":"
            + cell.metadata()
            + ":"
            + material.writeToNBT(new NBTTagCompound())
            + ":"
            + cell.facing()
            + ":"
            + appearance;
        Variant variant = variants.get(key);
        if (variant == null) {
            variant = new Variant(cell.block(), cell.metadata(), material, cell.facing(), appearance);
            variants.put(key, variant);
        }
        variant.sources.add(source);
    }

    public static final class Variant {

        private final net.minecraft.block.Block block;
        private final int metadata, facing;
        private final ItemStack material;
        private final NBTTagCompound appearance;
        private final Set<String> sources = new LinkedHashSet<>();

        private Variant(net.minecraft.block.Block block, int metadata, ItemStack material, int facing,
            NBTTagCompound appearance) {
            this.block = block;
            this.metadata = metadata;
            this.material = material.copy();
            this.facing = facing;
            this.appearance = (NBTTagCompound) appearance.copy();
        }

        HologramNativeTargetPlan.Cell target() {
            return new HologramNativeTargetPlan.Cell(
                0,
                0,
                0,
                block,
                metadata,
                material.copy(),
                facing,
                null,
                appearance.hasNoTags() ? null : (NBTTagCompound) appearance.copy(),
                null,
                null,
                false);
        }

        NBTTagCompound description() {
            NBTTagCompound result = new NBTTagCompound();
            result.setString("block", String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(block)));
            result.setInteger("metadata", metadata);
            result.setTag("material", material.writeToNBT(new NBTTagCompound()));
            result.setInteger("facing", facing);
            result.setTag("appearance", appearance.copy());
            result.setString("sourceClasses", String.join(",", sources));
            return result;
        }
    }

    private static int registeredId(String type) {
        int found = -1;
        for (int id = 0; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity machine = GregTechAPI.METATILEENTITIES[id];
            if (machine == null || !type.equals(
                machine.getClass()
                    .getName()))
                continue;
            require(found < 0, "ambiguous installed native controller class: " + type);
            found = id;
        }
        require(found >= 0, "required installed native controller is absent: " + type);
        return found;
    }

    private static final class Case {

        final String type;
        final ExtendedFacing facing;
        final int tier, sectors;

        Case(String type, ExtendedFacing facing, int tier, int sectors) {
            this.type = type;
            this.facing = facing;
            this.tier = tier;
            this.sectors = sectors;
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError("installed native target matrix: " + message);
    }
}
