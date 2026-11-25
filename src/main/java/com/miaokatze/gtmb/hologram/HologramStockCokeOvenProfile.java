// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.StructureSources;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchCokeOven;
import gregtech.api.util.HatchElementBuilder;
import gregtech.common.blocks.BlockCasings12;
import gregtech.common.blocks.ItemCasings;
import gregtech.common.tileentities.machines.multi.MTECokeOven;

/** Audited, optional shell profile for GT5-U's fixed basic Coke Oven (#236), not the industrial machine. */
final class HologramStockCokeOvenProfile {

    static final String SOURCE_ID = "gtmb:gregtech_coke_oven_236";
    static final String PIECE = "main";
    static final int A = 1, B = 1, C = 0;
    static final int CONTROLLER = MetaTileEntityIDs.COKE_OVEN_CONTROLLER.ID;
    static final int HATCH = MetaTileEntityIDs.COKE_OVEN_HATCH.ID;
    static final int HATCH_TIER = 0;
    private static final int SHELL_CELLS = 25, MAX_ENTRIES = 64;
    private static final Class<?> STEP_A = ((Object) StructureUtility.step(1, 0, 0)).getClass();
    private static final Class<?> STEP_B = ((Object) StructureUtility.step(0, 1, 0)).getClass();
    private static final Class<?> STEP_C = ((Object) StructureUtility.step(0, 0, 1)).getClass();

    enum Shell {
        UNPROVED, READY_CASING, MATCHED_CASING, MATCHED_HATCH, BLOCKED, EXPLICIT_HATCH_UNSATISFIED
    }

    final IStructureDefinition<MTECokeOven> definition;
    private final IMetaTileEntity controllerPrototype, hatchPrototype;
    private final Block casing, machineBlock;
    private final IStructureElement<?> shell, hatch, leaf;
    private final IStructureElement<?>[] nativeElements, sealedElements;
    private final long sourceRevision;

    private HologramStockCokeOvenProfile(IStructureDefinition<MTECokeOven> definition,
        IMetaTileEntity controllerPrototype, IMetaTileEntity hatchPrototype, Block casing, Block machineBlock,
        IStructureElement<?> shell, IStructureElement<?> hatch, IStructureElement<?> leaf, long sourceRevision,
        IStructureElement<?>[] nativeElements, IStructureElement<?>[] sealedElements) {
        this.definition = definition;
        this.controllerPrototype = controllerPrototype;
        this.hatchPrototype = hatchPrototype;
        this.casing = casing;
        this.machineBlock = machineBlock;
        this.shell = shell;
        this.hatch = hatch;
        this.leaf = leaf;
        this.sourceRevision = sourceRevision;
        this.nativeElements = nativeElements;
        this.sealedElements = sealedElements;
    }

    /** Reads only the exact registered stock provider; upstream's getter returns its static final definition. */
    static HologramStockCokeOvenProfile inspectRegistered() {
        IMetaTileEntity controller = GregTechAPI.METATILEENTITIES[CONTROLLER];
        IMetaTileEntity hatch = GregTechAPI.METATILEENTITIES[HATCH];
        Block casing = GregTechAPI.sBlockCasings12, machineBlock = GregTechAPI.sBlockMachines;
        long version = StructureSources.revision();
        if (controller == null || controller.getClass() != MTECokeOven.class || !stockHatch(hatch)
            || machineBlock == null || casing == null || casing.getClass() != BlockCasings12.class
            || casing.hasTileEntity(0)
            || StructureSources.hasSource(MTECokeOven.class)) return null;
        IStructureDefinition<MTECokeOven> definition = ((MTECokeOven) controller).getStructureDefinition();
        if (definition == null || !HologramStructureCursor.supports(definition)) return null;
        IStructureElement<?>[] elements = definition.getStructureFor(PIECE);
        if (elements == null || elements.length > MAX_ENTRIES) return null;
        // Fixed 3x3x3 declaration only. Validate the small immutable shape, never the world or a captured plan.
        IStructureElement<?>[] sealed = elements.clone();
        int a = -A, b = -B, c = -C, seen = 0, shellCount = 0, airCount = 0;
        IStructureElement<?> chain = null;
        for (IStructureElement<?> element : sealed) {
            if (element == null) return null;
            if (isNativeNavigation(element)) {
                a = (element.resetA() ? -A : a) + element.getStepA();
                b = (element.resetB() ? -B : b) + element.getStepB();
                c = (element.resetC() ? -C : c) + element.getStepC();
                continue;
            }
            if (a < -1 || a > 1 || b < -1 || b > 1 || c < 0 || c > 2 || a == 0 && b == 0 && c == 0)
                return null;
            int position = 1 << ((a + 1) + (b + 1) * 3 + c * 9);
            if ((seen & position) != 0) return null;
            seen |= position;
            boolean interior = a == 0 && b == 0 && c == 1;
            if (element == StructureUtility.isAir()) {
                if (!interior) return null;
                airCount++;
            } else {
                if (interior || !(element instanceof IStructureElementChain<?>) || chain != null && element != chain)
                    return null;
                chain = element;
                shellCount++;
            }
            a++;
        }
        if (shellCount != SHELL_CELLS || airCount != 1) return null;
        try {
            IStructureElement<?>[] branches = HologramDeclarationWrappers
                .nativeChainChildren((IStructureElementChain<?>) chain);
            if (branches == null || branches.length != 2 || !factory(branches[0], HatchElementBuilder.class, "build")
                || HologramIncrementalNativePlacement.fixedBlockData(branches[1]) != casing) return null;
            var profile = new HologramStockCokeOvenProfile(
                definition, controller, hatch, casing, machineBlock, chain, branches[0], branches[1], version,
                elements, sealed);
            return profile.declarationCurrent() ? profile : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return null;
        }
    }

    /**
     * Basic Coke Oven uses no shape tier/length signal. Any nonempty (even gt_hatch) channel configuration must
     * be handled as explicit native intent by a different reviewed path; this profile cannot emulate allocation.
     */
    boolean acceptsSettings(int main, NBTTagCompound channels) {
        return current() && main >= 1 && main <= 64 && channels != null && channels.hasNoTags();
    }

    /** Constant sized proof; no tile/world/provider callbacks in a mutation authority check. */
    boolean declarationCurrent() {
        if (!current() || definition.getStructureFor(PIECE) != nativeElements) return false;
        for (int i = 0; i < sealedElements.length; i++)
            if (nativeElements[i] != sealedElements[i]) return false;
        return casingLeaf(shell) == leaf;
    }

    IStructureElement<?>[] nativeElements() {
        return nativeElements;
    }

    boolean shellAt(ExtendedFacing facing, int dx, int dy, int dz) {
        if (Math.abs((long) dx) > 2 || Math.abs((long) dy) > 2 || Math.abs((long) dz) > 2) return false;
        int[] abc = new int[3], xyz = new int[3];
        for (int c = 0; c < 3; c++)
            for (int b = -1; b <= 1; b++)
                for (int a = -1; a <= 1; a++) {
                    if (a == 0 && b == 0 && c < 2) continue;
                    abc[0] = a;
                    abc[1] = b;
                    abc[2] = c;
                    facing.getWorldOffset(abc, xyz);
                    if (xyz[0] == dx && xyz[1] == dy && xyz[2] == dz) return true;
                }
        return false;
    }

    /** GT stock ItemCasings uses vanilla ItemBlock placement and preserves exact metadata. */
    ItemStack casingItem() {
        if (!current()) return null;
        Item item = Item.getItemFromBlock(casing);
        String name = Block.blockRegistry.getNameForObject(casing);
        if (item == null || item.getClass() != ItemCasings.class || ((ItemCasings) item).blockCasings != casing
            || name == null || Block.blockRegistry.getObject(name) != casing || Item.itemRegistry.getObject(name) != item
            || Block.getBlockFromItem(item) != casing || item.getMetadata(0) != 0
            || HologramNativePlacement.supported(casing, null) || HologramMaterialDescriptor.hasExact(casing)
            || HologramDecorMaterials.supports(casing)) return null;
        var sample = new ItemStack(item, 1, 0);
        return HologramGtswnPlacementMaterials.supplyKind(sample) < 0
            && !HologramGtswnPlacementMaterials.freeShell(casing, sample) ? sample : null;
    }

    /** Separately from a physical lease, preserve the stock controller's own formed-machine refusal. */
    boolean acceptsController(IGregTechTileEntity controller) {
        if (!current() || controller == null || controller.getMetaTileID() != CONTROLLER) return false;
        IMetaTileEntity meta = controller.getMetaTileEntity();
        return meta != null && meta.getClass() == MTECokeOven.class && !((MTECokeOven) meta).mMachine;
    }

    /**
     * Use the returned original leaf only after an actual pinned cursor yields this shell root and it has been
     * classified READY_CASING using current loaded-world observations. Connection and edit authority are separate.
     */
    IStructureElement<?> casingLeaf(IStructureElement<?> element) {
        if (element != shell || !current()) return null;
        try {
            IStructureElement<?>[] branches = HologramDeclarationWrappers
                .nativeChainChildren((IStructureElementChain<?>) shell);
            return branches != null && branches.length == 2 && branches[0] == hatch && branches[1] == leaf
                ? leaf : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return null;
        }
    }

    /**
     * No factory or hatch-adder callback and no writes. An exact explicit choice already installed is satisfied;
     * an absent or different hatch must be handled separately, never silently replaced by a casing or another tier.
     * desiredHatch=-1 means no per-cell hatch choice; desiredFacing=-1 means the facing was not changed.
     */
    Shell classify(IStructureElement<?> element, Block live, int meta, TileEntity tile, int desiredHatch,
        int desiredTier, int desiredFacing) {
        if (casingLeaf(element) == null) return Shell.UNPROVED;
        if (desiredHatch != -1 || desiredTier != -1 || desiredFacing != -1) {
            if (desiredHatch != HATCH || desiredTier != HATCH_TIER || desiredFacing < -1
                || desiredFacing >= ForgeDirection.VALID_DIRECTIONS.length)
                return Shell.EXPLICIT_HATCH_UNSATISFIED;
            return matchesHatch(live, tile, desiredFacing) ? Shell.MATCHED_HATCH : Shell.EXPLICIT_HATCH_UNSATISFIED;
        }
        if (matchesHatch(live, tile, -1)) return Shell.MATCHED_HATCH;
        if (tile != null) return Shell.BLOCKED;
        if (live == casing && meta == 0) return Shell.MATCHED_CASING;
        return live == Blocks.air ? Shell.READY_CASING : Shell.BLOCKED;
    }

    private boolean current() {
        return sourceRevision == StructureSources.revision()
            && GregTechAPI.METATILEENTITIES[CONTROLLER] == controllerPrototype
            && GregTechAPI.METATILEENTITIES[HATCH] == hatchPrototype && GregTechAPI.sBlockCasings12 == casing
            && GregTechAPI.sBlockMachines == machineBlock;
    }

    boolean matchesHatch(Block live, TileEntity tile, int desiredFacing) {
        if (live != machineBlock || !(tile instanceof IGregTechTileEntity) || tile.isInvalid())
            return false;
        IGregTechTileEntity gt = (IGregTechTileEntity) tile;
        if (gt.getMetaTileID() != HATCH || !stockHatch(gt.getMetaTileEntity())) return false;
        ForgeDirection facing = gt.getFrontFacing();
        return facing != null && facing != ForgeDirection.UNKNOWN
            && (desiredFacing == -1 || facing.ordinal() == desiredFacing);
    }

    private static boolean stockHatch(IMetaTileEntity hatch) {
        return hatch != null && hatch.getClass() == MTEHatchCokeOven.class && ((MTEHatchCokeOven) hatch).mTier == HATCH_TIER;
    }

    /** The native builder has three different navigation classes for A steps and B/C resets. */
    static boolean isNativeNavigation(IStructureElement<?> element) {
        if (element == null) return false;
        Class<?> type = element.getClass();
        return type == STEP_A || type == STEP_B || type == STEP_C;
    }

    private static boolean factory(IStructureElement<?> element, Class<?> owner, String name) {
        Method factory = element == null ? null : HologramDeclarationWrappers.factory(element.getClass());
        return factory != null && factory.getDeclaringClass() == owner && name.equals(factory.getName());
    }
}
