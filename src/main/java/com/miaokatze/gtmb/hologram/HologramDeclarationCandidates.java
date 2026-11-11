// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.apache.commons.lang3.tuple.Pair;

import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.Declarations;
import com.miaokatze.gtmb.hologram.HologramBlueprintResources.ExactKey;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.HatchElementBuilder;

/** Reads exact declarations without running check, hatch adders or setters on the live controller. */
final class HologramDeclarationCandidates {

    private final HologramDeclarationCollector collector;
    final List<ItemStack> stacks = new ArrayList<>();
    private final List<ItemStack> placementStacks = new ArrayList<>();
    private final List<Declarations.PureElement<?>> placementContracts = new ArrayList<>();
    private final IdentityHashMap<IStructureElement<?>, Map<Pair<Integer, ExactKey>, Boolean>> seen = new IdentityHashMap<>();
    private int observations;
    boolean complete = true;

    HologramDeclarationCandidates() {
        this(null);
    }

    HologramDeclarationCandidates(HologramDeclarationCollector collector) {
        this.collector = collector;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    void collect(IStructureElement element, Object context, World world, int x, int y, int z, ItemStack trigger,
        AutoPlaceEnvironment environment, int depth) {
        collect(element, context, world, x, y, z, trigger, environment, depth, true);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void collect(IStructureElement element, Object context, World world, int x, int y, int z, ItemStack trigger,
        AutoPlaceEnvironment environment, int depth, boolean placement) {
        if (element == null || depth > 12 || observations >= 128) {
            complete = false;
            return;
        }
        Map<Pair<Integer, ExactKey>, Boolean> signals = seen.computeIfAbsent(element, ignored -> new HashMap<>());
        Pair<Integer, ExactKey> signal = trigger == null ? Pair.of(0, null)
            : Pair.of(trigger.stackSize, HologramValues.lookupKey(trigger));
        Boolean previous = signals.get(signal);
        if (previous != null && (previous || !placement)) return;
        signals.put(trigger == null ? signal : Pair.of(trigger.stackSize, HologramValues.stackKey(trigger)), placement);
        observations++;
        if (element instanceof Declarations.PureElement) {
            Declarations.PureElement<?> declaration = (Declarations.PureElement<?>) element;
            if (placement) placementContracts.add(declaration);
            for (ItemStack stack : declaration.candidates()) add(stack, false);
            complete &= declaration.complete();
            return;
        }
        try {
            IStructureElement<?> backing = HologramDeclarationWrappers.backing(element);
            if (backing != null) {
                HologramDeclarationAdapters.Declaration projection = HologramDeclarationAdapters.declaration(element);
                boolean advisory = projection != null && !projection.backingAuthority;
                if (advisory) complete = false;
                if (projection == null && HologramDeclarationWrappers.isLazy(element)) complete = false;
                collect(
                    backing,
                    context,
                    world,
                    x,
                    y,
                    z,
                    HologramDeclarationWrappers.trigger(element, trigger),
                    environment,
                    depth + 1,
                    placement && !advisory);
                // Advisory data supplements the original OR declaration; only its own
                // real children can still establish their independent placement facts.
                if (!advisory || !(element instanceof IStructureElementChain)) return;
            }
            if (element instanceof IStructureElementChain) {
                IStructureElement<?>[] children = ((IStructureElementChain<?>) element).fallbacks();
                if (children == null || children.length > 64) complete = false;
                else for (IStructureElement<?> child : children)
                    collect(child, context, world, x, y, z, trigger, environment, depth + 1, placement);
                return;
            }
            IStructureElement<?> alternative = HologramDeclarationWrappers.alternativesBacking(element);
            if (alternative != null) {
                // Alternatives can be read through an initialized lazy element or transform;
                // its arbitrary transform/supplier is never executed and completeness is not asserted.
                complete = false;
                collect(alternative, context, world, x, y, z, trigger, environment, depth + 1, false);
                return;
            }
            Method factory = HologramDeclarationWrappers.factory(element.getClass());
            if (factory == null) {
                if (element != StructureUtility.isAir()) {
                    complete = false;
                    collectNativeOutput(element, context, world, x, y, z, trigger, environment, depth, placement);
                }
                return;
            }
            String name = factory.getName();
            if (factory.getDeclaringClass() == HatchElementBuilder.class && "build".equals(name)) {
                Predicate<ItemStack> declared = HologramHatchPolicy.predicate(element, context, trigger);
                if (declared == null) {
                    complete = false;
                    // A builder with a replacement filter has no recoverable full structural declaration.
                    // Do not expand a potentially broad predicate over the whole machine registry.
                    return;
                }
                if (collector == null) collectHatches(declared, placement);
                else {
                    HologramDeclarationCandidates shared = collector.hatches(element, trigger, declared);
                    for (ItemStack stack : shared.stacks) add(stack, placement);
                    complete &= shared.complete;
                }
                return;
            }
            if (factory.getDeclaringClass() == StructureUtility.class && "ofBlocksTiered".equals(name)) {
                Object hints = captured(element, "val$hints");
                if (!(hints instanceof List) || ((List<?>) hints).isEmpty() || ((List<?>) hints).size() > 128) {
                    complete = false;
                    return;
                }
                for (Object hint : (List<?>) hints) {
                    if (!(hint instanceof Pair)) {
                        complete = false;
                        continue;
                    }
                    Pair<?, ?> pair = (Pair<?, ?>) hint;
                    if (pair.getLeft() instanceof Block && pair.getRight() instanceof Integer)
                        add(new ItemStack((Block) pair.getLeft(), 1, (Integer) pair.getRight()), placement);
                    else complete = false;
                }
                // allKnownTiers describes known alternatives, not every value of an arbitrary tier extractor.
                complete = false;
                return;
            }
            boolean fixed = factory.getDeclaringClass() == StructureUtility.class && "ofBlock".equals(name);
            if (fixed && captured(element, "val$block") == net.minecraft.init.Blocks.lit_redstone_lamp) {
                // This reviewed fixed declaration retains its world state, but pays the vanilla lamp item.
                ItemStack material = HologramBlockIdentity.materialStack(
                    net.minecraft.init.Blocks.lit_redstone_lamp,
                    (Integer) captured(element, "val$meta"));
                if (material == null) complete = false;
                else add(material, placement);
                return;
            }
            boolean flat = factory.getDeclaringClass() == StructureUtility.class && "ofBlocksFlat".equals(name);
            if (factory.getDeclaringClass() == StructureUtility.class && "ofBlockAnyMeta".equals(name)) {
                Object accepted = captured(element, "val$block");
                if (!(accepted instanceof Block)) {
                    complete = false;
                    return;
                }
                HologramReplacementFamily.Family family = HologramReplacementFamily.wildcard(element);
                if (family != null) for (ItemStack stack : family.targets()) add(stack, placement);
                complete = false; // Inventory metadata mappings need not represent every accepted world metadata.
                return;
            }
            boolean frame = factory.getDeclaringClass() == GTStructureUtility.class && "ofFrame".equals(name)
                && HologramFrameSupport.target(element) != null;
            boolean coil = factory.getDeclaringClass() == GTStructureUtility.class && "ofCoil".equals(name);
            // The public protocol is usable without factory provenance. Its current outputs
            // prove individual materials, never exhaustive acceptance or a complete structure.
            if (!fixed && !flat && !frame) complete = false;
            collectNativeOutput(element, context, world, x, y, z, trigger, environment, depth, placement);
            if (coil) {
                for (ItemStack target : new ArrayList<>(stacks)) {
                    Block block = Block.getBlockFromItem(target.getItem());
                    HologramReplacementFamily.Family family = HologramReplacementFamily.resolve(
                        element,
                        block,
                        target.getItem()
                            .getMetadata(target.getItemDamage()));
                    if (family != null)
                        for (ItemStack alternativeStack : family.targets()) add(alternativeStack, placement);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            complete = false;
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void collectNativeOutput(IStructureElement element, Object context, World world, int x, int y, int z,
        ItemStack trigger, AutoPlaceEnvironment environment, int depth, boolean placement)
        throws ReflectiveOperationException {
        IStructureElement.BlocksToPlace blocks = element
            .getBlocksToPlace(context, world, x, y, z, trigger == null ? null : trigger.copy(), environment);
        if (blocks == null || blocks.getStacks() == null) {
            complete = false;
        } else {
            int count = 0;
            for (ItemStack stack : blocks.getStacks()) {
                if (count++ >= 128) {
                    complete = false;
                    break;
                }
                add(stack, placement);
            }
        }
        // The standard public lazy protocol initializes its own declaration. Read that
        // actual backing for roles and alternatives without calling the resolver again.
        if (HologramDeclarationWrappers.isLazy(element)) {
            IStructureElement<?> backing = HologramDeclarationWrappers.nativeBacking(element);
            if (backing != null) collect(backing, context, world, x, y, z, trigger, environment, depth + 1, placement);
        }
    }

    void collectHatches(Predicate<ItemStack> declared) {
        collectHatches(declared, true);
    }

    private void collectHatches(Predicate<ItemStack> declared, boolean placement) {
        try {
            for (IMetaTileEntity prototype : GregTechAPI.METATILEENTITIES) {
                if (prototype == null) continue;
                ItemStack stack = prototype.getStackForm(1);
                if (stack != null && declared.test(stack)) add(stack, placement);
            }
        } catch (RuntimeException | LinkageError invalid) {
            // Preserve the original partial-candidate/incomplete result when a registry entry rejects reads.
            complete = false;
        }
    }

    private static Object captured(Object element, String name) throws ReflectiveOperationException {
        Field field = element.getClass()
            .getDeclaredField(name);
        if (!field.isSynthetic()) throw new IllegalArgumentException("Unexpected declaration field");
        field.setAccessible(true);
        return field.get(element);
    }

    /** A partial enumeration can prove a particular native construction output; display-only alternatives cannot. */
    boolean allows(ItemStack target, boolean automatic) {
        if (target == null) return false;
        for (Declarations.PureElement<?> contract : placementContracts)
            if (contract.accepts(target) && (!automatic || contract.placementAllows(target))) return true;
        return placementStacks.stream()
            .anyMatch(candidate -> HologramValues.sameMaterial(candidate, target));
    }

    private void add(ItemStack stack, boolean placement) {
        if (stack == null || stack.getItem() == null) return;
        if (stack.getItemDamage() < 0 || stack.getItemDamage() == 32767) {
            complete = false;
            return;
        }
        boolean present = false;
        for (ItemStack existing : stacks)
            if (existing.getItem() == stack.getItem() && existing.getItemDamage() == stack.getItemDamage()
                && ItemStack.areItemStackTagsEqual(existing, stack)) {
                    present = true;
                    break;
                }
        if (!present && stacks.size() >= 512) {
            complete = false;
            return;
        }
        if (placement && placementStacks.stream()
            .noneMatch(existing -> HologramValues.sameMaterial(existing, stack))) {
            ItemStack copy = stack.copy();
            copy.stackSize = 1;
            placementStacks.add(copy);
        }
        if (present) return;
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        stacks.add(copy);
    }
}
