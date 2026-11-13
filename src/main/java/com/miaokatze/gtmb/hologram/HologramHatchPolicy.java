// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;

import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.blocks.ItemMachines;

/** Captures declared hatch types at the original API, independently of automatic fill quotas. */
public final class HologramHatchPolicy {

    private static final Method MATCHES_HATCH = findMatchesHatch();
    private static final Map<Object, Integer> SINGLE_IDS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, Integer> ELEMENT_SINGLE_IDS = new WeakHashMap<>();
    private static final Map<Object, BiFunction<Object, ItemStack, Predicate<ItemStack>>> BUILDERS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, BiFunction<Object, ItemStack, Predicate<ItemStack>>> ELEMENTS = new WeakHashMap<>();
    private static final Map<Object, Map<IHatchElement<?>, Integer>> REQUIREMENTS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, Map<IHatchElement<?>, Integer>> ELEMENT_REQUIREMENTS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, java.util.EnumSet<ForgeDirection>> DISALLOWED = new WeakHashMap<>();
    private static final Map<Object, List<IHatchElement<?>>> DECLARED = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, List<IHatchElement<?>>> ELEMENT_DECLARED = new WeakHashMap<>();

    public static synchronized List<IHatchElement<?>> declared(IStructureElement<?> element) {
        List<IHatchElement<?>> types = ELEMENT_DECLARED.get(element);
        return types == null ? java.util.Collections.emptyList() : new ArrayList<>(types);
    }

    /** A mandatory hatch marker is understood only through its complete, source-reviewed builder declaration. */
    static boolean completeMarker(IStructureElement<?> element) throws ReflectiveOperationException {
        for (int depth = 0; element != null && depth <= 12; depth++) {
            // A known branch cannot certify another arbitrary fallback in the same structural alternative.
            if (element instanceof IStructureElementChain) return false;
            if (completeMarkerLeaf(element)) return true;
            element = HologramDeclarationWrappers.backing(element);
        }
        return false;
    }

    private static synchronized boolean completeMarkerLeaf(IStructureElement<?> element) {
        Method factory = element.getClass()
            .getEnclosingMethod();
        List<IHatchElement<?>> types = ELEMENT_DECLARED.get(element);
        return factory != null && factory.getDeclaringClass() == gregtech.api.util.HatchElementBuilder.class
            && "build".equals(factory.getName())
            && types != null
            && !types.isEmpty()
            && ELEMENTS.containsKey(element);
    }

    public static synchronized void directions(IStructureElement<?> element,
        java.util.EnumSet<ForgeDirection> disallowed) {
        DISALLOWED.put(element, disallowed.clone());
    }

    public static synchronized boolean allowed(IStructureElement<?> element,
        com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing facing, ForgeDirection worldDirection) {
        java.util.EnumSet<ForgeDirection> excluded = DISALLOWED.get(element);
        if (excluded == null) return true;
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            if (excluded.contains(direction)) continue;
            ForgeDirection mapped = facing.getWorldDirection(
                direction == ForgeDirection.UP || direction == ForgeDirection.DOWN ? direction.getOpposite()
                    : direction);
            if (mapped == worldDirection) return true;
        }
        return false;
    }

    public static synchronized void requirements(Object builder,
        Map<? extends IHatchElement<?>, ? extends Number> entries) {
        Map<IHatchElement<?>, Integer> values = new java.util.LinkedHashMap<>();
        entries.forEach((type, count) -> values.put(type, Math.max(0, count.intValue())));
        REQUIREMENTS.put(builder, values);
    }

    public static synchronized Map<IHatchElement<?>, Integer> requirements(IStructureElement<?> element) {
        Map<IHatchElement<?>, Integer> values = ELEMENT_REQUIREMENTS.get(element);
        return values == null ? java.util.Collections.emptyMap() : new java.util.LinkedHashMap<>(values);
    }

    /** Layer/context counts cannot be flattened into the whole-machine preset's name/max quotas. */
    static synchronized boolean globalRequirementsComplete(IStructureElement<?> element) {
        for (IStructureElement<?> part : HologramElementCatalog.parts(element)) {
            Map<IHatchElement<?>, Integer> values = ELEMENT_REQUIREMENTS.get(part);
            if (values != null)
                for (IHatchElement<?> type : values.keySet()) if (contribution(type, null, 0) < 0) return false;
        }
        return true;
    }

    private HologramHatchPolicy() {}

    private static Method findMatchesHatch() {
        try {
            return IHatchElement.class.getMethod("matchesHatch", IMetaTileEntity.class);
        } catch (NoSuchMethodException absentOnBeta1And2) {
            return null;
        }
    }

    public static boolean matchesHatch(IHatchElement<?> element, IMetaTileEntity tile) {
        if (MATCHES_HATCH == null) return legacyMatchesHatch(element, tile);
        try {
            return (boolean) MATCHES_HATCH.invoke(element, tile);
        } catch (InvocationTargetException failedDelegate) {
            Throwable cause = failedDelegate.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Hatch matcher failed", cause);
        } catch (IllegalAccessException inaccessibleDelegate) {
            throw new IllegalStateException("Cannot invoke hatch matcher", inaccessibleDelegate);
        }
    }

    static boolean legacyMatchesHatch(IHatchElement<?> element, IMetaTileEntity tile) {
        return element.mteClasses()
            .stream()
            .anyMatch(type -> type.isInstance(tile));
    }

    public static synchronized void declare(Object builder, Collection<? extends IHatchElement<?>> elements,
        boolean sharedBlacklist) {
        SINGLE_IDS.remove(builder);
        List<IHatchElement<?>> declared = new ArrayList<>(elements);
        // Native decorators preserve the reviewed matching declaration, independently of their count/adder callbacks.
        if (declared.stream()
            .anyMatch(element -> !reviewed(element, 0))) {
            reset(builder);
            return;
        }
        DECLARED.put(builder, declared);
        if (!sharedBlacklist) {
            Map<IHatchElement<?>, Integer> values = new java.util.LinkedHashMap<>();
            if (declared.size() == 1) values.put(declared.get(0), 1);
            REQUIREMENTS.put(builder, values);
        }
        BUILDERS.put(builder, (context, trigger) -> stack -> {
            IMetaTileEntity tile = ItemMachines.getMetaTileEntity(stack);
            if (tile == null) return false;
            for (IHatchElement<?> element : declared) {
                // The builder blacklist limits automatic placement/preview, not structural acceptance.
                // Explicit declaration candidates must also include manually installed specialised hatches.
                if (matchesHatch(element, tile)) return true;
            }
            return false;
        });
    }

    private static boolean reviewed(IHatchElement<?> element, int depth) {
        return inspect(element, null, depth, false, new IdentityHashMap<>()) >= 0;
    }

    /** Native Either.count adds its branches, including repeated leaves. Never execute a custom count callback. */
    static int contribution(IHatchElement<?> element, IMetaTileEntity tile) {
        return Math.max(0, contribution(element, tile, 0));
    }

    private static int contribution(IHatchElement<?> element, IMetaTileEntity tile, int depth) {
        return inspect(element, tile, depth, true, new IdentityHashMap<>());
    }

    /** Read only native declaration fields; never execute a custom matcher, counter or hatch adder. */
    private static int inspect(IHatchElement<?> element, IMetaTileEntity tile, int depth, boolean quota,
        IdentityHashMap<IHatchElement<?>, Boolean> path) {
        if (element == null || depth >= 12 || path.put(element, Boolean.TRUE) != null) return -1;
        if (element instanceof gregtech.api.enums.HatchElement || reviewedSteamBus(element)) {
            path.remove(element);
            return tile != null && matchesHatch(element, tile) ? 1 : 0;
        }
        Class<?> type = element.getClass();
        try {
            if (type.getClassLoader() != IHatchElement.class.getClassLoader()) return -1;
            if (type.getName()
                .equals("gregtech.api.interfaces.HatchElement")) {
                // .265 matchesHatch delegates only to mBacking. Older class-based matching is not this contract.
                if (MATCHES_HATCH == null) return -1;
                if (quota) {
                    Field count = type.getDeclaredField("mCount");
                    if (count.getType() != java.util.function.ToLongFunction.class) return -1;
                    count.setAccessible(true);
                    if (count.get(element) != null) return -1;
                }
                Field backing = type.getDeclaredField("mBacking");
                if (backing.getType() != IHatchElement.class) return -1;
                backing.setAccessible(true);
                return inspect((IHatchElement<?>) backing.get(element), tile, depth + 1, quota, path);
            }
            if (!type.getName()
                .equals("gregtech.api.interfaces.HatchElementEither")) return -1;
            Field first = type.getDeclaredField("first"), second = type.getDeclaredField("second");
            if (first.getType() != IHatchElement.class || second.getType() != IHatchElement.class) return -1;
            first.setAccessible(true);
            second.setAccessible(true);
            int left = inspect((IHatchElement<?>) first.get(element), tile, depth + 1, quota, path);
            int right = inspect((IHatchElement<?>) second.get(element), tile, depth + 1, quota, path);
            return left < 0 || right < 0 ? -1 : left + right;
        } catch (ReflectiveOperationException | RuntimeException changedContract) {
            return -1;
        } finally {
            path.remove(element);
        }
    }

    public static synchronized void reset(Object builder) {
        SINGLE_IDS.remove(builder);
        automaticReject(builder);
    }

    // Native shouldReject gates survival auto-placement, not the explicit, observed hatch ID.
    public static synchronized void automaticReject(Object builder) {
        BUILDERS.remove(builder);
        REQUIREMENTS.remove(builder);
        DECLARED.remove(builder);
    }

    public static synchronized void singleId(Object builder, int id) {
        reset(builder);
        // Fixed upstream machines in the reviewed SteamWoodcutter 'B' branches.
        if (id == 22518 || id == 22576 || id == 31040) SINGLE_IDS.put(builder, id);
    }

    static boolean reviewedSteamBus(IHatchElement<?> element) {
        if (!(element instanceof Enum<?>role)) return false;
        Class<?> type = role.getDeclaringClass();
        return type.getClassLoader() == IHatchElement.class.getClassLoader() && type.getName()
            .equals(
                "gtPlusPlus.xmod.gregtech.api.metatileentity.implementations.base.MTESteamMultiBlockBase$SteamHatchElement")
            && ("InputBus_Steam".equals(role.name()) || "OutputBus_Steam".equals(role.name()));
    }

    static synchronized int manualSteamId(IStructureElement<?> element, Object context) {
        return context != null && context.getClass()
            .getClassLoader() == IHatchElement.class.getClassLoader() ? manualSteamId(
                element,
                context.getClass()
                    .getName())
                : -1;
    }

    static synchronized int manualSteamId(IStructureElement<?> element, String controller) {
        return "com.science.gtnl.common.machine.multiblock.steam.SteamWoodcutter".equals(controller)
            ? ELEMENT_SINGLE_IDS.getOrDefault(element, -1)
            : -1;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static synchronized void andFunction(Object builder, Function filter) {
        // An arbitrary function cannot be queried while displaying or planning a blueprint.
        reset(builder);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static synchronized void andBiFunction(Object builder, BiFunction filter) {
        reset(builder);
    }

    public static synchronized void built(Object builder, IStructureElement<?> element) {
        if (element != null && SINGLE_IDS.containsKey(builder))
            ELEMENT_SINGLE_IDS.put(element, SINGLE_IDS.get(builder));
        BiFunction<Object, ItemStack, Predicate<ItemStack>> rule = BUILDERS.get(builder);
        if (rule != null && element != null) ELEMENTS.put(element, rule);
        if (element != null && REQUIREMENTS.containsKey(builder))
            ELEMENT_REQUIREMENTS.put(element, REQUIREMENTS.get(builder));
        if (element != null && DECLARED.containsKey(builder)) ELEMENT_DECLARED.put(element, DECLARED.get(builder));
    }

    public static synchronized Predicate<ItemStack> predicate(IStructureElement<?> element, Object context,
        ItemStack trigger) {
        int id = manualSteamId(element, context);
        if (id >= 0)
            return stack -> stack != null && stack.getItem() instanceof ItemMachines && stack.getItemDamage() == id;
        BiFunction<Object, ItemStack, Predicate<ItemStack>> rule = ELEMENTS.get(element);
        return rule == null ? null : rule.apply(context, trigger);
    }
}
