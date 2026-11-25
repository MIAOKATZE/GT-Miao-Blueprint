// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Read-only presentation of an admitted request. Holds no blueprint, workspace snapshot or transport payload. */
public final class HologramOwnedPlacementProgress {

    public enum Phase {
        PREPARING("placement.prepare"),
        PREFLIGHT("placement.plan"),
        ADMISSION("placement.admission"),
        EXECUTING("job");

        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    public enum Outcome {
        SUCCESS, NO_WORK, FAILED, CANCELLED
    }

    /** Construct only from the authenticated committed-result protocol, never a prepare, ready, or slice ACK. */
    @com.github.bsideup.jabel.Desugar
    public record CommitReceipt(UUID request, long sequence, Outcome outcome, long placed, long alreadySatisfied,
        long total) {}

    /** An opaque handle, acquired once by the confirmed route and required for every update. */
    public static final class Ticket {

        private final UUID request;
        final long token;

        private Ticket(UUID request, long token) {
            this.request = request;
            this.token = token;
        }

        public UUID request() {
            return request;
        }
    }

    static final class Binding {

        private final Object world, connection, player, tool;
        private final int slot;

        Binding(Object world, Object connection, Object player, Object tool, int slot) {
            this.world = world;
            this.connection = connection;
            this.player = player;
            this.tool = tool;
            this.slot = slot;
        }

        boolean valid() {
            return world != null && connection != null && player != null && tool != null && slot >= 0 && slot < 9;
        }

        boolean sameActor(Binding other) {
            return other != null && world == other.world && connection == other.connection && player == other.player;
        }

        boolean sameTool(Binding other) {
            return sameActor(other) && tool == other.tool && slot == other.slot;
        }
    }

    private static final long TERMINAL_NANOS = 2_000_000_000L;
    private final HologramProgress.Tracker tracker = new HologramProgress.Tracker();
    private Ticket ticket;
    private Binding admitted;
    private BooleanSupplier cancel;
    private Phase phase;
    private long sequence, terminalAt;
    private Outcome terminal;

    Ticket begin(UUID request, Binding binding, long token, BooleanSupplier cancel, long now) {
        if (request == null || binding == null || !binding.valid() || token <= 0 || ticket != null && terminal == null)
            return null;
        tracker.clear();
        ticket = new Ticket(request, token);
        admitted = binding;
        this.cancel = cancel;
        terminal = null;
        phase = Phase.PREPARING;
        sequence = -1;
        progress(0, 0, now, false);
        return ticket;
    }

    boolean observe(Ticket expected, Binding binding, Phase next, long serial, long done, long total, long now) {
        if (!active(expected, binding) || next == null || serial < 0 || !counts(done, total)
            || next.ordinal() < phase.ordinal()
            || next == phase && !advance(serial, done, total)) return false;
        phase = next;
        sequence = serial;
        progress(done, total, now, false);
        return true;
    }

    boolean finish(Ticket expected, Binding binding, long serial, Outcome outcome, long placed,
        long alreadySatisfied, long total, long now) {
        if (outcome != Outcome.FAILED && outcome != Outcome.CANCELLED) return false;
        return finishResult(expected, binding, serial, outcome, placed, alreadySatisfied, total, now);
    }

    boolean acceptCommit(Ticket expected, Binding binding, CommitReceipt receipt, long now) {
        if (receipt == null || expected == null || !expected.request.equals(receipt.request())
            || receipt.outcome() != Outcome.SUCCESS && receipt.outcome() != Outcome.NO_WORK) return false;
        return finishResult(expected, binding, receipt.sequence(), receipt.outcome(), receipt.placed(),
            receipt.alreadySatisfied(), receipt.total(), now);
    }

    private boolean finishResult(Ticket expected, Binding binding, long serial, Outcome outcome, long placed,
        long alreadySatisfied, long total, long now) {
        if (!active(expected, binding) || serial < 0 || outcome == null
            || placed < 0 || alreadySatisfied < 0 || total < placed
            || alreadySatisfied > total - placed) return false;
        long done = placed + alreadySatisfied;
        boolean successfulResult = outcome == Outcome.SUCCESS || outcome == Outcome.NO_WORK;
        if (phase == Phase.EXECUTING && (serial <= sequence
            || (successfulResult || done != 0 || total != 0) && !advance(serial, done, total))) return false;
        // The authoritative terminal can arrive before executing progress. Earlier phase-local sequences are unrelated.
        if (outcome == Outcome.SUCCESS && (placed == 0 || done != total)) return false;
        if (outcome == Outcome.NO_WORK && (placed != 0 || done != total)) return false;
        terminal = outcome;
        terminalAt = now;
        cancel = null;
        if (outcome == Outcome.SUCCESS) {
            phase = Phase.EXECUTING;
            progress(done, total, now, true);
        } else tracker.clear(); // No work, stop, and rejection have text outcomes, not a spinner or 100%.
        return true;
    }

    private boolean advance(long serial, long done, long total) {
        HologramProgress last = tracker.view(0);
        return serial > sequence && done >= last.done && (last.total == 0 || last.total == total);
    }

    private static boolean counts(long done, long total) {
        return done >= 0 && total >= 0 && (total == 0 || done <= total);
    }

    private void progress(long done, long total, long now, boolean complete) {
        tracker.update(ticket.request.toString(), phase.label, done, total, "cells", complete, now);
    }

    boolean active(Ticket expected, Binding binding) {
        return expected != null && ticket == expected && terminal == null && admitted.sameActor(binding);
    }

    boolean visible(Binding binding) {
        return ticket != null && admitted.sameTool(binding);
    }

    HologramProgress view(Binding binding, long now) {
        return visible(binding) ? tracker.view(now) : null;
    }

    Ticket ticket() {
        return ticket;
    }

    Outcome terminal() {
        return terminal;
    }

    BooleanSupplier cancellation(Ticket expected, Binding binding) {
        return active(expected, binding) && visible(binding) ? cancel : null;
    }

    void expireTerminal(Binding current, long now) {
        if (terminal != null && (!visible(current) || now - terminalAt >= TERMINAL_NANOS)) clear();
    }

    void clear() {
        ticket = null;
        admitted = null;
        cancel = null;
        terminal = null;
        tracker.clear();
    }
}
