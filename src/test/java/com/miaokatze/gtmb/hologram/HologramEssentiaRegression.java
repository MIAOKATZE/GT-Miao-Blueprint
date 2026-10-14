package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import appeng.api.config.Actionable;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;

/** Bounded ledger fixtures verify conservation; genuine ThE monitors are covered by the FML world smoke. */
public final class HologramEssentiaRegression {

    private HologramEssentiaRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramEssentiaRegression.class, args)) return;
        run();
        System.out.println("Hologram essentia regressions passed");
    }

    public static void run() {
        try {
            jarPayload();
            nonJarAssets();
            shortageAndRollback();
            chargingFailure();
            aspectMismatch();
        } catch (Exception failure) {
            throw new AssertionError("essentia conservation regression", failure);
        }
    }

    private static void jarPayload() throws Exception {
        NBTTagCompound jar = new NBTTagCompound();
        jar.setString("id", "TileJar");
        jar.setString("Aspect", "aer");
        jar.setShort("Amount", (short) 64);
        NBTTagCompound before = (NBTTagCompound) jar.copy();
        HologramEssentia.Cost cost = HologramEssentia.jarCost(jar, "aer"::equals);
        SimpleAssert.eq("aer", cost.aspect, "the native aspect identity is retained");
        SimpleAssert.eq(64L, cost.amount, "a full jar requires sixty-four actual points");
        SimpleAssert.that(before.equals(jar), "quotation never modifies preview contents");
        jar.setString("id", "TileJarVoid");
        SimpleAssert.eq(64L, HologramEssentia.jarCost(jar, "aer"::equals).amount, "void jars also pay their contents");
        jar.setShort("Amount", (short) 0);
        SimpleAssert.that(
            HologramEssentia.jarCost(jar, tag -> false) == null,
            "explicitly empty contents need no aspect registry or payment");
        SimpleAssert.that(HologramBlueprintResources.plan(jar).essentia.isEmpty(), "empty native jar plan stays empty");
        for (long invalid : new long[] { -1, 65, 65536, Long.MAX_VALUE }) {
            jar.setLong("Amount", invalid);
            rejects(jar, "out-of-range quantities cannot wrap through TC's short reader");
        }
        jar.setShort("Amount", (short) 1);
        jar.removeTag("Aspect");
        rejects(jar, "a nonempty jar cannot silently omit its aspect");
        jar.setString("Aspect", "not_registered");
        rejects(jar, "unregistered aspects block content retention");
        jar.setString("id", "fixture:unrelated");
        SimpleAssert.that(
            HologramEssentia.jarCost(jar, tag -> false) == null,
            "an unrelated Amount field is never interpreted as essentia");
    }

    private static void rejects(NBTTagCompound jar, String reason) throws Exception {
        try {
            HologramEssentia.jarCost(jar, "aer"::equals);
            throw new AssertionError(reason);
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("源质"),
                reason);
        }
    }

    private static void nonJarAssets() throws Exception {
        NBTTagCompound alembic = new NBTTagCompound();
        alembic.setString("id", "TileSiphon");
        alembic.setString("aspect", "aer");
        alembic.setShort("amount", (short) 32);
        nonJarRejects(alembic, "native alembic contents cannot pass as a free lowercase quantity");
        alembic.setShort("amount", (short) 0);
        SimpleAssert
            .that(HologramBlueprintResources.plan(alembic).essentia.isEmpty(), "empty reviewed alembic is allowed");
        for (String id : new String[] { "TileAlchemyFurnace", "TileAlchemyFurnaceAdvanced", "TileTubeBuffer" }) {
            NBTTagCompound furnace = new NBTTagCompound();
            furnace.setString("id", id);
            NBTTagList list = new NBTTagList();
            NBTTagCompound aspect = new NBTTagCompound();
            aspect.setString("key", "aer");
            aspect.setInteger("amount", 32);
            list.appendTag(aspect);
            furnace.setTag("Aspects", list);
            nonJarRejects(furnace, "reviewed " + id + " contents cannot be restored for free");
            furnace.setTag("Aspects", new NBTTagList());
            SimpleAssert.that(
                HologramBlueprintResources.plan(furnace).essentia.isEmpty(),
                "explicitly empty contents are allowed");
        }
        NBTTagCompound unrelated = new NBTTagCompound();
        unrelated.setString("id", "fixture:configuration");
        unrelated.setInteger("amount", 32);
        SimpleAssert.that(
            HologramBlueprintResources.plan(unrelated).essentia.isEmpty(),
            "guard never interprets arbitrary configuration names as physical aspects");
    }

    private static void nonJarRejects(NBTTagCompound payload, String reason) throws Exception {
        NBTTagCompound before = (NBTTagCompound) payload.copy();
        try {
            HologramBlueprintResources.plan(payload);
            throw new AssertionError(reason);
        } catch (IOException expected) {
            SimpleAssert.that(payload.equals(before), reason + "; source remains unchanged");
        }
    }

    private static void shortageAndRollback() {
        Monitor monitor = new Monitor();
        List<IAEStack<?>> receipts = new ArrayList<>();
        SimpleAssert.eq(32L, HologramEssentia.quote(monitor, "aer", 32), "quotation uses simulation");
        SimpleAssert.eq(64L, monitor.stored, "simulation leaves physical stock unchanged");
        SimpleAssert.that(!HologramEssentia.reserve(monitor, "aer", 65, receipts::add), "shortage blocks modulation");
        SimpleAssert.eq(0, monitor.modulations, "failed preflight never extracts");
        monitor.actualLimit = 7;
        SimpleAssert.that(
            !HologramEssentia.reserve(monitor, "aer", 32, receipts::add),
            "a changed monitor cannot satisfy a reservation with seven points");
        SimpleAssert.eq(57L, monitor.stored, "the actual extraction is seven points");
        SimpleAssert.eq(
            7L,
            receipts.get(0)
                .getStackSize(),
            "rollback journals the actual extraction");
        monitor.insertionLimit = 3;
        HologramEssentia.refund(monitor, receipts.get(0));
        SimpleAssert.eq(60L, monitor.stored, "first rollback restores only accepted points");
        SimpleAssert.eq(
            4L,
            receipts.get(0)
                .getStackSize(),
            "unaccepted points remain recoverable");
        monitor.enabled = false;
        HologramEssentia.refund(monitor, receipts.get(0));
        SimpleAssert.eq(
            4L,
            receipts.get(0)
                .getStackSize(),
            "unavailable networks cannot discard refunds");
        monitor.enabled = true;
        monitor.insertionLimit = Long.MAX_VALUE;
        HologramEssentia.refund(monitor, receipts.get(0));
        HologramEssentia.refund(monitor, receipts.get(0));
        SimpleAssert.eq(64L, monitor.stored, "repeated refund cannot duplicate restored points");
        SimpleAssert.eq(
            0L,
            receipts.get(0)
                .getStackSize(),
            "completed receipt is drained");
    }

    private static void chargingFailure() {
        Monitor monitor = new Monitor();
        monitor.failCharge = true;
        List<IAEStack<?>> receipts = new ArrayList<>();
        try {
            HologramEssentia.reserve(monitor, "aer", 32, receipts::add);
            throw new AssertionError("charge failure must reject placement");
        } catch (IllegalStateException expected) {
            SimpleAssert.eq(32L, monitor.stored, "points were actually extracted before charge failure");
            SimpleAssert.eq(
                32L,
                receipts.get(0)
                    .getStackSize(),
                "payment survives a later charge exception");
        }
        HologramEssentia.refund(monitor, receipts.get(0));
        SimpleAssert.eq(64L, monitor.stored, "all actual points return after the failed fee");
    }

    private static void aspectMismatch() {
        Monitor monitor = new Monitor();
        monitor.wrongActualAspect = true;
        List<IAEStack<?>> receipts = new ArrayList<>();
        try {
            HologramEssentia.reserve(monitor, "aer", 8, receipts::add);
            throw new AssertionError("a different aspect cannot pay for air");
        } catch (IllegalStateException expected) {
            SimpleAssert.eq(
                "ignis",
                Stack.handler(receipts.get(0)).aspect,
                "the wrong actual aspect is journaled for return, never relabeled");
        }
    }

    private static final class Monitor implements HologramEssentia.Access {

        long stored = 64, actualLimit = Long.MAX_VALUE, insertionLimit = Long.MAX_VALUE;
        int modulations;
        boolean enabled = true, failCharge, wrongActualAspect;

        @Override
        public boolean available() {
            return enabled;
        }

        @Override
        public IAEStack<?> request(String aspect, long amount) {
            return Stack.create(aspect, amount);
        }

        @Override
        public long poweredCount(IAEStack<?> request) {
            return request.getStackSize();
        }

        @Override
        public IAEStack<?> extract(IAEStack<?> request, Actionable mode) {
            long amount = Math.min(stored, request.getStackSize());
            if (mode == Actionable.MODULATE) {
                modulations++;
                amount = Math.min(amount, actualLimit);
                stored -= amount;
            }
            return Stack.create(
                mode == Actionable.MODULATE && wrongActualAspect ? "ignis" : Stack.handler(request).aspect,
                amount);
        }

        @Override
        public IAEStack<?> inject(IAEStack<?> request) {
            long accepted = Math.min(request.getStackSize(), insertionLimit);
            stored += accepted;
            return accepted == request.getStackSize() ? null
                : Stack.create(Stack.handler(request).aspect, request.getStackSize() - accepted);
        }

        @Override
        public void charge(long amount, int unit) {
            if (failCharge) throw new IllegalStateException("fixture fee source changed");
        }
    }

    private static final class Stack implements InvocationHandler {

        static final IAEStackType<?> TYPE = (IAEStackType<?>) Proxy.newProxyInstance(
            HologramEssentiaRegression.class.getClassLoader(),
            new Class<?>[] { IAEStackType.class },
            (proxy, method, args) -> method.getName()
                .equals("getId") ? "essentia" : null);
        final String aspect;
        long amount;

        Stack(String aspect, long amount) {
            this.aspect = aspect;
            this.amount = amount;
        }

        static IAEStack<?> create(String aspect, long amount) {
            return (IAEStack<?>) Proxy.newProxyInstance(
                HologramEssentiaRegression.class.getClassLoader(),
                new Class<?>[] { IAEStack.class },
                new Stack(aspect, amount));
        }

        static Stack handler(IAEStack<?> stack) {
            return (Stack) Proxy.getInvocationHandler(stack);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getStackSize":
                    return amount;
                case "setStackSize":
                    amount = (Long) args[0];
                    return proxy;
                case "copy":
                    return create(aspect, amount);
                case "getStackType":
                    return TYPE;
                case "getAmountPerUnit":
                    return 1;
                case "isSameType":
                    return args[0] instanceof IAEStack<?> && aspect.equals(handler((IAEStack<?>) args[0]).aspect);
                case "toString":
                    return aspect + ":" + amount;
                case "hashCode":
                    return aspect.hashCode();
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }
    }
}
