package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Revision-bound local native mark editor. Deliberately has no server session, nonce, sequence or ACK. */
public final class HologramLocalAuthorAe {

    private static final Gson GSON = new Gson();

    private HologramLocalAuthorAe() {}

    public static NBTTagCompound preview(JsonObject source, long revision) throws IOException {
        HologramPresets.Cell cell = HologramLocalAuthorSemantics.cell(source);
        HologramAeBlueprint.Analysis analysis = analyze(cell);
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("localAuthor", true);
        result.setLong("planRevision", revision);
        result.setInteger("x", cell.x);
        result.setInteger("y", cell.y);
        result.setInteger("z", cell.z);
        result.setString("block", cell.block);
        result.setInteger("metadata", cell.metadata);
        result.setString("structureId", cell.structureId == null ? "" : cell.structureId);
        NBTTagList slots = new NBTTagList();
        long bytes = 0;
        if (analysis.slots.size() > 512) throw new IOException("AE preview slot budget exceeded");
        for (HologramAeBlueprint.Slot slot : analysis.slots) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", slot.id);
            row.setString("kind", slot.kind);
            row.setString("label", HologramAePolicy.fallback(slot.label));
            row.setInteger("limit", slot.limit);
            row.setBoolean("enabled", slot.enabled);
            row.setString("issueCode", slot.issueCode);
            row.setString("issuePath", slot.issuePath);
            NBTTagList allowed = new NBTTagList();
            for (String rule : slot.allowed) allowed.appendTag(new NBTTagString(rule));
            row.setTag("allowed", allowed);
            ItemStack stack = slot.stack();
            if (stack != null) {
                NBTTagCompound encoded = HologramBlueprintNbt.nativePayload(stack.writeToNBT(new NBTTagCompound()));
                bytes += HologramAeProtocol.bytes(encoded, HologramAeProtocol.MAX_CANDIDATE_BYTES);
                if (bytes > HologramAeProtocol.MAX_STACK_AGGREGATE) throw new IOException("AE preview stack budget");
                row.setTag("stack", encoded);
            }
            slots.appendTag(row);
        }
        NBTTagList issues = new NBTTagList();
        if (analysis.issues.size() > HologramAePolicy.MAX_ISSUES) throw new IOException("AE report budget");
        for (HologramAeBlueprint.Issue issue : analysis.issues) {
            NBTTagCompound row = new HologramAePolicy.Issue(
                issue.code,
                HologramAePolicy.severity(issue.severity),
                issue.path,
                HologramAePolicy.fallback(issue.message),
                cell.x,
                cell.y,
                cell.z).dto(0, 0, 0);
            HologramAePolicy.projectResolution(row, analysis, result.getString("structureId"));
            issues.appendTag(row);
        }
        result.setTag("slots", slots);
        result.setTag("issues", issues);
        return result;
    }

    /** A selected record plus small header; no full blueprint materialization for one slot edit. */
    public static JsonObject edit(JsonObject sourceCell, JsonObject header, NBTTagCompound request) throws IOException {
        HologramPresets.Preset preset = HologramLocalAuthorSemantics.preset(header);
        JsonElement beforeHeader = GSON.toJsonTree(preset);
        HologramPresets.Cell cell = HologramLocalAuthorSemantics.cell(sourceCell);
        JsonElement beforeCell = GSON.toJsonTree(cell);
        HologramAeBlueprint.Analysis analysis = analyze(cell);
        String mutation = request.getString("mutation");
        if (!"SET".equals(mutation) && !"CLEAR".equals(mutation)
            || "SET".equals(mutation) != request.hasKey("stack", 10)
            || "CLEAR".equals(mutation) && request.hasKey("stack")
            || request.hasKey("sourceInventory")
            || request.hasKey("path")
            || request.hasKey("owner")
            || request.hasKey("slotRole")
            || request.hasKey("cost")
            || request.hasKey("hostClass")) throw new IOException("AE mark mutation shape invalid");
        HologramAeProtocol.bytes(request, HologramAeProtocol.MAX_EDIT_BYTES);
        HologramAeBlueprint.Slot slot = null;
        for (HologramAeBlueprint.Slot candidate : analysis.slots)
            if (candidate.id.equals(request.getString("slotId"))) slot = candidate;
        if (slot == null || "SET".equals(mutation) && !slot.enabled)
            throw new IOException("AE slot is invalid or disabled");
        ItemStack marked = HologramAeProtocol.candidate(request, slot);
        HologramAeBlueprint.Analysis updated = HologramAeBlueprint
            .updateSlot(cell.block(), cell.metadata, analysis.payload(), slot.id, marked);
        boolean rebind = marked == null && "readonly".equals(slot.kind)
            && (slot.id.startsWith("def:") || slot.id.matches("@part=\\d+/def:.*"));
        ItemStack shell = rebind ? HologramAeBlueprint.sampleIdentity(cell.block(), cell.metadata, updated.payload())
            : null;
        if (rebind && shell == null
            && cell.block()
                .getClass()
                .getName()
                .equals("codechicken.multipart.BlockMultipart")
            && HologramAeBlueprint.withoutAeMultipart(updated.payload())
                .getTagList("parts", 10)
                .tagCount() > 0)
            throw new IOException("Remaining non-AE multipart identity cannot be rebound safely");
        preset.aeIssues = HologramAePolicy
            .resolveAfterMutation(preset.aeIssues, cell.x, cell.y, cell.z, slot.id, analysis, updated);
        HologramAeProtocol.bytes(updated.payload(), HologramAeProtocol.MAX_STACK_AGGREGATE);
        String payload = HologramBlueprintNbt.encodeCanonical(cell.block(), updated.payload());
        cell.tileNbt = payload == null ? "" : payload;
        cell.itemNbt = "";
        if (rebind) {
            if (shell == null) {
                cell.block = "minecraft:air";
                cell.metadata = cell.damage = 0;
                cell.item = null;
                cell.excluded = true;
                cell.tileNbt = "";
                cell.allowedTargets = new ArrayList<>();
                cell.facing = -1;
            } else {
                cell.item = String.valueOf(Item.itemRegistry.getNameForObject(shell.getItem()));
                cell.damage = shell.getItemDamage();
                if (cell.allowedTargets != null)
                    cell.allowedTargets = new ArrayList<>(Collections.singletonList(new HologramPresets.Target(shell)));
            }
        }
        HologramBlueprintProcessing.invalidate(preset, "resources");
        JsonObject result = new JsonObject();
        result.add("cell", HologramLocalAuthorSemantics.delta(sourceCell, beforeCell, GSON.toJsonTree(cell)));
        JsonObject changedHeader = new JsonObject();
        JsonObject updatedHeader = HologramLocalAuthorSemantics.delta(header, beforeHeader, GSON.toJsonTree(preset))
            .getAsJsonObject();
        for (String key : new String[] { "aeIssues", "processingNbt" }) {
            if (!java.util.Objects.equals(header.get(key), updatedHeader.get(key))) changedHeader
                .add(key, updatedHeader.has(key) ? updatedHeader.get(key) : com.google.gson.JsonNull.INSTANCE);
        }
        result.add("headerPatch", changedHeader);
        return result;
    }

    private static HologramAeBlueprint.Analysis analyze(HologramPresets.Cell cell) throws IOException {
        if (cell.controller || cell.excluded || cell.block() == null || cell.block() == Blocks.air)
            throw new IOException("AE source cell is not editable");
        NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
        if (!HologramAeBlueprint.supports(cell.block(), payload))
            throw new IOException("AE native adapter unavailable");
        // Registry shell is independent from candidate slot data; native host sample must match it.
        ItemStack nativeShell = HologramAeBlueprint.sampleIdentity(cell.block(), cell.metadata, payload);
        if (nativeShell != null && cell.stack() != null && !HologramValues.sameMaterial(nativeShell, cell.stack()))
            throw new IOException("AE target shell identity changed");
        return HologramAeBlueprint.analyze(cell.block(), cell.metadata, payload);
    }
}
