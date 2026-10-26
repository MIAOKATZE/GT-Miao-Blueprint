package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Field-domain invalidation proofs, including negative coverage and provider replacement counterexamples. */
public final class HologramNbtObligationsRegression {

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramNbtObligationsRegression.class, args)) return;
        run();
        System.out.println("NBT obligation projection regression passed");
    }

    public static void run() throws Exception {
        inventoryAndNameHaveIndependentStamps();
        unknownAndTypeChangesExpandInvalidation();
        physicalAttributesAreNeverFree();
        nativeOverridesLoseSelectiveProof();
        exactOwnershipAndPolicy();
        typedPhysicalBoundaryIdentity();
    }

    private static void inventoryAndNameHaveIndependentStamps() throws Exception {
        HologramPresets.Cell cell = chest(1);
        HologramSegmentDocument.Record first = new HologramSegmentDocument.Record(1, cell, true);
        HologramSegmentDocument.Page old = page(first, null, policy(null));
        NBTTagCompound changed = HologramBlueprintNbt.decodeData(cell.tileNbt);
        changed.getTagList("Items", 10)
            .getCompoundTagAt(0)
            .setByte("Count", (byte) 2);
        cell.tileNbt = HologramBlueprintNbt.encodeData(changed);
        HologramSegmentDocument.Record second = new HologramSegmentDocument.Record(1, cell, true);
        HologramSegmentDocument.Page inventory = page(second, old, policy(null));
        SimpleAssert.that(inventory.generation != old.generation, "inventory edit creates a new storage generation");
        sameStamp(
            old,
            inventory,
            HologramSegmentContract.Domain.APPEARANCE,
            true,
            "audited chest inventory cannot invalidate an unrelated appearance proof");
        sameStamp(
            old,
            inventory,
            HologramSegmentContract.Domain.ASSET_NEEDS,
            false,
            "actual quantity remains part of the exact asset input");
        sameStamp(
            old,
            inventory,
            HologramSegmentContract.Domain.RELATIONS,
            false,
            "physical item tags may contain coordinate cards and invalidate relation input");
        changed.setString("CustomName", "renamed");
        cell.tileNbt = HologramBlueprintNbt.encodeData(changed);
        HologramSegmentDocument.Page renamed = page(
            new HologramSegmentDocument.Record(1, cell, true),
            inventory,
            policy(null));
        sameStamp(
            inventory,
            renamed,
            HologramSegmentContract.Domain.ASSET_NEEDS,
            true,
            "display name does not re-interpret a proven independent inventory");
        sameStamp(
            inventory,
            renamed,
            HologramSegmentContract.Domain.APPEARANCE,
            false,
            "name changes still invalidate their appearance input");
        SimpleAssert.that(
            second.claimedPaths()
                .contains("tile/Items") && second.needsAssets(),
            "physical storage is claimed as an exact item obligation");
    }

    private static void unknownAndTypeChangesExpandInvalidation() throws Exception {
        HologramPresets.Cell cell = chest(1);
        HologramSegmentDocument.Record first = new HologramSegmentDocument.Record(1, cell, true);
        NBTTagCompound changed = HologramBlueprintNbt.decodeData(cell.tileNbt);
        changed.setInteger("privateModelReadsInventory", 1);
        cell.tileNbt = HologramBlueprintNbt.encodeData(changed);
        HologramSegmentDocument.Record unknown = new HologramSegmentDocument.Record(1, cell, true);
        SimpleAssert.that(
            !unknown.coverageComplete() && unknown.unclaimedPaths()
                .contains("tile/privateModelReadsInventory"),
            "new unclaimed input cannot inherit a formerly complete proof");
        SimpleAssert.that(
            !unknown.sameInput(first, HologramSegmentContract.Domain.APPEARANCE),
            "unknown fields expand appearance invalidation");
        changed.getTagList("Items", 10)
            .getCompoundTagAt(0)
            .setByte("Count", (byte) 2);
        cell.tileNbt = HologramBlueprintNbt.encodeData(changed);
        SimpleAssert.that(
            !new HologramSegmentDocument.Record(1, cell, true)
                .sameInput(unknown, HologramSegmentContract.Domain.APPEARANCE),
            "unknown reader dependency retains whole inventory input");
        changed.removeTag("privateModelReadsInventory");
        changed.setString("Items", "malformed typed inventory");
        cell.tileNbt = HologramBlueprintNbt.encodeData(changed);
        HologramSegmentDocument.Record malformed = new HologramSegmentDocument.Record(1, cell, true);
        SimpleAssert.that(
            malformed.unclaimedPaths()
                .contains("tile/Items") && malformed.needsAssets(),
            "malformed inventory type remains a strict asset fallback");
    }

    private static void physicalAttributesAreNeverFree() throws Exception {
        Block carpenter = (Block) Class.forName("com.carpentersblocks.block.BlockCarpentersBlock")
            .getConstructor(Material.class)
            .newInstance(Material.wood);
        NBTTagCompound source = new NBTTagCompound();
        source.setInteger("cbMetadata", 0);
        source.setString("cbOwner", "one-owner");
        source.setString("cbDesign", "one-design");
        source.setTag("cbAttrList", inventory(1));
        HologramNbtObligations.Facts first = facts(carpenter, source);
        SimpleAssert
            .that(first.coverageComplete() && first.needsAssets(), "Carpenter attributes are physical obligations");
        source.setString("cbDesign", "another-design");
        HologramNbtObligations.Facts design = facts(carpenter, source);
        SimpleAssert.that(
            first.input(HologramSegmentContract.Domain.ASSET_NEEDS)
                .equals(design.input(HologramSegmentContract.Domain.ASSET_NEEDS)),
            "chisel/design configuration does not reprice covers");
        source.getTagList("cbAttrList", 10)
            .getCompoundTagAt(0)
            .setByte("Count", (byte) 2);
        HologramNbtObligations.Facts material = facts(carpenter, source);
        SimpleAssert.that(
            !design.input(HologramSegmentContract.Domain.ASSET_NEEDS)
                .equals(material.input(HologramSegmentContract.Domain.ASSET_NEEDS)),
            "every cover quantity stays exact for strict native validation");
        SimpleAssert.that(
            !design.input(HologramSegmentContract.Domain.APPEARANCE)
                .equals(material.input(HologramSegmentContract.Domain.APPEARANCE)),
            "visible materials also invalidate appearance");
        source.setString("cbOwner", "another-owner");
        SimpleAssert.that(
            !material.input(HologramSegmentContract.Domain.RELATIONS)
                .equals(facts(carpenter, source).input(HologramSegmentContract.Domain.RELATIONS)),
            "owner values cannot leak from a type template");
    }

    private static void nativeOverridesLoseSelectiveProof() throws Exception {
        HologramPresets.Cell cell = chest(1);
        HologramSegmentDocument.Record original = new HologramSegmentDocument.Record(1, cell, true);
        HologramNativePlacement.Adapter custom = new HologramNativePlacement.Adapter() {

            public boolean supports(Block block) {
                return block == Blocks.chest;
            }

            public TileEntity create(Block block, int metadata, NBTTagCompound payload, World world) {
                return null;
            }

            public List<ItemStack> materials(Block block, int metadata, TileEntity tile) {
                return Collections.emptyList();
            }
        };
        HologramNativePlacement.register("test:nbt-obligation-override", custom);
        try {
            SimpleAssert
                .that(!original.matches(cell, true), "adapter replacement invalidates even equal source records");
            HologramSegmentDocument.Record before = new HologramSegmentDocument.Record(1, cell, true);
            SimpleAssert
                .that(!before.coverageComplete(), "a native extension cannot inherit vanilla reader-independence");
            cell.tileNbt = HologramBlueprintNbt.encodeData(chestPayload(2));
            HologramSegmentDocument.Record after = new HologramSegmentDocument.Record(1, cell, true);
            SimpleAssert.that(
                !before.sameInput(after, HologramSegmentContract.Domain.APPEARANCE),
                "custom native models may read inventory and retain whole input");
            long epoch = HologramNativePlacement.generation();
            SimpleAssert.that(
                HologramNativePlacement.unregister("test:nbt-obligation-override", custom),
                "expected provider can be removed");
            HologramNativePlacement.register("test:nbt-obligation-override", custom);
            cell.tileNbt = HologramBlueprintNbt.encodeData(chestPayload(1));
            SimpleAssert.that(
                HologramNativePlacement.generation() > epoch && !before.matches(cell, true),
                "re-registering the identical instance never revives an earlier descriptor epoch");
            SimpleAssert.that(
                !before.sameInput(
                    new HologramSegmentDocument.Record(1, cell, true),
                    HologramSegmentContract.Domain.ASSET_NEEDS),
                "equal payload is bound to the new provider epoch");
        } finally {
            HologramNativePlacement.unregister("test:nbt-obligation-override", custom);
        }
    }

    private static void exactOwnershipAndPolicy() throws Exception {
        HologramPresets.Cell cell = chest(1);
        HologramSegmentDocument.Record before = new HologramSegmentDocument.Record(1, cell, true);
        HologramSegmentDocument.Page original = page(before, null, policy(new Object[] { "Items", "CustomName" }));
        HologramSegmentDocument.Page inventorySelection = page(before, original, policy(new Object[] { "CustomName" }));
        sameStamp(
            original,
            inventorySelection,
            HologramSegmentContract.Domain.APPEARANCE,
            true,
            "inventory retention policy does not invalidate unrelated appearance");
        sameStamp(
            original,
            inventorySelection,
            HologramSegmentContract.Domain.ASSET_NEEDS,
            false,
            "inventory retention policy stays bound to asset projection");
        NBTTagCompound source = chestPayload(1);
        HologramNbtObligations.Facts frozen = facts(Blocks.chest, source);
        source.getTagList("Items", 10)
            .getCompoundTagAt(0)
            .setByte("Count", (byte) 30);
        SimpleAssert.that(
            frozen.input(HologramSegmentContract.Domain.ASSET_NEEDS)
                .equals(facts(Blocks.chest, chestPayload(1)).input(HologramSegmentContract.Domain.ASSET_NEEDS)),
            "third-party mutation cannot change immutable typed facts");
        try {
            before.claimedPaths()
                .clear();
            throw new AssertionError("mutable coverage escaped");
        } catch (UnsupportedOperationException expected) { /* Immutable receipt. */ }
    }

    private static void typedPhysicalBoundaryIdentity() throws Exception {
        NBTTagCompound source = chestPayload(1);
        NBTTagCompound extra = new NBTTagCompound();
        extra.setFloat("energy", 0.0f);
        source.getTagList("Items", 10)
            .getCompoundTagAt(0)
            .setTag("tag", extra);
        HologramNbtObligations.Facts positive = facts(Blocks.chest, source);
        extra.setFloat("energy", -0.0f);
        HologramNbtObligations.Facts negative = facts(Blocks.chest, source);
        SimpleAssert.that(
            !positive.input(HologramSegmentContract.Domain.ASSET_NEEDS)
                .equals(negative.input(HologramSegmentContract.Domain.ASSET_NEEDS)),
            "exact nested item identity preserves signed floating zero");
        NBTTagCompound stack = source.getTagList("Items", 10)
            .getCompoundTagAt(0);
        stack.setInteger("Count", 1);
        HologramNbtObligations.Facts integerCount = facts(Blocks.chest, source);
        SimpleAssert.that(
            !negative.input(HologramSegmentContract.Domain.ASSET_NEEDS)
                .equals(integerCount.input(HologramSegmentContract.Domain.ASSET_NEEDS)),
            "equal numeric values with different NBT types do not share proof");
        source.setTag("Items", new NBTTagList());
        HologramNbtObligations.Facts empty = facts(Blocks.chest, source);
        source.removeTag("Items");
        SimpleAssert.that(
            !empty.input(HologramSegmentContract.Domain.ASSET_NEEDS)
                .equals(facts(Blocks.chest, source).input(HologramSegmentContract.Domain.ASSET_NEEDS)),
            "explicit typed empty inventory is not absent");
    }

    private static HologramNbtObligations.Facts facts(Block block, NBTTagCompound source) throws Exception {
        return new HologramNbtObligations.Facts(
            HologramNbtObligations.descriptor(block, 0, source),
            HologramBlueprintNbt.encodeData(source),
            null,
            source,
            1024);
    }

    private static HologramPresets.Cell chest(int count) throws Exception {
        HologramPresets.Cell cell = new HologramPresets.Cell();
        cell.item = "minecraft:chest";
        cell.block = "minecraft:chest";
        cell.tileNbt = HologramBlueprintNbt.encodeData(chestPayload(count));
        return cell;
    }

    private static NBTTagCompound chestPayload(int count) {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("id", "Chest");
        source.setString("CustomName", "first-name");
        source.setTag("Items", inventory(count));
        return source;
    }

    private static NBTTagList inventory(int count) {
        NBTTagCompound stack = new NBTTagCompound();
        new ItemStack(Blocks.stone, count, 0).writeToNBT(stack);
        stack.setByte("Slot", (byte) 0);
        NBTTagList items = new NBTTagList();
        items.appendTag(stack);
        return items;
    }

    private static Object[] policy(Object[] roots) {
        return new Object[] { true, true, roots, 1, 1, "strict" };
    }

    private static HologramSegmentDocument.Page page(HologramSegmentDocument.Record record,
        HologramSegmentDocument.Page old, Object[] policy) {
        return new HologramSegmentDocument.Page(
            1,
            0,
            new HologramSegmentDocument.Record[] { record },
            old,
            true,
            Collections.emptySet(),
            policy);
    }

    private static void sameStamp(HologramSegmentDocument.Page left, HologramSegmentDocument.Page right,
        HologramSegmentContract.Domain domain, boolean expected, String message) {
        SimpleAssert.that(
            left.stamp(domain)
                .sameInput(right.stamp(domain)) == expected,
            message);
    }

    private HologramNbtObligationsRegression() {}
}
