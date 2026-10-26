package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.miaokatze.gtmb.api.CutTransforms;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.objects.GTItemStack;
import gregtech.api.util.GTUtility;
import gregtech.common.covers.Cover;

/** Exact .205 prototype/cover identities and independent orientation expectations; no world or machine ticks. */
public final class HologramGtSchemaTransformRegression {

    private static final int BASIC = 32745, DISTRIBUTOR = 32746, MAINTENANCE = 32747;

    private HologramGtSchemaTransformRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramGtSchemaTransformRegression.class, args)) return;
        run();
        System.out.println("GT schema transform regressions passed");
    }

    public static void run() {
        String threadName = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        IMetaTileEntity oldBasic = GregTechAPI.METATILEENTITIES[BASIC];
        IMetaTileEntity oldDistributor = GregTechAPI.METATILEENTITIES[DISTRIBUTOR];
        IMetaTileEntity oldMaintenance = GregTechAPI.METATILEENTITIES[MAINTENANCE];
        try {
            GregTechAPI.METATILEENTITIES[BASIC] = allocate(
                "gregtech.common.tileentities.machines.basic.MTENameRemover");
            GregTechAPI.METATILEENTITIES[DISTRIBUTOR] = allocate(
                "gregtech.common.tileentities.automation.MTEItemDistributor");
            GregTechAPI.METATILEENTITIES[MAINTENANCE] = allocate(
                "gregtech.api.metatileentity.implementations.MTEHatchMaintenance");
            basic();
            distributor();
            maintenance();
            enhancedLimits();
            covers();
            appearance();
        } catch (Exception failure) {
            throw new AssertionError("GT private schema transforms", failure);
        } finally {
            GregTechAPI.METATILEENTITIES[BASIC] = oldBasic;
            GregTechAPI.METATILEENTITIES[DISTRIBUTOR] = oldDistributor;
            GregTechAPI.METATILEENTITIES[MAINTENANCE] = oldMaintenance;
            Thread.currentThread()
                .setName(threadName);
        }
    }

    private static void basic() throws Exception {
        NBTTagCompound tag = machine(BASIC, 3);
        tag.setInteger("mMainFacing", 2);
        tag.setByte("mConnections", (byte) 0x84);
        tag.setByteArray("mRedstoneSided", new byte[] { 0, 1, 2, 3, 4, 5 });
        NBTTagCompound original = (NBTTagCompound) tag.copy();
        CutTransforms.State raw = state(tag);
        HologramCutTransformDirections.apply(raw, pose(false));
        SimpleAssert.eq(4, (int) raw.tile.getShort("mFacing"), "GT output SOUTH rotates WEST");
        SimpleAssert.eq(5, raw.tile.getInteger("mMainFacing"), "GT main NORTH rotates EAST independently");
        SimpleAssert.eq(0xa0, raw.tile.getByte("mConnections") & 255, "GT side masks preserve high flags");
        SimpleAssert
            .eq((byte) 2, raw.tile.getByteArray("mRedstoneSided")[5], "GT world side array follows main rotation");
        HologramBlockTransforms.Data blueprint = HologramBlockTransforms
            .turn(new HologramBlockTransforms.Data(null, "", 0, tag, null), false);
        SimpleAssert.eq(raw.tile, blueprint.tile, "raw and blueprint share exact GT schema");
        SimpleAssert.eq(original, tag, "both entry points preserve original native payload");
        for (int invalid : new int[] { -1, 0, 1, 6, 127 }) {
            NBTTagCompound unknown = machine(BASIC, 3);
            unknown.setInteger("mMainFacing", invalid);
            HologramBlockTransformGregTech.apply(unknown, pose(false));
            SimpleAssert.eq(invalid, unknown.getInteger("mMainFacing"), "unrecognized native main face is retained");
        }
        NBTTagCompound wrongType = machine(BASIC, 3);
        wrongType.setString("mMainFacing", "private");
        HologramBlockTransformGregTech.apply(wrongType, pose(false));
        SimpleAssert
            .eq("private", wrongType.getString("mMainFacing"), "wrong-type spatial-looking tag is not converted");
        NBTTagCompound vertical = machine(BASIC, 3);
        vertical.setInteger("mMainFacing", 2);
        rejectUnchanged(
            vertical,
            new CutTransforms.Transform(0, 1, 0, 0),
            "native basic machine main face cannot point UP");
        NBTTagCompound unrelated = machine(MAINTENANCE, 2);
        unrelated.setInteger("mMainFacing", 2);
        HologramBlockTransformGregTech.apply(unrelated, pose(false));
        SimpleAssert.eq(2, unrelated.getInteger("mMainFacing"), "mMainFacing is gated by BasicMachine prototype");
    }

    private static void distributor() throws Exception {
        NBTTagCompound tag = machine(DISTRIBUTOR, 3);
        tag.setByteArray("mItemsPerSide", new byte[] { 0, 0, 7, 0, 0, 0 });
        tag.setByte("mCurrentSide", (byte) 2);
        tag.setByte("mCurrentSideItemCount", (byte) 4);
        HologramBlockTransformGregTech.apply(tag, pose(false));
        SimpleAssert.eq((byte) 7, tag.getByteArray("mItemsPerSide")[5], "distributor NORTH quota moves EAST");
        SimpleAssert.eq((byte) 0, tag.getByteArray("mItemsPerSide")[2], "old distributor quota slot clears");
        SimpleAssert.eq(5, (int) tag.getByte("mCurrentSide"), "distributor cursor follows quota");
        SimpleAssert.eq(4, (int) tag.getByte("mCurrentSideItemCount"), "cursor progress count stays unchanged");
        for (int invalid : new int[] { -1, 6, 127 }) {
            tag.setByte("mCurrentSide", (byte) invalid);
            HologramBlockTransformGregTech.apply(tag, pose(false));
            SimpleAssert.eq(invalid, (int) tag.getByte("mCurrentSide"), "unknown distributor cursor remains unchanged");
        }
        NBTTagCompound item = new NBTTagCompound();
        item.setByteArray("mItemsPerSide", new byte[] { 0, 0, 9, 0, 0, 0 });
        HologramBlockTransforms.Data blueprint = HologramBlockTransforms
            .turn(new HologramBlockTransforms.Data(null, "", 0, machine(DISTRIBUTOR, 3), item), false);
        SimpleAssert.eq(
            (byte) 9,
            blueprint.item.getByteArray("mItemsPerSide")[5],
            "drop settings use captured tile prototype context");
        NBTTagCompound unrelated = machine(BASIC, 3);
        unrelated.setByteArray("mItemsPerSide", new byte[] { 0, 0, 9, 0, 0, 0 });
        unrelated.setByte("mCurrentSide", (byte) 2);
        HologramBlockTransformGregTech.apply(unrelated, pose(false));
        SimpleAssert.eq(
            (byte) 9,
            unrelated.getByteArray("mItemsPerSide")[2],
            "same-name private array in another MTE is not guessed");
        SimpleAssert
            .eq(2, (int) unrelated.getByte("mCurrentSide"), "same-name private cursor in another MTE is not guessed");
    }

    private static void maintenance() throws Exception {
        NBTTagCompound up = machine(MAINTENANCE, 1);
        up.setByte("mRotation", (byte) 0);
        HologramBlockTransformGregTech.apply(up, pose(false));
        SimpleAssert.eq(1, (int) up.getShort("mFacing"), "upward maintenance front stays UP under Y rotation");
        SimpleAssert.eq(1, (int) up.getByte("mRotation"), "native UP/NORMAL becomes UP/CLOCKWISE");
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS)
            for (Rotation rotation : Rotation.values()) for (boolean vertical : new boolean[] { false, true }) {
                NBTTagCompound tag = machine(MAINTENANCE, face.ordinal());
                tag.setByte("mRotation", (byte) rotation.getIndex());
                ExtendedFacing before = ExtendedFacing.of(face, rotation, Flip.NONE);
                HologramBlockTransformGregTech.apply(tag, pose(vertical));
                ExtendedFacing after = ExtendedFacing.of(
                    ForgeDirection.getOrientation(tag.getShort("mFacing")),
                    Rotation.byIndex(tag.getByte("mRotation")),
                    Flip.NONE);
                SimpleAssert.eq(
                    pose(vertical).direction(
                        before.getRelativeLeftInWorld()
                            .ordinal()),
                    after.getRelativeLeftInWorld()
                        .ordinal(),
                    "maintenance native left vector rotates");
                SimpleAssert.eq(
                    pose(vertical).direction(
                        before.getRelativeDownInWorld()
                            .ordinal()),
                    after.getRelativeDownInWorld()
                        .ordinal(),
                    "maintenance native down vector rotates");
            }
        NBTTagCompound reflected = machine(MAINTENANCE, 2);
        reflected.setByte("mRotation", (byte) 0);
        rejectUnchanged(reflected, new CutTransforms.Transform(0, 0, 0, 1), "native maintenance has no flipped frame");
        reflected.setByte("mRotation", (byte) 127);
        rejectUnchanged(reflected, pose(false), "unknown native roll is retained rather than normalized");
    }

    @SuppressWarnings("unchecked")
    private static void covers() throws Exception {
        NBTTagCompound unknown = machine(BASIC, 3);
        NBTTagList privateCovers = new NBTTagList();
        privateCovers.appendTag(new net.minecraft.nbt.NBTTagString("unknown-native-payload"));
        unknown.setTag("gt.covers", privateCovers);
        rejectUnchanged(unknown, pose(false), "unknown cover list types retain the complete native payload");
        Field registrationsField = CoverRegistry.class.getDeclaredField("covers");
        registrationsField.setAccessible(true);
        Map<Object, Object> registrations = (Map<Object, Object>) registrationsField.get(null);
        ItemStack monitor = new ItemStack(Items.paper, 1, 31020), unrelated = new ItemStack(Items.paper, 1, 31021);
        Object monitorKey = new GTItemStack(monitor), unrelatedKey = new GTItemStack(unrelated);
        Object oldMonitorRegistration = registrations.remove(monitorKey),
            oldUnrelated = registrations.remove(unrelatedKey);
        Field itemStack = ItemList.class.getDeclaredField("mStack"),
            notSet = ItemList.class.getDeclaredField("mHasNotBeenSet");
        itemStack.setAccessible(true);
        notSet.setAccessible(true);
        Object oldStack = itemStack.get(ItemList.Cover_FluidStorageMonitor);
        boolean oldNotSet = notSet.getBoolean(ItemList.Cover_FluidStorageMonitor);
        final int[] factories = { 0 };
        try {
            ItemList.Cover_FluidStorageMonitor.set(monitor);
            ITexture texture = (ITexture) Proxy.newProxyInstance(
                ITexture.class.getClassLoader(),
                new Class<?>[] { ITexture.class },
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null);
            // Factories are intentionally poisonous: identity inspection must never construct even a known cover.
            CoverRegistry.registerCover(monitor, texture, context -> {
                factories[0]++;
                throw new AssertionError("cover factory called");
            });
            CoverRegistry.registerCover(unrelated, texture, context -> {
                factories[0]++;
                return new Cover(context, null);
            });
            NBTTagCompound tag = machine(BASIC, 3);
            NBTTagCompound row = cover(monitor, 1, 2), other = cover(unrelated, 3, 2);
            NBTTagList covers = new NBTTagList();
            covers.appendTag(row);
            covers.appendTag(other);
            tag.setTag("gt.covers", covers);
            HologramBlockTransformGregTech.apply(tag, pose(false));
            NBTTagCompound changed = tag.getTagList("gt.covers", 10)
                .getCompoundTagAt(0);
            SimpleAssert.eq(1, (int) changed.getByte("s"), "monitor installation UP remains UP");
            SimpleAssert.eq(
                5,
                (int) changed.getCompoundTag("d")
                    .getByte("coverSide"),
                "independent monitor NORTH lookup becomes EAST");
            SimpleAssert.eq(
                2,
                (int) tag.getTagList("gt.covers", 10)
                    .getCompoundTagAt(1)
                    .getCompoundTag("d")
                    .getByte("coverSide"),
                "another registered cover's same-name data is preserved");
            SimpleAssert.eq(
                "private",
                changed.getCompoundTag("d")
                    .getString("inventory"),
                "cover inventory/configuration is untouched");
            SimpleAssert.eq(0, factories[0], "no cover factory or native NBT reader ran");
            NBTTagCompound unavailable = machine(BASIC, 3);
            NBTTagList missing = new NBTTagList();
            missing.appendTag(cover(monitor, 2, 2));
            unavailable.setTag("gt.covers", missing);
            registrations.remove(monitorKey);
            rejectUnchanged(
                unavailable,
                pose(false),
                "missing known monitor registration is explicit, atomic unsupported");
        } finally {
            registrations.remove(monitorKey);
            registrations.remove(unrelatedKey);
            if (oldMonitorRegistration != null) registrations.put(monitorKey, oldMonitorRegistration);
            if (oldUnrelated != null) registrations.put(unrelatedKey, oldUnrelated);
            itemStack.set(ItemList.Cover_FluidStorageMonitor, oldStack);
            notSet.setBoolean(ItemList.Cover_FluidStorageMonitor, oldNotSet);
        }
    }

    private static void enhancedLimits() throws Exception {
        IMetaTileEntity prototype = allocate("gregtech.common.tileentities.machines.multi.MTEOilDrill1");
        Method initial = MTEEnhancedMultiBlockBase.class.getDeclaredMethod("getInitialAlignmentLimits");
        initial.setAccessible(true);
        Field limits = MTEEnhancedMultiBlockBase.class.getDeclaredField("mLimits");
        limits.setAccessible(true);
        limits.set(prototype, initial.invoke(prototype));
        NBTTagCompound tag = machine(0, 2);
        tag.setByte("eRotation", (byte) 0);
        tag.setByte("eFlip", (byte) 0);
        NBTTagCompound before = (NBTTagCompound) tag.copy();
        try {
            HologramBlockTransformGregTech.apply(tag, new CutTransforms.Transform(0, 1, 0, 0), prototype);
            throw new AssertionError("native driller rejects a vertical front and roll");
        } catch (IOException expected) {
            SimpleAssert.eq(before, tag, "native alignment limits reject atomically without corrected/partial frame");
        }
        HologramBlockTransformGregTech.apply(tag, pose(false), prototype);
        SimpleAssert.eq(5, (int) tag.getShort("mFacing"), "native driller accepts its horizontal Y rotation");
    }

    private static void appearance() throws Exception {
        NBTTagCompound shape = new NBTTagCompound();
        shape.setByte("side", (byte) 0);
        shape.setByte("turn", (byte) 0);
        shape.setInteger("Disconnected", 0x100 | 4);
        NBTTagCompound original = (NBTTagCompound) shape.copy();
        HologramBlockTransformAdapters.architecture(shape, false);
        SimpleAssert.eq(
            0x100 | 32,
            shape.getInteger("Disconnected"),
            "Architecture world NORTH disconnection rotates EAST, flags retained");
        for (int i = 0; i < 3; i++) HologramBlockTransformAdapters.architecture(shape, false);
        SimpleAssert.eq(original, shape, "four Architecture shape and world mask turns restore source");
        NBTTagCompound facade = new NBTTagCompound();
        facade.setString("facadeId", "minecraft:log");
        facade.setInteger("facadeMeta", 6);
        HologramBlockTransformAdapters.enderIo(facade, false);
        SimpleAssert.eq(10, facade.getInteger("facadeMeta"), "EnderIO X-axis birch facade rotates to Z-axis birch");
        facade.setInteger("facadeMeta", 3);
        HologramBlockTransformAdapters.enderIo(facade, true);
        SimpleAssert.eq(11, facade.getInteger("facadeMeta"), "EnderIO Y-axis jungle facade tilts to Z-axis jungle");
        facade.setInteger("facadeMeta", 15);
        HologramBlockTransformAdapters.enderIo(facade, true);
        SimpleAssert.eq(15, facade.getInteger("facadeMeta"), "all-bark facade is orientation invariant");
        facade.setString("facadeId", "minecraft:stone");
        facade.setInteger("facadeMeta", 6);
        HologramBlockTransformAdapters.enderIo(facade, false);
        SimpleAssert.eq(6, facade.getInteger("facadeMeta"), "unknown facade metadata is not inferred as an axis");
    }

    private static NBTTagCompound cover(ItemStack stack, int side, int lookup) {
        NBTTagCompound row = new NBTTagCompound(), data = new NBTTagCompound();
        row.setInteger("id", GTUtility.stackToInt(stack));
        row.setByte("s", (byte) side);
        data.setByte("coverSide", (byte) lookup);
        data.setString("inventory", "private");
        row.setTag("d", data);
        return row;
    }

    private static void rejectUnchanged(NBTTagCompound tag, CutTransforms.Transform pose, String message)
        throws Exception {
        NBTTagCompound original = (NBTTagCompound) tag.copy();
        try {
            HologramBlockTransformGregTech.apply(tag, pose);
            throw new AssertionError(message);
        } catch (IOException expected) {
            SimpleAssert.eq(original, tag, message + ": no partial spatial edits");
        }
    }

    private static IMetaTileEntity allocate(String name) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (IMetaTileEntity) ((sun.misc.Unsafe) field.get(null)).allocateInstance(Class.forName(name));
    }

    private static NBTTagCompound machine(int id, int front) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "BaseMetaTileEntity");
        tag.setInteger("mID", id);
        tag.setShort("mFacing", (short) front);
        tag.setString("inventory", "do not touch");
        return tag;
    }

    private static CutTransforms.State state(NBTTagCompound tag) {
        return new CutTransforms.State(Blocks.stone, 0, tag, 0, 64, 0, 10, 70, 10);
    }

    private static CutTransforms.Transform pose(boolean vertical) {
        return new CutTransforms.Transform(vertical ? 0 : 1, vertical ? 1 : 0, 0, 0);
    }
}
