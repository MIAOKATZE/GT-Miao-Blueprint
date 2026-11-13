// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.widget.ScrollWidget;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Native presentation of the existing identity workflow. The workflow alone owns choices and requests. */
final class HologramEditorIdentityDialog {

    private final HologramUiModules ui;
    private IPanelHandler handler;
    private IdentityPanel panel;

    HologramEditorIdentityDialog(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean active() {
        return !ui.tutorial.active() && ui.identity.deidentificationDrawer();
    }

    void refresh() {
        if (panel != null) panel.fit();
        if (active() == (handler != null && handler.isPanelOpen())) return;
        ui.host.parent()
            .deferAction(() -> {
                if (active()) {
                    if (handler == null) handler = IPanelHandler.simple(ui.shell.host(), (parent, player) -> {
                        panel = new IdentityPanel();
                        return panel;
                    }, true);
                    if (!handler.isPanelOpen()) handler.openPanel();
                } else if (handler != null && handler.isPanelOpen()) handler.closePanel();
            });
    }

    private void activate(int id) {
        ui.host.parent()
            .deferAction(() -> {
                if (!active()) return;
                try (HologramMuiCanvasScreen.FieldGraph ignored = ui.host.parent()
                    .fieldGraph(ui)) {
                    ui.identity.activateDeidentification(id);
                }
            });
    }

    private ButtonWidget<?> action(int id, Supplier<String> label, BooleanSupplier enabled, BooleanSupplier selected,
        boolean primary, String tooltip) {
        ButtonWidget<?> button = HologramEditorNativeTheme
            .button(
                label,
                null,
                () -> { if (active() && enabled.getAsBoolean()) activate(id); },
                () -> active() && enabled.getAsBoolean(),
                selected,
                primary)
            .name("identity_" + id)
            .tooltipBuilder(t -> t.addLine(tooltip));
        ui.shell.registerControl(id, button, label, () -> active() && enabled.getAsBoolean());
        return button;
    }

    private final class IdentityPanel extends Dialog<Void> {

        private final VerticalScrollData scroll = new VerticalScrollData();
        private final ScrollWidget<?> viewport = new ScrollWidget<>(scroll);
        private final Flow footer = Flow.row()
            .height(24)
            .childPadding(8);
        private final TextWidget<?> heading;
        private int fittedWidth = -1, fittedHeight = -1;

        IdentityPanel() {
            super(ui.panelName("gtmb_native_identity"));
            setDisablePanelsBelow(true);
            setCloseOnOutOfBoundsClick(false);
            setDraggable(false);
            background(HologramEditorNativeTheme.PANEL);
            heading = HologramEditorNativeTheme
                .label(() -> ui.identity.placement() ? "本次放置校验与去标识" : "保存校验与去标识", HologramEditorNativeTheme.TITLE)
                .pos(12, 10)
                .height(20);
            child(heading);
            scroll.setScrollSize(270);
            Flow body = Flow.col()
                .widthRel(1f)
                .height(270)
                .childPadding(6);
            body.child(
                HologramEditorNativeTheme
                    .label(
                        () -> "当前等级 "
                            + ui.session.state().data.getInteger(
                                ui.identity.placement() ? "placementDeidentificationLevel"
                                    : "blueprintDeidentificationLevel")
                            + " · 选择 "
                            + ui.identity.selectedLevel()
                            + " · 推荐 "
                            + ui.session.state().data.getInteger("deidentificationRecommendedLevel"))
                    .widthRel(1f)
                    .height(20));
            body.child(HologramEditorNativeTheme.label(() -> {
                int minimum = HologramDeidentificationMemory.minimum(ui.session.state().data);
                return minimum > 0 ? "已处理的数据无法还原；本图最低可选等级为 " + minimum + "。" : "等级 0 保留原始身份；等级越高，清除的外部身份与运行数据越多。";
            })
                .widthRel(1f)
                .height(32));
            for (int start = 0; start <= 6; start += 6) {
                Flow row = Flow.row()
                    .widthRel(1f)
                    .height(24)
                    .childPadding(4);
                for (int level = start; level < Math.min(start + 6, 11); level++) {
                    final int choice = level;
                    row.child(
                        action(
                            22000 + choice,
                            () -> Integer.toString(choice),
                            () -> choice >= HologramDeidentificationMemory.minimum(ui.session.state().data),
                            () -> choice == ui.identity.selectedLevel(),
                            false,
                            "选择去标识等级 " + choice).widthRel(1f)
                                .expanded()
                                .height(24));
                }
                body.child(row);
            }
            body.child(
                HologramEditorNativeTheme
                    .label(
                        () -> "1 身份与网络 · 2 外部坐标 · 3 频率 · 4 安全凭据 · 5 连接记录\n"
                            + "6 屏幕 / 核心缓存 · 7 监控 / 图表缓存 · 8 私人界面页\n9 停机诊断 · 10 瞬时运行状态；保留配方数据")
                    .widthRel(1f)
                    .height(48));
            body.child(
                HologramEditorNativeTheme
                    .label(() -> "记住这份蓝图的选择（含 0）；下次放置及 Alt 快捷预览沿用。", HologramEditorNativeTheme.MUTED)
                    .widthRel(1f)
                    .height(28));
            body.child(
                HologramEditorNativeTheme
                    .label(() -> ui.identity.placement() ? "修正保存数据或清理阻塞物后，仍需重新核对材料并确认放置。" : "保存仍会检查全部数据；合并提示可展开查看每个位置。")
                    .widthRel(1f)
                    .height(28));
            Flow tools = Flow.row()
                .widthRel(1f)
                .height(24)
                .childPadding(8);
            tools.child(
                action(
                    214,
                    () -> "一键去标识 · 等级 " + Math.max(1, ui.identity.selectedLevel()),
                    () -> ui.session.editable() && ui.session.state().data.getBoolean("virtual"),
                    () -> false,
                    false,
                    "沿用现有处理与服务端校验").widthRel(1f)
                        .expanded()
                        .height(24));
            tools.child(
                action(
                    215,
                    () -> "移除放置阻塞物",
                    () -> ui.session.editable() && ui.review.placementReviewActive()
                        && ui.review.materialBlockers() > 0,
                    () -> false,
                    false,
                    "沿用现有阻塞物移除请求；不会确认施工").widthRel(1f)
                        .expanded()
                        .height(24));
            body.child(tools);
            viewport.child(body)
                .pos(12, 36);
            child(viewport);
            ButtonWidget<?> cancel = action(
                212,
                () -> ui.identity.placement() ? "无需去标识" : "取消",
                () -> true,
                () -> false,
                false,
                "Esc / E：沿用原选择与取消流程").widthRel(1f)
                    .expanded()
                    .height(24);
            footer.child(cancel);
            footer.child(
                action(
                    213,
                    () -> ui.identity.placement() ? "记住并应用" : "记住保存选择",
                    ui.session::editable,
                    () -> false,
                    true,
                    "Enter：提交当前选择，仍由服务端检查").widthRel(1f)
                        .expanded()
                        .height(24));
            child(footer);
            ui.shell.registerTarget("identity", this);
            ui.shell.registerTarget("identity.viewport", viewport);
            ui.shell.registerTarget("identity.levels", body);
            ui.shell.registerTarget("modal.close", cancel);
            onCloseAction(
                () -> { if (active() && ui.host.minecraft().currentScreen == ui.host.parent()) activate(212); });
            fit();
        }

        void fit() {
            int width = Math.max(240, Math.min(444, ui.host.screenWidth() - 12));
            int height = Math.max(160, Math.min(366, ui.host.screenHeight() - 12));
            if (width == fittedWidth && height == fittedHeight) return;
            fittedWidth = width;
            fittedHeight = height;
            size(width, height).center();
            heading.width(width - 24);
            viewport.size(width - 24, height - 78);
            footer.pos(12, height - 36)
                .width(width - 24);
            scheduleResize();
        }

        @Override
        public boolean onKeyPressed(char character, int key) {
            if (!active()) return true;
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_E) {
                activate(212);
                return true;
            }
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                activate(213);
                return true;
            }
            return super.onKeyPressed(character, key);
        }
    }
}
