package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual bounded pose preparation versus the existing native-aware placement transform on a read-only world. */
public final class HologramPresetPosePreparationRegression {

    private HologramPresetPosePreparationRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramPresetPosePreparationRegression.class, args)) return;
        run();
        System.out.println("Preset pose preparation regressions passed");
    }

    public static void run() {
        EntityPlayerMP player = null;
        try {
            player = player();
            for (int[] turns : new int[][] { {}, { 0 }, { 1 }, { 0, 1, 0 }, { 0, 0, 0, 0 } }) {
                HologramSession expected = session(player), candidate = session(player);
                HologramBlueprintProjection.populateVirtual(expected);
                HologramBlueprintProjection.Population population = new HologramBlueprintProjection.Population(candidate);
                while (!population.step(7, Long.MAX_VALUE)) {}
                for (int i = 0; i < turns.length; i++) SimpleAssert.that(
                    HologramWorldPlacementWorkflow.transformVirtual(expected, expected.x, expected.y, expected.z,
                        true, turns[i] == 1, i == turns.length - 1), "reference pose accepted");
                HologramPresetPosePreparation task = new HologramPresetPosePreparation(turns, false, true);
                String before = HologramBlueprintContentHash.of(candidate.virtualPreset);
                String sourceBefore = HologramBlueprintContentHash.of(candidate.processingSource);
                SimpleAssert.that(!task.step(candidate, 0, Long.MAX_VALUE), "zero work cannot start pose");
                SimpleAssert.that(!task.step(candidate, 7, 0), "zero time cannot start pose");
                SimpleAssert.eq(before, HologramBlueprintContentHash.of(candidate.virtualPreset), "zero budget preserves pose");
                int slices = 0;
                while (!task.step(candidate, 3, Long.MAX_VALUE))
                    SimpleAssert.that(++slices < 30000, "bounded pose reaches terminal state");
                SimpleAssert.that(slices > 50, "large pose really yields rather than wrapping a synchronous transform");
                SimpleAssert.eq(HologramBlueprintContentHash.of(expected.virtualPreset),
                    HologramBlueprintContentHash.of(candidate.virtualPreset), "preset pose equals existing transform");
                SimpleAssert.eq(expected.capture.cells.size(), candidate.capture.cells.size(), "all cells retained");
                for (int i = 0; i < expected.capture.cells.size(); i++) {
                    HologramCapture.Cell a = expected.capture.cells.get(i), b = candidate.capture.cells.get(i);
                    SimpleAssert.eq(HologramValues.position(a), HologramValues.position(b), "capture position parity");
                    SimpleAssert.eq(a.tileNbt, b.tileNbt, "capture tile payload parity");
                    SimpleAssert.eq(a.itemNbt, b.itemNbt, "capture item payload parity");
                }
                SimpleAssert.eq(sourceBefore, HologramBlueprintContentHash.of(candidate.processingSource),
                    "processing source retains canonical unrotated owned data");
            }
            HologramSession locked = session(player);
            HologramBlueprintProjection.Population population = new HologramBlueprintProjection.Population(locked);
            while (!population.step(13, Long.MAX_VALUE)) {}
            HologramPresetPosePreparation lock = new HologramPresetPosePreparation(new int[0], true);
            while (!lock.step(locked, 13, Long.MAX_VALUE)) {}
            SimpleAssert.that(locked.virtualLocked && locked.blueprintAwaitingConfirmation && !locked.placementReviewIssued,
                "quick lock prepares review intent without authorization or world placement");
            SimpleAssert.that(locked.job != 1 && locked.job != 3, "preparation never submits construction");
        } catch (Exception failure) {
            throw new AssertionError("preset pose preparation", failure);
        } finally {
            if (player != null) player.playerNetServerHandler.netManager.channel().close();
        }
    }

    private static HologramSession session(EntityPlayerMP player) {
        HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
        s.virtual = s.virtualPlacement = true;
        s.materialSettings.main = s.materialSettings.me = s.materialSettings.containers = false;
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.schema = 2;
        s.virtualPreset.id = "private_pose";
        s.virtualPreset.name = "pose";
        s.virtualPreset.controllerType = "area";
        for (int i = 0; i < 257; i++) s.virtualPreset.cells.add(
            HologramPresets.cell(new ItemStack(Blocks.stone), i % 17, i % 3, i / 17, -1, false));
        s.processingSource = HologramPresets.copy(s.virtualPreset);
        return s;
    }

    private static EntityPlayerMP player() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        Field provider = net.minecraft.world.World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new net.minecraft.world.WorldProviderSurface());
        Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod("player", sun.misc.Unsafe.class,
            HologramServiceEditRegression.ReadOnlyWorld.class, UUID.class, String.class);
        factory.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) factory.invoke(null, unsafe, world, UUID.randomUUID(), "PresetPose");
        player.posY = 64;
        player.capabilities.isCreativeMode = true;
        return player;
    }
}
