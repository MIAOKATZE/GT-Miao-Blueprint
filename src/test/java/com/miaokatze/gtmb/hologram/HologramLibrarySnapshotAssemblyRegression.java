package com.miaokatze.gtmb.hologram;

import java.util.Arrays;

/** Hash verification and inactivity expiry retain atomic hydration while transferring the admitted buffer. */
public final class HologramLibrarySnapshotAssemblyRegression {

    public static void main(String[] args) {
        run();
        System.out.println("HologramLibrarySnapshotAssemblyRegression passed");
    }

    public static void run() {
        byte[] source = new byte[HologramLibraryProtocol.CHUNK_BYTES + 3];
        Arrays.fill(source, (byte) 7);
        byte[] hash = HologramLibraryProtocol.digest()
            .digest(source);
        byte[] first = Arrays.copyOf(source, HologramLibraryProtocol.CHUNK_BYTES);
        byte[] last = Arrays.copyOfRange(source, first.length, source.length);
        HologramLibrarySnapshotAssembly assembly = new HologramLibrarySnapshotAssembly();
        check(assembly.acceptReserved(1, source.length, 0, first, hash, 0) == null, "partial hydration invisible");
        HologramSnapshotAssembly.Complete complete = assembly.acceptReserved(1, source.length, 1, last, hash, 1);
        check(
            complete != null && Arrays.equals(source, complete.bytes),
            "single admitted buffer contains all verified bytes");
        check(
            HologramTransportMemory.reserve(HologramSnapshotAssembly.MAX_BYTES) == null,
            "hydration retains its reservation after assembly");
        complete.close();
        assembly.acceptReserved(2, source.length, 0, first, hash, 0);
        check(
            assembly.acceptReserved(2, source.length, 1, last, hash, HologramLibraryProtocol.TIMEOUT_NANOS + 1) == null,
            "idle timeout never publishes a partial frame");
        byte[] wrong = hash.clone();
        wrong[0]++;
        assembly.acceptReserved(3, source.length, 0, first, wrong, 0);
        check(
            assembly.acceptReserved(3, source.length, 1, last, wrong, 1) == null,
            "whole-frame digest mismatch rejected");
        HologramTransportMemory.Lease all = HologramTransportMemory.reserve(HologramSnapshotAssembly.MAX_BYTES);
        check(all != null, "expiry and digest failure release admission");
        all.close();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
