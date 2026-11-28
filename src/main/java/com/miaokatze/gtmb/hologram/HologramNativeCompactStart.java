// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

/** Explicit refusal for retired compact requests; no preparation, execution, timeout or ownership state. */
final class HologramNativeCompactStart {

    private HologramNativeCompactStart() {}

    static boolean canonical(String text) {
        try {
            return java.util.UUID.fromString(text)
                .toString()
                .equals(text);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static boolean ordinary(HologramSession ignored) {
        return false;
    }

    static boolean tick(HologramSession ignored) {
        return false;
    }

    static boolean cancel(EntityPlayerMP player, String id) {
        return false;
    }

    static void cancel(HologramSession ignored) {}

    static void receive(EntityPlayerMP player, NBTTagCompound request) {
        HologramNetwork.refuseRetiredPlacement(
            HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler),
            request.getString("placementRequestId"));
    }

    static void receive(EntityPlayerMP player, NBTTagCompound request, HologramNativeReadyPlacement.Receipt receipt) {
        receipt.send(player, request.getString("placementRequestId"), false, "旧版施工入口已退役，请使用当前结构或保存蓝图施工入口。");
    }
}
