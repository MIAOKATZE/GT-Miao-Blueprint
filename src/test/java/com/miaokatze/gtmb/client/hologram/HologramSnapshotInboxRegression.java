package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual worker scheduling with controlled gates; no client world, registries or graphics context. */
public final class HologramSnapshotInboxRegression {

    private HologramSnapshotInboxRegression() {}

    public static void main(String[] args) {
        run();
        runQuotaContention();
        System.out.println("HologramSnapshotInboxRegression passed");
    }

    public static void run() {
        fifoAndBackpressure();
        epochCancellation();
        failedDecodeRetainsOrder();
        explicitRootCloseFence();
        networkBackpressureAndControlReserve();
        ownerDisplayAdmission();
        ownerOrdinaryRecovery();
        retiredActiveSlotRelease();
        contextSurvivesDecode();
    }

    private static void contextSurvivesDecode() {
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        AtomicInteger released = new AtomicInteger(), published = new AtomicInteger();
        com.miaokatze.gtmb.hologram.HologramSnapshotAssembly assembly = new com.miaokatze.gtmb.hologram.HologramSnapshotAssembly(
            1,
            sequence -> released.incrementAndGet());
        com.miaokatze.gtmb.hologram.HologramSnapshotAssembly.Complete frame = assembly
            .acceptReserved(5, 1, 0, new byte[] { 1 }, 0);
        java.util.function.BooleanSupplier context = current::get;
        frame.bindCurrent(context);
        SimpleAssert.that(
            frame.detachedContext() == context,
            "detached context is the original payload-free predicate, not Complete::current");
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(2, 16, bytes -> "old-origin")) {
            SimpleAssert.that(inbox.offer(frame.bytes, 5, frame, inbox.epoch(), null), "owned frame enters inbox");
            HologramSnapshotInbox.Result<String> result = inbox.awaitNext();
            SimpleAssert.that(result.current(), "decoded result keeps its admitted current origin");
            SimpleAssert.eq(1, released.get(), "poll returns exact credit once before delayed publication");
            assertDetachedContext(result, context, inbox);
            current.set(false);
            SimpleAssert.that(!result.current(), "connection transition invalidates already-decoded result");
            result.publish(value -> published.incrementAndGet());
            SimpleAssert.eq(0, published.get(), "publication itself refuses retired-origin result");
            result.close();
            SimpleAssert.eq(1, released.get(), "later disposal cannot duplicate old credit");
        }
    }

    /** Deterministic capture-shape proof: the retained Result owns only epoch + inbox + the original token. */
    private static void assertDetachedContext(HologramSnapshotInbox.Result<?> result,
        java.util.function.BooleanSupplier expected, HologramSnapshotInbox<?> inbox) {
        try {
            java.lang.reflect.Field current = HologramSnapshotInbox.Result.class.getDeclaredField("current");
            current.setAccessible(true);
            Object guard = current.get(result);
            boolean tokenFound = false;
            for (java.lang.reflect.Field field : guard.getClass()
                .getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object captured = field.get(guard);
                SimpleAssert.that(
                    !(captured instanceof com.miaokatze.gtmb.hologram.HologramSnapshotAssembly.Complete)
                        && !(captured instanceof byte[]),
                    "publication guard cannot capture released compressed owner/payload");
                SimpleAssert.that(
                    field.getType()
                        .isPrimitive() || captured == expected
                        || captured == inbox,
                    "publication guard has no hidden wrapper retaining the completed frame");
                tokenFound |= captured == expected;
            }
            SimpleAssert
                .that(tokenFound, "publication guard directly retains only the detached original context token");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Isolated-process coverage: saturating the shared quota must not interfere with other suites' caches. */
    public static void runQuotaContention() {
        transientQuotaRetainsReceipt(false);
        transientQuotaRetainsReceipt(true);
        impossiblePreparationHasExactFailure();
        impossibleExpandedRetainsTransportIdentity();
    }

    /** Real manifest ownership plus compressed-page copying, under the isolated 1 MiB quota. */
    public static void runCopyQuota(byte[] impossibleCopy, byte[] impossibleWorkingSet, byte[] temporary,
        long available) throws Exception {
        long before = com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedBytes();
        for (long resident : new long[] { 0,
            com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedByteLimit() / 2 }) {
            try (
                com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease competing = com.miaokatze.gtmb.hologram.HologramProcessGovernor
                    .retain("fixture.resident.server.before.first.page", resident)) {
                for (byte[] impossible : new byte[][] { impossibleCopy, impossibleWorkingSet }) {
                    try (
                        com.miaokatze.gtmb.hologram.HologramSnapshotSegments.Decoder decoder = new com.miaokatze.gtmb.hologram.HologramSnapshotSegments.Decoder();
                        HologramSnapshotInbox<net.minecraft.nbt.NBTTagCompound> inbox = new HologramSnapshotInbox<>(
                            2,
                            impossible.length,
                            1,
                            (bytes, current) -> copyQuotaDecode(decoder, bytes, current))) {
                        inbox.offer(impossible, 73);
                        net.minecraft.nbt.NBTTagCompound later = new net.minecraft.nbt.NBTTagCompound();
                        later.setString("session", "later-control");
                        inbox.offerValue(later);
                        HologramSnapshotInbox.Result<net.minecraft.nbt.NBTTagCompound> failure = awaitCopyQuota(inbox);
                        SimpleAssert.that(failure != null, "an impossible compressed copy cannot retry forever");
                        SimpleAssert.that(
                            failure.failure instanceof HologramSnapshotInbox.AdmissionException,
                            "manifest plus its own compressed page exposes a terminal budget failure");
                        SimpleAssert
                            .eq(73L, failure.transportSequence, "copy failure retains exact transport identity");
                        failure.close();
                        SimpleAssert.eq(later, inbox.poll().value, "an impossible copy cannot strand later controls");
                    }
                }
            }
        }
        SimpleAssert.eq(
            before,
            com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedBytes(),
            "failed copy releases its manifest");
        CountDownLatch retried = new CountDownLatch(2);
        com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease competing = com.miaokatze.gtmb.hologram.HologramProcessGovernor
            .retain(
                "fixture.transient.copy.competitor",
                com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedByteLimit() - before - available);
        try (
            com.miaokatze.gtmb.hologram.HologramSnapshotSegments.Decoder decoder = new com.miaokatze.gtmb.hologram.HologramSnapshotSegments.Decoder();
            HologramSnapshotInbox<net.minecraft.nbt.NBTTagCompound> inbox = new HologramSnapshotInbox<>(
                2,
                temporary.length,
                1,
                (bytes, current) -> {
                    retried.countDown();
                    return copyQuotaDecode(decoder, bytes, current);
                })) {
            java.util.concurrent.atomic.AtomicInteger released = new java.util.concurrent.atomic.AtomicInteger();
            inbox.put(temporary, 74, released::incrementAndGet, inbox.epoch());
            net.minecraft.nbt.NBTTagCompound later = new net.minecraft.nbt.NBTTagCompound();
            later.setString("session", "later-control");
            inbox.offerValue(later);
            waitFor(retried);
            SimpleAssert.that(inbox.poll() == null, "temporary copy contention retains the exact FIFO head");
            SimpleAssert.eq(0, released.get(), "temporary copy contention retains physical frame ownership");
            competing.close();
            HologramSnapshotInbox.Result<net.minecraft.nbt.NBTTagCompound> result = awaitCopyQuota(inbox);
            SimpleAssert
                .that(result != null && result.failure == null, "the original copy succeeds after contention retires");
            SimpleAssert.eq(74L, result.transportSequence, "temporary retry preserves exact transport identity");
            SimpleAssert
                .eq("exact-copy-quota-receipt", result.value.getString("session"), "the exact manifest survives retry");
            result.close();
            SimpleAssert.eq(1, released.get(), "frame ownership retires once");
            SimpleAssert.eq(later, inbox.poll().value, "temporary copy contention never reorders controls");
        } finally {
            competing.close();
        }
        SimpleAssert.eq(
            before,
            com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedBytes(),
            "all copy-quota leases retire explicitly");
    }

    private static HologramSnapshotInbox.Decoded<net.minecraft.nbt.NBTTagCompound> copyQuotaDecode(
        com.miaokatze.gtmb.hologram.HologramSnapshotSegments.Decoder decoder, byte[] bytes,
        java.util.function.BooleanSupplier current) throws IOException {
        com.miaokatze.gtmb.hologram.HologramSnapshotCodec.OwnedSnapshot decoded = decoder.decodeOwned(bytes, current);
        return new HologramSnapshotInbox.Decoded<>(decoded.value, decoded, decoded.retainedEstimate);
    }

    private static HologramSnapshotInbox.Result<net.minecraft.nbt.NBTTagCompound> awaitCopyQuota(
        HologramSnapshotInbox<net.minecraft.nbt.NBTTagCompound> inbox) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        HologramSnapshotInbox.Result<net.minecraft.nbt.NBTTagCompound> result;
        while ((result = inbox.poll()) == null && System.nanoTime() < deadline) Thread.sleep(5);
        return result;
    }

    /** Exercise real expanded-NBT admission under a competing single-player server's shared quota. */
    private static void transientQuotaRetainsReceipt(boolean materialized) {
        try {
            net.minecraft.nbt.NBTTagCompound source = new net.minecraft.nbt.NBTTagCompound();
            source.setString("session", "exact-committed-receipt");
            source.setByteArray("payload", new byte[32768]);
            byte[] bytes = com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(source);
            long estimate;
            try (
                com.miaokatze.gtmb.hologram.HologramSnapshotCodec.OwnedSnapshot decoded = com.miaokatze.gtmb.hologram.HologramSnapshotCodec
                    .decodeOwned(bytes, () -> true)) {
                estimate = decoded.retainedEstimate;
            }
            CountDownLatch retried = new CountDownLatch(2);
            java.util.concurrent.atomic.AtomicInteger released = new java.util.concurrent.atomic.AtomicInteger();
            com.miaokatze.gtmb.hologram.HologramProcessGovernor.Lease occupied = com.miaokatze.gtmb.hologram.HologramProcessGovernor
                .retain(
                    "fixture.competing.singleplayer.server",
                    com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedByteLimit()
                        - com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedBytes()
                        - (materialized ? estimate : 0));
            try (HologramSnapshotInbox<net.minecraft.nbt.NBTTagCompound> inbox = new HologramSnapshotInbox<>(
                2,
                bytes.length,
                1,
                (compressed, current) -> {
                    retried.countDown();
                    com.miaokatze.gtmb.hologram.HologramSnapshotCodec.OwnedSnapshot decoded = com.miaokatze.gtmb.hologram.HologramSnapshotCodec
                        .decodeOwned(compressed, current);
                    return new HologramSnapshotInbox.Decoded<>(
                        decoded.value,
                        decoded,
                        decoded.retainedEstimate,
                        materialized ? 2L * decoded.retainedEstimate : 0);
                })) {
                SimpleAssert.that(
                    inbox.put(bytes, 41, released::incrementAndGet, inbox.epoch()),
                    "exact compressed receipt admitted before quota contention");
                net.minecraft.nbt.NBTTagCompound later = new net.minecraft.nbt.NBTTagCompound();
                later.setString("session", "later-control");
                SimpleAssert.that(inbox.offerValue(later), "later control retained behind the busy receipt");
                waitFor(retried);
                SimpleAssert
                    .that(inbox.poll() == null, "transient quota cannot publish a terminal failure or overtake ACK");
                SimpleAssert.eq(0, released.get(), "retry retains the original physical frame reservation");
                SimpleAssert.that(!inbox.offer(bytes), "retries do not grow the bounded compressed data lane");
                occupied.close();
                HologramSnapshotInbox.Result<net.minecraft.nbt.NBTTagCompound> result = inbox.awaitNext();
                SimpleAssert.that(result.failure == null, "the original receipt succeeds after competing bytes retire");
                SimpleAssert.eq(41L, result.transportSequence, "retry preserves exact transport ownership");
                SimpleAssert.eq(source, result.value, "retry preserves every authoritative receipt byte");
                SimpleAssert.that(
                    (result.materialized != null) == materialized,
                    "only ordinary-state preparation reserves materialized work before client publication");
                result.close();
                SimpleAssert.eq(later, inbox.poll().value, "later control follows the retained receipt exactly once");
                SimpleAssert.that(inbox.poll() == null, "retry never duplicates a receipt");
                SimpleAssert.eq(1, released.get(), "the original physical reservation releases exactly once");
            } finally {
                occupied.close();
            }
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void impossiblePreparationHasExactFailure() {
        java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(
            2,
            4,
            1,
            (bytes, current) -> new HologramSnapshotInbox.Decoded<>(
                "exact session and command metadata",
                closed::incrementAndGet,
                1,
                com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedByteLimit()))) {
            inbox.offer(new byte[] { 1 }, 71);
            inbox.offerValue("later control");
            HologramSnapshotInbox.Result<String> failure = inbox.awaitNext();
            SimpleAssert.that(
                failure.failure instanceof HologramSnapshotInbox.AdmissionException,
                "an impossible preparation becomes an explicit failure without an arbitrary retry delay");
            SimpleAssert.eq(71L, failure.transportSequence, "terminal budget failure keeps exact transport identity");
            SimpleAssert
                .eq("exact session and command metadata", failure.value, "failure can retire only its own request");
            failure.close();
            SimpleAssert.eq(1, closed.get(), "failure releases decoded ownership exactly once");
            SimpleAssert
                .eq("later control", inbox.poll().value, "explicit budget failure cannot strand later controls");
        }
    }

    private static void impossibleExpandedRetainsTransportIdentity() {
        try {
            java.lang.reflect.Constructor<com.miaokatze.gtmb.hologram.HologramSnapshotCodec.BusyException> constructor = com.miaokatze.gtmb.hologram.HologramSnapshotCodec.BusyException.class
                .getDeclaredConstructor(String.class, long.class);
            constructor.setAccessible(true);
            com.miaokatze.gtmb.hologram.HologramSnapshotCodec.BusyException impossible = constructor.newInstance(
                "expanded input cannot fit",
                com.miaokatze.gtmb.hologram.HologramProcessGovernor.retainedByteLimit() + 1);
            try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(
                2,
                4,
                bytes -> { throw impossible; })) {
                inbox.offer(new byte[] { 1 }, 72);
                HologramSnapshotInbox.Result<String> failure = inbox.awaitNext();
                SimpleAssert.that(
                    failure.failure instanceof HologramSnapshotInbox.AdmissionException && failure.value == null,
                    "an impossible expanded tree terminates without allocating it or waiting forever");
                SimpleAssert
                    .eq(72L, failure.transportSequence, "the missing decode identity uses exact queued sequence");
                failure.close();
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void fifoAndBackpressure() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> decodingThread = new AtomicReference<>();
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(2, 4, bytes -> {
            decodingThread.set(Thread.currentThread());
            entered.countDown();
            waitFor(release);
            return "PREPARE";
        })) {
            SimpleAssert.that(inbox.offer(new byte[3], 7), "first snapshot admitted");
            waitFor(entered);
            SimpleAssert.that(decodingThread.get() != Thread.currentThread(), "pure decode leaves client thread");
            SimpleAssert.that(!inbox.offer(new byte[2]), "active bytes count toward compressed budget");
            SimpleAssert.that(inbox.offerValue("ACK"), "inline ACK admitted behind compressed PREPARE");
            SimpleAssert.that(!inbox.offerValue("next"), "decoded and undecoded entries share a bounded capacity");
            SimpleAssert.that(inbox.poll() == null, "ready ACK never overtakes blocked PREPARE");
            release.countDown();
            HologramSnapshotInbox.Result<String> decoded = inbox.awaitNext();
            SimpleAssert.eq("PREPARE", decoded.value, "PREPARE publishes first");
            SimpleAssert.eq(7L, decoded.transportSequence, "decode completion retains its exact transport owner");
            HologramSnapshotInbox.Result<String> inline = inbox.poll();
            SimpleAssert.eq("ACK", inline.value, "ACK is retained and publishes second");
            SimpleAssert.eq(-1L, inline.transportSequence, "inline ACK cannot complete an unrelated transport");
            SimpleAssert.that(inbox.offerValue("next"), "consumption relieves backpressure for retry");
            SimpleAssert.eq("next", inbox.poll().value, "rejected input can be retried without loss");
            boolean rejected = false;
            try {
                inbox.offer(new byte[5]);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            SimpleAssert.that(rejected, "single oversized snapshot rejected before decode");
        } finally {
            release.countDown();
        }
    }

    private static void epochCancellation() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(2, 4, bytes -> {
            if (bytes[0] == 1) {
                entered.countDown();
                waitFor(release);
                return "old-world";
            }
            return "new-world";
        })) {
            SimpleAssert.that(inbox.offer(new byte[] { 1, 0, 0 }), "old-world snapshot admitted");
            waitFor(entered);
            SimpleAssert.that(inbox.offerValue("old queued ACK"), "old ACK queued");
            inbox.clear();
            SimpleAssert.that(!inbox.offer(new byte[2]), "cancelled active decode still reserves physical bytes");
            SimpleAssert.that(inbox.offer(new byte[] { 2 }), "new epoch fits beside cancelled active work");
            SimpleAssert.that(!inbox.offerValue("excess"), "stale active work still counts toward capacity");
            release.countDown();
            SimpleAssert.eq("new-world", inbox.awaitNext().value, "old result and queued old ACK cannot cross epoch");
            SimpleAssert.that(inbox.poll() == null, "old result never reappears");
            SimpleAssert.that(inbox.offer(new byte[4]), "cancelled reservation released after decode exits");
            SimpleAssert.eq("new-world", inbox.awaitNext().value, "worker continues after cancellation");
        } finally {
            release.countDown();
        }
    }

    private static void failedDecodeRetainsOrder() {
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(
            2,
            4,
            bytes -> { throw new IOException("malformed compressed snapshot"); })) {
            SimpleAssert.that(inbox.offer(new byte[] { 1 }), "malformed snapshot admitted for validation");
            SimpleAssert.that(inbox.offerValue("ACK"), "ACK queued after malformed snapshot");
            String preview = "previous complete preview";
            HologramSnapshotInbox.Result<String> failed = inbox.awaitNext();
            if (failed.failure == null) preview = failed.value;
            SimpleAssert.that(failed.failure instanceof IOException, "failure reported to client caller");
            SimpleAssert.eq("previous complete preview", preview, "failed decode leaves installed preview intact");
            SimpleAssert.eq("ACK", inbox.poll().value, "failure never drops the later ACK");
            inbox.offerValue("cancelled session");
            inbox.offerValue("new session");
            SimpleAssert.eq("cancelled session", inbox.poll().value, "cancellation arrives in FIFO order");
            SimpleAssert.eq("new session", inbox.poll().value, "later session survives an ordinary cancelled message");
        }
    }

    private static void networkBackpressureAndControlReserve() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1),
            admitted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger released = new java.util.concurrent.atomic.AtomicInteger();
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(4, 8, bytes -> {
            entered.countDown();
            waitFor(release);
            return "frame";
        }, 1)) {
            long epoch = inbox.epoch();
            SimpleAssert
                .that(inbox.put(new byte[] { 1 }, 1, released::incrementAndGet, epoch), "leased frame admitted");
            waitFor(entered);
            SimpleAssert.that(inbox.offerValue("control"), "control reserve survives a full data lane");
            Thread network = new Thread(() -> {
                inbox.put(new byte[] { 2 }, 2, released::incrementAndGet, epoch);
                admitted.countDown();
            }, "test network ingress");
            network.start();
            SimpleAssert.that(inbox.poll() == null, "client poll returns immediately while the worker is blocked");
            release.countDown();
            SimpleAssert.eq("frame", inbox.awaitNext().value, "data publishes before its ordered control");
            waitFor(admitted);
            SimpleAssert.eq("control", inbox.poll().value, "queued control is retained while ingress waits");
            SimpleAssert.eq("frame", inbox.awaitNext().value, "network ingress resumes after publication");
            SimpleAssert.eq(2, released.get(), "each physical reservation releases exactly once");
            inbox.clear();
            SimpleAssert.that(
                !inbox.put(new byte[] { 3 }, 3, released::incrementAndGet, epoch),
                "stale ingress cannot enter a new epoch");
            SimpleAssert.eq(3, released.get(), "stale admission releases its transferred reservation");
        } finally {
            release.countDown();
        }
    }

    private static void ownerDisplayAdmission() {
        java.util.concurrent.atomic.AtomicInteger released = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Long> published = new java.util.ArrayList<>();
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(4, 8, bytes -> "frame", 2)) {
            long epoch = inbox.epoch();
            inbox.offer(new byte[] { 1 }, 29, released::incrementAndGet, epoch, null);
            inbox.offer(new byte[] { 2 }, 30, released::incrementAndGet, epoch, null);
            SimpleAssert.that(inbox.offerValue("control"), "control reserve remains bounded behind display results");
            waitForReady(inbox, 3);
            SimpleAssert
                .eq(0, released.get(), "decoded results retain their physical receive credits until owner consumption");
            SimpleAssert.that(inbox.offer(new byte[] { 3 }, 31, released::incrementAndGet, epoch, ready -> {
                published.add(ready.transportSequence);
                ready.close();
            }), "an owner can make room by publishing an already decoded FIFO result without waiting");
            SimpleAssert
                .eq(java.util.Collections.singletonList(29L), published, "only the oldest ready frame publishes");
            waitForReady(inbox, 3);
            HologramSnapshotInbox.Result<String> second = inbox.poll();
            SimpleAssert.eq(30L, second.transportSequence, "ready-owner admission retains the next exact receipt");
            second.close();
            SimpleAssert.eq("control", inbox.poll().value, "control cannot be overtaken by the new display frame");
            HologramSnapshotInbox.Result<String> third = inbox.poll();
            SimpleAssert.eq(31L, third.transportSequence, "the newly admitted frame follows the existing FIFO");
            third.close();
            SimpleAssert.eq(3, released.get(), "each physical frame releases exactly once");
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(2, 8, bytes -> {
            entered.countDown();
            waitFor(release);
            return "authoritative ACK";
        }, 1)) {
            long epoch = inbox.epoch();
            inbox.offer(new byte[] { 1 }, 41, released::incrementAndGet, epoch, null);
            waitFor(entered);
            SimpleAssert.that(
                !inbox.offer(
                    new byte[] { 2 },
                    42,
                    released::incrementAndGet,
                    epoch,
                    ready -> { throw new AssertionError("an undecoded head cannot publish"); }),
                "an owner returns busy immediately when no ready result can free its bounded data slot");
            SimpleAssert.eq(4, released.get(), "a rejected optional frame releases its own ownership immediately");
            SimpleAssert.that(inbox.offerValue("control"), "optional saturation preserves the control reserve");
            SimpleAssert.that(
                !inbox.offerValue(
                    "later control",
                    epoch,
                    ready -> { throw new AssertionError("an undecoded head cannot publish"); }),
                "an owner control also returns immediately at the existing FIFO capacity");
            SimpleAssert.that(inbox.poll() == null, "optional rejection cannot drop or overtake an authoritative ACK");
            release.countDown();
            HologramSnapshotInbox.Result<String> ack = inbox.awaitNext();
            SimpleAssert.eq(41L, ack.transportSequence, "the exact authoritative receipt remains admitted");
            ack.close();
            SimpleAssert.eq("control", inbox.poll().value, "the existing control follows its authoritative receipt");
            inbox.clear();
            SimpleAssert.that(
                !inbox.offer(new byte[] { 3 }, 43, released::incrementAndGet, epoch, null),
                "a stale optional frame cannot cross the epoch fence");
            SimpleAssert.eq(6, released.get(), "accepted and stale optional frames release once");
        } finally {
            release.countDown();
        }
    }

    private static void ownerOrdinaryRecovery() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger disposed = new AtomicInteger();
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(4, 8, bytes -> {
            entered.countDown();
            waitFor(release);
            return "authoritative";
        }, 2)) {
            long epoch = inbox.epoch();
            SimpleAssert.that(
                inbox.offer(new byte[] { 1 }, 61, disposed::incrementAndGet, epoch, null),
                "first ordinary frame admitted");
            waitFor(entered);
            SimpleAssert.that(
                inbox.offer(new byte[] { 2 }, 62, disposed::incrementAndGet, epoch, null),
                "second ordinary frame admitted");
            boolean accepted = inbox.offer(
                new byte[] { 3 },
                63,
                disposed::incrementAndGet,
                epoch,
                result -> { throw new AssertionError("owner must not wait for an undecoded FIFO head"); });
            SimpleAssert.that(!accepted, "third ordinary frame returns without waiting on its owner consumer");
            SimpleAssert.eq(1, disposed.get(), "rejected ordinary frame returns its byte lease and credit once");
            net.minecraft.nbt.NBTTagCompound recovery = HologramClient.snapshotIngressRecovery(63);
            SimpleAssert.eq(
                63L,
                recovery.getLong("transportSequence"),
                "headerless rejection retains exact frame identity for recovery");
            SimpleAssert.that(
                !recovery.hasKey("session"),
                "initial baseline recovery does not borrow previous session identity");
            release.countDown();
            HologramSnapshotInbox.Result<String> first = inbox.awaitNext(), second = inbox.awaitNext();
            SimpleAssert.eq(61L, first.transportSequence, "first authoritative ACK is not discarded for recovery");
            SimpleAssert.eq(62L, second.transportSequence, "second authoritative ACK remains FIFO");
            first.close();
            second.close();
            SimpleAssert.eq(3, disposed.get(), "all three reservations dispose exactly once");
        } finally {
            release.countDown();
        }
    }

    private static void retiredActiveSlotRelease() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger retired = new AtomicInteger();
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(2, 4, bytes -> {
            if (bytes[0] == 1) {
                entered.countDown();
                waitFor(release);
            }
            return "frame";
        }, 2)) {
            long epoch = inbox.epoch();
            inbox.offer(new byte[] { 1 }, 51, retired::incrementAndGet, epoch, null);
            waitFor(entered);
            inbox.clear();
            SimpleAssert.eq(0, retired.get(), "an old active decoder keeps its physical slot until it retires");
            inbox.offer(new byte[] { 2 }, 52, retired::incrementAndGet, inbox.epoch(), null);
            release.countDown();
            waitForReady(inbox, 1);
            SimpleAssert.eq(1, retired.get(), "stale completion returns only the old physical credit");
            HologramSnapshotInbox.Result<String> current = inbox.poll();
            SimpleAssert.eq(52L, current.transportSequence, "retired work cannot publish into the new epoch");
            current.close();
            SimpleAssert.eq(2, retired.get(), "the new physical credit returns only on owner consumption");
            inbox.offer(new byte[] { 3 }, 53, retired::incrementAndGet, inbox.epoch(), null);
            waitForReady(inbox, 1);
            inbox.clear();
            SimpleAssert.eq(3, retired.get(), "clearing a ready result returns its physical credit once");
            SimpleAssert.that(inbox.poll() == null, "cleared results cannot republish");
        } finally {
            release.countDown();
        }
        try (HologramSnapshotInbox<String> inbox = new HologramSnapshotInbox<>(2, 4, bytes -> "frame")) {
            List<String> consumed = new java.util.ArrayList<>();
            inbox.offerValue("first");
            inbox.offerValue("second");
            SimpleAssert.that(
                inbox.offerValue("third", inbox.epoch(), ready -> consumed.add(ready.value)),
                "an owner may consume ready legacy controls within the same bounded FIFO");
            SimpleAssert
                .eq(java.util.Collections.singletonList("first"), consumed, "ready controls publish in FIFO order");
            SimpleAssert.eq("second", inbox.poll().value, "the existing control remains before newly admitted work");
            SimpleAssert.eq("third", inbox.poll().value, "the new control is not reordered");
        }
    }

    private static void waitForReady(HologramSnapshotInbox<?> inbox, int expected) {
        try {
            java.lang.reflect.Field entries = HologramSnapshotInbox.class.getDeclaredField("entries");
            entries.setAccessible(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                synchronized (inbox) {
                    java.util.Deque<?> queue = (java.util.Deque<?>) entries.get(inbox);
                    int ready = 0;
                    for (Object entry : queue) {
                        java.lang.reflect.Field result = entry.getClass()
                            .getDeclaredField("result");
                        result.setAccessible(true);
                        if (result.get(entry) != null) ready++;
                    }
                    if (queue.size() == expected && ready == expected) return;
                }
                Thread.sleep(1);
            }
            throw new AssertionError("snapshot results did not become ready");
        } catch (ReflectiveOperationException | InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void waitFor(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("snapshot worker gate timed out");
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void explicitRootCloseFence() {
        HologramRootCloseFence fence = new HologramRootCloseFence();
        SimpleAssert
            .that(!fence.suppress("session", 7, true, false), "child return and resize have no explicit close fence");
        fence.close("session", 8);
        SimpleAssert
            .that(fence.suppress("session", 7, true, false), "ordinary queued open cannot undo explicit root exit");
        SimpleAssert
            .that(fence.suppress("session", 7, false, false), "older closed snapshot cannot acknowledge a newer exit");
        SimpleAssert.that(fence.suppress("session", 8, true, false), "same-sequence queued open is still suppressed");
        SimpleAssert.that(
            fence.suppress("session", 9, false, true),
            "import progress cannot acknowledge close while still busy");
        SimpleAssert
            .that(fence.suppress("session", 8, false, false), "exact close ACK retires fence without routing GUI");
        SimpleAssert.that(
            !fence.suppress("session", 8, true, false),
            "later actual right-click can reopen at the same server UI sequence");
        SimpleAssert
            .that(!fence.suppress("session", 9, true, false), "later higher-sequence open remains normal after ACK");
        fence.close("ghost", 4);
        SimpleAssert.that(
            fence.suppress("ghost", 4, true, false),
            "world Escape rejects a queued material review at the current UI sequence");
        SimpleAssert.that(
            fence.suppress("ghost", 4, false, false),
            "world cancellation ACK stays closed without requiring a synthetic UI edit sequence");
        SimpleAssert.that(
            !fence.suppress("ghost", 4, true, false),
            "ordinary air right-click can reopen the retained draft after world cancellation ACK");
        fence.close("session", 10);
        fence.close("session", 12);
        SimpleAssert
            .that(fence.suppress("session", 10, false, false), "first close ACK cannot retire a later explicit exit");
        SimpleAssert.that(fence.suppress("session", 12, true, false), "later exit still blocks queued open");
        SimpleAssert
            .that(!fence.suppress("new-session-uuid", 0, true, false), "a new server UUID is never permanently hidden");
        fence.close("new-session-uuid", 1);
        fence.clear();
        SimpleAssert.that(
            !fence.suppress("new-session-uuid", 1, true, false),
            "disconnect/clear projection releases old fence");

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (HologramSnapshotInbox<Opening> inbox = new HologramSnapshotInbox<>(2, 4, bytes -> {
            entered.countDown();
            waitFor(release);
            return new Opening(7, true);
        })) {
            fence.close("session", 8);
            inbox.offer(new byte[] { 1 });
            waitFor(entered);
            inbox.offerValue(new Opening(8, false));
            SimpleAssert.that(inbox.poll() == null, "close ACK cannot overtake a queued compressed old opening");
            release.countDown();
            Opening old = inbox.awaitNext().value;
            SimpleAssert.that(
                fence.suppress("session", old.sequence, old.open, false),
                "actual FIFO-decoded old open is suppressed");
            Opening ack = inbox.poll().value;
            SimpleAssert.that(fence.suppress("session", ack.sequence, ack.open, false), "FIFO close ACK retires fence");
            inbox.offerValue(new Opening(8, true));
            Opening deliberate = inbox.poll().value;
            SimpleAssert.that(
                !fence.suppress("session", deliberate.sequence, deliberate.open, false),
                "actual later right-click snapshot follows the retained ACK despite unchanged sequence");
        } finally {
            release.countDown();
        }
    }

    private static final class Opening {

        final long sequence;
        final boolean open;

        Opening(long sequence, boolean open) {
            this.sequence = sequence;
            this.open = open;
        }
    }
}
