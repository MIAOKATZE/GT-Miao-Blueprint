package com.miaokatze.gtmb.client.hologram;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.Tessellator;

import com.miaokatze.miaokatzecore.client.preview.PreviewRenderScope;

/** Internal compatibility names retained while Core owns the audited preview state implementation. */
final class HologramCutRawPreviewScope {

    private HologramCutRawPreviewScope() {}

    static final class RenderState extends PreviewRenderScope.RenderState {
    }

    static final class StateWitness extends PreviewRenderScope.StateWitness {
    }

    static final class ClientTessState extends PreviewRenderScope.ClientTessState {

        ClientTessState(Tessellator tessellator) throws ReflectiveOperationException {
            super(tessellator);
        }
    }

    static void releaseNativeContext(Block block) {
        PreviewRenderScope.releaseNativeContext(block);
    }
}
