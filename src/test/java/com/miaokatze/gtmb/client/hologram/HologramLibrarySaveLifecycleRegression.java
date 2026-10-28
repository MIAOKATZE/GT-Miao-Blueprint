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
            encodingFailureAndImmediateRetry();
            timeoutAndHeaderRefusalKeepTheirFailureReason();
            freshPreviewAndAdmissionOwnTheirIdleBudget();
            exactUiTerminalReleasesSaveButtons();
            cancelledSourceRetainsBaselineOrRecoversOnce();
            confirmationAdvancesUiAcknowledgement();
            cancelledCatalogCanRefreshAgain();
            preparationActivityIsCorrelatedAndBounded();
            savedPreviewOwnsItsObservedAngles();
            HologramLibraryTransferOwnershipRegression.run();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Blueprint save lifecycle", failure);
        }
    }

    private static void savedPreviewOwnsItsObservedAngles() {
        HologramState clicked = state(91, 12, "saveBlueprintReplace", false, false);
        HologramSavePreviewPose pose = new HologramSavePreviewPose(91, 8, clicked, .87, .42);
        HologramLibraryClient.SaveCommand command = new HologramLibraryClient.SaveCommand(
            91,
            clicked.session,
            12,
            "saveBlueprintReplace");
        SimpleAssert.that(
            pose.accepts(91, 8, clicked.session, 42, command, clicked),
            "the click-time geometry retains its observed cover angles");
        HologramState prepared = state(91, 12, "saveBlueprintReplace", false, true);
        prepared.data.setLong("planRevision", 43);
        SimpleAssert.that(
            pose.accepts(91, 8, clicked.session, 43, command, prepared),
            "the exact save publication may advance revision during automatic preparation");
        SimpleAssert.that(
            !pose.accepts(91, 8, clicked.session, 43, command, clicked),
            "a header ahead of its owned publication waits for the corresponding snapshot");
        prepared.data.setLong("uiSequence", 13);
        SimpleAssert.that(
            !pose.accepts(91, 8, clicked.session, 43, command, prepared),
            "an unrelated later command cannot carry an earlier save's camera to a new revision");
        prepared.data.setLong("uiSequence", 12);
        prepared.data.setLong("blueprintClientTaskToken", 92);
        SimpleAssert.that(
            !pose.accepts(91, 8, clicked.session, 43, command, prepared),
            "a successor task publication cannot authorize camera revision advancement");
        SimpleAssert.that(
            !pose.accepts(92, 8, clicked.session, 42, command, clicked)
                && !pose.accepts(91, 9, clicked.session, 42, command, clicked)
                && !pose.accepts(91, 8, "different-source", 42, command, clicked)
                && !pose.accepts(91, 8, clicked.session, 41, command, clicked),
            "task, transport generation, session and older revisions fence the frozen view");
        HologramBlueprintPreview.Request capture = new HologramBlueprintPreview.Request(
            new NBTTagCompound(),
            pose.yaw,
            pose.pitch,
            () -> true,
            image -> {},
            failure -> {});
        clicked.data.setLong("planRevision", 100);
        SimpleAssert.eq(.87, capture.yaw, "the render request keeps the saved yaw after later UI changes");
        SimpleAssert.eq(.42, capture.pitch, "the render request keeps the saved pitch after later UI changes");
        SimpleAssert.eq(42L, pose.revision, "the save camera freezes the source revision instead of retaining its DTO");
    }

    private static void freshPreviewAndAdmissionOwnTheirIdleBudget() throws ReflectiveOperationException {
        Field singleton = null;
        for (Field field : net.minecraft.client.Minecraft.class.getDeclaredFields())
            if (field.getType() == net.minecraft.client.Minecraft.class
                && java.lang.reflect.Modifier.isStatic(field.getModifiers())) singleton = field;
        if (singleton == null) throw new NoSuchFieldException("Minecraft singleton");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        HologramLibraryClient client = client();
        try {
            singleton.set(
                null,
                ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(net.minecraft.client.Minecraft.class));
            Method start = HologramLibraryClient.class.getDeclaredMethod("beginBackgroundTask", String.class);
            start.setAccessible(true);
            Method finish = HologramLibraryClient.class
                .getDeclaredMethod("finishBackgroundTask", long.class, boolean.class, String.class);
            finish.setAccessible(true);
            SimpleAssert.eq(0L, get(client, "lastActivity"), "a fresh client has no prior transport activity");
            long before = System.nanoTime();
            SimpleAssert.that((Boolean) start.invoke(client, "读取蓝图预览"), "fresh preview acquires its actual owner");
            long first = client.backgroundTaskToken(), started = (Long) get(client, "lastActivity");
            set(
                client,
                "savePreviewPose",
                new HologramSavePreviewPose(
                    first,
                    (Long) get(client, "generation"),
                    state(first, 1, "saveBlueprint", false, false),
                    .87,
                    .42));
            SimpleAssert.that(started >= before, "disk preparation receives an idle budget before its first tick");
            set(client, "preparing", true);
            client.tick();
            SimpleAssert
                .eq(first, client.backgroundTaskToken(), "first disk preparation tick cannot time out immediately");
            start.invoke(client, "读取蓝图预览");
            SimpleAssert.eq(started, get(client, "lastActivity"), "continuing an owner cannot refresh its idle budget");
            set(client, "preparing", false);
            finish.invoke(client, first, true, "preview read");
            SimpleAssert
                .eq(null, get(client, "savePreviewPose"), "successful task completion retires its cover camera");
            set(client, "lastActivity", System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(121));
            before = System.nanoTime();
            start.invoke(client, "准备蓝图内容");
            long second = client.backgroundTaskToken();
            set(
                client,
                "savePreviewPose",
                new HologramSavePreviewPose(
                    second,
                    (Long) get(client, "generation"),
                    state(second, 1, "saveBlueprint", false, false),
                    .87,
                    .42));
            SimpleAssert.that(
                second != first && (Long) get(client, "lastActivity") >= before,
                "admission after a completed disk task receives a fresh owner and idle budget");
            Method receive = HologramLibraryClient.class.getDeclaredMethod("receive", NBTTagCompound.class);
            receive.setAccessible(true);
            long expiredActivity = System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(121);
            set(client, "lastActivity", expiredActivity);
            NBTTagCompound stale = new NBTTagCompound();
            stale.setString("op", "contentReady");
            stale.setLong("clientTaskToken", first);
            receive.invoke(client, stale);
            SimpleAssert.eq(
                expiredActivity,
                get(client, "lastActivity"),
                "late content receipts cannot extend a successor task");
            set(client, "preparing", true);
            client.tick();
            SimpleAssert
                .eq(0L, client.backgroundTaskToken(), "a genuinely idle preparation still fails at the existing bound");
            SimpleAssert.eq(null, get(client, "savePreviewPose"), "failure and cancellation retire their cover camera");
            start.invoke(client, "升级服务器蓝图");
            long upgradeToken = client.backgroundTaskToken();
            Class<?> upgradeClass = Class.forName(HologramLibraryClient.class.getName() + "$UpgradeTask");
            Constructor<?> upgradeConstructor = upgradeClass
                .getDeclaredConstructor(long.class, long.class, boolean.class, Runnable.class);
            upgradeConstructor.setAccessible(true);
            java.util.concurrent.atomic.AtomicInteger refreshed = new java.util.concurrent.atomic.AtomicInteger();
            set(
                client,
                "upgradeTask",
                upgradeConstructor
                    .newInstance(upgradeToken, get(client, "generation"), true, (Runnable) refreshed::incrementAndGet));
            set(client, "preparing", true);
            NBTTagCompound report = new NBTTagCompound();
            report.setString("op", "upgradeLibraryResult");
            report.setLong("clientTaskToken", upgradeToken);
            report.setInteger("pages", 2);
            report.setInteger("skipped", 1);
            NBTTagList entries = new NBTTagList();
            NBTTagCompound skipped = new NBTTagCompound();
            skipped.setString("id", "public_fixture");
            skipped.setString("state", "skipped");
            skipped.setString("reason", "missing paired PNG");
            entries.appendTag(skipped);
            report.setTag("entries", entries);
            receive.invoke(client, report);
            SimpleAssert.that(
                client.busy() && refreshed.get() == 0,
                "an accepted partial upgrade report retains its owner until the last page");
            receive.invoke(client, report);
            SimpleAssert.eq(
                1,
                client.upgradeDetails()
                    .size(),
                "a duplicate report page cannot duplicate skip details");
            report.setInteger("page", 1);
            report.setBoolean("complete", true);
            report.setTag("entries", new NBTTagList());
            report.setLong("clientTaskToken", first);
            long activity = (Long) get(client, "lastActivity");
            receive.invoke(client, report);
            SimpleAssert.eq(activity, get(client, "lastActivity"), "a stale upgrade report cannot extend another task");
            report.setLong("clientTaskToken", upgradeToken);
            receive.invoke(client, report);
            receive.invoke(client, report);
            SimpleAssert.that(
                !client.busy() && refreshed.get() == 1
                    && client.upgradeDetails()
                        .get(0)
                        .contains("missing paired PNG"),
                "only the matching final page completes once and retains skip reasons");
        } finally {
            client.cancel();
            singleton.set(null, previous);
        }
    }

    private static void preparationActivityIsCorrelatedAndBounded() {
        HologramSavePreparationProgress progress = new HologramSavePreparationProgress();
        long second = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        NBTTagCompound event = new NBTTagCompound();
        event.setLong("clientTaskToken", 17);
        event.setString("session", "source");
        event.setLong("uiSequence", 23);
        event.setLong("planRevision", 31);
        event.setString("phase", "snapshot.prepare");
        event.setString("detail", "cells");
        event.setLong("done", 250);
        event.setLong("total", 1000);
        event.setString("unit", "rows");
        SimpleAssert.that(progress.receive(event, 17, "source", 23, 31, second), "current preparation is admitted");
        SimpleAssert.eq(
            25,
            progress.view(second)
                .percent(),
            "the actual completed rows drive the preparation bar");
        SimpleAssert.that(!progress.receive(event, 18, "source", 23, 31, 5 * second), "late task cannot extend retry");
        SimpleAssert
            .that(!progress.receive(event, 17, "other", 23, 31, 5 * second), "foreign session cannot keep save alive");
        SimpleAssert.that(
            !progress.receive(event, 17, "source", 24, 31, 5 * second),
            "old confirmation cannot extend current save");
        SimpleAssert.that(
            !progress.receive(event, 17, "source", 23, 32, 5 * second),
            "another revision cannot extend current save");
        event.setLong("done", 500);
        SimpleAssert.that(
            progress.receive(event, 17, "source", 23, 31, 150 * second),
            "real preparation may exceed 120 seconds");
        SimpleAssert.eq(
            50,
            progress.view(150 * second)
                .percent(),
            "later completed rows remain visible");
        SimpleAssert.that(
            progress.receive(event, 17, "source", 23, 31, 200 * second),
            "matching worker heartbeat covers a bounded atomic step");
        HologramSavePreparationProgress author = new HologramSavePreparationProgress();
        NBTTagCompound sourceEvent = (NBTTagCompound) event.copy();
        sourceEvent.removeTag("uiSequence");
        sourceEvent.setString("detail", "author_source");
        SimpleAssert.that(
            author.receiveAuthorSource(sourceEvent, 17, "source", 23, 31, second),
            "source extraction progress has its own task/session/revision authority without a save ACK");
        sourceEvent.setLong("done", 750);
        SimpleAssert.that(
            author.receiveAuthorSource(sourceEvent, 17, "source", 23, 31, 150 * second),
            "genuinely progressing source acquisition outlives the idle timeout");
        SimpleAssert.that(
            !author.receiveAuthorSource(sourceEvent, 18, "source", 23, 31, 151 * second)
                && !author.receiveAuthorSource(sourceEvent, 17, "source", 23, 32, 151 * second),
            "old task or source revision cannot refresh acquisition activity");
        sourceEvent.setLong("uiSequence", 24);
        SimpleAssert.that(
            !author.receiveAuthorSource(sourceEvent, 17, "source", 23, 31, 152 * second),
            "encoder sequence when present must match the real source request");
        HologramLibraryClient.SaveProgress deadline = new HologramLibraryClient.SaveProgress();
        deadline.begin(second, true);
        deadline.preparation(100 * second);
        deadline.preparation(200 * second);
        SimpleAssert.that(
            !deadline.waitingExpired(250 * second) && !deadline.idleExpired(250 * second),
            "matching preparation activity keeps the exact source wait alive beyond 120 seconds");
        SimpleAssert.that(
            deadline.waitingExpired(321 * second) && deadline.idleExpired(321 * second),
            "real source and transport loss expire after 120 seconds without correlated activity");
        SimpleAssert.that(
            !progress.receive(event, 17, "source", 23, 31, 751 * second),
            "unchanged heartbeat cannot hide a stalled worker forever");
        progress.clear();
        event.setString("detail", "cells");
        event.setLong("done", 500);
        progress.receive(event, 17, "source", 23, 31, second);
        event.setString("detail", "resources");
        progress.receive(event, 17, "source", 23, 31, 100 * second);
        event.setString("detail", "cells");
        progress.receive(event, 17, "source", 23, 31, 400 * second);
        event.setString("detail", "resources");
        SimpleAssert.that(
            !progress.receive(event, 17, "source", 23, 31, 701 * second),
            "alternating unchanged stage labels cannot conceal a stalled preparation");
        progress.clear();
        SimpleAssert
            .that(progress.view(800 * second) == null, "retired preparation leaves no progress in a later task");
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
            Runnable cancelledWork = worker.getQueue()
                .peek();
            client.cancel();
            SimpleAssert.eq(
                0,
                worker.getQueue()
                    .size(),
                "cancel discards obsolete queued IO before admitting retry");
            SimpleAssert.that(!client.busy(), "real cancellation releases loading when its generation is retired");
            SimpleAssert.that(
                client.rows(false) == existing && get(client, "store") == store,
                "cancel preserves the existing private catalog and its file store");
            long currentGeneration = (Long) get(client, "generation");
            SimpleAssert.that(currentGeneration > oldGeneration, "cancellation retires the old callback generation");
            client.refreshLocal();
            SimpleAssert.eq(
                1,
                worker.getQueue()
                    .size(),
                "cancel removes obsolete work so retry immediately owns the bounded queue");
            cancelledWork.run();
            SimpleAssert.that(
                client.busy() && client.rows(false) == existing,
                "a cancelled queue entry held by an external executor cannot perform IO or unlock retry");
            for (int retry = 0; retry < 10; retry++) {
                client.cancel();
                client.refreshLocal();
                SimpleAssert.eq(
                    1,
                    worker.getQueue()
                        .size(),
                    "repeated cancellation/retry cannot exhaust the IO queue");
            }
            currentGeneration = (Long) get(client, "generation");
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

    private static void encodingFailureAndImmediateRetry() throws ReflectiveOperationException {
        HologramLibraryClient client = client();
        begin(client, 101, 50, "saveBlueprintAs");
        NBTTagCompound failure = new NBTTagCompound();
        failure.setBoolean("transportError", true);
        failure.setString("errorCode", "snapshot.encode");
        failure.setString("session", "save-session");
        failure.setLong("uiSequence", 50);
        failure.setLong("clientTaskToken", 100);
        SimpleAssert.that(!client.transportFailed(failure), "an obsolete gzip failure cannot finish current owner");
        failure.setLong("clientTaskToken", 101);
        SimpleAssert.that(client.transportFailed(failure), "actual snapshot failure retires its save owner off screen");
        SimpleAssert.that(
            !client.busy() && client.backgroundTaskToken() == 0,
            "encoding failure clears preparation and download admission immediately");
        begin(client, 102, 50, "saveBlueprintAs");
        SimpleAssert.that(!client.transportFailed(failure), "late exact-sequence failure cannot terminate retry B");
        SimpleAssert.eq(102L, client.backgroundTaskToken(), "B retains its own token after A's late failure");
        client.cancel();
        SimpleAssert.that(!client.busy(), "pre-transfer cancellation reaches an immediate retryable terminal state");
        begin(client, 103, 50, "saveBlueprintAs");
        SimpleAssert.that(client.busy(), "a new save is admitted immediately after cancelling B");
        client.cancel();
    }

    private static void exactUiTerminalReleasesSaveButtons() throws ReflectiveOperationException {
        HologramUiModules ui = new HologramUiModules(null);
        set(ui.session, "state", state(111, 61, "saveBlueprintAs", false, true));
        set(ui.session, "libraryTaskSession", "save-session");
        set(ui.session, "libraryTaskToken", 111L);
        set(ui.session, "libraryTaskSequence", 61L);
        set(ui.session, "libraryTaskPresetSequence", 60L);
        set(ui.session, "pendingOperation", "confirmAeSave");
        ui.session.pendingSequence(61);
        ui.library.presetSaveSequence(60);
        ui.session.libraryTaskFinished("save-session", 61, 110, "old failure");
        ui.session.libraryTaskFinished("save-session", 60, 111, "old confirmation");
        SimpleAssert
            .eq(61L, ui.session.pendingSequence(), "wrong token/sequence cannot release another command ticket");
        ui.session.libraryTaskFinished("save-session", 61, 111, "cancelled");
        SimpleAssert.eq(-1L, ui.session.pendingSequence(), "exact cancellation releases save controls immediately");
        SimpleAssert.eq(
            -1L,
            ui.library.presetSaveSequence(),
            "confirmed save terminal also clears its original preset save acknowledgement marker");
        ui.session.pendingSequence(62);
        set(ui.session, "pendingOperation", "processBlueprint");
        ui.session.libraryTaskFinished("save-session", 61, 111, "late duplicate");
        SimpleAssert.eq(62L, ui.session.pendingSequence(), "late save terminal cannot clear a successor's pending UI");
    }

    private static void cancelledSourceRetainsBaselineOrRecoversOnce() throws ReflectiveOperationException {
        NBTTagCompound data = (NBTTagCompound) state(121, 70, "saveBlueprintAs", false, true).data.copy();
        NBTTagCompound cell = new NBTTagCompound();
        cell.setInteger("x", 1);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(cell);
        data.setTag("cells", rows);
        data.setInteger("cellCount", 1);
        data.setBoolean("snapshotPending", true);
        HologramState baseline = new HologramState(data);
        NBTTagCompound reply = new NBTTagCompound();
        reply.setString("session", "save-session");
        reply.setLong("planRevision", 42);
        reply.setInteger("cellCount", 1);
        reply.setLong("clientTaskToken", 121);
        reply.setLong("uiSequence", 70);
        HologramState retained = HologramClient.retainCancelledSaveSource(baseline, reply);
        SimpleAssert.that(
            retained != null && retained.cells == baseline.cells,
            "exact source cancellation retains the installed geometry without copying or rebuilding cells");
        SimpleAssert.that(
            !retained.data.getBoolean("snapshotPending") && !retained.data.getBoolean("blueprintSavePending"),
            "small source receipt retires preparation flags on an existing full baseline");
        data.setBoolean("areaSelectionShell", true);
        HologramState shell = new HologramState(data);
        SimpleAssert.eq(
            null,
            HologramClient.retainCancelledSaveSource(shell, reply),
            "a terminal receipt cannot manufacture geometry for an uninstalled shell");
        Field requested = HologramClient.class.getDeclaredField("fullSnapshotRequested");
        requested.setAccessible(true);
        boolean previousRequest = requested.getBoolean(null);
        Field recoveryKey = HologramClient.class.getDeclaredField("cancelledSourceRecovery");
        recoveryKey.setAccessible(true);
        String previousRecoveryKey = (String) recoveryKey.get(null);
        HologramState previousState = HologramClient.state;
        try {
            requested.setBoolean(null, false);
            HologramClient.state = baseline;
            SimpleAssert.that(
                !HologramClient.saveSourceCancelled(reply) && !requested.getBoolean(null),
                "an existing exact baseline requests no new full snapshot after cancellation");
            SimpleAssert.that(
                HologramClient.state.cells == baseline.cells,
                "production source cancellation preserves the original installed geometry");
            HologramClient.state = shell;
            requested.setBoolean(null, true);
            recoveryKey.set(null, "");
            SimpleAssert.that(
                HologramClient.saveSourceCancelled(reply),
                "missing geometry replaces its cancelled outstanding recovery request once");
            SimpleAssert.that(
                !HologramClient.saveSourceCancelled(reply),
                "duplicate cancellation cannot enqueue another full recovery");
            SimpleAssert.that(
                HologramClient.state == shell && shell.data.getBoolean("snapshotPending"),
                "recovery request keeps the shell pending until an actual baseline arrives");
        } finally {
            HologramClient.state = previousState;
            requested.setBoolean(null, previousRequest);
            recoveryKey.set(null, previousRecoveryKey);
        }
    }

    private static void timeoutAndHeaderRefusalKeepTheirFailureReason() throws ReflectiveOperationException {
        Field ownerField = HologramBackgroundTasks.class.getDeclaredField("OWNER");
        ownerField.setAccessible(true);
        HologramTaskOwner owner = (HologramTaskOwner) ownerField.get(null);
        SimpleAssert.that(!owner.busy(), "the terminal regression starts without stealing an existing owner");
        Field completionField = HologramBackgroundTasks.class.getDeclaredField("completion");
        completionField.setAccessible(true);
        String key = "watchdog-reason-" + System.nanoTime();
        HologramLibraryClient client = client();
        long first = owner.begin("library", key, "save");
        begin(client, first, 80, "saveBlueprintAs");
        client.failTask("transfer.idle.timeout", "传输超时：timeout-with-unsaved-edits");
        String failure = (String) completionField.get(null);
        SimpleAssert.that(
            !owner.busy() && failure.contains("timeout-with-unsaved-edits") && failure.contains("失败"),
            "the watchdog completes FAILURE using its actual reason before transport teardown");
        SimpleAssert.that(
            client.status()
                .contains("timeout-with-unsaved-edits")
                && client.status()
                    .contains("["),
            "timeout UI retains the actual failing phase");
        long second = owner.begin("library", key + "-retry", "save");
        begin(client, second, 81, "saveBlueprintAs");
        client.cancel();
        SimpleAssert.that(
            ((String) completionField.get(null)).contains("已取消"),
            "the same public cancel path reports explicit user CANCELLED");
        long third = owner.begin("library", key + "-header", "save");
        begin(client, third, 82, "saveBlueprintAs");
        Method receive = HologramLibraryClient.class.getDeclaredMethod("receive", NBTTagCompound.class);
        receive.setAccessible(true);
        NBTTagCompound header = new NBTTagCompound();
        header.setString("op", "downloadBegin");
        header.setBoolean("capturePreview", true);
        header.setLong("clientTaskToken", second);
        receive.invoke(client, header);
        SimpleAssert.eq(third, client.backgroundTaskToken(), "stale header cannot cancel the successor owner");
        header.setLong("clientTaskToken", third);
        // Empty receipt makes this same-owner native source inadmissible before any world or registry access.
        receive.invoke(client, header);
        SimpleAssert.that(
            !client.busy() && !owner.busy(),
            "same-owner rejected source header reaches an immediate terminal failure instead of waiting 120 seconds");
        SimpleAssert.that(
            ((String) completionField.get(null)).contains("失败") && client.status()
                .contains("来源"),
            "source refusal retains a precise failure reason");
        SimpleAssert.that(
            (Boolean) get(client, "downloadBeginObserved") && !(Boolean) get(client, "downloadBeginAccepted")
                && !(Boolean) get(client, "downloadDoneAccepted"),
            "bounded diagnostic facts distinguish rejected headers from received stream completion");
        long fourth = owner.begin("library", key + "-ignored-stream", "save");
        begin(client, fourth, 83, "saveBlueprintAs");
        set(client, "lastActivity", 123L);
        NBTTagCompound orphan = new NBTTagCompound();
        orphan.setString("op", "downloadChunk");
        orphan.setLong("clientTaskToken", fourth);
        orphan.setString("transfer", "00000000-0000-0000-0000-000000000001");
        receive.invoke(client, orphan);
        orphan.setString("op", "downloadDone");
        receive.invoke(client, orphan);
        SimpleAssert.eq(
            123L,
            get(client, "lastActivity"),
            "unaccepted stream packets cannot extend the real inactivity watchdog");
        client.cancel();
        long fifth = owner.begin("library", key + "-technical-error", "save");
        begin(client, fifth, 84, "saveBlueprintAs");
        client.failTask("download.invalid", "invalid encoded download");
        SimpleAssert.that(
            client.status()
                .contains("[")
                && client.status()
                    .contains(HologramProgressView.phase("library.prepare")),
            "translation of a technical failure preserves its actual UI phase suffix");
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
