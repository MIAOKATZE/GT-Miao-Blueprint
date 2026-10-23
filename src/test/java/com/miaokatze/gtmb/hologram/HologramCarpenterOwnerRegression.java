package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Opt-in exact CarpentersBlocks 3.7.3 classes; no world, player input or block-placement claim. */
public final class HologramCarpenterOwnerRegression {

    private HologramCarpenterOwnerRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramCarpenterOwnerRegression.class, args)) return;
        // This isolated bootstrap has no sided delegate; supply only the native server-side query it needs.
        Class<?> sided = cpw.mods.fml.common.IFMLSidedHandler.class;
        field(cpw.mods.fml.common.FMLCommonHandler.class, "sidedDelegate").set(
            cpw.mods.fml.common.FMLCommonHandler.instance(),
            java.lang.reflect.Proxy
                .newProxyInstance(sided.getClassLoader(), new Class<?>[] { sided }, (proxy, method, arguments) -> {
                    if (method.getName()
                        .equals("getSide")) return cpw.mods.fml.relauncher.Side.SERVER;
                    throw new AssertionError("Unexpected native fixture side request: " + method.getName());
                }));
        Class<?> base = Class.forName("com.carpentersblocks.tileentity.TEBase");
        Method mapping = TileEntity.class.getDeclaredMethod("addMapping", Class.class, String.class);
        mapping.setAccessible(true);
        mapping.invoke(null, base, "TileEntityCarpentersBlock");
        Block block = (Block) Class.forName("com.carpentersblocks.block.BlockCoverable")
            .getConstructor(Material.class)
            .newInstance(Material.wood);
        Method bind = HologramNativePlacement.class
            .getDeclaredMethod("bindCarpenterOwner", Block.class, TileEntity.class, EntityPlayerMP.class);
        bind.setAccessible(true);
        for (boolean online : new boolean[] { false, true }) {
            DedicatedServer server = allocate(DedicatedServer.class);
            server.setOnlineMode(online);
            Actor actor = allocate(Actor.class);
            actor.identity = new UUID(333, 444);
            actor.name = "NewCarpenterOwner";
            field(EntityPlayerMP.class, "mcServer").set(actor, server);
            TileEntity source = prepared(base), target = prepared(base);
            NBTTagCompound before = snapshot(source);
            SimpleAssert.that(
                !HologramDeidentification.foreignOwner(block, before, actor.identity, actor.name),
                "actual Carpenter root ownership does not block level-zero import");
            SimpleAssert.that(
                !HologramDeidentification.foreignOwner(before, actor.identity, actor.name),
                "registered native alias has the same nonblocking ownership semantics");
            bind.invoke(null, block, target, actor);
            NBTTagCompound after = snapshot(target);
            SimpleAssert.eq(
                online ? actor.identity.toString() : actor.name,
                after.getString("cbOwner"),
                "real ProtectedObject chooses native online UUID or offline name");
            NBTTagCompound expected = (NBTTagCompound) before.copy();
            expected.setString("cbOwner", after.getString("cbOwner"));
            SimpleAssert.eq(expected, after, "native binding preserves all skin, shape, design and chisel data");
            SimpleAssert.eq(before, snapshot(source), "source tile is never changed by new placement ownership");
            before.setString("mOwnerUUID", new UUID(55, 66).toString());
            SimpleAssert.that(
                HologramDeidentification.foreignOwner(block, before, actor.identity, actor.name),
                "unrelated device identity still blocks even on a native Carpenter root");
        }
        HologramCarpentersCompatibilityRegression.run(base);
        System.out.println(
            "GTMB_CARPENTER_OWNER PASS native 3.7.3 online/offline binding and exact nonowner persistence; no world/GUI claim");
    }

    @SuppressWarnings("unchecked")
    private static TileEntity prepared(Class<?> base) throws Exception {
        TileEntity tile = (TileEntity) base.getConstructor()
            .newInstance();
        field(base, "cbOwner").set(tile, new UUID(11, 22).toString());
        field(base, "cbMetadata").setInt(tile, 19);
        field(base, "cbDesign").set(tile, "source-design");
        ((String[]) field(base, "cbChiselDesign").get(tile))[0] = "source-chisel";
        Object skin = Class.forName("com.carpentersblocks.util.Attribute")
            .getConstructor(ItemStack.class)
            .newInstance(new ItemStack(Blocks.wool, 1, 11));
        ((Map<Byte, Object>) field(base, "cbAttrMap").get(tile)).put((byte) 6, skin);
        return tile;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static NBTTagCompound snapshot(TileEntity tile) {
        NBTTagCompound result = new NBTTagCompound();
        tile.writeToNBT(result);
        return result;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type
            .cast(((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type));
    }

    private static final class Actor extends EntityPlayerMP {

        private UUID identity;
        private String name;

        private Actor() {
            super(null, null, null, null);
            throw new AssertionError("Constructor is not used by this isolated native identity fixture");
        }

        @Override
        public UUID getUniqueID() {
            return identity;
        }

        @Override
        public String getDisplayName() {
            return name;
        }
    }
}
