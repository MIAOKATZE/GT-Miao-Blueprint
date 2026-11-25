// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.GregTechAPI;

/** Display-only evidence collected inside the existing, loaded-client-world declaration walk.
 * Counts refer to declared positions, not GT's server-owned formed flag or hatch membership. */
final class HologramLocalControllerSite {
    private int occupied, missing, different, unknown, hatches, matching;
    private final Map<IStructureElement<?>, Channel> declarations = new IdentityHashMap<>();
    private final Map<String, Integer> channels = new LinkedHashMap<>();

    String observe(IStructureElement<?> element, boolean unread, Block actual, int meta, ItemStack stack,
        int facing, Block expected, int expectedMeta, ItemStack wanted, int wantedFacing, boolean unsupported,
        boolean hatch) {
        if (element != null) {
            Channel channel = declarations.computeIfAbsent(element, Channel::read);
            if (channel != Channel.NONE) {
                int value = unread ? 0 : channel.value(stack);
                channels.merge(channel.id, value, (a, b) -> a.intValue() == b.intValue() ? a : 0);
            }
        }
        if (unread || actual == null) { unknown++; return "unsupported"; }
        if (actual != Blocks.air) {
            occupied++;
            if (hatch) hatches++;
        }
        if (unsupported || expected == null) { unknown++; return "unsupported"; }
        if (actual == Blocks.air && expected != Blocks.air) { missing++; return "missing"; }
        boolean same = actual == GregTechAPI.sBlockMachines || expected == GregTechAPI.sBlockMachines
            ? actual == expected && stack != null && wanted != null && HologramValues.same(stack, wanted)
                && (wantedFacing < 0 || facing == wantedFacing)
            : HologramBlockIdentity.sameForPlacement(actual, meta, expected, expectedMeta);
        if (same) { matching++; return "satisfied"; }
        different++;
        return "occupied";
    }

    void publish(NBTTagCompound header) {
        header.setInteger("siteOccupied", occupied);
        header.setInteger("siteMissing", missing);
        header.setInteger("siteDifferent", different);
        header.setInteger("siteUnknown", unknown);
        header.setInteger("siteMatching", matching);
        header.setInteger("siteHatches", hatches);
        NBTTagCompound observed = new NBTTagCompound();
        channels.forEach((id, value) -> { if (value > 0) observed.setInteger(id, value); });
        header.setTag("siteChannels", observed);
    }

    private static final class Channel {
        static final Channel NONE = new Channel("", java.util.Collections.emptyList(), false);
        final String id;
        final List<ItemStack> options;

        final boolean coil;
        private net.minecraft.item.Item lastItem;
        private int lastMeta, lastValue;

        Channel(String id, List<ItemStack> options, boolean coil) {
            this.id = id;
            this.options = options;
            this.coil = coil;
        }

        static Channel read(IStructureElement<?> element) {
            // A fork has alternatives, not one invertible material table. Do not flatten it or invoke a selector.
            String id = null;
            try {
                for (int i = 0; element != null && i < 12; i++) {
                    java.lang.reflect.Method factory = HologramDeclarationWrappers.factory(element.getClass());
                    if (factory == null) break;
                    Class<?> owner = factory.getDeclaringClass();
                    String op = factory.getName();
                    boolean structure = owner == com.gtnewhorizon.structurelib.structure.StructureUtility.class;
                    boolean gt = owner == gregtech.api.util.GTStructureUtility.class;
                    if (id != null && (structure && "ofBlocksTiered".equals(op) || gt && "ofCoil".equals(op)))
                        return new Channel(id, HologramDeclarationWrappers.materials(element), gt);
                    if (structure && "withChannel".equals(op)) {
                        if (id != null) break; // a nested selector changes which trigger actually governs this slot
                        id = HologramDeclarationWrappers.channel(element);
                    } else if (!(structure && ("onElementPass".equals(op) || "onElementFail".equals(op)
                        || "withDescription".equals(op)) || gt && "activeCoils".equals(op))) break;
                    element = HologramDeclarationWrappers.nativeBacking(element);
                }
            } catch (RuntimeException | ReflectiveOperationException | LinkageError unavailable) {
                // Optional/native declaration supplied no proven mapping.
            }
            // If the outer selector is known, an unprovable slot must also invalidate a positive value from its peers.
            return id == null ? NONE : new Channel(id, java.util.Collections.emptyList(), false);
        }

        int value(ItemStack stack) {
            if (stack == null || options.isEmpty()) return 0;
            boolean cacheable = !stack.hasTagCompound() && stack.getItem() != null;
            if (cacheable && stack.getItem() == lastItem && stack.getItemDamage() == lastMeta) return lastValue;
            int found = 0;
            for (int i = 0; i < options.size(); i++) {
                ItemStack option = options.get(i);
                if (HologramValues.same(stack, option) || coil && HologramValues.sameCoilLevel(stack, option)) {
                    if (found != 0) return 0;
                    found = i + 1;
                }
            }
            if (cacheable) {
                lastItem = stack.getItem();
                lastMeta = stack.getItemDamage();
                lastValue = found;
            }
            return found;
        }
    }
}
