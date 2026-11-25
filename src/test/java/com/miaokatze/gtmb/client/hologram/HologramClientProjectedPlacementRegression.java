// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.UUID;

import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Phase;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Reason;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerTransport.Reply;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Deterministic client-side receipt correlation and bounded mailbox, without starting a game or transport. */
public final class HologramClientProjectedPlacementRegression {

    private static final UUID REQUEST = new UUID(789, 123);

    private HologramClientProjectedPlacementRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        Object playHandler = new Object();
        Reply ready = reply(2, 0, Phase.AWAITING_SLICE, Reason.NONE);
        SimpleAssert.that(HologramClientProjectedPlacement.scoped(REQUEST, 3, 5, playHandler, ready, playHandler),
            "only current request + intent + established scope + exact play handler");
        SimpleAssert.that(!HologramClientProjectedPlacement.scoped(REQUEST, 3, 0, playHandler, ready, playHandler),
            "no data readiness before exact negotiated scope");
        SimpleAssert.that(!HologramClientProjectedPlacement.scoped(REQUEST, 3, 6, playHandler, ready, playHandler),
            "reply from the previous same-connection scope cannot reopen");
        SimpleAssert.that(!HologramClientProjectedPlacement.scoped(REQUEST, 4, 5, playHandler, ready, playHandler),
            "intent chronology cannot be borrowed across requests");
        SimpleAssert.that(!HologramClientProjectedPlacement.scoped(new UUID(1, 2), 3, 5, playHandler, ready, playHandler),
            "UUID mismatch refuses before any work");
        SimpleAssert.that(!HologramClientProjectedPlacement.scoped(REQUEST, 3, 5, playHandler, ready, new Object()),
            "wrong actual inbound play handler cannot receive progress or credit");

        Reply placed = reply(4, 0, Phase.PLACING, Reason.NONE);
        Reply stopAck = reply(4, 1, Phase.PLACING, Reason.NONE);
        Reply terminal = reply(5, 1, Phase.STOPPED, Reason.CANCELLED);
        SimpleAssert.eq(placed, HologramClientProjectedPlacement.newer(placed, ready),
            "delayed progress cannot regress a full one-entry mailbox");
        SimpleAssert.eq(stopAck, HologramClientProjectedPlacement.newer(placed, stopAck),
            "same status serial may carry a newer control acknowledgement");
        SimpleAssert.eq(terminal, HologramClientProjectedPlacement.newer(stopAck, terminal),
            "the real stopped receipt supersedes its pure control acknowledgement");
        SimpleAssert.eq(terminal, HologramClientProjectedPlacement.newer(terminal, placed),
            "delayed data does not evict the authoritative terminal receipt");
        Reply recovery = reply(6, 1, Phase.RECOVERY_PENDING, Reason.RECOVERY_PENDING);
        recovery.placed = 2;
        SimpleAssert.that(HologramClientProjectedPlacement.detail(recovery).contains("核定"),
            "recovery uncertainty is never called a complete job");
        Reply noWork = reply(6, 0, Phase.COMPLETE, Reason.NONE);
        SimpleAssert.that(HologramClientProjectedPlacement.detail(noWork).contains("无需放置"),
            "zero actual placements are not announced as a completed construction");
    }

    private static Reply reply(long sequence, long control, Phase phase, Reason reason) {
        Reply reply = new Reply();
        reply.request = REQUEST;
        reply.intentSequence = 3;
        reply.scopeGeneration = 5;
        reply.sequence = sequence;
        reply.controlSequence = control;
        reply.phase = phase;
        reply.reason = reason;
        return reply;
    }
}
