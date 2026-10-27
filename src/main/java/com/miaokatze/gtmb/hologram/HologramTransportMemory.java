package com.miaokatze.gtmb.hologram;

/** Compressed ingress is shared by ordinary and cut assembly and retained until publication/retirement. */
final class HologramTransportMemory {

    private static final long LIMIT = HologramSnapshotAssembly.MAX_BYTES;
    private static final int CONTROL_FRAME_BYTES = 64 * 1024, CONTROL_RESERVE_BYTES = 256 * 1024;
    private static long reserved, controlReserved;

    private HologramTransportMemory() {}

    static synchronized Lease reserve(long bytes) {
        if (bytes < 0 || bytes > LIMIT) return null;
        if (bytes <= LIMIT - reserved) {
            HologramProcessGovernor.Lease process = HologramProcessGovernor.tryRetain("transport.compressed", bytes);
            if (process == null) return null;
            reserved += bytes;
            return new Lease(bytes, false, process.transfer());
        }
        // A small complete legacy receipt must fit even while a large display has reserved the data lane.
        if (bytes <= CONTROL_FRAME_BYTES && bytes <= CONTROL_RESERVE_BYTES - controlReserved) {
            HologramProcessGovernor.Lease process = HologramProcessGovernor.tryRetain("transport.control", bytes);
            if (process == null) return null;
            controlReserved += bytes;
            return new Lease(bytes, true, process.transfer());
        }
        return null;
    }

    static final class Lease implements AutoCloseable {

        private long bytes;
        private final boolean control;
        private final HologramProcessGovernor.Lease process;

        Lease(long bytes, boolean control, HologramProcessGovernor.Lease process) {
            this.bytes = bytes;
            this.control = control;
            this.process = process;
        }

        public void close() {
            synchronized (HologramTransportMemory.class) {
                if (control) controlReserved -= bytes;
                else reserved -= bytes;
                bytes = 0;
                process.close();
                HologramTransportMemory.class.notifyAll();
            }
        }
    }
}
