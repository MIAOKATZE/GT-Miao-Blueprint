package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

/** Placement confirmation is independent of naming and scoped by the existing owner/save storage. */
public final class HologramCutPlacementPreference {

    public static final String SKIP_PROMPT = "skipPlacementPrompt";

    private HologramCutPlacementPreference() {}

    public static boolean skipsPrompt(NBTTagCompound state) {
        return state.getBoolean(SKIP_PROMPT);
    }

    static void validate(NBTTagCompound request) throws IOException {
        if (request.hasKey(SKIP_PROMPT)
            && (!"paste".equals(request.getString("action")) || !request.hasKey(SKIP_PROMPT, 1)))
            throw new IOException("只有放置操作可以保存放置确认偏好");
    }

    static void remember(NBTTagCompound state, NBTTagCompound request) throws IOException {
        validate(request);
        // The caller saves this with the paste write-ahead journal before touching any blocks.
        if (request.getBoolean(SKIP_PROMPT)) state.setBoolean(SKIP_PROMPT, true);
    }
}
