// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.miaokatzecore.api.camera.ViewportCoordinates;

/** Library and document controls for a client-owned artifact. Opening and editing do not require a server session. */
final class HologramPrivateBlueprintScreen extends HologramMuiCanvasScreen {

    private static final int OPEN = 1, PLACE = 2, IMPORT = 3, WORKBENCH = 4, BACK = 5, REFRESH = 6;
    private static final int UPSERT = 7, REMOVE = 8, UNDO = 9, REDO = 10, SAVE = 11, SAVE_COPY = 12, HELD = 13;
    private static final int CATALOG_ROW = 1000, ROW_HEIGHT = 20;
    private static final int[] NO_MOVE = new int[3];

    private final HologramLibraryClient library;
    private final HologramClientLibrary store;
    private final GuiScreen parent;
    private final Runnable workbench;
    private final String initialId;
    private final String session = "private-blueprint:" + UUID.randomUUID();
    private volatile boolean closed;
    private boolean initialized, busy, placing, discardArmed;
    private String status = "", id = "", jsonHash = "", pngHash = "", selectedId = "";
    private String replacementWarning = "";
    private List<HologramClientLibrary.Entry> rows = Collections.emptyList();
    private int scroll, hovered = -1, sampleSlot = -1;
    private HologramPrivateBlueprint document;
    private HologramState view;
    private HologramState.Preparation preparation;
    private boolean previewCurrent;
    private HologramMuiTextField path, x, y, z, block, metadata, item, damage;
    private int left, top, frameWidth, frameHeight, side, previewWidth, previewHeight, rowCount, dragX, dragY;
    private boolean orbiting;
    private double yaw = HologramSavePreviewPose.DEFAULT_YAW, pitch = HologramSavePreviewPose.DEFAULT_PITCH, zoom = 1;

    HologramPrivateBlueprintScreen(HologramLibraryClient library, HologramClientLibrary store, GuiScreen parent,
        Runnable workbench, String initialId) {
        super("private_blueprints");
        this.library = library;
        this.store = store;
        this.parent = parent;
        this.workbench = workbench;
        this.initialId = initialId;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        super.initGui();
        if (width <= 0 || height <= 0) return;
        Keyboard.enableRepeatEvents(true);
        layout();
        if (initialized) return;
        initialized = true;
        if (initialId.isEmpty()) refresh();
        else open(initialId);
    }

    private void layout() {
        for (HologramMuiTextField field : new HologramMuiTextField[] { path, x, y, z, block, metadata, item, damage })
            if (field != null) field.setFocused(false);
        resetNativeFields();
        frameWidth = Math.min(752, width - 20);
        frameHeight = height - 22;
        left = (width - frameWidth) / 2;
        top = 10;
        rowCount = Math.max(1, (frameHeight - 116) / ROW_HEIGHT);
        if (document == null) {
            path = field("private_import_path", path, left + 10, top + frameHeight - 57, frameWidth - 90, 2048);
            return;
        }
        side = left + frameWidth - 146;
        previewWidth = side - left - 16;
        previewHeight = frameHeight - 69;
        x = field("private_x", x, side, top + 43, 42, 10);
        y = field("private_y", y, side + 47, top + 43, 42, 10);
        z = field("private_z", z, side + 94, top + 43, 42, 10);
        block = field("private_block", block, side, top + 73, 92, 256);
        metadata = field("private_metadata", metadata, side + 94, top + 73, 42, 3);
        item = field("private_item", item, side, top + 103, 92, 256);
        damage = field("private_damage", damage, side + 94, top + 103, 42, 6);
    }

    private void drawControls() {
        if (document == null) {
            add(IMPORT, left + frameWidth - 75, top + frameHeight - 58, 65, text("import"));
            int each = (frameWidth - 20) / (workbench == null ? 4 : 5);
            add(OPEN, left + 10, top + frameHeight - 29, each - 4, text("edit"));
            add(PLACE, left + 10 + each, top + frameHeight - 29, each - 4, text("place"));
            add(REFRESH, left + 10 + each * 2, top + frameHeight - 29, each - 4, text("refresh"));
            add(BACK, left + 10 + each * 3, top + frameHeight - 29, each - 4, text("back"));
            if (workbench != null)
                add(WORKBENCH, left + 10 + each * 4, top + frameHeight - 29, each - 4, text("workbench"));
            return;
        }
        add(HELD, side, top + 124, 136, text("held"));
        add(UPSERT, side, top + 147, 66, text("upsert"));
        add(REMOVE, side + 70, top + 147, 66, text("remove"));
        int bottom = top + frameHeight - 28;
        int each = (frameWidth - 20) / 5;
        add(UNDO, left + 10, bottom, each - 4, text("undo"));
        add(REDO, left + 10 + each, bottom, each - 4, text("redo"));
        add(SAVE, left + 10 + each * 2, bottom, each - 4, text("save"));
        add(SAVE_COPY, left + 10 + each * 3, bottom, each - 4, text("saveCopy"));
        add(BACK, left + 10 + each * 4, bottom, each - 4, text("back"));
    }

    private HologramMuiTextField field(String name, HologramMuiTextField previous, int x, int y, int width,
        int maximum) {
        HologramMuiTextField field = new HologramMuiTextField(this, x, y, width, 16);
        field.widget()
            .name(name);
        field.setMaxStringLength(maximum);
        if (previous != null) field.setText(previous.getText());
        return field;
    }

    private void add(int id, int x, int y, int width, String text) {
        boolean active = placing ? id == BACK : !busy && enabled(id);
        nativeButton(id, x, y, width, 20, text, active, id == OPEN || id == SAVE ? HologramUiAccent.NEXT : 0);
    }

    private static String text(String key, Object... arguments) {
        return StatCollector.translateToLocalFormatted("gtmb.hologram.private." + key, arguments);
    }

    private void refresh() {
        start(text("loadingCatalog"));
        library.privateWork(store::list, listed -> {
            if (closed) return;
            rows = listed;
            scroll = Math.min(scroll, Math.max(0, rows.size() - rowCount));
            finish(rows.isEmpty() ? text("emptyCatalog") : "");
        }, this::failed);
    }

    private static final class Opened {

        final HologramPrivateBlueprint document;
        final String jsonHash, pngHash;

        Opened(HologramPrivateBlueprint document, String jsonHash, String pngHash) {
            this.document = document;
            this.jsonHash = jsonHash;
            this.pngHash = pngHash;
        }
    }

    private void open(String selected) {
        start(text("opening"));
        library.privateWork(() -> {
            HologramBlueprintArtifact.Artifact pair = store.privatePair(selected);
            return new Opened(new HologramPrivateBlueprint(pair.json), pair.jsonSha256, pair.pngSha256);
        }, opened -> opened(selected, opened), this::failed);
    }

    private void importFile(String text) {
        if (text.trim()
            .isEmpty()) {
            status = text("missingPath");
            return;
        }
        start(text("importing"));
        library.privateWork(() -> {
            String filename = text.trim();
            if (filename.startsWith("\"") && filename.endsWith("\"") && filename.length() > 1)
                filename = filename.substring(1, filename.length() - 1);
            Path source = Paths.get(filename);
            if (source.getFileName() == null || !source.getFileName()
                .toString()
                .toLowerCase(java.util.Locale.ROOT)
                .endsWith(".json")) throw new IOException(text("invalidPath"));
            if (!Files.isRegularFile(source)) throw new IOException(text("fileMissing"));
            try (InputStream in = Files.newInputStream(source)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = in.read(buffer)) != -1;) {
                    if (count > HologramBlueprintArtifact.MAX_JSON_BYTES - bytes.size())
                        throw new IOException(text("overBudget"));
                    bytes.write(buffer, 0, count);
                }
                return new Opened(new HologramPrivateBlueprint(bytes.toByteArray()), "", "");
            }
        }, opened -> opened("", opened), this::failed);
    }

    private void opened(String selected, Opened opened) {
        if (closed) return;
        id = selected;
        jsonHash = opened.jsonHash;
        pngHash = opened.pngHash;
        document = opened.document;
        layout();
        select(new HologramPrivateBlueprint.Position(0, 0, 0));
        refreshPreview();
    }

    private void refreshPreview() {
        start(text("preparing"));
        preparation = null;
        previewCurrent = false;
        HologramPrivateBlueprint source = document;
        library.privateWork(() -> source.snapshot(session), snapshot -> {
            if (closed) return;
            try {
                preparation = new HologramState.Preparation(snapshot, true);
                finish("");
            } catch (RuntimeException invalid) {
                failed(invalid);
            }
        }, this::failed);
    }

    private void start(String message) {
        busy = true;
        status = message;
        discardArmed = false;
        replacementWarning = "";
        for (HologramMuiTextField field : fields()) if (field != null) field.setEnabled(false);
    }

    private void finish(String message) {
        if (closed) return;
        busy = false;
        status = message;
    }

    private void failed(Throwable failure) {
        finish(text("failed", HologramUiText.reason(failure.getMessage())));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (placing && !library.busy()) {
            placing = false;
            status = library.status();
        }
        if (preparation != null) try {
            if (preparation.step(8192, 3_000_000L)) {
                view = preparation.result();
                previewCurrent = true;
                preparation = null;
            } else if (view == null) {
                view = preparation.firstDisplay();
                previewCurrent = view != null;
            }
        } catch (RuntimeException invalid) {
            preparation = null;
            failed(invalid);
        }
    }

    private boolean enabled(int id) {
        return switch (id) {
            case OPEN -> !selectedId.isEmpty();
            case PLACE -> !selectedId.isEmpty() && mc.thePlayer != null && !library.busy();
            case HELD -> mc.thePlayer != null;
            case UNDO -> document.canUndo();
            case REDO -> document.canRedo();
            case SAVE -> this.id.isEmpty() || document.dirty();
            default -> true;
        };
    }

    @Override
    protected void activateNative(int control) {
        if (control >= CATALOG_ROW) {
            int row = control - CATALOG_ROW;
            if (row >= rows.size()) return;
            String selected = rows.get(row).id;
            deferAction(() -> { if (document == null && !busy && !placing) selectedId = selected; });
        } else deferAction(() -> perform(control));
    }

    private void perform(int action) {
        if (placing && action == BACK) {
            library.cancel();
            return;
        }
        if (busy || placing || !actionInCurrentPage(action) || !enabled(action)) return;
        if (action != BACK) discardArmed = false;
        if (action != UPSERT && action != REMOVE) replacementWarning = "";
        switch (action) {
            case OPEN -> open(selectedId);
            case PLACE -> {
                library.previewLocal(selectedId, true);
                placing = library.busy();
                status = placing ? text("placing") : library.status();
            }
            case IMPORT -> importFile(path.getText());
            case WORKBENCH -> {
                mc.displayGuiScreen(parent);
                workbench.run();
            }
            case REFRESH -> refresh();
            case BACK -> back();
            case HELD -> held();
            case UPSERT, REMOVE -> edit(action == REMOVE);
            case UNDO, REDO -> {
                HologramPrivateBlueprint.Position changed = document.undo(action == UNDO);
                if (changed != null) {
                    select(changed);
                    refreshPreview();
                }
            }
            case SAVE, SAVE_COPY -> save(action == SAVE_COPY);
            default -> {}
        }
    }

    private boolean actionInCurrentPage(int action) {
        if (action == BACK) return true;
        return document == null ? action <= REFRESH : action >= UPSERT;
    }

    private void back() {
        if (document != null && document.dirty() && !discardArmed) {
            discardArmed = true;
            status = text("discard");
            return;
        }
        if (document == null || !initialId.isEmpty()) {
            mc.displayGuiScreen(parent);
            return;
        }
        document = null;
        if (view != null) HologramRenderer.sceneClosed();
        view = null;
        preparation = null;
        layout();
        refresh();
    }

    private HologramPrivateBlueprint.Position position() {
        return new HologramPrivateBlueprint.Position(
            Integer.parseInt(
                x.getText()
                    .trim()),
            Integer.parseInt(
                y.getText()
                    .trim()),
            Integer.parseInt(
                z.getText()
                    .trim()));
    }

    private void select(HologramPrivateBlueprint.Position position) {
        x.setText(Integer.toString(position.x));
        y.setText(Integer.toString(position.y));
        z.setText(Integer.toString(position.z));
        JsonObject cell = document.cell(position);
        block.setText(HologramPrivateBlueprint.text(cell, "block"));
        item.setText(HologramPrivateBlueprint.text(cell, "item"));
        String meta = HologramPrivateBlueprint.text(cell, "metadata"),
            itemDamage = HologramPrivateBlueprint.text(cell, "damage");
        metadata.setText(meta.isEmpty() ? "0" : meta);
        damage.setText(itemDamage.isEmpty() ? "0" : itemDamage);
        replacementWarning = "";
    }

    private void held() {
        int start = sampleSlot < 0 ? mc.thePlayer.inventory.currentItem - 1 : sampleSlot;
        for (int offset = 1; offset <= 9; offset++) {
            int slot = Math.floorMod(start + offset, 9);
            ItemStack sample = mc.thePlayer.inventory.mainInventory[slot];
            Block sampled = sample == null ? Blocks.air : Block.getBlockFromItem(sample.getItem());
            if (sampled == null || sampled == Blocks.air) continue;
            block.setText(String.valueOf(Block.blockRegistry.getNameForObject(sampled)));
            item.setText(String.valueOf(Item.itemRegistry.getNameForObject(sample.getItem())));
            metadata.setText(
                Integer.toString(
                    sample.getItem()
                        .getMetadata(sample.getItemDamage()) & 15));
            damage.setText(Integer.toString(sample.getItemDamage()));
            sampleSlot = slot;
            status = text("sampled", slot + 1, sample.getDisplayName());
            return;
        }
        status = text("noHeld");
    }

    private void edit(boolean remove) {
        try {
            HologramPrivateBlueprint.Position position = position();
            JsonObject old = document.cell(position);
            String blockId = block.getText()
                .trim(),
                itemId = item.getText()
                    .trim();
            int meta = remove ? 0
                : Integer.parseInt(
                    metadata.getText()
                        .trim());
            int dmg = remove || itemId.isEmpty() ? 0
                : Integer.parseInt(
                    damage.getText()
                        .trim());
            if (!remove && (!blockId.matches("[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+")
                || !itemId.isEmpty() && !itemId.matches("[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+")
                || meta < 0
                || meta > 15
                || dmg < 0
                || dmg > Short.MAX_VALUE)) {
                status = text("materials");
                return;
            }
            if (!remove && itemId.isEmpty() && !blockId.equals(HologramPrivateBlueprint.text(old, "block"))) {
                Block material = Block.getBlockFromName(blockId);
                Item resolved = material == null ? null : Item.getItemFromBlock(material);
                if (resolved != null && material != Blocks.air) {
                    itemId = String.valueOf(Item.itemRegistry.getNameForObject(resolved));
                    dmg = meta;
                }
            }
            if (old != null && (old.has("tileNbt") || old.has("itemNbt"))) {
                String intent = remove + ":" + position + ":" + blockId + ":" + meta + ":" + itemId + ":" + dmg;
                if (!intent.equals(replacementWarning)) {
                    replacementWarning = intent;
                    status = text("confirmPayload");
                    return;
                }
            }
            boolean changed = remove ? document.remove(position)
                : document.upsert(position, blockId, meta, itemId, dmg);
            if (changed) {
                select(position);
                refreshPreview();
            } else status = remove ? text("noCell") : text("sameCell");
        } catch (NumberFormatException invalid) {
            status = text("integers");
        } catch (IOException | IllegalStateException invalid) {
            failed(invalid);
        }
    }

    private void save(boolean copy) {
        start(text("processing"));
        HologramPrivateBlueprint source = document;
        String target = copy ? "" : id, expectedJson = jsonHash, expectedPng = pngHash;
        double saveYaw = yaw, savePitch = pitch;
        library.privateWork(() -> {
            byte[] json = source.saveBytes();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(source.thumbnail(saveYaw, savePitch), "png", bytes))
                throw new IOException(text("pngEncoder"));
            HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact.prepareSave(json, bytes.toByteArray());
            return store.saveDocument(pair.json, pair.png, target, expectedJson, expectedPng, () -> !closed);
        }, saved -> {
            if (closed) return;
            id = selectedId = saved.id;
            jsonHash = saved.jsonHash;
            pngHash = saved.pngHash;
            document.saved();
            finish(text("saved"));
        }, this::failed);
    }

    private HologramMuiTextField[] fields() {
        return document == null ? new HologramMuiTextField[] { path }
            : new HologramMuiTextField[] { x, y, z, block, metadata, item, damage };
    }

    @Override
    protected void renderScene(int mouseX, int mouseY, float delta) {
        if (!initialized) return;
        canvasTransform(0, 0, 1f);
        drawDefaultBackground();
        drawRect(left, top, left + frameWidth, top + frameHeight, 0xf51a2229);
        drawRect(left, top, left + frameWidth, top + 24, 0xff133b43);
        String title = document == null ? text("library")
            : (document.name()
                .isEmpty() ? text("unnamed") : document.name()) + (document.dirty() ? " *" : "");
        label(title, left + 10, top + 8, frameWidth - 20, 0xe6efe9);
        if (document == null) drawCatalog();
        else drawDocument(mouseX, mouseY);
        String feedback = status;
        if (feedback.isEmpty() && document != null && preparation != null) {
            feedback = text("progress", preparation.completed(), preparation.total());
        }
        label(feedback, left + 10, top + frameHeight - (document == null ? 72 : 43), frameWidth - 20, 0xf4c585);
        drawControls();
        for (HologramMuiTextField field : fields()) {
            field.setEnabled(!busy && !placing);
            field.drawTextBox();
        }
    }

    private void drawCatalog() {
        int first = top + 29;
        for (int row = 0; row < rowCount && scroll + row < rows.size(); row++) {
            HologramClientLibrary.Entry entry = rows.get(scroll + row);
            int width = frameWidth - 20;
            nativeButton(
                CATALOG_ROW + scroll + row,
                left + 10,
                first + row * ROW_HEIGHT,
                width,
                ROW_HEIGHT - 2,
                fontRendererObj.trimStringToWidth(catalogLabel(entry), width - 10),
                !busy && !placing,
                0,
                entry.id.equals(selectedId));
        }
    }

    private static String catalogLabel(HologramClientLibrary.Entry entry) {
        String builder = entry.authorName.isEmpty() ? "" : " · " + entry.authorName;
        return entry.name + builder + " · " + text("cellCount", entry.cells);
    }

    @Override
    protected String nativeTooltip(int id, boolean enabled) {
        int row = id - CATALOG_ROW;
        return row >= 0 && row < rows.size() ? catalogLabel(rows.get(row)) : "";
    }

    private void drawDocument(int mouseX, int mouseY) {
        int px = left + 8, py = top + 29;
        drawRect(px, py, px + previewWidth, py + previewHeight, 0xff10181e);
        hovered = -1;
        if (view != null && !view.cells.isEmpty()) {
            int selected = -1;
            try {
                HologramPrivateBlueprint.Position position = position();
                HologramState.Cell cell = view.regions.at(position.x, position.y, position.z);
                if (cell != null) selected = cell.index;
            } catch (NumberFormatException invalid) {
                // A partially typed coordinate has no highlighted preview cell.
            }
            HologramSelection previousSelection = HologramRenderer.selection;
            String previousStructure = HologramRenderer.selectedStructureId;
            int[] previousMove = HologramRenderer.moveOffset;
            boolean previousEmpty = HologramRenderer.showEmptyCells;
            int previousLayer = HologramRenderer.projectionLayer;
            ViewportCoordinates.FramebufferRect scissor = new ViewportCoordinates(px, py, previewWidth, previewHeight)
                .framebufferRect(width, height, mc.displayWidth, mc.displayHeight);
            GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
            try {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(scissor.left(), scissor.bottom(), scissor.width(), scissor.height());
                HologramRenderer.selection = null;
                HologramRenderer.selectedStructureId = "";
                HologramRenderer.moveOffset = NO_MOVE;
                HologramRenderer.showEmptyCells = false;
                HologramRenderer.projectionLayer = Integer.MAX_VALUE;
                hovered = HologramRenderer.preview(
                    view,
                    px,
                    py,
                    previewWidth,
                    previewHeight,
                    yaw,
                    pitch,
                    zoom,
                    Integer.MAX_VALUE,
                    0,
                    selected,
                    mouseX,
                    mouseY,
                    -1);
            } finally {
                HologramRenderer.selection = previousSelection;
                HologramRenderer.selectedStructureId = previousStructure;
                HologramRenderer.moveOffset = previousMove;
                HologramRenderer.showEmptyCells = previousEmpty;
                HologramRenderer.projectionLayer = previousLayer;
                GL11.glPopAttrib();
            }
        }
        label(text("canvas"), px + 5, py + 5, previewWidth - 10, 0xd1dedc);
        label(text("placeholder"), px + 5, py + previewHeight - 12, previewWidth - 10, 0xd1dedc);
        label(text("coordinates"), side, top + 30, 136, 0xa9bdbc);
        label(text("block"), side, top + 61, 136, 0xa9bdbc);
        label(text("item"), side, top + 91, 136, 0xa9bdbc);
        if (top + 177 < top + frameHeight - 46) label(
            text("cellCount", document.visibleCells()) + "   " + document.author(),
            side,
            top + 175,
            136,
            0xa9bdbc);
    }

    private void label(String text, int x, int y, int width, int color) {
        if (!text.isEmpty()) fontRendererObj.drawString(fontRendererObj.trimStringToWidth(text, width), x, y, color);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (document != null && insidePreview(mouseX, mouseY)) {
            if (button == 1) {
                orbiting = true;
                dragX = mouseX;
                dragY = mouseY;
            }
            if (!busy && previewCurrent && button == 0 && hovered >= 0 && hovered < view.cells.size()) {
                HologramState.Cell cell = view.cells.get(hovered);
                select(new HologramPrivateBlueprint.Position(cell.x, cell.y, cell.z));
            }
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (!orbiting || button != 1) return;
        yaw += (mouseX - dragX) * .015;
        pitch = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, pitch + (mouseY - dragY) * .015));
        dragX = mouseX;
        dragY = mouseY;
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (button == 1) orbiting = false;
    }

    @Override
    protected boolean muiScrollBefore() {
        int wheel = Mouse.getEventDWheel();
        if (!initialized || wheel == 0) return false;
        if (document == null) {
            scroll = Math.max(0, Math.min(rows.size() - rowCount, scroll - Integer.signum(wheel)));
            return true;
        }
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (!insidePreview(mx, my)) return false;
        zoom = Math.max(.15, Math.min(30, zoom * (wheel > 0 ? 1.15 : 1 / 1.15)));
        return true;
    }

    private boolean insidePreview(int x, int y) {
        return x >= left + 8 && x < left + 8 + previewWidth && y >= top + 29 && y < top + 29 + previewHeight;
    }

    @Override
    protected boolean muiKeyBefore(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            deferAction(() -> perform(BACK));
            return true;
        }
        if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && !busy && !placing) {
            if (document == null) {
                int action = path.isFocused() ? IMPORT : OPEN;
                deferAction(() -> perform(action));
                return true;
            }
            if (x.isFocused() || y.isFocused() || z.isFocused()) {
                deferAction(() -> {
                    if (document == null || busy) return;
                    try {
                        select(position());
                    } catch (NumberFormatException invalid) {
                        status = text("integers");
                    }
                });
                return true;
            }
        }
        if (document != null && isCtrlKeyDown()) {
            if (key == Keyboard.KEY_S) {
                int action = isShiftKeyDown() ? SAVE_COPY : SAVE;
                deferAction(() -> perform(action));
                return true;
            }
            if (key == Keyboard.KEY_Z || key == Keyboard.KEY_Y) {
                int action = key == Keyboard.KEY_Y || isShiftKeyDown() ? REDO : UNDO;
                deferAction(() -> perform(action));
                return true;
            }
        }
        for (HologramMuiTextField field : fields()) if (field != null && field.isFocused()) {
            discardArmed = false;
            replacementWarning = "";
            break;
        }
        return false;
    }

    @Override
    protected void onMuiActionFailure(RuntimeException failure) {
        failed(failure);
    }

    @Override
    protected void onMuiClose() {
        closed = true;
        if (view != null) HologramRenderer.sceneClosed();
        preparation = null;
        view = null;
        Keyboard.enableRepeatEvents(false);
    }
}
