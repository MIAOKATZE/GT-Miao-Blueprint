package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual legacy member regeneration narrows shared metadata without replacing any retained source payload. */
final class HologramSharedStructureRegenerationRegression {

    private static final Gson GSON = new Gson();

    private HologramSharedStructureRegenerationRegression() {}

    static void run() {
        int id = 32747;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[id];
        String name = Thread.currentThread()
            .getName();
        HologramWorkBudget.startServerTick();
        HologramWorkBudget.endServerTick();
        try {
            Thread.currentThread()
                .setName("Server thread");
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = new HologramDynamicDefinitionRegression.DynamicController();
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            scenario(unsafe, id, Blocks.stone, false, false, true);
            scenario(unsafe, id, Blocks.dirt, false, false, true);
            scenario(unsafe, id, Blocks.dirt, false, false, false);
            scenario(unsafe, id, Blocks.stone, true, false, true);
            scenario(unsafe, id, Blocks.stone, false, true, true);
        } catch (Exception failure) {
            throw new AssertionError("shared structure regeneration", failure);
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = previous;
            Thread.currentThread()
                .setName(name);
        }
    }

    private static void scenario(sun.misc.Unsafe unsafe, int id, Block declared, boolean unknownOld, boolean unknownNew,
        boolean payload) throws Exception {
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        player.inventory = new net.minecraft.entity.player.InventoryPlayer(player);
        player.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
        session.virtual = true;
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.schema = 2;
        session.virtualPreset.id = "shared_regeneration";
        session.virtualPreset.name = "shared regeneration";
        session.virtualPreset.controllerType = "area";
        HologramPresets.Structure selected = new HologramPresets.Structure();
        selected.id = "selected";
        selected.controllerId = id;
        selected.sourceClass = HologramDynamicDefinitionRegression.DynamicController.class.getName();
        selected.mainExplicit = true;
        session.virtualPreset.structures.add(selected);
        HologramPresets.Structure other = new HologramPresets.Structure();
        other.id = "other";
        session.virtualPreset.structures.add(other);
        session.blueprintActiveStructure = selected.id;
        session.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        add(session, 0, true, Collections.singletonList("selected"), "", "");
        HologramCapture.Cell shared = add(
            session,
            -1,
            false,
            Arrays.asList("selected", "other"),
            payload ? opaque("visible tile") : "",
            payload ? opaque("visible item") : "");
        shared.structureTargets = unknownOld ? null : identities(Blocks.stone, Blocks.glass);
        shared.candidates = identities(Blocks.stone, Blocks.glass);
        shared.candidateComplete = true;
        saved(session.virtualPreset, -1).allowedTargets = unknownOld ? null
            : HologramBlueprintData.savedTargets(shared.structureTargets);
        HologramCapture.Cell edited = add(
            session,
            50,
            false,
            Collections.singletonList("selected"),
            opaque("edited tile"),
            opaque("edited item"));
        session.virtualEdited.add(HologramValues.position(edited));
        HologramCapture.Cell departed = add(session, 60, false, Arrays.asList("selected", "other"), "", "");
        departed.structureTargets = identities(Blocks.stone, Blocks.glass);
        HologramCapture.Cell foreign = add(
            session,
            70,
            false,
            Collections.singletonList("other"),
            opaque("foreign tile"),
            opaque("foreign item"));
        session.processingSource = HologramPresets.copy(session.virtualPreset);
        HologramPresets.Cell rawBefore = saved(session.processingSource, -1);
        rawBefore.tileNbt = payload ? opaque("raw tile") : "";
        rawBefore.itemNbt = payload ? opaque("raw item") : "";
        rawBefore.prompt = "raw opaque annotation";
        HologramPresets.Preset originalPreset = session.virtualPreset, originalRaw = session.processingSource;
        String sourceBefore = GSON.toJson(saved(originalPreset, -1));
        String rawSourceBefore = GSON.toJson(rawBefore);
        try (GTMBExtensions.Registration extension = StructureSources.register(
            "gtmb_test:shared-regeneration",
            HologramDynamicDefinitionRegression.DynamicController.class,
            (machine, trigger) -> new StructureSources.Plan(
                StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
                    .addShape("main", new String[][] { { "A" } })
                    .addElement('A', StructureUtility.ofBlock(declared, 0))
                    .build(),
                Collections.singletonList(new StructureSources.Piece("main", -1, 0, 0, trigger))))) {
            HologramVirtualStructureEditing.Task task = new HologramVirtualStructureEditing.Task(
                session,
                selected,
                2,
                new NBTTagCompound());
            session.main = 2;
            HologramBlueprintData.storeStructureConfiguration(session, selected);
            int slices = 0;
            while (!task.step() && slices++ < 1000) {}
            SimpleAssert.that(slices < 1000, "real native shared regeneration completes bounded slices");
            HologramCapture.Cell generated = task.generated.get("-1,64,0");
            SimpleAssert.that(
                generated != null && generated.structureTargets != null,
                "asymmetric fixture reaches the actual shared coordinate and native candidates");
            if (unknownNew) generated.structureTargets = null;
            task.publish();
        }
        HologramCapture.Cell result = session.capture.positions.get("-1,64,0");
        int expected = declared == Blocks.stone && !unknownOld && !unknownNew ? 1 : 0;
        SimpleAssert.that(
            result != shared && result.candidates == null && !result.candidateComplete,
            "shared candidate cache is detached and never claims complete foreign declarations");
        SimpleAssert.eq(
            expected,
            result.structureTargets.size(),
            "candidate intersection cannot widen or use palette fallback");
        JsonObject localCurrent = GSON.toJsonTree(saved(originalPreset, -1))
            .getAsJsonObject();
        JsonObject localDeclared = new JsonObject();
        if (!unknownNew) localDeclared
            .add("allowedTargets", GSON.toJsonTree(HologramBlueprintData.savedTargets(identities(declared))));
        JsonObject localMerged = HologramLocalAuthorStructures
            .mergeCell(localCurrent, localCurrent, localDeclared, true);
        SimpleAssert.eq(
            expected,
            localMerged.getAsJsonArray("allowedTargets")
                .size(),
            "legacy empty and narrowed intersections match local author reconciliation");
        SimpleAssert.that(
            !localMerged.get("authorDeclarationComplete")
                .getAsBoolean(),
            "neither path declares a conflicting shared target fully verified");
        SimpleAssert.eq(2, shared.candidates.size(), "old candidate cache remains untouched");
        SimpleAssert.that(shared.candidateComplete, "retained source completeness was not mutated in place");
        SimpleAssert.eq(Arrays.asList("selected", "other"), result.memberships(), "foreign memberships preserved");
        SimpleAssert.eq(shared.tileNbt, result.tileNbt, "runtime tile payload preserved");
        SimpleAssert.eq(shared.itemNbt, result.itemNbt, "runtime item payload preserved");
        SimpleAssert.eq(
            expected,
            HologramCellPolicy.candidates(session, result)
                .size(),
            "only intersection is offered");
        SimpleAssert.that(
            !HologramCellPolicy.acceptableSample(session, result, new ItemStack(Blocks.glass)),
            "old-only glass alternative is no longer accepted");
        SimpleAssert.eq(
            expected == 1 || !payload,
            HologramCellPolicy.acceptableSample(session, result, new ItemStack(Blocks.stone)),
            "empty intersection protects payload source; intentional exact plain-source replay remains separate");
        assertSaved(session.virtualPreset, originalPreset, expected, "processed saved source");
        assertSaved(session.processingSource, originalRaw, expected, "raw processing source");
        SimpleAssert.eq(sourceBefore, GSON.toJson(saved(originalPreset, -1)), "old saved source object unchanged");
        SimpleAssert.eq(rawSourceBefore, GSON.toJson(rawBefore), "old raw source object unchanged");
        SimpleAssert.that(
            session.capture.positions.get("50,64,0") == edited,
            "selected-only explicit payload edit is retained verbatim");
        SimpleAssert.that(session.capture.positions.get("70,64,0") == foreign, "foreign source is retained verbatim");
        SimpleAssert.eq(
            Collections.singletonList("other"),
            session.capture.positions.get("60,64,0")
                .memberships(),
            "departed shared cell loses only selected membership");
        SimpleAssert.eq(Arrays.asList("selected", "other"), departed.memberships(), "old departed source untouched");
    }

    private static void assertSaved(HologramPresets.Preset value, HologramPresets.Preset before, int expected,
        String label) throws Exception {
        byte[] encoded = HologramBlueprintCompactCodec.encode(value, (done, total) -> {});
        HologramPresets.Preset roundtrip = GSON.fromJson(
            HologramBlueprintCompactCodec.parse(encoded)
                .getAsJsonObject("blueprint"),
            HologramPresets.Preset.class);
        HologramPresets.Cell actual = saved(roundtrip, -1), original = saved(before, -1);
        SimpleAssert.eq(expected, actual.allowedTargets.size(), label + " narrowed candidates survive serialization");
        JsonObject a = GSON.toJsonTree(actual)
            .getAsJsonObject(),
            b = GSON.toJsonTree(original)
                .getAsJsonObject();
        a.remove("allowedTargets");
        b.remove("allowedTargets");
        SimpleAssert.eq(b.toString(), a.toString(), label + " changes only candidate metadata");
    }

    private static HologramCapture.Cell add(HologramSession session, int x, boolean controller,
        List<String> memberships, String tile, String item) {
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, 64, 0);
        cell.block = Blocks.stone;
        cell.blueprintBlock = cell.blueprintItem = "minecraft:stone";
        cell.userPosition = true;
        cell.role = controller ? "templateController" : "prefab";
        cell.tileNbt = tile;
        cell.itemNbt = item;
        cell.prompt = "preserve annotation";
        HologramBlueprintData.setMemberships(cell, memberships);
        session.pins.put(session.capture.cells.size(), new ItemStack(Blocks.stone));
        session.capture.cells.add(cell);
        session.capture.positions.put(HologramValues.position(cell), cell);
        HologramPresets.Cell saved = HologramPresets.cell(
            new ItemStack(Blocks.stone),
            x,
            0,
            0,
            controller ? ExtendedFacing.DEFAULT.getDirection()
                .ordinal() : -1,
            controller);
        saved.structureId = memberships.get(0);
        saved.structureIds = new ArrayList<>(memberships);
        saved.tileNbt = tile;
        saved.itemNbt = item;
        saved.prompt = cell.prompt;
        session.virtualPreset.cells.add(saved);
        return cell;
    }

    private static HologramPresets.Cell saved(HologramPresets.Preset preset, int x) {
        for (HologramPresets.Cell cell : preset.cells) if (cell.x == x) return cell;
        throw new AssertionError("missing fixture source " + x);
    }

    private static List<ItemStack> identities(Block... blocks) {
        List<ItemStack> result = new ArrayList<>();
        for (Block block : blocks) result.add(new ItemStack(block));
        return result;
    }

    private static String opaque(String value) throws Exception {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("unknownNativeField", value);
        return HologramBlueprintNbt.encodeCaptured(tag);
    }
}
