// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;

/** Detached timing sidecar for the already-existing four exact native cross-history inputs. */
final class HologramGiantHistoryTiming {

    static final String FLAG = "gtmb.testlab.giantNativeHistoryTiming";
    static final String FILE = "gtmb-giant-native-history-delta-timing.json";
    private final List<Map<String, Object>> requests = new ArrayList<>(4);
    private Map<String, Object> current;
    private HologramLocalHistoryTrace trace;
    private HologramGiantWriteFrames frames = new HologramGiantWriteFrames();
    private HologramState preState, postState;
    private long preSerial, postSerial, postAt, press;
    private boolean preObserved;
    private HologramUiLibrary library;
    private int expectedRows, verifiedFull, verifiedCertified;

    static HologramGiantHistoryTiming optional(boolean requested, boolean crossHistory) {
        return requested && crossHistory ? new HologramGiantHistoryTiming() : null;
    }

    void prime(String action) {
        current = new LinkedHashMap<>();
        current.put("action", action);
        current.put("heapGcBeforePress", HologramGiantNativeEditProbe.heapGc());
        requests.add(current);
    }

    void press(HologramScreen screen, HologramLocalBlueprintDocument draft, String operation,
        int rows, long sequence, long pressed) {
        press = pressed;
        expectedRows = rows;
        library = screen.ui.library;
        trace = library.watchLocalHistory(operation, screen.ui.session.state(), draft, sequence, press);
        frames.begin();
    }

    void beforeDraw(HologramScreen screen) {
        if (trace == null) return;
        preObserved = true;
        preState = screen.ui.session.state();
        var prior = HologramRenderer.editorMaterialPresentation();
        preSerial = prior == null ? 0 : prior.serial();
        postState = null;
    }

    void afterDraw(HologramScreen screen) {
        postState = null;
        if (trace == null || !preObserved) return;
        preObserved = false;
        HologramState state = screen.ui.session.state();
        if (state != preState || state.data.getLong("planRevision") != trace.sourceRevision + 1
            || state.data.getLong("uiSequence") != trace.sequence) return;
        var material = HologramRenderer.editorMaterialPresentation();
        if (material == null || material.serial() <= preSerial || material.state() != state || !material.bodyMaterial()
            || !material.complete() || material.submittedCells() != expectedRows) return;
        postState = state;
        postSerial = material.serial();
        postAt = System.nanoTime();
    }

    void frame(long end) {
        frames.frameEnd(end);
    }

    void record(HologramState accepted, long serial, long end) {
        boolean exactNaturalFrame = actualFrame(accepted, postState, serial, postSerial, press, postAt, end);
        current.put("ownedDrawPostAndRenderEndConfirmed", exactNaturalFrame);
        current.put("pressToFullEndNanos", end - press);
        current.put("acceptedRevision", accepted.data.getLong("planRevision"));
        current.put("acceptedUiSequence", accepted.data.getLong("uiSequence"));
        current.put("materialSerial", serial);
        if (exactNaturalFrame) current.put("pressToMaterialDrawPostNanos", postAt - press);
        if (trace != null) {
            current.put("taskTiming", trace.snapshot());
            boolean header = "undoHeader".equals(current.get("action")) || "redoHeader".equals(current.get("action"));
            if (exactNaturalFrame && trace.completeCertified(expectedRows, header)) verifiedCertified++;
            if (exactNaturalFrame && trace.complete(expectedRows)) verifiedFull++;
        } else current.put("taskTiming", "NO_EXACT_ADMISSION");
        current.put("heapGcAfterFullEnd", HologramGiantNativeEditProbe.heapGc());
        var gaps = frames.receipt();
        gaps.put("kind", "owned RenderTick.END intervals; first interval may straddle the press");
        current.put("frameGaps", gaps);
        retire();
    }

    static boolean actualFrame(Object accepted, Object post, long serial, long drawnSerial, long press, long postAt,
        long end) {
        return accepted != null && accepted == post && serial > 0 && serial == drawnSerial && press <= postAt
            && postAt <= end;
    }

    void incomplete() {
        if (current == null) return;
        if (trace != null) {
            library.stopWatchingLocalHistory(trace);
            HologramLocalHistoryTrace.end(trace, HologramLocalHistoryTrace.Terminal.OBSERVATION_ABORTED);
            current.put("taskTiming", trace.snapshot());
        } else current.put("taskTiming", "NO_EXACT_ADMISSION");
        current.put("ownedDrawPostAndRenderEndConfirmed", false);
        retire();
    }

    private void retire() {
        if (library != null && trace != null) library.stopWatchingLocalHistory(trace);
        library = null;
        trace = null;
        preState = postState = null;
        preObserved = false;
        press = preSerial = postSerial = postAt = 0;
        current = null;
        frames = new HologramGiantWriteFrames();
    }

    Map<String, Object> receipt(boolean functional) {
        incomplete();
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", "gtmb.nativeHistoryDeltaTiming.v1");
        result.put("outcome", functional && verifiedCertified == 4 && requests.size() == 4 ? "PASS" : "INCOMPLETE");
        result.put("canonicalHistoryAndSaveAccepted", functional);
        result.put("rows", expectedRows);
        result.put("verifiedCertifiedRequests", verifiedCertified);
        result.put("verifiedFullRebuildRequests", verifiedFull);
        result.put("requests", requests);
        result.put("workCap", 512);
        result.put("budgetNanos", 2_000_000L);
        result.put("budgetStopOrder", "complete, then work cap, then time; reaching cap does not test the time predicate");
        result.put("unit", "cell rows plus region seals, material rows and membership lists");
        result.put("memorySample", "JVM heap and cumulative GC before dispatch and after each exact full-frame END; not peaks");
        return result;
    }
}
