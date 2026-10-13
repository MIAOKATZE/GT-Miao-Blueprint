package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** One canonical AE boundary for capture, disk migration, review and installation. */
final class HologramAePolicy {

    static final int VERSION = 1;
    static final int MAX_ISSUES = 8192;

    private HologramAePolicy() {}

    static boolean handles(Block block, String encoded) throws IOException {
        return HologramAeBlueprint.supports(block, HologramBlueprintNbt.decode(encoded));
    }

    /** Gson DTO: schema fields only; never contains original device/cell NBT. Coordinates are local on disk. */
    static final class Issue {

        String code, severity, path, message;
        int x, y, z;
        long count = 1;

        Issue() {}

        Issue(String code, String severity, String path, String message, int x, int y, int z) throws IOException {
            this.code = checked(code, 96);
            this.severity = checked(severity, 16);
            this.path = checked(path, 512);
            this.message = checked(message, 256);
            this.x = x;
            this.y = y;
            this.z = z;
            validate();
        }

        void validate() throws IOException {
            checked(code, 96);
            checked(severity, 16);
            checked(path, 512);
            checked(message, 256);
            if (!code.matches("[A-Za-z0-9_.-]{1,96}") || count < 1
                || !("warning".equals(severity) || "blocker".equals(severity) || "info".equals(severity)))
                throw new IOException("AE issue DTO invalid");
        }

        NBTTagCompound dto(int originX, int originY, int originZ) throws IOException {
            validate();
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("code", code);
            tag.setString("severity", severity);
            tag.setString("path", path);
            tag.setString("message", message);
            tag.setInteger("x", Math.addExact(x, originX));
            tag.setInteger("y", Math.addExact(y, originY));
            tag.setInteger("z", Math.addExact(z, originZ));
            tag.setLong("count", count);
            tag.setString("structureId", "");
            tag.setString("slotId", "");
            tag.setString("resolution", "none");
            return tag;
        }
    }

    static final class Draft {

        final HologramPresets.Preset preset;
        final List<Issue> issues;
        final boolean blocking;

        Draft(HologramPresets.Preset preset, List<Issue> issues) {
            this.preset = preset;
            this.issues = issues;
            blocking = issues.stream()
                .anyMatch(issue -> "blocker".equals(issue.severity));
        }
    }

    /** Does not mutate the source draft, library cache, source stacks or any world. */
    static Draft sanitize(HologramPresets.Preset source) throws IOException {
        HologramPresets.Preset result = HologramPresets.copy(source);
        Map<String, Issue> issues = new LinkedHashMap<>();
        Set<String> positions = new HashSet<>();
        for (HologramPresets.Cell cell : result.cells) positions.add(cell.x + "," + cell.y + "," + cell.z);
        // An omitted invalid field cannot be rediscovered. Only a validated explicit edit may retire its blocker.
        if (result.aeIssues != null) for (Issue issue : result.aeIssues) {
            // An explicitly removed whole cell has no artifact left to restore at that coordinate.
            boolean present = positions.contains(issue.x + "," + issue.y + "," + issue.z);
            if (!"blocker".equals(issue.severity) || present) add(issues, issue);
        }
        boolean ae = false;
        for (HologramPresets.Cell cell : result.cells) {
            Block block = cell.block();
            NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
            if (!HologramAeBlueprint.supports(block, payload)) continue;
            ae = true;
            HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, cell.metadata, payload);
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(block, analysis.payload());
            // AE shell tags are not a second copy of the host inventory/configuration. Canonical tile is authoritative.
            if (cell.itemNbt != null && !cell.itemNbt.isEmpty()) {
                add(
                    issues,
                    new Issue(
                        "ae.shell_item_payload_removed",
                        "warning",
                        "itemNbt",
                        "AE shell item payload is omitted; canonical machine configuration is retained in tile data.",
                        cell.x,
                        cell.y,
                        cell.z));
                cell.itemNbt = null;
            }
            for (HologramAeBlueprint.Issue issue : analysis.issues) add(
                issues,
                new Issue(
                    issue.code,
                    severity(issue.severity),
                    issue.path,
                    fallback(issue.message),
                    cell.x,
                    cell.y,
                    cell.z));
            if (analysis.blocking && analysis.issues.stream()
                .noneMatch(issue -> "blocker".equals(severity(issue.severity))))
                add(
                    issues,
                    new Issue(
                        "ae.unresolved_schema",
                        "blocker",
                        "tileNbt",
                        "AE schema validation is incomplete; resolve this machine before placement.",
                        cell.x,
                        cell.y,
                        cell.z));
        }
        if (ae) {
            add(
                issues,
                new Issue(
                    "ae.contents_not_saved",
                    "warning",
                    "policy",
                    "Storage cell contents are not saved; the source world and source cells are unchanged.",
                    0,
                    0,
                    0));
            add(
                issues,
                new Issue(
                    "ae.identities_reset",
                    "warning",
                    "policy",
                    "AE network, ownership and security identities are rebuilt for the player who places this blueprint.",
                    0,
                    0,
                    0));
            add(
                issues,
                new Issue(
                    "ae.physical_materials",
                    "info",
                    "policy",
                    "Encoded patterns, upgrade cards and empty cell shells require real materials at placement.",
                    0,
                    0,
                    0));
        }
        result.aePolicyVersion = VERSION;
        result.aeIssues = new ArrayList<>(issues.values());
        return new Draft(result, result.aeIssues);
    }

    /** Complete draft costs, independent of current construction scope/world and never a reservation. */
    static HologramBlueprintResources.Plan materials(HologramPresets.Preset preset) throws IOException {
        HologramBlueprintResources.Plan total = new HologramBlueprintResources.Plan();
        for (HologramPresets.Cell cell : preset.cells) {
            if (cell.excluded) continue;
            try {
                Block block = cell.block();
                NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
                ItemStack shell = cell.stack();
                if (HologramAeBlueprint.supports(block, payload)) {
                    if (HologramAeBlueprint.analyze(block, cell.metadata, payload).blocking) continue;
                    total.merge(HologramAeBlueprint.resourcePlan(block, cell.metadata, payload, null));
                } else {
                    payload = HologramBlueprintNbt.nativePayload(payload);
                    HologramBlueprintResources.Plan extra = HologramBlueprintResources.plan(payload)
                        .merge(
                            HologramBlueprintResources
                                .plan(HologramBlueprintNbt.nativePayload(HologramBlueprintNbt.decode(cell.itemNbt))));
                    if (HologramNativePlacement.supported(block)) {
                        List<ItemStack> nativeCosts = HologramNativePlacement.materials(block, cell.metadata, payload);
                        if (HologramNativePlacement.adapter(block)
                            .includesInventoryDrops(block)) extra.removeItemsIncludedByNativeDrops(nativeCosts);
                        total.addNativeItems(nativeCosts);
                    } else {
                        if (shell != null) total.addNativeItems(java.util.Collections.singletonList(shell));
                        total.addNativeItems(HologramBlueprintCovers.materials(cell.tileNbt));
                    }
                    total.merge(extra);
                }
            } catch (IOException | RuntimeException invalid) {
                preset.aeIssues.add(
                    new Issue(
                        "review.materials_unknown",
                        "blocker",
                        "materials",
                        fallback(invalid.getMessage()),
                        cell.x,
                        cell.y,
                        cell.z));
                if (preset.aeIssues.size() > MAX_ISSUES)
                    throw new IOException("Complete material report exceeds budget");
            }
        }
        return total;
    }

    /** Retire only the problems actually resolved by one server-validated native/tombstone mutation. */
    static List<Issue> resolveAfterMutation(List<Issue> existing, int x, int y, int z, String slotId,
        HologramAeBlueprint.Analysis before, HologramAeBlueprint.Analysis after) throws IOException {
        validateIssues(existing);
        List<Issue> retained = new ArrayList<>();
        for (Issue issue : existing) {
            boolean currentCell = issue.x == x && issue.y == y && issue.z == z;
            boolean wasPresent = before.issues.stream()
                .anyMatch(old -> old.code.equals(issue.code) && old.path.equals(issue.path));
            boolean stillPresent = after.issues.stream()
                .anyMatch(now -> now.code.equals(issue.code) && now.path.equals(issue.path));
            boolean selected = issue.path.equals(slotId) || issue.path.startsWith(slotId + "/");
            // Clearing a cable part removes its extra subtree by the helper's native def-side rule.
            int definition = slotId.lastIndexOf("def:");
            if (definition >= 0) {
                String extra = slotId.substring(0, definition) + "extra:" + slotId.substring(definition + 4);
                selected |= issue.path.equals(extra) || issue.path.startsWith(extra + "/");
            }
            boolean resolved = currentCell && "blocker".equals(issue.severity)
                && !"unsupported_host".equals(issue.code)
                && !stillPresent
                && (wasPresent || selected);
            if (!resolved) retained.add(issue);
        }
        for (HologramAeBlueprint.Issue current : after.issues) {
            Issue issue = new Issue(
                current.code,
                severity(current.severity),
                current.path,
                fallback(current.message),
                x,
                y,
                z);
            retained.removeIf(
                previous -> previous.x == x && previous.y == y
                    && previous.z == z
                    && previous.code.equals(issue.code)
                    && previous.path.equals(issue.path));
            retained.add(issue);
        }
        validateIssues(retained);
        return retained;
    }

    static List<Issue> copyIssues(List<Issue> issues) {
        List<Issue> result = new ArrayList<>();
        if (issues == null) return result;
        for (Issue issue : issues) {
            Issue copy = new Issue();
            copy.code = issue.code;
            copy.severity = issue.severity;
            copy.path = issue.path;
            copy.message = issue.message;
            copy.x = issue.x;
            copy.y = issue.y;
            copy.z = issue.z;
            copy.count = issue.count;
            result.add(copy);
        }
        return result;
    }

    /** Display navigation is derived from server-owned descriptors, never client-supplied NBT paths. */
    static void projectResolution(NBTTagCompound issue, HologramAeBlueprint.Analysis analysis, String structureId) {
        issue.setString("structureId", structureId);
        issue.setString("slotId", "");
        issue.setString("resolution", "none");
        if (!"blocker".equals(issue.getString("severity"))) return;
        if ("unsupported_host".equals(issue.getString("code"))) {
            issue.setString("resolution", "replaceCell");
            return;
        }
        String path = issue.getString("path");
        for (HologramAeBlueprint.Slot slot : analysis.slots) {
            if (!slot.issueCode.isEmpty()) {
                if (slot.issueCode.equals(issue.getString("code")) && slot.issuePath.equals(path)) {
                    issue.setString("slotId", slot.id);
                    issue.setString("resolution", "discardOmitted");
                    return;
                }
            } else if (path.equals(slot.id) || path.startsWith(slot.id + "/")) {
                issue.setString("slotId", slot.id);
                issue.setString("resolution", "clearSlot");
                return;
            }
        }
        if (issue.getString("code")
            .equals("unsupported_part")
            || issue.getString("code")
                .equals("unsupported_part_item")) {
            for (HologramAeBlueprint.Slot slot : analysis.slots) {
                int definition = slot.id.lastIndexOf("def:");
                if (definition < 0) continue;
                String extra = slot.id.substring(0, definition) + "extra:" + slot.id.substring(definition + 4);
                if (path.equals(extra) || path.startsWith(extra + "/")) {
                    issue.setString("slotId", slot.id);
                    issue.setString("resolution", "clearSlot");
                    return;
                }
            }
        }
        issue.setString("resolution", "replaceCell");
    }

    static String severity(String severity) {
        if ("blocker".equalsIgnoreCase(severity) || "error".equalsIgnoreCase(severity)) return "blocker";
        if ("info".equalsIgnoreCase(severity)) return "info";
        return "warning";
    }

    /** Defensive fallback is never used as a localization key or rendered as markup. */
    static String fallback(String text) {
        if (text == null) return "";
        String clean = text.replaceAll("[\\p{Cntrl}\\u00a7]", " ");
        return clean.length() > 256 ? clean.substring(0, 256) : clean;
    }

    static NBTTagList issues(List<Issue> issues, int x, int y, int z) throws IOException {
        validateIssues(issues);
        NBTTagList list = new NBTTagList();
        for (Issue issue : issues) list.appendTag(issue.dto(x, y, z));
        return list;
    }

    static void validateIssues(List<Issue> issues) throws IOException {
        if (issues == null || issues.size() > MAX_ISSUES) throw new IOException("AE issue report exceeds budget");
        for (Issue issue : issues) {
            if (issue == null) throw new IOException("AE issue is null");
            issue.validate();
        }
    }

    private static String checked(String value, int maximum) throws IOException {
        if (value == null || value.length() > maximum
            || value.chars()
                .anyMatch(character -> character < 32 || character == 167))
            throw new IOException("AE issue text invalid");
        return value;
    }

    private static void add(Map<String, Issue> issues, Issue issue) throws IOException {
        if (issue == null) throw new IOException("AE issue is null");
        issue.validate();
        String key = issue.x + "," + issue.y + "," + issue.z + ":" + issue.path + ":" + issue.code;
        // Schema analysis is idempotent; original loss reports survive migration without counting them twice.
        issues.put(key, issue);
        if (issues.size() > MAX_ISSUES) throw new IOException("AE complete report exceeds budget; split blueprint");
    }
}
