// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.compat.cookingforblockheads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.nbt.NBTTagCompound;
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
import com.miaokatze.gtmb.compat.cookingforblockheads.CookingForBlockheadsCompatibility;
import com.miaokatze.miaokatzecore.client.preview.ModelRendererMesh;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Static closed-door geometry. Native TESRs, entity rendering and kitchen lifecycle hooks are never called. */
@SideOnly(Side.CLIENT)
public final class CookingForBlockheadsClientCompatibility implements ClientRendering.Adapter {

    private static CookingForBlockheadsClientCompatibility instance;
    private final Map<String, Model> models = new LinkedHashMap<>();
    private GTMBExtensions.Registration handle;
    private final ISimpleBlockRenderingHandler renderer = new StaticRenderer();
    private static final float[][] COLORS = { { 1, 1, 1 }, { .85f, .5f, .2f }, { .7f, .3f, .85f }, { .4f, .6f, .85f },
        { .9f, .9f, .2f }, { .5f, .8f, .1f }, { .95f, .5f, .65f }, { .3f, .3f, .3f }, { .6f, .6f, .6f },
        { .3f, .5f, .6f }, { .5f, .25f, .7f }, { .2f, .3f, .7f }, { .4f, .3f, .2f }, { .4f, .5f, .2f },
        { .6f, .2f, .2f }, { .1f, .1f, .1f } };

    private CookingForBlockheadsClientCompatibility() throws ReflectiveOperationException {
        add(
            "cookingtable",
            "CookingTable",
            "Backsplash HandleTop HandleBottom Top CraftingPlate",
            "Bottom Body DoorTop DoorBottom");
        add("cookingoven", "Oven", "OvenRight OvenBack OvenTop OvenBottom OvenFront OvenLeft OvenGrid OvenDoor", "");
        add(
            "counter",
            "Counter",
            "BackSplash TopWall DoorHandle",
            "Door BottomWall RightWall LeftWall BackWall BottomStick");
        add("countercorner", "CounterCorner", "Top BackSplash BackSplashTwo", "Base WallFront");
        add(
            "cabinet",
            "Cabinet",
            "DoorHandle",
            "Door BottomWall TopWall RightWall LeftWall BackWall BottomStick LeftStick RightStick");
        add(
            "cabinetcorner",
            "CabinetCorner",
            "",
            "Bottom Right BackLeft BackRight BottomStick RightInset Top TopTwo Left BottomStickTwo BottomTwo LeftInset");
        add(
            "fridge",
            "SmallFridge",
            "PlugThingy LeftSeal RightSeal BottomSeal TopSeal BottomHinge TopHinge DoorHandle BackRightFoot FrontRightFoot BackLeftFoot FrontLeftFoot",
            "Door RightWall LeftWall TopWall BottomWall BackWall");
        add(
            "fridge-large",
            "Fridge",
            "BottomHinge TopHinge DoorHandle LeftSeal BottomSeal RightSeal TopSeal",
            "Door RightWall LeftWall TopWall BottomWall BackWall");
        add(
            "sink",
            "Sink",
            "MetalMiddle FaucetBase FaucetEnd Handle TopFront TopLeft TopBack TopRight",
            "Backsplash Bottom WallBottom Door WallLeft WallRight WallBack WallFront");
        add("toolrack", "ToolRack", "hookLeft woodenBeam hookRight", "");
        add("toaster", "Toaster", "Buttery Why", "");
    }

    public static synchronized void initialize() {
        if (instance != null || !Loader.isModLoaded("cookingforblockheads")) return;
        try {
            CookingForBlockheadsClientCompatibility candidate = new CookingForBlockheadsClientCompatibility();
            candidate.handle = ClientRendering.register("gtmb:cfb/static-model", candidate);
            MinecraftForge.EVENT_BUS.register(candidate);
            instance = candidate;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("CFB 1.4.14 static model ABI changed", failure);
        }
    }

    public static synchronized void close() {
        if (instance == null) return;
        instance.handle.close();
        MinecraftForge.EVENT_BUS.unregister(instance);
        instance = null;
    }

    public boolean supports(Block block) {
        return CookingForBlockheadsCompatibility.blockName(block) != null;
    }

    public boolean allowsEmptyGeometry(Block block, int metadata, IBlockAccess access, int x, int y, int z) {
        return "fridge".equals(CookingForBlockheadsCompatibility.blockName(block))
            && access.getBlock(x, y - 1, z) == block;
    }

    public TileEntity createTile(Block block, int metadata, World world) {
        return new PreviewTile();
    }

    public void loadTile(TileEntity tile, NBTTagCompound payload) {
        PreviewTile preview = (PreviewTile) tile;
        preview.color = payload.getByte("Color");
        if (preview.color < 0 || preview.color > 15) throw new IllegalArgumentException("Invalid CFB preview dye");
        preview.flipped = payload.getBoolean("IsFlipped");
        preview.burning = payload.getInteger("BurnTime") > 0;
    }

    public ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler registered) {
        return renderer;
    }

    @SubscribeEvent
    public void stitch(TextureStitchEvent.Pre event) {
        if (event.map.getTextureType() != 0) return;
        for (Model model : models.values()) event.map.setTextureEntry(model.sprite.getIconName(), model.sprite);
    }

    private void add(String key, String type, String plain, String colored) throws ReflectiveOperationException {
        Object nativeModel = Class.forName("net.blay09.mods.cookingforblockheads.client.model.Model" + type)
            .getConstructor()
            .newInstance();
        models.put(key, new Model(nativeModel, type, plain, colored));
    }

    private static final class PreviewTile extends TileEntity {

        int color;
        boolean flipped;
        boolean burning;
    }

    private static final class Part {

        final String name;
        final ModelRenderer normal, flipped;
        final boolean colored;

        Part(Object model, String name, boolean colored) throws ReflectiveOperationException {
            this.name = name;
            this.colored = colored;
            normal = (ModelRenderer) model.getClass()
                .getField(name)
                .get(model);
            boolean reversible = model.getClass()
                .getSimpleName()
                .equals("ModelCounter")
                || model.getClass()
                    .getSimpleName()
                    .equals("ModelCabinet")
                || model.getClass()
                    .getSimpleName()
                    .equals("ModelFridge")
                || model.getClass()
                    .getSimpleName()
                    .equals("ModelSmallFridge");
            flipped = reversible && (name.equals("Door") || name.equals("DoorHandle")
                || name.equals("BottomHinge")
                || name.equals("TopHinge")) ? (ModelRenderer) model.getClass()
                    .getField(name + "Flipped")
                    .get(model) : normal;
        }
    }

    private static final class Model {

        final NativeModelTexture sprite;
        final List<Part> parts = new ArrayList<>();
        final ModelRenderer burningDoor;

        Model(Object model, String type, String plain, String colored) throws ReflectiveOperationException {
            sprite = new NativeModelTexture(
                "gtmb:cfb/Model" + type,
                new ResourceLocation(
                    "cookingforblockheads",
                    "textures/entity/Model" + type + (type.equals("ToolRack") ? "-texture" : "") + ".png"));
            burningDoor = type.equals("Oven") ? (ModelRenderer) model.getClass()
                .getField("OvenDoorBurning")
                .get(model) : null;
            for (String name : plain.split(" ")) if (!name.isEmpty()) parts.add(new Part(model, name, false));
            for (String name : colored.split(" ")) if (!name.isEmpty()) parts.add(new Part(model, name, true));
        }
    }

    private final class StaticRenderer implements ISimpleBlockRenderingHandler {

        public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderBlocks) {}

        public boolean shouldRender3DInInventory(int modelId) {
            return false;
        }

        public int getRenderId() {
            return -1;
        }

        public boolean renderWorldBlock(IBlockAccess access, int x, int y, int z, Block block, int id,
            RenderBlocks renderBlocks) {
            if (MinecraftForgeClient.getRenderPass() > 0) return false;
            String name = CookingForBlockheadsCompatibility.blockName(block);
            if (name == null) return false;
            if (name.equals("fridge") && access.getBlock(x, y - 1, z) == block) return true;
            Model model = models
                .get(name.equals("fridge") && access.getBlock(x, y + 1, z) == block ? "fridge-large" : name);
            TileEntity tile = access.getTileEntity(x, y, z);
            if (!(tile instanceof PreviewTile)) throw new IllegalArgumentException("CFB static preview tile required");
            PreviewTile preview = (PreviewTile) tile;
            int meta = access.getBlockMetadata(x, y, z);
            if (meta < 2 || meta > 5) throw new IllegalArgumentException("Invalid CFB preview facing");
            double angle = meta == 2 ? 0 : meta == 5 ? -Math.PI / 2 : meta == 3 ? Math.PI : Math.PI / 2;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            boolean rack = name.equals("toolrack");
            boolean toaster = name.equals("toaster");
            double scale = toaster ? .5 : 1;
            String below = CookingForBlockheadsCompatibility.blockName(access.getBlock(x, y - 1, z));
            double baseY = rack ? 0
                : toaster ? .065 - ("counter".equals(below) || "countercorner".equals(below) ? .0625 : 0) : 1.5;
            Tessellator tess = Tessellator.instance;
            for (Part part : model.parts) {
                ModelRenderer root = preview.flipped ? part.flipped : part.normal;
                if (preview.burning && part.name.equals("OvenDoor")) root = model.burningDoor;
                float[] color = part.colored ? COLORS[preview.color] : COLORS[0];
                tess.setColorOpaque_F(color[0], color[1], color[2]);
                // CounterCorner's native render method scales individual parts around their own pivot.
                double sx = name.equals("countercorner") && !part.name.equals("BackSplashTwo") ? .96875 : 1;
                double sz = name.equals("countercorner")
                    && (part.name.equals("Top") || part.name.equals("Base") || part.name.equals("BackSplashTwo"))
                        ? .96875
                        : 1;
                double px = root.offsetX + root.rotationPointX / 16.0;
                double pz = root.offsetZ + root.rotationPointZ / 16.0;
                ModelRendererMesh.emit(root, 1f / 16, (vx, vy, vz, u, v, nx, ny, nz) -> {
                    double mx = (px + (vx - px) * sx) * scale;
                    double mz = (pz + (vz - pz) * sz) * scale;
                    double my = vy * scale;
                    double normalX = nx / sx, normalY = ny, normalZ = nz / sz;
                    if (!rack) {
                        mx = -mx;
                        my = -my;
                        normalX = -normalX;
                        normalY = -normalY;
                    }
                    double normalLength = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
                    if (normalLength == 0) normalLength = 1;
                    tess.setNormal(
                        (float) ((normalX * cos + normalZ * sin) / normalLength),
                        (float) (normalY / normalLength),
                        (float) ((-normalX * sin + normalZ * cos) / normalLength));
                    tess.addVertexWithUV(
                        x + .5 + mx * cos + mz * sin,
                        y + baseY + my,
                        z + .5 - mx * sin + mz * cos,
                        model.sprite.u(u),
                        model.sprite.v(v));
                });
            }
            tess.setColorOpaque_F(1, 1, 1);
            return true;
        }
    }
}
