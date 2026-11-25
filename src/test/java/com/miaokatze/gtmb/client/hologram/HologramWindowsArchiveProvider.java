// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Dormant Win64 handle-authority contract. No native driver, path opener, caller, or content authentication. */
final class HologramWindowsArchiveProvider {

    static final int MAX_READ = 16 * 1024;
    static final long MIN_BYTES = 116, MAX_BYTES = 19_670_072;
    private static final String LIBRARY = "MiaoBlueprints";

    enum Capability {
        READY,
        UNSUPPORTED_OS,
        ABI_UNVERIFIED,
        NATIVE_ACCESS_UNAVAILABLE,
        SYSTEM_MODULE_UNVERIFIED
    }

    enum Refusal {
        NO_PROVIDER,
        UNSUPPORTED_OS,
        ABI_UNVERIFIED,
        NATIVE_ACCESS_UNAVAILABLE,
        SYSTEM_MODULE_UNVERIFIED,
        INVALID_LEAF,
        STALE_OWNER,
        REENTRANT_OPERATION,
        BAD_ROOT,
        WRONG_HANDLE,
        NOT_LOCAL_REGULAR,
        REPARSE_POINT,
        SIZE_LIMIT,
        IO
    }

    enum Kind {
        DIRECTORY,
        FILE,
        OTHER
    }

    /** These are semantics, not numeric NT flags. A native implementation must enforce them in the actual open. */
    enum OpenMode {
        DIRECTORY,
        FILE
    }

    /** The driver uses one stable Java reference per actual acquired OS handle. */
    interface Handle {
    }

    /**
     * A vetted implementation must issue only a single exact-component RootDirectory-relative FILE_OPEN with
     * no reparsing, an explicit directory/non-directory contract, read-only access, and no write/delete sharing.
     * No backup privilege, default directory, open-by-id, or absolute path. If an operation throws before returning
     * a fresh owned child, the driver must close any partial acquisition itself. Descriptions apply to that exact
     * held OS object, never to a name checked separately. Reads use the held handle and explicit bounded offset.
     * unavailableData includes offline/recall placeholders and delete-pending objects. Do not reenter the lease.
     * Cancellation is cooperative between bounded callbacks, not an atomic OS I/O deadline.
     */
    interface Driver {

        Capability capability();

        Opened openRelative(Handle directory, String component, OpenMode mode) throws IOException;

        Info describe(Handle exact) throws IOException;

        int read(Handle exact, long offset, byte[] scratch, int count) throws IOException;

        /**
         * One ownership handoff of the exact handle. If physical close cannot be confirmed, the driver owns any
         * remaining close debt even when it throws. The provider reports the failure without retrying/double close.
         */
        void close(Handle owned) throws IOException;
    }

    static final class Identity {

        final long volume, high, low;

        Identity(long volume, long high, long low) {
            this.volume = volume;
            this.high = high;
            this.low = low;
        }

        boolean same(Identity other) {
            return other != null && volume == other.volume && high == other.high && low == other.low;
        }
    }

    static final class Opened {

        final Handle parent, child;
        final String component;
        final OpenMode mode;

        Opened(Handle parent, Handle child, String component, OpenMode mode) {
            this.parent = parent;
            this.child = child;
            this.component = component;
            this.mode = mode;
        }
    }

    static final class Info {

        final Handle handle;
        final Identity identity;
        final Kind kind;
        final boolean localDisk, reparse, unavailableData;
        final int links;
        final long size;

        Info(Handle handle, Identity identity, Kind kind, boolean localDisk, boolean reparse, boolean unavailableData,
            int links, long size) {
            this.handle = handle;
            this.identity = identity;
            this.kind = kind;
            this.localDisk = localDisk;
            this.reparse = reparse;
            this.unavailableData = unavailableData;
            this.links = links;
            this.size = size;
        }
    }

    static final class Rejected extends IOException {

        private static final long serialVersionUID = 1L;

        final Refusal reason;

        Rejected(Refusal reason) {
            this(reason, null);
        }

        private Rejected(Refusal reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = reason;
        }
    }

    static final class BorrowedRoot {

        private final Driver driver;
        private final Handle directory;
        private final Identity expected;
        private final Thread worker = Thread.currentThread();
        private final BooleanSupplier current;

        private BorrowedRoot(Driver driver, Handle directory, Identity expected, BooleanSupplier current) {
            this.driver = driver;
            this.directory = Objects.requireNonNull(directory);
            this.expected = Objects.requireNonNull(expected);
            this.current = Objects.requireNonNull(current);
        }
    }

    private HologramWindowsArchiveProvider() {}

    /**
     * Only the installed-game-directory authority may make this loan, on the native worker. The expected identity
     * is from its independent vetted root, not copied from the candidate described by open. It keeps the root handle
     * live until borrowers return and signals retirement via current. Owner queries should be side-effect-free.
     * This provider never closes the borrowed root.
     */
    static BorrowedRoot borrow(Driver driver, Handle exactVettedDirectory, Identity expected, BooleanSupplier current) {
        return new BorrowedRoot(driver, exactVettedDirectory, expected, current);
    }

    static Held open(BorrowedRoot root, String leaf, BooleanSupplier current) throws IOException {
        return open(root, leaf, null, current);
    }

    /** An independent file ID may further restrict the loan. Local selection need not reopen a path to invent one. */
    static Held open(BorrowedRoot root, String leaf, Identity expectedFile, BooleanSupplier current)
        throws IOException {
        if (!validLeaf(leaf)) throw new Rejected(Refusal.INVALID_LEAF);
        if (root == null || root.driver == null) throw new Rejected(Refusal.NO_PROVIDER);
        if (current == null) throw new Rejected(Refusal.STALE_OWNER);
        var scope = new Scope(root, current);
        try {
            scope.check();
            Capability capability = root.driver.capability();
            scope.check();
            if (capability != Capability.READY) throw unsupported(capability);
            try (var acquired = new Acquired(root.driver, root.directory)) {
                scope.rootInfo();
                Opened library = root.driver.openRelative(root.directory, LIBRARY, OpenMode.DIRECTORY);
                acquired.library = acquired.fresh(library, null);
                scope.check();
                checkRelation(library, root.directory, LIBRARY, OpenMode.DIRECTORY);
                Info directory = scope.info(acquired.library, Kind.DIRECTORY);
                if (directory.identity.same(root.expected)) throw new Rejected(Refusal.WRONG_HANDLE);

                scope.check();
                Opened entry = root.driver.openRelative(acquired.library, leaf, OpenMode.FILE);
                acquired.file = acquired.fresh(entry, acquired.library);
                scope.check();
                checkRelation(entry, acquired.library, leaf, OpenMode.FILE);
                Info file = scope.fileInfo(acquired.file);
                if (file.identity.same(root.expected) || file.identity.same(directory.identity)
                    || expectedFile != null && !expectedFile.same(file.identity)
                    || !directory.identity.same(scope.info(acquired.library, Kind.DIRECTORY).identity))
                    throw new Rejected(Refusal.WRONG_HANDLE);

                // Closing the transient ancestor may also return after this owner was cancelled. No transfer yet.
                acquired.closeLibrary();
                scope.rootInfo();
                scope.checkExactFile(acquired.file, file.identity, file.size);
                Held result = new Held(scope, acquired.file, file.identity, file.size);
                acquired.file = null; // Only here may the exact leaf move to the reader; it remains untrusted data.
                return result;
            }
        } catch (Rejected specific) {
            throw specific;
        } catch (IOException | RuntimeException failed) {
            throw new Rejected(Refusal.IO, failed);
        }
    }

    private static Rejected unsupported(Capability capability) {
        if (capability == null) return new Rejected(Refusal.NO_PROVIDER);
        return new Rejected(Refusal.valueOf(capability.name()));
    }

    private static boolean validLeaf(String leaf) {
        if (!HologramArchiveNamedImport.validLeaf(leaf)) return false;
        String stem = leaf.substring(0, leaf.indexOf('.'))
            .trim()
            .toUpperCase(Locale.ROOT);
        if ("CON".equals(stem) || "PRN".equals(stem) || "AUX".equals(stem) || "NUL".equals(stem)) return false;
        return stem.length() != 4 || !(stem.startsWith("COM") || stem.startsWith("LPT"))
            || "0123456789¹²³".indexOf(stem.charAt(3)) < 0;
    }

    private static void checkRelation(Opened proof, Handle parent, String component, OpenMode mode) throws IOException {
        if (proof == null || proof.child == null
            || proof.parent != parent
            || !component.equals(proof.component)
            || proof.mode != mode) throw new Rejected(Refusal.WRONG_HANDLE);
    }

    @FunctionalInterface
    private interface Guard {

        void check() throws IOException;
    }

    private static final Guard NO_LEASE = () -> {};

    private static final class Scope {

        final BorrowedRoot root;
        final BooleanSupplier request;

        Scope(BorrowedRoot root, BooleanSupplier request) {
            this.root = root;
            this.request = request;
        }

        void check() throws IOException {
            check(NO_LEASE);
        }

        void check(Guard lease) throws IOException {
            lease.check();
            if (Thread.currentThread() != root.worker) throw new Rejected(Refusal.STALE_OWNER);
            boolean rootLive = root.current.getAsBoolean();
            lease.check();
            if (!rootLive) throw new Rejected(Refusal.STALE_OWNER);
            boolean requestLive = request.getAsBoolean();
            lease.check();
            if (!requestLive) throw new Rejected(Refusal.STALE_OWNER);
        }

        void rootInfo() throws IOException {
            rootInfo(NO_LEASE);
        }

        void rootInfo(Guard lease) throws IOException {
            Info actual = info(root.directory, Kind.DIRECTORY, lease);
            if (!root.expected.same(actual.identity)) throw new Rejected(Refusal.BAD_ROOT);
        }

        Info info(Handle handle, Kind exactKind) throws IOException {
            return info(handle, exactKind, NO_LEASE);
        }

        Info info(Handle handle, Kind exactKind, Guard lease) throws IOException {
            check(lease);
            Info actual = root.driver.describe(handle);
            check(lease);
            if (actual == null || actual.handle != handle || actual.identity == null)
                throw new Rejected(Refusal.WRONG_HANDLE);
            if (actual.reparse) throw new Rejected(Refusal.REPARSE_POINT);
            if (!actual.localDisk || actual.kind != exactKind || actual.unavailableData)
                throw new Rejected(Refusal.NOT_LOCAL_REGULAR);
            if (actual.identity.volume != root.expected.volume) throw new Rejected(Refusal.BAD_ROOT);
            return actual;
        }

        Info fileInfo(Handle handle) throws IOException {
            return fileInfo(handle, NO_LEASE);
        }

        Info fileInfo(Handle handle, Guard lease) throws IOException {
            Info actual = info(handle, Kind.FILE, lease);
            if (actual.links != 1) throw new Rejected(Refusal.NOT_LOCAL_REGULAR);
            if (actual.size < MIN_BYTES || actual.size > MAX_BYTES) throw new Rejected(Refusal.SIZE_LIMIT);
            return actual;
        }

        void checkExactFile(Handle handle, Identity identity, long bytes) throws IOException {
            checkExactFile(handle, identity, bytes, NO_LEASE);
        }

        void checkExactFile(Handle handle, Identity identity, long bytes, Guard lease) throws IOException {
            Info actual = fileInfo(handle, lease);
            if (!identity.same(actual.identity) || bytes != actual.size) throw new Rejected(Refusal.WRONG_HANDLE);
        }
    }

    private static final class Acquired implements AutoCloseable {

        final Driver driver;
        final Handle borrowed;
        Handle library, file;

        Acquired(Driver driver, Handle borrowed) {
            this.driver = driver;
            this.borrowed = borrowed;
        }

        Handle fresh(Opened opened, Handle alreadyOwned) throws IOException {
            Handle candidate = opened == null ? null : opened.child;
            if (candidate == null || candidate == borrowed || candidate == alreadyOwned)
                throw new Rejected(Refusal.WRONG_HANDLE);
            return candidate;
        }

        void closeLibrary() throws IOException {
            Handle owned = library;
            library = null;
            if (owned != null) driver.close(owned);
        }

        @Override
        public void close() throws IOException {
            Handle owned = file;
            file = null;
            // Parent still closes when the child close fails. Each callback sees an already consumed slot.
            if (owned == null) {
                closeLibrary();
                return;
            }
            try {
                driver.close(owned);
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    closeLibrary();
                } catch (IOException | RuntimeException | Error failedParent) {
                    if (failedParent != failure) failure.addSuppressed(failedParent);
                }
                throw failure;
            }
            closeLibrary();
        }
    }

    /** Exact held input only. Identity and size are OS observations, not approval of any archive bytes. */
    static final class Held implements SeekableByteChannel {

        private final Scope scope;
        private final Handle file;
        private final Identity identity;
        private final long bytes;
        private final byte[] scratch = new byte[MAX_READ];
        private final Guard opened = this::ensureOpen;
        private long cursor;
        private boolean operating, disposalHandedOff;
        private volatile boolean open = true;

        private Held(Scope scope, Handle file, Identity identity, long bytes) {
            this.scope = scope;
            this.file = file;
            this.identity = identity;
            this.bytes = bytes;
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            try (var ignored = begin()) {
                Objects.requireNonNull(target);
                if (target.isReadOnly()) throw new ReadOnlyBufferException();
                try {
                    pinned();
                    if (!target.hasRemaining()) return 0;
                    if (cursor == bytes) return -1;
                    int wanted = (int) Math.min(bytes - cursor, Math.min(target.remaining(), MAX_READ));
                    int copied = scope.root.driver.read(file, cursor, scratch, wanted);
                    pinned();
                    if (copied < 1 || copied > wanted) throw new Rejected(Refusal.IO);
                    // Last pure guard after all callbacks. A detected close cannot modify the destination.
                    ensureOpen();
                    target.put(scratch, 0, copied);
                    cursor += copied;
                    return copied;
                } catch (IOException | RuntimeException failure) {
                    throw closeAfter(failure);
                } catch (Error failure) {
                    retire(failure);
                    throw failure;
                }
            }
        }

        @Override
        public long position() throws IOException {
            try (var ignored = begin()) {
                ensureCurrent();
                return cursor;
            }
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            try (var ignored = begin()) {
                ensureCurrent();
                if (newPosition < 0 || newPosition > bytes)
                    throw new IllegalArgumentException("Archive seek outside file");
                cursor = newPosition;
                return this;
            }
        }

        @Override
        public long size() throws IOException {
            try (var ignored = begin()) {
                try {
                    pinned();
                    return bytes;
                } catch (IOException | RuntimeException failure) {
                    throw closeAfter(failure);
                } catch (Error failure) {
                    retire(failure);
                    throw failure;
                }
            }
        }

        @Override
        public int write(ByteBuffer ignored) {
            throw new NonWritableChannelException();
        }

        @Override
        public SeekableByteChannel truncate(long ignored) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            requireWorker();
            if (!open) return;
            open = false;
            if (!operating) handOff();
        }

        private Operation begin() throws IOException {
            requireWorker();
            ensureOpen();
            if (operating) throw new Rejected(Refusal.REENTRANT_OPERATION);
            Operation operation = new Operation();
            operating = true;
            return operation;
        }

        private final class Operation implements AutoCloseable {

            @Override
            public void close() throws IOException {
                operating = false;
                if (!open) handOff();
            }
        }

        private void handOff() throws IOException {
            if (disposalHandedOff) return;
            disposalHandedOff = true;
            scope.root.driver.close(file);
        }

        private void requireWorker() throws IOException {
            if (Thread.currentThread() != scope.root.worker) throw new Rejected(Refusal.STALE_OWNER);
        }

        private void ensureOpen() throws IOException {
            if (!open) throw new ClosedChannelException();
        }

        private void ensureCurrent() throws IOException {
            ensureOpen();
            try {
                scope.check(opened);
            } catch (IOException | RuntimeException failure) {
                throw closeAfter(failure);
            } catch (Error failure) {
                retire(failure);
                throw failure;
            }
        }

        private void pinned() throws IOException {
            scope.rootInfo(opened);
            scope.checkExactFile(file, identity, bytes, opened);
        }

        private IOException closeAfter(Exception problem) {
            IOException failure = problem instanceof Rejected || problem instanceof ClosedChannelException
                ? (IOException) problem
                : new Rejected(Refusal.IO, problem);
            retire(failure);
            return failure;
        }

        private void retire(Throwable failure) {
            try {
                close();
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
    }
}
