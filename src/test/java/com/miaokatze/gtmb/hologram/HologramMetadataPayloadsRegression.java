package com.miaokatze.gtmb.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Literal schemas from optional native bytecode; no optional class initialization or native tile reader. */
public final class HologramMetadataPayloadsRegression {

    private static final String CANDLE = "forestry.apiculture.blocks.BlockCandle";
    private static final String CANDLE_TILE = "forestry.apiculture.tiles.TileCandle";
    private static final String WOOD = "forestry.arboriculture.blocks.BlockArbStairs";
    private static final String WOOD_TILE = "forestry.arboriculture.tiles.TileWood";
    private static final String STAIR = "mods.railcraft.common.blocks.aesthetics.stairs.BlockRailcraftStairs";
    private static final String STAIR_TILE = "mods.railcraft.common.blocks.aesthetics.stairs.TileStair";

    private HologramMetadataPayloadsRegression() {}

    public static void run() {
        SimpleAssert.that(
            HologramMetadataPayloads.known(null, null, new NBTTagCompound()),
            "empty payloads do not require an optional mod or tile registry");
        NBTTagCompound candle = root();
        candle.setInteger("colour", 0x123456);
        candle.setBoolean("lit", true);
        accepted(CANDLE, CANDLE_TILE, candle, "native candle appearance is nonspatial");
        NBTTagCompound wood = root();
        wood.setInteger("WT", 7);
        accepted(WOOD, WOOD_TILE, wood, "native wood material is nonspatial");
        NBTTagCompound stair = root();
        stair.setString("stair", "SANDY_BRICK");
        stair.setString("owner", "retained owner");
        stair.setString("ownerId", "retained profile identity");
        NBTTagCompound uuid = new NBTTagCompound();
        uuid.setLong("most", 17L);
        uuid.setLong("least", 29L);
        stair.setTag("uuid", uuid);
        accepted(STAIR, STAIR_TILE, stair, "native RC material and identities are nonspatial");
        NBTTagCompound legacy = (NBTTagCompound) stair.copy();
        legacy.setByte("stair", (byte) 2);
        accepted(STAIR, STAIR_TILE, legacy, "native reader still accepts legacy byte material");

        NBTTagCompound wrong = (NBTTagCompound) candle.copy();
        wrong.setString("colour", "123456");
        rejected(CANDLE, CANDLE_TILE, wrong, null, "candle material type is exact");
        wrong = (NBTTagCompound) wood.copy();
        wrong.setShort("WT", (short) 7);
        rejected(WOOD, WOOD_TILE, wrong, null, "wood material type is exact");
        wrong = (NBTTagCompound) stair.copy();
        wrong.getCompoundTag("uuid")
            .setInteger("most", 17);
        rejected(STAIR, STAIR_TILE, wrong, null, "RC UUID requires native longs");
        wrong = (NBTTagCompound) stair.copy();
        wrong.getCompoundTag("uuid")
            .setInteger("someDirection", 2);
        rejected(STAIR, STAIR_TILE, wrong, null, "unknown nested UUID data is not classified");
        for (String key : new String[] { "someDirection", "Items", "ForgeData", "privateData" }) {
            wrong = (NBTTagCompound) wood.copy();
            wrong.setTag(key, new NBTTagList());
            rejected(WOOD, WOOD_TILE, wrong, null, "unknown root payload is retained and rejected: " + key);
        }
        rejected(CANDLE + "$Custom", CANDLE_TILE, candle, null, "optional subclasses are not approved by name prefix");
        rejected(CANDLE, CANDLE_TILE + "$Custom", candle, null, "registered tile subclasses need their own schema");
        rejected(CANDLE, WOOD_TILE, candle, null, "registered tile class must match the exact block family");
        rejected("forestry.apiculture.blocks.BlockStump", CANDLE_TILE, candle, null, "stump has no tile schema");
        rejected(
            "thaumcraft.common.blocks.BlockCosmeticStairs",
            STAIR_TILE,
            stair,
            null,
            "TC stairs do not inherit RC payload approval");
        wrong = root();
        wrong.setInteger("someDirection", 2);
        rejected(
            "net.minecraft.block.BlockStone",
            "gtmb.UnknownTile",
            wrong,
            null,
            "native neutral block does not approve an unknown directional tile");
        NBTTagCompound item = new NBTTagCompound();
        item.setTag("privateInventory", new NBTTagList());
        rejected(WOOD, WOOD_TILE, wood, item, "private item NBT remains unclassified");
        wrong = (NBTTagCompound) wood.copy();
        wrong.setString("id", "");
        rejected(WOOD, WOOD_TILE, wrong, null, "nonempty tile requires a registered identity");
        wrong = (NBTTagCompound) wood.copy();
        wrong.setShort("x", (short) 1);
        rejected(WOOD, WOOD_TILE, wrong, null, "root coordinates retain the native int type");
    }

    private static NBTTagCompound root() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "fixture-selected-through-registry");
        tag.setInteger("x", 1);
        tag.setInteger("y", 2);
        tag.setInteger("z", 3);
        return tag;
    }

    private static void accepted(String block, String type, NBTTagCompound tile, String label) {
        NBTTagCompound before = (NBTTagCompound) tile.copy();
        SimpleAssert.that(HologramMetadataPayloads.knownRegistered(block, type, tile, null), label);
        SimpleAssert.eq(before, tile, label + " does not rewrite material or identity");
    }

    private static void rejected(String block, String type, NBTTagCompound tile, NBTTagCompound item, String label) {
        NBTTagCompound before = (NBTTagCompound) tile.copy();
        NBTTagCompound itemBefore = item == null ? null : (NBTTagCompound) item.copy();
        SimpleAssert.that(!HologramMetadataPayloads.knownRegistered(block, type, tile, item), label);
        SimpleAssert.eq(before, tile, label + " preserves tile payload");
        SimpleAssert.eq(itemBefore, item, label + " preserves item payload");
    }
}
