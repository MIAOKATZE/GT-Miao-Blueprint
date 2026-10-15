package com.miaokatze.gtmb.hologram;

import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.util.GTUtility;

/** Real registered item, cover factory, native cell and transaction checks; no production line inventory is used. */
public final class HologramGtswnNativeMaterialsSmoke {

    private static int checks;
    private static NBTTagCompound cases;

    private HologramGtswnNativeMaterialsSmoke() {}

    public static NBTTagCompound run(WorldServer world) throws Exception {
        require(
            world != null && world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-sulfur-"),
            "isolated world");
        checks = 0;
        cases = new NBTTagCompound();
        EntityPlayerMP actor = new ChatSinkActor(
            world,
            new GameProfile(UUID.fromString("02153e95-cb30-4a59-b9bf-e0d327dd3daa"), "GTMBNativeMaterial"));
        NBTTagCompound persisted = (NBTTagCompound) actor.getEntityData()
            .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
            .copy();
        ItemStack[] inventory = actor.inventory.mainInventory;
        boolean creative = actor.capabilities.isCreativeMode;
        actor.inventory.mainInventory = new ItemStack[36];
        actor.capabilities.isCreativeMode = false;
        try {
            nativeBills();
            supplies(actor);
            emptyMeCell(actor);
            nativeOutputSerializers();
            NBTTagCompound receipt = new NBTTagCompound();
            receipt.setBoolean("passed", true);
            receipt.setInteger("assertions", checks);
            receipt.setBoolean("actualNativeWorld", true);
            receipt.setBoolean("actualService", false);
            receipt.setBoolean("actualGui", false);
            receipt.setBoolean("fakeFixtureActor", true);
            receipt.setBoolean("actualMaterials", true);
            receipt.setBoolean("actualNativeTransactions", true);
            receipt.setBoolean("actualNativeProjection", true);
            receipt.setTag("cases", cases);
            receipt.setString(
                "scope",
                "native item/cover bills, prepaid+inventory transaction rollback, empty GT ME cell projection");
            System.out.println("[GTMB-GTSWN-MATERIALS] PASS " + checks + " assertions " + receipt);
            return receipt;
        } finally {
            actor.inventory.mainInventory = inventory;
            actor.capabilities.isCreativeMode = creative;
            actor.getEntityData()
                .setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        }
    }

    private static void nativeBills() throws Exception {
        Block node = null;
        for (Object value : Block.blockRegistry)
            if (value instanceof Block && HologramNetworkBindings.node((Block) value)) node = (Block) value;
        require(node != null, "actual native node registered");
        ItemStack identity = HologramAeBlueprint.blockSample(node, 0);
        fact(
            "freeNodeShell",
            HologramGtswnPlacementMaterials.freeShell(node, identity),
            "node retains placement identity with no shell bill");
        require(
            HologramNativePlacement.sample(node, 0, new NBTTagCompound())
                .isItemEqual(identity),
            "sample remains the node item");
        int regular = -1, laserInput = -1, laserOutput = -1;
        for (int id = 0; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity host = GregTechAPI.METATILEENTITIES[id];
            if (!(host instanceof MTEHatch)) continue;
            String name = host.getClass()
                .getName();
            if (name.equals("gregtech.api.metatileentity.implementations.MTEHatchEnergy")) regular = id;
            if (((MTEHatch) host).getConnectionType() != MTEHatch.ConnectionType.LASER) continue;
            if (name.equals("tectech.thing.metaTileEntity.hatch.MTEHatchEnergyTunnel")) laserInput = id;
            if (name.equals("tectech.thing.metaTileEntity.hatch.MTEHatchDynamoTunnel")) laserOutput = id;
        }
        require(
            regular >= 0 && laserInput >= 0 && laserOutput >= 0,
            "real energy hatch and both laser source/target registrations");
        for (String type : new String[] { "GTSwnCoverEnergyWireless", "GTSwnCoverDynamoWireless" }) {
            Item coverItem = null;
            for (Object value : Item.itemRegistry) if (value instanceof Item && value.getClass()
                .getName()
                .equals("com.miaokatze.gtswn.common.items." + type)) coverItem = (Item) value;
            require(coverItem != null, "actual native cover item " + type);
            ItemStack cover = new ItemStack(coverItem);
            NBTTagCompound ordinary = coverPayload(regular, cover);
            fact(
                type.equals("GTSwnCoverEnergyWireless") ? "ordinaryEnergyCoverFree" : "ordinaryDynamoCoverFree",
                HologramBlueprintCovers.materials(HologramBlueprintNbt.encodeCaptured(ordinary))
                    .isEmpty(),
                "ordinary hatch terminal-generated cover is free " + type);
            for (int laser : new int[] { laserInput, laserOutput }) {
                List<ItemStack> costs = HologramBlueprintCovers
                    .materials(HologramBlueprintNbt.encodeCaptured(coverPayload(laser, cover)));
                require(
                    costs.size() == 1 && costs.get(0).stackSize == 1
                        && costs.get(0)
                            .isItemEqual(
                                HologramGtswnPlacementMaterials.reference(HologramGtswnPlacementMaterials.TUBE)),
                    "laser source/target rule costs one real vacuum pipe " + type + " host=" + laser);
            }
            cases.setBoolean(
                type.equals("GTSwnCoverEnergyWireless") ? "laserEnergyTubeOne" : "laserDynamoTubeOne",
                true);
        }
        NBTTagCompound join = new NBTTagCompound();
        join.setString(HologramNetworkBindings.GROUP, "q1");
        join.setString(HologramNetworkBindings.ROLE, "join");
        List<ItemStack> bill = HologramGtswnPlacementMaterials.extraMaterials(GregTechAPI.sBlockMachines, join);
        fact(
            "incorporationAnchorOne",
            bill.size() == 1 && bill.get(0).stackSize == 1
                && bill.get(0)
                    .isItemEqual(HologramGtswnPlacementMaterials.reference(HologramGtswnPlacementMaterials.ANCHOR)),
            "one incorporation costs the real native anchor");
    }

    private static void supplies(EntityPlayerMP actor) throws Exception {
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        for (int kind : new int[] { HologramGtswnPlacementMaterials.ANCHOR, HologramGtswnPlacementMaterials.TUBE }) {
            String label = kind == HologramGtswnPlacementMaterials.ANCHOR ? "anchor" : "tube";
            ItemStack one = HologramGtswnPlacementMaterials.reference(kind);
            HologramGtswnPlacementMaterials.setSupply(actor, kind, 1);
            actor.inventory.mainInventory[1] = one.copy();
            HologramMaterials.Context context = new HologramMaterials.Context(actor, settings);
            require(context.count(one, 3) == 2, "prepaid and actual inventory aggregate " + kind);
            ItemStack tagged = one.copy();
            tagged.setTagCompound(new NBTTagCompound());
            tagged.getTagCompound()
                .setString("fixtureConfiguration", "exact physical object");
            require(
                context.count(tagged, 3) == 0,
                "bare prepaid supply never impersonates a configured physical object " + kind);
            ItemStack three = one.copy();
            three.stackSize = 3;
            HologramMaterials.Transaction shortage = context.begin();
            fact(
                label + "ShortageUntouched",
                !shortage.takeAll(three, false) && HologramGtswnPlacementMaterials.supplyCount(actor, kind) == 1
                    && actor.inventory.mainInventory[1].stackSize == 1,
                "shortage cannot spend or authorize placement " + kind);
            shortage.rollbackExternal();
            HologramMaterials.Transaction paid = context.begin();
            ItemStack two = one.copy();
            two.stackSize = 2;
            require(
                paid.takeAll(two, true) && HologramGtswnPlacementMaterials.supplyCount(actor, kind) == 1,
                "simulation preserves prepaid supply " + kind);
            require(
                paid.takeOne(one, false) && HologramGtswnPlacementMaterials.supplyCount(actor, kind) == 0
                    && actor.inventory.mainInventory[1].stackSize == 1,
                "native prepaid supply is spent before backpack " + kind);
            cases.setBoolean(label + "PrepaidPriority", true);
            require(
                paid.takeOne(one, false) && HologramGtswnPlacementMaterials.supplyCount(actor, kind) == 0
                    && actor.inventory.mainInventory[1] == null,
                "actual reserve consumes prepaid first then actual item " + kind);
            paid.rollbackExternal();
            actor.inventory.mainInventory[1] = one.copy(); // The Service owns its main-inventory snapshot.
            paid.rollbackExternal();
            fact(
                label + "RefundIdempotent",
                context.count(one, 3) == 2,
                "failed placement refund is exact and idempotent " + kind);
            HologramMaterials.Transaction committed = context.begin();
            require(committed.takeOne(one, false), "native prepaid receipt can commit " + kind);
            committed.commit();
            committed.rollbackExternal();
            require(
                HologramGtswnPlacementMaterials.supplyCount(actor, kind) == 0
                    && actor.inventory.mainInventory[1].stackSize == 1,
                "committed prepaid payment never refunds " + kind);
            actor.inventory.mainInventory[1] = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void emptyMeCell(EntityPlayerMP actor) throws Exception {
        int output = -1;
        for (int id = 0; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity host = GregTechAPI.METATILEENTITIES[id];
            if (host != null && host.getClass()
                .getName()
                .equals("gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME")) output = id;
        }
        require(output >= 0, "actual native GT ME output bus registered");
        ItemStack cell = AEApi.instance()
            .definitions()
            .items()
            .cell1k()
            .maybeStack(1)
            .get();
        ItemStack card = AEApi.instance()
            .definitions()
            .materials()
            .cardFuzzy()
            .maybeStack(1)
            .get();
        ICellWorkbenchItem editable = (ICellWorkbenchItem) cell.getItem();
        editable.getConfigInventory(cell)
            .setInventorySlotContents(0, new ItemStack(Items.diamond));
        editable.getUpgradesInventory(cell)
            .setInventorySlotContents(0, card.copy());
        IMEInventoryHandler<IAEItemStack> source = (IMEInventoryHandler<IAEItemStack>) AEApi.instance()
            .registries()
            .cell()
            .getCellInventory(cell, null, StorageChannel.ITEMS);
        require(
            source != null && source.injectItems(
                AEItemStack.create(new ItemStack(Items.diamond, 53)),
                Actionable.MODULATE,
                new PlayerSource(actor, null)) == null,
            "actual native cell accepts source contents");
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("id", "BaseMetaTileEntity");
        payload.setInteger("mID", output);
        NBTTagList inventory = new NBTTagList();
        NBTTagCompound row = cell.writeToNBT(new NBTTagCompound());
        row.setInteger("IntSlot", 0);
        inventory.appendTag(row);
        payload.setTag("Inventory", inventory);
        NBTTagCompound cached = (NBTTagCompound) GregTechAPI.METATILEENTITIES[output].getClass()
            .getMethod("saveStackToNBT", IAEItemStack.class)
            .invoke(GregTechAPI.METATILEENTITIES[output], AEItemStack.create(new ItemStack(Items.emerald, 37)));
        NBTTagList cache = new NBTTagList();
        cache.appendTag(cached);
        payload.setTag("cache", cache);
        payload.setBoolean("cacheMode", true);
        payload.setInteger("myPriority", 27);
        NBTTagCompound before = (NBTTagCompound) payload.copy();
        NBTTagCompound clean = HologramGtMeShadow.project(payload);
        fact(
            "meOutputCacheOmitted",
            !clean.hasKey("cache") && clean.getBoolean("cacheMode") && clean.getInteger("myPriority") == 27,
            "actual native output cache is omitted while settings remain");
        ItemStack empty = ItemStack.loadItemStackFromNBT(
            HologramBlueprintNbt.nativePayload(
                clean.getTagList("Inventory", 10)
                    .getCompoundTagAt(0)));
        IMEInventoryHandler<IAEItemStack> installed = (IMEInventoryHandler<IAEItemStack>) AEApi.instance()
            .registries()
            .cell()
            .getCellInventory(empty, null, StorageChannel.ITEMS);
        fact(
            "componentEmpty",
            installed != null && installed.extractItems(
                AEItemStack.create(new ItemStack(Items.diamond, 1)),
                Actionable.SIMULATE,
                new PlayerSource(actor, null)) == null,
            "native projected component has empty storage");
        fact(
            "componentPartitionPreserved",
            editable.getConfigInventory(empty)
                .getStackInSlot(0)
                .getItem() == Items.diamond,
            "component ghost partition survives");
        HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(payload);
        fact(
            "componentAndCardPriced",
            plan.items.size() == 2 && plan.items.stream()
                .anyMatch(s -> s.isItemEqual(cell) && !s.hasTagCompound())
                && plan.items.stream()
                    .anyMatch(s -> s.isItemEqual(card))
                && plan.fluids.isEmpty(),
            "only bare component and one installed card are payable, without embedded cargo or partition samples");
        fact("sourceUnchanged", payload.equals(before), "source component and inventory serialization stay unchanged");
    }

    /** FML owns AEStack/AEApi initialization; the pure projection regression uses the verified record contract. */
    private static void nativeOutputSerializers() throws Exception {
        for (boolean fluid : new boolean[] { false, true }) {
            String type = "gregtech.common.tileentities.machines.outputme."
                + (fluid ? "MTEHatchOutputME" : "MTEHatchOutputBusME");
            int id = -1;
            for (int candidate = 0; candidate < GregTechAPI.METATILEENTITIES.length; candidate++) {
                IMetaTileEntity host = GregTechAPI.METATILEENTITIES[candidate];
                if (host != null && host.getClass()
                    .getName()
                    .equals(type)) id = candidate;
            }
            require(id >= 0, "actual native ME output serializer owner " + type);
            long amount = fluid ? 7654L : 37L;
            ItemStack item = fluid ? null : new ItemStack(Items.emerald, (int) amount);
            FluidStack liquid = fluid ? FluidRegistry.getFluidStack("water", (int) amount) : null;
            require(!fluid || liquid != null, "actual registered fluid for native output writer");
            Object stack = fluid ? AEFluidStack.create(liquid) : AEItemStack.create(item);
            Class<?> parameter = Class
                .forName(fluid ? "appeng.api.storage.data.IAEFluidStack" : "appeng.api.storage.data.IAEItemStack");
            IMetaTileEntity host = GregTechAPI.METATILEENTITIES[id];
            NBTTagCompound serialized = (NBTTagCompound) host.getClass()
                .getMethod("saveStackToNBT", parameter)
                .invoke(host, stack);
            NBTTagCompound expectedRecord = new NBTTagCompound();
            expectedRecord.setTag(
                fluid ? "fluidStack" : "itemStack",
                fluid ? liquid.writeToNBT(new NBTTagCompound()) : item.writeToNBT(new NBTTagCompound()));
            expectedRecord.setLong("size", amount);
            require(
                expectedRecord.equals(serialized),
                "actual native writer verifies the audited pure fixture contract " + type);
            NBTTagCompound payload = new NBTTagCompound();
            payload.setString("id", "BaseMetaTileEntity");
            payload.setInteger("mID", id);
            NBTTagList cache = new NBTTagList();
            cache.appendTag(serialized);
            payload.setTag("cache", cache);
            payload.setBoolean("cacheMode", true);
            payload.setInteger("myPriority", 71);
            NBTTagCompound before = (NBTTagCompound) payload.copy();
            NBTTagCompound expectedProjection = (NBTTagCompound) payload.copy();
            expectedProjection.removeTag("cache");
            require(
                HologramGtMeShadow.project(payload)
                    .equals(expectedProjection) && payload.equals(before),
                "actual native output record is cleared while all settings and source remain intact " + type);
            HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(payload);
            require(
                plan.items.isEmpty() && plan.fluids.isEmpty(),
                "native pending output cannot enter the material plan " + type);
        }
        cases.setBoolean("nativeOutputSerializers", true);
    }

    private static NBTTagCompound coverPayload(int id, ItemStack cover) {
        NBTTagCompound tile = new NBTTagCompound(), row = new NBTTagCompound();
        tile.setString("id", "BaseMetaTileEntity");
        tile.setInteger("mID", id);
        row.setByte("s", (byte) 2);
        row.setInteger("id", GTUtility.stackToInt(cover));
        row.setTag("d", new NBTTagCompound());
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        tile.setTag("gt.covers", rows);
        return tile;
    }

    private static void require(boolean value, String detail) {
        if (!value) throw new IllegalStateException("GTSWN native material check failed: " + detail);
        checks++;
    }

    private static void fact(String key, boolean value, String detail) {
        require(value, detail);
        cases.setBoolean(key, value);
    }

    private static final class ChatSinkActor extends FakePlayer {

        ChatSinkActor(WorldServer world, GameProfile profile) {
            super(world, profile);
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            // This isolated actor has no Netty client; native material callbacks may report to chat.
        }
    }
}
