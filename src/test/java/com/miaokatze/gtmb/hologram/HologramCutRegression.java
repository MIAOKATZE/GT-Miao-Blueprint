package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Raw clipboard boundaries, lossless disk persistence, private ownership, and preview information boundaries. */
public final class HologramCutRegression {

    private HologramCutRegression() {}

    public static void run() {
        try {
            compositeSelection();
            NBTTagCompound floating = new NBTTagCompound();
            floating.setFloat("nativeFloat", Float.NaN);
            floating.setDouble("nativeDouble", Double.NaN);
            NBTTagList nestedFloating = new NBTTagList();
            nestedFloating.appendTag(floating.copy());
            NBTTagCompound nativeSnapshot = new NBTTagCompound();
            nativeSnapshot.setTag("nativeList", nestedFloating);
            NBTTagCompound readBack = HologramSnapshotCodec.decode(HologramSnapshotCodec.compress(nativeSnapshot));
            SimpleAssert.that(
                HologramCutWorld.sameNbt(nativeSnapshot, readBack),
                "legal native NaN roundtrip is valid disk data");
            readBack.getTagList("nativeList", 10)
                .getCompoundTagAt(0)
                .setDouble("nativeDouble", 1.0);
            SimpleAssert.that(
                !HologramCutWorld.sameNbt(nativeSnapshot, readBack),
                "changed floating payload is not equal disk data");
            SimpleAssert.that(
                !HologramCutWorld
                    .sameNbt(new net.minecraft.nbt.NBTTagDouble(-0.0), new net.minecraft.nbt.NBTTagDouble(0.0)),
                "native signed zero bits remain distinct");
            NBTTagCompound wire = new NBTTagCompound();
            wire.setString("action", "cut");
            wire.setString(
                "requestId",
                UUID.randomUUID()
                    .toString());
            wire.setInteger("x1", -20);
            wire.setInteger("y1", 80);
            wire.setInteger("z1", 42);
            wire.setString("name", "原料缓存 / Raw cache");
            wire.setBoolean(HologramCutNaming.SKIP_PROMPT, true);
            ByteBuf encoded = Unpooled.buffer();
            try {
                new HologramNetwork.CutAction(wire).toBytes(encoded);
                HologramNetwork.CutAction decoded = new HologramNetwork.CutAction();
                decoded.fromBytes(encoded);
                SimpleAssert.that(wire.equals(decoded.tag), "independent cut packet roundtrip");
            } finally {
                encoded.release();
            }
            hostile(0, 1, "wrong cut wire version");
            hostile(HologramCutProtocol.VERSION, Integer.MAX_VALUE, "hostile cut allocation length");
            hostile(HologramCutProtocol.VERSION, -1, "negative cut length");
            hostile(HologramCutProtocol.VERSION, HologramCutProtocol.REQUEST_BYTES + 1, "cut request byte boundary");
            NBTTagCompound expanded = new NBTTagCompound();
            expanded.setByteArray("data", new byte[100000]);
            ByteBuf bomb = Unpooled.buffer();
            try {
                byte[] packed = net.minecraft.nbt.CompressedStreamTools.compress(expanded);
                bomb.writeInt(HologramCutProtocol.VERSION);
                bomb.writeInt(packed.length);
                bomb.writeBytes(packed);
                boolean refused = false;
                try {
                    new HologramNetwork.CutAction().fromBytes(bomb);
                } catch (IllegalArgumentException expected) {
                    refused = true;
                }
                SimpleAssert.that(refused, "expanded cut NBT cannot bypass compressed request budget");
            } finally {
                bomb.release();
            }
            SimpleAssert.eq(262144, HologramCutEntry.volume(64, 64, 64), "maximum cut volume");
            rejected(() -> HologramCutEntry.volume(256, 256, 256), "excess cut volume");
            rejected(() -> HologramCutEntry.volume(0, 1, 1), "empty cut extent");
            rejected(() -> HologramCutEntry.bounds(Integer.MAX_VALUE, 1, 1, 2, 2, 2), "overflow-safe bounds");
            rejected(() -> HologramCutEntry.bounds(1, 255, 1, 2, 2, 2), "world height bounds");
            NBTTagCompound tile = new NBTTagCompound();
            tile.setString("id", "native-mod-tile");
            tile.setString("owner", "native-original-owner");
            tile.setString(
                "networkUUID",
                UUID.randomUUID()
                    .toString());
            tile.setInteger("x", 10);
            tile.setInteger("y", 20);
            tile.setInteger("z", 30);
            NBTTagCompound nested = new NBTTagCompound();
            nested.setInteger("x", 47);
            nested.setString("inventory", "private original content");
            tile.setTag("nested", nested);
            NBTTagCompound cell = new NBTTagCompound();
            cell.setString("block", "minecraft:chest");
            cell.setInteger("meta", 3);
            cell.setTag("tile", tile);
            NBTTagCompound relocated = HologramCutEntry.relocatedTile(cell, -7, 80, 18);
            SimpleAssert.eq(-7, relocated.getInteger("x"), "root x relocation");
            SimpleAssert.eq(
                47,
                relocated.getCompoundTag("nested")
                    .getInteger("x"),
                "native nested coordinates remain unchanged");
            SimpleAssert.eq("native-original-owner", relocated.getString("owner"), "native owner retained");
            SimpleAssert.eq(
                tile.getString("networkUUID"),
                relocated.getString("networkUUID"),
                "native network identity retained");
            SimpleAssert.eq(10, tile.getInteger("x"), "original tile snapshot remains immutable");
            NBTTagCompound entry = new NBTTagCompound();
            entry.setLong("sequence", 7);
            SimpleAssert.eq(
                "剪切 #7",
                HologramCutEntry.directory(entry)
                    .getString("name"),
                "old entries retain automatic names");
            SimpleAssert.eq("缓存", HologramCutNaming.checkedName("  缓存  "), "names trim surrounding whitespace");
            SimpleAssert.eq(
                64,
                HologramCutNaming.checkedName(new String(new char[64]).replace('\0', 'a'))
                    .length(),
                "maximum name remains accepted");
            rejected(
                () -> HologramCutNaming.checkedName(new String(new char[65]).replace('\0', 'a')),
                "oversized name");
            rejected(() -> HologramCutNaming.checkedName("unsafe\nname"), "name control characters");
            rejected(() -> HologramCutNaming.checkedName("\u00a7cname"), "name formatting escapes");
            entry.setString(
                "id",
                UUID.randomUUID()
                    .toString());
            entry.setInteger("sizeX", 2);
            entry.setInteger("sizeY", 1);
            entry.setInteger("sizeZ", 1);
            NBTTagList cells = new NBTTagList();
            cells.appendTag(cell);
            NBTTagCompound air = new NBTTagCompound();
            air.setString("block", "minecraft:air");
            air.setInteger("x", 1);
            cells.appendTag(air);
            entry.setTag("cells", cells);
            NBTTagCompound preview = HologramCutEntry.preview(entry);
            SimpleAssert.eq(
                2,
                preview.getTagList("cells", 10)
                    .tagCount(),
                "preview retains air cells");
            SimpleAssert.that(entry.equals(preview), "selected owner receives complete raw preview");
            preview.getTagList("cells", 10)
                .getCompoundTagAt(0)
                .getCompoundTag("tile")
                .setString("owner", "detached");
            SimpleAssert.eq("native-original-owner", tile.getString("owner"), "preview is a detached copy");
            SimpleAssert.that(
                !HologramCutEntry.directory(entry)
                    .hasKey("cells"),
                "directory does not expose raw cells");
            Path directory = Files.createTempDirectory("gtmb-cut-regression");
            UUID owner = UUID.randomUUID();
            HologramCutStorage store = new HologramCutStorage(directory, owner);
            NBTTagCompound state = store.load();
            SimpleAssert.that(!HologramCutNaming.skipsPrompt(state), "old and missing preferences continue prompting");
            SimpleAssert.that(!HologramCutPlacementPreference.skipsPrompt(state), "old saves still confirm placement");
            NBTTagCompound placementChoice = new NBTTagCompound();
            placementChoice.setString("action", "paste");
            placementChoice.setBoolean(HologramCutPlacementPreference.SKIP_PROMPT, true);
            HologramCutPlacementPreference.validate(placementChoice);
            SimpleAssert.that(
                !HologramCutPlacementPreference.skipsPrompt(state),
                "a proposed or cancelled choice cannot save a placement preference");
            NBTTagCompound ordinaryPaste = new NBTTagCompound();
            ordinaryPaste.setString("action", "paste");
            HologramCutPlacementPreference.remember(state, ordinaryPaste);
            SimpleAssert.that(
                !HologramCutPlacementPreference.skipsPrompt(state),
                "ordinary confirmation retains future prompts");
            NBTTagCompound placementOnly = new NBTTagCompound();
            HologramCutPlacementPreference.remember(placementOnly, placementChoice);
            SimpleAssert.that(
                HologramCutPlacementPreference.skipsPrompt(placementOnly)
                    && !HologramCutNaming.skipsPrompt(placementOnly),
                "placement opt-out does not disable cut naming");
            for (String invalidAction : new String[] { "cut", "undo", "list", "preview" }) {
                NBTTagCompound invalidChoice = (NBTTagCompound) placementChoice.copy();
                invalidChoice.setString("action", invalidAction);
                rejected(
                    () -> HologramCutPlacementPreference.validate(invalidChoice),
                    "non-paste placement preference " + invalidAction);
            }
            NBTTagCompound invalidType = (NBTTagCompound) placementChoice.copy();
            invalidType.setString(HologramCutPlacementPreference.SKIP_PROMPT, "true");
            rejected(() -> HologramCutPlacementPreference.validate(invalidType), "non-boolean placement preference");
            NBTTagCompound normalSave = new NBTTagCompound();
            normalSave.setString("name", "普通保存");
            HologramCutNaming.apply(state, new NBTTagCompound(), normalSave);
            SimpleAssert.that(!HologramCutNaming.skipsPrompt(state), "ordinary save does not disable naming prompt");
            HologramCutNaming.apply(state, entry, wire);
            SimpleAssert.that(
                !HologramCutPlacementPreference.skipsPrompt(state),
                "naming opt-out does not disable placement confirmation");
            HologramCutPlacementPreference.remember(state, placementChoice);
            SimpleAssert.eq(
                "原料缓存 / Raw cache",
                HologramCutEntry.directory(entry)
                    .getString("name"),
                "directory preserves custom name");
            SimpleAssert
                .that(HologramCutNaming.skipsPrompt(state), "explicit skip choice is part of pending durable state");
            state.getTagList("entries", 10)
                .appendTag(entry);
            NBTTagCompound journal = new NBTTagCompound();
            NBTTagCompound derived = HologramCutTransforms
                .apply(entry, new com.miaokatze.gtmb.api.CutTransforms.Transform(1, 0, 0, 1), 40, 80, 40);
            journal.setTag("entry", derived);
            journal.setString("consumeId", entry.getString("id"));
            state.setTag("pending", journal);
            store.save(state);
            SimpleAssert.that(
                HologramCutNaming.skipsPrompt(store.load()),
                "naming preference survives storage reload with journal");
            SimpleAssert.that(
                HologramCutPlacementPreference.skipsPrompt(store.load()),
                "placement preference survives storage reload with journal");
            SimpleAssert.that(
                state.equals(new HologramCutStorage(directory, owner).load()),
                "raw entry and incomplete transaction survive new storage instance");
            SimpleAssert.that(
                entry.equals(
                    store.load()
                        .getTagList("entries", 10)
                        .getCompoundTagAt(0)),
                "pending transformed placement retains the complete untransformed original");
            Path deniedTemporary = directory.resolve("gtmb-cut")
                .resolve(owner + ".nbt.tmp");
            Files.createDirectory(deniedTemporary);
            NBTTagCompound damagedAttempt = (NBTTagCompound) state.copy();
            damagedAttempt.removeTag("entries");
            damagedAttempt.setBoolean(HologramCutNaming.SKIP_PROMPT, false);
            damagedAttempt.setBoolean(HologramCutPlacementPreference.SKIP_PROMPT, false);
            rejected(() -> store.save(damagedAttempt), "failed pre-mutation persistence is reported");
            SimpleAssert.that(
                state.equals(store.load()),
                "failed atomic write retains previous raw inventory and recovery journal");
            Files.delete(deniedTemporary);
            SimpleAssert.eq(
                0,
                new HologramCutStorage(directory, UUID.randomUUID()).load()
                    .getTagList("entries", 10)
                    .tagCount(),
                "authenticated owners have independent queues");
            SimpleAssert.that(
                !HologramCutNaming.skipsPrompt(new HologramCutStorage(directory, UUID.randomUUID()).load()),
                "another owner does not inherit naming preference");
            SimpleAssert.that(
                !HologramCutNaming.skipsPrompt(new HologramCutStorage(directory.resolve("other-save"), owner).load()),
                "same owner in another save does not inherit naming preference");
            SimpleAssert.that(
                !HologramCutPlacementPreference
                    .skipsPrompt(new HologramCutStorage(directory, UUID.randomUUID()).load()),
                "another owner does not inherit placement preference");
            SimpleAssert.that(
                !HologramCutPlacementPreference
                    .skipsPrompt(new HologramCutStorage(directory.resolve("other-save"), owner).load()),
                "same owner in another save does not inherit placement preference");
            state.removeTag("pending");
            store.save(state);
            SimpleAssert.that(
                !store.load()
                    .hasKey("pending"),
                "atomic commit clears journal only after explicit completion");
            SimpleAssert
                .that(HologramCutNaming.skipsPrompt(store.load()), "journal completion retains naming preference");
            SimpleAssert.that(
                HologramCutPlacementPreference.skipsPrompt(store.load()),
                "journal completion retains placement preference");
            Files.delete(
                directory.resolve("gtmb-cut")
                    .resolve(owner + ".nbt"));
            Files.delete(directory.resolve("gtmb-cut"));
            Files.delete(directory);
        } catch (IOException failure) {
            throw new AssertionError("cut clipboard persistence", failure);
        }
    }

    private static void rejected(Checked action, String label) {
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IOException expected) {}
    }

    private static void compositeSelection() throws IOException {
        HologramAreaSelection area = new HologramAreaSelection();
        area.point(1, 10, 80, 20, "replace");
        area.point(2, 12, 80, 20, "replace");
        area.point(1, 15, 80, 20, "add");
        area.point(2, 16, 80, 20, "add");
        area.point(1, 11, 80, 20, "subtract");
        area.point(2, 11, 80, 20, "subtract");
        // A pending next corner must not discard the completed region composition.
        area.beginNext("add");
        area.point(1, 17, 80, 20, "replace");
        NBTTagCompound request = new NBTTagCompound();
        request.setTag("areaSelection", area.snapshot());
        HologramAreaSelection decoded = HologramCutEntry.selection(request);
        java.util.BitSet mask = HologramCutEntry.selectionMask(decoded, 10, 80, 20, 7, 1, 1);
        SimpleAssert.eq(4, mask.cardinality(), "composite selects unique cells within the bounded envelope");
        SimpleAssert.that(!mask.get(1), "subtracted cell is outside the cut");
        SimpleAssert.that(!mask.get(3) && !mask.get(4), "space between positive boxes is outside the cut");
        NBTTagCompound maximumRequest = (NBTTagCompound) request.copy();
        NBTTagList maximumRows = maximumRequest.getCompoundTag("areaSelection")
            .getTagList("areaRegions", 10);
        while (maximumRows.tagCount() < 128) maximumRows.appendTag(
            maximumRows.getCompoundTagAt(0)
                .copy());
        ByteBuf packet = Unpooled.buffer();
        try {
            new HologramNetwork.CutAction(maximumRequest).toBytes(packet);
            HologramNetwork.CutAction unpacked = new HologramNetwork.CutAction();
            unpacked.fromBytes(packet);
            SimpleAssert.that(maximumRequest.equals(unpacked.tag), "128-box selection fits the bounded cut packet");
            HologramCutEntry.selection(unpacked.tag);
        } finally {
            packet.release();
        }
        maximumRows.appendTag(
            maximumRows.getCompoundTagAt(0)
                .copy());
        rejected(() -> HologramCutEntry.selection(maximumRequest), "cut rejects more than 128 selection regions");
        NBTTagCompound sparse = new NBTTagCompound();
        sparse.setInteger("selectionVersion", 1);
        sparse.setInteger("selectedCells", mask.cardinality());
        sparse.setInteger("sizeX", 7);
        sparse.setInteger("sizeY", 1);
        sparse.setInteger("sizeZ", 1);
        NBTTagList cells = new NBTTagList();
        for (int x = mask.nextSetBit(0); x >= 0; x = mask.nextSetBit(x + 1)) {
            NBTTagCompound cell = new NBTTagCompound();
            cell.setInteger("x", x);
            cell.setInteger("y", 0);
            cell.setInteger("z", 0);
            cell.setString("block", x == 2 ? "minecraft:air" : "minecraft:stone");
            cells.appendTag(cell);
        }
        sparse.setTag("cells", cells);
        HologramCutEntry.validate(sparse);
        NBTTagCompound preview = HologramCutEntry.preview(sparse);
        SimpleAssert.that(sparse.equals(preview), "raw preview retains the exact sparse selection including air");
        SimpleAssert.eq(
            "minecraft:air",
            preview.getTagList("cells", 10)
                .getCompoundTagAt(1)
                .getString("block"),
            "selected air remains an explicit placement cell");
        NBTTagCompound truncated = (NBTTagCompound) sparse.copy();
        truncated.getTagList("cells", 10)
            .removeTag(0);
        rejected(() -> HologramCutEntry.validate(truncated), "truncated sparse entry cannot mutate the world");
        NBTTagCompound duplicated = (NBTTagCompound) sparse.copy();
        duplicated.getTagList("cells", 10)
            .getCompoundTagAt(1)
            .setInteger("x", 0);
        rejected(() -> HologramCutEntry.validate(duplicated), "duplicate cells cannot hide a missing selected cell");
        NBTTagCompound outside = (NBTTagCompound) sparse.copy();
        outside.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setInteger("x", 7);
        rejected(() -> HologramCutEntry.validate(outside), "sparse mask cannot escape its admitted volume");
        NBTTagCompound legacy = (NBTTagCompound) sparse.copy();
        legacy.removeTag("selectionVersion");
        legacy.removeTag("selectedCells");
        rejected(() -> HologramCutEntry.validate(legacy), "legacy entries still require every cuboid cell");
        legacy.setInteger("sizeX", 1);
        NBTTagList one = new NBTTagList();
        one.appendTag(
            cells.getCompoundTagAt(0)
                .copy());
        legacy.setTag("cells", one);
        HologramCutEntry.validate(legacy);
        area = new HologramAreaSelection();
        area.point(1, 10, 80, 20, "replace");
        area.point(2, 10, 80, 20, "replace");
        area.point(1, 10, 80, 20, "subtract");
        area.point(2, 10, 80, 20, "subtract");
        HologramAreaSelection empty = area;
        rejected(
            () -> HologramCutEntry.selectionMask(empty, 10, 80, 20, 1, 1, 1),
            "fully subtracted selections fail before capture or journal creation");
        // An interrupted transaction must persist both the original and the derived sparse placement exactly.
        Path root = Files.createTempDirectory("gtmb-cut-composite");
        UUID owner = UUID.randomUUID();
        HologramCutStorage store = new HologramCutStorage(root, owner);
        NBTTagCompound state = store.load();
        state.getTagList("entries", 10)
            .appendTag(sparse.copy());
        NBTTagCompound pending = new NBTTagCompound();
        pending.setTag(
            "entry",
            HologramCutTransforms
                .apply(sparse, new com.miaokatze.gtmb.api.CutTransforms.Transform(1, 1, 0, 1), 0, 80, 0));
        state.setTag("pending", pending);
        store.save(state);
        SimpleAssert.that(state.equals(store.load()), "journal roundtrip preserves selected air and omitted gaps");
        HologramCutEntry.validate(
            store.load()
                .getCompoundTag("pending")
                .getCompoundTag("entry"));
        Path blockedWrite = root.resolve("gtmb-cut")
            .resolve(owner + ".nbt.tmp");
        Files.createDirectory(blockedWrite);
        NBTTagCompound attemptedCommit = (NBTTagCompound) state.copy();
        attemptedCommit.removeTag("pending");
        attemptedCommit.setTag("entries", new NBTTagList());
        rejected(() -> store.save(attemptedCommit), "failed sparse transaction commit remains visible");
        SimpleAssert.that(
            state.equals(store.load()),
            "unknown sparse commit outcome retains the complete original cache and recovery journal");
        Files.delete(blockedWrite);
        Files.delete(
            root.resolve("gtmb-cut")
                .resolve(owner + ".nbt"));
        Files.delete(root.resolve("gtmb-cut"));
        Files.delete(root);
    }

    private static void hostile(int version, int length, String label) {
        ByteBuf bytes = Unpooled.buffer();
        try {
            bytes.writeInt(version);
            bytes.writeInt(length);
            bytes.writeByte(0);
            boolean refused = false;
            try {
                new HologramNetwork.CutAction().fromBytes(bytes);
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            SimpleAssert.that(refused, label);
        } finally {
            bytes.release();
        }
    }

    private interface Checked {

        void run() throws IOException;
    }
}
