// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The whole-blueprint form of the server membership rule, for one frozen portable source. */
public final class HologramBlueprintParticipation {

    private final Map<String, Boolean> enabled = new HashMap<>();
    private boolean hasDisabled;

    private HologramBlueprintParticipation() {}

    static HologramBlueprintParticipation fromPreset(HologramPresets.Preset preset) {
        HologramBlueprintParticipation scope = new HologramBlueprintParticipation();
        if (preset.structures != null) for (HologramPresets.Structure structure : preset.structures) {
            if (structure != null) scope.add(structure.id, structure.enabled);
        }
        return scope;
    }

    public static HologramBlueprintParticipation fromJson(JsonObject blueprint) {
        HologramBlueprintParticipation scope = new HologramBlueprintParticipation();
        JsonElement structures = blueprint.get("structures");
        if (structures != null && structures.isJsonArray()) for (JsonElement member : structures.getAsJsonArray()) {
            if (!member.isJsonObject()) continue;
            JsonObject row = member.getAsJsonObject();
            JsonElement flag = row.get("enabled");
            scope.add(id(row.get("id")), flag == null || !flag.isJsonPrimitive() || flag.getAsBoolean());
        }
        return scope;
    }

    private void add(String id, boolean participates) {
        if (id == null || id.isEmpty()) return;
        enabled.merge(id, participates, (first, second) -> first || second);
        hasDisabled |= !participates;
    }

    boolean includes(HologramPresets.Cell cell) {
        if (!hasDisabled) return true;
        if (cell.structureIds == null || cell.structureIds.isEmpty())
            return !Boolean.FALSE.equals(enabled.get(cell.structureId));
        boolean disabledMember = false;
        for (String id : cell.structureIds) {
            Boolean entry = enabled.get(id);
            if (Boolean.TRUE.equals(entry)) return true;
            disabledMember |= Boolean.FALSE.equals(entry);
        }
        return !disabledMember;
    }

    public boolean hasDisabledMembers() {
        return hasDisabled;
    }

    public boolean includes(JsonObject cell) {
        if (!hasDisabled) return true;
        JsonElement ids = cell.get("structureIds");
        if (ids == null || !ids.isJsonArray() || ids.getAsJsonArray().size() == 0)
            return !Boolean.FALSE.equals(enabled.get(id(cell.get("structureId"))));
        boolean disabledMember = false;
        for (JsonElement id : ids.getAsJsonArray()) {
            Boolean entry = enabled.get(id(id));
            if (Boolean.TRUE.equals(entry)) return true;
            disabledMember |= Boolean.FALSE.equals(entry);
        }
        return !disabledMember;
    }

    private static String id(JsonElement value) {
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }
}
