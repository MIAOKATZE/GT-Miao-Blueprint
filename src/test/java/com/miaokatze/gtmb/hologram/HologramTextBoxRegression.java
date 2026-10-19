package com.miaokatze.gtmb.hologram;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonObject;
import com.miaokatze.gtmb.Tags;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Volume, portable native payload, provenance and deny-first rules exercise product boundaries. */
public final class HologramTextBoxRegression {

    private HologramTextBoxRegression() {}

    public static void run() {
        try {
            HologramTextBox text = new HologramTextBox();
            text.id = HologramTextBox.PREFIX + "11111111-1111-1111-1111-111111111111";
            text.width = 2;
            text.height = 1;
            text.bitmap = new byte[] { 3 };
            text.pixelX = 2;
            text.pixelY = 3;
            text.thickness = 4;
            text.x = -1;
            text.y = 2;
            text.z = 3;
            List<HologramTextBox.Cell> cells = text.generate();
            SimpleAssert.eq(1, cells.size(), "adjacent pixels share their native host");
            SimpleAssert.eq(-1, cells.get(0).x, "negative block placement is exact");
            int volume = 0;
            for (HologramTextBox.Cell cell : cells) {
                SimpleAssert.eq("LittleTilesTileEntity", cell.tile.getString("id"), "native tile entity identity");
                for (int i = 0; i < cell.tile.getInteger("tilesCount"); i++) {
                    NBTTagCompound box = cell.tile.getCompoundTag("t" + i);
                    SimpleAssert.that(
                        !box.hasKey("isStructure") && !box.hasKey("text"),
                        "external payload consists of ordinary native tiles");
                    volume += (box.getInteger("bBox0maxX") - box.getInteger("bBox0minX"))
                        * (box.getInteger("bBox0maxY") - box.getInteger("bBox0minY"))
                        * (box.getInteger("bBox0maxZ") - box.getInteger("bBox0minZ"));
                }
            }
            SimpleAssert.eq(48, volume, "packed boxes preserve exactly the chargeable microblock volume");
            HologramTextBox restored = HologramTextBox.read(text.write());
            SimpleAssert.that(
                Arrays.equals(text.bitmap, restored.bitmap),
                "editable mask survives its native metadata roundtrip");
            restored.arc = 180;
            restored.bend = 90;
            restored.twist = 45;
            restored.yaw = 90;
            SimpleAssert.that(
                !restored.generate()
                    .isEmpty(),
                "combined planar and spatial deformation remains packable");
            restored.pixelX = 17;
            try {
                restored.generate();
                throw new AssertionError("pixel budget accepted");
            } catch (java.io.IOException expected) { /* Invalid requests are refused before world access. */ }

            JsonObject document = new JsonObject(), blueprint = new JsonObject(), metadata = new JsonObject(),
                mods = new JsonObject();
            document.add("blueprint", blueprint);
            blueprint.add("nativeBlueprint", metadata);
            blueprint.add("modVersions", mods);
            metadata.addProperty("gtmbVersion", "0.1.18");
            metadata.addProperty("formatVersion", 1);
            metadata.addProperty("gtnhVersion", "2.8.0-rc-2");
            metadata.addProperty("gtnhVersionSource", "dreamcoremod.displayedModpackVersion");
            mods.addProperty("gregtech", "old-native-version");
            blueprint.addProperty("futureField", "retained");
            blueprint.add("cells", new com.google.gson.JsonArray());
            byte[] upgraded = HologramBlueprintUpgrade.migrate(
                document.toString()
                    .getBytes(StandardCharsets.UTF_8),
                HologramBlueprintArtifact.MAX_JSON_BYTES,
                () -> true);
            JsonObject actual = HologramBlueprintCompactCodec.parse(upgraded)
                .getAsJsonObject("blueprint");
            SimpleAssert.eq(
                "2.8.0-rc-2",
                actual.getAsJsonObject("nativeBlueprint")
                    .get("gtnhVersion")
                    .getAsString(),
                "upgrade does not manufacture pack provenance");
            SimpleAssert.eq(
                "old-native-version",
                actual.getAsJsonObject("modVersions")
                    .get("gregtech")
                    .getAsString(),
                "upgrade retains other Mod versions");
            SimpleAssert.eq(
                Tags.RELEASE_VERSION,
                actual.getAsJsonObject("modVersions")
                    .get("gtmb")
                    .getAsString(),
                "only GTMB version is refreshed");
            SimpleAssert
                .eq(0, HologramBlueprintUpgrade.status(Tags.RELEASE_VERSION), "current blueprint upgrade is disabled");
            SimpleAssert
                .eq(1, HologramBlueprintUpgrade.status("0.1.20"), "ordinary metadata update is not highlighted");
            SimpleAssert
                .eq(2, HologramBlueprintUpgrade.status("0.1.18"), "important native file boundary is highlighted");
            SimpleAssert
                .that(
                    Arrays.equals(
                        upgraded,
                        HologramBlueprintUpgrade
                            .migrate(upgraded, HologramBlueprintArtifact.MAX_JSON_BYTES, () -> true)),
                    "repeated migration is byte-idempotent");

            DecorationVoucherConfig.View rules = new DecorationVoucherConfig.View(
                true,
                Collections.singleton("minecraft"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singleton("minecraft:wool@3"),
                Collections.emptyList());
            SimpleAssert.that(rules.allows(new ItemStack(Blocks.wool, 1, 2)), "allowlist admits a block variant");
            SimpleAssert.that(
                !rules.allows(new ItemStack(Blocks.wool, 1, 3)),
                "exact variant blacklist overrides whole-Mod permission");
            SimpleAssert.that(
                !HologramDecorationVoucher.covered(Blocks.stone, new ItemStack(Blocks.wool)),
                "unrelated inventory material cannot stand in for the placed shell");
        } catch (Exception failure) {
            throw new AssertionError("text and voucher authoring boundaries", failure);
        }
    }
}
