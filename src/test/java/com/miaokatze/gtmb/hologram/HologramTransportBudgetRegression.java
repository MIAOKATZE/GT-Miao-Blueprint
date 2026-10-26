package com.miaokatze.gtmb.hologram;

import java.util.UUID;

/** Ordinary and cut owners spend the same bytes and elapsed time, never separate per-family pools. */
public final class HologramTransportBudgetRegression {

    public static void main(String[] args) {
        run();
        System.out.println("HologramTransportBudgetRegression passed");
    }

    public static void run() {
        HologramTransportBudget budget = new HologramTransportBudget(12, 8, 10);
        UUID ordinary = new UUID(0, 1), cut = new UUID(0, 2);
        budget.begin(100);
        check(budget.tryAcquire(ordinary, 6, 101), "ordinary frame admitted");
        check(!budget.tryAcquire(ordinary, 3, 102), "owner quota applies across frames");
        check(budget.tryAcquire(cut, 6, 103), "cut frame shares remaining bytes");
        check(!budget.tryAcquire(cut, 1, 104), "global quota is shared by both families");
        budget.begin(200);
        check(!budget.tryAcquire(ordinary, 1, 210), "time budget bounds data sends");
        budget.begin(300);
        check(budget.tryAcquire(cut, 8, 301), "next tick renews owner and global quotas");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
