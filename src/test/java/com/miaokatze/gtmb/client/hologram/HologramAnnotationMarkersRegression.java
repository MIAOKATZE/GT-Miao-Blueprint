package com.miaokatze.gtmb.client.hologram;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** The preview exposes compact annotation targets and groups structure notes before drawing. */
public final class HologramAnnotationMarkersRegression {

    private HologramAnnotationMarkersRegression() {}

    public static void run() {
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
}
