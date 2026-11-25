// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.commitment;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.fits;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.isEnd;
import static com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.malformed;

import java.io.IOException;
import java.security.MessageDigest;

import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;

/** Single-credit, ordered stream. Not an inventory reservation, player admission, or world transaction. */
final class HologramProjectedLayerPlan {

    private HologramProjectedLayerPlan() {}

    enum Offer {
        ACCEPTED, REPLAY, OUT_OF_ORDER, STALE, CLOSED
    }

    /** World code can execute a ticket only after it independently proves stock, permission and dependencies. */
    static final class Ticket {

        final Slice slice;
        private final byte[] digest;
        private final int[] use;

        private Ticket(Slice slice, byte[] digest, int[] use) {
            this.slice = slice;
            this.digest = digest;
            this.use = use;
        }
    }

    static final class Receiver {

        private final Header header;
        private final int[] remaining;
        private byte[] expected;
        private byte[] lastDigest;
        private int next, settled, lastY, lastZ, lastX;
        private Ticket pending;
        private boolean closed;

        /** Create only after manifest/stock admission; the first ACK grants the initial upload credit. */
        Receiver(Manifest manifest) {
            header = manifest.header;
            expected = manifest.root();
            remaining = new int[header.palette.size()];
            for (int i = 0; i < remaining.length; i++) remaining[i] = header.palette.get(i).count;
        }

        Offer offer(byte[] encoded) throws IOException {
            Slice slice = HologramProjectedLayerWire.decodeSlice(encoded);
            if (!header.request.equals(slice.request)) return Offer.STALE;
            if (slice.sequence < next) {
                if (slice.sequence != next - 1) return Offer.STALE;
                requireDigest(encoded, lastDigest);
                return Offer.REPLAY;
            }
            if (finished() || closed) return Offer.CLOSED;
            if (slice.sequence != next) return Offer.OUT_OF_ORDER;
            if (pending != null) {
                requireDigest(encoded, pending.digest);
                return Offer.REPLAY;
            }
            byte[] digest = requireDigest(encoded, expected);
            int remainingSlices = header.slices - next - 1;
            int remainingCells = header.cells - settled - slice.size();
            if (!fits(remainingCells, remainingSlices) || (remainingSlices == 0) != isEnd(slice.next())
                || slice.y < header.minY || slice.y > header.maxY
                || next != 0 && HologramProjectedLayerWire.compare(lastY, lastZ, lastX,
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
            pending = new Ticket(slice, digest, use);
            return Offer.ACCEPTED;
        }

        private byte[] requireDigest(byte[] encoded, byte[] required) throws IOException {
            byte[] digest = commitment(header, encoded);
            if (!MessageDigest.isEqual(digest, required)) throw malformed();
            return digest;
        }

        Ticket pending() {
            return pending;
        }

        /** Advance only when this exact owned slice has been accounted for; never ACK a failed batch as done. */
        boolean settle(Ticket supplied) {
            if (supplied == null || supplied != pending || closed) return false;
            for (int i = 0; i < remaining.length; i++) remaining[i] -= supplied.use[i];
            Slice slice = supplied.slice;
            int last = slice.size() - 1;
            lastY = slice.y;
            lastZ = slice.z(last);
            lastX = slice.x(last);
            settled += slice.size();
            next++;
            expected = slice.next();
            lastDigest = supplied.digest;
            pending = null;
            return true;
        }

        /** Stop/transport timeout: forget uncommitted geometry, retain cumulative transfer counters. */
        void abort() {
            if (finished()) return;
            closed = true;
            pending = null;
        }

        boolean finished() {
            return next == header.slices;
        }

        Ack ack() throws IOException {
            boolean done = finished();
            return new Ack(header.request, next, settled, closed || done || pending != null ? 0 : 1, done, closed);
        }
    }

    /** Client-side credit accounting; a replay can never permit a second in-flight slice. */
    static final class Sender {

        private final Header header;
        private int next, settled, inFlightCells;
        private boolean credit, terminal;

        Sender(Header header) {
            this.header = header;
        }

        boolean accept(Ack ack) {
            if (terminal || !header.request.equals(ack.request) || ack.nextSlice < next || ack.nextSlice > next + 1
                || ack.nextSlice > header.slices || ack.settledCells > header.cells
                || ack.complete != (ack.nextSlice == header.slices)
                || !fits(header.cells - ack.settledCells, header.slices - ack.nextSlice)) return false;
            if (ack.nextSlice == next) {
                if (ack.settledCells != settled) return false;
            } else {
                if (inFlightCells == 0 || ack.settledCells != settled + inFlightCells) return false;
                next = ack.nextSlice;
                settled = ack.settledCells;
                inFlightCells = 0;
            }
            terminal = ack.complete || ack.aborted;
            credit = !terminal && inFlightCells == 0 && ack.credit == 1;
            return true;
        }

        boolean claim(Slice slice) {
            if (!credit || terminal || inFlightCells != 0 || !header.request.equals(slice.request)
                || slice.sequence != next || !fits(header.cells - settled - slice.size(), header.slices - next - 1))
                return false;
            inFlightCells = slice.size();
            credit = false;
            return true;
        }
    }
}
