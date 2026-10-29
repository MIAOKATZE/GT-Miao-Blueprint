package com.miaokatze.gtmb.blueprint.editor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Local synchronous transactions. A failed command never publishes a revision or changes history/selection. */
public final class AuthorEditor {

    private volatile AuthorDocument current;
    private Set<Long> selection = Collections.emptySet();
    private final int historyLimit;
    private long clock, identityClock, historyBytes;
    private final Deque<History> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    public AuthorEditor(AuthorDocument initial, int historyLimit) {
        if (initial == null || historyLimit < 0 || historyLimit > 1024)
            throw new IllegalArgumentException("Invalid editor configuration");
        current = initial;
        clock = initial.revision;
        identityClock = initial.nextId;
        this.historyLimit = historyLimit;
    }

    public AuthorDocument current() {
        return current;
    }

    public Set<Long> selection() {
        return selection;
    }

    public void select(Collection<Long> ids) {
        if (ids == null) throw new IllegalArgumentException("Missing selection");
        Set<Long> selected = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || current.cell(id) == null) throw new IllegalArgumentException("Unknown selected cell");
            selected.add(id);
        }
        selection = immutable(selected);
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Add one local source position; membership creation belongs to explicit structure/attachment commands. */
    public Change addCell(JsonObject source) {
        requireEditable();
        Transaction tx = new Transaction();
        AuthorDocument.Cell cell = checkedCell(tx.allocateId(), checkedObject(source));
        if (!cell.memberships.isEmpty())
            throw new IllegalArgumentException("New position cannot claim structure membership");
        tx.put(cell);
        return commit(tx, Collections.singleton(cell.id));
    }

    public Change move(int dx, int dy, int dz) {
        return geometry(false, dx, dy, dz);
    }

    public Change copy(int dx, int dy, int dz) {
        return geometry(true, dx, dy, dz);
    }

    private Change geometry(boolean copy, int dx, int dy, int dz) {
        requireEditable();
        if (selection.isEmpty() || (!copy && dx == 0 && dy == 0 && dz == 0)) return unchanged();
        Set<String> groups = geometryGroups(selection);
        Transaction tx = new Transaction();
        if (copy && (long) current.count + selection.size() > current.limits.maxCells)
            throw new IllegalArgumentException("Cell count budget exceeded");
        Map<String, String> remap = new LinkedHashMap<>();
        if (copy) for (String group : groups) remap.put(group, uniqueGroup(group, tx.nextId));
        if (!copy) for (long id : selection) tx.removePosition(current.cell(id));
        Set<Long> selected = new LinkedHashSet<>(), splitIds = new LinkedHashSet<>();
        Map<String, Set<Long>> copiedMembers = new LinkedHashMap<>();
        for (String group : groups) copiedMembers.put(group, new LinkedHashSet<>());
        for (long id : selection) {
            AuthorDocument.Cell old = current.cell(id);
            JsonObject raw = old.sourceCopy();
            translate(raw, dx, dy, dz);
            long targetId = copy ? tx.allocateId() : id;
            Set<String> moving = new LinkedHashSet<>(old.memberships);
            moving.retainAll(groups);
            Set<String> staying = new LinkedHashSet<>(old.memberships);
            staying.removeAll(groups);
            if (!staying.isEmpty() || copy) setMemberships(raw, moving);
            if (copy) remapMemberships(raw, remap);
            else if (!staying.isEmpty()) {
                JsonObject retained = old.sourceCopy();
                setMemberships(retained, staying);
                long retainedId = tx.allocateId();
                tx.put(withOrigin(checkedCell(retainedId, retained), old));
                splitIds.add(retainedId);
                for (String group : staying) {
                    AuthorDocument.Group original = tx.groups.get(group);
                    Set<Long> members = new LinkedHashSet<>(original.members);
                    members.remove(id);
                    members.add(retainedId);
                    tx.putGroup(group, new AuthorDocument.Group(original.source, members));
                }
            }
            AuthorDocument.Cell next = withOrigin(checkedCell(targetId, raw), old);
            tx.put(next);
            selected.add(targetId);
            for (String group : moving) copiedMembers.get(group)
                .add(targetId);
        }
        for (String group : groups) {
            AuthorDocument.Group old = current.groups.get(group);
            JsonObject raw = cloneObject(old.source);
            translate(raw, dx, dy, dz);
            String target = copy ? remap.get(group) : group;
            if (copy) raw.addProperty("id", target);
            tx.putGroup(target, new AuthorDocument.Group(raw, copiedMembers.get(group)));
        }
        if (!splitIds.isEmpty()) tx.obligation("shared-membership-split", splitIds, 0, 0, 0);
        tx.obligation(copy ? "copy" : "translate", selected, dx, dy, dz);
        return commit(tx, selected);
    }

    public Change delete() {
        requireEditable();
        if (selection.isEmpty()) return unchanged();
        Set<String> touched = new LinkedHashSet<>();
        for (long id : selection) {
            AuthorDocument.Cell cell = requireCell(id);
            touched.addAll(cell.memberships);
        }
        for (String id : touched) {
            AuthorDocument.Group group = current.groups.get(id);
            String attachment = AuthorDocument.string(group.source, "textBoxNbt");
            if (attachment != null && !attachment.isEmpty() && !selection.containsAll(group.members))
                throw new IllegalArgumentException("Select the complete text/image attachment");
        }
        Transaction tx = new Transaction();
        for (long id : selection) tx.remove(current.cell(id));
        for (String id : touched) {
            AuthorDocument.Group group = current.groups.get(id);
            Set<Long> members = new LinkedHashSet<>(group.members);
            members.removeAll(selection);
            if (members.isEmpty()) tx.removeGroup(id);
            else tx.putGroup(id, new AuthorDocument.Group(group.source, members));
        }
        return commit(tx, Collections.emptySet());
    }

    public Change rename(String title) {
        JsonObject patch = new JsonObject();
        patch.addProperty("name", title);
        return patchHeader(patch);
    }

    /** Only author configuration, never schema, world receipts, identity or geometry. JSON null is preserved. */
    private JsonObject validatedHeaderPatch(JsonObject patch) {
        requireEditable();
        Set<String> allowed = strings(
            "name",
            "prompt",
            "retainNbt",
            "retainCovers",
            "retainedNbtKeys",
            "hintAreas",
            "processingNbt",
            "sourceMod",
            "nativeBlueprint",
            "modVersions",
            "aeIssues",
            "aePolicyVersion");
        JsonObject owned = checkedPatch(patch, allowed);
        for (Map.Entry<String, JsonElement> field : owned.entrySet()) {
            String key = field.getKey();
            JsonElement value = field.getValue();
            if (key.equals("name") || key.equals("prompt") || key.equals("processingNbt") || key.equals("sourceMod"))
                requireString(value, key);
            else if (key.equals("nativeBlueprint")) {
                if (!value.isJsonNull() && !value.isJsonObject())
                    throw new IllegalArgumentException("Invalid native version metadata");
            } else if (key.equals("modVersions")) {
                if (!value.isJsonObject()) throw new IllegalArgumentException("Invalid mod versions");
                for (Map.Entry<String, JsonElement> version : value.getAsJsonObject()
                    .entrySet()) requireString(version.getValue(), "modVersion");
            } else if (key.equals("aeIssues")) {
                if (!value.isJsonArray()) throw new IllegalArgumentException("Invalid AE issues");
            } else if (key.equals("aePolicyVersion")) {
                try {
                    if (AuthorDocumentIO.integer(owned, key, 0) < 0)
                        throw new IllegalArgumentException("Invalid AE policy version");
                } catch (IOException e) {
                    throw invalid(e);
                }
            } else if (key.equals("hintAreas")) {
                if (!value.isJsonArray()) throw new IllegalArgumentException("Invalid hint areas");
                for (JsonElement row : value.getAsJsonArray()) {
                    if (!row.isJsonObject()) throw new IllegalArgumentException("Invalid hint area");
                    JsonObject area = row.getAsJsonObject();
                    try {
                        for (String coordinate : new String[] { "x1", "y1", "z1", "x2", "y2", "z2" })
                            if (Math.abs((long) AuthorDocumentIO.integer(area, coordinate, 0))
                                > current.limits.maxCoordinate)
                                throw new IllegalArgumentException("Hint coordinate budget exceeded");
                        AuthorDocumentIO.text(area, "prompt", false);
                    } catch (IOException e) {
                        throw invalid(e);
                    }
                }
            } else if (key.equals("retainNbt") || key.equals("retainCovers")) requireBoolean(value, key);
            else if (!value.isJsonNull()) {
                if (!value.isJsonArray()) throw new IllegalArgumentException("retainedNbtKeys must be array or null");
                Set<String> keys = new LinkedHashSet<>();
                for (JsonElement item : value.getAsJsonArray()) {
                    requireString(item, key);
                    if (item.isJsonNull() || !keys.add(item.getAsString()))
                        throw new IllegalArgumentException("Invalid retained NBT keys");
                }
            }
        }
        return owned;
    }

    public Change patchHeader(JsonObject patch) {
        return patchDocument(patch, Collections.emptyMap());
    }

    public Change patchDocument(JsonObject headerPatch, Map<Long, JsonObject> cellPatches) {
        Map<Long, JsonObject> replacements = new LinkedHashMap<>();
        for (Map.Entry<Long, JsonObject> entry : cellPatches.entrySet()) {
            JsonObject owned = checkedPatch(
                entry.getValue(),
                strings(
                    "item",
                    "defaultItem",
                    "block",
                    "tileNbt",
                    "itemNbt",
                    "prompt",
                    "metadata",
                    "damage",
                    "defaultDamage",
                    "count",
                    "facing",
                    "excluded",
                    "allowedTargets"));
            replacements.put(entry.getKey(), merge(requireCell(entry.getKey()).source, owned));
        }
        return replaceCells(replacements, headerPatch);
    }

    /** Identity, memberships and coordinates cannot be changed via this descriptor patch. */
    public Change patchCell(long id, JsonObject patch) {
        requireEditable();
        AuthorDocument.Cell cell = requireCell(id);
        JsonObject owned = checkedPatch(
            patch,
            strings(
                "item",
                "defaultItem",
                "block",
                "tileNbt",
                "itemNbt",
                "prompt",
                "metadata",
                "damage",
                "defaultDamage",
                "count",
                "facing",
                "excluded",
                "allowedTargets"));
        JsonObject raw = merge(cell.source, owned);
        if (AuthorJsonValues.equal(raw, cell.source)) return unchanged();
        Transaction tx = new Transaction();
        tx.put(checkedCell(id, raw));
        return commit(tx, selection);
    }

    /** Atomic native-inspector result. Geometry and membership remain owned by geometry commands. */
    public Change replaceCells(Map<Long, JsonObject> replacements) {
        return replaceCells(replacements, new JsonObject());
    }

    /** One selected-record transaction plus its small native-derived header patch. */
    public Change replaceCells(Map<Long, JsonObject> replacements, JsonObject headerPatch) {
        requireEditable();
        if (replacements == null) throw new IllegalArgumentException("Missing replacement cells");
        Transaction tx = new Transaction();
        for (Map.Entry<Long, JsonObject> entry : replacements.entrySet()) {
            AuthorDocument.Cell old = requireCell(entry.getKey());
            AuthorDocument.Cell replacement = checkedCell(old.id, checkedObject(entry.getValue()));
            if (!old.position.equals(replacement.position) || !old.memberships.equals(replacement.memberships))
                throw new IllegalArgumentException("Native cell replacement cannot change geometry/membership");
            if (!AuthorJsonValues.equal(old.source, replacement.source)) tx.put(replacement);
        }
        JsonObject headerChanges = validatedHeaderPatch(headerPatch);
        if (!headerChanges.entrySet()
            .isEmpty()) tx.header = merge(current.header, headerChanges);
        if (tx.changed.isEmpty() && AuthorJsonValues.equal(tx.header, current.header)) return unchanged();
        return commit(tx, selection);
    }

    public Change patchCells(Map<Long, JsonObject> patches) {
        Map<Long, JsonObject> replacements = new LinkedHashMap<>();
        for (Map.Entry<Long, JsonObject> entry : patches.entrySet()) {
            JsonObject owned = checkedPatch(
                entry.getValue(),
                strings(
                    "item",
                    "defaultItem",
                    "block",
                    "tileNbt",
                    "itemNbt",
                    "prompt",
                    "metadata",
                    "damage",
                    "defaultDamage",
                    "count",
                    "facing",
                    "excluded",
                    "allowedTargets"));
            replacements.put(entry.getKey(), merge(requireCell(entry.getKey()).source, owned));
        }
        return replaceCells(replacements);
    }

    /**
     * Explicit processing boundary only (not the edit hot path). Processing must preserve cell positions and
     * memberships; stable IDs follow those positions. Unknown fields must already be preserved by its adapter.
     * Pending geometry obligations are retained: a processing result is not proof of native transform support.
     */
    public Change replaceSource(JsonObject source) {
        requireEditable();
        AuthorDocument candidate;
        try {
            candidate = AuthorDocumentIO.readExpanded(source, current.limits);
        } catch (IOException e) {
            throw invalid(e);
        }
        if (!candidate.editable() || candidate.wrapped != current.wrapped || candidate.count != current.count)
            throw new IllegalArgumentException("Processing changed document shape");
        if (!AuthorJsonValues.equal(candidate.envelope, current.envelope))
            throw new IllegalArgumentException("Processing changed source envelope");
        Transaction tx = new Transaction();
        for (AuthorDocument.Cell cell : candidate.cells()) {
            Long id = current.positions.get(cell.position);
            if (id == null) throw new IllegalArgumentException("Processing changed geometry");
            AuthorDocument.Cell old = requireCell(id);
            if (!old.memberships.equals(cell.memberships))
                throw new IllegalArgumentException("Processing changed memberships");
            if (!AuthorJsonValues.equal(old.source, cell.source))
                tx.put(new AuthorDocument.Cell(id, cell.source, cell.position, cell.memberships, old.payloadOrigin));
        }
        if (!candidate.groupOrder.equals(current.groupOrder))
            throw new IllegalArgumentException("Processing changed structures");
        boolean groupsChanged = false;
        for (String id : current.groupOrder) {
            AuthorDocument.Group next = candidate.groups.get(id), old = current.groups.get(id);
            if (!AuthorJsonValues.equal(next.source, old.source)) {
                tx.putGroup(id, new AuthorDocument.Group(next.source, old.members));
                tx.changed.addAll(old.members);
                groupsChanged = true;
            }
        }
        tx.header = candidate.header;
        if (tx.changed.isEmpty() && !groupsChanged && AuthorJsonValues.equal(tx.header, current.header))
            return unchanged();
        return commit(tx, selection);
    }

    /**
     * Atomic detached declaration regeneration. The native adapter merges explicit source edits before calling.
     * Position matches retain stable IDs; shared members outside this structure cannot be silently removed.
     */
    public Change replaceStructure(long expectedRevision, JsonObject groupSource,
        Collection<JsonObject> generatedCells) {
        return replaceStructure(expectedRevision, groupSource, generatedCells, new JsonObject());
    }

    public Change replaceStructure(long expectedRevision, JsonObject groupSource, Collection<JsonObject> generatedCells,
        JsonObject headerPatch) {
        requireEditable();
        if (current.revision != expectedRevision) throw new IllegalStateException("Source revision changed");
        JsonObject incomingGroup = checkedObject(groupSource);
        String id;
        try {
            id = AuthorDocumentIO.text(incomingGroup, "id", true);
            AuthorDocumentIO.position(incomingGroup, current.limits);
        } catch (IOException e) {
            throw invalid(e);
        }
        AuthorDocument.Group oldGroup = current.groups.get(id);
        if (oldGroup == null || generatedCells == null)
            throw new IllegalArgumentException("Missing structure regeneration source");
        String attachment = AuthorDocument.string(oldGroup.source, "textBoxNbt");
        if (attachment != null && !attachment.isEmpty())
            throw new IllegalArgumentException("Use the attachment transaction for text/images");
        Transaction tx = new Transaction();
        for (long oldId : oldGroup.members) {
            AuthorDocument.Cell old = current.cell(oldId);
            Set<String> remaining = new LinkedHashSet<>(old.memberships);
            remaining.remove(id);
            if (remaining.isEmpty()) tx.remove(old);
            else {
                JsonObject retained = old.sourceCopy();
                setMemberships(retained, remaining);
                tx.put(checkedCell(oldId, retained));
            }
        }
        Set<Long> members = new LinkedHashSet<>();
        Set<AuthorDocument.Position> seen = new LinkedHashSet<>();
        for (JsonObject input : generatedCells) {
            JsonObject raw = checkedObject(input);
            AuthorDocument.Cell proposed = checkedCell(Long.MAX_VALUE - 1, raw);
            if (!proposed.memberships.contains(id) || !seen.add(proposed.position))
                throw new IllegalArgumentException("Invalid regenerated structure membership/position");
            AuthorDocument.Cell existing = current.cellAt(proposed.x(), proposed.y(), proposed.z());
            long cellId;
            if (existing != null) {
                Set<String> required = new LinkedHashSet<>(existing.memberships);
                required.remove(id);
                Set<String> supplied = new LinkedHashSet<>(proposed.memberships);
                supplied.remove(id);
                if (!required.equals(supplied))
                    throw new IllegalArgumentException("Regeneration changed another structure membership");
                if (!oldGroup.members.contains(existing.id) && required.isEmpty()
                    && !existing.excluded()
                    && !"minecraft:air".equals(existing.block())
                    && (!java.util.Objects.equals(existing.block(), proposed.block())
                        || !java.util.Objects.equals(existing.item(), proposed.item())
                        || existing.metadata() != proposed.metadata()
                        || existing.damage() != proposed.damage()))
                    throw new IllegalArgumentException("Regeneration overlaps independent content");
                if (!required.isEmpty() && (!java.util.Objects.equals(existing.block(), proposed.block())
                    || !java.util.Objects.equals(existing.item(), proposed.item())
                    || existing.metadata() != proposed.metadata()
                    || existing.damage() != proposed.damage()))
                    throw new IllegalArgumentException("Conflicting shared structure declaration");
                cellId = existing.id;
                raw = merge(existing.source, raw);
            } else {
                if (proposed.memberships.size() != 1)
                    throw new IllegalArgumentException("New cell cannot invent shared membership");
                cellId = tx.allocateId();
            }
            AuthorDocument.Cell result = checkedCell(cellId, raw);
            tx.put(result);
            members.add(cellId);
        }
        tx.putGroup(id, new AuthorDocument.Group(merge(oldGroup.source, incomingGroup), members));
        JsonObject headerChanges = validatedHeaderPatch(headerPatch);
        if (!headerChanges.entrySet()
            .isEmpty()) tx.header = merge(current.header, headerChanges);
        Set<Long> selected = new LinkedHashSet<>();
        for (long selectedId : selection) if (!tx.removed.contains(selectedId)) selected.add(selectedId);
        return commit(tx, selected);
    }

    /** Atomic output of the local native text/image generator, never a physical Cut operation. */
    public Change replaceAttachment(long expectedRevision, JsonObject groupSource,
        Collection<JsonObject> generatedCells) {
        requireEditable();
        if (current.revision != expectedRevision) throw new IllegalStateException("Source revision changed");
        JsonObject group = checkedObject(groupSource);
        String id;
        try {
            id = AuthorDocumentIO.text(group, "id", true);
            String attachment = AuthorDocumentIO.text(group, "textBoxNbt", true);
            if (id.isEmpty() || attachment.isEmpty())
                throw new IllegalArgumentException("Missing attachment identity/content");
            AuthorDocumentIO.position(group, current.limits);
        } catch (IOException e) {
            throw invalid(e);
        }
        if (generatedCells == null || generatedCells.isEmpty())
            throw new IllegalArgumentException("Attachment has no geometry");
        Transaction tx = new Transaction();
        AuthorDocument.Group old = current.groups.get(id);
        if (old != null) {
            String payload = AuthorDocument.string(old.source, "textBoxNbt");
            if (payload == null || payload.isEmpty()) throw new IllegalArgumentException("Not an existing attachment");
            for (long member : old.members) {
                AuthorDocument.Cell cell = current.cell(member);
                if (cell.controller() || "templateController".equals(cell.stringField("role")))
                    throw new IllegalArgumentException("Attachment generator cannot replace controller declarations");
                if (cell.memberships.size() != 1)
                    throw new IllegalArgumentException("Shared attachment cells cannot be replaced");
                tx.remove(cell);
            }
            group = merge(old.source, group);
        }
        Set<Long> selected = new LinkedHashSet<>();
        for (JsonObject input : generatedCells) {
            AuthorDocument.Cell cell = checkedCell(tx.allocateId(), checkedObject(input));
            if (!cell.memberships.equals(Collections.singleton(id)))
                throw new IllegalArgumentException("Generated attachment membership mismatch");
            Long occupied = tx.positions.get(cell.position);
            if (occupied != null) {
                AuthorDocument.Cell existing = current.cell(occupied);
                if (existing == null || existing.controller()
                    || "templateController".equals(existing.stringField("role"))
                    || !existing.memberships.isEmpty()
                    || (!existing.excluded()
                        && (!"minecraft:air".equals(existing.block()) || (existing.item() != null && !existing.item()
                            .isEmpty()))))
                    throw new IllegalArgumentException("Attachment geometry overlaps occupied content");
                tx.remove(existing);
            }
            tx.put(cell);
            selected.add(cell.id);
        }
        tx.putGroup(id, new AuthorDocument.Group(group, selected));
        return commit(tx, selected);
    }

    /**
     * Explicit native-transform adapter result, in the exact original source cell order. The adapter owns
     * native metadata/NBT correctness; this transaction proves shape, stable identity, collision and bounds.
     * Existing unresolved obligations are intentionally not discharged by this operation.
     */
    public Change replaceTransformedSource(long expectedRevision, JsonObject source) {
        return applyTransformedSource(expectedRevision, source, false);
    }

    /**
     * Only the explicit portable/native resolver may call this after fulfilling every pending obligation.
     * CAS prevents applying a result to a newer draft. Source order supplies stable identity through rotation.
     * A successful resolution rebases exact payloads to current coordinates and retires the fulfilled log.
     */
    public Change replaceResolvedSource(long expectedRevision, JsonObject source) {
        return applyTransformedSource(expectedRevision, source, true);
    }

    private Change applyTransformedSource(long expectedRevision, JsonObject source, boolean resolved) {
        requireEditable();
        if (current.revision != expectedRevision) throw new IllegalStateException("Source revision changed");
        AuthorDocument candidate;
        try {
            candidate = AuthorDocumentIO.readExpanded(source, current.limits);
        } catch (IOException e) {
            throw invalid(e);
        }
        if (!candidate.editable() || candidate.wrapped != current.wrapped
            || candidate.count != current.count
            || !candidate.groupOrder.equals(current.groupOrder)
            || !AuthorJsonValues.equal(candidate.envelope, current.envelope))
            throw new IllegalArgumentException("Native transform changed source identity/shape");
        Transaction tx = new Transaction();
        java.util.Iterator<AuthorDocument.Cell> before = current.cells()
            .iterator();
        List<AuthorDocument.Cell> replacements = new ArrayList<>();
        for (AuthorDocument.Cell cell : candidate.cells()) {
            AuthorDocument.Cell old = before.next();
            if (!resolved && old.payloadPositionPending() && (old.tileNbt() != null || old.itemNbt() != null))
                throw new IllegalStateException("Resolve pending native payload translation before rotating");
            if (!old.memberships.equals(cell.memberships))
                throw new IllegalArgumentException("Native transform changed memberships");
            if (!AuthorJsonValues.equal(old.source, cell.source) || (resolved && old.payloadPositionPending())) {
                tx.removePosition(old);
                replacements.add(new AuthorDocument.Cell(old.id, cell.source, cell.position, cell.memberships));
            }
        }
        for (AuthorDocument.Cell cell : replacements) tx.put(cell);
        boolean groupsChanged = false;
        for (String id : current.groupOrder) {
            AuthorDocument.Group old = current.groups.get(id), next = candidate.groups.get(id);
            if (!AuthorJsonValues.equal(old.source, next.source)) {
                tx.putGroup(id, new AuthorDocument.Group(next.source, old.members));
                tx.changed.addAll(old.members);
                groupsChanged = true;
            }
        }
        tx.header = candidate.header;
        if (resolved) tx.obligations = null;
        if (tx.changed.isEmpty() && !groupsChanged
            && AuthorJsonValues.equal(tx.header, current.header)
            && !(resolved && current.hasPendingObligations())) return unchanged();
        return commit(tx, selection);
    }

    private JsonObject checkedObject(JsonObject object) {
        try {
            return AuthorDocumentIO.boundedCopy(object, current.limits);
        } catch (IOException e) {
            throw invalid(e);
        }
    }

    public Change replaceCellNbt(long id, String tile, String item) {
        JsonObject patch = new JsonObject();
        patch.addProperty("tileNbt", tile);
        patch.addProperty("itemNbt", item);
        return patchCell(id, patch);
    }

    public Change setExcluded(boolean excluded) {
        requireEditable();
        Transaction tx = new Transaction();
        for (long id : selection) {
            AuthorDocument.Cell old = current.cell(id);
            if (old.excluded() == excluded) continue;
            JsonObject raw = old.sourceCopy();
            raw.addProperty("excluded", excluded);
            tx.put(checkedCell(id, raw));
        }
        return tx.changed.isEmpty() ? unchanged() : commit(tx, selection);
    }

    /** Source structure configuration; changing attachment geometry requires a dedicated geometry transaction. */
    public Change patchStructure(String id, JsonObject patch) {
        requireEditable();
        AuthorDocument.Group group = current.groups.get(id);
        if (group == null) throw new IllegalArgumentException("Unknown structure");
        JsonObject owned = checkedPatch(
            patch,
            strings("name", "enabled", "main", "mainExplicit", "noHatches", "channels"));
        for (Map.Entry<String, JsonElement> field : owned.entrySet()) {
            String key = field.getKey();
            JsonElement value = field.getValue();
            if (key.equals("name")) requireString(value, key);
            else if (key.equals("enabled") || key.equals("mainExplicit") || key.equals("noHatches"))
                requireBoolean(value, key);
            else if (key.equals("main")) {
                try {
                    if (AuthorDocumentIO.integer(owned, key, 1) < 1)
                        throw new IllegalArgumentException("Invalid main count");
                } catch (IOException e) {
                    throw invalid(e);
                }
            } else {
                if (!value.isJsonObject()) throw new IllegalArgumentException("Invalid structure channels");
                try {
                    for (Map.Entry<String, JsonElement> channel : value.getAsJsonObject()
                        .entrySet())
                        if (AuthorDocumentIO.integer(value.getAsJsonObject(), channel.getKey(), 0) < 0)
                            throw new IllegalArgumentException("Invalid structure channel");
                } catch (IOException e) {
                    throw invalid(e);
                }
            }
        }
        JsonObject raw = merge(group.source, owned);
        if (AuthorJsonValues.equal(raw, group.source)) return unchanged();
        Transaction tx = new Transaction();
        tx.putGroup(id, new AuthorDocument.Group(raw, group.members));
        tx.changed.addAll(group.members);
        return commit(tx, selection);
    }

    /** Frozen source clipboard; no native object, server permission or physical Cut asset can enter it. */
    public Clipboard clipboard() {
        requireEditable();
        if (selection.isEmpty()) throw new IllegalStateException("Select cells first");
        Set<String> groups = new LinkedHashSet<>();
        for (long id : selection) groups.addAll(current.cell(id).memberships);
        requireWholeAttachments(selection, groups);
        List<AuthorDocument.Cell> cells = new ArrayList<>();
        List<AuthorDocument.Group> structures = new ArrayList<>();
        int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
        for (long id : selection) {
            AuthorDocument.Cell cell = current.cell(id);
            cells.add(cell);
            x = Math.min(x, cell.x());
            y = Math.min(y, cell.y());
            z = Math.min(z, cell.z());
        }
        for (String group : groups) structures.add(current.groups.get(group));
        return new Clipboard(cells, structures, x, y, z, current.sourceFrame);
    }

    /** Legacy author cut means excluding source cells, not deleting geometry or moving world assets. */
    public Change cut() {
        clipboard();
        return setExcluded(true);
    }

    public Change paste(Clipboard clipboard, int x, int y, int z) {
        requireEditable();
        if (clipboard == null) throw new IllegalArgumentException("Missing clipboard");
        if (!current.sourceFrame.equals(clipboard.sourceFrame)) {
            for (AuthorDocument.Cell cell : clipboard.cells) if (cell.tileNbt() != null && !cell.tileNbt()
                .isEmpty() || cell.itemNbt() != null
                    && !cell.itemNbt()
                        .isEmpty())
                throw new IllegalArgumentException("Cannot paste native payloads across source coordinate frames");
        }
        Transaction tx = new Transaction();
        int dx = difference(x, clipboard.x), dy = difference(y, clipboard.y), dz = difference(z, clipboard.z);
        Set<Long> replaced = new LinkedHashSet<>();
        for (AuthorDocument.Cell cell : clipboard.cells) {
            JsonObject raw = cell.sourceCopy();
            translate(raw, dx, dy, dz);
            AuthorDocument.Position position;
            try {
                position = AuthorDocumentIO.position(raw, current.limits);
            } catch (IOException e) {
                throw invalid(e);
            }
            Long occupied = current.positions.get(position);
            if (occupied != null) replaced.add(occupied);
        }
        // Overwriting only half of a text/image/structure is not a safe source transform.
        Set<String> removedGroups = new LinkedHashSet<>();
        for (long id : replaced) {
            AuthorDocument.Cell cell = current.cell(id);
            removedGroups.addAll(cell.memberships);
        }
        requireWholeAttachments(replaced, removedGroups);
        if ((long) current.count - replaced.size() + clipboard.cells.size() > current.limits.maxCells)
            throw new IllegalArgumentException("Cell count budget exceeded");
        for (long id : replaced) tx.remove(current.cell(id));
        for (String id : removedGroups) {
            AuthorDocument.Group group = current.groups.get(id);
            Set<Long> remaining = new LinkedHashSet<>(group.members);
            remaining.removeAll(replaced);
            if (remaining.isEmpty()) tx.removeGroup(id);
            else tx.putGroup(id, new AuthorDocument.Group(group.source, remaining));
        }
        Map<String, String> remap = new LinkedHashMap<>();
        Map<String, Set<Long>> members = new LinkedHashMap<>();
        for (AuthorDocument.Group group : clipboard.groups) {
            String id = AuthorDocument.string(group.source, "id");
            remap.put(id, uniqueGroup(id, tx.nextId));
            members.put(id, new LinkedHashSet<>());
        }
        Set<Long> selected = new LinkedHashSet<>();
        for (AuthorDocument.Cell old : clipboard.cells) {
            JsonObject raw = old.sourceCopy();
            translate(raw, dx, dy, dz);
            remapMemberships(raw, remap);
            long id = tx.allocateId();
            raw.addProperty("excluded", false);
            tx.put(withOrigin(checkedCell(id, raw), old));
            selected.add(id);
            for (String group : old.memberships) members.get(group)
                .add(id);
        }
        for (AuthorDocument.Group group : clipboard.groups) {
            JsonObject raw = cloneObject(group.source);
            String old = AuthorDocument.string(raw, "id"), id = remap.get(old);
            translate(raw, dx, dy, dz);
            raw.addProperty("id", id);
            tx.putGroup(id, new AuthorDocument.Group(raw, members.get(old)));
        }
        tx.obligation("paste", selected, dx, dy, dz);
        return commit(tx, selected);
    }

    public Change undo() {
        return replay(undo, redo, true);
    }

    public Change redo() {
        return replay(redo, undo, false);
    }

    private Change replay(Deque<History> from, Deque<History> to, boolean backward) {
        if (from.isEmpty()) return unchanged();
        requireClock();
        History entry = from.peekLast();
        AuthorDocument before = current, desired = backward ? entry.before : entry.after;
        current = desired.withRevision(++clock, identityClock);
        selection = backward ? entry.beforeSelection : entry.afterSelection;
        from.removeLast();
        to.addLast(entry);
        Set<Long> changed = new LinkedHashSet<>(entry.changed);
        changed.addAll(entry.removed);
        Set<Long> removed = new LinkedHashSet<>();
        for (long id : new ArrayList<>(changed)) if (current.cell(id) == null) {
            changed.remove(id);
            removed.add(id);
        }
        return new Change(before, current, changed, removed);
    }

    private Change commit(Transaction tx, Collection<Long> selected) {
        requireClock();
        AuthorDocument after = tx.build(clock + 1);
        Set<Long> nextSelection = immutable(selected);
        History history = new History(
            current,
            after,
            selection,
            nextSelection,
            tx.changed,
            tx.removed,
            tx.extraHistoryBytes);
        for (History abandoned : redo) historyBytes -= abandoned.bytes;
        redo.clear();
        if (historyLimit > 0) {
            undo.addLast(history);
            historyBytes += history.bytes;
            while (undo.size() > historyLimit || historyBytes > current.limits.maxBytes * 2L)
                historyBytes -= undo.removeFirst().bytes;
        }
        AuthorDocument before = current;
        current = after;
        clock++;
        identityClock = Math.max(identityClock, after.nextId);
        selection = nextSelection;
        return new Change(before, after, tx.changed, tx.removed);
    }

    private Change unchanged() {
        return new Change(current, current, Collections.emptySet(), Collections.emptySet());
    }

    private void requireClock() {
        // Persisted drafts exclude MAX_VALUE; never publish an edit/history revision that cannot reopen.
        if (clock >= Long.MAX_VALUE - 1) throw new IllegalStateException("Revision budget exhausted");
    }

    private void requireEditable() {
        if (!current.editable()) throw new IllegalStateException(current.diagnostic());
    }

    private AuthorDocument.Cell requireCell(long id) {
        AuthorDocument.Cell cell = current.cell(id);
        if (cell == null) throw new IllegalArgumentException("Unknown cell");
        return cell;
    }

    private Set<String> geometryGroups(Collection<Long> selected) {
        Set<Long> ids = new LinkedHashSet<>(selected);
        Set<String> touched = new LinkedHashSet<>(), complete = new LinkedHashSet<>();
        for (long id : ids) touched.addAll(requireCell(id).memberships);
        for (String group : touched) if (ids.containsAll(current.groups.get(group).members)) complete.add(group);
        for (long id : ids) {
            Set<String> membership = requireCell(id).memberships;
            if (!membership.isEmpty() && Collections.disjoint(membership, complete))
                throw new IllegalArgumentException("Select at least one complete member structure");
        }
        return complete;
    }

    private static void setMemberships(JsonObject raw, Collection<String> groups) {
        JsonArray ids = new JsonArray();
        for (String group : groups) ids.add(new JsonPrimitive(group));
        raw.add("structureIds", ids);
        raw.addProperty(
            "structureId",
            groups.isEmpty() ? ""
                : groups.iterator()
                    .next());
    }

    private void requireWholeAttachments(Collection<Long> selected, Collection<String> groups) {
        for (String id : groups) {
            AuthorDocument.Group group = current.groups.get(id);
            String attachment = AuthorDocument.string(group.source, "textBoxNbt");
            if (attachment != null && !attachment.isEmpty() && !selected.containsAll(group.members))
                throw new IllegalArgumentException("Select the complete text/image attachment");
        }
    }

    private String uniqueGroup(String base, long salt) {
        String id = base + "_copy_" + salt;
        while (current.groups.get(id) != null) id += "_";
        if (id.length() > current.limits.maxStringChars)
            throw new IllegalArgumentException("Structure identity budget exceeded");
        return id;
    }

    private static int difference(int a, int b) {
        long result = (long) a - b;
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Coordinate overflow");
        return (int) result;
    }

    private void translate(JsonObject raw, int dx, int dy, int dz) {
        try {
            AuthorDocument.Position p = AuthorDocumentIO.position(raw, current.limits);
            raw.addProperty("x", translated(p.x, dx));
            raw.addProperty("y", translated(p.y, dy));
            raw.addProperty("z", translated(p.z, dz));
        } catch (IOException e) {
            throw invalid(e);
        }
    }

    private int translated(int source, int delta) {
        long value = (long) source + delta;
        if (Math.abs(value) > current.limits.maxCoordinate)
            throw new IllegalArgumentException("Document coordinate budget exceeded");
        return (int) value;
    }

    private static void remapMemberships(JsonObject raw, Map<String, String> remap) {
        JsonElement id = raw.get("structureId");
        if (id != null && !id.isJsonNull() && remap.containsKey(id.getAsString()))
            raw.addProperty("structureId", remap.get(id.getAsString()));
        JsonElement ids = raw.get("structureIds");
        if (ids != null && !ids.isJsonNull()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : ids.getAsJsonArray()) result.add(new JsonPrimitive(remap.get(item.getAsString())));
            raw.add("structureIds", result);
        }
    }

    private AuthorDocument.Cell checkedCell(long id, JsonObject raw) {
        try {
            AuthorDocument.Cell result = AuthorDocumentIO.cell(id, raw, current.limits), previous = current.cell(id);
            return previous == null ? result : withOrigin(result, previous);
        } catch (IOException e) {
            throw invalid(e);
        }
    }

    private static AuthorDocument.Cell withOrigin(AuthorDocument.Cell value, AuthorDocument.Cell origin) {
        return value.withPayloadOrigin(origin.payloadOrigin);
    }

    private JsonObject checkedPatch(JsonObject patch, Set<String> allowed) {
        if (patch == null) throw new IllegalArgumentException("Missing patch");
        JsonObject owned = checkedObject(patch);
        for (Map.Entry<String, JsonElement> field : owned.entrySet()) {
            if (!allowed.contains(field.getKey()))
                throw new IllegalArgumentException("Not an editable field: " + field.getKey());
            // Bound before admitting an arbitrary selected-source edit. No decompression/native callbacks here.
            if (field.getValue()
                .toString()
                .length() > current.limits.maxStringChars * 2L)
                throw new IllegalArgumentException("Selected source field budget exceeded");
        }
        return owned;
    }

    private static long retainedFieldBytes(JsonObject before, JsonObject after) {
        long bytes = 0;
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> field : before.entrySet()) keys.add(field.getKey());
        for (Map.Entry<String, JsonElement> field : after.entrySet()) keys.add(field.getKey());
        for (String key : keys) {
            JsonElement old = before.get(key), next = after.get(key);
            if (old == next) continue;
            if (old != null) bytes += AuthorDocumentIO.measure(old);
            if (next != null) bytes += AuthorDocumentIO.measure(next);
        }
        return bytes;
    }

    private static long objectDelta(JsonObject before, JsonObject after, boolean bytes) {
        long delta = 0;
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> field : before.entrySet()) keys.add(field.getKey());
        for (Map.Entry<String, JsonElement> field : after.entrySet()) keys.add(field.getKey());
        for (String key : keys) {
            JsonElement old = before.get(key), next = after.get(key);
            if (old == next) continue;
            long keyBytes = bytes ? AuthorDocumentIO.measure(new JsonPrimitive(key)) + 2 : 0;
            if (next != null) delta += (bytes ? AuthorDocumentIO.measure(next) : AuthorDocumentIO.nodes(next))
                + (old == null ? keyBytes : 0);
            if (old != null) delta -= (bytes ? AuthorDocumentIO.measure(old) : AuthorDocumentIO.nodes(old))
                + (next == null ? keyBytes : 0);
        }
        return delta;
    }

    private static JsonObject merge(JsonObject source, JsonObject patch) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> field : source.entrySet()) result.add(field.getKey(), field.getValue());
        for (Map.Entry<String, JsonElement> field : patch.entrySet()) result.add(field.getKey(), field.getValue());
        return result;
    }

    private static void requireString(JsonElement value, String key) {
        if (!value.isJsonNull() && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
            .isString())) throw new IllegalArgumentException("Invalid string: " + key);
    }

    private static void requireBoolean(JsonElement value, String key) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
            .isBoolean()) throw new IllegalArgumentException("Invalid boolean: " + key);
    }

    private static JsonObject cloneObject(JsonObject value) {
        return AuthorDocumentIO.copy(value)
            .getAsJsonObject();
    }

    private static IllegalArgumentException invalid(IOException e) {
        return new IllegalArgumentException(e.getMessage(), e);
    }

    private static Set<String> strings(String... values) {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return result;
    }

    private static <T> Set<T> immutable(Collection<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private final class Transaction {

        AuthorTree<Long, AuthorDocument.Cell[]> pages = current.pages;
        AuthorTree<AuthorDocument.Position, Long> positions = current.positions;
        AuthorTree<String, AuthorDocument.Group> groups = current.groups;
        List<String> order = current.groupOrder;
        JsonObject header = current.header;
        AuthorDocument.Obligation obligations = current.obligations;
        final Map<Long, AuthorDocument.Cell[]> writablePages = new HashMap<>();
        final Set<Long> changed = new LinkedHashSet<>(), removed = new LinkedHashSet<>();
        int count = current.count, pendingPayloadPositions = current.pendingPayloadPositions;
        long retainedBytes = current.retainedBytes, retainedNodes = current.retainedNodes, extraHistoryBytes;
        long nextId = Math.max(current.nextId, identityClock);

        long allocateId() {
            if (nextId >= Long.MAX_VALUE - 1) throw new IllegalStateException("Cell identity budget exhausted");
            return nextId++;
        }

        AuthorDocument.Cell[] page(long id) {
            long key = id / AuthorDocument.PAGE_SIZE;
            AuthorDocument.Cell[] page = writablePages.get(key);
            if (page == null) {
                AuthorDocument.Cell[] old = pages.get(key);
                page = old == null ? new AuthorDocument.Cell[AuthorDocument.PAGE_SIZE] : old.clone();
                writablePages.put(key, page);
            }
            return page;
        }

        void removePosition(AuthorDocument.Cell cell) {
            positions = positions.remove(cell.position);
        }

        void put(AuthorDocument.Cell cell) {
            Long collision = positions.get(cell.position);
            if (collision != null && collision != cell.id)
                throw new IllegalArgumentException("Cell position is occupied");
            AuthorDocument.Cell[] page = page(cell.id);
            int slot = (int) (cell.id % AuthorDocument.PAGE_SIZE);
            if (cell.payloadPositionPending()) pendingPayloadPositions++;
            if (page[slot] != null && page[slot].payloadPositionPending()) pendingPayloadPositions--;
            if (page[slot] == null) {
                count++;
                retainedBytes += cell.encodedBytes + 1;
                retainedNodes += cell.nodes;
            } else {
                retainedBytes += cell.encodedBytes - page[slot].encodedBytes;
                retainedNodes += cell.nodes - page[slot].nodes;
            }
            if (count > current.limits.maxCells) throw new IllegalArgumentException("Cell count budget exceeded");
            if (retainedBytes > current.limits.maxBytes)
                throw new IllegalArgumentException("Document logical byte budget exceeded");
            if (page[slot] == null) { /* new record */ } else if (!page[slot].position.equals(cell.position)) {
                Long owner = positions.get(page[slot].position);
                if (owner != null && owner == cell.id) positions = positions.remove(page[slot].position);
            }
            page[slot] = cell;
            positions = positions.put(cell.position, cell.id);
            changed.add(cell.id);
            removed.remove(cell.id);
        }

        void remove(AuthorDocument.Cell cell) {
            if (cell.payloadPositionPending()) pendingPayloadPositions--;
            page(cell.id)[(int) (cell.id % AuthorDocument.PAGE_SIZE)] = null;
            positions = positions.remove(cell.position);
            count--;
            retainedBytes -= cell.encodedBytes + 1;
            retainedNodes -= cell.nodes;
            changed.remove(cell.id);
            removed.add(cell.id);
        }

        void putGroup(String id, AuthorDocument.Group group) {
            if (groups.get(id) == null) {
                order = new ArrayList<>(order);
                order.add(id);
            }
            AuthorDocument.Group old = groups.get(id);
            retainedBytes += AuthorDocumentIO.measure(group.source)
                - (old == null ? -1 : AuthorDocumentIO.measure(old.source));
            retainedNodes += AuthorDocumentIO.nodes(group.source)
                - (old == null ? 0 : AuthorDocumentIO.nodes(old.source));
            if (old == null) extraHistoryBytes += AuthorDocumentIO.measure(group.source);
            else if (old.source != group.source) extraHistoryBytes += retainedFieldBytes(old.source, group.source);
            groups = groups.put(id, group);
        }

        void removeGroup(String id) {
            extraHistoryBytes += AuthorDocumentIO.measure(groups.get(id).source);
            retainedNodes -= AuthorDocumentIO.nodes(groups.get(id).source);
            retainedBytes -= AuthorDocumentIO.measure(groups.get(id).source) + 1;
            groups = groups.remove(id);
            order = new ArrayList<>(order);
            order.remove(id);
            JsonElement palette = header.get("palette");
            if (palette != null && palette.isJsonArray()) {
                JsonArray cleaned = new JsonArray();
                boolean changedPalette = false;
                for (JsonElement value : palette.getAsJsonArray()) {
                    if (!value.isJsonObject()) {
                        cleaned.add(value);
                        continue;
                    }
                    JsonObject descriptor = value.getAsJsonObject();
                    Set<String> memberships = new LinkedHashSet<>();
                    JsonElement list = descriptor.get("structureIds");
                    if (list != null && list.isJsonArray()
                        && list.getAsJsonArray()
                            .size() > 0) {
                        for (JsonElement member : list.getAsJsonArray())
                            if (member.isJsonPrimitive() && member.getAsJsonPrimitive()
                                .isString()) memberships.add(member.getAsString());
                    } else {
                        String member = AuthorDocument.string(descriptor, "structureId");
                        if (member != null && !member.isEmpty()) memberships.add(member);
                    }
                    if (memberships.remove(id)) {
                        JsonObject copy = cloneObject(descriptor);
                        setMemberships(copy, memberships);
                        cleaned.add(copy);
                        changedPalette = true;
                    } else cleaned.add(value);
                }
                if (changedPalette) {
                    JsonObject patch = new JsonObject();
                    patch.add("palette", cleaned);
                    header = merge(header, patch);
                }
            }
        }

        void obligation(String kind, Collection<Long> ids, int dx, int dy, int dz) {
            if ((obligations == null ? 0 : obligations.count) >= current.limits.maxObligations)
                throw new IllegalStateException(
                    "Pending transform budget exceeded; resolve obligations before editing further");
            JsonObject row = new JsonObject();
            row.addProperty("kind", kind);
            row.addProperty("sourceRevision", current.revision);
            JsonArray cells = new JsonArray();
            for (long id : ids) cells.add(new JsonPrimitive(id));
            row.add("cellIds", cells);
            row.addProperty("dx", dx);
            row.addProperty("dy", dy);
            row.addProperty("dz", dz);
            row.addProperty(
                "meaning",
                "geometry already applied; native payload and unknown extension semantics unresolved");
            obligations = new AuthorDocument.Obligation(row, obligations);
        }

        AuthorDocument build(long revision) {
            if (count > current.limits.maxCells) throw new IllegalArgumentException("Cell count budget exceeded");
            for (Map.Entry<Long, AuthorDocument.Cell[]> page : writablePages.entrySet()) {
                boolean empty = true;
                for (AuthorDocument.Cell cell : page.getValue()) if (cell != null) {
                    empty = false;
                    break;
                }
                pages = empty ? pages.remove(page.getKey()) : pages.put(page.getKey(), page.getValue());
            }
            if (header != current.header) {
                retainedBytes += objectDelta(current.header, header, true);
                retainedNodes += objectDelta(current.header, header, false);
            }
            try {
                AuthorDocumentIO.requireDraftBudget(current.limits, retainedBytes, retainedNodes, count, obligations);
            } catch (IOException e) {
                throw invalid(e);
            }
            return new AuthorDocument(
                current.sourceFrame,
                current.id,
                revision,
                nextId,
                count,
                current.limits,
                retainedBytes,
                retainedNodes,
                pendingPayloadPositions,
                current.envelope,
                header,
                current.wrapped,
                current.hadCells,
                current.hadStructures,
                pages,
                positions,
                groups,
                order,
                obligations,
                current.readOnlyReason);
        }
    }

    public static final class Clipboard {

        private final List<AuthorDocument.Cell> cells;
        private final List<AuthorDocument.Group> groups;
        private final int x, y, z;
        private final AuthorDocument.SourceFrame sourceFrame;

        private Clipboard(List<AuthorDocument.Cell> cells, List<AuthorDocument.Group> groups, int x, int y, int z,
            AuthorDocument.SourceFrame sourceFrame) {
            this.sourceFrame = sourceFrame;
            this.cells = Collections.unmodifiableList(cells);
            this.groups = Collections.unmodifiableList(groups);
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int cellCount() {
            return cells.size();
        }
    }

    public static final class Change {

        private final AuthorDocument before, after;
        private final Set<Long> changed, removed;

        Change(AuthorDocument before, AuthorDocument after, Collection<Long> changed, Collection<Long> removed) {
            this.before = before;
            this.after = after;
            this.changed = immutable(changed);
            this.removed = immutable(removed);
        }

        public AuthorDocument before() {
            return before;
        }

        public AuthorDocument after() {
            return after;
        }

        public Set<Long> changedIds() {
            return changed;
        }

        public Set<Long> removedIds() {
            return removed;
        }
    }

    private static final class History {

        final AuthorDocument before, after;
        final Set<Long> beforeSelection, afterSelection, changed, removed;
        final long bytes;

        History(AuthorDocument before, AuthorDocument after, Set<Long> beforeSelection, Set<Long> afterSelection,
            Set<Long> changed, Set<Long> removed, long extraBytes) {
            this.before = before;
            this.after = after;
            this.beforeSelection = beforeSelection;
            this.afterSelection = afterSelection;
            this.changed = immutable(changed);
            this.removed = immutable(removed);
            long retained = 1024 + extraBytes;
            Set<Long> affected = new LinkedHashSet<>(changed);
            affected.addAll(removed);
            for (long id : affected) {
                AuthorDocument.Cell old = before.cell(id), next = after.cell(id);
                if (old != null) retained += old.encodedBytes + 128;
                if (next != null) retained += next.encodedBytes + 128;
            }
            if (before.header != after.header) retained += retainedFieldBytes(before.header, after.header) + 1024;
            bytes = retained;
        }
    }
}
