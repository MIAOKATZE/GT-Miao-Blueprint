package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Base64;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Local row ownership eliminates redundant copies without sharing mutable stacks or source payloads. */
public final class HologramAuthorOwnedRowRegression {

    private HologramAuthorOwnedRowRegression() {}

    public static void run() {
        try {
            transfersOnlyOwnedRows();
            projectionKeepsSourceAndCellIsolation();
        } catch (IOException | ReflectiveOperationException failure) {
            throw new AssertionError("owned author display rows", failure);
        }
    }

    private static void transfersOnlyOwnedRows() throws ReflectiveOperationException {
        NBTTagCompound row = row();
        NBTTagCompound expected = (NBTTagCompound) row.copy();
        HologramState.Cell owned = HologramState.Cell.takeOwnedLocalRow(0, row);
        Field raw = HologramState.Cell.class.getDeclaredField("raw");
        raw.setAccessible(true);
        SimpleAssert.that(raw.get(owned) == row, "owned publication transfers the existing row tree");
        SimpleAssert.that(owned.targetDisplay == row.getCompoundTag("targetDisplay"), "owned display is not copied again");
        SimpleAssert.eq(expected, owned.tag(), "ownership transfer preserves every row field");

        NBTTagCompound exported = owned.tag();
        exported.getCompoundTag("targetDisplay").getCompoundTag("nativeTile").getIntArray("pixels")[0] = 99;
        exported.getCompoundTag("actualDisplay").getCompoundTag("nativeTile").setString("marker", "export-mutated");
        exported.getCompoundTag("targetStack").getCompoundTag("tag").setString("marker", "export-mutated");
        SimpleAssert.eq(expected, owned.tag(), "exported compound and array mutations cannot alter owned display data");

        SimpleAssert.that(owned.actualStack != owned.targetStack, "actual and target stacks are separate objects");
        SimpleAssert.that(
            owned.actualStack.getTagCompound() != owned.targetStack.getTagCompound(),
            "actual and target mutable stack tags are not shared");
        owned.actualStack.getTagCompound().setString("marker", "actual-mutated");
        SimpleAssert.eq("stack", owned.targetStack.getTagCompound().getString("marker"), "actual cannot mutate target");
        owned.targetStack.getTagCompound().setString("marker", "target-mutated");
        SimpleAssert.eq(expected, owned.tag(), "mutable stacks cannot change the authoritative display row");

        NBTTagCompound callerRow = row();
        HologramState.Cell defensive = new HologramState.Cell(0, callerRow);
        callerRow.getCompoundTag("targetDisplay").getCompoundTag("nativeTile").getIntArray("pixels")[0] = 123;
        callerRow.getCompoundTag("targetStack").getCompoundTag("tag").setString("marker", "caller-mutated");
        SimpleAssert.eq(expected, defensive.tag(), "ordinary constructor still defends against caller mutation");
    }

    private static void projectionKeepsSourceAndCellIsolation() throws IOException, ReflectiveOperationException {
        NBTTagCompound tile = new NBTTagCompound();
        tile.setString("marker", "tile-source");
        tile.setIntArray("pixels", new int[] { 1, 2, 3 });
        NBTTagCompound item = new NBTTagCompound();
        item.setString("marker", "item-source");
        String tilePayload = Base64.getEncoder().encodeToString(HologramSnapshotCodec.compress(tile));
        String itemPayload = Base64.getEncoder().encodeToString(HologramSnapshotCodec.compress(item));
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        JsonArray cells = new JsonArray();
        for (int i = 0; i < 2; i++) {
            JsonObject cell = new JsonObject();
            cell.addProperty("x", i);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            cell.addProperty("item", "minecraft:stone");
            cell.addProperty("tileNbt", tilePayload);
            cell.addProperty("itemNbt", itemPayload);
            cells.add(cell);
        }
        source.add("cells", cells);
        AuthorDocument document = AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
        byte[] before = AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults());
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            HologramState cold = projection.view();
            projection.request(cold, cold.cells.get(0));
            projection.request(cold, cold.cells.get(1));
            projection.advance(() -> 0);
            HologramState.Cell first = projection.view().cells.get(0), second = projection.view().cells.get(1);
            SimpleAssert.eq("", first.sourceWarning, "fixture reaches successful native completion");
            SimpleAssert.eq(tile, first.targetDisplay.getCompoundTag("nativeTile"), "native output retains exact tile payload");
            SimpleAssert.that(first.actualStack != null && first.targetStack != null, "native item fixture was decoded");
            SimpleAssert.that(
                first.targetDisplay.getCompoundTag("nativeTile") != second.targetDisplay.getCompoundTag("nativeTile"),
                "different cells do not share mutable decoded payload trees");
            Field raw = HologramState.Cell.class.getDeclaredField("raw");
            raw.setAccessible(true);
            SimpleAssert.that(
                first.targetDisplay == ((NBTTagCompound) raw.get(first)).getCompoundTag("targetDisplay"),
                "projection uses owned-row publication rather than the defensive constructor");
            first.actualStack.getTagCompound().setString("marker", "actual-mutated");
            first.targetStack.getTagCompound().setString("marker", "target-mutated");
            SimpleAssert.eq("item-source", second.targetStack.getTagCompound().getString("marker"), "stack edits stay per cell");
            SimpleAssert.eq(
                "item-source",
                first.tag().getCompoundTag("targetStack").getCompoundTag("tag").getString("marker"),
                "display-row stack payload remains immutable");
            NBTTagCompound exported = first.tag();
            exported.getCompoundTag("actualDisplay").getCompoundTag("nativeTile").getIntArray("pixels")[0] = 77;
            SimpleAssert.eq(tile, first.targetDisplay.getCompoundTag("nativeTile"), "export does not alias display payload");
            SimpleAssert.that(
                Arrays.equals(before, AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults())),
                "native preparation and consumer mutation cannot alter source, IDs, origins or obligations");
            SimpleAssert.eq(tilePayload, document.cell(projection.cellId(0)).tileNbt(), "source encoded NBT stays opaque and exact");
        }
    }

    private static NBTTagCompound row() {
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", "minecraft:stone");
        row.setString("wantId", "minecraft:stone");
        NBTTagCompound nativeData = new NBTTagCompound();
        nativeData.setString("marker", "tile");
        nativeData.setIntArray("pixels", new int[] { 1, 2, 3 });
        NBTTagCompound display = new NBTTagCompound();
        display.setTag("nativeTile", nativeData);
        row.setTag("actualDisplay", display);
        row.setTag("targetDisplay", display);
        ItemStack stack = new ItemStack(Blocks.stone);
        NBTTagCompound stackData = new NBTTagCompound();
        stackData.setString("marker", "stack");
        stack.setTagCompound(stackData);
        NBTTagCompound stackRow = stack.writeToNBT(new NBTTagCompound());
        row.setTag("actualStack", stackRow);
        row.setTag("targetStack", stackRow);
        return row;
    }
}
