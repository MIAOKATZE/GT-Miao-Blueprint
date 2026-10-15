package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Draft history and complete node coverage must survive real viewport selection and naming actions. */
public final class HologramQuantumGroupsRegression {

    private HologramQuantumGroupsRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        HologramState source = source();
        HologramQuantumGroupsModel model = new HologramQuantumGroupsModel(source);
        SimpleAssert.that(
            model.complete() && model.groups.size() == 1 && model.count("q1") == 3,
            "Every node starts in one default group without implicit server confirmation");
        SimpleAssert.that(model.nodes.get(2).cell == 2, "Node coordinates identify the actual preview cell");
        model.select(false);
        SimpleAssert.that(model.selected.size() == 3, "T selects every quantum node");
        model.select(true);
        SimpleAssert.that(model.selected.isEmpty(), "Ctrl+T selects the complement of the current selection");
        model.undo(false);
        SimpleAssert.that(model.selected.size() == 3 && model.canRedo(), "Selection itself can be undone");
        model.undo(true);
        SimpleAssert.that(model.selected.isEmpty(), "Selection itself can be redone");
        model.click(0, false);
        model.click(1, true);
        SimpleAssert.that(model.selected.size() == 2, "Ctrl click adds an actual node");
        model.click(0, true);
        SimpleAssert
            .that(model.selected.size() == 1 && model.selected.contains(1), "Ctrl click toggles an existing node");
        model.click(1, true);
        SimpleAssert.that(model.selected.isEmpty(), "Clicking the last selected node deselects it");
        model.click(0, false);
        SimpleAssert.that(model.create("前段供料") && model.count("q2") == 1, "A named group receives only selected nodes");
        model.undo(false);
        SimpleAssert.that(
            model.groups.size() == 1 && model.count("q1") == 3,
            "Undo restores both group creation and the old assignments");
        model.undo(true);
        SimpleAssert.that(model.groups.size() == 2 && model.count("q2") == 1, "Redo restores the exact named group");
        model.select(true);
        SimpleAssert.that(
            model.selected.size() == 2 && !model.selected.contains(0),
            "Ctrl+T is the selection complement even when all nodes are already classified");
        SimpleAssert
            .that(model.create("后段供料") && model.count("q3") == 2, "Remaining nodes can form a second named group");
        NBTTagList exported = model.export();
        SimpleAssert.that(
            exported.tagCount() == 2 && exported.getCompoundTagAt(0)
                .getString("name")
                .equals("前段供料")
                && exported.getCompoundTagAt(1)
                    .getString("name")
                    .equals("后段供料"),
            "The atomic payload omits empty groups and preserves Unicode names");
        model.assign(true);
        SimpleAssert.that(model.unassigned() == 2 && !model.complete(), "Unassigned nodes disable Save");
        model.undo(false);
        SimpleAssert.that(model.complete(), "Undo restores complete assignment coverage");
        model.undo(true);
        SimpleAssert.that(!model.complete(), "Redo restores the incomplete draft without enabling Save");
        model.undo(false);
        SimpleAssert.that(
            !model.rename("\n非法") && !model.rename("") && !model.rename(new String(new char[65]).replace('\0', '长')),
            "Control characters, blank names and oversized names are rejected locally");
        SimpleAssert.that(model.rename("后段供料二") && !model.canRedo(), "A new naming action replaces the redo branch");
        model.undo(false);
        SimpleAssert.that(model.find("q3").name.equals("后段供料"), "Undo restores the previous Chinese name");
        SimpleAssert.that(
            source.data.getCompoundTag("processing")
                .getTagList("quantumGroups", 10)
                .tagCount() == 0
                && !source.data.getCompoundTag("processing")
                    .getBoolean("quantumGroupsConfirmed"),
            "Discarding a local draft cannot mutate the canonical snapshot or confirm this blueprint");
        HologramQuantumGroupsModel reopened = new HologramQuantumGroupsModel(source);
        SimpleAssert.that(
            reopened.groups.size() == 1 && reopened.count("q1") == 3 && reopened.complete(),
            "Canceling optional custom classification leaves a complete default single group on reopening");
        placementGate();
        cacheConfirmation();
    }

    private static HologramState source() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "group-draft");
        source.setLong("planRevision", 4);
        NBTTagList cells = new NBTTagList(), nodes = new NBTTagList();
        for (int i = 0; i < 3; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("dx", i);
            cell.setInteger("dy", 2);
            cell.setInteger("dz", 3);
            cells.appendTag(cell);
            NBTTagCompound node = new NBTTagCompound();
            node.setInteger("x", i);
            node.setInteger("y", 2);
            node.setInteger("z", 3);
            node.setString("kind", i == 2 ? "join" : "node");
            nodes.appendTag(node);
        }
        source.setTag("cells", cells);
        NBTTagCompound processing = new NBTTagCompound();
        processing.setTag("quantumNodes", nodes);
        source.setTag("processing", processing);
        return new HologramState(source);
    }

    private static void placementGate() {
        NBTTagCompound state = new NBTTagCompound(), bindings = new NBTTagCompound();
        NBTTagList groups = new NBTTagList();
        NBTTagCompound first = new NBTTagCompound(), second = new NBTTagCompound();
        first.setBoolean("bound", true);
        first.setBoolean("terminalBinding", true);
        second.setBoolean("bound", false);
        second.setBoolean("terminalBinding", true);
        groups.appendTag(first);
        groups.appendTag(second);
        bindings.setTag("groups", groups);
        state.setTag("networkBindings", bindings);
        state.setLong("planRevision", 7);
        SimpleAssert.that(
            !HologramPlacementConfirmation.networksReady(state) && !HologramPlacementConfirmation.canBuild(state, 7),
            "One bound group never enables placement confirmation");
        second.setBoolean("bound", true);
        SimpleAssert.that(
            HologramPlacementConfirmation.networksReady(state) && HologramPlacementConfirmation.canBuild(state, 7),
            "All groups enable the existing reviewed placement gate");
        first.setBoolean("terminalBinding", false);
        second.setBoolean("terminalBinding", false);
        SimpleAssert.that(
            HologramPlacementConfirmation.networksReady(state) && HologramPlacementConfirmation.canBuild(state, 7)
                && !bindings.hasKey("inventory"),
            "Available original networks need no carried terminal or security proof for self-use");
        second.setBoolean("bound", false);
        second.setString("reason", "原存档或原网络锚点无法访问");
        SimpleAssert.that(
            !HologramPlacementConfirmation.networksReady(state),
            "An unavailable preserved network also blocks confirmation");
    }

    private static void cacheConfirmation() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", "cache-review");
        state.setLong("planRevision", 9);
        state.setLong("placementReviewNonce", 4);
        SimpleAssert.that(
            HologramPlacementConfirmation.cacheResources(state)
                .tagCount() == 0 && HologramPlacementConfirmation.cacheReviewMatches(state, null)
                && "确认放置".equals(HologramPlacementConfirmation.confirmationAction(state, 9, true)),
            "Ordinary blueprints retain their existing confirmation without a cache banner or extra gate");
        NBTTagList resources = new NBTTagList();
        NBTTagCompound row = new NBTTagCompound();
        row.setString("key", "cache.EU");
        row.setString("required", "123456789012345678901234567890");
        row.setString("available", "123456789012345678901234567890");
        row.setBoolean("optionalCache", true);
        resources.appendTag(row);
        state.setTag("blueprintResources", resources);
        SimpleAssert.that(
            !HologramPlacementConfirmation.emptyCacheFallback(state)
                && HologramPlacementConfirmation.cacheSummary(state)
                    .contains("123456789012345678901234567890")
                && HologramPlacementConfirmation.cacheReviewMatches(state, null),
            "An affordable cache shows exact server String amounts without truncating the energy quantity");
        row.setString("available", "7");
        row.setBoolean("emptyFallback", true);
        SimpleAssert.that(
            HologramPlacementConfirmation.emptyCacheFallback(state)
                && !HologramPlacementConfirmation.cacheReviewMatches(state, null)
                && "确认空缓存放置".equals(HologramPlacementConfirmation.confirmationAction(state, 9, true)),
            "An insufficient cache needs the explicitly labeled confirmation to be displayed before acceptance");
        NBTTagCompound reviewed = HologramPlacementConfirmation.cacheReviewSnapshot(state);
        SimpleAssert.that(
            HologramPlacementConfirmation.cacheReviewMatches(state, reviewed),
            "The displayed current review permits the existing final confirmation");
        row.setString("available", "6");
        SimpleAssert.that(
            !HologramPlacementConfirmation.cacheReviewMatches(state, reviewed),
            "A changed balance cannot use the previous displayed cache confirmation");
        row.setString("available", "7");
        state.setLong("placementReviewNonce", 5);
        SimpleAssert.that(
            !HologramPlacementConfirmation.cacheReviewMatches(state, reviewed),
            "Another placement review cannot reuse the previous cache acceptance");
        state.setLong("placementReviewNonce", 4);
        state.setLong("planRevision", 10);
        SimpleAssert.that(
            !HologramPlacementConfirmation.cacheReviewMatches(state, reviewed),
            "An edited blueprint requires its own cache review");
        state.setLong("planRevision", 9);
        state.setString("session", "different-session");
        SimpleAssert.that(
            !HologramPlacementConfirmation.cacheReviewMatches(state, reviewed),
            "A different session cannot reuse a displayed cache confirmation");
        state.setBoolean("virtual", true);
        state.setBoolean("virtualPlacement", true);
        state.setBoolean("virtualLocked", true);
        state.setBoolean("forcePlacementReady", true);
        String action = HologramPlacementConfirmation.confirmationAction(state, 9, true);
        SimpleAssert.that(
            action.contains("仅放已有") && action.contains("空缓存"),
            "Existing partial-material confirmation still explains the empty cache result");
        row.setString("key", "cache.warning");
        row.setBoolean("emptyFallback", false);
        SimpleAssert.that(
            HologramPlacementConfirmation.cacheResources(state)
                .tagCount() == 0,
            "Informational cache warnings do not add a permanent demand or confirmation gate");
    }
}
