package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** The raw author source is editable before target review and never spends an unbounded owner-thread turn. */
final class HologramSourceTransferRegression {

    static void run(HologramSession owner) throws IOException, ReflectiveOperationException {
        HologramAuthorSaveSnapshotRegression.run(owner);
        HologramSession s = fixture(owner);
        s.x = 100;
        s.y = 64;
        s.z = -200;
        HologramCapture.Cell unknown = cell(s, 0);
        unknown.unknown = true;
        unknown.blueprintBlock = "missingmod:machine";
        unknown.blueprintItem = "missingmod:machine";
        unknown.tileNbt = "retained-native-payload";
        unknown.itemNbt = "retained-item-payload";
        unknown.structureTargets = new ArrayList<>(Arrays.asList(new ItemStack(Blocks.stone, 1, 0)));
        s.capture.cells.add(unknown);
        HologramPresets.Cell source = new HologramPresets.Cell();
        source.defaultItem = "minecraft:stone";
        source.defaultDamage = 3;
        source.count = 7;
        source.allowedTargets = new ArrayList<>();
        HologramPresets.Target absent = new HologramPresets.Target();
        absent.item = "missingmod:declared_hatch";
        absent.damage = 11;
        source.allowedTargets.add(absent);
        s.virtualPreset.cells.add(source);
        unknown.structureIds = Arrays.asList("shared-structure");
        HologramCapture.Cell second = cell(s, 1);
        second.unknown = true;
        second.blueprintBlock = unknown.blueprintBlock;
        second.blueprintItem = unknown.blueprintItem;
        second.structureTargets = new ArrayList<>(unknown.structureTargets);
        second.structureIds = new ArrayList<>(unknown.structureIds);
        s.capture.cells.add(second);
        HologramPresets.Cell secondSource = HologramSnapshotMetadataSource.copyCell(source);
        secondSource.x = 1;
        s.virtualPreset.cells.add(secondSource);
        HologramPresets.HintArea hint = new HologramPresets.HintArea();
        hint.prompt = "retain declaration hint";
        s.virtualPreset.hintAreas.add(hint);
        s.virtualPreset.retainedNbtKeys = new ArrayList<>(Arrays.asList("configuration"));
        HologramSourceTransfer transfer = attach(s, 71);
        Field frameField = HologramSourceTransfer.class.getDeclaredField("sourceFrame");
        frameField.setAccessible(true);
        com.miaokatze.gtmb.blueprint.editor.AuthorDocument.SourceFrame frame = (com.miaokatze.gtmb.blueprint.editor.AuthorDocument.SourceFrame) frameField
            .get(transfer);
        SimpleAssert.eq(
            com.miaokatze.gtmb.blueprint.editor.AuthorDocument.SourceFrame.captured(s.dimension, 100, 64, -200),
            frame,
            "transfer freezes actual session coordinate basis");
        s.x++;
        SimpleAssert.that(!transfer.sourceUnchanged(), "origin changes invalidate the frozen transfer");
        s.x--;
        HologramPresets.Preset output = result(transfer);
        SimpleAssert.that(!transfer.step(0, Long.MAX_VALUE, () -> 0), "zero work cannot project metadata or cells");
        SimpleAssert.that(!transfer.step(100, 0, () -> 0), "expired deadline performs no first operation");
        SimpleAssert.eq(0, output.hintAreas.size(), "expired deadline leaves the header untouched");
        int turns = 0;
        while (!transfer.step(1, Long.MAX_VALUE, () -> 0)) {
            SimpleAssert.that(++turns < 100, "single-record cursor converges");
        }
        HologramPresets.Cell saved = output.cells.get(0);
        SimpleAssert.eq("missingmod:machine", saved.item, "unknown identity stays an unresolved declaration");
        SimpleAssert.eq("retained-native-payload", saved.tileNbt, "source retains native payload before local review");
        SimpleAssert.eq("retained-item-payload", saved.itemNbt, "source retains item payload before local review");
        SimpleAssert.eq("minecraft:stone", saved.defaultItem, "source-only fallback identity survives projection");
        SimpleAssert.eq(3, saved.defaultDamage, "source-only fallback subtype survives projection");
        SimpleAssert.eq(7, saved.count, "source-only count survives projection");
        SimpleAssert
            .eq(2, saved.allowedTargets.size(), "actual candidates and missing-registry declarations both survive");
        SimpleAssert.that(saved.allowedTargets.get(1) != absent, "unresolved candidate DTO is detached");
        SimpleAssert.that(
            saved.allowedTargets == output.cells.get(1).allowedTargets,
            "identical declared choices are one read-only owned list rather than duplicate graphs");
        SimpleAssert.that(
            saved.structureIds == output.cells.get(1).structureIds,
            "identical memberships are one read-only owned list");
        SimpleAssert.that(output.hintAreas.get(0) != hint, "hint metadata is detached record by record");
        SimpleAssert.that(output.retainedNbtKeys != s.virtualPreset.retainedNbtKeys, "retention list is not shared");
        SimpleAssert.that(transfer.sourceUnchanged(), "original exact identity remains current");
        NBTTagCompound identity = identity(s, 71);
        SimpleAssert.that(transfer.matches(identity), "cancellation identity matches exact session/revision/task");
        identity.setLong("clientTaskToken", 72);
        SimpleAssert.that(!transfer.matches(identity), "another client task cannot cancel the transfer");
        identity = identity(s, 71);
        identity.setLong("planRevision", s.planRevision + 1);
        SimpleAssert.that(!transfer.matches(identity), "another revision cannot cancel the transfer");
        identity = identity(s, 71);
        identity.setString("session", "another-session");
        SimpleAssert.that(!transfer.matches(identity), "another session cannot cancel the transfer");
        s.planRevision++;
        SimpleAssert.that(!transfer.sourceUnchanged(), "source revision change invalidates the cursor");
        boolean staleRejected = false;
        try {
            transfer.step(1, Long.MAX_VALUE, () -> 0);
        } catch (IOException expected) {
            staleRejected = true;
        }
        SimpleAssert.that(staleRejected, "stale source cannot continue even within an admitted slice");
        HologramSourceTransfer.cancel(s);
        SimpleAssert.that(s.sourceTransfer == null && result(transfer) == null, "cancel releases the owned graph");

        s = fixture(owner);
        HologramCapture.Cell placeholder = cell(s, 0);
        placeholder.block = com.gtnewhorizon.structurelib.StructureLibAPI.getBlockHint();
        placeholder.unknown = true;
        placeholder.structureTargets = new ArrayList<>(Arrays.asList(new ItemStack(Blocks.stone)));
        s.capture.cells.add(placeholder);
        HologramPresets.Cell raw = HologramBlueprintProjection
            .projectedCell(s, 0, new HologramPresets.Preset(), new java.util.HashSet<>(), true);
        SimpleAssert
            .eq(1, raw.allowedTargets.size(), "unresolved placeholder hands editable choices to local authoring");
        boolean reviewedRejected = false;
        try {
            HologramBlueprintProjection.projectedCell(s, 0, new HologramPresets.Preset(), new java.util.HashSet<>());
        } catch (IOException expected) {
            reviewedRejected = true;
        }
        SimpleAssert.that(reviewedRejected, "reviewed export still rejects unresolved placeholders");

        s = fixture(owner);
        for (int i = 0; i < 4096; i++) s.capture.cells.add(cell(s, i));
        transfer = attach(s, 73);
        output = result(transfer);
        SimpleAssert.that(
            !transfer.step(HologramSourceTransfer.MAX_WORK, Long.MAX_VALUE, () -> 0),
            "large source cannot finish in one bounded slice");
        SimpleAssert.that(output.cells.size() <= HologramSourceTransfer.MAX_WORK, "hard per-slice cell cap is honored");
        int before = output.cells.size();
        final long[] clock = { 0 };
        transfer.step(100, 3, () -> clock[0]++);
        SimpleAssert.eq(before + 3, output.cells.size(), "deadline is checked between every projected cell");
        Field retained = HologramSourceTransfer.class.getDeclaredField("retainedBytes");
        retained.setAccessible(true);
        retained.setLong(
            transfer,
            retained.getLong(transfer) + HologramSourceTransferBudget.LIMIT
                - transfer.ownedBytes()
                - HologramSourceTransferBudget.CELL
                - 16);
        transfer.step(1, Long.MAX_VALUE, () -> 0);
        SimpleAssert.that(
            transfer.ownedBytes() == HologramSourceTransferBudget.LIMIT,
            "exact physical owned-byte boundary remains admitted");
        boolean capacityRejected = false;
        try {
            transfer.step(1, Long.MAX_VALUE, () -> 0);
        } catch (IOException expectedFailure) {
            capacityRejected = true;
        }
        SimpleAssert.that(capacityRejected, "a real additional owned row beyond the unchanged quota fails terminally");
        HologramCapture sameSizeReplacement = new HologramCapture(null, new ItemStack(Blocks.stone));
        sameSizeReplacement.cells.addAll(s.capture.cells);
        s.capture = sameSizeReplacement;
        SimpleAssert.that(!transfer.sourceUnchanged(), "equal-size replacement capture does not reuse authority");
        HologramSourceTransfer.cancel(s);

        largeOwnedSource(owner);
        privateFixtures(owner);

        NBTTagCompound expected = identity(owner, 91), changed = (NBTTagCompound) expected.copy();
        SimpleAssert.that(HologramServerLibrary.sameAuthorSource(expected, changed), "worker reservation exact match");
        changed.setLong("clientTaskToken", 92);
        SimpleAssert
            .that(!HologramServerLibrary.sameAuthorSource(expected, changed), "superseded worker callback is rejected");
        final int[] released = { 0 };
        HologramServerLibrary.Preparation preparation = new HologramServerLibrary.Preparation(91);
        preparation.release = () -> released[0]++;
        preparation.valid = false;
        preparation.action = () -> { throw new AssertionError("retired worker must not encode"); };
        preparation.run();
        preparation.close();
        SimpleAssert.eq(1, released[0], "retired preparation releases its DTO lease exactly once");
    }

    private static void largeOwnedSource(HologramSession owner) throws IOException, ReflectiveOperationException {
        HologramSession s = fixture(owner);
        // Matches the largest fixture's shape: no declaration/member lists. Repeated immutable payload
        // deliberately exceeds logical JSON expansion; this tests heap ownership, not codec admission.
        String sharedPayload = new String(new char[4096]).replace('\0', 'x');
        for (int i = 0; i < 123_369; i++) {
            HologramCapture.Cell captured = cell(s, i);
            captured.tileNbt = sharedPayload;
            s.capture.cells.add(captured);
        }
        HologramSourceTransfer transfer = attach(s, 81);
        int turns = 0;
        while (!transfer.step(HologramSourceTransfer.MAX_WORK, Long.MAX_VALUE, () -> 0))
            SimpleAssert.that(++turns < 1000, "largest cheap capture converges through bounded slices");
        HologramPresets.Preset output = result(transfer);
        SimpleAssert.eq(123_369, output.cells.size(), "largest actual cell count fits the unchanged physical quota");
        SimpleAssert.that(
            transfer.ownedBytes() < HologramSourceTransferBudget.LIMIT,
            "shared payload/registry strings are not charged as new per-cell allocations");
        SimpleAssert.that(
            output.cells.get(0).tileNbt == sharedPayload && output.cells.get(123_368).tileNbt == sharedPayload,
            "immutable capture payload alias ownership is proven");
        SimpleAssert.that(
            output.cells.get(0).structureIds == output.cells.get(123_368).structureIds,
            "empty membership lists have one canonical immutable instance");
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        long extent = 16;
        for (Field field : HologramPresets.Cell.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            int width = !type.isPrimitive() ? unsafe.arrayIndexScale(Object[].class)
                : type == long.class || type == double.class ? 8
                    : type == int.class || type == float.class ? 4 : type == short.class || type == char.class ? 2 : 1;
            extent = Math.max(extent, unsafe.objectFieldOffset(field) + width);
        }
        long measured = HologramSourceTransferBudget.align(extent);
        SimpleAssert.that(
            measured <= HologramSourceTransferBudget.CELL,
            "actual native JVM field extent fits the uncompressed conservative row-layout budget");
        System.out.println(
            "author-source owned-byte regression: cells=" + output.cells.size()
                + " fieldExtent="
                + measured
                + " conservativeCell="
                + HologramSourceTransferBudget.CELL
                + " ownedBytes="
                + transfer.ownedBytes()
                + " quota="
                + HologramSourceTransferBudget.LIMIT);
        HologramSourceTransfer.cancel(s);
    }

    /** Optional private inputs stay outside the repository and are never printed, copied, or committed. */
    private static void privateFixtures(HologramSession owner) throws IOException, ReflectiveOperationException {
        String fixtures = System.getenv("GTMB_AUTHOR_SOURCE_FIXTURES");
        if (fixtures == null || fixtures.isEmpty()) return;
        for (String path : fixtures.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            com.google.gson.JsonObject root = HologramBlueprintCompactCodec
                .parse(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
            com.google.gson.JsonElement model = root.has("blueprint") ? root.get("blueprint")
                : root.getAsJsonArray("presets")
                    .get(0);
            HologramPresets.Preset original = new com.google.gson.Gson().fromJson(model, HologramPresets.Preset.class);
            HologramSession s = fixture(owner);
            s.virtualPreset = original;
            for (HologramPresets.Cell saved : original.cells) {
                HologramCapture.Cell captured = new HologramCapture.Cell(
                    null,
                    s.x + saved.x,
                    s.y + saved.y,
                    s.z + saved.z);
                // Raw declarations do not depend on which optional fixture mods happen to be registered.
                // This exercises the actual source fields and alias graph, not synthetic per-row estimates.
                captured.userPosition = true;
                captured.unknown = true;
                captured.block = Blocks.air;
                captured.blueprintItem = saved.item == null ? "" : saved.item;
                captured.blueprintBlock = saved.block == null ? "" : saved.block;
                captured.blueprintDamage = saved.damage;
                captured.meta = saved.metadata;
                captured.tileNbt = saved.tileNbt == null ? "" : saved.tileNbt;
                captured.itemNbt = saved.itemNbt == null ? "" : saved.itemNbt;
                captured.prompt = saved.prompt == null ? "" : saved.prompt;
                captured.structureId = saved.structureId == null ? "" : saved.structureId;
                captured.structureIds = saved.structureIds;
                captured.excluded = saved.excluded;
                captured.role = saved.controller ? "templateController" : "prefab";
                if (saved.allowedTargets != null) {
                    captured.structureTargets = new ArrayList<>();
                    for (HologramPresets.Target target : saved.allowedTargets) {
                        ItemStack stack = target.stack();
                        if (stack != null) captured.structureTargets.add(stack);
                    }
                }
                s.capture.cells.add(captured);
            }
            HologramSourceTransfer transfer = attach(s, 82);
            int slices = 0;
            while (!transfer.step(HologramSourceTransfer.MAX_WORK, Long.MAX_VALUE, () -> 0))
                SimpleAssert.that(++slices < 20_000, "private fixture source finishes bounded projection");
            HologramPresets.Preset projected = result(transfer);
            SimpleAssert.eq(
                original.cells.size(),
                projected.cells.size(),
                "private fixture exact actual cell count survives handoff");
            for (int i = 0; i < original.cells.size(); i++) {
                HologramPresets.Cell input = original.cells.get(i), output = projected.cells.get(i);
                if (input.tileNbt != null && !input.tileNbt.isEmpty()) SimpleAssert.that(
                    input.tileNbt == output.tileNbt,
                    "private fixture native payload aliases its captured immutable String");
                if (input.itemNbt != null && !input.itemNbt.isEmpty()) SimpleAssert.that(
                    input.itemNbt == output.itemNbt,
                    "private fixture item payload aliases its captured immutable String");
                SimpleAssert.eq(
                    input.allowedTargets == null ? 0 : input.allowedTargets.size(),
                    output.allowedTargets == null ? 0 : output.allowedTargets.size(),
                    "private fixture declared target count retained");
            }
            SimpleAssert.that(
                transfer.ownedBytes() <= HologramSourceTransferBudget.LIMIT,
                "actual private fixture source fits the unchanged owned allocation quota");
            System.out.println(
                "author-source private-fixture regression: cells=" + projected.cells.size()
                    + " slices="
                    + slices
                    + " ownedBytes="
                    + transfer.ownedBytes()
                    + " quota="
                    + HologramSourceTransferBudget.LIMIT);
            HologramSourceTransfer.cancel(s);
        }
    }

    private static HologramSession fixture(HologramSession owner) {
        HologramSession s = new HologramSession(owner.player, 0, 64, 0, false, 2);
        s.virtual = true;
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.schema = 2;
        s.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        return s;
    }

    private static HologramCapture.Cell cell(HologramSession s, int x) {
        HologramCapture.Cell cell = new HologramCapture.Cell(null, s.x + x, s.y, s.z);
        cell.userPosition = true;
        cell.block = Blocks.air;
        return cell;
    }

    private static HologramSourceTransfer attach(HologramSession s, long token) {
        HologramSourceTransfer transfer = new HologramSourceTransfer(s, token);
        s.sourceTransfer = transfer;
        return transfer;
    }

    private static HologramPresets.Preset result(HologramSourceTransfer transfer) throws ReflectiveOperationException {
        Field result = HologramSourceTransfer.class.getDeclaredField("result");
        result.setAccessible(true);
        return (HologramPresets.Preset) result.get(transfer);
    }

    private static NBTTagCompound identity(HologramSession s, long token) {
        NBTTagCompound identity = new NBTTagCompound();
        identity.setString("session", s.id);
        identity.setLong("planRevision", s.planRevision);
        identity.setLong("clientTaskToken", token);
        return identity;
    }
}
