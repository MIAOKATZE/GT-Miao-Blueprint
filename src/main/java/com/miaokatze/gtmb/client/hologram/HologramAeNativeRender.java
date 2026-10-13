package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

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
import appeng.api.implementations.parts.IPartCable;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
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
import appeng.parts.CableBusContainer;
import appeng.parts.CableBusStorage;
import appeng.parts.networking.PartCable;
import appeng.parts.reporting.AbstractPartReporting;
import appeng.tile.misc.TileInterface;
import appeng.tile.networking.TileCableBus;
import appeng.tile.storage.TileDrive;
import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;

/** Reviewed client visual hosts: physical inventories and proxy data remain constructor-empty and unready. */
final class HologramAeNativeRender implements HologramNativeRenderRegistry.Adapter {

    static final HologramAeNativeRender INSTANCE = new HologramAeNativeRender();

    private HologramAeNativeRender() {}

    private static final Map<CableBusContainer, Boolean> CAPTURED_TOPOLOGY = Collections
        .synchronizedMap(new WeakHashMap<>());

    /** Construct native visual parts without invoking any persistence, inventory or proxy reader. */
    static void loadCable(Object host, NBTTagCompound payload) throws ReflectiveOperationException {
        CableBusContainer bus = cableBus(host);
        if (bus == null) throw new IllegalArgumentException("Unreviewed AE CableBus visual host");
        Method center = CableBusStorage.class.getDeclaredMethod("setCenter", IPartCable.class);
        Method side = CableBusStorage.class.getDeclaredMethod("setSide", ForgeDirection.class, IPart.class);
        center.setAccessible(true);
        side.setAccessible(true);
        for (ForgeDirection direction : ForgeDirection.values()) {
            String key = "def:" + direction.ordinal();
            if (!payload.hasKey(key)) continue;
            ItemStack definition = HologramAeNativePreview.partDefinition(payload.getCompoundTag(key));
            IPart part = ((IPartItem) definition.getItem()).createPartFromItemStack(definition);
            if (part == null || !part.getClass()
                .getName()
                .startsWith("appeng.parts.")) throw new IllegalArgumentException("Unreviewed AE visual part factory");
            if (direction == ForgeDirection.UNKNOWN) {
                if (!(part instanceof IPartCable)) throw new IllegalArgumentException("Invalid AE visual center");
                center.invoke(bus, part);
            } else {
                if (part instanceof IPartCable) throw new IllegalArgumentException("Invalid AE visual side part");
                side.invoke(bus, direction, part);
            }
            part.setPartHostInfo(direction, bus, bus.getTile());
            if (part instanceof AbstractPartReporting) {
                NBTTagCompound extra = payload.getCompoundTag("extra:" + direction.ordinal());
                Field spin = AbstractPartReporting.class.getDeclaredField("spin");
                spin.setAccessible(true);
                spin.setByte(part, (byte) (extra.getByte("spin") & 3));
            }
        }
        // Reviewed native facade material factories receive only their bounded material identity.
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            String key = "facade:" + direction.ordinal();
            if (payload.hasKey(key, 10)) {
                ItemStack definition = HologramAeNativePreview.facadeDefinition(payload.getCompoundTag(key));
                appeng.api.parts.IFacadePart facade = ((appeng.items.parts.ItemFacade) definition.getItem())
                    .createPartFromItemStack(definition, direction);
                if (facade == null) throw new IllegalArgumentException("Missing AE visual facade material");
                bus.setFacade(direction.ordinal(), facade);
            }
        }
        boolean captured = payload.hasKey("gtmb.aeCableVisual", 10);
        CAPTURED_TOPOLOGY.put(bus, captured);
        if (captured) {
            NBTTagCompound visual = payload.getCompoundTag("gtmb.aeCableVisual");
            visualPacket(bus, visual.getInteger("connections") & 127, visual.getInteger("channels") & 0xffffff);
        }
    }

    /** Called after the detached tile enters the bounded cache, so adjacent cable lookup cannot recurse. */
    static void prepareTopology(TileEntity tile, IBlockAccess access) throws ReflectiveOperationException {
        CableBusContainer bus = cableBus(tile);
        if (bus == null || Boolean.TRUE.equals(CAPTURED_TOPOLOGY.get(bus))) return;
        if (!(bus.getPart(ForgeDirection.UNKNOWN) instanceof PartCable)) return;
        int connections = 0;
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            if (bus.getPart(direction) != null || bus.isBlocked(direction)) continue;
            TileEntity neighbor = access.getTileEntity(
                tile.xCoord + direction.offsetX,
                tile.yCoord + direction.offsetY,
                tile.zCoord + direction.offsetZ);
            CableBusContainer other = cableBus(neighbor);
            ForgeDirection opposite = direction.getOpposite();
            if (other != null && other.getPart(ForgeDirection.UNKNOWN) instanceof PartCable
                && other.getPart(opposite) == null
                && !other.isBlocked(opposite)
                && bus.getColor()
                    .matches(other.getColor()))
                connections |= 1 << direction.ordinal();
            else if (neighbor != null && neighbor.getClass() == Drive.class) {
                Drive drive = (Drive) neighbor;
                if (drive.getProxy()
                    .getConnectableSides()
                    .contains(opposite)
                    && bus.getColor()
                        .matches(drive.getColor()))
                    connections |= 1 << direction.ordinal();
            } else if (neighbor != null && neighbor.getClass() == Interface.class) {
                Interface iface = (Interface) neighbor;
                if (iface.getProxy()
                    .getConnectableSides()
                    .contains(opposite)
                    && bus.getColor()
                        .matches(iface.getColor()))
                    connections |= 1 << direction.ordinal();
            }
        }
        // Planned cables are offline. Only geometric adjacency is projected; saved power/channel state is not copied.
        visualPacket(bus, connections, 0);
    }

    private static void visualPacket(CableBusContainer bus, int connections, int channels) {
        IPart part = bus.getPart(ForgeDirection.UNKNOWN);
        if (!(part instanceof PartCable)) return;
        io.netty.buffer.ByteBuf bytes = io.netty.buffer.Unpooled.buffer(5, 5);
        try {
            bytes.writeByte(connections);
            bytes.writeInt(channels);
            ((PartCable) part).readFromStream(bytes);
        } catch (java.io.IOException invalidNativeFrame) {
            throw new IllegalArgumentException("Invalid AE visual cable frame", invalidNativeFrame);
        } finally {
            bytes.release();
        }
    }

    private static CableBusContainer cableBus(Object host) throws ReflectiveOperationException {
        if (host instanceof TileCableBus) return ((TileCableBus) host).getCableBus();
        if (host == null) return null;
        if (host.getClass()
            .getName()
            .equals("appeng.fmp.CableBusPart"))
            return (CableBusContainer) host.getClass()
                .getMethod("getCableBus")
                .invoke(host);
        boolean multipart = false;
        for (Class<?> type = host.getClass(); type != null; type = type.getSuperclass()) if (type.getName()
            .equals("codechicken.multipart.TileMultipart")) multipart = true;
        if (multipart) for (Object part : (java.util.List<?>) host.getClass()
            .getMethod("jPartList")
            .invoke(host)) {
                CableBusContainer bus = cableBus(part);
                if (bus != null) return bus;
            }
        return null;
    }

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
        return HologramAeNativePreview.supports(block) || block != null && block.getClass()
            .getName()
            .equals("appeng.block.networking.BlockCableBus");
    }

    public TileEntity createTile(Block block, int metadata, World isolatedWorld) {
        if (!supports(block)) throw new IllegalArgumentException("Unreviewed AE render factory");
        if (block.getClass() == BlockCableBus.class) {
            Class<? extends TileEntity> factory = ((appeng.block.AEBaseTileBlock) block).getTileEntityClass();
            if (factory == null || factory != registeredCableFactory() || !TileCableBus.class.isAssignableFrom(factory))
                throw new IllegalArgumentException("Unreviewed AE registered CableBus visual factory");
            TileEntity tile = block.createTileEntity(isolatedWorld, metadata);
            if (tile == null || tile.getClass() != factory)
                throw new IllegalArgumentException("Native AE CableBus factory returned the wrong visual host");
            return tile;
        }
        return block.getClass() == BlockDrive.class ? new Drive() : new Interface();
    }

    public void loadTile(TileEntity tile, NBTTagCompound payload) {
        if (tile instanceof TileCableBus && tile.getClass() == registeredCableFactory()) {
            try {
                loadCable(tile, payload);
                return;
            } catch (ReflectiveOperationException invalidNativeFactory) {
                throw new IllegalArgumentException("AE CableBus visual factory unavailable", invalidNativeFactory);
            }
        }
        if (tile.getClass() == Drive.class) {
            Drive drive = (Drive) tile;
            drive.visual = new Visual(payload, "drive");
            drive.getProxy()
                .setColor(drive.visual.color);
        } else if (tile.getClass() == Interface.class) {
            Interface iface = (Interface) tile;
            iface.visual = new Visual(payload, "interface");
            iface.getProxy()
                .setColor(iface.visual.color);
            iface.getProxy()
                .setValidSides(EnumSet.complementOf(EnumSet.of(iface.visual.up.getOpposite())));
        } else throw new IllegalArgumentException("Unreviewed AE detached render tile");
        // AEBaseTile.readFromNBT is final and executes inventory/event readers. It is deliberately never invoked here.
    }

    /** CBC implements optional MFR interfaces; resolve only its exact declared native API without javac linkage. */
    static Class<? extends TileEntity> registeredCableFactory() {
        try {
            Object factory = BlockCableBus.class.getDeclaredMethod("getNoTesrTile")
                .invoke(null);
            if (!(factory instanceof Class<?>) || !TileCableBus.class.isAssignableFrom((Class<?>) factory))
                throw new IllegalArgumentException("Missing reviewed native CableBus combined factory");
            return ((Class<?>) factory).asSubclass(TileEntity.class);
        } catch (ReflectiveOperationException unavailableNativeApi) {
            throw new IllegalArgumentException("Native CableBus factory API unavailable", unavailableNativeApi);
        }
    }

    public ISimpleBlockRenderingHandler renderer(Block block, ISimpleBlockRenderingHandler registered) {
        if (!supports(block) || registered.getClass() != WorldRender.class)
            throw new IllegalArgumentException("Unreviewed AE registered renderer");
        Object nativeRenderer = ((AEBaseBlock) block).getRendererInstance()
            .getRendererInstance();
        if (block.getClass() == BlockCableBus.class) {
            if (nativeRenderer.getClass() != appeng.client.render.blocks.RendererCableBus.class)
                throw new IllegalArgumentException("Unreviewed AE CableBus renderer");
            return registered;
        }
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
