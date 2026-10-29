package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;

/** Chisel 2's exact static CTM block family; subclasses with different geometry keep their own boundary. */
final class HologramChiselNativeRender implements HologramNativeRenderRegistry.Adapter {

    static final HologramChiselNativeRender INSTANCE = new HologramChiselNativeRender();

    private HologramChiselNativeRender() {}

    public boolean supports(Block block) {
        return block != null && block.getClass()
            .getName()
            .equals("team.chisel.block.BlockCarvable");
    }

    static boolean opaqueCube(Block block) {
        return selected(block) && block.isOpaqueCube()
            && block.renderAsNormalBlock()
            && block.getRenderBlockPass() == 0;
    }

    static boolean selected(Block block) {
        return INSTANCE.supports(block) && HologramNativeRenderRegistry.adapter(block) == INSTANCE;
    }

    static boolean opaqueCube(HologramState.Cell cell, boolean current) {
        current = current || cell.anchor || cell.status.equals("protected") && cell.actualStack != null;
        return opaqueCube(cell.block(current));
    }

    public boolean requiresTile(Block block, int metadata) {
        return false;
    }

    public ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler registered) {
        if (registered == null || !registered.getClass()
            .getName()
            .equals("team.chisel.client.render.RendererCTM"))
            throw new IllegalStateException("Chisel static CTM renderer unavailable");
        // This handler has only a final renderId; renderWorldBlock uses the supplied isolated block access.
        // Constructing RendererCTM would allocate/change Chisel's global render id, so retain its registered owner.
        return registered;
    }
}
