package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.HatchElementBuilder;

/** Reads exact declarations without running check, hatch adders or setters on the live controller. */
final class HologramDeclarationCandidates {

    final List<ItemStack> stacks = new ArrayList<>();
    final IdentityHashMap<IStructureElement<?>, Boolean> seen = new IdentityHashMap<>();
    boolean complete = true;

    @SuppressWarnings({ "rawtypes", "unchecked" })
    void collect(IStructureElement element, Object context, World world, int x, int y, int z, ItemStack trigger,
        AutoPlaceEnvironment environment, int depth) {
        if (element == null || depth > 12 || seen.size() >= 128) {
            complete = false;
            return;
        }
        if (seen.put(element, Boolean.TRUE) != null) return;
        if (element instanceof Declarations.PureElement) {
            Declarations.PureElement<?> declaration = (Declarations.PureElement<?>) element;
            for (ItemStack stack : declaration.candidates()) add(stack);
            complete &= declaration.complete();
            return;
        }
        try {
            if (element instanceof IStructureElementChain) {
                IStructureElement<?>[] children = ((IStructureElementChain<?>) element).fallbacks();
                if (children == null || children.length > 64) complete = false;
                else for (IStructureElement<?> child : children)
                    collect(child, context, world, x, y, z, trigger, environment, depth + 1);
                return;
            }
            IStructureElement<?> backing = HologramDeclarationWrappers.backing(element);
            if (backing != null) {
                collect(
                    backing,
                    context,
                    world,
                    x,
                    y,
                    z,
                    HologramDeclarationWrappers.trigger(element, trigger),
                    environment,
                    depth + 1);
                return;
            }
            IStructureElement<?> alternative = HologramDeclarationWrappers.alternativesBacking(element);
            if (alternative != null) {
                // Alternatives can be read through an initialized lazy element or transform;
                // its arbitrary transform/supplier is never executed and completeness is not asserted.
                complete = false;
                collect(alternative, context, world, x, y, z, trigger, environment, depth + 1);
                return;
            }
            Method factory = element.getClass()
                .getEnclosingMethod();
            if (factory == null) {
                if (element != StructureUtility.isAir()) complete = false;
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
                for (IMetaTileEntity prototype : GregTechAPI.METATILEENTITIES) {
                    if (prototype == null) continue;
                    ItemStack stack = prototype.getStackForm(1);
                    if (stack != null && declared.test(stack)) add(stack);
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
                        add(new ItemStack((Block) pair.getLeft(), 1, (Integer) pair.getRight()));
                    else complete = false;
                }
                // allKnownTiers describes known alternatives, not every value of an arbitrary tier extractor.
                complete = false;
                return;
            }
            boolean fixed = factory.getDeclaringClass() == StructureUtility.class && "ofBlock".equals(name);
            if (fixed && captured(element, "val$defaultBlock") == net.minecraft.init.Blocks.lit_redstone_lamp) {
                // This reviewed fixed declaration retains its world state, but pays the vanilla lamp item.
                ItemStack material = HologramBlockIdentity.materialStack(
                    net.minecraft.init.Blocks.lit_redstone_lamp,
                    (Integer) captured(element, "val$defaultMeta"));
                if (material == null) complete = false;
                else add(material);
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
                if (family != null) for (ItemStack stack : family.targets()) add(stack);
                complete = false; // Inventory metadata mappings need not represent every accepted world metadata.
                return;
            }
            boolean frame = factory.getDeclaringClass() == GTStructureUtility.class && "ofFrame".equals(name)
                && HologramFrameSupport.target(element) != null;
            boolean coil = factory.getDeclaringClass() == GTStructureUtility.class && "ofCoil".equals(name);
            if (!fixed && !flat && !frame && !coil) {
                complete = false;
                return;
            }
            IStructureElement.BlocksToPlace blocks = element
                .getBlocksToPlace(context, world, x, y, z, trigger, environment);
            if (blocks == null || blocks.getStacks() == null) {
                complete = false;
                return;
            }
            int count = 0;
            for (ItemStack stack : blocks.getStacks()) {
                if (count++ >= 128) {
                    complete = false;
                    break;
                }
                add(stack);
            }
            if (coil) complete = false; // Trigger selects one coil grade; full coil alternatives are not enumerated.
            if (coil) {
                for (ItemStack target : new ArrayList<>(stacks)) {
                    Block block = Block.getBlockFromItem(target.getItem());
                    HologramReplacementFamily.Family family = HologramReplacementFamily.resolve(
                        element,
                        block,
                        target.getItem()
                            .getMetadata(target.getItemDamage()));
                    if (family != null) for (ItemStack alternativeStack : family.targets()) add(alternativeStack);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
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

    private void add(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return;
        if (stack.getItemDamage() < 0 || stack.getItemDamage() == 32767) {
            complete = false;
            return;
        }
        for (ItemStack existing : stacks)
            if (existing.getItem() == stack.getItem() && existing.getItemDamage() == stack.getItemDamage()
                && ItemStack.areItemStackTagsEqual(existing, stack)) return;
        if (stacks.size() >= 512) {
            complete = false;
            return;
        }
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        stacks.add(copy);
    }
}
