package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

/** Strict schema-2 admission for a detached transformed draft, one cell/descriptor at a time. */
final class HologramPreparedBlueprintValidation implements AutoCloseable {

    private final HologramPresets.Preset preset;
    private final HologramBlueprintValidationCache validation;
    private final boolean receiptValidated;
    private final Set<String> selected = new HashSet<>(), structures = new HashSet<>(), positions = new HashSet<>();
    private Iterator<Map.Entry<String, String>> versions;
    private long expanded;
    private int stage, cursor;
    private HologramSchemaPageCache.Stage schema;

    HologramPreparedBlueprintValidation(HologramPresets.Preset preset) {
        this(preset, false);
    }

    HologramPreparedBlueprintValidation(HologramPresets.Preset preset, boolean receiptValidated) {
        this.preset = preset;
        this.receiptValidated = receiptValidated;
        validation = new HologramBlueprintValidationCache(preset);
    }

    boolean done() {
        return stage == 8;
    }

    long completed() {
        return cursor;
    }

    long total() {
        return stage == 5 ? preset.cells.size()
            : stage == 6 ? preset.palette.size()
                : stage == 4 ? preset.structures.size() : stage == 7 ? preset.hintAreas.size() : 1;
    }

    void advance() throws IOException {
        try {
            advanceStep();
        } catch (IOException | RuntimeException | Error failed) {
            close();
            throw failed;
        }
    }

    private void advanceStep() throws IOException {
        if (stage == 0) {
            HologramBlueprintVersions.validateIfPresent(preset);
            if (!receiptValidated) HologramBlueprintProcessing.validateReceipt(preset);
            HologramDeidentification.checked(preset.deidentificationLevel);
            if (preset.schema != 2 || preset.deidentificationVersion < 0
                || preset.deidentificationVersion > HologramDeidentification.VERSION
                || preset.aePolicyVersion < 0
                || preset.aePolicyVersion > HologramAePolicy.VERSION)
                throw new IOException("Unsupported blueprint policy version");
            if (preset.id == null || !preset.id.matches("[a-zA-Z0-9_-]{1,64}")
                || preset.name == null
                || preset.name.trim()
                    .isEmpty()
                || preset.name.length() > 48
                || preset.cells == null
                || preset.cells.isEmpty()
                || preset.cells.size() > HologramCapture.LIMIT
                || preset.palette == null
                || preset.palette.size() > 4096
                || preset.hintAreas == null
                || preset.hintAreas.size() > 256
                || preset.modVersions == null
                || preset.modVersions.size() > 512
                || preset.structures == null
                || preset.structures.size() > 256
                || preset.aeIssues == null
                || preset.aeIssues.size() > HologramAePolicy.MAX_ISSUES
                || preset.retainedNbtKeys != null && preset.retainedNbtKeys.size() > 512)
                throw new IOException("Blueprint header exceeds the strict schema budget");
            text(preset.prompt, 4096);
            text(preset.controllerType, 256);
            text(preset.sourceMod, 256);
            text(preset.sourceClass, 512);
            if (preset.controllerFacing != null
                && (preset.controllerFacing < 0 || preset.controllerFacing >= ExtendedFacing.values().length))
                throw new IOException("Invalid blueprint controller facing");
            next();
        } else if (stage == 1) {
            if (cursor < preset.aeIssues.size())
                HologramAePolicy.validateIssues(Collections.singletonList(preset.aeIssues.get(cursor++)));
            else next();
        } else if (stage == 2) {
            if (preset.retainedNbtKeys != null && cursor < preset.retainedNbtKeys.size()) {
                String key = preset.retainedNbtKeys.get(cursor++);
                if (key == null || key.isEmpty()
                    || key.length() > 256
                    || !selected.add(key)
                    || key.chars()
                        .anyMatch(value -> value < 32))
                    throw new IOException("Invalid retained NBT selection");
            } else {
                versions = preset.modVersions.entrySet()
                    .iterator();
                next();
            }
        } else if (stage == 3) {
            if (versions.hasNext()) {
                Map.Entry<String, String> entry = versions.next();
                HologramPresets.registryText(entry.getKey(), false);
                text(entry.getValue(), 256);
            } else next();
        } else if (stage == 4) {
            if (cursor < preset.structures.size()) {
                HologramPresets.Structure structure = preset.structures.get(cursor++);
                if (structure == null || structure.id == null
                    || !structure.id.matches("[a-zA-Z0-9_,:-]{1,64}")
                    || !structures.add(structure.id)
                    || structure.controllerId < -1) throw new IOException("Invalid or duplicate blueprint structure");
                text(structure.name, 256);
                text(structure.sourceMod, 256);
                text(structure.sourceClass, 512);
                if (structure.textBoxNbt != null && !structure.textBoxNbt.isEmpty()) {
                    if (!HologramTextBox.isAttachmentId(structure.id))
                        throw new IOException("Invalid attachment identity");
                    expanded += HologramBlueprintNbt.expandedBytes(structure.textBoxNbt);
                    HologramTextBox.read(HologramTextWorkflow.definition(structure));
                    expandedBudget();
                }
                if (structure.main < 1 || structure.channels == null || structure.channels.size() > 64)
                    throw new IOException("Invalid structure channels or size");
                for (Map.Entry<String, Integer> channel : structure.channels.entrySet()) if (channel.getKey() == null
                    || !channel.getKey()
                        .matches("[a-z0-9_.-]{1,48}")
                    || channel.getValue() == null
                    || channel.getValue() < 1) throw new IOException("Invalid structure channel");
                coordinates(structure.x, structure.y, structure.z);
            } else next();
        } else if (stage == 5 || stage == 6) {
            java.util.List<HologramPresets.Cell> cells = stage == 5 ? preset.cells : preset.palette;
            if (cursor < cells.size()) {
                HologramPresets.Cell cell = cells.get(cursor++);
                if (cell == null) throw new IOException("Null blueprint cell");
                expanded += (long) HologramBlueprintNbt.expandedBytes(cell.tileNbt)
                    + HologramBlueprintNbt.expandedBytes(cell.itemNbt);
                expandedBudget();
                schema.validate(cell, stage == 5, validation);
                if (stage == 5 && !positions.add(cell.x + "," + cell.y + "," + cell.z))
                    throw new IOException("Duplicate blueprint position");
                HologramPresets.validateMembership(cell, structures);
                validation.filter(cell);
            } else next();
        } else if (stage == 7) {
            if (cursor < preset.hintAreas.size()) {
                HologramPresets.HintArea area = preset.hintAreas.get(cursor++);
                if (area == null || area.x1 > area.x2 || area.y1 > area.y2 || area.z1 > area.z2)
                    throw new IOException("Invalid blueprint hint area");
                coordinates(area.x1, area.y1, area.z1);
                coordinates(area.x2, area.y2, area.z2);
                text(area.prompt, 4096);
            } else next();
        }
    }

    private void next() {
        stage++;
        cursor = 0;
        if (stage == 5) schema = HologramSchemaPageCache.begin(preset);
        if (stage == 8) {
            // Only the independent shape working set is published; it does not claim a complete manifest.
            preset.schemaPageCache = schema.finish();
            preset.segmentDocument = null;
            close();
        }
    }

    @Override
    public void close() {
        if (schema != null) schema.close();
        schema = null;
    }

    private void expandedBudget() throws IOException {
        if (expanded > 64L * 1024 * 1024) throw new IOException("Blueprint expanded NBT exceeds 64MiB");
    }

    private static void text(String value, int limit) throws IOException {
        if (value != null && value.length() > limit) throw new IOException("Blueprint text exceeds budget");
    }

    private static void coordinates(int x, int y, int z) throws IOException {
        if (Math.abs((long) x) > 60000000L || Math.abs((long) y) > 256L || Math.abs((long) z) > 60000000L)
            throw new IOException("Blueprint coordinates exceed budget");
    }
}
