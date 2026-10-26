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
            Set<String> excluded = new HashSet<>();
            for (HologramPresets.Cell cell : preset.cells)
                if (cell.excluded) excluded.add(cell.x + "," + cell.y + "," + cell.z);
            blocking = issues.stream()
                .anyMatch(
                    issue -> "blocker".equals(issue.severity)
                        && !excluded.contains(issue.x + "," + issue.y + "," + issue.z));
        }
    }

    /** Does not mutate the source draft, library cache, source stacks or any world. */
    static Draft sanitize(HologramPresets.Preset source) throws IOException {
        return sanitizeOwned(HologramPresets.copy(source), null, PreparationProgress.NONE);
    }

    interface PreparationProgress {

        PreparationProgress NONE = (phase, completed, total) -> {};

        void update(String phase, long completed, long total) throws IOException;
    }

    /** Caller transfers exclusive ownership and runs on the native provider owner thread. */
    static Draft sanitizeOwned(HologramPresets.Preset result, Map<String, String> networks,
        PreparationProgress progress) throws IOException {
        return sanitizeOwned(result, networks, null, progress);
    }

    /** Synchronous callers must already own the native provider thread. */
    static Draft sanitizeOwned(HologramPresets.Preset result, Map<String, String> networks,
        Map<String, HologramPresets.Target> nativeShells, PreparationProgress progress) throws IOException {
        Sanitation work = new Sanitation(result, networks, nativeShells, progress);
        while (!work.identityDone()) work.identity();
        work.prepareData();
        while (!work.aeDone()) work.ae();
        return work.finishData();
    }

    /** Native passes are owner-thread continuations; only prepareData/finishData may run on a worker. */
    static final class Sanitation {

        private final Thread owner = Thread.currentThread();
        private final Map<String, String> networks;
        private final Map<String, HologramPresets.Target> nativeShells;
        private final PreparationProgress progress;
        private final HologramAnalysisFacts facts = new HologramAnalysisFacts();
        private final Map<String, Issue> issues = new LinkedHashMap<>();
        private HologramPresets.Preset result;
        private int identityCursor, aeCursor;
        private boolean ae;

        Sanitation(HologramPresets.Preset result, Map<String, String> networks,
            Map<String, HologramPresets.Target> nativeShells, PreparationProgress progress) {
            this.result = result;
            this.networks = networks;
            this.nativeShells = nativeShells;
            this.progress = progress;
            result.deidentificationLevel = HologramDeidentification.level(result);
        }

        boolean identityDone() {
            return identityCursor == result.cells.size();
        }

        boolean aeDone() {
            return aeCursor == result.cells.size();
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Native sanitation requires owner thread");
        }

        void identity() throws IOException {
            requireOwner();
            HologramPresets.Cell cell = result.cells.get(identityCursor++);
            Block block = cell.block();
            NBTTagCompound payload = facts.copyForEdit(cell.tileNbt);
            if (HologramCarpentersCompatibility.stripOwner(block, payload))
                cell.tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
            cell.itemNbt = HologramCarpentersCompatibility.normalize(block, cell.itemNbt);
            if (HologramAeBlueprint.supports(block, payload) || HologramNetworkBindings.node(block)
                || HologramNetworkSchemas.removeExternalReferences(
                    block,
                    cell.damage,
                    payload == null ? null : (NBTTagCompound) payload.copy())
                || payload != null && (payload.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR)
                    || payload.hasKey(HologramNetworkBindings.GROUP)))
                result.deidentificationLevel = Math.max(1, result.deidentificationLevel);
            progress.update("identity", identityCursor, result.cells.size());
        }

        void prepareData() throws IOException {
            progress.update("identity", identityCursor, result.cells.size());
            result = HologramDeidentification.applyOwned(result, result.deidentificationLevel, networks, progress);
            List<Issue> networkChanges = result.deidentificationLevel > 0
                ? HologramNetworkBindings.sanitize(result, networks, progress)
                : java.util.Collections.emptyList();
            for (Issue issue : networkChanges) add(issues, issue);
            Set<String> positions = new HashSet<>();
            for (HologramPresets.Cell cell : result.cells) positions.add(cell.x + "," + cell.y + "," + cell.z);
            // An omitted invalid field cannot be rediscovered. Only a validated explicit edit may retire its blocker.
            if (result.aeIssues != null) for (Issue issue : result.aeIssues) {
                // An explicitly removed whole cell has no artifact left to restore at that coordinate.
                boolean present = positions.contains(issue.x + "," + issue.y + "," + issue.z);
                if (!"blocker".equals(issue.severity) || present) {
                    if (HologramAeBlueprint.automaticallyOmitted(issue.code)) add(
                        issues,
                        new Issue(
                            issue.code,
                            "warning",
                            issue.path,
                            "Unavailable component was omitted; replace its marker to restore it.",
                            issue.x,
                            issue.y,
                            issue.z));
                    else add(issues, issue);
                }
            }
        }

        void ae() throws IOException {
            requireOwner();
            HologramPresets.Cell cell = result.cells.get(aeCursor++);
            sanitizeAe(cell);
            progress.update("ae", aeCursor, result.cells.size());
        }

        private void sanitizeAe(HologramPresets.Cell cell) throws IOException {
            Block block = cell.block();
            NBTTagCompound payload = facts.read(cell.tileNbt);
            if (payload != null && payload.hasKey(HologramNetworkBindings.GROUP)) add(
                issues,
                new Issue(
                    "network.quantum_group",
                    "info",
                    "specialHint",
                    "Quantum network group " + HologramNetworkBindings.group(payload)
                        + " requires a real coordinate-bearing node or terminal before placement.",
                    cell.x,
                    cell.y,
                    cell.z));
            if (!HologramAeBlueprint.supports(block, payload)) return;
            ae = true;
            HologramAeBlueprint.Analysis analysis = facts.ae(block, cell.metadata, cell.tileNbt, payload);
            boolean omittedHost = !analysis.supported && analysis.issues.stream()
                .anyMatch(issue -> "unsupported_host".equals(issue.code));
            if (omittedHost) cell.excluded = true;
            boolean omittedPart = analysis.issues.stream()
                .anyMatch(issue -> "unsupported_part".equals(issue.code) || "unsupported_part_item".equals(issue.code));
            if (omittedPart && !analysis.blocking) {
                ItemStack shell = HologramAeBlueprint.sampleIdentity(block, cell.metadata, analysis.payload());
                if (shell == null && block.getClass()
                    .getName()
                    .equals("codechicken.multipart.BlockMultipart")) {
                    NBTTagCompound remainder = HologramAeBlueprint.withoutAeMultipart(analysis.payload());
                    if (remainder.getTagList("parts", 10)
                        .tagCount() > 0) {
                        if (nativeShells == null) {
                            List<ItemStack> components = HologramNativePlacement
                                .materials(block, cell.metadata, remainder);
                            if (!components.isEmpty()) shell = components.get(0);
                        } else {
                            String position = cell.x + "," + cell.y + "," + cell.z;
                            if (!nativeShells.containsKey(position))
                                throw new IOException("Native shell fallback was not frozen");
                            HologramPresets.Target frozen = nativeShells.get(position);
                            shell = frozen == null ? null : frozen.stack();
                        }
                    }
                }
                if (shell == null) {
                    cell.excluded = true;
                    add(
                        issues,
                        new Issue(
                            "ae.empty_host_downgraded",
                            "warning",
                            "tileNbt",
                            "All unreviewed parts were omitted; this empty native host is excluded from construction.",
                            cell.x,
                            cell.y,
                            cell.z));
                } else {
                    cell.item = String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(shell.getItem()));
                    cell.damage = shell.getItemDamage();
                }
            }
            NBTTagCompound projected = HologramBlueprintNbt
                .canonicalActorPayload(block, analysis.payload(), null, "", false);
            if (!java.util.Objects.equals(payload, projected))
                cell.tileNbt = HologramBlueprintNbt.encodeCanonical(projected);
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
                    omittedHost ? "warning" : severity(issue.severity),
                    issue.path,
                    fallback(issue.message),
                    cell.x,
                    cell.y,
                    cell.z));
            if (!omittedHost && analysis.blocking
                && analysis.issues.stream()
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

        Draft finishData() throws IOException {
            progress.update("ae", aeCursor, result.cells.size());
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
            progress.update("report", 0, 1);
            Draft draft = new Draft(result, result.aeIssues);
            progress.update("report", 1, 1);
            return draft;
        }
    }

    /** Server-thread preflight only for the rare mixed-multipart downgrade that needs native material callbacks. */
    static void captureNativeShell(HologramPresets.Cell cell, Map<String, HologramPresets.Target> shells)
        throws IOException {
        Block block = cell.block();
        if (block == null || !block.getClass()
            .getName()
            .equals("codechicken.multipart.BlockMultipart")) return;
        NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
        if (!HologramAeBlueprint.supports(block, payload)) return;
        HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(block, cell.metadata, payload);
        if (analysis.blocking || analysis.issues.stream()
            .noneMatch(issue -> "unsupported_part".equals(issue.code) || "unsupported_part_item".equals(issue.code))
            || HologramAeBlueprint.sampleIdentity(block, cell.metadata, analysis.payload()) != null) return;
        NBTTagCompound remainder = HologramAeBlueprint.withoutAeMultipart(analysis.payload());
        if (remainder.getTagList("parts", 10)
            .tagCount() == 0) return;
        List<ItemStack> materials = HologramNativePlacement.materials(block, cell.metadata, remainder);
        shells.put(
            cell.x + "," + cell.y + "," + cell.z,
            materials.isEmpty() ? null : new HologramPresets.Target(materials.get(0)));
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
                    boolean nativePlacement = HologramNativePlacement.supported(block);
                    List<ItemStack> nativeCosts = nativePlacement
                        ? HologramNativePlacement.materials(block, cell.metadata, payload)
                        : java.util.Collections.emptyList();
                    List<ItemStack> attributeCredits = new ArrayList<>();
                    String blockId = block == null ? null : Block.blockRegistry.getNameForObject(block);
                    if (blockId != null && Block.getBlockFromName(blockId) == block
                        && block.getClass()
                            .getName()
                            .startsWith("com.carpentersblocks.block.")) {
                        List<ItemStack> remainingNativeCosts = new ArrayList<>();
                        for (ItemStack cost : nativeCosts) remainingNativeCosts.add(cost.copy());
                        for (ItemStack attribute : HologramDecorMaterials.carpenterAttributeCosts(payload)
                            .values()) {
                            int remaining = attribute.stackSize;
                            for (ItemStack cost : remainingNativeCosts) {
                                if (!cost.isItemEqual(attribute) || !ItemStack.areItemStackTagsEqual(cost, attribute))
                                    continue;
                                int covered = Math.min(remaining, cost.stackSize);
                                cost.stackSize -= covered;
                                remaining -= covered;
                                if (remaining == 0) break;
                            }
                            if (remaining != 0)
                                throw new IOException("Native Carpenter attribute has no complete material bill");
                            attributeCredits.add(attribute.copy());
                        }
                    }
                    // Only audited cbAttrList rows receive credit. The shell and unrelated native costs cannot
                    // exempt an independent unknown item record elsewhere in this payload.
                    HologramBlueprintResources.Plan extra = HologramBlueprintResources.plan(payload, attributeCredits)
                        .merge(
                            HologramBlueprintResources
                                .plan(HologramBlueprintNbt.nativePayload(HologramBlueprintNbt.decode(cell.itemNbt))));
                    if (nativePlacement) {
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
