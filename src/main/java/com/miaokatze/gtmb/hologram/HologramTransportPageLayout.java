package com.miaokatze.gtmb.hologram;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Frozen transport identities only. Source-domain stamps never certify the current world/display DTO. */
final class HologramTransportPageLayout implements AutoCloseable {

    private static final int MAX_OWNERS = 64, MAX_FALLBACKS = 1024;
    private static final long OWNER_BYTES = 512L + 96L * MAX_FALLBACKS;
    private static final AtomicLong AUTHORITIES = new AtomicLong();
    private static final ReferenceQueue<HologramCapture> RETIRED = new ReferenceQueue<>();
    private static final Map<CaptureKey, Owner> OWNERS = new LinkedHashMap<>(16, 0.75f, true);
    private final int size;
    private Row[] rows;
    private HologramProcessGovernor.Lease lease;

    private HologramTransportPageLayout(Row[] rows, HologramProcessGovernor.Lease lease) {
        this.rows = rows;
        size = rows.length;
        this.lease = lease;
    }

    int size() {
        return size;
    }

    Row row(int index) {
        Row[] current = rows;
        if (current == null) throw new IllegalStateException("transport layout is closed");
        return current[index];
    }

    public synchronized void close() {
        rows = null;
        if (lease != null) lease.close();
        lease = null;
    }

    static HologramTransportPageLayout freeze(HologramSession session, NBTTagCompound fullSnapshot) {
        if (fullSnapshot == null || !fullSnapshot.hasKey("cells", 9))
            throw new IllegalArgumentException("transport layout requires a complete cells snapshot");
        NBTTagList cells = fullSnapshot.getTagList("cells", 10);
        try (Builder builder = new Builder(session, cells.tagCount())) {
            for (int index = 0; index < cells.tagCount(); index++) builder.add(index, cells.getCompoundTagAt(index));
            return builder.finish();
        }
    }

    static final class Row {

        final long authority, cellId, pageId;
        final HologramSegmentContract.Stamp report, mesh;

        Row(long authority, long cellId, HologramSegmentContract.Stamp report, HologramSegmentContract.Stamp mesh) {
            this.authority = authority;
            this.cellId = cellId;
            pageId = cellId > 0 ? (cellId - 1) / HologramSegmentContract.PAGE_SIZE
                : -1 - ((-cellId - 1) / HologramSegmentContract.PAGE_SIZE);
            this.report = report;
            this.mesh = mesh;
        }
    }

    /** Production fills this beside existing budgeted DTO rows; finish transfers its array without traversal. */
    static final class Builder implements AutoCloseable {

        private HologramCapture capture;
        private HologramSegmentDocument document;
        private final int x, y, z;
        private final long thread = Thread.currentThread()
            .getId();
        private Owner owner;
        private Row[] rows;
        private Set<Long> used;
        private int next;
        private HologramProcessGovernor.Lease lease;

        Builder(HologramSession session, int size) {
            this(
                session.id,
                session.capture,
                session.x,
                session.y,
                session.z,
                session.virtualPreset == null ? null : session.virtualPreset.segmentDocument,
                size);
        }

        /** Same owner-side input boundary, without a live world/player dependency in deterministic tests. */
        Builder(String session, HologramCapture capture, int x, int y, int z, HologramSegmentDocument document,
            int size) {
            if (session == null || size < 0
                || size > HologramCapture.LIMIT
                || (capture == null ? size != 0 : capture.cells.size() != size))
                throw new IllegalArgumentException("transport layout capture does not match complete rows");
            this.capture = capture;
            this.document = document;
            this.x = x;
            this.y = y;
            this.z = z;
            HologramProcessGovernor.Lease admitted = HologramProcessGovernor
                .tryRetain("transport.layout", 256L + 160L * size);
            if (admitted == null) throw new RejectedExecutionException("transport layout byte quota busy");
            lease = admitted.transfer();
            try {
                if (capture != null && size > 0)
                    owner = acquire(session, capture, document == null ? 1 : document.nextId);
                rows = new Row[size];
                used = new HashSet<>();
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        void add(int index, NBTTagCompound row) {
            requireOwner();
            if (rows == null || index != next || index >= rows.length || capture == null || row == null)
                throw new IllegalArgumentException("transport layout rows must be complete and ordered");
            HologramCapture.Cell cell = capture.cells.get(index);
            if (cell.x != row.getInteger("x") || cell.y != row.getInteger("y") || cell.z != row.getInteger("z"))
                throw new IllegalArgumentException("transport row no longer belongs to this capture index");
            Long priorFallback = owner.fallbacks.get(cell);
            long id = priorFallback == null ? cell.segmentCellId : priorFallback;
            if (priorFallback == null && id <= 0 && owner.nextId > 0 && owner.nextId < Long.MAX_VALUE) {
                id = owner.nextId++;
                cell.segmentCellId = id;
            }
            if (id > 0 && id >= owner.nextId) owner.nextId = id == Long.MAX_VALUE ? Long.MAX_VALUE : id + 1;
            if (id <= 0 || !used.add(id)) {
                // Positive IDs may already be P1 authorities. Never rewrite them to repair a conflict.
                id = owner.fallback(cell);
                if (!used.add(id)) throw new IllegalArgumentException("capture repeats the same cell object");
            }
            HologramSegmentContract.Stamp report = null, mesh = null;
            if (id > 0 && document != null) {
                HologramSegmentDocument.Record source = document.records.get(id);
                HologramSegmentDocument.Page page = document.pages.get((id - 1) / HologramSegmentContract.PAGE_SIZE);
                if (source != null && source.placed
                    && page != null
                    && source.at((long) cell.x - x, (long) cell.y - y, (long) cell.z - z)) {
                    report = page.stamp(HologramSegmentContract.Domain.REPORT);
                    mesh = page.stamp(HologramSegmentContract.Domain.MESH);
                }
            }
            rows[index] = new Row(owner.authority, id, report, mesh);
            next++;
        }

        Row row(int index) {
            requireOwner();
            if (rows == null || index < 0 || index >= next)
                throw new IllegalStateException("transport row is not frozen");
            return rows[index];
        }

        HologramTransportPageLayout finish() {
            requireOwner();
            if (rows == null || next != rows.length) throw new IllegalStateException("transport layout is incomplete");
            HologramTransportPageLayout result = new HologramTransportPageLayout(rows, lease);
            rows = null;
            lease = null;
            close();
            return result;
        }

        private void requireOwner() {
            if (Thread.currentThread()
                .getId() != thread)
                throw new IllegalStateException("transport identity freezing requires its capture owner thread");
        }

        public void close() {
            rows = null;
            used = null;
            document = null;
            capture = null;
            if (lease != null) lease.close();
            lease = null;
            if (owner != null) release(owner);
            owner = null;
        }
    }

    private static synchronized Owner acquire(String session, HologramCapture capture, long nextId) {
        reap();
        CaptureKey key = new CaptureKey(capture, null);
        Owner owner = OWNERS.get(key);
        if (owner != null && !owner.session.equals(session)) {
            OWNERS.remove(key);
            owner.retire();
            owner = null;
        }
        if (owner == null) {
            HologramProcessGovernor.Lease lease = HologramProcessGovernor
                .tryRetain("transport.layout-authority", OWNER_BYTES);
            if (lease == null) throw new RejectedExecutionException("transport identity byte quota busy");
            owner = new Owner(session, lease.transfer());
            if (OWNERS.size() >= MAX_OWNERS) {
                Iterator<Owner> iterator = OWNERS.values()
                    .iterator();
                Owner retired = iterator.next();
                iterator.remove();
                retired.retire();
            }
            OWNERS.put(new CaptureKey(capture, RETIRED), owner);
        }
        if (owner.thread != Thread.currentThread()
            .getId())
            throw new IllegalStateException("transport capture identities cannot be frozen on another thread");
        owner.nextId = Math.max(owner.nextId, nextId);
        owner.references++;
        return owner;
    }

    private static synchronized void release(Owner owner) {
        if (--owner.references < 0) throw new IllegalStateException("transport identity owner released twice");
        if (owner.retired && owner.references == 0) owner.lease.close();
    }

    static synchronized void clear() {
        for (Owner owner : OWNERS.values()) owner.retire();
        OWNERS.clear();
        reap();
    }

    private static void reap() {
        CaptureKey key;
        while ((key = (CaptureKey) RETIRED.poll()) != null) {
            Owner owner = OWNERS.remove(key);
            if (owner != null) owner.retire();
        }
    }

    private static final class Owner {

        final String session;
        final long authority = AUTHORITIES.incrementAndGet(), thread = Thread.currentThread()
            .getId();
        final HologramProcessGovernor.Lease lease;
        final Map<HologramCapture.Cell, Long> fallbacks = new WeakHashMap<>();
        long nextId = 1, nextFallback = -1;
        int references;
        boolean retired;

        Owner(String session, HologramProcessGovernor.Lease lease) {
            this.session = session;
            this.lease = lease;
        }

        long fallback(HologramCapture.Cell cell) {
            Long id = fallbacks.get(cell);
            if (id != null) return id;
            if (fallbacks.size() >= MAX_FALLBACKS)
                throw new RejectedExecutionException("transport identity conflict budget");
            long assigned = nextFallback--;
            fallbacks.put(cell, assigned);
            return assigned;
        }

        void retire() {
            retired = true;
            if (references == 0) lease.close();
        }
    }

    private static final class CaptureKey extends WeakReference<HologramCapture> {

        private final int hash;

        CaptureKey(HologramCapture capture, ReferenceQueue<HologramCapture> queue) {
            super(capture, queue);
            hash = System.identityHashCode(capture);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object value) {
            return this == value || value instanceof CaptureKey && get() != null && get() == ((CaptureKey) value).get();
        }
    }
}
