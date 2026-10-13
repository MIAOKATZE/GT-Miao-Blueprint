package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParser;

/** Independent share files; client supplied names never become arbitrary filesystem paths. */
final class HologramBlueprintSharing {

    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Path DIRECTORY = Paths.get("config", "gtmb", "hologram-blueprints");
    private static final Gson GSON = new GsonBuilder().create();

    private HologramBlueprintSharing() {}

    private static final class Share {

        int schema = 2;
        HologramPresets.Preset blueprint;
    }

    static void exportFile(String name, HologramPresets.Preset preset) throws IOException {
        write(resolve(name), preset);
    }

    static HologramPresets.Preset importFile(String name) throws IOException {
        return read(resolve(name));
    }

    static Path resolve(String name) throws IOException {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")) throw new IOException("蓝图文件名只能包含字母、数字、下划线和短横线");
        return DIRECTORY.resolve(name + ".json");
    }

    static void write(Path file, HologramPresets.Preset preset) throws IOException {
        HologramPresets.Preset copy = HologramPresets.copy(preset);
        HologramPresets.validate(copy);
        Share share = new Share();
        share.blueprint = copy;
        byte[] bytes = encodeBounded(GSON, share, MAX_BYTES);
        atomicWrite(file, bytes);
    }

    /** Stop serialization at the byte budget instead of allocating an unbounded JSON string first. */
    static byte[] encodeBounded(Gson gson, Object value, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer output = new OutputStreamWriter(new FilterOutputStream(bytes) {

            private int written;

            @Override
            public void write(int value) throws IOException {
                if (written >= limit) throw new IOException("蓝图JSON超过 " + limit / (1024 * 1024) + " MiB 上限");
                out.write(value);
                written++;
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                if (length > limit - written) throw new IOException("蓝图JSON超过 " + limit / (1024 * 1024) + " MiB 上限");
                out.write(buffer, offset, length);
                written += length;
            }
        }, StandardCharsets.UTF_8)) {
            gson.toJson(value, output);
        } catch (JsonIOException failure) {
            throw new IOException("蓝图JSON写入失败：" + failure.getMessage(), failure);
        }
        return bytes.toByteArray();
    }

    static void atomicWrite(Path file, byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("蓝图分享文件超过8MiB上限");
        Path parent = file.toAbsolutePath()
            .getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "hologram-blueprint-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files
                .move(temp, file.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static HologramPresets.Preset read(Path file) throws IOException {
        return decode(new String(readBytes(file), StandardCharsets.UTF_8));
    }

    static byte[] readBytes(Path file) throws IOException {
        return readBytes(file, MAX_BYTES);
    }

    static byte[] readBytes(Path file, int limit) throws IOException {
        if (Files.size(file) > limit) throw new IOException("蓝图文件超过 " + limit / (1024 * 1024) + " MiB 上限");
        try (InputStream input = Files.newInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > limit - output.size()) throw new IOException("蓝图文件超过 " + limit / (1024 * 1024) + " MiB 上限");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static void boundedJson(String json) throws IOException {
        boundedJson(json, MAX_BYTES);
    }

    static void boundedJson(String json, int limit) throws IOException {
        if (json.length() > limit) throw new IOException("蓝图JSON超出上限");
        int depth = 0;
        long utf8Bytes = 0;
        boolean string = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(json.charAt(i - 1))) utf8Bytes++;
            else utf8Bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            if (utf8Bytes > limit) throw new IOException("蓝图JSON UTF-8字节数超出上限");
            if (string) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
            } else if (c == '"') string = true;
            else if (c == '{' || c == '[') {
                if (++depth > 32) throw new IOException("蓝图JSON嵌套超出上限");
            } else if (c == '}' || c == ']') depth--;
        }
    }

    static HologramPresets.Preset decode(String json) throws IOException {
        boundedJson(json);
        try {
            JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonObject() || !root.getAsJsonObject()
                .has("schema")
                || !root.getAsJsonObject()
                    .get("schema")
                    .isJsonPrimitive()
                || !root.getAsJsonObject()
                    .get("schema")
                    .getAsJsonPrimitive()
                    .isNumber()
                || !"2".equals(
                    root.getAsJsonObject()
                        .get("schema")
                        .getAsString()))
                throw new IOException("蓝图分享文件schema无效");
            validatePresetSchema(
                root.getAsJsonObject()
                    .get("blueprint"));
            Share share = GSON.fromJson(root, Share.class);
            HologramPresets.validate(share.blueprint);
            return share.blueprint;
        } catch (RuntimeException invalid) {
            throw new IOException("蓝图分享JSON无效", invalid);
        }
    }

    static void validatePresetSchema(JsonElement entry) throws IOException {
        if (entry == null || !entry.isJsonObject()) throw new IOException("蓝图条目无效");
        if (entry.getAsJsonObject()
            .has("schema")) {
            JsonElement schema = entry.getAsJsonObject()
                .get("schema");
            if (!schema.isJsonPrimitive() || !schema.getAsJsonPrimitive()
                .isNumber()
                || !schema.getAsString()
                    .matches("[12]"))
                throw new IOException("蓝图条目schema无效");
        }
        integerFields(entry, "controllerId", "controllerFacing", "aePolicyVersion");
        for (String rows : new String[] { "cells", "palette", "structures", "hintAreas", "aeIssues" }) {
            JsonElement list = entry.getAsJsonObject()
                .get(rows);
            if (list == null) continue;
            if (!list.isJsonArray()) throw new IOException("蓝图条目列表格式无效");
            for (JsonElement row : list.getAsJsonArray()) {
                if (!row.isJsonObject()) throw new IOException("蓝图条目对象格式无效");
                if (rows.equals("aeIssues"))
                    for (String field : new String[] { "code", "severity", "path", "message" }) {
                        JsonElement value = row.getAsJsonObject()
                            .get(field);
                        if (value == null || !value.isJsonPrimitive()
                            || !value.getAsJsonPrimitive()
                                .isString())
                            throw new IOException("AE issue string field invalid: " + field);
                    }
                integerFields(
                    row,
                    "x",
                    "y",
                    "z",
                    "x1",
                    "y1",
                    "z1",
                    "x2",
                    "y2",
                    "z2",
                    "damage",
                    "defaultDamage",
                    "metadata",
                    "count",
                    "facing",
                    "main",
                    "controllerId");
                JsonElement channels = row.getAsJsonObject()
                    .get("channels");
                if (channels != null) {
                    if (!channels.isJsonObject()) throw new IOException("蓝图结构通道格式无效");
                    for (java.util.Map.Entry<String, JsonElement> channel : channels.getAsJsonObject()
                        .entrySet()) integerFields(channels, channel.getKey());
                }
                JsonElement targets = row.getAsJsonObject()
                    .get("allowedTargets");
                if (targets != null && !targets.isJsonNull()) {
                    if (!targets.isJsonArray()) throw new IOException("蓝图格候选格式无效");
                    for (JsonElement target : targets.getAsJsonArray()) {
                        if (!target.isJsonObject()) throw new IOException("蓝图格候选对象无效");
                        integerFields(target, "damage");
                    }
                }
            }
        }
    }

    private static void integerFields(JsonElement entry, String... keys) throws IOException {
        for (String key : keys) {
            JsonElement value = entry.getAsJsonObject()
                .get(key);
            if (value == null || value.isJsonNull() && key.equals("controllerFacing")) continue;
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
                .isNumber()
                || !value.getAsString()
                    .matches("-?[0-9]+"))
                throw new IOException("蓝图整数字段类型无效：" + key);
            BigInteger integer = new BigInteger(value.getAsString());
            if (integer.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0)
                throw new IOException("蓝图整数字段超出范围：" + key);
        }
    }
}
