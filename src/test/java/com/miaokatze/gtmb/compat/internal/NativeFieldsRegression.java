package com.miaokatze.gtmb.compat.internal;

/** Exact private-field lookup contracts used by the audited JABBA and Drawers adapters. */
public final class NativeFieldsRegression {

    private NativeFieldsRegression() {}

    public static void main(String[] args) throws Exception {
        Child child = new Child();
        if (NativeFields.find(Child.class, "inherited")
            .getInt(child) != 7) throw new AssertionError("Inherited private field must remain accessible");
        if (NativeFields.find(Child.class, "shadowed")
            .getInt(child) != 11) throw new AssertionError("The nearest declaration must win");
        NativeFields.find(Child.class, "inherited")
            .setInt(child, 19);
        if (NativeFields.find(Parent.class, "inherited")
            .getInt(child) != 19) throw new AssertionError("Native setters must write the actual parent field");
        try {
            NativeFields.find(Child.class, "absent");
            throw new AssertionError("Unreviewed fields must fail closed");
        } catch (NoSuchFieldException expected) {
            if (!"absent".equals(expected.getMessage())) throw new AssertionError("Missing-field diagnostic changed");
        }
        System.out.println("NativeFieldsRegression PASS");
    }

    private static class Parent {

        private int inherited = 7;
        private int shadowed = 3;
    }

    private static final class Child extends Parent {

        private int shadowed = 11;
    }
}
