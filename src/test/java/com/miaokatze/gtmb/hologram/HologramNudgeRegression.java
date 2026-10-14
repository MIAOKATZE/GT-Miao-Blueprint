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
        rejectedOrLostDispatch();
    }

    private static void rejectedOrLostDispatch() {
        int[] forward = { 0, 0, 1 }, right = { -1, 0, 0 };
        HologramWorldFlow.NudgeDispatch dispatch = new HologramWorldFlow.NudgeDispatch();
        dispatch.sync("session", 10, 0, false);
        dispatch.queue(forward, false);
        int[] first = dispatch.poll(10, 0);
        first[2] = 99;
        dispatch.releaseRepeats();
        dispatch.queue(right, false);
        dispatch.sync("session", 10, 19, false);
        SimpleAssert.that(
            dispatch.poll(10, 19) == null,
            "a non-ACK cannot release either the in-flight press or the later queued press before timeout");
        dispatch.sync("session", 10, 20, false);
        SimpleAssert.that(
            Arrays.equals(forward, dispatch.poll(10, 20)),
            "silent server throttle rejection retries the same immutable world offset at the existing timeout");
        SimpleAssert.that(dispatch.poll(10, 20) == null, "one timeout cannot dispatch duplicate retries in one tick");
        dispatch.sync("session", 11, 21, false);
        SimpleAssert.that(dispatch.poll(11, 21) == null, "an accepted retry still respects the shared interval");
        dispatch.sync("session", 11, 24, false);
        SimpleAssert.that(
            Arrays.equals(right, dispatch.poll(11, 24)),
            "ACK clears the retried press and preserves the later distinct deliberate press");
        dispatch.sync("session", 12, 25, false);
        dispatch.sync("session", 12, 60, false);
        SimpleAssert.that(
            dispatch.poll(12, 60) == null,
            "late old-revision traffic cannot replay an acknowledged press or move again");

        dispatch = new HologramWorldFlow.NudgeDispatch();
        dispatch.sync("bounded", 5, 0, false);
        dispatch.queue(forward, false);
        dispatch.poll(5, 0);
        for (int attempt = 1; attempt <= 2; attempt++) {
            dispatch.sync("bounded", 5, attempt * 20, false);
            SimpleAssert.that(
                Arrays.equals(forward, dispatch.poll(5, attempt * 20)),
                "unchanged revision permits bounded retry " + attempt);
        }
        dispatch.sync("bounded", 5, 60, false);
        SimpleAssert.that(dispatch.poll(5, 60) == null, "after two retries the rejected press stops");
        dispatch.sync("bounded", 5, 100, false);
        SimpleAssert.that(dispatch.poll(5, 100) == null, "exhausted retries never resurrect without a new press");
        dispatch.queue(right, false);
        SimpleAssert.that(
            Arrays.equals(right, dispatch.poll(5, 100)),
            "a new explicit press remains usable after a permanently rejected request");
        dispatch.sync("bounded", 5, 120, false);
        dispatch.sync("other", 5, 120, false);
        SimpleAssert.that(dispatch.poll(5, 120) == null, "session change discards a due retry and all queued intent");

        dispatch.sync("other", 5, 121, false);
        dispatch.queue(forward, false);
        dispatch.poll(5, 121);
        dispatch.sync("other", 5, 141, false);
        dispatch.sync("other", 5, 141, true);
        SimpleAssert.that(
            dispatch.poll(5, 141) == null,
            "world/tool/screen transition discards an in-flight press even when its retry is due");
        dispatch.queue(forward, true);
        dispatch.poll(5, 145);
        dispatch.releaseRepeats();
        dispatch.sync("other", 5, 165, false);
        SimpleAssert.that(
            dispatch.poll(5, 165) == null,
            "automatic held-key repeats are never replayed after release or a silent rejection");
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
