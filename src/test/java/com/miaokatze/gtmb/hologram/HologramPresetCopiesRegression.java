package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Independent old-Gson oracle plus mutable ownership and complete serialized-field coverage. */
public final class HologramPresetCopiesRegression {

    private static final Gson ORACLE = new GsonBuilder().create();

    public static void run() {
        fieldCoverage();
        oracleAndIsolation();
        nullDefaults();
        largeEncodedValues();
        immutableManifests();
        boundedCopy();
    }

    private static void boundedCopy() {
        HologramPresets.Preset source = fixture();
        for (int i = 0; i < 2048; i++) source.cells.add(source.cells.get(0));
        HologramPresetCopies.Task task = new HologramPresetCopies.Task(source);
        SimpleAssert.eq(0, task.result.cells.size(), "constructing copy continuation never copies the cell tree");
        SimpleAssert.that(!task.step(0, Long.MAX_VALUE), "zero work does not copy records");
        SimpleAssert.that(!task.step(100, 0), "zero time does not copy records");
        SimpleAssert.eq(0L, task.completed(), "no-budget copy stays at its original cursor");
        int slices = 0;
        while (true) {
            long before = task.completed();
            boolean done = task.step(7, Long.MAX_VALUE);
            SimpleAssert.that(task.completed() - before <= 7, "every list/map record shares the operation cap");
            slices++;
            if (done) break;
            SimpleAssert.that(slices < 1000, "bounded copy terminates");
        }
        SimpleAssert.that(slices > 200, "large copy genuinely yields rather than copying ahead of its cursor");
        SimpleAssert.eq(task.total(), task.completed(), "all DTO records were consumed");
        SimpleAssert.eq(
            ORACLE.toJsonTree(source),
            ORACLE.toJsonTree(task.result),
            "sliced DTO copy preserves complete serialized input");
        SimpleAssert.that(task.step(1, 1), "completed copy is idempotently complete");
        task.result.cells.get(17).allowedTargets.get(0).damage++;
        SimpleAssert.that(
            source.cells.get(17).allowedTargets.get(0).damage != task.result.cells.get(17).allowedTargets.get(0).damage,
            "sliced copy does not share mutable nested ownership");
    }

    private static void fieldCoverage() {
        fields(
            HologramPresets.Preset.class,
            "id name controllerType authorName authorUuid createdAt nativeBlueprint "
                + "fullyVoucherCovered schema aePolicyVersion deidentificationVersion deidentificationLevel "
                + "deidentificationChosen processingNbt quantumGroupingNbt aeIssues retainNbt retainCovers prompt "
                + "sourceMod sourceClass modVersions hintAreas structures retainedNbtKeys controllerId controllerFacing cells palette");
        fields(
            HologramPresets.Cell.class,
            "item defaultItem block tileNbt itemNbt prompt structureId structureIds "
                + "allowedTargets metadata excluded defaultDamage damage count x y z facing controller");
        fields(HologramPresets.Target.class, "item damage");
        fields(HologramPresets.HintArea.class, "x1 y1 z1 x2 y2 z2 prompt");
        fields(
            HologramPresets.Structure.class,
            "id name sourceMod sourceClass textBoxNbt x y z controllerId enabled "
                + "main mainExplicit noHatches channels");
        fields(HologramBlueprintVersions.Metadata.class, "formatVersion gtmbVersion gtnhVersion gtnhVersionSource");
        fields(HologramAePolicy.Issue.class, "code severity path message x y z count");
    }

    private static void fields(Class<?> type, String covered) {
        Set<String> actual = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())
                && !field.isSynthetic()) actual.add(field.getName());
        }
        SimpleAssert.eq(
            new HashSet<>(Arrays.asList(covered.split(" "))),
            actual,
            type.getSimpleName()
                + " copier covers every serialized field; new fields need explicit copy/oracle coverage");
    }

    private static void oracleAndIsolation() {
        HologramPresets.Preset source = fixture();
        HologramPresets.Preset expected = ORACLE.fromJson(ORACLE.toJson(source), HologramPresets.Preset.class);
        HologramPresets.Preset actual = HologramPresets.copy(source);
        SimpleAssert.eq(
            ORACLE.toJsonTree(expected),
            ORACLE.toJsonTree(actual),
            "explicit copier preserves every serialized value from independent old-Gson oracle");
        SimpleAssert.that(
            source != actual && source.cells != actual.cells && source.palette != actual.palette,
            "root and both flat DTO lists belong to their new owner");
        HologramPresets.Cell before = source.cells.get(0), after = actual.cells.get(0);
        SimpleAssert.that(
            before != after && before != actual.palette.get(0) && after != actual.palette.get(0),
            "aliased input occurrences become independent mutable cells");
        SimpleAssert.that(
            before.allowedTargets != after.allowedTargets && before.allowedTargets.get(0) != after.allowedTargets.get(0)
                && before.structureIds != after.structureIds,
            "nested cell lists and target DTOs are independent");
        SimpleAssert.that(
            source.nativeBlueprint != actual.nativeBlueprint && source.aeIssues != actual.aeIssues
                && source.aeIssues.get(0) != actual.aeIssues.get(0),
            "metadata and AE issue DTOs are independently mutable");
        SimpleAssert.that(
            source.structures != actual.structures && source.structures.get(0) != actual.structures.get(0)
                && source.structures.get(0).channels != actual.structures.get(0).channels,
            "structures and channel maps are independently mutable");
        SimpleAssert.that(
            source.hintAreas != actual.hintAreas && source.hintAreas.get(0) != actual.hintAreas.get(0)
                && source.modVersions != actual.modVersions
                && source.retainedNbtKeys != actual.retainedNbtKeys,
            "all remaining mutable containers are detached");
        SimpleAssert.eq(before.segmentCellId, after.segmentCellId, "stable CellId survives an explicit copy");
        SimpleAssert.that(
            !after.frozenBlockResolved && after.frozenBlock == null
                && after.metadataIdentityBoundary == null
                && after.frozenSensorSlots == null
                && after.frozenNbtDescriptor == null
                && after.frozenDescriptorOwner == null,
            "provider/snapshot proof is not silently inherited into a different mutable DTO scope");
        String originalJson = ORACLE.toJson(source);
        after.item = "changed:item";
        after.allowedTargets.get(0).damage++;
        after.structureIds.add("changed");
        actual.palette.get(0).block = "changed:block";
        actual.nativeBlueprint.gtmbVersion = "changed";
        actual.aeIssues.get(0).count++;
        actual.structures.get(0).channels.put("changed", 12);
        actual.hintAreas.get(0).prompt = "changed";
        actual.modVersions.put("changed", "value");
        actual.retainedNbtKeys.clear();
        SimpleAssert.eq(originalJson, ORACLE.toJson(source), "all nested writes leave the source unchanged");
        SimpleAssert.eq(
            expected.modVersions,
            HologramPresets.copy(source).modVersions,
            "map normalization matches old Gson values as well as its serialized projection");
    }

    private static void nullDefaults() {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.cells = null;
        source.palette = null;
        source.structures = null;
        source.hintAreas = null;
        source.modVersions = null;
        source.aeIssues = null;
        HologramPresets.Preset expected = ORACLE.fromJson(ORACLE.toJson(source), HologramPresets.Preset.class);
        HologramPresets.Preset actual = HologramPresets.copy(source);
        SimpleAssert.eq(
            ORACLE.toJsonTree(expected),
            ORACLE.toJsonTree(actual),
            "omitted root null containers preserve old Gson constructor defaults");
        SimpleAssert.that(
            actual.retainedNbtKeys == null && actual.nativeBlueprint == null
                && actual.fullyVoucherCovered == null
                && actual.controllerFacing == null,
            "nullable policy and metadata fields remain absent");
        SimpleAssert.that(HologramPresets.copy(null) == null, "null input retains old Gson null behavior");
        HologramPresets.Structure structure = new HologramPresets.Structure();
        structure.channels = null;
        source.structures = new ArrayList<>(Arrays.asList(structure));
        HologramPresets.Cell cell = new HologramPresets.Cell();
        source.cells = new ArrayList<>(Arrays.asList(cell, null));
        actual = HologramPresets.copy(source);
        SimpleAssert.that(actual.structures.get(0).channels.isEmpty(), "omitted channel map gets constructor default");
        SimpleAssert.that(
            actual.cells.get(0).allowedTargets == null && actual.cells.get(0).structureIds == null
                && actual.cells.get(1) == null,
            "nullable nested lists and explicit null list entries are retained");
    }

    private static void largeEncodedValues() {
        char[] characters = new char[8192];
        Arrays.fill(characters, 'q');
        String encoded = new String(characters);
        HologramPresets.Preset source = new HologramPresets.Preset();
        for (int index = 0; index < 10000; index++) {
            HologramPresets.Cell cell = new HologramPresets.Cell();
            cell.tileNbt = encoded;
            cell.itemNbt = encoded;
            cell.segmentCellId = index + 1;
            source.cells.add(cell);
        }
        HologramPresets.Preset copied = HologramPresets.copy(source);
        SimpleAssert.eq(10000, copied.cells.size(), "large legacy DTO projection preserves complete cell count");
        for (int index : new int[] { 0, 255, 256, 9999 }) {
            SimpleAssert.that(
                source.cells.get(index) != copied.cells.get(index) && copied.cells.get(index).tileNbt == encoded
                    && copied.cells.get(index).itemNbt == encoded,
                "large immutable encodings are shared without decoding or constructing a full JSON duplicate");
        }
        copied.cells.get(0).tileNbt = "changed";
        SimpleAssert.that(
            source.cells.get(0).tileNbt == encoded && copied.cells.get(1).tileNbt == encoded,
            "replacing one shared immutable value cannot contaminate another cell");
    }

    private static void immutableManifests() {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.schema = 2;
        source.id = "copy-manifest";
        source.name = "Copy manifest";
        HologramPresets.Cell cell = new HologramPresets.Cell();
        cell.item = "minecraft:stone";
        cell.block = "minecraft:stone";
        source.cells.add(cell);
        try {
            HologramPresets.validate(source);
            new HologramAnalysisDocument.Stage(source, true).publish();
            HologramPresets.Preset copied = HologramPresets.copy(source);
            SimpleAssert.that(
                source.segmentDocument != null && source.segmentDocument == copied.segmentDocument
                    && source.analysisDocument != null
                    && source.analysisDocument == copied.analysisDocument,
                "immutable manifests retain their authority without duplicating a second document graph");
            SimpleAssert
                .that(source.cells.get(0) != copied.cells.get(0), "manifest inheritance never shares mutable Cells");
            HologramPresets.validate(copied);
            SimpleAssert
                .eq(0L, copied.segmentDocument.schemaChecks, "copied stable ids bind the original page certificate");
        } catch (IOException failure) {
            throw new AssertionError("copy manifest fixture rejected", failure);
        }
    }

    private static HologramPresets.Preset fixture() {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.id = "copy-fixture";
        source.name = "copy<&>\"中文";
        source.controllerType = "type";
        source.authorName = "author";
        source.authorUuid = "uuid";
        source.createdAt = Long.MAX_VALUE - 33;
        source.nativeBlueprint = new HologramBlueprintVersions.Metadata();
        source.nativeBlueprint.formatVersion = 7;
        source.nativeBlueprint.gtmbVersion = "1.2.3";
        source.nativeBlueprint.gtnhVersion = "2.9.0";
        source.nativeBlueprint.gtnhVersionSource = "test";
        source.fullyVoucherCovered = true;
        source.schema = 2;
        source.aePolicyVersion = 8;
        source.deidentificationVersion = 9;
        source.deidentificationLevel = 10;
        source.deidentificationChosen = true;
        source.processingNbt = "opaque processing";
        source.quantumGroupingNbt = "opaque groups";
        HologramAePolicy.Issue issue = new HologramAePolicy.Issue();
        issue.code = "code";
        issue.severity = "severity";
        issue.path = "path";
        issue.message = "message";
        issue.x = 11;
        issue.y = 12;
        issue.z = 13;
        issue.count = Long.MAX_VALUE - 2;
        source.aeIssues.add(issue);
        source.aeIssues.add(null);
        source.retainNbt = false;
        source.retainCovers = false;
        source.prompt = "prompt";
        source.sourceMod = "mod";
        source.sourceClass = "class";
        source.modVersions.put("first", "1");
        source.modVersions.put("omit", null);
        source.modVersions.put(null, "null-key");
        HologramPresets.HintArea area = new HologramPresets.HintArea();
        area.x1 = 14;
        area.y1 = 15;
        area.z1 = 16;
        area.x2 = 17;
        area.y2 = 18;
        area.z2 = 19;
        area.prompt = "area";
        source.hintAreas.add(area);
        source.hintAreas.add(null);
        HologramPresets.Structure structure = new HologramPresets.Structure();
        structure.id = "structure";
        structure.name = "name";
        structure.sourceMod = "structure-mod";
        structure.sourceClass = "structure-class";
        structure.textBoxNbt = "opaque attachment";
        structure.x = 20;
        structure.y = 21;
        structure.z = 22;
        structure.controllerId = 23;
        structure.enabled = false;
        structure.main = 24;
        structure.mainExplicit = true;
        structure.noHatches = true;
        structure.channels = new LinkedHashMap<>();
        structure.channels.put("first", 25);
        structure.channels.put("omit", null);
        source.structures.add(structure);
        source.structures.add(null);
        source.retainedNbtKeys = new ArrayList<>(Arrays.asList("Items", null, ""));
        source.controllerId = 26;
        source.controllerFacing = 27;
        HologramPresets.Cell cell = new HologramPresets.Cell();
        cell.item = "test:item";
        cell.defaultItem = "test:default";
        cell.block = "test:block";
        cell.tileNbt = "opaque tile";
        cell.itemNbt = "opaque item";
        cell.prompt = "cell";
        cell.structureId = "structure";
        cell.structureIds = new ArrayList<>(Arrays.asList("structure", null));
        HologramPresets.Target target = new HologramPresets.Target();
        target.item = "test:target";
        target.damage = 28;
        cell.allowedTargets = new ArrayList<>(Arrays.asList(target, null));
        cell.metadata = 29;
        cell.excluded = true;
        cell.defaultDamage = 30;
        cell.damage = 31;
        cell.count = 32;
        cell.x = 33;
        cell.y = 34;
        cell.z = 35;
        cell.facing = 4;
        cell.controller = true;
        cell.segmentCellId = 1234;
        cell.frozenBlockResolved = true;
        cell.metadataIdentityBoundary = true;
        cell.frozenSensorSlots = new HashSet<>(Arrays.asList(1, 2));
        cell.frozenDescriptorOwner = new Object();
        source.cells.add(cell);
        source.cells.add(null);
        source.palette.add(cell);
        return source;
    }

    private HologramPresetCopiesRegression() {}
}
