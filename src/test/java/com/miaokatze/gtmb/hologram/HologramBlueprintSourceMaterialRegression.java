package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Arrays;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Source replay repairs stale structure candidates without granting new material or device identities. */
final class HologramBlueprintSourceMaterialRegression {

    static void run(HologramSession owner) throws ReflectiveOperationException {
        HologramSession session = new HologramSession(owner.player, 0, 64, 0, false, 2);
        session.virtual = true;
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.schema = 2;
        session.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        session.materialSettings.main = session.materialSettings.containers = session.materialSettings.me = false;
        HologramPlanClassifier classifier = new HologramPlanClassifier(session);
        int[] damage = { 0, 13, 15, 1, 6, 10 }, counts = { 4, 12, 36, 4, 4, 4 };
        Block[] blocks = { Blocks.wool, Blocks.wool, Blocks.wool, Blocks.stained_hardened_clay,
            Blocks.stained_hardened_clay, Blocks.stained_hardened_clay };
        int admitted = 0;
        for (int group = 0; group < damage.length; group++) {
            for (int repeat = 0; repeat < counts[group]; repeat++) {
                HologramCapture.Cell cell = cell(blocks[group], damage[group], admitted);
                cell.structureTargets = new ArrayList<>();
                if (group == 1 || group == 2) {
                    for (int variant = 0; variant < 12; variant++)
                        cell.structureTargets.add(new ItemStack(Blocks.wool, 1, variant));
                } else for (int variant = 13; variant <= 15; variant++)
                    cell.structureTargets.add(new ItemStack(Blocks.wool, 1, variant));
                ItemStack original = new ItemStack(blocks[group], 1, damage[group]);
                session.capture.cells.add(cell);
                session.capture.positions.put(HologramValues.position(cell), cell);
                session.pins.put(admitted, original);
                SimpleAssert.that(
                    HologramCellPolicy.acceptableSample(session, cell, original),
                    "plain source replay survives a stale height-dependent editing declaration");
                SimpleAssert.eq(
                    "pending",
                    HologramPlanPolicy.classify(session, cell, admitted),
                    "the native placement preflight admits the verified source state");
                SimpleAssert.eq(
                    "pending",
                    classify(classifier, session, cell, admitted),
                    "budgeted and synchronous placement preflight agree on every source replay");
                admitted++;
            }
        }
        SimpleAssert.eq(64, admitted, "six mismatched source/declaration groups reproduce all sixty-four admissions");
        HologramCapture.Cell source = session.capture.cells.get(4);
        ItemStack original = new ItemStack(Blocks.wool, 1, 13);
        int alternatives = source.structureTargets.size();
        SimpleAssert.that(
            !HologramCellPolicy.acceptableSample(session, source, new ItemStack(Blocks.wool, 1, 12)),
            "switching to another undeclared subtype remains rejected");
        SimpleAssert.that(
            HologramCellPolicy.acceptableSample(session, source, new ItemStack(Blocks.wool, 1, 3)),
            "existing declared editing alternatives retain their normal path");
        session.pins.put(4, new ItemStack(Blocks.wool, 1, 12));
        SimpleAssert.eq(
            "unsupported",
            HologramPlanPolicy.classify(session, source, 4),
            "synchronous preflight rejects undeclared replacement material");
        SimpleAssert.eq(
            "unsupported",
            classify(classifier, session, source, 4),
            "budgeted preflight rejects the same undeclared replacement material");
        session.pins.put(4, new ItemStack(Blocks.wool, 1, 3));
        SimpleAssert.eq(
            "pending",
            HologramPlanPolicy.classify(session, source, 4),
            "synchronous preflight retains declared replacement choices");
        SimpleAssert.eq(
            "pending",
            classify(classifier, session, source, 4),
            "budgeted preflight retains the same declared replacement choices");
        session.pins.put(4, original);
        SimpleAssert.eq(alternatives, source.structureTargets.size(), "literal replay does not expand allowedTargets");

        source.meta = 14;
        reject(session, source, original, "tampered saved world metadata");
        source.meta = 13;
        source.block = Blocks.stained_hardened_clay;
        reject(session, source, original, "tampered saved block object");
        source.block = Blocks.wool;
        source.blueprintBlock = "minecraft:stained_hardened_clay";
        reject(session, source, original, "tampered saved block registry");
        source.blueprintBlock = "minecraft:wool";
        source.blueprintItem = "minecraft:stone";
        reject(session, source, original, "tampered saved item registry");
        source.blueprintItem = "minecraft:wool";
        source.blueprintDamage = 14;
        reject(session, source, original, "tampered saved material damage");
        source.blueprintDamage = 13;
        source.unknown = true;
        reject(session, source, original, "unknown source declaration");
        source.unknown = false;
        source.tileNbt = "not-authorized-by-plain-replay";
        reject(session, source, original, "tile payload retains its existing admission path");
        source.tileNbt = "";
        source.itemNbt = "not-authorized-by-plain-replay";
        reject(session, source, original, "item payload retains its existing admission path");
        source.itemNbt = "";
        ItemStack tagged = original.copy();
        tagged.setTagCompound(new NBTTagCompound());
        tagged.getTagCompound()
            .setString("custom", "fixture");
        reject(session, source, tagged, "a newly supplied NBT variant is not a plain original");
        source.role = "templateController";
        reject(session, source, original, "controller role never receives the ordinary material exception");
        source.role = "prefab";
        session.virtualPreset.schema = 1;
        reject(session, source, original, "old template schemas retain their palette policy");
        session.virtualPreset.schema = 2;
        session.virtual = false;
        reject(session, source, original, "live structure editing still requires its native declaration");
        session.virtual = true;

        HologramCapture.Cell chest = cell(Blocks.chest, 0, 80);
        SimpleAssert.that(
            !HologramBlueprintSourceMaterial.plainReplay(session, chest, new ItemStack(Blocks.chest)),
            "a tile-bearing block is not an ordinary literal shell exception");
        HologramCapture.Cell log = cell(Blocks.log, 5, 81);
        log.blueprintDamage = 1;
        SimpleAssert.that(
            HologramBlueprintSourceMaterial.plainReplay(session, log, new ItemStack(Blocks.log, 1, 1)),
            "native axis metadata folding still preserves the physical item variant");
        SimpleAssert.that(
            !HologramBlueprintSourceMaterial.plainReplay(session, log, new ItemStack(Blocks.log, 1, 2)),
            "native axis folding does not authorize a different wood material");
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        gregtech.common.blocks.ItemMachines machines = (gregtech.common.blocks.ItemMachines) ((sun.misc.Unsafe) unsafeField
            .get(null)).allocateInstance(gregtech.common.blocks.ItemMachines.class);
        ItemStack invalidMachine = new ItemStack(machines, 1, gregtech.api.GregTechAPI.METATILEENTITIES.length);
        SimpleAssert.that(
            !HologramBlueprintSourceMaterial.plainReplay(session, source, invalidMachine)
                && !HologramCellPolicy.acceptableSample(session, source, invalidMachine),
            "an invalid GT machine identity never receives an ordinary block replay exception");
    }

    private static HologramCapture.Cell cell(Block block, int metadata, int x) {
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, 64, 0);
        cell.block = block;
        cell.meta = metadata;
        cell.blueprintBlock = String.valueOf(Block.blockRegistry.getNameForObject(block));
        cell.blueprintItem = String.valueOf(Item.itemRegistry.getNameForObject(Item.getItemFromBlock(block)));
        cell.blueprintDamage = metadata;
        cell.role = "prefab";
        cell.userPosition = true;
        HologramBlueprintData.setMemberships(cell, Arrays.asList("native-height-member"));
        return cell;
    }

    private static void reject(HologramSession session, HologramCapture.Cell cell, ItemStack sample, String label) {
        SimpleAssert.that(!HologramBlueprintSourceMaterial.plainReplay(session, cell, sample), label);
        SimpleAssert.that(!HologramCellPolicy.acceptableSample(session, cell, sample), label + " remains inadmissible");
    }

    private static String classify(HologramPlanClassifier classifier, HologramSession session,
        HologramCapture.Cell cell, int index) {
        net.minecraft.world.World world = session.player.worldObj;
        return classifier.classify(
            session,
            cell,
            index,
            world.blockExists(cell.x, cell.y, cell.z),
            world.getBlock(cell.x, cell.y, cell.z),
            world.getBlockMetadata(cell.x, cell.y, cell.z),
            world.getTileEntity(cell.x, cell.y, cell.z));
    }
}
