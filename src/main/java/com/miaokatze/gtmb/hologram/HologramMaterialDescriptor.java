package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.world.World;

import bartworks.system.material.BWItemMetaGeneratedBlock;
import bartworks.system.material.BWMetaGeneratedBlocks;
import bartworks.system.material.BWMetaGeneratedBlocksCasing;
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
                if (type == BWMetaGeneratedBlocksCasing.class && declaration == BWMetaGeneratedBlocks.class) return 3;
            } catch (ReflectiveOperationException | SecurityException | LinkageError ignored) {}
            return 0;
        }
    };

    private HologramMaterialDescriptor() {}

    static int kind(Block block) {
        if (block == null) return 0;
        int kind = KINDS.get(block.getClass());
        if (kind != 3) return kind;
        Item item = Item.getItemFromBlock(block);
        return item != null && item.getClass() == BWItemMetaGeneratedBlock.class ? 3 : 0;
    }

    static int damage(Block block, int metadata) {
        int kind = kind(block);
        if (kind == 3) {
            // BW's native item writes the same positive short to world metadata and TE.mMetaData.
            // A planned cell has no TE yet. Actual-world validation still calls the native TE-aware pick.
            if (metadata < 1 || metadata > Short.MAX_VALUE)
                throw new IllegalArgumentException("BW casing material must be a positive short");
            return metadata;
        }
        // Block.getDamageValue delegates to the actual damageDropped override. It must still run on every call:
        // an unknown mod may vary that result. GT's base casing implementation returns metadata directly.
        return kind == 2 ? metadata : block.damageDropped(metadata);
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
