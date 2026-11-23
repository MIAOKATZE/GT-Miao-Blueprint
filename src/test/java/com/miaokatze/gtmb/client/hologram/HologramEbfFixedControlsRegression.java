// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import gregtech.api.enums.HeatingCoilLevel;

/** Exact native coil-delta and facing expectations, without driving any input or game world. */
public final class HologramEbfFixedControlsRegression {

    private HologramEbfFixedControlsRegression() {}

    public static void run() {
        Map<String, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        Map<String, HeatingCoilLevel> copper = new LinkedHashMap<>(), kanthal = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) {
            String at = "p" + i;
            before.put(at, i < 16 ? "coil/0" : "native-fixed-" + i);
            after.put(at, i < 16 ? "coil/1" : before.get(at));
            if (i < 16) {
                copper.put(at, HeatingCoilLevel.LV);
                kanthal.put(at, HeatingCoilLevel.MV);
            }
        }
        check(HologramEbfFixedControls.exactCoilDelta(before, after, copper, kanthal), "sixteen exact native changes");
        after.put("p16", "substituted fixed hatch");
        check(!HologramEbfFixedControls.exactCoilDelta(before, after, copper, kanthal), "non-coil control changed");
        after.put("p16", before.get("p16"));
        after.put("p15", before.get("p15"));
        check(!HologramEbfFixedControls.exactCoilDelta(before, after, copper, kanthal), "unchanged member");
        after.put("p15", "coil/1");
        kanthal.remove("p15");
        kanthal.put("p17", HeatingCoilLevel.MV);
        check(!HologramEbfFixedControls.exactCoilDelta(before, after, copper, kanthal), "relocated member");
        kanthal.remove("p17");
        kanthal.put("p15", HeatingCoilLevel.HV);
        check(!HologramEbfFixedControls.exactCoilDelta(before, after, copper, kanthal), "guessed tier");
        check(HologramEbfFixedControls.nextFacing(new int[] { 0, 2, 5 }, -1) == 0, "native first declared side");
        check(HologramEbfFixedControls.nextFacing(new int[] { 0, 2, 5 }, 0) == 2, "cycle legal list");
        check(HologramEbfFixedControls.nextFacing(new int[] { 0, 2, 5 }, 5) == 0, "wrap legal list");
        check(HologramEbfFixedControls.nextFacing(new int[0], -1) == -1, "missing legality stops");
        exactPickLayout();
        hatchLedgerContract();
        exactCoilOnlyProfileAndCapture();
        exactCoilNativeStructurePane();
        System.out.println("GTMB stock EBF contracts PASS: exact 16-coordinate coil delta, lawful facing and post-dock frame fence");
    }

    private static void hatchLedgerContract() {
        check(HologramEbfHatchClickLedger.enabled(true, false, "selectPin"), "only first fixed native pick");
        check(!HologramEbfHatchClickLedger.enabled(false, false, "selectPin"), "off by default");
        check(!HologramEbfHatchClickLedger.enabled(true, true, "selectPin"), "coil-only unaffected");
        check(!HologramEbfHatchClickLedger.enabled(true, false, "reselectAfterPin"), "no later dispatch sampled");
        int[] r = { 20, 53, 416, 281 };
        check(HologramEbfHatchClickLedger.inside(r, 172, 145), "archived pointer in post-dock clip");
        check(HologramEbfHatchClickLedger.inside(r, 20, 53), "inclusive origin");
        check(!HologramEbfHatchClickLedger.inside(r, 436, 145)
            && !HologramEbfHatchClickLedger.inside(r, 172, 334), "exclusive outer edges");
        check(!HologramEbfHatchClickLedger.inside(null, 0, 0)
            && !HologramEbfHatchClickLedger.inside(new int[] { 0, 0, 0, 1 }, 0, 0), "missing or empty clip");
        int[] rightPane = { 456, 53, 184, 281 };
        check(HologramEbfHatchClickLedger.separate(r, rightPane), "owned post-dock scene and actual right pane disjoint");
        check(!HologramEbfHatchClickLedger.separate(new int[] { 20, 53, 600, 281 }, rightPane),
            "pre-dock wide scene is not an eligible first dock frame");
        check(!HologramEbfHatchClickLedger.separate(r, new int[] { 0, 31, 184, 250 }),
            "visible paper at the unpositioned overlay origin covers the scene");
        check(!HologramEbfHatchClickLedger.separate(r, null), "hidden pane is not a layout witness");
        for (String edge : Arrays.asList("press", "release")) {
            var row = new LinkedHashMap<String, Object>();
            HologramEbfHatchClickLedger.recordEdge(row, edge, true, null);
            check(Boolean.TRUE.equals(row.get(edge + "PreConsumed")) && Boolean.FALSE.equals(row.get(edge + "Dispatched"))
                && !row.containsKey(edge + "Handled"), "skipped " + edge + " has no invented handler return");
            for (boolean handled : new boolean[] { false, true }) {
                row.clear();
                HologramEbfHatchClickLedger.recordEdge(row, edge, false, handled);
                check(Boolean.FALSE.equals(row.get(edge + "PreConsumed")) && Boolean.TRUE.equals(row.get(edge + "Dispatched"))
                    && Boolean.valueOf(handled).equals(row.get(edge + "Handled")), "native " + edge + " return preserved");
            }
        }
        System.out.println("GTMB EBF hatch click ledger contracts PASS: initial pick only, exact clips and actual edge returns");
    }

    private static void exactCoilOnlyProfileAndCapture() {
        check(HologramEbfFixedControls.coilProfileAllowed(true, true, true, true, true, "ebf", false),
            "only explicit prepared-stock EBF profile");
        for (int denied = 0; denied < 5; denied++)
            check(!HologramEbfFixedControls.coilProfileAllowed(denied != 0, denied != 1, denied != 2, denied != 3,
                denied != 4, "ebf", false), "all exact owned profile requirements must be present");
        check(!HologramEbfFixedControls.coilProfileAllowed(true, true, true, true, true, "solar", false),
            "unrelated native machine");
        check(!HologramEbfFixedControls.coilProfileAllowed(true, true, true, true, true, "ebf", true),
            "exclude alternate drivers/construction");
        check(!HologramEbfFixedControls.COIL_FILE.equals("gtmb-ebf-fixed-native.json")
            && !HologramEbfFixedControls.COIL_SCHEMA.equals("gtmb-stock-ebf-native-controls-v1"),
            "coil-only result never aliases fixed controller acceptance");
        check(HologramEbfFixedControls.coilControlsMatch(Arrays.asList("structureDock", "channels", "chooseCoil", "coil2", "closeChannels")),
            "only native Channels actions, once and in order");
        check(!HologramEbfFixedControls.coilControlsMatch(Arrays.asList("structureDock", "channels", "chooseCoil", "coil2")),
            "no closed Channels witness");
        check(!HologramEbfFixedControls.coilControlsMatch(Arrays.asList("structureDock", "channels", "chooseCoil", "coil2", "coil2", "closeChannels")),
            "duplicate value action");
        check(!HologramEbfFixedControls.coilControlsMatch(Arrays.asList("structureDock", "pin61", "channels", "chooseCoil", "coil2", "closeChannels")),
            "no dependency on a hatch-control input");
        Object original = new Object(), body = new Object(), recaptured = new Object();
        check(HologramEbfFixedControls.coilAdmissionMatches(original, original, body, body, "owner", "owner",
            "first", "first", 0), "exact unchanged R0 pending receipt");
        check(!HologramEbfFixedControls.coilAdmissionMatches(original, recaptured, body, body, "owner", "owner",
            "first", "first", 0), "input did not use original state");
        check(!HologramEbfFixedControls.coilAdmissionMatches(original, original, body, body, "owner", "other",
            "first", "first", 0), "unrelated pending owner");
        check(!HologramEbfFixedControls.coilAdmissionMatches(original, original, body, new Object(), "owner", "owner",
            "first", "first", 0), "pending snapshot does not keep original body");
        check(!HologramEbfFixedControls.coilAdmissionMatches(original, original, body, body, "owner", "owner",
            "first", "second", 0), "a later generation is not an admission receipt");
        check(!HologramEbfFixedControls.coilAdmissionMatches(original, original, body, body, "owner", "owner",
            "first", "first", 1), "prior planned revision edited");
        check(HologramEbfFixedControls.coilFrameMatches(original, recaptured, recaptured, 20, 27, 28),
            "strictly later natural material frame after native channel close");
        check(!HologramEbfFixedControls.coilFrameMatches(original, recaptured, recaptured, 20, 27, 27),
            "close event not a new scene frame");
        check(!HologramEbfFixedControls.coilFrameMatches(original, recaptured, original, 20, 27, 28),
            "old body republished after close");
        check(!HologramEbfFixedControls.coilFrameMatches(original, recaptured, new Object(), 20, 27, 28),
            "unrelated frame not accepted");
        check(!HologramEbfFixedControls.coilFrameMatches(original, recaptured, recaptured, 28, 27, 28),
            "must also advance the original native frame serial");
        System.out.println("GTMB EBF coil-only contracts PASS: strict lease profile, untouched R0 admission and exact fresh frame");
    }

    private static void exactCoilNativeStructurePane() {
        int[] clip = { 466, 70, 150, 18 };
        check(HologramEbfFixedControls.coilStructurePaneEligible(true, false, true, 3, true, false, true, true, true, clip),
            "visible exact native structure pane with a newer owned frame");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(true, false, false, 3, true, false, true, true, true, clip),
            "closed structure page does not admit");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(true, false, true, 0, true, false, true, true, true, clip),
            "selection page does not expose Channels");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(false, false, true, 3, true, false, true, true, true, clip),
            "wrong workspace does not admit");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(true, true, true, 3, true, false, true, true, true, clip),
            "virtual source does not admit this physical EBF scenario");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(true, false, true, 3, false, false, true, true, true, clip),
            "collapsed Sources/Channels group does not expose its action");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(true, false, true, 3, true, true, true, true, true, clip),
            "modal intercepts the structure path");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(true, false, true, 3, true, false, false, true, true, clip),
            "unrelated viewport/owner does not admit");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(true, false, true, 3, true, false, true, false, true, clip),
            "old native layout does not admit");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(true, false, true, 3, true, false, true, true, false, clip),
            "frame drawn before the structure pane became actual");
        check(!HologramEbfFixedControls.coilStructurePaneEligible(true, false, true, 3, true, false, true, true, true, null),
            "disabled, unattached or clipped control does not admit");
        System.out.println("GTMB EBF coil structure pane contracts PASS: exact native page, owner, clip and fresh layout");
    }

    private static void exactPickLayout() {
        Object state = new Object(), scene = new Object();
        int[] bounds = { 20, 34, 384, 277 };
        check(HologramEbfFixedControls.pickFrameMatches(21, 22, state, state, scene, scene, bounds, bounds.clone()),
            "later draw owns current viewport");
        check(!HologramEbfFixedControls.pickFrameMatches(21, 21, state, state, scene, scene, bounds, bounds),
            "pre-dock draw not eligible");
        check(!HologramEbfFixedControls.pickFrameMatches(21, 22, state, new Object(), scene, scene, bounds, bounds),
            "unrelated logical revision owner");
        check(!HologramEbfFixedControls.pickFrameMatches(21, 22, state, state, scene, new Object(), bounds, bounds),
            "unrelated scene owner");
        check(!HologramEbfFixedControls.pickFrameMatches(21, 22, state, state, scene, scene, bounds,
            new int[] { 21, 34, 384, 277 }), "same state but old viewport origin");
        check(!HologramEbfFixedControls.pickFrameMatches(21, 22, state, state, scene, scene, bounds,
            new int[] { 20, 34, 500, 277 }), "same state but old viewport width");
        check(!HologramEbfFixedControls.pickFrameMatches(21, 22, state, state, scene, scene, null, null),
            "no mounted clip");
        int[] empty = { 20, 34, 0, 0 };
        check(!HologramEbfFixedControls.pickFrameMatches(21, 22, state, state, scene, scene, empty, empty),
            "empty clip");
        check(HologramEbfFixedControls.canSelectTarget(-1, new int[0], 8), "first exact pick");
        check(HologramEbfFixedControls.canSelectTarget(8, new int[] { 8 }, 8), "legitimately retained exact singleton");
        check(!HologramEbfFixedControls.canSelectTarget(8, new int[] { 8, 9 }, 8), "no group pick");
        check(!HologramEbfFixedControls.canSelectTarget(8, new int[0], 8), "stale scalar not a singleton");
        check(!HologramEbfFixedControls.canSelectTarget(9, new int[] { 9 }, 8), "do not replace an unrelated selection");
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}
