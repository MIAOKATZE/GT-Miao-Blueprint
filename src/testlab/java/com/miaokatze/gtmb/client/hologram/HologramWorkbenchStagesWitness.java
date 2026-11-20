// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Observes one open or recapture through its real workbench and renderer. */
final class HologramWorkbenchStagesWitness {

    private final HologramState preceding;
    private final long started;
    private HologramState early, complete;
    private long firstDraw, lastDrawCount;
    private boolean drawnBeforeComplete, earlyAtDrawPost, completeAtDrawPost;
    private int pick = -1;
    private int[] pickedPosition;
    private boolean earlyPick;

    HologramWorkbenchStagesWitness(HologramState preceding, long started) {
        this.preceding = preceding;
        this.started = started;
        lastDrawCount = MiaoVisonLiteRenderer.previewDirectMeshDraws;
    }

    boolean guiPost(HologramScreen screen) {
        long now = System.nanoTime();
        long drawCount = MiaoVisonLiteRenderer.previewDirectMeshDraws;
        boolean drew = drawCount > lastDrawCount;
        lastDrawCount = drawCount;
        HologramState state = screen.ui.session.state();
        if (!active(state)) return false;
        observe(state);
        boolean ready = complete(state);
        if (ready) completeAtDrawPost = true;
        else earlyAtDrawPost = true;
        if (!drew || !screen.localControllerStandard() || screen.ui.preview.sourcePreparing()) return false;
        boolean first = firstDraw == 0;
        if (first) {
            firstDraw = now;
            drawnBeforeComplete = !ready;
        }
        if (pick < 0) {
            int cell = pickBody(screen, state);
            if (cell >= 0) {
                pick = cell;
                pickedPosition = position(state.cells.get(cell));
                earlyPick = !ready;
            }
        }
        return first;
    }

    void observe(HologramState state) {
        if (!active(state)) return;
        if (complete(state)) {
            if (complete == null) complete = state;
        } else if (early == null) early = state;
    }

    private boolean active(HologramState state) {
        return !state.cells.isEmpty() && (preceding == null || state.cells != preceding.cells);
    }

    private static boolean complete(HologramState state) {
        return state.data.getBoolean("projectionDataComplete") && !state.data.getBoolean("capturePending");
    }

    boolean sawMaterial() {
        return firstDraw != 0;
    }

    Map<String, Object> receipt(HologramState state, NBTTagList marks, boolean requireEarly, boolean requireEarlyDraw) {
        Map<String, Object> result = new LinkedHashMap<>();
        long target = 0, installed = 0, firstMaterial = 0;
        boolean firstMaterialBeforeInstall = false;
        List<Map<String, Object>> events = new ArrayList<>();
        for (int index = 0; index < marks.tagCount(); index++) {
            NBTTagCompound mark = marks.getCompoundTagAt(index);
            long at = mark.getLong("atNanos");
            if (at < started || !state.session.equals(mark.getString("session"))) continue;
            String stage = mark.getString("stage");
            switch (stage) {
                case "CLIENT_CONTROLLER_TARGET" -> {
                    if (target == 0) target = at;
                }
                case "CLIENT_INSTALL" -> {
                    if (installed == 0) installed = at;
                }
                case "CLIENT_FIRST_MATERIAL" -> {
                    if (firstMaterial == 0) {
                        firstMaterial = at;
                        firstMaterialBeforeInstall = installed == 0;
                    }
                }
                default -> {
                    continue;
                }
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("stage", stage);
            event.put("elapsedNanos", at - started);
            event.put("revision", mark.getLong("revision"));
            event.put("count", mark.getLong("count"));
            events.add(event);
        }
        result.put("events", events);
        result.put("targetInstalledNanos", target == 0 ? null : target - started);
        result.put("completeInstalledNanos", installed == 0 ? null : installed - started);
        result.put("firstMaterialMarkNanos", firstMaterial == 0 ? null : firstMaterial - started);
        result.put("firstMaterialDrawPostNanos", firstDraw == 0 ? null : firstDraw - started);
        result.put("firstMaterialBeforeComplete", firstMaterialBeforeInstall || drawnBeforeComplete);
        result.put("earlySnapshotObserved", early != null);
        result.put("completeSnapshotObserved", complete != null);
        result.put("earlySnapshotObservedAtDrawPost", earlyAtDrawPost);
        result.put("completeSnapshotObservedAtDrawPost", completeAtDrawPost);
        if (requireEarly && (target == 0 || installed < target))
            throw new IllegalStateException("Expected target publication followed by full controller data: " + result);
        if (preceding != null) {
            boolean newGeneration = !preceding.data.getString("generation").equals(state.data.getString("generation"));
            result.put("recaptureHasNewGeneration", newGeneration);
            if (requireEarly && !newGeneration)
                throw new IllegalStateException("Controller recapture reused the preceding geometry generation");
        }
        if (requireEarlyDraw && !drawnBeforeComplete)
            throw new IllegalStateException("Expected an early controller material frame: " + result);
        if (early != null) {
            result.put("earlyGeneration", early.data.getString("generation"));
            result.put("completeGeneration", state.data.getString("generation"));
            sameGeometry(early, state);
        }
        if (pick >= 0 && (pick >= state.cells.size()
            || !java.util.Arrays.equals(pickedPosition, position(state.cells.get(pick)))))
            throw new IllegalStateException("Picked coordinates changed when details arrived");
        result.put("firstBodyPickIndex", pick < 0 ? null : pick);
        result.put("firstBodyPick", pickedPosition);
        result.put("firstBodyPickBeforeComplete", earlyPick);
        return result;
    }

    private static int pickBody(HologramScreen screen, HologramState state) {
        var bounds = screen.ui.canvas.viewport();
        boolean nativeScene = screen.ui.canvas.nativeScene();
        double left = nativeScene ? 0 : bounds.x(), top = nativeScene ? 0 : bounds.y();
        for (int vertical = 1; vertical < 8; vertical++)
            for (int horizontal = 1; horizontal < 8; horizontal++) {
                int picked = HologramRenderer.pick(
                    (int) Math.round(left + bounds.width() * horizontal / 8),
                    (int) Math.round(top + bounds.height() * vertical / 8));
                if (picked >= 0 && picked < state.cells.size()) {
                    HologramState.Cell cell = state.cells.get(picked);
                    if (!cell.anchor && !cell.iconOnly && !cell.missingBlock && !"minecraft:air".equals(cell.wantId))
                        return picked;
                }
            }
        return -1;
    }

    private static void sameGeometry(HologramState early, HologramState complete) {
        if (!early.session.equals(complete.session)
            || !early.data.getString("generation").equals(complete.data.getString("generation"))
            || early.cells.size() != complete.cells.size())
            throw new IllegalStateException("Controller details changed the installed geometry identity");
        for (int index = 0; index < early.cells.size(); index++) {
            HologramState.Cell a = early.cells.get(index), b = complete.cells.get(index);
            if (a.index != b.index || a.x != b.x || a.y != b.y || a.z != b.z
                || a.dx != b.dx || a.dy != b.dy || a.dz != b.dz
                || a.meta != b.meta || a.wantMeta != b.wantMeta || a.anchor != b.anchor
                || a.targetFacing != b.targetFacing || a.actualFacing != b.actualFacing
                || !a.id.equals(b.id) || !a.wantId.equals(b.wantId)
                || !ItemStack.areItemStacksEqual(a.actualStack, b.actualStack)
                || !ItemStack.areItemStacksEqual(a.targetStack, b.targetStack))
                throw new IllegalStateException("Controller details changed target, observation or index " + index);
        }
    }

    private static int[] position(HologramState.Cell cell) {
        return new int[] {cell.dx, cell.dy, cell.dz};
    }
}
