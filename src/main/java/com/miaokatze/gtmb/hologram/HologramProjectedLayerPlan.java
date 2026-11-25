// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.fits;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.malformed;

import java.io.IOException;
import java.util.ArrayDeque;

import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;

/** Bounded-window ordered stream. Not an inventory reservation, player admission, or world transaction. */
final class HologramProjectedLayerPlan {

    private HologramProjectedLayerPlan() {}

    enum Offer {
        ACCEPTED, REPLAY, OUT_OF_ORDER, STALE, CLOSED
    }

    /** World code can execute a ticket only after it independently proves stock, permission and dependencies. */
    static final class Ticket {

        final Slice slice;

        private Ticket(Slice slice) {
            this.slice = slice;
        }
    }

    static final class Receiver {

        private final Header header;
        private final int[] remaining;
        private int next, settled, received, receivedCells, lastY, lastZ, lastX;
        private final ArrayDeque<Ticket> pending = new ArrayDeque<>();
        private boolean closed;

        /** Create only after manifest/stock admission; the first ACK grants the initial upload credit. */
        Receiver(Manifest manifest) {
            header = manifest.header;
            remaining = new int[header.palette.size()];
            for (int i = 0; i < remaining.length; i++) remaining[i] = header.palette.get(i).count;
        }

        Offer offer(byte[] encoded) throws IOException {
            Slice slice = HologramProjectedLayerWire.decodeSlice(encoded);
            if (!header.request.equals(slice.request)) return Offer.STALE;
            // Task+sequence is the idempotency key. Replays never replace accepted work or issue another ticket.
            // Actual permission, target and payment validation remains in the server execution path.
            if (slice.sequence < next) {
                if (slice.sequence != next - 1) return Offer.STALE;
                return Offer.REPLAY;
            }
            if (finished() || closed) return Offer.CLOSED;
            if (slice.sequence < received) {
                for (Ticket ticket : pending) if (ticket.slice.sequence == slice.sequence) {
                    return Offer.REPLAY;
                }
                return Offer.STALE;
            }
            if (slice.sequence != received || pending.size() >= HologramProjectedLayerWire.WINDOW)
                return Offer.OUT_OF_ORDER;
            int remainingSlices = header.slices - received - 1;
            int remainingCells = header.cells - receivedCells - slice.size();
            if (!fits(remainingCells, remainingSlices)
                || slice.y < header.minY || slice.y > header.maxY
                || received != 0 && HologramProjectedLayerWire.compare(lastY, lastZ, lastX,
                    slice.y, slice.z(0), slice.x(0)) >= 0) throw malformed();
            int[] use = new int[remaining.length];
            for (int i = 0; i < slice.size(); i++) {
                int kind = slice.variant(i);
                if (kind >= remaining.length || ++use[kind] > remaining[kind]
                    || slice.x(i) < header.minX || slice.x(i) > header.maxX
                    || slice.z(i) < header.minZ || slice.z(i) > header.maxZ) throw malformed();
            }
            if (remainingSlices == 0) {
                for (int kind = 0; kind < remaining.length; kind++)
                    if (use[kind] != remaining[kind]) throw malformed();
            }
            pending.addLast(new Ticket(slice));
            for (int i = 0; i < remaining.length; i++) remaining[i] -= use[i];
            int last = slice.size() - 1;
            lastY = slice.y;
            lastZ = slice.z(last);
            lastX = slice.x(last);
            receivedCells += slice.size();
            received++;
            return Offer.ACCEPTED;
        }

        Ticket pending() {
            return pending.peekFirst();
        }

        /** Advance only when this exact owned slice has been accounted for; never ACK a failed batch as done. */
        boolean settle(Ticket supplied) {
            if (supplied == null || supplied != pending.peekFirst() || closed) return false;
            settled += supplied.slice.size();
            next++;
            pending.removeFirst();
            return true;
        }

        /** Stop/transport timeout: forget uncommitted geometry, retain cumulative transfer counters. */
        void abort() {
            if (finished()) return;
            closed = true;
            pending.clear();
        }

        boolean finished() {
            return next == header.slices;
        }

        Ack ack() throws IOException {
            boolean done = finished();
            return new Ack(header.request, next, settled, closed || done ? 0 : 1, done, closed);
        }
    }

    /** Client credit is a fixed window anchored to the settled frontier, never additive on replay. */
    static final class Sender {

        private final Header header;
        private final ArrayDeque<Integer> inFlight = new ArrayDeque<>();
        private int next, sent, settled, sentCells;
        private boolean credit, terminal;

        Sender(Header header) {
            this.header = header;
        }

        boolean accept(Ack ack) {
            if (terminal || !header.request.equals(ack.request) || ack.nextSlice < next || ack.nextSlice > sent
                || ack.nextSlice > header.slices || ack.settledCells > header.cells
                || ack.complete != (ack.nextSlice == header.slices)
                || !fits(header.cells - ack.settledCells, header.slices - ack.nextSlice)) return false;
            int advance = ack.nextSlice - next, expectedCells = settled, seen = 0;
            for (int count : inFlight) {
                if (seen++ == advance) break;
                expectedCells += count;
            }
            if (ack.settledCells != expectedCells) return false;
            for (int i = 0; i < advance; i++) inFlight.removeFirst();
            next = ack.nextSlice;
            settled = ack.settledCells;
            terminal = ack.complete || ack.aborted;
            credit = !terminal && ack.credit == 1;
            if (terminal) inFlight.clear();
            return true;
        }

        boolean canSend() {
            return credit && !terminal && inFlight.size() < HologramProjectedLayerWire.WINDOW
                && sent < header.slices;
        }

        boolean claim(Slice slice) {
            if (!canSend() || !header.request.equals(slice.request) || slice.sequence != sent
                || !fits(header.cells - sentCells - slice.size(), header.slices - sent - 1)) return false;
            inFlight.addLast(slice.size());
            sentCells += slice.size();
            sent++;
            return true;
        }
    }
}
