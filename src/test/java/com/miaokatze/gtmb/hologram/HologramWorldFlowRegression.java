package com.miaokatze.gtmb.hologram;

import com.miaokatze.gtmb.hologram.HologramWorldFlow.Gesture;
import com.miaokatze.gtmb.hologram.HologramWorldFlow.Intent;
import com.miaokatze.gtmb.hologram.HologramWorldFlow.Stage;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Gesture sequences must preserve the explicit UI construction confirmation boundary. */
public final class HologramWorldFlowRegression {

    private HologramWorldFlowRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        HologramAreaRegression.run();
        airPlacement();
        Stage workspace = HologramWorldFlow.stage(false, false, false, false, 0);
        SimpleAssert.eq(Intent.OPEN, HologramWorldFlow.intent(workspace, Gesture.RIGHT), "empty tool opens UI");
        SimpleAssert.eq(
            Intent.QUICK_PLACE,
            HologramWorldFlow.intent(workspace, Gesture.ALT_RIGHT),
            "Alt right requests the server's last saved blueprint without opening UI");
        SimpleAssert.eq(
            Intent.BEGIN_AREA,
            HologramWorldFlow.intent(workspace, Gesture.SHIFT_RIGHT),
            "one modifier starts area");
        Stage area = HologramWorldFlow.stage(true, true, true, false, 0);
        SimpleAssert.eq(
            Intent.NONE,
            HologramWorldFlow.intent(area, Gesture.RIGHT),
            "right corner never confirms ghost underneath area");
        SimpleAssert.eq(
            Intent.FINISH_AREA,
            HologramWorldFlow.intent(area, Gesture.SHIFT_RIGHT),
            "second Shift right completes area without overwriting its second corner");
        SimpleAssert.eq(Intent.NONE, HologramWorldFlow.intent(area, Gesture.ENTER), "Enter remains the chat key");
        SimpleAssert.eq(Intent.NONE, HologramWorldFlow.intent(area, Gesture.ALT_RIGHT), "Alt right remains a corner");
        SimpleAssert.eq(Intent.CANCEL_AREA, HologramWorldFlow.intent(area, Gesture.ESCAPE), "Escape cancels selection");
        Stage moving = HologramWorldFlow.stage(false, true, true, false, 0);
        SimpleAssert.eq(
            Intent.CONFIRM_POSITION,
            HologramWorldFlow.intent(moving, Gesture.RIGHT),
            "ordinary right confirms only position");
        SimpleAssert.eq(
            Intent.NONE,
            HologramWorldFlow.intent(moving, Gesture.ENTER),
            "Enter cannot confirm placement or construction");
        SimpleAssert.eq(
            Intent.CANCEL_PLACEMENT,
            HologramWorldFlow.intent(moving, Gesture.ESCAPE),
            "Escape retains draft through placement cancel");
        SimpleAssert.eq(
            Intent.NONE,
            HologramWorldFlow.intent(moving, Gesture.SHIFT_RIGHT),
            "moving placement never starts a conflicting area selection");
        SimpleAssert.eq(
            Intent.CONFIRM_POSITION,
            HologramWorldFlow.intent(moving, Gesture.ALT_RIGHT),
            "Alt right locks the forced floating target of the current blueprint");
        Stage locked = HologramWorldFlow.stage(false, true, true, true, 0);
        SimpleAssert.eq(
            Intent.OPEN,
            HologramWorldFlow.intent(locked, Gesture.RIGHT),
            "repeat right opens material UI, never executes");
        SimpleAssert.eq(Intent.NONE, HologramWorldFlow.intent(locked, Gesture.ROTATE), "locked plan cannot rotate");
        SimpleAssert.eq(
            Intent.UNLOCK_POSITION,
            HologramWorldFlow.intent(locked, Gesture.SHIFT_RIGHT),
            "Shift right unlocks without opening UI or starting area selection");
        SimpleAssert.eq(
            Intent.CANCEL_PLACEMENT,
            HologramWorldFlow.intent(locked, Gesture.ESCAPE),
            "Escape also exits a locked preview");
        for (int job : new int[] { 1, 2 }) {
            Stage active = HologramWorldFlow.stage(true, true, true, false, job);
            for (Gesture gesture : new Gesture[] { Gesture.ESCAPE, Gesture.ENTER, Gesture.ROTATE, Gesture.SHIFT_RIGHT,
                Gesture.ALT_RIGHT })
                SimpleAssert.eq(
                    Intent.NONE,
                    HologramWorldFlow.intent(active, gesture),
                    "world gesture never changes active construction");
            SimpleAssert
                .eq(Intent.OPEN, HologramWorldFlow.intent(active, Gesture.RIGHT), "job status remains reachable");
        }
        SimpleAssert.eq(
            Stage.WORKSPACE,
            HologramWorldFlow.stage(false, true, true, false, 4),
            "cancelled job never becomes a moving ghost");
        for (int job : new int[] { 3, 5 }) {
            for (boolean lockedPosition : new boolean[] { false, true }) {
                Stage completed = HologramWorldFlow.stage(false, true, true, lockedPosition, job);
                SimpleAssert.eq(Stage.WORKSPACE, completed, "a finished placement returns to the workspace");
                SimpleAssert.eq(
                    Intent.QUICK_PLACE,
                    HologramWorldFlow.intent(completed, Gesture.ALT_RIGHT),
                    "Alt right can preview the saved blueprint again after full or partial completion");
                SimpleAssert.eq(
                    Intent.OPEN,
                    HologramWorldFlow.intent(completed, Gesture.RIGHT),
                    "ordinary right opens completed job results without another world-position lock");
            }
        }
        HologramWorldFlow.KeyEdge key = new HologramWorldFlow.KeyEdge();
        SimpleAssert.that(!key.sample(true, true), "held key on entering projector context is suppressed");
        SimpleAssert.that(!key.sample(true, false), "held key remains suppressed");
        SimpleAssert.that(!key.sample(false, false), "release performs no action");
        SimpleAssert.that(key.sample(true, false), "fresh press performs one action");
        SimpleAssert.that(!key.sample(true, false), "tick repetition never repeats action");
        SimpleAssert.that(!key.sample(true, true), "changing screen with held key suppresses it");
        SimpleAssert.that(!key.sample(true, true), "returning to world with held key suppresses it");
        key.sample(false, false);
        SimpleAssert.that(key.sample(true, false), "fresh world press rearms key");
    }

    private static void airPlacement() {
        anchor(
            new int[] { 5, 65, 3 },
            HologramWorldFlow.airAnchor(-.25, 65.62, 3.8, 1, 0, 0, 6),
            "air anchor starts from camera eyes");
        anchor(
            new int[] { -1, 65, -3 },
            HologramWorldFlow.airAnchor(-.25, 65.62, 3.8, 0, 0, -4, 6),
            "non-unit ray normalizes and negative coordinates floor");
        anchor(
            new int[] { 0, 71, 0 },
            HologramWorldFlow.airAnchor(0, 65.62, 0, 0, 1, 0, 6),
            "vertical camera ray supports elevated suspended placement");
        anchor(
            new int[] { 0, 59, 0 },
            HologramWorldFlow.airAnchor(0, 65.62, 0, 0, -1, 0, 6),
            "downward ray preserves selected distance");
        anchor(
            new int[] { 6, 64, 8 },
            HologramWorldFlow.airAnchor(0, 64, 0, 3, 0, 4, 10),
            "oblique ray uses distance rather than one-axis offsets");
        anchor(
            new int[] { 0, 64, 0 },
            HologramWorldFlow.airAnchor(0, 64, 0, 1, 0, 0, 0),
            "zero distance preserves the supplied foot anchor");
        anchor(
            new int[] { 64, 64, 0 },
            HologramWorldFlow.airAnchor(0, 64, 0, 1, 0, 0, Integer.MAX_VALUE),
            "maximum local distance remains bounded");
        int[] aboveWorld = HologramWorldFlow.airAnchor(0, 250, 0, 0, 1, 0, 64);
        SimpleAssert.eq(314, aboveWorld[1], "preview does not silently clamp an invalid target back into the world");
        SimpleAssert.that(
            !HologramWorldFlow.worldPosition(aboveWorld[0], aboveWorld[1], aboveWorld[2], 256),
            "server position policy rejects height overflow");
        SimpleAssert
            .that(HologramWorldFlow.worldPosition(-30000000, 0, -30000000, 256), "minimum world boundary is valid");
        SimpleAssert
            .that(!HologramWorldFlow.worldPosition(30000000, 255, 0, 256), "upper horizontal boundary is exclusive");
        SimpleAssert.that(!HologramWorldFlow.worldPosition(0, -1, 0, 256), "below-world target is rejected");
        SimpleAssert.that(
            HologramWorldFlow.withinPreviewReach(0, 65.62, 0, 0, 129, 0),
            "maximum upward ray remains valid from eyes including voxel center rounding");
        SimpleAssert.that(
            !HologramWorldFlow.withinPreviewReach(0, 65.62, 0, 66, 65, 0),
            "client cannot extend the server preview reach");
        HologramWorldFlow.PreviewDistance distance = new HologramWorldFlow.PreviewDistance();
        Object firstWorld = new Object();
        distance.scroll(120);
        SimpleAssert.eq(6, distance.distance(), "inactive wheel cannot change the next preview");
        distance.sync(true, firstWorld, "first-preview");
        distance.scroll(240);
        SimpleAssert.eq(8, distance.distance(), "real wheel units adjust two distance steps");
        distance.sync(true, firstWorld, "first-preview");
        SimpleAssert.eq(8, distance.distance(), "same preview snapshots preserve local distance");
        distance.scroll(Integer.MAX_VALUE);
        SimpleAssert.eq(64, distance.distance(), "wheel overflow clamps at maximum distance");
        distance.scroll(Integer.MIN_VALUE);
        SimpleAssert.eq(0, distance.distance(), "negative wheel overflow reaches the foot anchor without wrapping");
        distance.sync(true, firstWorld, "second-preview");
        SimpleAssert.eq(6, distance.distance(), "new preview session resets wheel distance");
        distance.scroll(120);
        distance.sync(true, new Object(), "second-preview");
        SimpleAssert.eq(6, distance.distance(), "world switch resets wheel distance");
        distance.scroll(120);
        distance.sync(false, firstWorld, "second-preview");
        distance.scroll(120);
        distance.sync(true, firstWorld, "second-preview");
        SimpleAssert.eq(6, distance.distance(), "cancel and out-of-mode wheel never stick to the next preview");
        Object projector = new Object(), initialStack = new Object();
        distance.syncHeldTool(projector, 0, 2, "tool-a", initialStack);
        distance.sync(true, firstWorld, "network-copy-preview");
        distance.scroll(240);
        distance.syncHeldTool(projector, 0, 2, "tool-a", new Object());
        distance.sync(true, firstWorld, "network-copy-preview");
        SimpleAssert.eq(
            8,
            distance.distance(),
            "same logical tool survives a vanilla synchronized stack copy without losing wheel input");
        distance.syncHeldTool(projector, 0, 2, "tool-b", new Object());
        distance.sync(true, firstWorld, "network-copy-preview");
        SimpleAssert.eq(6, distance.distance(), "a different projector UUID resets wheel distance");
        distance.scroll(240);
        distance.syncHeldTool(projector, 0, 3, "tool-b", new Object());
        distance.sync(true, firstWorld, "network-copy-preview");
        SimpleAssert.eq(6, distance.distance(), "changing actual held slot resets even a copied logical UUID");
        distance.scroll(240);
        distance.syncHeldTool(projector, 1, 3, "tool-b", new Object());
        distance.sync(true, firstWorld, "network-copy-preview");
        SimpleAssert.eq(6, distance.distance(), "changing tool metadata resets preview distance");
        distance.scroll(240);
        distance.syncHeldTool(new Object(), 1, 3, "tool-b", new Object());
        distance.sync(true, firstWorld, "network-copy-preview");
        SimpleAssert
            .eq(6, distance.distance(), "a different actual item cannot inherit a copied tool UUID's preview distance");
        Object unidentified = new Object();
        distance.syncHeldTool(projector, 1, 3, "", unidentified);
        distance.sync(true, firstWorld, "unidentified-preview");
        distance.scroll(240);
        distance.syncHeldTool(projector, 1, 3, "", unidentified);
        distance.sync(true, firstWorld, "unidentified-preview");
        SimpleAssert.eq(8, distance.distance(), "unidentified tool retains distance only for the same actual stack");
        distance.syncHeldTool(projector, 1, 3, "", new Object());
        distance.sync(true, firstWorld, "unidentified-preview");
        SimpleAssert.eq(6, distance.distance(), "unidentified replacement cannot inherit wheel input");
    }

    private static void anchor(int[] expected, int[] actual, String label) {
        SimpleAssert.that(java.util.Arrays.equals(expected, actual), label);
    }
}
