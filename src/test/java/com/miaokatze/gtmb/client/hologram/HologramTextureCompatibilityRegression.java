package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Executes the supported GT5U .265 public API, six-facing inventory fallback and native callback failure contracts. */
public final class HologramTextureCompatibilityRegression {

    // GT's inventory model faces WEST. Rows are world front DOWN/UP/NORTH/SOUTH/WEST/EAST;
    // columns are world sides in the same order, with their canonical inventory-model source face.
    private static final int[][] EXPECTED_INVENTORY_SIDES = { { 4, 5, 1, 0, 3, 2 }, { 5, 4, 0, 1, 3, 2 },
        { 0, 1, 4, 5, 3, 2 }, { 0, 1, 5, 4, 2, 3 }, { 0, 1, 2, 3, 4, 5 }, { 0, 1, 3, 2, 5, 4 } };

    private HologramTextureCompatibilityRegression() {}

    public static void run() {
        try {
            modernOverride();
            nullInventoryFallback();
            callbackFailures();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failure.printStackTrace();
            throw new AssertionError(failure);
        }
    }

    private static void modernOverride() throws ReflectiveOperationException {
        Method getter = IMetaTileEntity.class.getMethod("getInventoryTextures");
        SimpleAssert
            .that(getter.isDefault(), "supported GT .265 declares the public inventory getter as a default method");
        SimpleAssert
            .eq(ITexture[][].class, getter.getReturnType(), "public getter returns native six-face texture layers");
        MachineProbe probe = new MachineProbe();
        probe.inventory = faces();
        IMetaTileEntity machine = (IMetaTileEntity) probe.proxy(IMetaTileEntity.class);
        int[] sources = identity();
        SimpleAssert.that(
            HologramMachineTextures.resolve(machine, -1, sources) == probe.inventory,
            "new API keeps the exact custom inventory model");
        SimpleAssert.eq(1, probe.getterCalls, "public getter delegates to the machine override");
        SimpleAssert.eq(0, probe.legacyCalls, "valid override avoids legacy reconstruction");
        for (int front = 0; front < 6; front++) {
            probe.world = faces();
            sources = identity();
            ITexture[][] resolved = HologramMachineTextures.resolve(machine, front, sources);
            for (int side = 0; side < 6; side++) {
                SimpleAssert.that(resolved[side] == probe.world[side], "world callback retains all overlay layers");
                SimpleAssert.eq(side, sources[side], "world callback retains the world-side icon orientation");
                SimpleAssert.eq(front, probe.worldFronts[side], "world callback receives the actual facing");
            }
        }
        SimpleAssert.eq(0, probe.legacyCalls, "world-side resolution also keeps the new inventory override");
    }

    private static void nullInventoryFallback() {
        MachineProbe probe = new MachineProbe();
        IMetaTileEntity machine = (IMetaTileEntity) probe.proxy(IMetaTileEntity.class);
        ITexture[][] inventory = HologramMachineTextures.resolve(machine, -1, identity());
        for (int side = 0; side < 6; side++) SimpleAssert
            .that(inventory[side] == probe.legacy[side], "null native model retains every fallback layer array");
        SimpleAssert.eq(1, probe.getterCalls, "fallback follows an actual call to the supported public getter");
        SimpleAssert.eq(6, probe.legacyCalls, "null model reconstructs all six canonical WEST-front inventory faces");
        for (int front = 0; front < 6; front++) {
            int[] sources = identity();
            ITexture[][] resolved = HologramMachineTextures.resolve(machine, front, sources);
            for (int side = 0; side < 6; side++) {
                int source = EXPECTED_INVENTORY_SIDES[front][side];
                SimpleAssert.that(
                    resolved[side] == probe.legacy[source],
                    "each world facing preserves the expected inventory base and overlay layers");
                SimpleAssert.eq(source, sources[side], "fallback icon source follows the canonical six-facing mapping");
                SimpleAssert.eq(front, probe.worldFronts[side], "world callback still receives the actual world front");
            }
            SimpleAssert.that(
                resolved[front] == probe.legacy[ForgeDirection.WEST.ordinal()],
                "canonical WEST inventory front follows every world front");
        }
        SimpleAssert.eq(7, probe.getterCalls, "a null model is not cached as an absent interface capability");
        probe.inventory = faces();
        SimpleAssert.that(
            HologramMachineTextures.resolve(machine, -1, identity()) == probe.inventory,
            "a subsequent valid native model replaces the null-model fallback immediately");
        SimpleAssert.eq(8, probe.getterCalls, "later resolves continue calling the supported public API");
    }

    private static void callbackFailures() {
        MachineProbe probe = new MachineProbe();
        IMetaTileEntity machine = (IMetaTileEntity) probe.proxy(IMetaTileEntity.class);
        probe.getterFailure = new IllegalStateException("model needs world");
        probe.legacyFailureSide = 0;
        ITexture[][] inventory = HologramMachineTextures.resolve(machine, -1, identity());
        SimpleAssert.that(inventory[0] == null, "failed legacy face remains eligible for block-icon fallback");
        for (int side = 1; side < 6; side++) {
            SimpleAssert
                .that(inventory[side] == probe.legacy[side], "one failed legacy face does not hide other faces");
        }
        probe.getterFailure = null;
        probe.inventory = faces();
        probe.world = faces();
        probe.worldFailureSide = 2;
        probe.world[4] = new ITexture[0];
        int[] sources = identity();
        ITexture[][] resolved = HologramMachineTextures.resolve(machine, 3, sources);
        for (int side = 0; side < 6; side++) {
            int source = EXPECTED_INVENTORY_SIDES[3][side];
            if (side == 2 || side == 4) {
                SimpleAssert
                    .that(resolved[side] == probe.inventory[source], "failed or empty world face uses inventory");
                SimpleAssert.eq(source, sources[side], "fallback icon orientation uses the inventory side");
            } else {
                SimpleAssert.that(resolved[side] == probe.world[side], "other world faces keep overlay layers");
            }
        }
        AssertionError fatal = new AssertionError("texture invariant failed");
        probe.getterFailure = fatal;
        boolean propagated = false;
        try {
            HologramMachineTextures.resolve(machine, -1, identity());
        } catch (AssertionError actual) {
            propagated = actual == fatal;
        }
        SimpleAssert.that(propagated, "serious inventory callback errors are not hidden by fallback");
        probe.getterFailure = null;
        probe.worldFailure = fatal;
        propagated = false;
        try {
            HologramMachineTextures.resolve(machine, 3, identity());
        } catch (AssertionError actual) {
            propagated = actual == fatal;
        }
        SimpleAssert.that(propagated, "serious world callback errors are not hidden by fallback");
    }

    private static int[] identity() {
        return new int[] { 0, 1, 2, 3, 4, 5 };
    }

    private static ITexture[][] faces() {
        ITexture[][] faces = new ITexture[6][];
        // Distinct layer arrays prove that both base and overlay slots survive resolution unchanged.
        for (int side = 0; side < 6; side++) faces[side] = new ITexture[2];
        return faces;
    }

    private static final class MachineProbe implements InvocationHandler {

        final ITexture[][] legacy = faces();
        final int[] worldFronts = new int[6];
        ITexture[][] inventory, world;
        Throwable getterFailure;
        Error worldFailure;
        int legacyCalls, getterCalls;
        int legacyFailureSide = -1, worldFailureSide = -1;

        Object proxy(Class<?> type) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getInventoryTextures":
                    getterCalls++;
                    if (getterFailure != null) throw getterFailure;
                    return inventory;
                case "getBaseMetaTileEntity":
                    return null;
                case "getTexture":
                    int side = ((ForgeDirection) args[1]).ordinal();
                    SimpleAssert.eq(null, args[0], "test prototype is read without a fabricated base tile");
                    SimpleAssert.eq(-1, args[3], "inventory and world calls use neutral color");
                    SimpleAssert.eq(false, args[5], "inventory and world calls are not redstone-active");
                    if ((boolean) args[4]) {
                        legacyCalls++;
                        SimpleAssert.eq(ForgeDirection.WEST, args[2], "legacy inventory uses GT's WEST front");
                        if (side == legacyFailureSide) throw new IllegalStateException("legacy face needs world");
                        return legacy[side];
                    }
                    worldFronts[side] = ((ForgeDirection) args[2]).ordinal();
                    if (worldFailure != null) throw worldFailure;
                    if (side == worldFailureSide) throw new IllegalStateException("world face needs tile");
                    return world == null ? null : world[side];
                default:
                    throw new AssertionError("Unexpected prototype operation: " + method.getName());
            }
        }
    }

}
