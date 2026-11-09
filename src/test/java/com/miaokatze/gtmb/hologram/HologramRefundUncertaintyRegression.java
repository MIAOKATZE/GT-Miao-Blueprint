package com.miaokatze.gtmb.hologram;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

import appeng.api.config.Actionable;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;

/** Native mutation-before-throw fixtures prove that ordinary refunds retain quarantine across a save. */
public final class HologramRefundUncertaintyRegression {

    private static final String UNCERTAIN = "gtmbRecoveryInjectionUncertain";

    private HologramRefundUncertaintyRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramRefundUncertaintyRegression.class, args)) return;
        run();
        System.out.println("HologramRefundUncertaintyRegression PASS");
    }

    public static void run() {
        try (AutoCloseable scope = HologramDemolitionDestinationRegression.nativeClassScope()) {
            itemInjectionUnknown();
            fluidInjectionUnknownAndKnownChargeFailure();
        } catch (Exception failure) {
            throw new AssertionError("Ordinary refund uncertainty", failure);
        }
    }

    private static void itemInjectionUnknown() throws Exception {
        ItemStack stack = new ItemStack(Items.stick, 10);
        NBTTagCompound receipt = HologramMaterials.itemReceipt(stack);
        int[] accepted = { 0 };
        expectFailure(() -> HologramMaterials.refundItem(stack, item(stack, stack.stackSize), receipt, request -> {
            accepted[0] += 4;
            throw new IllegalStateException("native accepted four, then listener threw");
        }));
        require(
            accepted[0] == 4 && stack.stackSize == 10 && receipt.hasKey(UNCERTAIN),
            "An unacknowledged native effect must keep its original evidence quarantined");
        NBTTagCompound reloaded = saved(receipt);
        expectFailure(() -> HologramMaterials.refundItem(stack, item(stack, stack.stackSize), reloaded, request -> {
            accepted[0] += 10;
            return null;
        }));
        require(
            accepted[0] == 4 && reloaded.getInteger("gtmbRecoveryCount") == 10,
            "A saved unknown refund may not inject its original amount again");
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        fixture.player.inventory.mainInventory[1] = null;
        reloaded.setBoolean("gtmbRecoveryMeFirst", true);
        java.lang.reflect.Method enqueue = HologramMaterials.Context.class
            .getDeclaredMethod("enqueueRecovery", NBTTagCompound.class);
        enqueue.setAccessible(true);
        enqueue.invoke(fixture.context, reloaded);
        enqueue.invoke(fixture.context, reloaded.copy());
        expectFailure(fixture.context::flushRecovery);
        require(
            fixture.pending() == 20 && fixture.player.inventory.mainInventory[1] == null,
            "Ordinary persisted recovery must quarantine unknown injection instead of falling back to the backpack");
        require(
            fixture.player.getEntityData()
                .getCompoundTag(net.minecraft.entity.player.EntityPlayer.PERSISTED_NBT_TAG)
                .getTagList("gtmbHologramMaterialRecovery", 10)
                .tagCount() == 2,
            "Unknown operations must retain separate receipts instead of merging ambiguous evidence");

        ItemStack partial = new ItemStack(Items.stick, 10);
        NBTTagCompound known = HologramMaterials.itemReceipt(partial);
        int inserted = HologramMaterials.refundItem(partial, item(partial, partial.stackSize), known, request -> {
            request.setStackSize(6);
            return request;
        });
        require(
            inserted == 4 && partial.stackSize == 6
                && !known.hasKey(UNCERTAIN)
                && known.getInteger("gtmbRecoveryCount") == 6,
            "A trusted acknowledgement clears quarantine to its exact remainder");
    }

    private static void fluidInjectionUnknownAndKnownChargeFailure() throws Exception {
        Fluid fluid = new Fluid("gtmb_refund_uncertainty_fixture");
        if (!FluidRegistry.registerFluid(fluid)) fluid = FluidRegistry.getFluid(fluid.getName());
        FluidAccess access = new FluidAccess();
        IAEFluidStack stack = fluid(new FluidStack(fluid, 1), 1);
        stack.setStackSize(5_000_000_000L);
        NBTTagCompound receipt = HologramMaterials.fluidReceipt(stack);
        access.throwInject = true;
        expectFailure(() -> HologramMaterials.refundFluid(access, stack, receipt));
        require(
            access.accepted == 4 && receipt.getLong(UNCERTAIN) == 5_000_000_000L,
            "Unknown fluid quantity and its original long amount must remain exact");
        NBTTagCompound reloaded = saved(receipt);
        access.throwInject = false;
        expectFailure(() -> HologramMaterials.refundFluid(access, stack, reloaded));
        require(
            access.accepted == 4 && reloaded.getLong("gtmbRecoveryAmount") == 5_000_000_000L,
            "Reloaded quarantine must refuse automatic fluid reinjection");

        IAEFluidStack known = stack.copy();
        known.setStackSize(10);
        NBTTagCompound knownReceipt = HologramMaterials.fluidReceipt(known);
        access.throwCharge = true;
        expectFailure(() -> HologramMaterials.refundFluid(access, known, knownReceipt));
        require(
            known.getStackSize() == 6 && knownReceipt.getLong("gtmbRecoveryAmount") == 6
                && !knownReceipt.hasKey(UNCERTAIN),
            "Charge failure after acknowledgement must preserve only the known remainder");
        access.throwCharge = false;
        access.acceptAll = true;
        HologramMaterials.refundFluid(access, known, knownReceipt);
        require(
            known.getStackSize() == 0 && access.accepted == 14,
            "A known charge failure retries only its remaining six units");
    }

    private static NBTTagCompound saved(NBTTagCompound receipt) throws Exception {
        return CompressedStreamTools
            .func_152457_a(CompressedStreamTools.compress(receipt), NBTSizeTracker.field_152451_a);
    }

    /** AE interfaces are explicit bounded providers; no initialized AE grid or storage implementation is claimed. */
    private static IAEItemStack item(ItemStack identity, long count) {
        long[] amount = { count };
        return (IAEItemStack) java.lang.reflect.Proxy.newProxyInstance(
            IAEItemStack.class.getClassLoader(),
            new Class<?>[] { IAEItemStack.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "copy":
                        return item(identity, amount[0]);
                    case "getStackSize":
                        return amount[0];
                    case "setStackSize":
                        amount[0] = (Long) args[0];
                        return proxy;
                    case "getAmountPerUnit":
                        return 1;
                    case "getItemStack": {
                        ItemStack result = identity.copy();
                        result.stackSize = (int) amount[0];
                        return result;
                    }
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
    }

    private static IAEFluidStack fluid(FluidStack identity, long count) {
        long[] amount = { count };
        return (IAEFluidStack) java.lang.reflect.Proxy.newProxyInstance(
            IAEFluidStack.class.getClassLoader(),
            new Class<?>[] { IAEFluidStack.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "copy":
                        return fluid(identity, amount[0]);
                    case "getStackSize":
                        return amount[0];
                    case "setStackSize":
                        amount[0] = (Long) args[0];
                        return proxy;
                    case "getAmountPerUnit":
                        return 1000;
                    case "getFluidStack":
                        return identity.copy();
                    case "equals":
                        return args[0] instanceof IAEFluidStack
                            && identity.isFluidEqual(((IAEFluidStack) args[0]).getFluidStack());
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected native/quarantine failure");
        } catch (IllegalStateException expected) {}
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class FluidAccess implements HologramMaterials.FluidAccess {

        long accepted;
        boolean throwInject, throwCharge, acceptAll;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public long poweredCount(IAEFluidStack stack) {
            return stack.getStackSize();
        }

        @Override
        public IAEFluidStack extract(IAEFluidStack stack, Actionable mode) {
            throw new AssertionError("Refunds must not extract");
        }

        @Override
        public IAEFluidStack inject(IAEFluidStack stack) {
            long amount = acceptAll ? stack.getStackSize() : Math.min(4, stack.getStackSize());
            accepted += amount;
            if (throwInject) throw new IllegalStateException("native accepted fluid, then listener threw");
            stack.setStackSize(stack.getStackSize() - amount);
            return stack.getStackSize() == 0 ? null : stack;
        }

        @Override
        public void charge(long amount, int unit) {
            if (throwCharge) throw new IllegalStateException("native energy charge threw after acknowledged injection");
        }
    }
}
