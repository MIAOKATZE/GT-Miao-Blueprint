package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.SecondaryPanel;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.Dialog;

/** Reuses the complete legacy editor in a native panel owned by the live workbench. */
abstract class HologramAttachmentEditor extends HologramMuiScreen {

    protected final HologramUiModules attachmentUi;
    private final String kind;
    private final int editorWidth, editorHeight;
    private final List<ModularPanel> children = new ArrayList<>();
    private FloatingPanel floating;
    private boolean embedded, retired;
    private String session;
    private long revision;
    private NBTTagCompound restoreState;

    HologramAttachmentEditor(HologramUiModules ui, String kind, int width, int height) {
        super(kind + "_editor", width, height);
        attachmentUi = ui;
        this.kind = kind;
        editorWidth = width;
        editorHeight = height;
    }

    final void openFloating() {
        embedded = true;
        session = attachmentUi.session.state().session;
        revision = attachmentUi.session.revision();
        attachmentUi.host.parent()
            .openDialog("gtmb_attachment_editor", (parent, player) -> {
                floating = new FloatingPanel();
                try {
                    buildPanel();
                    if (restoreState != null) restoreEditorState(restoreState);
                    floating.child(
                        new TitleDrag().pos(0, 0)
                            .size(editorWidth - 36, 32));
                    IWidget close = HologramEditorNativeTheme
                        .iconButton("close", () -> "返回工作区 · Esc / E", this::returnToWorkbench, () -> true, () -> false)
                        .pos(editorWidth - 30, 7)
                        .size(20);
                    floating.child(close);
                    target("close", close);
                    target("panel", floating);
                    floating.onCloseAction(this::retire);
                    return floating;
                } catch (RuntimeException failure) {
                    retire();
                    throw failure;
                }
            }, this::admitted);
    }

    private boolean admitted() {
        boolean valid = !retired && attachmentUi.textBoxes.allowed()
            && session.equals(attachmentUi.session.state().session)
            && revision == attachmentUi.session.revision();
        if (!valid) retire();
        return valid;
    }

    final void openFloating(NBTTagCompound state) {
        restoreState = state;
        openFloating();
    }

    @Override
    protected ModularPanel panel() {
        return embedded ? floating : super.panel();
    }

    @Override
    void deferAction(Runnable action) {
        if (!embedded) {
            super.deferAction(action);
            return;
        }
        attachmentUi.host.parent()
            .deferAction(() -> {
                if (retired || !editorOpen()) return;
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    onMuiActionFailure(failure);
                }
            });
    }

    @Override
    void openDialog(String name, SecondaryPanel.IPanelBuilder builder) {
        if (!embedded) {
            super.openDialog(name, builder);
            return;
        }
        deferAction(
            () -> attachmentUi.host.parent()
                .openDialog(name, (parent, player) -> {
                    ModularPanel child = builder.build(parent, player);
                    children.add(child);
                    return child;
                }, this::admitted));
    }

    final boolean editorOpen() {
        return embedded && !retired && (floating == null || floating.isValid() && !floating.isClosing());
    }

    /** Called outside dispatch, including tutorial checkpoint replacement and root disposal. */
    final void closeFloating() {
        if (!embedded) return;
        for (ModularPanel child : children) if (child.isValid()) child.closeIfOpen();
        children.clear();
        if (floating != null && floating.isValid()) floating.closeIfOpen();
        retire();
    }

    private void retire() {
        if (retired) return;
        retired = true;
        for (ModularPanel child : children) if (child.isValid()) child.closeIfOpen();
        children.clear();
        onMuiClose();
    }

    protected final void returnToWorkbench() {
        if (embedded) attachmentUi.host.parent()
            .deferAction(this::closeFloating);
        else deferAction(
            () -> Minecraft.getMinecraft()
                .displayGuiScreen(attachmentUi.host.parent()));
    }

    protected final <T extends IWidget> T target(String key, T widget) {
        if (embedded) attachmentUi.shell.registerTarget("attachment." + kind + "." + key, widget);
        return widget;
    }

    final <T extends IWidget> T sharedTarget(String key, T widget) {
        if (embedded) attachmentUi.shell.registerTarget(key, widget);
        return widget;
    }

    abstract NBTTagCompound editorState();

    abstract void restoreEditorState(NBTTagCompound state);

    /** All coordinates, clipping and hit testing use the same native viewport transform. */
    private final class FloatingPanel extends Dialog<Void> {

        private float x = Float.NaN, y = Float.NaN;

        FloatingPanel() {
            super(attachmentUi.panelName("attachment_editor"));
            size(editorWidth, editorHeight).pos(0, 0)
                .background(HologramEditorNativeTheme.PANEL);
            setDisablePanelsBelow(true).setCloseOnOutOfBoundsClick(false)
                .setDraggable(false);
        }

        private int availableHeight() {
            return Math.max(
                1,
                getScreen().getScreenArea().height
                    - (attachmentUi.session.isTutorial() ? HologramEditorTutorialOverlay.RESERVED_HEIGHT : 0));
        }

        private float scale() {
            return Math.min(
                1f,
                Math.min(
                    Math.max(1, getScreen().getScreenArea().width - 12) / (float) editorWidth,
                    Math.max(1, availableHeight() - 12) / (float) editorHeight));
        }

        private void clamp() {
            float scale = scale();
            float maxX = Math.max(0, getScreen().getScreenArea().width - editorWidth * scale);
            float maxY = Math.max(0, availableHeight() - editorHeight * scale);
            x = Float.isNaN(x) ? maxX / 2 : Math.max(0, Math.min(maxX, x));
            y = Float.isNaN(y) ? maxY / 2 : Math.max(0, Math.min(maxY, y));
        }

        @Override
        public void transform(IViewportStack stack) {
            clamp();
            stack.translate(x, y);
            stack.scale(scale(), scale());
        }

        @Override
        public void onUpdate() {
            super.onUpdate();
            if (retired || !attachmentUi.textBoxes.allowed()
                || !session.equals(attachmentUi.session.state().session)
                || revision != attachmentUi.session.revision())
                attachmentUi.host.parent()
                    .deferAction(HologramAttachmentEditor.this::closeFloating);
        }

        @Override
        public boolean shouldAnimate() {
            return false;
        }

        @Override
        public boolean onKeyPressed(char character, int key) {
            super.onKeyPressed(character, key);
            // The workbench handles layered back before MUI; every other key belongs to this editor.
            return true;
        }

        @Override
        public void dispose() {
            super.dispose();
            retire();
        }
    }

    /** Only the title starts a drag; form controls retain their native selection/drag behavior. */
    private final class TitleDrag extends Widget<TitleDrag> implements Interactable {

        private int mouseX, mouseY;
        private float startX, startY;

        @Override
        public Result onMousePressed(int button) {
            if (button != 0) return Result.STOP;
            floating.clamp();
            mouseX = getContext().getAbsMouseX();
            mouseY = getContext().getAbsMouseY();
            startX = floating.x;
            startY = floating.y;
            return Result.SUCCESS;
        }

        @Override
        public void onMouseDrag(int button, long elapsed) {
            if (button != 0) return;
            floating.x = startX + getContext().getAbsMouseX() - mouseX;
            floating.y = startY + getContext().getAbsMouseY() - mouseY;
            floating.clamp();
        }

        @Override
        public boolean onMouseRelease(int button) {
            return true;
        }
    }
}
