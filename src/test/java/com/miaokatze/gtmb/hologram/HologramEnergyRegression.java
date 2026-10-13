package com.miaokatze.gtmb.hologram;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Standalone buffer ledger checks; no game, FML or Gradle startup is needed. */
public final class HologramEnergyRegression {

    private HologramEnergyRegression() {}

    public static void main(String[] args) {
        run();
        System.out.println("Hologram energy ledger regressions passed");
    }

    public static void run() {
        realWithdrawalAndSingleUse();
        runtimeConversionAndFallback();
        perCellConversionRounding();
        partialAeRefund();
        staleQuoteAndInsufficientNetwork();
    }

    private static void realWithdrawalAndSingleUse() {
        Bank bank = new Bank(100, 100, true);
        HologramEnergy.Demand demand = new HologramEnergy.Demand();
        demand.eu = BigInteger.valueOf(37);
        demand.ae = new BigDecimal("13.5");
        HologramEnergy.Quote quote = HologramEnergy.quote(demand, bank);
        check(bank.eu.intValue() == 100 && bank.ae == 100, "quote only simulates");
        HologramEnergy.Receipt receipt = quote.consume();
        check(receipt != null && bank.eu.intValue() == 63 && bank.ae == 86.5, "real banks pay exact native amounts");
        boolean reused = false;
        try {
            quote.consume();
        } catch (IllegalStateException expected) {
            reused = true;
        }
        check(reused, "server nonce cannot pay twice");
        receipt.refund();
        receipt.refund();
        check(bank.eu.intValue() == 100 && bank.ae == 100, "placement veto refunds exactly once");
        HologramEnergy.Receipt committed = HologramEnergy.quote(demand, bank)
            .consume();
        committed.commit();
        committed.refund();
        check(bank.eu.intValue() == 63 && bank.ae == 86.5, "successful placement keeps payment");
    }

    private static void runtimeConversionAndFallback() {
        Bank bank = new Bank(1000, 0, false);
        bank.rfRatio = 25;
        bank.aeRatio = 0.5;
        HologramEnergy.Demand demand = new HologramEnergy.Demand();
        demand.rf = new BigDecimal("101");
        demand.ae = new BigDecimal("7.5");
        HologramEnergy.Quote quote = HologramEnergy.quote(demand, bank);
        check(quote.eu.equals(BigInteger.valueOf(30)), "RF and AE conversions round positive fractions up separately");
        check(quote.source.contains("unavailable"), "fallback source is explicit");
        quote.consume()
            .commit();
        check(bank.eu.intValue() == 970 && bank.ae == 0, "no AE network pays from actual wireless EU");
        bank.rfRatio = 100;
        check(HologramEnergy.quote(demand, bank).eu.intValue() == 105, "runtime conversion config is honored");
    }

    private static void partialAeRefund() {
        Bank bank = new Bank(100, 50, true);
        HologramEnergy.Demand demand = new HologramEnergy.Demand();
        demand.eu = BigInteger.TEN;
        demand.ae = BigDecimal.valueOf(20);
        HologramEnergy.Quote quote = HologramEnergy.quote(demand, bank);
        bank.partial = true;
        check(quote.consume() == null, "partial AE withdrawal cannot authorize a full buffer");
        check(bank.eu.intValue() == 100 && bank.ae == 50, "partial withdrawal and prior EU are both refunded");
    }

    private static void perCellConversionRounding() {
        Bank bank = new Bank(10, 0, false);
        bank.rfRatio = 25;
        HologramEnergy.Demand first = new HologramEnergy.Demand();
        first.rf = BigDecimal.ONE;
        HologramEnergy.Demand second = new HologramEnergy.Demand();
        second.rf = BigDecimal.ONE;
        BigInteger quotedBills = HologramEnergy.quote(first, bank).eu.add(HologramEnergy.quote(second, bank).eu);
        check(
            quotedBills.equals(BigInteger.valueOf(2)),
            "separate cell bills retain both positive fractional EU ceilings");
        HologramEnergy.quote(first, bank)
            .consume()
            .commit();
        HologramEnergy.quote(second, bank)
            .consume()
            .commit();
        check(bank.eu.equals(BigInteger.valueOf(8)), "actual per-cell deductions equal summed quoted bills");
    }

    private static void staleQuoteAndInsufficientNetwork() {
        Bank bank = new Bank(100, 50, true);
        HologramEnergy.Demand demand = new HologramEnergy.Demand();
        demand.ae = BigDecimal.valueOf(20);
        HologramEnergy.Quote quote = HologramEnergy.quote(demand, bank);
        bank.connected = false;
        check(quote.consume() == null && bank.eu.intValue() == 100, "disconnected quoted network is revalidated");
        bank.connected = true;
        bank.ae = 5;
        quote = HologramEnergy.quote(demand, bank);
        check(
            !quote.affordable && quote.consume() == null && bank.eu.intValue() == 100,
            "connected but underfunded AE does not silently switch to EU");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Bank implements HologramEnergy.Bank {

        BigInteger eu;
        double ae;
        boolean connected, partial;
        double rfRatio = 100, aeRatio = 0.5;

        Bank(long eu, double ae, boolean connected) {
            this.eu = BigInteger.valueOf(eu);
            this.ae = ae;
            this.connected = connected;
        }

        public BigInteger euAvailable() {
            return eu;
        }

        public boolean takeEu(BigInteger amount) {
            if (eu.compareTo(amount) < 0) return false;
            eu = eu.subtract(amount);
            return true;
        }

        public void refundEu(BigInteger amount) {
            eu = eu.add(amount);
        }

        public boolean aeAvailable() {
            return connected;
        }

        public double takeAe(double amount, boolean simulate) {
            double taken = Math.min(ae, amount);
            if (!simulate) {
                if (partial) taken /= 2;
                ae -= taken;
            }
            return taken;
        }

        public void refundAe(double amount) {
            ae += amount;
        }

        public double rfToEuPerHundred() {
            return rfRatio;
        }

        public double aeToEu() {
            return aeRatio;
        }
    }
}
