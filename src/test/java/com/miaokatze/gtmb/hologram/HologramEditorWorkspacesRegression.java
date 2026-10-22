package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

/** Actual registry/dispatcher owner transitions; no source-text or simulated registry assertions. */
public final class HologramEditorWorkspacesRegression {

    private HologramEditorWorkspacesRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramEditorWorkspacesRegression.class, args)) return;
        run();
        System.out.println("HologramEditorWorkspacesRegression passed");
    }

    public static void run() {
        try {
            creationAndRoundTrip();
            discardConsentAndAttachmentGate();
            projectionAreaImportConsent();
            importedControllerKeepsAuthorOwnership();
            sequenceFloorRetainsExactAcknowledgement();
            epochFencesReactivatedSession();
            admissionAndOwnership();
            provisionalFailureAndCommit();
            lateRollbackFences();
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("workspace fixture", failure);
        } finally {
            HologramWorkspace.clear();
        }
    }

    private static void importedControllerKeepsAuthorOwnership() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramSession projection = fixture.projection();
            projection.planRevision = 71;
            HologramSession declaration = new HologramSession(fixture.player, 0, 64, 0, true, 2);
            declaration.areaStructureId = "imported-structure";
            declaration.uiSequence = 81;
            declaration.planRevision = 73;
            HologramWorkspace.activate(fixture.id, declaration);
            SimpleAssert.that(
                declaration.target == projection.target && declaration.context == projection.context,
                "imported declaration may legitimately share native controller references");
            SimpleAssert.eq(
                "blueprint",
                HologramEditorWorkspaceRegistry.mode(declaration),
                "a native API reference never grants an imported declaration projection ownership");
            HologramSession restored = switchTo(declaration, "projection", 82);
            SimpleAssert.that(
                restored == projection,
                "imported controller context cannot overwrite the independently owned projection registry entry");
            SimpleAssert.eq(71L, restored.planRevision, "real controller keeps its own plan revision");
            SimpleAssert.eq(73L, declaration.planRevision, "imported declaration keeps its own plan revision");
        }
    }

    private static void epochFencesReactivatedSession() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramSession projection = fixture.projection();
            long firstEpoch = projection.editorWorkspaceEpoch;
            HologramSession blueprint = switchTo(projection, "blueprint", 61);
            commit(blueprint);
            projection = switchTo(blueprint, "projection", 62);
            commit(projection);
            for (String invalid : new String[] { "old", "future", "wrongType" }) {
                NBTTagCompound action = request(projection, "blueprint", 1);
                action.setLong(
                    "editorWorkspaceEpoch",
                    "old".equals(invalid) ? firstEpoch : projection.editorWorkspaceEpoch + 1);
                if ("wrongType".equals(invalid))
                    action.setInteger("editorWorkspaceEpoch", (int) projection.editorWorkspaceEpoch);
                HologramActionDispatcher.handle(fixture.player, action);
                SimpleAssert.that(
                    HologramWorkspace.find(fixture.id) == projection,
                    "reactivated domain rejects invalid activation context: " + invalid);
                SimpleAssert.eq(
                    62L,
                    projection.uiSequence,
                    "rejected activation context cannot lower the current ACK sequence: " + invalid);
            }
            NBTTagCompound fresh = request(projection, "blueprint", 63);
            fresh.setLong("editorWorkspaceEpoch", projection.editorWorkspaceEpoch);
            HologramActionDispatcher.handle(fixture.player, fresh);
            SimpleAssert.that(
                HologramWorkspace.find(fixture.id) == blueprint,
                "current typed epoch still admits a fresh switch");
            SimpleAssert.eq(63L, blueprint.uiSequence, "fresh activation request retains its exact sequence");
        }
    }

    private static void sequenceFloorRetainsExactAcknowledgement() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramSession projection = fixture.projection();
            HologramSession blueprint = switchTo(projection, "blueprint", 41);
            commit(blueprint);
            projection = switchTo(blueprint, "projection", 42);
            commit(projection);
            blueprint.uiSequence = 9001;
            SimpleAssert.eq(
                9001L,
                HologramEditorWorkspaceRegistry.sequenceFloor(projection),
                "inactive owner publishes the sequence floor without modifying active ACK identity");
            HologramActionDispatcher.handle(fixture.player, request(projection, "blueprint", 42));
            SimpleAssert.that(
                HologramWorkspace.find(fixture.id) == projection,
                "older request cannot lower the target's command sequence");
            SimpleAssert.eq(42L, projection.uiSequence, "refusal retains the exact source command ACK");
            SimpleAssert.eq(9001L, blueprint.uiSequence, "refusal preserves inactive target command sequence");
            SimpleAssert.eq(
                9001L,
                HologramEditorWorkspaceRegistry.sequenceFloor(projection),
                "refusal still publishes a usable floor for one fresh retry");
            HologramSession restored = switchTo(projection, "blueprint", 9002);
            SimpleAssert.that(restored == blueprint, "one request above the advertised floor restores target");
            SimpleAssert.eq(9002L, restored.uiSequence, "fresh retry ACK is monotonic and exact");
        }
    }

    private static void creationAndRoundTrip() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramSession projection = fixture.projection();
            projection.channels.setInteger("projection", 17);
            projection.planRevision = 11;
            projection.selected = 4;
            long firstEpoch = projection.editorWorkspaceEpoch;
            HologramSession blueprint = switchTo(projection, "blueprint", 31);
            SimpleAssert.that(blueprint != projection, "switch allocates an independently owned blueprint session");
            SimpleAssert.that(
                blueprint.target == null && blueprint.context == null
                    && blueprint.machine == null
                    && blueprint.constructable == null,
                "blank blueprint never captures the real controller at the same coordinates");
            SimpleAssert.eq("blueprint", HologramEditorWorkspaceRegistry.mode(blueprint), "blank author domain");
            SimpleAssert.that(blueprint.virtual, "blank workspace is virtual author state");
            SimpleAssert.that(blueprint.channels != projection.channels, "channel compounds are independently owned");
            SimpleAssert.that(blueprint.planRevision != 11, "new blueprint never adopts projection revision");
            SimpleAssert.that(blueprint.editorWorkspaceEpoch > firstEpoch, "domain activation advances epoch");
            SimpleAssert.eq(31L, blueprint.uiSequence, "first target baseline carries the exact switch request");
            commit(blueprint);
            blueprint.channels.setInteger("blueprint", 29);
            blueprint.planRevision = 23;
            blueprint.selected = 7;
            long secondEpoch = blueprint.editorWorkspaceEpoch;
            HologramSession restored = switchTo(blueprint, "projection", 32);
            SimpleAssert
                .that(restored != projection && restored.emptyProjection, "return opens a new empty projection owner");
            SimpleAssert.that(projection.cancelled, "committed blueprint opening retires the discarded projection");
            SimpleAssert.that(
                restored.target == null && restored.capture == null,
                "discarded controller content never revives");
            SimpleAssert.eq(-1, restored.selected, "empty projection has no retained selection");
            SimpleAssert.that(!restored.channels.hasKey("projection"), "discarded projection channels never revive");
            SimpleAssert.that(!restored.channels.hasKey("blueprint"), "blueprint channels never leak into projection");
            SimpleAssert.that(restored.editorWorkspaceEpoch > secondEpoch, "return cannot reuse the old epoch");
            commit(restored);
            HologramSession again = switchTo(restored, "blueprint", 33);
            SimpleAssert.that(again == blueprint, "return restores exact blueprint owner");
            SimpleAssert.eq(23L, again.planRevision, "blueprint revision survives independently");
            SimpleAssert.eq(7, again.selected, "blueprint selection survives independently");
            SimpleAssert.eq(29, again.channels.getInteger("blueprint"), "blueprint channels survive");
            SimpleAssert.that(!again.channels.hasKey("projection"), "projection channels never leak into blueprint");
        }
    }

    private static void discardConsentAndAttachmentGate() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramSession source = fixture.projection();
            source.planRevision = 17;
            source.channels.setInteger("kept", 9);
            long epoch = source.editorWorkspaceEpoch;
            NBTTagCompound draft = new NBTTagCompound();
            draft.setInteger("dimension", source.dimension);
            draft.setInteger("x", source.x);
            draft.setInteger("y", source.y);
            draft.setInteger("z", source.z);
            draft.setString("controllerType", HologramSessionPolicy.controllerType(source));
            fixture.player.getHeldItem()
                .getTagCompound()
                .setTag(HologramSessionKeys.DRAFT, draft);
            HologramActionDispatcher.handle(fixture.player, request(source, "blueprint", 21));
            SimpleAssert.that(source.discardConfirmationRequired, "physical projection always requires consent");
            SimpleAssert.eq(epoch, source.editorWorkspaceEpoch, "proposal does not advance the activation epoch");
            NBTTagCompound proposal = (NBTTagCompound) source.pendingSourceAction.copy();
            NBTTagCompound stale = (NBTTagCompound) proposal.copy();
            stale.setBoolean("confirmDiscard", true);
            stale.setString("sourceSwitchNonce", "stale");
            stale.setLong("uiSequence", 22);
            HologramActionDispatcher.handle(fixture.player, stale);
            SimpleAssert
                .that(HologramWorkspace.find(fixture.id) == source, "wrong consent nonce cannot switch domains");
            proposal.setString("op", "cancelSourceSwitch");
            proposal.setLong("uiSequence", 23);
            HologramActionDispatcher.handle(fixture.player, proposal);
            SimpleAssert
                .that(!source.discardConfirmationRequired && !source.cancelled, "cancel retains the active projection");
            SimpleAssert.eq(9, source.channels.getInteger("kept"), "cancel retains projection settings");
            SimpleAssert.that(
                fixture.player.getHeldItem()
                    .getTagCompound()
                    .hasKey(HologramSessionKeys.DRAFT),
                "cancel retains the item projection memory");
            NBTTagCompound attachment = request(source, "blueprint", 24);
            attachment.setString("op", "placeTextBox");
            attachment.setLong("textRequest", 81);
            HologramActionDispatcher.handle(fixture.player, attachment);
            SimpleAssert.that(
                source.attachmentTask == null && !source.textPlacementAck.getBoolean("accepted"),
                "server rejects physical text and picture before creating geometry tasks");
            SimpleAssert.that(
                source.target == fixture.world.occupant && !source.virtual,
                "rejected attachment never converts physical projection to a blueprint");
            HologramSession author = switchTo(source, "blueprint", 25);
            SimpleAssert.that(
                !source.cancelled && fixture.player.getHeldItem()
                    .getTagCompound()
                    .hasKey(HologramSessionKeys.DRAFT),
                "provisional target preserves source and item memory for rollback");
            commit(author);
            SimpleAssert.that(
                source.cancelled && !fixture.player.getHeldItem()
                    .getTagCompound()
                    .hasKey(HologramSessionKeys.DRAFT),
                "full baseline commit retires source and clears the item projection memory");
            HologramSession empty = switchTo(author, "projection", 26);
            commit(empty);
            NBTTagCompound forbidden = request(empty, "blueprint", 27);
            forbidden.setString("op", "main");
            forbidden.setInteger("value", 7);
            HologramActionDispatcher.handle(fixture.player, forbidden);
            SimpleAssert.eq(1, empty.main, "empty projection rejects editor mutations at the server");
            SimpleAssert.that(
                empty.target == null && empty.capture == null,
                "empty mutation cannot resurrect discarded geometry");
            SimpleAssert.that(
                HologramDraftPersistence.storedDraft(source, fixture.player.getHeldItem())
                    .hasNoTags(),
                "a discarded controller cannot recover a historical server draft without the current item marker");
        }
    }

    private static void projectionAreaImportConsent() throws ReflectiveOperationException, java.io.IOException {
        try (Fixture fixture = new Fixture()) {
            HologramSession source = fixture.projection();
            source.planRevision = 43;
            source.channels.setInteger("retained", 6);
            long epoch = source.editorWorkspaceEpoch;
            HologramArea area = new HologramArea();
            area.point(1, 1, 64, 0, "replace");
            area.point(2, 2, 64, 0, "replace");
            HologramWorkspace.AREAS.put(fixture.id, area);
            NBTTagList selection = (NBTTagList) area.regionSnapshot()
                .copy();
            SimpleAssert.that(
                !HologramAreaWorkflow.startAreaImport(source, area) && source.discardConfirmationRequired,
                "world selection completion asks consent before replacing a physical projection");
            SimpleAssert.that(
                !area.importing && HologramWorkspace.find(fixture.id) == source,
                "area proposal retains the source route and defers import work");
            NBTTagCompound cancel = (NBTTagCompound) source.pendingSourceAction.copy();
            cancel.setString("op", "cancelSourceSwitch");
            cancel.setLong("uiSequence", 31);
            HologramActionDispatcher.handle(fixture.player, cancel);
            SimpleAssert.that(selection.equals(area.regionSnapshot()), "cancel retains the completed world selection");
            SimpleAssert.eq(43L, source.planRevision, "cancel never changes the projection revision");
            SimpleAssert.eq(epoch, source.editorWorkspaceEpoch, "cancel never changes the projection activation");
            SimpleAssert
                .that(!HologramAreaWorkflow.startAreaImport(source, area), "a fresh area attempt asks consent again");
            NBTTagCompound confirm = (NBTTagCompound) source.pendingSourceAction.copy();
            confirm.setBoolean("confirmDiscard", true);
            confirm.setLong("uiSequence", 32);
            HologramActionDispatcher.handle(fixture.player, confirm);
            SimpleAssert.that(
                area.importing && source.projectionAreaImportDiscard,
                "the confirmed original selection continues through the real segmented importer");
            SimpleAssert.that(
                HologramWorkspace.find(fixture.id) == source && source.target == fixture.world.occupant,
                "unfinished import preserves the exact physical session for failure or cancellation");
            SimpleAssert.eq(43L, source.planRevision, "segmented import does not mutate the physical source revision");
            HologramSession staged = HologramAreaWorkflow.importStaging(source, area);
            SimpleAssert.that(
                !staged.id.equals(source.id) && staged.virtual,
                "projection area import stages an independent author session nonce");
            SimpleAssert.eq(44L, staged.planRevision, "only the staged author plan receives the new revision");
            HologramAreaWorkflow.cancelAreaImport(source);
            SimpleAssert.that(
                !area.importing && !source.projectionAreaImportDiscard && !source.cancelled,
                "canceled area work revokes its discard authority and retains projection memory");
            SimpleAssert.eq(
                6,
                source.channels.getInteger("retained"),
                "area cancellation preserves original projection policy");
        }
    }

    private static void admissionAndOwnership() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramSession projection = fixture.projection();
            projection.planRevision = 5;
            for (String malformed : new String[] { "missing", "int", "stale", "future" }) {
                NBTTagCompound request = request(projection, "blueprint", 10);
                if ("missing".equals(malformed)) request.removeTag("planRevision");
                else if ("int".equals(malformed)) request.setInteger("planRevision", 5);
                else request.setLong("planRevision", "stale".equals(malformed) ? 4 : 6);
                HologramActionDispatcher.handle(fixture.player, request);
                SimpleAssert.that(
                    HologramWorkspace.find(fixture.id) == projection,
                    "malformed or mismatched revision preserves source: " + malformed);
            }
            for (String busy : new String[] { "running", "paused", "capture", "deferred", "placement", "review" }) {
                projection.job = "running".equals(busy) ? 1 : "paused".equals(busy) ? 2 : 0;
                projection.capturePending = "capture".equals(busy);
                projection.localPlanPending = "deferred".equals(busy);
                projection.virtualPlacement = "placement".equals(busy);
                projection.discardConfirmationRequired = "review".equals(busy);
                HologramActionDispatcher.handle(fixture.player, request(projection, "blueprint", 11));
                SimpleAssert.that(
                    HologramWorkspace.find(fixture.id) == projection,
                    "unfinished source cannot be suspended: " + busy);
            }
            projection.job = 0;
            projection.capturePending = projection.localPlanPending = projection.virtualPlacement = false;
            projection.discardConfirmationRequired = false;
            HologramSession blueprint = switchTo(projection, "blueprint", 12);
            commit(blueprint);
            fixture.world.occupant = new TileEntity();
            HologramSession empty = switchTo(blueprint, "projection", 13);
            commit(empty);
            SimpleAssert.that(
                empty.emptyProjection && empty.target == null,
                "replaced or discarded real controller opens only the empty projection page");
            HologramEditorWorkspaceRegistry.forget(fixture.id);
            HologramSession author = switchTo(empty, "blueprint", 14);
            commit(author);
            blueprint = author;
            EntityPlayerMP reconnect = fixture.newPlayer(fixture.id);
            HologramActionDispatcher.handle(reconnect, request(blueprint, "projection", 15));
            SimpleAssert.that(
                HologramWorkspace.find(fixture.id) == blueprint,
                "a replacement player object cannot control an old connection's session");
            NBTTagCompound wrongSession = request(blueprint, "projection", 16);
            wrongSession.setString("session", projection.id);
            HologramActionDispatcher.handle(fixture.player, wrongSession);
            SimpleAssert.that(
                HologramWorkspace.find(fixture.id) == blueprint,
                "old domain session nonce cannot route a current command");
        }
    }

    private static void provisionalFailureAndCommit() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            HologramSession origin = fixture.projection();
            origin.open = true;
            origin.libraryUiActive = true;
            origin.planRevision = 19;
            long oldEpoch = origin.editorWorkspaceEpoch;
            HologramSession target = switchTo(origin, "blueprint", 41);
            long failedTargetEpoch = target.editorWorkspaceEpoch;
            SimpleAssert.that(
                target.presentationTransition != null,
                "domain first baseline remains provisional until complete encoding acceptance");
            NBTTagCompound partial = baseline(target);
            partial.setBoolean("partial", true);
            HologramWorkspace.commitPresentation(target, partial);
            SimpleAssert.that(target.presentationTransition != null, "partial ACK cannot commit a domain switch");
            NBTTagCompound wrong = baseline(target);
            wrong.setLong("uiSequence", 40);
            HologramWorkspace.commitPresentation(target, wrong);
            SimpleAssert.that(target.presentationTransition != null, "earlier command cannot commit the new owner");
            HologramNetwork.transportFailure(
                fixture.player,
                target.id,
                41,
                "snapshot.encode",
                new java.io.IOException("deterministic first baseline failure"));
            SimpleAssert.that(
                HologramWorkspace.find(fixture.id) == origin,
                "real transport failure hook restores the exact prior domain route");
            SimpleAssert.that(
                origin.open && origin.libraryUiActive,
                "failed switch restores the original presentation and library capabilities");
            SimpleAssert.eq(19L, origin.planRevision, "rollback cannot mutate source plan revision");
            SimpleAssert.eq(
                oldEpoch,
                origin.editorWorkspaceEpoch,
                "unpublished baseline failure preserves the exact epoch held by the retained source UI");
            NBTTagCompound failedContext = request(origin, "blueprint", 42);
            failedContext.setLong("editorWorkspaceEpoch", failedTargetEpoch);
            HologramActionDispatcher.handle(fixture.player, failedContext);
            SimpleAssert.that(
                HologramWorkspace.find(fixture.id) == origin,
                "failed target activation never grants capability over the restored source");
            SimpleAssert.eq(41L, origin.uiSequence, "failed target epoch cannot advance the source ACK sequence");
            NBTTagCompound retry = request(origin, "blueprint", 42);
            retry.setLong("editorWorkspaceEpoch", oldEpoch);
            retry.setBoolean("confirmDiscard", true);
            retry.setString("sourceSwitchNonce", origin.pendingSourceAction.getString("sourceSwitchNonce"));
            HologramActionDispatcher.handle(fixture.player, retry);
            target = HologramWorkspace.find(fixture.id);
            SimpleAssert.that(
                target != origin && "blueprint".equals(HologramEditorWorkspaceRegistry.mode(target)),
                "retained source epoch admits a fresh request after first-baseline failure");
            SimpleAssert.that(
                target.editorWorkspaceEpoch > failedTargetEpoch,
                "fresh target activation never reuses the failed target epoch");
            SimpleAssert.eq(42L, target.uiSequence, "successful retry keeps its exact request identity");
            commit(target);
            HologramNetwork.transportFailure(
                fixture.player,
                target.id,
                42,
                "snapshot.encode",
                new java.io.IOException("late failure after accepted full baseline"));
            SimpleAssert.that(
                HologramWorkspace.find(fixture.id) == target,
                "committed display cannot be rolled back by a late encoder failure");
        }
    }

    private static void lateRollbackFences() throws ReflectiveOperationException {
        for (String changed : new String[] { "sourceSequence", "targetSequence", "sourceTask", "targetTask", "sourceId",
            "targetId", "newOwner", "tool", "world", "cancelled", "job" }) {
            try (Fixture fixture = new Fixture()) {
                HologramSession source = fixture.projection();
                HologramSession target = switchTo(source, "blueprint", 51);
                String nonce = target.id;
                HologramSession expected = target;
                if ("sourceSequence".equals(changed)) source.uiSequence++;
                else if ("targetSequence".equals(changed)) target.uiSequence++;
                else if ("sourceTask".equals(changed)) source.blueprintClientTaskToken++;
                else if ("targetTask".equals(changed)) target.blueprintClientTaskToken++;
                else if ("sourceId".equals(changed)) source.id = UUID.randomUUID()
                    .toString();
                else if ("targetId".equals(changed)) target.id = UUID.randomUUID()
                    .toString();
                else if ("newOwner".equals(changed)) {
                    expected = new HologramSession(fixture.player, 0, 64, 0, false, 2);
                    HologramWorkspace.activate(fixture.id, expected);
                } else
                    if ("tool".equals(changed)) fixture.player.inventory.mainInventory[0] = new ItemStack(Blocks.dirt);
                    else if ("world".equals(changed)) fixture.player.worldObj = null;
                    else if ("cancelled".equals(changed)) target.cancelled = true;
                    else target.job = 1;
                SimpleAssert.that(
                    HologramWorkspace.rollbackPresentation(fixture.player, nonce, 51) == null,
                    "delayed failure cannot restore stale context after " + changed);
                SimpleAssert.that(
                    HologramWorkspace.find(fixture.id) == expected,
                    "delayed failure preserves current owner after " + changed);
                fixture.player.worldObj = fixture.world;
            }
        }
    }

    private static NBTTagCompound request(HologramSession source, String mode, long sequence) {
        source.actionCount = 0;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", source.id);
        request.setString("op", "switchEditorWorkspace");
        request.setString("editorWorkspace", mode);
        request.setLong("planRevision", source.planRevision);
        request.setLong("editorWorkspaceEpoch", source.editorWorkspaceEpoch);
        request.setLong("uiSequence", sequence);
        return request;
    }

    private static HologramSession switchTo(HologramSession source, String mode, long sequence) {
        NBTTagCompound action = request(source, mode, sequence);
        if ("projection".equals(HologramEditorWorkspaceRegistry.mode(source)) && !source.emptyProjection
            && "blueprint".equals(mode)) {
            HologramActionDispatcher.handle(source.player, action);
            SimpleAssert.that(
                HologramWorkspace.find(source.player.getUniqueID()) == source,
                "projection departure first asks consent without changing the active owner");
            action = (NBTTagCompound) source.pendingSourceAction.copy();
            action.setBoolean("confirmDiscard", true);
            action.setLong("uiSequence", sequence);
            source.actionCount = 0;
        }
        HologramActionDispatcher.handle(source.player, action);
        HologramSession target = HologramWorkspace.find(source.player.getUniqueID());
        SimpleAssert.that(
            target != source && mode.equals(HologramEditorWorkspaceRegistry.mode(target)),
            "actual dispatcher switches to " + mode + ": " + source.status);
        return target;
    }

    private static NBTTagCompound baseline(HologramSession target) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("session", target.id);
        tag.setLong("uiSequence", target.uiSequence);
        tag.setLong("editorWorkspaceEpoch", target.editorWorkspaceEpoch);
        tag.setTag("cells", new NBTTagList());
        return tag;
    }

    private static void commit(HologramSession target) {
        HologramWorkspace.commitPresentation(target, baseline(target));
        SimpleAssert.that(target.presentationTransition == null, "accepted complete baseline commits new domain");
    }

    private static final class Fixture implements AutoCloseable {

        final sun.misc.Unsafe unsafe;
        final UUID id = UUID.randomUUID();
        final HologramServiceEditRegression.ReadOnlyWorld world;
        final EntityPlayerMP player;

        Fixture() throws ReflectiveOperationException {
            HologramWorkspace.clear();
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            unsafe = (sun.misc.Unsafe) singleton.get(null);
            world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            world.occupiedX = 0;
            world.occupant = new TileEntity();
            player = newPlayer(id);
        }

        EntityPlayerMP newPlayer(UUID owner) throws ReflectiveOperationException {
            EntityPlayerMP actor = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            for (Field field : EntityPlayer.class.getDeclaredFields()) if (field.getType() == GameProfile.class) {
                field.setAccessible(true);
                field.set(actor, new GameProfile(owner, "WorkspaceFixture"));
            }
            for (Field field : Entity.class.getDeclaredFields()) if (field.getType() == UUID.class) {
                field.setAccessible(true);
                field.set(actor, owner);
            }
            actor.worldObj = world;
            actor.posY = 64;
            actor.inventory = new InventoryPlayer(actor);
            actor.capabilities = new PlayerCapabilities();
            actor.capabilities.allowEdit = true;
            actor.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            return actor;
        }

        HologramSession projection() {
            HologramSession session = HologramWorkspace.create(player, 0, 64, 0, true, 2);
            session.materialSettings.me = false;
            session.materialSettings.containers = false;
            HologramWorkspace.activate(id, session);
            SimpleAssert.eq("projection", HologramEditorWorkspaceRegistry.mode(session), "real controller domain");
            return session;
        }

        @Override
        public void close() {
            player.worldObj = world;
            HologramWorkspace.clear();
        }
    }
}
