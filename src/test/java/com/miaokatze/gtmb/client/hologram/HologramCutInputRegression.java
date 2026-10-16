package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.api.CutTransforms.Transform;
import com.miaokatze.gtmb.hologram.HologramWorldFlow;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramCutInputRegression {

    private HologramCutInputRegression() {}

    public static void run() {
        recoveryIsolation();
        HologramCutInput.Stage idle = HologramCutInput.Stage.IDLE;
        check(HologramCutInput.Intent.SWITCHER, idle, false, true, false, false);
        check(HologramCutInput.Intent.NONE, idle, false, false, true, false);
        check(HologramCutInput.Intent.STORAGE, idle, true, false, false, false);
        check(HologramCutInput.Intent.PREVIEW, idle, true, true, false, false);
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
            check(HologramCutInput.Intent.MARK, HologramCutInput.Stage.SELECTING, true, shift, alt, false);
            check(HologramCutInput.Intent.LOCK, HologramCutInput.Stage.MOVING, true, shift, alt, false);
            check(HologramCutInput.Intent.CONFIRM, HologramCutInput.Stage.LOCKED, true, shift, alt, false);
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
                    HologramCutInput.Intent.SWITCHER,
                    HologramCutInput.right(stage, true, true, false, false, requests.writing()),
                    "an unresolved " + operation + " allows local mode selection even from " + stage);
            }
            HologramCutInput.Switcher mode = new HologramCutInput.Switcher();
            SimpleAssert.that(mode.release(false) && !mode.cut(), "Shift release chooses ordinary blueprint");
            SimpleAssert.eq("pending", requests.unresolvedWrite(), "choosing blueprint keeps the original write id");
            SimpleAssert.that(requests.writing() && requests.busy(), "choosing blueprint cannot unlock cut writes");
            requests.acknowledge("verify");
            SimpleAssert.that(requests.writing(), "a directory ACK alone does not resolve a world write");
            requests.reconcile("pending");
            SimpleAssert.eq(
                HologramCutInput.Intent.PREVIEW,
                HologramCutInput.right(idle, true, true, false, false, requests.writing()),
                "confirmed recovery restores the usual queued Shift-right placement gesture");
            SimpleAssert.eq(
                HologramCutInput.Intent.SELECT,
                HologramCutInput.right(idle, true, true, true, false, requests.writing()),
                "confirmed recovery preserves Shift-Alt batch cutting");
        }
        for (HologramCutInput.Intent intent : new HologramCutInput.Intent[] { HologramCutInput.Intent.SELECT,
            HologramCutInput.Intent.PREVIEW, HologramCutInput.Intent.UNDO, HologramCutInput.Intent.MARK,
            HologramCutInput.Intent.CONFIRM })
            SimpleAssert.that(HologramCutInput.requiresCutReady(intent), "cut readiness is required for " + intent);
        SimpleAssert.that(
            !HologramCutInput.requiresCutReady(HologramCutInput.Intent.SWITCHER),
            "the local switcher never requires a successful cut-directory sync");
    }

    private static void checkRecoveryRouting(boolean queued, String situation) {
        HologramCutInput.Stage idle = HologramCutInput.Stage.IDLE;
        SimpleAssert.eq(
            HologramCutInput.Intent.SWITCHER,
            HologramCutInput.right(idle, queued, true, false, false, true),
            situation + ": Shift-right opens the local mode selector immediately");
        SimpleAssert.eq(
            queued,
            HologramCutInput.interceptsUse(idle, queued, false, false),
            situation + ": ordinary blueprint use has no dependency on a missing cut directory or write ACK");
        for (boolean shift : new boolean[] { false, true }) {
            SimpleAssert.that(
                !HologramCutInput.interceptsUse(idle, queued, shift, true),
                situation + ": active blueprint input keeps priority, including Shift-right finish");
            SimpleAssert.eq(
                HologramCutInput.Intent.NONE,
                HologramCutInput.right(idle, queued, shift, false, true, true),
                situation + ": cut recovery cannot redirect an active blueprint gesture");
        }
    }

    private static void check(HologramCutInput.Intent expected, HologramCutInput.Stage stage, boolean queued,
        boolean shift, boolean alt, boolean blueprintBusy) {
        SimpleAssert.eq(
            expected,
            HologramCutInput.right(stage, queued, shift, alt, blueprintBusy),
            "cut interaction priority: " + stage + "/" + queued + "/" + shift + "/" + alt + "/" + blueprintBusy);
    }
}
