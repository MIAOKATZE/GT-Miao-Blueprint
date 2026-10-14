package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Material arithmetic uses actual ItemStack variants; world installation has separate native witnesses. */
final class HologramDecorMaterialsRegression {

    private HologramDecorMaterialsRegression() {}

    static void run() {
        try {
            roundingAndRollback();
            mixedPrebuiltAndRaw();
            aggregateVariantsAndExactTags();
            emptyCost();
            factoryIdentity();
        } catch (Exception failure) {
            throw new AssertionError("Native decoration payment regression", failure);
        }
    }

    private static HologramDecorMaterials.Descriptor cost(ItemStack prebuilt, ItemStack raw, int n, int d) {
        return new HologramDecorMaterials.Descriptor(
            Collections.singletonList(new HologramDecorMaterials.Cost(prebuilt, raw, n, d)));
    }

    private static void roundingAndRollback() throws Exception {
        ItemStack raw = new ItemStack(Blocks.quartz_block, 1, 2);
        HologramDecorMaterials.Descriptor stair = cost(new ItemStack(Items.stick), raw, 3, 4);
        HologramDecorMaterials.Ledger ledger = new HologramDecorMaterials.Ledger();
        HologramDecorMaterials.Quote rejected = ledger.quote(stair, (stack, limit) -> 0);
        SimpleAssert.eq(1, count(rejected.items, raw), "first three-quarter shape rounds upward");
        SimpleAssert.eq(
            1,
            count(ledger.quote(stair, (stack, limit) -> 0).items, raw),
            "failed reservation creates no free fractional credit");
        ledger.commit(rejected);
        int charged = 1;
        for (int i = 1; i < 4; i++) {
            HologramDecorMaterials.Quote quote = ledger.quote(stair, (stack, limit) -> 0);
            charged += count(quote.items, raw);
            ledger.commit(quote);
        }
        SimpleAssert.eq(3, charged, "four three-quarter shapes aggregate to exactly three raw blocks");
        try {
            ledger.commit(rejected);
            throw new AssertionError("a quote must not be committed twice");
        } catch (IllegalStateException expected) {}
        HologramDecorMaterials.Ledger tiny = new HologramDecorMaterials.Ledger();
        int blocks = 0;
        for (int i = 0; i < 17; i++) {
            HologramDecorMaterials.Quote quote = tiny
                .quote(cost(new ItemStack(Items.stick), raw, 1, 16), (stack, limit) -> 0);
            blocks += count(quote.items, raw);
            tiny.commit(quote);
        }
        SimpleAssert.eq(2, blocks, "seventeen one-sixteenth parts charge two raw blocks");
    }

    private static void mixedPrebuiltAndRaw() throws Exception {
        ItemStack raw = new ItemStack(Blocks.planks, 1, 3);
        ItemStack cut = new ItemStack(Items.stick, 2);
        NBTTagCompound shape = new NBTTagCompound();
        shape.setInteger("Shape", 17);
        shape.setString("BaseName", "minecraft:planks");
        shape.setInteger("BaseData", 3);
        cut.setTagCompound(shape);
        HologramDecorMaterials.Descriptor descriptor = cost(cut, raw, 1, 8);
        HologramDecorMaterials.Ledger ledger = new HologramDecorMaterials.Ledger();
        HologramDecorMaterials.Quote quote = ledger.quote(
            descriptor,
            (stack, limit) -> stack.isItemEqual(cut) && ItemStack.areItemStackTagsEqual(stack, cut) ? 1 : 0);
        SimpleAssert.eq(1, count(quote.items, cut), "available exact prebuilt part pays once");
        SimpleAssert.eq(1, count(quote.items, raw), "remaining half-drop uses rounded raw fraction");
        ledger.commit(quote);
        HologramDecorMaterials.Quote next = ledger.quote(descriptor, (stack, limit) -> 0);
        SimpleAssert.eq(0, count(next.items, raw), "paid raw remainder remains usable in same batch");
        SimpleAssert.eq(0, count(next.items, cut), "no double charge for previous prebuilt part");
        // The dry-run copy must never change live batch credit.
        HologramDecorMaterials.Ledger simulation = ledger.copy();
        simulation.commit(simulation.quote(descriptor, (stack, limit) -> 0));
        SimpleAssert.eq(
            0,
            count(ledger.quote(descriptor, (stack, limit) -> 0).items, raw),
            "dry-run ledger does not mutate the placement ledger");
    }

    private static void aggregateVariantsAndExactTags() throws Exception {
        ItemStack jungle = new ItemStack(Blocks.planks, 1, 3);
        ItemStack birch = new ItemStack(Blocks.planks, 1, 2);
        ItemStack tagged = jungle.copy();
        tagged.setTagCompound(new NBTTagCompound());
        tagged.getTagCompound()
            .setString("asset", "preserved");
        HologramDecorMaterials.Descriptor descriptor = new HologramDecorMaterials.Descriptor(
            Arrays.asList(
                new HologramDecorMaterials.Cost(new ItemStack(Items.stick), jungle, 1, 3),
                new HologramDecorMaterials.Cost(new ItemStack(Items.blaze_rod), jungle, 2, 3),
                new HologramDecorMaterials.Cost(new ItemStack(Items.bone), birch, 1, 3),
                new HologramDecorMaterials.Cost(new ItemStack(Items.arrow), tagged, 1, 3)));
        HologramDecorMaterials.Quote quote = new HologramDecorMaterials.Ledger().quote(descriptor, (stack, limit) -> 0);
        SimpleAssert.eq(1, count(quote.items, jungle), "different shape fractions share exact raw variant");
        SimpleAssert.eq(1, count(quote.items, birch), "metadata variants round independently");
        SimpleAssert.eq(1, count(quote.items, tagged), "raw NBT variants round independently");
        SimpleAssert.eq(
            "preserved",
            tagged.getTagCompound()
                .getString("asset"),
            "quotation preserves source tags");
    }

    private static void emptyCost() throws Exception {
        HologramDecorMaterials.Descriptor empty = new HologramDecorMaterials.Descriptor(Collections.emptyList());
        HologramDecorMaterials.Ledger ledger = new HologramDecorMaterials.Ledger();
        HologramDecorMaterials.Quote quote = ledger.quote(
            empty,
            (stack, limit) -> { throw new AssertionError("empty decoration must never inspect inventory"); });
        SimpleAssert.eq(0, quote.items.size(), "zero-cost decoration has an empty legitimate bill");
        ledger.commit(quote);
    }

    private static void factoryIdentity() throws Exception {
        TileEntityChest prototype = new TileEntityChest();
        NBTTagCompound captured = new NBTTagCompound();
        prototype.writeToNBT(captured);
        NBTTagCompound original = (NBTTagCompound) captured.copy();
        HologramNativePlacement.validateFactoryId(captured, prototype);
        SimpleAssert.eq(original, captured, "native factory identity validation preserves full captured NBT");
        for (String foreign : new String[] { "gtmb:unregistered_tile", "Furnace", "" }) {
            NBTTagCompound malformed = (NBTTagCompound) captured.copy();
            malformed.setString("id", foreign);
            rejectFactory(malformed, prototype);
        }
        NBTTagCompound numeric = (NBTTagCompound) captured.copy();
        numeric.setInteger("id", 41);
        rejectFactory(numeric, prototype);
        NBTTagCompound partial = (NBTTagCompound) captured.copy();
        partial.removeTag("id");
        HologramNativePlacement.validateFactoryId(partial, prototype);
        SimpleAssert.eq(false, partial.hasKey("id"), "detached partial schema does not acquire an invented id");
    }

    private static void rejectFactory(NBTTagCompound payload, TileEntityChest prototype) throws Exception {
        try {
            HologramNativePlacement.validateFactoryId(payload, prototype);
            throw new AssertionError("mismatched or mistyped native tile identity must be rejected");
        } catch (java.io.IOException expected) {}
    }

    /** Invoked by the resource suite after real FML initialization with CarpentersBlocks installed. */
    static void nativeCarpenterResources() throws Exception {
        Block slope = null;
        for (Object candidate : Block.blockRegistry) if (candidate.getClass()
            .getName()
            .equals("com.carpentersblocks.block.BlockCarpentersSlope")) slope = (Block) candidate;
        SimpleAssert.that(slope != null, "native Carpenter resource fixture requires the registered slope");
        HologramResourceWorld detached = HologramResourceWorld.create(slope, 0);
        TileEntity tile = slope.createTileEntity(detached, 0);
        tile.setWorldObj(detached);
        tile.xCoord = 0;
        tile.yCoord = 64;
        tile.zCoord = 0;
        detached.tile = tile;
        Class<?> base = Class.forName("com.carpentersblocks.tileentity.TEBase");
        int wedge = Class.forName("com.carpentersblocks.data.Slope")
            .getField("ID_WEDGE_POS_N")
            .getByte(null);
        base.getMethod("setData", int.class)
            .invoke(tile, wedge);
        ItemStack taggedPlanks = new ItemStack(Blocks.planks, 1, 3);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("exactNativeItemTag", "retained Carpenter material variant");
        taggedPlanks.setTagCompound(tag);
        java.lang.reflect.Method apply = base.getMethod("addAttribute", byte.class, ItemStack.class);
        apply.invoke(tile, (byte) 6, new ItemStack(Blocks.log, 1, 8));
        apply.invoke(tile, (byte) 5, taggedPlanks);
        apply.invoke(tile, (byte) 13, new ItemStack(Items.dye, 1, 4));
        apply.invoke(tile, (byte) 20, new ItemStack(Items.snowball));
        NBTTagCompound captured = new NBTTagCompound();
        tile.writeToNBT(captured);
        NBTTagCompound original = (NBTTagCompound) captured.copy();
        List<ItemStack> nativeBill = HologramNativePlacement.materials(slope, 0, captured);
        HologramDecorMaterials.Descriptor descriptor = HologramDecorMaterials.describe(slope, 0, captured);
        HologramDecorMaterials.Quote construction = new HologramDecorMaterials.Ledger()
            .quote(descriptor, (stack, limit) -> 0);
        assertSameBill(nativeBill, construction.items, "native construction prices shell and all retained attributes");
        SimpleAssert.eq(1, count(construction.items, taggedPlanks), "complete native material ItemNBT remains exact");
        SimpleAssert.eq(1, count(construction.items, new ItemStack(Items.dye, 1, 4)), "dye is a paid native component");
        SimpleAssert
            .eq(1, count(construction.items, new ItemStack(Items.snowball)), "overlay is a paid native component");
        SimpleAssert.eq(
            1,
            count(construction.items, new ItemStack(Blocks.log, 1, 0)),
            "rotated cover uses the native drop cost");
        SimpleAssert.that(
            HologramBlueprintResources.plan(captured, nativeBill).items.isEmpty(),
            "construction resource inspection never charges the paid native attributes twice");
        HologramPresets.Preset preset = carpenterPreset(slope, captured);
        HologramBlueprintResources.Plan review = HologramAePolicy.materials(preset);
        SimpleAssert
            .that(preset.aeIssues.isEmpty(), "complete draft resource review accepts registered Carpenter attributes");
        assertSameBill(construction.items, review.items, "save review and construction share one exact native bill");
        ItemStack overlay = new ItemStack(Items.snowball);
        int[] modulated = { 0 };
        SimpleAssert.that(!review.reserve(new HologramBlueprintResources.Payment() {

            public boolean item(ItemStack stack, boolean simulate) {
                if (!simulate) modulated[0]++;
                return !stack.isItemEqual(overlay);
            }

            public boolean fluid(net.minecraftforge.fluids.FluidStack stack, long amount, boolean simulate) {
                throw new AssertionError("Carpenter fixture has no fluids");
            }

            public boolean energy(BigInteger amount, boolean simulate) {
                return amount.signum() == 0;
            }
        }), "missing native overlay blocks the complete material reservation");
        SimpleAssert.eq(0, modulated[0], "missing overlay never partially consumes other native material");
        List<ItemStack> unpaidOverlay = new ArrayList<>();
        for (ItemStack stack : nativeBill) if (!stack.isItemEqual(overlay)) unpaidOverlay.add(stack.copy());
        rejectCarpenterResources(slope, captured, unpaidOverlay, false);
        NBTTagCompound wrongCreditTag = (NBTTagCompound) captured.copy();
        NBTTagList creditRows = wrongCreditTag.getTagList("cbAttrList", 10);
        for (int i = 0; i < creditRows.tagCount(); i++) if (creditRows.getCompoundTagAt(i)
            .hasKey("tag", 10))
            creditRows.getCompoundTagAt(i)
                .getCompoundTag("tag")
                .setString("exactNativeItemTag", "different variant");
        rejectCarpenterResources(slope, wrongCreditTag, nativeBill, false);
        NBTTagCompound secondPhysicalCopy = (NBTTagCompound) captured.copy();
        NBTTagCompound repeatedMaterial = null;
        for (int i = 0; i < creditRows.tagCount(); i++) if (captured.getTagList("cbAttrList", 10)
            .getCompoundTagAt(i)
            .getByte("cbAttribute") == 5)
            repeatedMaterial = (NBTTagCompound) captured.getTagList("cbAttrList", 10)
                .getCompoundTagAt(i)
                .copy();
        repeatedMaterial.setByte("cbAttribute", (byte) 0);
        secondPhysicalCopy.getTagList("cbAttrList", 10)
            .appendTag(repeatedMaterial);
        rejectCarpenterResources(slope, secondPhysicalCopy, nativeBill, false);
        HologramDecorMaterials.Quote repeatedQuote = new HologramDecorMaterials.Ledger()
            .quote(HologramDecorMaterials.describe(slope, 0, secondPhysicalCopy), (stack, limit) -> 0);
        SimpleAssert.eq(
            2,
            count(repeatedQuote.items, taggedPlanks),
            "two different attribute slots require two actual materials");
        NBTTagCompound duplicateAsset = (NBTTagCompound) captured.copy();
        duplicateAsset.setTag("unreviewedAsset", taggedPlanks.writeToNBT(new NBTTagCompound()));
        rejectCarpenterResources(slope, duplicateAsset, nativeBill, true);
        NBTTagList sourceRows = captured.getTagList("cbAttrList", 10);
        for (String field : Arrays.asList("cbAttribute", "id", "Count", "Damage", "cbUniqueId", "tag")) {
            NBTTagCompound malformed = (NBTTagCompound) captured.copy();
            malformed.getTagList("cbAttrList", 10)
                .getCompoundTagAt(0)
                .setString(field, "wrong native type");
            rejectCarpenterEverywhere(slope, malformed, nativeBill);
        }
        for (byte attribute : new byte[] { -1, 26 }) {
            NBTTagCompound malformed = (NBTTagCompound) captured.copy();
            malformed.getTagList("cbAttrList", 10)
                .getCompoundTagAt(0)
                .setByte("cbAttribute", attribute);
            rejectCarpenterEverywhere(slope, malformed, nativeBill);
        }
        for (byte amount : new byte[] { 0, -1, 2 }) {
            NBTTagCompound malformed = (NBTTagCompound) captured.copy();
            malformed.getTagList("cbAttrList", 10)
                .getCompoundTagAt(0)
                .setByte("Count", amount);
            rejectCarpenterEverywhere(slope, malformed, nativeBill);
        }
        NBTTagCompound duplicateAttribute = (NBTTagCompound) captured.copy();
        duplicateAttribute.getTagList("cbAttrList", 10)
            .appendTag(
                sourceRows.getCompoundTagAt(0)
                    .copy());
        rejectCarpenterEverywhere(slope, duplicateAttribute, nativeBill);
        NBTTagCompound unknownItemField = (NBTTagCompound) captured.copy();
        unknownItemField.getTagList("cbAttrList", 10)
            .getCompoundTagAt(0)
            .setTag("ItemNBT", new ItemStack(Items.diamond).writeToNBT(new NBTTagCompound()));
        rejectCarpenterEverywhere(slope, unknownItemField, nativeBill);
        NBTTagCompound wrongRegistry = (NBTTagCompound) captured.copy();
        wrongRegistry.getTagList("cbAttrList", 10)
            .getCompoundTagAt(0)
            .setString("cbUniqueId", "minecraft:diamond");
        rejectCarpenterEverywhere(slope, wrongRegistry, nativeBill);
        NBTTagCompound missingItem = (NBTTagCompound) captured.copy();
        missingItem.getTagList("cbAttrList", 10)
            .getCompoundTagAt(0)
            .setShort("id", (short) -1);
        rejectCarpenterEverywhere(slope, missingItem, nativeBill);
        for (String field : Arrays.asList("cbOwner", "cbDesign", "cbChiselDesign_0")) {
            NBTTagCompound malformed = (NBTTagCompound) captured.copy();
            malformed.setInteger(field, 3);
            rejectCarpenterEverywhere(slope, malformed, nativeBill);
        }
        NBTTagCompound wrongMetadata = (NBTTagCompound) captured.copy();
        wrongMetadata.setString("cbMetadata", "unreviewed shape");
        rejectCarpenterEverywhere(slope, wrongMetadata, nativeBill);
        NBTTagCompound wrongFamily = (NBTTagCompound) captured.copy();
        wrongFamily.setString("id", "Chest");
        rejectCarpenterEverywhere(slope, wrongFamily, nativeBill);
        NBTTagCompound malformedList = (NBTTagCompound) captured.copy();
        malformedList.setString("cbAttrList", "opaque list");
        rejectCarpenterEverywhere(slope, malformedList, nativeBill);
        NBTTagList stringRows = new NBTTagList();
        stringRows.appendTag(new NBTTagString("opaque attribute"));
        malformedList.setTag("cbAttrList", stringRows);
        rejectCarpenterEverywhere(slope, malformedList, nativeBill);
        SimpleAssert
            .eq(original, captured, "native review, quotes, drop normalization and rejections preserve source NBT");
        SimpleAssert.eq(
            8,
            ((ItemStack) base.getMethod("getAttribute", byte.class)
                .invoke(tile, (byte) 6)).getItemDamage(),
            "native drop normalization never mutates the captured native tile");
        System.out
            .println("[GTMB-CARPENTER-RESOURCES] FINAL PASS detached native factory; review/construction contract");
    }

    private static HologramPresets.Preset carpenterPreset(Block block, NBTTagCompound payload) throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(block), 64, 80, 72, -1, false);
        cell.block = Block.blockRegistry.getNameForObject(block);
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        preset.cells.add(cell);
        return preset;
    }

    private static void rejectCarpenterEverywhere(Block block, NBTTagCompound payload, List<ItemStack> credit)
        throws Exception {
        NBTTagCompound original = (NBTTagCompound) payload.copy();
        try {
            HologramDecorMaterials.describe(block, 0, payload);
            throw new AssertionError("malformed native attributes must reject the construction quote");
        } catch (IOException expected) {}
        rejectCarpenterResources(block, payload, credit, true);
        SimpleAssert.eq(original, payload, "invalid native attribute rejection never edits its source");
    }

    private static void rejectCarpenterResources(Block block, NBTTagCompound payload, List<ItemStack> credit,
        boolean reviewAlso) throws Exception {
        try {
            HologramBlueprintResources.plan(payload, credit);
            throw new AssertionError("unreviewed or unpaid native attributes must reject resource inspection");
        } catch (IOException expected) {}
        if (reviewAlso) {
            HologramPresets.Preset preset = carpenterPreset(block, payload);
            HologramAePolicy.materials(preset);
            SimpleAssert.that(
                preset.aeIssues.stream()
                    .anyMatch(
                        issue -> issue.code.equals("review.materials_unknown") && issue.severity.equals("blocker")),
                "full draft review explicitly blocks malformed physical attributes");
        }
    }

    private static void assertSameBill(List<ItemStack> expected, List<ItemStack> actual, String message) {
        for (ItemStack stack : expected) SimpleAssert.eq(count(expected, stack), count(actual, stack), message);
        for (ItemStack stack : actual) SimpleAssert.eq(count(expected, stack), count(actual, stack), message);
    }

    private static int count(List<ItemStack> items, ItemStack sample) {
        int result = 0;
        for (ItemStack item : items)
            if (item.isItemEqual(sample) && ItemStack.areItemStackTagsEqual(item, sample)) result += item.stackSize;
        return result;
    }
}
