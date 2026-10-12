package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Production geometry edits against a world fixture that rejects every write. */
final class HologramBlueprintServiceRegression {

    static void run() {
        try {
            ItemStack stone = HologramArea.serverSample(Blocks.stone, 0, null);
            ItemStack chest = HologramArea.serverSample(Blocks.chest, 5, null);
            SimpleAssert.that(
                stone != null && stone.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.stone),
                "server stone sample uses registered block item without client pick hook");
            SimpleAssert.that(
                chest != null && chest.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.chest),
                "server chest sample keeps inventory identity");
            SimpleAssert.eq(0, chest.getItemDamage(), "chest inventory damage excludes world-facing metadata");
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            // Schema-2 ownership and resource plans use the real player profile, unlike the
            // older identity-only editor fixture. Unsafe skips the EntityPlayer constructor.
            Field profile = null;
            for (Field candidate : EntityPlayer.class.getDeclaredFields())
                if (candidate.getType() == GameProfile.class) {
                    profile = candidate;
                    break;
                }
            if (profile == null) throw new NoSuchFieldException("EntityPlayer GameProfile");
            profile.setAccessible(true);
            profile.set(
                player,
                new GameProfile(UUID.fromString("90a8679e-1f2d-44ef-b17e-5d22ba8d2c4b"), "BlueprintFixture"));
            player.worldObj = world;
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.isCreativeMode = true;
            player.capabilities.allowEdit = true;
            player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            Class<?> type = Class.forName(HologramService.class.getName() + "$Session");
            Constructor<?> ctor = type.getDeclaredConstructor(
                EntityPlayerMP.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
            ctor.setAccessible(true);
            Object session = ctor.newInstance(player, 0, 64, 0, false, 2);
            set(session, "virtual", true);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.name = "fixture";
            preset.controllerType = "area";
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, false));
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 4, 0, 0, -1, false));
            set(session, "virtualPreset", preset);
            HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
            settings.main = false;
            settings.containers = false;
            settings.me = false;
            set(session, "materialContext", new HologramMaterials.Context(player, settings));
            Method populate = method(type, "populateVirtual");
            populate.invoke(null, session);
            HologramCapture capture = (HologramCapture) get(session, "capture");
            capture.cells.get(0).prompt = "keep configuration";
            NBTTagCompound data = new NBTTagCompound();
            data.setString("setting", "saved-value");
            capture.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCaptured(data);
            Method move = HologramService.class.getDeclaredMethod("moveBlueprintCells", type, NBTTagCompound.class);
            move.setAccessible(true);
            NBTTagCompound action = new NBTTagCompound();
            action.setIntArray("indices", new int[] { 0 });
            action.setInteger("dx", 1);
            move.invoke(null, session, action);
            capture = (HologramCapture) get(session, "capture");
            SimpleAssert.eq(2, capture.cells.size(), "move keeps total cells");
            SimpleAssert.that(capture.positions.containsKey("2,64,0"), "move commits new coordinate");
            SimpleAssert.eq("keep configuration", capture.positions.get("2,64,0").prompt, "move preserves prompt");
            SimpleAssert.eq(
                "saved-value",
                HologramBlueprintNbt.decode(capture.positions.get("2,64,0").tileNbt)
                    .getString("setting"),
                "planning retains source NBT");
            action.setIntArray("indices", new int[] { capture.cells.indexOf(capture.positions.get("2,64,0")) });
            action.setInteger("dx", 2);
            Object original = get(session, "capture");
            try {
                move.invoke(null, session, action);
                throw new AssertionError("collision must reject whole move");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                SimpleAssert.that(expected.getCause() instanceof java.io.IOException, "collision is checked failure");
            }
            SimpleAssert.that(original == get(session, "capture"), "rejected geometry retains exact capture");
            Method scope = HologramService.class
                .getDeclaredMethod("inScope", type, HologramCapture.Cell.class, int.class);
            scope.setAccessible(true);
            HologramCapture.Cell cell = capture.cells.get(0);
            cell.excluded = true;
            SimpleAssert.that(!(Boolean) scope.invoke(null, session, cell, 0), "excluded cell cannot enter any scope");
            List<?> history = (List<?>) get(session, "undoEdits");
            Object undo = history.get(history.size() - 1);
            Method apply = undo.getClass()
                .getDeclaredMethod("apply", type);
            apply.setAccessible(true);
            apply.invoke(undo, session);
            capture = (HologramCapture) get(session, "capture");
            SimpleAssert.that(capture.positions.containsKey("1,64,0"), "geometry undo restores source coordinate");
            sharedWall(session, player);
            completeStructureMoves(session, player);
            selectionLifecycle(player, session);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            cause.printStackTrace();
            throw new AssertionError(cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static void sharedWall(Object session, EntityPlayerMP player) throws ReflectiveOperationException {
        Class<?> type = session.getClass();
        HologramPresets.Preset preset = (HologramPresets.Preset) get(session, "virtualPreset");
        HologramPresets.Structure a = new HologramPresets.Structure();
        a.id = "A";
        a.name = "A";
        HologramPresets.Structure b = new HologramPresets.Structure();
        b.id = "B";
        b.name = "B";
        a.enabled = false;
        b.enabled = true;
        preset.structures.add(a);
        preset.structures.add(b);
        HologramCapture capture = (HologramCapture) get(session, "capture");
        HologramCapture.Cell shared = capture.positions.get("1,64,0");
        shared.structureId = "A";
        shared.structureIds = new java.util.ArrayList<>(java.util.Arrays.asList("A", "B"));
        shared.tileNbt = "";
        shared.itemNbt = "";
        capture.positions.get("4,64,0").structureId = "B";
        HologramCapture.Cell onlyA = addFixtureCell(session, 7, "A", false);
        HologramArea area = new HologramArea();
        area.addMember("1,64,0", "A");
        area.addMember("1,64,0", "B");
        area.addMember("1,64,0", "B");
        SimpleAssert.eq(
            2,
            area.memberships(1, 64, 0)
                .size(),
            "region captures both owners without duplicate membership");
        Method scope = HologramService.class.getDeclaredMethod("inScope", type, HologramCapture.Cell.class, int.class);
        scope.setAccessible(true);
        set(session, "scope", 0);
        set(session, "blueprintActiveStructure", "");
        SimpleAssert
            .that((Boolean) scope.invoke(null, session, shared, 0), "shared wall builds when only B is enabled");
        b.enabled = false;
        SimpleAssert
            .that(!(Boolean) scope.invoke(null, session, shared, 0), "shared wall is outside scope when both disabled");
        b.enabled = true;
        Method select = HologramService.class.getDeclaredMethod("selectAreaStructure", type, String.class);
        select.setAccessible(true);
        select.invoke(null, session, "B");
        java.util.Set<Integer> selected = (java.util.Set<Integer>) get(session, "selectedIndices");
        SimpleAssert.that(selected.contains(capture.cells.indexOf(shared)), "selecting B includes the shared wall");
        set(session, "scope", 0);
        SimpleAssert.that(
            !(Boolean) scope.invoke(null, session, onlyA, capture.cells.indexOf(onlyA)),
            "active B excludes A even with global scope");
        select.invoke(null, session, "A");
        SimpleAssert.that(
            !(Boolean) scope.invoke(null, session, shared, 0),
            "active disabled A excludes shared wall despite enabled B");
        select.invoke(null, session, "B");
        shared.excluded = true;
        SimpleAssert
            .that(!(Boolean) scope.invoke(null, session, shared, 0), "explicit exclusion dominates shared ownership");
        shared.excluded = false;
        selected.clear();
        selected.add(capture.cells.indexOf(shared));
        set(session, "scope", 2);
        set(session, "virtualPlacement", true);
        set(session, "virtualLocked", true);
        player.posY = 64;
        method(type, "refreshPlan").invoke(null, session);
        Method build = HologramService.class.getDeclaredMethod("directBuild", type, boolean.class);
        build.setAccessible(true);
        build.invoke(null, session, false);
        List<Integer> work = (List<Integer>) get(session, "work");
        int sharedIndex = capture.cells.indexOf(shared);
        int otherIndex = capture.cells.indexOf(capture.positions.get("4,64,0"));
        SimpleAssert.eq(2, work.size(), "whole blueprint includes the shared wall and the other enabled physical cell");
        SimpleAssert.eq(
            1,
            java.util.Collections.frequency(work, sharedIndex),
            "two owners schedule only one work index and one material transaction for their shared wall");
        SimpleAssert.eq(
            1,
            java.util.Collections.frequency(work, otherIndex),
            "inspection selection cannot exclude the other enabled blueprint position");
        SimpleAssert.eq(
            0,
            java.util.Collections.frequency(work, capture.cells.indexOf(onlyA)),
            "whole placement preserves the disabled owner-only cell exclusion");
        assertWholeSharedBudget(session, player);
        SimpleAssert.that(
            player.worldObj.isAirBlock(shared.x, shared.y, shared.z) && player.worldObj.isAirBlock(4, 64, 0),
            "scheduling two physical positions does not write the read-only world");
        set(session, "job", 0);
        Method save = HologramService.class.getDeclaredMethod("blueprint", type, String.class);
        save.setAccessible(true);
        HologramPresets.Preset saved = (HologramPresets.Preset) save.invoke(null, session, "shared");
        int savedWalls = 0;
        for (HologramPresets.Cell cell : saved.cells) if (cell.x == 1 && cell.y == 0 && cell.z == 0) {
            savedWalls++;
            SimpleAssert.that(cell.belongsTo("A") && cell.belongsTo("B"), "save retains both memberships");
        }
        SimpleAssert.eq(1, savedWalls, "saved blueprint has one physical wall item");
        Method rebase = HologramService.class
            .getDeclaredMethod("moveVirtual", type, int.class, int.class, int.class, int.class);
        rebase.setAccessible(true);
        rebase.invoke(null, session, 3, 64, 0, 2);
        capture = (HologramCapture) get(session, "capture");
        shared = capture.positions.get("4,64,0");
        SimpleAssert.that(shared.belongsTo("A") && shared.belongsTo("B"), "rebase/clone preserves both owners");
        set(session, "blueprintActiveStructure", "");
        HologramCapture.Cell controllerA = addFixtureCell(session, 3, "A", true);
        Method move = HologramService.class.getDeclaredMethod("moveBlueprintCells", type, NBTTagCompound.class);
        move.setAccessible(true);
        NBTTagCompound action = new NBTTagCompound();
        action.setBoolean("copy", true);
        action.setInteger("dx", 12);
        action.setIntArray(
            "indices",
            new int[] { capture.cells.indexOf(controllerA), capture.cells.indexOf(shared),
                capture.cells.indexOf(capture.positions.get("10,64,0")) });
        move.invoke(null, session, action);
        capture = (HologramCapture) get(session, "capture");
        HologramCapture.Cell copiedWall = capture.positions.get("16,64,0");
        SimpleAssert.eq(
            1,
            copiedWall.memberships()
                .size(),
            "copy A alone does not attach its new wall to original B");
        SimpleAssert.that(
            !copiedWall.belongsTo("A") && !copiedWall.belongsTo("B"),
            "copy A receives a fresh structure identity");
        shared = capture.positions.get("4,64,0");
        SimpleAssert.that(shared.belongsTo("A") && shared.belongsTo("B"), "copy does not mutate source memberships");
        HologramCapture.Cell controllerB = addFixtureCell(session, 9, "B", true);
        b.x = 6;
        action.setInteger("dx", 24);
        action.setIntArray(
            "indices",
            new int[] { capture.cells.indexOf(shared), capture.cells.indexOf(capture.positions.get("3,64,0")),
                capture.cells.indexOf(controllerB), capture.cells.indexOf(capture.positions.get("10,64,0")),
                capture.cells.indexOf(capture.positions.get("7,64,0")) });
        move.invoke(null, session, action);
        capture = (HologramCapture) get(session, "capture");
        copiedWall = capture.positions.get("28,64,0");
        SimpleAssert.eq(
            2,
            copiedWall.memberships()
                .size(),
            "copy both structures retains a shared new wall");
        SimpleAssert
            .that(!copiedWall.belongsTo("A") && !copiedWall.belongsTo("B"), "both copied memberships are rekeyed");
        Method cut = HologramService.class
            .getDeclaredMethod("blueprintClipboard", type, NBTTagCompound.class, String.class);
        cut.setAccessible(true);
        action = new NBTTagCompound();
        action.setIntArray("indices", new int[] { capture.cells.indexOf(capture.positions.get("4,64,0")) });
        cut.invoke(null, session, action, "editCut");
        List<HologramCapture.Cell> clipboard = (List<HologramCapture.Cell>) get(session, "blueprintClipboard");
        SimpleAssert.eq(1, clipboard.size(), "shared wall is copied once into clipboard");
        SimpleAssert.that(
            clipboard.get(0)
                .belongsTo("A")
                && clipboard.get(0)
                    .belongsTo("B"),
            "cut preserves shared membership in clipboard");
    }

    private static void assertWholeSharedBudget(Object session, EntityPlayerMP player)
        throws ReflectiveOperationException {
        HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
        boolean originalCreative = player.capabilities.isCreativeMode, originalMain = settings.main;
        ItemStack originalStock = player.inventory.mainInventory[1];
        Object originalContext = get(session, "materialContext");
        try {
            player.capabilities.isCreativeMode = false;
            settings.main = true;
            player.inventory.mainInventory[1] = new ItemStack(Blocks.stone, 2);
            set(session, "materialContext", new HologramMaterials.Context(player, settings));
            method(session.getClass(), "refreshResources").invoke(null, session);
            HologramBlueprintResources.Plan total = (HologramBlueprintResources.Plan) get(
                session,
                "blueprintTotalResources");
            int requiredStone = 0;
            for (ItemStack cost : total.items)
                if (cost.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.stone))
                    requiredStone += cost.stackSize;
            SimpleAssert
                .eq(2, requiredStone, "whole plan budgets shared stone once plus the other physical stone once");
            SimpleAssert.eq(0, get(session, "planMissing"), "two actual stone materials satisfy both physical cells");
            SimpleAssert
                .eq(2, player.inventory.mainInventory[1].stackSize, "shared-wall material review never consumes stock");
        } finally {
            player.capabilities.isCreativeMode = originalCreative;
            settings.main = originalMain;
            player.inventory.mainInventory[1] = originalStock;
            set(session, "materialContext", originalContext);
        }
    }

    @SuppressWarnings("unchecked")
    private static void completeStructureMoves(Object session, EntityPlayerMP player)
        throws ReflectiveOperationException, java.io.IOException {
        Class<?> type = session.getClass();
        set(session, "x", 0);
        set(session, "y", 64);
        set(session, "z", 0);
        set(session, "blueprintActiveStructure", "");
        set(session, "scope", 0);
        set(session, "job", 0);
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.name = "Complete structure fixture";
        HologramPresets.Structure structure = new HologramPresets.Structure();
        structure.id = "whole-A";
        structure.name = "A";
        preset.structures.add(structure);
        for (int x = 1; x <= 3; x++) {
            HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), x, 0, 0, -1, false);
            if (x < 3) cell.structureId = structure.id;
            preset.cells.add(cell);
        }
        set(session, "virtualPreset", preset);
        method(type, "populateVirtual").invoke(null, session);
        HologramCapture capture = (HologramCapture) get(session, "capture");
        capture.cells.get(0).role = "templateController";
        capture.cells.get(0).prompt = "copy costed inventory and settings";
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("setting", "retained");
        net.minecraft.nbt.NBTTagList inventory = new net.minecraft.nbt.NBTTagList();
        inventory.appendTag(new ItemStack(Blocks.dirt, 3).writeToNBT(new NBTTagCompound()));
        payload.setTag("inventory", inventory);
        capture.cells.get(1).tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        Method move = HologramService.class.getDeclaredMethod("moveBlueprintCells", type, NBTTagCompound.class);
        move.setAccessible(true);
        NBTTagCompound action = new NBTTagCompound();
        action.setIntArray("indices", new int[] { 1 });
        action.setInteger("dx", 4);
        try {
            move.invoke(null, session, action);
            throw new AssertionError("structure interior cannot move independently");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert.that(rejected.getCause() instanceof java.io.IOException, "partial structure move rejected");
        }
        SimpleAssert.that(capture == get(session, "capture"), "partial rejection is atomic");
        action.setIntArray("indices", new int[] { 0, 1 });
        move.invoke(null, session, action);
        capture = (HologramCapture) get(session, "capture");
        SimpleAssert.that(
            capture.positions.containsKey("5,64,0") && capture.positions.containsKey("6,64,0"),
            "whole structure moves together");
        SimpleAssert.that(!capture.positions.containsKey("1,64,0"), "move removes its former exclusive source");
        action.setBoolean("copy", true);
        for (int repeat = 0; repeat < 3; repeat++) {
            java.util.Set<Integer> selected = (java.util.Set<Integer>) get(session, "selectedIndices");
            action.setIntArray(
                "indices",
                selected.stream()
                    .mapToInt(Integer::intValue)
                    .toArray());
            move.invoke(null, session, action);
        }
        capture = (HologramCapture) get(session, "capture");
        SimpleAssert.eq(9, capture.cells.size(), "three copies add six planned cells");
        SimpleAssert.that(capture.positions.containsKey("5,64,0"), "copy retains original source");
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (HologramPresets.Structure current : preset.structures) identities.add(current.id);
        SimpleAssert.eq(4, identities.size(), "every duplicated structure has an independent UUID");
        SimpleAssert.eq(4, preset.structures.size(), "copy does not duplicate structure descriptors twice");
        SimpleAssert.eq(
            "copy costed inventory and settings",
            capture.positions.get("17,64,0").prompt,
            "copy preserves prompts");
        HologramBlueprintResources.Plan resources = (HologramBlueprintResources.Plan) get(
            session,
            "blueprintResources");
        int inventoryCost = 0;
        for (ItemStack stack : resources.items)
            if (stack.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.dirt))
                inventoryCost += stack.stackSize;
        SimpleAssert.eq(12, inventoryCost, "all four retained inventories require real payment");
        int loose = capture.cells.indexOf(capture.positions.get("3,64,0"));
        action.setBoolean("copy", false);
        action.setIntArray("indices", new int[] { loose });
        action.setInteger("dx", 1);
        move.invoke(null, session, action);
        capture = (HologramCapture) get(session, "capture");
        SimpleAssert.that(capture.positions.containsKey("4,64,0"), "independent unstructured block can move");
        action.setIntArray("indices", new int[] { -1 });
        try {
            move.invoke(null, session, action);
            throw new AssertionError("invalid selection must reject");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert.that(rejected.getCause() instanceof java.io.IOException, "invalid indices fail closed");
        }
        Method add = HologramService.class.getDeclaredMethod("addPosition", type, NBTTagCompound.class);
        add.setAccessible(true);
        NBTTagCompound air = new NBTTagCompound();
        air.setInteger("dx", 19);
        air.setInteger("dy", 0);
        air.setInteger("dz", 0);
        air.setBoolean("airOnly", true);
        add.invoke(null, session, air);
        capture = (HologramCapture) get(session, "capture");
        HologramCapture.Cell virtualAir = capture.positions.get("19,64,0");
        SimpleAssert
            .that(virtualAir != null && virtualAir.block == Blocks.air, "air voxel receives canonical position");
        int count = capture.cells.size();
        add.invoke(null, session, air);
        SimpleAssert.eq(count, capture.cells.size(), "repeated air selection cannot duplicate a position");
        NBTTagCompound remoteAir = (NBTTagCompound) air.copy();
        remoteAir.setInteger("dx", 1000);
        add.invoke(null, session, remoteAir);
        SimpleAssert.eq(count, capture.cells.size(), "air pick outside blueprint bounds is rejected on server");
        Method save = HologramService.class.getDeclaredMethod("blueprint", type, String.class);
        save.setAccessible(true);
        HologramPresets.Preset saved = (HologramPresets.Preset) save.invoke(null, session, "air-preserving");
        boolean savedAir = false;
        for (HologramPresets.Cell cell : saved.cells) if (cell.x == 19) {
            savedAir = true;
            SimpleAssert.eq(Blocks.air, cell.block(), "air survives registry roundtrip");
            SimpleAssert.that(cell.stack() == null && !cell.missing(), "air has no item cost or missing-block marker");
        }
        SimpleAssert.that(savedAir, "air selection persists in the blueprint");
        Method pin = HologramService.class
            .getDeclaredMethod("pinSelection", type, NBTTagCompound.class, int.class, ItemStack.class);
        pin.setAccessible(true);
        int looseIndex = capture.cells.indexOf(capture.positions.get("4,64,0"));
        int airIndex = capture.cells.indexOf(virtualAir);
        NBTTagCompound selected = new NBTTagCompound();
        selected.setIntArray("selectedIndices", new int[] { looseIndex, airIndex });
        pin.invoke(null, session, selected, looseIndex, new ItemStack(Blocks.planks, 1, 1));
        SimpleAssert.eq(1, capture.positions.get("4,64,0").meta, "candidate updates virtual world metadata");
        SimpleAssert.eq(1, capture.positions.get("19,64,0").meta, "candidate applies to the entire selected batch");
        int structureIndex = capture.cells.indexOf(capture.positions.get("6,64,0"));
        selected.setIntArray("selectedIndices", new int[] { looseIndex, structureIndex });
        ItemStack previous = ((Map<Integer, ItemStack>) get(session, "pins")).get(looseIndex)
            .copy();
        try {
            pin.invoke(null, session, selected, looseIndex, new ItemStack(Blocks.dirt));
            throw new AssertionError("candidate batch must reject a structurally illegal member");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert
                .that(rejected.getCause() instanceof java.io.IOException, "candidate batch validates all members");
        }
        SimpleAssert.that(
            previous.isItemEqual(((Map<Integer, ItemStack>) get(session, "pins")).get(looseIndex)),
            "failed batch cannot partially pin its valid first member");
        HologramPresets.Preset implicit = new HologramPresets.Preset();
        implicit.schema = 2;
        implicit.name = "Implicit legacy template";
        implicit.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, true));
        implicit.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 2, 0, 0, -1, false));
        set(session, "virtualPreset", implicit);
        method(type, "populateVirtual").invoke(null, session);
        capture = (HologramCapture) get(session, "capture");
        SimpleAssert.eq(1, implicit.structures.size(), "untagged controller template becomes one explicit structure");
        SimpleAssert.that(
            capture.cells.stream()
                .allMatch(cell -> cell.belongsTo(implicit.structures.get(0).id)),
            "template shell never masquerades as independent loose blocks");
        action.setIntArray("indices", new int[] { 0 });
        action.setInteger("dx", 4);
        try {
            move.invoke(null, session, action);
            throw new AssertionError("partial implicit template cannot move");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert
                .that(rejected.getCause() instanceof java.io.IOException, "implicit template keeps whole constraint");
        }
        action.setIntArray("indices", new int[] { 0, 1 });
        move.invoke(null, session, action);
        SimpleAssert
            .eq(2, ((HologramCapture) get(session, "capture")).cells.size(), "complete implicit template can move");
        implicit.schema = 1;
        try {
            move.invoke(null, session, action);
            throw new AssertionError("schema one templates cannot expose coordinate editing");
        } catch (java.lang.reflect.InvocationTargetException rejected) {
            SimpleAssert
                .that(rejected.getCause() instanceof java.io.IOException, "schema one coordinate edits fail closed");
        }
        declarationCacheBoundaries(session);
    }

    @SuppressWarnings("unchecked")
    private static void declarationCacheBoundaries(Object session) throws ReflectiveOperationException {
        Class<?> type = session.getClass();
        ((Map<?, ?>) get(session, "declarationCandidates")).clear();
        ((Map<?, ?>) get(session, "pureDeclarations")).clear();
        set(session, "declarationCandidateCount", 0);
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> pure = com.gtnewhorizon.structurelib.structure.StructureUtility
            .withChannel(
                "cache_test",
                com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock(Blocks.stone, 0));
        HologramCapture.Cell first = new HologramCapture.Cell(pure, 31, 64, 0);
        HologramCapture.Cell second = new HologramCapture.Cell(pure, 32, 64, 0);
        Method candidates = HologramService.class
            .getDeclaredMethod("declaredCandidates", type, HologramCapture.Cell.class);
        candidates.setAccessible(true);
        List<ItemStack> one = (List<ItemStack>) candidates.invoke(null, session, first);
        List<ItemStack> two = (List<ItemStack>) candidates.invoke(null, session, second);
        SimpleAssert.eq(
            1,
            get(session, "declarationCandidateCount"),
            "pure declaration and transparent channel reuse one raw candidate cache across coordinates");
        SimpleAssert.that(
            one != two && one.size() == 1 && two.size() == 1,
            "per-position candidate lists remain independent for shared-cell intersections");
        one.clear();
        SimpleAssert.eq(
            1,
            ((List<?>) candidates.invoke(null, session, first)).size(),
            "position intersection cannot mutate cached raw declaration candidates");
        com.gtnewhorizon.structurelib.structure.IStructureElement<Object> chain = com.gtnewhorizon.structurelib.structure.StructureUtility
            .ofChain(pure, com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock(Blocks.dirt, 0));
        SimpleAssert.that(!HologramDeclarationPurity.contextFree(chain), "chains retain contextual cache boundaries");
        candidates.invoke(null, session, new HologramCapture.Cell(chain, 33, 64, 0));
        candidates.invoke(null, session, new HologramCapture.Cell(chain, 34, 64, 0));
        SimpleAssert
            .eq(3, get(session, "declarationCandidateCount"), "contextual branches retain separate coordinates");
        set(session, "declarationCandidateCount", 1024);
        candidates.invoke(null, session, first);
        SimpleAssert.eq(1, get(session, "declarationCandidateCount"), "raw candidate cache remains bounded");
    }

    @SuppressWarnings("unchecked")
    private static HologramCapture.Cell addFixtureCell(Object session, int x, String owner, boolean controller)
        throws ReflectiveOperationException {
        HologramCapture capture = (HologramCapture) get(session, "capture");
        HologramCapture.Cell cell = new HologramCapture.Cell(null, x, 64, 0);
        cell.block = Blocks.stone;
        cell.userPosition = true;
        cell.structureId = owner;
        cell.role = controller ? "templateController" : "prefab";
        int index = capture.cells.size();
        capture.cells.add(cell);
        capture.positions.put(x + ",64,0", cell);
        ((Map<Integer, ItemStack>) get(session, "pins")).put(index, new ItemStack(Blocks.stone));
        ((Map<String, ItemStack>) get(session, "coordinatePins")).put(x + ",64,0", new ItemStack(Blocks.stone));
        return cell;
    }

    @SuppressWarnings("unchecked")
    private static void selectionLifecycle(EntityPlayerMP player, Object session) throws ReflectiveOperationException {
        Field sessionsField = HologramService.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        Map<UUID, Object> sessions = (Map<UUID, Object>) sessionsField.get(null);
        Field areasField = HologramService.class.getDeclaredField("AREAS");
        areasField.setAccessible(true);
        Map<UUID, HologramArea> areas = (Map<UUID, HologramArea>) areasField.get(null);
        ItemStack original = player.getHeldItem();
        ItemStack unrelated = new ItemStack(Blocks.stone);
        unrelated.setTagCompound(new NBTTagCompound());
        unrelated.getTagCompound()
            .setString("gtitHologramToolId", "unrelated-tool");
        unrelated.getTagCompound()
            .setBoolean("gtitHologramAreaSelecting", true);
        player.inventory.mainInventory[1] = unrelated;
        original.getTagCompound()
            .setBoolean("gtitHologramAreaSelecting", true);
        HologramArea area = new HologramArea();
        area.selecting = true;
        area.point(1, 1, 64, 1);
        sessions.put(player.getUniqueID(), session);
        areas.put(player.getUniqueID(), area);
        SimpleAssert.that(HologramService.isAreaSelecting(player, original), "selection binds its current tool");
        player.dimension++;
        SimpleAssert.that(!HologramService.isAreaSelecting(player, original), "dimension change invalidates selection");
        Method ui = HologramService.class.getDeclaredMethod("uiSession", EntityPlayerMP.class);
        ui.setAccessible(true);
        ui.invoke(null, player);
        SimpleAssert.that(!areas.containsKey(player.getUniqueID()), "new dimension cannot inherit old corners");
        SimpleAssert.that(
            !original.getTagCompound()
                .hasKey("gtitHologramAreaSelecting"),
            "old bound tool marker cleared");
        SimpleAssert.that(
            unrelated.getTagCompound()
                .getBoolean("gtitHologramAreaSelecting"),
            "other logical tool untouched");
        original.getTagCompound()
            .setBoolean("gtitHologramAreaSelecting", true);
        area = new HologramArea();
        area.selecting = true;
        areas.put(player.getUniqueID(), area);
        player.inventory.currentItem = 1;
        ui.invoke(null, player);
        SimpleAssert.that(!areas.containsKey(player.getUniqueID()), "tool switch cannot inherit old region");
        SimpleAssert.that(
            !original.getTagCompound()
                .hasKey("gtitHologramAreaSelecting"),
            "switched-away marker cleared");
        areas.put(player.getUniqueID(), area);
        HologramService.clear();
        SimpleAssert.that(
            !unrelated.getTagCompound()
                .hasKey("gtitHologramAreaSelecting"),
            "server clear removes bound mode");
        SimpleAssert
            .that(!HologramService.isAreaSelecting(player, unrelated), "orphan tool marker cannot authorize selection");
    }

    private static Method method(Class<?> type, String name) throws ReflectiveOperationException {
        Method method = HologramService.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method;
    }

    private static Object get(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws ReflectiveOperationException {
        Field field = instance.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
