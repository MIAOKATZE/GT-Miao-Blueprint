// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.compat.botania;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.block.Block;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;

import com.miaokatze.gtmb.api.ClientRendering;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.client.compat.NativeModelTexture;
import com.miaokatze.miaokatzecore.client.preview.ModelRendererMesh;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** The claw's native TESR is a fixed model: no tile, inventory, ticks or world hooks are needed. */
@SideOnly(Side.CLIENT)
public final class BotaniaClientCompatibility implements ClientRendering.Adapter, ISimpleBlockRenderingHandler {

    private static BotaniaClientCompatibility instance;
    private final List<ModelRenderer> parts = new ArrayList<>();
    private final NativeModelTexture texture = new NativeModelTexture(
        "gtmb:botania/spawner-claw-model",
        new ResourceLocation("botania", "textures/model/spawnerClaw.png"));
    private GTMBExtensions.Registration handle;

    private BotaniaClientCompatibility() throws ReflectiveOperationException {
        Class<?> type = Class.forName("vazkii.botania.client.model.ModelSpawnerClaw");
        Object model = type.getConstructor()
            .newInstance();
        for (String name : new String[] { "Plate", "Claw1", "Claw2", "Claw3", "Claw4", "Claw5", "Claw6", "Claw7",
            "Claw8" }) {
            parts.add(readPart(model, name));
        }
    }

    private static ModelRenderer readPart(Object model, String name) throws ReflectiveOperationException {
        Field field = model.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return Objects.requireNonNull((ModelRenderer) field.get(model), "Missing native Botania model part: " + name);
    }

    public static synchronized void initialize() {
        if (instance != null || !Loader.isModLoaded("Botania")) return;
        try {
            BotaniaClientCompatibility candidate = new BotaniaClientCompatibility();
            candidate.handle = ClientRendering.register("gtmb:botania/spawner-claw-model", candidate);
            MinecraftForge.EVENT_BUS.register(candidate);
            instance = candidate;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Botania spawner claw model ABI changed", failure);
        }
    }

    public static synchronized void close() {
        if (instance == null) return;
        instance.handle.close();
        MinecraftForge.EVENT_BUS.unregister(instance);
        instance = null;
    }

    @Override
    public boolean supports(Block block) {
        return block != null && block.getClass()
            .getName()
            .equals("vazkii.botania.common.block.mana.BlockSpawnerClaw");
    }

    @Override
    public boolean requiresTile(Block block, int metadata) {
        return false;
    }

    @Override
    public TileEntity createTile(Block block, int metadata, World world) {
        return null;
    }

    @Override
    public ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler registered) {
        return this;
    }

    @SubscribeEvent
    public void stitch(TextureStitchEvent.Pre event) {
        if (event.map.getTextureType() == 0) event.map.setTextureEntry(texture.getIconName(), texture);
    }

    @Override
    public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderer) {}

    @Override
    public boolean shouldRender3DInInventory(int modelId) {
        return false;
    }

    @Override
    public int getRenderId() {
        return -1;
    }

    @Override
    public boolean renderWorldBlock(IBlockAccess world, int x, int y, int z, Block block, int modelId,
        RenderBlocks renderer) {
        if (MinecraftForgeClient.getRenderPass() > 0) return false;
        if (!supports(block)) return false;
        Tessellator tess = Tessellator.instance;
        tess.setColorOpaque_F(1, 1, 1);
        for (ModelRenderer part : parts) ModelRendererMesh.emit(part, 1f / 16, (vx, vy, vz, u, v, nx, ny, nz) -> {
            // RenderTileSpawnerClaw's translate(.5, 1.5, .5), then scale(1, -1, -1).
            tess.setNormal(nx, -ny, -nz);
            tess.addVertexWithUV(x + .5 + vx, y + 1.5 - vy, z + .5 - vz, texture.u(u), texture.v(v));
        });
        return true;
    }

}
