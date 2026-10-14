package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real NBT projection and ordered region masks, with no world edits or simulated recipe reconstruction. */
public final class HologramDeidentificationRegression {

    private HologramDeidentificationRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramDeidentificationRegression.class, args)) return;
        run();
        System.out.println("Hologram deidentification and composite area regressions passed");
    }

    public static void run() throws Exception {
        rawZeroAndOwnerGuard();
        coverSlotsBindActor();
        recipesSurviveEveryLevel();
        monotonicMetadata();
        carpenterOwnerBoundary();
        orderedRegions();
    }

    private static void carpenterOwnerBoundary() throws Exception {
        UUID actor = new UUID(11, 22), other = new UUID(33, 44);
        NBTTagCompound nativeTag = new NBTTagCompound();
        nativeTag.setString("id", "TileEntityCarpentersBlock");
        nativeTag.setString("cbOwner", actor.toString());
        nativeTag.setInteger("cbMetadata", 19);
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setString("cbOwner", "recipe owner literal");
        NBTTagCompound material = new ItemStack(Items.paper, 7).writeToNBT(new NBTTagCompound());
        material.setTag("tag", recipe.copy());
        NBTTagList attributes = new NBTTagList();
        attributes.appendTag(material);
        nativeTag.setTag("cbAttrList", attributes);
        nativeTag.setTag("encodedRecipe", recipe.copy());
        for (int level = 0; level <= 10; level++) {
            NBTTagCompound projected = (NBTTagCompound) nativeTag.copy();
            HologramDeidentification.project(projected, level);
            SimpleAssert.eq(
                level == 0 ? actor.toString() : "",
                projected.getString("cbOwner"),
                "Carpenter owner is retained at zero and explicitly blank from level one " + level);
            SimpleAssert.that(
                attributes.equals(projected.getTag("cbAttrList")) && recipe.equals(projected.getTag("encodedRecipe")),
                "Carpenter identity policy leaves physical attribute quantities and opaque recipe ownership exact "
                    + level);
        }
        SimpleAssert.that(
            !HologramDeidentification.foreignOwner(nativeTag, actor, "display name"),
            "registered Carpenter owner's own UUID can use level zero");
        SimpleAssert.that(
            HologramDeidentification.foreignOwner(nativeTag, other, "different player"),
            "level-zero native Carpenter identity belonging to another player is detected");
        NBTTagCompound cleared = (NBTTagCompound) nativeTag.copy();
        HologramDeidentification.project(cleared, 1);
        SimpleAssert.that(
            !HologramDeidentification.foreignOwner(cleared, other, "different player"),
            "blank level-one Carpenter ownership waits for native actor binding");
        NBTTagCompound transported = HologramBlueprintNbt.forPlacement(
            HologramBlueprintNbt.encodeCaptured(nativeTag),
            other,
            "different player",
            1,
            64,
            2,
            true,
            true);
        SimpleAssert.eq(
            actor.toString(),
            transported.getString("cbOwner"),
            "detached NBT transport never unconditionally rewrites the original Carpenter owner");
        NBTTagCompound selected = HologramBlueprintNbt.decode(
            HologramBlueprintNbt.filterCaptured(
                HologramBlueprintNbt.encodeCaptured(nativeTag),
                java.util.Collections.emptyList(),
                true));
        SimpleAssert.eq(
            actor.toString(),
            selected.getString("cbOwner"),
            "an NBT selection cannot silently remove Carpenter identity while claiming level zero");
        HologramDeidentification.project(selected, 1);
        SimpleAssert.eq(
            "",
            selected.getString("cbOwner"),
            "omitting optional Carpenter attributes does not disable the registered owner policy");
        NBTTagCompound opaque = new NBTTagCompound();
        opaque.setString("id", "opaque.tile");
        opaque.setString("cbOwner", other.toString());
        HologramDeidentification.project(opaque, 10);
        SimpleAssert
            .eq(other.toString(), opaque.getString("cbOwner"), "unknown same-name string is not a Carpenter identity");
        SimpleAssert.that(
            !HologramDeidentification.foreignOwner(opaque, actor, "self"),
            "unknown cbOwner data is not guessed as a permission field");
        NBTTagCompound complex = (NBTTagCompound) nativeTag.copy();
        complex.setTag("cbOwner", recipe.copy());
        HologramDeidentification.project(complex, 1);
        SimpleAssert.that(
            recipe.equals(complex.getTag("cbOwner")),
            "unreviewed complex cbOwner data is preserved for explicit schema rejection rather than recursively cleared");
    }

    private static void coverSlotsBindActor() throws Exception {
        NBTTagCompound coverData = new NBTTagCompound();
        coverData.setString("ownerUUID", "original-cover-owner");
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setString("ownerUUID", "legal recipe owner literal");
        coverData.setTag("encodedRecipe", recipe);
        NBTTagCompound row = new NBTTagCompound();
        row.setTag("d", coverData);
        NBTTagList covers = new NBTTagList();
        covers.appendTag(row);
        NBTTagCompound tile = new NBTTagCompound();
        tile.setTag("gt.covers", covers);
        HologramDeidentification.project(tile, 1);
        UUID actor = new UUID(1, 2);
        NBTTagCompound placed = HologramBlueprintNbt
            .forPlacement(HologramBlueprintNbt.encodeCanonical(tile), actor, "builder", 1, 64, 2, true, true);
        NBTTagCompound data = placed.getTagList("gt.covers", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("d");
        SimpleAssert.eq(
            actor.toString(),
            data.getString("ownerUUID"),
            "registered GT cover data ownership is cleared and rebound through its actual d wrapper");
        SimpleAssert.that(
            recipe.equals(data.getCompoundTag("encodedRecipe")),
            "cover ownership binding does not rewrite its opaque recipe configuration");
    }

    private static void rawZeroAndOwnerGuard() throws Exception {
        UUID owner = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        NBTTagCompound raw = new NBTTagCompound();
        raw.setString("mOwnerUuid", owner.toString());
        raw.setString("mOwnerName", "self");
        raw.setInteger("x", 90);
        String captured = HologramBlueprintNbt.encodeCaptured(raw);
        SimpleAssert
            .that(raw.equals(HologramBlueprintNbt.decode(captured)), "raw level zero retains exact device identity");
        SimpleAssert.that(
            raw.equals(HologramBlueprintNbt.decode(HologramBlueprintNbt.filterCaptured(captured, null, true))),
            "save filtering does not silently strip level zero ownership");
        SimpleAssert.that(!HologramDeidentification.foreignOwner(raw, owner, "self"), "own level zero remains usable");
        SimpleAssert.that(
            HologramDeidentification.foreignOwner(raw, new UUID(1, 2), "other"),
            "another player's level zero owner is detectable before placement");
        HologramDeidentification.project(raw, 1);
        SimpleAssert.that(
            raw.getString("mOwnerUuid")
                .isEmpty()
                && raw.getString("mOwnerName")
                    .isEmpty()
                && raw.getInteger("x") == 90,
            "level one clears owner while absolute positioning belongs to level two");
        HologramDeidentification.project(raw, 2);
        SimpleAssert.that(!raw.hasKey("x"), "level two removes absolute device coordinates");
        boolean invalid = false;
        try {
            HologramDeidentification.checked(11);
        } catch (IOException expected) {
            invalid = true;
        }
        SimpleAssert.that(invalid, "out-of-range level cannot reach a persisted blueprint");
    }

    private static void recipesSurviveEveryLevel() throws Exception {
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setString("owner", "legal recipe owner literal");
        recipe.setLong("frequency", 1234);
        recipe.setInteger("x", 5678);
        recipe.setString("proxy", "legal recipe configuration");
        NBTTagCompound item = new ItemStack(Items.paper, 7, 4).writeToNBT(new NBTTagCompound());
        item.setTag("tag", recipe.copy());
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(item);
        NBTTagCompound device = new NBTTagCompound();
        device.setString("ownerUUID", "source-device-owner");
        device.setTag("encodedRecipe", recipe.copy());
        device.setTag("data", recipe.copy());
        device.setTag("Inventory", inventory);
        device.setString("proxy", "opaque mod proxy settings");
        for (int level = 0; level <= 10; level++) {
            NBTTagCompound projected = (NBTTagCompound) device.copy();
            HologramDeidentification.project(projected, level);
            SimpleAssert.that(
                recipe.equals(projected.getCompoundTag("encodedRecipe"))
                    && recipe.equals(projected.getCompoundTag("data")),
                "every level preserves opaque recipe/data subtrees " + level);
            SimpleAssert.that(
                inventory.equals(projected.getTag("Inventory")),
                "every level preserves exact physical item registry/meta/count/NBT " + level);
            NBTTagCompound placed = HologramBlueprintNbt.nativePayload(
                HologramBlueprintNbt.forPlacement(
                    HologramBlueprintNbt.encodeCanonical(projected),
                    new UUID(1, 2),
                    "builder",
                    1,
                    64,
                    2,
                    true,
                    true));
            SimpleAssert.that(
                recipe.equals(placed.getCompoundTag("encodedRecipe")) && recipe.equals(placed.getCompoundTag("data"))
                    && inventory.equals(placed.getTag("Inventory")),
                "the complete placement chain preserves exact recipe and item data " + level);
            SimpleAssert.eq(
                "opaque mod proxy settings",
                projected.getString("proxy"),
                "unknown device schemas do not lose guessed network fields " + level);
            SimpleAssert.that(
                device.getString("ownerUUID")
                    .equals("source-device-owner"),
                "projection is detached");
        }
    }

    private static void monotonicMetadata() throws Exception {
        HologramPresets.Preset raw = new HologramPresets.Preset();
        raw.deidentificationChosen = true;
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        cell.block = "minecraft:stone";
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("ownerID", "old");
        cell.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        raw.cells.add(cell);
        HologramPresets.Preset zero = HologramDeidentification.apply(raw, 0);
        SimpleAssert.that(
            zero.deidentificationChosen && zero.deidentificationLevel == 0,
            "explicit zero choice survives copy and is distinct from never choosing");
        HologramPresets.Preset one = HologramDeidentification.apply(zero, 1);
        SimpleAssert.that(
            HologramBlueprintNbt.decode(one.cells.get(0).tileNbt)
                .getString("ownerID")
                .isEmpty(),
            "explicit level one sanitizes the stored device payload");
        SimpleAssert.eq(
            1,
            HologramDeidentification.apply(one, 0).deidentificationLevel,
            "an already stripped blueprint cannot claim raw level zero");
        HologramPresets.Preset legacy = HologramPresets.copy(raw);
        legacy.aePolicyVersion = 1;
        SimpleAssert
            .eq(1, HologramDeidentification.level(legacy), "legacy canonical blueprints migrate to at least level one");
        SimpleAssert.eq(
            "old",
            HologramBlueprintNbt.decode(raw.cells.get(0).tileNbt)
                .getString("ownerID"),
            "deidentifying a copy never alters the source baseline");
        NBTTagCompound placed = HologramBlueprintNbt
            .forPlacement(one.cells.get(0).tileNbt, new UUID(1, 2), "builder", 1, 2, 3, true, true);
        SimpleAssert.eq(
            new UUID(1, 2).toString(),
            placed.getString("ownerID"),
            "cleared non-GT ownership slots bind to the actual placing actor");
    }

    private static void orderedRegions() throws Exception {
        HologramArea area = new HologramArea();
        area.point(1, 0, 64, 0, "replace");
        area.point(2, 3, 64, 3, "replace");
        area.point(1, 4, 64, 0, "add");
        area.point(2, 5, 64, 3, "replace");
        SimpleAssert.that(
            area.contains(0, 64, 0) && area.contains(5, 64, 3),
            "first corner locks add mode for the complete pair");
        area.point(1, 1, 64, 1, "subtract");
        area.point(2, 2, 64, 2, "replace");
        SimpleAssert
            .that(!area.contains(1, 64, 1) && area.contains(0, 64, 0), "subtraction cuts only its bounded region");
        SimpleAssert.that(
            !area.contains(6, 64, 0) && !area.contains(1, 65, 1),
            "outside cells remain outside the selection mask");
        area.undoRegion();
        SimpleAssert.that(
            area.contains(1, 64, 1) && area.contains(5, 64, 3),
            "undo restores the entire previous composite mask");
        area.point(1, 10, 64, 10, "replace");
        area.point(2, 11, 64, 11, "replace");
        SimpleAssert.that(
            !area.contains(0, 64, 0) && area.contains(10, 64, 10),
            "replace discards prior shapes only after second corner");
        area.undoRegion();
        SimpleAssert
            .that(area.contains(0, 64, 0) && area.contains(5, 64, 3), "undo of replace restores all earlier shapes");
        area.point(1, 100000, 0, 100000, "subtract");
        area.point(2, 200000, 255, 200000, "subtract");
        int[] bounds = area.bounds();
        SimpleAssert.that(
            bounds[0] == 0 && bounds[1] == 64 && bounds[2] == 0 && bounds[3] == 5 && bounds[4] == 64 && bounds[5] == 3,
            "subtraction outside the selected area cannot enlarge the import bounds");
    }
}
