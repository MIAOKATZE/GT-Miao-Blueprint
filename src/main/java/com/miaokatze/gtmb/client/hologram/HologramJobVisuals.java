package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Detached job receipts affect drawing only. The complete snapshot remains the sole editing authority. */
final class HologramJobVisuals {

    private static final class Outcome {

        final boolean success;
        final String status;

        Outcome(boolean success, String status) {
            this.success = success;
            this.status = status;
        }
    }

    private static HologramState basis;
    private static long batch = -1, publication = -1;
    private static NBTTagCompound terminal;
    private static final Map<Integer, Outcome> outcomes = new HashMap<>();
    private static final java.util.Deque<NBTTagCompound> pending = new java.util.ArrayDeque<>();
    private static String pendingContext = "";
    private static int pendingIndices;
    private static long pendingBatch = -1, pendingPublication = -1, lostThroughBatch = -1, lostThroughPublication = -1;
    private static boolean overflow;

    private HologramJobVisuals() {}

    static void bind(HologramState complete) {
        if (basis == complete) return;
        if (complete == null) {
            basis = null;
            outcomes.clear();
            terminal = null;
            batch = publication = -1;
            clearPending();
            return;
        }
        boolean same = basis != null && context(basis.data).equals(context(complete.data))
            && basis.cells.size() == complete.cells.size();
        boolean abandoned = !same && basis != null && pendingContext.equals(context(basis.data));
        long fullBatch = complete.data.getLong("batchSeq");
        long fullPublication = complete.data.getCompoundTag("progress")
            .getLong("sequence");
        if (!same || covers(fullBatch, fullPublication, batch, publication)) {
            outcomes.clear();
            terminal = null;
            batch = fullBatch;
            publication = fullPublication;
        }
        basis = complete;
        if (!pendingContext.equals(context(complete.data))) {
            if (abandoned) clearPending();
            return;
        }
        if (overflow && !covers(fullBatch, fullPublication, lostThroughBatch, lostThroughPublication)) return;
        List<NBTTagCompound> ready = new ArrayList<>(pending);
        clearPending();
        for (NBTTagCompound delta : ready) applyCurrent(complete, delta);
    }

    static int[] accept(HologramState state, NBTTagCompound delta) {
        if (!delta.getBoolean("jobDelta") || !validArrays(delta)) return new int[0];
        if (state != basis) bind(state);
        if (state != null && state.session.equals(delta.getString("session"))
            && (delta.getLong("planRevision") < state.data.getLong("planRevision")
                || delta.getLong("planRevision") == state.data.getLong("planRevision")
                    && delta.getLong("placementReviewNonce") < state.data.getLong("placementReviewNonce")))
            return new int[0];
        if (state == null || !context(state.data).equals(context(delta))) {
            queue(delta);
            return new int[0];
        }
        if (overflow && pendingContext.equals(context(delta))) {
            recordGap(delta);
            return new int[0];
        }
        return applyCurrent(state, delta);
    }

    private static int[] applyCurrent(HologramState state, NBTTagCompound delta) {
        long nextBatch = delta.getLong("batchSeq"), nextPublication = delta.getLong("sequence");
        if (covers(batch, publication, nextBatch, nextPublication)) return new int[0];
        int[] indices = delta.getIntArray("ackIndices"), successes = delta.getIntArray("ackSuccesses");
        NBTTagList statuses = delta.getTagList("ackStatuses", 8);
        if (indices.length != successes.length || indices.length != statuses.tagCount()) return new int[0];
        for (int index : indices) if (index < 0 || index >= state.cells.size()) return new int[0];
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < indices.length; i++) {
            Outcome next = new Outcome(successes[i] != 0, statuses.getStringTagAt(i));
            Outcome old = outcomes.put(indices[i], next);
            if (old == null || old.success != next.success || !old.status.equals(next.status)) changed.add(indices[i]);
        }
        batch = delta.getLong("batchSeq");
        publication = nextPublication;
        if (delta.hasKey("job", 3) && delta.getInteger("job") >= 3
            && (!delta.hasKey("uiSequence", 4) || delta.getLong("uiSequence") >= state.data.getLong("uiSequence")))
            terminal = terminalMetadata(state, delta);
        return changed.stream()
            .mapToInt(Integer::intValue)
            .toArray();
    }

    /** A terminal receipt updates only lifecycle observations, sharing every immutable geometry/quote object. */
    static HologramState metadata(HologramState state) {
        if (state == null || state != basis
            || terminal == null
            || covers(
                state.data.getLong("batchSeq"),
                state.data.getCompoundTag("progress")
                    .getLong("sequence"),
                terminal.getLong("batchSeq"),
                terminal.getCompoundTag("progress")
                    .getLong("sequence")))
            return state;
        HologramState updated = HologramState.apply(state, terminal);
        basis = updated;
        return updated;
    }

    private static NBTTagCompound terminalMetadata(HologramState state, NBTTagCompound delta) {
        NBTTagCompound patch = new NBTTagCompound();
        patch.setBoolean("partial", true);
        patch.setString("session", state.session);
        patch.setString("generation", state.data.getString("generation"));
        patch.setLong("baseRevision", state.data.getLong("planRevision"));
        patch.setLong("planRevision", state.data.getLong("planRevision"));
        patch.setInteger("cellCount", state.cells.size());
        for (String key : new String[] { "job", "cursor", "completed", "status", "jobMissingCells", "jobProtectedCells",
            "jobUnsupportedCells", "jobSkippedCells", "batchSeq", "uiSequence" })
            if (delta.hasKey(key)) patch.setTag(
                key,
                delta.getTag(key)
                    .copy());
        if (delta.hasKey("completed", 3)) patch.setInteger("layerCompleted", delta.getInteger("completed"));
        NBTTagCompound progress = new NBTTagCompound();
        for (String key : new String[] { "scope", "session", "phase", "outcome", "complete", "done", "total", "unit",
            "sequence", "uiSequence", "planRevision" }) if (delta.hasKey(key))
                progress.setTag(
                    key,
                    delta.getTag(key)
                        .copy());
        patch.setTag("progress", progress);
        return patch;
    }

    private static String context(NBTTagCompound data) {
        return data.getString("session") + ":"
            + data.getLong("planRevision")
            + ":"
            + data.getLong("placementReviewNonce");
    }

    private static boolean covers(long firstBatch, long firstPublication, long otherBatch, long otherPublication) {
        return firstBatch > otherBatch || firstBatch == otherBatch && firstPublication >= otherPublication;
    }

    private static boolean validArrays(NBTTagCompound delta) {
        int count = delta.getIntArray("ackIndices").length;
        return count == delta.getIntArray("ackSuccesses").length && count == delta.getTagList("ackStatuses", 8)
            .tagCount();
    }

    private static void recordGap(NBTTagCompound delta) {
        long nextBatch = delta.getLong("batchSeq"), nextPublication = delta.getLong("sequence");
        if (!covers(lostThroughBatch, lostThroughPublication, nextBatch, nextPublication)) {
            lostThroughBatch = nextBatch;
            lostThroughPublication = nextPublication;
        }
    }

    private static void queue(NBTTagCompound delta) {
        String next = context(delta);
        if (!pendingContext.equals(next)) {
            clearPending();
            pendingContext = next;
        }
        long nextBatch = delta.getLong("batchSeq"), nextPublication = delta.getLong("sequence");
        if (covers(pendingBatch, pendingPublication, nextBatch, nextPublication)) return;
        pendingBatch = nextBatch;
        pendingPublication = nextPublication;
        int count = delta.getIntArray("ackIndices").length;
        if (overflow || pending.size() >= 8 || count > 65536 - pendingIndices) {
            overflow = true;
            recordGap(delta);
            pending.clear();
            pendingIndices = 0;
            return;
        }
        pending.addLast((NBTTagCompound) delta.copy());
        pendingIndices += count;
    }

    private static void clearPending() {
        pending.clear();
        pendingIndices = 0;
        pendingContext = "";
        pendingBatch = pendingPublication = lostThroughBatch = lostThroughPublication = -1;
        overflow = false;
    }

    static boolean waitingForFull() {
        return overflow && (basis == null || pendingContext.equals(context(basis.data)));
    }

    static int bufferedBatches() {
        return pending.size();
    }

    static int bufferedIndices() {
        return pendingIndices;
    }

    static int receivedCells() {
        return outcomes.size();
    }

    static String status(HologramState state, HologramState.Cell cell) {
        Outcome outcome = state == basis ? outcomes.get(cell.index) : null;
        if (outcome == null) return cell.status;
        if (!outcome.success && ("placed".equals(outcome.status) || "replaced".equals(outcome.status)
            || "removed".equals(outcome.status))) return "failed";
        return outcome.status;
    }

    static boolean completed(HologramState state, HologramState.Cell cell) {
        String status = status(state, cell);
        return "placed".equals(status) || "replaced".equals(status) || "removed".equals(status);
    }

    static int color(HologramState state, HologramState.Cell cell) {
        if (state != basis || !outcomes.containsKey(cell.index)
            || cell.excluded
            || cell.missingBlock
            || cell.sourceMissing) return cell.color();
        String status = status(state, cell);
        if ("missing".equals(status) || "failed".equals(status)) return 0xe6747a;
        if ("protected".equals(status)) return 0xe4b85a;
        if ("unsupported".equals(status)) return 0xb68ede;
        if ("satisfied".equals(status) || completed(state, cell)) return 0x78d7a1;
        return cell.color();
    }
}
