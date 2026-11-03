package com.miaokatze.gtmb.client.compat.carpenters;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.client.hologram.HologramNativeRenderRegistry;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Reviewed CB static ISBRH lifecycle; no TESR, global renderer registration or render-ID allocation. */
@SideOnly(Side.CLIENT)
public final class CarpentersClientCompatibility implements HologramNativeRenderRegistry.Adapter {

    private CarpentersClientCompatibility() {}

    public static GTMBExtensions.Registration install() {
        CarpentersClientCompatibility adapter = new CarpentersClientCompatibility();
        return GTMBExtensions.install("gtmb:carpenters/client-render", "native-render-builtin", () -> {
            HologramNativeRenderRegistry.registerBuiltin(adapter);
            return () -> HologramNativeRenderRegistry.unregisterBuiltin(adapter);
        });
    }

    public boolean supports(Block block) {
        return block != null && block.getClass()
            .getName()
            .startsWith("com.carpentersblocks.block.");
    }

    public boolean detachedRead(Block block) {
        // TEBase's reader can query/update lighting. The half-decoded tile must not enter the snapshot world.
        return true;
    }

    public boolean doubleSided(Block block) {
        return block.getClass()
            .getName()
            .equals("com.carpentersblocks.block.BlockCarpentersSlope");
    }

    public ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler registered) {
        if (!reviewedRenderer(registered)) return registered;
        try {
            // Native CB handlers retain mutable world/TE fields. Use a disposable handler, preserving the
            // already registered render ID instead of registering or reconstructing any global renderer state.
            return registered.getClass()
                .asSubclass(ISimpleBlockRenderingHandler.class)
                .getConstructor()
                .newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create detached Carpenter renderer", failure);
        }
    }

    public HologramNativeRenderRegistry.RenderScope renderScope(Block block, TileEntity tile,
        ISimpleBlockRenderingHandler renderer) {
        return reviewedRenderer(renderer) ? HologramNativeRenderRegistry.metadataOverride(tile) : null;
    }

    private static boolean reviewedRenderer(ISimpleBlockRenderingHandler renderer) {
        return renderer != null && renderer.getClass()
            .getName()
            .startsWith("com.carpentersblocks.renderer.");
    }
}
