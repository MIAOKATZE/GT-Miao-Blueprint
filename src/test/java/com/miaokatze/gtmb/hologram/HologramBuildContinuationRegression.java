package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises the production admission/accounting continuations without a game or world mutation. */
public final class HologramBuildContinuationRegression {

    private HologramBuildContinuationRegression() {}

    /** Legacy fixtures await the same production admission without executing any world transaction. */
    static void drain(HologramSession session) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        for (int slice = 0; session.buildTask != null && slice < 100000 && System.nanoTime() < deadline; slice++) {
            HologramBuildPreparation.tickBuild(session, 127, Long.MAX_VALUE);
            if ((slice & 31) == 0) Thread.yield();
        }
        SimpleAssert.that(session.buildTask == null, "production build admission finishes within the fixture bound");
    }

    public static void run() {
        try {
            stableOrdering();
            sharedStockParity();
            coordinateStockParity();
            admissionWaitsAndFences();
            inventoryChangesRequireFreshConsent();
            authoritativeRecountPublishesTogether();
            finishWaitsForAllCells();
            transformedDraftValidationKeepsStrictLimits();
            automaticNetworkGroupsPublishAtomically();
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("Build continuation", failure);
        }
    }

    private static void stableOrdering() {
        List<Integer> indices = new ArrayList<>(), expected = new ArrayList<>();
        for (int i = 4096; i >= 0; i--) {
            indices.add(i);
            expected.add(i);
        }
        java.util.Comparator<Integer> compare = (a, b) -> Integer.compare(a % 17, b % 17);
        expected.sort(compare);
        HologramWorkOrderTask task = new HologramWorkOrderTask(indices, compare);
        task.advance();
        SimpleAssert.that(!task.done(), "one checkpoint cannot synchronously sort a large plan");
        int work = 1;
        while (!task.done() && work++ < 200000) task.advance();
        SimpleAssert.that(task.done(), "merge ordering makes bounded progress");
        SimpleAssert.eq(expected, indices, "bounded ordering is stable for equal dependency/spatial priorities");
        for (List<Integer> small : Arrays.asList(new ArrayList<Integer>(), new ArrayList<>(Arrays.asList(8)))) {
            HologramWorkOrderTask minimal = new HologramWorkOrderTask(small, compare);
            for (int step = 0; !minimal.done() && step < 8; step++) minimal.advance();
            SimpleAssert.that(minimal.done(), "empty/single work completes without a merge");
        }
    }

    private static NBTTagCompound row(ItemStack item, long demand, long shell) {
        NBTTagCompound result = item.writeToNBT(new NBTTagCompound());
        result.setLong("required", demand);
        result.setLong("shellRequired", shell);
        return result;
    }

    private static ItemStack machine(int damage, int id) {
        ItemStack item = new ItemStack(Items.stick, 1, damage);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("mID", id);
        item.setTagCompound(tag);
        return item;
    }

    private static void sharedStockParity() {
        List<NBTTagCompound> rows = new ArrayList<>();
        rows.add(row(machine(9, 1), 4, 4));
        rows.add(row(machine(9, 2), 8, 3));
        rows.add(row(machine(10, 2), 2, 2));
        int[] calls = { 0 };
        HologramMaterialQuote.ItemStock stock = new HologramMaterialQuote.ItemStock() {

            public long exact(ItemStack item, long limit) {
                calls[0]++;
                return item.getItemDamage() == 9 && item.getTagCompound()
                    .getInteger("mID") == 2 ? Math.min(5, limit) : 0;
            }

            public long fuzzy(ItemStack item, long limit) {
                calls[0]++;
                return item.getItemDamage() == 9 ? Math.min(9, limit) : 0;
            }
        };
        HologramMaterialQuote.CounterTask task = new HologramMaterialQuote.CounterTask(rows, stock, item -> true);
        int work = 0;
        while (!task.done() && work++ < 300) {
            int before = calls[0];
            task.advance();
            SimpleAssert.that(calls[0] - before <= 1, "a checkpoint performs at most one native stock observation");
        }
        SimpleAssert.that(task.done(), "fuzzy shared stock converges");
        SimpleAssert.eq(4L, task.available(rows.get(0)), "fuzzy shell uses the stock left after exact contents");
        SimpleAssert.eq(5L, task.available(rows.get(1)), "exact inventory contents cannot also pay shell demand");
        SimpleAssert.eq(0L, task.available(rows.get(2)), "metadata keeps a separate physical pool");
        HologramMaterialQuote.QuoteCounter reference = new HologramMaterialQuote.QuoteCounter(
            rows,
            stock,
            item -> true);
        for (NBTTagCompound row : rows) SimpleAssert
            .eq(reference.count(row), task.available(row), "bounded allocation preserves exact legacy ledger");

        List<NBTTagCompound> large = new ArrayList<>();
        for (int i = 0; i < 3000; i++) large.add(row(machine(9, i), 1, 1));
        HologramMaterialQuote.CounterTask sameShell = new HologramMaterialQuote.CounterTask(large, stock, item -> true);
        for (int i = 0; i < 3000; i++) sameShell.advance();
        SimpleAssert.that(
            !sameShell.done() && sameShell.available(large.get(2999)) == 0,
            "a same-shell group cannot hide an entire allocation in its first count callback");
    }

    private static void coordinateStockParity() throws ReflectiveOperationException {
        Method sensor = HologramMaterialQuoteRegression.class.getDeclaredMethod("sensorCard");
        sensor.setAccessible(true);
        Method card = HologramMaterialQuoteRegression.class
            .getDeclaredMethod("card", Item.class, int.class, String.class);
        card.setAccessible(true);
        Item item = (Item) sensor.invoke(null);
        ItemStack n = (ItemStack) card.invoke(null, item, 1, ""), m = (ItemStack) card.invoke(null, item, 2, "");
        List<NBTTagCompound> rows = Arrays.asList(
            row((ItemStack) card.invoke(null, item, 19, "relative"), 2, 0),
            row((ItemStack) card.invoke(null, item, 1, "absolute"), 1, 0),
            row((ItemStack) card.invoke(null, item, 29, "clear"), 1, 0));
        HologramMaterialQuote.ItemStock stock = new HologramMaterialQuote.ItemStock() {

            public long exact(ItemStack expected, long limit) {
                return Math.min(
                    limit,
                    (HologramCoordinatePolicies.paymentEquivalent(expected, n) ? 1L : 0L)
                        + (HologramCoordinatePolicies.paymentEquivalent(expected, m) ? 1L : 0L));
            }

            public long fuzzy(ItemStack expected, long limit) {
                throw new AssertionError("coordinate stock remains exact");
            }
        };
        HologramMaterialQuote.CounterTask task = new HologramMaterialQuote.CounterTask(rows, stock, ignored -> false);
        for (int step = 0; !task.done() && step < 300; step++) task.advance();
        SimpleAssert.that(task.done(), "coordinate priorities converge under one-member checkpoints");
        HologramMaterialQuote.QuoteCounter reference = new HologramMaterialQuote.QuoteCounter(
            rows,
            stock,
            ignored -> false);
        long total = 0;
        for (NBTTagCompound row : rows) {
            SimpleAssert.eq(
                reference.count(row),
                task.available(row),
                "relative/clear and absolute preserve shared-card priority");
            total += task.available(row);
        }
        SimpleAssert.eq(2L, total, "two coordinate card instances cannot be promised to more than two rows");
    }

    private static HologramSession fixture(int count) throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        Field provider = net.minecraft.world.World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new net.minecraft.world.WorldProviderSurface());
        Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
            "player",
            sun.misc.Unsafe.class,
            HologramServiceEditRegression.ReadOnlyWorld.class,
            UUID.class,
            String.class);
        factory.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) factory
            .invoke(null, unsafe, world, UUID.randomUUID(), "BuildContinuation");
        player.posY = 64;
        HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
        s.virtual = s.virtualPlacement = s.virtualLocked = true;
        s.blueprintAwaitingConfirmation = s.placementReviewIssued = true;
        s.virtualPreset = new HologramPresets.Preset();
        s.virtualPreset.schema = 2;
        s.virtualPreset.deidentificationLevel = 1;
        s.materialSettings.containers = s.materialSettings.me = false;
        s.capture = new HologramCapture(null, player.getHeldItem());
        for (int i = 0; i < count; i++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(null, i, 64, 0);
            cell.block = Blocks.stone;
            cell.anchor = true; // Read-only admissions; executing a transaction is outside this fixture's authority.
            cell.stableIndex = i;
            cell.status = "pending";
            s.capture.cells.add(cell);
            s.expectedBlocks.add(Blocks.air);
            s.expectedMeta.add(0);
            s.expectedTiles.add(null);
            s.expectedFacings.add(-1);
            s.planStatus.add("pending");
        }
        s.materialQuote = HologramMaterialQuote.fromRows(new NBTTagList(), new NBTTagList());
        s.materialQuote.setBoolean("complete", true);
        s.materialQuote.setString("session", s.id);
        s.materialQuote.setLong("planRevision", s.planRevision);
        s.materialQuote.setLong("voucherRulesRevision", DecorationVoucherConfig.revision());
        return s;
    }

    private static NBTTagCompound confirmation(HologramSession s) {
        NBTTagCompound action = new NBTTagCompound();
        action.setString("session", s.id);
        action.setLong("planRevision", s.planRevision);
        action.setLong("placementReviewNonce", s.placementReviewNonce);
        return action;
    }

    private static void close(HologramSession s) {
        s.player.playerNetServerHandler.netManager.channel()
            .close();
    }

    private static void admissionWaitsAndFences() throws ReflectiveOperationException {
        HologramSession s = fixture(513);
        try {
            HologramBuildPreparation.reviewedBuild(s, confirmation(s));
            SimpleAssert.that(
                HologramBuildPreparation.isPending(s) && s.job != 1,
                "reviewed action schedules admission without scanning/starting on the command stack");
            for (int i = 0; i < 10; i++) HologramBuildPreparation.tickBuild(s, 1, Long.MAX_VALUE);
            SimpleAssert
                .that(s.job != 1 && s.buildTask != null, "incomplete exact checks cannot authorize construction");
            s.placementReviewNonce++;
            HologramBuildPreparation.tickBuild(s, 1, Long.MAX_VALUE);
            SimpleAssert
                .that(s.buildTask == null && s.job != 1, "a late confirmation nonce cancels all unpublished work");
            s.buildTask = new HologramBuildTask(s, confirmation(s));
            for (int step = 0; s.buildTask != null && step < 100000; step++)
                HologramBuildPreparation.tickBuild(s, 1, Long.MAX_VALUE);
            SimpleAssert.eq(1, s.job, "only the fully verified plan publishes a running job");
            SimpleAssert.eq(513, s.work.size(), "all exact selected cells survive bounded work preparation");
            SimpleAssert
                .that(!s.blueprintAwaitingConfirmation, "successful admission consumes the review capability once");
            SimpleAssert.eq(0, s.cursor, "admission never executes a world transaction");
        } finally {
            close(s);
        }

        HologramSession changed = fixture(513);
        try {
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) changed.player.worldObj;
            world.occupiedX = 512;
            world.occupied = Blocks.stone;
            changed.buildTask = new HologramBuildTask(changed, confirmation(changed));
            for (int step = 0; changed.buildTask != null && step < 10000; step++)
                HologramBuildPreparation.tickBuild(changed, 1, Long.MAX_VALUE);
            SimpleAssert.that(
                changed.job != 1 && changed.localPlanPending,
                "the last selected cell is checked before admission and a changed world requests fresh exact planning");
        } finally {
            close(changed);
        }
    }

    private static void authoritativeRecountPublishesTogether()
        throws ReflectiveOperationException, java.io.IOException {
        HologramSession s = fixture(0);
        try {
            NBTTagList materials = new NBTTagList();
            materials.appendTag(row(new ItemStack(Items.stick), 5, 0));
            NBTTagCompound quote = HologramMaterialQuote.fromRows(materials, new NBTTagList());
            quote.setBoolean("complete", true);
            quote.setString("session", s.id);
            quote.setLong("planRevision", s.planRevision);
            quote.setLong("voucherRulesRevision", DecorationVoucherConfig.revision());
            s.materialQuote = quote;
            s.player.inventory.mainInventory[3] = new ItemStack(Items.stick, 2);
            HologramMaterialQuote.RecheckTask task = new HologramMaterialQuote.RecheckTask(s);
            int steps = 0;
            while (!task.done() && steps++ < 1000) {
                task.advance();
                SimpleAssert
                    .that(quote == s.materialQuote, "a partially recounted ledger never replaces the reviewed quote");
            }
            SimpleAssert.that(task.done(), "authoritative recheck completes both payable projections");
            task.publish();
            SimpleAssert.eq(3, s.planMissing, "recheck reports the current physical inventory shortage");
            SimpleAssert.eq(2, s.player.inventory.mainInventory[3].stackSize, "preflight never debits stock");
            HologramMaterialQuote.RecheckTask obsolete = new HologramMaterialQuote.RecheckTask(s);
            s.planRevision++;
            boolean refused = false;
            try {
                obsolete.advance();
            } catch (java.io.IOException expected) {
                refused = true;
            }
            SimpleAssert.that(refused, "a stale detached quote cannot observe or publish another plan");
        } finally {
            close(s);
        }
    }

    private static void inventoryChangesRequireFreshConsent() throws ReflectiveOperationException {
        for (boolean reviewedShortage : new boolean[] { false, true }) {
            HologramSession s = fixture(1);
            try {
                NBTTagList materials = new NBTTagList();
                NBTTagCompound nativeRow = row(new ItemStack(Items.stick), 5, 0);
                nativeRow.setLong("available", reviewedShortage ? 0 : 5);
                materials.appendTag(nativeRow);
                s.materialQuote = HologramMaterialQuote.fromRows(materials, new NBTTagList());
                s.materialQuote.setBoolean("complete", true);
                s.materialQuote.setString("session", s.id);
                s.materialQuote.setLong("planRevision", s.planRevision);
                s.materialQuote.setLong("voucherRulesRevision", DecorationVoucherConfig.revision());
                s.planMissing = reviewedShortage ? 5 : 0;
                s.player.inventory.mainInventory[3] = new ItemStack(Items.stick, 2);
                NBTTagCompound action = confirmation(s);
                action.setBoolean("forceExplicit", true);
                long reviewedNonce = s.placementReviewNonce;
                s.buildTask = new HologramBuildTask(s, action);
                for (int step = 0; s.buildTask != null && step < 10000; step++)
                    HologramBuildPreparation.tickBuild(s, 1, Long.MAX_VALUE);
                SimpleAssert
                    .eq(3, s.planMissing, "admission observes current stock before its final shortage decision");
                SimpleAssert
                    .eq(2, s.player.inventory.mainInventory[3].stackSize, "a review never spends physical stock");
                if (reviewedShortage) SimpleAssert.that(
                    s.job == 1 && s.jobPartialPlacement,
                    "a previously reviewed shortage plus explicit consent preserves partial placement");
                else SimpleAssert.that(
                    s.job != 1 && s.placementReviewNonce > reviewedNonce,
                    "a packet cannot invent shortage consent after previously sufficient inventory disappears");
            } finally {
                close(s);
            }
        }
    }

    private static void finishWaitsForAllCells() throws ReflectiveOperationException {
        HologramSession s = fixture(513);
        try {
            for (HologramCapture.Cell cell : s.capture.cells) {
                cell.anchor = false;
                cell.status = "satisfied";
            }
            s.capture.cells.get(512).status = "missing";
            s.work.add(1);
            s.job = 1;
            HologramPlacementFinishTask task = new HologramPlacementFinishTask(s);
            SimpleAssert.that(
                !task.step(s, 1, Long.MAX_VALUE) && s.job == 1,
                "finishing cannot announce success before controller notifications/accounting finish");
            boolean early = false;
            try {
                task.publish(s);
            } catch (IllegalStateException expected) {
                early = true;
            }
            SimpleAssert.that(early, "an incomplete terminal scan cannot publish completion");
            for (int step = 0; !task.step(s, 1, Long.MAX_VALUE) && step < 1000; step++)
                SimpleAssert.eq(1, s.job, "terminal accounting retains the active job state");
            task.publish(s);
            SimpleAssert.eq(5, s.job, "a missing tail cell remains partial completion");
        } finally {
            close(s);
        }
    }

    private static void transformedDraftValidationKeepsStrictLimits() throws java.io.IOException {
        for (int invalid = 0; invalid < 4; invalid++) {
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.id = "bounded_group_fixture";
            preset.name = "Bounded group fixture";
            for (int i = 0; i < 513; i++)
                preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), i, 0, 0, -1, false));
            if (invalid == 1) preset.cells.get(512).x = 0;
            if (invalid == 2) preset.cells.get(512).structureId = "absent_structure";
            if (invalid == 3) preset.cells.get(512).metadata = 65536;
            boolean legacyRejected = false, boundedRejected = false;
            try {
                HologramPresets.validate(preset);
            } catch (java.io.IOException expected) {
                legacyRejected = true;
            }
            HologramPreparedBlueprintValidation task = new HologramPreparedBlueprintValidation(preset);
            try {
                task.advance();
                SimpleAssert.that(!task.done(), "transformed source validation does not scan the tree on admission");
                for (int step = 0; !task.done() && step < 10000; step++) task.advance();
                SimpleAssert.that(task.done(), "strict transformed-draft validation makes bounded progress");
            } catch (java.io.IOException expected) {
                boundedRejected = true;
            }
            SimpleAssert.eq(
                legacyRejected,
                boundedRejected,
                "bounded draft validation retains legacy shape/position/membership rejection");
            SimpleAssert.eq(invalid != 0, boundedRejected, "tail corruption cannot be hidden by an early valid page");
        }
    }

    private static void automaticNetworkGroupsPublishAtomically()
        throws ReflectiveOperationException, java.io.IOException {
        HologramSession s = fixture(513);
        try {
            s.virtualPreset.id = "bounded_network_fixture";
            s.virtualPreset.name = "Bounded network fixture";
            s.processingSource = new HologramPresets.Preset(); // A former processing input cannot replace current
                                                               // edits.
            for (int i = 0; i < s.capture.cells.size(); i++) {
                HologramCapture.Cell cell = s.capture.cells.get(i);
                cell.anchor = false;
                cell.blueprintBlock = cell.blueprintItem = "minecraft:stone";
                s.capture.positions.put(HologramValues.position(cell), cell);
                ItemStack target = new ItemStack(Blocks.stone);
                s.pins.put(i, target);
                s.coordinatePins.put(HologramValues.position(cell), target.copy());
                s.planTargets.add(target.copy());
                if (i < 3) {
                    NBTTagCompound payload = new NBTTagCompound(), anchor = new NBTTagCompound();
                    anchor.setInteger("anchorDim", 73);
                    anchor.setInteger("anchorX", i < 2 ? 100 : 200);
                    anchor.setInteger("anchorY", 64);
                    anchor.setInteger("anchorZ", 321);
                    payload.setTag(HologramNetworkBindings.CAPTURE_ANCHOR, anchor);
                    payload.setString(HologramNetworkBindings.ROLE, "join");
                    if (i == 0) {
                        NBTTagList inventory = new NBTTagList();
                        inventory.appendTag(new ItemStack(Items.diamond, 5).writeToNBT(new NBTTagCompound()));
                        payload.setTag("Inventory", inventory);
                    }
                    cell.tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
                }
            }
            HologramCapture originalCapture = s.capture;
            HologramPresets.Preset originalPreset = s.virtualPreset;
            java.util.Map<Integer, ItemStack> originalPins = s.pins;
            String originalPayload = s.capture.cells.get(0).tileNbt;
            long nonce = s.placementReviewNonce;

            s.buildTask = new HologramBuildTask(s, confirmation(s));
            for (int step = 0; step < 30; step++) HologramBuildPreparation.tickBuild(s, 1, Long.MAX_VALUE);
            SimpleAssert.that(
                s.capture == originalCapture && s.virtualPreset == originalPreset && s.pins == originalPins,
                "automatic grouping does not publish a projected prefix");
            HologramBuildPreparation.cancel(s);
            SimpleAssert.that(
                s.capture == originalCapture && originalPayload.equals(s.capture.cells.get(0).tileNbt)
                    && s.undoEdits.isEmpty(),
                "cancelling automatic preparation leaves the editable source and history intact");

            s.buildTask = new HologramBuildTask(s, confirmation(s));
            boolean staged = false;
            long deadline = System.nanoTime() + 10_000_000_000L;
            for (int step = 0; s.buildTask != null && step < 500000 && System.nanoTime() < deadline; step++) {
                String phase = HologramBuildPreparation.phase(s);
                if ("build.network.stage".equals(phase) || "build.network.publish".equals(phase)) {
                    staged = true;
                    SimpleAssert.that(
                        s.capture == originalCapture && s.virtualPreset == originalPreset && s.pins == originalPins,
                        "validated private staging remains unpublished until the manifest swap");
                }
                HologramBuildPreparation.tickBuild(s, 1, Long.MAX_VALUE);
                if ((step & 31) == 0) Thread.yield();
            }
            SimpleAssert
                .that(s.buildTask == null && staged, "automatic grouping finishes the actual worker/staging lifecycle");
            SimpleAssert.that(
                s.capture != originalCapture && s.virtualPreset != originalPreset && s.pins != originalPins,
                "all transformed draft references publish as one coherent result");
            SimpleAssert.that(
                s.job != 1 && s.localPlanPending && s.networkBindingRequested && s.placementReviewNonce > nonce,
                "new automatic groups retain the original fresh-material/network-confirmation requirement");
            NBTTagCompound first = HologramBlueprintNbt.decode(s.capture.cells.get(0).tileNbt);
            NBTTagCompound second = HologramBlueprintNbt.decode(s.capture.cells.get(1).tileNbt);
            NBTTagCompound third = HologramBlueprintNbt.decode(s.capture.cells.get(2).tileNbt);
            SimpleAssert.eq(
                HologramNetworkBindings.group(first),
                HologramNetworkBindings.group(second),
                "same captured source network shares one generated logical group");
            SimpleAssert.that(
                !HologramNetworkBindings.group(first)
                    .equals(HologramNetworkBindings.group(third)),
                "independent source networks remain independent groups");
            SimpleAssert.that(
                !first.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR) && HologramBlueprintNbt.decode(originalPayload)
                    .hasKey(HologramNetworkBindings.CAPTURE_ANCHOR),
                "automatic deidentification preserves the immutable original payload for undo");
            SimpleAssert.eq(
                5,
                HologramBlueprintResources.plan(first).items.get(0).stackSize,
                "network preparation never erases physical inventory obligations");
            SimpleAssert.eq(1, s.undoEdits.size(), "one automatic grouping produces one complete undo record");
            SimpleAssert.eq(
                originalPayload,
                s.undoEdits.get(0).geometry.get(0).tileNbt,
                "bounded history captures the pre-group source rather than a transformed prefix");
        } finally {
            close(s);
        }
    }
}
