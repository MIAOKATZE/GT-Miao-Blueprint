package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Observes native Forge cancellation for one placement call without changing or posting events. */
public final class HologramNativePlacementEvents {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    static {
        // One observer for the process; large blueprints never rebuild Forge's listener arrays per cell.
        MinecraftForge.EVENT_BUS.register(new HologramNativePlacementEvents());
    }

    private HologramNativePlacementEvents() {}

    static Scope open(World world, EntityPlayer actor, int x, int y, int z) {
        Scope scope = new Scope(CURRENT.get(), world, actor, x, y, z);
        CURRENT.set(scope);
        return scope;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void placed(BlockEvent.PlaceEvent event) {
        Scope scope = CURRENT.get();
        if (scope == null || event.world != scope.world
            || event.player != scope.actor
            || event.x != scope.x
            || event.y != scope.y
            || event.z != scope.z) return;
        if (scope.events == null) scope.events = new ArrayList<>();
        scope.events.add(event);
    }

    static final class Scope implements AutoCloseable {

        private Scope previous;
        private World world;
        private EntityPlayer actor;
        private final int x, y, z;
        private List<BlockEvent.PlaceEvent> events;

        private Scope(Scope previous, World world, EntityPlayer actor, int x, int y, int z) {
            this.previous = previous;
            this.world = world;
            this.actor = actor;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean cancelled() {
            // Read retained event objects after native placement returns, after every event handler has run.
            if (events != null) for (BlockEvent.PlaceEvent event : events) if (event.isCanceled()) return true;
            return false;
        }

        @Override
        public void close() {
            if (CURRENT.get() == this) {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            }
            if (events != null) events.clear();
            events = null;
            previous = null;
            world = null;
            actor = null;
        }
    }
}
