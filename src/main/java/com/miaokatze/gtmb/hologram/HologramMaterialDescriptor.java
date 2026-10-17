package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import gregtech.common.blocks.BlockCasingsAbstract;

/** Audited pick implementation identity, independent of MCP/SRG names. Never caches a native result. */
final class HologramMaterialDescriptor {

    private static final Method PICK = pickMethod();
    private static final ClassValue<Integer> KINDS = new ClassValue<Integer>() {

        @Override
        protected Integer computeValue(Class<?> type) {
            if (PICK == null) return 0;
            try {
                Class<?> declaration = type.getMethod(PICK.getName(), PICK.getParameterTypes())
                    .getDeclaringClass();
                if (declaration == Block.class) return 1;
                if (declaration == BlockCasingsAbstract.class) return 2;
            } catch (ReflectiveOperationException | SecurityException | LinkageError ignored) {}
            return 0;
        }
    };

    private HologramMaterialDescriptor() {}

    static int kind(Block block) {
        return block == null ? 0 : KINDS.get(block.getClass());
    }

    static int damage(Block block, int metadata) {
        // Block.getDamageValue delegates to the actual damageDropped override. It must still run on every call:
        // an unknown mod may vary that result. GT's base casing implementation returns metadata directly.
        return kind(block) == 2 ? metadata : block.damageDropped(metadata);
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
