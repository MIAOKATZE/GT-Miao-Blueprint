package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.testutil.BlueprintArtifactTestData;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Synthetic artifacts and real native Carpenter factories; no player files or live placement are used. */
final class HologramCarpentersCompatibilityRegression {

    private HologramCarpentersCompatibilityRegression() {}

    static void run(Class<?> base) throws Exception {
        Field config = DecorationVoucherConfig.class.getDeclaredField("current");
        config.setAccessible(true);
        Object previous = config.get(null);
        try {
            config.set(null, DecorationVoucherConfig.defaults());
            Block slope = null;
            for (String type : new String[] { "Block", "Barrier", "Bed", "Button", "CollapsibleBlock", "DaylightSensor",
                "Door", "FlowerPot", "GarageDoor", "Gate", "Hatch", "Ladder", "Lever", "PressurePlate", "Safe", "Slope",
                "Stairs", "Torch" }) {
                Block block = register(type);
                SimpleAssert.that(
                    HologramDecorationVoucher.covered(block, new ItemStack(block)),
                    "registered Carpenter ItemBlock family remains covered: " + type);
                if (type.equals("Slope")) slope = block;
            }
            NBTTagCompound captured = nativePayload(base);
            oldArtifactAndCapture(slope, captured);
            ownerTypesAreIgnored(slope, captured);
            nativeVoucherBill(slope, captured, config);
        } finally {
            config.set(null, previous);
        }
    }

    private static Block register(String type) throws Exception {
        String name = "CarpentersBlocks:blockCarpenters" + type;
        Object existing = Block.blockRegistry.getObject(name);
        if (existing instanceof Block && existing != Blocks.air) return (Block) existing;
        Block block = (Block) Class.forName("com.carpentersblocks.block.BlockCarpenters" + type)
            .getConstructor(Material.class)
            .newInstance(Material.wood);
        Item item = type.equals("Slope") ? (Item) Class.forName("com.carpentersblocks.block.ItemBlockCarpentersSlope")
            .getConstructor(Block.class)
            .newInstance(block) : new ItemBlock(block);
        int free = 3650;
        while (free > 0 && (Item.getItemById(free) != null || Block.getBlockById(free) != Blocks.air)) free--;
        SimpleAssert.that(free > 0, "isolated Carpenter fixture has a free registry slot");
        Method add = cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry.class
            .getDeclaredMethod("add", int.class, String.class, Object.class, BitSet.class);
        add.setAccessible(true);
        add.invoke(Block.blockRegistry, free, name, block, new BitSet());
        add.invoke(Item.itemRegistry, free, name, item, new BitSet());
        return block;
    }

    @SuppressWarnings("unchecked")
    private static NBTTagCompound nativePayload(Class<?> base) throws Exception {
        TileEntity tile = (TileEntity) base.getConstructor()
            .newInstance();
        NBTTagCompound initial = new NBTTagCompound();
        initial.setInteger("cbMetadata", 19);
        initial.setString("cbOwner", "synthetic-original-owner");
        initial.setTag("cbAttrList", new NBTTagList());
        tile.readFromNBT(initial);
        Object cover = Class.forName("com.carpentersblocks.util.Attribute")
            .getConstructor(ItemStack.class)
            .newInstance(new ItemStack(Blocks.wool, 1, 11));
        Field attributes = base.getDeclaredField("cbAttrMap");
        attributes.setAccessible(true);
        java.util.Map<Byte, Object> map = (java.util.Map<Byte, Object>) attributes.get(tile);
        map.put((byte) 6, cover);
        map.put((byte) 0, cover);
        NBTTagCompound tag = new NBTTagCompound();
        tile.writeToNBT(tag);
        tag.setString("ownerID", "synthetic-compatibility-alias");
        NBTTagCompound opaque = new NBTTagCompound();
        opaque.setString("ownerID", "nested-opaque-value");
        opaque.setString("cbOwner", "nested-opaque-value");
        tag.setTag("opaqueConfiguration", opaque);
        return HologramBlueprintNbt.decode(HologramBlueprintNbt.encodeCaptured(tag));
    }

    private static HologramPresets.Preset preset(Block block, NBTTagCompound tag) throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.id = "carpenter_compatibility_fixture";
        preset.name = "Synthetic Carpenter";
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(block), 0, 0, 0, -1, false);
        cell.block = Block.blockRegistry.getNameForObject(block);
        // Raw transport deliberately models an older saved payload, before contextual compatibility projection.
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(tag);
        preset.cells.add(cell);
        HologramBlueprintVersions.stampSaved(preset);
        return preset;
    }

    private static void oldArtifactAndCapture(Block slope, NBTTagCompound original) throws Exception {
        NBTTagCompound expected = (NBTTagCompound) original.copy();
        expected.removeTag("cbOwner");
        expected.removeTag("ownerID");
        NBTTagCompound item = new NBTTagCompound();
        item.setString("cbOwner", "synthetic-item-owner");
        item.setString("ownerID", "synthetic-item-alias");
        item.setString("displayMarker", "keep-this-item-data");
        HologramPresets.Preset source = preset(slope, original);
        source.cells.get(0).itemNbt = HologramBlueprintNbt.encodeCaptured(item);
        Path directory = Files.createTempDirectory("gtmb-carpenter-compat-");
        Path file = directory.resolve("synthetic.json");
        try {
            byte[] json = HologramBlueprintCompactCodec.encode(source, (done, total) -> {});
            HologramBlueprintArtifact.writePair(file, json, BlueprintArtifactTestData.previewPng());
            byte[] png = Files.readAllBytes(HologramBlueprintArtifact.previewPath(file));
            HologramPresets.Preset loaded = HologramBlueprintSharing.read(file);
            SimpleAssert.eq(
                expected,
                HologramBlueprintNbt.decode(loaded.cells.get(0).tileNbt),
                "normal legacy artifact read removes only root owner fields and preserves shape and two covers");
            NBTTagCompound expectedItem = (NBTTagCompound) item.copy();
            expectedItem.removeTag("cbOwner");
            expectedItem.removeTag("ownerID");
            SimpleAssert.eq(
                expectedItem,
                HologramBlueprintNbt.decode(loaded.cells.get(0).itemNbt),
                "context identifies an old Carpenter item payload without inventing a tile id");
            SimpleAssert.that(
                Arrays.equals(json, Files.readAllBytes(file))
                    && Arrays.equals(png, Files.readAllBytes(HologramBlueprintArtifact.previewPath(file))),
                "loading conversion is memory-only and never rewrites either original artifact member");
            HologramPresets.Preset sanitized = HologramAePolicy.sanitize(source).preset;
            SimpleAssert.eq(
                expected,
                HologramBlueprintNbt.decode(sanitized.cells.get(0).tileNbt),
                "level-zero identity admission uses the same Carpenter compatibility projection");
            SimpleAssert.eq(
                0,
                sanitized.deidentificationLevel,
                "Carpenter ownership omission does not require raising the global deidentification policy");
            SimpleAssert.eq(
                expected,
                HologramBlueprintNbt.decode(HologramBlueprintNbt.encodeCaptured(slope, original)),
                "new contextual capture omits native ownership without touching source shape or covers");
            SimpleAssert.eq(
                original,
                HologramBlueprintNbt.decode(source.cells.get(0).tileNbt),
                "canonicalizing a detached draft leaves the source payload unchanged");
            String unrelated = HologramBlueprintNbt.encodeCaptured(original);
            SimpleAssert.eq(
                unrelated,
                HologramCarpentersCompatibility.normalize(Blocks.stone, unrelated),
                "the same root fields on another mod/block are not guessed as Carpenter ownership");
            byte[] tampered = Arrays.copyOf(json, json.length + 1);
            tampered[tampered.length - 1] = ' ';
            Files.write(file, tampered);
            try {
                HologramBlueprintSharing.read(file);
                throw new AssertionError("original artifact digest must be checked before ownership conversion");
            } catch (java.io.IOException expectedDigestFailure) {}
        } finally {
            Files.deleteIfExists(HologramBlueprintArtifact.previewPath(file));
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void ownerTypesAreIgnored(Block slope, NBTTagCompound original) throws Exception {
        NBTTagList longs = new NBTTagList();
        longs.appendTag(new net.minecraft.nbt.NBTTagLong(17L));
        longs.appendTag(new net.minecraft.nbt.NBTTagLong(29L));
        NBTTagCompound compound = new NBTTagCompound();
        compound.setLong("UUIDMost", 17L);
        compound.setLong("UUIDLeast", 29L);
        for (net.minecraft.nbt.NBTBase value : new net.minecraft.nbt.NBTBase[] {
            new net.minecraft.nbt.NBTTagString("synthetic-owner"), new net.minecraft.nbt.NBTTagLong(17L),
            new net.minecraft.nbt.NBTTagIntArray(new int[] { 1, 2, 3, 4 }), longs, compound }) {
            NBTTagCompound source = (NBTTagCompound) original.copy();
            source.setTag("cbOwner", value.copy());
            source.setTag("ownerID", value.copy());
            NBTTagCompound expected = (NBTTagCompound) source.copy();
            expected.removeTag("cbOwner");
            expected.removeTag("ownerID");
            SimpleAssert.eq(
                expected,
                HologramBlueprintNbt.decode(HologramBlueprintNbt.encodeCaptured(slope, source)),
                "normal capture ignores either Carpenter root owner field regardless of NBT type " + value.getId());
            HologramPresets.Preset old = preset(slope, source);
            NBTTagCompound item = new NBTTagCompound();
            item.setTag("cbOwner", value.copy());
            item.setTag("ownerID", value.copy());
            item.setInteger("retainedItemMarker", 23);
            old.cells.get(0).itemNbt = HologramBlueprintNbt.encodeCaptured(item);
            HologramPresets.Preset loaded = HologramBlueprintSharing.decode(
                new String(
                    HologramBlueprintCompactCodec.encode(old, (done, total) -> {}),
                    java.nio.charset.StandardCharsets.UTF_8));
            SimpleAssert.eq(
                expected,
                HologramBlueprintNbt.decode(loaded.cells.get(0).tileNbt),
                "normal legacy decode omits typed owner roots and preserves exact native shape/covers");
            NBTTagCompound expectedItem = new NBTTagCompound();
            expectedItem.setInteger("retainedItemMarker", 23);
            SimpleAssert.eq(
                expectedItem,
                HologramBlueprintNbt.decode(loaded.cells.get(0).itemNbt),
                "normal legacy decode also omits non-string owner fields from the Carpenter item scope");
            String unrelated = HologramBlueprintNbt.encodeCaptured(source);
            SimpleAssert.eq(
                unrelated,
                HologramCarpentersCompatibility.normalize(Blocks.stone, unrelated),
                "ignoring owner types remains scoped to the confirmed Carpenter block family");
            SimpleAssert.that(
                source.hasKey("cbOwner", value.getId()) && source.hasKey("ownerID", value.getId()),
                "normal capture and loading do not mutate the original typed source payload");
        }
    }

    private static void nativeVoucherBill(Block slope, NBTTagCompound original, Field config) throws Exception {
        HologramPresets.Preset canonical = HologramAePolicy.sanitize(preset(slope, original)).preset;
        NBTTagCompound tag = HologramBlueprintNbt.decode(canonical.cells.get(0).tileNbt);
        HologramDecorMaterials.Descriptor descriptor = HologramDecorMaterials.describe(slope, 0, tag);
        HologramDecorMaterials.Ledger ledger = new HologramDecorMaterials.Ledger();
        HologramDecorMaterials.Quote ordinary = ledger.quote(descriptor, (sample, limit) -> 0);
        SimpleAssert.eq(1, count(ordinary.items, new ItemStack(slope)), "one real slope shell is priced per cell");
        SimpleAssert.eq(
            2,
            count(ordinary.items, new ItemStack(Blocks.wool, 1, 11)),
            "two occupied native cover slots require two exact physical cover items");
        for (int damage = 0; damage < 5; damage++) {
            final int shapeGroup = damage;
            HologramDecorMaterials.Quote prebuilt = ledger.quote(
                descriptor,
                (sample, limit) -> sample.getItem() == Item.getItemFromBlock(slope)
                    && sample.getItemDamage() == shapeGroup ? 1 : 0);
            SimpleAssert.eq(
                1,
                count(prebuilt.items, new ItemStack(slope, 1, damage)),
                "each native slope shell group pays exactly one shell without changing cbMetadata");
        }
        SimpleAssert.that(
            HologramVoucherCoverage.compute(canonical),
            "native factory credits make covered Carpenter skins pass the saved voucher coverage marker");
        HologramDecorMaterials.Descriptor payable = HologramDecorationVoucher.payable(slope, descriptor);
        HologramDecorMaterials.Quote waived = ledger.quote(payable, (sample, limit) -> 0);
        SimpleAssert.that(
            HologramDecorationVoucher.hasCovered(slope, descriptor) && waived.items.isEmpty(),
            "the server voucher ledger waives both native shell and approved covers");
        ledger.commit(waived);
        SimpleAssert.eq(
            2,
            count(ledger.quote(descriptor, (sample, limit) -> 0).items, new ItemStack(Blocks.wool, 1, 11)),
            "waived costs never create reusable raw-material ledger credit");
        SimpleAssert.that(
            HologramBlueprintResources.plan(tag, ordinary.items).items.isEmpty(),
            "independent asset inspection accepts covers only against the complete exact native bill");
        try {
            HologramBlueprintResources.plan(tag, Collections.singletonList(new ItemStack(Blocks.wool, 1, 11)));
            throw new AssertionError("one cover cannot credit two distinct native attribute slots");
        } catch (java.io.IOException expectedMissingCredit) {}
        DecorationVoucherConfig.View defaults = DecorationVoucherConfig.defaults();
        config.set(
            null,
            new DecorationVoucherConfig.View(
                true,
                defaults.allowedModIds,
                defaults.allowedBlocks,
                defaults.allowedOres,
                defaults.deniedModIds,
                Collections.singleton("minecraft:wool@11"),
                defaults.deniedOres));
        SimpleAssert.that(!HologramVoucherCoverage.compute(canonical), "administrator cover blacklist remains binding");
        SimpleAssert.eq(
            2,
            count(
                ledger.quote(HologramDecorationVoucher.payable(slope, descriptor), (sample, limit) -> 0).items,
                new ItemStack(Blocks.wool, 1, 11)),
            "a denied cover remains payable in its full native quantity");
        config.set(null, defaults);
        Block machine = DecorationVoucherRegression
            .registeredFixture("gregtech:gtmb_carpenter_negative", new Block(Material.iron) {});
        SimpleAssert.that(
            !HologramDecorationVoucher.eligible(new ItemStack(machine)),
            "nondecorative GT machines retain the industrial veto");
        HologramDecorMaterials.Cost industrial = new HologramDecorMaterials.Cost(
            new ItemStack(machine),
            new ItemStack(machine),
            1,
            1);
        SimpleAssert.that(
            !HologramDecorationVoucher.covered(slope, industrial),
            "a Carpenter host cannot waive an embedded industrial material");
        NBTTagCompound malformed = (NBTTagCompound) tag.copy();
        NBTTagList attrs = malformed.getTagList("cbAttrList", 10);
        attrs.appendTag(
            attrs.getCompoundTagAt(0)
                .copy());
        SimpleAssert.that(
            !HologramVoucherCoverage.compute(preset(slope, malformed)),
            "coverage still rejects duplicate native attributes rather than bypassing exact material validation");
    }

    private static int count(List<ItemStack> bill, ItemStack exact) {
        int result = 0;
        for (ItemStack item : bill)
            if (item.isItemEqual(exact) && ItemStack.areItemStackTagsEqual(item, exact)) result += item.stackSize;
        return result;
    }
}
