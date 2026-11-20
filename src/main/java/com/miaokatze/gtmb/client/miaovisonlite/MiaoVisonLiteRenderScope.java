// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.miaovisonlite;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.Tessellator;

import com.miaokatze.miaokatzecore.client.preview.PreviewRenderScope;

/** Isolates native callbacks and offscreen passes from the surrounding Minecraft render state. */
public final class MiaoVisonLiteRenderScope extends PreviewRenderScope.RenderState implements AutoCloseable {

    private MiaoVisonLiteRenderScope() {}

    public static MiaoVisonLiteRenderScope enter() {
        MiaoVisonLiteRenderScope scope = new MiaoVisonLiteRenderScope();
        try {
            scope.push();
            return scope;
        } catch (RuntimeException | Error failure) {
            try {
                scope.restore();
            } catch (RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    public static final class StateWitness extends PreviewRenderScope.StateWitness {
    }

    public static final class ClientTessState extends PreviewRenderScope.ClientTessState {

        public ClientTessState(Tessellator tessellator) throws ReflectiveOperationException {
            super(tessellator);
        }
    }

    public static void releaseNativeContext(Block block) {
        PreviewRenderScope.releaseNativeContext(block);
    }

    @Override
    public void close() {
        restore();
    }
}
