package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Isolated author draft. Only a complete, explicit Save can leave this model. */
final class HologramQuantumGroupsModel {

    static final class Node {

        final int x, y, z, cell;
        final String kind;
        String group = "";

        Node(NBTTagCompound value, HologramState state) {
            x = value.getInteger("x");
            y = value.getInteger("y");
            z = value.getInteger("z");
            kind = value.getString("kind");
            int found = -1;
            for (HologramState.Cell candidate : state.cells)
                if (candidate.dx == x && candidate.dy == y && candidate.dz == z) found = candidate.index;
            cell = found;
        }
    }

    static final class Group {

        final String id;
        String name;

        Group(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Draft {

        final List<Group> groups = new ArrayList<>();
        final List<String> assignments = new ArrayList<>();
        final Set<Integer> selected;
        final String active;

        Draft(HologramQuantumGroupsModel model) {
            for (Group group : model.groups) groups.add(new Group(group.id, group.name));
            for (Node node : model.nodes) assignments.add(node.group);
            selected = new LinkedHashSet<>(model.selected);
            active = model.active;
        }
    }

    final List<Node> nodes = new ArrayList<>();
    final List<Group> groups = new ArrayList<>();
    final Set<Integer> selected = new LinkedHashSet<>();
    final String session;
    final long revision;
    private final ArrayDeque<Draft> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    String active = "";

    HologramQuantumGroupsModel(HologramState state) {
        session = state.session;
        revision = state.data.getLong("planRevision");
        NBTTagCompound processing = state.data.getCompoundTag("processing");
        NBTTagList source = processing.getTagList("quantumNodes", 10);
        for (int i = 0; i < source.tagCount(); i++) nodes.add(new Node(source.getCompoundTagAt(i), state));
        NBTTagList values = processing.getTagList("quantumGroups", 10);
        for (int i = 0; i < values.tagCount(); i++) {
            NBTTagCompound row = values.getCompoundTagAt(i);
            String id = row.getString("id");
            if (find(id) != null) continue;
            groups.add(new Group(id, row.getString("name")));
            NBTTagList cells = row.getTagList("cells", 10);
            for (int c = 0; c < cells.tagCount(); c++) {
                NBTTagCompound cell = cells.getCompoundTagAt(c);
                for (Node node : nodes) if (node.x == cell.getInteger("x") && node.y == cell.getInteger("y")
                    && node.z == cell.getInteger("z")) node.group = id;
            }
        }
        if (groups.isEmpty()) {
            groups.add(new Group("q1", "默认网络"));
            for (Node node : nodes) node.group = "q1";
        }
        active = groups.get(0).id;
    }

    Group find(String id) {
        for (Group group : groups) if (group.id.equals(id)) return group;
        return null;
    }

    int count(String id) {
        int count = 0;
        for (Node node : nodes) if (node.group.equals(id)) count++;
        return count;
    }

    int unassigned() {
        int count = 0;
        for (Node node : nodes) if (find(node.group) == null) count++;
        return count;
    }

    static boolean validName(String name) {
        if (name.trim()
            .isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++)
            if (Character.isISOControl(name.charAt(i)) || name.charAt(i) == 167) return false;
        return true;
    }

    boolean complete() {
        if (nodes.isEmpty() || unassigned() > 0) return false;
        for (Group group : groups) if (count(group.id) > 0 && !validName(group.name)) return false;
        for (Node node : nodes) if (node.cell < 0) return false;
        return true;
    }

    private void remember() {
        undo.addLast(new Draft(this));
        if (undo.size() > 128) undo.removeFirst();
        redo.clear();
    }

    void click(int node, boolean control) {
        if (node < 0 || node >= nodes.size()) return;
        remember();
        boolean remove = selected.contains(node);
        if (!control) selected.clear();
        if (remove) selected.remove(node);
        else selected.add(node);
    }

    void select(boolean complement) {
        remember();
        Set<Integer> previous = new LinkedHashSet<>(selected);
        selected.clear();
        for (int i = 0; i < nodes.size(); i++) if (!complement || !previous.contains(i)) selected.add(i);
    }

    void activate(String id) {
        if (find(id) == null || active.equals(id)) return;
        remember();
        active = id;
    }

    boolean create(String name) {
        if (selected.isEmpty() || !validName(name)) return false;
        remember();
        int number = 1;
        while (find("q" + number) != null) number++;
        active = "q" + number;
        groups.add(new Group(active, name.trim()));
        for (int index : selected) nodes.get(index).group = active;
        return true;
    }

    boolean rename(String name) {
        Group group = find(active);
        if (group == null || !validName(name) || group.name.equals(name.trim())) return false;
        remember();
        group.name = name.trim();
        return true;
    }

    void assign(boolean clear) {
        if (selected.isEmpty() || !clear && find(active) == null) return;
        remember();
        for (int index : selected) nodes.get(index).group = clear ? "" : active;
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    void undo(boolean forward) {
        ArrayDeque<Draft> from = forward ? redo : undo, to = forward ? undo : redo;
        if (from.isEmpty()) return;
        to.addLast(new Draft(this));
        Draft draft = from.removeLast();
        groups.clear();
        for (Group group : draft.groups) groups.add(new Group(group.id, group.name));
        for (int i = 0; i < nodes.size(); i++) nodes.get(i).group = draft.assignments.get(i);
        selected.clear();
        selected.addAll(draft.selected);
        active = draft.active;
    }

    NBTTagList export() {
        NBTTagList result = new NBTTagList();
        for (Group group : groups) {
            if (count(group.id) == 0) continue;
            NBTTagCompound value = new NBTTagCompound();
            value.setString("id", group.id);
            value.setString("name", group.name);
            NBTTagList cells = new NBTTagList();
            for (Node node : nodes) if (node.group.equals(group.id)) {
                NBTTagCompound cell = new NBTTagCompound();
                cell.setInteger("x", node.x);
                cell.setInteger("y", node.y);
                cell.setInteger("z", node.z);
                cells.appendTag(cell);
            }
            value.setTag("cells", cells);
            result.appendTag(value);
        }
        return result;
    }
}
