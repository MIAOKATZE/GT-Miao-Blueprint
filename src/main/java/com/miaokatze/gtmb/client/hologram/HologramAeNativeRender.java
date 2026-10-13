package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramAeNativePreview;

import appeng.api.AEApi;
import appeng.api.util.AEColor;
import appeng.block.AEBaseBlock;
import appeng.block.networking.BlockCableBus;
import appeng.block.storage.BlockDrive;
import appeng.client.render.BlockRenderInfo;
import appeng.client.render.BusRenderHelper;
import appeng.client.render.BusRenderer;
import appeng.client.render.WorldRender;
import appeng.client.render.blocks.RenderBlockInterface;
import appeng.client.render.blocks.RenderDrive;
import appeng.tile.misc.TileInterface;
import appeng.tile.storage.TileDrive;
import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;

/** Reviewed client visual hosts: physical inventories and proxy data remain constructor-empty and unready. */
final class HologramAeNativeRender implements HologramNativeRenderRegistry.Adapter {

    static final HologramAeNativeRender INSTANCE = new HologramAeNativeRender();

    private HologramAeNativeRender() {}

    /** CBC's real world pass callback owns a separate native helper; never reuse the live world's thread state. */
    static CableContext cableContext(Block block, TileEntity tile) throws ReflectiveOperationException {
        if (block.getClass() == BlockCableBus.class && tile instanceof appeng.tile.networking.TileCableBus)
            return new CableContext();
        if (!block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart") || tile == null) return null;
        if (!Class.forName("codechicken.multipart.TileMultipart")
            .isInstance(tile)) return null;
        Class<?> cablePart = Class.forName("appeng.fmp.CableBusPart");
        for (Object part : (java.util.List<?>) tile.getClass()
            .getMethod("jPartList")
            .invoke(tile))
            if (cablePart.isInstance(part) && "ae2_cablebus".equals(
                part.getClass()
                    .getMethod("getType")
                    .invoke(part)))
                return new CableContext();
        return null;
    }

    static final class CableContext implements AutoCloseable {

        private final ThreadLocal<Object> renderers, icons;
        private final Object previousRenderer, previousIcons;
        private final BusRenderHelper previousHelper;
        private final AEBaseBlock cableBlock;
        private final Method restoreColor;
        private final int previousColor;

        @SuppressWarnings("unchecked")
        private CableContext() throws ReflectiveOperationException {
            Block block = AEApi.instance()
                .definitions()
                .blocks()
                .multiPart()
                .maybeBlock()
                .orNull();
            if (block == null || block.getClass() != BlockCableBus.class)
                throw new IllegalArgumentException("Registered official CableBus render host is unavailable");
            cableBlock = (AEBaseBlock) block;
            restoreColor = block.getClass()
                .getDeclaredMethod("setRenderColor", int.class);
            restoreColor.setAccessible(true);
            Field renderer = BusRenderer.class.getDeclaredField("renderer");
            Field threadState = BlockRenderInfo.class.getDeclaredField("threadState");
            renderer.setAccessible(true);
            threadState.setAccessible(true);
            renderers = (ThreadLocal<Object>) renderer.get(BusRenderer.INSTANCE);
            icons = (ThreadLocal<Object>) threadState.get(cableBlock.getRendererInstance());
            previousHelper = BusRenderHelper.instances.get();
            previousRenderer = renderers.get();
            previousIcons = icons.get();
            previousColor = cableBlock.colorMultiplier(null, 0, 0, 0);
            // The official ThreadLocal initializers construct a new renderer and its correctly bound helper lazily.
            renderers.remove();
            icons.remove();
            BusRenderHelper.instances.remove();
        }

        public void close() {
            try {
                restoreColor.invoke(cableBlock, previousColor);
            } catch (ReflectiveOperationException unavailableNativeSetter) {
                throw new IllegalStateException("Native CableBus color restoration failed", unavailableNativeSetter);
            } finally {
                icons.set(previousIcons);
                BusRenderHelper.instances.set(previousHelper);
                renderers.set(previousRenderer);
            }
        }
    }

    public boolean supports(Block block) {
        return HologramAeNativePreview.supports(block);
    }

    public TileEntity createTile(Block block, int metadata, World isolatedWorld) {
        if (!supports(block)) throw new IllegalArgumentException("Unreviewed AE render factory");
        return block.getClass() == BlockDrive.class ? new Drive() : new Interface();
    }

    public void loadTile(TileEntity tile, NBTTagCompound payload) {
        if (tile.getClass() == Drive.class) ((Drive) tile).visual = new Visual(payload, "drive");
        else if (tile.getClass() == Interface.class) ((Interface) tile).visual = new Visual(payload, "interface");
        else throw new IllegalArgumentException("Unreviewed AE detached render tile");
        // AEBaseTile.readFromNBT is final and executes inventory/event readers. It is deliberately never invoked here.
    }

    public ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler registered) {
        if (!supports(block) || registered.getClass() != WorldRender.class)
            throw new IllegalArgumentException("Unreviewed AE registered renderer");
        Object nativeRenderer = ((AEBaseBlock) block).getRendererInstance()
            .getRendererInstance();
        Class<?> expected = block.getClass() == BlockDrive.class ? RenderDrive.class : RenderBlockInterface.class;
        if (nativeRenderer.getClass() != expected) throw new IllegalArgumentException("Unreviewed AE block renderer");
        return new IsolatedRenderer();
    }

    private static final class Visual {

        final ForgeDirection forward, up;
        final AEColor color;
        final int state, type;
        final boolean stuck;

        Visual(NBTTagCompound payload, String host) {
            NBTTagCompound frame = HologramAeNativePreview.frame(payload, host);
            forward = HologramAeNativePreview.direction(frame, "orientation_forward");
            up = HologramAeNativePreview.direction(frame, "orientation_up");
            if (host.equals("drive") && (forward == ForgeDirection.UNKNOWN || up == ForgeDirection.UNKNOWN)
                || forward != ForgeDirection.UNKNOWN && (forward == up || forward == up.getOpposite()))
                throw new IllegalArgumentException("Invalid AE visual orientation");
            color = AEColor.values()[HologramAeNativePreview.validColor(frame.getByte("paint"))];
            state = frame.getInteger("state") & 0x7fffffff;
            type = frame.getInteger("type") & 0xfffff;
            stuck = frame.getBoolean("stuck");
        }
    }

    private static final class Drive extends TileDrive {

        private Visual visual;

        protected ItemStack getItemFromTile(Object ignored) {
            // AEBaseTile's registry keys exact classes; the visual subclass uses the reviewed original definition.
            return AEApi.instance()
                .definitions()
                .blocks()
                .drive()
                .maybeStack(1)
                .orNull();
        }

        public ForgeDirection getForward() {
            return visual == null ? ForgeDirection.SOUTH : visual.forward;
        }

        public ForgeDirection getUp() {
            return visual == null ? ForgeDirection.UP : visual.up;
        }

        public AEColor getColor() {
            return visual == null ? AEColor.Transparent : visual.color;
        }

        public int getCellStatus(int slot) {
            return visual == null ? 0 : visual.state >> (slot * 3) & 7;
        }

        public int getCellType(int slot) {
            return visual == null ? 0 : visual.type >> (slot * 2) & 3;
        }

        public boolean isPowered() {
            return visual != null && (visual.state & 1 << 30) != 0;
        }
    }

    private static final class Interface extends TileInterface {

        private Visual visual;

        protected ItemStack getItemFromTile(Object ignored) {
            return AEApi.instance()
                .definitions()
                .blocks()
                .iface()
                .maybeStack(1)
                .orNull();
        }

        public ForgeDirection getForward() {
            return visual == null ? ForgeDirection.UNKNOWN : visual.forward;
        }

        public ForgeDirection getUp() {
            return visual == null ? ForgeDirection.UNKNOWN : visual.up;
        }

        public AEColor getColor() {
            return visual == null ? AEColor.Transparent : visual.color;
        }

        public boolean isStuck() {
            return visual != null && visual.stuck;
        }
    }

    /** AE keeps temporary icons per thread. Replace only this thread's context, preserving the exact prior object. */
    private static final class IsolatedRenderer implements ISimpleBlockRenderingHandler {

        private final WorldRender delegate = new WorldRender();
        private static final Field THREAD = field(BlockRenderInfo.class, "threadState");
        private static final Field TEXTURES = field(BlockRenderInfo.class, "textures");

        public boolean renderWorldBlock(IBlockAccess access, int x, int y, int z, Block block, int id,
            RenderBlocks renderer) {
            BlockRenderInfo info = ((AEBaseBlock) block).getRendererInstance();
            try {
                @SuppressWarnings("unchecked")
                ThreadLocal<Object> local = (ThreadLocal<Object>) THREAD.get(info);
                Object previous = local.get();
                BlockRenderInfo.TextureSet textures = (BlockRenderInfo.TextureSet) TEXTURES.get(info);
                IIcon[] icons = new IIcon[6];
                for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) icons[side.ordinal()] = textures.get(side)
                    .getOriginal();
                local.remove();
                try {
                    info.setTemporaryRenderIcons(icons[1], icons[0], icons[3], icons[2], icons[5], icons[4]);
                    return delegate.renderWorldBlock(access, x, y, z, block, id, renderer);
                } finally {
                    local.set(previous);
                }
            } catch (IllegalAccessException unavailablePinnedApi) {
                throw new IllegalStateException("AE visual context API unavailable", unavailablePinnedApi);
            }
        }

        public void renderInventoryBlock(Block block, int metadata, int id, RenderBlocks renderer) {
            throw new UnsupportedOperationException("AE preview renders only bounded static world geometry");
        }

        public boolean shouldRender3DInInventory(int id) {
            return false;
        }

        public int getRenderId() {
            return delegate.getRenderId();
        }

        private static Field field(Class<?> owner, String name) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException unavailablePinnedApi) {
                throw new IllegalStateException("AE visual context API unavailable", unavailablePinnedApi);
            }
        }
    }
}
