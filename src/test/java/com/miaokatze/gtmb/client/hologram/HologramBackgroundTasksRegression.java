package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Executable task lifetime tests; no source-text matching or GUI implementation mirrors. */
public final class HologramBackgroundTasksRegression {

    private HologramBackgroundTasksRegression() {}

    public static void run() {
        presentationValues();
        freshScreenBoundary();
        transportFailures();
        areaSnapshotReview();
        List<String> events = new ArrayList<>();
        HologramTaskOwner owner = new HologramTaskOwner(new HologramTaskOwner.Notifications() {

            @Override
            public void background(String label) {
                events.add("background:" + label);
            }

            @Override
            public void finished(String label, HologramTaskOwner.Result result, String detail) {
                events.add(result + ":" + label + ":" + detail);
            }
        });
        long download = owner.begin("library", "download-1", "download");
        SimpleAssert.that(download > 0 && owner.busy(), "IO owns the projector before receiving its first ACK");
        owner.detach();
        owner.detach();
        SimpleAssert.eq(1, events.size(), "closing twice announces the same background operation once");
        owner.attach();
        SimpleAssert.that(!owner.detached(), "reopen attaches presentation without starting another worker");
        SimpleAssert.eq(download, owner.begin("library", "download-1", "download"), "same request retains its token");
        SimpleAssert.eq("download-1", owner.key(), "reopen retains the original task context identity");
        owner.detach();
        SimpleAssert.eq(1, events.size(), "reopen and close again do not repeat the background announcement");
        SimpleAssert.eq(0L, owner.begin("server", "job-2", "build"), "a second operation cannot steal IO ownership");
        SimpleAssert.that(
            !owner.finish(download + 1, HologramTaskOwner.Result.SUCCESS, "early ACK"),
            "an unrelated acknowledgement cannot finish the download");
        SimpleAssert.that(owner.busy(), "closing UI and receiving an unrelated ACK keep the task running");
        owner.finish(download, HologramTaskOwner.Result.SUCCESS, "disk and server committed");
        SimpleAssert.eq(2, events.size(), "the authoritative completion emits one final notification");
        SimpleAssert.that(
            !owner.finish(download, HologramTaskOwner.Result.SUCCESS, "duplicate snapshot"),
            "repeated completion is ignored");
        SimpleAssert.eq(
            0L,
            owner.begin("library", "download-1", "download"),
            "old running snapshots cannot revive a finished task");
        long build = owner.begin("server", "job-2", "build");
        SimpleAssert.that(build > download, "tokens monotonically distinguish replacement operations");
        SimpleAssert.that(
            !owner.finish(download, HologramTaskOwner.Result.FAILURE, "late old failure"),
            "an old failure cannot unlock a new task");
        SimpleAssert.that(owner.busy(), "new task remains owned after an old terminal receipt");
        SimpleAssert
            .that(owner.advance("server", "job-2", "session-B:21"), "pause advances the accepted command receipt");
        SimpleAssert.eq(build, owner.token(), "pausing keeps the same operation owner");
        SimpleAssert.that(
            !owner.finishReceipt("server", "job-2", HologramTaskOwner.Result.SUCCESS, "old build ACK"),
            "old command completion cannot finish a paused task");
        SimpleAssert
            .that(owner.advance("server", "session-B:21", "session-B:22"), "resume advances to its new uiSequence");
        SimpleAssert.that(
            !owner.finishReceipt("server", "session-B:21", HologramTaskOwner.Result.SUCCESS, "late pause ACK"),
            "late pause receipt cannot complete resumed work");
        SimpleAssert.that(
            owner.advance("server", "session-B:22", "session-B:23"),
            "cancel waits for its exact terminal sequence");
        SimpleAssert.that(
            !owner.finishReceipt("server", "session-B:22", HologramTaskOwner.Result.SUCCESS, "late resume ACK"),
            "late resume completion cannot race the cancellation receipt");
        SimpleAssert.that(
            owner.finishReceipt("server", "session-B:23", HologramTaskOwner.Result.CANCELLED, "explicit cancel"),
            "the actual new cancellation receipt unlocks the owner");
        SimpleAssert.that(
            !owner.busy() && events.get(2)
                .startsWith("CANCELLED:"),
            "explicit cancellation is distinct from detaching the presentation");
        SimpleAssert.eq(
            0L,
            owner.begin("server", "session-B:21", "late paused snapshot"),
            "retired pause aliases cannot reopen cancelled work");
        SimpleAssert.eq(
            0L,
            owner.begin("server", "session-B:22", "late resumed snapshot"),
            "retired resume aliases cannot reacquire the projector");
        long disconnected = owner.begin("server", "job-3", "capture");
        owner.detach();
        owner.finish(disconnected, HologramTaskOwner.Result.FAILURE, "connection closed");
        SimpleAssert.that(
            !owner.busy() && events.get(4)
                .contains("connection closed"),
            "disconnect releases ownership with failure instead of claiming cross-connection persistence");
        owner.finish(disconnected, HologramTaskOwner.Result.SUCCESS, "late success");
        SimpleAssert.eq(5, events.size(), "late completion after disconnect produces no success sound or notification");
        long resumed = owner.begin("server", "session-C:30", "resumed build");
        owner.advance("server", "session-C:30", "session-C:31");
        owner.advance("server", "session-C:31", "session-C:32");
        SimpleAssert
            .that(owner.token() == resumed && owner.busy(), "pause and resume preserve the original active job");
        SimpleAssert.that(
            !owner.finishReceipt("server", "session-C:31", HologramTaskOwner.Result.SUCCESS, "stale"),
            "old paused completion cannot unlock resumed construction");
        SimpleAssert.that(
            owner.finishReceipt("server", "session-C:32", HologramTaskOwner.Result.SUCCESS, "real final job"),
            "the current authoritative completion unlocks resumed construction");
        SimpleAssert.that(!owner.busy() && events.size() == 6, "resumed success announces exactly once");
        long continued = owner.begin("server", "session-D:40", "construction");
        owner.advance("server", "session-D:40", "session-D:41");
        SimpleAssert.that(
            owner.rejectAdvance("server", "session-D:41", "session-D:40"),
            "a rejected pause returns receipt admission to the still-running original job");
        SimpleAssert.that(owner.token() == continued && owner.busy(), "rejected controls do not unlock the projector");
        SimpleAssert.that(
            !owner.finishReceipt("server", "session-D:41", HologramTaskOwner.Result.SUCCESS, "late rejected control"),
            "the rejected sequence cannot later finish the owner");
        SimpleAssert.that(
            owner.finishReceipt("server", "session-D:40", HologramTaskOwner.Result.SUCCESS, "original job finished"),
            "the original job can truthfully complete after its pause was rejected");
    }

    private static void presentationValues() {
        NBTTagCompound library = new NBTTagCompound();
        library.setBoolean("blueprintTab", true);
        library.setInteger("libraryOffset", 17);
        library.setString("sourceSwitchNonce", "server-owned-proposal");
        NBTTagCompound review = new NBTTagCompound();
        review.setLong("placementReviewNonce", 94);
        review.setString("transactionId", "active-save-review");
        NBTTagCompound values = new NBTTagCompound();
        values.setTag("library", library);
        values.setTag("review", review);
        HologramBackgroundUiState saved = new HologramBackgroundUiState("workspace-a", 8, values);
        library.setInteger("libraryOffset", 0);
        review.setLong("placementReviewNonce", 0);
        SimpleAssert.that(saved.matches("workspace-a", 8), "the original owner can restore presentation values");
        SimpleAssert.that(
            !saved.matches("workspace-b", 8) && !saved.matches("workspace-a", 9),
            "a different session or newer worker cannot restore an older presentation");
        NBTTagCompound first = saved.values();
        SimpleAssert.eq(
            17,
            first.getCompoundTag("library")
                .getInteger("libraryOffset"),
            "browser scroll is detached from the disposed presentation's mutable values");
        SimpleAssert.that(
            first.getCompoundTag("library")
                .getBoolean("blueprintTab"),
            "reopen retains the business tab rather than choosing a background page");
        SimpleAssert.eq(
            94L,
            first.getCompoundTag("review")
                .getLong("placementReviewNonce"),
            "capturing presentation does not erase its review identity");
        first.getCompoundTag("review")
            .setString("transactionId", "modified-copy");
        SimpleAssert.eq(
            "active-save-review",
            saved.values()
                .getCompoundTag("review")
                .getString("transactionId"),
            "repeated fresh screens receive independent review presentation copies");

        HologramProcessingFlow flow = new HologramProcessingFlow(new NBTTagCompound(), false, true);
        flow.requirements(48);
        flow.tendency(true);
        flow.next();
        flow.values()
            .setInteger("deidentificationLevel", 3);
        flow.choose();
        flow.next();
        flow.offset = 12;
        HologramProcessingFlow restored = HologramProcessingFlow.restoreBackgroundUiState(flow.backgroundUiState());
        SimpleAssert.that(
            restored != flow && restored.values() != flow.values(),
            "processing review resumes in a fresh business model without keeping the old screen");
        SimpleAssert.eq(flow.stage, restored.stage, "processing review resumes its original step");
        SimpleAssert.eq(12, restored.offset, "processing review resumes its original scroll");
        SimpleAssert.eq(flow.policy(), restored.policy(), "processing choices and confirmed prefix are preserved");
        SimpleAssert.that(
            restored.fullReview() && restored.requiredStages() == 48 && !restored.complete(),
            "reopen preserves full review and never implicitly submits or completes the business action");
        restored.values()
            .setInteger("deidentificationLevel", 9);
        SimpleAssert.eq(
            3,
            flow.values()
                .getInteger("deidentificationLevel"),
            "fresh review edits cannot modify the detached model");
    }

    private static void freshScreenBoundary() {
        final int[] freshScreens = { 0 }, outboundActions = { 0 };
        String owner = "com/miaokatze/gtmb/client/hologram/";
        try (java.io.InputStream source = HologramBackgroundTasksRegression.class.getClassLoader()
            .getResourceAsStream(owner + "HologramBackgroundTasks.class")) {
            if (source == null) throw new java.io.IOException("missing production background owner");
            new org.objectweb.asm.ClassReader(source)
                .accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {

                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                        if (!"reopen".equals(name)) return null;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {

                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                if (opcode == org.objectweb.asm.Opcodes.NEW && (owner + "HologramScreen").equals(type))
                                    freshScreens[0]++;
                                SimpleAssert.that(
                                    !type.endsWith("HologramBackgroundScreen"),
                                    "reopen allocates the business workstation without a dedicated background screen");
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String type, String method, String descriptor,
                                boolean isInterface) {
                                if ("sendAction".equals(method) || "command".equals(method)
                                    || "beginLibrary".equals(method)) outboundActions[0]++;
                            }
                        };
                    }
                }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        } catch (java.io.IOException failure) {
            throw new AssertionError("cannot inspect production reopen boundary", failure);
        }
        SimpleAssert.eq(1, freshScreens[0], "every normal reopen constructs a fresh business screen after disposal");
        SimpleAssert.eq(0, outboundActions[0], "presentation reattachment cannot repeat the original business action");
        for (java.lang.reflect.Field field : HologramBackgroundUiState.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            SimpleAssert.that(
                type == String.class || type == long.class || type == NBTTagCompound.class,
                "detached presentation contains pure values without retaining disposed UI objects: " + field.getName());
        }
    }

    private static void transportFailures() {
        List<HologramTaskOwner.Result> results = new ArrayList<>();
        HologramTaskOwner owner = new HologramTaskOwner(new HologramTaskOwner.Notifications() {

            @Override
            public void background(String label) {}

            @Override
            public void finished(String label, HologramTaskOwner.Result result, String detail) {
                results.add(result);
                SimpleAssert.that(detail.contains("准备失败"), "the owner retains the actual preparation failure reason");
            }
        });
        long token = owner.begin("server", "area-session:60", "area import");
        owner.detach();
        NBTTagCompound failure = new NBTTagCompound();
        failure.setBoolean("transportError", true);
        failure.setString("session", "area-session");
        failure.setString("errorCode", "snapshot.prepare");
        String detail = "蓝图快照准备失败；保留原预览";
        SimpleAssert
            .that(!owner.failSnapshot(failure, detail), "a session-only transport error cannot release an owner");
        failure.setLong("uiSequence", 59);
        SimpleAssert
            .that(!owner.failSnapshot(failure, detail), "an older snapshot error cannot end the current import");
        failure.setLong("uiSequence", 60);
        failure.setLong("clientTaskToken", token + 1);
        SimpleAssert.that(!owner.failSnapshot(failure, detail), "a supplied wrong owner token is rejected");
        failure.removeTag("clientTaskToken");
        SimpleAssert
            .that(owner.failSnapshot(failure, detail), "the actual failed snapshot ends its exact import owner");
        SimpleAssert
            .that(!owner.busy() && owner.detached(), "failed import unlocks while keeping its detached presentation");
        SimpleAssert
            .eq(HologramTaskOwner.Result.FAILURE, results.get(0), "snapshot failure never reports world success");
        SimpleAssert
            .that(!owner.failSnapshot(failure, detail), "duplicate transport failure emits no second terminal event");
        SimpleAssert.that(
            owner.retiredReceipt("server", "area-session:60"),
            "the client can suppress fallback chat for an already announced failed receipt");
        SimpleAssert.eq(1, results.size(), "failed snapshot announces one failure and no success sound result");
        SimpleAssert.eq(
            0L,
            owner.begin("server", "area-session:60", "stale progress"),
            "late progress cannot revive the failed snapshot owner");
        long retry = owner.begin("server", "area-session:61", "retry import");
        SimpleAssert.that(
            retry > token && !owner.failSnapshot(failure, detail),
            "a delayed failed receipt cannot terminate the retry in the same session");
        failure.setString("session", "other-world-session");
        failure.setLong("uiSequence", 61);
        SimpleAssert.that(!owner.failSnapshot(failure, detail), "cross-session failure cannot end the retry");
        failure.setString("session", "area-session");
        SimpleAssert.that(owner.failSnapshot(failure, detail), "the retry can fail only through its own exact receipt");

        HologramClientProgress.clear();
        NBTTagCompound progress = new NBTTagCompound();
        progress.setString("scope", "server");
        progress.setString("session", "area-session");
        progress.setLong("uiSequence", 62);
        progress.setLong("sequence", 1);
        progress.setString("phase", "area.blocks");
        progress.setLong("done", 300);
        progress.setLong("total", 1000);
        HologramClientProgress.receive(progress);
        HologramClientProgress.transportFailed(failure);
        SimpleAssert.that(
            HologramClientProgress.view("server", "area-session") != null,
            "an old failed snapshot does not erase newer import progress");
        failure.setLong("uiSequence", 62);
        HologramClientProgress.transportFailed(failure);
        SimpleAssert.eq(
            null,
            HologramClientProgress.view("server", "area-session"),
            "matching transport failure clears the otherwise endless in-progress observation");
        HologramClientProgress.clear();
    }

    /** Server publication regressions feed an actually assembled/decoded area snapshot through this same owner gate. */
    public static void assertAreaSnapshotCompletes(NBTTagCompound finalWire, long startRevision) {
        List<HologramTaskOwner.Result> results = new ArrayList<>();
        HologramTaskOwner owner = new HologramTaskOwner(new HologramTaskOwner.Notifications() {

            @Override
            public void background(String label) {}

            @Override
            public void finished(String label, HologramTaskOwner.Result result, String detail) {
                results.add(result);
            }
        });
        SimpleAssert.that(
            !finalWire.getString("session")
                .isEmpty() && finalWire.hasKey("uiSequence", 4),
            "actual decoded publication retains the area request identity");
        owner.begin("server", finalWire.getString("session") + ":" + finalWire.getLong("uiSequence"), "area import");
        owner.detach();
        SimpleAssert.that(
            owner.finishAreaSnapshot(finalWire, startRevision, false),
            "the actual area wire result reaches the production task completion gate");
        SimpleAssert.that(
            !owner.busy() && owner.detached(),
            "actual completed area publication unlocks its owner without reopening a deliberately closed UI");
        SimpleAssert.eq(
            HologramTaskOwner.Result.SUCCESS,
            results.get(0),
            "success requires actual areaImported and a revision newer than the pre-import workspace");
        SimpleAssert.that(
            !owner.finishAreaSnapshot(finalWire, startRevision, false),
            "retransmitted actual area publication cannot finish the same owner twice");
        SimpleAssert.eq(1, results.size(), "actual area completion emits exactly one terminal result");
    }

    private static void areaSnapshotReview() {
        List<HologramTaskOwner.Result> results = new ArrayList<>();
        HologramTaskOwner owner = new HologramTaskOwner(new HologramTaskOwner.Notifications() {

            @Override
            public void background(String label) {}

            @Override
            public void finished(String label, HologramTaskOwner.Result result, String detail) {
                results.add(result);
            }
        });
        owner.begin("server", "review-session:35", "area import");
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("session", "review-session");
        snapshot.setLong("uiSequence", 35);
        snapshot.setLong("planRevision", 1);
        snapshot.setBoolean("areaImporting", true);
        SimpleAssert.that(
            !owner.finishAreaSnapshot(snapshot, 1, false) && owner.busy(),
            "a partial imported snapshot cannot finish its active worker");
        snapshot.setBoolean("areaImporting", false);
        snapshot.setBoolean("discardConfirmationRequired", true);
        NBTTagCompound proposal = new NBTTagCompound();
        proposal.setString("session", "review-session");
        proposal.setString("op", "importArea");
        proposal.setLong("planRevision", 1);
        proposal.setString("sourceSwitchNonce", "server-issued-review");
        snapshot.setTag("discardTargetAction", proposal);
        SimpleAssert.that(
            !owner.finishAreaSnapshot(snapshot, 1, false) && owner.busy(),
            "an actual source-switch review retains its owner instead of claiming import success");
        snapshot.setBoolean("discardConfirmationRequired", false);
        snapshot.setBoolean("areaImported", true);
        SimpleAssert.that(
            owner.finishAreaSnapshot(snapshot, 1, false),
            "a terminal import result is consumed after the review gate closes");
        SimpleAssert.eq(
            HologramTaskOwner.Result.FAILURE,
            results.get(0),
            "areaImported without a newer authoritative revision cannot report success");
    }
}
