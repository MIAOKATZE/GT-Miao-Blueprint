package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Presentation-only navigation. A binding becomes ready exclusively through the server snapshot. */
final class HologramNetworkBindingNavigation {

    private String session = "", group = "";
    private long sequence = -1, revision;
    private boolean wasBound;

    void begin(NBTTagCompound state, long sequence, String group, boolean wasBound) {
        session = state.getString("session");
        revision = state.getLong("planRevision");
        this.sequence = sequence;
        this.group = group;
        this.wasBound = wasBound;
    }

    boolean pending() {
        return sequence >= 0;
    }

    void cancel() {
        sequence = -1;
    }

    int observe(NBTTagCompound state, String selectedGroup) {
        if (!pending()) return -1;
        if (!session.equals(state.getString("session")) || !group.equals(selectedGroup)
            || !state.getBoolean("networkBindingRequested")) {
            cancel();
            return -1;
        }
        long acknowledged = state.getLong("uiSequence");
        if (acknowledged < sequence) return -1;
        int boundGroup = indexOf(state, group);
        boolean success = acknowledged == sequence && state.getLong("planRevision") > revision
            && boundGroup >= 0
            && groups(state).getCompoundTagAt(boundGroup)
                .getBoolean("bound");
        cancel();
        // Deliberate rebinding stays on that group; an old bound flag cannot advance a failed request.
        return success && !wasBound ? firstPending(state) : -1;
    }

    static NBTTagList groups(NBTTagCompound state) {
        return state.getCompoundTag("networkBindings")
            .getTagList("groups", 10);
    }

    static int indexOf(NBTTagCompound state, String id) {
        NBTTagList groups = groups(state);
        for (int i = 0; i < groups.tagCount(); i++) if (id.equals(
            groups.getCompoundTagAt(i)
                .getString("id")))
            return i;
        return -1;
    }

    static int firstPending(NBTTagCompound state) {
        NBTTagList groups = groups(state);
        for (int i = 0; i < groups.tagCount(); i++) {
            NBTTagCompound group = groups.getCompoundTagAt(i);
            if (group.getBoolean("terminalBinding") && !group.getBoolean("bound")) return i;
        }
        return -1;
    }

    static int accent(int id, NBTTagCompound state, int selected, boolean pending, boolean rebindSelected) {
        if (pending) return 0;
        NBTTagList groups = groups(state);
        if (id == 197 && state.getCompoundTag("networkBindings")
            .getBoolean("terminalBinding") && HologramPlacementConfirmation.networksReady(state)) return 0x80efaa;
        if (selected < 0 || selected >= groups.tagCount()) return 0;
        NBTTagCompound group = groups.getCompoundTagAt(selected);
        if (!group.getBoolean("terminalBinding")) return 0;
        if (id == 60000 + selected && !group.getBoolean("bound")) return 0x68dded;
        if (id >= 61000 && id < 61036 && (!group.getBoolean("bound") || rebindSelected)) {
            NBTTagList inventory = state.getCompoundTag("networkBindings")
                .getTagList("inventory", 10);
            for (int i = 0; i < inventory.tagCount(); i++) {
                NBTTagCompound terminal = inventory.getCompoundTagAt(i);
                if (terminal.getInteger("slot") == id - 61000 && terminal.getBoolean("available")) return 0x80efaa;
            }
        }
        return 0;
    }
}
