package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Pure NBT projection checks. Does not start a Minecraft world or loader. */
public final class HologramEnergyPayloadRegression {

    private HologramEnergyPayloadRegression() {}

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("Hologram energy payload regressions passed");
    }

    public static void run() {
        try {
            verify();
        } catch (Exception failure) {
            throw new AssertionError("Energy payload projection failed", failure);
        }
    }

    private static void verify() throws Exception {
        NBTTagCompound source = new NBTTagCompound();
        source.setLong("mStoredEnergy", Long.MAX_VALUE);
        source.setDouble("internalCurrentPower", 12.5);
        source.setDouble("internalMaxPower", 200000);
        source.setLong("EnergyL", 9000);
        NBTTagCompound unknown = new NBTTagCompound();
        unknown.setLong("amount", 777);
        source.setTag("energyBuffer", unknown);
        NBTTagCompound inventoryItem = new NBTTagCompound();
        inventoryItem.setString("id", "fixture:battery");
        inventoryItem.setByte("Count", (byte) 1);
        inventoryItem.setShort("Damage", (short) 0);
        inventoryItem.setDouble("internalCurrentPower", 99);
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(inventoryItem);
        source.setTag("Inventory", inventory);
        NBTTagCompound snapshot = (NBTTagCompound) source.copy();
        HologramEnergyPayload.Prepared prepared = HologramEnergyPayload.prepare(source, null);
        check(source.equals(snapshot), "inspection never mutates captured NBT");
        check(prepared.demand.eu.equals(BigInteger.valueOf(Long.MAX_VALUE)), "native EU remains exact");
        check(prepared.demand.ae.equals(new BigDecimal("12.5")), "only device AE buffer is paid");
        check(prepared.tilePaid.getLong("mStoredEnergy") == Long.MAX_VALUE, "paid payload retains EU");
        check(
            prepared.tileEmpty.getLong("mStoredEnergy") == 0
                && prepared.tileEmpty.getDouble("internalCurrentPower") == 0,
            "underfunded payload has empty device buffers");
        check(
            prepared.tilePaid.getLong("EnergyL") == 0 && !prepared.tilePaid.hasKey("energyBuffer"),
            "unknown numeric and container cache schemas cannot restore free energy");
        check(!prepared.warnings.isEmpty(), "unknown caches report omission");
        check(prepared.tileEmpty.getDouble("internalMaxPower") == 200000, "capacity is never a payment amount");
        check(
            prepared.tileEmpty.getTagList("Inventory", 10)
                .getCompoundTagAt(0)
                .getDouble("internalCurrentPower") == 99,
            "tagged inventory items remain exact material costs rather than device energy banks");
        NBTTagCompound invalid = new NBTTagCompound();
        invalid.setDouble("internalCurrentPower", Double.POSITIVE_INFINITY);
        HologramEnergyPayload.Prepared invalidPrepared = HologramEnergyPayload.prepare(invalid, null);
        check(
            invalidPrepared.demand.empty() && invalidPrepared.tilePaid.getDouble("internalCurrentPower") == 0,
            "nonfinite source energy cannot authorize an infinite buffer");
        capacitorBankProjection();
        creativeAeProjection();
    }

    /**
     * Tests the detached projection with explicit native type values; actual target resolution is a runtime API check.
     */
    private static void capacitorBankProjection() throws Exception {
        Class<?> schemaClass = Class.forName(HologramEnergyPayload.class.getName() + "$CapBankSchema");
        Constructor<?> schemaConstructor = schemaClass.getDeclaredConstructor(String.class, int.class, boolean.class);
        schemaConstructor.setAccessible(true);
        Constructor<?> preparedConstructor = HologramEnergyPayload.Prepared.class
            .getDeclaredConstructor(NBTTagCompound.class, NBTTagCompound.class, schemaClass);
        preparedConstructor.setAccessible(true);
        NBTTagCompound source = new NBTTagCompound();
        source.setString("type", "CREATIVE");
        source.setInteger("storedEnergyRF", 1800);
        NBTTagCompound item = (NBTTagCompound) source.copy();
        HologramEnergyPayload.Prepared finite = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, item, schemaConstructor.newInstance("SIMPLE", 1000, false));
        check(
            finite.tilePaid.getString("type")
                .equals("SIMPLE")
                && finite.itemPaid.getString("type")
                    .equals("SIMPLE"),
            "source NBT cannot promote the actual finite target to creative");
        check(
            finite.tilePaid.getInteger("storedEnergyRF") == 1000 && finite.tileEmpty.getInteger("storedEnergyRF") == 0,
            "ordinary buffer respects native target capacity and empty fallback");
        check(finite.demand.rf.equals(BigDecimal.valueOf(1000)), "tile and block-item aliases fund one actual bank");
        source.setInteger("storedEnergyRF", 17);
        item = (NBTTagCompound) source.copy();
        HologramEnergyPayload.Prepared creative = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, item, schemaConstructor.newInstance("CREATIVE", 1000, true));
        check(
            creative.demand.empty() && !creative.intrinsicSource.isEmpty(),
            "verified native creative bank is an explicit intrinsic source");
        check(
            creative.tilePaid.getInteger("storedEnergyRF") == 17 && creative.itemPaid.getInteger("storedEnergyRF") == 17
                && creative.tileEmpty.getInteger("storedEnergyRF") == 0
                && creative.itemEmpty.getInteger("storedEnergyRF") == 0,
            "creative selected buffer is retained while explicit empty projection is zero");
        check(
            source.getInteger("storedEnergyRF") == 17 && source.getString("type")
                .equals("CREATIVE"),
            "native projection leaves captured source unchanged");
        source.setInteger("storedEnergyRF", 0);
        HologramEnergyPayload.Prepared zero = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, source, schemaConstructor.newInstance("CREATIVE", 5000000, true));
        check(
            zero.tilePaid.hasKey("storedEnergyRF", 3) && zero.tilePaid.getInteger("storedEnergyRF") == 0
                && zero.itemPaid.getInteger("storedEnergyRF") == 0
                && zero.demand.empty(),
            "SELF15 native Int0 stays zero rather than becoming the 2500000 RF catalog template");
        source.setInteger("storedEnergyRF", 1000);
        HologramEnergyPayload.Prepared full = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, null, schemaConstructor.newInstance("CREATIVE", 1000, true));
        check(
            full.tilePaid.getInteger("storedEnergyRF") == 1000 && full.demand.empty(),
            "creative native capacity boundary is retained without buying finite RF");
        source.setInteger("storedEnergyRF", 1001);
        HologramEnergyPayload.Prepared above = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, null, schemaConstructor.newInstance("CREATIVE", 1000, true));
        check(
            above.tilePaid.getInteger("storedEnergyRF") == 1000 && above.tileEmpty.getInteger("storedEnergyRF") == 0,
            "creative over-capacity data follows native clamp rather than generating half a bank");
        source.setShort("storedEnergyRF", (short) 17);
        HologramEnergyPayload.Prepared numeric = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, null, schemaConstructor.newInstance("CREATIVE", 1000, true));
        check(
            source.hasKey("storedEnergyRF", 2) && numeric.tilePaid.hasKey("storedEnergyRF", 3)
                && numeric.tilePaid.getInteger("storedEnergyRF") == 17
                && numeric.warnings.isEmpty(),
            "native numeric reader accepts short RF and native writer normalizes it to Int without mutating source");
        source.setInteger("storedEnergyRF", -1);
        HologramEnergyPayload.Prepared negative = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, null, schemaConstructor.newInstance("CREATIVE", 1000, true));
        check(
            negative.tilePaid.getInteger("storedEnergyRF") == 0 && !negative.warnings.isEmpty(),
            "negative creative RF is explicitly clamped to native zero");
        source.setString("storedEnergyRF", "17");
        HologramEnergyPayload.Prepared malformed = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, null, schemaConstructor.newInstance("CREATIVE", 1000, true));
        check(
            malformed.tilePaid.getInteger("storedEnergyRF") == 0 && !malformed.warnings.isEmpty(),
            "nonnumeric creative RF is reported and never replaced with a template balance");
        source.setDouble("storedEnergyRF", Double.NaN);
        HologramEnergyPayload.Prepared nonfinite = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, null, schemaConstructor.newInstance("CREATIVE", 1000, true));
        check(
            nonfinite.tilePaid.getInteger("storedEnergyRF") == 0 && !nonfinite.warnings.isEmpty(),
            "nonfinite creative RF cannot authorize a fabricated buffer");
        source.setInteger("storedEnergyRF", 17);
        HologramEnergyPayload.Prepared ordinary = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, source, schemaConstructor.newInstance("SIMPLE", 1000, false));
        check(
            ordinary.demand.rf.equals(BigDecimal.valueOf(17)) && ordinary.intrinsicSource.isEmpty()
                && ordinary.tilePaid.getInteger("storedEnergyRF") == 17
                && ordinary.tileEmpty.getInteger("storedEnergyRF") == 0,
            "same nonzero buffer on an actual finite variant still requires exact RF payment once");
    }

    private static void creativeAeProjection() throws Exception {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("id", "appeng.tile.networking.TileCreativeEnergyController");
        source.setDouble("internalCurrentPower", (double) (Long.MAX_VALUE / 10000));
        source.setDouble("internalMaxPower", 123456);
        HologramEnergyPayload.Prepared ordinary = HologramEnergyPayload.prepare(source, null);
        check(
            ordinary.demand.ae.signum() > 0 && ordinary.intrinsicSource.isEmpty(),
            "copied native tile id and a huge balance do not authorize an infinite target");
        Class<?> schemaClass = Class.forName(HologramEnergyPayload.class.getName() + "$CapBankSchema");
        Constructor<?> preparedConstructor = HologramEnergyPayload.Prepared.class
            .getDeclaredConstructor(NBTTagCompound.class, NBTTagCompound.class, schemaClass, String.class);
        preparedConstructor.setAccessible(true);
        HologramEnergyPayload.Prepared nativeCreative = (HologramEnergyPayload.Prepared) preparedConstructor
            .newInstance(source, source, null, "AE native creative energy provider");
        check(
            nativeCreative.demand.empty() && !nativeCreative.intrinsicSource.isEmpty(),
            "verified native creative target does not pay a copied base cache");
        check(
            nativeCreative.tilePaid.getDouble("internalCurrentPower") == 0
                && nativeCreative.itemPaid.getDouble("internalCurrentPower") == 0
                && !nativeCreative.tilePaid.hasKey("internalMaxPower"),
            "creative payload clears copied cache/capacity and lets native constant power getters supply behavior");
        check(
            source.getDouble("internalCurrentPower") > 0 && source.hasKey("internalMaxPower"),
            "creative projection never rewrites captured source data");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
