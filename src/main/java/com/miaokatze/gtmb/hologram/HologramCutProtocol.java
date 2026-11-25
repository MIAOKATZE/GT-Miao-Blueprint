// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Independent owner/save clipboard. Requests contain IDs, coordinates, pose and optional naming/confirmation choices,
 * never block data. Replies contain bounded metadata only; rotation/turnX/turnZ/mirror default to identity.
 */
public final class HologramCutProtocol {

    public static final int VERSION = 1; // Existing frame format.
    private static final String BOUNDS_PROTOCOL = "cutBoundsProtocol";
    private static final String BOUNDS_TOKEN = "cutBoundsToken";
    private static final int BOUNDS_VERSION = 1;
    private static final String PASSIVE_LIST = "cutMetadataOnly";

    private static boolean untargetedList(NBTTagCompound request) {
        return "list".equals(request.getString("action"))
            && (!request.hasKey("id") || request.hasKey("id", 8) && request.getString("id")
                .isEmpty());
    }

    /** A legacy exact-key parser rejects this before its old LIST can run storage recovery. */
    public static void markPassiveList(NBTTagCompound request) {
        if (untargetedList(request)) request.setByte(PASSIVE_LIST, (byte) 1);
    }

    public static boolean passiveList(NBTTagCompound request) {
        return untargetedList(request) && request.hasKey(PASSIVE_LIST, 1) && request.getByte(PASSIVE_LIST) == 1;
    }

    static boolean passiveListKey(String key) {
        return PASSIVE_LIST.equals(key);
    }

    public static UUID boundsProof(NBTTagCompound message) {
        if (!message.hasKey(BOUNDS_PROTOCOL, 3) || message.getInteger(BOUNDS_PROTOCOL) != BOUNDS_VERSION
            || !message.hasKey(BOUNDS_TOKEN, 8)) return null;
        String token = message.getString(BOUNDS_TOKEN);
        if (token.length() != 36) return null;
        try {
            UUID id = UUID.fromString(token);
            return id.toString()
                .equals(token) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public static void writeBoundsProof(NBTTagCompound message, UUID token) {
        message.setInteger(BOUNDS_PROTOCOL, BOUNDS_VERSION);
        message.setString(BOUNDS_TOKEN, token.toString());
    }

    static boolean boundsKey(String key) {
        return BOUNDS_PROTOCOL.equals(key) || BOUNDS_TOKEN.equals(key);
    }

    public static final int MAX_CELLS = 262144;
    public static final int MAX_SIDE = 256;
    // Includes a bounded (128-box) constructive area snapshot, never client block data.
    public static final int REQUEST_BYTES = 65536;
    public static final int MAX_ENTRIES = 64;

    private HologramCutProtocol() {}
}
