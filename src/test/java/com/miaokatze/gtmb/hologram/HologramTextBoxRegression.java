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

    private static void cuboidPacking() throws Exception {
        HologramTextBox text = new HologramTextBox();
        text.width = 32;
        text.height = 16;
        text.thickness = 16;
        text.bitmap = new byte[64];
        Arrays.fill(text.bitmap, (byte) 255);
        List<HologramTextBox.Cell> packed = text.generate();
        SimpleAssert.eq(2, packed.size(), "large filled glyph spans two complete blocks");
        for (HologramTextBox.Cell cell : packed) {
            SimpleAssert.that(cell.fullBlock, "uncolored full occupancy may use an equivalent ordinary cube");
            SimpleAssert.eq(
                1,
                cell.tile()
                    .getInteger("tilesCount"),
                "4096 voxels merge into one cuboid");
        }
        SimpleAssert.eq(8192, voxels(packed).size(), "three-dimensional packing retains exact material volume");
        text.color = 0x123456;
        for (HologramTextBox.Cell cell : text.generate()) {
            SimpleAssert.that(!cell.fullBlock, "arbitrary tint cannot be lost by converting to an ordinary block");
            SimpleAssert.eq(
                1,
                cell.tile()
                    .getInteger("tilesCount"),
                "colored full cube still uses only one native tile");
        }
        text.width = 2;
        text.height = 2;
        text.thickness = 2;
        text.bitmap = new byte[] { 7 };
        java.util.Set<String> expected = new java.util.HashSet<>(
            Arrays.asList("0,0,0", "0,0,1", "0,1,0", "0,1,1", "1,1,0", "1,1,1"));
        SimpleAssert
            .eq(expected, voxels(text.generate()), "cuboid merging never fills glyph holes or overlaps volumes");
        SimpleAssert.eq(
            2,
            text.generate()
                .get(0)
                .tile()
                .getInteger("tilesCount"),
            "L-shaped glyph packs into two exact cuboids");
    }

    private static void centeredArcs() throws Exception {
        for (boolean vertical : new boolean[] { false, true }) for (int angle : new int[] { -180, 180 }) {
            HologramTextBox text = new HologramTextBox();
            text.vertical = vertical;
            text.width = vertical ? 3 : 33;
            text.height = vertical ? 33 : 3;
            text.bitmap = new byte[(text.width * text.height + 7) / 8];
            Arrays.fill(text.bitmap, (byte) 255);
            text.arc = angle;
            text.thickness = 3;
            java.util.Set<String> curved = voxels(text.generate());
            SimpleAssert.that(curved.contains(vertical ? "1,16,1" : "16,1,1"), "arc keeps the text midpoint fixed");
            for (String point : curved) {
                String[] parts = point.split(",");
                int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]);
                String reflected = (vertical ? x : 32 - x) + "," + (vertical ? 32 - y : y) + "," + parts[2];
                SimpleAssert.that(
                    curved.contains(reflected),
                    "positive and negative arcs unfold symmetrically around the running midpoint");
            }
        }
    }

    private static void rigidRotationAndLayout() throws Exception {
        HologramTextBox text = new HologramTextBox();
        text.text = "猫A\n\uD83D\uDE3A";
        text.vertical = true;
        SimpleAssert.that(
            Arrays.equals(new String[] { "猫", "A", "", "\uD83D\uDE3A" }, text.lines()),
            "vertical layout stacks upright Unicode codepoints and retains explicit line gaps");
        text.width = 35;
        text.height = 2;
        text.bitmap = new byte[(text.width * text.height + 7) / 8];
        Arrays.fill(text.bitmap, (byte) 255);
        text.thickness = 2;
        java.util.Set<String> original = voxels(text.generate());
        SimpleAssert.that(
            text.generate()
                .size() > 1,
            "rigid rotation fixture crosses multiple native hosts");
        for (boolean vertical : new boolean[] { false, true }) {
            java.util.Set<String> expected = new java.util.HashSet<>();
            for (String point : original) {
                String[] values = point.split(",");
                int x = Integer.parseInt(values[0]), y = Integer.parseInt(values[1]), z = Integer.parseInt(values[2]);
                expected.add(vertical ? x + "," + (15 - z) + "," + y : (15 - z) + "," + y + "," + x);
            }
            text.turn(vertical);
            SimpleAssert.eq(expected, voxels(text.generate()), "all microvoxels rotate around one object origin");
            for (int i = 0; i < 3; i++) text.turn(vertical);
            SimpleAssert.eq(original, voxels(text.generate()), "four object turns restore all hosts exactly");
        }
        for (int i = 0; i < 150; i++) text.turn(i % 3 == 0);
        SimpleAssert
            .that(text.turns.length <= HologramPreviewPose.MAX_RECIPE_LENGTH, "mixed rotations keep bounded history");
        NBTTagCompound legacy = text.write();
        legacy.removeTag("layoutVersion");
        SimpleAssert.eq(
            0,
            HologramTextBox.read(legacy).layoutVersion,
            "legacy rotated layouts keep their saved geometry contract");
    }

    private static java.util.Set<String> voxels(List<HologramTextBox.Cell> cells) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (HologramTextBox.Cell cell : cells) for (int i = 0; i < cell.tile()
            .getInteger("tilesCount"); i++) {
                NBTTagCompound box = cell.tile()
                    .getCompoundTag("t" + i);
                for (int x = box.getInteger("bBox0minX"); x < box.getInteger("bBox0maxX"); x++)
                    for (int y = box.getInteger("bBox0minY"); y < box.getInteger("bBox0maxY"); y++)
                        for (int z = box.getInteger("bBox0minZ"); z < box.getInteger("bBox0maxZ"); z++)
                            result.add((cell.x * 16 + x) + "," + (cell.y * 16 + y) + "," + (cell.z * 16 + z));
            }
        return result;
    }

    public static void run() {
        try {
            cuboidPacking();
            centeredArcs();
            rigidRotationAndLayout();
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
                SimpleAssert.eq(
                    "LittleTilesTileEntity",
                    cell.tile()
                        .getString("id"),
                    "native tile entity identity");
                for (int i = 0; i < cell.tile()
                    .getInteger("tilesCount"); i++) {
                    NBTTagCompound box = cell.tile()
                        .getCompoundTag("t" + i);
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
