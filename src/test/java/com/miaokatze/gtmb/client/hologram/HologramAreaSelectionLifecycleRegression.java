package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramAreaSelection;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Full import/save baselines retire only their already-submitted local selection. */
public final class HologramAreaSelectionLifecycleRegression {

    private HologramAreaSelectionLifecycleRegression() {}

    public static void run() {
        Map<Field, Object> saved = new LinkedHashMap<>();
        HologramState previous = HologramClient.state;
        try {
            for (String name : new String[] { "localArea", "localAreaView", "localAreaSession", "localAreaTool",
                "localAreaDimension", "localAreaRevision", "localAreaCommandSequence", "localAreaAwaitingBegin",
                "localAreaFinishing", "localAreaCancelling" }) {
                Field field = field(name);
                saved.put(field, field.get(null));
            }
            HologramClient.state = new HologramState(new NBTTagCompound());
            submitted();
            SimpleAssert
                .that(HologramClient.blueprintInputBusy(), "submitted first area retains input until acceptance");
            for (String key : new String[] { "session", "toolId", "dimension", "planRevision", "uiSequence" }) {
                NBTTagCompound unrelated = complete();
                if ("session".equals(key) || "toolId".equals(key)) unrelated.setString(key, "another");
                else if ("dimension".equals(key)) unrelated.setInteger(key, 1);
                else unrelated.setLong(key, 6);
                HologramClient.retireSubmittedLocalArea(unrelated);
                SimpleAssert.that(HologramClient.blueprintInputBusy(), key + " cannot release another submission");
            }
            for (String flag : new String[] { "workspaceUiOnly", "areaSelectionOnly", "partial", "capturePending",
                "areaSelecting" }) {
                NBTTagCompound incomplete = complete();
                incomplete.setBoolean(flag, true);
                HologramClient.retireSubmittedLocalArea(incomplete);
                SimpleAssert
                    .that(HologramClient.blueprintInputBusy(), flag + " cannot masquerade as completed geometry");
            }
            NBTTagCompound missing = complete();
            missing.removeTag("areaSelecting");
            HologramClient.retireSubmittedLocalArea(missing);
            SimpleAssert.that(HologramClient.blueprintInputBusy(), "omitted area facts cannot release local selection");

            field("localAreaCancelling").setBoolean(null, true);
            HologramClient.retireSubmittedLocalArea(complete());
            SimpleAssert.that(HologramClient.blueprintInputBusy(), "a pending cancellation retains its separate owner");
            field("localAreaCancelling").setBoolean(null, false);

            // Reproduce the missing compact-registration path with the authoritative complete import baseline.
            HologramClient.retireSubmittedLocalArea(complete());
            SimpleAssert
                .that(!HologramClient.blueprintInputBusy(), "complete first import retires its local busy gate");
            SimpleAssert.eq(
                HologramCutInput.Intent.SWITCHER,
                HologramCutInput
                    .right(HologramCutInput.Stage.IDLE, true, false, HologramClient.blueprintInputBusy(), false, false),
                "next real Shift-right can enter the mode selector in the same connection");
            SimpleAssert.eq(
                HologramCutInput.Intent.NONE,
                HologramCutInput.right(
                    HologramCutInput.Stage.IDLE,
                    false,
                    false,
                    HologramClient.blueprintInputBusy(),
                    false,
                    false),
                "an ordinary right click does not enter the selector");
            SimpleAssert
                .that(field("localAreaView").get(null) == null, "submitted overlay is retired with its geometry");

            submitted();
            field("localAreaAwaitingBegin").setBoolean(null, true);
            Object nextSelection = field("localArea").get(null);
            HologramClient.retireSubmittedLocalArea(complete());
            SimpleAssert.that(
                field("localArea").get(null) == nextSelection,
                "late first-save baseline preserves a newly begun second selection");
            field("localAreaAwaitingBegin").setBoolean(null, false);
            field("localAreaCommandSequence").setLong(null, 30);
            HologramClient.retireSubmittedLocalArea(complete());
            SimpleAssert.that(
                field("localArea").get(null) == nextSelection,
                "old completion cannot retire a newer submission in the same session");

            submitted();
            NBTTagCompound transitioned = complete();
            transitioned.setString("session", "blueprint-editor");
            transitioned.setString("editorWorkspacePreviousSession", "first-area");
            HologramClient.retireSubmittedLocalArea(transitioned);
            SimpleAssert.that(
                HologramClient.blueprintInputBusy(),
                "a previous-session label alone cannot retire submitted geometry in another workspace");
            transitioned.setString("editorWorkspace", "blueprint");
            HologramClient.retireSubmittedLocalArea(transitioned);
            SimpleAssert.that(
                !HologramClient.blueprintInputBusy(),
                "explicit projection-to-blueprint transition retires its originating submitted geometry");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect local area lifecycle", failure);
        } finally {
            HologramClient.state = previous;
            for (Map.Entry<Field, Object> entry : saved.entrySet()) try {
                entry.getKey()
                    .set(null, entry.getValue());
            } catch (IllegalAccessException failure) {
                throw new AssertionError("Cannot restore local area fixture", failure);
            }
        }
    }

    private static void submitted() throws ReflectiveOperationException {
        field("localArea").set(null, new HologramAreaSelection());
        field("localAreaView").set(null, new NBTTagCompound());
        field("localAreaSession").set(null, "first-area");
        field("localAreaTool").set(null, "projector");
        field("localAreaDimension").setInt(null, 0);
        field("localAreaRevision").setLong(null, 7);
        field("localAreaCommandSequence").setLong(null, 12);
        field("localAreaAwaitingBegin").setBoolean(null, false);
        field("localAreaFinishing").setBoolean(null, true);
        field("localAreaCancelling").setBoolean(null, false);
    }

    private static NBTTagCompound complete() {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("session", "first-area");
        data.setString("toolId", "projector");
        data.setInteger("dimension", 0);
        data.setLong("planRevision", 8);
        data.setLong("uiSequence", 12);
        data.setBoolean("areaSelecting", false);
        data.setBoolean("areaImported", true);
        data.setString("blueprintSource", "saved");
        return data;
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = HologramClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
