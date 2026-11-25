// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-thread construction ownership only. The caller still authenticates the actor/tool and preserves every
 * operation-specific world, payment and journal check. Neither a packet marker nor a UUID can open a generation.
 */
final class HologramConstructionTaskGate {

    enum Kind {
        INCREMENTAL_NATIVE, PROJECTED_LAYER, LEGACY_NATIVE, LEGACY_PROJECTED, CURRENT_DRAFT, DEMOLITION, AE_ORDER
    }

    enum Decision {
        GRANTED, ACTIVE_REPLAY, TERMINAL_REPLAY, STALE_SCOPE, RETIRED_UNKNOWN, BUSY, RECOVERY_PENDING, CAPACITY
    }

    enum Phase {
        UNSEEN, ACTIVE, STOPPING, RECOVERY_PENDING, TERMINAL, STALE_SCOPE, RETIRED_UNKNOWN
    }

    enum End {
        COMPLETE, FAILED, CANCELLED, OWNER_REVOKED, STOPPED_FOR_RECOVERY
    }

    enum Settlement {
        COMMITTED, ROLLED_BACK, RECOVERY_PENDING
    }

    static final class Scope {

        final UUID actor;
        final long generation;
        private final Object player, world, connection;

        private Scope(UUID actor, long generation, Object player, Object world, Object connection) {
            this.actor = actor;
            this.generation = generation;
            this.player = player;
            this.world = world;
            this.connection = connection;
        }

        boolean matches(UUID actor, Object player, Object world, Object connection) {
            return this.actor.equals(actor) && this.player == player && this.world == world
                && this.connection == connection;
        }
    }

    /** Sequence is assigned/captured for one authenticated intent, and must be preserved on every retransmission. */
    static final class Request {

        final UUID id;
        final long sequence;
        final Kind kind;

        Request(UUID id, long sequence, Kind kind) {
            this.id = Objects.requireNonNull(id);
            this.kind = Objects.requireNonNull(kind);
            if (sequence <= 0) throw new IllegalArgumentException("Construction request sequence must be positive");
            this.sequence = sequence;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Request other)) return false;
            return sequence == other.sequence && kind == other.kind && id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return (id.hashCode() * 31 + Long.hashCode(sequence)) * 31 + kind.hashCode();
        }
    }

    /** Only the first successful admission gives the scheduler this token. Replays never receive it. */
    static final class Lease {

        final Scope scope;
        final Request request;
        private Permit inFlight;
        private End stop;
        private boolean recovery;
        private long committed;

        private Lease(Scope scope, Request request) {
            this.scope = scope;
            this.request = request;
        }
    }

    /** Exact world/payment transaction. A journal suspension does not release it. */
    static final class Permit {

        private final Lease lease;

        private Permit(Lease lease) {
            this.lease = lease;
        }
    }

    /** No references to a player, world or transport survive in the bounded result cache. */
    static final class Outcome {

        final Request request;
        final End end;
        final long committedTransactions;

        private Outcome(Lease lease, End end) {
            request = lease.request;
            this.end = end;
            committedTransactions = lease.committed;
        }
    }

    static final class Admission {

        final Decision decision;
        final Lease lease;
        final Outcome outcome;

        private Admission(Decision decision, Lease lease, Outcome outcome) {
            this.decision = decision;
            this.lease = lease;
            this.outcome = outcome;
        }
    }

    static final class View {

        final Phase phase;
        final long committedTransactions;
        final Outcome outcome;

        private View(Phase phase, long committedTransactions, Outcome outcome) {
            this.phase = phase;
            this.committedTransactions = committedTransactions;
            this.outcome = outcome;
        }
    }

    private static final class Owner {

        final Scope scope;
        long seenThrough;

        Owner(Scope scope) {
            this.scope = scope;
        }
    }

    private static final class ResultKey {

        final long generation;
        final Request request;

        ResultKey(Scope scope, Request request) {
            generation = scope.generation;
            this.request = request;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof ResultKey other)) return false;
            return generation == other.generation && request.equals(other.request);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(generation) * 31 + request.hashCode();
        }
    }

    private final Thread thread = Thread.currentThread();
    private final int maxScopes, maxLeases, maxOutcomes;
    private final Map<UUID, Owner> owners = new HashMap<>();
    private final Map<UUID, Lease> active = new HashMap<>();
    private final Map<ResultKey, Outcome> outcomes = new LinkedHashMap<>();
    private long generation;
    private boolean shutdown;
    private volatile boolean stoppedAndDrained;

    HologramConstructionTaskGate(int maxScopes, int maxLeases, int maxOutcomes) {
        if (maxScopes < 1 || maxLeases < 1 || maxLeases > maxScopes || maxOutcomes < 1)
            throw new IllegalArgumentException("Invalid construction gate capacity");
        this.maxScopes = maxScopes;
        this.maxLeases = maxLeases;
        this.maxOutcomes = maxOutcomes;
    }

    /**
     * Lifecycle code alone binds a verified live endpoint, using its exact previous generation (null for initial
     * login). Equality of endpoint objects cannot reset its request watermark, and a delayed close cannot close it.
     */
    Scope bind(Scope expected, UUID actor, Object player, Object world, Object connection) {
        requireThread();
        Objects.requireNonNull(actor);
        Objects.requireNonNull(player);
        Objects.requireNonNull(world);
        Objects.requireNonNull(connection);
        Owner old = owners.get(actor);
        if (shutdown || expected != (old == null ? null : old.scope)) return null;
        if (old != null && old.scope.matches(actor, player, world, connection)) return old.scope;
        if (old == null && owners.size() >= maxScopes) return null;
        if (generation == Long.MAX_VALUE) return null;
        Scope scope = new Scope(actor, ++generation, player, world, connection);
        owners.put(actor, new Owner(scope));
        if (old != null) retireScope(old.scope);
        return scope;
    }

    boolean revoke(Scope exact) {
        requireThread();
        if (current(exact) == null) return false;
        owners.remove(exact.actor);
        retireScope(exact);
        return true;
    }

    /**
     * Check existing competing work and authoritative journal state on the server thread first. This method reads
     * only the resulting booleans and cannot call a provider or mutate the game. A denied intent still consumes its
     * authenticated sequence, so a transport retry cannot become a delayed start after contention clears.
     */
    Admission acquire(Scope scope, Request request, boolean legacyBusy, boolean settlementPending) {
        requireThread();
        Objects.requireNonNull(request);
        Owner owner = current(scope);
        if (owner == null) return denied(Decision.STALE_SCOPE);
        Lease old = active.get(scope.actor);
        if (old != null && old.scope == scope && old.request.equals(request)) return denied(Decision.ACTIVE_REPLAY);
        if (request.sequence <= owner.seenThrough) {
            Outcome prior = outcomes.get(new ResultKey(scope, request));
            return prior == null ? denied(Decision.RETIRED_UNKNOWN)
                : new Admission(Decision.TERMINAL_REPLAY, null, prior);
        }
        owner.seenThrough = request.sequence;
        if (settlementPending || old != null && old.recovery) return denied(Decision.RECOVERY_PENDING);
        if (legacyBusy || old != null) return denied(Decision.BUSY);
        if (active.size() >= maxLeases) return denied(Decision.CAPACITY);
        Lease lease = new Lease(scope, request);
        active.put(scope.actor, lease);
        return new Admission(Decision.GRANTED, lease, null);
    }

    private static Admission denied(Decision reason) {
        return new Admission(reason, null, null);
    }

    boolean onOwnerThread() {
        return Thread.currentThread() == thread;
    }

    boolean scopeCurrent(Scope exact) {
        requireThread();
        return current(exact) != null;
    }

    /** A later server thread may start a fresh runtime only after the old one proves it has no outstanding permits. */
    boolean stoppedAndDrained() {
        return stoppedAndDrained;
    }

    /** Transitional reciprocal fence for genuine legacy starts until they acquire the same lease themselves. */
    boolean busy(UUID actor) {
        requireThread();
        return active.containsKey(actor);
    }

    View query(Scope scope, Request request) {
        requireThread();
        Objects.requireNonNull(request);
        Owner owner = current(scope);
        if (owner == null) return new View(Phase.STALE_SCOPE, 0, null);
        Lease job = active.get(scope.actor);
        if (job != null && job.scope == scope && job.request.equals(request))
            return new View(job.recovery ? Phase.RECOVERY_PENDING : job.stop == null ? Phase.ACTIVE : Phase.STOPPING,
                job.committed, null);
        Outcome result = outcomes.get(new ResultKey(scope, request));
        if (result != null) return new View(Phase.TERMINAL, result.committedTransactions, result);
        return new View(request.sequence <= owner.seenThrough ? Phase.RETIRED_UNKNOWN : Phase.UNSEEN, 0, null);
    }

    Permit beginTransaction(Lease exact) {
        requireThread();
        if (!owns(exact) || current(exact.scope) == null || exact.stop != null
            || exact.recovery || exact.inFlight != null) return null;
        exact.inFlight = new Permit(exact);
        return exact.inFlight;
    }

    /** Exact, callback-free guard for the final world/payment mutation boundary. Invalidity is not settlement. */
    boolean transactionCurrent(Permit exact) {
        requireThread();
        if (exact == null) return false;
        Lease lease = exact.lease;
        return owns(lease) && lease.inFlight == exact && lease.stop == null && !lease.recovery
            && current(lease.scope) != null;
    }

    /**
     * Called at the paired world/payment boundary, not merely after a world callback. On uncertainty retain the
     * permit until the existing journal proves commit or rollback. Resolution always stops the suspended job;
     * a new job requires a new explicit request and fresh normal authorization.
     */
    boolean settle(Permit exact, Settlement settlement) {
        requireThread();
        Objects.requireNonNull(settlement);
        if (exact == null || !owns(exact.lease) || exact.lease.inFlight != exact) return false;
        Lease lease = exact.lease;
        if (settlement == Settlement.RECOVERY_PENDING) {
            if (lease.recovery) return false;
            lease.recovery = true;
            return true;
        }
        lease.inFlight = null;
        if (settlement == Settlement.COMMITTED) lease.committed++;
        if (lease.recovery) terminate(lease, End.STOPPED_FOR_RECOVERY);
        else if (lease.stop != null) terminate(lease, lease.stop);
        if (shutdown && active.isEmpty()) stoppedAndDrained = true;
        return true;
    }

    /** Completion cannot release a live permit; job cancellation uses the exact scoped request instead. */
    boolean finish(Lease exact, End end) {
        requireThread();
        if (end != End.COMPLETE && end != End.FAILED) throw new IllegalArgumentException("Invalid completion reason");
        if (!owns(exact) || exact.inFlight != null || exact.stop != null || exact.recovery) return false;
        terminate(exact, end);
        return true;
    }

    boolean cancel(Scope scope, Request request) {
        requireThread();
        Objects.requireNonNull(request);
        if (current(scope) == null) return false;
        Lease lease = active.get(scope.actor);
        if (lease == null || lease.scope != scope || !lease.request.equals(request) || lease.stop != null) return false;
        stop(lease, End.CANCELLED);
        return true;
    }

    /** Administrative runtime reset stops work without reopening the scope's replay watermark or releasing permits. */
    void stopActiveJobs() {
        requireThread();
        for (Lease lease : new ArrayList<>(active.values())) stop(lease, End.CANCELLED);
    }

    /** Disarm future acquisition without removing any lease whose transaction still needs settlement. */
    boolean shutdown() {
        requireThread();
        if (!shutdown) {
            shutdown = true;
            for (Owner owner : owners.values()) retireScope(owner.scope);
            owners.clear();
            outcomes.clear();
        }
        if (active.isEmpty()) stoppedAndDrained = true;
        return stoppedAndDrained;
    }

    private Owner current(Scope scope) {
        if (shutdown || scope == null) return null;
        Owner current = owners.get(scope.actor);
        return current != null && current.scope == scope ? current : null;
    }

    private boolean owns(Lease lease) {
        return lease != null && active.get(lease.scope.actor) == lease;
    }

    private void retireScope(Scope scope) {
        Lease lease = active.get(scope.actor);
        if (lease != null && lease.scope == scope) stop(lease, End.OWNER_REVOKED);
    }

    private void stop(Lease lease, End end) {
        if (lease.stop == null) lease.stop = end;
        if (lease.inFlight == null) terminate(lease, lease.stop);
    }

    private void terminate(Lease lease, End end) {
        if (lease.inFlight != null) throw new IllegalStateException("Unsettled construction permit");
        active.remove(lease.scope.actor, lease);
        if (current(lease.scope) == null) return;
        outcomes.put(new ResultKey(lease.scope, lease.request), new Outcome(lease, end));
        while (outcomes.size() > maxOutcomes) outcomes.remove(outcomes.keySet().iterator().next());
    }

    private void requireThread() {
        if (!onOwnerThread()) throw new IllegalStateException("Construction gate owner thread");
    }
}
