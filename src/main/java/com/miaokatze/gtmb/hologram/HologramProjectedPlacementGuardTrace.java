// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Temporary log-only diagnosis. Explicit opt-in records at most one guard rejection per JVM. */
final class HologramProjectedPlacementGuardTrace {

    static final String PROPERTY = "gtmb.projectedPlacementGuardTrace";
    private static final String CONTEXT = "施工工具、位置或会话已变化，请重新确认";
    private static final String BUSY = "请先处理当前施工、采集或未保存的服务器草稿";
    private static final OneShot TRACE = new OneShot(optedIn());

    private HologramProjectedPlacementGuardTrace() {}

    enum Phase {
        RECEIVE,
        WAIT,
        PUBLISH
    }

    enum Reason {
        TIMEOUT("施工准备超时，请缩小施工选区后重试"),
        CONNECTION_CLOSED(CONTEXT),
        WORLD_CHANGED(CONTEXT),
        SLOT_CHANGED(CONTEXT),
        STACK_REPLACED(CONTEXT),
        NOT_PROJECTOR(CONTEXT),
        EDIT_DISABLED(CONTEXT),
        WORKSPACE_CHANGED(CONTEXT),
        ANCHOR_BOUNDS(CONTEXT),
        ANCHOR_REACH(CONTEXT),
        ANCHOR_NOT_LOADED(CONTEXT),
        ACTIVE_DEMOLITION(BUSY),
        ACTIVE_AE_ORDER(BUSY),
        AREA_BUSY(BUSY),
        PREVIOUS_REVISION_CHANGED(BUSY),
        PREVIOUS_EPOCH_CHANGED(BUSY),
        PREVIOUS_JOB_BUSY(BUSY),
        PREVIOUS_CAPTURE_PENDING(BUSY),
        PREVIOUS_UNSAVED(BUSY);

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    static IOException failure(Phase phase, Reason reason, Consumer<String> log) {
        return TRACE.failure(phase, reason, log);
    }

    private static boolean optedIn() {
        try {
            return Boolean.getBoolean(PROPERTY);
        } catch (SecurityException denied) {
            return false;
        }
    }

    static final class OneShot {

        private final AtomicBoolean armed;

        OneShot(boolean enabled) {
            armed = new AtomicBoolean(enabled);
        }

        IOException failure(Phase phase, Reason reason, Consumer<String> log) {
            if (armed.compareAndSet(true, false)) {
                try {
                    log.accept("GTMB projected placement guard rejected: stage=" + phase + " reason=" + reason);
                } catch (RuntimeException | LinkageError unavailable) {
                    // A diagnostic sink must never change the original refusal.
                }
            }
            return new IOException(reason.message);
        }
    }
}
