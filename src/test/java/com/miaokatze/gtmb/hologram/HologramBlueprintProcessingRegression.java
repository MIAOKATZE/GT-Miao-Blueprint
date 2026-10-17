package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Native NBT receipts, geometry holes, reversible session decisions and atomic reusable policy persistence. */
public final class HologramBlueprintProcessingRegression {

    private HologramBlueprintProcessingRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramBlueprintProcessingRegression.class, args)) return;
        run();
        System.out.println("Blueprint processing regression passed");
    }

    public static void run() throws Exception {
        adaptiveStages();
        explicitStagesAndSessionBacktracking();
        sharingKeepsAnonymousNetworkSlots();
        actualCellsAndRelativeProjection();
        globalAndCellSelections();
        resourceDecisionsAreLocalAndPayable();
        legacyFloorAndDependencyInvalidation();
        auditedSensorCacheBoundary();
        sensorObservationPaymentBoundary();
        sensorNativeStateBoundary();
        importedMaterialIdentityBoundary();
        ordinaryMaterialVariantBoundary();
        automaticHintsFollowActualProcessing();
        atomicPolicyLibrary();
    }

    private static void resourceDecisionsAreLocalAndPayable() throws Exception {
        HologramPresets.Preset raw = source(104);
        NBTTagCompound payload = HologramBlueprintNbt.decode(raw.cells.get(0).tileNbt);
        net.minecraft.nbt.NBTTagList unknown = new net.minecraft.nbt.NBTTagList();
        unknown.appendTag(new ItemStack(Items.diamond, 3).writeToNBT(new NBTTagCompound()));
        payload.setTag("unknownInventory", unknown);
        raw.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        raw.cells.get(1).tileNbt = raw.cells.get(0).tileNbt;
        HologramBlueprintProcessing.Policy policy = completed("self");
        NBTTagCompound state = HologramBlueprintProcessing.state(raw, policy, 0, 100, 64, 200);
        net.minecraft.nbt.NBTTagList blockers = state.getTagList("resourceBlockers", 10);
        SimpleAssert.eq(2, blockers.tagCount(), "unknown inventories are reported separately for identical cells");
        SimpleAssert.that(
            blockers.getCompoundTagAt(0)
                .getString("path")
                .equals("tile:/unknownInventory")
                && !blockers.getCompoundTagAt(0)
                    .getBoolean("canPreserve"),
            "report exposes an exact removable path without free preservation");
        HologramBlueprintProcessing.ResourceDecision removal = new HologramBlueprintProcessing.ResourceDecision();
        removal.path = "tile:/unknownInventory/0";
        removal.action = "remove";
        policy.resourceDecisions.add(removal);
        HologramPresets.Preset processed = HologramBlueprintProcessing.process(raw, policy, 0, 100, 64, 200);
        SimpleAssert.eq(
            0,
            HologramBlueprintNbt.decode(processed.cells.get(0).tileNbt)
                .getTagList("unknownInventory", 10)
                .tagCount(),
            "precise list entry removal is materialized");
        SimpleAssert.eq(
            1,
            HologramBlueprintNbt.decode(processed.cells.get(1).tileNbt)
                .getTagList("unknownInventory", 10)
                .tagCount(),
            "same type and NBT at another position are untouched");
        SimpleAssert.eq(
            1,
            HologramBlueprintNbt.decode(raw.cells.get(0).tileNbt)
                .getTagList("unknownInventory", 10)
                .tagCount(),
            "author source is immutable");
        removal.action = "preserve";
        expectIo(() -> HologramBlueprintProcessing.process(raw, policy, 0, 100, 64, 200));
        policy.resourceDecisions.clear();
        payload.removeTag("unknownInventory");
        payload.setTag("Inventory", unknown.copy());
        raw.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        removal.path = "tile:/Inventory";
        policy.resourceDecisions.add(removal);
        processed = HologramBlueprintProcessing.process(raw, policy, 0, 100, 64, 200);
        HologramBlueprintResources.Plan bill = HologramBlueprintResources
            .plan(HologramBlueprintNbt.decode(processed.cells.get(0).tileNbt));
        SimpleAssert.eq(3, bill.items.get(0).stackSize, "explicit preservation still pays the exact real inventory");
        NBTTagCompound duplicate = policy.write();
        duplicate.getTagList("resourceDecisions", 10)
            .appendTag(removal.write());
        expectIo(() -> HologramBlueprintProcessing.Policy.read(duplicate));
    }

    private static void sharingKeepsAnonymousNetworkSlots() throws Exception {
        HologramPresets.Preset raw = source(104);
        NBTTagCompound original = HologramBlueprintNbt.decode(raw.cells.get(0).tileNbt);
        original.setString(HologramNetworkBindings.GROUP, "q1");
        original.setString(HologramNetworkBindings.ROLE, "join");
        raw.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(original);
        raw = HologramQuantumGroups.configure(raw, HologramQuantumGroups.groups(raw));
        HologramBlueprintProcessing.Policy sharing = completed("share");
        SimpleAssert.eq("rebind", sharing.quantum, "sharing recommends recipient terminal binding by default");
        SimpleAssert.eq("preserve", completed("self").quantum, "self-use preserves the original network semantics");
        HologramPresets.Preset portable = HologramBlueprintProcessing.process(raw, sharing, 0, 100, 64, 200);
        NBTTagCompound processed = HologramBlueprintNbt.decode(portable.cells.get(0).tileNbt);
        SimpleAssert.that(
            "q1".equals(processed.getString(HologramNetworkBindings.GROUP))
                && "join".equals(processed.getString(HologramNetworkBindings.ROLE))
                && processed.getString("ownerName")
                    .isEmpty()
                && !processed.hasKey("coreX")
                && !HologramBlueprintProcessing.quantumCleared(portable),
            "sharing keeps an anonymous topology slot while removing source owner and external coordinates");
        SimpleAssert.that(
            portable.cells.get(0).prompt.contains("自己的有效终端"),
            "shared blueprint explains the recipient's own terminal requirement");
        sharing.quantum = "clear";
        HologramPresets.Preset cleared = HologramBlueprintProcessing.process(raw, sharing, 0, 100, 64, 200);
        SimpleAssert.that(
            HologramBlueprintProcessing.quantumCleared(cleared) && cleared.quantumGroupingNbt == null
                && !HologramBlueprintNbt.decode(cleared.cells.get(0).tileNbt)
                    .hasKey(HologramNetworkBindings.GROUP),
            "explicit clear removes logical groups and retires their exact classification review");
        SimpleAssert.eq(
            "original",
            HologramBlueprintNbt.decode(raw.cells.get(0).tileNbt)
                .getString("ownerName"),
            "sharing and clearing leave the author source unchanged");
    }

    private static void adaptiveStages() throws Exception {
        HologramPresets.Preset decor = new HologramPresets.Preset();
        HologramPresets.Cell cell = new HologramPresets.Cell();
        cell.item = "minecraft:stone";
        cell.block = "minecraft:stone";
        decor.cells.add(cell);
        SimpleAssert.eq(
            0,
            HologramBlueprintProcessing.requiredStages(decor, 0, 0, 64, 0),
            "ordinary decorative blocks can save directly");
        NBTTagCompound system = new NBTTagCompound();
        system.setString("id", "decor");
        system.setInteger("x", 10);
        system.setInteger("y", 64);
        system.setInteger("z", 20);
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(system);
        SimpleAssert.eq(
            0,
            HologramBlueprintProcessing.requiredStages(decor, 0, 0, 64, 0),
            "system location fields do not force author identity review");
        system.setString("unreviewedPayload", "opaque");
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(system);
        int opaque = HologramBlueprintProcessing.requiredStages(decor, 0, 0, 64, 0);
        SimpleAssert.that(
            (opaque & 1 << 4) != 0 && (opaque & 1 << 2) == 0,
            "unknown data keeps NBT review but does not invent quantum relations");
        int sensitive = HologramBlueprintProcessing.requiredStages(source(104), 0, 100, 64, 200);
        SimpleAssert.that(
            (sensitive & 1 << 1) != 0 && (sensitive & 1 << 3) != 0,
            "server inspection retains ownership and actual coordinate relation reviews");
    }

    private static void explicitStagesAndSessionBacktracking() throws Exception {
        HologramPresets.Preset raw = source(104);
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("share", 0);
        HologramPresets.Cell paletteSample = new HologramPresets.Cell();
        paletteSample.item = "minecraft:stone";
        paletteSample.block = "minecraft:stone";
        paletteSample.tileNbt = raw.cells.get(0).tileNbt;
        paletteSample.itemNbt = raw.cells.get(0).tileNbt;
        raw.palette.add(paletteSample);
        expectIo(() -> HologramBlueprintProcessing.process(raw, policy, 0, 100, 64, 200));
        NBTTagCompound outOfOrder = policy.write();
        outOfOrder.setTag("confirmedStages", HologramBlueprintProcessing.strings(Arrays.asList("preset", "quantum")));
        expectIo(() -> HologramBlueprintProcessing.Policy.read(outOfOrder));
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        HologramPresets.Preset cleaned = HologramBlueprintProcessing.process(raw, policy, 0, 100, 64, 200);
        SimpleAssert.that(
            HologramBlueprintProcessing.complete(cleaned),
            "all explicitly confirmed stages produce a completed receipt");
        SimpleAssert.eq(
            "",
            HologramBlueprintNbt.decode(cleaned.cells.get(0).tileNbt)
                .getString("ownerName"),
            "share clears audited owner slot");
        SimpleAssert.eq(
            "original",
            HologramBlueprintNbt.decode(raw.cells.get(0).tileNbt)
                .getString("ownerName"),
            "raw session source is immutable");
        HologramBlueprintProcessing.Policy self = completed("self");
        HologramPresets.Preset restored = HologramBlueprintProcessing.process(raw, self, 0, 100, 64, 200);
        SimpleAssert.eq(
            "original",
            HologramBlueprintNbt.decode(restored.cells.get(0).tileNbt)
                .getString("ownerName"),
            "reprocessing raw source can change an earlier decision without permanent source deletion");
        SimpleAssert.that(
            !HologramBlueprintNbt.decode(cleaned.processingNbt)
                .hasKey("source"),
            "saved receipt has no raw identity backup");
        SimpleAssert.that(
            cleaned.palette.get(0).tileNbt == null && cleaned.palette.get(0).itemNbt == null,
            "candidate palette cannot hide unprocessed original identity or coordinate payloads");
        SimpleAssert.that(
            raw.palette.get(0).tileNbt != null,
            "removing unused palette transport does not mutate the review source");
    }

    private static void actualCellsAndRelativeProjection() throws Exception {
        HologramPresets.Preset hole = source(102);
        HologramCoordinatePolicies.Relation outside = HologramCoordinatePolicies.inspect(hole, 7, 100, 64, 200)
            .get(0);
        SimpleAssert.that(!outside.internal, "a hole inside the bounding box is not an internal selected cell");
        SimpleAssert.eq(
            "absolute",
            HologramCoordinatePolicies.mode(outside, completed("self")),
            "self preserves an external target by default");
        SimpleAssert.eq(
            "clear",
            HologramCoordinatePolicies.mode(outside, completed("share")),
            "share clears external binding by default");
        HologramPresets.Preset raw = source(104);
        HologramBlueprintProcessing.Policy policy = completed("self");
        HologramPresets.Preset processed = HologramBlueprintProcessing.process(raw, policy, 7, 100, 64, 200);
        NBTTagCompound saved = HologramBlueprintNbt.decode(processed.cells.get(0).tileNbt);
        SimpleAssert.that(
            !saved.hasKey("coreX") && !saved.hasKey("coreZ"),
            "relative result does not persist old world coordinates");
        NBTTagCompound relation = saved.getTagList(HologramCoordinatePolicies.RELATIONS, 10)
            .getCompoundTagAt(0);
        SimpleAssert.eq("relative", relation.getString("mode"), "actual selected target defaults relative");
        SimpleAssert
            .eq(0, relation.getInteger("targetX"), "relative descriptor does not secretly retain source absolute x");
        NBTTagCompound placed = HologramCoordinatePolicies.placementProjection(saved, -1, 300, 80, 500);
        SimpleAssert.eq(304, placed.getInteger("coreX"), "relative relation follows translation");
        SimpleAssert.eq(80, placed.getInteger("coreY"), "relative relation binds destination height");
        SimpleAssert.that(
            !placed.hasKey("dimension") && !placed.hasKey(HologramCoordinatePolicies.RELATIONS),
            "implicit-dimension schema receives no invented dimension field or transport descriptor");
        NBTTagCompound turned = HologramBlueprintNbt
            .decode(HologramCoordinatePolicies.rotatePayload(processed.cells.get(0).tileNbt, false));
        placed = HologramCoordinatePolicies.placementProjection(turned, 2, 300, 80, 500);
        SimpleAssert.eq(300, placed.getInteger("coreX"), "yaw turns the relative x vector");
        SimpleAssert.eq(504, placed.getInteger("coreZ"), "yaw points relative vector along z");
        turned = HologramBlueprintNbt
            .decode(HologramCoordinatePolicies.rotatePayload(HologramBlueprintNbt.encodeCanonical(turned), true));
        placed = HologramCoordinatePolicies.placementProjection(turned, 2, 300, 80, 500);
        SimpleAssert.eq(76, placed.getInteger("coreY"), "pitch also turns the relative vector");
        SimpleAssert.that(
            saved.equals(HologramBlueprintNbt.decode(processed.cells.get(0).tileNbt)),
            "installation and rotation projections never mutate persisted payload");
        HologramPresets.Preset changedSelection = HologramPresets.copy(processed);
        changedSelection.cells.remove(1);
        HologramCoordinatePolicies.Relation noLongerInternal = HologramCoordinatePolicies
            .inspect(changedSelection, 7, 100, 64, 200)
            .get(0);
        SimpleAssert
            .that(!noLongerInternal.internal, "stored internal flag is recomputed when a target cell is removed");
        SimpleAssert.eq(
            "clear",
            HologramCoordinatePolicies.mode(noLongerInternal, completed("share")),
            "share clears a former internal binding after the target becomes a hole");
        HologramPresets.Preset excludedTarget = HologramPresets.copy(processed);
        excludedTarget.cells.get(1).excluded = true;
        SimpleAssert.that(
            !HologramCoordinatePolicies.inspect(excludedTarget, 7, 100, 64, 200)
                .get(0).internal,
            "a target excluded from construction cannot be misclassified as an internal placed device");
        policy.coordinateDefault = "clear";
        HologramPresets.Preset clear = HologramBlueprintProcessing.process(raw, policy, 7, 100, 64, 200);
        NBTTagCompound cleared = HologramCoordinatePolicies
            .placementProjection(HologramBlueprintNbt.decode(clear.cells.get(0).tileNbt), 3, 300, 80, 500);
        SimpleAssert.that(
            !cleared.hasKey("coreX") && !cleared.getBoolean("partOfScreen"),
            "clear disables native extender relationship");
    }

    private static void globalAndCellSelections() throws Exception {
        HologramPresets.Preset raw = source(104);
        NBTTagCompound tag = HologramBlueprintNbt.decode(raw.cells.get(0).tileNbt);
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setString("owner", "recipe owner literal");
        recipe.setInteger("x", 55);
        tag.setTag("recipe", recipe);
        tag.setString("opaque", "retain");
        raw.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        HologramBlueprintProcessing.Policy policy = completed("self");
        policy.deidentificationLevel = 10;
        policy.ignoredNbtKeys.add("opaque");
        policy.ignoredNbtKeys.add("id");
        HologramPresets.Preset result = HologramBlueprintProcessing.process(raw, policy, 0, 100, 64, 200);
        NBTTagCompound selected = HologramBlueprintNbt.decode(result.cells.get(0).tileNbt);
        SimpleAssert.that(
            !selected.hasKey("opaque") && selected.hasKey("id"),
            "explicit global ignore removes selected data and preserves system tile identity");
        SimpleAssert.that(
            recipe.equals(selected.getCompoundTag("recipe")),
            "identity projection does not recurse into recipe payloads");
        HologramBlueprintProcessing.CellNbtOverride override = new HologramBlueprintProcessing.CellNbtOverride();
        override.x = 0;
        override.y = 0;
        override.z = 0;
        override.all = true;
        policy.cellNbtOverrides.add(override);
        result = HologramBlueprintProcessing.process(raw, policy, 0, 100, 64, 200);
        SimpleAssert.eq(
            "retain",
            HologramBlueprintNbt.decode(result.cells.get(0).tileNbt)
                .getString("opaque"),
            "explicit per-cell all overrides global root selection using the raw baseline");
        raw.retainedNbtKeys = Arrays.asList("id", "ownerName");
        result = HologramBlueprintProcessing.process(raw, policy, 0, 100, 64, 200);
        SimpleAssert.that(
            result.retainedNbtKeys == null && result.retainNbt,
            "resolved per-cell choices cannot be overwritten by a stale global save or placement filter");
        SimpleAssert.eq(
            "retain",
            HologramBlueprintNbt.decode(result.cells.get(0).tileNbt)
                .getString("opaque"),
            "per-cell all can retain its available raw baseline even when a legacy global key filter is narrower");
        expectIo(
            () -> HologramBlueprintProcessing.editNbtValue(raw, new int[] { 0 }, "tile:/recipe/x", "int", "1000000"));
        expectIo(
            () -> HologramBlueprintProcessing
                .editNbtValue(raw, new int[] { 0 }, "tile:/opaque", "string", "new contents"));
        NBTTagCompound jar = new NBTTagCompound();
        jar.setString("id", "TileJar");
        jar.setString("Aspect", "aer");
        jar.setShort("Amount", (short) 64);
        HologramBlueprintNbt.normalizeResourceSelection(jar);
        SimpleAssert.eq((short) 64, jar.getShort("Amount"), "selected complete essentia pair remains exact");
        jar.removeTag("Amount");
        HologramBlueprintNbt.normalizeResourceSelection(jar);
        SimpleAssert.that(
            !jar.hasKey("Aspect") && jar.getShort("Amount") == 0,
            "omitting a jar quantity clears its paired physical type");
    }

    private static void legacyFloorAndDependencyInvalidation() throws Exception {
        HologramPresets.Preset legacy = source(104);
        legacy.id = "legacy";
        legacy.deidentificationLevel = 3;
        HologramBlueprintProcessing.markLegacy(legacy);
        SimpleAssert.that(
            !HologramBlueprintProcessing.complete(legacy),
            "legacy file has an explicit incomplete legacy receipt");
        expectIo(() -> HologramBlueprintProcessing.process(legacy, completed("self"), 0, 100, 64, 200));
        HologramBlueprintProcessing.Policy policy = completed("self");
        policy.deidentificationLevel = 3;
        HologramPresets.Preset result = HologramBlueprintProcessing.process(legacy, policy, 0, 100, 64, 200);
        HologramBlueprintProcessing.invalidate(result, "geometry");
        SimpleAssert.eq(
            3,
            HologramBlueprintProcessing.policy(result).confirmedStages.size(),
            "geometry invalidates coordinates and later stages while retaining earlier explicit choices");
        SimpleAssert.that(
            !HologramBlueprintProcessing.complete(result),
            "edited geometry requires another review before saving");
    }

    private static void auditedSensorCacheBoundary() {
        String prefix = "shedar.mods.ic2.nuclearcontrol.items.";
        NBTTagCompound energy = new NBTTagCompound();
        energy.setInteger("targetType", 2);
        energy.setDouble("energyL", 64);
        energy.setDouble("maxStorageL", 1000);
        energy.setDouble("range_trigger_amount", 64);
        energy.setString("title", "User title");
        energy.setInteger("state", 1);
        SimpleAssert.that(
            HologramCoordinatePolicies.clearableSensorTag(prefix + "ItemCardEnergySensorLocation", energy),
            "registered energy card bindings, observation caches and title are explicitly clearable");
        NBTTagCompound reactor = new NBTTagCompound();
        reactor.setInteger("heat", 10);
        reactor.setInteger("maxHeat", 100);
        reactor.setInteger("output", 20);
        reactor.setInteger("timeLeft", 30);
        reactor.setBoolean("reactorPoweredB", true);
        reactor.setBoolean("isSteam", false);
        SimpleAssert.that(
            HologramCoordinatePolicies.clearableSensorTag(prefix + "ItemCardReactorSensorLocation", reactor),
            "registered reactor telemetry is an audited observation schema");
        energy.setInteger("unknownAsset", 64);
        SimpleAssert.that(
            !HologramCoordinatePolicies.clearableSensorTag(prefix + "ItemCardEnergySensorLocation", energy),
            "unknown additional card payload blocks clear instead of silently discarding possible assets");
        energy.removeTag("unknownAsset");
        energy.setTag("energyL", new NBTTagCompound());
        SimpleAssert.that(
            !HologramCoordinatePolicies.clearableSensorTag(prefix + "ItemCardEnergySensorLocation", energy),
            "registered key with an unexpected asset-like compound is not treated as a scalar cache");
        SimpleAssert.that(
            !HologramCoordinatePolicies.clearableSensorTag("opaque.SensorCard", reactor),
            "a similar unknown item class never receives the reviewed sensor deletion policy");
    }

    private static void sensorObservationPaymentBoundary() {
        String gt = "gregtech.common.items.ItemSensorCard";
        NBTTagCompound observed = new NBTTagCompound();
        observed.setInteger("x", 100);
        observed.setInteger("y", 64);
        observed.setInteger("z", 200);
        observed.setInteger("mString", 2);
        observed.setString("mString0", "actual LCR information");
        observed.setString("mString1", "actual reactor input state");
        observed.setString("mString9", "stale native observation");
        observed.setInteger("state", 1);
        observed.setString(HologramCoordinatePolicies.PAYMENT, "relative");
        SimpleAssert.that(
            HologramCoordinatePolicies.clearableSensorTag(gt, observed),
            "registered GT card supports its native typed info count, info lines and shared NC state");
        SimpleAssert.that(
            HologramCoordinatePolicies.normalizePaymentTag(gt, observed)
                .hasNoTags(),
            "a blank GT card can pay for reviewed relation configuration regardless of current native observations");
        SimpleAssert.eq(
            "actual LCR information",
            observed.getString("mString0"),
            "payment normalization preserves the source card and its displayed native information");
        NBTTagCompound absolute = HologramCoordinatePolicies.normalizePaymentTag(gt, observed, false);
        SimpleAssert.eq(100, absolute.getInteger("x"), "absolute payment retains the exact author target x");
        SimpleAssert.that(
            !absolute.hasKey("mString") && !absolute.hasKey("state"),
            "absolute payment also excludes only pure runtime observations");
        NBTTagCompound moved = (NBTTagCompound) observed.copy();
        moved.setInteger("x", 101);
        SimpleAssert.that(
            !absolute.equals(HologramCoordinatePolicies.normalizePaymentTag(gt, moved, false)),
            "a different coordinate cannot pay for an absolute relation");
        observed.setString("title", "Author title");
        observed.setInteger("opaqueAsset", 7);
        NBTTagCompound normalized = HologramCoordinatePolicies.normalizePaymentTag(gt, observed);
        SimpleAssert.eq("Author title", normalized.getString("title"), "GT card user configuration is still exact");
        SimpleAssert.eq(7, normalized.getInteger("opaqueAsset"), "unknown GT card data never becomes a free asset");
        SimpleAssert.that(
            !HologramCoordinatePolicies.clearableSensorTag(gt, observed),
            "unknown additional GT card data prevents clear instead of silently discarding assets");
        NBTTagCompound unexpected = new NBTTagCompound();
        unexpected.setInteger("Count", 64);
        observed.setTag("mString0", unexpected);
        observed.setTag("x", unexpected.copy());
        normalized = HologramCoordinatePolicies.normalizePaymentTag(gt, observed);
        SimpleAssert.that(
            unexpected.equals(normalized.getTag("mString0")) && unexpected.equals(normalized.getTag("x")),
            "a known cache or coordinate key with an unreviewed type retains its complete exact data");
        observed.setString("mStringPrivate", "private extension");
        SimpleAssert.eq(
            "private extension",
            HologramCoordinatePolicies.normalizePaymentTag(gt, observed)
                .getString("mStringPrivate"),
            "GT cache matching is the exact numbered native field family rather than a broad prefix");
        SimpleAssert.that(
            observed.equals(HologramCoordinatePolicies.normalizePaymentTag("gregtech.custom.SensorCard", observed)),
            "a similar package never grants sensor normalization");

        NBTTagCompound energy = new NBTTagCompound();
        energy.setInteger("targetType", 2);
        energy.setDouble("energyL", 123);
        energy.setDouble("maxStorageL", 1000);
        energy.setDouble("range_trigger_amount", 123);
        NBTTagCompound energyPayment = HologramCoordinatePolicies
            .normalizePaymentTag("shedar.mods.ic2.nuclearcontrol.items.ItemCardEnergySensorLocation", energy);
        SimpleAssert
            .eq(2, energyPayment.getInteger("targetType"), "energy sensor target type remains real configuration");
        SimpleAssert.that(
            !energyPayment.hasKey("energyL") && !energyPayment.hasKey("maxStorageL"),
            "NC energy observations are not priced as stored energy assets");
        NBTTagCompound changedType = (NBTTagCompound) energy.copy();
        changedType.setInteger("targetType", 3);
        SimpleAssert.that(
            !energyPayment.equals(
                HologramCoordinatePolicies.normalizePaymentTag(
                    "shedar.mods.ic2.nuclearcontrol.items.ItemCardEnergySensorLocation",
                    changedType)),
            "a different real sensor configuration cannot pay for the chosen configuration");
        NBTTagCompound unknownMode = new NBTTagCompound();
        unknownMode.setString(HologramCoordinatePolicies.PAYMENT, "future_mode");
        unknownMode.setInteger("x", 12);
        SimpleAssert.eq(
            "future_mode",
            HologramCoordinatePolicies.normalizePaymentTag(gt, unknownMode)
                .getString(HologramCoordinatePolicies.PAYMENT),
            "unknown payment mode is never stripped as an authorized normalization marker");

        ItemStack ordinary = new ItemStack(Items.paper);
        ordinary.setTagCompound(observed);
        ItemStack different = ordinary.copy();
        different.getTagCompound()
            .setInteger("opaqueAsset", 8);
        SimpleAssert.that(
            !HologramCoordinatePolicies.reviewedCoordinateItem(ordinary)
                && !HologramCoordinatePolicies.paymentEquivalent(ordinary, different),
            "a review marker on an ordinary item never makes its complete inventory payload fuzzy");
    }

    private static void sensorNativeStateBoundary() throws Exception {
        String registry = "gtmb:processing_native_sensor_fixture";
        Item card = (Item) Item.itemRegistry.getObject(registry);
        if (card == null) {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            card = (Item) unsafe.allocateInstance(Class.forName("gregtech.common.items.ItemSensorCard"));
            Field delegate = Item.class.getDeclaredField("delegate");
            delegate.setAccessible(true);
            delegate.set(
                card,
                ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Item.itemRegistry)
                    .getDelegate(card, Item.class));
            int free = 30000;
            while (free > 4095 && Item.getItemById(free) != null) free--;
            if (free <= 4095) throw new AssertionError("No isolated registered sensor item slot");
            Item.itemRegistry.addObject(free, registry, card);
        }
        NBTTagCompound serialized = new ItemStack(card, 3, 7).writeToNBT(new NBTTagCompound());
        serialized.setByte("Slot", (byte) 2);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("state", 2);
        tag.setInteger("x", 1340);
        tag.setInteger("y", 68);
        tag.setInteger("z", 260);
        tag.setString("title", "Configured title");
        tag.setInteger("mString", 1);
        tag.setString("mString0", "Observed LCR information");
        tag.setInteger("extraAsset", 64);
        serialized.setTag("tag", tag);
        NBTTagCompound original = (NBTTagCompound) serialized.copy();
        NBTTagCompound projected = HologramCoordinatePolicies.sensorStateProjection(serialized);
        NBTTagCompound expected = (NBTTagCompound) serialized.copy();
        expected.getCompoundTag("tag")
            .removeTag("state");
        SimpleAssert.that(
            projected.equals(expected) && serialized.equals(original),
            "state-only native comparison is detached and preserves every other serialized card field and observation");
        NBTTagCompound changedState = (NBTTagCompound) serialized.copy();
        changedState.getCompoundTag("tag")
            .setInteger("state", 3);
        SimpleAssert.that(
            projected.equals(HologramCoordinatePolicies.sensorStateProjection(changedState)),
            "exact registered native NO_TARGET/OUT_OF_RANGE int states do not alter the strict static card comparison");
        NBTTagCompound wrongType = (NBTTagCompound) serialized.copy();
        wrongType.getCompoundTag("tag")
            .setString("state", "2");
        SimpleAssert.that(
            wrongType.equals(HologramCoordinatePolicies.sensorStateProjection(wrongType)),
            "a same-named string state is never normalized as the native integer observation");
        NBTTagCompound paper = new ItemStack(Items.paper).writeToNBT(new NBTTagCompound());
        paper.setTag("tag", tag.copy());
        SimpleAssert.that(
            paper.equals(HologramCoordinatePolicies.sensorStateProjection(paper)),
            "ordinary item state and complete opaque payload remain exact");
        NBTTagCompound emptyAfterState = (NBTTagCompound) serialized.copy();
        NBTTagCompound stateOnly = new NBTTagCompound();
        stateOnly.setInteger("state", 2);
        emptyAfterState.setTag("tag", stateOnly);
        SimpleAssert.that(
            !HologramCoordinatePolicies.sensorStateProjection(emptyAfterState)
                .hasKey("tag"),
            "removing the sole reviewed native state restores an absent card tag without changing the source");
        for (String field : new String[] { "x", "title", "extraAsset", "mString0" }) {
            NBTTagCompound variant = (NBTTagCompound) serialized.copy();
            if (variant.getCompoundTag("tag")
                .hasKey(field, 3))
                variant.getCompoundTag("tag")
                    .setInteger(field, 999);
            else variant.getCompoundTag("tag")
                .setString(field, "different");
            SimpleAssert.that(
                !projected.equals(HologramCoordinatePolicies.sensorStateProjection(variant)),
                "state-only comparison preserves coordinate/configuration/asset/other observation differences "
                    + field);
        }
        NBTTagCompound panel = new NBTTagCompound();
        panel.setString("id", "IC2NCAdvancedInfoPanel");
        panel.setInteger("x", 1440);
        panel.setShort("facing", (short) 2);
        panel.setInteger("colorText", 1234);
        panel.setInteger("state", 12); // Not a reviewed panel-root runtime field.
        net.minecraft.nbt.NBTTagList items = new net.minecraft.nbt.NBTTagList();
        items.appendTag(serialized.copy());
        items.appendTag(paper.copy());
        panel.setTag("Items", items);
        NBTTagCompound panelBefore = (NBTTagCompound) panel.copy();
        NBTTagCompound staticPanel = HologramCoordinatePolicies.nativePanelProjection(panel);
        NBTTagCompound projectedCard = staticPanel.getTagList("Items", 10)
            .getCompoundTagAt(0);
        SimpleAssert.that(
            !projectedCard.getCompoundTag("tag")
                .hasKey("state")
                && !projectedCard.getCompoundTag("tag")
                    .hasKey("mString")
                && !projectedCard.getCompoundTag("tag")
                    .hasKey("mString0")
                && projectedCard.getCompoundTag("tag")
                    .getInteger("x") == 1340
                && projectedCard.getCompoundTag("tag")
                    .getInteger("extraAsset") == 64
                && projectedCard.getByte("Count") == 3
                && projectedCard.getShort("Damage") == 7
                && projectedCard.getByte("Slot") == 2
                && staticPanel.getInteger("state") == 12
                && staticPanel.getTagList("Items", 10)
                    .getCompoundTagAt(1)
                    .equals(paper)
                && panel.equals(panelBefore),
            "whole-panel projection drops only registered typed card observations, preserving all static root/slot/assets and ordinary items");
        for (String field : new String[] { "x", "extraAsset", "title" }) {
            NBTTagCompound variant = (NBTTagCompound) panel.copy();
            NBTTagCompound variantTag = variant.getTagList("Items", 10)
                .getCompoundTagAt(0)
                .getCompoundTag("tag");
            if (variantTag.hasKey(field, 3)) variantTag.setInteger(field, 999);
            else variantTag.setString(field, "different");
            SimpleAssert.that(
                !staticPanel.equals(HologramCoordinatePolicies.nativePanelProjection(variant)),
                "whole-panel presence comparison rejects different mapped coordinates/configuration/unknown assets "
                    + field);
        }
        NBTTagCompound wrongCount = (NBTTagCompound) panel.copy();
        wrongCount.getTagList("Items", 10)
            .getCompoundTagAt(0)
            .setByte("Count", (byte) 4);
        SimpleAssert.that(
            !staticPanel.equals(HologramCoordinatePolicies.nativePanelProjection(wrongCount)),
            "whole-panel presence cannot ignore missing or additional physical cards");
        NBTTagCompound wrongCacheType = (NBTTagCompound) panel.copy();
        wrongCacheType.getTagList("Items", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("tag")
            .setTag("mString0", new NBTTagCompound());
        SimpleAssert.that(
            !staticPanel.equals(HologramCoordinatePolicies.nativePanelProjection(wrongCacheType)),
            "same-named runtime data of an unreviewed type remains strict in the native panel comparison");
        NBTTagCompound unrelated = (NBTTagCompound) panel.copy();
        unrelated.setString("id", "opaque.panel");
        SimpleAssert.that(
            !HologramCoordinatePolicies.nativePanelPayload(unrelated)
                && unrelated.equals(HologramCoordinatePolicies.nativePanelProjection(unrelated)),
            "a foreign panel payload never receives registered NC card runtime projection");
    }

    private static void importedMaterialIdentityBoundary() throws Exception {
        NBTTagCompound tile = new NBTTagCompound();
        tile.setString("id", "BaseMetaTileEntity");
        tile.setShort("mID", (short) 23);
        HologramPresets.validateGtPayload(tile, 23, "BaseMetaTileEntity");
        expectIo(() -> HologramPresets.validateGtPayload(tile, 24, "BaseMetaTileEntity"));
        NBTTagCompound wrongType = (NBTTagCompound) tile.copy();
        wrongType.setDouble("mID", 23);
        expectIo(() -> HologramPresets.validateGtPayload(wrongType, 23, "BaseMetaTileEntity"));
        NBTTagCompound overflow = (NBTTagCompound) tile.copy();
        overflow.setLong("mID", 4294967296L + 23);
        expectIo(() -> HologramPresets.validateGtPayload(overflow, 23, "BaseMetaTileEntity"));
        NBTTagCompound foreign = (NBTTagCompound) tile.copy();
        foreign.setString("id", "TileJar");
        expectIo(() -> HologramPresets.validateGtPayload(foreign, 23, "BaseMetaTileEntity"));
        NBTTagCompound partial = new NBTTagCompound();
        partial.setShort("mID", (short) 23);
        partial.setInteger("textureIndex", 42);
        HologramPresets.validateGtPayload(partial, 23, "BaseMetaTileEntity");
        HologramPresets.validateGtPayload(null, 23, "BaseMetaTileEntity");
        NBTTagCompound pipe = (NBTTagCompound) tile.copy();
        pipe.setString("id", "BaseMetaPipeEntity");
        HologramPresets.validateGtPayload(pipe, 23, "BaseMetaPipeEntity");
        expectIo(() -> HologramPresets.validateGtPayload(pipe, 23, "BaseMetaTileEntity"));
        Method validate = HologramPresets.class.getDeclaredMethod("validateBlueprintCell", HologramPresets.Cell.class);
        validate.setAccessible(true);
        HologramPresets.Cell spoof = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        spoof.block = "minecraft:dirt";
        try {
            validate.invoke(null, spoof);
            throw new AssertionError("ordinary item/block spoof must fail at import boundary");
        } catch (InvocationTargetException rejected) {
            SimpleAssert.that(
                rejected.getCause() instanceof IOException,
                "item/block mismatch is rejected before placement or resource accounting");
        }
        HologramPresets.Cell lamp = HologramPresets.cell(new ItemStack(Blocks.redstone_lamp), 0, 0, 0, -1, false);
        lamp.block = "minecraft:lit_redstone_lamp";
        validate.invoke(null, lamp);
        HologramPresets.Cell furnace = HologramPresets.cell(new ItemStack(Blocks.furnace), 0, 0, 0, -1, false);
        furnace.block = "minecraft:lit_furnace";
        validate.invoke(null, furnace);
        SimpleAssert.eq(
            "minecraft:lit_redstone_lamp",
            lamp.block,
            "server material identity gate preserves the captured powered lamp state");
    }

    private static void ordinaryMaterialVariantBoundary() throws Exception {
        Method validate = HologramPresets.class.getDeclaredMethod("validateBlueprintCell", HologramPresets.Cell.class);
        validate.setAccessible(true);
        variant(validate, Blocks.wool, 15, 15);
        rejectVariant(validate, Blocks.wool, 15, 0);
        for (int meta = 0; meta < 16; meta++) {
            variant(validate, Blocks.log, meta, meta & 3);
            variant(validate, Blocks.log2, meta, meta & 3);
            variant(validate, Blocks.leaves, meta, meta & 3);
            variant(validate, Blocks.leaves2, meta, meta & 3);
        }
        rejectVariant(validate, Blocks.log, 9, 0);
        rejectVariant(validate, Blocks.leaves2, 13, 0);
        for (int meta = 0; meta < 5; meta++) variant(validate, Blocks.quartz_block, meta, meta >= 3 ? 2 : meta);
        rejectVariant(validate, Blocks.quartz_block, 4, 0);
        for (int meta = 0; meta < 8; meta++) variant(validate, Blocks.oak_stairs, meta, 0);
        rejectVariant(validate, Blocks.oak_stairs, 7, 1);
        variant(validate, Blocks.dirt, 0, 0);
        variant(validate, Blocks.dirt, 1, 0);
        variant(validate, Blocks.dirt, 2, 2);
        rejectVariant(validate, Blocks.dirt, 2, 0);
        rejectVariant(validate, Blocks.dirt, 0, 2);
        SimpleAssert
            .eq(0, Blocks.dirt.damageDropped(2), "podzol drop callback differs from its physical pick material");
        SimpleAssert.eq(
            2,
            HologramPresets.materialDamage(Blocks.dirt, 2),
            "native common pick callback preserves podzol payment");

        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Block casing = registeredVariantBlock(
            "processing_variant_casing",
            (Block) unsafe.allocateInstance(gregtech.common.blocks.BlockCasings1.class),
            true);
        variant(validate, casing, 1, 1);
        rejectVariant(validate, casing, 1, 0);
        SimpleAssert.eq(
            1,
            HologramPresets.materialDamage(casing, 1),
            "real GT casing common callback keeps the exact casing tier");
        Block directional = registeredVariantBlock("processing_no_subtype_direction", new Block(Material.rock) {

            @Override
            public int damageDropped(int meta) {
                return meta;
            }
        }, false);
        variant(validate, directional, 2, 0);
        rejectVariant(validate, directional, 2, 1);

        HologramPresets.Cell candidate = HologramPresets.cell(new ItemStack(Blocks.wool, 1, 15), 0, 0, 0, -1, false);
        Method paletteValidate = HologramPresets.class
            .getDeclaredMethod("validateBlueprintCell", HologramPresets.Cell.class, boolean.class);
        paletteValidate.setAccessible(true);
        paletteValidate.invoke(null, candidate, false);
        SimpleAssert.eq(
            15,
            candidate.stack()
                .getItemDamage(),
            "palette candidate carries its exact material without a fictitious world state");
    }

    private static Block registeredVariantBlock(String name, Block prototype, boolean casing) throws Exception {
        String registry = "gtmb:" + name;
        Object existing = Block.blockRegistry.getObject(registry);
        if (existing instanceof Block && existing != Blocks.air) return (Block) existing;
        // Unsafe skips Forge's final delegate initializer; install the same native registry delegate before admission.
        if (prototype.delegate == null) {
            Field delegate = Block.class.getDeclaredField("delegate");
            delegate.setAccessible(true);
            delegate.set(
                prototype,
                ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Block.blockRegistry)
                    .getDelegate(prototype, Block.class));
        }
        // Later suites deliberately reserve 4051 and 4087..4094; keep this fixture in a separate free range.
        int free = 3999;
        while (free > 0 && (Item.getItemById(free) != null || Block.getBlockById(free) != Blocks.air)) free--;
        if (free == 0) throw new AssertionError("No isolated variant block registry slot");
        Block.blockRegistry.addObject(free, registry, prototype);
        ItemBlock material = casing ? new gregtech.common.blocks.ItemCasings(prototype) : new ItemBlock(prototype);
        Item.itemRegistry.addObject(free, registry, material);
        return prototype;
    }

    private static void variant(Method validate, Block block, int metadata, int damage) throws Exception {
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(block, 1, damage), 0, 0, 0, -1, false);
        cell.block = Block.blockRegistry.getNameForObject(block);
        cell.metadata = metadata;
        validate.invoke(null, cell);
        HologramPresets.validateMaterialVariant(block, metadata, cell.stack());
        SimpleAssert.eq(metadata, cell.metadata, "accepted native material preserves its exact orientation/state");
    }

    private static void rejectVariant(Method validate, Block block, int metadata, int damage) throws Exception {
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(block, 1, damage), 0, 0, 0, -1, false);
        cell.block = Block.blockRegistry.getNameForObject(block);
        cell.metadata = metadata;
        try {
            validate.invoke(null, cell);
            throw new AssertionError("unpaid ordinary material variant must fail at import");
        } catch (InvocationTargetException rejected) {
            SimpleAssert.that(
                rejected.getCause() instanceof IOException,
                "variant mismatch is rejected before placement/payment");
        }
        expectIo(() -> HologramPresets.validateMaterialVariant(block, metadata, cell.stack()));
    }

    private static void automaticHintsFollowActualProcessing() throws Exception {
        Block previous = gregtech.api.GregTechAPI.sBlockMachines;
        try {
            // Exercise the registered GT root/cover policy without constructing or ticking a machine.
            gregtech.api.GregTechAPI.sBlockMachines = Blocks.stone;
            HologramPresets.Preset raw = new HologramPresets.Preset();
            raw.schema = 2;
            raw.prompt = "作者的全局说明";
            HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
            cell.block = "minecraft:stone";
            cell.prompt = "作者的设备说明";
            raw.cells.add(cell);
            NBTTagCompound nativeTag = new NBTTagCompound();
            nativeTag.setString("id", "BaseMetaTileEntity");
            nativeTag.setShort("mID", (short) 7);
            nativeTag.setString("mOwnerName", "PRIVATE_AUTHOR_NAME");
            nativeTag.setString("mOwnerUuid", "945b6d4d-09a7-4d0c-a6fb-545c5a21ff11");
            nativeTag.setInteger("currentTab", 4);
            nativeTag.setString("shutDownReasonID", "PRIVATE_SHUTDOWN_VALUE");
            nativeTag.setBoolean("mWasShutdown", true);
            nativeTag.setBoolean("mActive", true);
            nativeTag.setLong("lastAESampleTick", 37);
            NBTTagCompound opaque = new NBTTagCompound();
            opaque.setString("ownerUUID", "PRIVATE_RECIPE_LITERAL");
            nativeTag.setTag("encodedRecipe", opaque.copy());
            NBTTagCompound coverData = new NBTTagCompound();
            coverData.setString("ownerUUID", "PRIVATE_COVER_UUID");
            coverData.setInteger("frequency", 39);
            NBTTagCompound cover = new NBTTagCompound();
            cover.setTag("d", coverData);
            net.minecraft.nbt.NBTTagList covers = new net.minecraft.nbt.NBTTagList();
            covers.appendTag(cover);
            covers.appendTag(new ItemStack(Items.paper, 3).writeToNBT(new NBTTagCompound()));
            nativeTag.setTag("gt.covers", covers);
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(nativeTag);
            HologramBlueprintProcessing.Policy full = completed("self");
            full.deidentificationLevel = 10;
            full.autoHints = "full";
            HologramPresets.Preset processed = HologramBlueprintProcessing.process(raw, full, 0, 0, 64, 0);
            String explanation = processed.cells.get(0).prompt;
            for (String field : new String[] { "mOwnerName", "mOwnerUuid", "currentTab", "shutDownReasonID",
                "mWasShutdown", "mActive", "lastAESampleTick", "tile:/gt.covers/#0/d/ownerUUID", "frequency" })
                SimpleAssert
                    .that(explanation.contains(field), "full explains the actual registered field change " + field);
            SimpleAssert.that(
                explanation.startsWith(cell.prompt + "\n"),
                "automatic explanations preserve the author's cell text");
            SimpleAssert
                .that(processed.prompt.startsWith(raw.prompt + "\n"), "full preserves the author's global text");
            for (String secret : new String[] { "PRIVATE_AUTHOR_NAME", "945b6d4d-09a7-4d0c-a6fb-545c5a21ff11",
                "PRIVATE_RECIPE_LITERAL", "PRIVATE_COVER_UUID", "PRIVATE_SHUTDOWN_VALUE" })
                SimpleAssert.that(
                    !explanation.contains(secret) && !processed.prompt.contains(secret)
                        && !HologramBlueprintNbt.decode(processed.processingNbt)
                            .toString()
                            .contains(secret),
                    "automatic text/provenance never leaks removed or opaque values " + secret);
            SimpleAssert.that(
                !explanation.contains("encodedRecipe"),
                "opaque recipe keys are not guessed into identity reports");
            SimpleAssert.that(
                opaque.equals(
                    HologramBlueprintNbt.decode(processed.cells.get(0).tileNbt)
                        .getTag("encodedRecipe")),
                "hint reporting leaves opaque recipe content exact");
            HologramPresets.Preset repeated = HologramBlueprintProcessing.process(raw, full, 0, 0, 64, 0);
            SimpleAssert.eq(
                explanation,
                repeated.cells.get(0).prompt,
                "reprocessing immutable raw source produces the same explanations");
            HologramPresets.Preset loaded = HologramPresets.copy(processed);
            loaded.cells.get(0).x = 5;
            loaded.cells.get(0).prompt += "\n作者后加说明";
            HologramBlueprintProcessing.invalidate(loaded, "geometry");
            HologramPresets.Preset reviewedAgain = HologramBlueprintProcessing.process(loaded, full, 0, 0, 64, 0);
            SimpleAssert.that(
                reviewedAgain.cells.get(0).prompt.contains("作者后加说明")
                    && occurrences(reviewedAgain.cells.get(0).prompt, "已清除设备所有者标识") == 1,
                "moving and re-reviewing a persisted cell retains actual irreversible descriptions exactly once");
            HologramBlueprintProcessing.Policy none = HologramBlueprintProcessing.Policy.read(full.write());
            none.autoHints = "none";
            HologramPresets.Preset without = HologramBlueprintProcessing.process(loaded, none, 0, 0, 64, 0);
            SimpleAssert.eq(
                "作者的设备说明\n作者后加说明",
                without.cells.get(0).prompt,
                "none removes recorded generated blocks while preserving edits made after those blocks");
            SimpleAssert.eq(raw.prompt, without.prompt, "none does not keep stale automatic global descriptions");
            SimpleAssert.eq(
                cell.prompt,
                HologramBlueprintProcessing.process(raw, none, 0, 0, 64, 0).cells.get(0).prompt,
                "none emits no automatic cell text from raw input");
            HologramBlueprintProcessing.Policy light = HologramBlueprintProcessing.Policy.read(full.write());
            light.autoHints = "light";
            String recipient = HologramBlueprintProcessing.process(raw, light, 0, 0, 64, 0).cells.get(0).prompt;
            SimpleAssert.that(
                recipient.contains("当前玩家身份") && !recipient.contains("currentTab")
                    && !recipient.contains("lastAESampleTick"),
                "light retains placement obligations without complete audit detail");
            HologramBlueprintProcessing.Policy retain = completed("self");
            SimpleAssert.that(
                HologramBlueprintProcessing.process(raw, retain, 0, 0, 64, 0).cells.get(0).prompt
                    .contains("模板保留原所有者字段"),
                "light warns about retained registered identity without printing its value");
            HologramPresets.Preset quantum = HologramPresets.copy(raw);
            NBTTagCompound quantumTag = HologramBlueprintNbt.decode(quantum.cells.get(0).tileNbt);
            quantumTag.setString(HologramNetworkBindings.GROUP, "q1");
            quantumTag.setString(HologramNetworkBindings.ROLE, "join");
            quantum.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(quantumTag);
            quantum = HologramQuantumGroups.configure(quantum, HologramQuantumGroups.groups(quantum));
            light.quantum = "clear";
            String clearNote = HologramBlueprintProcessing.process(quantum, light, 0, 0, 64, 0).cells.get(0).prompt;
            SimpleAssert.that(
                clearNote.contains("量子网络绑定已清除") && clearNote.contains("自己的有效终端"),
                "quantum clear still instructs the recipient after its group descriptor is removed");
        } finally {
            gregtech.api.GregTechAPI.sBlockMachines = previous;
        }
        HologramPresets.Preset carpenter = new HologramPresets.Preset();
        carpenter.schema = 2;
        HologramPresets.Cell carpenterCell = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        carpenterCell.block = "minecraft:stone";
        NBTTagCompound carpenterTag = new NBTTagCompound();
        carpenterTag.setString("id", "TileEntityCarpentersSlope");
        carpenterTag.setString("cbOwner", "PRIVATE_NATIVE_OWNER");
        carpenterTag.setInteger("cbMetadata", 19);
        carpenterCell.tileNbt = HologramBlueprintNbt.encodeCaptured(carpenterTag);
        carpenter.cells.add(carpenterCell);
        HologramBlueprintProcessing.Policy carpenterPolicy = completed("self");
        carpenterPolicy.deidentificationLevel = 1;
        carpenterPolicy.autoHints = "full";
        String ownerNote = HologramBlueprintProcessing.process(carpenter, carpenterPolicy, 0, 0, 64, 0).cells
            .get(0).prompt;
        SimpleAssert.that(
            ownerNote.contains("tile:/cbOwner") && !ownerNote.contains("PRIVATE_NATIVE_OWNER")
                && !ownerNote.contains("cbMetadata"),
            "full reports scoped Carpenter ownership without reporting or altering shape fields");
        carpenterTag.setString("id", "opaque.tile");
        carpenterCell.tileNbt = HologramBlueprintNbt.encodeCaptured(carpenterTag);
        SimpleAssert.that(
            HologramBlueprintProcessing.process(carpenter, carpenterPolicy, 0, 0, 64, 0).cells.get(0).prompt == null,
            "an opaque same-named owner field is preserved and never guessed into an automatic report");
        HologramBlueprintProcessing.Policy self = completed("self");
        self.autoHints = "light";
        String absolute = HologramBlueprintProcessing.process(source(102), self, 0, 100, 64, 200).cells.get(0).prompt;
        SimpleAssert.that(
            absolute.contains("锚定原绝对坐标") && absolute.contains("放置所在维度"),
            "light warns about external absolute anchors without leaking the source tuple");
        HologramPresets.Preset inside = source(104);
        String relationId = HologramCoordinatePolicies.inspect(inside, 0, 100, 64, 200)
            .get(0).id;
        self.coordinateOverrides.put(relationId, "absolute");
        SimpleAssert.that(
            HologramBlueprintProcessing.process(inside, self, 0, 100, 64, 200).cells.get(0).prompt.contains("锚定原绝对坐标"),
            "explicit internal absolute choices carry the same fixed-coordinate obligation");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length()))
            count++;
        return count;
    }

    private static void atomicPolicyLibrary() throws Exception {
        Field cache = HologramProcessingPresets.class.getDeclaredField("cached");
        cache.setAccessible(true);
        Object previous = cache.get(null);
        Method entry = HologramProcessingPresets.class.getDeclaredMethod(
            "entry",
            String.class,
            String.class,
            HologramBlueprintProcessing.Policy.class,
            boolean.class);
        entry.setAccessible(true);
        Method write = HologramProcessingPresets.class.getDeclaredMethod("write", Path.class, List.class);
        write.setAccessible(true);
        Method read = HologramProcessingPresets.class.getDeclaredMethod("read", Path.class);
        read.setAccessible(true);
        Path directory = Files.createTempDirectory("gtmb-processing-regression-");
        Path file = directory.resolve("presets.nbt"), blocked = directory.resolve("blocked");
        try {
            HologramBlueprintProcessing.Policy policy = completed("share");
            policy.ignoredNbtKeys.add("currentTab");
            List<HologramProcessingPresets.Entry> list = new ArrayList<>();
            list.add((HologramProcessingPresets.Entry) entry.invoke(null, "original", "分享流程", policy, true));
            write.invoke(null, file, list);
            byte[] previousBytes = Files.readAllBytes(file);
            @SuppressWarnings("unchecked")
            List<HologramProcessingPresets.Entry> loaded = (List<HologramProcessingPresets.Entry>) read
                .invoke(null, file);
            SimpleAssert.eq("分享流程", loaded.get(0).name, "named flow survives binary NBT persistence");
            SimpleAssert.that(
                loaded.get(0).policy.confirmedStages.isEmpty(),
                "applying a flow still requires walking every stage");
            SimpleAssert.eq(
                "currentTab",
                loaded.get(0).policy.ignoredNbtKeys.get(0),
                "reusable flow retains global ignored root settings");
            loaded.get(0).name = "mutated reader";
            SimpleAssert.eq(
                "分享流程",
                HologramProcessingPresets.all()
                    .get(0).name,
                "flow library cache owns detached entries");
            Files.createDirectory(blocked);
            Files.write(blocked.resolve("keep"), new byte[] { 1 });
            list.get(0).name = "updated";
            try {
                write.invoke(null, blocked, list);
                throw new AssertionError("expected failed atomic replacement");
            } catch (InvocationTargetException expected) {
                SimpleAssert.that(expected.getCause() instanceof IOException, "failed replacement surfaces IO failure");
            }
            SimpleAssert.that(
                Arrays.equals(previousBytes, Files.readAllBytes(file)),
                "failed flow write preserves previous bytes");
            SimpleAssert.eq(
                "分享流程",
                HologramProcessingPresets.all()
                    .get(0).name,
                "failed flow write preserves previous cache");
        } finally {
            cache.set(null, previous);
            Files.deleteIfExists(blocked.resolve("keep"));
            Files.deleteIfExists(blocked);
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static HologramBlueprintProcessing.Policy completed(String tendency) throws IOException {
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults(tendency, 0);
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        return policy;
    }

    private static HologramPresets.Preset source(int targetX) throws IOException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.name = "fixture";
        HologramPresets.Cell host = new HologramPresets.Cell();
        host.block = "minecraft:stone";
        host.item = "minecraft:stone";
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "IC2NCInfoPanelExtender");
        tag.setBoolean("partOfScreen", true);
        tag.setInteger("coreX", targetX);
        tag.setInteger("coreY", 64);
        tag.setInteger("coreZ", 200);
        tag.setString("ownerName", "original");
        host.tileNbt = HologramBlueprintNbt.encodeCaptured(tag);
        preset.cells.add(host);
        HologramPresets.Cell target = new HologramPresets.Cell();
        target.block = "minecraft:stone";
        target.item = "minecraft:stone";
        target.x = 4;
        preset.cells.add(target);
        return preset;
    }

    private interface IoAction {

        void run() throws Exception;
    }

    private static void expectIo(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) { /* expected */ }
    }
}
