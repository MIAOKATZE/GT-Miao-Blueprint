package com.miaokatze.gtmb.client.hologram;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramMaterialQuote;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises the actual client quote consumer with wire identities and counts that broke the old material list. */
public final class HologramMaterialReviewRegression {

    private HologramMaterialReviewRegression() {}

    public static void run() {
        NBTTagCompound state = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        ItemStack tagged = new ItemStack(Items.diamond, 1, 7);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("displayVariant", "first");
        tagged.setTagCompound(tag);
        NBTTagCompound first = HologramMaterialQuote.itemRow(tagged, 4294967301L, 2147483649L);
        // Deliberately contradict the process-local native ID. The stable registry identity must win.
        first.getCompoundTag("item")
            .setShort("id", (short) net.minecraft.item.Item.getIdFromItem(Items.stick));
        rows.appendTag(first);
        tagged.getTagCompound()
            .setString("displayVariant", "second");
        rows.appendTag(HologramMaterialQuote.itemRow(tagged, 2, 1));
        NBTTagCompound unknown = (NBTTagCompound) first.copy();
        unknown.setString("itemRegistry", "gtmb_regression:missing_registry_item");
        rows.appendTag(unknown);
        NBTTagCompound energy = new NBTTagCompound();
        energy.setString("kind", "energy");
        energy.setLong("required", Long.MAX_VALUE);
        energy.setString("requiredExact", "123456789012345678901234567890");
        rows.appendTag(energy);
        NBTTagCompound quote = new NBTTagCompound();
        quote.setBoolean("complete", true);
        quote.setTag("rows", rows);
        state.setTag("materialQuote", quote);

        HologramMaterialReview model = new HologramMaterialReview();
        SimpleAssert.that(model.update(state) && model.ready() && model.size() == 4, "consumer retains exact rows");
        HologramMaterialReview.Row a = model.row(0), b = model.row(1);
        SimpleAssert.that(
            a.item.getItem() == Items.diamond && a.item.getItemDamage() == 7,
            "registry and damage determine the visible icon, ignoring an unrelated numeric id");
        SimpleAssert.that(
            "4294967301".equals(a.required) && "2147483649".equals(a.available) && "2147483652".equals(a.missing),
            "all visible material counts preserve values above signed int");
        SimpleAssert.that(
            "first".equals(
                a.item.getTagCompound()
                    .getString("displayVariant"))
                && "second".equals(
                    b.item.getTagCompound()
                        .getString("displayVariant"))
                && a.index != b.index,
            "same-name items with different complete NBT stay independently inspectable");
        a.item.getTagCompound()
            .setString("displayVariant", "client-only");
        SimpleAssert.that(
            "first".equals(
                first.getCompoundTag("item")
                    .getCompoundTag("tag")
                    .getString("displayVariant")),
            "render samples are detached from the authoritative quote");
        SimpleAssert
            .that(model.row(2).item == null, "unavailable registry never falls back to a misleading numeric icon");
        SimpleAssert.that(
            "123456789012345678901234567890".equals(model.row(3).required),
            "energy display uses exact decimal quantities beyond long");
        SimpleAssert.that(
            !model.update(state) && model.row(0) == a,
            "an unchanged snapshot reuses decoded samples across rendered frames");
        // Restore the detached render sample before checking reuse across a separate wire quote.
        a.item.getTagCompound()
            .setString("displayVariant", "first");
        NBTTagCompound projected = (NBTTagCompound) quote.copy();
        projected.getTagList("rows", 10)
            .getCompoundTagAt(1)
            .setLong("available", 2);
        projected.getTagList("rows", 10)
            .getCompoundTagAt(1)
            .setLong("missing", 0);
        state.setTag("materialQuote", projected);
        SimpleAssert.that(
            !model.update(state) && model.row(0) == a && model.row(1) != b,
            "a voucher projection keeps list geometry and unaffected decoded rows while updating changed quantities");
        SimpleAssert.that("0".equals(model.row(1).missing), "projected shortage updates without rebuilding the list");
        state.setTag("materialQuote", quote);
        quote.setLong("planRevision", 3);
        state.setLong("planRevision", 4);
        model.update(state);
        SimpleAssert.that(!model.ready(), "stale material quote cannot enable the new transaction button");
        metadataFence();
        contextAndUnchangedChoices();
        incompleteEstimateCannotEnableConfirmation();
    }

    private static void incompleteEstimateCannotEnableConfirmation() {
        NBTTagCompound row = HologramMaterialQuote.itemRow(new ItemStack(Items.diamond), 37, 0);
        row.setBoolean("voucherEligible", true);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        NBTTagCompound quote = new NBTTagCompound();
        quote.setBoolean("complete", false);
        quote.setBoolean("localEstimate", true);
        quote.setTag("rows", rows);
        NBTTagCompound state = new NBTTagCompound();
        state.setTag("materialQuote", quote);
        HologramMaterialReview model = new HologramMaterialReview();
        model.update(state);
        SimpleAssert.that(
            !model.ready() && !model.hasItemShortage() && !model.hasVoucherDemand(),
            "an incomplete estimate cannot enable confirmation, material orders or voucher controls");
    }

    private static void contextAndUnchangedChoices() {
        try {
            HologramUiModules ui = new HologramUiModules(null);
            NBTTagCompound source = new NBTTagCompound();
            source.setString("session", "context");
            source.setBoolean("virtual", true);
            source.setBoolean("placementDeidentificationChosen", true);
            source.setInteger("placementDeidentificationLevel", 1);
            NBTTagCompound processing = new NBTTagCompound();
            source.setTag("processing", processing);
            java.lang.reflect.Field state = HologramUiSession.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(ui.session, new HologramState(source));
            SimpleAssert.that(
                !ui.nbt.hasPlacementNbtChoices() && !ui.identity.hasPlacementIdentityChoices(),
                "plain blocks expose no ineffective NBT or identity settings in material review");
            NBTTagList keys = new NBTTagList();
            keys.appendTag(new net.minecraft.nbt.NBTTagString("configuration"));
            processing.setTag("nbtKeys", keys);
            processing.setInteger("requiredStages", 1 << 1);
            state.set(ui.session, new HologramState(source));
            SimpleAssert.that(
                ui.nbt.hasPlacementNbtChoices() && ui.identity.hasPlacementIdentityChoices(),
                "server-analyzed editable keys and identity stage retain useful manual configuration");
            for (String name : new String[] { "nbtPlacement", "openedPlacementAll", "openedPlacementCovers" }) {
                java.lang.reflect.Field field = HologramUiNbtEditor.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(ui.nbt, true);
            }
            ui.nbt.nbtDrawer(true);
            SimpleAssert.that(
                ui.nbt.activateControl(173) && !ui.nbt.nbtDrawer()
                    && ui.session.sequence() == 0
                    && ui.session.pendingSequence() < 0,
                "applying an unchanged placement NBT choice closes the drawer without a request or revalidation");
            java.lang.reflect.Field placement = HologramUiDeidentification.class
                .getDeclaredField("deidentificationPlacement");
            placement.setAccessible(true);
            placement.setBoolean(ui.identity, true);
            java.lang.reflect.Field drawer = HologramUiDeidentification.class
                .getDeclaredField("deidentificationDrawer");
            drawer.setAccessible(true);
            drawer.setBoolean(ui.identity, true);
            SimpleAssert.that(
                ui.identity.activateDeidentification(213) && !drawer.getBoolean(ui.identity)
                    && ui.session.sequence() == 0
                    && ui.session.pendingSequence() < 0,
                "reapplying the same chosen identity level performs no network action or review reset");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot install actual no-op review controls", failure);
        }
    }

    private static void metadataFence() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "quote-session");
        source.setLong("planRevision", 12);
        source.setLong("uiSequence", 5);
        source.setLong("placementReviewNonce", 3);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(new NBTTagCompound());
        source.setTag("cells", cells);
        HologramState base = new HologramState(source);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("materialQuoteOnly", true);
        receipt.setString("session", "quote-session");
        receipt.setLong("planRevision", 12);
        receipt.setLong("uiSequence", 6);
        receipt.setLong("placementReviewNonce", 4);
        receipt.setBoolean("targetClosed", true);
        receipt.setTag("cells", new NBTTagList());
        NBTTagCompound voucher = new NBTTagCompound();
        voucher.setBoolean("selected", true);
        receipt.setTag("decorativeVoucher", voucher);
        HologramState next = HologramMaterialQuoteUpdates.apply(base, receipt);
        SimpleAssert.that(
            next != null && next.cells == base.cells
                && next.regions == base.regions
                && next.structureMembers == base.structureMembers
                && next.parsedCells == 0,
            "lightweight quote retains parsed geometry, spatial indices and membership references");
        SimpleAssert.that(
            next.data.getCompoundTag("decorativeVoucher")
                .getBoolean("selected")
                && !next.data.getBoolean("targetClosed")
                && !base.data.getCompoundTag("decorativeVoucher")
                    .getBoolean("selected"),
            "quote whitelist updates only material metadata without mutating the previous snapshot");
        SimpleAssert.that(
            next.data.getLong("uiSequence") == 5 && next.data.getLong("materialQuoteSequence") == 6,
            "a material ACK has its own sequence and cannot invalidate an in-flight full encoding");
        HologramState delayedFull = new HologramState(source);
        HologramState retained = HologramMaterialQuoteUpdates.retain(delayedFull, next);
        SimpleAssert.that(
            retained.cells == delayedFull.cells && retained.data.getLong("placementReviewNonce") == 4
                && retained.data.getCompoundTag("decorativeVoucher")
                    .getBoolean("selected"),
            "a delayed full baseline installs its geometry while retaining the newer voucher receipt");
        source.setLong("uiSequence", 7);
        HologramState newerFull = new HologramState(source);
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.retain(newerFull, next) == newerFull,
            "a newer ordinary action remains authoritative over the prior quote");
        source.setLong("uiSequence", 5);
        source.setLong("planRevision", 13);
        HologramState changedPlan = HologramMaterialQuoteUpdates.retain(new HologramState(source), next);
        SimpleAssert.that(
            changedPlan.data.getLong("materialQuoteSequence") == 6
                && !changedPlan.data.getCompoundTag("decorativeVoucher")
                    .getBoolean("selected"),
            "a newer plan keeps the local sequence watermark without inheriting an old voucher projection");
        receipt.setLong("placementReviewNonce", 2);
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.apply(next, receipt) == null,
            "a stale voucher response cannot roll back a newer review nonce");
        receipt.setLong("placementReviewNonce", 5);
        receipt.setLong("uiSequence", 4);
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.apply(next, receipt) == null,
            "an old command response cannot replace current material metadata");
        receipt.setLong("uiSequence", 7);
        receipt.setLong("planRevision", 13);
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.apply(next, receipt) == null,
            "a different plan requires an actual snapshot, not a material-only reply");
        receipt.setLong("planRevision", 12);
        receipt.setString("session", "other-session");
        SimpleAssert.that(
            HologramMaterialQuoteUpdates.apply(next, receipt) == null,
            "a quote from another workstation cannot enter this review");
    }

    public static void main(String[] args) {
        run();
    }
}
