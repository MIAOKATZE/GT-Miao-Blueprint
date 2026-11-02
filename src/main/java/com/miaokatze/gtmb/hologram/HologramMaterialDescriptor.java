package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** Audited pick implementation identity, independent of MCP/SRG names. Never caches a native result. */
public final class HologramMaterialDescriptor {

    private static final Method PICK = pickMethod();
    private static final ClassValue<Class<?>> PICK_DECLARATIONS = new ClassValue<Class<?>>() {

        @Override
        protected Class<?> computeValue(Class<?> type) {
            if (PICK == null) return Void.class;
            try {
                return type.getMethod(PICK.getName(), PICK.getParameterTypes())
                    .getDeclaringClass();
            } catch (ReflectiveOperationException | SecurityException | LinkageError ignored) {}
            return Void.class;
        }
    };
    private static final Map<Class<?>, IntUnaryOperator> NATIVE_DAMAGE = new HashMap<>();
    private static final Map<Class<?>, Map<Class<?>, ExactRule>> EXACT = new HashMap<>();
    private static long generation;

    static {
        HologramMaterialDescriptorAdapters.register();
    }

    private HologramMaterialDescriptor() {}

    /** Internal bridge for the scoped public API. Matching never calls a pick implementation or a tile factory. */
    public static AutoCloseable registerExact(Class<? extends Block> blockType, Class<? extends Item> itemType,
        Class<? extends Block> nativePickDeclaration, IntUnaryOperator plannedDamage) {
        return registerExact(blockType, itemType, nativePickDeclaration, plannedDamage, null);
    }

    /** Actual readers belong only to built-in reviewed material types, never to the public planned projection. */
    static AutoCloseable registerExact(Class<? extends Block> blockType, Class<? extends Item> itemType,
        Class<? extends Block> nativePickDeclaration, IntUnaryOperator plannedDamage,
        ToIntFunction<TileEntity> actualDamage) {
        if (blockType == null || itemType == null || nativePickDeclaration == null || plannedDamage == null)
            throw new IllegalArgumentException("Complete material identity and projection required");
        if (PICK_DECLARATIONS.get(blockType) != nativePickDeclaration)
            throw new IllegalArgumentException("Native pick declaration does not match the exact block type");
        ExactRule rule = new ExactRule(nativePickDeclaration, plannedDamage, actualDamage);
        synchronized (HologramMaterialDescriptor.class) {
            Map<Class<?>, ExactRule> items = EXACT.computeIfAbsent(blockType, ignored -> new HashMap<>());
            if (items.putIfAbsent(itemType, rule) != null)
                throw new IllegalArgumentException("Material identity already registered");
            generation++;
        }
        return () -> {
            synchronized (HologramMaterialDescriptor.class) {
                Map<Class<?>, ExactRule> items = EXACT.get(blockType);
                if (items != null && items.remove(itemType, rule)) {
                    if (items.isEmpty()) EXACT.remove(blockType);
                    generation++;
                }
            }
        };
    }

    /** Only built-in reviewed metadata-only native implementations may grant the ordinary-cell proof. */
    static synchronized void registerNative(Class<? extends Block> declaration, IntUnaryOperator damage) {
        if (PICK_DECLARATIONS.get(declaration) != declaration || damage == null)
            throw new IllegalArgumentException("Audited native pick declaration required");
        if (NATIVE_DAMAGE.putIfAbsent(declaration, damage) != null)
            throw new IllegalArgumentException("Native material implementation already registered");
        generation++;
    }

    /** Include in caches whose validation depends on registered planned material projections. */
    public static synchronized long generation() {
        return generation;
    }

    static synchronized boolean hasExact(Block block) {
        return block != null && EXACT.containsKey(block.getClass());
    }

    static synchronized int kind(Block block) {
        if (block == null) return 0;
        if (exactRule(block) != null) return 3;
        Class<?> declaration = PICK_DECLARATIONS.get(block.getClass());
        if (declaration == Block.class) return 1;
        return NATIVE_DAMAGE.containsKey(declaration) ? 2 : 0;
    }

    static int damage(Block block, int metadata) {
        ExactRule exact;
        IntUnaryOperator nativeDamage;
        Class<?> declaration = PICK_DECLARATIONS.get(block.getClass());
        synchronized (HologramMaterialDescriptor.class) {
            exact = exactRule(block);
            nativeDamage = NATIVE_DAMAGE.get(declaration);
        }
        if (exact != null) return exact.plannedDamage.applyAsInt(metadata);
        if (nativeDamage != null) return nativeDamage.applyAsInt(metadata);
        // Unknown damageDropped overrides still run every time; only the inherited native pick is proved.
        if (declaration == Block.class) return block.damageDropped(metadata);
        throw new IllegalStateException("No material descriptor for native pick implementation");
    }

    /** A source-backed actual value for read-only snapshots, or null when no audited native reader is available. */
    static Integer actualDamage(Block block, int metadata, TileEntity tile) {
        if (block == null) return null;
        ExactRule exact;
        IntUnaryOperator nativeDamage;
        synchronized (HologramMaterialDescriptor.class) {
            exact = exactRule(block);
            nativeDamage = NATIVE_DAMAGE.get(PICK_DECLARATIONS.get(block.getClass()));
        }
        if (exact != null) return exact.actualDamage == null ? null : exact.actualDamage.applyAsInt(tile);
        return nativeDamage == null ? null : nativeDamage.applyAsInt(metadata);
    }

    private static ExactRule exactRule(Block block) {
        Map<Class<?>, ExactRule> items = EXACT.get(block.getClass());
        if (items == null) return null;
        Item item = Item.getItemFromBlock(block);
        ExactRule rule = item == null ? null : items.get(item.getClass());
        return rule != null && PICK_DECLARATIONS.get(block.getClass()) == rule.nativePickDeclaration ? rule : null;
    }

    private static final class ExactRule {

        final Class<?> nativePickDeclaration;
        final IntUnaryOperator plannedDamage;
        final ToIntFunction<TileEntity> actualDamage;

        ExactRule(Class<?> nativePickDeclaration, IntUnaryOperator plannedDamage,
            ToIntFunction<TileEntity> actualDamage) {
            this.nativePickDeclaration = nativePickDeclaration;
            this.plannedDamage = plannedDamage;
            this.actualDamage = actualDamage;
        }
    }

    private static Method pickMethod() {
        Method found = null;
        for (Method method : Block.class.getDeclaredMethods()) {
            Class<?>[] arguments = method.getParameterTypes();
            if (method.getReturnType() != int.class || arguments.length != 4
                || arguments[0] != World.class
                || arguments[1] != int.class
                || arguments[2] != int.class
                || arguments[3] != int.class) continue;
            if (found != null) return null; // An altered native API cannot acquire a proof through guessing.
            found = method;
        }
        return found;
    }
}
