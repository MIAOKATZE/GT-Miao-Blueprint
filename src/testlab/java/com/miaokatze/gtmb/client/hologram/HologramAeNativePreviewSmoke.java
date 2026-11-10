package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.hologram.HologramAeNativePreview;

import appeng.api.AEApi;
import appeng.api.util.AEColor;
import appeng.block.AEBaseBlock;
import appeng.block.misc.BlockInterface;
import appeng.client.render.BlockRenderInfo;
import appeng.client.texture.ExtraBlockTextures;
import appeng.tile.AEBaseTile;
import appeng.tile.misc.TileInterface;
import appeng.tile.storage.TileDrive;

/** Opt-in real registered AE/ISBRH/atlas evidence. No live world, placement, readiness or native inventory decoding. */
final class HologramAeNativePreviewSmoke {

    private HologramAeNativePreviewSmoke() {}

    static void regression() throws ReflectiveOperationException {
        // Client native profile supplies test.output; this exact class is never linked from a dedicated server path.
        Class.forName("com.miaokatze.gtmb.client.hologram.HologramAeNativePreviewRegression")
            .getMethod("run")
            .invoke(null);
        if (Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke"))
            Class.forName("com.miaokatze.gtmb.client.hologram.HologramAeNativePreviewRegression")
                .getMethod("cableRenderPass")
                .invoke(null);
    }

    static NBTTagCompound fixture(boolean vertical) throws ReflectiveOperationException {
        NBTTagCompound scene = new NBTTagCompound();
        scene.setString("session", "ae-native-static-preview");
        scene.setInteger("y", 64);
        scene.setBoolean("virtual", true);
        NBTTagList cells = new NBTTagList();
        for (int index = 0; index < 4; index++) {
            boolean drive = index < 2;
            Block block = drive ? AEApi.instance()
                .definitions()
                .blocks()
                .drive()
                .maybeBlock()
                .get()
                : AEApi.instance()
                    .definitions()
                    .blocks()
                    .iface()
                    .maybeBlock()
                    .get();
            NBTTagCompound canonical = new NBTTagCompound();
            ForgeDirection forward = drive
                ? vertical ? index == 0 ? ForgeDirection.UP : ForgeDirection.DOWN
                    : index == 0 ? ForgeDirection.SOUTH : ForgeDirection.NORTH
                : vertical ? ForgeDirection.SOUTH : ForgeDirection.UP;
            ForgeDirection up = drive
                ? vertical ? index == 0 ? ForgeDirection.NORTH : ForgeDirection.EAST : ForgeDirection.UP
                : vertical ? index == 2 ? ForgeDirection.DOWN : ForgeDirection.UP
                    : index == 2 ? ForgeDirection.WEST : ForgeDirection.EAST;
            AEColor color = index == 0 ? AEColor.Red
                : index == 1 ? AEColor.Blue : index == 2 ? AEColor.Green : AEColor.Orange;
            canonical.setString("orientation_forward", forward.name());
            canonical.setString("orientation_up", up.name());
            canonical.setByte("paintedColor", (byte) color.ordinal());
            canonical.setInteger(
                "pointAt",
                up.getOpposite()
                    .ordinal());
            if (drive) {
                NBTTagCompound inventory = new NBTTagCompound();
                for (int slot : index == 0 ? new int[] { 0, 9 } : new int[] { 3, 6 }) {
                    NBTTagCompound row = new NBTTagCompound();
                    AEApi.instance()
                        .definitions()
                        .items()
                        .cell1k()
                        .maybeStack(1)
                        .get()
                        .writeToNBT(row);
                    inventory.setTag("item" + slot, row);
                }
                canonical.setTag("inv", inventory);
            }
            NBTTagCompound planned = HologramAeNativePreview.target(block, canonical);
            TileEntity source = drive ? new TileDrive() : new TileInterface();
            set(AEBaseTile.class, source, "forward", forward);
            set(AEBaseTile.class, source, "up", up);
            set(drive ? TileDrive.class : TileInterface.class, source, "paintedColor", color);
            if (drive) {
                int state = 1 << 30;
                for (int slot : index == 0 ? new int[] { 0, 9 } : new int[] { 3, 6 }) state |= 4 << (slot * 3);
                set(TileDrive.class, source, "state", state);
                set(TileDrive.class, source, "type", 0);
            }
            NBTTagCompound actual = HologramAeNativePreview.capture(source);
            NBTTagCompound actualDisplay = new NBTTagCompound(), targetDisplay = new NBTTagCompound();
            actualDisplay.setTag("nativeTile", actual);
            targetDisplay.setTag("nativeTile", planned);
            NBTTagCompound cell = new NBTTagCompound();
            cell.setString("id", Block.blockRegistry.getNameForObject(block));
            cell.setString("wantId", Block.blockRegistry.getNameForObject(block));
            cell.setInteger("x", index * 2);
            cell.setInteger("y", 64);
            cell.setInteger("dx", index * 2);
            cell.setString("status", "pending");
            cell.setString("structureId", "ae-static-" + index);
            cell.setTag("actualDisplay", actualDisplay);
            cell.setTag("targetDisplay", targetDisplay);
            NBTTagCompound stack = new NBTTagCompound();
            new ItemStack(block).writeToNBT(stack);
            cell.setTag("actualStack", stack.copy());
            cell.setTag("targetStack", stack.copy());
            cells.appendTag(cell);
        }
        scene.setTag("cells", cells);
        return scene;
    }

    /** Precompile with a real sentinel thread context; inspect usable quads before any UI overlays. */
    static String verify(HologramState state, boolean current) throws ReflectiveOperationException {
        check(state.cells.size() == 4, "all four genuine AE fixture cells");
        HologramBlockRender.begin(state);
        HologramBlockAccess access = new HologramBlockAccess(state, current);
        StringBuilder receipt = new StringBuilder();
        for (HologramState.Cell cell : state.cells) {
            NBTTagCompound before = (NBTTagCompound) (current ? cell.actualDisplay : cell.targetDisplay).copy();
            Block block = cell.block(current);
            BlockRenderInfo info = ((AEBaseBlock) block).getRendererInstance();
            @SuppressWarnings("unchecked")
            ThreadLocal<Object> contexts = (ThreadLocal<Object>) get(BlockRenderInfo.class, info, "threadState");
            Object original = contexts.get();
            contexts.remove();
            try {
                IIcon sentinel = Blocks.diamond_block.getIcon(0, 0);
                info.setTemporaryRenderIcons(sentinel, sentinel, sentinel, sentinel, sentinel, sentinel);
                Object expected = contexts.get();
                check(
                    HologramBlockRender.ready(cell, current),
                    "native ISBRH failed: " + HologramBlockRender.failure(cell, current));
                check(
                    contexts.get() == expected && info.hasTemporaryRenderIcons(),
                    "exact old renderer ThreadLocal object restored");
                for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) check(
                    info.getTexture(side)
                        .getOriginal() == sentinel,
                    "sentinel icon state restored after preview");
            } finally {
                contexts.set(original);
            }
            check(contexts.get() == original, "fixture restores preexisting thread context");
            check(!HologramBlockRender.missingTexture(cell, current), "no missing atlas surface");
            List<double[]> vertices = HologramBlockRender.attributes(cell, current);
            check(!vertices.isEmpty(), "actual GL emitted UV/color attributes");
            TileEntity tile = access.getTileEntity(cell.x, cell.y, cell.z);
            check(
                tile != null && tile.getWorldObj() != net.minecraft.client.Minecraft.getMinecraft().theWorld,
                "detached native visual tile");
            if (tile instanceof TileDrive) verifyDrive((TileDrive) tile, vertices, cell.index);
            else {
                TileInterface iface = (TileInterface) tile;
                check(
                    HologramBlockRender
                        .usesIcon(cell, current, ((BlockInterface) block).getRenderTexture(1, iface.getColor())),
                    "native colored directional Interface arrow atlas");
            }
            check(
                before.equals(current ? cell.actualDisplay : cell.targetDisplay),
                "render leaves complete snapshot unchanged");
            receipt.append(cell.wantId)
                .append("@")
                .append(cell.index)
                .append(" vertices=")
                .append(vertices.size())
                .append(" nativeUV/color/slots/context PASS\n");
        }
        return receipt.toString();
    }

    private static void verifyDrive(TileDrive drive, List<double[]> vertices, int index) {
        IIcon cells = ExtraBlockTextures.MEStorageCellTextures.getIcon();
        int occupied = 0, vacant = 0;
        ForgeDirection forward = drive.getForward(), up = drive.getUp();
        ForgeDirection west = appeng.util.Platform.crossProduct(forward, up);
        for (int start = 0; start + 3 < vertices.size(); start += 4) {
            double[] center = new double[5];
            for (int corner = 0; corner < 4; corner++) for (int coordinate = 0; coordinate < 5; coordinate++)
                center[coordinate] += vertices.get(start + corner)[coordinate] / 4;
            if (!(center[3] > cells.getMinU() && center[3] < cells.getMaxU()
                && center[4] > cells.getMinV()
                && center[4] < cells.getMaxV())) continue;
            for (int corner = 0; corner < 4; corner++) {
                double[] vertex = vertices.get(start + corner);
                double plane = vertex[0] * forward.offsetX + vertex[1] * forward.offsetY + vertex[2] * forward.offsetZ;
                check(
                    Math.abs(plane - (forward.offsetX + forward.offsetY + forward.offsetZ > 0 ? 1 : 0)) < 1e-5,
                    "drive cells on exact native front plane");
            }
            boolean empty = Math.abs(center[4] - cells.getInterpolatedV(14)) < 1e-5;
            boolean item = Math.abs(center[4] - cells.getInterpolatedV(2)) < 1e-5;
            check(empty || item, "exact native vacant/item cell UV range");
            double rightCoordinate = center[0] * west.offsetX + center[1] * west.offsetY + center[2] * west.offsetZ;
            double upCoordinate = center[0] * up.offsetX + center[1] * up.offsetY + center[2] * up.offsetZ;
            if (west.offsetX + west.offsetY + west.offsetZ < 0) rightCoordinate += 1;
            if (up.offsetX + up.offsetY + up.offsetZ < 0) upCoordinate += 1;
            int column = Math.round((float) ((rightCoordinate * 16 - 4.5) / 7));
            // Native selectFace converts GUI row V to 16 - V before mapping the oriented up axis.
            int row = Math.round((float) (((1 - upCoordinate) * 16 - 2) / 3));
            int slot = row * 2 + 1 - column;
            check(slot >= 0 && slot < 10, "native cell UV has exact ten-slot position");
            check(
                item == (drive.getCellStatus(slot) != 0),
                "native geometry UV matches authoritative cell slot " + slot);
            if (item) occupied++;
            else vacant++;
        }
        check(occupied == 4 && vacant == 16, "both native passes emit exact two occupied/eight vacant cell textures");
        int paint = drive.getColor().driveVariant;
        boolean painted = false;
        for (double[] vertex : vertices) {
            int color = (int) vertex[5];
            if (Math.abs((color & 255) - (paint >> 16 & 255)) <= 1
                && Math.abs((color >> 8 & 255) - (paint >> 8 & 255)) <= 1
                && Math.abs((color >> 16 & 255) - (paint & 255)) <= 1) painted = true;
        }
        check(painted, "native Drive emits selected paint variant RGB");
        check(
            drive.getCellStatus(index == 0 ? 0 : 3) != 0 && drive.getCellStatus(index == 0 ? 3 : 0) == 0,
            "distinct native slot arrangements");
    }

    private static void set(Class<?> owner, Object target, String name, Object value)
        throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new IllegalStateException("AE native GL: " + message);
    }
}
