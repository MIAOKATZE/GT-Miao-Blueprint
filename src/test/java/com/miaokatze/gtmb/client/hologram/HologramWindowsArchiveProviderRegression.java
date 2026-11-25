// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.BorrowedRoot;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Capability;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Driver;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Handle;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Held;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Identity;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Info;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Kind;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.OpenMode;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Opened;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Refusal;
import com.miaokatze.gtmb.client.hologram.HologramWindowsArchiveProvider.Rejected;
import com.miaokatze.gtmb.hologram.HologramArchiveGeometryReader;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Virtual contract only. No Windows execution, native linking, or file/path open. */
public final class HologramWindowsArchiveProviderRegression {

    private static final String LEAF = "十二塔.gtpg";
    private static final Identity ROOT = new Identity(41, 0, 100);
    private static final Identity LIBRARY = new Identity(41, 0, 200);
    private static final Identity FILE = new Identity(41, 1, 300);
    private static final Identity OTHER = new Identity(41, 2, 301);

    private HologramWindowsArchiveProviderRegression() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        run();
    }

    public static void run() throws IOException, InterruptedException {
        heldReadOnlyAndBounded();
        earlyRefusals();
        exactDescriptorRefusals();
        cancellationsDoNotPublish();
        reentryCannotPublish();
        workerAndClose();
    }

    private static void heldReadOnlyAndBounded() throws IOException {
        Fake os = new Fake();
        try (var held = HologramWindowsArchiveProvider.open(os.loan(), LEAF, () -> true)) {
            SimpleAssert.eq(2, os.relativeOpens, "only MiaoBlueprints + explicit leaf are opened");
            SimpleAssert.eq((long) os.data.length, held.size(), "length is from that exact held regular file");
            SimpleAssert
                .eq(Collections.singletonList(os.library), os.closed, "transient parent retired before transfer");
            ByteBuffer bytes = ByteBuffer.allocate(HologramWindowsArchiveProvider.MAX_READ * 2);
            held.position(100);
            int read = held.read(bytes);
            SimpleAssert.eq(HologramWindowsArchiveProvider.MAX_READ, read, "hard transfer cap from large destination");
            SimpleAssert.eq(100L, os.lastOffset, "explicit offset belongs to that file only");
            SimpleAssert.eq(100L + read, held.position(), "cursor advances only after the successful bounded copy");
            SimpleAssert.eq((int) os.data[100], (int) bytes.get(0), "file bytes as read, not an invented manifest");
            held.position(held.size());
            SimpleAssert.eq(-1, held.read(bytes), "exact end of file");
            SimpleAssert.eq(1, os.reads, "EOF requires no native read");
            try {
                held.write(ByteBuffer.allocate(1));
                throw new AssertionError("A selected archive is never writable");
            } catch (NonWritableChannelException expected) {}
            try {
                held.truncate(0);
                throw new AssertionError("A selected archive is never truncatable");
            } catch (NonWritableChannelException expected) {}
        }
        SimpleAssert.eq(Arrays.asList(os.library, os.file), os.closed, "only exact owned handles closed once");

        Fake localKnown = new Fake();
        try (var held = HologramWindowsArchiveProvider.open(localKnown.loan(), LEAF, FILE, () -> true)) {
            SimpleAssert.that(held.isOpen(), "optional independent file ID can restrict local authority");
        }
        Fake malformed = new Fake();
        var held = HologramWindowsArchiveProvider.open(malformed.loan(), LEAF, () -> true);
        try {
            HologramArchiveGeometryReader.inspectExplicitIndex(held, () -> true);
            throw new AssertionError("OS file identity is not archive-content approval");
        } catch (IOException expected) {
            SimpleAssert.that(
                !held.isOpen() && malformed.closed.equals(Arrays.asList(malformed.library, malformed.file)),
                "arbitrary held bytes fail the separate parser and close exact leaf");
        }
    }

    private static void earlyRefusals() throws IOException {
        Fake os = new Fake();
        for (String invalid : new String[] { "../outside.gtpg", "C:/absolute.gtpg", "file:stream.gtpg", ".gtpg",
            "AUX.work.gtpg", "LPT².gtpg", "COM1 .gtpg" }) {
            expect(Refusal.INVALID_LEAF, () -> HologramWindowsArchiveProvider.open(os.loan(), invalid, () -> true));
        }
        SimpleAssert.eq(0, os.capabilityCalls, "no OS call for traversal, stream or reserved DOS leaf");
        expect(Refusal.NO_PROVIDER, () -> HologramWindowsArchiveProvider.open(null, LEAF, () -> true));
        expect(Refusal.STALE_OWNER, () -> HologramWindowsArchiveProvider.open(os.loan(), LEAF, null));
        for (Capability cap : Capability.values()) {
            if (cap == Capability.READY) continue;
            os.cap = cap;
            expect(Refusal.valueOf(cap.name()), () -> HologramWindowsArchiveProvider.open(os.loan(), LEAF, () -> true));
        }
        SimpleAssert.eq(0, os.descriptions, "unsupported provider cannot even inspect the borrowed root");
        SimpleAssert.eq(0, os.relativeOpens, "unsupported provider cannot touch an archive");
        os.cap = Capability.READY;
        os.rootInfo = info(os.root, OTHER, Kind.DIRECTORY, true, false, false, 1, 0);
        expect(Refusal.BAD_ROOT, () -> HologramWindowsArchiveProvider.open(os.loan(), LEAF, () -> true));
        SimpleAssert
            .that(os.closed.isEmpty() && os.relativeOpens == 0, "wrong independent root is never used or closed");
    }

    private static void exactDescriptorRefusals() throws IOException {
        Fake wrongFile = new Fake();
        expect(
            Refusal.WRONG_HANDLE,
            () -> HologramWindowsArchiveProvider.open(wrongFile.loan(), LEAF, OTHER, () -> true));
        refusedAfterLeaf(wrongFile);

        Fake reparse = new Fake();
        reparse.libraryInfo = info(reparse.library, LIBRARY, Kind.DIRECTORY, true, true, false, 1, 0);
        expect(Refusal.REPARSE_POINT, () -> HologramWindowsArchiveProvider.open(reparse.loan(), LEAF, () -> true));
        SimpleAssert.eq(Collections.singletonList(reparse.library), reparse.closed, "never descend through reparse");
        SimpleAssert.eq(1, reparse.relativeOpens, "leaf never opened after wrong ancestor");

        Fake movedParent = new Fake();
        movedParent.afterFileOpen = () -> movedParent.libraryInfo = info(
            movedParent.library,
            OTHER,
            Kind.DIRECTORY,
            true,
            false,
            false,
            1,
            0);
        expect(Refusal.WRONG_HANDLE, () -> HologramWindowsArchiveProvider.open(movedParent.loan(), LEAF, () -> true));
        refusedAfterLeaf(movedParent);

        Fake guessed = new Fake();
        guessed.wrongParent = true;
        expect(Refusal.WRONG_HANDLE, () -> HologramWindowsArchiveProvider.open(guessed.loan(), LEAF, () -> true));
        refusedAfterLeaf(guessed);

        Fake aliased = new Fake();
        aliased.aliasRootAtLeaf = true;
        expect(Refusal.WRONG_HANDLE, () -> HologramWindowsArchiveProvider.open(aliased.loan(), LEAF, () -> true));
        SimpleAssert
            .eq(Collections.singletonList(aliased.library), aliased.closed, "borrowed root never closed on alias");

        Fake wrongDescriptor = new Fake();
        wrongDescriptor.fileInfo = info(wrongDescriptor.library, FILE, Kind.FILE, true, false, false, 1, 120);
        expect(
            Refusal.WRONG_HANDLE,
            () -> HologramWindowsArchiveProvider.open(wrongDescriptor.loan(), LEAF, () -> true));
        refusedAfterLeaf(wrongDescriptor);

        Fake remote = new Fake();
        remote.fileInfo = info(remote.file, FILE, Kind.FILE, false, false, false, 1, 120);
        expect(Refusal.NOT_LOCAL_REGULAR, () -> HologramWindowsArchiveProvider.open(remote.loan(), LEAF, () -> true));
        refusedAfterLeaf(remote);

        Fake placeholder = new Fake();
        placeholder.fileInfo = info(placeholder.file, FILE, Kind.FILE, true, false, true, 1, 120);
        expect(
            Refusal.NOT_LOCAL_REGULAR,
            () -> HologramWindowsArchiveProvider.open(placeholder.loan(), LEAF, () -> true));
        refusedAfterLeaf(placeholder);

        Fake linked = new Fake();
        linked.fileInfo = info(linked.file, FILE, Kind.FILE, true, false, false, 2, 120);
        expect(Refusal.NOT_LOCAL_REGULAR, () -> HologramWindowsArchiveProvider.open(linked.loan(), LEAF, () -> true));
        refusedAfterLeaf(linked);

        Fake oversized = new Fake();
        oversized.fileInfo = info(
            oversized.file,
            FILE,
            Kind.FILE,
            true,
            false,
            false,
            1,
            HologramWindowsArchiveProvider.MAX_BYTES + 1);
        expect(Refusal.SIZE_LIMIT, () -> HologramWindowsArchiveProvider.open(oversized.loan(), LEAF, () -> true));
        refusedAfterLeaf(oversized);
    }

    private static void cancellationsDoNotPublish() throws IOException {
        AtomicBoolean current = new AtomicBoolean(true);
        Fake first = new Fake();
        first.afterLibraryOpen = () -> current.set(false);
        expect(Refusal.STALE_OWNER, () -> HologramWindowsArchiveProvider.open(first.loan(), LEAF, current::get));
        SimpleAssert
            .eq(Collections.singletonList(first.library), first.closed, "cancel in first open retires new parent");

        current.set(true);
        Fake second = new Fake();
        second.afterFileOpen = () -> current.set(false);
        expect(Refusal.STALE_OWNER, () -> HologramWindowsArchiveProvider.open(second.loan(), LEAF, current::get));
        refusedAfterLeaf(second);

        current.set(true);
        Fake closingParent = new Fake();
        closingParent.afterLibraryClose = () -> current.set(false);
        expect(
            Refusal.STALE_OWNER,
            () -> HologramWindowsArchiveProvider.open(closingParent.loan(), LEAF, current::get));
        SimpleAssert.eq(
            Arrays.asList(closingParent.library, closingParent.file),
            closingParent.closed,
            "cancel during planned parent close never hands the leaf out");

        current.set(true);
        Fake reading = new Fake();
        try (var held = HologramWindowsArchiveProvider.open(reading.loan(), LEAF, current::get)) {
            reading.afterRead = () -> current.set(false);
            ByteBuffer output = ByteBuffer.wrap(new byte[] { 91, 92, 93 });
            expect(Refusal.STALE_OWNER, () -> held.read(output));
            SimpleAssert.that(
                output.position() == 0 && output.get(0) == 91 && !held.isOpen(),
                "stale native read is staged; destination/position stay intact");
        }
        SimpleAssert.eq(Arrays.asList(reading.library, reading.file), reading.closed, "cancelled file closes once");

        Fake changed = new Fake();
        try (var held = HologramWindowsArchiveProvider.open(changed.loan(), LEAF, () -> true)) {
            changed.afterRead = () -> changed.fileInfo = info(
                changed.file,
                OTHER,
                Kind.FILE,
                true,
                false,
                false,
                1,
                changed.data.length);
            ByteBuffer output = ByteBuffer.wrap(new byte[] { 83 });
            expect(Refusal.WRONG_HANDLE, () -> held.read(output));
            SimpleAssert.that(
                output.position() == 0 && output.get(0) == 83 && !held.isOpen(),
                "postread exact identity mismatch cannot publish even a partial byte");
        }

        Fake replacedRoot = new Fake();
        try (var held = HologramWindowsArchiveProvider.open(replacedRoot.loan(), LEAF, () -> true)) {
            replacedRoot.afterRead = () -> replacedRoot.rootInfo = info(
                replacedRoot.root,
                OTHER,
                Kind.DIRECTORY,
                true,
                false,
                false,
                1,
                0);
            ByteBuffer output = ByteBuffer.allocate(3);
            expect(Refusal.BAD_ROOT, () -> held.read(output));
            SimpleAssert.that(output.position() == 0 && !held.isOpen(), "borrowed root is still exact during read");
        }
    }

    private static void reentryCannotPublish() throws IOException {
        Fake finalDescribe = new Fake();
        try (var held = HologramWindowsArchiveProvider.open(finalDescribe.loan(), LEAF, () -> true)) {
            finalDescribe.afterFileDescribe = () -> {
                if (finalDescribe.reads != 0) logicalClose(held, finalDescribe);
            };
            ByteBuffer out = ByteBuffer.wrap(new byte[] { 101, 102, 103 });
            out.position(1);
            expectClosed(() -> held.read(out));
            SimpleAssert.that(
                out.position() == 1 && out.get(1) == 102 && out.get(2) == 103,
                "close from final descriptor callback cannot publish staged bytes");
        }
        SimpleAssert.eq(
            Arrays.asList(finalDescribe.library, finalDescribe.file),
            finalDescribe.closed,
            "descriptor reentry still hands off exact file once after callback unwinds");

        Fake authority = new Fake();
        var opened = new AtomicReference<Held>();
        var closeOnPostcheck = new AtomicBoolean();
        try (var held = HologramWindowsArchiveProvider.open(authority.loan(), LEAF, () -> {
            if (closeOnPostcheck.get()) {
                logicalClose(opened.get(), authority);
                return false; // Closed result takes precedence even when this callback also revokes the scope.
            }
            return true;
        })) {
            opened.set(held);
            authority.afterFileDescribe = () -> { if (authority.reads != 0) closeOnPostcheck.set(true); };
            ByteBuffer out = ByteBuffer.wrap(new byte[] { 81 });
            expectClosed(() -> held.read(out));
            SimpleAssert.that(
                out.position() == 0 && out.get(0) == 81 && !held.isOpen(),
                "close from final authority callback leaves caller buffer unchanged");
        }
        SimpleAssert.eq(
            Arrays.asList(authority.library, authority.file),
            authority.closed,
            "authority reentry hands off original once");

        Fake nested = new Fake();
        try (var held = HologramWindowsArchiveProvider.open(nested.loan(), LEAF, () -> true)) {
            nested.afterRead = () -> {
                int describes = nested.descriptions;
                ByteBuffer untouched = ByteBuffer.wrap(new byte[] { 76 });
                try {
                    expect(Refusal.REENTRANT_OPERATION, () -> held.read(untouched));
                    expect(Refusal.REENTRANT_OPERATION, held::size);
                    expect(Refusal.REENTRANT_OPERATION, held::position);
                } catch (IOException unexpected) {
                    throw new AssertionError(unexpected);
                }
                SimpleAssert.that(
                    nested.descriptions == describes && untouched.position() == 0 && untouched.get(0) == 76,
                    "nested owner operation cannot inspect or overwrite caller storage");
            };
            SimpleAssert
                .eq(1, held.read(ByteBuffer.allocate(1)), "refused reentry does not cancel the original operation");
            SimpleAssert.eq(1, nested.reads, "exactly one underlying read");
        }
    }

    private static void logicalClose(Held held, Fake driver) {
        try {
            held.close();
            SimpleAssert.that(
                !held.isOpen() && !driver.closed.contains(driver.file),
                "logically closed now, physical handoff deferred until callback returns");
        } catch (IOException unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private static void expectClosed(Work action) throws IOException {
        try {
            action.run();
        } catch (ClosedChannelException expected) {
            return;
        }
        throw new AssertionError("Expected deterministic closed operation");
    }

    private static void workerAndClose() throws IOException, InterruptedException {
        Fake os = new Fake();
        try (var held = HologramWindowsArchiveProvider.open(os.loan(), LEAF, () -> true)) {
            int descriptions = os.descriptions;
            var problem = new AtomicReference<Throwable>();
            Thread foreign = new Thread(() -> {
                try {
                    expect(Refusal.STALE_OWNER, () -> held.read(ByteBuffer.allocate(1)));
                    expect(Refusal.STALE_OWNER, held::close);
                } catch (Throwable failure) {
                    problem.set(failure);
                }
            });
            foreign.start();
            foreign.join();
            SimpleAssert.that(
                problem.get() == null && os.descriptions == descriptions && os.reads == 0 && held.isOpen(),
                "foreign worker cannot inspect, read, or close native handle");
            os.failFileClose = true;
            try {
                held.close();
                throw new AssertionError("native close failure should be visible");
            } catch (IOException expected) {
                SimpleAssert.that(!held.isOpen(), "close consumes ownership even if native close failed");
            }
            held.close();
        }
        SimpleAssert.eq(Arrays.asList(os.library, os.file), os.closed, "a failed handoff is not retried by the lease");
        SimpleAssert.eq(Collections.singletonList(os.file), os.closeDebt, "driver owns unconfirmed physical close");

        Fake oversizedRead = new Fake();
        try (var held = HologramWindowsArchiveProvider.open(oversizedRead.loan(), LEAF, () -> true)) {
            oversizedRead.badReadResult = true;
            ByteBuffer output = ByteBuffer.wrap(new byte[] { 65 });
            expect(Refusal.IO, () -> held.read(output));
            SimpleAssert.that(
                output.position() == 0 && output.get(0) == 65 && !held.isOpen(),
                "impossible native count never escapes the private buffer");
        }
    }

    private static void refusedAfterLeaf(Fake os) {
        SimpleAssert.eq(Arrays.asList(os.file, os.library), os.closed, "refusal rolls back exact leaf then parent");
        SimpleAssert.eq(0, os.reads, "refusal does not touch archive content");
    }

    @FunctionalInterface
    private interface Work {

        void run() throws IOException;
    }

    private static void expect(Refusal reason, Work work) throws IOException {
        try {
            work.run();
        } catch (Rejected rejected) {
            SimpleAssert.eq(reason, rejected.reason, "specific fail-closed reason");
            return;
        }
        throw new AssertionError("Expected refusal: " + reason);
    }

    private static Info info(Handle h, Identity id, Kind kind, boolean disk, boolean reparse, boolean pending,
        int links, long size) {
        return new Info(h, id, kind, disk, reparse, pending, links, size);
    }

    private static final class Node implements Handle {
    }

    private static final class Fake implements Driver {

        final Handle root = new Node(), library = new Node(), file = new Node();
        final byte[] data = new byte[40_000];
        final List<Handle> closed = new ArrayList<>();
        final List<Handle> closeDebt = new ArrayList<>();
        final AtomicBoolean alive = new AtomicBoolean(true);
        Capability cap = Capability.READY;
        Info rootInfo = info(root, ROOT, Kind.DIRECTORY, true, false, false, 1, 0);
        Info libraryInfo = info(library, LIBRARY, Kind.DIRECTORY, true, false, false, 1, 0);
        Info fileInfo = info(file, FILE, Kind.FILE, true, false, false, 1, data.length);
        boolean wrongParent, aliasRootAtLeaf, failFileClose, badReadResult, callbackActive;
        int relativeOpens, capabilityCalls, descriptions, reads;
        long lastOffset;
        Runnable afterLibraryOpen, afterFileOpen, afterLibraryClose, afterRead, afterFileDescribe;

        Fake() {
            for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        }

        BorrowedRoot loan() {
            return HologramWindowsArchiveProvider.borrow(this, root, ROOT, alive::get);
        }

        @Override
        public Capability capability() {
            capabilityCalls++;
            return cap;
        }

        @Override
        public Opened openRelative(Handle directory, String component, OpenMode mode) {
            relativeOpens++;
            if (directory == root) {
                SimpleAssert.that(
                    "MiaoBlueprints".equals(component) && mode == OpenMode.DIRECTORY,
                    "cannot resolve a caller-specified component at the root");
                if (afterLibraryOpen != null) afterLibraryOpen.run();
                return new Opened(root, library, component, mode);
            }
            SimpleAssert.that(
                directory == library && LEAF.equals(component) && mode == OpenMode.FILE && !closed.contains(library),
                "explicit leaf must be relative to the exact open ancestor");
            if (afterFileOpen != null) afterFileOpen.run();
            return new Opened(wrongParent ? root : library, aliasRootAtLeaf ? root : file, component, mode);
        }

        @Override
        public Info describe(Handle exact) {
            SimpleAssert.that(!closed.contains(exact), "only live held objects may be described");
            descriptions++;
            if (exact == root) return rootInfo;
            if (exact == library) return libraryInfo;
            if (exact == file) {
                callback(afterFileDescribe);
                return fileInfo;
            }
            throw new AssertionError("unknown handle");
        }

        @Override
        public int read(Handle exact, long offset, byte[] scratch, int count) {
            SimpleAssert.that(
                exact == file && !closed.contains(file) && count <= HologramWindowsArchiveProvider.MAX_READ,
                "only exact original owned file gets bounded read");
            reads++;
            lastOffset = offset;
            System.arraycopy(data, (int) offset, scratch, 0, count);
            callback(afterRead);
            return badReadResult ? count + 1 : count;
        }

        @Override
        public void close(Handle exact) throws IOException {
            SimpleAssert.that(
                exact != root && !closed.contains(exact) && !callbackActive,
                "no borrowed-root, duplicate or reentrant physical close");
            closed.add(exact);
            if (exact == library && afterLibraryClose != null) afterLibraryClose.run();
            if (exact == file && failFileClose) {
                closeDebt.add(exact);
                throw new IOException("virtual native close unconfirmed; driver retains debt");
            }
        }

        private void callback(Runnable hook) {
            if (hook == null) return;
            SimpleAssert.that(!callbackActive, "unexpected nested driver callback");
            callbackActive = true;
            try {
                hook.run();
            } finally {
                callbackActive = false;
            }
        }
    }
}
