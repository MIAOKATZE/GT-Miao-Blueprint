// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.miaokatzecore.api.items.NativeItemIds;

/** Stable registry identity across native extended headers and task-frozen destination ID remaps. */
final class HologramNativeItemIdsRegression {

    private HologramNativeItemIdsRegression() {}

    static void run() throws IOException {
        Item physical = Items.diamond;
        int nativeId = Item.getIdFromItem(physical);
        String registry = Item.itemRegistry.getNameForObject(physical);
        NBTTagCompound source = new ItemStack(physical, 4, 2).writeToNBT(new NBTTagCompound());
        NBTTagCompound body = new NBTTagCompound();
        body.setString("variant", "exact native item body");
        source.setTag("tag", body);
        source.setShort("id", (short) Item.getIdFromItem(Items.stick));
        source.setInteger("idExt", nativeId);
        NBTTagCompound original = (NBTTagCompound) source.copy();
        String captured = HologramBlueprintNbt.encodeCaptured(source);
        NBTTagCompound portable = HologramBlueprintNbt.decodeData(captured);
        SimpleAssert.eq(
            registry,
            portable.getString(HologramBlueprintNbt.ITEM_REGISTRY),
            "native idExt takes precedence when capturing a stable registry identity");
        SimpleAssert.eq(original, source, "numeric header capture never changes the source body");
        Map<String, Integer> highIds = new HashMap<>();
        highIds.put(registry, 70000);
        NBTTagCompound high = HologramBlueprintNbt.decodeSnapshot(captured, highIds, false);
        SimpleAssert.that(
            high.hasKey("id", 2) && high.getShort("id") == 0 && high.getInteger("idExt") == 70000,
            "a task-frozen high destination ID uses the actual native extended representation");
        SimpleAssert.eq(
            registry,
            high.getString(HologramBlueprintNbt.ITEM_REGISTRY),
            "high remapping keeps its portable identity");
        SimpleAssert.eq(body, high.getCompoundTag("tag"), "extended remapping preserves the complete item body");
        NBTTagCompound restored = HologramBlueprintNbt.decode(raw(high));
        SimpleAssert.that(
            NativeItemIds.read(restored) == nativeId && !restored.hasKey("idExt"),
            "current low registry remap cannot retain an obsolete high idExt");
        SimpleAssert.eq(
            physical,
            HologramBlueprintNbt.stableItem(restored),
            "portable identity resolves to the real current item");
        NBTTagCompound missing = (NBTTagCompound) high.copy();
        missing.setString(HologramBlueprintNbt.ITEM_REGISTRY, "test:missing-native-item");
        missing = HologramBlueprintNbt.decode(raw(missing));
        SimpleAssert.that(
            missing.getBoolean(HologramBlueprintNbt.MISSING_ITEM) && NativeItemIds.read(missing) == -1
                && !missing.hasKey("idExt"),
            "an unresolved registry marker cannot fall back to a stale extended numeric item");
        for (int kind : new int[] { 1, 2, 3, 4 }) {
            NBTTagCompound old = new ItemStack(physical).writeToNBT(new NBTTagCompound());
            // The legacy byte fixture uses a registered low numeric item; integer/long/short use diamond.
            int id = kind == 1 ? Item.getIdFromItem(Item.getItemFromBlock(net.minecraft.init.Blocks.stone)) : nativeId;
            if (kind == 1) old.setByte("id", (byte) id);
            else if (kind == 2) old.setShort("id", (short) id);
            else if (kind == 3) old.setInteger("id", id);
            else old.setLong("id", id);
            NBTTagCompound normalized = HologramBlueprintNbt.decode(HologramBlueprintNbt.encodeCaptured(old));
            SimpleAssert.eq(
                Item.getItemById(id),
                HologramBlueprintNbt.stableItem(normalized),
                "previous blueprint integer header forms remain readable: " + kind);
        }
        NBTTagCompound item = new ItemStack(physical, 4).writeToNBT(new NBTTagCompound());
        item.setShort("id", (short) 0);
        item.setInteger("idExt", nativeId);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(item);
        NBTTagCompound inventory = new NBTTagCompound();
        inventory.setTag("Items", rows);
        SimpleAssert.eq(
            4,
            HologramBlueprintResources.plan(inventory).items.get(0).stackSize,
            "resource accounting understands the native extended header without requiring a runtime mixin");
        NBTTagCompound opaque = new NBTTagCompound();
        opaque.setTag("opaqueInventory", rows.copy());
        HologramFullPreservation.mark(opaque, "opaqueInventory");
        SimpleAssert.eq(
            4,
            HologramBlueprintResources.plan(opaque).items.get(0).stackSize,
            "strict fully-preserved item records allow the exact native idExt field");
        item.setFloat("idExt", 1);
        NBTTagList invalidRows = new NBTTagList();
        invalidRows.appendTag(item);
        NBTTagCompound invalid = new NBTTagCompound();
        invalid.setTag("Items", invalidRows);
        try {
            HologramBlueprintResources.plan(invalid);
            throw new AssertionError("non-integer extended header accepted");
        } catch (IOException expected) {}
    }

    private static String raw(NBTTagCompound tag) throws IOException {
        return Base64.getEncoder()
            .encodeToString(HologramSnapshotCodec.compress(tag));
    }
}
