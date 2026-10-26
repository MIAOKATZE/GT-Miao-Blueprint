package com.miaokatze.gtmb.hologram;

/** Common/server contract. Logical pages are independent of the private client render pages. */
final class HologramSegmentContract {

    static final int PAGE_SIZE = 256;
    static final int SCHEMA_EPOCH = 1;

    enum Domain {
        SCHEMA,
        APPEARANCE,
        ASSET_NEEDS,
        RELATIONS,
        REPORT,
        MESH
    }

    enum Presence {
        ABSENT,
        EXPLICIT_EMPTY,
        KNOWN_DEFAULT,
        PRESENT_UNKNOWN
    }

    /** Storage generations never stand in for the input version of a specific domain. */
    static final class Stamp {

        final long authority, segment, input;
        final Domain domain;
        final int schemaEpoch;

        Stamp(long authority, long segment, Domain domain, long input) {
            this.authority = authority;
            this.segment = segment;
            this.domain = domain;
            this.input = input;
            schemaEpoch = SCHEMA_EPOCH;
        }

        boolean sameInput(Stamp other) {
            return other != null && authority == other.authority
                && segment == other.segment
                && domain == other.domain
                && input == other.input
                && schemaEpoch == other.schemaEpoch;
        }
    }

    /** A completed task may publish only while its exact domain input is still bound. */
    static boolean publishable(Stamp scheduled, Stamp current) {
        return scheduled != null && scheduled.sameInput(current);
    }

    /*
     * Initial integration is synchronous: one producer owns a miss. Reserve retained byte/entry capacity
     * before creating retained copies; oversized results take the uncached strict path. Never retain a
     * world/player, payment success, or mutable extension argument. Async subscribers/leases require a
     * separate scheduler integration, and cannot be inferred from this serial contract.
     */
    private HologramSegmentContract() {}
}
