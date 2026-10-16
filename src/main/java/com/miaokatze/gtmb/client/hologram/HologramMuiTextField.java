package com.miaokatze.gtmb.client.hologram;

import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

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

    HologramMuiTextField(HologramMuiCanvasScreen owner, int x, int y, int width, int height) {
        this.owner = owner;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        xPosition = x;
        yPosition = y;
        field.background(HologramMuiTheme.PANEL)
            .setTextColor(HologramMuiTheme.TEXT)
            .hintColor(HologramMuiTheme.MUTED)
            .setMarkedColor(0xffc4a36b)
            .value(value)
            .setMaxLength(maxLength)
            .autoUpdateOnChange(true);
        field.setEnabled(false);
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

    void show(long frame, int originX, int originY, float scale) {
        this.frame = frame;
        int px = x, py = y;
        int w = Math.max(1, width), h = Math.max(1, height);
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
        if (this.frame == frame) return;
        setFocused(false);
        field.setEnabled(false);
    }

    void hideForOverlay() {
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
        field.setTextColor(enabled ? HologramMuiTheme.TEXT : HologramMuiTheme.MUTED);
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
            return editable ? super.onMousePressed(button) : Result.STOP;
        }

        @Override
        public Result onKeyPressed(char character, int key) {
            return editable ? super.onKeyPressed(character, key) : Result.STOP;
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
