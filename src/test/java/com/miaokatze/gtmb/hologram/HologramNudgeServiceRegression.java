package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Calls the real server nudge entry with hostile packets; the fixture rejects every world write. */
public final class HologramNudgeServiceRegression {

    private HologramNudgeServiceRegression() {}

    public static void run() {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            for (Field field : EntityPlayer.class.getDeclaredFields()) if (field.getType() == GameProfile.class) {
                field.setAccessible(true);
                field.set(player, new GameProfile(UUID.randomUUID(), "LockedNudgeFixture"));
            }
            ReadOnlyNudgeWorld world = (ReadOnlyNudgeWorld) unsafe.allocateInstance(ReadOnlyNudgeWorld.class);
            world.rejectX = Integer.MIN_VALUE;
            world.occupiedX = Integer.MIN_VALUE;
            player.worldObj = world;
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.allowEdit = true;
            player.posY = 64;
            player.inventory.mainInventory[0] = new ItemStack(new ItemNekoHologramProjector());
            player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 3);
            Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
            Constructor<?> ctor = type.getDeclaredConstructor(
                EntityPlayerMP.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
            ctor.setAccessible(true);
            Object session = ctor.newInstance(player, 0, 64, 0, false, 1);
            set(session, "virtual", true);
            set(session, "virtualPlacement", true);
            set(session, "virtualLocked", true);
            set(session, "blueprintAwaitingConfirmation", true);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.controllerType = "area";
            preset.name = "nudge fixture";
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
            set(session, "virtualPreset", preset);
            HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
            settings.main = true;
            settings.containers = settings.me = false;
            set(session, "materialContext", new HologramMaterials.Context(player, settings));
            java.lang.reflect.Method populate = HologramBlueprintProjection.class
                .getDeclaredMethod("populateVirtual", type);
            populate.setAccessible(true);
            populate.invoke(null, session);
            Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
            sessionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> sessions = (Map<UUID, Object>) sessionsField.get(null);
            Object prior = sessions.put(player.getUniqueID(), session);
            try {
                NBTTagCompound request = request(session, 1, 0, 0);
                Object capture = get(session, "capture");
                request.setInteger("dx", Integer.MIN_VALUE);
                HologramService.nudgeWorldPosition(player, request);
                SimpleAssert.that(get(session, "capture") == capture, "overflow displacement preserves exact draft");
                request = request(session, 1, 1, 0);
                HologramService.nudgeWorldPosition(player, request);
                request = request(session, 1, 0, 0);
                request.setDouble("dx", 1);
                HologramService.nudgeWorldPosition(player, request);
                SimpleAssert.eq(0, get(session, "x"), "diagonal and non-integer offsets are rejected");
                for (int job : new int[] { 1, 2, 3, 4, 5 }) {
                    set(session, "job", job);
                    HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                    SimpleAssert.eq(0, get(session, "x"), "only an unpaid job-zero locked preview may move");
                }
                set(session, "job", 0);
                set(session, "virtualLocked", false);
                HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                SimpleAssert.eq(0, get(session, "x"), "moving green preview ignores locked-position requests");
                set(session, "virtualLocked", true);
                request = request(session, 1, 0, 0);
                request.setString("session", "wrong-session");
                HologramService.nudgeWorldPosition(player, request);
                request = request(session, 1, 0, 0);
                request.setLong("planRevision", request.getLong("planRevision") - 1);
                HologramService.nudgeWorldPosition(player, request);
                SimpleAssert.eq(0, get(session, "x"), "old session and plan cannot shift the preview");
                int originalSlot = player.inventory.currentItem;
                player.inventory.currentItem = 1;
                HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                player.inventory.currentItem = originalSlot;
                SimpleAssert.eq(0, get(session, "x"), "changing held tool invalidates nudge authority");
                set(session, "placementReviewIssued", true);
                set(session, "forcePlacementReady", true);
                HologramAeProtocol.State ae = (HologramAeProtocol.State) get(session, "ae");
                ae.review = (HologramAeProtocol.Review) unsafe.allocateInstance(HologramAeProtocol.Review.class);
                long nonce = (Long) get(session, "placementReviewNonce"),
                    revision = (Long) get(session, "planRevision");
                HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                SimpleAssert.eq(1, get(session, "x"), "a valid offset moves the real capture exactly one voxel");
                SimpleAssert.eq(true, get(session, "virtualLocked"), "nudge preserves cyan locked state");
                SimpleAssert.eq(false, get(session, "placementReviewIssued"), "old placement review is revoked");
                SimpleAssert
                    .eq(false, get(session, "forcePlacementReady"), "old missing-material capability is revoked");
                SimpleAssert.that(ae.review == null, "nudge expires the prior AE save review");
                SimpleAssert.eq(true, get(session, "blueprintAwaitingConfirmation"), "ordinary build remains gated");
                SimpleAssert
                    .that((Long) get(session, "placementReviewNonce") > nonce, "nudge replaces the review nonce");
                SimpleAssert.that((Long) get(session, "planRevision") > revision, "position changes the material plan");
                SimpleAssert.eq(false, get(session, "open"), "nudge stays in the world until another right click");
                HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                SimpleAssert.eq(1, get(session, "x"), "server throttles a repeated packet within four ticks");
                world.tick = 4;
                HologramService.nudgeWorldPosition(player, request(session, 0, 1, 0));
                SimpleAssert.eq(65, get(session, "y"), "vertical nudge is exactly one voxel");
                world.tick = 8;
                world.rejectX = 2;
                capture = get(session, "capture");
                HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                SimpleAssert.that(get(session, "capture") == capture, "unloaded destination retains the exact capture");
                world.rejectX = Integer.MIN_VALUE;
                world.tick = 12;
                HologramCapture cells = (HologramCapture) get(session, "capture");
                int oldY = cells.cells.get(0).y;
                set(cells.cells.get(0), "y", 255);
                HologramService.nudgeWorldPosition(player, request(session, 0, 1, 0));
                SimpleAssert.eq(65, get(session, "y"), "whole blueprint height is checked beyond its anchor");
                set(cells.cells.get(0), "y", oldY);
                world.tick = 16;
                set(session, "x", 64);
                HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                SimpleAssert.eq(64, get(session, "x"), "a one-voxel step still obeys server preview reach");
                world.tick = 20;
                player.posX = 29999999;
                set(session, "x", 29999999);
                HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                SimpleAssert.eq(29999999, get(session, "x"), "horizontal world boundary rejects the entire step");
                player.posX = 0;
                set(session, "x", 1);
                java.lang.reflect.Method blueprintAction = HologramAuthoringWorkflow.class
                    .getDeclaredMethod("blueprintAction", type, NBTTagCompound.class, String.class);
                blueprintAction.setAccessible(true);
                blueprintAction.invoke(null, session, request(session, 0, 1, 0), "confirmBlueprintPlacement");
                SimpleAssert.eq(0, get(session, "job"), "old or forged confirmation cannot build after a nudge");
                SimpleAssert.eq(3, player.inventory.mainInventory[1].stackSize, "preview changes consume no material");
                ItemStack materials = player.inventory.mainInventory[1];
                player.inventory.mainInventory[1] = null;
                world.tick = 24;
                HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                SimpleAssert.eq(1, get(session, "planMissing"), "new empty destination refreshes missing materials");
                world.tick = 28;
                world.occupiedX = 3;
                HologramService.nudgeWorldPosition(player, request(session, 1, 0, 0));
                SimpleAssert.eq(0, get(session, "planMissing"), "matching destination removes its material deficit");
                SimpleAssert.eq(3, materials.stackSize, "material review and every nudge consume nothing");
                player.inventory.mainInventory[1] = materials;
            } finally {
                if (prior == null) sessions.remove(player.getUniqueID());
                else sessions.put(player.getUniqueID(), prior);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static NBTTagCompound request(Object session, int dx, int dy, int dz) throws ReflectiveOperationException {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", (String) get(session, "id"));
        request.setLong("planRevision", (Long) get(session, "planRevision"));
        request.setInteger("dx", dx);
        request.setInteger("dy", dy);
        request.setInteger("dz", dz);
        return request;
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class ReadOnlyNudgeWorld extends WorldServer {

        long tick;
        int rejectX;
        int occupiedX;

        private ReadOnlyNudgeWorld() {
            super(null, null, "nudge fixture", 0, null, null);
        }

        @Override
        public long getTotalWorldTime() {
            return tick;
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return x != rejectX;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return x == occupiedX ? Blocks.stone : Blocks.air;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return 0;
        }

        @Override
        public net.minecraft.tileentity.TileEntity getTileEntity(int x, int y, int z) {
            return null;
        }

        @Override
        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
            return true;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            throw new AssertionError("nudge must never write a world block");
        }

        @Override
        public void setTileEntity(int x, int y, int z, net.minecraft.tileentity.TileEntity tile) {
            throw new AssertionError("nudge must never write a world tile");
        }
    }
}
