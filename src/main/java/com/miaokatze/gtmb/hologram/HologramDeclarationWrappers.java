package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

import org.apache.commons.lang3.tuple.Pair;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;

import gregtech.api.GregTechAPI;
import gregtech.api.casing.Casings;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.util.GTStructureUtility;
import gregtech.common.blocks.BlockCasings5;
import gtPlusPlus.core.material.Material;

/** Exact reviewed wrappers; reading declarations never invokes controller callbacks. */
final class HologramDeclarationWrappers {

    private static final Map<IStructureElement<?>, IStructureElement<?>> FIXED_LAZY = new WeakHashMap<>();

    private HologramDeclarationWrappers() {}

    static IStructureElement<?> backing(IStructureElement<?> element) throws ReflectiveOperationException {
        if (isLazy(element)) return lazyBacking(element);
        Method factory = element.getClass()
            .getEnclosingMethod();
        if (factory == null) return null;
        String name = factory.getName();
        String field;
        if (factory.getDeclaringClass() == StructureUtility.class && "withChannel".equals(name)) field = "val$backing";
        else if (factory.getDeclaringClass() == StructureUtility.class && "onElementPass".equals(name)
            || factory.getDeclaringClass() == GTStructureUtility.class && "activeCoils".equals(name))
            field = "val$element";
        else return null;
        return (IStructureElement<?>) captured(element, field);
    }

    static boolean isLazy(IStructureElement<?> element) {
        return element != null && element.getClass()
            .getName()
            .equals("com.gtnewhorizon.structurelib.structure.LazyStructureElement");
    }

    /**
     * Read initialized declarations, or reconstruct the two reviewed fixed-material factories.
     * Never execute the deferred Function/Supplier: it may mutate its controller.
     */
    private static IStructureElement<?> lazyBacking(IStructureElement<?> element) throws ReflectiveOperationException {
        Field initialized = element.getClass()
            .getDeclaredField("elem");
        if (initialized.getType() != IStructureElement.class) return null;
        initialized.setAccessible(true);
        IStructureElement<?> leaf = (IStructureElement<?>) initialized.get(element);
        if (leaf != null) return leaf;
        synchronized (FIXED_LAZY) {
            leaf = FIXED_LAZY.get(element);
            if (leaf != null) return leaf;
            Field deferred = element.getClass()
                .getDeclaredField("to");
            deferred.setAccessible(true);
            Object factory = deferred.get(element);
            Object source = lambdaCapture(factory, GTStructureUtility.class.getName());
            if (source != null) {
                Object material = lambdaCapture(source, GTStructureUtility.class.getName());
                if (material instanceof Material) {
                    ItemStack frame = ((Material) material).getFrameBox(1);
                    if (frame != null && frame.getItem() != null) {
                        Block block = Block.getBlockFromItem(frame.getItem());
                        if (block != null) leaf = StructureUtility.ofBlock(block, frame.getItemDamage());
                    }
                }
            } else {
                Object supplier = lambdaCapture(factory, StructureUtility.class.getName());
                Object casing = lambdaCapture(supplier, "gregtech.api.casing.ICasing");
                if (casing instanceof Casings) {
                    Casings fixed = (Casings) casing;
                    Block block = fixed.getBlock();
                    if (block != null) leaf = StructureUtility.ofBlock(block, fixed.getBlockMeta());
                }
            }
            if (leaf != null) FIXED_LAZY.put(element, leaf);
            return leaf;
        }
    }

    /** A lambda's captured constant is data; invoking its implementation is deliberately avoided. */
    private static Object lambdaCapture(Object lambda, String owner) throws ReflectiveOperationException {
        if (lambda == null || !lambda.getClass()
            .isSynthetic()
            || !lambda.getClass()
                .getName()
                .startsWith(owner + "$$Lambda$"))
            return null;
        Field captured = null;
        for (Field field : lambda.getClass()
            .getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (captured != null) return null;
            captured = field;
        }
        if (captured == null) return null;
        captured.setAccessible(true);
        return captured.get(lambda);
    }

    static IStructureElement<?> alternativesBacking(IStructureElement<?> element) throws ReflectiveOperationException {
        IStructureElement<?> reviewed = backing(element);
        if (reviewed != null) return reviewed;
        Method factory = element.getClass()
            .getEnclosingMethod();
        if (factory != null && factory.getDeclaringClass() == GTStructureUtility.class
            && "triggerItemTransform".equals(factory.getName()))
            return (IStructureElement<?>) captured(element, "val$backing");
        if (factory != null && factory.getDeclaringClass() == GTStructureUtility.class
            && "chainAllGlassesImpl".equals(factory.getName())
            && element instanceof GTStructureUtility.ProxyStructureElement)
            return ((GTStructureUtility.ProxyStructureElement<?, ?>) element).proxiedElement;
        if (element.getClass()
            .getName()
            .equals("com.gtnewhorizon.structurelib.structure.LazyStructureElement")) {
            Field field = element.getClass()
                .getDeclaredField("elem");
            if (field.getType() != IStructureElement.class) return null;
            field.setAccessible(true);
            return (IStructureElement<?>) field.get(element);
        }
        return null;
    }

    static String channel(IStructureElement<?> element) throws ReflectiveOperationException {
        Method factory = element.getClass()
            .getEnclosingMethod();
        return factory != null && factory.getDeclaringClass() == StructureUtility.class
            && "withChannel".equals(factory.getName()) ? (String) captured(element, "val$channel") : null;
    }

    static ItemStack trigger(IStructureElement<?> element, ItemStack trigger) throws ReflectiveOperationException {
        String channel = channel(element);
        return channel == null || trigger == null ? trigger : ChannelDataAccessor.withChannel(trigger, channel);
    }

    static Set<String> channels(IStructureElement<?> element) {
        Set<String> result = new LinkedHashSet<>();
        for (IStructureElement<?> part : HologramElementCatalog.parts(element)) {
            try {
                String channel = channel(part);
                if (channel != null) result.add(channel);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        }
        return result;
    }

    static void observeChannels(IStructureElement<?> element, ItemStack trigger) {
        observeChannels(element, trigger, 0, new IdentityHashMap<>());
    }

    private static void observeChannels(IStructureElement<?> element, ItemStack trigger, int depth,
        IdentityHashMap<IStructureElement<?>, Boolean> seen) {
        if (element == null || trigger == null
            || depth > 12
            || seen.size() >= 128
            || seen.put(element, Boolean.TRUE) != null) return;
        try {
            String channel = channel(element);
            ItemStack next = trigger(element, trigger);
            if (channel != null) {
                HologramChannelTrace.record(trigger, channel, next.stackSize, 1);
                HologramChannelTrace.declareMaterials(channel, materials(backing(element)));
            }
            IStructureElement<?> backing = backing(element);
            if (backing != null) observeChannels(backing, next, depth + 1, seen);
            else if (element instanceof IStructureElementChain) {
                IStructureElement<?>[] children = ((IStructureElementChain<?>) element).fallbacks();
                if (children != null && children.length <= 64)
                    for (IStructureElement<?> child : children) observeChannels(child, trigger, depth + 1, seen);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
    }

    static void observeMaterials(IStructureElement<?> element) {
        for (IStructureElement<?> part : HologramElementCatalog.parts(element)) {
            try {
                String channel = channel(part);
                if (channel != null) HologramChannelTrace.declareMaterials(channel, materials(backing(part)));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        }
    }

    private static List<ItemStack> materials(IStructureElement<?> root) throws ReflectiveOperationException {
        List<ItemStack> result = new ArrayList<>();
        for (IStructureElement<?> part : HologramElementCatalog.parts(root)) {
            Method factory = part.getClass()
                .getEnclosingMethod();
            if (factory == null) continue;
            if (factory.getDeclaringClass() == StructureUtility.class && "ofBlocksTiered".equals(factory.getName())) {
                Object raw = captured(part, "val$hints");
                if (!(raw instanceof List) || ((List<?>) raw).size() > 128) return new ArrayList<>();
                for (Object hint : (List<?>) raw) {
                    if (!(hint instanceof Pair)) return new ArrayList<>();
                    Pair<?, ?> pair = (Pair<?, ?>) hint;
                    if (!(pair.getLeft() instanceof Block) || !(pair.getRight() instanceof Integer))
                        return new ArrayList<>();
                    result.add(new ItemStack((Block) pair.getLeft(), 1, (Integer) pair.getRight()));
                }
                return result;
            }
            if (factory.getDeclaringClass() == GTStructureUtility.class && "ofCoil".equals(factory.getName())) {
                if (GregTechAPI.sBlockCasings5 == null) return result;
                for (int raw = 1; raw <= 14; raw++) {
                    HeatingCoilLevel level = HeatingCoilLevel.getFromTier((byte) (raw - 1));
                    result.add(new ItemStack(GregTechAPI.sBlockCasings5, 1, BlockCasings5.getMetaFromCoilHeat(level)));
                }
                return result;
            }
        }
        return result;
    }

    static Object captured(Object element, String name) throws ReflectiveOperationException {
        Field field = element.getClass()
            .getDeclaredField(name);
        if (!field.isSynthetic()) throw new IllegalArgumentException("Unexpected declaration field");
        field.setAccessible(true);
        return field.get(element);
    }
}
