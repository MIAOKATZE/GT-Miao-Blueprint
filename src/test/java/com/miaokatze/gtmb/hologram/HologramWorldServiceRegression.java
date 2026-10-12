package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityChest;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Exercises the production material-confirmation boundary without allowing any world writes. */
public final class HologramWorldServiceRegression {

    private HologramWorldServiceRegression() {}

    public static void run() {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            for (Field field : EntityPlayer.class.getDeclaredFields()) {
                if (field.getType() != GameProfile.class) continue;
                field.setAccessible(true);
                field.set(
                    player,
                    new GameProfile(UUID.fromString("204d8323-b5d7-42db-8d11-273ce72bc4a5"), "WorldFlowFixture"));
            }
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            player.worldObj = world;
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.allowEdit = true;
            player.posY = 64;
            player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            Class<?> type = Class.forName(HologramService.class.getName() + "$Session");
            Constructor<?> ctor = type.getDeclaredConstructor(
                EntityPlayerMP.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
            ctor.setAccessible(true);
            Object session = ctor.newInstance(player, 0, 64, 0, false, 2);
            set(session, "virtual", true);
            set(session, "virtualPlacement", true);
            set(session, "blueprintAwaitingConfirmation", true);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.name = "confirmation fixture";
            preset.controllerType = "area";
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, false));
            set(session, "virtualPreset", preset);
            HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
            settings.main = false;
            settings.containers = false;
            settings.me = false;
            set(session, "materialContext", new HologramMaterials.Context(player, settings));
            Method populate = HologramService.class.getDeclaredMethod("populateVirtual", type);
            populate.setAccessible(true);
            populate.invoke(null, session);
            replacementActorCannotReuseSession(unsafe, player, world, ctor);
            Method action = HologramService.class
                .getDeclaredMethod("blueprintAction", type, NBTTagCompound.class, String.class);
            action.setAccessible(true);
            NBTTagCompound request = new NBTTagCompound();
            request.setString("session", (String) get(session, "id"));
            request.setLong("planRevision", (Long) get(session, "planRevision"));
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "moving preview cannot confirm construction");
            SimpleAssert.eq(false, get(session, "virtualLocked"), "rejected confirm never locks position");
            set(session, "virtualLocked", true);
            request.setString("session", "stale-session");
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "stale session cannot confirm construction");
            request.setString("session", (String) get(session, "id"));
            request.setLong("planRevision", (Long) get(session, "planRevision") - 1);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "stale material plan cannot start construction");
            request.setLong("planRevision", (Long) get(session, "planRevision"));
            request.setBoolean("forceExplicit", true);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "first explicit force has no server authority");
            SimpleAssert
                .eq(true, get(session, "forcePlacementReady"), "missing material issues second-review capability");
            SimpleAssert
                .eq(true, get(session, "blueprintAwaitingConfirmation"), "shortage keeps material review active");
            request.setBoolean("forceExplicit", false);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "repeated ordinary packet cannot exercise force capability");
            request.setBoolean("forceExplicit", true);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(1, get(session, "job"), "second explicit review schedules protected partial construction");
            SimpleAssert
                .eq(true, get(session, "virtualLocked"), "material confirmation keeps selected position locked");
            SimpleAssert.eq(
                false,
                get(session, "blueprintAwaitingConfirmation"),
                "successful material confirmation closes review");
            matchingTileNeedsNoHarvestAuthority(session, world);
            wholePlacementDoesNotFollowInspection(session);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            throw new AssertionError("production world confirmation regression", cause);
        }
    }

    private static void replacementActorCannotReuseSession(sun.misc.Unsafe unsafe, EntityPlayerMP fixture,
        HologramServiceEditRegression.ReadOnlyWorld world, Constructor<?> constructor)
        throws ReflectiveOperationException {
        UUID identity = UUID.fromString("60950aae-0fc1-48a7-9b91-1fd30f61a9a5");
        GameProfile profile = new GameProfile(identity, "ReconnectFixture");
        EntityPlayerMP owner = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        EntityPlayerMP replacement = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        for (EntityPlayerMP actor : new EntityPlayerMP[] { owner, replacement }) {
            for (Field field : EntityPlayer.class.getDeclaredFields()) {
                if (field.getType() != GameProfile.class) continue;
                field.setAccessible(true);
                field.set(actor, profile);
            }
            for (Field field : Entity.class.getDeclaredFields()) {
                if (field.getType() != UUID.class) continue;
                field.setAccessible(true);
                field.set(actor, identity);
            }
            actor.worldObj = world;
            actor.inventory = new InventoryPlayer(actor);
            actor.capabilities = new PlayerCapabilities();
            actor.capabilities.allowEdit = true;
            actor.inventory.mainInventory[0] = fixture.getHeldItem()
                .copy();
        }
        SimpleAssert.that(
            owner != replacement && identity.equals(owner.getUniqueID()) && identity.equals(replacement.getUniqueID()),
            "replacement actor has the same nonnull player UUID");
        Object oldSession = constructor.newInstance(owner, 0, 64, 0, false, 2);
        set(oldSession, "open", true);
        Field sessionsField = HologramService.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Object> sessions = (java.util.Map<UUID, Object>) sessionsField.get(null);
        Object previous = sessions.put(identity, oldSession);
        ItemStack ownerStack = owner.getHeldItem()
            .copy(),
            replacementStack = replacement.getHeldItem()
                .copy();
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", (String) get(oldSession, "id"));
        request.setString("op", "close");
        request.setLong("uiSequence", 73);
        try {
            HologramService.handle(replacement, request);
            SimpleAssert.eq(0L, get(oldSession, "uiSequence"), "replacement actor cannot acknowledge the old session");
            SimpleAssert.eq(true, get(oldSession, "open"), "replacement actor cannot close the old session");
            SimpleAssert.that(
                get(oldSession, "materialContext") == null,
                "replacement actor cannot bind its inventory to the old session");
            SimpleAssert.that(
                ItemStack.areItemStacksEqual(ownerStack, owner.getHeldItem())
                    && ItemStack.areItemStacksEqual(replacementStack, replacement.getHeldItem()),
                "rejected replacement actor preserves both tool inventories");
        } finally {
            if (previous == null) sessions.remove(identity);
            else sessions.put(identity, previous);
        }
    }

    private static void matchingTileNeedsNoHarvestAuthority(Object session,
        HologramServiceEditRegression.ReadOnlyWorld world) throws ReflectiveOperationException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.controllerType = "area";
        preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.chest), 1, 0, 0, -1, false));
        set(session, "virtualPreset", preset);
        world.occupied = Blocks.chest;
        world.occupiedX = 1;
        world.occupant = new TileEntityChest();
        TileEntityChest original = (TileEntityChest) world.occupant;
        Class<?> type = session.getClass();
        Method populate = HologramService.class.getDeclaredMethod("populateVirtual", type);
        populate.setAccessible(true);
        populate.invoke(null, session);
        HologramCapture.Cell cell = ((HologramCapture) get(session, "capture")).cells.get(0);
        Method harvest = HologramService.class.getDeclaredMethod("recoverable", type, HologramCapture.Cell.class);
        harvest.setAccessible(true);
        SimpleAssert
            .eq(false, harvest.invoke(null, session, cell), "ordinary chest has no destructive harvest authority");
        Method classify = HologramService.class
            .getDeclaredMethod("classify", type, HologramCapture.Cell.class, int.class);
        classify.setAccessible(true);
        SimpleAssert.eq(
            "satisfied",
            classify.invoke(null, session, cell, 0),
            "already matching placed chest is kept during a partial job retry");
        SimpleAssert.that(world.occupant == original, "classification preserves the original tile instance");
        world.occupied = Blocks.furnace;
        SimpleAssert.eq(
            "blocked",
            classify.invoke(null, session, cell, 0),
            "unmatched occupied tile still blocks construction without replacement authority");
        world.occupied = Blocks.chest;
        set(session, "mode", 2);
        SimpleAssert.eq(
            "protected",
            classify.invoke(null, session, cell, 0),
            "matching tile cannot acquire demolition authority from keep classification");
    }

    private static void wholePlacementDoesNotFollowInspection(Object session) throws ReflectiveOperationException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        HologramPresets.Structure a = new HologramPresets.Structure();
        a.id = "viewed";
        a.enabled = true;
        HologramPresets.Structure b = new HologramPresets.Structure();
        b.id = "other";
        b.enabled = true;
        HologramPresets.Structure disabled = new HologramPresets.Structure();
        disabled.id = "disabled";
        disabled.enabled = false;
        preset.structures.add(a);
        preset.structures.add(b);
        preset.structures.add(disabled);
        HologramCapture capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        HologramCapture.Cell viewed = new HologramCapture.Cell(null, 0, 64, 0);
        viewed.structureId = a.id;
        HologramCapture.Cell other = new HologramCapture.Cell(null, 1, 65, 0);
        other.structureId = b.id;
        HologramCapture.Cell turnedOff = new HologramCapture.Cell(null, 2, 64, 0);
        turnedOff.structureId = disabled.id;
        capture.cells.add(viewed);
        capture.cells.add(other);
        capture.cells.add(turnedOff);
        set(session, "capture", capture);
        set(session, "virtualPreset", preset);
        set(session, "virtual", true);
        set(session, "virtualPlacement", true);
        set(session, "virtualLocked", true);
        set(session, "scope", 2);
        set(session, "selected", 0);
        set(session, "blueprintActiveStructure", a.id);
        @SuppressWarnings("unchecked")
        java.util.Set<Integer> selection = (java.util.Set<Integer>) get(session, "selectedIndices");
        selection.clear();
        selection.add(0);
        Method scope = HologramService.class
            .getDeclaredMethod("inScope", session.getClass(), HologramCapture.Cell.class, int.class);
        scope.setAccessible(true);
        SimpleAssert.eq(
            true,
            scope.invoke(null, session, other, 1),
            "locked placement reviews the other structure even while inspecting one selected cell");
        Method edit = HologramService.class
            .getDeclaredMethod("editableStructureCell", session.getClass(), HologramCapture.Cell.class);
        edit.setAccessible(true);
        SimpleAssert.eq(
            false,
            edit.invoke(null, session, other),
            "whole placement never grants cross-structure edit authority");
        SimpleAssert
            .eq(false, scope.invoke(null, session, turnedOff, 2), "explicit disabled structure remains excluded");
        other.excluded = true;
        SimpleAssert
            .eq(false, scope.invoke(null, session, other, 1), "explicit cell exclusions still protect world positions");
        other.excluded = false;
        set(session, "scope", 1);
        set(session, "layer", 0);
        SimpleAssert.eq(
            true,
            scope.invoke(null, session, other, 1),
            "preview layer cannot hide other blueprint rows from review");
        set(session, "scope", 3);
        set(session, "selected", -1);
        selection.clear();
        SimpleAssert.eq(
            true,
            scope.invoke(null, session, other, 1),
            "stale family-selection view cannot shrink a locked placement");
        set(session, "virtualPlacement", false);
        set(session, "scope", 2);
        set(session, "selected", 0);
        selection.add(0);
        SimpleAssert.eq(
            false,
            scope.invoke(null, session, other, 1),
            "pure blueprint editing retains structure and selection isolation");
        set(session, "virtual", false);
        set(session, "virtualPreset", null);
        SimpleAssert.eq(false, scope.invoke(null, session, other, 1), "normal structure selection retains batch scope");
    }

    private static Object get(Object session, String key) throws ReflectiveOperationException {
        Field field = session.getClass()
            .getDeclaredField(key);
        field.setAccessible(true);
        return field.get(session);
    }

    private static void set(Object session, String key, Object value) throws ReflectiveOperationException {
        Field field = session.getClass()
            .getDeclaredField(key);
        field.setAccessible(true);
        field.set(session, value);
    }
}
