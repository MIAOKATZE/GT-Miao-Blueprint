// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.compat.floodlights;

import java.lang.reflect.Field;
import java.util.Objects;

import net.minecraft.block.Block;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.api.ClientRendering;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.client.compat.NativeModelTexture;
import com.miaokatze.miaokatzecore.client.preview.ModelRendererMesh;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Audited 1.5.6 model/pose conversion. Never invokes a TESR, native NBT reader, inventory or tick. */
public final class FloodLightsClientCompatibility implements ClientRendering.Adapter, ISimpleBlockRenderingHandler {

    private static final String BASE = "de.keridos.floodlights.";
    private static FloodLightsClientCompatibility instance;
    private GTMBExtensions.Registration handle;
    private final ModelRenderer small, square;
    private final NativeModelTexture[][] textures = new NativeModelTexture[3][2];
    private final float[][] colors = new float[3][];

    private FloodLightsClientCompatibility() throws ReflectiveOperationException {
        small = model("Small");
        square = model("Square");
        String[] images = { "growLight", "fluorescentSmallLight", "fluorescentSquareLight" };
        for (int type = 0; type < images.length; type++) for (int active = 0; active < 2; active++) {
            String image = images[type] + (active == 0 ? "_off" : "_on");
            textures[type][active] = new NativeModelTexture(
                "gtmb:floodlights/" + image,
                new ResourceLocation("floodlights", "textures/blocks/" + image + ".png"));
        }
        for (int channel = 0; channel < 3; channel++)
            colors[channel] = ((float[]) Class.forName(BASE + "util.RenderUtil")
                .getField(new String[] { "r", "g", "b" }[channel])
                .get(null)).clone();
    }

    private static ModelRenderer model(String type) throws ReflectiveOperationException {
        Object nativeModel = Class.forName(BASE + "client.render.model.TileEntity" + type + "FluorescentLightModel")
            .getConstructor()
            .newInstance();
        return Objects.requireNonNull(
            (ModelRenderer) nativeModel.getClass()
                .getField("shape1")
                .get(nativeModel),
            "Missing FloodLights native model root");
    }

    public static synchronized void initialize() {
        if (instance != null || !Loader.isModLoaded("FloodLights")) return;
        try {
            FloodLightsClientCompatibility candidate = new FloodLightsClientCompatibility();
            candidate.handle = ClientRendering.register("gtmb:floodlights/static-models", candidate);
            MinecraftForge.EVENT_BUS.register(candidate);
            instance = candidate;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FloodLights 1.5.6 visual ABI changed", failure);
        }
    }

    public static synchronized void close() {
        if (instance == null) return;
        instance.handle.close();
        MinecraftForge.EVENT_BUS.unregister(instance);
        instance = null;
    }

    public boolean supports(Block block) {
        return block != null && (block.getClass()
            .getName()
            .equals(BASE + "block.BlockGrowLight")
            || block.getClass()
                .getName()
                .equals(BASE + "block.BlockSmallElectricFloodlight"));
    }

    /** Keep the actual detached tile type: native SmallFloodlight bounds cast it before reading orientation. */
    public void loadTile(TileEntity tile, NBTTagCompound payload) {
        try {
            // Match the native reader's constructor defaults for older payloads with absent visual fields.
            int direction = payload.hasKey("teDirection") ? payload.getByte("teDirection")
                : ((ForgeDirection) field("TileEntityFL", "orientation").get(tile)).ordinal();
            int color = payload.hasKey("color") ? payload.getInteger("color")
                : field("TileEntityFL", "color").getInt(tile);
            if (direction < 0 || direction > 5 || color < 0 || color > 16)
                throw new IllegalArgumentException("Invalid FloodLights visual direction/color");
            field("TileEntityFL", "orientation").set(tile, ForgeDirection.getOrientation(direction));
            field("TileEntityFL", "color").setInt(tile, color);
            field("TileEntityMetaFloodlight", "wasActive").setBoolean(tile, payload.getBoolean("wasActive"));
            if (tile.getClass()
                .getName()
                .equals(BASE + "tileentity.TileEntitySmallFloodlight"))
                field("TileEntitySmallFloodlight", "rotationState")
                    .setBoolean(tile, payload.getBoolean("teRotationState"));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("FloodLights visual fields unavailable", failure);
        }
    }

    private static Field field(String type, String name) throws ReflectiveOperationException {
        Field field = Class.forName(BASE + "tileentity." + type)
            .getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler registered) {
        return this;
    }

    @SubscribeEvent
    public void stitch(TextureStitchEvent.Pre event) {
        if (event.map.getTextureType() == 0) for (NativeModelTexture[] pair : textures)
            for (NativeModelTexture texture : pair) event.map.setTextureEntry(texture.getIconName(), texture);
    }

    public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderer) {}

    public boolean shouldRender3DInInventory(int modelId) {
        return false;
    }

    public int getRenderId() {
        return -1;
    }

    public boolean renderWorldBlock(IBlockAccess access, int x, int y, int z, Block block, int modelId,
        RenderBlocks renderer) {
        if (MinecraftForgeClient.getRenderPass() > 0 || !supports(block)) return false;
        TileEntity tile = access.getTileEntity(x, y, z);
        try {
            boolean grow = block.getClass()
                .getName()
                .equals(BASE + "block.BlockGrowLight");
            int meta = access.getBlockMetadata(x, y, z);
            if (!grow && meta != 0 && meta != 1)
                throw new IllegalArgumentException("Unknown FloodLights model metadata");
            boolean active = field("TileEntityMetaFloodlight", "wasActive").getBoolean(tile);
            ForgeDirection direction = (ForgeDirection) field("TileEntityFL", "orientation").get(tile);
            boolean rotated = !grow && field("TileEntitySmallFloodlight", "rotationState").getBoolean(tile);
            int color = field("TileEntityFL", "color").getInt(tile);
            int type = grow ? 0 : meta + 1;
            ModelRenderer model = !grow && meta == 0 ? small : square;
            emit(model, textures[type][active ? 1 : 0], direction, grow, rotated, color, x, y, z);
            return true;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("FloodLights visual fields unavailable", failure);
        }
    }

    void emit(ModelRenderer model, NativeModelTexture texture, ForgeDirection direction, boolean grow, boolean rotated,
        int color, int x, int y, int z) {
        if (texture.getIconWidth() == 0 || texture.getIconHeight() == 0)
            throw new IllegalStateException("FloodLights native model texture has not loaded");
        float rx = model.rotateAngleX, ry = model.rotateAngleY, rz = model.rotateAngleZ;
        Tessellator tess = Tessellator.instance;
        try {
            pose(model, direction, grow, rotated);
            tess.setColorOpaque_F(
                color == 16 ? 1 : Math.min(1, colors[0][color] * 1.21342f),
                color == 16 ? 1 : Math.min(1, colors[1][color] * 1.21342f),
                color == 16 ? 1 : Math.min(1, colors[2][color] * 1.21342f));
            ModelRendererMesh.emit(model, 1f / 16, (vx, vy, vz, u, v, nx, ny, nz) -> {
                // Native TESRs translate(.5,1.5,.5), then rotate 180 degrees about Z.
                tess.setNormal(-nx, -ny, nz);
                tess.addVertexWithUV(x + .5 - vx, y + 1.5 - vy, z + .5 + vz, texture.u(u), texture.v(v));
            });
        } finally {
            model.rotateAngleX = rx;
            model.rotateAngleY = ry;
            model.rotateAngleZ = rz;
            tess.setColorOpaque_F(1, 1, 1);
        }
    }

    static void pose(ModelRenderer model, ForgeDirection direction, boolean grow, boolean rotated) {
        float half = (float) (Math.PI / 2);
        model.rotateAngleX = model.rotateAngleY = model.rotateAngleZ = 0;
        switch (direction) {
            case DOWN:
                model.rotateAngleX = (float) Math.PI;
                break;
            case UP:
                break;
            case WEST:
                model.rotateAngleZ = half;
                break;
            case EAST:
                model.rotateAngleZ = -half;
                break;
            case SOUTH:
                model.rotateAngleX = -half;
                break;
            case NORTH:
                model.rotateAngleX = half;
                break;
            default:
                throw new IllegalArgumentException("Invalid FloodLights orientation");
        }
        if (!grow) {
            if (direction == ForgeDirection.NORTH || direction == ForgeDirection.SOUTH)
                model.rotateAngleZ = rotated ? half : 0;
            else model.rotateAngleY = rotated ? 0 : half;
        }
    }
}
