// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Local saved-source facts, not placement variants, a payable bill, or an admission receipt. */
final class HologramSavedMaterialSource {

    static final int MAX_TRACKED_DESCRIPTORS = 4096;
    private static final int MAX_ID_CHARS = 192;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+");
    private static final Set<String> KNOWN_FIELDS = new HashSet<>(
        Arrays.asList(
            "block",
            "metadata",
            "item",
            "damage",
            "count",
            "facing",
            "controller",
            "excluded",
            "itemNbt",
            "tileNbt",
            "defaultItem",
            "defaultDamage",
            "prompt",
            "allowedTargets",
            "structureId",
            "structureIds",
            "structureRole",
            "structureFamily",
            "structureHint",
            "structureCandidatesComplete",
            "structureHatchFacings"));

    @Desugar
    record Variant(String block, int metadata, String item, int damage, int facing, boolean controller) {}

    @Desugar
    record Count(Variant variant, int cells) {}

    private final List<Count> variants;
    private final int airCells, payloadCells, unsupportedCells, limitCells;
    private final boolean retentionNeedsReview;
    private final long estimatedBytes;

    private HologramSavedMaterialSource(List<Count> variants, int airCells, int payloadCells, int unsupportedCells,
        int limitCells, boolean retentionNeedsReview) {
        this.variants = Collections.unmodifiableList(variants); // Owned exclusively by the snapshot.
        this.airCells = airCells;
        this.payloadCells = payloadCells;
        this.unsupportedCells = unsupportedCells;
        this.limitCells = limitCells;
        this.retentionNeedsReview = retentionNeedsReview;
        long bytes = 128L;
        for (Count row : variants) bytes += 160L + 2L * (row.variant()
            .block()
            .length()
            + row.variant()
                .item()
                .length());
        estimatedBytes = bytes;
    }

    List<Count> variants() {
        return variants;
    }

    int airCells() {
        return airCells;
    }

    int payloadCells() {
        return payloadCells;
    }

    int unsupportedCells() {
        return unsupportedCells;
    }

    int limitCells() {
        return limitCells;
    }

    boolean retentionNeedsReview() {
        return retentionNeedsReview;
    }

    long estimatedBytes() {
        return estimatedBytes;
    }

    /** Runs on the display reader's existing worker loop; a descriptor is inspected only at first use. */
    static final class Tally {

        private static final int AIR = -1, PAYLOAD = -2, UNSUPPORTED = -3, LIMIT = -4;
        private final int[] buckets, counts;
        private final Map<Variant, Integer> indices = new LinkedHashMap<>();
        private final boolean knownSchema, retentionNeedsReview;
        private int airCells, payloadCells, unsupportedCells, limitCells;

        Tally(JsonObject blueprint, int dictionarySize) {
            this(dictionarySize, integer(blueprint.get("schema"), 1, 1, 2) == 2,
                nonemptyPolicy(blueprint, "retainedNbtKeys")
                    || nonemptyPolicy(blueprint, "processingNbt")
                    || nonemptyPolicy(blueprint, "semanticNbt")
                    || nonemptyPolicy(blueprint, "quantumGroupingNbt")
                    || !optionalBoolean(blueprint.get("retainNbt"))
                    || !optionalBoolean(blueprint.get("retainCovers")));
        }

        Tally(int dictionarySize, boolean knownSchema, boolean retentionNeedsReview) {
            int capacity = Math.min(dictionarySize, MAX_TRACKED_DESCRIPTORS);
            buckets = new int[capacity];
            counts = new int[capacity];
            this.knownSchema = knownSchema;
            this.retentionNeedsReview = retentionNeedsReview;
        }

        void include(int descriptorIndex, JsonObject firstUse) {
            if (descriptorIndex >= buckets.length) {
                // Do not grow transient tables or keep another reference per cell for exceptionally wide palettes.
                limitCells++;
                return;
            }
            int bucket = buckets[descriptorIndex];
            if (bucket == 0) buckets[descriptorIndex] = bucket = classify(firstUse, false);
            count(bucket);
        }

        private void count(int bucket) {
            if (bucket == LIMIT) limitCells++;
            else if (bucket == AIR) airCells++;
            else if (bucket == PAYLOAD) payloadCells++;
            else if (bucket == UNSUPPORTED) unsupportedCells++;
            else counts[bucket - 1]++;
        }

        /** Folded into expanded-source decoding, never called when the material page opens. */
        void includeExpanded(JsonObject row) { count(classify(row, true)); }

        /** Frozen drafts already validated their scalar representation; no retained payload is decoded here. */
        void includeFrozen(String block, int metadata, String item, int damage, int facing,
            int count, boolean controller, String tileNbt, String itemNbt) {
            if (!tileNbt.isEmpty() || !itemNbt.isEmpty()) count(PAYLOAD);
            else if (!knownSchema || metadata < 0 || metadata > 15 || damage < 0 || damage > Short.MAX_VALUE
                || facing < -1 || facing > 5 || count != 1) count(UNSUPPORTED);
            else if ("minecraft:air".equals(block) && !controller
                && (item.isEmpty() || "minecraft:air".equals(item))) count(AIR);
            else if (!validRegistry(block) || !validRegistry(item) || "minecraft:air".equals(block)
                || "minecraft:air".equals(item)) count(UNSUPPORTED);
            else count(variant(new Variant(block, metadata, item, damage, facing, controller)));
        }

        HologramSavedMaterialSource snapshot() {
            List<Count> result = new ArrayList<>(indices.size());
            for (Map.Entry<Variant, Integer> row : indices.entrySet())
                result.add(new Count(row.getKey(), counts[row.getValue() - 1]));
            return new HologramSavedMaterialSource(
                result,
                airCells,
                payloadCells,
                unsupportedCells,
                limitCells,
                retentionNeedsReview);
        }

        private int classify(JsonObject row, boolean expanded) {
            if (row == null) return UNSUPPORTED;
            // The strings may be resolved dictionary payloads. Their contents are deliberately not read or retained.
            if (!emptyString(row.get("tileNbt")) || !emptyString(row.get("itemNbt"))) return PAYLOAD;
            JsonElement excluded = row.get("excluded");
            JsonElement controllerField = row.get("controller");
            if (!knownSchema || !optionalBoolean(excluded) || !optionalBoolean(controllerField)) return UNSUPPORTED;
            for (Map.Entry<String, JsonElement> field : row.entrySet())
                if (!KNOWN_FIELDS.contains(field.getKey()) && !(expanded
                    && ("x".equals(field.getKey()) || "y".equals(field.getKey()) || "z".equals(field.getKey()))))
                    return UNSUPPORTED;
            String block = registry(row.get("block")), item = registry(row.get("item"));
            boolean controller = present(controllerField) && controllerField.getAsBoolean();
            int metadata = integer(row.get("metadata"), 0, 0, 15);
            int damage = integer(row.get("damage"), 0, 0, Short.MAX_VALUE);
            int facing = integer(row.get("facing"), -1, -1, 5);
            int count = integer(row.get("count"), 1, 1, 1);
            // Compact storage validates references and positions, not the other descriptor scalars.
            if (metadata < 0 || damage < 0 || facing < -1 || count != 1) return UNSUPPORTED;
            if ("minecraft:air".equals(block) && !controller
                && (!present(row.get("item")) || "minecraft:air".equals(item))) return AIR;
            if (block == null || item == null || "minecraft:air".equals(block) || "minecraft:air".equals(item))
                return UNSUPPORTED;
            return variant(new Variant(block, metadata, item, damage, facing, controller));
        }

        private int variant(Variant variant) {
            Integer known = indices.get(variant);
            if (known != null) return known;
            if (indices.size() >= counts.length) return LIMIT;
            int bucket = indices.size() + 1;
            indices.put(variant, bucket);
            return bucket;
        }
    }

    private static boolean present(JsonElement value) {
        return value != null && !value.isJsonNull();
    }

    private static boolean optionalBoolean(JsonElement value) {
        return !present(value) || value.isJsonPrimitive() && value.getAsJsonPrimitive()
            .isBoolean();
    }

    private static boolean emptyString(JsonElement value) {
        return !present(value) || value.isJsonPrimitive() && value.getAsJsonPrimitive()
            .isString()
            && value.getAsString()
                .isEmpty();
    }

    private static boolean nonemptyPolicy(JsonObject blueprint, String key) {
        JsonElement value = blueprint.get(key);
        if ("retainedNbtKeys".equals(key)) return present(value); // Empty selection is also meaningful.
        return !emptyString(value);
    }

    private static String registry(JsonElement value) {
        if (!present(value) || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isString())
            return null;
        String id = value.getAsString();
        return validRegistry(id) ? id : null;
    }

    private static boolean validRegistry(String id) {
        return id != null && id.length() <= MAX_ID_CHARS && ID.matcher(id).matches();
    }

    private static int integer(JsonElement value, int absent, int min, int max) {
        if (!present(value)) return absent;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
            .isNumber()) return Integer.MIN_VALUE;
        String raw = value.getAsString();
        if ("-1".equals(raw) && min == -1) return -1;
        if (raw.isEmpty() || raw.length() > 5 || raw.length() > 1 && raw.charAt(0) == '0') return Integer.MIN_VALUE;
        int result = 0;
        for (int i = 0; i < raw.length(); i++) {
            char digit = raw.charAt(i);
            if (digit < '0' || digit > '9') return Integer.MIN_VALUE;
            result = result * 10 + digit - '0';
        }
        return result >= min && result <= max ? result : Integer.MIN_VALUE;
    }
}
