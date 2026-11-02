package com.miaokatze.gtmb.api;

import java.util.function.IntUnaryOperator;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

import com.miaokatze.gtmb.hologram.HologramMaterialDescriptor;

/** Pure planned material projections for exact block/item classes and a reviewed native pick implementation. */
public final class MaterialVariants {

    private MaterialVariants() {}

    /**
     * The projection reads only metadata and returns the complete physical item damage. It must not access worlds,
     * create tiles, perform I/O or mutate native state. Neither block nor item subclasses inherit this registration.
     * Registration grants no actual-world identity or ordinary-cell optimization proof: placed cells still require
     * their native material identity. The declaration is the class defining the native getDamageValue implementation;
     * matching uses method identity, independently of runtime MCP/SRG names, and never invokes it to probe support.
     */
    public static GTMBExtensions.Registration registerExact(String id, Class<? extends Block> blockType,
        Class<? extends Item> itemType, Class<? extends Block> nativePickDeclaration, IntUnaryOperator plannedDamage) {
        return GTMBExtensions.install(id, "material-variant", () -> {
            AutoCloseable handle = HologramMaterialDescriptor
                .registerExact(blockType, itemType, nativePickDeclaration, plannedDamage);
            return () -> Declarations.close(handle);
        });
    }

    /** Invalidates any derived validation or resource plan when a registration is installed or retired. */
    public static long generation() {
        return HologramMaterialDescriptor.generation();
    }
}
