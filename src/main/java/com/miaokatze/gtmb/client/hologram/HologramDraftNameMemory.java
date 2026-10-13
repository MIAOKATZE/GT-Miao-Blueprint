package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** Text belongs to a loaded source and session, independently of revision-bound selection indices. */
final class HologramDraftNameMemory {

    private final String session, source, canonicalName, name;

    HologramDraftNameMemory(NBTTagCompound snapshot, String name) {
        session = snapshot.getString("session");
        source = source(snapshot);
        canonicalName = canonicalName(snapshot);
        this.name = name;
    }

    String restore(NBTTagCompound snapshot) {
        return session.equals(snapshot.getString("session")) && source.equals(source(snapshot))
            && canonicalName.equals(canonicalName(snapshot)) ? name : canonicalName(snapshot);
    }

    static boolean sameSource(NBTTagCompound before, NBTTagCompound after) {
        return before.getString("session")
            .equals(after.getString("session")) && source(before).equals(source(after));
    }

    static String canonicalName(NBTTagCompound snapshot) {
        return snapshot.getBoolean("virtual") ? snapshot.getString("virtualPresetName") : "";
    }

    private static String source(NBTTagCompound snapshot) {
        String local = snapshot.getString("libraryLocalId");
        if (!local.isEmpty()) return "local:" + local;
        String preset = snapshot.getString("virtualPresetId");
        if (!preset.isEmpty()) return "preset:" + preset;
        preset = snapshot.getString("selectedPresetId");
        if (snapshot.getBoolean("virtual") && !preset.isEmpty()) return "selected:" + preset;
        // Unsaved area drafts and real controller planning have no library id yet.
        return "draft:" + snapshot.getBoolean("virtual")
            + ":"
            + snapshot.getInteger("x")
            + ":"
            + snapshot.getInteger("y")
            + ":"
            + snapshot.getInteger("z")
            + ":"
            + canonicalName(snapshot);
    }
}
