// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class HologramPlacementRequestRegression {

    private HologramPlacementRequestRegression() {}

    static void run() {
        HologramProjectedPlacement.Cell first = new HologramProjectedPlacement.Cell(
            -2,
            0,
            5,
            "minecraft:stone",
            0,
            "minecraft:stone",
            0,
            -1);
        HologramProjectedPlacement.Cell second = new HologramProjectedPlacement.Cell(
            0,
            2,
            -2,
            "minecraft:stone",
            0,
            "minecraft:stone",
            0,
            -1);
        HologramProjectedPlacement.Plan plan = new HologramProjectedPlacement.Plan(
            Arrays.asList(first, second),
            Collections.singletonList(new HologramProjectedPlacement.Need("minecraft:stone", 0, 2)));
        try {
            UUID id = UUID.randomUUID();
            NBTTagCompound tag = HologramPlacementRequest.encode(id, new int[] { 100, 64, 200 }, plan);
            HologramPlacementRequest.Header header = HologramPlacementRequest.header(tag);
            HologramPlacementRequest.Decoded decoded = HologramPlacementRequest.decode(header, tag, 256);
            if (HologramNetwork.prepareProjectedPlacement(tag).placementUpload != null)
                throw new AssertionError("A small execution request no longer uses the normal Action packet");
            if (!id.toString()
                .equals(header.id()) || decoded.size() != 2
                || decoded.cell(0).x() != -2
                || decoded.cell(1).z() != -2
                || !tag.getCompoundTag("blueprint")
                    .hasNoTags()
                || tag.getTagList("placementVariants", 10)
                    .tagCount() != 1)
                throw new AssertionError("Projected operation wire differs from the client plan");

            NBTTagCompound underpaid = (NBTTagCompound) tag.copy();
            underpaid.getTagList("placementMaterials", 10)
                .getCompoundTagAt(0)
                .setInteger("count", 1);
            reject(header, underpaid);
            NBTTagCompound invalidVariant = (NBTTagCompound) tag.copy();
            invalidVariant.getIntArray("placementOperations")[3] = 1;
            reject(header, invalidVariant);
            NBTTagCompound aboveWorld = (NBTTagCompound) tag.copy();
            aboveWorld.getIntArray("placementOperations")[1] = 200;
            reject(header, aboveWorld);
            NBTTagCompound duplicate = (NBTTagCompound) tag.copy();
            int[] operations = duplicate.getIntArray("placementOperations");
            System.arraycopy(operations, 0, operations, 4, 4);
            reject(header, duplicate);
            deviceRoundTrip(id);
            localController(id);
            testOrdinaryEligibility();
            segmentedPlacement(id, tag);
            byte[] detachedCells = ("{\"schema\":2,\"blueprint\":{\"schema\":2,\"id\":\"old\",\"name\":\"Old\","
                + "\"structures\":[{\"id\":\"removed-provider\",\"sourceClass\":\"missing.Source\",\"main\":0}],"
                + "\"cells\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\","
                + "\"metadata\":0,\"damage\":0,\"structureId\":\"removed-group\"}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            HologramProjectedPlacement.Plan detached = HologramProjectedPlacement
                .compile(detachedCells, new int[] { 100, 64, 200 }, new int[0], 256, () -> true);
            if (detached.cells().size() != 1 || detached.needs().get(0).count() != 1)
                throw new AssertionError("Stored grid operations must be independent of old declaration or group data");
        } catch (IOException | InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void segmentedPlacement(UUID id, NBTTagCompound seed) throws IOException, InterruptedException {
        NBTTagCompound plan = (NBTTagCompound) seed.copy();
        plan.setIntArray("placementAnchor", new int[] { 0, 0, 0 });
        int count = 360_000;
        int[] operations = new int[4 * count];
        Random random = new Random(123_365);
        for (int i = 0; i < count; i++) {
            operations[4 * i] = i;
            operations[4 * i + 1] = i & 255;
            operations[4 * i + 2] = random.nextInt(60_000_000) - 30_000_000;
        }
        plan.setIntArray("placementOperations", operations);
        plan.getTagList("placementMaterials", 10).getCompoundTagAt(0).setInteger("count", count);
        if (HologramNetwork.prepareProjectedPlacement(plan).placementUpload == null)
            throw new AssertionError("The large placement is still routed through the single packet");
        byte[] compressed = HologramSnapshotCodec.compress(plan);
        if (compressed.length <= 1024 * 1024) throw new AssertionError("Large placement did not exceed old single-packet cap");
        byte[] checksum = HologramPlacementUpload.digest(compressed);
        HologramPlacementUpload.Fragment first = fragment(id, compressed, checksum, 0);
        ByteBuf wire = Unpooled.buffer();
        try {
            first.toBytes(wire);
            if (wire.readableBytes() >= Short.MAX_VALUE) throw new AssertionError("Placement fragment exceeds C17 size");
            HologramPlacementUpload.Fragment received = new HologramPlacementUpload.Fragment();
            received.fromBytes(wire);
            first = received;
        } finally {
            wire.release();
        }
        HologramPlacementUpload.Assembly assembly = new HologramPlacementUpload.Assembly(first);
        HologramPlacementUpload.Assembly corrupt = new HologramPlacementUpload.Assembly(first);
        expectFailure(assembly::decode);
        expectFailure(() -> new HologramPlacementUpload.Assembly(fragment(id, compressed, checksum, 1)));
        expectFailure(() -> new HologramPlacementUpload.Assembly(new HologramPlacementUpload.Fragment(
            id, HologramPlacementUpload.MAX_COMPRESSED + 1, 0, checksum, firstData(compressed))));
        if (assembly.accept(first) != null || corrupt.accept(first) != null)
            throw new AssertionError("A partial upload returned the operation payload");
        HologramPlacementUpload.Fragment duplicate = first;
        expectFailure(() -> assembly.accept(duplicate));
        expectFailure(() -> assembly.accept(fragment(id, compressed, checksum, 2)));
        expectFailure(() -> assembly.accept(fragment(UUID.randomUUID(), compressed, checksum, 1)));
        int chunks = (compressed.length + HologramPlacementUpload.CHUNK_BYTES - 1) / HologramPlacementUpload.CHUNK_BYTES;
        for (int index = 1; index < chunks; index++) {
            HologramPlacementUpload.Fragment frame = fragment(id, compressed, checksum, index);
            byte[] result = assembly.accept(frame);
            if ((result != null) != (index == chunks - 1)) throw new AssertionError("Placement finalized on the wrong fragment");
            if (index == chunks - 1) frame.data[0] ^= 1;
            corrupt.accept(frame);
        }
        expectFailure(corrupt::decode);
        NBTTagCompound restored = assembly.decode();
        HologramPlacementRequest.Decoded decoded = HologramPlacementRequest.decode(
            HologramPlacementRequest.header(restored), restored, 256);
        if (decoded.size() != count || decoded.cell(count - 1).x() != count - 1
            || decoded.cell(count - 1).z() != operations[4 * (count - 1) + 2])
            throw new AssertionError("The segmented plan changed its operations");

        UUID falseOwner = UUID.randomUUID();
        HologramPlacementUpload.Assembly mismatched = new HologramPlacementUpload.Assembly(fragment(falseOwner, compressed, checksum, 0));
        for (int index = 0; index < chunks; index++) mismatched.accept(fragment(falseOwner, compressed, checksum, index));
        expectFailure(mismatched::decode);

        NBTTagCompound unrelated = (NBTTagCompound) seed.copy();
        unrelated.setString("action", "checkPlacementMaterials");
        byte[] wrongAction = HologramSnapshotCodec.compress(unrelated);
        HologramPlacementUpload.Fragment wrong = fragment(id, wrongAction, HologramPlacementUpload.digest(wrongAction), 0);
        HologramPlacementUpload.Assembly wrongKind = new HologramPlacementUpload.Assembly(wrong);
        wrongKind.accept(wrong);
        expectFailure(wrongKind::decode);
    }

    private static byte[] firstData(byte[] encoded) {
        return Arrays.copyOf(encoded, HologramPlacementUpload.CHUNK_BYTES);
    }

    private static HologramPlacementUpload.Fragment fragment(UUID id, byte[] encoded, byte[] checksum, int index) {
        int start = index * HologramPlacementUpload.CHUNK_BYTES;
        int end = Math.min(encoded.length, start + HologramPlacementUpload.CHUNK_BYTES);
        return new HologramPlacementUpload.Fragment(id, encoded.length, index, checksum, Arrays.copyOfRange(encoded, start, end));
    }

    private static void expectFailure(IoStep step) throws IOException {
        try {
            step.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Invalid segmented placement was accepted");
    }

    @FunctionalInterface
    private interface IoStep {

        void run() throws IOException;
    }

    private static void deviceRoundTrip(UUID id) throws IOException, InterruptedException {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setInteger("mID", 12);
        payload.setString(HologramNetworkBindings.GROUP, "q1");
        String state = HologramBlueprintNbt.encodeCanonical(payload);
        HologramProjectedPlacement.Cell device = new HologramProjectedPlacement.Cell(
            0, 0, 0, "gregtech:gt.blockmachines", 0, "gregtech:gt.blockmachines", 12, 3, true, state, "");
        HologramProjectedPlacement.Plan devicePlan = new HologramProjectedPlacement.Plan(
            Collections.singletonList(device),
            Collections.singletonList(new HologramProjectedPlacement.Need(device.item(), device.damage(), 1)));
        NBTTagCompound request = HologramPlacementRequest.encode(id, new int[] { 10, 64, 20 }, devicePlan);
        HologramPlacementRequest.Header header = HologramPlacementRequest.header(request);
        HologramProjectedPlacement.Cell decoded = HologramPlacementRequest.decode(header, request, 256).cell(0);
        if (!decoded.controller() || !payload.equals(HologramBlueprintNbt.decodeData(decoded.tile())))
            throw new AssertionError("Device operation state did not survive the compact payload palette");
        request.getTagList("placementVariants", 10).getCompoundTagAt(0).setByteArray("device", new byte[] { 1 });
        reject(header, request);
    }

    private static void localController(UUID id) throws IOException {
        NBTTagCompound displayed = new NBTTagCompound();
        displayed.setBoolean("supported", true);
        displayed.setInteger("scope", 1);
        displayed.setInteger("layer", 1);
        displayed.setBoolean("materialMe", false);
        displayed.setInteger("materialPriority", 2);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(displayedRow(10, 64, 0, true));
        cells.appendTag(displayedRow(11, 65, 1, false));
        cells.appendTag(displayedRow(11, 64, 0, false));
        displayed.setTag("cells", cells);
        int[] anchor = { 10, 64, 20 };
        NBTTagCompound check = HologramPlacementRequest.encodeMaterialCheck(id, displayed);
        if (check.hasKey("placementAnchor") || check.hasKey("placementOperations")
            || !Collections.singletonMap(new HologramPlacementRequest.Material("minecraft:stone", 0), 1)
                .equals(HologramPlacementRequest.materialCheck(check)))
            throw new AssertionError("Material inspection carried geometry or ignored the displayed scope");
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        HologramPlacementRequest.sourceOptions(check).apply(settings);
        if (settings.me || !settings.main || !settings.containers || settings.priority != 2)
            throw new AssertionError("Explicit material sources did not preserve the held-tool defaults for absent fields");
        HologramProjectedPlacement.Plan build = HologramProjectedPlacement.compileDisplayed(displayed, anchor, 256,
            () -> true);
        if (build.cells().size() != 1 || build.cells().get(0).y() != 1 || build.needs().get(0).count() != 1)
            throw new AssertionError("Controller build ignored the selected layer");
        NBTTagCompound placement = HologramPlacementRequest.encode(id, anchor, build, displayed);
        if (!HologramPlacementRequest.header(placement).sources().equals(HologramPlacementRequest.sourceOptions(check)))
            throw new AssertionError("Placement and standalone check disagree about the selected material sources");
        displayed.setInteger("scope", 0);
        HologramProjectedPlacement.Plan all = HologramProjectedPlacement.compileDisplayed(displayed, anchor, 256,
            () -> true);
        if (all.cells().size() != 2 || all.cells().stream().anyMatch(cell -> cell.x() == 0 && cell.y() == 0 && cell.z() == 0))
            throw new AssertionError("Controller build included its protected anchor");

        NBTTagCompound hint = displayedRow(12, 64, 0, false);
        hint.setString("wantId", "structurelib:blockhint");
        hint.setBoolean("missingBlock", true);
        hint.setBoolean("iconOnly", true);
        hint.setString("status", "unsupported");
        hint.removeTag("targetStack");
        cells.appendTag(hint);
        rejectUnresolvedDisplayed(displayed, anchor);

        displayed.setInteger("scope", 2);
        displayed.setInteger("selected", 1);
        displayed.setIntArray("selectedIndices", new int[] { 1 });
        HologramProjectedPlacement.Plan concrete = HologramProjectedPlacement.compileDisplayed(
            displayed, anchor, 256, () -> true);
        if (concrete.cells().size() != 1 || concrete.cells().get(0).x() != 1 || concrete.cells().get(0).y() != 1
            || concrete.needs().size() != 1 || concrete.needs().get(0).count() != 1)
            throw new AssertionError("An out-of-scope hint blocked a known target or widened the requested scope");

        displayed.setInteger("selected", 3);
        displayed.setIntArray("selectedIndices", new int[] { 3 });
        rejectUnresolvedDisplayed(displayed, anchor);
    }

    private static void rejectUnresolvedDisplayed(NBTTagCompound displayed, int[] anchor) throws IOException {
        try {
            HologramProjectedPlacement.compileDisplayed(displayed, anchor, 256, () -> true);
        } catch (IOException expected) {
            if (!"所选范围包含未确定材料的结构格，请先明确目标方块".equals(expected.getMessage())) throw expected;
            return;
        }
        throw new AssertionError("An unresolved native hint became a placeable operation");
    }

    private static void testOrdinaryEligibility() throws IOException {
        ItemStack stone = new ItemStack(Blocks.stone);
        HologramExecutionEligibility.validate(Blocks.stone, 0, stone, -1, false, null, null);
        NBTTagCompound authority = new NBTTagCompound();
        authority.setString(HologramNetworkBindings.MODE, "preserve");
        try {
            HologramExecutionEligibility.validate(Blocks.stone, 0, stone, -1, false, null, authority);
        } catch (IOException expected) {
            try {
                HologramExecutionEligibility.validate(Blocks.gold_block, 0, stone, -1, false, null, null);
            } catch (IOException wrongPayment) {
                return;
            }
            throw new AssertionError("An operation accepted a material for the wrong block");
        }
        throw new AssertionError("An operation accepted source-world authority");
    }

    private static NBTTagCompound displayedRow(int x, int y, int dy, boolean anchor) {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", x);
        row.setInteger("y", y);
        row.setInteger("z", 20);
        row.setInteger("dy", dy);
        row.setString("wantId", "minecraft:stone");
        row.setBoolean("anchor", anchor);
        row.setTag("targetStack", new ItemStack(Blocks.stone).writeToNBT(new NBTTagCompound()));
        return row;
    }

    private static void reject(HologramPlacementRequest.Header header, NBTTagCompound tag) throws InterruptedException {
        try {
            HologramPlacementRequest.decode(header, tag, 256);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Malformed projected operation was accepted");
    }
}
