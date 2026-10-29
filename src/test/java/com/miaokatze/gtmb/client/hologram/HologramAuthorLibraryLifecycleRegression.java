package com.miaokatze.gtmb.client.hologram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real read/parse worker -> owner install/selection path, without smoke-intent interception or GL. */
public final class HologramAuthorLibraryLifecycleRegression {

    private HologramAuthorLibraryLifecycleRegression() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        for (String selected : new String[] { "", "local_A" }) asyncOpen(selected);
        saveAndRevisionRouting();
        stableToolIdentity();
        exactSourceIdentity();
        exactReceiptPair();
    }

    private static void asyncOpen(String previousSelection) throws Exception {
        HologramLibraryClient library = new HologramLibraryClient();
        library.select(false, previousSelection);
        ExecutorService io = Executors.newSingleThreadExecutor();
        ArrayDeque<Runnable> ownerQueue = new ArrayDeque<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<HologramLocalAuthorRuntime> installed = new AtomicReference<>();
        AtomicBoolean current = new AtomicBoolean(true);
        Path source = Files.createTempFile("gtmb-library-origin-", ".json");
        Thread ownerThread = Thread.currentThread();
        try {
            Files.write(source, source("finished B"));
            HologramAuthorOpen.begin(() -> {
                SimpleAssert.that(Thread.currentThread() != ownerThread, "source IO is really asynchronous");
                return Files.readAllBytes(source);
            }, io, task -> { synchronized (ownerQueue) { ownerQueue.add(task); } }, current::get, (document, bytes) -> {
                SimpleAssert.that(Thread.currentThread() == ownerThread, "installation runs on owner");
                HologramAuthorLibraryOrigin origin = new HologramAuthorLibraryOrigin("local_B", false,
                    document.revision(), null, bytes);
                library.completeAuthorOpen(document, "local_B", origin,
                    (value, provenance) -> installed.set(new HologramLocalAuthorRuntime(value, false, provenance)));
            }, failure::set);
            io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            SimpleAssert.eq(previousSelection, library.selected(false), "read completion alone cannot change selection");
            drain(ownerQueue);
            if (failure.get() != null) throw failure.get();
            SimpleAssert.eq("local_B", library.selected(false), "successful source installation selects B, never A");
            HologramLocalAuthorRuntime runtime = installed.get();
            SimpleAssert.that(runtime != null && runtime.finishedSourceCurrent() && !runtime.dirty(),
                "finished source remains saved and directly placeable without invented processing completion");
            SimpleAssert.eq("local_B", runtime.view().data.getString("selectedPresetId"),
                "displayed source and singleton selection agree");
            SimpleAssert.that(!runtime.view().data.getBoolean("blueprintProcessingRequired")
                && !runtime.view().data.getBoolean("blueprintProcessingComplete"),
                "original artifact placement is independent of a fabricated processing certificate");
            runtime.view().authorProjection.close();

            // Actual async failure and a retired owner both preserve the previous successful source/selection.
            Files.write(source, new byte[] { '{' });
            HologramAuthorOpen.begin(() -> Files.readAllBytes(source), io,
                task -> { synchronized (ownerQueue) { ownerQueue.add(task); } }, current::get,
                document -> { throw new AssertionError("invalid source installed"); }, failure::set);
            io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            drain(ownerQueue);
            SimpleAssert.that(failure.get() != null, "failed read reports an error rather than selecting a row");
            SimpleAssert.eq("local_B", library.selected(false), "read failure preserves prior selected artifact");
            Files.write(source, source("late C"));
            HologramAuthorOpen.begin(() -> Files.readAllBytes(source), io,
                task -> { synchronized (ownerQueue) { ownerQueue.add(task); } }, current::get,
                document -> library.completeAuthorOpen(document, "local_C", null, (value, origin) -> {}), failure::set);
            io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            current.set(false);
            drain(ownerQueue);
            SimpleAssert.eq("local_B", library.selected(false), "late completion cannot select another source");
        } finally {
            io.shutdownNow();
            Files.deleteIfExists(source);
        }
    }

    private static void saveAndRevisionRouting() throws Exception {
        AuthorDocument document = HologramLibraryClient.readAuthorSource(source("finished"));
        for (String id : new String[] { "local_saved", "public_saved", "integrated_saved" }) {
            boolean integrated = id.startsWith("integrated_");
            HologramAuthorLibraryOrigin origin = new HologramAuthorLibraryOrigin(id, integrated,
                document.revision(), new NBTTagList(), source("finished"));
            HologramLocalAuthorRuntime runtime = new HologramLocalAuthorRuntime(document, false, origin);
            try {
                SimpleAssert.eq(HologramLocalAuthorRuntime.SaveRoute.FINALIZED, runtime.saveRoute(true),
                    "ordinary Save of untouched finished source does not require local processing");
                AtomicBoolean dispatched = new AtomicBoolean();
                HologramUiLibrary.dispatchAuthorSave(runtime, id, 63, -27,
                    (actualOwner, policy, replace, yaw, pitch) -> {
                        SimpleAssert.that(actualOwner == runtime && policy == null,
                            "actual UI argument dispatch must not call confirmedPolicy for finalized source");
                        SimpleAssert.eq(id, replace, "actual UI dispatch preserves overwrite identity");
                        SimpleAssert.that(yaw == 63 && pitch == -27, "actual UI dispatch preserves thumbnail camera");
                        dispatched.set(true);
                    });
                SimpleAssert.that(dispatched.get(), "untouched ordinary Save reaches submission, not a no-op");
                HologramAuthorSaveHandoff cameraSave = new HologramAuthorSaveHandoff(runtime, document, null,
                    "local_target", "local_target", "", 51, () -> true, 63, -27);
                SimpleAssert.that(cameraSave.finalized && cameraSave.yaw == 63 && cameraSave.pitch == -27,
                    "camera-only Save retains actual thumbnail pose and enters finalized server handoff");
                cameraSave.requireReconciliable(source("owner reviewed"));
                runtime.rename("edited");
                SimpleAssert.that(!runtime.finishedSourceCurrent(), "editing cannot place stale saved content as current");
                SimpleAssert.eq(HologramLocalAuthorRuntime.SaveRoute.PROCESS, runtime.saveRoute(true),
                    "edited content uses actual processing before formal Save");
                runtime.setProcessing(new NBTTagCompound(), true);
                SimpleAssert.eq(HologramLocalAuthorRuntime.SaveRoute.SUBMIT, runtime.saveRoute(true),
                    "completed current policy routes ordinary Save to formal server handoff, not recovery IO");
                dispatched.set(false);
                HologramUiLibrary.dispatchAuthorSave(runtime, id, 0, 0,
                    (actualOwner, policy, replace, yaw, pitch) -> {
                        SimpleAssert.that(policy != null, "edited UI submission requires exact confirmed policy");
                        dispatched.set(true);
                    });
                SimpleAssert.that(dispatched.get(), "processed edited source reaches formal submission");
                AuthorDocument submitted = runtime.document();
                runtime.librarySaved(submitted, "local_committed", false, null, source("reviewed content"));
                SimpleAssert.that(runtime.finishedSourceCurrent() && !runtime.dirty(),
                    "only committed exact revision restores finished source placement");
                SimpleAssert.eq("reviewed content", runtime.document().title(),
                    "committed reviewed source, not pre-review raw source, is displayed and placeable");
                runtime.undo();
                SimpleAssert.that(runtime.dirty() && !runtime.finishedSourceCurrent(),
                    "Save preserves undo; undoing reviewed reconciliation invalidates finished provenance");
                SimpleAssert.eq("edited", runtime.document().title(), "undo restores pre-review edited content");
                runtime.libraryReceiptChanged(runtime.document());
                SimpleAssert.that(runtime.libraryOrigin() == null && runtime.dirty() && !runtime.saving(),
                    "post-commit external replacement is terminal: preserve source, unlock, revoke finished provenance");
                runtime.rename("newer");
                runtime.librarySaved(submitted, "local_stale", false, null, source("late"));
                SimpleAssert.that(!runtime.finishedSourceCurrent() && runtime.dirty(),
                    "late receipt cannot mark a newer document saved or placeable");
            } finally {
                runtime.view().authorProjection.close();
            }
        }
        HologramLocalAuthorRuntime recovery = new HologramLocalAuthorRuntime(document, true, null);
        try {
            SimpleAssert.that(!recovery.finishedSourceCurrent(), "saved recovery is not a finished artifact");
            SimpleAssert.eq(HologramLocalAuthorRuntime.SaveRoute.PROCESS, recovery.saveRoute(true),
                "ordinary Save of recovery still follows real formal processing, not a true flag");
        } finally {
            recovery.view().authorProjection.close();
        }
    }

    /** Invoked by the server fixture using production basicState + blueprint scalar writers. */
    public static void serverSnapshot(NBTTagCompound activated, NBTTagCompound processed, String localId,
        String integratedId, long task) {
        HologramAuthorSaveHandoff handoff = new HologramAuthorSaveHandoff(null, null, new NBTTagCompound(),
            localId, localId, integratedId, task, () -> true, 0, 0);
        SimpleAssert.that(handoff.activation(activated), "actual server activation snapshot matches formal save");
        NBTTagCompound wrong = (NBTTagCompound) activated.copy();
        wrong.setLong("blueprintClientTaskToken", task + 1);
        SimpleAssert.that(!handoff.activation(wrong), "other task cannot activate formal Save");
        handoff.session = activated.getString("session");
        handoff.processSequence = processed.getLong("uiSequence");
        SimpleAssert.that(handoff.processed(processed), "actual processing receipt advances the formal save route");
        handoff.saving = true;
        SimpleAssert.that(!handoff.processed(processed), "duplicate processing receipt cannot submit twice");
    }

    private static void stableToolIdentity() {
        net.minecraft.item.Item item = new net.minecraft.item.Item();
        net.minecraft.item.ItemStack original = new net.minecraft.item.ItemStack(item);
        original.setTagCompound(new NBTTagCompound());
        original.getTagCompound().setString("gtitHologramToolId", "projector-A");
        net.minecraft.item.ItemStack synced = original.copy();
        synced.getTagCompound().setString("gtitHologramSelectedPreset", "private-new");
        SimpleAssert.that(HologramAuthorSubmission.sameTool(original, "projector-A", synced),
            "server-owned selected-preset sync replaces ItemStack without cancelling formal save");
        synced.getTagCompound().setString("gtitHologramToolId", "projector-B");
        SimpleAssert.that(!HologramAuthorSubmission.sameTool(original, "projector-A", synced),
            "different projector cannot inherit formal save ownership");
    }

    private static void exactSourceIdentity() throws Exception {
        byte[] original = source("original");
        HologramAuthorLibraryOrigin origin = new HologramAuthorLibraryOrigin("local_source", false, 0, null, original);
        origin.requireSource(original.clone());
        boolean rejected = false;
        try { origin.requireSource(source("external replacement")); }
        catch (java.io.IOException expected) { rejected = true; }
        SimpleAssert.that(rejected, "same library id cannot overwrite or place an externally replaced source");
        original[0] = 0;
        SimpleAssert.that(origin.source()[0] != 0, "origin owns immutable exact finalized bytes");
    }

    private static void exactReceiptPair() throws Exception {
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("jsonSha256", "reviewed-json");
        receipt.setString("previewSha256", "reviewed-png");
        HologramLibraryClient.requireReceiptPair(receipt, "reviewed-json", "reviewed-png");
        for (String[] replaced : new String[][] { { "external-json", "reviewed-png" },
            { "reviewed-json", "external-png" } }) {
            boolean rejected = false;
            try { HologramLibraryClient.requireReceiptPair(receipt, replaced[0], replaced[1]); }
            catch (java.io.IOException expected) { rejected = true; }
            SimpleAssert.that(rejected, "external replacement of either pair member cannot acquire reviewed provenance");
        }
    }

    private static byte[] source(String title) {
        return ("{\"blueprint\":{\"schema\":2,\"name\":\"" + title
            + "\",\"cells\":[{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\"}]}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void drain(ArrayDeque<Runnable> queue) {
        while (true) {
            Runnable next;
            synchronized (queue) { next = queue.poll(); }
            if (next == null) return;
            next.run();
        }
    }
}
