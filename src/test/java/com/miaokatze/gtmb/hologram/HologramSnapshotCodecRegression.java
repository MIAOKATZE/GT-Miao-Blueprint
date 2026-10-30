package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Large NBT and actual raw message boundaries without a live channel or client world. */
public final class HologramSnapshotCodecRegression {

    private HologramSnapshotCodecRegression() {}

    public static void run() {
        try {
            byte[] payload = new byte[3 * 1024 * 1024];
            new Random(80).nextBytes(payload);
            NBTTagCompound source = new NBTTagCompound();
            source.setString("session", "mega");
            source.setString("generation", "mega");
            source.setString("utf", "ASCII\u0000\u732b\ud83d\ude3a");
            source.setLong("batchSeq", 37);
            source.setByteArray("payload", payload);
            byte[] compressed = HologramSnapshotCodec.compress(source);
            SimpleAssert.that(compressed.length > 2 * 1024 * 1024, "fixture exceeds Forge single-packet limit");
            HologramSnapshotAssembly assembly = new HologramSnapshotAssembly();
            byte[] joined = null;
            for (int offset = 0, index = 0; offset
                < compressed.length; offset += HologramSnapshotAssembly.CHUNK_BYTES, index++) {
                byte[] slice = Arrays.copyOfRange(
                    compressed,
                    offset,
                    Math.min(compressed.length, offset + HologramSnapshotAssembly.CHUNK_BYTES));
                ByteBuf buffer = Unpooled.buffer();
                try {
                    new HologramNetwork.Fragment(80, compressed.length, index, slice).toBytes(buffer);
                    SimpleAssert.that(buffer.readableBytes() < 2 * 1024 * 1024, "every actual encoded fragment fits");
                    HologramNetwork.Fragment decoded = new HologramNetwork.Fragment();
                    decoded.fromBytes(buffer);
                    buffer.clear();
                    decoded.toBytes(buffer);
                    long wireSequence = buffer.readLong();
                    int wireTotal = buffer.readInt(), wireIndex = buffer.readInt(), wireLength = buffer.readInt();
                    byte[] wireBytes = new byte[wireLength];
                    buffer.readBytes(wireBytes);
                    joined = assembly.accept(wireSequence, wireTotal, wireIndex, wireBytes, index);
                    if (offset + slice.length < compressed.length)
                        SimpleAssert.that(joined == null, "partial transport does not publish");
                } finally {
                    buffer.release();
                }
            }
            SimpleAssert.that(joined != null, "all fragments complete the snapshot");
            NBTTagCompound decoded = HologramSnapshotCodec.decode(joined);
            SimpleAssert.that(Arrays.equals(payload, decoded.getByteArray("payload")), "large decoded NBT is complete");
            SimpleAssert.eq("mega", decoded.getString("generation"), "generation retained");
            SimpleAssert.eq(source.getString("utf"), decoded.getString("utf"), "valid modified UTF retained");
            SimpleAssert.eq(37, (int) decoded.getLong("batchSeq"), "ACK batch retained");
            hostileArray();
            malformedUtf();
            badFragment();
            palette();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void palette() throws IOException {
        NBTTagCompound source = paletteFixture();
        NBTTagCompound original = (NBTTagCompound) source.copy();
        byte[] legacy = HologramSnapshotCodec.compress(source);
        byte[] packed = HologramSnapshotCodec.compressPacked(source);
        SimpleAssert.that((legacy[0] & 255) == 31 && (legacy[1] & 255) == 139, "legacy remains a GZIP stream");
        SimpleAssert.that(packed[0] == 0x47 && packed[7] == 1, "packed wire has its own explicit version");
        NBTTagCompound decoded = HologramSnapshotCodec.decode(packed);
        SimpleAssert.that(HologramSnapshotPalette.isPacked(decoded), "decode retains the runtime dictionary");
        SimpleAssert.eq(
            1,
            decoded.getTagList(HologramSnapshotPalette.PALETTE_KEY, 10)
                .tagCount(),
            "exact equal descriptors serialize and parse once");
        SimpleAssert.eq(
            80,
            decoded.getTagList("cells", 10)
                .tagCount(),
            "each coordinate/reference remains present");
        SimpleAssert.eq(source, expand(decoded), "unknown fields, exact numeric types and all nested NBT survive");
        SimpleAssert.eq(original, source, "producer never changes the frozen owner baseline");
        NBTTagCompound copied = (NBTTagCompound) decoded.copy();
        SimpleAssert.that(HologramSnapshotPalette.isPacked(copied), "packed copy preserves format provenance");
        HologramSnapshotPalette.descriptor(
            copied,
            copied.getTagList("cells", 10)
                .getCompoundTagAt(0))
            .getCompoundTag("unknown")
            .setString("name", "changed");
        SimpleAssert.eq(source, expand(decoded), "packed copy owns independent descriptor subtrees");

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        CompressedStreamTools.write(source, new DataOutputStream(raw));
        TrackingSize tracker = new TrackingSize();
        CompressedStreamTools.func_152457_a(legacy, tracker);
        SimpleAssert.eq(
            source,
            expand(HologramSnapshotCodec.decode(packed, raw.size(), tracker.bytes)),
            "logical limits match real legacy serialization and Forge NBT tracking at the boundary");
        rejects(packed, raw.size() - 1, tracker.bytes, "references count against the old logical raw limit");
        rejects(packed, raw.size(), tracker.bytes - 1, "references count against the old NBT tracker limit");
        for (int length = 0; length < 8; length++)
            rejects(Arrays.copyOf(packed, length), "truncated packed header cannot enter legacy parsing");
        byte[] future = packed.clone();
        future[7] = 2;
        rejects(future, "unknown palette wire version is rejected");
        NBTTagCompound bad = (NBTTagCompound) decoded.copy();
        bad.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setInteger(HologramSnapshotPalette.REFERENCE_KEY, -1);
        rejects(HologramSnapshotCodec.compressPacked(bad), "negative descriptor references are rejected");
        bad = (NBTTagCompound) decoded.copy();
        bad.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setInteger(HologramSnapshotPalette.REFERENCE_KEY, 1);
        rejects(HologramSnapshotCodec.compressPacked(bad), "reference cannot point past the descriptor dictionary");
        bad = (NBTTagCompound) decoded.copy();
        bad.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setLong(HologramSnapshotPalette.REFERENCE_KEY, 0);
        rejects(HologramSnapshotCodec.compressPacked(bad), "reference type cannot coerce long to int");
        bad = (NBTTagCompound) decoded.copy();
        bad.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setString("unknownOverlay", "cannot hide data");
        rejects(HologramSnapshotCodec.compressPacked(bad), "unknown fields cannot be lost in a position overlay");
        bad = (NBTTagCompound) decoded.copy();
        bad.getTagList(HologramSnapshotPalette.PALETTE_KEY, 10)
            .getCompoundTagAt(0)
            .setInteger("x", 3);
        rejects(HologramSnapshotCodec.compressPacked(bad), "descriptor cannot supply ambiguous coordinates");
        SimpleAssert.that(
            HologramSnapshotPalette.pack(source, () -> true, 0, 0) == source,
            "exhausted dictionary budget returns the intact original without a deep-copy fallback");
        NBTTagCompound unique = (NBTTagCompound) source.copy();
        NBTTagList uniqueRows = unique.getTagList("cells", 10);
        for (int i = 0; i < uniqueRows.tagCount(); i++) uniqueRows.getCompoundTagAt(i)
            .setInteger("unique", i);
        SimpleAssert.that(
            !HologramSnapshotPalette
                .isPacked(HologramSnapshotCodec.decode(HologramSnapshotCodec.compressPacked(unique))),
            "unprofitable dictionary retains legacy format");
        NBTTagCompound collision = (NBTTagCompound) source.copy();
        collision.setInteger(HologramSnapshotPalette.VERSION_KEY, 1);
        collision.setTag(HologramSnapshotPalette.PALETTE_KEY, new NBTTagList());
        NBTTagCompound roundtrip = HologramSnapshotCodec.decode(HologramSnapshotCodec.compressPacked(collision));
        SimpleAssert
            .that(!HologramSnapshotPalette.isPacked(roundtrip), "legacy reserved root collisions never upgrade");
        SimpleAssert.eq(collision, roundtrip, "legal legacy fields with reserved names survive unchanged");
        SimpleAssert.that(
            !HologramSnapshotPalette.isPacked((NBTTagCompound) roundtrip.copy()),
            "legacy copy also retains ordinary format identity");
        collision = (NBTTagCompound) source.copy();
        collision.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setString(HologramSnapshotPalette.REFERENCE_KEY, "extension");
        roundtrip = HologramSnapshotCodec.decode(HologramSnapshotCodec.compressPacked(collision));
        SimpleAssert.that(!HologramSnapshotPalette.isPacked(roundtrip), "legacy row collision retains legacy format");
        SimpleAssert.eq(collision, roundtrip, "reserved row extension is preserved rather than overwritten");
        boolean cancelled = false;
        try {
            HologramSnapshotPalette.pack(source, () -> false);
        } catch (java.io.InterruptedIOException expected) {
            cancelled = true;
        }
        SimpleAssert.that(cancelled, "dictionary construction cooperates with worker cancellation");
    }

    static NBTTagCompound paletteFixture() {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("session", "palette-fixture");
        root.setLong("planRevision", 7);
        root.setString("futureRoot", "retained");
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < 80; i++) {
            NBTTagCompound row = new NBTTagCompound();
            if (i == 0) row.setByte("x", (byte) 0);
            else row.setInteger("x", i);
            row.setLong("y", 12);
            row.setShort("z", (short) 3);
            row.setInteger("dx", i);
            row.setString("wantId", "minecraft:stone");
            row.setString("status", "pending");
            NBTTagCompound unknown = new NBTTagCompound();
            unknown.setString("name", "ASCII\u0000\u732b\ud83d\ude3a");
            unknown.setByteArray("bytes", new byte[1024]);
            unknown.setIntArray("ints", new int[] { 0, -1, Integer.MAX_VALUE });
            unknown.setFloat("float", 1.25f);
            unknown.setDouble("double", -0.125);
            NBTTagList names = new NBTTagList();
            names.appendTag(new NBTTagString("kept"));
            unknown.setTag("list", names);
            row.setTag("unknown", unknown);
            rows.appendTag(row);
        }
        root.setTag("cells", rows);
        return root;
    }

    private static NBTTagCompound expand(NBTTagCompound root) {
        NBTTagCompound expanded = new NBTTagCompound();
        for (Object name : root.func_150296_c()) {
            String key = (String) name;
            if (!HologramSnapshotPalette.metadataKey(key) && !"cells".equals(key)) expanded.setTag(
                key,
                root.getTag(key)
                    .copy());
        }
        NBTTagList rows = new NBTTagList(), entries = root.getTagList("cells", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound position = entries.getCompoundTagAt(i);
            NBTTagCompound row = (NBTTagCompound) HologramSnapshotPalette.descriptor(root, position)
                .copy();
            for (Object name : position.func_150296_c()) {
                String key = (String) name;
                if (HologramSnapshotPalette.coordinateKey(key)) row.setTag(
                    key,
                    position.getTag(key)
                        .copy());
            }
            rows.appendTag(row);
        }
        expanded.setTag("cells", rows);
        return expanded;
    }

    private static void rejects(byte[] bytes, String reason) {
        rejects(bytes, 128 * 1024 * 1024, 512L * 1024 * 1024, reason);
    }

    private static void rejects(byte[] bytes, int raw, long tracked, String reason) {
        boolean rejected = false;
        try {
            HologramSnapshotCodec.decode(bytes, raw, tracked);
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, reason);
    }

    private static final class TrackingSize extends NBTSizeTracker {

        long bytes;

        TrackingSize() {
            super(Long.MAX_VALUE);
        }

        @Override
        public void func_152450_a(long bits) {
            bytes += bits / 8;
        }
    }

    private static void hostileArray() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(7);
            output.writeUTF("hostile");
            output.writeInt(536870912); // 8 * length wraps to zero in vanilla size accounting.
        }
        boolean rejected = false;
        try {
            HologramSnapshotCodec.decode(bytes.toByteArray());
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "overflowing vanilla array length is rejected before allocation");
    }

    private static void badFragment() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeLong(81);
            buffer.writeInt(HologramSnapshotAssembly.MAX_BYTES + 1);
            buffer.writeInt(0);
            buffer.writeInt(1);
            buffer.writeByte(0);
            boolean rejected = false;
            try {
                new HologramNetwork.Fragment().fromBytes(buffer);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            SimpleAssert.that(rejected, "declared transport budget rejects hostile packet");
        } finally {
            buffer.release();
        }
    }

    private static void malformedUtf() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(8);
            output.writeUTF("name");
            output.writeShort(1);
            output.writeByte(0xc0); // Two-byte modified UTF sequence with its continuation missing.
            output.writeByte(0);
        }
        boolean rejected = false;
        try {
            HologramSnapshotCodec.decode(bytes.toByteArray());
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "malformed UTF rejected without invoking vanilla CrashReport hooks");
    }
}
