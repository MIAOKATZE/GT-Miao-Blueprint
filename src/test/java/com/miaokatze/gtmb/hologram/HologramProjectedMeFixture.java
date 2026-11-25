// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.ILocatable;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;

/** Real terminal registry and auth / GTMB settlement with detached grid, energy and exact item storage. */
final class HologramProjectedMeFixture implements AutoCloseable {

    int stock = 3, modulates;
    private final Map<Long, ILocatable> locator;
    private final long key = java.util.concurrent.ThreadLocalRandom.current()
        .nextLong();
    private Item terminalItem = new Item();

    @SuppressWarnings("unchecked")
    HologramProjectedMeFixture(EntityPlayerMP actor, boolean allowed) throws ReflectiveOperationException {
        this(actor, allowed, null, null);
    }

    @SuppressWarnings("unchecked")
    HologramProjectedMeFixture(EntityPlayerMP actor, boolean allowed, ItemStack[] types, int[] quantities)
        throws ReflectiveOperationException {
        var monitor = proxy(IMEMonitor.class, (p, m, a) -> {
            if (!"extractItems".equals(m.getName())) throw new AssertionError(m.getName());
            var wanted = (IAEItemStack) a[0];
            if (!(a[2] instanceof PlayerSource source) || source.player != actor
                || wanted.getItemStack()
                    .hasTagCompound()
                || !allowed) throw new AssertionError("inexact/unauthorized ME extraction");
            int at = -1;
            if (types != null) {
                for (int k = 0; k < types.length; k++) if (wanted.getItemStack()
                    .isItemEqual(types[k])) {
                        at = k;
                        break;
                    }
                if (at < 0) return null;
            } else if (!wanted.getItemStack()
                .isItemEqual(new ItemStack(Blocks.stone))) throw new AssertionError("unexpected source item");
            int qty = (int) Math.min(at < 0 ? stock : quantities[at], wanted.getStackSize());
            if (qty == 0) return null;
            if (a[1] == Actionable.MODULATE) {
                modulates++;
                if (at < 0) stock -= qty;
                else quantities[at] -= qty;
            }
            return wanted.copy()
                .setStackSize(qty);
        });
        var storage = proxy(IStorageGrid.class, (p, m, a) -> monitor);
        var security = proxy(
            ISecurityGrid.class,
            (p, m, a) -> a[0] == actor && (allowed || a[1] != SecurityPermissions.EXTRACT));
        var energy = proxy(IEnergyGrid.class, (p, m, a) -> {
            if ("isNetworkPowered".equals(m.getName())) return true;
            if ("extractAEPower".equals(m.getName())) return a[0];
            throw new AssertionError(m.getName());
        });
        IGrid[] grid = new IGrid[1];
        IGridNode[] node = new IGridNode[1];
        var wap = proxy(IWirelessAccessPoint.class, (p, m, a) -> switch (m.getName()) {
            case "getActionableNode", "getGridNode" -> node[0];
            case "getGrid" -> grid[0];
            default -> throw new AssertionError(m.getName());
        });
        node[0] = proxy(IGridNode.class, (p, m, a) -> switch (m.getName()) {
            case "isActive" -> true;
            case "getGrid" -> grid[0];
            case "getMachine" -> wap;
            default -> throw new AssertionError(m.getName());
        });
        var machines = proxy(
            IMachineSet.class,
            (p, m, a) -> Collections.singleton(node[0])
                .iterator());
        grid[0] = proxy(IGrid.class, (p, m, a) -> switch (m.getName()) {
            case "getMachines" -> machines;
            case "getCache" -> a[0] == IStorageGrid.class ? storage : a[0] == ISecurityGrid.class ? security : energy;
            default -> throw new AssertionError(m.getName());
        });
        var reg = AEApi.instance()
            .registries();
        var table = reg.locatable()
            .getClass()
            .getDeclaredField("set");
        table.setAccessible(true);
        locator = (Map<Long, ILocatable>) table.get(reg.locatable());
        locator.put(
            key,
            (ILocatable) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { ILocatable.class, IGridHost.class },
                (p, m, a) -> switch (m.getName()) {
                case "getLocatableSerial" -> key;
                case "getGridNode" -> node[0];
                default -> throw new AssertionError(m.getName());
                }));
        reg.wireless()
            .registerWirelessHandler(proxy(IWirelessTermHandler.class, (p, m, a) -> switch (m.getName()) {
            case "canHandle" -> ((ItemStack) a[0]).getItem() == terminalItem;
            case "getEncryptionKey" -> Long.toString(key);
            case "hasInfinityRange", "hasInfinityPower", "hasPower" -> true;
            default -> throw new AssertionError(m.getName());
            }));
        ItemStack carried = new ItemStack(terminalItem);
        carried.setTagCompound(new NBTTagCompound());
        actor.inventory.mainInventory[2] = carried;
    }

    @Override
    public void close() {
        locator.remove(key);
        terminalItem = null;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
