package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exact palette projection, mutable-consumer isolation and mixed transport-cache identities. */
public final class HologramSnapshotRowsRegression {

    private HologramSnapshotRowsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramSnapshotRowsRegression.class, args)) return;
        run();
        System.out.println("HologramSnapshotRowsRegression passed");
    }

    public static void run() throws Exception {
        exactRows();
        boundaries();
        sharedOwnerPage();
        transportFormats();
        referenceProjection();
    }

    public static NBTTagCompound compactForTest(NBTTagCompound source) {
        NBTTagCompound result = new NBTTagCompound();
        for (Object raw : source.func_150296_c()) {
            String key = (String) raw;
            if (!key.equals("cells")) result.setTag(
                key,
                source.getTag(key)
                    .copy());
        }
        NBTTagList rows = source.getTagList("cells", 10), packed = new NBTTagList();
        HologramSnapshotRows.Builder builder = new HologramSnapshotRows.Builder();
        for (int i = 0; i < rows.tagCount(); i++) packed.appendTag(builder.add(rows.getCompoundTagAt(i)));
        result.setTag("cells", packed);
        return result;
    }

    private static NBTTagCompound row(int index) {
        NBTTagCompound row = new NBTTagCompound();
        String[] axes = { "x", "y", "z", "dx", "dy", "dz" };
        for (int axis = 0; axis < axes.length; axis++) row.setInteger(axes[axis], index + axis);
        row.setString("wantId", "minecraft:stone");
        row.setString("status", "preview");
        row.setTag("empty", new NBTTagCompound());
        row.setTag("entries", new NBTTagList());
        row.setByteArray("bytes", new byte[] { -1, 0, 127 });
        row.setIntArray("numbers", new int[] { Integer.MIN_VALUE, 0, Integer.MAX_VALUE });
        return row;
    }

    private static void exactRows() throws Exception {
        List<NBTTagCompound> source = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            NBTTagCompound row = row(index);
            row.setDouble("double", index % 2 == 0 ? 0.0 : -0.0);
            row.setFloat("float", Float.intBitsToFloat(0x7fc00000 + index));
            if (index == 3) row.removeTag("empty");
            source.add(row);
        }
        NBTTagCompound page = HologramSnapshotRows.compactPage(source);
        HologramSnapshotRows.validatePage(page, source.size());
        NBTTagList projected = HologramSnapshotRows.project(page, true);
        for (int index = 0; index < source.size(); index++) SimpleAssert.that(
            HologramSnapshotNbt.same(source.get(index), HologramSnapshotRows.export(projected.getCompoundTagAt(index))),
            "palette export preserves absence, empty values, arrays and exact numeric bits");
        SimpleAssert.that(
            projected.getCompoundTagAt(0)
                .getTag(HologramSnapshotRows.PALETTE)
                == projected.getCompoundTagAt(1)
                    .getTag(HologramSnapshotRows.PALETTE),
            "one publication shares one immutable palette");
        projected.getCompoundTagAt(0)
            .getTagList(HologramSnapshotRows.PALETTE, 10)
            .getCompoundTagAt(0)
            .setString("wantId", "changed");
        SimpleAssert.eq(
            "minecraft:stone",
            page.getTagList(HologramSnapshotRows.PALETTE, 10)
                .getCompoundTagAt(0)
                .getString("wantId"),
            "publication palette cannot mutate the cached page");
        NBTTagCompound invalid = (NBTTagCompound) page.copy();
        invalid.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setIntArray("position", new int[5]);
        rejects(invalid, source.size());
        invalid = (NBTTagCompound) page.copy();
        invalid.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setInteger("descriptor", 99);
        rejects(invalid, source.size());
        NBTTagCompound old = row(1);
        old.removeTag("dy");
        NBTTagCompound fallback = HologramSnapshotRows.compactPage(Collections.singletonList(old));
        SimpleAssert.that(
            !HologramSnapshotRows.isCompactPage(fallback) && HologramSnapshotNbt.same(
                old,
                fallback.getTagList("cells", 10)
                    .getCompoundTagAt(0)),
            "legacy rows without six typed coordinates remain byte-semantic full rows");
    }

    private static void rejects(NBTTagCompound page, int count) {
        try {
            HologramSnapshotRows.validatePage(page, count);
            throw new AssertionError("Malformed compact page must fail");
        } catch (java.io.IOException expected) {}
    }

    private static void boundaries() {
        HologramSnapshotRows.Builder builder = new HologramSnapshotRows.Builder();
        NBTTagCompound first = builder.add(row(0));
        for (int i = 1; i < 256; i++) builder.add(row(i));
        NBTTagCompound next = builder.add(row(256));
        SimpleAssert.that(
            first.getTag(HologramSnapshotRows.PALETTE) != next.getTag(HologramSnapshotRows.PALETTE),
            "owner preparation bounds each palette to 256 cells");
        SimpleAssert.eq(
            1,
            first.getTagList(HologramSnapshotRows.PALETTE, 10)
                .tagCount(),
            "positions do not prevent exact descriptor reuse");
        SimpleAssert.eq(
            0,
            HologramSnapshotRows.export(first)
                .getInteger("x"),
            "a later owner page cannot change an earlier compact row");
    }

    private static void sharedOwnerPage() throws Exception {
        HologramSnapshotRows.Builder builder = new HologramSnapshotRows.Builder();
        List<NBTTagCompound> source = new ArrayList<>();
        for (int i = 0; i < 256; i++) source.add(builder.add(row(i)));
        NBTTagCompound page = HologramSnapshotRows.compactPage(source);
        HologramSnapshotRows.validatePage(page, source.size());
        SimpleAssert.that(
            page.getTag(HologramSnapshotRows.PALETTE) == source.get(0)
                .getTag(HologramSnapshotRows.PALETTE),
            "wire projection reuses an already-proved owner palette without expanding every descriptor");
        NBTTagList projected = HologramSnapshotRows.project((NBTTagCompound) page.copy(), false);
        for (int i = 0; i < source.size(); i++) SimpleAssert.that(
            HologramSnapshotNbt.same(row(i), HologramSnapshotRows.export(projected.getCompoundTagAt(i))),
            "direct owner-page projection retains every field and coordinate");
        source.get(0)
            .setString("overlay", "model-only replacement");
        NBTTagCompound changed = HologramSnapshotRows.compactPage(source);
        NBTTagList overlay = HologramSnapshotRows.project(changed, false);
        SimpleAssert.eq(
            "model-only replacement",
            HologramSnapshotRows.export(overlay.getCompoundTagAt(0))
                .getString("overlay"),
            "rows with overlays use exact re-interning rather than the common-page shortcut");
        SimpleAssert.that(
            !HologramSnapshotRows.export(overlay.getCompoundTagAt(1))
                .hasKey("overlay"),
            "one row's overlay does not change other descriptor users");
    }

    private static void transportFormats() throws Exception {
        HologramSnapshotSegmentEncoding.clear();
        HologramCapture capture = new HologramCapture(
            null,
            new net.minecraft.item.ItemStack(net.minecraft.init.Blocks.stone));
        List<NBTTagCompound> source = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            NBTTagCompound row = row(i);
            source.add(row);
            capture.cells
                .add(new HologramCapture.Cell(null, row.getInteger("x"), row.getInteger("y"), row.getInteger("z")));
        }
        byte[] first = encoded(capture, source, false), compact = encoded(capture, source, true),
            last = encoded(capture, source, false);
        SimpleAssert
            .that(java.util.Arrays.equals(first, last), "old-new-old cache negotiation restores identical old bytes");
        SimpleAssert.that(
            !java.util.Arrays.equals(first, compact),
            "different row formats never reuse the same cached encoding");
        try (HologramSnapshotSegments.Decoder decoder = new HologramSnapshotSegments.Decoder()) {
            NBTTagCompound full = decoder.decode(first), packed = decoder.decode(compact);
            for (int i = 0; i < source.size(); i++) SimpleAssert.that(
                HologramSnapshotNbt.same(
                    full.getTagList("cells", 10)
                        .getCompoundTagAt(i),
                    HologramSnapshotRows.export(
                        packed.getTagList("cells", 10)
                            .getCompoundTagAt(i))),
                "production segmented formats preserve every cell field");
        } finally {
            HologramSnapshotSegmentEncoding.clear();
        }
    }

    private static byte[] encoded(HologramCapture capture, List<NBTTagCompound> source, boolean compact)
        throws Exception {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("session", "rows-format");
        NBTTagList cells = new NBTTagList();
        HologramSnapshotRows.Builder rows = new HologramSnapshotRows.Builder();
        try (HologramTransportPageLayout.Builder layout = new HologramTransportPageLayout.Builder(
            "rows-format",
            capture,
            0,
            0,
            0,
            null,
            source.size())) {
            for (int i = 0; i < source.size(); i++) {
                layout.add(i, source.get(i));
                cells.appendTag(compact ? rows.add(source.get(i)) : source.get(i));
            }
            root.setTag("cells", cells);
            try (HologramTransportPageLayout frozen = layout.finish()) {
                HologramSnapshotSegmentEncoding.Result result = HologramSnapshotSegmentEncoding
                    .encode(root, frozen, Collections.emptySet(), () -> true, ignored -> {});
                try {
                    return result.bytes.bytes.clone();
                } finally {
                    result.bytes.close();
                }
            }
        }
    }

    private static void referenceProjection() throws Exception {
        long before = HologramProcessGovernor.retainedBytes();
        List<NBTTagCompound> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) rows.add(row(i));
        NBTTagCompound page = HologramSnapshotRows.compactPage(rows);
        byte[] cold = compactFrame(page, false), reference = compactFrame(page, true);
        try (HologramSnapshotSegments.Decoder decoder = new HologramSnapshotSegments.Decoder()) {
            try (HologramSnapshotCodec.OwnedSnapshot first = decoder.decodeOwned(cold, () -> true);
                HologramSnapshotCodec.OwnedSnapshot second = decoder.decodeOwned(reference, () -> true)) {
                NBTTagCompound firstRow = first.value.getTagList("cells", 10)
                    .getCompoundTagAt(0);
                NBTTagCompound secondRow = second.value.getTagList("cells", 10)
                    .getCompoundTagAt(0);
                SimpleAssert.that(
                    firstRow.getTag(HologramSnapshotRows.PALETTE) != secondRow.getTag(HologramSnapshotRows.PALETTE),
                    "reference projections own independent palette copies");
                NBTTagCompound exported = HologramSnapshotRows.export(firstRow);
                exported.getByteArray("bytes")[0] = 42;
                SimpleAssert.that(
                    HologramSnapshotNbt.same(rows.get(0), HologramSnapshotRows.export(secondRow)),
                    "a mutable export cannot corrupt a reference publication");
                firstRow.getIntArray("position")[0] = 99;
                firstRow.getTagList(HologramSnapshotRows.PALETTE, 10)
                    .getCompoundTagAt(0)
                    .setString("wantId", "changed");
                try (HologramSnapshotCodec.OwnedSnapshot third = decoder.decodeOwned(reference, () -> true)) {
                    SimpleAssert.that(
                        HologramSnapshotNbt.same(
                            rows.get(0),
                            HologramSnapshotRows.export(
                                third.value.getTagList("cells", 10)
                                    .getCompoundTagAt(0))),
                        "published row mutations cannot corrupt the exact cached page");
                }
            }
            decoder.clear();
            try {
                decoder.decodeOwned(reference, () -> true);
                throw new AssertionError("retired compact references require a full frame");
            } catch (HologramSnapshotSegments.MissingSegmentException expected) {
                SimpleAssert.eq("compact-reference", expected.session, "compact recovery retains its exact session");
            }
        }
        SimpleAssert
            .eq(before, HologramProcessGovernor.retainedBytes(), "compact cache and projections retire explicitly");
    }

    private static byte[] compactFrame(NBTTagCompound page, boolean reference) throws Exception {
        int count = page.getTagList("cells", 10)
            .tagCount();
        NBTTagCompound header = new NBTTagCompound();
        header.setString("session", "compact-reference");
        header.setIntArray("gtmbTransportPageSizes", new int[] { count });
        int[] order = new int[count];
        for (int i = 0; i < count; i++) order[i] = i;
        header.setIntArray("gtmbTransportOrder", order);
        long[] headerRaw = { 0 }, pageRaw = { 0 };
        byte[] manifest = HologramSnapshotCodec.compress(header, () -> true, bytes -> headerRaw[0] = bytes);
        byte[] encoded = HologramSnapshotCodec.compress(page, () -> true, bytes -> pageRaw[0] = bytes);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
            output.writeInt(0x47545332);
            output.writeInt(2);
            output.writeInt((int) headerRaw[0]);
            output.writeInt(manifest.length);
            output.write(manifest);
            output.writeInt(count);
            output.writeInt(1);
            output.write(
                HologramLibraryProtocol.digest()
                    .digest(encoded));
            output.writeInt((int) pageRaw[0]);
            output.writeInt(reference ? 0 : encoded.length);
            if (!reference) output.write(encoded);
        }
        return bytes.toByteArray();
    }
}
