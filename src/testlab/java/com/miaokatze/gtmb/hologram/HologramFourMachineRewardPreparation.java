// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.stats.Achievement;
import net.minecraft.stats.StatBase;
import net.minecraft.stats.StatList;
import net.minecraft.world.WorldServer;

import com.miaokatze.gtmb.register.ProjectorRegistry;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;

/** Settle the pack's real controller inventory achievement before taking any business material baseline. */
final class HologramFourMachineRewardPreparation {

    private final NBTTagCompound receipt = new NBTTagCompound();
    private final WorldServer world;
    private final EntityPlayerMP actor;
    private final Achievement achievement;
    private final Class<?> scanner;
    private final int interval;
    private final long started;
    private Map<NBTTagCompound, Long> before;
    private Map<NBTTagCompound, Long> initializedInventory;
    private ItemStack seed;
    private long seededAt = -1, rewardedAt = -1;
    private long initializedAt = -1;
    private boolean complete;

    HologramFourMachineRewardPreparation(WorldServer world, EntityPlayerMP actor) throws Exception {
        this.world = world;
        this.actor = actor;
        started = world.getTotalWorldTime();
        scanner = Class.forName("com.gtnewhorizon.gtnhlib.eventhandlers.PlayerInventoryEventHandler");
        interval = Class.forName("com.gtnewhorizon.gtnhlib.GTNHLibConfig")
            .getField("inventoryScanInterval")
            .getInt(null);
        require(interval >= 1 && interval <= 100, "Unreviewed native inventory scan interval");
        StatBase stat = StatList.func_151177_a("electric_blast_furnace");
        require(stat instanceof Achievement, "Pack EBF achievement is not registered");
        achievement = (Achievement) stat;
        Object statistics = actor.func_147099_x();
        File statisticsFile = null;
        for (Field field : statistics.getClass()
            .getDeclaredFields()) if (field.getType() == File.class) {
                require(statisticsFile == null, "Ambiguous native statistics file");
                field.setAccessible(true);
                statisticsFile = (File) field.get(statistics);
            }
        require(statisticsFile != null, "Native statistics file was not exposed");
        Path save = world.getSaveHandler()
            .getWorldDirectory()
            .toPath()
            .toRealPath();
        Path actualFile = statisticsFile.getCanonicalFile()
            .toPath();
        require(
            actualFile.equals(
                save.resolve("stats")
                    .resolve(actor.getUniqueID() + ".json")),
            "Reward preparation statistics are outside this owned save");
        require(
            !actor.func_147099_x()
                .hasAchievementUnlocked(achievement),
            "Fresh owned EBF achievement already unlocked");
        receipt.setString("ownedSave", save.toString());
        receipt.setString("nativeStatisticsFile", actualFile.toString());
        receipt.setBoolean("statisticsOnlyInOwnedSave", true);
        receipt.setString(
            "actor",
            actor.getUniqueID()
                .toString());
        receipt.setInteger("inventoryScanInterval", interval);
        receipt
            .setString("condition", "pack inventory.add gregtech:gt.blockmachines meta1000 -> electric_blast_furnace");
        receipt.setTag(
            "loadedAmazingTrophiesArtifact",
            artifact(Class.forName("glowredman.amazingtrophies.api.AmazingTrophiesAPI")));
        receipt.setTag("loadedInventoryScannerArtifact", artifact(scanner));
        receipt.setInteger("additionalSlotUpperBound", 2);
        receipt.setInteger("reviewedPriorMatrixSlotUpperBound", 30);
        receipt.setInteger("reviewedMatrixWithRewardSlotUpperBound", 32);
        receipt.setBoolean("businessLedgerUnchanged", true);
    }

    boolean tick(HologramFourMachineWorldFixture fixture) throws Exception {
        if (complete) return true;
        long tick = world.getTotalWorldTime();
        require(tick - started <= Math.max(200, interval * 8), "Natural EBF reward preparation timed out");
        if (seededAt < 0) {
            Field statesField = scanner.getDeclaredField("SERVER_STATES");
            statesField.setAccessible(true);
            Object state = ((Map<?, ?>) statesField.get(null)).get(actor.getUniqueID());
            if (state == null || !state.getClass()
                .getField("seeded")
                .getBoolean(state)) return false;
            ItemStack held = actor.getHeldItem();
            if (held == null || held.getItem() != ProjectorRegistry.projector || !held.hasTagCompound()) return false;
            String tool = held.getTagCompound()
                .getString("gtitHologramToolId");
            if (tool.isEmpty()) return false;
            require(
                UUID.fromString(tool)
                    .toString()
                    .equals(tool),
                "Native projector UUID is not canonical");
            Map<NBTTagCompound, Long> inventory = HologramFourMachineWorldAudit.inventory(actor);
            if (!inventory.equals(initializedInventory)) {
                initializedInventory = inventory;
                initializedAt = tick;
                return false;
            }
            if (tick - initializedAt < interval * 2L) return false;
            receipt.setBoolean("nativeScannerSeededBeforeSupply", true);
            receipt.setString("actualInitializedToolUuid", tool);
            receipt.setLong("fullInventoryStableFromTick", initializedAt);
            receipt.setLong("fullInventoryStableThroughTick", tick);
            receipt.setInteger("requiredStableScanCycles", 2);
            receipt.setBoolean("toolAndTerminalFullNbtStable", true);
            before = inventory;
            seed = HologramFourMachineSpec.EBF.registeredController();
            require(
                before.getOrDefault(HologramFourMachineWorldAudit.identity(seed), 0L) == 0,
                "Unexpected EBF seed already held");
            receipt.setTag("inventoryBefore", HologramFourMachineWorldAudit.counts(before));
            receipt.setInteger("occupiedSlotsBefore", occupiedSlots());
            List<ItemStack> seeds = new ArrayList<>();
            seeds.add(seed);
            fixture.supply(seeds, false);
            seededAt = tick;
            receipt.setLong("seededWorldTick", tick);
            receipt.setTag("finiteRegisteredSeed", seed.writeToNBT(new NBTTagCompound()));
            receipt.setInteger("seedCount", 1);
            return false;
        }
        ItemStack trophy = null;
        for (ItemStack stack : actor.inventory.mainInventory) if (stack != null && stack.hasTagCompound()
            && "amazingtrophies:trophy".equals(Item.itemRegistry.getNameForObject(stack.getItem()))
            && "electric_blast_furnace".equals(
                stack.getTagCompound()
                    .getString("trophyID"))) {
                        require(trophy == null && stack.stackSize == 1, "Duplicate actual EBF trophy reward");
                        trophy = stack.copy();
                    }
        if (trophy == null || !actor.func_147099_x()
            .hasAchievementUnlocked(achievement)) return false;
        require(
            actor.getUniqueID()
                .toString()
                .equals(
                    trophy.getTagCompound()
                        .getString("uuid"))
                && actor.getCommandSenderName()
                    .equals(
                        trophy.getTagCompound()
                            .getString("name"))
                && trophy.getTagCompound()
                    .getLong("time") > 0,
            "Natural trophy receipt does not identify the actual owned actor");
        Map<NBTTagCompound, Long> delta = new LinkedHashMap<>(HologramFourMachineWorldAudit.inventory(actor));
        for (var entry : before.entrySet()) delta.merge(entry.getKey(), -entry.getValue(), Long::sum);
        delta.entrySet()
            .removeIf(entry -> entry.getValue() == 0);
        Map<NBTTagCompound, Long> expected = new LinkedHashMap<>();
        HologramFourMachineWorldAudit.add(expected, seed);
        HologramFourMachineWorldAudit.add(expected, trophy);
        require(
            delta.equals(expected),
            "Reward preparation received resources beyond exact finite seed + actual trophy");
        if (rewardedAt < 0) rewardedAt = tick;
        if (tick - rewardedAt < interval * 2L) return false;
        require(occupiedSlots() <= 32, "Reward preparation exceeded reviewed matrix inventory capacity");
        receipt.setLong("rewardObservedWorldTick", rewardedAt);
        receipt.setLong("settledWorldTick", tick);
        receipt.setBoolean("actualAchievementUnlocked", true);
        receipt.setTag("actualTrophy", trophy.writeToNBT(new NBTTagCompound()));
        receipt.setTag("actualInventoryDelta", HologramFourMachineWorldAudit.counts(delta));
        receipt.setInteger("occupiedSlotsAfter", occupiedSlots());
        receipt.setBoolean("seedAndRewardRetained", true);
        receipt.setBoolean("settled", true);
        complete = true;
        return true;
    }

    NBTTagCompound receipt() {
        return (NBTTagCompound) receipt.copy();
    }

    private int occupiedSlots() {
        int count = 0;
        for (ItemStack stack : actor.inventory.mainInventory) if (stack != null && stack.stackSize > 0) count++;
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static NBTTagCompound artifact(Class<?> anchor) {
        NBTTagCompound result = new NBTTagCompound();
        for (var entry : ArtifactIdentity.capture(anchor)
            .asMap()
            .entrySet()) result.setString(entry.getKey(), String.valueOf(entry.getValue()));
        return result;
    }
}
