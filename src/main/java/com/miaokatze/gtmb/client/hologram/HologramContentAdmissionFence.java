package com.miaokatze.gtmb.client.hologram;

/** A content receipt can open only its original UI selection, once, before cancellation. */
final class HologramContentAdmissionFence {

    private final long task;
    private final String transfer, expectedHash;
    private boolean retired;

    HologramContentAdmissionFence(long task, String transfer, String expectedHash) {
        if (task <= 0 || transfer == null || transfer.isEmpty() || expectedHash == null)
            throw new IllegalArgumentException("Content admission identity");
        this.task = task;
        this.transfer = transfer;
        this.expectedHash = expectedHash;
    }

    boolean accept(long currentTask, long receivedTask, String receivedTransfer, String receivedHash) {
        if (retired || task != currentTask
            || task != receivedTask
            || !transfer.equals(receivedTransfer)
            || receivedHash == null
            || !receivedHash.matches("[a-f0-9]{64}")
            || !expectedHash.isEmpty() && !expectedHash.equals(receivedHash)) return false;
        retired = true;
        return true;
    }

    void cancel() {
        retired = true;
    }
}
