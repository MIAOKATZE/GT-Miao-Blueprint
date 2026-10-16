package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

/** Optional clipboard metadata, stored inside the existing owner/save record. */
public final class HologramCutNaming {

    public static final int MAX_LENGTH = 64;
    public static final String SKIP_PROMPT = "skipNamePrompt";

    private HologramCutNaming() {}

    public static String checkedName(String requested) throws IOException {
        String name = requested == null ? "" : requested.trim();
        if (name.length() > MAX_LENGTH) throw new IOException("剪切名称不能超过 64 个字符");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || c == '\u00a7') throw new IOException("剪切名称包含不可用字符");
        }
        return name;
    }

    public static boolean skipsPrompt(NBTTagCompound state) {
        return state.getBoolean(SKIP_PROMPT);
    }

    static void apply(NBTTagCompound state, NBTTagCompound entry, NBTTagCompound request) throws IOException {
        String name = checkedName(request.getString("name"));
        if (!name.isEmpty()) entry.setString("name", name);
        // Called only while preparing a cut. The existing write-ahead save makes both fields durable together.
        if (request.getBoolean(SKIP_PROMPT)) state.setBoolean(SKIP_PROMPT, true);
    }
}
