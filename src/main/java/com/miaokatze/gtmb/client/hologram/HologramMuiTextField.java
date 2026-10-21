package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

/** Workflow-facing draft adapter; editing, selection, clipboard and focus belong to MUI2. */
final class HologramMuiTextField {

    private final HologramMuiCanvasScreen owner;
    private final NativeField field = new NativeField();
    private final StringValue value = new StringValue("");
    private final int x, y, width, height;
    final int xPosition, yPosition;
    private int actualX = -1, actualY = -1, actualW, actualH;
    private long frame;
    private int maxLength = 32;
    private boolean editable = true, requestFocus;
    private boolean nativeOwned;
    private com.cleanroommc.modularui.widget.ParentWidget<?> legacyParent;

    boolean nativeOwned() { return nativeOwned; }
    com.cleanroommc.modularui.widget.ParentWidget<?> legacyParent() { return legacyParent; }

    TextFieldWidget claimNative() {
        if (nativeOwned) return field;
        if (legacyParent != null || field.isValid()) throw new IllegalStateException("Text field already belongs to a legacy panel");
        nativeOwned=true;
        field.widthRel(1f).height(Math.max(18,height));
        field.setEnabled(true);
        return field;
    }

    void claimLegacy(com.cleanroommc.modularui.widget.ParentWidget<?> mount) {
        if (nativeOwned) throw new IllegalStateException("Native dock field cannot be reparented");
        if (legacyParent == mount) return;
        if (legacyParent != null || field.isValid()) throw new IllegalStateException("Text field already has a parent");
        legacyParent=mount;
        mount.child(field);
    }

    HologramMuiTextField(HologramMuiCanvasScreen owner, int x, int y, int width, int height) {
        this.owner = owner;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        xPosition = x;
        yPosition = y;
        field.background(HologramEditorTheme.INPUT)
            .setTextColor(HologramEditorTheme.TEXT)
            .hintColor(HologramEditorTheme.MUTED)
            .setMarkedColor(0xffc4a36b)
            .value(value)
            .setMaxLength(maxLength)
            .autoUpdateOnChange(true);
        field.setEnabled(false);
        field.onUpdateListener(widget -> {
            if (nativeOwned && requestFocus && editable && field.isValid() && field.areAncestorsEnabled()
                && field.getPanel().isEnabled()) {
                requestFocus = false;
                field.getContext().focus(field);
            }
        });
        owner.registerField(this);
    }

    TextFieldWidget widget() {
        return field;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    boolean sameBounds(HologramMuiTextField other) {
        return x == other.x && y == other.y && width == other.width && height == other.height;
    }

    void drawTextBox() {
        owner.showField(this);
    }

    void show(long frame, CanvasTransform transform) {
        if(nativeOwned) return;
        this.frame = frame;
        field.setTextColor(
            editable && !HologramBackgroundTasks.busy() ? HologramEditorTheme.TEXT : HologramEditorTheme.MUTED);
        int px = (int) Math.round(transform.toScreenX(x)), py = (int) Math.round(transform.toScreenY(y));
        int w = Math.max(1, (int) Math.round(width * transform.scale()));
        int h = Math.max(1, (int) Math.round(height * transform.scale()));
        if (actualX != px || actualY != py || actualW != w || actualH != h) {
            actualX = px;
            actualY = py;
            actualW = w;
            actualH = h;
            field.pos(px, py)
                .size(w, h)
                .scheduleResize();
        }
        field.setEnabled(true);
        if (requestFocus && field.isValid()) {
            requestFocus = false;
            field.getContext()
                .focus(field);
        }
    }

    void finishFrame(long frame) {
        if(nativeOwned) return;
        if (this.frame == frame) return;
        if (field.isValid() && field.isFocused()) field.getContext()
            .removeFocus();
        field.setEnabled(false);
    }

    void drawPassive(long frame) {
        if (this.frame != frame || actualW <= 0 || actualH <= 0) return;
        HologramEditorTheme.INPUT.draw((float) actualX, actualY, actualW, actualH);
        net.minecraft.client.gui.FontRenderer font = net.minecraft.client.Minecraft.getMinecraft().fontRenderer;
        font.drawString(
            font.trimStringToWidth(getText(), Math.max(1, actualW - 6)),
            actualX + 3,
            actualY + Math.max(1, (actualH - font.FONT_HEIGHT) / 2),
            HologramEditorTheme.TEXT);
    }

    void hideForOverlay() {
        if(nativeOwned) return;
        frame = -1;
    }

    String getText() {
        return value.getStringValue();
    }

    String getSelectedText() {
        return field.selectedText();
    }

    void setText(String text) {
        String bounded = text.length() > maxLength ? text.substring(0, maxLength) : text;
        value.setStringValue(bounded);
        field.setText(bounded);
        field.cursorEnd();
    }

    void setMaxStringLength(int max) {
        maxLength = Math.max(0, max);
        field.setMaxLength(maxLength);
        if (getText().length() > maxLength) setText(getText());
    }

    void setEnabled(boolean enabled) {
        editable = enabled;
        field.setTextColor(enabled ? HologramEditorTheme.TEXT : HologramEditorTheme.MUTED);
        if (!enabled) setFocused(false);
    }

    boolean isFocused() {
        return field.isValid() && field.isFocused();
    }

    void setFocused(boolean focused) {
        requestFocus = focused;
        if (!field.isValid()) return;
        if (focused && field.isEnabled()) field.getContext()
            .focus(field);
        else if (isFocused()) field.getContext()
            .removeFocus();
    }

    // Old workflow calls remain harmless: the native widget has already received the input event.
    void updateCursorCounter() {}

    void mouseClicked(int x, int y, int button) {}

    boolean textboxKeyTyped(char character, int key) {
        return false;
    }

    void setCursorPositionEnd() {
        field.cursorEnd();
    }

    void setSelectionPos(int position) {
        field.selection(position);
    }

    private final class NativeField extends TextFieldWidget {

        @Override
        public Result onMousePressed(int button) {
            return editable && !HologramBackgroundTasks.busy() ? super.onMousePressed(button) : Result.STOP;
        }

        @Override
        public Result onKeyPressed(char character, int key) {
            return editable && !HologramBackgroundTasks.busy() ? super.onKeyPressed(character, key) : Result.STOP;
        }

        void cursorEnd() {
            handler.setCursor(0, getText().length(), true);
        }

        void selection(int position) {
            handler.setOffsetCursor(0, position);
        }

        String selectedText() {
            return handler.getSelectedText();
        }
    }
}
