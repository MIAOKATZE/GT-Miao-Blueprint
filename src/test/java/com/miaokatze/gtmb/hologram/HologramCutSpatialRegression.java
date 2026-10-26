package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.api.CutTransforms;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Target-aware raw extensions, source-local browsing and complete LittleTiles reference graphs. */
public final class HologramCutSpatialRegression {

    private HologramCutSpatialRegression() {}

    public static void run() {
        try {
            previewRequestKeys();
            extensionTargets();
            legacyAtomicity();
            littleTiles();
        } catch (Exception failure) {
            throw new AssertionError("cut spatial derivation", failure);
        }
    }

    private static void previewRequestKeys() throws IOException {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", "preview");
        request.setString(
            "requestId",
            java.util.UUID.randomUUID()
                .toString());
        request.setString("previewPurpose", "placement");
        request.setLong("previewGeneration", 23);
        request.setInteger("originX", 200);
        request.setInteger("originY", 64);
        request.setInteger("originZ", 100);
        HologramCutService.validateRequestKeys(request);
        request.setTag("tile", new NBTTagCompound());
        try {
            HologramCutService.validateRequestKeys(request);
            throw new AssertionError("raw client payloads must remain outside the cut request protocol");
        } catch (IOException expected) {}
    }

    private static void legacyAtomicity() throws Exception {
        NBTTagCompound entry = entry(1);
        NBTTagCompound cell = entry.getTagList("cells", 10)
            .getCompoundTagAt(0);
        cell.setString("block", "minecraft:command_block");
        cell.setInteger("meta", 3);
        NBTTagCompound original = (NBTTagCompound) entry.copy();
        try (AutoCloseable registration = HologramBlockTransformApi.register(new HologramBlockTransformApi.Adapter() {

            public boolean accepts(HologramBlockTransformApi.State state) {
                return state.block == Blocks.command_block && state.tile != null && !state.tile.hasKey("firstStep");
            }

            public void rotate(HologramBlockTransformApi.State state, boolean vertical) {
                state.tile.setBoolean("firstStep", true);
                state.metadata = 7;
            }
        })) {
            NBTTagCompound transformed = HologramCutTransforms
                .apply(entry, new CutTransforms.Transform(2, 0, 0, 0), 200, 64, 100);
            SimpleAssert.eq(
                3,
                transformed.getTagList("cells", 10)
                    .getCompoundTagAt(0)
                    .getInteger("meta"),
                "an unhandled later quarter turn cannot leak earlier metadata edits");
            SimpleAssert.that(
                tile(original, 0).equals(tile(transformed, 0)),
                "a partially recognized legacy rotation restores the entire native tile payload");
            SimpleAssert.that(
                transformed.getTagList("transformUnknown", 8)
                    .tagCount() > 0,
                "partial legacy rotations have a visible warning");
            SimpleAssert.that(entry.equals(original), "partial legacy rotations never edit the cached original");
        }
    }

    private static void extensionTargets() throws IOException {
        NBTTagCompound entry = entry(1), source = tile(entry, 0);
        source.setInteger("linkedX", 101);
        source.setString("owner", "unchanged");
        NBTTagCompound original = (NBTTagCompound) entry.copy();
        int[] calls = { 0 };
        Runnable remove = HologramCutTransforms.install(new CutTransforms.Adapter() {

            public boolean accepts(CutTransforms.State state) {
                return state.targetX >= 200;
            }

            public void transform(CutTransforms.State state, CutTransforms.Transform transform) {
                calls[0]++;
                SimpleAssert.eq(7, state.sourceDimension, "source dimension reaches the extension");
                SimpleAssert.eq(9, state.targetDimension, "placement dimension reaches the extension");
                SimpleAssert.eq(9, state.copy().targetDimension, "dimension survives detached predicate copies");
                state.tile.setInteger("linkedX", state.tile.getInteger("linkedX") + state.targetX - state.sourceX);
            }
        });
        try {
            NBTTagCompound request = new NBTTagCompound(), response = new NBTTagCompound();
            request.setString("previewPurpose", "storage");
            HologramCutService.preview(response, entry, request, 9);
            SimpleAssert.that(entry.equals(response.getCompoundTag("preview")), "storage is an exact original view");
            SimpleAssert.eq(0, calls[0], "storage never invokes placement extensions");

            request.setString("previewPurpose", "local");
            HologramCutService.preview(response, entry, request, 9);
            SimpleAssert.eq(
                101,
                tile(response.getCompoundTag("preview"), 0).getInteger("linkedX"),
                "moving sketches retain source-local extension context");
            SimpleAssert.that(
                response.getCompoundTag("preview")
                    .getBoolean("transformTargetDependent"),
                "even a target-only accepts predicate marks its local sketch as target dependent");

            request.setString("previewPurpose", "placement");
            request.setInteger("originX", 200);
            request.setInteger("originY", 64);
            request.setInteger("originZ", 100);
            request.setLong("previewGeneration", 23);
            HologramCutService.preview(response, entry, request, 9);
            NBTTagCompound paste = HologramCutTransforms
                .apply(entry, new CutTransforms.Transform(0, 0, 0, 0), 200, 64, 100, 9);
            SimpleAssert.eq(201, tile(paste, 0).getInteger("linkedX"), "identity translation invokes the extension");
            SimpleAssert.that(
                paste.equals(response.getCompoundTag("preview")),
                "locked placement preview and paste share the exact target derivation");
            SimpleAssert.eq(23L, response.getLong("previewGeneration"), "preview receipt binds generation");
            SimpleAssert.eq(200, response.getInteger("originX"), "preview receipt binds actual origin");
            SimpleAssert.eq(2, calls[0], "one extension call per placement derivation");
            SimpleAssert.that(entry.equals(original), "all previews and placements preserve the cached original");
        } finally {
            remove.run();
        }
        NBTTagCompound expected = (NBTTagCompound) original.copy();
        new CutTransforms.Transform(0, 0, 0, 0).write(expected);
        SimpleAssert.that(
            expected.equals(HologramCutTransforms.apply(entry, new CutTransforms.Transform(0, 0, 0, 0), 200, 64, 100)),
            "unregistered identity translations retain the entire typed raw entry");
    }

    private static void littleTiles() {
        NBTTagCompound entry = entry(2);
        for (int i = 0; i < 2; i++) {
            NBTTagCompound root = new NBTTagCompound(), part = new NBTTagCompound();
            root.setString("id", "LittleTilesTileEntity");
            root.setInteger("tilesCount", 1);
            part.setBoolean("isStructure", true);
            part.setBoolean("main", i == 0);
            part.setString("id", "fixed");
            part.setInteger("count", 1);
            part.setInteger("bSize", 1);
            String[] fields = { "minX", "minY", "minZ", "maxX", "maxY", "maxZ" };
            int[] box = { 1, 2, 3, 5, 8, 12 };
            for (int n = 0; n < 6; n++) part.setInteger("bBox0" + fields[n], box[n]);
            for (int n = 0; n < 3; n++) part.setInteger("cVec" + "xyz".charAt(n), box[n]);
            String prefix = i == 0 ? "i0" : "";
            part.setInteger(prefix + "coordX", i == 0 ? 1 : -1);
            part.setInteger(prefix + "coordY", 0);
            part.setInteger(prefix + "coordZ", 0);
            for (int n = 0; n < 3; n++) part.setInteger(prefix + "pos" + "xyz".charAt(n), box[n]);
            part.setString("inventoryPayload", "must-not-change");
            root.setTag("t0", part);
            entry.getTagList("cells", 10)
                .getCompoundTagAt(i)
                .setTag("tile", root);
        }
        NBTTagCompound original = (NBTTagCompound) entry.copy();
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
            CutTransforms.Transform pose = new CutTransforms.Transform(y, x, z, 0);
            NBTTagCompound transformed = HologramCutTransforms.apply(entry, pose, 200, 64, 100);
            SimpleAssert.eq(
                0,
                transformed.getTagList("transformUnknown", 8)
                    .tagCount(),
                "complete supported LittleTiles references rotate without unknown-schema warnings");
            assertLinks(transformed);
            SimpleAssert.that(entry.equals(original), "multi-quarter LittleTiles rotations preserve cached originals");
        }
        NBTTagCompound mirrored = HologramCutTransforms
            .apply(entry, new CutTransforms.Transform(0, 0, 0, 1), 200, 64, 100);
        SimpleAssert.that(
            mirrored.getTagList("transformUnknown", 8)
                .tagCount() > 0,
            "unsupported LittleTiles mirror has an explicit warning");
        SimpleAssert.that(tile(entry, 0).equals(tile(mirrored, 0)), "unsupported mirror retains complete native tile");
        NBTTagCompound partial = (NBTTagCompound) entry.copy();
        partial.getTagList("cells", 10)
            .removeTag(0);
        NBTTagCompound result = HologramCutTransforms
            .apply(partial, new CutTransforms.Transform(1, 0, 0, 0), 200, 64, 100);
        SimpleAssert.that(
            result.getTagList("transformUnknown", 8)
                .tagCount() > 0,
            "a referenced LittleTiles cell outside the exact mask remains explicitly unsupported");
        SimpleAssert.that(tile(partial, 0).equals(tile(result, 0)), "partial references do not leak half-rotated NBT");
    }

    private static void assertLinks(NBTTagCompound entry) {
        NBTTagList cells = entry.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i), part = tile(entry, i).getCompoundTag("t0");
            String prefix = part.getBoolean("main") ? "i0" : "";
            int x = cell.getInteger("x") + part.getInteger(prefix + "coordX");
            int y = cell.getInteger("y") + part.getInteger(prefix + "coordY");
            int z = cell.getInteger("z") + part.getInteger(prefix + "coordZ");
            NBTTagCompound target = cells.getCompoundTagAt(1 - i);
            SimpleAssert.that(
                x == target.getInteger("x") && y == target.getInteger("y") && z == target.getInteger("z"),
                "rotated references reach the actual transformed neighbor cell");
            NBTTagCompound targetPart = target.getCompoundTag("tile")
                .getCompoundTag("t0");
            String[] axes = { "X", "Y", "Z" };
            for (int n = 0; n < 3; n++) SimpleAssert.eq(
                targetPart.getInteger("bBox0min" + axes[n]),
                part.getInteger(prefix + "pos" + "xyz".charAt(n)),
                "reference corners match the transformed target voxel minimum");
            SimpleAssert.eq("must-not-change", part.getString("inventoryPayload"), "native inventory remains intact");
        }
    }

    private static NBTTagCompound entry(int count) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setInteger("dimension", 7);
        entry.setInteger("x", 100);
        entry.setInteger("y", 64);
        entry.setInteger("z", 100);
        entry.setInteger("sizeX", count);
        entry.setInteger("sizeY", 1);
        entry.setInteger("sizeZ", 1);
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < count; i++) {
            NBTTagCompound cell = new NBTTagCompound(), tile = new NBTTagCompound();
            cell.setString("block", "minecraft:stone");
            cell.setInteger("x", i);
            cell.setInteger("y", 0);
            cell.setInteger("z", 0);
            tile.setString("id", "gtmb_test:raw_spatial");
            cell.setTag("tile", tile);
            rows.appendTag(cell);
        }
        entry.setTag("cells", rows);
        return entry;
    }

    private static NBTTagCompound tile(NBTTagCompound entry, int index) {
        return entry.getTagList("cells", 10)
            .getCompoundTagAt(index)
            .getCompoundTag("tile");
    }
}
