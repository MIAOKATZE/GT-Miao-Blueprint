// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;

import gregtech.api.enums.HatchElement;
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
    private static final Map<Object, Predicate<ItemStack>> STRUCTURAL_BUILDERS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, Predicate<ItemStack>> STRUCTURAL_ELEMENTS = new WeakHashMap<>();
    private static final Map<Object, Map<IHatchElement<?>, Integer>> REQUIREMENTS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, Map<IHatchElement<?>, Integer>> ELEMENT_REQUIREMENTS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, java.util.EnumSet<ForgeDirection>> DISALLOWED = new WeakHashMap<>();
    private static final Map<Object, List<IHatchElement<?>>> DECLARED = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, List<IHatchElement<?>>> ELEMENT_DECLARED = new WeakHashMap<>();
    private static final Map<Object, Predicate<ItemStack>> COLD_BUILDERS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, Predicate<ItemStack>> COLD_ELEMENTS = new WeakHashMap<>();
    private static final Map<Object, Object> NATIVE_BROWSE_BUILDERS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, NativeBrowse> NATIVE_BROWSE_ELEMENTS = new WeakHashMap<>();
    private static final Map<Object, Object> SINGLE_ROLE_BROWSE_BUILDERS = new WeakHashMap<>();
    private static final Map<IStructureElement<?>, NativeBrowse> SINGLE_ROLE_BROWSE_ELEMENTS = new WeakHashMap<>();

    private static final class NativeBrowse {

        final WeakReference<Object> builder;
        final Object declaration;

        NativeBrowse(Object builder, Object declaration) {
            this.builder = new WeakReference<>(builder);
            this.declaration = declaration;
        }
    }

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

    /** Native anyOf(single stock role) picker, never an implicit placement. */
    static HatchElement singleNativePresetRole(IStructureElement<?> element) {
        try {
            for (int depth = 0; element != null && depth <= 12; depth++) {
                if (element instanceof IStructureElementChain
                    || HologramDeclarationAdapters.declaration(element) != null) return null;
                if (completeMarkerLeaf(element)) return singleNativePresetLeaf(element);
                var factory = HologramDeclarationWrappers.factory(element.getClass());
                if (factory == null
                    || factory.getDeclaringClass() != com.gtnewhorizon.structurelib.structure.StructureUtility.class
                    || !java.util.Arrays.asList("withChannel", "onElementPass", "onElementFail", "withDescription")
                        .contains(factory.getName()))
                    return null;
                element = HologramDeclarationWrappers.nativeBacking(element);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // No preset offer if the exact native shape cannot be proved.
        }
        return null;
    }

    private static synchronized HatchElement singleNativePresetLeaf(IStructureElement<?> element) {
        var roles = ELEMENT_DECLARED.get(element);
        var needs = ELEMENT_REQUIREMENTS.get(element);
        if (roles == null || roles.size() != 1
            || !(roles.get(0) instanceof HatchElement role)
            || !stockColdCount(role)
            || needs == null
            || needs.size() != 1
            || !Integer.valueOf(1)
                .equals(needs.get(role)))
            return null;
        var proof = SINGLE_ROLE_BROWSE_ELEMENTS.get(element);
        if (proof == null || SINGLE_ROLE_BROWSE_BUILDERS.get(proof.builder.get()) != proof.declaration) return null;
        return role;
    }

    static boolean singleNativePresetMatches(HatchElement role, IMetaTileEntity candidate) {
        return candidate != null && !role.mteBlacklist()
            .contains(candidate.getClass()) && matchesHatch(role, candidate);
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
        COLD_BUILDERS.remove(builder);
        // The atLeast mixin supplies this after declare. Unlike declaration proof, native preview can
        // ask a mixed role's own item filter on an owned, unfilled controller.
        if (!DECLARED.containsKey(builder)) NATIVE_BROWSE_BUILDERS.put(builder, new Object());
        try {
            Predicate<ItemStack> cold = coldPreviewRule(DECLARED.get(builder), entries);
            if (cold != null) COLD_BUILDERS.put(builder, cold);
        } catch (RuntimeException | LinkageError unavailable) {
            // Optional browsing evidence cannot interfere with native builder registration.
        }
    }

    private static Predicate<ItemStack> coldPreviewRule(List<IHatchElement<?>> declared,
        Map<? extends IHatchElement<?>, ? extends Number> entries) {
        // Reproduce the pinned atLeast item filter on a cold context, where reviewed stock counts are zero.
        // The blacklist is shared across ALL declared roles, not just those with a positive minimum.
        if (declared == null || !entries.keySet()
            .equals(new HashSet<>(declared))) return null;
        List<IHatchElement<?>> needed = new ArrayList<>();
        Set<Class<? extends IMetaTileEntity>> blacklist = new HashSet<>();
        for (IHatchElement<?> type : declared) {
            Number minimum = entries.get(type);
            if (!(minimum instanceof Byte || minimum instanceof Short
                || minimum instanceof Integer
                || minimum instanceof Long) || !stockColdCount(type)) return null;
            if (minimum.longValue() > 0) needed.add(type);
            blacklist.addAll(type.mteBlacklist());
        }
        if (needed.isEmpty()) return null;
        return stack -> {
            IMetaTileEntity tile = ItemMachines.getMetaTileEntity(stack);
            if (tile == null || blacklist.contains(tile.getClass())) return false;
            for (IHatchElement<?> type : needed) if (matchesHatch(type, tile)) return true;
            return false;
        };
    }

    private static boolean stockColdCount(IHatchElement<?> type) {
        if (!(type instanceof HatchElement hatch)) return false;
        // These .265 counts read initially empty base lists, not overridable getters or layered callbacks.
        return switch (hatch) {
            case Muffler, Maintenance, InputHatch, InputBus, OutputHatch, OutputBus, Energy, Dynamo -> true;
            default -> false;
        };
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
        COLD_BUILDERS.remove(builder);
        NATIVE_BROWSE_BUILDERS.remove(builder);
        SINGLE_ROLE_BROWSE_BUILDERS.remove(builder);
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
            if (declared.size() == 1) {
                values.put(declared.get(0), 1);
                if (declared.get(0) instanceof HatchElement role && stockColdCount(role))
                    SINGLE_ROLE_BROWSE_BUILDERS.put(builder, new Object());
            }
            REQUIREMENTS.put(builder, values);
        }
        Predicate<ItemStack> structural = stack -> {
            IMetaTileEntity tile = ItemMachines.getMetaTileEntity(stack);
            if (tile == null) return false;
            for (IHatchElement<?> element : declared) {
                // The builder blacklist limits automatic placement/preview, not structural acceptance.
                // Explicit declaration candidates must also include manually installed specialised hatches.
                if (reviewedGtsrDynamo(element) ? nativeDynamo(tile) : matchesHatch(element, tile)) return true;
            }
            return false;
        };
        STRUCTURAL_BUILDERS.put(builder, structural);
        BUILDERS.put(builder, (context, trigger) -> structural);
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
        if (reviewedGtsrDynamo(element)) {
            path.remove(element);
            return tile != null && nativeDynamo(tile) ? 1 : 0;
        }
        if (element instanceof gregtech.api.enums.HatchElement || reviewedSteamBus(element)
            || !quota && reviewedStockCokeElement(element)) {
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
        STRUCTURAL_BUILDERS.remove(builder);
        automaticReject(builder);
    }

    // Native shouldReject gates survival auto-placement, not the explicit, observed hatch ID.
    public static synchronized void automaticReject(Object builder) {
        BUILDERS.remove(builder);
        REQUIREMENTS.remove(builder);
        DECLARED.remove(builder);
        COLD_BUILDERS.remove(builder);
        NATIVE_BROWSE_BUILDERS.remove(builder);
        SINGLE_ROLE_BROWSE_BUILDERS.remove(builder);
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

    private static boolean reviewedGtsrDynamo(IHatchElement<?> element) {
        // GTSR .70 DynamoOutput uses its two standard dynamo lists; steam/layer counters are not inferred.
        return element instanceof Enum<?>role && "DynamoOutput".equals(role.name())
            && role.getDeclaringClass()
                .getClassLoader() == IHatchElement.class.getClassLoader()
            && role.getDeclaringClass()
                .getName()
                .equals("com.miaokatze.gtsr.common.machine.MTEMegaSteamTurbineArray$MegaSteamTurbineArrayHatchElement");
    }

    private static boolean nativeDynamo(IMetaTileEntity tile) {
        return tile instanceof gregtech.api.metatileentity.implementations.MTEHatchDynamo
            || tile instanceof tectech.thing.metaTileEntity.hatch.MTEHatchDynamoMulti;
    }

    private static boolean reviewedStockCokeElement(IHatchElement<?> element) {
        // GT's declared CokeHatch leaf exposes a fixed MTEHatchCokeOven class matcher. Its count/adder are
        // not used here; this trusts one declaration primitive, not the controller or a handpicked position.
        return element instanceof Enum<?>role && "CokeHatch".equals(role.name())
            && role.getDeclaringClass()
                .getClassLoader() == IHatchElement.class.getClassLoader()
            && role.getDeclaringClass()
                .getName()
                .equals("gregtech.common.tileentities.machines.multi.MTECokeOven$CokeHatchElement");
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
        invalidateAdditionalFilter(builder);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static synchronized void andBiFunction(Object builder, BiFunction filter) {
        invalidateAdditionalFilter(builder);
    }

    private static void invalidateAdditionalFilter(Object builder) {
        // GT's structural check calls the native adder only. Additional item filters govern automatic funding
        // and candidate queries, and are never invoked for the already selected model's structural proof.
        SINGLE_IDS.remove(builder);
        BUILDERS.remove(builder);
        COLD_BUILDERS.remove(builder);
        NATIVE_BROWSE_BUILDERS.remove(builder);
        SINGLE_ROLE_BROWSE_BUILDERS.remove(builder);
    }

    public static synchronized void built(Object builder, IStructureElement<?> element) {
        if (element != null && SINGLE_IDS.containsKey(builder))
            ELEMENT_SINGLE_IDS.put(element, SINGLE_IDS.get(builder));
        BiFunction<Object, ItemStack, Predicate<ItemStack>> rule = BUILDERS.get(builder);
        if (element == null) return;
        if (rule != null) ELEMENTS.put(element, rule);
        else ELEMENTS.remove(element);
        Predicate<ItemStack> structural = STRUCTURAL_BUILDERS.get(builder);
        if (structural != null) STRUCTURAL_ELEMENTS.put(element, structural);
        else STRUCTURAL_ELEMENTS.remove(element);
        if (element != null && REQUIREMENTS.containsKey(builder))
            ELEMENT_REQUIREMENTS.put(element, REQUIREMENTS.get(builder));
        if (element != null && DECLARED.containsKey(builder)) ELEMENT_DECLARED.put(element, DECLARED.get(builder));
        Predicate<ItemStack> cold = COLD_BUILDERS.get(builder);
        if (cold != null && element != null) COLD_ELEMENTS.put(element, cold);
        Object browse = NATIVE_BROWSE_BUILDERS.get(builder);
        if (element != null && browse != null) NATIVE_BROWSE_ELEMENTS.put(element, new NativeBrowse(builder, browse));
        Object single = SINGLE_ROLE_BROWSE_BUILDERS.get(builder);
        if (single != null) SINGLE_ROLE_BROWSE_ELEMENTS.put(element, new NativeBrowse(builder, single));
    }

    /** Only the plain native atLeast filter; arbitrary filters and wrappers do not inherit this marker. */
    static synchronized boolean nativeBrowseAtLeast(IStructureElement<?> element) {
        NativeBrowse browse = NATIVE_BROWSE_ELEMENTS.get(element);
        // A native leaf closes over the mutable builder; a later filter change invalidates older leaves too.
        if (browse == null || NATIVE_BROWSE_BUILDERS.get(browse.builder.get()) != browse.declaration) return false;
        Method factory = HologramDeclarationWrappers.factory(element.getClass());
        return factory != null && factory.getDeclaringClass() == gregtech.api.util.HatchElementBuilder.class
            && "build".equals(factory.getName());
    }

    /** Known native reference offers, not structural or auto-placement authority. */
    static synchronized Predicate<ItemStack> coldPreviewPredicate(IStructureElement<?> element) {
        return COLD_ELEMENTS.get(element);
    }

    static synchronized Predicate<ItemStack> structuralPredicate(IStructureElement<?> element, Object context,
        ItemStack trigger) {
        Predicate<ItemStack> declaration = STRUCTURAL_ELEMENTS.get(element);
        return declaration != null ? declaration : predicate(element, context, trigger);
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
