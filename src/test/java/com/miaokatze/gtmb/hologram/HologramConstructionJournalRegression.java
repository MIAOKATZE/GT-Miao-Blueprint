// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

import appeng.api.storage.data.IAEItemStack;

/** Actual forced/atomic files, native NBT and shared production extraction/restore boundaries. */
public final class HologramConstructionJournalRegression {

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramConstructionJournalRegression.class, args)) return;
        run();
        System.out.println("HologramConstructionJournalRegression PASS");
        System.exit(0);
    }

    public static void run() {
        Path previous = HologramConstructionJournal.STORAGE_ROOT.get();
        try (AutoCloseable scope = HologramDemolitionDestinationRegression.nativeClassScope()) {
            Path root = Files.createTempDirectory("gtmb-durable-recovery-");
            HologramConstructionJournal.STORAGE_ROOT.set(root);
            HologramRecoveryDeliveryRegression.Fixture f = new HologramRecoveryDeliveryRegression.Fixture();
            nonDurableCleanup(f);
            nativeProviderCoverage();
            callbackBindingCoverage();
            sparseLocalCoverage();
            Field identity = net.minecraft.entity.Entity.class.getDeclaredField("entityUniqueID");
            identity.setAccessible(true);
            identity.set(f.player, java.util.UUID.randomUUID());
            ItemStack[] original = HologramMaterialAccess.copyInventory(f.player);
            NBTTagCompound journal = HologramConstructionJournal
                .begin(f.player, 1, 64, 1, Blocks.stone, 2, null, original);
            HologramMaterials.Transaction payment = f.context.begin()
                .journal(journal);
            require(
                journal.getTag("heldInventory") == journal.getTag("beforeInventory"),
                "initial held inventory reuses immutable initial evidence");
            Object initialPayment = journal.getTag("payment");
            payment.beforeWorldMutation();
            require(
                journal.getTag("payment") == initialPayment,
                "unchanged payment stage reuses its existing NBT tree");
            Path file = root.resolve("gtmb-construction-recovery")
                .resolve(f.player.getUniqueID() + ".nbt");
            int[] removed = { 0 };
            IAEItemStack request = (IAEItemStack) java.lang.reflect.Proxy.newProxyInstance(
                IAEItemStack.class.getClassLoader(),
                new Class<?>[] { IAEItemStack.class },
                (proxy, method, arguments) -> {
                    if ("getStackSize".equals(method.getName())) return 7L;
                    if ("writeToNBT".equals(method.getName())) {
                        NBTTagCompound encoded = (NBTTagCompound) arguments[0];
                        new ItemStack(Items.apple, 1).writeToNBT(encoded);
                        encoded.setLong("Cnt", 7);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
            failure(() -> payment.extract("item", request, () -> {
                try {
                    NBTTagCompound disk = HologramSnapshotCodec.decode(Files.readAllBytes(file));
                    require(
                        disk.getCompoundTag("operation")
                            .getLong("requested") == 7,
                        "write-ahead operation reaches disk before native effect");
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                removed[0] += 3;
                throw new IllegalStateException("provider removed three then threw");
            }));
            require(removed[0] == 3 && payment.uncertain(), "unknown native result remains unresolved");
            failure(payment::rollbackExternal);
            HologramSession session = session(f);
            HologramConstructionJournal
                .suspend(session, payment, new IllegalStateException("injected extraction failure"));
            require(session.job == 4 && session.stopTick, "unknown result stops this batch");
            verifyPublicationCache(f, root, file, journal);
            HologramConstructionJournal.owner(f.player)
                .removeTag(HologramConstructionJournal.KEY);
            HologramConstructionJournal.reload(f.player);
            NBTTagCompound reloaded = HologramConstructionJournal.owner(f.player)
                .getCompoundTag(HologramConstructionJournal.KEY);
            require(
                reloaded.getCompoundTag("operation")
                    .getLong("requested") == 7,
                "durable file restores newer evidence over stale player data");
            try {
                HologramConstructionJobs.start(java.util.UUID.randomUUID(), 0, new HologramConstructionJobs.Source() {

                    public HologramSession session() {
                        return session;
                    }

                    public HologramStockNativeWire.Operation operation() {
                        return HologramStockNativeWire.Operation.BUILD;
                    }

                    public boolean current() {
                        throw new AssertionError("recovery must block before source admission");
                    }

                    public boolean prepare(long deadline) {
                        throw new AssertionError("unresolved recovery reached preparation");
                    }

                    public HologramConstructionJobs.Target next(long deadline) {
                        throw new AssertionError("unresolved recovery reached world work");
                    }

                    public boolean exhausted() {
                        return true;
                    }

                    public long total() {
                        return 0;
                    }

                    public void finished(HologramConstructionJobs.Job job) {
                        throw new AssertionError("unresolved recovery admitted a job");
                    }
                });
                throw new AssertionError("unresolved recovery admitted construction");
            } catch (java.io.IOException blocked) {
                require(
                    blocked.getMessage()
                        .contains("待核定"),
                    "persisted recovery blocks the real common admission before world/payment");
            }
            failure(() -> HologramConstructionJournal.resolve(f.player, reloaded.getString("id"), "rollback"));
            failure(() -> HologramMaterials.Transaction.adjudicateExtraction(reloaded, 8));
            HologramMaterials.Transaction.adjudicateExtraction(reloaded, 3);
            HologramConstructionJournal.persist(f.player, reloaded);
            require(
                reloaded.getCompoundTag("payment")
                    .getTagList("items", 10)
                    .tagCount() == 1,
                "only adjudicated actual extraction enters sole payment ledger");
            failure(() -> HologramMaterials.Transaction.adjudicateExtraction(reloaded, 3));
            failure(() -> HologramConstructionJournal.resolve(f.player, "wrong-receipt", "accept"));
            HologramConstructionJournal.resolve(f.player, reloaded.getString("id"), "accept");
            HologramConstructionJournal.reload(f.player);
            require(
                !HologramConstructionJournal.pending(f.player),
                "durable settlement tombstone prevents stale NBT resurrection");

            NBTTagCompound second = HologramConstructionJournal
                .begin(f.player, 1, 64, 1, Blocks.stone, 2, null, original);
            HologramMaterials.Transaction secondPayment = f.context.begin()
                .journal(second);
            // The deleted restoreCell lambda seam only tested a legacy wrapper. Exercise the retained production
            // suspend/resolve contract directly; native writer restoration failures belong to writer tests.
            HologramConstructionJournal
                .suspend(session, secondPayment, new IllegalStateException("injected explicit recovery suspension"));
            require(
                session.job == 4 && second.hasKey("beforeInventory", 9) && second.hasKey("payment", 10),
                "production suspension preserves legacy world/payment evidence and stops the session");
            HologramConstructionJournal.resolve(f.player, second.getString("id"), "accept");

            // A directory at the destination makes strict publication fail before calling the native provider.
            Files.delete(file);
            Files.createDirectory(file);
            int[] called = { 0 };
            failure(() -> {
                NBTTagCompound blocked = HologramConstructionJournal
                    .begin(f.player, 1, 64, 1, Blocks.stone, 0, null, original);
                f.context.begin()
                    .journal(blocked)
                    .extract("item", request, () -> {
                        called[0]++;
                        return null;
                    });
            });
            require(called[0] == 0, "journal write failure prevents external mutation");
        } catch (Exception error) {
            throw new AssertionError("Durable construction journal", error);
        } finally {
            if (previous == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(previous);
        }
    }

    private static void nonDurableCleanup(HologramRecoveryDeliveryRegression.Fixture f) {
        NBTTagCompound journal = new NBTTagCompound() {

            @Override
            public net.minecraft.nbt.NBTBase copy() {
                throw new AssertionError("Undurable cleanup copied the complete journal");
            }
        };
        journal.setString("phase", "reserved");
        HologramConstructionJournal.owner(f.player)
            .setTag(HologramConstructionJournal.KEY, journal);
        HologramConstructionJournal.clear(f.player);
        require(
            !HologramConstructionJournal.owner(f.player)
                .hasKey(HologramConstructionJournal.KEY) && "reserved".equals(journal.getString("phase")),
            "undurable settlement removes the receipt without copying or mutating its full tree");
    }

    private static void sparseLocalCoverage() throws Exception {
        var f = new HologramRecoveryDeliveryRegression.Fixture();
        var settings = new HologramMaterials.Settings();
        settings.me = settings.containers = false;
        var context = new HologramMaterials.Context(f.player, settings, java.util.Collections.emptyList());
        f.player.inventory.mainInventory[1] = new ItemStack(Items.apple, 5);
        ItemStack unrelated = f.player.inventory.mainInventory[5];
        var journal = HologramConstructionJournal.beginAction(f.player, 1, 64, 1, Blocks.air, 0, null, 0);
        var payment = context.begin()
            .journal(journal)
            .trackLocalInventory();
        require(
            payment.takeAll(new ItemStack(Items.apple, 2), false),
            "native local extraction pays the requested amount");
        payment.snapshot();
        require(
            !journal.hasKey("beforeInventory") && !journal.hasKey("heldInventory")
                && journal.getTagList("localSlots", 10)
                    .tagCount() == 1,
            "modern payment stores only the one used slot, without whole-inventory evidence");
        f.player.inventory.mainInventory[6] = new ItemStack(Items.feather, 17);
        require(payment.localInventoryCurrent(), "unrelated inventory edits do not invalidate the action");
        var resumed = HologramMaterials.Transaction.resume(f.player, journal);
        require(
            resumed.localInventoryCurrent() && resumed.rollbackAction(),
            "persisted sparse receipt refunds native deductions");
        require(
            f.player.inventory.mainInventory[1].stackSize == 5 && f.player.inventory.mainInventory[5] == unrelated
                && f.player.inventory.mainInventory[6].stackSize == 17,
            "rollback restores only its owned material slot and preserves unrelated edits");
        require(
            !HologramConstructionJournal.pending(f.player),
            "confirmed world/payment rollback retires its action and permits the next cell");

        journal = HologramConstructionJournal.beginAction(f.player, 1, 64, 1, Blocks.air, 0, null, 0);
        payment = context.begin()
            .journal(journal)
            .trackLocalInventory();
        require(payment.takeOne(new ItemStack(Items.apple), false), "second action pays one native item");
        ItemStack replacement = new ItemStack(Items.apple, 4);
        f.player.inventory.mainInventory[1] = replacement;
        require(!payment.localInventoryCurrent(), "even an equal replacement loses live slot ownership");
        failure(payment::rollbackAction);
        require(
            f.player.inventory.mainInventory[1] == replacement && replacement.stackSize == 4
                && HologramConstructionJournal.owner(f.player)
                    .getTag(HologramConstructionJournal.KEY) == journal,
            "a later replacement is never overwritten or refunded twice, and its unresolved action stays attached");
        HologramConstructionJournal.clear(f.player);
    }

    private static void nativeProviderCoverage() throws Exception {
        // This contract intentionally exercises native registries, unlike the injected extractor fixtures.
        // AE's real registry constructor requires its real config even when no network source is selected.
        HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
            try {
                nativeProviderCoverageInitialized();
            } catch (Exception failure) {
                throw new AssertionError("Native provider fixture", failure);
            }
        });
    }

    private static void nativeProviderCoverageInitialized() throws Exception {
        var f = new HologramRecoveryDeliveryRegression.Fixture();
        var first = new net.minecraft.inventory.InventoryBasic("first", false, 3);
        var second = new net.minecraft.inventory.InventoryBasic("second", false, 2);
        first.setInventorySlotContents(0, new ItemStack(Items.apple, 3));
        second.setInventorySlotContents(0, new ItemStack(Items.apple, 7));
        // A provider alias of the active tool must never expose its contents as a material source.
        first.setInventorySlotContents(1, f.player.inventory.mainInventory[0]);
        ItemStack bag = new ItemStack(Items.writable_book);
        bag.setTagCompound(new NBTTagCompound());
        bag.getTagCompound()
            .setInteger("gtmbProviderFixture", 4);
        second.setInventorySlotContents(1, bag);
        int[] dirty = new int[2];
        boolean[] enabled = { true };
        boolean[] throwAfterExtracting = { false }, durableBeforeExtraction = { false };
        String prefix = "gtmb-contract-" + f.player.getUniqueID();
        for (int i = 0; i < 2; i++) {
            final int index = i;
            com.gtnewhorizon.structurelib.util.InventoryUtility.registerInventoryProvider(
                prefix + i,
                new com.gtnewhorizon.structurelib.util.InventoryUtility.InventoryProvider<com.gtnewhorizon.structurelib.util.InventoryIterable<net.minecraft.inventory.InventoryBasic>>() {

                    public com.gtnewhorizon.structurelib.util.InventoryIterable<net.minecraft.inventory.InventoryBasic> getInventory(
                        net.minecraft.entity.player.EntityPlayerMP player) {
                        return player == f.player && (index != 0 || enabled[0])
                            ? new com.gtnewhorizon.structurelib.util.InventoryIterable<>(index == 0 ? first : second)
                            : null;
                    }

                    public void markDirty(
                        com.gtnewhorizon.structurelib.util.InventoryIterable<net.minecraft.inventory.InventoryBasic> inv) {
                        dirty[index]++;
                        inv.getInventory()
                            .markDirty();
                    }
                });
        }
        com.gtnewhorizon.structurelib.util.InventoryUtility.registerStackExtractor(
            prefix,
            new com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackExtractor() {

                public boolean isAPIImplemented(APIType type) {
                    return type == APIType.MAIN || type == APIType.IS_VALID_SOURCE;
                }

                public boolean isValidSource(ItemStack stack, net.minecraft.entity.player.EntityPlayerMP player) {
                    return stack.hasTagCompound() && stack.getTagCompound()
                        .hasKey("gtmbProviderFixture");
                }

                public int takeFromStack(java.util.function.Predicate<ItemStack> predicate, boolean simulate, int count,
                    com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackCounter counter, ItemStack source,
                    ItemStack filter, net.minecraft.entity.player.EntityPlayerMP player) {
                    require(
                        source == bag && player == f.player,
                        "native extractors receive the actual provider source and authenticated player even in simulation");
                    ItemStack stone = new ItemStack(Blocks.stone);
                    if (!predicate.test(stone)) return 0;
                    int taken = Math.min(
                        count,
                        source.getTagCompound()
                            .getInteger("gtmbProviderFixture"));
                    if (!simulate && throwAfterExtracting[0]) {
                        NBTTagCompound receipt = HologramConstructionJournal.owner(player)
                            .getCompoundTag(HologramConstructionJournal.KEY);
                        durableBeforeExtraction[0] = receipt.getBoolean("durable") && "container".equals(
                            receipt.getCompoundTag("operation")
                                .getString("kind"));
                        taken = Math.min(1, taken);
                    }
                    if (!simulate) source.getTagCompound()
                        .setInteger(
                            "gtmbProviderFixture",
                            source.getTagCompound()
                                .getInteger("gtmbProviderFixture") - taken);
                    counter.add(stone, taken);
                    if (!simulate && throwAfterExtracting[0])
                        throw new IllegalStateException("native container removed a partial stack then threw");
                    return taken;
                }
            });
        var registry = com.gtnewhorizon.structurelib.SortedRegistry.getRegistry("inventoryProviders");
        registry.registerOrdering(
            f.player,
            java.util.Arrays.asList(prefix + 1, prefix + 0),
            java.util.Arrays.asList("5000-main-inventory", "7000-ender-inventory"));
        var settings = new HologramMaterials.Settings();
        settings.main = settings.me = false;
        var context = new HologramMaterials.Context(f.player, settings, 0);
        ItemStack apple = new ItemStack(Items.apple);
        require(
            context.count(apple, 20) == 10 && dirty[0] == 0 && dirty[1] == 0,
            "native provider simulation includes equipped-like inventories without saving or extracting");
        require(
            context.count(new ItemStack(Items.feather), 20) == 0,
            "an equipped alias cannot consume the protected tool slot");
        enabled[0] = false;
        require(
            context.count(apple, 20) == 7,
            "native provider conditions are re-evaluated instead of caching Ender-like permission");
        enabled[0] = true;
        var payment = context.begin();
        require(
            payment.takeAll(new ItemStack(Items.apple, 4), false) && first.getStackInSlot(0).stackSize == 3
                && second.getStackInSlot(0).stackSize == 3
                && dirty[1] == 1
                && dirty[0] == 0,
            "actual direct extraction follows player provider ordering and invokes native persistence");
        require(
            context.count(new ItemStack(Blocks.stone), 20) == 4,
            "native extractors inspect carried containers outside the main inventory");
        require(
            payment.takeAll(new ItemStack(Blocks.stone, 2), false) && bag.getTagCompound()
                .getInteger("gtmbProviderFixture") == 2 && dirty[1] == 2,
            "container extraction saves its native equipped provider");
        payment.rollbackExternal();
        require(
            f.pending() == 6 && second.getStackInSlot(0).stackSize == 3
                && bag.getTagCompound()
                    .getInteger("gtmbProviderFixture") == 2,
            "rollback refunds actual external items without restoring external sources and duplicating them");
        NBTTagCompound journal = HologramConstructionJournal
            .begin(f.player, 1, 64, 1, Blocks.air, 0, null, HologramMaterialAccess.copyInventory(f.player, 0), 0);
        HologramMaterials.Transaction unresolved = context.begin()
            .journal(journal);
        throwAfterExtracting[0] = true;
        failure(() -> unresolved.takeAll(new ItemStack(Blocks.stone, 2), false));
        unresolved.snapshotPayment();
        require(
            journal.getCompoundTag("payment")
                .getTagList("containerItems", 10)
                .tagCount() == 1,
            "partial counter report remains in the known ledger when the native adapter throws");
        Object unknown = journal.getTag("operation");
        failure(() -> unresolved.requestedContainer(new ItemStack(Blocks.stone), 1, bag));
        require(journal.getTag("operation") == unknown, "another request cannot replace unresolved native evidence");
        require(
            durableBeforeExtraction[0] && unresolved.uncertain()
                && bag.getTagCompound()
                    .getInteger("gtmbProviderFixture") == 1,
            "generic extractor has durable evidence before side effects and isolates partial unknown results");
        failure(unresolved::rollbackExternal);
        HologramConstructionJournal.resolve(f.player, journal.getString("id"), "accept");
        throwAfterExtracting[0] = false;
        settings.containers = false;
        require(
            context.count(apple, 20) == 0 && context.count(new ItemStack(Blocks.stone), 20) == 0,
            "container mask disables direct and nested provider sources");
        settings.main = true;
        require(
            context.mainCount(new ItemStack(Items.stick), 20) == 0,
            "a disabled native main provider cannot be bypassed by the explicit main mask");
    }

    private static void callbackBindingCoverage() throws Exception {
        var other = new HologramRecoveryDeliveryRegression.Fixture();
        for (boolean moveWorld : new boolean[] { true, false }) {
            var f = new HologramRecoveryDeliveryRegression.Fixture();
            var originalInventory = f.player.inventory;
            ItemStack first = new ItemStack(Items.writable_book), second = first.copy();
            for (ItemStack source : new ItemStack[] { first, second }) {
                source.setTagCompound(new NBTTagCompound());
                source.getTagCompound()
                    .setInteger("bindingFixture", 3);
            }
            originalInventory.mainInventory[1] = first;
            originalInventory.mainInventory[2] = second;
            int[] calls = { 0 };
            var adapter = new com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackExtractor() {

                public boolean isAPIImplemented(APIType type) {
                    return type == APIType.MAIN || type == APIType.IS_VALID_SOURCE;
                }

                public boolean isValidSource(ItemStack source, net.minecraft.entity.player.EntityPlayerMP player) {
                    return source.hasTagCompound() && source.getTagCompound()
                        .hasKey("bindingFixture");
                }

                public int takeFromStack(java.util.function.Predicate<ItemStack> predicate, boolean simulate, int count,
                    com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackCounter counter, ItemStack source,
                    ItemStack filter, net.minecraft.entity.player.EntityPlayerMP player) {
                    require(
                        (source == first || source == second) && player == f.player,
                        "native extractors receive the actual provider source and authenticated player even in simulation");
                    ItemStack stone = new ItemStack(Blocks.stone);
                    if (!predicate.test(stone)) return 0;
                    int taken = Math.min(
                        simulate ? count : 1,
                        source.getTagCompound()
                            .getInteger("bindingFixture"));
                    if (!simulate) {
                        calls[0]++;
                        source.getTagCompound()
                            .setInteger("bindingFixture", 3 - taken);
                    }
                    counter.add(stone, taken);
                    if (!simulate) {
                        if (moveWorld) player.worldObj = other.world;
                        else player.inventory = new net.minecraft.entity.player.InventoryPlayer(player);
                    }
                    return taken;
                }
            };
            var settings = new HologramMaterials.Settings();
            settings.main = settings.me = false;
            var context = new HologramMaterials.Context(
                f.player,
                settings,
                java.util.Collections.singletonList(adapter));
            NBTTagCompound journal = HologramConstructionJournal
                .begin(f.player, 1, 64, 1, Blocks.air, 0, null, HologramMaterialAccess.copyInventory(f.player, 0), 0);
            var payment = context.begin()
                .journal(journal);
            payment.guard(
                () -> f.player.worldObj == f.world && f.player.inventory == originalInventory
                    && HologramConstructionJournal.owner(f.player)
                        .getTag(HologramConstructionJournal.KEY) == journal);
            failure(() -> payment.takeAll(new ItemStack(Blocks.stone, 2), false));
            require(
                calls[0] == 1 && second.getTagCompound()
                    .getInteger("bindingFixture") == 3,
                "callback rebinding stops before another material source can debit");
            Object held = journal.getTag("heldInventory");
            f.player.inventory = null;
            payment.paymentChanged();
            payment.snapshotPayment();
            require(
                journal.getTag("heldInventory") == held && journal.getCompoundTag("payment")
                    .getTagList("containerItems", 10)
                    .tagCount() == 1
                    && journal.getCompoundTag("payment")
                        .getTagList("containerStates", 10)
                        .tagCount() == 1,
                "known partial receipt survives without reading replacement inventory");
            f.player.inventory = originalInventory;
            f.player.worldObj = f.world;
            HologramConstructionJournal.resolve(f.player, journal.getString("id"), "accept");
        }

        var f = new HologramRecoveryDeliveryRegression.Fixture();
        NBTTagCompound journal = HologramConstructionJournal
            .begin(f.player, 1, 64, 1, Blocks.air, 0, null, HologramMaterialAccess.copyInventory(f.player, 0), 0);
        var payment = f.context.begin()
            .journal(journal);
        payment.guard(() -> f.player.worldObj == f.world);
        IAEItemStack actual = nativeItemReceipt(3);
        IAEItemStack result = payment.extract("item", actual, () -> {
            f.player.worldObj = other.world;
            return actual;
        });
        failure(() -> payment.extractedItem(result));
        f.player.inventory = null;
        payment.paymentChanged();
        payment.snapshotPayment();
        require(
            !payment.uncertain() && journal.getCompoundTag("payment")
                .getTagList("items", 10)
                .getCompoundTagAt(0)
                .getLong("Cnt") == 3,
            "known ME result is retained before changed binding reaches fees or another source");
        f.player.worldObj = f.world;
        HologramConstructionJournal.resolve(f.player, journal.getString("id"), "accept");
    }

    private static IAEItemStack nativeItemReceipt(int amount) {
        final IAEItemStack[] reference = { null };
        reference[0] = (IAEItemStack) java.lang.reflect.Proxy.newProxyInstance(
            IAEItemStack.class.getClassLoader(),
            new Class<?>[] { IAEItemStack.class },
            (proxy, method, arguments) -> {
                if ("getStackSize".equals(method.getName())) return (long) amount;
                if ("copy".equals(method.getName())) return reference[0];
                if ("writeToNBT".equals(method.getName())) {
                    NBTTagCompound encoded = (NBTTagCompound) arguments[0];
                    new ItemStack(Items.apple).writeToNBT(encoded);
                    encoded.setLong("Cnt", amount);
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        return reference[0];
    }

    private static void verifyPublicationCache(HologramRecoveryDeliveryRegression.Fixture fixture, Path root, Path file,
        NBTTagCompound journal) throws Exception {
        long hits = HologramConstructionJournal.publicationCacheHits;
        HologramConstructionJournal.persist(fixture.player, journal);
        require(
            HologramConstructionJournal.publicationCacheHits == hits + 1,
            "identical disk bytes avoid old NBT decode");

        // An external writer can change content without changing the journal identity/revision. Such bytes
        // must use the original decoder and conflict checks, even when the destination path stays the same.
        NBTTagCompound changed = HologramSnapshotCodec.decode(Files.readAllBytes(file));
        changed.setString("failure", "external same-revision edit");
        Files.write(file, HologramSnapshotCodec.compress(changed));
        hits = HologramConstructionJournal.publicationCacheHits;
        HologramConstructionJournal.persist(fixture.player, journal);
        require(
            HologramConstructionJournal.publicationCacheHits == hits,
            "changed bytes require original NBT validation");

        byte[] accepted = Files.readAllBytes(file);
        long revision = journal.getLong("revision");
        changed = HologramSnapshotCodec.decode(accepted);
        changed.setLong("revision", revision + 1);
        byte[] conflict = HologramSnapshotCodec.compress(changed);
        Files.write(file, conflict);
        failure(() -> HologramConstructionJournal.persist(fixture.player, journal));
        require(journal.getLong("revision") == revision, "conflict cannot advance in-memory revision");
        require(java.util.Arrays.equals(conflict, Files.readAllBytes(file)), "conflict preserves external evidence");
        Files.write(file, accepted);
        HologramConstructionJournal.persist(fixture.player, journal);
        require(
            HologramConstructionJournal.publicationCacheHits == hits + 1,
            "failed publication retains last successful bytes");

        // The same actor and exact bytes in another save root must not inherit this root's cache hit.
        Path otherRoot = Files.createTempDirectory("gtmb-durable-cache-root-");
        Path otherFile = otherRoot.resolve("gtmb-construction-recovery")
            .resolve(file.getFileName());
        Files.createDirectories(otherFile.getParent());
        Files.write(otherFile, Files.readAllBytes(file));
        NBTTagCompound other = (NBTTagCompound) journal.copy();
        hits = HologramConstructionJournal.publicationCacheHits;
        try {
            HologramConstructionJournal.STORAGE_ROOT.set(otherRoot);
            HologramConstructionJournal.persist(fixture.player, other);
            require(
                HologramConstructionJournal.publicationCacheHits == hits,
                "publication cache is bound to exact save root");
        } finally {
            HologramConstructionJournal.STORAGE_ROOT.set(root);
        }
        HologramConstructionJournal.persist(fixture.player, journal);
        require(
            HologramConstructionJournal.publicationCacheHits == hits,
            "returning roots validates the original file again");
    }

    private static HologramSession session(HologramRecoveryDeliveryRegression.Fixture fixture) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        HologramSession session = (HologramSession) ((sun.misc.Unsafe) field.get(null))
            .allocateInstance(HologramSession.class);
        Field player = HologramSession.class.getDeclaredField("player");
        player.setAccessible(true);
        player.set(session, fixture.player);
        session.job = 1;
        return session;
    }

    private static void failure(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Expected fail-closed rejection");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
