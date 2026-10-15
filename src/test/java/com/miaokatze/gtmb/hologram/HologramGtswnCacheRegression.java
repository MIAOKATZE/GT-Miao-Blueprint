package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** GTSWN cover buffers use exact native schemas and the existing single-use EU payment/refund contract. */
public final class HologramGtswnCacheRegression {

    private HologramGtswnCacheRegression() {}

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("Hologram GTSWN cache regressions passed");
    }

    public static void run() {
        try {
            supportedBuffers();
            aliasesAndSeparateSides();
            unknownBuffersAndInventory();
            malformedBuffers();
            capacityAndLargeBills();
            existingEuReceipt();
        } catch (Exception failure) {
            throw new AssertionError("GTSWN cache projection failed", failure);
        }
    }

    private static HologramGtswnCacheAdapter.Schema schema(NBTTagCompound row, int side, int id) throws IOException {
        if (id == 41) return new HologramGtswnCacheAdapter.Schema(true, 1000, (double) 0.15f);
        if (id == 42) return new HologramGtswnCacheAdapter.Schema(false, Long.MAX_VALUE, 0.25);
        return null;
    }

    private static HologramEnergyPayload.Prepared prepare(NBTTagCompound tile, NBTTagCompound item) throws IOException {
        return HologramEnergyPayload.prepare(tile, item, HologramGtswnCacheRegression::schema);
    }

    private static void supportedBuffers() throws Exception {
        NBTTagCompound source = payload(row(0, 41, 100));
        source.setLong("mStoredEnergy", 200);
        NBTTagCompound before = (NBTTagCompound) source.copy();
        HologramEnergyPayload.Prepared prepared = prepare(source, null);
        check(source.equals(before), "inspection leaves captured cover NBT untouched");
        // Config.downlinkLossEU is a native float widened to double, including its rounding before ceil.
        check(prepared.demand.eu.equals(BigInteger.valueOf(316)), "cover loss bill merges with the machine EU buffer");
        check(data(prepared.tilePaid, 0).getLong("storedEU") == 100, "paid cover restores its captured stored EU");
        check(data(prepared.tileEmpty, 0).getLong("storedEU") == 0, "underfunded cover starts empty");
        check(
            data(prepared.tilePaid, 0).getLong("capacity") == 1000,
            "copied capacity cannot override native capacity");
        check(
            data(prepared.tilePaid, 0).getDouble("fractionalEU") == 0
                && data(prepared.tileEmpty, 0).getDouble("fractionalEU") == 0,
            "fractional carry cannot create unpaid EU in either projection");
        check(
            data(prepared.tilePaid, 0).getLong("ticksSinceLastRefill") == 0
                && data(prepared.tileEmpty, 0).getLong("ticksSinceLastRefill") == 0,
            "copy starts a fresh native transfer clock");
        check(data(prepared.tilePaid, 0).getInteger("voltage") == 32, "native voltage configuration is retained");
        check(prepared.warnings.isEmpty(), "supported paid covers no longer report unknown-cache omission");
    }

    private static void aliasesAndSeparateSides() throws Exception {
        NBTTagCompound tile = payload(row(2, 41, 100));
        NBTTagCompound item = payload(row(2, 41, 80));
        HologramEnergyPayload.Prepared aliases = prepare(tile, item);
        check(aliases.demand.eu.equals(BigInteger.valueOf(116)), "tile/item aliases pay for one installed cover");
        check(
            data(aliases.tilePaid, 0).getLong("storedEU") == 100
                && data(aliases.itemPaid, 0).getLong("storedEU") == 100,
            "both placement paths restore only the one funded buffer");
        HologramEnergyPayload.Prepared sides = prepare(payload(row(0, 41, 1), row(5, 41, 1)), null);
        check(sides.demand.eu.equals(BigInteger.valueOf(4)), "loss is rounded for each distinct installed cover");
        NBTTagCompound changed = payload(row(2, 41, 100));
        data(changed, 0).setInteger("voltage", 128);
        expectIo(() -> prepare(tile, changed));
        expectIo(() -> prepare(tile, payload(row(2, 42, 100))));
    }

    private static void unknownBuffersAndInventory() throws Exception {
        NBTTagCompound source = payload(row(0, 99, 100));
        source.setLong("storedEU", 500);
        NBTTagCompound carried = payload(row(1, 41, 700));
        carried.setShort("id", (short) 1);
        carried.setByte("Count", (byte) 1);
        carried.setShort("Damage", (short) 0);
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(carried);
        source.setTag("Inventory", inventory);
        HologramEnergyPayload.Prepared prepared = prepare(source, null);
        check(prepared.demand.empty(), "unregistered schemas and charged inventory items grant no device demand");
        check(
            data(prepared.tilePaid, 0).getLong("storedEU") == 0 && prepared.tilePaid.getLong("storedEU") == 0,
            "unknown cover and top-level storedEU still empty safely");
        check(
            data(
                prepared.tilePaid.getTagList("Inventory", 10)
                    .getCompoundTagAt(0),
                0).getLong("storedEU") == 700,
            "charged inventory item remains its exact paid material identity");
        HologramEnergyPayload.Prepared carriedRoot = prepare(carried, null);
        check(
            carriedRoot.demand.empty() && data(carriedRoot.tilePaid, 0).getLong("storedEU") == 700,
            "a root ItemStack record cannot impersonate an installed cover bank");
        check(HologramGtswnCacheAdapter.kindForCover(new Object()) == -1, "unknown runtime classes are not link nodes");
        expectIo(
            () -> HologramEnergyPayload
                .prepare(source, null, (row, side, id) -> { throw new IOException("native registry unavailable"); }));
    }

    private static void malformedBuffers() throws Exception {
        NBTTagCompound integer = payload(row(0, 41, 100));
        data(integer, 0).setInteger("storedEU", 100);
        expectIo(() -> prepare(integer, null));
        NBTTagCompound negative = payload(row(0, 41, -1));
        expectIo(() -> prepare(negative, null));
        NBTTagCompound configured = payload(row(0, 41, 100));
        data(configured, 0).setString("configured", "true");
        expectIo(() -> prepare(configured, null));
        NBTTagCompound noData = row(0, 41, 100);
        noData.removeTag("d");
        expectIo(() -> prepare(payload(noData), null));
        expectIo(() -> prepare(payload(row(0, 41, 1), row(0, 41, 2)), null));
        expectIo(() -> prepare(payload(row(6, 41, 1)), null));
        NBTTagCompound wrongList = new NBTTagCompound();
        NBTTagList strings = new NBTTagList();
        strings.appendTag(new NBTTagString("cover"));
        wrongList.setTag("gt.covers", strings);
        expectIo(() -> prepare(wrongList, null));
    }

    private static void capacityAndLargeBills() throws Exception {
        HologramEnergyPayload.Prepared clipped = prepare(payload(row(0, 41, 2000)), null);
        check(data(clipped.tilePaid, 0).getLong("storedEU") == 1000, "energy restore respects current native capacity");
        check(clipped.demand.eu.equals(BigInteger.valueOf(1151)), "only the bounded target buffer is charged");
        check(!clipped.warnings.isEmpty(), "native capacity adjustment is visible");
        NBTTagCompound unconfigured = payload(row(0, 41, 100));
        data(unconfigured, 0).setBoolean("configured", false);
        HologramEnergyPayload.Prepared empty = prepare(unconfigured, null);
        check(
            empty.demand.empty() && data(empty.tilePaid, 0).getLong("storedEU") == 0,
            "an unconfigured native cover receives no paid buffer");
        HologramEnergyPayload.Prepared dynamo = prepare(payload(row(0, 42, Long.MAX_VALUE)), null);
        BigInteger bill = HologramGtswnCacheAdapter.deduction(Long.MAX_VALUE, 0.25);
        check(
            dynamo.demand.eu.equals(bill) && bill.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
            "dynamo cache and downlink bill retain exact long/BigInteger precision");
        check(data(dynamo.tilePaid, 0).getLong("storedEU") == Long.MAX_VALUE, "dynamo stored EU remains exact");
    }

    private static void existingEuReceipt() throws Exception {
        HologramEnergyPayload.Prepared prepared = prepare(payload(row(0, 41, 100)), null);
        EuBank bank = new EuBank(1000);
        HologramEnergy.Quote quote = HologramEnergy.quote(prepared.demand, bank);
        check(bank.balance.equals(BigInteger.valueOf(1000)), "preview never debits the actor's EU bank");
        HologramEnergy.Receipt receipt = quote.consume();
        check(
            receipt != null && bank.balance.equals(BigInteger.valueOf(884)),
            "consume pays the native cover bill once");
        receipt.refund();
        receipt.refund();
        check(
            bank.balance.equals(BigInteger.valueOf(1000)) && bank.refunds == 1,
            "failed construction refunds the actor exactly once");
        HologramEnergy.Receipt committed = HologramEnergy.quote(prepared.demand, bank)
            .consume();
        committed.commit();
        committed.refund();
        check(bank.balance.equals(BigInteger.valueOf(884)), "successful construction cannot later refund a paid cache");
        EuBank poor = new EuBank(115);
        check(
            HologramEnergy.quote(prepared.demand, poor)
                .consume() == null && poor.balance.equals(BigInteger.valueOf(115))
                && data(prepared.tileEmpty, 0).getLong("storedEU") == 0,
            "insufficient EU leaves the bank unchanged and provides the existing empty fallback");
    }

    private static final class EuBank implements HologramEnergy.Bank {

        BigInteger balance;
        int refunds;

        EuBank(long balance) {
            this.balance = BigInteger.valueOf(balance);
        }

        public BigInteger euAvailable() {
            return balance;
        }

        public boolean takeEu(BigInteger amount) {
            if (balance.compareTo(amount) < 0) return false;
            balance = balance.subtract(amount);
            return true;
        }

        public void refundEu(BigInteger amount) {
            balance = balance.add(amount);
            refunds++;
        }

        public boolean aeAvailable() {
            return false;
        }

        public double takeAe(double amount, boolean simulate) {
            throw new AssertionError("EU cover cannot consume AE");
        }

        public void refundAe(double amount) {
            throw new AssertionError("EU cover cannot refund AE");
        }

        public double rfToEuPerHundred() {
            throw new AssertionError("EU cover cannot convert RF");
        }

        public double aeToEu() {
            throw new AssertionError("EU cover cannot convert AE");
        }
    }

    private static NBTTagCompound row(int side, int id, long stored) {
        NBTTagCompound row = new NBTTagCompound();
        row.setByte("s", (byte) side);
        row.setInteger("id", id);
        NBTTagCompound data = new NBTTagCompound();
        data.setLong("storedEU", stored);
        data.setBoolean("configured", true);
        data.setInteger("voltage", 32);
        data.setDouble("amperageDecimal", 0.1);
        data.setLong("capacity", Long.MAX_VALUE);
        data.setDouble("fractionalEU", 0.75);
        data.setLong("ticksSinceLastRefill", 500);
        row.setTag("d", data);
        return row;
    }

    private static NBTTagCompound payload(NBTTagCompound... rows) {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList covers = new NBTTagList();
        for (NBTTagCompound row : rows) covers.appendTag(row);
        result.setTag("gt.covers", covers);
        return result;
    }

    private static NBTTagCompound data(NBTTagCompound payload, int index) {
        return payload.getTagList("gt.covers", 10)
            .getCompoundTagAt(index)
            .getCompoundTag("d");
    }

    private interface IoAction {

        void run() throws Exception;
    }

    private static void expectIo(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected invalid native cover buffer to be rejected");
        } catch (IOException expected) {
            // Required rejection before any receipt or world mutation.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
