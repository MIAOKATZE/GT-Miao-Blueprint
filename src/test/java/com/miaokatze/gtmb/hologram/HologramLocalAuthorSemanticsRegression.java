package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Selected payload work must not turn a local edit into whole-document admission or a generic asset setter. */
public final class HologramLocalAuthorSemanticsRegression {

    private HologramLocalAuthorSemanticsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramLocalAuthorSemanticsRegression.class, args)) return;
        run();
        System.out.println("Local author semantics regression passed");
    }

    public static void run() throws Exception {
        legacyMigration();
        resizedKnownArrays();
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("id", "gtswn.fixture");
        payload.setInteger("currentTab", 1);
        payload.setString("unknownVendorData", "preserve");
        JsonObject cell = new JsonObject();
        cell.addProperty("block", "minecraft:stone");
        cell.addProperty("tileNbt", HologramBlueprintNbt.encodeCanonical(payload));
        JsonObject extension = new JsonObject();
        extension.addProperty("future", 17);
        cell.add("unknownExtension", extension);
        String original = cell.toString();

        NBTTagCompound page = HologramLocalAuthorSemantics.inspect(cell, 7, 0, 8);
        SimpleAssert.eq(7, page.getInteger("index"), "detail identity remains caller's local display index");
        SimpleAssert.that(
            page.getTagList("entries", 10)
                .tagCount() > 0,
            "explicit selected inspection has rows");
        SimpleAssert.eq(original, cell.toString(), "inspector cannot mutate authoritative source");
        JsonObject header = new JsonObject();
        header.addProperty("retainNbt", false);
        NBTTagCompound none = HologramLocalAuthorSemantics.inspect(cell, header, 7, 0, 8);
        SimpleAssert.that(
            !none.getBoolean("all") && none.getTagList("keys", 8)
                .tagCount() == 0,
            "explicit retain-none does not become retain-all when opening the inspector");

        NBTTagList edits = edits("tile:/currentTab", "int", "2");
        List<JsonObject> changed = HologramLocalAuthorSemantics.editNbt(Collections.singletonList(cell), edits);
        JsonObject result = changed.get(0);
        SimpleAssert.eq(original, cell.toString(), "returned records are staged, never installed into input");
        SimpleAssert.that(
            result.get("unknownExtension")
                .equals(extension),
            "unknown JSON members survive typed edit");
        SimpleAssert.that(!result.has("itemNbt"), "absent untouched payload does not become null or empty string");
        NBTTagCompound decoded = HologramBlueprintNbt.decode(
            result.get("tileNbt")
                .getAsString());
        SimpleAssert.eq(2, decoded.getInteger("currentTab"), "selected allowed value changes");
        SimpleAssert.eq("preserve", decoded.getString("unknownVendorData"), "unknown payload fields survive edit");

        boolean refused = false;
        try {
            HologramLocalAuthorSemantics.editNbt(Collections.singletonList(cell), edits("tile:/id", "string", "other"));
        } catch (IOException expected) {
            refused = true;
        }
        SimpleAssert.that(refused, "local editor does not acquire identity or asset authority");
        SimpleAssert.eq(original, cell.toString(), "rejection cannot partially mutate source");

        JsonObject missing = new JsonObject();
        missing.addProperty("block", "minecraft:stone");
        refused = false;
        try {
            HologramLocalAuthorSemantics.editNbt(Arrays.asList(cell, missing), edits);
        } catch (IOException expected) {
            refused = true;
        }
        SimpleAssert.that(refused, "multi-selection edit rejects missing payload atomically");
        SimpleAssert.eq(original, cell.toString(), "earlier selected record remains untouched after later failure");

        List<JsonObject> unchanged = HologramLocalAuthorSemantics
            .editNbt(Collections.singletonList(cell), edits("tile:/currentTab", "int", "1"));
        SimpleAssert.eq(
            original,
            unchanged.get(0)
                .toString(),
            "no-op preserves exact encoded payload and source shape");
    }

    private static void legacyMigration() throws Exception {
        JsonObject source = new JsonParser()
            .parse(
                "{\"schema\":1,\"name\":\"legacy\","
                    + "\"controllerType\":\"missing.Controller:1\",\"controllerId\":1,\"future\":true,"
                    + "\"palette\":[{\"item\":\"missing:controller\",\"damage\":1}],"
                    + "\"cells\":[{\"item\":\"missing:controller\",\"damage\":1,\"controller\":true,\"vendor\":9}]}")
            .getAsJsonObject();
        String before = source.toString();
        JsonObject migrated = HologramLocalAuthorSemantics.upgradeLegacy(source);
        SimpleAssert.eq(
            2,
            migrated.get("schema")
                .getAsInt(),
            "explicit legacy edit migration stays available");
        SimpleAssert.that(
            migrated.get("future")
                .getAsBoolean(),
            "legacy unknown header preserved");
        JsonObject cell = migrated.getAsJsonArray("cells")
            .get(0)
            .getAsJsonObject();
        SimpleAssert.eq(
            9,
            cell.get("vendor")
                .getAsInt(),
            "legacy unknown cell preserved");
        SimpleAssert.eq(
            1,
            migrated.getAsJsonArray("structures")
                .size(),
            "implicit legacy controller membership materialized");
        SimpleAssert.eq(
            migrated.getAsJsonArray("structures")
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsString(),
            cell.get("structureId")
                .getAsString(),
            "legacy member group identity is consistent");
        SimpleAssert.eq(before, source.toString(), "legacy input is not changed in place");
    }

    private static void resizedKnownArrays() throws Exception {
        JsonArray before = new JsonParser()
            .parse("[{\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1,\"prompt\":\"old\"}]")
            .getAsJsonArray();
        JsonArray raw = new JsonParser().parse(before.toString())
            .getAsJsonArray();
        raw.get(0)
            .getAsJsonObject()
            .addProperty("futureHint", "retained");
        JsonArray after = new JsonParser().parse(
            "[{\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1,\"prompt\":\"new\"},{\"prompt\":\"generated\"}]")
            .getAsJsonArray();
        JsonArray merged = HologramLocalAuthorSemantics.delta(raw, before, after)
            .getAsJsonArray();
        SimpleAssert.eq(2, merged.size(), "native generated rows may be added explicitly");
        SimpleAssert.eq(
            "retained",
            merged.get(0)
                .getAsJsonObject()
                .get("futureHint")
                .getAsString(),
            "changing known array size cannot erase unknown members of retained row");
        SimpleAssert.eq(
            "new",
            merged.get(0)
                .getAsJsonObject()
                .get("prompt")
                .getAsString(),
            "known field change applied");
    }

    private static NBTTagList edits(String path, String type, String value) {
        NBTTagCompound edit = new NBTTagCompound();
        edit.setString("path", path);
        edit.setString("type", type);
        edit.setString("value", value);
        NBTTagList list = new NBTTagList();
        list.appendTag(edit);
        return list;
    }
}
