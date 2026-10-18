package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

/** Exact material-order admission and terminal watermark, shared by progress and full-snapshot installation. */
final class HologramMaterialOrderLifecycle {

    private Object world, connection;
    private String session = "", id = "";
    private long revision, nonce, command = -1, sequence = -1;
    private boolean pending, terminal;
    private NBTTagCompound latest;
    private final Set<String> retired = new LinkedHashSet<>();

    void begin(Object world, Object connection, HologramState state, long command) {
        bind(world, connection, state);
        this.command = command;
        id = "";
        sequence = -1;
        latest = null;
        pending = true;
        terminal = false;
    }

    private void bind(Object world, Object connection, HologramState state) {
        if (this.world != world || this.connection != connection) retired.clear();
        this.world = world;
        this.connection = connection;
        session = state.session;
        revision = state.data.getLong("planRevision");
        nonce = state.data.getLong("placementReviewNonce");
    }

    boolean receive(Object world, Object connection, HologramState state, NBTTagCompound event) {
        if (!event.getBoolean("materialOrderOnly") || !"server".equals(event.getString("scope"))
            || !"material.order".equals(event.getString("phase"))
            || !matches(world, connection, state)
            || !session.equals(event.getString("session"))
            || revision != event.getLong("planRevision")
            || nonce != event.getLong("placementReviewNonce")
            || event.getLong("uiSequence") != command) return false;
        NBTTagCompound order = event.getCompoundTag("materialOrder");
        if (!event.getString("orderId")
            .equals(order.getString("orderId")) || event.getLong("orderSequence") != order.getLong("orderSequence"))
            return false;
        return accept(order);
    }

    private boolean accept(NBTTagCompound order) {
        String incoming = order.getString("orderId");
        long incomingSequence = order.getLong("orderSequence");
        if (incoming.isEmpty() || incomingSequence < 0
            || retired.contains(key(incoming))
            || !id.isEmpty() && !id.equals(incoming)
            || terminal
            || incomingSequence <= sequence) return false;
        if (id.isEmpty() && !pending) return false;
        id = incoming;
        sequence = incomingSequence;
        pending = false;
        latest = (NBTTagCompound) order.copy();
        terminal = !order.getBoolean("active");
        if (terminal) retire(id);
        return true;
    }

    /** Keep the latest small order metadata when a complete geometry baseline arrives late. */
    HologramState snapshot(Object world, Object connection, HologramState next) {
        NBTTagCompound order = next.data.getCompoundTag("materialOrder");
        if (!matches(world, connection, next)) {
            if (pending || active()) return next;
            if (!order.getBoolean("active") || order.getString("orderId")
                .isEmpty()) return next;
            if (order.hasKey("planRevision", 4) && order.getLong("planRevision") != next.data.getLong("planRevision")
                || order.hasKey("placementReviewNonce", 4)
                    && order.getLong("placementReviewNonce") != next.data.getLong("placementReviewNonce"))
                return next;
            // A full, already-admitted snapshot can recover a server order after rebuilding the UI owner.
            bind(world, connection, next);
            command = order.hasKey("orderUiSequence", 4) ? order.getLong("orderUiSequence")
                : next.data.getLong("uiSequence");
            id = "";
            sequence = -1;
            pending = true;
            terminal = false;
            latest = null;
        }
        long orderCommand = order.hasKey("orderUiSequence", 4) ? order.getLong("orderUiSequence")
            : next.data.getLong("uiSequence");
        if ((pending && orderCommand == command || !id.isEmpty() && id.equals(order.getString("orderId")))
            && validSnapshotContext(order)) accept(order);
        if (latest == null) return next;
        String candidateId = order.getString("orderId");
        boolean stale = candidateId.isEmpty() || !candidateId.equals(id)
            || order.getLong("orderSequence") < sequence
            || terminal && order.getBoolean("active");
        if (!stale) return next;
        NBTTagCompound patch = new NBTTagCompound();
        patch.setTag("materialOrder", latest);
        if (next.data.getLong("uiSequence") <= command || order.getBoolean("active"))
            patch.setString("status", latest.getString("status"));
        return next.withMetadata(patch);
    }

    private boolean validSnapshotContext(NBTTagCompound order) {
        return (!order.hasKey("planRevision", 4) || order.getLong("planRevision") == revision)
            && (!order.hasKey("placementReviewNonce", 4) || order.getLong("placementReviewNonce") == nonce);
    }

    private boolean matches(Object world, Object connection, HologramState state) {
        return this.world == world && this.connection == connection && owns(state);
    }

    boolean owns(HologramState state) {
        return state != null && session.equals(state.session)
            && revision == state.data.getLong("planRevision")
            && nonce == state.data.getLong("placementReviewNonce");
    }

    HologramState apply(HologramState state) {
        if (latest == null) return state;
        NBTTagCompound patch = new NBTTagCompound();
        patch.setTag("materialOrder", latest);
        patch.setString("status", latest.getString("status"));
        return state.withMetadata(patch);
    }

    NBTTagCompound order() {
        return latest == null ? new NBTTagCompound() : latest;
    }

    long command() {
        return command;
    }

    String id() {
        return id;
    }

    boolean active() {
        return latest != null && !terminal;
    }

    long restoreOwner(HologramTaskOwner owner) {
        return active() ? owner.begin("material", session + ":" + command, "AE 本次缺料订单") : 0;
    }

    boolean finishOwner(HologramTaskOwner owner) {
        if (!terminal || latest == null) return false;
        String state = latest.getString("state");
        return owner.finishReceipt(
            "material",
            session + ":" + command,
            "complete".equals(state) || "completed".equals(state) ? HologramTaskOwner.Result.SUCCESS
                : "cancelled".equals(state) ? HologramTaskOwner.Result.CANCELLED : HologramTaskOwner.Result.FAILURE,
            latest.getString("status"));
    }

    void reject() {
        pending = false;
        terminal = true;
        if (!id.isEmpty()) retire(id);
        latest = latest == null ? new NBTTagCompound() : (NBTTagCompound) latest.copy();
        latest.setBoolean("active", false);
        latest.setString("state", "failed");
        latest.setString("status", "本次 AE 下单未获服务端接纳；请核对状态后重试。");
    }

    boolean rejectSubmission(HologramState snapshot) {
        if (!pending || !id.isEmpty()
            || !session.equals(snapshot.session)
            || snapshot.data.getLong("planRevision") != revision
            || snapshot.data.getLong("placementReviewNonce") != nonce
            || snapshot.data.getLong("uiSequence") < command
            || snapshot.data.getBoolean("capturePending")) return false;
        NBTTagCompound order = snapshot.data.getCompoundTag("materialOrder");
        if (!order.getString("orderId")
            .isEmpty() && (!order.hasKey("orderUiSequence", 4) || order.getLong("orderUiSequence") >= command))
            return false;
        reject();
        latest.setString("status", snapshot.data.getString("status"));
        return true;
    }

    /** Material ACKs have an independent sequence; they cannot borrow a full snapshot's global ACK. */
    boolean quoteReceipt(Object world, Object connection, HologramState state, NBTTagCompound receipt) {
        if (!receipt.getBoolean("materialQuoteOnly") || !matches(world, connection, state)
            || !session.equals(receipt.getString("session"))
            || revision != receipt.getLong("planRevision")
            || nonce != receipt.getLong("placementReviewNonce")
            || receipt.getLong("uiSequence") != command) return false;
        NBTTagCompound order = receipt.getCompoundTag("materialOrder");
        if (!order.getString("orderId")
            .isEmpty() && order.hasKey("orderUiSequence", 4)
            && order.getLong("orderUiSequence") == command
            && validSnapshotContext(order)) return accept(order);
        if (!pending || !id.isEmpty()
            || !order.getString("orderId")
                .isEmpty() && (!order.hasKey("orderUiSequence", 4) || order.getLong("orderUiSequence") >= command))
            return false;
        reject();
        latest.setString("status", receipt.getString("status"));
        return true;
    }

    boolean rejectChangedReview(Object world, Object connection, HologramState state, NBTTagCompound receipt) {
        if (!pending || !id.isEmpty()
            || !receipt.getBoolean("materialQuoteOnly")
            || this.world != world
            || this.connection != connection
            || state == null
            || !session.equals(state.session)
            || !session.equals(receipt.getString("session"))
            || revision != state.data.getLong("planRevision")
            || revision != receipt.getLong("planRevision")
            || receipt.getLong("uiSequence") != command
            || receipt.getLong("placementReviewNonce") <= nonce
            || receipt.getLong("placementReviewNonce") != state.data.getLong("placementReviewNonce")) return false;
        reject();
        latest.setString("status", receipt.getString("status"));
        return true;
    }

    private String key(String orderId) {
        return session + ":" + revision + ":" + nonce + ":" + orderId;
    }

    private void retire(String orderId) {
        retired.add(key(orderId));
        while (retired.size() > 128) retired.remove(
            retired.iterator()
                .next());
    }
}
