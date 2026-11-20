// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramBlueprintLimits;
import com.miaokatze.gtmb.hologram.HologramBlueprintPreviewBinding;
import com.miaokatze.gtmb.hologram.HologramLibraryFacade;

/** Client-owned document. Preview rows are disposable and never become the source for a save. */
final class HologramPrivateBlueprint {

    private static final int HISTORY_LIMIT = 32;

    static final class Position {

        final int x, y, z;

        Position(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean inBounds() {
            return Math.abs((long) x) <= 60_000_000 && Math.abs((long) y) <= 256 && Math.abs((long) z) <= 60_000_000;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Position position && x == position.x && y == position.y && z == position.z;
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }
    }

    private static final class Change {

        final Position position;
        final JsonObject before, after;
        final long previousRevision, nextRevision;

        Change(Position position, JsonObject before, JsonObject after, long previousRevision, long nextRevision) {
            this.position = position;
            this.before = before;
            this.after = after;
            this.previousRevision = previousRevision;
            this.nextRevision = nextRevision;
        }
    }

    private final JsonObject root, blueprint;
    // A removed position keeps a null slot so undo also restores its original file order.
    private final Map<Position, JsonObject> cells = new LinkedHashMap<>();
    private final ArrayDeque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private long revision, nextRevision, savedRevision;
    private int visibleCells;
    private int recordedCells;

    HologramPrivateBlueprint(byte[] bytes) throws IOException {
        root = HologramBlueprintCompactCodec.parse(HologramBlueprintPreviewBinding.sourceJson(bytes));
        JsonElement content = root.get("blueprint");
        if (content == null || !content.isJsonObject()) throw new IOException("请选择单个已导出的 GTMB 蓝图 JSON");
        blueprint = content.getAsJsonObject();
        JsonElement rows = blueprint.remove("cells");
        if (rows == null || !rows.isJsonArray()) throw new IOException("蓝图缺少方块列表");
        try {
            for (JsonElement element : rows.getAsJsonArray()) {
                JsonObject cell = element.getAsJsonObject();
                Position position = new Position(integer(cell, "x"), integer(cell, "y"), integer(cell, "z"));
                if (!position.inBounds()) throw new IOException("蓝图坐标超出范围");
                if (cells.putIfAbsent(position, cell) != null) throw new IOException("蓝图包含重复坐标");
                if (visible(cell)) visibleCells++;
            }
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图方块记录无效", invalid);
        }
        recordedCells = cells.size();
    }

    private static int integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? 0
            : value.getAsBigDecimal()
                .intValueExact();
    }

    static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    String name() {
        return text(blueprint, "name");
    }

    String author() {
        return text(blueprint, "authorName");
    }

    JsonObject cell(Position position) {
        return cells.get(position);
    }

    boolean dirty() {
        return revision != savedRevision;
    }

    void saved() {
        savedRevision = revision;
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    int visibleCells() {
        return visibleCells;
    }

    boolean upsert(Position position, String block, int metadata, String item, int damage) throws IOException {
        if (!position.inBounds()) throw new IOException("蓝图坐标超出范围");
        JsonObject old = cells.get(position);
        if (old == null && !HologramBlueprintLimits.accepts(recordedCells + 1L)) throw new IOException("蓝图已达到实际记录格数上限");
        boolean changedMaterial = old == null || !block.equals(text(old, "block"))
            || !item.equals(text(old, "item"))
            || metadata != integer(old, "metadata")
            || damage != integer(old, "damage");
        if (!changedMaterial && visible(old)) return false;
        JsonObject updated = new JsonObject();
        if (old != null)
            for (Map.Entry<String, JsonElement> field : old.entrySet()) updated.add(field.getKey(), field.getValue());
        updated.addProperty("x", position.x);
        updated.addProperty("y", position.y);
        updated.addProperty("z", position.z);
        if (changedMaterial) {
            // Old device payloads cannot describe a different material. Undo retains the original row.
            updated.remove("tileNbt");
            updated.remove("itemNbt");
            updated.remove("facing");
        }
        updated.addProperty("block", block);
        updated.addProperty("metadata", metadata);
        if (item.isEmpty()) {
            updated.remove("item");
            updated.remove("damage");
        } else {
            updated.addProperty("item", item);
            updated.addProperty("damage", damage);
        }
        updated.remove("excluded");
        return change(position, old, updated);
    }

    boolean remove(Position position) {
        return change(position, cells.get(position), null);
    }

    Position undo(boolean backwards) {
        ArrayDeque<Change> from = backwards ? undo : redo;
        if (from.isEmpty()) return null;
        Change change = from.removeLast();
        set(change.position, backwards ? change.before : change.after);
        (backwards ? redo : undo).addLast(change);
        revision = backwards ? change.previousRevision : change.nextRevision;
        return change.position;
    }

    private boolean change(Position position, JsonObject before, JsonObject after) {
        if (java.util.Objects.equals(before, after)) return false;
        ArrayList<Change> retired = new ArrayList<>(redo);
        if (undo.size() >= HISTORY_LIMIT) retired.add(undo.removeFirst());
        undo.addLast(new Change(position, before, after, revision, ++nextRevision));
        revision = nextRevision;
        redo.clear();
        set(position, after);
        for (Change dropped : retired) {
            if (cells.get(dropped.position) == null && undo.stream()
                .noneMatch(change -> change.position.equals(dropped.position))) cells.remove(dropped.position);
        }
        return true;
    }

    private void set(Position position, JsonObject cell) {
        JsonObject before = cells.put(position, cell);
        if (before != null) recordedCells--;
        if (cell != null) recordedCells++;
        if (visible(before)) visibleCells--;
        if (visible(cell)) visibleCells++;
    }

    private static boolean visible(JsonObject cell) {
        return cell != null && !"true".equals(text(cell, "excluded"));
    }

    private JsonArray rows() {
        JsonArray rows = new JsonArray();
        for (JsonObject cell : cells.values()) if (cell != null) rows.add(cell);
        return rows;
    }

    NBTTagCompound snapshot(String session) throws IOException {
        NBTTagCompound snapshot = HologramBlueprintPreview.privateSnapshot(blueprint, rows());
        snapshot.setString("session", session);
        snapshot.setLong("planRevision", revision);
        return snapshot;
    }

    /** Source geometry thumbnail, independent of the display renderer and unknown resource registrations. */
    BufferedImage thumbnail(double yaw, double pitch) {
        int size = HologramBlueprintArtifact.WIDTH;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster()
            .getDataBuffer()).getData();
        Arrays.fill(pixels, 0x14232b);
        if (visibleCells > 0) {
            double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
            double top = Double.POSITIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
            double cosYaw = Math.cos(yaw), sinYaw = Math.sin(yaw);
            double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
            for (Map.Entry<Position, JsonObject> row : cells.entrySet()) {
                if (!visible(row.getValue())) continue;
                Position p = row.getKey();
                double x = p.x * cosYaw + p.z * sinYaw;
                double z = p.z * cosYaw - p.x * sinYaw;
                double y = p.y * cosPitch + z * sinPitch;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
            double scale = Math.min(16, (size - 96) / Math.max(1, Math.max(right - left, bottom - top)));
            int radius = Math.max(1, (int) Math.round(scale * .65));
            double centerX = (left + right) / 2, centerY = (top + bottom) / 2;
            double[] depth = new double[pixels.length];
            Arrays.fill(depth, Double.NEGATIVE_INFINITY);
            for (Map.Entry<Position, JsonObject> row : cells.entrySet()) {
                if (!visible(row.getValue())) continue;
                Position p = row.getKey();
                double x = p.x * cosYaw + p.z * sinYaw;
                double z = p.z * cosYaw - p.x * sinYaw;
                double y = p.y * cosPitch + z * sinPitch;
                double cameraDepth = z * cosPitch - p.y * sinPitch;
                int px = (int) Math.round(size / 2d + (x - centerX) * scale);
                int py = (int) Math.round(size / 2d - (y - centerY) * scale);
                for (int by = py - radius; by <= py + radius; by++)
                    for (int bx = px - radius; bx <= px + radius; bx++) {
                        int index = by * size + bx;
                        if (cameraDepth < depth[index]) continue;
                        depth[index] = cameraDepth;
                        pixels[index] = bx == px - radius || by == py + radius ? 0x46867f : 0x90cdc6;
                    }
            }
        }
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0xaac1c1));
            graphics.drawString("BLUEPRINT GEOMETRY   /   " + visibleCells + " CELLS", 20, size - 20);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    byte[] saveBytes() throws IOException {
        JsonObject currentRoot = new JsonObject();
        for (Map.Entry<String, JsonElement> field : root.entrySet()) currentRoot.add(field.getKey(), field.getValue());
        JsonObject currentBlueprint = new JsonObject();
        for (Map.Entry<String, JsonElement> field : blueprint.entrySet())
            currentBlueprint.add(field.getKey(), field.getValue());
        if (revision != 0) {
            currentBlueprint.remove("fullyVoucherCovered");
            String receipt = text(currentBlueprint, "processingNbt");
            if (!receipt.isEmpty()) currentBlueprint
                .addProperty("processingNbt", HologramLibraryFacade.invalidatePrivateGeometryReceipt(receipt));
        }
        currentBlueprint.add("cells", rows());
        currentRoot.add("blueprint", currentBlueprint);
        // Imported documents can contain unregistered assets and legacy metadata. The common codec only checks
        // portable structure here; server construction validation runs on submission to the server.
        byte[] bytes = currentRoot.toString()
            .getBytes(StandardCharsets.UTF_8);
        return HologramBlueprintCompactCodec.recompress(bytes, HologramBlueprintArtifact.MAX_JSON_BYTES);
    }
}
