package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import appeng.api.config.Actionable;
import appeng.api.storage.data.IAEFluidStack;
import appeng.util.item.AEFluidStack;

/** Resource accounting and the production fluid ledger, using a bounded monitor fixture rather than a live AE grid. */
public final class HologramBlueprintResourcesRegression {

    private static int aeAssertions;
    private static net.minecraft.item.Item controllerMaterialFixture;

    private HologramBlueprintResourcesRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramBlueprintResourcesRegression.class, args)) return;
        run();
        System.out.println("Hologram blueprint resources regressions passed");
    }

    public static void run() {
        try {
            inventoryAndEnergy();
            configurationSamples();
            fluidCostsAndSelectedKeys();
            rejectionBeforePayment();
            bulkPayment();
            exactBulkContext();
            fuzzyShellKeepsInternalResourcesExact();
            sealedRecoveryNeverUsesFuzzyShellPayment();
            clearedControllerPinKeepsRegisteredMaterial();
            sealedBlueprintStateHasPriority();
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            throw new AssertionError("blueprint resources regression", failure);
        }
    }

    /** Called by smoke after real FML/AE initialization; the monitor remains an explicit bounded fixture. */
    public static void runWithAe() {
        aeAssertions = 0;
        System.out.println("[AE-FLUID-TEST] START lifecycle=real-FML monitor=fixture");
        try {
            aeThat(
                appeng.core.AEConfig.instance != null && appeng.api.AEApi.instance() != null,
                "server AE lifecycle is initialized before data stack and fluid ledger checks");
            taggedFluidRejection();
            fluidLedger();
            System.out.println("[AE-FLUID-TEST] FINAL PASS assertCount=" + aeAssertions + " monitor=fixture");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            throw new AssertionError("real-FML AE fluid ledger regression", failure);
        }
    }

    private static void inventoryAndEnergy() throws Exception {
        ItemStack diamond = new ItemStack(Items.diamond, 1, 0);
        NBTTagCompound itemTag = new NBTTagCompound();
        itemTag.setString("fixtureIdentity", "precise tagged contents");
        diamond.setTagCompound(itemTag);
        NBTTagCompound row = diamond.writeToNBT(new NBTTagCompound());
        row.setInteger("IntSlot", 5);
        row.setInteger("Count", 5000);
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(row);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setTag("Inventory", inventory);
        payload.setLong("mStoredEnergy", Long.MAX_VALUE);
        NBTTagCompound original = (NBTTagCompound) payload.copy();
        HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(payload);
        SimpleAssert.eq(1, plan.items.size(), "same item identity aggregates");
        SimpleAssert.eq(5000, plan.items.get(0).stackSize, "GT int Count is not truncated through vanilla byte loader");
        SimpleAssert.eq(
            "precise tagged contents",
            plan.items.get(0)
                .getTagCompound()
                .getString("fixtureIdentity"),
            "inventory payment preserves exact item tags");
        HologramEnergy.Demand cacheDemand = HologramEnergyPayload.prepare(payload, null).demand;
        SimpleAssert.that(
            BigInteger.valueOf(Long.MAX_VALUE)
                .equals(cacheDemand.eu),
            "retained cache EU has an exact separate demand");
        SimpleAssert.that(
            plan.energy.signum() == 0,
            "optional device caches are excluded from required construction resources");
        plan.merge(HologramBlueprintResources.plan(payload));
        SimpleAssert.eq(10000, plan.items.get(0).stackSize, "merged cells sum inventory requirements");
        cacheDemand.merge(HologramEnergyPayload.prepare(payload, null).demand);
        SimpleAssert.that(
            BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(2))
                .equals(cacheDemand.eu),
            "separate aggregate cache demand exceeds long without overflow");
        SimpleAssert
            .that(plan.energy.signum() == 0, "merging physical resource plans cannot silently restore cache billing");
        SimpleAssert.that(payload.equals(original), "cost inspection leaves placement state untouched");
        plan.items.get(0)
            .getTagCompound()
            .setString("fixtureIdentity", "mutated");
        SimpleAssert.that(payload.equals(original), "resource prototypes are detached from payload");
        NBTTagCompound ghostRow = diamond.writeToNBT(new NBTTagCompound());
        ghostRow.setByte("Count", (byte) 0);
        NBTTagList ghost = new NBTTagList();
        ghost.appendTag(ghostRow);
        NBTTagCompound ghostPayload = new NBTTagCompound();
        ghostPayload.setTag("Inventory", ghost);
        SimpleAssert.eq(
            0,
            HologramBlueprintResources.plan(ghostPayload).items.size(),
            "zero-count GT ghost circuits need no item");
    }

    private static void fluidCostsAndSelectedKeys() throws Exception {
        Fluid fluid = fixtureFluid("gtmb_blueprint_resource_fixture");
        fixtureFluid("steam");
        FluidStack first = new FluidStack(fluid, 1250);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("id", "fixture:tile");
        payload.setTag("mFluid", first.writeToNBT(new NBTTagCompound()));
        payload.setLong("mStoredSteam", 2000);
        payload.setLong("mStoredEnergy", 5000);
        HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(payload);
        SimpleAssert.eq(2, plan.fluids.size(), "tank fluid and GT stored steam both have costs");
        long steam = 0;
        for (HologramBlueprintResources.FluidCost cost : plan.fluids) {
            if (cost.stack.getFluid()
                .getName()
                .equals("steam")) steam = cost.amount;
            else {
                SimpleAssert.that(cost.amount == 1250, "tank amount exact");
                SimpleAssert.that(cost.stack.getFluid() == fluid, "fluid payment preserves registry identity");
            }
        }
        SimpleAssert.that(steam == 4000, "GT steam EU requires two mB per stored unit");
        String encoded = HologramBlueprintNbt.encodeCaptured(payload);
        NBTTagCompound selected = HologramBlueprintNbt.forPlacement(
            encoded,
            UUID.randomUUID(),
            "player",
            10,
            20,
            30,
            true,
            false,
            Arrays.asList("mStoredEnergy"));
        HologramBlueprintResources.Plan downgraded = HologramBlueprintResources.plan(selected);
        SimpleAssert.eq(0, downgraded.fluids.size(), "cancelled fluid keys neither restore nor charge contents");
        HologramEnergyPayload.Prepared selectedCache = HologramEnergyPayload.prepare(selected, null);
        SimpleAssert.that(
            BigInteger.valueOf(5000)
                .equals(selectedCache.demand.eu),
            "selected energy has an exact independent cache demand");
        SimpleAssert.that(
            downgraded.energy.signum() == 0 && selectedCache.tilePaid.getLong("mStoredEnergy") == 5000
                && selectedCache.tileEmpty.getLong("mStoredEnergy") == 0,
            "selected cache is excluded from required resources and has explicit paid/empty projections");
        BigInteger[] balance = { BigInteger.valueOf(4999) };
        HologramEnergy.Bank bank = euFixtureBank(balance);
        SimpleAssert.that(
            HologramEnergy.quote(selectedCache.demand, bank)
                .consume() == null && balance[0].equals(BigInteger.valueOf(4999)),
            "selected underfunded cache cannot restore or deduct a partial EU balance");
        balance[0] = BigInteger.valueOf(5000);
        HologramEnergy.Receipt paid = HologramEnergy.quote(selectedCache.demand, bank)
            .consume();
        SimpleAssert.that(
            paid != null && balance[0].signum() == 0,
            "selected cache must consume its full independent EU demand before paid restoration");
        paid.refund();
        SimpleAssert.that(
            balance[0].equals(BigInteger.valueOf(5000)),
            "selected cache placement cancellation refunds its independent receipt");
        HologramEnergy.quote(selectedCache.demand, bank)
            .consume()
            .commit();
        SimpleAssert.that(balance[0].signum() == 0, "committed selected cache retains exact independent payment");
        NBTTagCompound fluidOnly = HologramBlueprintNbt
            .forPlacement(encoded, UUID.randomUUID(), "player", 10, 20, 30, true, false, Arrays.asList("mFluid"));
        HologramEnergyPayload.Prepared omittedCache = HologramEnergyPayload.prepare(fluidOnly, null);
        HologramBlueprintResources.Plan fluidOnlyPlan = HologramBlueprintResources.plan(fluidOnly);
        SimpleAssert.that(
            omittedCache.demand.empty() && !omittedCache.tilePaid.hasKey("mStoredEnergy")
                && fluidOnlyPlan.fluids.size() == 1
                && fluidOnlyPlan.fluids.get(0).amount == 1250,
            "deselecting the energy NBT root removes restoration/payment while selected fluid keeps its exact physical cost");
        HologramEnergy.quote(omittedCache.demand, bank)
            .consume()
            .commit();
        SimpleAssert.that(balance[0].signum() == 0, "deselected cache needs no EU even after the account is depleted");
        SimpleAssert.that(
            HologramBlueprintNbt.decode(encoded)
                .hasKey("mFluid"),
            "temporary downgrade preserves captured blueprint");
    }

    /** Explicit unit-test bank. Actual UUID/network Context payment is checked by EnergyNetworkSmoke. */
    private static HologramEnergy.Bank euFixtureBank(BigInteger[] balance) {
        return new HologramEnergy.Bank() {

            public BigInteger euAvailable() {
                return balance[0];
            }

            public boolean takeEu(BigInteger amount) {
                if (balance[0].compareTo(amount) < 0) return false;
                balance[0] = balance[0].subtract(amount);
                return true;
            }

            public void refundEu(BigInteger amount) {
                balance[0] = balance[0].add(amount);
            }

            public boolean aeAvailable() {
                return false;
            }

            public double takeAe(double amount, boolean simulate) {
                throw new AssertionError("EU-only fixture cannot withdraw AE");
            }

            public void refundAe(double amount) {
                throw new AssertionError("EU-only fixture cannot refund AE");
            }

            public double rfToEuPerHundred() {
                return 100;
            }

            public double aeToEu() {
                return 0.5;
            }
        };
    }

    private static void taggedFluidRejection() throws Exception {
        FluidStack tagged = new FluidStack(fixtureFluid("gtmb_blueprint_resource_fixture"), 1250);
        tagged.tag = new NBTTagCompound();
        tagged.tag.setString("grade", "a");
        NBTTagCompound taggedPayload = new NBTTagCompound();
        taggedPayload.setTag("mFluid", tagged.writeToNBT(new NBTTagCompound()));
        aeThat(!HologramMaterials.supportsFluidNbt(tagged), "resolved AE2 adapter does not preserve fluid tags");
        try {
            HologramBlueprintResources.plan(taggedPayload);
            throw new AssertionError("Unsupported tagged fluid must reject before placement");
        } catch (IOException expected) {
            aeThat(
                expected.getMessage()
                    .contains("mFluid"),
                "tagged fluid rejection identifies cancellable root key");
        }
    }

    private static void configurationSamples() throws Exception {
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setTag("ghostStack", new ItemStack(Items.diamond, 64).writeToNBT(new NBTTagCompound()));
        recipe.setTag(
            "fluid",
            new FluidStack(fixtureFluid("gtmb_blueprint_resource_fixture"), 1000).writeToNBT(new NBTTagCompound()));
        NBTTagCompound payload = new NBTTagCompound();
        payload.setTag("recipe", recipe);
        payload.setTag("filter", recipe.copy());
        HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(payload);
        SimpleAssert
            .eq(0, plan.items.size(), "recipe/filter ghost items remain configuration without inventory payment");
        SimpleAssert.eq(0, plan.fluids.size(), "recipe/filter fluid samples remain configuration without tank payment");
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(new ItemStack(Items.diamond, 7).writeToNBT(new NBTTagCompound()));
        NBTTagCompound coverData = new NBTTagCompound();
        coverData.setTag("Inventory", inventory);
        payload.setTag("coverData", coverData);
        SimpleAssert.eq(
            7,
            HologramBlueprintResources.plan(payload).items.get(0).stackSize,
            "actual nested cover inventory still pays its entire stored quantity");
    }

    private static void rejectionBeforePayment() throws Exception {
        NBTTagCompound negative = new NBTTagCompound();
        negative.setLong("mStoredEnergy", -1);
        HologramEnergyPayload.Prepared invalidCache = HologramEnergyPayload.prepare(negative, null);
        SimpleAssert.that(
            invalidCache.demand.empty() && invalidCache.tilePaid.getLong("mStoredEnergy") == 0
                && !invalidCache.warnings.isEmpty(),
            "invalid cache cannot create an energy demand or restore unpaid negative data");
        SimpleAssert.that(
            HologramBlueprintResources.plan(negative).energy.signum() == 0,
            "invalid optional cache is omitted independently of required physical resources");
        NBTTagCompound unknown = new NBTTagCompound();
        NBTTagCompound tank = new NBTTagCompound();
        tank.setLong("capacityUsed", 500);
        unknown.setTag("customTank", tank);
        reject(unknown, "customTank");
        NBTTagCompound malformed = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        list.appendTag(new NBTTagString("opaque serialized item"));
        malformed.setTag("Items", list);
        reject(malformed, "Items");
        NBTTagCompound missing = new ItemStack(Items.diamond).writeToNBT(new NBTTagCompound());
        missing.setBoolean("gtitBlueprintMissingItem", true);
        NBTTagCompound missingPayload = new NBTTagCompound();
        NBTTagList missingList = new NBTTagList();
        missingList.appendTag(missing);
        missingPayload.setTag("Items", missingList);
        reject(missingPayload, "Items");
    }

    private static void bulkPayment() throws Exception {
        HologramBlueprintResources.Plan plan = new HologramBlueprintResources.Plan();
        plan.addItems(Arrays.asList(new ItemStack(Items.diamond, 10000)));
        plan.energy = BigInteger.valueOf(5000);
        final int[] mutations = { 0 }, taken = { 0 };
        HologramBlueprintResources.Payment insufficient = new HologramBlueprintResources.Payment() {

            @Override
            public boolean item(ItemStack stack, boolean simulate) {
                if (!simulate) mutations[0]++;
                return true;
            }

            @Override
            public boolean fluid(FluidStack stack, long amount, boolean simulate) {
                return true;
            }

            @Override
            public boolean energy(BigInteger amount, boolean simulate) {
                return false;
            }
        };
        SimpleAssert.that(!plan.reserve(insufficient), "insufficient EU refuses payment");
        SimpleAssert.eq(0, mutations[0], "all resources simulate before any resource is removed");
        SimpleAssert.that(plan.reserve(new HologramBlueprintResources.Payment() {

            @Override
            public boolean item(ItemStack stack, boolean simulate) {
                if (!simulate) {
                    mutations[0]++;
                    taken[0] += stack.stackSize;
                }
                return true;
            }

            @Override
            public boolean fluid(FluidStack stack, long amount, boolean simulate) {
                return true;
            }

            @Override
            public boolean energy(BigInteger amount, boolean simulate) {
                return BigInteger.valueOf(5000)
                    .equals(amount);
            }
        }), "paid requirements reserve successfully");
        SimpleAssert.eq(1, mutations[0], "large slots use one bulk extraction rather than ten thousand takeOne calls");
        SimpleAssert.eq(10000, taken[0], "large slots consume the entire requirement");
        ItemStack hugeRefund = new ItemStack(Items.diamond, 200_000_000);
        NBTTagCompound receipt = HologramMaterials.itemReceipt(hugeRefund);
        SimpleAssert.eq(
            200_000_000,
            HologramMaterials.recoveryItem(receipt).stackSize,
            "large external item refunds use one exact int-count durable receipt");
        SimpleAssert.eq(
            7,
            HologramMaterials.recoveryItem(new ItemStack(Items.diamond, 7).writeToNBT(new NBTTagCompound())).stackSize,
            "legacy byte-count recovery receipts remain readable");
    }

    private static void exactBulkContext() throws Exception {
        java.lang.reflect.Method playerFactory = HologramRegressionTest.class.getDeclaredMethod("materialPlayer");
        playerFactory.setAccessible(true);
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) playerFactory
            .invoke(null);
        player.inventory.mainInventory[1].getTagCompound()
            .setInteger("materials", 8192);
        java.lang.reflect.Method contextFactory = HologramRegressionTest.class
            .getDeclaredMethod("materialContext", net.minecraft.entity.player.EntityPlayerMP.class);
        contextFactory.setAccessible(true);
        HologramMaterials.Context context = (HologramMaterials.Context) contextFactory.invoke(null, player);
        SimpleAssert.that(
            context.take(stack -> true, false, 5000)
                .isEmpty(),
            "anonymous predicate extraction retains the original construction safety budget");
        HologramMaterials.Transaction transaction = context.begin();
        SimpleAssert.that(
            transaction.takeAll(new ItemStack(net.minecraft.init.Blocks.stone, 5000), false),
            "explicit exact resource identity permits one large transaction extraction");
        transaction.commit();
        SimpleAssert.eq(
            3192,
            player.inventory.mainInventory[1].getTagCompound()
                .getInteger("materials"),
            "large exact payment physically removes all five thousand items from container");
    }

    private static void fuzzyShellKeepsInternalResourcesExact() throws Exception {
        java.lang.reflect.Method playerFactory = HologramRegressionTest.class.getDeclaredMethod("materialPlayer");
        playerFactory.setAccessible(true);
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) playerFactory
            .invoke(null);
        player.inventory.mainInventory[1] = null;
        ItemStack sample = new ItemStack(net.minecraft.init.Blocks.wool, 1, 3);
        sample.setTagCompound(new NBTTagCompound());
        sample.getTagCompound()
            .setString("network", "saved");
        ItemStack material = sample.copy();
        material.stackSize = 2;
        material.getTagCompound()
            .setString("network", "inventory");
        player.inventory.mainInventory[2] = material;
        player.inventory.mainInventory[3] = new ItemStack(net.minecraft.init.Blocks.wool, 5, 4);
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        HologramMaterials.Context context = new HologramMaterials.Context(player, settings);
        SimpleAssert.eq(0, context.count(sample, 64), "ordinary resource count requires exact tags");
        SimpleAssert.eq(2, context.countFuzzy(sample, 64), "fuzzy shell count ignores tags and retains exact damage");
        SimpleAssert.eq(2, material.stackSize, "shell availability simulation preserves inventory");
        HologramMaterials.Transaction transaction = context.begin();
        SimpleAssert
            .that(!transaction.takeAll(sample, true), "internal resource payment stays exact in shell transaction");
        SimpleAssert.that(transaction.takeOneFuzzy(sample, true), "shell simulation accepts available tag variant");
        SimpleAssert.eq(2, material.stackSize, "shell simulation consumes nothing");
        SimpleAssert.that(transaction.takeOneFuzzy(sample, false), "shell payment consumes actual matching variant");
        SimpleAssert.eq(1, material.stackSize, "shell payment consumes exactly one item");
        SimpleAssert.eq(
            "inventory",
            material.getTagCompound()
                .getString("network"),
            "remaining source tag is preserved");
        SimpleAssert
            .that(!transaction.takeAll(sample, false), "fuzzy shell choice never relaxes later internal resource tags");
        SimpleAssert
            .eq(5, player.inventory.mainInventory[3].stackSize, "different damage never supplies shell material");
        transaction.commit();
    }

    private static void sealedRecoveryNeverUsesFuzzyShellPayment() throws Exception {
        java.lang.reflect.Method playerFactory = HologramRegressionTest.class.getDeclaredMethod("materialPlayer");
        playerFactory.setAccessible(true);
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) playerFactory
            .invoke(null);
        player.inventory.mainInventory[1] = null;
        ItemStack plain = new ItemStack(net.minecraft.init.Blocks.wool, 1, 3);
        ItemStack sealed = plain.copy();
        NBTTagCompound tile = new NBTTagCompound();
        tile.setInteger("mID", 17);
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(new ItemStack(Items.diamond, 7).writeToNBT(new NBTTagCompound()));
        tile.setTag("Inventory", inventory);
        tile.setLong("mStoredEnergy", 4000);
        sealed.setTagCompound(new NBTTagCompound());
        sealed.getTagCompound()
            .setTag(HologramRecovery.TAG, HologramRecovery.sealNBT(tile, "minecraft:wool", 3, "minecraft:wool", 3, 17));
        ItemStack differentSeal = sealed.copy();
        differentSeal.getTagCompound()
            .getCompoundTag(HologramRecovery.TAG)
            .getCompoundTag("tile")
            .setLong("mStoredEnergy", 8000);
        SimpleAssert.that(
            !HologramMaterials.shellPredicate(sealed)
                .test(plain),
            "same item/meta ordinary shell cannot pay for a sealed device inventory and energy");
        SimpleAssert.that(
            !HologramMaterials.shellPredicate(sealed)
                .test(differentSeal),
            "another sealed inventory state is not interchangeable with the requested seal");
        SimpleAssert.that(
            !HologramMaterials.shellPredicate(plain)
                .test(sealed),
            "ordinary fuzzy shell extraction never destroys a sealed device's paid contents");

        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        HologramMaterials.Context context = new HologramMaterials.Context(player, settings);
        player.inventory.mainInventory[2] = new ItemStack(net.minecraft.init.Blocks.wool, 2, 3);
        SimpleAssert.eq(0, context.countFuzzy(sealed, 64), "sealed availability uses exact NBT even via the fuzzy API");
        HologramMaterials.Transaction denied = context.begin();
        SimpleAssert
            .that(!denied.takeOneFuzzy(sealed, false), "fuzzy payment entry point rejects unsealed substitutes");
        denied.rollbackExternal();
        SimpleAssert
            .eq(2, player.inventory.mainInventory[2].stackSize, "rejected sealed payment consumes no ordinary shell");
        player.inventory.mainInventory[3] = differentSeal;
        SimpleAssert.eq(0, context.countFuzzy(sealed, 64), "a different real seal also cannot satisfy the target");
        player.inventory.mainInventory[4] = sealed.copy();
        SimpleAssert.eq(1, context.countFuzzy(sealed, 64), "the identical real sealed item can pay exactly");
        SimpleAssert.eq(2, context.countFuzzy(plain, 64), "sealed variants are excluded from ordinary fuzzy counts");

        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Class<?> type = Class.forName(HologramService.class.getName() + "$Session");
        Object session = unsafe.allocateInstance(type);
        for (Object[] entry : new Object[][] { { "player", player }, { "materialContext", context },
            { "virtual", true } }) {
            java.lang.reflect.Field field = type.getDeclaredField((String) entry[0]);
            field.setAccessible(true);
            field.set(session, entry[1]);
        }
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.deidentificationLevel = 1;
        java.lang.reflect.Field presetField = type.getDeclaredField("virtualPreset");
        presetField.setAccessible(true);
        presetField.set(session, preset);
        java.lang.reflect.Method fuzzy = HologramService.class
            .getDeclaredMethod("fuzzyBlueprintShells", type, ItemStack.class);
        fuzzy.setAccessible(true);
        SimpleAssert.that(
            !(Boolean) fuzzy.invoke(null, session, sealed) && (Boolean) fuzzy.invoke(null, session, plain),
            "level one construction still applies exact sealed payment while allowing ordinary fuzzy shells");
        java.lang.reflect.Method copyInventory = HologramService.class
            .getDeclaredMethod("copyInventory", net.minecraft.entity.player.EntityPlayerMP.class);
        copyInventory.setAccessible(true);
        ItemStack[] before = (ItemStack[]) copyInventory.invoke(null, player);
        HologramMaterials.Transaction transaction = context.begin();
        SimpleAssert.that(
            transaction.takeOneFuzzy(sealed, false),
            "real identical seal is physically reserved via exact fallback");
        SimpleAssert.eq(0, context.count(sealed, 64), "reservation removes the one paid sealed item");
        java.lang.reflect.Method rollback = HologramService.class
            .getDeclaredMethod("rollbackMaterials", type, HologramMaterials.Transaction.class, ItemStack[].class);
        rollback.setAccessible(true);
        rollback.invoke(null, session, transaction, before);
        rollback.invoke(null, session, transaction, before);
        SimpleAssert.eq(
            1,
            context.count(sealed, 64),
            "service rollback restores exactly one original seal without duplicating its inventory");
        SimpleAssert.eq(2, context.countFuzzy(plain, 64), "sealed refund leaves the two ordinary shells unchanged");
        SimpleAssert.that(
            ItemStack.areItemStackTagsEqual(sealed, player.inventory.mainInventory[4]),
            "refunded sealed inventory and energy NBT remain byte-for-byte exact");
    }

    private static void clearedControllerPinKeepsRegisteredMaterial() throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        int id = 247;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[id];
        try {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = (gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor) unsafe
                .allocateInstance(gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor.class);
            String registry = "gtmb:cleared_controller_material_fixture";
            net.minecraft.item.Item material = (net.minecraft.item.Item) net.minecraft.item.Item.itemRegistry
                .getObject(registry);
            if (material == null) {
                int free = 4095;
                while (free > 0 && (net.minecraft.item.Item.getItemById(free) != null
                    || net.minecraft.block.Block.getBlockById(free) != null
                        && net.minecraft.block.Block.getBlockById(free) != net.minecraft.init.Blocks.air))
                    free--;
                if (free == 0) throw new AssertionError("No free isolated controller block/item registry slot");
                net.minecraft.block.Block block = new net.minecraft.block.Block(
                    net.minecraft.block.material.Material.rock) {};
                net.minecraft.block.Block.blockRegistry.addObject(free, registry, block);
                material = new gregtech.common.blocks.ItemMachines(block);
                net.minecraft.item.Item.itemRegistry.addObject(free, registry, material);
            }
            controllerMaterialFixture = material;
            String registeredMaterial = String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(material));
            SimpleAssert.that(
                net.minecraft.item.Item.itemRegistry.getObject(registeredMaterial) == material,
                "isolated controller material uses the actual Forge registry name");
            Class<?> type = Class.forName(HologramService.class.getName() + "$Session");
            Object session = unsafe.allocateInstance(type);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.controllerId = id;
            preset.sourceClass = gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor.class.getName();
            for (Object[] entry : new Object[][] { { "virtual", true }, { "virtualPreset", preset },
                { "pins", new java.util.HashMap<Integer, ItemStack>() },
                { "coordinatePins", new java.util.HashMap<String, ItemStack>() } }) {
                java.lang.reflect.Field field = type.getDeclaredField((String) entry[0]);
                field.setAccessible(true);
                field.set(session, entry[1]);
            }
            HologramCapture.Cell controller = new HologramCapture.Cell(null, 0, 64, 0);
            controller.userPosition = true;
            controller.role = "templateController";
            controller.blueprintItem = registeredMaterial;
            controller.blueprintDamage = id;
            controller.meta = 0;
            java.lang.reflect.Method draft = HologramService.class
                .getDeclaredMethod("draftTarget", type, HologramCapture.Cell.class, int.class);
            draft.setAccessible(true);
            java.lang.reflect.Method explicit = HologramService.class
                .getDeclaredMethod("explicitTarget", type, HologramCapture.Cell.class, int.class);
            explicit.setAccessible(true);
            java.lang.reflect.Method warning = HologramService.class
                .getDeclaredMethod("blueprintSourceWarning", HologramPresets.Preset.class, String.class);
            warning.setAccessible(true);
            SimpleAssert.eq(
                "",
                warning.invoke(null, preset, controller.structureId),
                "controller fixture has a complete registered source-class and ID descriptor");
            ItemStack restored = (ItemStack) draft.invoke(null, session, controller, 0);
            SimpleAssert.that(
                restored != null && restored.getItem() == material && restored.getItemDamage() == id,
                "cleared controller pin restores its registered blueprint damage, never world metadata zero");
            SimpleAssert.that(
                (Boolean) explicit.invoke(null, session, controller, 0),
                "valid default controller is a construction target and cannot silently become satisfied without a pin");
            controller.sourceWarning = "source identity mismatch";
            SimpleAssert.that(
                draft.invoke(null, session, controller, 0) == null
                    && !(Boolean) explicit.invoke(null, session, controller, 0),
                "source warning still rejects implicit controller restoration");
            controller.sourceWarning = "";
            controller.blueprintDamage = id + 1;
            SimpleAssert.that(
                draft.invoke(null, session, controller, 0) == null,
                "default restoration cannot substitute another registered or unregistered controller ID");
            controller.blueprintDamage = id;
            controller.role = "prefab";
            SimpleAssert.that(
                draft.invoke(null, session, controller, 0) == null,
                "ordinary unspecified new positions retain their existing keep-world behavior");
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = previous;
        }
    }

    private static void sealedBlueprintStateHasPriority() throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        int id = 247;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[id];
        try {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = (gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor) unsafe
                .allocateInstance(gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor.class);
            net.minecraft.item.Item item = controllerMaterialFixture;
            SimpleAssert
                .that(item != null, "sealed fixture reuses the actual isolated registered ItemMachines instance");
            String fixtureItemName = String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(item));
            net.minecraft.block.Block fixtureBlock = net.minecraft.block.Block.getBlockFromItem(item);
            String fixtureBlockName = String
                .valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(fixtureBlock));
            NBTTagCompound paid = new NBTTagCompound();
            paid.setString("id", "fixture_sealed_machine");
            paid.setInteger("mID", id);
            paid.setString("mOwnerUuid", new UUID(1, 2).toString());
            NBTTagList inventory = new NBTTagList();
            inventory.appendTag(new ItemStack(Items.diamond, 7).writeToNBT(new NBTTagCompound()));
            paid.setTag("Inventory", inventory);
            paid.setLong("mStoredEnergy", 4000);
            NBTTagCompound fluid = new NBTTagCompound();
            fluid.setString("FluidName", "water");
            fluid.setInteger("Amount", 2400);
            paid.setTag("mFluid", fluid);
            paid.setTag("gt.covers", new NBTTagList());
            NBTTagCompound rebound = HologramService.sealedActorTile(paid, new UUID(9, 8), "builder", 1, 64, 2);
            SimpleAssert.that(
                inventory.equals(rebound.getTag("Inventory")) && fluid.equals(rebound.getTag("mFluid"))
                    && rebound.getLong("mStoredEnergy") == 4000
                    && paid.getTag("gt.covers")
                        .equals(rebound.getTag("gt.covers")),
                "actor binding preserves all paid sealed inventory, fluid, cache and covers");
            SimpleAssert.eq(
                new UUID(9, 8).toString(),
                rebound.getString("mOwnerUuid"),
                "seal state is bound to the actual placing actor");

            ItemStack sealed = new ItemStack(item, 1, id);
            sealed.setTagCompound(new NBTTagCompound());
            sealed.getTagCompound()
                .setTag(
                    HologramRecovery.TAG,
                    HologramRecovery.sealNBT(paid, fixtureBlockName, 0, fixtureItemName, id, id));
            SimpleAssert.that(
                HologramRecovery.validItem(sealed, fixtureBlock),
                "sealed fixture envelope matches its actual registered block/item identities");
            NBTTagCompound template = new NBTTagCompound();
            NBTTagList templateInventory = new NBTTagList();
            templateInventory.appendTag(new ItemStack(Items.emerald, 19).writeToNBT(new NBTTagCompound()));
            template.setTag("Inventory", templateInventory);
            template.setLong("mStoredEnergy", 98765);
            HologramCapture.Cell cell = new HologramCapture.Cell(null, 1, 64, 2);
            cell.tileNbt = HologramBlueprintNbt.encodeCaptured(template);
            cell.block = fixtureBlock;
            HologramCapture capture = new HologramCapture(null, new ItemStack(Items.feather));
            capture.cells.add(cell);
            java.util.Map<HologramCapture.Cell, Integer> indices = new java.util.IdentityHashMap<>();
            indices.put(cell, 0);
            java.lang.reflect.Method playerFactory = HologramRegressionTest.class.getDeclaredMethod("materialPlayer");
            playerFactory.setAccessible(true);
            net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) playerFactory
                .invoke(null);
            for (java.lang.reflect.Field field : net.minecraft.entity.player.EntityPlayer.class.getDeclaredFields())
                if (field.getType() == com.mojang.authlib.GameProfile.class) {
                    field.setAccessible(true);
                    field.set(player, new com.mojang.authlib.GameProfile(new UUID(9, 8), "builder"));
                }
            Class<?> type = Class.forName(HologramService.class.getName() + "$Session");
            Object session = unsafe.allocateInstance(type);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            for (Object[] entry : new Object[][] { { "player", player }, { "virtual", true },
                { "virtualPreset", preset }, { "capture", capture }, { "indexedCapture", capture },
                { "cellIndices", indices }, { "planTargets", java.util.Collections.singletonList(sealed) },
                { "networkBindings", new HologramNetworkBindings.State() } }) {
                java.lang.reflect.Field field = type.getDeclaredField((String) entry[0]);
                field.setAccessible(true);
                field.set(session, entry[1]);
            }
            java.lang.reflect.Method resources = HologramService.class
                .getDeclaredMethod("resourcePlan", type, HologramCapture.Cell.class);
            java.lang.reflect.Method cache = HologramService.class
                .getDeclaredMethod("cachePlan", type, HologramCapture.Cell.class, int.class);
            java.lang.reflect.Method covers = HologramService.class
                .getDeclaredMethod("coverCosts", type, HologramCapture.Cell.class);
            resources.setAccessible(true);
            cache.setAccessible(true);
            covers.setAccessible(true);
            HologramBlueprintResources.Plan bill = (HologramBlueprintResources.Plan) resources
                .invoke(null, session, cell);
            SimpleAssert.that(
                bill.items.isEmpty() && bill.fluids.isEmpty() && bill.energy.signum() == 0,
                "a paid complete seal never charges its contents or a different template inventory again");
            SimpleAssert.that(
                ((HologramEnergyPayload.Prepared) cache.invoke(null, session, cell, 0)).demand.empty()
                    && ((java.util.List<?>) covers.invoke(null, session, cell)).isEmpty(),
                "sealed cache and covers have no additional blueprint restoration charge");
            paid.setInteger("anchorDim", 0);
            sealed.getTagCompound()
                .getCompoundTag(HologramRecovery.TAG)
                .setTag("tile", paid);
            try {
                resources.invoke(null, session, cell);
                throw new AssertionError("unmergeable external seal state must fail before payment");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                SimpleAssert.that(
                    expected.getCause() instanceof IOException,
                    "unmergeable seal is rejected by pure preflight rather than losing paid resources");
            }
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = previous;
        }
    }

    private static void fluidLedger() {
        FluidStack fluid = new FluidStack(fixtureFluid("gtmb_blueprint_resource_fixture"), 1);
        FluidFixture monitor = new FluidFixture(fluid, 10000);
        List<IAEFluidStack> ledger = new ArrayList<>();
        monitor.live = false;
        aeThat(
            !HologramMaterials.reserveFluid(monitor, fluid, 3000, ledger::add),
            "removed or unauthorized terminal cannot extract");
        aeEq(0, monitor.extractions, "lost access never reaches monitor extraction");
        monitor.live = true;
        monitor.extractionLimit = 2000;
        aeThat(
            !HologramMaterials.reserveFluid(monitor, fluid, 3000, ledger::add),
            "partial real removal refuses placement");
        aeThat(
            ledger.get(0)
                .getStackSize() == 2000,
            "ledger records actual partial extraction");
        aeThat(monitor.stored == 8000, "fixture storage removes only actual returned quantity");
        IAEFluidStack left = ledger.get(0)
            .copy();
        monitor.powerLimit = 700;
        monitor.insertionLimit = 500;
        monitor.throwCharge = true;
        try {
            HologramMaterials.refundFluid(monitor, left);
            throw new AssertionError("fixture energy charge must fail after insertion");
        } catch (IllegalStateException expected) {
            aeThat(left.getStackSize() == 1500, "inserted fluid is removed from refund before charging failure");
        }
        NBTTagCompound receipt = HologramMaterials.fluidReceipt(left);
        aeThat(receipt.getLong("gtmbRecoveryAmount") == 1500, "persistent receipt retains actual unpaid remainder");
        aeThat(
            fluid.isFluidEqual(FluidStack.loadFluidStackFromNBT(receipt)),
            "durable refund keeps exact fluid registry identity");
        monitor.throwCharge = false;
        monitor.powerLimit = Long.MAX_VALUE;
        monitor.insertionLimit = Long.MAX_VALUE;
        HologramMaterials.refundFluid(monitor, left);
        aeThat(
            left.getStackSize() == 0 && monitor.stored == 10000,
            "retry returns remaining fluid once, without duplication");
        monitor.extractionLimit = Long.MAX_VALUE;
        monitor.throwCharge = true;
        ledger.clear();
        try {
            HologramMaterials.reserveFluid(monitor, fluid, 3000, ledger::add);
            throw new AssertionError("fixture charging failure should propagate to service rollback");
        } catch (IllegalStateException expected) {
            aeThat(
                ledger.get(0)
                    .getStackSize() == 3000,
                "charge failure cannot lose extraction ledger");
        }
        IAEFluidStack huge = AEFluidStack.create(fluid);
        huge.setStackSize(5_000_000_000L);
        aeThat(
            HologramMaterials.fluidReceipt(huge)
                .getLong("gtmbRecoveryAmount") == 5_000_000_000L,
            "durable AE fluid quantity is long, beyond Forge FluidStack int size");
    }

    private static final class FluidFixture implements HologramMaterials.FluidAccess {

        final FluidStack identity;
        long stored;
        long powerLimit = Long.MAX_VALUE, extractionLimit = Long.MAX_VALUE, insertionLimit = Long.MAX_VALUE;
        boolean live = true, throwCharge;
        int extractions;

        FluidFixture(FluidStack identity, long stored) {
            this.identity = identity.copy();
            this.stored = stored;
        }

        @Override
        public boolean available() {
            return live;
        }

        @Override
        public long poweredCount(IAEFluidStack stack) {
            return Math.min(powerLimit, stack.getStackSize());
        }

        @Override
        public IAEFluidStack extract(IAEFluidStack stack, Actionable mode) {
            extractions++;
            aeThat(identity.isFluidEqual(stack.getFluidStack()), "AE request keeps exact fluid identity");
            long amount = Math.min(stored, stack.getStackSize());
            if (mode == Actionable.MODULATE) {
                amount = Math.min(amount, extractionLimit);
                stored -= amount;
            }
            IAEFluidStack result = stack.copy();
            result.setStackSize(amount);
            return result;
        }

        @Override
        public IAEFluidStack inject(IAEFluidStack stack) {
            long accepted = Math.min(insertionLimit, stack.getStackSize());
            stored += accepted;
            IAEFluidStack left = stack.copy();
            left.setStackSize(stack.getStackSize() - accepted);
            return left.getStackSize() == 0 ? null : left;
        }

        @Override
        public void charge(long amount, int unit) {
            if (throwCharge) throw new IllegalStateException("fixture charge failure");
        }
    }

    private static void aeThat(boolean condition, String label) {
        aeAssertions++;
        SimpleAssert.that(condition, label);
    }

    private static void aeEq(Object expected, Object actual, String label) {
        aeAssertions++;
        SimpleAssert.eq(expected, actual, label);
    }

    private static Fluid fixtureFluid(String name) {
        Fluid result = FluidRegistry.getFluid(name);
        if (result == null) {
            result = new Fluid(name);
            FluidRegistry.registerFluid(result);
        }
        return result;
    }

    private static void reject(NBTTagCompound payload, String key) throws Exception {
        try {
            HologramBlueprintResources.plan(payload);
            throw new AssertionError("Unsupported resources must reject before placement");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains(key),
                "rejection identifies cancellable root key");
        }
    }
}
