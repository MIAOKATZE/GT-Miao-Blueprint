package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Negative protocol regressions; no AE grid, native container, source inventory or disk write. */
public final class HologramAeProtocolRegression {

    private static Block fixtureBlock;

    private HologramAeProtocolRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAeProtocolRegression.class, args)) return;
        run();
        System.out.println("Hologram AE protocol regressions passed");
    }

    public static void run() {
        try {
            reviewIdentityAndImmutability();
            previewIdentityAndReplay();
            rateAndBounds();
            canonicalRecipeFields();
            nativeSlotDtoProjection();
            repeatedSanitizationAndExplicitResolution();
            changedTileRotationPreservesRecipe();
            selectiveMixedTransport();
            serviceShellRebind();
        } catch (Exception failure) {
            throw new AssertionError("AE protocol/canonical persistence regression", failure);
        }
    }

    private static void reviewIdentityAndImmutability() throws Exception {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.name = "reviewed";
        preset.id = "review_fixture";
        preset.schema = 2;
        preset.aeIssues.add(
            new HologramAePolicy.Issue("ae.contents_not_saved", "warning", "inv.item0", "contents removed", 1, 2, 3));
        NBTTagList materials = new NBTTagList();
        NBTTagCompound material = new NBTTagCompound();
        material.setInteger("required", 4);
        materials.appendTag(material);
        HologramAeProtocol.Review review = new HologramAeProtocol.Review(
            "session",
            "tool",
            9,
            "saveBlueprint",
            false,
            new HologramAePolicy.Draft(preset, preset.aeIssues),
            materials);
        preset.name = "changed after review";
        preset.aeIssues.clear();
        material.setInteger("required", 99);
        NBTTagCompound confirm = new NBTTagCompound();
        confirm.setLong("planRevision", 9);
        confirm.setString("transactionId", review.transactionId);
        SimpleAssert.eq("reviewed", review.approve(confirm, "session", "tool", 9).name, "review freezes exact draft");
        SimpleAssert.eq(
            1,
            review.snapshot(0, 0, 0)
                .getInteger("issueCount"),
            "review freezes complete issues");
        SimpleAssert.eq(
            4,
            review.snapshot(0, 0, 0)
                .getTagList("materials", 10)
                .getCompoundTagAt(0)
                .getInteger("required"),
            "review freezes exact materials");
        rejects(() -> review.approve(confirm, "different session", "tool", 9), "foreign session");
        rejects(() -> review.approve(confirm, "session", "different tool", 9), "foreign projector");
        rejects(() -> review.approve(confirm, "session", "tool", 10), "new plan");
        NBTTagCompound stale = (NBTTagCompound) confirm.copy();
        stale.setLong("planRevision", 8);
        rejects(() -> review.approve(stale, "session", "tool", 9), "stale request revision");
        NBTTagCompound foreign = (NBTTagCompound) confirm.copy();
        foreign.setString("transactionId", "other nonce");
        rejects(() -> review.approve(foreign, "session", "tool", 9), "foreign transaction");
        HologramPresets.Preset blocked = new HologramPresets.Preset();
        blocked.name = "blocked";
        blocked.aeIssues.add(new HologramAePolicy.Issue("ae.unknown_cell", "blocker", "inv.item0", "unknown", 0, 0, 0));
        HologramAeProtocol.Review deny = new HologramAeProtocol.Review(
            "session",
            "tool",
            9,
            "saveBlueprint",
            false,
            new HologramAePolicy.Draft(blocked, blocked.aeIssues),
            new NBTTagList());
        NBTTagCompound blockedConfirm = (NBTTagCompound) confirm.copy();
        blockedConfirm.setString("transactionId", deny.transactionId);
        rejects(() -> deny.approve(blockedConfirm, "session", "tool", 9), "blocker forbids confirmation");
    }

    private static void previewIdentityAndReplay() throws Exception {
        HologramAeProtocol.Preview preview = new HologramAeProtocol.Preview(
            "session",
            "tool",
            "group",
            9,
            10,
            64,
            20,
            null,
            2,
            "payload");
        SimpleAssert.that(
            preview.current("session", "tool", "group", 9, 10, 64, 20, null, 2, "payload"),
            "exact preview binding");
        SimpleAssert
            .that(!preview.current("session", "other tool", "group", 9, 10, 64, 20, null, 2, "payload"), "tool bound");
        SimpleAssert.that(
            !preview.current("session", "tool", "other group", 9, 10, 64, 20, null, 2, "payload"),
            "structure bound");
        SimpleAssert
            .that(!preview.current("session", "tool", "group", 9, 11, 64, 20, null, 2, "payload"), "coordinate bound");
        SimpleAssert
            .that(!preview.current("session", "tool", "group", 9, 10, 64, 20, null, 3, "payload"), "metadata bound");
        SimpleAssert.that(
            !preview.current("session", "tool", "group", 9, 10, 64, 20, null, 2, "other payload"),
            "payload bound");
        NBTTagCompound request = clear(1);
        request.setString("token", preview.token);
        NBTTagCompound ack = new NBTTagCompound();
        ack.setBoolean("accepted", true);
        preview.remember(request, ack, 10, "edited payload");
        SimpleAssert.that(preview.replay((NBTTagCompound) request.copy()), "identical retransmit is read-only replay");
        NBTTagCompound changed = (NBTTagCompound) request.copy();
        changed.setString("slotId", "different slot");
        SimpleAssert.that(!preview.replay(changed), "same sequence with different slot is rejected");
        preview.expires = System.nanoTime() - 1;
        SimpleAssert.that(
            !preview.current("session", "tool", "group", 10, 10, 64, 20, null, 2, "edited payload"),
            "expired preview");
    }

    private static void rateAndBounds() throws Exception {
        HologramAeProtocol.State state = new HologramAeProtocol.State();
        HologramAeProtocol.validateEdit(clear(1), state, 1);
        rejects(() -> HologramAeProtocol.validateEdit(clear(2), state, 1), "one request per tick");
        for (int i = 2; i <= 10; i++) HologramAeProtocol.validateEdit(clear(i), state, i);
        rejects(() -> HologramAeProtocol.validateEdit(clear(11), state, 11), "ten per twenty ticks");
        HologramAeProtocol.validateEdit(clear(12), state, 21);
        NBTTagCompound source = clear(13);
        source.setInteger("sourceInventory", 0);
        rejects(
            () -> HologramAeProtocol.validateEdit(source, new HologramAeProtocol.State(), 1),
            "source inventory is never accepted");
        NBTTagCompound badOperation = clear(13);
        badOperation.setString("mutation", "EXTRACT");
        rejects(
            () -> HologramAeProtocol.validateEdit(badOperation, new HologramAeProtocol.State(), 1),
            "extraction enum invalid");
        NBTTagCompound wrongType = clear(13);
        wrongType.setInteger("editSeq", 13);
        rejects(
            () -> HologramAeProtocol.validateEdit(wrongType, new HologramAeProtocol.State(), 1),
            "typed sequence required");
        NBTTagCompound oversized = new NBTTagCompound();
        oversized.setByteArray("array", new byte[HologramAeProtocol.MAX_CANDIDATE_BYTES]);
        rejects(
            () -> HologramAeProtocol.bytes(oversized, HologramAeProtocol.MAX_CANDIDATE_BYTES),
            "single stack decoded budget");
        oversized.setByteArray("array", new byte[HologramAeProtocol.MAX_EDIT_BYTES]);
        rejects(
            () -> HologramAeProtocol.validateEdit(oversized, new HologramAeProtocol.State(), 1),
            "whole edit decoded budget");
        ArrayList<HologramAePolicy.Issue> report = new ArrayList<>();
        HologramAePolicy.Issue issue = new HologramAePolicy.Issue(
            "ae.fixture",
            "warning",
            "inv.item0",
            "warning",
            0,
            0,
            0);
        for (int i = 0; i <= HologramAePolicy.MAX_ISSUES; i++) report.add(issue);
        rejects(
            () -> HologramAePolicy.issues(report, 0, 0, 0),
            "complete report rejects overflow instead of hiding a blocker");
    }

    private static void canonicalRecipeFields() throws Exception {
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setString("owner", "recipe literal");
        recipe.setString("ownerUUID", "recipe UUID literal");
        recipe.setInteger("x", 44);
        NBTTagCompound host = new NBTTagCompound();
        host.setTag("patternRecipe", recipe);
        String encoded = HologramBlueprintNbt.encodeCanonical(host);
        NBTTagCompound decoded = HologramBlueprintNbt.decode(encoded)
            .getCompoundTag("patternRecipe");
        SimpleAssert
            .eq("recipe literal", decoded.getString("owner"), "canonical transport preserves recipe owner field");
        SimpleAssert.eq(
            "recipe UUID literal",
            decoded.getString("ownerUUID"),
            "canonical transport preserves recipe UUID field");
        SimpleAssert.eq(44, decoded.getInteger("x"), "canonical transport preserves recipe coordinates");
        SimpleAssert.eq("recipe literal", recipe.getString("owner"), "source compound remains unchanged");
    }

    /** The appearance DTO removes transport bookkeeping without changing recipe literals or stored canonical data. */
    @SuppressWarnings("unchecked")
    private static void nativeSlotDtoProjection() throws Exception {
        NBTTagCompound literal = new NBTTagCompound();
        literal.setString("ownerUUID", "legal recipe owner UUID");
        literal.setString("ownerName", "legal recipe owner name");
        literal.setInteger("x", 17);
        literal.setInteger("y", 29);
        literal.setInteger("z", 41);
        net.minecraft.item.ItemStack ingredient = new net.minecraft.item.ItemStack(net.minecraft.init.Items.iron_ingot);
        ingredient.setTagCompound(literal);
        NBTTagList inputs = new NBTTagList();
        inputs.appendTag(ingredient.writeToNBT(new NBTTagCompound()));
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setTag("in", inputs);
        recipe.setBoolean("crafting", true);
        net.minecraft.item.ItemStack original = new net.minecraft.item.ItemStack(net.minecraft.init.Items.paper);
        original.setTagCompound(recipe);
        NBTTagCompound originalNbt = original.writeToNBT(new NBTTagCompound());
        NBTTagCompound canonical = HologramBlueprintNbt.decode(HologramBlueprintNbt.encodeCanonical(originalNbt));
        NBTTagCompound canonicalBefore = (NBTTagCompound) canonical.copy();
        SimpleAssert.that(
            canonical.getCompoundTag("tag")
                .getTagList("in", 10)
                .getCompoundTagAt(0)
                .hasKey(HologramBlueprintNbt.ITEM_REGISTRY),
            "canonical recipe fixture contains real nested transport markers");
        net.minecraft.item.ItemStack marked = net.minecraft.item.ItemStack.loadItemStackFromNBT(canonical);
        java.lang.reflect.Constructor<?> slotCtor = HologramAeBlueprint.Slot.class.getDeclaredConstructor(
            String.class,
            String.class,
            int.class,
            String.class,
            net.minecraft.item.ItemStack.class,
            String[].class,
            int.class,
            Object.class);
        slotCtor.setAccessible(true);
        HologramAeBlueprint.Slot slot = (HologramAeBlueprint.Slot) slotCtor
            .newInstance("patterns/#0", "physical", 1, "pattern", marked, new String[] { "patterns", "#0" }, 0, null);
        NBTTagCompound storedSlotBefore = slot.stack()
            .writeToNBT(new NBTTagCompound());
        Class<?> workType = Class.forName(HologramAeBlueprint.class.getName() + "$Work");
        java.lang.reflect.Constructor<?> workCtor = workType.getDeclaredConstructor(NBTTagCompound.class);
        workCtor.setAccessible(true);
        Object work = workCtor.newInstance(new NBTTagCompound());
        Field slots = workType.getDeclaredField("slots");
        slots.setAccessible(true);
        ((List<HologramAeBlueprint.Slot>) slots.get(work)).add(slot);
        java.lang.reflect.Constructor<?> analysisCtor = HologramAeBlueprint.Analysis.class
            .getDeclaredConstructor(workType);
        analysisCtor.setAccessible(true);
        HologramAeBlueprint.Analysis analysis = (HologramAeBlueprint.Analysis) analysisCtor.newInstance(work);
        HologramAeProtocol.Preview preview = new HologramAeProtocol.Preview(
            "dto",
            "tool",
            "",
            1,
            0,
            64,
            0,
            fixture(),
            0,
            "");
        NBTTagCompound dto = preview.snapshot(analysis)
            .getTagList("slots", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("stack");
        SimpleAssert.eq(originalNbt, dto, "native slot DTO retains the exact complete recipe ItemStack NBT");
        SimpleAssert.eq(
            storedSlotBefore,
            slot.stack()
                .writeToNBT(new NBTTagCompound()),
            "DTO projection never mutates canonical slot data");
        SimpleAssert.eq(canonicalBefore, canonical, "DTO projection leaves canonical registry markers intact");
        SimpleAssert.eq(
            originalNbt,
            original.writeToNBT(new NBTTagCompound()),
            "DTO projection never mutates source stack NBT");
        SimpleAssert.that(
            slot.stack()
                .getTagCompound()
                .getTagList("in", 10)
                .getCompoundTagAt(0)
                .hasKey(HologramBlueprintNbt.ITEM_REGISTRY),
            "stored recipe remains canonically portable after display projection");
    }

    /** Exercise production target bookkeeping without a native tile, grid or writable world. */
    @SuppressWarnings("unchecked")
    private static void serviceShellRebind() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) unsafe
            .allocateInstance(net.minecraft.entity.player.EntityPlayerMP.class);
        Field profile = null;
        for (Field candidate : net.minecraft.entity.player.EntityPlayer.class.getDeclaredFields())
            if (candidate.getType() == com.mojang.authlib.GameProfile.class) {
                profile = candidate;
                break;
            }
        if (profile == null) throw new NoSuchFieldException("EntityPlayer GameProfile");
        profile.setAccessible(true);
        UUID actorIdentity = UUID.fromString("d4b28489-65e0-4a41-9e51-e25a263ac314");
        profile.set(player, new com.mojang.authlib.GameProfile(actorIdentity, "AeProtocol"));
        Field entityIdentity = net.minecraft.entity.Entity.class.getDeclaredField("entityUniqueID");
        entityIdentity.setAccessible(true);
        entityIdentity.set(player, actorIdentity);
        SimpleAssert
            .eq(actorIdentity, player.getUniqueID(), "service fixture entity identity matches its real profile");
        SimpleAssert.eq("AeProtocol", player.getCommandSenderName(), "service fixture supplies its real actor name");
        player.worldObj = world;
        player.inventory = new net.minecraft.entity.player.InventoryPlayer(player);
        player.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
        player.inventory.mainInventory[0] = new net.minecraft.item.ItemStack(Blocks.stone);
        Class<?> sessionType = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
        java.lang.reflect.Constructor<?> ctor = sessionType.getDeclaredConstructor(
            net.minecraft.entity.player.EntityPlayerMP.class,
            int.class,
            int.class,
            int.class,
            boolean.class,
            int.class);
        ctor.setAccessible(true);
        Object session = ctor.newInstance(player, 0, 64, 0, false, 2);
        HologramMaterials.Settings localMaterials = (HologramMaterials.Settings) value(
            sessionType,
            session,
            "materialSettings");
        localMaterials.main = true;
        localMaterials.containers = false;
        localMaterials.me = false;
        HologramCapture.Cell cell = new HologramCapture.Cell(null, 1, 64, 0);
        cell.block = fixture();
        cell.meta = 7;
        cell.tileNbt = "immutable canonical payload";
        cell.structureId = "preserved structure";
        cell.structureTargets = new ArrayList<>();
        cell.structureTargets.add(new net.minecraft.item.ItemStack(net.minecraft.init.Items.paper));
        net.minecraft.item.ItemStack source = new net.minecraft.item.ItemStack(net.minecraft.init.Items.arrow, 3);
        NBTTagCompound sourceTag = new NBTTagCompound();
        sourceTag.setString("source", "unchanged");
        source.setTagCompound(sourceTag);
        for (String fieldName : new String[] { "pins", "autoTargets" }) {
            Field field = sessionType.getDeclaredField(fieldName);
            field.setAccessible(true);
            ((java.util.Map<Integer, net.minecraft.item.ItemStack>) field.get(session))
                .put(0, new net.minecraft.item.ItemStack(net.minecraft.init.Items.paper));
        }
        for (String fieldName : new String[] { "coordinatePins", "frozenAutoTargets" }) {
            Field field = sessionType.getDeclaredField(fieldName);
            field.setAccessible(true);
            ((java.util.Map<String, net.minecraft.item.ItemStack>) field.get(session))
                .put("1,64,0", new net.minecraft.item.ItemStack(net.minecraft.init.Items.paper));
        }
        Field targetsField = sessionType.getDeclaredField("planTargets");
        targetsField.setAccessible(true);
        List<net.minecraft.item.ItemStack> targets = (List<net.minecraft.item.ItemStack>) targetsField.get(session);
        targets.add(new net.minecraft.item.ItemStack(net.minecraft.init.Items.paper));
        Method rebind = HologramAuthoringWorkflow.class.getDeclaredMethod(
            "rebindAeShell",
            sessionType,
            HologramCapture.Cell.class,
            int.class,
            net.minecraft.item.ItemStack.class);
        rebind.setAccessible(true);
        rebind.invoke(null, session, cell, 0, source);
        SimpleAssert.eq("minecraft:arrow", cell.blueprintItem, "surviving shell replaces old blueprint material");
        SimpleAssert.eq(7, cell.meta, "part identity does not reinterpret native host metadata");
        SimpleAssert.that(cell.block == fixture(), "part identity does not reinterpret native host as air");
        SimpleAssert.eq("immutable canonical payload", cell.tileNbt, "rebind preserves sanitized host payload");
        SimpleAssert.eq("preserved structure", cell.structureId, "rebind preserves exact structure ownership");
        for (String fieldName : new String[] { "pins", "autoTargets", "coordinatePins", "frozenAutoTargets" }) {
            Field field = sessionType.getDeclaredField(fieldName);
            field.setAccessible(true);
            java.util.Map<?, net.minecraft.item.ItemStack> map = (java.util.Map<?, net.minecraft.item.ItemStack>) field
                .get(session);
            net.minecraft.item.ItemStack rebound = map.values()
                .iterator()
                .next();
            SimpleAssert.that(
                rebound.getItem() == net.minecraft.init.Items.arrow,
                "all frozen and indexed targets follow remaining shell");
            SimpleAssert.that(rebound != source, "target maps never alias source stack");
        }
        SimpleAssert.that(
            targets.get(0)
                .getItem() == net.minecraft.init.Items.arrow,
            "paid main follows surviving shell");
        SimpleAssert.that(
            cell.structureTargets.get(0)
                .getItem() == net.minecraft.init.Items.arrow,
            "stored declaration follows server-derived shell");
        targets.get(0)
            .setTagCompound(null);
        SimpleAssert.eq(
            "unchanged",
            source.getTagCompound()
                .getString("source"),
            "source inventory NBT is immutable");
        rebind.invoke(null, session, cell, 0, null);
        SimpleAssert.that(
            cell.block == Blocks.air && cell.excluded && cell.userPosition,
            "empty native host is excluded air and cannot remove live blocks");
        SimpleAssert.eq("", cell.tileNbt, "empty host has no installable payload");
        SimpleAssert.eq("", cell.blueprintItem, "empty host has no payable main");
        SimpleAssert.that(targets.get(0) == null, "empty host has no paid target");
        for (String fieldName : new String[] { "pins", "autoTargets", "coordinatePins", "frozenAutoTargets" }) {
            Field field = sessionType.getDeclaredField(fieldName);
            field.setAccessible(true);
            SimpleAssert
                .that(((java.util.Map<?, ?>) field.get(session)).isEmpty(), "empty host removes every stale target");
        }
        SimpleAssert.eq("preserved structure", cell.structureId, "emptying native host retains group boundary");
        importBusyRetainsDraft(sessionType, session, cell);
        virtualStructureNavigation(sessionType, session);
    }

    @SuppressWarnings("unchecked")
    private static void virtualStructureNavigation(Class<?> sessionType, Object session) throws Exception {
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) value(
            sessionType,
            session,
            "player");
        Field areasField = HologramWorkspace.class.getDeclaredField("AREAS");
        areasField.setAccessible(true);
        java.util.Map<UUID, HologramArea> areas = (java.util.Map<UUID, HologramArea>) areasField.get(null);
        UUID actor = player.getUniqueID();
        HologramArea previousArea = areas.get(actor);
        HologramArea area = new HologramArea();
        area.imported = true;
        area.structures.put("B", new HologramArea.Structure(2, 64, 0, null));
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        HologramCapture capture = new HologramCapture(null, player.getHeldItem());
        String blockName = String.valueOf(Block.blockRegistry.getNameForObject(fixture()));
        for (int index = 0; index < 2; index++) {
            String id = index == 0 ? "A" : "B";
            HologramPresets.Structure structure = new HologramPresets.Structure();
            structure.id = id;
            preset.structures.add(structure);
            HologramPresets.Cell saved = new HologramPresets.Cell();
            saved.block = blockName;
            saved.x = index + 1;
            saved.structureId = id;
            preset.cells.add(saved);
            HologramCapture.Cell cell = new HologramCapture.Cell(null, index + 1, 64, 0);
            cell.block = fixture();
            cell.blueprintBlock = blockName;
            cell.structureId = id;
            cell.userPosition = true;
            NBTTagCompound raw = new NBTTagCompound();
            if (index == 1) raw.setString("unreviewed", "requires explicit discard");
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(
                HologramAeBlueprint.analyze(fixture(), 0, raw)
                    .payload());
            capture.cells.add(cell);
            capture.positions.put((index + 1) + ",64,0", cell);
        }
        area.blueprint = preset;
        setValue(sessionType, session, "capture", capture);
        setValue(sessionType, session, "virtualPreset", preset);
        setValue(sessionType, session, "blueprintActiveStructure", "A");
        setValue(sessionType, session, "planRevision", 20L);
        setValue(sessionType, session, "uiSequence", 42L);
        String sessionId = (String) value(sessionType, session, "id");
        Method select = HologramAreaWorkflow.class
            .getDeclaredMethod("selectVirtualStructure", sessionType, NBTTagCompound.class);
        Method ae = HologramAuthoringWorkflow.class
            .getDeclaredMethod("aeAction", sessionType, NBTTagCompound.class, String.class);
        select.setAccessible(true);
        ae.setAccessible(true);
        NBTTagCompound request = new NBTTagCompound();
        request.setBoolean("virtualOnly", true);
        request.setLong("planRevision", 20);
        request.setString("structureId", "B");
        try {
            areas.put(actor, area);
            SimpleAssert
                .eq(true, select.invoke(null, session, request), "warning navigation selects a stored virtual member");
            SimpleAssert
                .eq(sessionId, value(sessionType, session, "id"), "virtual navigation does not retire the session");
            SimpleAssert.eq(
                42L,
                value(sessionType, session, "uiSequence"),
                "virtual navigation preserves the request acknowledgment counter");
            SimpleAssert.eq(
                true,
                value(sessionType, session, "virtual"),
                "live area lookup cannot turn warning navigation into native editing");
            SimpleAssert.eq(
                "B",
                value(sessionType, session, "blueprintActiveStructure"),
                "virtual navigation activates the requested member");
            SimpleAssert.eq(
                21L,
                value(sessionType, session, "planRevision"),
                "successful navigation publishes a fresh revision");
            NBTTagCompound open = new NBTTagCompound();
            open.setLong("planRevision", 21);
            open.setString("structureId", "B");
            open.setInteger("x", 2);
            open.setInteger("y", 64);
            open.setInteger("z", 0);
            ae.invoke(null, session, open, "aePreviewOpen");
            HologramAeProtocol.State state = (HologramAeProtocol.State) value(sessionType, session, "ae");
            SimpleAssert.that(
                state.preview != null && state.preview.structure.equals("B"),
                "fresh cross-structure AE preview accepts its server-bound member and blocker payload");
            SimpleAssert
                .eq(false, select.invoke(null, session, request), "stale navigation cannot change the active member");
            request.setLong("planRevision", 21);
            request.setString("structureId", "missing");
            SimpleAssert
                .eq(false, select.invoke(null, session, request), "missing structure cannot gain editing authority");
            SimpleAssert.eq(
                "B",
                value(sessionType, session, "blueprintActiveStructure"),
                "rejected navigation preserves current authority");
            request.setString("structureId", "");
            SimpleAssert
                .eq(true, select.invoke(null, session, request), "explicit clear returns to whole virtual draft");
            SimpleAssert.eq(
                "",
                value(sessionType, session, "blueprintActiveStructure"),
                "unstructured issue navigation can clear the active group");
            SimpleAssert.that(
                value(sessionType, session, "capture") == capture
                    && value(sessionType, session, "virtualPreset") == preset,
                "navigation keeps the exact draft and cell payloads");
        } finally {
            if (previousArea == null) areas.remove(actor);
            else areas.put(actor, previousArea);
        }
    }

    @SuppressWarnings("unchecked")
    private static void importBusyRetainsDraft(Class<?> sessionType, Object session, HologramCapture.Cell cell)
        throws Exception {
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) value(
            sessionType,
            session,
            "player");
        Field areasField = HologramWorkspace.class.getDeclaredField("AREAS");
        areasField.setAccessible(true);
        java.util.Map<UUID, HologramArea> areas = (java.util.Map<UUID, HologramArea>) areasField.get(null);
        UUID actor = player.getUniqueID();
        HologramArea previousArea = areas.get(actor);
        HologramArea area = new HologramArea();
        area.first = new int[] { 0, 64, 0 };
        area.second = new int[] { 17, 64, 0 };
        HologramCapture previousCapture = new HologramCapture(null, player.getHeldItem());
        previousCapture.cells.add(cell);
        HologramPresets.Preset previousDraft = new HologramPresets.Preset();
        previousDraft.schema = 2;
        setValue(sessionType, session, "capture", previousCapture);
        setValue(sessionType, session, "virtualPreset", previousDraft);
        setValue(sessionType, session, "virtual", true);
        setValue(sessionType, session, "virtualPlacement", true);
        setValue(sessionType, session, "virtualLocked", true);
        setValue(sessionType, session, "blueprintAwaitingConfirmation", true);
        setValue(sessionType, session, "forcePlacementReady", true);
        setValue(sessionType, session, "planRevision", 9L);
        setValue(sessionType, session, "placementReviewNonce", 7L);
        HologramAeProtocol.State state = (HologramAeProtocol.State) value(sessionType, session, "ae");
        state.preview = new HologramAeProtocol.Preview("old", "tool", "", 9, 0, 64, 0, fixture(), 0, "old");
        state.review = new HologramAeProtocol.Review(
            "old",
            "tool",
            9,
            "saveBlueprint",
            false,
            new HologramAePolicy.Draft(previousDraft, previousDraft.aeIssues),
            new NBTTagList());
        Method begin = HologramAreaWorkflow.class.getDeclaredMethod("startAreaImport", sessionType, HologramArea.class);
        Method busy = HologramSessionPolicy.class.getDeclaredMethod("blockedByAreaImport", sessionType);
        Method close = HologramAreaWorkflow.class.getDeclaredMethod("cancelAreaImport", sessionType);
        Method build = HologramBuildPreparation.class.getDeclaredMethod("directBuild", sessionType, boolean.class);
        Method nativeBuild = HologramBuildPreparation.class.getDeclaredMethod("nativeBuild", sessionType);
        Method move = HologramWorldPlacementWorkflow.class
            .getDeclaredMethod("moveVirtual", sessionType, int.class, int.class, int.class, int.class);
        for (Method method : new Method[] { begin, busy, close, build, nativeBuild, move }) method.setAccessible(true);
        try {
            areas.put(actor, area);
            begin.invoke(null, session, area);
            SimpleAssert
                .that(area.importing && (Boolean) busy.invoke(null, session), "import is an explicit busy phase");
            SimpleAssert.eq(10L, value(sessionType, session, "planRevision"), "starting import expires old actions");
            SimpleAssert.eq(
                8L,
                value(sessionType, session, "placementReviewNonce"),
                "starting import expires old placement confirmation");
            SimpleAssert.that(state.preview == null && state.review == null, "starting import expires old AE tokens");
            SimpleAssert.eq(
                false,
                value(sessionType, session, "virtualPlacement"),
                "old world placement is no longer authorized");
            SimpleAssert
                .eq(false, value(sessionType, session, "virtualLocked"), "old world position confirmation expires");
            SimpleAssert.eq(
                false,
                value(sessionType, session, "blueprintAwaitingConfirmation"),
                "old placement dialog expires");
            build.invoke(null, session, false);
            nativeBuild.invoke(null, session);
            SimpleAssert.eq(
                0,
                value(sessionType, session, "job"),
                "direct and native construction cannot schedule during import");
            SimpleAssert.eq(
                false,
                move.invoke(null, session, 2, 64, 0, 2),
                "world tools cannot rebase the old draft during import");
            close.invoke(null, session);
            SimpleAssert
                .that(!area.importing && !(Boolean) busy.invoke(null, session), "closing cancels unfinished import");
            SimpleAssert.eq(false, value(sessionType, session, "open"), "cancellation cannot reopen the editor");
            SimpleAssert.that(
                value(sessionType, session, "capture") == previousCapture
                    && value(sessionType, session, "virtualPreset") == previousDraft,
                "import and cancellation retain the exact old draft");
            SimpleAssert.that(previousCapture.cells.get(0) == cell, "import and cancellation do not replace old cells");
        } finally {
            if (previousArea == null) areas.remove(actor);
            else areas.put(actor, previousArea);
        }
    }

    private static Object value(Class<?> type, Object instance, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void setValue(Class<?> type, Object instance, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static Block fixture() {
        if (fixtureBlock != null) return fixtureBlock;
        Block fixture = new appeng.block.GtmbAeStaticFixture();
        int free = 4095;
        while (free > 0 && Block.getBlockById(free) != null && Block.getBlockById(free) != Blocks.air) free--;
        if (free == 0) throw new AssertionError("No free test-only block registry ID");
        Block.blockRegistry.addObject(free, "gtmb:ae_protocol_fixture", fixture);
        fixtureBlock = fixture;
        return fixtureBlock;
    }

    private static void repeatedSanitizationAndExplicitResolution() throws Exception {
        Block block = fixture();
        SimpleAssert.that(
            HologramAeBlueprint.staticBlockShell(block, 0),
            "static AE decoration has no tile-state restore requirement");
        SimpleAssert.that(
            !HologramAeBlueprint.staticBlockShell(Blocks.stone, 0),
            "static-shell exception does not classify unrelated blocks");
        NBTTagCompound raw = new NBTTagCompound();
        NBTTagCompound unsafe = new NBTTagCompound();
        unsafe.setString("externalReference", "must not be copied or silently accepted");
        raw.setTag("unreviewed", unsafe);
        String original = HologramBlueprintNbt.encodeCanonical(raw);
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.schema = 2;
        source.id = "ae_protocol_fixture";
        source.name = "AE protocol fixture";
        HologramPresets.Cell cell = new HologramPresets.Cell();
        cell.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
        SimpleAssert.that(cell.block() == block, "persistence fixture resolves its actual registry identity");
        SimpleAssert
            .that(HologramAeBlueprint.supports(cell.block(), raw), "persistence fixture enters the AE policy boundary");
        cell.tileNbt = original;
        source.cells.add(cell);
        HologramAePolicy.Draft first = HologramAePolicy.sanitize(source);
        HologramAePolicy.Draft second = HologramAePolicy.sanitize(first.preset);
        HologramAePolicy.Draft third = HologramAePolicy.sanitize(second.preset);
        SimpleAssert.that(
            first.blocking && second.blocking && third.blocking,
            "omitted unreviewed field remains blocked across repeated canonical entry points");
        SimpleAssert.eq(original, source.cells.get(0).tileNbt, "source blueprint payload is immutable");
        SimpleAssert.eq(0, source.aeIssues.size(), "source issue list is immutable");
        SimpleAssert.that(
            !HologramBlueprintNbt.decode(first.preset.cells.get(0).tileNbt)
                .hasKey("unreviewed"),
            "unsafe source field is omitted rather than kept as a recoverable NBT shadow");
        Field cache = HologramPresets.class.getDeclaredField("cached");
        cache.setAccessible(true);
        Object previous = cache.get(null);
        try {
            cache.set(null, new ArrayList<>(Collections.singletonList(first.preset)));
            HologramPresets.Preset loaded = HologramPresets.get(source.id);
            SimpleAssert
                .that(HologramAePolicy.sanitize(loaded).blocking, "save/get migration cannot forget unresolved loss");
            rejects(
                () -> HologramPresets.save(second.preset),
                "writer refuses twice-sanitized unresolved blueprint before any disk write");
            SimpleAssert.that(
                HologramAePolicy.sanitize(HologramPresets.get(source.id)).blocking,
                "rejected persistence leaves unresolved cached source intact");
        } finally {
            cache.set(null, previous);
        }
        HologramAeBlueprint.Analysis before = HologramAeBlueprint
            .analyze(block, 0, HologramBlueprintNbt.decode(first.preset.cells.get(0).tileNbt));
        HologramAeBlueprint.Slot tombstone = null;
        for (HologramAeBlueprint.Slot slot : before.slots)
            if (slot.issueCode.equals("static_block_data")) tombstone = slot;
        SimpleAssert.that(
            tombstone != null && !tombstone.enabled && tombstone.kind.equals("readonly"),
            "omitted field requires an explicit server-issued readonly discard slot");
        final String tombstoneId = tombstone.id;
        HologramAeBlueprint.Analysis wrong = HologramAeBlueprint.analyze(block, 0, before.payload());
        SimpleAssert.that(wrong.blocking, "opening or re-analyzing is not an acknowledgement");
        HologramAeBlueprint.Analysis cleared = HologramAeBlueprint
            .updateSlot(block, 0, before.payload(), tombstoneId, null);
        SimpleAssert.that(!cleared.blocking, "validated explicit tombstone CLEAR resolves this omitted field");
        HologramPresets.Preset repaired = HologramPresets.copy(first.preset);
        repaired.cells.get(0).tileNbt = HologramBlueprintNbt.encodeCanonical(cleared.payload());
        repaired.aeIssues = HologramAePolicy
            .resolveAfterMutation(repaired.aeIssues, 0, 0, 0, tombstoneId, before, cleared);
        SimpleAssert.that(
            !HologramAePolicy.sanitize(repaired).blocking,
            "explicit repair persists through later canonical analyses");
        SimpleAssert.that(
            HologramAePolicy.sanitize(first.preset).blocking,
            "repair does not mutate the original unresolved draft");
        SimpleAssert.eq(
            "must not be copied or silently accepted",
            unsafe.getString("externalReference"),
            "source NBT compound is never changed by review/CLEAR");
        HologramPresets.Preset removed = HologramPresets.copy(first.preset);
        removed.cells.clear();
        SimpleAssert.that(
            !HologramAePolicy.sanitize(removed).blocking,
            "explicit whole-cell removal leaves no blocked artifact to restore");
    }

    private static void changedTileRotationPreservesRecipe() throws Exception {
        Block block = fixture();
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setString("ownerUUID", "legal recipe value");
        recipe.setString("ownerName", "legal recipe name");
        NBTTagCompound tile = new NBTTagCompound();
        tile.setTag("encodedPatternRecipe", recipe);
        tile.setInteger("orientation", 0);
        NBTTagCompound item = new NBTTagCompound();
        item.setTag("encodedPatternRecipe", recipe.copy());
        item.setInteger("orientation", 0);
        HologramBlockTransforms.Adapter turn = new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.block == block;
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) {
                data.tile.setInteger("orientation", 1);
                data.item.setInteger("orientation", 1);
            }
        };
        HologramBlockTransforms.registerFirst(turn);
        try {
            HologramBlockTransforms.Result changed = HologramBlockTransforms.turn(
                block,
                "",
                0,
                HologramBlueprintNbt.encodeCanonical(tile),
                HologramBlueprintNbt.encodeCanonical(item),
                false);
            NBTTagCompound result = HologramBlueprintNbt.decode(changed.tileNbt);
            SimpleAssert.eq(1, result.getInteger("orientation"), "test actually executes changed-tile re-encoding");
            SimpleAssert.eq(
                "legal recipe value",
                result.getCompoundTag("encodedPatternRecipe")
                    .getString("ownerUUID"),
                "rotated AE tile keeps complete encoded recipe UUID data");
            SimpleAssert.eq(
                "legal recipe name",
                result.getCompoundTag("encodedPatternRecipe")
                    .getString("ownerName"),
                "rotated AE tile keeps complete encoded recipe owner data");
            SimpleAssert.eq(0, tile.getInteger("orientation"), "rotation leaves source tile compound unchanged");
            NBTTagCompound changedItem = HologramBlueprintNbt.decode(changed.itemNbt);
            SimpleAssert.eq(1, changedItem.getInteger("orientation"), "changed item re-encoding executes too");
            SimpleAssert.eq(
                "legal recipe value",
                changedItem.getCompoundTag("encodedPatternRecipe")
                    .getString("ownerUUID"),
                "ordinary AE item re-encoding keeps legal recipe UUID data");
            SimpleAssert.eq(
                "legal recipe name",
                changedItem.getCompoundTag("encodedPatternRecipe")
                    .getString("ownerName"),
                "ordinary AE item re-encoding keeps legal recipe owner data");
            SimpleAssert.eq(0, item.getInteger("orientation"), "source item compound remains unchanged");
        } finally {
            HologramBlockTransforms.unregister(turn);
        }
    }

    private static void selectiveMixedTransport() throws Exception {
        NBTTagCompound nonAe = new NBTTagCompound();
        nonAe.setString("id", "fixture_non_ae");
        nonAe.setString("ownerUUID", "old non-AE owner");
        nonAe.setString("ownerName", "old non-AE name");
        nonAe.setInteger("x", 17);
        NBTTagCompound nested = new NBTTagCompound();
        nested.setString("ownerID", "nested old owner");
        nonAe.setTag("nested", nested);
        nonAe.setTag("cover", (NBTTagCompound) nested.copy());
        NBTTagCompound ae = new NBTTagCompound();
        ae.setString("id", "ae2_cablebus");
        NBTTagCompound recipe = new NBTTagCompound();
        recipe.setString("ownerUUID", "legal AE recipe UUID");
        recipe.setString("ownerName", "legal AE recipe owner");
        recipe.setInteger("x", 333);
        recipe.setTag("cover", (NBTTagCompound) nested.copy());
        NBTTagCompound extra = new NBTTagCompound();
        extra.setTag("encodedRecipe", recipe);
        ae.setTag("extra:0", extra);
        NBTTagList parts = new NBTTagList();
        parts.appendTag(nonAe);
        parts.appendTag(ae);
        NBTTagCompound source = new NBTTagCompound();
        source.setInteger("x", 1);
        source.setInteger("y", 2);
        source.setInteger("z", 3);
        source.setString("ownerUUID", "old root owner");
        source.setTag("parts", parts);
        NBTTagList unresolved = new NBTTagList();
        NBTTagCompound marker = new NBTTagCompound();
        marker.setString("code", "static_block_data");
        marker.setString("path", "unreviewed");
        marker.setString("message", "bounded unresolved summary");
        unresolved.appendTag(marker);
        source.setTag(HologramAeBlueprint.UNRESOLVED, unresolved);
        NBTTagCompound original = (NBTTagCompound) source.copy();
        NBTTagCompound capture = HologramAeBlueprint.isAeMultipartPart(ae)
            ? HologramBlueprintNbt.canonicalMultipartPayload(source, null, "", false)
            : null;
        SimpleAssert.that(capture != null, "production exact AE part predicate recognizes only its registered id");
        NBTTagCompound captureDecoded = HologramBlueprintNbt.decode(HologramBlueprintNbt.encodeCanonical(capture));
        SimpleAssert.that(
            captureDecoded.getTagList("parts", 10)
                .getCompoundTagAt(1)
                .equals(ae),
            "AE part entire recipe subtree remains exact through capture transport");
        NBTTagCompound rawCapture = HologramBlueprintNbt.decode(HologramBlueprintNbt.encodeCaptured(nonAe));
        SimpleAssert.eq(
            "old non-AE owner",
            rawCapture.getString("ownerUUID"),
            "standalone level 0 capture retains its original actor identity");
        SimpleAssert.eq(
            "",
            captureDecoded.getTagList("parts", 10)
                .getCompoundTagAt(0)
                .getString("ownerUUID"),
            "mandatory AE multipart canonicalization still resets non-AE part ownership and requires level 1");
        NBTTagCompound explicitOne = (NBTTagCompound) rawCapture.copy();
        HologramDeidentification.project(explicitOne, 1);
        SimpleAssert.that(
            explicitOne.getString("ownerUUID")
                .isEmpty()
                && explicitOne.getCompoundTag("nested")
                    .equals(nested),
            "explicit level 1 clears device identity without traversing opaque nested recipe configuration");
        UUID actor = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        NBTTagCompound placement = HologramBlueprintNbt.canonicalMultipartPayload(source, actor, "builder", false);
        HologramBlueprintNbt.bindTarget(placement, 10, 64, 20);
        NBTTagCompound legacy = HologramBlueprintNbt
            .forPlacement(HologramBlueprintNbt.encodeCanonical(source), actor, "builder", 10, 64, 20, true, true);
        SimpleAssert.eq(
            actor.toString(),
            placement.getTagList("parts", 10)
                .getCompoundTagAt(0)
                .getString("ownerUUID"),
            "explicit native multipart part root is rebound to the placing actor");
        SimpleAssert.that(
            placement.getTagList("parts", 10)
                .getCompoundTagAt(0)
                .getCompoundTag("nested")
                .equals(nested),
            "opaque non-AE part configuration is preserved during actor binding");
        SimpleAssert.that(
            placement.getTagList("parts", 10)
                .getCompoundTagAt(1)
                .equals(ae),
            "AE recipe owner/UUID/coordinates remain intact during actor binding");
        SimpleAssert.eq(
            legacy.getInteger("x"),
            placement.getInteger("x"),
            "FMP root target x follows existing placement policy");
        SimpleAssert.eq(
            legacy.getInteger("y"),
            placement.getInteger("y"),
            "FMP root target y follows existing placement policy");
        SimpleAssert.eq(
            legacy.getInteger("z"),
            placement.getInteger("z"),
            "FMP root target z follows existing placement policy");
        SimpleAssert.eq(
            17,
            placement.getTagList("parts", 10)
                .getCompoundTagAt(0)
                .getInteger("x"),
            "legacy nested coordinates are not guessed or recursively rewritten");
        NBTTagCompound noCovers = HologramBlueprintNbt.canonicalMultipartPayload(source, actor, "builder", true);
        SimpleAssert.that(
            !noCovers.getTagList("parts", 10)
                .getCompoundTagAt(0)
                .hasKey("cover"),
            "non-AE cover stripping retains established behavior");
        SimpleAssert.that(
            noCovers.getTagList("parts", 10)
                .getCompoundTagAt(1)
                .equals(ae),
            "cover option does not recursively strip legal AE encoded recipe tags");
        Method filter = HologramBlueprintNbt.class
            .getDeclaredMethod("filter", NBTTagCompound.class, List.class, boolean.class);
        filter.setAccessible(true);
        NBTTagCompound selected = (NBTTagCompound) filter
            .invoke(null, source, Collections.singletonList("parts"), true);
        SimpleAssert.that(
            selected.hasKey(HologramAeBlueprint.UNRESOLVED, 9),
            "NBT selection cannot forget an already omitted unresolved marker");
        NBTTagCompound filtered = HologramBlueprintNbt.canonicalMultipartPayload(selected, null, "", false);
        SimpleAssert.that(
            filtered.getTagList("parts", 10)
                .getCompoundTagAt(1)
                .equals(ae),
            "filtered transport keeps AE recipe exact");
        SimpleAssert.that(
            filtered.getTag(HologramAeBlueprint.UNRESOLVED)
                .equals(unresolved),
            "typed loss summary remains exact");
        SimpleAssert
            .that(source.equals(original), "capture/filter/placement do not mutate mixed FMP source parts or root");
    }

    private static NBTTagCompound clear(long seq) {
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("editSeq", seq);
        request.setLong("planRevision", 9);
        request.setString("token", "fixture token");
        request.setString("slotId", "inv.item0");
        request.setString("mutation", "CLEAR");
        return request;
    }

    private interface Attempt {

        void run() throws Exception;
    }

    private static void rejects(Attempt attempt, String label) throws Exception {
        boolean rejected = false;
        try {
            attempt.run();
        } catch (IOException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, label);
    }
}
