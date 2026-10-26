package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Revision-bound detail producers. Native resource readers execute only in owner continuations. */
final class HologramProcessingDetails {

    private static final long PAGE_BYTES = 256L * 1024;
    private static final long PAGE_ROWS_BYTES = PAGE_BYTES - 4096;

    interface Task extends AutoCloseable {

        boolean step(int maxWork, long deadline) throws IOException;

        NBTTagCompound result();

        void close();
    }

    static final class Directory {

        final long[] rows, blockers;
        final boolean known, complete;

        private Directory(long[] rows, long[] blockers, boolean known, boolean complete) {
            this.rows = rows;
            this.blockers = blockers;
            this.known = known;
            this.complete = complete;
        }

        long total(boolean blocked) {
            long[] values = blocked ? blockers : rows;
            return values[values.length - 1];
        }

        int chunk(long offset, boolean blocked) {
            long[] values = blocked ? blockers : rows;
            int low = 0, high = values.length - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (values[mid] <= offset) low = mid;
                else high = mid - 1;
            }
            return Math.min(low, values.length - 2);
        }
    }

    static void summarize(HologramPresets.Preset source) {
        if (source == null) return;
        int chunks = (source.cells.size() + 255) / 256;
        HologramProcessGovernor.Lease lease = HologramRetainedCache
            .tryReserve("details.resource-directory", 256L + 16L * (chunks + 1));
        if (lease == null) {
            source.resourceDirectory = null;
            return;
        }
        try {
            long[] rows = new long[chunks + 1], blockers = new long[chunks + 1];
            boolean known = true, complete = true;
            for (int i = 0; i < source.cells.size(); i++) {
                HologramPresets.Cell cell = source.cells.get(i);
                if (!cell.excluded) {
                    known &= cell.resourceTileRows >= 0 && cell.resourceItemRows >= 0;
                    complete &= cell.resourceTileComplete && cell.resourceItemComplete;
                    rows[i / 256 + 1] += Math.max(0, cell.resourceTileRows) + Math.max(0, cell.resourceItemRows);
                    blockers[i / 256 + 1] += Math.max(0, cell.resourceTileBlockers)
                        + Math.max(0, cell.resourceItemBlockers);
                }
            }
            for (int i = 1; i <= chunks; i++) {
                rows[i] += rows[i - 1];
                blockers[i] += blockers[i - 1];
            }
            Directory directory = new Directory(rows, blockers, known, complete);
            HologramRetainedCache.watch(directory)
                .add(lease);
            source.resourceDirectory = directory;
        } catch (RuntimeException | Error failed) {
            lease.close();
            throw failed;
        }
    }

    static Task resource(HologramPresets.Preset source, String domain, int offset, int limit) throws IOException {
        if (source == null || !("resourceReview".equals(domain) || "resourceBlockers".equals(domain)))
            throw new IOException("Resource report source is unavailable");
        return new ResourceTask(source, "resourceBlockers".equals(domain), offset, limit);
    }

    static Task cell(HologramPresets.Preset source, HologramBlueprintProcessing.Policy policy, int index, int offset,
        int limit) throws IOException {
        if (source == null || index < 0 || index >= source.cells.size())
            throw new IOException("Cell detail source is unavailable");
        return new CellTask(source, policy, index, offset, limit);
    }

    private abstract static class Base implements Task {

        final HologramAnalysisFacts facts = new HologramAnalysisFacts();
        final NBTTagCompound output = new NBTTagCompound();
        final int offset, limit;
        final HologramProcessGovernor.Lease resultLease;
        boolean done;

        Base(int offset, int limit) throws IOException {
            this.offset = offset;
            this.limit = Math.max(1, Math.min(64, limit));
            resultLease = HologramProcessGovernor.tryRetain("details.owner-result", PAGE_BYTES);
            if (resultLease == null) throw new HologramSnapshotCodec.BusyException("Detail result quota is busy");
        }

        void owner() {
            String name = Thread.currentThread()
                .getName();
            if (HologramSnapshotDataContext.active()
                || !HologramWorkBudget.isOwnerThread() && !"Server thread".equals(name) && !"main".equals(name))
                throw new IllegalStateException("Native detail producers require the server owner");
        }

        public NBTTagCompound result() {
            if (!done) throw new IllegalStateException("Detail page is incomplete");
            return output;
        }

        public void close() {
            facts.close();
            resultLease.close();
        }
    }

    private static final class ResourceTask extends Base {

        final HologramPresets.Preset source;
        final boolean blocked;
        final Directory directory;
        final NBTTagList page = new NBTTagList();
        int cursor, scope;
        long skip, total, pageBytes;
        boolean complete = true, pageFull;

        ResourceTask(HologramPresets.Preset source, boolean blocked, int offset, int limit) throws IOException {
            super(offset, limit);
            this.source = source;
            this.blocked = blocked;
            directory = source.resourceDirectory;
            skip = Math.max(0, this.offset);
            if (directory != null && directory.known && source.cells.size() > 0) {
                complete = directory.complete;
                if (this.offset >= directory.total(blocked)) {
                    cursor = source.cells.size();
                    return;
                }
                int chunk = directory.chunk(skip, blocked);
                cursor = chunk * 256;
                skip -= (blocked ? directory.blockers : directory.rows)[chunk];
            }
        }

        public boolean step(int maxWork, long deadline) throws IOException {
            owner();
            HologramProcessGovernor.Lease cpu = HologramProcessGovernor.tryHeavyCpu("details.resource-owner");
            if (cpu == null) return false;
            try {
                for (int work = 0; work < maxWork && !done; work++) {
                    if (work > 0 && System.nanoTime() >= deadline) return false;
                    if (cursor >= source.cells.size()
                        || (pageFull || page.tagCount() >= limit) && directory != null && directory.known) {
                        output.setTag("rows", page);
                        long count = directory != null && directory.known ? directory.total(blocked) : total;
                        output.setLong("total", count);
                        if (offset < 0 || offset > count) output.setString("detailError", "offset");
                        output.setInteger("offset", offset);
                        output.setBoolean("complete", complete);
                        done = true;
                        break;
                    }
                    HologramPresets.Cell cell = source.cells.get(cursor);
                    boolean tile = scope == 0;
                    long known = cell.excluded ? 0
                        : blocked ? tile ? cell.resourceTileBlockers : cell.resourceItemBlockers
                            : tile ? cell.resourceTileRows : cell.resourceItemRows;
                    if (known >= 0 && (skip >= known || pageFull || page.tagCount() >= limit)) {
                        complete &= cell.excluded || (tile ? cell.resourceTileComplete : cell.resourceItemComplete);
                        skip = Math.max(0, skip - known);
                        total += known;
                    } else {
                        NBTTagCompound inspected = HologramBlueprintProcessing.resourceScopePage(
                            cell,
                            cursor,
                            tile ? "tile" : "item",
                            (int) skip,
                            pageFull ? 0 : Math.max(0, limit - page.tagCount()),
                            blocked,
                            Math.max(0, PAGE_ROWS_BYTES - pageBytes),
                            facts);
                        long count = inspected.getLong("total");
                        pageBytes += inspected.getLong("bytes");
                        pageFull |= inspected.getBoolean("byteLimitReached");
                        if (pageFull && page.tagCount() == 0
                            && inspected.getTagList("rows", 10)
                                .tagCount() == 0)
                            output.setString("detailError", "rowTooLarge");
                        if (directory != null && directory.known && known >= 0 && count != known)
                            throw new IOException("Resource detail dependencies changed; refresh the report");
                        complete &= inspected.getBoolean("complete");
                        total += count;
                        NBTTagList rows = inspected.getTagList("rows", 10);
                        for (int i = 0; i < rows.tagCount(); i++) page.appendTag(rows.getCompoundTagAt(i));
                        skip = Math.max(0, skip - count);
                    }
                    if (++scope == 2) {
                        scope = 0;
                        cursor++;
                    }
                }
                return done;
            } finally {
                cpu.close();
            }
        }
    }

    private static final class CellTask extends Base {

        final HologramPresets.Preset source;
        final HologramBlueprintProcessing.Policy policy;
        final int index;

        CellTask(HologramPresets.Preset source, HologramBlueprintProcessing.Policy policy, int index, int offset,
            int limit) throws IOException {
            super(offset, limit);
            this.source = source;
            this.policy = policy;
            this.index = index;
        }

        public boolean step(int maxWork, long deadline) throws IOException {
            owner();
            if (done) return true;
            HologramProcessGovernor.Lease cpu = HologramProcessGovernor.tryHeavyCpu("details.cell-owner");
            if (cpu == null) return false;
            try {
                HologramPresets.Cell cell = source.cells.get(index);
                HologramBlueprintProcessing.CellNbtOverride selected = null;
                if (policy != null) for (HologramBlueprintProcessing.CellNbtOverride choice : policy.cellNbtOverrides)
                    if (choice.x == cell.x && choice.y == cell.y && choice.z == cell.z) {
                        selected = choice;
                        break;
                    }
                List<String> chosen = selected == null || selected.all ? null : selected.keys;
                if (stringsBytes(chosen) + stringsBytes(selected == null ? null : selected.ignoredPaths)
                    > PAGE_ROWS_BYTES - 1024) {
                    output.setString("detailError", "rowTooLarge");
                    done = true;
                    return true;
                }
                Set<String> keys = chosen == null ? null : new HashSet<>(chosen);
                NBTTagCompound metadata = new NBTTagCompound();
                metadata.setInteger("index", index);
                metadata.setBoolean("all", chosen == null);
                metadata.setTag(
                    "keys",
                    HologramBlueprintProcessing.strings(chosen == null ? java.util.Collections.emptyList() : chosen));
                metadata.setTag(
                    "ignoredPaths",
                    HologramBlueprintProcessing
                        .strings(selected == null ? java.util.Collections.emptyList() : selected.ignoredPaths));
                long metadataBytes = HologramSnapshotNbt.estimate(metadata);
                if (metadataBytes > PAGE_ROWS_BYTES) {
                    output.setString("detailError", "rowTooLarge");
                } else {
                    NBTTagCompound page = HologramBlueprintProcessing
                        .nbtEntryPage(cell, facts, offset, limit, PAGE_ROWS_BYTES - metadataBytes, keys);
                    output.setTag("cellMeta", metadata);
                    for (Object raw : page.func_150296_c()) output.setTag((String) raw, page.getTag((String) raw));
                }
                output.setInteger("offset", offset);
                output.setBoolean("complete", true);
                done = true;
                return true;
            } finally {
                cpu.close();
            }
        }
    }

    private static long stringsBytes(List<String> strings) {
        long bytes = 128;
        if (strings != null) for (String value : strings) bytes += 144L + 2L * value.length();
        return bytes;
    }

    private HologramProcessingDetails() {}
}
