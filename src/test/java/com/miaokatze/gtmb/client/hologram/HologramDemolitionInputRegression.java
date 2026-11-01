package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramDemolitionInputRegression {

    private HologramDemolitionInputRegression() {}

    public static void run() {
        HologramCutInput.Switcher choice = new HologramCutInput.Switcher();
        for (HologramCutInput.Mode expected : HologramCutInput.Mode.values()) {
            SimpleAssert.eq(expected, choice.mode(), "selector cycles through all three tool modes");
            SimpleAssert.that(!choice.release(true), "holding Shift keeps selection local");
            choice.cycle();
        }
        SimpleAssert.eq(HologramCutInput.Mode.BLUEPRINT, choice.mode(), "selector wraps to blueprint");
        choice.cycle();
        choice.cycle();
        SimpleAssert.that(choice.release(false), "releasing Shift commits demolition once");
        choice.cycle();
        SimpleAssert.eq(HologramCutInput.Mode.DEMOLITION, choice.mode(), "committed mode cannot change");
        SimpleAssert.that(!choice.release(false), "a later tick cannot submit another selection");
        HologramCutInput.Switcher cancelled = new HologramCutInput.Switcher(HologramCutInput.Mode.DEMOLITION);
        cancelled.cancel();
        SimpleAssert.that(!cancelled.release(false), "cancelled switcher never enters demolition");

        for (boolean shift : new boolean[] { false, true })
            for (boolean alt : new boolean[] { false, true }) for (boolean control : new boolean[] { false, true }) {
                SimpleAssert.eq(
                    HologramCutInput.right(HologramCutInput.Stage.SELECTING, shift, alt, false, false, control),
                    HologramCutInput.right(
                        HologramCutInput.Stage.SELECTING,
                        shift,
                        alt,
                        false,
                        HologramCutInput.Mode.DEMOLITION,
                        control),
                    "demolition keeps the shared editable selection gestures");
                SimpleAssert.eq(
                    HologramCutInput.Intent.NONE,
                    HologramCutInput.right(
                        HologramCutInput.Stage.SELECTING,
                        shift,
                        alt,
                        true,
                        HologramCutInput.Mode.DEMOLITION,
                        control),
                    "an existing blueprint operation keeps world input priority");
            }
        SimpleAssert.eq(
            HologramCutInput.Intent.SELECT,
            HologramCutInput
                .right(HologramCutInput.Stage.IDLE, false, true, false, HologramCutInput.Mode.DEMOLITION, false),
            "Alt in demolition never undoes a stored cut");
        directoryIndependence();
        unknownOutcome();
    }

    private static void directoryIndependence() {
        try {
            Constructor<HologramCutClient> constructor = HologramCutClient.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            HologramCutClient local = constructor.newInstance();
            Field demolition = HologramCutClient.class.getDeclaredField("demolitionMode");
            Field unavailable = HologramCutClient.class.getDeclaredField("directoryUnavailable");
            demolition.setAccessible(true);
            unavailable.setAccessible(true);
            unavailable.setBoolean(local, true);
            SimpleAssert.that(local.busy(), "cut operations retain the unavailable-directory gate");
            demolition.setBoolean(local, true);
            SimpleAssert.that(!local.busy(), "demolition does not require a loaded or available cut directory");
            SimpleAssert
                .eq(HologramCutInput.Mode.DEMOLITION, local.mode(), "controller exposes demolition to the switcher");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("demolition directory isolation", failure);
        }
    }

    private static void unknownOutcome() {
        HologramCutRequests requests = new HologramCutRequests();
        HologramCutContinuous continuous = new HologramCutContinuous();
        continuous.enter();
        continuous.submitted("demolition");
        requests.begin("demolition", "demolish", "", 0);
        requests.begin("directory", "list", "", 0);
        SimpleAssert.that(requests.writing(), "submitted demolition owns its world-write outcome");
        requests.expire(HologramCutRequests.WRITE_TIMEOUT_NS);
        SimpleAssert.eq("demolition", requests.unresolvedDemolition(), "timeout requests demolition status directly");
        requests.acknowledge("directory");
        SimpleAssert.that(requests.writing(), "a cut-directory reply cannot resolve demolition");
        continuous.exit();
        requests.exitActivity();
        requests.begin("status", "demolitionStatus", "demolition", HologramCutRequests.WRITE_TIMEOUT_NS);
        requests.acknowledge("status");
        HologramCutRequests.Request resolved = requests.reconcile("demolition");
        SimpleAssert
            .that(resolved != null && !requests.currentActivity(resolved), "Escape retains only the pending outcome");
        SimpleAssert
            .that(!continuous.completed(resolved.id, true), "a delayed demolition ACK cannot restart after Escape");
        SimpleAssert.that(!requests.writing(), "matching status releases the original request without replay");
        SimpleAssert.that(requests.acknowledge("demolition") == null, "duplicate ACK has no local effect");
    }
}
