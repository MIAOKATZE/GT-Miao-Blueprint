package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.api.CutTransforms.Transform;
import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramCutInputRegression {

    private HologramCutInputRegression() {}

    public static void run() {
        localBlueprintOwnsSelection();
        recoveryIsolation();
        selectedModeRouting();
        continuousAndNudge();
        HologramCutInput.Stage idle = HologramCutInput.Stage.IDLE;
        check(HologramCutInput.Intent.SWITCHER, idle, false, true, false, false);
        check(HologramCutInput.Intent.NONE, idle, false, false, true, false);
        check(HologramCutInput.Intent.STORAGE, idle, true, false, false, false);
        check(HologramCutInput.Intent.STORAGE, idle, true, true, false, false);
        check(HologramCutInput.Intent.UNDO, idle, true, false, true, false);
        check(HologramCutInput.Intent.SELECT, idle, true, true, true, false);
        NBTTagCompound blueprint = new NBTTagCompound();
        for (int job : new int[] { 0, 3, 4, 5 }) {
            blueprint.setInteger("job", job);
            SimpleAssert.that(
                !HologramCutInput.blueprintBusy(blueprint),
                "idle or terminal blueprint job permits cut tools: " + job);
            check(
                HologramCutInput.Intent.SWITCHER,
                idle,
                false,
                true,
                false,
                HologramCutInput.blueprintBusy(blueprint));
            check(HologramCutInput.Intent.STORAGE, idle, true, false, false, HologramCutInput.blueprintBusy(blueprint));
        }
        for (int job : new int[] { 1, 2 }) {
            blueprint.setInteger("job", job);
            SimpleAssert.that(
                HologramCutInput.blueprintBusy(blueprint),
                "running or paused blueprint work retains input priority: " + job);
            check(HologramCutInput.Intent.NONE, idle, true, true, false, HologramCutInput.blueprintBusy(blueprint));
        }
        blueprint.setInteger("job", 0);
        blueprint.setBoolean("virtual", true);
        blueprint.setBoolean("virtualPlacement", true);
        SimpleAssert
            .that(HologramCutInput.blueprintBusy(blueprint), "moving blueprint preview retains its world input");
        blueprint.setBoolean("virtualLocked", true);
        SimpleAssert
            .that(HologramCutInput.blueprintBusy(blueprint), "locked blueprint preview retains its review input");
        for (int job : new int[] { 3, 4, 5 }) {
            blueprint.setInteger("job", job);
            SimpleAssert.that(
                !HologramCutInput.blueprintBusy(blueprint),
                "a terminal job's residual placement flags are not an active preview: " + job);
        }
        for (String guard : new String[] { "areaSelecting", "areaImporting", "capturePending" }) {
            blueprint.setBoolean(guard, true);
            SimpleAssert.that(
                HologramCutInput.blueprintBusy(blueprint),
                guard + " retains blueprint priority even after a terminal job");
            blueprint.setBoolean(guard, false);
        }
        SimpleAssert.eq(
            HologramWorldFlow.Intent.FINISH_AREA,
            HologramWorldFlow.intent(HologramWorldFlow.Stage.AREA, HologramWorldFlow.Gesture.SHIFT_RIGHT),
            "existing blueprint area still consumes Shift-right as finish instead of opening the switcher");
        SimpleAssert.eq("add", HologramClient.areaSelectionMode(true, false), "blueprint Ctrl area-add is retained");
        SimpleAssert.eq(
            "subtract",
            HologramClient.areaSelectionMode(true, true),
            "blueprint Ctrl-Shift area-subtract is retained");
        for (boolean shift : new boolean[] { false, true }) for (boolean alt : new boolean[] { false, true }) {
            check(HologramCutInput.Intent.NONE, idle, true, shift, alt, true);
            check(
                shift && alt ? HologramCutInput.Intent.MORE
                    : shift ? HologramCutInput.Intent.CONFIRM_SELECTION : HologramCutInput.Intent.MARK,
                HologramCutInput.Stage.SELECTING,
                true,
                shift,
                alt,
                false);
            check(
                shift && alt ? HologramCutInput.Intent.SELECT
                    : alt ? HologramCutInput.Intent.UNDO
                        : shift ? HologramCutInput.Intent.STORAGE : HologramCutInput.Intent.LOCK,
                HologramCutInput.Stage.MOVING,
                true,
                shift,
                alt,
                false);
            check(
                shift && alt ? HologramCutInput.Intent.SELECT
                    : alt ? HologramCutInput.Intent.UNDO
                        : shift ? HologramCutInput.Intent.STORAGE : HologramCutInput.Intent.CONFIRM,
                HologramCutInput.Stage.LOCKED,
                true,
                shift,
                alt,
                false);
        }
        HologramCutInput.Switcher choice = new HologramCutInput.Switcher();
        SimpleAssert.that(!choice.cut(), "switcher initially selects blueprint");
        SimpleAssert.that(!choice.release(true), "holding Shift never starts an operation");
        choice.cycle();
        SimpleAssert.that(choice.cut(), "one extra right press selects cut");
        choice.cycle();
        SimpleAssert.that(!choice.cut(), "second extra press cycles back to blueprint");
        choice.cycle();
        SimpleAssert.that(choice.release(false), "first Shift release commits chosen mode");
        SimpleAssert.that(!choice.release(false), "later ticks cannot execute a second operation");
        choice.cycle();
        SimpleAssert.that(choice.cut(), "finished switcher cannot change committed mode");
        HologramCutInput.Switcher cancelled = new HologramCutInput.Switcher();
        cancelled.cycle();
        cancelled.cancel();
        SimpleAssert.that(!cancelled.release(false), "Escape never executes a cut on later Shift release");

        for (int mirror = 0; mirror < 8; mirror++) for (int face = 0; face < 6; face++) {
            Transform pose = HologramCutClient.facingPose(face, mirror);
            SimpleAssert.eq(
                face,
                pose.direction(ForgeDirection.NORTH.ordinal()),
                "facing preset reaches the chosen direction with mirror " + mirror + " and face " + face);
            SimpleAssert.eq(mirror, pose.mirror, "facing preset preserves all source-local mirror flags");
        }

        NBTTagCompound source = new NBTTagCompound(), raw = new NBTTagCompound();
        raw.setInteger("x", -2);
        raw.setInteger("y", 3);
        raw.setInteger("z", 8);
        raw.setString("block", "minecraft:stone");
        raw.setInteger("meta", 2);
        raw.setString("secretInventory", "complete owner-private payload");
        raw.setTag("targetDisplay", new NBTTagCompound());
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("facing", 4);
        display.setString("inventory", "private");
        raw.setTag("display", display);
        NBTTagList list = new NBTTagList();
        list.appendTag(raw);
        source.setTag("cells", list);
        source.setString("session", "ordinary-blueprint");
        NBTTagCompound preview = HologramCutClient.detachedPreview(source);
        SimpleAssert.that(source.equals(preview), "raw cut preview keeps every owner-private field");
        preview.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .setString("secretInventory", "changed detached copy");
        SimpleAssert.eq(
            "complete owner-private payload",
            raw.getString("secretInventory"),
            "raw renderer input is detached from authenticated payload");
    }

    private static void localBlueprintOwnsSelection() {
        HologramCutClient cut = HologramCutClient.INSTANCE;
        HologramState previous = HologramClient.state;
        String[] names = { "localArea", "localAreaAwaitingBegin", "localAreaFinishing", "localAreaCancelling" };
        Field[] fields = new Field[names.length];
        Object[] saved = new Object[names.length];
        int captured = 0;
        try {
            for (int i = 0; i < names.length; i++) {
                fields[i] = HologramClient.class.getDeclaredField(names[i]);
                fields[i].setAccessible(true);
                saved[i] = fields[i].get(null);
                captured++;
                fields[i].set(null, i == 0 ? null : Boolean.FALSE);
            }
            HologramClient.state = new HologramState(new NBTTagCompound());
            Method busy = HologramCutClient.class.getDeclaredMethod("blueprintBusy");
            busy.setAccessible(true);
            SimpleAssert.that(!(Boolean) busy.invoke(cut), "initial workspace permits choosing cut mode");

            HologramAreaSelection area = new HologramAreaSelection();
            area.point(1, 0, 64, 0, "replace");
            area.point(2, 8, 72, 8, "replace");
            area.beginNext("subtract");
            fields[0].set(null, area);
            SimpleAssert.that(
                (Boolean) busy.invoke(cut),
                "local subtract selection owns input before its server snapshot arrives");
            area.point(1, 2, 66, 2, "replace");
            area.point(2, 4, 68, 4, "replace");
            NBTTagCompound geometry = area.snapshot();
            for (HologramCutInput.Stage stage : HologramCutInput.Stage.values())
                for (boolean shift : new boolean[] { false, true }) for (boolean alt : new boolean[] { false, true })
                    for (boolean control : new boolean[] { false, true }) {
                        boolean owned = (Boolean) busy.invoke(cut);
                        SimpleAssert.eq(
                            HologramCutInput.Intent.NONE,
                            HologramCutInput.right(stage, shift, alt, owned, true, control),
                            "even a residual cut cannot steal any subtract-selection modifier combination");
                        SimpleAssert.that(
                            !HologramCutInput.interceptsUse(stage, shift, owned, true),
                            "blueprint mouse events reach their owner even when a stale cut stage exists");
                    }
            cut.beginSelection();
            cut.chooseCutMode();
            SimpleAssert.that(!cut.active() && !cut.cutMode(), "blocked cut entry never leaves an armed cut mode");
            SimpleAssert.eq(geometry, area.snapshot(), "attempted cut leaves both inclusion and subtraction intact");
            Method worldStage = HologramClient.class.getDeclaredMethod("worldStage");
            worldStage.setAccessible(true);
            SimpleAssert.eq(
                HologramWorldFlow.Intent.FINISH_AREA,
                HologramWorldFlow
                    .intent((HologramWorldFlow.Stage) worldStage.invoke(null), HologramWorldFlow.Gesture.SHIFT_RIGHT),
                "Shift-right still finishes blueprint import after the rejected Ctrl-Shift-Alt-right gesture");

            fields[0].set(null, null);
            for (int i = 1; i < names.length; i++) {
                fields[i].setBoolean(null, true);
                SimpleAssert.that(
                    (Boolean) busy.invoke(cut),
                    names[i] + " fences cut entry until the server receipt completes");
                fields[i].setBoolean(null, false);
            }
            SimpleAssert.that(!(Boolean) busy.invoke(cut), "completed area lifecycle restores initial workspace input");
            SimpleAssert.eq(
                HologramCutInput.Intent.SELECT,
                HologramCutInput.right(HologramCutInput.Stage.IDLE, true, true, false, false, true),
                "Ctrl-Shift-Alt-right remains available from the initial workspace");
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("Local blueprint and cut input ownership", failure);
        } finally {
            HologramClient.state = previous;
            try {
                for (int i = 0; i < captured; i++) fields[i].set(null, saved[i]);
            } catch (IllegalAccessException failure) {
                throw new AssertionError("Restore local selection fixture", failure);
            }
        }
    }

    private static void recoveryIsolation() {
        HologramCutInput.Stage idle = HologramCutInput.Stage.IDLE;
        HologramCutRequests requests = new HologramCutRequests();
        requests.observeHeld(true);
        requests.begin("directory", "list", "", 0);
        SimpleAssert.that(!requests.writing(), "initial directory sync is not a cut write");
        checkRecoveryRouting(false, "initial directory has not loaded");
        requests.expire(HologramCutRequests.READ_TIMEOUT_NS);
        checkRecoveryRouting(false, "directory sync timed out");
        for (int tick = 0; tick < 100; tick++)
            SimpleAssert.that(!requests.observeHeld(true), "a failed sync does not poll while the tool stays held");
        SimpleAssert.that(!requests.active("list"), "opening a local switcher cannot restart a directory request");

        // The failed ACK removes the network request, but its directory failure must still gate cut actions.
        requests.begin("failed", "paste", "cached-entry", HologramCutRequests.READ_TIMEOUT_NS);
        SimpleAssert.that(requests.writing(), "in-flight paste retains the world-write safety gate");
        requests.acknowledge("failed");
        SimpleAssert.that(!requests.writing(), "a failure ACK need not leave an unresolved request id");
        checkRecoveryRouting(true, "failed write ACK with cached entries and no unresolved id");

        for (String operation : new String[] { "cut", "paste", "undo" }) {
            requests.clear();
            requests.begin("pending", operation, "cached-entry", 0);
            checkRecoveryRouting(true, "in-flight " + operation);
            requests.expire(HologramCutRequests.WRITE_TIMEOUT_NS);
            requests.begin("verify", "list", requests.unresolvedWrite(), HologramCutRequests.WRITE_TIMEOUT_NS);
            for (HologramCutInput.Stage stage : HologramCutInput.Stage.values()) {
                SimpleAssert.eq(
                    stage == HologramCutInput.Stage.SELECTING ? HologramCutInput.Intent.MARK
                        : HologramCutInput.Intent.STORAGE,
                    HologramCutInput.right(stage, true, false, false, true, true),
                    "an unresolved " + operation + " allows Ctrl-Shift storage browsing even from " + stage);
            }
            HologramCutInput.Switcher mode = new HologramCutInput.Switcher();
            SimpleAssert.that(mode.release(false) && !mode.cut(), "Shift release chooses ordinary blueprint");
            SimpleAssert.eq("pending", requests.unresolvedWrite(), "choosing blueprint keeps the original write id");
            SimpleAssert.that(requests.writing() && requests.busy(), "choosing blueprint cannot unlock cut writes");
            requests.acknowledge("verify");
            SimpleAssert.that(requests.writing(), "a directory ACK alone does not resolve a world write");
            requests.reconcile("pending");
            SimpleAssert.eq(
                HologramCutInput.Intent.STORAGE,
                HologramCutInput.right(idle, true, false, false, true, false),
                "confirmed recovery restores Shift-right storage browsing");
            SimpleAssert.eq(
                HologramCutInput.Intent.SELECT,
                HologramCutInput.right(idle, true, true, false, true, false),
                "confirmed recovery preserves optional Shift-Alt selection reset");
        }
        for (HologramCutInput.Intent intent : new HologramCutInput.Intent[] { HologramCutInput.Intent.SELECT,
            HologramCutInput.Intent.PREVIEW, HologramCutInput.Intent.UNDO, HologramCutInput.Intent.MARK,
            HologramCutInput.Intent.CONFIRM })
            SimpleAssert.that(HologramCutInput.requiresCutReady(intent), "cut readiness is required for " + intent);
        SimpleAssert.that(
            !HologramCutInput.requiresCutReady(HologramCutInput.Intent.SWITCHER),
            "the local switcher never requires a successful cut-directory sync");
    }

    private static void selectedModeRouting() {
        HologramCutInput.Stage idle = HologramCutInput.Stage.IDLE;
        SimpleAssert.eq(
            HologramCutInput.Intent.STORAGE,
            HologramCutInput.right(idle, true, false, false, true, false),
            "remembered CUT mode routes Shift-right to storage, including an empty cache");
        SimpleAssert.eq(
            HologramCutInput.Intent.STORAGE,
            HologramCutInput.right(idle, false, false, false, true, false),
            "CUT mode keeps the list directly reachable while idle, even without cached entries");
        SimpleAssert.eq(
            HologramCutInput.Intent.NONE,
            HologramCutInput.right(idle, false, false, false, false, false),
            "choosing blueprint keeps its ordinary right click independent of stored cuts");
        SimpleAssert.that(
            !HologramCutInput.interceptsUse(idle, false, false, false),
            "a private cut directory alone cannot intercept ordinary blueprint use");
        SimpleAssert.that(
            HologramCutInput.interceptsUse(idle, false, false, true),
            "an idle CUT mode still intercepts the storage-list gesture");
        for (boolean cut : new boolean[] { false, true }) {
            SimpleAssert.eq(
                HologramCutInput.Intent.SELECT,
                HologramCutInput.right(idle, true, true, false, cut, false),
                "Shift-Alt-right starts another selection without a placement preview");
            SimpleAssert.eq(
                HologramCutInput.Intent.STORAGE,
                HologramCutInput.right(idle, true, false, false, cut, true),
                "Ctrl-Shift-right directly opens stored cuts in either mode");
        }
        HologramCutInput.Switcher remembered = new HologramCutInput.Switcher(true);
        SimpleAssert.that(remembered.cut(), "reopened mode selector starts on the selected CUT mode");
        remembered.cycle();
        SimpleAssert
            .that(remembered.release(false) && !remembered.cut(), "one additional right press returns to blueprint");
        for (HologramCutInput.Stage stage : HologramCutInput.Stage.values()) {
            SimpleAssert.eq(
                stage == HologramCutInput.Stage.SELECTING ? HologramCutInput.Intent.MORE
                    : HologramCutInput.Intent.SELECT,
                HologramCutInput.right(stage, true, true, false, true, false),
                "Shift-Alt-right starts a fresh local selection before mark/lock/confirm from " + stage);
            SimpleAssert.eq(
                stage == HologramCutInput.Stage.SELECTING ? HologramCutInput.Intent.MARK : HologramCutInput.Intent.UNDO,
                HologramCutInput.right(stage, false, true, false, true, false),
                "Alt-right marks corner 2 during selection and retains undo outside selection: " + stage);
            SimpleAssert.eq(
                stage != idle,
                HologramCutInput.cancelsActivity(stage, false, false),
                "local cancellation clears selection/moving/locked activity independently of the persistent queue");
            SimpleAssert.that(
                HologramCutInput.exitsMode(stage, true, false, false),
                "Escape exits CUT from every stage, including idle without an active session: " + stage);
            SimpleAssert.eq(
                stage != idle,
                HologramCutInput.exitsMode(stage, false, false, false),
                "residual activity is still escapable without the selected mode flag: " + stage);
        }
        SimpleAssert.that(
            HologramCutInput.cancelsActivity(idle, true, false),
            "Escape exits the local cut session after cut completion without forcing a paste");
        SimpleAssert
            .that(HologramCutInput.cancelsActivity(idle, false, true), "Escape clears residual local raw preview");
        SimpleAssert.that(
            !HologramCutInput.exitsMode(idle, false, false, false),
            "after cancelling once another Escape can open the normal pause menu");
        SimpleAssert.eq(
            HologramCutInput.Intent.NONE,
            HologramCutInput.right(idle, false, false, false, false, false),
            "after Escape ordinary right use is available regardless of retained cut cache");
        SimpleAssert.eq(
            HologramCutInput.Intent.SWITCHER,
            HologramCutInput.right(idle, true, false, false, false, false),
            "after Escape Shift-right follows the default blueprint mode selector");
    }

    private static void continuousAndNudge() {
        HologramCutContinuous flow = new HologramCutContinuous();
        HologramCutRequests requests = new HologramCutRequests();
        flow.enter();
        for (int i = 0; i < 3; i++) {
            flow.submitted("continuous-" + i);
            SimpleAssert.that(flow.completed("continuous-" + i, true), "each saved cut continues empty selection");
            SimpleAssert.that(!flow.completed("continuous-" + i, true), "duplicate ACK cannot restart selection");
        }
        flow.submitted("late");
        requests.begin("late", "cut", "", 0);
        flow.exit();
        SimpleAssert.that(requests.writing(), "Escape only records exit intent; server transaction remains owned");
        SimpleAssert.that(!flow.completed(requests.acknowledge("late").id, true), "late success cannot undo Escape");
        SimpleAssert.that(!requests.writing(), "authoritative ACK still releases the transaction after Escape");
        flow.enter();
        flow.submitted("old-generation");
        flow.exit();
        flow.enter();
        SimpleAssert.that(!flow.completed("old-generation", true), "a new mode session cannot inherit an old cut");
        flow.submitted("failed");
        SimpleAssert.that(!flow.completed("failed", false), "failed write never reports another completed cut");
        flow.submitted("reconciled");
        requests.begin("reconciled", "cut", "", 0);
        requests.expire(HologramCutRequests.WRITE_TIMEOUT_NS);
        SimpleAssert.that(
            flow.completed(requests.reconcile("reconciled").id, true),
            "authoritative reconciliation resumes the same continuous selection");

        for (HologramCutInput.Stage stage : HologramCutInput.Stage.values()) {
            SimpleAssert.eq(
                stage == HologramCutInput.Stage.SELECTING ? HologramCutInput.Intent.CONFIRM_SELECTION
                    : HologramCutInput.Intent.STORAGE,
                HologramCutInput.right(stage, true, false, false, true, false),
                "Shift-right confirms an editable cut selection and opens storage outside selection: " + stage);
        }
        for (boolean cutMode : new boolean[] { false, true }) {
            SimpleAssert.eq(
                HologramCutInput.Intent.MARK,
                HologramCutInput.right(HologramCutInput.Stage.SELECTING, false, true, false, cutMode, false),
                "holding Alt to adjust selection distance never replaces the second corner with undo");
            SimpleAssert.eq(
                HologramCutInput.Intent.MORE,
                HologramCutInput.right(HologramCutInput.Stage.SELECTING, true, true, false, cutMode, false),
                "explicit Shift-Alt selection reset remains available");
        }
        for (HologramCutInput.Intent intent : HologramCutInput.Intent.values()) {
            SimpleAssert.eq(
                intent == HologramCutInput.Intent.NONE || intent == HologramCutInput.Intent.STORAGE
                    || intent == HologramCutInput.Intent.SWITCHER,
                HologramCutInput.allowsWorldAction(intent, true),
                "background task owns world operations while allowing local UI: " + intent);
        }
        for (int yaw : new int[] { 0, 90, 180, 270 })
            for (boolean alt : new boolean[] { false, true }) for (int index = 0; index < 4; index++) {
                HologramCutNudge nudge = new HologramCutNudge();
                int[] anchor = { 8, 80, 16 };
                boolean[] keys = new boolean[4];
                nudge.sample(anchor, true, 1, keys, false, yaw, 0, 256);
                keys[index] = true;
                int[] expected = HologramWorldFlow.nudgeOffset(yaw, HologramCutInput.nudgeDirection(index, alt));
                int[] moved = nudge.sample(anchor, true, 1, keys, alt, yaw, 1, 256);
                for (int axis = 0; axis < 3; axis++) SimpleAssert
                    .eq(anchor[axis] + expected[axis], moved[axis], "local nudge shares blueprint direction");
                SimpleAssert.eq(80, anchor[1], "nudge detaches the anchor rather than mutating confirmation snapshots");
                SimpleAssert.that(
                    nudge.sample(moved, true, 1, keys, alt, yaw, 8, 256) == moved,
                    "held nudge waits the normal repeat delay");
                int[] repeated = nudge.sample(moved, true, 1, keys, alt, yaw, 9, 256);
                SimpleAssert.that(repeated != moved, "held nudge repeats after eight ticks");
                SimpleAssert.that(
                    nudge.sample(repeated, false, 1, keys, alt, yaw, 10, 256) == repeated,
                    "background or open screen suppresses local movement");
                SimpleAssert.that(
                    nudge.sample(repeated, true, 1, keys, alt, yaw, 30, 256) == repeated,
                    "returning with a held key cannot leak a nudge");
            }
        SimpleAssert
            .eq(HologramWorldFlow.Nudge.UP, HologramCutInput.nudgeDirection(0, true), "Alt-Up moves vertically");
        SimpleAssert
            .eq(HologramWorldFlow.Nudge.DOWN, HologramCutInput.nudgeDirection(1, true), "Alt-Down moves vertically");
        try {
            HologramCutInput.nudgeDirection(4, true);
            throw new AssertionError("removed Alt-W/S slots must not resolve to a movement direction");
        } catch (IllegalArgumentException expected) {}
    }

    private static void checkRecoveryRouting(boolean queued, String situation) {
        HologramCutInput.Stage idle = HologramCutInput.Stage.IDLE;
        SimpleAssert.eq(
            HologramCutInput.Intent.STORAGE,
            HologramCutInput.right(idle, true, false, false, queued, true),
            situation + ": Ctrl-Shift-right opens stored cuts immediately");
        SimpleAssert.eq(
            false,
            HologramCutInput.interceptsUse(idle, false, false, false),
            situation + ": ordinary blueprint use has no dependency on a missing cut directory or write ACK");
        for (boolean shift : new boolean[] { false, true }) {
            SimpleAssert.that(
                !HologramCutInput.interceptsUse(idle, shift, true, queued),
                situation + ": active blueprint input keeps priority, including Shift-right finish");
            SimpleAssert.eq(
                HologramCutInput.Intent.NONE,
                HologramCutInput.right(idle, shift, false, true, queued, true),
                situation + ": cut recovery cannot redirect an active blueprint gesture");
        }
    }

    private static void check(HologramCutInput.Intent expected, HologramCutInput.Stage stage, boolean queued,
        boolean shift, boolean alt, boolean blueprintBusy) {
        SimpleAssert.eq(
            expected,
            HologramCutInput.right(stage, shift, alt, blueprintBusy, queued, false),
            "cut interaction priority: " + stage + "/" + queued + "/" + shift + "/" + alt + "/" + blueprintBusy);
    }
}
