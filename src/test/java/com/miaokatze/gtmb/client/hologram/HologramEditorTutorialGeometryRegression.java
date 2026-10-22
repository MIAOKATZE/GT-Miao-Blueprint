package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Finite transactions must preserve native payloads and keep cancellation/checkpoints independent. */
public final class HologramEditorTutorialGeometryRegression {

    private HologramEditorTutorialGeometryRegression() {}

    public static void run() {
        NBTTagCompound source = source();
        NBTTagCompound original = (NBTTagCompound) source.copy();
        NBTTagCompound checkpoint = new NBTTagCompound();
        checkpoint.setTag("source", source);
        checkpoint.setTag("scene", source.copy());
        HologramEditorTutorialGeometry geometry = new HologramEditorTutorialGeometry();
        geometry.restore(checkpoint);
        boolean refused = false;
        try {
            geometry.reviewSource();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.that(refused && !geometry.stored(), "an unselected source cannot enter cut confirmation");
        geometry.choose(false);
        refused = false;
        try {
            geometry.store();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.that(refused && !geometry.stored(), "one corner cannot cut or consume a source");
        geometry.choose(true);
        geometry.expand(3, 2);
        geometry.submit();
        SimpleAssert
            .eq(2, geometry.count(), "the observed source count is independent of its expanded selection volume");
        SimpleAssert.eq(15, geometry.selectionVolume(), "inclusive expanded bounds include selected air positions");
        NBTTagCompound captured = geometry.snapshot()
            .getCompoundTag("capturedSelection");
        fullVolume(captured, 5, 1, 3, 2, 13, "submitted box");
        refused = false;
        try {
            geometry.store();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.that(refused && !geometry.stored(), "two corners alone do not consent to destructive cutting");
        geometry.reviewSource();
        NBTTagCompound sourceReview = geometry.snapshot();
        geometry.store();
        SimpleAssert.eq(
            0,
            geometry.scene()
                .getTagList("cells", 10)
                .tagCount(),
            "stored cells leave the teaching source");
        NBTTagCompound retained = geometry.snapshot();
        fullVolume(retained.getCompoundTag("clipboard"), 5, 1, 3, 2, 13, "stored selection");
        SimpleAssert.that(
            captured.equals(retained.getCompoundTag("clipboard")),
            "box capture and cut storage preserve the same complete selection rather than shrinking to observed cells");
        refused = false;
        try {
            geometry.take();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.that(
            refused && geometry.stored() && !geometry.placing(),
            "the teaching source cannot teleport to placement before opening its storage entry");
        geometry.openStorage();
        NBTTagCompound browser = geometry.snapshot();
        geometry.take();
        SimpleAssert.that(source.equals(original), "teaching moves never modify the supplied source");
        fullVolume(geometry.scene(), 5, 1, 3, 2, 13, "placement preview");
        SimpleAssert.eq(
            4,
            geometry.scene()
                .getInteger("x"),
            "the placement frame follows the destination anchor");
        SimpleAssert.eq(
            2,
            geometry.scene()
                .getInteger("z"),
            "the complete selection moves to its destination plane");
        NBTTagCompound relocated = geometry.scene()
            .getTagList("cells", 10)
            .getCompoundTagAt(1);
        SimpleAssert.that(
            relocated.getCompoundTag("tile")
                .equals(
                    source.getTagList("cells", 10)
                        .getCompoundTagAt(1)
                        .getCompoundTag("tile")),
            "moving geometry preserves the complete native tile, including its binary inventory payload");
        refused = false;
        try {
            geometry.place();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.that(refused && geometry.stored(), "unlocked placement cannot consume the teaching entry");
        geometry.lock();
        refused = false;
        try {
            geometry.place();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.that(refused && geometry.stored(), "locking alone cannot bypass the independent placement review");
        geometry.reviewPlacement();
        geometry.cancel();
        SimpleAssert.that(geometry.stored() && !geometry.consumed(), "cancel retains the same unconsumed entry");
        SimpleAssert.that(
            retained.getCompoundTag("clipboard")
                .getTagList("cells", 10)
                .equals(
                    geometry.retained()
                        .getTagList("cells", 10)),
            "cancel retains all selected air and complete native payloads");
        geometry.openStorage();
        geometry.take();
        geometry.lock();
        geometry.reviewPlacement();
        geometry.place();
        SimpleAssert.that(
            !geometry.stored() && geometry.consumed(),
            "validated geometry placement consumes only the teaching entry");
        geometry.restore(retained);
        fullVolume(geometry.retained(), 5, 1, 3, 2, 13, "restored previous selection");
        SimpleAssert.that(
            geometry.stored() && !geometry.consumed(),
            "previous checkpoint restores independent stored geometry");
        geometry.openStorage();
        geometry.take();
        geometry.lock();
        geometry.reviewPlacement();
        geometry.scene()
            .getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setInteger("x", 12345);
        refused = false;
        try {
            geometry.place();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.that(
            refused && geometry.stored(),
            "mismatching actual placement geometry is rejected without consuming its entry");
        geometry.restore(sourceReview);
        SimpleAssert.that(
            geometry.sourceReview() && !geometry.stored() && !geometry.placing(),
            "returning to a source-review checkpoint restores the undecided modal, not a completed cut");
        geometry.restore(browser);
        SimpleAssert.that(
            geometry.storageOpen() && geometry.stored() && !geometry.placing(),
            "a storage checkpoint reopens the unconsumed entry before its place-button action");
        rebasedSelection(source, original);
    }

    private static void fullVolume(NBTTagCompound entry, int sx, int sy, int sz, int blocks, int air, String context) {
        SimpleAssert.eq(sx, entry.getInteger("sizeX"), context + " retains its inclusive X dimension");
        SimpleAssert.eq(sy, entry.getInteger("sizeY"), context + " retains its inclusive Y dimension");
        SimpleAssert.eq(sz, entry.getInteger("sizeZ"), context + " retains its inclusive Z dimension");
        SimpleAssert.eq(
            sx * sy * sz,
            entry.getTagList("cells", 10)
                .tagCount(),
            context + " retains every selected cell");
        SimpleAssert
            .eq(sx * sy * sz, entry.getInteger("selectedCells"), context + " records the complete selection mask");
        SimpleAssert.eq(blocks, entry.getInteger("blocks"), context + " records actual non-air blocks separately");
        int actualAir = 0;
        NBTTagList rows = entry.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++) if ("minecraft:air".equals(
            rows.getCompoundTagAt(i)
                .getString("block"))) {
                    actualAir++;
                    SimpleAssert.that(
                        !rows.getCompoundTagAt(i)
                            .hasKey("tile"),
                        context + " does not invent tile data for empty positions");
                }
        SimpleAssert.eq(air, actualAir, context + " keeps selected air rather than dropping it from the entry");
    }

    private static void rebasedSelection(NBTTagCompound source, NBTTagCompound original) {
        NBTTagCompound checkpoint = new NBTTagCompound();
        checkpoint.setTag("source", source.copy());
        checkpoint.setTag("scene", source.copy());
        checkpoint.setIntArray("first", new int[] { 1, 0, 0 });
        checkpoint.setIntArray("second", new int[] { 4, 0, 2 });
        HologramEditorTutorialGeometry geometry = new HologramEditorTutorialGeometry();
        geometry.restore(checkpoint);
        geometry.submit();
        NBTTagCompound captured = geometry.snapshot()
            .getCompoundTag("capturedSelection");
        fullVolume(captured, 4, 1, 3, 1, 11, "nonzero-origin selection");
        SimpleAssert.eq(1, captured.getInteger("x"), "capture retains the selected origin for source review");
        NBTTagCompound first = captured.getTagList("cells", 10)
            .getCompoundTagAt(0);
        SimpleAssert.eq(0, first.getInteger("x"), "captured rows are rebased to the minimum selected corner");
        SimpleAssert.that(
            first.getCompoundTag("tile")
                .equals(
                    source.getTagList("cells", 10)
                        .getCompoundTagAt(1)
                        .getCompoundTag("tile")),
            "coordinate rebasing leaves the complete native payload unchanged");
        first.getCompoundTag("tile")
            .setByteArray("nativeInventory", new byte[] { 9 });
        SimpleAssert
            .that(source.equals(original), "capture/checkpoint copies do not alias the original observed source");
        SimpleAssert.that(
            !captured.equals(
                geometry.snapshot()
                    .getCompoundTag("capturedSelection")),
            "a returned checkpoint does not alias the retained captured geometry");
    }

    private static NBTTagCompound source() {
        NBTTagCompound source = new NBTTagCompound();
        source.setInteger("sizeX", 2);
        source.setInteger("sizeY", 1);
        source.setInteger("sizeZ", 1);
        NBTTagList cells = new NBTTagList();
        for (int i = 0; i < 2; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", i);
            row.setString("block", "minecraft:stone");
            NBTTagCompound tile = new NBTTagCompound();
            tile.setByteArray("nativeInventory", new byte[] { 0, -1, 2, -128 });
            row.setTag("tile", tile);
            cells.appendTag(row);
        }
        source.setTag("cells", cells);
        return source;
    }
}
