package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

/** Observes the existing task owners. No clock-based completion, invented totals, or task ownership. */
final class HologramEditorTaskProgress extends Widget<HologramEditorTaskProgress> {

    private final HologramUiModules ui;
    private final Supplier<HologramProgress> cut;
    private final List<HologramProgress> observations = new ArrayList<>();

    HologramEditorTaskProgress(HologramUiModules ui, Supplier<HologramProgress> cut) {
        this.ui = ui;
        this.cut = cut;
        name("editor_native_task_progress");
        disableHoverBackground();
        setEnabled(false);
        tooltipBuilder(tooltip -> {
            long now = System.nanoTime();
            for (HologramProgress progress : observations)
                tooltip.addLine(IKey.str(HologramProgressView.summary(progress, now)));
        }).tooltipAutoUpdate(true);
        ui.shell.registerTarget("progress", this);
        ui.shell.registerTarget("progress.tasks", this);
    }

    void refresh() {
        observations.clear();
        if (!ui.session.isTutorial()) {
            add(ui.session.progress());
            add(HologramBackgroundTasks.progress());
            add(HologramClientProgress.view("server", ui.session.state().session));
            add(HologramClientProgress.view("snapshot", ""));
            add(HologramLibraryClient.INSTANCE.progress());
            if (ui.workspaces.isCut()) add(cut.get());
            add(ui.textBoxes.progress());
        }
        setEnabled(!observations.isEmpty());
    }

    private void add(HologramProgress progress) {
        if (progress == null) return;
        for (HologramProgress existing : observations)
            if (existing.operation.equals(progress.operation) && existing.phase.equals(progress.phase)) return;
        observations.add(progress);
    }

    List<HologramProgress> observations() {
        return new ArrayList<>(observations);
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
        int count = observations.size();
        if (count == 0) return;
        int width = Math.max(0, getArea().width - 4);
        for (int i = 0; i < count; i++) {
            int left = 2 + width * i / count, right = 2 + width * (i + 1) / count;
            HologramProgressView.drawCompact(ui.host.font(), observations.get(i), left, 2, right - left - 3, 3);
        }
    }
}
