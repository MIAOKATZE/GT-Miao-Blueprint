package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.NBTTagCompound;

/** Shared, monotonic review admission. Polls/keepalives are deliberately not progress. */
public final class HologramAuthorReviewDeadline {

    public static final long HARD_NANOS = TimeUnit.MINUTES.toNanos(30);
    public static final long COMPUTE_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final long SLACK_NANOS = TimeUnit.SECONDS.toNanos(30);
    // Reserve for both admitted review slots. Unrelated future library traffic cannot be predicted;
    // actual-progress and hard ceilings still bound retention if that traffic delays a review.
    public static final int MAX_CONTENDERS = 2;
    private final int uploadRate, downloadRate;
    private final long started;
    private long stageStarted, stageBudget, progress, idleBudget;
    private int stage;

    public HologramAuthorReviewDeadline(int bytes, int uploadRate, int downloadRate, long now) throws IOException {
        checkRate(uploadRate);
        checkRate(downloadRate);
        this.uploadRate = uploadRate;
        this.downloadRate = downloadRate;
        started = now;
        long upload = transferNanos(bytes, uploadRate);
        if (upload + COMPUTE_NANOS + transferNanos(1, downloadRate) > HARD_NANOS)
            throw infeasible("upload", bytes, uploadRate);
        begin(0, upload, idleNanos(uploadRate), now);
    }

    public static HologramAuthorReviewDeadline negotiated(int bytes, NBTTagCompound packet, long now)
        throws IOException {
        if (packet.getInteger("reviewContenders") != MAX_CONTENDERS)
            throw new IOException("Unsupported author review deadline policy");
        return new HologramAuthorReviewDeadline(
            bytes,
            packet.getInteger("reviewUploadRate"),
            packet.getInteger("reviewDownloadRate"),
            now);
    }

    public boolean ratesMatch(int upload, int download) {
        return uploadRate == upload && downloadRate == download;
    }

    public void advertise(NBTTagCompound packet) {
        packet.setInteger("reviewContenders", MAX_CONTENDERS);
        packet.setInteger("reviewUploadRate", uploadRate);
        packet.setInteger("reviewDownloadRate", downloadRate);
    }

    public synchronized void progress(long now) {
        progress = now;
    }

    public synchronized void computing(long now) throws IOException {
        requireCurrent(now);
        if (stage == 0) begin(1, COMPUTE_NANOS, COMPUTE_NANOS, now);
    }

    /** Result size is unknown at admission: reject before publishing any response bytes if it cannot fit. */
    public synchronized void result(int bytes, long now) throws IOException {
        requireCurrent(now);
        if (stage == 2) return;
        long transfer = transferNanos(bytes, downloadRate);
        if (now - started + transfer > HARD_NANOS) throw infeasible("result", bytes, downloadRate);
        begin(2, transfer, idleNanos(downloadRate), now);
    }

    public synchronized boolean expired(long now) {
        return timeout(now) != null;
    }

    public synchronized String timeout(long now) {
        if (now - started > HARD_NANOS) return "Author review exceeded its hard 30 minute retention ceiling";
        String phase = stage == 0 ? "upload" : stage == 1 ? "processing" : "result download";
        if (now - stageStarted > stageBudget)
            return "Author review " + phase + " exceeded its admitted size/rate budget";
        if (now - progress > idleBudget)
            return "Author review " + phase + " made no byte/stage progress; bandwidth contention or disconnected peer";
        return null;
    }

    private void requireCurrent(long now) throws IOException {
        String failure = timeout(now);
        if (failure != null) throw new IOException(failure);
    }

    private void begin(int next, long budget, long idle, long now) {
        stage = next;
        stageStarted = progress = now;
        stageBudget = budget;
        idleBudget = idle;
    }

    private static void checkRate(int rate) throws IOException {
        if (rate < 1024 || rate > 1024 * 1024) throw new IOException("Invalid author review bandwidth policy");
    }

    static long transferNanos(int bytes, int rate) throws IOException {
        if (bytes <= 0 || bytes > HologramAuthorReviewProtocol.MAX_BYTES)
            throw new IOException("Invalid author review transfer size");
        checkRate(rate);
        // Two fair-share wire times accommodate tick/poll scheduling, plus fixed network slack.
        return SLACK_NANOS + (2L * bytes * MAX_CONTENDERS * 1000000000L + rate - 1) / rate;
    }

    private static long idleNanos(int rate) {
        return SLACK_NANOS + 2L * HologramLibraryProtocol.CHUNK_BYTES * MAX_CONTENDERS * 1000000000L / rate;
    }

    private static IOException infeasible(String stage, int bytes, int rate) {
        return new IOException(
            "Author review " + stage
                + " of "
                + bytes
                + " bytes at aggregate "
                + rate
                + " B/s (up to "
                + MAX_CONTENDERS
                + " admitted review owners) cannot fit the bounded 30 minute review window; "
                + "reduce source/result size or increase library bandwidth; recovery draft retained");
    }
}
