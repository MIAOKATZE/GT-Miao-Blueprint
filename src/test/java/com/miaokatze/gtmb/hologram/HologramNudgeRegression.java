package com.miaokatze.gtmb.hologram;

import java.util.Arrays;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Camera-relative arrows remain one voxel per step and held input repeats within the stated bounds. */
public final class HologramNudgeRegression {

    private HologramNudgeRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        int[][] forwards = { { 0, 0, 1 }, { -1, 0, 0 }, { 0, 0, -1 }, { 1, 0, 0 } };
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            int[] forward = forwards[quadrant];
            for (int revolution : new int[] { -2, -1, 0, 1, 2 }) {
                double yaw = quadrant * 90 + revolution * 360;
                offset(forward, yaw, HologramWorldFlow.Nudge.FORWARD);
                offset(new int[] { -forward[0], 0, -forward[2] }, yaw, HologramWorldFlow.Nudge.BACKWARD);
                offset(new int[] { forward[2], 0, -forward[0] }, yaw, HologramWorldFlow.Nudge.LEFT);
                offset(new int[] { -forward[2], 0, forward[0] }, yaw, HologramWorldFlow.Nudge.RIGHT);
                offset(new int[] { 0, 1, 0 }, yaw, HologramWorldFlow.Nudge.UP);
                offset(new int[] { 0, -1, 0 }, yaw, HologramWorldFlow.Nudge.DOWN);
            }
        }
        offset(new int[] { 0, 0, 1 }, 44.99, HologramWorldFlow.Nudge.FORWARD);
        offset(new int[] { -1, 0, 0 }, 45, HologramWorldFlow.Nudge.FORWARD);
        SimpleAssert.that(!HologramWorldFlow.unitNudge(1, 1, 0), "diagonal packets cannot move two voxels");
        SimpleAssert.that(!HologramWorldFlow.unitNudge(0, 0, 0), "empty packets are not a position change");
        SimpleAssert
            .that(!HologramWorldFlow.unitNudge(Integer.MIN_VALUE, 0, 0), "integer overflow cannot forge a unit");
        HologramWorldFlow.KeyRepeat repeat = new HologramWorldFlow.KeyRepeat();
        SimpleAssert.that(repeat.sample(true, true, false, 0), "initial arrow press immediately moves one voxel");
        for (int tick = 1; tick < 8; tick++)
            SimpleAssert.that(!repeat.sample(true, true, false, tick), "initial repeat delay prevents extra steps");
        SimpleAssert.that(repeat.sample(true, true, false, 8), "held arrow repeats after eight ticks");
        SimpleAssert.that(repeat.repeated(), "repeat events can be distinguished from deliberate new presses");
        for (int tick = 9; tick < 12; tick++)
            SimpleAssert.that(!repeat.sample(true, true, false, tick), "repeat interval is four ticks");
        SimpleAssert.that(repeat.sample(true, true, false, 12), "held arrow repeats at the bounded interval");
        SimpleAssert.that(!repeat.sample(true, false, true, 13), "UI input never nudges the world");
        SimpleAssert.that(!repeat.sample(true, true, true, 14), "closing UI with an arrow held requires release");
        repeat.sample(false, true, false, 15);
        SimpleAssert.that(repeat.sample(true, true, false, 16), "a deliberate new press rearms after UI exit");
        SimpleAssert.that(!repeat.repeated(), "a new press can be queued even when released before dispatch");
        queuedDispatch();
    }

    private static void queuedDispatch() {
        HologramWorldFlow.NudgeDispatch dispatch = new HologramWorldFlow.NudgeDispatch();
        int[] forward = { 0, 0, 1 }, right = { -1, 0, 0 };
        dispatch.sync("session", 10, 0, false);
        dispatch.queue(forward, false);
        SimpleAssert.that(Arrays.equals(forward, dispatch.poll(10, 0)), "first arrow dispatches immediately");
        dispatch.sync("session", 10, 1, false);
        dispatch.queue(right, false);
        dispatch.releaseRepeats();
        SimpleAssert.that(dispatch.poll(10, 1) == null, "a same-revision background snapshot cannot ACK the request");
        dispatch.sync("session", 11, 2, false);
        SimpleAssert.that(dispatch.poll(11, 2) == null, "different arrows share the same four-tick interval");
        dispatch.sync("session", 11, 4, false);
        SimpleAssert.that(
            Arrays.equals(right, dispatch.poll(11, 4)),
            "a released short press dispatches after ACK and interval");
        dispatch.sync("session", 12, 5, false);
        dispatch.queue(forward, true);
        dispatch.releaseRepeats();
        SimpleAssert.that(dispatch.poll(12, 8) == null, "releasing a held arrow drops its queued automatic repeat");
        dispatch.queue(right, false);
        dispatch.queue(forward, true);
        dispatch.releaseRepeats();
        SimpleAssert.that(
            Arrays.equals(right, dispatch.poll(12, 8)),
            "automatic repeats cannot overwrite a queued deliberate step");
        dispatch.queue(forward, false);
        dispatch.sync("session", 13, 9, true);
        SimpleAssert
            .that(dispatch.poll(13, 12) == null, "screen/tool/world transitions clear queued and pending input");
        dispatch.queue(forward, false);
        dispatch.sync("another-session", 99, 13, false);
        SimpleAssert
            .that(dispatch.poll(99, 20) == null, "a different session clears old input instead of acknowledging it");
    }

    private static void offset(int[] expected, double yaw, HologramWorldFlow.Nudge direction) {
        int[] actual = HologramWorldFlow.nudgeOffset(yaw, direction);
        SimpleAssert.that(Arrays.equals(expected, actual), "camera yaw " + yaw + " arrow " + direction);
        SimpleAssert.that(HologramWorldFlow.unitNudge(actual[0], actual[1], actual[2]), "every arrow is one voxel");
    }
}
