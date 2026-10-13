package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.render.IIconTexture;

/** Opt-in GL cache witness using registered GT hatches and the installed native casing atlas. */
public final class HologramHatchMaterialVerification {

    private static final int SIZE = 256, FRONT = 3, PAGE = 1, CASING_A = 48;
    private static final double YAW = .6, PITCH = .5;

    private HologramHatchMaterialVerification() {}

    /** Invoke after the production fixture screenshots; these isolated renders never enter its source count. */
    public static NBTTagCompound verify(File output) throws IOException {
        require(Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"), "hatch GL witness is opt-in");
        Minecraft mc = Minecraft.getMinecraft();
        require(mc.renderViewEntity != null, "world group witness requires the existing real render camera");
        require(SIZE <= mc.displayWidth && SIZE <= mc.displayHeight, "framebuffer must fit the hatch witness");
        require(output.isDirectory() || output.mkdirs(), "hatch GL evidence directory must exist");
        int casingB = differentCasing();
        ItemStack stack = activeHatch(mc.renderViewEntity);
        int originalMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int originalLayer = HologramRenderer.projectionLayer;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(0xffffffff);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        NBTTagCompound receipt = new NBTTagCompound();
        NBTTagList sequences = new NBTTagList();
        try {
            HologramRenderer.projectionLayer = Integer.MAX_VALUE;
            for (boolean current : new boolean[] { false, true }) {
                int[][] preview = new int[3][], world = new int[3][];
                String[] signatures = new String[3];
                for (int step = 0; step < 3; step++) {
                    int casing = step == 1 ? casingB : CASING_A;
                    HologramState state = fixture(stack, casing, false, mc.renderViewEntity);
                    HologramTextures.begin(state);
                    HologramTextures.Material material = HologramTextures.material(state.cells.get(0), current);
                    verifyBackground(material, casing);
                    signatures[step] = signature(material.sides[1]);
                    String name = (current ? "current" : "target") + "-" + step + "-casing-" + PAGE + "-" + casing;
                    preview[step] = drawPreview(state, current);
                    save(output, "hatch-preview-" + name + ".png", preview[step]);
                    world[step] = drawWorld(worldView(state, current), mc.renderViewEntity);
                    save(output, "hatch-world-" + name + ".png", world[step]);
                    NBTTagCompound row = new NBTTagCompound();
                    row.setString("view", current ? "current" : "target");
                    row.setInteger("step", step);
                    row.setInteger("texturePage", PAGE);
                    row.setInteger("textureIndex", casing);
                    row.setTag("capturedNativeDisplay", state.cells.get(0).targetDisplay.copy());
                    row.setTag("nativeAtlasFaces", nativeFaces(material));
                    sequences.appendTag(row);
                }
                require(
                    !signatures[0].equals(signatures[1]) && signatures[0].equals(signatures[2]),
                    "installed native casing atlas must change A to B and restore A");
                int previewChanged = changed(preview[0], preview[1]);
                int worldChanged = changed(world[0], world[1]);
                require(
                    previewChanged >= 64,
                    "preview GL pixels did not change with the hatch casing: " + previewChanged);
                require(
                    worldChanged >= 64,
                    "world group GL pixels did not change with the hatch casing: " + worldChanged);
                require(Arrays.equals(preview[0], preview[2]), "preview A pixels did not return after B");
                require(Arrays.equals(world[0], world[2]), "world group A pixels did not return after B");
                HologramState inactive = fixture(stack, CASING_A, false, mc.renderViewEntity);
                HologramState active = fixture(stack, CASING_A, true, mc.renderViewEntity);
                HologramTextures.begin(inactive);
                HologramTextures.Material off = HologramTextures.material(inactive.cells.get(0), current);
                String offFront = signature(off.sides[FRONT]);
                String offBack = signature(off.sides[FRONT ^ 1]);
                HologramTextures.begin(active);
                HologramTextures.Material on = HologramTextures.material(active.cells.get(0), current);
                require(
                    !offFront.equals(signature(on.sides[FRONT])),
                    "real native hatch active front must change its atlas layers");
                require(
                    offBack.equals(signature(on.sides[FRONT ^ 1])),
                    "active hatch must retain the casing background at the rear");
                int[] activePreview = drawPreview(active, current);
                int[] inactivePreview = drawPreview(inactive, current);
                int[] activeWorld = drawWorld(worldView(active, current), mc.renderViewEntity);
                int[] inactiveWorld = drawWorld(worldView(inactive, current), mc.renderViewEntity);
                int previewOverlayChanged = changed(activePreview, inactivePreview);
                int worldOverlayChanged = changed(activeWorld, inactiveWorld);
                require(previewOverlayChanged >= 12, "active native hatch overlay was not rasterized in the preview");
                require(worldOverlayChanged >= 12, "active native hatch overlay was not rasterized in the world group");
                save(output, "hatch-preview-active-" + (current ? "current" : "target") + ".png", activePreview);
                save(output, "hatch-world-active-" + (current ? "current" : "target") + ".png", activeWorld);
                receipt.setInteger((current ? "current" : "target") + "PreviewCasingChangedPixels", previewChanged);
                receipt.setInteger((current ? "current" : "target") + "WorldCasingChangedPixels", worldChanged);
                receipt.setInteger(
                    (current ? "current" : "target") + "PreviewOverlayChangedPixels",
                    previewOverlayChanged);
                receipt.setInteger((current ? "current" : "target") + "WorldOverlayChangedPixels", worldOverlayChanged);
            }
            receipt.setInteger("metaTileId", stack.getItemDamage());
            receipt.setInteger("casingA", CASING_A);
            receipt.setInteger("casingB", casingB);
            receipt.setString(
                "machine",
                GregTechAPI.METATILEENTITIES[stack.getItemDamage()].getClass()
                    .getName());
            receipt.setBoolean("previewAndWorldABAExactPixels", true);
            receipt.setTag("nativeSequences", sequences);
            return receipt;
        } finally {
            HologramRenderer.projectionLayer = originalLayer;
            HologramRenderer.release();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(originalMode);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }

    private static int differentCasing() {
        ITexture[] page = Textures.BlockIcons.casingTexturePages[PAGE];
        require(
            page != null && CASING_A < page.length && page[CASING_A] instanceof IIconTexture,
            "installed native chemical casing 1:48 must be a real atlas texture");
        IIcon a = ((IIconTexture) page[CASING_A]).getIcon(1, null);
        if (page.length > 19 && usableDifferent(a, page[19])) return 19;
        for (int i = 1; i < page.length; i++) if (usableDifferent(a, page[i])) return i;
        throw new IllegalStateException("installed native casing page has no distinct valid B material");
    }

    private static boolean usableDifferent(IIcon a, ITexture texture) {
        if (!(texture instanceof IIconTexture)) return false;
        IIcon b = ((IIconTexture) texture).getIcon(1, null);
        return valid(a) && valid(b) && !uv(a).equals(uv(b));
    }

    private static ItemStack activeHatch(Entity camera) {
        for (IMetaTileEntity machine : GregTechAPI.METATILEENTITIES) {
            if (!(machine instanceof MTEHatch)) continue;
            ItemStack stack = machine.getStackForm(1);
            if (stack == null || !(stack.getItem() instanceof ItemMachines)) continue;
            try {
                HologramState inactive = fixture(stack, CASING_A, false, camera);
                HologramState active = fixture(stack, CASING_A, true, camera);
                HologramTextures.begin(inactive);
                HologramTextures.Material off = HologramTextures.material(inactive.cells.get(0), false);
                String offFront = signature(off.sides[FRONT]);
                HologramTextures.begin(active);
                HologramTextures.Material on = HologramTextures.material(active.cells.get(0), false);
                if (!off.fallback && !on.fallback && !offFront.equals(signature(on.sides[FRONT]))) return stack;
            } catch (RuntimeException unsupportedHatch) {
                // The witness chooses an installed hatch with native active/inactive atlas callbacks.
            }
        }
        throw new IllegalStateException(
            "no registered native hatch exposes distinct active/inactive atlas front layers");
    }

    private static HologramState fixture(ItemStack stack, int casing, boolean active, Entity camera) {
        Block block = Block.getBlockFromItem(stack.getItem());
        require(block != null, "registered hatch item must identify its native block");
        int x = (int) Math.floor(camera.posX), y = (int) Math.floor(camera.posY), z = (int) Math.floor(camera.posZ);
        NBTTagCompound display = nativeDisplay(stack, casing, active, x, y, z);
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", Block.blockRegistry.getNameForObject(block));
        row.setString("wantId", Block.blockRegistry.getNameForObject(block));
        row.setInteger("x", x);
        row.setInteger("y", y);
        row.setInteger("z", z);
        row.setInteger("actualFacing", FRONT);
        row.setInteger("targetFacing", FRONT);
        row.setTag("actualDisplay", display.copy());
        row.setTag("targetDisplay", display.copy());
        NBTTagCompound item = new NBTTagCompound();
        stack.writeToNBT(item);
        row.setTag("actualStack", item.copy());
        row.setTag("targetStack", item.copy());
        row.setString("status", "pending");
        NBTTagList cells = new NBTTagList();
        cells.appendTag(row);
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "native-hatch-material-gl-witness");
        data.setInteger("x", x);
        data.setInteger("y", y);
        data.setInteger("z", z);
        data.setBoolean("virtual", true);
        data.setTag("cells", cells);
        return new HologramState(data);
    }

    private static NBTTagCompound nativeDisplay(ItemStack stack, int casing, boolean active, int x, int y, int z) {
        BaseMetaTileEntity tile = new BaseMetaTileEntity();
        tile.setWorldObj(new HologramRenderWorld(new HologramBlockAccess(new HologramState(new NBTTagCompound()))));
        tile.xCoord = x;
        tile.yCoord = y;
        tile.zCoord = z;
        tile.setInitialValuesAsNBT(null, (short) stack.getItemDamage());
        require(
            tile.getMetaTileEntity() instanceof MTEHatch,
            "registered native hatch factory must construct a real hatch");
        ((MTEHatch) tile.getMetaTileEntity()).updateTexture(PAGE << 7 | casing);
        tile.setFrontFacing(ForgeDirection.getOrientation(FRONT));
        tile.setActive(active);
        try {
            Method capture = Class.forName("com.miaokatze.gtmb.hologram.HologramDisplaySnapshot")
                .getDeclaredMethod("capture", TileEntity.class);
            capture.setAccessible(true);
            NBTTagCompound display = (NBTTagCompound) capture.invoke(null, tile);
            require(
                display.getInteger("texturePage") == PAGE && display.getInteger("textureIndex") == casing
                    && display.getInteger("facing") == FRONT
                    && display.getBoolean("active") == active,
                "public native hatch casing/facing/active APIs must survive actual display capture");
            return display;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("actual native hatch display capture failed", failure);
        }
    }

    private static HologramState worldView(HologramState state, boolean current) {
        if (!current) return state;
        NBTTagCompound data = (NBTTagCompound) state.data.copy();
        NBTTagCompound row = data.getTagList("cells", 10)
            .getCompoundTagAt(0);
        row.setTag(
            "targetDisplay",
            row.getCompoundTag("actualDisplay")
                .copy());
        row.setTag(
            "targetStack",
            row.getCompoundTag("actualStack")
                .copy());
        row.setInteger("targetFacing", row.getInteger("actualFacing"));
        return new HologramState(data);
    }

    private static void verifyBackground(HologramTextures.Material material, int casing) {
        require(material.gregTech && !material.fallback, "native hatch texture must resolve without fallback");
        ITexture background = Textures.BlockIcons.casingTexturePages[PAGE][casing];
        require(background instanceof IIconTexture, "native casing background must expose its installed atlas icon");
        for (int side = 0; side < 6; side++) {
            IIcon expected = ((IIconTexture) background).getIcon(side, null);
            require(
                !material.sides[side].isEmpty() && valid(expected)
                    && uv(expected).equals(uv(material.sides[side].get(0).icon)),
                "native hatch side " + side + " lost the actual casing atlas background");
        }
    }

    private static int[] drawPreview(HologramState state, boolean current) {
        setup();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, SIZE, SIZE, 0, -1000, 1000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        HologramRenderer
            .preview(state, 0, 0, SIZE, SIZE, YAW, PITCH, 1.7, Integer.MAX_VALUE, current ? 1 : 0, -1, -1, -1, 0);
        return pixels();
    }

    private static int[] drawWorld(HologramState state, Entity camera) {
        setup();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(-1.5, 1.5, -1.5, 1.5, -10, 10);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glRotated(Math.toDegrees(PITCH), 1, 0, 0);
        GL11.glRotated(-Math.toDegrees(YAW), 0, 1, 0);
        HologramState.Cell cell = state.cells.get(0);
        GL11.glTranslated(-(cell.x + .5 - camera.posX), -(cell.y + .5 - camera.posY), -(cell.z + .5 - camera.posZ));
        HologramRenderer.world(state, 1);
        for (int attempt = 1; attempt < 12 && HologramRenderer.lastWorldGeometry == 0; attempt++)
            HologramRenderer.world(state, 1);
        require(
            HologramRenderer.lastWorldGeometry > 0 && HologramRenderer.lastWorldGTGeometry == 1
                && HologramRenderer.lastWorldFallback == 0,
            "actual world group must compile one native GT material without fallback");
        return pixels();
    }

    private static void setup() {
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, 0, SIZE, SIZE);
        GL11.glDepthMask(true);
        GL11.glClearDepth(1);
        GL11.glClearColor(.04f, .08f, .1f, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
    }

    private static int[] pixels() {
        ByteBuffer rgba = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        int[] rgb = new int[SIZE * SIZE];
        for (int i = 0; i < rgb.length; i++)
            rgb[i] = (rgba.get(i * 4) & 255) << 16 | (rgba.get(i * 4 + 1) & 255) << 8 | (rgba.get(i * 4 + 2) & 255);
        return rgb;
    }

    private static int changed(int[] a, int[] b) {
        int changed = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) changed++;
        return changed;
    }

    private static void save(File output, String name, int[] pixels) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++) image.setRGB(x, SIZE - 1 - y, pixels[y * SIZE + x]);
        require(ImageIO.write(image, "png", new File(output, name)), "native hatch GL PNG encoder required");
    }

    private static NBTTagList nativeFaces(HologramTextures.Material material) {
        NBTTagList faces = new NBTTagList();
        for (int side = 0; side < 6; side++) {
            NBTTagCompound face = new NBTTagCompound();
            face.setInteger("side", side);
            face.setString("nativeAtlasLayers", signature(material.sides[side]));
            NBTTagList uv = new NBTTagList();
            IIcon icon = material.sides[side].get(0).icon;
            for (double value : new double[] { icon.getMinU(), icon.getMaxU(), icon.getMinV(), icon.getMaxV() })
                uv.appendTag(new NBTTagDouble(value));
            face.setTag("backgroundUV", uv);
            faces.appendTag(face);
        }
        return faces;
    }

    private static String signature(List<HologramTextures.Sample> samples) {
        StringBuilder text = new StringBuilder();
        for (HologramTextures.Sample sample : samples) {
            require(valid(sample.icon), "native hatch atlas cannot contain an unresolved texture");
            text.append(sample.icon.getIconName())
                .append(':')
                .append(uv(sample.icon))
                .append(':')
                .append(sample.color)
                .append(';');
        }
        return text.toString();
    }

    private static String uv(IIcon icon) {
        return icon.getMinU() + ":" + icon.getMaxU() + ":" + icon.getMinV() + ":" + icon.getMaxV();
    }

    private static boolean valid(IIcon icon) {
        if (icon == null || icon.getMaxU() <= icon.getMinU() || icon.getMaxV() <= icon.getMinV()) return false;
        String name = icon.getIconName()
            .toLowerCase(java.util.Locale.ROOT);
        return !name.contains("missingno") && !name.contains("error_rendering") && !name.contains("rendering_error");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
