package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityChest;

import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Exercises the production material-confirmation boundary without allowing any world writes. */
public final class HologramWorldServiceRegression {

    private HologramWorldServiceRegression() {}

    public static void run() {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(FeedbackPlayer.class);
            for (Field field : EntityPlayer.class.getDeclaredFields()) {
                if (field.getType() != GameProfile.class) continue;
                field.setAccessible(true);
                field.set(
                    player,
                    new GameProfile(UUID.fromString("204d8323-b5d7-42db-8d11-273ce72bc4a5"), "WorldFlowFixture"));
            }
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            player.worldObj = world;
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            player.capabilities.allowEdit = true;
            player.posY = 64;
            player.inventory.mainInventory[0] = new ItemStack(
                new com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector());
            Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
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
            set(session, "virtualPlacement", true);
            set(session, "blueprintAwaitingConfirmation", true);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.id = "world_confirmation_fixture";
            preset.name = "confirmation fixture";
            preset.controllerType = "area";
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, false));
            set(session, "virtualPreset", preset);
            HologramMaterials.Settings settings = (HologramMaterials.Settings) get(session, "materialSettings");
            settings.main = false;
            settings.containers = false;
            settings.me = false;
            set(session, "materialContext", new HologramMaterials.Context(player, settings));
            Method populate = HologramBlueprintProjection.class.getDeclaredMethod("populateVirtual", type);
            populate.setAccessible(true);
            populate.invoke(null, session);
            unavailableQuickSelectionKeepsDraft(session, player);
            completedJobQuickPlacementGuards(session, player);
            cancelLockedPlacementKeepsDraft(session, player);
            controllerOpeningPurpose(session, player, world);
            replacementActorCannotReuseSession(unsafe, player, world, ctor);
            Method action = HologramAuthoringWorkflow.class
                .getDeclaredMethod("blueprintAction", type, NBTTagCompound.class, String.class);
            action.setAccessible(true);
            NBTTagCompound request = new NBTTagCompound();
            request.setString("session", (String) get(session, "id"));
            request.setLong("planRevision", (Long) get(session, "planRevision"));
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "moving preview cannot confirm construction");
            SimpleAssert.eq(false, get(session, "virtualLocked"), "rejected confirm never locks position");
            set(session, "virtualLocked", true);
            request.setString("session", "stale-session");
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "stale session cannot confirm construction");
            request.setString("session", (String) get(session, "id"));
            request.setLong("planRevision", (Long) get(session, "planRevision") - 1);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "stale material plan cannot start construction");
            request.setLong("planRevision", (Long) get(session, "planRevision"));
            positionLockRequiresSeparateWorldReview(session, player, request, action);
            placementPolicyIsIdempotentAndKeepsConfirmation(session, action);
            resourceSchemaFailureIsVisible(player);
            satisfiedPlanIsAnExplicitNoop(player, world);
            request.setLong("planRevision", (Long) get(session, "planRevision"));
            request.setBoolean("forceExplicit", true);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert
                .eq(false, get(session, "forcePlacementReady"), "missing review nonce cannot issue force authority");
            SimpleAssert.that(
                ((String) get(session, "status")).contains("材料确认已更新"),
                "the outer confirmation packet must obtain the policy ACK's current review nonce");
            long reviewedNonce = (Long) get(session, "placementReviewNonce");
            request.setLong("placementReviewNonce", reviewedNonce - 1);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "an old nonce cannot start partial construction");
            SimpleAssert.eq(false, get(session, "forcePlacementReady"), "an old nonce cannot issue force authority");
            // The policy helper used its own ACK packet. Refresh this independent packet exactly as
            // the client does after receiving the updated review, rather than bypassing nonce admission.
            request.setLong("placementReviewNonce", reviewedNonce);
            request.setBoolean("forceExplicit", false);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "a request without partial consent has no partial authority");
            SimpleAssert.eq(
                false,
                get(session, "forcePlacementReady"),
                "a rejected request never creates reusable partial authority");
            SimpleAssert
                .eq(true, get(session, "blueprintAwaitingConfirmation"), "shortage keeps material review active");
            request.setBoolean("forceExplicit", false);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "repeated ordinary packet cannot exercise force capability");
            request.setBoolean("forceExplicit", true);
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(1, get(session, "job"), "one explicit partial review schedules protected construction");
            SimpleAssert
                .eq(true, get(session, "virtualLocked"), "material confirmation keeps selected position locked");
            SimpleAssert.eq(
                false,
                get(session, "blueprintAwaitingConfirmation"),
                "successful material confirmation closes review");
            matchingTileNeedsNoHarvestAuthority(session, world);
            wholePlacementDoesNotFollowInspection(session);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            throw new AssertionError("production world confirmation regression", cause);
        }
    }

    private static void placementPolicyIsIdempotentAndKeepsConfirmation(Object session, Method action)
        throws ReflectiveOperationException {
        long revision = (Long) get(session, "planRevision"), nonce = (Long) get(session, "placementReviewNonce");
        HologramCapture capture = (HologramCapture) get(session, "capture");
        int x = capture.cells.get(0).x, y = capture.cells.get(0).y, z = capture.cells.get(0).z;
        NBTTagCompound configure = new NBTTagCompound();
        configure.setString("session", (String) get(session, "id"));
        configure.setLong("planRevision", revision);
        configure.setLong("placementReviewNonce", nonce);
        configure.setInteger("level", 1);
        action.invoke(null, session, configure, "configurePlacementDeidentification");
        long appliedRevision = (Long) get(session, "planRevision"),
            appliedNonce = (Long) get(session, "placementReviewNonce");
        SimpleAssert.eq(revision + 1, appliedRevision, "manual placement policy produces one reviewed material plan");
        SimpleAssert.eq(nonce + 1, appliedNonce, "manual application replaces the old review nonce once");
        SimpleAssert.eq(
            true,
            get(session, "placementDeidentificationChosen"),
            "server acknowledges the placing player's own policy");
        SimpleAssert.that(
            get(session, "capture") == capture && capture.cells.get(0).x == x
                && capture.cells.get(0).y == y
                && capture.cells.get(0).z == z,
            "deidentification retains the exact locked ghost capture and world coordinates");
        SimpleAssert.eq(true, get(session, "virtualLocked"), "policy application keeps the target locked");
        SimpleAssert.eq(
            true,
            get(session, "blueprintAwaitingConfirmation"),
            "policy ACK still requires a real placement confirmation");
        action.invoke(null, session, configure, "configurePlacementDeidentification");
        SimpleAssert.eq(
            appliedRevision,
            get(session, "planRevision"),
            "retrying the exact original packet is idempotent after its first ACK");
        SimpleAssert.eq(
            appliedNonce,
            get(session, "placementReviewNonce"),
            "a stale same-policy retry cannot expire the new review");
        configure.setLong("planRevision", appliedRevision);
        for (int i = 0; i < 20; i++) action.invoke(null, session, configure, "configurePlacementDeidentification");
        SimpleAssert
            .eq(appliedRevision, get(session, "planRevision"), "duplicate preference requests do not refresh the plan");
        SimpleAssert.eq(
            appliedNonce,
            get(session, "placementReviewNonce"),
            "duplicate preference requests do not revoke the reviewed nonce");
        Object oldCompleted = get(session, "completed");
        set(session, "job", 3);
        set(session, "completed", 7);
        String appliedStatus = (String) get(session, "status");
        set(session, "status", "actual terminal world result");
        action.invoke(null, session, configure, "configurePlacementDeidentification");
        SimpleAssert.eq(3, get(session, "job"), "a duplicate policy cannot reset a completed world job to idle");
        SimpleAssert.eq(7, get(session, "completed"), "a duplicate policy cannot erase actual completed-cell counts");
        SimpleAssert.eq(
            "actual terminal world result",
            get(session, "status"),
            "idempotent policy keeps actual rejection or completion feedback");
        set(session, "job", 0);
        set(session, "completed", oldCompleted);
        set(session, "status", appliedStatus);
        configure.setBoolean("forceExplicit", false);
        action.invoke(null, session, configure, "confirmBlueprintPlacement");
        SimpleAssert
            .eq(false, get(session, "forcePlacementReady"), "an old review nonce cannot authorize construction");
        configure.setLong("placementReviewNonce", appliedNonce);
        action.invoke(null, session, configure, "confirmBlueprintPlacement");
        SimpleAssert.eq(0, get(session, "job"), "a policy ACK does not invent successful world placement");
        SimpleAssert.eq(
            false,
            get(session, "forcePlacementReady"),
            "a genuine review without partial consent returns the actual missing-material gate");
        SimpleAssert.that(
            ((String) get(session, "status")).contains("明确确认"),
            "confirmation returns an explicit shortage result the user can act on");
        set(session, "forcePlacementReady", false);
    }

    private static void resourceSchemaFailureIsVisible(EntityPlayerMP player) {
        try {
            HologramSession session = new HologramSession(player, 0, 64, 0, false, 1);
            session.virtual = session.virtualPlacement = session.virtualLocked = true;
            session.materialSettings.main = session.materialSettings.containers = session.materialSettings.me = false;
            session.materialContext = new HologramMaterials.Context(player, session.materialSettings);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.name = "resource schema rejection";
            preset.controllerType = "area";
            HologramPresets.Cell unsupported = HologramPresets.cell(new ItemStack(Blocks.chest), 1, 0, 0, -1, false);
            NBTTagCompound raw = new NBTTagCompound();
            raw.setString("id", "Chest");
            raw.setTag("customAsset", new ItemStack(net.minecraft.init.Items.diamond).writeToNBT(new NBTTagCompound()));
            unsupported.tileNbt = HologramBlueprintNbt.encodeCanonical(raw);
            preset.cells.add(unsupported);
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 2, 0, 0, -1, false));
            session.virtualPreset = preset;
            HologramBlueprintProjection.populateVirtual(session);
            HologramCapture.Cell invalid = session.capture.cells.get(0);
            SimpleAssert.that(
                !invalid.resourceError.isEmpty() && invalid.resourceError.contains("customAsset"),
                "the actual resource parser rejects an unaudited physical asset");
            SimpleAssert.eq(
                "pending",
                session.capture.cells.get(1).status,
                "the other real blueprint cell is independently placeable");
            HologramPlacementJobs.start(session);
            SimpleAssert.eq(0, session.job, "unsafe resource data blocks the entire world job before any writes");
            SimpleAssert.that(
                session.status.contains(invalid.resourceError) && !session.status.contains("背包无法容纳回收"),
                "the user sees the actual resource schema failure rather than a false inventory-capacity diagnosis");
            SimpleAssert.eq(0, session.completed, "rejected schema does not manufacture completed cells");
        } catch (java.io.IOException failure) {
            throw new AssertionError("resource rejection fixture", failure);
        }
    }

    private static void satisfiedPlanIsAnExplicitNoop(EntityPlayerMP player,
        HologramServiceEditRegression.ReadOnlyWorld world) {
        net.minecraft.block.Block oldBlock = world.occupied;
        int oldX = world.occupiedX;
        net.minecraft.tileentity.TileEntity oldTile = world.occupant;
        try {
            world.occupied = Blocks.stone;
            world.occupiedX = 1;
            world.occupant = null;
            HologramSession s = new HologramSession(player, 0, 64, 0, false, 1);
            s.virtual = s.virtualPlacement = s.virtualLocked = true;
            s.materialSettings.main = s.materialSettings.containers = s.materialSettings.me = false;
            s.materialContext = new HologramMaterials.Context(player, s.materialSettings);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.name = "already satisfied world";
            preset.controllerType = "area";
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 1, 0, 0, -1, false));
            s.virtualPreset = preset;
            HologramBlueprintProjection.populateVirtual(s);
            SimpleAssert.eq("satisfied", s.capture.cells.get(0).status, "native classifier sees the existing target");
            HologramPlacementJobs.start(s);
            SimpleAssert.eq(0, s.job, "a satisfied plan does not manufacture a running or completed job");
            SimpleAssert.eq(0, s.completed, "NOOP does not claim a written cell");
            SimpleAssert.that(
                s.status.contains("已满足") && s.status.contains("未扣材料"),
                "no-work confirmation explicitly explains that the target already exists without payment");
        } finally {
            world.occupied = oldBlock;
            world.occupiedX = oldX;
            world.occupant = oldTile;
        }
    }

    /** Exercise the actual block/air server entry points, retaining the intent during pending collection. */
    private static void controllerOpeningPurpose(Object session, EntityPlayerMP player,
        HologramServiceEditRegression.ReadOnlyWorld world) throws ReflectiveOperationException {
        Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Object> sessions = (java.util.Map<UUID, Object>) sessionsField.get(null);
        Object previous = sessions.put(player.getUniqueID(), session);
        String[] fields = { "virtual", "target", "context", "capturePending", "open", "openPurpose", "job",
            "blueprintAwaitingConfirmation", "placementReviewIssued", "presetBrowseForPlacement", "settingsOnly" };
        Object[] original = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) original[i] = get(session, fields[i]);
        net.minecraft.tileentity.TileEntity originalTile = world.occupant;
        int originalX = world.occupiedX;
        try {
            ControllerTile controller = new ControllerTile();
            world.occupiedX = 0;
            world.occupant = controller;
            set(session, "virtual", false);
            set(session, "target", controller);
            set(session, "context", controller);
            set(session, "capturePending", true);
            set(session, "job", 0);
            set(session, "openPurpose", "");
            HologramService.openWorld(player, 0, 64, 0, 1);
            SimpleAssert.eq(
                "controllerStructure",
                get(session, "openPurpose"),
                "actual controller block entry carries structure-tab intent even before capture completes");
            SimpleAssert.eq(true, get(session, "open"), "controller entry requests the real workspace");
            HologramService.openAir(player);
            SimpleAssert
                .eq("", get(session, "openPurpose"), "ordinary air entry leaves remembered tab choice to the client");
            SimpleAssert.that(
                sessions.get(player.getUniqueID()) == session,
                "cached controller opening and ordinary air reopening retain the valid session");
        } finally {
            world.occupant = originalTile;
            world.occupiedX = originalX;
            for (int i = 0; i < fields.length; i++) set(session, fields[i], original[i]);
            if (previous == null) sessions.remove(player.getUniqueID());
            else sessions.put(player.getUniqueID(), previous);
        }
    }

    private static final class ControllerTile extends net.minecraft.tileentity.TileEntity
        implements com.gtnewhorizon.structurelib.alignment.constructable.IConstructable {

        @Override
        public void construct(ItemStack trigger, boolean hintsOnly) {
            throw new AssertionError("cached controller opening must not construct or rescan the fixture");
        }

        @Override
        public String[] getStructureDescription(ItemStack trigger) {
            return new String[] { "controller opening fixture" };
        }
    }

    private static void cancelLockedPlacementKeepsDraft(Object session, EntityPlayerMP player)
        throws ReflectiveOperationException {
        Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Object> sessions = (java.util.Map<UUID, Object>) sessionsField.get(null);
        Object originalSession = sessions.put(player.getUniqueID(), session);
        String[] fields = { "virtualPlacement", "virtualLocked", "blueprintAwaitingConfirmation",
            "placementReviewIssued", "forcePlacementReady", "openPurpose", "placementReviewNonce", "blueprintPreview",
            "presetBrowseForPlacement", "job", "open", "uiSequence" };
        Object[] original = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) original[i] = get(session, fields[i]);
        Object draft = get(session, "virtualPreset"), capture = get(session, "capture");
        Object originalScope = get(session, "scope"), originalRevision = get(session, "planRevision");
        ItemStack originalHeld = player.getHeldItem();
        HologramAeProtocol.State ae = (HologramAeProtocol.State) get(session, "ae");
        HologramAeProtocol.Review originalReview = ae.review;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", (String) get(session, "id"));
        request.setLong("planRevision", (Long) get(session, "planRevision"));
        try {
            set(session, "virtualLocked", true);
            for (int job : new int[] { 1, 2, 4 }) {
                set(session, "job", job);
                HologramService.cancelWorldPlacement(player, request);
                SimpleAssert
                    .eq(true, get(session, "virtualPlacement"), "world Escape cannot cancel active or cancelled jobs");
                SimpleAssert.eq(true, get(session, "virtualLocked"), "rejected job cancellation keeps its lock");
                SimpleAssert.eq(job, get(session, "job"), "Escape preserves guarded job state");
            }
            set(session, "job", 0);
            for (int state = 0; state < 3; state++) {
                boolean locked = state > 0, reviewed = state == 2;
                set(session, "virtualPlacement", true);
                set(session, "virtualLocked", locked);
                set(session, "blueprintAwaitingConfirmation", locked);
                set(session, "placementReviewIssued", reviewed);
                set(session, "forcePlacementReady", reviewed);
                set(session, "openPurpose", reviewed ? "placementReview" : "");
                set(session, "placementReviewNonce", 0L);
                ae.review = new HologramAeProtocol.Review(
                    (String) get(session, "id"),
                    (String) get(session, "toolId"),
                    (Long) get(session, "planRevision"),
                    "saveBlueprint",
                    false,
                    new HologramAePolicy.Draft((HologramPresets.Preset) draft, java.util.Collections.emptyList()),
                    new net.minecraft.nbt.NBTTagList());
                HologramAeProtocol.Review review = ae.review;
                request.setLong("planRevision", (Long) get(session, "planRevision") - 1);
                HologramService.cancelWorldPlacement(player, request);
                SimpleAssert.eq(true, get(session, "virtualPlacement"), "stale Escape cannot cancel world placement");
                SimpleAssert.eq(locked, get(session, "virtualLocked"), "stale Escape preserves locked or moving state");
                SimpleAssert
                    .eq(0L, get(session, "placementReviewNonce"), "stale Escape cannot expire review authority");
                SimpleAssert.that(ae.review == review, "stale Escape preserves the existing AE review");
                request.setLong("planRevision", (Long) get(session, "planRevision"));
                HologramService.cancelWorldPlacement(player, request);
                for (String field : new String[] { "virtualPlacement", "virtualLocked", "blueprintAwaitingConfirmation",
                    "placementReviewIssued", "forcePlacementReady", "presetBrowseForPlacement" })
                    SimpleAssert.eq(false, get(session, field), "valid Escape revokes placement state " + field);
                SimpleAssert.eq("", get(session, "openPurpose"), "Escape clears the placement review open purpose");
                SimpleAssert.eq(
                    locked ? 1L : 0L,
                    get(session, "placementReviewNonce"),
                    "Escape expires locked authority while preserving the fresh moving nonce contract");
                SimpleAssert.that(ae.review == null, "Escape invalidates pending AE review authority");
                SimpleAssert
                    .eq(true, get(session, "blueprintPreview"), "Escape returns to editing the same blueprint draft");
                SimpleAssert.eq(false, get(session, "open"), "green and cyan Escape exit without opening the UI");
                SimpleAssert.that(
                    sessions.get(player.getUniqueID()) == session && get(session, "virtualPreset") == draft
                        && get(session, "capture") == capture,
                    "Escape retains the exact session, draft and capture");
                SimpleAssert.eq(0, get(session, "job"), "Escape never schedules construction");
                SimpleAssert.eq(originalScope, get(session, "scope"), "Escape preserves the draft construction scope");
                SimpleAssert
                    .eq(originalRevision, get(session, "planRevision"), "Escape never replans or rewrites the draft");
                SimpleAssert.eq(1, player.getHeldItem().stackSize, "Escape consumes no resources");
                HologramService.cancelWorldPlacement(player, request);
                SimpleAssert.eq(
                    locked ? 1L : 0L,
                    get(session, "placementReviewNonce"),
                    "replayed Escape cannot expire a second capability");
            }
            set(session, "virtualPlacement", true);
            set(session, "virtualLocked", true);
            set(session, "uiSequence", 42L);
            request.setLong("planRevision", (Long) get(session, "planRevision") - 1);
            request.setLong("positionLockSequence", 41);
            HologramService.cancelWorldPlacement(player, request);
            SimpleAssert.eq(true, get(session, "virtualLocked"), "another lock identity cannot relax revision fencing");
            request.setLong("positionLockSequence", 42);
            player.inventory.mainInventory[player.inventory.currentItem] = new ItemStack(Blocks.stone);
            HologramService.cancelWorldPlacement(player, request);
            SimpleAssert.eq(false, get(session, "virtualLocked"), "Escape cancels its own in-flight moved lock");
            SimpleAssert.eq(false, get(session, "virtualPlacement"), "correlated Escape exits the placement preview");
            SimpleAssert.eq(0, get(session, "job"), "cancel race cannot start construction");
        } finally {
            player.inventory.mainInventory[player.inventory.currentItem] = originalHeld;
            for (int i = 0; i < fields.length; i++) set(session, fields[i], original[i]);
            ae.review = originalReview;
            if (originalSession == null) sessions.remove(player.getUniqueID());
            else sessions.put(player.getUniqueID(), originalSession);
        }
    }

    /** Pure guard coverage; full preset reload and fresh material contexts are exercised by the FML/socket suites. */
    private static void completedJobQuickPlacementGuards(Object session, EntityPlayerMP player)
        throws ReflectiveOperationException {
        Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Object> sessions = (java.util.Map<UUID, Object>) sessionsField.get(null);
        Object originalSession = sessions.get(player.getUniqueID());
        Object originalJob = get(session, "job"), originalLocked = get(session, "virtualLocked");
        Object originalAwaiting = get(session, "blueprintAwaitingConfirmation");
        Object originalIssued = get(session, "placementReviewIssued");
        String originalSelection = player.getHeldItem()
            .getTagCompound()
            .getString("gtitHologramPreset");
        Object draft = get(session, "virtualPreset"), capture = get(session, "capture");
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("x", 3);
        request.setInteger("y", 300);
        request.setInteger("z", 0);
        request.setInteger("side", 1);
        try {
            player.getHeldItem()
                .getTagCompound()
                .setString("gtitHologramPreset", "world_completed_quick_guard_fixture");
            for (int job : new int[] { 0, 1, 2, 3, 5 }) {
                Object old = session;
                set(old, "virtualLocked", true);
                set(old, "job", job);
                set(old, "blueprintAwaitingConfirmation", false);
                sessions.put(player.getUniqueID(), old);
                if (job == 3 || job == 5) {
                    set(old, "placementReviewIssued", true);
                    long revision = (Long) get(old, "planRevision"), nonce = (Long) get(old, "placementReviewNonce");
                    HologramService.openAir(player);
                    HologramService.openWorld(player, 0, 64, 0, 1);
                    SimpleAssert.eq(job, get(old, "job"), "reopening a completed result keeps its terminal job state");
                    SimpleAssert
                        .eq(revision, get(old, "planRevision"), "reopening completed results never replans placement");
                    SimpleAssert.eq(
                        nonce,
                        get(old, "placementReviewNonce"),
                        "completed results do not issue a placement review nonce");
                    SimpleAssert.eq(
                        false,
                        get(old, "blueprintAwaitingConfirmation"),
                        "completed results do not reopen material confirmation");
                }
                HologramService.quickPlaceLastBlueprint(player, request);
                Object next = sessions.get(player.getUniqueID());
                if (job == 0 || job == 1 || job == 2) {
                    SimpleAssert
                        .that(next == old, "unconfirmed, running and paused placements block a replacement shortcut");
                    SimpleAssert.eq(job, get(old, "job"), "blocked replacement shortcut preserves the current job");
                    SimpleAssert.that(
                        ((FeedbackPlayer) player).message.contains("请先完成"),
                        "unconfirmed, running and paused placement guards reject before target validation");
                } else {
                    SimpleAssert.that(
                        ((FeedbackPlayer) player).message.contains("快捷放置位置"),
                        "completed and partial completed jobs reach normal shortcut target validation");
                    SimpleAssert.that(next == old, "invalid repeat target preserves the completed session");
                    SimpleAssert
                        .eq(job, get(old, "job"), "invalid repeat target leaves the finished job record intact");
                }
                SimpleAssert.that(
                    get(old, "virtualPreset") == draft && get(old, "capture") == capture,
                    "shortcut guard failure keeps the exact draft and capture for every job state");
                SimpleAssert.eq(
                    1,
                    player.getHeldItem().stackSize,
                    "shortcut guard failures consume no materials for any job state");
            }
        } finally {
            set(session, "job", originalJob);
            set(session, "virtualLocked", originalLocked);
            set(session, "blueprintAwaitingConfirmation", originalAwaiting);
            set(session, "placementReviewIssued", originalIssued);
            if (originalSession == null) sessions.remove(player.getUniqueID());
            else sessions.put(player.getUniqueID(), originalSession);
            if (originalSelection.isEmpty()) player.getHeldItem()
                .getTagCompound()
                .removeTag("gtitHologramPreset");
            else player.getHeldItem()
                .getTagCompound()
                .setString("gtitHologramPreset", originalSelection);
        }
    }

    private static void unavailableQuickSelectionKeepsDraft(Object session, EntityPlayerMP player)
        throws ReflectiveOperationException {
        Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Object> sessions = (java.util.Map<UUID, Object>) sessionsField.get(null);
        Object previous = sessions.put(player.getUniqueID(), session);
        Object draft = get(session, "virtualPreset"), capture = get(session, "capture");
        NBTTagCompound request = new NBTTagCompound();
        request.setInteger("x", 0);
        request.setInteger("y", 64);
        request.setInteger("z", 0);
        request.setInteger("side", 1);
        set(session, "virtualPlacement", false);
        try {
            HologramService.quickPlaceLastBlueprint(player, request);
            SimpleAssert.that(
                ((FeedbackPlayer) player).message.contains("蓝图库"),
                "empty last selection explains the library entry");
            String missing = "e1f4dada-6371-45a6-886f-53a145d5159f";
            player.getHeldItem()
                .getTagCompound()
                .setString("gtitHologramPreset", missing);
            HologramService.quickPlaceLastBlueprint(player, request);
            SimpleAssert.that(
                ((FeedbackPlayer) player).message.contains("蓝图不存在"),
                "deleted last blueprint gives explicit feedback: " + ((FeedbackPlayer) player).message);
            SimpleAssert
                .that(sessions.get(player.getUniqueID()) == session, "missing last selection retains the old session");
            SimpleAssert.that(
                get(session, "virtualPreset") == draft && get(session, "capture") == capture,
                "failed shortcut keeps the exact editable draft and capture");
            SimpleAssert.eq(
                missing,
                player.getHeldItem()
                    .getTagCompound()
                    .getString("gtitHologramPreset"),
                "missing shortcut never silently selects another blueprint");
            SimpleAssert.eq(0, get(session, "job"), "missing shortcut cannot authorize construction");
            SimpleAssert.eq(1, player.getHeldItem().stackSize, "shortcut failure consumes no materials");
        } finally {
            player.getHeldItem()
                .getTagCompound()
                .removeTag("gtitHologramPreset");
            set(session, "virtualPlacement", true);
            if (previous == null) sessions.remove(player.getUniqueID());
            else sessions.put(player.getUniqueID(), previous);
        }
    }

    private static final class FeedbackPlayer extends EntityPlayerMP {

        String message;

        private FeedbackPlayer() {
            super(null, null, null, null);
        }

        @Override
        public void addChatMessage(net.minecraft.util.IChatComponent component) {
            message = component.getUnformattedText();
        }
    }

    private static void positionLockRequiresSeparateWorldReview(Object session, EntityPlayerMP player,
        NBTTagCompound request, Method action) throws ReflectiveOperationException {
        Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Object> sessions = (java.util.Map<UUID, Object>) sessionsField.get(null);
        Object previous = sessions.put(player.getUniqueID(), session);
        HologramPresets.Preset preset = (HologramPresets.Preset) get(session, "virtualPreset");
        try {
            set(session, "virtualLocked", false);
            set(session, "blueprintAwaitingConfirmation", false);
            request.setInteger("x", 0);
            request.setInteger("y", 64);
            request.setInteger("z", 0);
            request.setInteger("side", 1);
            long sequence = (Long) get(session, "uiSequence") + 1;
            request.setLong("uiSequence", sequence);
            HologramService.confirmWorldPosition(player, request);
            SimpleAssert.eq(sequence, get(session, "uiSequence"), "world lock ACK carries its real command identity");
            SimpleAssert.eq(true, get(session, "virtualLocked"), "first right click locks the world preview");
            SimpleAssert
                .eq(false, get(session, "placementReviewIssued"), "first lock cannot issue material UI authority");
            SimpleAssert.eq(false, get(session, "open"), "first lock never queues an open UI");
            long firstNonce = (Long) get(session, "placementReviewNonce");
            HologramService.confirmWorldPosition(player, request);
            SimpleAssert
                .eq(firstNonce, get(session, "placementReviewNonce"), "duplicate lock delivery cannot issue review");
            SimpleAssert.eq(0, get(session, "job"), "duplicate lock delivery cannot begin construction");
            request.setLong("planRevision", (Long) get(session, "planRevision"));
            action.invoke(null, session, request, "confirmBlueprintPlacement");
            SimpleAssert.eq(0, get(session, "job"), "forged confirm after lock cannot bypass the second world click");
            long nonce = (Long) get(session, "placementReviewNonce");
            request.setLong("planRevision", (Long) get(session, "planRevision") - 1);
            HologramService.unlockWorldPosition(player, request);
            SimpleAssert.eq(true, get(session, "virtualLocked"), "stale unlock cannot change locked position");
            request.setLong("planRevision", (Long) get(session, "planRevision"));
            set(session, "forcePlacementReady", true);
            HologramService.unlockWorldPosition(player, request);
            SimpleAssert
                .eq(false, get(session, "virtualLocked"), "Shift right unlocks without beginning area selection");
            SimpleAssert
                .eq(false, get(session, "blueprintAwaitingConfirmation"), "unlock revokes placement confirmation");
            SimpleAssert.eq(false, get(session, "placementReviewIssued"), "unlock revokes issued material review");
            SimpleAssert.eq(false, get(session, "forcePlacementReady"), "unlock revokes material shortage override");
            SimpleAssert.that((Long) get(session, "placementReviewNonce") > nonce, "unlock expires the review nonce");
            SimpleAssert.that(get(session, "virtualPreset") == preset, "unlock keeps the exact edited blueprint draft");
            request.setLong("planRevision", (Long) get(session, "planRevision"));
            HologramService.confirmWorldPosition(player, request);
            SimpleAssert.eq(false, get(session, "virtualLocked"), "a retired sequence cannot relock after unlock");
            request.setLong("uiSequence", sequence + 1);
            HologramService.confirmWorldPosition(player, request);
            SimpleAssert.eq(true, get(session, "virtualLocked"), "preview can lock again after Shift right");
            nonce = (Long) get(session, "placementReviewNonce");
            HologramService.openWorld(player, 0, 64, 0, 1);
            SimpleAssert.eq(true, get(session, "libraryUiActive"), "second right publishes the progress UI shell");
            SimpleAssert.that(
                (Boolean) get(session, "placementReviewIssued") || (Boolean) get(session, "placementValidating"),
                "second right either reuses a complete quote or starts budgeted validation");
            SimpleAssert.that(
                sessions.get(player.getUniqueID()) == session,
                "locked placement retains its session on block use");
            SimpleAssert
                .that((Long) get(session, "placementReviewNonce") > nonce, "second right opens a fresh review nonce");
            SimpleAssert.eq(0, get(session, "job"), "opening material review never starts construction");
            HologramSession planning = (HologramSession) session;
            for (int slice = 0; planning.localPlanPending && slice < 128; slice++)
                HologramDeferredPlanRefresh.tick(planning, Integer.MAX_VALUE, 1_000_000_000L, ignored -> {});
            SimpleAssert.that(
                !planning.localPlanPending && planning.placementReviewIssued,
                "budgeted placement validation finishes before policy edits receive review authority");
            Method dismiss = HologramProjectorWorkflow.class
                .getDeclaredMethod("dismissPresetBrowse", session.getClass());
            dismiss.setAccessible(true);
            dismiss.invoke(null, session);
            SimpleAssert.eq(
                true,
                get(session, "blueprintAwaitingConfirmation"),
                "closing UI keeps locked placement material gate");
            request.setLong("planRevision", (Long) get(session, "planRevision"));
        } finally {
            if (previous == null) sessions.remove(player.getUniqueID());
            else sessions.put(player.getUniqueID(), previous);
        }
    }

    private static void replacementActorCannotReuseSession(sun.misc.Unsafe unsafe, EntityPlayerMP fixture,
        HologramServiceEditRegression.ReadOnlyWorld world, Constructor<?> constructor)
        throws ReflectiveOperationException {
        UUID identity = UUID.fromString("60950aae-0fc1-48a7-9b91-1fd30f61a9a5");
        GameProfile profile = new GameProfile(identity, "ReconnectFixture");
        EntityPlayerMP owner = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        EntityPlayerMP replacement = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        for (EntityPlayerMP actor : new EntityPlayerMP[] { owner, replacement }) {
            for (Field field : EntityPlayer.class.getDeclaredFields()) {
                if (field.getType() != GameProfile.class) continue;
                field.setAccessible(true);
                field.set(actor, profile);
            }
            for (Field field : Entity.class.getDeclaredFields()) {
                if (field.getType() != UUID.class) continue;
                field.setAccessible(true);
                field.set(actor, identity);
            }
            actor.worldObj = world;
            actor.inventory = new InventoryPlayer(actor);
            actor.capabilities = new PlayerCapabilities();
            actor.capabilities.allowEdit = true;
            actor.inventory.mainInventory[0] = fixture.getHeldItem()
                .copy();
        }
        SimpleAssert.that(
            owner != replacement && identity.equals(owner.getUniqueID()) && identity.equals(replacement.getUniqueID()),
            "replacement actor has the same nonnull player UUID");
        Object oldSession = constructor.newInstance(owner, 0, 64, 0, false, 2);
        set(oldSession, "open", true);
        Field sessionsField = HologramWorkspace.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Object> sessions = (java.util.Map<UUID, Object>) sessionsField.get(null);
        Object previous = sessions.put(identity, oldSession);
        ItemStack ownerStack = owner.getHeldItem()
            .copy(),
            replacementStack = replacement.getHeldItem()
                .copy();
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", (String) get(oldSession, "id"));
        request.setString("op", "close");
        request.setLong("uiSequence", 73);
        try {
            HologramService.handle(replacement, request);
            SimpleAssert.eq(0L, get(oldSession, "uiSequence"), "replacement actor cannot acknowledge the old session");
            SimpleAssert.eq(true, get(oldSession, "open"), "replacement actor cannot close the old session");
            SimpleAssert.that(
                get(oldSession, "materialContext") == null,
                "replacement actor cannot bind its inventory to the old session");
            SimpleAssert.that(
                ItemStack.areItemStacksEqual(ownerStack, owner.getHeldItem())
                    && ItemStack.areItemStacksEqual(replacementStack, replacement.getHeldItem()),
                "rejected replacement actor preserves both tool inventories");
        } finally {
            if (previous == null) sessions.remove(identity);
            else sessions.put(identity, previous);
        }
    }

    private static void matchingTileNeedsNoHarvestAuthority(Object session,
        HologramServiceEditRegression.ReadOnlyWorld world) throws ReflectiveOperationException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.controllerType = "area";
        preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.chest), 1, 0, 0, -1, false));
        set(session, "virtualPreset", preset);
        world.occupied = Blocks.chest;
        world.occupiedX = 1;
        world.occupant = new TileEntityChest();
        TileEntityChest original = (TileEntityChest) world.occupant;
        Class<?> type = session.getClass();
        Method populate = HologramBlueprintProjection.class.getDeclaredMethod("populateVirtual", type);
        populate.setAccessible(true);
        populate.invoke(null, session);
        HologramCapture.Cell cell = ((HologramCapture) get(session, "capture")).cells.get(0);
        Method harvest = HologramCellPolicy.class.getDeclaredMethod("recoverable", type, HologramCapture.Cell.class);
        harvest.setAccessible(true);
        SimpleAssert
            .eq(false, harvest.invoke(null, session, cell), "ordinary chest has no destructive harvest authority");
        Method classify = HologramPlanPolicy.class
            .getDeclaredMethod("classify", type, HologramCapture.Cell.class, int.class);
        classify.setAccessible(true);
        SimpleAssert.eq(
            "satisfied",
            classify.invoke(null, session, cell, 0),
            "already matching placed chest is kept during a partial job retry");
        SimpleAssert.that(world.occupant == original, "classification preserves the original tile instance");
        world.occupied = Blocks.furnace;
        SimpleAssert.eq(
            "blocked",
            classify.invoke(null, session, cell, 0),
            "unmatched occupied tile still blocks construction without replacement authority");
        world.occupied = Blocks.chest;
        set(session, "mode", 2);
        SimpleAssert.eq(
            "protected",
            classify.invoke(null, session, cell, 0),
            "matching tile cannot acquire demolition authority from keep classification");
    }

    private static void wholePlacementDoesNotFollowInspection(Object session) throws ReflectiveOperationException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        HologramPresets.Structure a = new HologramPresets.Structure();
        a.id = "viewed";
        a.enabled = true;
        HologramPresets.Structure b = new HologramPresets.Structure();
        b.id = "other";
        b.enabled = true;
        HologramPresets.Structure disabled = new HologramPresets.Structure();
        disabled.id = "disabled";
        disabled.enabled = false;
        preset.structures.add(a);
        preset.structures.add(b);
        preset.structures.add(disabled);
        HologramCapture capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        HologramCapture.Cell viewed = new HologramCapture.Cell(null, 0, 64, 0);
        viewed.structureId = a.id;
        HologramCapture.Cell other = new HologramCapture.Cell(null, 1, 65, 0);
        other.structureId = b.id;
        HologramCapture.Cell turnedOff = new HologramCapture.Cell(null, 2, 64, 0);
        turnedOff.structureId = disabled.id;
        capture.cells.add(viewed);
        capture.cells.add(other);
        capture.cells.add(turnedOff);
        set(session, "capture", capture);
        set(session, "virtualPreset", preset);
        set(session, "virtual", true);
        set(session, "virtualPlacement", true);
        set(session, "virtualLocked", true);
        set(session, "scope", 2);
        set(session, "selected", 0);
        set(session, "blueprintActiveStructure", a.id);
        @SuppressWarnings("unchecked")
        java.util.Set<Integer> selection = (java.util.Set<Integer>) get(session, "selectedIndices");
        selection.clear();
        selection.add(0);
        Method scope = HologramSessionPolicy.class
            .getDeclaredMethod("inScope", session.getClass(), HologramCapture.Cell.class, int.class);
        scope.setAccessible(true);
        SimpleAssert.eq(
            true,
            scope.invoke(null, session, other, 1),
            "locked placement reviews the other structure even while inspecting one selected cell");
        Method edit = HologramSessionPolicy.class
            .getDeclaredMethod("editableStructureCell", session.getClass(), HologramCapture.Cell.class);
        edit.setAccessible(true);
        SimpleAssert.eq(
            false,
            edit.invoke(null, session, other),
            "whole placement never grants cross-structure edit authority");
        SimpleAssert
            .eq(false, scope.invoke(null, session, turnedOff, 2), "explicit disabled structure remains excluded");
        other.excluded = true;
        SimpleAssert
            .eq(false, scope.invoke(null, session, other, 1), "explicit cell exclusions still protect world positions");
        other.excluded = false;
        set(session, "scope", 1);
        set(session, "layer", 0);
        SimpleAssert.eq(
            true,
            scope.invoke(null, session, other, 1),
            "preview layer cannot hide other blueprint rows from review");
        set(session, "scope", 3);
        set(session, "selected", -1);
        selection.clear();
        SimpleAssert.eq(
            true,
            scope.invoke(null, session, other, 1),
            "stale family-selection view cannot shrink a locked placement");
        set(session, "virtualPlacement", false);
        set(session, "scope", 2);
        set(session, "selected", 0);
        selection.add(0);
        SimpleAssert.eq(
            false,
            scope.invoke(null, session, other, 1),
            "pure blueprint editing retains structure and selection isolation");
        set(session, "virtual", false);
        set(session, "virtualPreset", null);
        SimpleAssert.eq(false, scope.invoke(null, session, other, 1), "normal structure selection retains batch scope");
    }

    private static Object get(Object session, String key) throws ReflectiveOperationException {
        Field field = session.getClass()
            .getDeclaredField(key);
        field.setAccessible(true);
        return field.get(session);
    }

    private static void set(Object session, String key, Object value) throws ReflectiveOperationException {
        Field field = session.getClass()
            .getDeclaredField(key);
        field.setAccessible(true);
        field.set(session, value);
    }
}
