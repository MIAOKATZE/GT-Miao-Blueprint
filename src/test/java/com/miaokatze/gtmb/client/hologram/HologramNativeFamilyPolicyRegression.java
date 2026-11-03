package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Internal family traits must compose with, rather than replace, a community renderer adapter. */
final class HologramNativeFamilyPolicyRegression {

    private HologramNativeFamilyPolicyRegression() {}

    static void run() {
        try {
            communityPriorityAndDetachedRead();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("native family policy fixture", failure);
        }
    }

    private static void communityPriorityAndDetachedRead() throws ReflectiveOperationException {
        Block block = net.minecraft.init.Blocks.stone;
        HologramNativeRenderRegistry.Adapter family = new HologramNativeRenderRegistry.Adapter() {

            public boolean supports(Block candidate) {
                return candidate == block;
            }

            public boolean detachedRead(Block candidate) {
                return true;
            }

            public boolean doubleSided(Block candidate) {
                return true;
            }

            public TileEntity createTile(Block candidate, int metadata, World world) {
                throw new AssertionError("community factory must precede the built-in family");
            }
        };
        HologramNativeRenderRegistry.Adapter community = new HologramNativeRenderRegistry.Adapter() {

            public boolean supports(Block candidate) {
                return candidate == block;
            }

            public TileEntity createTile(Block candidate, int metadata, World world) {
                return new DetachedProbe();
            }
        };
        HologramNativeRenderRegistry.Adapter later = candidate -> candidate == block;
        HologramNativeRenderRegistry.registerBuiltin(family);
        HologramNativeRenderRegistry.register(community);
        HologramNativeRenderRegistry.register(later);
        try {
            SimpleAssert.that(
                HologramNativeRenderRegistry.adapter(block) == community,
                "first community adapter precedes both later community adapters and built-in family modules");
            SimpleAssert.that(
                HologramNativeRenderRegistry.doubleSided(block),
                "built-in slope policy survives a community renderer override");
            SimpleAssert.that(
                community.renderScope(block, null, null) == null,
                "community adapters do not acquire the built-in renderer scope implicitly");
            TileEntity[] tile = { null };
            HologramRenderWorld world = world(block, tile);
            NBTTagCompound payload = new NBTTagCompound();
            payload.setInteger("x", 900);
            tile[0] = HologramNativeTiles.create(block, 3, payload, world, 7, 68, -3);
            SimpleAssert.that(
                ((DetachedProbe) tile[0]).detachedDuringRead,
                "family policy keeps even a community tile detached during deserialization");
            SimpleAssert.that(tile[0].getWorldObj() == world, "completed visual tile binds to its detached world");
            SimpleAssert.eq(900, payload.getInteger("x"), "native hydration leaves the source payload intact");
            metadataScope(world, tile[0]);
            HologramNativeRenderRegistry.unregister(community);
            SimpleAssert.that(
                HologramNativeRenderRegistry.adapter(block) == later,
                "removing a community adapter reveals the next community adapter");
            HologramNativeRenderRegistry.unregister(later);
            SimpleAssert.that(
                HologramNativeRenderRegistry.adapter(block) == family,
                "built-in family remains available after all community overrides are removed");
        } finally {
            HologramNativeRenderRegistry.unregister(community);
            HologramNativeRenderRegistry.unregister(later);
            HologramNativeRenderRegistry.unregisterBuiltin(family);
        }
        SimpleAssert.that(
            !HologramNativeRenderRegistry.detachedRead(block, null) && !HologramNativeRenderRegistry.doubleSided(block),
            "closing a family module also removes its hydration and culling policies");
    }

    private static void metadataScope(HologramRenderWorld world, TileEntity tile) {
        SimpleAssert.that(
            HologramNativeRenderRegistry.metadataOverride(new TileEntity()) == null,
            "a tile outside the detached render world receives no metadata scope");
        TileEntity impostor = new TileEntity();
        impostor.setWorldObj(world);
        impostor.xCoord = tile.xCoord;
        impostor.yCoord = tile.yCoord;
        impostor.zCoord = tile.zCoord;
        expectInvalidScope(impostor);
        SimpleAssert.that(
            !world.setBlockMetadataWithNotify(7, 68, -3, 11, 4),
            "flags 4 cannot write metadata without a reviewed scope");
        HologramNativeRenderRegistry.RenderScope scope = HologramNativeRenderRegistry.metadataOverride(tile);
        try {
            SimpleAssert.that(scope.access() == world, "scope exposes the same bounded render world");
            expectInvalidScope(tile);
            SimpleAssert.that(
                !world.setBlockMetadataWithNotify(7, 68, -3, 11, 3)
                    && !world.setBlockMetadataWithNotify(8, 68, -3, 11, 4),
                "scope refuses notifying writes and writes at neighboring coordinates");
            SimpleAssert.that(
                world.setBlockMetadataWithNotify(7, 68, -3, 11, 4),
                "reviewed renderer can temporarily expose material metadata at its own tile");
            SimpleAssert.eq(11, world.getBlockMetadata(7, 68, -3), "scope metadata reaches renderer reads");
            SimpleAssert.eq(3, world.getBlockMetadata(8, 68, -3), "neighbor metadata stays unchanged");
        } finally {
            scope.close();
        }
        SimpleAssert.eq(3, world.getBlockMetadata(7, 68, -3), "closing scope restores snapshot metadata");
        SimpleAssert.that(
            !world.setBlockMetadataWithNotify(7, 68, -3, 11, 4),
            "metadata writes are refused again after scope close");
    }

    private static void expectInvalidScope(TileEntity tile) {
        try {
            HologramNativeRenderRegistry.metadataOverride(tile);
            throw new AssertionError("unmatched or nested metadata scope must be rejected");
        } catch (IllegalArgumentException expected) {}
    }

    private static HologramRenderWorld world(Block block, TileEntity[] tile) throws ReflectiveOperationException {
        // Match the existing isolation fixture: bypass World bootstrap and use only the audited snapshot methods.
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        HologramRenderWorld world = (HologramRenderWorld) unsafe.allocateInstance(HologramRenderWorld.class);
        IBlockAccess access = (IBlockAccess) Proxy.newProxyInstance(
            IBlockAccess.class.getClassLoader(),
            new Class<?>[] { IBlockAccess.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getTileEntity")) {
                    return (Integer) args[0] == 7 && (Integer) args[1] == 68 && (Integer) args[2] == -3 ? tile[0]
                        : null;
                }
                if (method.getName()
                    .equals("getBlockMetadata")) return 3;
                if (method.getName()
                    .equals("getBlock")) return block;
                throw new AssertionError("unexpected world access " + method.getName());
            });
        Field field = HologramRenderWorld.class.getDeclaredField("access");
        field.setAccessible(true);
        field.set(world, access);
        world.isRemote = true;
        return world;
    }

    public static final class DetachedProbe extends TileEntity {

        boolean detachedDuringRead;

        public void readFromNBT(NBTTagCompound payload) {
            detachedDuringRead = getWorldObj() == null;
            super.readFromNBT(payload);
        }
    }
}
