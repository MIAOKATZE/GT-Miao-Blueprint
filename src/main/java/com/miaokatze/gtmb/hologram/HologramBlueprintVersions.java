package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.Tags;

/** Native file metadata; independent of the internal schema used by controller templates. */
final class HologramBlueprintVersions {

    static final int FORMAT_VERSION = 1;
    static final String PACK_SOURCE = "dreamcoremod.displayedModpackVersion";
    static final String UNAVAILABLE_SOURCE = "unavailable";
    static final String UNKNOWN_PACK = "unknown";
    private static final String COREMOD_CLASS = "com.dreammaster.coremod.DreamCoreMod";
    private static final String SEMVER_NUMBER = "(?:0|[1-9][0-9]*)";
    private static final String SEMVER_PRERELEASE = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)";
    private static final Pattern SEMVER = Pattern.compile(
        SEMVER_NUMBER + "\\."
            + SEMVER_NUMBER
            + "\\."
            + SEMVER_NUMBER
            + "(?:-"
            + SEMVER_PRERELEASE
            + "(?:\\."
            + SEMVER_PRERELEASE
            + ")*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private static final Pattern EXACT_PACK = Pattern
        .compile("[0-9]+(?:\\.[0-9]+){2,3}(?:[-+ ][0-9A-Za-z][0-9A-Za-z._+ -]*)?");

    private HologramBlueprintVersions() {}

    /** No defaults: deserializing an older file must never manufacture its missing provenance. */
    static final class Metadata {

        int formatVersion;
        String gtmbVersion, gtnhVersion, gtnhVersionSource;
    }

    /** Legitimate in-memory templates acquire metadata only at a native creation/export boundary. */
    static void stamp(HologramPresets.Preset preset) throws IOException {
        if (preset == null) throw new IOException("Blueprint version metadata requires a blueprint");
        if (preset.nativeBlueprint == null) stampSaved(preset);
        else validate(preset);
    }

    /** Author saves record this environment; transfers and imports must preserve the received metadata. */
    static void stampSaved(HologramPresets.Preset preset) throws IOException {
        if (preset == null) throw new IOException("Blueprint version metadata requires a blueprint");
        Metadata current = current();
        validate(current);
        preset.nativeBlueprint = current;
    }

    static Metadata current() {
        String pack = null;
        try {
            Class<?> coremod = Class.forName(COREMOD_CLASS, false, HologramBlueprintVersions.class.getClassLoader());
            Field version = coremod.getField("displayedModpackVersion");
            if (version.getType() == String.class && Modifier.isStatic(version.getModifiers())) {
                pack = (String) version.get(null);
            }
        } catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) {
            // A partial development fixture can omit GTNHCore; it must not claim a guessed pack version.
        }
        return current(pack);
    }

    static Metadata current(String displayedPackVersion) {
        Metadata result = new Metadata();
        result.formatVersion = FORMAT_VERSION;
        result.gtmbVersion = Tags.RELEASE_VERSION;
        if (isExactPack(displayedPackVersion)) {
            result.gtnhVersion = displayedPackVersion;
            result.gtnhVersionSource = PACK_SOURCE;
        } else {
            result.gtnhVersion = UNKNOWN_PACK;
            result.gtnhVersionSource = UNAVAILABLE_SOURCE;
        }
        return result;
    }

    static void validateIfPresent(HologramPresets.Preset preset) throws IOException {
        if (preset != null && preset.nativeBlueprint != null) validate(preset);
    }

    static void validate(HologramPresets.Preset preset) throws IOException {
        if (preset == null) throw new IOException("Missing native blueprint version metadata");
        validate(preset.nativeBlueprint);
    }

    /** Validate the original JSON before Gson can coerce field types or apply model initializers. */
    static void validateJson(JsonElement presetJson) throws IOException {
        if (presetJson == null || !presetJson.isJsonObject())
            throw new IOException("Missing native blueprint version metadata");
        JsonElement nativeJson = presetJson.getAsJsonObject()
            .get("nativeBlueprint");
        if (nativeJson == null || !nativeJson.isJsonObject())
            throw new IOException("Unsupported old blueprint file: missing nativeBlueprint metadata");
        JsonObject object = nativeJson.getAsJsonObject();
        JsonElement format = object.get("formatVersion");
        if (format == null || !format.isJsonPrimitive()
            || !format.getAsJsonPrimitive()
                .isNumber()
            || !Integer.toString(FORMAT_VERSION)
                .equals(format.getAsString()))
            throw new IOException("Unsupported native blueprint formatVersion");
        Metadata metadata = new Metadata();
        metadata.formatVersion = FORMAT_VERSION;
        metadata.gtmbVersion = string(object, "gtmbVersion");
        metadata.gtnhVersion = string(object, "gtnhVersion");
        metadata.gtnhVersionSource = string(object, "gtnhVersionSource");
        validate(metadata);
    }

    private static String string(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isString())
            throw new IOException("Native blueprint metadata " + key + " must be a string");
        return value.getAsString();
    }

    private static void validate(Metadata metadata) throws IOException {
        if (metadata == null) throw new IOException("Missing native blueprint version metadata");
        if (metadata.formatVersion != FORMAT_VERSION)
            throw new IOException("Unsupported native blueprint formatVersion");
        if (metadata.gtmbVersion == null || metadata.gtmbVersion.length() > 128
            || !SEMVER.matcher(metadata.gtmbVersion)
                .matches())
            throw new IOException("Native blueprint GTMB version must be a semantic release version");
        if (UNAVAILABLE_SOURCE.equals(metadata.gtnhVersionSource)) {
            if (!UNKNOWN_PACK.equals(metadata.gtnhVersion))
                throw new IOException("Unavailable GTNH version must be marked unknown");
        } else if (!PACK_SOURCE.equals(metadata.gtnhVersionSource) || !isExactPack(metadata.gtnhVersion)) {
            throw new IOException("Native blueprint GTNH version or detection source is invalid");
        }
    }

    private static boolean isExactPack(String version) {
        return version != null && version.length() <= 128
            && EXACT_PACK.matcher(version)
                .matches();
    }
}
