package com.miaokatze.gtmb.client.hologram;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtmb.hologram.HologramMachineReference;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Full-size registered native world rendering of already formed server machines; no hologram mesh fallback. */
public final class HologramMachineReferenceClient {

    private static final String[] VIEWS = { "front-left", "front-right", "rear-left", "cutaway" };
    private static final float[] YAW = { -35, 35, 145, -35 };
    private static final int BACKGROUND = 0x151b24;
    private int stage = -1;
    private long stageAt;
    private final long installedAt = System.nanoTime();
    private boolean finished;
    private ReferenceScreen screen;

    public static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.referenceClient") || Boolean.getBoolean("gtmb.hologram.clientSmoke"))
            throw new IllegalStateException("Machine references require a separate client profile");
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramMachineReferenceClient());
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (stage < 0) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) {
                    require(System.nanoTime() - installedAt < 300000000000L, "main menu initialization timeout");
                    return;
                }
                mc.gameSettings.guiScale = 1;
                mc.gameSettings.ambientOcclusion = 0;
                next(mc);
                return;
            }
            if (System.nanoTime() - stageAt < 2000000000L) return;
            require(mc.currentScreen == screen && screen.draws >= 2, "reference screen must actually render");
            require(screen.failure == null, "native render failure: " + screen.failure);
            require(screen.vertices > 200 && screen.gtTiles > 0, "actual registered GT triangles must render");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error");
            String filename = screen.state.getString("name") + "-" + VIEWS[stage % 4] + ".png";
            File screenshot = new File(new File(mc.mcDataDir, "screenshots"), filename);
            if (screenshot.exists()) require(screenshot.delete(), "replace reference screenshot");
            ScreenShotHelper
                .saveScreenshot(mc.mcDataDir, filename, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            String raster = audit(screenshot);
            System.out.println(
                "[GTMB-MACHINE-REFERENCE-CLIENT] PASS " + filename
                    + " nativeVertices="
                    + screen.vertices
                    + " restoredGT="
                    + screen.gtTiles
                    + " "
                    + raster
                    + " screenshot="
                    + screenshot.getAbsolutePath());
            next(mc);
        } catch (Throwable failure) {
            failure.printStackTrace();
            finished = true;
            if (screen != null) screen.release();
            System.err.println("[GTMB-MACHINE-REFERENCE-CLIENT] FINAL FAIL " + failure);
            mc.shutdown();
        }
    }

    private void next(Minecraft mc) throws Exception {
        if (screen != null) screen.release();
        if (++stage == HologramMachineReference.MACHINES.length * VIEWS.length) {
            finished = true;
            System.out
                .println("[GTMB-MACHINE-REFERENCE-CLIENT] FINAL PASS screenshots=16 full native registered renderers");
            mc.shutdown();
            return;
        }
        String name = HologramMachineReference.MACHINES[stage / VIEWS.length];
        File file = new File(System.getProperty("gtmb.hologram.referenceStateDir", ""), name + ".nbt");
        NBTTagCompound state;
        try (FileInputStream input = new FileInputStream(file)) {
            state = CompressedStreamTools.readCompressed(input);
        }
        require(state.getBoolean("formed"), "reference machine must have passed real server formation");
        screen = new ReferenceScreen(state, stage % VIEWS.length);
        mc.displayGuiScreen(screen);
        stageAt = System.nanoTime();
    }

    private static String audit(File file) throws Exception {
        BufferedImage image = ImageIO.read(file);
        require(image != null, "saved screenshot must decode");
        int foreground = 0, minY = image.getHeight(), maxY = 0;
        Set<Integer> colors = new HashSet<>();
        for (int y = 90; y < image.getHeight() - 90; y++) for (int x = 50; x < image.getWidth() - 50; x++) {
            int rgb = image.getRGB(x, y) & 0xffffff;
            int difference = Math.abs((rgb & 255) - (BACKGROUND & 255))
                + Math.abs((rgb >> 8 & 255) - (BACKGROUND >> 8 & 255))
                + Math.abs((rgb >> 16 & 255) - (BACKGROUND >> 16 & 255));
            if (difference > 30) {
                foreground++;
                colors.add(rgb);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        require(
            foreground > 30000 && colors.size() > 100 && maxY - minY >= 600,
            "machine must retain large detailed material pixels: foreground=" + foreground
                + " colors="
                + colors.size()
                + " height="
                + (maxY - minY));
        return "materialPixels=" + foreground + " colors=" + colors.size() + " machineHeight=" + (maxY - minY);
    }

    private static final class ReferenceScreen extends GuiScreen {

        private static final Field VERTEX_COUNT = ReflectionHelper
            .findField(Tessellator.class, "vertexCount", "field_78406_i");
        final NBTTagCompound state;
        final int view;
        final ReferenceWorld world;
        final List<NBTTagCompound> blocks = new ArrayList<>();
        int draws, vertices, gtTiles, list;
        Throwable failure;

        ReferenceScreen(NBTTagCompound state, int view) {
            this.state = state;
            this.view = view;
            world = new ReferenceWorld(state, view == 3);
            NBTTagList rows = state.getTagList("blocks", 10);
            for (int i = 0; i < rows.tagCount(); i++) blocks.add(rows.getCompoundTagAt(i));
            VERTEX_COUNT.setAccessible(true);
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTick) {
            drawRect(0, 0, width, height, 0xff000000 | BACKGROUND);
            int[] b = state.getIntArray("bounds");
            String title = state.getString("name")
                .replace('-', ' ');
            drawCenteredString(
                fontRendererObj,
                title + " | " + (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1) + " blocks",
                width / 2,
                32,
                0xe8edf5);
            drawCenteredString(
                fontRendererObj,
                "GT5U native renderer | server formation verified | " + VIEWS[view],
                width / 2,
                height - 52,
                0xa5b3c8);
            if (view == 3) drawCenteredString(
                fontRendererObj,
                "CUTAWAY: front block plane hidden for internal material reference",
                width / 2,
                height - 35,
                0xf2bb65);
            int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                if (list == 0 && failure == null) compile();
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, .1f);
                GL11.glColor4f(1, 1, 1, 1);
                mc.getTextureManager()
                    .bindTexture(TextureMap.locationBlocksTexture);
                double dx = b[3] - b[0] + 1, dy = b[4] - b[1] + 1, dz = b[5] - b[2] + 1;
                double angle = Math.toRadians(YAW[view]), pitch = Math.toRadians(25);
                double projectedW = Math.abs(Math.cos(angle)) * dx + Math.abs(Math.sin(angle)) * dz;
                double projectedH = Math.cos(pitch) * dy
                    + Math.sin(pitch) * (Math.abs(Math.sin(angle)) * dx + Math.abs(Math.cos(angle)) * dz);
                double scale = .94 * Math.min((width - 160) / projectedW, (height - 180) / projectedH);
                GL11.glTranslated(width / 2.0, height / 2.0, 0);
                GL11.glScaled(scale, -scale, -scale);
                GL11.glRotatef(-25, 1, 0, 0);
                GL11.glRotatef(YAW[view], 0, 1, 0);
                GL11.glTranslated(-(b[0] + b[3] + 1) / 2.0, -(b[1] + b[4] + 1) / 2.0, -(b[2] + b[5] + 1) / 2.0);
                if (list != 0) GL11.glCallList(list);
                draws++;
            } catch (Throwable failed) {
                failure = failed;
            } finally {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(matrixMode);
                GL11.glPopAttrib();
            }
        }

        private void compile() throws Exception {
            RenderBlocks renderer = new RenderBlocks(world);
            renderer.enableAO = false;
            renderer.renderAllFaces = false;
            Tessellator tess = Tessellator.instance;
            int previousPass = net.minecraftforge.client.MinecraftForgeClient.getRenderPass();
            list = GL11.glGenLists(1);
            require(list != 0, "native GL display list allocation");
            GL11.glNewList(list, GL11.GL_COMPILE);
            try {
                for (NBTTagCompound row : blocks) {
                    if (!row.hasKey("machineId")) continue;
                    if (view == 3 && row.getInteger("z") == state.getIntArray("bounds")[2]) continue;
                    TileEntity tile = world
                        .getTileEntity(row.getInteger("x"), row.getInteger("y"), row.getInteger("z"));
                    require(tile instanceof IGregTechTileEntity, "actual GT tile restored");
                    require(
                        ((IGregTechTileEntity) tile).getMetaTileID() == row.getInteger("machineId"),
                        "exact registered GT ID restored");
                    gtTiles++;
                }
                for (int pass = 0; pass <= 1; pass++) {
                    ForgeHooksClient.setRenderPass(pass);
                    if (pass == 1) {
                        GL11.glEnable(GL11.GL_BLEND);
                        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    }
                    for (NBTTagCompound row : blocks) {
                        int x = row.getInteger("x"), y = row.getInteger("y"), z = row.getInteger("z");
                        if (view == 3 && z == state.getIntArray("bounds")[2]) continue;
                        Block block = world.getBlock(x, y, z);
                        if (!block.canRenderInPass(pass)) continue;
                        tess.startDrawingQuads();
                        tess.setTranslation(0, 0, 0);
                        tess.setBrightness(0xf000f0);
                        tess.setColorOpaque_F(1, 1, 1);
                        require(block.getRenderType() >= 0, "registered static renderer unavailable: " + block);
                        renderer.renderBlockByRenderType(block, x, y, z);
                        vertices += VERTEX_COUNT.getInt(tess);
                        tess.draw();
                    }
                }
            } finally {
                ForgeHooksClient.setRenderPass(previousPass);
                GL11.glEndList();
            }
        }

        void release() {
            if (list != 0) {
                GL11.glDeleteLists(list, 1);
                list = 0;
            }
        }
    }

    /** Detached native tile deserialization is confined to this opt-in tool; no ticks or network connection occur. */
    private static final class ReferenceWorld extends World {

        private Map<String, NBTTagCompound> rows;
        private Map<String, TileEntity> tiles;

        ReferenceWorld(NBTTagCompound state, boolean cutaway) {
            super(
                new SaveHandlerMP(),
                "GTMB machine reference",
                new WorldProviderSurface(),
                new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT),
                new Profiler());
            isRemote = true;
            rows = new HashMap<>();
            tiles = new HashMap<>();
            NBTTagList blocks = state.getTagList("blocks", 10);
            for (int i = 0; i < blocks.tagCount(); i++) {
                NBTTagCompound row = blocks.getCompoundTagAt(i);
                if (cutaway && row.getInteger("z") == state.getIntArray("bounds")[2]) continue;
                rows.put(key(row.getInteger("x"), row.getInteger("y"), row.getInteger("z")), row);
            }
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return new ChunkProviderClient(this);
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public net.minecraft.entity.Entity getEntityByID(int id) {
            return null;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            NBTTagCompound row = rows == null ? null : rows.get(key(x, y, z));
            return row == null ? net.minecraft.init.Blocks.air : Block.getBlockFromName(row.getString("block"));
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            NBTTagCompound row = rows == null ? null : rows.get(key(x, y, z));
            return row == null ? 0 : row.getInteger("meta");
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            String key = key(x, y, z);
            if (tiles == null || rows == null) return null;
            if (tiles.containsKey(key)) return tiles.get(key);
            NBTTagCompound row = rows.get(key);
            if (row == null || !row.hasKey("tile", 10)) return null;
            Block block = getBlock(x, y, z);
            TileEntity tile = block.createTileEntity(this, row.getInteger("meta"));
            require(tile != null, "native tile factory for " + block);
            tile.setWorldObj(this);
            NBTTagCompound saved = (NBTTagCompound) row.getCompoundTag("tile")
                .copy();
            saved.setInteger("x", x);
            saved.setInteger("y", y);
            saved.setInteger("z", z);
            tile.readFromNBT(saved);
            tile.xCoord = x;
            tile.yCoord = y;
            tile.zCoord = z;
            tiles.put(key, tile);
            return tile;
        }

        @Override
        public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minimum) {
            return 0xf000f0;
        }

        @Override
        public float getLightBrightness(int x, int y, int z) {
            return 1;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return rows != null && rows.containsKey(key(x, y, z));
        }

        @Override
        public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean fallback) {
            return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            return false;
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            return false;
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {}

        @Override
        public void removeTileEntity(int x, int y, int z) {}

        @Override
        public void markBlockForUpdate(int x, int y, int z) {}

        @Override
        public void markTileEntityChunkModified(int x, int y, int z, TileEntity tile) {}

        @Override
        public void notifyBlockChange(int x, int y, int z, Block block) {}

        @Override
        public void notifyBlocksOfNeighborChange(int x, int y, int z, Block block) {}

        @Override
        public void notifyBlockOfNeighborChange(int x, int y, int z, Block block) {}

        @Override
        public boolean spawnEntityInWorld(net.minecraft.entity.Entity entity) {
            return false;
        }

        @Override
        public void scheduleBlockUpdate(int x, int y, int z, Block block, int delay) {}

        @Override
        public boolean updateLightByType(net.minecraft.world.EnumSkyBlock type, int x, int y, int z) {
            return false;
        }

        @Override
        public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
