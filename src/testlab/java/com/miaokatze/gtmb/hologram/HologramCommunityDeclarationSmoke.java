package com.miaokatze.gtmb.hologram;

import java.util.IdentityHashMap;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.GTStructureUtility;

/**
 * Optional native declaration smoke. Requires actual installed/registered mods and Mixin; never installs synthetic
 * community classes, never writes blocks, and never calls check or placement. Passing is declaration coverage only.
 */
public final class HologramCommunityDeclarationSmoke {

    private HologramCommunityDeclarationSmoke() {}

    /** Confirms automatic standard factory recording in a fully initialized GregTech runtime. */
    public static void runStandardFactories() {
        IStructureElement<Object> glass = GTStructureUtility.chainAllGlasses(
            -1,
            (machine, tier) -> { throw new AssertionError("glass setter invoked"); },
            machine -> { throw new AssertionError("glass getter invoked"); });
        IStructureElement<Object> casings = GTStructureUtility.chainAllCasings(
            -1,
            (machine, tier) -> { throw new AssertionError("casing setter invoked"); },
            machine -> { throw new AssertionError("casing getter invoked"); });
        IStructureElement<Object> pipes = GTStructureUtility.chainItemPipeCasings(
            -1,
            (machine, tier) -> { throw new AssertionError("pipe setter invoked"); },
            machine -> { throw new AssertionError("pipe getter invoked"); });
        for (IStructureElement<?> element : new IStructureElement<?>[] { glass, casings, pipes }) {
            if (HologramDeclarationAdapters.declaration(element) == null)
                throw new AssertionError("Standard factory RETURN recorder did not run");
            HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
            candidates.collect(element, null, null, 0, 0, 0, new ItemStack(Items.feather, 2), null, 0);
            if (candidates.stacks.isEmpty()) throw new AssertionError("Standard factory has no declared candidates");
        }
        System.out.println("[GTMB-COMMUNITY-DECLARATIONS] STANDARD FACTORIES PASS glass/casing/item_pipe");
    }

    /** Test-only class selectors are samples, never production machine adapters. Missing samples fail strictly. */
    public static void runRequired(String... nativeClasses) {
        if (nativeClasses == null || nativeClasses.length == 0) throw new AssertionError("No native samples selected");
        for (String name : nativeClasses) {
            IMetaTileEntity prototype = null;
            for (IMetaTileEntity registered : GregTechAPI.METATILEENTITIES) {
                if (registered != null && registered.getClass()
                    .getName()
                    .equals(name)) {
                    prototype = registered;
                    break;
                }
            }
            if (prototype == null) throw new AssertionError("Required native sample is not registered: " + name);
            // A detached native copy protects the registration prototype's instance counters and machine state.
            IMetaTileEntity detached = prototype.newMetaEntity(null);
            if (!(detached instanceof MTEEnhancedMultiBlockBase) || !(detached instanceof IConstructable))
                throw new AssertionError("Native sample does not expose standard GT structure API: " + name);
            MTEEnhancedMultiBlockBase<?> machine = (MTEEnhancedMultiBlockBase<?>) detached;
            ItemStack signal = new ItemStack(Items.feather, 2);
            IStructureDefinition<?> definition = machine.getStructureDefinition();
            HologramCapture capture = HologramCapture.declarationPieces((IConstructable) machine, signal);
            if (capture.pieces.isEmpty()) throw new AssertionError("No native construct pieces captured: " + name);
            int count = 0, recognized = 0, partial = 0;
            IdentityHashMap<IStructureElement<?>, Boolean> seen = new IdentityHashMap<>();
            for (HologramCapture.Piece piece : capture.pieces) {
                IStructureElement<?>[] elements = definition.getStructureFor(piece.name);
                if (elements == null || elements.length == 0) throw new AssertionError("Empty native piece: " + name);
                for (IStructureElement<?> element : elements) {
                    if (element == null || seen.put(element, Boolean.TRUE) != null) continue;
                    count++;
                    HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
                    candidates.collect(element, machine, null, 0, 0, 0, piece.trigger, null, 0);
                    if (!candidates.stacks.isEmpty()) recognized++;
                    if (!candidates.complete) partial++;
                }
            }
            if (recognized == 0) throw new AssertionError("No native declarations recognized: " + name);
            System.out.println(
                "[GTMB-COMMUNITY-DECLARATIONS] NATIVE DECLARATION PASS " + name
                    + " pieces="
                    + capture.pieces.size()
                    + " distinctElements="
                    + count
                    + " recognized="
                    + recognized
                    + " partial="
                    + partial
                    + " (declaration-only; world placement not tested)");
        }
    }
}
