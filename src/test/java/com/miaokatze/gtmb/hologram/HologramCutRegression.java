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
            state.removeTag("pending");
            store.save(state);
            SimpleAssert.that(
                !store.load()
                    .hasKey("pending"),
                "atomic commit clears journal only after explicit completion");
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
