// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.world.World;

/** One synchronous native placement only; no player preference, world listener or background state is changed. */
public final class HologramConstructionSounds implements AutoCloseable {

    private static final ThreadLocal<HologramConstructionSounds> ACTIVE = new ThreadLocal<>();
    private final HologramConstructionSounds previous;
    private final World world;
    private final int x, y, z;
    private boolean closed;

    private HologramConstructionSounds(World world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    static HologramConstructionSounds enter(World world, int x, int y, int z) {
        if (world == null) throw new IllegalArgumentException("Placement sound scope requires its actual world");
        return new HologramConstructionSounds(world, x, y, z);
    }

    /** Called at the real World.playSoundEffect entry, before its IWorldAccess/sound-packet dispatch. */
    public static boolean suppressed(World world, double x, double y, double z) {
        HologramConstructionSounds active = ACTIVE.get();
        return active != null && active.world == world
            && x >= active.x
            && x < active.x + 1D
            && y >= active.y
            && y < active.y + 1D
            && z >= active.z
            && z < active.z + 1D;
    }

    @Override
    public void close() {
        if (closed) return;
        if (ACTIVE.get() != this)
            throw new IllegalStateException("Placement sound scopes must close on their owning thread in order");
        closed = true;
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }
}
