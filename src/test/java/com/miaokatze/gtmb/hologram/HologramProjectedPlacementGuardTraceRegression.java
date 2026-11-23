// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.miaokatze.gtmb.hologram.HologramProjectedPlacementGuardTrace.OneShot;
import com.miaokatze.gtmb.hologram.HologramProjectedPlacementGuardTrace.Phase;
import com.miaokatze.gtmb.hologram.HologramProjectedPlacementGuardTrace.Reason;
import com.miaokatze.gtmb.testutil.SimpleAssert;

final class HologramProjectedPlacementGuardTraceRegression {

    private HologramProjectedPlacementGuardTraceRegression() {}

    public static void main(String[] args) {
        run();
        if (args.length == 1) verifyJvmSetting("on".equals(args[0]));
        System.out.println("Projected placement guard trace regression passed.");
    }

    static void run() {
        for (Phase phase : Phase.values()) for (Reason reason : Reason.values()) {
            List<String> log = new ArrayList<>();
            OneShot off = new OneShot(false);
            String message = oldMessage(reason);
            SimpleAssert.eq(message, off.failure(phase, reason, log::add).getMessage(), "unchanged disabled reply");
            SimpleAssert.that(log.isEmpty(), "a disabled trace never calls its sink");

            OneShot on = new OneShot(true);
            SimpleAssert.eq(message, on.failure(phase, reason, log::add).getMessage(), "unchanged enabled reply");
            SimpleAssert.eq(Collections.singletonList(line(phase, reason)), log, "only phase and reason are exposed");
            on.failure(Phase.PUBLISH, Reason.TIMEOUT, log::add);
            SimpleAssert.eq(1, log.size(), "a later rejection cannot emit another diagnostic");
        }

        List<String> log = new ArrayList<>();
        OneShot brokenSink = new OneShot(true);
        SimpleAssert.eq(
            oldMessage(Reason.ANCHOR_REACH),
            brokenSink.failure(Phase.WAIT, Reason.ANCHOR_REACH, line -> {
                throw new IllegalStateException("test sink unavailable");
            }).getMessage(),
            "sink failure must not alter the IOException reply");
        brokenSink.failure(Phase.RECEIVE, Reason.SLOT_CHANGED, log::add);
        SimpleAssert.that(log.isEmpty(), "a failed sink attempt cannot rearm the trace");
    }

    private static void verifyJvmSetting(boolean on) {
        List<String> log = new ArrayList<>();
        HologramProjectedPlacementGuardTrace.failure(Phase.WAIT, Reason.ANCHOR_NOT_LOADED, log::add);
        HologramProjectedPlacementGuardTrace.failure(Phase.PUBLISH, Reason.WORKSPACE_CHANGED, log::add);
        SimpleAssert.eq(on ? Collections.singletonList(line(Phase.WAIT, Reason.ANCHOR_NOT_LOADED))
            : Collections.emptyList(), log, "startup JVM trace setting honors default off and optional one shot");
    }

    private static String line(Phase phase, Reason reason) {
        return "GTMB projected placement guard rejected: stage=" + phase + " reason=" + reason;
    }

    private static String oldMessage(Reason reason) {
        switch (reason) {
            case TIMEOUT:
                return "施工准备超时，请缩小施工选区后重试";
            case ACTIVE_DEMOLITION:
            case ACTIVE_AE_ORDER:
            case AREA_BUSY:
            case PREVIOUS_REVISION_CHANGED:
            case PREVIOUS_EPOCH_CHANGED:
            case PREVIOUS_JOB_BUSY:
            case PREVIOUS_CAPTURE_PENDING:
            case PREVIOUS_UNSAVED:
                return "请先处理当前施工、采集或未保存的服务器草稿";
            default:
                return "施工工具、位置或会话已变化，请重新确认";
        }
    }
}
