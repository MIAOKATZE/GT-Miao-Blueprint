package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramJobVisualRegression {

    private HologramJobVisualRegression() {}

    public static void run() {
        HologramJobVisuals.bind(null);
        HologramState basis = state("job", 2, 3, 0);
        NBTTagCompound unchanged = (NBTTagCompound) basis.fullData();
        HologramJobVisuals.bind(basis);
        NBTTagCompound delta = delta("job", 2, 3, 1, 0, true, "placed");
        SimpleAssert.eq(1, HologramJobVisuals.accept(basis, delta).length, "one receipt changes only one visual cell");
        SimpleAssert
            .that(HologramJobVisuals.completed(basis, basis.cells.get(0)), "successful receipt hides its ghost");
        SimpleAssert.eq("pending", basis.cells.get(0).status, "receipt never rewrites an immutable editable cell");
        SimpleAssert
            .that(unchanged.equals(basis.fullData()), "receipt leaves all source, quote and authority NBT unchanged");
        SimpleAssert
            .eq(0, HologramJobVisuals.accept(basis, delta).length, "duplicate batch cannot replay visual changes");
        NBTTagCompound sameBatch = delta("job", 2, 3, 1, 2, true, "placed");
        sameBatch.setLong("sequence", 11);
        SimpleAssert.eq(
            1,
            HologramJobVisuals.accept(basis, sameBatch).length,
            "a later publication in the same job batch still carries new receipts");
        HologramState earlierSameBatch = state("job", 2, 3, 1);
        HologramJobVisuals.bind(earlierSameBatch);
        SimpleAssert.that(
            HologramJobVisuals.completed(earlierSameBatch, earlierSameBatch.cells.get(2)),
            "same-batch full state published before the receipt cannot erase it");
        basis = earlierSameBatch;
        SimpleAssert.eq(
            0,
            HologramJobVisuals.accept(basis, delta("job", 1, 3, 9, 1, true, "placed")).length,
            "older plan revision cannot affect the preview");
        SimpleAssert.eq(
            0,
            HologramJobVisuals.accept(basis, delta("job", 2, 2, 9, 1, true, "placed")).length,
            "older placement nonce cannot affect the preview");
        NBTTagCompound malformed = delta("job", 2, 3, 2, 1, true, "placed");
        malformed.setIntArray("ackIndices", new int[] { 1, 2 });
        SimpleAssert
            .eq(0, HologramJobVisuals.accept(basis, malformed).length, "parallel receipt arrays validate atomically");
        SimpleAssert.eq(2, HologramJobVisuals.receivedCells(), "malformed receipt cannot partially mutate visuals");
        SimpleAssert.eq(
            0,
            HologramJobVisuals.accept(basis, delta("job", 2, 3, 2, 99, true, "placed")).length,
            "out-of-range indices cannot reach renderer cells");
        HologramJobVisuals.accept(basis, delta("job", 2, 3, 2, 1, false, "placed"));
        SimpleAssert
            .that(!HologramJobVisuals.completed(basis, basis.cells.get(1)), "failed writes never hide a ghost as done");
        HologramState lateFull = state("job", 2, 3, 0);
        HologramJobVisuals.bind(lateFull);
        SimpleAssert.that(
            HologramJobVisuals.completed(lateFull, lateFull.cells.get(0)),
            "a delayed older full snapshot preserves newer receipts for the same context");
        HologramJobVisuals.bind(state("job", 2, 3, 2));
        SimpleAssert.eq(0, HologramJobVisuals.receivedCells(), "a covering full snapshot replaces detached receipts");

        HologramJobVisuals.bind(null);
        HologramJobVisuals.accept(null, delta);
        SimpleAssert.eq(1, HologramJobVisuals.bufferedBatches(), "early delta waits for a complete matching basis");
        basis = state("job", 2, 3, 0);
        HologramJobVisuals.bind(basis);
        SimpleAssert.eq(0, HologramJobVisuals.bufferedBatches(), "matching basis drains early receipts");
        SimpleAssert
            .that(HologramJobVisuals.completed(basis, basis.cells.get(0)), "buffered receipt is still visual-only");

        HologramJobVisuals.bind(null);
        for (int batch = 1; batch <= 9; batch++)
            HologramJobVisuals.accept(null, delta("job", 2, 3, batch, 0, true, "placed"));
        SimpleAssert
            .that(HologramJobVisuals.waitingForFull(), "exceeding eight early batches requests full visual recovery");
        SimpleAssert.that(
            HologramJobVisuals.bufferedBatches() <= 8 && HologramJobVisuals.bufferedIndices() <= 65536,
            "a missing basis never creates an unbounded visual queue");
        basis = state("job", 2, 3, 0);
        HologramJobVisuals.bind(basis);
        SimpleAssert.that(HologramJobVisuals.waitingForFull(), "old full state cannot conceal a visual gap");
        SimpleAssert.eq(
            0,
            HologramJobVisuals.accept(basis, delta("job", 2, 3, 10, 0, true, "placed")).length,
            "after overflow only a covering full state can repair the missing receipt range");
        HologramJobVisuals.bind(state("job", 2, 3, 10));
        SimpleAssert.that(!HologramJobVisuals.waitingForFull(), "a covering full snapshot repairs overflow");

        HologramJobVisuals.bind(null);
        NBTTagCompound oversized = delta("job", 2, 3, 1, 0, true, "placed");
        oversized.setIntArray("ackIndices", new int[65537]);
        oversized.setIntArray("ackSuccesses", new int[65537]);
        NBTTagList statuses = new NBTTagList();
        for (int i = 0; i < 65537; i++) statuses.appendTag(new NBTTagString("placed"));
        oversized.setTag("ackStatuses", statuses);
        HologramJobVisuals.accept(null, oversized);
        SimpleAssert.that(
            HologramJobVisuals.waitingForFull() && HologramJobVisuals.bufferedIndices() == 0,
            "a single oversized early publication cannot exceed the index budget");
        basis = state("new-job", 1, 1, 0);
        HologramJobVisuals.bind(basis);
        HologramJobVisuals.accept(basis, delta("new-job", 1, 1, 1, 0, true, "placed"));
        SimpleAssert.that(
            HologramJobVisuals.completed(basis, basis.cells.get(0)),
            "a different complete context remains usable after an unrelated early queue overflow");
        HologramJobVisuals.bind(null);
    }

    private static HologramState state(String session, long revision, long nonce, long batch) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("session", session);
        n.setLong("planRevision", revision);
        n.setLong("placementReviewNonce", nonce);
        n.setLong("batchSeq", batch);
        NBTTagCompound progress = new NBTTagCompound();
        progress.setLong("sequence", batch * 10);
        n.setTag("progress", progress);
        n.setInteger("job", 1);
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 3; i++) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setString("id", "minecraft:air");
            cell.setString("wantId", "minecraft:air");
            cell.setString("status", "pending");
            cell.setInteger("x", i);
            cells.appendTag(cell);
        }
        n.setTag("cells", cells);
        return new HologramState(n);
    }

    private static NBTTagCompound delta(String session, long revision, long nonce, long batch, int index,
        boolean success, String status) {
        NBTTagCompound n = new NBTTagCompound();
        n.setBoolean("jobDelta", true);
        n.setString("session", session);
        n.setLong("planRevision", revision);
        n.setLong("placementReviewNonce", nonce);
        n.setLong("batchSeq", batch);
        n.setLong("sequence", batch * 10);
        n.setIntArray("ackIndices", new int[] { index });
        n.setIntArray("ackSuccesses", new int[] { success ? 1 : 0 });
        NBTTagList statuses = new NBTTagList();
        statuses.appendTag(new NBTTagString(status));
        n.setTag("ackStatuses", statuses);
        return n;
    }
}
