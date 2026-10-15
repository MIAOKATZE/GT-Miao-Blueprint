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
        placementGate();
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
        second.setBoolean("bound", false);
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
        second.setBoolean("bound", false);
        second.setBoolean("terminalBinding", false);
        SimpleAssert.that(
            !HologramPlacementConfirmation.networksReady(state),
            "An unavailable preserved network also blocks confirmation");
    }
}
