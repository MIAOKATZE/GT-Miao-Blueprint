// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.compat.cookingforblockheads;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.hologram.HologramBlueprintResources;
import com.miaokatze.gtmb.hologram.HologramNativeCapture;
import com.miaokatze.gtmb.hologram.HologramNativePlacement;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModMetadata;

/** Exercises the installed production codecs without loading a kitchen world or ticking native tiles. */
public final class CookingForBlockheadsRegression {

    private CookingForBlockheadsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(CookingForBlockheadsRegression.class, args)) return;
        int registrations = GTMBExtensions.descriptors()
            .size();
        CookingForBlockheadsCompatibility.register();
        try {
            sink();
            book();
            oven();
            nativeLocalCapture();
            nativeSerializers();
            nativeRoundTrips();
            shellInsideTable();
        } finally {
            CookingForBlockheadsCompatibility.close();
        }
        SimpleAssert.eq(
            registrations,
            GTMBExtensions.descriptors()
                .size(),
            "only module registrations removed");
        System.out.println("Cooking For Blockheads resource regressions passed");
    }

    private static void nativeLocalCapture() throws Exception {
        Class<? extends TileEntity> type = Class.forName("net.blay09.mods.cookingforblockheads.tile.TileFridge")
            .asSubclass(TileEntity.class);
        TileEntity.addMapping(type, "cookingforblockheads:fridge");
        TileEntity fridge = type.getConstructor()
            .newInstance();
        IInventory local = (IInventory) type.getMethod("getInternalInventory")
            .invoke(fridge);
        ItemStack diamonds = new ItemStack(Items.diamond, 7);
        local.setInventorySlotContents(3, diamonds);
        Field shared = type.getSuperclass()
            .getDeclaredField("sharedInventory");
        shared.setAccessible(true);
        Object network = Proxy.newProxyInstance(
            IInventory.class.getClassLoader(),
            new Class<?>[] { IInventory.class },
            (proxy, method, args) -> {
                throw new AssertionError("Capture followed shared kitchen inventory: " + method);
            });
        shared.set(fridge, network);
        NBTTagCompound before = new NBTTagCompound();
        fridge.writeToNBT(before);
        NBTTagCompound captured = HologramNativeCapture.capture(fridge);
        NBTTagCompound after = new NBTTagCompound();
        fridge.writeToNBT(after);
        SimpleAssert.eq(before, after, "real fridge serializer leaves local and source state unchanged");
        SimpleAssert.eq(
            7,
            captured.getTagList("Items", 10)
                .getCompoundTagAt(0)
                .getByte("Count") & 255,
            "only the seven locally persisted diamonds were captured");
        SimpleAssert.that(
            local.getStackInSlot(3) == diamonds && diamonds.stackSize == 7,
            "capture neither replaces nor mutates the live stack");
        SimpleAssert.that(
            shared.get(fridge) == network && fridge.getWorldObj() == null,
            "capture neither rebinds shared storage nor creates a world");
    }

    private static void nativeSerializers() throws Exception {
        String[][] names = { { "cookingtable", "CookingTable" }, { "cookingoven", "Oven" }, { "counter", "Counter" },
            { "countercorner", "CounterCorner" }, { "cabinet", "Cabinet" }, { "cabinetcorner", "CabinetCorner" },
            { "sink", "Sink" }, { "toolrack", "ToolRack" }, { "toaster", "Toaster" } };
        for (String[] entry : names) {
            Class<? extends TileEntity> type = Class
                .forName("net.blay09.mods.cookingforblockheads.tile.Tile" + entry[1])
                .asSubclass(TileEntity.class);
            TileEntity.addMapping(type, "cookingforblockheads:" + entry[0]);
            TileEntity tile = type.getConstructor()
                .newInstance();
            NBTTagCompound before = new NBTTagCompound();
            tile.writeToNBT(before);
            NBTTagCompound captured = HologramNativeCapture.capture(tile);
            CookingForBlockheadsCompatibility.validate(entry[0], captured);
            NBTTagCompound after = new NBTTagCompound();
            tile.writeToNBT(after);
            SimpleAssert.eq(before, after, "real read-only native serializer: " + entry[0]);
        }
    }

    private static Block fixtureBlock(String name, String field) throws Exception {
        String registry = "cookingforblockheads:" + name;
        if (Block.blockRegistry.containsKey(registry)) return Block.getBlockFromName(registry);
        Block block = Class
            .forName("net.blay09.mods.cookingforblockheads.block.Block" + field.substring("block".length()))
            .asSubclass(Block.class)
            .getConstructor()
            .newInstance();
        int free = 4095;
        while (free > 0 && (Item.getItemById(free) != null
            || Block.getBlockById(free) != null && Block.getBlockById(free) != Blocks.air)) free--;
        SimpleAssert.that(free > 0, "isolated real kitchen shell registry slot");
        Item item = name.equals("fridge")
            ? (Item) Class.forName("net.blay09.mods.cookingforblockheads.item.ItemBlockFridge")
                .getConstructor(Block.class)
                .newInstance(block)
            : (Item) Class.forName("net.blay09.mods.cookingforblockheads.item.ItemBlockGenericKitchen")
                .getConstructor(Block.class, String.class, Boolean.class)
                .newInstance(block, name, true);
        // GameData.addPrefix uses the active mod, even for a name already containing a colon.
        Loader loader = Loader.instance();
        Field controllerField = Loader.class.getDeclaredField("modController");
        controllerField.setAccessible(true);
        Object previousController = controllerField.get(loader);
        LoadController controller = new LoadController(loader);
        Field active = LoadController.class.getDeclaredField("activeContainer");
        active.setAccessible(true);
        ModMetadata metadata = new ModMetadata();
        metadata.modId = "cookingforblockheads";
        active.set(controller, new DummyModContainer(metadata));
        controllerField.set(loader, controller);
        try {
            Block.blockRegistry.addObject(free, registry, block);
            Item.itemRegistry.addObject(free, registry, item);
        } finally {
            controllerField.set(loader, previousController);
        }
        SimpleAssert.eq(registry, Block.blockRegistry.getNameForObject(block), "real fixture block namespace");
        SimpleAssert.eq(registry, Item.itemRegistry.getNameForObject(item), "real fixture item namespace");
        return block;
    }

    private static void nativeRoundTrips() throws Exception {
        String[][] names = { { "cookingtable", "CookingTable" }, { "cookingoven", "Oven" }, { "counter", "Counter" },
            { "countercorner", "CounterCorner" }, { "cabinet", "Cabinet" }, { "cabinetcorner", "CabinetCorner" },
            { "fridge", "Fridge" }, { "sink", "Sink" }, { "toolrack", "ToolRack" }, { "toaster", "Toaster" } };
        for (String[] entry : names) {
            Block block = fixtureBlock(entry[0], "block" + entry[1]);
            TileEntity original = Class.forName("net.blay09.mods.cookingforblockheads.tile.Tile" + entry[1])
                .asSubclass(TileEntity.class)
                .getConstructor()
                .newInstance();
            NBTTagCompound selected = HologramNativeCapture.capture(original);
            if (selected.hasKey("Color")) selected.setByte("Color", (byte) 5);
            if (selected.hasKey("IsFlipped")) selected.setBoolean("IsFlipped", true);
            if (selected.hasKey("Items")) {
                NBTTagList inventory = new NBTTagList();
                NBTTagCompound item = new NBTTagCompound();
                new ItemStack(Items.diamond, 1).writeToNBT(item);
                item.setByte("Slot", (byte) 0);
                inventory.appendTag(item);
                selected.setTag("Items", inventory);
            }
            if (entry[0].equals("cookingtable")) {
                NBTTagCompound item = new NBTTagCompound();
                new ItemStack(Items.diamond, 1).writeToNBT(item);
                selected.setTag("NoFilterBook", item);
            }
            if (entry[0].equals("sink")) {
                selected.removeTag("Empty");
                new FluidStack(FluidRegistry.WATER, 1234).writeToNBT(selected);
            }
            if (entry[0].equals("cookingoven")) {
                selected.setInteger("BurnTime", 250);
                selected.setInteger("CurrentItemBurnTime", 1600);
                selected.setIntArray("CookTimes", new int[] { 17, -1, 0, 0, 0, 0, 0, 0, 0 });
            }
            HologramNativePlacement.Adapter adapter = HologramNativePlacement.adapter(block);
            SimpleAssert.that(adapter != null, "real native block has module factory: " + entry[0]);
            SimpleAssert.that(
                !HologramNativePlacement.supported(block, (NBTTagCompound) null)
                    && HologramNativePlacement.supported(block, selected),
                "ordinary ItemBlock placement is preserved unless a native payload exists: " + entry[0]);
            TileEntity restored = adapter.create(block, 2, selected, null);
            SimpleAssert.that(restored.getWorldObj() == null, "native reader restores without a world: " + entry[0]);
            SimpleAssert.eq(
                selected,
                HologramNativeCapture.capture(restored),
                "native complete persistence roundtrip: " + entry[0]);
            List<ItemStack> costs = adapter.materials(block, 2, restored);
            SimpleAssert.eq(1, costs.size(), "one native shell component: " + entry[0]);
            SimpleAssert.that(
                costs.get(0)
                    .getItem() == Item.getItemFromBlock(block)
                    && costs.get(0)
                        .getItemDamage() == 0
                    && costs.get(0).stackSize == 1,
                "shell item has no directional item variant: " + entry[0]);
        }
    }

    private static void shellInsideTable() throws Exception {
        Block block = fixtureBlock("cookingtable", "blockCookingTable");
        Item item = Item.getItemFromBlock(block);
        ItemStack shell = new ItemStack(item, 1);
        TileEntity table = Class.forName("net.blay09.mods.cookingforblockheads.tile.TileCookingTable")
            .asSubclass(TileEntity.class)
            .getConstructor()
            .newInstance();
        table.getClass()
            .getMethod("setNoFilterBook", ItemStack.class)
            .invoke(table, shell);
        NBTTagCompound captured = HologramNativeCapture.capture(table);
        HologramNativePlacement.Adapter adapter = HologramNativePlacement.adapter(block);
        SimpleAssert
            .that(adapter != null && !adapter.includesInventoryDrops(block), "CFB shell bill excludes internal assets");
        HologramBlueprintResources.Plan plan = HologramBlueprintResources
            .plan(captured, adapter.materials(block, 2, table), adapter.includesInventoryDrops(block));
        SimpleAssert.eq(1, plan.items.size(), "shell credit cannot erase a physically retained identical shell");
        SimpleAssert.that(
            plan.items.get(0)
                .getItem() == item && plan.items.get(0).stackSize == 1,
            "the second shell inside NoFilterBook remains payable");
    }

    private static void sink() throws Exception {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("id", "cookingforblockheads:sink");
        new FluidStack(FluidRegistry.WATER, 1250).writeToNBT(root);
        NBTTagCompound identity = new NBTTagCompound();
        identity.setString("batch", "tag preserved");
        root.setTag("Tag", identity);
        NBTTagCompound original = (NBTTagCompound) root.copy();
        ResourceContainers.RootClaim claim = only(root);
        SimpleAssert.eq(
            1250L,
            claim.fluids()
                .get(0).amount,
            "finite serialized amount, not handler INT_MAX");
        SimpleAssert.eq(
            identity,
            claim.fluids()
                .get(0)
                .identity().tag,
            "fluid tag identity retained");
        SimpleAssert.eq(original, root, "resource inspection is read-only");
        expectFailure(
            () -> ResourceContainers.validateSelection(root, new HashSet<>(Arrays.asList("FluidName", "Amount"))));
        SimpleAssert.that(
            ResourceContainers.decodeRoots(root, Collections.emptySet())
                .isEmpty(),
            "unselected group has no bill");
        root.setString("id", "cookingbook:sink");
        SimpleAssert.eq(
            1250L,
            only(root).fluids()
                .get(0).amount,
            "legacy registered tile id");
        root.setString("id", "other:sink");
        SimpleAssert.that(
            ResourceContainers.decodeRoots(root)
                .isEmpty(),
            "unrelated namespace is not admitted");
        root.setString("id", "cookingforblockheads:sink");
        root.setInteger("Amount", Integer.MAX_VALUE);
        expectFailure(() -> ResourceContainers.decodeRoots(root));
        root.setLong("Amount", 1000);
        expectFailure(() -> ResourceContainers.decodeRoots(root));
        root.removeTag("FluidName");
        root.removeTag("Amount");
        root.removeTag("Tag");
        root.setString("Empty", "");
        SimpleAssert.that(
            only(root).fluids()
                .isEmpty(),
            "empty physical tank has no cost");
        root.setInteger("Amount", 10);
        expectFailure(() -> ResourceContainers.decodeRoots(root));
    }

    private static void book() throws Exception {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("id", "cookingforblockheads:cookingtable");
        root.setTag("NoFilterBook", new NBTTagCompound());
        SimpleAssert.that(
            only(root).items()
                .isEmpty(),
            "native empty book compound is not an item");
        NBTTagCompound book = new NBTTagCompound();
        new ItemStack(Items.book, 1).writeToNBT(book);
        root.setTag("NoFilterBook", book);
        SimpleAssert.eq(
            1,
            only(root).items()
                .get(0).stackSize,
            "real retained book paid exactly once");
        book.setInteger("id", Item.getIdFromItem(Items.book));
        SimpleAssert.that(
            only(root).items()
                .get(0)
                .getItem() == Items.book,
            "native integer id reader compatibility");
        book.setShort("id", (short) 0);
        book.setInteger("idExt", Item.getIdFromItem(Items.book));
        SimpleAssert.that(
            only(root).items()
                .get(0)
                .getItem() == Items.book,
            "EndlessIDs idExt takes precedence over zero short id");
        book.setFloat("idExt", 340);
        expectFailure(() -> ResourceContainers.decodeRoots(root));
        book.removeTag("idExt");
        book.setShort("id", (short) Item.getIdFromItem(Items.book));
        book.setInteger("Credits", 1000);
        expectFailure(() -> ResourceContainers.decodeRoots(root));
    }

    private static void oven() throws Exception {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("id", "cookingforblockheads:cookingoven");
        root.setInteger("BurnTime", 0);
        root.setInteger("CurrentItemBurnTime", 1600);
        root.setIntArray("CookTimes", new int[9]);
        SimpleAssert.that(
            only(root).items()
                .isEmpty(),
            "historical fuel duration is not remaining fuel");
        expectFailure(
            () -> ResourceContainers.validateSelection(root, new HashSet<>(Arrays.asList("BurnTime", "CookTimes"))));
        root.setInteger("BurnTime", 1200);
        NBTTagCompound original = (NBTTagCompound) root.copy();
        expectFailure(() -> ResourceContainers.decodeRoots(root));
        SimpleAssert.eq(original, root, "refused runtime state stays intact for RAW preservation");
        root.setInteger("BurnTime", 0);
        root.setIntArray("CookTimes", new int[] { 5, 0, 0, 0, 0, 0, 0, 0, 0 });
        expectFailure(() -> ResourceContainers.decodeRoots(root));
        root.setIntArray("CookTimes", new int[] { -1, 0, 0, 0, 0, 0, 0, 0, 0 });
        CookingForBlockheadsCompatibility.validate("cookingoven", root);
        SimpleAssert.that(
            only(root).items()
                .isEmpty(),
            "native completed-slot sentinel is not cooking progress");
    }

    private static ResourceContainers.RootClaim only(NBTTagCompound root) throws IOException {
        List<ResourceContainers.RootClaim> claims = ResourceContainers.decodeRoots(root);
        SimpleAssert.eq(1, claims.size(), "one complete native resource group");
        return claims.get(0);
    }

    private interface Checked {

        void run() throws Exception;
    }

    private static void expectFailure(Checked action) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Malformed or unpriced CFB state was accepted");
    }
}
