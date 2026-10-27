package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

/** Detached, repeatable authoring projection. The server retains the unmodified review source in its session. */
final class HologramBlueprintProcessing {

    static final int VERSION = 1;
    static final List<String> STAGES = java.util.Collections
        .unmodifiableList(Arrays.asList("preset", "identity", "quantum", "coordinates", "nbt", "hints"));

    private HologramBlueprintProcessing() {}

    static final class CellNbtOverride {

        int x, y, z;
        boolean all = true;
        List<String> keys = new ArrayList<>();
        List<String> ignoredPaths = new ArrayList<>();

        NBTTagCompound write() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("x", x);
            tag.setInteger("y", y);
            tag.setInteger("z", z);
            tag.setBoolean("all", all);
            tag.setTag("keys", strings(keys));
            tag.setTag("ignoredPaths", strings(ignoredPaths));
            return tag;
        }
    }

    static final class Policy {

        String tendency = "self", quantum = "preserve", coordinateDefault = "auto", autoHints = "light";
        int deidentificationLevel;
        List<String> confirmedStages = new ArrayList<>();
        List<String> ignoredNbtKeys = new ArrayList<>(), ignoredNbtPaths = new ArrayList<>();
        Map<String, String> coordinateOverrides = new LinkedHashMap<>();
        List<CellNbtOverride> cellNbtOverrides = new ArrayList<>();
        List<ResourceDecision> resourceDecisions = new ArrayList<>();

        NBTTagCompound write() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("version", VERSION);
            tag.setString("tendency", tendency);
            tag.setInteger("deidentificationLevel", deidentificationLevel);
            tag.setString("quantum", quantum);
            tag.setString("coordinateDefault", coordinateDefault);
            tag.setString("autoHints", autoHints);
            tag.setTag("confirmedStages", strings(confirmedStages));
            tag.setTag("ignoredNbtKeys", strings(ignoredNbtKeys));
            tag.setTag("ignoredNbtPaths", strings(ignoredNbtPaths));
            NBTTagList relations = new NBTTagList();
            for (Map.Entry<String, String> entry : coordinateOverrides.entrySet()) {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("id", entry.getKey());
                row.setString("mode", entry.getValue());
                relations.appendTag(row);
            }
            tag.setTag("coordinateOverrides", relations);
            NBTTagList overrides = new NBTTagList();
            for (CellNbtOverride cell : cellNbtOverrides) overrides.appendTag(cell.write());
            tag.setTag("cellNbtOverrides", overrides);
            NBTTagList resources = new NBTTagList();
            for (ResourceDecision decision : resourceDecisions) resources.appendTag(decision.write());
            tag.setTag("resourceDecisions", resources);
            return tag;
        }

        static Policy read(NBTTagCompound tag) throws IOException {
            if (tag == null || !tag.hasKey("version", 3) || tag.getInteger("version") != VERSION)
                throw new IOException("处理流程版本无效");
            Policy policy = new Policy();
            policy.tendency = choice(tag, "tendency", "self", "share");
            if (!tag.hasKey("deidentificationLevel", 3)) throw new IOException("请明确选择去标识等级");
            policy.deidentificationLevel = HologramDeidentification.checked(tag.getInteger("deidentificationLevel"));
            policy.quantum = choice(tag, "quantum", "preserve", "rebind", "clear");
            policy.coordinateDefault = choice(tag, "coordinateDefault", "auto", "clear", "absolute", "relative");
            policy.autoHints = choice(tag, "autoHints", "light", "full", "none");
            policy.confirmedStages = readStrings(tag, "confirmedStages", STAGES.size(), 32);
            for (int i = 0; i < policy.confirmedStages.size(); i++) if (!STAGES.get(i)
                .equals(policy.confirmedStages.get(i))) throw new IOException("请依次明确确认处理流程的每个阶段");
            policy.ignoredNbtKeys = readStrings(tag, "ignoredNbtKeys", 256, 256);
            policy.ignoredNbtPaths = readStrings(tag, "ignoredNbtPaths", 512, 1024);
            NBTTagList relations = compounds(tag, "coordinateOverrides", 8192);
            for (int i = 0; i < relations.tagCount(); i++) {
                NBTTagCompound row = relations.getCompoundTagAt(i);
                String id = text(row.getString("id"), 256);
                String mode = choice(row, "mode", "clear", "absolute", "relative");
                if (id.isEmpty() || policy.coordinateOverrides.put(id, mode) != null)
                    throw new IOException("坐标关系设置无效或重复");
            }
            NBTTagList cells = compounds(tag, "cellNbtOverrides", 8192);
            Set<String> positions = new LinkedHashSet<>();
            for (int i = 0; i < cells.tagCount(); i++) {
                NBTTagCompound row = cells.getCompoundTagAt(i);
                if (!row.hasKey("x", 3) || !row.hasKey("y", 3) || !row.hasKey("z", 3) || !row.hasKey("all", 1))
                    throw new IOException("单格NBT设置缺少位置或明确选择");
                CellNbtOverride cell = new CellNbtOverride();
                cell.x = row.getInteger("x");
                cell.y = row.getInteger("y");
                cell.z = row.getInteger("z");
                cell.all = row.getBoolean("all");
                cell.keys = readStrings(row, "keys", 256, 256);
                cell.ignoredPaths = readStrings(row, "ignoredPaths", 512, 1024);
                if (!positions.add(position(cell.x, cell.y, cell.z))) throw new IOException("单格NBT设置位置重复");
                policy.cellNbtOverrides.add(cell);
            }
            for (String path : policy.ignoredNbtPaths) checkPath(path);
            for (CellNbtOverride cell : policy.cellNbtOverrides) for (String path : cell.ignoredPaths) checkPath(path);
            NBTTagList resources = compounds(tag, "resourceDecisions", 8192);
            Set<String> decisions = new LinkedHashSet<>();
            for (int i = 0; i < resources.tagCount(); i++) {
                NBTTagCompound row = resources.getCompoundTagAt(i);
                if (!row.hasKey("x", 3) || !row.hasKey("y", 3) || !row.hasKey("z", 3))
                    throw new IOException("资源处理决定缺少方块位置");
                ResourceDecision decision = new ResourceDecision();
                decision.x = row.getInteger("x");
                decision.y = row.getInteger("y");
                decision.z = row.getInteger("z");
                decision.path = text(row.getString("path"), 1024);
                checkPath(decision.path);
                decision.action = choice(row, "action", "remove", "preserve", "cancel");
                if (!decisions.add(position(decision.x, decision.y, decision.z) + decision.path))
                    throw new IOException("资源处理决定重复");
                policy.resourceDecisions.add(decision);
            }
            return policy;
        }

        boolean complete() {
            return confirmedStages.equals(STAGES);
        }
    }

    static final class ResourceDecision {

        int x, y, z;
        String path, action;

        NBTTagCompound write() {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", x);
            row.setInteger("y", y);
            row.setInteger("z", z);
            row.setString("scope", path.startsWith("tile:") ? "tile" : "item");
            row.setString("path", path);
            row.setString("action", action);
            return row;
        }
    }

    static Policy defaults(String tendency, int minimumLevel) throws IOException {
        Policy policy = new Policy();
        if (!"self".equals(tendency) && !"share".equals(tendency)) throw new IOException("处理倾向无效");
        policy.tendency = tendency;
        policy.deidentificationLevel = Math
            .max(HologramDeidentification.checked(minimumLevel), "share".equals(tendency) ? 6 : 0);
        policy.quantum = "share".equals(tendency) ? "rebind" : "preserve";
        policy.autoHints = "light";
        return policy;
    }

    static Policy policy(HologramPresets.Preset preset) throws IOException {
        return policy(preset, new HologramAnalysisFacts());
    }

    static Policy policy(HologramPresets.Preset preset, HologramAnalysisFacts facts) throws IOException {
        if (preset != null && preset.processingNbt != null) {
            NBTTagCompound receipt = facts.read(preset.processingNbt);
            if (receipt.hasKey("policy", 10)) return Policy.read(receipt.getCompoundTag("policy"));
        }
        return defaults("self", minimumLevel(preset, facts));
    }

    /** Owner-scoped policy discovery; one exact cell identity obligation per cooperative operation. */
    static final class PolicyTask implements AutoCloseable {

        private final HologramPresets.Preset source;
        private final HologramAnalysisFacts facts = new HologramAnalysisFacts();
        private final Thread owner = Thread.currentThread();
        private boolean initialized, closed;
        private int cursor;
        private Policy result;

        PolicyTask(HologramPresets.Preset source) {
            this.source = source;
        }

        long completed() { return cursor; }

        long total() { return source == null ? 0 : source.cells.size(); }

        Policy result() {
            if (result == null) throw new IllegalStateException("Policy is not complete");
            return result;
        }

        boolean step(int maxWork, long maxNanos) throws IOException {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Policy discovery requires its owner thread");
            if (result != null) return true;
            if (closed) throw new IllegalStateException("Policy discovery was cancelled");
            HologramPlanBudget budget = new HologramPlanBudget(maxWork, maxNanos);
            while (result == null && budget.take()) {
                if (!initialized) {
                    initialized = true;
                    if (source != null && source.processingNbt != null) {
                        NBTTagCompound receipt = facts.read(source.processingNbt);
                        if (receipt.hasKey("policy", 10)) {
                            result = Policy.read(receipt.getCompoundTag("policy"));
                            continue;
                        }
                    }
                    int minimum = HologramDeidentification.level(source);
                    if (source == null || minimum > 0) result = defaults("self", minimum);
                } else if (cursor < source.cells.size()) {
                    if (identityBoundary(source.cells.get(cursor++), facts)) result = defaults("self", 1);
                } else result = defaults("self", 0);
            }
            return result != null;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            facts.close();
        }
    }

    static HologramPresets.Preset process(HologramPresets.Preset source, Policy requested, int dimension, int originX,
        int originY, int originZ) throws IOException {
        try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
            return process(source, requested, dimension, originX, originY, originZ, facts);
        }
    }

    private static HologramPresets.Preset process(HologramPresets.Preset source, Policy requested, int dimension,
        int originX, int originY, int originZ, HologramAnalysisFacts facts) throws IOException {
        if (source == null || requested == null) throw new IOException("没有可处理的蓝图来源");
        Policy policy = Policy.read(requested.write());
        if (!policy.complete()) throw new IOException("保存前必须明确完成全部处理阶段");
        facts.bind(source);
        int minimum = minimumLevel(source, facts);
        if (policy.deidentificationLevel < minimum) throw new IOException("旧结果已清除的身份不能恢复，请选择至少等级 " + minimum);
        // Capture exact coordinate schemas before any identity projection removes a registered relation field.
        List<HologramCoordinatePolicies.Relation> relations = HologramCoordinatePolicies
            .inspect(source, dimension, originX, originY, originZ, facts);
        HologramPresets.Preset selected = HologramQuantumGroups.defaulted(source, facts);
        removeGeneratedHints(selected);
        selected.processingNbt = null;
        Map<String, CellNbtOverride> overrides = new HashMap<>();
        for (CellNbtOverride entry : policy.cellNbtOverrides) overrides.put(position(entry.x, entry.y, entry.z), entry);
        Map<String, List<ResourceDecision>> resources = resourceIndex(policy);
        Set<String> matchedResources = new LinkedHashSet<>();
        ScopeReviews scopeReviews = new ScopeReviews(facts);
        List<SelectedScope[]> selectedScopes = new ArrayList<>();
        for (HologramPresets.Cell cell : selected.cells) {
            if (resources.containsKey(position(cell.x, cell.y, cell.z)))
                matchedResources.add(position(cell.x, cell.y, cell.z));
            applyResourceDecisions(
                cell,
                resources.getOrDefault(position(cell.x, cell.y, cell.z), java.util.Collections.emptyList()),
                facts);
            CellNbtOverride override = overrides.get(position(cell.x, cell.y, cell.z));
            SelectedScope tileScope = select(
                cell.tileNbt,
                "tile",
                policy,
                override,
                selected.retainNbt,
                selected.retainedNbtKeys,
                selected.retainCovers,
                cell,
                scopeReviews);
            SelectedScope itemScope = select(
                cell.itemNbt,
                "item",
                policy,
                override,
                selected.retainNbt,
                selected.retainedNbtKeys,
                selected.retainCovers,
                cell,
                scopeReviews);
            cell.tileNbt = tileScope.encoded;
            cell.itemNbt = itemScope.encoded;
            selectedScopes.add(new SelectedScope[] { tileScope, itemScope });
        }
        if (!matchedResources.containsAll(resources.keySet())) throw new IOException("资源处理决定对应的方块已不存在，请重新进行处理");
        // All choices are now materialized in the detached cells. A legacy global filter must not override them later.
        selected.retainNbt = true;
        selected.retainedNbtKeys = null;
        // Palette entries are item/damage candidate samples, never installation or payment payloads.
        for (HologramPresets.Cell sample : selected.palette) {
            sample.tileNbt = null;
            sample.itemNbt = null;
        }
        HologramPresets.Preset result = HologramDeidentification
            .applyOwned(selected, policy.deidentificationLevel, null, HologramAePolicy.PreparationProgress.NONE);
        for (int i = 0; i < result.cells.size(); i++) {
            HologramPresets.Cell after = result.cells.get(i);
            after.tileNbt = selectedScopes.get(i)[0].restore(after.tileNbt);
            after.itemNbt = selectedScopes.get(i)[1].restore(after.itemNbt);
        }
        result.deidentificationChosen = true;
        HologramCoordinatePolicies.apply(result, relations, policy, facts);
        if ("clear".equals(policy.quantum)) clearQuantum(result);
        else applyQuantumMode(result, policy.quantum);
        for (int i = 0; i < result.cells.size(); i++) {
            HologramPresets.Cell after = result.cells.get(i);
            if (after.excluded) continue;
            scopeReviews.finish(after, "tile", selectedScopes.get(i)[0], after.tileNbt);
            scopeReviews.finish(after, "item", selectedScopes.get(i)[1], after.itemNbt);
        }
        NBTTagCompound generatedHints = addHints(source, result, relations, policy, facts);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", VERSION);
        receipt.setBoolean("complete", true);
        receipt.setBoolean("legacy", false);
        receipt.setInteger("minimumLevel", minimum);
        receipt.setTag("policy", policy.write());
        receipt.setTag("generatedHints", generatedHints);
        NBTTagCompound reports = HologramExtensionSnapshots.authoring(result);
        if (!reports.hasNoTags()) receipt.setTag("extensions", reports);
        result.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
        facts.publish();
        result.analysisDocument = source.analysisDocument;
        return result;
    }

    static NBTTagCompound state(HologramPresets.Preset source, Policy policy, int dimension, int originX, int originY,
        int originZ) throws IOException {
        try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
            NBTTagCompound result = state(source, policy, dimension, originX, originY, originZ, facts);
            facts.publish();
            return result;
        }
    }

    static NBTTagCompound state(HologramPresets.Preset source, Policy policy, int dimension, int originX, int originY,
        int originZ, HologramAnalysisFacts facts) throws IOException {
        facts.bind(source);
        if (policy == null) policy = policy(source, facts);
        NBTTagCompound state = new NBTTagCompound();
        state.setTag("policy", policy.write());
        state.setBoolean("complete", complete(source));
        state.setBoolean("legacy", legacy(source));
        int minimum = minimumLevel(source, facts);
        List<HologramCoordinatePolicies.Relation> relations = HologramCoordinatePolicies
            .inspect(source, dimension, originX, originY, originZ, facts);
        state.setInteger("minimumLevel", minimum);
        HologramQuantumGroups.Facts quantum = HologramQuantumGroups.analyze(source, facts);
        state.setTag("quantumGroups", quantum.groups());
        state.setTag("quantumNodes", quantum.nodes());
        state.setBoolean("quantumGroupsConfirmed", quantum.confirmed());
        NBTTagList rows = new NBTTagList();
        for (HologramCoordinatePolicies.Relation relation : relations) {
            NBTTagCompound row = relation.write(HologramCoordinatePolicies.mode(relation, policy));
            row.setInteger("targetX", relation.targetX);
            row.setInteger("targetY", relation.targetY);
            row.setInteger("targetZ", relation.targetZ);
            row.setInteger("targetDimension", relation.targetDimension);
            row.setBoolean("targetKnown", !"clear".equals(relation.previousMode));
            rows.appendTag(row);
        }
        state.setTag("relations", rows);
        Set<String> keys = new java.util.TreeSet<>(), paths = new java.util.TreeSet<>();
        ResourceReview review = new ResourceReview(facts);
        int required = minimum > 0 ? 1 << 1 : 0;
        if (!relations.isEmpty()) required |= 1 << 3;
        int index = 0;
        if (source != null) for (HologramPresets.Cell cell : source.cells) {
            NBTTagCompound fields = facts.certificate(
                cell,
                HologramSegmentContract.Domain.REPORT,
                "fields",
                new Object[] { cell.block(), cell.tileNbt, cell.itemNbt, HologramSegmentContract.SCHEMA_EPOCH },
                true,
                () -> fieldFacts(cell, facts));
            NBTTagList fieldKeys = fields.getTagList("keys", 8), fieldPaths = fields.getTagList("paths", 8);
            for (int i = 0; i < fieldKeys.tagCount(); i++) keys.add(fieldKeys.getStringTagAt(i));
            for (int i = 0; i < fieldPaths.tagCount(); i++) paths.add(fieldPaths.getStringTagAt(i));
            required |= fields.getInteger("required");
            if (!cell.excluded) {
                review.add(cell, index, "tile", cell.tileNbt);
                review.add(cell, index, "item", cell.itemNbt);
            }
            index++;
        }
        state.setTag("nbtKeys", strings(new ArrayList<>(keys).subList(0, Math.min(256, keys.size()))));
        state.setTag("nbtPaths", strings(new ArrayList<>(paths).subList(0, Math.min(4096, paths.size()))));
        state.setInteger(
            "requiredStages",
            source == null || source.cells.isEmpty() ? 63 : required == 0 ? 0 : required | 1 << 5);
        review.write(state);
        return state;
    }

    private static NBTTagCompound fieldFacts(HologramPresets.Cell cell, HologramAnalysisFacts facts)
        throws IOException {
        NBTTagCompound tile = facts.read(cell.tileNbt), item = facts.read(cell.itemNbt);
        Set<String> keys = new java.util.TreeSet<>(), paths = new java.util.TreeSet<>();
        collect(tile, "tile:", keys, paths);
        collect(item, "item:", keys, paths);
        NBTTagCompound result = new NBTTagCompound();
        result.setTag("keys", strings(new ArrayList<>(keys).subList(0, Math.min(256, keys.size()))));
        result.setTag("paths", strings(new ArrayList<>(paths).subList(0, Math.min(4096, paths.size()))));
        result.setInteger("required", requiredStages(cell, tile, item));
        return result;
    }

    private static Map<String, List<ResourceDecision>> resourceIndex(Policy policy) {
        Map<String, List<ResourceDecision>> result = new HashMap<>();
        for (ResourceDecision decision : policy.resourceDecisions)
            result.computeIfAbsent(position(decision.x, decision.y, decision.z), ignored -> new ArrayList<>())
                .add(decision);
        for (List<ResourceDecision> decisions : result.values())
            decisions.sort((left, right) -> compareResourcePaths(right.path, left.path));
        return result;
    }

    private static int compareResourcePaths(String left, String right) {
        String[] a = left.split("/", -1), b = right.split("/", -1);
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int compared = a[i].matches("[0-9]+") && b[i].matches("[0-9]+")
                ? new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]))
                : a[i].compareTo(b[i]);
            if (compared != 0) return compared;
        }
        return Integer.compare(a.length, b.length);
    }

    private static void applyResourceDecisions(HologramPresets.Cell cell, List<ResourceDecision> decisions,
        HologramAnalysisFacts facts) throws IOException {
        if (decisions.isEmpty()) return;
        for (String scope : Arrays.asList("tile", "item")) {
            boolean selected = false;
            for (ResourceDecision decision : decisions) selected |= decision.path.startsWith(scope + ":");
            if (!selected) continue;
            NBTTagCompound before = facts.read("tile".equals(scope) ? cell.tileNbt : cell.itemNbt);
            NBTTagCompound tag = before == null ? null : (NBTTagCompound) before.copy();
            boolean changed = false;
            for (ResourceDecision decision : decisions) {
                if (!"cancel".equals(decision.action) || !decision.path.startsWith(scope + ":")) continue;
                if (tag == null) continue;
                HologramFullPreservation.forget(tag, HologramFullPreservation.root(decision.path));
                changed = true;
            }
            for (ResourceDecision decision : decisions) {
                if (!"remove".equals(decision.action) || !decision.path.startsWith(scope + ":")) continue;
                if (tag == null) continue;
                String path = decision.path.substring(decision.path.indexOf(':') + 2);
                if (!path.contains("/")) HologramFullPreservation.forget(tag, unescape(path));
                // A durable omitted-data marker is resolved only by the adapter's audited acknowledgement API.
                NBTTagList unresolved = tag.getTagList(HologramAeBlueprint.UNRESOLVED, 10);
                List<String> codes = new ArrayList<>();
                for (int i = 0; i < unresolved.tagCount(); i++) {
                    NBTTagCompound marker = unresolved.getCompoundTagAt(i);
                    if (marker.getString("path")
                        .equals(path)) codes.add(marker.getString("code"));
                }
                removePath(tag, decision.path.substring(decision.path.indexOf(':') + 1), cell.block());
                for (String code : codes) tag = HologramAeBlueprint.resolveDiscardedIssue(tag, code, path);
                changed = true;
            }
            for (ResourceDecision decision : decisions) {
                if (!"preserve".equals(decision.action) || !decision.path.startsWith(scope + ":")) continue;
                if (tag == null) throw new IOException("完全保留路径不存在：" + decision.path);
                if ("tile".equals(scope) && HologramAeBlueprint.supports(cell.block(), tag))
                    throw new IOException("AE私有资源须由专用格式解析；请移除无法解析的信息：" + decision.path);
                HologramFullPreservation.mark(tag, HologramFullPreservation.root(decision.path));
                changed = true;
            }
            if (!changed) continue;
            HologramBlueprintNbt.normalizeResourceSelection(tag);
            if (before.equals(tag)) continue;
            if ("tile".equals(scope)) cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
            else cell.itemNbt = HologramBlueprintNbt.encodeCanonical(tag);
        }
    }

    /** Reuses the decoded selection and its exact result; ordinary scopes never decode merely to find a marker. */
    private static final class SelectedScope {

        final String encoded, error;
        final NBTTagCompound preserved;

        SelectedScope(String encoded, String error, NBTTagCompound tag) {
            this.encoded = encoded;
            this.error = error;
            this.preserved = tag != null && tag.hasKey(HologramFullPreservation.TAG) ? tag : null;
        }

        String restore(String projected) throws IOException {
            if (preserved == null || java.util.Objects.equals(encoded, projected)) return projected;
            return HologramFullPreservation.restore(preserved, projected);
        }
    }

    /** Native Carpenter skins already have a separate exact material bill in the construction planner. */
    private static HologramBlueprintResources.Plan resourcePlan(HologramPresets.Cell cell, String scope,
        NBTTagCompound tag) throws IOException {
        List<net.minecraft.item.ItemStack> components = "tile".equals(scope)
            && HologramDeidentification.carpenterBlock(cell.block())
                ? new ArrayList<>(
                    HologramDecorMaterials.carpenterAttributeCosts(HologramBlueprintNbt.nativePayload(tag))
                        .values())
                : java.util.Collections.emptyList();
        return HologramBlueprintResources.plan(tag, components);
    }

    private static final class ScopeReviews {

        final HologramAnalysisFacts facts;

        ScopeReviews() {
            this(new HologramAnalysisFacts());
        }

        ScopeReviews(HologramAnalysisFacts facts) {
            this.facts = facts;
        }

        // A null value is a validated success, not a miss. Failures may be resolved by later author projections.
        final Map<ReportKey, String> outcomes = new LinkedHashMap<ReportKey, String>() {

            @Override
            protected boolean removeEldestEntry(Map.Entry<ReportKey, String> eldest) {
                return size() > 4096;
            }
        };

        SelectedScope select(HologramPresets.Cell cell, String scope, String encoded, NBTTagCompound tag) {
            String error = cell.excluded ? null : review(cell, scope, encoded, tag);
            return new SelectedScope(encoded, error, tag);
        }

        String review(HologramPresets.Cell cell, String scope, String encoded, NBTTagCompound decoded) {
            if (encoded == null || encoded.isEmpty()) return null;
            ReportKey key = new ReportKey(cell, scope, encoded);
            if (outcomes.containsKey(key)) return outcomes.get(key);
            String error = null;
            try {
                NBTTagCompound proof = facts.certificate(
                    cell,
                    HologramSegmentContract.Domain.ASSET_NEEDS,
                    "scope:" + scope,
                    new Object[] { cell.block(), cell.item, cell.damage, cell.metadata, scope, encoded,
                        com.miaokatze.gtmb.api.ResourceContainers.generation(), HologramNativePlacement.generation(),
                        HologramAeBlueprint.POLICY_VERSION },
                    true,
                    () -> {
                        String problem = inspect(cell, scope, encoded, decoded);
                        NBTTagCompound result = new NBTTagCompound();
                        result.setBoolean("success", problem == null);
                        if (problem != null) result.setString("error", problem);
                        return result;
                    });
                if (!proof.getBoolean("success")) error = proof.getString("error");
            } catch (IOException invalid) {
                error = HologramAePolicy.fallback(invalid.getMessage());
            }
            outcomes.put(key, error);
            return error;
        }

        private String inspect(HologramPresets.Cell cell, String scope, String encoded, NBTTagCompound decoded) {
            try {
                NBTTagCompound tag = decoded == null ? facts.read(encoded) : decoded;
                if (tag != null && "tile".equals(scope) && HologramAeBlueprint.supports(cell.block(), tag)) {
                    HologramAeBlueprint.Analysis analysis = facts.ae(cell.block(), cell.damage, encoded, tag);
                    if (analysis.blocking) {
                        for (HologramAeBlueprint.Issue issue : analysis.issues)
                            if ("blocker".equals(issue.severity)) throw new IOException(
                                "无法解析资源 " + scope + ":/" + issue.path + "：" + issue.message + "；请移除此信息");
                    }
                    tag = (NBTTagCompound) tag.copy();
                    for (String root : analysis.handledRoots) tag.removeTag(root);
                }
                resourcePlan(cell, scope, tag == null ? null : (NBTTagCompound) tag.copy());
            } catch (IOException invalid) {
                return HologramAePolicy.fallback(invalid.getMessage());
            }
            return null;
        }

        void finish(HologramPresets.Cell cell, String scope, SelectedScope before, String encoded) throws IOException {
            String error = before.error;
            if (!java.util.Objects.equals(before.encoded, encoded)) {
                NBTTagCompound tag = null;
                // Only explicitly preserved scopes require an unchanged-subtree check.
                if (before.preserved != null) {
                    tag = HologramBlueprintNbt.decode(encoded);
                    HologramFullPreservation.assertUnchanged(before.preserved, tag);
                }
                error = review(cell, scope, encoded, tag);
            }
            if (error != null)
                throw new IOException("蓝图格 " + position(cell.x, cell.y, cell.z) + " · " + scope + "：" + error);
        }
    }

    /** Detached reports use raw preset coordinates, exactly matching the decision policy. No world/inventory lookup. */
    private static final class ResourceReview {

        private final NBTTagList rows = new NBTTagList();
        private final HologramAnalysisFacts facts;
        // Complete immutable encoded NBT plus exact block/meta/scope, never merely a machine type.
        private final Map<ReportKey, ReviewIssues> cache = new LinkedHashMap<ReportKey, ReviewIssues>() {

            @Override
            protected boolean removeEldestEntry(Map.Entry<ReportKey, ReviewIssues> eldest) {
                return size() > 4096;
            }
        };
        private long total, blocking;
        private boolean nativeComplete = true;

        ResourceReview(HologramAnalysisFacts facts) {
            this.facts = facts;
        }

        void add(HologramPresets.Cell cell, int index, String scope, String encoded) throws IOException {
            if (encoded == null || encoded.isEmpty()) {
                resourceCounts(cell, scope, 0, 0);
                return;
            }
            ReportKey key = new ReportKey(cell, scope, encoded);
            NBTTagCompound frozen = HologramSnapshotDataContext.active()
                && !("tile".equals(scope) && HologramAeBlueprint.supports(cell.block(), facts.read(encoded)))
                    ? HologramSnapshotDataContext.resourceReview(cell, scope)
                    : null;
            NBTTagCompound certificate = facts.certificate(
                cell,
                HologramSegmentContract.Domain.ASSET_NEEDS,
                "resource:" + scope,
                HologramAnalysisDocument.resourceDependencies(cell, scope),
                !com.miaokatze.gtmb.api.ResourceContainers.hasCodecs()
                    && (cell.frozenAeTotal < 0 || facts.coverage(cell)),
                () -> {
                    if (frozen != null) return frozen;
                    ReviewIssues problems = cache.get(key);
                    if (problems == null) {
                        long[] count = { -1, 0 };
                        ProblemList rows = inspect(
                            cell,
                            scope,
                            facts.copyForEdit(encoded),
                            encoded,
                            count,
                            0,
                            Math.max(0, 8192 - this.rows.tagCount()),
                            false);
                        problems = new ReviewIssues(
                            rows,
                            count[0] < 0 ? rows.total : count[0],
                            count[0] < 0 ? rows.blockers : count[0],
                            count[1] == 0);
                        cache.put(key, problems);
                    }
                    NBTTagCompound result = new NBTTagCompound();
                    NBTTagList entries = new NBTTagList();
                    for (NBTTagCompound problem : problems.rows) entries.appendTag(problem.copy());
                    result.setTag("rows", entries);
                    result.setLong("total", problems.total);
                    result.setLong("blocking", problems.blocking);
                    result.setBoolean("nativeComplete", problems.complete);
                    return result;
                });
            NBTTagList problems = certificate.getTagList("rows", 10);
            resourceCounts(cell, scope, certificate.getLong("total"), certificate.getLong("blocking"));
            boolean complete = certificate.getBoolean("nativeComplete");
            if ("tile".equals(scope)) cell.resourceTileComplete = complete;
            else cell.resourceItemComplete = complete;
            nativeComplete &= complete;
            blocking += certificate.getLong("blocking");
            total += certificate.hasKey("total", 4) ? certificate.getLong("total") : problems.tagCount();
            for (int i = 0; i < problems.tagCount(); i++) {
                if (rows.tagCount() >= 8192) break;
                NBTTagCompound row = (NBTTagCompound) problems.getCompoundTagAt(i)
                    .copy();
                row.setInteger("index", index);
                row.setInteger("x", cell.x);
                row.setInteger("y", cell.y);
                row.setInteger("z", cell.z);
                rows.appendTag(row);
            }
        }

        private ProblemList inspect(HologramPresets.Cell cell, String scope, NBTTagCompound tag, String encoded,
            long[] count, int skip, int limit, boolean blockersOnly) throws IOException {
            return inspect(cell, scope, tag, encoded, count, skip, limit, blockersOnly, Long.MAX_VALUE);
        }

        private ProblemList inspect(HologramPresets.Cell cell, String scope, NBTTagCompound tag, String encoded,
            long[] count, int skip, int limit, boolean blockersOnly, long byteLimit) throws IOException {
            ProblemList problems = new ProblemList(skip, limit, blockersOnly, byteLimit);
            if ("tile".equals(scope) && HologramAeBlueprint.supports(cell.block(), tag)) {
                try {
                    List<HologramAeBlueprint.Issue> issues;
                    if (HologramSnapshotDataContext.active()) {
                        HologramSnapshotDataContext.AeIssues frozen = HologramSnapshotDataContext.aeIssues(cell);
                        if (frozen.failure != null) throw new IOException(frozen.failure);
                        issues = frozen.issues;
                        count[0] = frozen.total;
                    } else issues = facts.ae(cell.block(), cell.damage, encoded, tag).issues;
                    for (HologramAeBlueprint.Issue issue : issues) {
                        if (!"blocker".equals(issue.severity) || issue.path.isEmpty()) continue;
                        if (Arrays
                            .asList("unsupported_host", "unsupported_part", "unsupported_part_item", "unsupported_cell")
                            .contains(issue.code)) continue;
                        problems.add(resourceRow(cell, -1, scope, "/" + issue.path, issue.message, false));
                    }
                    return problems;
                } catch (IOException invalid) {
                    count[1] = 1;
                    // A malformed schema cannot prevent opening its exact removal controls.
                    for (Object raw : new java.util.TreeSet<Object>(tag.func_150296_c())) {
                        String root = (String) raw;
                        if (!HologramBlueprintNbt.protectedKey(cell.block(), tag, root))
                            problems.add(resourceRow(cell, -1, scope, "/" + escape(root), invalid.getMessage(), false));
                    }
                    return problems;
                }
            }
            Set<String> preserved = HologramFullPreservation.roots(tag);
            try {
                resourcePlan(cell, scope, tag);
                for (String root : preserved) {
                    NBTTagCompound row = resourceRow(cell, -1, scope, "/" + escape(root), "此路径已完全保留", true);
                    row.setBoolean("preserved", true);
                    row.setBoolean("canCancelPreserve", true);
                    NBTTagCompound ordinary = identity(cell, tag);
                    ordinary.setTag(
                        root,
                        tag.getTag(root)
                            .copy());
                    HologramBlueprintNbt.includeResourceCompanions(tag, ordinary, root);
                    try {
                        resourcePlan(cell, scope, ordinary);
                        row.setBoolean("canCancel", true);
                    } catch (IOException stillUnknown) {
                        row.setString("message", "此路径已完全保留；取消后仍须移除或重新选择完全保留");
                    }
                    problems.add(row);
                }
                return problems;
            } catch (IOException unsupported) {
                // Only failures need root isolation; copy just essential identity and this root, not the whole tile.
                NBTTagCompound identity = identity(cell, tag);
                for (Object raw : new java.util.TreeSet<Object>(tag.func_150296_c())) {
                    String root = (String) raw;
                    if (HologramBlueprintNbt.protectedKey(cell.block(), tag, root)) continue;
                    NBTTagCompound selected = (NBTTagCompound) identity.copy();
                    selected.setTag(
                        root,
                        tag.getTag(root)
                            .copy());
                    HologramBlueprintNbt.includeResourceCompanions(tag, selected, root);
                    try {
                        resourcePlan(cell, scope, selected);
                        if (preserved.contains(root)) {
                            NBTTagCompound row = resourceRow(cell, -1, scope, "/" + escape(root), "此路径已完全保留", true);
                            row.setBoolean("preserved", true);
                            row.setBoolean("canCancelPreserve", true);
                            row.setBoolean("canCancel", true);
                            problems.add(row);
                        }
                    } catch (IOException invalid) {
                        boolean preserve = false;
                        String message = invalid.getMessage();
                        try {
                            HologramFullPreservation.mark(selected, root);
                            resourcePlan(cell, scope, selected);
                            preserve = true;
                        } catch (IOException unparsed) {
                            message = unparsed.getMessage();
                        }
                        NBTTagCompound row = resourceRow(cell, -1, scope, "/" + escape(root), message, preserve);
                        if (preserved.contains(root)) {
                            row.setBoolean("preserved", true);
                            row.setBoolean("canCancelPreserve", true);
                        }
                        problems.add(row);
                    }
                }
            }
            return problems;
        }

        private static final class ReviewIssues {

            final List<NBTTagCompound> rows;
            final long total, blocking;
            final boolean complete;

            ReviewIssues(List<NBTTagCompound> rows, long total, long blocking, boolean complete) {
                this.rows = rows;
                this.total = total;
                this.blocking = blocking;
                this.complete = complete;
            }
        }

        private static final class ProblemList extends ArrayList<NBTTagCompound> {

            private static final long serialVersionUID = 1L;
            final int skip, limit;
            final boolean blockersOnly;
            long total, blockers, selected;
            final long byteLimit;
            long bytes;
            boolean byteLimitReached;

            ProblemList(int skip, int limit, boolean blockersOnly, long byteLimit) {
                this.skip = skip;
                this.limit = limit;
                this.blockersOnly = blockersOnly;
                this.byteLimit = byteLimit;
            }

            @Override
            public boolean add(NBTTagCompound row) {
                total++;
                boolean blocking = !row.getBoolean("preserved") || !row.getBoolean("canFullPreserve");
                if (blocking) blockers++;
                if (blockersOnly && !blocking) return true;
                if (selected++ < skip || size() >= limit) return true;
                long cost = HologramSnapshotNbt.estimate(row);
                if (byteLimitReached || cost > byteLimit - bytes) {
                    byteLimitReached = true;
                    return true;
                }
                bytes += cost;
                return super.add(row);
            }
        }

        private static NBTTagCompound identity(HologramPresets.Cell cell, NBTTagCompound tag) {
            NBTTagCompound identity = new NBTTagCompound();
            for (Object raw : tag.func_150296_c()) {
                String root = (String) raw;
                if (!HologramFullPreservation.TAG.equals(root)
                    && HologramBlueprintNbt.protectedKey(cell.block(), tag, root))
                    identity.setTag(
                        root,
                        tag.getTag(root)
                            .copy());
            }
            return identity;
        }

        void write(NBTTagCompound state) {
            state.setTag("resourceReview", rows);
            NBTTagList blockers = new NBTTagList();
            for (int i = 0; i < rows.tagCount(); i++) {
                NBTTagCompound row = rows.getCompoundTagAt(i);
                if (!row.getBoolean("preserved") || !row.getBoolean("canFullPreserve")) blockers.appendTag(row.copy());
            }
            state.setTag("resourceBlockers", blockers);
            state.setLong("resourceReviewTotal", total);
            state.setLong("resourceBlockingTotal", blocking);
            state.setBoolean("resourceSummaryComplete", nativeComplete);
            state.setBoolean("resourceReviewTruncated", total > rows.tagCount());
            state.setBoolean("resourceReviewComplete", nativeComplete && total == rows.tagCount());
            state.setString("resourceCoordinateSpace", "preset");
        }
    }

    private static void resourceCounts(HologramPresets.Cell cell, String scope, long rows, long blockers) {
        if ("tile".equals(scope)) {
            cell.resourceTileRows = rows;
            cell.resourceTileBlockers = blockers;
            cell.resourceTileComplete = true;
        } else {
            cell.resourceItemRows = rows;
            cell.resourceItemBlockers = blockers;
            cell.resourceItemComplete = true;
        }
    }

    /** Owner-only provider evaluation; workers consume the immutable rows and complete counts. */
    static NBTTagCompound freezeResourceReview(HologramPresets.Cell cell, String scope, HologramAnalysisFacts facts,
        int limit, long byteLimit) throws IOException {
        if (HologramSnapshotDataContext.active())
            throw new IllegalStateException("Resource providers require the owner");
        String encoded = "tile".equals(scope) ? cell.tileNbt : cell.itemNbt;
        long[] count = { -1, 0 };
        ResourceReview.ProblemList problems = new ResourceReview(facts)
            .inspect(cell, scope, facts.copyForEdit(encoded), encoded, count, 0, limit, false, byteLimit);
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        for (NBTTagCompound row : problems) rows.appendTag(row);
        result.setTag("rows", rows);
        result.setLong("total", count[0] < 0 ? problems.total : count[0]);
        result.setLong("blocking", count[0] < 0 ? problems.blockers : count[0]);
        result.setBoolean("nativeComplete", count[1] == 0);
        return result;
    }

    static NBTTagCompound resourceScopePage(HologramPresets.Cell cell, int index, String scope, int offset, int limit,
        boolean blockersOnly, long byteLimit, HologramAnalysisFacts facts) throws IOException {
        if (HologramSnapshotDataContext.active()) throw new IllegalStateException("Resource pages require the owner");
        String encoded = "tile".equals(scope) ? cell.tileNbt : cell.itemNbt;
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        long total = 0;
        boolean complete = true;
        if (encoded != null && !encoded.isEmpty()) {
            long[] count = { -1, 0 };
            ResourceReview.ProblemList problems = new ResourceReview(facts).inspect(
                cell,
                scope,
                facts.copyForEdit(encoded),
                encoded,
                count,
                offset,
                limit,
                blockersOnly,
                byteLimit);
            total = count[0] >= 0 ? count[0] : blockersOnly ? problems.blockers : problems.total;
            complete = count[1] == 0;
            result.setLong("bytes", problems.bytes);
            result.setBoolean("byteLimitReached", problems.byteLimitReached);
            for (NBTTagCompound original : problems) {
                NBTTagCompound row = (NBTTagCompound) original.copy();
                row.setInteger("index", index);
                rows.appendTag(row);
            }
        }
        result.setTag("rows", rows);
        result.setLong("total", total);
        result.setBoolean("complete", complete);
        return result;
    }

    private static final class ReportKey {

        final String block, scope, encoded;
        final net.minecraft.block.Block type;
        final int metadata, damage;
        final long generation;

        ReportKey(HologramPresets.Cell cell, String scope, String encoded) {
            this.block = cell.block;
            this.type = cell.block();
            this.metadata = cell.metadata;
            this.damage = cell.damage;
            this.generation = com.miaokatze.gtmb.api.ResourceContainers.generation();
            this.scope = scope;
            this.encoded = encoded;
        }

        @Override
        public int hashCode() {
            return java.util.Objects
                .hash(block, System.identityHashCode(type), metadata, damage, generation, scope, encoded);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ReportKey)) return false;
            ReportKey key = (ReportKey) other;
            return type == key.type && metadata == key.metadata
                && damage == key.damage
                && generation == key.generation
                && java.util.Objects.equals(block, key.block)
                && scope.equals(key.scope)
                && encoded.equals(key.encoded);
        }
    }

    private static NBTTagCompound resourceRow(HologramPresets.Cell cell, int index, String scope, String path,
        String message, boolean preserve) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("index", index);
        row.setInteger("x", cell.x);
        row.setInteger("y", cell.y);
        row.setInteger("z", cell.z);
        row.setString("scope", scope);
        row.setString("path", scope + ":" + path);
        row.setString(
            "rootKey",
            path.substring(1)
                .split("/", 2)[0]);
        row.setString("message", message == null ? "资源格式未能识别" : message);
        row.setBoolean("canPreserve", preserve);
        row.setBoolean("canFullPreserve", preserve);
        row.setBoolean("canMigrate", false);
        return row;
    }

    private static String escape(String key) {
        return key.replace("~", "~0")
            .replace("/", "~1");
    }

    /** Derived from the actual server source, never from a client claim that sensitive data is absent. */
    static int requiredStages(HologramPresets.Preset source, int dimension, int x, int y, int z) throws IOException {
        if (source == null || source.cells.isEmpty()) return 63;
        HologramAnalysisFacts facts = new HologramAnalysisFacts();
        return requiredStages(
            source,
            minimumLevel(source, facts),
            !HologramCoordinatePolicies.inspect(source, dimension, x, y, z, facts)
                .isEmpty(),
            facts);
    }

    private static int requiredStages(HologramPresets.Preset source, int minimum, boolean hasRelations,
        HologramAnalysisFacts facts) throws IOException {
        if (source == null || source.cells.isEmpty()) return 63;
        int required = minimum > 0 ? 1 << 1 : 0;
        if (hasRelations) required |= 1 << 3;
        for (HologramPresets.Cell cell : source.cells) {
            NBTTagCompound tile = facts.read(cell.tileNbt);
            required |= requiredStages(cell, tile, facts.read(cell.itemNbt));
        }
        if (required != 0) required |= 1 << 5;
        return required;
    }

    private static int requiredStages(HologramPresets.Cell cell, NBTTagCompound tile, NBTTagCompound item) {
        int required = 0;
        if (HologramNetworkBindings.node(cell.block()) || tile != null
            && (tile.hasKey(HologramNetworkBindings.GROUP) || tile.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR)))
            required |= 1 << 2;
        for (NBTTagCompound tag : new NBTTagCompound[] { tile, item }) {
            if (tag == null) continue;
            Map<Integer, List<String>> risks = HologramDeidentification.retainedRiskFields(cell, tag, "");
            for (List<String> paths : risks.values())
                for (String path : paths) if (!path.matches("/?[xyz]")) required |= 1 << 1;
            for (Object raw : tag.func_150296_c()) {
                String key = (String) raw;
                if (!Arrays.asList("id", "x", "y", "z", "gtitBlueprintItemRegistry", "gtitBlueprintCoverRegistry")
                    .contains(key)) required |= 1 << 4;
            }
        }
        return required;
    }

    static boolean complete(HologramPresets.Preset preset) throws IOException {
        if (preset == null || preset.processingNbt == null) return false;
        NBTTagCompound receipt = HologramBlueprintNbt.decode(preset.processingNbt);
        return receipt.getBoolean("complete") && !receipt.getBoolean("legacy")
            && receipt.hasKey("policy", 10)
            && Policy.read(receipt.getCompoundTag("policy"))
                .complete();
    }

    /** Canonical AE/quantum analysis may rediscover a shell node; the explicit completed author decision wins. */
    static HologramPresets.Preset enforcePolicy(HologramPresets.Preset preset) throws IOException {
        if (quantumCleared(preset)) clearQuantum(preset);
        else if (complete(preset)) applyQuantumMode(preset, policy(preset).quantum);
        return preset;
    }

    private static void applyQuantumMode(HologramPresets.Preset preset, String mode) throws IOException {
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null || !tag.hasKey(HologramNetworkBindings.GROUP)) continue;
            if (tag.hasKey(HologramNetworkBindings.MODE, 8) && mode.equals(tag.getString(HologramNetworkBindings.MODE))
                && (!"rebind".equals(mode) || !tag.hasKey(HologramNetworkBindings.ORIGINAL))) continue;
            tag.setString(HologramNetworkBindings.MODE, mode);
            if ("rebind".equals(mode)) tag.removeTag(HologramNetworkBindings.ORIGINAL);
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        }
    }

    static boolean quantumCleared(HologramPresets.Preset preset) throws IOException {
        return complete(preset) && "clear".equals(policy(preset).quantum);
    }

    /** Native AE/verified network reconstruction already has an irreversible level-one identity boundary. */
    static int minimumLevel(HologramPresets.Preset preset) throws IOException {
        return minimumLevel(preset, new HologramAnalysisFacts());
    }

    private static int minimumLevel(HologramPresets.Preset preset, HologramAnalysisFacts facts) throws IOException {
        int minimum = HologramDeidentification.level(preset);
        if (preset == null || minimum > 0) return minimum;
        for (HologramPresets.Cell cell : preset.cells) if (identityBoundary(cell, facts)) return 1;
        return minimum;
    }

    /** The same complete native identity obligation serves synchronous and resumed policy discovery. */
    private static boolean identityBoundary(HologramPresets.Cell cell, HologramAnalysisFacts facts) throws IOException {
        NBTTagCompound identity = facts.certificate(
            cell,
            HologramSegmentContract.Domain.REPORT,
            "identity-floor",
            new Object[] { cell.block(), cell.damage, cell.tileNbt, cell.metadataIdentityBoundary,
                HologramSegmentContract.SCHEMA_EPOCH },
            true,
            () -> {
                boolean boundary;
                if (HologramSnapshotMetadataSource.detached() && cell.metadataIdentityBoundary != null)
                    boundary = cell.metadataIdentityBoundary;
                else {
                    NBTTagCompound tag = facts.read(cell.tileNbt);
                    boundary = HologramAeBlueprint.supports(cell.block(), tag)
                        || HologramNetworkBindings.node(cell.block())
                        || tag != null && (tag.hasKey(HologramNetworkBindings.GROUP)
                            || tag.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR))
                        || HologramNetworkSchemas.removeExternalReferences(
                            cell.block(),
                            cell.damage,
                            tag == null ? null : (NBTTagCompound) tag.copy());
                }
                NBTTagCompound result = new NBTTagCompound();
                result.setBoolean("boundary", boundary);
                return result;
            });
        return identity.getBoolean("boundary");
    }

    private static boolean legacy(HologramPresets.Preset preset) throws IOException {
        return preset != null && (preset.processingNbt == null ? preset.id != null
            : HologramBlueprintNbt.decode(preset.processingNbt)
                .getBoolean("legacy"));
    }

    static void markLegacy(HologramPresets.Preset preset) throws IOException {
        if (preset == null || preset.processingNbt != null) return;
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", VERSION);
        receipt.setBoolean("legacy", true);
        receipt.setBoolean("complete", false);
        receipt.setInteger("minimumLevel", HologramDeidentification.level(preset));
        receipt.setString("reason", "旧蓝图没有逐阶段处理记录；已经清除的身份不能恢复");
        preset.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
    }

    static void validateReceipt(HologramPresets.Preset preset) throws IOException {
        if (preset.processingNbt == null) return;
        NBTTagCompound receipt = HologramBlueprintNbt.decode(preset.processingNbt);
        if (receipt == null || !receipt.hasKey("version", 3) || receipt.getInteger("version") != VERSION)
            throw new IOException("蓝图处理记录版本无效");
        if (receipt.hasKey("policy", 10)) {
            Policy policy = Policy.read(receipt.getCompoundTag("policy"));
            if (receipt.getBoolean("complete") && !policy.complete()) throw new IOException("蓝图处理记录不完整");
        } else if (receipt.getBoolean("complete")) throw new IOException("蓝图处理记录缺少阶段选择");
        generatedBlocks(preset);
    }

    static void invalidate(HologramPresets.Preset preset) throws IOException {
        invalidate(preset, "identity");
    }

    static void invalidate(HologramPresets.Preset preset, String dependency) throws IOException {
        invalidate(preset, dependency, null);
    }

    /** Annotation edits can reuse the session's already derived policy without inspecting every payload again. */
    static void invalidate(HologramPresets.Preset preset, String dependency, Policy knownPolicy) throws IOException {
        if (preset == null) return;
        NBTTagCompound previous = HologramBlueprintNbt.decode(preset.processingNbt);
        Policy policy = knownPolicy != null ? knownPolicy
            : previous != null && previous.hasKey("policy", 10) ? Policy.read(previous.getCompoundTag("policy"))
                : policy(preset);
        String stage = "prompt".equals(dependency) ? "hints"
            : "quantum".equals(dependency) ? "quantum"
                : "geometry".equals(dependency) ? "coordinates" : "nbt".equals(dependency) ? "nbt" : "identity";
        int index = STAGES.indexOf(stage);
        while (policy.confirmedStages.size() > index) policy.confirmedStages.remove(policy.confirmedStages.size() - 1);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", VERSION);
        receipt.setBoolean("complete", false);
        receipt.setBoolean("legacy", previous == null ? preset.id != null : previous.getBoolean("legacy"));
        receipt.setInteger("minimumLevel", HologramDeidentification.level(preset));
        receipt.setTag("policy", policy.write());
        if (previous != null && previous.hasKey("generatedHints", 10)) receipt.setTag(
            "generatedHints",
            previous.getCompoundTag("generatedHints")
                .copy());
        preset.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
    }

    private static SelectedScope select(String encoded, String kind, Policy policy, CellNbtOverride override,
        boolean retainNbt, List<String> retainedKeys, boolean covers, HologramPresets.Cell cell, ScopeReviews reviews)
        throws IOException {
        NBTTagCompound tag = reviews.facts.read(encoded);
        if (tag == null) return reviews.select(cell, kind, null, null);
        net.minecraft.block.Block block = cell.block();
        NBTTagCompound before = tag;
        if (override != null && !override.all)
            tag = HologramBlueprintNbt.filterSelection(block, tag, override.keys, covers);
        else if (override == null) {
            tag = HologramBlueprintNbt
                .filterSelection(block, tag, retainNbt ? retainedKeys : java.util.Collections.emptyList(), covers);
            for (String key : policy.ignoredNbtKeys)
                if (!HologramBlueprintNbt.protectedKey(block, tag, key)) tag.removeTag(key);
        } else tag = HologramBlueprintNbt.filterSelection(block, tag, null, covers);
        for (String path : override == null ? policy.ignoredNbtPaths : override.ignoredPaths)
            if (path.startsWith(kind + ":")) removePath(tag, path.substring(kind.length() + 1), block);
        HologramBlueprintNbt.normalizeResourceSelection(tag);
        if (block == gregtech.api.GregTechAPI.sBlockMachines) {
            HologramBlueprintNbt.normalizeTransportItems(tag);
            tag = HologramGtMeShadow.project(tag);
        }
        if (before.hasKey(HologramFullPreservation.TAG)) HologramFullPreservation.assertUnchanged(before, tag);
        return reviews
            .select(cell, kind, before.equals(tag) ? encoded : HologramBlueprintNbt.encodeCanonical(tag), tag);
    }

    static void removeNbtPath(HologramPresets.Preset preset, List<Integer> indices, String path) throws IOException {
        checkPath(path);
        HologramAnalysisFacts facts = new HologramAnalysisFacts();
        List<String> staged = new ArrayList<>();
        boolean tile = path.startsWith("tile:"), changed = false;
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= preset.cells.size()) throw new IOException("方块NBT编辑位置无效");
            HologramPresets.Cell cell = preset.cells.get(index);
            String original = tile ? cell.tileNbt : cell.itemNbt;
            NBTTagCompound before = facts.read(original);
            if (before == null) {
                staged.add(original);
                continue;
            }
            NBTTagCompound tag = (NBTTagCompound) before.copy();
            removePath(tag, path.substring(path.indexOf(':') + 1), cell.block());
            HologramBlueprintNbt.normalizeResourceSelection(tag);
            boolean dirty = !before.equals(tag);
            changed |= dirty;
            staged.add(dirty ? HologramBlueprintNbt.encodeCanonical(tag) : original);
        }
        if (!changed) return;
        invalidate(preset, "nbt");
        for (int i = 0; i < indices.size(); i++) {
            if (tile) preset.cells.get(indices.get(i)).tileNbt = staged.get(i);
            else preset.cells.get(indices.get(i)).itemNbt = staged.get(i);
        }
    }

    /** A value editor has no additive asset capability: only registered configuration fields may be changed. */
    static void editNbtValue(HologramPresets.Preset preset, int[] indices, String path, String type, String value)
        throws IOException {
        checkPath(path);
        if (indices == null || indices.length == 0 || indices.length > 8192) throw new IOException("请选择需要编辑NBT的方块");
        if (value == null || value.length() > 4096) throw new IOException("NBT值过长");
        List<String> staged = new ArrayList<>();
        HologramAnalysisFacts facts = new HologramAnalysisFacts();
        boolean changed = false;
        boolean tile = path.startsWith("tile:");
        for (int index : indices) {
            if (index < 0 || index >= preset.cells.size()) throw new IOException("方块NBT编辑位置无效");
            HologramPresets.Cell cell = preset.cells.get(index);
            String encoded = tile ? cell.tileNbt : cell.itemNbt;
            NBTTagCompound tag = facts.copyForEdit(encoded);
            if (tag == null) throw new IOException("所选方块没有该NBT数据");
            String[] segments = path.substring(path.indexOf(':') + 2)
                .split("/", -1);
            // Nested opaque data, physical ItemStacks, recipes and relationships never acquire a generic setter.
            if (segments.length != 1 || !editableConfig(cell, tag, unescape(segments[0])))
                throw new IOException("该条目属于系统身份、关系、真实资产或未审阅数据，只能明确忽略，不能改写数值");
            String key = unescape(segments[0]);
            NBTBase original = tag.getTag(key);
            if (original == null || !type(original).equals(type)) throw new IOException("NBT值必须保持已有条目的原始类型");
            NBTBase replacement = parseValue(type, value);
            boolean dirty = !original.equals(replacement);
            changed |= dirty;
            if (dirty) tag.setTag(key, replacement);
            staged.add(dirty ? HologramBlueprintNbt.encodeCanonical(tag) : encoded);
        }
        if (!changed) return;
        invalidate(preset, "nbt");
        for (int i = 0; i < indices.length; i++) {
            if (tile) preset.cells.get(indices[i]).tileNbt = staged.get(i);
            else preset.cells.get(indices[i]).itemNbt = staged.get(i);
        }
    }

    static NBTTagList nbtEntries(HologramPresets.Cell cell) throws IOException {
        return nbtEntries(cell, new HologramAnalysisFacts());
    }

    static NBTTagList nbtEntries(HologramPresets.Cell cell, HologramAnalysisFacts facts) throws IOException {
        NBTTagCompound certificate = facts.certificate(
            cell,
            HologramSegmentContract.Domain.REPORT,
            "entries",
            new Object[] { cell.block(), cell.tileNbt, cell.itemNbt, HologramSegmentContract.SCHEMA_EPOCH },
            true,
            () -> {
                NBTTagList entries = new NBTTagList();
                EntrySink sink = new EntrySink() {

                    void accept(NBTTagCompound row) {
                        entries.appendTag(row);
                    }
                };
                entryRows(cell, facts.read(cell.tileNbt), "tile:", sink, 0, null);
                entryRows(cell, facts.read(cell.itemNbt), "item:", sink, 0, null);
                NBTTagCompound result = new NBTTagCompound();
                result.setTag("rows", entries);
                return result;
            });
        return certificate.getTagList("rows", 10);
    }

    /** Stream the requested tail directly; details never retain the full 2048-row certificate. */
    static NBTTagCompound nbtEntryPage(HologramPresets.Cell cell, HologramAnalysisFacts facts, int offset, int limit,
        long byteLimit, Set<String> selected) throws IOException {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        EntrySink sink = new EntrySink() {

            long bytes;
            boolean full;

            void accept(NBTTagCompound row) {
                if (seen <= offset || rows.tagCount() >= limit || full) return;
                String key = row.getString("key");
                row.setString("label", key);
                row.setBoolean("selected", selected == null || selected.contains(key));
                row.setBoolean("blueprintSelected", selected == null || selected.contains(key));
                long cost = HologramSnapshotNbt.estimate(row);
                if (cost > byteLimit - bytes) {
                    full = true;
                    if (rows.tagCount() == 0) result.setString("detailError", "rowTooLarge");
                    return;
                }
                rows.appendTag(row);
                bytes += cost;
            }
        };
        entryRows(cell, facts.read(cell.tileNbt), "tile:", sink, 0, null);
        entryRows(cell, facts.read(cell.itemNbt), "item:", sink, 0, null);
        result.setTag("rows", rows);
        result.setInteger("total", sink.seen);
        if (offset < 0 || offset > sink.seen) result.setString("detailError", "offset");
        return result;
    }

    private abstract static class EntrySink {

        int seen;

        final void add(NBTTagCompound row) {
            seen++;
            accept(row);
        }

        abstract void accept(NBTTagCompound row);
    }

    private static void entryRows(HologramPresets.Cell cell, NBTTagCompound tag, String prefix, EntrySink entries,
        int depth, NBTTagCompound device) {
        if (tag == null || depth > 8 || entries.seen >= 2048) return;
        if (device == null) device = tag;
        for (Object raw : tag.func_150296_c()) {
            String key = (String) raw;
            NBTBase value = tag.getTag(key);
            NBTTagCompound row = new NBTTagCompound();
            String path = prefix + "/"
                + key.replace("~", "~0")
                    .replace("/", "~1");
            boolean protectedField = depth == 0 && HologramBlueprintNbt.protectedKey(cell.block(), device, key);
            boolean editable = depth == 0 && editableConfig(cell, device, key)
                && value.getId() >= 1
                && value.getId() <= 8
                && value.getId() != 7;
            row.setString("key", key);
            row.setString("path", path);
            row.setString("type", type(value));
            String rendered = scalar(value);
            row.setString("value", rendered.length() > 4096 ? rendered.substring(0, 4096) : rendered);
            row.setBoolean("editable", editable);
            row.setBoolean("protected", protectedField);
            row.setString("category", category(cell, device, key, depth, editable, protectedField));
            row.setString(
                "detail",
                depth == 0 && "cbOwner".equals(key) && HologramDeidentification.carpenterOwner(cell.block(), device)
                    ? "Carpenter 来源所有者仅作记录；新放置方块自动归当前玩家，无需为此去标识"
                    : protectedField ? "系统身份由服务器维护"
                        : editable ? "已审阅的配置项；修改后须重新完成NBT核验" : "保留原数据；此项可能含真实资产或未审阅格式，仅支持明确忽略");
            entries.add(row);
            if (value instanceof NBTTagCompound && !protectedField)
                entryRows(cell, (NBTTagCompound) value, path, entries, depth + 1, device);
            // Lists are described as a whole. Inventory and recipe lists are never recursively made editable.
            if (entries.seen >= 2048) return;
        }
    }

    private static boolean editableConfig(HologramPresets.Cell cell, NBTTagCompound tag, String key) {
        if (HologramBlueprintNbt.protectedKey(key)) return false;
        boolean registered = cell.block() != null && cell.block() == gregtech.api.GregTechAPI.sBlockMachines
            || tag.getString("id")
                .startsWith("gtswn.");
        return registered && ("currentTab".equals(key) || "mActive".equals(key) || "mWasShutdown".equals(key));
    }

    private static String category(HologramPresets.Cell cell, NBTTagCompound tag, String key, int depth,
        boolean editable, boolean protectedField) {
        if (protectedField) return key.toLowerCase(java.util.Locale.ROOT)
            .contains("owner") ? "identity" : "system";
        if (editable) return "configuration";
        if (depth > 0) return "opaque";
        String id = tag.getString("id");
        boolean nc = id.equals("IC2NCInfoPanel") || id.equals("IC2NCAdvancedInfoPanel")
            || id.equals("IC2NCInfoPanelExtender")
            || id.equals("IC2NCAdvancedInfoPanelExtender");
        if (nc && java.util.Arrays.asList("coreX", "coreY", "coreZ", "partOfScreen", "screenData")
            .contains(key)) return "relation";
        if (nc && "Items".equals(key)) return "asset";
        if ((id.equals("TileJar") || id.equals("TileJarVoid")) && ("Aspect".equals(key) || "Amount".equals(key)))
            return "asset";
        if ((id.equals("TileAlchemyFurnace") || id.equals("TileAlchemyFurnaceAdvanced") || id.equals("TileTubeBuffer"))
            && "Aspects".equals(key)) return "asset";
        if (id.equals("TileSiphon") && "amount".equals(key)) return "asset";
        if (cell.block() != null && cell.block() == gregtech.api.GregTechAPI.sBlockMachines
            && (java.util.Arrays.asList("mInventory", "Inventory", "inventory", "Items", "mFluid", "mStoredEnergy")
                .contains(key) || key.matches("mFluid[0-9]+")))
            return "asset";
        return "opaque";
    }

    private static String type(NBTBase tag) {
        String[] types = { "end", "byte", "short", "int", "long", "float", "double", "byte_array", "string", "list",
            "compound", "int_array" };
        return tag.getId() >= 0 && tag.getId() < types.length ? types[tag.getId()] : "unknown";
    }

    private static String scalar(NBTBase tag) {
        if (tag instanceof NBTTagString) return ((NBTTagString) tag).func_150285_a_();
        if (tag instanceof NBTBase.NBTPrimitive) {
            NBTBase.NBTPrimitive number = (NBTBase.NBTPrimitive) tag;
            if (tag.getId() == 5 || tag.getId() == 6) return Double.toString(number.func_150286_g());
            return Long.toString(number.func_150291_c());
        }
        return tag.toString();
    }

    private static NBTBase parseValue(String type, String value) throws IOException {
        try {
            switch (type) {
                case "byte":
                    return new NBTTagByte(Byte.parseByte(value));
                case "short":
                    return new NBTTagShort(Short.parseShort(value));
                case "int":
                    return new NBTTagInt(Integer.parseInt(value));
                case "long":
                    return new NBTTagLong(Long.parseLong(value));
                case "float": {
                    float parsed = Float.parseFloat(value);
                    if (!Float.isFinite(parsed)) throw new NumberFormatException();
                    return new NBTTagFloat(parsed);
                }
                case "double": {
                    double parsed = Double.parseDouble(value);
                    if (!Double.isFinite(parsed)) throw new NumberFormatException();
                    return new NBTTagDouble(parsed);
                }
                case "string":
                    return new NBTTagString(value);
                default:
                    throw new IOException("该NBT类型不能作为配置值编辑");
            }
        } catch (NumberFormatException invalid) {
            throw new IOException("NBT值不符合原类型或超出范围", invalid);
        }
    }

    private static void removePath(NBTTagCompound root, String path, net.minecraft.block.Block block)
        throws IOException {
        String alias = HologramAeBlueprint.inventoryAliasPath(block, path);
        removePathValue(root, path, block);
        if (alias != null) removePathValue(root, alias, block);
    }

    private static void removePathValue(NBTTagCompound root, String path, net.minecraft.block.Block block)
        throws IOException {
        String[] segments = path.substring(1)
            .split("/", -1);
        NBTBase parent = root;
        for (int i = 0; i < segments.length; i++) {
            String key = unescape(segments[i]);
            if (i == 0 && HologramBlueprintNbt.protectedKey(block, root, key)) return;
            if (parent instanceof NBTTagCompound) {
                NBTTagCompound compound = (NBTTagCompound) parent;
                if (i == segments.length - 1) compound.removeTag(key);
                else {
                    parent = compound.getTag(key);
                    if (parent == null) return;
                }
            } else if (parent instanceof NBTTagList) {
                NBTTagList list = (NBTTagList) parent;
                int index;
                try {
                    index = Integer.parseInt(key);
                } catch (NumberFormatException invalid) {
                    throw new IOException("NBT列表路径必须使用数字索引", invalid);
                }
                if (index < 0 || index >= list.tagCount()) return;
                if (i == segments.length - 1) list.removeTag(index);
                else {
                    if (list.func_150303_d() != 10) throw new IOException("NBT列表子路径只支持复合条目");
                    parent = list.getCompoundTagAt(index);
                }
            } else {
                return;
            }
        }
    }

    private static void collect(String encoded, String kind, Set<String> keys, Set<String> paths) throws IOException {
        collect(HologramBlueprintNbt.decode(encoded), kind, keys, paths);
    }

    private static void collect(NBTTagCompound tag, String kind, Set<String> keys, Set<String> paths) {
        if (tag == null) return;
        if (keys.size() > 256 && paths.size() >= 4096) return;
        for (Object raw : tag.func_150296_c()) {
            String key = (String) raw;
            if (!HologramBlueprintNbt.protectedKey(tag, key)) keys.add(key);
        }
        collectPaths(tag, kind, paths, 0);
    }

    private static void collectPaths(NBTTagCompound tag, String prefix, Set<String> paths, int depth) {
        if (depth > 16 || paths.size() >= 4096) return;
        for (Object raw : tag.func_150296_c()) {
            String key = (String) raw;
            if (depth == 0 && HologramBlueprintNbt.protectedKey(tag, key)) continue;
            String path = prefix + "/"
                + key.replace("~", "~0")
                    .replace("/", "~1");
            paths.add(path);
            NBTBase value = tag.getTag(key);
            if (value instanceof NBTTagCompound) collectPaths((NBTTagCompound) value, path, paths, depth + 1);
            if (paths.size() >= 4096) return;
        }
    }

    private static void clearQuantum(HologramPresets.Preset preset) throws IOException {
        // Removing every logical slot also retires the review of that exact named cell map.
        // The completed processing receipt still authorizes explicit unbound placement; choosing a
        // binding policy in a later author pass requires confirming a new classification for this blueprint.
        preset.quantumGroupingNbt = null;
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null || !tag.hasKey(HologramNetworkBindings.GROUP)) continue;
            tag.removeTag(HologramNetworkBindings.GROUP);
            tag.removeTag(HologramNetworkBindings.ROLE);
            tag.removeTag(HologramNetworkBindings.HINT);
            tag.removeTag(HologramNetworkBindings.CAPTURE_ANCHOR);
            tag.removeTag(HologramNetworkBindings.ORIGINAL);
            tag.removeTag(HologramNetworkBindings.MODE);
            if (HologramNetworkBindings.node(cell.block())) {
                for (String key : new String[] { "anchorDim", "anchorX", "anchorY", "anchorZ", "proxy", "linked" })
                    tag.removeTag(key);
            }
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(tag);
        }
    }

    private static NBTTagCompound addHints(HologramPresets.Preset source, HologramPresets.Preset preset,
        List<HologramCoordinatePolicies.Relation> relations, Policy policy, HologramAnalysisFacts facts)
        throws IOException {
        Set<String> blocks = new LinkedHashSet<>();
        NBTTagCompound generated = new NBTTagCompound();
        if ("none".equals(policy.autoHints)) {
            generated.setTag("blocks", strings(new ArrayList<>()));
            return generated;
        }
        boolean full = "full".equals(policy.autoHints);
        if ("full".equals(policy.autoHints)) {
            String hint = "作者处理：去标识等级 " + policy.deidentificationLevel
                + "；量子关系"
                + ("clear".equals(policy.quantum) ? "清除后重新配置"
                    : "preserve".equals(policy.quantum) ? "保持原存档的原网络绑定" : "放置时用自己的终端重新绑定")
                + "；未明确忽略的未适配数据保留原样";
            preset.prompt = appendGenerated(preset.prompt, java.util.Collections.singletonList(hint), blocks);
        }
        Map<String, List<String>> cellHints = new LinkedHashMap<>();
        List<String> previousBlocks = generatedBlocks(source);
        for (int index = 0; index < preset.cells.size(); index++) {
            HologramPresets.Cell cell = preset.cells.get(index), original = source.cells.get(index);
            List<String> hints = new ArrayList<>();
            cellHints.put(position(cell.x, cell.y, cell.z), hints);
            NBTTagCompound payload = facts.read(cell.tileNbt);
            NBTTagCompound before = facts.read(original.tileNbt);
            Map<Integer, List<String>> changes = new java.util.TreeMap<>();
            mergeFields(changes, HologramDeidentification.changedFields(original, before, payload, "tile:"));
            mergeFields(
                changes,
                HologramDeidentification
                    .changedFields(original, facts.read(original.itemNbt), facts.read(cell.itemNbt), "item:"));
            for (Map.Entry<Integer, List<String>> entry : changes.entrySet())
                if (full) hints.add(fieldHint(entry.getKey(), entry.getValue()));
                else if (entry.getKey() == 1) hints.add("设备所有者标识已清空；放置时按当前玩家身份重新建立，确认当前账户和权限");
                else if (entry.getKey() >= 3 && entry.getKey() <= 6)
                    hints.add("设备的网络、安全或显示屏连接设置已清除；放置后按已确认的关系策略重新连接并检查权限");
                else if (entry.getKey() == 2 && entry.getValue()
                    .stream()
                    .anyMatch(path -> !path.matches(".*/[xyz]"))) hints.add("设备的外部锚定设置已清除；放置后重新配置目标，根位置会使用新的放置坐标");
            // Persisted sources cannot recover removed identities. Keep their verified descriptions without raw values.
            if (original.prompt != null && !original.prompt.isEmpty())
                for (String oldBlock : previousBlocks) if (original.prompt.contains(oldBlock)) {
                    for (String line : oldBlock.split("\n")) {
                        for (int group = 1; group <= 10; group++)
                            if (line.startsWith(fieldPrefix(group)) && HologramDeidentification.level(source) >= group
                                && !changes.containsKey(group)) {
                                    if (full && !hints.contains(line)) hints.add(line);
                                    else if (!full && group == 1 && !changes.containsKey(1))
                                        hints.add("设备所有者标识已清空；放置时按当前玩家身份重新建立，确认当前账户和权限");
                                }
                    }
                }
            Map<Integer, List<String>> retained = HologramDeidentification.retainedRiskFields(cell, payload, "tile:");
            mergeFields(retained, HologramDeidentification.retainedRiskFields(cell, facts.read(cell.itemNbt), "item:"));
            for (Map.Entry<Integer, List<String>> entry : retained.entrySet()) {
                String fields = full ? "（" + String.join("、", entry.getValue()) + "）" : "";
                if (entry.getKey() == 1) hints.add("模板保留原所有者字段" + fields + "；放置前确认当前玩家有权使用该设备");
                else if (entry.getKey() >= 2 && entry.getKey() <= 6)
                    hints.add("模板保留原锚定、网络或安全设置" + fields + "；移动蓝图不会自动修复这些外部设置，放置后检查目标与权限");
            }
            if (payload != null && payload.hasKey(HologramNetworkBindings.GROUP)) hints.add(
                "量子网络组 " + payload.getString(HologramNetworkBindings.GROUP)
                    + ("preserve".equals(policy.quantum) ? "：保持原存档的原量子网络绑定；原存档或原网络不可用时无法施工，无需选择终端"
                        : "：放置前选择当前玩家自己的有效终端，按该终端重新绑定"));
            else if ("clear".equals(policy.quantum) && (HologramNetworkBindings.node(cell.block())
                || before != null && (before.hasKey(HologramNetworkBindings.GROUP)
                    || before.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR))))
                hints.add("量子网络绑定已清除；以未绑定状态放置，需要使用自己的有效终端重新连接");
        }
        for (HologramCoordinatePolicies.Relation relation : relations) {
            String mode = HologramCoordinatePolicies.mode(relation, policy);
            if (!full && "relative".equals(mode) && relation.internal) continue;
            List<String> hints = cellHints.get(position(relation.x, relation.y, relation.z));
            if (hints == null) continue;
            hints
                .add(
                    relation.label + ("clear".equals(mode)
                        ? "：连接已清除，放置后重新设置" + ("nc.sensor".equals(relation.adapter) ? "；传感卡的绑定、监测缓存和标题也会重置" : "")
                        : "relative".equals(mode)
                            ? "：随蓝图平移和旋转校准，使用放置所在维度" + (relation.internal ? "，指向本蓝图实际选中的方块" : "；目标在蓝图外，放置后确认对应位置仍有有效设备")
                            : HologramNativeHubData.adapter(relation.adapter)
                                ? "：仍保留原维度和绝对坐标；移动和旋转蓝图不会改变此绑定，放置后检查原设备是否可用"
                                : "：仍锚定原绝对坐标；移动和旋转蓝图不会改变此关系，请检查原目标在放置所在维度是否可用"));
        }
        for (HologramPresets.Cell cell : preset.cells)
            cell.prompt = appendGenerated(cell.prompt, cellHints.get(position(cell.x, cell.y, cell.z)), blocks);
        generated.setTag("blocks", strings(new ArrayList<>(blocks)));
        return generated;
    }

    private static void mergeFields(Map<Integer, List<String>> target, Map<Integer, List<String>> incoming) {
        for (Map.Entry<Integer, List<String>> entry : incoming.entrySet()) {
            Set<String> paths = new java.util.TreeSet<>(
                target.getOrDefault(entry.getKey(), java.util.Collections.emptyList()));
            paths.addAll(entry.getValue());
            target.put(entry.getKey(), new ArrayList<>(paths));
        }
    }

    private static String fieldPrefix(int group) {
        String[] prefixes = { "", "已清除设备所有者标识", "已处理位置与锚定字段", "已移除频道与网络标识", "已移除安全身份", "已移除外部连接", "已处理显示屏连接字段",
            "已移除监测缓存", "已移除显示页设置", "已移除停机记录", "已移除运行状态与采样字段" };
        return prefixes[group];
    }

    private static String fieldHint(int group, List<String> fields) {
        String[] guidance = { "", "放置时按当前玩家身份重建空身份", "根位置使用目标坐标，外部锚定需重新核验", "放置后重新选择网络或频道", "放置后重新授权", "放置后重新连接设备",
            "按已确认坐标策略重建；已清除的连接需重新设置", "放置后重新采集有效目标的数据", "放置后重新选择显示页", "原停机原因不再保留，请重新检查运行条件", "放置后由真实设备重新产生运行状态" };
        return fieldPrefix(group) + "（" + String.join("、", fields) + "）；" + guidance[group];
    }

    private static String appendGenerated(String original, List<String> lines, Set<String> generated)
        throws IOException {
        if (lines == null || lines.isEmpty()) return original;
        Set<String> unique = new LinkedHashSet<>();
        for (String line : lines) if (original == null || !original.contains(line)) unique.add(line);
        if (unique.isEmpty()) return original;
        String block = "自动说明：\n" + String.join("\n", unique);
        String result = original == null || original.isEmpty() ? block : original + "\n" + block;
        if (result.length() > 4096) throw new IOException("自动说明与原有提示合计超过4096字，请缩短原提示或调整自动提示模式");
        generated.add(block);
        return result;
    }

    private static List<String> generatedBlocks(HologramPresets.Preset preset) throws IOException {
        NBTTagCompound receipt = HologramBlueprintNbt.decode(preset.processingNbt);
        if (receipt == null || !receipt.hasKey("generatedHints", 10)) return java.util.Collections.emptyList();
        NBTTagCompound hints = receipt.getCompoundTag("generatedHints");
        NBTBase raw = hints.getTag("blocks");
        if (!(raw instanceof NBTTagList)) throw new IOException("自动提示记录格式无效");
        NBTTagList list = (NBTTagList) raw;
        if (list.tagCount() > 65536 || list.tagCount() > 0 && list.func_150303_d() != 8)
            throw new IOException("自动提示记录类型或数量无效");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.tagCount(); i++) {
            String block = list.getStringTagAt(i);
            if (!block.startsWith("自动说明：\n") || block.length() > 4096) throw new IOException("自动提示记录文本无效");
            result.add(block);
        }
        result.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return result;
    }

    private static void removeGeneratedHints(HologramPresets.Preset preset) throws IOException {
        List<String> blocks = generatedBlocks(preset);
        if (blocks.isEmpty()) return;
        preset.prompt = removeGenerated(preset.prompt, blocks);
        for (HologramPresets.Cell cell : preset.cells) cell.prompt = removeGenerated(cell.prompt, blocks);
    }

    private static String removeGenerated(String original, List<String> blocks) {
        if (original == null || original.isEmpty() || blocks.isEmpty()) return original;
        String result = original;
        for (String block : blocks) {
            int index = result.indexOf(block);
            while (index >= 0) {
                int end = index + block.length();
                if ((index == 0 || result.charAt(index - 1) == '\n')
                    && (end == result.length() || result.charAt(end) == '\n')) {
                    int start = index > 0 ? index - 1 : index;
                    if (start == 0 && end < result.length()) end++;
                    result = result.substring(0, start) + result.substring(end);
                    index = result.indexOf(block);
                } else index = result.indexOf(block, index + 1);
            }
        }
        return result;
    }

    static NBTTagList strings(List<String> values) {
        NBTTagList result = new NBTTagList();
        for (String value : values) result.appendTag(new NBTTagString(value));
        return result;
    }

    static List<String> readStrings(NBTTagCompound tag, String key, int limit, int length) throws IOException {
        if (!tag.hasKey(key)) return new ArrayList<>();
        NBTBase raw = tag.getTag(key);
        if (!(raw instanceof NBTTagList)) throw new IOException("处理流程列表格式无效：" + key);
        NBTTagList list = (NBTTagList) raw;
        if (list.tagCount() > limit || list.tagCount() > 0 && list.func_150303_d() != 8)
            throw new IOException("处理流程列表超出上限或类型无效：" + key);
        List<String> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < list.tagCount(); i++) {
            String value = text(list.getStringTagAt(i), length);
            if (!unique.add(value)) throw new IOException("处理流程列表存在重复项：" + key);
            result.add(value);
        }
        return result;
    }

    private static NBTTagList compounds(NBTTagCompound tag, String key, int limit) throws IOException {
        if (!tag.hasKey(key)) return new NBTTagList();
        NBTBase raw = tag.getTag(key);
        if (!(raw instanceof NBTTagList)) throw new IOException("处理流程列表格式无效：" + key);
        NBTTagList list = (NBTTagList) raw;
        if (list.tagCount() > limit || list.tagCount() > 0 && list.func_150303_d() != 10)
            throw new IOException("处理流程列表类型或数量无效：" + key);
        return list;
    }

    private static String choice(NBTTagCompound tag, String key, String... choices) throws IOException {
        if (!tag.hasKey(key, 8) || !Arrays.asList(choices)
            .contains(tag.getString(key))) throw new IOException("请明确选择处理选项：" + key);
        return tag.getString(key);
    }

    private static String text(String value, int limit) throws IOException {
        if (value == null || value.length() > limit
            || value.chars()
                .anyMatch(c -> c < 32 || c == 167))
            throw new IOException("处理流程文本无效或过长");
        return value;
    }

    private static void checkPath(String path) throws IOException {
        text(path, 1024);
        if (!(path.startsWith("tile:/") || path.startsWith("item:/")))
            throw new IOException("NBT路径须以 tile:/ 或 item:/ 开头");
        for (String key : path.substring(path.indexOf(':') + 2)
            .split("/", -1)) unescape(key);
    }

    private static String unescape(String key) throws IOException {
        if (key.matches(".*~(?![01]).*")) throw new IOException("NBT路径转义无效");
        return key.replace("~1", "/")
            .replace("~0", "~");
    }

    private static String position(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
