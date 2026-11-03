package com.miaokatze.gtmb.hologram;

import java.util.Arrays;

/** Dependency-free checks for fragment ordering, atomic completion and hostile resource declarations. */
public final class HologramSnapshotAssemblyRegression {

    private HologramSnapshotAssemblyRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("HologramSnapshotAssemblyRegression passed");
    }

    public static void run() {
        HologramSnapshotAssembly assembly = new HologramSnapshotAssembly();
        byte[] first = new byte[HologramSnapshotAssembly.CHUNK_BYTES];
        Arrays.fill(first, (byte) 7);
        byte[] tail = { 9, 11, 13 };
        int total = first.length + tail.length;
        check(assembly.accept(1, total, 0, first, 0) == null, "partial snapshot stays invisible");
        byte[] completed = assembly.accept(1, total, 1, tail, 1);
        check(completed != null && completed.length == total, "complete snapshot joins every byte");
        check(completed[0] == 7 && completed[first.length] == 9 && completed[total - 1] == 13, "byte order");
        check(assembly.accept(1, total, 0, first, 2) == null, "completed sequence cannot replay");
        check(assembly.accept(2, total, 1, tail, 3) == null, "must start at first fragment");
        check(assembly.accept(2, total, 0, first, 4) == null, "new sequence starts");
        check(assembly.accept(1, total, 1, tail, 5) == null, "old sequence cannot mix into newer one");
        check(assembly.accept(2, total, 1, tail, 6) != null, "old fragment does not damage new assembly");
        check(assembly.accept(3, total, 0, first, 7) == null, "timeout fixture starts");
        check(assembly.accept(3, total, 1, tail, 31_000_000_007L) == null, "expired partial never publishes");
        check(assembly.accept(4, HologramSnapshotAssembly.MAX_BYTES + 1, 0, first, 8) == null, "byte budget");
        check(assembly.accept(4, total, Integer.MAX_VALUE, tail, 9) == null, "hostile index");
        check(assembly.accept(4, total, 0, tail, 10) == null, "short nonfinal fragment");
        check(assembly.accept(4, tail.length, 0, tail, 11) != null, "small snapshot survives invalid declarations");
        check(assembly.accept(5, total, 0, first, 12) == null, "clear fixture starts");
        assembly.clear();
        check(assembly.accept(5, total, 1, tail, 13) == null, "cancelled assembly cannot resume");
        check(assembly.accept(6, tail.length, 0, tail, 14) != null, "next snapshot after cancellation");

        assembly = new HologramSnapshotAssembly();
        total = 2 * first.length + tail.length;
        check(assembly.accept(1, total, 0, first, 0) == null, "slow snapshot starts");
        assembly.expire(25_000_000_000L);
        check(assembly.accept(1, total, 1, first, 25_000_000_000L) == null, "slow snapshot makes progress");
        assembly.expire(50_000_000_000L);
        completed = assembly.accept(1, total, 2, tail, 50_000_000_000L);
        check(completed != null && completed.length == total, "active snapshot survives beyond thirty seconds");
        check(completed[first.length] == 7 && completed[total - 1] == 13, "slow snapshot byte order");

        check(assembly.accept(2, total, 0, first, 60_000_000_000L) == null, "idle snapshot starts");
        check(assembly.accept(2, total, 1, first, 85_000_000_000L) == null, "idle snapshot last progress");
        assembly.expire(115_000_000_001L);
        check(
            assembly.accept(2, total, 2, tail, 115_000_000_001L) == null,
            "snapshot expires after thirty seconds without progress");
        check(assembly.accept(3, tail.length, 0, tail, 115_000_000_002L) != null, "next snapshot after idle expiry");

        check(assembly.accept(4, total, 0, first, 120_000_000_000L) == null, "invalid activity fixture starts");
        check(assembly.accept(3, total, 1, first, 149_000_000_000L) == null, "stale fragment is ignored");
        check(assembly.accept(4, total, 1, tail, 149_000_000_000L) == null, "invalid fragment is ignored");
        check(
            assembly.accept(4, total, 1, first, 150_000_000_001L) == null,
            "invalid fragments do not prolong inactivity timeout");
        check(
            assembly.accept(4, total, 2, tail, 150_000_000_002L) == null,
            "expired sequence cannot publish after invalid activity");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
