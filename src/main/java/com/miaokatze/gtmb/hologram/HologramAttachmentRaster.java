package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** Exact raster singleflight. Producer, cache and each geometry continuation own independent references. */
final class HologramAttachmentRaster {

    private static final long FORMAT_EPOCH = 1;
    static final AtomicInteger COMPUTATIONS = new AtomicInteger();
    private static Handle cached;

    private HologramAttachmentRaster() {}

    static HologramProcessGovernor.Subscription<Shared> request(HologramPictureBox frozen, Executor executor,
        BiConsumer<Handle, Throwable> complete) throws IOException {
        Input input = new Input(frozen);
        Producer producer = null;
        try {
            Key key = new Key(input);
            synchronized (HologramAttachmentRaster.class) {
                if (cached != null && cached.shared.key.equals(key)) {
                    Handle view = cached.shared.borrow();
                    input.close();
                    deliver(complete, view, null);
                    return null;
                }
            }
            producer = new Producer(key, input);
            return HologramProcessGovernor.singleFlight(key, FORMAT_EPOCH, producer, executor, (shared, error) -> {
                if (error != null) {
                    deliver(complete, null, error);
                    return;
                }
                Handle view;
                try {
                    view = shared.borrow();
                } catch (RuntimeException unavailable) {
                    deliver(complete, null, unavailable);
                    return;
                }
                deliver(complete, view, null);
            });
        } catch (RuntimeException | Error failure) {
            if (producer == null) input.close();
            else producer.close();
            throw failure;
        }
    }

    private static void deliver(BiConsumer<Handle, Throwable> complete, Handle view, Throwable error) {
        try {
            complete.accept(view, error);
        } catch (RuntimeException | Error failure) {
            if (view != null) view.close();
            throw failure;
        }
    }

    static synchronized void clearCache() {
        if (cached != null) cached.close();
        cached = null;
    }

    private static synchronized void cache(Shared shared) {
        Handle next = shared.borrow();
        Handle previous = cached;
        cached = next;
        if (previous != null) previous.close();
    }

    static final class Handle implements AutoCloseable {

        private Shared shared;

        Handle(Shared shared) {
            this.shared = shared;
        }

        byte[] pixels() {
            if (shared == null) throw new IllegalStateException("Raster view released");
            return shared.pixels;
        }

        @Override
        public synchronized void close() {
            Shared release = shared;
            shared = null;
            if (release != null) release.release();
        }
    }

    static final class Shared implements HologramProcessGovernor.SharedOutput {

        final Key key;
        private Input input;
        private byte[] pixels;
        private HologramProcessGovernor.Lease pixelsLease;
        private int references = 1;
        private boolean producerReleased;

        Shared(Key key, Input input, byte[] pixels, HologramProcessGovernor.Lease pixelsLease) {
            this.key = key;
            this.input = input.retain();
            this.pixels = pixels;
            this.pixelsLease = pixelsLease.transfer();
        }

        synchronized Handle borrow() {
            if (references == 0) throw new IllegalStateException("Raster result released");
            Handle handle = new Handle(this);
            references++;
            return handle;
        }

        synchronized void release() {
            if (--references != 0) return;
            pixels = null;
            pixelsLease.close();
            pixelsLease = null;
            input.close();
            input = null;
        }

        /** The governor releases this producer reference after all subscriber callbacks have borrowed their views. */
        @Override
        public synchronized void close() {
            if (producerReleased) return;
            producerReleased = true;
            release();
        }
    }

    private static final class Producer implements Callable<Shared>, AutoCloseable {

        private Key key;
        private Input input;

        Producer(Key key, Input input) {
            this.key = key;
            this.input = input;
        }

        @Override
        public Shared call() throws IOException {
            HologramPictureBox picture = input.picture;
            long count = (long) picture.width * picture.height;
            HologramProcessGovernor.Lease output = HologramAttachmentMemory
                .reserve("attachment shared raster", count + 64);
            Shared result = null;
            try (HologramProcessGovernor.Lease working = HologramAttachmentMemory.reserve(
                "attachment raster working",
                picture.source.length + 8L * count
                    + 48L * (picture.width + 2)
                    + 512L * (picture.sourceWidth + picture.sourceHeight)
                    + 4096)) {
                HologramAttachmentGeometry.checkpoint("raster", 0, picture.height);
                COMPUTATIONS.incrementAndGet();
                byte[] pixels = picture.rasterize();
                HologramAttachmentGeometry.checkpoint("raster", picture.height, picture.height);
                result = new Shared(key, input, pixels, output);
                cache(result);
                return result;
            } catch (IOException | RuntimeException | Error failure) {
                if (result != null) result.close();
                else output.close();
                throw failure;
            }
        }

        @Override
        public synchronized void close() {
            Input release = input;
            input = null;
            key = null;
            if (release != null) release.close();
        }
    }

    private static final class Input implements AutoCloseable {

        private HologramPictureBox picture;
        private HologramProcessGovernor.Lease lease;
        private int references = 1;

        Input(HologramPictureBox picture) throws IOException {
            lease = HologramAttachmentMemory.reserve("attachment raster input", 4096L + picture.source.length * 4L);
            this.picture = picture;
            lease.transfer();
        }

        synchronized Input retain() {
            if (references == 0) throw new IllegalStateException("Raster input released");
            references++;
            return this;
        }

        @Override
        public synchronized void close() {
            if (references == 0 || --references != 0) return;
            picture = null;
            lease.close();
            lease = null;
        }
    }

    private static final class Key {

        private final Input input;
        private final int hash;

        Key(Input input) {
            this.input = input;
            HologramPictureBox picture = input.picture;
            int value = Arrays.hashCode(picture.source);
            value = 31 * value + picture.sourceWidth;
            value = 31 * value + picture.sourceHeight;
            value = 31 * value + picture.width;
            value = 31 * value + picture.height;
            value = 31 * value + picture.palette;
            value = 31 * value + picture.filter;
            value = 31 * value + picture.brightness;
            value = 31 * value + picture.contrast;
            value = 31 * value + picture.saturation;
            value = 31 * value + picture.backgroundTolerance;
            value = 31 * value + (picture.removeBackground ? 1 : 0);
            hash = 31 * value + (picture.dither ? 1 : 0);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) return true;
            if (!(other instanceof Key)) return false;
            HologramPictureBox a = input.picture, b = ((Key) other).input.picture;
            return a != null && b != null
                && a.sourceWidth == b.sourceWidth
                && a.sourceHeight == b.sourceHeight
                && a.width == b.width
                && a.height == b.height
                && a.palette == b.palette
                && a.filter == b.filter
                && a.brightness == b.brightness
                && a.contrast == b.contrast
                && a.saturation == b.saturation
                && a.backgroundTolerance == b.backgroundTolerance
                && a.removeBackground == b.removeBackground
                && a.dither == b.dither
                && Arrays.equals(a.source, b.source);
        }
    }
}
