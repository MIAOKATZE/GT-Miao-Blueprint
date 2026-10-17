package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.concurrent.ThreadPoolExecutor;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises the actual save owner and screen acknowledgement without a render context or disk worker. */
public final class HologramLibrarySaveLifecycleRegression {

    private HologramLibrarySaveLifecycleRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLibrarySaveLifecycleRegression.class, args)) return;
        run();
    }

    public static void run() {
        try {
            reviewIsNotCompletion();
            detachedFailureAndRetry();
            confirmationAdvancesUiAcknowledgement();
            cancelledCatalogCanRefreshAgain();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Blueprint save lifecycle", failure);
        }
    }

    private static void confirmationAdvancesUiAcknowledgement() throws ReflectiveOperationException {
        HologramUiModules ui = new HologramUiModules(null);
        HologramState review = state(91, 30, "saveBlueprintAs", true, false);
        review.data.getCompoundTag("aeSaveReview")
            .setBoolean("canConfirm", true);
        set(ui.session, "state", review);
        set(ui.session, "sequence", 30L);
        set(ui.diagnostics, "captureRenderSmokeAction", true);
        set(ui.ae, "aeReview", new HologramAeReviewModel(review.data.getCompoundTag("aeSaveReview"), true));
        ui.library.presetSaveSequence(30);
        ui.ae.activateAeReview(181);
        NBTTagCompound issued = (NBTTagCompound) get(ui.diagnostics, "capturedRenderSmokeAction");
        SimpleAssert.eq("confirmAeSave", issued.getString("op"), "the actual review control issues confirmation");
        SimpleAssert.eq(31L, issued.getLong("uiSequence"), "confirmation advances the request sequence");
        SimpleAssert.eq(
            issued.getLong("uiSequence"),
            ui.library.presetSaveSequence(),
            "the real confirmation control advances the saved UI acknowledgement marker with its request");
        HologramState old = state(91, 30, "saveBlueprintAs", false, false);
        old.data.setBoolean("blueprintOperationSuccess", true);
        SimpleAssert.that(!ui.library.prepareSnapshot(old).saveAck, "an earlier success cannot clear confirmation UI");
        HologramState saved = state(91, 31, "saveBlueprintAs", false, false);
        saved.data.setBoolean("blueprintOperationSuccess", true);
        HologramUiLibrary.SnapshotTransition transition = ui.library.prepareSnapshot(saved);
        SimpleAssert.that(transition.saveAck, "the final success matches the advanced UI acknowledgement marker");
        set(ui.library, "keepLibraryOffset", true);
        ui.library.acknowledgeSnapshot(saved, transition, false);
        SimpleAssert.eq(-1L, ui.library.presetSaveSequence(), "the matching success clears the pending UI save marker");
    }

    private static void cancelledCatalogCanRefreshAgain() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        HologramClientLibrary store = new HologramClientLibrary(Paths.get("build", "unused-library-lifecycle"));
        set(client, "store", store);
        NBTTagList existing = labels("existing");
        set(client, "local", existing);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) get(client, "worker");
        // Hold the real submitted IO tasks in their production queue; this case exercises admission and callbacks.
        worker.setThreadFactory(task -> null);
        try {
            long oldGeneration = (Long) get(client, "generation");
            client.refreshLocal();
            SimpleAssert.eq(
                1,
                worker.getQueue()
                    .size(),
                "the original refresh enters the actual bounded IO queue");
            SimpleAssert.that(client.busy(), "an admitted catalog refresh owns its loading flag");
            client.cancel();
            SimpleAssert.that(!client.busy(), "real cancellation releases loading when its generation is retired");
            SimpleAssert.that(
                client.rows(false) == existing && get(client, "store") == store,
                "cancel preserves the existing private catalog and its file store");
            long currentGeneration = (Long) get(client, "generation");
            SimpleAssert.that(currentGeneration > oldGeneration, "cancellation retires the old callback generation");
            client.refreshLocal();
            SimpleAssert.eq(
                2,
                worker.getQueue()
                    .size(),
                "retry is actually admitted instead of remaining loading forever");
            Method finish = HologramLibraryClient.class
                .getDeclaredMethod("finishLocalRefresh", long.class, String.class, NBTTagList.class);
            finish.setAccessible(true);
            finish.invoke(client, oldGeneration, "local:" + oldGeneration, labels("obsolete"));
            SimpleAssert.that(
                client.busy() && client.rows(false) == existing,
                "an old successful worker cannot clear the new loading flag or overwrite its existing rows");
            NBTTagList refreshed = labels("refreshed");
            finish.invoke(client, currentGeneration, "local:" + currentGeneration, refreshed);
            SimpleAssert.that(
                !client.busy() && client.rows(false) == refreshed,
                "only the current callback publishes rows and completes the retried refresh");
        } finally {
            worker.shutdownNow();
        }
    }

    private static NBTTagList labels(String name) {
        NBTTagCompound label = new NBTTagCompound();
        label.setString("id", name);
        label.setString("name", name);
        NBTTagList labels = new NBTTagList();
        labels.appendTag(label);
        return labels;
    }

    private static void reviewIsNotCompletion() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        begin(client, 71, 10, "saveBlueprintAs");
        HologramState review = state(71, 10, "saveBlueprintAs", true, false);
        HologramUiModules ui = new HologramUiModules(null);
        set(ui.session, "state", state(0, 9, "", false, false));
        ui.library.presetSaveSequence(10);
        SimpleAssert.that(
            !ui.library.prepareSnapshot(review).saveAck,
            "the real AE review snapshot is not a failed terminal save acknowledgement");
        SimpleAssert.that(client.snapshot(review), "the current save owner exposes its authoritative AE review");
        SimpleAssert.eq(71L, client.backgroundTaskToken(), "showing the review retains the original download token");
        SimpleAssert.that(client.busy(), "the save remains owned while waiting for user review");

        client.saveCommand("save-session", 11, "confirmAeSave");
        SimpleAssert.that(!client.snapshot(review), "a late pre-confirmation review cannot reopen submitted controls");
        HologramState rejectedConfirmation = state(71, 11, "saveBlueprintAs", true, false);
        SimpleAssert.that(
            client.snapshot(rejectedConfirmation),
            "a rejected confirmation returning the exact review can restore its controls");
        SimpleAssert.eq(71L, client.backgroundTaskToken(), "confirmation and retry keep the same save owner token");
        ui.library.presetSaveSequence(11);
        HologramState downloading = state(71, 11, "saveBlueprintAs", false, true);
        SimpleAssert.that(!client.snapshot(downloading), "accepted confirmation advances to the receipt transfer");
        SimpleAssert.that(
            !ui.library.prepareSnapshot(downloading).saveAck,
            "an issued save receipt still awaits JSON, preview and disk completion");
        SimpleAssert
            .eq(71L, client.backgroundTaskToken(), "the server receipt cannot retire the token before download");

        // downloadBegin consumes this flag before bytes enter the existing receipt-bound transfer path.
        set(client, "draftPending", false);
        client.snapshot(state(71, 11, "saveBlueprintAs", false, false));
        SimpleAssert.eq(71L, client.backgroundTaskToken(), "a later snapshot cannot cancel an admitted byte transfer");
    }

    private static void detachedFailureAndRetry() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        begin(client, 81, 20, "exportDraft");
        HologramState failure = state(81, 20, "exportDraft", false, false);
        failure.data.setString("status", "保存来源已变化，请重试");
        // No screen receives this snapshot: the background owner must settle it independently.
        SimpleAssert.that(!client.snapshot(failure), "a true failure does not advertise another review");
        SimpleAssert.eq(0L, client.backgroundTaskToken(), "a true failure releases the exact save token without a UI");
        SimpleAssert.that(!client.busy(), "failure clears preparing and download admission so a retry can start");
        SimpleAssert
            .eq("保存来源已变化，请重试", client.status(), "the actual server refusal is retained instead of cancellation");

        // Library export retries can reuse uiSequence, so session/sequence/operation alone are insufficient.
        begin(client, 82, 20, "exportDraft");
        client.snapshot(failure);
        SimpleAssert
            .eq(82L, client.backgroundTaskToken(), "an older task's exact-sequence failure cannot cancel retry");
        client.snapshot(state(81, 20, "exportDraft", true, false));
        SimpleAssert.eq(82L, client.backgroundTaskToken(), "an old review cannot occupy or finish the new save");
        client.snapshot(state(82, 19, "exportDraft", false, false));
        client.snapshot(state(82, 21, "exportDraft", false, false));
        client.snapshot(state(82, 20, "processBlueprint", false, false));
        HologramState otherSession = state(82, 20, "exportDraft", false, false);
        otherSession.data.setString("session", "other-session");
        client.snapshot(otherSession);
        SimpleAssert
            .eq(82L, client.backgroundTaskToken(), "other operations, sequences and sessions cannot unlock a save");
        HologramState opening = state(82, 20, "exportDraft", false, false);
        opening.data.setBoolean("workspaceUiOnly", true);
        client.snapshot(opening);
        HologramState preparing = state(82, 20, "exportDraft", false, false);
        preparing.data.setBoolean("snapshotPending", true);
        client.snapshot(preparing);
        SimpleAssert
            .eq(82L, client.backgroundTaskToken(), "presentation and preparation receipts are not save failures");
        HologramState currentFailure = state(82, 20, "exportDraft", false, false);
        client.snapshot(currentFailure);
        SimpleAssert.that(!client.busy(), "the retry still ends on its own precise terminal refusal");
        client.snapshot(currentFailure);
        SimpleAssert.eq(0L, client.backgroundTaskToken(), "duplicate failure cannot revive or finish another owner");
    }

    private static HologramLibraryClient client() throws ReflectiveOperationException {
        Constructor<HologramLibraryClient> constructor = HologramLibraryClient.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void begin(HologramLibraryClient client, long token, long sequence, String operation)
        throws ReflectiveOperationException {
        set(client, "backgroundTaskToken", token);
        set(client, "draftPending", true);
        set(client, "downloadExpected", true);
        set(client, "preparing", true);
        client.saveCommand("save-session", sequence, operation);
    }

    private static HologramState state(long token, long sequence, String operation, boolean review, boolean pending) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "save-session");
        data.setLong("planRevision", 42);
        data.setLong("uiSequence", sequence);
        data.setBoolean("virtual", true);
        data.setBoolean("blueprintProcessingComplete", true);
        data.setLong("blueprintClientTaskToken", token);
        data.setString("blueprintOperation", operation);
        data.setBoolean("blueprintOperationSuccess", false);
        data.setBoolean("blueprintSavePending", pending);
        if (review) {
            NBTTagCompound dto = new NBTTagCompound();
            dto.setLong("clientTaskToken", token);
            dto.setString("session", "save-session");
            dto.setLong("planRevision", 42);
            dto.setString("transactionId", "review-" + token);
            data.setTag("aeSaveReview", dto);
        }
        return new HologramState(data);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
