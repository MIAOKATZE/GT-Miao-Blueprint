// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import crazypants.enderio.conduit.liquid.AbstractEnderLiquidConduit;

/** Four reviewed 2.10.55 identities; no references to classes absent in EnderIO 2.10.51. */
final class HologramEnderIoCapabilities {

    private static final String PREFIX = "crazypants.enderio.conduit.liquid.";
    private static final List<String> MODERN = Arrays.asList(
        PREFIX + "PrismaticNaquadahEnderLiquidConduit",
        PREFIX + "FluxedEnderLiquidConduit",
        PREFIX + "TranscendentEnderLiquidConduit",
        PREFIX + "CosmicEnderLiquidConduit");

    private HologramEnderIoCapabilities() {}

    static int modernMetadata(String type) {
        int index = MODERN.indexOf(type);
        return index < 0 ? -1 : index + 7;
    }

    static HologramNativeCapture.ConduitAdapter modernLiquidAdapter() {
        return new HologramNativeCapture.ConduitAdapter() {

            @Override
            public boolean supports(Object conduit) {
                return conduit != null && modernMetadata(
                    conduit.getClass()
                        .getName())
                    >= 0;
            }

            @Override
            public NBTTagCompound capture(Object conduit) throws IOException {
                if (!supports(conduit) || !(conduit instanceof AbstractEnderLiquidConduit))
                    throw new IOException("Unreviewed modern EnderIO conduit identity");
                AbstractEnderLiquidConduit liquid = (AbstractEnderLiquidConduit) conduit;
                requireMaterial(liquid);
                NBTTagCompound body = new NBTTagCompound();
                // These four classes inherit the reviewed read-only writer without overrides.
                liquid.writeToNBT(body);
                return body;
            }
        };
    }

    /** Preflight before TileConduitBundle.readFromNBT, which otherwise loads conduit classes itself. */
    static void requireRepresentable(NBTTagCompound bundle) throws IOException {
        if (!bundle.hasKey("conduits")) return;
        if (!bundle.hasKey("conduits", 9)) throw new IOException("Malformed EnderIO conduit list");
        NBTTagList conduits = (NBTTagList) bundle.getTag("conduits");
        if (conduits.tagCount() > 0 && conduits.func_150303_d() != 10)
            throw new IOException("Malformed EnderIO conduit rows");
        for (int i = 0; i < conduits.tagCount(); i++) {
            NBTTagCompound row = conduits.getCompoundTagAt(i);
            String type = row.getString("conduitType");
            if (!MODERN.contains(type)) continue; // Existing adapters/strict native validation retain their authority.
            if (!row.hasKey("conduitType", 8) || !row.hasKey("conduit", 10))
                throw new IOException("Malformed modern EnderIO conduit row " + i);
            try {
                // Targeted optional-type lookup is necessary because 2.10.51 has none of these four classes.
                Class<?> target = Class.forName(type, false, HologramEnderIoCapabilities.class.getClassLoader());
                AbstractEnderLiquidConduit conduit = target.asSubclass(AbstractEnderLiquidConduit.class)
                    .getConstructor()
                    .newInstance();
                requireMaterial(conduit);
            } catch (ReflectiveOperationException | LinkageError | ClassCastException unavailable) {
                throw new IOException("Target EnderIO cannot represent conduit row " + i + ": " + type, unavailable);
            }
        }
    }

    private static void requireMaterial(AbstractEnderLiquidConduit conduit) throws IOException {
        int expected = modernMetadata(
            conduit.getClass()
                .getName());
        if (expected < 0 || conduit.getMetadata() != expected)
            throw new IOException("Modern EnderIO conduit metadata identity changed");
        List<ItemStack> drops = conduit.getDrops();
        if (drops.size() != 1 || drops.get(0) == null
            || drops.get(0)
                .getItem() == null
            || drops.get(0).stackSize != 1
            || drops.get(0)
                .getItemDamage() != expected
            || Item.itemRegistry.getNameForObject(
                drops.get(0)
                    .getItem())
                == null)
            throw new IOException("Modern EnderIO conduit has no exact registered material identity");
    }
}
