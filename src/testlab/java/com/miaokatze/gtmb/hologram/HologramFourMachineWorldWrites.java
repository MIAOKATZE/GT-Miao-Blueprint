// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.WorldServer;

/**
 * Passive native WorldServer notification observer; no event cancellation, replacement or synthetic acknowledgement.
 */
final class HologramFourMachineWorldWrites {

    private static final int MAX_WRITES = 4096;
    private final WorldServer world;
    private final IWorldAccess listener;
    private final int[] anchor;
    private final Map<String, NBTTagCompound> previous = new LinkedHashMap<>();
    private NBTTagList writes = new NBTTagList();
    private String label = "", failure = "";
    private long sequence;
    private boolean closed;

    HologramFourMachineWorldWrites(WorldServer world, int[] anchor) {
        this.world = world;
        this.anchor = anchor.clone();
        listener = (IWorldAccess) Proxy.newProxyInstance(
            IWorldAccess.class.getClassLoader(),
            new Class<?>[] { IWorldAccess.class },
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName()
                        .equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName()
                        .equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                    return "GTMB exact owned world write observer";
                }
                // Native IWorldAccess has coordinate update callbacks with three int arguments. Observing both
                // update/render notifications is safe; exact state deduplication removes duplicate notifications.
                if (!closed && args != null
                    && args.length == 3
                    && args[0] instanceof Integer
                    && args[1] instanceof Integer
                    && args[2] instanceof Integer) {
                    try {
                        observe((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                    } catch (Throwable problem) {
                        failure = problem.toString();
                    }
                }
                return null; // Audit failures never change the production mutation or its return value.
            });
        world.addWorldAccess(listener);
    }

    void arm(int[][] positions, String action) {
        require(
            !closed && positions.length <= HologramFourMachineSpec.MAX_TARGET_CELLS + 1,
            "Invalid passive write scope");
        previous.clear();
        writes = new NBTTagList();
        failure = "";
        label = action;
        for (int[] p : positions) {
            require(
                p.length == 3 && Math.abs(p[0] - anchor[0]) <= 16
                    && Math.abs(p[1] - anchor[1]) <= 12
                    && Math.abs(p[2] - anchor[2]) <= 16,
                "Passive observer escaped reviewed machine footprint");
            previous.put(key(p[0], p[1], p[2]), stable(HologramFourMachineWorldAudit.cell(world, p[0], p[1], p[2])));
        }
        previous.putIfAbsent(
            key(anchor[0], anchor[1], anchor[2]),
            stable(HologramFourMachineWorldAudit.cell(world, anchor[0], anchor[1], anchor[2])));
    }

    private void observe(int x, int y, int z) {
        String position = key(x, y, z);
        if (!previous.containsKey(position) || !failure.isEmpty()) return;
        NBTTagCompound actual = HologramFourMachineWorldAudit.cell(world, x, y, z);
        NBTTagCompound state = stable(actual);
        if (state.equals(previous.get(position))) return;
        if (writes.tagCount() >= MAX_WRITES) {
            failure = "Passive world write trace exceeded bound; evidence incomplete";
            return;
        }
        previous.put(position, state);
        actual.setLong("writeSequence", ++sequence);
        actual.setString("action", label);
        actual.setBoolean(
            "controllerFormedAtWrite",
            HologramFourMachineWorldAudit.cell(world, anchor[0], anchor[1], anchor[2])
                .getBoolean("naturalFormed"));
        writes.appendTag(actual);
    }

    NBTTagCompound snapshot() {
        NBTTagCompound result = new NBTTagCompound();
        result.setString(
            "source",
            "Actual IWorldAccess notifications after native world state writes; not BreakEvent order");
        result.setString("action", label);
        result.setString("failure", failure);
        result.setTag("writes", writes.copy());
        result.setBoolean("complete", failure.isEmpty());
        return result;
    }

    void close() {
        if (!closed) {
            closed = true;
            world.removeWorldAccess(listener);
        }
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static NBTTagCompound stable(NBTTagCompound n) {
        NBTTagCompound result = (NBTTagCompound) n.copy();
        result.removeTag("worldTick");
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
