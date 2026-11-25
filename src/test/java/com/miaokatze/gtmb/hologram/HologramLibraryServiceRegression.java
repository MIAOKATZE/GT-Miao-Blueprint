// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/** Production admission and private draft lookup, using the existing world fixture that forbids writes. */
final class HologramLibraryServiceRegression {

    @SuppressWarnings("unchecked")
    static void run() {
        Map<UUID, Object> sessions = null;
        UUID ownerId = UUID.fromString("2fa98e10-25fd-4eb6-b33f-23595ace9479");
        Object previous = null;
        EntityPlayerMP owner = null, other = null, reconnect = null;
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            owner = player(unsafe, world, ownerId, "LibraryFixture");
            other = player(unsafe, world, UUID.randomUUID(), "OtherFixture");
            reconnect = player(unsafe, world, ownerId, "LibraryFixture");
            Method authenticated = HologramLibraryService.class
                .getDeclaredMethod("connectedProjector", EntityPlayerMP.class);
            authenticated.setAccessible(true);
            SimpleAssert
                .that((Boolean) authenticated.invoke(null, owner), "connected server projector fixture authenticated");
            Class<?> type = Class.forName("com.miaokatze.gtmb.hologram.HologramSession");
            Constructor<?> ctor = type.getDeclaredConstructor(
                EntityPlayerMP.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                int.class);
            ctor.setAccessible(true);
            Object session = ctor.newInstance(owner, 0, 64, 0, false, 2);
            set(session, "open", true);
            set(session, "planRevision", 42L);
            Field map = HologramWorkspace.class.getDeclaredField("SESSIONS");
            map.setAccessible(true);
            sessions = (Map<UUID, Object>) map.get(null);
            previous = sessions.put(ownerId, session);
            NBTTagCompound request = new NBTTagCompound();
            request.setString("session", (String) get(session, "id"));
            request.setLong("planRevision", 42L);
            // The asynchronous publication task consumes this header on its first budgeted step.
            // Exercise that production boundary without requiring a Forge packet channel in this admission fixture.
            NBTTagCompound header = HologramSnapshots.basicState((HologramSession) session);
            SimpleAssert.that(header.getBoolean("open"), "prepared header carries the one-shot UI open intent");
            SimpleAssert.that(
                !HologramSnapshots.basicState((HologramSession) session)
                    .getBoolean("open"),
                "a second header cannot reopen the same UI intent");
            SimpleAssert.that(!(Boolean) get(session, "open"), "real snapshot consumes one-shot UI open intent");
            SimpleAssert
                .that((Boolean) get(session, "libraryUiActive"), "snapshot retains independent active UI capability");
            SimpleAssert.that(HologramService.librarySessionCurrent(owner, request), "current request admitted");
            owner.inventory.currentItem = 1;
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, request), "switched held tool rejects transfer");
            SimpleAssert
                .that(!(Boolean) authenticated.invoke(null, owner), "held stone cannot authenticate projector upload");
            owner.inventory.currentItem = 0;
            SimpleAssert.that(!HologramService.librarySessionCurrent(other, request), "other UUID cannot activate");
            SimpleAssert.that(
                !HologramService.librarySessionCurrent(reconnect, request),
                "reconnected player object cannot reuse old transfer");
            request.setLong("planRevision", 41L);
            SimpleAssert.that(!HologramService.librarySessionCurrent(owner, request), "old plan cannot activate");
            request.setLong("planRevision", 42L);
            String saveReceipt = UUID.randomUUID()
                .toString();
            String savedLocal = "local_" + UUID.randomUUID();
            String jsonSha = HologramBlueprintArtifact.hash(new byte[] { 1, 2, 3 });
            String previewSha = HologramBlueprintArtifact.hash(new byte[] { 4, 5, 6 });
            NBTTagCompound saveRequest = (NBTTagCompound) request.copy();
            saveRequest.setString("saveReceipt", saveReceipt);
            saveRequest.setString("replaceLocalId", savedLocal);
            set(session, "blueprintSaveReceipt", saveReceipt);
            set(session, "blueprintSaveLocalId", savedLocal);
            set(session, "blueprintSaveRevision", 42L);
            set(session, "blueprintDirty", true);
            SimpleAssert.that(
                HologramService.librarySaveCurrent(owner, saveRequest),
                "native artifact save capability is owner, source revision, and local ID bound");
            owner.inventory.currentItem = 1;
            set(session, "libraryUiActive", false);
            SimpleAssert.that(
                HologramLibraryService.saveCurrent(owner, saveRequest)
                    && HologramSessionPolicy.tickValid((HologramSession) session),
                "admitted save continues in the background with its original tool in the original slot");
            ItemStack originalTool = owner.inventory.mainInventory[0];
            owner.inventory.mainInventory[0] = null;
            SimpleAssert.that(
                !HologramLibraryService.saveCurrent(owner, saveRequest),
                "removing original tool revokes the save");
            owner.inventory.mainInventory[0] = originalTool;
            NBTTagCompound changed = (NBTTagCompound) saveRequest.copy();
            changed.setLong("planRevision", 43L);
            SimpleAssert.that(
                !HologramLibraryService.saveCurrent(owner, changed),
                "background save cannot accept a changed plan");
            owner.inventory.currentItem = 0;
            set(session, "libraryUiActive", true);
            SimpleAssert.that(
                !HologramService.librarySaveCurrent(other, saveRequest),
                "other actor cannot submit a saved preview for the owner");
            SimpleAssert.that(
                !HologramService.librarySaveCurrent(reconnect, saveRequest),
                "reconnected actor object cannot reuse preview save capability");
            SimpleAssert.that(
                HologramService.libraryDraftPrepared(owner, saveRequest, jsonSha),
                "server records the exact pre-transfer JSON SHA under its save nonce");
            NBTTagCompound acknowledgement = (NBTTagCompound) saveRequest.copy();
            acknowledgement.setString("op", "ackBlueprintSaved");
            acknowledgement.setString("localId", savedLocal);
            acknowledgement.setString("jsonSha256", previewSha);
            acknowledgement.setString("previewSha256", previewSha);
            HologramService.handle(owner, acknowledgement);
            SimpleAssert.that(
                (Boolean) get(session, "blueprintDirty"),
                "ACK of rewritten JSON preserves the dirty source and pending nonce");
            acknowledgement.setString("jsonSha256", jsonSha);
            acknowledgement.setString("previewSha256", jsonSha);
            HologramService.handle(owner, acknowledgement);
            SimpleAssert
                .that((Boolean) get(session, "blueprintDirty"), "ACK of another PNG preserves the dirty source");
            acknowledgement.setString("previewSha256", previewSha);
            acknowledgement.setLong("planRevision", 41L);
            HologramService.handle(owner, acknowledgement);
            SimpleAssert.that(
                (Boolean) get(session, "blueprintDirty"),
                "old source revision cannot acknowledge a complete artifact");
            acknowledgement.setLong("planRevision", 42L);
            owner.inventory.currentItem = 1;
            NBTTagCompound backgroundClose = (NBTTagCompound) request.copy();
            backgroundClose.setString("op", "close");
            HologramService.handle(owner, backgroundClose);
            SimpleAssert.eq(
                saveReceipt,
                get(session, "blueprintSaveReceipt"),
                "closing presentation preserves exact admitted receipt");
            HologramService.handle(owner, acknowledgement);
            SimpleAssert.that(
                (Boolean) get(session, "blueprintDirty") && saveReceipt.equals(get(session, "blueprintSaveReceipt")),
                "even exact submitted hashes cannot commit without a local owner receipt");
            owner.inventory.currentItem = 0;
            set(session, "libraryUiActive", true);
            exerciseLocalReceipt(unsafe, owner, other, reconnect, session, request, savedLocal, jsonSha, previewSha);
            NBTTagCompound close = (NBTTagCompound) request.copy();
            close.setString("op", "close");
            HologramService.handle(owner, close);
            SimpleAssert.that(!HologramService.librarySessionCurrent(owner, request), "closed UI cannot reopen");
            set(session, "open", true);
            HologramSnapshots.basicState((HologramSession) session);
            SimpleAssert.that(
                HologramService.librarySessionCurrent(owner, request),
                "explicit reopen grants fresh UI capability");
            set(session, "capturePending", true);
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, request), "unfinished capture blocks transfer");
            set(session, "capturePending", false);
            set(session, "job", 1);
            SimpleAssert.that(!HologramService.librarySessionCurrent(owner, request), "construction blocks activation");
            set(session, "job", 0);
            HologramPresets.Preset preset = new HologramPresets.Preset();
            preset.schema = 2;
            preset.id = "private_" + UUID.randomUUID();
            preset.name = "Owner's stage";
            preset.authorName = "Imported creator";
            preset.authorUuid = UUID.randomUUID()
                .toString();
            preset.createdAt = 123;
            preset.controllerType = "area";
            preset.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
            set(session, "libraryPrivatePreset", preset);
            currentSaveUsesWorkspaceIdentity((HologramSession) session, preset);
            savedEditorReprocessing((HologramSession) session, preset);
            SimpleAssert
                .that(HologramService.libraryPrivatePreset(owner, preset.id) != null, "owner reads private stage");
            SimpleAssert.that(
                HologramService.libraryPrivatePreset(other, preset.id) == null,
                "private stage cross-owner rejected");
            SimpleAssert.that(
                HologramService.libraryPrivatePreset(reconnect, preset.id) == null,
                "private stage exact player bound");
            owner.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            SimpleAssert.that(
                HologramService.libraryPrivatePreset(owner, preset.id) == null,
                "same dimension reloaded world cannot read old private stage");
            owner.worldObj = world;
            HologramPresets.Preset copy = HologramService.libraryPrivatePreset(owner, preset.id);
            copy.name = "Changed copy";
            SimpleAssert.eq("Owner's stage", preset.name, "lookup isolates temporary server draft");
            HologramService.handle(owner, close);
            SimpleAssert.that(
                HologramService.libraryPrivatePreset(owner, preset.id) == null,
                "explicit close revokes private stage lookup after the one-shot open intent was consumed");
            set(session, "open", true);
            HologramSnapshots.basicState((HologramSession) session);
            String originalAuthor = preset.authorUuid;
            HologramLibraryService.stampAuthor(preset, owner, false);
            SimpleAssert.eq(originalAuthor, preset.authorUuid, "update preserves imported creator UUID");
            SimpleAssert.eq(123L, preset.createdAt, "update preserves creation timestamp");
            HologramLibraryService.stampAuthor(preset, owner, true);
            SimpleAssert.eq(ownerId.toString(), preset.authorUuid, "save as stamps actual server actor UUID");
            SimpleAssert.eq("LibraryFixture", preset.authorName, "save as stamps actual profile name");
            SimpleAssert.that(preset.createdAt > 123, "save as establishes new creation time");
            Object replaced = ctor.newInstance(owner, 0, 64, 0, false, 2);
            set(replaced, "open", true);
            set(replaced, "planRevision", 42L);
            sessions.put(ownerId, replaced);
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, request), "new session invalidates old transfer");
            SimpleAssert
                .that(HologramService.libraryPrivatePreset(owner, preset.id) == null, "replacement drops old stage");
            NBTTagCompound bootstrap = new NBTTagCompound();
            bootstrap.setString(
                "quickToken",
                UUID.randomUUID()
                    .toString());
            bootstrap.setInteger("x", 0);
            bootstrap.setInteger("y", 0);
            bootstrap.setInteger("z", 0);
            bootstrap.setInteger("side", 1);
            NBTTagCompound ticket = HologramService.libraryQuickBootstrap(owner, bootstrap);
            Object quickSession = sessions.get(ownerId);
            SimpleAssert.that(!(Boolean) get(quickSession, "open"), "quick bootstrap creates no open UI");
            SimpleAssert
                .that(get(quickSession, "libraryPrivatePreset") == null, "bootstrap never reuses a private stage");
            ticket.setBoolean("quickPlace", true);
            ticket.setString("action", "place");
            SimpleAssert.that(
                HologramService.librarySessionCurrent(owner, ticket),
                "closed bootstrap accepts exact place token");
            ticket.setString("action", "edit");
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, ticket), "quick capability cannot open editor");
            ticket.setString("action", "place");
            String quickToken = ticket.getString("quickToken");
            ticket.setString(
                "quickToken",
                UUID.randomUUID()
                    .toString());
            SimpleAssert.that(!HologramService.librarySessionCurrent(owner, ticket), "wrong quick token rejected");
            ticket.setString("quickToken", quickToken);
            owner.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            SimpleAssert.that(
                !HologramService.librarySessionCurrent(owner, ticket),
                "same dimension different world rejects late transfer");
            owner.worldObj = world;
            HologramService.libraryQuickCancel(other, quickToken);
            SimpleAssert.that(
                HologramService.librarySessionCurrent(owner, ticket),
                "another player cannot cancel owner's quick token");
            HologramService.libraryQuickCancel(owner, quickToken);
            SimpleAssert
                .that(!HologramService.librarySessionCurrent(owner, ticket), "Escape revokes pending quick activation");
            retiredDraftSaveActions((HologramSession) quickSession);
            retiredPublicHydration(unsafe, (HologramSession) quickSession, sessions);
            SimpleAssert.that(!HologramLibraryFacade.enabled(), "headless/integrated path has no dedicated library");
            SimpleAssert.that(
                !HologramLibraryService.sessionCurrent(owner, request),
                "disabled transfer bridge rejects requests");
            owner.playerNetServerHandler.netManager.channel()
                .close();
            SimpleAssert.that(
                !(Boolean) authenticated.invoke(null, owner),
                "closed actual network channel rejects transfer auth");
            boolean invalid = false;
            try {
                HologramLibraryService.localId("../another-player", false);
            } catch (java.io.IOException expected) {
                invalid = true;
            }
            SimpleAssert.that(invalid, "client private filename cannot traverse directories");
            String publicId = "public_" + UUID.randomUUID();
            SimpleAssert.eq(
                publicId,
                HologramLibraryService.publicId(publicId),
                "public last selection accepts exact opaque public UUID");
            for (String wrongScope : new String[] { preset.id, "local_" + UUID.randomUUID(), "public_../other",
                "public_missing" }) {
                invalid = false;
                try {
                    HologramLibraryService.publicId(wrongScope);
                } catch (java.io.IOException expected) {
                    invalid = true;
                }
                SimpleAssert
                    .that(invalid, "public shortcut cannot read private/local or malformed identifier " + wrongScope);
            }
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("Library service regression", failure);
        } finally {
            close(owner);
            close(other);
            close(reconnect);
            if (sessions != null) {
                if (previous == null) sessions.remove(ownerId);
                else sessions.put(ownerId, previous);
            }
        }
    }

    private static void retiredDraftSaveActions(HologramSession session) throws ReflectiveOperationException {
        EntityPlayerMP player = session.player;
        EmbeddedChannel channel = (EmbeddedChannel) player.playerNetServerHandler.netManager.channel();
        Method dispatch = HologramNetwork.class
            .getDeclaredMethod("dispatch", EntityPlayerMP.class, NBTTagCompound.class);
        dispatch.setAccessible(true);
        channel.runPendingTasks();
        while (channel.readOutbound() != null) {}
        String receipt = session.blueprintSaveReceipt;
        long task = session.blueprintClientTaskToken, sequence = session.uiSequence, revision = session.planRevision;
        var capture = session.capture;
        var review = session.ae.review;
        var coverage = session.voucherCoverageTask;
        var savePrep = session.structureSavePreparation;
        boolean dirty = session.blueprintDirty;
        for (String op : new String[] { "savePreset", "saveBlueprint", "saveBlueprintAs", "saveBlueprintReplace",
            "updatePreset", "exportDraft", "confirmAeSave", "exportBlueprint" }) {
            // The integrated share export is genuinely local disk output, not the retired draft transport.
            if ("exportBlueprint".equals(op) && !HologramLibraryFacade.enabled()) continue;
            NBTTagCompound request = new NBTTagCompound();
            request.setString("op", op);
            request.setString("session", session.id);
            request.setLong("editorWorkspaceEpoch", session.editorWorkspaceEpoch);
            request.setLong("uiSequence", sequence + 1L);
            dispatchLegacyPublicAction(dispatch, player, request);
            assertPublicHydrationNotice(channel, "retired draft " + op);
            SimpleAssert.that(
                HologramWorkspace.find(player.getUniqueID()) == session && session.capture == capture
                    && session.ae.review == review
                    && session.voucherCoverageTask == coverage
                    && session.structureSavePreparation == savePrep
                    && session.blueprintSaveReceipt.equals(receipt)
                    && session.blueprintClientTaskToken == task
                    && session.uiSequence == sequence
                    && session.planRevision == revision
                    && session.blueprintDirty == dirty,
                op + " creates no preparation, save receipt, or dirty-state change");
        }
        try {
            HologramLibraryFacade.downloadDraft(
                player,
                new HologramPresets.Preset(),
                session.id,
                revision,
                "local_" + UUID.randomUUID(),
                "unissued",
                91L);
            throw new AssertionError("retired post-review port must never enqueue raw JSON");
        } catch (java.io.IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("未下载、未保存"),
                "post-review port refuses before reaching the server library in either mode");
        }
    }

    private static void retiredPublicHydration(sun.misc.Unsafe unsafe, HologramSession session,
        Map<UUID, Object> sessions) throws ReflectiveOperationException {
        EntityPlayerMP player = session.player;
        UUID owner = player.getUniqueID();
        String publicId = "public_" + UUID.randomUUID();
        Field global = MinecraftServer.class.getDeclaredField("mcServer");
        global.setAccessible(true);
        Object originalServer = global.get(null);
        Method dispatch = HologramNetwork.class
            .getDeclaredMethod("dispatch", EntityPlayerMP.class, NBTTagCompound.class);
        dispatch.setAccessible(true);
        EmbeddedChannel channel = (EmbeddedChannel) player.playerNetServerHandler.netManager.channel();
        SimpleAssert.that(
            sessions.get(owner) == session && !session.cancelled && channel.isOpen(),
            "public refusal fixture starts with the authenticated current session and open transport");
        channel.runPendingTasks();
        while (channel.readOutbound() != null) {}
        long sequence = session.uiSequence, resync = session.snapshotResyncTick;
        HologramPresets.Preset originalPreset = session.virtualPreset;
        HologramCapture originalCapture = session.capture;
        boolean originalOpen = session.open, originalInitial = session.libraryInitialSnapshot;
        String originalStatus = session.status, originalPurpose = session.openPurpose;
        ItemStack tool = player.getHeldItem();
        NBTTagCompound originalTool = (NBTTagCompound) tool.getTagCompound()
            .copy();
        try {
            global.set(null, unsafe.allocateInstance(DedicatedServer.class));
            SimpleAssert.that(HologramLibraryFacade.enabled(), "fixture uses the actual dedicated public mode");
            retiredDraftSaveActions(session);
            retiredReadKeepsRemovedProjection(
                unsafe,
                dispatch,
                sessions,
                (HologramServiceEditRegression.ReadOnlyWorld) player.worldObj,
                publicId);
            for (String op : new String[] { "previewPreset", "editPreset", "choosePlacementPreset",
                "requestFullSnapshot" }) {
                if ("requestFullSnapshot".equals(op)) {
                    HologramPresets.Preset remote = new HologramPresets.Preset();
                    remote.id = publicId;
                    session.virtualPreset = remote;
                }
                HologramPresets.Preset before = session.virtualPreset;
                NBTTagCompound request = new NBTTagCompound();
                request.setString("op", op);
                request.setString("session", session.id);
                if (!"requestFullSnapshot".equals(op)) request.setString("presetId", publicId);
                request.setLong("uiSequence", sequence + 1L);
                dispatchLegacyPublicAction(dispatch, player, request);
                assertPublicHydrationNotice(channel, op);
                SimpleAssert.that(
                    sessions.get(owner) == session && session.capture == originalCapture
                        && session.virtualPreset == before
                        && session.uiSequence == sequence
                        && session.snapshotResyncTick == resync
                        && session.open == originalOpen
                        && session.openPurpose.equals(originalPurpose)
                        && session.status.equals(originalStatus)
                        && session.libraryInitialSnapshot == originalInitial,
                    op + " leaves current presentation, plan and full-resync watermark untouched");
            }

            // Direct compatibility producer is fenced too, not merely one network opcode.
            session.virtualPreset = originalPreset;
            HologramPresetWorkflow.loadPreset(session, publicId, false);
            SimpleAssert.that(
                session.virtualPreset == originalPreset && session.capture == originalCapture
                    && session.status.contains("手动下载"),
                "direct full public load fails before opening the server library");
            session.status = originalStatus;

            sessions.remove(owner);
            NBTTagCompound quick = new NBTTagCompound();
            quick.setString("action", "quickPlaceLastBlueprint");
            quick.setString("publicPresetId", publicId);
            dispatchLegacyPublicAction(dispatch, player, quick);
            assertPublicHydrationNotice(channel, "quick explicit public source");
            SimpleAssert
                .that(sessions.get(owner) == null, "quick public request does not create or activate a workspace");

            quick.removeTag("publicPresetId");
            tool.getTagCompound()
                .setString(HologramSessionKeys.SELECTED_PRESET, publicId);
            dispatchLegacyPublicAction(dispatch, player, quick);
            assertPublicHydrationNotice(channel, "quick remembered public source");
            SimpleAssert.that(sessions.get(owner) == null, "remembered public id cannot reopen the legacy transfer");
        } finally {
            sessions.put(owner, session);
            tool.setTagCompound(originalTool);
            session.virtualPreset = originalPreset;
            session.status = originalStatus;
            global.set(null, originalServer);
        }
    }

    private static void retiredReadKeepsRemovedProjection(sun.misc.Unsafe unsafe, Method dispatch,
        Map<UUID, Object> sessions, HologramServiceEditRegression.ReadOnlyWorld world, String publicId)
        throws ReflectiveOperationException {
        UUID id = UUID.randomUUID();
        EntityPlayerMP player = player(unsafe, world, id, "RetiredHydrationProjection");
        EmbeddedChannel channel = (EmbeddedChannel) player.playerNetServerHandler.netManager.channel();
        try {
            HologramSession projection = new HologramSession(player, 0, 64, 0, false, 2);
            projection.target = new net.minecraft.tileentity.TileEntity();
            projection.target.invalidate();
            HologramEditorWorkspaceRegistry.activated(projection);
            sessions.put(id, projection);
            SimpleAssert.that(
                HologramEditorWorkspaceRegistry.physicalProjection(projection)
                    && HologramSessionPolicy.controllerReplaced(projection),
                "retained fixture really is a prune-eligible removed controller");
            long loadReads = world.loadReads;

            NBTTagCompound preview = new NBTTagCompound();
            preview.setString("op", "previewPreset");
            preview.setString("presetId", publicId);
            preview.setString("session", "wrong-session");
            dispatchLegacyPublicAction(dispatch, player, preview);
            preview.setString("session", projection.id);
            preview.setLong("editorWorkspaceEpoch", projection.editorWorkspaceEpoch + 1L);
            dispatchLegacyPublicAction(dispatch, player, preview);
            channel.runPendingTasks();
            SimpleAssert.that(
                channel.readOutbound() == null && sessions.get(id) == projection && world.loadReads == loadReads,
                "wrong session or epoch is silent and cannot trigger retirement");

            preview.setLong("editorWorkspaceEpoch", projection.editorWorkspaceEpoch);
            dispatchLegacyPublicAction(dispatch, player, preview);
            assertPublicHydrationNotice(channel, "owned public switch with removed controller");
            SimpleAssert.that(
                sessions.get(id) == projection && !projection.cancelled && world.loadReads == loadReads,
                "refusal occurs before the removed-controller check");

            HologramSession editor = new HologramSession(player, 0, 64, 0, false, 2);
            editor.virtual = true;
            editor.virtualPreset = new HologramPresets.Preset();
            editor.virtualPreset.id = publicId;
            HologramEditorWorkspaceRegistry.activated(editor);
            sessions.put(id, editor);
            SimpleAssert.that(
                HologramEditorWorkspaceRegistry.retained(id, "projection") == projection,
                "inactive removed projection and current editor are independent retained states");
            loadReads = world.loadReads;
            NBTTagCompound resync = new NBTTagCompound();
            resync.setString("op", "requestFullSnapshot");
            resync.setString("session", editor.id);
            resync.setLong("editorWorkspaceEpoch", editor.editorWorkspaceEpoch);
            dispatchLegacyPublicAction(dispatch, player, resync);
            assertPublicHydrationNotice(channel, "public resync with inactive removed projection");
            SimpleAssert.that(
                sessions.get(id) == editor && !projection.cancelled && world.loadReads == loadReads,
                "retired public resync does not prune a different retained presentation");

            HologramWorkspace.pruneRemovedController(player);
            SimpleAssert.that(
                sessions.get(id) == editor && projection.cancelled
                    && HologramEditorWorkspaceRegistry.retained(id, "projection") == null,
                "ordinary maintenance can still retire the removed controller without touching the editor");
        } finally {
            sessions.remove(id);
            HologramEditorWorkspaceRegistry.forget(id);
            close(player);
        }
    }

    private static void dispatchLegacyPublicAction(Method dispatch, EntityPlayerMP player, NBTTagCompound request)
        throws ReflectiveOperationException {
        ByteBuf wire = Unpooled.buffer();
        try {
            HologramNetwork.Action sent = new HologramNetwork.Action();
            sent.tag = (NBTTagCompound) request.copy(); // An older registered Action without capability-advertisement.
            sent.toBytes(wire);
            HologramNetwork.Action decoded = new HologramNetwork.Action();
            decoded.fromBytes(wire);
            dispatch.invoke(null, player, decoded.tag);
        } finally {
            wire.release();
        }
    }

    private static void assertPublicHydrationNotice(EmbeddedChannel channel, String request) {
        channel.runPendingTasks();
        SimpleAssert.that(
            channel.readOutbound() instanceof S02PacketChat && channel.readOutbound() == null,
            request + " receives only the ordinary refusal notice, not a reconstructed workspace");
    }

    private static void currentSaveUsesWorkspaceIdentity(HologramSession session, HologramPresets.Preset source)
        throws java.io.IOException {
        boolean wasVirtual = session.virtual, wasPlacement = session.virtualPlacement;
        String previousPurpose = session.openPurpose, previousSource = session.blueprintSource,
            previousLocal = session.libraryLocalId, previousTab = session.workspaceTab;
        HologramPresets.Preset previousPreset = session.virtualPreset;
        String local = "local_" + UUID.randomUUID();
        session.virtual = true;
        session.openPurpose = "blueprintEdit";
        session.blueprintSource = "saved";
        session.virtualPreset = HologramPresets.copy(source);
        session.libraryLocalId = local;
        NBTTagCompound opened = HologramSnapshots.basicState(session);
        SimpleAssert.eq(
            "blueprintEdit",
            opened.getString("openPurpose"),
            "the real snapshot publishes the initial blueprint opening intent");
        SimpleAssert.eq("", session.openPurpose, "the real snapshot consumes that opening intent exactly once");
        SimpleAssert.eq(
            local,
            HologramLibraryWorkflow.currentBlueprintSaveId(session, true),
            "dedicated private edits write back the loaded local file even when its author is someone else");
        HologramSnapshots.basicState(session);
        SimpleAssert.eq(
            local,
            HologramLibraryWorkflow.currentBlueprintSaveId(session, true),
            "a later snapshot cannot turn current-file saving into another Save As");
        session.virtualPreset.authorUuid = null;
        session.virtualPreset.processingNbt = null;
        SimpleAssert.eq(
            local,
            HologramLibraryWorkflow.currentBlueprintSaveId(session, true),
            "a legacy imported local file retains its original editable identity without author-based restrictions");
        session.virtualPreset.id = "public_" + UUID.randomUUID();
        SimpleAssert.eq(
            "",
            HologramLibraryWorkflow.currentBlueprintSaveId(session, true),
            "public sources cannot reuse a stale local selection as their replacement target");
        HologramPresets.Preset publicCopy = HologramPresets.copy(source);
        HologramLibraryWorkflow.stampSavedAuthor(session, publicCopy, "saveBlueprint", false);
        SimpleAssert.eq(
            source.authorUuid,
            publicCopy.authorUuid,
            "quick saving a public source as a new private copy preserves its creator UUID");
        SimpleAssert.eq(
            source.createdAt,
            publicCopy.createdAt,
            "a public source copy preserves the original provenance timestamp");
        session.virtualPreset.id = "private_" + UUID.randomUUID();
        SimpleAssert.eq(
            "",
            HologramLibraryWorkflow.currentBlueprintSaveId(session, true),
            "a different current private draft cannot replace the previous source file");
        session.virtualPreset = HologramPresets.copy(source);
        session.blueprintSource = "imported";
        SimpleAssert.eq(
            "",
            HologramLibraryWorkflow.currentBlueprintSaveId(session, true),
            "an unsaved import cannot inherit the previous file target");
        session.blueprintSource = "saved";
        session.virtualPlacement = true;
        SimpleAssert.eq(
            "",
            HologramLibraryWorkflow.currentBlueprintSaveId(session, true),
            "a placement selection is not an editable save target");
        session.virtualPlacement = wasPlacement;
        session.virtual = wasVirtual;
        session.openPurpose = previousPurpose;
        session.blueprintSource = previousSource;
        session.virtualPreset = previousPreset;
        session.libraryLocalId = previousLocal;
        session.workspaceTab = previousTab;
    }

    private static void savedEditorReprocessing(HologramSession previous, HologramPresets.Preset source)
        throws java.io.IOException {
        EntityPlayerMP player = previous.player;
        double y = player.posY;
        ItemStack inventory = player.inventory.mainInventory[1];
        HologramPresets.Preset staged = previous.libraryPrivatePreset;
        HologramSession editing = null;
        try {
            player.posY = previous.y;
            HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
            policy.autoHints = "none";
            policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
            previous.libraryPrivatePreset = HologramBlueprintProcessing
                .process(HologramPresets.copy(source), policy, previous.dimension, previous.x, previous.y, previous.z);
            editing = HologramPresetWorkflow.editPreset(previous, source.id);
            SimpleAssert.that(
                editing != previous && editing.blueprintEditing,
                "successful saved edit owns explicit editing state");
            NBTTagCompound opened = HologramSnapshots.basicState(editing);
            SimpleAssert
                .eq("blueprintEdit", opened.getString("openPurpose"), "saved edit publishes its opening intent");
            NBTTagCompound later = HologramSnapshots.basicState(editing);
            SimpleAssert.eq("", later.getString("openPurpose"), "later snapshots consume no second opening intent");
            SimpleAssert.that(
                later.getBoolean("blueprintEditing"),
                "saved edit remains processable after open intent consumption");
            NBTTagCompound preview = new NBTTagCompound();
            preview.setString("op", "previewPreset");
            preview.setString("session", editing.id);
            preview.setString("presetId", source.id);
            HologramActionDispatcher.handle(player, preview);
            HologramSession browsing = HologramWorkspace.find(player.getUniqueID());
            SimpleAssert.that(
                browsing != editing && !browsing.blueprintEditing,
                "explicit preview cannot inherit saved editing state");
            NBTTagCompound browseSnapshot = HologramSnapshots.basicState(browsing);
            SimpleAssert.that(
                browsing.libraryUiActive && !browseSnapshot.getBoolean("blueprintEditing"),
                "the published preview grants private-library UI access without author processing access");
            HologramSnapshotPreparation.cancel(browsing);
            editing = HologramPresetWorkflow.editPreset(browsing, source.id);
            SimpleAssert.that(
                editing != browsing && editing.blueprintEditing,
                "returning through Edit grants explicit author processing again");
            player.inventory.mainInventory[1] = new ItemStack(Blocks.dirt, 32);
            editing.materialSettings.containers = editing.materialSettings.me = false;
            NBTTagCompound pin = new NBTTagCompound();
            pin.setString("op", "customPin");
            pin.setString("session", editing.id);
            pin.setLong("planRevision", editing.planRevision);
            pin.setInteger("selected", 0);
            pin.setInteger("inventorySlot", 1);
            HologramActionDispatcher.handle(player, pin);
            SimpleAssert.eq(
                32,
                player.inventory.mainInventory[1].stackSize,
                "inventory sampling never pays construction material");
            SimpleAssert.eq(
                Blocks.dirt,
                editing.capture.cells.get(0).block,
                "actual inventory action updates the editable target");
            NBTTagCompound process = new NBTTagCompound();
            process.setLong("planRevision", editing.planRevision);
            HologramAuthoringWorkflow.processingAction(editing, process, "beginBlueprintReprocessing");
            SimpleAssert.that(
                editing.processingWorkflowStarted,
                "saved edit can restart processing against its selected target");
            process.setLong("planRevision", editing.planRevision);
            process.setTag("policy", policy.write());
            HologramAuthoringWorkflow.processingAction(editing, process, "processBlueprint");
            SimpleAssert.that(editing.processingReviewed, "saved edit can complete processing again");
            HologramAuthoringWorkflow.validateProcessedSaveOptions(editing, new NBTTagCompound());
            HologramPresets.Preset save = HologramBlueprintProjection.blueprint(editing, "Reviewed inventory edit");
            HologramPresets.validate(save);
            SimpleAssert.eq(
                "minecraft:dirt",
                save.cells.get(0).block,
                "the reviewed save carries the selected inventory material");
            editing.virtualPlacement = true;
            SimpleAssert.that(
                !HologramSnapshots.basicState(editing)
                    .getBoolean("blueprintEditing"),
                "world placement masks processing access even when started from an editor");
        } finally {
            if (editing != null && editing != previous) HologramSnapshotPreparation.cancel(editing);
            previous.libraryPrivatePreset = staged;
            player.inventory.mainInventory[1] = inventory;
            player.posY = y;
            HologramWorkspace.activate(player.getUniqueID(), previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static void retiredDraftBypassesFullReceive(sun.misc.Unsafe unsafe, EntityPlayerMP owner,
        NBTTagCompound activeProof, EmbeddedChannel connection) throws ReflectiveOperationException {
        Field global = MinecraftServer.class.getDeclaredField("mcServer");
        global.setAccessible(true);
        Object originalServer = global.get(null);
        Field ingress = HologramLibraryFacade.class.getDeclaredField("inbound");
        ingress.setAccessible(true);
        java.util.Queue<Object> queue = (java.util.Queue<Object>) ingress.get(null);
        Field peers = HologramLibraryFacade.class.getDeclaredField("pending");
        peers.setAccessible(true);
        Map<UUID, HologramLibraryProtocol.RequestQuota> pending = (Map<UUID, HologramLibraryProtocol.RequestQuota>) peers
            .get(null);
        Field globalBudget = HologramLibraryFacade.class.getDeclaredField("inboundQuota");
        globalBudget.setAccessible(true);
        HologramLibraryProtocol.RequestQuota total = (HologramLibraryProtocol.RequestQuota) globalBudget.get(null);
        Field busyReplies = HologramBusyReplies.class.getDeclaredField("pending");
        busyReplies.setAccessible(true);
        Map<EntityPlayerMP, Object> busy = (Map<EntityPlayerMP, Object>) busyReplies.get(null);
        Field noticesField = HologramLibraryFacade.class.getDeclaredField("retiredDraftNotices");
        noticesField.setAccessible(true);
        Map<UUID, Object> notices = (Map<UUID, Object>) noticesField.get(null);
        UUID actor = HologramLibraryFacade.actorId(owner);
        SimpleAssert.that(
            !pending.containsKey(actor) && !notices.containsKey(actor),
            "staged receipt fixture starts without any unowned inbound requests");
        Object previousBusy = busy.remove(owner);
        java.util.List<Object> originalQueue = new java.util.ArrayList<>(queue);
        try {
            global.set(null, unsafe.allocateInstance(DedicatedServer.class));
            for (int i = 0; i < HologramLibraryProtocol.CONTROL_REQUESTS; i++)
                HologramLibraryFacade.receive(owner, HologramLibraryProtocol.request("list"));
            HologramLibraryProtocol.RequestQuota full = pending.get(actor);
            int occupied = originalQueue.size() + HologramLibraryProtocol.CONTROL_REQUESTS;
            SimpleAssert.eq(occupied, queue.size(), "actual receive fills the peer control mailbox");
            SimpleAssert.that(full != null && !full.acquire(false, false), "ordinary control ingress is at its limit");

            NBTTagCompound export = (NBTTagCompound) activeProof.copy();
            export.setString("op", "exportDraft");
            HologramLibraryFacade.receive(owner, export);
            HologramLibraryFacade.receive(owner, export);
            SimpleAssert.eq(occupied, queue.size(), "retired export neither evicts nor adds to the full real mailbox");
            SimpleAssert.that(
                pending.get(actor) == full && !busy.containsKey(owner) && notices.containsKey(actor),
                "the active disk token cannot leak into generic busy/error handling");
            Method flush = HologramLibraryFacade.class.getDeclaredMethod("flushRetiredDraftNotices");
            flush.setAccessible(true);
            flush.invoke(null);
            assertPublicHydrationNotice(connection, "coalesced retired export with an active positive disk token");
            SimpleAssert.that(
                !notices.containsKey(actor) && !busy.containsKey(owner),
                "only a chat notice was issued; no duplicate notice or save terminal remains");
        } finally {
            for (Object accepted : new java.util.ArrayList<>(queue))
                if (!originalQueue.contains(accepted) && queue.remove(accepted)) {
                    boolean chunk = (Boolean) get(accepted, "chunk");
                    var quota = (HologramLibraryProtocol.RequestQuota) get(accepted, "quota");
                    quota.release(chunk);
                    total.release(chunk);
                    if (quota.empty()) pending.remove(actor, quota);
                }
            notices.remove(actor);
            if (previousBusy == null) busy.remove(owner);
            else busy.put(owner, previousBusy);
            global.set(null, originalServer);
        }
    }

    @SuppressWarnings("unchecked")
    private static void exerciseLocalReceipt(sun.misc.Unsafe unsafe, EntityPlayerMP owner, EntityPlayerMP other,
        EntityPlayerMP reconnect, Object session, NBTTagCompound base, String local, String jsonHash, String pngHash)
        throws ReflectiveOperationException, java.io.IOException {
        Field resultsField = HologramLibraryFacade.class.getDeclaredField("savedResults");
        resultsField.setAccessible(true);
        Map<UUID, Object> results = (Map<UUID, Object>) resultsField.get(null);
        Object previous = results.remove(owner.getUniqueID());
        try {
            NBTTagCompound proof = (NBTTagCompound) base.copy();
            proof.setString(
                "saveReceipt",
                UUID.randomUUID()
                    .toString());
            proof.setString("localId", local);
            proof.setString("jsonSha256", jsonHash);
            proof.setString("previewSha256", "");
            proof.setBoolean("localSaveReceipt", true);
            proof.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            set(session, "blueprintSaveReceipt", proof.getString("saveReceipt"));
            set(session, "blueprintSaveLocalId", local);
            set(session, "blueprintSaveRevision", 42L);
            set(session, "blueprintSaveJsonSha256", jsonHash);
            set(session, "blueprintSavePreviewSha256", "");
            set(session, "blueprintDirty", true);
            Method remember = HologramLibraryFacade.class
                .getDeclaredMethod("rememberSavedResult", EntityPlayerMP.class, NBTTagCompound.class, byte[].class);
            remember.setAccessible(true);
            remember.invoke(null, owner, proof, (byte[]) null);
            Object activeResult = results.get(owner.getUniqueID());
            NBTTagCompound activeProof = (NBTTagCompound) get(activeResult, "proof");
            HologramSession current = (HologramSession) session;
            long existingTask = current.blueprintClientTaskToken;
            boolean hadTask = activeProof.hasKey("clientTaskToken");
            long savedTask = activeProof.getLong("clientTaskToken");
            // A replay matching the already-bound owner's token must not send a terminal library error.
            current.blueprintClientTaskToken = 79L;
            activeProof.setLong("clientTaskToken", 79L);
            try {
                EmbeddedChannel connection = (EmbeddedChannel) owner.playerNetServerHandler.netManager.channel();
                connection.runPendingTasks();
                while (connection.readOutbound() != null) {}
                NBTTagCompound repeatedSave = new NBTTagCompound();
                repeatedSave.setString("op", "saveBlueprintAs");
                repeatedSave.setString("session", current.id);
                repeatedSave.setLong("clientTaskToken", 79L);
                HologramService.handle(owner, repeatedSave);
                assertPublicHydrationNotice(connection, "retired draft replay during existing disk receipt");
                retiredDraftBypassesFullReceive(unsafe, owner, activeProof, connection);
                SimpleAssert.that(
                    results.get(owner.getUniqueID()) == activeResult
                        && current.blueprintSaveReceipt.equals(proof.getString("saveReceipt")),
                    "new draft refusal leaves the original staged receipt and its ledger intact");
            } finally {
                current.blueprintClientTaskToken = existingTask;
                if (hadTask) activeProof.setLong("clientTaskToken", savedTask);
                else activeProof.removeTag("clientTaskToken");
            }
            NBTTagCompound ack = (NBTTagCompound) proof.copy();
            ack.setString("op", "ackBlueprintSaved");
            ack.setString("previewSha256", pngHash);
            SimpleAssert.that(!HologramLibraryFacade.localSaveWritten(other, ack), "local receipt is actor bound");
            SimpleAssert
                .that(!HologramLibraryFacade.localSaveWritten(reconnect, ack), "reconnection cannot reuse receipt");
            for (String field : new String[] { "session", "saveReceipt", "localId", "jsonSha256", "previewSha256" }) {
                NBTTagCompound stale = (NBTTagCompound) ack.copy();
                stale.setString(field, "wrong");
                HologramService.handle(owner, stale);
                SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "wrong " + field + " keeps dirty draft");
                SimpleAssert.eq("", get(session, "blueprintSavePreviewSha256"), "rejected receipt cannot bind PNG");
            }
            NBTTagCompound stale = (NBTTagCompound) ack.copy();
            stale.setLong("planRevision", 41L);
            HologramService.handle(owner, stale);
            SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "old revision cannot commit local receipt");
            HologramSession saving = (HologramSession) session;
            boolean previousVirtual = saving.virtual, previousUiActive = saving.libraryUiActive;
            HologramPresets.Preset previousPreset = saving.virtualPreset, previousPrivate = saving.libraryPrivatePreset;
            HologramPresets.Preset receiptSource = new HologramPresets.Preset();
            receiptSource.id = "private_" + UUID.randomUUID();
            receiptSource.name = "Saved orientation receipt";
            receiptSource.controllerType = "area";
            receiptSource.schema = 2;
            receiptSource.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
            saving.virtual = true;
            saving.virtualPreset = HologramPresets.copy(receiptSource);
            saving.libraryPrivatePreset = receiptSource;
            saving.rotationTurns = 1;
            saving.rotationProof = new HologramBlueprintRotation.Proof(receiptSource.id, "old-source-hash", 0);
            saving.libraryUiActive = false;
            // No preview upload has armed a PNG hash: only this exact owner disk receipt binds it.
            HologramService.handle(owner, ack);
            SimpleAssert.that(
                !(Boolean) get(session, "blueprintDirty"),
                "local pair receipt commits through actual dispatcher");
            SimpleAssert.eq(pngHash, get(session, "blueprintSavePreviewSha256"), "owner PNG digest is bound once");
            SimpleAssert.eq(
                0,
                HologramBlueprintRotation.approvedTurns(saving, receiptSource),
                "the actual successful background disk receipt establishes a zero relative saved rotation");
            SimpleAssert.eq(
                1,
                saving.rotationProof.savedTurns,
                "successful save tracks the current pose without erasing the undo pose domain");
            saving.virtual = previousVirtual;
            saving.virtualPreset = previousPreset;
            saving.libraryPrivatePreset = previousPrivate;
            saving.libraryUiActive = previousUiActive;
            HologramBlueprintRotation.reset(saving);
            Object committed = results.get(owner.getUniqueID());
            HologramBusyReplies.rejectLibraryCallback(owner, ack);
            HologramBusyReplies.flush();
            SimpleAssert.that(
                !(Boolean) get(session, "blueprintDirty"),
                "callback congestion cannot dirty an already committed save");
            SimpleAssert
                .eq("saveCommitted", get(committed, "outcome"), "busy callback cannot rewrite committed outcome");
            SimpleAssert
                .eq("saveCommitted", get(committed, "outcome"), "result preserves committed outcome for lost replies");
            NBTTagCompound differentPng = (NBTTagCompound) ack.copy();
            differentPng.setString("previewSha256", jsonHash);
            SimpleAssert.that(
                !HologramLibraryFacade.localSaveWritten(owner, differentPng),
                "terminal receipt cannot rebind PNG");
            proof.setString(
                "saveReceipt",
                UUID.randomUUID()
                    .toString());
            set(session, "blueprintSaveReceipt", proof.getString("saveReceipt"));
            set(session, "blueprintSavePreviewSha256", "");
            set(session, "blueprintDirty", true);
            remember.invoke(null, owner, proof, (byte[]) null);
            NBTTagCompound canceled = (NBTTagCompound) proof.copy();
            canceled.setString("previewSha256", pngHash);
            HologramLibraryFacade.resolveSavedResult(owner, canceled, true);
            SimpleAssert.eq(
                "saveRejected",
                get(results.get(owner.getUniqueID()), "outcome"),
                "cancel before disk ACK resolves the pre-download result");
            canceled.setString("op", "ackBlueprintSaved");
            HologramService.handle(owner, canceled);
            SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "late canceled ACK retains unsaved workspace");
            proof.setString(
                "saveReceipt",
                UUID.randomUUID()
                    .toString());
            set(session, "blueprintSaveReceipt", proof.getString("saveReceipt"));
            remember.invoke(null, owner, proof, (byte[]) null);
            Field queueField = com.miaokatze.gtmb.util.ServerTaskScheduler.class.getDeclaredField("SERVER_TASKS");
            queueField.setAccessible(true);
            java.util.Queue<Runnable> tasks = (java.util.Queue<Runnable>) queueField.get(null);
            java.util.List<Runnable> previousTasks = new java.util.ArrayList<>();
            Runnable task;
            while ((task = tasks.poll()) != null) previousTasks.add(task);
            try {
                HologramLibraryFacade.draftPreparationFailed(owner, proof, "download admission failed");
                SimpleAssert.eq(
                    proof.getString("saveReceipt"),
                    get(session, "blueprintSaveReceipt"),
                    "worker admission failure waits for server-thread source revocation");
                tasks.remove()
                    .run();
                SimpleAssert
                    .eq("", get(session, "blueprintSaveReceipt"), "failed admission revokes exact owner source");
                Method prune = HologramLibraryFacade.class.getDeclaredMethod("pruneSavedResults");
                prune.setAccessible(true);
                prune.invoke(null);
                SimpleAssert.eq(
                    "saveRejected",
                    get(results.get(owner.getUniqueID()), "outcome"),
                    "same-owner prune retires prepublished result instead of leaking pending state");
                SimpleAssert
                    .that((Boolean) get(session, "blueprintDirty"), "admission failure retains workspace edits");
            } finally {
                tasks.clear();
                tasks.addAll(previousTasks);
            }
            proof.setString(
                "saveReceipt",
                UUID.randomUUID()
                    .toString());
            set(session, "blueprintSaveReceipt", proof.getString("saveReceipt"));
            set(session, "blueprintSaveJsonSha256", jsonHash);
            remember.invoke(null, owner, proof, (byte[]) null);
            NBTTagCompound wrongCallback = (NBTTagCompound) proof.copy();
            wrongCallback.setString("session", "old-session");
            HologramBusyReplies.rejectLibraryCallback(owner, wrongCallback);
            HologramBusyReplies.flush();
            SimpleAssert.eq(
                proof.getString("saveReceipt"),
                get(session, "blueprintSaveReceipt"),
                "rejected stale callback cannot revoke the current save source");
            HologramBusyReplies.rejectLibraryCallback(owner, proof);
            NBTTagCompound listRejection = new NBTTagCompound();
            listRejection.setString("op", "list");
            HologramBusyReplies.reject(owner, listRejection, HologramBusyReplies.Kind.LIBRARY);
            HologramBusyReplies.flush();
            SimpleAssert.eq(
                "",
                get(session, "blueprintSaveReceipt"),
                "exact callback rejection retires its save even when a later busy notice is coalesced");
            SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "callback rejection retains unsaved workspace");
            Method prune = HologramLibraryFacade.class.getDeclaredMethod("pruneSavedResults");
            prune.setAccessible(true);
            prune.invoke(null);
            SimpleAssert.eq(
                "saveRejected",
                get(results.get(owner.getUniqueID()), "outcome"),
                "rejected callback resolves prepublished save rather than leaving it pending");
            byte[] reviewed = " \n{\"blueprint\":{\"name\":\"binding_fixture\",\"cells\":[]}}\r\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String reviewedHash = HologramBlueprintArtifact.hash(reviewed);
            proof.setString(
                "saveReceipt",
                UUID.randomUUID()
                    .toString());
            proof.setString("jsonSha256", reviewedHash);
            proof.setString("previewSha256", "");
            set(session, "blueprintSaveReceipt", proof.getString("saveReceipt"));
            set(session, "blueprintSaveJsonSha256", reviewedHash);
            set(session, "blueprintSavePreviewSha256", "");
            set(session, "blueprintDirty", true);
            Method rememberSource = HologramLibraryFacade.class
                .getDeclaredMethod("rememberSavedResult", EntityPlayerMP.class, NBTTagCompound.class, byte[].class);
            rememberSource.setAccessible(true);
            rememberSource.invoke(null, owner, proof, reviewed);
            NBTTagCompound boundAck = (NBTTagCompound) proof.copy();
            boundAck.setString("op", "ackBlueprintSaved");
            boundAck.setString("reviewedJsonSha256", reviewedHash);
            boundAck.setString("previewSha256", pngHash);
            boundAck.setString("jsonSha256", HologramBlueprintPreviewBinding.boundJsonSha256(reviewed, pngHash));
            NBTTagCompound forged = (NBTTagCompound) boundAck.copy();
            forged.setString("jsonSha256", jsonHash);
            HologramService.handle(owner, forged);
            SimpleAssert.that(
                (Boolean) get(session, "blueprintDirty"),
                "matching review hash cannot authorize an unrelated claimed disk JSON hash");
            SimpleAssert.eq("", get(session, "blueprintSavePreviewSha256"), "forged bound ACK cannot bind preview");
            forged = (NBTTagCompound) boundAck.copy();
            forged.setString("reviewedJsonSha256", jsonHash);
            HologramService.handle(owner, forged);
            SimpleAssert.that((Boolean) get(session, "blueprintDirty"), "changed reviewed hash rejects bound disk ACK");
            HologramService.handle(owner, boundAck);
            SimpleAssert
                .that(!(Boolean) get(session, "blueprintDirty"), "actual bound JSON ACK commits through dispatcher");
            Object boundResult = results.get(owner.getUniqueID());
            NBTTagCompound acceptedProof = (NBTTagCompound) get(boundResult, "proof");
            SimpleAssert.eq(
                boundAck.getString("jsonSha256"),
                acceptedProof.getString("jsonSha256"),
                "commit reply retains exact complete disk JSON digest");
            SimpleAssert.eq(
                reviewedHash,
                acceptedProof.getString("reviewedJsonSha256"),
                "commit proof independently retains exact reviewed source digest");
            SimpleAssert
                .that(get(boundResult, "preparedJson") == null, "completed ACK releases pending prepared bytes");
        } finally {
            if (previous == null) results.remove(owner.getUniqueID());
            else results.put(owner.getUniqueID(), previous);
        }
    }

    private static EntityPlayerMP player(sun.misc.Unsafe unsafe, HologramServiceEditRegression.ReadOnlyWorld world,
        UUID id, String name) throws ReflectiveOperationException {
        return player(unsafe, world, id, name, EntityPlayerMP.class);
    }

    static EntityPlayerMP placementPlayer(sun.misc.Unsafe unsafe, HologramPlacementFixtureWorld world, String name)
        throws ReflectiveOperationException {
        return player(unsafe, world, UUID.randomUUID(), name, HologramNativeAdmissionRegression.PlacementPlayer.class);
    }

    private static EntityPlayerMP player(sun.misc.Unsafe unsafe, HologramServiceEditRegression.ReadOnlyWorld world,
        UUID id, String name, Class<? extends EntityPlayerMP> type) throws ReflectiveOperationException {
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(type);
        for (Field profile : EntityPlayer.class.getDeclaredFields()) if (profile.getType() == GameProfile.class) {
            profile.setAccessible(true);
            profile.set(player, new GameProfile(id, name));
        }
        // Unsafe skips Entity's UUID initializer; a profile alone does not initialize getUniqueID().
        for (Field identity : Entity.class.getDeclaredFields()) if (identity.getType() == UUID.class) {
            identity.setAccessible(true);
            identity.set(player, id);
        }
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.inventory.mainInventory[0] = new ItemStack(new ItemNekoHologramProjector());
        player.inventory.mainInventory[1] = new ItemStack(Blocks.stone);
        player.capabilities = new PlayerCapabilities();
        NetworkManager connection = new NetworkManager(false);
        for (Field channel : NetworkManager.class.getDeclaredFields())
            if (channel.getType() == io.netty.channel.Channel.class) {
                channel.setAccessible(true);
                channel.set(connection, new EmbeddedChannel(new ChannelInboundHandlerAdapter()));
            }
        new NetHandlerPlayServer(null, connection, player);
        return player;
    }

    private static void close(EntityPlayerMP player) {
        if (player != null && player.playerNetServerHandler != null) player.playerNetServerHandler.netManager.channel()
            .close();
    }

    private static void set(Object session, String name, Object value) throws ReflectiveOperationException {
        Field field = session.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private static Object get(Object session, String name) throws ReflectiveOperationException {
        Field field = session.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(session);
    }
}
