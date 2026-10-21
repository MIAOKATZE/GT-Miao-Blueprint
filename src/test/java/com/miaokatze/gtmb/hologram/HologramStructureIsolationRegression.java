package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;
import gregtech.common.tileentities.machines.multi.MTELargeChemicalReactor;

/** Uses the actual GT definitions which previously shared the region's global material palette. */
final class HologramStructureIsolationRegression {

    private HologramStructureIsolationRegression() {}

    static void run() {
        String threadName = Thread.currentThread()
            .getName();
        Block oldCasings1 = GregTechAPI.sBlockCasings1, oldCasings5 = GregTechAPI.sBlockCasings5,
            oldCasings8 = GregTechAPI.sBlockCasings8;
        Thread.currentThread()
            .setName("Server thread");
        try {
            // The framework-free harness boots vanilla registries, so bind production declarations to isolated casing
            // identities.
            GregTechAPI.sBlockCasings1 = casing(4089, "isolation_heatproof");
            GregTechAPI.sBlockCasings5 = casing(4088, "isolation_coils");
            GregTechAPI.sBlockCasings8 = casing(4087, "isolation_inert");
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            for (Field field : EntityPlayer.class.getDeclaredFields())
                if (field.getType() == com.mojang.authlib.GameProfile.class) {
                    field.setAccessible(true);
                    field.set(
                        player,
                        new com.mojang.authlib.GameProfile(
                            java.util.UUID.fromString("faed7230-f18d-4f73-baae-e6bcf319d416"),
                            "StructureFixture"));
                }
            player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.isCreativeMode = true;
            player.capabilities.allowEdit = true;
            player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
            Constructor<?> constructor = type.getDeclaredConstructor(
                EntityPlayerMP.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
            constructor.setAccessible(true);
            Object furnaceSession = constructor.newInstance(player, 0, 64, 0, false, 2);
            Object chemicalSession = constructor.newInstance(player, 10, 64, 0, false, 2);
            String oldNonce = (String) get(furnaceSession, "id");
            method("activateAreaSession", type).invoke(null, furnaceSession);
            String firstActivation = (String) get(furnaceSession, "id");
            method("activateAreaSession", type).invoke(null, furnaceSession);
            SimpleAssert.that(
                !oldNonce.equals(firstActivation) && !firstActivation.equals(get(furnaceSession, "id")),
                "cached area editor receives a fresh network nonce on every activation");
            MTEElectricBlastFurnace furnace = new MTEElectricBlastFurnace("isolation_ebf");
            MTELargeChemicalReactor chemical = new MTELargeChemicalReactor("isolation_lcr");
            ItemStack hotCasing = new ItemStack(GregTechAPI.sBlockCasings1, 1, 11);
            ItemStack inertCasing = new ItemStack(GregTechAPI.sBlockCasings8, 1, 0);
            IStructureElement<?> furnaceElement = shell(furnace.getStructureDefinition(), hotCasing);
            IStructureElement<?> chemicalElement = shell(chemical.getStructureDefinition(), inertCasing);
            HologramCapture.Cell furnaceCell = cell(furnaceElement, hotCasing, "furnace");
            HologramCapture.Cell chemicalCell = cell(chemicalElement, inertCasing, "chemical");
            set(furnaceSession, "machine", furnace);
            set(furnaceSession, "areaStructureId", "furnace");
            set(furnaceSession, "capture", capture(furnaceCell));
            set(chemicalSession, "machine", chemical);
            set(chemicalSession, "areaStructureId", "chemical");
            set(chemicalSession, "capture", capture(chemicalCell));
            Method candidates = method("candidates", type, HologramCapture.Cell.class);
            Method accepts = method("acceptableSample", type, HologramCapture.Cell.class, ItemStack.class);
            SimpleAssert.that(
                contains(options(candidates, furnaceSession, furnaceCell), hotCasing),
                "EBF declaration exposes heatproof casing");
            SimpleAssert.that(
                !contains(options(candidates, furnaceSession, furnaceCell), inertCasing),
                "EBF never inherits LCR inert casing");
            SimpleAssert.that(
                contains(options(candidates, chemicalSession, chemicalCell), inertCasing),
                "LCR declaration exposes inert casing");
            SimpleAssert.that(
                !(Boolean) accepts.invoke(null, furnaceSession, furnaceCell, inertCasing),
                "server rejects foreign inventory/source casing");
            attachmentProjection(player, furnace, furnaceElement, hotCasing);

            HologramArea area = new HologramArea();
            area.structures.put("furnace", new HologramArea.Structure(0, 64, 0, furnace));
            area.structures.put("chemical", new HologramArea.Structure(10, 64, 0, chemical));
            area.structures.get("furnace").capture = (HologramCapture) get(furnaceSession, "capture");
            area.structures.get("chemical").capture = (HologramCapture) get(chemicalSession, "capture");
            Map<Object, Object> areas = serviceMap("AREAS"), editors = serviceMap("AREA_STRUCTURES");
            Object oldArea = areas.put(player.getUniqueID(), area);
            Map<String, Object> sources = new HashMap<>();
            sources.put("furnace", furnaceSession);
            sources.put("chemical", chemicalSession);
            Object oldEditors = editors.put(player.getUniqueID(), sources);
            try {
                furnaceCell.structureIds = Arrays.asList("furnace", "chemical");
                furnaceCell.candidates = null;
                List<ItemStack> shared = options(candidates, furnaceSession, furnaceCell);
                SimpleAssert.that(
                    !contains(shared, hotCasing) && !contains(shared, inertCasing),
                    "shared wall requires intersection of both actual declarations");
                SimpleAssert.that(
                    !(Boolean) accepts.invoke(null, furnaceSession, furnaceCell, hotCasing),
                    "server rejects one-sided shared-wall casing");
            } finally {
                if (oldArea == null) areas.remove(player.getUniqueID());
                else areas.put(player.getUniqueID(), oldArea);
                if (oldEditors == null) editors.remove(player.getUniqueID());
                else editors.put(player.getUniqueID(), oldEditors);
            }

            Object virtual = constructor.newInstance(player, 0, 64, 0, false, 2);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.name = "Independent structures";
            HologramPresets.Structure a = structure("furnace", 2, "coil", 4);
            HologramPresets.Structure b = structure("chemical", 1, "custom", 7);
            preset.structures.add(a);
            preset.structures.add(b);
            HologramPresets.Cell saved = HologramPresets.cell(hotCasing, 1, 0, 0, -1, false);
            saved.metadata = hotCasing.getItem()
                .getMetadata(hotCasing.getItemDamage());
            saved.structureId = "furnace";
            saved.allowedTargets = new ArrayList<>(Arrays.asList(new HologramPresets.Target(hotCasing)));
            preset.cells.add(saved);
            preset.palette.add(HologramPresets.cell(hotCasing, 0, 0, 0, -1, false));
            preset.palette.add(HologramPresets.cell(inertCasing, 0, 0, 0, -1, false));
            set(virtual, "virtual", true);
            set(virtual, "virtualPreset", preset);
            method("populateVirtual", type).invoke(null, virtual);
            HologramCapture.Cell virtualCell = ((HologramCapture) get(virtual, "capture")).cells.get(0);
            SimpleAssert.eq(
                1,
                options(candidates, virtual, virtualCell).size(),
                "saved per-cell candidates ignore the global mixed palette");
            SimpleAssert.that(
                !(Boolean) accepts.invoke(null, virtual, virtualCell, inertCasing),
                "virtual inventory sample cannot bypass source declaration");
            Method select = method("selectAreaStructure", type, String.class);
            select.invoke(null, virtual, "furnace");
            SimpleAssert.eq(2, get(virtual, "main"), "furnace has its own main signal");
            ((NBTTagCompound) get(virtual, "channels")).setInteger("coil", 9);
            select.invoke(null, virtual, "chemical");
            NBTTagCompound channels = (NBTTagCompound) get(virtual, "channels");
            SimpleAssert.that(
                !channels.hasKey("coil") && channels.getInteger("custom") == 7,
                "chemical channels do not inherit furnace signals");
            select.invoke(null, virtual, "furnace");
            SimpleAssert.eq(
                9,
                ((NBTTagCompound) get(virtual, "channels")).getInteger("coil"),
                "switching back retains the furnace's edit");
            set(virtual, "editClipboard", inertCasing);
            NBTTagCompound paste = new NBTTagCompound();
            paste.setInteger("selected", 0);
            method("edit", type, NBTTagCompound.class, String.class).invoke(null, virtual, paste, "editPaste");
            SimpleAssert.that(
                !contains(new ArrayList<>(((Map<Integer, ItemStack>) get(virtual, "pins")).values()), inertCasing),
                "clipboard cannot bypass structure candidate validation");
            HologramPresets.Preset copied = HologramPresets.copy(preset);
            SimpleAssert.eq(
                9,
                copied.structures.get(0).channels.get("coil"),
                "saved structure configuration survives deep copy");
            SimpleAssert.eq(
                saved.allowedTargets.get(0).item,
                copied.cells.get(0).allowedTargets.get(0).item,
                "legal material identity survives save copy");
            saved.allowedTargets = null;
            method("populateVirtual", type).invoke(null, virtual);
            virtualCell = ((HologramCapture) get(virtual, "capture")).cells.get(0);
            SimpleAssert.eq(
                1,
                options(candidates, virtual, virtualCell).size(),
                "legacy structure blueprint stays within its own target identity");
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("structure isolation fixture", failure);
        } finally {
            GregTechAPI.sBlockCasings1 = oldCasings1;
            GregTechAPI.sBlockCasings5 = oldCasings5;
            GregTechAPI.sBlockCasings8 = oldCasings8;
            Thread.currentThread()
                .setName(threadName);
        }
    }

    private static void attachmentProjection(EntityPlayerMP player, MTEElectricBlastFurnace furnace,
        IStructureElement<?> element, ItemStack target) throws java.io.IOException {
        HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
        session.machine = furnace;
        session.capture = new HologramCapture(furnace, new ItemStack(Blocks.stone));
        NBTTagCompound triggerData = new NBTTagCompound();
        triggerData.setString("sourceTrigger", "unchanged");
        session.capture.trigger.setTagCompound(triggerData);
        NBTTagCompound originalTriggerData = (NBTTagCompound) triggerData.copy();
        HologramCapture.Cell physical = new HologramCapture.Cell(element, 1, 64, 0);
        physical.block = Block.getBlockFromItem(target.getItem());
        physical.meta = target.getItemDamage();
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("attachmentRegression", "retained source");
        physical.tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
        String originalTileNbt = physical.tileNbt;
        session.capture.cells.add(physical);
        session.capture.positions.put(HologramValues.position(physical), physical);
        session.pins.put(0, target.copy());
        session.coordinatePins.put(HologramValues.position(physical), target.copy());
        session.planTargets.add(target.copy());
        for (int x = 2; x <= 36; x++) {
            HologramCapture.Cell cell = HologramBlueprintData.blueprintCell(physical, x, 64, 0);
            session.capture.cells.add(cell);
            session.capture.positions.put(HologramValues.position(cell), cell);
            session.planTargets.add(target.copy());
        }
        HologramCapture sourceCapture = session.capture;

        HologramPresets.Preset projection = new HologramPresets.Preset();
        projection.schema = 2;
        HologramPresets.Structure declared = structure("physical-source", 1, "", 0);
        declared.sourceClass = furnace.getClass()
            .getName();
        projection.structures.add(declared);
        java.util.Set<String> palette = new java.util.HashSet<>();
        for (int index = 0; index < sourceCapture.cells.size(); index++)
            projection.cells.add(HologramBlueprintProjection.projectedCell(session, index, projection, palette));
        SimpleAssert.that(
            !projection.cells.get(0).allowedTargets.isEmpty(),
            "actual EBF declaration supplies saved alternatives");
        HologramPresets.Cell saved = projection.cells.get(0);
        List<HologramPresets.Cell> savedCells = new ArrayList<>(projection.cells);
        projection.cells.add(saved);
        rejectProjection(session, projection, sourceCapture, physical, "duplicate projected positions");
        projection.cells.clear();
        rejectProjection(session, projection, sourceCapture, physical, "missing projected source cell");
        projection.cells.addAll(savedCells);

        HologramBlueprintData.setMemberships(physical, Arrays.asList("physical-left", "physical-right"));
        HologramCapture detached = HologramBlueprintData.projectedCapture(sourceCapture, projection, 0, 64, 0);
        SimpleAssert.eq(
            Arrays.asList("physical-left", "physical-right"),
            physical.memberships(),
            "detaching never rewrites multiple memberships on the original physical object");
        SimpleAssert.that(
            detached != sourceCapture && detached.cells.get(0) != physical,
            "projection metadata is installed only on independently owned capture cells");
        SimpleAssert
            .that(sourceCapture.trigger == session.capture.trigger, "detaching preserves the original trigger object");
        SimpleAssert.eq(
            originalTriggerData,
            sourceCapture.trigger.getTagCompound(),
            "detaching preserves exact original trigger NBT");
        HologramBlueprintData.setMemberships(physical, java.util.Collections.emptyList());

        HologramCapture.Cell conflict = HologramBlueprintData
            .blueprintCell(physical, physical.x, physical.y, physical.z);
        try {
            HologramTextWorkflow.stageAttachment(
                session,
                projection,
                java.util.Collections.singletonList(conflict),
                java.util.Collections.emptySet(),
                null);
            throw new AssertionError("overlapping attachment must be rejected");
        } catch (java.io.IOException expected) {
            SimpleAssert.that(
                session.capture == sourceCapture && session.virtualPreset == null && !session.virtual,
                "failed physical-to-author conversion restores the exact original capture, preset and mode");
            SimpleAssert.that(
                physical.memberships()
                    .isEmpty(),
                "rejection cannot mutate original physical membership");
            SimpleAssert
                .that(HologramValues.same(target, session.pins.get(0)), "rejection retains original pin identity");
            SimpleAssert
                .eq(originalTileNbt, sourceCapture.cells.get(0).tileNbt, "rejection retains original device NBT");
            SimpleAssert.that(session.undoEdits.isEmpty(), "rejected staging creates no partial geometry undo entry");
        }

        HologramCapture.Cell added = new HologramCapture.Cell(null, 1, 64, 4);
        added.block = Blocks.stone;
        added.actualStack = new ItemStack(Blocks.stone);
        HologramBlueprintData.setMemberships(added, java.util.Collections.singletonList("attachment"));
        HologramTextWorkflow.stageAttachment(
            session,
            projection,
            java.util.Collections.singletonList(added),
            java.util.Collections.emptySet(),
            null);
        HologramCapture.Cell converted = session.capture.cells.get(0);
        SimpleAssert.that(session.virtual && converted != physical, "successful attachment owns detached source cells");
        SimpleAssert.eq(
            java.util.Collections.singletonList(declared.id),
            converted.memberships(),
            "the existing physical cell acquires its actual schema2 projected member");
        for (int index = 0; index < 36; index++) {
            SimpleAssert.eq(
                java.util.Collections.singletonList(declared.id),
                session.capture.cells.get(index)
                    .memberships(),
                "every retained source position acquires the projected member, not only the selected cell");
            SimpleAssert.that(
                sourceCapture.cells.get(index)
                    .memberships()
                    .isEmpty(),
                "all original physical cells remain unchanged after commit");
        }
        SimpleAssert.that(
            physical.memberships()
                .isEmpty(),
            "conversion leaves the physical source available unchanged");
        SimpleAssert
            .eq(projection.cells.get(0).tileNbt, converted.tileNbt, "conversion preserves projected source NBT");
        SimpleAssert.that(
            contains(HologramCellPolicy.candidates(session, converted), target),
            "virtual editing retains candidates from the actual EBF declaration");
        SimpleAssert
            .that(HologramValues.same(target, session.pins.get(0)), "conversion retains the original pinned target");
        SimpleAssert.eq(
            java.util.Collections.singletonList(declared.id),
            session.undoEdits.get(session.undoEdits.size() - 1).geometry.get(0)
                .memberships(),
            "undo of the attachment retains a valid projected source member");

        HologramBlueprintData.setMemberships(converted, Arrays.asList(declared.id, "shared-source"));
        List<ItemStack> alternatives = converted.structureTargets;
        HologramCapture.Cell replacement = HologramBlueprintData.blueprintCell(added, added.x, added.y, added.z);
        HologramTextWorkflow.stageAttachment(
            session,
            projection,
            java.util.Collections.singletonList(replacement),
            java.util.Collections.singleton(HologramValues.position(added)),
            null);
        SimpleAssert.eq(
            Arrays.asList(declared.id, "shared-source"),
            session.capture.cells.get(0)
                .memberships(),
            "existing virtual re-edit retains all shared-wall memberships");
        SimpleAssert.that(
            session.capture.cells.get(0).structureTargets == alternatives,
            "existing virtual re-edit retains the exact source candidate boundary");
        SimpleAssert.that(
            session.capture.cells.get(0) == converted,
            "existing virtual content is not detached or converted a second time");
    }

    private static void rejectProjection(HologramSession session, HologramPresets.Preset projection,
        HologramCapture original, HologramCapture.Cell physical, String label) {
        try {
            HologramTextWorkflow.stageAttachment(
                session,
                projection,
                java.util.Collections.emptyList(),
                java.util.Collections.emptySet(),
                null);
            throw new AssertionError(label + " must fail before session mutation");
        } catch (java.io.IOException expected) {
            SimpleAssert.that(
                session.capture == original && !session.virtual && session.virtualPreset == null,
                label + " keeps the original capture, mode and preset");
            SimpleAssert.that(
                original.cells.get(0) == physical && physical.memberships()
                    .isEmpty(),
                label + " leaves source cells and memberships unchanged");
            SimpleAssert.that(
                session.pins.size() == 1 && session.coordinatePins.size() == 1 && session.undoEdits.isEmpty(),
                label + " leaves pins and undo untouched");
        }
    }

    private static Block casing(int id, String name) {
        Block block = new Block(net.minecraft.block.material.Material.iron) {

            @Override
            public int damageDropped(int metadata) {
                return metadata;
            }
        };
        Block.blockRegistry.addObject(id, "gtmb:" + name, block);
        Item item = new ItemBlock(block) {

            @Override
            public int getMetadata(int damage) {
                return damage;
            }
        }.setHasSubtypes(true);
        Item.itemRegistry.addObject(id, "gtmb:" + name, item);
        return block;
    }

    private static IStructureElement<?> shell(IStructureDefinition<?> definition, ItemStack wanted) {
        for (IStructureElement<?> element : definition.getStructureFor("main")) {
            if (element.isNavigating()) continue;
            HologramCasingFallback.Target target = HologramCasingFallback.resolve(element, new ItemStack(Blocks.stone));
            if (target != null && same(new ItemStack(target.block, 1, target.meta), wanted)) return element;
        }
        throw new AssertionError("actual GT definition has no expected casing");
    }

    private static HologramCapture.Cell cell(IStructureElement<?> element, ItemStack stack, String id) {
        HologramCapture.Cell cell = new HologramCapture.Cell(element, 1, 64, 0);
        cell.block = Block.getBlockFromItem(stack.getItem());
        cell.meta = stack.getItemDamage();
        cell.structureId = id;
        cell.structureIds = new ArrayList<>(Arrays.asList(id));
        return cell;
    }

    private static HologramCapture capture(HologramCapture.Cell cell) {
        HologramCapture capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        capture.cells.add(cell);
        capture.positions.put("1,64,0", cell);
        return capture;
    }

    private static HologramPresets.Structure structure(String id, int main, String channel, int value) {
        HologramPresets.Structure structure = new HologramPresets.Structure();
        structure.id = id;
        structure.name = id;
        structure.main = main;
        structure.channels.put(channel, value);
        return structure;
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> options(Method method, Object session, HologramCapture.Cell cell)
        throws ReflectiveOperationException {
        return (List<ItemStack>) method.invoke(null, session, cell);
    }

    private static boolean contains(List<ItemStack> options, ItemStack target) {
        return options.stream()
            .anyMatch(option -> same(option, target));
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage();
    }

    private static Method method(String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = HologramEngineInspection.declaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object get(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> serviceMap(String name) throws ReflectiveOperationException {
        Field field = HologramEngineInspection.declaredField(name);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }
}
