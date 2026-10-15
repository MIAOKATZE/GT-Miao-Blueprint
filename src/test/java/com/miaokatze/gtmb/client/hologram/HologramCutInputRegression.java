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

    private static void check(HologramCutInput.Intent expected, HologramCutInput.Stage stage, boolean queued,
        boolean shift, boolean alt, boolean blueprintBusy) {
        SimpleAssert.eq(
            expected,
            HologramCutInput.right(stage, queued, shift, alt, blueprintBusy),
            "cut interaction priority: " + stage + "/" + queued + "/" + shift + "/" + alt + "/" + blueprintBusy);
    }
}
