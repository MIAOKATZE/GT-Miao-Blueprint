package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Pure projection: native GT input Slot writers plus audited output NBT fixtures; no AE/FML initialization. */
public final class HologramGtMeShadowRegression {

    private static final String FLUID = "gregtech.common.tileentities.machines.MTEHatchInputME";
    private static final String ITEM = "gregtech.common.tileentities.machines.MTEHatchInputBusME";
    private static final String OUTPUT_ITEM = "gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME";
    private static final String OUTPUT_FLUID = "gregtech.common.tileentities.machines.outputme.MTEHatchOutputME";
    private static final int FLUID_ID = 31001, ITEM_ID = 31002, OUTPUT_ITEM_ID = 31003, OUTPUT_FLUID_ID = 31004;

    private HologramGtMeShadowRegression() {}

    /** Real FML only: invoke GT's actual PatternSlot writer, preserving encoded recipes and pricing buffered assets. */
    static void craftingWithAe() throws Exception {
        int id = 31005;
        IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[id];
        try {
            Class<?> owner = Class.forName("gregtech.common.tileentities.machines.MTEHatchCraftingInputME");
            GregTechAPI.METATILEENTITIES[id] = (IMetaTileEntity) allocate(owner);
            ItemStack pattern = appeng.api.AEApi.instance()
                .definitions()
                .items()
                .encodedPattern()
                .maybeStack(1)
                .get();
            NBTTagCompound recipe = new NBTTagCompound();
            NBTTagList inputs = new NBTTagList(), outputs = new NBTTagList();
            inputs.appendTag(new ItemStack(Items.iron_ingot, 2).writeToNBT(new NBTTagCompound()));
            outputs.appendTag(new ItemStack(Items.gold_ingot).writeToNBT(new NBTTagCompound()));
            recipe.setTag("in", inputs);
            recipe.setTag("out", outputs);
            recipe.setBoolean("crafting", false);
            pattern.setTagCompound(recipe);
            Object nativeSlot = allocate(Class.forName(owner.getName() + "$PatternSlot"));
            for (String fieldName : new String[] { "pattern", "itemInventory", "fluidInventory" }) {
                Field field = nativeSlot.getClass()
                    .getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = fieldName.equals("pattern") ? pattern
                    : fieldName.equals("itemInventory")
                        ? new java.util.ArrayList<>(java.util.Arrays.asList(new ItemStack(Items.diamond, 5000)))
                        : new java.util.ArrayList<>(java.util.Arrays.asList(new FluidStack(liquid(), 2345)));
                field.set(nativeSlot, value);
            }
            NBTTagCompound body = (NBTTagCompound) nativeSlot.getClass()
                .getMethod("writeToNBT", NBTTagCompound.class)
                .invoke(nativeSlot, new NBTTagCompound());
            NBTTagCompound row = new NBTTagCompound(), root = new NBTTagCompound();
            row.setInteger("patternSlot", 0);
            row.setTag("patternSlotNBT", body);
            NBTTagList internal = new NBTTagList(), inventory = new NBTTagList();
            internal.appendTag(row);
            NBTTagCompound physicalPattern = pattern.writeToNBT(new NBTTagCompound());
            physicalPattern.setInteger("IntSlot", 0);
            inventory.appendTag(physicalPattern);
            root.setString("id", "BaseMetaTileEntity");
            root.setInteger("mID", id);
            root.setTag("Inventory", inventory);
            root.setTag("internalInventory", internal);
            NBTTagCompound original = (NBTTagCompound) root.copy();
            HologramBlueprintResources.Plan bill = HologramBlueprintResources.plan(root);
            SimpleAssert
                .eq(2, bill.items.size(), "physical pattern mirror is charged once alongside actual buffered items");
            int blanks = 0, diamonds = 0;
            for (ItemStack item : bill.items) {
                if (appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .blankPattern()
                    .isSameAs(item)) blanks += item.stackSize;
                if (item.getItem() == Items.diamond) diamonds += item.stackSize;
            }
            SimpleAssert.eq(1, blanks, "encoded recipe consumes one corresponding blank pattern");
            SimpleAssert.eq(5000, diamonds, "native GT integer Count is paid without byte truncation");
            SimpleAssert.eq(2345L, bill.fluids.get(0).amount, "physical buffered fluid remains payable");
            SimpleAssert
                .that(root.equals(original), "costing leaves complete native recipe and buffered contents unchanged");
            root.removeTag("Inventory");
            SimpleAssert.eq(
                2,
                HologramBlueprintResources.plan(root).items.size(),
                "internal mirror alone still consumes its physical blank pattern");
            row.setInteger("patternSlot", 36);
            try {
                HologramBlueprintResources.plan(root);
                throw new AssertionError("out-of-range native pattern slot must reject");
            } catch (IOException expected) { /* native bounds are enforced before a reader */ }
        } finally {
            GregTechAPI.METATILEENTITIES[id] = previous;
        }
    }

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramGtMeShadowRegression.class, args)) return;
        run();
        System.out.println("GT ME shadow projection regressions passed");
    }

    public static void run() {
        IMetaTileEntity oldFluid = GregTechAPI.METATILEENTITIES[FLUID_ID];
        IMetaTileEntity oldItem = GregTechAPI.METATILEENTITIES[ITEM_ID];
        IMetaTileEntity oldOutputItem = GregTechAPI.METATILEENTITIES[OUTPUT_ITEM_ID];
        IMetaTileEntity oldOutputFluid = GregTechAPI.METATILEENTITIES[OUTPUT_FLUID_ID];
        try {
            // Constructor-free native registry fixtures: the tested owner is the real exact GT class,
            // while slots below invoke its actual writer. Source/native grid behavior is tested in FML.
            GregTechAPI.METATILEENTITIES[FLUID_ID] = (IMetaTileEntity) allocate(Class.forName(FLUID));
            GregTechAPI.METATILEENTITIES[ITEM_ID] = (IMetaTileEntity) allocate(Class.forName(ITEM));
            GregTechAPI.METATILEENTITIES[OUTPUT_ITEM_ID] = (IMetaTileEntity) allocate(Class.forName(OUTPUT_ITEM));
            GregTechAPI.METATILEENTITIES[OUTPUT_FLUID_ID] = (IMetaTileEntity) allocate(Class.forName(OUTPUT_FLUID));
            fluidProjection();
            itemProjection();
            outputProjection();
            strictSchemas();
        } catch (Exception failure) {
            throw new AssertionError("native GT ME shadow projection", failure);
        } finally {
            GregTechAPI.METATILEENTITIES[FLUID_ID] = oldFluid;
            GregTechAPI.METATILEENTITIES[ITEM_ID] = oldItem;
            GregTechAPI.METATILEENTITIES[OUTPUT_ITEM_ID] = oldOutputItem;
            GregTechAPI.METATILEENTITIES[OUTPUT_FLUID_ID] = oldOutputFluid;
        }
    }

    private static void fluidProjection() throws Exception {
        Fluid liquid = liquid();
        FluidStack config = new FluidStack(liquid, 1), extracted = new FluidStack(liquid, 4096);
        NBTTagCompound source = payload(FLUID_ID, nativeRow(FLUID, config, extracted, 5000));
        source.setTag("mFluid", new FluidStack(liquid, 17).writeToNBT(new NBTTagCompound()));
        NBTTagCompound before = (NBTTagCompound) source.copy();
        NBTTagCompound projected = HologramGtMeShadow.project(source);
        NBTTagCompound row = projected.getTagList("slots", 10)
            .getCompoundTagAt(0);
        SimpleAssert.that(source.equals(before), "native fluid mirror projection leaves captured source untouched");
        SimpleAssert.that(
            row.getCompoundTag("config")
                .equals(
                    before.getTagList("slots", 10)
                        .getCompoundTagAt(0)
                        .getCompoundTag("config")),
            "native fluid filter sample is retained exactly");
        SimpleAssert.that(
            !row.hasKey("extracted") && !row.hasKey("extractedAmount"),
            "unpaid native fluid simulation and deferred extraction amount are absent from stored/placement payload");
        SimpleAssert.that(projected.equals(HologramGtMeShadow.project(projected)), "fluid projection is idempotent");
        HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(source);
        SimpleAssert.eq(0, plan.fluids.size(), "ME hatch captures configuration without its residual tank contents");
        SimpleAssert.that(!projected.hasKey("mFluid"), "residual physical fluid cannot reappear during placement");
        SimpleAssert.that(source.equals(before), "resource quotation cannot mutate the captured ME network mirror");
    }

    private static void itemProjection() throws Exception {
        ItemStack config = new ItemStack(Items.diamond, 1), mirror = new ItemStack(Items.diamond, 64);
        NBTTagCompound source = payload(ITEM_ID, nativeRow(ITEM, config, mirror, 96));
        NBTTagList physical = new NBTTagList();
        NBTTagCompound manual = new ItemStack(Items.emerald, 3).writeToNBT(new NBTTagCompound());
        manual.setInteger("IntSlot", 1);
        physical.appendTag(manual);
        source.setTag("Inventory", physical);
        NBTTagCompound before = (NBTTagCompound) source.copy();
        NBTTagCompound projected = HologramGtMeShadow.project(source);
        NBTTagCompound row = projected.getTagList("slots", 10)
            .getCompoundTagAt(0);
        SimpleAssert.that(
            !row.hasKey("extracted") && !row.hasKey("extractedAmount"),
            "item mirror is removed before a native bus can load its recipe view");
        SimpleAssert.that(source.equals(before), "native item mirror source and manual inventory stay untouched");
        HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(source);
        SimpleAssert
            .eq(0, plan.items.size(), "manual cargo and network mirrors are omitted from the configuration blueprint");
        SimpleAssert.eq(
            0,
            projected.getTagList("Inventory", 10)
                .tagCount(),
            "manual cargo is never loaded into a placed ME bus");
        physical.getCompoundTagAt(0)
            .setInteger("IntSlot", 0);
        boolean circuitRegistered = ItemList.Circuit_Integrated.hasBeenSet();
        IOException rejected = rejects(source, "Inventory");
        if (!circuitRegistered) SimpleAssert.that(
            rejected.getMessage()
                .contains("尚未注册"),
            "an unavailable native circuit registry rejects configuration without invoking its uninitialized getter");
        SimpleAssert.that(
            circuitRegistered == ItemList.Circuit_Integrated.hasBeenSet(),
            "circuit rejection never initializes or changes the global native item registration");
    }

    private static void outputProjection() throws Exception {
        for (boolean fluid : new boolean[] { false, true }) {
            // GT5-Unofficial 5.09.54.205 outputme/MTEHatchOutput{Bus,}ME.saveStackToNBT:
            // itemStack/fluidStack holds the vanilla stack record, size is the real long quantity.
            // Actual AE stack creation and both native writers are checked only by the FML materials smoke.
            NBTTagCompound cached = new NBTTagCompound();
            cached.setTag(
                fluid ? "fluidStack" : "itemStack",
                fluid ? new FluidStack(liquid(), 7654).writeToNBT(new NBTTagCompound())
                    : new ItemStack(Items.diamond, 53).writeToNBT(new NBTTagCompound()));
            cached.setLong("size", fluid ? 7654L : 53L);
            NBTTagCompound source = new NBTTagCompound();
            source.setString("id", "BaseMetaTileEntity");
            source.setInteger("mID", fluid ? OUTPUT_FLUID_ID : OUTPUT_ITEM_ID);
            NBTTagList cache = new NBTTagList();
            cache.appendTag(cached);
            source.setTag("cache", cache);
            source.setTag(fluid ? "cachedFluids" : "cachedItems", cache.copy());
            source.setBoolean("cacheMode", true);
            source.setInteger("myPriority", 71);
            NBTTagCompound before = (NBTTagCompound) source.copy();
            NBTTagCompound clean = HologramGtMeShadow.project(source);
            SimpleAssert.that(
                !clean.hasKey("cache") && !clean.hasKey(fluid ? "cachedFluids" : "cachedItems"),
                "audited output cache fixtures are omitted for both current and legacy readers");
            SimpleAssert.that(
                clean.getBoolean("cacheMode") && clean.getInteger("myPriority") == 71,
                "native output connection/cache settings survive without stock");
            HologramBlueprintResources.Plan plan = HologramBlueprintResources.plan(source);
            SimpleAssert.that(
                plan.items.isEmpty() && plan.fluids.isEmpty(),
                "pending ME output cargo is neither copied nor charged");
            SimpleAssert.eq(before, source, "native output source remains unchanged");
            SimpleAssert.eq(clean, HologramGtMeShadow.project(clean), "native output projection is idempotent");
            NBTTagCompound bad = (NBTTagCompound) source.copy();
            bad.getTagList("cache", 10)
                .getCompoundTagAt(0)
                .setString("size", "7654");
            rejects(bad, "cache");
            bad = (NBTTagCompound) source.copy();
            bad.setString("id", "fixture:unknown_me");
            SimpleAssert.eq(bad, HologramGtMeShadow.project(bad), "unknown host never acquires the ME cache exemption");
        }
    }

    private static void strictSchemas() throws Exception {
        NBTTagCompound source = payload(
            FLUID_ID,
            nativeRow(FLUID, new FluidStack(liquid(), 1), new FluidStack(liquid(), 1000), 1000));
        NBTTagCompound bad = (NBTTagCompound) source.copy();
        bad.getTagList("slots", 10)
            .getCompoundTagAt(0)
            .setTag("cargo", new ItemStack(Items.diamond).writeToNBT(new NBTTagCompound()));
        rejects(bad, "cargo");
        bad = (NBTTagCompound) source.copy();
        bad.getTagList("slots", 10)
            .getCompoundTagAt(0)
            .setString("extractedAmount", "1000");
        rejects(bad, "extractedAmount");
        bad = (NBTTagCompound) source.copy();
        bad.getTagList("slots", 10)
            .getCompoundTagAt(0)
            .setInteger("index", 16);
        rejects(bad, "index");
        bad = (NBTTagCompound) source.copy();
        bad.getTagList("slots", 10)
            .appendTag(
                bad.getTagList("slots", 10)
                    .getCompoundTagAt(0)
                    .copy());
        rejects(bad, "index");
        bad = (NBTTagCompound) source.copy();
        bad.setInteger("version", 0);
        rejects(bad, "version");
        bad = (NBTTagCompound) source.copy();
        bad.setInteger("version", 2);
        rejects(bad, "version");
        bad = (NBTTagCompound) source.copy();
        bad.setString("id", "fixture:opaque");
        SimpleAssert.that(
            bad.equals(HologramGtMeShadow.project(bad)),
            "a spoofed root identity receives no ME schema exemption");
        try {
            HologramBlueprintResources.plan(bad);
            throw new AssertionError("unreviewed owner cannot make independent fluid records free by naming config");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("slots"),
                "unreviewed config remains an unsupported asset");
        }
        NBTTagCompound empty = new NBTTagCompound();
        empty.setString("id", "BaseMetaTileEntity");
        empty.setInteger("mID", FLUID_ID);
        NBTTagCompound emptyProjection = HologramGtMeShadow.project(empty);
        SimpleAssert.eq(
            1,
            emptyProjection.getInteger("version"),
            "an explicitly omitted slot schema becomes an empty current host");
        SimpleAssert.that(!empty.hasKey("version"), "empty projection remains detached from raw source");
    }

    private static IOException rejects(NBTTagCompound payload, String key) throws Exception {
        NBTTagCompound before = (NBTTagCompound) payload.copy();
        try {
            HologramGtMeShadow.project(payload);
            throw new AssertionError("invalid native ME schema: " + key);
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains(key),
                "rejection identifies exact native path " + key);
            SimpleAssert.that(payload.equals(before), "failed native projection preserves raw source");
            return expected;
        }
    }

    /** This record comes from GT .205's real Slot.writeToNBT, not a parallel hand-written serializer. */
    private static NBTTagCompound nativeRow(String owner, Object config, Object extracted, int amount)
        throws Exception {
        Class<?> slotType = Class.forName(owner + "$Slot");
        Object slot = allocate(slotType);
        set(slot, "config", config);
        set(slot, "extracted", extracted);
        set(slot, "extractedAmount", amount);
        NBTTagCompound row = new NBTTagCompound();
        slotType.getMethod("writeToNBT", NBTTagCompound.class)
            .invoke(slot, row);
        row.setInteger("index", 0);
        return row;
    }

    private static NBTTagCompound payload(int id, NBTTagCompound row) {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("id", "BaseMetaTileEntity");
        source.setInteger("mID", id);
        source.setInteger("version", 1);
        NBTTagList slots = new NBTTagList();
        slots.appendTag(row);
        source.setTag("slots", slots);
        return source;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Fluid liquid() {
        String name = "gtmb_native_me_shadow_regression";
        Fluid fluid = FluidRegistry.getFluid(name);
        if (fluid == null) {
            fluid = new Fluid(name);
            FluidRegistry.registerFluid(fluid);
        }
        return fluid;
    }
}
