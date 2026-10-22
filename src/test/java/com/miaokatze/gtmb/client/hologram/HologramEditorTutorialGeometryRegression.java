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
        geometry.choose(false);
        geometry.choose(true);
        geometry.expand(3, 2);
        geometry.submit();
        SimpleAssert.eq(
            2,
            geometry.snapshot()
                .getCompoundTag("capturedSelection")
                .getTagList("cells", 10)
                .tagCount(),
            "selection captures the actual bounded cells including native NBT");
        geometry.store();
        SimpleAssert.eq(
            0,
            geometry.scene()
                .getTagList("cells", 10)
                .tagCount(),
            "stored cells leave the teaching source");
        NBTTagCompound retained = geometry.snapshot();
        geometry.take();
        SimpleAssert.that(source.equals(original), "teaching moves never modify the supplied source");
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
        boolean refused = false;
        try {
            geometry.place();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.that(refused && geometry.stored(), "unlocked placement cannot consume the teaching entry");
        geometry.lock();
        geometry.cancel();
        SimpleAssert.that(geometry.stored() && !geometry.consumed(), "cancel retains the same unconsumed entry");
        geometry.take();
        geometry.lock();
        geometry.place();
        SimpleAssert.that(
            !geometry.stored() && geometry.consumed(),
            "validated geometry placement consumes only the teaching entry");
        geometry.restore(retained);
        SimpleAssert.that(
            geometry.stored() && !geometry.consumed(),
            "previous checkpoint restores independent stored geometry");
        geometry.take();
        geometry.lock();
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
