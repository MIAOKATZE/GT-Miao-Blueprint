package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;

/** Internal domain boundary. */
final class HologramAreaDeclarations {

    private HologramAreaDeclarations() {}

    static void bindAreaMemberships(HologramSession s) {
        if (s.areaStructureId.isEmpty() || s.capture == null || s.capture.pending) return;
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area == null) return;
        HologramArea.Structure own = area.structures.get(s.areaStructureId);
        if (own == null) return;
        own.capture = s.capture;
        Map<String, HologramSession> editors = HologramWorkspace.AREA_STRUCTURES.get(s.player.getUniqueID());
        if (editors != null) for (HologramSession editor : editors.values())
            if (editor.capture != null) for (HologramCapture.Cell cell : editor.capture.cells) cell.candidates = null;
        for (HologramCapture.Cell cell : s.capture.cells) {
            List<String> ids = area.memberships(cell.x, cell.y, cell.z);
            if ((cell.element != null || cell.anchor) && !ids.contains(own.id)) ids.add(own.id);
            HologramBlueprintData.setMemberships(cell, ids);
            cell.candidates = null;
        }
    }

    static HologramSession areaDeclarationSession(HologramSession s, HologramArea area, String id) {
        Map<String, HologramSession> sessions = HologramWorkspace.AREA_STRUCTURES
            .computeIfAbsent(s.player.getUniqueID(), ignored -> new HashMap<>());
        return areaDeclarationSession(s, area, id, sessions);
    }

    /** Import preparation owns a separate cache until its final atomic publication. */
    static HologramSession areaDeclarationSession(HologramSession s, HologramArea area, String id,
        Map<String, HologramSession> sessions) {
        HologramArea.Structure structure = area.structures.get(id);
        if (structure == null || structure.capture == null
            || s.player.worldObj.getTileEntity(structure.x, structure.y, structure.z)
                != structure.machine.getBaseMetaTileEntity())
            return null;
        HologramSession cached = sessions.get(id);
        if (cached != null && cached.capture == structure.capture) return cached;
        HologramSession source = new HologramSession(s.player, structure.x, structure.y, structure.z, true, s.side);
        source.areaStructureId = id;
        source.main = structure.capture.trigger.stackSize;
        source.mainExplicit = false;
        source.channels = new NBTTagCompound();
        ChannelDataAccessor.iterateChannelData(structure.capture.trigger)
            .forEach(entry -> source.channels.setInteger(entry.getKey(), entry.getValue()));
        source.capture = structure.capture;
        source.capabilities = HologramCapabilities
            .describe(structure.capture.context, structure.capture.trigger, structure.capture.channelReport)
            .withSavedChannels(source.channels);
        sessions.put(id, source);
        return source;
    }
}
