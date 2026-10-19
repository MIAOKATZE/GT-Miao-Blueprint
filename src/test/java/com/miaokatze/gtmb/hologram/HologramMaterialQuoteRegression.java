package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

/** Wire identity, stock allocation and requester delivery boundaries; native network acceptance is separate. */
final class HologramMaterialQuoteRegression {

    private HologramMaterialQuoteRegression() {}

    static void run() {
        try {
            wireIdentityAndLongCounts();
            sharedFuzzyStockProtectsExactContents();
            actualFuzzyPaymentProtectsLaterExactMachine();
            coordinateQuotesAndActualPaymentSharePhysicalCards();
            boundToolSlotSurvivesHotbarSelection();
            voucherProjectionAndExactStockSnapshot();
            headlessAeLongArithmetic();
            boundedObservationGrowth();
            preparedQuotesUseObservedBudgetsAndFenceTheNextBlueprint();
            craftingDeliveryCannotFeedItsOwnUnfinishedCpu();
            craftingDeliveryFailurePreservesRealReceipts();
            creativeVoucherSelectionAndCommittedPlacement();
        } catch (Exception failure) {
            throw new AssertionError("Material quote regression", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void headlessAeLongArithmetic() {
        ItemStack identity = machine(9, 77);
        // AEItemStack initializes AEApi/Platform and therefore requires a real FML lifecycle.
        // Native AE equivalence and its original overflow are covered by the default-off FML helper.
        appeng.api.storage.data.IAEItemStack request = aeStackFixture(identity, Long.MAX_VALUE);
        long stock = 5_000_000_256L;
        int[] powerCalls = { 0 }, storageCalls = { 0 };
        double[] powerLimit = { 6_000_000_000d };
        java.math.BigInteger[] expectedFee = { java.math.BigInteger.valueOf(Long.MAX_VALUE) };
        appeng.api.networking.security.BaseActionSource source = new appeng.api.networking.security.BaseActionSource();
        appeng.api.networking.energy.IEnergySource power = (amount, mode, multiplier) -> {
            require(
                mode == appeng.api.config.Actionable.SIMULATE && multiplier == appeng.api.config.PowerMultiplier.CONFIG,
                "native quote power mode and multiplier");
            require(
                amount == expectedFee[0].doubleValue(),
                "native transfer fee agrees with independent BigInteger arithmetic");
            powerCalls[0]++;
            return Math.min(amount, powerLimit[0]);
        };
        appeng.api.storage.IMEInventory<appeng.api.storage.data.IAEItemStack> inventory = (appeng.api.storage.IMEInventory<appeng.api.storage.data.IAEItemStack>) java.lang.reflect.Proxy
            .newProxyInstance(
                HologramMaterialQuoteRegression.class.getClassLoader(),
                new Class<?>[] { appeng.api.storage.IMEInventory.class },
                (proxy, method, args) -> {
                    if (!"extractItems".equals(method.getName())) throw new AssertionError(method.getName());
                    require(
                        args[1] == appeng.api.config.Actionable.SIMULATE && args[2] == source,
                        "native long quote retains the authenticated source and never debits storage");
                    appeng.api.storage.data.IAEItemStack requested = (appeng.api.storage.data.IAEItemStack) args[0];
                    require(
                        identity.getItem() == requested.getItemStack()
                            .getItem() && identity.getItemDamage()
                                == requested.getItemStack()
                                    .getItemDamage()
                            && ItemStack.areItemStackTagsEqual(identity, requested.getItemStack()),
                        "native long simulation preserves complete item NBT");
                    storageCalls[0]++;
                    appeng.api.storage.data.IAEItemStack found = requested.copy();
                    found.setStackSize(Math.min(stock, requested.getStackSize()));
                    return found;
                });
        require(
            HologramMaterials.simulatePoweredExtraction(power, inventory, request, source)
                .getStackSize() == stock,
            "safe native long simulation handles Long.MAX_VALUE without truncating real five-billion stock");
        require(powerCalls[0] == 1 && storageCalls[0] == 1, "one quote performs one power and one storage simulation");
        require(
            request.getStackSize() == Long.MAX_VALUE,
            "simulation bounds a copy without changing the caller's demand");
        request.setStackSize(stock);
        expectedFee[0] = java.math.BigInteger.valueOf(stock);
        require(
            HologramMaterials.simulatePoweredExtraction(power, inventory, request, source)
                .getStackSize() == stock,
            "a bounded five-billion demand retains its full long stock count");
        powerLimit[0] = 17;
        require(
            HologramMaterials.simulatePoweredExtraction(power, inventory, request, source)
                .getStackSize() == 17,
            "an energy shortage still limits quoted item availability");
        powerLimit[0] = 0;
        require(
            HologramMaterials.simulatePoweredExtraction(power, inventory, request, source) == null && powerCalls[0] == 4
                && storageCalls[0] == 3,
            "zero simulated energy prevents any storage callback or debit");
        for (int unit : new int[] { 1, 1000 }) {
            java.math.BigInteger divisor = java.math.BigInteger.valueOf(unit);
            for (long amount : new long[] { 0, 1, 999, 1000, 1001, stock, Long.MAX_VALUE - 1, Long.MAX_VALUE }) {
                long expected = java.math.BigInteger.valueOf(amount)
                    .add(divisor.subtract(java.math.BigInteger.ONE))
                    .divide(divisor)
                    .longValueExact();
                require(
                    HologramMaterials.transferUnits(amount, unit) == expected,
                    "item and fluid transfer fees agree with an independent arbitrary-precision ceil oracle");
            }
        }
    }

    private static appeng.api.storage.data.IAEItemStack aeStackFixture(ItemStack identity, long initialSize) {
        ItemStack sample = identity.copy();
        long[] size = { initialSize };
        return (appeng.api.storage.data.IAEItemStack) java.lang.reflect.Proxy.newProxyInstance(
            HologramMaterialQuoteRegression.class.getClassLoader(),
            new Class<?>[] { appeng.api.storage.data.IAEItemStack.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getStackSize":
                        return size[0];
                    case "setStackSize":
                        size[0] = (Long) args[0];
                        return proxy;
                    case "getAmountPerUnit":
                        return 1;
                    case "copy":
                        return aeStackFixture(sample, size[0]);
                    case "getItemStack":
                        return sample.copy();
                    case "isSameType":
                        if (!(args[0] instanceof IAEItemStack)) return false;
                        ItemStack other = ((IAEItemStack) args[0]).getItemStack();
                        return sample.getItem() == other.getItem() && sample.getItemDamage() == other.getItemDamage()
                            && ItemStack.areItemStackTagsEqual(sample, other);
                    default:
                        throw new AssertionError("Unexpected AE interface fixture operation: " + method.getName());
                }
            });
    }

    private static void craftingDeliveryCannotFeedItsOwnUnfinishedCpu() throws Exception {
        CraftingDeliveryFixture oldSequence = new CraftingDeliveryFixture(64);
        oldSequence.produce(32);
        // Reproduce the removed flush-before-native-done sequence against the same production receipt code.
        oldSequence.flush();
        require(
            oldSequence.reentries == 1 && oldSequence.order.received == 64
                && oldSequence.order.pendingDelivery() == 0
                && oldSequence.stored == 0
                && oldSequence.poll()
                && oldSequence.order.done == 64,
            "the native-like boundary reproduces the old false completion by recycling the same physical 32 outputs");
        CraftingDeliveryFixture fixture = new CraftingDeliveryFixture(64);
        require(
            fixture.order.injectCraftedItems(fixture.link, fixture.output(32), Actionable.SIMULATE) == null
                && fixture.order.received == 0
                && fixture.order.pendingDelivery() == 0,
            "native simulation advertises capacity without acknowledging or persisting any real output");
        fixture.produce(32);
        for (int poll = 0; poll < 4; poll++) require(!fixture.poll(), "partial native production cannot finish a row");
        require(
            fixture.insertions == 0 && fixture.reentries == 0
                && fixture.stored == 0
                && fixture.order.received == 32
                && fixture.order.pendingDelivery() == 32
                && fixture.order.index == 0,
            "unfinished native output stays in the real persistent ledger instead of feeding the same CPU again");
        fixture.produce(32);
        fixture.completeNative();
        require(fixture.poll(), "native done plus the full real output permits normal storage delivery");
        require(
            fixture.insertions == 1 && fixture.reentries == 0
                && fixture.stored == 64
                && fixture.order.done == 64
                && fixture.order.index == 1
                && fixture.order.pendingDelivery() == 0,
            "one physical set of 64 outputs reaches storage exactly once before the row is credited");

        CraftingDeliveryFixture partial = new CraftingDeliveryFixture(64);
        partial.produce(64);
        partial.completeNative();
        partial.storageLimit = 48;
        require(
            !partial.poll() && partial.stored == 48
                && partial.order.pendingDelivery() == 16
                && "delivering".equals(partial.order.state)
                && partial.order.done == 0,
            "native storage remainders retain only the unpaid 16 and cannot prematurely complete a row");
        partial.storageLimit = Long.MAX_VALUE;
        require(
            partial.poll() && partial.stored == 64 && partial.insertions == 2 && partial.order.done == 64,
            "the next delivery offers only the real remainder, without duplicating the accepted 48");

        CraftingDeliveryFixture large = new CraftingDeliveryFixture(5_000_000_256L);
        large.produce(5_000_000_256L);
        require(
            !large.poll() && large.insertions == 0 && large.order.pendingDelivery() == 5_000_000_256L,
            "buffering native completion retains a multi-receipt long quantity without int or byte truncation");
        large.completeNative();
        boolean delivered = false;
        for (int poll = 0; poll < 4 && !delivered; poll++) delivered = large.poll();
        require(
            delivered && large.stored == 5_000_000_256L && large.order.done == 5_000_000_256L && large.insertions == 3,
            "three bounded native item receipts deliver the exact five-billion quantity once");
    }

    private static void craftingDeliveryFailurePreservesRealReceipts() throws Exception {
        CraftingDeliveryFixture cancelled = new CraftingDeliveryFixture(64);
        cancelled.produce(32);
        cancelled.nativeCancelled = true;
        cancelled.order.jobStateChange(cancelled.link);
        require(
            !cancelled.poll() && "cancelled".equals(cancelled.order.state)
                && cancelled.insertions == 0
                && cancelled.recoveryAmount("gtmbHologramMaterialRecovery") == 32,
            "cancelled native jobs never flush into an unfinished CPU and preserve their real partial output");
        CraftingDeliveryFixture shortOutput = new CraftingDeliveryFixture(64);
        shortOutput.produce(32);
        shortOutput.completeNative();
        require(
            !shortOutput.poll() && "failed".equals(shortOutput.order.state)
                && shortOutput.insertions == 0
                && shortOutput.recoveryAmount("gtmbHologramMaterialRecovery") == 32,
            "native completion without all real outputs fails and preserves the actual 32 instead of minting 64");
        CraftingDeliveryFixture preparationFailure = new CraftingDeliveryFixture(64);
        preparationFailure.produce(64);
        preparationFailure.completeNative();
        preparationFailure.throwDuringPreparation = true;
        boolean preparationEscaped = false;
        try {
            preparationFailure.poll();
        } catch (IllegalStateException expected) {
            require(
                "Native monitor preparation failed".equals(expected.getMessage()),
                "the preparation failure is real");
            preparationEscaped = true;
            // Match the server tick's existing poll-exception -> finish -> ordinary receipt release path.
            preparationFailure.order.finish("failed", "Native delivery preparation failed", true);
        }
        require(
            preparationEscaped && "failed".equals(preparationFailure.order.state)
                && preparationFailure.insertions == 0
                && preparationFailure.stored == 0
                && preparationFailure.order.done == 0
                && preparationFailure.recoveryAmount("gtmbHologramMaterialRecovery") == 64
                && preparationFailure.recoveryAmount("gtmbHologramCraftingUncertainDelivery") == 0,
            "failure before any native insertion keeps the actual 64 in ordinary recovery instead of quarantining them");
        CraftingDeliveryFixture uncertain = new CraftingDeliveryFixture(64);
        uncertain.produce(64);
        uncertain.completeNative();
        uncertain.throwAfterInsertion = true;
        require(
            !uncertain.poll() && "failed".equals(uncertain.order.state)
                && uncertain.order.done == 0
                && uncertain.order.index == 0
                && uncertain.stored == 64
                && uncertain.recoveryAmount("gtmbHologramCraftingUncertainDelivery") == 64
                && uncertain.recoveryAmount("gtmbHologramMaterialRecovery") == 0,
            "a native insertion that throws after accepting is quarantined, never mistaken for pending-zero success");
        uncertain.flush();
        require(
            uncertain.insertions == 1 && uncertain.stored == 64,
            "an unacknowledged insertion is never automatically reoffered and cannot duplicate physical output");
    }

    /** Models AE1080's synchronous CPU -> requester callback before link completion, not a native-network claim. */
    private static final class CraftingDeliveryFixture {

        final ItemStack identity = machine(14950, 73);
        final EntityPlayerMP player;
        final PlayerSource source;
        final ICraftingLink link;
        final HologramAeCraftingOrders.Order order;
        final IMEMonitor<IAEItemStack> monitor;
        boolean nativeDone, nativeCancelled, throwAfterInsertion, throwDuringPreparation;
        int insertions, reentries;
        long stored, storageLimit = Long.MAX_VALUE;

        @SuppressWarnings("unchecked")
        CraftingDeliveryFixture(long requested) throws Exception {
            player = materialPlayer();
            source = new PlayerSource(player, null);
            link = (ICraftingLink) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { ICraftingLink.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isDone":
                            return nativeDone;
                        case "isCanceled":
                            return nativeCancelled;
                        case "cancel":
                            nativeCancelled = true;
                            return null;
                        default:
                            throw new AssertionError("Unexpected native link boundary: " + method.getName());
                    }
                });
            order = new HologramAeCraftingOrders.Order(
                player,
                null,
                null,
                new NBTTagCompound(),
                Collections.singletonList(new HologramAeCraftingOrders.Request(output(requested), "exact output")));
            order.link = link;
            order.state = "crafting";
            monitor = (IMEMonitor<IAEItemStack>) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IMEMonitor.class },
                (proxy, method, args) -> {
                    if (!"injectItems".equals(method.getName())) throw new AssertionError(method.getName());
                    require(
                        args[1] == Actionable.MODULATE && args[2] == source && source.player == player,
                        "delivery retains the actual player action source");
                    IAEItemStack offered = (IAEItemStack) args[0];
                    require(output(1).isSameType((Object) offered), "delivery retains the complete native item NBT");
                    insertions++;
                    if (!nativeDone) {
                        // AE's crafting inventory has first priority and can feed this old output to its own CPU.
                        reentries++;
                        IAEStack<?> remainder = order.injectCraftedItems(link, offered, Actionable.MODULATE);
                        completeNative();
                        return remainder;
                    }
                    long accepted = Math.min(storageLimit, offered.getStackSize());
                    stored = Math.addExact(stored, accepted);
                    if (throwAfterInsertion)
                        throw new IllegalStateException("Native acknowledgement lost after insert");
                    return accepted == offered.getStackSize() ? null
                        : offered.copy()
                            .setStackSize(offered.getStackSize() - accepted);
                });
        }

        IAEItemStack output(long count) {
            return aeStackFixture(identity, count);
        }

        void produce(long count) {
            require(
                order.injectCraftedItems(link, output(count), Actionable.MODULATE) == null,
                "the production requester accepts only the real native batch into its durable ledger");
        }

        void completeNative() {
            nativeDone = true;
            order.jobStateChange(link);
        }

        void flush() {
            order.flushDeliveries((receipt, offered) -> {
                ItemStack sample = HologramMaterials.recoveryItem(receipt);
                if (throwDuringPreparation) throw new IllegalStateException("Native monitor preparation failed");
                return () -> {
                    IAEItemStack remainder = monitor
                        .injectItems(aeStackFixture(sample, offered), Actionable.MODULATE, source);
                    return remainder == null ? 0 : remainder.getStackSize();
                };
            });
        }

        boolean poll() {
            return order.pollDelivery(this::flush);
        }

        long recoveryAmount(String key) {
            NBTTagList ledger = player.getEntityData()
                .getCompoundTag(net.minecraft.entity.player.EntityPlayer.PERSISTED_NBT_TAG)
                .getTagList(key, 10);
            long amount = 0;
            for (int i = 0; i < ledger.tagCount(); i++) amount += ledger.getCompoundTagAt(i)
                .getInteger("gtmbRecoveryCount");
            return amount;
        }
    }

    private static void boundedObservationGrowth() {
        int[] reads = { 0 };
        long stock = 5_000_000_256L;
        ItemStack tagged = machine(9, 1), other = machine(9, 2), plain = new ItemStack(Items.stick, 1, 9);
        HologramMaterialQuote.StockSnapshot snapshot = new HologramMaterialQuote.StockSnapshot(
            new HologramMaterialQuote.ItemStock() {

                public long exact(ItemStack item, long limit) {
                    require(limit <= 5_000_000_400L, "stock observation uses the real bounded demand");
                    reads[0]++;
                    return Math.min(limit, item.hasTagCompound() ? stock : 2);
                }

                public long fuzzy(ItemStack item, long limit) {
                    return exact(item, limit);
                }
            });
        require(
            snapshot.exact(tagged, 1) == 1 && snapshot.exact(tagged, 5_000_000_000L) == 5_000_000_000L,
            "a first one-item observation cannot cap a later five-billion demand");
        require(
            snapshot.exact(tagged, 5_000_000_400L) == stock && snapshot.exact(tagged, Long.MAX_VALUE) == stock,
            "an exhausted observation remains reusable without repeating or adding storage simulations");
        require(reads[0] == 3, "growth replaces capped observations rather than adding them");
        require(
            snapshot.exact(other, 1) == 1 && snapshot.exact(plain, 3) == 2 && reads[0] == 5,
            "different complete NBT and no-NBT identities never share the observation cache");
        boolean refused = false;
        try {
            snapshot.cachedItems()
                .exact(other, 2);
        } catch (IllegalStateException missing) {
            refused = true;
        }
        require(refused && reads[0] == 5, "allocation cannot silently observe a larger unprepared stock budget");
    }

    private static void preparedQuotesUseObservedBudgetsAndFenceTheNextBlueprint() throws Exception {
        EntityPlayerMP player = materialPlayer();
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) ((sun.misc.Unsafe) unsafeField.get(null))
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        player.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
        player.inventory.mainInventory[0] = new ItemStack(Items.feather);
        HologramSession session = new HologramSession(player, 0, 64, 0, false, 1);
        session.virtual = session.virtualPlacement = true;
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.schema = 2;
        session.virtualPreset.deidentificationLevel = 1;
        session.capture = new HologramCapture(null, new ItemStack(Items.feather));
        NBTTagList normal = new NBTTagList();
        NBTTagCompound shell = legacy(machine(9, 1), 6, 0, 6), exact = legacy(machine(9, 2), 5, 0, 0);
        shell.setLong("voucherShellRequired", 6);
        normal.appendTag(shell);
        normal.appendTag(exact);
        int[] reads = { 0 };
        HologramMaterialQuote.ItemStock source = new HologramMaterialQuote.ItemStock() {

            public long exact(ItemStack item, long limit) {
                reads[0]++;
                require(limit <= 11, "prepared exact inventory limit follows the aggregate demand");
                return Math.min(limit, 4);
            }

            public long fuzzy(ItemStack item, long limit) {
                reads[0]++;
                require(limit <= 11, "prepared shell inventory limit follows the aggregate demand");
                return Math.min(limit, 7);
            }
        };
        HologramMaterialQuote.Preparation preparation = new HologramMaterialQuote.Preparation(
            session,
            normal,
            HologramMaterialQuote.voucherRows(normal),
            new NBTTagList(),
            new HologramMaterialQuote.StockSnapshot(source));
        long completed = 0;
        while (!preparation.done()) {
            int before = reads[0];
            preparation.step(1, Long.MAX_VALUE);
            require(reads[0] - before <= 1, "a quote preparation unit performs at most one native inventory query");
            require(preparation.completedWork() > completed, "quote work progress never resets between ledgers");
            completed = preparation.completedWork();
        }
        require(
            reads[0] == 2 && shell.getLong("available") == 3 && exact.getLong("available") == 4,
            "actual shortage allocation shares bounded stock and protects the later exact-NBT bill");
        NBTTagCompound first = preparation.result();
        HologramMaterialQuote.Cache previous = session.materialQuoteCache;
        for (int i = 0; i < 10; i++) {
            session.jobVoucherRequested = i % 2 == 0;
            HologramMaterialQuote.project(session);
        }
        require(
            reads[0] == 2 && session.materialQuoteCache == previous,
            "ten voucher projections neither rescan inventory nor replace the demand cache");
        HologramMaterialQuote.Preparation stale = new HologramMaterialQuote.Preparation(
            session,
            normal,
            HologramMaterialQuote.voucherRows(normal),
            new NBTTagList(),
            new HologramMaterialQuote.StockSnapshot(source));
        stale.step(1, Long.MAX_VALUE);
        session.job = 3;
        session.capture = new HologramCapture(null, new ItemStack(Items.feather));
        session.planRevision++;
        require(
            !HologramMaterialQuote.cached(session),
            "completed construction cannot lend its stock cache to a new capture");
        boolean retired = false;
        try {
            stale.step(1, Long.MAX_VALUE);
        } catch (IllegalStateException correct) {
            retired = true;
        }
        require(
            retired && reads[0] == 2,
            "obsolete quote preparation cannot observe or publish the replacement blueprint");
        session.job = 0;
        session.jobVoucherRequested = false;
        NBTTagList next = new NBTTagList();
        next.appendTag(legacy(machine(9, 2), 2, 0, 0));
        HologramMaterialQuote.ItemStock empty = new HologramMaterialQuote.ItemStock() {

            public long exact(ItemStack item, long limit) {
                return 0;
            }

            public long fuzzy(ItemStack item, long limit) {
                return 0;
            }
        };
        NBTTagCompound second = HologramMaterialQuote.snapshot(
            session,
            next,
            HologramMaterialQuote.voucherRows(next),
            new NBTTagList(),
            new HologramMaterialQuote.StockSnapshot(empty));
        require(
            session.materialQuoteCache != previous && HologramMaterialQuote.cached(session)
                && second.getTagList("rows", 10)
                    .getCompoundTagAt(0)
                    .getLong("available") == 0,
            "the next blueprint observes its own empty inventory instead of reusing the previous stock");
        require(
            first.getTagList("rows", 10)
                .getCompoundTagAt(0)
                .getLong("available") == 3,
            "the previous prepared result remains detached after the next blueprint publishes");
    }

    private static void voucherProjectionAndExactStockSnapshot() {
        ItemStack a = machine(9, 1), b = machine(9, 2);
        NBTTagCompound row = legacy(a, 7, 0, 5);
        row.setLong("voucherShellRequired", 5);
        NBTTagList normal = new NBTTagList();
        normal.appendTag(row);
        normal.appendTag(legacy(b, 3, 0, 0));
        NBTTagList voucher = HologramMaterialQuote.voucherRows(normal);
        require(
            voucher.getCompoundTagAt(0)
                .getLong("required") == 2
                && voucher.getCompoundTagAt(0)
                    .getLong("shellRequired") == 0,
            "voucher waives only proven host shell costs, keeping exact contents of the same NBT identity");
        require(
            normal.getCompoundTagAt(0)
                .getLong("required") == 7
                && voucher.getCompoundTagAt(1)
                    .getLong("required") == 3,
            "voucher projection preserves the reusable normal ledger and other full-NBT variants");
        int[] reads = { 0 };
        HologramMaterialQuote.StockSnapshot observed = new HologramMaterialQuote.StockSnapshot(
            new HologramMaterialQuote.ItemStock() {

                public long exact(ItemStack item, long limit) {
                    reads[0]++;
                    return item.getTagCompound()
                        .getInteger("mID") == 1 ? 6 : 1;
                }

                public long fuzzy(ItemStack item, long limit) {
                    throw new AssertionError("strict stock fixture cannot request fuzzy materials");
                }
            });
        java.util.List<NBTTagCompound> ordinary = Arrays.asList(normal.getCompoundTagAt(0), normal.getCompoundTagAt(1));
        HologramMaterialQuote.QuoteCounter first = new HologramMaterialQuote.QuoteCounter(
            ordinary,
            observed,
            ignored -> false);
        require(
            first.count(ordinary.get(0)) == 6 && first.count(ordinary.get(1)) == 1,
            "inventory snapshot keeps same item/damage with different complete NBT separate");
        for (int repeat = 0; repeat < 10; repeat++) {
            java.util.List<NBTTagCompound> reduced = Arrays
                .asList(voucher.getCompoundTagAt(0), voucher.getCompoundTagAt(1));
            HologramMaterialQuote.QuoteCounter next = new HologramMaterialQuote.QuoteCounter(
                reduced,
                observed,
                ignored -> false);
            require(
                next.count(reduced.get(0)) == 2 && next.count(reduced.get(1)) == 1,
                "repeated voucher projection uses the same physical stock observation");
        }
        require(reads[0] == 2, "ten projections perform no new native inventory reads");
    }

    /** Real server review/projection and the successful placement transaction share the creative no-debit rule. */
    private static void creativeVoucherSelectionAndCommittedPlacement() throws Exception {
        voucherSelectionAndCommittedPlacement(true);
        voucherSelectionAndCommittedPlacement(false);
    }

    private static void voucherSelectionAndCommittedPlacement(boolean creative) throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        CreativeVoucherWorld world = (CreativeVoucherWorld) unsafe.allocateInstance(CreativeVoucherWorld.class);
        java.lang.reflect.Field provider = net.minecraft.world.World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new net.minecraft.world.WorldProviderSurface());
        EntityPlayerMP player = materialPlayer();
        java.lang.reflect.Field watcherField = net.minecraft.entity.Entity.class.getDeclaredField("dataWatcher");
        watcherField.setAccessible(true);
        net.minecraft.entity.DataWatcher watcher = new net.minecraft.entity.DataWatcher(player);
        watcher.addObject(0, (byte) 0);
        watcherField.set(player, watcher);
        java.util.UUID identity = new java.util.UUID(9116, 43);
        for (java.lang.reflect.Field field : net.minecraft.entity.player.EntityPlayer.class.getDeclaredFields())
            if (field.getType() == com.mojang.authlib.GameProfile.class) {
                field.setAccessible(true);
                field.set(player, new com.mojang.authlib.GameProfile(identity, "CreativeVoucher"));
            }
        java.lang.reflect.Field entityIdentity = net.minecraft.entity.Entity.class.getDeclaredField("entityUniqueID");
        entityIdentity.setAccessible(true);
        entityIdentity.set(player, identity);
        player.worldObj = world;
        player.posY = 64;
        player.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
        player.capabilities.isCreativeMode = creative;
        player.capabilities.allowEdit = true;
        player.inventory.mainInventory[2] = new ItemStack(net.minecraft.init.Blocks.stone, 64);
        player.inventory.mainInventory[0] = new ItemStack(Items.feather);
        java.lang.reflect.Field config = DecorationVoucherConfig.class.getDeclaredField("current");
        config.setAccessible(true);
        Object previousConfig = config.get(null);
        com.miaokatze.gtmb.common.items.hologram.ItemDecorationVoucher previousVoucher = com.miaokatze.gtmb.register.DecorationVoucherRegistry.voucher;
        try {
            config.set(null, new DecorationVoucherConfig.View(true, Collections.singletonList("minecraft")));
            if (previousVoucher == null)
                com.miaokatze.gtmb.register.DecorationVoucherRegistry.voucher = new com.miaokatze.gtmb.common.items.hologram.ItemDecorationVoucher();
            ItemStack voucher = HologramDecorationVoucher.stack();
            voucher.stackSize = 2;
            player.inventory.mainInventory[1] = voucher;
            HologramSession session = new HologramSession(player, 0, 64, 0, false, 1);
            session.virtual = session.virtualPlacement = session.virtualLocked = true;
            session.blueprintAwaitingConfirmation = session.placementReviewIssued = true;
            session.virtualPreset = new HologramPresets.Preset();
            session.virtualPreset.schema = 2;
            session.virtualPreset.deidentificationLevel = 1;
            session.materialSettings.containers = session.materialSettings.me = false;
            session.materialContext = mainSource(player);
            session.capture = new HologramCapture(null, new ItemStack(Items.feather));
            ItemStack stone = new ItemStack(net.minecraft.init.Blocks.stone);
            int[] nativePlacements = { 0 };
            com.gtnewhorizon.structurelib.structure.IStructureElement<Object> element = new com.gtnewhorizon.structurelib.structure.IStructureElement<Object>() {

                public boolean check(Object ignored, net.minecraft.world.World target, int x, int y, int z) {
                    return target.getBlock(x, y, z) == net.minecraft.init.Blocks.stone;
                }

                public boolean spawnHint(Object ignored, net.minecraft.world.World target, int x, int y, int z,
                    ItemStack trigger) {
                    return true;
                }

                public boolean placeBlock(Object ignored, net.minecraft.world.World target, int x, int y, int z,
                    ItemStack trigger) {
                    throw new AssertionError("placement must use the survival source callback");
                }

                public PlaceResult survivalPlaceBlock(Object ignored, net.minecraft.world.World target, int x, int y,
                    int z, ItemStack trigger,
                    com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment environment) {
                    require(
                        environment.getSource()
                            .takeOne(stone, false),
                        "creative transaction supplies the native shell");
                    nativePlacements[0]++;
                    return target.setBlock(x, y, z, net.minecraft.init.Blocks.stone, 0, 3) ? PlaceResult.ACCEPT
                        : PlaceResult.REJECT;
                }
            };
            HologramCapture.Cell cell = new HologramCapture.Cell(element, 0, 64, 0);
            cell.block = net.minecraft.init.Blocks.stone;
            cell.blueprintBlock = cell.blueprintItem = "minecraft:stone";
            session.capture.cells.add(cell);
            session.planTargets.add(stone);
            require(
                HologramDecorationVoucher.covered(cell.block, stone),
                "placement fixture exercises a genuinely covered shell");
            NBTTagList rows = new NBTTagList();
            NBTTagCompound row = legacy(stone, 7, 0, 5);
            row.setLong("voucherShellRequired", 5);
            rows.appendTag(row);
            int[] inventoryReads = { 0 };
            HologramMaterialQuote.StockSnapshot stock = new HologramMaterialQuote.StockSnapshot(
                new HologramMaterialQuote.ItemStock() {

                    public long exact(ItemStack item, long limit) {
                        inventoryReads[0]++;
                        return creative ? 0 : limit;
                    }

                    public long fuzzy(ItemStack item, long limit) {
                        inventoryReads[0]++;
                        return creative ? 0 : limit;
                    }
                });
            HologramMaterialQuote
                .snapshot(session, rows, HologramMaterialQuote.voucherRows(rows), new NBTTagList(), stock);
            require(session.planMissing == 0, "creative free shell demand cannot create a partial-confirmation gate");
            HologramSession retained = new HologramSession(player, 0, 64, 0, false, 1);
            retained.capture = session.capture;
            NBTTagCompound retainedQuote = (NBTTagCompound) session.materialQuote.copy();
            NBTTagCompound essence = new NBTTagCompound();
            essence.setString("kind", "essentia");
            essence.setLong("required", 2);
            essence.setLong("missing", 2);
            retainedQuote.getTagList("rows", 10)
                .appendTag(essence);
            NBTTagCompound optional = new NBTTagCompound();
            optional.setString("kind", "energy");
            optional.setBoolean("optionalCache", true);
            optional.setLong("missing", 100);
            retainedQuote.getTagList("rows", 10)
                .appendTag(optional);
            retained.materialQuoteCache = new HologramMaterialQuote.Cache(retained, retainedQuote, retainedQuote);
            HologramMaterialQuote.project(retained);
            require(
                retained.planMissing == 2,
                "creative review retains the actual essentia payment gate while free shells and optional empty caches add none");
            HologramMaterialQuote.Cache cache = session.materialQuoteCache;
            int observations = inventoryReads[0];
            int worldReads = world.reads;
            long revision = session.planRevision;
            for (int i = 0; i < 11; i++) {
                boolean selected = i % 2 == 0;
                NBTTagCompound action = new NBTTagCompound();
                action.setString("session", session.id);
                action.setLong("planRevision", session.planRevision);
                action.setLong("placementReviewNonce", session.placementReviewNonce);
                action.setLong("uiSequence", 100 + i);
                action.setBoolean("enabled", selected);
                require(
                    HologramMaterialWorkflow.action(session, action, "setDecorativeVoucher"),
                    "real material action is handled");
                NBTTagCompound metadata = new NBTTagCompound();
                HologramMaterialWorkflow.writeState(session, metadata);
                NBTTagCompound choice = metadata.getCompoundTag("decorativeVoucher");
                NBTTagCompound quote = metadata.getCompoundTag("materialQuote");
                require(
                    choice.getBoolean("usable") && choice.getBoolean("selected") == selected
                        && choice.getLong("available") == 2
                        && choice.getLong("eligibleDemand") == 5,
                    "creative players holding vouchers receive usable, selected server UI metadata");
                require(
                    quote.getBoolean("creativePaymentsFree") == creative
                        && quote.getBoolean("useDecorationVoucher") == selected
                        && quote.getLong("placementReviewNonce") == session.placementReviewNonce
                        && quote.getTagList("rows", 10)
                            .getCompoundTagAt(0)
                            .getLong("required") == (selected ? 2 : 7),
                    "creative selection projects the cached shell waiver while retaining exact embedded requirements");
                if (i == 1) {
                    player.inventory.mainInventory[1] = null;
                    long nonce = session.placementReviewNonce;
                    action.setLong("placementReviewNonce", nonce);
                    action.setBoolean("enabled", true);
                    HologramMaterialWorkflow.action(session, action, "setDecorativeVoucher");
                    require(
                        !session.jobVoucherRequested && session.placementReviewNonce == nonce,
                        "creative selection still requires a real clean voucher in the main inventory");
                    player.inventory.mainInventory[1] = voucher;
                }
            }
            require(
                session.materialQuoteCache == cache && session.planRevision == revision
                    && inventoryReads[0] == observations
                    && world.reads == worldReads
                    && !HologramSnapshotPreparation.pending(session),
                "creative voucher actions do not rescan inventory, replan geometry or enqueue a full snapshot");
            require(
                HologramWorldTransactions.operateChecked(session, cell, 0),
                "creative voucher placement commits successfully");
            require(
                "placed".equals(cell.status) && world.placed == net.minecraft.init.Blocks.stone
                    && nativePlacements[0] == 1
                    && session.jobVoucherConsumed == !creative
                    && player.inventory.mainInventory[1] == voucher
                    && voucher.stackSize == (creative ? 2 : 1)
                    && HologramDecorationVoucher.available(player) == (creative ? 2 : 1)
                    && player.inventory.mainInventory[2].stackSize == 64,
                "voucher placement preserves stocked shells; survival pays one voucher and creative pays none");
        } finally {
            config.set(null, previousConfig);
            com.miaokatze.gtmb.register.DecorationVoucherRegistry.voucher = previousVoucher;
        }
    }

    private static final class CreativeVoucherWorld extends net.minecraft.world.WorldServer {

        net.minecraft.block.Block placed;
        int reads;

        private CreativeVoucherWorld() {
            super(null, null, "creative-voucher", 0, null, null);
        }

        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        public int getHeight() {
            return 256;
        }

        public long getTotalWorldTime() {
            return 0;
        }

        public net.minecraft.block.Block getBlock(int x, int y, int z) {
            reads++;
            return placed == null ? net.minecraft.init.Blocks.air : placed;
        }

        public int getBlockMetadata(int x, int y, int z) {
            reads++;
            return 0;
        }

        public net.minecraft.tileentity.TileEntity getTileEntity(int x, int y, int z) {
            reads++;
            return null;
        }

        public boolean canMineBlock(net.minecraft.entity.player.EntityPlayer player, int x, int y, int z) {
            return true;
        }

        public boolean isAirBlock(int x, int y, int z) {
            return placed == null;
        }

        public boolean setBlock(int x, int y, int z, net.minecraft.block.Block block, int meta, int flags) {
            placed = block;
            return true;
        }

        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            return true;
        }
    }

    private static EntityPlayerMP materialPlayer() throws Exception {
        java.lang.reflect.Method factory = HologramRegressionTest.class.getDeclaredMethod("materialPlayer");
        factory.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) factory.invoke(null);
        Arrays.fill(player.inventory.mainInventory, null);
        return player;
    }

    private static HologramMaterials.Context mainSource(EntityPlayerMP player) {
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        return new HologramMaterials.Context(player, settings);
    }

    private static void actualFuzzyPaymentProtectsLaterExactMachine() throws Exception {
        EntityPlayerMP player = materialPlayer();
        ItemStack exactN = machine(14950, 41), alternateM = machine(14950, 42);
        player.inventory.mainInventory[1] = exactN.copy();
        player.inventory.mainInventory[1].stackSize = 2;
        player.inventory.mainInventory[2] = alternateM.copy();
        Map<HologramBlueprintResources.ExactKey, Long> pending = new LinkedHashMap<>();
        pending.put(HologramValues.stackKey(exactN), 3L);
        Map<HologramBlueprintResources.ExactKey, Long> current = Collections
            .singletonMap(HologramValues.stackKey(exactN), 2L);
        HologramMaterials.Context source = mainSource(player);
        HologramMaterials.Transaction first = source.begin()
            .protectAssets(pending, current);
        require(first.takeOne(exactN, false), "current cell exact internal resource reserves before its shell");
        require(first.takeOneFuzzy(exactN, true), "protected shell simulation finds the alternative tagged machine");
        require(
            player.inventory.mainInventory[1].stackSize == 1 && player.inventory.mainInventory[2] != null,
            "simulation does not consume either protected machine or alternative");
        require(first.takeOneFuzzy(exactN, false), "actual fuzzy shell uses available alternate NBT variant");
        require(
            player.inventory.mainInventory[1] != null && player.inventory.mainInventory[1].stackSize == 1
                && player.inventory.mainInventory[2] == null,
            "early-slot exact machine survives while the tagged alternate pays");
        first.commit();
        pending.put(HologramValues.stackKey(exactN), 1L);
        source.assetsCommitted(current);
        HologramMaterials.Transaction later = source.begin()
            .protectAssets(pending, pending);
        require(later.takeOne(exactN, false), "later cell can consume its protected complete-NBT machine");
        later.commit();
        require(player.inventory.mainInventory[1] == null, "each exact and fuzzy requirement is charged once");
    }

    private static Item sensorCard() throws Exception {
        String registry = "gtmb:material_quote_sensor_fixture";
        Item registered = (Item) Item.itemRegistry.getObject(registry);
        if (registered != null) return registered;
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Item card = (Item) ((sun.misc.Unsafe) unsafeField.get(null))
            .allocateInstance(Class.forName("gregtech.common.items.ItemSensorCard"));
        java.lang.reflect.Field delegate = Item.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        delegate.set(
            card,
            ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Item.itemRegistry)
                .getDelegate(card, Item.class));
        int free = 30000;
        while (free > 4095 && Item.getItemById(free) != null) free--;
        if (free <= 4095) throw new AssertionError("No registered sensor fixture slot");
        Item.itemRegistry.addObject(free, registry, card);
        return card;
    }

    private static ItemStack card(Item item, int x, String mode) {
        ItemStack stack = new ItemStack(item, 1, 7);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("x", x);
        tag.setInteger("y", 64);
        tag.setInteger("z", 9);
        tag.setInteger("state", x);
        tag.setString("title", "Shared configured sensor");
        if (!mode.isEmpty()) tag.setString(HologramCoordinatePolicies.PAYMENT, mode);
        stack.setTagCompound(tag);
        return stack;
    }

    private static void coordinateQuotesAndActualPaymentSharePhysicalCards() throws Exception {
        Item item = sensorCard();
        ItemStack relative = card(item, 19, "relative"), clear = card(item, 29, "clear");
        ItemStack n = card(item, 1, ""), m = card(item, 2, "");
        NBTTagCompound a = legacy(relative, 1, 0, 0), b = legacy(clear, 1, 0, 0);
        HologramMaterialQuote.QuoteCounter counter = new HologramMaterialQuote.QuoteCounter(
            Arrays.asList(a, b),
            new HologramMaterialQuote.ItemStock() {

                public long exact(ItemStack expected, long limit) {
                    return HologramCoordinatePolicies.paymentEquivalent(expected, n) ? Math.min(1L, limit) : 0;
                }

                public long fuzzy(ItemStack expected, long limit) {
                    return 0;
                }
            },
            ignored -> false);
        require(
            counter.count(a) + counter.count(b) == 1,
            "relative and clear rows cannot count one physical card twice");
        ItemStack absolute = card(item, 1, "absolute");
        NBTTagCompound broadRow = legacy(relative, 2, 0, 0), absoluteRow = legacy(absolute, 1, 0, 0);
        HologramMaterialQuote.QuoteCounter priorities = new HologramMaterialQuote.QuoteCounter(
            Arrays.asList(broadRow, absoluteRow),
            new HologramMaterialQuote.ItemStock() {

                public long exact(ItemStack expected, long limit) {
                    long stock = (HologramCoordinatePolicies.paymentEquivalent(expected, n) ? 1L : 0L)
                        + (HologramCoordinatePolicies.paymentEquivalent(expected, m) ? 1L : 0L);
                    return Math.min(stock, limit);
                }

                public long fuzzy(ItemStack expected, long limit) {
                    return 0;
                }
            },
            ignored -> false);
        require(
            priorities.count(absoluteRow) == 1 && priorities.count(broadRow) == 1,
            "absolute coordinates receive stock before earlier broad rows and shared available stock is allocated once");
        EntityPlayerMP player = materialPlayer();
        player.inventory.mainInventory[1] = n.copy();
        player.inventory.mainInventory[2] = m.copy();
        Map<HologramBlueprintResources.ExactKey, Long> pending = new LinkedHashMap<>();
        pending.put(HologramValues.stackKey(relative), 1L);
        pending.put(HologramValues.stackKey(absolute), 1L);
        Map<HologramBlueprintResources.ExactKey, Long> current = Collections
            .singletonMap(HologramValues.stackKey(relative), 1L);
        HologramMaterials.Context source = mainSource(player);
        HologramMaterials.Transaction broad = source.begin()
            .protectAssets(pending, current);
        require(broad.takeOne(relative, false), "earlier broad coordinate payment can use another real card");
        require(
            player.inventory.mainInventory[1] != null && player.inventory.mainInventory[2] == null,
            "broad payment preserves the card needed for the later absolute coordinates");
        broad.commit();
        pending.remove(HologramValues.lookupKey(relative));
        source.assetsCommitted(current);
        HologramMaterials.Transaction strict = source.begin()
            .protectAssets(pending, pending);
        require(strict.takeOne(absolute, false), "later absolute coordinates remain payable");
        strict.commit();
        ItemStack craftableRelative = HologramMaterialReservations.craftingSample(relative);
        require(
            craftableRelative != null && !craftableRelative.getTagCompound()
                .hasKey(HologramCoordinatePolicies.PAYMENT)
                && !craftableRelative.getTagCompound()
                    .hasKey("x")
                && HologramCoordinatePolicies.paymentEquivalent(relative, craftableRelative),
            "AE requests a native payable card rather than an internal relative-payment marker");
        ItemStack craftableAbsolute = HologramMaterialReservations.craftingSample(absolute);
        require(
            craftableAbsolute != null && craftableAbsolute.getTagCompound()
                .getInteger("x") == 1
                && !craftableAbsolute.getTagCompound()
                    .hasKey(HologramCoordinatePolicies.PAYMENT),
            "absolute card crafting keeps its exact coordinates while stripping only reviewed internal state");
    }

    private static void boundToolSlotSurvivesHotbarSelection() throws Exception {
        EntityPlayerMP player = materialPlayer();
        ItemStack material = new ItemStack(net.minecraft.init.Blocks.stone);
        player.inventory.mainInventory[0] = material.copy();
        player.inventory.mainInventory[8] = material.copy();
        player.inventory.mainInventory[8].stackSize = 2;
        player.inventory.currentItem = 0;
        HologramMaterials.Context source = mainSource(player);
        player.inventory.currentItem = 8;
        require(source.count(material, 64) == 2, "changing held slot does not exclude the selected payment stack");
        HologramMaterials.Settings settings = new HologramMaterials.Settings();
        settings.containers = settings.me = false;
        source = new HologramMaterials.Context(player, settings, 0);
        HologramMaterials.Transaction transaction = source.begin();
        require(
            transaction.takeOne(material, false) && transaction.takeOne(material, false),
            "a newly created background context pays the held slot with the admitted tool slot bound explicitly");
        require(
            player.inventory.mainInventory[8] == null && player.inventory.mainInventory[0] != null,
            "actual payment consumes slot eight and never consumes protected original slot zero");
        transaction.commit();
    }

    private static void wireIdentityAndLongCounts() throws Exception {
        NBTTagList materialRows = new NBTTagList();
        ItemStack a = machine(14950, 41), b = machine(14951, 41), c = machine(14950, 42);
        materialRows.appendTag(legacy(a, 5_000_000_256L, 4_000_000_000L, 0));
        materialRows.appendTag(legacy(b, 256, 255, 0));
        materialRows.appendTag(legacy(c, 65_536, 1, 0));
        NBTTagCompound quote = HologramMaterialQuote.fromRows(materialRows, new NBTTagList());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(quote, output);
        NBTTagCompound decoded = CompressedStreamTools.readCompressed(new ByteArrayInputStream(output.toByteArray()));
        NBTTagList rows = decoded.getTagList("rows", 10);
        require(rows.tagCount() == 3, "machine damage and full mID NBT remain distinct rows");
        NBTTagCompound first = rows.getCompoundTagAt(0);
        require(first.getLong("required") == 5_000_000_256L, "required is independent of byte Count and int limits");
        require(first.getLong("missing") == 1_000_000_256L, "missing uses exact long arithmetic");
        require(
            first.getCompoundTag("item")
                .getByte("Count") == 1,
            "display icon is exactly one item");
        first.getCompoundTag("item")
            .setShort("id", (short) Item.getIdFromItem(Items.diamond));
        ItemStack icon = HologramMaterialQuote.readItem(first);
        require(
            icon != null && icon.getItem() == Items.stick && icon.getItemDamage() == 14950,
            "stable registry identity survives a mismatched numeric item id");
        require(
            icon.getTagCompound()
                .getInteger("mID") == 41,
            "full machine tag survives transport");
        first.setString("itemRegistry", "missing:invalid");
        require(
            HologramMaterialQuote.readItem(first) == null,
            "unknown stable registry never falls back to another item");
        require(
            a.stackSize == 1 && a.getTagCompound()
                .getInteger("mID") == 41,
            "quote never mutates the source item");
    }

    private static void sharedFuzzyStockProtectsExactContents() {
        ItemStack shellA = machine(9, 1), exactB = machine(9, 2), otherMeta = machine(10, 2);
        NBTTagCompound a = legacy(shellA, 4, 0, 4), b = legacy(exactB, 8, 0, 3);
        NBTTagCompound c = legacy(otherMeta, 2, 0, 2);
        int[] exactCalls = { 0 }, fuzzyCalls = { 0 };
        HologramMaterialQuote.QuoteCounter counter = new HologramMaterialQuote.QuoteCounter(
            Arrays.asList(a, b, c),
            new HologramMaterialQuote.ItemStock() {

                @Override
                public long exact(ItemStack item, long limit) {
                    exactCalls[0]++;
                    return item.getItemDamage() == 9 && item.getTagCompound()
                        .getInteger("mID") == 2 ? Math.min(5, limit) : 0;
                }

                @Override
                public long fuzzy(ItemStack item, long limit) {
                    fuzzyCalls[0]++;
                    return item.getItemDamage() == 9 ? Math.min(9, limit) : 0;
                }
            },
            item -> true);
        require(counter.count(a) == 4, "fuzzy pool allocates only stock remaining after exact contents");
        require(counter.count(b) == 5, "exact content stock cannot also pay a tagged shell row");
        require(counter.count(c) == 0, "different machine metadata has a separate stock pool");
        require(
            counter.count(a) == 4 && exactCalls[0] == 1 && fuzzyCalls[0] == 2,
            "repeat counter access uses the cached allocation without rescanning sources");
    }

    private static ItemStack machine(int damage, int id) {
        ItemStack stack = new ItemStack(Items.stick, 1, damage);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("mID", id);
        stack.setTagCompound(tag);
        return stack;
    }

    private static NBTTagCompound legacy(ItemStack item, long required, long available, long shell) {
        NBTTagCompound row = item.writeToNBT(new NBTTagCompound());
        row.setLong("required", required);
        row.setLong("available", available);
        row.setLong("shellRequired", shell);
        return row;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
