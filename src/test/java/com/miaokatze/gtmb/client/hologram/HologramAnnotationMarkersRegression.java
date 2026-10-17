package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** The preview exposes compact annotation targets and groups structure notes before drawing. */
public final class HologramAnnotationMarkersRegression {

    private HologramAnnotationMarkersRegression() {}

    public static void run() {
        quantumGroupsRoundTrip();
        blockerSeveritySurvivesAggregation();
        resourceBlockersHaveIndependentMarker();
        NBTTagCompound data = new NBTTagCompound();
        data.setString("blueprintPrompt", "Foundation first");
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 8; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("dx", i);
            cell.setString("structureId", "machine");
            cell.setString("prompt", i < 4 ? "Shared instruction" : "Second instruction");
            cells.appendTag(cell);
        }
        NBTTagCompound loose = new NBTTagCompound();
        loose.setInteger("dx", 10);
        loose.setString("prompt", "Loose block");
        cells.appendTag(loose);
        data.setTag("cells", cells);
        NBTTagCompound area = new NBTTagCompound();
        area.setIntArray("bounds", new int[] { 0, 0, 0, 7, 0, 0 });
        area.setString("prompt", "Area instruction");
        NBTTagList areas = new NBTTagList();
        areas.appendTag(area);
        data.setTag("hintAreas", areas);
        List<HologramAnnotationMarkers.Marker> markers = HologramAnnotationMarkers.collect(new HologramState(data));
        SimpleAssert.eq(4, markers.size(), "blueprint, loose cell, complete structure, and area each have one marker");
        HologramAnnotationMarkers.Marker structure = null;
        for (HologramAnnotationMarkers.Marker marker : markers)
            if (marker.key.equals("structure:machine")) structure = marker;
        SimpleAssert.that(structure != null, "structure marker has a stable pin identifier");
        SimpleAssert.eq(
            "Shared instruction\nSecond instruction",
            structure.prompt,
            "repeated member annotations appear once inside the structure tooltip");
        SimpleAssert.eq(7, structure.bounds[3], "structure marker spans its annotated members");
        structure.x = 100;
        structure.y = 80;
        SimpleAssert.that(
            structure.contains(100, 80) && !structure.contains(120, 80),
            "only the miniature marker opens its annotation tooltip");
    }

    private static void quantumGroupsRoundTrip() {
        NBTTagCompound source = new NBTTagCompound();
        source.setBoolean("virtual", true);
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 5; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("dx", i * 2);
            cell.setInteger("dy", i);
            cell.setInteger("dz", -i);
            cell.setString("structureId", i == 4 ? "other" : "selected");
            String group = i == 3 ? "q2" : "q1";
            cell.setString("networkGroup", group);
            NBTTagCompound hint = new NBTTagCompound();
            hint.setString("type", "quantumNetwork");
            hint.setString("group", group);
            hint.setString("hint", group.equals("q1") ? "Connect furnace network" : "Connect storage network");
            cell.setTag("specialHint", hint);
            cell.setString("prompt", i == 3 ? "Storage member" : "Furnace member");
            cells.appendTag(cell);
        }
        source.setTag("cells", cells);
        HologramState state;
        try {
            state = new HologramState(
                com.miaokatze.gtmb.hologram.HologramSnapshotCodec
                    .decode(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(source)));
        } catch (java.io.IOException failure) {
            throw new AssertionError("quantum annotation DTO codec round trip", failure);
        }
        SimpleAssert.eq("q1", state.cells.get(0).networkGroup, "network group survives actual snapshot codec");
        SimpleAssert.eq(
            "Connect furnace network",
            state.cells.get(0).specialHint.getString("hint"),
            "specialized group hint survives snapshot codec and client state decoding");
        cells.getCompoundTagAt(0)
            .getCompoundTag("specialHint")
            .setString("hint", "mutated source");
        SimpleAssert.eq(
            "Connect furnace network",
            state.cells.get(0).specialHint.getString("hint"),
            "client hint owns an independent copy of the incoming DTO");
        List<HologramAnnotationMarkers.Marker> markers = HologramAnnotationMarkers.collect(state);
        SimpleAssert
            .eq(2, markers.size(), "five grouped cells draw two specialized markers without ordinary duplicates");
        HologramAnnotationMarkers.Marker first = markers.get(0), second = markers.get(1);
        SimpleAssert.eq("quantum:q1", first.key, "group pin identity is independent of coordinates and cell ordering");
        SimpleAssert.eq("量子网络组 1", first.title, "first group has a readable specialized title");
        SimpleAssert.eq("量子网络组 2", second.title, "different network group stays distinguishable");
        SimpleAssert.eq(
            "Connect furnace network\nFurnace member",
            first.prompt,
            "same-group hint and independent per-cell prompts deduplicate in the single hover/pin surface");
        SimpleAssert.eq(8, first.bounds[3], "whole-blueprint group bounds span all current members");
        first.x = 120;
        first.y = 90;
        SimpleAssert.that(
            first.contains(120, 90) && !first.contains(130, 90),
            "specialized group uses the existing compact marker hit area");
        state.data.setString("activeStructure", "selected");
        markers = HologramAnnotationMarkers.collect(state);
        SimpleAssert.eq(4, markers.get(0).bounds[3], "structure filtering excludes another structure's group member");
        SimpleAssert.eq(2, markers.get(0).bounds[4], "group marker layer bounds use filtered current pose");
        state.data.setString("activeStructure", "other");
        markers = HologramAnnotationMarkers.collect(state);
        SimpleAssert.eq(1, markers.size(), "selected structure cannot expose another network group");
        SimpleAssert
            .eq(8, markers.get(0).bounds[0], "marker is anchored to selected structure's current transformed cell");
        state.data.setBoolean("virtual", false);
        for (HologramAnnotationMarkers.Marker marker : HologramAnnotationMarkers.collect(state)) SimpleAssert
            .that(!marker.key.startsWith("quantum:"), "live entity views never acquire virtual network bindings");
        NBTTagCompound invalid = new NBTTagCompound();
        invalid.setString("networkGroup", "q1");
        NBTTagCompound mismatched = new NBTTagCompound();
        mismatched.setString("type", "quantumNetwork");
        mismatched.setString("group", "q2");
        invalid.setTag("specialHint", mismatched);
        SimpleAssert.that(
            !new HologramState.Cell(0, invalid).quantumNetworkHint(),
            "mismatched descriptor cannot silently attribute a cell to another network");
    }

    private static void blockerSeveritySurvivesAggregation() {
        NBTTagCompound data = new NBTTagCompound(), cell = new NBTTagCompound();
        cell.setInteger("x", 7);
        cell.setInteger("dx", 2);
        cell.setString("structureId", "machine");
        NBTTagList cells = new NBTTagList(), issues = new NBTTagList();
        cells.appendTag(cell);
        data.setTag("cells", cells);
        NBTTagCompound warning = new NBTTagCompound();
        warning.setString("severity", "warning");
        warning.setString("code", "ae.node_reset");
        warning.setLong("count", 1);
        warning.setInteger("x", 7);
        warning.setInteger("y", 0);
        warning.setInteger("z", 0);
        warning.setString("structureId", "machine");
        issues.appendTag(warning);
        data.setTag("aeIssues", issues);
        for (HologramAnnotationMarkers.Marker marker : HologramAeWarningMarkers.collect(new HologramState(data)))
            SimpleAssert.that(!marker.blocker, "Ordinary warnings keep the warning marker appearance");
        NBTTagCompound blocker = (NBTTagCompound) warning.copy();
        blocker.setString("severity", "blocker");
        blocker.setString("code", "ae.unreviewed_tile_field");
        issues.appendTag(blocker);
        List<HologramAnnotationMarkers.Marker> markers = HologramAeWarningMarkers.collect(new HologramState(data));
        SimpleAssert.eq(2, markers.size(), "Comprehensive and structure marker still aggregate each issue once");
        for (HologramAnnotationMarkers.Marker marker : markers)
            SimpleAssert.that(marker.blocker, "A mixed warning/blocker group retains the red cross severity");
    }

    private static void resourceBlockersHaveIndependentMarker() {
        NBTTagCompound data = new NBTTagCompound(), processing = new NBTTagCompound(), blocker = new NBTTagCompound();
        blocker.setInteger("x", 12);
        blocker.setInteger("y", 4);
        blocker.setInteger("z", -3);
        blocker.setString("path", "tile:/unknownInventory");
        blocker.setString("message", "Unsupported inventory schema");
        NBTTagList blockers = new NBTTagList();
        blockers.appendTag(blocker);
        processing.setTag("resourceBlockers", blockers);
        data.setTag("processing", processing);
        List<HologramAnnotationMarkers.Marker> markers = HologramAnnotationMarkers.collect(new HologramState(data));
        SimpleAssert
            .eq(1, markers.size(), "A non-AE resource blocker remains visible without old AE issues or preview cells");
        HologramAnnotationMarkers.Marker marker = markers.get(0);
        SimpleAssert.eq("resource:report", marker.key, "Resource reports have a stable independent pin identity");
        SimpleAssert.that(
            marker.blocker && marker.bounds == null,
            "Raw resource coordinates use a global red cross rather than a guessed transformed cell");
        SimpleAssert.that(
            marker.prompt.contains("12,4,-3") && marker.prompt.contains("tile:/unknownInventory"),
            "The compact report preserves the exact raw cell and NBT path");
        processing.setBoolean("resourceReviewTruncated", true);
        processing.setLong("resourceReviewTotal", 8193);
        marker = HologramAnnotationMarkers.collect(new HologramState(data))
            .get(0);
        SimpleAssert.that(
            marker.title.contains("未完整") && marker.prompt.contains("8193") && marker.prompt.contains("全局根条目"),
            "An over-budget report never presents its listed rows as the complete set");
    }
}
