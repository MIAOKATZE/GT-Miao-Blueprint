package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.Tags;

/** Version-directed metadata migration. Never substitutes this installation's pack or other Mod versions. */
public final class HologramBlueprintUpgrade {

    private HologramBlueprintUpgrade() {}

    /** 0=current/newer/invalid, 1=optional metadata refresh, 2=pre native-file processing boundary. */
    public static int status(String version) {
        if (version == null || version.isEmpty()) return 2;
        int[] old = numbers(version), now = numbers(Tags.RELEASE_VERSION);
        if (old == null || now == null || compare(old, now) >= 0) return 0;
        return compare(old, new int[] { 0, 1, 19 }) < 0 ? 2 : 1;
    }

    static int status(JsonObject blueprint) {
        JsonElement nativeInfo = blueprint.get("nativeBlueprint");
        if (nativeInfo == null || !nativeInfo.isJsonObject()) return 2;
        JsonElement version = nativeInfo.getAsJsonObject()
            .get("gtmbVersion");
        return status(version == null || !version.isJsonPrimitive() ? "" : version.getAsString());
    }

    public static byte[] migrate(byte[] original, int limit, java.util.function.BooleanSupplier current)
        throws IOException {
        JsonObject document = HologramBlueprintCompactCodec.parse(original, limit);
        boolean changed = false;
        if (document.has("blueprint")) changed = migrate(document.getAsJsonObject("blueprint"));
        else if (document.has("presets"))
            for (JsonElement row : document.getAsJsonArray("presets")) changed |= migrate(row.getAsJsonObject());
        else throw new IOException("缺少蓝图容器");
        if (!changed) return original;
        return HologramBlueprintCompactCodec.recompress(
            document.toString()
                .getBytes(StandardCharsets.UTF_8),
            limit,
            current);
    }

    private static boolean migrate(JsonObject blueprint) throws IOException {
        if (status(blueprint) == 0) return false;
        JsonElement nativeInfo = blueprint.get("nativeBlueprint");
        JsonObject metadata;
        if (nativeInfo == null || nativeInfo.isJsonNull()) {
            metadata = new JsonObject();
            metadata.addProperty("formatVersion", HologramBlueprintVersions.FORMAT_VERSION);
            metadata.addProperty("gtnhVersion", HologramBlueprintVersions.UNKNOWN_PACK);
            metadata.addProperty("gtnhVersionSource", HologramBlueprintVersions.UNAVAILABLE_SOURCE);
            blueprint.add("nativeBlueprint", metadata);
        } else if (nativeInfo.isJsonObject()) metadata = nativeInfo.getAsJsonObject();
        else throw new IOException("旧蓝图版本信息损坏");
        metadata.addProperty("gtmbVersion", Tags.RELEASE_VERSION);
        JsonElement mods = blueprint.get("modVersions");
        if (mods != null && !mods.isJsonNull() && !mods.isJsonObject()) throw new IOException("旧蓝图 Mod 信息损坏");
        JsonObject versions = mods == null || mods.isJsonNull() ? new JsonObject() : mods.getAsJsonObject();
        versions.addProperty("gtmb", Tags.RELEASE_VERSION);
        blueprint.add("modVersions", versions);
        HologramPresets.Preset preset = new com.google.gson.Gson().fromJson(blueprint, HologramPresets.Preset.class);
        blueprint.addProperty("fullyVoucherCovered", HologramVoucherCoverage.compute(preset));
        return true;
    }

    private static int[] numbers(String version) {
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")) return null;
        try {
            String[] fields = version.split("[-+]", 2)[0].split("\\.");
            return new int[] { Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]) };
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static int compare(int[] left, int[] right) {
        for (int i = 0; i < 3; i++) if (left[i] != right[i]) return Integer.compare(left[i], right[i]);
        return 0;
    }
}
