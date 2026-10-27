package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

/** Saved-preset activation owns a private candidate; the old editor remains the only published route. */
final class HologramPresetActivation {

    private static final Map<HologramSession, Task> TASKS = new IdentityHashMap<>();
    private static final int MAX_TASKS = 32;

    enum State {
        OWNER_READY,
        WAITING_TOOL,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    interface Preparation {

        default boolean current() {
            return true;
        }

        boolean step(HologramSession candidate, int maxWork, long maxNanos) throws IOException;
    }

    interface Completion {

        void completed(HologramSession candidate);

        void failed(String reason);
    }

    private HologramPresetActivation() {}

    /** Input and candidate transfer exclusively to this task. No success receipt is issued at admission. */
    static boolean begin(HologramSession origin, HologramSession candidate, HologramPresets.Preset ownedPreset,
        String selectedId, Preparation prepare, Completion completion) {
        cancel(origin, "蓝图加载已被新的请求替代");
        HologramWorkspace.PresentationTransition transition = HologramWorkspace.preparePresentation(origin, candidate);
        if (transition == null || ownedPreset == null || !candidate.virtual || TASKS.size() >= MAX_TASKS) {
            completion.failed("蓝图加载未开始：会话已变化或准备队列已满；原编辑保留");
            return false;
        }
        try {
            HologramPresetWorkflow.initializeCandidate(candidate, ownedPreset, selectedId);
            Task task = new Task(origin, candidate, selectedId, transition, prepare, completion);
            TASKS.put(origin, task);
            task.progress(false, "");
            return true;
        } catch (IOException | RuntimeException | LinkageError failure) {
            TASKS.remove(origin);
            completion.failed("蓝图加载失败：" + failure.getMessage());
            return false;
        }
    }

    static boolean pending(HologramSession origin) {
        return TASKS.containsKey(origin);
    }

    static boolean canAdvance(HologramSession origin) {
        Task task = TASKS.get(origin);
        if (task == null) return false;
        try {
            return task.current() && HologramSessionPolicy.currentTool(origin) != null;
        } catch (RuntimeException | LinkageError failure) {
            // Let the owner tick deliver one terminal failure; never throw out of the shared scheduler predicate.
            return true;
        }
    }

    static void cancel(HologramSession origin, String reason) {
        Task task = TASKS.remove(origin);
        if (task != null) task.fail(State.CANCELLED, reason);
    }

    static void tick(HologramSession origin) {
        tick(origin, Integer.MAX_VALUE, HologramWorkBudget.remainingNanos(HologramWorkBudget.SLICE_NANOS));
    }

    /** Deterministic work/time port; native projection and planning never leave their creating owner thread. */
    static void tick(HologramSession origin, int maxWork, long maxNanos) {
        Task task = TASKS.get(origin);
        if (task == null) return;
        try {
            if (!task.current()) {
                cancel(origin, "蓝图加载已取消：会话、蓝图版本、世界或提供器已变化；原编辑保留");
                return;
            }
            if (HologramSessionPolicy.currentTool(origin) == null) {
                task.state = State.WAITING_TOOL;
                task.progress(false, "等待重新手持原投影仪");
                return;
            }
            task.state = State.OWNER_READY;
            if (!task.step(maxWork, maxNanos)) {
                task.progress(false, "");
                return;
            }
            HologramSession candidate = task.candidate;
            if (!task.current() || !HologramSessionPolicy.valid(candidate)
                || !candidate.player.worldObj.blockExists(candidate.x, candidate.y, candidate.z)
                || (candidate.virtualPlacement
                    ? !HologramSessionPolicy.previewReach(candidate.player, candidate.x, candidate.y, candidate.z)
                    : candidate.player.getDistanceSq(candidate.x + .5, candidate.y + .5, candidate.z + .5) > 4096)) {
                cancel(origin, "蓝图加载已取消：最终激活环境已变化；原编辑保留");
                return;
            }
            // Remove before Workspace activation, whose lifecycle hook cancels retired-origin work.
            TASKS.remove(origin);
            if (!HologramWorkspace.activatePresentation(task.transition)) {
                task.fail(State.CANCELLED, "蓝图加载已取消：编辑工作区已变化；原编辑保留");
                return;
            }
            task.state = State.COMPLETED;
            HologramSessionPolicy.currentTool(task.candidate)
                .getTagCompound()
                .setString(HologramSessionKeys.SELECTED_PRESET, task.selectedId);
            task.candidate.player.inventory.markDirty();
        } catch (IOException | RuntimeException | LinkageError failure) {
            TASKS.remove(origin);
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                .warn("Saved preset activation failed session=" + task.session + " phase=" + task.phase(), failure);
            if (task.state == State.COMPLETED) {
                HologramWorkspace.rollbackPresentation(origin.player, task.candidate.id, task.sequence);
                task.state = State.OWNER_READY;
            }
            task.fail(State.FAILED, "蓝图加载失败；原编辑保留：" + failure.getMessage());
            return;
        }
        // A downstream callback failure cannot issue a second terminal callback or retry an activation.
        task.completion.completed(task.candidate);
    }

    static final class Task {

        final HologramSession origin, candidate;
        final HologramWorkspace.PresentationTransition transition;
        final Completion completion;
        final Preparation prepare;
        final String selectedId, session;
        final long revision, sequence, clientTask, progressClientTask, localGeneration, editorEpoch;
        final long resources = com.miaokatze.gtmb.api.ResourceContainers.generation();
        final long nativeProviders = HologramNativePlacement.generation();
        final long declarations = HologramDeclarationAdapters.generation();
        final long vouchers = DecorationVoucherConfig.revision();
        final HologramCapture capture;
        final HologramPresets.Preset preset;
        final Thread owner = Thread.currentThread();
        HologramBlueprintProcessing.PolicyTask policy;
        HologramPresetCopies.Task copy;
        HologramBlueprintProjection.Population population;
        HologramResourcePlanner.PlanTask plan;
        State state = State.OWNER_READY;
        int stage = -1, boundsCursor;
        long progressTick = Long.MIN_VALUE;
        String progressPhase = "";

        Task(HologramSession origin, HologramSession candidate, String selectedId,
            HologramWorkspace.PresentationTransition transition, Preparation prepare, Completion completion) {
            this.origin = origin;
            this.candidate = candidate;
            this.selectedId = selectedId;
            this.transition = transition;
            this.prepare = prepare;
            this.completion = completion;
            session = origin.id;
            revision = origin.planRevision;
            sequence = origin.uiSequence;
            clientTask = origin.blueprintClientTaskToken;
            progressClientTask = candidate.blueprintClientTaskToken;
            localGeneration = origin.localPlanGeneration;
            editorEpoch = origin.editorWorkspaceEpoch;
            capture = origin.capture;
            preset = origin.virtualPreset;
            policy = new HologramBlueprintProcessing.PolicyTask(candidate.virtualPreset);
            copy = new HologramPresetCopies.Task(candidate.virtualPreset);
        }

        boolean current() {
            return HologramWorkspace.find(origin.player.getUniqueID()) == origin && !origin.cancelled
                && session.equals(origin.id)
                && revision == origin.planRevision
                && sequence == origin.uiSequence
                && clientTask == origin.blueprintClientTaskToken
                && localGeneration == origin.localPlanGeneration
                && editorEpoch == origin.editorWorkspaceEpoch
                && capture == origin.capture
                && preset == origin.virtualPreset
                && origin.player.worldObj == origin.libraryWorld
                && origin.player.dimension == origin.dimension
                && !origin.player.isDead
                && HologramWorkspace.areaSessionOwned(origin)
                && HologramSessionPolicy.activationValid(origin)
                && resources == com.miaokatze.gtmb.api.ResourceContainers.generation()
                && nativeProviders == HologramNativePlacement.generation()
                && declarations == HologramDeclarationAdapters.generation()
                && vouchers == DecorationVoucherConfig.revision()
                && HologramWorkspace.presentationAllowed(origin)
                && (prepare == null || prepare.current());
        }

        boolean step(int maxWork, long maxNanos) throws IOException {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Activation must stay on its owner thread");
            if (maxWork <= 0 || maxNanos <= 0) return false;
            // One phase per slice avoids accidentally multiplying a caller's work/time allowance.
            if (stage == -1) {
                if (!policy.step(maxWork, maxNanos)) return false;
                candidate.processingPolicy = policy.result();
                policy.close();
                policy = null;
                stage = 0;
            } else if (stage == 0) {
                if (!copy.step(maxWork, maxNanos)) return false;
                candidate.processingSource = copy.result;
                if (candidate.libraryPrivatePreset == candidate.virtualPreset)
                    candidate.libraryPrivatePreset = candidate.processingSource;
                copy = null;
                population = new HologramBlueprintProjection.Population(candidate);
                stage = 1;
            } else if (stage == 1) {
                if (!population
                    .step(maxWork, maxNanos == Long.MAX_VALUE ? Long.MAX_VALUE : HologramWorkBudget.deadline(maxNanos)))
                    return false;
                if (candidate.capture == null || candidate.capture.incomplete) throw new IOException(
                    "预设投影不完整：" + (candidate.capture == null ? "missing" : candidate.capture.failure));
                population = null;
                stage = 2;
            } else if (stage == 2) {
                if (prepare != null && !prepare.step(candidate, maxWork, maxNanos)) return false;
                stage = 3;
            } else if (stage == 3) {
                HologramPlanBudget budget = new HologramPlanBudget(maxWork, maxNanos);
                while (candidate.virtualPlacement && boundsCursor < candidate.capture.cells.size() && budget.take()) {
                    HologramCapture.Cell cell = candidate.capture.cells.get(boundsCursor++);
                    if (!HologramSessionPolicy.ignoredPlacementAir(candidate, cell, cell.stableIndex)
                        && !HologramWorldFlow
                            .worldPosition(cell.x, cell.y, cell.z, candidate.player.worldObj.getHeight()))
                        throw new IOException("蓝图结构范围超出世界边界或高度");
                }
                if (candidate.virtualPlacement && boundsCursor < candidate.capture.cells.size()) return false;
                plan = HologramResourcePlanner.beginPlan(candidate);
                stage = 4;
            } else if (stage == 4) {
                if (!plan.step(maxWork, maxNanos)) return false;
                stage = 5;
            }
            return stage == 5;
        }

        String phase() {
            return state == State.WAITING_TOOL ? "preset.waitingTool"
                : stage == -1 ? "preset.policy"
                    : stage == 0 ? "preset.copy"
                        : stage == 1 ? population.phase()
                            : stage == 2 ? "preset.orientation"
                                : stage == 3 ? "preset.bounds" : stage == 4 ? plan.phase() : "preset.ready";
        }

        void fail(State terminal, String reason) {
            if (state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED) return;
            state = terminal;
            if (policy != null) policy.close();
            policy = null;
            copy = null;
            population = null;
            plan = null;
            try {
                progress(true, reason);
            } finally {
                completion.failed(reason);
            }
        }

        void progress(boolean terminal, String detail) {
            String phase = terminal ? "preset." + state.name()
                .toLowerCase(java.util.Locale.ROOT) : phase();
            long tick = origin.libraryWorld.getTotalWorldTime();
            if (!terminal && tick == progressTick && phase.equals(progressPhase)) return;
            progressTick = tick;
            progressPhase = phase;
            NBTTagCompound progress = new NBTTagCompound();
            progress.setString("scope", "server");
            progress.setString("session", session);
            progress.setLong("planRevision", revision);
            progress.setLong("uiSequence", sequence);
            progress.setLong("clientTaskToken", progressClientTask);
            progress.setString("phase", phase);
            progress.setString("outcome", terminal ? "failed" : state == State.WAITING_TOOL ? "paused" : "running");
            progress.setBoolean("complete", terminal);
            progress.setString("detail", detail);
            progress.setString("error", terminal ? detail : "");
            progress.setString("unit", "operations");
            progress.setLong(
                "done",
                policy != null ? policy.completed()
                    : copy != null ? copy.completed()
                        : population != null ? population.completed() : plan != null ? plan.completed() : boundsCursor);
            progress.setLong(
                "total",
                policy != null ? policy.total()
                    : copy != null ? copy.total()
                        : population != null ? population.total()
                            : plan != null ? plan.total()
                                : candidate.capture == null ? 0 : candidate.capture.cells.size());
            NBTTagCompound state = new NBTTagCompound();
            state.setBoolean("progressOnly", true);
            state.setTag("progress", progress);
            HologramWorkspace.snapshotSink()
                .send(origin.player, state);
        }
    }
}
