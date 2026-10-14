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
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Native GT Slot serializers and exact class registrations exercise projection; no live ME grid is claimed. */
public final class HologramGtMeShadowRegression {

    private static final String FLUID = "gregtech.common.tileentities.machines.MTEHatchInputME";
    private static final String ITEM = "gregtech.common.tileentities.machines.MTEHatchInputBusME";
    private static final int FLUID_ID = 31001, ITEM_ID = 31002;

    private HologramGtMeShadowRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramGtMeShadowRegression.class, args)) return;
        run();
        System.out.println("GT ME shadow projection regressions passed");
    }

    public static void run() {
        IMetaTileEntity oldFluid = GregTechAPI.METATILEENTITIES[FLUID_ID];
        IMetaTileEntity oldItem = GregTechAPI.METATILEENTITIES[ITEM_ID];
        try {
            // Constructor-free native registry fixtures: the tested owner is the real exact GT class,
            // while slots below invoke its actual writer. Source/native grid behavior is tested in FML.
            GregTechAPI.METATILEENTITIES[FLUID_ID] = (IMetaTileEntity) allocate(Class.forName(FLUID));
            GregTechAPI.METATILEENTITIES[ITEM_ID] = (IMetaTileEntity) allocate(Class.forName(ITEM));
            fluidProjection();
            itemProjection();
            strictSchemas();
        } catch (Exception failure) {
            throw new AssertionError("native GT ME shadow projection", failure);
        } finally {
            GregTechAPI.METATILEENTITIES[FLUID_ID] = oldFluid;
            GregTechAPI.METATILEENTITIES[ITEM_ID] = oldItem;
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
        SimpleAssert.eq(1, plan.fluids.size(), "only the independent physical tank is priced");
        SimpleAssert
            .eq(17L, plan.fluids.get(0).amount, "ghost config and 4096-point mirror cannot increase fluid demand");
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
        SimpleAssert.eq(1, plan.items.size(), "real manual slot remains a required physical item");
        SimpleAssert.that(
            plan.items.get(0)
                .getItem() == Items.emerald && plan.items.get(0).stackSize == 3,
            "only three emeralds are charged; configured diamond and 64 shadow diamonds are not physical stock");
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

    private static void rejects(NBTTagCompound payload, String key) throws Exception {
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
