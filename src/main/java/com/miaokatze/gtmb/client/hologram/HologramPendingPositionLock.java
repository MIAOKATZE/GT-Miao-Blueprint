package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;

/** Local visual intent only. The server snapshot remains the sole placement authority. */
final class HologramPendingPositionLock {

    private final HologramUiTicket ticket = new HologramUiTicket();
    private String session = "", issuedSession = "";
    private long revision = -1, issuedSequence = -1;
    private String cancellingSession = "";
    private long cancellingSequence = -1;
    private int x, y, z;

    boolean begin(NBTTagCompound state, int[] anchor, long watermark, NBTTagCompound request) {
        if (pending(state) || cancelling(state)) return false;
        String nextSession = state.getString("session");
        if (!issuedSession.equals(nextSession)) issuedSequence = -1;
        issuedSession = session = nextSession;
        revision = state.getLong("planRevision");
        issuedSequence = Math.max(Math.max(issuedSequence, watermark), state.getLong("uiSequence")) + 1;
        ticket.begin(session, issuedSequence);
        x = anchor[0];
        y = anchor[1];
        z = anchor[2];
        request.setString("action", "confirmVirtualPosition");
        request.setString("session", session);
        request.setLong("planRevision", revision);
        request.setLong("uiSequence", issuedSequence);
        request.setInteger("x", x);
        request.setInteger("y", y);
        request.setInteger("z", z);
        request.setInteger("side", state.getInteger("side"));
        return true;
    }

    boolean pending(NBTTagCompound state) {
        return ticket.sequence() >= 0 && state != null
            && session.equals(state.getString("session"))
            && revision == state.getLong("planRevision");
    }

    int[] anchor() {
        return new int[] { x, y, z };
    }

    void snapshot(NBTTagCompound state) {
        if (cancellingSequence >= 0
            && (!cancellingSession.equals(state.getString("session")) || !state.getBoolean("virtualPlacement")
                || state.getLong("uiSequence") > cancellingSequence)) {
            cancellingSession = "";
            cancellingSequence = -1;
        }
        if (ticket.sequence() < 0) return;
        if (!pending(state) || !state.getBoolean("virtual")
            || !state.getBoolean("virtualPlacement")
            || state.getBoolean("cancelled")) clear();
        else if (!"PREPARE".equals(state.getString("phase")))
            ticket.acknowledge(state.getString("session"), state.getLong("uiSequence"));
    }

    boolean rejected(NBTTagCompound event) {
        if (event.hasKey("uiSequence", 4) && cancellingSequence >= 0
            && cancellingSession.equals(event.getString("session"))
            && cancellingSequence == event.getLong("uiSequence")) {
            clear();
            return true;
        }
        return event.hasKey("uiSequence", 4)
            && ticket.rejectExact(event.getString("session"), event.getLong("uiSequence"));
    }

    void cancel(NBTTagCompound request) {
        if (ticket.sequence() >= 0) {
            cancellingSession = session;
            cancellingSequence = ticket.sequence();
            request.setLong("positionLockSequence", ticket.sequence());
            request.setLong("uiSequence", ticket.sequence());
        }
        ticket.clear();
        session = "";
        revision = -1;
    }

    boolean cancelling(NBTTagCompound state) {
        return cancellingSequence >= 0 && state != null && cancellingSession.equals(state.getString("session"));
    }

    void clear() {
        cancellingSession = "";
        cancellingSequence = -1;
        ticket.clear();
        session = "";
        revision = -1;
        // Keep the watermark: a rejected request must not share an identity with its retry.
    }
}
