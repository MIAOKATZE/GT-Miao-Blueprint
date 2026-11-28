// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

/** Old-peer rejection DTO only; this protocol no longer maintains a second request ledger. */
final class HologramConstructionReceipts {

    private HologramConstructionReceipts() {}

    static boolean consumed(EntityPlayerMP player, String id) {
        return false;
    }

    static void clear() {}

    static void retire(EntityPlayerMP player, String id) {}

    static void reply(EntityPlayerMP player, String id, boolean accepted, String reason) {
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("placementRequestId", id);
        receipt.setBoolean("placementRequestAccepted", false);
        receipt.setString("errorMessage", "旧版施工入口已退役，请使用当前结构或保存蓝图施工入口。");
        HologramNetwork.sendState(player, receipt);
    }

    static void cancel(EntityPlayerMP player, String id) {
        reply(player, id, false, "");
    }
}
