package com.miaokatze.gtmb.testutil;

/** Framework-free assertions aggregated by TestRunner. */
public final class SimpleAssert {

    private SimpleAssert() {}

    /** 条件不成立抛 {@link AssertionError} */
    public static void that(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    /** 相等断言（null 安全） */
    public static void eq(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + "：期望 <" + expected + ">，实际 <" + actual + ">");
        }
    }
}
