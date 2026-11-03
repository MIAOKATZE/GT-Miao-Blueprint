package com.miaokatze.gtmb.compat.carpenters.common;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.hologram.HologramNativePlacement;

/** Audited Carpenter persistence and physical materials; optional classes are resolved only for matching blocks. */
public final class CarpentersCompatibility {

    private CarpentersCompatibility() {}

    /** Called by common bootstrap only when Carpenter's Blocks is loaded; fixtures may install explicitly. */
    public static GTMBExtensions.Registration install() {
        HologramNativePlacement.Adapter adapter = new Placement();
        return GTMBExtensions.install("gtmb:carpenters", "native-placement", () -> {
            HologramNativePlacement.registerBuiltin("gtmb:carpenters", adapter);
            return () -> HologramNativePlacement.unregister("gtmb:carpenters", adapter);
        });
    }

    /** Type-only display admission is independent of placement and client renderer installation. */
    public static GTMBExtensions.Registration installDisplay() {
        return GTMBExtensions.nativeDisplay("gtmb:carpenters/display", tile -> {
            for (Class<?> type = tile.getClass(); type != null; type = type.getSuperclass()) if (type.getName()
                .startsWith("com.carpentersblocks.")) return true;
            return false;
        });
    }

    private static final class Placement implements HologramNativePlacement.FamilyPolicy {

        @Override
        public boolean supports(Block block) {
            return block.getClass()
                .getName()
                .startsWith("com.carpentersblocks.block.");
        }

        @Override
        public TileEntity create(Block block, int metadata, NBTTagCompound tag, World detachedWorld) throws Exception {
            if (block.getClass()
                .getName()
                .equals("com.carpentersblocks.block.BlockCarpentersSlope")) {
                Field registered = Class.forName("com.carpentersblocks.data.Slope")
                    .getDeclaredField("slopesList");
                registered.setAccessible(true);
                Object[] shapes = (Object[]) registered.get(null);
                int shape = tag.getInteger("cbMetadata");
                if (!tag.hasKey("cbMetadata", 99) || shape < 0 || shape >= shapes.length || shapes[shape] == null)
                    throw unsupported("unregistered Carpenter slope shape");
            }
            TileEntity tile = HologramNativePlacement.createDetached(block, metadata, tag, detachedWorld);
            if (tag.hasKey("cbOwner") && !tag.hasKey("cbOwner", 8))
                throw unsupported("invalid Carpenter ownership type");
            Set<Byte> retained = new HashSet<>();
            for (int i = 0; i < tag.getTagList("cbAttrList", 10)
                .tagCount(); i++) {
                byte attr = tag.getTagList("cbAttrList", 10)
                    .getCompoundTagAt(i)
                    .getByte("cbAttribute");
                if (attr < 0 || attr > 25 || !retained.add(attr))
                    throw unsupported("unregistered or duplicate Carpenter attribute");
                if (!(Boolean) call(tile, "hasAttribute", new Class<?>[] { byte.class }, attr))
                    throw unsupported("unregistered Carpenter material");
            }
            return tile;
        }

        @Override
        public List<ItemStack> materials(Block block, int metadata, TileEntity detachedTile) throws Exception {
            Item base = Item.getItemFromBlock(block);
            if (base == null) throw unsupported("Carpenter block item is missing");
            List<ItemStack> costs = new ArrayList<>();
            costs.add(new ItemStack(base, 1, block.damageDropped(metadata)));
            for (byte attr = 0; attr < 26; attr++)
                if ((Boolean) call(detachedTile, "hasAttribute", new Class<?>[] { byte.class }, attr)) costs
                    .add((ItemStack) call(detachedTile, "getAttributeForDrop", new Class<?>[] { byte.class }, attr));
            return costs;
        }

        @Override
        public void prepareActor(Block block, TileEntity detachedTile, EntityPlayerMP actor) throws Exception {
            if (actor == null) return;
            // Change only the new tile, preserving the native online UUID/offline player-name convention.
            Class<?> owner = Class.forName("com.carpentersblocks.util.protection.ProtectedObject");
            call(
                detachedTile,
                "setOwner",
                new Class<?>[] { owner },
                owner.getConstructor(EntityPlayer.class)
                    .newInstance(actor));
        }

        @Override
        public boolean matchesMaterial(Block block, ItemStack cost, ItemStack sample) {
            if (cost.isItemEqual(sample) && ItemStack.areItemStackTagsEqual(cost, sample)) return true;
            return block.getClass()
                .getName()
                .equals("com.carpentersblocks.block.BlockCarpentersSlope")
                && cost.getItem() == Item.getItemFromBlock(block)
                && sample.getItem() == cost.getItem()
                && cost.getItemDamage() == 0
                && sample.getItemDamage() >= 0
                && sample.getItemDamage() < 5
                && ItemStack.areItemStackTagsEqual(cost, sample);
        }
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass()
            .getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static IOException unsupported(String detail) {
        return new IOException("Unsupported native blueprint: " + detail);
    }
}
