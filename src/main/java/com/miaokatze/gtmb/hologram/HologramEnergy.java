package com.miaokatze.gtmb.hologram;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;

/** Server-only quotes and single-use receipts for retained device buffers. Never accesses item energy. */
public final class HologramEnergy {

    private HologramEnergy() {}

    public static final class Demand {

        public BigInteger eu = BigInteger.ZERO;
        public BigDecimal rf = BigDecimal.ZERO;
        public BigDecimal ae = BigDecimal.ZERO;

        public Demand merge(Demand other) {
            if (other != null) {
                eu = eu.add(other.eu);
                rf = rf.add(other.rf);
                ae = ae.add(other.ae);
            }
            return this;
        }

        public boolean empty() {
            return eu.signum() == 0 && rf.signum() == 0 && ae.signum() == 0;
        }

        private void validate() {
            if (eu.signum() < 0 || rf.signum() < 0 || ae.signum() < 0)
                throw new IllegalArgumentException("Negative buffer demand");
        }
    }

    /** A bank instance binds the placing player and their currently authenticated network. */
    public interface Bank {

        BigInteger euAvailable();

        boolean takeEu(BigInteger amount);

        void refundEu(BigInteger amount);

        boolean aeAvailable();

        double takeAe(double amount, boolean simulate);

        void refundAe(double amount);

        double rfToEuPerHundred();

        double aeToEu();
    }

    public static final class Quote {

        public final UUID nonce = UUID.randomUUID();
        public final BigInteger eu;
        public final double ae;
        public final String source;
        public final boolean affordable;
        public final BigInteger availableEu;
        public final double availableAe;
        private final Bank bank;
        private boolean consumed;

        private Quote(Bank bank, BigInteger eu, double ae, String source, boolean affordable) {
            this.bank = bank;
            this.eu = eu;
            this.ae = ae;
            this.source = source;
            this.affordable = affordable;
            this.availableEu = bank.euAvailable();
            this.availableAe = ae > 0 ? bank.takeAe(ae, true) : 0;
        }

        /** Rechecks and pays from this quote's bound banks. Preview values cannot authorize restoration. */
        public synchronized Receipt consume() {
            if (consumed) throw new IllegalStateException("Energy quote already consumed");
            consumed = true;
            if (!affordable) return null;
            BigInteger paidEu = BigInteger.ZERO;
            double paidAe = 0;
            try {
                if (ae > 0 && (!bank.aeAvailable() || bank.takeAe(ae, true) < ae)) return null;
                if (eu.signum() > 0) {
                    if (!bank.takeEu(eu)) return null;
                    paidEu = eu;
                }
                if (ae > 0) {
                    paidAe = bank.takeAe(ae, false);
                    if (!Double.isFinite(paidAe) || paidAe < 0 || paidAe > ae)
                        throw new IllegalStateException("Invalid AE extraction receipt");
                    if (paidAe < ae) {
                        if (paidAe > 0) bank.refundAe(paidAe);
                        paidAe = 0;
                        if (paidEu.signum() > 0) bank.refundEu(paidEu);
                        paidEu = BigInteger.ZERO;
                        return null;
                    }
                }
                return new Receipt(bank, paidEu, paidAe, source);
            } catch (RuntimeException | LinkageError failure) {
                if (paidAe > 0) bank.refundAe(paidAe);
                if (paidEu.signum() > 0) bank.refundEu(paidEu);
                throw failure;
            }
        }
    }

    public static final class Receipt {

        public final BigInteger eu;
        public final double ae;
        public final String source;
        private final Bank bank;
        private boolean settled;

        private Receipt(Bank bank, BigInteger eu, double ae, String source) {
            this.bank = bank;
            this.eu = eu;
            this.ae = ae;
            this.source = source;
        }

        public synchronized void commit() {
            if (settled) throw new IllegalStateException("Energy receipt already settled");
            settled = true;
        }

        /** Banks must queue undeliverable refunds; calling this twice never issues a second refund. */
        public synchronized void refund() {
            if (settled) return;
            settled = true;
            if (ae > 0) bank.refundAe(ae);
            if (eu.signum() > 0) bank.refundEu(eu);
        }
    }

    public static Quote quote(Demand demand, Bank bank) {
        demand.validate();
        BigInteger eu = demand.eu;
        if (demand.rf.signum() > 0) eu = eu.add(
            convert(demand.rf, bank.rfToEuPerHundred()).divide(new BigDecimal(100), 0, RoundingMode.CEILING)
                .toBigIntegerExact());
        boolean networkAe = demand.ae.signum() > 0 && bank.aeAvailable();
        double ae = 0;
        if (networkAe) {
            ae = demand.ae.doubleValue();
            // Never restore more than the native double API can represent or was actually asked to pay.
            if (!Double.isFinite(ae) || ae <= 0
                || BigDecimal.valueOf(ae)
                    .compareTo(demand.ae) != 0)
                return new Quote(bank, eu, 0, "AE network: unsupported amount precision", false);
        } else if (demand.ae.signum() > 0) {
            eu = eu.add(
                convert(demand.ae, bank.aeToEu()).setScale(0, RoundingMode.CEILING)
                    .toBigIntegerExact());
        }
        String source = networkAe ? (eu.signum() > 0 ? "AE network + wireless EU" : "AE network")
            : demand.ae.signum() > 0 ? "wireless EU (AE network unavailable)"
                : demand.rf.signum() > 0 ? "wireless EU (RF conversion)" : "wireless EU";
        boolean affordable = bank.euAvailable()
            .compareTo(eu) >= 0 && (ae == 0 || bank.takeAe(ae, true) >= ae);
        return new Quote(bank, eu, ae, source, affordable);
    }

    private static BigDecimal convert(BigDecimal amount, double factor) {
        if (!Double.isFinite(factor) || factor <= 0)
            throw new IllegalStateException("Invalid runtime energy conversion");
        return amount.multiply(BigDecimal.valueOf(factor));
    }
}
