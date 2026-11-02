package com.miaokatze.gtmb.hologram;

import java.util.Collections;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import appeng.api.config.Actionable;
import appeng.api.storage.data.IAEItemStack;

/** Exact persisted policies and AE adapter capacity/acknowledgement protocol; no full native AE lifecycle claim. */
public final class HologramDemolitionDestinationRegression {

    private static final String DESTINATION = "gtmbDemolitionDestination";
    private static final String UNCERTAIN = "gtmbRecoveryInjectionUncertain";
    private static final String COUNT = "gtmbRecoveryCount";

    private HologramDemolitionDestinationRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramDemolitionDestinationRegression.class, args)) return;
        run();
        System.out.println("HologramDemolitionDestinationRegression PASS");
    }

    public static void run() {
        try {
            persistedPolicies();
            backpackAndUnavailableAe();
            oldAeReceiptDoesNotBlockBackpack();
            capacityAndPower();
            nativeAcknowledgements();
        } catch (Exception failure) {
            throw new AssertionError("Demolition destination regression", failure);
        }
    }

    /** Reuse the existing scoped real Loader/MUI prerequisites without changing their ownership or restoration. */
    static AutoCloseable nativeClassScope() throws Exception {
        Class<?> scope = Class.forName(HologramBlueprintResourcesRegression.class.getName() + "$RawNativeUiScope");
        java.lang.reflect.Method open = scope.getDeclaredMethod("open");
        open.setAccessible(true);
        return (AutoCloseable) open.invoke(null);
    }

    private static void persistedPolicies() throws ReflectiveOperationException {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        ItemStack nativeDrop = fixture.stack(1);
        for (int destination : new int[] { 0, 1 }) {
            fixture.context.recoverWorldAtPlayer(Collections.singletonList(nativeDrop), destination);
            fixture.context.recoverWorldAtPlayer(Collections.singletonList(nativeDrop), destination);
        }
        fixture.context.recoverWorldAtPlayer(Collections.singletonList(nativeDrop));
        NBTTagList queue = queue(fixture);
        SimpleAssert.eq(3, queue.tagCount(), "AE, backpack and old receipts retain separate merge identities");
        SimpleAssert.eq(
            0,
            queue.getCompoundTagAt(0)
                .getInteger(DESTINATION),
            "backpack survives persistence");
        SimpleAssert.eq(
            1,
            queue.getCompoundTagAt(1)
                .getInteger(DESTINATION),
            "AE survives persistence");
        SimpleAssert.that(
            !queue.getCompoundTagAt(2)
                .hasKey(DESTINATION),
            "legacy receipts keep their old schema");
        for (int index = 0; index < queue.tagCount(); index++) {
            NBTTagCompound row = queue.getCompoundTagAt(index);
            SimpleAssert.that(
                ItemStack.areItemStackTagsEqual(nativeDrop, HologramMaterials.recoveryItem(row)),
                "delivery labels never alter the native item NBT");
            SimpleAssert.eq(index == 2 ? 1 : 2, row.getInteger(COUNT), "same destination merges exact quantities");
        }
        ItemStack configured = nativeDrop.copy();
        configured.getTagCompound()
            .setString("native-mode", "different retained configuration");
        fixture.context.recoverWorldAtPlayer(Collections.singletonList(configured), 0);
        SimpleAssert.eq(4, queue.tagCount(), "different native configuration does not merge");
    }

    private static void backpackAndUnavailableAe() throws ReflectiveOperationException {
        HologramRecoveryDeliveryRegression.Fixture backpack = new HologramRecoveryDeliveryRegression.Fixture();
        backpack.player.inventory.mainInventory[1] = backpack.stack(60);
        backpack.context.recoverWorldAtPlayer(Collections.singletonList(backpack.stack(130)), 0);
        backpack.flushFully();
        SimpleAssert
            .eq(64, backpack.player.inventory.mainInventory[1].stackSize, "selected backpack receives its space");
        SimpleAssert.eq(126, backpack.dropped(), "only selected backpack capacity overflow reaches the feet");
        SimpleAssert.eq(0L, backpack.pending(), "backpack delivery acknowledges every exact native item once");
        backpack.verifyDrops();

        HologramRecoveryDeliveryRegression.Fixture unavailable = new HologramRecoveryDeliveryRegression.Fixture();
        unavailable.player.inventory.mainInventory[1] = null;
        unavailable.context.recoverWorldAtPlayer(Collections.singletonList(unavailable.stack(70)), 1);
        refuses(unavailable.context::flushRecovery, "unavailable AE stops delivery");
        SimpleAssert
            .that(unavailable.player.inventory.mainInventory[1] == null, "selected AE never falls into backpack");
        SimpleAssert.eq(0, unavailable.dropped(), "unavailable AE is not a full-network overflow");
        SimpleAssert.eq(70L, unavailable.pending(), "unavailable AE keeps committed drops in the real ledger");
        refuses(() -> unavailable.context.requireDemolitionDestination(1), "AE readiness fails before world work");
        unavailable.context.requireDemolitionDestination(0);
    }

    private static void capacityAndPower() throws ReflectiveOperationException {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        ItemStack stack = fixture.stack(130);
        NBTTagCompound receipt = HologramMaterials.itemReceipt(stack);
        Access partial = new Access(stack);
        partial.capacity = 64;
        HologramMaterials.deliverDemolitionToMe(partial, stack, receipt);
        SimpleAssert.eq(64, partial.stored, "usable AE receives exactly its real simulated capacity");
        SimpleAssert.eq(66, stack.stackSize, "only confirmed capacity overflow remains eligible for feet");
        SimpleAssert.eq(66, receipt.getInteger(COUNT), "known AE remainder is persisted before later callbacks");
        SimpleAssert.eq(64, partial.charged, "only actual acknowledged insertions are charged");

        ItemStack fullStack = fixture.stack(70);
        Access full = new Access(fullStack);
        full.capacity = 0;
        HologramMaterials.deliverDemolitionToMe(full, fullStack, HologramMaterials.itemReceipt(fullStack));
        SimpleAssert.eq(70, fullStack.stackSize, "a usable full AE leaves the exact overflow");
        SimpleAssert.eq(0, full.mutations, "a full-network simulation makes no insertion mutation");

        for (String reason : new String[] { "offline", "unpowered", "no INJECT permission", "out of range" }) {
            ItemStack retained = fixture.stack(70);
            Access unavailable = new Access(retained);
            unavailable.unavailable = reason;
            refuses(
                () -> HologramMaterials
                    .deliverDemolitionToMe(unavailable, retained, HologramMaterials.itemReceipt(retained)),
                reason + " never becomes capacity overflow");
            SimpleAssert.eq(70, retained.stackSize, "unavailable destinations retain the full known quantity");
            SimpleAssert.eq(0, unavailable.simulations, "unavailable AE is rejected before storage simulation");
        }
        ItemStack noPower = fixture.stack(70);
        Access insufficient = new Access(noPower);
        insufficient.capacity = 0;
        insufficient.power = 69;
        refuses(
            () -> HologramMaterials
                .deliverDemolitionToMe(insufficient, noPower, HologramMaterials.itemReceipt(noPower)),
            "insufficient transfer power cannot masquerade as full storage");
        SimpleAssert.eq(0, insufficient.simulations, "insufficient power never probes storage as a drop decision");
    }

    private static void oldAeReceiptDoesNotBlockBackpack() throws ReflectiveOperationException {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        fixture.context.recoverWorldAtPlayer(Collections.singletonList(fixture.stack(70)), 1);
        fixture.player.inventory.mainInventory[1] = null;
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.me = settings.containers = false;
        HologramMaterials.Context backpack = new HologramMaterials.Context(fixture.player, settings, 0, 0);
        for (int batch = 0; batch < 3; batch++) {
            backpack.recoverWorldAtPlayer(Collections.singletonList(fixture.stack(1)), 0);
            backpack.flushRecovery();
        }
        SimpleAssert.eq(
            3,
            fixture.player.inventory.mainInventory[1].stackSize,
            "new backpack work continues past an old unavailable AE receipt");
        SimpleAssert.eq(70L, fixture.pending(), "the old AE quantity remains intact and is never rerouted");
        SimpleAssert.eq(1, queue(fixture).tagCount(), "all current backpack receipts retire independently");
        SimpleAssert.eq(
            1,
            queue(fixture).getCompoundTagAt(0)
                .getInteger(DESTINATION),
            "the old AE policy survives new backpack work");
        SimpleAssert.eq(0, fixture.dropped(), "an unavailable old AE never produces backpack-path world drops");

        HologramRecoveryDeliveryRegression.Fixture mixed = new HologramRecoveryDeliveryRegression.Fixture();
        mixed.player.inventory.mainInventory[1] = null;
        mixed.context.recoverWorldAtPlayer(Collections.singletonList(mixed.stack(3)), 0);
        mixed.context.recoverWorldAtPlayer(Collections.singletonList(mixed.stack(70)), 1);
        mixed.player.inventory.inventoryChanged = false;
        refuses(
            mixed.context::flushRecovery,
            "background recovery reports unavailable AE after earlier backpack delivery");
        SimpleAssert.eq(
            3,
            mixed.player.inventory.mainInventory[1].stackSize,
            "a later destination failure cannot undo successful inventory delivery");
        SimpleAssert.that(
            mixed.player.inventory.inventoryChanged,
            "already delivered inventory is marked dirty even when a later receipt fails");
    }

    private static void nativeAcknowledgements() throws ReflectiveOperationException {
        HologramRecoveryDeliveryRegression.Fixture fixture = new HologramRecoveryDeliveryRegression.Fixture();
        ItemStack unknown = fixture.stack(130);
        NBTTagCompound uncertain = HologramMaterials.itemReceipt(unknown);
        Access lostAcknowledgement = new Access(unknown);
        lostAcknowledgement.capacity = 64;
        lostAcknowledgement.throwAfterInsertion = true;
        refuses(
            () -> HologramMaterials.deliverDemolitionToMe(lostAcknowledgement, unknown, uncertain),
            "native provider may throw after accepting items");
        SimpleAssert.eq(64, lostAcknowledgement.stored, "fixture actually accepted native items before failure");
        SimpleAssert.eq(64, uncertain.getInteger(UNCERTAIN), "unknown offer quantity is retained for review");
        refuses(
            () -> HologramMaterials.deliverDemolitionToMe(lostAcknowledgement, unknown, uncertain),
            "unknown acceptance is never blindly reoffered");
        SimpleAssert.eq(1, lostAcknowledgement.mutations, "retry cannot insert a second copy");

        ItemStack chargeFailure = fixture.stack(130);
        NBTTagCompound known = HologramMaterials.itemReceipt(chargeFailure);
        Access failedCharge = new Access(chargeFailure);
        failedCharge.capacity = 64;
        failedCharge.throwDuringCharge = true;
        refuses(
            () -> HologramMaterials.deliverDemolitionToMe(failedCharge, chargeFailure, known),
            "charging can fail after acknowledged insertion");
        SimpleAssert.eq(66, known.getInteger(COUNT), "a failed charge keeps only the confirmed real remainder");
        SimpleAssert.that(!known.hasKey(UNCERTAIN), "known insertion is not mislabeled as uncertain");
        failedCharge.throwDuringCharge = false;
        HologramMaterials.deliverDemolitionToMe(failedCharge, chargeFailure, known);
        SimpleAssert.eq(64, failedCharge.stored, "later delivery cannot refund already inserted items");

        ItemStack revokedStack = fixture.stack(130);
        NBTTagCompound revokedReceipt = HologramMaterials.itemReceipt(revokedStack);
        Access revoked = new Access(revokedStack);
        revoked.capacity = 64;
        revoked.revokeAfterInsertion = true;
        refuses(
            () -> HologramMaterials.deliverDemolitionToMe(revoked, revokedStack, revokedReceipt),
            "revoked permission after native insertion stops overflow delivery");
        SimpleAssert.eq(66, revokedReceipt.getInteger(COUNT), "revoked permission retains the known remainder");

        ItemStack malformedStack = fixture.stack(130);
        NBTTagCompound malformedReceipt = HologramMaterials.itemReceipt(malformedStack);
        Access malformed = new Access(malformedStack);
        malformed.wrongNativeIdentity = true;
        refuses(
            () -> HologramMaterials.deliverDemolitionToMe(malformed, malformedStack, malformedReceipt),
            "native remainder cannot change the retained item identity");
        SimpleAssert.that(malformedReceipt.hasKey(UNCERTAIN), "malformed native acknowledgement stays quarantined");
    }

    private static NBTTagList queue(HologramRecoveryDeliveryRegression.Fixture fixture) {
        return fixture.player.getEntityData()
            .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
            .getTagList("gtmbHologramMaterialRecovery", 10);
    }

    private static void refuses(Runnable callback, String message) {
        boolean refused = false;
        try {
            callback.run();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        SimpleAssert.that(refused, message);
    }

    private static final class Access implements HologramMaterials.DemolitionItemAccess {

        final ItemStack identity;
        int capacity = Integer.MAX_VALUE, power = Integer.MAX_VALUE, stored, charged, simulations, mutations;
        String unavailable;
        boolean throwAfterInsertion, throwDuringCharge, revokeAfterInsertion, wrongNativeIdentity;

        Access(ItemStack identity) {
            this.identity = identity.copy();
        }

        @Override
        public void requireAvailable() {
            if (unavailable != null) throw new IllegalStateException(unavailable);
        }

        @Override
        public IAEItemStack create(ItemStack stack) {
            try {
                java.lang.reflect.Method model = HologramMaterialQuoteRegression.class
                    .getDeclaredMethod("aeStackFixture", ItemStack.class, long.class);
                model.setAccessible(true);
                return (IAEItemStack) model.invoke(null, stack, (long) stack.stackSize);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("existing AE interface model", failure);
            }
        }

        @Override
        public int poweredCount(IAEItemStack stack) {
            return Math.min(power, (int) stack.getStackSize());
        }

        @Override
        public IAEItemStack inject(IAEItemStack offered, Actionable mode) {
            SimpleAssert.that(
                identity.isItemEqual(offered.getItemStack())
                    && ItemStack.areItemStackTagsEqual(identity, offered.getItemStack()),
                "AE receives the complete exact native item identity");
            int accepted = Math.min(capacity - stored, (int) offered.getStackSize());
            if (mode == Actionable.SIMULATE) simulations++;
            else {
                mutations++;
                stored += accepted;
                if (throwAfterInsertion) throw new IllegalStateException("acknowledgement lost after acceptance");
                if (revokeAfterInsertion) unavailable = "permission revoked";
                if (wrongNativeIdentity) {
                    ItemStack changed = identity.copy();
                    changed.getTagCompound()
                        .setString("fixture", "wrong native output identity");
                    return create(changed);
                }
            }
            return accepted == offered.getStackSize() ? null
                : offered.copy()
                    .setStackSize(offered.getStackSize() - accepted);
        }

        @Override
        public void charge(int amount, int unit) {
            if (throwDuringCharge) throw new IllegalStateException("native charge failed");
            charged += amount;
        }
    }
}
