package com.miaokatze.gtmb.hologram;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

/** At most one small latest rejection per live peer/channel/tick; a flood cannot amplify into one reply per packet. */
final class HologramBusyReplies {

    enum Kind {
        ACTION,
        CUT,
        LIBRARY
    }

    private static final Map<EntityPlayerMP, NBTTagCompound[]> pending = new IdentityHashMap<>();

    private HologramBusyReplies() {}

    static synchronized void reject(EntityPlayerMP player, NBTTagCompound request, Kind kind) {
        NBTTagCompound[] replies = pending.get(player);
        if (replies == null) {
            if (pending.size() >= 256) return; // Bounded by peers, not attacker-controlled nonces.
            replies = new NBTTagCompound[Kind.values().length];
            pending.put(player, replies);
        }
        NBTTagCompound proof = new NBTTagCompound();
        for (String key : new String[] { "session", "op", "action", "requestId", "transfer", "saveReceipt", "localId",
            "jsonSha256", "previewSha256" }) {
            String value = request.getString(key);
            proof.setString(key, value.substring(0, Math.min(128, value.length())));
        }
        proof.setLong("uiSequence", request.getLong("uiSequence"));
        proof.setLong("planRevision", request.getLong("planRevision"));
        proof.setInteger("artifactVersion", request.getInteger("artifactVersion"));
        replies[kind.ordinal()] = proof;
        HologramRuntimeMetrics.add(HologramRuntimeMetrics.Counter.ACTION_REJECTED, 1);
    }

    static void flush() {
        Map<EntityPlayerMP, NBTTagCompound[]> replies;
        synchronized (HologramBusyReplies.class) {
            if (pending.isEmpty()) return;
            replies = new IdentityHashMap<>(pending);
            pending.clear();
        }
        for (Map.Entry<EntityPlayerMP, NBTTagCompound[]> peer : replies.entrySet()) {
            EntityPlayerMP player = peer.getKey();
            if (player.playerNetServerHandler == null || player.playerNetServerHandler.netManager == null
                || !player.playerNetServerHandler.netManager.isChannelOpen()) continue;
            for (Kind kind : Kind.values()) {
                NBTTagCompound proof = peer.getValue()[kind.ordinal()];
                if (proof == null) continue;
                if (kind == Kind.LIBRARY) {
                    proof.setString("op", "error");
                    proof.setString("code", "busy");
                    proof.setString("message", "服务器蓝图库请求繁忙，请稍后重试");
                    HologramNetwork.sendLibraryReply(player, proof);
                } else {
                    proof.setString("scope", kind == Kind.CUT ? "cut" : "server");
                    if (kind == Kind.CUT) proof.setString("op", proof.getString("action"));
                    proof.setString("phase", "transport.busy");
                    proof.setString("outcome", "rejected");
                    proof.setString("code", "busy");
                    proof.setString("message", "服务器请求繁忙，此操作未执行，请稍后重试");
                    NBTTagCompound state = new NBTTagCompound();
                    state.setBoolean("progressOnly", true);
                    state.setTag("progress", proof);
                    HologramNetwork.sendState(player, state);
                }
            }
        }
    }

    static synchronized void clear() {
        pending.clear();
    }
}
