package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.IHatchElement;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Coordinate-free hatch combinations. Only server declaration candidates can become planned targets. */
final class HologramHatchProfiles {

    // Bound this synchronous optional shortcut independently of the resumable structure capture.
    static final int MAX_CANDIDATE_EVALUATIONS = 1_000_000;
    static final int MAX_GROUP_SLOT_EDGES = 131_072;
    static final int MAX_EDGE_SCANS = 32_000_000;

    private HologramHatchProfiles() {}

    static String role(HologramSession s, HologramCapture.Cell cell, ItemStack stack) {
        if (stack == null || gregtech.common.blocks.ItemMachines.getMetaTileEntity(stack) == null) return "";
        for (com.gtnewhorizon.structurelib.structure.IStructureElement<?> part : HologramElementCatalog
            .parts(cell.element))
            for (IHatchElement<?> type : HologramHatchPolicy.declared(part)) if (HologramHatchPolicy
                .matchesHatch(type, gregtech.common.blocks.ItemMachines.getMetaTileEntity(stack))) return type.name();
        return "";
    }

    static NBTTagCompound capture(HologramSession s) {
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("tier", s.hatchTier);
        result.setBoolean("downgrade", s.hatchDowngrade);
        NBTTagList entries = new NBTTagList();
        result.setTag("entries", entries);
        if (s.virtual || s.capture == null || s.capture.incomplete || s.capturePending) return result;
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (cell.anchor || cell.userPosition || !HologramSessionPolicy.editableStructureCell(s, cell)) continue;
            ItemStack stack = HologramCellPolicy.targetFor(s, cell, i);
            if (stack == null || HologramCellPolicy.hatchTier(stack) == Integer.MAX_VALUE) continue;
            String role = role(s, cell, stack);
            if (role.isEmpty()) continue;
            NBTTagCompound found = null;
            for (int j = 0; j < entries.tagCount(); j++) {
                NBTTagCompound entry = entries.getCompoundTagAt(j);
                if (role.equals(entry.getString("role")) && same(stack, stack(entry))) found = entry;
            }
            if (found != null) found.setInteger("count", found.getInteger("count") + 1);
            else {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setString("role", role);
                entry.setString("item", String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())));
                entry.setInteger("damage", stack.getItemDamage());
                if (stack.hasTagCompound()) entry.setTag(
                    "tag",
                    stack.getTagCompound()
                        .copy());
                entry.setInteger("count", 1);
                entries.appendTag(entry);
            }
        }
        return result;
    }

    static ItemStack stack(NBTTagCompound entry) {
        Object item = Item.itemRegistry.getObject(entry.getString("item"));
        if (!(item instanceof Item)) return null;
        ItemStack result = new ItemStack((Item) item, 1, entry.getInteger("damage"));
        if (entry.hasKey("tag", 10)) result.setTagCompound(
            (NBTTagCompound) entry.getCompoundTag("tag")
                .copy());
        return result;
    }

    static boolean same(ItemStack first, ItemStack second) {
        return first != null && second != null
            && first.isItemEqual(second)
            && ItemStack.areItemStackTagsEqual(first, second);
    }

    /** Validates the entire combination before the caller mutates any configuration or target. */
    static Map<Integer, ItemStack> plan(HologramSession s, NBTTagCompound profile) throws IOException {
        try {
            return planBounded(s, profile, new Budget());
        } catch (WorkLimitException limit) {
            throw new IOException("仓室搭配计算超出安全工作预算（" + limit.kind.label + "）；请减少仓室数量或拆分搭配，原规划未修改", limit);
        }
    }

    private static Map<Integer, ItemStack> planBounded(HologramSession s, NBTTagCompound profile, Budget budget)
        throws IOException {
        if (s.virtual || s.mode == 2 || s.capture == null || s.capture.incomplete || s.capturePending)
            throw new IOException("请在完整实体结构的建造或替换模式使用仓室搭配");
        NBTTagList entries = profile.getTagList("entries", 10);
        if (entries.tagCount() == 0 || entries.tagCount() > 128) throw new IOException("仓室搭配为空或超出上限");
        List<NBTTagCompound> units = new ArrayList<>();
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            int count = entry.getInteger("count");
            ItemStack sample = stack(entry);
            if (entry.getString("role")
                .isEmpty()
                || entry.getString("role")
                    .length() > 256
                || count < 1
                || count > 512
                || units.size() + count > 512
                || sample == null
                || HologramCellPolicy.hatchTier(sample) == Integer.MAX_VALUE
                || HologramRecovery.hasSeal(sample)) throw new IOException("仓室搭配含无效角色或机器身份");
            for (int n = 0; n < count; n++) units.add(entry);
        }
        // Explicit user pins take precedence, followed by installed devices, just like the basic preset.
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < s.capture.cells.size(); i++) {
            budget.evaluate();
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (cell.anchor || cell.userPosition || !HologramSessionPolicy.editableStructureCell(s, cell)) continue;
            TileEntity actual = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
            ItemStack fixed = s.pins.get(i);
            if (fixed == null && actual instanceof IGregTechTileEntity)
                fixed = new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) actual).getMetaTileID());
            if (fixed != null) {
                if (HologramCellPolicy.hatchTier(fixed) == Integer.MAX_VALUE) continue;
                String role = role(s, cell, fixed);
                if (role.isEmpty()) throw new IOException("手选或现场仓室没有可匹配的结构声明角色；规划未修改");
                if (HologramCellPolicy.hatchElement(s, cell, fixed, true) == null)
                    throw new IOException("手选或现场仓室不符合当前格的声明限制；规划未修改");
                int match = -1;
                for (int n = 0; n < units.size(); n++) {
                    budget.evaluate();
                    if (role.equals(
                        units.get(n)
                            .getString("role"))
                        && same(fixed, stack(units.get(n)))) {
                        match = n;
                        break;
                    }
                }
                if (match < 0) throw new IOException("手选或现场仓室与该搭配不匹配；请调整搭配，手选目标保持不变");
                units.remove(match);
            } else if (actual == null && !s.pins.containsKey(i)
                && (HologramCellPolicy.trivial(s.player.worldObj, cell.x, cell.y, cell.z)
                    || s.mode == 1 && HologramCellPolicy.destructive(
                        cell,
                        s.player.worldObj.getBlock(cell.x, cell.y, cell.z),
                        s.player.worldObj.getBlockMetadata(cell.x, cell.y, cell.z))))
                positions.add(i);
        }
        List<List<Integer>> choices = new ArrayList<>();
        // Count expands the same entry object. Its legal positions are evaluated once, not once per machine.
        Map<NBTTagCompound, List<Integer>> entryChoices = new IdentityHashMap<>();
        for (NBTTagCompound unit : units) {
            List<Integer> legal = entryChoices.get(unit);
            if (legal == null) {
                legal = new ArrayList<>();
                ItemStack sample = stack(unit);
                for (int p = 0; p < positions.size(); p++) {
                    budget.evaluate();
                    HologramCapture.Cell cell = s.capture.cells.get(positions.get(p));
                    if (!unit.getString("role")
                        .equals(role(s, cell, sample))) continue;
                    for (ItemStack candidate : HologramCellPolicy.candidates(s, cell)) {
                        budget.evaluate();
                        if (same(candidate, sample)) {
                            legal.add(p);
                            break;
                        }
                    }
                }
                entryChoices.put(unit, legal);
            }
            choices.add(legal);
        }
        int[] casingCosts = new int[positions.size()];
        for (int p = 0; p < positions.size(); p++) {
            HologramCapture.Cell cell = s.capture.cells.get(positions.get(p));
            casingCosts[p] = HologramIndustrialConstraints
                .casing(cell, HologramBlockIdentity.materialStack(cell.block, cell.meta)) ? 1 : 0;
        }
        int[] owners = assign(choices, positions.size(), casingCosts, budget);
        if (owners == null) throw new IOException("此结构没有足够的声明候选位置容纳该仓室搭配；规划未修改");
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        for (int p = 0; p < owners.length; p++)
            if (owners[p] >= 0) result.put(positions.get(p), stack(units.get(owners[p])));
        Map<IHatchElement<?>, Integer> quotas = new LinkedHashMap<>();
        for (HologramCapture.Cell cell : s.capture.cells)
            if (HologramSessionPolicy.editableStructureCell(s, cell)) HologramCellPolicy.hatchRequirements(s, cell)
                .forEach((type, count) -> quotas.merge(type, count, Math::max));
        int casings = 0;
        for (int i = 0; i < s.capture.cells.size(); i++) {
            HologramCapture.Cell cell = s.capture.cells.get(i);
            if (!HologramSessionPolicy.editableStructureCell(s, cell)) continue;
            ItemStack target = result.get(i);
            if (target == null) {
                TileEntity actual = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
                if (s.pins.containsKey(i)) target = s.pins.get(i);
                else if (actual instanceof IGregTechTileEntity) target = new ItemStack(
                    GregTechAPI.sBlockMachines,
                    1,
                    ((IGregTechTileEntity) actual).getMetaTileID());
                else target = HologramBlockIdentity.materialStack(cell.block, cell.meta);
            }
            if (HologramIndustrialConstraints.casing(cell, target)) casings++;
            if (cell.anchor || target == null) continue;
            gregtech.api.interfaces.metatileentity.IMetaTileEntity tile = gregtech.common.blocks.ItemMachines
                .getMetaTileEntity(target);
            if (tile != null) for (IHatchElement<?> type : new ArrayList<>(quotas.keySet()))
                if (HologramHatchPolicy.matchesHatch(type, tile)) quotas.put(type, quotas.get(type) - 1);
        }
        if (quotas.values()
            .stream()
            .anyMatch(count -> count > 0) || casings < HologramIndustrialConstraints.minimumCasings(s.context))
            throw new IOException("此搭配未满足当前结构的仓室配额或最低壳体数量；规划未修改");
        return result;
    }

    /** Every required machine must fit; consume the fewest casing slots among complete legal matchings. */
    static int[] assign(List<List<Integer>> choices, int slots) {
        return assign(choices, slots, new int[slots]);
    }

    static int[] assign(List<List<Integer>> choices, int slots, int[] casingCosts) {
        return assign(choices, slots, casingCosts, new Budget());
    }

    static int[] assign(List<List<Integer>> choices, int slots, int[] casingCosts, Budget budget) {
        Map<List<Integer>, Group> equalGroups = new LinkedHashMap<>();
        Map<List<Integer>, Group> identityGroups = new IdentityHashMap<>();
        for (int unit = 0; unit < choices.size(); unit++) {
            List<Integer> legal = choices.get(unit);
            Group group = identityGroups.get(legal);
            if (group == null) {
                group = equalGroups.get(legal);
                if (group == null) {
                    group = new Group(legal);
                    equalGroups.put(legal, group);
                }
                identityGroups.put(legal, group);
            }
            group.units.add(unit);
        }
        List<Group> groups = new ArrayList<>(equalGroups.values());
        if (groups.size() == 1) {
            Group group = groups.get(0);
            for (int ignored : group.legal) budget.graphEdge();
            int[] owners = new int[slots];
            Arrays.fill(owners, -1);
            int nextUnit = 0;
            // With one interchangeable group, choosing zero-cost slots first is the exact optimum.
            for (int cost = 0; cost <= 1; cost++) for (int slot : group.legal) {
                budget.scanEdge();
                if (casingCosts[slot] != cost || owners[slot] >= 0) continue;
                owners[slot] = group.units.get(nextUnit++);
                if (nextUnit == group.units.size()) return owners;
            }
            return null;
        }
        // Only referenced positions need graph nodes. A large capture of unrelated blocks adds no residual edges.
        Map<Integer, Integer> slotNodes = new LinkedHashMap<>();
        for (Group group : groups) for (int slot : group.legal) {
            budget.graphEdge();
            slotNodes.putIfAbsent(slot, groups.size() + slotNodes.size());
        }
        int source = groups.size() + slotNodes.size(), sink = source + 1, nodes = sink + 1;
        List<List<Edge>> graph = new ArrayList<>();
        for (int i = 0; i < nodes; i++) graph.add(new ArrayList<>());
        for (int index = 0; index < groups.size(); index++) {
            Group group = groups.get(index);
            edge(graph, source, index, group.units.size(), 0);
            for (int slot : group.legal) edge(graph, index, slotNodes.get(slot), 1, 0);
        }
        for (Map.Entry<Integer, Integer> slot : slotNodes.entrySet())
            edge(graph, slot.getValue(), sink, 1, casingCosts[slot.getKey()]);
        // Entry capacity carries duplicate machines without duplicating their slot edges. Reverse negative
        // edges retain global minimum casing consumption when earlier assignments must move aside.
        for (int flow = 0; flow < choices.size(); flow++) {
            int[] distance = new int[nodes], previous = new int[nodes], previousEdge = new int[nodes];
            Arrays.fill(distance, Integer.MAX_VALUE);
            boolean[] queued = new boolean[nodes];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            distance[source] = 0;
            queue.add(source);
            queued[source] = true;
            while (!queue.isEmpty()) {
                int from = queue.removeFirst();
                queued[from] = false;
                List<Edge> edges = graph.get(from);
                for (int index = 0; index < edges.size(); index++) {
                    budget.scanEdge();
                    Edge edge = edges.get(index);
                    if (edge.capacity == 0 || distance[edge.to] <= distance[from] + edge.cost) continue;
                    distance[edge.to] = distance[from] + edge.cost;
                    previous[edge.to] = from;
                    previousEdge[edge.to] = index;
                    if (!queued[edge.to]) {
                        queue.addLast(edge.to);
                        queued[edge.to] = true;
                    }
                }
            }
            if (distance[sink] == Integer.MAX_VALUE) return null;
            for (int to = sink; to != source; to = previous[to]) {
                Edge edge = graph.get(previous[to])
                    .get(previousEdge[to]);
                edge.capacity--;
                graph.get(to)
                    .get(edge.reverse).capacity++;
            }
        }
        int[] owners = new int[slots];
        Arrays.fill(owners, -1);
        int[] nodeSlots = new int[source];
        for (Map.Entry<Integer, Integer> slot : slotNodes.entrySet()) nodeSlots[slot.getValue()] = slot.getKey();
        for (int index = 0; index < groups.size(); index++) {
            Group group = groups.get(index);
            int nextUnit = 0;
            for (Edge edge : graph.get(index)) if (edge.to >= groups.size() && edge.to < source && edge.capacity == 0)
                owners[nodeSlots[edge.to]] = group.units.get(nextUnit++);
        }
        return owners;
    }

    private static void edge(List<List<Edge>> graph, int from, int to, int capacity, int cost) {
        Edge forward = new Edge(
            to,
            graph.get(to)
                .size(),
            capacity,
            cost);
        Edge reverse = new Edge(
            from,
            graph.get(from)
                .size(),
            0,
            -cost);
        graph.get(from)
            .add(forward);
        graph.get(to)
            .add(reverse);
    }

    private static final class Group {

        final List<Integer> legal;
        final List<Integer> units = new ArrayList<>();

        Group(List<Integer> legal) {
            this.legal = legal;
        }
    }

    enum LimitKind {

        CANDIDATES("候选评估"),
        GRAPH_EDGES("分配图大小"),
        EDGE_SCANS("匹配扫描");

        final String label;

        LimitKind(String label) {
            this.label = label;
        }
    }

    static final class WorkLimitException extends RuntimeException {

        final LimitKind kind;

        WorkLimitException(LimitKind kind) {
            this.kind = kind;
        }
    }

    static final class Budget {

        final int candidateLimit, edgeLimit, scanLimit;
        int evaluations, graphEdges, edgeScans;

        Budget() {
            this(MAX_CANDIDATE_EVALUATIONS, MAX_GROUP_SLOT_EDGES, MAX_EDGE_SCANS);
        }

        Budget(int candidateLimit, int edgeLimit, int scanLimit) {
            this.candidateLimit = candidateLimit;
            this.edgeLimit = edgeLimit;
            this.scanLimit = scanLimit;
        }

        void evaluate() {
            if (++evaluations > candidateLimit) throw new WorkLimitException(LimitKind.CANDIDATES);
        }

        void graphEdge() {
            if (++graphEdges > edgeLimit) throw new WorkLimitException(LimitKind.GRAPH_EDGES);
        }

        void scanEdge() {
            if (++edgeScans > scanLimit) throw new WorkLimitException(LimitKind.EDGE_SCANS);
        }
    }

    private static final class Edge {

        final int to, reverse, cost;
        int capacity;

        Edge(int to, int reverse, int capacity, int cost) {
            this.to = to;
            this.reverse = reverse;
            this.capacity = capacity;
            this.cost = cost;
        }
    }
}
